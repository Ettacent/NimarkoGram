"""Material contract checks; visual/AGSL execution still needs an Android device."""
import math
import unittest
from pathlib import Path

MAIN = Path(__file__).resolve().parents[2] / "main"


class LiquidGlassMaterialTests(unittest.TestCase):
    def test_shader_keeps_one_backdrop_sample_and_preserves_alpha(self):
        shader = (MAIN / "res/raw/liquid_glass_shader.agsl").read_text()
        self.assertEqual(shader.count("img.eval("), 1)
        self.assertIn("color.rgb = mix(color.rgb, half3(color.a), rim)", shader)
        self.assertNotIn("color.a =", shader)
        self.assertIn("sd < 0.0 && thickness > 0.0", shader)
        self.assertIn("float2 uv = fragCoord", shader)
        self.assertIn("uv = clamp(uv", shader)
        self.assertNotIn("refract_length", shader)

    def test_lens_has_no_displacement_jump_at_either_edge(self):
        def displacement(t, depth=14):
            t = min(1, max(0, t))
            return depth * .85 * 16 * t * t * (1 - t) ** 2
        for t in (-1, 0, 1, 2):
            self.assertEqual(displacement(t), 0)
        for n in range(1001):
            t = n / 1000
            self.assertLessEqual(displacement(t), 14 * .85)
            self.assertLess(abs(displacement(t + .0001) - displacement(t)), .01)
        self.assertLess(displacement(.00001), 1e-6)
        self.assertLess(displacement(.99999), 1e-6)

    def test_reflection_is_bounded_premultiplied_and_fades_to_zero(self):
        # CPU reference for the final reflection blend, over light/dark and
        # partially transparent surfaces, including zero-intensity transitions.
        for alpha in (0, .1, .5, 1):
            for rgb in (0, alpha * .3, alpha):
                for intensity in (0, .01, .4, .9, 1, 15):
                    for edge in (0, .5, 1):
                        for light in (0, .5, 1):
                            for shoulder in (0, .5, 1):
                                for enabled in (0, 1):
                                    rim = (edge * (.04 + .12 * light) + shoulder * .065 * light)
                                    rim *= min(max(intensity, 0), 1) * enabled
                                    result = rgb * (1 - rim) + alpha * rim
                                    self.assertTrue(math.isfinite(result))
                                    self.assertGreaterEqual(result, 0)
                                    self.assertLessEqual(result, alpha + 1e-8)
                                    if intensity == 0 or enabled == 0 or (edge == 0 and shoulder == 0):
                                        self.assertEqual(result, rgb)

    def test_continuous_uniform_updates_and_opt_in_factory(self):
        base = MAIN / "java/org/telegram/ui/Components/blur3"
        effect = (base / "LiquidGlassEffect.java").read_text()
        self.assertIn("Math.abs(this.intensity - intensity) > 0.001f", effect)
        factory = (base / "BlurredBackgroundDrawableViewFactory.java").read_text()
        self.assertIn("isLiquidGlassEffectAllowed && Build.VERSION.SDK_INT", factory)
        self.assertIn("NimarkoConfig.glareOnElements", effect)
        self.assertIn('shader.setFloatUniform("reflection_enabled"', effect)

    def test_surface_tint_is_shared_and_fallbacks_remain(self):
        source = (MAIN / "java/org/telegram/ui/Components/blur3/drawable/color/impl/BlurredBackgroundProviderImpl.java").read_text()
        self.assertIn("return isDark ? 0.46f : 0.62f;", source)
        self.assertIn("Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU", source)
        self.assertIn("!SharedConfig.chatBlurEnabled()", source)
        self.assertIn("return 0.85f;", source)
        self.assertIn("return 0.76f;", source)
        for method in ("mainTabs", "topPanel", "topPanelChatActivity", "bottomPanelChatActivity"):
            body = source.split("public static BlurredBackgroundProvider " + method + "(", 1)[1].split("public static", 1)[0]
            self.assertIn("glassSurfaceOpacity(isDark)", body)
        notification = (MAIN / "java/app/nimarkogram/messenger/notifications/NotificationGlassSurface.java").read_text()
        self.assertIn("BlurredBackgroundProviderImpl.topPanelChatActivity(resourcesProvider)", notification)


if __name__ == "__main__":
    unittest.main()
