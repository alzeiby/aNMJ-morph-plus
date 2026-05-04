package io.github.alzeiby.anmjmorphplus;

import ij.ImageJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.gui.ImageWindow;
import ij.process.ByteProcessor;
import ij.process.ImageProcessor;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertFalse;

public class ANMJMorphWorkflowSmokeTest {

    @Test(timeout = 30000)
    public void cancellationNeverSignalsSuccessfulCompletion() throws Exception {
        final ImageJ app = new ImageJ(ImageJ.NO_SHOW);
        final Path root = Files.createTempDirectory("anmj-cancelled-");
        final List<Double> progress = new ArrayList<>();
        try {
            final ImageStack stack = new ImageStack(64, 64);
            for (int c = 0; c < 2; c++) {
                final ByteProcessor pixels = new ByteProcessor(64, 64);
                for (int y = 12; y < 52; y++)
                    for (int x = 12; x < 52; x++)
                        if ((x - 32) * (x - 32) + (y - 32) * (y - 32) < 16 * 16)
                            pixels.set(x, y, 200);
                stack.addSlice(pixels);
            }
            final ImagePlus image = new ImagePlus("cancelled.tif", stack);
            image.setDimensions(2, 1, 1);
            image.setOpenAsHyperStack(true);
            image.show();
            try {
                new AnalysisWorkflow(new AnalysisWorkflow.ReviewPrompter() {
                    @Override
                    public void review(ImagePlus target, String instruction) {
                        if (instruction.contains("threshold")) {
                            target.getProcessor().setThreshold(100, 255, ImageProcessor.NO_LUT_UPDATE);
                        }
                    }
                    @Override
                    public boolean confirmSegmentation(String instruction) {
                        throw new ANMJMorphCommand.Cancelled();
                    }
                }, (fraction, status) -> progress.add(fraction))
                    .analyze(image, root.resolve("cancelled.tif"), 1, 2);
                org.junit.Assert.fail("Expected cancellation");
            } catch (ANMJMorphCommand.Cancelled expected) {
                assertFalse(progress.contains(1.0));
                assertEquals(-1.0, progress.get(progress.size() - 1), 0.0);
                assertFalse(Files.exists(root.resolve("raw_data_table_pixels.csv")));
            }
        } finally {
            final int[] ids = WindowManager.getIDList();
            if (ids != null) for (int id : ids) {
                final ImagePlus opened = WindowManager.getImage(id);
                if (opened != null) { opened.changes = false; opened.close(); }
            }
            app.quit();
        }
    }

    @Test(timeout = 30000)
    public void completesWithSelectedThresholdsAndWritesOutput() throws Exception {
        final ImageJ app = new ImageJ(ImageJ.NO_SHOW);
        final Path root = Files.createTempDirectory("anmj-workflow-");
        try {
            final List<Double> fractions = new ArrayList<>();
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
                    } else if (message.contains("Draw a line") ||
                        message.contains("Erase the axon")) {
                        assertTrue("Progress must not start while user is editing", fractions.isEmpty());
                        final ImagePlus reference = WindowManager.getImage("Nerve reference");
                        assertNotNull("Nerve reference should be visible during cleanup", reference);
                        assertNotNull(reference.getWindow());
                        assertTrue(reference.getWindow().isVisible());
                        assertEquals("Only the reference and working nerve images should show",
                            2, WindowManager.getIDList().length);
                        assertTrue(reference.getWindow().getX() + reference.getWindow().getWidth() <=
                            subject.getWindow().getX());
                    }
                }

                @Override
                public boolean confirmSegmentation(final String message) {
                    assertTrue("Segmentation must be complete before approval",
                        fractions.contains(0.85));
                    assertFalse("Review is not completion", fractions.contains(1.0));
                    final ImagePlus reference = WindowManager.getImage("Muscle reference");
                    final ImagePlus preview = WindowManager.getImage("Endplate segmented");
                    assertNotNull("Original muscle image must be visible", reference);
                    assertNotNull("Segmented preview must be visible", preview);
                    assertNotNull(reference.getWindow());
                    assertNotNull(preview.getWindow());
                    assertEquals("Preview must be an editable binary foreground mask",
                        8, preview.getBitDepth());
                    assertTrue(reference.getWindow().isVisible());
                    assertTrue(preview.getWindow().isVisible());
                    assertEquals("Only original and segmented windows should be displayed",
                        2, WindowManager.getIDList().length);
                    assertTrue("Original should appear to the left",
                        reference.getWindow().getX() + reference.getWindow().getWidth() <=
                            preview.getWindow().getX());
                    return true;
                }
            }, (fraction, status) -> {
                if (fraction == 1.0) {
                    assertTrue("CSV must be saved before progress reaches 100%",
                        Files.isRegularFile(root.resolve("raw_data_table_pixels.csv")));
                    try {
                        assertTrue(Files.size(root.resolve("raw_data_table_pixels.csv")) > 50);
                        assertTrue(Files.size(root.resolve("cleaned_images/segmented_endplatefixture.tif")) > 0);
                    } catch (java.io.IOException e) {
                        throw new AssertionError(e);
                    }
                }
                fractions.add(fraction);
            }).analyze(image, root.resolve("fixture.tif"), 1, 2);
            assertEquals("All completed stages must be reported in order",
                List.of(0.0, 0.20, 0.50, 0.75, 0.85, 0.90, 0.97, 1.0), fractions);
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
