package io.github.alzeiby.anmjmorphplus;

import ij.ImagePlus;
import ij.WindowManager;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;

final class LegacyBatchFileProcessor implements BatchSessionRunner.FileProcessor {

    private final Function<Path, ImagePlus> imageLoader;
    private final Consumer<ImagePlus> imagePresenter;
    private final Function<String, String> macroRunner;
    private final StructuralNormalizer structuralNormalizer;

    LegacyBatchFileProcessor() {
        final ImageLoader loader = new ImageLoader();
        final LegacyMacroRunner macro = new LegacyMacroRunner();
        this.imageLoader = loader::load;
        this.imagePresenter = ImagePlus::show;
        this.macroRunner = macro::runForResult;
        this.structuralNormalizer = new StructuralNormalizer();
    }

    LegacyBatchFileProcessor(
        final Function<Path, ImagePlus> imageLoader,
        final Consumer<ImagePlus> imagePresenter,
        final Function<String, String> macroRunner,
        final StructuralNormalizer structuralNormalizer
    ) {
        this.imageLoader = Objects.requireNonNull(imageLoader, "imageLoader");
        this.imagePresenter = Objects.requireNonNull(imagePresenter, "imagePresenter");
        this.macroRunner = Objects.requireNonNull(macroRunner, "macroRunner");
        this.structuralNormalizer = Objects.requireNonNull(structuralNormalizer, "structuralNormalizer");
    }

    @Override
    public void process(final Path path, final BatchChoiceResolver choices) {
        final Set<Integer> existingImageIds = currentImageIds();
        try {
            ImagePlus image;
            try {
                image = imageLoader.apply(path);
            } catch (ImageLoadingException e) {
                final String reason = e.getMessage() != null && e.getMessage().contains("single-series")
                    ? "MULTI_SERIES_UNSUPPORTED"
                    : "OPEN_FAILED";
                throw BatchFileException.precheck(reason, e.getMessage());
            }

            final ImageShape shape = ImageShape.from(image);
            final InputNormalization normalization = InputPolicy.normalizationFor(shape);
            if (normalization == InputNormalization.REJECT_TIME_SERIES) {
                image.close();
                throw BatchFileException.precheck("T_GT_1", InputWorkflowRunner.TIME_SERIES_ERROR);
            }

            final SupportedImageFormat format = SupportedImageFormat.fromName(path.getFileName().toString())
                .orElseThrow(() -> BatchFileException.precheck("UNSUPPORTED_FORMAT", "Unsupported image format"));

            TwoPlaneInterpretation twoPlaneChoice = null;
            boolean presented = false;
            if (normalization == InputNormalization.CHOOSE_TWO_PLANE_INTERPRETATION) {
                imagePresenter.accept(image);
                presented = true;
                final InputSignature twoPlaneSignature = InputSignature.of(format, shape, normalization, null);
                twoPlaneChoice = choices.resolveTwoPlane(twoPlaneSignature);
            }
            final ImagePlus normalized = structuralNormalizer.normalize(image, twoPlaneChoice);
            if (normalized != image) {
                image.changes = false;
                image.close();
                image = normalized;
                presented = false;
            }
            if (!presented) {
                imagePresenter.accept(image);
            }
            final int effectiveChannels = image.getNChannels();

            if (effectiveChannels < 2) {
                image.close();
                throw BatchFileException.precheck(
                    "INVALID_CHANNEL_SELECTION",
                    "At least two channels are required to select muscle endplate and nerve terminal"
                );
            }

            final String interpretation = twoPlaneChoice == null ? null : twoPlaneChoice.name();
            final InputSignature channelSignature = InputSignature.of(format, shape, normalization, interpretation);
            final BatchChoiceResolver.ChannelChoice channelChoice =
                choices.resolveChannels(channelSignature, effectiveChannels);

            final String argument = buildMacroArgument(image, channelChoice);
            final String result;
            try {
                result = macroRunner.apply(argument);
            } catch (RuntimeException e) {
                throw BatchFileException.runtime("MACRO_ERROR", messageOrClass(e), e);
            }
            if ("[aborted]".equals(result)) {
                throw BatchFileException.cancelled("Macro execution was cancelled");
            }
        } finally {
            closeImagesCreatedAfter(existingImageIds);
        }
    }

    static String buildMacroArgument(
        final ImagePlus image,
        final BatchChoiceResolver.ChannelChoice channelChoice
    ) {
        final StringBuilder argument = new StringBuilder()
            .append("image-id=").append(image.getID())
            .append(";muscle-channel=").append(channelChoice.muscleEndplateChannel())
            .append(";nerve-channel=").append(channelChoice.nerveTerminalChannel());
        return argument.toString();
    }

    private static Set<Integer> currentImageIds() {
        final Set<Integer> ids = new HashSet<>();
        final int[] current = WindowManager.getIDList();
        if (current != null) {
            for (int id : current) {
                ids.add(id);
            }
        }
        return ids;
    }

    private static void closeImagesCreatedAfter(final Set<Integer> existingImageIds) {
        final int[] current = WindowManager.getIDList();
        if (current == null) {
            return;
        }
        for (int id : current) {
            if (!existingImageIds.contains(id)) {
                final ImagePlus image = WindowManager.getImage(id);
                if (image != null) {
                    image.changes = false;
                    image.close();
                }
            }
        }
    }

    private static String messageOrClass(final RuntimeException error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
