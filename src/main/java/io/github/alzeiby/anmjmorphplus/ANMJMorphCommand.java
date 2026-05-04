package io.github.alzeiby.anmjmorphplus;

import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.LookUpTable;
import ij.Prefs;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.gui.NonBlockingGenericDialog;
import ij.gui.Roi;
import ij.gui.Toolbar;
import ij.io.DirectoryChooser;
import ij.io.FileInfo;
import ij.io.OpenDialog;
import ij.measure.Calibration;
import ij.measure.Measurements;
import ij.measure.ResultsTable;
import ij.plugin.ChannelSplitter;
import ij.plugin.CompositeConverter;
import ij.plugin.ZProjector;
import ij.plugin.filter.Analyzer;
import ij.plugin.filter.BackgroundSubtracter;
import ij.plugin.filter.RankFilters;
import ij.plugin.filter.ThresholdToSelection;
import ij.plugin.frame.ThresholdAdjuster;
import ij.plugin.tool.BrushTool;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import loci.formats.FormatException;
import loci.plugins.BF;
import org.scijava.command.Command;
import org.scijava.plugin.Plugin;
import sc.fiji.analyzeSkeleton.AnalyzeSkeleton_;
import sc.fiji.analyzeSkeleton.Edge;
import sc.fiji.analyzeSkeleton.Graph;
import sc.fiji.analyzeSkeleton.SkeletonResult;
import sc.fiji.analyzeSkeleton.Vertex;

import java.awt.Color;
import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.Window;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

@Plugin(type = Command.class, menuPath = "Analyze>Tools>aNMJ-morph+")
public class ANMJMorphCommand implements Command {

    private static final String TIME_SERIES_ERROR =
        "Time-series images (T > 1) are not supported. Reduce the image to a single time point before running aNMJ-morph+.";
    private static final String MUSCLE_CHANNEL_PREF = "anmj-morph-plus.muscle-channel";
    private static final String NERVE_CHANNEL_PREF = "anmj-morph-plus.nerve-channel";

    @Override
    public void run() {
        // A modeless dialog waits for user input. Do not block the AWT event thread.
        if (EventQueue.isDispatchThread()) {
            new Thread(this::runAnalysis, "aNMJ-morph+").start();
        } else {
            runAnalysis();
        }
    }

