package com.kite.trading.scheduler;

import com.kite.trading.service.MultiTfRSINiftyOptionService;
import com.kite.trading.service.TelegramService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MultiTfRSINiftyScheduler {

  private static final Logger logger = LoggerFactory.getLogger(MultiTfRSINiftyScheduler.class);

  private static final int ONE_MINUTE_MS = 60_000;

  private final MultiTfRSINiftyOptionService strategyService;
  private final TelegramService telegramService;

  public MultiTfRSINiftyScheduler(
      final MultiTfRSINiftyOptionService strategyService, final TelegramService telegramService) {
    this.strategyService = strategyService;
    this.telegramService = telegramService;
  }

  @Scheduled(fixedRate = ONE_MINUTE_MS, initialDelay = 15_000)
  public void scheduledMultiTfCheck() {
    try {
      strategyService.evaluate();
    } catch (final Exception e) {
      logger.error("Error in Multi-TF RSI Nifty scheduler", e);
      telegramService.sendMessage("Error in Multi-TF RSI Nifty strategy: " + e.getMessage());
    }
  }

  @Scheduled(cron = "0 0 8 * * MON-FRI", zone = "Asia/Kolkata")
  public void resetDaily() {
    strategyService.resetDaily();
    telegramService.sendMessage(
        "\uD83D\uDEE1\uFE0F Multi-TF RSI Nifty Option initialized for new trading day.");
    logger.info("Multi-TF RSI Nifty daily reset complete");
  }
}
