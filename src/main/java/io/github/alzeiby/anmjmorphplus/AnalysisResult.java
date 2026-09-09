package io.github.alzeiby.anmjmorphplus;

final class AnalysisResult {
    final int imageWidth;
    final int imageHeight;
    final double pixelSizeX;
    final double pixelSizeY;
    final String sizeUnit;
    final String inputTitle;
    final String thresholdMethods;
    final double axonDiameter;
    final double counts0;
    final double counts2;
    final double counts4;
    final double counts5;
    final double totalLengthOfBranches;
    final Measurement nerve;
    final Measurement achr;
    final Measurement endplate;
    final Measurement unoccupied;
    final boolean imageAlright;
    final double numberOfClusters;

    AnalysisResult(
        final int imageWidth,
        final int imageHeight,
        final double pixelSizeX,
        final double pixelSizeY,
        final String sizeUnit,
        final String inputTitle,
        final String thresholdMethods,
        final double axonDiameter,
        final double counts0,
        final double counts2,
        final double counts4,
        final double counts5,
        final double totalLengthOfBranches,
        final Measurement nerve,
        final Measurement achr,
        final Measurement endplate,
        final Measurement unoccupied,
        final boolean imageAlright,
        final double numberOfClusters
    ) {
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
        this.pixelSizeX = pixelSizeX;
        this.pixelSizeY = pixelSizeY;
        this.sizeUnit = sizeUnit;
        this.inputTitle = inputTitle;
        this.thresholdMethods = thresholdMethods;
        this.axonDiameter = axonDiameter;
        this.counts0 = counts0;
        this.counts2 = counts2;
        this.counts4 = counts4;
        this.counts5 = counts5;
        this.totalLengthOfBranches = totalLengthOfBranches;
        this.nerve = nerve;
        this.achr = achr;
        this.endplate = endplate;
        this.unoccupied = unoccupied;
        this.imageAlright = imageAlright;
        this.numberOfClusters = numberOfClusters;
    }

    static final class Measurement {
        final double area;
        final double perimeter;
        final double feret;

        Measurement(final double area, final double perimeter, final double feret) {
            this.area = area;
            this.perimeter = perimeter;
            this.feret = feret;
        }
    }
}
