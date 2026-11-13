/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.documents;

import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Layout;
import android.text.Selection;
import android.text.Spannable;
import android.view.ActionMode;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.text.BreakIterator;

final class TextDocumentSelection {
    interface Host {
        boolean allowCopy(BlockTextView view);
        void stopScroll();
        boolean textToOverlay(BlockTextView view, float[] point);
        boolean overlayToText(BlockTextView view, float[] point);
    }

    private final Host host;
    private final FrameLayout overlay;
    private final Handle startHandle, endHandle;
    private final Path selectionPath = new Path();
    private final RectF bounds = new RectF();
    private final Rect revealRect = new Rect();
    private final float[] point = new float[2];
    private final Matrix mapping = new Matrix();
    private final Matrix inverseMapping = new Matrix();
    private BlockTextView active;
    private Spannable activeText;
    private long activeBinding;
    private ActionMode mode;
    private ActionMode.Callback2 modeCallback;
    private boolean changing;
    private long selectionVersion;
    private Handle dragging;
    private int dragPointer = -1;
    private float grabX, grabY;
    private ViewTreeObserver observed;
    private final ViewTreeObserver.OnPreDrawListener preDraw = this::onPreDraw;

    private boolean onPreDraw() {
        final long version = selectionVersion;
        if (active != null && !valid()) cancelIfCurrent(version);
        if (active != null && selectionVersion == version) {
            placeHandle(startHandle, rangeStart());
            if (active != null && selectionVersion == version) placeHandle(endHandle, rangeEnd());
        }
        return true;
    }

