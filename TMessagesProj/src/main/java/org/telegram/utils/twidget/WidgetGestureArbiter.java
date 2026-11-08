/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.utils.twidget;

public final class WidgetGestureArbiter {

    public static final int NONE = -1;
    public static final int OWNER_PENDING = 0, OWNER_WIDGET = 1, OWNER_BUBBLE = 2;
    public static final int PRESS = 0, TAP = 1, DOUBLE_TAP = 2, HOLD = 3, DRAG_X = 4, DRAG_Y = 5, MENU_CLICK = 6;
    public static final int ACTION_NONE = 0, ACTION_MENU = 1, ACTION_SWIPE = 2, ACTION_SCROLL = 3, ACTION_BACK = 4;

    private long handle;

    private long handle() {
        if (handle == 0) handle = WidgetSandboxNative.arbiterCreate();
        return handle;
    }

    public int down(double t, double x, double y, boolean claimX, boolean claimY, boolean hold) {
        return down(t, x, y, claimX, claimY, hold, false, 0);
    }

    public int down(double t, double x, double y, boolean claimX, boolean claimY, boolean hold, boolean mouse, int button) {
        return WidgetSandboxNative.arbiterDown(handle(), t, x, y, claimX, claimY, hold, mouse, button);
    }

    public int move(double t, double x, double y) {
        return handle == 0 ? NONE : WidgetSandboxNative.arbiterMove(handle, t, x, y);
    }

    public int tick(double t) {
        return handle == 0 ? NONE : WidgetSandboxNative.arbiterTick(handle, t);
    }

    public int up(double t, double x, double y) {
        return handle == 0 ? NONE : WidgetSandboxNative.arbiterUp(handle, t, x, y);
    }

    public void cancel() {
        if (handle != 0) WidgetSandboxNative.arbiterCancel(handle);
    }

    public double holdDeadline() {
        return handle == 0 ? -1 : WidgetSandboxNative.arbiterHoldDeadline(handle);
    }

    public void release() {
        if (handle == 0) return;
        WidgetSandboxNative.arbiterDestroy(handle);
        handle = 0;
    }

    public static int owner(int verdict) {
        return verdict & 3;
    }

    public static int gesture(int verdict) {
        return (verdict >> 2) & 7;
    }

    public static boolean cancel(int verdict) {
        return (verdict & (1 << 5)) != 0;
    }

    public static int action(int verdict) {
        return (verdict >> 6) & 7;
    }

    public static boolean edge(int verdict) {
        return (verdict & (1 << 11)) != 0;
    }
}
