"""Optional desktop Skia check of the real shader (not Android GPU validation)."""
from pathlib import Path
import unittest

try:
    import skia
    import numpy as np
except ImportError:
    skia = None


@unittest.skipIf(skia is None, "optional skia-python dependency")
class GlassRuntimeTests(unittest.TestCase):
    def setUp(self):
        path = Path(__file__).resolve().parents[2] / "main/res/raw/liquid_glass_shader.agsl"
        self.effect = skia.RuntimeEffect.MakeForShader(path.read_text())

    def render(self, width=320, height=44, offset=0., intensity=.9, transparent=False, source_offset=0.):
        builder = skia.RuntimeShaderBuilder(self.effect)
        for name, value in {
            "resolution": [float(width), float(height)],
            "center": [width / 2 + offset, height / 2],
            "size": [width / 2, height / 2],
            "radius": [height / 2] * 4,
            "thickness": 8.8,
            "refract_index": 1.5,
            "refract_intensity": intensity,
            "reflection_enabled": 1.,
            "foreground_color_premultiplied": [0., 0., 0., 0.] if transparent else [.05, .06, .08, .46],
        }.items():
            builder.setUniform(name, value)
        backdrop = skia.Shaders.Color(skia.ColorTRANSPARENT) if transparent else skia.GradientShader.MakeLinear(
            [(0., source_offset), (float(width), float(height) + source_offset)], [skia.ColorWHITE, skia.ColorBLUE])
        builder.setChild("img", backdrop)
        surface = skia.Surface(width, height)
        surface.getCanvas().clear(skia.ColorTRANSPARENT)
        surface.getCanvas().drawPaint(skia.Paint(Shader=builder.makeShader()))
        return surface.makeImageSnapshot().toarray().astype(np.int16)

    def test_transparency_and_determinism(self):
        self.assertEqual(int(self.render(transparent=True).max()), 0)
        self.assertTrue(np.array_equal(self.render(), self.render()))

    def test_small_geometry_change_does_not_flash(self):
        for width, height in ((44, 44), (320, 44), (320, 88), (640, 132)):
            original = self.render(width, height)
            moved = self.render(width, height, offset=.1)
            self.assertLessEqual(int(np.abs(original - moved).max()), 8)

    def test_lens_changes_edges_without_distorting_center(self):
        plain = self.render(intensity=0.)
        glass = self.render()
        self.assertGreater(int(np.abs(plain - glass).max()), 0)
        self.assertTrue(np.array_equal(plain[20:24, 100:200], glass[20:24, 100:200]))

    def test_slow_backdrop_scroll_has_no_shader_threshold(self):
        previous = self.render(width=72, height=28)
        for i in range(1, 65):
            frame = self.render(width=72, height=28, source_offset=i / 8.)
            self.assertLessEqual(int(np.abs(frame - previous).max()), 2)
            previous = frame


if __name__ == "__main__":
    unittest.main()
