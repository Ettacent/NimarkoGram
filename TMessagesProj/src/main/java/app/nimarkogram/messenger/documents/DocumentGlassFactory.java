/* Modifications Copyright (C) 2026 Ettacent. */

/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.documents;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.view.View;
import android.view.ViewOutlineProvider;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.GlassRim;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundProvider;
import org.telegram.utils.glass.GlassSettings;
import org.telegram.utils.glass.source.GlassSource;
import org.telegram.utils.glass.source.GlassSourceColor;
import org.telegram.utils.glass.source.GlassSourceEmpty;
import org.telegram.utils.glass.source.GlassSourceRenderNode;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

final class DocumentGlassFactory extends BlurredBackgroundDrawableViewFactory {
    interface Progress { float value(View owner); }
    private final GlassSourceColor fallback;
    private final Progress progress;
    private final ArrayList<WeakReference<Material>> materials = new ArrayList<>();
    private boolean primeScanPending;

    DocumentGlassFactory(GlassSource source, GlassSourceColor fallback, Progress progress) {
        super(source);
        this.fallback = fallback;
        this.progress = progress;
    }

    @Override protected BlurredBackgroundDrawable decorateDrawable(View view, BlurredBackgroundDrawable live) {
        if (Build.VERSION.SDK_INT < 31 || !(live.getUnwrappedSource() instanceof GlassSourceRenderNode)) return live;
        Material material = new Material(view, live, BlurredBackgroundDrawableViewFactory.createDrawable(fallback), progress);
        materials.add(new WeakReference<>(material));
        primeScanPending = true;
        return material;
    }

    void invalidateMaterialViews() {
        for (int i = materials.size() - 1; i >= 0; i--) {
            Material material = materials.get(i).get();
            if (material == null || material.released) materials.remove(i);
            else {
                View view = material.view.get();
                if (!material.released && view != null) view.invalidate();
            }
        }
    }

    void updateMaterialColors() {
        for (int i = materials.size() - 1; i >= 0; i--) {
            Material material = materials.get(i).get();
            if (material == null || material.released) materials.remove(i);
            else {
                material.updateColors();
                View owner = material.view.get();
                if (owner != null) owner.invalidate();
            }
        }
    }

    void primeMaterials(Canvas canvas) {
        if (!primeScanPending || !canvas.isHardwareAccelerated()) return;
        primeScanPending = false;
        for (int i = materials.size() - 1; i >= 0; i--) {
            Material material = materials.get(i).get();
            if (material == null || material.released) materials.remove(i);
            else material.prime(canvas);
        }
    }

    @Override public void release(View view, BlurredBackgroundDrawable drawable) {
        if (drawable instanceof Material) {
            Material material = (Material) drawable;
            if (material.released) return;
            material.released = true;
            materials.removeIf(ref -> ref.get() == null || ref.get() == material);
            material.flat.setCallback(null);
            super.release(view, material);
            material.live.setCallback(null);
            View owner = material.view.get();
            if (owner != null) owner.invalidate();
        } else super.release(view, drawable);
    }

    void releaseMaterials() {
        while (!materials.isEmpty()) {
            Material material = materials.remove(materials.size() - 1).get();
            if (material != null) release(material.view.get(), material);
        }
        primeScanPending = false;
    }

    private static final class Material extends BlurredBackgroundDrawable implements android.graphics.drawable.Drawable.Callback {
        final WeakReference<View> view;
        final BlurredBackgroundDrawable live, flat;
        private final Progress progress;
        private final Paint add = new Paint();
        private final RectF layer = new RectF();
        private float extent;
        private boolean primed;
        boolean released;

        Material(View view, BlurredBackgroundDrawable live, BlurredBackgroundDrawable flat, Progress progress) {
            super(GlassSourceEmpty.INSTANCE);
            this.view = new WeakReference<>(view);
            this.live = live;
            this.flat = flat;
            this.progress = progress;
            add.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
            live.setCallback(this);
            flat.setCallback(this);
        }

