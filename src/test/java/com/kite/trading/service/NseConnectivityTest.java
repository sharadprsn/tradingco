package com.kite.trading.service;

import static org.junit.jupiter.api.Assertions.*;

import com.kite.trading.config.NseConfig;
import com.kite.trading.dto.OptionChainData;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class NseConnectivityTest {

  @Autowired private OptionChainClient optionChainClient;

  @Autowired private NseConfig nseConfig;

  @Test
  void verifyNseConfig() {
    assertNotNull(nseConfig.getHomeUrl());
    assertNotNull(nseConfig.getOptionChainUrl("NIFTY"));
    assertNotNull(nseConfig.getUserAgent());
    assertTrue(nseConfig.getOptionChainUrl("NIFTY").contains("NIFTY"));
  }

  @Test
  void fetchOptionChainFromNse() {
    final OptionChainData data = optionChainClient.fetchOptionChain();
    assertNotNull(data, "Option chain data should not be null");
    assertNotNull(data.records(), "Records should not be null");
    assertNotNull(data.records().data(), "Options data list should not be null");
    assertFalse(data.records().data().isEmpty(), "Should have at least one option contract");
    assertNotNull(data.records().underlyingValue(), "Underlying value should be present");
    assertTrue(
        data.records().underlyingValue().compareTo(java.math.BigDecimal.ZERO) > 0,
        "Underlying value should be positive");
    assertNotNull(data.records().expiryDates(), "Expiry dates should not be null");
    assertFalse(data.records().expiryDates().isEmpty(), "Should have at least one expiry date");
    assertNotNull(data.records().strikePrices(), "Strike prices should not be null");
    assertFalse(data.records().strikePrices().isEmpty(), "Should have at least one strike price");
  }

  @Test
  void optionDataHasValidContracts() {
    final OptionChainData data = optionChainClient.fetchOptionChain();
    assertNotNull(data);
    assertNotNull(data.records());

    final var sampleOption =
        data.records().data().stream()
            .filter(d -> d.ce() != null || d.pe() != null)
            .findFirst()
            .orElse(null);
    assertNotNull(sampleOption, "At least one option should have CE or PE contract");

    if (sampleOption.ce() != null) {
      assertNotNull(sampleOption.ce().strikePrice());
      assertNotNull(sampleOption.ce().openInterest());
      assertNotNull(sampleOption.ce().impliedVolatility());
      assertNotNull(sampleOption.ce().lastPrice());
    }
    if (sampleOption.pe() != null) {
      assertNotNull(sampleOption.pe().strikePrice());
      assertNotNull(sampleOption.pe().openInterest());
      assertNotNull(sampleOption.pe().impliedVolatility());
      assertNotNull(sampleOption.pe().lastPrice());
    }
  }

  @Test
  void verifyDirectV3Connectivity() {
    final String targetUrl =
        "https://www.nseindia.com/api/option-chain-v3?type=Indices&symbol=NIFTY&expiry=21-Jul-2026";
    final String homeUrl = "https://www.nseindia.com/option-chain";

    try {
      final java.net.http.HttpClient client =
          java.net.http.HttpClient.newBuilder()
              .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
              .connectTimeout(java.time.Duration.ofSeconds(15))
              .build();

      // Step 1: Establish session and get cookie
      final java.net.http.HttpRequest homeRequest =
          java.net.http.HttpRequest.newBuilder()
              .uri(java.net.URI.create(homeUrl))
              .timeout(java.time.Duration.ofSeconds(15))
              .header("User-Agent", nseConfig.getUserAgent())
              .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
              .header("Accept-Language", "en-US,en;q=0.9")
              .GET()
              .build();

      final java.net.http.HttpResponse<String> homeResponse =
          client.send(homeRequest, java.net.http.HttpResponse.BodyHandlers.ofString());

      assertEquals(200, homeResponse.statusCode(), "NSE home page request should return 200");

      final java.util.List<String> cookies =
          new java.util.ArrayList<>(homeResponse.headers().allValues("Set-Cookie"));
      if (cookies.isEmpty()) {
        cookies.addAll(homeResponse.headers().allValues("set-cookie"));
      }

      assertFalse(cookies.isEmpty(), "Should receive at least one cookie from NSE home page");

      final String sessionCookie =
          cookies.stream()
              .map(c -> c.split(";")[0])
              .filter(c -> c.contains("="))
              .collect(java.util.stream.Collectors.joining("; "));

      assertNotNull(sessionCookie);
      assertFalse(sessionCookie.isEmpty(), "Session cookie string should not be empty");

      // Step 2: Query the option chain API using the cookies
      final java.net.http.HttpRequest apiRequest =
          java.net.http.HttpRequest.newBuilder()
              .uri(java.net.URI.create(targetUrl))
              .timeout(java.time.Duration.ofSeconds(15))
              .header("User-Agent", nseConfig.getUserAgent())
              .header("Accept", "application/json")
              .header("Accept-Language", "en-US,en;q=0.9")
              .header("Referer", homeUrl)
              .header("Cookie", sessionCookie)
              .GET()
              .build();

      final java.net.http.HttpResponse<String> apiResponse =
          client.send(apiRequest, java.net.http.HttpResponse.BodyHandlers.ofString());

      assertEquals(200, apiResponse.statusCode(), "NSE API request should return 200 OK");

      final String body = apiResponse.body();
      assertNotNull(body, "Response body should not be null");
      assertFalse(body.isBlank(), "Response body should not be blank");

      // Parse JSON and verify it has records.data structure
      final com.fasterxml.jackson.databind.ObjectMapper mapper =
          new com.fasterxml.jackson.databind.ObjectMapper();
      final com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(body);

      assertNotNull(root.get("records"), "Response JSON should contain 'records' field");
      final com.fasterxml.jackson.databind.JsonNode records = root.get("records");

      assertNotNull(records.get("data"), "records should contain 'data' array");
      assertTrue(records.get("data").isArray(), "'data' should be an array");
      assertFalse(records.get("data").isEmpty(), "'data' array should not be empty");

      System.out.println(
          "Connectivity verified successfully! Total records found: " + records.get("data").size());

    } catch (final Exception e) {
      fail("NSE connectivity test failed with exception: " + e.getMessage(), e);
    }
  }
}
