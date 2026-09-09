package io.github.alzeiby.anmjmorphplus;

import ij.ImagePlus;
import ij.io.DirectoryChooser;
import ij.io.FileInfo;

import java.nio.file.Path;

final class SingleImageAnalysisRunner {

    private final StructuralNormalizer structuralNormalizer = new StructuralNormalizer();
    private final AnalysisWorkflow analysisWorkflow = new AnalysisWorkflow();
    private final CsvOutputWriter outputWriter = new CsvOutputWriter();

    void analyze(ImagePlus image, final Path selectedPath) {
        final Path inputPath = selectedPath == null ? pathForCurrentImage(image) : selectedPath;
        final InputNormalization normalization = StructuralNormalizer.normalizationFor(image);
        if (normalization == InputNormalization.REJECT_TIME_SERIES) {
            throw new IllegalArgumentException(InputWorkflowRunner.TIME_SERIES_ERROR);
        }

        final TwoPlaneInterpretation twoPlane = normalization == InputNormalization.CHOOSE_TWO_PLANE_INTERPRETATION
            ? chooseTwoPlane()
            : null;
        image = normalizeAndPresent(image, twoPlane, false);

        if (image.getNChannels() < 2) {
            throw new IllegalArgumentException(
                "At least two channels are required to select muscle endplate and nerve terminal"
            );
        }

        analyzeSelected(image, inputPath, chooseChannels(image.getNChannels()));
    }

    void analyzeBatch(ImagePlus image, final Path inputPath, final BatchChoiceResolver choices) {
        final InputNormalization normalization = StructuralNormalizer.normalizationFor(image);
        if (normalization == InputNormalization.REJECT_TIME_SERIES) {
            throw new IllegalArgumentException(InputWorkflowRunner.TIME_SERIES_ERROR);
        }
        final SupportedImageFormat format = SupportedImageFormat.fromName(inputPath.getFileName().toString())
            .orElseThrow(() -> new IllegalArgumentException("Unsupported image format: " + inputPath));
        final TwoPlaneInterpretation twoPlane = normalization == InputNormalization.CHOOSE_TWO_PLANE_INTERPRETATION
            ? choices.resolveTwoPlane(BatchChoiceResolver.signature(format, image, normalization, null))
            : null;
        final String signature = BatchChoiceResolver.signature(
            format, image, normalization, twoPlane == null ? null : twoPlane.name()
        );
        image = normalizeAndPresent(image, twoPlane, true);
        if (image.getNChannels() < 2) {
            throw new IllegalArgumentException("At least two channels are required to select muscle endplate and nerve terminal");
        }
        analyzeSelected(image, inputPath, choices.resolveChannels(signature, image.getNChannels()));
    }

    ImagePlus normalizeAndPresent(
        ImagePlus image,
        final TwoPlaneInterpretation twoPlane,
        final boolean alwaysShow
    ) {
        final ImagePlus normalized = structuralNormalizer.normalize(image, twoPlane);
        final boolean replaced = normalized != image;
        if (replaced) {
            image.changes = false;
            image.close();
            image = normalized;
        }
        if (replaced || alwaysShow) {
            image.show();
        }
        return image;
    }

    void analyzeSelected(
        final ImagePlus image,
        final Path inputPath,
        final BatchChoiceResolver.ChannelChoice selected
    ) {
        analysisWorkflow.analyze(
            image, inputPath, selected,
            result -> outputWriter.append(AnalysisOutputPaths.forInput(inputPath).csv, result)
        );
    }

    private static TwoPlaneInterpretation chooseTwoPlane() {
        final BatchChoiceResolver.PromptResult<TwoPlaneInterpretation> result =
            BatchChoiceResolver.promptTwoPlane(false);
        if (result == null) {
            throw new AnalysisCancelledException();
        }
        return result.value;
    }

    private static BatchChoiceResolver.ChannelChoice chooseChannels(final int channelCount) {
        final BatchChoiceResolver.PromptResult<BatchChoiceResolver.ChannelChoice> result =
            BatchChoiceResolver.promptChannels(channelCount, false);
        if (result == null) {
            throw new AnalysisCancelledException();
        }
        return result.value;
    }

    static Path pathForCurrentImage(final ImagePlus image) {
        final FileInfo info = image.getOriginalFileInfo();
        if (info != null && info.directory != null && !info.directory.isEmpty()) {
            return Path.of(info.directory).resolve(image.getTitle());
        }

        final DirectoryChooser chooser = new DirectoryChooser("Select folder for analysis output");
        final String directory = chooser.getDirectory();
        if (directory == null) {
            throw new AnalysisCancelledException();
        }
        return Path.of(directory).resolve(image.getTitle());
    }
}
