package io.github.alzeiby.anmjmorphplus;

import ij.ImagePlus;
import ij.ImageStack;
import ij.Prefs;
import ij.gui.GenericDialog;
import ij.gui.NonBlockingGenericDialog;
import ij.gui.OvalRoi;
import ij.measure.Calibration;
import ij.process.ByteProcessor;
import org.junit.Test;
import java.awt.GraphicsEnvironment;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeFalse;

public class ANMJMorphCommandTest {

    @Test
    public void imageInteractionIsNotBlockedByFijiDialogPreferences() {
        assumeFalse(GraphicsEnvironment.isHeadless());
        final boolean oldPreference = Prefs.nonBlockingFilterDialogs;
        Prefs.nonBlockingFilterDialogs = false;
        try {
            final GenericDialog dialog = ANMJMorphCommand.interactionDialog("Crop");
            try {
                assertTrue(dialog instanceof NonBlockingGenericDialog);
                assertFalse(dialog.isModal());
            } finally {
                dialog.dispose();
            }
        } finally {
            Prefs.nonBlockingFilterDialogs = oldPreference;
        }
    }

    @Test
    public void cropKeepsAllChannelsAndZSlices() {
        final ImageStack stack = new ImageStack(6, 5);
        for (int layer = 0; layer < 6; layer++) {
            final ByteProcessor processor = new ByteProcessor(6, 5);
            for (int y = 0; y < 5; y++) {
                for (int x = 0; x < 6; x++) processor.set(x, y, layer * 30 + y * 6 + x);
            }
            stack.addSlice(processor);
        }
        final ImagePlus image = new ImagePlus("multi-channel", stack);
        image.setDimensions(2, 3, 1);
        image.setOpenAsHyperStack(true);
        image.getLocalCalibration().pixelWidth = 0.4;
        image.getLocalCalibration().pixelHeight = 0.5;
        image.getLocalCalibration().setUnit("micron");
        image.setRoi(2, 1, 3, 2);

        final ImagePlus crop = ANMJMorphCommand.cropToRectangle(image);
        assertEquals(3, crop.getWidth());
        assertEquals(2, crop.getHeight());
        assertEquals(2, crop.getNChannels());
        assertEquals(3, crop.getNSlices());
        assertEquals(6, crop.getStackSize());
        for (int layer = 1; layer <= 6; layer++) {
            assertEquals(image.getStack().getProcessor(layer).get(2, 1),
                crop.getStack().getProcessor(layer).get(0, 0));
        }
        assertEquals(0.4, crop.getCalibration().pixelWidth, 1e-9);
        assertEquals(0.5, crop.getCalibration().pixelHeight, 1e-9);
        assertNull(crop.getRoi());
        assertEquals(6, image.getWidth());
    }

    @Test(expected = IllegalArgumentException.class)
    public void cropRequiresRectangle() {
        final ImagePlus image = new ImagePlus("test", new ByteProcessor(10, 10));
        image.setRoi(new OvalRoi(2, 2, 5, 5));
        ANMJMorphCommand.cropToRectangle(image);
    }

    @Test
    public void uncalibratedImagesCanUsePixels() {
        final ImagePlus image = new ImagePlus("pixels", new ByteProcessor(10, 10));
        ANMJMorphCommand.applyPixelCalibration(image, false, Double.NaN, Double.NaN);
        AnalysisWorkflow.canonicalizeCalibration(image);
        final Calibration cal = image.getCalibration();
        assertEquals("pixel", cal.getUnit());
        assertEquals(1.0, cal.pixelWidth, 1e-9);
        assertEquals(1.0, cal.pixelHeight, 1e-9);
    }

    @Test
    public void userPhysicalScaleCanConvertUncalibratedImages() {
        final ImagePlus image = new ImagePlus("pixels", new ByteProcessor(10, 10));
        ANMJMorphCommand.applyPixelCalibration(image, true, 0.4, 0.6);
        AnalysisWorkflow.canonicalizeCalibration(image);
        assertEquals("micron", image.getCalibration().getUnit());
        assertEquals(0.4, image.getCalibration().pixelWidth, 1e-9);
        assertEquals(0.6, image.getCalibration().pixelHeight, 1e-9);
    }

