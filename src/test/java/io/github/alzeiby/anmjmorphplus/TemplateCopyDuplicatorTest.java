package io.github.alzeiby.anmjmorphplus;

import ij.CompositeImage;
import ij.ImagePlus;
import ij.ImageStack;
import ij.io.FileInfo;
import ij.macro.Interpreter;
import ij.measure.Calibration;
import ij.process.ByteProcessor;
import ij.process.LUT;
import ij.gui.Roi;
import org.junit.Test;

import java.awt.Color;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

public class TemplateCopyDuplicatorTest {

    private final TemplateCopyDuplicator duplicator = new TemplateCopyDuplicator();

    @Test
    public void exactDuplicateCommandPreservesPlainTwoChannelSourceCopy() {
        final ImageStack stack = new ImageStack(2, 1);
        stack.addSlice(new ByteProcessor(2, 1, new byte[] {7, 9}, null));
        stack.addSlice(new ByteProcessor(2, 1, new byte[] {19, 23}, null));
        final ImagePlus sourceCopy = new ImagePlus("__aNMJ_source_41", stack);
        sourceCopy.setDimensions(2, 1, 1);
        sourceCopy.setOpenAsHyperStack(true);
        sourceCopy.getCalibration().pixelWidth = 0.5;
        sourceCopy.getCalibration().pixelHeight = 0.75;
        sourceCopy.setProperty("Info", "plain source-copy info");

        assertFalse(sourceCopy.isComposite());
        final ImagePlus templateCopy = duplicateRegistered(sourceCopy);

        assertFalse(templateCopy.isComposite());
        assertNotEquals(sourceCopy.getID(), templateCopy.getID());
        assertEquals("__aNMJ_template_" + sourceCopy.getID(), templateCopy.getTitle());
        assertEquals(2, templateCopy.getNChannels());
        assertEquals(1, templateCopy.getNSlices());
        assertEquals(1, templateCopy.getNFrames());
        assertEquals(7, templateCopy.getStack().getProcessor(1).get(0, 0));
        assertEquals(19, templateCopy.getStack().getProcessor(2).get(0, 0));
        templateCopy.getStack().getProcessor(1).set(0, 0, 99);
        assertEquals(7, sourceCopy.getStack().getProcessor(1).get(0, 0));
        assertEquals(0.5, templateCopy.getCalibration().pixelWidth, 0.0);
        assertEquals(0.75, templateCopy.getCalibration().pixelHeight, 0.0);
        assertEquals("plain source-copy info", templateCopy.getProperty("Info"));
    }

