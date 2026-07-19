package com.kite.trading.service;

import com.kite.trading.dto.IndexQuote;
import com.kite.trading.dto.IndexQuote.IndexData;
import com.kite.trading.dto.MultiTfRSINiftySignal;
import com.kite.trading.dto.OhlcCandle;
import com.kite.trading.dto.OptionChainData;
import com.kite.trading.dto.OptionChainData.OptionContract;
import com.kite.trading.dto.OptionChainData.OptionData;
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
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class MultiTfRSINiftyOptionService {

  private static final Logger logger = LoggerFactory.getLogger(MultiTfRSINiftyOptionService.class);

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  private static final LocalTime MARKET_START = LocalTime.of(9, 15);
  private static final LocalTime TRADE_START = LocalTime.of(9, 30);
  private static final LocalTime TRADE_END = LocalTime.of(11, 30);
  private static final LocalTime MARKET_END = LocalTime.of(15, 30);

  static final int RSI_PERIOD = 14;
  static final int SMA_PERIOD = 20;
  private static final BigDecimal MIN_PREMIUM = BigDecimal.valueOf(60);
  private static final BigDecimal MAX_PREMIUM = BigDecimal.valueOf(100);
  static final int MAX_TRADES_PER_DAY = 2;

  // Risk-management bands (do not alter the RSI/SMA indicators themselves).
  static final BigDecimal RSI_LONG_MIN = BigDecimal.valueOf(40);
  static final BigDecimal RSI_LONG_MAX = BigDecimal.valueOf(70);
  static final BigDecimal RSI_SHORT_MIN = BigDecimal.valueOf(30);
  static final BigDecimal RSI_SHORT_MAX = BigDecimal.valueOf(60);
  static final BigDecimal STOP_PCT = BigDecimal.valueOf(0.0035);
  static final BigDecimal TARGET_PCT = BigDecimal.valueOf(0.0075);

  static final int FIVE_MIN_CANDLES = 5;
  static final int FIFTEEN_MIN_CANDLES = 3;

  private final NseOptionChainClient optionChainClient;
  private final CandlestickPatternService patternService;
  private final TelegramService telegramService;
  private final Clock clock;

  private final List<TfState> states = new CopyOnWriteArrayList<>();
  private final List<MultiTfRSINiftySignal> signals = new CopyOnWriteArrayList<>();
  private int longTradesToday;
  private int shortTradesToday;

  @Autowired
  public MultiTfRSINiftyOptionService(
      final NseOptionChainClient optionChainClient,
      final CandlestickPatternService patternService,
      final TelegramService telegramService) {
    this(optionChainClient, patternService, telegramService, Clock.system(IST));
  }

  public MultiTfRSINiftyOptionService(
      final NseOptionChainClient optionChainClient,
      final CandlestickPatternService patternService,
      final TelegramService telegramService,
      final Clock clock) {
    this.optionChainClient = optionChainClient;
    this.patternService = patternService;
    this.telegramService = telegramService;
    this.clock = clock;
  }

  private static final int SEED_CANDLES_5M = 100;
  private static final int SEED_CANDLES_15M = 40;

  public void evaluate() {
    final LocalTime now = LocalTime.now(clock);
    final DayOfWeek day = LocalDate.now(clock).getDayOfWeek();
    if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
      return;
    }
    if (now.isBefore(MARKET_START) || now.isAfter(MARKET_END)) {
      return;
    }
    if (now.isBefore(TRADE_START) || now.isAfter(TRADE_END)) {
      logger.debug("Outside trade window 9:30-11:30, skipping");
      return;
    }

    final IndexQuote quote = optionChainClient.fetchIndexQuote("NIFTY");
    if (quote == null || quote.data() == null || quote.data().isEmpty()) {
      return;
    }
    final IndexData nifty = quote.data().getFirst();
    if (nifty.lastPrice() == null) {
      return;
    }

    final TfState state = getOrCreateState();
    ensureSeeded(state);
    final OhlcCandle tickCandle =
        new OhlcCandle(
            LocalDateTime.now(clock),
            nifty.lastPrice(),
            nifty.lastPrice(),
            nifty.lastPrice(),
            nifty.lastPrice());
    state.oneMinTicks.add(tickCandle);

    buildFiveMinCandle(state);
    buildFifteenMinCandle(state);

    if (state.candles5.size() < RSI_PERIOD || state.candles15.size() < RSI_PERIOD) {
      return;
    }

    updateRsi(state, state.candles5, state.rsi5, state.rsiSma5, true);
    updateRsi(state, state.candles15, state.rsi15, state.rsiSma15, false);

    final int tradesToday = longTradesToday + shortTradesToday;
    if (tradesToday >= MAX_TRADES_PER_DAY) {
      if (state.inTrade) {
        checkExit(state, now, nifty.lastPrice());
      }
      return;
    }

    if (state.inTrade) {
      checkExit(state, now, nifty.lastPrice());
      return;
    }

    evaluateEntry(state, nifty.lastPrice(), now);
  }

  private TfState getOrCreateState() {
    if (states.isEmpty()) {
      final TfState state = new TfState();
      states.add(state);
      return state;
    }
    return states.getFirst();
  }

  private void ensureSeeded(final TfState state) {
    if (state.seeded) {
      return;
    }
    seedHistory(state);
    state.seeded = true;
  }

  /**
   * Warms up RSI(14) and its SMA(20) by seeding completed-session OHLC history so the 15m RSI is
   * valid from the first bar of the trading day. Only the last completed trading session's candles
   * are used (today's live bar comes from NSE ticks). Falls back to live-only behaviour if seeding
   * fails.
   */
  private void seedHistory(final TfState state) {
    try {
      final List<OhlcCandle> candles15 =
          lastCompletedSession(optionChainClient.fetchIndexCandles("15m", SEED_CANDLES_15M));
      final List<OhlcCandle> candles5 =
          lastCompletedSession(optionChainClient.fetchIndexCandles("5m", SEED_CANDLES_5M));

      if (candles15.size() < RSI_PERIOD + 1 || candles5.size() < RSI_PERIOD + 1) {
        logger.warn(
            "Insufficient seeded history (5m={}, 15m={}); RSI will warm up live only",
            candles5.size(),
            candles15.size());
        return;
      }

      state.candles15.addAll(candles15);
      state.candles5.addAll(candles5);

      updateRsi(state, state.candles5, state.rsi5, state.rsiSma5, true);
      updateRsi(state, state.candles15, state.rsi15, state.rsiSma15, false);

      logger.info(
          "Seeded RSI history: 5m={} bars (rsi={}, sma={}), 15m={} bars (rsi={}, sma={})",
          state.candles5.size(),
          state.rsi5.size(),
          state.rsiSma5.size(),
          state.candles15.size(),
          state.rsi15.size(),
          state.rsiSma15.size());
    } catch (final Exception e) {
      logger.warn("Failed to seed RSI history, falling back to live-only: {}", e.getMessage());
    }
  }

  private static List<OhlcCandle> lastCompletedSession(final List<OhlcCandle> candles) {
    if (candles.isEmpty()) {
      return candles;
    }
    final LocalDate today = LocalDate.now();
    LocalDate latestDate = null;
    for (final OhlcCandle c : candles) {
      final LocalDate d = c.timestamp().toLocalDate();
      if (latestDate == null || d.isAfter(latestDate)) {
        latestDate = d;
      }
    }
    if (latestDate == null || latestDate.isEqual(today)) {
      return candles;
    }
    final List<OhlcCandle> result = new ArrayList<>();
    for (final OhlcCandle c : candles) {
      if (c.timestamp().toLocalDate().isEqual(latestDate)) {
        result.add(c);
      }
    }
    return result;
  }

  void buildFiveMinCandle(final TfState state) {
    state.tickCount5++;
    if (state.tickCount5 < FIVE_MIN_CANDLES) {
      final OhlcCandle tick = state.oneMinTicks.getLast();
      if (state.runningCandle5 == null) {
        state.runningCandle5 = tick;
      } else {
        state.runningCandle5 =
            new OhlcCandle(
                state.runningCandle5.timestamp(),
                state.runningCandle5.open(),
                max(state.runningCandle5.high(), tick.high()),
                min(state.runningCandle5.low(), tick.low()),
                tick.close());
      }
      return;
    }
    state.tickCount5 = 0;
    final OhlcCandle finalCandle = closeRunningCandle(state, state.runningCandle5);
    state.candles5.add(finalCandle);
    state.runningCandle5 = null;
  }

  void buildFifteenMinCandle(final TfState state) {
    state.tickCount15++;
    if (state.tickCount15 < FIFTEEN_MIN_CANDLES) {
      final OhlcCandle tick = state.oneMinTicks.getLast();
      if (state.runningCandle15 == null) {
        state.runningCandle15 = tick;
      } else {
        state.runningCandle15 =
            new OhlcCandle(
                state.runningCandle15.timestamp(),
                state.runningCandle15.open(),
                max(state.runningCandle15.high(), tick.high()),
                min(state.runningCandle15.low(), tick.low()),
                tick.close());
      }
      return;
    }
    state.tickCount15 = 0;
    final OhlcCandle finalCandle = closeRunningCandle(state, state.runningCandle15);
    state.candles15.add(finalCandle);
    state.runningCandle15 = null;
  }

  private static OhlcCandle closeRunningCandle(final TfState state, final OhlcCandle running) {
    if (running != null) {
      return running;
    }
    final OhlcCandle tick = state.oneMinTicks.getLast();
    return new OhlcCandle(tick.timestamp(), tick.open(), tick.high(), tick.low(), tick.close());
  }

  static void updateRsi(
      final TfState state,
      final List<OhlcCandle> candles,
      final List<BigDecimal> rsiValues,
      final List<BigDecimal> smaValues,
      final boolean isFiveMin) {
    if (candles.size() < RSI_PERIOD + 1) {
      return;
    }

    final int prevRsiCount = rsiValues.size();
    final int targetCount = candles.size() - RSI_PERIOD;

    for (int i = prevRsiCount + RSI_PERIOD; i < candles.size(); i++) {
      final BigDecimal rsi = computeSingleRsi(state, candles, i, isFiveMin);
      rsiValues.add(rsi);
    }

    if (rsiValues.size() >= SMA_PERIOD && rsiValues.size() > prevRsiCount) {
      BigDecimal s = BigDecimal.ZERO;
      for (int i = rsiValues.size() - SMA_PERIOD; i < rsiValues.size(); i++) {
        s = s.add(rsiValues.get(i));
      }
      smaValues.clear();
      smaValues.add(s.divide(BigDecimal.valueOf(SMA_PERIOD), 4, RoundingMode.HALF_UP));
    }
  }

  static BigDecimal computeSingleRsi(
      final TfState state, final List<OhlcCandle> candles, final int idx, final boolean isFiveMin) {
    final boolean isFirst = idx == RSI_PERIOD;
    final BigDecimal change = candles.get(idx).close().subtract(candles.get(idx - 1).close());
    final BigDecimal currentGain = change.compareTo(BigDecimal.ZERO) > 0 ? change : BigDecimal.ZERO;
    final BigDecimal currentLoss =
        change.compareTo(BigDecimal.ZERO) < 0 ? change.abs() : BigDecimal.ZERO;

    final BigDecimal avgGain;
    final BigDecimal avgLoss;
    if (isFirst) {
      BigDecimal totalGain = BigDecimal.ZERO;
      BigDecimal totalLoss = BigDecimal.ZERO;
      for (int i = 1; i <= RSI_PERIOD; i++) {
        final BigDecimal c = candles.get(i).close().subtract(candles.get(i - 1).close());
        if (c.compareTo(BigDecimal.ZERO) > 0) {
          totalGain = totalGain.add(c);
        } else {
          totalLoss = totalLoss.add(c.abs());
        }
      }
      avgGain = totalGain.divide(BigDecimal.valueOf(RSI_PERIOD), 4, RoundingMode.HALF_UP);
      avgLoss = totalLoss.divide(BigDecimal.valueOf(RSI_PERIOD), 4, RoundingMode.HALF_UP);
      if (isFiveMin) {
        state.prevAvgGain5 = avgGain;
        state.prevAvgLoss5 = avgLoss;
      } else {
        state.prevAvgGain15 = avgGain;
        state.prevAvgLoss15 = avgLoss;
      }
    } else {
      final BigDecimal prevGain = isFiveMin ? state.prevAvgGain5 : state.prevAvgGain15;
      final BigDecimal prevLoss = isFiveMin ? state.prevAvgLoss5 : state.prevAvgLoss15;
      avgGain =
          prevGain
              .multiply(BigDecimal.valueOf(RSI_PERIOD - 1))
              .add(currentGain)
              .divide(BigDecimal.valueOf(RSI_PERIOD), 4, RoundingMode.HALF_UP);
      avgLoss =
          prevLoss
              .multiply(BigDecimal.valueOf(RSI_PERIOD - 1))
              .add(currentLoss)
              .divide(BigDecimal.valueOf(RSI_PERIOD), 4, RoundingMode.HALF_UP);
      if (isFiveMin) {
        state.prevAvgGain5 = avgGain;
        state.prevAvgLoss5 = avgLoss;
      } else {
        state.prevAvgGain15 = avgGain;
        state.prevAvgLoss15 = avgLoss;
      }
    }

    if (avgLoss.compareTo(BigDecimal.ZERO) == 0) {
      return BigDecimal.valueOf(100);
    }
    final BigDecimal rs = avgGain.divide(avgLoss, 4, RoundingMode.HALF_UP);
    return BigDecimal.valueOf(100)
        .subtract(BigDecimal.valueOf(100).divide(BigDecimal.ONE.add(rs), 4, RoundingMode.HALF_UP));
  }

  private void evaluateEntry(final TfState state, final BigDecimal spotPrice, final LocalTime now) {
    final boolean rsi5Bullish = isRsiBullish(state.rsi5, state.rsiSma5);
    final boolean rsi5Bearish = isRsiBearish(state.rsi5, state.rsiSma5);
    final boolean rsi15Bullish = isRsiBullish(state.rsi15, state.rsiSma15);
    final boolean rsi15Bearish = isRsiBearish(state.rsi15, state.rsiSma15);

    if (state.rsi5.isEmpty() || state.rsi15.isEmpty()) {
      return;
    }

    final BigDecimal rsi5 = state.rsi5.getLast();
    final BigDecimal rsi15 = state.rsi15.getLast();

    final boolean longBand = rsi5.compareTo(RSI_LONG_MIN) > 0 && rsi5.compareTo(RSI_LONG_MAX) <= 0;
    final boolean shortBand =
        rsi5.compareTo(RSI_SHORT_MIN) >= 0 && rsi5.compareTo(RSI_SHORT_MAX) < 0;

    final List<String> patterns = patternService.detectPatterns(state.candles5);
    final boolean hasPattern = !patterns.isEmpty();

    if (rsi15Bullish && rsi5Bullish && longBand && longTradesToday < 1 && hasPattern) {
      final OptionContract callContract = findSuitableStrike(spotPrice, "CE");
      if (callContract != null) {
        generateSignal(
            state,
            "CE",
            callContract.strikePrice(),
            "LONG",
            callContract.lastPrice(),
            spotPrice,
            patterns,
            true,
            true,
            now);
      }
      return;
    }

    if (rsi15Bearish && rsi5Bearish && shortBand && shortTradesToday < 1 && hasPattern) {
      final OptionContract putContract = findSuitableStrike(spotPrice, "PE");
      if (putContract != null) {
        generateSignal(
            state,
            "PE",
            putContract.strikePrice(),
            "SHORT",
            putContract.lastPrice(),
            spotPrice,
            patterns,
            true,
            true,
            now);
      }
    }
  }

  private boolean isRsiBullish(final List<BigDecimal> rsi, final List<BigDecimal> sma) {
    if (rsi.isEmpty() || sma.isEmpty()) {
      return false;
    }
    return rsi.getLast().compareTo(sma.getLast()) > 0;
  }

  private boolean isRsiBearish(final List<BigDecimal> rsi, final List<BigDecimal> sma) {
    if (rsi.isEmpty() || sma.isEmpty()) {
      return false;
    }
    return rsi.getLast().compareTo(sma.getLast()) < 0;
  }

  private OptionContract findSuitableStrike(final BigDecimal spotPrice, final String type) {
    final OptionChainData chain = optionChainClient.fetchOptionChain("NIFTY");
    if (chain == null || chain.records() == null) {
      return null;
    }
    final BigDecimal baseStrike = roundToNearestFifty(spotPrice);
    final List<BigDecimal> candidates = new ArrayList<>();
    candidates.add(baseStrike);
    candidates.add(baseStrike.add(BigDecimal.valueOf(50)));
    candidates.add(baseStrike.add(BigDecimal.valueOf(100)));
    candidates.add(baseStrike.subtract(BigDecimal.valueOf(50)));
    candidates.add(baseStrike.subtract(BigDecimal.valueOf(100)));

    OptionContract best = null;
    BigDecimal bestPremiumDiff = null;
    for (final OptionData data : chain.records().data()) {
      final BigDecimal strike = data.strikePrice();
      if (!candidates.contains(strike)) {
        continue;
      }
      final OptionContract contract = "CE".equals(type) ? data.ce() : data.pe();
      if (contract == null || contract.lastPrice() == null) {
        continue;
      }
      final BigDecimal premium = contract.lastPrice();
      if (premium.compareTo(MIN_PREMIUM) >= 0 && premium.compareTo(MAX_PREMIUM) <= 0) {
        final BigDecimal diff = premium.subtract(MIN_PREMIUM).abs();
        if (best == null || diff.compareTo(bestPremiumDiff) < 0) {
          best = contract;
          bestPremiumDiff = diff;
        }
      }
    }
    return best;
  }

  private static BigDecimal roundToNearestFifty(final BigDecimal value) {
    final BigDecimal divided = value.divide(BigDecimal.valueOf(50), 0, RoundingMode.HALF_UP);
    return divided.multiply(BigDecimal.valueOf(50));
  }

  private void generateSignal(
      final TfState state,
      final String optionType,
      final BigDecimal strikePrice,
      final String direction,
      final BigDecimal entryPremium,
      final BigDecimal spotPrice,
      final List<String> patterns,
      final boolean rsi5Aligned,
      final boolean rsi15Aligned,
      final LocalTime now) {
    final String patternStr = String.join(",", patterns);
    final MultiTfRSINiftySignal signal =
        new MultiTfRSINiftySignal(
            "NIFTY",
            optionType,
            strikePrice,
            direction,
            LocalDateTime.now(clock),
            entryPremium,
            spotPrice,
            patternStr,
            rsi5Aligned,
            rsi15Aligned,
            "SIGNAL_READY");

    signals.add(signal);
    state.activeSignal = signal;
    state.inTrade = true;
    state.direction = direction;
    state.entryRsi5 = state.rsi5.isEmpty() ? BigDecimal.ZERO : state.rsi5.getLast();
    state.entryRsi15 = state.rsi15.isEmpty() ? BigDecimal.ZERO : state.rsi15.getLast();
    state.entrySpot = spotPrice;
    if ("LONG".equals(direction)) {
      longTradesToday++;
    } else {
      shortTradesToday++;
    }

    logger.info(
        "{} signal: {} {} strike={} premium={} spot={} patterns={}",
        "NIFTY",
        direction,
        optionType,
        strikePrice,
        entryPremium,
        spotPrice,
        patternStr);

    notifySignal(signal);
  }

  private void notifySignal(final MultiTfRSINiftySignal signal) {
    final String arrow = "LONG".equals(signal.direction()) ? "\uD83D\uDCE1" : "\uD83D\uDD34";
    final String message =
        arrow
            + " MULTI-TF RSI SIGNAL\n\n"
            + "Instrument: "
            + signal.symbol()
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
            + signal.niftySpotPrice()
            + "\n"
            + "Patterns: "
            + signal.candlePattern()
            + "\n"
            + "RSI 5m: "
            + (signal.rsi5Aligned() ? "\u2705" : "\u274C")
            + "\n"
            + "RSI 15m: "
            + (signal.rsi15Aligned() ? "\u2705" : "\u274C");
    telegramService.sendMessage(message);
  }

  private void checkExit(final TfState state, final LocalTime now, final BigDecimal spotPrice) {
    final boolean rsi5Bullish = isRsiBullish(state.rsi5, state.rsiSma5);
    final boolean rsi5Bearish = isRsiBearish(state.rsi5, state.rsiSma5);
    final boolean rsi15Bullish = isRsiBullish(state.rsi15, state.rsiSma15);
    final boolean rsi15Bearish = isRsiBearish(state.rsi15, state.rsiSma15);

    // Directional exit on the entry timeframe (5m) RSI cross only.
    final boolean exitSignal = "LONG".equals(state.direction) ? !rsi5Bullish : !rsi5Bearish;

    // Hard stop / take-profit on the underlying move (option buy must clear theta).
    final BigDecimal movePct =
        spotPrice.subtract(state.entrySpot).divide(state.entrySpot, 6, RoundingMode.HALF_UP);
    final boolean stopped =
        "LONG".equals(state.direction)
            ? movePct.compareTo(STOP_PCT.negate()) <= 0
            : movePct.compareTo(STOP_PCT) >= 0;
    final boolean targetHit =
        "LONG".equals(state.direction)
            ? movePct.compareTo(TARGET_PCT) >= 0
            : movePct.compareTo(TARGET_PCT.negate()) <= 0;

    // Soft 15m conflict: only exits early when the higher timeframe fully flips against us,
    // avoiding premature exits on a transient 15m dip mid-trend.
    final boolean conflict =
        "LONG".equals(state.direction)
            ? (!rsi15Bullish && !rsi5Bullish)
            : (!rsi15Bearish && !rsi5Bearish);

    if (stopped || targetHit || exitSignal || conflict || now.isAfter(TRADE_END)) {
      final BigDecimal exitPremium = fetchCurrentPremium(state);
      final String reason =
          targetHit
              ? "TARGET"
              : stopped
                  ? "STOP"
                  : exitSignal ? "RSI CROSS" : now.isAfter(TRADE_END) ? "EOD" : "CONFLICT";
      notifyExit(state, exitPremium, reason);
      resetState(state);
    }
  }

  private BigDecimal fetchCurrentPremium(final TfState state) {
    try {
      final OptionChainData chain = optionChainClient.fetchOptionChain("NIFTY");
      if (chain == null || chain.records() == null) {
        return BigDecimal.ZERO;
      }
      for (final OptionData data : chain.records().data()) {
        if (data.strikePrice().compareTo(state.activeSignal.strikePrice()) != 0) {
          continue;
        }
        final OptionContract contract =
            "CE".equals(state.activeSignal.optionType()) ? data.ce() : data.pe();
        if (contract != null && contract.lastPrice() != null) {
          return contract.lastPrice();
        }
      }
    } catch (final Exception e) {
      logger.warn("Failed to fetch current premium for exit: {}", e.getMessage());
    }
    return BigDecimal.ZERO;
  }

  private void notifyExit(final TfState state, final BigDecimal exitPremium, final String reason) {
    final String message =
        "\u26A0\uFE0F MULTI-TF RSI EXIT\n\n"
            + "Instrument: NIFTY\n"
            + "Direction: "
            + state.direction
            + "\n"
            + "Option: "
            + state.activeSignal.optionType()
            + " "
            + state.activeSignal.strikePrice()
            + "\n"
            + "Exit Premium: \u20B9"
            + exitPremium
            + "\n"
            + "Reason: "
            + reason;
    telegramService.sendMessage(message);
    logger.info("NIFTY {} exit at {} ({})", state.direction, exitPremium, reason);
  }

  public void resetDaily() {
    states.clear();
    signals.clear();
    longTradesToday = 0;
    shortTradesToday = 0;
    logger.info("Multi-TF RSI Nifty Option state reset");
  }

  public List<MultiTfRSINiftySignal> getSignals() {
    return List.copyOf(signals);
  }

  private static BigDecimal max(final BigDecimal a, final BigDecimal b) {
    return a.compareTo(b) >= 0 ? a : b;
  }

  private static BigDecimal min(final BigDecimal a, final BigDecimal b) {
    return a.compareTo(b) <= 0 ? a : b;
  }

  private void resetState(final TfState state) {
    state.inTrade = false;
    state.direction = null;
    state.activeSignal = null;
    state.entryRsi5 = BigDecimal.ZERO;
    state.entryRsi15 = BigDecimal.ZERO;
  }

  static class TfState {
    boolean seeded;

    final List<OhlcCandle> oneMinTicks = new ArrayList<>();
    final List<OhlcCandle> candles5 = new ArrayList<>();
    final List<OhlcCandle> candles15 = new ArrayList<>();
    OhlcCandle runningCandle5;
    OhlcCandle runningCandle15;
    int tickCount5;
    int tickCount15;

    final List<BigDecimal> rsi5 = new ArrayList<>();
    final List<BigDecimal> rsi15 = new ArrayList<>();
    final List<BigDecimal> rsiSma5 = new ArrayList<>();
    final List<BigDecimal> rsiSma15 = new ArrayList<>();

    boolean inTrade;
    String direction;
    MultiTfRSINiftySignal activeSignal;
    BigDecimal entryRsi5 = BigDecimal.ZERO;
    BigDecimal entryRsi15 = BigDecimal.ZERO;
    BigDecimal entrySpot = BigDecimal.ZERO;

    BigDecimal prevAvgGain5;
    BigDecimal prevAvgLoss5;
    BigDecimal prevAvgGain15;
    BigDecimal prevAvgLoss15;
  }
}