    @Test
    public void preservesKnownPixelScaleAsCalibrationSuggestion() {
        assertEquals(0.23294, ANMJMorphCommand.proposedMicronsPerPixel(0.23294), 1e-9);
        assertEquals(1, ANMJMorphCommand.proposedMicronsPerPixel(Double.NaN), 1e-9);
        assertEquals(1, ANMJMorphCommand.proposedMicronsPerPixel(0), 1e-9);
    }

    @Test
    public void physicalUnitsAreConvertedToMicrons() {
        final ImagePlus image = new ImagePlus("nm", new ByteProcessor(10, 10));
        final Calibration cal = image.getLocalCalibration();
        cal.setUnit("nm");
        cal.pixelWidth = 120;
        cal.pixelHeight = 180;
        AnalysisWorkflow.canonicalizeCalibration(image);
        assertEquals("micron", cal.getUnit());
        assertEquals(0.12, cal.pixelWidth, 1e-9);
        assertEquals(0.18, cal.pixelHeight, 1e-9);
    }

    @Test(expected = IllegalArgumentException.class)
    public void invalidEnteredScaleIsRejected() {
        final ImagePlus image = new ImagePlus("bad", new ByteProcessor(10, 10));
        ANMJMorphCommand.applyPixelCalibration(image, true, 0, 0.1);
    }

    @Test
    public void pixelCsvUsesPixelUnitLabels() throws Exception {
        final Path csv = Files.createTempFile("anmj-pixels", ".csv");
        try {
            assertEquals(3, AnalysisWorkflow.prepareCsv(csv, true));
            final String header = Files.readString(csv);
            assertTrue(header.contains("Nerve Terminal Area (px2)"));
            assertTrue(header.contains("Axon Diameter (px)"));
            assertFalse(header.contains("Axon Diameter (um)"));
        } finally {
            Files.deleteIfExists(csv);
        }
    }

    @Test
    public void thresholdMaskUsesChosenBoundsRatherThanAutomaticThreshold() {
        final ByteProcessor pixels = new ByteProcessor(4, 1);
        pixels.set(0, 0, 10);
        pixels.set(1, 0, 90);
        pixels.set(2, 0, 130);
        pixels.set(3, 0, 240);
        final ImagePlus image = new ImagePlus("reference", pixels);
        AnalysisWorkflow.applyThresholdMask(image, new double[]{80, 160});
        assertEquals(255, image.getProcessor().get(0, 0));
        assertEquals(0, image.getProcessor().get(1, 0));
        assertEquals(0, image.getProcessor().get(2, 0));
        assertEquals(255, image.getProcessor().get(3, 0));
        assertTrue(image.getProcessor().isBinary());
    }

    @Test(expected = IllegalStateException.class)
    public void unselectedThresholdFailsWithClearMessage() {
        final ImagePlus image = new ImagePlus("nerve", new ByteProcessor(4, 4));
        AnalysisWorkflow.requireThreshold(image);
    }

    @Test
    public void selectedThresholdIsRetained() {
        final ImagePlus image = new ImagePlus("nerve", new ByteProcessor(5, 5));
        image.getProcessor().setThreshold(20, 120, ByteProcessor.NO_LUT_UPDATE);
        assertArrayEquals(new double[]{20, 120}, AnalysisWorkflow.requireThreshold(image), 0);
    }

