/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.messenger.utils;

import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.view.Choreographer;
import android.view.View;

import android.util.SparseArray;

import androidx.annotation.Nullable;

import org.telegram.messenger.BuildConfig;

import java.util.LinkedHashSet;
import java.util.Set;

import me.vkryl.core.reference.ReferenceList;

public final class Choreographer60FpsContent implements Choreographer.FrameCallback {


    private static final int  TARGET_FPS        = 60;

    private static final long FRAME_INTERVAL_NS = 1_000_000_000L / TARGET_FPS;


    private static final long[] FRAME_MASKS = buildFrameMasks();
    private static Choreographer60FpsContent sInstance;

    public static Choreographer60FpsContent getInstance() {
        checkMainThread();
        if (sInstance == null) {
            sInstance = new Choreographer60FpsContent();
        }
        return sInstance;
    }


    private final Choreographer mChoreographer = Choreographer.getInstance();

    private final Set<FrameCallback> mOneShot = new LinkedHashSet<>();

    private final SparseArray<CallbackGroup> mGroups = new SparseArray<>();

    private final ReferenceList<Drawable> mDrawablesToInvalidate      = new ReferenceList<>();
    private final ReferenceList<Drawable> mDrawablesToInvalidate30fps = new ReferenceList<>();
    private final ReferenceList<View>     mViewsToInvalidate          = new ReferenceList<>();

    private long mAccumulatedNs;

    private long mLastVsyncNs;

    private int mCounter;


    public interface FrameCallback {
        void doFrame(long frameTimeNanos);
    }

    public void post(FrameCallback callback) {
        checkMainThread();
        mOneShot.add(callback);
    }

    public void postInvalidateDrawable(Drawable drawable) {
        checkMainThread();
        mDrawablesToInvalidate.add(drawable);
    }

    public void postInvalidateDrawable30fps(Drawable drawable) {
        checkMainThread();
        mDrawablesToInvalidate30fps.add(drawable);
    }

    public void postInvalidateView(View view) {
        checkMainThread();
        mViewsToInvalidate.add(view);
    }

    public void addFrameCallback(FrameCallback callback) {
        addFrameCallback(callback, TARGET_FPS);
    }

    public void addFrameCallback(Runnable callback) {
        addFrameCallback(callback, TARGET_FPS);
    }

    public void addFrameCallbackOnce(Runnable callback, int fps) {
        checkMainThread();
        if (callback == null) {
            return;
        }
        fps = Math.max(1, Math.min(fps, TARGET_FPS));
        removeFrameCallbackOnce(callback);
        CallbackGroup group = getOrCreateGroup(fps);
        if (group.runnableCallbacksOnce == null) {
            group.runnableCallbacksOnce = new ReferenceList<>();
        }
        group.runnableCallbacksOnce.add(callback);
    }


    public void addFrameCallback(Runnable callback, int fps) {
        checkMainThread();
        if (callback == null) {
            return;
        }
        fps = Math.max(1, Math.min(fps, TARGET_FPS));
        removeFrameCallback(callback);
        getOrCreateGroup(fps).runnableCallbacks.add(callback);
    }

    public void addFrameCallback(FrameCallback callback, int fps) {
        checkMainThread();
        fps = Math.max(1, Math.min(fps, TARGET_FPS));
        removeFrameCallback(callback);
        getOrCreateGroup(fps).callbacks.add(callback);
    }

    public void removeFrameCallback(Runnable callback) {
        checkMainThread();
        if (callback == null) {
            return;
        }
        for (int i = 0; i < mGroups.size(); i++) {
            CallbackGroup group = mGroups.valueAt(i);
            if (group.runnableCallbacks.remove(callback)) {
                return;
            }
        }
    }

    public void removeFrameCallbackOnce(Runnable callback) {
        checkMainThread();
        if (callback == null) {
            return;
        }
        for (int i = 0; i < mGroups.size(); i++) {
            CallbackGroup group = mGroups.valueAt(i);
            if (group.runnableCallbacksOnce != null && group.runnableCallbacksOnce.remove(callback)) {
                return;
            }
        }
    }

    public void removeFrameCallback(FrameCallback callback) {
        checkMainThread();
        if (callback == null) {
            return;
        }
        for (int i = 0; i < mGroups.size(); i++) {
            CallbackGroup group = mGroups.valueAt(i);
            if (group.callbacks.remove(callback)) {
                return;
            }
        }
    }


    private Choreographer60FpsContent() {
        mChoreographer.postFrameCallback(this);
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        if (mLastVsyncNs == 0) {
            mLastVsyncNs = frameTimeNanos;
        } else {
            mAccumulatedNs += frameTimeNanos - mLastVsyncNs;
            mLastVsyncNs    = frameTimeNanos;

            if (mAccumulatedNs >= FRAME_INTERVAL_NS) {
                mAccumulatedNs %= FRAME_INTERVAL_NS;
                dispatchFrame(frameTimeNanos);
            }
        }

        mChoreographer.postFrameCallback(this);
    }

