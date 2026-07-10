package com.kite.trading.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Wrapper for the Kite Connect {@code /session/token} API response.
 *
 * <p>The session endpoint returns data inside a standard Kite API envelope:
 *
 * <pre>{@code
 * {
 *   "status": "success",
 *   "data": {
 *     "access_token": "...",
 *     "public_token": "...",
 *     "refresh_token": "...",
 *     "user_id": "...",
 *     "user_name": "...",
 *     "email": "...",
 *     "broker": "..."
 *   }
 * }
 * }</pre>
 *
 * @author Kite Trading Team
 * @version 1.0.0
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SessionResponse(
    @JsonProperty("status") String status, @JsonProperty("data") KiteSession data) {}
