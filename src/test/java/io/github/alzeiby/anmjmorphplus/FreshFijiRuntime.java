package io.github.alzeiby.anmjmorphplus;

import ij.IJ;
import ij.ImageJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.gui.Line;
import ij.gui.Roi;
import ij.gui.Toolbar;
import ij.measure.Calibration;
import ij.plugin.frame.ThresholdAdjuster;
import ij.plugin.tool.BrushTool;
import ij.process.ByteProcessor;
import org.scijava.Context;
import org.scijava.command.CommandInfo;
import org.scijava.command.CommandService;
import org.scijava.plugin.PluginService;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Fresh-Fiji direct-Java scientific runtime oracle.
 *
 * <p>This harness deliberately lives in the production package so it can use the package-private
 * AnalysisWorkflow ReviewPrompter seam without adding any production-only test API. It never runs,
 * reads, or transforms the legacy IJM resource.</p>
 */
public final class FreshFijiRuntime {

    private static final int RECT_WIDTH = 384;
    private static final int RECT_HEIGHT = 512;
    private static final String REFERENCE_SHA256 =
        "b60dcedcd7509183e61a5301110f114fdaf366348f8297cae96608d6c902e0c2";
    private static final String HEADER_1 =
        "IMAGE DETAILS (frame size),,NMJ,THRESHOLD,PRE-SYNAPTIC,,,,Branch analysis,,,,,,,,,POST-SYNAPTIC";
    private static final String HEADER_2 =
        "\"Number of pixels (eg, 512 x 512)\",\"Metric (eg, 67.48 x 67.48um)\",\"Ref number\",\"(nerve terminal/motor endplate)\",\"Number of Axonal Inputs\",\"Axon Diameter (um)\",\"Nerve Terminal Perimeter (um)\",\"Nerve Terminal Area (um2)\",\"Skeleton Trees\",\"Terminal Tips\",\"Triple Junctions\",\"Quadruple Junctions\",\" Number of Terminal Branches\",\"Number of Branch Points\",\"Total Length of Branches (um)\",\"Average Length of Branches (um)\",\"\"\"Complexity\"\"\",\"AChR Perimeter (um)\",\"AChR Area (um2)\",\"Endplate Diameter (um)\",\"Endplate Perimeter (um)\",\"Endplate Area (um2)\",\"\"\"Compactness\"\" (%)\",\"Unoccupied AChR Area (um2)\",\"\"\"Area of Synaptic Contact\"\" (um2)\",\"\"\"Overlap\"\" (%)\",\"Number of AChR Clusters\",\"Average Area of AChR Clusters (um2)\",\"\"\"Fragmentation\"\"\"";

