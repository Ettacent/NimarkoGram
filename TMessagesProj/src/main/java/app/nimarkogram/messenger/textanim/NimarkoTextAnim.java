 
package app.nimarkogram.messenger.textanim;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.Editable;
import android.text.Layout;
import android.text.Spannable;
import android.text.TextPaint;
import android.text.style.ForegroundColorSpan;
import android.text.style.ReplacementSpan;
import android.view.View;
import android.widget.EditText;

import java.lang.reflect.Field;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.Theme;

import app.nimarkogram.messenger.NimarkoConfig;

public final class NimarkoTextAnim {

    private NimarkoTextAnim() {}

    private static final int APPEAR_DURATION = 300;
    private static final int BLUR_DURATION = 300;
    private static final int BLUR_RADIUS = 10;
    private static final int BLUR_TEXT_DELAY_PCT = 20;
    private static final int SLIDE_DIST_PX = 20;
    private static final int REPLACE_DURATION = 200; 
    private static final int CURSOR_SPEED = 25;
    private static final int CURSOR_WIDTH_PX = 5;
    private static final int PARTICLE_COUNT = 5;
    private static final float PARTICLE_SPEED = 1.0f;
    private static final float PARTICLE_SIZE = 1.0f;
    private static final int MASS_DELETE_THRESHOLD = 3;
    
    private static final int MASS_INSERT_THRESHOLD = 24;
    
    private static final int SPOILER_DURATION = 110;
    private static final float SPOILER_PARTICLE_SPEED = 3.1f;
    private static final int SPOILER_FADE_IN = 18;
    private static final int SPOILER_FADE_OUT = 35;
    private static final int SPOILER_PARTICLE_COUNT = 15;
    private static final int CURSOR_COLOR_FALLBACK = 0xff2a92e0;

    private static volatile boolean installed;
    private static final Object installLock = new Object();
    private static final List<XC_MethodHook.Unhook> hooks = new ArrayList<>();
    private static final WeakHashMap<View, State> states = new WeakHashMap<>();
    
