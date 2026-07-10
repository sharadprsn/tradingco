package com.kite.trading.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

public record QuoteResponse(
    @JsonProperty("status") String status, @JsonProperty("data") QuoteData data) {
  public record QuoteData(@JsonProperty("last_price") BigDecimal lastPrice) {}
}
