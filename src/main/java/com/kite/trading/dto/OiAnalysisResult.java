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
    @JsonProperty("tradeRecommendation") String tradeRecommendation) {
  public OiAnalysisResult {
    if (tradeRecommendation == null) {
      tradeRecommendation = "";
    }
  }
}
