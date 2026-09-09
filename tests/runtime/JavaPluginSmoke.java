import ij.CompositeImage;
import ij.IJ;
import ij.ImagePlus;
import ij.ImageStack;
import ij.WindowManager;
import ij.io.FileInfo;
import ij.macro.Interpreter;
import ij.plugin.ZProjector;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import ij.process.LUT;
import org.scijava.Context;
import org.scijava.command.CommandInfo;
import org.scijava.command.CommandService;
import org.scijava.plugin.PluginService;

import java.awt.Color;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

public final class JavaPluginSmoke {

    private static final String COMMAND_CLASS =
        "io.github.alzeiby.anmjmorphplus.ANMJMorphCommand";
    private static final String MENU_PATH = "Analyze > Tools > aNMJ-morph+";
    private static final String NORMALIZER_CLASS =
        "io.github.alzeiby.anmjmorphplus.StructuralNormalizer";
    private static final String TWO_PLANE_CLASS =
        "io.github.alzeiby.anmjmorphplus.TwoPlaneInterpretation";
    private static final String CHANNEL_CANONICALIZER_CLASS =
        "io.github.alzeiby.anmjmorphplus.ChannelRoleCanonicalizer";

    private JavaPluginSmoke() {
    }

    public static void main(final String[] args) throws Exception {
        try (Context context = new Context(PluginService.class, CommandService.class)) {
            final PluginService plugins = context.service(PluginService.class);
            plugins.reloadPlugins();

            final CommandService commands = context.service(CommandService.class);
            final CommandInfo command = commands.getCommand(COMMAND_CLASS);
            if (command == null) {
                throw new IllegalStateException("aNMJ-morph+ command was not discovered");
            }
            if (!COMMAND_CLASS.equals(command.getClassName())) {
                throw new IllegalStateException("Unexpected command class: " + command.getClassName());
            }
            if (command.getMenuPath() == null ||
                !MENU_PATH.equals(command.getMenuPath().getMenuString())) {
                throw new IllegalStateException(
                    "Unexpected menu path: " +
                    (command.getMenuPath() == null ? "<none>" : command.getMenuPath().getMenuString())
                );
            }

            command.loadClass();
            smokeStructuralNormalizer();
            smokeChannelRoleCanonicalizer();
            smokeBatchProjectionBridge();
            require(IJ.getInstance() == null, "Java plugin smoke unexpectedly created an ImageJ UI instance");
            final int[] remainingImageIds = WindowManager.getIDList();
            require(
                remainingImageIds == null || remainingImageIds.length == 0,
                "Java plugin smoke left ImageJ images registered after cleanup"
            );
            System.out.println("DONE java plugin smoke");
        }
    }

