package com.kite.trading.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

public record OiAnalysisResult(
    @JsonProperty("direction") String direction,
    @JsonProperty("confidence") BigDecimal confidence,
    @JsonProperty("pcr") BigDecimal pcr,
    @JsonProperty("suggestedStrikes") List<BigDecimal> suggestedStrikes,
    @JsonProperty("reasoning") String reasoning,
    @JsonProperty("tradeRecommendation") String tradeRecommendation,
    @JsonProperty("vix") BigDecimal vix,
    @JsonProperty("indexOpen") BigDecimal indexOpen,
    @JsonProperty("largestPeOiStrike") BigDecimal largestPeOiStrike,
    @JsonProperty("largestCeOiStrike") BigDecimal largestCeOiStrike,
    @JsonProperty("marketSentiment") BigDecimal marketSentiment) {
  public OiAnalysisResult(
      String direction,
      BigDecimal confidence,
      BigDecimal pcr,
      List<BigDecimal> suggestedStrikes,
      String reasoning,
      String tradeRecommendation,
      BigDecimal vix,
      BigDecimal indexOpen,
      BigDecimal largestPeOiStrike,
      BigDecimal largestCeOiStrike) {
    this(
        direction,
        confidence,
        pcr,
        suggestedStrikes,
        reasoning,
        tradeRecommendation,
        vix,
        indexOpen,
        largestPeOiStrike,
        largestCeOiStrike,
        BigDecimal.ZERO);
  }

  public OiAnalysisResult {
    if (tradeRecommendation == null) {
      tradeRecommendation = "";
    }
    if (marketSentiment == null) {
      marketSentiment = BigDecimal.ZERO;
    }
  }
}
