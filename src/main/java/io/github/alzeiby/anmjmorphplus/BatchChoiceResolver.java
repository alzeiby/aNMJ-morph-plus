package io.github.alzeiby.anmjmorphplus;

import ij.IJ;
import ij.ImagePlus;
import ij.gui.GenericDialog;

import java.util.HashMap;
import java.util.Map;

final class BatchChoiceResolver {

    static final class ChannelChoice {
        final int muscleEndplateChannel;
        final int nerveTerminalChannel;

        ChannelChoice(final int muscleEndplateChannel, final int nerveTerminalChannel) {
            this.muscleEndplateChannel = muscleEndplateChannel;
            this.nerveTerminalChannel = nerveTerminalChannel;
        }
    }

    static final class PromptResult<T> {
        final T value;
        final boolean remember;

        PromptResult(final T value, final boolean remember) {
            this.value = value;
            this.remember = remember;
        }
    }

    private final Map<String, TwoPlaneInterpretation> twoPlaneChoices = new HashMap<>();
    private final Map<String, ChannelChoice> channelChoices = new HashMap<>();

    TwoPlaneInterpretation resolveTwoPlane(final String signature) {
        final TwoPlaneInterpretation remembered = twoPlaneChoices.get(signature);
        if (remembered != null) return remembered;
        final PromptResult<TwoPlaneInterpretation> result = promptTwoPlane(true);
        if (result == null) throw new AnalysisCancelledException();
        if (result.remember) twoPlaneChoices.put(signature, result.value);
        return result.value;
    }

    ChannelChoice resolveChannels(final String signature, final int channelCount) {
        final ChannelChoice remembered = channelChoices.get(signature);
        if (remembered != null) return remembered;
        final PromptResult<ChannelChoice> result = promptChannels(channelCount, true);
        if (result == null) throw new AnalysisCancelledException();
        if (result.remember) channelChoices.put(signature, result.value);
        return result.value;
    }

    static PromptResult<TwoPlaneInterpretation> promptTwoPlane(final boolean allowRemember) {
        final GenericDialog dialog = new GenericDialog("Two-plane image detected");
        dialog.addMessage("This image contains one channel and two planes. Choose how the two planes should be interpreted.");
        dialog.addChoice("Interpret as",
            new String[] {"Two channels (Keyence/two-page export)", "Z stack (maximum-project)"},
            "Two channels (Keyence/two-page export)");
        if (allowRemember) dialog.addCheckbox("Apply this choice to remaining matching files in this batch", false);
        dialog.showDialog();
        if (dialog.wasCanceled()) return null;
        final TwoPlaneInterpretation value = dialog.getNextChoiceIndex() == 0
            ? TwoPlaneInterpretation.CHANNELS : TwoPlaneInterpretation.Z_STACK;
        return new PromptResult<>(value, allowRemember && dialog.getNextBoolean());
    }

    static PromptResult<ChannelChoice> promptChannels(final int channelCount, final boolean allowRemember) {
        if (channelCount < 2) throw new IllegalArgumentException("At least two channels are required");
        final String[] channels = new String[channelCount];
        for (int i = 0; i < channelCount; i++) channels[i] = Integer.toString(i + 1);
        while (true) {
            final GenericDialog dialog = new GenericDialog("aNMJ-morph+ channel assignment");
            dialog.addChoice("Muscle endplate channel", channels, channels[0]);
            dialog.addChoice("Nerve terminal channel", channels, channels[1]);
            if (allowRemember) dialog.addCheckbox("Apply these channel choices to remaining matching files in this batch", false);
            dialog.showDialog();
            if (dialog.wasCanceled()) return null;
            final int muscle = dialog.getNextChoiceIndex() + 1;
            final int nerve = dialog.getNextChoiceIndex() + 1;
            final boolean remember = allowRemember && dialog.getNextBoolean();
            if (muscle != nerve) return new PromptResult<>(new ChannelChoice(muscle, nerve), remember);
            IJ.error("aNMJ-morph+", "Muscle endplate and nerve terminal must use different channels.");
        }
    }

    static String signature(
        final SupportedImageFormat format,
        final ImagePlus image,
        final InputNormalization normalization,
        final String interpretation
    ) {
        return format + "|" + image.getWidth() + "x" + image.getHeight() + "|c" + image.getNChannels() +
            "|z" + image.getNSlices() + "|t" + image.getNFrames() + "|" + image.getBitDepth() + "|" +
            normalization + (interpretation == null ? "" : "|" + interpretation);
    }
}
