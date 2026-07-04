package com.kite.trading.service;

import com.kite.trading.config.KiteConfig;
import com.kite.trading.config.StrategyConfigProperties;
import com.kite.trading.dto.Candle;
import com.kite.trading.dto.HistoricalDataResponse;
import com.kite.trading.dto.OrderRequest;
import com.kite.trading.dto.OrderResponse;
import com.kite.trading.dto.QuoteResponse;
import com.kite.trading.dto.StrategyStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

@Service
public class NiftyFuturesStrategyService {

    private static final Logger logger = LoggerFactory.getLogger(NiftyFuturesStrategyService.class);

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime MARKET_OPEN = LocalTime.of(9, 15);
    private static final LocalTime MARKET_CLOSE = LocalTime.of(15, 30);
    private static final int SECOND_CANDLE_INDEX = 1;
    private static final int THIRD_CANDLE_INDEX = 2;
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ISO_INSTANT;

    private final ZerodhaApiClient apiClient;
    private final KiteAuthService authService;
    private final KiteConfig kiteConfig;
    private final StrategyConfigProperties strategyConfig;

    private final AtomicReference<StrategyPhase> phase = new AtomicReference<>(StrategyPhase.IDLE);
    private final List<Candle> candles = Collections.synchronizedList(new ArrayList<>());

    private volatile boolean active;
    private volatile BigDecimal candle2High;
    private volatile BigDecimal candle2Low;
    private volatile BigDecimal candle3High;
    private volatile BigDecimal candle3Low;
    private volatile BigDecimal dayHigh;
    private volatile BigDecimal dayLow;
    private volatile BigDecimal upperBreakoutLevel;
    private volatile BigDecimal lowerBreakoutLevel;
    private volatile BigDecimal atr;
    private volatile BigDecimal currentPrice;

    private volatile String positionOrderId;
    private volatile PositionSide positionSide = PositionSide.NONE;
    private volatile BigDecimal entryPrice;
    private volatile LocalDateTime entryTime;
    private volatile BigDecimal initialSlPrice;
    private volatile BigDecimal currentSlPrice;
    private volatile BigDecimal trailingSlDistancePoints;
    private volatile BigDecimal optionStrikePrice;
    private volatile BigDecimal initialSlPoints;
    private volatile BigDecimal lastUpdatedAtr;
    private volatile BigDecimal currentPnl;

    public enum StrategyPhase {
        IDLE, COLLECTING, LEVELS_SET, PUT_SOLD, CALL_SOLD, EXITED
    }

    public enum PositionSide {
        NONE, PUT_SHORT, CALL_SHORT
    }

    public NiftyFuturesStrategyService(final ZerodhaApiClient apiClient,
                                       final KiteAuthService authService,
                                       final KiteConfig kiteConfig,
                                       final StrategyConfigProperties strategyConfig) {
        this.apiClient = apiClient;
        this.authService = authService;
        this.kiteConfig = kiteConfig;
        this.strategyConfig = strategyConfig;
    }

    public synchronized void start() {
        if (active) {
            logger.warn("Strategy is already active");
            return;
        }
        logger.info("Starting Nifty Futures Strategy");
        reset();
        this.active = true;
        this.phase.set(StrategyPhase.COLLECTING);
        fetchInitialData();
    }

    public synchronized void stop() {
        if (!active) {
            logger.warn("Strategy is not active");
            return;
        }
        logger.info("Stopping Nifty Futures Strategy");
        this.active = false;
        this.phase.set(StrategyPhase.IDLE);
    }

