/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.utils.twidget;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.os.Build;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Looper;
import android.os.Trace;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;

final class WidgetSandboxBackend implements WidgetSandboxPainter.Resources, WidgetCanvas.Resources {
    private static final long MEMORY_BUDGET = 32L * 1024 * 1024;
    private final WidgetSandboxSession owner;
    private final java.util.concurrent.ConcurrentHashMap<Integer, Paint> fonts = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<Integer, Paint> deviceFonts = new java.util.concurrent.ConcurrentHashMap<>();
    private WidgetText text;
    private final java.util.concurrent.ConcurrentHashMap<Integer, Bitmap> images = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<Integer, WidgetCanvas> canvases = new java.util.concurrent.ConcurrentHashMap<>();
    private final HashMap<Integer, JSONArray> pendingLayers = new HashMap<>();
    private long layerParseNanos;
    private HashMap<Long, LayerContent> layerContents = new HashMap<>();
    private final WidgetSandboxPainter painter = new WidgetSandboxPainter();
    private final Rect bounds = new Rect();
    private int nextFont;
    private long imageBytes;

    WidgetSandboxBackend(WidgetSandboxSession owner) {
        this.owner = owner;
    }

    float[] createFont(byte[] encodedFamilies, float size, int weight, boolean italic, byte[] encodedFeatures) {
        assertWorker();
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
        paint.setTextSize(size);
        paint.setTypeface(typeface(text(encodedFamilies), weight, italic));
        String features = featureSettings(text(encodedFeatures));
        if (features != null) paint.setFontFeatureSettings(features);
        float density = owner.density;
        Paint device = new Paint(paint);
        device.setTextSize(size * density);
        Paint.FontMetrics metrics = device.getFontMetrics();
        int id = ++nextFont;
        fonts.put(id, paint);
        deviceFonts.put(id, device);
        device.getTextBounds("x", 0, 1, bounds);
        return new float[] {
            id,
            -metrics.ascent / density,
            metrics.descent / density,
            (metrics.descent - metrics.ascent + metrics.leading) / density,
            bounds.height() / density,
            device.measureText("0") / density
        };
    }

    static String featureSettings(String features) {
        if (features.isEmpty()) return null;
        StringBuilder out = new StringBuilder();
        for (String entry : features.split(",")) {
            int eq = entry.indexOf('=');
            if (eq != 4) continue;
            String tag = entry.substring(0, 4), value = entry.substring(5);
            if (!tag.matches("[\\x20-\\x7e]{4}") || tag.indexOf('"') >= 0 || !value.matches("\\d{1,5}")) continue;
            if (out.length() > 0) out.append(", ");
            out.append('"').append(tag).append("\" ").append(value);
        }
        return out.length() == 0 ? null : out.toString();
    }

    void deleteFont(int id) {
        fonts.remove(id);
        deviceFonts.remove(id);
    }

    float textWidth(int id, byte[] value) {
        Paint paint = deviceFonts.get(id);
        return paint == null ? 0 : paint.measureText(text(value)) / owner.density;
    }

    float[] loadImage(int id, byte[] encodedUrl) {
        String url = text(encodedUrl);
        int comma = url.indexOf(',');
        if (!url.startsWith("data:") || comma < 0 || url.length() > 2 * 1024 * 1024) return null;
        String meta = url.substring(5, comma);
        if (meta.contains("svg")) return null;
        byte[] bytes;
        try {
            String payload = url.substring(comma + 1);
            bytes =
                    meta.contains(";base64")
                            ? Base64.decode(payload, Base64.DEFAULT)
                            : java.net.URLDecoder.decode(payload, "UTF-8")
                                    .getBytes(StandardCharsets.ISO_8859_1);
        } catch (Exception error) {
            return null;
        }
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (options.outWidth <= 0
                || options.outHeight <= 0
                || (long) options.outWidth * options.outHeight > 4 * 1024 * 1024) return null;
        long decodedBytes = (long) options.outWidth * options.outHeight * 4;
        if (imageBytes + decodedBytes > 32L * 1024 * 1024) return null;
        options.inJustDecodeBounds = false;
        Bitmap image = WidgetPng.decodeBroken(bytes);
        if (image == null) image = BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        if (image == null) return null;
        Bitmap old = images.put(id, image);
        imageBytes += image.getAllocationByteCount();
        if (old != null) imageBytes -= old.getAllocationByteCount();
        return new float[] {image.getWidth(), image.getHeight()};
    }

