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

    private Set<FrameCallback> mOneShot = new LinkedHashSet<>();
    private Set<FrameCallback> mDispatchOneShot = new LinkedHashSet<>();

    private final SparseArray<CallbackGroup> mGroups = new SparseArray<>();

    private final CallbackGroup[] mDispatchGroups = new CallbackGroup[TARGET_FPS];

    private ReferenceList<Drawable> mDrawablesToInvalidate      = new ReferenceList<>();
    private ReferenceList<Drawable> mDrawablesToInvalidate30fps = new ReferenceList<>();
    private ReferenceList<View>     mViewsToInvalidate          = new ReferenceList<>();

    private ReferenceList<Drawable> mDispatchDrawables      = new ReferenceList<>();
    private ReferenceList<Drawable> mDispatchDrawables30fps = new ReferenceList<>();
    private ReferenceList<View> mDispatchViews = new ReferenceList<>();

    private boolean mFrameScheduled;
    private boolean mDispatching;

    private long mAccumulatedNs;

    private long mLastVsyncNs;

    private int mCounter;


    public interface FrameCallback {
        void doFrame(long frameTimeNanos);
    }

    public void post(FrameCallback callback) {
        checkMainThread();
        if (callback != null) {
        mOneShot.add(callback);
            scheduleFrame();
        }
    }

    public void postInvalidateDrawable(Drawable drawable) {
        checkMainThread();
        if (drawable != null) {
        mDrawablesToInvalidate.add(drawable);
            scheduleFrame();
        }
    }

    public void postInvalidateDrawable30fps(Drawable drawable) {
        checkMainThread();
        if (drawable != null) {
        mDrawablesToInvalidate30fps.add(drawable);
            scheduleFrame();
        }
    }

    public void postInvalidateView(View view) {
        checkMainThread();
        if (view != null) {
        mViewsToInvalidate.add(view);
            scheduleFrame();
        }
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
        removeFrameCallbackOnceInternal(callback);
        CallbackGroup group = getOrCreateGroup(fps);
        if (group.runnableCallbacksOnce == null) {
            group.runnableCallbacksOnce = new ReferenceList<>();
        }
        group.runnableCallbacksOnce.add(callback);
        scheduleFrame();
    }


    public void addFrameCallback(Runnable callback, int fps) {
        checkMainThread();
        if (callback == null) {
            return;
        }
        fps = Math.max(1, Math.min(fps, TARGET_FPS));
        removeFrameCallbackInternal(callback);
        CallbackGroup group = getOrCreateGroup(fps);
        if (mDispatching) {
            if (group.pendingRunnables == null) {
                group.pendingRunnables = new ReferenceList<>();
            }
            group.pendingRunnables.add(callback);
        } else {
            group.runnableCallbacks.add(callback);
        }
        scheduleFrame();
    }

    public void addFrameCallback(FrameCallback callback, int fps) {
        checkMainThread();
        if (callback == null) {
            return;
        }
        fps = Math.max(1, Math.min(fps, TARGET_FPS));
        removeFrameCallbackInternal(callback);
        CallbackGroup group = getOrCreateGroup(fps);
        if (mDispatching) {
            if (group.pendingCallbacks == null) {
                group.pendingCallbacks = new ReferenceList<>();
            }
            group.pendingCallbacks.add(callback);
        } else {
            group.callbacks.add(callback);
        }
        scheduleFrame();
    }

    public void removeFrameCallback(Runnable callback) {
        checkMainThread();
        if (callback == null) {
            return;
        }
        removeFrameCallbackInternal(callback);
        updateScheduling();
    }

    private void removeFrameCallbackInternal(Runnable callback) {
        for (int i = 0; i < mGroups.size(); i++) {
            CallbackGroup group = mGroups.valueAt(i);
            group.runnableCallbacks.remove(callback);
            if (group.pendingRunnables != null) {
                group.pendingRunnables.remove(callback);
            }
        }
    }

    public void removeFrameCallbackOnce(Runnable callback) {
        checkMainThread();
        if (callback == null) {
            return;
        }
        removeFrameCallbackOnceInternal(callback);
        updateScheduling();
    }

    private void removeFrameCallbackOnceInternal(Runnable callback) {
        for (int i = 0; i < mGroups.size(); i++) {
            CallbackGroup group = mGroups.valueAt(i);
            if (group.runnableCallbacksOnce != null) {
                group.runnableCallbacksOnce.remove(callback);
            }
            if (group.dispatchOnce != null) {
                group.dispatchOnce.remove(callback);
            }
        }
    }

    public void removeFrameCallback(FrameCallback callback) {
        checkMainThread();
        if (callback == null) {
            return;
        }
        removeFrameCallbackInternal(callback);
        updateScheduling();
    }

    private void removeFrameCallbackInternal(FrameCallback callback) {
        for (int i = 0; i < mGroups.size(); i++) {
            CallbackGroup group = mGroups.valueAt(i);
            group.callbacks.remove(callback);
            if (group.pendingCallbacks != null) {
                group.pendingCallbacks.remove(callback);
            }
        }
    }


    private Choreographer60FpsContent() {
    }

    private static boolean hasLiveReferences(ReferenceList<?> list) {
        list.isEmpty();
        boolean live = false;
        for (Object ignored : list) {
            live = true;
        }
        if (!live) {
            list.clear();
        }
        return live;
    }

    private void scheduleFrame() {
        if (!mDispatching && !mFrameScheduled) {
            mFrameScheduled = true;
        mChoreographer.postFrameCallback(this);
        }
    }

    private void updateScheduling() {
        if (mDispatching) {
            return;
        }
        boolean hasWork = !mOneShot.isEmpty();
        hasWork |= hasLiveReferences(mViewsToInvalidate);
        hasWork |= hasLiveReferences(mDrawablesToInvalidate);
        hasWork |= hasLiveReferences(mDrawablesToInvalidate30fps);
        for (int i = mGroups.size() - 1; i >= 0; i--) {
            CallbackGroup group = mGroups.valueAt(i);
            boolean live = hasLiveReferences(group.callbacks);
            live |= hasLiveReferences(group.runnableCallbacks);
            if (group.runnableCallbacksOnce != null) {
                live |= hasLiveReferences(group.runnableCallbacksOnce);
            }
            if (!live) {
                mGroups.removeAt(i);
            }
            hasWork |= live;
        }
        if (hasWork) {
            scheduleFrame();
        } else {
            if (mFrameScheduled) {
                mChoreographer.removeFrameCallback(this);
                mFrameScheduled = false;
            }
            mLastVsyncNs = 0;
            mAccumulatedNs = 0;
        }
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        mFrameScheduled = false;
        mDispatching = true;
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

        mDispatching = false;
        updateScheduling();
    }

    private void dispatchFrame(long frameTimeNanos) {
        final boolean draw30fps = mCounter % 2 == 0;
        final int groupCount = mGroups.size();
        for (int i = 0; i < groupCount; i++) {
            CallbackGroup group = mGroups.valueAt(i);
            mDispatchGroups[i] = group;
            if ((group.frameMask & (1L << mCounter)) != 0) {
                group.dispatchOnce = group.runnableCallbacksOnce;
                group.runnableCallbacksOnce = null;
            }
        }
        final long tickMask = 1L << mCounter;
        for (int i = 0; i < groupCount; i++) {
            CallbackGroup group = mDispatchGroups[i];
            mDispatchGroups[i] = null;
            if ((group.frameMask & tickMask) != 0) {
                if (group.dispatchOnce != null) {
                    for (Runnable runnable : group.dispatchOnce) {
                        runnable.run();
                    }
                    group.dispatchOnce.clear();
                    group.dispatchOnce = null;
                }

                for (FrameCallback cb : group.callbacks) {
                    cb.doFrame(frameTimeNanos);
                }
                for (Runnable runnable : group.runnableCallbacks) {
                    runnable.run();
                }
            }
        }

        Set<FrameCallback> oneShot = mOneShot;
        mOneShot = mDispatchOneShot;
        mDispatchOneShot = oneShot;
        for (FrameCallback cb : oneShot) {
            cb.doFrame(frameTimeNanos);
        }

        oneShot.clear();
        ReferenceList<View> views = mViewsToInvalidate;
        mViewsToInvalidate = mDispatchViews;
        mDispatchViews = views;
        for (View view : views) {
            view.invalidate();
        }
        views.clear();
        ReferenceList<Drawable> drawables = mDrawablesToInvalidate;
        mDrawablesToInvalidate = mDispatchDrawables;
        mDispatchDrawables = drawables;
        for (Drawable drawable : drawables) {
            drawable.invalidateSelf();
        }
        drawables.clear();
        if (draw30fps) {
            ReferenceList<Drawable> drawables30fps = mDrawablesToInvalidate30fps;
            mDrawablesToInvalidate30fps = mDispatchDrawables30fps;
            mDispatchDrawables30fps = drawables30fps;
            for (Drawable drawable : drawables30fps) {
                drawable.invalidateSelf();
            }
            drawables30fps.clear();
        }
            for (int i = 0; i < mGroups.size(); i++) {
            CallbackGroup group = mGroups.valueAt(i);
            if (group.pendingCallbacks != null) {
                group.callbacks.addAll(group.pendingCallbacks);
                group.pendingCallbacks.clear();
            }
            if (group.pendingRunnables != null) {
                group.runnableCallbacks.addAll(group.pendingRunnables);
                group.pendingRunnables.clear();
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

        @Nullable
        ReferenceList<Runnable> dispatchOnce;
        @Nullable
        ReferenceList<FrameCallback> pendingCallbacks;
        @Nullable
        ReferenceList<Runnable> pendingRunnables;
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
