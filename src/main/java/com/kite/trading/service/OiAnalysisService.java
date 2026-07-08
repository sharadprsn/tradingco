package com.kite.trading.service;

import com.kite.trading.dto.IndexQuote;
import com.kite.trading.dto.IndexQuote.IndexData;
import com.kite.trading.dto.OiAnalysisResult;
import com.kite.trading.dto.OiDataSnapshot;
import com.kite.trading.dto.OiDataSnapshot.OiStrikeInfo;
import com.kite.trading.dto.OptionChainData;
import com.kite.trading.dto.OptionChainData.OptionContract;
import com.kite.trading.dto.OptionChainData.OptionData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

@Service
public class OiAnalysisService {

    private static final Logger logger = LoggerFactory.getLogger(OiAnalysisService.class);

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime PREDICTION_TIME = LocalTime.of(10, 0);
    private static final BigDecimal PCR_BULLISH_THRESHOLD = BigDecimal.valueOf(1.2);
    private static final BigDecimal PCR_BEARISH_THRESHOLD = BigDecimal.valueOf(0.8);
    private static final BigDecimal OI_CHANGE_SIGNIFICANCE = BigDecimal.valueOf(20);
    private static final int TOP_STRIKES_COUNT = 5;
    private static final int NEAR_STRIKE_RANGE = 5;
    private static final int STRIKE_INTERVAL = 50;
    private static final BigDecimal EXIT_PCR_SHIFT = BigDecimal.valueOf(0.3);
    private static final BigDecimal EXIT_OI_SURGE = BigDecimal.valueOf(50);
    private static final BigDecimal MIN_PCR_CHANGE_FOR_NOTIFICATION = BigDecimal.valueOf(0.05);
    private static final BigDecimal MIN_OI_CHANGE_FRACTION = BigDecimal.valueOf(0.05);

    // === Exit strategy improvement constants ===
    private static final int CONFIRMATION_CONSECUTIVE = 2;
    private static final int PCR_VOLATILITY_WINDOW = 8;
    private static final BigDecimal PCR_VOLATILITY_MULTIPLIER = BigDecimal.valueOf(2);
    private static final int DIRECTION_ROLLING_WINDOW = 3;
    private static final long EXIT_COOLDOWN_MINUTES = 15;
    private static final BigDecimal EXIT_CONFIDENCE_THRESHOLD = BigDecimal.valueOf(50);
    private static final BigDecimal PRICE_CONFIRMATION_PCT = BigDecimal.valueOf(0.5);
    private static final int DAYS_TO_EXPIRY_DAMPENING = 2;
    private static final BigDecimal TIME_DECAY_FACTOR = BigDecimal.valueOf(1.5);

    // === Loss mitigation constants ===
    private static final LocalTime AFTERNOON_THRESHOLD = LocalTime.of(14, 30);
    private static final BigDecimal HARD_STOP_LOSS_PCT = BigDecimal.valueOf(1.0);
    private static final BigDecimal TRAILING_STOP_PCT = BigDecimal.valueOf(0.5);
    private static final BigDecimal LOSS_CAP_PCT = BigDecimal.valueOf(2.0);
    private static final BigDecimal AFTERNOON_THRESHOLD_MULTIPLIER = BigDecimal.valueOf(0.5);
    private static final int STRONG_SIGNAL_CONFIRMATION_REQUIRED = 1;

    // === SuperTrend constants (15-min timeframe = 3 snapshots at 6-min intervals) ===
    private static final int SUPERTREND_PERIOD = 3;
    private static final BigDecimal SUPERTREND_MULTIPLIER = BigDecimal.valueOf(3);

    // === Strike selection improvement constants ===
    private static final int STRIKE_WEIGHT_MIN_STRIKE = 1;
    private static final BigDecimal CREDIT_RISK_MIN_RATIO = BigDecimal.valueOf(0.25);
    private static final BigDecimal OI_CHANGE_Z_SCORE_THRESHOLD = BigDecimal.valueOf(2.0);
    private static final int VOLATILITY_WINDOW = 5;
    private static final BigDecimal BASE_SPREAD_MULTIPLIER = BigDecimal.valueOf(0.8);
    private static final BigDecimal MAX_SPREAD_MULTIPLIER = BigDecimal.valueOf(2.5);
    private static final BigDecimal HIGH_CONFIDENCE_THRESHOLD = BigDecimal.valueOf(80);
    private static final int MAX_HEDGE_STRIKES = 3;

    private final OptionChainClient optionChainClient;
    private final TelegramService telegramService;

    private final List<OiDataSnapshot> snapshots = new CopyOnWriteArrayList<>();
    private final List<CandleData> candleData = new CopyOnWriteArrayList<>();
    private final AtomicReference<OiAnalysisResult> lastAnalysis = new AtomicReference<>();
    private final AtomicReference<OiDataSnapshot> entrySnapshot = new AtomicReference<>();
    private final AtomicReference<OiDataSnapshot> lastNotifiedSnapshot = new AtomicReference<>();
    private volatile List<LocalDate> knownExpiryDates = List.of();
    private volatile boolean positionEntered;
    private volatile boolean predictionSentToday;

    // === Exit strategy improvement state ===
    private volatile ExitSignal lastDetectedSignal = ExitSignal.NONE;
    private volatile int confirmationStreak;
    private volatile BigDecimal entryPrice = BigDecimal.ZERO;
    private volatile String entryDirection = "NEUTRAL";
    private volatile String superTrendDirection = "NONE";
    private volatile Instant lastExitFiredAt = Instant.MIN;
    private volatile BigDecimal highestPriceSinceEntry = BigDecimal.ZERO;
    private volatile BigDecimal lowestPriceSinceEntry = BigDecimal.ZERO;
    private volatile boolean earlyWarningSent;

    public OiAnalysisService(final OptionChainClient optionChainClient,
                             final TelegramService telegramService) {
        this.optionChainClient = optionChainClient;
        this.telegramService = telegramService;
    }

    public OiDataSnapshot fetchAndRecordOi() {
        final OptionChainData data = optionChainClient.fetchOptionChain();
        if (data == null || data.records() == null || data.records().data() == null) {
            logger.warn("No option chain data available");
            return null;
        }

        if (data.records().expiryDates() != null) {
            this.knownExpiryDates = data.records().expiryDates().stream()
                    .map(d -> LocalDate.parse(d, DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH)))
                    .sorted()
                    .toList();
        }

        final List<OptionData> allOptions = data.records().data();
        final BigDecimal underlying = data.records().underlyingValue();
        if (underlying == null) {
            logger.warn("Underlying value not available");
            return null;
        }

        final BigDecimal atmStrike = roundToNearestStrike(underlying);
        final BigDecimal minStrike = atmStrike.subtract(BigDecimal.valueOf(NEAR_STRIKE_RANGE * STRIKE_INTERVAL));
        final BigDecimal maxStrike = atmStrike.add(BigDecimal.valueOf(NEAR_STRIKE_RANGE * STRIKE_INTERVAL));

        BigDecimal totalPeOi = BigDecimal.ZERO;
        BigDecimal totalCeOi = BigDecimal.ZERO;
        BigDecimal totalPeOiChange = BigDecimal.ZERO;
        BigDecimal totalCeOiChange = BigDecimal.ZERO;

        final List<OiStrikeInfo> buildUpList = new ArrayList<>();

