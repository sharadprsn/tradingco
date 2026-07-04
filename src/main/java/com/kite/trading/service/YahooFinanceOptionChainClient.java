package com.kite.trading.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.kite.trading.dto.OptionChainData;
import com.kite.trading.dto.OptionChainData.OptionContract;
import com.kite.trading.dto.OptionChainData.OptionData;
import com.kite.trading.dto.OptionChainData.Records;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class YahooFinanceOptionChainClient implements OptionChainClient {

    private static final Logger logger = LoggerFactory.getLogger(YahooFinanceOptionChainClient.class);

    private static final String YAHOO_OPTION_URL =
            "https://query1.finance.yahoo.com/v7/finance/options/%5ENSEI";
    private static final DateTimeFormatter NSE_DATE_FMT =
            DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);

    private final WebClient webClient;

    private final Map<String, BigDecimal> previousOi = new ConcurrentHashMap<>();

    public YahooFinanceOptionChainClient(final WebClient webClient) {
        this.webClient = webClient;
    }

    @Override
    public OptionChainData fetchOptionChain() {
        try {
            final YahooResponse response = webClient.get()
                    .uri(YAHOO_OPTION_URL)
                    .header("User-Agent", "Mozilla/5.0")
                    .retrieve()
                    .bodyToMono(YahooResponse.class)
                    .block();

            if (response == null || response.optionChain() == null
                    || response.optionChain().result() == null
                    || response.optionChain().result().isEmpty()) {
                logger.warn("Yahoo Finance returned empty response");
                return null;
            }

            return convert(response.optionChain().result().getFirst());
        } catch (final Exception e) {
            logger.error("Failed to fetch option chain from Yahoo Finance", e);
            return null;
        }
    }

    private OptionChainData convert(final YahooResult result) {
        final BigDecimal underlying = BigDecimal.valueOf(result.quote().regularMarketPrice());
        final List<String> expiryDates = new ArrayList<>();
        final List<BigDecimal> strikes = new ArrayList<>();
        final List<OptionData> data = new ArrayList<>();
        final Map<BigDecimal, List<OptionData>> dataByExpiry = new HashMap<>();

        if (result.expirationDates() != null) {
            for (final long ts : result.expirationDates()) {
                expiryDates.add(formatExpiry(ts));
            }
        }

        if (result.strikes() != null) {
            for (final double s : result.strikes()) {
                strikes.add(BigDecimal.valueOf(s));
            }
        }

        if (result.options() != null) {
            for (final YahooOptionGroup group : result.options()) {
                final String expiryStr = formatExpiry(group.expirationDate());
                final Map<BigDecimal, OptionContract> putsByStrike = new HashMap<>();

                if (group.puts() != null) {
                    for (final YahooContract yc : group.puts()) {
                        final BigDecimal strike = BigDecimal.valueOf(yc.strike());
                        putsByStrike.put(strike, toContract(yc, strike, expiryStr, underlying));
                    }
                }

                if (group.calls() != null) {
                    for (final YahooContract yc : group.calls()) {
                        final BigDecimal strike = BigDecimal.valueOf(yc.strike());
                        final OptionContract pe = putsByStrike.get(strike);
                        data.add(new OptionData(strike, expiryStr, toContract(yc, strike, expiryStr, underlying), pe));
                    }
                }

                for (final Map.Entry<BigDecimal, OptionContract> entry : putsByStrike.entrySet()) {
                    final BigDecimal strike = entry.getKey();
                    final boolean hasCe = group.calls() != null
                            && group.calls().stream().anyMatch(c -> BigDecimal.valueOf(c.strike()).compareTo(strike) == 0);
                    if (!hasCe) {
                        data.add(new OptionData(strike, expiryStr, null, entry.getValue()));
                    }
                }
            }
        }

        return new OptionChainData(
                new Records(expiryDates, data, null, underlying, strikes),
                null);
    }

    private OptionContract toContract(final YahooContract yc, final BigDecimal strike,
                                       final String expiry, final BigDecimal underlying) {
        final BigDecimal oi = BigDecimal.valueOf(yc.openInterest());
        final BigDecimal change = computeOiChange(strike, expiry, yc.optionType(), oi);

        return new OptionContract(
                strike,
                expiry,
                "^NSEI",
                null,
                oi,
                change,
                change.compareTo(BigDecimal.ZERO) != 0 && oi.compareTo(BigDecimal.ZERO) > 0
                        ? change.multiply(BigDecimal.valueOf(100)).divide(oi.subtract(change), 2, RoundingMode.HALF_UP)
                        : BigDecimal.ZERO,
                BigDecimal.valueOf(yc.volume()),
                BigDecimal.valueOf(yc.impliedVolatility()),
                BigDecimal.valueOf(yc.lastPrice()),
                BigDecimal.valueOf(yc.change()),
                BigDecimal.valueOf(yc.changePercent()),
                null,
                null,
                BigDecimal.valueOf(yc.bidSize()),
                BigDecimal.valueOf(yc.bid()),
                BigDecimal.valueOf(yc.askSize()),
                BigDecimal.valueOf(yc.ask()),
                underlying);
    }

    private BigDecimal computeOiChange(final BigDecimal strike, final String expiry,
                                        final String optionType, final BigDecimal currentOi) {
        final String key = strike + "|" + expiry + "|" + optionType;
        final BigDecimal prev = previousOi.get(key);
        previousOi.put(key, currentOi);
        if (prev == null) {
            return BigDecimal.ZERO;
        }
        return currentOi.subtract(prev);
    }

    private static String formatExpiry(final long timestamp) {
        return Instant.ofEpochSecond(timestamp)
                .atZone(ZoneId.of("Asia/Kolkata"))
                .format(NSE_DATE_FMT);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record YahooResponse(
            @JsonProperty("optionChain") YahooOptionChain optionChain
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record YahooOptionChain(
            @JsonProperty("result") List<YahooResult> result
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record YahooResult(
            @JsonProperty("quote") YahooQuote quote,
            @JsonProperty("expirationDates") long[] expirationDates,
            @JsonProperty("strikes") double[] strikes,
            @JsonProperty("options") List<YahooOptionGroup> options
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record YahooQuote(
            @JsonProperty("regularMarketPrice") double regularMarketPrice
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record YahooOptionGroup(
            @JsonProperty("expirationDate") long expirationDate,
            @JsonProperty("calls") List<YahooContract> calls,
            @JsonProperty("puts") List<YahooContract> puts
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record YahooContract(
            @JsonProperty("strike") double strike,
            @JsonProperty("openInterest") long openInterest,
            @JsonProperty("volume") long volume,
            @JsonProperty("lastPrice") double lastPrice,
            @JsonProperty("bid") double bid,
            @JsonProperty("ask") double ask,
            @JsonProperty("impliedVolatility") double impliedVolatility,
            @JsonProperty("change") double change,
            @JsonProperty("percentChange") double changePercent,
            @JsonProperty("bidSize") long bidSize,
            @JsonProperty("askSize") long askSize
    ) {
        String optionType() {
            return ""; // determined by context (calls vs puts array)
        }
    }
}
