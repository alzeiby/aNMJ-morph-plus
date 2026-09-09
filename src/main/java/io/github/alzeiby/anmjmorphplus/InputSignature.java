package io.github.alzeiby.anmjmorphplus;

import java.util.Objects;

final class InputSignature {

    private final String value;

    private InputSignature(final String value) {
        this.value = value;
    }

    static InputSignature of(
        final SupportedImageFormat format,
        final ImageShape shape,
        final InputNormalization normalization,
        final String interpretation
    ) {
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(shape, "shape");
        Objects.requireNonNull(normalization, "normalization");
        final String suffix = interpretation == null ? "" : "|interpretation=" + interpretation;
        return new InputSignature(
            "format=" + format.name() +
            "|w=" + shape.width() +
            "|h=" + shape.height() +
            "|c=" + shape.channels() +
            "|z=" + shape.slices() +
            "|t=" + shape.frames() +
            "|bit=" + shape.bitDepth() +
            "|normalization=" + normalization.name() + suffix
        );
    }

    String value() {
        return value;
    }

    @Override
    public boolean equals(final Object other) {
        return other instanceof InputSignature && value.equals(((InputSignature) other).value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    @Override
    public String toString() {
        return value;
    }
}
