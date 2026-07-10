package com.kite.trading.service;

import com.kite.trading.dto.PositionsResponse;
import com.kite.trading.dto.SessionResponse;

/**
 * Client interface for making raw HTTP calls to the Zerodha Kite REST API.
 *
 * <p>This interface abstracts the transport layer so that higher-level services depend on an
 * abstraction rather than a concrete HTTP implementation, following the Dependency Inversion
 * Principle (DIP).
 *
 * @author Kite Trading Team
 * @version 1.0.0
 */
public interface ZerodhaApiClient {

  /**
   * Exchanges a request token and checksum for a full API session.
   *
   * @param apiKey The API key configured for this application
   * @param requestToken The request token from the OAuth redirect
   * @param checksum SHA-256 hex digest of {@code apiKey + requestToken + apiSecret}
   * @return SessionResponse containing the status and wrapped KiteSession data
   */
  SessionResponse generateSession(String apiKey, String requestToken, String checksum);

  /**
   * Fetches all positions (net + day) from the Zerodha portfolio API.
   *
   * @param accessToken A valid access token for the authenticated user
   * @return PositionsResponse containing net and day positions
   */
  PositionsResponse getPositions(String accessToken);
}
