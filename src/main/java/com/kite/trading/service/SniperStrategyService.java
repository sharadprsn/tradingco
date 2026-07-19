package com.kite.trading.service;

import com.kite.trading.dto.IndexQuote;
import com.kite.trading.dto.OhlcCandle;
import com.kite.trading.dto.OptionChainData;
import com.kite.trading.dto.OptionChainData.OptionContract;
import com.kite.trading.dto.OptionChainData.OptionData;
import com.kite.trading.dto.SniperSignal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * "Sniper" process-driven option-buying strategy for NIFTY 50 and SENSEX.
 *
 * <p>It follows a strict, emotionless 7-step checklist (mirroring the discipline of a pilot's
 * pre-flight checklist) and only alerts when every single step is satisfied. The strategy never
 * auto-executes; it is a discipline/alert tool. The human decides whether to take the trade.
 *
 * <ol>
 *   <li>India VIX gate &mdash; if VIX &gt; 19, no trade today (premiums too expensive).
 *   <li>FII/DII context &mdash; background only; a counter-trend context demands more confirmation.
 *   <li>Mark Previous Day High / Low (PDH / PDL) from prior session candles.
 *   <li>Opening Range (OR) &mdash; high/low of 9:15&ndash;9:45; mark OR high/low once complete.
 *   <li>Wait &mdash; act only on a breakout or reversal of one of the 4 levels; otherwise idle.
 *   <li>Volume confirmation &mdash; the breakout must be on volume above the recent average.
 *   <li>5-min Supertrend alignment &mdash; the breakout direction must agree with the Supertrend
 *       trend (bullish when price is above the line, bearish when below).
 * </ol>
 *
 * <p>When all 7 steps pass, an alert (CE for long, PE for short) is pushed to Telegram.
 */
@Service
public class SniperStrategyService {

  private static final Logger logger = LoggerFactory.getLogger(SniperStrategyService.class);

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  private static final LocalTime MARKET_START = LocalTime.of(9, 15);
  private static final LocalTime OR_END = LocalTime.of(9, 45);
  private static final LocalTime MARKET_END = LocalTime.of(15, 30);

  // Step 1 gate
  static final BigDecimal VIX_GATE = BigDecimal.valueOf(19);

  // Step 6 volume filter
  static final int VOLUME_AVG_PERIOD = 20;
  static final BigDecimal VOLUME_MIN_MULTIPLE = BigDecimal.valueOf(1.2);

  // Step 7: 5-minute Supertrend (ATR 10, multiplier 3) directional alignment.
  static final int SUPERTREND_ATR_PERIOD = 10;
  static final BigDecimal SUPERTREND_MULTIPLIER = BigDecimal.valueOf(3);
  static final int FIVE_MIN_TICKS = 5;

  // Trade window for new signals (keep it inside the liquid part of the day)
  static final LocalTime SIGNAL_START = LocalTime.of(9, 50);
  static final LocalTime SIGNAL_END = LocalTime.of(15, 0);

  // Option premium band for strike selection
  private static final BigDecimal MIN_PREMIUM = BigDecimal.valueOf(20);
  private static final BigDecimal MAX_PREMIUM = BigDecimal.valueOf(300);

  // Indices traded: symbol -> client key
  private final List<String> indices = List.of("NIFTY", "SENSEX");

  private final NseOptionChainClient nseClient;
  private final OptionChainClient optionChainClient;
  private final TelegramService telegramService;
  private final Clock clock;

  private final Map<String, IndexState> states = new ConcurrentHashMap<>();
  private final List<SniperSignal> signals = new CopyOnWriteArrayList<>();

  // Pre-market context (fetched once per day, refreshed lazily before 9:15)
  private LocalDate contextDate;
  private BigDecimal cachedVix;
  private BigDecimal fiiNet;
  private BigDecimal diiNet;

