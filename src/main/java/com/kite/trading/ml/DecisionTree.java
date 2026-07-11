package com.kite.trading.ml;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

final class DecisionTree implements Serializable {

  @Serial private static final long serialVersionUID = 2L;

  private Node root;
  private final int maxDepth;
  private final int minSamples;
  private final int numClasses;
  private final int numFeaturesPerSplit;
  private final Random random;
  private int totalSamples;

  private transient double[] featureImportance;

  DecisionTree(
      int maxDepth, int minSamples, int numClasses, int numFeaturesPerSplit, Random random) {
    this.maxDepth = maxDepth;
    this.minSamples = minSamples;
    this.numClasses = numClasses;
    this.numFeaturesPerSplit = numFeaturesPerSplit;
    this.random = random;
  }

  void fit(double[][] x, int[] y) {
    int n = x.length;
    totalSamples = n;
    int[] indices = new int[n];
    for (int i = 0; i < n; i++) {
      indices[i] = i;
    }
    root = buildTree(x, y, indices, 0);
    featureImportance = null;
  }

  int predict(double[] x) {
    return predict(root, x);
  }

  double[] predictProbability(double[] x) {
    return predictProbability(root, x);
  }

  double[] computeFeatureImportances(int numFeatures) {
    if (featureImportance != null) {
      return featureImportance;
    }
    double[] imp = new double[numFeatures];
    if (root != null) {
      accumulateImportance(root, imp);
    }
    double sum = 0.0;
    for (double v : imp) {
      sum += v;
    }
    if (sum > 0.0) {
      for (int i = 0; i < imp.length; i++) {
        imp[i] /= sum;
      }
    }
    featureImportance = imp;
    return imp;
  }

  private void accumulateImportance(Node node, double[] imp) {
    if (node == null || node.isLeaf()) {
      return;
    }
    double giniBefore =
        weightedGiniFromProbs(
            node.left.probabilities, node.left.sampleCount,
            node.right.probabilities, node.right.sampleCount);
    double giniAfter =
        weightedGiniFromProbs(
            node.left.probabilities, node.left.sampleCount,
            node.right.probabilities, node.right.sampleCount);
    double decrease = giniBefore - giniAfter;
    if (decrease > 0.0) {
      imp[node.featureIndex] += decrease * node.sampleCount / totalSamples;
    }
    accumulateImportance(node.left, imp);
    accumulateImportance(node.right, imp);
  }

  private double weightedGiniFromProbs(
      double[] leftProbs, int leftCount, double[] rightProbs, int rightCount) {
    double giniLeft = giniFromProbs(leftProbs);
    double giniRight = giniFromProbs(rightProbs);
    int total = leftCount + rightCount;
    if (total == 0) return 0.0;
    return (leftCount / (double) total) * giniLeft + (rightCount / (double) total) * giniRight;
  }

  private double giniFromProbs(double[] probs) {
    double sumSq = 0.0;
    for (double p : probs) {
      sumSq += p * p;
    }
    return 1.0 - sumSq;
  }

  private Node buildTree(double[][] x, int[] y, int[] indices, int depth) {
    if (depth >= maxDepth || indices.length <= minSamples || allSameClass(y, indices)) {
      return createLeaf(y, indices);
    }

    int[] featureSubset = randomFeatureSubset(x[0].length);

    Split bestSplit = null;
    double bestGini = Double.MAX_VALUE;

    for (int fIdx : featureSubset) {
      double[] thresholds = uniqueValues(x, indices, fIdx);
      for (double t : thresholds) {
        Split split = trySplit(x, indices, fIdx, t);
        if (split.left.length == 0 || split.right.length == 0) {
          continue;
        }
        double gini = weightedGini(y, split.left, split.right);
        if (gini < bestGini) {
          bestGini = gini;
          bestSplit = split;
          bestSplit.featureIndex = fIdx;
          bestSplit.threshold = t;
        }
      }
    }

    if (bestSplit == null || bestGini >= currentGini(y, indices)) {
      return createLeaf(y, indices);
    }

    Node node = new Node();
    node.featureIndex = bestSplit.featureIndex;
    node.threshold = bestSplit.threshold;
    node.sampleCount = indices.length;
    node.left = buildTree(x, y, bestSplit.left, depth + 1);
    node.right = buildTree(x, y, bestSplit.right, depth + 1);
    return node;
  }

