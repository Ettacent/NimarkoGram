/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.utils;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.SurfaceView;
import android.view.ViewTreeObserver;
import android.view.Window;

import androidx.recyclerview.widget.RecyclerView;

import app.nimarkogram.messenger.NimarkoConfig;

import org.telegram.messenger.SharedConfig;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.WeakHashMap;

public final class NimarkoAppMotionBlur implements ViewTreeObserver.OnPreDrawListener,
        View.OnAttachStateChangeListener {
    public interface Excluded {}
    private static final int MAX_VIEWS = 512;
    private static final int MAX_LAYERS = 48;
    private static final long MAX_FRAME_GAP_NS = 120_000_000L;
    private static final WeakHashMap<ViewGroup, WeakReference<NimarkoAppMotionBlur>> INSTANCES = new WeakHashMap<>();
    private static final WeakHashMap<View, WeakReference<Entry>> LAYER_OWNERS = new WeakHashMap<>();
    private static final WeakHashMap<Application, Boolean> APPLICATIONS = new WeakHashMap<>();
    private static final WeakHashMap<Activity, Boolean> PAUSED_ACTIVITIES = new WeakHashMap<>();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final Runnable SETTINGS_CHANGED = NimarkoAppMotionBlur::onSettingsChanged;
    private static final Application.ActivityLifecycleCallbacks ACTIVITY_CALLBACKS = new Application.ActivityLifecycleCallbacks() {
        @Override
        public void onActivityCreated(Activity activity, Bundle state) {
            PAUSED_ACTIVITIES.put(activity, Boolean.TRUE);
            attachWindow(activity.getWindow());
            updateActivityRoots(activity, true, false);
        }

        @Override
        public void onActivityStarted(Activity activity) {
        }

        @Override
        public void onActivityResumed(Activity activity) {
            PAUSED_ACTIVITIES.put(activity, Boolean.FALSE);
            attachWindow(activity.getWindow());
            updateActivityRoots(activity, false, false);
        }

        @Override
        public void onActivityPaused(Activity activity) {
            PAUSED_ACTIVITIES.put(activity, Boolean.TRUE);
            updateActivityRoots(activity, true, false);
        }

        @Override
        public void onActivityStopped(Activity activity) {
            PAUSED_ACTIVITIES.put(activity, Boolean.TRUE);
            updateActivityRoots(activity, true, false);
        }

        @Override
        public void onActivitySaveInstanceState(Activity activity, Bundle state) {
        }

        @Override
        public void onActivityDestroyed(Activity activity) {
            PAUSED_ACTIVITIES.put(activity, Boolean.TRUE);
            updateActivityRoots(activity, true, true);
        }
    };

    private final WeakReference<ViewGroup> rootReference;
    private WeakReference<Activity> ownerReference;
    private final WeakHashMap<View, Entry> snapshots = new WeakHashMap<>();
    private final View[] walkViews = new View[MAX_VIEWS];
    private final Entry[] walkEntries = new Entry[MAX_VIEWS];
    private final Entry[] sampledEntries = new Entry[MAX_VIEWS];
    private final float[] points = new float[8];
    private final float[] vector = new float[2];
    private final Matrix rootToScreen = new Matrix();
    private final int[] rootLocation = new int[2];
    private final Rect clip = new Rect();
    private ViewTreeObserver observer;
    private Object visibilityListener;
    private boolean enabled = true;
    private boolean detached = true;
    private boolean paused;
    private boolean frameScheduled;
    private long frame;
    private long epoch = 1L;
    private long lastFrameNs;
    private int rootWidth;
    private int rootHeight;
    private float density;
    private float minimumArea;
    private int sampledCount;
    private int fingerprintBudget;
    private boolean hasMotion;

    private final Runnable idleFrame = new Runnable() {
        @Override
        public void run() {
            if (!frameScheduled) return;
            frameScheduled = false;
            ViewGroup root = rootReference.get();
            if (root != null && canUse(root) && root.isAttachedToWindow() && root.isShown()
                    && root.getWindowVisibility() == View.VISIBLE) {
                root.invalidate();
            } else {
                reset();
            }
        }
    };

    public static void install(Application app) {
        requireMainThread();
        if (app == null) throw new IllegalArgumentException("app");
        if (Build.VERSION.SDK_INT < 31) return;
        if (APPLICATIONS.containsKey(app)) return;
        app.registerActivityLifecycleCallbacks(ACTIVITY_CALLBACKS);
        APPLICATIONS.put(app, Boolean.TRUE);
    }

    public static NimarkoAppMotionBlur attachWindow(Window window) {
        if (window == null || Build.VERSION.SDK_INT < 31
                || Looper.myLooper() != Looper.getMainLooper()) return null;
        View content = window.findViewById(android.R.id.content);
        if (!(content instanceof ViewGroup)) {
            window.getDecorView();
            content = window.findViewById(android.R.id.content);
        }
        if (!(content instanceof ViewGroup)) return null;
        NimarkoAppMotionBlur blur = attachRoot((ViewGroup) content);
        Activity owner = findActivity(window.getContext());
        if (owner != null) blur.bindOwner(owner);
        return blur;
    }

    public static void onSettingsChanged() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            MAIN_HANDLER.removeCallbacks(SETTINGS_CHANGED);
            MAIN_HANDLER.post(SETTINGS_CHANGED);
            return;
        }
        MAIN_HANDLER.removeCallbacks(SETTINGS_CHANGED);
        for (NimarkoAppMotionBlur blur : collectRoots(null)) blur.invalidate();
    }

    public static boolean isViewMotionBlurred(View view) {
        for (int depth = 0; view != null && depth < 64; depth++) {
            WeakReference<Entry> reference = LAYER_OWNERS.get(view);
            Entry entry = reference == null ? null : reference.get();
            if (entry != null && entry.applied && (entry.contentApplied
                    ? NimarkoContentMotionBlur.isApplied(view) : NimarkoMotionBlurEffect.isApplied(view))) return true;
            view = view.getParent() instanceof View ? (View) view.getParent() : null;
        }
        return false;
    }

    private static void updateActivityRoots(Activity activity, boolean paused, boolean destroy) {
        for (NimarkoAppMotionBlur blur : collectRoots(activity)) {
            if (destroy) {
                blur.detach();
            } else {
                blur.paused = paused;
                blur.reset();
                ViewGroup root = blur.rootReference.get();
                if (root != null && blur.canUse(root)) root.postInvalidateOnAnimation();
            }
        }
    }

    private static ArrayList<NimarkoAppMotionBlur> collectRoots(Activity owner) {
        ArrayList<NimarkoAppMotionBlur> roots = new ArrayList<>();
        for (WeakReference<NimarkoAppMotionBlur> reference : INSTANCES.values()) {
            NimarkoAppMotionBlur blur = reference.get();
            if (blur == null || blur.detached) continue;
            ViewGroup root = blur.rootReference.get();
            if (root == null) continue;
            Activity activity = blur.ownerReference == null ? null : blur.ownerReference.get();
            if (activity == null) activity = findActivity(root.getContext());
            if (owner == null || activity == owner) roots.add(blur);
        }
        return roots;
    }

    private static Activity findActivity(Context context) {
        for (int i = 0; context != null && i < 32; i++) {
            if (context instanceof Activity) return (Activity) context;
            if (!(context instanceof ContextWrapper)) break;
            Context next = ((ContextWrapper) context).getBaseContext();
            if (next == context) break;
            context = next;
        }
        return null;
    }

    private void bindOwner(Activity owner) {
        Activity previous = ownerReference == null ? null : ownerReference.get();
        if (previous != owner) {
            ownerReference = new WeakReference<>(owner);
            reset();
        }
        boolean ownerPaused = Boolean.TRUE.equals(PAUSED_ACTIVITIES.get(owner));
        if (paused != ownerPaused) setPaused(ownerPaused);
    }

    public static NimarkoAppMotionBlur attachRoot(View root) {
        if (Build.VERSION.SDK_INT < 31 || Looper.myLooper() != Looper.getMainLooper()) return null;
        return root instanceof ViewGroup ? attachRoot((ViewGroup) root) : null;
    }

    public static NimarkoAppMotionBlur attachRoot(ViewGroup root) {
        if (root == null || Build.VERSION.SDK_INT < 31
                || Looper.myLooper() != Looper.getMainLooper()) return null;
        WeakReference<NimarkoAppMotionBlur> reference = INSTANCES.get(root);
        NimarkoAppMotionBlur instance = reference == null ? null : reference.get();
        if (instance != null && !instance.detached) return instance;
        instance = new NimarkoAppMotionBlur(root);
        instance.attach();
        return instance;
    }

    public NimarkoAppMotionBlur(ViewGroup root) {
        requireMainThread();
        if (root == null) throw new IllegalArgumentException("root");
        rootReference = new WeakReference<>(root);
        Activity owner = findActivity(root.getContext());
        if (owner != null) {
            ownerReference = new WeakReference<>(owner);
            paused = Boolean.TRUE.equals(PAUSED_ACTIVITIES.get(owner));
        }
    }

    public void attach() {
        requireMainThread();
        ViewGroup root = rootReference.get();
        if (root == null || !detached) return;
        WeakReference<NimarkoAppMotionBlur> reference = INSTANCES.get(root);
        NimarkoAppMotionBlur existing = reference == null ? null : reference.get();
        if (existing != null && existing != this && !existing.detached) {
            throw new IllegalStateException("Root already has a motion blur coordinator");
        }
        reset();
        detached = false;
        INSTANCES.put(root, new WeakReference<>(this));
        root.addOnAttachStateChangeListener(this);
        if (root.isAttachedToWindow()) bindObserver(root);
    }

    private void setPaused(boolean paused) {
        this.paused = paused;
        reset();
    }

    private boolean canUse(ViewGroup root) {
        return !detached && !paused && enabled && Build.VERSION.SDK_INT >= 31
                && NimarkoConfig.motionBlur
                && SharedConfig.getDevicePerformanceClass() >= SharedConfig.PERFORMANCE_CLASS_AVERAGE
                && root.isHardwareAccelerated();
    }

    public void setEnabled(boolean enabled) {
        requireMainThread();
        if (this.enabled == enabled) return;
        this.enabled = enabled;
        invalidate();
    }

    public void invalidate() {
        requireMainThread();
        reset();
        ViewGroup root = rootReference.get();
        if (root != null && canUse(root)) root.postInvalidateOnAnimation();
    }

    public void reset() {
        requireMainThread();
        epoch++;
        lastFrameNs = 0L;
        hasMotion = false;
        for (Entry entry : snapshots.values()) {
            clearEntry(entry);
            entry.motion.reset();
        }
        frameScheduled = false;
        ViewGroup root = rootReference.get();
        if (root != null) {
            root.removeCallbacks(idleFrame);
        }
    }

    public void detach() {
        requireMainThread();
        if (detached) return;
        reset();
        detached = true;
        unbindObserver();
        ViewGroup root = rootReference.get();
        if (root != null) {
            root.removeOnAttachStateChangeListener(this);
            WeakReference<NimarkoAppMotionBlur> reference = INSTANCES.get(root);
            if (reference != null && reference.get() == this) INSTANCES.remove(root);
        }
        snapshots.clear();
        Arrays.fill(walkViews, null);
        Arrays.fill(walkEntries, null);
        Arrays.fill(sampledEntries, null);
    }

    @Override
    public boolean onPreDraw() {
        ViewGroup root = rootReference.get();
        if (root == null || detached) return true;
        if (!canUse(root) || !root.isAttachedToWindow() || !root.isShown()
                || root.getWindowVisibility() != View.VISIBLE || root.getAlpha() <= 0.01f
                || root.getWidth() <= 0 || root.getHeight() <= 0) {
            if (lastFrameNs != 0L || hasMotion) reset();
            return true;
        }
        bindObserver(root);
        long now = System.nanoTime();
        float newDensity = root.getResources().getDisplayMetrics().density;
        if (!Float.isFinite(newDensity) || newDensity <= 0f) {
            reset();
            return true;
        }
        if (rootWidth != root.getWidth() || rootHeight != root.getHeight() || density != newDensity
                || lastFrameNs != 0L && (now <= lastFrameNs || now - lastFrameNs > MAX_FRAME_GAP_NS)) {
            reset();
        }
        rootWidth = root.getWidth();
        rootHeight = root.getHeight();
        density = newDensity;
        minimumArea = Math.max(1f, Math.min(rootWidth * (float) rootHeight * 0.001f, density * density * 256f));
        frame++;
        rootToScreen.reset();
        root.transformMatrixToGlobal(rootToScreen);
        vector[0] = vector[1] = 0f;
        rootToScreen.mapPoints(vector);
        root.getLocationOnScreen(rootLocation);
        rootToScreen.postTranslate(rootLocation[0] - Math.round(vector[0]), rootLocation[1] - Math.round(vector[1]));
        scan(root, now);
        selectLayers();
        pruneSnapshots();
        lastFrameNs = now;
        if (hasMotion && !frameScheduled) {
            frameScheduled = true;
            root.postOnAnimation(idleFrame);
        } else if (!hasMotion) {
            cancelIdleFrame(root);
        }
        return true;
    }

    private void scan(ViewGroup root, long now) {
        int head = 0;
        int tail = 1;
        int visited = 1;
        sampledCount = 0;
        fingerprintBudget = MAX_VIEWS;
        walkViews[0] = root;
        walkEntries[0] = null;
        try {
            while (head < tail) {
                View current = walkViews[head];
                Entry parent = walkEntries[head];
                walkViews[head] = null;
                walkEntries[head] = null;
                head++;
                if (current.getVisibility() != View.VISIBLE || current.getAlpha() <= 0.01f) continue;
                Entry entry = sample(current, parent, now);
                if (entry != null) sampledEntries[sampledCount++] = entry;
                if (!(current instanceof ViewGroup) || entry == null
                        || entry.excluded || entry.childRight <= entry.childLeft || entry.childBottom <= entry.childTop) continue;
                ViewGroup group = (ViewGroup) current;
                entry.childrenComplete = group.getChildCount() <= MAX_VIEWS - visited;
                if (!entry.childrenComplete) entry.split = true;
                for (int index = group.getChildCount() - 1; index >= 0 && visited < MAX_VIEWS; index--) {
                    View child = group.getChildAt(index);
                    visited++;
                    if (child == null || child.getVisibility() != View.VISIBLE || child.getAlpha() <= 0.01f) continue;
                    walkViews[tail] = child;
                    walkEntries[tail] = entry;
                    tail++;
                }
            }
        } finally {
            Arrays.fill(walkViews, null);
            Arrays.fill(walkEntries, null);
        }
    }

    private Entry sample(View view, Entry parent, long now) {
        int width = view.getWidth();
        int height = view.getHeight();
        if (width <= 0 || height <= 0) return null;
        Entry entry = snapshots.get(view);
        if (entry == null) {
            entry = new Entry();
            entry.view = new WeakReference<>(view);
            snapshots.put(view, entry);
        }
        boolean historyContinuous = entry.frame == frame - 1 && entry.epoch == epoch
                && entry.parent == parent && (parent == null || !parent.historyDiscontinuous);
        long stableItemId = RecyclerView.NO_ID;
        int adapterPosition = RecyclerView.NO_POSITION;
        int itemViewType = 0;
        RecyclerView.Adapter adapter = null;
        if (view.getParent() instanceof RecyclerView) {
            RecyclerView recyclerView = (RecyclerView) view.getParent();
            adapter = recyclerView.getAdapter();
            RecyclerView.ViewHolder holder = recyclerView.getChildViewHolder(view);
            if (adapter != null && holder != null) {
                adapterPosition = holder.getAdapterPosition();
                itemViewType = holder.getItemViewType();
                if (adapter.hasStableIds()) stableItemId = holder.getItemId();
                if (adapterPosition == RecyclerView.NO_POSITION
                        || entry.itemViewType != itemViewType
                        || (adapter.hasStableIds() ? entry.stableItemId != stableItemId
                        : entry.adapterPosition != adapterPosition)) historyContinuous = false;
            } else {
                historyContinuous = false;
            }
            if (recyclerView.getScrollState() == RecyclerView.SCROLL_STATE_IDLE && !recyclerView.isAnimating()
                    && (entry.left != view.getLeft() || entry.top != view.getTop())
                    && entry.localMatrix.equals(view.getMatrix())) historyContinuous = false;
        }
        RecyclerView.Adapter previousAdapter = entry.adapter == null ? null : entry.adapter.get();
        if (previousAdapter != adapter) historyContinuous = false;
        entry.historyDiscontinuous = !historyContinuous;
        boolean continuous = historyContinuous;
        entry.relativeMotion = !historyContinuous || entry.left != view.getLeft() || entry.top != view.getTop()
                || entry.width != width || entry.height != height || !entry.localMatrix.equals(view.getMatrix())
                || parent != null && parent.scrolled;
        entry.scrolled = entry.scrollX != view.getScrollX() || entry.scrollY != view.getScrollY();
        entry.split = entry.scrolled && view instanceof ViewGroup;
        WeakReference<NimarkoAppMotionBlur> nested = view == rootReference.get() ? null : INSTANCES.get(view);
        NimarkoAppMotionBlur nestedOwner = nested == null ? null : nested.get();
        entry.excluded = view instanceof SurfaceView
                || view instanceof Excluded
                || nestedOwner != null && !nestedOwner.detached;
        entry.split |= entry.excluded;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            int fingerprint = group.getChildCount();
            for (int index = 0; index < group.getChildCount() && index < 32 && fingerprintBudget > 0; index++) {
                fingerprintBudget--;
                fingerprint = 31 * fingerprint + System.identityHashCode(group.getChildAt(index));
            }
            if (entry.childFingerprint != fingerprint) {
                entry.motion.reset();
                entry.split = true;
            }
            entry.childFingerprint = fingerprint;
        }
        if (parent == null) {
            entry.matrix.reset();
        } else {
            entry.matrix.set(parent.matrix);
            entry.matrix.preTranslate(view.getLeft() - parent.scrollX, view.getTop() - parent.scrollY);
            entry.matrix.preConcat(view.getMatrix());
        }
        entry.corners[0] = entry.corners[6] = 0f;
        entry.corners[1] = entry.corners[3] = 0f;
        entry.corners[2] = entry.corners[4] = width;
        entry.corners[5] = entry.corners[7] = height;
        entry.matrix.mapPoints(entry.corners);
        for (float value : entry.corners) {
            if (!Float.isFinite(value)) {
                entry.frame = 0L;
                return null;
            }
        }
        float left = Math.min(Math.min(entry.corners[0], entry.corners[2]), Math.min(entry.corners[4], entry.corners[6]));
        float top = Math.min(Math.min(entry.corners[1], entry.corners[3]), Math.min(entry.corners[5], entry.corners[7]));
        float right = Math.max(Math.max(entry.corners[0], entry.corners[2]), Math.max(entry.corners[4], entry.corners[6]));
        float bottom = Math.max(Math.max(entry.corners[1], entry.corners[3]), Math.max(entry.corners[5], entry.corners[7]));
        float clipLeft = parent == null ? 0f : parent.childLeft;
        float clipTop = parent == null ? 0f : parent.childTop;
        float clipRight = parent == null ? rootWidth : parent.childRight;
        float clipBottom = parent == null ? rootHeight : parent.childBottom;
        if (view.getClipBounds(clip)) {
            mapBounds(entry.matrix, clip.left, clip.top, clip.right, clip.bottom);
            clipLeft = Math.max(clipLeft, points[0]);
            clipTop = Math.max(clipTop, points[1]);
            clipRight = Math.min(clipRight, points[2]);
            clipBottom = Math.min(clipBottom, points[3]);
        }
        float area = Math.max(0f, Math.min(right, clipRight) - Math.max(left, clipLeft))
                * Math.max(0f, Math.min(bottom, clipBottom) - Math.max(top, clipTop));
        entry.alpha = view.getAlpha() * (parent == null ? 1f : parent.alpha);
        entry.visibleArea = area;
        entry.childLeft = clipLeft;
        entry.childTop = clipTop;
        entry.childRight = clipRight;
        entry.childBottom = clipBottom;
        if (!(view instanceof ViewGroup) || ((ViewGroup) view).getClipChildren()) {
            entry.childLeft = Math.max(entry.childLeft, left);
            entry.childTop = Math.max(entry.childTop, top);
            entry.childRight = Math.min(entry.childRight, right);
            entry.childBottom = Math.min(entry.childBottom, bottom);
        }
        if (view instanceof ViewGroup && ((ViewGroup) view).getClipToPadding()) {
            mapBounds(entry.matrix, view.getPaddingLeft(), view.getPaddingTop(),
                    width - view.getPaddingRight(), height - view.getPaddingBottom());
            entry.childLeft = Math.max(entry.childLeft, points[0]);
            entry.childTop = Math.max(entry.childTop, points[1]);
            entry.childRight = Math.min(entry.childRight, points[2]);
            entry.childBottom = Math.min(entry.childBottom, points[3]);
        }
        float largestX = 0f;
        float largestY = 0f;
        float largest = 0f;
        int largestCorner = 0;
        entry.screenMatrix.set(rootToScreen);
        entry.screenMatrix.preConcat(entry.matrix);
        if (!entry.screenMatrix.invert(entry.inverse)) continuous = false;
        points[0] = points[6] = 0f;
        points[1] = points[3] = 0f;
        points[2] = points[4] = width;
        points[5] = points[7] = height;
        if (continuous) {
            entry.previousScreenMatrix.mapPoints(points);
            entry.inverse.mapPoints(points);
            for (int i = 0; i < 8; i += 2) {
                float x = (i == 2 || i == 4 ? width : 0f) - points[i];
                float y = (i >= 4 ? height : 0f) - points[i + 1];
                if (!(view instanceof ViewGroup)) {
                    x += entry.scrollX - view.getScrollX();
                    y += entry.scrollY - view.getScrollY();
                }
                entry.displacement[i] = x;
                entry.displacement[i + 1] = y;
                float distance = x * x + y * y;
                if (!Float.isFinite(distance)) continuous = false;
                if (distance > largest) {
                    largest = distance;
                    largestX = x;
                    largestY = y;
                    largestCorner = i;
                }
            }
        }
        entry.covered = false;
        entry.selected = false;
        entry.eligible = continuous && !entry.excluded && area * entry.alpha >= minimumArea
                && largest <= Math.max(rootWidth, rootHeight) * (double) Math.max(rootWidth, rootHeight) * 0.25d;
        if (entry.eligible && lastFrameNs != 0L) {
            entry.motion.prime(lastFrameNs, density);
            if (largest >= 0.0001f) {
                float magnitude = (float) Math.sqrt(largest);
                float trackedX = entry.displacement[entry.trackedCorner];
                float trackedY = entry.displacement[entry.trackedCorner + 1];
                float trackedLength = (float) Math.hypot(trackedX, trackedY);
                if (trackedLength * trackedLength >= largest * 0.5f) {
                    largestX = trackedX * magnitude / trackedLength;
                    largestY = trackedY * magnitude / trackedLength;
                } else {
                    entry.trackedCorner = largestCorner;
                }
                for (int i = 0; i < 8; i++) entry.shape[i] = entry.displacement[i] / magnitude;
                entry.motion.update(largestX, largestY, now, density);
            } else {
                entry.motion.update(0f, 0f, now, density);
            }
            entry.motion.tick(now);
        } else {
            entry.motion.reset();
        }
        entry.independent = historyContinuous && (entry.relativeMotion
                || entry.independent && (entry.motion.isActive() || parent != null && parent.motion.isActive()));
        entry.previousScreenMatrix.set(entry.screenMatrix);
        entry.parent = parent;
        entry.width = width;
        entry.height = height;
        entry.scrollX = view.getScrollX();
        entry.scrollY = view.getScrollY();
        entry.frame = frame;
        entry.epoch = epoch;
        entry.stableItemId = stableItemId;
        entry.adapterPosition = adapterPosition;
        entry.itemViewType = itemViewType;
        if (previousAdapter != adapter) entry.adapter = adapter == null ? null : new WeakReference<>(adapter);
        entry.left = view.getLeft();
        entry.top = view.getTop();
        entry.localMatrix.set(view.getMatrix());
        return entry;
    }

    private void selectLayers() {
        for (int i = sampledCount - 1; i >= 0; i--) {
            Entry entry = sampledEntries[i];
            if (entry.parent != null && (entry.relativeMotion || entry.independent || entry.split)) entry.parent.split = true;
        }
        int layers = 0;
        double remainingPixels = rootWidth * (double) rootHeight * 2d;
        float maximumSpan = SharedConfig.getDevicePerformanceClass() >= SharedConfig.PERFORMANCE_CLASS_HIGH ? 16f : 8f;
        for (int i = 0; i < sampledCount; i++) {
            Entry entry = sampledEntries[i];
            entry.aggregate = false;
            entry.aggregateActive = false;
            float length = Math.min(maximumSpan, entry.motion.getLength());
            for (int j = 0; j < 8; j++) entry.span[j] = entry.shape[j] * length;
        }
        for (int i = sampledCount - 1; i >= 0; i--) {
            Entry entry = sampledEntries[i];
            View view = entry.view.get();
            if (view instanceof RecyclerView && view instanceof NimarkoContentMotionBlur.Target
                    && ((NimarkoContentMotionBlur.Target) view).canBlurMotionContent()
                    && !((RecyclerView) view).isAnimating()) {
                coalesceScrollingChildren(entry);
            }
        }
        hasMotion = false;
        for (int i = 0; i < sampledCount; i++) {
            Entry entry = sampledEntries[i];
            entry.covered = entry.parent != null && (entry.parent.selected || entry.parent.covered);
            View view = entry.view.get();
            double cost = entry.width * (double) entry.height;
            if (view != null && (entry.aggregate || entry.eligible && !entry.split)
                    && !entry.covered && layers < MAX_LAYERS && cost <= remainingPixels
                    && (entry.aggregate || entry.motion.getLength() > 0f)) {
                entry.selected = true;
                layers++;
                remainingPixels -= cost;
                hasMotion |= entry.aggregate ? entry.aggregateActive : entry.motion.isActive();
            }
        }
        for (Entry entry : snapshots.values()) {
            if (entry.frame != frame || !entry.selected) clearEntry(entry);
        }
        for (int i = 0; i < sampledCount; i++) {
            Entry entry = sampledEntries[i];
            if (entry.selected) {
                View view = entry.view.get();
                if (view != null) {
                    if (entry.applied && entry.contentApplied != entry.aggregate) clearEntry(entry);
                    if (entry.aggregate) {
                        NimarkoContentMotionBlur.apply(view, entry.span);
                    } else {
                        NimarkoMotionBlurEffect.apply(view, entry.span);
                    }
                    boolean applied = entry.aggregate ? NimarkoContentMotionBlur.isApplied(view)
                            : NimarkoMotionBlurEffect.isApplied(view);
                    if (!applied) {
                        clearEntry(entry);
                        entry.selected = false;
                        continue;
                    }
                    WeakReference<Entry> owner = LAYER_OWNERS.get(view);
                    if (owner == null || owner.get() != entry) LAYER_OWNERS.put(view, new WeakReference<>(entry));
                    entry.applied = true;
                    entry.contentApplied = entry.aggregate;
                }
            }
        }
        layers = 0;
        remainingPixels = rootWidth * (double) rootHeight * 2d;
        for (int i = 0; i < sampledCount; i++) {
            Entry entry = sampledEntries[i];
            if (entry.selected && entry.applied) {
                layers++;
                remainingPixels -= entry.width * (double) entry.height;
            }
        }
        for (int i = 0; i < sampledCount; i++) {
            Entry entry = sampledEntries[i];
            entry.covered = entry.parent != null && (entry.parent.selected || entry.parent.covered);
            View view = entry.view.get();
            double cost = entry.width * (double) entry.height;
            if (!entry.selected && !entry.covered && view != null && entry.eligible && !entry.split
                    && entry.motion.getLength() > 0f && layers < MAX_LAYERS && cost <= remainingPixels) {
                NimarkoMotionBlurEffect.apply(view, entry.span);
                if (NimarkoMotionBlurEffect.isApplied(view)) {
                    entry.selected = entry.applied = true;
                    entry.contentApplied = false;
                    LAYER_OWNERS.put(view, new WeakReference<>(entry));
                    remainingPixels -= cost;
                    layers++;
                    hasMotion |= entry.motion.isActive();
                }
            }
            sampledEntries[i] = null;
        }
    }

    private void coalesceScrollingChildren(Entry parent) {
        if (parent.historyDiscontinuous || parent.excluded || !parent.childrenComplete) return;
        int count = 0;
        float referenceX = 0f, referenceY = 0f, sumX = 0f, sumY = 0f;
        float referenceSpanX = 0f, referenceSpanY = 0f;
        boolean active = false;
        for (int i = 0; i < sampledCount; i++) {
            Entry child = sampledEntries[i];
            if (child.parent != parent || child.visibleArea <= 0f) continue;
            if (child.historyDiscontinuous || child.excluded || child.split || !child.eligible
                    || child.motion.getLength() <= 0f) return;
            for (int j = 2; j < 8; j += 2) {
                if (Math.hypot(child.displacement[j] - child.displacement[0],
                        child.displacement[j + 1] - child.displacement[1]) > 0.25f * density) return;
                if (Math.hypot(child.span[j] - child.span[0], child.span[j + 1] - child.span[1])
                        > Math.max(0.1d, Math.hypot(child.span[0], child.span[1]) * 0.1d)) return;
            }
            mapChildVector(child, parent, child.displacement[0], child.displacement[1]);
            float x = vector[0], y = vector[1];
            if (count == 0) {
                referenceX = x;
                referenceY = y;
            } else if (Math.hypot(x - referenceX, y - referenceY)
                    > Math.max(0.25f * density, Math.hypot(referenceX, referenceY) * 0.05d)) {
                return;
            }
            mapChildVector(child, parent, child.span[0], child.span[1]);
            if (count == 0) {
                referenceSpanX = vector[0];
                referenceSpanY = vector[1];
            } else if (Math.hypot(vector[0] - referenceSpanX, vector[1] - referenceSpanY)
                    > Math.max(0.1d, Math.hypot(referenceSpanX, referenceSpanY) * 0.1d)) {
                return;
            }
            sumX += vector[0];
            sumY += vector[1];
            active |= child.motion.isActive();
            count++;
        }
        if (count < 2) return;
        float x = sumX / count, y = sumY / count;
        if (!Float.isFinite(x) || !Float.isFinite(y)
                || Math.hypot(x, y) < NimarkoMotionBlurEffect.MIN_SPAN_PX) return;
        for (int i = 0; i < 8; i += 2) {
            parent.span[i] = x;
            parent.span[i + 1] = y;
        }
        parent.aggregate = true;
        parent.aggregateActive = active;
    }

    private void mapChildVector(Entry child, Entry parent, float x, float y) {
        Arrays.fill(points, 0f);
        points[2] = x;
        points[3] = y;
        child.screenMatrix.mapPoints(points);
        parent.inverse.mapPoints(points);
        vector[0] = points[2] - points[0];
        vector[1] = points[3] - points[1];
    }

    private void clearEntry(Entry entry) {
        if (!entry.applied) return;
        View view = entry.view.get();
        WeakReference<Entry> owner = view == null ? null : LAYER_OWNERS.get(view);
        if (owner != null && owner.get() == entry) {
            if (entry.contentApplied) {
                NimarkoContentMotionBlur.clear(view);
            } else {
                NimarkoMotionBlurEffect.clear(view);
            }
            LAYER_OWNERS.remove(view);
        }
        entry.applied = false;
    }

    private void mapBounds(Matrix matrix, float left, float top, float right, float bottom) {
        points[0] = points[6] = left;
        points[1] = points[3] = top;
        points[2] = points[4] = right;
        points[5] = points[7] = bottom;
        matrix.mapPoints(points);
        float x1 = Math.min(Math.min(points[0], points[2]), Math.min(points[4], points[6]));
        float y1 = Math.min(Math.min(points[1], points[3]), Math.min(points[5], points[7]));
        float x2 = Math.max(Math.max(points[0], points[2]), Math.max(points[4], points[6]));
        float y2 = Math.max(Math.max(points[1], points[3]), Math.max(points[5], points[7]));
        points[0] = x1;
        points[1] = y1;
        points[2] = x2;
        points[3] = y2;
    }

    private void pruneSnapshots() {
        if (snapshots.size() <= MAX_VIEWS) return;
        Iterator<Entry> iterator = snapshots.values().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().frame != frame) iterator.remove();
        }
    }

    private void cancelIdleFrame(ViewGroup root) {
        frameScheduled = false;
        root.removeCallbacks(idleFrame);
    }

    private void bindObserver(ViewGroup root) {
        ViewTreeObserver next = root.getViewTreeObserver();
        if (observer == next && next.isAlive()) return;
        unbindObserver();
        if (next.isAlive()) {
            observer = next;
            observer.addOnPreDrawListener(this);
            if (Build.VERSION.SDK_INT >= 34) {
                visibilityListener = WindowVisibility34.register(observer, this);
            }
        }
    }

    private void unbindObserver() {
        if (observer != null && observer.isAlive()) {
            observer.removeOnPreDrawListener(this);
            if (Build.VERSION.SDK_INT >= 34 && visibilityListener != null) {
                WindowVisibility34.unregister(observer, visibilityListener);
            }
        }
        visibilityListener = null;
        observer = null;
    }

    @Override
    public void onViewAttachedToWindow(View view) {
        reset();
        bindObserver((ViewGroup) view);
    }

    @Override
    public void onViewDetachedFromWindow(View view) {
        reset();
        unbindObserver();
    }

    public void onWindowVisibilityChanged(int visibility) {
        if (visibility != View.VISIBLE) reset();
    }

    private static void requireMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Main thread required");
    }

    private static final class WindowVisibility34 {
        static Object register(ViewTreeObserver observer, NimarkoAppMotionBlur owner) {
            ViewTreeObserver.OnWindowVisibilityChangeListener listener = owner::onWindowVisibilityChanged;
            observer.addOnWindowVisibilityChangeListener(listener);
            return listener;
        }

        static void unregister(ViewTreeObserver observer, Object listener) {
            observer.removeOnWindowVisibilityChangeListener(
                    (ViewTreeObserver.OnWindowVisibilityChangeListener) listener);
        }
    }

    private static final class Entry {
        final Matrix matrix = new Matrix();
        final Matrix localMatrix = new Matrix();
        final Matrix screenMatrix = new Matrix();
        final Matrix previousScreenMatrix = new Matrix();
        final Matrix inverse = new Matrix();
        final NimarkoMotionBlurState motion = new NimarkoMotionBlurState();
        final float[] corners = new float[8];
        final float[] displacement = new float[8];
        final float[] shape = new float[8];
        final float[] span = new float[8];
        WeakReference<View> view;
        Entry parent;
        long frame;
        long epoch;
        long stableItemId = RecyclerView.NO_ID;
        WeakReference<RecyclerView.Adapter> adapter;
        int adapterPosition = RecyclerView.NO_POSITION;
        int itemViewType;
        int left;
        int top;
        int width;
        int height;
        int scrollX;
        int scrollY;
        int childFingerprint;
        int trackedCorner;
        float alpha;
        float visibleArea;
        float childLeft;
        float childTop;
        float childRight;
        float childBottom;
        boolean covered;
        boolean historyDiscontinuous;
        boolean scrolled;
        boolean relativeMotion;
        boolean independent;
        boolean split;
        boolean excluded;
        boolean eligible;
        boolean selected;
        boolean applied;
        boolean contentApplied;
        boolean childrenComplete;
        boolean aggregate;
        boolean aggregateActive;
    }
}
