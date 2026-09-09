package io.github.alzeiby.anmjmorphplus;

import ij.IJ;
import ij.gui.GenericDialog;

import java.nio.file.Path;
import java.util.Objects;

final class BatchChoiceResolver {

    static final class ChannelChoice {
        private final int muscleEndplateChannel;
        private final int nerveTerminalChannel;

        ChannelChoice(final int muscleEndplateChannel, final int nerveTerminalChannel) {
            this.muscleEndplateChannel = muscleEndplateChannel;
            this.nerveTerminalChannel = nerveTerminalChannel;
        }

        int muscleEndplateChannel() {
            return muscleEndplateChannel;
        }

        int nerveTerminalChannel() {
            return nerveTerminalChannel;
        }
    }

    static final class PromptResult<T> {
        private final T value;
        private final boolean remember;

        PromptResult(final T value, final boolean remember) {
            this.value = value;
            this.remember = remember;
        }

        T value() {
            return value;
        }

        boolean remember() {
            return remember;
        }
    }

    interface Prompter {
        PromptResult<TwoPlaneInterpretation> promptTwoPlane(InputSignature signature);
        PromptResult<ChannelChoice> promptChannels(InputSignature signature, int channelCount);
    }

    private final Path root;
    private final BatchCheckpointStore store;
    private final BatchCheckpointStore.Session session;
    private final Prompter prompter;

    BatchChoiceResolver(
        final Path root,
        final BatchCheckpointStore store,
        final BatchCheckpointStore.Session session,
        final Prompter prompter
    ) {
        this.root = Objects.requireNonNull(root, "root");
        this.store = Objects.requireNonNull(store, "store");
        this.session = Objects.requireNonNull(session, "session");
        this.prompter = Objects.requireNonNull(prompter, "prompter");
    }

    TwoPlaneInterpretation resolveTwoPlane(final InputSignature signature) {
        final String key = "two-plane|" + signature.value();
        final String remembered = session.choices.get(key);
        if (remembered != null) {
            try {
                return TwoPlaneInterpretation.valueOf(remembered);
            } catch (IllegalArgumentException e) {
                throw new BatchCheckpointStore.BatchCheckpointException("Malformed remembered two-plane choice", e);
            }
        }
        final PromptResult<TwoPlaneInterpretation> result = prompter.promptTwoPlane(signature);
        if (result == null || result.value() == null) {
            throw BatchFileException.cancelled("Two-plane interpretation was cancelled");
        }
        if (result.remember()) {
            session.choices.put(key, result.value().name());
            store.save(root, session);
        }
        return result.value();
    }

    ChannelChoice resolveChannels(final InputSignature signature, final int channelCount) {
        final String key = "channels|" + signature.value();
        final String remembered = session.choices.get(key);
        if (remembered != null) {
            final String[] values = remembered.split(",", -1);
            if (values.length != 2) {
                throw new BatchCheckpointStore.BatchCheckpointException("Malformed remembered channel choice");
            }
            try {
                return validateChannels(
                    new ChannelChoice(Integer.parseInt(values[0]), Integer.parseInt(values[1])),
                    channelCount
                );
            } catch (NumberFormatException e) {
                throw new BatchCheckpointStore.BatchCheckpointException("Malformed remembered channel choice", e);
            }
        }
        final PromptResult<ChannelChoice> result = prompter.promptChannels(signature, channelCount);
        if (result == null || result.value() == null) {
            throw BatchFileException.cancelled("Channel selection was cancelled");
        }
        final ChannelChoice choice = validateChannels(result.value(), channelCount);
        if (result.remember()) {
            session.choices.put(
                key,
                choice.muscleEndplateChannel() + "," + choice.nerveTerminalChannel()
            );
            store.save(root, session);
        }
        return choice;
    }

    private static ChannelChoice validateChannels(final ChannelChoice choice, final int channelCount) {
        if (choice.muscleEndplateChannel() < 1 || choice.muscleEndplateChannel() > channelCount ||
            choice.nerveTerminalChannel() < 1 || choice.nerveTerminalChannel() > channelCount ||
            choice.muscleEndplateChannel() == choice.nerveTerminalChannel()) {
            throw BatchFileException.precheck(
                "INVALID_CHANNEL_SELECTION",
                "Muscle endplate and nerve terminal must be different valid channels"
            );
        }
        return choice;
    }

    static Prompter interactivePrompter() {
        return new Prompter() {
            @Override
            public PromptResult<TwoPlaneInterpretation> promptTwoPlane(final InputSignature signature) {
                final GenericDialog dialog = new GenericDialog("Two-plane image detected");
                dialog.addMessage(
                    "This image contains one channel and two planes. Choose how the two planes should be interpreted."
                );
                dialog.addChoice(
                    "Interpret as",
                    new String[] {"Two channels (Keyence/two-page export)", "Z stack (maximum-project)"},
                    "Two channels (Keyence/two-page export)"
                );
                dialog.addCheckbox("Apply this choice to remaining matching files in this batch", false);
                dialog.showDialog();
                if (dialog.wasCanceled()) {
                    return null;
                }
                final TwoPlaneInterpretation choice = dialog.getNextChoiceIndex() == 0
                    ? TwoPlaneInterpretation.CHANNELS
                    : TwoPlaneInterpretation.Z_STACK;
                return new PromptResult<>(choice, dialog.getNextBoolean());
            }

            @Override
            public PromptResult<ChannelChoice> promptChannels(final InputSignature signature, final int channelCount) {
                if (channelCount < 2) {
                    throw BatchFileException.precheck(
                        "INVALID_CHANNEL_SELECTION",
                        "At least two channels are required to select muscle endplate and nerve terminal"
                    );
                }
                final String[] channels = new String[channelCount];
                for (int i = 0; i < channelCount; i++) {
                    channels[i] = Integer.toString(i + 1);
                }
                while (true) {
                    final GenericDialog dialog = new GenericDialog("aNMJ-morph+ channel assignment");
                    dialog.addChoice("Muscle endplate channel", channels, channels[0]);
                    dialog.addChoice("Nerve terminal channel", channels, channels[1]);
                    dialog.addCheckbox("Apply these channel choices to remaining matching files in this batch", false);
                    dialog.showDialog();
                    if (dialog.wasCanceled()) {
                        return null;
                    }
                    final int muscle = dialog.getNextChoiceIndex() + 1;
                    final int nerve = dialog.getNextChoiceIndex() + 1;
                    final boolean remember = dialog.getNextBoolean();
                    if (muscle != nerve) {
                        return new PromptResult<>(new ChannelChoice(muscle, nerve), remember);
                    }
                    IJ.error("aNMJ-morph+", "Muscle endplate and nerve terminal must use different channels.");
                }
            }
        };
    }
}
