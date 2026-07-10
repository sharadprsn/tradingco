package com.kite.trading.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record OrderRequest(
    @NotBlank @JsonProperty("tradingsymbol") String tradingSymbol,
    @NotBlank @JsonProperty("exchange") String exchange,
    @NotBlank @JsonProperty("transaction_type") String transactionType,
    @NotNull @JsonProperty("quantity") Integer quantity,
    @NotNull @JsonProperty("price") BigDecimal price,
    @NotBlank @JsonProperty("product") String product,
    @NotBlank @JsonProperty("order_type") String orderType,
    @JsonProperty("validity") String validity,
    @JsonProperty("stoploss") BigDecimal stoploss,
    @JsonProperty("squareoff") BigDecimal squareoff,
    @JsonProperty("trailing_stoploss") Integer trailingStoploss) {
  public OrderRequest {
    if (validity == null) validity = "DAY";
    if (product == null) product = "MIS";
  }
}