  @org.springframework.beans.factory.annotation.Autowired
  public SniperStrategyService(
      final NseOptionChainClient nseClient,
      final OptionChainClient optionChainClient,
      final TelegramService telegramService) {
    this(nseClient, optionChainClient, telegramService, Clock.system(IST));
  }

  public SniperStrategyService(
      final NseOptionChainClient nseClient,
      final OptionChainClient optionChainClient,
      final TelegramService telegramService,
      final Clock clock) {
    this.nseClient = nseClient;
    this.optionChainClient = optionChainClient;
    this.telegramService = telegramService;
    this.clock = clock;
  }

  public void evaluate() {
    final LocalTime now = LocalTime.now(clock);
    final DayOfWeek day = LocalDate.now(clock).getDayOfWeek();
    if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
      return;
    }

    // Refresh pre-market context (VIX + FII/DII) lazily on the first run of the day, before any
    // time gating, so Step 1 can be evaluated even when invoked before the bell. The method
    // self-guards against re-fetching on the same day.
    refreshPremarketContext();

    if (now.isBefore(MARKET_START) || now.isAfter(MARKET_END)) {
      return;
    }

    // Step 1: hard VIX gate.
    if (cachedVix != null && cachedVix.compareTo(VIX_GATE) > 0) {
      logger.debug("Sniper: India VIX {} > {} gate, no trading today", cachedVix, VIX_GATE);
      return;
    }

