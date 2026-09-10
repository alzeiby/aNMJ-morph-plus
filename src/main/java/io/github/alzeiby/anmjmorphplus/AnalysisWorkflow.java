package io.github.alzeiby.anmjmorphplus;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.LookUpTable;
import ij.Prefs;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.gui.NonBlockingGenericDialog;
import ij.gui.Toolbar;
import ij.measure.Calibration;
import ij.measure.Measurements;
import ij.measure.ResultsTable;
import ij.plugin.ChannelSplitter;
import ij.plugin.ZProjector;
import ij.plugin.filter.Analyzer;
import ij.plugin.filter.BackgroundSubtracter;
import ij.plugin.filter.MaximumFinder;
import ij.plugin.filter.ParticleAnalyzer;
import ij.plugin.filter.RankFilters;
import ij.plugin.filter.ThresholdToSelection;
import ij.plugin.frame.ThresholdAdjuster;
import ij.plugin.tool.BrushTool;
import ij.process.Blitter;
import ij.process.ImageProcessor;
import sc.fiji.analyzeSkeleton.AnalyzeSkeleton_;
import sc.fiji.analyzeSkeleton.Edge;
import sc.fiji.analyzeSkeleton.Graph;
import sc.fiji.analyzeSkeleton.SkeletonResult;
import sc.fiji.analyzeSkeleton.Vertex;