        for (final OptionData option : allOptions) {
            if (option.strikePrice() == null
                    || option.strikePrice().compareTo(minStrike) < 0
                    || option.strikePrice().compareTo(maxStrike) > 0) {
                continue;
            }
            if (option.pe() != null) {
                final BigDecimal oi = safeOi(option.pe().openInterest());
                final BigDecimal change = safeOi(option.pe().changeinOpenInterest());
                totalPeOi = totalPeOi.add(oi);
                totalPeOiChange = totalPeOiChange.add(change);
                if (change.compareTo(BigDecimal.ZERO) > 0) {
                    buildUpList.add(new OiStrikeInfo(
                            option.strikePrice(), "PE", oi, change,
                            option.pe().pchangeinOpenInterest()));
                }
            }
            if (option.ce() != null) {
                final BigDecimal oi = safeOi(option.ce().openInterest());
                final BigDecimal change = safeOi(option.ce().changeinOpenInterest());
                totalCeOi = totalCeOi.add(oi);
                totalCeOiChange = totalCeOiChange.add(change);
                if (change.compareTo(BigDecimal.ZERO) > 0) {
                    buildUpList.add(new OiStrikeInfo(
                            option.strikePrice(), "CE", oi, change,
                            option.ce().pchangeinOpenInterest()));
                }
            }
        }

        final BigDecimal pcr = totalCeOi.compareTo(BigDecimal.ZERO) > 0
                ? totalPeOi.divide(totalCeOi, 4, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;

        buildUpList.sort(Comparator.comparing(OiStrikeInfo::changeInOi).reversed());
        final List<OiStrikeInfo> topBuildUp = buildUpList.size() > TOP_STRIKES_COUNT
                ? buildUpList.subList(0, TOP_STRIKES_COUNT)
                : buildUpList;

        final OiDataSnapshot snapshot = new OiDataSnapshot(
                LocalDateTime.now(IST), underlying,
                totalPeOi, totalCeOi, totalPeOiChange, totalCeOiChange,
                pcr, topBuildUp);

        snapshots.add(snapshot);
        logger.info("OI snapshot recorded: PCR={}, Underlying={}, PE Change={}, CE Change={}",
                pcr, underlying, totalPeOiChange, totalCeOiChange);

        if (snapshots.size() > 100) {
            snapshots.remove(0);
        }

        if (positionEntered && underlying != null) {
            if (underlying.compareTo(highestPriceSinceEntry) > 0) {
                highestPriceSinceEntry = underlying;
            }
            if (underlying.compareTo(lowestPriceSinceEntry) < 0) {
                lowestPriceSinceEntry = underlying;
            }
        }

        recordCandleData();

        return snapshot;
    }

    private void recordCandleData() {
        final IndexQuote quote = optionChainClient.fetchIndexQuote();
        if (quote != null && quote.data() != null && !quote.data().isEmpty()) {
            final IndexData nifty50 = quote.data().getFirst();
            if (nifty50.high() != null && nifty50.low() != null) {
                candleData.add(new CandleData(nifty50.high(), nifty50.low()));
                if (candleData.size() > 100) {
                    candleData.remove(0);
                }
                logger.debug("Index quote recorded: high={}, low={}", nifty50.high(), nifty50.low());
            }
        }
    }

    public OiAnalysisResult analyzeAndPredict() {
        if (snapshots.isEmpty()) {
            logger.warn("No OI snapshots available for prediction");
            return null;
        }

        final OiDataSnapshot first = snapshots.getFirst();
        final OiDataSnapshot latest = snapshots.getLast();

        final BigDecimal peOiChangeOverPeriod = latest.totalPeOi().subtract(first.totalPeOi());
        final BigDecimal ceOiChangeOverPeriod = latest.totalCeOi().subtract(first.totalCeOi());
        final BigDecimal pcrChange = latest.pcr().subtract(first.pcr());

        final BigDecimal totalChange = peOiChangeOverPeriod.abs().add(ceOiChangeOverPeriod.abs());
        final BigDecimal peChangePercent = totalChange.compareTo(BigDecimal.ZERO) > 0
                ? peOiChangeOverPeriod.abs().multiply(BigDecimal.valueOf(100))
                        .divide(totalChange, 2, RoundingMode.HALF_UP)
                : BigDecimal.valueOf(50);

        final BigDecimal latestPcr = latest.pcr();

        final StringBuilder reasoning = new StringBuilder();

        String direction;
        BigDecimal confidence;
        if (peOiChangeOverPeriod.compareTo(ceOiChangeOverPeriod) > 0
                && peChangePercent.compareTo(BigDecimal.valueOf(60)) > 0) {
            direction = "BULLISH";
            confidence = peChangePercent.min(BigDecimal.valueOf(90));
            reasoning.append("PE OI buildup dominates (")
                    .append(peChangePercent).append("% of total OI change). ");
        } else if (ceOiChangeOverPeriod.compareTo(peOiChangeOverPeriod) > 0
                && BigDecimal.valueOf(100).subtract(peChangePercent)
                        .compareTo(BigDecimal.valueOf(60)) > 0) {
            direction = "BEARISH";
            confidence = BigDecimal.valueOf(100).subtract(peChangePercent)
                    .min(BigDecimal.valueOf(90));
            reasoning.append("CE OI buildup dominates (")
                    .append(BigDecimal.valueOf(100).subtract(peChangePercent))
                    .append("% of total OI change). ");
        } else {
            direction = "NEUTRAL";
            confidence = BigDecimal.valueOf(50);
            reasoning.append("Balanced OI buildup between PE and CE. ");
        }

        if (latestPcr.compareTo(PCR_BULLISH_THRESHOLD) >= 0) {
            reasoning.append("PCR is ").append(latestPcr).append(" (bullish). ");
        } else if (latestPcr.compareTo(PCR_BEARISH_THRESHOLD) <= 0) {
            reasoning.append("PCR is ").append(latestPcr).append(" (bearish). ");
        } else {
            reasoning.append("PCR is ").append(latestPcr).append(" (neutral). ");
        }

        if (pcrChange.abs().compareTo(BigDecimal.valueOf(0.1)) > 0) {
            reasoning.append("PCR shifted by ")
                    .append(pcrChange.setScale(2, RoundingMode.HALF_UP))
                    .append(" since first snapshot. ");
        }

        final BigDecimal volatilityMultiplier = computeVolatilityMultiplier();
        final String strategy = suggestStrategy(direction, latest, topBuildUpStrikes(latest, "PE"),
                topBuildUpStrikes(latest, "CE"));
        final List<BigDecimal> suggestedStrikes = pickStrikes(direction, latest, volatilityMultiplier, confidence);
        final String tradeRecommendation = buildTradeRecommendation(direction, suggestedStrikes);

        final OiAnalysisResult result = new OiAnalysisResult(
                direction, confidence, latestPcr, strategy,
                suggestedStrikes, reasoning.toString().strip(), tradeRecommendation);

        lastAnalysis.set(result);
        logger.info("OI Analysis: direction={}, confidence={}, strategy={}, pcr={}, strikes={}",
                direction, confidence, strategy, latestPcr, suggestedStrikes);

        return result;
    }

