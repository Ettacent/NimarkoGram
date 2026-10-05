/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.notifications;

import android.graphics.Canvas;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;
import java.util.ArrayList;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedLinearLayout;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.blur3.capture.IBlur3Capture;
import org.telegram.ui.Components.blur3.utils.Blur3Utils;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.DownscaleScrollableNoiseSuppressor;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceRenderNode;

@androidx.annotation.RequiresApi(31)
public final class NotificationListGlassSource {
    private final ViewGroup root;
    private final View[] contents;
    private final DownscaleScrollableNoiseSuppressor suppressor = new DownscaleScrollableNoiseSuppressor();
    private final BlurredBackgroundSourceRenderNode source = new BlurredBackgroundSourceRenderNode(null);
    private final BlurredBackgroundDrawableViewFactory factory = new BlurredBackgroundDrawableViewFactory(source);
    private final ArrayList<RectF> positions = new ArrayList<>();
    private final RectF region = new RectF();

    public NotificationListGlassSource(ViewGroup root, View[] contents) {
        this.root = root;
        this.contents = contents.clone();
        positions.add(region);
        factory.setSourceRootView(null, root);
        factory.setLiquidGlassEffectAllowed(true);
    }

    public BlurredBackgroundDrawableViewFactory update(AnimatedLinearLayout panel, Theme.ResourcesProvider resources) {
        if (root.getWidth() <= 0 || root.getHeight() <= 0 || panel == null || !panel.isAttachedToWindow()) return null;
        float padding = AndroidUtilities.dp(48);
        region.set(0, Math.max(0, panel.getY() - padding), root.getWidth(),
                Math.min(root.getHeight(), panel.getY() + panel.getAnimatedHeightWithPadding() + padding));
        if (region.isEmpty()) return null;
        suppressor.setupRenderNodes(positions, 1);
        suppressor.invalidateResultRenderNodes((canvas, position) -> {
            for (View child : contents) {
                if (!shouldCapture(child, panel)) continue;
                if (child instanceof RecyclerListView) {
                    Blur3Utils.captureRelativeParent(((RecyclerListView) child)::captureContentForBlur,
                            canvas, position, child, root, Math.round(child.getAlpha() * 255));
                } else if (child instanceof IBlur3Capture) {
                    Blur3Utils.captureRelativeParent((IBlur3Capture) child, canvas, position, child, root,
                            Math.round(child.getAlpha() * 255));
                } else {
                    int save = canvas.save();
                    canvas.clipRect(position);
                    canvas.translate(child.getLeft(), child.getTop());
                    canvas.concat(child.getMatrix());
                    if (child.getAlpha() < 1f) {
                        canvas.saveLayerAlpha(0, 0, child.getWidth(), child.getHeight(), Math.round(child.getAlpha() * 255));
                    }
                    child.draw(canvas);
                    canvas.restoreToCount(save);
                }
            }
        }, root.getWidth(), root.getHeight());
        Canvas canvas = source.beginRecording(root.getWidth(), root.getHeight());
        try {
            canvas.drawColor(Theme.getColor(Theme.key_windowBackgroundWhite, resources));
            if (root.getBackground() != null) root.getBackground().draw(canvas);
            suppressor.draw(canvas, DownscaleScrollableNoiseSuppressor.DRAW_GLASS);
        } finally {
            source.endRecording();
        }
        return factory;
    }

    private boolean shouldCapture(View child, AnimatedLinearLayout panel) {
        return child != panel && !(child instanceof AnimatedLinearLayout.IndependentPanel)
                && !(child instanceof NotificationInlinePanel) && child.getParent() == root
                && child.getVisibility() == View.VISIBLE && child.getAlpha() > 0
                && child.getWidth() > 0 && child.getHeight() > 0;
    }
}
