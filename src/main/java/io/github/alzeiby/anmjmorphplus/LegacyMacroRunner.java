package io.github.alzeiby.anmjmorphplus;

import ij.IJ;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

final class LegacyMacroRunner implements WorkflowRunner {

    static final String MACRO_RESOURCE = "/legacy/aNMJ-morph macro.txt";

    @Override
    public void run() {
        IJ.runMacro(loadMacro());
    }

    void run(final String argument) {
        IJ.runMacro(loadMacro(), argument == null ? "" : argument);
    }

    static String loadMacro() {
        try (InputStream stream = LegacyMacroRunner.class.getResourceAsStream(MACRO_RESOURCE)) {
            if (stream == null) {
                throw new IllegalStateException("Packaged aNMJ-morph reference macro is missing");
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("Could not read packaged aNMJ-morph reference macro", e);
        }
    }
}
