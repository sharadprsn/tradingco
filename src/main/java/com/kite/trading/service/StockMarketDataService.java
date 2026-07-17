package com.kite.trading.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kite.trading.config.NseConfig;
import com.kite.trading.dto.MarketBreadth;
import com.kite.trading.dto.StockCandle;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Service
public class StockMarketDataService {

  private static final Logger logger = LoggerFactory.getLogger(StockMarketDataService.class);
  private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
  private static final int CANDLE_INTERVAL_MINUTES = 5;

  private final WebClient webClient;
  private final NseConfig nseConfig;
  private final ObjectMapper objectMapper;

  private volatile String sessionCookie;
  private final Map<String, CandleBuilder> activeCandles = new ConcurrentHashMap<>();
  private final Map<String, List<StockCandle>> completedCandles = new ConcurrentHashMap<>();
  private MarketBreadth latestBreadth;

  private final Map<String, DailyRange> currentDayRange = new ConcurrentHashMap<>();
  private final Map<String, DailyRange> previousDayRange = new ConcurrentHashMap<>();
  private static final Path RANGE_FILE = Paths.get("./data/daily_ranges.json");
  private boolean rangesLoaded = false;

  public StockMarketDataService(final WebClient webClient, final NseConfig nseConfig) {
    this.webClient = webClient;
    this.nseConfig = nseConfig;
    this.objectMapper = new ObjectMapper();
  }