    TextDocumentSelection(Host host, FrameLayout overlay) {
        this.host = host;
        this.overlay = overlay;
        overlay.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        startHandle = new Handle(true);
        endHandle = new Handle(false);
        overlay.addView(startHandle, new FrameLayout.LayoutParams(dp(48), dp(48)));
        overlay.addView(endHandle, new FrameLayout.LayoutParams(dp(48), dp(48)));
        overlay.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) {}
            @Override public void onViewDetachedFromWindow(View view) { cancel(); }
        });
    }

    void beforeRebind(BlockTextView view) {
        view.cancelGesture();
        if (active == view) cancel();
        ++view.binding;
    }

    void permissionsChanged() {
        if (active != null && !valid()) cancel();
    }

    void onScrollStarted() {
        cancel();
    }

    void cancel() {
        final long version = ++selectionVersion;
        BlockTextView oldView = active;
        Spannable oldText = activeText;
        active = null;
        activeText = null;
        modeCallback = null;
        ActionMode oldMode = mode;
        mode = null;
        startHandle.setVisibility(View.GONE);
        endHandle.setVisibility(View.GONE);
        if (observed != null && observed.isAlive()) observed.removeOnPreDrawListener(preDraw);
        observed = null;
        final boolean wasChanging = changing;
        changing = true;
        try {
            endDrag();
            if (oldText != null && (selectionVersion == version || activeText != oldText)) {
                Selection.removeSelection(oldText);
            }
        if (oldView != null) oldView.invalidate();
        if (oldMode != null) oldMode.finish();
        } finally {
            changing = wasChanging;
        }
    }

    private boolean usable(BlockTextView view) {
        return view != null && host.allowCopy(view) && view.isEnabled() && view.isTextSelectable()
                && view.isAttachedToWindow() && view.isShown() && view.getWindowVisibility() == View.VISIBLE
                && overlay.isAttachedToWindow() && overlay.isShown() && view.getLayout() != null
                && view.getText() instanceof Spannable;
    }

    private boolean valid() {
        final BlockTextView view = active;
        final Spannable text = activeText;
        final long binding = activeBinding, version = selectionVersion;
        return ownsSelection(view, text, binding, version) && view.getSelectionStart() >= 0
                && view.getSelectionEnd() >= 0 && view.getSelectionStart() <= text.length()
                && view.getSelectionEnd() <= text.length()
                && view.getSelectionEnd() != view.getSelectionStart();
    }

    private boolean ownsSelection(BlockTextView view, Spannable text, long binding, long version) {
        return text != null && usable(view) && selectionVersion == version
                && active == view && activeText == text && activeBinding == binding
                && view.binding == binding && view.getText() == text;
    }

    private void cancelIfCurrent(long version) {
        if (selectionVersion == version) cancel();
    }

    private boolean select(BlockTextView view, int start, int end) {
        long version = ++selectionVersion;
        final CharSequence value = view == null ? null : view.getText();
        final long binding = view == null ? -1 : view.binding;
        if (!(value instanceof Spannable) || !usable(view) || start < 0 || end < 0 || start == end
                || start > value.length() || end > value.length() || selectionVersion != version
                || view.binding != binding || view.getText() != value) return false;
        final Spannable text = (Spannable) value;
        if (active != view || activeText != text || activeBinding != binding) {
            cancel();
            if (selectionVersion != version + 1) return false;
            version = selectionVersion;
            if (!usable(view) || selectionVersion != version || view.binding != binding
                    || view.getText() != text) return false;
            active = view;
            activeText = text;
            activeBinding = binding;
        }
        final long requestVersion = version;
        final boolean wasChanging = changing;
        changing = true;
        try {
            host.stopScroll();
            if (!ownsSelection(view, text, binding, requestVersion)) {
                cancelIfCurrent(requestVersion);
                return false;
            }
            view.requestFocus();
            if (!ownsSelection(view, text, binding, requestVersion)
                    || start > text.length() || end > text.length()) {
                cancelIfCurrent(requestVersion);
                return false;
            }
            Selection.setSelection(text, start, end);
        } finally {
            changing = wasChanging;
        }
        if (!ownsSelection(view, text, binding, requestVersion) || !valid()) {
            cancelIfCurrent(requestVersion);
            return false;
        }
        if (mode == null) {
            final ActionMode.Callback2 callback = new ActionMode.Callback2() {
                private boolean current() {
                    return modeCallback == this && valid() && modeCallback == this && active == view
                            && activeText == text && activeBinding == binding;
                }

                @Override public boolean onCreateActionMode(ActionMode action, Menu menu) {
                    if (!current()) return false;
                    menu.add(0, android.R.id.copy, 0, android.R.string.copy)
                            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
                    menu.add(0, android.R.id.selectAll, 1, android.R.string.selectAll)
                            .setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
                    return true;
                }

                @Override public boolean onPrepareActionMode(ActionMode action, Menu menu) {
                    if (!current()) return false;
                    menu.findItem(android.R.id.selectAll).setEnabled(
                            rangeStart() != 0 || rangeEnd() != active.length());
                    return true;
                }

                @Override public boolean onActionItemClicked(ActionMode action, MenuItem item) {
                    if (!current()) return false;
                    return contextItem(view, item.getItemId());
                }

                @Override public void onDestroyActionMode(ActionMode action) {
                    if (modeCallback == this && (mode == null || mode == action)) cancel();
                }

                @Override public void onGetContentRect(ActionMode action, View origin, Rect rect) {
                    if (!current() || origin != active) {
                        rect.setEmpty();
                        return;
                    }
                    selectionPath.reset();
                    active.getLayout().getSelectionPath(rangeStart(), rangeEnd(), selectionPath);
                    selectionPath.computeBounds(bounds, true);
                    bounds.offset(active.getTotalPaddingLeft() - active.getScrollX(),
                            active.getTotalPaddingTop() - active.getScrollY());
                    bounds.roundOut(rect);
                }
            };
            modeCallback = callback;
            ActionMode started = view.startActionMode(callback, ActionMode.TYPE_FLOATING);
            if (modeCallback != callback || selectionVersion != requestVersion || !valid()) {
                if (started != null) started.finish();
                if (modeCallback == callback) cancelIfCurrent(requestVersion);
                return false;
            }
            mode = started;
            if (mode == null) {
                cancel();
                return false;
            }
            observed = overlay.getViewTreeObserver();
            observed.addOnPreDrawListener(preDraw);
        }
        final ActionMode currentMode = mode;
        currentMode.invalidate();
        if (selectionVersion != requestVersion || mode != currentMode || !valid()) return false;
        currentMode.invalidateContentRect();
        if (selectionVersion != requestVersion || mode != currentMode || !valid()) return false;
        preDraw.onPreDraw();
        return selectionVersion == requestVersion && ownsSelection(view, text, binding, requestVersion) && valid();
    }

    private boolean selectWord(BlockTextView view, float x, float y) {
        if (!usable(view) || view.length() == 0) return false;
        int offset = Math.min(view.getOffsetForPosition(x, y), view.length() - 1);
        BreakIterator words = BreakIterator.getWordInstance(view.getTextLocale());
        words.setText(view.getText().toString());
        int start = words.preceding(offset + 1);
        int end = words.following(offset);
        if (start == BreakIterator.DONE) start = 0;
        if (end == BreakIterator.DONE) end = view.length();
        return select(view, start, end);
    }

    private void changed(BlockTextView view) {
        if (changing || view.bridge == null) return;
        int start = view.getSelectionStart(), end = view.getSelectionEnd();
        if (start >= 0 && end >= 0 && end != start && usable(view)) select(view, start, end);
        else if (active == view) cancel();
    }

    private boolean contextItem(BlockTextView view, int id) {
        if (!usable(view)) {
            if (active == view) cancel();
            return false;
        }
        if (id == android.R.id.selectAll) return view.length() > 0 && select(view, 0, view.length());
        if (id == android.R.id.copy && active == view && valid()) {
            final long version = selectionVersion;
            boolean copied = view.nativeCopy();
            cancelIfCurrent(version);
            return copied;
        }
        return false;
    }

    private void placeHandle(Handle handle, int offset) {
        final BlockTextView view = active;
        final long version = selectionVersion;
        Layout layout = view.getLayout();
        int line = layout.getLineForOffset(offset);
        point[0] = layout.getPrimaryHorizontal(offset) + view.getTotalPaddingLeft() - view.getScrollX();
        point[1] = layout.getLineBottom(line) + view.getTotalPaddingTop() - view.getScrollY();
        boolean visible = host.textToOverlay(view, point) && finite(point)
                && point[0] >= 0 && point[0] <= overlay.getWidth()
                && point[1] >= 0 && point[1] <= overlay.getHeight();
        if (selectionVersion != version || active != view) return;
        handle.tipX = point[0];
        handle.tipY = point[1];
        handle.setTranslationX(point[0] - dp(24));
        handle.setTranslationY(point[1] - dp(8));
        handle.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        int color = view.getCurrentTextColor();
        if (handle.inkColor != color) {
            handle.inkColor = color;
            handle.invalidate();
        }
    }

    private boolean beginDrag(Handle handle, int pointer, float x, float y) {
        if (!valid()) {
            cancel();
            return false;
        }
        final BlockTextView view = active;
        final Spannable text = activeText;
        final long binding = activeBinding, version = selectionVersion;
        host.stopScroll();
        if (!ownsSelection(view, text, binding, version) || !valid()) return false;
        dragging = handle;
        dragPointer = pointer;
        Layout layout = active.getLayout();
        int offset = handle.start ? rangeStart() : rangeEnd();
        int line = layout.getLineForOffset(offset);
        point[0] = layout.getPrimaryHorizontal(offset) + active.getTotalPaddingLeft() - active.getScrollX();
        point[1] = (layout.getLineTop(line) + layout.getLineBottom(line)) / 2f
                + active.getTotalPaddingTop() - active.getScrollY();
        if (!host.textToOverlay(view, point) || !finite(point)) {
            cancelIfCurrent(version);
            return false;
        }
        if (!ownsSelection(view, text, binding, version) || !valid()) return false;
        grabX = point[0] - x;
        grabY = point[1] - y;
        overlay.getParent().requestDisallowInterceptTouchEvent(true);
        return true;
    }

    private boolean dragTo(float x, float y) {
        if (dragging == null || !valid()) {
            cancel();
            return false;
        }
        final BlockTextView view = active;
        final Spannable text = activeText;
        final long binding = activeBinding, version = selectionVersion;
        final boolean startHandle = dragging.start;
        point[0] = x + grabX;
        point[1] = y + grabY;
        if (!host.overlayToText(view, point) || !finite(point)) {
            cancelIfCurrent(version);
            return false;
        }
        if (!ownsSelection(view, text, binding, version) || !valid() || dragging == null) return false;
        int offset = view.getOffsetForPosition(point[0], point[1]);
        int start = rangeStart(), end = rangeEnd();
        if (offset < 0 || offset > text.length()) return false;
        if (startHandle && offset >= end || !startHandle && offset <= start) return true;
        if (startHandle) start = offset;
        else end = offset;
        boolean selected = select(view, start, end);
        if (selected) revealOffset(startHandle ? start : end);
        return selected;
    }

    private void revealOffset(int offset) {
        if (!valid()) return;
        Layout layout = active.getLayout();
        int line = layout.getLineForOffset(offset);
        int x = Math.round(layout.getPrimaryHorizontal(offset)) + active.getTotalPaddingLeft();
        revealRect.set(x, layout.getLineTop(line) + active.getTotalPaddingTop(),
                x + 1, layout.getLineBottom(line) + active.getTotalPaddingTop());
        active.requestRectangleOnScreen(revealRect, true);
    }

    private void extendWord(BlockTextView view, float x, float y, int anchorStart, int anchorEnd) {
        if (active != view || !valid()) return;
        int offset = view.getOffsetForPosition(x, y);
        if (offset < 0 || offset > view.length()) return;
        if (select(view, Math.min(offset, anchorStart), Math.max(offset, anchorEnd))) revealOffset(offset);
    }

    private void endDrag() {
        final ActionMode currentMode = mode;
        dragging = null;
        dragPointer = -1;
        ViewParent parent = overlay.getParent();
        if (parent != null) parent.requestDisallowInterceptTouchEvent(false);
        if (currentMode != null && mode == currentMode) {
            currentMode.invalidateContentRect();
        }
    }

    private int rangeStart() {
        return Math.min(active.getSelectionStart(), active.getSelectionEnd());
    }

    private int rangeEnd() {
        return Math.max(active.getSelectionStart(), active.getSelectionEnd());
    }

    private int dp(int value) {
        return Math.round(value * overlay.getResources().getDisplayMetrics().density);
    }

    private static boolean finite(float[] point) {
        return !Float.isNaN(point[0]) && !Float.isInfinite(point[0])
                && !Float.isNaN(point[1]) && !Float.isInfinite(point[1]);
    }

    boolean mapToAncestor(View view, ViewGroup ancestor, float[] point) {
        mapping.reset();
        if (!matrixToAncestor(view, ancestor, mapping)) return false;
        mapping.mapPoints(point);
        return finite(point);
    }

    boolean mapFromAncestor(View view, ViewGroup ancestor, float[] point) {
        mapping.reset();
        if (!matrixToAncestor(view, ancestor, mapping) || !mapping.invert(inverseMapping)) return false;
        inverseMapping.mapPoints(point);
        return finite(point);
    }

    private static boolean matrixToAncestor(View view, ViewGroup ancestor, Matrix matrix) {
        while (view != ancestor) {
            if (!(view.getParent() instanceof View)) return false;
            View parent = (View) view.getParent();
            matrix.postConcat(view.getMatrix());
            matrix.postTranslate(view.getLeft() - parent.getScrollX(), view.getTop() - parent.getScrollY());
            view = parent;
        }
        return true;
    }

    private final class Handle extends View {
        final boolean start;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        float tipX, tipY;
        int inkColor;

        Handle(boolean start) {
            super(overlay.getContext());
            this.start = start;
            setVisibility(View.GONE);
            setFocusable(false);
        }

        @Override protected void onDraw(Canvas canvas) {
            if (active == null) return;
            paint.setColor(active.getCurrentTextColor());
            float x = dp(24), y = dp(8), radius = dp(8);
            canvas.drawCircle(x, y + radius, radius, paint);
            canvas.drawRect(x - dp(1), y, x + dp(1), y + radius, paint);
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                return beginDrag(this, event.getPointerId(0), event.getX() + getTranslationX(),
                        event.getY() + getTranslationY());
            }
            if (dragging != this) return false;
            if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_POINTER_DOWN
                    || action == MotionEvent.ACTION_POINTER_UP) {
                cancel();
                return true;
            }
            int index = event.findPointerIndex(dragPointer);
            if (index < 0) {
                cancel();
                return true;
            }
            if (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP) {
                dragTo(event.getX(index) + getTranslationX(), event.getY(index) + getTranslationY());
            }
            if (action == MotionEvent.ACTION_UP) endDrag();
            return true;
        }
    }

    static class BlockTextView extends TextView {
        private final TextDocumentSelection bridge;
        private final GestureDetector gestures;
        private long binding;
        long documentOwner = -1;
        private float lastX, lastY;
        private boolean wordDragging;
        private int wordStart, wordEnd;

        BlockTextView(android.content.Context context, TextDocumentSelection bridge) {
            super(context);
            this.bridge = bridge;
            gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent event) { return true; }
                @Override public void onLongPress(MotionEvent event) {
                    if (bridge.selectWord(BlockTextView.this, event.getX(), event.getY())) {
                        wordDragging = true;
                        wordStart = bridge.rangeStart();
                        wordEnd = bridge.rangeEnd();
                        getParent().requestDisallowInterceptTouchEvent(true);
                        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                    }
                }
                @Override public boolean onDoubleTap(MotionEvent event) {
                    return bridge.selectWord(BlockTextView.this, event.getX(), event.getY());
                }
                @Override public boolean onSingleTapUp(MotionEvent event) {
                    bridge.cancel();
                    performClick();
                    return true;
                }
            });
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            lastX = event.getX();
            lastY = event.getY();
            if (event.getPointerCount() > 1 || !bridge.usable(this)) {
                cancelGesture();
                return false;
            }
            gestures.onTouchEvent(event);
            int action = event.getActionMasked();
            if (wordDragging && action == MotionEvent.ACTION_MOVE) {
                bridge.extendWord(this, lastX, lastY, wordStart, wordEnd);
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                if (wordDragging && getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                wordDragging = false;
                if (action == MotionEvent.ACTION_CANCEL && bridge.active == this) bridge.cancel();
            }
            return true;
        }

        private void cancelGesture() {
            if (wordDragging && getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
            wordDragging = false;
            if (gestures == null) return;
            long now = SystemClock.uptimeMillis();
            MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0);
            gestures.onTouchEvent(cancel);
            cancel.recycle();
        }

        @Override public boolean performClick() { return super.performClick(); }

        @Override public boolean performLongClick() {
            return bridge.selectWord(this, lastX, lastY);
        }

        @Override public ActionMode startActionMode(ActionMode.Callback callback, int type) {
            return callback == bridge.modeCallback && type == ActionMode.TYPE_FLOATING
                    ? super.startActionMode(callback, type) : null;
        }

        @Override public ActionMode startActionMode(ActionMode.Callback callback) {
            return startActionMode(callback, ActionMode.TYPE_PRIMARY);
        }

        @Override protected void onSelectionChanged(int start, int end) {
            super.onSelectionChanged(start, end);
            if (bridge != null) bridge.changed(this);
        }

        @Override protected void onFocusChanged(boolean focused, int direction, Rect previous) {
            super.onFocusChanged(focused, direction, previous);
            if (!focused && bridge != null && bridge.active == this) bridge.cancel();
        }

        @Override protected void onDetachedFromWindow() {
            bridge.beforeRebind(this);
            super.onDetachedFromWindow();
        }

        @Override public boolean onTextContextMenuItem(int id) {
            return bridge.contextItem(this, id);
        }

        private boolean nativeCopy() { return super.onTextContextMenuItem(android.R.id.copy); }

        private boolean accessibilitySelectionTarget(CharSequence text, long binding, long version) {
            return text instanceof Spannable && this.binding == binding && getText() == text
                    && bridge.selectionVersion == version && bridge.usable(this)
                    && this.binding == binding && getText() == text && bridge.selectionVersion == version;
        }
        @Override public boolean performAccessibilityAction(int action, Bundle arguments) {
            final CharSequence text = getText();
            final long binding = this.binding;
            long version = bridge.selectionVersion;
            if (!bridge.host.allowCopy(this)
                    && (isSelectionAction(action, arguments) || !isReadingAction(action))) return false;
            if (action == AccessibilityNodeInfo.ACTION_COPY) return bridge.contextItem(this, android.R.id.copy);
            if (action == AccessibilityNodeInfo.ACTION_CUT) return false;
            if (action == AccessibilityNodeInfo.ACTION_SET_SELECTION) {
                if (!accessibilitySelectionTarget(text, binding, version)) return false;
                int start = arguments == null ? -1 : arguments.getInt(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, -1);
                int end = arguments == null ? -1 : arguments.getInt(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, -1);
                final boolean clear = start == -1 && end == -1;
                if (!clear && (start < 0 || end < start || end > text.length())) return false;
                if (clear || start == end) {
                    final boolean wasChanging = bridge.changing;
                    bridge.changing = true;
                    try {
                        if (bridge.active == this) {
                            bridge.cancel();
                            ++version;
                        }
                        if (!accessibilitySelectionTarget(text, binding, version)
                                || !clear && start > text.length()) return false;
                        if (clear) Selection.removeSelection((Spannable) text);
                        else Selection.setSelection((Spannable) text, start);
                        return accessibilitySelectionTarget(text, binding, version);
                    } finally {
                        bridge.changing = wasChanging;
                    }
                }
                return bridge.select(this, start, end);
            }
            return super.performAccessibilityAction(action, arguments);
        }

        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            if (!bridge.host.allowCopy(this)) {
                if (android.os.Build.VERSION.SDK_INT >= 33) info.setTextSelectable(false);
                info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_SELECTION);
                info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_COPY);
                info.removeAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_CUT);
                for (AccessibilityNodeInfo.AccessibilityAction action
                        : new java.util.ArrayList<>(info.getActionList())) {
                    if (!isReadingAction(action.getId())) info.removeAction(action);
                }
            }
        }

        private static boolean isReadingAction(int action) {
            return action == AccessibilityNodeInfo.ACTION_FOCUS
                    || action == AccessibilityNodeInfo.ACTION_CLEAR_FOCUS
                    || action == AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS
                    || action == AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS
                    || action == AccessibilityNodeInfo.ACTION_CLICK
                    || action == AccessibilityNodeInfo.ACTION_LONG_CLICK
                    || action == AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY
                    || action == AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY
                    || action == AccessibilityNodeInfo.ACTION_NEXT_HTML_ELEMENT
                    || action == AccessibilityNodeInfo.ACTION_PREVIOUS_HTML_ELEMENT
                    || action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD
                    || action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
                    || action == AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId();
        }

        private static boolean isSelectionAction(int action, Bundle arguments) {
            return action == AccessibilityNodeInfo.ACTION_SET_SELECTION
                    || action == AccessibilityNodeInfo.ACTION_COPY || action == AccessibilityNodeInfo.ACTION_CUT
                    || arguments != null && arguments.getBoolean(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN)
                    && (action == AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY
                    || action == AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY);
        }
    }
}
