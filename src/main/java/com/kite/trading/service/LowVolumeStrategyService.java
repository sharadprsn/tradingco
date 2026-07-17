package com.kite.trading.service;

import com.kite.trading.dto.MarketBreadth;
import com.kite.trading.dto.StockCandle;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class LowVolumeStrategyService {

  private static final Logger logger = LoggerFactory.getLogger(LowVolumeStrategyService.class);
  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  private static final BigDecimal MIN_RISK_REWARD = BigDecimal.valueOf(2.0);
  private static final int IGNORE_FIRST_CANDLES = 3;
  private static final int MAX_ATTEMPTS_PER_STOCK = 2;

  private final StockMarketDataService stockDataService;
  private final TelegramService telegramService;

  private volatile String marketBias = "NEUTRAL";
  private final Map<String, SignalState> signalStates = new ConcurrentHashMap<>();
  private final Map<String, PositionState> positions = new ConcurrentHashMap<>();
  private final Map<String, LocalDateTime> lastEvaluatedCandle = new ConcurrentHashMap<>();
  private final Map<String, Long> dayLowestVolume = new ConcurrentHashMap<>();
  private final Map<String, Integer> attemptCount = new ConcurrentHashMap<>();
  private boolean strategyEnabled = true;

  public LowVolumeStrategyService(
      final StockMarketDataService stockDataService, final TelegramService telegramService) {
    this.stockDataService = stockDataService;
    this.telegramService = telegramService;
  }

  public void evaluateMarketBias() {
    final MarketBreadth breadth = stockDataService.fetchMarketBreadth();
    if (breadth == null) {
      logger.warn("No market breadth data available, keeping previous bias: {}", marketBias);
      return;
    }
    if (breadth.isBullish()) {
      marketBias = "BULLISH";
    } else if (breadth.isBearish()) {
      marketBias = "BEARISH";
    } else {
      marketBias = "NEUTRAL";
    }
    final String msg =
        "<b>Low-Volume Strategy: Market Bias</b>\n"
            + "Advances: "
            + breadth.advances()
            + "\n"
            + "Declines: "
            + breadth.declines()
            + "\n"
            + "Bias: "
            + marketBias;
    telegramService.sendMessage(msg);
    logger.info(
        "Market bias set to {} (A:{}/D:{})", marketBias, breadth.advances(), breadth.declines());
  }

  public void evaluateStock(final String symbol) {
    if (!strategyEnabled || "NEUTRAL".equals(marketBias)) {
      return;
    }

    if (!rangeBreakoutFilterPassed(symbol)) {
      return;
    }

    final Integer attempts = attemptCount.get(symbol);
    if (attempts != null && attempts >= MAX_ATTEMPTS_PER_STOCK) {
      return;
    }

    if (positions.containsKey(symbol)) {
      manageExit(symbol);
      return;
    }

    final SignalState signalState = signalStates.get(symbol);
    if (signalState != null && signalState.triggered) {
      if (signalState.entryTriggered) {
        manageExit(symbol);
      } else {
        checkAndUpdateSignal(symbol, signalState);
      }
      return;
    }

    findSetup(symbol);
  }

  private boolean rangeBreakoutFilterPassed(final String symbol) {
    final StockMarketDataService.DailyRange prevRange =
        stockDataService.getPreviousDayRange(symbol);
    if (prevRange == null) {
      return true;
    }

    try {
      stockDataService.updateQuote(symbol);
    } catch (final Exception e) {
      return false;
    }

    final List<StockCandle> candles = stockDataService.getCompletedCandles(symbol);
    if (candles.isEmpty()) {
      return false;
    }

    final BigDecimal currentPrice = candles.getLast().close();

    if ("BULLISH".equals(marketBias)) {
      final boolean abovePDH = currentPrice.compareTo(prevRange.high()) > 0;
      if (!abovePDH) {
        logger.debug("{}: Price {} below PDH {}, skipping", symbol, currentPrice, prevRange.high());
      }
      return abovePDH;
    }

    if ("BEARISH".equals(marketBias)) {
      final boolean belowPDL = currentPrice.compareTo(prevRange.low()) < 0;
      if (!belowPDL) {
        logger.debug("{}: Price {} above PDL {}, skipping", symbol, currentPrice, prevRange.low());
      }
      return belowPDL;
    }

    return false;
  }

  private void findSetup(final String symbol) {
    final List<StockCandle> candles = stockDataService.getCompletedCandles(symbol);
    if (candles.size() < IGNORE_FIRST_CANDLES + 1) {
      return;
    }

    final StockCandle latestCandle = candles.getLast();
    final LocalDateTime lastEvaluated = lastEvaluatedCandle.get(symbol);
    if (lastEvaluated != null && !latestCandle.timestamp().isAfter(lastEvaluated)) {
      return;
    }

    updateDayLowestVolume(symbol, candles);

    final Long currentLowest = dayLowestVolume.get(symbol);
    if (currentLowest == null || latestCandle.volume() > currentLowest) {
      return;
    }

    final boolean isGreen = latestCandle.close().compareTo(latestCandle.open()) > 0;
    final boolean isRed = latestCandle.close().compareTo(latestCandle.open()) < 0;

    final boolean isOppositeColor =
        ("BEARISH".equals(marketBias) && isGreen) || ("BULLISH".equals(marketBias) && isRed);

    if (!isOppositeColor) {
      return;
    }

    final String action = "BEARISH".equals(marketBias) ? "SELL" : "BUY";
    final BigDecimal entry = "SELL".equals(action) ? latestCandle.low() : latestCandle.high();
    final BigDecimal stopLoss = "SELL".equals(action) ? latestCandle.high() : latestCandle.low();

    final BigDecimal risk = entry.subtract(stopLoss).abs();
    if (risk.compareTo(BigDecimal.ZERO) == 0) {
      return;
    }

    final BigDecimal target;
    final String riskReward;
    if ("SELL".equals(action)) {
      target = entry.subtract(risk.multiply(MIN_RISK_REWARD));
      riskReward = MIN_RISK_REWARD.stripTrailingZeros().toPlainString();
    } else {
      target = entry.add(risk.multiply(MIN_RISK_REWARD));
      riskReward = MIN_RISK_REWARD.stripTrailingZeros().toPlainString();
    }

    lastEvaluatedCandle.put(symbol, latestCandle.timestamp());

    final SignalState newSignal =
        new SignalState(
            symbol,
            action,
            entry,
            stopLoss,
            target,
            latestCandle.timestamp(),
            latestCandle.volume(),
            true,
            false);

    signalStates.put(symbol, newSignal);

    logger.info(
        "Setup found for {}: {} at {}, SL: {}, Target: {}, R:R 1:{}",
        symbol,
        action,
        entry,
        stopLoss,
        target,
        riskReward);
    telegramService.sendMessage(
        "<b>Low-Volume Strategy Setup</b>\n"
            + "Symbol: "
            + symbol
            + "\n"
            + "Action: "
            + action
            + "\n"
            + "Bias: "
            + marketBias
            + "\n"
            + "Candle: "
            + latestCandle.timestamp()
            + "\n"
            + "Entry: "
            + entry
            + "\n"
            + "SL: "
            + stopLoss
            + "\n"
            + "Target: "
            + target
            + "\n"
            + "R:R: 1:"
            + riskReward
            + "\n"
            + "<i>Waiting for entry trigger...</i>");
  }

  private void updateDayLowestVolume(final String symbol, final List<StockCandle> candles) {
    long minVolume = Long.MAX_VALUE;
    for (final StockCandle candle : candles) {
      if (candle.volume() > 0 && candle.volume() < minVolume) {
        minVolume = candle.volume();
      }
    }
    if (minVolume != Long.MAX_VALUE) {
      dayLowestVolume.put(symbol, minVolume);
    }
  }

  private void checkAndUpdateSignal(final String symbol, final SignalState signal) {
    final List<StockCandle> candles = stockDataService.getCompletedCandles(symbol);
    if (candles.size() < IGNORE_FIRST_CANDLES + 1) {
      return;
    }

    final StockCandle latestCandle = candles.getLast();
    if (latestCandle.timestamp().isAfter(signal.candleTime)) {
      final boolean isOpposite =
          ("BEARISH".equals(marketBias) && latestCandle.close().compareTo(latestCandle.open()) > 0)
              || ("BULLISH".equals(marketBias)
                  && latestCandle.close().compareTo(latestCandle.open()) < 0);
      if (isOpposite && latestCandle.volume() < signal.candleVolume) {
        signalStates.remove(symbol);
        lastEvaluatedCandle.put(symbol, latestCandle.timestamp());
        findSetup(symbol);
        return;
      }
    }

    checkEntryTrigger(symbol, signal);
  }

  private void checkEntryTrigger(final String symbol, final SignalState signal) {
    try {
      stockDataService.updateQuote(symbol);
    } catch (final Exception e) {
      logger.error("Error updating quote for entry check {}: {}", symbol, e.getMessage());
      return;
    }

    final BigDecimal currentPrice = stockDataService.getLastPrice(symbol);
    if (currentPrice == null) {
      return;
    }

    boolean entryTriggered = false;
    if ("SELL".equals(signal.action)) {
      if (currentPrice.compareTo(signal.entry) <= 0) {
        entryTriggered = true;
      }
    } else {
      if (currentPrice.compareTo(signal.entry) >= 0) {
        entryTriggered = true;
      }
    }

    if (entryTriggered) {
      signal.entryTriggered = true;
      positions.put(
          symbol,
          new PositionState(
              symbol,
              signal.action,
              signal.entry,
              signal.stopLoss,
              signal.target,
              LocalDateTime.now(IST)));

      final BigDecimal risk = signal.entry.subtract(signal.stopLoss).abs();
      final BigDecimal riskReward =
          signal.target.subtract(signal.entry).abs().divide(risk, 1, RoundingMode.HALF_UP);

      logger.info("Entry triggered for {}: {} at {}", symbol, signal.action, signal.entry);
      telegramService.sendMessage(
          "<b>Low-Volume Strategy Entry</b>\n"
              + "Symbol: "
              + symbol
              + "\n"
              + "Action: "
              + signal.action
              + "\n"
              + "Entry: "
              + signal.entry
              + "\n"
              + "SL: "
              + signal.stopLoss
              + "\n"
              + "Target: "
              + signal.target
              + "\n"
              + "R:R: 1:"
              + riskReward
              + "\n"
              + "Time: "
              + LocalDateTime.now(IST)
              + "\n"
              + "<i>Position entered. Monitoring for exit...</i>");
    }
  }

  private void manageExit(final String symbol) {
    final PositionState position = positions.get(symbol);
    if (position == null) {
      return;
    }

    try {
      stockDataService.updateQuote(symbol);
    } catch (final Exception e) {
      return;
    }

    final StockCandle latest = stockDataService.getLatestCompletedCandle(symbol);
    if (latest == null) {
      return;
    }

    final BigDecimal currentPrice = latest.close();

    if ("SELL".equals(position.action)) {
      if (currentPrice.compareTo(position.sl) >= 0) {
        exitPosition(symbol, currentPrice, "STOP_LOSS_HIT");
        return;
      }
      if (currentPrice.compareTo(position.target) <= 0) {
        telegramService.sendMessage(
            "<b>Partial Profit - "
                + symbol
                + "</b>\n"
                + "1:2 Target reached at "
                + currentPrice
                + "\n"
                + "Booking 50%, moving rest to breakeven.");
        position.targetHit = true;
        position.sl = position.entry;
      }
      if (position.targetHit && currentPrice.compareTo(position.entry) >= 0) {
        exitPosition(symbol, currentPrice, "BREAKEVEN_EXIT");
      }
    } else {
      if (currentPrice.compareTo(position.sl) <= 0) {
        exitPosition(symbol, currentPrice, "STOP_LOSS_HIT");
        return;
      }
      if (currentPrice.compareTo(position.target) >= 0) {
        telegramService.sendMessage(
            "<b>Partial Profit - "
                + symbol
                + "</b>\n"
                + "1:2 Target reached at "
                + currentPrice
                + "\n"
                + "Booking 50%, moving rest to breakeven.");
        position.targetHit = true;
        position.sl = position.entry;
      }
      if (position.targetHit && currentPrice.compareTo(position.entry) <= 0) {
        exitPosition(symbol, currentPrice, "BREAKEVEN_EXIT");
      }
    }

    final LocalTime now = LocalTime.now(IST);
    if (now.isAfter(LocalTime.of(15, 15))) {
      exitPosition(symbol, currentPrice, "TIME_SQUARE_OFF");
    }
  }

  private void exitPosition(final String symbol, final BigDecimal exitPrice, final String reason) {
    final PositionState position = positions.remove(symbol);
    signalStates.remove(symbol);
    if (position == null) {
      return;
    }

    if ("STOP_LOSS_HIT".equals(reason)) {
      attemptCount.merge(symbol, 1, Integer::sum);
    }

    final BigDecimal pnl =
        "SELL".equals(position.action)
            ? position.entry.subtract(exitPrice)
            : exitPrice.subtract(position.entry);

    logger.info(
        "Exit for {}: {} at {} (Reason: {}, P&L: {})", symbol, reason, exitPrice, reason, pnl);
    telegramService.sendMessage(
        "<b>Low-Volume Strategy Exit</b>\n"
            + "Symbol: "
            + symbol
            + "\n"
            + "Reason: "
            + reason
            + "\n"
            + "Exit: "
            + exitPrice
            + "\n"
            + "P&L: "
            + pnl
            + "\n"
            + "Time: "
            + LocalDateTime.now(IST));
  }

  public void reset() {
    marketBias = "NEUTRAL";
    signalStates.clear();
    positions.clear();
    lastEvaluatedCandle.clear();
    dayLowestVolume.clear();
    attemptCount.clear();
    logger.info("Low-volume strategy state reset");
  }

  public boolean isStrategyEnabled() {
    return strategyEnabled;
  }

  public void setStrategyEnabled(final boolean enabled) {
    this.strategyEnabled = enabled;
  }

  public String getMarketBias() {
    return marketBias;
  }

  private static class SignalState {
    final String symbol;
    final String action;
    final BigDecimal entry;
    final BigDecimal stopLoss;
    final BigDecimal target;
    final LocalDateTime candleTime;
    final long candleVolume;
    volatile boolean triggered;
    volatile boolean entryTriggered;

    SignalState(
        final String symbol,
        final String action,
        final BigDecimal entry,
        final BigDecimal stopLoss,
        final BigDecimal target,
        final LocalDateTime candleTime,
        final long candleVolume,
        final boolean triggered,
        final boolean entryTriggered) {
      this.symbol = symbol;
      this.action = action;
      this.entry = entry;
      this.stopLoss = stopLoss;
      this.target = target;
      this.candleTime = candleTime;
      this.candleVolume = candleVolume;
      this.triggered = triggered;
      this.entryTriggered = entryTriggered;
    }
  }

  private static class PositionState {
    final String symbol;
    final String action;
    final BigDecimal entry;
    volatile BigDecimal sl;
    final BigDecimal target;
    final LocalDateTime entryTime;
    volatile boolean targetHit;

    PositionState(
        final String symbol,
        final String action,
        final BigDecimal entry,
        final BigDecimal sl,
        final BigDecimal target,
        final LocalDateTime entryTime) {
      this.symbol = symbol;
      this.action = action;
      this.entry = entry;
      this.sl = sl;
      this.target = target;
      this.entryTime = entryTime;
      this.targetHit = false;
    }
  }
}
