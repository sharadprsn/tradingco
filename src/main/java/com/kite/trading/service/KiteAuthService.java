package com.kite.trading.service;

import com.kite.trading.dto.KiteSession;
import com.kite.trading.dto.SessionRequest;

/**
 * Service interface for Zerodha Kite authentication operations.
 * 
 * This interface defines the contract for the OAuth-based authentication flow
 * with the Zerodha Kite API:
 * 
 * <ol>
 *   <li>Obtain a login URL via {@link #generateLoginUrl()}</li>
 *   <li>User visits the URL in a browser, authenticates, and is redirected back
 *       with a {@code request_token}</li>
 *   <li>Exchange the request token for an access token via
 *       {@link #generateSession(SessionRequest)}</li>
 * </ol>
 * 
 * This follows the Interface Segregation Principle (ISP) by keeping
 * authentication concerns separate from other API operations.
 * 
 * @author Kite Trading Team
 * @version 1.0.0
 */
public interface KiteAuthService {

    /**
     * Generates the Kite Connect login URL.
     * 
     * The client should open this URL in a browser so the user can authenticate
     * with their Zerodha credentials (including TOTP). After successful login,
     * Zerodha redirects to the configured redirect URL with a
     * {@code request_token} query parameter.
     *
     * @return The full Kite Connect login URL
     */
    String generateLoginUrl();

    /**
     * Exchanges the provided {@code request_token} for a full API session.
     * 
     * This method calls the Kite API to generate an access token from the
     * request token obtained during the OAuth redirect.
     *
     * @param sessionRequest The request containing the {@code request_token}
     * @return KiteSession with access token and user details
     * @throws KiteAuthenticationException if the token exchange fails
     */
    KiteSession generateSession(SessionRequest sessionRequest);

    /**
     * Retrieves the current active session.
     *
     * @return The current KiteSession if authenticated, {@code null} otherwise
     */
    KiteSession getCurrentSession();

    /**
     * Checks whether an authenticated session currently exists.
     *
     * @return {@code true} if there is an active session, {@code false} otherwise
     */
    boolean isAuthenticated();

    /**
     * Invalidates the current session and clears all tokens.
     */
    void logout();
}
