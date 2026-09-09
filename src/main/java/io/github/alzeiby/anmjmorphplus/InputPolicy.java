package io.github.alzeiby.anmjmorphplus;

final class InputPolicy {

    private InputPolicy() {
    }

    static InputNormalization normalizationFor(final ImageShape shape) {
        if (shape.frames() > 1) {
            return InputNormalization.REJECT_TIME_SERIES;
        }
        if (shape.isSingleSliceRgb()) {
            return InputNormalization.RGB_TO_CHANNELS;
        }
        if (shape.channels() == 1 && shape.slices() == 2) {
            return InputNormalization.CHOOSE_TWO_PLANE_INTERPRETATION;
        }
        if (shape.slices() > 1) {
            return InputNormalization.MAX_PROJECT_Z;
        }
        return InputNormalization.USE_AS_IS;
    }
}
