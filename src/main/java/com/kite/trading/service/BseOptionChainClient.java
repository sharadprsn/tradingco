package com.kite.trading.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kite.trading.dto.IndexQuote;
import com.kite.trading.dto.OptionChainData;
import com.kite.trading.dto.OptionChainData.OptionContract;
import com.kite.trading.dto.OptionChainData.OptionData;
import com.kite.trading.dto.OptionChainData.Records;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

@Component
public class BseOptionChainClient implements OptionChainClient {

  private static final Logger logger = LoggerFactory.getLogger(BseOptionChainClient.class);

  private static final String BSE_API_BASE = "https://api.bseindia.com/BseIndiaAPI/api";
  private static final String BSE_HOME = "https://www.bseindia.com/";
  private static final String SCRIP_CODE_SENSEX = "1";

  private static final DateTimeFormatter BSE_EXPIRY_FMT =
      DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);
  private static final DateTimeFormatter NSE_EXPIRY_FMT =
      DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);

  private static final String USER_AGENT =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36";

  private final WebClient webClient;
  private final ObjectMapper objectMapper;

  private volatile String sessionCookie;

  private final Map<String, BigDecimal> previousOi = new ConcurrentHashMap<>();

  public BseOptionChainClient(final WebClient webClient) {
    this.webClient = webClient;
    this.objectMapper = new ObjectMapper();
  }

  @Override
  public OptionChainData fetchOptionChain() {
    return fetchOptionChain("SENSEX");
  }

  @Override
  public OptionChainData fetchOptionChain(final String symbol) {
    if (!"SENSEX".equals(symbol)) {
      logger.warn("BSE client only supports SENSEX, got {}", symbol);
      return null;
    }
    ensureSession();
    final String expiry = resolveNearestExpiry();
    if (expiry == null) {
      logger.warn("BSE: could not resolve expiry for SENSEX");
      return null;
    }
    return fetchChain(expiry);
  }

  private void ensureSession() {
    if (sessionCookie == null) {
      logger.info("Establishing BSE session via {}", BSE_HOME);
      final boolean success =
          Boolean.TRUE.equals(
              webClient
                  .get()
                  .uri(BSE_HOME)
                  .header("User-Agent", USER_AGENT)
                  .header(
                      "Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                  .header("Accept-Language", "en-US,en;q=0.9")
                  .exchangeToMono(
                      resp -> {
                        if (!resp.statusCode().is2xxSuccessful()) {
                          logger.warn("BSE home page returned {}", resp.statusCode());
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
                          logger.debug("BSE session cookie captured");
                        } else {
                          logger.warn("No Set-Cookie header in BSE response");
                        }
                        return resp.bodyToMono(String.class).thenReturn(true);
                      })
                  .onErrorResume(
                      e -> {
                        logger.error("Failed to establish BSE session: {}", e.getMessage());
                        return Mono.just(false);
                      })
                  .block());
      if (Boolean.TRUE.equals(success)) {
        logger.info("BSE session established");
      }
    }
  }

  private String resolveNearestExpiry() {
    try {
      final String url = BSE_API_BASE + "/ddlExpiry_New/w?scrip_cd=" + SCRIP_CODE_SENSEX;
      final String raw =
          webClient
              .get()
              .uri(url)
              .header("User-Agent", USER_AGENT)
              .header("Accept", MediaType.APPLICATION_JSON_VALUE)
              .header("Accept-Language", "en-US,en;q=0.9")
              .header("Referer", BSE_HOME)
              .header("Cookie", sessionCookie != null ? sessionCookie : "")
              .retrieve()
              .bodyToMono(String.class)
              .block();
      if (raw == null || raw.isBlank()) {
        logger.warn("BSE expiry response is empty");
        return null;
      }
      final JsonNode root = objectMapper.readTree(raw);
      final JsonNode table1 = root.get("Table1");
      if (table1 == null || !table1.isArray() || table1.isEmpty()) {
        logger.warn("BSE expiry Table1 is empty");
        return null;
      }
      final String nearest = table1.get(0).get("ExpiryDate").asText();
      logger.debug("BSE resolved nearest expiry: {}", nearest);
      return nearest;
    } catch (final Exception e) {
      logger.error(
          "Failed to resolve BSE expiry: {} {}", e.getClass().getSimpleName(), e.getMessage());
      return null;
    }
  }

  private OptionChainData fetchChain(final String expiry) {
    try {
      final String raw =
          webClient
              .get()
              .uri(
                  uriBuilder ->
                      uriBuilder
                          .scheme("https")
                          .host("api.bseindia.com")
                          .path("/BseIndiaAPI/api/DerivOptionChain_IV/w")
                          .queryParam("scrip_cd", SCRIP_CODE_SENSEX)
                          .queryParam("Expiry", expiry)
                          .queryParam("strprice", "0")
                          .build())
              .header("User-Agent", USER_AGENT)
              .header("Accept", MediaType.APPLICATION_JSON_VALUE)
              .header("Accept-Language", "en-US,en;q=0.9")
              .header("Referer", BSE_HOME)
              .header("Cookie", sessionCookie != null ? sessionCookie : "")
              .retrieve()
              .bodyToMono(String.class)
              .block();
      if (raw == null || raw.isBlank()) {
        logger.warn("BSE option chain response is empty for expiry {}", expiry);
        return emptyResponse();
      }
      final JsonNode root = objectMapper.readTree(raw);
      return convert(root, expiry);
    } catch (final Exception e) {
      logger.error(
          "Failed to fetch BSE option chain: {} {}", e.getClass().getSimpleName(), e.getMessage());
      sessionCookie = null;
      return emptyResponse();
    }
  }

  private OptionChainData convert(final JsonNode root, final String expiry) {
    final JsonNode table = root.get("Table");
    if (table == null || !table.isArray()) {
      return emptyResponse();
    }

    final LocalDate parsedExpiry = LocalDate.parse(expiry, BSE_EXPIRY_FMT);
    final String nseExpiry = parsedExpiry.format(NSE_EXPIRY_FMT);

    BigDecimal underlyingValue = BigDecimal.ZERO;
    if (table.size() > 0) {
      final JsonNode first = table.get(0);
      if (first.has("UlaValue")) {
        final String uv = first.get("UlaValue").asText();
        if (!uv.isBlank()) {
          underlyingValue = new BigDecimal(uv);
        }
      }
    }

    final List<BigDecimal> strikePrices = new ArrayList<>();
    final List<OptionData> dataList = new ArrayList<>();

    for (final JsonNode row : table) {
      final String strikeFormatted =
          row.has("Strike_Price") ? row.get("Strike_Price").asText() : "";
      final String strikeClean = row.has("Strike_Price1") ? row.get("Strike_Price1").asText() : "";
      final BigDecimal strike;
      try {
        strike =
            new BigDecimal(strikeClean.isEmpty() ? strikeFormatted.replace(",", "") : strikeClean);
      } catch (final NumberFormatException e) {
        continue;
      }
      strikePrices.add(strike);

      final OptionContract ce =
          buildContract(row, strike, nseExpiry, "SENSEX", underlyingValue, true);
      final OptionContract pe =
          buildContract(row, strike, nseExpiry, "SENSEX", underlyingValue, false);

      dataList.add(new OptionData(strike, nseExpiry, ce, pe));
    }

    return new OptionChainData(
        new Records(List.of(nseExpiry), dataList, null, underlyingValue, strikePrices), null);
  }

  private OptionContract buildContract(
      final JsonNode row,
      final BigDecimal strike,
      final String expiry,
      final String symbol,
      final BigDecimal underlying,
      final boolean isCall) {
    final String prefix = isCall ? "C_" : "";

    final String ltpStr = getText(row, prefix + "Last_Trd_Price");
    final String changeStr = getText(row, prefix + "NetChange");
    final String oiStr = getText(row, prefix + "Open_Interest");
    final String oiChangeStr = getText(row, prefix + "Absolute_Change_OI");
    final String volumeStr = getText(row, prefix + "Vol_Traded");
    final String bidQtyStr = getText(row, prefix + "BIdQty");
    final String bidPriceStr = getText(row, prefix + "BidPrice");
    final String askQtyStr = getText(row, prefix + "OfferQty");
    final String askPriceStr = getText(row, prefix + "OfferPrice");
    final String ivStr = getText(row, prefix + "IV");
    final String seriesCode = getText(row, prefix + "Series_Code");
    final String seriesId = getText(row, prefix + "Series_Id");

    final BigDecimal lastPrice = parseBigDecimal(ltpStr);
    final BigDecimal change = parseBigDecimal(changeStr);
    final BigDecimal pChange =
        lastPrice.compareTo(BigDecimal.ZERO) > 0 && change.compareTo(BigDecimal.ZERO) != 0
            ? change
                .multiply(BigDecimal.valueOf(100))
                .divide(lastPrice.subtract(change), 2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;

    final BigDecimal oi = parseBigDecimal(oiStr);
    final BigDecimal oiChange = parseBigDecimal(oiChangeStr);

    final BigDecimal oiChangePct =
        oiChange.compareTo(BigDecimal.ZERO) != 0 && oi.compareTo(BigDecimal.ZERO) > 0
            ? oiChange
                .multiply(BigDecimal.valueOf(100))
                .divide(oi.subtract(oiChange), 2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;

    final String optionType = isCall ? "CE" : "PE";
    final String identifier =
        seriesCode.isBlank() ? (symbol + expiry + strike + optionType) : seriesCode;

    return new OptionContract(
        strike,
        expiry,
        symbol,
        identifier,
        oi,
        oiChange,
        oiChangePct,
        parseBigDecimal(volumeStr),
        parseBigDecimal(ivStr),
        lastPrice,
        change,
        pChange,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        parseBigDecimal(bidQtyStr),
        parseBigDecimal(bidPriceStr),
        parseBigDecimal(askQtyStr),
        parseBigDecimal(askPriceStr),
        underlying);
  }

  @Override
  public IndexQuote fetchIndexQuote(final String symbol) {
    if (!"SENSEX".equals(symbol)) {
      return null;
    }
    try {
      final String raw =
          webClient
              .get()
              .uri("https://api.bseindia.com/RealTimeBseIndiaAPI/api/GetSensexData/w")
              .header("User-Agent", USER_AGENT)
              .header("Accept", MediaType.APPLICATION_JSON_VALUE)
              .header("Referer", BSE_HOME)
              .retrieve()
              .bodyToMono(String.class)
              .block();
      if (raw == null || raw.isBlank()) {
        return null;
      }
      final JsonNode root = objectMapper.readTree(raw);
      final JsonNode table = root.get("Table");
      if (table == null || !table.isArray() || table.isEmpty()) {
        return null;
      }
      final JsonNode row = table.get(0);
      return new IndexQuote(
          List.of(
              new IndexQuote.IndexData(
                  "SENSEX",
                  parseBigDecimal(getText(row, "Open")),
                  parseBigDecimal(getText(row, "High")),
                  parseBigDecimal(getText(row, "Low")),
                  parseBigDecimal(getText(row, "LTP")),
                  parseBigDecimal(getText(row, "PreviousClose")))));
    } catch (final Exception e) {
      logger.error(
          "Failed to fetch BSE index quote: {} {}", e.getClass().getSimpleName(), e.getMessage());
      return null;
    }
  }

  @Override
  public IndexQuote fetchIndexQuote() {
    return fetchIndexQuote("SENSEX");
  }

  private static String getText(final JsonNode node, final String field) {
    final JsonNode f = node.get(field);
    return f == null || f.isNull() ? "" : f.asText().trim();
  }

  private static BigDecimal parseBigDecimal(final String str) {
    if (str == null || str.isBlank()) {
      return BigDecimal.ZERO;
    }
    try {
      return new BigDecimal(str.replace(",", ""));
    } catch (final NumberFormatException e) {
      return BigDecimal.ZERO;
    }
  }

  private static OptionChainData emptyResponse() {
    return new OptionChainData(
        new Records(
            Collections.emptyList(),
            Collections.emptyList(),
            "",
            BigDecimal.ZERO,
            Collections.emptyList()),
        null);
  }
}
