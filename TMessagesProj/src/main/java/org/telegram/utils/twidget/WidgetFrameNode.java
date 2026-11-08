/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.utils.twidget;

import android.graphics.Canvas;
import android.graphics.Picture;
import android.graphics.RenderNode;
import android.os.Build;

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

        @Override
        public boolean canDraw(Canvas canvas) {
            return canvas.isHardwareAccelerated();
        }

        @Override
        public void setLayered(boolean layered) {
            node.setUseCompositingLayer(layered, null);
        }

        @Override
        public void draw(Canvas canvas, float alpha) {
            if (!node.hasDisplayList()) return;
            node.setAlpha(alpha);
            canvas.drawRenderNode(node);
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
