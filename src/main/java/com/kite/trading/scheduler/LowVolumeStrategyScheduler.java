package com.kite.trading.scheduler;

import com.kite.trading.config.StockStrategyConfig;
import com.kite.trading.service.LowVolumeStrategyService;
import com.kite.trading.service.StockMarketDataService;
import com.kite.trading.service.TelegramService;
import jakarta.annotation.PostConstruct;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class LowVolumeStrategyScheduler {

  private static final Logger logger = LoggerFactory.getLogger(LowVolumeStrategyScheduler.class);
  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  private static final LocalTime MARKET_START = LocalTime.of(9, 15);
  private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);

  private final StockStrategyConfig config;
  private final StockMarketDataService stockDataService;
  private final LowVolumeStrategyService strategyService;
  private final TelegramService telegramService;
  private volatile boolean biasEvaluatedToday = false;

  public LowVolumeStrategyScheduler(
      final StockStrategyConfig config,
      final StockMarketDataService stockDataService,
      final LowVolumeStrategyService strategyService,
      final TelegramService telegramService) {
    this.config = config;
    this.stockDataService = stockDataService;
    this.strategyService = strategyService;
    this.telegramService = telegramService;
  }

  @PostConstruct
  public void init() {
    if (config.isEnabled()) {
      stockDataService.loadPreviousRanges();
    }
  }

  @Scheduled(fixedRate = 60_000, initialDelay = 30_000)
  public void scheduledStockDataPoll() {
    if (!shouldRun()) {
      return;
    }

    if (!config.isEnabled() || config.getWatchlist().isEmpty()) {
      return;
    }

    final LocalTime now = LocalTime.now(IST);

    try {
      if (now.isAfter(LocalTime.of(9, 24))
          && now.isBefore(LocalTime.of(9, 26))
          && !biasEvaluatedToday) {
        biasEvaluatedToday = true;
        strategyService.evaluateMarketBias();
        return;
      }

      if (now.isBefore(LocalTime.of(9, 30))) {
        return;
      }

      final List<String> watchlist = config.getWatchlist();
      for (final String symbol : watchlist) {
        try {
          stockDataService.updateQuote(symbol);
        } catch (final Exception e) {
          logger.error("Error polling {}: {}", symbol, e.getMessage());
        }
      }

      if (now.getMinute() % config.getCandleIntervalMinutes() == 0) {
        for (final String symbol : watchlist) {
          try {
            strategyService.evaluateStock(symbol);
          } catch (final Exception e) {
            logger.error("Error evaluating {}: {}", symbol, e.getMessage());
          }
        }
      }

    } catch (final Exception e) {
      logger.error("Error in low-volume strategy scheduler", e);
    }
  }

  @Scheduled(cron = "0 0 9 * * MON-FRI", zone = "Asia/Kolkata")
  public void resetDaily() {
    if (!config.isEnabled()) {
      return;
    }
    logger.info("Resetting low-volume strategy for new trading day");
    biasEvaluatedToday = false;
    stockDataService.reset();
    strategyService.reset();
    telegramService.sendMessage("Low-Volume Strategy ready. Monitoring watchlist at 9:30 AM.");
  }

  @Scheduled(cron = "0 30 15 * * MON-FRI", zone = "Asia/Kolkata")
  public void marketCloseSummary() {
    if (!config.isEnabled()) {
      return;
    }
    stockDataService.saveCurrentRanges();
    logger.info("Low-volume strategy daily summary");
    telegramService.sendMessage("Low-Volume Strategy: Market closed. Resetting for tomorrow.");
  }

  private boolean shouldRun() {
    if (!config.isEnabled()) {
      return false;
    }
    final LocalTime now = LocalTime.now(IST);
    final DayOfWeek day = LocalDate.now(IST).getDayOfWeek();
    if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
      return false;
    }
    return !now.isBefore(MARKET_START) && !now.isAfter(MARKET_CLOSE);
  }
}