        @Override public void draw(Canvas canvas) {
            if (released || live.getAlpha() == 0 || live.getPaddedBounds().isEmpty()) return;
            float p = canvas.isHardwareAccelerated() ? progress.value(view.get()) : 1f;
            if (p >= 1f) { live.draw(canvas); return; }
            if (p <= 0f) {
                flat.draw(canvas);
                prime(canvas);
                return;
            }
            layer.set(live.getPaddedBounds()); layer.inset(-extent, -extent);
            int glassAlpha = Math.round(255f * p);
            int scene = canvas.saveLayer(layer, null);
            int base = canvas.saveLayerAlpha(layer, 255 - glassAlpha);
            flat.draw(canvas);
            canvas.restoreToCount(base);
            add.setAlpha(glassAlpha);
            int glass = canvas.saveLayer(layer, add);
            live.draw(canvas);
            canvas.restoreToCount(glass);
            canvas.restoreToCount(scene);
        }

        void prime(Canvas canvas) {
            if (primed || released || !canvas.isHardwareAccelerated() || live.getPaddedBounds().isEmpty()) return;
            layer.set(live.getPaddedBounds());layer.inset(-extent,-extent);
            int save = canvas.saveLayerAlpha(layer,0);
            try {
                live.draw(canvas);
                if (live.getAlpha() == 0) live.buildDisplayList();
                primed = true;
            } finally {
                canvas.restoreToCount(save);
            }
        }

