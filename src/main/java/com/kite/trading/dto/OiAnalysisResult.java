package com.kite.trading.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

public record OiAnalysisResult(
    @JsonProperty("direction") String direction,
    @JsonProperty("confidence") BigDecimal confidence,
    @JsonProperty("pcr") BigDecimal pcr,
    @JsonProperty("suggestedStrategy") String suggestedStrategy,
    @JsonProperty("suggestedStrikes") List<BigDecimal> suggestedStrikes,
    @JsonProperty("reasoning") String reasoning,
    @JsonProperty("tradeRecommendation") String tradeRecommendation,
    @JsonProperty("vix") BigDecimal vix,
    @JsonProperty("indexOpen") BigDecimal indexOpen,
    @JsonProperty("largestPeOiStrike") BigDecimal largestPeOiStrike,
    @JsonProperty("largestCeOiStrike") BigDecimal largestCeOiStrike) {
  public OiAnalysisResult {
    if (tradeRecommendation == null) {
      tradeRecommendation = "";
    }
  }
}
