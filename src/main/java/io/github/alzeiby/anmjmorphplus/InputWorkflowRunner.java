package io.github.alzeiby.anmjmorphplus;

import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.io.OpenDialog;

import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

final class InputWorkflowRunner implements WorkflowRunner {

    static final String TIME_SERIES_ERROR =
        "Time-series images (T > 1) are not supported. Reduce the image to a single time point before running aNMJ-morph+.";
    static final String IMAGE_ARGUMENT_PREFIX = "image-id=";

    private final Supplier<ImagePlus> currentImage;
    private final Supplier<InputMode> modeSelector;
    private final Supplier<Path> fileSelector;
    private final Function<Path, ImagePlus> imageLoader;
    private final Consumer<ImagePlus> imagePresenter;
    private final Consumer<String> macroRunner;
    private final WorkflowRunner batchRunner;
    private final Consumer<String> errorReporter;

    InputWorkflowRunner() {
        final ImageLoader loader = new ImageLoader();
        final LegacyMacroRunner legacy = new LegacyMacroRunner();
        this.currentImage = WindowManager::getCurrentImage;
        this.modeSelector = InputWorkflowRunner::chooseMode;
        this.fileSelector = InputWorkflowRunner::chooseFile;
        this.imageLoader = loader::load;
        this.imagePresenter = ImagePlus::show;
        this.macroRunner = legacy::run;
        this.batchRunner = new BatchSessionRunner();
        this.errorReporter = message -> IJ.error("aNMJ-morph+", message);
    }

    InputWorkflowRunner(
        final Supplier<ImagePlus> currentImage,
        final Supplier<InputMode> modeSelector,
        final Supplier<Path> fileSelector,
        final Function<Path, ImagePlus> imageLoader,
        final Consumer<ImagePlus> imagePresenter,
        final Consumer<String> macroRunner,
        final WorkflowRunner batchRunner,
        final Consumer<String> errorReporter
    ) {
        this.currentImage = Objects.requireNonNull(currentImage, "currentImage");
        this.modeSelector = Objects.requireNonNull(modeSelector, "modeSelector");
        this.fileSelector = Objects.requireNonNull(fileSelector, "fileSelector");
        this.imageLoader = Objects.requireNonNull(imageLoader, "imageLoader");
        this.imagePresenter = Objects.requireNonNull(imagePresenter, "imagePresenter");
        this.macroRunner = Objects.requireNonNull(macroRunner, "macroRunner");
        this.batchRunner = Objects.requireNonNull(batchRunner, "batchRunner");
        this.errorReporter = Objects.requireNonNull(errorReporter, "errorReporter");
    }

    @Override
    public void run() {
        final ImagePlus openImage = currentImage.get();
        if (openImage != null) {
            analyze(openImage, false);
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
            analyze(imageLoader.apply(selected), true);
        } catch (ImageLoadingException e) {
            errorReporter.accept(e.getMessage());
        }
    }

    private void analyze(final ImagePlus image, final boolean presentImage) {
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
        macroRunner.accept(IMAGE_ARGUMENT_PREFIX + image.getID());
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