    private static final int BLUR_CACHE_MAX = 96;
    private static final int BLUR_BITMAP_MAX_DIMENSION = 512;
    private static final long BLUR_BITMAP_MAX_BYTES = 1024L * 1024L;
    private static final long BLUR_CACHE_MAX_BYTES = 8L * 1024L * 1024L;
    private static long blurCacheBytes;
    private static final LinkedHashMap<String, Bitmap> blurCache =
            new LinkedHashMap<String, Bitmap>(BLUR_CACHE_MAX, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Bitmap> eldest) {
                    if (size() > BLUR_CACHE_MAX) {
                        Bitmap b = eldest.getValue();
                        blurCacheBytes = Math.max(0L, blurCacheBytes - bitmapBytes(b));
                        if (b != null && !b.isRecycled()) {
                            try { b.recycle(); } catch (Throwable ignored) {}
                        }
                        return true;
                    }
                    return false;
                }
            };
    
    private static volatile Field fieldCursorWidth;
    private static volatile Field fieldResourcesProvider;
    
    private static final boolean DIRECT_INTEGRATION = true;

    private static volatile boolean masterEnabled;
    private static volatile boolean appearEnabled;
    private static volatile boolean smoothCursorEnabled;
    private static volatile boolean deleteEnabled;
    private static volatile boolean spoilerEnabled;
    private static volatile boolean pineInitScheduled;
    private static volatile boolean pineUnavailable;

    public static void initIfEnabled() {
        applySettings();
    }

    public static void applySettings() {
        synchronized (installLock) {
            boolean wasSmoothCursorEnabled = smoothCursorEnabled;
            masterEnabled = NimarkoConfig.nimarkoTextAnim;
            appearEnabled = NimarkoConfig.nimarkoTextAnimAppear;
            smoothCursorEnabled = NimarkoConfig.nimarkoTextAnimCursor;
            deleteEnabled = NimarkoConfig.nimarkoTextAnimDelete;
            spoilerEnabled = NimarkoConfig.nimarkoTextAnimSpoiler;
            if (DIRECT_INTEGRATION) {
                
                if (installed) {
                    uninstall();
                }
                if (!masterEnabled
                        || (wasSmoothCursorEnabled && !smoothCursorEnabled)) {
                    restoreAllSystemCursors();
                }
                return;
            }
            if (masterEnabled && !installed) {
                if (ApplicationLoader.isPineAvailable()) {
                    install();
                } else {
                    requestPineInit();
                }
            }
            else if (!masterEnabled && installed) uninstall();
            else if (wasSmoothCursorEnabled && !smoothCursorEnabled) restoreAllSystemCursors();
        }
    }

    public static void beforeEditorDraw(EditText edit) {
        if (!masterEnabled || edit == null) return;
        try {
            ensureDirectFields(edit.getClass());
            handleBeforeDraw(edit);
        } catch (Throwable t) {
            FileLog.e("NimarkoTextAnim beforeDraw failed", t);
        }
    }

    public static void afterEditorDraw(EditText edit, Canvas canvas) {
        if (!masterEnabled || edit == null || canvas == null) return;
        try {
            handleAfterDraw(edit, canvas);
        } catch (Throwable t) {
            FileLog.e("NimarkoTextAnim afterDraw failed", t);
        }
    }

    public static void onEditorFocusChanged(EditText edit, boolean focused) {
        if (edit == null) return;
        try {
            if (!masterEnabled) {
                restoreSystemCursor(edit);
                return;
            }
            ensureDirectFields(edit.getClass());
            handleFocusChanged(edit, focused);
        } catch (Throwable t) {
            FileLog.e("NimarkoTextAnim focus callback failed", t);
        }
    }

    public static void onEditorTouch(EditText edit) {
        if (!masterEnabled || edit == null) return;
        try {
            edit.invalidate();
            startHeartbeat(edit);
        } catch (Throwable ignored) {
        }
    }

    private static void requestPineInit() {
        if (pineInitScheduled || pineUnavailable) return;
        pineInitScheduled = true;
        try {
            Thread worker = new Thread(() -> {
                try {
                    ApplicationLoader.ensurePineInited();
                    if (ApplicationLoader.isPineAvailable()) {
                        synchronized (installLock) {
                            if (NimarkoConfig.nimarkoTextAnim && !installed) {
                                install();
                            }
                        }
                    } else {
                        pineUnavailable = true;
                        FileLog.w("NimarkoTextAnim: Pine unavailable; feature disabled for this process");
                    }
                } catch (Throwable t) {
                    pineUnavailable = true;
                    FileLog.e("NimarkoTextAnim: Pine initialization failed", t);
                } finally {
                    pineInitScheduled = false;
                }
            }, "ng-textanim-pine");
            worker.setDaemon(true);
            worker.start();
        } catch (Throwable t) {
            pineInitScheduled = false;
            pineUnavailable = true;
            FileLog.e("NimarkoTextAnim: unable to start Pine initializer", t);
        }
    }

    public static void install() {
        synchronized (installLock) {
            if (installed) return;
            if (!ApplicationLoader.isPineAvailable()) {
                FileLog.w("NimarkoTextAnim: install skipped because Pine is unavailable");
                return;
            }
            Class<?> target = findEditorClass();
            if (target == null) {
                FileLog.w("NimarkoTextAnim: no suitable EditText class found, abort install");
                return;
            }
            cacheFields(target);
            try {
                hookOnDraw(target);
                hookFocusChanged(target);
                hookTouchEvent(target);
                installed = true;
                FileLog.d("NimarkoTextAnim: installed on " + target.getName());
            } catch (Throwable t) {
                FileLog.e("NimarkoTextAnim install failed", t);
                uninstall();
            }
        }
    }

    public static void uninstall() {
        synchronized (installLock) {
            for (XC_MethodHook.Unhook h : hooks) {
                try { h.unhook(); } catch (Throwable ignored) {}
            }
            hooks.clear();
            synchronized (blurCache) {
                for (Bitmap b : blurCache.values()) {
                    if (b != null && !b.isRecycled()) {
                        try { b.recycle(); } catch (Throwable ignored) {}
                    }
                }
                blurCache.clear();
                blurCacheBytes = 0L;
            }
            
            synchronized (states) {
                if (fieldCursorWidth != null) {
                    for (Map.Entry<View, State> e : states.entrySet()) {
                        State st = e.getValue();
                        View v = e.getKey();
                        if (st != null && v != null && st.systemCursorHidden) {
                            try {
                                
                                fieldCursorWidth.setFloat(v, st.savedCursorWidth);
                                if (v instanceof EditText) {
                                    ((EditText) v).setCursorVisible(st.savedCursorVisible);
                                }
                                st.systemCursorHidden = false;
                                v.invalidate();
                            } catch (Throwable ignored) {}
                        }
                    }
                }
                states.clear();
            }
            
            fieldCursorWidth = null;
            fieldResourcesProvider = null;
            installed = false;
        }
    }

    private static Class<?> findEditorClass() {
        
        for (String name : new String[] {
                "org.telegram.ui.Components.EditTextCaption",
                "org.telegram.ui.Components.EditTextBoldCursor",
                "org.telegram.ui.Components.EditTextEffects"
        }) {
            try {
                return Class.forName(name);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static void cacheFields(Class<?> target) {
        fieldResourcesProvider = findField(target, "resourcesProvider");
        fieldCursorWidth = findField(target, "cursorWidth");
    }

    private static void ensureDirectFields(Class<?> target) {
        if (fieldResourcesProvider != null && fieldCursorWidth != null) return;
        synchronized (installLock) {
            if (fieldResourcesProvider == null || fieldCursorWidth == null) {
                cacheFields(target);
            }
        }
    }

    private static Field findField(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (NoSuchFieldException ignored) {}
        }
        return null;
    }

    private static void hookOnDraw(Class<?> cls) throws Exception {
        Method m = cls.getDeclaredMethod("onDraw", Canvas.class);
        m.setAccessible(true);
        hooks.add(XposedBridge.hookMethod(m, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                if (!masterEnabled) return;
                try {
                    handleBeforeDraw((EditText) param.thisObject);
                } catch (Throwable t) {
                    FileLog.e(t);
                }
            }
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                if (!masterEnabled) return;
                try {
                    handleAfterDraw(
                            (EditText) param.thisObject, (Canvas) param.args[0]);
                } catch (Throwable t) {
                    FileLog.e(t);
                }
            }
        }));
    }

    private static void hookFocusChanged(Class<?> cls) {
        Method m = findMethod(cls, "onFocusChanged");
        if (m == null) return;
        m.setAccessible(true);
        hooks.add(XposedBridge.hookMethod(m, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    EditText view = (EditText) param.thisObject;
                    boolean focused = (Boolean) param.args[0];
                    handleFocusChanged(view, focused);
                } catch (Throwable t) { FileLog.e(t); }
            }
        }));
    }

    private static void hookTouchEvent(Class<?> cls) {
        Method m = findMethod(cls, "onTouchEvent");
        if (m == null) return;
        m.setAccessible(true);
        hooks.add(XposedBridge.hookMethod(m, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    View view = (View) param.thisObject;
                    view.invalidate();
                    startHeartbeat(view);
                } catch (Throwable ignored) {}
            }
        }));
    }

    private static Method findMethod(Class<?> cls, String name) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name)) return m;
            }
        }
        return null;
    }

    private static State getState(View v) {
        synchronized (states) {
            State s = states.get(v);
            if (s == null) {
                s = new State();
                states.put(v, s);
            }
            return s;
        }
    }

    private static void startHeartbeat(final View view) {
        final State st = getState(view);
        if (st.heartbeatRunning) return;
        st.heartbeatRunning = true;
        
        final Runnable beat = new Runnable() {
            @Override public void run() {
                if (!st.focused) { st.heartbeatRunning = false; return; }
                if (!view.isShown() || view.getWindowToken() == null) {
                    st.heartbeatRunning = false; return;
                }
                view.invalidate();
                view.postOnAnimation(this);
            }
        };
        view.postOnAnimation(beat);
    }

    private static int getLastLineStart(EditText edit) {
        try {
            Layout l = edit.getLayout();
            if (l != null && l.getLineCount() > 0) {
                return l.getLineStart(l.getLineCount() - 1);
            }
        } catch (Throwable ignored) {}
        return 0;
    }

    private static void handleBeforeDraw(EditText edit) {
        State st = getState(edit);
        st.drawingDepth++;
        st.focused = edit.isFocused();
        if (st.focused && smoothCursorEnabled) {
            startHeartbeat(edit);
        }
        if (smoothCursorEnabled) setupCursor(edit, st);
        Editable text = edit.getText();
        if (text == null) return;
        String now = text.toString();
        int newLen = now.length();

        int minLen = Math.min(newLen, st.prevLen);
        int prefix = 0;
        while (prefix < minLen && now.charAt(prefix) == st.prevText.charAt(prefix)) prefix++;
        int suffix = 0;
        while (suffix < newLen - prefix
                && suffix < st.prevLen - prefix
                && now.charAt(newLen - 1 - suffix) == st.prevText.charAt(st.prevLen - 1 - suffix)) {
            suffix++;
        }
        int oldEnd = st.prevLen - suffix;
        int newEnd = newLen - suffix;
        int delCount = oldEnd - prefix;
        int insCount = newEnd - prefix;
        boolean hadEdit = (delCount > 0) || (insCount > 0);

        if (delCount > 0 && deleteEnabled) {
            String removed = st.prevText.substring(prefix, oldEnd);
            boolean massDelete = (newLen == 0 && delCount > MASS_DELETE_THRESHOLD);
            if (!massDelete) {
                int i = 0;
                while (i < removed.length()) {
                    int step = Character.charCount(removed.codePointAt(i));
                    spawnDeleteParticles(edit, st, prefix + i, removed.substring(i, i + step));
                    i += step;
                }
            }
        }

        if (hadEdit && !st.charStartTimes.isEmpty()) {
            int delta = insCount - delCount;
            
            Map<Integer, CharData> shifted = new HashMap<>();
            for (Map.Entry<Integer, CharData> e : st.charStartTimes.entrySet()) {
                int idx = e.getKey();
                if (idx < prefix) {
                    shifted.put(idx, e.getValue());
                } else if (idx >= oldEnd) {
                    int n = idx + delta;
                    if (n >= 0 && n < newLen) shifted.put(n, e.getValue());
                }
                
            }
            st.charStartTimes.clear();
            st.charStartTimes.putAll(shifted);
        }

        if (insCount > 0 && insCount <= MASS_INSERT_THRESHOLD) {
            long nowMs = System.currentTimeMillis();
            
            boolean isReplace = (delCount > 0 && insCount > 0);
            String inserted = now.substring(prefix, newEnd);
            Spannable spannable = (text instanceof Spannable) ? text : null;
            int i = 0;
            while (i < insCount) {
                int absIdx = prefix + i;
                int step = Character.charCount(inserted.codePointAt(i));
                int next = i + step;
                String ch = inserted.substring(i, next);
                
                if (!ch.trim().isEmpty()) {
                    boolean isEmojiOrReplacement = step > 1
                            || isCjkOrSymbol(ch)
                            || hasReplacementSpan(spannable, absIdx, absIdx + step);
                    if (!isEmojiOrReplacement) {
                        st.charStartTimes.put(absIdx, new CharData(nowMs, ch, step, isReplace));
                    }
                }
                i = next;
            }
        }

        if (hadEdit) {
            if (st.charStartTimes.isEmpty() && st.particles.isEmpty()) {
                st.hasAnimatingChars = false;
                removeSpan(edit, st);
            } else {
                st.hasAnimatingChars = true;
                updateHiddenSpan(edit, st);
                startAnimationLoop(edit, st);
            }
        }
        st.prevText = now;
        st.prevLen = newLen;
    }

    private static boolean isCjkOrSymbol(String s) {
        if (s.isEmpty()) return false;
        char c = s.charAt(0);
        
        return c >= 0x2032 && c <= 0x3299;
    }

    private static boolean hasReplacementSpan(Spannable s, int from, int to) {
        if (s == null) return false;
        try {
            ReplacementSpan[] arr = s.getSpans(from, to, ReplacementSpan.class);
            return arr != null && arr.length > 0;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void handleAfterDraw(EditText edit, Canvas canvas) {
        State st = getState(edit);
        st.drawingDepth = Math.max(0, st.drawingDepth - 1);
        if (st.drawingDepth > 0) return;
        
        long nowMs = System.currentTimeMillis();
        float dtNorm = (st.lastFrameMs == 0L) ? 1f
                : Math.max(0.1f, Math.min(3f, (nowMs - st.lastFrameMs) / 16.6667f));
        st.lastFrameMs = nowMs;
        if (st.hasAnimatingChars) drawAnimatedChars(edit, canvas, st);
        if (smoothCursorEnabled) drawCursor(edit, canvas, st, dtNorm);
        if (!st.particles.isEmpty()) drawParticles(canvas, st, dtNorm);
    }

    private static void drawAnimatedChars(EditText edit, Canvas canvas, State st) {
        TextPaint paint = edit.getPaint();
        if (paint == null) return;
        Layout layout = edit.getLayout();
        if (layout == null) return;
        
        long now = System.currentTimeMillis();
        int dtMs = (st.lastSpoilerDrawMs == 0) ? 16
                : (int) Math.min(now - st.lastSpoilerDrawMs, 50L);
        st.lastSpoilerDrawMs = now;
        int textLen = edit.getText().length();
        int padL = edit.getPaddingLeft();
        int padT = edit.getPaddingTop();
        int scrollX = edit.getScrollX();
        int paintAlpha = paint.getAlpha();
        float textSize = paint.getTextSize();

        for (Map.Entry<Integer, CharData> e : st.charStartTimes.entrySet()) {
            int idx = e.getKey();
            CharData data = e.getValue();
            if (idx < 0 || idx >= textLen) continue;
            int line = layout.getLineForOffset(idx);
            float x = padL + layout.getPrimaryHorizontal(idx) - scrollX;
            float y = padT + layout.getLineBaseline(line);
            long elapsed = now - data.startTime;

            if (spoilerEnabled) {
                if (elapsed < SPOILER_DURATION) {
                    float fadeIn = (elapsed < SPOILER_FADE_IN) ? (elapsed / (float) SPOILER_FADE_IN) : 1f;
                    long left = SPOILER_DURATION - elapsed;
                    float fadeOut = (left < SPOILER_FADE_OUT) ? (left / (float) SPOILER_FADE_OUT) : 1f;
                    drawSpoiler(canvas, data.text, x, y, paint, st, idx, fadeIn, fadeOut, dtMs);
                    continue;
                }
                elapsed -= SPOILER_DURATION;
            }

            int appearDur = data.replace ? REPLACE_DURATION : APPEAR_DURATION;
            int blurDur = data.replace ? REPLACE_DURATION : BLUR_DURATION;
            float pAppear = easeOutQuint(Math.min(1f, elapsed / (float) appearDur));
            float pBlur = easeOutQuint(Math.min(1f, elapsed / (float) blurDur));
            float yOff = (appearEnabled && !data.replace) ? (-SLIDE_DIST_PX * (1f - pAppear)) : 0f;
            float yDraw = y + yOff;

            canvas.save();
            if (appearEnabled) {
                drawCharBlurred(canvas, data.text, x, yDraw, paint, pBlur, paintAlpha, st);
            } else {
                
                int a = (int) (pBlur * paintAlpha);
                if (a > 0) {
                    paint.setAlpha(a);
                    canvas.drawText(data.text, x, yDraw, paint);
                }
            }
            canvas.restore();
        }
        paint.setAlpha(paintAlpha);
    }

    private static void drawCharBlurred(Canvas canvas, String text, float x, float y,
                                        Paint paint, float progress, int baseAlpha, State st) {
        int blurAlpha = (int) ((1f - progress) * 255f);
        float textCutoff = BLUR_TEXT_DELAY_PCT / 100f;
        int textAlpha = (progress > textCutoff)
                ? (int) (((progress - textCutoff) / (1f - textCutoff)) * 255f) : 0;
        if (blurAlpha > 5) {
            try {
                drawBlurredCharBitmap(canvas, text, x, y, paint, blurAlpha, st);
            } catch (Throwable t) {
                drawFallbackBlur(canvas, text, x, y, paint, blurAlpha, st);
            }
        }
        if (textAlpha > 0) {
            int a = (int) ((textAlpha / 255f) * baseAlpha);
            if (a > 0) { paint.setAlpha(a); canvas.drawText(text, x, y, paint); }
        }
    }

    private static void drawBlurredCharBitmap(Canvas canvas, String text, float x, float y,
                                              Paint paint, int alpha, State st) {
        float textSize = paint.getTextSize();
        float measuredWidth = paint.measureText(text);
        if (!Float.isFinite(textSize) || !Float.isFinite(measuredWidth) || textSize <= 0f || measuredWidth < 0f) {
            throw new IllegalArgumentException("invalid blur dimensions");
        }
        int w = Math.max(4, (int) ((measuredWidth / 2f) + (BLUR_RADIUS * 2)));
        int h = Math.max(4, (int) (((textSize / 2f) * 1.5f) + (BLUR_RADIUS * 2)));
        long requestedBytes = (long) w * (long) h * 4L;
        if (w > BLUR_BITMAP_MAX_DIMENSION || h > BLUR_BITMAP_MAX_DIMENSION
                || requestedBytes <= 0L || requestedBytes > BLUR_BITMAP_MAX_BYTES) {
            throw new IllegalArgumentException("blur bitmap too large");
        }
        String cacheKey = text + "|w" + w + "|h" + h
                + '|' + Float.floatToIntBits(textSize) + '|' + paint.getColor()
                + '|' + (paint.getTypeface() == null ? 0 : System.identityHashCode(paint.getTypeface()))
                + '|' + paint.getFlags()
                + '|' + Float.floatToIntBits(paint.getTextScaleX())
                + '|' + Float.floatToIntBits(paint.getTextSkewX())
                + '|' + Float.floatToIntBits(paint.getLetterSpacing())
                + '|' + paint.isFakeBoldText();
        synchronized (blurCache) {
            Bitmap cached = blurCache.get(cacheKey);
            if (cached != null && !cached.isRecycled()) {
                
                drawCachedBlur(canvas, cached, x, y, paint, alpha, st);
                return;
            }
            if (cached != null) blurCache.remove(cacheKey);
            trimBlurCacheFor(requestedBytes);
            if (blurCacheBytes + requestedBytes > BLUR_CACHE_MAX_BYTES) {
                throw new IllegalArgumentException("blur cache budget exceeded");
            }
            
            Bitmap bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            Canvas c2 = new Canvas(bmp);
            c2.scale(0.5f, 0.5f);
            c2.translate(BLUR_RADIUS * 2, (textSize * 1.5f) - (BLUR_RADIUS * 2));
            if (st.sharedPaint == null) st.sharedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            st.sharedPaint.set(paint);
            st.sharedPaint.setAlpha(255);
            c2.drawText(text, 0f, 0f, st.sharedPaint);
            Utilities.stackBlurBitmap(bmp, Math.max(1, BLUR_RADIUS / 2));
            long actualBytes = bitmapBytes(bmp);
            if (actualBytes <= 0L || actualBytes > BLUR_BITMAP_MAX_BYTES
                    || blurCacheBytes + actualBytes > BLUR_CACHE_MAX_BYTES) {
                bmp.recycle();
                throw new IllegalArgumentException("allocated blur bitmap exceeds budget");
            }
            blurCache.put(cacheKey, bmp);
            blurCacheBytes += actualBytes;
            drawCachedBlur(canvas, bmp, x, y, paint, alpha, st);
        }
    }

    private static long bitmapBytes(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) return 0L;
        try {
            return bitmap.getAllocationByteCount();
        } catch (Throwable ignore) {
            return (long) bitmap.getRowBytes() * bitmap.getHeight();
        }
    }

    private static void trimBlurCacheFor(long requestedBytes) {
        java.util.Iterator<Map.Entry<String, Bitmap>> iterator = blurCache.entrySet().iterator();
        while ((blurCache.size() >= BLUR_CACHE_MAX
                || blurCacheBytes + requestedBytes > BLUR_CACHE_MAX_BYTES) && iterator.hasNext()) {
            Bitmap bitmap = iterator.next().getValue();
            iterator.remove();
            blurCacheBytes = Math.max(0L, blurCacheBytes - bitmapBytes(bitmap));
            if (bitmap != null && !bitmap.isRecycled()) {
                try { bitmap.recycle(); } catch (Throwable ignored) {}
            }
        }
    }

    private static void drawCachedBlur(Canvas canvas, Bitmap bmp, float x, float y,
                                       Paint paint, int alpha, State st) {
        if (st.sharedPaint == null) st.sharedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        st.sharedPaint.setAlpha(alpha);
        float textSize = paint.getTextSize();
        canvas.save();
        canvas.translate(x - (BLUR_RADIUS * 2), (y - (textSize * 1.5f)) + (BLUR_RADIUS * 2));
        canvas.scale(2f, 2f);
        canvas.drawBitmap(bmp, 0f, 0f, st.sharedPaint);
        canvas.restore();
    }

    private static void drawFallbackBlur(Canvas canvas, String text, float x, float y,
                                         Paint paint, int alpha, State st) {
        if (st.sharedPaint == null) st.sharedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        st.sharedPaint.set(paint);
        st.sharedPaint.setAlpha(alpha / 3);
        canvas.drawText(text, x, y, st.sharedPaint);
    }

    private static void drawSpoiler(Canvas canvas, String text, float x, float y,
                                    Paint paint, State st, int idx,
                                    float fadeIn, float fadeOut, int dtMs) {
        float textSize = paint.getTextSize();
        float width = paint.measureText(text);
        int color = paint.getColor();
        if (st.spoilerPaints == null) {
            st.spoilerPaints = new Paint[3];
            for (int i = 0; i < 3; i++) {
                st.spoilerPaints[i] = new Paint(Paint.ANTI_ALIAS_FLAG);
                st.spoilerPaints[i].setStrokeWidth(1.4f);
                st.spoilerPaints[i].setStyle(Paint.Style.STROKE);
                st.spoilerPaints[i].setStrokeCap(Paint.Cap.ROUND);
            }
        }
        for (int i = 0; i < 3; i++) st.spoilerPaints[i].setColor(color);
        float alphaScale = easeOutQuint(fadeIn) * fadeOut;
        float cx = x + (width / 2f);
        float cy = y - (0.3f * textSize);

        List<SpoilerParticle> list = st.spoilerParticles.get(idx);
        if (list == null) { list = new ArrayList<>(); st.spoilerParticles.put(idx, list); }
        int wanted = Math.max(SPOILER_PARTICLE_COUNT,
                (int) (((width / 6f) * SPOILER_PARTICLE_COUNT) / 15f));

        Iterator<SpoilerParticle> it = list.iterator();
        while (it.hasNext()) {
            SpoilerParticle p = it.next();
            if (!p.update(dtMs) || !p.inside(x - 10, y - 0.8f * textSize - 10,
                    x + width + 10, y + 0.2f * textSize + 10)) {
                if (st.spoilerPool.size() < 200) st.spoilerPool.push(p);
                it.remove();
            }
        }
        while (list.size() < wanted && alphaScale > 0.1f) {
            SpoilerParticle p = st.spoilerPool.isEmpty()
                    ? new SpoilerParticle(cx, cy, width, textSize, SPOILER_PARTICLE_SPEED)
                    : st.spoilerPool.pop();
            if (!st.spoilerPool.isEmpty()) p.reset(cx, cy, width, textSize, SPOILER_PARTICLE_SPEED);
            list.add(p);
        }
        for (int band = 0; band < 3; band++) {
            for (SpoilerParticle p : list) {
                if (p.alphaType != band) continue;
                float a = p.alpha * p.life * alphaScale;
                if (a < 0.05f) continue;
                st.spoilerPaints[band].setAlpha((int) (a * 255f));
                canvas.drawPoint(p.x, p.y, st.spoilerPaints[band]);
            }
        }
    }

    private static void spawnDeleteParticles(EditText edit, State st, int idx, String ch) {
        if (ch.trim().isEmpty()) return;
        TextPaint paint = edit.getPaint();
        if (paint == null) return;
        Layout layout = edit.getLayout();
        if (layout == null) return;
        float textSize = paint.getTextSize();
        int color = paint.getColor();
        Random r = new Random();
        int line = layout.getLineForOffset(Math.min(idx, layout.getText().length()));
        float x = edit.getPaddingLeft()
                + layout.getPrimaryHorizontal(Math.min(idx, layout.getText().length()))
                - edit.getScrollX();
        float y = edit.getPaddingTop() + layout.getLineBaseline(line);
        float w = paint.measureText(ch);
        for (int i = 0; i < PARTICLE_COUNT; i++) {
            st.particles.add(new Particle(
                    x + r.nextFloat() * w,
                    y - r.nextFloat() * (0.6f * textSize),
                    color, textSize, PARTICLE_SPEED, PARTICLE_SIZE));
        }
    }

    private static void drawParticles(Canvas canvas, State st, float dtNorm) {
        if (st.cursorPaint == null) st.cursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Iterator<Particle> it = st.particles.iterator();
        while (it.hasNext()) {
            Particle p = it.next();
            int a = (int) (p.life * 255f);
            if (a <= 0) { it.remove(); continue; }
            int srcA = (p.color >>> 24) & 0xff;
            st.cursorPaint.setARGB(
                    (int) ((a / 255f) * (srcA / 255f) * 255f),
                    (p.color >> 16) & 0xff, (p.color >> 8) & 0xff, p.color & 0xff);
            canvas.drawCircle(p.x, p.y, p.size / 2f, st.cursorPaint);
            
            p.life -= p.decay * dtNorm;
            p.x += p.vx * dtNorm; p.y += p.vy * dtNorm;
        }
    }

    private static int getCursorColor(View v) {
        try {
            Integer key = Theme.key_chat_messagePanelCursor;
            if (fieldResourcesProvider != null) {
                Object rp = fieldResourcesProvider.get(v);
                if (rp instanceof Theme.ResourcesProvider) {
                    return Theme.getColor(key, (Theme.ResourcesProvider) rp);
                }
            }
            return Theme.getColor(key);
        } catch (Throwable ignored) {
            return CURSOR_COLOR_FALLBACK;
        }
    }

    private static void setupCursor(View view, State st) {
        if (fieldCursorWidth == null) return;
        try {
            if (st.focused && !st.systemCursorHidden) {
                
                st.savedCursorWidth = fieldCursorWidth.getFloat(view);
                if (view instanceof EditText) {
                    st.savedCursorVisible = ((EditText) view).isCursorVisible();
                    ((EditText) view).setCursorVisible(true);
                }
                fieldCursorWidth.setFloat(view, 0f);
                st.systemCursorHidden = true;
            } else if (!st.focused && st.systemCursorHidden) {
                fieldCursorWidth.setFloat(view, st.savedCursorWidth);
                if (view instanceof EditText) {
                    ((EditText) view).setCursorVisible(st.savedCursorVisible);
                }
                st.systemCursorHidden = false;
            }
        } catch (Throwable ignored) {}
    }

    private static void restoreAllSystemCursors() {
        synchronized (states) {
            for (Map.Entry<View, State> entry : states.entrySet()) {
                View view = entry.getKey();
                State st = entry.getValue();
                if (view == null || st == null || !st.systemCursorHidden || fieldCursorWidth == null) continue;
                try {
                    fieldCursorWidth.setFloat(view, st.savedCursorWidth);
                    if (view instanceof EditText) {
                        ((EditText) view).setCursorVisible(st.savedCursorVisible);
                    }
                    st.systemCursorHidden = false;
                    view.invalidate();
                } catch (Throwable ignored) {}
            }
        }
    }

    private static void restoreSystemCursor(View view) {
        if (view == null || fieldCursorWidth == null) return;
        synchronized (states) {
            State st = states.get(view);
            if (st == null || !st.systemCursorHidden) return;
            try {
                fieldCursorWidth.setFloat(view, st.savedCursorWidth);
                if (view instanceof EditText) {
                    ((EditText) view).setCursorVisible(st.savedCursorVisible);
                }
                st.systemCursorHidden = false;
                view.invalidate();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void handleFocusChanged(EditText edit, boolean focused) {
        State st = getState(edit);
        st.focused = focused;
        if (focused) {
            edit.invalidate();
            startHeartbeat(edit);
        } else {
            st.cursorX = -1f;
            st.cursorY = -1f;
            restoreSystemCursor(edit);
            edit.invalidate();
        }
    }

    private static void drawCursor(EditText edit, Canvas canvas, State st, float dtNorm) {
        if (!st.focused) return;
        try {
            canvas.save();
            int color = getCursorColor(edit);
            Layout layout = edit.getLayout();
            int sel = Math.max(0, edit.getSelectionStart());
            float textSize = edit.getPaint().getTextSize();
            float h = edit.getHeight() > 0 ? edit.getHeight() / 2f : textSize;
            float targetX = 0f;
            float targetY = h;
            if (layout != null) {
                try {
                    int line = layout.getLineForOffset(sel);
                    targetX = layout.getPrimaryHorizontal(sel);
                    targetY = layout.getLineTop(line)
                            + ((layout.getLineBottom(line) - layout.getLineTop(line)) / 2f);
                } catch (Throwable ignored) {}
            }
            if (st.cursorX < 0f) st.cursorX = targetX;
            if (st.cursorY < 0f) st.cursorY = targetY;
            
            float ease = Math.min(1f, Math.max(0.05f, CURSOR_SPEED / 100f) * dtNorm);
            boolean moving = false;
            float maxX = 60f * dtNorm, maxY = 90f * dtNorm;
            float dx = targetX - st.cursorX;
            if (Math.abs(dx) > 0.1f) {
                float step = clamp(dx * ease, -maxX, maxX);
                st.cursorX += step; moving = true;
            } else st.cursorX = targetX;
            float dy = targetY - st.cursorY;
            if (Math.abs(dy) > 0.1f) {
                float step = clamp(dy * ease * 1.2f, -maxY, maxY);
                st.cursorY += step; moving = true;
            } else st.cursorY = targetY;

            int alpha;
            if (moving) alpha = 255;
            else alpha = (int) (((Math.sin(System.currentTimeMillis() / 1000.0 * 6.0) + 1.0)
                    / 2.0 * 0.8 + 0.2) * 255.0);

            int padL = edit.getPaddingLeft();
            float padT = edit.getPaddingTop();
            float half = (textSize * 1.25f) / 2f;
            float top = st.cursorY + padT - half;
            float bot = st.cursorY + padT + half;
            float xPos = padL + st.cursorX;
            if (st.cursorPaint == null) st.cursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            if (st.cursorRect == null) st.cursorRect = new RectF();
            st.cursorPaint.setARGB(alpha, (color >> 16) & 0xff, (color >> 8) & 0xff, color & 0xff);
            st.cursorRect.set(xPos, top, xPos + CURSOR_WIDTH_PX, bot);
            canvas.drawRoundRect(st.cursorRect, CURSOR_WIDTH_PX / 2f, CURSOR_WIDTH_PX / 2f,
                    st.cursorPaint);
            canvas.restore();
        } catch (Throwable t) { FileLog.e(t); }
    }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    private static void updateHiddenSpan(EditText edit, State st) {
        try {
            Editable text = edit.getText();
            if (!(text instanceof Spannable)) return;
            if (st.hiddenSpans != null) {
                for (Object sp : st.hiddenSpans) text.removeSpan(sp);
                st.hiddenSpans.clear();
            } else st.hiddenSpans = new ArrayList<>();
            if (st.charStartTimes.isEmpty()) return;
            int len = text.length();
            for (Map.Entry<Integer, CharData> e : st.charStartTimes.entrySet()) {
                int idx = e.getKey();
                CharData data = e.getValue();
                if (idx < 0 || idx >= len) continue;
                ForegroundColorSpan span = new ForegroundColorSpan(0);
                text.setSpan(span, idx, Math.min(idx + data.length, len),
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
                st.hiddenSpans.add(span);
            }
        } catch (Throwable ignored) {}
    }

    private static void removeSpan(EditText edit, State st) {
        try {
            Editable text = edit.getText();
            if (!(text instanceof Spannable)) return;
            if (st.hiddenSpans != null && !st.hiddenSpans.isEmpty()) {
                for (Object sp : st.hiddenSpans) text.removeSpan(sp);
                st.hiddenSpans.clear();
            }
        } catch (Throwable ignored) {}
    }

    private static void startAnimationLoop(final EditText edit, final State st) {
        if (st.animationRunning) return;
        st.animationRunning = true;
        final int maxDuration = (spoilerEnabled ? SPOILER_DURATION : 0)
                + Math.max(APPEAR_DURATION, BLUR_DURATION);
        edit.post(new Runnable() {
            @Override public void run() {
                try {
                    if (edit.getWindowToken() == null) {
                        st.animationRunning = false;
                        removeSpan(edit, st);
                        cleanupSpoilerParticles(st);
                        return;
                    }
                    if (st.charStartTimes.isEmpty() && st.particles.isEmpty()) {
                        st.animationRunning = false;
                        st.hasAnimatingChars = false;
                        removeSpan(edit, st);
                        cleanupSpoilerParticles(st);
                        return;
                    }
                    long now = System.currentTimeMillis();
                    Iterator<Map.Entry<Integer, CharData>> it
                            = st.charStartTimes.entrySet().iterator();
                    while (it.hasNext()) {
                        Map.Entry<Integer, CharData> e = it.next();
                        if (now - e.getValue().startTime >= maxDuration) {
                            it.remove();
                            List<SpoilerParticle> sp = st.spoilerParticles.remove(e.getKey());
                            if (sp != null) for (SpoilerParticle p : sp) {
                                if (st.spoilerPool.size() < 200) st.spoilerPool.push(p);
                            }
                        }
                    }
                    updateHiddenSpan(edit, st);
                    edit.invalidate();
                    if (st.charStartTimes.isEmpty() && st.particles.isEmpty()) {
                        st.animationRunning = false;
                        st.hasAnimatingChars = false;
                        removeSpan(edit, st);
                        cleanupSpoilerParticles(st);
                        return;
                    }
                    
                    edit.postOnAnimation(this);
                } catch (Throwable t) {
                    st.animationRunning = false;
                    st.hasAnimatingChars = false;
                    removeSpan(edit, st);
                    cleanupSpoilerParticles(st);
                }
            }
        });
    }

    private static void cleanupSpoilerParticles(State st) {
        for (List<SpoilerParticle> l : st.spoilerParticles.values()) {
            for (SpoilerParticle p : l) {
                if (st.spoilerPool.size() < 200) st.spoilerPool.push(p);
            }
        }
        st.spoilerParticles.clear();
    }

    private static float easeOutQuint(float t) {
        return 1f - (float) Math.pow(1f - t, 5.0);
    }

    private static final class State {
        String prevText = "";
        int prevLen = 0;
        boolean hasAnimatingChars = false;
        boolean animationRunning = false;
        boolean focused = false;
        boolean systemCursorHidden = false;
        
        float savedCursorWidth = 2f;
        boolean savedCursorVisible = true;
        boolean heartbeatRunning = false;
        int drawingDepth = 0;
        float cursorX = -1f, cursorY = -1f;
        long lastSpoilerDrawMs = 0L;
        long lastFrameMs = 0L;
        Paint cursorPaint;
        RectF cursorRect;
        Paint sharedPaint;
        Paint[] spoilerPaints;
        List<Object> hiddenSpans;
        final Map<Integer, CharData> charStartTimes = new HashMap<>();
        final List<Particle> particles = new ArrayList<>();
        final Map<Integer, List<SpoilerParticle>> spoilerParticles = new HashMap<>();
        final java.util.Stack<SpoilerParticle> spoilerPool = new java.util.Stack<>();
    }

    private static final class CharData {
        final long startTime;
        final String text;
        final int length;
        final boolean replace; 
        CharData(long t, String s, int l) { this(t, s, l, false); }
        CharData(long t, String s, int l, boolean r) { startTime = t; text = s; length = l; replace = r; }
    }

    private static final class Particle {
        float x, y, vx, vy, size, life = 1f, decay;
        int color;
        Particle(float x, float y, int color, float textSize, float speed, float sizeF) {
            this.x = x; this.y = y; this.color = color;
            Random r = new Random();
            this.vx = (r.nextFloat() - 0.5f) * 4f * speed;
            this.vy = ((-r.nextFloat() * 3f) - 1f) * speed;
            this.size = ((r.nextFloat() * (textSize / 6f)) + (textSize / 10f)) * sizeF;
            this.decay = r.nextFloat() * 0.04f + 0.02f;
        }
    }

    private static final class SpoilerParticle {
        private static final float[] LEVELS = {0.3f, 0.6f, 1f};
        private static final Random R = new Random();
        float x, y, vx, vy, velocity, life = 1f, alpha, size;
        int alphaType;
        long lifeMs, currentMs;
        SpoilerParticle(float cx, float cy, float w, float h, float speed) {
            reset(cx, cy, w, h, speed);
        }
        void reset(float cx, float cy, float w, float h, float speed) {
            x = cx + (R.nextFloat() - 0.5f) * w;
            y = cy + (R.nextFloat() - 0.5f) * h;
            double th = R.nextDouble() * 2 * Math.PI;
            vx = (float) Math.cos(th); vy = (float) Math.sin(th);
            velocity = (R.nextFloat() * 6f + 4f) * speed;
            lifeMs = R.nextInt(2000) + 1000L;
            currentMs = 0L;
            life = 1f;
            alphaType = R.nextInt(LEVELS.length);
            alpha = LEVELS[alphaType];
            size = R.nextFloat() * 1.5f + 1f;
        }
        boolean update(int dt) {
            currentMs += dt;
            if (currentMs >= lifeMs) return false;
            float step = velocity * dt / 500f;
            x += vx * step; y += vy * step;
            life = 1f - currentMs / (float) lifeMs;
            return true;
        }
        boolean inside(float x1, float y1, float x2, float y2) {
            return x >= x1 && x <= x2 && y >= y1 && y <= y2;
        }
    }
}
