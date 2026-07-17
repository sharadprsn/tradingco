package com.kite.trading.controller;

import com.kite.trading.dto.OptionChainData;
import com.kite.trading.service.OptionChainClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/option-chain")
public class OptionChainController {

  private static final Logger logger = LoggerFactory.getLogger(OptionChainController.class);

  private final OptionChainClient optionChainClient;

  public OptionChainController(final OptionChainClient optionChainClient) {
    this.optionChainClient = optionChainClient;
  }

  @GetMapping
  public ResponseEntity<OptionChainData> getOptionChain(
      @RequestParam(name = "symbol", defaultValue = "NIFTY") final String symbol) {
    logger.info("Fetching option chain data for symbol: {}", symbol);
    final OptionChainData data = optionChainClient.fetchOptionChain(symbol);
    if (data == null) {
      logger.warn("Option chain data unavailable for symbol: {}", symbol);
      return ResponseEntity.noContent().build();
    }
    return ResponseEntity.ok(data);
  }
}