    public ExitSignal checkExitSignal() {
        if (!positionEntered || snapshots.size() < 2) {
            return ExitSignal.NONE;
        }

        final OiDataSnapshot entry = entrySnapshot.get();
        if (entry == null) {
            return ExitSignal.NONE;
        }

        final OiDataSnapshot latest = snapshots.getLast();
        final BigDecimal pcrShift = latest.pcr().subtract(entry.pcr()).abs();

        if (pcrShift.compareTo(EXIT_PCR_SHIFT) > 0) {
            final String direction = pcrShift.compareTo(BigDecimal.ZERO) > 0 ? "increasing" : "decreasing";
            logger.warn("Exit signal: PCR shifted by {} ({}) since entry", pcrShift, direction);
            return ExitSignal.PCR_SHIFT;
        }

        final OiAnalysisResult analysis = lastAnalysis.get();
        if (analysis != null) {
            final String entryDirection = analysis.direction();
            final String currentDirection = computeDirection(latest);
            if (!entryDirection.equals(currentDirection) && !"NEUTRAL".equals(currentDirection)) {
                logger.warn("Exit signal: Direction changed from {} to {}", entryDirection, currentDirection);
                return ExitSignal.DIRECTION_REVERSAL;
            }
        }

        return ExitSignal.NONE;
    }

    public String buildOiReport(final OiDataSnapshot snapshot) {
        if (snapshot == null) {
            return "No OI data available.";
        }

        final StringBuilder sb = new StringBuilder();
        sb.append(String.format("\uD83D\uDCCA OI Update %s", snapshot.timestamp().toLocalTime()))
                .append("\nNifty: ").append(snapshot.underlyingValue())
                .append("\nPCR: ").append(snapshot.pcr().setScale(2, RoundingMode.HALF_UP))
                .append("\nPE OI: ").append(formatOi(snapshot.totalPeOi()))
                .append(" (Chg: ").append(formatOi(snapshot.totalPeOiChange())).append(")")
                .append("\nCE OI: ").append(formatOi(snapshot.totalCeOi()))
                .append(" (Chg: ").append(formatOi(snapshot.totalCeOiChange())).append(")");

        if (snapshot.topOiBuildUp() != null && !snapshot.topOiBuildUp().isEmpty()) {
            sb.append("\n\nTop OI Buildup:");
            for (final OiStrikeInfo info : snapshot.topOiBuildUp()) {
                sb.append("\n").append(info.strikePrice()).append(" ")
                        .append(info.optionType()).append(": ")
                        .append(formatOi(info.changeInOi())).append(" (")
                        .append(info.pchangeInOi()).append("%)");
            }
        }

        return sb.toString();
    }

    public String buildPredictionReport(final OiAnalysisResult result, final String timeLabel) {
        if (result == null) {
            return "No prediction available.";
        }

        final String emoji = switch (result.direction()) {
            case "BULLISH" -> "\uD83D\uDFE2";
            case "BEARISH" -> "\uD83D\uDD34";
            default -> "\uD83D\uDFE1";
        };

        final String headerLabel = timeLabel != null ? timeLabel : "9:45 AM";

        final StringBuilder sb = new StringBuilder();
        sb.append(emoji).append(" MARKET PREDICTION (").append(headerLabel).append(") ").append(emoji)
                .append("\n\nDirection: ").append(result.direction())
                .append("\nConfidence: ").append(result.confidence()).append("%")
                .append("\nPCR: ").append(result.pcr().setScale(2, RoundingMode.HALF_UP))
                .append("\n\nSuggested Strategy: ").append(result.suggestedStrategy())
                .append("\nSuggested Strikes: ").append(result.suggestedStrikes());

        if (result.tradeRecommendation() != null && !result.tradeRecommendation().isBlank()) {
            sb.append("\n\nTrade Recommendation:\n").append(result.tradeRecommendation());
        }

        sb.append("\n\nReasoning: ").append(result.reasoning());

        return sb.toString();
    }

    public String buildExitReport(final ExitSignal signal, final OiDataSnapshot currentSnapshot) {
        return buildExitReport(signal, currentSnapshot, null);
    }

    public String buildExitReport(final ExitSignal signal, final OiDataSnapshot currentSnapshot,
                                   final OiAnalysisResult newRecommendation) {
        return buildExitReport(new ExitAssessment(signal, BigDecimal.ZERO, BigDecimal.ZERO),
                currentSnapshot, newRecommendation);
    }

    public String buildExitReport(final ExitAssessment assessment, final OiDataSnapshot currentSnapshot,
                                   final OiAnalysisResult newRecommendation) {
        final String message = switch (assessment.signal()) {
            case PCR_SHIFT -> "PCR has shifted significantly since entry. Consider closing the position.";
            case DIRECTION_REVERSAL -> "Market direction has reversed based on OI data. Consider closing the position.";
            case SUPERTREND_REVERSAL -> "15-min SuperTrend reversal detected. Consider closing the position.";
            case OI_SURGE -> "OI volume surge detected. Consider closing the position.";
            default -> "No exit signal.";
        };

        final StringBuilder sb = new StringBuilder();
        sb.append("\u26A0\uFE0F EXIT SIGNAL DETECTED \u26A0\uFE0F")
                .append("\n\n").append(message);

        if (assessment.confidence().compareTo(BigDecimal.ZERO) > 0) {
            sb.append("\nConfidence: ").append(assessment.confidence()).append("%");
        }
        if (assessment.exitFraction().compareTo(BigDecimal.ZERO) > 0
                && assessment.exitFraction().compareTo(BigDecimal.ONE) < 0) {
            sb.append("\nSuggested Exit: ").append(
                    assessment.exitFraction().multiply(BigDecimal.valueOf(100)).setScale(0))
                    .append("% of position");
        }

        if (currentSnapshot != null) {
            sb.append("\n\nCurrent PCR: ")
                    .append(currentSnapshot.pcr().setScale(2, RoundingMode.HALF_UP))
                    .append("\nCurrent Nifty: ").append(currentSnapshot.underlyingValue());
        }

        if (newRecommendation != null) {
            final String emoji = switch (newRecommendation.direction()) {
                case "BULLISH" -> "\uD83D\uDFE2";
                case "BEARISH" -> "\uD83D\uDD34";
                default -> "\uD83D\uDFE1";
            };
            sb.append("\n\n\uD83D\uDD04 New Recommendation:")
                    .append("\n").append(emoji).append(" Direction: ").append(newRecommendation.direction())
                    .append(" (").append(newRecommendation.confidence()).append("%)")
                    .append("\nStrategy: ").append(newRecommendation.suggestedStrategy());
            if (newRecommendation.tradeRecommendation() != null
                    && !newRecommendation.tradeRecommendation().isBlank()) {
                sb.append("\n").append(newRecommendation.tradeRecommendation());
            }
        }

        return sb.toString();
    }

    public void notifyPrediction() {
        if (predictionSentToday) {
            return;
        }
        final OiAnalysisResult result = analyzeAndPredict();
        if (result != null) {
            final String report = buildPredictionReport(result, "9:45 AM");
            telegramService.sendMessage(report);
            predictionSentToday = true;
            logger.info("Prediction sent via Telegram: {}", result.direction());
        }
    }

    public void reprocessPrediction(final String timeLabel) {
        if (snapshots.isEmpty()) {
            logger.warn("No snapshots available for reprocessing at {}", timeLabel);
            return;
        }
        final OiAnalysisResult result = analyzeAndPredict();
        if (result != null) {
            final String report = buildPredictionReport(result, timeLabel);
            telegramService.sendMessage(report);
            logger.info("Reprocessed prediction sent via Telegram at {}: {}", timeLabel, result.direction());
        }
    }

    public void notifyOiUpdate() {
        final OiDataSnapshot snapshot = fetchAndRecordOi();
        if (snapshot == null) {
            return;
        }

        final OiDataSnapshot lastNotified = lastNotifiedSnapshot.get();
        if (lastNotified != null && !hasChangedSignificantly(snapshot, lastNotified)) {
            logger.debug("OI change below notification threshold, skipping");
            return;
        }

        final String report = buildOiReport(snapshot);
        telegramService.sendMessage(report);
        lastNotifiedSnapshot.set(snapshot);
    }

