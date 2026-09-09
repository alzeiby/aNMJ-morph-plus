import ij.CompositeImage;
import ij.ImagePlus;
import ij.ImageStack;
import ij.io.FileInfo;
import ij.plugin.ZProjector;
import ij.process.ByteProcessor;
import ij.process.ColorProcessor;
import ij.process.FloatProcessor;
import org.scijava.Context;
import org.scijava.command.CommandInfo;
import org.scijava.command.CommandService;
import org.scijava.plugin.PluginService;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
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
            smokeBatchProjectionBridge();
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
                    final Object channelChoice = channelChoiceConstructor.newInstance(1, 2);
                    return promptResultConstructor.newInstance(channelChoice, false);
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
        addByteSlice(stack, 9);
        addByteSlice(stack, 7);
        addByteSlice(stack, 4);
        addByteSlice(stack, 12);
        final AtomicBoolean sourceClosed = new AtomicBoolean(false);
        final ImagePlus source = new ImagePlus("bridge-z.lsm", stack) {
            @Override
            public void close() {
                sourceClosed.set(true);
                super.close();
            }
        };
        source.setDimensions(2, 3, 1);
        source.setOpenAsHyperStack(true);
        final FileInfo sourceFileInfo = new FileInfo();
        sourceFileInfo.fileName = "bridge-z.lsm";
        sourceFileInfo.directory = "C:\\source data\\";
        source.setFileInfo(sourceFileInfo);

        final AtomicReference<ImagePlus> presented = new AtomicReference<>();
        final AtomicReference<String> macroArgument = new AtomicReference<>();
        final Function<Path, ImagePlus> loader = ignored -> source;
        final Consumer<ImagePlus> presenter = presented::set;
        final Function<String, String> macroRunner = argument -> {
            macroArgument.set(argument);
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
        process.invoke(processor, Path.of("bridge-z.lsm"), resolver);

        final ImagePlus normalized = presented.get();
        require(normalized != null && normalized != source, "Batch Z source was not replaced");
        require(sourceClosed.get(), "Batch Z source image was not closed");
        require(normalized.getNChannels() == 2 && normalized.getNSlices() == 1, "Batch Z projection dimensions wrong");
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

    private static Object enumConstant(final Class<?> enumClass, final String name) {
        for (Object constant : enumClass.getEnumConstants()) {
            if (((Enum<?>) constant).name().equals(name)) {
                return constant;
            }
        }
        throw new IllegalStateException("Missing enum constant " + name + " in " + enumClass.getName());
    }
}
