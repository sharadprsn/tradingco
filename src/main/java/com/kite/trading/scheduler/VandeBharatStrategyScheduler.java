package com.kite.trading.scheduler;

import com.kite.trading.service.TelegramService;
import com.kite.trading.service.VandeBharatStrategyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class VandeBharatStrategyScheduler {

  private static final Logger logger = LoggerFactory.getLogger(VandeBharatStrategyScheduler.class);

  private static final int FIVE_MINUTES_MS = 300_000;

  private final VandeBharatStrategyService strategyService;
  private final TelegramService telegramService;

  public VandeBharatStrategyScheduler(
      final VandeBharatStrategyService strategyService, final TelegramService telegramService) {
    this.strategyService = strategyService;
    this.telegramService = telegramService;
  }

  @Scheduled(fixedRate = FIVE_MINUTES_MS, initialDelay = 10_000)
  public void scheduledVandeBharatCheck() {
    try {
      strategyService.analyze();
    } catch (final Exception e) {
      logger.error("Error in Vande Bharat scheduler", e);
      telegramService.sendMessage("Error in Vande Bharat strategy: " + e.getMessage());
    }
  }

  @Scheduled(cron = "0 0 9 * * MON-FRI", zone = "Asia/Kolkata")
  public void resetDaily() {
    strategyService.resetDaily();
    telegramService.sendMessage(
        "\uD83D\uDEE1\uFE0F Vande Bharat strategy initialized for new trading day.");
    logger.info("Vande Bharat daily reset complete");
  }

  @Scheduled(cron = "0 10 9 * * MON-FRI", zone = "Asia/Kolkata")
  public void preMarketScan() {
    try {
      strategyService.preMarketScan();
    } catch (final Exception e) {
      logger.error("Error in Vande Bharat pre-market scan", e);
      telegramService.sendMessage("Error in pre-market scan: " + e.getMessage());
    }
  }
}
