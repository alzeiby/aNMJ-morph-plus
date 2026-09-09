package io.github.alzeiby.anmjmorphplus;

import org.junit.Test;
import org.scijava.plugin.Plugin;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

public class ANMJMorphCommandTest {

    @Test
    public void commandUsesRequestedFijiMenuPath() {
        final Plugin plugin = ANMJMorphCommand.class.getAnnotation(Plugin.class);
        assertEquals("Analyze>Tools>aNMJ-morph+", plugin.menuPath());
    }

    @Test
    public void commandDelegatesToCurrentWorkflowRunner() {
        final AtomicBoolean ran = new AtomicBoolean(false);
        final ANMJMorphCommand command = new ANMJMorphCommand(() -> ran.set(true));
        command.run();
        assertTrue(ran.get());
    }

    @Test
    public void canonicalMacroIsPackagedAsMigrationReference() {
        final String macro = LegacyMacroRunner.loadMacro();
        assertTrue(macro.contains("function processOpenImage(fileName)"));
        assertTrue(macro.contains("totalLengthOfBranches = (imageWidth * imageHeight - counts0) * pixelSizeX;"));
        assertTrue(macro.contains("File.append(output, outputFilename);"));
    }

    @Test
    public void scijavaPluginMetadataIsGenerated() throws IOException {
        try (InputStream stream = ANMJMorphCommand.class.getResourceAsStream("/META-INF/json/org.scijava.plugin.Plugin")) {
            assertNotNull(stream);
            final String metadata = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(metadata.contains(ANMJMorphCommand.class.getName()));
            assertTrue(metadata.contains("Analyze>Tools>aNMJ-morph+"));
        }
    }
}