    @Test
    public void exactDuplicateCommandPreservesCompositeLutsRangesMetadataAndPosition() {
        final ImageStack stack = new ImageStack(2, 1);
        for (int i = 1; i <= 8; i++) {
            stack.addSlice(new ByteProcessor(2, 1, new byte[] {(byte) i, (byte) (i + 20)}, null));
        }
        final ImagePlus base = new ImagePlus("__aNMJ_source_51", stack);
        base.setDimensions(2, 2, 2);
        base.setOpenAsHyperStack(true);
        final CompositeImage sourceCopy = new CompositeImage(base, CompositeImage.COMPOSITE);
        sourceCopy.setChannelLut(LUT.createLutFromColor(Color.RED), 1);
        sourceCopy.setChannelLut(LUT.createLutFromColor(Color.GREEN), 2);
        sourceCopy.setPosition(1, 1, 1);
        sourceCopy.setDisplayRange(10, 110);
        sourceCopy.setPosition(2, 1, 1);
        sourceCopy.setDisplayRange(20, 120);
        sourceCopy.setPosition(2, 2, 2);
        final Calibration calibration = sourceCopy.getCalibration();
        calibration.pixelWidth = 0.5;
        calibration.pixelHeight = 0.75;
        calibration.pixelDepth = 2.5;
        calibration.setUnit("um");
        sourceCopy.setProperty("Info", "source-copy info");
        sourceCopy.setProp("custom-key", "custom value");
        final FileInfo fileInfo = new FileInfo();
        fileInfo.fileName = "source.tif";
        fileInfo.directory = "C:\\source data\\";
        sourceCopy.setFileInfo(fileInfo);

        final ImagePlus templateCopy = duplicateRegistered(sourceCopy);

        assertNotEquals(sourceCopy.getID(), templateCopy.getID());
        assertEquals("__aNMJ_template_" + sourceCopy.getID(), templateCopy.getTitle());
        assertTrue(templateCopy instanceof CompositeImage);
        final CompositeImage compositeCopy = (CompositeImage) templateCopy;
        assertEquals(CompositeImage.COMPOSITE, compositeCopy.getMode());
        assertEquals(255, compositeCopy.getChannelLut(1).getRed(255));
        assertEquals(0, compositeCopy.getChannelLut(1).getGreen(255));
        assertEquals(255, compositeCopy.getChannelLut(2).getGreen(255));
        assertEquals(10.0, compositeCopy.getChannelLut(1).min, 0.0);
        assertEquals(110.0, compositeCopy.getChannelLut(1).max, 0.0);
        assertEquals(20.0, compositeCopy.getChannelLut(2).min, 0.0);
        assertEquals(120.0, compositeCopy.getChannelLut(2).max, 0.0);
        assertEquals(2, templateCopy.getC());
        assertEquals(2, templateCopy.getZ());
        assertEquals(2, templateCopy.getT());
        assertEquals(sourceCopy.getStack().getProcessor(1).get(0, 0), templateCopy.getStack().getProcessor(1).get(0, 0));
        templateCopy.getStack().getProcessor(1).set(0, 0, 99);
        assertNotEquals(99, sourceCopy.getStack().getProcessor(1).get(0, 0));
        assertEquals(0.5, templateCopy.getCalibration().pixelWidth, 0.0);
        assertEquals(0.75, templateCopy.getCalibration().pixelHeight, 0.0);
        assertEquals(2.5, templateCopy.getCalibration().pixelDepth, 0.0);
        assertEquals(sourceCopy.getCalibration().getUnit(), templateCopy.getCalibration().getUnit());
        assertEquals("source-copy info", templateCopy.getProperty("Info"));
        assertEquals("custom value", templateCopy.getProp("custom-key"));
        assertNotNull(templateCopy.getOriginalFileInfo());
        assertEquals("source.tif", templateCopy.getOriginalFileInfo().fileName);
        assertNull(templateCopy.getOriginalFileInfo().directory);
        assertEquals("C:\\source data\\", sourceCopy.getOriginalFileInfo().directory);
    }

    @Test
    public void exactDuplicateCommandCropsSourceCopyRoiAndAdjustsCalibrationOrigin() {
        final ImagePlus sourceCopy = new ImagePlus("__aNMJ_source_61", new ByteProcessor(5, 4));
        for (int y = 0; y < sourceCopy.getHeight(); y++) {
            for (int x = 0; x < sourceCopy.getWidth(); x++) {
                sourceCopy.getProcessor().set(x, y, y * 10 + x);
            }
        }
        sourceCopy.getCalibration().pixelWidth = 0.5;
        sourceCopy.getCalibration().pixelHeight = 0.75;
        sourceCopy.getCalibration().xOrigin = 10.0;
        sourceCopy.getCalibration().yOrigin = 20.0;
        sourceCopy.setRoi(new Roi(1, 1, 3, 2));

        final ImagePlus templateCopy = duplicateRegistered(sourceCopy);

        assertEquals(3, templateCopy.getWidth());
        assertEquals(2, templateCopy.getHeight());
        assertEquals(11, templateCopy.getProcessor().get(0, 0));
        assertEquals(23, templateCopy.getProcessor().get(2, 1));
        assertEquals(9.0, templateCopy.getCalibration().xOrigin, 0.0);
        assertEquals(19.0, templateCopy.getCalibration().yOrigin, 0.0);
        assertEquals(5, sourceCopy.getWidth());
        assertEquals(4, sourceCopy.getHeight());
        assertNotNull(sourceCopy.getRoi());
        assertEquals(1, sourceCopy.getRoi().getBounds().x);
        assertEquals(1, sourceCopy.getRoi().getBounds().y);
        assertEquals(10.0, sourceCopy.getCalibration().xOrigin, 0.0);
        assertEquals(20.0, sourceCopy.getCalibration().yOrigin, 0.0);
    }

    private ImagePlus duplicateRegistered(final ImagePlus sourceCopy) {
        final boolean previousBatchMode = Interpreter.batchMode;
        Interpreter.batchMode = true;
        Interpreter.addBatchModeImage(sourceCopy);
        try {
            final ImagePlus templateCopy = duplicator.duplicate(sourceCopy);
            Interpreter.removeBatchModeImage(templateCopy);
            return templateCopy;
        } finally {
            Interpreter.removeBatchModeImage(sourceCopy);
            Interpreter.batchMode = previousBatchMode;
        }
    }
}