    public synchronized void evaluate() {
        if (!active) {
            return;
        }

        if (isOutsideMarketHours()) {
            logger.debug("Outside market hours, skipping evaluation");
            return;
        }

        if (!authService.isAuthenticated()) {
            logger.warn("Not authenticated, skipping strategy evaluation");
            return;
        }

        try {
            updateCurrentPrice();
            fetchLatestCandles();

            final StrategyPhase currentPhase = phase.get();

            if (currentPhase == StrategyPhase.COLLECTING) {
                if (hasEnoughCandles()) {
                    identifyCandleLevels();
                    calculateAtr();
                    phase.set(StrategyPhase.LEVELS_SET);
                    logger.info("Levels identified - Upper: {}, Lower: {}, ATR: {}",
                            upperBreakoutLevel, lowerBreakoutLevel, atr);
                }
            }

            if (currentPhase == StrategyPhase.LEVELS_SET || currentPhase == StrategyPhase.COLLECTING) {
                if (phase.get() == StrategyPhase.LEVELS_SET) {
                    checkBreakout();
                }
            }

            if (currentPhase == StrategyPhase.PUT_SOLD || currentPhase == StrategyPhase.CALL_SOLD) {
                updateDayHighLow();
                updateTrailingSl();
                checkStopLoss();
            }

        } catch (final Exception e) {
            logger.error("Error during strategy evaluation", e);
        }
    }

    public StrategyStatus getStatus() {
        final StrategyPhase currentPhase = phase.get();

        final StrategyStatus.CandleLevels levels = new StrategyStatus.CandleLevels(
                candle2High, candle2Low, candle3High, candle3Low,
                dayHigh, dayLow, upperBreakoutLevel, lowerBreakoutLevel);

        StrategyStatus.PositionInfo positionInfo = null;
        if (positionSide != PositionSide.NONE) {
            positionInfo = new StrategyStatus.PositionInfo(
                    positionSide.name(), entryPrice, entryTime,
                    optionStrikePrice, initialSlPrice, currentSlPrice,
                    trailingSlDistancePoints, strategyConfig.getLotSize(),
                    positionOrderId, currentPnl);
        }

        return new StrategyStatus(
                active, currentPhase.name(), levels, positionInfo,
                atr, LocalDateTime.now(), strategyConfig.getDeployedCapital(), currentPnl);
    }

    public StrategyPhase getPhase() {
        return phase.get();
    }

    public boolean isActive() {
        return active;
    }

    private void reset() {
        candles.clear();
        candle2High = null;
        candle2Low = null;
        candle3High = null;
        candle3Low = null;
        dayHigh = null;
        dayLow = null;
        upperBreakoutLevel = null;
        lowerBreakoutLevel = null;
        atr = null;
        currentPrice = null;
        positionOrderId = null;
        positionSide = PositionSide.NONE;
        entryPrice = null;
        entryTime = null;
        initialSlPrice = null;
        currentSlPrice = null;
        trailingSlDistancePoints = null;
        optionStrikePrice = null;
        initialSlPoints = null;
        lastUpdatedAtr = null;
        currentPnl = null;
        phase.set(StrategyPhase.COLLECTING);
    }

    private void fetchInitialData() {
        try {
            final String accessToken = getAccessToken();
            if (accessToken == null) {
                return;
            }

            final String instrumentToken = strategyConfig.getNiftyFuturesToken();
            final String to = LocalDateTime.now(IST).format(ISO_FORMATTER);
            final String from = LocalDate.now(IST).atTime(MARKET_OPEN).minusMinutes(30)
                    .toInstant(IST.getRules().getOffset(LocalDateTime.now(IST)))
                    .toString();

            final HistoricalDataResponse response = apiClient.getHistoricalData(
                    accessToken, kiteConfig.getApiKey(),
                    instrumentToken, strategyConfig.getInterval(),
                    from, to);

            if (response != null && response.data() != null && response.data().candles() != null) {
                final List<Candle> parsed = parseCandles(response.data().candles());
                candles.addAll(parsed);
                logger.info("Loaded {} candles for strategy", parsed.size());
            }

            updateCurrentPrice();

        } catch (final Exception e) {
            logger.error("Failed to fetch initial data", e);
        }
    }

