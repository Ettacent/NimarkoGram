package dev.nimarko.blurregression;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.*;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.util.Log;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.utils.RenderNodeEffects;
import org.telegram.ui.Components.blur3.DownscaleScrollableNoiseSuppressor;
import java.io.*;
import java.lang.reflect.Field;
import java.util.*;


public final class CheckActivity extends Activity {
    private static final int WIDTH = 1088;
    private static final int LIMIT = 3;
    private File output;
    private PrintWriter report;
    private int failures, comparisons, wraps, negativeMaximum;
    private long renderNanos, frames;

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        final String token = getIntent().getStringExtra("run");
        new Thread(() -> {
            try {
                if (token == null || !token.matches("[a-zA-Z0-9_-]+")) {
                    throw new IllegalArgumentException("safe run token required");
                }
                output = new File(getFilesDir(), token);
                if (!output.mkdirs()) throw new IOException("output already exists: " + output);
                report = new PrintWriter(new File(output, "report.txt"), "UTF-8");
                runTests();
            } catch (Throwable error) {
                if (report != null) {
                    error.printStackTrace(report);
                    report.println("RESULT FAIL exception=" + error);
                }
                Log.e("FrostedBlurRegression", "FAILED", error);
            } finally {
                if (report != null) report.close();
                runOnUiThread(this::finish);
            }
        }, "frosted-hwui-regression").start();
    }

    private void runTests() throws Exception {
        report.println("HWUI sdk=" + android.os.Build.VERSION.SDK_INT
                + " model=" + android.os.Build.MODEL + " fingerprint=" + android.os.Build.FINGERPRINT);

        runCase(3f, 704, 0, true, "positive", true);
        if (negativeMaximum <= LIMIT) {
            failures++;
            report.println("CONTROL FAIL: single-pass did not exceed " + LIMIT);
        } else report.println("CONTROL PASS: single-pass max=" + negativeMaximum);
        report.flush();

        for (float density : new float[] {2.625f, 3f, 4f}) {
            for (int height : new int[] {704, 624}) {
                for (int scene = 0; scene < 3; scene++) {
                    for (String sequence : new String[] {"positive", "negative", "fraction-reverse"}) {
                        runCase(density, height, scene, true, sequence, false);
                    }
                }
            }
        }

        for (int height : new int[] {704, 624}) {
            runCase(3f, height, 1, false, "fraction-reverse", false);
        }
        report.println("SUMMARY comparisons=" + comparisons + " wraps=" + wraps
                + " failures=" + failures + " negativeMax=" + negativeMaximum
                + " frames=" + frames + " renderAndReadbackMeanMs="
                + renderNanos / 1_000_000.0 / frames);
        report.println("RESULT " + (failures == 0 ? "PASS" : "FAIL"));
        report.flush();
    }

    private static float delta(String sequence, int frame) {
        if (sequence.equals("positive")) return 1f;
        if (sequence.equals("negative")) return -1f;


        if (frame <= 32) return .5f;
        if (frame <= 64) return -.75f;
        return .5f;
    }

    private void runCase(float density, int height, int scene, boolean simple,
                         String sequence, boolean negative) throws Exception {
        AndroidUtilities.density = density;
        String id = (negative ? "single" : "production") + "-d" + density + "-h" + height
                + "-s" + scene + "-simple" + simple + "-" + sequence;
        DownscaleScrollableNoiseSuppressor suppressor = new DownscaleScrollableNoiseSuppressor(simple, false);
        suppressor.setupRenderNodes(Collections.singletonList(new RectF(0, 0, WIDTH, height)), 1);
        if (negative) forceSinglePass(suppressor, density);



        int guard = Math.max(224, (int) Math.ceil(3 * (.57735f * 40 * density + .5f) + 16));
        if (height <= guard * 2) throw new AssertionError("empty ROI: " + id);
        report.println("CASE " + id + " roi=" + guard + "," + guard + ","
                + (WIDTH - guard) + "," + (height - guard));
        report.flush();

        ImageReader reader = ImageReader.newInstance(WIDTH, height, PixelFormat.RGBA_8888, 3,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE | HardwareBuffer.USAGE_GPU_COLOR_OUTPUT);
        HardwareRenderer renderer = new HardwareRenderer();
        RenderNode root = new RenderNode("frosted-regression-output");
        root.setPosition(0, 0, WIDTH, height);
        renderer.setSurface(reader.getSurface());
        renderer.setOpaque(false);
        renderer.setContentRoot(root);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        int[] previous = null;
        float scroll = 0, phase = 0;
        int caseMaximum = 0, caseFailures = 0;
        double peakMean = 0;
        int count = sequence.equals("fraction-reverse") ? 96 : 40;
        try {
            for (int frame = 0; frame <= count; frame++) {
                float movement = frame == 0 ? 0 : delta(sequence, frame);
                float before = phase;
                if (frame != 0) {
                    scroll += movement;
                    phase = (phase + movement) % 8;
                    suppressor.onScrolled(0, movement);
                }
                boolean wrap = frame != 0 && Math.abs(phase - before - movement) > 4;
                final float position = scroll;
                suppressor.invalidateResultRenderNodes((canvas, rect) -> {
                    if (scene == 2) canvas.drawColor(0xffaaaaaa);
                    for (int y = -512; y < height + 512; y += 137) {
                        paint.setColor((y / 137 & 1) == 0 ? 0xffd3deff : 0xff603840);
                        canvas.drawRoundRect(64, y - position, WIDTH - 48,
                                y + 65 - position, 18, 18, paint);
                        if (scene == 1) {
                            paint.setColor(Color.WHITE);
                            canvas.drawRect(32, y + 68 - position, WIDTH - 16,
                                    y + 69 - position, paint);
                        }
                    }
                }, WIDTH, height);
                Canvas canvas = root.beginRecording(WIDTH, height);
                canvas.drawColor(0xff181b25);
                suppressor.draw(canvas, DownscaleScrollableNoiseSuppressor.DRAW_FROSTED_GLASS);
                root.endRecording();
                long start = System.nanoTime();
                Bitmap bitmap = readback(renderer, reader);
                renderNanos += System.nanoTime() - start;
                frames++;
                int[] pixels = new int[WIDTH * height];
                bitmap.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, height);
                int maximum = 0;
                double sum = 0;
                if (previous != null) {
                    for (int y = guard; y < height - guard; y++) {
                        for (int x = guard; x < WIDTH - guard; x++) {
                            int index = y * WIDTH + x;
                            for (int shift = 0; shift < 24; shift += 8) {
                                int difference = Math.abs(((pixels[index] >>> shift) & 255)
                                        - ((previous[index] >>> shift) & 255));
                                sum += difference;
                                maximum = Math.max(maximum, difference);
                            }
                        }
                    }
                    double mean = sum / ((long) (height - 2 * guard) * (WIDTH - 2 * guard) * 3);
                    caseMaximum = Math.max(caseMaximum, maximum);
                    peakMean = Math.max(peakMean, mean);
                    if (negative) negativeMaximum = Math.max(negativeMaximum, maximum);
                    else {
                        comparisons++;
                        if (wrap) wraps++;
                        if (maximum > LIMIT) { failures++; caseFailures++; }
                    }
                    report.println("FRAME " + id + " f=" + frame + " scroll=" + scroll
                            + " delta=" + movement + " phase=" + phase + " wrap=" + wrap
                            + " max=" + maximum + " mean=" + mean);
                }
                boolean referenceImage = density == 3f && height == 704 && simple
                        && sequence.equals("positive") && frame >= 7 && frame <= 9;
                if (referenceImage || (!negative && maximum > LIMIT && caseFailures == 1)) {
                    try (FileOutputStream stream = new FileOutputStream(new File(output, id + "-f" + frame + ".png"))) {
                        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                            throw new IOException("PNG compression failed");
                        }
                    }
                }
                previous = pixels;
                bitmap.recycle();
            }
        } finally {
            renderer.destroy();
            reader.close();
        }
        report.println("CASE_END " + id + " max=" + caseMaximum + " peakMean=" + peakMean
                + " failures=" + caseFailures);
        report.flush();
    }

    private static Bitmap readback(HardwareRenderer renderer, ImageReader reader) throws Exception {
        int status = renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw();
        if (status != 0) throw new AssertionError("HardwareRenderer sync status=" + status);
        Image image = null;
        for (int attempt = 0; attempt < 100 && image == null; attempt++) {
            image = reader.acquireNextImage();
            if (image == null) Thread.sleep(2);
        }
        if (image == null) throw new AssertionError("HWUI readback timed out");
        try (Image acquired = image; HardwareBuffer buffer = acquired.getHardwareBuffer()) {
            Bitmap hardware = Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB));
            if (hardware == null) throw new AssertionError("wrapHardwareBuffer returned null");
            try {
                Bitmap result = hardware.copy(Bitmap.Config.ARGB_8888, false);
                if (result == null) throw new AssertionError("hardware copy returned null");
                return result;
            } finally { hardware.recycle(); }
        }
    }

    private static void forceSinglePass(DownscaleScrollableNoiseSuppressor suppressor,
                                        float density) throws Exception {

        Field partsField = DownscaleScrollableNoiseSuppressor.class.getDeclaredField("rectRenderNodes");
        partsField.setAccessible(true);
        for (Object part : (List<?>) partsField.get(suppressor)) {
            Field blurField = part.getClass().getDeclaredField("renderNodesForBlur");
            blurField.setAccessible(true);
            DownscaleScrollableNoiseSuppressor.DownscaledRenderNode node =
                    (DownscaleScrollableNoiseSuppressor.DownscaledRenderNode) blurField.get(part);
            float radius = DownscaleScrollableNoiseSuppressor.downscaleRadius(40 * density, 8);
            node.setPrimaryEffect(RenderEffect.createBlurEffect(radius, radius,
                    RenderNodeEffects.getSaturationX3RenderEffect(), Shader.TileMode.CLAMP));
        }
    }
}
