/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.QrShare;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.SweepGradient;

import java.util.HashMap;

public final class QrGlass {

    private QrGlass() {
    }

    public static final int RIM_BIG = RimTune.BIG;

    public static int controlFill() {
        return ((int) (255 * RimTune.v[RimTune.FILL] / 100f + 0.5f) << 24) | 0xFFFFFF;
    }

    private static final float SHEEN_DP = 40;
    private static Bitmap sheenRamp;
    private static BitmapShader sheenShader;
    private static final Paint sheenPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private static final Paint sheenRoundPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private static final RectF sheenDst = new RectF();
    private static final Matrix sheenMatrix = new Matrix();

    private static Bitmap sheenRamp() {
        if (sheenRamp == null) {
            final int n = 64;
            final int[] px = new int[n];
            for (int j = 0; j < n; j++) px[j] = ((int) (0x40 * (1f - (j + 0.5f) / n) + 0.5f) << 24) | 0xFFFFFF;
            sheenRamp = Bitmap.createBitmap(px, 1, n, Bitmap.Config.ARGB_8888);
        }
        return sheenRamp;
    }

    private static int sheenAlpha(boolean light, float alpha) {
        return (int) (255 * Math.max(0f, Math.min(1f, alpha)) * (light ? 1f : 0x14 / (float) 0x40));
    }

    static void drawSheen(Canvas c, RectF r, boolean light, float alpha) {
        sheenDst.set(r.left, r.top, r.right, r.top + Math.min(r.height(), dp(SHEEN_DP)));
        sheenPaint.setAlpha(sheenAlpha(light, alpha));
        c.drawBitmap(sheenRamp(), null, sheenDst, sheenPaint);
    }

