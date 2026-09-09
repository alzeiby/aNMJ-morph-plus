package io.github.alzeiby.anmjmorphplus;

import ij.IJ;
import ij.ImagePlus;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class LegacyBatchFileProcessorTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void timeSeriesIsRejectedBeforePresentationOrMacro() throws Exception {
        final ImagePlus image = IJ.createHyperStack("time.tif", 8, 8, 2, 1, 2, 8);
        final AtomicBoolean presented = new AtomicBoolean(false);
        final AtomicBoolean macroRan = new AtomicBoolean(false);
        final LegacyBatchFileProcessor processor = new LegacyBatchFileProcessor(
            path -> image,
            ignored -> presented.set(true),
            argument -> { macroRan.set(true); return null; }
        );

        final BatchFileException error = assertThrows(
            BatchFileException.class,
            () -> processor.process(Path.of("time.tif"), resolver())
        );

        assertEquals(BatchFileException.Kind.PRECHECK, error.kind());
        assertEquals("T_GT_1", error.reasonCode());
        assertFalse(presented.get());
        assertFalse(macroRan.get());
    }

    @Test
    public void suppliedChoicesAreEncodedWithoutChangingOrdering() throws Exception {
        final ImagePlus image = IJ.createHyperStack("sample.tif", 8, 8, 3, 1, 1, 8);
        final AtomicReference<String> argument = new AtomicReference<>();
        final LegacyBatchFileProcessor processor = new LegacyBatchFileProcessor(
            path -> image,
            ignored -> { },
            value -> { argument.set(value); return null; }
        );

        processor.process(Path.of("sample.tif"), resolver());

        assertTrue(argument.get().contains("muscle-channel=1"));
        assertTrue(argument.get().contains("nerve-channel=2"));
        assertFalse(argument.get().contains("two-plane="));
    }

    @Test
    public void macroAbortIsClassifiedAsPerFileCancellation() throws Exception {
        final ImagePlus image = IJ.createHyperStack("sample.tif", 8, 8, 2, 1, 1, 8);
        final LegacyBatchFileProcessor processor = new LegacyBatchFileProcessor(
            path -> image,
            ignored -> { },
            argument -> "[aborted]"
        );

        final BatchFileException error = assertThrows(
            BatchFileException.class,
            () -> processor.process(Path.of("sample.tif"), resolver())
        );

        assertEquals(BatchFileException.Kind.CANCELLED, error.kind());
        assertEquals("USER_CANCELLED", error.reasonCode());
    }

    @Test
    public void ambiguousTwoPlaneChoiceIsPassedBackToMacro() throws Exception {
        final ImagePlus image = IJ.createHyperStack("two-plane.tif", 8, 8, 1, 2, 1, 8);
        final AtomicReference<String> argument = new AtomicReference<>();
        final LegacyBatchFileProcessor processor = new LegacyBatchFileProcessor(
            path -> image,
            ignored -> { },
            value -> { argument.set(value); return null; }
        );

        processor.process(Path.of("two-plane.tif"), resolver());

        assertTrue(argument.get().contains("two-plane=channels"));
        assertTrue(argument.get().contains("muscle-channel=1"));
        assertTrue(argument.get().contains("nerve-channel=2"));
    }

    private BatchChoiceResolver resolver() throws Exception {
        final Path root = temporaryFolder.newFolder().toPath();
        final BatchCheckpointStore store = new BatchCheckpointStore();
        return new BatchChoiceResolver(
            root,
            store,
            store.load(root),
            new BatchChoiceResolver.Prompter() {
                @Override
                public BatchChoiceResolver.PromptResult<BatchChoiceResolver.TwoPlaneChoice> promptTwoPlane(final InputSignature signature) {
                    return new BatchChoiceResolver.PromptResult<>(BatchChoiceResolver.TwoPlaneChoice.CHANNELS, false);
                }

                @Override
                public BatchChoiceResolver.PromptResult<BatchChoiceResolver.ChannelChoice> promptChannels(final InputSignature signature, final int channelCount) {
                    return new BatchChoiceResolver.PromptResult<>(new BatchChoiceResolver.ChannelChoice(1, 2), false);
                }
            }
        );
    }
}
