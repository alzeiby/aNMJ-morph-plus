package io.github.alzeiby.anmjmorphplus;

import ij.ImagePlus;

import java.util.Objects;

final class ImageShape {

    private final int width;
    private final int height;
    private final int channels;
    private final int slices;
    private final int frames;
    private final int bitDepth;

    ImageShape(
        final int width,
        final int height,
        final int channels,
        final int slices,
        final int frames,
        final int bitDepth
    ) {
        this.width = width;
        this.height = height;
        this.channels = channels;
        this.slices = slices;
        this.frames = frames;
        this.bitDepth = bitDepth;
    }

    static ImageShape from(final ImagePlus image) {
        Objects.requireNonNull(image, "image");
        return new ImageShape(
            image.getWidth(),
            image.getHeight(),
            image.getNChannels(),
            image.getNSlices(),
            image.getNFrames(),
            image.getBitDepth()
        );
    }

    int width() {
        return width;
    }

    int height() {
        return height;
    }

    int channels() {
        return channels;
    }

    int slices() {
        return slices;
    }

    int frames() {
        return frames;
    }

    int bitDepth() {
        return bitDepth;
    }

    boolean isSingleSliceRgb() {
        return channels == 1 && slices == 1 && bitDepth == 24;
    }
}
