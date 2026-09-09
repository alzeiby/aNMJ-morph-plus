package io.github.alzeiby.anmjmorphplus;

import ij.ImagePlus;
import ij.WindowManager;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;

final class JavaBatchFileProcessor implements BiConsumer<Path, BatchChoiceResolver> {

    private final ImageLoader imageLoader = new ImageLoader();
    private final SingleImageAnalysisRunner analysisRunner = new SingleImageAnalysisRunner();

    @Override
    public void accept(final Path path, final BatchChoiceResolver choices) {
        final Set<Integer> existingImageIds = currentImageIds();
        try {
            ImagePlus image = load(path);
            final InputNormalization normalization = StructuralNormalizer.normalizationFor(image);
            if (normalization == InputNormalization.REJECT_TIME_SERIES) {
                image.close();
                throw BatchFileException.precheck("T_GT_1", InputWorkflowRunner.TIME_SERIES_ERROR);
            }

            final SupportedImageFormat format = SupportedImageFormat.fromName(path.getFileName().toString())
                .orElseThrow(() -> BatchFileException.precheck("UNSUPPORTED_FORMAT", "Unsupported image format"));

            TwoPlaneInterpretation twoPlaneChoice = null;
            if (normalization == InputNormalization.CHOOSE_TWO_PLANE_INTERPRETATION) {
                image.show();
                twoPlaneChoice = choices.resolveTwoPlane(BatchChoiceResolver.signature(format, image, normalization, null));
            }
            final String interpretation = twoPlaneChoice == null ? null : twoPlaneChoice.name();
            final String channelSignature = BatchChoiceResolver.signature(format, image, normalization, interpretation);

            image = analysisRunner.normalizeAndPresent(image, twoPlaneChoice, true);

            final int channelCount = image.getNChannels();
            if (channelCount < 2) {
                image.close();
                throw BatchFileException.precheck(
                    "INVALID_CHANNEL_SELECTION",
                    "At least two channels are required to select muscle endplate and nerve terminal"
                );
            }

            final BatchChoiceResolver.ChannelChoice selected = choices.resolveChannels(
                channelSignature,
                channelCount
            );

            BatchChoiceResolver.ChannelChoice analysisChoice = selected;
            boolean channelsCanonical = false;
            if (channelCount <= SingleImageAnalysisRunner.IMAGEJ_ARRANGER_MAX_CHANNELS) {
                image = analysisRunner.canonicalizeSelected(image, selected);
                analysisChoice = new BatchChoiceResolver.ChannelChoice(1, 2);
                channelsCanonical = image.getNChannels() == 2 && image.isComposite();
            }

            try {
                analysisRunner.analyzeCanonical(image, path, analysisChoice, channelsCanonical);
            } catch (AnalysisCancelledException e) {
                throw BatchFileException.cancelled("Analysis was cancelled");
            } catch (BatchFileException e) {
                throw e;
            } catch (RuntimeException e) {
                throw BatchFileException.runtime(
                    "ANALYSIS_FAILED",
                    Objects.toString(e.getMessage(), e.getClass().getSimpleName()),
                    e
                );
            }
        } finally {
            closeImagesCreatedAfter(existingImageIds);
        }
    }

    private ImagePlus load(final Path path) {
        try {
            return imageLoader.load(path);
        } catch (ImageLoadingException e) {
            final String reason = e.getMessage() != null && e.getMessage().contains("single-series")
                ? "MULTI_SERIES_UNSUPPORTED"
                : "OPEN_FAILED";
            throw BatchFileException.precheck(reason, e.getMessage());
        }
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

}
