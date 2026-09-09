package io.github.alzeiby.anmjmorphplus;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class AnalysisOutputPaths {
    final Path csv;
    final Path axon;
    final Path endplate;
    final Path endplateIntermediate;

    private AnalysisOutputPaths(final Path parent, final String stem) {
        final Path cleaned = parent.resolve("cleaned_images");
        csv = parent.resolve("raw_data_table.csv");
        axon = cleaned.resolve("axon_terminal" + stem + ".tif");
        endplate = cleaned.resolve("muscle_endplate" + stem + ".tif");
        endplateIntermediate = cleaned.resolve("muscle_intermediate_endplate" + stem + ".tif");
    }

    static AnalysisOutputPaths forInput(final Path input) {
        final Path absolute = input.toAbsolutePath().normalize();
        final Path parent = absolute.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("Input image has no parent directory: " + input);
        }
        final String name = absolute.getFileName().toString();
        final int dot = name.lastIndexOf('.');
        final String stem = dot > 0 ? name.substring(0, dot) : name;
        return new AnalysisOutputPaths(parent, stem);
    }

    void ensureCleanedDirectory() {
        try {
            Files.createDirectories(axon.getParent());
        } catch (IOException e) {
            throw new IllegalStateException("Could not create cleaned_images directory: " + axon.getParent(), e);
        }
    }

}
