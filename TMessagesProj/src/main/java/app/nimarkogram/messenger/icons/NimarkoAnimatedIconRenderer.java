/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.icons;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;

import org.telegram.messenger.R;

public final class NimarkoAnimatedIconRenderer {
    private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint tintPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint slashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF target = new RectF();
    private final RectF partTarget = new RectF(0, 0, 100, 100);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path recordShape = new Path();
    private final NimarkoAnimatedIconSpec.DeletePose deletePose = new NimarkoAnimatedIconSpec.DeletePose();
    private final float[] motion = new float[6];

    public NimarkoAnimatedIconRenderer() {
        tintPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.SRC_IN));
        slashPaint.setColor(0xffffffff);
        slashPaint.setStyle(Paint.Style.STROKE);
        slashPaint.setStrokeCap(Paint.Cap.ROUND);
        dotPaint.setColor(0xffffffff);
    }

    public boolean draw(Canvas canvas, NimarkoAnimatedIconSpec spec, float frame, int totalFrames,
                        RectF bounds, Paint source, long layerColor) {
        return draw(canvas, spec, frame, totalFrames, bounds, source, layerColor, false);
    }

    public boolean draw(Canvas canvas, NimarkoAnimatedIconSpec spec, float frame, int totalFrames,
                        RectF bounds, Paint source, long layerColor, boolean sourceForeground) {
        NimarkoIconResources.NavigationIcons icons = NimarkoIconResources.activeNavigationIcons();
        if (icons == null || bounds.width() <= 0 || bounds.height() <= 0) return false;
        if (spec.recordDelete != 0) {
            return drawRecordDelete(canvas, icons, spec, frame, bounds, source, layerColor);
        }
        for (int id : spec.icons) {
            NimarkoIconResources.PreparedIcon icon = icons.getIcon(Math.abs(id));
            if (icon == null || icon.bitmap.isRecycled()) return false;
        }
        int segment = spec.segment(frame);
        int current = spec.icons[segment];
        int next = spec.icons[Math.min(segment + 1, spec.icons.length - 1)];
        float progress = spec.progress(segment, frame);
        float size = Math.min(bounds.width(), bounds.height()) * spec.scale;
        int save = canvas.save();
        canvas.clipRect(bounds);
        spec.sampleMotion(frame, motion);
        if (spec.hasMotion()) {
            float radians = (float) Math.toRadians(motion[4]);
            float cos = Math.abs((float) Math.cos(radians));
            float sin = Math.abs((float) Math.sin(radians));
            float extentX = size * .5f * (cos * motion[2] + sin * motion[3]);
            float extentY = size * .5f * (sin * motion[2] + cos * motion[3]);
            float offsetX = bounds.width() * motion[0];
            float offsetY = bounds.height() * motion[1];
            float fit = Math.min(1f, Math.min(bounds.width() * .5f / Math.max(.0001f, Math.abs(offsetX) + extentX),
                    bounds.height() * .5f / Math.max(.0001f, Math.abs(offsetY) + extentY)));
            canvas.translate(offsetX * fit, offsetY * fit);
            canvas.rotate(motion[4], bounds.centerX(), bounds.centerY());
            canvas.scale(motion[2] * fit, motion[3] * fit, bounds.centerX(), bounds.centerY());
        }
        if (Math.abs(current) == Math.abs(next)) {
            float phase = spec.pulsePhase(frame, totalFrames);
            float pulseScale = spec.pulse && !spec.hasMotion() ? 1 - .035f * (float) Math.sin(Math.PI * phase) : 1;
            float slashStart = current < 0 && next >= 0 ? progress : 0;
            float slashEnd = current < 0 ? 1 : next < 0 ? progress : 0;
            drawIcon(canvas, icons.getIcon(Math.abs(current)), bounds, source, layerColor, size * pulseScale, motion[5], 0, slashStart, slashEnd, sourceForeground);
        } else {
            float movement = spec.alignedTransition || spec.hasMotion() ? 0 : 1;
            drawIcon(canvas, icons.getIcon(Math.abs(current)), bounds, source, layerColor, size * (1 - .06f * progress * movement), (1 - progress) * motion[5], 0, 0, current < 0 ? 1 : 0, sourceForeground);
            drawIcon(canvas, icons.getIcon(Math.abs(next)), bounds, source, layerColor, size * (1 - .06f * (1 - progress) * movement), progress * motion[5], 0, 0, next < 0 ? 1 : 0, sourceForeground);
        }
        canvas.restoreToCount(save);
        return true;
    }

    private boolean drawRecordDelete(Canvas canvas, NimarkoIconResources.NavigationIcons icons,
                                     NimarkoAnimatedIconSpec spec, float frame, RectF bounds, Paint source, long layerColor) {
        if (icons.selection == 0) return false;
        NimarkoIconResources.PreparedIcon body = icons.getIcon(R.drawable.ng_record_delete_body);
        NimarkoIconResources.PreparedIcon lid = icons.getIcon(R.drawable.ng_record_delete_lid);
        boolean ready = usable(body) && usable(lid);
        NimarkoIconResources.PreparedIcon fallback = ready ? null : icons.getIcon(spec.icons[0]);
        if (source.getAlpha() == 0) return true;
        if (!ready && !usable(fallback)) return false;
        int save = canvas.save();
        canvas.clipRect(bounds);
        int composite = canvas.saveLayerAlpha(bounds, source.getAlpha());
        bitmapPaint.setColorFilter(null);
        bitmapPaint.setAlpha(255);
        float size = Math.min(bounds.width(), bounds.height()) * spec.scale;
        int transform = canvas.save();
        canvas.translate(bounds.centerX() - size / 2, bounds.centerY() - size / 2);
        canvas.scale(size / 100, size / 100);
        if (ready) {
            spec.sampleRecordDelete(frame, deletePose);
            if (deletePose.shapeAlpha > 0) {
                int dot = canvas.save();
                canvas.translate(50.25f, deletePose.shapeY);
                NimarkoRecordDeleteShape.sample(spec.recordDelete, deletePose.frame, recordShape);
                dotPaint.setAlpha(Math.round(255 * deletePose.shapeAlpha));
                canvas.drawPath(recordShape, dotPaint);
                canvas.restoreToCount(dot);
            }
            if (deletePose.bodyAlpha > 0) {
                int part = canvas.save();
                canvas.translate(deletePose.bodyX, deletePose.bodyBottom);
                canvas.scale(deletePose.bodyWidth / 60, deletePose.bodyHeight / 60);
                canvas.translate(-50, -92);
                bitmapPaint.setAlpha(Math.round(255 * deletePose.bodyAlpha));
                canvas.drawBitmap(body.bitmap, null, partTarget, bitmapPaint);
                canvas.restoreToCount(part);
            }
            if (deletePose.lidAlpha > 0) {
                int part = canvas.save();
                canvas.translate(50.168f, deletePose.lidY);
                canvas.rotate(deletePose.lidRotation);
                canvas.scale(deletePose.lidScaleX, deletePose.lidScaleY);
                canvas.translate(-50.168f, -21);
                bitmapPaint.setAlpha(255);
                canvas.drawBitmap(lid.bitmap, null, partTarget, bitmapPaint);
                canvas.restoreToCount(part);
            }
        } else {
            canvas.drawBitmap(fallback.bitmap, null, partTarget, bitmapPaint);
        }
        canvas.restoreToCount(transform);
        boolean hasLayerColor = (layerColor & 0x100000000L) != 0;
        tintPaint.setColor(hasLayerColor ? (int) layerColor : 0xffffffff);
        tintPaint.setColorFilter(hasLayerColor ? null : source.getColorFilter());
        tintPaint.setShader(source.getShader());
        canvas.drawRect(bounds, tintPaint);
        tintPaint.setShader(null);
        tintPaint.setColorFilter(null);
        canvas.restoreToCount(composite);
        canvas.restoreToCount(save);
        return true;
    }

    private static boolean usable(NimarkoIconResources.PreparedIcon icon) {
        return icon != null && !icon.bitmap.isRecycled();
    }

    private void drawIcon(Canvas canvas, NimarkoIconResources.PreparedIcon icon, RectF bounds, Paint source,
                          long layerColor, float size, float alpha, float rotation, float slashStart, float slashEnd, boolean sourceForeground) {
        if (alpha <= 0 || source.getAlpha() == 0) return;
        float cx = bounds.centerX(), cy = bounds.centerY();
        float factor = size / Math.max(icon.bitmap.getWidth(), icon.bitmap.getHeight());
        float w = icon.bitmap.getWidth() * factor / 2f, h = icon.bitmap.getHeight() * factor / 2f;
        target.set(cx - w, cy - h, cx + w, cy + h);
        boolean preserveColor = icon.noTint && !sourceForeground;
        int opacity = Math.round(source.getAlpha() * alpha);
        bitmapPaint.setAlpha(255);
        bitmapPaint.setColorFilter(null);
        int save = preserveColor && opacity == 255 ? canvas.save() : canvas.saveLayerAlpha(bounds, opacity);
        canvas.save();
        canvas.rotate(rotation, cx, cy);
        canvas.drawBitmap(icon.bitmap, null, target, bitmapPaint);
        if (slashEnd > slashStart) {
            slashPaint.setStrokeWidth(size / 15f);
            slashPaint.setAlpha(255);
            float d = size * .32f;
            canvas.drawLine(cx - d + 2 * d * slashStart, cy - d + 2 * d * slashStart,
                    cx - d + 2 * d * slashEnd, cy - d + 2 * d * slashEnd, slashPaint);
        }
        canvas.restore();
        if (!preserveColor) {
            boolean hasLayerColor = (layerColor & 0x100000000L) != 0;
            tintPaint.setColor(hasLayerColor ? (int) layerColor : 0xffffffff);
            tintPaint.setColorFilter(hasLayerColor ? null : source.getColorFilter());
            tintPaint.setShader(source.getShader());
            canvas.drawRect(bounds, tintPaint);
            tintPaint.setShader(null);
            tintPaint.setColorFilter(null);
        }
        canvas.restoreToCount(save);
    }
}
