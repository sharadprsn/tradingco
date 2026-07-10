package com.kite.trading.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Data Transfer Object representing a trading position in Zerodha.
 *
 * <p>This DTO encapsulates all relevant information about a trading position including instrument
 * details, quantity, average price, and P&L information.
 *
 * @author Kite Trading Team
 * @version 1.0.0
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record Position(
    @JsonProperty("tradingsymbol") String tradingSymbol,
    @JsonProperty("exchange") String exchange,
    @JsonProperty("instrument_token") Long instrumentToken,
    @JsonProperty("product") String product,
    @JsonProperty("quantity") Integer quantity,
    @JsonProperty("overnight_quantity") Integer overnightQuantity,
    @JsonProperty("average_price") Double averagePrice,
    @JsonProperty("last_price") Double lastPrice,
    @JsonProperty("close_price") Double closePrice,
    @JsonProperty("pnl") Double pnl,
    @JsonProperty("m2m") Double m2m,
    @JsonProperty("unrealised") Double unrealised,
    @JsonProperty("realised") Double realised,
    @JsonProperty("value") Double value,
    @JsonProperty("buy_quantity") Integer buyQuantity,
    @JsonProperty("buy_price") Double buyPrice,
    @JsonProperty("buy_value") Double buyValue,
    @JsonProperty("buy_m2m") Double buyM2m,
    @JsonProperty("sell_quantity") Integer sellQuantity,
    @JsonProperty("sell_price") Double sellPrice,
    @JsonProperty("sell_value") Double sellValue,
    @JsonProperty("sell_m2m") Double sellM2m,
    @JsonProperty("day_quantity") Integer dayQuantity,
    @JsonProperty("night_quantity") Integer nightQuantity) {}
