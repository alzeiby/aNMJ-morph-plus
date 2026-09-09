package io.github.alzeiby.anmjmorphplus;

import ij.ImagePlus;
import ij.measure.Calibration;
import ij.process.ImageProcessor;

import java.util.ArrayDeque;

/** XY calibration for the legacy skeleton-pixel-count branch-length estimator. */
final class AnisotropicSkeletonCalibration {

    private AnisotropicSkeletonCalibration() {
    }

    static double effectivePixelSize(final ImagePlus skeleton) {
        final Calibration calibration = skeleton.getCalibration();
        final double pixelWidth = calibration.pixelWidth;
        final double pixelHeight = calibration.pixelHeight;

        if (!(pixelWidth > 0.0) || !(pixelHeight > 0.0)) {
            throw new IllegalArgumentException("Pixel width and height must be positive");
        }
        if (Double.compare(pixelWidth, pixelHeight) == 0) {
            return pixelWidth;
        }

        final ImageProcessor pixels = skeleton.getProcessor();
        final int width = pixels.getWidth();
        final int height = pixels.getHeight();
        final boolean[] visited = new boolean[pixels.getPixelCount()];
        final ArrayDeque<Integer> queue = new ArrayDeque<>();
        long totalForeground = 0;
        double totalCalibratedLegacyLength = 0.0;

        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final int start = y * width + x;
                if (visited[start] || !foreground(pixels, x, y)) {
                    continue;
                }

                visited[start] = true;
                queue.addLast(start);
                long componentPixels = 0;
                long horizontal = 0;
                long vertical = 0;
                long diagonal = 0;

                while (!queue.isEmpty()) {
                    final int index = queue.removeFirst();
                    final int componentX = index % width;
                    final int componentY = index / width;
                    componentPixels++;

                    if (componentX + 1 < width && foreground(pixels, componentX + 1, componentY)) {
                        horizontal++;
                    }
                    if (componentY + 1 < height && foreground(pixels, componentX, componentY + 1)) {
                        vertical++;
                    }
                    if (componentY + 1 < height) {
                        final boolean down = foreground(pixels, componentX, componentY + 1);
                        final boolean right = componentX + 1 < width
                            && foreground(pixels, componentX + 1, componentY);
                        final boolean left = componentX > 0
                            && foreground(pixels, componentX - 1, componentY);
                        if (componentX + 1 < width
                            && foreground(pixels, componentX + 1, componentY + 1)
                            && !right
                            && !down) {
                            diagonal++;
                        }
                        if (componentX > 0
                            && foreground(pixels, componentX - 1, componentY + 1)
                            && !left
                            && !down) {
                            diagonal++;
                        }
                    }

                    for (int dy = -1; dy <= 1; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            if (dx == 0 && dy == 0) {
                                continue;
                            }
                            final int neighborX = componentX + dx;
                            final int neighborY = componentY + dy;
                            if (neighborX < 0 || neighborX >= width || neighborY < 0 || neighborY >= height) {
                                continue;
                            }
                            final int neighbor = neighborY * width + neighborX;
                            if (!visited[neighbor] && foreground(pixels, neighborX, neighborY)) {
                                visited[neighbor] = true;
                                queue.addLast(neighbor);
                            }
                        }
                    }
                }

                totalForeground += componentPixels;
                totalCalibratedLegacyLength += componentPixels * componentScale(
                    horizontal,
                    vertical,
                    diagonal,
                    pixelWidth,
                    pixelHeight
                );
            }
        }

        if (totalForeground == 0) {
            return Math.sqrt(pixelWidth * pixelHeight);
        }
        return totalCalibratedLegacyLength / totalForeground;
    }

    private static double componentScale(
        final long horizontal,
        final long vertical,
        final long diagonal,
        final double pixelWidth,
        final double pixelHeight
    ) {
        final double unitLength = horizontal + vertical + diagonal * Math.sqrt(2.0);

        if (!(unitLength > 0.0)) {
            // An isolated skeleton pixel has no orientation to calibrate. The geometric mean
            // is symmetric in X/Y and collapses to the legacy scale for isotropic pixels.
            return Math.sqrt(pixelWidth * pixelHeight);
        }
        final double calibratedLength = horizontal * pixelWidth
            + vertical * pixelHeight
            + diagonal * Math.hypot(pixelWidth, pixelHeight);
        return calibratedLength / unitLength;
    }

    private static boolean foreground(final ImageProcessor pixels, final int x, final int y) {
        return pixels.get(x, y) != 0;
    }
}
