package com.kite.trading.service;

import com.kite.trading.dto.IndexQuote;
import com.kite.trading.dto.OptionChainData;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;

@Component
@Primary
public class FallbackOptionChainClient implements OptionChainClient {

  private static final Logger logger = LoggerFactory.getLogger(FallbackOptionChainClient.class);

  private static final Set<String> NSE_SYMBOLS =
      Set.of("NIFTY", "BANKNIFTY", "FINNIFTY", "MIDCPNIFTY");

  private final NseOptionChainClient nseClient;
  private final BseOptionChainClient bseClient;

  public FallbackOptionChainClient(
      final NseOptionChainClient nseClient, final BseOptionChainClient bseClient) {
    this.nseClient = nseClient;
    this.bseClient = bseClient;
  }

  @Override
  public OptionChainData fetchOptionChain() {
    return fetchOptionChain("NIFTY");
  }

  @Override
  public OptionChainData fetchOptionChain(final String symbol) {
    if (NSE_SYMBOLS.contains(symbol)) {
      return fetchFromNse(symbol);
    }
    if ("SENSEX".equals(symbol)) {
      return fetchFromBse();
    }
    logger.warn("Unsupported symbol: {}", symbol);
    return null;
  }

  private OptionChainData fetchFromNse(final String symbol) {
    OptionChainData nseData = nseClient.fetchOptionChain(symbol);
    if (isValid(nseData)) {
      return nseData;
    }

    for (int attempt = 1; attempt <= 2; attempt++) {
      logger.warn(
          "NSE option chain unavailable for {}, retrying (attempt {}/2)...", symbol, attempt);
      try {
        Thread.sleep(5000);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
      nseData = nseClient.fetchOptionChain(symbol);
      if (isValid(nseData)) {
        return nseData;
      }
    }

    logger.warn("NSE option chain failed after 3 attempts for {}", symbol);
    return null;
  }

  private OptionChainData fetchFromBse() {
    OptionChainData bseData = bseClient.fetchOptionChain("SENSEX");
    if (isValid(bseData)) {
      return bseData;
    }

    for (int attempt = 1; attempt <= 2; attempt++) {
      logger.warn("BSE option chain unavailable for SENSEX, retrying (attempt {}/2)...", attempt);
      try {
        Thread.sleep(5000);
      } catch (final InterruptedException e) {
        Thread.currentThread().interrupt();
        break;
      }
      bseData = bseClient.fetchOptionChain("SENSEX");
      if (isValid(bseData)) {
        return bseData;
      }
    }

    logger.warn("BSE option chain failed after 3 attempts for SENSEX");
    return null;
  }

  @Override
  public IndexQuote fetchIndexQuote(final String symbol) {
    if (NSE_SYMBOLS.contains(symbol)) {
      return nseClient.fetchIndexQuote(symbol);
    }
    if ("SENSEX".equals(symbol)) {
      return bseClient.fetchIndexQuote("SENSEX");
    }
    return null;
  }

  @Override
  public IndexQuote fetchIndexQuote() {
    return fetchIndexQuote("NIFTY");
  }

  private static boolean isValid(final OptionChainData data) {
    return data != null
        && data.records() != null
        && data.records().data() != null
        && !data.records().data().isEmpty();
  }
}
