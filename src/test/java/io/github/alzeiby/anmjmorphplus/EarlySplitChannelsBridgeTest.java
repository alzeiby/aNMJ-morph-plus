package io.github.alzeiby.anmjmorphplus;

import ij.CompositeImage;
import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.macro.Interpreter;
import ij.process.ByteProcessor;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class EarlySplitChannelsBridgeTest {

    @Test
    public void exactCommandClosesSourceCreatesC1ThenC2AndLeavesC2Current() {
        final ImageStack stack = new ImageStack(2, 1);
        stack.addSlice(new ByteProcessor(2, 1, new byte[] {7, 9}, null));
        stack.addSlice(new ByteProcessor(2, 1, new byte[] {19, 23}, null));
        final ImagePlus base = new ImagePlus("split.tif", stack);
        base.setDimensions(2, 1, 1);
        base.setOpenAsHyperStack(true);
        final CompositeImage source = new CompositeImage(base, CompositeImage.COMPOSITE);

        final boolean previousBatchMode = Interpreter.batchMode;
        Interpreter.batchMode = true;
        Interpreter.addBatchModeImage(source);
        WindowManager.setTempCurrentImage(source);
        try {
            source.setC(1);
            final EarlySplitChannelsBridge.SplitResult result =
                EarlySplitChannelsBridge.splitCurrentOrThrow();

            assertEquals(source.getID(), result.sourceId);
            assertTrue(result.channel1Id != result.channel2Id);
            assertNull(WindowManager.getImage(result.sourceId));
            final ImagePlus c1 = WindowManager.getImage(result.channel1Id);
            final ImagePlus c2 = WindowManager.getImage(result.channel2Id);
            assertEquals("C1-split.tif", c1.getTitle());
            assertEquals("C2-split.tif", c2.getTitle());
            assertEquals(7, c1.getProcessor().get(0, 0));
            assertEquals(19, c2.getProcessor().get(0, 0));
            assertSame(c2, WindowManager.getCurrentImage());
            Interpreter.removeBatchModeImage(c1);
            Interpreter.removeBatchModeImage(c2);
        } finally {
            Interpreter.removeBatchModeImage(source);
            WindowManager.setTempCurrentImage((ImagePlus) null);
            Interpreter.batchMode = previousBatchMode;
        }
    }

    @Test
    public void bridgeReturnsErrorInsteadOfPretendingSuccessWhenNoCurrentImage() {
        final ImagePlus previous = WindowManager.getTempCurrentImage();
        WindowManager.setTempCurrentImage((ImagePlus) null);
        try {
            assertTrue(EarlySplitChannelsBridge.splitCurrent().startsWith("ERROR: "));
        } finally {
            WindowManager.setTempCurrentImage(previous);
        }
    }
}
