package io.github.alzeiby.anmjmorphplus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class AnalysisOutputPaths {
    final Path csv;
    final Path axon;
    final Path endplate;
    final Path endplateIntermediate;

    private AnalysisOutputPaths(
        final Path csv,
        final Path axon,
        final Path endplate,
        final Path endplateIntermediate
    ) {
        this.csv = csv;
        this.axon = axon;
        this.endplate = endplate;
        this.endplateIntermediate = endplateIntermediate;
    }

    static AnalysisOutputPaths forInput(final Path input) {
        final Path absolute = input.toAbsolutePath().normalize();
        final Path parent = absolute.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("Input image has no parent directory: " + input);
        }
        final Path cleaned = parent.resolve("cleaned_images");
        final String name = absolute.getFileName().toString();
        final int dot = name.lastIndexOf('.');
        final String stem = dot > 0 ? name.substring(0, dot) : name;
        return new AnalysisOutputPaths(
            parent.resolve("raw_data_table.csv"),
            cleaned.resolve("axon_terminal" + stem + ".tif"),
            cleaned.resolve("muscle_endplate" + stem + ".tif"),
            cleaned.resolve("muscle_intermediate_endplate" + stem + ".tif")
        );
    }

    void ensureCleanedDirectory() {
        try {
            Files.createDirectories(axon.getParent());
        } catch (IOException e) {
            throw new IllegalStateException("Could not create cleaned_images directory: " + axon.getParent(), e);
        }
    }

    Path[] cleanedOutputs() {
        return new Path[] {axon, endplate, endplateIntermediate};
    }
}
