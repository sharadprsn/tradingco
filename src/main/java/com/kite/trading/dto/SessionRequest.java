package com.kite.trading.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Data Transfer Object for exchanging a request token for an API session.
 * 
 * After the user logs in via the Kite Connect login URL in their browser,
 * they are redirected back with a {@code request_token}. This DTO carries
 * that token so the backend can exchange it for a full {@link KiteSession}.
 * 
 * @author Kite Trading Team
 * @version 1.0.0
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SessionRequest(
        @JsonProperty("request_token")
        String requestToken
) {
}