    private void dispatchFrame(long frameTimeNanos) {
        final long tickMask = 1L << mCounter;
        for (int i = 0; i < mGroups.size(); i++) {
            CallbackGroup group = mGroups.valueAt(i);
            final boolean fire = (group.frameMask & tickMask) != 0;
            if (fire) {
                if (group.runnableCallbacksOnce != null) {
                    ReferenceList<Runnable> referenceList = group.runnableCallbacksOnce;
                    group.runnableCallbacksOnce = null;
                    for (Runnable runnable : referenceList) {
                        runnable.run();
                    }
                }

                for (FrameCallback cb : group.callbacks) {
                    cb.doFrame(frameTimeNanos);
                }
                for (Runnable runnable : group.runnableCallbacks) {
                    runnable.run();
                }
            }
        }

        for (FrameCallback cb : mOneShot) {
            cb.doFrame(frameTimeNanos);
        }

        for (View view : mViewsToInvalidate) {
            view.invalidate();
        }
        for (Drawable drawable : mDrawablesToInvalidate) {
            drawable.invalidateSelf();
        }
        mViewsToInvalidate.clear();
        mDrawablesToInvalidate.clear();
        mOneShot.clear();

        if (mCounter % 2 == 0) {
            for (Drawable drawable : mDrawablesToInvalidate30fps) {
                drawable.invalidateSelf();
            }
            mDrawablesToInvalidate30fps.clear();
        }

        if (mCounter % 300 == 0) {
            for (int i = 0; i < mGroups.size(); i++) {
                CallbackGroup g = mGroups.valueAt(i);
                try {
                    g.callbacks.isEmpty();
                    g.runnableCallbacks.isEmpty();
                    if (g.runnableCallbacksOnce != null) {
                        g.runnableCallbacksOnce.isEmpty();
                    }
                } catch (Throwable ignored) {}
            }
        }

        mCounter++;
        if (mCounter == TARGET_FPS) {
            mCounter = 0;
        }
    }

    private CallbackGroup getOrCreateGroup(int fps) {
        fps = Math.max(1, Math.min(fps, TARGET_FPS));
        CallbackGroup group = mGroups.get(fps);
        if (group == null) {
            group = new CallbackGroup(FRAME_MASKS[fps - 1]);
            mGroups.put(fps, group);
        }
        return group;
    }


    private static long[] buildFrameMasks() {
        long[] masks = new long[TARGET_FPS];
        for (int index = 0; index < masks.length; index++) {
            masks[index] = buildFrameMask(index + 1);
        }
        return masks;
    }

    private static long buildFrameMask(int fps) {
        int baseFps = 1;
        for (int candidate = 2; candidate <= TARGET_FPS; candidate++) {
            if (TARGET_FPS % candidate == 0
                    && Math.abs(candidate - fps) < Math.abs(baseFps - fps)) {
                baseFps = candidate;
            }
        }

        int stride = TARGET_FPS / baseFps;
        long mask = 0;
        for (int tick = 0; tick < TARGET_FPS; tick += stride) {
            mask |= 1L << tick;
        }

        int correction = fps - baseFps;
        int leapFrames = Math.abs(correction);
        for (int i = 0; i < leapFrames; i++) {
            int targetTick = (2 * i + 1) * TARGET_FPS / (2 * leapFrames);
            for (int distance = 0; distance < TARGET_FPS; distance++) {
                boolean selected = false;
                for (int direction = 0; direction < 2; direction++) {
                    int tick = (targetTick + (direction == 0 ? distance : -distance)
                            + TARGET_FPS) % TARGET_FPS;
                    long bit = 1L << tick;
                    if (correction > 0) {
                        if ((mask & bit) != 0 || (stride % 2 == 0 && tick % 2 == 0)) {
                            continue;
                        }
                        mask |= bit;
                    } else {
                        if ((mask & bit) == 0) {
                            continue;
                        }
                        mask &= ~bit;
                    }
                    selected = true;
                    break;
                }
                if (selected) {
                    break;
                }
            }
        }
        return mask;
    }
    private static final class CallbackGroup {
        final long frameMask;

        final ReferenceList<FrameCallback> callbacks = new ReferenceList<>();
        final ReferenceList<Runnable> runnableCallbacks = new ReferenceList<>();
        @Nullable
        ReferenceList<Runnable> runnableCallbacksOnce;

        CallbackGroup(long frameMask) {
            this.frameMask = frameMask;
        }
    }

    private static void checkMainThread() {
        if (BuildConfig.DEBUG_PRIVATE_VERSION || BuildConfig.DEBUG_VERSION) {
            if (Looper.myLooper() != Looper.getMainLooper()) {
                throw new IllegalStateException("Choreographer60FpsContent must be used on the main thread");
            }
        }
    }
}
