/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.QrShare;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;

import com.google.zxing.EncodeHintType;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.google.zxing.qrcode.encoder.Encoder;

import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.TelegramQRCodeWriter;

import java.util.HashMap;
import java.util.Random;

public final class QrCodeLayer {

    public static final class Code {
        Bitmap mask;
        int size;
        final Rect maskSrc = new Rect();
        int atlasY;
        int n;
        float cell;
        int pad = 16;
        boolean[] dark;
        float[] noise;
        int logoFrom, logoTo;
        int imageSize;
        int hm;
        float[] hcx, hcy, hdelay;
        boolean[] hon;
        int[] hsrc;
    }

    private static void prepareHalftone(Code c) {
        if (c.hcx != null) return;
        int m = 0;
        for (int y = 0; y < c.n; y++)
            for (int x = 0; x < c.n; x++)
                if (!(x >= c.logoFrom && x < c.logoTo && y >= c.logoFrom && y < c.logoTo)) m++;
        c.hm = m;
        c.hcx = new float[m];
        c.hcy = new float[m];
        c.hdelay = new float[m];
        c.hon = new boolean[m];
        c.hsrc = new int[m * 4];
        final float mid = (c.n - 1) / 2f, maxD = mid * 1.4142f;
        int j = 0;
        for (int y = 0; y < c.n; y++) {
            for (int x = 0; x < c.n; x++) {
                if (x >= c.logoFrom && x < c.logoTo && y >= c.logoFrom && y < c.logoTo) continue;
                final int i = y * c.n + x;
                final float d = (float) Math.hypot(x - mid, y - mid) / maxD;
                final float n4 = fract(c.noise[i] * 3.17f);
                final boolean finder = inFinder(c, x, y);
                c.hcx[j] = c.pad + (x + 0.5f) * c.cell;
                c.hcy[j] = c.pad + (y + 0.5f) * c.cell;
                c.hdelay[j] = finder ? d * 0.5f * 0.85f : d * 0.5f + n4 * 0.06f;
                c.hon[j] = c.dark[i];
                c.hsrc[j * 4] = x;
                c.hsrc[j * 4 + 1] = y;
                j++;
            }
        }
    }

    static Code build(String link, int px) {
        return build(link, px, ErrorCorrectionLevel.M, 0.75f, null);
    }

    public static Code build(String link, int px, ErrorCorrectionLevel level, float radiusFactor, android.graphics.drawable.Drawable center) {
        try {
            final HashMap<EncodeHintType, Object> hints = new HashMap<>();
            hints.put(EncodeHintType.ERROR_CORRECTION, level);
            hints.put(EncodeHintType.MARGIN, 0);
            final TelegramQRCodeWriter writer = new TelegramQRCodeWriter();
            if (center != null) writer.setCenterDrawable(center);
            final Code c = new Code();
            final Bitmap code = writer.encode(link, px, px, hints, null, radiusFactor, 0x00000000, 0xFFFFFFFF);
            c.size = code.getWidth();
            c.n = Encoder.encode(link, level, hints).getMatrix().getWidth();
            c.cell = (c.size - 2f * c.pad) / c.n;
            c.dark = new boolean[c.n * c.n];
            c.noise = new float[c.n * c.n];
            final Random r = new Random(link.hashCode());
            for (int y = 0; y < c.n; y++) {
                for (int x = 0; x < c.n; x++) {
                    final int sx = (int) (c.pad + (x + 0.5f) * c.cell), sy = (int) (c.pad + (y + 0.5f) * c.cell);
                    boolean ink = false;
                    for (int k = -1; k <= 1 && !ink; k++) {
                        final int px2 = Math.max(0, Math.min(c.size - 1, sx + (int) (k * c.cell * 0.3f)));
                        ink = Color.alpha(code.getPixel(px2, sy)) > 40;
                    }
                    c.dark[y * c.n + x] = ink;
                    c.noise[y * c.n + x] = r.nextFloat();
                }
            }
            final Bitmap atlas = atlas();
            c.atlasY = c.size + 2;
            c.mask = Bitmap.createBitmap(Math.max(c.size, atlas.getWidth()), c.atlasY + atlas.getHeight(), Bitmap.Config.ARGB_8888);
            final Canvas sheet = new Canvas(c.mask);
            sheet.drawBitmap(code, 0, 0, null);
            sheet.drawBitmap(atlas, 0, c.atlasY, null);
            code.recycle();
            c.mask.prepareToDraw();
            c.maskSrc.set(0, 0, c.size, c.size);
            c.imageSize = writer.getImageSize();
            final int logo = Math.round(writer.getImageSize() / c.cell) + 2;
            c.logoFrom = (c.n - logo) / 2;
            c.logoTo = c.logoFrom + logo;
            return c;
        } catch (Exception e) {
            return null;
        }
    }

    static final int MODE_DUST = 0, MODE_BURST = 1, MODE_ASSEMBLE = 2;
    private static final long ASSEMBLE_MS = 380;