        @Override public void setBounds(int left, int top, int right, int bottom) {
            super.setBounds(left, top, right, bottom);
            live.setBounds(left, top, right, bottom);
            flat.setBounds(left, top, right, bottom);
        }
        @Override public void setBounds(Rect bounds) { setBounds(bounds.left, bounds.top, bounds.right, bounds.bottom); }
        @Override public GlassSource getSource() { return live.getSource(); }
        @Override public GlassSource getUnwrappedSource() { return live.getUnwrappedSource(); }
        @Override public void getPositionRelativeSource(RectF bounds) { live.getPositionRelativeSource(bounds); }
        @Override protected void onSourceOffsetChange(float x,float y) { live.setSourceOffset(x,y); flat.setSourceOffset(x,y); }
        @Override protected void onAlphaChanged(int oldAlpha,int alpha) { live.setAlpha(alpha); flat.setAlpha(alpha); }
        @Override protected void onGlassSettingsChanged(GlassSettings settings) {
            live.setGlassSettings(settings);
            flat.setGlassSettings(settings);
            invalidateSelf();
        }
        @Override public void setLiquidGlassEffectAllowed() { live.setLiquidGlassEffectAllowed(); }
        @Override public BlurredBackgroundDrawable setPadding(int p) { live.setPadding(p);flat.setPadding(p);return this; }
        @Override public BlurredBackgroundDrawable setHasPadding(boolean b) { live.setHasPadding(b);flat.setHasPadding(b);return this; }
        @Override public boolean getPadding(Rect r) { return live.getPadding(r); }
        @Override public BlurredBackgroundDrawable setClipToOutline(boolean b) { live.setClipToOutline(b);flat.setClipToOutline(b);return this; }
        @Override public BlurredBackgroundDrawable setRadius(float r) { live.setRadius(r);flat.setRadius(r);return this; }
        @Override public BlurredBackgroundDrawable setRadius(float a,float b,float c,float d) { live.setRadius(a,b,c,d);flat.setRadius(a,b,c,d);return this; }
        @Override public void setRadius(float a,float b,float c,float d,boolean z) { live.setRadius(a,b,c,d,z);flat.setRadius(a,b,c,d,z); }
        @Override public BlurredBackgroundDrawable setThickness(int t) { live.setThickness(t);flat.setThickness(t);return this; }
        @Override public void setIntensity(float p) { live.setIntensity(p);flat.setIntensity(p); }
        @Override public float getIntensity() { return live.getIntensity(); }
        @Override public BlurredBackgroundDrawable setArrow(int side,float position,float height,float tipRadius,float filletRadius) {
            live.setArrow(side,position,height,tipRadius,filletRadius);flat.setArrow(side,position,height,tipRadius,filletRadius);return this;
        }
        @Override public BlurredBackgroundDrawable clearArrow() { live.clearArrow();flat.clearArrow();return this; }
        @Override public BlurredBackgroundDrawable setRim(GlassRim rim) { live.setRim(rim);flat.setRim(rim);return this; }
        @Override public BlurredBackgroundDrawable setLightAngle(float angle) { live.setLightAngle(angle);flat.setLightAngle(angle);return this; }
        @Override public BlurredBackgroundDrawable setEvenRim(boolean even) { live.setEvenRim(even);flat.setEvenRim(even);return this; }
        @Override public Rect getPaddedBounds() { return live.getPaddedBounds(); }
        @Override public void getBoundsWithPaddingInset(Rect bounds) { live.getBoundsWithPaddingInset(bounds); }
        @Override public Path getPath() { return live.getPath(); }
        @Override public ViewOutlineProvider getViewOutlineProvider() { return live.getViewOutlineProvider(); }
        @Override public void getOutline(Outline outline) { live.getOutline(outline); }
        @Override public BlurredBackgroundDrawable setColorProvider(BlurredBackgroundColorProvider provider) {
            live.setColorProvider(provider);flat.setColorProvider(provider);
            if (provider instanceof BlurredBackgroundProvider) {
                BlurredBackgroundProvider p = (BlurredBackgroundProvider) provider;
                updateExtent(p.getShadowRadius(),p.getShadowDx(),p.getShadowDy());
            }
            return this;
        }
        private void updateExtent(float radius,float dx,float dy) { extent=3f*radius+Math.max(Math.abs(dx),Math.abs(dy))+AndroidUtilities.dp(2); }
        @Override public void updateColors() { live.updateColors();flat.updateColors(); }
        @Override public BlurredBackgroundDrawable setOutset(int x,int y) { live.setOutset(x,y);flat.setOutset(x,y);return this; }
        @Override public int getOutsetX() { return live.getOutsetX(); }
        @Override public int getOutsetY() { return live.getOutsetY(); }
        @Override public void setShadowAlpha(float p) { live.setShadowAlpha(p);flat.setShadowAlpha(p); }
        @Override public void setShadowParams(float radius,float x,float y) { live.setShadowParams(radius,x,y);flat.setShadowParams(radius,x,y);updateExtent(radius,x,y); }
        @Override public void setStrokeWidth(float top,float bottom) { live.setStrokeWidth(top,bottom);flat.setStrokeWidth(top,bottom); }
        @Override public void enableInAppKeyboardOptimization() { live.enableInAppKeyboardOptimization();flat.enableInAppKeyboardOptimization(); }
        @Override public void setColorFilter(ColorFilter filter) { live.setColorFilter(filter);flat.setColorFilter(filter); }
        @Override public boolean hasDisplayList() { return live.hasDisplayList(); }
        @Override public boolean shouldHaveDisplayList() { return live.shouldHaveDisplayList(); }
        @Override public boolean isInvalidated() { return live.isInvalidated(); }
        @Override public void buildDisplayList() { live.buildDisplayList(); }
        @Override public void invalidateDisplayList() { live.invalidateDisplayList(); }
        @Override public void updateDisplayList() { live.updateDisplayList(); }
        @Override public void invalidateDrawable(android.graphics.drawable.Drawable who) { if (!released) invalidateSelf(); }
        @Override public void scheduleDrawable(android.graphics.drawable.Drawable who,Runnable what,long when) { if (!released) scheduleSelf(what,when); }
        @Override public void unscheduleDrawable(android.graphics.drawable.Drawable who,Runnable what) { unscheduleSelf(what); }
    }
}
