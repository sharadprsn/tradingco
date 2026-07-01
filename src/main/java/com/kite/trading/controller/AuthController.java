package com.kite.trading.controller;

import com.kite.trading.dto.ErrorResponse;
import com.kite.trading.dto.KiteSession;
import com.kite.trading.dto.LoginUrlResponse;
import com.kite.trading.dto.SessionRequest;
import com.kite.trading.service.KiteAuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * REST controller for authentication operations.
 * 
 * Provides endpoints for the Kite Connect OAuth flow:
 * <ul>
 *   <li>{@code GET /api/v1/auth/login-url} – obtain the login URL to visit
 *       in a browser</li>
 *   <li>{@code POST /api/v1/auth/session} – exchange the received
 *       {@code request_token} for an API session</li>
 *   <li>{@code POST /api/v1/auth/logout} – invalidate the current session</li>
 * </ul>
 * 
 * This class follows the Single Responsibility Principle by handling only
 * REST-level authentication concerns.
 * 
 * @author Kite Trading Team
 * @version 1.0.0
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);

    private final KiteAuthService authService;

    /**
     * Constructs AuthController with the authentication service.
     *
     * @param authService The authentication service for OAuth operations
     */
    public AuthController(final KiteAuthService authService) {
        this.authService = authService;
    }

    /**
     * Returns the Kite Connect login URL.
     * 
     * The client should open this URL in a browser. After the user authenticates
     * with their Zerodha credentials, the browser redirects to the configured
     * redirect URI with a {@code request_token} query parameter.
     *
     * @return ResponseEntity containing the login URL
     */
    @GetMapping("/login-url")
    public ResponseEntity<LoginUrlResponse> getLoginUrl() {
        logger.info("Generating Kite Connect login URL");
        final String url = authService.generateLoginUrl();
        return ResponseEntity.ok(new LoginUrlResponse(url));
    }

    /**
     * OAuth callback endpoint.
     * 
     * After the user logs in via the Kite Connect URL in their browser,
     * Zerodha redirects here with a {@code request_token} query parameter.
     * This endpoint automatically exchanges it for a session and returns
     * the session details.
     * 
     * If no {@code request_token} is present the endpoint returns a
     * {@code 400 Bad Request}.
     *
     * @param requestToken The request token from the OAuth redirect (query param)
     * @return ResponseEntity containing the authenticated session
     */
    @GetMapping("/callback")
    public ResponseEntity<?> callback(
            @RequestParam(name = "request_token", required = false) final String requestToken) {
        if (requestToken == null || requestToken.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(ErrorResponse.of("MISSING_PARAM",
                            "Missing required query parameter: request_token"));
        }
        logger.info("OAuth callback received – exchanging request token for session");
        final KiteSession session = authService.generateSession(
                new SessionRequest(requestToken));
        return ResponseEntity.ok(session);
    }

    /**
     * Exchanges a {@code request_token} for a full API session.
     * 
     * The {@code request_token} is obtained from the OAuth redirect after the
     * user successfully logs in via the browser. This endpoint exchanges it
     * for an access token that is used for all subsequent API calls.
     *
     * @param sessionRequest The request containing the {@code request_token}
     * @return ResponseEntity containing the authenticated session
     */
    @PostMapping("/session")
    public ResponseEntity<KiteSession> generateSession(
            @RequestBody final SessionRequest sessionRequest) {
        logger.info("Exchanging request token for session");
        final KiteSession session = authService.generateSession(sessionRequest);
        return ResponseEntity.ok(session);
    }

    /**
     * Logs out the current user and invalidates the session.
     *
     * @return ResponseEntity with a success message
     */
    @PostMapping("/logout")
    public ResponseEntity<String> logout() {
        logger.info("Logout request received");
        authService.logout();
        return ResponseEntity.ok("Logged out successfully");
    }

    /**
     * Checks whether an authenticated session currently exists.
     *
     * @return ResponseEntity containing the authentication status
     */
    @GetMapping("/status")
    public ResponseEntity<Boolean> isAuthenticated() {
        return ResponseEntity.ok(authService.isAuthenticated());
    }
}
