package com.kite.trading.ml;

import com.kite.trading.dto.OiDataSnapshot;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

final class FeatureEngineering {

  static final int SEQ_LENGTH = 10;
  static final int LOOKAHEAD = 5;
  static final int NUM_FEATURES = 20;
  static final double BULLISH_THRESHOLD = 0.003;
  static final double BEARISH_THRESHOLD = -0.003;

  private static final int MEDIUM_WINDOW = 20;

  private FeatureEngineering() {}

  static double[][] engineerFeatures(List<OiDataSnapshot> snapshots) {
    List<OiDataSnapshot> sorted = new ArrayList<>(snapshots);
    sorted.sort(Comparator.comparing(OiDataSnapshot::timestamp));

    int n = sorted.size();
    double[][] features = new double[n][NUM_FEATURES];

    for (int i = 0; i < n; i++) {
      OiDataSnapshot s = sorted.get(i);
      double pcr = doubleVal(s.pcr(), 1.0);
      double peOiChange = doubleVal(s.totalPeOiChange(), 0.0);
      double ceOiChange = doubleVal(s.totalCeOiChange(), 0.0);
      double underlying = doubleVal(s.underlyingValue(), 0.0);
      double vix = 15.0;
      double largestPeOiStrike = doubleVal(s.largestPeOiStrike(), underlying);
      double largestCeOiStrike = doubleVal(s.largestCeOiStrike(), underlying);
      double marketSentiment = doubleVal(s.marketSentiment(), 0.0);

      features[i][0] = pcr;
      features[i][1] = i > 0 ? pcr - features[i - 1][0] : 0.0;
      features[i][2] = i > 2 ? pcr - features[i - 3][0] : 0.0;
      features[i][3] = peOiChange;
      features[i][4] = ceOiChange;

      double totalChange = Math.abs(peOiChange) + Math.abs(ceOiChange);
      features[i][5] = totalChange > 0.0 ? peOiChange / totalChange : 0.5;

      double pePct = totalChange > 0.0 ? Math.abs(peOiChange) / totalChange * 100.0 : 50.0;
      features[i][6] = pePct >= 50.0 ? pePct : 100.0 - pePct;

      features[i][7] = vix;

      features[i][8] =
          i > 0 && Math.abs(features[i - 1][0]) > 1e-12
              ? (underlying - doubleVal(sorted.get(i - 1).underlyingValue(), 0.0))
                  / doubleVal(sorted.get(i - 1).underlyingValue(), 1.0)
              : 0.0;
      features[i][9] =
          i > 2 && Math.abs(doubleVal(sorted.get(i - 3).underlyingValue(), 0.0)) > 1e-12
              ? (underlying - doubleVal(sorted.get(i - 3).underlyingValue(), 0.0))
                  / doubleVal(sorted.get(i - 3).underlyingValue(), 1.0)
              : 0.0;

      features[i][10] = underlying != 0.0 ? (underlying - largestPeOiStrike) / underlying : 0.0;
      features[i][11] = underlying != 0.0 ? (largestCeOiStrike - underlying) / underlying : 0.0;

      LocalDateTime ts = s.timestamp();
      features[i][12] = ts.getHour() + ts.getMinute() / 60.0;
      features[i][13] = ts.getDayOfWeek().getValue() - 1;

      features[i][14] =
          underlying != 0.0 ? (largestCeOiStrike - largestPeOiStrike) / underlying : 0.0;

      features[i][15] = marketSentiment;

      // Medium-term features (window = 20)
      features[i][16] = i >= MEDIUM_WINDOW ? pcr - features[i - MEDIUM_WINDOW][0] : 0.0;
      features[i][17] =
          i >= MEDIUM_WINDOW
                  && Math.abs(doubleVal(sorted.get(i - MEDIUM_WINDOW).underlyingValue(), 0.0))
                      > 1e-12
              ? (underlying - doubleVal(sorted.get(i - MEDIUM_WINDOW).underlyingValue(), 0.0))
                  / doubleVal(sorted.get(i - MEDIUM_WINDOW).underlyingValue(), 1.0)
              : 0.0;
      features[i][18] = i >= MEDIUM_WINDOW ? computeOiMomentum(sorted, i, MEDIUM_WINDOW) : 0.0;
      features[i][19] = i >= MEDIUM_WINDOW ? computeOiPutRatio(sorted, i, MEDIUM_WINDOW) : 0.0;
    }

    return features;
  }

