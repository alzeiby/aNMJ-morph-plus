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

public class SourceCopyDuplicatorTest {

    private final SourceCopyDuplicator duplicator = new SourceCopyDuplicator();

    @Test
    public void exactDuplicateCommandPreservesPlainTwoChannelImage() {
        final ImageStack stack = new ImageStack(2, 1);
        stack.addSlice(new ByteProcessor(2, 1, new byte[] {7, 9}, null));
        stack.addSlice(new ByteProcessor(2, 1, new byte[] {19, 23}, null));
        final ImagePlus source = new ImagePlus("plain-two-channel.tif", stack);
        source.setDimensions(2, 1, 1);
        source.setOpenAsHyperStack(true);
        source.getCalibration().pixelWidth = 0.5;
        source.getCalibration().pixelHeight = 0.75;
        source.setProperty("Info", "plain two-channel info");

        assertFalse(source.isComposite());
        final ImagePlus copy = duplicateRegistered(source);

        assertFalse(copy.isComposite());
        assertNotEquals(source.getID(), copy.getID());
        assertEquals("__aNMJ_source_" + source.getID(), copy.getTitle());
        assertEquals(2, copy.getNChannels());
        assertEquals(1, copy.getNSlices());
        assertEquals(1, copy.getNFrames());
        assertEquals(7, copy.getStack().getProcessor(1).get(0, 0));
        assertEquals(19, copy.getStack().getProcessor(2).get(0, 0));
        copy.getStack().getProcessor(1).set(0, 0, 99);
        assertEquals(7, source.getStack().getProcessor(1).get(0, 0));
        assertEquals(0.5, copy.getCalibration().pixelWidth, 0.0);
        assertEquals(0.75, copy.getCalibration().pixelHeight, 0.0);
        assertEquals("plain two-channel info", copy.getProperty("Info"));
    }

    @Test
    public void exactDuplicateCommandPreservesCompositePixelsMetadataAndPosition() {
        final ImageStack stack = new ImageStack(2, 1);
        for (int i = 1; i <= 8; i++) {
            stack.addSlice(new ByteProcessor(2, 1, new byte[] {(byte) i, (byte) (i + 20)}, null));
        }
        final ImagePlus base = new ImagePlus("source.tif", stack);
        base.setDimensions(2, 2, 2);
        base.setOpenAsHyperStack(true);
        final CompositeImage source = new CompositeImage(base, CompositeImage.COMPOSITE);
        source.setChannelLut(LUT.createLutFromColor(Color.RED), 1);
        source.setChannelLut(LUT.createLutFromColor(Color.GREEN), 2);
        source.setPosition(1, 1, 1);
        source.setDisplayRange(10, 110);
        source.setPosition(2, 1, 1);
        source.setDisplayRange(20, 120);
        source.setPosition(2, 2, 2);
        final Calibration calibration = source.getCalibration();
        calibration.pixelWidth = 0.5;
        calibration.pixelHeight = 0.75;
        calibration.pixelDepth = 2.5;
        calibration.setUnit("um");
        source.setProperty("Info", "source info");
        source.setProp("custom-key", "custom value");
        final FileInfo fileInfo = new FileInfo();
        fileInfo.fileName = "source.tif";
        fileInfo.directory = "C:\\source data\\";
        source.setFileInfo(fileInfo);

        final ImagePlus copy = duplicateRegistered(source);

        assertNotEquals(source.getID(), copy.getID());
        assertEquals("__aNMJ_source_" + source.getID(), copy.getTitle());
        assertTrue(copy instanceof CompositeImage);
        final CompositeImage compositeCopy = (CompositeImage) copy;
        assertEquals(CompositeImage.COMPOSITE, compositeCopy.getMode());
        assertEquals(255, compositeCopy.getChannelLut(1).getRed(255));
        assertEquals(0, compositeCopy.getChannelLut(1).getGreen(255));
        assertEquals(255, compositeCopy.getChannelLut(2).getGreen(255));
        assertEquals(10.0, compositeCopy.getChannelLut(1).min, 0.0);
        assertEquals(110.0, compositeCopy.getChannelLut(1).max, 0.0);
        assertEquals(20.0, compositeCopy.getChannelLut(2).min, 0.0);
        assertEquals(120.0, compositeCopy.getChannelLut(2).max, 0.0);
        assertEquals(2, copy.getC());
        assertEquals(2, copy.getZ());
        assertEquals(2, copy.getT());
        assertEquals(source.getStack().getProcessor(1).get(0, 0), copy.getStack().getProcessor(1).get(0, 0));
        copy.getStack().getProcessor(1).set(0, 0, 99);
        assertNotEquals(99, source.getStack().getProcessor(1).get(0, 0));
        assertEquals(0.5, copy.getCalibration().pixelWidth, 0.0);
        assertEquals(0.75, copy.getCalibration().pixelHeight, 0.0);
        assertEquals(2.5, copy.getCalibration().pixelDepth, 0.0);
        assertEquals(source.getCalibration().getUnit(), copy.getCalibration().getUnit());
        assertEquals("source info", copy.getProperty("Info"));
        assertEquals("custom value", copy.getProp("custom-key"));
        assertNotNull(copy.getOriginalFileInfo());
        assertEquals("source.tif", copy.getOriginalFileInfo().fileName);
        assertNull(copy.getOriginalFileInfo().directory);
        assertEquals("C:\\source data\\", source.getOriginalFileInfo().directory);
    }

    @Test
    public void exactDuplicateCommandCropsRoiAndAdjustsCalibrationOriginWithoutChangingSource() {
        final ImagePlus source = new ImagePlus("roi.tif", new ByteProcessor(5, 4));
        for (int y = 0; y < source.getHeight(); y++) {
            for (int x = 0; x < source.getWidth(); x++) {
                source.getProcessor().set(x, y, y * 10 + x);
            }
        }
        source.getCalibration().pixelWidth = 0.5;
        source.getCalibration().pixelHeight = 0.75;
        source.getCalibration().xOrigin = 10.0;
        source.getCalibration().yOrigin = 20.0;
        source.setRoi(new Roi(1, 1, 3, 2));

        final ImagePlus copy = duplicateRegistered(source);

        assertEquals(3, copy.getWidth());
        assertEquals(2, copy.getHeight());
        assertEquals(11, copy.getProcessor().get(0, 0));
        assertEquals(23, copy.getProcessor().get(2, 1));
        assertEquals(9.0, copy.getCalibration().xOrigin, 0.0);
        assertEquals(19.0, copy.getCalibration().yOrigin, 0.0);
        assertEquals(5, source.getWidth());
        assertEquals(4, source.getHeight());
        assertNotNull(source.getRoi());
        assertEquals(1, source.getRoi().getBounds().x);
        assertEquals(1, source.getRoi().getBounds().y);
        assertEquals(10.0, source.getCalibration().xOrigin, 0.0);
        assertEquals(20.0, source.getCalibration().yOrigin, 0.0);
    }

    private ImagePlus duplicateRegistered(final ImagePlus source) {
        final boolean previousBatchMode = Interpreter.batchMode;
        Interpreter.batchMode = true;
        Interpreter.addBatchModeImage(source);
        try {
            final ImagePlus copy = duplicator.duplicate(source);
            Interpreter.removeBatchModeImage(copy);
            return copy;
        } finally {
            Interpreter.removeBatchModeImage(source);
            Interpreter.batchMode = previousBatchMode;
        }
    }
}
