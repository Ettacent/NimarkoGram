"""Host tests of production sampling/transform logic, not Android GPU validation.

Only the small extracted Java harness is compiled; no app build is required.
"""
import pathlib
import subprocess
import tempfile
import unittest


BLUR = (pathlib.Path(__file__).resolve().parents[2]
        / "main/java/org/telegram/ui/Components/blur3")


def block(source, signature):
    start = source.index(signature)
    opening = source.index("{", start)
    depth, end = 1, opening + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]


class FrostedScrollSamplingTests(unittest.TestCase):
    def test_production_sampling_and_material_modes(self):
        source = (BLUR / "DownscaleScrollableNoiseSuppressor.java").read_text()
        self.run_sampling_harness(source)

    def run_sampling_harness(self, source, expected_failure=None):
        # Take constructor fields too, so a newly introduced gate cannot be
        # silently replaced with a test-only value.
        constructors = source[source.index("    public boolean isLiquidGlassEnabled;"):
                              source.index("    public static final int DRAW_GLASS")]
        production = "\n".join(block(source, signature) for signature in (
            "public class DownscaledRenderNode", "private class SourcePart",
            "private void updateGlassMode()",
            "private static int roundDown(", "public static int roundUp(",
            "public static float convertRadiusToSigma(",
            "public static float convertSigmaToRadius(",
            "public static float downscaleRadius("))
        production += block(source[source.index("public static final float MAX_RADIUS_FOR_FAST_BLUR"):],
                            "public void onScrolled(")
        harness = r"""
import java.util.*;
public class DownscaleScrollableNoiseSuppressor {
  @interface Nullable {}
  static class LiteMode {
    static final int FLAG_LIQUID_GLASS = 1;
    static boolean glass;
    static boolean isEnabled(int flag) { return glass; }
  }
  static class BlurredBackgroundDrawableViewFactory {
    static boolean isLiquidGlassEnabled() { return LiteMode.glass; }
  }
  static class MediaDataController {
    static long calcHash(long h, long v) { return h * 31 + v; }
  }
  static float density = 1;
  static float dpf2(float value) { return value * density; }
  static class Shader { enum TileMode { CLAMP } }
  static class RenderEffect {
    float radius;
    float variance, maxSigma;
    int passes;
    boolean saturated;
    static RenderEffect createBlurEffect(float x, float y, Shader.TileMode mode) {
      RenderEffect e = new RenderEffect(); e.radius = x;
      e.maxSigma = convertRadiusToSigma(x); e.variance = e.maxSigma * e.maxSigma;
      e.passes = 1; return e;
    }
    static RenderEffect createBlurEffect(float x, float y, RenderEffect input, Shader.TileMode mode) {
      RenderEffect e = createBlurEffect(x, y, mode);
      e.variance += input.variance; e.maxSigma = Math.max(e.maxSigma, input.maxSigma);
      e.passes += input.passes; e.saturated = input.saturated; return e;
    }
    static RenderEffect createChainEffect(RenderEffect blur, RenderEffect color) {
      blur.saturated = color.saturated; return blur;
    }
  }
  static class RenderNodeEffects {
    static RenderEffect getSaturationX3RenderEffect() {
      RenderEffect e = new RenderEffect(); e.saturated = true; return e;
    }
  }
  static class Canvas {
    void drawRenderNode(RenderNode node) {}
    void scale(float x, float y) {}
  }
  static class RenderNode {
    static long ids;
    final long id = ++ids;
    int width, height, recordings;
    float tx, ty, sx = 1, sy = 1;
    RenderEffect effect;
    RenderNode(String name) {}
    long getUniqueId() { return id; }
    int getWidth() { return width; }
    int getHeight() { return height; }
    boolean hasDisplayList() { return recordings > 0; }
    void setPosition(int l, int t, int r, int b) { width = r-l; height = b-t; }
    Canvas beginRecording(int w, int h) { recordings++; return new Canvas(); }
    void endRecording() {}
    void discardDisplayList() { recordings = 0; }
    void setRenderEffect(RenderEffect e) { effect = e; }
    void setScaleX(float v) { sx = v; }
    void setScaleY(float v) { sy = v; }
    void setPivotX(float v) {}
    void setPivotY(float v) {}
    void setTranslationX(float v) { tx = v; }
    void setTranslationY(float v) { ty = v; }
  }
  static class Rect {
    int left, top, right, bottom;
    void set(int l, int t, int r, int b) { left=l; top=t; right=r; bottom=b; }
  }
  static class RectF { float left, top, right, bottom; }
  private final RenderNode[] resultRenderNodes;
  private final ArrayList<SourcePart> rectRenderNodes = new ArrayList<>();
  private int rectRenderNodesCount;
  private Rect recordingPos;
  private long capturePositionsGeneration;
  public void invalidateCapturePositions() { capturePositionsGeneration++; }
  private int compositions;
  private boolean invalidateResultRenderNodes(int w, int h) {
    compositions++;
    for (RenderNode node : resultRenderNodes) { node.setPosition(0,0,w,h); node.beginRecording(w,h); }
    return true;
  }
  public static final float BLUR_SIGMA_SCALE = 0.57735f;
""" + constructors + production + r"""
  static void check(boolean ok, String label) {
    if (!ok) throw new AssertionError(label);
  }
  static void near(float actual, float expected, String label) {
    check(Math.abs(actual - expected) < .0001f, label + ": " + actual + " != " + expected);
  }
  static float fraction(float v) { return v - (float)Math.floor(v); }
  // One-dimensional blue bubbles separated by a 1px dark gap. This models the
  // sample/restore transforms with a Gaussian kernel, not Android GPU output.
  static double blueSample(double contentY) {
    double period = contentY - Math.floor(contentY / 16) * 16;
    return period >= 7 && period < 8 ? 0 : 1;
  }
  static double filteredSample(int index, float scale, float phase, float scroll, double sigma) {
    int radius = (int)Math.ceil(sigma * 3);
    double sum=0, weights=0;
    for (int i=-radius; i<=radius; i++) {
      double weight = Math.exp(-.5 * i * i / (sigma * sigma));
      sum += weight * blueSample((index + i + .5) * scale - phase + scroll);
      weights += weight;
    }
    return sum / weights;
  }
  static double renderedBlue(DownscaledRenderNode n, float scroll, boolean locked) {
    float phase = locked ? n.renderNodeOriginalWithOffset.ty : 0;
    double pos=(128 + phase) / n.scaleY - .5;
    int index=(int)Math.floor(pos);
    double t=pos-index, sigma=Math.sqrt(n.renderNodeDownsampled[0].effect.variance);
    return filteredSample(index,n.scaleY,phase,scroll,sigma)*(1-t)
        + filteredSample(index+1,n.scaleY,phase,scroll,sigma)*t;
  }
  static void checkThinGap(DownscaledRenderNode n) {
    double prevLocked=0, prevUnlocked=0, jumpLocked=0, jumpUnlocked=0;
    for (int frame=0; frame<=512; frame++) {
      float scroll=frame/16f;
      n.setScrollPhase(0,scroll);
      double locked=renderedBlue(n,scroll,true), unlocked=renderedBlue(n,scroll,false);
      if (frame>0) {
        jumpLocked=Math.max(jumpLocked,Math.abs(locked-prevLocked));
        jumpUnlocked=Math.max(jumpUnlocked,Math.abs(unlocked-prevUnlocked));
      }
      prevLocked=locked; prevUnlocked=unlocked;
    }
    check(jumpUnlocked>.02, "thin gap exposes unstable sampling control");
    check(jumpLocked<.003, "thin gap colour continuity: " + jumpLocked);
  }
  static void checkScroll(DownscaledRenderNode node) {
    float x = 0, y = 0;
    // Both directions, subpixel drag, modulus wraps, and a direction reversal.
    for (float delta : new float[] {.25f, 1, 6.75f, 8, 19.5f, -.25f, -3, -32, -.5f, 64}) {
      x += delta; y -= delta;
      node.setScrollPhase(x, y);
        // Java's signed remainder can choose either adjacent grid origin
        // after reversal. Compare phase, not the sign of that origin.
        near(fraction(node.renderNodeOriginalWithOffset.tx / node.scaleX),
             fraction(x / node.scaleX), "source x phase");
        near(fraction(node.renderNodeOriginalWithOffset.ty / node.scaleY),
             fraction(y / node.scaleY), "source y phase");
        // A moving edge retains its subpixel position on the sample grid.
        near(fraction((2 - y + node.renderNodeOriginalWithOffset.ty) / node.scaleY),
             fraction(2f / node.scaleY), "content-locked sampling");
      for (RenderNode restored : node.renderNodeRestored) {
        near(restored.tx + node.renderNodeOriginalWithOffset.tx, 0, "x geometry restored");
        near(restored.ty + node.renderNodeOriginalWithOffset.ty, 0, "y geometry restored");
      }
    }
  }
  public static void main(String[] args) {
    for (boolean glass : new boolean[] {false, true}) {
      LiteMode.glass = glass;
      DownscaleScrollableNoiseSuppressor defaults = new DownscaleScrollableNoiseSuppressor();
      check(defaults.simpleMode && !defaults.allowNoiseSuppress, "default quality choice");
      for (boolean simple : new boolean[] {false, true}) {
        for (boolean explicit : new boolean[] {false, true}) {
          DownscaleScrollableNoiseSuppressor h = new DownscaleScrollableNoiseSuppressor(simple, explicit);
          check(h.k == 1, "capture must precede phase/downsampling");
          SourcePart part = h.new SourcePart();
          int scale = !glass && simple && explicit ? 16 : 8;
          check(part.renderNodesForBlur.scaleX == scale, "existing blur scale");
          check(part.renderNodesForBlur.scaleY == scale, "existing blur scale y");
          near((float)Math.sqrt(part.renderNodesForBlur.renderNodeDownsampled[0].effect.variance),
               convertRadiusToSigma(downscaleRadius(glass ? 40 - 1.66f : 40, scale)),
               "same Gaussian blur strength");
          check(part.renderNodesForBlur.renderNodeDownsampled[0].effect.saturated == (!glass && simple),
                "existing saturation");
          check((part.renderNodesForGlass != null) == glass, "no fallback material switch");
          if (!glass && !simple) {
            check(part.renderNodesForBlur.renderNodeDownsampled[1].effect.saturated,
                  "secondary saturation preserved");
          }
          part.renderNode.setPosition(0, 0, 256, 128);
          part.invalidate();
          check(part.renderNodesForBlur.renderNodeDownsampled[0].width == 256 / scale,
                "existing downsample resolution");
          int recordings = part.renderNodesForBlur.renderNodeDownsampled[0].recordings;
          checkScroll(part.renderNodesForBlur);
          checkThinGap(part.renderNodesForBlur);
          part.invalidate();
          check(part.renderNodesForBlur.renderNodeDownsampled[0].recordings == recordings,
                "scroll phase must not force wrapper re-recording");
          if (glass) {
            check(part.renderNodesForGlass.scaleX == 2, "approved liquid resolution");
            near((float)Math.sqrt(part.renderNodesForGlass.renderNodeDownsampled[0].effect.variance),
                 convertRadiusToSigma(downscaleRadius(12, 2)), "approved liquid blur");
            check(part.renderNodesForGlass.renderNodeDownsampled[0].effect.saturated,
                  "approved liquid saturation");
            checkScroll(part.renderNodesForGlass);
            checkThinGap(part.renderNodesForGlass);
          }
        }
      }
    }
    // Device densities are critical: at density=3 the old 8x blur had sigma
    // 8.72 and Skia silently added a second, moving resampling grid.
    for (boolean glass : new boolean[] {false, true}) {
    LiteMode.glass = glass;
    for (float d : new float[] {1, 1.5f, 2, 2.625f, 3, 3.5f, 4, 5, 6}) {
      density = d;
      for (boolean simple : new boolean[] {false, true}) {
        for (boolean explicit : new boolean[] {false, true}) {
          DownscaleScrollableNoiseSuppressor h = new DownscaleScrollableNoiseSuppressor(simple, explicit);
          SourcePart p = h.new SourcePart();
          RenderEffect e = p.renderNodesForBlur.renderNodeDownsampled[0].effect;
          float sigma = convertRadiusToSigma(downscaleRadius(dpf2(glass ? 40 - 1.66f : 40),
              !glass && simple && explicit ? 16 : 8));
          near((float)Math.sqrt(e.variance), sigma, "density-specific variance retained");
          check(e.maxSigma <= 4.00001f, "no implicit Skia resampling at density " + d);
          check(e.passes == Math.max(1, (int)Math.ceil(sigma * sigma / 16)), "minimal pass count");
          check(e.saturated == (!glass && simple), "one saturation stage, unchanged material");
          if (glass) {
            e = p.renderNodesForGlass.renderNodeDownsampled[0].effect;
            sigma = convertRadiusToSigma(downscaleRadius(dpf2(12), 2));
            near((float)Math.sqrt(e.variance), sigma, "liquid variance retained");
            check(e.maxSigma <= 4.00001f, "no implicit liquid resampling at density " + d);
            check(e.passes == Math.max(1, (int)Math.ceil(sigma * sigma / 16)), "minimal liquid pass count");
            check(e.saturated, "liquid saturation retained");
          }
        }
      }
    }
    }
    density = 1;
    // Rebuild only effects, from retained captures, without a new scroll/capture.
    LiteMode.glass = false;
    DownscaleScrollableNoiseSuppressor live = new DownscaleScrollableNoiseSuppressor();
    SourcePart part = live.new SourcePart();
    live.rectRenderNodes.add(part);
    part.lastHash = 123;
    part.renderNode.setPosition(0,0,256,128);
    part.renderNode.beginRecording(256,128);
    part.invalidate();
    live.invalidateResultRenderNodes(256,128);
    long captureId = part.renderNode.getUniqueId();
    for (boolean enabled : new boolean[] {true, false, true, false}) {
      DownscaledRenderNode oldBlur = part.renderNodesForBlur;
      LiteMode.glass = enabled;
      int compositions = live.compositions;
      live.updateGlassMode();
      check(live.isLiquidGlassEnabled == enabled, "live mode");
      check((part.renderNodesForGlass != null) == enabled, "live effect graph");
      check(part.renderNodesForBlur != oldBlur, "old effect graph replaced");
      check(part.renderNode.getUniqueId() == captureId && part.renderNode.recordings == 1,
            "capture retained");
      check(part.lastHash == 123, "capture hash retained");
      check(live.compositions == compositions + 1, "composition rebuilt immediately");
      check(part.renderNodesForBlur.renderNodeDownsampled[0].hasDisplayList(), "ready without scroll");
      checkScroll(part.renderNodesForBlur);
      oldBlur = part.renderNodesForBlur;
      live.updateGlassMode();
      check(part.renderNodesForBlur == oldBlur && live.compositions == compositions + 1,
            "unchanged mode must not rebuild");
    }
    // Real outer scroll path: hidden/new islands, both directions, fractional
    // motion, mode toggles and reattachment all use the same content grid.
    for (boolean glass : new boolean[] {false, true}) {
      LiteMode.glass = glass;
      DownscaleScrollableNoiseSuppressor h = new DownscaleScrollableNoiseSuppressor();
      h.onScrolled(1.25f, 13.5f); // No panel yet: phase must still advance.
      SourcePart first = h.new SourcePart();
      h.rectRenderNodes.add(first); h.rectRenderNodesCount = 1;
      first.renderNode.setPosition(0,0,256,128); first.invalidate();
      assertPhase(first, 1.25f, 13.5f, "new panel phase");
      h.rectRenderNodesCount = 0;
      h.onScrolled(-3, -6.25f); // A temporarily hidden panel misses this callback.
      h.rectRenderNodesCount = 1;
      first.invalidate();
      assertPhase(first, -1.75f, 7.25f, "reattached panel phase");
      SourcePart second = h.new SourcePart();
      h.rectRenderNodes.add(second); h.rectRenderNodesCount = 2;
      assertPhase(second, -1.75f, 7.25f, "second panel phase");
      for (float delta : new float[] {0.25f, .5f, 32f, -9.75f, -64f, 0.125f}) {
        h.onScrolled(delta, -delta);
        assertPhase(first, h.scrollPhaseX, h.scrollPhaseY, "active panel phase");
        assertPhase(second, h.scrollPhaseX, h.scrollPhaseY, "shared panel phase");
      }
      float x=h.scrollPhaseX, y=h.scrollPhaseY;
      h.onScrolled(Float.NaN, 1); h.onScrolled(1, Float.POSITIVE_INFINITY);
      near(h.scrollPhaseX,x,"invalid scroll ignored x"); near(h.scrollPhaseY,y,"invalid scroll ignored y");
      LiteMode.glass = !glass; h.updateGlassMode();
      assertPhase(first, x, y, "mode toggle phase");
      assertPhase(second, x, y, "mode toggle hidden capture phase");
    }
  }
  static void assertPhase(SourcePart p, float x, float y, String label) {
    for (DownscaledRenderNode n : new DownscaledRenderNode[] {p.renderNodesForBlur, p.renderNodesForGlass}) {
      if (n == null) continue;
      near(fraction(n.renderNodeOriginalWithOffset.tx / n.scaleX), fraction(x / n.scaleX), label + " x");
      near(fraction(n.renderNodeOriginalWithOffset.ty / n.scaleY), fraction(y / n.scaleY), label + " y");
      for (RenderNode restored : n.renderNodeRestored) {
        near(restored.tx + n.renderNodeOriginalWithOffset.tx, 0, label + " x geometry");
        near(restored.ty + n.renderNodeOriginalWithOffset.ty, 0, label + " y geometry");
      }
    }
  }
}
"""
        with tempfile.TemporaryDirectory(prefix="frosted-scroll-") as temp:
            file = pathlib.Path(temp) / "DownscaleScrollableNoiseSuppressor.java"
            file.write_text(harness)
            result = subprocess.run(["javac", str(file)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            result = subprocess.run(["java", "-cp", temp, "DownscaleScrollableNoiseSuppressor"],
                                    capture_output=True, text=True)
            if expected_failure:
                self.assertNotEqual(result.returncode, 0, "negative control unexpectedly passed")
                self.assertIn(expected_failure, result.stderr)
            else:
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_negative_control_liquid_phase_disabled(self):
        source = (BLUR / "DownscaleScrollableNoiseSuppressor.java").read_text()
        source = source.replace("private void setScrollPhase(float x, float y) {",
                                "private void setScrollPhase(float x, float y) { if (isLiquidGlassEnabled) return;")
        self.run_sampling_harness(source, "source x phase")

    def test_negative_control_liquid_kernel_resamples(self):
        source = (BLUR / "DownscaleScrollableNoiseSuppressor.java").read_text()
        source = source.replace("setStableBlur(radius, secondEffect);", """
            setPrimaryEffect(RenderEffect.createChainEffect(RenderEffect.createBlurEffect(
                downscaleRadius(radius, scaleX), downscaleRadius(radius, scaleY), Shader.TileMode.CLAMP), secondEffect));
        """)
        self.run_sampling_harness(source, "no implicit liquid resampling")

    def test_negative_control_hidden_panel_loses_phase(self):
        source = (BLUR / "DownscaleScrollableNoiseSuppressor.java").read_text()
        source = source.replace("scrollPhaseX = (scrollPhaseX + dx) % 16;",
                                "if (rectRenderNodesCount == 0) return; scrollPhaseX = (scrollPhaseX + dx) % 16;")
        self.run_sampling_harness(source, "new panel phase")


if __name__ == "__main__":
    unittest.main()
