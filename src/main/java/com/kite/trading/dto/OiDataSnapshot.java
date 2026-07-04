package com.kite.trading.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

public record OiDataSnapshot(
    @JsonProperty("timestamp") LocalDateTime timestamp,
    @JsonProperty("underlyingValue") BigDecimal underlyingValue,
    @JsonProperty("totalPeOi") BigDecimal totalPeOi,
    @JsonProperty("totalCeOi") BigDecimal totalCeOi,
    @JsonProperty("totalPeOiChange") BigDecimal totalPeOiChange,
    @JsonProperty("totalCeOiChange") BigDecimal totalCeOiChange,
    @JsonProperty("pcr") BigDecimal pcr,
    @JsonProperty("topOiBuildUp") List<OiStrikeInfo> topOiBuildUp
) {
    public record OiStrikeInfo(
        @JsonProperty("strikePrice") BigDecimal strikePrice,
        @JsonProperty("optionType") String optionType,
        @JsonProperty("openInterest") BigDecimal openInterest,
        @JsonProperty("changeInOi") BigDecimal changeInOi,
        @JsonProperty("pchangeInOi") BigDecimal pchangeInOi
    ) {}
}
