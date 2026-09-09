package io.github.alzeiby.anmjmorphplus;

import ij.ImagePlus;

final class InputPolicy {

    private InputPolicy() {
    }

    static InputNormalization normalizationFor(final ImagePlus image) {
        if (image.getNFrames() > 1) {
            return InputNormalization.REJECT_TIME_SERIES;
        }
        if (image.getNChannels() == 1 && image.getNSlices() == 1 && image.getBitDepth() == 24) {
            return InputNormalization.RGB_TO_CHANNELS;
        }
        if (image.getNChannels() == 1 && image.getNSlices() == 2) {
            return InputNormalization.CHOOSE_TWO_PLANE_INTERPRETATION;
        }
        if (image.getNSlices() > 1) {
            return InputNormalization.MAX_PROJECT_Z;
        }
        return InputNormalization.USE_AS_IS;
    }
}
