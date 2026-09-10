package io.github.alzeiby.anmjmorphplus;

import ij.ImagePlus;
import ij.io.FileInfo;
import ij.process.ByteProcessor;
import org.junit.Test;

import java.nio.file.Path;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ANMJMorphCommandTest {

    @Test
    public void recognizesSupportedExtensionsCaseInsensitively() {
        assertTrue(ANMJMorphCommand.supported(Path.of("sample.TIFF")));
        assertTrue(ANMJMorphCommand.supported(Path.of("NMJ 1.LSM")));
        assertTrue(ANMJMorphCommand.supported(Path.of("file.CZI")));
        assertTrue(ANMJMorphCommand.supported(Path.of("file.JpEg")));
        assertFalse(ANMJMorphCommand.supported(Path.of("notes.txt")));
    }

    @Test
    public void currentImageUsesCurrentTitleWithOriginalDirectory() {
        final ImagePlus image = new ImagePlus("renamed-current-title.tif", new ByteProcessor(4, 4));
        final FileInfo info = new FileInfo();
        info.directory = Path.of("analysis", "source").toString() + java.io.File.separator;
        info.fileName = "original-on-disk-name.tif";
        image.setFileInfo(info);

        assertEquals(Path.of(info.directory).resolve(image.getTitle()), ANMJMorphCommand.pathForCurrentImage(image));
    }

    @Test
    public void calibrationIsCanonicalizedToMicrons() {
        final ImagePlus image = new ImagePlus("nm.tif", new ByteProcessor(4, 4));
        image.getCalibration().pixelWidth = 250;
        image.getCalibration().pixelHeight = 0.0005;
        image.getCalibration().setUnit("nm");
        image.getCalibration().setYUnit("mm");

        AnalysisWorkflow.canonicalizeCalibration(image);

        assertEquals(0.25, image.getCalibration().pixelWidth, 0.0);
        assertEquals(0.5, image.getCalibration().pixelHeight, 0.0);
        assertEquals("microns", image.getCalibration().getUnits());
        assertEquals("microns", image.getCalibration().getYUnits());
    }

    @Test(expected = IllegalArgumentException.class)
    public void uncalibratedPixelsAreRejected() {
        AnalysisWorkflow.canonicalizeCalibration(new ImagePlus("pixels.tif", new ByteProcessor(4, 4)));
    }
}
