package com.kite.trading.service;

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

    private final NseOptionChainClient nseClient;
    private final TelegramService telegramService;

    private final List<OiDataSnapshot> snapshots = new CopyOnWriteArrayList<>();
    private final AtomicReference<OiAnalysisResult> lastAnalysis = new AtomicReference<>();
    private final AtomicReference<OiDataSnapshot> entrySnapshot = new AtomicReference<>();
    private final AtomicReference<OiDataSnapshot> lastNotifiedSnapshot = new AtomicReference<>();
    private volatile List<LocalDate> knownExpiryDates = List.of();
    private volatile boolean positionEntered;
    private volatile boolean predictionSentToday;

    public OiAnalysisService(final NseOptionChainClient nseClient,
                             final TelegramService telegramService) {
        this.nseClient = nseClient;
        this.telegramService = telegramService;
    }

    public OiDataSnapshot fetchAndRecordOi() {
        final OptionChainData data = nseClient.fetchOptionChain();
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

        return snapshot;
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

        final String strategy = suggestStrategy(direction, latest, topBuildUpStrikes(latest, "PE"),
                topBuildUpStrikes(latest, "CE"));
        final List<BigDecimal> suggestedStrikes = pickStrikes(direction, latest);
        final String tradeRecommendation = buildTradeRecommendation(direction, suggestedStrikes);

        final OiAnalysisResult result = new OiAnalysisResult(
                direction, confidence, latestPcr, strategy,
                suggestedStrikes, reasoning.toString().strip(), tradeRecommendation);

        lastAnalysis.set(result);
        logger.info("OI Analysis: direction={}, confidence={}, strategy={}, pcr={}",
                direction, confidence, strategy, latestPcr);

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

    public String buildPredictionReport(final OiAnalysisResult result) {
        if (result == null) {
            return "No prediction available.";
        }

        final String emoji = switch (result.direction()) {
            case "BULLISH" -> "\uD83D\uDFE2";
            case "BEARISH" -> "\uD83D\uDD34";
            default -> "\uD83D\uDFE1";
        };

        final StringBuilder sb = new StringBuilder();
        sb.append(emoji).append(" MARKET PREDICTION (10 AM) ").append(emoji)
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
        final String message = switch (signal) {
            case PCR_SHIFT -> "PCR has shifted significantly since entry. Consider closing the position.";
            case DIRECTION_REVERSAL -> "Market direction has reversed based on OI data. Consider closing the position.";
            default -> "No exit signal.";
        };

        final StringBuilder sb = new StringBuilder();
        sb.append("\u26A0\uFE0F EXIT SIGNAL DETECTED \u26A0\uFE0F")
                .append("\n\n").append(message);

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
            final String report = buildPredictionReport(result);
            telegramService.sendMessage(report);
            predictionSentToday = true;
            logger.info("Prediction sent via Telegram: {}", result.direction());
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
        final ExitSignal signal = checkExitSignal();
        if (signal != ExitSignal.NONE) {
            final OiDataSnapshot current = snapshots.isEmpty() ? null : snapshots.getLast();
            final OiAnalysisResult newRecommendation = analyzeAndPredict();
            final String report = buildExitReport(signal, current, newRecommendation);
            telegramService.sendMessage(report);
        }
    }

    public void markPositionEntered() {
        this.positionEntered = true;
        this.entrySnapshot.set(snapshots.isEmpty() ? null : snapshots.getLast());
        logger.info("Position entry recorded for OI monitoring");
    }

    public void markPositionExited() {
        this.positionEntered = false;
        this.entrySnapshot.set(null);
        logger.info("Position exit recorded, stopping OI monitoring");
    }

    public void reset() {
        snapshots.clear();
        lastAnalysis.set(null);
        entrySnapshot.set(null);
        lastNotifiedSnapshot.set(null);
        positionEntered = false;
        predictionSentToday = false;
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
        return switch (direction) {
            case "BULLISH" -> "PUT CREDIT SPREAD";
            case "BEARISH" -> "CALL CREDIT SPREAD";
            default -> "IRON CONDOR";
        };
    }

    private List<BigDecimal> pickStrikes(final String direction, final OiDataSnapshot latest) {
        final List<BigDecimal> strikes = new ArrayList<>();
        if (latest.topOiBuildUp() == null || latest.topOiBuildUp().isEmpty()) {
            return strikes;
        }

        final BigDecimal underlying = latest.underlyingValue();
        if (underlying == null) {
            return strikes;
        }

        final BigDecimal atm = roundToNearestStrike(underlying);
        final BigDecimal twoStrikesDown = BigDecimal.valueOf(2L * STRIKE_INTERVAL);
        final BigDecimal oneStrike = BigDecimal.valueOf(STRIKE_INTERVAL);

        switch (direction) {
            case "BULLISH" -> {
                final BigDecimal putStrike = topBuildUpStrikes(latest, "PE").stream()
                        .filter(s -> s.compareTo(atm) < 0)
                        .findFirst()
                        .orElse(atm.subtract(twoStrikesDown));
                strikes.add(putStrike);
                strikes.add(putStrike.subtract(oneStrike));
            }
            case "BEARISH" -> {
                final BigDecimal callStrike = topBuildUpStrikes(latest, "CE").stream()
                        .filter(s -> s.compareTo(atm) > 0)
                        .findFirst()
                        .orElse(atm.add(twoStrikesDown));
                strikes.add(callStrike);
                strikes.add(callStrike.add(oneStrike));
            }
            default -> {
                final BigDecimal putStrike = topBuildUpStrikes(latest, "PE").stream()
                        .filter(s -> s.compareTo(atm) < 0)
                        .findFirst()
                        .orElse(atm.subtract(twoStrikesDown));
                final BigDecimal callStrike = topBuildUpStrikes(latest, "CE").stream()
                        .filter(s -> s.compareTo(atm) > 0)
                        .findFirst()
                        .orElse(atm.add(twoStrikesDown));
                strikes.add(putStrike);
                strikes.add(putStrike.subtract(oneStrike));
                strikes.add(callStrike);
                strikes.add(callStrike.add(oneStrike));
            }
        }

        return strikes;
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

    public enum ExitSignal {
        NONE, PCR_SHIFT, DIRECTION_REVERSAL
    }
}
