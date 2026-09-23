"""Theme/material regressions; actual Android rendering still needs device QA."""
import json
import re
import unittest
from pathlib import Path

from test_recording_composer_lifecycle import ENTER, method

MAIN = Path(__file__).resolve().parents[2] / "main"
CARD = (MAIN / "java/app/nimarkogram/messenger/infocards/BaseInfoCard.java").read_text()


def objects(value):
    if isinstance(value, dict):
        yield value
        for child in value.values():
            yield from objects(child)
    elif isinstance(value, list):
        for child in value:
            yield from objects(child)


class ComposerCardThemeColorsTests(unittest.TestCase):
    def test_dot_asset_has_no_painted_background_or_grey_stage(self):
        dot = method(ENTER, "private class RecordDot extends View")
        asset_name = re.search(r"int resId = R.raw.(\w+);", dot)[1]
        asset = json.loads((MAIN / f"res/raw/{asset_name}.json").read_text())
        colors = method(dot, "public void updateColors()")
        self.assertNotIn("key_chat_messagePanelBackground", colors)
        names = set(re.findall(r'setLayerColor\("([^"]+)", dotColor\)', colors))
        self.assertEqual(names, {layer["nm"] for layer in asset["layers"]})
        # All shapes share the recording colour; none paints white slits or a
        # hard-coded grey lid. Holes are paths in the same compound fill.
        paints = [x for x in objects(asset) if x.get("ty") in ("fl", "st")]
        self.assertTrue(paints)
        for paint in paints:
            red, green, blue, alpha = paint["c"]["k"]
            self.assertGreater(red, green * 2)
            self.assertLess(blue, red)
            self.assertEqual(alpha, 1)
        body = next(layer for layer in asset["layers"] if layer["nm"] == "Box")
        paths = [x for x in objects(body["shapes"]) if x.get("ty") == "sh"]
        self.assertEqual(len(paths), 4)  # silhouette + three cut-outs
        self.assertEqual(len([x for x in objects(body) if x.get("ty") == "fl"]), 1)

    def test_brand_retains_colour_without_turning_off_glass(self):
        helper = method(CARD, "private static float getBrandedGlassOpacity(")
        dark, light = map(float, re.search(r"return dark \? ([.\d]+)f : ([.\d]+)f", helper).groups())
        self.assertTrue(.60 < dark <= light < 1)
        self.assertGreaterEqual(light, .88)
        inline = method(CARD, "private void drawInline(")
        self.assertIn("glass.draw(canvas)", inline)
        self.assertIn("themeMode ? .18f : brandedGlassOpacity", inline)
        self.assertIn("fillPaint.setAlpha(oldAlpha)", inline)
        mode = method(CARD, "protected void applyColorMode()")
        self.assertIn("Theme.key_windowBackgroundWhite, resourcesProvider", mode)
        # USD brand over a white surface: more saturation and higher contrast
        # for the existing white label than the old 60% tint.
        brand = -14840995 & 0xffffff
        rgb = [(brand >> shift) & 255 for shift in (16, 8, 0)]
        composite = lambda opacity: [255 * (1-opacity) + x * opacity for x in rgb]
        old, new = composite(.60), composite(light)
        self.assertGreater(max(new) - min(new), max(old) - min(old))
        self.assertLess(sum(new), sum(old))

    def test_nested_glass_invalidations_reach_the_background_view(self):
        self.assertIn("background.glass.setCallback(background)", CARD)
        wrapper = method(CARD, "private static final class CardBackground")
        self.assertIn("implements Drawable.Callback", wrapper)
        self.assertIn("invalidateSelf()", method(wrapper, "public void invalidateDrawable("))
        self.assertIn("scheduleSelf(what, when)", method(wrapper, "public void scheduleDrawable("))
        self.assertIn("unscheduleSelf(what)", method(wrapper, "public void unscheduleDrawable("))
        self.assertIn("glass.setAlpha(alpha)", method(wrapper, "public void setAlpha("))


if __name__ == "__main__":
    unittest.main()