    @Test
    public void segmentationUsesSelectedEndplateThreshold() {
        final ByteProcessor raw = new ByteProcessor(40, 40);
        for (int y = 10; y < 30; y++) {
            for (int x = 10; x < 30; x++) raw.set(x, y, 160);
        }
        final ImagePlus reference = new ImagePlus("Endplate reference", raw);
        AnalysisWorkflow.applyThresholdMask(reference, new double[]{100, 200});
        assertEquals(0, reference.getProcessor().get(20, 20));
        assertEquals(255, reference.getProcessor().get(0, 0));
        final ByteProcessor segmented = AnalysisWorkflow.segmentEndplate(reference.getProcessor());
        assertEquals(255, segmented.get(20, 20));
        assertEquals(0, segmented.get(0, 0));
    }

    @Test
    public void brushStartsSmallAndPreservesCustomSize() {
        assertEquals(12, AnalysisWorkflow.preferredBrushWidth(100));
        assertEquals(12, AnalysisWorkflow.preferredBrushWidth(Double.NaN));
        assertEquals(8, AnalysisWorkflow.preferredBrushWidth(8));
        assertEquals(24, AnalysisWorkflow.preferredBrushWidth(24));
    }

    @Test
    public void remembersChannelSelectionAndRejectsOutOfRangeDefaults() {
        final String muscleKey = "anmj-morph-plus.muscle-channel";
        final String nerveKey = "anmj-morph-plus.nerve-channel";
        final double previousMuscle = Prefs.get(muscleKey, 1);
        final double previousNerve = Prefs.get(nerveKey, 2);
        try {
            Prefs.set(muscleKey, 2);
            Prefs.set(nerveKey, 1);
            assertArrayEquals(new int[] {2, 1}, ANMJMorphCommand.previousChannels(2));
            assertArrayEquals(new int[] {2, 1}, ANMJMorphCommand.previousChannels(4));
            Prefs.set(muscleKey, 4);
            Prefs.set(nerveKey, 1);
            assertArrayEquals(new int[] {1, 2}, ANMJMorphCommand.previousChannels(2));
            Prefs.set(muscleKey, 1);
            Prefs.set(nerveKey, 1);
            assertArrayEquals(new int[] {1, 2}, ANMJMorphCommand.previousChannels(2));
        } finally {
            Prefs.set(muscleKey, previousMuscle);
            Prefs.set(nerveKey, previousNerve);
        }
    }

    @Test
    public void preparesNerveAsWhiteForegroundForSkeletonAnalysis() {
        final ByteProcessor mask = new ByteProcessor(20, 20);
        mask.setValue(255);
        mask.fill();
        for (int y = 4; y < 16; y++) mask.set(10, y, 0);
        final ImagePlus nerve = new ImagePlus("nerve", mask);
        nerve.setRoi(3, 3, 11, 14);
        mask.setThreshold(0, 0, ByteProcessor.NO_LUT_UPDATE);
        AnalysisWorkflow.prepareNerveForSkeleton(nerve);
        assertNull(nerve.getRoi());
        assertFalse(nerve.getProcessor().isThreshold());
        assertEquals(0, nerve.getProcessor().get(0, 0));
        assertEquals(255, nerve.getProcessor().get(10, 10));
    }

    @Test
    public void segmentationCountsEndplateInsteadOfWhiteBackground() {
        final ByteProcessor mask = new ByteProcessor(50, 32);
        mask.setValue(255);
        mask.fill();
        for (int y = 9; y < 18; y++) {
            for (int x = 7; x < 16; x++) mask.set(x, y, 0);
            for (int x = 30; x < 39; x++) mask.set(x, y, 0);
        }
        final ByteProcessor segmented = AnalysisWorkflow.segmentEndplate(mask);
        assertEquals("background should be black", 0, segmented.get(0, 0));
        assertEquals("endplate should be white", 255, segmented.get(10, 12));
        assertEquals("endplate should be white", 255, segmented.get(33, 12));
        final ImagePlus image = new ImagePlus("segmented", segmented);
        assertEquals(2, AnalysisWorkflow.countEndplateClusters(image));
        assertEquals(255.0, image.getProcessor().getMinThreshold(), 0.0);
        assertEquals(255.0, image.getProcessor().getMaxThreshold(), 0.0);
    }
}
