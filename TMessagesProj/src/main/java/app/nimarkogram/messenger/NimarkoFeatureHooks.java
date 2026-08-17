package app.nimarkogram.messenger;

import java.util.concurrent.atomic.AtomicBoolean;

public final class NimarkoFeatureHooks {

    private static final AtomicBoolean noAuthorship = new AtomicBoolean();
    private static final AtomicBoolean noCaptions = new AtomicBoolean();
    private static final AtomicBoolean discussInsteadOfMute = new AtomicBoolean();
    private static final AtomicBoolean forwardWithoutAuthor = new AtomicBoolean();

    private NimarkoFeatureHooks() {}

    public static void switchNoAuthor(boolean b) {
        noAuthorship.set(b);
    }

    public static boolean isNoAuthor() {
        return noAuthorship.get();
    }

    public static void switchNoCaptions(boolean b) {
        noCaptions.set(b);
    }

    public static boolean isNoCaptions() {
        return noCaptions.get();
    }

    public static void setDiscussInsteadOfMute(boolean b) {
        discussInsteadOfMute.set(b);
    }

    public static boolean isDiscussInsteadOfMute() {
        return discussInsteadOfMute.get();
    }

    public static void setForwardWithoutAuthor(boolean b) {
        forwardWithoutAuthor.set(b);
    }

    public static boolean isForwardWithoutAuthor() {
        return forwardWithoutAuthor.get();
    }

    public static void resetAll() {
        noAuthorship.set(false);
        noCaptions.set(false);
        discussInsteadOfMute.set(false);
        forwardWithoutAuthor.set(false);
    }
}