    public static void drawSheenRound(Canvas c, RectF r, float radius, boolean light, float alpha) {
        final Bitmap ramp = sheenRamp();
        if (sheenShader == null) {
            sheenShader = new BitmapShader(ramp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            sheenRoundPaint.setShader(sheenShader);
        }
        sheenMatrix.setScale(r.width() / ramp.getWidth(), Math.min(r.height(), dp(SHEEN_DP)) / ramp.getHeight());
        sheenMatrix.postTranslate(r.left, r.top);
        sheenShader.setLocalMatrix(sheenMatrix);
        sheenRoundPaint.setAlpha(sheenAlpha(light, alpha));
        c.drawRoundRect(r, radius, radius, sheenRoundPaint);
    }

    private static final HashMap<Long, Bitmap> rimBakes = new HashMap<>();
    private static final SweepGradient[] rimSweeps = new SweepGradient[RimTune.NG];
    private static int rimVersion = -1;
    private static final Paint rimPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private static final Rect rimSrc = new Rect();
    private static final RectF rimDst = new RectF();
    private static final int[] rimSx = new int[4], rimSy = new int[4];
    private static final float[] rimDx = new float[4], rimDy = new float[4];

    public static boolean drawRim(Canvas c, RectF r, float radius, int grp, float alpha) {
        if (!QrRender.hasNodes()) return false;
        if (alpha <= 0.004f) return true;
        ensureRims();
        final float[] gk = RimTune.g[grp];
        final float inset = dpf2(gk[RimTune.INSET]);
        final float gl = r.left + inset, gt = r.top + inset, gr = r.right - inset, gbt = r.bottom - inset;
        final float w = gr - gl, h = gbt - gt;
        if (w < 1f || h < 1f) return true;
        final float rg = Math.min(Math.max(0f, radius - inset), Math.min(w, h) / 2f);
        final float dw = dpf2(gk[RimTune.D_W]), t = Math.max(dpf2(gk[RimTune.T_TOP]), dpf2(gk[RimTune.T_BOT]));
        final int pad = (int) Math.ceil(dw) + 2;
        final int corner = (int) Math.ceil(rg + t) + 1;
        final int rw = Math.round(w), rh = Math.round(h);
        final boolean sliceX = rw >= 2 * corner + 2, sliceY = rh >= 2 * corner + 2;
        final int ew = sliceX ? 2 * corner + 2 : rw, eh = sliceY ? 2 * corner + 2 : rh;
        final long key = ((long) grp << 56) ^ ((long) Math.round(rg * 4) << 32) ^ ((long) ew << 16) ^ eh;
        Bitmap b = rimBakes.get(key);
        if (b == null) {
            b = bakeRim(grp, rg, ew, eh, pad);
            rimBakes.put(key, b);
        }
        final int nw = b.getWidth(), nh = b.getHeight();
        final int nx = sliceX ? 3 : 1, ny = sliceY ? 3 : 1;
        rimSx[0] = 0;
        rimDx[0] = gl - pad;
        if (sliceX) {
            rimSx[1] = pad + corner;
            rimSx[2] = nw - pad - corner;
            rimDx[1] = gl + corner;
            rimDx[2] = gr - corner;
        }
        rimSx[nx] = nw;
        rimDx[nx] = gr + pad;
        rimSy[0] = 0;
        rimDy[0] = gt - pad;
        if (sliceY) {
            rimSy[1] = pad + corner;
            rimSy[2] = nh - pad - corner;
            rimDy[1] = gt + corner;
            rimDy[2] = gbt - corner;
        }
        rimSy[ny] = nh;
        rimDy[ny] = gbt + pad;
        rimPaint.setAlpha((int) (255 * Math.min(1f, alpha)));
        for (int j = 0; j < ny; j++) {
            for (int i = 0; i < nx; i++) {
                rimSrc.set(rimSx[i], rimSy[j], rimSx[i + 1], rimSy[j + 1]);
                rimDst.set(rimDx[i], rimDy[j], rimDx[i + 1], rimDy[j + 1]);
                c.drawBitmap(b, rimSrc, rimDst, rimPaint);
            }
        }
        return true;
    }

    private static void ensureRims() {
        if (rimVersion == RimTune.version) return;
        rimVersion = RimTune.version;
        rimBakes.clear();
        final int n = 73;
        for (int k = 0; k < RimTune.NG; k++) {
            final float[] gk = RimTune.g[k];
            final int[] cols = new int[n];
            final float[] pos = new float[n];
            for (int i = 0; i < n; i++) {
                final double th = Math.toRadians(i * 5);
                final float side = (float) Math.pow(Math.abs(Math.cos(th)), gk[RimTune.D_EXP]);
                final float sn = (float) Math.sin(th);
                final float tb = (sn < 0 ? gk[RimTune.D_TOP] : gk[RimTune.D_BOT]) / 100f;
                final float a = Math.max(0f, Math.min(1f, tb + (gk[RimTune.D_A] / 100f - tb) * side));
                cols[i] = ((int) (255 * a + 0.5f)) << 24;
                pos[i] = i / (float) (n - 1);
            }
            rimSweeps[k] = new SweepGradient(0, 0, cols, pos);
        }
    }

    private static Bitmap bakeRim(int grp, float rg, int ew, int eh, int pad) {
        final float[] gk = RimTune.g[grp];
        final Bitmap b = Bitmap.createBitmap(ew + 2 * pad, eh + 2 * pad, Bitmap.Config.ARGB_8888);
        final Canvas cv = new Canvas(b);
        final RectF e = new RectF(pad, pad, pad + ew, pad + eh);
        final float dw = dpf2(gk[RimTune.D_W]);
        if (dw > 0.05f) {
            final Matrix m = new Matrix();
            m.setScale(e.width() / Math.max(1f, e.height()), 1f);
            m.postTranslate(e.centerX(), e.centerY());
            final SweepGradient sweep = rimSweeps[grp];
            sweep.setLocalMatrix(m);
            final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeWidth(dw);
            ring.setShader(sweep);
            final RectF rr = new RectF(e);
            rr.inset(-dw / 2f, -dw / 2f);
            cv.drawRoundRect(rr, rg + dw / 2f, rg + dw / 2f, ring);
        }
        final Paint white = new Paint(Paint.ANTI_ALIAS_FLAG);
        white.setColor(Color.WHITE);
        final Path a = new Path(), cut = new Path();
        final RectF shifted = new RectF();
        for (int pass = 0; pass < 2; pass++) {
            final float off = pass == 0 ? dpf2(gk[RimTune.T_TOP]) : -dpf2(gk[RimTune.T_BOT]);
            final float alpha = (pass == 0 ? gk[RimTune.A_TOP] : gk[RimTune.A_BOT]) / 100f;
            if (alpha <= 0.004f || Math.abs(off) < 0.1f) continue;
            a.rewind();
            a.addRoundRect(e, rg, rg, Path.Direction.CW);
            shifted.set(e);
            shifted.offset(0, off);
            cut.rewind();
            cut.addRoundRect(shifted, rg, rg, Path.Direction.CW);
            cv.save();
            QrRender.clipOut(cv, cut);
            white.setAlpha(Math.round(255 * Math.min(1f, alpha)));
            cv.drawPath(a, white);
            cv.restore();
        }
        b.prepareToDraw();
        return b;
    }
}
