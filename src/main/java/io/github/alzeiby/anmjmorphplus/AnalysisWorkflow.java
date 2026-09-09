package io.github.alzeiby.anmjmorphplus;

import ij.IJ;
import ij.ImagePlus;
import ij.Prefs;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.gui.NonBlockingGenericDialog;
import ij.gui.Toolbar;
import ij.measure.ResultsTable;
import ij.plugin.ChannelArranger;
import ij.plugin.ChannelSplitter;
import ij.plugin.Concatenator;
import ij.plugin.ZProjector;
import ij.plugin.filter.Analyzer;
import ij.plugin.frame.ThresholdAdjuster;
import ij.plugin.tool.BrushTool;

import java.awt.Color;
import java.awt.Window;
import java.nio.file.Path;
import java.util.function.Consumer;

final class AnalysisWorkflow {

    interface ReviewPrompter {
        void review(ImagePlus subject, String message);
        boolean confirmSegmentation(String message);
    }

    private static final String SCREEN2 = "2/7 Threshold Nerve terminal. \nUse the reference image as a guide to selecting the appropriate threshold. \nSelect a pre-set threshold from the drop down menu or adjust manually (top scroll bar). \n-\nHint: If you make any mistakes start macro again ('esc' exits macro). \n-\nErase Background Noise. \nPaintbrush tool is already selected. \nBrush width can be adjusted by double-clicking on brush icon. \n-\nPress OK.";
    private static final String SCREEN3 = "3/7 Threshold Muscle Endplate. \nUse the reference image as a guide to selecting appropriate threshold. \nSelect a pre-set threshold from the drop-down menu or adjust manually (top scroll bar). \n-\nErase Background Noise. \nPaintbrush tool is already selected. \nBrush width can be adjusted by double clicking on brush icon. \n-\nPress OK. ";
    private static final String SCREEN4 = "Screen 4/7 Measuring Axon Width. \nLine tool is already selected. \nMeasure by drawing line and pressing 'M' on keyboard. \nThree measurements are required: \n   1) Maximum axon width \n   2) Minimum axon width \n   3) Axon hillock width \n-\nHint: Axon hillock is where axon meets nerve terminals. \nHint: Zoom in for accurate measurement of thin axons. \nPress 'ctrl' and '+' or '-' to zoom in or out. \n-\nNotes: Axon width will be recorded automatically using the average of the three measurements. \nEnsure that extra measurements are deleted from 'results tab' before pressing OK. \nIf no axon is present, press OK. \n-\nPress OK.";
    private static final String SCREEN5 = "Screen 5/7 Erase Axon \nPaintbrush tool is already selected. \nBrush width can be adjusted by double-clicking on brush icon. \n-\nPress OK. \nRemainder of the 'aNMJ-morph' macro will run automatically.";
    private static final String SCREEN6 = "Screen 6/7 Check segmented image. \nDoes this image look similar to the reference image? \nIf not, please untick the checkbox below. \n-\nHint: Do not press 'cancel' as this will stop the macro running to completion. \nNotes: See manuscript and instruction video for examples of incorrect segmentation. \n-\nPress OK.";

    private final ReviewPrompter prompter;
    private int paintbrushToolId = -1;

    AnalysisWorkflow() {
        this(new ImageJReviewPrompter());
    }

    AnalysisWorkflow(final ReviewPrompter prompter) {
        this.prompter = prompter;
    }

