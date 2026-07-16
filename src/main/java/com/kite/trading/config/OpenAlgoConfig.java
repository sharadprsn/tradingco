package com.kite.trading.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "openalgo")
public class OpenAlgoConfig {

  private String baseUrl = "http://localhost:5000";
  private String apiKey = "";

  public String getBaseUrl() {
    return baseUrl;
  }

  public void setBaseUrl(final String baseUrl) {
    this.baseUrl = baseUrl;
  }

  public String getApiKey() {
    return apiKey;
  }

  public void setApiKey(final String apiKey) {
    this.apiKey = apiKey;
  }
}
