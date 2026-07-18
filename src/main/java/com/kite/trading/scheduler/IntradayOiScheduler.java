package com.kite.trading.scheduler;

import com.kite.trading.dto.OiDataSnapshot;
import com.kite.trading.repository.OiSnapshotRepository;
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
  private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);
  private static final int SIX_MINUTES_MS = 360_000;

  private final OiAnalysisService oiAnalysisService;
  private final TelegramService telegramService;
  private final OiSnapshotRepository snapshotRepository;

  public IntradayOiScheduler(
      final OiAnalysisService oiAnalysisService,
      final TelegramService telegramService,
      final OiSnapshotRepository snapshotRepository) {
    this.oiAnalysisService = oiAnalysisService;
    this.telegramService = telegramService;
    this.snapshotRepository = snapshotRepository;
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

      oiAnalysisService.checkStabilityAndNotify();

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
    logger.info("Resetting OI state for new trading day — polling every 6 min from 9:15 AM");
    oiAnalysisService.reset();
    telegramService.sendMessage(
        "Jai Shree Krishna — OI monitor active. Polling every 6 min from 9:15 AM.");
    logger.info("Daily reset complete");
  }

  @Scheduled(cron = "0 0 16 * * MON-FRI", zone = "Asia/Kolkata")
  public void marketCloseSummary() {
    final var snapshots = oiAnalysisService.getSnapshots();
    if (!snapshots.isEmpty()) {
      final String indexLabel = oiAnalysisService.getCurrentIndexLabel();
      final var first = findClosestTo(snapshots, LocalTime.of(9, 15));
      final var last = snapshots.getLast();
      final BigDecimal val915 = first != null ? first.underlyingValue() : null;
      final BigDecimal val1530 = last.underlyingValue();
      final String movement =
          (val915 != null) ? String.format("%+.2f", val1530.subtract(val915)) : "N/A";
      final String summary =
          "9:15 AM "
              + indexLabel
              + ": "
              + (val915 != null ? val915 : "N/A")
              + "\n3:30 PM "
              + indexLabel
              + ": "
              + val1530
              + "\nMovement: "
              + movement;
      telegramService.sendMessage(summary);
    }

    oiAnalysisService.reset();
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
