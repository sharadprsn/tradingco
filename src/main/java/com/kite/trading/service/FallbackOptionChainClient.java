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
  private final OpenAlgoOptionChainClient openAlgoClient;

  public FallbackOptionChainClient(
      final NseOptionChainClient nseClient, final OpenAlgoOptionChainClient openAlgoClient) {
    this.nseClient = nseClient;
    this.openAlgoClient = openAlgoClient;
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
    return openAlgoClient.fetchOptionChain(symbol);
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

    logger.warn(
        "NSE option chain failed after 3 attempts for {}, falling back to OpenAlgo", symbol);
    final OptionChainData openAlgoData = openAlgoClient.fetchOptionChain(symbol);
    if (isValid(openAlgoData)) {
      return openAlgoData;
    }

    logger.error("Both NSE and OpenAlgo option chain sources failed for {}", symbol);
    return null;
  }

  @Override
  public IndexQuote fetchIndexQuote(final String symbol) {
    if (NSE_SYMBOLS.contains(symbol)) {
      return nseClient.fetchIndexQuote(symbol);
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
