package com.kite.trading.ml;

import com.kite.trading.dto.OiDataSnapshot;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
  private static final int CALIBRATION_BINS = 10;

  private final Path modelPath;
  private final SentimentAnalyzer sentimentAnalyzer;
  private final boolean enabled;

  private volatile RandomForest model;

  private final List<PredictionRecord> predictionHistory = new ArrayList<>();
  private final Object historyLock = new Object();

  private volatile double[] calibrationCurve;
  private volatile double historicalAccuracy;

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

    double[] rawProbs = model.predictProbability(seq);
    double[] calProbs = calibrateProbabilities(rawProbs);
    int labelIdx = argmax(calProbs);
    String direction =
        switch (labelIdx) {
          case 0 -> "BULLISH";
          case 1 -> "BEARISH";
          default -> "NEUTRAL";
        };
    double confidence = calProbs[labelIdx];

    return new PredictResult(direction, confidence, List.of(calProbs[0], calProbs[1], calProbs[2]));
  }

  public WeightedPredictResult weightedPredict(List<OiDataSnapshot> snapshots) {
    PredictResult result = predict(snapshots);
    if (result == null) {
      return null;
    }
    double weight = computeBlendWeight();
    return new WeightedPredictResult(
        result.direction(), result.confidence(), result.probabilities(), weight);
  }

  public TrainResult train(List<OiDataSnapshot> snapshots) {
    if (!enabled) {
      return new TrainResult("skipped", null, null, "ML disabled", null);
    }
    if (snapshots.size() < MIN_TRAIN_SAMPLES) {
      logger.warn(
          "Only {} samples available, need at least {}", snapshots.size(), MIN_TRAIN_SAMPLES);
      return new TrainResult(
          "skipped",
          null,
          null,
          "Insufficient data: " + snapshots.size() + " samples < " + MIN_TRAIN_SAMPLES,
          null);
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
            "Insufficient sequences: " + x.length + " < " + MIN_TRAIN_SAMPLES,
            null);
      }

      int numFeatures = x[0].length;
      RandomForest rf =
          new RandomForest(NUM_TREES, MAX_DEPTH, MIN_SAMPLES, 3, numFeatures, new Random(42));
      rf.fit(x, y);

      // Compute feature importance
      double[] importances = rf.getFeatureImportances();
      String[] featureNames = FeatureEngineering.getFeatureNames();
      StringBuilder fiLog = new StringBuilder("Feature importances:");
      for (int i = 0; i < featureNames.length; i++) {
        if (importances[i] > 0.01) {
          fiLog.append(String.format(" %s=%.1f%%", featureNames[i], importances[i] * 100));
        }
      }
      logger.info(fiLog.toString());

      // Compute OOB proxy accuracy on training data
      double oobAccuracy = computeAccuracy(x, y, rf);
      historicalAccuracy = oobAccuracy;
      logger.info("Training accuracy (OOB proxy): {:.1f}%", oobAccuracy * 100);

      // Build calibration curve
      buildCalibrationCurve(x, y, rf);

      this.model = rf;
      saveModel();
      logger.info("Training complete: {} samples, {} features", x.length, numFeatures);

      return new TrainResult("success", oobAccuracy, x.length, null, importances);
    } catch (Exception e) {
      logger.error("Training failed", e);
      return new TrainResult("error", null, null, e.getMessage(), null);
    }
  }

  public void recordPrediction(String direction, double confidence, double actualReturn) {
    synchronized (historyLock) {
      String actualDir;
      if (actualReturn >= FeatureEngineering.BULLISH_THRESHOLD) {
        actualDir = "BULLISH";
      } else if (actualReturn <= FeatureEngineering.BEARISH_THRESHOLD) {
        actualDir = "BEARISH";
      } else {
        actualDir = "NEUTRAL";
      }
      boolean correct = direction.equals(actualDir);
      predictionHistory.add(
          new PredictionRecord(
              LocalDate.now(), direction, confidence, actualDir, actualReturn, correct));
    }
  }

  public double calibrateConfidence(double rawConfidence, String direction) {
    if (calibrationCurve == null) {
      return rawConfidence;
    }
    int bin = (int) (rawConfidence * CALIBRATION_BINS);
    if (bin >= CALIBRATION_BINS) bin = CALIBRATION_BINS - 1;
    if (bin < 0) bin = 0;
    return calibrationCurve[bin];
  }

  public String exportPredictionCsv() {
    synchronized (historyLock) {
      StringBuilder sb =
          new StringBuilder(
              "date,predicted_direction,confidence,actual_direction,actual_return,correct\n");
      for (PredictionRecord r : predictionHistory) {
        sb.append(r.date().format(DateTimeFormatter.ISO_LOCAL_DATE))
            .append(",")
            .append(r.predictedDirection())
            .append(",")
            .append(String.format("%.4f", r.confidence()))
            .append(",")
            .append(r.actualDirection())
            .append(",")
            .append(String.format("%.6f", r.actualReturn()))
            .append(",")
            .append(r.correct())
            .append("\n");
      }
      return sb.toString();
    }
  }

  public void writeBacktestCsv(Path outputPath) {
    synchronized (historyLock) {
      try {
        Files.createDirectories(outputPath.getParent());
        Files.writeString(
            outputPath,
            exportPredictionCsv(),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING);
        logger.info("Backtest CSV written to {}", outputPath);
      } catch (IOException e) {
        logger.error("Failed to write backtest CSV", e);
      }
    }
  }

  public AccuracyReport computeAccuracyReport() {
    synchronized (historyLock) {
      if (predictionHistory.isEmpty()) {
        return new AccuracyReport(0, 0, 0.0, 0.0, 0.0, 0.0, 0.0, 0);
      }
      long correct = predictionHistory.stream().filter(PredictionRecord::correct).count();
      long total = predictionHistory.size();
      double accuracy = (double) correct / total;

      double avgConfCorrect =
          predictionHistory.stream()
              .filter(PredictionRecord::correct)
              .mapToDouble(PredictionRecord::confidence)
              .average()
              .orElse(0.0);
      double avgConfWrong =
          predictionHistory.stream()
              .filter(r -> !r.correct())
              .mapToDouble(PredictionRecord::confidence)
              .average()
              .orElse(0.0);

      double bestThreshold = findBestThreshold();

      return new AccuracyReport(
          (int) total,
          (int) correct,
          accuracy,
          avgConfCorrect,
          avgConfWrong,
          computeCalibrationError(),
          bestThreshold,
          predictionHistory.size());
    }
  }

  public SentimentResult getSentiment() {
    if (!enabled || sentimentAnalyzer == null) {
      return new SentimentResult(0.0, "neutral");
    }
    SentimentAnalyzer.SentimentResult r = sentimentAnalyzer.getSentiment();
    return new SentimentResult(r.score(), r.label());
  }

  private double computeBlendWeight() {
    if (predictionHistory.size() < 5) {
      return 0.3;
    }
    synchronized (historyLock) {
      long recentCorrect =
          predictionHistory.stream()
              .skip(Math.max(0, predictionHistory.size() - 20))
              .filter(PredictionRecord::correct)
              .count();
      long recentTotal = Math.min(predictionHistory.size(), 20);
      double recentAccuracy = recentTotal > 0 ? (double) recentCorrect / recentTotal : 0.3;
      // Weight ranges from 0.2 to 0.8
      return 0.2 + recentAccuracy * 0.6;
    }
  }

  private double computeAccuracy(double[][] x, int[] y, RandomForest rf) {
    int correct = 0;
    int total = x.length;
    for (int i = 0; i < total; i++) {
      int pred = rf.predict(x[i]);
      if (pred == y[i]) {
        correct++;
      }
    }
    return total > 0 ? (double) correct / total : 0.0;
  }

  private void buildCalibrationCurve(double[][] x, int[] y, RandomForest rf) {
    int nBins = CALIBRATION_BINS;
    double[] binAcc = new double[nBins];
    int[] binCount = new int[nBins];

    for (int i = 0; i < x.length; i++) {
      double[] probs = rf.predictProbability(x[i]);
      double maxProb = max(probs);
      int predClass = argmax(probs);
      int bin = (int) (maxProb * nBins);
      if (bin >= nBins) bin = nBins - 1;
      binCount[bin]++;
      if (predClass == y[i]) {
        binAcc[bin]++;
      }
    }

    calibrationCurve = new double[nBins];
    for (int i = 0; i < nBins; i++) {
      double mid = (i + 0.5) / nBins;
      if (binCount[i] > 0) {
        calibrationCurve[i] = binAcc[i] / binCount[i];
      } else {
        calibrationCurve[i] = mid;
      }
    }
  }

  private double[] calibrateProbabilities(double[] rawProbs) {
    if (calibrationCurve == null) {
      return rawProbs;
    }
    double[] cal = new double[rawProbs.length];
    double sum = 0.0;
    for (int i = 0; i < rawProbs.length; i++) {
      int bin = (int) (rawProbs[i] * CALIBRATION_BINS);
      if (bin >= CALIBRATION_BINS) bin = CALIBRATION_BINS - 1;
      if (bin < 0) bin = 0;
      cal[i] = calibrationCurve[bin];
      sum += cal[i];
    }
    if (sum > 0.0) {
      for (int i = 0; i < cal.length; i++) {
        cal[i] /= sum;
      }
    }
    return cal;
  }

  private double findBestThreshold() {
    synchronized (historyLock) {
      if (predictionHistory.size() < 10) return 0.5;
      double bestT = 0.5;
      double bestProfit = -Double.MAX_VALUE;
      for (int t = 20; t <= 90; t += 5) {
        double threshold = t / 100.0;
        double profit = 0.0;
        int trades = 0;
        for (PredictionRecord r : predictionHistory) {
          if (r.confidence() >= threshold) {
            trades++;
            if (r.correct()) {
              profit += Math.abs(r.actualReturn()) * 100;
            } else {
              profit -= Math.abs(r.actualReturn()) * 100;
            }
          }
        }
        if (trades >= 5 && profit > bestProfit) {
          bestProfit = profit;
          bestT = threshold;
        }
      }
      return bestT;
    }
  }

  private double computeCalibrationError() {
    if (calibrationCurve == null || predictionHistory.size() < 10) return 1.0;
    synchronized (historyLock) {
      double error = 0.0;
      int count = 0;
      for (int i = 0; i < CALIBRATION_BINS; i++) {
        double expected = (i + 0.5) / CALIBRATION_BINS;
        error += Math.abs(calibrationCurve[i] - expected);
        count++;
      }
      return count > 0 ? error / count : 1.0;
    }
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

  private static double max(double[] arr) {
    double m = arr[0];
    for (int i = 1; i < arr.length; i++) {
      if (arr[i] > m) m = arr[i];
    }
    return m;
  }

  @Override
  public void destroy() {
    logger.info("MlService shutting down");
  }

  public record PredictResult(String direction, double confidence, List<Double> probabilities) {}

  public record WeightedPredictResult(
      String direction, double confidence, List<Double> probabilities, double blendWeight) {}

  public record TrainResult(
      String status,
      Double valAccuracy,
      Integer samples,
      String reason,
      double[] featureImportances) {}

  public record SentimentResult(double score, String label) {}

  public record PredictionRecord(
      LocalDate date,
      String predictedDirection,
      double confidence,
      String actualDirection,
      double actualReturn,
      boolean correct) {}

  public record AccuracyReport(
      int totalPredictions,
      int correctPredictions,
      double accuracy,
      double avgConfCorrect,
      double avgConfWrong,
      double calibrationError,
      double bestThreshold,
      int historySize) {}
}