    private static void smokeStructuralNormalizer() throws Exception {
        final Class<?> normalizerClass = Class.forName(NORMALIZER_CLASS);
        final Constructor<?> constructor = normalizerClass.getDeclaredConstructor();
        constructor.setAccessible(true);
        final Object normalizer = constructor.newInstance();
        final Class<?> interpretationClass = Class.forName(TWO_PLANE_CLASS);
        final Method normalize = normalizerClass.getDeclaredMethod(
            "normalize",
            ImagePlus.class,
            interpretationClass
        );
        normalize.setAccessible(true);

        final ImagePlus rgb = new ImagePlus(
            "rgb.png",
            new ColorProcessor(2, 1, new int[] {0xff123456, 0xffa1b2c3})
        );
        rgb.getCalibration().pixelWidth = 0.75;
        rgb.getCalibration().pixelHeight = 1.25;
        final FileInfo rgbFileInfo = new FileInfo();
        rgbFileInfo.fileName = "rgb.png";
        rgbFileInfo.directory = "C:\\rgb source\\";
        rgb.setFileInfo(rgbFileInfo);
        rgb.setProperty("Info", "rgb provenance");
        final ImagePlus composite = (ImagePlus) normalize.invoke(normalizer, rgb, null);
        require(composite instanceof CompositeImage, "RGB normalization did not return CompositeImage");
        require(composite.getNChannels() == 3 && composite.getNSlices() == 1, "RGB normalization dimensions wrong");
        require(composite.getStack().getProcessor(1).get(0, 0) == 0x12, "RGB red channel wrong");
        require(composite.getStack().getProcessor(2).get(0, 0) == 0x34, "RGB green channel wrong");
        require(composite.getStack().getProcessor(3).get(0, 0) == 0x56, "RGB blue channel wrong");
        require("rgb.png".equals(composite.getTitle()), "RGB source title was not preserved");
        require(composite.getCalibration().pixelWidth == 0.75, "RGB pixel width was not preserved");
        require(composite.getCalibration().pixelHeight == 1.25, "RGB pixel height was not preserved");
        require(composite.getOriginalFileInfo() != null, "RGB source FileInfo missing");
        require(
            "C:\\rgb source\\".equals(composite.getOriginalFileInfo().directory),
            "RGB source directory was not preserved"
        );
        require(
            "rgb provenance".equals(composite.getProperty("Info")),
            "RGB source provenance was not preserved"
        );

        final Object channelsChoice = enumConstant(interpretationClass, "CHANNELS");
        final ImagePlus twoPlaneChannels = twoPlane(7, 19);
        normalize.invoke(normalizer, twoPlaneChannels, channelsChoice);
        require(
            twoPlaneChannels.getNChannels() == 2 && twoPlaneChannels.getNSlices() == 1,
            "Two-plane channel reinterpretation dimensions wrong"
        );
        require(twoPlaneChannels.getStack().getProcessor(1).get(0, 0) == 7, "Two-plane channel 1 wrong");
        require(twoPlaneChannels.getStack().getProcessor(2).get(0, 0) == 19, "Two-plane channel 2 wrong");

        final Object zChoice = enumConstant(interpretationClass, "Z_STACK");
        final ImagePlus twoPlaneZ = twoPlane(31, 17);
        final ImagePlus projectedTwoPlaneZ = (ImagePlus) normalize.invoke(normalizer, twoPlaneZ, zChoice);
        require(projectedTwoPlaneZ.getNChannels() == 1 && projectedTwoPlaneZ.getNSlices() == 1, "Two-plane Z projection dimensions wrong");
        require(projectedTwoPlaneZ.getProcessor().get(0, 0) == 31, "Two-plane Z maximum wrong");

        final ImageStack zStack = new ImageStack(1, 1);
        addByteSlice(zStack, 1);
        addByteSlice(zStack, 10);
        addByteSlice(zStack, 5);
        addByteSlice(zStack, 2);
        addByteSlice(zStack, 3);
        addByteSlice(zStack, 20);
        final ImagePlus multichannelZ = new ImagePlus("z.lsm", zStack);
        multichannelZ.setDimensions(2, 3, 1);
        multichannelZ.setOpenAsHyperStack(true);
        multichannelZ.getCalibration().pixelWidth = 0.25;
        multichannelZ.getCalibration().pixelHeight = 0.5;
        final FileInfo sourceInfo = new FileInfo();
        sourceInfo.fileName = "z.lsm";
        sourceInfo.directory = "C:\\source data\\";
        multichannelZ.setFileInfo(sourceInfo);
        multichannelZ.setProperty("Info", "source provenance");
        final ImagePlus projectedMultichannelZ = (ImagePlus) normalize.invoke(normalizer, multichannelZ, null);
        require(projectedMultichannelZ.getNChannels() == 2 && projectedMultichannelZ.getNSlices() == 1, "Multichannel Z projection dimensions wrong");
        require(projectedMultichannelZ.getStack().getProcessor(1).get(0, 0) == 5, "Multichannel Z channel 1 maximum wrong");
        require(projectedMultichannelZ.getStack().getProcessor(2).get(0, 0) == 20, "Multichannel Z channel 2 maximum wrong");
        require("z.lsm".equals(projectedMultichannelZ.getTitle()), "Projected source title was not preserved");
        require(projectedMultichannelZ.getCalibration().pixelWidth == 0.25, "Projected pixel width was not preserved");
        require(projectedMultichannelZ.getCalibration().pixelHeight == 0.5, "Projected pixel height was not preserved");
        require(projectedMultichannelZ.getOriginalFileInfo() != null, "Projected source FileInfo missing");
        require(
            "C:\\source data\\".equals(projectedMultichannelZ.getOriginalFileInfo().directory),
            "Projected source directory was not preserved"
        );
        require(
            "source provenance".equals(projectedMultichannelZ.getProperty("Info")),
            "Projected source provenance was not preserved"
        );

        final ImagePlus rgbZ = rgbZStack();
        final ImagePlus expectedRgbZ = ZProjector.run(rgbZStack(), "max");
        final ImagePlus projectedRgbZ = (ImagePlus) normalize.invoke(normalizer, rgbZ, null);
        require(projectedRgbZ.getNChannels() == 1 && projectedRgbZ.getNSlices() == 1, "RGB Z projection dimensions wrong");
        final int actualRgbZPixel = projectedRgbZ.getProcessor().get(0, 0);
        final int expectedRgbZPixel = expectedRgbZ.getProcessor().get(0, 0);
        require(
            actualRgbZPixel == expectedRgbZPixel,
            "RGB Z maximum differs from ImageJ ZProjector: actual=" +
                Integer.toHexString(actualRgbZPixel) +
                " expected=" + Integer.toHexString(expectedRgbZPixel) +
                " actualType=" + rgbZ.getProcessor().getClass().getName() +
                " expectedType=" + expectedRgbZ.getProcessor().getClass().getName()
        );

        final ImagePlus floatNaNZ = floatZStack(Float.NaN, 5.0f, 4.0f);
        final ImagePlus expectedFloatNaNZ = ZProjector.run(floatZStack(Float.NaN, 5.0f, 4.0f), "max");
        final ImagePlus projectedFloatNaNZ = (ImagePlus) normalize.invoke(normalizer, floatNaNZ, null);
        require(
            Float.floatToIntBits(projectedFloatNaNZ.getProcessor().getf(0)) ==
                Float.floatToIntBits(expectedFloatNaNZ.getProcessor().getf(0)),
            "32-bit NaN Z maximum differs from ImageJ ZProjector"
        );
    }

