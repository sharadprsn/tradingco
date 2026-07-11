package com.kite.trading.ml;

import com.kite.trading.dto.OiDataSnapshot;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Random;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class MlService implements DisposableBean {

  private static final Logger logger = LoggerFactory.getLogger(MlService.class);

  private static final int NUM_TREES = 200;
  private static final int MAX_DEPTH = 15;
  private static final int MIN_SAMPLES = 5;
  private static final int MIN_TRAIN_SAMPLES = 50;

  private final Path modelPath;
  private final SentimentAnalyzer sentimentAnalyzer;
  private final boolean enabled;

  private volatile RandomForest model;

  public MlService(
      @Value("${ml.enabled:true}") boolean enabled,
      @Value("${ml.model-path:./data/model.rf}") String modelPath) {
    this.enabled = enabled;
    this.modelPath = Paths.get(modelPath);
    this.sentimentAnalyzer = enabled ? new SentimentAnalyzer() : null;
    loadModel();
  }

  public boolean isModelLoaded() {
    return enabled && model != null;
  }

  public PredictResult predict(List<OiDataSnapshot> snapshots) {
    if (!enabled || model == null) {
      return null;
    }
    if (snapshots.size() < FeatureEngineering.SEQ_LENGTH) {
      return null;
    }

    List<OiDataSnapshot> recent =
        snapshots.subList(
            Math.max(0, snapshots.size() - FeatureEngineering.SEQ_LENGTH), snapshots.size());
    double[][] features = FeatureEngineering.engineerFeatures(recent);
    double[] seq = FeatureEngineering.buildLatestSequence(features);
    if (seq == null) {
      return null;
    }

    double[] probs = model.predictProbability(seq);
    int labelIdx = argmax(probs);
    String direction =
        switch (labelIdx) {
          case 0 -> "BULLISH";
          case 1 -> "BEARISH";
          default -> "NEUTRAL";
        };
    double confidence = probs[labelIdx];

    return new PredictResult(direction, confidence, List.of(probs[0], probs[1], probs[2]));
  }

  public TrainResult train(List<OiDataSnapshot> snapshots) {
    if (!enabled) {
      return new TrainResult("skipped", null, null, "ML disabled");
    }
    if (snapshots.size() < MIN_TRAIN_SAMPLES) {
      logger.warn(
          "Only {} samples available, need at least {}", snapshots.size(), MIN_TRAIN_SAMPLES);
      return new TrainResult(
          "skipped",
          null,
          null,
          "Insufficient data: " + snapshots.size() + " samples < " + MIN_TRAIN_SAMPLES);
    }

    try {
      double[][] features = FeatureEngineering.engineerFeatures(snapshots);
      double[] underlyingValues = FeatureEngineering.extractUnderlyingValues(snapshots);
      int[] labels = FeatureEngineering.generateLabels(features, underlyingValues);

      double[][] x = FeatureEngineering.createSequences(features, labels);
      int[] y = FeatureEngineering.createSequenceLabels(labels);

      if (x.length < MIN_TRAIN_SAMPLES) {
        logger.warn("Only {} sequences available, need at least {}", x.length, MIN_TRAIN_SAMPLES);
        return new TrainResult(
            "skipped",
            null,
            null,
            "Insufficient sequences: " + x.length + " < " + MIN_TRAIN_SAMPLES);
      }

      int numFeatures = x[0].length;
      RandomForest rf =
          new RandomForest(NUM_TREES, MAX_DEPTH, MIN_SAMPLES, 3, numFeatures, new Random(42));
      rf.fit(x, y);

      this.model = rf;
      saveModel();
      logger.info("Training complete: {} samples, {} features", x.length, numFeatures);

      return new TrainResult("success", null, x.length, null);
    } catch (Exception e) {
      logger.error("Training failed", e);
      return new TrainResult("error", null, null, e.getMessage());
    }
  }

  public SentimentResult getSentiment() {
    if (!enabled || sentimentAnalyzer == null) {
      return new SentimentResult(0.0, "neutral");
    }
    SentimentAnalyzer.SentimentResult r = sentimentAnalyzer.getSentiment();
    return new SentimentResult(r.score(), r.label());
  }

  private void loadModel() {
    if (!enabled) {
      return;
    }
    if (Files.exists(modelPath)) {
      try (InputStream is = Files.newInputStream(modelPath)) {
        model = (RandomForest) new java.io.ObjectInputStream(is).readObject();
        logger.info("Model loaded from {}", modelPath);
      } catch (Exception e) {
        logger.warn("Failed to load model from {}: {}", modelPath, e.getMessage());
        model = null;
      }
    } else {
      logger.info("No model found at {}, will train on first market close", modelPath);
    }
  }

  private void saveModel() {
    try {
      Files.createDirectories(modelPath.getParent());
      try (OutputStream os = Files.newOutputStream(modelPath)) {
        new java.io.ObjectOutputStream(os).writeObject(model);
      }
      logger.info("Model saved to {}", modelPath);
    } catch (IOException e) {
      logger.error("Failed to save model", e);
    }
  }

  private static int argmax(double[] arr) {
    int best = 0;
    for (int i = 1; i < arr.length; i++) {
      if (arr[i] > arr[best]) {
        best = i;
      }
    }
    return best;
  }

  @Override
  public void destroy() {
    logger.info("MlService shutting down");
  }

  public record PredictResult(String direction, double confidence, List<Double> probabilities) {}

  public record TrainResult(String status, Double valAccuracy, Integer samples, String reason) {}

  public record SentimentResult(double score, String label) {}
}
