package com.kite.trading.service;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

@Service
public class SentimentClient {

  private static final Logger logger = LoggerFactory.getLogger(SentimentClient.class);

  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  private final WebClient webClient;
  private final boolean enabled;

  public SentimentClient(
      final WebClient.Builder webClientBuilder,
      @Value("${ml.sidecar.url:http://ml-sidecar:8000}") final String sidecarUrl,
      @Value("${ml.sidecar.enabled:false}") final boolean enabled) {
    this.enabled = enabled;
    this.webClient = webClientBuilder.baseUrl(sidecarUrl).build();
  }

  public SentimentResult getSentiment() {
    if (!enabled) {
      return new SentimentResult(BigDecimal.ZERO, "neutral", List.of(), false);
    }

    try {
      final SentimentResponse response =
          webClient
              .get()
              .uri("/sentiment")
              .retrieve()
              .bodyToMono(SentimentResponse.class)
              .timeout(TIMEOUT)
              .block();

      if (response != null) {
        logger.debug("Market sentiment: score={}, label={}", response.score(), response.label());
        return new SentimentResult(
            BigDecimal.valueOf(response.score()),
            response.label(),
            response.headlines(),
            response.cached());
      }
    } catch (final Exception e) {
      logger.warn("Failed to fetch market sentiment (falling back to neutral): {}", e.getMessage());
    }

    return new SentimentResult(BigDecimal.ZERO, "neutral", List.of(), false);
  }

  public boolean isEnabled() {
    return enabled;
  }

  public record SentimentResponse(
      @JsonProperty("score") double score,
      @JsonProperty("label") String label,
      @JsonProperty("headlines") List<String> headlines,
      @JsonProperty("cached") boolean cached) {}

  public record SentimentResult(
      BigDecimal score, String label, List<String> headlines, boolean cached) {
    public SentimentResult() {
      this(BigDecimal.ZERO, "neutral", List.of(), false);
    }
  }
}
