package io.github.alzeiby.anmjmorphplus;

import ij.ImagePlus;
import ij.io.DirectoryChooser;
import ij.io.FileInfo;

import java.nio.file.Path;

final class SingleImageAnalysisRunner {

    private final StructuralNormalizer structuralNormalizer = new StructuralNormalizer();
    private final ChannelRoleCanonicalizer channelRoleCanonicalizer = new ChannelRoleCanonicalizer();
    private final AnalysisWorkflow analysisWorkflow = new AnalysisWorkflow();
    private final CsvOutputWriter outputWriter = new CsvOutputWriter();

    void analyze(ImagePlus image, final Path selectedPath) {
        final Path inputPath = selectedPath == null ? pathForCurrentImage(image) : selectedPath;
        final InputNormalization normalization = InputPolicy.normalizationFor(image);
        if (normalization == InputNormalization.REJECT_TIME_SERIES) {
            throw new IllegalArgumentException(InputWorkflowRunner.TIME_SERIES_ERROR);
        }

        final TwoPlaneInterpretation twoPlane = normalization == InputNormalization.CHOOSE_TWO_PLANE_INTERPRETATION
            ? chooseTwoPlane()
            : null;
        final ImagePlus normalized = structuralNormalizer.normalize(image, twoPlane);
        if (normalized != image) {
            image.changes = false;
            image.close();
            image = normalized;
            image.show();
        }

        if (image.getNChannels() < 2) {
            throw new IllegalArgumentException(
                "At least two channels are required to select muscle endplate and nerve terminal"
            );
        }

        final BatchChoiceResolver.ChannelChoice selected = chooseChannels(image.getNChannels());
        BatchChoiceResolver.ChannelChoice analysisChoice = selected;
        boolean channelsCanonical = false;
        if (image.getNChannels() <= ChannelRoleCanonicalizer.IMAGEJ_ARRANGER_MAX_CHANNELS) {
            final ImagePlus canonical = channelRoleCanonicalizer.canonicalize(image, selected);
            if (canonical != image) {
                image = canonical;
                image.show();
            }
            analysisChoice = new BatchChoiceResolver.ChannelChoice(1, 2);
            channelsCanonical = image.getNChannels() == 2 && image.isComposite();
        }

        final AnalysisRun run = analysisWorkflow.analyze(image, inputPath, analysisChoice, channelsCanonical);
        outputWriter.append(AnalysisOutputPaths.forInput(inputPath).csv, run.result);
        analysisWorkflow.finish(run);
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
