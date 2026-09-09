package io.github.alzeiby.anmjmorphplus;

import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;
import ij.gui.GenericDialog;
import ij.io.OpenDialog;

import java.nio.file.Path;

final class InputWorkflowRunner implements Runnable {

    static final String TIME_SERIES_ERROR =
        "Time-series images (T > 1) are not supported. Reduce the image to a single time point before running aNMJ-morph+.";

    @Override
    public void run() {
        ImagePlus image = WindowManager.getCurrentImage();
        Path path = null;

        if (image == null) {
            final GenericDialog mode = new GenericDialog("aNMJ-morph+");
            mode.addChoice("Analyze", new String[] {"Single image", "Batch folder"}, "Single image");
            mode.showDialog();
            if (mode.wasCanceled()) return;
            if (mode.getNextChoiceIndex() == 1) {
                new BatchSessionRunner().run();
                return;
            }

            final OpenDialog file = new OpenDialog("Select image to analyze");
            if (file.getPath() == null) return;
            path = Path.of(file.getPath());
            try {
                image = new ImageLoader().load(path);
                image.show();
            } catch (RuntimeException e) {
                IJ.error("aNMJ-morph+", message(e));
                return;
            }
        }

        try {
            new SingleImageAnalysisRunner().analyze(image, path);
        } catch (AnalysisCancelledException ignored) {
        } catch (RuntimeException e) {
            IJ.error("aNMJ-morph+", message(e));
        }
    }

    private static String message(final RuntimeException error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }
}
