package com.kite.trading.service;

import com.kite.trading.dto.LstmPredictionRequest;
import com.kite.trading.dto.LstmPredictionRequest.LstmSnapshot;
import com.kite.trading.dto.LstmPredictionResponse;
import com.kite.trading.dto.LstmTrainResponse;
import com.kite.trading.dto.OiDataSnapshot;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.util.retry.Retry;

@Service
public class LstmPredictionClient {

  private static final Logger logger = LoggerFactory.getLogger(LstmPredictionClient.class);

  private static final int MIN_SNAPSHOTS = 10;
  private static final Duration TIMEOUT = Duration.ofSeconds(5);
  private static final Duration TRAIN_TIMEOUT = Duration.ofSeconds(300);

  private final WebClient webClient;
  private final boolean enabled;

  public LstmPredictionClient(
      final WebClient.Builder webClientBuilder,
      @Value("${ml.sidecar.url:http://ml-sidecar:8000}") final String sidecarUrl,
      @Value("${ml.sidecar.enabled:false}") final boolean enabled) {
    this.enabled = enabled;
    this.webClient = webClientBuilder.baseUrl(sidecarUrl).build();
  }

  public boolean isEnabled() {
    return enabled;
  }

  public LstmPredictionResponse predict(final List<OiDataSnapshot> snapshots) {
    if (!enabled || snapshots.size() < MIN_SNAPSHOTS) {
      return null;
    }

    try {
      final List<OiDataSnapshot> recent =
          snapshots.subList(snapshots.size() - MIN_SNAPSHOTS, snapshots.size());
      final List<LstmSnapshot> lstmSnapshots = new ArrayList<>(MIN_SNAPSHOTS);

      for (final OiDataSnapshot s : recent) {
        lstmSnapshots.add(
            new LstmSnapshot(
                s.underlyingValue(),
                s.totalPeOi(),
                s.totalCeOi(),
                s.totalPeOiChange(),
                s.totalCeOiChange(),
                s.pcr(),
                s.largestPeOiStrike() != null ? s.largestPeOiStrike() : BigDecimal.ZERO,
                s.largestCeOiStrike() != null ? s.largestCeOiStrike() : BigDecimal.ZERO,
                null,
                null,
                null,
                null,
                s.marketSentiment() != null ? s.marketSentiment() : BigDecimal.ZERO));
      }

      final LstmPredictionRequest request = new LstmPredictionRequest(lstmSnapshots);

      final LstmPredictionResponse response =
          webClient
              .post()
              .uri("/predict")
              .bodyValue(request)
              .retrieve()
              .bodyToMono(LstmPredictionResponse.class)
              .timeout(TIMEOUT)
              .retryWhen(Retry.max(1).filter(ex -> true))
              .block();

      logger.debug(
          "LSTM prediction: direction={}, confidence={}",
          response != null ? response.direction() : null,
          response != null ? response.confidence() : null);

      return response;
    } catch (final Exception e) {
      logger.warn("LSTM prediction failed (falling back to rules): {}", e.getMessage());
      return null;
    }
  }

  public LstmTrainResponse triggerTraining() {
    if (!enabled) {
      return new LstmTrainResponse("skipped", null, null, null, null, "ML sidecar disabled");
    }

    try {
      final LstmTrainResponse response =
          webClient
              .post()
              .uri("/train")
              .retrieve()
              .bodyToMono(LstmTrainResponse.class)
              .timeout(TRAIN_TIMEOUT)
              .block();

      logger.info(
          "LSTM training result: status={}, val_accuracy={}, samples={}",
          response != null ? response.status() : null,
          response != null ? response.valAccuracy() : null,
          response != null ? response.samples() : null);

      return response;
    } catch (final Exception e) {
      logger.error("LSTM training trigger failed: {}", e.getMessage());
      return new LstmTrainResponse("error", null, null, null, null, e.getMessage());
    }
  }
}
