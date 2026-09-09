package io.github.alzeiby.anmjmorphplus;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CsvOutputWriterTest {

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    @Test
    public void writesExactLegacyHeadersAndTwentyNineColumnRow() throws Exception {
        final Path csv = temporaryFolder.newFile("raw_data_table.csv").toPath();
        Files.delete(csv);

        new CsvOutputWriter().append(csv, result(true, 1));

        final List<String> lines = Files.readAllLines(csv);
        assertEquals(3, lines.size());
        assertEquals(
            "IMAGE DETAILS (frame size),,NMJ,THRESHOLD,PRE-SYNAPTIC,,,,Branch analysis,,,,,,,,,POST-SYNAPTIC",
            lines.get(0)
        );
        assertEquals(
            "\"Number of pixels (eg, 512 x 512)\",\"Metric (eg, 67.48 x 67.48um)\",\"Ref number\",\"(nerve terminal/motor endplate)\",\"Number of Axonal Inputs\",\"Axon Diameter (um)\",\"Nerve Terminal Perimeter (um)\",\"Nerve Terminal Area (um2)\",\"Value 0 (background white pixels)\",\"Value 2 (terminal pixels)\",\"Value 4 (three-point branch pixels)\",\"Value 5 (four-point branch pixels)\",\" Number of Terminal Branches\",\"Number of Branch Points\",\"Total Length of Branches (um)\",\"Average Length of Branches (um)\",\"\"\"Complexity\"\"\",\"AChR Perimeter (um)\",\"AChR Area (um2)\",\"Endplate Diameter (um)\",\"Endplate Perimeter (um)\",\"Endplate Area (um2)\",\"\"\"Compactness\"\" (%)\",\"Unoccupied AChR Area (um2)\",\"\"\"Area of Synaptic Contact\"\" (um2)\",\"\"\"Overlap\"\" (%)\",\"Number of AChR Clusters\",\"Average Area of AChR Clusters (um2)\",\"\"\"Fragmentation\"\"\"",
            lines.get(1)
        );
        assertEquals(29, parseCsv(lines.get(2)).size());
        final List<String> fields = parseCsv(lines.get(2));
        assertEquals("=J3", fields.get(12));
        assertEquals("=(K3+L3)*0.28", fields.get(13));
        assertEquals("=O3/J3", fields.get(15));
        assertEquals("=LOG10(M3*N3*O3)", fields.get(16));
        assertEquals("=S3/V3*100", fields.get(22));
        assertEquals("=S3-X3", fields.get(24));
        assertEquals("=(S3-X3)/S3*100", fields.get(25));
        assertEquals("1.00000000", fields.get(26));
        assertEquals("=IF(AA3,S3/AA3,\"\")", fields.get(27));
        assertEquals("=IF(AA3,1-1/AA3,\"\")", fields.get(28));
    }

    @Test
    public void secondAppendUsesNextExcelRowAndUncheckedSegmentationBlanksCluster() throws Exception {
        final Path csv = temporaryFolder.newFile("append.csv").toPath();
        Files.delete(csv);
        final CsvOutputWriter writer = new CsvOutputWriter();
        writer.append(csv, result(true, 1));
        writer.append(csv, result(false, 99));

        final List<String> fields = parseCsv(Files.readAllLines(csv).get(3));
        assertEquals("=J4", fields.get(12));
        assertEquals("", fields.get(26));
        assertEquals("=IF(AA4,S4/AA4,\"\")", fields.get(27));
    }

    @Test
    public void repairsMissingTrailingNewlineBeforeAppend() throws Exception {
        final Path csv = temporaryFolder.newFile("repair.csv").toPath();
        Files.writeString(csv, "existing-row-without-newline");
        new CsvOutputWriter().append(csv, result(true, 1));
        final String text = Files.readString(csv);
        assertTrue(text.startsWith("existing-row-without-newline\n"));
    }

    private static AnalysisResult result(final boolean accepted, final double clusters) {
        return new AnalysisResult(
            512, 512, 0.1317882255, 0.1317882255, "microns", "NMJ_1.lsm",
            "Default/Default", 1.31788226, 259923, 99, 139, 23, 292.70164895,
            new AnalysisResult.Measurement(452.57889814, 635.50525379, Double.NaN),
            new AnalysisResult.Measurement(432.90079961, 586.75552449, Double.NaN),
            new AnalysisResult.Measurement(711.72886127, 107.30171646, 33.58609418),
            new AnalysisResult.Measurement(4315.80853971, Double.NaN, Double.NaN),
            accepted, clusters
        );
    }

    private static List<String> parseCsv(final String line) {
        final java.util.ArrayList<String> fields = new java.util.ArrayList<>();
        final StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            final char ch = line.charAt(i);
            if (ch == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (ch == ',' && !quoted) {
                fields.add(current.toString());
                current.setLength(0);
            } else {
                current.append(ch);
            }
        }
        fields.add(current.toString());
        return fields;
    }
}