    void canvasRun(int id, int width, int height, int[] ops) throws Exception {
        assertWorker();
        if (width <= 0 || height <= 0) {
            canvasRelease(id);
            return;
        }
        checkCanvasSize(id, width, height);
        WidgetCanvas surface = canvases.get(id);
        if (surface == null) {
            surface = new WidgetCanvas(id);
            canvases.put(id, surface);
        }
        surface.run(width, height, ops, this);
    }

    byte[] canvasRead(int id, int x, int y, int width, int height) {
        assertWorker();
        if (width <= 0 || height <= 0 || (long) width * height > 4 * 1024 * 1024) return null;
        WidgetCanvas surface = canvases.get(id);
        return surface == null ? new byte[width * height * 4] : surface.read(x, y, width, height);
    }

    void canvasRelease(int id) {
        assertWorker();
        WidgetCanvas surface = canvases.remove(id);
        if (surface != null) surface.release();
    }

    private void checkCanvasSize(int id, int width, int height) {
        if ((long) width * height > 4 * 1024 * 1024)
            throw new IllegalArgumentException("Canvas dimensions");
        long bytes = (long) width * height * 8 + imageBytes;
        for (java.util.Map.Entry<Integer, WidgetCanvas> entry : canvases.entrySet())
            if (entry.getKey() != id) bytes += entry.getValue().allocationBytes();
        if (bytes > MEMORY_BUDGET) throw new IllegalArgumentException("Canvas memory budget");
    }

    @Override
    public Paint font(int id) {
        return fonts.get(id);
    }

    @Override
    public Paint deviceFont(int id) {
        return deviceFonts.get(id);
    }

    @Override
    public Bitmap image(int id) {
        return images.get(id);
    }

    @Override
    public Bitmap canvas(int id) {
        WidgetCanvas surface = canvases.get(id);
        return surface == null ? null : surface.snapshot();
    }

    @Override
    public Bitmap canvasBitmap(int id) {
        WidgetCanvas surface = canvases.get(id);
        return surface == null ? null : surface.bitmap();
    }

    byte[] formatIntl(byte[] request) {
        return WidgetSandboxIntl.format(text(request)).getBytes(StandardCharsets.UTF_8);
    }

    void signal(int kind, byte[] message) {
        owner.onWorkerSignal(kind, text(message));
    }

    void selectableText(byte[] encodedRuns) {
        assertWorker();
        try {
            text = WidgetText.parse(text(encodedRuns), deviceFonts::get, owner.density);
        } catch (Exception e) {
            text = null;
        }
    }

    void recordLayer(int index, int version, byte[] encodedList) throws Exception {
        assertWorker();
        long started = System.nanoTime();
        pendingLayers.put(index, new JSONArray(text(encodedList)));
        layerParseNanos += System.nanoTime() - started;
    }

    void record(
            byte[] encodedList,
            byte[] encodedComposition,
            double nativeClock,
            int width,
            int height,
            float density,
            float layoutMs,
            int passes,
            int builds)
            throws Exception {
        assertWorker();
        owner.lastList = encodedList;
        owner.lastComposition = encodedComposition;
        Trace.beginSection("WidgetSandbox:Record");
        long started = System.nanoTime();
        double clockOffset = nativeClock - started / 1e9;
        WidgetFrameNode node = WidgetFrameNode.create("widget-sandbox-content", 0, 0, width, height);
        try {
            JSONArray list = new JSONArray(text(encodedList));
            JSONObject composition = new JSONObject(text(encodedComposition));
            long parsed = System.nanoTime();
            Canvas canvas = node.beginRecording();
            try {
                canvas.scale(density, density);
                painter.paint(canvas, list, this, density);
            } finally {
                node.endRecording();
            }
            WidgetSandboxSession.Layer[] layers = layers(composition, density);
            owner.onRecorded((parsed - started + layerParseNanos) / 1e6f, (System.nanoTime() - parsed) / 1e6f);
            owner.onWorkerFrame(
                    node,
                    layers,
                    clockOffset,
                    width,
                    height,
                    layoutMs,
                    (System.nanoTime() - started) / 1e6f,
                    passes,
                    builds,
                    list.length(),
                    text);
        } catch (Exception error) {
            node.discard();
            throw error;
        } finally {
            pendingLayers.clear();
            layerParseNanos = 0;
            Trace.endSection();
        }
    }

