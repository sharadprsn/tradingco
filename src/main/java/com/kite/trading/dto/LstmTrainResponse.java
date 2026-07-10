package com.kite.trading.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record LstmTrainResponse(
    @JsonProperty("status") String status,
    @JsonProperty("val_accuracy") Double valAccuracy,
    @JsonProperty("val_loss") Double valLoss,
    @JsonProperty("epochs") Integer epochs,
    @JsonProperty("samples") Integer samples,
    @JsonProperty("reason") String reason) {}
