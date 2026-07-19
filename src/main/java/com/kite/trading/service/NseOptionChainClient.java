package com.kite.trading.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kite.trading.config.NseConfig;
import com.kite.trading.dto.IndexQuote;
import com.kite.trading.dto.OhlcCandle;
import com.kite.trading.dto.OptionChainData;
import com.kite.trading.dto.StockQuote;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    return fetchOptionChain("NIFTY");
  }

  public OptionChainData fetchOptionChain(final String symbol) {
    ensureSession();
    final String expiry = resolveNearestExpiry(symbol);
    if (expiry == null) {
      logger.warn("Could not resolve any expiry date for {}, returning empty response", symbol);
      return emptyResponse();
    }
    return fetchData(expiry, symbol);
  }

  private synchronized void ensureSession() {
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

  private String resolveNearestExpiry(final String symbol) {
    try {
      final String url = nseConfig.getContractInfoUrl(symbol);
      final String raw =
          webClient
              .get()
              .uri(url)
              .header("User-Agent", nseConfig.getUserAgent())
              .header("Accept", MediaType.APPLICATION_JSON_VALUE)
              .header("Accept-Language", "en-US,en;q=0.9")
              .header("Referer", nseConfig.getHomeUrl())
              .header("Cookie", sessionCookie != null ? sessionCookie : "")
              .retrieve()
              .bodyToMono(String.class)
              .block();
      if (raw == null || raw.isBlank()) {
        logger.warn("NSE contract info response is empty for {}", symbol);
        return null;
      }
      final JsonNode root = objectMapper.readTree(raw);
      final JsonNode expiryDates = root.get("expiryDates");
      if (expiryDates == null || !expiryDates.isArray() || expiryDates.isEmpty()) {
        logger.warn("NSE contract info for {} has no expiryDates", symbol);
        return null;
      }
      final String nearest = expiryDates.get(0).asText();
      logger.debug("Resolved nearest NSE expiry for {}: {}", symbol, nearest);
      return nearest;
    } catch (final Exception e) {
      logger.error(
          "Failed to resolve NSE expiry dates for {}: {} {}",
          symbol,
          e.getClass().getSimpleName(),
          e.getMessage());
    }
    return null;
  }

  private OptionChainData fetchData(final String expiry, final String symbol) {
    try {
      final String url = nseConfig.getOptionChainUrl(symbol) + "&expiry=" + expiry;
      logger.debug("Fetching NSE option chain for {} expiry {}", symbol, expiry);
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
          "Failed to fetch NSE option chain for {}: {} {}",
          symbol,
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

  public IndexQuote fetchIndexQuote(final String symbol) {
    try {
      final String url = nseConfig.getIndexQuoteUrl(symbol);
      logger.debug("Fetching NSE index quote for {} from {}", symbol, url);
      return webClient
          .get()
          .uri(url)
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
          "Failed to fetch NSE index quote for {}: {} {}",
          symbol,
          e.getClass().getSimpleName(),
          e.getMessage());
      return null;
    }
  }

  public IndexQuote fetchIndexQuote() {
    return fetchIndexQuote("NIFTY");
  }

  public StockQuote fetchEquityQuote(final String symbol) {
    ensureSession();
    try {
      final String url = nseConfig.getEquityQuoteUrl(symbol);
      logger.debug("Fetching NSE equity quote for {} from {}", symbol, url);
      return webClient
          .get()
          .uri(url)
          .header("User-Agent", nseConfig.getUserAgent())
          .header("Accept", MediaType.APPLICATION_JSON_VALUE)
          .header("Accept-Language", "en-US,en;q=0.9")
          .header("Referer", nseConfig.getHomeUrl())
          .header("Cookie", sessionCookie != null ? sessionCookie : "")
          .retrieve()
          .bodyToMono(StockQuote.class)
          .block();
    } catch (final Exception e) {
      logger.error(
          "Failed to fetch NSE equity quote for {}: {} {}",
          symbol,
          e.getClass().getSimpleName(),
          e.getMessage());
      return null;
    }
  }

  private OptionChainData fetchEquityData(final String expiry, final String symbol) {
    try {
      final String url = nseConfig.getEquityOptionChainUrl(symbol) + "&expiry=" + expiry;
      logger.debug("Fetching NSE equity option chain for {} expiry {}", symbol, expiry);
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
          "Failed to fetch equity option chain for {}: {} {}",
          symbol,
          e.getClass().getSimpleName(),
          e.getMessage());
      sessionCookie = null;
      return emptyResponse();
    }
  }

  public Map<String, BhavCopyEntry> fetchBhavCopyData(final LocalDate date) {
    ensureSession();
    final String dateStr = date.format(DateTimeFormatter.ofPattern("ddMMyyyy"));
    final String url = nseConfig.getBhavCopyUrl(dateStr);
    logger.debug("Fetching NSE bhavcopy for {} from {}", date, url);
    try {
      final String csv =
          webClient
              .get()
              .uri(url)
              .header("User-Agent", nseConfig.getUserAgent())
              .header("Accept", "text/csv, */*")
              .header("Accept-Language", "en-US,en;q=0.9")
              .header("Referer", nseConfig.getHomeUrl())
              .header("Cookie", sessionCookie != null ? sessionCookie : "")
              .retrieve()
              .bodyToMono(String.class)
              .block();
      if (csv == null || csv.isBlank()) {
        logger.warn("Bhavcopy response empty for {}", dateStr);
        return Map.of();
      }
      return parseBhavCopy(csv);
    } catch (final Exception e) {
      logger.error(
          "Failed to fetch bhavcopy for {}: {} {}",
          dateStr,
          e.getClass().getSimpleName(),
          e.getMessage());
      return Map.of();
    }
  }

  private static Map<String, BhavCopyEntry> parseBhavCopy(final String csv) {
    final Map<String, BhavCopyEntry> result = new HashMap<>();
    final String[] lines = csv.split("\\r?\\n");
    if (lines.length < 2) {
      return result;
    }
    for (int i = 1; i < lines.length; i++) {
      final String line = lines[i].trim();
      if (line.isEmpty()) continue;
      final String[] cols = line.split(",");
      if (cols.length < 7) continue;
      final String symbol = cols[0].trim();
      final String series = cols[1].trim();
      if (!"EQ".equals(series) && !"BE".equals(series)) continue;
      try {
        final BigDecimal high = new BigDecimal(cols[5].trim());
        final BigDecimal low = new BigDecimal(cols[6].trim());
        result.put(symbol, new BhavCopyEntry(high, low));
      } catch (final NumberFormatException e) {
        logger.debug("Skipping bhavcopy row with invalid numbers: {}", line);
      }
    }
    logger.debug("Parsed {} bhavcopy entries", result.size());
    return result;
  }

  public record BhavCopyEntry(BigDecimal high, BigDecimal low) {}

  /**
   * Fetches historical index OHLC candles (e.g. NIFTY 5m/15m) used to warm up RSI/SMA at the start
   * of a trading day so that the 15m RSI(14) is valid from the first bar.
   *
   * <p>Primary source is the local nse-ohlc-api service (yfinance/TradingView). On failure it falls
   * back to Yahoo Finance's public chart endpoint. Index volume is not reported by Yahoo and is
   * left as zero.
   *
   * @param interval candle interval, e.g. "5m" or "15m"
   * @param count number of recent candles to request
   * @return list of OHLC candles (oldest first); empty on failure
   */
  public List<OhlcCandle> fetchIndexCandles(final String interval, final int count) {
    final List<OhlcCandle> fromApi = fetchIndexCandlesFromOhlcApi(interval, count);
    if (!fromApi.isEmpty()) {
      return fromApi;
    }
    logger.warn("OHLC API unavailable for {} {}, falling back to Yahoo direct", interval, count);
    return fetchIndexCandlesFromYahoo(interval, count);
  }

  private List<OhlcCandle> fetchIndexCandlesFromOhlcApi(final String interval, final int count) {
    try {
      final String url = nseConfig.getOhlcApiBaseUrl() + "/api/ohlc/auto";
      final java.util.Map<String, Object> body = new java.util.LinkedHashMap<>();
      body.put("symbol", nseConfig.getOhlcApiSymbol());
      body.put("interval", interval);
      body.put("count", count);
      final String json =
          webClient
              .post()
              .uri(url)
              .contentType(MediaType.APPLICATION_JSON)
              .accept(MediaType.APPLICATION_JSON)
              .header("User-Agent", nseConfig.getUserAgent())
              .bodyValue(body)
              .retrieve()
              .bodyToMono(String.class)
              .block(Duration.ofMillis(nseConfig.getOhlcApiTimeoutMs()));
      if (json == null || json.isBlank()) {
        return List.of();
      }
      final OhlcApiResponse response = objectMapper.readValue(json, OhlcApiResponse.class);
      return mapOhlcApiData(response);
    } catch (final Exception e) {
      logger.warn(
          "Failed to fetch candles from OHLC API ({} {}): {} {}",
          interval,
          count,
          e.getClass().getSimpleName(),
          e.getMessage());
      return List.of();
    }
  }

  private List<OhlcCandle> fetchIndexCandlesFromYahoo(final String interval, final int count) {
    try {
      final String symbol = nseConfig.getOhlcApiSymbol().replace("^", "%5E");
      final String url =
          "https://query1.finance.yahoo.com/v8/finance/chart/"
              + symbol
              + "?interval="
              + interval
              + "&range=5d";
      final String json =
          webClient
              .get()
              .uri(url)
              .header("User-Agent", nseConfig.getUserAgent())
              .accept(MediaType.APPLICATION_JSON)
              .retrieve()
              .bodyToMono(String.class)
              .block(Duration.ofMillis(nseConfig.getOhlcApiTimeoutMs()));
      if (json == null || json.isBlank()) {
        return List.of();
      }
      final YahooChartResponse response = objectMapper.readValue(json, YahooChartResponse.class);
      return mapYahooData(response);
    } catch (final Exception e) {
      logger.warn(
          "Failed to fetch candles from Yahoo ({} {}): {} {}",
          interval,
          count,
          e.getClass().getSimpleName(),
          e.getMessage());
      return List.of();
    }
  }

  private static List<OhlcCandle> mapOhlcApiData(final OhlcApiResponse response) {
    final List<OhlcCandle> result = new ArrayList<>();
    if (response == null
        || response.data() == null
        || response.data().data() == null
        || response.data().data().isEmpty()) {
      return result;
    }
    for (final OhlcApiBar bar : response.data().data()) {
      if (bar == null || bar.open() == null || bar.close() == null) {
        continue;
      }
      final BigDecimal high = bar.high() != null ? bar.high() : bar.open().max(bar.close());
      final BigDecimal low = bar.low() != null ? bar.low() : bar.open().min(bar.close());
      if (high.compareTo(low) == 0) {
        continue;
      }
      result.add(new OhlcCandle(bar.datetime(), bar.open(), high, low, bar.close()));
    }
    return result;
  }

  private static List<OhlcCandle> mapYahooData(final YahooChartResponse response) {
    final List<OhlcCandle> result = new ArrayList<>();
    if (response == null
        || response.chart() == null
        || response.chart().result() == null
        || response.chart().result().isEmpty()) {
      return result;
    }
    final YahooChartResult res = response.chart().result().getFirst();
    if (res.timestamp() == null || res.indicators() == null || res.indicators().quote() == null) {
      return result;
    }
    final List<Long> ts = res.timestamp();
    final YahooQuote q = res.indicators().quote().getFirst();
    final int n = Math.min(Math.min(ts.size(), q.open().size()), q.close().size());
    for (int i = 0; i < n; i++) {
      final BigDecimal open = q.open().get(i);
      final BigDecimal close = q.close().get(i);
      if (open == null || close == null) {
        continue;
      }
      final BigDecimal high = q.high().get(i) != null ? q.high().get(i) : open.max(close);
      final BigDecimal low = q.low().get(i) != null ? q.low().get(i) : open.min(close);
      if (high.compareTo(low) == 0) {
        continue;
      }
      final LocalDateTime dt =
          LocalDateTime.ofEpochSecond(ts.get(i), 0, java.time.ZoneOffset.ofHoursMinutes(5, 30));
      result.add(new OhlcCandle(dt, open, high, low, close));
    }
    return result;
  }

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record PreOpenResponse(
      @JsonProperty("advances") int advances,
      @JsonProperty("declines") int declines,
      @JsonProperty("unchanged") int unchanged,
      @JsonProperty("data") List<PreOpenItem> data) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record PreOpenItem(@JsonProperty("metadata") PreOpenMetadata metadata) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  public record PreOpenMetadata(
      @JsonProperty("symbol") String symbol,
      @JsonProperty("lastPrice") BigDecimal lastPrice,
      @JsonProperty("change") BigDecimal change,
      @JsonProperty("pChange") BigDecimal pChange,
      @JsonProperty("finalQuantity") long finalQuantity,
      @JsonProperty("totalTurnover") BigDecimal totalTurnover,
      @JsonProperty("iep") BigDecimal iep) {}

  public PreOpenResponse fetchPreOpenData(final String key) {
    ensureSession();
    final String url = nseConfig.getPreOpenUrl(key);
    logger.debug("Fetching pre-open market data for key={}", key);
    try {
      final String json =
          webClient
              .get()
              .uri(url)
              .header("User-Agent", nseConfig.getUserAgent())
              .header("Accept", MediaType.APPLICATION_JSON_VALUE)
              .header("Accept-Language", "en-US,en;q=0.9")
              .header("Referer", nseConfig.getHomeUrl())
              .header("Cookie", sessionCookie != null ? sessionCookie : "")
              .retrieve()
              .bodyToMono(String.class)
              .block();
      if (json == null || json.isBlank()) return null;
      return objectMapper.readValue(json, PreOpenResponse.class);
    } catch (final Exception e) {
      logger.error(
          "Failed to fetch pre-open data: {} {}", e.getClass().getSimpleName(), e.getMessage());
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

  // ---- OHLC API (nse-ohlc-api) response DTOs ----

  @JsonIgnoreProperties(ignoreUnknown = true)
  record OhlcApiResponse(@JsonProperty("data") OhlcApiData data) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record OhlcApiData(@JsonProperty("data") List<OhlcApiBar> data) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record OhlcApiBar(
      @JsonProperty("datetime") LocalDateTime datetime,
      @JsonProperty("open") BigDecimal open,
      @JsonProperty("high") BigDecimal high,
      @JsonProperty("low") BigDecimal low,
      @JsonProperty("close") BigDecimal close,
      @JsonProperty("volume") BigDecimal volume) {}

  // ---- Yahoo Finance chart (fallback) response DTOs ----

  @JsonIgnoreProperties(ignoreUnknown = true)
  record YahooChartResponse(@JsonProperty("chart") YahooChart chart) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record YahooChart(@JsonProperty("result") List<YahooChartResult> result) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record YahooChartResult(
      @JsonProperty("timestamp") List<Long> timestamp,
      @JsonProperty("indicators") YahooIndicators indicators) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record YahooIndicators(@JsonProperty("quote") List<YahooQuote> quote) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  record YahooQuote(
      @JsonProperty("open") List<BigDecimal> open,
      @JsonProperty("high") List<BigDecimal> high,
      @JsonProperty("low") List<BigDecimal> low,
      @JsonProperty("close") List<BigDecimal> close) {}
}
