package io.github.alzeiby.anmjmorphplus;

import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.io.OpenDialog;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

final class InputWorkflowRunner implements WorkflowRunner {

    static final String TIME_SERIES_ERROR =
        "Time-series images (T > 1) are not supported. Reduce the image to a single time point before running aNMJ-morph+.";
    private final Supplier<ImagePlus> currentImage;
    private final Supplier<InputMode> modeSelector;
    private final Supplier<Path> fileSelector;
    private final Function<Path, ImagePlus> imageLoader;
    private final java.util.function.Consumer<ImagePlus> imagePresenter;
    private final SingleImageProcessor singleImageProcessor;
    private final WorkflowRunner batchRunner;
    private final java.util.function.Consumer<String> errorReporter;

    @FunctionalInterface
    interface SingleImageProcessor {
        void analyze(ImagePlus image, Path inputPath);
    }

    InputWorkflowRunner() {
        final ImageLoader loader = new ImageLoader();
        final SingleImageAnalysisRunner single = new SingleImageAnalysisRunner();
        this.currentImage = WindowManager::getCurrentImage;
        this.modeSelector = InputWorkflowRunner::chooseMode;
        this.fileSelector = InputWorkflowRunner::chooseFile;
        this.imageLoader = loader::load;
        this.imagePresenter = ImagePlus::show;
        this.singleImageProcessor = single::analyze;
        this.batchRunner = new BatchSessionRunner();
        this.errorReporter = message -> IJ.error("aNMJ-morph+", message);
    }

    InputWorkflowRunner(
        final Supplier<ImagePlus> currentImage,
        final Supplier<InputMode> modeSelector,
        final Supplier<Path> fileSelector,
        final Function<Path, ImagePlus> imageLoader,
        final java.util.function.Consumer<ImagePlus> imagePresenter,
        final SingleImageProcessor singleImageProcessor,
        final WorkflowRunner batchRunner,
        final java.util.function.Consumer<String> errorReporter
    ) {
        this.currentImage = Objects.requireNonNull(currentImage, "currentImage");
        this.modeSelector = Objects.requireNonNull(modeSelector, "modeSelector");
        this.fileSelector = Objects.requireNonNull(fileSelector, "fileSelector");
        this.imageLoader = Objects.requireNonNull(imageLoader, "imageLoader");
        this.imagePresenter = Objects.requireNonNull(imagePresenter, "imagePresenter");
        this.singleImageProcessor = Objects.requireNonNull(singleImageProcessor, "singleImageProcessor");
        this.batchRunner = Objects.requireNonNull(batchRunner, "batchRunner");
        this.errorReporter = Objects.requireNonNull(errorReporter, "errorReporter");
    }

    @Override
    public void run() {
        final ImagePlus openImage = currentImage.get();
        if (openImage != null) {
            analyze(openImage, null, false);
            return;
        }

        final InputMode mode = modeSelector.get();
        if (mode == null) {
            return;
        }
        if (mode == InputMode.BATCH_FOLDER) {
            batchRunner.run();
            return;
        }

        final Path selected = fileSelector.get();
        if (selected == null) {
            return;
        }
        try {
            analyze(imageLoader.apply(selected), selected, true);
        } catch (ImageLoadingException e) {
            errorReporter.accept(e.getMessage());
        }
    }

    private void analyze(final ImagePlus image, final Path inputPath, final boolean presentImage) {
        if (InputPolicy.normalizationFor(ImageShape.from(image)) == InputNormalization.REJECT_TIME_SERIES) {
            if (presentImage) {
                image.close();
            }
            errorReporter.accept(TIME_SERIES_ERROR);
            return;
        }
        if (presentImage) {
            imagePresenter.accept(image);
        }
        try {
            singleImageProcessor.analyze(image, inputPath);
        } catch (AnalysisCancelledException e) {
            // User cancelled an interactive analysis step; no error dialog is needed.
        } catch (RuntimeException e) {
            errorReporter.accept(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private static InputMode chooseMode() {
        final GenericDialog dialog = new GenericDialog("aNMJ-morph+");
        dialog.addChoice("Analyze", new String[] {"Single image", "Batch folder"}, "Single image");
        dialog.showDialog();
        if (dialog.wasCanceled()) {
            return null;
        }
        return dialog.getNextChoiceIndex() == 0 ? InputMode.SINGLE_IMAGE : InputMode.BATCH_FOLDER;
    }

    private static Path chooseFile() {
        final OpenDialog dialog = new OpenDialog("Select image to analyze");
        final String path = dialog.getPath();
        return path == null ? null : Path.of(path);
    }
}
