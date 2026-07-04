package com.kite.trading.service;

import com.kite.trading.dto.OrderRequest;
import com.kite.trading.dto.OrderResponse;
import com.kite.trading.dto.PositionsResponse;
import com.kite.trading.dto.QuoteResponse;
import com.kite.trading.dto.SessionResponse;

/**
 * Client interface for making raw HTTP calls to the Zerodha Kite REST API.
 * 
 * This interface abstracts the transport layer so that higher-level services
 * depend on an abstraction rather than a concrete HTTP implementation,
 * following the Dependency Inversion Principle (DIP).
 * 
 * @author Kite Trading Team
 * @version 1.0.0
 */
public interface ZerodhaApiClient {

    /**
     * Exchanges a request token and checksum for a full API session.
     *
     * @param apiKey       The API key configured for this application
     * @param requestToken The request token from the OAuth redirect
     * @param checksum     SHA-256 hex digest of {@code apiKey + requestToken + apiSecret}
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

    /**
     * Fetches the LTP quote for a given instrument.
     *
     * @param accessToken     A valid access token
     * @param apiKey          The API key for authorization header
     * @param instrumentToken The instrument token to quote
     * @return QuoteResponse containing last price data
     */
    QuoteResponse getQuote(String accessToken, String apiKey, String instrumentToken);

    /**
     * Places an order on the Kite exchange.
     *
     * @param accessToken  A valid access token
     * @param apiKey       The API key for authorization header
     * @param variety      Order variety (e.g. "regular", "amo", "iceberg")
     * @param orderRequest The order details
     * @return OrderResponse containing the order ID
     */
    OrderResponse placeOrder(String accessToken, String apiKey,
                             String variety, OrderRequest orderRequest);

    /**
     * Modifies an existing order.
     *
     * @param accessToken  A valid access token
     * @param apiKey       The API key for authorization header
     * @param variety      Order variety
     * @param orderId      The ID of the order to modify
     * @param orderRequest Updated order details
     * @return OrderResponse containing the updated order ID
     */
    OrderResponse modifyOrder(String accessToken, String apiKey,
                              String variety, String orderId, OrderRequest orderRequest);
}
