package com.kite.trading.scheduler;

import com.kite.trading.service.OiAnalysisService;
import com.kite.trading.service.OiAnalysisService.ExitSignal;
import com.kite.trading.service.TelegramService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;

@Component
public class IntradayOiScheduler {

    private static final Logger logger = LoggerFactory.getLogger(IntradayOiScheduler.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime MARKET_START = LocalTime.of(9, 30);
    private static final LocalTime PREDICTION_TIME = LocalTime.of(9, 45);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);
    private static final int SIX_MINUTES_MS = 360_000;

    private final OiAnalysisService oiAnalysisService;
    private final TelegramService telegramService;

    private volatile boolean predictionExecutedToday;

    public IntradayOiScheduler(final OiAnalysisService oiAnalysisService,
                               final TelegramService telegramService) {
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

            final boolean isPastPredictionTime = !now.isBefore(PREDICTION_TIME);
            if (isPastPredictionTime && !predictionExecutedToday) {
                oiAnalysisService.notifyPrediction();
                predictionExecutedToday = true;
                logger.info("9:45 AM prediction executed and sent via Telegram");
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
        predictionExecutedToday = false;
        final String baseUrl = System.getenv().getOrDefault("APP_BASE_URL", "https://localhost:443");
        final String loginEndpoint = baseUrl.replaceAll("/+$", "") + "/api/v1/auth/login-url";
        final String message = "\uD83D\uDD11 Authenticate with Kite to start trading:\n" + loginEndpoint;
        telegramService.sendMessage(message);
        logger.info("Authentication URL sent via Telegram: {}", loginEndpoint);
    }

    @Scheduled(cron = "0 0 16 * * MON-FRI", zone = "Asia/Kolkata")
    public void marketCloseSummary() {
        if (!oiAnalysisService.getSnapshots().isEmpty()) {
            final var latest = oiAnalysisService.getSnapshots().getLast();
            final var analysis = oiAnalysisService.getLastAnalysis();
            final StringBuilder summary = new StringBuilder();
            summary.append("Market Close Summary")
                    .append("\nFinal Nifty: ").append(latest.underlyingValue())
                    .append("\nFinal PCR: ").append(latest.pcr());
            if (analysis != null) {
                summary.append("\nPredicted Direction: ").append(analysis.direction())
                        .append("\nStrategy: ").append(analysis.suggestedStrategy());
            }
            summary.append("\nTotal snapshots: ").append(oiAnalysisService.getSnapshots().size());
            telegramService.sendMessage(summary.toString());
        }
        oiAnalysisService.reset();
        predictionExecutedToday = false;
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