  public synchronized void ensureSession() {
    if (sessionCookie != null) {
      return;
    }
    logger.info("Establishing NSE session for stock data");
    try {
      final String homeUrl = "https://www.nseindia.com";
      final boolean success =
          Boolean.TRUE.equals(
              webClient
                  .get()
                  .uri(homeUrl)
                  .header("User-Agent", nseConfig.getUserAgent())
                  .header(
                      "Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                  .header("Accept-Language", "en-US,en;q=0.9")
                  .exchangeToMono(
                      resp -> {
                        if (!resp.statusCode().is2xxSuccessful()) {
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
                          logger.debug("NSE session cookie captured");
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
        logger.info("NSE session established for stock data");
      } else {
        logger.warn("Failed to establish NSE session");
      }
    } catch (final Exception e) {
      logger.error("Error establishing NSE session: {}", e.getMessage());
    }
  }

  public MarketBreadth fetchMarketBreadth() {
    try {
      ensureSession();
      final String url = "https://www.nseindia.com/api/equity-stockIndices?index=NIFTY%2050";
      final String raw =
          webClient
              .get()
              .uri(url)
              .header("User-Agent", nseConfig.getUserAgent())
              .header("Accept", MediaType.APPLICATION_JSON_VALUE)
              .header("Accept-Language", "en-US,en;q=0.9")
              .header("Referer", "https://www.nseindia.com")
              .header("Cookie", sessionCookie != null ? sessionCookie : "")
              .retrieve()
              .bodyToMono(String.class)
              .block();
      if (raw == null || raw.isBlank()) {
        logger.warn("Empty market breadth response");
        return latestBreadth;
      }
      final JsonNode root = objectMapper.readTree(raw);
      final JsonNode advance = root.get("advance");
      if (advance == null) {
        logger.warn("No advance data in market breadth response");
        return latestBreadth;
      }
      final int advances = Integer.parseInt(advance.get("advances").asText("0"));
      final int declines = Integer.parseInt(advance.get("declines").asText("0"));
      final int unchanged = Integer.parseInt(advance.get("unchanged").asText("0"));
      latestBreadth = new MarketBreadth(advances, declines, unchanged, LocalDateTime.now(IST));
      logger.info(
          "Market breadth - Advances: {}, Declines: {}, Unchanged: {}",
          advances,
          declines,
          unchanged);
      return latestBreadth;
    } catch (final Exception e) {
      logger.error("Failed to fetch market breadth: {}", e.getMessage());
      return latestBreadth;
    }
  }

  public void updateQuote(final String symbol) {
    try {
      ensureSession();
      final String url = "https://www.nseindia.com/api/quote-equity?symbol=" + symbol;
      final String raw =
          webClient
              .get()
              .uri(url)
              .header("User-Agent", nseConfig.getUserAgent())
              .header("Accept", MediaType.APPLICATION_JSON_VALUE)
              .header("Accept-Language", "en-US,en;q=0.9")
              .header("Referer", "https://www.nseindia.com")
              .header("Cookie", sessionCookie != null ? sessionCookie : "")
              .retrieve()
              .bodyToMono(String.class)
              .block();
      if (raw == null || raw.isBlank()) {
        logger.warn("Empty quote response for {}", symbol);
        return;
      }
      final JsonNode root = objectMapper.readTree(raw);
      final JsonNode priceInfo = root.get("priceInfo");
      if (priceInfo == null) {
        logger.warn("No priceInfo in quote response for {}", symbol);
        return;
      }
      final BigDecimal lastPrice = new BigDecimal(priceInfo.get("lastPrice").asText("0"));
      final long volume = priceInfo.get("totalTradedVolume").asLong(0);
      updateCandle(symbol, lastPrice, volume);
      updateDailyRange(symbol, lastPrice);
    } catch (final Exception e) {
      logger.error("Failed to fetch quote for {}: {}", symbol, e.getMessage());
      sessionCookie = null;
    }
  }

  private synchronized void updateCandle(
      final String symbol, final BigDecimal price, final long totalVolume) {
    final LocalTime now = LocalTime.now(IST);
    final LocalDateTime nowDt = LocalDateTime.now(IST);
    final int periodStartMinute =
        (now.getHour() * 60 + now.getMinute()) / CANDLE_INTERVAL_MINUTES * CANDLE_INTERVAL_MINUTES;
    final LocalTime periodStart = LocalTime.of(periodStartMinute / 60, periodStartMinute % 60);
    final LocalDateTime periodStartDt = LocalDateTime.of(LocalDate.now(IST), periodStart);

    activeCandles.compute(
        symbol,
        (key, builder) -> {
          if (builder == null || !builder.periodStart.equals(periodStartDt)) {
            if (builder != null && builder.hasData()) {
              final StockCandle completed = builder.build(nowDt);
              completedCandles.computeIfAbsent(symbol, k -> new ArrayList<>()).add(completed);
              logger.debug(
                  "Completed 5-min candle for {}: O={} H={} L={} C={} V={}",
                  symbol,
                  completed.open(),
                  completed.high(),
                  completed.low(),
                  completed.close(),
                  completed.volume());
            }
            return new CandleBuilder(symbol, periodStartDt, price, totalVolume);
          }
          builder.update(price, totalVolume);
          return builder;
        });
  }

  public List<StockCandle> getCompletedCandles(final String symbol) {
    final List<StockCandle> candles =
        completedCandles.getOrDefault(symbol, Collections.emptyList());
    final List<StockCandle> result = new ArrayList<>(candles);
    result.sort(Comparator.comparing(StockCandle::timestamp));
    return result;
  }

  public List<StockCandle> getDayCandles(final String symbol) {
    final List<StockCandle> result = getCompletedCandles(symbol);
    final CandleBuilder active = activeCandles.get(symbol);
    if (active != null && active.hasData()) {
      result.add(active.build(LocalDateTime.now(IST)));
    }
    return result;
  }

  public BigDecimal getLastPrice(final String symbol) {
    final CandleBuilder builder = activeCandles.get(symbol);
    return builder != null ? builder.lastPrice : null;
  }

  public StockCandle getLatestCompletedCandle(final String symbol) {
    final List<StockCandle> candles = completedCandles.get(symbol);
    if (candles == null || candles.isEmpty()) {
      return null;
    }
    return candles.getLast();
  }

  public StockCandle getLowestVolumeCandle(final String symbol) {
    final List<StockCandle> candles = completedCandles.get(symbol);
    if (candles == null || candles.isEmpty()) {
      return null;
    }
    return candles.stream()
        .filter(c -> c.volume() > 0)
        .min(Comparator.comparingLong(StockCandle::volume))
        .orElse(null);
  }

  public MarketBreadth getLatestBreadth() {
    return latestBreadth;
  }

  public synchronized void loadPreviousRanges() {
    if (rangesLoaded) {
      return;
    }
    rangesLoaded = true;
    try {
      if (Files.exists(RANGE_FILE)) {
        final String json = Files.readString(RANGE_FILE);
        final Map<String, Map<String, Object>> raw =
            objectMapper.readValue(json, new TypeReference<Map<String, Map<String, Object>>>() {});
        for (final var entry : raw.entrySet()) {
          final String symbol = entry.getKey();
          final Map<String, Object> range = entry.getValue();
          final BigDecimal high = new BigDecimal(range.get("high").toString());
          final BigDecimal low = new BigDecimal(range.get("low").toString());
          final String dateStr = (String) range.get("date");
          previousDayRange.put(symbol, new DailyRange(high, low, LocalDate.parse(dateStr)));
        }
        logger.info("Loaded {} previous day ranges from file", previousDayRange.size());
      } else {
        logger.info("No previous day ranges file found, skipping PDH/PDL filter");
      }
    } catch (final Exception e) {
      logger.warn("Failed to load previous day ranges: {}", e.getMessage());
    }
  }

  public synchronized void saveCurrentRanges() {
    try {
      final Map<String, Map<String, Object>> raw = new HashMap<>();
      for (final var entry : currentDayRange.entrySet()) {
        final DailyRange range = entry.getValue();
        final Map<String, Object> r = new HashMap<>();
        r.put("high", range.high());
        r.put("low", range.low());
        r.put("date", range.date().toString());
        raw.put(entry.getKey(), r);
      }
      Files.createDirectories(RANGE_FILE.getParent());
      objectMapper.writeValue(RANGE_FILE.toFile(), raw);
      logger.info("Saved {} daily ranges to file", raw.size());
    } catch (final Exception e) {
      logger.warn("Failed to save daily ranges: {}", e.getMessage());
    }
  }

  public DailyRange getPreviousDayRange(final String symbol) {
    if (!rangesLoaded) {
      loadPreviousRanges();
    }
    return previousDayRange.get(symbol);
  }

  public void updateDailyRange(final String symbol, final BigDecimal price) {
    currentDayRange.compute(
        symbol,
        (key, existing) -> {
          if (existing == null) {
            return new DailyRange(price, price, LocalDate.now(IST));
          }
          final BigDecimal newHigh = price.compareTo(existing.high()) > 0 ? price : existing.high();
          final BigDecimal newLow = price.compareTo(existing.low()) < 0 ? price : existing.low();
          return new DailyRange(newHigh, newLow, existing.date());
        });
  }

  public synchronized void reset() {
    previousDayRange.clear();
    currentDayRange.clear();
    activeCandles.clear();
    completedCandles.clear();
    latestBreadth = null;
    rangesLoaded = false;
    logger.info("Stock market data reset for new day");
  }

  public record DailyRange(BigDecimal high, BigDecimal low, LocalDate date) {}

  private static class CandleBuilder {
    final String symbol;
    final LocalDateTime periodStart;
    BigDecimal open;
    BigDecimal high;
    BigDecimal low;
    BigDecimal lastPrice;
    long startVolume;
    long lastVolume;
    boolean hasData;

    CandleBuilder(
        final String symbol,
        final LocalDateTime periodStart,
        final BigDecimal price,
        final long totalVolume) {
      this.symbol = symbol;
      this.periodStart = periodStart;
      this.open = price;
      this.high = price;
      this.low = price;
      this.lastPrice = price;
      this.startVolume = totalVolume;
      this.lastVolume = totalVolume;
      this.hasData = true;
    }

    void update(final BigDecimal price, final long totalVolume) {
      this.lastPrice = price;
      if (price.compareTo(high) > 0) {
        this.high = price;
      }
      if (price.compareTo(low) < 0) {
        this.low = price;
      }
      this.lastVolume = totalVolume;
      this.hasData = true;
    }

    boolean hasData() {
      return hasData;
    }

    StockCandle build(final LocalDateTime now) {
      return new StockCandle(
          symbol,
          periodStart,
          open,
          high,
          low,
          lastPrice != null ? lastPrice : open,
          lastVolume - startVolume);
    }
  }
}
