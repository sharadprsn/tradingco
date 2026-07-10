package com.kite.trading.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kite.trading.config.NseConfig;
import com.kite.trading.dto.IndexQuote;
import com.kite.trading.dto.OptionChainData;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
public class NseOptionChainClient implements OptionChainClient {

  private static final Logger logger = LoggerFactory.getLogger(NseOptionChainClient.class);

  private final WebClient webClient;
  private final NseConfig nseConfig;
  private final ObjectMapper objectMapper;

  private volatile String sessionCookie;

  public NseOptionChainClient(final WebClient webClient, final NseConfig nseConfig) {
    this.webClient = webClient;
    this.nseConfig = nseConfig;
    this.objectMapper = new ObjectMapper();
  }

  public OptionChainData fetchOptionChain() {
    ensureSession();
    final String expiry = resolveNearestExpiry();
    if (expiry == null) {
      logger.warn("Could not resolve any expiry date, returning empty response");
      return emptyResponse();
    }
    return fetchData(expiry);
  }

  private void ensureSession() {
    if (sessionCookie == null) {
      logger.info("Establishing NSE session via {}", nseConfig.getHomeUrl());
      final boolean success =
          Boolean.TRUE.equals(
              webClient
                  .get()
                  .uri(nseConfig.getHomeUrl())
                  .header("User-Agent", nseConfig.getUserAgent())
                  .header(
                      "Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                  .header("Accept-Language", "en-US,en;q=0.9")
                  .header("Accept-Encoding", "gzip, deflate, br")
                  .exchangeToMono(
                      resp -> {
                        if (!resp.statusCode().is2xxSuccessful()) {
                          logger.warn(
                              "NSE session page returned {} {}",
                              resp.statusCode(),
                              nseConfig.getHomeUrl());
                          return resp.bodyToMono(String.class).thenReturn(false);
                        }
                        final List<String> cookies =
                            resp.headers().asHttpHeaders().get(HttpHeaders.SET_COOKIE);
                        if (cookies != null && !cookies.isEmpty()) {
                          sessionCookie =
                              cookies.stream()
                                  .map(c -> c.split(";")[0])
                                  .filter(c -> c.contains("="))
                                  .collect(Collectors.joining("; "));
                          logger.debug("NSE session cookie captured: {}", sessionCookie);
                        } else {
                          logger.warn("No Set-Cookie header in NSE response");
                        }
                        return resp.bodyToMono(String.class).thenReturn(true);
                      })
                  .onErrorResume(
                      e -> {
                        logger.error("Failed to establish NSE session: {}", e.getMessage());
                        return Mono.just(false);
                      })
                  .block());
      if (Boolean.TRUE.equals(success)) {
        logger.info("NSE session established");
      }
    }
  }

  private String resolveNearestExpiry() {
    try {
      final String raw =
          webClient
              .get()
              .uri(nseConfig.getContractInfoUrl())
              .header("User-Agent", nseConfig.getUserAgent())
              .header("Accept", MediaType.APPLICATION_JSON_VALUE)
              .header("Accept-Language", "en-US,en;q=0.9")
              .header("Referer", nseConfig.getHomeUrl())
              .header("Cookie", sessionCookie != null ? sessionCookie : "")
              .retrieve()
              .bodyToMono(String.class)
              .block();
      if (raw == null || raw.isBlank()) {
        logger.warn("NSE contract info response is empty");
        return null;
      }
      final JsonNode root = objectMapper.readTree(raw);
      final JsonNode expiryDates = root.get("expiryDates");
      if (expiryDates == null || !expiryDates.isArray() || expiryDates.isEmpty()) {
        logger.warn(
            "NSE contract info has no expiryDates: {}",
            raw.substring(0, Math.min(200, raw.length())));
        return null;
      }
      final String nearest = expiryDates.get(0).asText();
      logger.debug("Resolved nearest NSE expiry: {}", nearest);
      return nearest;
    } catch (final Exception e) {
      logger.error(
          "Failed to resolve NSE expiry dates: {} {}",
          e.getClass().getSimpleName(),
          e.getMessage());
    }
    return null;
  }

  private OptionChainData fetchData(final String expiry) {
    try {
      final String url = nseConfig.getOptionChainUrl() + "&expiry=" + expiry;
      logger.debug("Fetching NSE option chain for expiry {}", expiry);
      final V3FullResponse v3 =
          webClient
              .get()
              .uri(url)
              .header("User-Agent", nseConfig.getUserAgent())
              .header("Accept", MediaType.APPLICATION_JSON_VALUE)
              .header("Accept-Language", "en-US,en;q=0.9")
              .header("Referer", nseConfig.getHomeUrl())
              .header("Cookie", sessionCookie != null ? sessionCookie : "")
              .retrieve()
              .bodyToMono(V3FullResponse.class)
              .block();
      return convert(v3);
    } catch (final Exception e) {
      logger.error(
          "Failed to fetch NSE option chain data: {} {}",
          e.getClass().getSimpleName(),
          e.getMessage());
      sessionCookie = null;
      return emptyResponse();
    }
  }

  private static OptionChainData convert(final V3FullResponse v3) {
    if (v3 == null || v3.records() == null) {
      return emptyResponse();
    }
    final V3Records rec = v3.records();
    final List<OptionChainData.OptionData> data =
        rec.data() != null
            ? rec.data().stream().map(NseOptionChainClient::toOptionData).toList()
            : Collections.emptyList();

    final V3Filtered vf = v3.filtered();
    final OptionChainData.Filtered filtered =
        vf != null
            ? new OptionChainData.Filtered(
                vf.data() != null
                    ? vf.data().stream().map(NseOptionChainClient::toOptionData).toList()
                    : Collections.emptyList(),
                toSummary(vf.ce()),
                toSummary(vf.pe()),
                toBigDecimals(vf.strikePrices()))
            : new OptionChainData.Filtered(
                Collections.emptyList(), null, null, Collections.emptyList());

    return new OptionChainData(
        new OptionChainData.Records(
            rec.expiryDates(),
            data,
            rec.timestamp(),
            rec.underlyingValue(),
            toBigDecimals(rec.strikePrices())),
        filtered);
  }

  private static List<BigDecimal> toBigDecimals(final List<String> values) {
    if (values == null) return Collections.emptyList();
    final List<BigDecimal> result = new ArrayList<>(values.size());
    for (final String s : values) {
      try {
        result.add(new BigDecimal(s.trim()));
      } catch (final NumberFormatException e) {
        result.add(BigDecimal.ZERO);
      }
    }
    return result;
  }

  private static OptionChainData.OptionData toOptionData(final V3OptionData v3d) {
    if (v3d == null) return null;
    return new OptionChainData.OptionData(
        v3d.strikePrice(), v3d.expiryDates(), toContract(v3d.ce()), toContract(v3d.pe()));
  }

  private static OptionChainData.OptionContract toContract(final V3Contract c) {
    if (c == null) return null;
    return new OptionChainData.OptionContract(
        c.strikePrice(),
        c.expiryDate(),
        c.underlying(),
        c.identifier(),
        c.openInterest(),
        c.changeinOpenInterest(),
        c.pchangeinOpenInterest(),
        c.totalTradedVolume(),
        c.impliedVolatility(),
        c.lastPrice(),
        c.change(),
        c.pChange(),
        c.totalBuyQuantity(),
        c.totalSellQuantity(),
        c.buyQuantity1(),
        c.buyPrice1(),
        c.sellQuantity1(),
        c.sellPrice1(),
        c.underlyingValue());
  }

  private static OptionChainData.OptionSummary toSummary(final V3Summary s) {
    if (s == null) return null;
    return new OptionChainData.OptionSummary(
        s.strikePrice(),
        s.openInterest(),
        s.changeinOpenInterest(),
        s.pchangeinOpenInterest(),
        s.totalTradedVolume(),
        s.impliedVolatility(),
        s.lastPrice());
  }

  private static OptionChainData emptyResponse() {
    return new OptionChainData(
        new OptionChainData.Records(
            Collections.emptyList(),
            Collections.emptyList(),
            "",
            BigDecimal.ZERO,
            Collections.emptyList()),
        new OptionChainData.Filtered(Collections.emptyList(), null, null, Collections.emptyList()));
  }

  public IndexQuote fetchIndexQuote() {
    try {
      logger.debug("Fetching NSE index quote from {}", nseConfig.getIndexQuoteUrl());
      return webClient
          .get()
          .uri(nseConfig.getIndexQuoteUrl())
          .header("User-Agent", nseConfig.getUserAgent())
          .header("Accept", MediaType.APPLICATION_JSON_VALUE)
          .header("Accept-Language", "en-US,en;q=0.9")
          .header("Referer", nseConfig.getHomeUrl())
          .header("Cookie", sessionCookie != null ? sessionCookie : "")
          .retrieve()
          .bodyToMono(IndexQuote.class)
          .block();
    } catch (final Exception e) {
      logger.error(
          "Failed to fetch NSE index quote: {} {}", e.getClass().getSimpleName(), e.getMessage());
      return null;
    }
  }

  // ---- V3-specific DTOs (inner records) ----

  @JsonIgnoreProperties(ignoreUnknown = true)
  record V3FullResponse(
      @JsonProperty("records") V3Records records, @JsonProperty("filtered") V3Filtered filtered) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record V3Records(
      @JsonProperty("expiryDates") List<String> expiryDates,
      @JsonProperty("data") List<V3OptionData> data,
      @JsonProperty("timestamp") String timestamp,
      @JsonProperty("underlyingValue") BigDecimal underlyingValue,
      @JsonProperty("strikePrices") List<String> strikePrices) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record V3Filtered(
      @JsonProperty("data") List<V3OptionData> data,
      @JsonProperty("CE") V3Summary ce,
      @JsonProperty("PE") V3Summary pe,
      @JsonProperty("strikePrices") List<String> strikePrices) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record V3OptionData(
      @JsonProperty("strikePrice") BigDecimal strikePrice,
      @JsonProperty("expiryDates") String expiryDates,
      @JsonProperty("CE") V3Contract ce,
      @JsonProperty("PE") V3Contract pe) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record V3Contract(
      @JsonProperty("strikePrice") BigDecimal strikePrice,
      @JsonProperty("expiryDate") String expiryDate,
      @JsonProperty("underlying") String underlying,
      @JsonProperty("identifier") String identifier,
      @JsonProperty("openInterest") BigDecimal openInterest,
      @JsonProperty("changeinOpenInterest") BigDecimal changeinOpenInterest,
      @JsonProperty("pchangeinOpenInterest") BigDecimal pchangeinOpenInterest,
      @JsonProperty("totalTradedVolume") BigDecimal totalTradedVolume,
      @JsonProperty("impliedVolatility") BigDecimal impliedVolatility,
      @JsonProperty("lastPrice") BigDecimal lastPrice,
      @JsonProperty("change") BigDecimal change,
      @JsonProperty("pChange") BigDecimal pChange,
      @JsonProperty("totalBuyQuantity") BigDecimal totalBuyQuantity,
      @JsonProperty("totalSellQuantity") BigDecimal totalSellQuantity,
      @JsonProperty("buyQuantity1") BigDecimal buyQuantity1,
      @JsonProperty("buyPrice1") BigDecimal buyPrice1,
      @JsonProperty("sellQuantity1") BigDecimal sellQuantity1,
      @JsonProperty("sellPrice1") BigDecimal sellPrice1,
      @JsonProperty("underlyingValue") BigDecimal underlyingValue) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record V3Summary(
      @JsonProperty("strikePrice") BigDecimal strikePrice,
      @JsonProperty("openInterest") BigDecimal openInterest,
      @JsonProperty("changeinOpenInterest") BigDecimal changeinOpenInterest,
      @JsonProperty("pchangeinOpenInterest") BigDecimal pchangeinOpenInterest,
      @JsonProperty("totalTradedVolume") BigDecimal totalTradedVolume,
      @JsonProperty("impliedVolatility") BigDecimal impliedVolatility,
      @JsonProperty("lastPrice") BigDecimal lastPrice) {}
}
