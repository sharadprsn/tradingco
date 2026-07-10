package com.kite.trading.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record LstmPredictionResponse(
    @JsonProperty("direction") String direction,
    @JsonProperty("confidence") double confidence,
    @JsonProperty("probabilities") List<Double> probabilities) {}
