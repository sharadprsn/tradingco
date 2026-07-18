package com.kite.trading.service;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kite.trading.config.NseConfig;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@SpringBootTest
@ActiveProfiles("test")
class NseConnectivityDiagnosticTest {

  private static final Logger logger = LoggerFactory.getLogger(NseConnectivityDiagnosticTest.class);
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Autowired private WebClient webClient;
  @Autowired private NseConfig nseConfig;

  @Test
  void testSessionEstablishment() {
    logger.info("=== Test: Session Establishment ===");
    logger.info("Home URL: {}", nseConfig.getHomeUrl());
    logger.info("User-Agent: {}", nseConfig.getUserAgent());

    final String cookie =
        webClient
            .get()
            .uri(nseConfig.getHomeUrl())
            .headers(h -> addBrowserHeaders(h))
            .exchangeToMono(
                resp -> {
                  logger.info("Session response status: {}", resp.statusCode());
                  resp.headers()
                      .asHttpHeaders()
                      .forEach(
                          (name, values) -> logger.debug("Response header: {} = {}", name, values));
                  final List<String> cookies =
                      resp.headers().asHttpHeaders().get(HttpHeaders.SET_COOKIE);
                  if (cookies != null && !cookies.isEmpty()) {
                    logger.info("Captured {} Set-Cookie headers", cookies.size());
                    cookies.forEach(c -> logger.debug("Cookie: {}", c));
                    return Mono.just(
                        cookies.stream()
                            .map(c -> c.split(";")[0])
                            .filter(c -> c.contains("="))
                            .collect(Collectors.joining("; ")));
                  }
                  logger.warn("No Set-Cookie headers received");
                  return resp.bodyToMono(String.class).thenReturn("");
                })
            .onErrorResume(
                e -> {
                  logger.error(
                      "Session establishment failed: {}: {}",
                      e.getClass().getSimpleName(),
                      e.getMessage());
                  return Mono.just("");
                })
            .block();

    assertNotNull(cookie);
    assertFalse(cookie.isEmpty(), "Should have captured a session cookie");
    logger.info("Session cookie: {}", cookie);
  }

  @Test
  void testIndexQuoteEndpoint() {
    logger.info("=== Test: Index Quote Endpoint ===");
    final String symbol = "NIFTY";
    final String cookie = establishSession();
    assertNotNull(cookie);
    assertFalse(cookie.isEmpty());

    final String url = nseConfig.getIndexQuoteUrl(symbol);
    logger.info("Fetching index quote from: {}", url);

    final String raw =
        webClient
            .get()
            .uri(url)
            .headers(h -> addApiHeaders(h, cookie))
            .exchangeToMono(
                resp -> {
                  logger.info("Index quote response status: {}", resp.statusCode());
                  resp.headers()
                      .asHttpHeaders()
                      .forEach(
                          (name, values) -> logger.debug("Response header: {} = {}", name, values));
                  return resp.bodyToMono(String.class);
                })
            .onErrorResume(
                e -> {
                  logger.error(
                      "Index quote failed: {}: {}", e.getClass().getSimpleName(), e.getMessage());
                  return Mono.just("");
                })
            .block();

    assertNotNull(raw, "Response body should not be null");
    assertFalse(raw.isBlank(), "Response body should not be blank");
    logger.info("Index quote raw response length: {} chars", raw.length());
    logger.info("Index quote raw (truncated): {}", truncate(raw, 500));

    try {
      final JsonNode root = MAPPER.readTree(raw);
      assertTrue(root.isArray(), "Index quote response should be an array");
      assertTrue(root.size() > 0, "Index quote array should have entries");
      final JsonNode first = root.get(0);
      logger.info("Index name: {}", first.get("index").asText());
      logger.info("Index value: {}", first.get("lastPrice"));
      assertNotNull(first.get("lastPrice"), "lastPrice should be present");
      assertTrue(first.get("lastPrice").asDouble() > 0, "Index value should be positive");
    } catch (final Exception e) {
      logger.error("Failed to parse index quote JSON: {}", e.getMessage());
      throw new AssertionError("Index quote response is not valid JSON", e);
    }
  }

