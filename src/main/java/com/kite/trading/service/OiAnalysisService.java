package com.kite.trading.service;

import com.kite.trading.dto.IndexQuote;
import com.kite.trading.dto.IndexQuote.IndexData;
import com.kite.trading.dto.OiAnalysisResult;
import com.kite.trading.dto.OiDataSnapshot;
import com.kite.trading.dto.OiDataSnapshot.OiStrikeInfo;
import com.kite.trading.dto.OptionChainData;
import com.kite.trading.dto.OptionChainData.OptionData;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class OiAnalysisService {

  private static final Logger logger = LoggerFactory.getLogger(OiAnalysisService.class);

  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  private static final LocalTime PREDICTION_TIME = LocalTime.of(10, 0);
  private static final BigDecimal PCR_BULLISH_THRESHOLD = BigDecimal.valueOf(1.2);
  private static final BigDecimal PCR_BEARISH_THRESHOLD = BigDecimal.valueOf(0.8);
  private static final int TOP_STRIKES_COUNT = 5;
  private static final int NEAR_STRIKE_RANGE = 5;
  private static final int STRIKE_INTERVAL_NIFTY = 50;
  private static final int STRIKE_INTERVAL_SENSEX = 100;
  private static final BigDecimal EXIT_PCR_SHIFT = BigDecimal.valueOf(0.3);
  private static final BigDecimal EXIT_OI_SURGE = BigDecimal.valueOf(50);
  private static final BigDecimal MIN_PCR_CHANGE_FOR_NOTIFICATION = BigDecimal.valueOf(0.05);
  private static final BigDecimal MIN_OI_CHANGE_FRACTION = BigDecimal.valueOf(0.05);

  // === Exit strategy constants ===
  private static final int CONFIRMATION_CONSECUTIVE = 2;
  private static final int PCR_VOLATILITY_WINDOW = 8;
  private static final BigDecimal PCR_VOLATILITY_MULTIPLIER = BigDecimal.valueOf(2);
  private static final int DIRECTION_ROLLING_WINDOW = 3;
  private static final long EXIT_COOLDOWN_MINUTES = 15;
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

  // === Position sizing constants ===
  private static final BigDecimal DEPLOYED_CAPITAL = BigDecimal.valueOf(1_000_000);
  private static final BigDecimal TARGET_PCT = BigDecimal.valueOf(0.6);
  private static final BigDecimal STOP_LOSS_PCT = BigDecimal.valueOf(1.0);
  private static final int LOT_SIZE_NIFTY = 65;
  private static final int LOT_SIZE_SENSEX = 20;

  // === SuperTrend constants ===
  private static final int SUPERTREND_PERIOD = 3;
  private static final BigDecimal SUPERTREND_MULTIPLIER = BigDecimal.valueOf(3);

  private static final BigDecimal MARGIN_PER_LOT = BigDecimal.valueOf(60_000);

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
  private volatile String initialPredictionDirection = "NEUTRAL";
  private volatile String lastNotifiedDirection = "NEUTRAL";

  // === Exit strategy improvement state ===
  private volatile ExitSignal lastDetectedSignal = ExitSignal.NONE;
  private volatile int confirmationStreak;
  private volatile BigDecimal entryPrice = BigDecimal.ZERO;
  private volatile String entryDirection = "NEUTRAL";
  private volatile String superTrendDirection = "NONE";
  private volatile Instant lastExitFiredAt = Instant.MIN;
  private volatile BigDecimal highestPriceSinceEntry = BigDecimal.ZERO;
  private volatile BigDecimal lowestPriceSinceEntry = BigDecimal.ZERO;
  private volatile BigDecimal entrySoldStrike = BigDecimal.ZERO;
  private volatile String entryStrikeType = ""; // "PE" or "CE"
  private volatile boolean earlyWarningSent;
  private volatile BigDecimal entrySoldPremium = BigDecimal.ZERO;
  private volatile BigDecimal entryHedgeStrike = BigDecimal.ZERO;
  private volatile BigDecimal entryHedgePremium = BigDecimal.ZERO;

  private volatile String currentIndex = "NIFTY";

  static String resolveIndexForDay() {
    return resolveIndexForDay(LocalDate.now(IST).getDayOfWeek());
  }

  static String resolveIndexForDay(final DayOfWeek day) {
    return switch (day) {
      case MONDAY, TUESDAY, FRIDAY -> "NIFTY";
      case WEDNESDAY, THURSDAY -> "SENSEX";
      default -> "NIFTY";
    };
  }

  private int strikeInterval() {
    return "SENSEX".equals(currentIndex) ? STRIKE_INTERVAL_SENSEX : STRIKE_INTERVAL_NIFTY;
  }

  private int lotSize() {
    return "SENSEX".equals(currentIndex) ? LOT_SIZE_SENSEX : LOT_SIZE_NIFTY;
  }

  private String indexLabel() {
    return "SENSEX".equals(currentIndex) ? "SENSEX" : "Nifty";
  }

  public String getCurrentIndexLabel() {
    return indexLabel();
  }

  public OiAnalysisService(
      final OptionChainClient optionChainClient, final TelegramService telegramService) {
    this.optionChainClient = optionChainClient;
    this.telegramService = telegramService;
  }

  public OiDataSnapshot fetchAndRecordOi() {
    this.currentIndex = resolveIndexForDay();
    final OptionChainData data = optionChainClient.fetchOptionChain(currentIndex);
    if (data == null || data.records() == null || data.records().data() == null) {
      logger.warn("No option chain data available for {}", currentIndex);
      return null;
    }

    if (data.records().expiryDates() != null) {
      this.knownExpiryDates =
          data.records().expiryDates().stream()
              .map(
                  d ->
                      LocalDate.parse(
                          d, DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH)))
              .sorted()
              .toList();
    }

    final List<OptionData> allOptions = data.records().data();
    final BigDecimal underlying = data.records().underlyingValue();
    if (underlying == null) {
      logger.warn("Underlying value not available for {}", currentIndex);
      return null;
    }

    final int interval = strikeInterval();
    final BigDecimal atmStrike = roundToNearestStrike(underlying, interval);
    final BigDecimal minStrike =
        atmStrike.subtract(BigDecimal.valueOf(NEAR_STRIKE_RANGE * interval));
    final BigDecimal maxStrike = atmStrike.add(BigDecimal.valueOf(NEAR_STRIKE_RANGE * interval));

    BigDecimal totalPeOi = BigDecimal.ZERO;
    BigDecimal totalCeOi = BigDecimal.ZERO;
    BigDecimal totalPeOiChange = BigDecimal.ZERO;
    BigDecimal totalCeOiChange = BigDecimal.ZERO;

    final List<OiDataSnapshot.StrikePremium> strikePremiums = new ArrayList<>();
    BigDecimal maxPeOi = BigDecimal.ZERO;
    BigDecimal maxCeOi = BigDecimal.ZERO;
    BigDecimal maxPeOiStrike = BigDecimal.ZERO;
    BigDecimal maxCeOiStrike = BigDecimal.ZERO;

    for (final OptionData option : allOptions) {
      if (option.strikePrice() == null) continue;
      final BigDecimal pePremium =
          option.pe() != null ? safePremium(option.pe().lastPrice()) : BigDecimal.ZERO;
      final BigDecimal cePremium =
          option.ce() != null ? safePremium(option.ce().lastPrice()) : BigDecimal.ZERO;
      strikePremiums.add(
          new OiDataSnapshot.StrikePremium(option.strikePrice(), pePremium, cePremium));

      if (option.pe() != null) {
        final BigDecimal oi = safeOi(option.pe().openInterest());
        if (oi.compareTo(maxPeOi) > 0) {
          maxPeOi = oi;
          maxPeOiStrike = option.strikePrice();
        }
      }
      if (option.ce() != null) {
        final BigDecimal oi = safeOi(option.ce().openInterest());
        if (oi.compareTo(maxCeOi) > 0) {
          maxCeOi = oi;
          maxCeOiStrike = option.strikePrice();
        }
      }
    }

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
          buildUpList.add(
              new OiStrikeInfo(
                  option.strikePrice(), "PE", oi, change, option.pe().pchangeinOpenInterest()));
        }
      }
      if (option.ce() != null) {
        final BigDecimal oi = safeOi(option.ce().openInterest());
        final BigDecimal change = safeOi(option.ce().changeinOpenInterest());
        totalCeOi = totalCeOi.add(oi);
        totalCeOiChange = totalCeOiChange.add(change);
        if (change.compareTo(BigDecimal.ZERO) > 0) {
          buildUpList.add(
              new OiStrikeInfo(
                  option.strikePrice(), "CE", oi, change, option.ce().pchangeinOpenInterest()));
        }
      }
    }

    final BigDecimal pcr =
        totalCeOi.compareTo(BigDecimal.ZERO) > 0
            ? totalPeOi.divide(totalCeOi, 4, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;

    buildUpList.sort(Comparator.comparing(OiStrikeInfo::changeInOi).reversed());
    final List<OiStrikeInfo> topBuildUp =
        buildUpList.size() > TOP_STRIKES_COUNT
            ? buildUpList.subList(0, TOP_STRIKES_COUNT)
            : buildUpList;

    final OiDataSnapshot snapshot =
        new OiDataSnapshot(
            LocalDateTime.now(IST),
            underlying,
            totalPeOi,
            totalCeOi,
            totalPeOiChange,
            totalCeOiChange,
            pcr,
            topBuildUp,
            maxPeOiStrike,
            maxCeOiStrike,
            strikePremiums);

    snapshots.add(snapshot);
    logger.info(
        "OI snapshot recorded for {}: PCR={}, Underlying={}, PE Change={}, CE Change={}",
        currentIndex,
        pcr,
        underlying,
        totalPeOiChange,
        totalCeOiChange);

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
    final IndexQuote quote = optionChainClient.fetchIndexQuote(currentIndex);
    if (quote != null && quote.data() != null && !quote.data().isEmpty()) {
      final IndexData indexData = quote.data().getFirst();
      if (indexData.high() != null && indexData.low() != null) {
        candleData.add(new CandleData(indexData.high(), indexData.low()));
        if (candleData.size() > 100) {
          candleData.remove(0);
        }
        logger.debug(
            "Index quote recorded for {}: high={}, low={}",
            currentIndex,
            indexData.high(),
            indexData.low());
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
    final BigDecimal peChangePercent =
        totalChange.compareTo(BigDecimal.ZERO) > 0
            ? peOiChangeOverPeriod
                .abs()
                .multiply(BigDecimal.valueOf(100))
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
      reasoning
          .append("PE OI buildup dominates (")
          .append(peChangePercent)
          .append("% of total OI change). ");
    } else if (ceOiChangeOverPeriod.compareTo(peOiChangeOverPeriod) > 0
        && BigDecimal.valueOf(100).subtract(peChangePercent).compareTo(BigDecimal.valueOf(60))
            > 0) {
      direction = "BEARISH";
      confidence = BigDecimal.valueOf(100).subtract(peChangePercent).min(BigDecimal.valueOf(90));
      reasoning
          .append("CE OI buildup dominates (")
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
      reasoning
          .append("PCR shifted by ")
          .append(pcrChange.setScale(2, RoundingMode.HALF_UP))
          .append(" since first snapshot. ");
    }

    final String strategy = suggestStrategy(direction);
    final List<BigDecimal> suggestedStrikes = pickStrikes(direction, latest, confidence);

    final BigDecimal vix = fetchVix();
    final BigDecimal indexOpen = fetchIndexOpen();

    final String tradeRecommendation =
        buildTradeRecommendation(direction, suggestedStrikes, confidence, latest, vix, indexOpen);

    final OiAnalysisResult result =
        new OiAnalysisResult(
            direction,
            confidence,
            latestPcr,
            strategy,
            suggestedStrikes,
            reasoning.toString().strip(),
            tradeRecommendation,
            vix,
            indexOpen,
            latest.largestPeOiStrike(),
            latest.largestCeOiStrike());

    lastAnalysis.set(result);
    logger.info(
        "OI Analysis for {}: direction={}, confidence={}, strategy={}, pcr={}, strikes={}, vix={}",
        currentIndex,
        direction,
        confidence,
        strategy,
        latestPcr,
        suggestedStrikes,
        vix);

    return result;
  }

  public String buildOiReport(final OiDataSnapshot snapshot) {
    if (snapshot == null) {
      return "No OI data available.";
    }

    final StringBuilder sb = new StringBuilder();
    sb.append(String.format("\uD83D\uDCCA OI Update %s", snapshot.timestamp().toLocalTime()))
        .append("\nNifty: ")
        .append(snapshot.underlyingValue())
        .append("\nPCR: ")
        .append(snapshot.pcr().setScale(2, RoundingMode.HALF_UP))
        .append("\nPE OI: ")
        .append(formatOi(snapshot.totalPeOi()))
        .append(" (Chg: ")
        .append(formatOi(snapshot.totalPeOiChange()))
        .append(")")
        .append("\nCE OI: ")
        .append(formatOi(snapshot.totalCeOi()))
        .append(" (Chg: ")
        .append(formatOi(snapshot.totalCeOiChange()))
        .append(")");

    if (snapshot.topOiBuildUp() != null && !snapshot.topOiBuildUp().isEmpty()) {
      sb.append("\n\nTop OI Buildup:");
      for (final OiStrikeInfo info : snapshot.topOiBuildUp()) {
        sb.append("\n")
            .append(info.strikePrice())
            .append(" ")
            .append(info.optionType())
            .append(": ")
            .append(formatOi(info.changeInOi()))
            .append(" (")
            .append(info.pchangeInOi())
            .append("%)");
      }
    }

    return sb.toString();
  }

  public String buildPredictionReport(final OiAnalysisResult result, final String timeLabel) {
    if (result == null) {
      return "No prediction available.";
    }

    final String emoji =
        switch (result.direction()) {
          case "BULLISH" -> "\uD83D\uDFE2";
          case "BEARISH" -> "\uD83D\uDD34";
          default -> "\uD83D\uDFE1";
        };

    final String headerLabel = timeLabel != null ? timeLabel : "9:45 AM";

    final StringBuilder sb = new StringBuilder();
    sb.append(emoji)
        .append(" ")
        .append(indexLabel())
        .append(" PREDICTION (")
        .append(headerLabel)
        .append(") ")
        .append(emoji)
        .append("\n\nDirection: ")
        .append(result.direction())
        .append("\nConfidence: ")
        .append(result.confidence())
        .append("%")
        .append("\nPCR: ")
        .append(result.pcr().setScale(2, RoundingMode.HALF_UP));

    if (result.vix() != null) {
      sb.append("\nVIX: ").append(result.vix().setScale(2, RoundingMode.HALF_UP));
    }
    if (result.vix() != null && result.indexOpen() != null) {
      final BigDecimal open = result.indexOpen().setScale(0, RoundingMode.HALF_UP);
      final BigDecimal range =
          open.multiply(result.vix()).divide(BigDecimal.valueOf(1600), 0, RoundingMode.HALF_UP);
      final BigDecimal lower = open.subtract(range);
      final BigDecimal upper = open.add(range);
      sb.append("\nDay Range: ")
          .append(open)
          .append(" \u00B1 ")
          .append(range)
          .append(" (")
          .append(lower)
          .append(" - ")
          .append(upper)
          .append(")");
    }
    if (result.indexOpen() != null) {
      sb.append("\nOpen: ").append(result.indexOpen().setScale(0, RoundingMode.HALF_UP));
    }

    if (result.largestPeOiStrike() != null
        && result.largestPeOiStrike().compareTo(BigDecimal.ZERO) > 0) {
      sb.append("\nMax PE OI: ")
          .append(result.largestPeOiStrike().setScale(0, RoundingMode.HALF_UP));
    }

    if (result.largestCeOiStrike() != null
        && result.largestCeOiStrike().compareTo(BigDecimal.ZERO) > 0) {
      sb.append("\nMax CE OI: ")
          .append(result.largestCeOiStrike().setScale(0, RoundingMode.HALF_UP));
    }

    sb.append("\n\nSuggested Strategy: ")
        .append(result.suggestedStrategy())
        .append("\nSuggested Strikes: ")
        .append(result.suggestedStrikes());

    if (result.tradeRecommendation() != null && !result.tradeRecommendation().isBlank()) {
      sb.append("\n\nTrade Recommendation:\n").append(result.tradeRecommendation());
    }

    sb.append("\n\nReasoning: ").append(result.reasoning());

    return sb.toString();
  }

  public String buildExitReport(final ExitSignal signal, final OiDataSnapshot currentSnapshot) {
    return buildExitReport(signal, currentSnapshot, null);
  }

  public String buildExitReport(
      final ExitSignal signal,
      final OiDataSnapshot currentSnapshot,
      final OiAnalysisResult newRecommendation) {
    return buildExitReport(
        new ExitAssessment(signal, BigDecimal.ZERO, BigDecimal.ZERO),
        currentSnapshot,
        newRecommendation);
  }

  public String buildExitReport(
      final ExitAssessment assessment,
      final OiDataSnapshot currentSnapshot,
      final OiAnalysisResult newRecommendation) {
    final String message =
        switch (assessment.signal()) {
          case PCR_SHIFT ->
              "PCR shift detected – market sentiment changing. Consider closing sold options.";
          case DIRECTION_REVERSAL ->
              "Direction reversal detected – sold options at risk. Consider closing position.";
          case SUPERTREND_REVERSAL ->
              "SuperTrend reversal detected. Consider closing sold options.";
          case OI_SURGE ->
              "OI surge detected – potential volatility ahead. Consider reducing position.";
          case STRIKE_BREACH ->
              "Underlying crossed sold strike – option is now ITM. Close position immediately.";
          case PROFIT_TARGET -> "Profit target reached – option decayed sufficiently. Book profit.";
          case HARD_STOP -> "HARD STOP-LOSS triggered – option premium doubled. Exit immediately.";
          case TRAILING_STOP -> "TRAILING STOP triggered – locking in profits. Exit immediately.";
          default -> "No exit signal.";
        };

    final StringBuilder sb = new StringBuilder();
    sb.append("\u26A0\uFE0F EXIT SIGNAL DETECTED \u26A0\uFE0F").append("\n\n").append(message);

    if (assessment.confidence().compareTo(BigDecimal.ZERO) > 0) {
      sb.append("\nConfidence: ").append(assessment.confidence()).append("%");
    }
    if (assessment.exitFraction().compareTo(BigDecimal.ZERO) > 0
        && assessment.exitFraction().compareTo(BigDecimal.ONE) < 0) {
      sb.append("\nSuggested Exit: ")
          .append(assessment.exitFraction().multiply(BigDecimal.valueOf(100)).setScale(0))
          .append("% of position");
    }

    if (currentSnapshot != null) {
      sb.append("\n\nCurrent PCR: ")
          .append(currentSnapshot.pcr().setScale(2, RoundingMode.HALF_UP))
          .append("\nCurrent ")
          .append(indexLabel())
          .append(": ")
          .append(currentSnapshot.underlyingValue());
    }

    if (newRecommendation != null) {
      final String emoji =
          switch (newRecommendation.direction()) {
            case "BULLISH" -> "\uD83D\uDFE2";
            case "BEARISH" -> "\uD83D\uDD34";
            default -> "\uD83D\uDFE1";
          };
      sb.append("\n\n\uD83D\uDD04 New Recommendation:")
          .append("\n")
          .append(emoji)
          .append(" Direction: ")
          .append(newRecommendation.direction())
          .append(" (")
          .append(newRecommendation.confidence())
          .append("%)")
          .append("\nStrategy: ")
          .append(newRecommendation.suggestedStrategy());
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
      initialPredictionDirection = result.direction();
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
      logger.info(
          "Reprocessed prediction sent via Telegram at {}: {}", timeLabel, result.direction());
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

  private boolean hasChangedSignificantly(
      final OiDataSnapshot current, final OiDataSnapshot previous) {
    final BigDecimal pcrDiff = current.pcr().subtract(previous.pcr()).abs();
    if (pcrDiff.compareTo(MIN_PCR_CHANGE_FOR_NOTIFICATION) > 0) {
      return true;
    }

    final BigDecimal peOiChange =
        safeOi(current.totalPeOiChange())
            .abs()
            .subtract(safeOi(previous.totalPeOiChange()).abs())
            .abs();
    final BigDecimal ceOiChange =
        safeOi(current.totalCeOiChange())
            .abs()
            .subtract(safeOi(previous.totalCeOiChange()).abs())
            .abs();

    final BigDecimal prevPeOi = safeOi(previous.totalPeOi());
    if (prevPeOi.compareTo(BigDecimal.ZERO) > 0
        && peOiChange.divide(prevPeOi, 4, RoundingMode.HALF_UP).compareTo(MIN_OI_CHANGE_FRACTION)
            > 0) {
      return true;
    }

    final BigDecimal prevCeOi = safeOi(previous.totalCeOi());
    if (prevCeOi.compareTo(BigDecimal.ZERO) > 0
        && ceOiChange.divide(prevCeOi, 4, RoundingMode.HALF_UP).compareTo(MIN_OI_CHANGE_FRACTION)
            > 0) {
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

      final boolean isHardExit =
          assessment.signal() == ExitSignal.HARD_STOP
              || assessment.signal() == ExitSignal.TRAILING_STOP;
      if (!isHardExit) {
        lastExitFiredAt = Instant.now();
      }
      confirmationStreak = 0;
      lastDetectedSignal = ExitSignal.NONE;
      earlyWarningSent = false;

      if (assessment.exitFraction().compareTo(BigDecimal.ONE) >= 0) {
        logger.warn(
            "Full exit signal fired: {} (confidence: {}%)",
            assessment.signal(), assessment.confidence());
      } else {
        logger.warn(
            "Partial exit signal fired: {} (confidence: {}%, fraction: {}%)",
            assessment.signal(),
            assessment.confidence(),
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
    final OiAnalysisResult analysis = lastAnalysis.get();
    if (analysis != null && !analysis.suggestedStrikes().isEmpty()) {
      final String strategy = analysis.suggestedStrategy();
      if ("DIRECTIONAL PUT SELLING".equals(strategy)) {
        this.entrySoldStrike = analysis.suggestedStrikes().get(0);
        this.entryStrikeType = "PE";
        this.entrySoldPremium =
            current != null ? lookupPremium(current, this.entrySoldStrike, "PE") : BigDecimal.ZERO;
        final BigDecimal hedge =
            current != null
                ? findHedgeStrike(current.strikePremiums(), "BULLISH", analysis.suggestedStrikes())
                : null;
        this.entryHedgeStrike = hedge != null ? hedge : BigDecimal.ZERO;
        this.entryHedgePremium =
            (current != null && hedge != null)
                ? lookupPremium(current, this.entryHedgeStrike, "PE")
                : BigDecimal.ZERO;
      } else if ("DIRECTIONAL CALL SELLING".equals(strategy)) {
        this.entrySoldStrike = analysis.suggestedStrikes().get(0);
        this.entryStrikeType = "CE";
        this.entrySoldPremium =
            current != null ? lookupPremium(current, this.entrySoldStrike, "CE") : BigDecimal.ZERO;
        final BigDecimal hedge =
            current != null
                ? findHedgeStrike(current.strikePremiums(), "BEARISH", analysis.suggestedStrikes())
                : null;
        this.entryHedgeStrike = hedge != null ? hedge : BigDecimal.ZERO;
        this.entryHedgePremium =
            (current != null && hedge != null)
                ? lookupPremium(current, this.entryHedgeStrike, "CE")
                : BigDecimal.ZERO;
      } else if ("SHORT STRANGLE".equals(strategy)) {
        this.entrySoldStrike = analysis.suggestedStrikes().get(0); // Put leg
        this.entryHedgeStrike =
            analysis.suggestedStrikes().size() > 1
                ? analysis.suggestedStrikes().get(1)
                : BigDecimal.ZERO; // Call leg
        this.entryStrikeType = "STRANGLE";
        this.entrySoldPremium =
            current != null ? lookupPremium(current, this.entrySoldStrike, "PE") : BigDecimal.ZERO;
        this.entryHedgePremium =
            (current != null && this.entryHedgeStrike.compareTo(BigDecimal.ZERO) > 0)
                ? lookupPremium(current, this.entryHedgeStrike, "CE")
                : BigDecimal.ZERO;
      } else {
        this.entrySoldStrike = analysis.suggestedStrikes().get(0);
        this.entryStrikeType = "";
        this.entrySoldPremium = BigDecimal.ZERO;
        this.entryHedgeStrike = BigDecimal.ZERO;
        this.entryHedgePremium = BigDecimal.ZERO;
      }
    }
    this.confirmationStreak = 0;
    this.lastDetectedSignal = ExitSignal.NONE;
    this.earlyWarningSent = false;
    logger.info(
        "Position entry recorded for OI monitoring at price={} direction={} strategy={} soldStrike={} (premium={}) hedgeStrike={} (premium={})",
        entryPrice,
        entryDirection,
        analysis != null ? analysis.suggestedStrategy() : "NONE",
        entrySoldStrike,
        entrySoldPremium,
        entryHedgeStrike,
        entryHedgePremium);
  }

  public void markPositionExited() {
    this.positionEntered = false;
    this.entrySnapshot.set(null);
    this.highestPriceSinceEntry = BigDecimal.ZERO;
    this.lowestPriceSinceEntry = BigDecimal.ZERO;
    this.entrySoldStrike = BigDecimal.ZERO;
    this.entryStrikeType = "";
    this.earlyWarningSent = false;
    this.entrySoldPremium = BigDecimal.ZERO;
    this.entryHedgeStrike = BigDecimal.ZERO;
    this.entryHedgePremium = BigDecimal.ZERO;
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
    initialPredictionDirection = "NEUTRAL";
    lastNotifiedDirection = "NEUTRAL";
    lastDetectedSignal = ExitSignal.NONE;
    confirmationStreak = 0;
    entryPrice = BigDecimal.ZERO;
    entryDirection = "NEUTRAL";
    superTrendDirection = "NONE";
    lastExitFiredAt = Instant.MIN;
    highestPriceSinceEntry = BigDecimal.ZERO;
    lowestPriceSinceEntry = BigDecimal.ZERO;
    entrySoldStrike = BigDecimal.ZERO;
    entryStrikeType = "";
    earlyWarningSent = false;
    entrySoldPremium = BigDecimal.ZERO;
    entryHedgeStrike = BigDecimal.ZERO;
    entryHedgePremium = BigDecimal.ZERO;
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

  public String getInitialPredictionDirection() {
    return initialPredictionDirection;
  }

  public void checkAndNotifyDirectionChange() {
    if (!predictionSentToday) {
      return;
    }
    final OiAnalysisResult result = analyzeAndPredict();
    if (result == null) {
      return;
    }
    final String current = result.direction();
    if (current.equals(initialPredictionDirection) || current.equals(lastNotifiedDirection)) {
      return;
    }
    lastNotifiedDirection = current;
    final LocalTime now = LocalTime.now(IST);
    final String timeLabel =
        String.format(
            "%d:%02d %s",
            now.getHour() > 12 ? now.getHour() - 12 : now.getHour(),
            now.getMinute(),
            now.getHour() >= 12 ? "PM" : "AM");
    final String report = buildPredictionReport(result, timeLabel);
    telegramService.sendMessage(report);
    logger.info(
        "Direction change prediction sent at {}: {} (was {})",
        timeLabel,
        current,
        initialPredictionDirection);
  }

  private String buildTradeRecommendation(
      final String direction,
      final List<BigDecimal> strikes,
      final BigDecimal confidence,
      final OiDataSnapshot latest,
      final BigDecimal vix,
      final BigDecimal indexOpen) {
    if (knownExpiryDates.isEmpty() || strikes.isEmpty()) {
      return "";
    }

    final DateTimeFormatter fmt = DateTimeFormatter.ofPattern("dd-MMM", Locale.ENGLISH);
    final String expiry = knownExpiryDates.getFirst().format(fmt);
    final int lotSz = lotSize();

    final BigDecimal targetAmt =
        DEPLOYED_CAPITAL
            .multiply(TARGET_PCT)
            .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);
    final BigDecimal slAmt =
        DEPLOYED_CAPITAL
            .multiply(STOP_LOSS_PCT)
            .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP);

    final StringBuilder rec = new StringBuilder();

    final String tradeLine =
        switch (direction) {
          case "BULLISH" -> {
            final BigDecimal sellStrike = strikes.getFirst();
            yield String.format("SELL %s PE (%s)", sellStrike, expiry);
          }
          case "BEARISH" -> {
            final BigDecimal sellStrike = strikes.getFirst();
            yield String.format("SELL %s CE (%s)", sellStrike, expiry);
          }
          case "NEUTRAL" -> {
            if (strikes.size() < 2) {
              yield "SELL PE & CE (" + expiry + ")";
            }
            yield String.format("SELL %s PE & %s CE (%s)", strikes.get(0), strikes.get(1), expiry);
          }
          default -> "";
        };
    rec.append(tradeLine);

    final BigDecimal hedgeStrike = findHedgeStrike(latest.strikePremiums(), direction, strikes);
    if (hedgeStrike != null) {
      final String hedgeType =
          switch (direction) {
            case "BULLISH" -> "PE";
            case "BEARISH" -> "CE";
            default -> "";
          };
      rec.append("\nBUY ")
          .append(hedgeStrike.setScale(0, RoundingMode.HALF_UP))
          .append(" ")
          .append(hedgeType)
          .append(" (")
          .append(expiry)
          .append(") [Hedge]");
      final BigDecimal spread = strikes.getFirst().subtract(hedgeStrike).abs();
      rec.append(" | Spread: ").append(spread).append(" pts");
    }

    rec.append("\nCapital: \u20B9")
        .append(DEPLOYED_CAPITAL)
        .append(" | Target: \u20B9")
        .append(targetAmt)
        .append(" (0.6%)")
        .append(" | SL: \u20B9")
        .append(slAmt)
        .append(" (1%)");

    final int lots = DEPLOYED_CAPITAL.divide(MARGIN_PER_LOT, 0, RoundingMode.DOWN).intValue();
    final int totalQty = lots * lotSz;
    final BigDecimal decayPointsNeeded =
        targetAmt.divide(BigDecimal.valueOf(totalQty), 1, RoundingMode.HALF_UP);

    rec.append("\nLots: ")
        .append(lots)
        .append(" (Qty: ")
        .append(totalQty)
        .append(") | Dynamic Position Sizing")
        .append("\nDecay Target: ")
        .append(decayPointsNeeded)
        .append(" pts decay needed to hit target")
        .append(" | SL: Exits at 2.0x net entry premium");

    rec.append("\nOI Reasoning: ");
    if (latest.largestPeOiStrike() != null
        && latest.largestPeOiStrike().compareTo(BigDecimal.ZERO) > 0) {
      rec.append("Max PE OI at ")
          .append(latest.largestPeOiStrike().setScale(0, RoundingMode.HALF_UP))
          .append(". ");
    }
    if (latest.largestCeOiStrike() != null
        && latest.largestCeOiStrike().compareTo(BigDecimal.ZERO) > 0) {
      rec.append("Max CE OI at ")
          .append(latest.largestCeOiStrike().setScale(0, RoundingMode.HALF_UP))
          .append(". ");
    }
    rec.append("PCR: ")
        .append(latest.pcr().setScale(2, RoundingMode.HALF_UP))
        .append(" | Confidence: ")
        .append(confidence)
        .append("%");

    if (vix != null && indexOpen != null) {
      final BigDecimal range =
          indexOpen
              .setScale(0, RoundingMode.HALF_UP)
              .multiply(vix)
              .divide(BigDecimal.valueOf(1600), 0, RoundingMode.HALF_UP);
      rec.append("\nDay range: \u00B1").append(range).append(" pts");
    }

    return rec.toString();
  }

  private String suggestStrategy(final String direction) {
    return switch (direction) {
      case "BULLISH" -> "DIRECTIONAL PUT SELLING";
      case "BEARISH" -> "DIRECTIONAL CALL SELLING";
      default -> "SHORT STRANGLE";
    };
  }

  private List<BigDecimal> pickStrikes(
      final String direction, final OiDataSnapshot latest, final BigDecimal confidence) {
    final List<BigDecimal> strikes = new ArrayList<>();
    final BigDecimal underlying = latest.underlyingValue();
    if (underlying == null || latest.strikePremiums() == null) {
      return strikes;
    }

    final BigDecimal vix = fetchVix();
    BigDecimal minPremium = BigDecimal.valueOf(30);
    BigDecimal maxPremium = BigDecimal.valueOf(40);
    if (vix != null) {
      if (vix.compareTo(BigDecimal.valueOf(12)) < 0) {
        minPremium = BigDecimal.valueOf(20);
        maxPremium = BigDecimal.valueOf(30);
      } else if (vix.compareTo(BigDecimal.valueOf(16)) > 0) {
        minPremium = BigDecimal.valueOf(40);
        maxPremium = BigDecimal.valueOf(55);
      }
    }

    switch (direction) {
      case "BULLISH" -> {
        final BigDecimal sellStrike =
            findStrikeByPremium(
                latest.strikePremiums(), "PE", minPremium, maxPremium, underlying, true);
        if (sellStrike != null) strikes.add(sellStrike);
      }
      case "BEARISH" -> {
        final BigDecimal sellStrike =
            findStrikeByPremium(
                latest.strikePremiums(), "CE", minPremium, maxPremium, underlying, false);
        if (sellStrike != null) strikes.add(sellStrike);
      }
      default -> {
        final BigDecimal putStrike =
            findStrikeByPremium(
                latest.strikePremiums(), "PE", minPremium, maxPremium, underlying, true);
        final BigDecimal callStrike =
            findStrikeByPremium(
                latest.strikePremiums(), "CE", minPremium, maxPremium, underlying, false);
        if (putStrike != null) strikes.add(putStrike);
        if (callStrike != null) strikes.add(callStrike);
      }
    }

    return strikes;
  }

  private BigDecimal findStrikeByPremium(
      final List<OiDataSnapshot.StrikePremium> premiums,
      final String type,
      final BigDecimal minPremium,
      final BigDecimal maxPremium,
      final BigDecimal underlying,
      final boolean otmBelow) {
    BigDecimal best = null;
    BigDecimal bestDiff = null;
    for (final OiDataSnapshot.StrikePremium sp : premiums) {
      final BigDecimal premium = "PE".equals(type) ? sp.pePremium() : sp.cePremium();
      if (premium == null || premium.compareTo(BigDecimal.ZERO) <= 0) continue;
      if (premium.compareTo(minPremium) < 0 || premium.compareTo(maxPremium) > 0) continue;
      final boolean isOtm =
          otmBelow
              ? sp.strikePrice().compareTo(underlying) < 0
              : sp.strikePrice().compareTo(underlying) > 0;
      if (!isOtm) continue;
      final BigDecimal mid =
          minPremium.add(maxPremium).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
      final BigDecimal diff = premium.subtract(mid).abs();
      if (best == null || diff.compareTo(bestDiff) < 0) {
        best = sp.strikePrice();
        bestDiff = diff;
      }
    }
    return best;
  }

  private BigDecimal findHedgeStrike(
      final List<OiDataSnapshot.StrikePremium> premiums,
      final String direction,
      final List<BigDecimal> sellStrikes) {
    if (premiums == null || sellStrikes == null || sellStrikes.isEmpty()) return null;
    final String type =
        "BULLISH".equals(direction) ? "PE" : "BEARISH".equals(direction) ? "CE" : null;
    if (type == null) return null;
    final BigDecimal sellStrike = sellStrikes.getFirst();
    BigDecimal best = null;
    BigDecimal bestDiff = null;
    for (final OiDataSnapshot.StrikePremium sp : premiums) {
      final BigDecimal premium = "PE".equals(type) ? sp.pePremium() : sp.cePremium();
      if (premium == null || premium.compareTo(BigDecimal.ZERO) <= 0) continue;
      final boolean isFurtherOtm =
          "PE".equals(type)
              ? sp.strikePrice().compareTo(sellStrike) < 0
              : sp.strikePrice().compareTo(sellStrike) > 0;
      if (!isFurtherOtm) continue;
      final BigDecimal diff = premium.subtract(BigDecimal.TEN).abs();
      if (best == null || diff.compareTo(bestDiff) < 0) {
        best = sp.strikePrice();
        bestDiff = diff;
      }
    }
    return best;
  }

  private BigDecimal findMaxOiSupport(final OiDataSnapshot snapshot) {
    if (snapshot.topOiBuildUp() == null) {
      return null;
    }
    return snapshot.topOiBuildUp().stream()
        .filter(s -> "PE".equals(s.optionType()))
        .max(Comparator.comparing(OiStrikeInfo::openInterest))
        .map(OiStrikeInfo::strikePrice)
        .orElse(null);
  }

  private BigDecimal findMaxOiResistance(final OiDataSnapshot snapshot) {
    if (snapshot.topOiBuildUp() == null) {
      return null;
    }
    return snapshot.topOiBuildUp().stream()
        .filter(s -> "CE".equals(s.optionType()))
        .max(Comparator.comparing(OiStrikeInfo::openInterest))
        .map(OiStrikeInfo::strikePrice)
        .orElse(null);
  }

  private String computeDirection(final OiDataSnapshot snapshot) {
    final BigDecimal peChange = snapshot.totalPeOiChange();
    final BigDecimal ceChange = snapshot.totalCeOiChange();
    final BigDecimal total = peChange.abs().add(ceChange.abs());
    if (total.compareTo(BigDecimal.ZERO) == 0) {
      return "NEUTRAL";
    }
    final BigDecimal pePct =
        peChange.abs().multiply(BigDecimal.valueOf(100)).divide(total, 2, RoundingMode.HALF_UP);
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

  private static BigDecimal safePremium(final BigDecimal value) {
    return value != null && value.compareTo(BigDecimal.ZERO) > 0 ? value : BigDecimal.ZERO;
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

  private BigDecimal fetchVix() {
    final IndexQuote quote = optionChainClient.fetchIndexQuote("VIX");
    if (quote != null && quote.data() != null && !quote.data().isEmpty()) {
      return quote.data().getFirst().lastPrice();
    }
    return null;
  }

  private BigDecimal fetchIndexOpen() {
    final IndexQuote quote = optionChainClient.fetchIndexQuote(currentIndex);
    if (quote != null && quote.data() != null && !quote.data().isEmpty()) {
      return quote.data().getFirst().open();
    }
    return null;
  }

  private BigDecimal roundToNearestStrike(final BigDecimal price, final int interval) {
    final BigDecimal divided = price.divide(BigDecimal.valueOf(interval), 0, RoundingMode.HALF_UP);
    return divided.multiply(BigDecimal.valueOf(interval));
  }

  private record CandleData(BigDecimal high, BigDecimal low) {}

  public enum ExitSignal {
    NONE,
    PCR_SHIFT,
    DIRECTION_REVERSAL,
    SUPERTREND_REVERSAL,
    OI_SURGE,
    HARD_STOP,
    TRAILING_STOP,
    STRIKE_BREACH,
    PROFIT_TARGET
  }

  public record ExitAssessment(ExitSignal signal, BigDecimal confidence, BigDecimal exitFraction) {
    public static final ExitAssessment NONE =
        new ExitAssessment(ExitSignal.NONE, BigDecimal.ZERO, BigDecimal.ZERO);
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
        sumRange =
            sumRange.add(
                snapshots
                    .get(i + 1)
                    .underlyingValue()
                    .subtract(snapshots.get(i).underlyingValue())
                    .abs());
      }
      atr = sumRange.divide(BigDecimal.valueOf(SUPERTREND_PERIOD - 1), 4, RoundingMode.HALF_UP);
    }

    BigDecimal sumClose = BigDecimal.ZERO;
    for (int i = snapStart; i < snapEnd; i++) {
      sumClose = sumClose.add(snapshots.get(i).underlyingValue());
    }
    final BigDecimal mid =
        sumClose.divide(BigDecimal.valueOf(SUPERTREND_PERIOD), 2, RoundingMode.HALF_UP);
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

    if (isHardStopTriggered(latest)) {
      final BigDecimal entryNet =
          "STRANGLE".equals(entryStrikeType)
              ? entrySoldPremium.add(entryHedgePremium)
              : entrySoldPremium.subtract(entryHedgePremium);
      final BigDecimal currentSold =
          "STRANGLE".equals(entryStrikeType)
              ? lookupPremium(latest, entrySoldStrike, "PE")
              : lookupPremium(latest, entrySoldStrike, entryStrikeType);
      final BigDecimal currentHedge =
          "STRANGLE".equals(entryStrikeType)
              ? lookupPremium(latest, entryHedgeStrike, "CE")
              : lookupPremium(latest, entryHedgeStrike, entryStrikeType);
      final BigDecimal currentNet =
          "STRANGLE".equals(entryStrikeType)
              ? currentSold.add(currentHedge)
              : currentSold.subtract(currentHedge);
      logger.warn(
          "HARD STOP triggered at premium (entry net={}, current net={}, direction={})",
          entryNet,
          currentNet,
          entryDirection);
      return new ExitAssessment(ExitSignal.HARD_STOP, BigDecimal.valueOf(95), BigDecimal.ONE);
    }

    if (isTrailingStopTriggered(currentPrice)) {
      logger.warn(
          "TRAILING STOP triggered at price={} (high={}, entry={})",
          currentPrice,
          highestPriceSinceEntry,
          entryPrice);
      return new ExitAssessment(ExitSignal.TRAILING_STOP, BigDecimal.valueOf(90), BigDecimal.ONE);
    }

    if (isLossCapExceeded(currentPrice)) {
      logger.warn(
          "LOSS CAP exceeded at price={} (entry={}, direction={})",
          currentPrice,
          entryPrice,
          entryDirection);
      return new ExitAssessment(ExitSignal.HARD_STOP, BigDecimal.valueOf(95), BigDecimal.ONE);
    }

    final ExitSignal breachSignal = checkStrikeBreach(currentPrice);
    if (breachSignal != ExitSignal.NONE) {
      logger.warn(
          "{} triggered at price={} (sold strike={}, direction={})",
          breachSignal,
          currentPrice,
          entrySoldStrike,
          entryDirection);
      return new ExitAssessment(breachSignal, BigDecimal.valueOf(90), BigDecimal.ONE);
    }

    final ExitSignal profitSignal = checkProfitTarget(latest);
    if (profitSignal != ExitSignal.NONE) {
      final BigDecimal entryNet =
          "STRANGLE".equals(entryStrikeType)
              ? entrySoldPremium.add(entryHedgePremium)
              : entrySoldPremium.subtract(entryHedgePremium);
      final BigDecimal currentSold =
          "STRANGLE".equals(entryStrikeType)
              ? lookupPremium(latest, entrySoldStrike, "PE")
              : lookupPremium(latest, entrySoldStrike, entryStrikeType);
      final BigDecimal currentHedge =
          "STRANGLE".equals(entryStrikeType)
              ? lookupPremium(latest, entryHedgeStrike, "CE")
              : lookupPremium(latest, entryHedgeStrike, entryStrikeType);
      final BigDecimal currentNet =
          "STRANGLE".equals(entryStrikeType)
              ? currentSold.add(currentHedge)
              : currentSold.subtract(currentHedge);
      logger.info(
          "{} triggered at premium (entry net={}, current net={}, direction={})",
          profitSignal,
          entryNet,
          currentNet,
          entryDirection);
      return new ExitAssessment(profitSignal, BigDecimal.valueOf(80), BigDecimal.ONE);
    }

    if (isInCooldown()) {
      return ExitAssessment.NONE;
    }

    final BigDecimal timeMultiplier =
        isPastAfternoonThreshold() ? AFTERNOON_THRESHOLD_MULTIPLIER : BigDecimal.ONE;
    final BigDecimal dynamicThreshold =
        computeDynamicPcrThreshold()
            .multiply(getTimeDecayFactor())
            .multiply(timeMultiplier)
            .max(EXIT_PCR_SHIFT);
    final BigDecimal pcrShift = latest.pcr().subtract(entry.pcr()).abs();
    final boolean pcrShiftTriggered = pcrShift.compareTo(dynamicThreshold) > 0;

    final BigDecimal totalOiChange =
        latest.totalPeOiChange().abs().add(latest.totalCeOiChange().abs());
    final boolean oiSurgeTriggered = totalOiChange.compareTo(EXIT_OI_SURGE) > 0;

    final String currentDir = computeRollingDirection();
    final boolean directionReversalTriggered =
        !entryDirection.equals(currentDir) && !"NEUTRAL".equals(currentDir);

    final boolean superTrendReversal = checkSuperTrendReversal();

    final boolean isStrongSignal =
        (pcrShiftTriggered && directionReversalTriggered)
            || (pcrShiftTriggered && superTrendReversal)
            || pcrShift.compareTo(dynamicThreshold.multiply(BigDecimal.valueOf(2))) > 0;

    final ExitSignal primarySignal;
    int requiredConfirmations =
        isStrongSignal ? STRONG_SIGNAL_CONFIRMATION_REQUIRED : CONFIRMATION_CONSECUTIVE;
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
      logger.info(
          "Exit signal detected (pending confirmation): {} (streak {}/{})",
          primarySignal,
          confirmationStreak,
          requiredConfirmations);
      return ExitAssessment.NONE;
    }

    if (confirmationStreak < requiredConfirmations) {
      logger.info(
          "Exit signal pending confirmation: {} (streak {}/{})",
          primarySignal,
          confirmationStreak,
          requiredConfirmations);
      return ExitAssessment.NONE;
    }

    final BigDecimal confidence =
        computeConfidence(
            primarySignal,
            pcrShift,
            dynamicThreshold,
            directionReversalTriggered,
            superTrendReversal,
            oiSurgeTriggered,
            currentPrice,
            entry);
    final BigDecimal exitFraction = computeExitFraction(primarySignal, pcrShift, dynamicThreshold);

    logger.warn(
        "Exit signal confirmed: {} (confidence: {}%, exit fraction: {}%)",
        primarySignal, confidence, exitFraction.multiply(BigDecimal.valueOf(100)));

    return new ExitAssessment(primarySignal, confidence, exitFraction);
  }

  private BigDecimal lookupPremium(
      final OiDataSnapshot snapshot, final BigDecimal strike, final String type) {
    if (snapshot == null
        || snapshot.strikePremiums() == null
        || strike == null
        || strike.compareTo(BigDecimal.ZERO) == 0) {
      return BigDecimal.ZERO;
    }
    for (final var sp : snapshot.strikePremiums()) {
      if (sp.strikePrice().compareTo(strike) == 0) {
        return "PE".equals(type) ? safePremium(sp.pePremium()) : safePremium(sp.cePremium());
      }
    }
    return BigDecimal.ZERO;
  }

  private boolean isHardStopTriggered(final OiDataSnapshot latest) {
    if (!positionEntered || entrySoldPremium.compareTo(BigDecimal.ZERO) == 0) {
      return false;
    }

    BigDecimal currentNet;
    BigDecimal entryNet;

    if ("STRANGLE".equals(entryStrikeType)) {
      final BigDecimal currentSold = lookupPremium(latest, entrySoldStrike, "PE");
      final BigDecimal currentHedge = lookupPremium(latest, entryHedgeStrike, "CE");
      currentNet = currentSold.add(currentHedge);
      entryNet = entrySoldPremium.add(entryHedgePremium);
    } else {
      final BigDecimal currentSold = lookupPremium(latest, entrySoldStrike, entryStrikeType);
      final BigDecimal currentHedge = lookupPremium(latest, entryHedgeStrike, entryStrikeType);
      currentNet = currentSold.subtract(currentHedge);
      entryNet = entrySoldPremium.subtract(entryHedgePremium);
    }

    if (entryNet.compareTo(BigDecimal.ZERO) <= 0) {
      return false;
    }

    // Stop loss if current net premium has doubled (2.0x)
    return currentNet.compareTo(entryNet.multiply(BigDecimal.valueOf(2.0))) >= 0;
  }

  private boolean isTrailingStopTriggered(final BigDecimal currentPrice) {
    if (entryPrice == null
        || entryPrice.compareTo(BigDecimal.ZERO) == 0
        || highestPriceSinceEntry.compareTo(BigDecimal.ZERO) == 0) {
      return false;
    }
    if ("BULLISH".equals(entryDirection)) {
      final BigDecimal pullback =
          highestPriceSinceEntry
              .subtract(currentPrice)
              .multiply(BigDecimal.valueOf(100))
              .divide(highestPriceSinceEntry, 2, RoundingMode.HALF_UP);
      return pullback.compareTo(TRAILING_STOP_PCT) >= 0 && currentPrice.compareTo(entryPrice) > 0;
    }
    if ("BEARISH".equals(entryDirection)) {
      final BigDecimal pullback =
          currentPrice
              .subtract(lowestPriceSinceEntry)
              .multiply(BigDecimal.valueOf(100))
              .divide(lowestPriceSinceEntry, 2, RoundingMode.HALF_UP);
      return pullback.compareTo(TRAILING_STOP_PCT) >= 0 && currentPrice.compareTo(entryPrice) < 0;
    }
    return false;
  }

  private boolean isLossCapExceeded(final BigDecimal currentPrice) {
    return false; // Disabled as it is redundant under option-premium stops
  }

  private BigDecimal estimateUnrealizedLossPct(final BigDecimal currentPrice) {
    if (entryPrice == null || entryPrice.compareTo(BigDecimal.ZERO) == 0) {
      return BigDecimal.ZERO;
    }
    if ("BULLISH".equals(entryDirection)) {
      if (currentPrice.compareTo(entryPrice) >= 0) return BigDecimal.ZERO;
      return entryPrice
          .subtract(currentPrice)
          .multiply(BigDecimal.valueOf(100))
          .divide(entryPrice, 2, RoundingMode.HALF_UP);
    }
    if ("BEARISH".equals(entryDirection)) {
      if (currentPrice.compareTo(entryPrice) <= 0) return BigDecimal.ZERO;
      return currentPrice
          .subtract(entryPrice)
          .multiply(BigDecimal.valueOf(100))
          .divide(entryPrice, 2, RoundingMode.HALF_UP);
    }
    return BigDecimal.ZERO;
  }

  private ExitSignal checkStrikeBreach(final BigDecimal currentPrice) {
    if (entrySoldStrike == null
        || entrySoldStrike.compareTo(BigDecimal.ZERO) == 0
        || entryStrikeType.isEmpty()
        || "STRANGLE".equals(entryStrikeType)
        || currentPrice == null) {
      return ExitSignal.NONE;
    }
    if ("PE".equals(entryStrikeType)) {
      // Sold PUT expecting bullish move; breach if price drops below strike
      if (currentPrice.compareTo(entrySoldStrike) <= 0) {
        return ExitSignal.STRIKE_BREACH;
      }
    } else if ("CE".equals(entryStrikeType)) {
      // Sold CALL expecting bearish move; breach if price rises above strike
      if (currentPrice.compareTo(entrySoldStrike) >= 0) {
        return ExitSignal.STRIKE_BREACH;
      }
    }
    return ExitSignal.NONE;
  }

  private ExitSignal checkProfitTarget(final OiDataSnapshot latest) {
    if (!positionEntered || entrySoldPremium.compareTo(BigDecimal.ZERO) == 0) {
      return ExitSignal.NONE;
    }

    BigDecimal currentNet;
    BigDecimal entryNet;

    if ("STRANGLE".equals(entryStrikeType)) {
      final BigDecimal currentSold = lookupPremium(latest, entrySoldStrike, "PE");
      final BigDecimal currentHedge = lookupPremium(latest, entryHedgeStrike, "CE");
      currentNet = currentSold.add(currentHedge);
      entryNet = entrySoldPremium.add(entryHedgePremium);
    } else {
      final BigDecimal currentSold = lookupPremium(latest, entrySoldStrike, entryStrikeType);
      final BigDecimal currentHedge = lookupPremium(latest, entryHedgeStrike, entryStrikeType);
      currentNet = currentSold.subtract(currentHedge);
      entryNet = entrySoldPremium.subtract(entryHedgePremium);
    }

    if (entryNet.compareTo(BigDecimal.ZERO) <= 0) {
      return ExitSignal.NONE;
    }

    // Take profit if current net premium has decayed to 20% (0.2x) or less
    if (currentNet.compareTo(entryNet.multiply(BigDecimal.valueOf(0.2))) <= 0) {
      return ExitSignal.PROFIT_TARGET;
    }

    return ExitSignal.NONE;
  }

  private boolean isPastAfternoonThreshold() {
    return !LocalTime.now(IST).isBefore(AFTERNOON_THRESHOLD);
  }

  private void sendEarlyWarning(final ExitSignal signal, final BigDecimal currentPrice) {
    if (earlyWarningSent) {
      return;
    }
    final String message =
        switch (signal) {
          case PCR_SHIFT ->
              "\u26A0\uFE0F EARLY WARNING: PCR shift – sold option may be at risk. Monitor closely.";
          case DIRECTION_REVERSAL ->
              "\u26A0\uFE0F EARLY WARNING: Direction reversal – sold option OTM status threatened. Monitor closely.";
          case SUPERTREND_REVERSAL ->
              "\u26A0\uFE0F EARLY WARNING: SuperTrend reversal – consider partial exit. Monitor closely.";
          case OI_SURGE ->
              "\u26A0\uFE0F EARLY WARNING: OI surge – increased volatility expected. Monitor closely.";
          case STRIKE_BREACH ->
              "\u26A0\uFE0F EARLY WARNING: Underlying approaching sold strike. Consider exiting.";
          default -> "";
        };
    if (!message.isBlank()) {
      final BigDecimal lossPct = estimateUnrealizedLossPct(currentPrice);
      final String lossInfo =
          lossPct.compareTo(BigDecimal.ZERO) > 0 ? "\nEstimated loss: " + lossPct + "%" : "";
      telegramService.sendMessage(
          message + lossInfo + "\nCurrent " + indexLabel() + ": " + currentPrice);
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
      sumAbsChange =
          sumAbsChange.add(snapshots.get(i).pcr().subtract(snapshots.get(i - 1).pcr()).abs());
    }
    final BigDecimal avgAbsChange =
        sumAbsChange.divide(BigDecimal.valueOf(PCR_VOLATILITY_WINDOW), 4, RoundingMode.HALF_UP);
    return avgAbsChange.multiply(PCR_VOLATILITY_MULTIPLIER).max(BigDecimal.valueOf(0.1));
  }

  private BigDecimal getTimeDecayFactor() {
    if (knownExpiryDates.isEmpty()) {
      return BigDecimal.ONE;
    }
    final long daysToExpiry =
        ChronoUnit.DAYS.between(LocalDate.now(IST), knownExpiryDates.getFirst());
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
    final BigDecimal pePct =
        sumPeChange.abs().multiply(BigDecimal.valueOf(100)).divide(total, 2, RoundingMode.HALF_UP);
    if (pePct.compareTo(BigDecimal.valueOf(60)) > 0 && sumPeChange.compareTo(BigDecimal.ZERO) > 0) {
      return "BULLISH";
    }
    if (pePct.compareTo(BigDecimal.valueOf(40)) < 0 && sumCeChange.compareTo(BigDecimal.ZERO) > 0) {
      return "BEARISH";
    }
    return "NEUTRAL";
  }

  private boolean isPriceConfirmed(
      final BigDecimal currentPrice, final BigDecimal entryPriceValue) {
    if (entryPriceValue == null || entryPriceValue.compareTo(BigDecimal.ZERO) == 0) {
      return true;
    }
    final BigDecimal priceChangePct =
        currentPrice
            .subtract(entryPriceValue)
            .abs()
            .multiply(BigDecimal.valueOf(100))
            .divide(entryPriceValue, 2, RoundingMode.HALF_UP);
    return priceChangePct.compareTo(PRICE_CONFIRMATION_PCT) >= 0;
  }

  private boolean isInCooldown() {
    if (lastExitFiredAt == Instant.MIN) {
      return false;
    }
    return Duration.between(lastExitFiredAt, Instant.now()).toMinutes() < EXIT_COOLDOWN_MINUTES;
  }

  private BigDecimal computeConfidence(
      final ExitSignal signal,
      final BigDecimal pcrShift,
      final BigDecimal dynamicThreshold,
      final boolean directionReversal,
      final boolean superTrendReversal,
      final boolean oiSurge,
      final BigDecimal currentPrice,
      final OiDataSnapshot entry) {
    final BigDecimal baseConfidence =
        switch (signal) {
          case PCR_SHIFT -> {
            final BigDecimal ratio = pcrShift.divide(dynamicThreshold, 2, RoundingMode.HALF_UP);
            yield BigDecimal.valueOf(30)
                .add(ratio.multiply(BigDecimal.valueOf(40)).min(BigDecimal.valueOf(50)));
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

  private BigDecimal computeExitFraction(
      final ExitSignal signal, final BigDecimal pcrShift, final BigDecimal dynamicThreshold) {
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
