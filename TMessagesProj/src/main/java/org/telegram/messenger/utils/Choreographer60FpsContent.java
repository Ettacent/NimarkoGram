/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.messenger.utils;

import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Choreographer;
import android.view.View;

import android.util.SparseArray;

import androidx.annotation.Nullable;

import java.util.ArrayDeque;
import java.util.Iterator;

import java.util.LinkedHashSet;
import java.util.Set;

import java.util.function.Consumer;
import me.vkryl.core.reference.ReferenceList;

public final class Choreographer60FpsContent implements Choreographer.FrameCallback {


    private static final int  TARGET_FPS        = 60;

    private static final long FRAME_INTERVAL_NS = 1_000_000_000L / TARGET_FPS;


    private static final long[] FRAME_MASKS = buildFrameMasks();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    private static final class InstanceHolder {
        static final Choreographer60FpsContent INSTANCE = new Choreographer60FpsContent();
    }

    public static Choreographer60FpsContent getInstance() {
        return InstanceHolder.INSTANCE;
    }


    private Choreographer mChoreographer;

    private static final int POST = 0, INVALIDATE_DRAWABLE = 1, INVALIDATE_DRAWABLE_30 = 2,
            INVALIDATE_VIEW = 3, ADD_ONCE = 4, ADD_RUNNABLE = 5, ADD_CALLBACK = 6,
            REMOVE_RUNNABLE = 7, REMOVE_ONCE = 8, REMOVE_CALLBACK = 9, RECONCILE = 10;
    private static final class Command {
        final int operation;
        final Object target;
        final long value;

        Command(int operation, Object target, long value) {
            this.operation = operation;
            this.target = target;
            this.value = value;
        }
    }
    private final ArrayDeque<Command> mCommands = new ArrayDeque<>();
    private final Runnable mDrainCommands = () -> {
        synchronized (mCommands) {
            mDrainPosted = false;
        }
        drainCommands();
    };
    private boolean mDrainPosted;

    private long mDispatchFrameTimeNs;
    private final Consumer<FrameCallback> mRunFrameCallback = callback -> callback.doFrame(mDispatchFrameTimeNs);
    private static final Consumer<Runnable> RUN_CALLBACK = Runnable::run;
    private static final Consumer<View> INVALIDATE = View::invalidate;
    private static final Consumer<Drawable> INVALIDATE_SELF = Drawable::invalidateSelf;

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
    private boolean mPreparingFrame;
    private boolean mDispatching;

    private long mAccumulatedNs;

    private long mLastVsyncNs;

    private int mCounter;


    public interface FrameCallback {
        void doFrame(long frameTimeNanos);
    }

    public void post(FrameCallback callback) {
        submit(POST, callback, 0);
    }

    private void postOnMain(FrameCallback callback) {
        if (callback != null) {
        mOneShot.add(callback);
            scheduleFrame();
        }
    }

    public void postInvalidateDrawable(Drawable drawable) {
        submit(INVALIDATE_DRAWABLE, drawable, 0);
    }

    private void postInvalidateDrawableOnMain(Drawable drawable) {
        if (drawable != null) {
        mDrawablesToInvalidate.add(drawable);
            scheduleFrame();
        }
    }

    public void postInvalidateDrawable30fps(Drawable drawable) {
        submit(INVALIDATE_DRAWABLE_30, drawable, 0);
    }

    private void postInvalidateDrawable30fpsOnMain(Drawable drawable) {
        if (drawable != null) {
        mDrawablesToInvalidate30fps.add(drawable);
            scheduleFrame();
        }
    }

    public void postInvalidateView(View view) {
        submit(INVALIDATE_VIEW, view, 0);
    }

    private void postInvalidateViewOnMain(View view) {
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
        submit(ADD_ONCE, callback, fps);
    }

    private void addFrameCallbackOnceOnMain(Runnable callback, int fps) {
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
        submit(ADD_RUNNABLE, callback, fps);
    }

    private void addRunnableOnMain(Runnable callback, int fps) {
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
        submit(ADD_CALLBACK, callback, fps);
    }

    private void addFrameCallbackOnMain(FrameCallback callback, int fps) {
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
        submit(REMOVE_RUNNABLE, callback, 0);
    }

    private void removeRunnableOnMain(Runnable callback) {
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
        submit(REMOVE_ONCE, callback, 0);
    }

    private void removeFrameCallbackOnceOnMain(Runnable callback) {
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
        submit(REMOVE_CALLBACK, callback, 0);
    }