    private void fetchLatestCandles() {
        try {
            final String accessToken = getAccessToken();
            if (accessToken == null) {
                return;
            }

            final String instrumentToken = strategyConfig.getNiftyFuturesToken();
            final String to = LocalDateTime.now(IST).format(ISO_FORMATTER);

            final Instant lookbackFrom = Instant.now()
                    .minus(Duration.ofMinutes(strategyConfig.getCandleLookback() * 15L + 60));
            final String from = lookbackFrom.toString();

            final HistoricalDataResponse response = apiClient.getHistoricalData(
                    accessToken, kiteConfig.getApiKey(),
                    instrumentToken, strategyConfig.getInterval(),
                    from, to);

            if (response != null && response.data() != null && response.data().candles() != null) {
                final List<Candle> parsed = parseCandles(response.data().candles());

                synchronized (candles) {
                    final Instant latestExisting = candles.isEmpty() ? Instant.MIN
                            : candles.get(candles.size() - 1).timestamp();

                    for (final Candle candle : parsed) {
                        if (candle.timestamp().isAfter(latestExisting)) {
                            candles.add(candle);
                        }
                    }

                    final Instant cutoff = Instant.now()
                            .minus(Duration.ofMinutes(strategyConfig.getCandleLookback() * 15L + 120));
                    candles.removeIf(c -> c.timestamp().isBefore(cutoff));
                }

                updateDayHighLow();
                calculateAtr();
            }

        } catch (final Exception e) {
            logger.error("Failed to fetch latest candles", e);
        }
    }

    private void updateCurrentPrice() {
        try {
            final String accessToken = getAccessToken();
            if (accessToken == null) {
                return;
            }

            final QuoteResponse quote = apiClient.getQuote(
                    accessToken, kiteConfig.getApiKey(),
                    strategyConfig.getNiftyFuturesToken());

            if (quote != null && quote.data() != null) {
                currentPrice = quote.data().lastPrice();
                updateDayHighLow();
            }

        } catch (final Exception e) {
            logger.debug("Failed to update current price (will retry)", e);
        }
    }

    private void identifyCandleLevels() {
        final List<Candle> todayCandles = getTodayCandles();

        if (todayCandles.size() < 3) {
            logger.warn("Not enough today's candles to identify levels (have {})", todayCandles.size());
            return;
        }

        final Candle secondCandle = todayCandles.get(SECOND_CANDLE_INDEX);
        final Candle thirdCandle = todayCandles.get(THIRD_CANDLE_INDEX);

        candle2High = secondCandle.high();
        candle2Low = secondCandle.low();
        candle3High = thirdCandle.high();
        candle3Low = thirdCandle.low();

        upperBreakoutLevel = candle2High.max(candle3High);
        lowerBreakoutLevel = candle2Low.min(candle3Low);

        updateDayHighLow();

        logger.info("Candle 2 - High: {}, Low: {}", candle2High, candle2Low);
        logger.info("Candle 3 - High: {}, Low: {}", candle3High, candle3Low);
        logger.info("Upper breakout: {}, Lower breakout: {}", upperBreakoutLevel, lowerBreakoutLevel);
        logger.info("Day High: {}, Day Low: {}", dayHigh, dayLow);
    }

    private void updateDayHighLow() {
        synchronized (candles) {
            for (final Candle candle : candles) {
                if (candle.high() != null) {
                    dayHigh = dayHigh == null ? candle.high() : dayHigh.max(candle.high());
                }
                if (candle.low() != null) {
                    dayLow = dayLow == null ? candle.low() : dayLow.min(candle.low());
                }
            }
        }

        if (currentPrice != null) {
            dayHigh = dayHigh == null ? currentPrice : dayHigh.max(currentPrice);
            dayLow = dayLow == null ? currentPrice : dayLow.min(currentPrice);
        }
    }

