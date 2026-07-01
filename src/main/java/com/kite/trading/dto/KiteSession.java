package com.kite.trading.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Data Transfer Object representing an active Kite session.
 * 
 * This DTO holds the session tokens and user information required
 * for making authenticated API calls to Zerodha.
 * 
 * @author Kite Trading Team
 * @version 1.0.0
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record KiteSession(
        @JsonProperty("access_token")
        String accessToken,
        
        @JsonProperty("public_token")
        String publicToken,
        
        @JsonProperty("refresh_token")
        String refreshToken,
        
        @JsonProperty("user_id")
        String userId,
        
        @JsonProperty("user_name")
        String userName,
        
        @JsonProperty("email")
        String email,
        
        @JsonProperty("broker")
        String broker
) {
}
