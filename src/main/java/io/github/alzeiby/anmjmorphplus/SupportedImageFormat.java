package io.github.alzeiby.anmjmorphplus;

import java.util.Locale;
import java.util.Optional;

enum SupportedImageFormat {

    TIFF(true, ".tif", ".tiff"),
    LSM(false, ".lsm"),
    ND2(false, ".nd2"),
    CZI(false, ".czi"),
    LIF(false, ".lif"),
    PNG(false, ".png"),
    JPEG(false, ".jpg", ".jpeg"),
    BMP(false, ".bmp");

    private final boolean nativeImageJ;
    private final String[] extensions;

    SupportedImageFormat(final boolean nativeImageJ, final String... extensions) {
        this.nativeImageJ = nativeImageJ;
        this.extensions = extensions;
    }

    boolean usesNativeImageJ() {
        return nativeImageJ;
    }

    static Optional<SupportedImageFormat> fromName(final String name) {
        if (name == null) {
            return Optional.empty();
        }
        final String lowerName = name.toLowerCase(Locale.ROOT);
        for (SupportedImageFormat format : values()) {
            for (String extension : format.extensions) {
                if (lowerName.endsWith(extension)) {
                    return Optional.of(format);
                }
            }
        }
        return Optional.empty();
    }
}
