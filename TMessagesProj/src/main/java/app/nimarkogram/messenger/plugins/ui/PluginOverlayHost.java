package app.nimarkogram.messenger.plugins.ui;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import org.telegram.messenger.FileLog;

final class PluginOverlayHost extends FrameLayout {

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final String pluginId;

    PluginOverlayHost(Context context, String pluginId) {
        super(context);
        this.pluginId = pluginId;
        setClipChildren(false);
        setClipToPadding(false);
        setClickable(false);
        setFocusable(false);
        setSaveFromParentEnabled(false);
    }

    String getPluginId() {
        return pluginId;
    }

    private static boolean isMainThread() {
        return Looper.myLooper() == Looper.getMainLooper();
    }

    private void postMutation(Runnable mutation) {
        if (!mainHandler.post(mutation)) {
            FileLog.w("Unable to queue plugin overlay mutation for " + pluginId);
        }
    }

    @Override
    public void addView(
            View child, int index, ViewGroup.LayoutParams params) {
        if (!isMainThread()) {
            postMutation(() -> addViewIfDetached(child, index, params));
            return;
        }
        addViewIfDetached(child, index, params);
    }

    private void addViewIfDetached(
            View child, int index, ViewGroup.LayoutParams params) {
        if (child == null || child.getParent() != null) {
            return;
        }
        int safeIndex = index;
        if (safeIndex < 0 || safeIndex > getChildCount()) {
            safeIndex = getChildCount();
        }
        super.addView(child, safeIndex, params);
    }

    @Override
    public void removeView(View view) {
        if (!isMainThread()) {
            postMutation(() -> removeViewIfOwned(view));
            return;
        }
        removeViewIfOwned(view);
    }

    void removeViewIfOwned(View view) {
        if (view != null && view.getParent() == this) {
            super.removeView(view);
        }
    }

    @Override
    public void removeViewAt(int index) {
        if (!isMainThread()) {
            postMutation(() -> removeViewAtSafely(index));
            return;
        }
        removeViewAtSafely(index);
    }

    private void removeViewAtSafely(int index) {
        if (index >= 0 && index < getChildCount()) {
            super.removeViewAt(index);
        }
    }

    @Override
    public void removeViews(int start, int count) {
        if (!isMainThread()) {
            postMutation(() -> removeViewsSafely(start, count));
            return;
        }
        removeViewsSafely(start, count);
    }

    private void removeViewsSafely(int start, int count) {
        if (start < 0 || count <= 0 || start >= getChildCount()) {
            return;
        }
        super.removeViews(start, Math.min(count, getChildCount() - start));
    }

    @Override
    public void removeAllViews() {
        if (!isMainThread()) {
            postMutation(this::removeAllViews);
            return;
        }
        super.removeAllViews();
    }

    @Override
    public void removeViewInLayout(View view) {
        if (!isMainThread()) {
            postMutation(() -> removeViewIfOwned(view));
            return;
        }
        if (view != null && view.getParent() == this) {
            super.removeViewInLayout(view);
        }
    }

    @Override
    public void removeViewsInLayout(int start, int count) {
        if (!isMainThread()) {
            postMutation(() -> removeViewsSafely(start, count));
            return;
        }
        if (start < 0 || count <= 0 || start >= getChildCount()) {
            return;
        }
        super.removeViewsInLayout(
                start, Math.min(count, getChildCount() - start));
    }

    @Override
    public void removeAllViewsInLayout() {
        if (!isMainThread()) {
            postMutation(this::removeAllViews);
            return;
        }
        super.removeAllViewsInLayout();
    }
}