    private FreshFijiRuntime() {
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
            System.out.println("Fresh Fiji runtime passed");
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
        require(REFERENCE_SHA256.equals(sha256(reference)), "NMJ_1 reference hash changed");
        require(
            FreshFijiRuntime.class.getResource("/legacy/aNMJ-morph macro.txt") == null,
            "Legacy macro resource must not be packaged"
        );
        verifyPluginDiscovery();

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

            final DeterministicReviewPrompter squarePrompter = new DeterministicReviewPrompter();
            for (int run = 1; run <= runs; run++) {
                analyzeOne(squareInput, squarePrompter, false);
            }

            final DeterministicReviewPrompter rectangularPrompter = new DeterministicReviewPrompter();
            for (int run = 1; run <= runs; run++) {
                analyzeOne(rectangularInput, rectangularPrompter, false);
            }

            final DeterministicReviewPrompter anisotropicPrompter = new DeterministicReviewPrompter();
            for (int run = 1; run <= runs; run++) {
                analyzeOne(anisotropicInput, anisotropicPrompter, true);
            }

            validateCase(squareDir, "512 x 512", "NMJ_1.lsm", runs, false);
            validateCase(rectangularDir, "384 x 512", "NMJ_1_rect_384x512.tif", runs, false);
            validateCase(anisotropicDir, "512 x 512", "NMJ_1_aniso_y2.lsm", runs, true);
        } finally {
            closeAllImages();
            imageJ.dispose();
        }
    }

    private static void verifyPluginDiscovery() throws IOException {
        try (Context context = new Context(PluginService.class, CommandService.class)) {
            context.service(PluginService.class).reloadPlugins();
            final CommandInfo command = context.service(CommandService.class)
                .getCommand("io.github.alzeiby.anmjmorphplus.ANMJMorphCommand");
            require(command != null, "aNMJ-morph+ command was not discovered");
            require("Analyze > Tools > aNMJ-morph+".equals(command.getMenuPath().getMenuString()),
                "Unexpected plugin menu path");
            try {
                Class.forName("io.github.alzeiby.anmjmorphplus.LegacyMacroRunner");
                throw new IllegalStateException("LegacyMacroRunner is still packaged");
            } catch (ClassNotFoundException expected) {
                // Expected: the Java plugin is self-contained.
            }
        }
    }

    private static void verifyChannelSelectionScience() {
        verifySelectedChannels(3, 3, 1, 33, 11);
        verifySelectedChannels(10, 10, 3, 110, 33);
        verifySelectedChannelsIgnoreAreaRoi();
    }

    private static void verifySelectedChannelsIgnoreAreaRoi() {
        final ImageStack stack = new ImageStack(10, 8);
        stack.addSlice(new ByteProcessor(10, 8));
        stack.addSlice(new ByteProcessor(10, 8));
        final ImagePlus source = new ImagePlus("channel-selection-roi-probe", stack);
        source.setDimensions(2, 1, 1);
        source.setOpenAsHyperStack(true);
        source.setRoi(new Roi(2, 1, 4, 3));
        try {
            final ImagePlus selected = AnalysisWorkflow.channel(source, 1);
            require(selected.getWidth() == 10 && selected.getHeight() == 8,
                "Pre-existing area ROI cropped selected analysis channel");
            selected.close();
        } finally {
            source.close();
        }
    }

    private static void verifySelectedChannels(
        final int channelCount,
        final int muscleChannel,
        final int nerveChannel,
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

            final ImagePlus muscle = AnalysisWorkflow.channel(source, muscleChannel);
            final ImagePlus nerve = AnalysisWorkflow.channel(source, nerveChannel);
            source.close();
            source = null;

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
            require(muscle.getProcessor().getColorModel().getRGB(255) == 0xffffffff &&
                    nerve.getProcessor().getColorModel().getRGB(255) == 0xffffffff,
                "Selected channel is not grayscale");
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
            ImagePlus image = ANMJMorphCommand.load(input);
            final ImagePlus normalized = ANMJMorphCommand.normalize(image, false);
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
            workflow.analyze(image, input, 1, 2);
            require(Double.compare(ij.Prefs.get("brush.width", -1), 100.0) == 0,
                "Paintbrush width was not initialized to 100 pixels");
            require(!ij.Prefs.get("brush.overlay", false),
                "Paintbrush must edit image pixels, not an overlay");
        } finally {
            closeImagesCreatedAfter(existing);
        }
    }

    private static void createRectangularFixture(final Path reference, final Path output) {
        final Set<Integer> existing = imageIds();
        try {
            final ImagePlus source = ANMJMorphCommand.load(reference);
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
        @Override
        public void review(final ImagePlus subject, final String message) {
            if (message.startsWith("2/7 Threshold Nerve terminal.")) {
                require(Toolbar.getPlugInTool() instanceof BrushTool, "Screen 2 paintbrush is not selected");
                applyDefaultThreshold(subject, "Screen 2");
                return;
            }
            if (message.startsWith("3/7 Threshold Muscle Endplate.")) {
                require(Toolbar.getPlugInTool() instanceof BrushTool, "Screen 3 paintbrush is not selected");
                applyDefaultThreshold(subject, "Screen 3");
                return;
            }
            if (message.startsWith("Screen 4/7 Measuring Axon Width.")) {
                require(Toolbar.getToolId() == Toolbar.LINE, "Screen 4 line tool is not selected");
                synthesizeAxonWidths(subject);
                return;
            }
            if (message.startsWith("Screen 5/7 Erase Axon")) {
                require(Toolbar.getPlugInTool() instanceof BrushTool, "Screen 5 paintbrush is not selected");
                return;
            }
            if (message.startsWith("7/7 Congratulations-")) {
                return;
            }
            if (message.startsWith("WARNING: The image has a different scale on the X and Y axes.")) {
                return;
            }
            throw new IllegalStateException("Unexpected review prompt in deterministic direct runtime: " + message);
        }

        @Override
        public boolean confirmSegmentation(final String message) {
            require(message.startsWith("Screen 6/7 Check segmented image."),
                "Unexpected segmentation confirmation message");
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

    private static void validateCase(
        final Path caseDir,
        final String dimensions,
        final String inputName,
        final int runs,
        final boolean anisotropic
    ) throws IOException {
        final Path csv = caseDir.resolve("input/raw_data_table.csv");
        final byte[] bytes = Files.readAllBytes(csv);
        require(bytes.length > 0 && bytes[bytes.length - 1] == '\n', "CSV must end with newline: " + csv);
        final String text = new String(bytes, StandardCharsets.UTF_8);
        require(!text.toLowerCase().contains("nan"), "CSV contains NaN: " + csv);
        final List<String> lines = Files.readAllLines(csv, StandardCharsets.UTF_8);
        require(lines.size() == runs + 2, "Unexpected CSV row count: " + csv);
        require(HEADER_1.equals(lines.get(0)) && HEADER_2.equals(lines.get(1)), "CSV headers changed");

        for (int i = 0; i < runs; i++) {
            final int spreadsheetRow = i + 3;
            final List<String> row = parseCsv(lines.get(i + 2));
            require(row.size() == 29, "CSV data row must have 29 columns");
            require(dimensions.equals(row.get(0)), "Image dimensions changed");
            require(inputName.equals(row.get(2)), "Input name changed");
            final String threshold = inputName.contains("rect_")
                ? "Nerve C2:Default[85.00000000-255.00000000]/Muscle C1:Default[62.00000000-255.00000000]"
                : "Nerve C2:Default[88.00000000-255.00000000]/Muscle C1:Default[62.00000000-255.00000000]";
            require(threshold.equals(row.get(3)), "Threshold automation changed");
            require(("=J" + spreadsheetRow).equals(row.get(12)), "Terminal-branch formula changed");
            require(("=O" + spreadsheetRow + "/M" + spreadsheetRow).equals(row.get(15)),
                "Average-branch formula changed");
            require(("=S" + spreadsheetRow + "-X" + spreadsheetRow).equals(row.get(24)),
                "Synaptic-contact formula changed");
            require(("=(S" + spreadsheetRow + "-X" + spreadsheetRow + ")/S" + spreadsheetRow + "*100")
                .equals(row.get(25)), "Overlap formula changed");
            final double achrArea = number(row, 18);
            final double unoccupied = number(row, 23);
            require(unoccupied >= 0 && unoccupied <= achrArea, "Unoccupied AChR area is physically impossible");
            if (!inputName.contains("rect_")) validateOracle(row, anisotropic);
        }

        final String stem = inputName.substring(0, inputName.lastIndexOf('.'));
        final Path cleaned = caseDir.resolve("input/cleaned_images");
        for (String prefix : new String[] {"axon_terminal", "muscle_endplate"}) {
            final Path output = cleaned.resolve(prefix + stem + ".tif");
            require(Files.isRegularFile(output) && Files.size(output) > 0, "Missing cleaned image: " + output);
        }
    }

    private static void validateOracle(final List<String> row, final boolean anisotropic) {
        if (anisotropic) {
            exact(row, 1, "67.47557148 x 134.95114296microns");
            near(row, 5, 1.31788226, 0.000001);
            near(row, 6, 1017.62733461, 0.0001);
            near(row, 7, 905.15779629, 0.0001);
            near(row, 14, 527.80095379, 0.0001);
            near(row, 17, 934.91665430, 0.0001);
            near(row, 18, 865.80159922, 0.0001);
            near(row, 19, 66.75369588, 0.0001);
            near(row, 20, 168.70412322, 0.0001);
            near(row, 21, 1423.45772254, 0.0001);
            near(row, 23, 474.28906864, 0.0001);
        } else {
            exact(row, 1, "67.47557148 x 67.47557148microns");
            near(row, 5, 1.31788226, 0.000001);
            near(row, 6, 635.50525379, 0.0001);
            near(row, 7, 452.57889814, 0.0001);
            near(row, 14, 331.89935592, 0.0001);
            near(row, 17, 586.75552449, 0.0001);
            near(row, 18, 432.90079961, 0.0001);
            near(row, 19, 33.58609418, 0.0001);
            near(row, 20, 107.30171646, 0.0001);
            near(row, 21, 711.72886127, 0.0001);
            near(row, 23, 237.14453432, 0.0001);
        }
        near(row, 8, 32, 0);
        near(row, 9, 99, 0);
        near(row, 10, 47, 0);
        near(row, 11, 2, 0);
        near(row, 13, 49, 0);
        near(row, 26, 3, 0);
    }

    private static void exact(final List<String> row, final int column, final String expected) {
        require(expected.equals(row.get(column)), "Column " + column + " changed: " + row.get(column));
    }

    private static void near(final List<String> row, final int column, final double expected, final double tolerance) {
        final double actual = number(row, column);
        require(Math.abs(actual - expected) <= tolerance,
            "Column " + column + " changed: " + actual + " expected " + expected);
    }

    private static double number(final List<String> row, final int column) {
        return Double.parseDouble(row.get(column));
    }

    private static List<String> parseCsv(final String line) {
        final List<String> fields = new ArrayList<>();
        final StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            final char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == ',' && !quoted) {
                fields.add(field.toString());
                field.setLength(0);
            } else {
                field.append(c);
            }
        }
        require(!quoted, "Unterminated quoted CSV field");
        fields.add(field.toString());
        return fields;
    }

    private static String sha256(final Path path) throws IOException {
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path));
            final StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) hex.append(String.format("%02x", value));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
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

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }
}