    private static void smokeChannelRoleCanonicalizer() throws Exception {
        final Class<?> canonicalizerClass = Class.forName(CHANNEL_CANONICALIZER_CLASS);
        final Constructor<?> canonicalizerConstructor = canonicalizerClass.getDeclaredConstructor();
        canonicalizerConstructor.setAccessible(true);
        final Object canonicalizer = canonicalizerConstructor.newInstance();
        final Class<?> channelChoiceClass = Class.forName(
            "io.github.alzeiby.anmjmorphplus.BatchChoiceResolver$ChannelChoice"
        );
        final Constructor<?> channelChoiceConstructor =
            channelChoiceClass.getDeclaredConstructor(int.class, int.class);
        channelChoiceConstructor.setAccessible(true);
        final Method canonicalize = canonicalizerClass.getDeclaredMethod(
            "canonicalize",
            ImagePlus.class,
            channelChoiceClass
        );
        canonicalize.setAccessible(true);
        final Object threeOne = channelChoiceConstructor.newInstance(3, 1);
        final Object twoOne = channelChoiceConstructor.newInstance(2, 1);
        final Object oneTwo = channelChoiceConstructor.newInstance(1, 2);

        final ImageStack stack = new ImageStack(1, 1);
        addByteSlice(stack, 11);
        addByteSlice(stack, 22);
        addByteSlice(stack, 33);
        final AtomicBoolean sourceClosed = new AtomicBoolean(false);
        final ImagePlus source = new ImagePlus("roles.lsm", stack) {
            @Override
            public void close() {
                sourceClosed.set(true);
                super.close();
            }
        };
        source.setDimensions(3, 1, 1);
        source.setOpenAsHyperStack(true);
        source.getCalibration().pixelWidth = 0.25;
        source.getCalibration().pixelHeight = 0.5;
        final FileInfo fileInfo = new FileInfo();
        fileInfo.fileName = "roles.lsm";
        fileInfo.directory = "C:\\role source\\";
        source.setFileInfo(fileInfo);
        source.setProperty("Info", "role provenance");
        final int sourceId = source.getID();

        final ImagePlus canonical = (ImagePlus) canonicalize.invoke(canonicalizer, source, threeOne);

        require(sourceClosed.get(), "Channel canonicalization did not close transformed source");
        require(canonical != source, "Channel canonicalization did not replace transformed source");
        require(canonical.getID() != sourceId, "Channel canonicalization reused the source image ID");
        require(canonical.getNChannels() == 2, "Channel canonicalization did not reduce to two channels");
        require(canonical.getStack().getProcessor(1).get(0, 0) == 33, "Canonical muscle channel is wrong");
        require(canonical.getStack().getProcessor(2).get(0, 0) == 11, "Canonical nerve channel is wrong");
        require("roles.lsm".equals(canonical.getTitle()), "Canonical source title was not preserved");
        require(canonical.getCalibration().pixelWidth == 0.25, "Canonical pixel width was not preserved");
        require(canonical.getCalibration().pixelHeight == 0.5, "Canonical pixel height was not preserved");
        require(canonical.getOriginalFileInfo() != null, "Canonical source FileInfo missing");
        require(
            "C:\\role source\\".equals(canonical.getOriginalFileInfo().directory),
            "Canonical source directory was not preserved"
        );
        require(
            "role provenance".equals(canonical.getProperty("Info")),
            "Canonical source provenance was not preserved"
        );

        final ImageStack swapStack = new ImageStack(1, 1);
        addByteSlice(swapStack, 7);
        addByteSlice(swapStack, 19);
        final ImagePlus swap = new ImagePlus("swap.tif", swapStack);
        swap.setDimensions(2, 1, 1);
        swap.setOpenAsHyperStack(true);
        final ImagePlus swapped = (ImagePlus) canonicalize.invoke(canonicalizer, swap, twoOne);
        require(swapped.getStack().getProcessor(1).get(0, 0) == 19, "Two-channel swap C1 wrong");
        require(swapped.getStack().getProcessor(2).get(0, 0) == 7, "Two-channel swap C2 wrong");

        final ImageStack compositeStack = new ImageStack(1, 1);
        addByteSlice(compositeStack, 1);
        addByteSlice(compositeStack, 2);
        addByteSlice(compositeStack, 3);
        final ImagePlus compositeBase = new ImagePlus("composite.tif", compositeStack);
        compositeBase.setDimensions(3, 1, 1);
        compositeBase.setOpenAsHyperStack(true);
        final CompositeImage composite = new CompositeImage(compositeBase, CompositeImage.COMPOSITE);
        composite.setChannelLut(LUT.createLutFromColor(Color.RED), 1);
        composite.setChannelLut(LUT.createLutFromColor(Color.GREEN), 2);
        composite.setChannelLut(LUT.createLutFromColor(Color.BLUE), 3);
        final ImagePlus arrangedComposite =
            (ImagePlus) canonicalize.invoke(canonicalizer, composite, threeOne);
        require(arrangedComposite instanceof CompositeImage, "Canonical composite type was not preserved");
        final CompositeImage canonicalComposite = (CompositeImage) arrangedComposite;
        require(canonicalComposite.getMode() == CompositeImage.COMPOSITE, "Canonical composite mode changed");
        require(canonicalComposite.getChannelLut(1).getBlue(255) == 255, "Selected C3 LUT was not moved to C1");
        require(canonicalComposite.getChannelLut(2).getRed(255) == 255, "Selected C1 LUT was not moved to C2");

        final float payloadNaN = Float.intBitsToFloat(0x7fc12345);
        final ImageStack floatStack = new ImageStack(2, 1);
        floatStack.addSlice(new FloatProcessor(2, 1, new float[] {Float.NEGATIVE_INFINITY, -0.0f}));
        floatStack.addSlice(new FloatProcessor(2, 1, new float[] {2.0f, 3.0f}));
        floatStack.addSlice(new FloatProcessor(2, 1, new float[] {payloadNaN, Float.POSITIVE_INFINITY}));
        final ImagePlus floats = new ImagePlus("float.tif", floatStack);
        floats.setDimensions(3, 1, 1);
        floats.setOpenAsHyperStack(true);
        final ImagePlus canonicalFloats = (ImagePlus) canonicalize.invoke(canonicalizer, floats, threeOne);
        require(
            Float.floatToRawIntBits(canonicalFloats.getStack().getProcessor(1).getf(0)) ==
                Float.floatToRawIntBits(payloadNaN),
            "Canonical NaN payload bits changed"
        );
        require(
            Float.floatToRawIntBits(canonicalFloats.getStack().getProcessor(1).getf(1)) ==
                Float.floatToRawIntBits(Float.POSITIVE_INFINITY),
            "Canonical positive infinity changed"
        );
        require(
            Float.floatToRawIntBits(canonicalFloats.getStack().getProcessor(2).getf(0)) ==
                Float.floatToRawIntBits(Float.NEGATIVE_INFINITY),
            "Canonical negative infinity changed"
        );
        require(
            Float.floatToRawIntBits(canonicalFloats.getStack().getProcessor(2).getf(1)) ==
                Float.floatToRawIntBits(-0.0f),
            "Canonical signed zero changed"
        );

        final ImageStack tenStack = new ImageStack(1, 1);
        for (int channel = 1; channel <= 10; channel++) {
            addByteSlice(tenStack, channel);
        }
        final ImagePlus ten = new ImagePlus("ten.tif", tenStack);
        ten.setDimensions(10, 1, 1);
        ten.setOpenAsHyperStack(true);
        final ImagePlus unchangedTen = (ImagePlus) canonicalize.invoke(canonicalizer, ten, threeOne);
        require(unchangedTen == ten, ">9-channel image was unexpectedly canonicalized");

        final Class<?> normalizerClass = Class.forName(NORMALIZER_CLASS);
        final Constructor<?> normalizerConstructor = normalizerClass.getDeclaredConstructor();
        normalizerConstructor.setAccessible(true);
        final Object normalizer = normalizerConstructor.newInstance();
        final Class<?> interpretationClass = Class.forName(TWO_PLANE_CLASS);
        final Method normalize = normalizerClass.getDeclaredMethod(
            "normalize",
            ImagePlus.class,
            interpretationClass
        );
        normalize.setAccessible(true);
        final ImagePlus twoPlaneChannels = twoPlane(13, 29);
        final Object channelsChoice = enumConstant(interpretationClass, "CHANNELS");
        final ImagePlus interpreted =
            (ImagePlus) normalize.invoke(normalizer, twoPlaneChannels, channelsChoice);
        final ImagePlus swappedTwoPlane =
            (ImagePlus) canonicalize.invoke(canonicalizer, interpreted, twoOne);
        require(swappedTwoPlane.getNChannels() == 2, "Two-plane CHANNELS canonicalization dimensions wrong");
        require(swappedTwoPlane.getNSlices() == 1, "Two-plane CHANNELS canonicalization retained Z planes");
        require(swappedTwoPlane.getStack().getProcessor(1).get(0, 0) == 29, "Two-plane CHANNELS swap C1 wrong");
        require(swappedTwoPlane.getStack().getProcessor(2).get(0, 0) == 13, "Two-plane CHANNELS swap C2 wrong");

        final Class<?> processorClass = Class.forName(
            "io.github.alzeiby.anmjmorphplus.LegacyBatchFileProcessor"
        );
        final Method buildMacroArgument = processorClass.getDeclaredMethod(
            "buildMacroArgument",
            ImagePlus.class,
            channelChoiceClass,
            boolean.class,
            int.class
        );
        buildMacroArgument.setAccessible(true);
        final ImagePlus plainTwoChannel = twoPlane(7, 19);
        plainTwoChannel.setDimensions(2, 1, 1);
        require(!plainTwoChannel.isComposite(), "Plain two-channel smoke fixture unexpectedly composite");
        final String plainArgument = (String) buildMacroArgument.invoke(null, plainTwoChannel, oneTwo, false, -12345);
        require(
            !plainArgument.contains("channels-canonical="),
            "Plain two-channel bridge unexpectedly skipped legacy Arrange"
        );
        require(
            plainArgument.contains("source-copy-id=-12345"),
            "Plain two-channel bridge omitted source-copy ID"
        );
        require(!interpreted.isComposite(), "C1/Z2 CHANNELS fixture unexpectedly composite");
        final String twoPlaneArgument = (String) buildMacroArgument.invoke(null, interpreted, oneTwo, false, -23456);
        require(
            !twoPlaneArgument.contains("channels-canonical="),
            "C1/Z2 CHANNELS bridge unexpectedly skipped legacy Arrange"
        );
        require(
            twoPlaneArgument.contains("source-copy-id=-23456"),
            "C1/Z2 CHANNELS bridge omitted source-copy ID"
        );
    }

