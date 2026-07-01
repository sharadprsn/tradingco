package com.kite.trading.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * Data Transfer Object for the positions API response from Zerodha.
 * 
 * This DTO represents the response structure when fetching all positions
 * from the Zerodha Kite API.
 * 
 * @author Kite Trading Team
 * @version 1.0.0
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PositionsResponse(
        @JsonProperty("status")
        String status,
        
        @JsonProperty("data")
        PositionsData data
) {
    /**
     * Inner record containing positions data.
     *
     * @param net List of net positions
     * @param day List of day trading positions
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PositionsData(
            @JsonProperty("net")
            List<Position> net,
            
            @JsonProperty("day")
            List<Position> day
    ) {
    }
}
