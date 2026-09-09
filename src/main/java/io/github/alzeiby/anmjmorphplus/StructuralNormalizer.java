package io.github.alzeiby.anmjmorphplus;

import ij.ImagePlus;
import ij.plugin.ZProjector;
import ij.plugin.CompositeConverter;

import java.util.Objects;

final class StructuralNormalizer {

    static InputNormalization normalizationFor(final ImagePlus image) {
        if (image.getNFrames() > 1) return InputNormalization.REJECT_TIME_SERIES;
        if (image.getNChannels() == 1 && image.getNSlices() == 1 && image.getBitDepth() == 24) {
            return InputNormalization.RGB_TO_CHANNELS;
        }
        if (image.getNChannels() == 1 && image.getNSlices() == 2) {
            return InputNormalization.CHOOSE_TWO_PLANE_INTERPRETATION;
        }
        return image.getNSlices() > 1 ? InputNormalization.MAX_PROJECT_Z : InputNormalization.USE_AS_IS;
    }

    ImagePlus normalize(final ImagePlus image, final TwoPlaneInterpretation twoPlaneInterpretation) {
        Objects.requireNonNull(image, "image");
        final InputNormalization normalization = normalizationFor(image);
        switch (normalization) {
            case USE_AS_IS:
                return image;
            case RGB_TO_CHANNELS:
                return CompositeConverter.makeComposite(image);
            case CHOOSE_TWO_PLANE_INTERPRETATION:
                if (twoPlaneInterpretation == null) {
                    throw new IllegalArgumentException("Two-plane interpretation is required");
                }
                if (twoPlaneInterpretation == TwoPlaneInterpretation.CHANNELS) {
                    image.setDimensions(2, 1, 1);
                    image.setOpenAsHyperStack(true);
                    return image;
                }
                return maxProjectZ(image);
            case MAX_PROJECT_Z:
                return maxProjectZ(image);
            case REJECT_TIME_SERIES:
                throw new IllegalArgumentException(InputWorkflowRunner.TIME_SERIES_ERROR);
            default:
                throw new IllegalStateException("Unhandled normalization: " + normalization);
        }
    }

    private static ImagePlus maxProjectZ(final ImagePlus image) {
        if (image.getNSlices() <= 1) {
            return image;
        }
        final ImagePlus projection = ZProjector.run(image, "max");
        if (projection == null) {
            throw new IllegalStateException("ImageJ could not maximum-project the Z stack");
        }
        projection.setTitle(image.getTitle());
        projection.setFileInfo(image.getOriginalFileInfo());
        final Object info = image.getProperty("Info");
        if (info != null) {
            projection.setProperty("Info", info);
        }
        return projection;
    }

}
