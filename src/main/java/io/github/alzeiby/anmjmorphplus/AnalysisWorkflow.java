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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

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
        final int muscleChannel,
        final int nerveChannel
    ) {
        final Path parent = inputPath.toAbsolutePath().normalize().getParent();
        final String name = inputPath.getFileName().toString();
        final int dot = name.lastIndexOf('.');
        final String stem = dot > 0 ? name.substring(0, dot) : name;
        final Path cleaned = parent.resolve("cleaned_images");
        try {
            Files.createDirectories(cleaned);
        } catch (IOException e) {
            throw new IllegalStateException("Could not create cleaned_images directory", e);
        }
        final Path axonPath = cleaned.resolve("axon_terminal" + stem + ".tif");
        final Path endplatePath = cleaned.resolve("muscle_endplate" + stem + ".tif");
        final Path intermediatePath = cleaned.resolve("muscle_intermediate_endplate" + stem + ".tif");
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
        final Channels original = splitSelected(image, muscleChannel, nerveChannel);
        final Channels template = splitSelected(templateCopy, muscleChannel, nerveChannel);

        selectPaintbrush();
        Toolbar.setForegroundColor(Color.BLACK);

        makeCurrent(original.nerve);
        IJ.run(original.nerve, "Threshold...", "");
        prompter.review(original.nerve, SCREEN2);
        final String thresholdNerve = ThresholdAdjuster.getMethod();
        Prefs.blackBackground = false;
        IJ.run(original.nerve, "Make Binary", "thresholded remaining black");
        IJ.run(original.nerve, "Despeckle", "");
        IJ.saveAs(original.nerve, "Tiff", axonPath.toString());
        closeImage(template.nerve);
        closeThreshold();

        makeCurrent(original.muscle);
        IJ.run(original.muscle, "Threshold...", "");
        prompter.review(original.muscle, SCREEN3);
        final String thresholdEndplate = ThresholdAdjuster.getMethod();
        IJ.run(original.muscle, "Make Binary", "thresholded remaining black");
        IJ.run(original.muscle, "Despeckle", "");
        closeThreshold();
        IJ.saveAs(original.muscle, "Tiff", endplatePath.toString());
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
        final double[] nerveMeasurement = measure(original.nerve);

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
        final double[] achrMeasurement = measure(original.muscle);
        makeCurrent(original.muscle);
        IJ.run(original.muscle, "Subtract Background...", "rolling=50 create");
        final ImagePlus intermediate = original.muscle;
        IJ.run(intermediate, "Make Binary", "thresholded remaining black");
        IJ.run(intermediate, "Create Selection", "");
        final double[] endplateMeasurement = measure(intermediate);
        IJ.saveAs(intermediate, "Tiff", intermediatePath.toString());
        closeImage(intermediate);
        closeImage(original.muscle);

        final ImagePlus reopenedAxon = openTiff(axonPath);
        final ImagePlus reopenedEndplate = openTiff(endplatePath);
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
        final double[] unoccupiedMeasurement = measure(overlapAverage);
        clearResults();
        closeImage(overlapConcat);
        closeImage(overlapAverage);

        final ImagePlus segmentCopy = duplicate(sourceCopy, "__aNMJ_segment_" + sourceCopy.getID());
        final Channels segment = splitSelected(segmentCopy, muscleChannel, nerveChannel);
        closeImage(segment.nerve);
        IJ.run(segment.muscle, "Make Binary", "");
        IJ.run(segment.muscle, "Find Maxima...", "noise=10 output=[Segmented Particles]");
        final ImagePlus segmented = currentRequired("Find Maxima");
        IJ.run(segmented, "Invert", "");
        final boolean imageAlright = prompter.confirmSegmentation(SCREEN6);
        IJ.run(segmented, "Fill Holes", "");

        final ImagePlus reopenedIntermediate = openTiff(intermediatePath);
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

        final Path csv = parent.resolve("raw_data_table.csv");
        final int row = prepareCsv(csv);
        final String[] fields = {
            quote(width + " x " + height),
            quote(format(pixelSizeX * width) + " x " + format(pixelSizeY * height) + sizeUnit),
            inputPath.getFileName().toString(), thresholdNerve + "/" + thresholdEndplate, "", format(axonDiameter),
            format(nerveMeasurement[1]), format(nerveMeasurement[0]), format(counts0), format(counts2), format(counts4),
            format(counts5), quote("=J" + row), quote("=(K" + row + "+L" + row + ")*0.28"),
            format(totalLengthOfBranches), quote("=O" + row + "/J" + row),
            quote("=LOG10(M" + row + "*N" + row + "*O" + row + ")"), format(achrMeasurement[1]),
            format(achrMeasurement[0]), format(endplateMeasurement[2]), format(endplateMeasurement[1]),
            format(endplateMeasurement[0]), quote("=S" + row + "/V" + row + "*100"), format(unoccupiedMeasurement[0]),
            quote("=S" + row + "-X" + row), quote("=(S" + row + "-X" + row + ")/S" + row + "*100"),
            imageAlright ? format(numberOfClusters) : "", quote("=IF(AA" + row + ",S" + row + "/AA" + row + ",\"\")"),
            quote("=IF(AA" + row + ",1-1/AA" + row + ",\"\")")
        };
        write(csv, String.join(",", fields) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        closeImage(finalConcat);
        closeImage(finalAverage);
    }

    private static ImagePlus duplicate(final ImagePlus source, final String title) {
        IJ.run(source, "Duplicate...", "title=[" + title + "] duplicate");
        return currentRequired("Duplicate");
    }

    private static Channels splitSelected(
        final ImagePlus source,
        final int muscleChannel,
        final int nerveChannel
    ) {
        ImagePlus selected = source;
        if (source.getNChannels() <= 9 &&
            (source.getNChannels() != 2 || muscleChannel != 1 || nerveChannel != 2)) {
            selected = ChannelArranger.run(source,
                new int[] {muscleChannel, nerveChannel});
            if (selected == null) throw new IllegalStateException("Could not arrange selected channels");
            selected.setCalibration(source.getCalibration());
        }
        final ImagePlus[] split = ChannelSplitter.split(selected);
        if (selected.getWindow() != null) closeImage(selected);
        split[0].show();
        split[1].show();
        return new Channels(split[0], split[1]);
    }

    private static double[] measure(final ImagePlus image) {
        final ResultsTable table = Analyzer.getResultsTable();
        IJ.run(image, "Measure", "");
        final int row = table.size() - 1;
        return new double[] {
            table.getValue("Area", row),
            table.getValue("Perim.", row),
            table.getValue("Feret", row)
        };
    }

    private static int prepareCsv(final Path csv) {
        try {
            if (!Files.exists(csv) || Files.size(csv) == 0) {
                write(csv, CSV_HEADER_1 + "\n" + CSV_HEADER_2 + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                return 3;
            }
            final byte[] contents = Files.readAllBytes(csv);
            if (contents.length > 0 && contents[contents.length - 1] != '\n') {
                write(csv, "\n", StandardOpenOption.APPEND);
            }
            return Files.readAllLines(csv).size() + 1;
        } catch (IOException e) {
            throw new IllegalStateException("Could not prepare CSV output: " + csv, e);
        }
    }

    private static String format(final double value) {
        return IJ.d2s(value, 8);
    }

    private static String quote(final String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static void write(final Path path, final String text, final StandardOpenOption... options) {
        try {
            Files.writeString(path, text, options);
        } catch (IOException e) {
            throw new IllegalStateException("Could not write CSV output: " + path, e);
        }
    }

    private static final String CSV_HEADER_1 =
        "IMAGE DETAILS (frame size),,NMJ,THRESHOLD,PRE-SYNAPTIC,,,,Branch analysis,,,,,,,,,POST-SYNAPTIC";
    private static final String CSV_HEADER_2 =
        "\"Number of pixels (eg, 512 x 512)\",\"Metric (eg, 67.48 x 67.48um)\",\"Ref number\",\"(nerve terminal/motor endplate)\",\"Number of Axonal Inputs\",\"Axon Diameter (um)\",\"Nerve Terminal Perimeter (um)\",\"Nerve Terminal Area (um2)\",\"Value 0 (background white pixels)\",\"Value 2 (terminal pixels)\",\"Value 4 (three-point branch pixels)\",\"Value 5 (four-point branch pixels)\",\" Number of Terminal Branches\",\"Number of Branch Points\",\"Total Length of Branches (um)\",\"Average Length of Branches (um)\",\"\"\"Complexity\"\"\",\"AChR Perimeter (um)\",\"AChR Area (um2)\",\"Endplate Diameter (um)\",\"Endplate Perimeter (um)\",\"Endplate Area (um2)\",\"\"\"Compactness\"\" (%)\",\"Unoccupied AChR Area (um2)\",\"\"\"Area of Synaptic Contact\"\" (um2)\",\"\"\"Overlap\"\" (%)\",\"Number of AChR Clusters\",\"Average Area of AChR Clusters (um2)\",\"\"\"Fragmentation\"\"\"";

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
                throw new ANMJMorphCommand.Cancelled();
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
                throw new ANMJMorphCommand.Cancelled();
            }
            return dialog.getNextBoolean();
        }
    }
}
