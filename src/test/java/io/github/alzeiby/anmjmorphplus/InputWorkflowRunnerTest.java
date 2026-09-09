package io.github.alzeiby.anmjmorphplus;

import ij.IJ;
import ij.ImagePlus;
import org.junit.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class InputWorkflowRunnerTest {

    @Test
    public void currentImageUsesDirectJavaAnalysisWithNoSyntheticPath() {
        final ImagePlus image = IJ.createHyperStack("current", 8, 8, 2, 1, 1, 8);
        final AtomicReference<ImagePlus> analyzed = new AtomicReference<>();
        final AtomicReference<Path> path = new AtomicReference<>();
        final InputWorkflowRunner runner = runner(
            () -> image,
            () -> { throw new AssertionError("mode selector should not run"); },
            () -> { throw new AssertionError("file selector should not run"); },
            selected -> { throw new AssertionError("loader should not run"); },
            shown -> { throw new AssertionError("current image should not be shown again"); },
            (subject, inputPath) -> { analyzed.set(subject); path.set(inputPath); },
            () -> { throw new AssertionError("batch runner should not run"); },
            message -> { throw new AssertionError(message); }
        );

        runner.run();

        assertSame(image, analyzed.get());
        assertNull(path.get());
    }

    @Test
    public void currentTimeSeriesIsRejectedBeforeJavaAnalysis() {
        final ImagePlus image = IJ.createHyperStack("time", 8, 8, 1, 1, 2, 8);
        final AtomicReference<String> error = new AtomicReference<>();
        final AtomicBoolean analysisRan = new AtomicBoolean(false);
        final InputWorkflowRunner runner = runner(
            () -> image,
            () -> InputMode.SINGLE_IMAGE,
            () -> Paths.get("unused.tif"),
            selected -> image,
            ignored -> { },
            (subject, inputPath) -> analysisRan.set(true),
            () -> { throw new AssertionError("batch runner should not run"); },
            error::set
        );

        runner.run();

        assertFalse(analysisRan.get());
        assertEquals(InputWorkflowRunner.TIME_SERIES_ERROR, error.get());
    }

    @Test
    public void batchChoiceRunsDedicatedJavaBatchWorkflow() {
        final AtomicBoolean batchRan = new AtomicBoolean(false);
        final AtomicBoolean singleRan = new AtomicBoolean(false);
        final InputWorkflowRunner runner = runner(
            () -> null,
            () -> InputMode.BATCH_FOLDER,
            () -> { throw new AssertionError("file selector should not run"); },
            selected -> { throw new AssertionError("loader should not run"); },
            ignored -> { },
            (subject, inputPath) -> singleRan.set(true),
            () -> batchRan.set(true),
            message -> { throw new AssertionError(message); }
        );

        runner.run();

        assertTrue(batchRan.get());
        assertFalse(singleRan.get());
    }

    @Test
    public void selectedSingleImageIsLoadedPresentedAndAnalyzedWithSelectedPath() {
        final Path path = Paths.get("fixture.lsm");
        final ImagePlus image = IJ.createHyperStack("loaded", 8, 8, 2, 1, 1, 8);
        final AtomicReference<Path> loadedPath = new AtomicReference<>();
        final AtomicReference<ImagePlus> presented = new AtomicReference<>();
        final AtomicReference<ImagePlus> analyzed = new AtomicReference<>();
        final AtomicReference<Path> analyzedPath = new AtomicReference<>();
        final InputWorkflowRunner runner = runner(
            () -> null,
            () -> InputMode.SINGLE_IMAGE,
            () -> path,
            selected -> { loadedPath.set(selected); return image; },
            presented::set,
            (subject, inputPath) -> { analyzed.set(subject); analyzedPath.set(inputPath); },
            () -> { throw new AssertionError("batch runner should not run"); },
            message -> { throw new AssertionError(message); }
        );

        runner.run();

        assertEquals(path, loadedPath.get());
        assertSame(image, presented.get());
        assertSame(image, analyzed.get());
        assertEquals(path, analyzedPath.get());
    }

    @Test
    public void loaderFailureIsReportedWithoutJavaAnalysis() {
        final AtomicReference<String> error = new AtomicReference<>();
        final AtomicBoolean analyzed = new AtomicBoolean(false);
        final InputWorkflowRunner runner = runner(
            () -> null,
            () -> InputMode.SINGLE_IMAGE,
            () -> Paths.get("bad.gif"),
            selected -> { throw new ImageLoadingException("Unsupported image format: bad.gif"); },
            ignored -> { },
            (subject, inputPath) -> analyzed.set(true),
            () -> { throw new AssertionError("batch runner should not run"); },
            error::set
        );

        runner.run();

        assertFalse(analyzed.get());
        assertEquals("Unsupported image format: bad.gif", error.get());
    }

    @Test
    public void canceledModeDoesNothing() {
        final AtomicBoolean analysisRan = new AtomicBoolean(false);
        final InputWorkflowRunner runner = runner(
            () -> null,
            () -> null,
            () -> { throw new AssertionError("file selector should not run"); },
            selected -> { throw new AssertionError("loader should not run"); },
            ignored -> { },
            (subject, inputPath) -> analysisRan.set(true),
            () -> { throw new AssertionError("batch runner should not run"); },
            message -> { throw new AssertionError(message); }
        );

        runner.run();

        assertFalse(analysisRan.get());
    }

    private static InputWorkflowRunner runner(
        final java.util.function.Supplier<ImagePlus> currentImage,
        final java.util.function.Supplier<InputMode> modeSelector,
        final java.util.function.Supplier<Path> fileSelector,
        final java.util.function.Function<Path, ImagePlus> imageLoader,
        final java.util.function.Consumer<ImagePlus> imagePresenter,
        final InputWorkflowRunner.SingleImageProcessor singleImageProcessor,
        final WorkflowRunner batchRunner,
        final java.util.function.Consumer<String> errorReporter
    ) {
        return new InputWorkflowRunner(
            currentImage,
            modeSelector,
            fileSelector,
            imageLoader,
            imagePresenter,
            singleImageProcessor,
            batchRunner,
            errorReporter
        );
    }
}
