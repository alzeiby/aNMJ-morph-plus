package io.github.alzeiby.anmjmorphplus;

import ij.ImagePlus;
import ij.measure.Calibration;
import ij.process.ByteProcessor;
import org.junit.Test;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class AnisotropicSkeletonCalibrationTest {

    @Test
    public void isotropicCalibrationPreservesLegacyPixelScaleExactly() {
        final ImagePlus diagonal = skeleton(new int[][] {{1, 1}, {2, 2}, {3, 3}, {4, 4}, {5, 5}}, 2.0, 2.0);
        assertEquals(2.0, AnisotropicSkeletonCalibration.effectivePixelSize(diagonal), 0.0);
    }

    @Test
    public void horizontalAndVerticalChainsUseTheirPhysicalAxisScale() {
        final ImagePlus horizontal = skeleton(new int[][] {{1, 3}, {2, 3}, {3, 3}, {4, 3}, {5, 3}}, 2.0, 5.0);
        final ImagePlus vertical = skeleton(new int[][] {{3, 1}, {3, 2}, {3, 3}, {3, 4}, {3, 5}}, 2.0, 5.0);

        assertEquals(2.0, AnisotropicSkeletonCalibration.effectivePixelSize(horizontal), 1e-12);
        assertEquals(5.0, AnisotropicSkeletonCalibration.effectivePixelSize(vertical), 1e-12);
    }

    @Test
    public void diagonalChainUsesOrientationWeightedXyCalibration() {
        final ImagePlus diagonal = skeleton(new int[][] {{1, 1}, {2, 2}, {3, 3}, {4, 4}, {5, 5}}, 2.0, 5.0);
        final double expected = Math.hypot(2.0, 5.0) / Math.sqrt(2.0);
        assertEquals(expected, AnisotropicSkeletonCalibration.effectivePixelSize(diagonal), 1e-12);
    }

    @Test
    public void transposeWithSwappedCalibrationIsInvariant() {
        final ImagePlus first = skeleton(new int[][] {{1, 1}, {2, 2}, {3, 3}, {4, 3}, {5, 3}}, 2.0, 5.0);
        final ImagePlus transposed = skeleton(new int[][] {{1, 1}, {2, 2}, {3, 3}, {3, 4}, {3, 5}}, 5.0, 2.0);
        assertEquals(
            AnisotropicSkeletonCalibration.effectivePixelSize(first),
            AnisotropicSkeletonCalibration.effectivePixelSize(transposed),
            1e-12
        );
    }

    @Test
    public void orthogonalJunctionDoesNotCountDiagonalCornerShortcuts() {
        final ImagePlus tee = skeleton(
            new int[][] {{1, 3}, {2, 3}, {3, 3}, {2, 2}, {2, 4}},
            2.0,
            5.0
        );
        assertEquals(3.5, AnisotropicSkeletonCalibration.effectivePixelSize(tee), 1e-12);
    }

    @Test
    public void calibrationAndPixelsAreRestoredAfterAnalysis() {
        final ImagePlus image = skeleton(new int[][] {{1, 1}, {2, 2}, {3, 3}, {4, 4}}, 2.0, 5.0);
        final byte[] before = (byte[]) image.getProcessor().getPixelsCopy();
        final String unit = image.getCalibration().getUnit();

        AnisotropicSkeletonCalibration.effectivePixelSize(image);

        assertEquals(2.0, image.getCalibration().pixelWidth, 0.0);
        assertEquals(5.0, image.getCalibration().pixelHeight, 0.0);
        assertEquals(unit, image.getCalibration().getUnit());
        assertArrayEquals(before, (byte[]) image.getProcessor().getPixels());
    }

    @Test
    public void isolatedPixelUsesSymmetricAreaEquivalentScale() {
        final ImagePlus isolated = skeleton(new int[][] {{3, 3}}, 2.0, 8.0);
        assertEquals(4.0, AnisotropicSkeletonCalibration.effectivePixelSize(isolated), 1e-12);
    }

    @Test
    public void disconnectedComponentsAreCalibratedAdditively() {
        final ImagePlus horizontal = skeleton(new int[][] {{1, 1}, {2, 1}}, 1.0, 4.0);
        final ImagePlus vertical = skeleton(new int[][] {{5, 3}, {5, 4}, {5, 5}}, 1.0, 4.0);
        final ImagePlus union = skeleton(
            new int[][] {{1, 1}, {2, 1}, {5, 3}, {5, 4}, {5, 5}},
            1.0,
            4.0
        );

        final double expectedTotal =
            2.0 * AnisotropicSkeletonCalibration.effectivePixelSize(horizontal)
                + 3.0 * AnisotropicSkeletonCalibration.effectivePixelSize(vertical);
        final double actualTotal = 5.0 * AnisotropicSkeletonCalibration.effectivePixelSize(union);
        assertEquals(expectedTotal, actualTotal, 1e-12);
    }

    @Test
    public void isolatedPixelsDoNotBorrowOrientationFromAnotherComponent() {
        final ImagePlus mixed = skeleton(
            new int[][] {
                {1, 1}, {3, 1}, {5, 1}, {1, 3}, {3, 3}, {5, 3},
                {1, 5}, {2, 5}
            },
            1.0,
            4.0
        );
        final double expectedTotal = 6.0 * 2.0 + 2.0 * 1.0;
        assertEquals(
            expectedTotal,
            8.0 * AnisotropicSkeletonCalibration.effectivePixelSize(mixed),
            1e-12
        );
    }

    @Test
    public void twoByTwoCornerSuppressesRedundantDiagonalShortcut() {
        final ImagePlus corner = skeleton(new int[][] {{1, 1}, {2, 1}, {2, 2}}, 2.0, 8.0);
        assertEquals(5.0, AnisotropicSkeletonCalibration.effectivePixelSize(corner), 1e-12);
    }

    private static ImagePlus skeleton(final int[][] points, final double pixelWidth, final double pixelHeight) {
        final ByteProcessor processor = new ByteProcessor(7, 7);
        for (int[] point : points) {
            processor.set(point[0], point[1], 255);
        }
        final ImagePlus image = new ImagePlus("skeleton", processor);
        final Calibration calibration = image.getCalibration();
        calibration.pixelWidth = pixelWidth;
        calibration.pixelHeight = pixelHeight;
        calibration.setUnit("microns");
        return image;
    }
}