  private Node createLeaf(int[] y, int[] indices) {
    Node node = new Node();
    node.probabilities = classProbabilities(y, indices);
    node.prediction = argmax(node.probabilities);
    node.sampleCount = indices.length;
    return node;
  }

  private double[] classProbabilities(int[] y, int[] indices) {
    double[] probs = new double[numClasses];
    for (int idx : indices) {
      probs[y[idx]] += 1.0;
    }
    for (int i = 0; i < numClasses; i++) {
      probs[i] /= indices.length;
    }
    return probs;
  }

  private int predict(Node node, double[] x) {
    while (!node.isLeaf()) {
      if (x[node.featureIndex] <= node.threshold) {
        node = node.left;
      } else {
        node = node.right;
      }
    }
    return node.prediction;
  }

  private double[] predictProbability(Node node, double[] x) {
    while (!node.isLeaf()) {
      if (x[node.featureIndex] <= node.threshold) {
        node = node.left;
      } else {
        node = node.right;
      }
    }
    return node.probabilities;
  }

  private int[] randomFeatureSubset(int totalFeatures) {
    int k = Math.min(numFeaturesPerSplit, totalFeatures);
    int[] subset = new int[k];
    if (k == totalFeatures) {
      for (int i = 0; i < k; i++) {
        subset[i] = i;
      }
      return subset;
    }
    boolean[] selected = new boolean[totalFeatures];
    int count = 0;
    while (count < k) {
      int f = random.nextInt(totalFeatures);
      if (!selected[f]) {
        selected[f] = true;
        subset[count++] = f;
      }
    }
    return subset;
  }

  private boolean allSameClass(int[] y, int[] indices) {
    if (indices.length == 0) {
      return true;
    }
    int first = y[indices[0]];
    for (int i = 1; i < indices.length; i++) {
      if (y[indices[i]] != first) {
        return false;
      }
    }
    return true;
  }

  private double[] uniqueValues(double[][] x, int[] indices, int featureIdx) {
    double[] sorted = new double[indices.length];
    for (int i = 0; i < indices.length; i++) {
      sorted[i] = x[indices[i]][featureIdx];
    }
    java.util.Arrays.sort(sorted);
    List<Double> uniq = new ArrayList<>();
    for (double v : sorted) {
      if (uniq.isEmpty() || Math.abs(v - uniq.getLast()) > 1e-12) {
        uniq.add(v);
      }
    }
    double[] result = new double[uniq.size()];
    for (int i = 0; i < uniq.size(); i++) {
      result[i] = uniq.get(i);
    }
    return result;
  }

  private Split trySplit(double[][] x, int[] indices, int featureIdx, double threshold) {
    List<Integer> leftList = new ArrayList<>();
    List<Integer> rightList = new ArrayList<>();
    for (int idx : indices) {
      if (x[idx][featureIdx] <= threshold) {
        leftList.add(idx);
      } else {
        rightList.add(idx);
      }
    }
    int[] left = leftList.stream().mapToInt(Integer::intValue).toArray();
    int[] right = rightList.stream().mapToInt(Integer::intValue).toArray();
    return new Split(left, right);
  }

  private double weightedGini(int[] y, int[] left, int[] right) {
    int total = left.length + right.length;
    double giniLeft = gini(y, left);
    double giniRight = gini(y, right);
    return (left.length / (double) total) * giniLeft + (right.length / (double) total) * giniRight;
  }

  private double gini(int[] y, int[] indices) {
    if (indices.length == 0) {
      return 0.0;
    }
    double[] counts = new double[numClasses];
    for (int idx : indices) {
      counts[y[idx]] += 1.0;
    }
    double sum = 0.0;
    for (int i = 0; i < numClasses; i++) {
      double p = counts[i] / indices.length;
      sum += p * p;
    }
    return 1.0 - sum;
  }

  private double currentGini(int[] y, int[] indices) {
    return gini(y, indices);
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

  private static final class Node implements Serializable {
    @Serial private static final long serialVersionUID = 2L;

    int featureIndex;
    double threshold;
    Node left;
    Node right;
    int prediction = -1;
    double[] probabilities;
    int sampleCount;

    boolean isLeaf() {
      return prediction >= 0;
    }
  }

  private static final class Split {
    final int[] left;
    final int[] right;
    int featureIndex;
    double threshold;

    Split(int[] left, int[] right) {
      this.left = left;
      this.right = right;
    }
  }
}
