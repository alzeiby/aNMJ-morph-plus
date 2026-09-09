package io.github.alzeiby.anmjmorphplus;

import org.junit.Test;

import java.nio.file.Path;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class SupportedImageFormatTest {
    @Test
    public void recognizesSupportedExtensionsCaseInsensitively() {
        assertTrue(ANMJMorphCommand.supported(Path.of("sample.TIFF")));
        assertTrue(ANMJMorphCommand.supported(Path.of("NMJ 1.LSM")));
        assertTrue(ANMJMorphCommand.supported(Path.of("file.CZI")));
        assertTrue(ANMJMorphCommand.supported(Path.of("file.JpEg")));
        assertFalse(ANMJMorphCommand.supported(Path.of("notes.txt")));
    }
}