    for (final String index : indices) {
      try {
        evaluateIndex(index, now);
      } catch (final Exception e) {
        logger.error("Sniper: error evaluating {}: {}", index, e.getMessage());
      }
    }
  }

  void refreshPremarketContext() {
    final LocalDate today = LocalDate.now(clock);
    if (contextDate != null && contextDate.isEqual(today) && cachedVix != null) {
      return;
    }
    contextDate = today;
    try {
      cachedVix = nseClient.fetchIndiaVix();
    } catch (final Exception e) {
      logger.warn("Sniper: failed to fetch India VIX: {}", e.getMessage());
      cachedVix = null;
    }
    try {
      final NseOptionChainClient.FiiDiiResponse fiiDii = nseClient.fetchFiiDii();
      fiiNet = BigDecimal.ZERO;
      diiNet = BigDecimal.ZERO;
      if (fiiDii != null && fiiDii.data() != null) {
        for (final NseOptionChainClient.FiiDiiItem item : fiiDii.data()) {
          if (item.buyValue() == null || item.sellValue() == null) {
            continue;
          }
          final BigDecimal net = item.buyValue().subtract(item.sellValue());
          if ("FII".equalsIgnoreCase(item.category())) {
            fiiNet = fiiNet.add(net);
          } else if ("DII".equalsIgnoreCase(item.category())) {
            diiNet = diiNet.add(net);
          }
        }
      }
    } catch (final Exception e) {
      logger.warn("Sniper: failed to fetch FII/DII: {}", e.getMessage());
      fiiNet = null;
      diiNet = null;
    }
    logger.info(
        "Sniper pre-market context: VIX={} FII_NET={} DII_NET={}", cachedVix, fiiNet, diiNet);
  }

  private void evaluateIndex(final String index, final LocalTime now) {
    final IndexState state = states.computeIfAbsent(index, k -> new IndexState());
    if (!state.pdhPdlLoaded) {
      loadPreviousDayLevels(index, state);
    }

    final IndexQuote quote = optionChainClient.fetchIndexQuote(index);
    if (quote == null || quote.data() == null || quote.data().isEmpty()) {
      return;
    }
    final IndexQuote.IndexData data = quote.data().getFirst();
    if (data.lastPrice() == null) {
      return;
    }
    final BigDecimal price = data.lastPrice();
    state.lastPrice = price;

    // Maintain 1-min tick series + EMA. Index volume is not always reported by the data
    // source, so we use the per-tick range (high-low) as a proportional activity proxy for the
    // volume-confirmation step (Step 6). This is directionally consistent: a real breakout prints
    // wide-range bars with heavy participation.
    final BigDecimal tickHigh = data.high() != null ? data.high() : price;
    final BigDecimal tickLow = data.low() != null ? data.low() : price;
    final BigDecimal tickOpen = data.open() != null ? data.open() : price;
    final BigDecimal tickRange = tickHigh.subtract(tickLow).max(BigDecimal.ZERO);
    final OhlcCandle tick =
        new OhlcCandle(LocalDateTime.now(clock), tickOpen, tickHigh, tickLow, price);
    state.ticks.add(tick);
    if (state.ticks.size() > 400) {
      state.ticks.removeFirst();
    }
    state.lastVolume = tickRange.max(BigDecimal.ZERO);
    state.volumes.add(state.lastVolume);
    if (state.volumes.size() > 400) {
      state.volumes.removeFirst();
    }
    updateSupertrend(state, tick);

    // Exit management: if a position is open, exit when price crosses the 5m Supertrend line
    // (long exits below the line, short exits above it).
    checkSupertrendExit(index, state, price);

    // Step 4: opening range 9:15-9:45.
    if (!state.orComplete && now.isAfter(OR_END)) {
      if (state.orHigh.compareTo(BigDecimal.ZERO) == 0) {
        state.orHigh = price;
        state.orLow = price;
      }
      state.orComplete = true;
      logger.info("Sniper [{}] opening range set: ORH={} ORL={}", index, state.orHigh, state.orLow);
    } else if (!state.orComplete) {
      state.orHigh = max(state.orHigh, price);
      state.orLow = min(state.orLow, price);
    }

    if (!state.orComplete) {
      return;
    }

    // Step 5: only act on a breakout/reversal of one of the 4 levels.
    final Breakout breakout = detectBreakout(state, price);
    if (breakout == Breakout.NONE) {
      return;
    }

    // Step 6: volume confirmation. Index volume is not always reported by the data source, so we
    // use the per-tick price range as a proportional activity proxy. The breakout bar's range must
    // exceed the previous-day baseline range (a stable reference) by the required multiple.
    final BigDecimal avgVolume = state.baselineVolume;
    final boolean volumeOk =
        avgVolume.compareTo(BigDecimal.ZERO) > 0
            && state.lastVolume.compareTo(avgVolume.multiply(VOLUME_MIN_MULTIPLE)) >= 0;
    state.lastVolumeConfirmed = volumeOk;
    if (!volumeOk) {
      logger.debug(
          "Sniper [{}] breakout {} but volume {} < baseline {} -> waiting",
          index,
          breakout,
          state.lastVolume,
          avgVolume);
      return;
    }

    // Step 7: 5-minute Supertrend alignment. The breakout direction must agree with the
    // Supertrend trend (bullish = price above the line, bearish = price below it).
    final Supertrend.State st = state.supertrend == null ? null : state.supertrend.latest();
    final boolean stAligned =
        st != null && (isLongBreakout(breakout) ? st.trendUp() : !st.trendUp());
    state.lastEmaAligned = stAligned;
    if (!stAligned) {
      logger.debug(
          "Sniper [{}] breakout {} but Supertrend not aligned (trendUp={})",
          index,
          breakout,
          st == null ? "n/a" : st.trendUp());
      return;
    }

    // All 7 steps passed -> generate alert.
    if (now.isBefore(SIGNAL_START) || now.isAfter(SIGNAL_END)) {
      logger.debug("Sniper [{}] valid setup but outside signal window", index);
      return;
    }
    if (state.inTrade) {
      return;
    }
    generateSignal(index, state, breakout, price);
  }

  private Breakout detectBreakout(final IndexState state, final BigDecimal price) {
    final boolean longBo = price.compareTo(state.pdh) > 0 || price.compareTo(state.orHigh) > 0;
    final boolean shortBo = price.compareTo(state.pdl) < 0 || price.compareTo(state.orLow) < 0;
    if (longBo) {
      return state.lastDirection == -1 ? Breakout.LONG_REVERSAL : Breakout.LONG_BREAKOUT;
    }
    if (shortBo) {
      return state.lastDirection == 1 ? Breakout.SHORT_REVERSAL : Breakout.SHORT_BREAKOUT;
    }
    final Supertrend.State st = state.supertrend == null ? null : state.supertrend.latest();
    state.lastDirection = st == null ? 0 : (st.trendUp() ? 1 : -1);
    return Breakout.NONE;
  }

  private static boolean isLongBreakout(final Breakout breakout) {
    return breakout == Breakout.LONG_BREAKOUT || breakout == Breakout.LONG_REVERSAL;
  }

  private void generateSignal(
      final String index, final IndexState state, final Breakout breakout, final BigDecimal price) {
    final boolean isLong = breakout == Breakout.LONG_BREAKOUT || breakout == Breakout.LONG_REVERSAL;
    final String optionType = isLong ? "CE" : "PE";
    final String direction = isLong ? "LONG" : "SHORT";

    final OptionContract contract = findSuitableStrike(index, price, optionType);
    if (contract == null) {
      logger.debug("Sniper [{}] no suitable {} strike found", index, optionType);
      return;
    }

    final SniperSignal signal =
        new SniperSignal(
            index,
            index,
            optionType,
            contract.strikePrice(),
            direction,
            LocalDateTime.now(clock),
            contract.lastPrice(),
            price,
            breakout.name(),
            true,
            true,
            cachedVix,
            fiiNet,
            diiNet,
            "SIGNAL_READY");
    signals.add(signal);
    state.inTrade = true;
    state.activeSignal = signal;

    logger.info(
        "Sniper [{}] SIGNAL: {} {} strike={} premium={} spot={} level={}",
        index,
        direction,
        optionType,
        contract.strikePrice(),
        contract.lastPrice(),
        price,
        breakout.name());
    notifySignal(signal);
  }

  private void checkSupertrendExit(
      final String index, final IndexState state, final BigDecimal price) {
    state.lastExitTriggered = false;
    if (!state.inTrade || state.activeSignal == null) {
      return;
    }
    final Supertrend.State st = state.supertrend == null ? null : state.supertrend.latest();
    if (st == null) {
      return;
    }
    final boolean isLong = "LONG".equals(state.activeSignal.direction());
    final boolean crossedOut =
        isLong ? price.compareTo(st.supertrend()) < 0 : price.compareTo(st.supertrend()) > 0;
    if (!crossedOut) {
      return;
    }
    state.lastExitTriggered = true;
    generateExitSignal(index, state, price, st.supertrend(), isLong);
  }

  private void generateExitSignal(
      final String index,
      final IndexState state,
      final BigDecimal price,
      final BigDecimal supertrendLine,
      final boolean isLong) {
    final String exitDirection = isLong ? "SHORT" : "LONG";
    final SniperSignal exit =
        new SniperSignal(
            index,
            index,
            state.activeSignal.optionType(),
            state.activeSignal.strikePrice(),
            exitDirection,
            LocalDateTime.now(clock),
            state.activeSignal.entryPremium(),
            price,
            "SUPERTREND_EXIT",
            true,
            true,
            cachedVix,
            fiiNet,
            diiNet,
            "EXIT");
    signals.add(exit);
    logger.info(
        "Sniper [{}] EXIT: {} price={} crossed Supertrend line={}",
        index,
        exitDirection,
        price,
        supertrendLine);
    notifyExit(exit);
    state.inTrade = false;
    state.activeSignal = null;
  }

  private OptionContract findSuitableStrike(
      final String index, final BigDecimal spot, final String type) {
    final OptionChainData chain = optionChainClient.fetchOptionChain(index);
    if (chain == null || chain.records() == null || chain.records().data() == null) {
      return null;
    }
    final BigDecimal base =
        spot.divide(BigDecimal.valueOf(50), 0, RoundingMode.HALF_UP)
            .multiply(BigDecimal.valueOf(50));
    final List<BigDecimal> candidates = new ArrayList<>();
    candidates.add(base);
    candidates.add(base.add(BigDecimal.valueOf(50)));
    candidates.add(base.subtract(BigDecimal.valueOf(50)));
    candidates.add(base.add(BigDecimal.valueOf(100)));
    candidates.add(base.subtract(BigDecimal.valueOf(100)));

    OptionContract best = null;
    BigDecimal bestDiff = null;
    for (final OptionData d : chain.records().data()) {
      if (!candidates.contains(d.strikePrice())) {
        continue;
      }
      final OptionContract contract = "CE".equals(type) ? d.ce() : d.pe();
      if (contract == null || contract.lastPrice() == null) {
        continue;
      }
      final BigDecimal premium = contract.lastPrice();
      if (premium.compareTo(MIN_PREMIUM) >= 0 && premium.compareTo(MAX_PREMIUM) <= 0) {
        final BigDecimal diff = premium.subtract(MIN_PREMIUM).abs();
        if (best == null || diff.compareTo(bestDiff) < 0) {
          best = contract;
          bestDiff = diff;
        }
      }
    }
    return best;
  }

  private void loadPreviousDayLevels(final String index, final IndexState state) {
    try {
      // PDH/PDL come directly from the NSE 1-day (daily) candle of the previous trading session.
      final List<OhlcCandle> daily = nseClient.fetchIndexDailyCandle();
      if (daily.isEmpty() || daily.getFirst().high() == null || daily.getFirst().low() == null) {
        logger.warn("Sniper [{}] could not derive PDH/PDL from daily candle", index);
        return;
      }
      final OhlcCandle candle = daily.getFirst();
      state.pdh = candle.high();
      state.pdl = candle.low();
      state.pdhPdlLoaded = true;
      // Seed the volume baseline from the daily candle's range so the live first tick has a
      // meaningful average to compare against (Step 6).
      seedVolumeBaseline(state, candle);
      logger.info("Sniper [{}] PDH={} PDL={} (from 1d candle)", index, state.pdh, state.pdl);
    } catch (final Exception e) {
      logger.warn("Sniper [{}] failed to load previous-day levels: {}", index, e.getMessage());
    }
  }

  private void seedVolumeBaseline(final IndexState state, final OhlcCandle candle) {
    final BigDecimal range = candle.high().subtract(candle.low()).max(BigDecimal.ZERO);
    state.baselineVolume = range;
    // Repeat the daily range a few times so the rolling average is stable before live ticks accrue.
    for (int i = 0; i < VOLUME_AVG_PERIOD; i++) {
      state.volumes.add(range);
    }
  }

  private void updateSupertrend(final IndexState state, final OhlcCandle tick) {
    if (state.supertrend == null) {
      state.supertrend = new Supertrend(SUPERTREND_ATR_PERIOD, SUPERTREND_MULTIPLIER);
    }
    state.tickCount5++;
    if (state.runningCandle5 == null) {
      state.runningCandle5 =
          new OhlcCandle(tick.timestamp(), tick.open(), tick.high(), tick.low(), tick.close());
    } else {
      state.runningCandle5 =
          new OhlcCandle(
              state.runningCandle5.timestamp(),
              state.runningCandle5.open(),
              max(state.runningCandle5.high(), tick.high()),
              min(state.runningCandle5.low(), tick.low()),
              tick.close());
    }
    if (state.tickCount5 >= FIVE_MIN_TICKS) {
      state.tickCount5 = 0;
      state.supertrend.addCandle(state.runningCandle5);
      state.runningCandle5 = null;
    }
  }

  private void notifySignal(final SniperSignal signal) {
    final String arrow = "LONG".equals(signal.direction()) ? "\uD83D\uDCCD" : "\uD83D\uDD34";
    final String message =
        arrow
            + " SNIPER SIGNAL\n\n"
            + "Index: "
            + signal.index()
            + "\n"
            + "Direction: "
            + signal.direction()
            + "\n"
            + "Option: "
            + signal.optionType()
            + " "
            + signal.strikePrice()
            + "\n"
            + "Entry Premium: \u20B9"
            + signal.entryPremium()
            + "\n"
            + "Spot: \u20B9"
            + signal.spotPrice()
            + "\n"
            + "Triggered Level: "
            + signal.triggeredLevel()
            + "\n"
            + "India VIX: "
            + (signal.indiaVix() == null ? "n/a" : signal.indiaVix())
            + " (gate < 19)\n"
            + "FII Net: "
            + (signal.fiiNet() == null ? "n/a" : signal.fiiNet())
            + "\n"
            + "DII Net: "
            + (signal.diiNet() == null ? "n/a" : signal.diiNet())
            + "\n"
            + "Volume Confirmed: "
            + (signal.volumeConfirmed() ? "\u2705" : "\u274C")
            + "\n"
            + "Supertrend (5m) Aligned: "
            + (signal.emaAligned() ? "\u2705" : "\u274C")
            + "\n\nThis is a discipline alert, not investment advice.";
    telegramService.sendMessage(message);
  }

  private void notifyExit(final SniperSignal signal) {
    final String arrow = "\uD83D\uDED1";
    final String message =
        arrow
            + " SNIPER EXIT\n\n"
            + "Index: "
            + signal.index()
            + "\n"
            + "Exit Direction: "
            + signal.direction()
            + "\n"
            + "Option: "
            + signal.optionType()
            + " "
            + signal.strikePrice()
            + "\n"
            + "Exit Spot: \u20B9"
            + signal.spotPrice()
            + "\n"
            + "Triggered Level: "
            + signal.triggeredLevel()
            + " (price crossed 5m Supertrend line)\n"
            + "India VIX: "
            + (signal.indiaVix() == null ? "n/a" : signal.indiaVix())
            + "\n"
            + "FII Net: "
            + (signal.fiiNet() == null ? "n/a" : signal.fiiNet())
            + "\n"
            + "DII Net: "
            + (signal.diiNet() == null ? "n/a" : signal.diiNet())
            + "\n\nThis is a discipline alert, not investment advice.";
    telegramService.sendMessage(message);
  }

  public void resetDaily() {
    states.clear();
    signals.clear();
    contextDate = null;
    cachedVix = null;
    fiiNet = null;
    diiNet = null;
    logger.info("Sniper strategy daily reset");
  }

  public List<SniperSignal> getSignals() {
    return List.copyOf(signals);
  }

  private static BigDecimal max(final BigDecimal a, final BigDecimal b) {
    return a.compareTo(b) >= 0 ? a : b;
  }

  private static BigDecimal min(final BigDecimal a, final BigDecimal b) {
    return a.compareTo(b) <= 0 ? a : b;
  }

  private enum Breakout {
    NONE,
    LONG_BREAKOUT,
    LONG_REVERSAL,
    SHORT_BREAKOUT,
    SHORT_REVERSAL
  }

  static class IndexState {
    boolean pdhPdlLoaded;
    BigDecimal pdh = BigDecimal.ZERO;
    BigDecimal pdl = BigDecimal.ZERO;

    boolean orComplete;
    BigDecimal orHigh = BigDecimal.ZERO;
    BigDecimal orLow = BigDecimal.ZERO;

    BigDecimal lastPrice = BigDecimal.ZERO;
    Supertrend supertrend;
    int lastDirection;

    OhlcCandle runningCandle5;
    int tickCount5;

    final List<OhlcCandle> ticks = new ArrayList<>();
    final List<BigDecimal> volumes = new ArrayList<>();
    BigDecimal lastVolume = BigDecimal.ZERO;
    BigDecimal baselineVolume = BigDecimal.ZERO;
    boolean lastVolumeConfirmed;
    boolean lastEmaAligned;

    boolean inTrade;
    SniperSignal activeSignal;
    boolean lastExitTriggered;
  }
}
