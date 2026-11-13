/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.utils.twidget;

import android.graphics.Bitmap;
import android.graphics.Paint;
import android.os.RemoteException;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

final class WidgetEngineBackend {
    private static final int PART = 256 * 1024;

    private final IWidgetEngineCallback client;
    private final float density;
    private static final int MAX_CACHED_WIDTHS = 8192;
    private static final int MAX_CACHED_TEXT = 1024;
    private static final java.util.LinkedHashMap<String, Float> WIDTHS =
            new java.util.LinkedHashMap<String, Float>(4096, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Float> eldest) {
                    return size() > MAX_CACHED_WIDTHS;
                }
            };
    private static final int TEXT_PIECE = 64 * 1024;
    private static volatile boolean localFonts = true;

    private final ConcurrentHashMap<Integer, Paint> deviceFonts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Integer, String> fontKeys = new ConcurrentHashMap<>();
    private final Object largeLock = new Object();
    private int nextFont;
    private long imageBytes;

    WidgetEngineBackend(IWidgetEngineCallback client, float density) {
        this.client = client;
        this.density = density;
    }

    float[] createFont(byte[] encodedFamilies, float size, int weight, boolean italic, byte[] encodedFeatures) throws Exception {
        int id = ++nextFont;
        float[][] metrics = new float[1][];
        call(() -> metrics[0] = client.defineFont(id, encodedFamilies, size, weight, italic, encodedFeatures));
        if (metrics[0] == null || metrics[0].length != 6) throw new IllegalStateException("Widget client: font");
        Paint paint = localFonts ? localFont(encodedFamilies, size, weight, italic, encodedFeatures) : null;
        if (paint != null) deviceFonts.put(id, paint);
        else fontKeys.put(id, WidgetSandboxBackend.text(encodedFamilies) + '\u0001' + size + '\u0001' + weight + '\u0001'
                + italic + '\u0001' + WidgetSandboxBackend.text(encodedFeatures) + '\u0001');
        Stats.fonts++;
        return metrics[0];
    }

    private Paint localFont(byte[] encodedFamilies, float size, int weight, boolean italic, byte[] encodedFeatures) {
        try {
            return WidgetSandboxBackend.paints(WidgetSandboxBackend.text(encodedFamilies), size, weight, italic,
                    WidgetSandboxBackend.text(encodedFeatures), density)[1];
        } catch (RuntimeException error) {
            localFonts = false;
            android.util.Log.w("WidgetEngine", "no fonts in this process (" + error.getClass().getSimpleName()
                    + "): widths come from the client");
            return null;
        }
    }

    void deleteFont(int id) {
        deviceFonts.remove(id);
        fontKeys.remove(id);
        try {
            client.deleteFont(id);
        } catch (RemoteException ignored) {
        }
    }

    float textWidth(int id, byte[] value) {
        Paint paint = deviceFonts.get(id);
        if (paint != null) return paint.measureText(WidgetSandboxBackend.text(value)) / density;
        if (value == null || value.length == 0) return 0;
        String text = WidgetSandboxBackend.text(value);
        String fontKey = fontKeys.get(id);
        String key = fontKey != null && text.length() <= MAX_CACHED_TEXT ? fontKey + text : null;
        if (key != null) {
            synchronized (WIDTHS) {
                Float known = WIDTHS.get(key);
                if (known != null) {
                    Stats.hits++;
                    return known;
                }
            }
        }
        float width = 0;
        if (value.length <= WidgetRemoteEngine.MAX_TEXT) {
            width = remoteWidth(id, value);
        } else {
            for (int start = 0; start < text.length(); ) {
                int end = Math.min(text.length(), start + TEXT_PIECE);
                if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
                width += remoteWidth(id, text.substring(start, end).getBytes(StandardCharsets.UTF_8));
                start = end;
            }
        }
        if (key != null) {
            synchronized (WIDTHS) {
                WIDTHS.put(key, width);
            }
        }
        return width;
    }

    private float remoteWidth(int id, byte[] value) {
        Stats.remote++;
        float[] width = new float[1];
        call(() -> width[0] = client.textWidth(id, value));
        return width[0];
    }

    float[] loadImage(int id, byte[] encodedUrl) throws Exception {
        Bitmap image = WidgetSandboxBackend.decodeImage(WidgetSandboxBackend.text(encodedUrl), imageBytes);
        if (image == null) return null;
        imageBytes += image.getAllocationByteCount();
        call(() -> client.defineImage(id, image));
        return new float[] {image.getWidth(), image.getHeight()};
    }

    byte[] formatIntl(byte[] request) {
        return WidgetSandboxIntl.format(WidgetSandboxBackend.text(request)).getBytes(StandardCharsets.UTF_8);
    }

    void signal(int kind, byte[] message) throws Exception {
        send(message, value -> client.signal(kind, value));
    }

    void selectableText(byte[] encodedRuns) throws Exception {
        send(encodedRuns, value -> client.selectableText(value));
    }

    void recordLayer(int index, int version, byte[] encodedList) throws Exception {
        send(encodedList, value -> client.recordLayer(index, version, value));
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
        send(encodedList, list -> client.record(list, encodedComposition, nativeClock, width, height, density, layoutMs,
                passes, builds));
    }

    void canvasRun(int id, int width, int height, int[] ops) throws Exception {
        call(() -> client.canvasRun(id, width, height, ops));
    }

    byte[] canvasRead(int id, int x, int y, int width, int height) throws Exception {
        byte[][] out = new byte[1][];
        call(() -> out[0] = client.canvasRead(id, x, y, width, height));
        return out[0];
    }

    void canvasRelease(int id) {
        try {
            client.canvasRelease(id);
        } catch (RemoteException ignored) {
        }
    }

    private interface Send {
        void run(byte[] value) throws RemoteException;
    }

    private void send(byte[] value, Send send) {
        if (value == null || value.length <= PART) {
            call(() -> send.run(value));
            return;
        }
        synchronized (largeLock) {
            for (int offset = 0; offset < value.length; offset += PART) {
                byte[] part = new byte[Math.min(PART, value.length - offset)];
                System.arraycopy(value, offset, part, 0, part.length);
                call(() -> client.part(part));
            }
            call(() -> send.run(null));
        }
    }

    private interface Call {
        void run() throws RemoteException;
    }

    private static void call(Call call) {
        try {
            call.run();
        } catch (RemoteException error) {
            throw new IllegalStateException("Widget client: " + error);
        }
    }

    static final class Stats {
        static volatile long fonts, hits, remote;
        private static long lastFonts, lastHits, lastRemote;

        static void start() {
            if (!org.telegram.messenger.BuildConfig.DEBUG_PRIVATE_VERSION) return;
            Thread thread = new Thread(() -> {
                while (true) {
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        return;
                    }
                    long f = fonts, h = hits, r = remote;
                    if (f == lastFonts && h == lastHits && r == lastRemote) continue;
                    int cached;
                    synchronized (WIDTHS) {
                        cached = WIDTHS.size();
                    }
                    android.util.Log.d("WidgetEngine", "widths 5 s: remote " + (r - lastRemote) + ", cache hits "
                            + (h - lastHits) + ", fonts " + (f - lastFonts) + " (cached " + cached + ", local fonts "
                            + localFonts + ")");
                    lastFonts = f;
                    lastHits = h;
                    lastRemote = r;
                }
            }, "widget-engine-stats");
            thread.setDaemon(true);
            thread.start();
        }
    }
}
