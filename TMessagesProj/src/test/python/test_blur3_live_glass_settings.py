"""Execute shared live-setting logic with host stubs, without building the app."""
import pathlib
import subprocess
import tempfile
import unittest

from test_frosted_scroll_sampling import BLUR, block


def run_java(name, source):
    with tempfile.TemporaryDirectory(prefix="blur3-settings-") as temp:
        file = pathlib.Path(temp) / (name + ".java")
        file.write_text(source)
        for command in (["javac", str(file)], ["java", "-cp", temp, name]):
            result = subprocess.run(command, capture_output=True, text=True)
            if result.returncode:
                raise AssertionError(result.stdout + result.stderr)


class LiveGlassSettingsTests(unittest.TestCase):
    def test_themed_default_alpha_refreshes_but_explicit_overrides_survive(self):
        source = (BLUR / "drawable/color/BlurredBackgroundColorProviderThemed.java").read_text()
        production = block(source, "public class BlurredBackgroundColorProviderThemed")
        drawable = (BLUR / "drawable/BlurredBackgroundDrawable.java").read_text()
        self.assertIn("((BlurredBackgroundColorProviderThemed) colorProvider).updateColors();",
                      block(drawable, "public void updateColors()"))
        run_java("BlurredBackgroundColorProviderThemed", r"""
interface BlurredBackgroundColorProvider {
  int getShadowColor(); int getBackgroundColor(); int getStrokeColorTop();
  int getStrokeColorBottom(); int getStrokeColorFull();
}
class AndroidUtilities { static float computePerceivedBrightness(int color) { return .5f; } }
class Theme {
  interface ResourcesProvider {}
  static int key_divider = 1;
  static int getColor(int key, ResourcesProvider p) { return 0xff123456; }
  static int multAlpha(int color, float alpha) { return Math.round(255 * alpha); }
}
class LiteMode {
  static final int FLAG_LIQUID_GLASS = 1; static boolean enabled;
  static boolean isEnabled(int flag) { return enabled; }
}
""" + production[:-1] + r"""
  public static void main(String[] args) {
    BlurredBackgroundColorProviderThemed dynamic = new BlurredBackgroundColorProviderThemed(null, 0);
    BlurredBackgroundColorProviderThemed explicit = new BlurredBackgroundColorProviderThemed(null, 0, .31f);
    BlurredBackgroundColorProviderThemed setter = new BlurredBackgroundColorProviderThemed(null, 0);
    setter.setAlpha(.23f);
    for (boolean enabled : new boolean[] {false,true,false,true}) {
      LiteMode.enabled = enabled;
      dynamic.updateColors(); explicit.updateColors(); setter.updateColors();
      if (dynamic.getBackgroundColor() != Math.round(255 * (enabled ? .85f : .76f)))
        throw new AssertionError("stale default alpha");
      if (explicit.getBackgroundColor() != Math.round(255 * .31f)
          || setter.getBackgroundColor() != Math.round(255 * .23f))
        throw new AssertionError("explicit alpha overwritten");
    }
  }
}
""")

    def test_effective_gate_requires_platform_liquid_and_chat_blur(self):
        source = (BLUR / "BlurredBackgroundDrawableViewFactory.java").read_text()
        run_java("GateHarness", r"""
public class GateHarness {
  static class LiteMode {
    static final int FLAG_LIQUID_GLASS = 1; static boolean enabled;
    static boolean isEnabled(int flag) { return enabled; }
  }
  static class SharedConfig {
    static boolean blur;
    static boolean chatBlurEnabled() { return blur; }
  }
  static class Build {
    static class VERSION { static int SDK_INT; }
    static class VERSION_CODES { static final int TIRAMISU = 33; }
  }
""" + block(source, "public static boolean isLiquidGlassEnabled()") + r"""
  public static void main(String[] args) {
    for (int sdk : new int[] {29,31,32,33,35}) {
      Build.VERSION.SDK_INT = sdk;
      for (boolean glass : new boolean[] {false,true}) {
        LiteMode.enabled = glass;
        for (boolean blur : new boolean[] {false,true}) {
          SharedConfig.blur = blur;
          if (isLiquidGlassEnabled() != (sdk >= 33 && glass && blur)) throw new AssertionError();
        }
      }
    }
  }
}
""")

    def test_drawable_lazily_creates_and_reuses_effect_across_live_toggles(self):
        source = (BLUR / "drawable/BlurredBackgroundDrawableRenderNode.java").read_text()
        fields = source[source.index("    private LiquidGlassEffect liquidGlassEffect;"):
                        source.index("    @RequiresApi(api = Build.VERSION_CODES.TIRAMISU)")]
        methods = block(source, "public void setLiquidGlassEffectAllowed()")
        methods += block(source, "private void updateGlassSettings(boolean hardwareAccelerated)")
        self.assertIn("updateGlassSettings(canvas.isHardwareAccelerated());",
                      block(source, "public void draw(@NonNull Canvas canvas)"))
        run_java("DrawableHarness", r"""
public class DrawableHarness {
  static class LiteMode {
    static final int FLAG_LIQUID_GLASS = 1; static boolean enabled;
    static boolean isEnabled(int flag) { return enabled; }
  }
  static class Build {
    static class VERSION { static int SDK_INT = 33; }
    static class VERSION_CODES { static final int TIRAMISU = 33; }
  }
  static class SharedConfig { static boolean blur = true; }
  static class NimarkoConfig { static boolean glareOnElements; }
  static class BlurredBackgroundDrawableViewFactory {
    static boolean isLiquidGlassEnabled() {
      return LiteMode.enabled && SharedConfig.blur && Build.VERSION.SDK_INT >= 33;
    }
  }
  static class RenderNode { Object effect; void setRenderEffect(Object value) { effect = value; } }
  static class LiquidGlassEffect {
    RenderNode node;
    LiquidGlassEffect(RenderNode node) { this.node = node; }
    void setEnabled(boolean enabled) { node.setRenderEffect(enabled ? this : null); }
  }
  final RenderNode renderNodeFill = new RenderNode();
  boolean renderNodeInvalidated;
  int backgroundColor = 76, updates;
  void updateColors() {
    backgroundColor = BlurredBackgroundDrawableViewFactory.isLiquidGlassEnabled() ? 46 : 76;
    updates++;
    renderNodeInvalidated = true;
  }
""" + fields + methods + r"""
  static void check(boolean ok) { if (!ok) throw new AssertionError(); }
  public static void main(String[] args) {
    DrawableHarness h = new DrawableHarness();
    h.setLiquidGlassEffectAllowed();
    h.updateGlassSettings(true);
    check(h.liquidGlassEffect == null && h.renderNodeFill.effect == null);
    LiquidGlassEffect first = null;
    for (boolean enabled : new boolean[] {true, false, true, false, true}) {
      LiteMode.enabled = enabled;
      h.renderNodeInvalidated = false;
      h.updateGlassSettings(true);
      check(h.renderNodeInvalidated);
      check(h.backgroundColor == (enabled ? 46 : 76));
      if (first == null) first = h.liquidGlassEffect;
      check(h.liquidGlassEffect == first);
      check(h.renderNodeFill.effect == (enabled ? first : null));
      h.renderNodeInvalidated = false;
      int updates = h.updates;
      h.updateGlassSettings(true);
      check(!h.renderNodeInvalidated && h.updates == updates);
    }
    // A software snapshot updates color but must leave the retained hardware
    // effect graph alone until the next hardware draw.
    LiteMode.enabled = false;
    h.renderNodeInvalidated = false;
    h.updateGlassSettings(false);
    check(h.backgroundColor == 76 && h.renderNodeFill.effect == first && h.renderNodeInvalidated);
    h.updateGlassSettings(true);
    check(h.renderNodeFill.effect == null);
    // Capability persists even on unsupported SDKs, without attaching AGSL.
    LiteMode.enabled = true;
    Build.VERSION.SDK_INT = 32;
    h.updateGlassSettings(true);
    check(h.liquidGlassEffect == first && h.renderNodeFill.effect == null);
    Build.VERSION.SDK_INT = 33;
    h.updateGlassSettings(true);
    check(h.liquidGlassEffect == first && h.renderNodeFill.effect == first);
    SharedConfig.blur = false;
    h.updateGlassSettings(true);
    check(h.liquidGlassEffect == first && h.renderNodeFill.effect == null);
    SharedConfig.blur = true;
    h.updateGlassSettings(true);
    check(h.liquidGlassEffect == first && h.renderNodeFill.effect == first);
    // Frosted-only drawables must also refresh provider foreground on toggle.
    DrawableHarness frosted = new DrawableHarness();
    LiteMode.enabled = false;
    frosted.updateGlassSettings(true);
    check(frosted.liquidGlassEffect == null && frosted.updates == 1 && frosted.backgroundColor == 76);
    LiteMode.enabled = true;
    frosted.updateGlassSettings(true);
    check(frosted.liquidGlassEffect == null && frosted.updates == 2 && frosted.backgroundColor == 46);
    NimarkoConfig.glareOnElements = true;
    frosted.updateGlassSettings(true);
    check(frosted.updates == 3 && frosted.liquidGlassEffect == null);
  }
}
""")

    def test_source_clear_request_falls_back_without_losing_capability(self):
        source = (BLUR / "source/BlurredBackgroundSourceRenderNode.java").read_text()
        for method in ("public boolean isDisplayListReady()", "public void draw(Canvas canvas,"):
            self.assertIn("getEffectiveSuppressorIndex()", block(source, method))
        run_java("SourceHarness", r"""
public class SourceHarness {
  static class LiteMode {
    static final int FLAG_LIQUID_GLASS = 1; static boolean enabled;
    static boolean isEnabled(int flag) { return enabled; }
  }
  static class DownscaleScrollableNoiseSuppressor {
    static final int DRAW_GLASS=-2, DRAW_FROSTED_GLASS=-3, DRAW_FROSTED_GLASS_NO_SATURATION=-4;
  }
  static class BlurredBackgroundDrawableViewFactory {
    static boolean isLiquidGlassEnabled() { return LiteMode.enabled; }
  }
  int scrollableNoiseSuppressorIndex;
""" + block(source, "private int getEffectiveSuppressorIndex()") + r"""
  public static void main(String[] args) {
    SourceHarness h = new SourceHarness();
    for (int index : new int[] {-2,-3,-4}) {
      h.scrollableNoiseSuppressorIndex = index;
      for (boolean enabled : new boolean[] {false,true,false,true}) {
        LiteMode.enabled = enabled;
        int expected = !enabled && index == -2 ? -3 : index;
        if (h.getEffectiveSuppressorIndex() != expected || h.scrollableNoiseSuppressorIndex != index)
          throw new AssertionError();
      }
    }
  }
}
""")

    def test_factory_refreshes_every_created_drawable_and_dispatches_sources(self):
        source = (BLUR / "BlurredBackgroundDrawableViewFactory.java").read_text()
        self.assertIn("factories.add(this);", block(source, "public BlurredBackgroundDrawableViewFactory(BlurredBackgroundSource source)"))
        create = block(source, "private BlurredBackgroundDrawable create(")
        self.assertIn("createdDrawables.add(drawable);", create)
        self.assertIn("drawable.setColorProvider(provider);", create)
        self.assertNotIn("LiteMode.isEnabled", create)
        self.assertIn("createdDrawables.remove(drawable);", block(source, "public void release("))
        run_java("BlurredBackgroundDrawableViewFactory", r"""
import java.util.*;
import java.lang.ref.WeakReference;
public class BlurredBackgroundDrawableViewFactory {
  static class View { int invalidations; void invalidate() { invalidations++; } }
  static class BlurredBackgroundDrawable {
    int colors, redraws;
    void updateColors() { colors++; }
    void invalidateSelf() { redraws++; }
  }
  static class Source {
    int dispatches;
    void dispatchOnDrawablesRelativePositionChange() { dispatches++; }
  }
  static final List<BlurredBackgroundDrawableViewFactory> factories = new ArrayList<>();
  final List<BlurredBackgroundDrawable> createdDrawables = new ArrayList<>();
  final Map<BlurredBackgroundDrawable, WeakReference<View>> drawableViews = new WeakHashMap<>();
  final Source source = new Source();
  int linkedInvalidations;
  void invalidateAllLinkedViews() { linkedInvalidations++; }
""" + block(source, "public static void invalidateGlassSettings()") + r"""
  public static void main(String[] args) {
    for (int i=0;i<3;i++) {
      BlurredBackgroundDrawableViewFactory f = new BlurredBackgroundDrawableViewFactory();
      factories.add(f);
      for (int j=0;j<i;j++) f.createdDrawables.add(new BlurredBackgroundDrawable());
    }
    View manual = new View();
    factories.get(2).drawableViews.put(factories.get(2).createdDrawables.get(0), new WeakReference<>(manual));
    invalidateGlassSettings(); invalidateGlassSettings();
    if (manual.invalidations != 2) throw new AssertionError("manually drawn view missed refresh");
    for (BlurredBackgroundDrawableViewFactory f : factories) {
      if (f.linkedInvalidations != 2 || f.source.dispatches != 2) throw new AssertionError();
      for (BlurredBackgroundDrawable d : f.createdDrawables)
        if (d.colors != 2 || d.redraws != 2) throw new AssertionError();
    }
  }
}
""")

    def test_fresh_shader_initializes_even_zero_valued_uniforms(self):
        source = (BLUR / "LiquidGlassEffect.java").read_text()
        production = block(source, "public class LiquidGlassEffect")
        # Replace only the app-config namespace; execute the actual constructor
        # and uniform-cache update against a recording RuntimeShader stub.
        production = production.replace("app.nimarkogram.messenger.NimarkoConfig", "NimarkoConfig")
        run_java("LiquidGlassEffect", r"""
class NimarkoConfig { static boolean glareOnElements; }
class R { static class raw { static int liquid_glass_shader; } }
class AndroidUtilities { static String readRes(int id) { return ""; } }
class Color {
  static int alpha(int c) { return c >>> 24; }
  static int red(int c) { return (c >>> 16) & 255; }
  static int green(int c) { return (c >>> 8) & 255; }
  static int blue(int c) { return c & 255; }
}
class RuntimeShader {
  static int writes;
  RuntimeShader(String code) {}
  void setFloatUniform(String key, float... values) { writes++; }
}
class RenderEffect {
  static RenderEffect createRuntimeShaderEffect(RuntimeShader shader, String input) { return new RenderEffect(); }
}
class RenderNode {
  int getWidth() { return 0; } int getHeight() { return 0; }
  void setRenderEffect(RenderEffect effect) {}
}
""" + production[:-1] + r"""
  public static void main(String[] args) {
    LiquidGlassEffect e = new LiquidGlassEffect(new RenderNode());
    e.update(0,0,0,0,0,0,0,0,0,0,0,0);
    if (RuntimeShader.writes != 9) throw new AssertionError("first uniforms skipped");
    e.update(0,0,0,0,0,0,0,0,0,0,0,0);
    if (RuntimeShader.writes != 9) throw new AssertionError("unchanged uniforms rewritten");
    e = new LiquidGlassEffect(new RenderNode());
    e.update(0,0,0,0,0,0,0,0,0,0,0,0);
    if (RuntimeShader.writes != 18) throw new AssertionError("recreated uniforms skipped");
    e.update(0,0,0,0,0,0,0,0,0,0,0,0xff123456);
    if (e.getForegroundColor() != 0xff123456 || RuntimeShader.writes != 27)
      throw new AssertionError("stale foreground");
  }
}
""")


if __name__ == "__main__":
    unittest.main()
