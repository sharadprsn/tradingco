package com.kite.trading.service;

import com.kite.trading.config.KiteConfig;
import com.kite.trading.dto.PositionsResponse;
import com.kite.trading.dto.SessionResponse;
import com.kite.trading.exception.KiteApiException;
import com.kite.trading.exception.KiteAuthenticationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * Implementation of {@link ZerodhaApiClient} that communicates with the Zerodha Kite REST API over
 * HTTP.
 *
 * <p>This class follows the Single Responsibility Principle by focusing solely on HTTP-level API
 * communication; it has no knowledge of business logic or authentication flow sequencing.
 *
 * @author Kite Trading Team
 * @version 1.0.0
 */
@Component
public class ZerodhaApiClientImpl implements ZerodhaApiClient {

  private static final Logger logger = LoggerFactory.getLogger(ZerodhaApiClientImpl.class);

  private final WebClient webClient;
  private final KiteConfig kiteConfig;

  /**
   * Constructs a ZerodhaApiClientImpl with the required dependencies.
   *
   * @param webClient The WebClient used for HTTP communication
   * @param kiteConfig The Kite API configuration (base URL, API key, etc.)
   */
  public ZerodhaApiClientImpl(final WebClient webClient, final KiteConfig kiteConfig) {
    this.webClient = webClient;
    this.kiteConfig = kiteConfig;
  }

  /** {@inheritDoc} */
  @Override
  public SessionResponse generateSession(
      final String apiKey, final String requestToken, final String checksum) {
    logger.info("Exchanging request token for API session");

    try {
      final String sessionUrl = kiteConfig.getBaseUrl() + "/session/token";

      final MultiValueMap<String, String> formData = new LinkedMultiValueMap<>();
      formData.add("api_key", apiKey);
      formData.add("request_token", requestToken);
      formData.add("checksum", checksum);

      return webClient
          .post()
          .uri(sessionUrl)
          .contentType(MediaType.APPLICATION_FORM_URLENCODED)
          .bodyValue(formData)
          .retrieve()
          .bodyToMono(SessionResponse.class)
          .block();
    } catch (final Exception e) {
      logger.error("Session generation failed", e);
      throw new KiteAuthenticationException("Session generation failed: " + e.getMessage(), e);
    }
  }

  /** {@inheritDoc} */
  @Override
  public PositionsResponse getPositions(final String accessToken) {
    logger.info("Fetching positions from Zerodha API");
    logger.debug(
        "Using access token: {}...",
        accessToken != null && accessToken.length() > 8 ? accessToken.substring(0, 8) : "null");

    try {
      final String positionsUrl = kiteConfig.getBaseUrl() + "/portfolio/positions";
      final String authHeader = "token " + kiteConfig.getApiKey() + ":" + accessToken;

      return webClient
          .get()
          .uri(positionsUrl)
          .header("Authorization", authHeader)
          .header("X-Kite-Version", "3")
          .retrieve()
          .onStatus(HttpStatusCode::isError, this::handleErrorResponse)
          .bodyToMono(PositionsResponse.class)
          .block();
    } catch (final KiteApiException e) {
      throw e;
    } catch (final Exception e) {
      logger.error("Failed to fetch positions", e);
      throw new KiteApiException("Failed to fetch positions: " + e.getMessage(), e);
    }
  }

  /**
   * Handles non-2xx responses from the Kite API by capturing the response body.
   *
   * @param response The client response with error status
   * @return Mono that emits a KiteApiException with the response body
   */
  private Mono<Throwable> handleErrorResponse(final ClientResponse response) {
    return response
        .bodyToMono(String.class)
        .flatMap(
            body -> {
              logger.error("Kite API error ({}): {}", response.statusCode(), body);
              return Mono.error(
                  new KiteApiException("Kite API returned " + response.statusCode() + ": " + body));
            });
  }
}
