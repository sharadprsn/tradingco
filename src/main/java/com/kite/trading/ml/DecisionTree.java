package com.kite.trading.ml;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

final class DecisionTree implements Serializable {

  @Serial private static final long serialVersionUID = 1L;

  private Node root;
  private final int maxDepth;
  private final int minSamples;
  private final int numClasses;
  private final int numFeaturesPerSplit;
  private final Random random;

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
    int[] indices = new int[n];
    for (int i = 0; i < n; i++) {
      indices[i] = i;
    }
    root = buildTree(x, y, indices, 0);
  }

  int predict(double[] x) {
    return predict(root, x);
  }

  double[] predictProbability(double[] x) {
    return predictProbability(root, x);
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
    node.left = buildTree(x, y, bestSplit.left, depth + 1);
    node.right = buildTree(x, y, bestSplit.right, depth + 1);
    return node;
  }

  private Node createLeaf(int[] y, int[] indices) {
    Node node = new Node();
    node.probabilities = classProbabilities(y, indices);
    node.prediction = argmax(node.probabilities);
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
    @Serial private static final long serialVersionUID = 1L;

    int featureIndex;
    double threshold;
    Node left;
    Node right;
    int prediction = -1;
    double[] probabilities;

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
