package com.maxdemarzi.pagerank;

public interface PageRank extends Algorithm {
    double ALPHA = 0.85;
    double ONE_MINUS_ALPHA = 1 - ALPHA;

    void compute(int iterations, String relProperty, float relMaxValue);
    double getResult(long node);
    long numberOfNodes();
    String getPropertyName();
}