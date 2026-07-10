package com.kite.trading.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDateTime;

/**
 * Data Transfer Object for error responses in the application.
 *
 * <p>This DTO provides a consistent error response format across all API endpoints, including error
 * details and timestamps.
 *
 * @author Kite Trading Team
 * @version 1.0.0
 */
public record ErrorResponse(
    @JsonProperty("error") String error,
    @JsonProperty("message") String message,
    @JsonProperty("timestamp") LocalDateTime timestamp) {
  /**
   * Factory method to create an ErrorResponse with current timestamp.
   *
   * @param error The error type or code
   * @param message The detailed error message
   * @return A new ErrorResponse instance
   */
  public static ErrorResponse of(final String error, final String message) {
    return new ErrorResponse(error, message, LocalDateTime.now());
  }
}
