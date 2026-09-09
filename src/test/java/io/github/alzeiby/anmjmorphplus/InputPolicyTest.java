package io.github.alzeiby.anmjmorphplus;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class InputPolicyTest {

    @Test
    public void rejectsTimeSeriesBeforeOtherNormalization() {
        assertEquals(
            InputNormalization.REJECT_TIME_SERIES,
            InputPolicy.normalizationFor(new ImageShape(16, 12, 1, 2, 3, 24))
        );
    }

    @Test
    public void identifiesSingleSliceRgb() {
        assertEquals(
            InputNormalization.RGB_TO_CHANNELS,
            InputPolicy.normalizationFor(new ImageShape(16, 12, 1, 1, 1, 24))
        );
    }

    @Test
    public void preservesTheAmbiguousOneChannelTwoPlaneCase() {
        assertEquals(
            InputNormalization.CHOOSE_TWO_PLANE_INTERPRETATION,
            InputPolicy.normalizationFor(new ImageShape(16, 12, 1, 2, 1, 8))
        );
    }

    @Test
    public void identifiesRealZStacks() {
        assertEquals(
            InputNormalization.MAX_PROJECT_Z,
            InputPolicy.normalizationFor(new ImageShape(16, 12, 2, 3, 1, 8))
        );
    }

    @Test
    public void leavesOrdinaryMultiChannelImagesAlone() {
        assertEquals(
            InputNormalization.USE_AS_IS,
            InputPolicy.normalizationFor(new ImageShape(16, 12, 2, 1, 1, 8))
        );
    }
}
