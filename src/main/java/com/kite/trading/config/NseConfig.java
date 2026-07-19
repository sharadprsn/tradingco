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

  public String getEquityQuoteUrl(final String symbol) {
    return "https://www.nseindia.com/api/quote-equity?symbol=" + symbol;
  }

  public String getEquityOptionChainUrl(final String symbol) {
    return "https://www.nseindia.com/api/option-chain-v3?type=Equities&symbol=" + symbol;
  }

  public String getPreOpenUrl(final String key) {
    return "https://www.nseindia.com/api/market-data-pre-open?key=" + key;
  }

  public String getBhavCopyUrl(final String dateStr) {
    return "https://nsearchives.nseindia.com/products/content/sec_bhavdata_full_"
        + dateStr
        + ".csv";
  }

  public String getHomeUrl() {
    return homeUrl;
  }

  public void setHomeUrl(final String homeUrl) {
    this.homeUrl = homeUrl;
  }

  private String ohlcApiBaseUrl = "http://nse-ohlc-api:5000";
  private int ohlcApiTimeoutMs = 10000;
  private String ohlcApiSymbol = "^NSEI";

  public String getOhlcApiBaseUrl() {
    return ohlcApiBaseUrl;
  }

  public void setOhlcApiBaseUrl(final String ohlcApiBaseUrl) {
    this.ohlcApiBaseUrl = ohlcApiBaseUrl;
  }

  public int getOhlcApiTimeoutMs() {
    return ohlcApiTimeoutMs;
  }

  public void setOhlcApiTimeoutMs(final int ohlcApiTimeoutMs) {
    this.ohlcApiTimeoutMs = ohlcApiTimeoutMs;
  }

  public String getOhlcApiSymbol() {
    return ohlcApiSymbol;
  }

  public void setOhlcApiSymbol(final String ohlcApiSymbol) {
    this.ohlcApiSymbol = ohlcApiSymbol;
  }

  public String getUserAgent() {
    return userAgent;
  }

  public void setUserAgent(final String userAgent) {
    this.userAgent = userAgent;
  }
}
