package io.github.alzeiby.anmjmorphplus;

import ij.ImageJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;

public class ANMJMorphWorkflowSmokeTest {

    @Test(timeout = 30000)
    public void completesWithSelectedThresholdsAndWritesOutput() throws Exception {
        final ImageJ app = new ImageJ(ImageJ.NO_SHOW);
        final Path root = Files.createTempDirectory("anmj-workflow-");
        try {
            final ImageStack channels = new ImageStack(64, 64);
            for (int c = 0; c < 2; c++) {
                final ByteProcessor pixels = new ByteProcessor(64, 64);
                for (int y = 12; y < 52; y++) {
                    for (int x = 12; x < 52; x++) {
                        if ((x - 32) * (x - 32) + (y - 32) * (y - 32) < (c == 0 ? 16 * 16 : 12 * 12)) {
                            pixels.set(x, y, 200);
                        }
                    }
                }
                channels.addSlice(pixels);
            }
            final ImagePlus image = new ImagePlus("fixture.tif", channels);
            image.setDimensions(2, 1, 1);
            image.setOpenAsHyperStack(true);
            image.show();
            new AnalysisWorkflow(new AnalysisWorkflow.ReviewPrompter() {
                @Override
                public void review(final ImagePlus subject, final String message) {
                    if (message.contains("threshold")) {
                        final String title = message.contains("nerve") ? "Nerve reference" : "Muscle reference";
                        final ImagePlus reference = WindowManager.getImage(title);
                        assertNotNull(title + " must remain visible", reference);
                        assertNotNull(reference.getWindow());
                        assertNotNull(subject.getWindow());
                        assertTrue("reference should be to the left of the working image",
                            reference.getWindow().getX() + reference.getWindow().getWidth() <=
                                subject.getWindow().getX());
                        assertFalse("reference should remain unthresholded", reference.getProcessor().isThreshold());
                        subject.getProcessor().setThreshold(100, 255, ImageProcessor.NO_LUT_UPDATE);
                    }
                }

                @Override
                public boolean confirmSegmentation(final String message) {
                    return true;
                }
            }).analyze(image, root.resolve("fixture.tif"), 1, 2);
            assertTrue(Files.size(root.resolve("raw_data_table_pixels.csv")) > 50);
            assertTrue(Files.exists(root.resolve("cleaned_images").resolve("axon_terminalfixture.tif")));
        } finally {
            final int[] ids = WindowManager.getIDList();
            if (ids != null) for (int id : ids) {
                final ImagePlus image = WindowManager.getImage(id);
                if (image != null) { image.changes = false; image.close(); }
            }
            app.quit();
        }
    }
}
