package io.github.alzeiby.anmjmorphplus;

import ij.IJ;
import ij.ImagePlus;
import ij.Prefs;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.gui.NonBlockingGenericDialog;
import ij.gui.Toolbar;
import ij.measure.ResultsTable;
import ij.plugin.Concatenator;
import ij.plugin.ZProjector;
import ij.plugin.filter.Analyzer;
import ij.plugin.frame.ThresholdAdjuster;
import ij.plugin.tool.BrushTool;

import java.awt.Color;
import java.awt.Window;
import java.nio.file.Path;

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

    AnalysisRun analyze(
        final ImagePlus image,
        final Path inputPath,
        final BatchChoiceResolver.ChannelChoice channelChoice,
        final boolean channelsCanonical
    ) {
        final AnalysisOutputPaths paths = AnalysisOutputPaths.forInput(inputPath);
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
        final Channels original = splitSelected(image, channelChoice, channelsCanonical);
        final Channels template = splitSelected(templateCopy, channelChoice, channelsCanonical);

        selectPaintbrush();
        Toolbar.setForegroundColor(Color.BLACK);

        makeCurrent(original.nerve);
        IJ.run(original.nerve, "Threshold...", "");
        positionPair(original.nerve, template.nerve);
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
        positionPair(original.muscle, template.muscle);
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
        final Measurement nerveMeasurement = measure(original.nerve);

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
        final Measurement achrMeasurement = measure(original.muscle);
        makeCurrent(original.muscle);
        IJ.run(original.muscle, "Subtract Background...", "rolling=50 create");
        final ImagePlus intermediate = original.muscle;
        IJ.run(intermediate, "Make Binary", "thresholded remaining black");
        IJ.run(intermediate, "Create Selection", "");
        final Measurement endplateMeasurement = measure(intermediate);
        IJ.saveAs(intermediate, "Tiff", paths.endplateIntermediate.toString());
        closeImage(intermediate);
        closeImage(original.muscle);

        final ImagePlus reopenedAxon = openTiff(paths.axon, paths.axon.getFileName().toString());
        final ImagePlus reopenedEndplate = openTiff(paths.endplate, paths.endplate.getFileName().toString());
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
        final Measurement unoccupiedMeasurement = measure(overlapAverage);
        clearResults();
        closeImage(overlapConcat);
        closeImage(overlapAverage);

        final ImagePlus segmentCopy = duplicate(sourceCopy, "__aNMJ_segment_" + sourceCopy.getID());
        final Channels segment = splitSelected(segmentCopy, channelChoice, channelsCanonical);
        closeImage(segment.nerve);
        IJ.run(segment.muscle, "Make Binary", "");
        IJ.run(segment.muscle, "Find Maxima...", "noise=10 output=[Segmented Particles]");
        final ImagePlus segmented = currentRequired("Find Maxima");
        IJ.run(segmented, "Invert", "");
        final boolean imageAlright = prompter.confirmSegmentation(SCREEN6);
        IJ.run(segmented, "Fill Holes", "");

        final ImagePlus reopenedIntermediate = openTiff(
            paths.endplateIntermediate,
            paths.endplateIntermediate.getFileName().toString()
        );
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
            thresholdNerve,
            thresholdEndplate,
            axonDiameter,
            nerveMeasurement.perimeter,
            nerveMeasurement.area,
            counts0,
            counts2,
            counts4,
            counts5,
            totalLengthOfBranches,
            achrMeasurement.perimeter,
            achrMeasurement.area,
            endplateMeasurement.feret,
            endplateMeasurement.perimeter,
            endplateMeasurement.area,
            unoccupiedMeasurement.area,
            imageAlright,
            numberOfClusters
        );
        return new AnalysisRun(result, finalConcat, finalAverage);
    }

    void finish(final AnalysisRun run) {
        prompter.review(
            run.finalAverage,
            "7/7 Congratulations- 'aNMJ-morph is now complete! \n-\nPress OK. \nImages will be closed down and measurements will be saved onto a raw_data_table document."
        );
        closeImage(run.finalConcatenated);
        closeImage(run.finalAverage);
    }

    private static ImagePlus duplicate(final ImagePlus source, final String title) {
        IJ.run(source, "Duplicate...", "title=[" + title + "] duplicate");
        final ImagePlus copy = currentRequired("Duplicate");
        if (copy == source || !title.equals(copy.getTitle())) {
            throw new IllegalStateException("ImageJ Duplicate... did not create expected image " + title);
        }
        return copy;
    }

    private static Channels splitSelected(
        final ImagePlus source,
        final BatchChoiceResolver.ChannelChoice choice,
        final boolean channelsCanonical
    ) {
        makeCurrent(source);
        ImagePlus selected = source;
        if (!channelsCanonical) {
            IJ.run(source, "Arrange Channels...", "new=" +
                choice.muscleEndplateChannel() + choice.nerveTerminalChannel());
            selected = currentRequired("Arrange Channels");
        }
        selected.setC(1);
        final String title = selected.getTitle();
        IJ.run(selected, "Split Channels", "");
        final ImagePlus muscle = WindowManager.getImage("C1-" + title);
        final ImagePlus nerve = WindowManager.getImage("C2-" + title);
        if (muscle == null || nerve == null) {
            throw new IllegalStateException("ImageJ Split Channels did not create C1/C2 for " + title);
        }
        return new Channels(muscle, nerve);
    }

    private static Measurement measure(final ImagePlus image) {
        final ResultsTable table = Analyzer.getResultsTable();
        final int before = table.size();
        IJ.run(image, "Measure", "");
        if (table.size() <= before) {
            throw new IllegalStateException("ImageJ Measure did not append a Results row");
        }
        final int row = table.size() - 1;
        return new Measurement(
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
        if (summary == null || summary.size() == 0) {
            throw new IllegalStateException("Analyze Particles did not create Summary results");
        }
        return summary.getValue("Count", summary.size() - 1);
    }

    private static ImagePlus openTiff(final Path path, final String title) {
        final ImagePlus image = IJ.openImage(path.toString());
        if (image == null) {
            throw new IllegalStateException("Could not open intermediate TIFF: " + path);
        }
        image.setTitle(title);
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

    private static void positionPair(final ImagePlus subject, final ImagePlus reference) {
        if (subject.getWindow() == null || reference.getWindow() == null) {
            return;
        }
        final int width = subject.getWindow().getWidth();
        subject.getWindow().setLocation(50, 50);
        reference.getWindow().setLocation(100 + width, 50);
        makeCurrent(subject);
    }

    private static void makeCurrent(final ImagePlus image) {
        if (image == null) {
            throw new IllegalStateException("Cannot select a closed image");
        }
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
        final Toolbar toolbar = Toolbar.getInstance();
        if (toolbar == null) {
            throw new IllegalStateException("ImageJ toolbar is unavailable");
        }
        // Use ImageJ's built-in paintbrush PlugInTool directly. The legacy macro's
        // Paintbrush Tool Options command belongs to StartupMacros and is not guaranteed
        // to be installed in a clean Fiji runtime.
        if (!isOurPaintbrushSelected(toolbar)) {
            new BrushTool().run("");
            paintbrushToolId = Toolbar.getToolId();
        }
        BrushTool.setBrushWidth(width);
        selectPaintbrush();
    }

    private void selectPaintbrush() {
        final Toolbar toolbar = Toolbar.getInstance();
        if (toolbar == null) {
            throw new IllegalStateException("ImageJ toolbar is unavailable");
        }
        if (paintbrushToolId < 0) {
            new BrushTool().run("");
            paintbrushToolId = Toolbar.getToolId();
        }
        toolbar.setTool(paintbrushToolId);
        if (!(Toolbar.getPlugInTool() instanceof BrushTool)) {
            new BrushTool().run("");
            paintbrushToolId = Toolbar.getToolId();
            toolbar.setTool(paintbrushToolId);
        }
        if (!(Toolbar.getPlugInTool() instanceof BrushTool)) {
            throw new IllegalStateException("ImageJ Paintbrush Tool could not be installed");
        }
    }

    private boolean isOurPaintbrushSelected(final Toolbar toolbar) {
        if (paintbrushToolId < 0) {
            return false;
        }
        toolbar.setTool(paintbrushToolId);
        return Toolbar.getPlugInTool() instanceof BrushTool;
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

    private static final class Measurement {
        final double area;
        final double perimeter;
        final double feret;

        Measurement(final double area, final double perimeter, final double feret) {
            this.area = area;
            this.perimeter = perimeter;
            this.feret = feret;
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
