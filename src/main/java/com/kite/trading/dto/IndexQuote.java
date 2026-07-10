package com.kite.trading.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record IndexQuote(@JsonProperty("data") List<IndexData> data) {
  @JsonIgnoreProperties(ignoreUnknown = true)
  public record IndexData(
      @JsonProperty("symbol") String symbol,
      @JsonProperty("open") BigDecimal open,
      @JsonProperty("high") BigDecimal high,
      @JsonProperty("low") BigDecimal low,
      @JsonProperty("lastPrice") BigDecimal lastPrice,
      @JsonProperty("previousClose") BigDecimal previousClose) {}
}
