package app.nimarkogram.messenger.infocards;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.CacheControlActivity;
import org.telegram.ui.Components.AnimatedFloat;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.LaunchActivity;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;

public class CacheCard extends BaseInfoCard {

    private final StorageRingDrawable ring;
    
    private boolean ringFilledOnce;
    private boolean sizeRequestPending;
    private boolean lifecycleAttached;
    private long lifecycleGeneration;
    private long fractionRequestGeneration;
    private SizeCallbackHandle sizeCallback;
    private static final Object SIZE_LOCK = new Object();
    private static final ArrayList<Runnable> SIZE_CALLBACKS = new ArrayList<>();
    private static final ArrayList<WeakReference<SizeCallbackHandle>> SIZE_WEAK_CALLBACKS = new ArrayList<>();
    private static boolean sizeComputationRunning;

    public static final class SizeCallbackHandle {
        private Runnable callback;
        private boolean canceled;

        SizeCallbackHandle(Runnable callback) {
            this.callback = callback;
        }

        public void cancel() {
            synchronized (SIZE_LOCK) {
                canceled = true;
                callback = null;
                for (int i = SIZE_WEAK_CALLBACKS.size() - 1; i >= 0; i--) {
                    SizeCallbackHandle handle = SIZE_WEAK_CALLBACKS.get(i).get();
                    if (handle == null || handle == this) SIZE_WEAK_CALLBACKS.remove(i);
                }
            }
        }

        void dispatch() {
            Runnable runnable;
            synchronized (SIZE_LOCK) {
                if (canceled) return;
                canceled = true;
                runnable = callback;
                callback = null;
            }
            if (runnable != null) runnable.run();
        }
    }

    public CacheCard(Context context, Theme.ResourcesProvider resourcesProvider, int iconRes) {
        super(context, resourcesProvider);
        
        ring = new StorageRingDrawable(iconView);
        ring.setRingColor(currentFgColor());
        iconView.setImageDrawable(ring);
        
        iconView.setColorFilter(null);
    }

    @Override
    public int getCardId() {
        return InfoCardType.CACHE.id;
    }

    @Override
    public long getRefreshInterval() {
        return 300000; 
    }

    @Override
    protected void onAttachedToWindow() {
        lifecycleAttached = true;
        lifecycleGeneration++;
        super.onAttachedToWindow();
    }

    @Override
    protected boolean isRefreshDue() {
        
        return true;
    }

    @Override
    public void onUpdateData(boolean force) {
        if (!lifecycleAttached || !isAttachedToWindow()) return;
        
        if (cachedSizeText != null) {
            setText(cachedSizeText, true);
            if (cachedFraction >= 0f) {
                ring.setProgress(cachedFraction, ringFilledOnce);
                ringFilledOnce = true;
            }
        } else {
            startLoading();
        }
        
        if (!sizeRequestPending) {
            sizeRequestPending = true;
            final long generation = lifecycleGeneration;
            sizeCallback = requestSizeComputationWeak(() -> {
                if (lifecycleAttached && generation == lifecycleGeneration && isAttachedToWindow()) {
                    sizeRequestPending = false;
                    sizeCallback = null;
                    setText(cachedSizeText, true);
                    stopLoading();
                    markDataUpdated();
                }
            });
        }

        try {
            final long generation = lifecycleGeneration;
            final long fractionGeneration = ++fractionRequestGeneration;
            final WeakReference<CacheCard> cardRef = new WeakReference<>(this);
            CacheControlActivity.getDeviceTotalSize((deviceTotal, deviceFree) -> {
                CacheCard card = cardRef.get();
                if (card == null || !card.lifecycleAttached
                        || generation != card.lifecycleGeneration
                        || fractionGeneration != card.fractionRequestGeneration
                        || !card.isAttachedToWindow()) {
                    return;
                }
                float fraction = 0f;
                if (deviceTotal != null && deviceTotal > 0) {
                    long used = deviceTotal - (deviceFree == null ? 0 : deviceFree);
                    fraction = (float) used / (float) (long) deviceTotal;
                }
                
                cachedFraction = fraction;
                card.ring.setProgress(fraction, card.ringFilledOnce);
                card.ringFilledOnce = true;
            });
        } catch (Throwable ignore) {
        }
    }

    public static CharSequence liveValueText() {
        return cachedSizeText;
    }

    public static void computeAsync(Runnable onDone) {
        requestSizeComputation(onDone);
    }

    public static SizeCallbackHandle computeAsyncWeak(Runnable onDone) {
        return requestSizeComputationWeak(onDone);
    }

    private static void requestSizeComputation(Runnable onDone) {
        synchronized (SIZE_LOCK) {
            if (onDone != null) SIZE_CALLBACKS.add(onDone);
            if (sizeComputationRunning) return;
            sizeComputationRunning = true;
        }
        startSizeComputation();
    }

    private static SizeCallbackHandle requestSizeComputationWeak(Runnable onDone) {
        SizeCallbackHandle handle = new SizeCallbackHandle(onDone);
        synchronized (SIZE_LOCK) {
            SIZE_WEAK_CALLBACKS.add(new WeakReference<>(handle));
            if (sizeComputationRunning) return handle;
            sizeComputationRunning = true;
        }
        startSizeComputation();
        return handle;
    }

