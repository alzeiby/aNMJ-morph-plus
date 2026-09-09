package io.github.alzeiby.anmjmorphplus;

import ij.IJ;
import ij.ImagePlus;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;

public class BatchChoiceResolverTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void rememberedChoicesRequireExplicitOptInAndSurviveReload() throws Exception {
        final Path root = temporaryFolder.newFolder("batch").toPath();
        final BatchCheckpointStore store = new BatchCheckpointStore();
        final BatchCheckpointStore.Session session = store.load(root);
        final AtomicInteger channelPrompts = new AtomicInteger();
        final AtomicInteger planePrompts = new AtomicInteger();
        final BatchChoiceResolver.Prompter prompter = new BatchChoiceResolver.Prompter() {
            @Override
            public BatchChoiceResolver.PromptResult<TwoPlaneInterpretation> promptTwoPlane(final String signature) {
                planePrompts.incrementAndGet();
                return new BatchChoiceResolver.PromptResult<>(TwoPlaneInterpretation.CHANNELS, true);
            }

            @Override
            public BatchChoiceResolver.PromptResult<BatchChoiceResolver.ChannelChoice> promptChannels(final String signature, final int channelCount) {
                final int count = channelPrompts.incrementAndGet();
                return new BatchChoiceResolver.PromptResult<>(new BatchChoiceResolver.ChannelChoice(1, 2), count > 1);
            }
        };
        final BatchChoiceResolver resolver = new BatchChoiceResolver(root, store, session, prompter);
        final String channelSignature = BatchChoiceResolver.signature(
            SupportedImageFormat.TIFF,
            image(16, 12, 2, 1, 1),
            InputNormalization.USE_AS_IS,
            null
        );
        final String planeSignature = BatchChoiceResolver.signature(
            SupportedImageFormat.TIFF,
            image(16, 12, 1, 2, 1),
            InputNormalization.CHOOSE_TWO_PLANE_INTERPRETATION,
            null
        );

        resolver.resolveChannels(channelSignature, 2);
        resolver.resolveChannels(channelSignature, 2);
        resolver.resolveChannels(channelSignature, 2);
        resolver.resolveTwoPlane(planeSignature);
        resolver.resolveTwoPlane(planeSignature);

        assertEquals(2, channelPrompts.get());
        assertEquals(1, planePrompts.get());

        final BatchCheckpointStore.Session reloaded = store.load(root);
        final BatchChoiceResolver reloadedResolver = new BatchChoiceResolver(root, store, reloaded, new FailingPrompter());
        assertEquals(1, reloadedResolver.resolveChannels(channelSignature, 2).muscleEndplateChannel);
        assertEquals(TwoPlaneInterpretation.CHANNELS, reloadedResolver.resolveTwoPlane(planeSignature));
    }

    @Test
    public void differentSignaturesDoNotShareChoices() throws Exception {
        final Path root = temporaryFolder.newFolder("different").toPath();
        final BatchCheckpointStore store = new BatchCheckpointStore();
        final AtomicInteger prompts = new AtomicInteger();
        final BatchChoiceResolver resolver = new BatchChoiceResolver(
            root,
            store,
            store.load(root),
            new BatchChoiceResolver.Prompter() {
                @Override
                public BatchChoiceResolver.PromptResult<TwoPlaneInterpretation> promptTwoPlane(final String signature) {
                    throw new AssertionError("not used");
                }

                @Override
                public BatchChoiceResolver.PromptResult<BatchChoiceResolver.ChannelChoice> promptChannels(final String signature, final int channelCount) {
                    prompts.incrementAndGet();
                    return new BatchChoiceResolver.PromptResult<>(new BatchChoiceResolver.ChannelChoice(1, 2), true);
                }
            }
        );

        resolver.resolveChannels(BatchChoiceResolver.signature(SupportedImageFormat.TIFF, image(16, 12, 2, 1, 1), InputNormalization.USE_AS_IS, null), 2);
        resolver.resolveChannels(BatchChoiceResolver.signature(SupportedImageFormat.LSM, image(16, 12, 2, 1, 1), InputNormalization.USE_AS_IS, null), 2);

        assertEquals(2, prompts.get());
    }

    @Test
    public void differentImageDimensionsDoNotShareRememberedChannels() throws Exception {
        final Path root = temporaryFolder.newFolder("dimensions").toPath();
        final BatchCheckpointStore store = new BatchCheckpointStore();
        final AtomicInteger prompts = new AtomicInteger();
        final BatchChoiceResolver resolver = new BatchChoiceResolver(
            root,
            store,
            store.load(root),
            new BatchChoiceResolver.Prompter() {
                @Override
                public BatchChoiceResolver.PromptResult<TwoPlaneInterpretation> promptTwoPlane(final String signature) {
                    throw new AssertionError("not used");
                }

                @Override
                public BatchChoiceResolver.PromptResult<BatchChoiceResolver.ChannelChoice> promptChannels(final String signature, final int channelCount) {
                    prompts.incrementAndGet();
                    return new BatchChoiceResolver.PromptResult<>(new BatchChoiceResolver.ChannelChoice(1, 2), true);
                }
            }
        );

        resolver.resolveChannels(BatchChoiceResolver.signature(SupportedImageFormat.TIFF, image(16, 12, 2, 1, 1), InputNormalization.USE_AS_IS, null), 2);
        resolver.resolveChannels(BatchChoiceResolver.signature(SupportedImageFormat.TIFF, image(32, 12, 2, 1, 1), InputNormalization.USE_AS_IS, null), 2);

        assertEquals(2, prompts.get());
    }

    @Test
    public void signaturePreservesExactLegacyImageShapeFields() {
        final ImagePlus image = image(16, 12, 2, 3, 1);

        assertEquals(
            "format=TIFF|w=16|h=12|c=2|z=3|t=1|bit=8|normalization=MAX_PROJECT_Z",
            BatchChoiceResolver.signature(SupportedImageFormat.TIFF, image, InputNormalization.MAX_PROJECT_Z, null)
        );
    }

    @Test
    public void signaturePreservesInterpretationSuffix() {
        final ImagePlus image = image(16, 12, 1, 2, 1);

        assertEquals(
            "format=LSM|w=16|h=12|c=1|z=2|t=1|bit=8|normalization=CHOOSE_TWO_PLANE_INTERPRETATION|interpretation=CHANNELS",
            BatchChoiceResolver.signature(
                SupportedImageFormat.LSM,
                image,
                InputNormalization.CHOOSE_TWO_PLANE_INTERPRETATION,
                TwoPlaneInterpretation.CHANNELS.name()
            )
        );
    }

    private static final class FailingPrompter implements BatchChoiceResolver.Prompter {
        @Override
        public BatchChoiceResolver.PromptResult<TwoPlaneInterpretation> promptTwoPlane(final String signature) {
            throw new AssertionError("remembered two-plane choice should be reused");
        }

        @Override
        public BatchChoiceResolver.PromptResult<BatchChoiceResolver.ChannelChoice> promptChannels(final String signature, final int channelCount) {
            throw new AssertionError("remembered channel choice should be reused");
        }
    }

    private static ImagePlus image(final int width, final int height, final int channels, final int slices, final int frames) {
        return IJ.createHyperStack("fixture", width, height, channels, slices, frames, 8);
    }
}
