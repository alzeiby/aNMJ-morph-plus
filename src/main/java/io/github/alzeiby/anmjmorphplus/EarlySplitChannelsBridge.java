package io.github.alzeiby.anmjmorphplus;

import ij.IJ;
import ij.ImagePlus;
import ij.WindowManager;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** ImageJ-macro callable bridge for the two early Split Channels boundaries. */
public final class EarlySplitChannelsBridge {

    private EarlySplitChannelsBridge() {
    }

    /**
     * Splits the actual current ImageJ image synchronously using the ImageJ 1.x command.
     * The macro treats anything other than {@code OK} as fail-closed and never falls back.
     */
    public static String splitCurrent() {
        try {
            final SplitResult result = splitCurrentOrThrow();
            return "OK;" + result.channel1Id + ";" + result.channel2Id;
        } catch (RuntimeException e) {
            return "ERROR: " + messageOrClass(e);
        }
    }

    static SplitResult splitCurrentOrThrow() {
        final ImagePlus source = WindowManager.getCurrentImage();
        if (source == null) {
            throw new IllegalStateException("No current image for Split Channels");
        }
        if (source.getNChannels() != 2) {
            throw new IllegalStateException(
                "Split Channels bridge requires exactly two channels; found " + source.getNChannels()
            );
        }
        if (source.getC() != 1) {
            throw new IllegalStateException("Split Channels bridge requires current channel C1");
        }

        final int sourceId = source.getID();
        final String sourceTitle = source.getTitle();
        final Set<Integer> before = currentImageIds();

        // Deliberately invoke the exact ImageJ command against the actual current image.
        IJ.run("Split Channels");

        if (WindowManager.getImage(sourceId) != null) {
            throw new IllegalStateException("Split Channels did not close its source image");
        }

        final List<Integer> created = new ArrayList<>();
        final int[] after = WindowManager.getIDList();
        if (after != null) {
            for (int id : after) {
                if (!before.contains(id)) {
                    created.add(id);
                }
            }
        }
        if (created.size() != 2) {
            throw new IllegalStateException(
                "Split Channels created " + created.size() + " images; expected exactly two"
            );
        }

        final ImagePlus channel1 = imageWithTitle("C1-" + sourceTitle, created);
        final ImagePlus channel2 = imageWithTitle("C2-" + sourceTitle, created);
        if (channel1.getID() == channel2.getID()) {
            throw new IllegalStateException("Split Channels reused a channel image ID");
        }
        if (WindowManager.getCurrentImage() != channel2) {
            throw new IllegalStateException("Split Channels did not leave C2 current");
        }
        return new SplitResult(sourceId, channel1.getID(), channel2.getID(), sourceTitle);
    }

    private static ImagePlus imageWithTitle(final String title, final List<Integer> created) {
        ImagePlus match = null;
        for (int id : created) {
            final ImagePlus image = WindowManager.getImage(id);
            if (image != null && title.equals(image.getTitle())) {
                if (match != null) {
                    throw new IllegalStateException("Split Channels created duplicate title " + title);
                }
                match = image;
            }
        }
        if (match == null) {
            throw new IllegalStateException("Split Channels did not create expected title " + title);
        }
        return match;
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

    private static String messageOrClass(final RuntimeException error) {
        return error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
    }

    static final class SplitResult {
        final int sourceId;
        final int channel1Id;
        final int channel2Id;
        final String sourceTitle;

        SplitResult(final int sourceId, final int channel1Id, final int channel2Id, final String sourceTitle) {
            this.sourceId = sourceId;
            this.channel1Id = channel1Id;
            this.channel2Id = channel2Id;
            this.sourceTitle = sourceTitle;
        }
    }
}
