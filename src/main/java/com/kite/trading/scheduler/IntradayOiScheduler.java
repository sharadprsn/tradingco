package com.kite.trading.scheduler;

import com.kite.trading.dto.OiDataSnapshot;
import com.kite.trading.service.OiAnalysisService;
import com.kite.trading.service.TelegramService;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class IntradayOiScheduler {

  private static final Logger logger = LoggerFactory.getLogger(IntradayOiScheduler.class);
  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  private static final LocalTime MARKET_START = LocalTime.of(9, 15);
  private static final LocalTime PREDICTION_TIME_945 = LocalTime.of(9, 45);
  private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);
  private static final int SIX_MINUTES_MS = 360_000;

  private final OiAnalysisService oiAnalysisService;
  private final TelegramService telegramService;

  private volatile boolean prediction945Executed;
  private volatile boolean autoEntryExecutedToday;

  private static final LocalTime AUTO_ENTRY_TIME = LocalTime.of(9, 50);

  public IntradayOiScheduler(
      final OiAnalysisService oiAnalysisService, final TelegramService telegramService) {
    this.oiAnalysisService = oiAnalysisService;
    this.telegramService = telegramService;
  }

  @Scheduled(fixedRate = SIX_MINUTES_MS, initialDelay = 5_000)
  public void scheduledOiCheck() {
    if (!shouldRun()) {
      return;
    }

    final LocalTime now = LocalTime.now(IST);
    logger.debug("OI scheduler tick at {}", now);

    try {
      oiAnalysisService.fetchAndRecordOi();

      final boolean isPast945 = !now.isBefore(PREDICTION_TIME_945);
      if (isPast945 && !prediction945Executed) {
        oiAnalysisService.notifyPrediction();
        prediction945Executed = true;
        logger.info("9:45 AM prediction executed and sent via Telegram");
      }

      if (prediction945Executed) {
        oiAnalysisService.checkAndNotifyDirectionChange();
      }

      final boolean isPast950 = !now.isBefore(AUTO_ENTRY_TIME);
      if (isPast950 && prediction945Executed && !autoEntryExecutedToday) {
        oiAnalysisService.markPositionEntered();
        autoEntryExecutedToday = true;
        logger.info("9:50 AM automatic position entry executed");
      }

      if (oiAnalysisService.isPositionEntered()) {
        oiAnalysisService.notifyExitIfNeeded();
      }

    } catch (final Exception e) {
      logger.error("Error in OI scheduler execution", e);
      telegramService.sendMessage("Error in OI scheduler: " + e.getMessage());
    }
  }

  @Scheduled(cron = "0 0 9 * * MON-FRI", zone = "Asia/Kolkata")
  public void resetDaily() {
    logger.info("Resetting OI scheduler state for new trading day");
    oiAnalysisService.reset();
    prediction945Executed = false;
    autoEntryExecutedToday = false;
    telegramService.sendMessage("Jai Shree Krishna");
    logger.info("Reset message sent via Telegram");
  }

  @Scheduled(cron = "0 0 16 * * MON-FRI", zone = "Asia/Kolkata")
  public void marketCloseSummary() {
    final var snapshots = oiAnalysisService.getSnapshots();
    if (!snapshots.isEmpty()) {
      final String indexLabel = oiAnalysisService.getCurrentIndexLabel();
      final var first = findClosestTo(snapshots, LocalTime.of(9, 45));
      final var last = snapshots.getLast();
      final BigDecimal val945 = first != null ? first.underlyingValue() : null;
      final BigDecimal val1530 = last.underlyingValue();
      final String movement =
          (val945 != null) ? String.format("%+.2f", val1530.subtract(val945)) : "N/A";
      final String summary =
          "9:45 AM "
              + indexLabel
              + ": "
              + (val945 != null ? val945 : "N/A")
              + "\n3:30 PM "
              + indexLabel
              + ": "
              + val1530
              + "\nMovement: "
              + movement;
      telegramService.sendMessage(summary);
    }
    oiAnalysisService.reset();
    prediction945Executed = false;
    autoEntryExecutedToday = false;
  }

  private static OiDataSnapshot findClosestTo(
      final java.util.List<OiDataSnapshot> snapshots, final LocalTime target) {
    OiDataSnapshot closest = null;
    long minDiff = Long.MAX_VALUE;
    for (final var s : snapshots) {
      final long diff = Math.abs(ChronoUnit.MILLIS.between(s.timestamp().toLocalTime(), target));
      if (diff < minDiff) {
        minDiff = diff;
        closest = s;
      }
    }
    return closest;
  }

  boolean shouldRun() {
    final LocalTime now = LocalTime.now(IST);
    final DayOfWeek day = LocalDate.now(IST).getDayOfWeek();
    if (day == DayOfWeek.SATURDAY || day == DayOfWeek.SUNDAY) {
      return false;
    }
    return !now.isBefore(MARKET_START) && !now.isAfter(MARKET_CLOSE);
  }
}
