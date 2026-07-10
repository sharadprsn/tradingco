package com.kite.trading.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Configuration class for WebClient instances.
 *
 * <p>This configuration provides WebClient beans used for making HTTP requests to external APIs
 * with proper timeout and buffer settings.
 *
 * @author Kite Trading Team
 * @version 1.0.0
 */
@Configuration
public class WebClientConfig {

  /**
   * Creates a WebClient bean configured for Zerodha API communication.
   *
   * <p>The WebClient is configured with: - Large buffer size (16MB) for handling large responses -
   * Default codecs configuration for JSON processing
   *
   * @return A configured WebClient instance
   */
  @Bean
  public WebClient webClient() {
    final ExchangeStrategies strategies =
        ExchangeStrategies.builder()
            .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(16 * 1024 * 1024))
            .build();

    return WebClient.builder().exchangeStrategies(strategies).build();
  }
}
