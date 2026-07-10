package com.kite.trading.config;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "nse")
public class NseConfig {

  private static final String OC_BASE =
      "https://www.nseindia.com/api/option-chain-v3?type=Indices&symbol=";
  private static final String CI_BASE =
      "https://www.nseindia.com/api/option-chain-contract-info?symbol=";

  private static final Map<String, String> SYMBOL_TICKER =
      Map.of(
          "NIFTY", "NIFTY%2050",
          "BANKNIFTY", "NIFTY%20BANK",
          "FINNIFTY", "FINNIFTY",
          "VIX", "INDIA%20VIX");

  private String homeUrl = "https://www.nseindia.com/option-chain";
  private String userAgent =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

  public NseConfig() {}

  public String getOptionChainUrl(final String symbol) {
    return OC_BASE + symbol;
  }

  public String getContractInfoUrl(final String symbol) {
    return CI_BASE + symbol;
  }

  public String getIndexQuoteUrl(final String symbol) {
    final String ticker = SYMBOL_TICKER.getOrDefault(symbol, "NIFTY%2050");
    return "https://www.nseindia.com/api/quote-indices?indices=" + ticker;
  }

  public String getHomeUrl() {
    return homeUrl;
  }

  public void setHomeUrl(final String homeUrl) {
    this.homeUrl = homeUrl;
  }

  public String getUserAgent() {
    return userAgent;
  }

  public void setUserAgent(final String userAgent) {
    this.userAgent = userAgent;
  }
}
