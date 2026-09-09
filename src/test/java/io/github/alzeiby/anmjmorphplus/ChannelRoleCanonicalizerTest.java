package io.github.alzeiby.anmjmorphplus;

import ij.CompositeImage;
import ij.ImagePlus;
import ij.ImageStack;
import ij.io.FileInfo;
import ij.plugin.ChannelArranger;
import ij.process.ByteProcessor;
import ij.process.FloatProcessor;
import ij.process.LUT;
import org.junit.Test;

import java.awt.Color;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotSame;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ChannelRoleCanonicalizerTest {

    @Test
    public void threeChannelChoiceThreeOneMatchesImageJAndDropsIgnoredChannel() {
        final ImagePlus actual = threeChannelByteImage("source.lsm", 11, 22, 33);
        stampMetadata(actual);
        final ImagePlus reference = threeChannelByteImage("source.lsm", 11, 22, 33);
        stampMetadata(reference);
        final ImagePlus expected = ChannelArranger.run(reference, new int[] {3, 1});
        expected.copyAttributes(reference);

        final ImagePlus canonical = SingleImageAnalysisRunner.canonicalize(actual, choice(3, 1));

        assertNotSame(actual, canonical);
        assertEquals(2, canonical.getNChannels());
        assertEquals(33, channelByte(canonical, 1));
        assertEquals(11, channelByte(canonical, 2));
        assertEquals(channelByte(expected, 1), channelByte(canonical, 1));
        assertEquals(channelByte(expected, 2), channelByte(canonical, 2));
        assertMetadata(canonical, "source.lsm");
    }

    @Test
    public void nonuniformThreeChannelPixelsMatchImageJExactly() {
        final ImageStack actualStack = new ImageStack(3, 1);
        actualStack.addSlice(new ByteProcessor(3, 1, new byte[] {11, 12, 13}, null));
        actualStack.addSlice(new ByteProcessor(3, 1, new byte[] {21, 22, 23}, null));
        actualStack.addSlice(new ByteProcessor(3, 1, new byte[] {31, 32, 33}, null));
        final ImagePlus actual = new ImagePlus("nonuniform.tif", actualStack);
        actual.setDimensions(3, 1, 1);
        actual.setOpenAsHyperStack(true);

        final ImageStack referenceStack = new ImageStack(3, 1);
        referenceStack.addSlice(new ByteProcessor(3, 1, new byte[] {11, 12, 13}, null));
        referenceStack.addSlice(new ByteProcessor(3, 1, new byte[] {21, 22, 23}, null));
        referenceStack.addSlice(new ByteProcessor(3, 1, new byte[] {31, 32, 33}, null));
        final ImagePlus reference = new ImagePlus("nonuniform.tif", referenceStack);
        reference.setDimensions(3, 1, 1);
        reference.setOpenAsHyperStack(true);
        final ImagePlus expected = ChannelArranger.run(reference, new int[] {3, 1});

        final ImagePlus canonical = SingleImageAnalysisRunner.canonicalize(actual, choice(3, 1));

        assertEquals(expected.getStackSize(), canonical.getStackSize());
        for (int slice = 1; slice <= canonical.getStackSize(); slice++) {
            for (int pixel = 0; pixel < canonical.getStack().getProcessor(slice).getPixelCount(); pixel++) {
                assertEquals(
                    expected.getStack().getProcessor(slice).get(pixel),
                    canonical.getStack().getProcessor(slice).get(pixel)
                );
            }
        }
    }

    @Test
    public void twoChannelSwapProducesCanonicalOneTwoOrder() {
        final ImagePlus image = twoChannelByteImage("swap.tif", 7, 19);

        final ImagePlus canonical = SingleImageAnalysisRunner.canonicalize(image, choice(2, 1));

        assertNotSame(image, canonical);
        assertEquals(2, canonical.getNChannels());
        assertEquals(19, channelByte(canonical, 1));
        assertEquals(7, channelByte(canonical, 2));
    }

    @Test
    public void alreadyCanonicalTwoChannelImageIsExactNoOp() {
        final ImagePlus image = twoChannelByteImage("ordinary.tif", 7, 19);

        final ImagePlus canonical = SingleImageAnalysisRunner.canonicalize(image, choice(1, 2));

        assertSame(image, canonical);
    }

    @Test
    public void compositeModeAndSelectedChannelLutsFollowImageJArrangement() {
        final ImagePlus base = threeChannelByteImage("composite.tif", 11, 22, 33);
        final CompositeImage composite = new CompositeImage(base, CompositeImage.COMPOSITE);
        composite.setChannelLut(LUT.createLutFromColor(Color.RED), 1);
        composite.setChannelLut(LUT.createLutFromColor(Color.GREEN), 2);
        composite.setChannelLut(LUT.createLutFromColor(Color.BLUE), 3);
        composite.setPosition(1, 1, 1);
        composite.setDisplayRange(10, 110);
        composite.setPosition(2, 1, 1);
        composite.setDisplayRange(20, 120);
        composite.setPosition(3, 1, 1);
        composite.setDisplayRange(30, 130);

        final ImagePlus canonical = SingleImageAnalysisRunner.canonicalize(composite, choice(3, 1));

        assertTrue(canonical instanceof CompositeImage);
        final CompositeImage arranged = (CompositeImage) canonical;
        assertEquals(CompositeImage.COMPOSITE, arranged.getMode());
        assertEquals(255, arranged.getChannelLut(1).getBlue(255));
        assertEquals(0, arranged.getChannelLut(1).getRed(255));
        assertEquals(255, arranged.getChannelLut(2).getRed(255));
        assertEquals(0, arranged.getChannelLut(2).getBlue(255));
        assertEquals(30.0, arranged.getChannelLut(1).min, 0.0);
        assertEquals(130.0, arranged.getChannelLut(1).max, 0.0);
        assertEquals(10.0, arranged.getChannelLut(2).min, 0.0);
        assertEquals(110.0, arranged.getChannelLut(2).max, 0.0);
    }

    @Test
    public void floatNaNInfinityAndPayloadBitsArePreservedExactly() {
        final float payloadNaN = Float.intBitsToFloat(0x7fc12345);
        final ImageStack stack = new ImageStack(3, 1);
        stack.addSlice(new FloatProcessor(3, 1, new float[] {Float.NEGATIVE_INFINITY, -0.0f, 17.5f}));
        stack.addSlice(new FloatProcessor(3, 1, new float[] {2.0f, 3.0f, 4.0f}));
        stack.addSlice(new FloatProcessor(3, 1, new float[] {payloadNaN, Float.POSITIVE_INFINITY, 0.0f}));
        final ImagePlus image = new ImagePlus("float.tif", stack);
        image.setDimensions(3, 1, 1);
        image.setOpenAsHyperStack(true);

        final ImagePlus canonical = SingleImageAnalysisRunner.canonicalize(image, choice(3, 1));

        assertRawFloatBits(canonical.getStack().getProcessor(1).getf(0), payloadNaN);
        assertRawFloatBits(canonical.getStack().getProcessor(1).getf(1), Float.POSITIVE_INFINITY);
        assertRawFloatBits(canonical.getStack().getProcessor(2).getf(0), Float.NEGATIVE_INFINITY);
        assertRawFloatBits(canonical.getStack().getProcessor(2).getf(1), -0.0f);
        assertRawFloatBits(canonical.getStack().getProcessor(2).getf(2), 17.5f);
    }

    @Test
    public void postZProjectionCanBeCanonicalizedWithoutChangingProjectedPixels() {
        final ImageStack stack = new ImageStack(1, 1);
        addByte(stack, 1); addByte(stack, 10); addByte(stack, 100);
        addByte(stack, 5); addByte(stack, 2); addByte(stack, 80);
        addByte(stack, 3); addByte(stack, 20); addByte(stack, 120);
        final ImagePlus z = new ImagePlus("z.lsm", stack);
        z.setDimensions(3, 3, 1);
        z.setOpenAsHyperStack(true);
        final ImagePlus projected = new StructuralNormalizer().normalize(z, null);

        final ImagePlus canonical = SingleImageAnalysisRunner.canonicalize(projected, choice(3, 1));

        assertEquals(2, canonical.getNChannels());
        assertEquals(1, canonical.getNSlices());
        assertEquals(120, channelByte(canonical, 1));
        assertEquals(5, channelByte(canonical, 2));
    }

    @Test
    public void transformedSourceIsClosedExactlyOnceAndReplacementHasNewIdentity() {
        final AtomicInteger closes = new AtomicInteger();
        final ImagePlus base = threeChannelByteImage("close.tif", 1, 2, 3);
        final ImagePlus source = new ImagePlus(base.getTitle(), base.getStack()) {
            @Override
            public void close() {
                closes.incrementAndGet();
                super.close();
            }
        };
        source.setDimensions(3, 1, 1);
        source.setOpenAsHyperStack(true);

        final ImagePlus canonical = SingleImageAnalysisRunner.canonicalize(source, choice(3, 1));

        assertNotSame(source, canonical);
        assertEquals(1, closes.get());
        assertTrue(source.getID() != canonical.getID());
    }

    @Test
    public void moreThanNineChannelsIsDeliberateNoOp() {
        final ImageStack stack = new ImageStack(1, 1);
        for (int channel = 1; channel <= 10; channel++) {
            addByte(stack, channel);
        }
        final ImagePlus image = new ImagePlus("ten.tif", stack);
        image.setDimensions(10, 1, 1);
        image.setOpenAsHyperStack(true);

        final ImagePlus canonical = SingleImageAnalysisRunner.canonicalize(image, choice(3, 1));

        assertSame(image, canonical);
        assertEquals(10, canonical.getNChannels());
    }

    private static BatchChoiceResolver.ChannelChoice choice(final int muscle, final int nerve) {
        return new BatchChoiceResolver.ChannelChoice(muscle, nerve);
    }

    private static ImagePlus threeChannelByteImage(
        final String title,
        final int first,
        final int second,
        final int third
    ) {
        final ImageStack stack = new ImageStack(1, 1);
        addByte(stack, first);
        addByte(stack, second);
        addByte(stack, third);
        final ImagePlus image = new ImagePlus(title, stack);
        image.setDimensions(3, 1, 1);
        image.setOpenAsHyperStack(true);
        return image;
    }

    private static ImagePlus twoChannelByteImage(final String title, final int first, final int second) {
        final ImageStack stack = new ImageStack(1, 1);
        addByte(stack, first);
        addByte(stack, second);
        final ImagePlus image = new ImagePlus(title, stack);
        image.setDimensions(2, 1, 1);
        image.setOpenAsHyperStack(true);
        return image;
    }

    private static void addByte(final ImageStack stack, final int value) {
        stack.addSlice(new ByteProcessor(1, 1, new byte[] {(byte) value}, null));
    }

    private static int channelByte(final ImagePlus image, final int channel) {
        return image.getStack().getProcessor(image.getStackIndex(channel, 1, 1)).get(0, 0);
    }

    private static void stampMetadata(final ImagePlus image) {
        image.getCalibration().pixelWidth = 0.25;
        image.getCalibration().pixelHeight = 0.5;
        image.getCalibration().pixelDepth = 1.75;
        image.getCalibration().setUnit("microns");
        final FileInfo fileInfo = new FileInfo();
        fileInfo.fileName = "original-source.lsm";
        fileInfo.directory = "C:\\source data\\";
        image.setFileInfo(fileInfo);
        image.setProperty("Info", "source-provenance");
    }

    private static void assertMetadata(final ImagePlus image, final String title) {
        assertEquals(title, image.getTitle());
        assertEquals(0.25, image.getCalibration().pixelWidth, 0.0);
        assertEquals(0.5, image.getCalibration().pixelHeight, 0.0);
        assertEquals(1.75, image.getCalibration().pixelDepth, 0.0);
        assertEquals("microns", image.getCalibration().getUnit());
        assertEquals("original-source.lsm", image.getOriginalFileInfo().fileName);
        assertEquals("C:\\source data\\", image.getOriginalFileInfo().directory);
        assertEquals("source-provenance", image.getProperty("Info"));
    }

    private static void assertRawFloatBits(final float actual, final float expected) {
        assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(actual));
    }
}
