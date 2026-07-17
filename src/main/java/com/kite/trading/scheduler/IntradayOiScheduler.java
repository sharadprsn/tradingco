package com.kite.trading.scheduler;

import com.kite.trading.dto.OiDataSnapshot;
import com.kite.trading.entity.OiSnapshotEntity;
import com.kite.trading.ml.MlService;
import com.kite.trading.repository.OiSnapshotRepository;
import com.kite.trading.service.OiAnalysisService;
import com.kite.trading.service.TelegramService;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
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
  private final MlService mlService;
  private final OiSnapshotRepository snapshotRepository;

  // Dynamic stability gate — no fixed-time flags needed

  public IntradayOiScheduler(
      final OiAnalysisService oiAnalysisService,
      final TelegramService telegramService,
      final MlService mlService,
      final OiSnapshotRepository snapshotRepository) {
    this.oiAnalysisService = oiAnalysisService;
    this.telegramService = telegramService;
    this.mlService = mlService;
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
      // 1. Collect fresh OI snapshot
      oiAnalysisService.fetchAndRecordOi();

      // 2. Check OI stability → if stable & confidence > 80%, fires Calendar Spread alert
      oiAnalysisService.checkStabilityAndNotify();

      // 3. If a position was manually entered, monitor for exit signals
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
        "🙏 Jai Shree Krishna — OI monitor active. Polling every 6 min from 9:15 AM.");
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

    // Train on accumulated H2 data across days for better generalization
    final LocalDate monthAgo = LocalDate.now(IST).minusDays(60);
    final List<OiSnapshotEntity> historicalEntities =
        snapshotRepository.findByTimestampBetweenOrderByTimestampAsc(
            monthAgo.atStartOfDay(), LocalDateTime.now(IST));
    final List<OiDataSnapshot> trainingData =
        historicalEntities.stream().map(OiSnapshotEntity::toSnapshot).toList();
    if (!trainingData.isEmpty()) {
      logger.info("Training ML model on {} historical samples from H2", trainingData.size());
    }
    final List<OiDataSnapshot> dataForTraining = trainingData.isEmpty() ? snapshots : trainingData;
    // Record actual outcome for ML accuracy tracking
    if (snapshots.size() >= 2) {
      final double firstVal =
          snapshots.getFirst().underlyingValue() != null
              ? snapshots.getFirst().underlyingValue().doubleValue()
              : 0.0;
      final double lastVal =
          snapshots.getLast().underlyingValue() != null
              ? snapshots.getLast().underlyingValue().doubleValue()
              : 0.0;
      if (firstVal > 0) {
        final double actualReturn = (lastVal - firstVal) / firstVal;
        mlService.recordPrediction(
            snapshots.getFirst().pcr().compareTo(java.math.BigDecimal.valueOf(1.2)) > 0
                ? "BULLISH"
                : snapshots.getFirst().pcr().compareTo(java.math.BigDecimal.valueOf(0.8)) < 0
                    ? "BEARISH"
                    : "NEUTRAL",
            0.5,
            actualReturn);
      }
    }

    final MlService.TrainResult trainResult = mlService.train(dataForTraining);
    if (trainResult != null && "success".equals(trainResult.status())) {
      final MlService.AccuracyReport accReport = mlService.computeAccuracyReport();
      final StringBuilder msg = new StringBuilder();
      msg.append("ML model retrained: samples=").append(trainResult.samples());
      if (accReport.totalPredictions() > 0) {
        msg.append("\nAccuracy: ")
            .append(String.format("%.1f%%", accReport.accuracy() * 100))
            .append(" (")
            .append(accReport.correctPredictions())
            .append("/")
            .append(accReport.totalPredictions())
            .append(")")
            .append("\nAvg conf (correct): ")
            .append(String.format("%.1f%%", accReport.avgConfCorrect() * 100))
            .append(" | Avg conf (wrong): ")
            .append(String.format("%.1f%%", accReport.avgConfWrong() * 100));
        if (accReport.bestThreshold() > 0) {
          msg.append("\nBest threshold: ")
              .append(String.format("%.0f%%", accReport.bestThreshold() * 100));
        }
      }
      telegramService.sendMessage(msg.toString());

      // Write backtest CSV
      mlService.writeBacktestCsv(java.nio.file.Paths.get("./data/predictions.csv"));
    } else if (trainResult != null && "skipped".equals(trainResult.status())) {
      logger.info("ML training skipped: {}", trainResult.reason());
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
