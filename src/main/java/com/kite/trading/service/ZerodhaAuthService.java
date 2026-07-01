package com.kite.trading.service;

import com.kite.trading.config.KiteConfig;
import com.kite.trading.dto.KiteSession;
import com.kite.trading.dto.SessionRequest;
import com.kite.trading.dto.SessionResponse;
import com.kite.trading.exception.KiteAuthenticationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Implementation of {@link KiteAuthService} for the Zerodha Kite OAuth flow.
 * 
 * <p><b>Authentication flow:</b>
 * <ol>
 *   <li>Call {@link #generateLoginUrl()} – returns a URL the user visits in
 *       their browser to log in with their Zerodha credentials + TOTP.</li>
 *   <li>Zerodha redirects back with a {@code request_token} query parameter.</li>
 *   <li>Call {@link #generateSession(SessionRequest)} – exchanges the request
 *       token for an access token that is used for all subsequent API calls.
 *       The request must include a {@code checksum} computed as the SHA-256
 *       hex digest of {@code api_key + request_token + api_secret}.</li>
 * </ol>
 * 
 * This class follows the Single Responsibility Principle by handling only
 * authentication concerns.
 * 
 * @author Kite Trading Team
 * @version 1.0.0
 */
@Service
public class ZerodhaAuthService implements KiteAuthService {

    private static final Logger logger = LoggerFactory.getLogger(ZerodhaAuthService.class);

    private static final String HASH_ALGORITHM = "SHA-256";

    private final ZerodhaApiClient apiClient;
    private final KiteConfig kiteConfig;
    private KiteSession currentSession;

    /**
     * Constructs ZerodhaAuthService with the required dependencies.
     *
     * @param apiClient  The Zerodha API client for making API calls
     * @param kiteConfig The configuration containing API key, secret, and URLs
     */
    public ZerodhaAuthService(final ZerodhaApiClient apiClient,
                              final KiteConfig kiteConfig) {
        this.apiClient = apiClient;
        this.kiteConfig = kiteConfig;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String generateLoginUrl() {
        final String url = kiteConfig.getLoginUrl()
                + "?api_key=" + kiteConfig.getApiKey()
                + "&v3=1";
        logger.info("Generated Kite Connect login URL");
        return url;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public KiteSession generateSession(final SessionRequest sessionRequest) {
        final String requestToken = sessionRequest.requestToken();

        if (requestToken == null || requestToken.isBlank()) {
            throw new KiteAuthenticationException(
                    "request_token must not be null or blank");
        }

        final String checksum = computeChecksum(requestToken);

        logger.info("Exchanging request token for session");

        final SessionResponse response = apiClient.generateSession(
                kiteConfig.getApiKey(), requestToken, checksum);

        if (response == null || response.data() == null) {
            throw new KiteAuthenticationException(
                    "Session generation returned an empty response");
        }

        currentSession = response.data();

        if (currentSession.accessToken() == null) {
            throw new KiteAuthenticationException(
                    "Session generated without an access token");
        }

        logger.info("Session generated successfully for user: {}",
                currentSession.userId());
        return currentSession;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public KiteSession getCurrentSession() {
        return currentSession;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isAuthenticated() {
        return currentSession != null && currentSession.accessToken() != null;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void logout() {
        logger.info("Clearing current session");
        currentSession = null;
    }

    /**
     * Computes the Kite Connect API checksum.
     * 
     * The checksum is the lowercase hexadecimal SHA-256 digest of
     * {@code api_key + request_token + api_secret}.
     *
     * @param requestToken the request token received from the OAuth redirect
     * @return the hex-encoded SHA-256 checksum
     * @throws KiteAuthenticationException if the hash algorithm is unavailable
     */
    private String computeChecksum(final String requestToken) {
        final String raw = kiteConfig.getApiKey() + requestToken + kiteConfig.getApiSecret();

        try {
            final MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            final byte[] hash = digest.digest(raw.getBytes());
            return HexFormat.of().formatHex(hash);
        } catch (final NoSuchAlgorithmException e) {
            throw new KiteAuthenticationException(
                    "SHA-256 algorithm not available: " + e.getMessage(), e);
        }
    }
}