    private boolean hasChangedSignificantly(final OiDataSnapshot current, final OiDataSnapshot previous) {
        final BigDecimal pcrDiff = current.pcr().subtract(previous.pcr()).abs();
        if (pcrDiff.compareTo(MIN_PCR_CHANGE_FOR_NOTIFICATION) > 0) {
            return true;
        }

        final BigDecimal peOiChange = safeOi(current.totalPeOiChange()).abs()
                .subtract(safeOi(previous.totalPeOiChange()).abs()).abs();
        final BigDecimal ceOiChange = safeOi(current.totalCeOiChange()).abs()
                .subtract(safeOi(previous.totalCeOiChange()).abs()).abs();

        final BigDecimal prevPeOi = safeOi(previous.totalPeOi());
        if (prevPeOi.compareTo(BigDecimal.ZERO) > 0
                && peOiChange.divide(prevPeOi, 4, RoundingMode.HALF_UP)
                        .compareTo(MIN_OI_CHANGE_FRACTION) > 0) {
            return true;
        }

        final BigDecimal prevCeOi = safeOi(previous.totalCeOi());
        if (prevCeOi.compareTo(BigDecimal.ZERO) > 0
                && ceOiChange.divide(prevCeOi, 4, RoundingMode.HALF_UP)
                        .compareTo(MIN_OI_CHANGE_FRACTION) > 0) {
            return true;
        }

        return false;
    }

    public void notifyExitIfNeeded() {
        final ExitAssessment assessment = computeExitAssessment();
        if (assessment.signal() != ExitSignal.NONE) {
            final OiDataSnapshot current = snapshots.isEmpty() ? null : snapshots.getLast();
            final OiAnalysisResult newRecommendation = analyzeAndPredict();
            final String report = buildExitReport(assessment, current, newRecommendation);
            telegramService.sendMessage(report);

            final boolean isHardExit = assessment.signal() == ExitSignal.HARD_STOP
                    || assessment.signal() == ExitSignal.TRAILING_STOP;
            if (!isHardExit) {
                lastExitFiredAt = Instant.now();
            }
            confirmationStreak = 0;
            lastDetectedSignal = ExitSignal.NONE;
            earlyWarningSent = false;

            if (assessment.exitFraction().compareTo(BigDecimal.ONE) >= 0) {
                logger.warn("Full exit signal fired: {} (confidence: {}%)",
                        assessment.signal(), assessment.confidence());
            } else {
                logger.warn("Partial exit signal fired: {} (confidence: {}%, fraction: {}%)",
                        assessment.signal(), assessment.confidence(),
                        assessment.exitFraction().multiply(BigDecimal.valueOf(100)));
            }
        }
    }

    public void markPositionEntered() {
        this.positionEntered = true;
        final OiDataSnapshot current = snapshots.isEmpty() ? null : snapshots.getLast();
        this.entrySnapshot.set(current);
        if (current != null) {
            this.entryPrice = current.underlyingValue();
            this.entryDirection = computeDirection(current);
            this.highestPriceSinceEntry = current.underlyingValue();
            this.lowestPriceSinceEntry = current.underlyingValue();
        }
        this.confirmationStreak = 0;
        this.lastDetectedSignal = ExitSignal.NONE;
        this.earlyWarningSent = false;
        logger.info("Position entry recorded for OI monitoring at price={} direction={}",
                entryPrice, entryDirection);
    }

    public void markPositionExited() {
        this.positionEntered = false;
        this.entrySnapshot.set(null);
        this.highestPriceSinceEntry = BigDecimal.ZERO;
        this.lowestPriceSinceEntry = BigDecimal.ZERO;
        this.earlyWarningSent = false;
        logger.info("Position exit recorded, stopping OI monitoring");
    }

    public void reset() {
        snapshots.clear();
        candleData.clear();
        lastAnalysis.set(null);
        entrySnapshot.set(null);
        lastNotifiedSnapshot.set(null);
        positionEntered = false;
        predictionSentToday = false;
        lastDetectedSignal = ExitSignal.NONE;
        confirmationStreak = 0;
        entryPrice = BigDecimal.ZERO;
        entryDirection = "NEUTRAL";
        superTrendDirection = "NONE";
        lastExitFiredAt = Instant.MIN;
        highestPriceSinceEntry = BigDecimal.ZERO;
        lowestPriceSinceEntry = BigDecimal.ZERO;
        earlyWarningSent = false;
        logger.info("OI Analysis Service reset");
    }

    public List<OiDataSnapshot> getSnapshots() {
        return List.copyOf(snapshots);
    }

    public OiAnalysisResult getLastAnalysis() {
        return lastAnalysis.get();
    }

    public boolean isPositionEntered() {
        return positionEntered;
    }

    public boolean isPredictionSentToday() {
        return predictionSentToday;
    }

    private String buildTradeRecommendation(final String direction, final List<BigDecimal> strikes) {
        if (knownExpiryDates.isEmpty() || strikes.isEmpty()) {
            return "";
        }

        final DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd-MMM", Locale.ENGLISH);
        final String nearExpiry = knownExpiryDates.getFirst().format(fmt);
        final String farExpiry = knownExpiryDates.size() > 1
                ? knownExpiryDates.get(1).format(fmt)
                : nearExpiry;

        return switch (direction) {
            case "BULLISH" -> {
                final BigDecimal sellStrike = strikes.get(0);
                final BigDecimal hedgeStrike = strikes.size() > 1 ? strikes.get(1) : sellStrike;
                yield String.format("SELL %s PE (%s) | HEDGE: BUY %s PE (%s)",
                        sellStrike, nearExpiry, hedgeStrike, farExpiry);
            }
            case "BEARISH" -> {
                final BigDecimal sellStrike = strikes.get(0);
                final BigDecimal hedgeStrike = strikes.size() > 1 ? strikes.get(1) : sellStrike;
                yield String.format("SELL %s CE (%s) | HEDGE: BUY %s CE (%s)",
                        sellStrike, nearExpiry, hedgeStrike, farExpiry);
            }
            case "NEUTRAL" -> {
                if (strikes.size() < 4) {
                    yield "SELL PE & CE (" + nearExpiry + ")";
                }
                yield String.format("SELL %s PE & %s CE (%s) | HEDGE: BUY %s PE & %s CE (%s)",
                        strikes.get(0), strikes.get(2), nearExpiry,
                        strikes.get(1), strikes.get(3), farExpiry);
            }
            default -> "";
        };
    }

    private String suggestStrategy(final String direction, final OiDataSnapshot latest,
                                    final List<BigDecimal> putSupport, final List<BigDecimal> callResistance) {
        final String base = switch (direction) {
            case "BULLISH" -> "PUT CREDIT SPREAD";
            case "BEARISH" -> "CALL CREDIT SPREAD";
            default -> "IRON CONDOR";
        };
        final BigDecimal volatilityMultiplier = computeVolatilityMultiplier();
        final boolean isVolatile = volatilityMultiplier.compareTo(BigDecimal.valueOf(1.5)) >= 0;
        if (isVolatile) {
            return base + " (WIDE - high volatility)";
        }
        if (volatilityMultiplier.compareTo(BigDecimal.valueOf(0.8)) <= 0) {
            return base + " (TIGHT - low volatility)";
        }
        return base;
    }