  @Test
  void testContractInfoEndpoint() {
    logger.info("=== Test: Contract Info Endpoint ===");
    final String symbol = "NIFTY";
    final String cookie = establishSession();
    assertNotNull(cookie);
    assertFalse(cookie.isEmpty());

    final String url = nseConfig.getContractInfoUrl(symbol);
    logger.info("Fetching contract info from: {}", url);

    final String raw =
        webClient
            .get()
            .uri(url)
            .headers(h -> addApiHeaders(h, cookie))
            .exchangeToMono(
                resp -> {
                  logger.info("Contract info response status: {}", resp.statusCode());
                  if (!resp.statusCode().is2xxSuccessful()) {
                    logger.warn("Contract info request failed with status {}", resp.statusCode());
                    return resp.bodyToMono(String.class);
                  }
                  return resp.bodyToMono(String.class);
                })
            .onErrorResume(
                e -> {
                  logger.error(
                      "Contract info failed: {}: {}", e.getClass().getSimpleName(), e.getMessage());
                  return Mono.just("");
                })
            .block();

    assertNotNull(raw, "Response body should not be null");
    assertFalse(raw.isBlank(), "Response body should not be blank");
    logger.info("Contract info raw response length: {} chars", raw.length());
    logger.info("Contract info raw (truncated): {}", truncate(raw, 800));

    try {
      final JsonNode root = MAPPER.readTree(raw);
      final JsonNode expiryDates = root.get("expiryDates");
      assertNotNull(expiryDates, "Response should contain expiryDates");
      assertTrue(expiryDates.isArray(), "expiryDates should be an array");
      assertFalse(expiryDates.isEmpty(), "expiryDates should not be empty");
      logger.info("First expiry: {}", expiryDates.get(0).asText());
      logger.info("Total expiry dates: {}", expiryDates.size());
      logger.info("Underlying: {}", root.get("underlying"));
      logger.info("Underlying values: {}", root.get("underlyingValues"));
    } catch (final Exception e) {
      logger.error("Failed to parse contract info JSON: {}", e.getMessage());
      throw new AssertionError("Contract info response is not valid JSON", e);
    }
  }

  @Test
  void testOptionChainEndpoint() {
    logger.info("=== Test: Option Chain Endpoint ===");
    final String symbol = "NIFTY";
    final String cookie = establishSession();
    assertNotNull(cookie);
    assertFalse(cookie.isEmpty());

    final String expiry = resolveNearestExpiry(symbol, cookie);
    assertNotNull(expiry, "Should resolve nearest expiry");
    logger.info("Nearest expiry: {}", expiry);

    final String url = nseConfig.getOptionChainUrl(symbol) + "&expiry=" + expiry;
    logger.info("Fetching option chain from: {}", url);

    final String raw =
        webClient
            .get()
            .uri(url)
            .headers(h -> addApiHeaders(h, cookie))
            .exchangeToMono(
                resp -> {
                  logger.info("Option chain response status: {}", resp.statusCode());
                  logger.info(
                      "Response content-type: {}", resp.headers().asHttpHeaders().getContentType());
                  resp.headers()
                      .asHttpHeaders()
                      .forEach(
                          (name, values) -> logger.debug("Response header: {} = {}", name, values));
                  return resp.bodyToMono(String.class);
                })
            .onErrorResume(
                e -> {
                  logger.error(
                      "Option chain failed: {}: {}", e.getClass().getSimpleName(), e.getMessage());
                  return Mono.just("");
                })
            .block();

    assertNotNull(raw, "Response body should not be null");
    assertFalse(raw.isBlank(), "Response body should not be blank");
    logger.info("Option chain raw response length: {} chars", raw.length());
    logger.info("Option chain raw (first 1000 chars): {}", truncate(raw, 1000));

    try {
      final JsonNode root = MAPPER.readTree(raw);
      assertNotNull(root.get("records"), "Response should contain records");
      final JsonNode records = root.get("records");
      assertNotNull(records.get("data"), "Records should contain data");
      assertTrue(records.get("data").isArray(), "data should be an array");
      assertTrue(records.get("data").size() > 0, "data should have entries");
      logger.info("Total option records: {}", records.get("data").size());
      logger.info("Underlying value: {}", records.get("underlyingValue"));
      logger.info("Timestamp: {}", records.get("timestamp"));
      logger.info("Expiry dates: {}", records.get("expiryDates"));
      logger.info(
          "Strike prices count: {}",
          records.get("strikePrices") != null ? records.get("strikePrices").size() : 0);

      final JsonNode first = records.get("data").get(0);
      logger.info(
          "First record - strikePrice: {}, expiry: {}",
          first.get("strikePrice"),
          first.get("expiryDates"));
    } catch (final Exception e) {
      logger.error("Failed to parse option chain JSON: {}", e.getMessage());
      throw new AssertionError("Option chain response is not valid JSON", e);
    }
  }