    private void calculateAtr() {
        final List<Candle> sortedCandles;
        synchronized (candles) {
            sortedCandles = new ArrayList<>(candles);
        }

        if (sortedCandles.size() < strategyConfig.getAtrPeriod() + 1) {
            return;
        }

        sortedCandles.sort(Comparator.comparing(Candle::timestamp));

        final int period = strategyConfig.getAtrPeriod();
        final List<BigDecimal> trueRanges = new ArrayList<>();

        for (int i = 1; i < sortedCandles.size(); i++) {
            final Candle current = sortedCandles.get(i);
            final Candle previous = sortedCandles.get(i - 1);

            final BigDecimal tr1 = current.high().subtract(current.low());
            final BigDecimal tr2 = current.high().subtract(previous.close()).abs();
            final BigDecimal tr3 = current.low().subtract(previous.close()).abs();

            final BigDecimal tr = tr1.max(tr2).max(tr3);
            trueRanges.add(tr);
        }

        if (trueRanges.size() < period) {
            return;
        }

        BigDecimal sum = BigDecimal.ZERO;
        for (int i = 0; i < period; i++) {
            sum = sum.add(trueRanges.get(i));
        }
        BigDecimal currentAtr = sum.divide(BigDecimal.valueOf(period), 2, RoundingMode.HALF_UP);

        for (int i = period; i < trueRanges.size(); i++) {
            currentAtr = currentAtr.multiply(BigDecimal.valueOf(period - 1))
                    .add(trueRanges.get(i))
                    .divide(BigDecimal.valueOf(period), 2, RoundingMode.HALF_UP);
        }

        this.atr = currentAtr;
        this.lastUpdatedAtr = currentAtr;
    }

    private void checkBreakout() {
        if (currentPrice == null || upperBreakoutLevel == null || lowerBreakoutLevel == null) {
            return;
        }

        if (positionSide != PositionSide.NONE) {
            return;
        }

        if (currentPrice.compareTo(upperBreakoutLevel) > 0) {
            logger.info("UPPER BREAKOUT detected! Price {} > Upper level {}",
                    currentPrice, upperBreakoutLevel);
            executeSellPut();
            return;
        }

        if (currentPrice.compareTo(lowerBreakoutLevel) < 0) {
            logger.info("LOWER BREAKOUT detected! Price {} < Lower level {}",
                    currentPrice, lowerBreakoutLevel);
            executeSellCall();
        }
    }

    private void executeSellPut() {
        if (dayLow == null) {
            logger.warn("Day low not available, cannot sell Put");
            return;
        }

        final BigDecimal strike = roundToNearestStrike(dayLow);
        this.optionStrikePrice = strike;
        this.positionSide = PositionSide.PUT_SHORT;
        this.entryPrice = currentPrice;
        this.entryTime = LocalDateTime.now();

        calculateInitialSl();

        logger.info("SELL PUT at strike {} (day low: {}), SL: {}, Trailing: {} * ATR",
                strike, dayLow, initialSlPrice, strategyConfig.getTrailingSlMultiplier());

        final String tradingSymbol = buildOptionSymbol(strike, "PE");
        placeSellOrder(tradingSymbol);
        this.phase.set(StrategyPhase.PUT_SOLD);
    }

    private void executeSellCall() {
        if (dayHigh == null) {
            logger.warn("Day high not available, cannot sell Call");
            return;
        }

        final BigDecimal strike = roundToNearestStrike(dayHigh);
        this.optionStrikePrice = strike;
        this.positionSide = PositionSide.CALL_SHORT;
        this.entryPrice = currentPrice;
        this.entryTime = LocalDateTime.now();

        calculateInitialSl();

        logger.info("SELL CALL at strike {} (day high: {}), SL: {}, Trailing: {} * ATR",
                strike, dayHigh, initialSlPrice, strategyConfig.getTrailingSlMultiplier());

        final String tradingSymbol = buildOptionSymbol(strike, "CE");
        placeSellOrder(tradingSymbol);
        this.phase.set(StrategyPhase.CALL_SOLD);
    }