    void analyze(
        final ImagePlus image,
        final Path inputPath,
        final BatchChoiceResolver.ChannelChoice channelChoice,
        final Consumer<AnalysisResult> resultConsumer
    ) {
        final AnalysisOutputPaths paths = AnalysisOutputPaths.forInput(inputPath);
        paths.ensureCleanedDirectory();
        final int width = image.getWidth();
        final int height = image.getHeight();
        final double pixelSizeX = image.getCalibration().pixelWidth;
        final double pixelSizeY = image.getCalibration().pixelHeight;
        final String sizeUnit = image.getCalibration().getUnits();

        if (pixelSizeX != pixelSizeY) {
            prompter.review(image, "WARNING: The image has a different scale on the X and Y axes. The results may not be accurate!");
        }
        clearResults();
        installPaintbrush(100);

        final ImagePlus sourceCopy = duplicate(image, "__aNMJ_source_" + image.getID());
        final ImagePlus templateCopy = duplicate(sourceCopy, "__aNMJ_template_" + sourceCopy.getID());
        final Channels original = splitSelected(image, channelChoice);
        final Channels template = splitSelected(templateCopy, channelChoice);

        selectPaintbrush();
        Toolbar.setForegroundColor(Color.BLACK);

        makeCurrent(original.nerve);
        IJ.run(original.nerve, "Threshold...", "");
        prompter.review(original.nerve, SCREEN2);
        final String thresholdNerve = ThresholdAdjuster.getMethod();
        Prefs.blackBackground = false;
        IJ.run(original.nerve, "Make Binary", "thresholded remaining black");
        IJ.run(original.nerve, "Despeckle", "");
        IJ.saveAs(original.nerve, "Tiff", paths.axon.toString());
        closeImage(template.nerve);
        closeThreshold();

        makeCurrent(original.muscle);
        IJ.run(original.muscle, "Threshold...", "");
        prompter.review(original.muscle, SCREEN3);
        final String thresholdEndplate = ThresholdAdjuster.getMethod();
        IJ.run(original.muscle, "Make Binary", "thresholded remaining black");
        IJ.run(original.muscle, "Despeckle", "");
        closeThreshold();
        IJ.saveAs(original.muscle, "Tiff", paths.endplate.toString());
        closeImage(template.muscle);

        Toolbar.getInstance().setTool(4);
        makeCurrent(original.nerve);
        IJ.run("Set Measurements...", "  redirect=None decimal=8");
        prompter.review(original.nerve, SCREEN4);
        final double axonDiameter = meanLength(Analyzer.getResultsTable());
        clearResults();

        selectPaintbrush();
        Toolbar.setForegroundColor(Color.WHITE);
        makeCurrent(original.nerve);
        prompter.review(original.nerve, SCREEN5);

        IJ.run("Set Measurements...", "area mean min perimeter feret's redirect=None decimal=8");
        IJ.run(original.nerve, "Create Selection", "");
        final AnalysisResult.Measurement nerveMeasurement = measure(original.nerve);

        IJ.run(original.nerve, "Make Binary", "thresholded remaining black");
        IJ.run(original.nerve, "Convert to Mask", "");
        IJ.run(original.nerve, "Skeletonize", "");
        final double branchPixelSize = AnisotropicSkeletonCalibration.effectivePixelSize(original.nerve);
        IJ.run(original.nerve, "BinaryConnectivity ", "white");
        final int[] histogram = original.nerve.getProcessor().getHistogram();
        final double counts0 = histogram[0];
        final double counts2 = histogram[2];
        final double counts4 = histogram[4];
        final double counts5 = histogram[5];
        final double totalLengthOfBranches = ((double) width * height - counts0) * branchPixelSize;
        closeImage(original.nerve);

        IJ.run(original.muscle, "Create Selection", "");
        final AnalysisResult.Measurement achrMeasurement = measure(original.muscle);
        makeCurrent(original.muscle);
        IJ.run(original.muscle, "Subtract Background...", "rolling=50 create");
        final ImagePlus intermediate = original.muscle;
        IJ.run(intermediate, "Make Binary", "thresholded remaining black");
        IJ.run(intermediate, "Create Selection", "");
        final AnalysisResult.Measurement endplateMeasurement = measure(intermediate);
        IJ.saveAs(intermediate, "Tiff", paths.endplateIntermediate.toString());
        closeImage(intermediate);
        closeImage(original.muscle);

        final ImagePlus reopenedAxon = openTiff(paths.axon);
        final ImagePlus reopenedEndplate = openTiff(paths.endplate);
        IJ.run(reopenedEndplate, "Invert", "");
        final Concatenator overlapConcatenator = new Concatenator();
        overlapConcatenator.setIm5D(false);
        final ImagePlus overlapConcat = overlapConcatenator.concatenate(
            new ImagePlus[] {reopenedAxon, reopenedEndplate}, false
        );
        overlapConcat.setTitle("Concatenated Stacks");
        overlapConcat.show();
        final ImagePlus overlapAverage = ZProjector.run(overlapConcat, "avg");
        overlapAverage.setTitle("AVG_Concatenated Stacks");
        overlapAverage.show();
        IJ.run(overlapAverage, "Make Binary", "");
        IJ.run(overlapAverage, "Create Selection", "");
        final AnalysisResult.Measurement unoccupiedMeasurement = measure(overlapAverage);
        clearResults();
        closeImage(overlapConcat);
        closeImage(overlapAverage);

        final ImagePlus segmentCopy = duplicate(sourceCopy, "__aNMJ_segment_" + sourceCopy.getID());
        final Channels segment = splitSelected(segmentCopy, channelChoice);
        closeImage(segment.nerve);
        IJ.run(segment.muscle, "Make Binary", "");
        IJ.run(segment.muscle, "Find Maxima...", "noise=10 output=[Segmented Particles]");
        final ImagePlus segmented = currentRequired("Find Maxima");
        IJ.run(segmented, "Invert", "");
        final boolean imageAlright = prompter.confirmSegmentation(SCREEN6);
        IJ.run(segmented, "Fill Holes", "");

        final ImagePlus reopenedIntermediate = openTiff(paths.endplateIntermediate);
        final Concatenator stage6Concatenator = new Concatenator();
        stage6Concatenator.setIm5D(true);
        final ImagePlus finalConcat = stage6Concatenator.concatenate(
            new ImagePlus[] {segmented, reopenedIntermediate}, false
        );
        finalConcat.setTitle("Concatenated Stacks");
        finalConcat.show();
        final ImagePlus finalAverage = ZProjector.run(finalConcat, "avg");
        finalAverage.setTitle("AVG_Concatenated Stacks");
        finalAverage.show();
        Prefs.blackBackground = false;
        IJ.run(finalAverage, "Make Binary", "thresholded remaining black");
        IJ.run(finalAverage, "Analyze Particles...", "display summarize");
        final double numberOfClusters = summaryCount();
        clearResults();
        closeWindow("Summary");
        closeImage(sourceCopy);
        closeImage(segment.muscle);
        closeImage(segmented);
        closeImage(reopenedIntermediate);

        final AnalysisResult result = new AnalysisResult(
            width,
            height,
            pixelSizeX,
            pixelSizeY,
            sizeUnit,
            inputPath.getFileName().toString(),
            thresholdNerve + "/" + thresholdEndplate,
            axonDiameter,
            counts0,
            counts2,
            counts4,
            counts5,
            totalLengthOfBranches,
            nerveMeasurement,
            achrMeasurement,
            endplateMeasurement,
            unoccupiedMeasurement,
            imageAlright,
            numberOfClusters
        );
        resultConsumer.accept(result);
        closeImage(finalConcat);
        closeImage(finalAverage);
    }

