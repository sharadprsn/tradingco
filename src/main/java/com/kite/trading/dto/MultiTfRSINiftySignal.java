package com.kite.trading.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record MultiTfRSINiftySignal(
    @JsonProperty("symbol") String symbol,
    @JsonProperty("optionType") String optionType,
    @JsonProperty("strikePrice") BigDecimal strikePrice,
    @JsonProperty("direction") String direction,
    @JsonProperty("timestamp") LocalDateTime timestamp,
    @JsonProperty("entryPremium") BigDecimal entryPremium,
    @JsonProperty("niftySpotPrice") BigDecimal niftySpotPrice,
    @JsonProperty("candlePattern") String candlePattern,
    @JsonProperty("rsi5Aligned") boolean rsi5Aligned,
    @JsonProperty("rsi15Aligned") boolean rsi15Aligned,
    @JsonProperty("status") String status) {}
