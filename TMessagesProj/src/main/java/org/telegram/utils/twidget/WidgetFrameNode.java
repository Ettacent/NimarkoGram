/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.utils.twidget;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorSpace;
import android.graphics.HardwareRenderer;
import android.graphics.Paint;
import android.graphics.Picture;
import android.graphics.PixelFormat;
import android.graphics.RecordingCanvas;
import android.graphics.RenderNode;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;

import org.telegram.messenger.FileLog;
import androidx.annotation.RequiresApi;

public abstract class WidgetFrameNode {

    final int left, top, right, bottom;

    WidgetFrameNode(int left, int top, int right, int bottom) {
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
    }

    static WidgetFrameNode create(String name, int left, int top, int right, int bottom) {
        return Build.VERSION.SDK_INT >= 29
                ? new Node(name, left, top, right, bottom)
                : new PictureNode(left, top, right, bottom);
    }

    abstract Canvas beginRecording();

    abstract void endRecording();

    public abstract boolean recorded();

    abstract void discard();

    public abstract boolean canDraw(Canvas canvas);

    public abstract void draw(Canvas canvas, float alpha);

    public void setLayered(boolean layered) {
    }

    @RequiresApi(29)
    private static final class Node extends WidgetFrameNode {
        private final RenderNode node;

        Node(String name, int left, int top, int right, int bottom) {
            super(left, top, right, bottom);
            node = new RenderNode(name);
            node.setPosition(left, top, right, bottom);
        }

        @Override
        Canvas beginRecording() {
            return node.beginRecording(right - left, bottom - top);
        }

        @Override
        void endRecording() {
            node.endRecording();
        }

        @Override
        public boolean recorded() {
            return node.hasDisplayList();
        }

        @Override
        void discard() {
            node.discardDisplayList();
        }

        private Bitmap software;
        private Paint softwarePaint;
        @Override
        public boolean canDraw(Canvas canvas) {
            return true;
        }

        @Override
        public void setLayered(boolean layered) {
            node.setUseCompositingLayer(layered, null);
        }

        @Override
        public void draw(Canvas canvas, float alpha) {
            if (!node.hasDisplayList()) return;
            if (!canvas.isHardwareAccelerated()) {
                drawSoftware(canvas, alpha);
                return;
            }
            node.setAlpha(alpha);
            canvas.drawRenderNode(node);
        }

        private void drawSoftware(Canvas canvas, float alpha) {
            if (alpha <= 0) return;
            if (software == null) {
                software = render(node, left, top, right - left, bottom - top);
                if (software == null) return;
            }
            if (softwarePaint == null) softwarePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
            softwarePaint.setAlpha(Math.round(Math.min(1, alpha) * 255));
            canvas.drawBitmap(software, left, top, softwarePaint);
        }

        private static Bitmap render(RenderNode node, int left, int top, int width, int height) {
            if (width <= 0 || height <= 0) return null;
            ImageReader reader = null;
            HardwareRenderer renderer = null;
            try {
                reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 1,
                        HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE | HardwareBuffer.USAGE_GPU_COLOR_OUTPUT);
                renderer = new HardwareRenderer();
                renderer.setOpaque(false);
                renderer.setSurface(reader.getSurface());
                RenderNode root = new RenderNode("widget-sandbox-software");
                root.setPosition(0, 0, width, height);
                RecordingCanvas recording = root.beginRecording(width, height);
                recording.translate(-left, -top);
                node.setAlpha(1);
                recording.drawRenderNode(node);
                root.endRecording();
                renderer.setContentRoot(root);
                renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw();
                Image image = reader.acquireNextImage();
                if (image == null) return null;
                try {
                    HardwareBuffer buffer = image.getHardwareBuffer();
                    if (buffer == null) return null;
                    try {
                        Bitmap hardware = Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB));
                        return hardware == null ? null : hardware.copy(Bitmap.Config.ARGB_8888, false);
                    } finally {
                        buffer.close();
                    }
                } finally {
                    image.close();
                }
            } catch (Throwable e) {
                FileLog.e(e);
                return null;
            } finally {
                if (renderer != null) renderer.destroy();
                if (reader != null) reader.close();
            }
        }
    }

    private static final class PictureNode extends WidgetFrameNode {
        private final Picture picture = new Picture();
        private boolean recording;

        PictureNode(int left, int top, int right, int bottom) {
            super(left, top, right, bottom);
        }

        @Override
        Canvas beginRecording() {
            recording = true;
            return picture.beginRecording(Math.max(1, right - left), Math.max(1, bottom - top));
        }

        @Override
        void endRecording() {
            picture.endRecording();
            recording = false;
        }

        @Override
        public boolean recorded() {
            return true;
        }

        @Override
        void discard() {
            if (recording) endRecording();
        }

        @Override
        public boolean canDraw(Canvas canvas) {
            return true;
        }

        @Override
        public void draw(Canvas canvas, float alpha) {
            if (recording || alpha <= 0) return;
            int count;
            if (alpha < 1) {
                count = canvas.saveLayerAlpha(left, top, right, bottom, Math.round(alpha * 255));
            } else {
                count = canvas.save();
            }
            canvas.translate(left, top);
            canvas.drawPicture(picture);
            canvas.restoreToCount(count);
        }
    }
}
