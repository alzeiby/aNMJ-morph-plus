package io.github.alzeiby.anmjmorphplus;

import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.Line;
import ij.gui.Roi;
import ij.plugin.frame.ThresholdAdjuster;

import java.io.IOException;
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
        final Path squareInputDir = squareDir.resolve("input");
        final Path rectangularInputDir = rectangularDir.resolve("input");
        Files.createDirectories(squareInputDir);
        Files.createDirectories(rectangularInputDir);

        final Path squareInput = squareInputDir.resolve("NMJ_1.lsm");
        Files.copy(reference, squareInput, StandardCopyOption.REPLACE_EXISTING);

        final ImageJ imageJ = new ImageJ(ImageJ.NO_SHOW);
        try {
            final Path rectangularInput = rectangularInputDir.resolve("NMJ_1_rect_384x512.tif");
            createRectangularFixture(reference, rectangularInput);

            final DeterministicReviewPrompter squarePrompter =
                new DeterministicReviewPrompter(squareDir.resolve("trace.txt"));
            for (int run = 1; run <= runs; run++) {
                append(squareDir.resolve("trace.txt"), "START direct square run " + run);
                analyzeOne(squareInput, squarePrompter);
                append(squareDir.resolve("trace.txt"), "DONE stage 7 direct square run " + run);
            }

            final DeterministicReviewPrompter rectangularPrompter =
                new DeterministicReviewPrompter(rectangularDir.resolve("trace.txt"));
            for (int run = 1; run <= runs; run++) {
                append(rectangularDir.resolve("trace.txt"), "START direct rectangular run " + run);
                analyzeOne(rectangularInput, rectangularPrompter);
                append(rectangularDir.resolve("trace.txt"), "DONE stage 7 direct rectangular run " + run);
            }
        } finally {
            closeAllImages();
            imageJ.dispose();
        }
    }

    private static void analyzeOne(
        final Path input,
        final DeterministicReviewPrompter prompter
    ) {
        final Set<Integer> existing = imageIds();
        try {
            IJ.resetEscape();
            ImagePlus image = new ImageLoader().load(input);
            final ImageShape shape = ImageShape.from(image);
            final InputNormalization normalization = InputPolicy.normalizationFor(shape);
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

            BatchChoiceResolver.ChannelChoice analysisChoice = CHANNELS;
            boolean channelsCanonical = false;
            if (image.getNChannels() <= ChannelRoleCanonicalizer.IMAGEJ_ARRANGER_MAX_CHANNELS) {
                final ImagePlus canonical = new ChannelRoleCanonicalizer().canonicalize(image, CHANNELS);
                if (canonical != image) {
                    image.changes = false;
                    image.close();
                    image = canonical;
                    image.show();
                }
                analysisChoice = CHANNELS;
                channelsCanonical = image.getNChannels() == 2 && image.isComposite();
            }

            final AnalysisWorkflow workflow = new AnalysisWorkflow(prompter);
            final AnalysisRun run = workflow.analyze(image, input, analysisChoice, channelsCanonical);
            new CsvOutputWriter().append(AnalysisOutputPaths.forInput(input).csv, run.result);
            workflow.finish(run);
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
