package com.kite.trading.service;

import com.kite.trading.dto.IndexQuote;
import com.kite.trading.dto.OptionChainData;

public interface OptionChainClient {
  OptionChainData fetchOptionChain();

  default OptionChainData fetchOptionChain(final String symbol) {
    return fetchOptionChain();
  }

  default IndexQuote fetchIndexQuote(final String symbol) {
    return null;
  }

  default IndexQuote fetchIndexQuote() {
    return fetchIndexQuote("NIFTY");
  }
}
