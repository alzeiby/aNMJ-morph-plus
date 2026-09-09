package io.github.alzeiby.anmjmorphplus;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.GenericDialog;
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
        final InputNormalization normalization = InputPolicy.normalizationFor(ImageShape.from(image));
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
        final GenericDialog dialog = new GenericDialog("Two-plane image detected");
        dialog.addMessage(
            "This image contains one channel and two planes. Choose how the two planes should be interpreted."
        );
        dialog.addChoice(
            "Interpret as",
            new String[] {"Two channels (Keyence/two-page export)", "Z stack (maximum-project)"},
            "Two channels (Keyence/two-page export)"
        );
        dialog.showDialog();
        if (dialog.wasCanceled()) {
            throw new AnalysisCancelledException();
        }
        return dialog.getNextChoiceIndex() == 0
            ? TwoPlaneInterpretation.CHANNELS
            : TwoPlaneInterpretation.Z_STACK;
    }

    private static BatchChoiceResolver.ChannelChoice chooseChannels(final int channelCount) {
        final String[] channels = new String[channelCount];
        for (int i = 0; i < channelCount; i++) {
            channels[i] = Integer.toString(i + 1);
        }
        while (true) {
            final GenericDialog dialog = new GenericDialog("aNMJ-morph+ channel assignment");
            dialog.addChoice("Muscle endplate channel", channels, channels[0]);
            dialog.addChoice("Nerve terminal channel", channels, channels[1]);
            dialog.showDialog();
            if (dialog.wasCanceled()) {
                throw new AnalysisCancelledException();
            }
            final int muscle = dialog.getNextChoiceIndex() + 1;
            final int nerve = dialog.getNextChoiceIndex() + 1;
            if (muscle != nerve) {
                return new BatchChoiceResolver.ChannelChoice(muscle, nerve);
            }
            IJ.error("aNMJ-morph+", "Muscle endplate and nerve terminal must use different channels.");
        }
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
