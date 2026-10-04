/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components.Reactions;

import static android.graphics.Canvas.ALL_SAVE_FLAG;
import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import app.nimarkogram.messenger.utils.ui.SystemTextPaint;
import android.text.style.ReplacementSpan;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;

public class AddReactionsSpan extends ReplacementSpan {

    private final TextPaint textPaint = new SystemTextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rectF = new RectF();
    private StaticLayout layout;
    private float width, height;
    private int alpha;
    private ValueAnimator alphaAnimator;

    public AddReactionsSpan(float textSize, Theme.ResourcesProvider resourcesProvider) {
        textPaint.setTextSize(dp(textSize));
        textPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText5, resourcesProvider));
    }

    public void makeLayout() {
        if (layout == null) {
            layout = new StaticLayout(LocaleController.getString(R.string.ReactionAddReactionsHint), textPaint, AndroidUtilities.displaySize.x, LocaleController.isRTL ? Layout.Alignment.ALIGN_OPPOSITE : Layout.Alignment.ALIGN_NORMAL, 1, 0, false);
            width = layout.getLineWidth(0);
            height = layout.getHeight();
        }
    }

    @Override
    public int getSize(@NonNull Paint paint, CharSequence text, int start, int end, @Nullable Paint.FontMetricsInt fm) {
        makeLayout();
        return (int) (dp(8) + width);
    }

    @Override
    public void draw(@NonNull Canvas canvas, CharSequence text, int start, int end, float _x, int top, int _y, int bottom, @NonNull Paint paint) {
        makeLayout();
        rectF.set(canvas.getClipBounds());
        canvas.saveLayerAlpha(rectF, alpha, ALL_SAVE_FLAG);
        float transY = (top + (bottom - top) / 2f - height / 2f);
        canvas.translate(_x + dp(4), transY);
        layout.draw(canvas);
        canvas.restore();
    }

    public void show(View parent) {
        animateAlpha(parent, 255, null);
    }

    public void hide(View parent, Runnable after) {
        animateAlpha(parent, 0, after);
    }
    private void animateAlpha(View parent, int target, Runnable after) {
        if (alphaAnimator != null) {
            alphaAnimator.removeAllListeners();
            alphaAnimator.removeAllUpdateListeners();
            alphaAnimator.cancel();
        }
        ValueAnimator valueAnimator = ValueAnimator.ofInt(alpha, target);
        alphaAnimator = valueAnimator;
        valueAnimator.addUpdateListener(animator -> {
            alpha = (int) animator.getAnimatedValue();
            parent.invalidate();
        });
        valueAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (alphaAnimator != animation) return;
                alphaAnimator = null;
                if (after != null) after.run();
            }
        });
        valueAnimator.setDuration(200);
        valueAnimator.start();
    }
}
