package io.github.alzeiby.anmjmorphplus;

import ij.ImagePlus;
import ij.plugin.ZProjector;
import ij.process.ColorProcessor;
import ij.plugin.CompositeConverter;

import java.util.Objects;

final class StructuralNormalizer {

    ImagePlus normalize(final ImagePlus image, final TwoPlaneInterpretation twoPlaneInterpretation) {
        Objects.requireNonNull(image, "image");
        final InputNormalization normalization = InputPolicy.normalizationFor(ImageShape.from(image));
        switch (normalization) {
            case USE_AS_IS:
                return image;
            case RGB_TO_CHANNELS:
                return rgbToChannels(image);
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

    private static ImagePlus rgbToChannels(final ImagePlus image) {
        if (!(image.getProcessor() instanceof ColorProcessor)) {
            throw new IllegalArgumentException("Expected an ImageJ RGB image");
        }
        final ImagePlus composite = CompositeConverter.makeComposite(image);
        if (composite == null) {
            throw new IllegalStateException("ImageJ could not convert RGB image to a composite");
        }
        return composite;
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
        projection.setCalibration(image.getCalibration());
        projection.setFileInfo(image.getOriginalFileInfo());
        final Object info = image.getProperty("Info");
        if (info != null) {
            projection.setProperty("Info", info);
        }
        return projection;
    }

}
