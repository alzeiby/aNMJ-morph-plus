package io.github.alzeiby.anmjmorphplus;

import ij.IJ;
import ij.CompositeImage;
import ij.ImagePlus;
import ij.ImageStack;
import ij.io.FileInfo;
import ij.macro.Interpreter;
import ij.process.ByteProcessor;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
            argument -> { macroRan.set(true); return null; },
            new StructuralNormalizer()
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
            value -> { argument.set(value); return null; },
            new StructuralNormalizer()
        );

        processor.process(Path.of("sample.tif"), resolver());

        assertTrue(argument.get().contains("muscle-channel=1"));
        assertTrue(argument.get().contains("nerve-channel=2"));
        assertFalse(argument.get().contains("two-plane="));
    }

    @Test
    public void threeChannelChoiceThreeOneIsCanonicalizedAndMacroGetsFixedOneTwo() throws Exception {
        final ImageStack stack = new ImageStack(1, 1);
        stack.addSlice(new ByteProcessor(1, 1, new byte[] {11}, null));
        stack.addSlice(new ByteProcessor(1, 1, new byte[] {22}, null));
        stack.addSlice(new ByteProcessor(1, 1, new byte[] {33}, null));
        final AtomicInteger closes = new AtomicInteger();
        final ImagePlus source = new ImagePlus("three.tif", stack) {
            @Override
            public void close() {
                closes.incrementAndGet();
                super.close();
            }
        };
        source.setDimensions(3, 1, 1);
        source.setOpenAsHyperStack(true);
        final FileInfo fileInfo = new FileInfo();
        fileInfo.fileName = "three.tif";
        fileInfo.directory = "C:\\source data\\";
        source.setFileInfo(fileInfo);
        final List<ImagePlus> presented = new ArrayList<>();
        final AtomicReference<String> argument = new AtomicReference<>();
        final LegacyBatchFileProcessor processor = new LegacyBatchFileProcessor(
            path -> source,
            presented::add,
            value -> { argument.set(value); return null; },
            new StructuralNormalizer()
        );

        processor.process(Path.of("three.tif"), resolver(3, 1));

        assertEquals(2, presented.size());
        assertTrue(presented.get(0) == source);
        final ImagePlus canonical = presented.get(1);
        assertTrue(canonical != source);
        assertEquals(1, closes.get());
        assertTrue(source.getID() != canonical.getID());
        assertEquals(2, canonical.getNChannels());
        assertEquals(33, canonical.getStack().getProcessor(1).get(0, 0));
        assertEquals(11, canonical.getStack().getProcessor(2).get(0, 0));
        assertEquals("three.tif", canonical.getTitle());
        assertEquals("C:\\source data\\", canonical.getOriginalFileInfo().directory);
        assertTrue(argument.get().contains("image-id=" + canonical.getID()));
        assertTrue(argument.get().contains("muscle-channel=1"));
        assertTrue(argument.get().contains("nerve-channel=2"));
        assertFalse(argument.get().contains("muscle-channel=3"));
    }

    @Test
    public void twoChannelSwapIsCanonicalizedBeforeMacro() throws Exception {
        final ImageStack stack = new ImageStack(1, 1);
        stack.addSlice(new ByteProcessor(1, 1, new byte[] {7}, null));
        stack.addSlice(new ByteProcessor(1, 1, new byte[] {19}, null));
        final ImagePlus source = new ImagePlus("swap.tif", stack);
        source.setDimensions(2, 1, 1);
        source.setOpenAsHyperStack(true);
        final List<ImagePlus> presented = new ArrayList<>();
        final AtomicReference<String> argument = new AtomicReference<>();
        final LegacyBatchFileProcessor processor = new LegacyBatchFileProcessor(
            path -> source,
            presented::add,
            value -> { argument.set(value); return null; },
            new StructuralNormalizer()
        );

        processor.process(Path.of("swap.tif"), resolver(2, 1));

        assertEquals(2, presented.size());
        final ImagePlus canonical = presented.get(1);
        assertEquals(19, canonical.getStack().getProcessor(1).get(0, 0));
        assertEquals(7, canonical.getStack().getProcessor(2).get(0, 0));
        assertTrue(argument.get().contains("image-id=" + canonical.getID()));
        assertTrue(argument.get().contains("muscle-channel=1"));
        assertTrue(argument.get().contains("nerve-channel=2"));
    }

    @Test
    public void macroAbortIsClassifiedAsPerFileCancellation() throws Exception {
        final ImagePlus image = IJ.createHyperStack("sample.tif", 8, 8, 2, 1, 1, 8);
        final LegacyBatchFileProcessor processor = new LegacyBatchFileProcessor(
            path -> image,
            ignored -> { },
            argument -> "[aborted]",
            new StructuralNormalizer()
        );

        final BatchFileException error = assertThrows(
            BatchFileException.class,
            () -> processor.process(Path.of("sample.tif"), resolver())
        );

        assertEquals(BatchFileException.Kind.CANCELLED, error.kind());
        assertEquals("USER_CANCELLED", error.reasonCode());
    }

    @Test
    public void ambiguousTwoPlaneChannelsAreNormalizedBeforeMacro() throws Exception {
        final ImagePlus image = IJ.createHyperStack("two-plane.tif", 8, 8, 1, 2, 1, 8);
        final AtomicReference<String> argument = new AtomicReference<>();
        final LegacyBatchFileProcessor processor = new LegacyBatchFileProcessor(
            path -> image,
            ignored -> { },
            value -> { argument.set(value); return null; },
            new StructuralNormalizer()
        );

        processor.process(Path.of("two-plane.tif"), resolver());

        assertEquals(2, image.getNChannels());
        assertEquals(1, image.getNSlices());
        assertFalse(argument.get().contains("two-plane="));
        assertTrue(argument.get().contains("muscle-channel=1"));
        assertTrue(argument.get().contains("nerve-channel=2"));
    }

    @Test
    public void ambiguousTwoPlaneChannelsCanBeSwappedThenMacroGetsFixedOneTwo() throws Exception {
        final ImageStack stack = new ImageStack(1, 1);
        stack.addSlice(new ByteProcessor(1, 1, new byte[] {13}, null));
        stack.addSlice(new ByteProcessor(1, 1, new byte[] {29}, null));
        final ImagePlus image = new ImagePlus("two-plane-swap.tif", stack);
        image.setDimensions(1, 2, 1);
        image.setOpenAsHyperStack(true);
        final List<ImagePlus> presented = new ArrayList<>();
        final AtomicReference<String> argument = new AtomicReference<>();
        final LegacyBatchFileProcessor processor = new LegacyBatchFileProcessor(
            path -> image,
            presented::add,
            value -> { argument.set(value); return null; },
            new StructuralNormalizer()
        );

        processor.process(
            Path.of("two-plane-swap.tif"),
            resolver(TwoPlaneInterpretation.CHANNELS, 2, 1)
        );

        assertEquals(2, presented.size());
        final ImagePlus canonical = presented.get(1);
        assertEquals(2, canonical.getNChannels());
        assertEquals(1, canonical.getNSlices());
        assertEquals(29, canonical.getStack().getProcessor(1).get(0, 0));
        assertEquals(13, canonical.getStack().getProcessor(2).get(0, 0));
        assertTrue(argument.get().contains("image-id=" + canonical.getID()));
        assertTrue(argument.get().contains("muscle-channel=1"));
        assertTrue(argument.get().contains("nerve-channel=2"));
    }

    @Test
    public void ambiguousTwoPlaneZReplacementIsPresentedBeforeSingleChannelPrecheck() throws Exception {
        final ImageStack stack = new ImageStack(1, 1);
        stack.addSlice(new ByteProcessor(1, 1, new byte[] {31}, null));
        stack.addSlice(new ByteProcessor(1, 1, new byte[] {17}, null));
        final AtomicBoolean originalClosed = new AtomicBoolean(false);
        final ImagePlus image = new ImagePlus("two-plane-z.tif", stack) {
            @Override
            public void close() {
                originalClosed.set(true);
                super.close();
            }
        };
        image.setDimensions(1, 2, 1);
        image.setOpenAsHyperStack(true);
        final java.util.List<ImagePlus> presented = new java.util.ArrayList<>();
        final AtomicBoolean macroRan = new AtomicBoolean(false);
        final LegacyBatchFileProcessor processor = new LegacyBatchFileProcessor(
            path -> image,
            presented::add,
            argument -> { macroRan.set(true); return null; },
            new StructuralNormalizer()
        );

        final BatchFileException error = assertThrows(
            BatchFileException.class,
            () -> processor.process(Path.of("two-plane-z.tif"), resolver(TwoPlaneInterpretation.Z_STACK))
        );

        assertEquals(BatchFileException.Kind.PRECHECK, error.kind());
        assertEquals("INVALID_CHANNEL_SELECTION", error.reasonCode());
        assertEquals(2, presented.size());
        assertTrue(presented.get(0) == image);
        assertTrue(presented.get(1) != image);
        assertEquals(1, presented.get(1).getNChannels());
        assertEquals(1, presented.get(1).getNSlices());
        assertEquals(31, presented.get(1).getProcessor().get(0, 0));
        assertTrue(originalClosed.get());
        assertFalse(macroRan.get());
    }

    @Test
    public void rgbIsStructurallyNormalizedThenCanonicalizedBeforeMacro() throws Exception {
        final AtomicBoolean originalClosed = new AtomicBoolean(false);
        final ImagePlus base = IJ.createImage("rgb.png", "RGB black", 2, 1, 1);
        final ImagePlus rgb = new ImagePlus(base.getTitle(), base.getProcessor()) {
            @Override
            public void close() {
                originalClosed.set(true);
                super.close();
            }
        };
        rgb.getProcessor().set(0, 0, 0xff123456);
        rgb.getProcessor().set(1, 0, 0xffa1b2c3);
        final AtomicReference<ImagePlus> presented = new AtomicReference<>();
        final AtomicReference<String> argument = new AtomicReference<>();
        final LegacyBatchFileProcessor processor = new LegacyBatchFileProcessor(
            path -> rgb,
            presented::set,
            value -> { argument.set(value); return null; },
            new StructuralNormalizer()
        );

        processor.process(Path.of("rgb.png"), resolver());

        final ImagePlus normalized = presented.get();
        assertTrue(normalized != rgb);
        assertTrue(originalClosed.get());
        assertTrue(normalized instanceof CompositeImage);
        assertEquals(2, normalized.getNChannels());
        assertEquals(0x12, normalized.getStack().getProcessor(1).get(0, 0));
        assertEquals(0x34, normalized.getStack().getProcessor(2).get(0, 0));
        assertTrue(argument.get().contains("image-id=" + normalized.getID()));
        assertTrue(argument.get().contains("muscle-channel=1"));
        assertTrue(argument.get().contains("nerve-channel=2"));
        assertFalse(argument.get().contains("two-plane="));
    }

    @Test
    public void multichannelZIsMaximumProjectedBeforeMacro() throws Exception {
        final ImageStack stack = new ImageStack(1, 1);
        stack.addSlice(new ByteProcessor(1, 1, new byte[] {3}, null));
        stack.addSlice(new ByteProcessor(1, 1, new byte[] {20}, null));
        stack.addSlice(new ByteProcessor(1, 1, new byte[] {9}, null));
        stack.addSlice(new ByteProcessor(1, 1, new byte[] {7}, null));
        final AtomicBoolean originalClosed = new AtomicBoolean(false);
        final ImagePlus image = new ImagePlus("z.lsm", stack) {
            @Override
            public void close() {
                originalClosed.set(true);
                super.close();
            }
        };
        image.setDimensions(2, 2, 1);
        image.setOpenAsHyperStack(true);
        final FileInfo fileInfo = new FileInfo();
        fileInfo.fileName = "z.lsm";
        fileInfo.directory = "C:\\source data\\";
        image.setFileInfo(fileInfo);
        final AtomicReference<ImagePlus> presented = new AtomicReference<>();
        final AtomicReference<String> argument = new AtomicReference<>();
        final LegacyBatchFileProcessor processor = new LegacyBatchFileProcessor(
            path -> image,
            presented::set,
            value -> { argument.set(value); return null; },
            new StructuralNormalizer()
        );

        processor.process(Path.of("z.lsm"), resolver());

        final ImagePlus normalized = presented.get();
        assertTrue(normalized != image);
        assertTrue(originalClosed.get());
        assertEquals(2, normalized.getNChannels());
        assertEquals(1, normalized.getNSlices());
        assertEquals(9, normalized.getStack().getProcessor(1).get(0, 0));
        assertEquals(20, normalized.getStack().getProcessor(2).get(0, 0));
        assertEquals("z.lsm", normalized.getTitle());
        assertEquals("z.lsm", normalized.getOriginalFileInfo().fileName);
        assertEquals("C:\\source data\\", normalized.getOriginalFileInfo().directory);
        assertTrue(argument.get().contains("image-id=" + normalized.getID()));
    }

    @Test
    public void rememberedThreeOneChoiceStaysOriginalWhileMacroGetsCanonicalOneTwo() throws Exception {
        final ImageStack stack = new ImageStack(1, 1);
        stack.addSlice(new ByteProcessor(1, 1, new byte[] {11}, null));
        stack.addSlice(new ByteProcessor(1, 1, new byte[] {22}, null));
        stack.addSlice(new ByteProcessor(1, 1, new byte[] {33}, null));
        final ImagePlus image = new ImagePlus("remember.tif", stack);
        image.setDimensions(3, 1, 1);
        image.setOpenAsHyperStack(true);
        final Path root = temporaryFolder.newFolder("remembered-three-one").toPath();
        final BatchCheckpointStore store = new BatchCheckpointStore();
        final BatchCheckpointStore.Session session = store.load(root);
        final BatchChoiceResolver resolver = new BatchChoiceResolver(
            root,
            store,
            session,
            new BatchChoiceResolver.Prompter() {
                @Override
                public BatchChoiceResolver.PromptResult<TwoPlaneInterpretation> promptTwoPlane(final InputSignature signature) {
                    throw new AssertionError("not used");
                }

                @Override
                public BatchChoiceResolver.PromptResult<BatchChoiceResolver.ChannelChoice> promptChannels(
                    final InputSignature signature,
                    final int channelCount
                ) {
                    return new BatchChoiceResolver.PromptResult<>(
                        new BatchChoiceResolver.ChannelChoice(3, 1),
                        true
                    );
                }
            }
        );
        final AtomicReference<String> argument = new AtomicReference<>();
        final LegacyBatchFileProcessor processor = new LegacyBatchFileProcessor(
            path -> image,
            ignored -> { },
            value -> { argument.set(value); return null; },
            new StructuralNormalizer()
        );

        processor.process(Path.of("remember.tif"), resolver);

        assertTrue(argument.get().contains("muscle-channel=1"));
        assertTrue(argument.get().contains("nerve-channel=2"));
        final InputSignature signature = InputSignature.of(
            SupportedImageFormat.TIFF,
            new ImageShape(1, 1, 3, 1, 1, 8),
            InputNormalization.USE_AS_IS,
            null
        );
        final String key = "channels|" + signature.value();
        assertEquals("3,1", store.load(root).choices.get(key));
    }

    @Test
    public void moreThanNineChannelsKeepsLegacyMacroChannelIndices() throws Exception {
        final ImageStack stack = new ImageStack(1, 1);
        for (int channel = 1; channel <= 10; channel++) {
            stack.addSlice(new ByteProcessor(1, 1, new byte[] {(byte) channel}, null));
        }
        final ImagePlus image = new ImagePlus("ten.tif", stack);
        image.setDimensions(10, 1, 1);
        image.setOpenAsHyperStack(true);
        final List<ImagePlus> presented = new ArrayList<>();
        final AtomicReference<String> argument = new AtomicReference<>();
        final LegacyBatchFileProcessor processor = new LegacyBatchFileProcessor(
            path -> image,
            presented::add,
            value -> { argument.set(value); return null; },
            new StructuralNormalizer()
        );

        processor.process(Path.of("ten.tif"), resolver(3, 1));

        assertEquals(1, presented.size());
        assertTrue(presented.get(0) == image);
        assertEquals(10, image.getNChannels());
        assertTrue(argument.get().contains("muscle-channel=3"));
        assertTrue(argument.get().contains("nerve-channel=1"));
    }

    @Test
    public void preexistingSentinelImageIsNotClosedByCanonicalizationCleanup() throws Exception {
        final boolean previousBatchMode = Interpreter.batchMode;
        final AtomicInteger sentinelCloses = new AtomicInteger();
        final ImagePlus sentinel = new ImagePlus("sentinel", new ByteProcessor(1, 1)) {
            @Override
            public void close() {
                sentinelCloses.incrementAndGet();
                super.close();
            }
        };
        Interpreter.batchMode = true;
        Interpreter.addBatchModeImage(sentinel);
        try {
            final ImageStack stack = new ImageStack(1, 1);
            stack.addSlice(new ByteProcessor(1, 1, new byte[] {11}, null));
            stack.addSlice(new ByteProcessor(1, 1, new byte[] {22}, null));
            stack.addSlice(new ByteProcessor(1, 1, new byte[] {33}, null));
            final ImagePlus image = new ImagePlus("sample.tif", stack);
            image.setDimensions(3, 1, 1);
            image.setOpenAsHyperStack(true);
            final LegacyBatchFileProcessor processor = new LegacyBatchFileProcessor(
                path -> image,
                ignored -> { },
                argument -> null,
                new StructuralNormalizer()
            );

            processor.process(Path.of("sample.tif"), resolver(3, 1));

            assertEquals(0, sentinelCloses.get());
            boolean sentinelStillRegistered = false;
            for (int id : Interpreter.getBatchModeImageIDs()) {
                if (id == sentinel.getID()) {
                    sentinelStillRegistered = true;
                    break;
                }
            }
            assertTrue(sentinelStillRegistered);
        } finally {
            Interpreter.removeBatchModeImage(sentinel);
            Interpreter.batchMode = previousBatchMode;
        }
    }

    private BatchChoiceResolver resolver() throws Exception {
        return resolver(TwoPlaneInterpretation.CHANNELS);
    }

    private BatchChoiceResolver resolver(final TwoPlaneInterpretation twoPlaneInterpretation) throws Exception {
        return resolver(twoPlaneInterpretation, 1, 2);
    }

    private BatchChoiceResolver resolver(final int muscle, final int nerve) throws Exception {
        return resolver(TwoPlaneInterpretation.CHANNELS, muscle, nerve);
    }

    private BatchChoiceResolver resolver(
        final TwoPlaneInterpretation twoPlaneInterpretation,
        final int muscle,
        final int nerve
    ) throws Exception {
        final Path root = temporaryFolder.newFolder().toPath();
        final BatchCheckpointStore store = new BatchCheckpointStore();
        return new BatchChoiceResolver(
            root,
            store,
            store.load(root),
            new BatchChoiceResolver.Prompter() {
                @Override
                public BatchChoiceResolver.PromptResult<TwoPlaneInterpretation> promptTwoPlane(final InputSignature signature) {
                    return new BatchChoiceResolver.PromptResult<>(twoPlaneInterpretation, false);
                }

                @Override
                public BatchChoiceResolver.PromptResult<BatchChoiceResolver.ChannelChoice> promptChannels(final InputSignature signature, final int channelCount) {
                    return new BatchChoiceResolver.PromptResult<>(new BatchChoiceResolver.ChannelChoice(muscle, nerve), false);
                }
            }
        );
    }
}
