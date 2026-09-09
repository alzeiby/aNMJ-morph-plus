package io.github.alzeiby.anmjmorphplus;

import ij.CompositeImage;
import ij.ImagePlus;
import ij.ImageStack;
import ij.io.FileInfo;
import ij.measure.Calibration;
import ij.plugin.ZProjector;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import ij.process.ImageProcessor;
import ij.process.ShortProcessor;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class StructuralNormalizerTest {

    private final StructuralNormalizer normalizer = new StructuralNormalizer();

    @Test
    public void noOpReturnsExactImageWithoutChangingStructureOrPixels() {
        final ImagePlus image = twoChannelImage("ordinary.tif", 7, 19);
        final ImageStack originalStack = image.getStack();

        final ImagePlus normalized = normalizer.normalize(image, null);

        assertSame(image, normalized);
        assertSame(originalStack, image.getStack());
        assertEquals(2, image.getNChannels());
        assertEquals(1, image.getNSlices());
        assertEquals(7, channelPixel(image, 1));
        assertEquals(19, channelPixel(image, 2));
    }

    @Test
    public void rgbBecomesRedGreenBlueChannelsWithExactByteValues() {
        final int[] pixels = {0xff112233, 0xfff405a6};
        final ImagePlus image = new ImagePlus("rgb.png", new ColorProcessor(2, 1, pixels));
        stampMetadata(image);

        final ImagePlus normalized = normalizer.normalize(image, null);

        assertNotSame(image, normalized);
        assertTrue(normalized instanceof CompositeImage);
        assertEquals(CompositeImage.COMPOSITE, ((CompositeImage) normalized).getMode());
        assertEquals(3, normalized.getNChannels());
        assertEquals(1, normalized.getNSlices());
        assertEquals(1, normalized.getNFrames());
        assertEquals(8, normalized.getBitDepth());
        assertEquals("Red", normalized.getStack().getSliceLabel(1));
        assertEquals("Green", normalized.getStack().getSliceLabel(2));
        assertEquals("Blue", normalized.getStack().getSliceLabel(3));
        assertEquals(0x11, normalized.getStack().getProcessor(1).get(0, 0));
        assertEquals(0xf4, normalized.getStack().getProcessor(1).get(1, 0));
        assertEquals(0x22, normalized.getStack().getProcessor(2).get(0, 0));
        assertEquals(0x05, normalized.getStack().getProcessor(2).get(1, 0));
        assertEquals(0x33, normalized.getStack().getProcessor(3).get(0, 0));
        assertEquals(0xa6, normalized.getStack().getProcessor(3).get(1, 0));
        assertDefaultRgbCompositeLuts((CompositeImage) normalized);
        assertMetadata(normalized, "rgb.png");
    }

    @Test
    public void ambiguousTwoPlaneChannelsReinterpretsPlaneOneThenPlaneTwoWithoutPixelChanges() {
        final ImagePlus image = oneChannelTwoPlaneImage("two-plane.tif", 13, 29);

        normalizer.normalize(image, TwoPlaneInterpretation.CHANNELS);

        assertEquals(2, image.getNChannels());
        assertEquals(1, image.getNSlices());
        assertEquals(13, channelPixel(image, 1));
        assertEquals(29, channelPixel(image, 2));
    }

    @Test
    public void ambiguousTwoPlaneZStackChoiceMaximumProjectsTheTwoPlanes() {
        final ImagePlus image = oneChannelTwoPlaneImage("two-plane.tif", 31, 17);

        final ImagePlus normalized = normalizer.normalize(image, TwoPlaneInterpretation.Z_STACK);

        assertNotSame(image, normalized);
        assertEquals(1, normalized.getNChannels());
        assertEquals(1, normalized.getNSlices());
        assertEquals(31, channelPixel(normalized, 1));
        assertEquals("two-plane.tif", normalized.getTitle());
    }

    @Test
    public void multichannelZProjectionTakesMaximumIndependentlyWithinEachChannel() {
        final ImageStack stack = new ImageStack(1, 1);
        addByteSlice(stack, "c1-z1", 1);
        addByteSlice(stack, "c2-z1", 10);
        addByteSlice(stack, "c1-z2", 5);
        addByteSlice(stack, "c2-z2", 2);
        addByteSlice(stack, "c1-z3", 3);
        addByteSlice(stack, "c2-z3", 20);
        final ImagePlus image = new ImagePlus("z-stack.lsm", stack);
        image.setDimensions(2, 3, 1);
        image.setOpenAsHyperStack(true);
        stampMetadata(image);

        final ImagePlus normalized = normalizer.normalize(image, null);

        assertNotSame(image, normalized);
        assertEquals(2, normalized.getNChannels());
        assertEquals(1, normalized.getNSlices());
        assertEquals(1, normalized.getNFrames());
        assertEquals(5, channelPixel(normalized, 1));
        assertEquals(20, channelPixel(normalized, 2));
        assertMetadata(normalized, "z-stack.lsm");
    }

    @Test
    public void structuralChangesPreserveTitleCalibrationAndSourceProvenance() {
        final ImagePlus image = oneChannelTwoPlaneImage("source file.tif", 4, 9);
        stampMetadata(image);

        normalizer.normalize(image, TwoPlaneInterpretation.CHANNELS);

        assertMetadata(image, "source file.tif");
    }

    @Test
    public void eightBitSingleChannelMaxMatchesImageJZProjector() {
        assertMatchesImageJMaxProjection(8, 1);
    }

    @Test
    public void sixteenBitMultichannelMaxMatchesImageJZProjector() {
        assertMatchesImageJMaxProjection(16, 2);
    }

    @Test
    public void thirtyTwoBitMultichannelMaxMatchesImageJZProjector() {
        assertMatchesImageJMaxProjection(32, 2);
    }

    @Test
    public void rgbZStackMaxMatchesImageJZProjectorComponentForComponent() {
        final ImagePlus actual = rgbZStack();
        final ImagePlus referenceSource = rgbZStack();
        final ImagePlus expected = ZProjector.run(referenceSource, "max");

        final ImagePlus normalized = normalizer.normalize(actual, null);

        assertNotSame(actual, normalized);
        assertEquals(1, normalized.getNChannels());
        assertEquals(1, normalized.getNSlices());
        assertEquals(1, normalized.getNFrames());
        assertEquals(expected.getProcessor().get(0, 0), normalized.getProcessor().get(0, 0));
        assertEquals(expected.getProcessor().get(1, 0), normalized.getProcessor().get(1, 0));
    }

    @Test
    public void thirtyTwoBitNaNThenFiniteMaxMatchesImageJZProjector() {
        assertFloatMaxMatchesImageJ(Float.NaN, 5.0f, 4.0f);
    }

    @Test
    public void thirtyTwoBitAllNaNMaxMatchesImageJZProjector() {
        assertFloatMaxMatchesImageJ(Float.NaN, Float.NaN, Float.NaN);
    }

    private static ImagePlus twoChannelImage(final String title, final int first, final int second) {
        final ImageStack stack = new ImageStack(1, 1);
        addByteSlice(stack, "c1", first);
        addByteSlice(stack, "c2", second);
        final ImagePlus image = new ImagePlus(title, stack);
        image.setDimensions(2, 1, 1);
        image.setOpenAsHyperStack(true);
        return image;
    }

    private static ImagePlus oneChannelTwoPlaneImage(final String title, final int first, final int second) {
        final ImageStack stack = new ImageStack(1, 1);
        addByteSlice(stack, "z1", first);
        addByteSlice(stack, "z2", second);
        final ImagePlus image = new ImagePlus(title, stack);
        image.setDimensions(1, 2, 1);
        image.setOpenAsHyperStack(true);
        return image;
    }

    private static void addByteSlice(final ImageStack stack, final String label, final int value) {
        stack.addSlice(label, new ByteProcessor(1, 1, new byte[] {(byte) value}, null));
    }

    private static int channelPixel(final ImagePlus image, final int channel) {
        return image.getStack().getProcessor(image.getStackIndex(channel, 1, 1)).get(0, 0);
    }

    private static void stampMetadata(final ImagePlus image) {
        final Calibration calibration = image.getCalibration();
        calibration.pixelWidth = 0.25;
        calibration.pixelHeight = 0.5;
        calibration.pixelDepth = 1.75;
        calibration.setUnit("microns");
        final FileInfo fileInfo = new FileInfo();
        fileInfo.fileName = "original-source.lsm";
        fileInfo.directory = "C:\\source data\\";
        image.setFileInfo(fileInfo);
        image.setProperty("Info", "provenance-metadata");
    }

    private static void assertMetadata(final ImagePlus image, final String expectedTitle) {
        assertEquals(expectedTitle, image.getTitle());
        assertEquals(0.25, image.getCalibration().pixelWidth, 0.0);
        assertEquals(0.5, image.getCalibration().pixelHeight, 0.0);
        assertEquals(1.75, image.getCalibration().pixelDepth, 0.0);
        assertEquals("microns", image.getCalibration().getUnit());
        assertEquals("original-source.lsm", image.getOriginalFileInfo().fileName);
        assertEquals("C:\\source data\\", image.getOriginalFileInfo().directory);
        assertEquals("provenance-metadata", image.getProperty("Info"));
    }

    private static void assertDefaultRgbCompositeLuts(final CompositeImage image) {
        assertEquals(255, image.getChannelLut(1).getRed(255));
        assertEquals(0, image.getChannelLut(1).getGreen(255));
        assertEquals(0, image.getChannelLut(1).getBlue(255));
        assertEquals(0, image.getChannelLut(2).getRed(255));
        assertEquals(255, image.getChannelLut(2).getGreen(255));
        assertEquals(0, image.getChannelLut(2).getBlue(255));
        assertEquals(0, image.getChannelLut(3).getRed(255));
        assertEquals(0, image.getChannelLut(3).getGreen(255));
        assertEquals(255, image.getChannelLut(3).getBlue(255));
    }

    private void assertMatchesImageJMaxProjection(final int bitDepth, final int channels) {
        final ImagePlus actual = zStack(bitDepth, channels);
        final ImagePlus referenceSource = zStack(bitDepth, channels);
        final ImagePlus expected = ZProjector.run(referenceSource, "max");

        final ImagePlus normalized = normalizer.normalize(actual, null);

        assertEquals(expected.getNChannels(), normalized.getNChannels());
        assertEquals(expected.getNSlices(), normalized.getNSlices());
        assertEquals(expected.getNFrames(), normalized.getNFrames());
        assertEquals(expected.getStackSize(), normalized.getStackSize());
        for (int slice = 1; slice <= normalized.getStackSize(); slice++) {
            assertEquals(expected.getStack().getSliceLabel(slice), normalized.getStack().getSliceLabel(slice));
            final ImageProcessor expectedProcessor = expected.getStack().getProcessor(slice);
            final ImageProcessor actualProcessor = normalized.getStack().getProcessor(slice);
            for (int pixel = 0; pixel < actualProcessor.getPixelCount(); pixel++) {
                assertEquals(expectedProcessor.getf(pixel), actualProcessor.getf(pixel), 0.0f);
            }
        }
    }

    private static ImagePlus zStack(final int bitDepth, final int channels) {
        final int slices = 3;
        final ImageStack stack = new ImageStack(2, 1);
        for (int z = 0; z < slices; z++) {
            for (int channel = 0; channel < channels; channel++) {
                final float first = (channel + 1) * 100 + new float[] {4, 17, 9}[z];
                final float second = (channel + 1) * 100 + new float[] {21, 3, 15}[z];
                stack.addSlice("c" + (channel + 1) + "-z" + (z + 1), processor(bitDepth, first, second));
            }
        }
        final ImagePlus image = new ImagePlus("projection-source.tif", stack);
        image.setDimensions(channels, slices, 1);
        image.setOpenAsHyperStack(channels > 1);
        return image;
    }

    private static ImagePlus rgbZStack() {
        final ImageStack stack = new ImageStack(2, 1);
        stack.addSlice(new ColorProcessor(2, 1, new int[] {0xff102030, 0xfff01020}));
        stack.addSlice(new ColorProcessor(2, 1, new int[] {0xff405010, 0xff20e030}));
        stack.addSlice(new ColorProcessor(2, 1, new int[] {0xff206070, 0xff3040d0}));
        final ImagePlus image = new ImagePlus("rgb-z.tif", stack);
        image.setDimensions(1, 3, 1);
        image.setOpenAsHyperStack(true);
        return image;
    }

    private void assertFloatMaxMatchesImageJ(final float... values) {
        final ImagePlus actual = floatZStack(values);
        final ImagePlus expected = ZProjector.run(floatZStack(values), "max");

        final ImagePlus normalized = normalizer.normalize(actual, null);

        assertEquals(
            Float.floatToIntBits(expected.getProcessor().getf(0)),
            Float.floatToIntBits(normalized.getProcessor().getf(0))
        );
    }

    private static ImagePlus floatZStack(final float... values) {
        final ImageStack stack = new ImageStack(1, 1);
        for (float value : values) {
            stack.addSlice(new FloatProcessor(1, 1, new float[] {value}));
        }
        final ImagePlus image = new ImagePlus("float-z.tif", stack);
        image.setDimensions(1, values.length, 1);
        image.setOpenAsHyperStack(true);
        return image;
    }

    private static ImageProcessor processor(final int bitDepth, final float first, final float second) {
        if (bitDepth == 8) {
            return new ByteProcessor(2, 1, new byte[] {(byte) first, (byte) second}, null);
        }
        if (bitDepth == 16) {
            return new ShortProcessor(2, 1, new short[] {(short) first, (short) second}, null);
        }
        if (bitDepth == 32) {
            return new FloatProcessor(2, 1, new float[] {first + 0.25f, second + 0.5f});
        }
        throw new IllegalArgumentException("Unsupported test bit depth: " + bitDepth);
    }
}