    private List<BigDecimal> pickStrikes(final String direction, final OiDataSnapshot latest,
                                          final BigDecimal volatilityMultiplier, final BigDecimal confidence) {
        final List<BigDecimal> strikes = new ArrayList<>();

        final BigDecimal underlying = latest.underlyingValue();
        if (underlying == null) {
            return strikes;
        }

        final BigDecimal atm = roundToNearestStrike(underlying);
        final BigDecimal spreadWidth = computeDynamicSpreadWidth(volatilityMultiplier, confidence);
        final List<OiStrikeInfo> allBuildUp = latest.topOiBuildUp() != null
                ? latest.topOiBuildUp()
                : List.of();

        switch (direction) {
            case "BULLISH" -> {
                final BigDecimal sellStrike = bestOiStrike(allBuildUp, "PE", atm, true, spreadWidth);
                if (sellStrike == null) {
                    return strikes;
                }
                final BigDecimal hedgeStrike = bestHedgeStrike(allBuildUp, "PE", sellStrike, true, spreadWidth);
                strikes.add(sellStrike);
                strikes.add(hedgeStrike);
            }
            case "BEARISH" -> {
                final BigDecimal sellStrike = bestOiStrike(allBuildUp, "CE", atm, false, spreadWidth);
                if (sellStrike == null) {
                    return strikes;
                }
                final BigDecimal hedgeStrike = bestHedgeStrike(allBuildUp, "CE", sellStrike, false, spreadWidth);
                strikes.add(sellStrike);
                strikes.add(hedgeStrike);
            }
            default -> {
                final BigDecimal putStrike = bestOiStrike(allBuildUp, "PE", atm, true, spreadWidth);
                final BigDecimal callStrike = bestOiStrike(allBuildUp, "CE", atm, false, spreadWidth);
                if (putStrike == null || callStrike == null) {
                    return strikes;
                }
                final BigDecimal putHedge = bestHedgeStrike(allBuildUp, "PE", putStrike, true, spreadWidth);
                final BigDecimal callHedge = bestHedgeStrike(allBuildUp, "CE", callStrike, false, spreadWidth);
                strikes.add(putStrike);
                strikes.add(putHedge);
                strikes.add(callStrike);
                strikes.add(callHedge);
            }
        }

        return strikes;
    }

    private BigDecimal computeDynamicSpreadWidth(final BigDecimal volatilityMultiplier,
                                                  final BigDecimal confidence) {
        final BigDecimal baseWidth = BigDecimal.valueOf(STRIKE_INTERVAL);
        final BigDecimal volAdjusted = baseWidth.multiply(volatilityMultiplier);
        final BigDecimal confidenceFactor = confidence.compareTo(HIGH_CONFIDENCE_THRESHOLD) >= 0
                ? BigDecimal.valueOf(0.8)
                : BigDecimal.ONE;
        final BigDecimal rawWidth = volAdjusted.multiply(confidenceFactor);
        final BigDecimal minWidth = BigDecimal.valueOf(STRIKE_INTERVAL);
        final BigDecimal maxWidth = BigDecimal.valueOf(3L * STRIKE_INTERVAL);
        return rawWidth.min(maxWidth).max(minWidth);
    }

    private BigDecimal computeVolatilityMultiplier() {
        if (snapshots.size() < VOLATILITY_WINDOW + 1) {
            return BigDecimal.ONE;
        }
        BigDecimal sumAbsMove = BigDecimal.ZERO;
        final int end = snapshots.size();
        final int start = end - VOLATILITY_WINDOW;
        for (int i = start; i < end; i++) {
            sumAbsMove = sumAbsMove.add(
                    snapshots.get(i).underlyingValue()
                            .subtract(snapshots.get(i - 1).underlyingValue())
                            .abs());
        }
        final BigDecimal avgMove = sumAbsMove.divide(
                BigDecimal.valueOf(VOLATILITY_WINDOW), 2, RoundingMode.HALF_UP);
        final BigDecimal normalMove = BigDecimal.valueOf(100);
        final BigDecimal rawMultiplier = avgMove.divide(normalMove, 2, RoundingMode.HALF_UP);
        return rawMultiplier.multiply(BASE_SPREAD_MULTIPLIER)
                .max(BigDecimal.valueOf(0.6))
                .min(MAX_SPREAD_MULTIPLIER);
    }

