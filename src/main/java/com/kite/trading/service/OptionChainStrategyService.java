package com.kite.trading.service;

import com.kite.trading.config.KiteConfig;
import com.kite.trading.config.StrategyConfigProperties;
import com.kite.trading.dto.OptionChainData;
import com.kite.trading.dto.OptionChainData.OptionContract;
import com.kite.trading.dto.OptionChainData.OptionData;
import com.kite.trading.dto.OptionChainStrategyStatus;
import com.kite.trading.dto.OptionChainStrategyStatus.IvAnalysis;
import com.kite.trading.dto.OptionChainStrategyStatus.LegInfo;
import com.kite.trading.dto.OrderRequest;
import com.kite.trading.dto.OrderResponse;
import com.kite.trading.dto.QuoteResponse;
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
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class OptionChainStrategyService {

    private static final Logger logger = LoggerFactory.getLogger(OptionChainStrategyService.class);

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 15);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);
    private static final LocalTime ENTRY_WINDOW_START = LocalTime.of(9, 45);
    private static final LocalTime ENTRY_WINDOW_END = LocalTime.of(10, 15);
    private static final int NUM_OTM_STRIKES = 5;
    private static final long EVALUATION_INTERVAL_MINUTES = 6;
    private static final BigDecimal IV_HIGH_THRESHOLD = BigDecimal.valueOf(25);
    private static final BigDecimal IV_LOW_THRESHOLD = BigDecimal.valueOf(12);
    private static final BigDecimal SKEW_THRESHOLD = BigDecimal.valueOf(5);

    private final NseOptionChainClient nseClient;
    private final ZerodhaApiClient apiClient;
    private final KiteAuthService authService;
    private final KiteConfig kiteConfig;
    private final StrategyConfigProperties strategyConfig;

    private final AtomicReference<StrategyPhase> phase = new AtomicReference<>(StrategyPhase.IDLE);
    private final List<LegInfo> legs = new CopyOnWriteArrayList<>();

    private volatile boolean active;
    private volatile BigDecimal underlyingValue;
    private volatile BigDecimal atmStrike;
    private volatile LocalDateTime entryTime;
    private volatile LocalDateTime lastEvaluationTime;
    private volatile String strategyType;
    private volatile BigDecimal totalPremium;
    private volatile BigDecimal maxLoss;
    private volatile BigDecimal currentPnl;
    private volatile String ivInterpretation;

    private volatile BigDecimal lastPutIv;
    private volatile BigDecimal lastCallIv;
    private volatile BigDecimal lastPutSkew;
    private volatile BigDecimal lastCallSkew;
    private volatile String lastIvTimestamp;

    private volatile BigDecimal niftyCurrentPrice;

    private enum StrategyPhase {
        IDLE, ANALYZING, ENTERED, MONITORING, ADJUSTING, EXITED
    }

    public OptionChainStrategyService(final NseOptionChainClient nseClient,
                                      final ZerodhaApiClient apiClient,
                                      final KiteAuthService authService,
                                      final KiteConfig kiteConfig,
                                      final StrategyConfigProperties strategyConfig) {
        this.nseClient = nseClient;
        this.apiClient = apiClient;
        this.authService = authService;
        this.kiteConfig = kiteConfig;
        this.strategyConfig = strategyConfig;
    }

    public synchronized void start() {
        if (active) {
            logger.warn("Option chain strategy is already active");
            return;
        }
        logger.info("Starting option chain strategy");
        reset();
        this.active = true;
        this.phase.set(StrategyPhase.ANALYZING);
        fetchAndAnalyze();
    }

    public synchronized void stop() {
        if (!active) {
            logger.warn("Option chain strategy is not active");
            return;
        }
        logger.info("Stopping option chain strategy");
        this.active = false;
        this.phase.set(StrategyPhase.IDLE);
    }

    public synchronized void evaluate() {
        if (!active) {
            logger.warn("Strategy not active, skipping evaluation");
            return;
        }

        if (isOutsideMarketHours()) {
            logger.debug("Outside market hours, skipping evaluation");
            return;
        }

        if (!authService.isAuthenticated()) {
            logger.warn("Not authenticated, skipping evaluation");
            return;
        }

        try {
            updateNiftyPrice();
            final OptionChainData data = nseClient.fetchOptionChain();
            if (data == null || data.records() == null) {
                logger.warn("Failed to fetch option chain data for evaluation");
                return;
            }

            this.underlyingValue = data.records().underlyingValue();
            this.lastEvaluationTime = LocalDateTime.now();

            final StrategyPhase currentPhase = phase.get();

            if (currentPhase == StrategyPhase.ANALYZING) {
                analyzeIvAndEnter(data);
            } else if (currentPhase == StrategyPhase.ENTERED || currentPhase == StrategyPhase.MONITORING) {
                validatePosition(data);
            }

            recordIvSnapshot(data);

        } catch (final Exception e) {
            logger.error("Error during option chain strategy evaluation", e);
        }
    }

    public OptionChainStrategyStatus getStatus() {
        return new OptionChainStrategyStatus(
                active, phase.get().name(), underlyingValue,
                entryTime, lastEvaluationTime, strategyType,
                Collections.unmodifiableList(new ArrayList<>(legs)),
                totalPremium, maxLoss, currentPnl,
                new IvAnalysis(lastIvTimestamp, lastPutIv, lastCallIv,
                        lastPutSkew, lastCallSkew, ivInterpretation));
    }

    public boolean isActive() {
        return active;
    }

    private void reset() {
        legs.clear();
        underlyingValue = null;
        atmStrike = null;
        entryTime = null;
        lastEvaluationTime = null;
        strategyType = null;
        totalPremium = null;
        maxLoss = null;
        currentPnl = null;
        ivInterpretation = null;
        lastPutIv = null;
        lastCallIv = null;
        lastPutSkew = null;
        lastCallSkew = null;
        lastIvTimestamp = null;
        niftyCurrentPrice = null;
        phase.set(StrategyPhase.ANALYZING);
    }

    private void fetchAndAnalyze() {
        try {
            final OptionChainData data = nseClient.fetchOptionChain();
            if (data == null || data.records() == null) {
                logger.error("No option chain data available");
                stop();
                return;
            }

            this.underlyingValue = data.records().underlyingValue();
            this.lastEvaluationTime = LocalDateTime.now();
            analyzeIvAndEnter(data);

        } catch (final Exception e) {
            logger.error("Failed to fetch and analyze option chain", e);
            stop();
        }
    }

    private void analyzeIvAndEnter(final OptionChainData data) {
        final List<OptionData> allOptions = data.records().data();
        if (allOptions == null || allOptions.isEmpty()) {
            logger.warn("No option data available for analysis");
            return;
        }

        final BigDecimal underlying = data.records().underlyingValue();
        if (underlying == null) {
            logger.warn("Underlying value not available");
            return;
        }

        this.atmStrike = roundToNearestStrike(underlying);

        final List<OptionData> otmPuts = findOtmPuts(allOptions, atmStrike);
        final List<OptionData> otmCalls = findOtmCalls(allOptions, atmStrike);

        if (otmPuts.size() < NUM_OTM_STRIKES || otmCalls.size() < NUM_OTM_STRIKES) {
            logger.warn("Not enough OTM strikes available. Puts: {}, Calls: {}",
                    otmPuts.size(), otmCalls.size());
            return;
        }

        final BigDecimal putIv = averageIv(otmPuts, true);
        final BigDecimal callIv = averageIv(otmCalls, false);
        final BigDecimal putSkew = calculateSkew(otmPuts, true);
        final BigDecimal callSkew = calculateSkew(otmCalls, false);

        this.lastPutIv = putIv;
        this.lastCallIv = callIv;
        this.lastPutSkew = putSkew;
        this.lastCallSkew = callSkew;

        logger.info("IV Analysis - Put IV: {}, Call IV: {}, Put Skew: {}, Call Skew: {}",
                putIv, callIv, putSkew, callSkew);

        final boolean isEntryWindow = isWithinEntryWindow();
        final String interpretation = interpretIv(putIv, callIv, putSkew, callSkew);
        this.ivInterpretation = interpretation;

        if (!isEntryWindow && phase.get() == StrategyPhase.ANALYZING) {
            logger.info("Outside entry window ({} - {}), analyzing only",
                    ENTRY_WINDOW_START, ENTRY_WINDOW_END);
            return;
        }

        if (!isEntryWindow && phase.get() == StrategyPhase.ANALYZING) {
            return;
        }

        if (phase.get() == StrategyPhase.ANALYZING) {
            decideAndEnter(otmPuts, otmCalls, putIv, callIv, putSkew, callSkew, interpretation);
        }
    }

    private void decideAndEnter(final List<OptionData> otmPuts, final List<OptionData> otmCalls,
                                final BigDecimal putIv, final BigDecimal callIv,
                                final BigDecimal putSkew, final BigDecimal callSkew,
                                final String interpretation) {
        if ("HIGH_IV".equals(interpretation) || "PUT_SKEW".equals(interpretation)) {
            logger.info("High IV / Put skew detected - entering PUT CREDIT SPREAD");
            executePutCreditSpread(otmPuts);
        } else if ("CALL_SKEW".equals(interpretation)) {
            logger.info("Call skew detected - entering CALL CREDIT SPREAD");
            executeCallCreditSpread(otmCalls);
        } else if ("BALANCED".equals(interpretation)) {
            logger.info("Balanced IV - entering IRON CONDOR");
            executeIronCondor(otmPuts, otmCalls);
        } else {
            logger.info("Low IV environment - entering IRON CONDOR");
            executeIronCondor(otmPuts, otmCalls);
        }
    }

    private void executePutCreditSpread(final List<OptionData> otmPuts) {
        if (otmPuts.size() < 2) {
            logger.warn("Need at least 2 OTM puts for credit spread");
            return;
        }

        final OptionData soldPut = otmPuts.get(0);
        final OptionData boughtPut = otmPuts.get(Math.min(NUM_OTM_STRIKES - 1, otmPuts.size() - 1));

        this.strategyType = "PUT_CREDIT_SPREAD";
        this.entryTime = LocalDateTime.now();

        final OptionContract soldContract = soldPut.pe();
        final OptionContract boughtContract = boughtPut.pe();

        final BigDecimal soldPremium = soldContract != null && soldContract.lastPrice() != null
                ? soldContract.lastPrice() : BigDecimal.ZERO;
        final BigDecimal boughtPremium = boughtContract != null && boughtContract.lastPrice() != null
                ? boughtContract.lastPrice() : BigDecimal.ZERO;

        final String soldSymbol = buildOptionSymbol(soldPut.strikePrice(), "PE");
        final String boughtSymbol = buildOptionSymbol(boughtPut.strikePrice(), "PE");

        final String soldOrderId = placeSpreadLeg(soldSymbol, "SELL", this.strategyConfig.getLotSize());
        final String boughtOrderId = placeSpreadLeg(boughtSymbol, "BUY", this.strategyConfig.getLotSize());

        final List<LegInfo> newLegs = new ArrayList<>();
        newLegs.add(new LegInfo(soldPut.strikePrice(), "PE", "SELL", soldPremium,
                soldContract != null ? soldContract.impliedVolatility() : null, soldOrderId));
        newLegs.add(new LegInfo(boughtPut.strikePrice(), "PE", "BUY", boughtPremium,
                boughtContract != null ? boughtContract.impliedVolatility() : null, boughtOrderId));
        this.legs.addAll(newLegs);

        final BigDecimal width = soldPut.strikePrice().subtract(boughtPut.strikePrice()).abs();
        this.totalPremium = soldPremium.subtract(boughtPremium)
                .multiply(BigDecimal.valueOf(this.strategyConfig.getLotSize()));
        this.maxLoss = width.multiply(BigDecimal.valueOf(this.strategyConfig.getLotSize()))
                .subtract(this.totalPremium.max(BigDecimal.ZERO));

        logger.info("PUT CREDIT SPREAD entered: Sell {} PE @ {}, Buy {} PE @ {}",
                soldPut.strikePrice(), soldPremium, boughtPut.strikePrice(), boughtPremium);

        this.phase.set(StrategyPhase.ENTERED);
    }

    private void executeCallCreditSpread(final List<OptionData> otmCalls) {
        if (otmCalls.size() < 2) {
            logger.warn("Need at least 2 OTM calls for credit spread");
            return;
        }

        final OptionData soldCall = otmCalls.get(0);
        final OptionData boughtCall = otmCalls.get(Math.min(NUM_OTM_STRIKES - 1, otmCalls.size() - 1));

        this.strategyType = "CALL_CREDIT_SPREAD";
        this.entryTime = LocalDateTime.now();

        final OptionContract soldContract = soldCall.ce();
        final OptionContract boughtContract = boughtCall.ce();

        final BigDecimal soldPremium = soldContract != null && soldContract.lastPrice() != null
                ? soldContract.lastPrice() : BigDecimal.ZERO;
        final BigDecimal boughtPremium = boughtContract != null && boughtContract.lastPrice() != null
                ? boughtContract.lastPrice() : BigDecimal.ZERO;

        final String soldSymbol = buildOptionSymbol(soldCall.strikePrice(), "CE");
        final String boughtSymbol = buildOptionSymbol(boughtCall.strikePrice(), "CE");

        final String soldOrderId = placeSpreadLeg(soldSymbol, "SELL", this.strategyConfig.getLotSize());
        final String boughtOrderId = placeSpreadLeg(boughtSymbol, "BUY", this.strategyConfig.getLotSize());

        final List<LegInfo> newLegs = new ArrayList<>();
        newLegs.add(new LegInfo(soldCall.strikePrice(), "CE", "SELL", soldPremium,
                soldContract != null ? soldContract.impliedVolatility() : null, soldOrderId));
        newLegs.add(new LegInfo(boughtCall.strikePrice(), "CE", "BUY", boughtPremium,
                boughtContract != null ? boughtContract.impliedVolatility() : null, boughtOrderId));
        this.legs.addAll(newLegs);

        final BigDecimal width = boughtCall.strikePrice().subtract(soldCall.strikePrice()).abs();
        this.totalPremium = soldPremium.subtract(boughtPremium)
                .multiply(BigDecimal.valueOf(this.strategyConfig.getLotSize()));
        this.maxLoss = width.multiply(BigDecimal.valueOf(this.strategyConfig.getLotSize()))
                .subtract(this.totalPremium.max(BigDecimal.ZERO));

        logger.info("CALL CREDIT SPREAD entered: Sell {} CE @ {}, Buy {} CE @ {}",
                soldCall.strikePrice(), soldPremium, boughtCall.strikePrice(), boughtPremium);

        this.phase.set(StrategyPhase.ENTERED);
    }

    private void executeIronCondor(final List<OptionData> otmPuts, final List<OptionData> otmCalls) {
        if (otmPuts.size() < 2 || otmCalls.size() < 2) {
            logger.warn("Need at least 2 OTM puts and 2 OTM calls for Iron Condor");
            return;
        }

        this.strategyType = "IRON_CONDOR";
        this.entryTime = LocalDateTime.now();

        final OptionData soldPut = otmPuts.get(0);
        final OptionData boughtPut = otmPuts.get(Math.min(NUM_OTM_STRIKES - 1, otmPuts.size() - 1));
        final OptionData soldCall = otmCalls.get(0);
        final OptionData boughtCall = otmCalls.get(Math.min(NUM_OTM_STRIKES - 1, otmCalls.size() - 1));

        final OptionContract soldPutContract = soldPut.pe();
        final OptionContract boughtPutContract = boughtPut.pe();
        final OptionContract soldCallContract = soldCall.ce();
        final OptionContract boughtCallContract = boughtCall.ce();

        final BigDecimal soldPutPremium = soldPutContract != null && soldPutContract.lastPrice() != null
                ? soldPutContract.lastPrice() : BigDecimal.ZERO;
        final BigDecimal boughtPutPremium = boughtPutContract != null && boughtPutContract.lastPrice() != null
                ? boughtPutContract.lastPrice() : BigDecimal.ZERO;
        final BigDecimal soldCallPremium = soldCallContract != null && soldCallContract.lastPrice() != null
                ? soldCallContract.lastPrice() : BigDecimal.ZERO;
        final BigDecimal boughtCallPremium = boughtCallContract != null && boughtCallContract.lastPrice() != null
                ? boughtCallContract.lastPrice() : BigDecimal.ZERO;

        final String soldPutSymbol = buildOptionSymbol(soldPut.strikePrice(), "PE");
        final String boughtPutSymbol = buildOptionSymbol(boughtPut.strikePrice(), "PE");
        final String soldCallSymbol = buildOptionSymbol(soldCall.strikePrice(), "CE");
        final String boughtCallSymbol = buildOptionSymbol(boughtCall.strikePrice(), "CE");

        final String soldPutOrderId = placeSpreadLeg(soldPutSymbol, "SELL", this.strategyConfig.getLotSize());
        final String boughtPutOrderId = placeSpreadLeg(boughtPutSymbol, "BUY", this.strategyConfig.getLotSize());
        final String soldCallOrderId = placeSpreadLeg(soldCallSymbol, "SELL", this.strategyConfig.getLotSize());
        final String boughtCallOrderId = placeSpreadLeg(boughtCallSymbol, "BUY", this.strategyConfig.getLotSize());

        this.legs.add(new LegInfo(soldPut.strikePrice(), "PE", "SELL", soldPutPremium,
                soldPutContract != null ? soldPutContract.impliedVolatility() : null, soldPutOrderId));
        this.legs.add(new LegInfo(boughtPut.strikePrice(), "PE", "BUY", boughtPutPremium,
                boughtPutContract != null ? boughtPutContract.impliedVolatility() : null, boughtPutOrderId));
        this.legs.add(new LegInfo(soldCall.strikePrice(), "CE", "SELL", soldCallPremium,
                soldCallContract != null ? soldCallContract.impliedVolatility() : null, soldCallOrderId));
        this.legs.add(new LegInfo(boughtCall.strikePrice(), "CE", "BUY", boughtCallPremium,
                boughtCallContract != null ? boughtCallContract.impliedVolatility() : null, boughtCallOrderId));

        final BigDecimal totalCredit = soldPutPremium.add(soldCallPremium)
                .subtract(boughtPutPremium.add(boughtCallPremium));
        this.totalPremium = totalCredit.multiply(BigDecimal.valueOf(this.strategyConfig.getLotSize()));

        final BigDecimal putWidth = soldPut.strikePrice().subtract(boughtPut.strikePrice()).abs();
        final BigDecimal callWidth = boughtCall.strikePrice().subtract(soldCall.strikePrice()).abs();
        final BigDecimal maxWidth = putWidth.max(callWidth);
        this.maxLoss = maxWidth.multiply(BigDecimal.valueOf(this.strategyConfig.getLotSize()))
                .subtract(this.totalPremium.max(BigDecimal.ZERO));

        logger.info("IRON CONDOR entered: Sell {} PE/{} CE, Buy {} PE/{} CE, Credit: {}",
                soldPut.strikePrice(), soldCall.strikePrice(),
                boughtPut.strikePrice(), boughtCall.strikePrice(), totalCredit);

        this.phase.set(StrategyPhase.ENTERED);
    }

    private void validatePosition(final OptionChainData data) {
        final StrategyPhase currentPhase = phase.get();
        if (currentPhase != StrategyPhase.ENTERED && currentPhase != StrategyPhase.MONITORING) {
            return;
        }

        phase.set(StrategyPhase.MONITORING);

        final List<OptionData> allOptions = data.records().data();
        if (allOptions == null || allOptions.isEmpty()) {
            return;
        }

        final BigDecimal currentUnderlying = data.records().underlyingValue();
        if (currentUnderlying == null) {
            return;
        }

        final List<OptionData> otmPuts = findOtmPuts(allOptions, roundToNearestStrike(currentUnderlying));
        final List<OptionData> otmCalls = findOtmCalls(allOptions, roundToNearestStrike(currentUnderlying));

        final BigDecimal currentPutIv = otmPuts.size() >= NUM_OTM_STRIKES
                ? averageIv(otmPuts, true) : null;
        final BigDecimal currentCallIv = otmCalls.size() >= NUM_OTM_STRIKES
                ? averageIv(otmCalls, false) : null;

        updatePnl(currentUnderlying);

        if (needsAdjustment(currentPutIv, currentCallIv, currentUnderlying)) {
            logger.warn("Position needs adjustment based on IV/price movement");
            this.phase.set(StrategyPhase.ADJUSTING);
            adjustPosition(otmPuts, otmCalls, currentPutIv, currentCallIv);
        }
    }

    private boolean needsAdjustment(final BigDecimal currentPutIv, final BigDecimal currentCallIv,
                                    final BigDecimal currentUnderlying) {
        if (currentUnderlying == null || atmStrike == null) {
            return false;
        }

        final BigDecimal priceMovePercent = currentUnderlying.subtract(atmStrike)
                .abs()
                .divide(atmStrike, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(100));

        if (priceMovePercent.compareTo(BigDecimal.valueOf(1.5)) > 0) {
            logger.info("Price moved {}/{}% from entry ATM - adjustment needed",
                    priceMovePercent, BigDecimal.valueOf(1.5));
            return true;
        }

        if (currentPutIv != null && currentCallIv != null) {
            final BigDecimal ivChange = currentPutIv.subtract(currentCallIv).abs();
            if (ivChange.compareTo(SKEW_THRESHOLD) > 0) {
                logger.info("IV skew changed by {} - adjustment may be needed", ivChange);
                return true;
            }
        }

        return false;
    }

    private void adjustPosition(final List<OptionData> otmPuts, final List<OptionData> otmCalls,
                                final BigDecimal currentPutIv, final BigDecimal currentCallIv) {
        logger.info("Adjusting position based on market conditions");

        final String newInterpretation = interpretIv(
                currentPutIv != null ? currentPutIv : BigDecimal.ZERO,
                currentCallIv != null ? currentCallIv : BigDecimal.ZERO,
                BigDecimal.ZERO, BigDecimal.ZERO);

        if ("PUT_SKEW".equals(newInterpretation) && "CALL_CREDIT_SPREAD".equals(strategyType)) {
            logger.info("Skew shifted to puts, rolling call spread to put spread");
            exitAllLegs();
            if (otmPuts.size() >= 2) {
                executePutCreditSpread(otmPuts);
            }
        } else if ("CALL_SKEW".equals(newInterpretation) && "PUT_CREDIT_SPREAD".equals(strategyType)) {
            logger.info("Skew shifted to calls, rolling put spread to call spread");
            exitAllLegs();
            if (otmCalls.size() >= 2) {
                executeCallCreditSpread(otmCalls);
            }
        } else {
            logger.info("Adjustment needed but not rolling - tightening strikes");
            exitAllLegs();
            if (otmPuts.size() >= 2 && otmCalls.size() >= 2) {
                executeIronCondor(otmPuts, otmCalls);
            }
        }

        this.phase.set(StrategyPhase.MONITORING);
    }

    private void exitAllLegs() {
        for (final LegInfo leg : legs) {
            if (leg.orderId() != null) {
                try {
                    final String accessToken = getAccessToken();
                    if (accessToken != null) {
                        final String oppositeAction = "SELL".equals(leg.action()) ? "BUY" : "SELL";
                        final String symbol = buildOptionSymbol(leg.strike(), leg.type());
                        final OrderRequest exitOrder = new OrderRequest(
                                symbol, "NFO",
                                oppositeAction, strategyConfig.getLotSize(), BigDecimal.ZERO,
                                "MIS", "MARKET", "DAY", null, null, null);
                        apiClient.placeOrder(accessToken, kiteConfig.getApiKey(), "regular", exitOrder);
                        logger.info("Exit order for leg {} {}", leg.type(), leg.strike());
                    }
                } catch (final Exception e) {
                    logger.error("Failed to exit leg {}", leg, e);
                }
            }
        }
        updatePnl(underlyingValue != null ? underlyingValue : BigDecimal.ZERO);
        legs.clear();
    }

    private void updatePnl(final BigDecimal currentUnderlying) {
        if (currentUnderlying == null || legs.isEmpty()) {
            return;
        }

        BigDecimal pnl = BigDecimal.ZERO;
        for (final LegInfo leg : legs) {
            final BigDecimal intrinsicValue = calculateIntrinsicValue(
                    currentUnderlying, leg.strike(), leg.type());
            if ("SELL".equals(leg.action())) {
                pnl = pnl.add(leg.premium().subtract(intrinsicValue));
            } else {
                pnl = pnl.subtract(leg.premium().subtract(intrinsicValue));
            }
        }
        this.currentPnl = pnl.multiply(BigDecimal.valueOf(strategyConfig.getLotSize()));
    }

    private BigDecimal calculateIntrinsicValue(final BigDecimal underlying,
                                                final BigDecimal strike, final String type) {
        if ("PE".equals(type)) {
            return strike.subtract(underlying).max(BigDecimal.ZERO);
        }
        return underlying.subtract(strike).max(BigDecimal.ZERO);
    }

    private void recordIvSnapshot(final OptionChainData data) {
        if (data == null || data.records() == null) {
            return;
        }

        final List<OptionData> allOptions = data.records().data();
        if (allOptions == null || allOptions.isEmpty()) {
            return;
        }

        this.lastIvTimestamp = data.records().timestamp();

        final BigDecimal underlying = data.records().underlyingValue();
        if (underlying == null) {
            return;
        }

        final BigDecimal atm = roundToNearestStrike(underlying);
        final List<OptionData> nearPuts = findOtmPuts(allOptions, atm);
        final List<OptionData> nearCalls = findOtmCalls(allOptions, atm);

        if (nearPuts.size() >= NUM_OTM_STRIKES) {
            this.lastPutIv = averageIv(nearPuts, true);
            this.lastPutSkew = calculateSkew(nearPuts, true);
        }
        if (nearCalls.size() >= NUM_OTM_STRIKES) {
            this.lastCallIv = averageIv(nearCalls, false);
            this.lastCallSkew = calculateSkew(nearCalls, false);
        }

        this.ivInterpretation = interpretIv(
                lastPutIv != null ? lastPutIv : BigDecimal.ZERO,
                lastCallIv != null ? lastCallIv : BigDecimal.ZERO,
                lastPutSkew != null ? lastPutSkew : BigDecimal.ZERO,
                lastCallSkew != null ? lastCallSkew : BigDecimal.ZERO);

        logger.debug("IV snapshot at {}: Put IV: {}, Call IV: {}, Interpretation: {}",
                lastIvTimestamp, lastPutIv, lastCallIv, ivInterpretation);
    }

    private List<OptionData> findOtmPuts(final List<OptionData> allOptions, final BigDecimal atm) {
        return allOptions.stream()
                .filter(o -> o.strikePrice() != null && o.strikePrice().compareTo(atm) < 0)
                .filter(o -> o.pe() != null && o.pe().impliedVolatility() != null)
                .sorted(Comparator.comparing(OptionData::strikePrice).reversed())
                .limit(NUM_OTM_STRIKES)
                .toList();
    }

    private List<OptionData> findOtmCalls(final List<OptionData> allOptions, final BigDecimal atm) {
        return allOptions.stream()
                .filter(o -> o.strikePrice() != null && o.strikePrice().compareTo(atm) > 0)
                .filter(o -> o.ce() != null && o.ce().impliedVolatility() != null)
                .sorted(Comparator.comparing(OptionData::strikePrice))
                .limit(NUM_OTM_STRIKES)
                .toList();
    }

    private BigDecimal averageIv(final List<OptionData> options, final boolean isPut) {
        return options.stream()
                .map(o -> isPut ? o.pe().impliedVolatility() : o.ce().impliedVolatility())
                .filter(v -> v != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(BigDecimal.valueOf(options.size()), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateSkew(final List<OptionData> options, final boolean isPut) {
        if (options.size() < 2) {
            return BigDecimal.ZERO;
        }

        final BigDecimal nearestIv = isPut
                ? options.get(0).pe().impliedVolatility()
                : options.get(0).ce().impliedVolatility();
        final BigDecimal farthestIv = isPut
                ? options.get(options.size() - 1).pe().impliedVolatility()
                : options.get(options.size() - 1).ce().impliedVolatility();

        if (nearestIv == null || farthestIv == null) {
            return BigDecimal.ZERO;
        }

        return farthestIv.subtract(nearestIv);
    }

    private String interpretIv(final BigDecimal putIv, final BigDecimal callIv,
                               final BigDecimal putSkew, final BigDecimal callSkew) {
        if (putIv.compareTo(IV_HIGH_THRESHOLD) > 0 || callIv.compareTo(IV_HIGH_THRESHOLD) > 0) {
            return "HIGH_IV";
        }
        if (putSkew.compareTo(SKEW_THRESHOLD) > 0) {
            return "PUT_SKEW";
        }
        if (callSkew.compareTo(SKEW_THRESHOLD) > 0) {
            return "CALL_SKEW";
        }
        if (putIv.compareTo(IV_LOW_THRESHOLD) < 0 && callIv.compareTo(IV_LOW_THRESHOLD) < 0) {
            return "LOW_IV";
        }
        return "BALANCED";
    }

    private void updateNiftyPrice() {
        try {
            final String accessToken = getAccessToken();
            if (accessToken == null) {
                return;
            }
            final QuoteResponse quote = apiClient.getQuote(
                    accessToken, kiteConfig.getApiKey(),
                    strategyConfig.getNiftyFuturesToken());
            if (quote != null && quote.data() != null) {
                this.niftyCurrentPrice = quote.data().lastPrice();
            }
        } catch (final Exception e) {
            logger.debug("Failed to update Nifty price", e);
        }
    }

    private String placeSpreadLeg(final String tradingSymbol, final String action,
                                   final int quantity) {
        try {
            final String accessToken = getAccessToken();
            if (accessToken == null) {
                logger.warn("No access token for order placement");
                return null;
            }

            final OrderRequest order = new OrderRequest(
                    tradingSymbol, "NFO",
                    action, quantity, BigDecimal.ZERO,
                    "MIS", "MARKET", "DAY", null, null, null);

            final OrderResponse response = apiClient.placeOrder(
                    accessToken, kiteConfig.getApiKey(), "regular", order);

            if (response != null && response.data() != null) {
                logger.info("Spread leg placed: {} {} - OrderId: {}", action, tradingSymbol,
                        response.data().orderId());
                return response.data().orderId();
            }
        } catch (final Exception e) {
            logger.error("Failed to place spread leg: {} {}", action, tradingSymbol, e);
        }
        return null;
    }

    private String getAccessToken() {
        return authService.getAccessToken();
    }

    private boolean isOutsideMarketHours() {
        final LocalTime now = LocalTime.now(IST);
        return now.isBefore(MARKET_OPEN) || now.isAfter(MARKET_CLOSE);
    }

    private boolean isWithinEntryWindow() {
        final LocalTime now = LocalTime.now(IST);
        return !now.isBefore(ENTRY_WINDOW_START) && !now.isAfter(ENTRY_WINDOW_END);
    }

    private BigDecimal roundToNearestStrike(final BigDecimal price) {
        if (price == null) {
            return BigDecimal.ZERO;
        }
        final int interval = strategyConfig.getStrikeInterval();
        final BigDecimal divided = price.divide(BigDecimal.valueOf(interval), 0, RoundingMode.HALF_UP);
        return divided.multiply(BigDecimal.valueOf(interval));
    }

    private String buildOptionSymbol(final BigDecimal strike, final String type) {
        final LocalDate now = LocalDate.now(IST);
        final int year = now.getYear() % 100;
        final String month = now.getMonth().name().substring(0, 3).toUpperCase();
        final String strikeStr = strike.setScale(0, RoundingMode.HALF_UP).toBigInteger().toString();
        return "NIFTY" + strikeStr + month + year + type;
    }
}
