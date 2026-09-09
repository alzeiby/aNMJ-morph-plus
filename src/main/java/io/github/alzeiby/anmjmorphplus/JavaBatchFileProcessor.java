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
    private final StructuralNormalizer structuralNormalizer = new StructuralNormalizer();
    private final ChannelRoleCanonicalizer channelRoleCanonicalizer = new ChannelRoleCanonicalizer();
    private final AnalysisWorkflow analysisWorkflow = new AnalysisWorkflow();
    private final CsvOutputWriter outputWriter = new CsvOutputWriter();

    @Override
    public void accept(final Path path, final BatchChoiceResolver choices) {
        final Set<Integer> existingImageIds = currentImageIds();
        try {
            ImagePlus image = load(path);
            final InputNormalization normalization = InputPolicy.normalizationFor(image);
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

            final ImagePlus normalized = structuralNormalizer.normalize(image, twoPlaneChoice);
            if (normalized != image) {
                image.changes = false;
                image.close();
                image = normalized;
            }
            image.show();

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
            if (channelCount <= ChannelRoleCanonicalizer.IMAGEJ_ARRANGER_MAX_CHANNELS) {
                final ImagePlus canonical = channelRoleCanonicalizer.canonicalize(image, selected);
                if (canonical != image) {
                    image = canonical;
                    image.show();
                }
                analysisChoice = new BatchChoiceResolver.ChannelChoice(1, 2);
                channelsCanonical = image.getNChannels() == 2 && image.isComposite();
            }

            final AnalysisRun run;
            try {
                run = analysisWorkflow.analyze(image, path, analysisChoice, channelsCanonical);
                outputWriter.append(AnalysisOutputPaths.forInput(path).csv, run.result);
                analysisWorkflow.finish(run);
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