    private static void startSizeComputation() {
        new Thread(() -> {
            long total = 0;
            try {
                Context ctx = ApplicationLoader.applicationContext;
                if (ctx != null) {
                    total += dirSize(ctx.getCacheDir(), 0);
                    total += dirSize(ctx.getFilesDir(), 0);
                }
            } catch (Throwable ignore) {
            }
            final long size = total;
            AndroidUtilities.runOnUIThread(() -> {
                cachedSizeText = AndroidUtilities.formatFileSize(size, true, true);
                ArrayList<Runnable> callbacks;
                ArrayList<WeakReference<SizeCallbackHandle>> weakCallbacks;
                synchronized (SIZE_LOCK) {
                    sizeComputationRunning = false;
                    callbacks = new ArrayList<>(SIZE_CALLBACKS);
                    SIZE_CALLBACKS.clear();
                    weakCallbacks = new ArrayList<>(SIZE_WEAK_CALLBACKS);
                    SIZE_WEAK_CALLBACKS.clear();
                }
                for (Runnable callback : callbacks) {
                    try { callback.run(); } catch (Throwable ignore) {}
                }
                for (WeakReference<SizeCallbackHandle> callbackRef : weakCallbacks) {
                    SizeCallbackHandle callback = callbackRef.get();
                    if (callback != null) {
                        try { callback.dispatch(); } catch (Throwable ignore) {}
                    }
                }
            });
        }, "pill-cache-size").start();
    }

    @Override
    protected void onDetachedFromWindow() {
        lifecycleAttached = false;
        lifecycleGeneration++;
        fractionRequestGeneration++;
        sizeRequestPending = false;
        if (sizeCallback != null) {
            sizeCallback.cancel();
            sizeCallback = null;
        }
        super.onDetachedFromWindow();
    }

    private static volatile String cachedSizeText;
    private static volatile float cachedFraction = -1f;

    private static long dirSize(File dir, int depth) {
        if (dir == null || depth > 12) return 0;
        long total = 0;
        try {
            if (dir.isFile()) return dir.length();
            File[] children = dir.listFiles();
            if (children == null) return 0;
            for (File f : children) {
                if (f == null) continue;
                if (f.isDirectory()) {
                    total += dirSize(f, depth + 1);
                } else {
                    total += f.length();
                }
            }
        } catch (Throwable ignore) {
        }
        return total;
    }

    @Override
    public void onCardClicked() {
        
        onUpdateData(true);
        try {
            LaunchActivity la = LaunchActivity.instance;
            if (la != null) {
                INavigationLayout layout = la.getActionBarLayout();
                if (layout != null) {
                    layout.presentFragment(new CacheControlActivity());
                }
            }
        } catch (Throwable ignore) {
        }
    }

    @Override
    public boolean onCardLongClicked() {
        return false;
    }

    @Override
    public void updateColors() {
        int fg = currentFgColor();
        setTextColor(fg);
        if (ring != null) ring.setRingColor(fg);
    }

    private int currentFgColor() {
        if (InfoCardsConfig.getColorMode() == InfoCardsConfig.COLOR_MODE_THEME) {
            return Theme.getColor(Theme.key_windowBackgroundWhiteBlueButton, resourcesProvider);
        }
        return 0xffffffff;
    }

    private static final class StorageRingDrawable extends Drawable {

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private final AnimatedFloat animatedFraction;
        private float fraction;
        private int ringColor = 0xffffffff;

        StorageRingDrawable(View parentToInvalidate) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND);
            
            animatedFraction = new AnimatedFloat(parentToInvalidate, 650L, CubicBezierInterpolator.EASE_OUT_QUINT);
        }

        void setRingColor(int color) {
            this.ringColor = color;
            invalidateSelf();
        }

        void setProgress(float value, boolean animated) {
            float clamped = Math.max(0.05f, Math.min(1f, value));
            this.fraction = clamped;
            if (!animated) {
                animatedFraction.force(clamped);
            }
            invalidateSelf();
        }

        @Override
        public void draw(@NonNull Canvas canvas) {
            Rect bounds = getBounds();
            int w = bounds.width();
            int h = bounds.height();
            float stroke = AndroidUtilities.dp(2);
            float size = Math.min(w, h) - stroke;
            float left = (w - size) / 2f;
            float top = (h - size) / 2f;
            rect.set(left, top, left + size, top + size);

            float frac = animatedFraction.set(fraction);

            paint.setStrokeWidth(stroke);
            paint.setColor(ringColor);

            paint.setAlpha(0x32);
            canvas.drawCircle(w / 2f, h / 2f, size / 2f, paint);

            paint.setAlpha(0xFF);
            canvas.drawArc(rect, -90f, frac * 360f, false, paint);
        }

        @Override
        public int getOpacity() {
            return android.graphics.PixelFormat.TRANSLUCENT;
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter colorFilter) {
            paint.setColorFilter(colorFilter);
        }
    }
}
