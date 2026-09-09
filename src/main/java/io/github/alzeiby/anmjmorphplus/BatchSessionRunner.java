package io.github.alzeiby.anmjmorphplus;

import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.io.DirectoryChooser;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

final class BatchSessionRunner implements Runnable {

    private final ImageLoader loader = new ImageLoader();
    private final SingleImageAnalysisRunner runner = new SingleImageAnalysisRunner();

    @Override
    public void run() {
        final DirectoryChooser chooser = new DirectoryChooser("Select directory with images to process");
        final String directory = chooser.getDirectory();
        if (directory != null) {
            run(Path.of(directory));
        }
    }

    void run(final Path root) {
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException("Batch root is not a directory: " + root);
        }

        final BatchChoiceResolver choices = new BatchChoiceResolver();
        int succeeded = 0;
        int failed = 0;
        for (Path file : discover(root)) {
            final Set<Integer> existing = currentImageIds();
            try {
                final ImagePlus image = loader.load(file);
                image.show();
                runner.analyzeBatch(image, file, choices);
                succeeded++;
            } catch (AnalysisCancelledException e) {
                break;
            } catch (RuntimeException e) {
                failed++;
                IJ.log("aNMJ-morph+ batch failed for " + file + ": " +
                    (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
            } finally {
                closeImagesCreatedAfter(existing);
            }
        }
        IJ.log("aNMJ-morph+ batch: " + succeeded + " succeeded, " + failed + " failed");
    }

    static List<Path> discover(final Path root) {
        final List<Path> files = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(Files::isRegularFile)
                .filter(path -> SupportedImageFormat.fromName(path.getFileName().toString()).isPresent())
                .filter(path -> !containsGeneratedDirectory(root.relativize(path)))
                .forEach(files::add);
        } catch (IOException e) {
            throw new IllegalStateException("Could not enumerate batch folder: " + root, e);
        }
        files.sort(Comparator.comparing(path -> root.relativize(path).toString().toLowerCase(Locale.ROOT)));
        return files;
    }

    private static boolean containsGeneratedDirectory(final Path relative) {
        final Path parent = relative.getParent();
        if (parent == null) {
            return false;
        }
        for (Path part : parent) {
            final String name = part.toString().toLowerCase(Locale.ROOT);
            if (name.contains("cleaned_images") || name.equals(".anmj-morph-plus")) {
                return true;
            }
        }
        return false;
    }

    private static Set<Integer> currentImageIds() {
        final Set<Integer> ids = new HashSet<>();
        final int[] open = WindowManager.getIDList();
        if (open != null) {
            for (int id : open) ids.add(id);
        }
        return ids;
    }

    private static void closeImagesCreatedAfter(final Set<Integer> existing) {
        final int[] open = WindowManager.getIDList();
        if (open == null) return;
        for (int id : open) {
            if (!existing.contains(id)) {
                final ImagePlus image = WindowManager.getImage(id);
                if (image != null) {
                    image.changes = false;
                    image.close();
                }
            }
        }
    }
}
