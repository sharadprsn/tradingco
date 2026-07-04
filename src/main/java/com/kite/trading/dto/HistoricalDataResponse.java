package com.kite.trading.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

public record HistoricalDataResponse(
    @JsonProperty("status") String status,
    @JsonProperty("data") HistoricalData data
) {
    public record HistoricalData(
        @JsonProperty("candles") List<List<Object>> candles
    ) {}
}
