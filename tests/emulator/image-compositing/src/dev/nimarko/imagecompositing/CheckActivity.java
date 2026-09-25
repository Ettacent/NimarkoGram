package dev.nimarko.imagecompositing;

import android.app.Activity;
import android.os.Bundle;
import android.graphics.*;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;
import android.util.Log;
import java.io.*;
import java.util.Locale;


public final class CheckActivity extends Activity {
    private static final int W = 96, H = 80, LIMIT = 3;
    private static final int ADD = 0, SRC_OVER = 1, UNDERLAY = 2, UNISOLATED = 3,
            DROP_PREVIEW = 4, OLD = 5, NEW = 6, DIRECT_ADD = 7;
    private File output;
    private PrintWriter report;
    private int failures, comparisons, frameCount;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        new Thread(() -> {
            try {
                String token = getIntent().getStringExtra("run");
                if (token == null || !token.matches("[a-zA-Z0-9_-]+")) throw new IOException("bad token");
                output = new File(getFilesDir(), token);
                if (!output.mkdirs()) throw new IOException("output exists");
                report = new PrintWriter(new File(output, "report.txt"), "UTF-8");
                report.println("ENV sdk=" + android.os.Build.VERSION.SDK_INT + " fingerprint=" + android.os.Build.FINGERPRINT);
                report.println("ORACLE premultiplied RGBA lerp then parent alpha then SRC_OVER background; tolerance=" + LIMIT + "/255");
                for (boolean hardware : new boolean[] {false, true}) {
                    try (Raster raster = new Raster(hardware)) { runBackend(raster); }
                }
                report.println("SUMMARY comparisons=" + comparisons + " frames=" + frameCount + " failures=" + failures);
                report.println("RESULT " + (failures == 0 ? "PASS" : "FAIL"));
            } catch (Throwable e) {
                if (report != null) { e.printStackTrace(report); report.println("RESULT FAIL exception=" + e); }
                Log.e("ImageCompositing", "reference failed", e);
            } finally {
                if (report != null) report.close();
                runOnUiThread(this::finish);
            }
        }, "image-compositing-reference").start();
    }

    private void runBackend(Raster raster) throws Exception {
        int before = failures;
        double max = 0, edgeMax = 0, alphaMax = 0;
        int edgePixels = 0, transparentPixels = 0, directFailures = 0;
        double directMax = 0;
        report.println("BACKEND " + raster.name + " hardwareCanvasRequired=" + raster.hardware);
        report.flush();
        int[] steps = {0, 1, 16, 64, 127, 128, 192, 254, 255};
        int[] backgrounds = {0, 0xff000000, 0xffffffff, 0xff204060};
        for (int scene = 0; scene < 4; scene++) {
            Bitmap a = fixture(scene, false), b = scene == 0 ? a : fixture(scene, true);
            save(a, raster.name + "-scene" + scene + "-old.png");
            save(b, raster.name + "-scene" + scene + "-new.png");
            for (int geometry = 0; geometry < 2; geometry++) {
                Bitmap old = raster.render(a, b, OLD, 0, 255, 0, geometry);
                Bitmap next = raster.render(a, b, NEW, 255, 255, 0, geometry);
                double[][] op = pixels(old), np = pixels(next);
                for (int i = 0; i < op.length; i++) {
                    if ((op[i][3] > 0 && op[i][3] < 255) || (np[i][3] > 0 && np[i][3] < 255)) edgePixels++;
                    if (op[i][3] == 0 && np[i][3] == 0) transparentPixels++;
                }
                old.recycle(); next.recycle();
                double[] previousCenter = null;
                for (int bg : backgrounds) for (int parent : new int[] {64, 153, 255}) for (int t : steps) {
                    if (t == 0) previousCenter = null;
                    Bitmap actual = raster.render(a, b, ADD, t, parent, bg, geometry);
                    String id = raster.name + "-s" + scene + "-g" + geometry + "-bg" + Integer.toHexString(bg) + "-p" + parent + "-t" + t;
                    double[] error = compare(actual, op, np, t, parent, bg);
                    comparisons++;
                    max = Math.max(max, error[0]); alphaMax = Math.max(alphaMax, error[1]);
                    edgeMax = Math.max(edgeMax, error[2]);
                    if (error[0] > LIMIT) {
                        failures++;
                        report.println("FAIL " + id + " max=" + error[0] + " alpha=" + error[1] + " edge=" + error[2]);
                        if (failures < 12) save(actual, id + "-FAIL.png");
                    }
                    if (scene == 0 || scene == 3) {
                        double[] center = pixels(actual)[(H / 2) * W + W / 2];


                        if (previousCenter != null) for (int ch = 0; ch < 4; ch++) {
                            if (center[ch] < previousCenter[ch] - 2) {
                                failures++;
                                report.println("FAIL temporal-dip " + id + " channel=" + ch);
                            }
                        }
                        previousCenter = center;
                    }
                    if (geometry == 1 && parent == 153 && (t == 0 || t == 128 || t == 255)) save(actual, id + ".png");
                    actual.recycle();
                    Bitmap direct = raster.render(a, b, DIRECT_ADD, t, parent, bg, geometry);
                    double directError = compare(direct, op, np, t, parent, bg)[0];
                    directMax = Math.max(directMax, directError);
                    if (directError > LIMIT) directFailures++;
                    if (geometry == 1 && parent == 255 && bg == 0 && t == 255) {
                        save(direct, id + "-direct-add-endpoint.png");
                    }
                    direct.recycle();
                }

                if (geometry == 1) for (int mode : new int[] {SRC_OVER, UNDERLAY, UNISOLATED, DROP_PREVIEW}) {
                    int bg = mode == UNISOLATED ? 0xff808080 : 0;
                    Bitmap wrong = raster.render(a, b, mode, 128, 255, bg, geometry);
                    double error = compare(wrong, op, np, 128, 255, bg)[0];
                    boolean detected = error > 8;
                    if (!detected) failures++;
                    report.println("CONTROL " + raster.name + " scene=" + scene + " mode=" + mode + " detected=" + detected + " max=" + error);
                    save(wrong, raster.name + "-s" + scene + "-negative" + mode + ".png");
                    wrong.recycle();
                }
                report.flush();
            }
            if (a != b) b.recycle();
            a.recycle();
        }
        if (edgePixels == 0 || transparentPixels == 0) throw new AssertionError("missing coverage");
        report.printf(Locale.US, "BACKEND_END %s max=%.4f alphaMax=%.4f edgeMax=%.4f edgePixels=%d transparentPixels=%d failures=%d%n",
                raster.name, max, alphaMax, edgeMax, edgePixels, transparentPixels, failures - before);
        report.println("DIRECT_ADD_CHARACTERIZATION " + raster.name + " max=" + directMax + " comparisonsOverTolerance=" + directFailures
                + " (not the layer-restore ADD reference; failures are retained, not waived for reference)");
        report.flush();
    }

    private static Bitmap fixture(int scene, boolean next) {
        Bitmap bitmap = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bitmap);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        if (scene < 2 || scene == 3) {
            p.setColor(scene == 3 ? (next ? Color.WHITE : 0x66ffffff)
                    : scene == 0 ? Color.WHITE : next ? 0xff408ce0 : 0xffdc7438);
            c.drawRect(0, 0, W, H, p);
            if (scene == 1) {
                p.setColor(next ? 0xfff0b040 : 0xff4060d0);
                c.drawCircle(next ? 57.25f : 36.75f, 38.5f, 24.25f, p);
            }
        } else {
            p.setColor(next ? 0xb038c8ff : 0x90ff6838);
            c.drawCircle(next ? 57.25f : 34.75f, 35.25f, 22.5f, p);
            p.setColor(next ? 0x4038ff80 : 0xe0e040e0);
            c.drawRoundRect(next ? 18.5f : 54.25f, 18.5f, next ? 38.75f : 74.5f, 60.25f, 7.5f, 7.5f, p);

            for (int x = 16; x < 80; x++) {
                p.setColor(Color.argb((x - 16) * 4, next ? 30 : 240, 180, next ? 250 : 40));
                c.drawRect(x, 63, x + 1, 69, p);
            }
        }
        return bitmap;
    }

    private static void source(Canvas c, Bitmap bitmap, int weight, boolean add, int geometry) {
        int save = c.save();
        if (geometry == 1) { c.translate(.375f, -.25f); c.scale(.9375f, .9375f, W / 2f, H / 2f); }
        Path clip = new Path();
        clip.addRoundRect(new RectF(8.25f, 6.75f, W - 8.5f, H - 6.25f), 13.5f, 13.5f, Path.Direction.CW);
        c.clipPath(clip);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        paint.setAlpha(weight);
        if (add) paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        c.drawBitmap(bitmap, 0, 0, paint);
        c.restoreToCount(save);
    }

    private static void draw(Canvas c, Bitmap old, Bitmap next, int mode, int t, int parent, int bg, int geometry) {
        c.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        c.drawColor(bg);
        if (mode == OLD || mode == NEW) {
            source(c, mode == OLD ? old : next, 255, false, geometry);
            return;
        }
        if (mode == UNISOLATED) {
            source(c, old, 255 - t, false, geometry);
            source(c, next, t, true, geometry);
            return;
        }

        int parentSave = c.saveLayerAlpha(0, 0, W, H, parent);
        int blendSave = c.saveLayer(0, 0, W, H, null);
        if (mode != DROP_PREVIEW) source(c, old, mode == UNDERLAY ? 255 : 255 - t, false, geometry);
        if (mode == ADD) {


            Paint add = new Paint();
            add.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
            int incoming = c.saveLayer(0, 0, W, H, add);
            source(c, next, t, false, geometry);
            c.restoreToCount(incoming);
        } else {
            source(c, next, t, mode == DIRECT_ADD, geometry);
        }
        c.restoreToCount(blendSave);
        c.restoreToCount(parentSave);
    }

    private static double[][] pixels(Bitmap b) {
        int[] raw = new int[W * H];
        b.getPixels(raw, 0, W, 0, 0, W, H);
        double[][] values = new double[raw.length][4];
        for (int i = 0; i < raw.length; i++) {
            int v = raw[i], a = Color.alpha(v);
            values[i][0] = Color.red(v) * a / 255.0;
            values[i][1] = Color.green(v) * a / 255.0;
            values[i][2] = Color.blue(v) * a / 255.0;
            values[i][3] = a;
        }
        return values;
    }

    private static double[] compare(Bitmap actual, double[][] old, double[][] next, int t, int parent, int bg) {
        double[][] got = pixels(actual);
        double[] background = {Color.red(bg) * Color.alpha(bg) / 255.0,
                Color.green(bg) * Color.alpha(bg) / 255.0, Color.blue(bg) * Color.alpha(bg) / 255.0, Color.alpha(bg)};
        double max = 0, alphaMax = 0, edgeMax = 0;
        for (int i = 0; i < got.length; i++) {
            double alpha = ((255 - t) * old[i][3] + t * next[i][3]) / 255.0 * parent / 255.0;
            boolean edge = (old[i][3] > 0 && old[i][3] < 255) || (next[i][3] > 0 && next[i][3] < 255);
            for (int ch = 0; ch < 4; ch++) {
                double mix = ((255 - t) * old[i][ch] + t * next[i][ch]) / 255.0 * parent / 255.0;
                double expected = mix + background[ch] * (1 - alpha / 255.0);
                double error = Math.abs(expected - got[i][ch]);
                max = Math.max(max, error);
                if (ch == 3) alphaMax = Math.max(alphaMax, error);
                if (edge) edgeMax = Math.max(edgeMax, error);
            }
        }
        return new double[] {max, alphaMax, edgeMax};
    }

    private void save(Bitmap b, String name) throws IOException {
        try (FileOutputStream f = new FileOutputStream(new File(output, name))) {
            if (!b.compress(Bitmap.CompressFormat.PNG, 100, f)) throw new IOException("PNG failed");
        }
    }

    private final class Raster implements AutoCloseable {
        final boolean hardware;
        final String name;
        ImageReader reader;
        HardwareRenderer renderer;
        RenderNode root;
        Raster(boolean hardware) {
            this.hardware = hardware;
            name = hardware ? "hwui" : "software";
            if (hardware) {
                reader = ImageReader.newInstance(W, H, PixelFormat.RGBA_8888, 3,
                        HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE | HardwareBuffer.USAGE_GPU_COLOR_OUTPUT);
                renderer = new HardwareRenderer();
                root = new RenderNode("image-compositing-reference");
                root.setPosition(0, 0, W, H);
                renderer.setSurface(reader.getSurface());
                renderer.setOpaque(false);
                renderer.setContentRoot(root);
            }
        }
        Bitmap render(Bitmap a, Bitmap b, int mode, int t, int parent, int bg, int geometry) throws Exception {
            frameCount++;
            if (!hardware) {
                Bitmap result = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(result);
                if (canvas.isHardwareAccelerated()) throw new AssertionError("software backend is HW");
                draw(canvas, a, b, mode, t, parent, bg, geometry);
                return result;
            }
            Canvas canvas = root.beginRecording(W, H);
            if (!canvas.isHardwareAccelerated()) throw new AssertionError("HW backend is software");
            draw(canvas, a, b, mode, t, parent, bg, geometry);
            root.endRecording();
            int status = renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw();


            if (status != 0 && status != HardwareRenderer.SYNC_FRAME_DROPPED) {
                throw new AssertionError("sync status=" + status);
            }
            Image image = null;
            for (int attempt = 0; attempt < 250 && image == null; attempt++) {
                image = reader.acquireNextImage();
                if (image == null) Thread.sleep(2);
            }
            if (image == null) throw new AssertionError("HW readback timeout");
            try (Image acquired = image; HardwareBuffer buffer = acquired.getHardwareBuffer()) {
                Bitmap hw = Bitmap.wrapHardwareBuffer(buffer, ColorSpace.get(ColorSpace.Named.SRGB));
                if (hw == null) throw new AssertionError("wrap failed");
                try {
                    Bitmap result = hw.copy(Bitmap.Config.ARGB_8888, false);
                    if (result == null || !result.hasAlpha()) throw new AssertionError("alpha readback unavailable");
                    return result;
                } finally { hw.recycle(); }
            }
        }
        @Override public void close() {
            if (renderer != null) renderer.destroy();
            if (reader != null) reader.close();
            if (root != null) root.discardDisplayList();
        }
    }
}