    private void removeFrameCallbackOnMain(FrameCallback callback) {
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

    private void submit(int operation, Object target, long value) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            drainCommands();
            execute(operation, target, value);
        } else {
            synchronized (mCommands) {
                mCommands.addLast(new Command(operation, target, value));
                if (!mDrainPosted) {
                    mDrainPosted = true;
                    if (!MAIN_HANDLER.post(mDrainCommands)) {
                        mDrainPosted = false;
                        throw new IllegalStateException("Main looper rejected frame scheduler mutation");
                    }
                }
            }
        }
    }

    private void drainCommands() {
        checkMainThread();
        final int count;
        synchronized (mCommands) {
            count = mCommands.size();
        }
        try {
            for (int i = 0; i < count; i++) {
                final Command command;
                synchronized (mCommands) {
                    command = mCommands.pollFirst();
                    if (command == null) return;
                }
                execute(command.operation, command.target, command.value);
            }
        } finally {
            synchronized (mCommands) {
                if (!mCommands.isEmpty() && !mDrainPosted) {
                    mDrainPosted = true;
                    if (!MAIN_HANDLER.post(mDrainCommands)) {
                        mDrainPosted = false;
                        throw new IllegalStateException("Main looper rejected frame scheduler mutation");
                    }
                }
            }
        }
    }

    private void execute(int operation, Object target, long value) {
        checkMainThread();
        switch (operation) {
            case POST: postOnMain((FrameCallback) target); break;
            case INVALIDATE_DRAWABLE: postInvalidateDrawableOnMain((Drawable) target); break;
            case INVALIDATE_DRAWABLE_30: postInvalidateDrawable30fpsOnMain((Drawable) target); break;
            case INVALIDATE_VIEW: postInvalidateViewOnMain((View) target); break;
            case ADD_ONCE: addFrameCallbackOnceOnMain((Runnable) target, (int) value); break;
            case ADD_RUNNABLE: addRunnableOnMain((Runnable) target, (int) value); break;
            case ADD_CALLBACK: addFrameCallbackOnMain((FrameCallback) target, (int) value); break;
            case REMOVE_RUNNABLE: removeRunnableOnMain((Runnable) target); break;
            case REMOVE_ONCE: removeFrameCallbackOnceOnMain((Runnable) target); break;
            case REMOVE_CALLBACK: removeFrameCallbackOnMain((FrameCallback) target); break;
            case RECONCILE: updateScheduling(); break;
            default: throw new IllegalArgumentException("Unknown frame scheduler operation");
        }
    }

    private Choreographer choreographer() {
        if (mChoreographer == null) mChoreographer = Choreographer.getInstance();
        return mChoreographer;
    }

    private static <T> void dispatchAll(ReferenceList<T> list, Consumer<T> action) {
        final Iterator<T> iterator = list.iterator();
        boolean exhausted = false;
        try {
            while (iterator.hasNext()) action.accept(iterator.next());
            exhausted = true;
        } finally {
            if (!exhausted) while (iterator.hasNext()) iterator.next();
        }
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
        if (!mPreparingFrame && !mDispatching && !mFrameScheduled) {
            mFrameScheduled = true;
            choreographer().postFrameCallback(this);
        }
    }

    private void updateScheduling() {
        if (mPreparingFrame || mDispatching) {
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
        if (Looper.myLooper() == Looper.getMainLooper()) {
            doFrameOnMain(frameTimeNanos);
        } else {
            submit(RECONCILE, null, 0);
        }
    }

    private void doFrameOnMain(long frameTimeNanos) {
        checkMainThread();
        if (mPreparingFrame || mDispatching) return;
        mFrameScheduled = false;
        mPreparingFrame = true;
        try {
            drainCommands();
            mPreparingFrame = false;
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

        } finally {
            mPreparingFrame = false;
        mDispatching = false;
        updateScheduling();
    }

    }
    private void dispatchFrame(long frameTimeNanos) {
        mDispatchFrameTimeNs = frameTimeNanos;
        final boolean draw30fps = mCounter % 2 == 0;
        final int groupCount = mGroups.size();
        try {
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
            if ((group.frameMask & tickMask) != 0) {
                if (group.dispatchOnce != null) {
                        try {
                            dispatchAll(group.dispatchOnce, RUN_CALLBACK);
                        } finally {
                    group.dispatchOnce.clear();
                    group.dispatchOnce = null;
                }

                    }
                    dispatchAll(group.callbacks, mRunFrameCallback);
                    dispatchAll(group.runnableCallbacks, RUN_CALLBACK);
            }
        }

        Set<FrameCallback> oneShot = mOneShot;
        mOneShot = mDispatchOneShot;
        mDispatchOneShot = oneShot;
            try {
                for (FrameCallback cb : oneShot) cb.doFrame(frameTimeNanos);
            } finally {

        oneShot.clear();
            }
        ReferenceList<View> views = mViewsToInvalidate;
        mViewsToInvalidate = mDispatchViews;
        mDispatchViews = views;
            try {
                dispatchAll(views, INVALIDATE);
            } finally {
        views.clear();
            }
        ReferenceList<Drawable> drawables = mDrawablesToInvalidate;
        mDrawablesToInvalidate = mDispatchDrawables;
        mDispatchDrawables = drawables;
            try {
                dispatchAll(drawables, INVALIDATE_SELF);
            } finally {
        drawables.clear();
            }
        if (draw30fps) {
            ReferenceList<Drawable> drawables30fps = mDrawablesToInvalidate30fps;
            mDrawablesToInvalidate30fps = mDispatchDrawables30fps;
            mDispatchDrawables30fps = drawables30fps;
                try {
                    dispatchAll(drawables30fps, INVALIDATE_SELF);
                } finally {
            drawables30fps.clear();
                }
            }
        } finally {
            for (int i = 0; i < groupCount; i++) {
                CallbackGroup group = mDispatchGroups[i];
                mDispatchGroups[i] = null;
                if (group != null && group.dispatchOnce != null) {
                    if (group.runnableCallbacksOnce == null) {
                        group.runnableCallbacksOnce = group.dispatchOnce;
                    } else {
                        group.runnableCallbacksOnce.addAll(group.dispatchOnce);
                    }
                    group.dispatchOnce = null;
                }
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
            if (mCounter == TARGET_FPS) mCounter = 0;
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
            if (Looper.myLooper() != Looper.getMainLooper()) {
            throw new IllegalStateException("Frame scheduler state must be used on the main thread");
        }
    }
}
