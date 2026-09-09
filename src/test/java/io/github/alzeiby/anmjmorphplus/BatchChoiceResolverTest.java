package io.github.alzeiby.anmjmorphplus;

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
            public BatchChoiceResolver.PromptResult<BatchChoiceResolver.TwoPlaneChoice> promptTwoPlane(final InputSignature signature) {
                planePrompts.incrementAndGet();
                return new BatchChoiceResolver.PromptResult<>(BatchChoiceResolver.TwoPlaneChoice.CHANNELS, true);
            }

            @Override
            public BatchChoiceResolver.PromptResult<BatchChoiceResolver.ChannelChoice> promptChannels(final InputSignature signature, final int channelCount) {
                final int count = channelPrompts.incrementAndGet();
                return new BatchChoiceResolver.PromptResult<>(new BatchChoiceResolver.ChannelChoice(1, 2), count > 1);
            }
        };
        final BatchChoiceResolver resolver = new BatchChoiceResolver(root, store, session, prompter);
        final ImageShape shape = new ImageShape(16, 12, 2, 1, 1, 8);
        final InputSignature channelSignature = InputSignature.of(
            SupportedImageFormat.TIFF,
            shape,
            InputNormalization.USE_AS_IS,
            null
        );
        final InputSignature planeSignature = InputSignature.of(
            SupportedImageFormat.TIFF,
            new ImageShape(16, 12, 1, 2, 1, 8),
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
        assertEquals(1, reloadedResolver.resolveChannels(channelSignature, 2).muscleEndplateChannel());
        assertEquals(BatchChoiceResolver.TwoPlaneChoice.CHANNELS, reloadedResolver.resolveTwoPlane(planeSignature));
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
                public BatchChoiceResolver.PromptResult<BatchChoiceResolver.TwoPlaneChoice> promptTwoPlane(final InputSignature signature) {
                    throw new AssertionError("not used");
                }

                @Override
                public BatchChoiceResolver.PromptResult<BatchChoiceResolver.ChannelChoice> promptChannels(final InputSignature signature, final int channelCount) {
                    prompts.incrementAndGet();
                    return new BatchChoiceResolver.PromptResult<>(new BatchChoiceResolver.ChannelChoice(1, 2), true);
                }
            }
        );

        resolver.resolveChannels(InputSignature.of(SupportedImageFormat.TIFF, new ImageShape(16, 12, 2, 1, 1, 8), InputNormalization.USE_AS_IS, null), 2);
        resolver.resolveChannels(InputSignature.of(SupportedImageFormat.LSM, new ImageShape(16, 12, 2, 1, 1, 8), InputNormalization.USE_AS_IS, null), 2);

        assertEquals(2, prompts.get());
    }

    private static final class FailingPrompter implements BatchChoiceResolver.Prompter {
        @Override
        public BatchChoiceResolver.PromptResult<BatchChoiceResolver.TwoPlaneChoice> promptTwoPlane(final InputSignature signature) {
            throw new AssertionError("remembered two-plane choice should be reused");
        }

        @Override
        public BatchChoiceResolver.PromptResult<BatchChoiceResolver.ChannelChoice> promptChannels(final InputSignature signature, final int channelCount) {
            throw new AssertionError("remembered channel choice should be reused");
        }
    }
}
