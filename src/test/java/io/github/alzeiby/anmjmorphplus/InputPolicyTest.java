package io.github.alzeiby.anmjmorphplus;

import ij.IJ;
import ij.ImagePlus;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class InputPolicyTest {

    @Test
    public void rejectsTimeSeriesBeforeOtherNormalization() {
        final ImagePlus image = IJ.createImage("time", "RGB black", 16, 12, 6);
        image.setDimensions(1, 2, 3);
        assertEquals(
            InputNormalization.REJECT_TIME_SERIES,
            StructuralNormalizer.normalizationFor(image)
        );
    }

    @Test
    public void identifiesSingleSliceRgb() {
        final ImagePlus image = IJ.createImage("rgb", "RGB black", 16, 12, 1);
        assertEquals(
            InputNormalization.RGB_TO_CHANNELS,
            StructuralNormalizer.normalizationFor(image)
        );
    }

    @Test
    public void preservesTheAmbiguousOneChannelTwoPlaneCase() {
        assertEquals(
            InputNormalization.CHOOSE_TWO_PLANE_INTERPRETATION,
            StructuralNormalizer.normalizationFor(IJ.createHyperStack("two-plane", 16, 12, 1, 2, 1, 8))
        );
    }

    @Test
    public void identifiesRealZStacks() {
        assertEquals(
            InputNormalization.MAX_PROJECT_Z,
            StructuralNormalizer.normalizationFor(IJ.createHyperStack("z", 16, 12, 2, 3, 1, 8))
        );
    }

    @Test
    public void leavesOrdinaryMultiChannelImagesAlone() {
        assertEquals(
            InputNormalization.USE_AS_IS,
            StructuralNormalizer.normalizationFor(IJ.createHyperStack("ordinary", 16, 12, 2, 1, 1, 8))
        );
    }
}