    private static ImagePlus duplicate(final ImagePlus source, final String title) {
        IJ.run(source, "Duplicate...", "title=[" + title + "] duplicate");
        return currentRequired("Duplicate");
    }

    private static Channels splitSelected(
        final ImagePlus source,
        final BatchChoiceResolver.ChannelChoice choice
    ) {
        ImagePlus selected = source;
        if (source.getNChannels() <= 9 &&
            (source.getNChannels() != 2 || choice.muscleEndplateChannel != 1 || choice.nerveTerminalChannel != 2)) {
            selected = ChannelArranger.run(source,
                new int[] {choice.muscleEndplateChannel, choice.nerveTerminalChannel});
            if (selected == null) throw new IllegalStateException("Could not arrange selected channels");
            selected.setCalibration(source.getCalibration());
        }
        final ImagePlus[] split = ChannelSplitter.split(selected);
        if (selected.getWindow() != null) closeImage(selected);
        split[0].show();
        split[1].show();
        return new Channels(split[0], split[1]);
    }

    private static AnalysisResult.Measurement measure(final ImagePlus image) {
        final ResultsTable table = Analyzer.getResultsTable();
        IJ.run(image, "Measure", "");
        final int row = table.size() - 1;
        return new AnalysisResult.Measurement(
            table.getValue("Area", row),
            table.getValue("Perim.", row),
            table.getValue("Feret", row)
        );
    }