    private void calculateInitialSl() {
        final BigDecimal slAmount = strategyConfig.getDeployedCapital()
                .multiply(strategyConfig.getInitialSlPercentage())
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        final BigDecimal lotSize = BigDecimal.valueOf(strategyConfig.getLotSize());
        this.initialSlPoints = slAmount.divide(lotSize, 2, RoundingMode.HALF_UP);

        final BigDecimal trailingDistance = atr != null
                ? atr.multiply(strategyConfig.getTrailingSlMultiplier())
                : initialSlPoints;
        this.trailingSlDistancePoints = trailingDistance;

        if (positionSide == PositionSide.PUT_SHORT) {
            this.initialSlPrice = entryPrice.subtract(initialSlPoints);
            this.currentSlPrice = entryPrice.subtract(trailingDistance);
        } else {
            this.initialSlPrice = entryPrice.add(initialSlPoints);
            this.currentSlPrice = entryPrice.add(trailingDistance);
        }

        logger.info("Initial SL calculation - Amount: {}, Points: {}, Trailing distance: {}",
                slAmount, initialSlPoints, trailingDistance);
    }

    private void updateTrailingSl() {
        if (currentPrice == null || atr == null || trailingSlDistancePoints == null) {
            return;
        }

        if (positionSide == PositionSide.PUT_SHORT) {
            final BigDecimal newSl = currentPrice.subtract(trailingSlDistancePoints);
            if (newSl.compareTo(currentSlPrice) > 0) {
                logger.info("Trailing SL UP: {} -> {}", currentSlPrice, newSl);
                currentSlPrice = newSl;
                updateSlOrder();
            }
        } else if (positionSide == PositionSide.CALL_SHORT) {
            final BigDecimal newSl = currentPrice.add(trailingSlDistancePoints);
            if (currentSlPrice != null && newSl.compareTo(currentSlPrice) < 0) {
                logger.info("Trailing SL DOWN: {} -> {}", currentSlPrice, newSl);
                currentSlPrice = newSl;
                updateSlOrder();
            }
        }
    }

    private void checkStopLoss() {
        if (currentPrice == null || currentSlPrice == null) {
            return;
        }

        boolean hitSl = false;

        if (positionSide == PositionSide.PUT_SHORT && currentPrice.compareTo(currentSlPrice) <= 0) {
            hitSl = true;
            logger.warn("STOP LOSS HIT (Put Short): Price {} <= SL {}", currentPrice, currentSlPrice);
        } else if (positionSide == PositionSide.CALL_SHORT && currentPrice.compareTo(currentSlPrice) >= 0) {
            hitSl = true;
            logger.warn("STOP LOSS HIT (Call Short): Price {} >= SL {}", currentPrice, currentSlPrice);
        }

        if (hitSl) {
            calculatePnl();
            exitPosition();
        }
    }

    private void exitPosition() {
        if (positionOrderId != null) {
            try {
                final String accessToken = getAccessToken();
                if (accessToken != null) {
                    final String tradingSymbol = buildOptionSymbol(optionStrikePrice,
                            positionSide == PositionSide.PUT_SHORT ? "PE" : "CE");
                    final OrderRequest exitOrder = new OrderRequest(
                            tradingSymbol, "NFO",
                            "BUY", strategyConfig.getLotSize(), BigDecimal.ZERO,
                            "MIS", "MARKET", "DAY", null, null, null);
                    apiClient.placeOrder(accessToken, kiteConfig.getApiKey(), "regular", exitOrder);
                    logger.info("Exit order placed for position: {}", positionOrderId);
                }
            } catch (final Exception e) {
                logger.error("Failed to place exit order", e);
            }
        }

        currentPnl = calculatePnl();
        logger.info("Position exited at {} with P&L: {}", currentPrice, currentPnl);
        this.phase.set(StrategyPhase.EXITED);
        this.positionSide = PositionSide.NONE;
        this.positionOrderId = null;
    }

