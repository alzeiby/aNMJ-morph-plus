package io.github.alzeiby.anmjmorphplus;

import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.gui.Line;
import ij.gui.Roi;
import ij.measure.Calibration;
import ij.plugin.frame.ThresholdAdjuster;
import ij.process.ByteProcessor;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Set;

/**
 * Fresh-Fiji direct-Java scientific runtime oracle.
 *
 * <p>This harness deliberately lives in the production package so it can use the package-private
 * AnalysisWorkflow ReviewPrompter seam without adding any production-only test API. It never runs,
 * reads, or transforms the legacy IJM resource.</p>
 */
public final class DirectJavaAnalysisRuntime {

    private static final int RECT_WIDTH = 384;
    private static final int RECT_HEIGHT = 512;
    private static final BatchChoiceResolver.ChannelChoice CHANNELS =
        new BatchChoiceResolver.ChannelChoice(1, 2);

    private DirectJavaAnalysisRuntime() {
    }

    public static void main(final String[] args) {
        try {
            if (args.length != 3) {
                throw new IllegalArgumentException(
                    "Expected: <NMJ_1.lsm> <work-directory> <runs>"
                );
            }
            final Path reference = Path.of(args[0]).toAbsolutePath().normalize();
            final Path work = Path.of(args[1]).toAbsolutePath().normalize();
            final int runs = Integer.parseInt(args[2]);
            if (runs < 1) {
                throw new IllegalArgumentException("runs must be >= 1");
            }
            run(reference, work, runs);
            System.out.println("DONE direct Java analysis runtime");
            System.exit(0);
        } catch (Throwable error) {
            error.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static void run(final Path reference, final Path work, final int runs) throws IOException {
        if (!Files.isRegularFile(reference)) {
            throw new IllegalArgumentException("Missing NMJ_1 reference: " + reference);
        }
        require(
            DirectJavaAnalysisRuntime.class.getResource("/legacy/aNMJ-morph macro.txt") == null,
            "Direct-Java oracle must run with the legacy macro resource physically absent"
        );

        Files.createDirectories(work);
        final Path squareDir = work.resolve("square");
        final Path rectangularDir = work.resolve("rectangular");
        final Path anisotropicDir = work.resolve("anisotropic");
        final Path squareInputDir = squareDir.resolve("input");
        final Path rectangularInputDir = rectangularDir.resolve("input");
        final Path anisotropicInputDir = anisotropicDir.resolve("input");
        Files.createDirectories(squareInputDir);
        Files.createDirectories(rectangularInputDir);
        Files.createDirectories(anisotropicInputDir);

        final Path squareInput = squareInputDir.resolve("NMJ_1.lsm");
        Files.copy(reference, squareInput, StandardCopyOption.REPLACE_EXISTING);

        final ImageJ imageJ = new ImageJ(ImageJ.NO_SHOW);
        try {
            verifyChannelSelectionScience();
            final Path rectangularInput = rectangularInputDir.resolve("NMJ_1_rect_384x512.tif");
            createRectangularFixture(reference, rectangularInput);
            final Path anisotropicInput = anisotropicInputDir.resolve("NMJ_1_aniso_y2.lsm");
            Files.copy(reference, anisotropicInput, StandardCopyOption.REPLACE_EXISTING);

            final DeterministicReviewPrompter squarePrompter =
                new DeterministicReviewPrompter(squareDir.resolve("trace.txt"));
            for (int run = 1; run <= runs; run++) {
                append(squareDir.resolve("trace.txt"), "START direct square run " + run);
                analyzeOne(squareInput, squarePrompter, false);
                append(squareDir.resolve("trace.txt"), "DONE stage 7 direct square run " + run);
            }

            final DeterministicReviewPrompter rectangularPrompter =
                new DeterministicReviewPrompter(rectangularDir.resolve("trace.txt"));
            for (int run = 1; run <= runs; run++) {
                append(rectangularDir.resolve("trace.txt"), "START direct rectangular run " + run);
                analyzeOne(rectangularInput, rectangularPrompter, false);
                append(rectangularDir.resolve("trace.txt"), "DONE stage 7 direct rectangular run " + run);
            }

            final DeterministicReviewPrompter anisotropicPrompter =
                new DeterministicReviewPrompter(anisotropicDir.resolve("trace.txt"));
            for (int run = 1; run <= runs; run++) {
                append(anisotropicDir.resolve("trace.txt"), "START direct anisotropic run " + run);
                analyzeOne(anisotropicInput, anisotropicPrompter, true);
                append(anisotropicDir.resolve("trace.txt"), "DONE stage 7 direct anisotropic run " + run);
            }
        } finally {
            closeAllImages();
            imageJ.dispose();
        }
    }

    private static void verifyChannelSelectionScience() {
        verifySelectedChannels(3, new BatchChoiceResolver.ChannelChoice(3, 1), 33, 11);
        verifySelectedChannels(10, new BatchChoiceResolver.ChannelChoice(10, 3), 11, 22);
    }

    private static void verifySelectedChannels(
        final int channelCount,
        final BatchChoiceResolver.ChannelChoice choice,
        final int expectedMuscle,
        final int expectedNerve
    ) {
        final Set<Integer> existing = imageIds();
        ImagePlus source = null;
        try {
            final ImageStack stack = new ImageStack(2, 2);
            for (int channel = 1; channel <= channelCount; channel++) {
                final byte value = (byte) (channel * 11);
                stack.addSlice(new ByteProcessor(2, 2, new byte[] {value, value, value, value}, null));
            }
            source = new ImagePlus("channel-selection-probe", stack);
            source.setDimensions(channelCount, 1, 1);
            source.setOpenAsHyperStack(true);
            final Calibration calibration = source.getCalibration().copy();
            calibration.pixelWidth = 2.5;
            calibration.pixelHeight = 7.0;
            calibration.setUnit("um");
            source.setCalibration(calibration);
            final String expectedUnit = source.getCalibration().getUnit();

            final Method splitSelected = AnalysisWorkflow.class.getDeclaredMethod(
                "splitSelected", ImagePlus.class, BatchChoiceResolver.ChannelChoice.class
            );
            splitSelected.setAccessible(true);
            final Object channels = splitSelected.invoke(null, source, choice);
            final Field muscleField = channels.getClass().getDeclaredField("muscle");
            final Field nerveField = channels.getClass().getDeclaredField("nerve");
            muscleField.setAccessible(true);
            nerveField.setAccessible(true);
            final ImagePlus muscle = (ImagePlus) muscleField.get(channels);
            final ImagePlus nerve = (ImagePlus) nerveField.get(channels);

            require(muscle.getProcessor().get(0, 0) == expectedMuscle,
                "Muscle channel pixels changed for " + channelCount + "-channel input");
            require(nerve.getProcessor().get(0, 0) == expectedNerve,
                "Nerve channel pixels changed for " + channelCount + "-channel input");
            require(Double.compare(muscle.getCalibration().pixelWidth, 2.5) == 0 &&
                    Double.compare(muscle.getCalibration().pixelHeight, 7.0) == 0,
                "Muscle channel calibration changed for " + channelCount + "-channel input");
            require(Double.compare(nerve.getCalibration().pixelWidth, 2.5) == 0 &&
                    Double.compare(nerve.getCalibration().pixelHeight, 7.0) == 0,
                "Nerve channel calibration changed for " + channelCount + "-channel input");
            require(expectedUnit.equals(muscle.getCalibration().getUnit()) &&
                    expectedUnit.equals(nerve.getCalibration().getUnit()),
                "Channel calibration unit changed for " + channelCount + "-channel input");
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Could not run channel-selection scientific probe", e);
        } finally {
            if (source != null && source.getWindow() == null) {
                source.changes = false;
                source.close();
            }
            closeImagesCreatedAfter(existing);
        }
    }

    private static void analyzeOne(
        final Path input,
        final DeterministicReviewPrompter prompter,
        final boolean doubleYPixelSize
    ) {
        final Set<Integer> existing = imageIds();
        try {
            IJ.resetEscape();
            ImagePlus image = new ImageLoader().load(input);
            final InputNormalization normalization = StructuralNormalizer.normalizationFor(image);
            if (normalization == InputNormalization.REJECT_TIME_SERIES) {
                throw new IllegalStateException("Unexpected T>1 input in direct runtime fixture");
            }

            final TwoPlaneInterpretation twoPlane =
                normalization == InputNormalization.CHOOSE_TWO_PLANE_INTERPRETATION
                    ? TwoPlaneInterpretation.CHANNELS
                    : null;
            final ImagePlus normalized = new StructuralNormalizer().normalize(image, twoPlane);
            if (normalized != image) {
                image.changes = false;
                image.close();
                image = normalized;
            }
            image.show();

            if (doubleYPixelSize) {
                final ij.measure.Calibration calibration = image.getCalibration().copy();
                calibration.pixelHeight = calibration.pixelWidth * 2.0;
                image.setCalibration(calibration);
            }

            final AnalysisWorkflow workflow = new AnalysisWorkflow(prompter);
            workflow.analyze(
                image,
                input,
                CHANNELS,
                result -> new CsvOutputWriter().append(AnalysisOutputPaths.forInput(input).csv, result)
            );
        } finally {
            closeImagesCreatedAfter(existing);
        }
    }

    private static void createRectangularFixture(final Path reference, final Path output) {
        final Set<Integer> existing = imageIds();
        try {
            final ImagePlus source = new ImageLoader().load(reference);
            source.show();
            require(source.getWidth() >= RECT_WIDTH && source.getHeight() >= RECT_HEIGHT,
                "Reference is too small for rectangular fixture");
            source.setRoi(new Roi(0, 0, RECT_WIDTH, RECT_HEIGHT));
            IJ.run(source, "Duplicate...", "title=[NMJ_1_rect_384x512.tif] duplicate");
            final ImagePlus rectangular = WindowManager.getCurrentImage();
            require(rectangular != null, "Duplicate did not create rectangular fixture");
            require(rectangular.getWidth() == RECT_WIDTH && rectangular.getHeight() == RECT_HEIGHT,
                "Rectangular fixture dimensions changed");
            IJ.saveAs(rectangular, "Tiff", output.toString());
            require(Files.isRegularFile(output), "Rectangular fixture was not saved");
        } finally {
            closeImagesCreatedAfter(existing);
        }
    }

    private static final class DeterministicReviewPrompter implements AnalysisWorkflow.ReviewPrompter {
        private final Path trace;

        DeterministicReviewPrompter(final Path trace) {
            this.trace = trace;
        }

        @Override
        public void review(final ImagePlus subject, final String message) {
            if (message.startsWith("2/7 Threshold Nerve terminal.")) {
                applyDefaultThreshold(subject, "Screen 2");
                return;
            }
            if (message.startsWith("3/7 Threshold Muscle Endplate.")) {
                applyDefaultThreshold(subject, "Screen 3");
                return;
            }
            if (message.startsWith("Screen 4/7 Measuring Axon Width.")) {
                synthesizeAxonWidths(subject);
                append(trace, "REVIEW Screen 4 widths=10,12,8");
                return;
            }
            if (message.startsWith("Screen 5/7 Erase Axon")) {
                append(trace, "REVIEW Screen 5 no erase edits");
                return;
            }
            if (message.startsWith("7/7 Congratulations-")) {
                append(trace, "REVIEW Screen 7 accepted");
                return;
            }
            if (message.startsWith("WARNING: The image has a different scale on the X and Y axes.")) {
                append(trace, "REVIEW anisotropic warning accepted");
                return;
            }
            throw new IllegalStateException("Unexpected review prompt in deterministic direct runtime: " + message);
        }

        @Override
        public boolean confirmSegmentation(final String message) {
            require(message.startsWith("Screen 6/7 Check segmented image."),
                "Unexpected segmentation confirmation message");
            append(trace, "REVIEW Screen 6 accepted");
            return true;
        }

        private void applyDefaultThreshold(final ImagePlus subject, final String screen) {
            require(subject != null, screen + " subject is null");
            final int expectedId = subject.getID();
            final Object processor = subject.getProcessor();
            ThresholdAdjuster.setMethod("Default");
            IJ.setAutoThreshold(subject, "Default dark");
            require(subject.getID() == expectedId, screen + " subject ID changed during threshold automation");
            require(subject.getProcessor() == processor, screen + " processor changed during threshold automation");
            append(trace, "REVIEW " + screen + " threshold=Default image=" + expectedId);
        }

        private void synthesizeAxonWidths(final ImagePlus subject) {
            final int[][] lines = {
                {100, 100, 110, 100},
                {100, 110, 112, 110},
                {100, 120, 108, 120}
            };
            for (int[] coordinates : lines) {
                subject.setRoi(new Line(
                    coordinates[0], coordinates[1], coordinates[2], coordinates[3]
                ));
                IJ.run(subject, "Measure", "");
            }
        }
    }

    private static Set<Integer> imageIds() {
        final Set<Integer> ids = new HashSet<>();
        final int[] current = WindowManager.getIDList();
        if (current != null) {
            for (int id : current) {
                ids.add(id);
            }
        }
        return ids;
    }

    private static void closeImagesCreatedAfter(final Set<Integer> existing) {
        final int[] current = WindowManager.getIDList();
        if (current == null) {
            return;
        }
        for (int id : current) {
            if (!existing.contains(id)) {
                final ImagePlus image = WindowManager.getImage(id);
                if (image != null) {
                    image.changes = false;
                    image.close();
                }
            }
        }
    }

    private static void closeAllImages() {
        final int[] current = WindowManager.getIDList();
        if (current == null) {
            return;
        }
        for (int id : current) {
            final ImagePlus image = WindowManager.getImage(id);
            if (image != null) {
                image.changes = false;
                image.close();
            }
        }
    }

    private static void append(final Path trace, final String line) {
        try {
            Files.createDirectories(trace.getParent());
            Files.writeString(
                trace,
                line + System.lineSeparator(),
                StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND
            );
        } catch (IOException e) {
            throw new IllegalStateException("Could not append direct-Java runtime trace", e);
        }
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