    private static double meanLength(final ResultsTable table) {
        if (table == null || table.size() == 0) {
            return Double.NaN;
        }
        double sum = 0.0;
        for (int row = 0; row < table.size(); row++) {
            sum += table.getValue("Length", row);
        }
        return sum / table.size();
    }

    private static double summaryCount() {
        final ResultsTable summary = ResultsTable.getResultsTable("Summary");
        return summary.getValue("Count", summary.size() - 1);
    }

    private static ImagePlus openTiff(final Path path) {
        final ImagePlus image = IJ.openImage(path.toString());
        image.show();
        makeCurrent(image);
        return image;
    }

    private static ImagePlus currentRequired(final String operation) {
        final ImagePlus current = WindowManager.getCurrentImage();
        if (current == null) {
            throw new IllegalStateException(operation + " did not leave a current image");
        }
        return current;
    }

    private static void makeCurrent(final ImagePlus image) {
        if (image.getWindow() != null) {
            IJ.selectWindow(image.getID());
        } else {
            WindowManager.setTempCurrentImage(image);
        }
    }

    private static void closeThreshold() {
        final Window threshold = WindowManager.getWindow("Threshold");
        if (threshold instanceof ThresholdAdjuster) {
            ((ThresholdAdjuster) threshold).close();
        }
    }

    private static void clearResults() {
        Analyzer.setUnsavedMeasurements(false);
        Analyzer.resetCounter();
        closeWindow("Results");
    }

    private void installPaintbrush(final int width) {
        new BrushTool().run("");
        paintbrushToolId = Toolbar.getToolId();
        BrushTool.setBrushWidth(width);
    }

    private void selectPaintbrush() {
        Toolbar.getInstance().setTool(paintbrushToolId);
    }

    private static void closeWindow(final String title) {
        final Window window = WindowManager.getWindow(title);
        if (window != null) {
            window.dispose();
        }
    }

    private static void closeImage(final ImagePlus image) {
        if (image != null) {
            image.changes = false;
            image.close();
        }
    }

    private static final class Channels {
        final ImagePlus muscle;
        final ImagePlus nerve;

        Channels(final ImagePlus muscle, final ImagePlus nerve) {
            this.muscle = muscle;
            this.nerve = nerve;
        }
    }

    private static final class ImageJReviewPrompter implements ReviewPrompter {
        @Override
        public void review(final ImagePlus subject, final String message) {
            makeCurrent(subject);
            final GenericDialog dialog = NonBlockingGenericDialog.newDialog("Action Required", subject);
            dialog.addMessage(message);
            dialog.showDialog();
            if (dialog.wasCanceled() || IJ.escapePressed()) {
                throw new AnalysisCancelledException();
            }
            IJ.wait(100);
            makeCurrent(subject);
        }

        @Override
        public boolean confirmSegmentation(final String message) {
            final GenericDialog dialog = new GenericDialog("Is this image OK?");
            dialog.addMessage(message);
            dialog.addCheckbox("Image looks OK", true);
            dialog.showDialog();
            if (dialog.wasCanceled() || IJ.escapePressed()) {
                throw new AnalysisCancelledException();
            }
            return dialog.getNextBoolean();
        }
    }
}
