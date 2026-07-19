package com.kite.trading.scheduler;

import com.kite.trading.service.SniperStrategyService;
import com.kite.trading.service.TelegramService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class SniperStrategyScheduler {

  private static final Logger logger = LoggerFactory.getLogger(SniperStrategyScheduler.class);

  private static final int ONE_MINUTE_MS = 60_000;

  private final SniperStrategyService strategyService;
  private final TelegramService telegramService;

  public SniperStrategyScheduler(
      final SniperStrategyService strategyService, final TelegramService telegramService) {
    this.strategyService = strategyService;
    this.telegramService = telegramService;
  }

  @Scheduled(fixedRate = ONE_MINUTE_MS, initialDelay = 20_000)
  public void scheduledSniperCheck() {
    try {
      strategyService.evaluate();
    } catch (final Exception e) {
      logger.error("Error in Sniper strategy scheduler", e);
      telegramService.sendMessage("Error in Sniper strategy: " + e.getMessage());
    }
  }

  @Scheduled(cron = "0 0 8 * * MON-FRI", zone = "Asia/Kolkata")
  public void resetDaily() {
    strategyService.resetDaily();
    telegramService.sendMessage(
        "\uD83D\uDEE1\uFE0F Sniper Strategy (NIFTY 50 & SENSEX options) initialized for new trading day.");
    logger.info("Sniper strategy daily reset complete");
  }
}
