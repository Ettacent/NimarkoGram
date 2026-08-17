package org.telegram.ui.Components.chat;

import android.graphics.PointF;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;

import androidx.annotation.NonNull;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

public final class ViewPositionWatcher implements
        ViewTreeObserver.OnPreDrawListener,
        View.OnAttachStateChangeListener {

    public interface OnChangedListener {
        void onPositionChanged(@NonNull View view, @NonNull RectF rectInParent);
    }

    private final View anchorView;
    private ViewTreeObserver vto;
    private boolean listening;

    private static final class ListenerRegistration {
        final OnChangedListener listener;
        final RectF callbackRect = new RectF();
        boolean hasPosition;

        ListenerRegistration(@NonNull OnChangedListener listener) {
            this.listener = listener;
        }
    }

    private static final class TrackedGeometry {
        
        final WeakReference<ViewGroup> parent;
        final boolean multiwindow;
        final List<ListenerRegistration> listeners = new ArrayList<>(1);
        final RectF last = new RectF();
        boolean hasLast;
        boolean needsInitialDispatch;

        TrackedGeometry(@NonNull ViewGroup parent, boolean multiwindow) {
            this.parent = new WeakReference<>(parent);
            this.multiwindow = multiwindow;
        }

        void addListener(@NonNull OnChangedListener listener) {
            listeners.add(new ListenerRegistration(listener));
            needsInitialDispatch = true;
        }
    }

    private static final class TrackedView {
        final List<TrackedGeometry> geometries = new ArrayList<>(1);
        boolean multiwindowListening;
    }

    private final WeakHashMap<View, TrackedView> tracked = new WeakHashMap<>();
    private final RectF tmpRect = new RectF(); 
    private static final int[] tmpCords = new int[2];

    private final View.OnAttachStateChangeListener multiwindowAttachStateListener =
            new View.OnAttachStateChangeListener() {
                @Override
                public void onViewAttachedToWindow(@NonNull View v) {
                }

                @Override
                public void onViewDetachedFromWindow(@NonNull View v) {
                    stopMultiwindowListening(v, tracked.remove(v));
                }
            };

    public ViewPositionWatcher(@NonNull View anchorView) {
        this.anchorView = anchorView;
        anchorView.addOnAttachStateChangeListener(this);
        attachIfPossible();
    }

    public void subscribe(@NonNull View view,
                          @NonNull ViewGroup parentView,
                          @NonNull OnChangedListener listener) {
        subscribe(view, parentView, listener, false);
    }

    public void subscribe(@NonNull View view,
                          @NonNull ViewGroup parentView,
                          @NonNull OnChangedListener listener,
                          boolean multiwindow) {
        TrackedView trackedView = tracked.get(view);
        if (trackedView == null) {
            trackedView = new TrackedView();
            tracked.put(view, trackedView);
        }

        TrackedGeometry geometry = null;
        for (TrackedGeometry candidate : trackedView.geometries) {
            if (candidate.parent.get() == parentView && candidate.multiwindow == multiwindow) {
                geometry = candidate;
                break;
            }
        }
        if (geometry == null) {
            geometry = new TrackedGeometry(parentView, multiwindow);
            trackedView.geometries.add(geometry);
        }
        geometry.addListener(listener);

        ensureListening();

        if (multiwindow) {
            ensureMultiwindowListening(view, trackedView);
        }
    }

    public void unsubscribe(@NonNull View view) {
        stopMultiwindowListening(view, tracked.remove(view));
    }

    public void clear() {
        for (Map.Entry<View, TrackedView> entry : tracked.entrySet()) {
            View view = entry.getKey();
            if (view != null) {
                stopMultiwindowListening(view, entry.getValue());
            }
        }
        tracked.clear();
    }

    public void shutdown() {
        detachIfListening();
        anchorView.removeOnAttachStateChangeListener(this);
        clear();
    }

    private void ensureMultiwindowListening(@NonNull View view, @NonNull TrackedView trackedView) {
        if (trackedView.multiwindowListening) return;

        ViewTreeObserver observer = view.getViewTreeObserver();
        if (observer == null || !observer.isAlive()) return;

        observer.addOnPreDrawListener(this);
        trackedView.multiwindowListening = true;
        view.addOnAttachStateChangeListener(multiwindowAttachStateListener);
    }

    private void stopMultiwindowListening(@NonNull View view, TrackedView trackedView) {
        if (trackedView == null || !trackedView.multiwindowListening) return;

        view.removeOnAttachStateChangeListener(multiwindowAttachStateListener);
        ViewTreeObserver observer = view.getViewTreeObserver();
        if (observer != null && observer.isAlive()) {
            observer.removeOnPreDrawListener(this);
        }
        trackedView.multiwindowListening = false;
    }

    private void attachIfPossible() {
        if (!anchorView.isAttachedToWindow()) return;
        ViewTreeObserver newVto = anchorView.getViewTreeObserver();
        if (newVto != null && newVto.isAlive()) {
            vto = newVto;
            if (!listening) {
                vto.addOnPreDrawListener(this);
                listening = true;
            }
        }
    }

    private void ensureListening() {
        if (!listening) attachIfPossible();
    }

    private void detachIfListening() {
        if (listening && vto != null && vto.isAlive()) {
            vto.removeOnPreDrawListener(this);
        }
        listening = false;
        vto = null;
    }

    @Override
    public void onViewAttachedToWindow(@NonNull View v) {
        attachIfPossible();
    }

    @Override
    public void onViewDetachedFromWindow(@NonNull View v) {
        if (v == anchorView) {
            detachIfListening();
        }
    }

    @Override
    public boolean onPreDraw() {
        
        ViewTreeObserver current = anchorView.getViewTreeObserver();
        if (current != vto) {
            detachIfListening();
            attachIfPossible();
        }

        if (tracked.isEmpty()) return true;

        for (Map.Entry<View, TrackedView> e : tracked.entrySet()) {
            View view = e.getKey();
            TrackedView trackedView = e.getValue();
            if (view == null || trackedView == null) continue;

            for (TrackedGeometry geometry : trackedView.geometries) {
                ViewGroup parent = geometry.parent.get();
                if (parent == null) continue; 
                if (geometry.multiwindow) {
                    view.getLocationOnScreen(tmpCords);
                    tmpRect.set(tmpCords[0], tmpCords[1], tmpCords[0] + view.getWidth(), tmpCords[1] + view.getHeight());

                    parent.getLocationOnScreen(tmpCords);
                    tmpRect.offset(-tmpCords[0], -tmpCords[1]);
                } else {
                    if (!computeRectInParent(view, parent, tmpRect)) continue;
                }

                final boolean changed = !geometry.hasLast || !tmpRect.equals(geometry.last);
                if (changed) {
                    geometry.last.set(tmpRect);
                    geometry.hasLast = true;
                }
                if (changed || geometry.needsInitialDispatch) {
                    
                    geometry.needsInitialDispatch = false;
                    final int listenerCount = geometry.listeners.size();
                    for (int i = 0; i < listenerCount; i++) {
                        ListenerRegistration registration = geometry.listeners.get(i);
                        if (!changed && registration.hasPosition) continue;

                        registration.callbackRect.set(geometry.last);
                        registration.hasPosition = true;
                        try {
                            registration.listener.onPositionChanged(view, registration.callbackRect);
                        } catch (Throwable ignored) {
                            
                        }
                    }
                }
            }
        }
        return true;
    }

    public static float computeYCoordinateInParent(@NonNull View view, @NonNull ViewGroup parentView) {
        computeRectInParent(view, parentView, tmpRectF2);
        return tmpRectF2.top;
    }

    public static float computeXCoordinateInParent(@NonNull View view, @NonNull ViewGroup parentView) {
        computeRectInParent(view, parentView, tmpRectF2);
        return tmpRectF2.left;
    }

    private static RectF tmpRectF2 = new RectF();
    public static boolean computeCoordinatesInParent(@NonNull View view,
                                                   @NonNull ViewGroup parentView, PointF out) {
        final boolean result = computeRectInParent(view, parentView, tmpRectF2);
        if (result) {
            out.x = tmpRectF2.left;
            out.y = tmpRectF2.top;
        }

        return result;
    }

    public static boolean computeRectInParent(@NonNull View view,
                                               @NonNull View parentView,
                                               @NonNull RectF out) {
        float left = 0f;
        float top = 0f;

        View current = view;
        while (current != null && current != parentView) {
            left += current.getX();
            top  += current.getY();

            ViewParent vp = current.getParent();
            if (!(vp instanceof View)) {
                return false; 
            }
            View parent = (View) vp;
            left -= parent.getScrollX();
            top  -= parent.getScrollY();

            current = parent;
        }

        if (current != parentView) {
            
            return false;
        }

        final float l = left;
        final float t = top;
        final float r = l + view.getWidth();
        final float b = t + view.getHeight();
        out.set(l, t, r, b);
        return true;
    }
}
