package com.kite.trading.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDateTime;

public record SniperSignal(
    @JsonProperty("symbol") String symbol,
    @JsonProperty("index") String index,
    @JsonProperty("optionType") String optionType,
    @JsonProperty("strikePrice") BigDecimal strikePrice,
    @JsonProperty("direction") String direction,
    @JsonProperty("timestamp") LocalDateTime timestamp,
    @JsonProperty("entryPremium") BigDecimal entryPremium,
    @JsonProperty("spotPrice") BigDecimal spotPrice,
    @JsonProperty("triggeredLevel") String triggeredLevel,
    @JsonProperty("volumeConfirmed") boolean volumeConfirmed,
    @JsonProperty("emaAligned") boolean emaAligned,
    @JsonProperty("indiaVix") BigDecimal indiaVix,
    @JsonProperty("fiiNet") BigDecimal fiiNet,
    @JsonProperty("diiNet") BigDecimal diiNet,
    @JsonProperty("status") String status) {}