    private static void smokeBatchProjectionBridge() throws Exception {
        final Class<?> normalizerClass = Class.forName(NORMALIZER_CLASS);
        final Constructor<?> normalizerConstructor = normalizerClass.getDeclaredConstructor();
        normalizerConstructor.setAccessible(true);
        final Object normalizer = normalizerConstructor.newInstance();

        final Class<?> checkpointClass = Class.forName(
            "io.github.alzeiby.anmjmorphplus.BatchCheckpointStore"
        );
        final Constructor<?> checkpointConstructor = checkpointClass.getDeclaredConstructor();
        checkpointConstructor.setAccessible(true);
        final Object checkpointStore = checkpointConstructor.newInstance();

        final Class<?> sessionClass = Class.forName(
            "io.github.alzeiby.anmjmorphplus.BatchCheckpointStore$Session"
        );
        final Constructor<?> sessionConstructor = sessionClass.getDeclaredConstructor();
        sessionConstructor.setAccessible(true);
        final Object session = sessionConstructor.newInstance();

        final Class<?> resolverClass = Class.forName(
            "io.github.alzeiby.anmjmorphplus.BatchChoiceResolver"
        );
        final Class<?> prompterClass = Class.forName(
            "io.github.alzeiby.anmjmorphplus.BatchChoiceResolver$Prompter"
        );
        final Class<?> channelChoiceClass = Class.forName(
            "io.github.alzeiby.anmjmorphplus.BatchChoiceResolver$ChannelChoice"
        );
        final Constructor<?> channelChoiceConstructor =
            channelChoiceClass.getDeclaredConstructor(int.class, int.class);
        channelChoiceConstructor.setAccessible(true);
        final Class<?> promptResultClass = Class.forName(
            "io.github.alzeiby.anmjmorphplus.BatchChoiceResolver$PromptResult"
        );
        final Constructor<?> promptResultConstructor =
            promptResultClass.getDeclaredConstructor(Object.class, boolean.class);
        promptResultConstructor.setAccessible(true);
        final Object prompter = Proxy.newProxyInstance(
            JavaPluginSmoke.class.getClassLoader(),
            new Class<?>[] {prompterClass},
            (proxy, method, args) -> {
                if ("promptChannels".equals(method.getName())) {
                    final Object channelChoice = channelChoiceConstructor.newInstance(3, 1);
                    return promptResultConstructor.newInstance(channelChoice, true);
                }
                if ("promptTwoPlane".equals(method.getName())) {
                    throw new IllegalStateException("Unexpected two-plane prompt in Z bridge smoke");
                }
                if ("toString".equals(method.getName())) {
                    return "JavaPluginSmokePrompter";
                }
                if ("hashCode".equals(method.getName())) {
                    return System.identityHashCode(proxy);
                }
                if ("equals".equals(method.getName())) {
                    return proxy == args[0];
                }
                throw new IllegalStateException("Unexpected prompter method: " + method.getName());
            }
        );
        final Constructor<?> resolverConstructor = resolverClass.getDeclaredConstructor(
            Path.class,
            checkpointClass,
            sessionClass,
            prompterClass
        );
        resolverConstructor.setAccessible(true);
        final Path root = Files.createTempDirectory("anmj-morph-plus-smoke-");
        final Object resolver = resolverConstructor.newInstance(
            root,
            checkpointStore,
            session,
            prompter
        );

        final ImageStack stack = new ImageStack(1, 1);
        addByteSlice(stack, 3);
        addByteSlice(stack, 20);
        addByteSlice(stack, 100);
        addByteSlice(stack, 9);
        addByteSlice(stack, 7);
        addByteSlice(stack, 80);
        addByteSlice(stack, 4);
        addByteSlice(stack, 12);
        addByteSlice(stack, 120);
        final AtomicBoolean sourceClosed = new AtomicBoolean(false);
        final ImagePlus source = new ImagePlus("bridge-z.lsm", stack) {
            @Override
            public void close() {
                sourceClosed.set(true);
                super.close();
            }
        };
        source.setDimensions(3, 3, 1);
        source.setOpenAsHyperStack(true);
        final FileInfo sourceFileInfo = new FileInfo();
        sourceFileInfo.fileName = "bridge-z.lsm";
        sourceFileInfo.directory = "C:\\source data\\";
        source.setFileInfo(sourceFileInfo);

        final AtomicBoolean sentinelClosed = new AtomicBoolean(false);
        final ImagePlus sentinel = new ImagePlus("sentinel", new ByteProcessor(1, 1)) {
            @Override
            public void close() {
                sentinelClosed.set(true);
                super.close();
            }
        };
        final boolean previousBatchMode = Interpreter.batchMode;
        Interpreter.batchMode = true;
        Interpreter.addBatchModeImage(sentinel);

        final List<ImagePlus> presentedImages = new ArrayList<>();
        final AtomicReference<ImagePlus> presented = new AtomicReference<>();
        final AtomicReference<String> macroArgument = new AtomicReference<>();
        final AtomicReference<Integer> sourceCopyId = new AtomicReference<>();
        final Function<Path, ImagePlus> loader = ignored -> source;
        final Consumer<ImagePlus> presenter = image -> {
            presented.set(image);
            presentedImages.add(image);
            Interpreter.addBatchModeImage(image);
        };
        final Function<String, String> macroRunner = argument -> {
            macroArgument.set(argument);
            final ImagePlus current = presented.get();
            require(
                current != null && WindowManager.getImage(current.getID()) == current,
                "Batch macro image ID does not resolve to the canonical image"
            );
            final String sourceCopyText = argumentValue(argument, "source-copy-id");
            require(sourceCopyText != null, "Batch macro argument omitted source-copy ID");
            final int copyId = Integer.parseInt(sourceCopyText);
            sourceCopyId.set(copyId);
            final ImagePlus sourceCopy = WindowManager.getImage(copyId);
            require(sourceCopy != null, "Batch source-copy ID does not resolve while macro is running");
            require(
                ("__aNMJ_source_" + current.getID()).equals(sourceCopy.getTitle()),
                "Batch source-copy title does not match canonical source ID"
            );
            require(sourceCopy.getID() != current.getID(), "Batch source copy reused canonical source ID");
            require(
                sourceCopy.getNChannels() == current.getNChannels() &&
                    sourceCopy.getNSlices() == current.getNSlices() &&
                    sourceCopy.getNFrames() == current.getNFrames(),
                "Batch source-copy dimensions changed before macro handoff"
            );
            require(
                sourceCopy.getStack().getProcessor(1).get(0, 0) == current.getStack().getProcessor(1).get(0, 0),
                "Batch source-copy pixels differ before macro handoff"
            );
            return null;
        };

        final Class<?> processorClass = Class.forName(
            "io.github.alzeiby.anmjmorphplus.LegacyBatchFileProcessor"
        );
        final Constructor<?> processorConstructor = processorClass.getDeclaredConstructor(
            Function.class,
            Consumer.class,
            Function.class,
            normalizerClass
        );
        processorConstructor.setAccessible(true);
        final Object processor = processorConstructor.newInstance(
            loader,
            presenter,
            macroRunner,
            normalizer
        );
        final Method process = processorClass.getDeclaredMethod("process", Path.class, resolverClass);
        process.setAccessible(true);
        try {
            process.invoke(processor, Path.of("bridge-z.lsm"), resolver);

            final ImagePlus normalized = presented.get();
            require(normalized != null && normalized != source, "Batch Z source was not replaced");
            require(sourceClosed.get(), "Batch Z source image was not closed");
            require(normalized.getNChannels() == 2 && normalized.getNSlices() == 1, "Batch Z projection dimensions wrong");
            require(normalized.getStack().getProcessor(1).get(0, 0) == 120, "Batch canonical muscle channel wrong");
            require(normalized.getStack().getProcessor(2).get(0, 0) == 9, "Batch canonical nerve channel wrong");
            require(normalized.getID() != source.getID(), "Batch canonical image reused source ID");
            require("bridge-z.lsm".equals(normalized.getTitle()), "Batch Z source title was not preserved");
            require(normalized.getOriginalFileInfo() != null, "Batch Z source FileInfo missing");
            require(
                "C:\\source data\\".equals(normalized.getOriginalFileInfo().directory),
                "Batch Z source directory was not preserved"
            );
            require(
                macroArgument.get() != null &&
                    macroArgument.get().contains("image-id=" + normalized.getID()),
                "Batch macro did not receive the projected image ID"
            );
            require(
                macroArgument.get().contains("muscle-channel=1") &&
                    macroArgument.get().contains("nerve-channel=2"),
                "Batch macro did not receive canonical channel roles"
            );
            require(
                macroArgument.get().contains("channels-canonical=1"),
                "Batch macro did not receive canonical-channel shadow flag"
            );
            require(
                sourceCopyId.get() != null && WindowManager.getImage(sourceCopyId.get()) == null,
                "Batch source copy was not cleaned up after macro handoff"
            );
            require(!sentinelClosed.get(), "Batch cleanup closed a pre-existing sentinel image");
            final Path checkpoint = root.resolve(".anmj-morph-plus").resolve("session-v1.tsv");
            final String checkpointText = Files.readString(checkpoint);
            require(checkpointText.contains("\t3,1"), "Batch checkpoint did not preserve original 3,1 channel choice");
        } finally {
            for (ImagePlus image : presentedImages) {
                Interpreter.removeBatchModeImage(image);
            }
            Interpreter.removeBatchModeImage(sentinel);
            Interpreter.batchMode = previousBatchMode;
        }
    }

