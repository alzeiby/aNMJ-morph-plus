package io.github.alzeiby.anmjmorphplus;

import ij.IJ;
import ij.ImagePlus;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class ImageShapeTest {

    @Test
    public void readsImageJHyperstackDimensions() {
        final ImagePlus image = IJ.createHyperStack("fixture", 16, 12, 2, 3, 1, 8);

        final ImageShape shape = ImageShape.from(image);

        assertEquals(16, shape.width());
        assertEquals(12, shape.height());
        assertEquals(2, shape.channels());
        assertEquals(3, shape.slices());
        assertEquals(1, shape.frames());
        assertEquals(8, shape.bitDepth());
    }

    @Test
    public void recognizesImageJRgbImages() {
        final ImagePlus image = IJ.createImage("rgb", "RGB black", 16, 12, 1);

        assertTrue(ImageShape.from(image).isSingleSliceRgb());
    }
}
