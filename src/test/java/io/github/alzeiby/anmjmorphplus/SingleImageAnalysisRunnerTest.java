package io.github.alzeiby.anmjmorphplus;

import ij.ImagePlus;
import ij.io.FileInfo;
import ij.process.ByteProcessor;
import org.junit.Test;

import java.nio.file.Path;

import static org.junit.Assert.assertEquals;

public class SingleImageAnalysisRunnerTest {

    @Test
    public void currentImageUsesCurrentTitleWithOriginalDirectory() {
        final ImagePlus image = new ImagePlus("renamed-current-title.tif", new ByteProcessor(4, 4));
        final FileInfo info = new FileInfo();
        info.directory = Path.of("analysis", "source").toString() + java.io.File.separator;
        info.fileName = "original-on-disk-name.tif";
        image.setFileInfo(info);

        final Path actual = SingleImageAnalysisRunner.pathForCurrentImage(image);

        assertEquals(Path.of(info.directory).resolve("renamed-current-title.tif"), actual);
    }
}