  @Test
  void testWithJavaHttpClient() {
    logger.info("=== Test: Java HttpClient Fallback ===");
    try {
      final HttpClient client =
          HttpClient.newBuilder()
              .followRedirects(HttpClient.Redirect.NORMAL)
              .connectTimeout(Duration.ofSeconds(15))
              .build();

      final String homeUrl = "https://www.nseindia.com";
      logger.info("Step 1: Hit home page to establish session via Java HttpClient");

      final HttpRequest homeRequest =
          HttpRequest.newBuilder()
              .uri(URI.create(homeUrl))
              .timeout(Duration.ofSeconds(15))
              .header("User-Agent", nseConfig.getUserAgent())
              .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
              .header("Accept-Language", "en-US,en;q=0.9")
              .header("Accept-Encoding", "gzip, deflate, br")
              .GET()
              .build();

      final HttpResponse<String> homeResponse =
          client.send(homeRequest, HttpResponse.BodyHandlers.ofString());
      logger.info("Home page status: {}", homeResponse.statusCode());
      logger.info("Home page headers:");
      homeResponse.headers().map().forEach((k, v) -> logger.info("  {}: {}", k, v));

      final var cookies = homeResponse.headers().allValues("Set-Cookie");
      if (cookies.isEmpty()) {
        cookies.addAll(homeResponse.headers().allValues("set-cookie"));
      }
      logger.info("Set-Cookie count: {}", cookies.size());
      cookies.forEach(c -> logger.info("  Cookie: {}", c));

      final String sessionCookie =
          cookies.stream()
              .map(c -> c.split(";")[0])
              .filter(c -> c.contains("="))
              .collect(Collectors.joining("; "));
      logger.info("Session cookie: {}", sessionCookie);
      assertFalse(sessionCookie.isEmpty(), "Should have captured session cookie");

      final String optionChainUrl = nseConfig.getOptionChainUrl("NIFTY");
      logger.info("Step 2: Fetch option chain from: {}", optionChainUrl);

      final String expiry = resolveNearestExpiryJava(client, sessionCookie);
      assertNotNull(expiry, "Should resolve nearest expiry");

      final String fullUrl = optionChainUrl + "&expiry=" + expiry;
      final HttpRequest dataRequest =
          HttpRequest.newBuilder()
              .uri(URI.create(fullUrl))
              .timeout(Duration.ofSeconds(15))
              .header("User-Agent", nseConfig.getUserAgent())
              .header("Accept", MediaType.APPLICATION_JSON_VALUE)
              .header("Accept-Language", "en-US,en;q=0.9")
              .header("Referer", "https://www.nseindia.com/option-chain")
              .header("Cookie", sessionCookie)
              .GET()
              .build();

      final HttpResponse<String> dataResponse =
          client.send(dataRequest, HttpResponse.BodyHandlers.ofString());
      logger.info("Option chain status: {}", dataResponse.statusCode());
      logger.info("Option chain body length: {}", dataResponse.body().length());
      logger.info("Option chain body (truncated): {}", truncate(dataResponse.body(), 500));

      assertEquals(200, dataResponse.statusCode(), "Option chain request should succeed");

      final JsonNode root = MAPPER.readTree(dataResponse.body());
      assertNotNull(root.get("records"));
      final JsonNode records = root.get("records");
      assertNotNull(records.get("data"));
      assertTrue(records.get("data").isArray());
      assertTrue(records.get("data").size() > 0);
      logger.info(
          "Java HttpClient test passed! Found {} option records", records.get("data").size());

    } catch (final Exception e) {
      logger.error(
          "Java HttpClient test failed: {}: {}", e.getClass().getSimpleName(), e.getMessage());
      throw new AssertionError("Java HttpClient test failed", e);
    }
  }

  private String establishSession() {
    logger.info("Establishing NSE session...");
    final String homeUrl = nseConfig.getHomeUrl();
    try {
      return webClient
          .get()
          .uri(homeUrl)
          .headers(NseConnectivityDiagnosticTest::addBrowserHeaders)
          .exchangeToMono(
              resp -> {
                logger.info("Session response status: {}", resp.statusCode());
                if (!resp.statusCode().is2xxSuccessful()) {
                  logger.warn("Session page returned {}", resp.statusCode());
                  return resp.bodyToMono(String.class).thenReturn("");
                }
                final List<String> cookies =
                    resp.headers().asHttpHeaders().get(HttpHeaders.SET_COOKIE);
                if (cookies != null && !cookies.isEmpty()) {
                  return Mono.just(
                      cookies.stream()
                          .map(c -> c.split(";")[0])
                          .filter(c -> c.contains("="))
                          .collect(Collectors.joining("; ")));
                }
                logger.warn("No Set-Cookie in response");
                return resp.bodyToMono(String.class).thenReturn("");
              })
          .onErrorResume(
              e -> {
                logger.error(
                    "Session failed: {}: {}", e.getClass().getSimpleName(), e.getMessage());
                return Mono.just("");
              })
          .block();
    } catch (final Exception e) {
      logger.error(
          "Session establishment error: {}: {}", e.getClass().getSimpleName(), e.getMessage());
      return "";
    }
  }

