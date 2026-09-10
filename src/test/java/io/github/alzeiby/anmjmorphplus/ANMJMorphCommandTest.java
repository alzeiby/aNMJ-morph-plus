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
}
