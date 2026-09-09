package io.github.alzeiby.anmjmorphplus;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SupportedImageFormatTest {

    @Test
    public void recognizesSupportedExtensionsCaseInsensitively() {
        assertEquals(SupportedImageFormat.TIFF, SupportedImageFormat.fromName("sample.tif").get());
        assertEquals(SupportedImageFormat.TIFF, SupportedImageFormat.fromName("sample.TIFF").get());
        assertEquals(SupportedImageFormat.LSM, SupportedImageFormat.fromName("NMJ 1.LSM").get());
        assertEquals(SupportedImageFormat.CZI, SupportedImageFormat.fromName("file.CZI").get());
        assertEquals(SupportedImageFormat.JPEG, SupportedImageFormat.fromName("file.JpEg").get());
    }

    @Test
    public void distinguishesNativeTiffRoutingFromBioFormatsRouting() {
        assertTrue(SupportedImageFormat.TIFF.usesNativeImageJ());
        assertFalse(SupportedImageFormat.LSM.usesNativeImageJ());
        assertFalse(SupportedImageFormat.PNG.usesNativeImageJ());
        assertFalse(SupportedImageFormat.BMP.usesNativeImageJ());
    }

    @Test
    public void rejectsUnsupportedNames() {
        assertFalse(SupportedImageFormat.fromName("notes.txt").isPresent());
        assertFalse(SupportedImageFormat.fromName(null).isPresent());
    }
}
