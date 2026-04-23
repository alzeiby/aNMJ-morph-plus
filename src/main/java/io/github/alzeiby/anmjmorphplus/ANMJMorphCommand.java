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
import ij.plugin.filter.MaximumFinder;
import ij.plugin.filter.ParticleAnalyzer;
import ij.plugin.filter.RankFilters;
import ij.plugin.filter.ThresholdToSelection;
import ij.plugin.frame.ThresholdAdjuster;
import ij.plugin.tool.BrushTool;
import ij.process.Blitter;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
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
        } finally {
            // Don't leave Fiji's shared progress bar stuck at a partial value
            // if the command finishes, is cancelled, or fails before analysis.
            IJ.showProgress(-1.0);
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
        // Channel selection is ready for the analysis workflow added next.
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

    private static boolean isPixelUnit(final String unit) {
        return unit == null || unit.isBlank() || unit.equalsIgnoreCase("pixel") ||
            unit.equalsIgnoreCase("pixels") || unit.equalsIgnoreCase("px");
    }

    private static void choosePixelCalibration(final ImagePlus image) {
        final Calibration cal = image.getLocalCalibration();
        if (!isPixelUnit(cal.getXUnit()) &&
            !isPixelUnit(cal.getYUnit())) return;

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

    static final class Cancelled extends RuntimeException {
    }
}

