package com.kite.trading.exception;

/**
 * Custom exception for Zerodha API communication errors.
 *
 * <p>This exception is thrown when there are issues communicating with the Zerodha API, such as
 * network errors, timeout, or API limit exceeded.
 *
 * @author Kite Trading Team
 * @version 1.0.0
 */
public class KiteApiException extends RuntimeException {

  /**
   * Creates a new KiteApiException with the specified message.
   *
   * @param message The error message describing the API error
   */
  public KiteApiException(final String message) {
    super(message);
  }

  /**
   * Creates a new KiteApiException with message and cause.
   *
   * @param message The error message
   * @param cause The underlying cause of the exception
   */
  public KiteApiException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
