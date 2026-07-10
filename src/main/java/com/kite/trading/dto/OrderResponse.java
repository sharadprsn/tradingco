package com.kite.trading.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record OrderResponse(
    @JsonProperty("status") String status, @JsonProperty("data") OrderData data) {
  public record OrderData(@JsonProperty("order_id") String orderId) {}
}
