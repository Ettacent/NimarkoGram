/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.utils.twidget;

final class WidgetSandboxNative {

    static native long create(
            byte[] directory,
            byte[] html,
            byte[] theme,
            float width,
            float density,
            int cpuMultiplier,
            byte[] locale,
            byte[] storage,
            byte[] sdk,
            byte[] stoppedLabel,
            boolean visible,
            Object backend);

    static native void registerResources(
            byte[] key, byte[] sandbox, byte[] validator, byte[] renderer, byte[] sandboxBytecode, byte[] validatorBytecode);

    static native void destroy(long session);

    static native void visible(long session, boolean visible);

    static native void theme(long session, byte[] theme);

    static native void width(long session, float width);

    static native void data(long session, byte[] data);

    static native void safeArea(long session, byte[] area);

    static native void answerRequest(long session, long request, int failure, byte[] result);

    static native void repaint(long session);

    static native void tick(
            long session, float timeMs, boolean frame, boolean animate, boolean paint);

    static native void touch(
            long session, int action, float x, float y, int pointer, boolean primary, boolean mouse, int modifiers,
            int button);

    static native void hover(long session, int action, float x, float y, int modifiers);


    static native void conformance(boolean enabled);

    static native void limits(byte[] json);

    static native void attackScan(boolean enabled);

    private static final boolean LOADED = load();

    private static boolean load() {
        try {
            System.loadLibrary("twidget");
            return true;
        } catch (Throwable error) {
            return false;
        }
    }

    static boolean available() {
        return LOADED;
    }

    private WidgetSandboxNative() {}
}
