package com.kite.trading.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record StockQuote(@JsonProperty("priceInfo") PriceInfo priceInfo) {

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record PriceInfo(
      @JsonProperty("open") BigDecimal open,
      @JsonProperty("high") BigDecimal high,
      @JsonProperty("low") BigDecimal low,
      @JsonProperty("lastPrice") BigDecimal lastPrice,
      @JsonProperty("previousClose") BigDecimal previousClose,
      @JsonProperty("totalTradedVolume") BigDecimal totalTradedVolume) {}
}
