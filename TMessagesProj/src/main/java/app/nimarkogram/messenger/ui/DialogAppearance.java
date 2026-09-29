package app.nimarkogram.messenger.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.animation.LinearInterpolator;

import androidx.recyclerview.widget.RecyclerView;

import org.telegram.ui.Cells.DialogCell;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.function.BooleanSupplier;

public final class DialogAppearance {
    private final RecyclerListView list;
    private final BooleanSupplier allowed;
    private final HashMap<DialogCell, Fade> fades = new HashMap<>();
    private ViewTreeObserver observer;
    private ViewTreeObserver.OnPreDrawListener pending;

    public DialogAppearance(RecyclerListView list, BooleanSupplier allowed) {
        this.list = list;
        this.allowed = allowed;
        list.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(View view) { }
            public void onViewDetachedFromWindow(View view) {
                clearPending();
                for (Fade fade : new ArrayList<>(fades.values())) fade.animator.cancel();
            }
        });
        list.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
            public void onChildViewAttachedToWindow(View view) { }
            public void onChildViewDetachedFromWindow(View view) {
                Fade fade = fades.get(view);
                if (fade != null) fade.animator.cancel();
            }
        });
    }

    public void beforeUpdate() {
        if (pending != null || !allowed.getAsBoolean()) return;
        HashMap<Long, Position> visible = new HashMap<>();
        for (int i = 0; i < list.getChildCount(); i++) {
            View child = list.getChildAt(i);
            if (child instanceof DialogCell) {
                int position = list.getChildAdapterPosition(child);
                if (position != RecyclerView.NO_POSITION) {
                    visible.put(((DialogCell) child).getDialogId(), new Position(position, child.getTop()));
                }
            }
        }
        if (visible.isEmpty()) return;
        pending = () -> {
            clearPending();
            if (!allowed.getAsBoolean()) return true;
            final int visibleTop = list.getClipToPadding() ? list.getPaddingTop() : 0;
            final int visibleBottom = list.getHeight() - (list.getClipToPadding() ? list.getPaddingBottom() : 0);
            for (int i = 0; i < list.getChildCount(); i++) {
                View child = list.getChildAt(i);
                if (!(child instanceof DialogCell)) continue;
                DialogCell cell = (DialogCell) child;
                Fade current = fades.get(cell);
                if (current != null && current.dialogId != cell.getDialogId()) current.animator.cancel();
                int position = list.getChildAdapterPosition(cell);
                Position previous = visible.get(cell.getDialogId());
                if (previous != null && cell.isMessagePreviewTransitionRunning()) continue;
                boolean arriving = previous == null || position < previous.index && cell.getTop() < previous.top;
                if (arriving && position != RecyclerView.NO_POSITION && cell.getDialogId() != 0
                        && cell.getBottom() > visibleTop
                        && cell.getTop() < visibleBottom
                        && cell.getAlpha() == 1f && !fades.containsKey(cell)) {
                    Fade fade = new Fade(cell, previous != null);
                    fades.put(cell, fade);
                    cell.setAlpha(0f);
                    fade.animator.start();
                }
            }
            return true;
        };
        observer = list.getViewTreeObserver();
        observer.addOnPreDrawListener(pending);
    }

    private static final class Position {
        final int index;
        final int top;

        Position(int index, int top) {
            this.index = index;
            this.top = top;
        }
    }

    private void clearPending() {
        if (observer != null && observer.isAlive() && pending != null) {
            observer.removeOnPreDrawListener(pending);
        }
        observer = null;
        pending = null;
    }

    private final class Fade {
        final ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        final long dialogId;
        float appliedAlpha;

        Fade(DialogCell cell, boolean promoted) {
            dialogId = cell.getDialogId();
            final Runnable handoff = animator::cancel;
            if (promoted) cell.onPreviewCrossfadeStarted = handoff;
            animator.setDuration(250);
            animator.setInterpolator(new LinearInterpolator());
            animator.addUpdateListener(value -> {
                if (cell.getParent() != list || cell.getDialogId() != dialogId
                        || cell.getAlpha() != appliedAlpha || !allowed.getAsBoolean()) {
                    animator.cancel();
                    return;
                }
                float remaining = 1f - (float) value.getAnimatedValue();
                appliedAlpha = 1f - remaining * remaining * remaining;
                cell.setAlpha(appliedAlpha);
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (cell.onPreviewCrossfadeStarted == handoff) cell.onPreviewCrossfadeStarted = null;
                    if (fades.get(cell) != Fade.this) return;
                    fades.remove(cell);
                    if (cell.getAlpha() == appliedAlpha) cell.setAlpha(1f);
                }
            });
        }
    }
}
