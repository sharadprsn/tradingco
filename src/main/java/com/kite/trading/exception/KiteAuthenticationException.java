package com.kite.trading.exception;

/**
 * Custom exception for Zerodha API authentication errors.
 *
 * <p>This exception is thrown when authentication with the Zerodha API fails due to invalid
 * credentials, expired tokens, or other auth-related issues.
 *
 * @author Kite Trading Team
 * @version 1.0.0
 */
public class KiteAuthenticationException extends RuntimeException {

  /**
   * Creates a new KiteAuthenticationException with the specified message.
   *
   * @param message The error message describing the authentication failure
   */
  public KiteAuthenticationException(final String message) {
    super(message);
  }

  /**
   * Creates a new KiteAuthenticationException with message and cause.
   *
   * @param message The error message
   * @param cause The underlying cause of the exception
   */
  public KiteAuthenticationException(final String message, final Throwable cause) {
    super(message, cause);
  }
}