    private static ImagePlus twoPlane(final int first, final int second) {
        final ImageStack stack = new ImageStack(1, 1);
        addByteSlice(stack, first);
        addByteSlice(stack, second);
        final ImagePlus image = new ImagePlus("two-plane.tif", stack);
        image.setDimensions(1, 2, 1);
        image.setOpenAsHyperStack(true);
        return image;
    }

    private static ImagePlus rgbZStack() {
        final ImageStack stack = new ImageStack(1, 1);
        stack.addSlice(new ColorProcessor(1, 1, new int[] {0xff102030}));
        stack.addSlice(new ColorProcessor(1, 1, new int[] {0xff405010}));
        stack.addSlice(new ColorProcessor(1, 1, new int[] {0xff206070}));
        final ImagePlus image = new ImagePlus("rgb-z.tif", stack);
        image.setDimensions(1, 3, 1);
        image.setOpenAsHyperStack(true);
        return image;
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

    private static void addByteSlice(final ImageStack stack, final int value) {
        stack.addSlice(new ByteProcessor(1, 1, new byte[] {(byte) value}, null));
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static String argumentValue(final String argument, final String key) {
        final String prefix = key + "=";
        for (String part : argument.split(";")) {
            if (part.startsWith(prefix)) {
                return part.substring(prefix.length());
            }
        }
        return null;
    }

    private static Object enumConstant(final Class<?> enumClass, final String name) {
        for (Object constant : enumClass.getEnumConstants()) {
            if (((Enum<?>) constant).name().equals(name)) {
                return constant;
            }
        }
        throw new IllegalStateException("Missing enum constant " + name + " in " + enumClass.getName());
    }
}
