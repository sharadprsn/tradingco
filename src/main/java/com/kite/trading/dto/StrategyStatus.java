package com.kite.trading.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record StrategyStatus(
    @JsonProperty("active") boolean active,
    @JsonProperty("phase") String phase,
    @JsonProperty("levels") CandleLevels levels,
    @JsonProperty("position") PositionInfo position,
    @JsonProperty("atr") BigDecimal atr,
    @JsonProperty("lastUpdated") LocalDateTime lastUpdated,
    @JsonProperty("deployedCapital") BigDecimal deployedCapital,
    @JsonProperty("currentPnl") BigDecimal currentPnl
) {
    public record CandleLevels(
        @JsonProperty("candle2High") BigDecimal candle2High,
        @JsonProperty("candle2Low") BigDecimal candle2Low,
        @JsonProperty("candle3High") BigDecimal candle3High,
        @JsonProperty("candle3Low") BigDecimal candle3Low,
        @JsonProperty("dayHigh") BigDecimal dayHigh,
        @JsonProperty("dayLow") BigDecimal dayLow,
        @JsonProperty("upperBreakout") BigDecimal upperBreakout,
        @JsonProperty("lowerBreakout") BigDecimal lowerBreakout
    ) {}

    public record PositionInfo(
        @JsonProperty("side") String side,
        @JsonProperty("entryPrice") BigDecimal entryPrice,
        @JsonProperty("entryTime") LocalDateTime entryTime,
        @JsonProperty("strikePrice") BigDecimal strikePrice,
        @JsonProperty("initialSl") BigDecimal initialSl,
        @JsonProperty("currentSl") BigDecimal currentSl,
        @JsonProperty("trailingSlDistance") BigDecimal trailingSlDistance,
        @JsonProperty("quantity") Integer quantity,
        @JsonProperty("orderId") String orderId,
        @JsonProperty("pnl") BigDecimal pnl
    ) {}
}
