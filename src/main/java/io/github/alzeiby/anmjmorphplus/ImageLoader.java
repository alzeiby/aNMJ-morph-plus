package io.github.alzeiby.anmjmorphplus;

import ij.IJ;
import ij.ImagePlus;
import loci.formats.FormatException;
import loci.formats.ImageReader;
import loci.plugins.BF;
import loci.plugins.in.ImporterOptions;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Function;

final class ImageLoader {

    @FunctionalInterface
    interface SeriesCounter {
        int count(String path) throws FormatException, IOException;
    }

    @FunctionalInterface
    interface BioFormatsOpener {
        ImagePlus[] open(ImporterOptions options) throws FormatException, IOException;
    }

    private final Function<String, ImagePlus> nativeOpener;
    private final SeriesCounter seriesCounter;
    private final BioFormatsOpener bioFormatsOpener;

    ImageLoader() {
        this(IJ::openImage, ImageLoader::countSeries, BF::openImagePlus);
    }

    ImageLoader(
        final Function<String, ImagePlus> nativeOpener,
        final SeriesCounter seriesCounter,
        final BioFormatsOpener bioFormatsOpener
    ) {
        this.nativeOpener = Objects.requireNonNull(nativeOpener, "nativeOpener");
        this.seriesCounter = Objects.requireNonNull(seriesCounter, "seriesCounter");
        this.bioFormatsOpener = Objects.requireNonNull(bioFormatsOpener, "bioFormatsOpener");
    }

    ImagePlus load(final Path path) {
        Objects.requireNonNull(path, "path");
        final String fileName = path.getFileName() == null ? path.toString() : path.getFileName().toString();
        final SupportedImageFormat format = SupportedImageFormat.fromName(fileName)
            .orElseThrow(() -> new ImageLoadingException("Unsupported image format: " + fileName));
        final String absolutePath = path.toAbsolutePath().normalize().toString();

        final ImagePlus image = format.usesNativeImageJ()
            ? openNative(absolutePath, fileName)
            : openWithBioFormats(absolutePath, fileName);
        image.setTitle(fileName);
        return image;
    }

    private ImagePlus openNative(final String absolutePath, final String fileName) {
        final ImagePlus image = nativeOpener.apply(absolutePath);
        if (image == null) {
            throw new ImageLoadingException("Could not open image: " + fileName);
        }
        return image;
    }

    private ImagePlus openWithBioFormats(final String absolutePath, final String fileName) {
        try {
            final int seriesCount = seriesCounter.count(absolutePath);
            if (seriesCount != 1) {
                throw new ImageLoadingException(
                    "Bio-Formats found " + seriesCount + " image series in " + fileName +
                    ". aNMJ-morph+ requires a single-series input."
                );
            }

            final ImporterOptions options = new ImporterOptions();
            options.setId(absolutePath);
            options.setQuiet(true);

            final ImagePlus[] images = bioFormatsOpener.open(options);
            if (images == null || images.length != 1 || images[0] == null) {
                closeImages(images);
                final int returned = images == null ? 0 : images.length;
                throw new ImageLoadingException(
                    "Bio-Formats returned " + returned + " images for " + fileName +
                    ". aNMJ-morph+ requires exactly one image."
                );
            }
            return images[0];
        } catch (FormatException | IOException e) {
            throw new ImageLoadingException("Could not open image with Bio-Formats: " + fileName, e);
        }
    }

    private static int countSeries(final String path) throws FormatException, IOException {
        final ImageReader reader = new ImageReader();
        try {
            reader.setId(path);
            return reader.getSeriesCount();
        } finally {
            reader.close();
        }
    }

    private static void closeImages(final ImagePlus[] images) {
        if (images == null) {
            return;
        }
        for (ImagePlus image : images) {
            if (image != null) {
                image.close();
            }
        }
    }
}