    private BigDecimal bestOiStrike(final List<OiStrikeInfo> allBuildUp, final String optionType,
                                     final BigDecimal atm, final boolean belowAtm,
                                     final BigDecimal spreadWidth) {
        final List<OiStrikeInfo> filtered = allBuildUp.stream()
                .filter(s -> optionType.equals(s.optionType()))
                .filter(s -> belowAtm
                        ? s.strikePrice().compareTo(atm) < 0 && !isOiOutlier(s, allBuildUp, optionType)
                        : s.strikePrice().compareTo(atm) > 0 && !isOiOutlier(s, allBuildUp, optionType))
                .filter(s -> {
                    final BigDecimal distance = s.strikePrice().subtract(atm).abs();
                    return distance.compareTo(spreadWidth.multiply(BigDecimal.valueOf(2))) <= 0;
                })
                .toList();

        if (filtered.isEmpty()) {
            return computeFallbackStrike(atm, belowAtm, spreadWidth);
        }

        return filtered.stream()
                .max(Comparator.comparing(s ->
                        s.changeInOi()
                                .multiply(BigDecimal.valueOf(100))
                                .divide(s.strikePrice().subtract(atm).abs()
                                        .max(BigDecimal.valueOf(STRIKE_INTERVAL)), 2, RoundingMode.HALF_UP)
                                .multiply(BigDecimal.ONE.add(
                                        s.pchangeInOi().abs().divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP)))))
                .map(OiStrikeInfo::strikePrice)
                .orElse(computeFallbackStrike(atm, belowAtm, spreadWidth));
    }

    private BigDecimal bestHedgeStrike(final List<OiStrikeInfo> allBuildUp, final String optionType,
                                        final BigDecimal sellStrike, final boolean belowSellStrike,
                                        final BigDecimal spreadWidth) {
        final List<BigDecimal> candidateStrikes = allBuildUp.stream()
                .filter(s -> optionType.equals(s.optionType()))
                .filter(s -> belowSellStrike
                        ? s.strikePrice().compareTo(sellStrike) < 0
                        : s.strikePrice().compareTo(sellStrike) > 0)
                .map(OiStrikeInfo::strikePrice)
                .distinct()
                .sorted(belowSellStrike ? Comparator.reverseOrder() : Comparator.naturalOrder())
                .limit(MAX_HEDGE_STRIKES)
                .toList();

        if (!candidateStrikes.isEmpty()) {
            return candidateStrikes.getFirst();
        }

        return belowSellStrike
                ? sellStrike.subtract(spreadWidth)
                : sellStrike.add(spreadWidth);
    }

    private boolean isOiOutlier(final OiStrikeInfo candidate, final List<OiStrikeInfo> allBuildUp,
                                 final String optionType) {
        final List<BigDecimal> changes = allBuildUp.stream()
                .filter(s -> optionType.equals(s.optionType()))
                .map(OiStrikeInfo::changeInOi)
                .toList();
        if (changes.size() < 3) {
            return false;
        }
        final BigDecimal mean = changes.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(changes.size()), 2, RoundingMode.HALF_UP);
        final BigDecimal variance = changes.stream()
                .map(c -> c.subtract(mean).pow(2))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(changes.size()), 2, RoundingMode.HALF_UP);
        final BigDecimal stdDev = BigDecimal.valueOf(Math.sqrt(variance.doubleValue()));
        if (stdDev.compareTo(BigDecimal.ZERO) == 0) {
            return false;
        }
        final BigDecimal zScore = candidate.changeInOi().subtract(mean).abs()
                .divide(stdDev, 2, RoundingMode.HALF_UP);
        return zScore.compareTo(OI_CHANGE_Z_SCORE_THRESHOLD) > 0;
    }

    private BigDecimal computeFallbackStrike(final BigDecimal atm, final boolean belowAtm,
                                              final BigDecimal spreadWidth) {
        final BigDecimal offset = spreadWidth.max(BigDecimal.valueOf(2L * STRIKE_INTERVAL));
        return belowAtm ? atm.subtract(offset) : atm.add(offset);
    }

    private List<BigDecimal> topBuildUpStrikes(final OiDataSnapshot snapshot, final String optionType) {
        if (snapshot.topOiBuildUp() == null) {
            return List.of();
        }
        return snapshot.topOiBuildUp().stream()
                .filter(s -> optionType.equals(s.optionType()))
                .map(OiStrikeInfo::strikePrice)
                .toList();
    }

    private String computeDirection(final OiDataSnapshot snapshot) {
        final BigDecimal peChange = snapshot.totalPeOiChange();
        final BigDecimal ceChange = snapshot.totalCeOiChange();
        final BigDecimal total = peChange.abs().add(ceChange.abs());
        if (total.compareTo(BigDecimal.ZERO) == 0) {
            return "NEUTRAL";
        }
        final BigDecimal pePct = peChange.abs().multiply(BigDecimal.valueOf(100))
                .divide(total, 2, RoundingMode.HALF_UP);
        if (pePct.compareTo(BigDecimal.valueOf(60)) > 0 && peChange.compareTo(BigDecimal.ZERO) > 0) {
            return "BULLISH";
        }
        if (pePct.compareTo(BigDecimal.valueOf(40)) < 0 && ceChange.compareTo(BigDecimal.ZERO) > 0) {
            return "BEARISH";
        }
        return "NEUTRAL";
    }

    private static BigDecimal safeOi(final BigDecimal value) {
        return value != null ? value : BigDecimal.ZERO;
    }

    private static String formatOi(final BigDecimal value) {
        if (value == null) {
            return "0";
        }
        final long longVal = value.longValue();
        if (longVal >= 1_00_00_000) {
            return String.format("%.1fCr", longVal / 1_00_00_000.0);
        }
        if (longVal >= 1_00_000) {
            return String.format("%.1fL", longVal / 1_00_000.0);
        }
        return String.valueOf(longVal);
    }

    private BigDecimal roundToNearestStrike(final BigDecimal price) {
        final BigDecimal divided = price.divide(BigDecimal.valueOf(STRIKE_INTERVAL), 0, RoundingMode.HALF_UP);
        return divided.multiply(BigDecimal.valueOf(STRIKE_INTERVAL));
    }

    private record CandleData(BigDecimal high, BigDecimal low) {}

    public enum ExitSignal {
        NONE, PCR_SHIFT, DIRECTION_REVERSAL, SUPERTREND_REVERSAL, OI_SURGE,
        HARD_STOP, TRAILING_STOP
    }

    public record ExitAssessment(ExitSignal signal, BigDecimal confidence, BigDecimal exitFraction) {
        public static final ExitAssessment NONE = new ExitAssessment(ExitSignal.NONE, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private boolean checkSuperTrendReversal() {
        if (snapshots.size() < SUPERTREND_PERIOD + 1) {
            return false;
        }
        final String currentDirection = computeSuperTrendDirection();
        final String previousDirection = superTrendDirection;
        superTrendDirection = currentDirection;
        return !"NONE".equals(previousDirection)
                && !"NONE".equals(currentDirection)
                && !previousDirection.equals(currentDirection);
    }

    private String computeSuperTrendDirection() {
        if (snapshots.size() < SUPERTREND_PERIOD) {
            return "NONE";
        }

        final int snapEnd = snapshots.size();
        final int snapStart = snapEnd - SUPERTREND_PERIOD;

        BigDecimal atr;
        if (candleData.size() >= SUPERTREND_PERIOD) {
            final int cEnd = candleData.size();
            final int cStart = cEnd - SUPERTREND_PERIOD;
            BigDecimal sumRange = BigDecimal.ZERO;
            for (int i = cStart; i < cEnd; i++) {
                sumRange = sumRange.add(candleData.get(i).high().subtract(candleData.get(i).low()));
            }
            atr = sumRange.divide(BigDecimal.valueOf(SUPERTREND_PERIOD), 4, RoundingMode.HALF_UP);
        } else {
            BigDecimal sumRange = BigDecimal.ZERO;
            for (int i = snapStart; i < snapEnd - 1; i++) {
                sumRange = sumRange.add(
                        snapshots.get(i + 1).underlyingValue()
                                .subtract(snapshots.get(i).underlyingValue())
                                .abs());
            }
            atr = sumRange.divide(BigDecimal.valueOf(SUPERTREND_PERIOD - 1), 4, RoundingMode.HALF_UP);
        }

        BigDecimal sumClose = BigDecimal.ZERO;
        for (int i = snapStart; i < snapEnd; i++) {
            sumClose = sumClose.add(snapshots.get(i).underlyingValue());
        }
        final BigDecimal mid = sumClose.divide(BigDecimal.valueOf(SUPERTREND_PERIOD), 2, RoundingMode.HALF_UP);
        final BigDecimal band = SUPERTREND_MULTIPLIER.multiply(atr);
        final BigDecimal close = snapshots.getLast().underlyingValue();
        final String previous = superTrendDirection;
        if ("NONE".equals(previous)) {
            if (close.compareTo(mid.add(band)) > 0) return "BULLISH";
            if (close.compareTo(mid.subtract(band)) < 0) return "BEARISH";
            return "NONE";
        }
        if ("BULLISH".equals(previous)) {
            if (close.compareTo(mid.subtract(band)) < 0) return "BEARISH";
            return "BULLISH";
        }
        if (close.compareTo(mid.add(band)) > 0) return "BULLISH";
        return "BEARISH";
    }

    private ExitAssessment computeExitAssessment() {
        if (!positionEntered || snapshots.size() < 2) {
            return ExitAssessment.NONE;
        }

        final OiDataSnapshot entry = entrySnapshot.get();
        final OiDataSnapshot latest = snapshots.getLast();
        if (entry == null || latest == null) {
            return ExitAssessment.NONE;
        }

        final BigDecimal currentPrice = latest.underlyingValue();

        if (isHardStopTriggered(currentPrice)) {
            logger.warn("HARD STOP triggered at price={} (entry={}, direction={})",
                    currentPrice, entryPrice, entryDirection);
            return new ExitAssessment(ExitSignal.HARD_STOP, BigDecimal.valueOf(95), BigDecimal.ONE);
        }

        if (isTrailingStopTriggered(currentPrice)) {
            logger.warn("TRAILING STOP triggered at price={} (high={}, entry={})",
                    currentPrice, highestPriceSinceEntry, entryPrice);
            return new ExitAssessment(ExitSignal.TRAILING_STOP, BigDecimal.valueOf(90), BigDecimal.ONE);
        }

        if (isLossCapExceeded(currentPrice)) {
            logger.warn("LOSS CAP exceeded at price={} (entry={}, direction={})",
                    currentPrice, entryPrice, entryDirection);
            return new ExitAssessment(ExitSignal.HARD_STOP, BigDecimal.valueOf(95), BigDecimal.ONE);
        }

        if (isInCooldown()) {
            return ExitAssessment.NONE;
        }

        final BigDecimal timeMultiplier = isPastAfternoonThreshold()
                ? AFTERNOON_THRESHOLD_MULTIPLIER
                : BigDecimal.ONE;
        final BigDecimal dynamicThreshold = computeDynamicPcrThreshold()
                .multiply(getTimeDecayFactor())
                .multiply(timeMultiplier)
                .max(EXIT_PCR_SHIFT);
        final BigDecimal pcrShift = latest.pcr().subtract(entry.pcr()).abs();
        final boolean pcrShiftTriggered = pcrShift.compareTo(dynamicThreshold) > 0;

        final BigDecimal totalOiChange = latest.totalPeOiChange().abs()
                .add(latest.totalCeOiChange().abs());
        final boolean oiSurgeTriggered = totalOiChange.compareTo(EXIT_OI_SURGE) > 0;

        final String currentDir = computeRollingDirection();
        final boolean directionReversalTriggered = !entryDirection.equals(currentDir)
                && !"NEUTRAL".equals(currentDir);

        final boolean superTrendReversal = checkSuperTrendReversal();

        final boolean isStrongSignal = (pcrShiftTriggered && directionReversalTriggered)
                || (pcrShiftTriggered && superTrendReversal)
                || pcrShift.compareTo(dynamicThreshold.multiply(BigDecimal.valueOf(2))) > 0;

        final ExitSignal primarySignal;
        int requiredConfirmations = isStrongSignal
                ? STRONG_SIGNAL_CONFIRMATION_REQUIRED
                : CONFIRMATION_CONSECUTIVE;
        if (pcrShiftTriggered) {
            primarySignal = ExitSignal.PCR_SHIFT;
        } else if (directionReversalTriggered) {
            primarySignal = ExitSignal.DIRECTION_REVERSAL;
        } else if (superTrendReversal) {
            primarySignal = ExitSignal.SUPERTREND_REVERSAL;
        } else if (oiSurgeTriggered) {
            primarySignal = ExitSignal.OI_SURGE;
        } else {
            confirmationStreak = 0;
            lastDetectedSignal = ExitSignal.NONE;
            return ExitAssessment.NONE;
        }

        if (primarySignal == lastDetectedSignal) {
            confirmationStreak++;
        } else {
            confirmationStreak = 1;
            lastDetectedSignal = primarySignal;
            sendEarlyWarning(primarySignal, currentPrice);
            logger.info("Exit signal detected (pending confirmation): {} (streak {}/{})",
                    primarySignal, confirmationStreak, requiredConfirmations);
            return ExitAssessment.NONE;
        }

        if (confirmationStreak < requiredConfirmations) {
            logger.info("Exit signal pending confirmation: {} (streak {}/{})",
                    primarySignal, confirmationStreak, requiredConfirmations);
            return ExitAssessment.NONE;
        }

        final BigDecimal confidence = computeConfidence(primarySignal, pcrShift, dynamicThreshold,
                directionReversalTriggered, superTrendReversal, oiSurgeTriggered,
                currentPrice, entry);
        final BigDecimal exitFraction = computeExitFraction(primarySignal, pcrShift, dynamicThreshold);

        logger.warn("Exit signal confirmed: {} (confidence: {}%, exit fraction: {}%)",
                primarySignal, confidence, exitFraction.multiply(BigDecimal.valueOf(100)));

        return new ExitAssessment(primarySignal, confidence, exitFraction);
    }

    private boolean isHardStopTriggered(final BigDecimal currentPrice) {
        if (entryPrice == null || entryPrice.compareTo(BigDecimal.ZERO) == 0) {
            return false;
        }
        final BigDecimal priceMovePct = currentPrice.subtract(entryPrice).abs()
                .multiply(BigDecimal.valueOf(100))
                .divide(entryPrice, 2, RoundingMode.HALF_UP);
        final boolean adverseMove;
        if ("BULLISH".equals(entryDirection)) {
            adverseMove = currentPrice.compareTo(entryPrice) < 0
                    && priceMovePct.compareTo(HARD_STOP_LOSS_PCT) >= 0;
        } else if ("BEARISH".equals(entryDirection)) {
            adverseMove = currentPrice.compareTo(entryPrice) > 0
                    && priceMovePct.compareTo(HARD_STOP_LOSS_PCT) >= 0;
        } else {
            adverseMove = priceMovePct.compareTo(HARD_STOP_LOSS_PCT) >= 0;
        }
        return adverseMove;
    }

    private boolean isTrailingStopTriggered(final BigDecimal currentPrice) {
        if (entryPrice == null || entryPrice.compareTo(BigDecimal.ZERO) == 0
                || highestPriceSinceEntry.compareTo(BigDecimal.ZERO) == 0) {
            return false;
        }
        if ("BULLISH".equals(entryDirection)) {
            final BigDecimal pullback = highestPriceSinceEntry.subtract(currentPrice)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(highestPriceSinceEntry, 2, RoundingMode.HALF_UP);
            return pullback.compareTo(TRAILING_STOP_PCT) >= 0
                    && currentPrice.compareTo(entryPrice) > 0;
        }
        if ("BEARISH".equals(entryDirection)) {
            final BigDecimal pullback = currentPrice.subtract(lowestPriceSinceEntry)
                    .multiply(BigDecimal.valueOf(100))
                    .divide(lowestPriceSinceEntry, 2, RoundingMode.HALF_UP);
            return pullback.compareTo(TRAILING_STOP_PCT) >= 0
                    && currentPrice.compareTo(entryPrice) < 0;
        }
        return false;
    }

    private boolean isLossCapExceeded(final BigDecimal currentPrice) {
        if (entryPrice == null || entryPrice.compareTo(BigDecimal.ZERO) == 0) {
            return false;
        }
        final BigDecimal lossPct = estimateUnrealizedLossPct(currentPrice);
        return lossPct.compareTo(LOSS_CAP_PCT) >= 0;
    }

    private BigDecimal estimateUnrealizedLossPct(final BigDecimal currentPrice) {
        if (entryPrice == null || entryPrice.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        if ("BULLISH".equals(entryDirection)) {
            if (currentPrice.compareTo(entryPrice) >= 0) return BigDecimal.ZERO;
            return entryPrice.subtract(currentPrice).multiply(BigDecimal.valueOf(100))
                    .divide(entryPrice, 2, RoundingMode.HALF_UP);
        }
        if ("BEARISH".equals(entryDirection)) {
            if (currentPrice.compareTo(entryPrice) <= 0) return BigDecimal.ZERO;
            return currentPrice.subtract(entryPrice).multiply(BigDecimal.valueOf(100))
                    .divide(entryPrice, 2, RoundingMode.HALF_UP);
        }
        return BigDecimal.ZERO;
    }

    private boolean isPastAfternoonThreshold() {
        return !LocalTime.now(IST).isBefore(AFTERNOON_THRESHOLD);
    }

    private void sendEarlyWarning(final ExitSignal signal, final BigDecimal currentPrice) {
        if (earlyWarningSent) {
            return;
        }
        final String message = switch (signal) {
            case PCR_SHIFT ->
                "\u26A0\uFE0F EARLY WARNING: PCR shift detected (pending confirmation). Monitor closely.";
            case DIRECTION_REVERSAL ->
                "\u26A0\uFE0F EARLY WARNING: Direction reversal detected (pending confirmation). Monitor closely.";
            case SUPERTREND_REVERSAL ->
                "\u26A0\uFE0F EARLY WARNING: SuperTrend reversal detected (pending confirmation). Monitor closely.";
            case OI_SURGE ->
                "\u26A0\uFE0F EARLY WARNING: OI volume surge detected (pending confirmation). Monitor closely.";
            default -> "";
        };
        if (!message.isBlank()) {
            final BigDecimal lossPct = estimateUnrealizedLossPct(currentPrice);
            final String lossInfo = lossPct.compareTo(BigDecimal.ZERO) > 0
                    ? "\nEstimated loss: " + lossPct + "%"
                    : "";
            telegramService.sendMessage(message + lossInfo + "\nCurrent Nifty: " + currentPrice);
            earlyWarningSent = true;
            logger.info("Early warning sent for signal: {}", signal);
        }
    }

    private BigDecimal computeDynamicPcrThreshold() {
        if (snapshots.size() < PCR_VOLATILITY_WINDOW + 1) {
            return EXIT_PCR_SHIFT;
        }
        BigDecimal sumAbsChange = BigDecimal.ZERO;
        final int end = snapshots.size();
        final int start = end - PCR_VOLATILITY_WINDOW;
        for (int i = start; i < end; i++) {
            sumAbsChange = sumAbsChange.add(
                    snapshots.get(i).pcr().subtract(snapshots.get(i - 1).pcr()).abs());
        }
        final BigDecimal avgAbsChange = sumAbsChange.divide(
                BigDecimal.valueOf(PCR_VOLATILITY_WINDOW), 4, RoundingMode.HALF_UP);
        return avgAbsChange.multiply(PCR_VOLATILITY_MULTIPLIER).max(BigDecimal.valueOf(0.1));
    }

    private BigDecimal getTimeDecayFactor() {
        if (knownExpiryDates.isEmpty()) {
            return BigDecimal.ONE;
        }
        final long daysToExpiry = ChronoUnit.DAYS.between(
                LocalDate.now(IST), knownExpiryDates.getFirst());
        if (daysToExpiry <= DAYS_TO_EXPIRY_DAMPENING && daysToExpiry >= 0) {
            return TIME_DECAY_FACTOR;
        }
        return BigDecimal.ONE;
    }

    private String computeRollingDirection() {
        if (snapshots.size() < DIRECTION_ROLLING_WINDOW) {
            return computeDirection(snapshots.getLast());
        }
        BigDecimal sumPeChange = BigDecimal.ZERO;
        BigDecimal sumCeChange = BigDecimal.ZERO;
        final int start = Math.max(0, snapshots.size() - DIRECTION_ROLLING_WINDOW);
        for (int i = start; i < snapshots.size(); i++) {
            sumPeChange = sumPeChange.add(snapshots.get(i).totalPeOiChange());
            sumCeChange = sumCeChange.add(snapshots.get(i).totalCeOiChange());
        }
        final BigDecimal total = sumPeChange.abs().add(sumCeChange.abs());
        if (total.compareTo(BigDecimal.ZERO) == 0) {
            return "NEUTRAL";
        }
        final BigDecimal pePct = sumPeChange.abs().multiply(BigDecimal.valueOf(100))
                .divide(total, 2, RoundingMode.HALF_UP);
        if (pePct.compareTo(BigDecimal.valueOf(60)) > 0 && sumPeChange.compareTo(BigDecimal.ZERO) > 0) {
            return "BULLISH";
        }
        if (pePct.compareTo(BigDecimal.valueOf(40)) < 0 && sumCeChange.compareTo(BigDecimal.ZERO) > 0) {
            return "BEARISH";
        }
        return "NEUTRAL";
    }

    private boolean isPriceConfirmed(final OiDataSnapshot latest, final OiDataSnapshot entry) {
        if (entry.underlyingValue() == null || entry.underlyingValue().compareTo(BigDecimal.ZERO) == 0) {
            return true;
        }
        return isPriceConfirmed(latest.underlyingValue(), entry.underlyingValue());
    }

    private boolean isPriceConfirmed(final BigDecimal currentPrice, final BigDecimal entryPriceValue) {
        if (entryPriceValue == null || entryPriceValue.compareTo(BigDecimal.ZERO) == 0) {
            return true;
        }
        final BigDecimal priceChangePct = currentPrice.subtract(entryPriceValue)
                .abs().multiply(BigDecimal.valueOf(100))
                .divide(entryPriceValue, 2, RoundingMode.HALF_UP);
        return priceChangePct.compareTo(PRICE_CONFIRMATION_PCT) >= 0;
    }

    private boolean isInCooldown() {
        if (lastExitFiredAt == Instant.MIN) {
            return false;
        }
        return Duration.between(lastExitFiredAt, Instant.now())
                .toMinutes() < EXIT_COOLDOWN_MINUTES;
    }

    private BigDecimal computeConfidence(final ExitSignal signal, final BigDecimal pcrShift,
                                          final BigDecimal dynamicThreshold,
                                          final boolean directionReversal,
                                          final boolean superTrendReversal,
                                          final boolean oiSurge,
                                          final BigDecimal currentPrice,
                                          final OiDataSnapshot entry) {
        final BigDecimal baseConfidence = switch (signal) {
            case PCR_SHIFT -> {
                final BigDecimal ratio = pcrShift.divide(dynamicThreshold, 2, RoundingMode.HALF_UP);
                yield BigDecimal.valueOf(30).add(
                        ratio.multiply(BigDecimal.valueOf(40)).min(BigDecimal.valueOf(50)));
            }
            case DIRECTION_REVERSAL -> BigDecimal.valueOf(70);
            case SUPERTREND_REVERSAL -> BigDecimal.valueOf(65);
            case OI_SURGE -> BigDecimal.valueOf(55);
            default -> BigDecimal.ZERO;
        };
        int signalCount = 0;
        if (pcrShift.compareTo(EXIT_PCR_SHIFT) > 0) signalCount++;
        if (directionReversal) signalCount++;
        if (superTrendReversal) signalCount++;
        if (oiSurge) signalCount++;
        BigDecimal boost = BigDecimal.valueOf(signalCount > 1 ? 15 : 0);

        if (isPriceConfirmed(currentPrice, entry.underlyingValue())) {
            boost = boost.add(BigDecimal.valueOf(10));
        }

        if (isPastAfternoonThreshold()) {
            boost = boost.add(BigDecimal.valueOf(10));
        }

        final BigDecimal lossPct = estimateUnrealizedLossPct(currentPrice);
        if (lossPct.compareTo(BigDecimal.valueOf(0.5)) > 0) {
            boost = boost.add(BigDecimal.valueOf(10));
        }

        return baseConfidence.add(boost).min(BigDecimal.valueOf(95));
    }

    private BigDecimal computeExitFraction(final ExitSignal signal, final BigDecimal pcrShift,
                                            final BigDecimal dynamicThreshold) {
        if (signal == ExitSignal.PCR_SHIFT) {
            final BigDecimal ratio = pcrShift.divide(dynamicThreshold, 2, RoundingMode.HALF_UP);
            if (ratio.compareTo(BigDecimal.valueOf(1.5)) >= 0) {
                return BigDecimal.ONE;
            }
            return BigDecimal.valueOf(0.5);
        }
        if (signal == ExitSignal.SUPERTREND_REVERSAL) {
            return BigDecimal.valueOf(0.5);
        }
        return BigDecimal.ONE;
    }
}
