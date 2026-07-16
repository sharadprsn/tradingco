package com.kite.trading.service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.kite.trading.config.OpenAlgoConfig;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Component
public class OpenAlgoOptionChainClient implements OptionChainClient {

  private static final Logger logger = LoggerFactory.getLogger(OpenAlgoOptionChainClient.class);

  private static final DateTimeFormatter OPENALGO_EXPIRY_FMT =
      DateTimeFormatter.ofPattern("dd-MMM-yy", Locale.ENGLISH);
  private static final DateTimeFormatter OPENALGO_REQ_FMT =
      DateTimeFormatter.ofPattern("ddMMMyy", Locale.ENGLISH);
  private static final DateTimeFormatter NSE_EXPIRY_FMT =
      DateTimeFormatter.ofPattern("dd-MMM-yyyy", Locale.ENGLISH);

  private static final Map<String, String> UNDERLYING_EXCHANGE =
      Map.of(
          "NIFTY", "NSE_INDEX",
          "BANKNIFTY", "NSE_INDEX",
          "FINNIFTY", "NSE_INDEX",
          "MIDCPNIFTY", "NSE_INDEX",
          "SENSEX", "BSE_INDEX");

  private static final Map<String, String> UNDERLYING_FNO_EXCHANGE =
      Map.of(
          "NIFTY", "NFO",
          "BANKNIFTY", "NFO",
          "FINNIFTY", "NFO",
          "MIDCPNIFTY", "NFO",
          "SENSEX", "BFO");

  private final WebClient webClient;
  private final OpenAlgoConfig config;

  private final Map<String, BigDecimal> previousOi = new ConcurrentHashMap<>();

  public OpenAlgoOptionChainClient(final WebClient webClient, final OpenAlgoConfig config) {
    this.webClient = webClient;
    this.config = config;
  }

  @Override
  public OptionChainData fetchOptionChain() {
    return fetchOptionChain("NIFTY");
  }

  @Override
  public OptionChainData fetchOptionChain(final String symbol) {
    try {
      final String expiry = fetchNearestExpiry(symbol);
      if (expiry == null) {
        logger.warn("OpenAlgo: no expiry found for {}", symbol);
        return null;
      }
      final String requestExpiry = toRequestFormat(expiry);
      return fetchChain(symbol, requestExpiry);
    } catch (final Exception e) {
      logger.error("OpenAlgo option chain failed for {}: {}", symbol, e.getMessage());
      return null;
    }
  }

  private String fetchNearestExpiry(final String symbol) {
    final String exchange = UNDERLYING_FNO_EXCHANGE.getOrDefault(symbol, "NFO");
    final ExpiryRequest body = new ExpiryRequest(config.getApiKey(), symbol, exchange, "options");
    try {
      final ExpiryResponse response =
          webClient
              .post()
              .uri(config.getBaseUrl() + "/api/v1/expiry")
              .bodyValue(body)
              .retrieve()
              .bodyToMono(ExpiryResponse.class)
              .block();
      if (response == null || response.data() == null || response.data().isEmpty()) {
        logger.warn("OpenAlgo expiry API returned empty for {} (exchange={})", symbol, exchange);
        return null;
      }
      final String nearest = response.data().getFirst();
      logger.debug("OpenAlgo resolved nearest expiry for {}: {}", symbol, nearest);
      return nearest;
    } catch (final Exception e) {
      logger.error("OpenAlgo expiry API failed for {}: {}", symbol, e.getMessage());
      return null;
    }
  }

  private OptionChainData fetchChain(final String symbol, final String expiryDate) {
    final String exchange = UNDERLYING_EXCHANGE.getOrDefault(symbol, "NSE_INDEX");
    final OptionChainRequestBody body =
        new OptionChainRequestBody(config.getApiKey(), symbol, exchange, expiryDate, null);
    try {
      final OptionChainResponse response =
          webClient
              .post()
              .uri(config.getBaseUrl() + "/api/v1/optionchain")
              .bodyValue(body)
              .retrieve()
              .bodyToMono(OptionChainResponse.class)
              .block();
      if (response == null || !"success".equals(response.status()) || response.chain() == null) {
        logger.warn(
            "OpenAlgo option chain API returned unsuccessful for {} (expiry={})",
            symbol,
            expiryDate);
        return null;
      }
      return convert(response, symbol);
    } catch (final Exception e) {
      logger.error(
          "OpenAlgo option chain API failed for {} (expiry={}): {}",
          symbol,
          expiryDate,
          e.getMessage());
      return null;
    }
  }

  private OptionChainData convert(final OptionChainResponse response, final String symbol) {
    if (response.chain() == null || response.chain().isEmpty()) {
      return emptyResponse();
    }

    final String nseExpiry = toNseFormat(response.expiryDate());
    final BigDecimal underlyingValue =
        response.underlyingLtp() != null
            ? BigDecimal.valueOf(response.underlyingLtp())
            : BigDecimal.ZERO;

    final List<BigDecimal> strikePrices = new ArrayList<>();
    final List<OptionData> dataList = new ArrayList<>();

    for (final ChainItem item : response.chain()) {
      final BigDecimal strike = BigDecimal.valueOf(item.strike());
      strikePrices.add(strike);

      final OptionContract ce =
          item.ce() != null
              ? toContract(item.ce(), strike, nseExpiry, symbol, underlyingValue, "CE")
              : null;
      final OptionContract pe =
          item.pe() != null
              ? toContract(item.pe(), strike, nseExpiry, symbol, underlyingValue, "PE")
              : null;

      dataList.add(new OptionData(strike, nseExpiry, ce, pe));
    }

    return new OptionChainData(
        new Records(List.of(nseExpiry), dataList, null, underlyingValue, strikePrices), null);
  }