import java.awt.Color;
import java.awt.Window;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

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
        canonicalizeCalibration(image);
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

        final ImagePlus muscle = channel(image, muscleChannel);
        final ImagePlus nerve = channel(image, nerveChannel);
        closeImage(image);
        nerve.show();
        final ImagePlus nerveReference = nerve.duplicate();
        nerveReference.setTitle("Nerve reference");
        nerveReference.show();

        Toolbar.setForegroundColor(Color.BLACK);

        makeCurrent(nerve);
        IJ.run(nerve, "Threshold...", "");
        Toolbar.getInstance().setTool("Paintbrush Tool");
        prompter.review(nerve, SCREEN2);
        final String thresholdNerve = threshold(nerve);
        Prefs.blackBackground = false;
        IJ.run(nerve, "Make Binary", "thresholded remaining black");
        new RankFilters().rank(nerve.getProcessor(), 1.0, RankFilters.MEDIAN);
        IJ.saveAs(nerve, "Tiff", axonPath.toString());
        closeImage(nerveReference);

        final ImagePlus segmentMuscle = muscle.duplicate();
        segmentMuscle.setTitle("Muscle reference");
        muscle.show();
        segmentMuscle.show();
        makeCurrent(muscle);
        IJ.run(muscle, "Threshold...", "");
        Toolbar.getInstance().setTool("Paintbrush Tool");
        prompter.review(muscle, SCREEN3);
        final String thresholdEndplate = threshold(muscle);
        IJ.run(muscle, "Make Binary", "thresholded remaining black");
        new RankFilters().rank(muscle.getProcessor(), 1.0, RankFilters.MEDIAN);
        closeThreshold();
        IJ.saveAs(muscle, "Tiff", endplatePath.toString());
        segmentMuscle.hide();

        Toolbar.getInstance().setTool(Toolbar.LINE);
        makeCurrent(nerve);
        Analyzer.setMeasurements(0);
        Analyzer.setPrecision(8);
        Analyzer.setRedirectImage(null);
        prompter.review(nerve, SCREEN4);
        final ResultsTable widths = Analyzer.getResultsTable();
        if (widths != null && widths.size() != 0 && widths.size() != 3) {
            throw new IllegalStateException("Measure exactly three axon widths, or none when no axon is present");
        }
        final double axonDiameter = meanLength(widths);
        clearResults();

        Toolbar.getInstance().setTool("Paintbrush Tool");
        Toolbar.setForegroundColor(Color.WHITE);
        makeCurrent(nerve);
        prompter.review(nerve, SCREEN5);
        final double unoccupiedAchrArea = unoccupiedArea(muscle, nerve);

        selectForeground(nerve);
        final double[] nerveMeasurement = measure(nerve);

        IJ.run(nerve, "Make Binary", "thresholded remaining black");
        IJ.run(nerve, "Skeletonize", "");
        final AnalyzeSkeleton_ analyzer = new AnalyzeSkeleton_();
        analyzer.setup("", nerve);
        final SkeletonResult skeleton = analyzer.run(AnalyzeSkeleton_.NONE, false, false, null, true, false);
        double totalLengthOfBranches = 0.0;
        int terminalBranches = 0;
        for (Graph graph : skeleton.getGraph()) {
            for (Edge edge : graph.getEdges()) totalLengthOfBranches += edge.getLength();
            for (Vertex vertex : graph.getVertices()) {
                if (vertex.getBranches().size() == 1 &&
                    vertex.getBranches().get(0).getV1() != vertex.getBranches().get(0).getV2()) terminalBranches++;
            }
        }
        final int branchPoints = sum(skeleton.getJunctions());
        final int tripleJunctions = sum(skeleton.getTriples());
        final int quadrupleJunctions = sum(skeleton.getQuadruples());
        closeImage(nerve);

        selectForeground(muscle);
        final double[] achrMeasurement = measure(muscle);
        new BackgroundSubtracter().rollingBallBackground(
            muscle.getProcessor(), 50, true, false, false, true, true
        );
        IJ.run(muscle, "Make Binary", "thresholded remaining black");
        selectForeground(muscle);
        final double[] endplateMeasurement = measure(muscle);

        IJ.run(segmentMuscle, "Make Binary", "");
        if (segmentMuscle.isInvertedLut()) segmentMuscle.getProcessor().invert();
        final ImageProcessor segmentedProcessor = new MaximumFinder().findMaxima(
            segmentMuscle.getProcessor(), 10, false, ImageProcessor.NO_THRESHOLD,
            MaximumFinder.SEGMENTED, false, false);
        if (segmentedProcessor == null) throw new IllegalStateException("Find Maxima did not create a segmented image");
        if (!Prefs.blackBackground) segmentedProcessor.invertLut();
        final ImagePlus segmented = new ImagePlus(segmentMuscle.getTitle() + " Segmented", segmentedProcessor);
        segmented.setCalibration(segmentMuscle.getCalibration());
        segmented.show();
        segmented.getProcessor().invert();
        segmented.updateAndDraw();
        final boolean imageAlright = prompter.confirmSegmentation(SCREEN6);
        segmented.getProcessor().copyBits(muscle.getProcessor(), 0, 0, Blitter.AND);
        final ResultsTable particles = new ResultsTable();
        new ParticleAnalyzer(ParticleAnalyzer.SHOW_NONE, 0, particles, 0, Double.POSITIVE_INFINITY).analyze(segmented);
        final double numberOfClusters = particles.size();
        closeImage(segmentMuscle);
        closeImage(segmented);
        closeImage(muscle);

        final Path csv = parent.resolve("raw_data_table.csv");
        final int row = prepareCsv(csv);
        final String[] fields = {
            quote(width + " x " + height),
            quote(format(pixelSizeX * width) + " x " + format(pixelSizeY * height) + sizeUnit),
            quote(inputPath.getFileName().toString()), thresholdNerve + "/" + thresholdEndplate, "",
            Double.isNaN(axonDiameter) ? "" : format(axonDiameter),
            format(nerveMeasurement[1]), format(nerveMeasurement[0]), format(skeleton.getNumOfTrees()), format(terminalBranches),
            format(tripleJunctions), format(quadrupleJunctions), quote("=J" + row), format(branchPoints),
            format(totalLengthOfBranches), quote("=O" + row + "/M" + row),
            quote("=LOG10(M" + row + "*N" + row + "*O" + row + ")"), format(achrMeasurement[1]),
            format(achrMeasurement[0]), format(endplateMeasurement[2]), format(endplateMeasurement[1]),
            format(endplateMeasurement[0]), quote("=S" + row + "/V" + row + "*100"), format(unoccupiedAchrArea),
            quote("=S" + row + "-X" + row), quote("=(S" + row + "-X" + row + ")/S" + row + "*100"),
            imageAlright ? format(numberOfClusters) : "", quote("=IF(AA" + row + ",S" + row + "/AA" + row + ",\"\")"),
            quote("=IF(AA" + row + ",1-1/AA" + row + ",\"\")")
        };
        write(csv, String.join(",", fields) + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    static void canonicalizeCalibration(final ImagePlus image) {
        final Calibration calibration = image.getCalibration();
        calibration.pixelWidth = microns(calibration.pixelWidth, calibration.getXUnit());
        calibration.pixelHeight = microns(calibration.pixelHeight, calibration.getYUnit());
        calibration.setUnit("micron");
        calibration.setYUnit("micron");
    }

    private static double microns(final double value, final String rawUnit) {
        if (!(value > 0.0) || !Double.isFinite(value)) {
            throw new IllegalArgumentException("Image pixel width and height must be finite and positive");
        }
        final String unit = rawUnit.toLowerCase(Locale.ROOT);
        final double factor;
        switch (unit) {
            case "micron": case "microns": case "um": case "\u00b5m": case "\u03bcm": factor = 1.0; break;
            case "nm": case "nanometer": case "nanometers": factor = 0.001; break;
            case "\u00e5": case "angstrom": case "angstroms": factor = 0.0001; break;
            case "mm": case "millimeter": case "millimeters": factor = 1000.0; break;
            case "cm": case "centimeter": case "centimeters": factor = 10000.0; break;
            case "m": case "meter": case "meters": factor = 1000000.0; break;
            case "inch": case "inches": factor = 25400.0; break;
            default: throw new IllegalArgumentException("Image calibration must use physical length units convertible to microns, not '" + rawUnit + "'");
        }
        final double converted = value * factor;
        if (!Double.isFinite(converted)) throw new IllegalArgumentException("Image calibration is outside the supported range");
        return converted;
    }

    static ImagePlus channel(final ImagePlus source, final int channel) {
        source.setPositionWithoutUpdate(channel, source.getZ(), source.getT());
        final double displayMin = source.getDisplayRangeMin(), displayMax = source.getDisplayRangeMax();
        final ImageStack stack = ChannelSplitter.getChannel(source, channel);
        stack.setColorModel(LookUpTable.createGrayscaleColorModel(source.isInvertedLut()));
        final ImagePlus copy = source.createImagePlus();
        copy.setStack("C" + channel + "-" + source.getTitle(), stack);
        if (copy.getNSlices() == 1) {
            copy.setDisplayRange(displayMin, displayMax);
            return copy;
        }
        final ImagePlus projected = ZProjector.run(copy, "max");
        projected.setDisplayRange(displayMin, displayMax);
        copy.flush();
        return projected;
    }

    private static double unoccupiedArea(final ImagePlus achr, final ImagePlus nerve) {
        final ImageProcessor achrProcessor = achr.getProcessor();
        final ImageProcessor nerveProcessor = nerve.getProcessor();
        final int pixels = achrProcessor.getPixelCount();
        int unoccupied = 0;
        for (int i = 0; i < pixels; i++) {
            if (achrProcessor.get(i) == 0 && nerveProcessor.get(i) != 0) unoccupied++;
        }
        return unoccupied * achr.getCalibration().pixelWidth * achr.getCalibration().pixelHeight;
    }

    private static double[] measure(final ImagePlus image) {
        final ResultsTable table = new ResultsTable();
        new Analyzer(image, Measurements.AREA | Measurements.PERIMETER | Measurements.FERET, table).measure();
        return new double[] {
            table.getValue("Area", 0),
            table.getValue("Perim.", 0),
            table.getValue("Feret", 0)
        };
    }

    private static void selectForeground(final ImagePlus image) {
        final ImageProcessor processor = image.getProcessor();
        if (!processor.isThreshold()) {
            if (!processor.isBinary()) throw new IllegalStateException("Foreground selection requires a binary image");
            int foreground = processor.isInvertedLut() ? 255 : 0;
            if (Prefs.blackBackground) foreground = foreground == 255 ? 0 : 255;
            processor.setThreshold(foreground, foreground, ImageProcessor.NO_LUT_UPDATE);
        }
        image.setRoi(ThresholdToSelection.run(image));
    }

    private static int prepareCsv(final Path csv) {
        try {
            if (!Files.exists(csv) || Files.size(csv) == 0) {
                write(csv, CSV_HEADER_1 + "\n" + CSV_HEADER_2 + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                return 3;
            }
            final byte[] contents = Files.readAllBytes(csv);
            int row = 1;
            for (byte value : contents) if (value == '\n') row++;
            if (contents.length > 0 && contents[contents.length - 1] != '\n') {
                write(csv, "\n", StandardOpenOption.APPEND);
                row++;
            }
            return row;
        } catch (IOException e) {
            throw new IllegalStateException("Could not prepare CSV output: " + csv, e);
        }
    }

    private static String format(final double value) {
        return IJ.d2s(value, 8);
    }

    private static String threshold(final ImagePlus image) {
        final ImageProcessor processor = image.getProcessor();
        return ThresholdAdjuster.getMethod() + (processor.isThreshold()
            ? "[" + format(processor.getMinThreshold()) + "-" + format(processor.getMaxThreshold()) + "]" : "[none]");
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
        "\"Number of pixels (eg, 512 x 512)\",\"Metric (eg, 67.48 x 67.48um)\",\"Ref number\",\"(nerve terminal/motor endplate)\",\"Number of Axonal Inputs\",\"Axon Diameter (um)\",\"Nerve Terminal Perimeter (um)\",\"Nerve Terminal Area (um2)\",\"Skeleton Trees\",\"Terminal Tips\",\"Triple Junctions\",\"Quadruple Junctions\",\" Number of Terminal Branches\",\"Number of Branch Points\",\"Total Length of Branches (um)\",\"Average Length of Branches (um)\",\"\"\"Complexity\"\"\",\"AChR Perimeter (um)\",\"AChR Area (um2)\",\"Endplate Diameter (um)\",\"Endplate Perimeter (um)\",\"Endplate Area (um2)\",\"\"\"Compactness\"\" (%)\",\"Unoccupied AChR Area (um2)\",\"\"\"Area of Synaptic Contact\"\" (um2)\",\"\"\"Overlap\"\" (%)\",\"Number of AChR Clusters\",\"Average Area of AChR Clusters (um2)\",\"\"\"Fragmentation\"\"\"";

    private static int sum(final int[] values) {
        int total = 0;
        for (int value : values) total += value;
        return total;
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
        final Window results = WindowManager.getWindow("Results");
        if (results != null) results.dispose();
    }

    private void installPaintbrush(final int width) {
        Prefs.set("brush.width", width);
        Prefs.set("brush.overlay", false);
        new BrushTool().run("");
    }

    private static void closeImage(final ImagePlus image) {
        if (image != null) {
            image.changes = false;
            image.close();
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
