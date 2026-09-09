package io.github.alzeiby.anmjmorphplus;

import ij.IJ;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

final class CsvOutputWriter {

    private static final String COLUMN = ",";
    private static final String DECIMAL = ".";

    void append(final Path csv, final AnalysisResult result) {
        final int rowNumber = prepare(csv);
        final String clusters = result.imageAlright ? format(result.numberOfClusters) : "";
        final String pixels = result.imageWidth + " x " + result.imageHeight;
        final String metric = format(result.pixelSizeX * result.imageWidth) + " x " +
            format(result.pixelSizeY * result.imageHeight) + result.sizeUnit;

        final String output = quote(pixels) + COLUMN +
            quote(metric) + COLUMN +
            result.inputTitle + COLUMN +
            result.thresholdMethods + COLUMN +
            COLUMN +
            format(result.axonDiameter) + COLUMN +
            format(result.nerve.perimeter) + COLUMN +
            format(result.nerve.area) + COLUMN +
            format(result.counts0) + COLUMN +
            format(result.counts2) + COLUMN +
            format(result.counts4) + COLUMN +
            format(result.counts5) + COLUMN +
            quote("=J" + rowNumber) + COLUMN +
            quote("=(K" + rowNumber + "+L" + rowNumber + ")*0" + DECIMAL + "28") + COLUMN +
            format(result.totalLengthOfBranches) + COLUMN +
            quote("=O" + rowNumber + "/J" + rowNumber) + COLUMN +
            quote("=LOG10(M" + rowNumber + "*N" + rowNumber + "*O" + rowNumber + ")") + COLUMN +
            format(result.achr.perimeter) + COLUMN +
            format(result.achr.area) + COLUMN +
            format(result.endplate.feret) + COLUMN +
            format(result.endplate.perimeter) + COLUMN +
            format(result.endplate.area) + COLUMN +
            quote("=S" + rowNumber + "/V" + rowNumber + "*100") + COLUMN +
            format(result.unoccupied.area) + COLUMN +
            quote("=S" + rowNumber + "-X" + rowNumber) + COLUMN +
            quote("=(S" + rowNumber + "-X" + rowNumber + ")/S" + rowNumber + "*100") + COLUMN +
            clusters + COLUMN +
            quote("=IF(AA" + rowNumber + COLUMN + "S" + rowNumber + "/AA" + rowNumber + COLUMN + "\"\")") + COLUMN +
            quote("=IF(AA" + rowNumber + COLUMN + "1-1/AA" + rowNumber + COLUMN + "\"\")") + "\n";

        write(csv, output, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static int prepare(final Path csv) {
        try {
            if (!Files.exists(csv) || Files.size(csv) == 0) {
                write(csv, header1() + "\n" + header2() + "\n", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                return 3;
            }
            final byte[] contents = Files.readAllBytes(csv);
            if (contents.length > 0 && contents[contents.length - 1] != '\n') {
                write(csv, "\n", StandardOpenOption.APPEND);
            }
            return Files.readAllLines(csv).size() + 1;
        } catch (IOException e) {
            throw new IllegalStateException("Could not prepare CSV output: " + csv, e);
        }
    }

    private static String format(final double value) {
        return IJ.d2s(value, 8).replace('.', DECIMAL.charAt(0));
    }

    private static String quote(final String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static void write(final Path path, final String text, final StandardOpenOption... options) {
        try {
            Files.writeString(path, text, options);
        } catch (IOException e) {
            throw new IllegalStateException("Could not write CSV output: " + path, e);
        }
    }

    private static String header1() {
        return "IMAGE DETAILS (frame size)" + COLUMN +
            COLUMN +
            "NMJ" + COLUMN +
            "THRESHOLD" + COLUMN +
            "PRE-SYNAPTIC" + COLUMN +
            COLUMN + COLUMN + COLUMN +
            "Branch analysis" + COLUMN +
            COLUMN + COLUMN + COLUMN + COLUMN + COLUMN + COLUMN + COLUMN + COLUMN +
            "POST-SYNAPTIC";
    }

    private static String header2() {
        return quote("Number of pixels (eg, 512 x 512)") + COLUMN +
            quote("Metric (eg, 67.48 x 67.48um)") + COLUMN +
            quote("Ref number") + COLUMN +
            quote("(nerve terminal/motor endplate)") + COLUMN +
            quote("Number of Axonal Inputs") + COLUMN +
            quote("Axon Diameter (um)") + COLUMN +
            quote("Nerve Terminal Perimeter (um)") + COLUMN +
            quote("Nerve Terminal Area (um2)") + COLUMN +
            quote("Value 0 (background white pixels)") + COLUMN +
            quote("Value 2 (terminal pixels)") + COLUMN +
            quote("Value 4 (three-point branch pixels)") + COLUMN +
            quote("Value 5 (four-point branch pixels)") + COLUMN +
            quote(" Number of Terminal Branches") + COLUMN +
            quote("Number of Branch Points") + COLUMN +
            quote("Total Length of Branches (um)") + COLUMN +
            quote("Average Length of Branches (um)") + COLUMN +
            quote("\"Complexity\"") + COLUMN +
            quote("AChR Perimeter (um)") + COLUMN +
            quote("AChR Area (um2)") + COLUMN +
            quote("Endplate Diameter (um)") + COLUMN +
            quote("Endplate Perimeter (um)") + COLUMN +
            quote("Endplate Area (um2)") + COLUMN +
            quote("\"Compactness\" (%)") + COLUMN +
            quote("Unoccupied AChR Area (um2)") + COLUMN +
            quote("\"Area of Synaptic Contact\" (um2)") + COLUMN +
            quote("\"Overlap\" (%)") + COLUMN +
            quote("Number of AChR Clusters") + COLUMN +
            quote("Average Area of AChR Clusters (um2)") + COLUMN +
            quote("\"Fragmentation\"");
    }
}
