package com.kite.trading.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

public record LstmPredictionRequest(@JsonProperty("snapshots") List<LstmSnapshot> snapshots) {

  public record LstmSnapshot(
      @JsonProperty("underlyingValue") BigDecimal underlyingValue,
      @JsonProperty("totalPeOi") BigDecimal totalPeOi,
      @JsonProperty("totalCeOi") BigDecimal totalCeOi,
      @JsonProperty("totalPeOiChange") BigDecimal totalPeOiChange,
      @JsonProperty("totalCeOiChange") BigDecimal totalCeOiChange,
      @JsonProperty("pcr") BigDecimal pcr,
      @JsonProperty("largestPeOiStrike") BigDecimal largestPeOiStrike,
      @JsonProperty("largestCeOiStrike") BigDecimal largestCeOiStrike,
      @JsonProperty("vix") BigDecimal vix,
      @JsonProperty("indexOpen") BigDecimal indexOpen,
      @JsonProperty("indexHigh") BigDecimal indexHigh,
      @JsonProperty("indexLow") BigDecimal indexLow,
      @JsonProperty("marketSentiment") BigDecimal marketSentiment) {}
}
