package io.github.alzeiby.anmjmorphplus;

import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.io.DirectoryChooser;
import ij.io.FileInfo;
import ij.io.OpenDialog;
import ij.plugin.CompositeConverter;
import ij.plugin.ZProjector;
import loci.formats.FormatException;
import loci.plugins.BF;
import org.scijava.command.Command;
import org.scijava.plugin.Plugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
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

    @Override
    public void run() {
        try {
            ImagePlus image = WindowManager.getCurrentImage();
            Path path = null;
            if (image == null) {
                final GenericDialog mode = new GenericDialog("aNMJ-morph+");
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
        image.setIgnoreGlobalCalibration(true);
        if (image.getNFrames() > 1) throw new IllegalArgumentException(TIME_SERIES_ERROR);
        if (image.getNChannels() == 1 && image.getBitDepth() == 24) {
            return normalize(CompositeConverter.makeComposite(image), false);
        }
        if (image.getNChannels() == 1 && image.getNSlices() == 2 && twoPlanesAsChannels) {
            image.setDimensions(2, 1, 1);
            image.setOpenAsHyperStack(true);
            return image;
        }
        return image.getNSlices() > 1 && image.getNChannels() <= 2
            ? ZProjector.run(image, "max") : image;
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
        final GenericDialog dialog = new GenericDialog("Two-plane image detected");
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
        while (true) {
            final GenericDialog dialog = new GenericDialog("aNMJ-morph+ channel assignment");
            dialog.addChoice("Muscle endplate channel", channels, channels[0]);
            dialog.addChoice("Nerve terminal channel", channels, channels[1]);
            dialog.showDialog();
            if (dialog.wasCanceled()) throw new Cancelled();
            final int muscle = dialog.getNextChoiceIndex() + 1;
            final int nerve = dialog.getNextChoiceIndex() + 1;
            if (muscle != nerve) return new int[] {muscle, nerve};
            IJ.error("aNMJ-morph+", "Muscle endplate and nerve terminal must use different channels.");
        }
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
