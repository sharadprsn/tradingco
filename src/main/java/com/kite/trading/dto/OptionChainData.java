package com.kite.trading.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.util.List;

public record OptionChainData(
    @JsonProperty("records") Records records, @JsonProperty("filtered") Filtered filtered) {
  public record Records(
      @JsonProperty("expiryDates") List<String> expiryDates,
      @JsonProperty("data") List<OptionData> data,
      @JsonProperty("timestamp") String timestamp,
      @JsonProperty("underlyingValue") BigDecimal underlyingValue,
      @JsonProperty("strikePrices") List<BigDecimal> strikePrices) {}

  public record Filtered(
      @JsonProperty("data") List<OptionData> data,
      @JsonProperty("ce") OptionSummary ce,
      @JsonProperty("pe") OptionSummary pe,
      @JsonProperty("strikePrices") List<BigDecimal> strikePrices) {}

  public record OptionData(
      @JsonProperty("strikePrice") BigDecimal strikePrice,
      @JsonProperty("expiryDate") String expiryDate,
      @JsonProperty("CE") OptionContract ce,
      @JsonProperty("PE") OptionContract pe) {}

  public record OptionContract(
      @JsonProperty("strikePrice") BigDecimal strikePrice,
      @JsonProperty("expiryDate") String expiryDate,
      @JsonProperty("underlying") String underlying,
      @JsonProperty("identifier") String identifier,
      @JsonProperty("openInterest") BigDecimal openInterest,
      @JsonProperty("changeinOpenInterest") BigDecimal changeinOpenInterest,
      @JsonProperty("pchangeinOpenInterest") BigDecimal pchangeinOpenInterest,
      @JsonProperty("totalTradedVolume") BigDecimal totalTradedVolume,
      @JsonProperty("impliedVolatility") BigDecimal impliedVolatility,
      @JsonProperty("lastPrice") BigDecimal lastPrice,
      @JsonProperty("change") BigDecimal change,
      @JsonProperty("pChange") BigDecimal pChange,
      @JsonProperty("totalBuyQuantity") BigDecimal totalBuyQuantity,
      @JsonProperty("totalSellQuantity") BigDecimal totalSellQuantity,
      @JsonProperty("bidQty") BigDecimal bidQty,
      @JsonProperty("bidprice") BigDecimal bidprice,
      @JsonProperty("askQty") BigDecimal askQty,
      @JsonProperty("askPrice") BigDecimal askPrice,
      @JsonProperty("underlyingValue") BigDecimal underlyingValue) {}

  public record OptionSummary(
      @JsonProperty("strikePrice") BigDecimal strikePrice,
      @JsonProperty("openInterest") BigDecimal openInterest,
      @JsonProperty("changeinOpenInterest") BigDecimal changeinOpenInterest,
      @JsonProperty("pchangeinOpenInterest") BigDecimal pchangeinOpenInterest,
      @JsonProperty("totalTradedVolume") BigDecimal totalTradedVolume,
      @JsonProperty("impliedVolatility") BigDecimal impliedVolatility,
      @JsonProperty("lastPrice") BigDecimal lastPrice) {}
}
