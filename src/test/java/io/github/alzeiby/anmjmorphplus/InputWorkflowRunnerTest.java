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
    public void currentImageUsesExactImageIdHandoff() {
        final ImagePlus image = IJ.createHyperStack("current", 8, 8, 2, 1, 1, 8);
        final AtomicReference<String> argument = new AtomicReference<>();
        final InputWorkflowRunner runner = runner(
            () -> image,
            () -> { throw new AssertionError("mode selector should not run"); },
            () -> { throw new AssertionError("file selector should not run"); },
            path -> { throw new AssertionError("loader should not run"); },
            imageToShow -> { throw new AssertionError("current image should not be shown again"); },
            argument::set,
            () -> { throw new AssertionError("batch runner should not run"); },
            message -> { throw new AssertionError(message); }
        );

        runner.run();

        assertEquals(InputWorkflowRunner.IMAGE_ARGUMENT_PREFIX + image.getID(), argument.get());
    }

    @Test
    public void currentTimeSeriesIsRejectedBeforeMacroHandoff() {
        final ImagePlus image = IJ.createHyperStack("time", 8, 8, 1, 1, 2, 8);
        final AtomicReference<String> error = new AtomicReference<>();
        final AtomicBoolean macroRan = new AtomicBoolean(false);
        final InputWorkflowRunner runner = runner(
            () -> image,
            () -> InputMode.SINGLE_IMAGE,
            () -> Paths.get("unused.tif"),
            path -> image,
            ignored -> { },
            argument -> macroRan.set(true),
            () -> { throw new AssertionError("batch runner should not run"); },
            error::set
        );

        runner.run();

        assertFalse(macroRan.get());
        assertEquals(InputWorkflowRunner.TIME_SERIES_ERROR, error.get());
    }

    @Test
    public void batchChoiceUsesDedicatedMacroArgument() {
        final AtomicBoolean batchRan = new AtomicBoolean(false);
        final AtomicReference<String> argument = new AtomicReference<>();
        final InputWorkflowRunner runner = runner(
            () -> null,
            () -> InputMode.BATCH_FOLDER,
            () -> { throw new AssertionError("file selector should not run"); },
            path -> { throw new AssertionError("loader should not run"); },
            ignored -> { },
            argument::set,
            () -> batchRan.set(true),
            message -> { throw new AssertionError(message); }
        );

        runner.run();

        assertTrue(batchRan.get());
        assertNull(argument.get());
    }

    @Test
    public void selectedSingleImageIsLoadedPresentedAndHandedOffById() {
        final Path path = Paths.get("fixture.lsm");
        final ImagePlus image = IJ.createHyperStack("loaded", 8, 8, 2, 1, 1, 8);
        final AtomicReference<Path> loadedPath = new AtomicReference<>();
        final AtomicReference<ImagePlus> presented = new AtomicReference<>();
        final AtomicReference<String> argument = new AtomicReference<>();
        final InputWorkflowRunner runner = runner(
            () -> null,
            () -> InputMode.SINGLE_IMAGE,
            () -> path,
            selected -> {
                loadedPath.set(selected);
                return image;
            },
            presented::set,
            argument::set,
            () -> { throw new AssertionError("batch runner should not run"); },
            message -> { throw new AssertionError(message); }
        );

        runner.run();

        assertEquals(path, loadedPath.get());
        assertSame(image, presented.get());
        assertEquals(InputWorkflowRunner.IMAGE_ARGUMENT_PREFIX + image.getID(), argument.get());
    }

    @Test
    public void loaderFailureIsReportedWithoutMacroHandoff() {
        final AtomicReference<String> error = new AtomicReference<>();
        final AtomicReference<String> argument = new AtomicReference<>();
        final InputWorkflowRunner runner = runner(
            () -> null,
            () -> InputMode.SINGLE_IMAGE,
            () -> Paths.get("bad.gif"),
            path -> { throw new ImageLoadingException("Unsupported image format: bad.gif"); },
            ignored -> { },
            argument::set,
            () -> { throw new AssertionError("batch runner should not run"); },
            error::set
        );

        runner.run();

        assertNull(argument.get());
        assertEquals("Unsupported image format: bad.gif", error.get());
    }

    @Test
    public void canceledModeDoesNothing() {
        final AtomicBoolean macroRan = new AtomicBoolean(false);
        final InputWorkflowRunner runner = runner(
            () -> null,
            () -> null,
            () -> { throw new AssertionError("file selector should not run"); },
            path -> { throw new AssertionError("loader should not run"); },
            ignored -> { },
            argument -> macroRan.set(true),
            () -> { throw new AssertionError("batch runner should not run"); },
            message -> { throw new AssertionError(message); }
        );

        runner.run();

        assertFalse(macroRan.get());
    }

    private static InputWorkflowRunner runner(
        final java.util.function.Supplier<ImagePlus> currentImage,
        final java.util.function.Supplier<InputMode> modeSelector,
        final java.util.function.Supplier<Path> fileSelector,
        final java.util.function.Function<Path, ImagePlus> imageLoader,
        final java.util.function.Consumer<ImagePlus> imagePresenter,
        final java.util.function.Consumer<String> macroRunner,
        final WorkflowRunner batchRunner,
        final java.util.function.Consumer<String> errorReporter
    ) {
        return new InputWorkflowRunner(
            currentImage,
            modeSelector,
            fileSelector,
            imageLoader,
            imagePresenter,
            macroRunner,
            batchRunner,
            errorReporter
        );
    }
}
