package com.maxdemarzi.pagerank;

public interface PageRank extends Algorithm {
    double ALPHA = 0.85;
    double ONE_MINUS_ALPHA = 1 - ALPHA;

    void compute(int iterations, String relProperty, float relMaxValue);
    @Override
    double getResult(long node);
    @Override
    long numberOfNodes();
    @Override
    String getPropertyName();
}