    private static final int CAP = 2400;
    private final float[] px = new float[CAP], py = new float[CAP], pvx = new float[CAP], pvy = new float[CAP],
        psize = new float[CAP], plife = new float[CAP], page = new float[CAP], prot = new float[CAP], pvr = new float[CAP], pfloor = new float[CAP];
    private final byte[] pkind = new byte[CAP];
    private int pcount;

    private Code current, previous;
    private float progress = 1f;
    private long swapStart;
    private int mode = MODE_DUST;
    private static final long DUST_MS = 450, BURST_MS = 450;
    private final RectF card = new RectF();

    void setCardBounds(float l, float t, float r, float b) {
        card.set(l, t, r, b);
    }

    private static float srgbToLin(float c) {return c <= 0.04045f ? c / 12.92f : (float) Math.pow((c + 0.055f) / 1.055f, 2.4f);}

    private static float linToSrgb(float c) {return c <= 0.0031308f ? c * 12.92f : 1.055f * (float) Math.pow(c, 1 / 2.4f) - 0.055f;}

    static int mixOklab(int a, int b, float t) {
        if (t <= 0f) return a;
        if (t >= 1f) return b;
        final float ar = srgbToLin(Color.red(a) / 255f), ag = srgbToLin(Color.green(a) / 255f), ab = srgbToLin(Color.blue(a) / 255f);
        final float br = srgbToLin(Color.red(b) / 255f), bg = srgbToLin(Color.green(b) / 255f), bb = srgbToLin(Color.blue(b) / 255f);
        float l1 = (float) Math.cbrt(0.4122214708f * ar + 0.5363325363f * ag + 0.0514459929f * ab);
        float m1 = (float) Math.cbrt(0.2119034982f * ar + 0.6806995451f * ag + 0.1073969566f * ab);
        float s1 = (float) Math.cbrt(0.0883024619f * ar + 0.2817188376f * ag + 0.6299787005f * ab);
        float l2 = (float) Math.cbrt(0.4122214708f * br + 0.5363325363f * bg + 0.0514459929f * bb);
        float m2 = (float) Math.cbrt(0.2119034982f * br + 0.6806995451f * bg + 0.1073969566f * bb);
        float s2 = (float) Math.cbrt(0.0883024619f * br + 0.2817188376f * bg + 0.6299787005f * bb);
        final float l = l1 + (l2 - l1) * t, m = m1 + (m2 - m1) * t, s = s1 + (s2 - s1) * t;
        final float l3 = l * l * l, m3 = m * m * m, s3 = s * s * s;
        final float r = 4.0767416621f * l3 - 3.3077115913f * m3 + 0.2309699292f * s3;
        final float g = -1.2684380046f * l3 + 2.6097574011f * m3 - 0.3413193965f * s3;
        final float bl = -0.0041960863f * l3 - 0.7034186147f * m3 + 1.7076147010f * s3;
        final int alpha = (int) (Color.alpha(a) + (Color.alpha(b) - Color.alpha(a)) * t);
        return Color.argb(alpha, clamp255(linToSrgb(r)), clamp255(linToSrgb(g)), clamp255(linToSrgb(bl)));
    }

    private static int clamp255(float v) {return Math.max(0, Math.min(255, Math.round(v * 255f)));}

    private boolean[] spawned;
    private final Paint maskPaint = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final Paint particlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect src = new Rect();
    private final RectF dst = new RectF();
    private final Random random = new Random();
    private int color = Color.BLACK;
    private PorterDuffColorFilter filter;
    private long lastFrame;
    private float cardCx, cardCy, codeSize, codeTop;

    void set(Code code) {
        current = code;
        previous = null;
        progress = 1f;
    }

    long assembleDelay = 0;
    private float buildOverride = -1f;
    private long introAt;

    public void setBuildOverride(float p) {buildOverride = p;}

    private boolean introRunning;

    private static float introProgress(long el) {
        final float D = Math.max(100f, RimTune.v[RimTune.INTRO_MS]);
        float u = Math.min(1f, el / D);
        final float H = RimTune.v[RimTune.INTRO_HALF] / 100f;
        if (H > 0.01f) {
            final float hn = NAT_HALF;
            u = u < H ? u * hn / H : hn + (u - H) * (1f - hn) / Math.max(0.01f, 1f - H);
        }
        float p = springP(u * ORIG_DONE_S);
        final float E = RimTune.v[RimTune.INTRO_EASE];
        if (E > 0f && u > U80) {
            final float p0 = springP(U80 * ORIG_DONE_S);
            final float m0 = Math.min((springP((U80 + 0.002f) * ORIG_DONE_S) - p0) / 0.002f * (1f - U80), 3f * (1f - p0));
            final float t = (u - U80) / (1f - U80), t2 = t * t, t3 = t2 * t;
            final float herm = (2 * t3 - 3 * t2 + 1) * p0 + (t3 - 2 * t2 + t) * m0 + (-2 * t3 + 3 * t2) * 1f;
            p = p + (herm - p) * E;
        }
        return u >= 1f ? 1f : Math.min(1f, p);
    }

    private static final float ORIG_DONE_S = 0.467f;

