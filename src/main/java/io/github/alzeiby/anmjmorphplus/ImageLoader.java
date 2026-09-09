package io.github.alzeiby.anmjmorphplus;

import ij.IJ;
import ij.ImagePlus;
import loci.formats.FormatException;
import loci.plugins.BF;

import java.io.IOException;
import java.nio.file.Path;

final class ImageLoader {

    ImagePlus load(final Path path) {
        final String fileName = path.getFileName().toString();
        final SupportedImageFormat format = SupportedImageFormat.fromName(fileName)
            .orElseThrow(() -> new IllegalArgumentException("Unsupported image format: " + fileName));
        final String absolute = path.toAbsolutePath().normalize().toString();

        ImagePlus image;
        if (format.usesNativeImageJ()) {
            image = IJ.openImage(absolute);
        } else {
            try {
                final ImagePlus[] images = BF.openImagePlus(absolute);
                if (images == null || images.length != 1) {
                    if (images != null) for (ImagePlus opened : images) if (opened != null) opened.close();
                    throw new IllegalArgumentException("Bio-Formats requires exactly one image: " + fileName);
                }
                image = images[0];
            } catch (FormatException | IOException e) {
                throw new IllegalStateException("Could not open image: " + fileName, e);
            }
        }
        if (image == null) throw new IllegalStateException("Could not open image: " + fileName);
        image.setTitle(fileName);
        return image;
    }
}