  private OptionContract toContract(
      final OptionDataDetail opt,
      final BigDecimal strike,
      final String expiry,
      final String symbol,
      final BigDecimal underlying,
      final String optionType) {
    if (opt == null) return null;

    final BigDecimal lastPrice =
        opt.ltp() != null ? BigDecimal.valueOf(opt.ltp()) : BigDecimal.ZERO;
    final BigDecimal prevClose =
        opt.prev_close() != null ? BigDecimal.valueOf(opt.prev_close()) : BigDecimal.ZERO;
    final BigDecimal change = lastPrice.subtract(prevClose);
    final BigDecimal pChange =
        prevClose.compareTo(BigDecimal.ZERO) > 0
            ? change.multiply(BigDecimal.valueOf(100)).divide(prevClose, 2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;

    final BigDecimal oi = opt.oi() != null ? BigDecimal.valueOf(opt.oi()) : BigDecimal.ZERO;
    final BigDecimal oiChange = computeOiChange(strike, expiry, optionType, oi);

    return new OptionContract(
        strike,
        expiry,
        symbol,
        opt.symbol(),
        oi,
        oiChange,
        oiChange.compareTo(BigDecimal.ZERO) != 0 && oi.compareTo(BigDecimal.ZERO) > 0
            ? oiChange
                .multiply(BigDecimal.valueOf(100))
                .divide(oi.subtract(oiChange), 2, RoundingMode.HALF_UP)
            : BigDecimal.ZERO,
        opt.volume() != null ? BigDecimal.valueOf(opt.volume()) : BigDecimal.ZERO,
        BigDecimal.ZERO,
        lastPrice,
        change,
        pChange,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        opt.bid() != null ? BigDecimal.valueOf(opt.bid()) : BigDecimal.ZERO,
        BigDecimal.ZERO,
        opt.ask() != null ? BigDecimal.valueOf(opt.ask()) : BigDecimal.ZERO,
        underlying);
  }

  private BigDecimal computeOiChange(
      final BigDecimal strike,
      final String expiry,
      final String optionType,
      final BigDecimal currentOi) {
    final String key = strike + "|" + expiry + "|" + optionType;
    final BigDecimal prev = previousOi.get(key);
    previousOi.put(key, currentOi);
    if (prev == null) {
      return BigDecimal.ZERO;
    }
    return currentOi.subtract(prev);
  }

  private static String toRequestFormat(final String expiry) {
    return LocalDate.parse(expiry, OPENALGO_EXPIRY_FMT).format(OPENALGO_REQ_FMT);
  }

  private static String toNseFormat(final String expiry) {
    return LocalDate.parse(expiry, OPENALGO_REQ_FMT).format(NSE_EXPIRY_FMT);
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

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record ExpiryRequest(
      @JsonProperty("apikey") String apikey,
      @JsonProperty("symbol") String symbol,
      @JsonProperty("exchange") String exchange,
      @JsonProperty("instrumenttype") String instrumenttype) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record ExpiryResponse(
      @JsonProperty("status") String status, @JsonProperty("data") List<String> data) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record OptionChainRequestBody(
      @JsonProperty("apikey") String apikey,
      @JsonProperty("underlying") String underlying,
      @JsonProperty("exchange") String exchange,
      @JsonProperty("expiry_date") String expiryDate,
      @JsonProperty("strike_count") Integer strikeCount) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record OptionChainResponse(
      @JsonProperty("status") String status,
      @JsonProperty("underlying") String underlying,
      @JsonProperty("underlying_ltp") Double underlyingLtp,
      @JsonProperty("expiry_date") String expiryDate,
      @JsonProperty("atm_strike") Double atmStrike,
      @JsonProperty("chain") List<ChainItem> chain) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record ChainItem(
      @JsonProperty("strike") Double strike,
      @JsonProperty("ce") OptionDataDetail ce,
      @JsonProperty("pe") OptionDataDetail pe) {}

  @JsonIgnoreProperties(ignoreUnknown = true)
  private record OptionDataDetail(
      @JsonProperty("symbol") String symbol,
      @JsonProperty("label") String label,
      @JsonProperty("ltp") Double ltp,
      @JsonProperty("bid") Double bid,
      @JsonProperty("ask") Double ask,
      @JsonProperty("open") Double open,
      @JsonProperty("high") Double high,
      @JsonProperty("low") Double low,
      @JsonProperty("prev_close") Double prevClose,
      @JsonProperty("volume") Long volume,
      @JsonProperty("oi") Long oi,
      @JsonProperty("lotsize") Integer lotsize,
      @JsonProperty("tick_size") Double tickSize) {

    @SuppressWarnings("unused")
    public Double prev_close() {
      return prevClose;
    }
  }
}