    private static float springP(float t) {
        final double z = 0.9, w0 = 2 * Math.PI / 0.62, wd = w0 * Math.sqrt(1 - z * z);
        final double x = 1 - Math.exp(-z * w0 * t) * (Math.cos(wd * t) + z / Math.sqrt(1 - z * z) * Math.sin(wd * t));
        return (float) Math.min(1.0, x * 1.02);
    }

    private static final float NAT_HALF, U80;

    static {
        float h = 0.5f, e = 0.8f;
        for (int i = 0; i <= 1000; i++) {
            if (springP(i / 1000f * ORIG_DONE_S) >= 0.5f) {
                h = i / 1000f;
                break;
            }
        }
        for (int i = 0; i <= 1000; i++) {
            if (springP(i / 1000f * ORIG_DONE_S) >= 0.8f) {
                e = i / 1000f;
                break;
            }
        }
        NAT_HALF = h;
        U80 = e;
    }

    long buildElapsedMs() {
        if (mode != MODE_ASSEMBLE) return Long.MAX_VALUE;
        if (swapStart == 0) return -1;
        final long e = System.currentTimeMillis() - swapStart;
        return e > 1500 ? Long.MAX_VALUE : e;
    }

    float buildProgress() {
        return mode == MODE_ASSEMBLE ? Math.max(0f, progress) : 1f;
    }

    public void assemble(Code code) {
        current = code;
        previous = null;
        mode = MODE_ASSEMBLE;
        progress = 0f;
        swapStart = 0;
        introAt = 0;
        introRunning = false;
    }

    void swapTo(Code code) {
        swapTo(code, MODE_DUST);
    }

    static boolean introPlays() {
        return QrRender.hasVertices() && SharedConfig.getDevicePerformanceClass() == SharedConfig.PERFORMANCE_CLASS_HIGH;
    }

    boolean inIntro() {
        return mode == MODE_ASSEMBLE && progress < 1f;
    }

    void replaceSilently(Code code) {
        current = code;
    }

    void swapTo(Code code, int mode) {
        if (current == null) {
            set(code);
            return;
        }
        if (mode == MODE_DUST && !QrRender.hasVertices()) mode = MODE_BURST;
        previous = progress < 1f && previous != null ? previous : current;
        current = code;
        progress = 0f;
        this.mode = mode;
        swapStart = System.currentTimeMillis();
        if (spawned == null || spawned.length < previous.n * previous.n) spawned = new boolean[previous.n * previous.n];
        else java.util.Arrays.fill(spawned, false);
        burstPending = mode == MODE_BURST;
    }

    private boolean burstPending;

    public boolean isAnimating() {
        return progress < 1f || pcount > 0;
    }

    private int rgb() {
        return color & 0xFFFFFF;
    }