    private WidgetSandboxSession.Layer[] layers(JSONObject composition, float density) {
        JSONArray items = composition.optJSONArray("l");
        int count = items == null ? 0 : items.length();
        HashMap<Long, LayerContent> contents = new HashMap<>();
        WidgetSandboxSession.Layer[] layers = new WidgetSandboxSession.Layer[count];
        int used = 0;
        for (int i = 0; i < count; i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            long id = item.optLong("id");
            long version = item.optLong("cv");
            JSONArray box = item.optJSONArray("b");
            if (box == null) continue;
            int left = (int) Math.floor(box.optDouble(0) * density);
            int top = (int) Math.floor(box.optDouble(1) * density);
            int right = (int) Math.ceil((box.optDouble(0) + box.optDouble(2)) * density);
            int bottom = (int) Math.ceil((box.optDouble(1) + box.optDouble(3)) * density);
            LayerContent content = layerContents.get(id);
            JSONArray list = pendingLayers.get(i);
            if (list != null) {
                WidgetFrameNode node = WidgetFrameNode.create("widget-sandbox-layer", left, top, right, bottom);
                Canvas canvas = node.beginRecording();
                try {
                    canvas.translate(-left, -top);
                    canvas.scale(density, density);
                    painter.paint(canvas, list, this, density);
                } finally {
                    node.endRecording();
                }
                content = new LayerContent(node, (int) version);
            } else if (content == null || content.version != (int) version) {
                continue;
            }
            contents.put(id, content);
            JSONArray clip = item.optJSONArray("l");
            float[] clipBox = null, clipRadii = null;
            if (clip != null) {
                clipBox = new float[4];
                WidgetSandboxPainter.floats(clip, clipBox, 4);
                JSONArray corners = item.optJSONArray("q");
                if (corners != null) {
                    clipRadii = new float[8];
                    WidgetSandboxPainter.floats(corners, clipRadii, 8);
                }
            }
            JSONArray keys = item.optJSONArray("k");
            float[] track = new float[keys == null ? 0 : keys.length() / 8 * 8];
            WidgetSandboxPainter.floats(keys, track, track.length);
            layers[used++] =
                    new WidgetSandboxSession.Layer(
                            content.node,
                            clipBox,
                            clipRadii,
                            item.optDouble("ts"),
                            item.optDouble("td"),
                            item.optInt("tr") != 0,
                            track);
        }
        layerContents = contents;
        if (used == layers.length) return layers;
        WidgetSandboxSession.Layer[] trimmed = new WidgetSandboxSession.Layer[used];
        System.arraycopy(layers, 0, trimmed, 0, used);
        return trimmed;
    }

    private static final class LayerContent {
        final WidgetFrameNode node;
        final int version;

        LayerContent(WidgetFrameNode node, int version) {
            this.node = node;
            this.version = version;
        }
    }

    private static void assertWorker() {
        if (Looper.myLooper() == Looper.getMainLooper())
            throw new IllegalStateException("Native layout and recording must run off UI");
    }

    private static String text(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }

    private static Typeface typeface(String families, int weight, boolean italic) {
        Typeface base = Typeface.SANS_SERIF;
        for (String raw : families.split(",")) {
            String name =
                    raw.trim()
                            .replace("\"", "")
                            .replace("'", "")
                            .toLowerCase(java.util.Locale.ROOT);
            if (name.isEmpty()) continue;
            switch (name) {
                case "-apple-system":
                case "blinkmacsystemfont":
                case "system-ui":
                case "ui-sans-serif":
                case "sans-serif":
                case "segoe ui":
                case "roboto":
                case "inter":
                case "helvetica neue":
                case "helvetica":
                case "arial":
                    base = Typeface.SANS_SERIF;
                    break;
                case "serif":
                case "ui-serif":
                case "georgia":
                case "times new roman":
                case "times":
                    base = Typeface.SERIF;
                    break;
                case "monospace":
                case "ui-monospace":
                case "menlo":
                case "consolas":
                case "courier new":
                case "sf mono":
                    base = Typeface.MONOSPACE;
                    break;
                default:
                    base = Typeface.create(name, Typeface.NORMAL);
                    if (base == Typeface.DEFAULT) continue;
            }
            break;
        }
        if (Build.VERSION.SDK_INT >= 28)
            return Typeface.create(base, Math.max(1, Math.min(1000, weight)), italic);
        return Typeface.create(base, (weight >= 600 ? Typeface.BOLD : 0) | (italic ? Typeface.ITALIC : 0));
    }
}
