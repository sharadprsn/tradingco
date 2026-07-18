package com.kite.trading.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record VandeBharatSignal(
    @JsonProperty("symbol") String symbol,
    @JsonProperty("direction") String direction,
    @JsonProperty("timestamp") LocalDateTime timestamp,
    @JsonProperty("entryPrice") BigDecimal entryPrice,
    @JsonProperty("stopLoss") BigDecimal stopLoss,
    @JsonProperty("pdh") BigDecimal pdh,
    @JsonProperty("pdl") BigDecimal pdl,
    @JsonProperty("currentPrice") BigDecimal currentPrice,
    @JsonProperty("status") String status) {}
