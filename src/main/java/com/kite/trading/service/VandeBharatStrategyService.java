package com.kite.trading.service;

import com.kite.trading.dto.StockQuote;
import com.kite.trading.dto.StockQuote.PriceInfo;
import com.kite.trading.dto.VandeBharatSignal;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class VandeBharatStrategyService {

  private static final Logger logger = LoggerFactory.getLogger(VandeBharatStrategyService.class);

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  private static final LocalTime MARKET_START = LocalTime.of(9, 15);
  private static final LocalTime NO_TRADE_UNTIL = LocalTime.of(9, 30);
  private static final LocalTime MARKET_END = LocalTime.of(15, 30);
  private static final int EMA_PERIOD = 10;
  private static final int MAX_CANDLES = 20;
  private static final int MAX_ACTIVE_STOCKS = 10;

  private static final BigDecimal DEFAULT_RANGE_PCT = BigDecimal.valueOf(0.01);
  private static final BigDecimal CAPITAL = BigDecimal.valueOf(1_000_000);
  private static final BigDecimal RISK_PER_TRADE_PCT = BigDecimal.valueOf(0.5);
  private static final BigDecimal MAX_BREAKOUT_MULTIPLIER = BigDecimal.valueOf(1.02);
  private static final int INSIDE_CANDLE_LIMIT = 6;
  private static final int MAX_CONCURRENT_TRADES = 2;
  private static final BigDecimal TRAIL_MULTIPLIER = BigDecimal.ONE;
  private static final BigDecimal PARTIAL_EXIT_MULTIPLIER = BigDecimal.valueOf(2);
  private static final BigDecimal PARTIAL_EXIT_PCT = BigDecimal.valueOf(50);

  private static final List<String> DEFAULT_WATCHLIST =
      List.of(
          "ABB",
          "ABCAPITAL",
          "ADANIENSOL",
          "ADANIGREEN",
          "ADANIPOWER",
          "BHARATFORG",
          "BHEL",
          "BSE",
          "CGPOWER",
          "CUMMINSIND",
          "FEDERALBNK",
          "GLENMARK",
          "GVT&D",
          "HINDALCO",
          "IDEA",
          "KEI",
          "LAURUSLABS",
          "LTF",
          "MCX",
          "MOTHERSON",
          "NATIONALUM",
          "NTPC",
          "POLYCAB",
          "POWERINDIA",
          "SAIL",
          "SHRIRAMFIN",
          "SOLARINDS",
          "TATASTEEL",
          "TORNTPHARM",
          "VEDL");

  private final NseOptionChainClient nseClient;
  private final TelegramService telegramService;
  private final Clock clock;

  private final Map<String, StockState> states = new ConcurrentHashMap<>();
  private final List<VandeBharatSignal> signals = new CopyOnWriteArrayList<>();

  @Autowired
  public VandeBharatStrategyService(
      final NseOptionChainClient nseClient, final TelegramService telegramService) {
    this(nseClient, telegramService, Clock.system(IST));
  }

  public VandeBharatStrategyService(
      final NseOptionChainClient nseClient,
      final TelegramService telegramService,
      final Clock clock) {
    this.nseClient = nseClient;
    this.telegramService = telegramService;
    this.clock = clock;
  }

  public void analyze() {
    final LocalTime now = LocalTime.now(clock);
    final DayOfWeek day = LocalDate.now(clock).getDayOfWeek();
    if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
      return;
    }
    if (now.isBefore(MARKET_START) || now.isAfter(MARKET_END)) {
      return;
    }
    if (now.isBefore(NO_TRADE_UNTIL)) {
      logger.debug("No-trade zone before 9:30, skipping");
      return;
    }

    if (states.isEmpty()) {
      logger.info("No scanned stocks available, using default watchlist");
      for (final String symbol : DEFAULT_WATCHLIST) {
        states.putIfAbsent(symbol, new StockState(symbol));
      }
    }

    logger.debug("Vande Bharat analysis tick at {}", now);
    for (final StockState state : states.values()) {
      try {
        processStock(state, now);
      } catch (final Exception e) {
        logger.error("Error processing {}", state.symbol, e);
      }
    }
  }

  public void preMarketScan() {
    final DayOfWeek day = LocalDate.now(clock).getDayOfWeek();
    if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
      logger.info("Weekend, skipping pre-market scan");
      return;
    }

    final NseOptionChainClient.PreOpenResponse foResp = nseClient.fetchPreOpenData("FO");
    if (foResp == null || foResp.data() == null || foResp.data().isEmpty()) {
      logger.warn("Pre-open data not available, keeping current watchlist");
      return;
    }

    final NseOptionChainClient.PreOpenResponse niftyResp = nseClient.fetchPreOpenData("NIFTY");
    if (niftyResp == null) {
      logger.warn("NIFTY pre-open data not available, continuing without it");
    }

    final List<NseOptionChainClient.PreOpenItem> sorted =
        foResp.data().stream()
            .sorted(
                Comparator.comparing(
                    (final NseOptionChainClient.PreOpenItem item) -> item.metadata().pChange(),
                    Comparator.nullsLast(Comparator.reverseOrder())))
            .limit(MAX_ACTIVE_STOCKS)
            .toList();

    logger.info(
        "Pre-market scan: {} F&O stocks from pre-open API, picked top {} by %CHNG",
        foResp.data().size(), sorted.size());

    states.clear();
    signals.clear();
    for (final NseOptionChainClient.PreOpenItem item : sorted) {
      final String symbol = item.metadata().symbol();
      states.put(symbol, new StockState(symbol));
      logger.info(
          "Selected {}: change={}%  price={}  turnover={}",
          symbol,
          item.metadata().pChange(),
          item.metadata().lastPrice(),
          item.metadata().totalTurnover());
    }

    loadPdhPdlFromBhavCopy();

    notifyPreOpenResults(
        sorted,
        foResp.advances(),
        foResp.declines(),
        foResp.unchanged(),
        niftyResp != null ? niftyResp.advances() : -1,
        niftyResp != null ? niftyResp.declines() : -1,
        niftyResp != null ? niftyResp.unchanged() : -1);
  }

  private void notifyPreOpenResults(
      final List<NseOptionChainClient.PreOpenItem> items,
      final int foAdvances,
      final int foDeclines,
      final int foUnchanged,
      final int niftyAdvances,
      final int niftyDeclines,
      final int niftyUnchanged) {
    final StringBuilder sb = new StringBuilder();
    sb.append("\uD83D\uDD0D VANDE BHARAT PRE-MARKET SCAN\n\n");
    sb.append("NIFTY 50  A: ").append(niftyAdvances >= 0 ? niftyAdvances : "-");
    sb.append("  D: ").append(niftyDeclines >= 0 ? niftyDeclines : "-");
    sb.append("  U: ").append(niftyUnchanged >= 0 ? niftyUnchanged : "-");
    sb.append("\n");
    sb.append("F&O       A: ").append(foAdvances);
    sb.append("  D: ").append(foDeclines);
    sb.append("  U: ").append(foUnchanged);
    sb.append("\n\nTop 10 by %CHNG:\n");

    int rank = 1;
    for (final NseOptionChainClient.PreOpenItem item : items) {
      final NseOptionChainClient.PreOpenMetadata m = item.metadata();
      final StockState state = states.get(m.symbol());
      sb.append(rank++)
          .append(". ")
          .append(m.symbol())
          .append("  ")
          .append(formatPct(m.pChange()))
          .append("  \u20B9")
          .append(formatPrice(m.lastPrice()));
      if (state != null && state.pdhSet) {
        sb.append("  PDH: \u20B9")
            .append(formatPrice(state.pdh))
            .append("  PDL: \u20B9")
            .append(formatPrice(state.pdl));
      }
      sb.append("\n");
    }

    telegramService.sendMessage(sb.toString());
  }

  private void loadPdhPdlFromBhavCopy() {
    try {
      LocalDate prev = LocalDate.now(clock).minusDays(1);
      while (prev.getDayOfWeek() == DayOfWeek.SATURDAY || prev.getDayOfWeek() == DayOfWeek.SUNDAY) {
        prev = prev.minusDays(1);
      }
      final Map<String, NseOptionChainClient.BhavCopyEntry> bhavCopy =
          nseClient.fetchBhavCopyData(prev);
      if (bhavCopy.isEmpty()) {
        logger.warn("Bhavcopy returned no data, PDH/PDL will use fallback");
        return;
      }
      int count = 0;
      for (final StockState state : states.values()) {
        final NseOptionChainClient.BhavCopyEntry entry = bhavCopy.get(state.symbol);
        if (entry != null) {
          state.pdh = entry.high();
          state.pdl = entry.low();
          state.pdhSet = true;
          count++;
          logger.info(
              "PDH/PDL for {} from bhavcopy: HIGH={}, LOW={}",
              state.symbol,
              entry.high(),
              entry.low());
        }
      }
      if (count == 0) {
        logger.warn("No bhavcopy entries matched selected stocks, using fallback");
      }
    } catch (final Exception e) {
      logger.warn("Failed to load bhavcopy: {}, PDH/PDL will use fallback", e.getMessage());
    }
  }

  private static String formatTurnover(final BigDecimal value) {
    if (value == null) return "0";
    final long v = value.longValue();
    if (v >= 1_00_00_000) {
      return String.format("%.1fCr", v / 1_00_00_000.0);
    } else if (v >= 1_00_000) {
      return String.format("%.1fL", v / 1_00_000.0);
    }
    return String.valueOf(v);
  }

  private static String formatVolume(final long value) {
    if (value >= 1_00_000) {
      return String.format("%.1fL", value / 1_00_000.0);
    } else if (value >= 1_000) {
      return String.format("%.1fK", value / 1_000.0);
    }
    return String.valueOf(value);
  }

  private static String formatPct(final BigDecimal value) {
    if (value == null) return "0%";
    return (value.compareTo(BigDecimal.ZERO) >= 0 ? "+" : "")
        + value.setScale(1, RoundingMode.HALF_UP)
        + "%";
  }

  private static String formatPrice(final BigDecimal value) {
    if (value == null) return "0";
    return value.setScale(0, RoundingMode.HALF_UP).toString();
  }

  private void processStock(final StockState state, final LocalTime now) {
    final StockQuote quote = nseClient.fetchEquityQuote(state.symbol);
    if (quote == null || quote.priceInfo() == null) {
      return;
    }

    final PriceInfo info = quote.priceInfo();
    if (info.lastPrice() == null) {
      return;
    }

    if (!state.pdhSet) {
      initializePdhPdl(state, info);
    }

    trackRange(state, info);
    final FiveMinCandle candle = buildCandle(state, info, now);
    if (candle == null) {
      return;
    }

    state.candles.add(candle);
    if (state.candles.size() == 1) {
      state.firstCandleHigh = candle.high;
      state.firstCandleLow = candle.low;
      logger.debug("{} first 5-min candle: high={}, low={}", state.symbol, candle.high, candle.low);
    }
    if (state.candles.size() > MAX_CANDLES) {
      state.candles.removeFirst();
    }

    updateEma(state);
    if (state.inTrade) {
      updateTrailingStop(state, candle);
    }
    evaluateStrategy(state, candle);
  }

  private void initializePdhPdl(final StockState state, final PriceInfo info) {
    final BigDecimal prevClose = info.previousClose();
    final BigDecimal open = info.open();
    if (prevClose != null && prevClose.compareTo(BigDecimal.ZERO) > 0) {
      final BigDecimal range = prevClose.multiply(DEFAULT_RANGE_PCT);
      state.pdh = prevClose.add(range);
      state.pdl = prevClose.subtract(range);
    } else if (open != null && open.compareTo(BigDecimal.ZERO) > 0) {
      final BigDecimal range = open.multiply(DEFAULT_RANGE_PCT);
      state.pdh = open.add(range);
      state.pdl = open.subtract(range);
    } else {
      return;
    }
    state.pdhSet = true;
    logger.info("PDH/PDL for {}: PDH={}, PDL={}", state.symbol, state.pdh, state.pdl);
  }

  private void trackRange(final StockState state, final PriceInfo info) {
    if (info.high() != null && info.high().compareTo(state.todayHigh) > 0) {
      state.todayHigh = info.high();
    }
    if (info.low() != null
        && (state.todayLow.compareTo(BigDecimal.ZERO) == 0
            || info.low().compareTo(state.todayLow) < 0)) {
      state.todayLow = info.low();
    }
  }

  private FiveMinCandle buildCandle(
      final StockState state, final PriceInfo info, final LocalTime now) {
    final BigDecimal currentPrice = info.lastPrice();
    final BigDecimal currentVolume =
        info.totalTradedVolume() != null ? info.totalTradedVolume() : BigDecimal.ZERO;

    if (state.lastPrice == null) {
      state.lastPrice = currentPrice;
      state.lastVolume = currentVolume;
      state.lastSlot = now;
      state.prevDayHigh = info.high();
      state.prevDayLow = info.low();
      return null;
    }

    final BigDecimal open = state.lastPrice;
    final BigDecimal close = currentPrice;
    final BigDecimal high;
    final BigDecimal low;
    if (info.high() != null
        && state.prevDayHigh != null
        && info.high().compareTo(state.prevDayHigh) > 0) {
      high = info.high();
    } else {
      high = open.compareTo(close) > 0 ? open : close;
    }
    if (info.low() != null
        && state.prevDayLow != null
        && info.low().compareTo(state.prevDayLow) < 0) {
      low = info.low();
    } else {
      low = open.compareTo(close) < 0 ? open : close;
    }
    final BigDecimal volume = currentVolume.subtract(state.lastVolume);
    final BigDecimal candleVolume =
        volume.compareTo(BigDecimal.ZERO) > 0 ? volume : BigDecimal.ZERO;

    final LocalDateTime candleTime = LocalDate.now(clock).atTime(state.lastSlot);

    state.lastPrice = currentPrice;
    state.lastVolume = currentVolume;
    state.lastSlot = now;
    state.prevDayHigh = info.high();
    state.prevDayLow = info.low();

    return new FiveMinCandle(candleTime, open, high, low, close, candleVolume);
  }

  private void updateEma(final StockState state) {
    if (state.candles.size() < EMA_PERIOD) {
      return;
    }
    if (state.candles.size() == EMA_PERIOD) {
      BigDecimal sum = BigDecimal.ZERO;
      for (final FiveMinCandle c : state.candles) {
        sum = sum.add(c.close);
      }
      state.ema10 = sum.divide(BigDecimal.valueOf(EMA_PERIOD), 4, RoundingMode.HALF_UP);
      return;
    }

    final FiveMinCandle latest = state.candles.getLast();
    final BigDecimal multiplier = BigDecimal.valueOf(2.0 / (EMA_PERIOD + 1));
    final BigDecimal ema = latest.close.subtract(state.ema10).multiply(multiplier).add(state.ema10);
    state.ema10 = ema.setScale(4, RoundingMode.HALF_UP);
  }

  private void evaluateStrategy(final StockState state, final FiveMinCandle candle) {
    if (state.inTrade) {
      checkExit(state, candle);
      return;
    }

    if (state.signalGenerated) {
      return;
    }

    final BigDecimal close = candle.close;
    final BigDecimal pdh = state.pdh;
    final BigDecimal pdl = state.pdl;

    // Phase 1: No breakout yet
    if (state.breakoutCandle == null) {
      if (close.compareTo(pdh) > 0) {
        if (close.compareTo(pdh.multiply(MAX_BREAKOUT_MULTIPLIER)) > 0) {
          logger.debug(
              "{} LONG breakout too extended: close {} > {}x PDH",
              state.symbol,
              close,
              MAX_BREAKOUT_MULTIPLIER);
          return;
        }
        state.breakoutDirection = "LONG";
        state.breakoutCandle = candle;
        state.insideCandles = new ArrayList<>();
        logger.info("{} LONG breakout: close {} above PDH {}", state.symbol, close, pdh);
        return;
      }
      if (close.compareTo(pdl) < 0) {
        final BigDecimal maxBreakoutLower = pdl.multiply(BigDecimal.valueOf(0.98));
        if (close.compareTo(maxBreakoutLower) < 0) {
          logger.debug(
              "{} SHORT breakout too extended: close {} more than 2% below PDL {}",
              state.symbol, close, pdl);
          return;
        }
        state.breakoutDirection = "SHORT";
        state.breakoutCandle = candle;
        state.insideCandles = new ArrayList<>();
        logger.info("{} SHORT breakout: close {} below PDL {}", state.symbol, close, pdl);
        return;
      }
      return;
    }

    // Phase 2: Breakout detected, looking for inside candle
    if (state.insideCandle == null) {
      if (isInsideCandle(state.breakoutCandle, candle) && hasLowerVolume(state, candle)) {
        state.insideCandles.add(candle);
        state.insideCandle = candle;
        logger.info(
            "{} Inside candle detected for {} entry", state.symbol, state.breakoutDirection);
        return;
      }
      state.insideCandles.add(candle);
      if (state.insideCandles.size() >= INSIDE_CANDLE_LIMIT) {
        logger.debug(
            "{} No inside candle after {} attempts, resetting", state.symbol, INSIDE_CANDLE_LIMIT);
        state.breakoutCandle = null;
        state.breakoutDirection = null;
        state.insideCandles = null;
      }
      return;
    }

    // Phase 3: Inside candle detected, waiting for entry trigger
    final boolean entryTriggered =
        ("LONG".equals(state.breakoutDirection)
                && close.compareTo(state.insideCandle.high) > 0
                && close.compareTo(state.pdh) > 0
                && close.compareTo(state.firstCandleHigh) > 0)
            || ("SHORT".equals(state.breakoutDirection)
                && close.compareTo(state.insideCandle.low) < 0
                && close.compareTo(state.pdl) < 0
                && close.compareTo(state.firstCandleLow) < 0);
    if (entryTriggered && candle.volume.compareTo(state.insideCandle.volume) > 0) {
      generateSignal(state, state.insideCandle);
      enterTrade(state.symbol);
    }
  }

  static boolean isInsideCandle(final FiveMinCandle breakout, final FiveMinCandle candle) {
    return candle.high.compareTo(breakout.high) <= 0 && candle.low.compareTo(breakout.low) >= 0;
  }

  private static boolean hasLowerVolume(final StockState state, final FiveMinCandle candle) {
    if (state.breakoutCandle == null) {
      return false;
    }
    return candle.volume.compareTo(state.breakoutCandle.volume) <= 0;
  }

  private void generateSignal(final StockState state, final FiveMinCandle insideCandle) {
    if (isMaxTradesReached()) {
      logger.debug(
          "Max {} concurrent trades, deferring signal for {}", MAX_CONCURRENT_TRADES, state.symbol);
      return;
    }
    final String direction = state.breakoutDirection;
    final BigDecimal entryPrice;
    final BigDecimal stopLoss;

    if ("LONG".equals(direction)) {
      entryPrice = insideCandle.high;
      stopLoss = insideCandle.low;
    } else {
      entryPrice = insideCandle.low;
      stopLoss = insideCandle.high;
    }

    final BigDecimal maxLossPerTrade =
        CAPITAL
            .multiply(RISK_PER_TRADE_PCT)
            .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    final BigDecimal stopDistance = entryPrice.subtract(stopLoss).abs();
    final BigDecimal quantity =
        stopDistance.compareTo(BigDecimal.ZERO) > 0
            ? maxLossPerTrade.divide(stopDistance, 0, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;

    final VandeBharatSignal signal =
        new VandeBharatSignal(
            state.symbol,
            direction,
            LocalDateTime.now(clock),
            entryPrice,
            stopLoss,
            state.pdh,
            state.pdl,
            insideCandle.close,
            "SIGNAL_READY");

    signals.add(signal);
    state.signalGenerated = true;
    state.activeSignal = signal;

    logger.info(
        "{} {} signal: entry={}, SL={}, qty={}",
        state.symbol,
        direction,
        entryPrice,
        stopLoss,
        quantity);

    notifySignal(signal, quantity);
  }

  private void notifySignal(final VandeBharatSignal signal, final BigDecimal quantity) {
    final String arrow = "LONG".equals(signal.direction()) ? "\uD83D\uDCE1" : "\uD83D\uDD34";
    final String message =
        arrow
            + " VANDE BHARAT SIGNAL\n\n"
            + "Stock: "
            + signal.symbol()
            + "\nDirection: "
            + signal.direction()
            + "\nEntry: "
            + signal.entryPrice()
            + "\nStop Loss: "
            + signal.stopLoss()
            + "\nPDH: "
            + signal.pdh()
            + " | PDL: "
            + signal.pdl()
            + "\nCurrent: "
            + signal.currentPrice()
            + "\nSuggested Qty: "
            + quantity;
    telegramService.sendMessage(message);
  }

  private boolean isMaxTradesReached() {
    return states.values().stream().filter(s -> s.inTrade).count() >= MAX_CONCURRENT_TRADES;
  }

  private void updateTrailingStop(final StockState state, final FiveMinCandle candle) {
    if (state.stopDistance == null || state.stopDistance.compareTo(BigDecimal.ZERO) <= 0) {
      return;
    }
    if ("LONG".equals(state.tradeDirection)) {
      if (candle.high.compareTo(state.highestPrice) > 0) {
        state.highestPrice = candle.high;
        final BigDecimal newStop =
            state.highestPrice.subtract(state.stopDistance.multiply(TRAIL_MULTIPLIER));
        if (newStop.compareTo(state.trailingStop) > 0) {
          state.trailingStop = newStop;
        }
      }
    } else if ("SHORT".equals(state.tradeDirection)) {
      if (candle.low.compareTo(state.lowestPrice) < 0) {
        state.lowestPrice = candle.low;
        final BigDecimal newStop =
            state.lowestPrice.add(state.stopDistance.multiply(TRAIL_MULTIPLIER));
        if (newStop.compareTo(state.trailingStop) < 0) {
          state.trailingStop = newStop;
        }
      }
    }
  }

  private void checkExit(final StockState state, final FiveMinCandle candle) {
    // 1. Partial exit at 1:2 RR
    if (!state.partialExitDone
        && state.entryPrice != null
        && state.stopDistance != null
        && state.stopDistance.compareTo(BigDecimal.ZERO) > 0) {
      final BigDecimal target =
          "LONG".equals(state.tradeDirection)
              ? state.entryPrice.add(state.stopDistance.multiply(PARTIAL_EXIT_MULTIPLIER))
              : state.entryPrice.subtract(state.stopDistance.multiply(PARTIAL_EXIT_MULTIPLIER));
      final boolean hit =
          "LONG".equals(state.tradeDirection)
              ? candle.high.compareTo(target) >= 0
              : candle.low.compareTo(target) <= 0;
      if (hit) {
        state.partialExitDone = true;
        notifyPartialExit(state, target);
      }
    }

    // 2. Trailing stop
    if (state.trailingStop != null) {
      final boolean hitTrail =
          "LONG".equals(state.tradeDirection)
              ? candle.close.compareTo(state.trailingStop) < 0
              : candle.close.compareTo(state.trailingStop) > 0;
      if (hitTrail) {
        notifyExit(state, candle.close, "TRAILING STOP");
        resetState(state);
        return;
      }
    }

    // 3. EMA 10
    if (state.ema10 == null) {
      return;
    }
    final boolean hitEma =
        "LONG".equals(state.tradeDirection)
            ? candle.close.compareTo(state.ema10) < 0
            : candle.close.compareTo(state.ema10) > 0;
    if (hitEma) {
      notifyExit(state, candle.close, "EMA 10");
      resetState(state);
    }
  }

  private void notifyPartialExit(final StockState state, final BigDecimal target) {
    final String message =
        "\uD83D\uDCB0 VANDE BHARAT PARTIAL EXIT\n\n"
            + "Stock: "
            + state.symbol
            + "\nDirection: "
            + state.tradeDirection
            + "\nTarget Hit: "
            + target
            + "\nBooked: "
            + PARTIAL_EXIT_PCT
            + "% at 1:"
            + PARTIAL_EXIT_MULTIPLIER
            + " RR\nRest running with trailing stop & EMA 10";
    telegramService.sendMessage(message);
    logger.info(
        "{} partial exit at {} (1:{} RR), {}% booked",
        state.symbol, target, PARTIAL_EXIT_MULTIPLIER, PARTIAL_EXIT_PCT);
  }

  private void notifyExit(final StockState state, final BigDecimal exitPrice, final String reason) {
    final String message =
        "\u26A0\uFE0F VANDE BHARAT EXIT\n\n"
            + "Stock: "
            + state.symbol
            + "\nDirection: "
            + state.tradeDirection
            + "\nExit Price: "
            + exitPrice
            + "\nReason: "
            + reason;
    telegramService.sendMessage(message);
    logger.info("{} exit at {} ({})", state.symbol, exitPrice, reason);
  }

  public void enterTrade(final String symbol) {
    final StockState state = states.get(symbol);
    if (state == null || state.activeSignal == null) {
      return;
    }
    state.inTrade = true;
    state.tradeDirection = state.activeSignal.direction();
    state.entryPrice = state.activeSignal.entryPrice();
    state.stopDistance = state.entryPrice.subtract(state.activeSignal.stopLoss()).abs();
    state.trailingStop = state.activeSignal.stopLoss();
    state.highestPrice = state.entryPrice;
    state.lowestPrice = state.entryPrice;
    state.partialExitDone = false;
    state.breakoutCandle = null;
    state.breakoutDirection = null;
    state.insideCandles = null;
    state.insideCandle = null;
    logger.info(
        "Entered trade for {} {} entry={} SL={}",
        state.symbol,
        state.tradeDirection,
        state.entryPrice,
        state.activeSignal.stopLoss());
  }

  public void resetDaily() {
    states.clear();
    signals.clear();
    logger.info("Vande Bharat state reset, awaiting pre-market scan");
  }

  private void resetState(final StockState state) {
    state.pdhSet = false;
    state.pdh = BigDecimal.ZERO;
    state.pdl = BigDecimal.ZERO;
    state.todayHigh = BigDecimal.ZERO;
    state.todayLow = BigDecimal.ZERO;
    state.candles.clear();
    state.ema10 = null;
    state.lastPrice = null;
    state.lastVolume = BigDecimal.ZERO;
    state.lastSlot = null;
    state.breakoutCandle = null;
    state.breakoutDirection = null;
    state.insideCandles = null;
    state.insideCandle = null;
    state.signalGenerated = false;
    state.activeSignal = null;
    state.inTrade = false;
    state.tradeDirection = null;
    state.entryPrice = null;
    state.stopDistance = null;
    state.highestPrice = null;
    state.lowestPrice = null;
    state.trailingStop = null;
    state.partialExitDone = false;
  }

  public List<VandeBharatSignal> getSignals() {
    return List.copyOf(signals);
  }

  // ---- inner types ----

  record FiveMinCandle(
      LocalDateTime timestamp,
      BigDecimal open,
      BigDecimal high,
      BigDecimal low,
      BigDecimal close,
      BigDecimal volume) {}

  static class StockState {

    final String symbol;

    boolean pdhSet;
    BigDecimal pdh = BigDecimal.ZERO;
    BigDecimal pdl = BigDecimal.ZERO;
    BigDecimal todayHigh = BigDecimal.ZERO;
    BigDecimal todayLow = BigDecimal.ZERO;
    BigDecimal firstCandleHigh;
    BigDecimal firstCandleLow;

    final List<FiveMinCandle> candles = new ArrayList<>();
    BigDecimal ema10;

    BigDecimal lastPrice;
    BigDecimal lastVolume = BigDecimal.ZERO;
    LocalTime lastSlot;
    BigDecimal prevDayHigh;
    BigDecimal prevDayLow;

    FiveMinCandle breakoutCandle;
    String breakoutDirection;
    List<FiveMinCandle> insideCandles;
    FiveMinCandle insideCandle;
    boolean signalGenerated;
    VandeBharatSignal activeSignal;
    boolean inTrade;
    String tradeDirection;
    BigDecimal entryPrice;
    BigDecimal stopDistance;
    BigDecimal highestPrice;
    BigDecimal lowestPrice;
    BigDecimal trailingStop;
    boolean partialExitDone;

    StockState(final String symbol) {
      this.symbol = symbol;
    }
  }
}
