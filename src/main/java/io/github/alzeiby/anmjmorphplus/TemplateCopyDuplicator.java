package io.github.alzeiby.anmjmorphplus;

import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

final class TemplateCopyDuplicator {

    ImagePlus duplicate(final ImagePlus sourceCopy) {
        Objects.requireNonNull(sourceCopy, "sourceCopy");

        final Set<Integer> before = currentImageIds();
        final String expectedTitle = "__aNMJ_template_" + sourceCopy.getID();
        IJ.run(sourceCopy, "Duplicate...", "title=[" + expectedTitle + "] duplicate");

        final List<Integer> created = new ArrayList<>();
        final int[] after = WindowManager.getIDList();
        if (after != null) {
            for (int id : after) {
                if (!before.contains(id)) {
                    created.add(id);
                }
            }
        }
        if (created.size() != 1) {
            throw new IllegalStateException(
                "ImageJ Duplicate... created " + created.size() + " images; expected exactly one"
            );
        }

        final ImagePlus templateCopy = WindowManager.getImage(created.get(0));
        if (templateCopy == null) {
            throw new IllegalStateException("ImageJ Duplicate... result is no longer open");
        }
        if (templateCopy.getID() == sourceCopy.getID()) {
            throw new IllegalStateException("ImageJ Duplicate... reused the source-copy image ID");
        }
        if (!expectedTitle.equals(templateCopy.getTitle())) {
            throw new IllegalStateException(
                "ImageJ Duplicate... produced unexpected title: " + templateCopy.getTitle()
            );
        }
        return templateCopy;
    }

    private static Set<Integer> currentImageIds() {
        final Set<Integer> ids = new HashSet<>();
        final int[] current = WindowManager.getIDList();
        if (current != null) {
            for (int id : current) {
                ids.add(id);
            }
        }
        return ids;
    }
}
