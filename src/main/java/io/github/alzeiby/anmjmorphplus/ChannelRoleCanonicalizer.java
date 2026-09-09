package io.github.alzeiby.anmjmorphplus;

import ij.ImagePlus;
import ij.plugin.ChannelArranger;

import java.util.Objects;

final class ChannelRoleCanonicalizer {

    static final int IMAGEJ_ARRANGER_MAX_CHANNELS = 9;

    ImagePlus canonicalize(
        final ImagePlus image,
        final BatchChoiceResolver.ChannelChoice channelChoice
    ) {
        Objects.requireNonNull(image, "image");
        Objects.requireNonNull(channelChoice, "channelChoice");

        if (image.getNChannels() > IMAGEJ_ARRANGER_MAX_CHANNELS) {
            return image;
        }
        if (image.getNChannels() == 2 &&
            channelChoice.muscleEndplateChannel == 1 &&
            channelChoice.nerveTerminalChannel == 2) {
            return image;
        }

        final ImagePlus canonical = ChannelArranger.run(
            image,
            new int[] {
                channelChoice.muscleEndplateChannel,
                channelChoice.nerveTerminalChannel
            }
        );
        if (canonical == null) {
            throw new IllegalStateException("ImageJ could not arrange the selected channels");
        }

        canonical.setTitle(image.getTitle());
        canonical.setCalibration(image.getCalibration());
        canonical.setFileInfo(image.getOriginalFileInfo());
        final Object info = image.getProperty("Info");
        if (info != null) {
            canonical.setProperty("Info", info);
        }
        return canonical;
    }
}
