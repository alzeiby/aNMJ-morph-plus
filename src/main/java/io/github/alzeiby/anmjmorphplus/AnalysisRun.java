package io.github.alzeiby.anmjmorphplus;

import ij.ImagePlus;

final class AnalysisRun {
    final AnalysisResult result;
    final ImagePlus finalConcatenated;
    final ImagePlus finalAverage;

    AnalysisRun(final AnalysisResult result, final ImagePlus finalConcatenated, final ImagePlus finalAverage) {
        this.result = result;
        this.finalConcatenated = finalConcatenated;
        this.finalAverage = finalAverage;
    }
}