  private static double computeOiMomentum(List<OiDataSnapshot> sorted, int i, int window) {
    double sumPe = 0.0;
    double sumCe = 0.0;
    for (int j = i - window + 1; j <= i; j++) {
      sumPe += doubleVal(sorted.get(j).totalPeOiChange(), 0.0);
      sumCe += doubleVal(sorted.get(j).totalCeOiChange(), 0.0);
    }
    double total = Math.abs(sumPe) + Math.abs(sumCe);
    return total > 0.0 ? sumPe / total : 0.5;
  }

  private static double computeOiPutRatio(List<OiDataSnapshot> sorted, int i, int window) {
    double sumPeOi = 0.0;
    double sumCeOi = 0.0;
    for (int j = i - window + 1; j <= i; j++) {
      sumPeOi += doubleVal(sorted.get(j).totalPeOi(), 0.0);
      sumCeOi += doubleVal(sorted.get(j).totalCeOi(), 0.0);
    }
    return sumCeOi > 0.0 ? sumPeOi / sumCeOi : 1.0;
  }

  static int[] generateLabels(double[][] features, double[] underlyingValues) {
    int n = features.length;
    int[] labels = new int[n];
    for (int i = 0; i < n; i++) {
      int futureIdx = i + LOOKAHEAD;
      if (futureIdx < n) {
        double ret = (underlyingValues[futureIdx] - underlyingValues[i]) / underlyingValues[i];
        if (ret >= BULLISH_THRESHOLD) {
          labels[i] = 0;
        } else if (ret <= BEARISH_THRESHOLD) {
          labels[i] = 1;
        } else {
          labels[i] = 2;
        }
      } else {
        labels[i] = 2;
      }
    }
    return labels;
  }

  static double[][] createSequences(double[][] features, int[] labels) {
    int n = features.length;
    int numSamples = n - SEQ_LENGTH - LOOKAHEAD;
    if (numSamples <= 0) {
      return new double[0][0];
    }
    int flatSize = SEQ_LENGTH * NUM_FEATURES;
    double[][] sequences = new double[numSamples][flatSize];
    for (int i = 0; i < numSamples; i++) {
      int idx = 0;
      for (int t = i; t < i + SEQ_LENGTH; t++) {
        System.arraycopy(features[t], 0, sequences[i], idx, NUM_FEATURES);
        idx += NUM_FEATURES;
      }
    }
    return sequences;
  }

  static int[] createSequenceLabels(int[] labels) {
    int n = labels.length;
    int numSamples = n - SEQ_LENGTH - LOOKAHEAD;
    if (numSamples <= 0) {
      return new int[0];
    }
    int[] seqLabels = new int[numSamples];
    for (int i = 0; i < numSamples; i++) {
      seqLabels[i] = labels[i + SEQ_LENGTH];
    }
    return seqLabels;
  }

  static double[] buildLatestSequence(double[][] features) {
    if (features.length < SEQ_LENGTH) {
      return null;
    }
    int flatSize = SEQ_LENGTH * NUM_FEATURES;
    double[] seq = new double[flatSize];
    int idx = 0;
    for (int t = features.length - SEQ_LENGTH; t < features.length; t++) {
      System.arraycopy(features[t], 0, seq, idx, NUM_FEATURES);
      idx += NUM_FEATURES;
    }
    return seq;
  }

  static double[] extractUnderlyingValues(List<OiDataSnapshot> snapshots) {
    return snapshots.stream().mapToDouble(s -> doubleVal(s.underlyingValue(), 0.0)).toArray();
  }

  static String[] getFeatureNames() {
    return new String[] {
      "PCR",
      "PCR_Chg_1",
      "PCR_Chg_3",
      "PE_OI_Chg",
      "CE_OI_Chg",
      "OI_Dominance",
      "OI_Dom_Pct",
      "VIX",
      "Ret_1",
      "Ret_3",
      "PE_OI_Dist",
      "CE_OI_Dist",
      "Hour",
      "DayOfWeek",
      "OI_Call_Put_Spread",
      "Sentiment",
      "PCR_Chg_20",
      "Ret_20",
      "OI_Momentum_20",
      "OI_Put_Ratio_20"
    };
  }

  private static double doubleVal(BigDecimal bd, double fallback) {
    return bd != null ? bd.doubleValue() : fallback;
  }
}
