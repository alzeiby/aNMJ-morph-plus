package io.github.alzeiby.anmjmorphplus;

import ij.ImagePlus;
import ij.process.ByteProcessor;
import loci.plugins.in.ImporterOptions;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;

public class ImageLoaderTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void tiffUsesNativeImageJAndPreservesBasename() {
        final ImagePlus expected = new ImagePlus("old-title", new ByteProcessor(8, 6));
        final AtomicBoolean bioFormatsCalled = new AtomicBoolean(false);
        final ImageLoader loader = new ImageLoader(
            path -> expected,
            path -> { throw new AssertionError("series counter should not run for TIFF"); },
            options -> {
                bioFormatsCalled.set(true);
                return new ImagePlus[] {expected};
            }
        );

        final ImagePlus loaded = loader.load(Paths.get("folder", "sample.TIFF"));

        assertSame(expected, loaded);
        assertEquals("sample.TIFF", loaded.getTitle());
        assertFalse(bioFormatsCalled.get());
    }

    @Test
    public void bioFormatsUsesSingleSeriesAndReturnsExactImage() {
        final ImagePlus expected = new ImagePlus("source", new ByteProcessor(8, 6));
        final AtomicReference<ImporterOptions> capturedOptions = new AtomicReference<>();
        final ImageLoader loader = new ImageLoader(
            path -> { throw new AssertionError("native opener should not run for PNG"); },
            path -> 1,
            options -> {
                capturedOptions.set(options);
                return new ImagePlus[] {expected};
            }
        );

        final ImagePlus loaded = loader.load(Paths.get("folder", "sample.png"));

        assertSame(expected, loaded);
        assertEquals("sample.png", loaded.getTitle());
        assertEquals(ImporterOptions.COLOR_MODE_DEFAULT, capturedOptions.get().getColorMode());
        assertEquals(ImporterOptions.VIEW_HYPERSTACK, capturedOptions.get().getStackFormat());
        assertEquals(ImporterOptions.ORDER_XYCZT, capturedOptions.get().getStackOrder());
        assertEquals(true, capturedOptions.get().isAutoscale());
        assertEquals(false, capturedOptions.get().openAllSeries());
        assertEquals(true, capturedOptions.get().isQuiet());
    }

    @Test
    public void pinnedBioFormatsApiLoadsRgbPng() throws IOException {
        final File png = temporaryFolder.newFile("rgb.png");
        final BufferedImage source = new BufferedImage(7, 5, BufferedImage.TYPE_INT_RGB);
        source.setRGB(2, 3, 0x3366CC);
        ImageIO.write(source, "png", png);

        final ImagePlus loaded = new ImageLoader().load(png.toPath());

        assertEquals("rgb.png", loaded.getTitle());
        assertEquals(7, loaded.getWidth());
        assertEquals(5, loaded.getHeight());
        assertEquals(3, loaded.getNChannels());
        assertEquals(1, loaded.getNSlices());
        assertEquals(1, loaded.getNFrames());
        assertEquals(8, loaded.getBitDepth());
        loaded.close();
    }

    @Test
    public void multipleBioFormatsSeriesAreRejectedBeforeOpening() {
        final AtomicBoolean opened = new AtomicBoolean(false);
        final ImageLoader loader = new ImageLoader(
            path -> { throw new AssertionError("native opener should not run"); },
            path -> 2,
            options -> {
                opened.set(true);
                return new ImagePlus[0];
            }
        );

        final ImageLoadingException error = assertThrows(
            ImageLoadingException.class,
            () -> loader.load(Paths.get("multi.lsm"))
        );

        assertEquals(
            "Bio-Formats found 2 image series in multi.lsm. aNMJ-morph+ requires a single-series input.",
            error.getMessage()
        );
        assertFalse(opened.get());
    }

    @Test
    public void unsupportedExtensionIsRejectedBeforeAnyOpenerRuns() {
        final ImageLoader loader = new ImageLoader(
            path -> { throw new AssertionError("native opener should not run"); },
            path -> { throw new AssertionError("series counter should not run"); },
            options -> { throw new AssertionError("Bio-Formats should not run"); }
        );

        final ImageLoadingException error = assertThrows(
            ImageLoadingException.class,
            () -> loader.load(Paths.get("sample.gif"))
        );

        assertEquals("Unsupported image format: sample.gif", error.getMessage());
    }
}
