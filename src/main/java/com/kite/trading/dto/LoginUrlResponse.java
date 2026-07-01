package com.kite.trading.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Data Transfer Object that carries the Kite Connect login URL.
 * 
 * This DTO is returned by the {@code GET /api/v1/auth/login-url} endpoint.
 * The client should open this URL in a browser so the user can authenticate
 * with Zerodha and obtain a {@code request_token}.
 * 
 * @author Kite Trading Team
 * @version 1.0.0
 */
public record LoginUrlResponse(
        @JsonProperty("login_url")
        String loginUrl
) {
}