    private void runAnalysis() {
        try {
            ImagePlus image = WindowManager.getCurrentImage();
            Path path = null;
            if (image == null) {
                final GenericDialog mode = interactionDialog("aNMJ-morph+");
                mode.addChoice("Analyze", new String[] {"Single image", "Batch folder"}, "Single image");
                mode.showDialog();
                if (mode.wasCanceled()) return;
                if (mode.getNextChoiceIndex() == 1) {
                    runBatch();
                    return;
                }
                final OpenDialog file = new OpenDialog("Select image to analyze");
                if (file.getPath() == null) return;
                path = Path.of(file.getPath());
                image = load(path);
                image.show();
            }
            analyze(image, path == null ? pathForCurrentImage(image) : path);
        } catch (Cancelled ignored) {
        } catch (RuntimeException e) {
            IJ.error("aNMJ-morph+", e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private static void analyze(ImagePlus image, final Path path) {
        final boolean twoPlanesAsChannels = image.getBitDepth() != 24 && image.getNChannels() == 1 &&
            image.getNSlices() == 2 && chooseTwoPlanesAsChannels();
        final ImagePlus normalized = normalize(image, twoPlanesAsChannels);
        if (normalized != image) {
            image.changes = false;
            image.close();
            image = normalized;
            image.show();
        }
        final ImagePlus cropped = chooseCrop(image);
        if (cropped != image) {
            image.changes = false;
            image.close();
            image = cropped;
            image.show();
        }
        choosePixelCalibration(image);
        if (image.getNChannels() < 2) {
            throw new IllegalArgumentException("At least two channels are required to select muscle endplate and nerve terminal");
        }
        final int[] channels = chooseChannels(image.getNChannels());
        new AnalysisWorkflow().analyze(image, path, channels[0], channels[1]);
    }

    private static void runBatch() {
        final String directory = new DirectoryChooser("Select directory with images to process").getDirectory();
        if (directory == null) return;
        final Path root = Path.of(directory);
        int succeeded = 0;
        int failed = 0;
        for (Path file : discover(root)) {
            try {
                final ImagePlus image = load(file);
                image.show();
                analyze(image, file);
                succeeded++;
            } catch (Cancelled e) {
                break;
            } catch (RuntimeException e) {
                failed++;
                IJ.log("aNMJ-morph+ batch failed for " + file + ": " +
                    (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            } finally {
                closeAllImages();
            }
        }
        IJ.log("aNMJ-morph+ batch: " + succeeded + " succeeded, " + failed + " failed");
    }

    static List<Path> discover(final Path root) {
        try (Stream<Path> stream = Files.walk(root)) {
            return stream.filter(Files::isRegularFile)
                .filter(ANMJMorphCommand::supported)
                .filter(path -> !generated(root.relativize(path)))
                .sorted(Comparator.comparing(path -> root.relativize(path).toString().toLowerCase(Locale.ROOT)))
                .collect(Collectors.toList());
        } catch (IOException e) {
            throw new IllegalStateException("Could not enumerate batch folder: " + root, e);
        }
    }

    private static boolean generated(final Path relative) {
        final Path parent = relative.getParent();
        return parent != null && StreamSupport.stream(parent.spliterator(), false)
            .map(part -> part.toString().toLowerCase(Locale.ROOT))
            .anyMatch(name -> name.equals("cleaned_images") || name.equals(".anmj-morph-plus"));
    }

    static ImagePlus load(final Path path) {
        final String name = path.getFileName().toString();
        if (!supported(path)) throw new IllegalArgumentException("Unsupported image format: " + name);
        final String absolute = path.toAbsolutePath().normalize().toString();
        final ImagePlus calibrationScope = new ImagePlus();
        final ij.measure.Calibration globalCalibration = calibrationScope.getGlobalCalibration();
        calibrationScope.setGlobalCalibration(null);
        try {
            final ImagePlus[] images = BF.openImagePlus(absolute);
            if (images == null || images.length != 1 || images[0] == null) {
                if (images != null) for (ImagePlus opened : images) if (opened != null) opened.close();
                throw new IllegalStateException("Expected exactly one image series: " + name);
            }
            images[0].setTitle(name);
            return images[0];
        } catch (FormatException | IOException e) {
            throw new IllegalStateException("Could not open image: " + name, e);
        } finally {
            calibrationScope.setGlobalCalibration(globalCalibration);
        }
    }

    static ImagePlus normalize(final ImagePlus image, final boolean twoPlanesAsChannels) {
        // Capture the calibration Fiji actually displays *before* ignoring its
        // optional global calibration. Already-open Bio-Formats images can
        // expose the physical scale through the effective calibration while
        // their local calibration still says "pixel".
        final Calibration effective = image.getCalibration().copy();
        image.setIgnoreGlobalCalibration(true);
        image.setCalibration(effective.copy());
        if (image.getNFrames() > 1) throw new IllegalArgumentException(TIME_SERIES_ERROR);
        if (image.getNChannels() == 1 && image.getBitDepth() == 24) {
            final ImagePlus composite = CompositeConverter.makeComposite(image);
            composite.setIgnoreGlobalCalibration(true);
            composite.setCalibration(effective.copy());
            return normalize(composite, false);
        }
        if (image.getNChannels() == 1 && image.getNSlices() == 2 && twoPlanesAsChannels) {
            image.setDimensions(2, 1, 1);
            image.setOpenAsHyperStack(true);
            return image;
        }
        if (image.getNSlices() > 1 && image.getNChannels() <= 2) {
            final ImagePlus projected = ZProjector.run(image, "max");
            projected.setIgnoreGlobalCalibration(true);
            projected.setCalibration(effective.copy());
            return projected;
        }
        return image;
    }

    static boolean supported(final Path path) {
        final String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.matches(".*\\.(tif|tiff|lsm|nd2|czi|lif|png|jpe?g|bmp)$");
    }

    static Path pathForCurrentImage(final ImagePlus image) {
        final FileInfo info = image.getOriginalFileInfo();
        if (info != null && info.directory != null && !info.directory.isEmpty()) {
            return Path.of(info.directory).resolve(image.getTitle());
        }
        final String directory = new DirectoryChooser("Select folder for analysis output").getDirectory();
        if (directory == null) throw new Cancelled();
        return Path.of(directory).resolve(image.getTitle());
    }

    private static boolean chooseTwoPlanesAsChannels() {
        final GenericDialog dialog = interactionDialog("Two-plane image");
        dialog.addChoice("Interpret as",
            new String[] {"Two channels (Keyence/two-page export)", "Z stack (maximum-project)"},
            "Two channels (Keyence/two-page export)");
        dialog.showDialog();
        if (dialog.wasCanceled()) throw new Cancelled();
        return dialog.getNextChoiceIndex() == 0;
    }

    private static int[] chooseChannels(final int count) {
        final String[] channels = new String[count];
        for (int i = 0; i < count; i++) channels[i] = Integer.toString(i + 1);
        final int[] previous = previousChannels(count);
        int muscleDefault = previous[0];
        int nerveDefault = previous[1];
        while (true) {
            final GenericDialog dialog = interactionDialog("Channels");
            dialog.addChoice("Muscle endplate channel", channels, channels[muscleDefault - 1]);
            dialog.addChoice("Nerve terminal channel", channels, channels[nerveDefault - 1]);
            dialog.showDialog();
            if (dialog.wasCanceled()) throw new Cancelled();
            final int muscle = dialog.getNextChoiceIndex() + 1;
            final int nerve = dialog.getNextChoiceIndex() + 1;
            if (muscle != nerve) {
                Prefs.set(MUSCLE_CHANNEL_PREF, muscle);
                Prefs.set(NERVE_CHANNEL_PREF, nerve);
                Prefs.savePreferences();
                return new int[] {muscle, nerve};
            }
            muscleDefault = muscle;
            nerveDefault = nerve;
            IJ.error("aNMJ-morph+", "Muscle endplate and nerve terminal must use different channels.");
        }
    }

    static int[] previousChannels(final int count) {
        if (count < 2) throw new IllegalArgumentException("At least two channels are required");
        final int muscle = (int) Prefs.get(MUSCLE_CHANNEL_PREF, 1);
        final int nerve = (int) Prefs.get(NERVE_CHANNEL_PREF, 2);
        if (muscle < 1 || muscle > count || nerve < 1 || nerve > count || muscle == nerve) {
            return new int[] {1, 2};
        }
        return new int[] {muscle, nerve};
    }

    private static ImagePlus chooseCrop(final ImagePlus image) {
        final Toolbar toolbar = Toolbar.getInstance();
        if (toolbar != null) toolbar.setTool(Toolbar.RECTANGLE);
        if (image.getWindow() != null) IJ.selectWindow(image.getID());
        else WindowManager.setTempCurrentImage(image);
        final GenericDialog dialog = interactionDialog("Crop");
        dialog.addMessage("Draw a rectangle on the image to crop it.\nLeave it unselected to use the full image.");
        dialog.setOKLabel("Continue");
        dialog.showDialog();
        if (dialog.wasCanceled()) throw new Cancelled();
        if (image.getRoi() == null) return image;
        return cropToRectangle(image);
    }

    static GenericDialog interactionDialog(final String title) {
        // Do not use newDialog(title, image): that factory can return a modal
        // dialog and may keep bringing the dialog in front of the image.
        return new NonBlockingGenericDialog(title);
    }

    static ImagePlus cropToRectangle(final ImagePlus image) {
        final Roi selection = image.getRoi();
        if (selection == null || selection.getType() != Roi.RECTANGLE) {
            throw new IllegalArgumentException("Select a rectangular region on the image before choosing Crop.");
        }
        final Rectangle bounds = selection.getBounds();
        if (bounds.width <= 0 || bounds.height <= 0 || bounds.x < 0 || bounds.y < 0 ||
            bounds.x + bounds.width > image.getWidth() || bounds.y + bounds.height > image.getHeight()) {
            throw new IllegalArgumentException("The crop must be a nonempty rectangle inside the image.");
        }
        final Calibration effective = image.getCalibration().copy();
        final ImagePlus cropped = image.crop("stack");
        if (cropped == null || cropped.getWidth() != bounds.width || cropped.getHeight() != bounds.height ||
            cropped.getStackSize() != image.getStackSize()) {
            throw new IllegalStateException("Could not crop all image channels and slices consistently.");
        }
        cropped.setTitle(image.getTitle());
        cropped.setIgnoreGlobalCalibration(true);
        cropped.setCalibration(effective);
        cropped.killRoi();
        return cropped;
    }

    private static void choosePixelCalibration(final ImagePlus image) {
        final Calibration cal = image.getLocalCalibration();
        if (!AnalysisWorkflow.isPixelUnit(cal.getXUnit()) &&
            !AnalysisWorkflow.isPixelUnit(cal.getYUnit())) return;

        final GenericDialog dialog = interactionDialog("Calibration");
        dialog.addMessage("Physical units not specified. Confirm microns per pixel, or measure in pixels.");
        dialog.addChoice("Measurement units", new String[] {
            "Pixels (px and px^2)", "Microns (enter microns per pixel)"
        }, suggestPhysicalUnits(image) ? "Microns (enter microns per pixel)" : "Pixels (px and px^2)");
        // Fiji/Bio-Formats can retain a physical pixel scale while losing its unit
        // label (notably in already-open ND2s). Never silently reset that scale to 1.
        dialog.addNumericField("Pixel width (microns/pixel)",
            proposedMicronsPerPixel(cal.pixelWidth), 6);
        dialog.addNumericField("Pixel height (microns/pixel)",
            proposedMicronsPerPixel(cal.pixelHeight), 6);
        dialog.showDialog();
        if (dialog.wasCanceled()) throw new Cancelled();
        final boolean useMicrons = dialog.getNextChoiceIndex() == 1;
        final double x = dialog.getNextNumber();
        final double y = dialog.getNextNumber();
        applyPixelCalibration(image, useMicrons, x, y);
    }

    static double proposedMicronsPerPixel(final double existing) {
        return Double.isFinite(existing) && existing > 0 ? existing : 1.0;
    }

    static boolean suggestPhysicalUnits(final ImagePlus image) {
        final String title = image.getTitle().toLowerCase(Locale.ROOT);
        final boolean microscopyFormat = title.endsWith(".nd2") || title.endsWith(".czi") ||
            title.endsWith(".lsm") || title.endsWith(".lif");
        if (!microscopyFormat) return false;
        final Calibration cal = image.getLocalCalibration();
        final double x = cal.pixelWidth, y = cal.pixelHeight;
        return Double.isFinite(x) && Double.isFinite(y) && x > 0 && y > 0 &&
            (Math.abs(x - 1.0) > 1e-9 || Math.abs(y - 1.0) > 1e-9);
    }

    static void applyPixelCalibration(final ImagePlus image, final boolean useMicrons,
        final double micronsPerPixelX, final double micronsPerPixelY) {
        if (useMicrons && (!(micronsPerPixelX > 0) || !(micronsPerPixelY > 0) ||
            !Double.isFinite(micronsPerPixelX) || !Double.isFinite(micronsPerPixelY))) {
            throw new IllegalArgumentException("Microns per pixel must be positive, finite values for X and Y.");
        }
        image.setIgnoreGlobalCalibration(true);
        final Calibration cal = image.getLocalCalibration();
        cal.pixelWidth = useMicrons ? micronsPerPixelX : 1.0;
        cal.pixelHeight = useMicrons ? micronsPerPixelY : 1.0;
        cal.setUnit(useMicrons ? "micron" : "pixel");
        cal.setYUnit(useMicrons ? "micron" : "pixel");
    }

    private static void closeAllImages() {
        final int[] ids = WindowManager.getIDList();
        if (ids == null) return;
        for (int id : ids) {
            final ImagePlus image = WindowManager.getImage(id);
            if (image != null) {
                image.changes = false;
                image.close();
            }
        }
    }

    static final class Cancelled extends RuntimeException {
    }
}

final class AnalysisWorkflow {

    interface ReviewPrompter {
        void review(ImagePlus subject, String message);
        boolean confirmSegmentation(String message);
    }

    @FunctionalInterface
    interface ProgressSink {
        void update(double fraction, String status);
    }

    // This is an *analysis* progress indicator, not an animated timer.
    // The fraction advances only after the named processing step has returned.
    private static final ProgressSink FIJI_PROGRESS = (fraction, status) -> {
        IJ.showProgress(fraction);
        IJ.showStatus("aNMJ-morph+: " + status);
    };

    private static final String SCREEN2 = "Adjust the nerve threshold. Compare with the reference image on the left.\nUse the brush to remove noise, then click Continue.";
    private static final String SCREEN3 = "Adjust the endplate threshold. Compare with the reference image on the left.\nUse the brush to remove noise, then click Continue.";
    private static final String SCREEN4 = "Draw a line across each width and press M, in this order:\n1. Maximum axon width\n2. Minimum axon width\n3. Axon hillock width (where the axon joins the terminals)\nKeep exactly these three measurements, or none if no axon is present.";
    private static final String SCREEN5 = "Erase the axon from the nerve image with the brush.\nClick Continue when done.";
    private static final String SCREEN6 = "Review the automatically segmented endplate on the right against the original on the left.\nWhite = AChR clusters; black = background. Touching lobes are separated at narrow necks.\nNo manual drawing is required. Uncheck if the result is unsuitable.";

    private final ReviewPrompter prompter;
    private final ProgressSink progress;

    AnalysisWorkflow() {
        this(new ImageJReviewPrompter(), FIJI_PROGRESS);
    }

    AnalysisWorkflow(final ReviewPrompter prompter) {
        this(prompter, FIJI_PROGRESS);
    }

    AnalysisWorkflow(final ReviewPrompter prompter, final ProgressSink progress) {
        this.prompter = prompter;
        this.progress = progress;
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
            prompter.review(image, "X and Y pixel sizes differ. Check the calibration before continuing.");
        }
        clearResults();
        installPaintbrush();
        resetThreshold();

        ImagePlus muscle = null, nerve = null, nerveReference = null, segmentMuscle = null, segmented = null;
        final boolean blackBackground = Prefs.blackBackground;
        boolean progressStarted = false;
        boolean completed = false;
        try {
        muscle = channel(image, muscleChannel);
        nerve = channel(image, nerveChannel);
        closeImage(image);
        nerve.show();
        nerveReference = nerve.duplicate();
        nerveReference.setTitle("Nerve reference");
        nerveReference.show();
        arrangeComparison(nerveReference, nerve);

        Toolbar.setForegroundColor(Color.BLACK);

        makeCurrent(nerve);
        IJ.run(nerve, "Threshold...", "");
        Toolbar.getInstance().setTool("Paintbrush Tool");
        final double[] nerveBounds = reviewThreshold(nerve, SCREEN2);
        final String thresholdNerve = threshold(nerve);
        Prefs.blackBackground = false;
        closeThreshold();
        applyThresholdMask(nerve, nerveBounds);
        new RankFilters().rank(nerve.getProcessor(), 1.0, RankFilters.MEDIAN);
        IJ.saveAs(nerve, "Tiff", axonPath.toString());
        nerveReference.hide();
        nerve.hide();

        segmentMuscle = muscle.duplicate();
        segmentMuscle.setIgnoreGlobalCalibration(true);
        segmentMuscle.setTitle("Muscle reference");
        muscle.show();
        segmentMuscle.show();
        arrangeComparison(segmentMuscle, muscle);
        makeCurrent(muscle);
        IJ.run(muscle, "Threshold...", "");
        Toolbar.getInstance().setTool("Paintbrush Tool");
        final double[] muscleBounds = reviewThreshold(muscle, SCREEN3);
        final String thresholdEndplate = threshold(muscle);
        closeThreshold();
        applyThresholdMask(muscle, muscleBounds);
        new RankFilters().rank(muscle.getProcessor(), 1.0, RankFilters.MEDIAN);
        // Keep the user's cleaned mask for segmentation, not the raw reference.
        final ImageProcessor cleanedEndplateMask = muscle.getProcessor().duplicate();
        IJ.saveAs(muscle, "Tiff", endplatePath.toString());
        segmentMuscle.hide();

        // Keep the raw nerve next to the edited nerve during width measurement
        // and cleanup. Hide the unrelated muscle image.
        muscle.hide();
        nerve.show();
        nerveReference.show();
        arrangeComparison(nerveReference, nerve);
        Toolbar.getInstance().setTool(Toolbar.LINE);
        makeCurrent(nerve);
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
        // No progress bar runs while the user is drawing widths or editing the
        // masks. The processing phase starts when that interaction is finished.
        progressStarted = true;
        progress.update(0.0, "Processing junction");
        closeImage(nerveReference);
        final double unoccupiedAchrArea = unoccupiedArea(muscle, nerve);

        selectForeground(nerve);
        final double[] nerveMeasurement = measure(nerve);
        progress.update(0.20, "Nerve measurements complete");

        // The thresholded mask is black signal on white background. Fiji's
        // Analyze Skeleton expects white skeleton pixels on black background.
        prepareNerveForSkeleton(nerve);
        final boolean beforeSkeleton = Prefs.blackBackground;
        try {
            Prefs.blackBackground = true;
            IJ.run(nerve, "Skeletonize", "");
        } finally {
            Prefs.blackBackground = beforeSkeleton;
        }
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
        progress.update(0.50, "Skeleton and branch measurements complete");

        selectForeground(muscle);
        final double[] achrMeasurement = measure(muscle);
        new BackgroundSubtracter().rollingBallBackground(
            muscle.getProcessor(), 50, true, false, false, true, true
        );
        if (!muscle.getProcessor().isThreshold()) {
            muscle.getProcessor().setThreshold(0, 0, ImageProcessor.NO_LUT_UPDATE);
        }
        applyThresholdMask(muscle, requireThreshold(muscle));
        selectForeground(muscle);
        final double[] endplateMeasurement = measure(muscle);
        progress.update(0.75, "Endplate measurements complete");

        final ByteProcessor segmentedProcessor = segmentEndplate(cleanedEndplateMask);
        segmented = new ImagePlus("Endplate segmented", segmentedProcessor);
        segmented.setCalibration(segmentMuscle.getCalibration());
        progress.update(0.85, "Automatic segmentation complete; awaiting review");
        // The measurement mask has a yellow ROI from the area calculation.
        // It is not the segmented preview and should not be shown here.
        muscle.hide();
        segmentMuscle.show();
        segmented.show();
        arrangeComparison(segmentMuscle, segmented);
        final boolean imageAlright = prompter.confirmSegmentation(SCREEN6);
        // Count only after the automatic segmentation is displayed and accepted.
        final double numberOfClusters = imageAlright ? countEndplateClusters(segmented) : Double.NaN;
        progress.update(0.90, "Cluster counting complete");
        final Path segmentationPath = cleaned.resolve("segmented_endplate" + stem + ".tif");
        if (imageAlright) {
            IJ.saveAs(segmented, "Tiff", segmentationPath.toString());
        }
        closeImage(segmentMuscle);
        closeImage(segmented);
        closeImage(muscle);

        final boolean pixelMeasurements = isPixelUnit(sizeUnit);
        final Path csv = parent.resolve(pixelMeasurements ? "raw_data_table_pixels.csv" : "raw_data_table.csv");
        final int row = prepareCsv(csv, pixelMeasurements);
        final String[] fields = {
            quote(width + " x " + height),
            quote(format(pixelSizeX * width) + " x " + format(pixelSizeY * height) + sizeUnit),
            quote(inputPath.getFileName().toString()), "Nerve C" + nerveChannel + ":" + thresholdNerve + "/Muscle C" + muscleChannel + ":" + thresholdEndplate, "",
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
        final String record = String.join(",", fields) + "\n";
        write(csv, record, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        progress.update(0.97, "Output written; verifying files");
        verifyOutput(csv, record, axonPath, endplatePath, imageAlright ? segmentationPath : null);
        completed = true;
        } finally {
            closeThreshold();
            Prefs.blackBackground = blackBackground;
            closeImage(segmented);
            closeImage(segmentMuscle);
            closeImage(nerveReference);
            closeImage(nerve);
            closeImage(muscle);
            if (progressStarted) {
                // Complete the indicator only after file verification AND cleanup.
                // A cancelled/failed run is never reported as 100%.
                progress.update(completed ? 1.0 : -1.0, completed
                    ? "Analysis complete; output verified"
                    : "Analysis stopped without verified output");
            }
        }
    }

    static void verifyOutput(final Path csv, final String expectedRecord,
        final Path axon, final Path endplate, final Path segmentation) {
        try {
            for (Path file : new Path[] { axon, endplate, segmentation }) {
                if (file == null) continue;
                if (!Files.isRegularFile(file) || Files.size(file) <= 0)
                    throw new IllegalStateException("Output was not saved: " + file);
            }
            final byte[] expected = expectedRecord.getBytes(StandardCharsets.UTF_8);
            if (!Files.isRegularFile(csv) || Files.size(csv) < expected.length)
                throw new IllegalStateException("CSV record was not saved: " + csv);
            try (SeekableByteChannel channel = Files.newByteChannel(csv, StandardOpenOption.READ)) {
                channel.position(channel.size() - expected.length);
                final ByteBuffer tail = ByteBuffer.allocate(expected.length);
                while (tail.hasRemaining()) {
                    if (channel.read(tail) < 0)
                        throw new IllegalStateException("Could not read saved CSV record: " + csv);
                }
                if (!java.util.Arrays.equals(expected, tail.array()))
                    throw new IllegalStateException("Saved CSV record differs from measurements: " + csv);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not verify analysis output", e);
        }
    }

    static void canonicalizeCalibration(final ImagePlus image) {
        image.setIgnoreGlobalCalibration(true);
        final Calibration calibration = image.getLocalCalibration();
        if (isPixelUnit(calibration.getXUnit()) && isPixelUnit(calibration.getYUnit())) {
            calibration.pixelWidth = 1.0;
            calibration.pixelHeight = 1.0;
            calibration.setUnit("pixel");
            calibration.setYUnit("pixel");
            return;
        }
        calibration.pixelWidth = microns(calibration.pixelWidth, calibration.getXUnit());
        calibration.pixelHeight = microns(calibration.pixelHeight, calibration.getYUnit());
        calibration.setUnit("micron");
        calibration.setYUnit("micron");
    }

    static boolean isPixelUnit(final String rawUnit) {
        return rawUnit == null || rawUnit.isBlank() ||
            rawUnit.equalsIgnoreCase("pixel") || rawUnit.equalsIgnoreCase("pixels") ||
            rawUnit.equalsIgnoreCase("px");
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
        copy.setIgnoreGlobalCalibration(true);
        copy.setStack("C" + channel + "-" + source.getTitle(), stack);
        if (copy.getNSlices() == 1) {
            copy.setDisplayRange(displayMin, displayMax);
            return copy;
        }
        final ImagePlus projected = ZProjector.run(copy, "max");
        projected.setIgnoreGlobalCalibration(true);
        projected.setDisplayRange(displayMin, displayMax);
        copy.flush();
        return projected;
    }

    static void applyThresholdMask(final ImagePlus image, final double[] bounds) {
        final ImageProcessor processor = image.getProcessor();
        processor.setThreshold(bounds[0], bounds[1], ImageProcessor.NO_LUT_UPDATE);
        final ImageProcessor mask = processor.createMask();
        if (mask == null) throw new IllegalStateException("Could not apply threshold to " + image.getTitle());
        // Fiji's black-foreground convention is used by the rest of the workflow.
        mask.invert();
        image.setProcessor(mask);
        image.updateAndDraw();
    }

    static ByteProcessor segmentEndplate(final ImageProcessor cleanedMask) {
        if (!(cleanedMask instanceof ByteProcessor) || !cleanedMask.isBinary()) {
            throw new IllegalArgumentException("Endplate segmentation requires a binary mask");
        }
        // Split only clearly necked, hole-free components. Avoid hard-coded
        // intensity maxima on a binary image (which split continuous rings).
        return AutomaticClusterSeparator.segment((ByteProcessor)cleanedMask);
    }

    static int countEndplateClusters(final ImagePlus segmented) {
        final ImageProcessor pixels = segmented.getProcessor();
        if (!(pixels instanceof ByteProcessor) || !pixels.isBinary()) {
            throw new IllegalArgumentException("Particle analysis requires a binary segmented image");
        }
        // Explicit 8-connected component flood fill: O(pixels), independent of
        // Fiji's particle analyzer state, dialogs, thresholds, and progress bar.
        final int width=pixels.getWidth(), height=pixels.getHeight();
        final int n=width*height;
        final boolean[] seen=new boolean[n];
        final int[] queue=new int[n];
        int count=0;
        for(int start=0; start<n; start++) {
            if(seen[start] || pixels.get(start)!=255) continue;
            count++;
            seen[start]=true;
            int front=0, end=0;
            queue[end++]=start;
            while(front<end) {
                final int index=queue[front++];
                final int x=index%width, y=index/width;
                for(int yy=Math.max(0,y-1); yy<=Math.min(height-1,y+1); yy++)
                    for(int xx=Math.max(0,x-1); xx<=Math.min(width-1,x+1); xx++) {
                        final int next=yy*width+xx;
                        if(!seen[next] && pixels.get(next)==255) {
                            seen[next]=true;
                            queue[end++]=next;
                        }
                    }
            }
        }
        return count;
    }

    private double[] reviewThreshold(final ImagePlus subject, final String instructions) {
        boolean missing = false;
        while (true) {
            prompter.review(subject, missing
                ? "Set a threshold in the Threshold window before continuing.\n" + instructions : instructions);
            if (subject.getProcessor().isThreshold()) return requireThreshold(subject);
            missing = true;
        }
    }

    static double[] requireThreshold(final ImagePlus image) {
        final ImageProcessor processor = image.getProcessor();
        if (!processor.isThreshold()) {
            throw new IllegalStateException("No threshold set for " + image.getTitle() + ". Set the threshold before continuing.");
        }
        return new double[] {processor.getMinThreshold(), processor.getMaxThreshold()};
    }

    static void arrangeComparison(final ImagePlus reference, final ImagePlus editable) {
        if (GraphicsEnvironment.isHeadless() || reference.getWindow() == null || editable.getWindow() == null) return;
        final Rectangle area = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        final Window left = reference.getWindow();
        final Window right = editable.getWindow();
        final int gap = 12;
        // Give both images the same substantial viewport and reserve space
        // below them for the non-modal Continue dialog.
        final int viewportWidth = Math.min(700, Math.max(180, (area.width - 3 * gap) / 2));
        final int viewportHeight = Math.min(720, Math.max(180, area.height - 190));
        left.setSize(viewportWidth, viewportHeight);
        right.setSize(viewportWidth, viewportHeight);
        reference.getCanvas().fitToWindow();
        editable.getCanvas().fitToWindow();
        final int total = left.getWidth() + gap + right.getWidth();
        final int startX = area.x + Math.max(0, (area.width - total) / 2);
        final int top = area.y + 8;
        left.setLocation(startX, top);
        right.setLocation(Math.min(startX + left.getWidth() + gap, area.x + area.width - right.getWidth()), top);
        right.toFront();
    }

    private static void placeReviewDialog(final GenericDialog dialog,
        final ImagePlus reference, final ImagePlus edited) {
        if (GraphicsEnvironment.isHeadless() || reference == null || edited == null ||
            reference.getWindow() == null || edited.getWindow() == null) return;
        dialog.pack();
        final Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        final Window left = reference.getWindow(), right = edited.getWindow();
        final int center = (left.getX() + right.getX() + right.getWidth()) / 2;
        final int bottomOfImages = Math.max(left.getY() + left.getHeight(), right.getY() + right.getHeight());
        final int x = Math.max(screen.x, Math.min(center - dialog.getWidth()/2,
            screen.x + screen.width - dialog.getWidth()));
        final int y = Math.max(screen.y, Math.min(bottomOfImages + 8,
            screen.y + screen.height - dialog.getHeight() - 8));
        dialog.setLocation(x,y);
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

    static void prepareNerveForSkeleton(final ImagePlus image) {
        final ImageProcessor pixels = image.getProcessor();
        if (!pixels.isBinary()) throw new IllegalArgumentException("Skeletonization requires a binary nerve mask");
        image.killRoi();
        pixels.resetThreshold();
        pixels.invert();
        image.updateAndDraw();
    }

    static int prepareCsv(final Path csv, final boolean pixelMeasurements) {
        try {
            if (!Files.exists(csv) || Files.size(csv) == 0) {
                final String header = pixelMeasurements ? CSV_HEADER_2
                    .replace("67.48 x 67.48um", "512 x 512px")
                    .replace("(um2)", "(px2)")
                    .replace("(um)", "(px)") : CSV_HEADER_2;
                write(csv, CSV_HEADER_1 + "\n" + header + "\n",
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

    private static void resetThreshold() {
        closeThreshold();
        ThresholdAdjuster.setMethod("Default");
        Prefs.set(ThresholdAdjuster.MODE_KEY, 0);
        Prefs.set(ThresholdAdjuster.DARK_BACKGROUND, true);
        Prefs.set(ThresholdAdjuster.NO_RESET, true);
        Prefs.set(ThresholdAdjuster.SIXTEEN_BIT, false);
    }

    private static void clearResults() {
        Analyzer.setUnsavedMeasurements(false);
        Analyzer.resetCounter();
        final Window results = WindowManager.getWindow("Results");
        if (results != null) results.dispose();
    }

    private void installPaintbrush() {
        final int width = preferredBrushWidth(Prefs.get("brush.width", 12.0));
        Prefs.set("brush.width", width);
        BrushTool.setBrushWidth(width);
        Prefs.set("brush.overlay", false);
        new BrushTool().run("");
    }

    static int preferredBrushWidth(final double saved) {
        // Earlier versions forced 100 pixels; keep other user-selected widths.
        if (!Double.isFinite(saved) || saved <= 0 || saved == 100.0) return 12;
        return Math.max(1, Math.min(512, (int) Math.round(saved)));
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
            final GenericDialog dialog = ANMJMorphCommand.interactionDialog("Review image");
            dialog.addMessage(message);
            dialog.setOKLabel("Continue");
            final ImagePlus nerveReference = WindowManager.getImage("Nerve reference");
            final ImagePlus muscleReference = WindowManager.getImage("Muscle reference");
            final ImagePlus reference = nerveReference != null && nerveReference.getWindow() != null
                ? nerveReference : muscleReference;
            placeReviewDialog(dialog, reference, subject);
            dialog.showDialog();
            if (dialog.wasCanceled() || IJ.escapePressed()) {
                throw new ANMJMorphCommand.Cancelled();
            }
            makeCurrent(subject);
        }

        @Override
        public boolean confirmSegmentation(final String message) {
            final GenericDialog dialog = ANMJMorphCommand.interactionDialog("Segmentation");
            dialog.addMessage(message);
            dialog.addCheckbox("Image looks OK", true);
            dialog.setOKLabel("Continue");
            placeReviewDialog(dialog, WindowManager.getImage("Muscle reference"),
                WindowManager.getImage("Endplate segmented"));
            dialog.showDialog();
            if (dialog.wasCanceled() || IJ.escapePressed()) {
                throw new ANMJMorphCommand.Cancelled();
            }
            return dialog.getNextBoolean();
        }
    }
}