    private BigDecimal calculatePnl() {
        if (entryPrice == null || currentPrice == null) {
            return BigDecimal.ZERO;
        }

        final BigDecimal pointsDiff;
        if (positionSide == PositionSide.PUT_SHORT) {
            pointsDiff = currentPrice.subtract(entryPrice);
        } else {
            pointsDiff = entryPrice.subtract(currentPrice);
        }

        return pointsDiff.multiply(BigDecimal.valueOf(strategyConfig.getLotSize()));
    }

    private String getAccessToken() {
        return authService.getAccessToken();
    }

    private List<Candle> getTodayCandles() {
        final LocalDate today = LocalDate.now(IST);
        final List<Candle> todayCandles = new ArrayList<>();

        synchronized (candles) {
            for (final Candle candle : candles) {
                final LocalDate candleDate = candle.timestamp()
                        .atZone(IST).toLocalDate();
                if (candleDate.equals(today)) {
                    todayCandles.add(candle);
                }
            }
        }

        todayCandles.sort(Comparator.comparing(Candle::timestamp));
        return todayCandles;
    }

    private boolean hasEnoughCandles() {
        return getTodayCandles().size() >= 3;
    }

    private boolean isOutsideMarketHours() {
        final LocalTime now = LocalTime.now(IST);
        return now.isBefore(MARKET_OPEN) || now.isAfter(MARKET_CLOSE);
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

    private void placeSellOrder(final String tradingSymbol) {
        try {
            final String accessToken = getAccessToken();
            if (accessToken == null) {
                logger.warn("No access token, unable to place order");
                return;
            }

            final OrderRequest order = new OrderRequest(
                    tradingSymbol, "NFO",
                    "SELL", strategyConfig.getLotSize(), BigDecimal.ZERO,
                    "MIS", "MARKET", "DAY", null, null, null);

            final OrderResponse response = apiClient.placeOrder(
                    accessToken, kiteConfig.getApiKey(), "regular", order);

            if (response != null && response.data() != null) {
                this.positionOrderId = response.data().orderId();
                logger.info("Order placed successfully: {}", positionOrderId);
            }

        } catch (final Exception e) {
            logger.error("Failed to place sell order for {}", tradingSymbol, e);
        }
    }

    private void updateSlOrder() {
        if (positionOrderId == null || optionStrikePrice == null) {
            return;
        }

        try {
            final String accessToken = getAccessToken();
            if (accessToken == null) {
                return;
            }

            final String type = positionSide == PositionSide.PUT_SHORT ? "PE" : "CE";
            final String tradingSymbol = buildOptionSymbol(optionStrikePrice, type);

            final OrderRequest modifyOrder = new OrderRequest(
                    tradingSymbol, "NFO",
                    "SELL", strategyConfig.getLotSize(), currentSlPrice,
                    "MIS", "SL", "DAY", null, null, null);

            apiClient.modifyOrder(accessToken, kiteConfig.getApiKey(),
                    "regular", positionOrderId, modifyOrder);

            logger.debug("SL order updated to {}", currentSlPrice);

        } catch (final Exception e) {
            logger.error("Failed to update SL order", e);
        }
    }

    private List<Candle> parseCandles(final List<List<Object>> rawCandles) {
        final List<Candle> result = new ArrayList<>();
        if (rawCandles == null) {
            return result;
        }

        for (final List<Object> raw : rawCandles) {
            if (raw.size() < 6) {
                continue;
            }
            try {
                final Instant timestamp = Instant.parse(raw.get(0).toString());
                final BigDecimal open = new BigDecimal(raw.get(1).toString());
                final BigDecimal high = new BigDecimal(raw.get(2).toString());
                final BigDecimal low = new BigDecimal(raw.get(3).toString());
                final BigDecimal close = new BigDecimal(raw.get(4).toString());
                final Long volume = Long.parseLong(raw.get(5).toString());
                result.add(new Candle(timestamp, open, high, low, close, volume));
            } catch (final Exception e) {
                logger.debug("Failed to parse candle: {}", raw, e);
            }
        }

        return result;
    }
}