    public void setColor(int color) {
        if (this.color != color || filter == null) {
            this.color = color;
            filter = new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN);
        }
    }

    private float front(Code c, float p) {
        final float e = 1f - (1f - p) * (1f - p) * (0.55f + 0.45f * (1f - p));
        return -1.5f + e * (c.n + 6f);
    }

    public static float logoShare(Code c) {
        return c == null ? 0 : (float) c.imageSize / c.size;
    }

    float logoSize(float size) {
        return current == null ? 0 : current.imageSize * size / current.size;
    }

    public void draw(Canvas canvas, float left, float top, float size) {
        final long now = System.currentTimeMillis();
        final float dt = lastFrame == 0 ? 0.016f : Math.min(0.05f, (now - lastFrame) / 1000f);
        lastFrame = now;
        if (current == null) return;
        if (mode == MODE_ASSEMBLE && progress < 1f && canvas.isHardwareAccelerated() && !introPlays()) {
            if (swapStart == 0) swapStart = now;
            progress = 1f;
            introRunning = false;
        }
        final long ms = mode == MODE_BURST ? BURST_MS : mode == MODE_ASSEMBLE ? ASSEMBLE_MS : DUST_MS;
        if (progress < 1f && swapStart == 0)
            swapStart = now + (mode == MODE_ASSEMBLE ? assembleDelay : 0);
        if (mode == MODE_ASSEMBLE && (buildOverride >= 0f || introRunning)) {
            if (introAt == 0) introAt = now;
            introRunning = true;
            progress = Math.max(progress, introProgress(now - introAt));
            if (progress >= 1f) {introRunning = false;}
        } else if (progress < 1f) progress = Math.min(1f, (now - swapStart) / (float) ms);
        maskPaint.setColorFilter(filter);
        cardCx = left + size / 2f;
        cardCy = top + size / 2f;
        codeSize = size;
        codeTop = top;

        if (mode == MODE_ASSEMBLE && progress < 1f) {
            drawAssemble(canvas, current, left, top, size, progress);
        } else if (mode == MODE_BURST && previous != null && progress < 1f) {
            if (burstPending) {
                burstPending = false;
                burstAll(previous, left, top, size);
            }
            final float u = Math.max(0f, progress / 0.92f);
            if (u > 0f) {
                final float sc = popScale(u);
                canvas.save();
                canvas.scale(sc, sc, cardCx, cardCy);
                drawAssemble(canvas, current, left, top, size, u);
                canvas.restore();
            }
        } else if (progress >= 1f || previous == null) {
            drawCode(canvas, current, left, top, size, Float.NaN, true);
        } else {
            drawSwap(canvas, left, top, size);
        }
        drawParticles(canvas, dt);
    }

    private void drawCode(Canvas canvas, Code c, float left, float top, float size, float f, boolean all) {
        final float scale = size / c.size;
        if (all) {
            dst.set(left, top, left + size, top + size);
            canvas.drawBitmap(c.mask, c.maskSrc, dst, maskPaint);
        }
    }

    private android.graphics.BitmapShader oldShader;
    private Bitmap oldShaderFor;
    private final Paint oldPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);

    private void drawSwap(Canvas canvas, float left, float top, float size) {
        final Code o = previous, c = current;
        prepareHalftone(o);
        prepareHalftone(c);
        ensureBuffers(Math.max(o.hm, c.hm));
        if (oldShaderFor != o.mask) {
            oldShader = new android.graphics.BitmapShader(o.mask, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP);
            oldPaint.setShader(oldShader);
            oldShaderFor = o.mask;
        }
        if (maskShaderFor != c.mask) {
            maskShader = new android.graphics.BitmapShader(c.mask, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP);
            modPaint.setShader(maskShader);
            maskShaderFor = c.mask;
        }
        final float fo = front(o, progress), fc = front(c, progress);
        final float so = size / o.size, sc = size / c.size;
        int no = 0, nn = 0;
        boolean allSettled = true;
        for (int j = 0; j < o.hm; j++) {
            if (!o.hon[j]) continue;
            final int x = o.hsrc[j * 4], y = o.hsrc[j * 4 + 1], i = y * o.n + x;
            final float t = (o.n - 1 - y) + o.noise[i] * 3.2f;
            if (fo > t) {
                if (!spawned[i]) {
                    spawned[i] = true;
                    spawn(left + (o.pad + (x + 0.5f) * o.cell) * so, top + (o.pad + (y + 0.5f) * o.cell) * so, o.cell * so);
                }
                continue;
            }
            final float near = 1f - Math.min(1f, (t - fo) / 2.2f);
            final float jx = near > 0 ? (float) Math.sin((x * 13 + y * 7) + progress * 60f) * dpf2(1.4f) * near : 0;
            final float jy = -near * dpf2(1.5f);
            final float gx0 = o.pad + x * o.cell, gy0 = o.pad + y * o.cell, gx1 = gx0 + o.cell, gy1 = gy0 + o.cell;
            quad(dv, dt, dc, no++, left + gx0 * so + jx, top + gy0 * so + jy, left + gx1 * so + jx, top + gy1 * so + jy,
                gx0, gy0, gx1, gy1, ((int) (255 * (1f - near * 0.25f)) << 24) | rgb());
        }
        for (int j = 0; j < c.hm; j++) {
            if (!c.hon[j]) continue;
            final int x = c.hsrc[j * 4], y = c.hsrc[j * 4 + 1], i = y * c.n + x;
            final float t = (c.n - 1 - y) + c.noise[i] * 3.2f + 1.6f;
            if (fc <= t) {
                allSettled = false;
                continue;
            }
            final float a = Math.min(1f, (fc - t) / 2.4f);
            final float gx0 = c.pad + x * c.cell, gy0 = c.pad + y * c.cell, gx1 = gx0 + c.cell, gy1 = gy0 + c.cell;
            final float ks = 0.35f + 0.65f * backOut(a);
            if (a >= 1f || (a > 0.4f && Math.abs(ks - 1f) < 0.01f)) {
                quad(mv, mt, mc, nn++, left + gx0 * sc, top + gy0 * sc, left + gx1 * sc, top + gy1 * sc, gx0, gy0, gx1, gy1, 0xFF000000 | rgb());
            } else {
                allSettled = false;
                final float k = ks, cx = left + (gx0 + gx1) * 0.5f * sc, cy = top + (gy0 + gy1) * 0.5f * sc, hh = c.cell * 0.5f * sc * k;
                quad(mv, mt, mc, nn++, cx - hh, cy - hh, cx + hh, cy + hh, gx0, gy0, gx1, gy1, ((int) (255 * Math.min(1f, a * 2.5f)) << 24) | rgb());
            }
        }
        if (no == 0 && allSettled) {
            progress = 1f;
            dst.set(left, top, left + size, top + size);
            canvas.drawBitmap(c.mask, c.maskSrc, dst, maskPaint);
            return;
        }
        if (no > 0)
            canvas.drawVertices(Canvas.VertexMode.TRIANGLES, no * 8, dv, 0, dt, 0, dc, 0, quadIdx, 0, no * 6, oldPaint);
        if (nn > 0)
            canvas.drawVertices(Canvas.VertexMode.TRIANGLES, nn * 8, mv, 0, mt, 0, mc, 0, quadIdx, 0, nn * 6, modPaint);
        final float l = left + (c.pad + c.logoFrom * c.cell) * sc, tp = top + (c.pad + c.logoFrom * c.cell) * sc;
        final float r = left + (c.pad + c.logoTo * c.cell) * sc, b = top + (c.pad + c.logoTo * c.cell) * sc;
        canvas.save();
        canvas.clipRect(l, tp, r, b);
        dst.set(left, top, left + size, top + size);
        canvas.drawBitmap(c.mask, c.maskSrc, dst, maskPaint);
        canvas.restore();
    }

    private void drawCell(Canvas canvas, Code c, int x, int y, float left, float top, float s, float scale, float alpha) {
        if (x >= c.logoFrom && x < c.logoTo && y >= c.logoFrom && y < c.logoTo) return;
        final int sx = (int) (c.pad + x * c.cell), sy = (int) (c.pad + y * c.cell);
        final int ex = (int) Math.ceil(c.pad + (x + 1) * c.cell), ey = (int) Math.ceil(c.pad + (y + 1) * c.cell);
        src.set(sx, sy, ex, ey);
        final float cx = left + (sx + ex) / 2f * s, cy = top + (sy + ey) / 2f * s;
        final float hw = (ex - sx) / 2f * s * scale, hh = (ey - sy) / 2f * s * scale;
        dst.set(cx - hw, cy - hh, cx + hw, cy + hh);
        maskPaint.setAlpha((int) (255 * alpha));
        canvas.drawBitmap(previous != null && c == previous ? c.mask : c.mask, src, dst, maskPaint);
        maskPaint.setAlpha(255);
    }

    private static float backOut(float u) {
        final float c1 = 2.2f, c3 = c1 + 1f;
        u -= 1f;
        return 1f + c3 * u * u * u + c1 * u * u;
    }

    private static float popScale(float u) {
        if (u >= 1f) return 1f;
        if (u < 0.45f) {
            final float k = u / 0.45f;
            return 0.92f + 0.12f * (1f - (1f - k) * (1f - k));
        }
        final float k = (u - 0.45f) / 0.55f;
        return 1.04f - 0.04f * k * k * (3f - 2f * k);
    }

    private static float pop(float u) {
        if (u <= 0f) return 0f;
        if (u >= 1f) return 1f;
        if (u < 0.6f) {
            final float k = u / 0.6f;
            return 1.08f * (1f - (1f - k) * (1f - k) * (1f - k));
        }
        final float k = (u - 0.6f) / 0.4f;
        return 1.08f - 0.08f * k * k * (3f - 2f * k);
    }

    private static boolean inFinder(Code c, int x, int y) {
        return (x < 7 && y < 7) || (x >= c.n - 7 && y < 7) || (x < 7 && y >= c.n - 7);
    }

    private void drawAssemble(Canvas canvas, Code c, float left, float top, float size, float p) {
        if (mode == MODE_ASSEMBLE) {
            drawVariant(canvas, c, left, top, size, p);
            return;
        }
        final float s = size / c.size;
        final float mid = (c.n - 1) / 2f, maxD = mid * 1.4142f;
        for (int y = 0; y < c.n; y++) {
            for (int x = 0; x < c.n; x++) {
                final int i = y * c.n + x;
                if (!c.dark[i] || inFinder(c, x, y)) continue;
                final float d = (float) Math.hypot(x - mid, y - mid) / maxD;
                final float u = (p - (0.05f + d * 0.52f + c.noise[i] * 0.06f)) / 0.367f;
                if (u <= 0f) continue;
                final float e = u >= 1f ? 1f : 1f - (1f - u) * (1f - u) * (1f - u);
                drawCell(canvas, c, x, y, left, top, s, mode == MODE_ASSEMBLE ? 0.4f + 0.6f * e : pop(u), mode == MODE_ASSEMBLE ? e : Math.min(1f, u * 6f));
            }
        }
        drawFinders(canvas, c, left, top, size, p);
    }

    private static float fract(float v) {return v - (float) Math.floor(v);}

    private static float easeOut(float u) {
        u = Math.max(0f, Math.min(1f, u));
        return 1f - (1f - u) * (1f - u) * (1f - u);
    }

    private static final int ATLAS_STEPS = 8, ATLAS_CELL = 48;
    private static Bitmap atlas;
    private android.graphics.BitmapShader maskShader;
    private Bitmap maskShaderFor;
    private final Paint modPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private float[] dv, dt, mv, mt;
    private int[] dc, mc;
    private short[] quadIdx;

    private static synchronized Bitmap atlas() {
        if (atlas == null) {
            atlas = Bitmap.createBitmap(ATLAS_CELL * ATLAS_STEPS, ATLAS_CELL, Bitmap.Config.ARGB_8888);
            final Canvas ac = new Canvas(atlas);
            final Paint ap = new Paint(Paint.ANTI_ALIAS_FLAG);
            ap.setColor(Color.WHITE);
            final RectF r = new RectF();
            for (int k = 0; k < ATLAS_STEPS; k++) {
                final float round = 1f - 0.7f * k / (ATLAS_STEPS - 1f);
                final float h = ATLAS_CELL / 2f - 1.5f;
                r.set(k * ATLAS_CELL + 1.5f, 1.5f, k * ATLAS_CELL + ATLAS_CELL - 1.5f, ATLAS_CELL - 1.5f);
                ac.drawRoundRect(r, h * round, h * round, ap);
            }
        }
        return atlas;
    }

    private void ensureBuffers(int m) {
        if (dv != null && dv.length >= m * 8) return;
        dv = new float[m * 8];
        dt = new float[m * 8];
        dc = new int[m * 4];
        mv = new float[m * 8];
        mt = new float[m * 8];
        mc = new int[m * 4];
        quadIdx = new short[m * 6];
        for (int q = 0; q < m; q++) {
            final int b = q * 4;
            quadIdx[q * 6] = (short) b;
            quadIdx[q * 6 + 1] = (short) (b + 1);
            quadIdx[q * 6 + 2] = (short) (b + 2);
            quadIdx[q * 6 + 3] = (short) b;
            quadIdx[q * 6 + 4] = (short) (b + 2);
            quadIdx[q * 6 + 5] = (short) (b + 3);
        }
    }

    private static void quad(float[] v, float[] t, int[] col, int q, float l, float tp, float r, float b, float tl, float tt, float tr, float tb, int color) {
        final int o = q * 8;
        v[o] = l;
        v[o + 1] = tp;
        v[o + 2] = r;
        v[o + 3] = tp;
        v[o + 4] = r;
        v[o + 5] = b;
        v[o + 6] = l;
        v[o + 7] = b;
        t[o] = tl;
        t[o + 1] = tt;
        t[o + 2] = tr;
        t[o + 3] = tt;
        t[o + 4] = tr;
        t[o + 5] = tb;
        t[o + 6] = tl;
        t[o + 7] = tb;
        final int c4 = q * 4;
        col[c4] = color;
        col[c4 + 1] = color;
        col[c4 + 2] = color;
        col[c4 + 3] = color;
    }

    private static float easeInOutPoly(float u) {
        u = u < 0f ? 0f : (u > 1f ? 1f : u);
        if (u < 0.5f) return 4f * u * u * u;
        final float v = -2f * u + 2f;
        return 1f - v * v * v * 0.5f;
    }

    private void drawHalftone(Canvas canvas, Code c, float left, float top, float size, float p) {
        prepareHalftone(c);
        ensureBuffers(2 * c.hm + c.n * c.n);
        if (maskShaderFor != c.mask) {
            maskShader = new android.graphics.BitmapShader(c.mask, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP);
            modPaint.setShader(maskShader);
            maskShaderFor = c.mask;
        }
        final float s = size / c.size;
        final float intro = Math.min(1f, Math.max(0f, p) / 0.1f);
        final float half = c.cell * 0.5f;
        int nd = 0, nm = 0;
        boolean allDone = true;
        for (int j = 0; j < c.hm; j++) {
            final float e = easeInOutPoly((p - c.hdelay[j]) / 0.434f);
            final float cx = left + c.hcx[j] * s, cy = top + c.hcy[j] * s;
            if (c.hon[j]) {
                final float k = e > 0.75f ? (e - 0.75f) * 4f : 0f;
                if (k < 1f) {
                    allDone = false;
                    final float hs = half * s * (0.2f + 0.8f * e);
                    final int step = Math.min(ATLAS_STEPS - 1, (int) (e * (ATLAS_STEPS - 1) + 0.5f));
                    final float a = intro * (1f - k);
                    if (a > 0.004f) quad(dv, dt, dc, nd++, cx - hs, cy - hs, cx + hs, cy + hs,
                        step * ATLAS_CELL, c.atlasY, step * ATLAS_CELL + ATLAS_CELL, c.atlasY + ATLAS_CELL, ((int) (255 * a) << 24) | rgb());
                }
                if (k > 0f) {
                    final int o = j * 4;
                    final float gx0 = c.pad + c.hsrc[o] * c.cell, gy0 = c.pad + c.hsrc[o + 1] * c.cell;
                    final float gx1 = gx0 + c.cell, gy1 = gy0 + c.cell;
                    quad(mv, mt, mc, nm++, left + gx0 * s, top + gy0 * s, left + gx1 * s, top + gy1 * s, gx0, gy0, gx1, gy1, ((int) (255 * intro) << 24) | rgb());
                }
            } else {
                if (e < 1f) allDone = false;
                final float sc = 0.2f * (1f - e);
                final float fade = e < 0.7f ? 1f : 1f - (e - 0.7f) / 0.3f;
                final float a = intro * 0.9f * fade;
                if (sc > 0.01f && a > 0.004f) {
                    final float hs = half * s * sc;
                    quad(dv, dt, dc, nd++, cx - hs, cy - hs, cx + hs, cy + hs, 0, c.atlasY, ATLAS_CELL, c.atlasY + ATLAS_CELL, ((int) (255 * a) << 24) | rgb());
                }
            }
        }
        final float le = easeOut((p - 0.05f) / 0.45f);
        if (le > 0f) {
            for (int y = c.logoFrom; y < c.logoTo; y++)
                for (int x = c.logoFrom; x < c.logoTo; x++) {
                    if (!c.dark[y * c.n + x]) continue;
                    final float gx0 = c.pad + x * c.cell, gy0 = c.pad + y * c.cell;
                    quad(mv, mt, mc, nm++, left + gx0 * s, top + gy0 * s, left + (gx0 + c.cell) * s, top + (gy0 + c.cell) * s, gx0, gy0, gx0 + c.cell, gy0 + c.cell, ((int) (255 * intro * le) << 24) | rgb());
                }
        }
        if (allDone && intro >= 1f) {
            dst.set(left, top, left + size, top + size);
            canvas.drawBitmap(c.mask, c.maskSrc, dst, maskPaint);
            return;
        }
        if (nd > 0) {
            System.arraycopy(dv, 0, mv, nm * 8, nd * 8);
            System.arraycopy(dt, 0, mt, nm * 8, nd * 8);
            System.arraycopy(dc, 0, mc, nm * 4, nd * 4);
        }
        final int q = nm + nd;
        if (q > 0)
            canvas.drawVertices(Canvas.VertexMode.TRIANGLES, q * 8, mv, 0, mt, 0, mc, 0, quadIdx, 0, q * 6, modPaint);
    }

    private void drawVariant(Canvas canvas, Code c, float left, float top, float size, float p) {
        drawHalftone(canvas, c, left, top, size, p);
    }

    private void drawFinders(Canvas canvas, Code c, float left, float top, float size, float p) {
        final float s = size / c.size;
        for (int f = 0; f < 3; f++) {
            final int fx = f == 1 ? c.n - 7 : 0, fy = f == 2 ? c.n - 7 : 0;
            final float u = p / 0.32f;
            if (u <= 0f) continue;
            final float l = left + (c.pad + fx * c.cell) * s, t = top + (c.pad + fy * c.cell) * s;
            final float r = left + (c.pad + (fx + 7) * c.cell) * s, b = top + (c.pad + (fy + 7) * c.cell) * s;
            final float sc = pop(u);
            canvas.save();
            canvas.scale(sc, sc, (l + r) / 2f, (t + b) / 2f);
            canvas.clipRect(l - 1, t - 1, r + 1, b + 1);
            dst.set(left, top, left + size, top + size);
            maskPaint.setAlpha(255);
            canvas.drawBitmap(c.mask, c.maskSrc, dst, maskPaint);
            canvas.restore();
        }
        maskPaint.setAlpha(255);
    }

    private int add() {
        if (pcount >= CAP) return -1;
        return pcount++;
    }

    private void spawn(float x, float y, float size) {
        final float dx = x - cardCx, dy = y - cardCy, d = Math.max(1f, (float) Math.hypot(dx, dy));
        final float rx = dx / d, ry = dy / d;
        final float roll = random.nextFloat();
        final int count = roll < 0.12f ? 2 : (roll < 0.7f ? 3 : 5);
        for (int k = 0; k < count; k++) {
            final int i = add();
            if (i < 0) return;
            final float kindRoll = random.nextFloat();
            final boolean spark = k > 0 && kindRoll < 0.3f;
            pkind[i] = (byte) (spark ? 2 : (random.nextFloat() < 0.14f ? 1 : 0));
            px[i] = x + (random.nextFloat() - 0.5f) * size * 0.5f;
            py[i] = y + (random.nextFloat() - 0.5f) * size * 0.5f;
            if (spark) {
                psize[i] = dpf2(0.9f + random.nextFloat() * 0.9f);
            } else if (k == 0 && roll < 0.12f) {
                psize[i] = size * (1.25f + random.nextFloat() * 0.45f);
            } else {
                psize[i] = size * (0.3f + random.nextFloat() * 0.55f);
            }
            final float speed = dp(110) + random.nextFloat() * dp(150);
            final float radial = 0.45f + random.nextFloat() * 0.35f;
            pvx[i] = rx * speed * radial + (random.nextFloat() - 0.5f) * dp(90);
            pvy[i] = ry * speed * radial - speed * (1f - radial) - dp(30);
            plife[i] = 0.30f + random.nextFloat() * 0.20f;
            page[i] = 0f;
            prot[i] = random.nextFloat() * 90f;
            pvr[i] = (random.nextFloat() - 0.5f) * 720f;
            pfloor[i] = Float.NaN;
        }
    }

    private void burstAll(Code o, float left, float top, float size) {
        final float so = size / o.size;
        for (int y = 0; y < o.n; y++) {
            for (int x = 0; x < o.n; x++) {
                final int idx = y * o.n + x;
                if (!o.dark[idx] || (x >= o.logoFrom && x < o.logoTo && y >= o.logoFrom && y < o.logoTo)) continue;
                if (random.nextFloat() < 0.4f) continue;
                final int i = add();
                if (i < 0) return;
                final float cx = left + (o.pad + (x + 0.5f) * o.cell) * so, cy = top + (o.pad + (y + 0.5f) * o.cell) * so;
                final float dx = cx - cardCx, d = Math.max(1f, Math.abs(dx));
                final float speed = dp(1000) + random.nextFloat() * dp(900);
                final float a = (float) Math.toRadians((random.nextFloat() - 0.5f) * 36f);
                pkind[i] = (byte) (random.nextFloat() < 0.12f ? 1 : 0);
                px[i] = cx;
                py[i] = cy;
                psize[i] = o.cell * so * (0.7f + random.nextFloat() * 0.6f);
                pvx[i] = (float) Math.sin(a) * speed + Math.signum(dx) * Math.min(1f, d / (size * 0.5f)) * speed * 0.18f;
                pvy[i] = -(float) Math.cos(a) * speed;
                plife[i] = 0.24f + random.nextFloat() * 0.06f;
                page[i] = -((o.n - 1 - y) / 4) * 0.018f;
                prot[i] = random.nextFloat() * 90f;
                pvr[i] = (random.nextFloat() - 0.5f) * 720f;
                pfloor[i] = 1f;
            }
        }
    }

    private void drawParticles(Canvas canvas, float dt) {
        for (int i = pcount - 1; i >= 0; i--) {
            page[i] += dt;
            if (page[i] >= plife[i]) {
                final int last = --pcount;
                px[i] = px[last];
                py[i] = py[last];
                pvx[i] = pvx[last];
                pvy[i] = pvy[last];
                psize[i] = psize[last];
                plife[i] = plife[last];
                page[i] = page[last];
                prot[i] = prot[last];
                pvr[i] = pvr[last];
                pfloor[i] = pfloor[last];
                pkind[i] = pkind[last];
                continue;
            }
            final boolean bouncy = !Float.isNaN(pfloor[i]);
            if (bouncy && page[i] < 0f) {
                particlePaint.setColor(color);
                particlePaint.setAlpha(255);
                final float s0 = psize[i];
                dst.set(px[i] - s0 / 2, py[i] - s0 / 2, px[i] + s0 / 2, py[i] + s0 / 2);
                canvas.drawRoundRect(dst, s0 * 0.3f, s0 * 0.3f, particlePaint);
                continue;
            }
            if (bouncy) {
                final float drag = Math.max(0f, 1f - 6.5f * dt);
                pvx[i] *= drag;
                pvy[i] = pvy[i] * drag - dp(260) * dt;
            } else {
                pvy[i] -= dp(20) * dt;
                pvx[i] *= (1f - 2.4f * dt);
                pvy[i] *= (1f - 3.0f * dt);
                if (!card.isEmpty() && py[i] < card.top - dp(56)) pvy[i] *= 0.85f;
            }
            px[i] += pvx[i] * dt;
            py[i] += pvy[i] * dt;
            prot[i] += pvr[i] * dt;
            final float t = page[i] / plife[i];
            float a = bouncy ? (t < 0.35f ? 1f : 1f - (t - 0.35f) / 0.65f) : (t < 0.08f ? t / 0.08f : 1f - (t - 0.08f) / 0.92f);
            if (!bouncy && !card.isEmpty()) {
                final float over = card.top - dp(56) - py[i];
                if (over > 0) a *= Math.max(0f, 1f - over / dp(24));
            }
            final float shrink = 1f - (1f - (1f - t) * (1f - t)) * (bouncy ? 0.35f : 0.85f);
            final float s = psize[i] * shrink;
            if (pkind[i] == 2) {
                particlePaint.setColor(Color.WHITE);
                particlePaint.setAlpha((int) (255 * a * 0.9f));
                canvas.drawCircle(px[i], py[i], s, particlePaint);
                particlePaint.setAlpha((int) (255 * a * 0.22f));
                canvas.drawCircle(px[i], py[i], s * 2.6f, particlePaint);
                continue;
            }
            float out = 0f;
            if (!card.isEmpty()) {
                final float ox = Math.max(card.left - px[i], Math.max(0f, px[i] - card.right));
                final float oy = Math.max(card.top - py[i], Math.max(0f, py[i] - card.bottom));
                out = Math.min(1f, Math.max(ox, oy) / dp(36));
            }
            particlePaint.setColor(out > 0f ? mixOklab(color, Color.WHITE, out) : color);
            particlePaint.setAlpha((int) (255 * a));
            canvas.save();
            canvas.rotate(prot[i], px[i], py[i]);
            dst.set(px[i] - s / 2, py[i] - s / 2, px[i] + s / 2, py[i] + s / 2);
            canvas.drawRoundRect(dst, s * 0.3f, s * 0.3f, particlePaint);
            if (pkind[i] == 1) {
                final float g = (float) Math.sin(Math.PI * Math.min(1f, t * 1.6f));
                particlePaint.setColor(Color.WHITE);
                particlePaint.setAlpha((int) (200 * a * g));
                canvas.drawRoundRect(dst, s * 0.3f, s * 0.3f, particlePaint);
            }
            canvas.restore();
        }
    }
}
