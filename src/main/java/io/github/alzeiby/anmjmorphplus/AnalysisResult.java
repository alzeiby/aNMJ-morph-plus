package io.github.alzeiby.anmjmorphplus;

final class AnalysisResult {
    final int imageWidth;
    final int imageHeight;
    final double pixelSizeX;
    final double pixelSizeY;
    final String sizeUnit;
    final String inputTitle;
    final String thresholdMethodNerveTerminal;
    final String thresholdMethodEndplate;
    final double axonDiameter;
    final double nerveTerminalPerimeter;
    final double nerveTerminalArea;
    final double counts0;
    final double counts2;
    final double counts4;
    final double counts5;
    final double totalLengthOfBranches;
    final double achrPerimeter;
    final double achrArea;
    final double endplateDiameter;
    final double endplatePerimeter;
    final double endplateArea;
    final double unoccupiedAchrArea;
    final boolean imageAlright;
    final double numberOfClusters;

    AnalysisResult(
        final int imageWidth,
        final int imageHeight,
        final double pixelSizeX,
        final double pixelSizeY,
        final String sizeUnit,
        final String inputTitle,
        final String thresholdMethodNerveTerminal,
        final String thresholdMethodEndplate,
        final double axonDiameter,
        final double nerveTerminalPerimeter,
        final double nerveTerminalArea,
        final double counts0,
        final double counts2,
        final double counts4,
        final double counts5,
        final double totalLengthOfBranches,
        final double achrPerimeter,
        final double achrArea,
        final double endplateDiameter,
        final double endplatePerimeter,
        final double endplateArea,
        final double unoccupiedAchrArea,
        final boolean imageAlright,
        final double numberOfClusters
    ) {
        this.imageWidth = imageWidth;
        this.imageHeight = imageHeight;
        this.pixelSizeX = pixelSizeX;
        this.pixelSizeY = pixelSizeY;
        this.sizeUnit = sizeUnit;
        this.inputTitle = inputTitle;
        this.thresholdMethodNerveTerminal = thresholdMethodNerveTerminal;
        this.thresholdMethodEndplate = thresholdMethodEndplate;
        this.axonDiameter = axonDiameter;
        this.nerveTerminalPerimeter = nerveTerminalPerimeter;
        this.nerveTerminalArea = nerveTerminalArea;
        this.counts0 = counts0;
        this.counts2 = counts2;
        this.counts4 = counts4;
        this.counts5 = counts5;
        this.totalLengthOfBranches = totalLengthOfBranches;
        this.achrPerimeter = achrPerimeter;
        this.achrArea = achrArea;
        this.endplateDiameter = endplateDiameter;
        this.endplatePerimeter = endplatePerimeter;
        this.endplateArea = endplateArea;
        this.unoccupiedAchrArea = unoccupiedAchrArea;
        this.imageAlright = imageAlright;
        this.numberOfClusters = numberOfClusters;
    }
}