  private String resolveNearestExpiry(final String symbol, final String cookie) {
    try {
      final String url = nseConfig.getContractInfoUrl(symbol);
      logger.info("Resolving nearest expiry from: {}", url);
      final String raw =
          webClient
              .get()
              .uri(url)
              .headers(h -> addApiHeaders(h, cookie))
              .retrieve()
              .bodyToMono(String.class)
              .block();
      if (raw == null || raw.isBlank()) {
        logger.warn("Empty contract info response");
        return null;
      }
      final JsonNode root = MAPPER.readTree(raw);
      final JsonNode expiryDates = root.get("expiryDates");
      if (expiryDates == null || !expiryDates.isArray() || expiryDates.isEmpty()) {
        logger.warn("No expiry dates in contract info response");
        logger.info("Full response: {}", truncate(raw, 500));
        return null;
      }
      return expiryDates.get(0).asText();
    } catch (final Exception e) {
      logger.error(
          "Failed to resolve expiry: {}: {}", e.getClass().getSimpleName(), e.getMessage());
      return null;
    }
  }

  private String resolveNearestExpiryJava(final HttpClient client, final String cookie)
      throws Exception {
    final String url = nseConfig.getContractInfoUrl("NIFTY");
    logger.info("Resolving nearest expiry (Java HttpClient) from: {}", url);

    final HttpRequest request =
        HttpRequest.newBuilder()
            .uri(URI.create(url))
            .timeout(Duration.ofSeconds(15))
            .header("User-Agent", nseConfig.getUserAgent())
            .header("Accept", MediaType.APPLICATION_JSON_VALUE)
            .header("Accept-Language", "en-US,en;q=0.9")
            .header("Referer", "https://www.nseindia.com/option-chain")
            .header("Cookie", cookie)
            .GET()
            .build();

    final HttpResponse<String> response =
        client.send(request, HttpResponse.BodyHandlers.ofString());
    logger.info("Contract info status: {}", response.statusCode());
    logger.info("Contract info body length: {}", response.body().length());

    if (response.statusCode() != 200) {
      logger.warn("Contract info request failed: body={}", truncate(response.body(), 500));
      return null;
    }

    final JsonNode root = MAPPER.readTree(response.body());
    final JsonNode expiryDates = root.get("expiryDates");
    if (expiryDates == null || !expiryDates.isArray() || expiryDates.isEmpty()) {
      logger.warn("No expiry dates in response");
      return null;
    }
    return expiryDates.get(0).asText();
  }

  private static void addBrowserHeaders(final HttpHeaders headers) {
    headers.set(
        "User-Agent",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
    headers.set(
        "Accept",
        "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8");
    headers.set("Accept-Language", "en-US,en;q=0.9");
    headers.set("Accept-Encoding", "gzip, deflate, br");
    headers.set("Connection", "keep-alive");
    headers.set("Sec-Fetch-Dest", "document");
    headers.set("Sec-Fetch-Mode", "navigate");
    headers.set("Sec-Fetch-Site", "none");
    headers.set("Sec-Fetch-User", "?1");
    headers.set(
        "Sec-Ch-Ua",
        "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\"");
    headers.set("Sec-Ch-Ua-Mobile", "?0");
    headers.set("Sec-Ch-Ua-Platform", "\"Windows\"");
    headers.set("Upgrade-Insecure-Requests", "1");
  }

  private static void addApiHeaders(final HttpHeaders headers, final String cookie) {
    headers.set(
        "User-Agent",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
    headers.set("Accept", MediaType.APPLICATION_JSON_VALUE);
    headers.set("Accept-Language", "en-US,en;q=0.9");
    headers.set("Accept-Encoding", "gzip, deflate, br");
    headers.set("Connection", "keep-alive");
    headers.set("Referer", "https://www.nseindia.com/option-chain");
    headers.set("Cookie", cookie);
    headers.set("Sec-Fetch-Dest", "empty");
    headers.set("Sec-Fetch-Mode", "cors");
    headers.set("Sec-Fetch-Site", "same-origin");
    headers.set(
        "Sec-Ch-Ua",
        "\"Not_A Brand\";v=\"8\", \"Chromium\";v=\"120\", \"Google Chrome\";v=\"120\"");
    headers.set("Sec-Ch-Ua-Mobile", "?0");
    headers.set("Sec-Ch-Ua-Platform", "\"Windows\"");
    headers.set("Origin", "https://www.nseindia.com");
  }

  private static String truncate(final String s, final int maxLen) {
    if (s == null) return "null";
    if (s.length() <= maxLen) return s;
    return s.substring(0, maxLen) + "... (truncated, total " + s.length() + " chars)";
  }
}
