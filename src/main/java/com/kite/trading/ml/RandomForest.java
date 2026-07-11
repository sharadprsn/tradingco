package com.kite.trading.ml;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

final class RandomForest implements Serializable {

  @Serial private static final long serialVersionUID = 2L;

  private final List<DecisionTree> trees;
  private final int numClasses;
  private final int numFeatures;
  private transient double[] featureImportance;

  RandomForest(
      int numTrees, int maxDepth, int minSamples, int numClasses, int numFeatures, Random random) {
    this.numClasses = numClasses;
    this.numFeatures = numFeatures;
    this.trees = new ArrayList<>(numTrees);
    int featuresPerSplit = (int) Math.sqrt(numFeatures);
    if (featuresPerSplit < 1) {
      featuresPerSplit = 1;
    }
    for (int i = 0; i < numTrees; i++) {
      trees.add(
          new DecisionTree(
              maxDepth, minSamples, numClasses, featuresPerSplit, new Random(random.nextLong())));
    }
  }

  void fit(double[][] x, int[] y) {
    int n = x.length;
    for (DecisionTree tree : trees) {
      double[][] bootstrapX = new double[n][];
      int[] bootstrapY = new int[n];
      Random bagRandom = new Random();
      for (int i = 0; i < n; i++) {
        int idx = bagRandom.nextInt(n);
        bootstrapX[i] = x[idx];
        bootstrapY[i] = y[idx];
      }
      tree.fit(bootstrapX, bootstrapY);
    }
    computeFeatureImportance();
  }

  int predict(double[] x) {
    int[] votes = new int[numClasses];
    for (DecisionTree tree : trees) {
      votes[tree.predict(x)]++;
    }
    return argmax(votes);
  }

  double[] predictProbability(double[] x) {
    double[] probs = new double[numClasses];
    for (DecisionTree tree : trees) {
      double[] treeProbs = tree.predictProbability(x);
      for (int i = 0; i < numClasses; i++) {
        probs[i] += treeProbs[i];
      }
    }
    for (int i = 0; i < numClasses; i++) {
      probs[i] /= trees.size();
    }
    return probs;
  }

  double[] getFeatureImportances() {
    return featureImportance;
  }

  void computeFeatureImportance() {
    double[] imp = new double[numFeatures];
    for (DecisionTree tree : trees) {
      double[] treeImp = tree.computeFeatureImportances(numFeatures);
      for (int i = 0; i < numFeatures; i++) {
        imp[i] += treeImp[i];
      }
    }
    double sum = 0.0;
    for (double v : imp) {
      sum += v;
    }
    if (sum > 0.0) {
      for (int i = 0; i < numFeatures; i++) {
        imp[i] /= sum;
      }
    }
    this.featureImportance = imp;
  }

  int getNumFeatures() {
    return numFeatures;
  }

  private static int argmax(int[] arr) {
    int best = 0;
    for (int i = 1; i < arr.length; i++) {
      if (arr[i] > arr[best]) {
        best = i;
      }
    }
    return best;
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
}
