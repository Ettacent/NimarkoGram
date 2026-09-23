"""Complete capsule bounds on host/JVM stubs, not Android/GPU visual QA.

Reuses the gesture harness's layout/pivot stubs. Production motion, layout, draw,
touch and settle methods are extracted on every run.
"""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

from test_infocard_drag_continuity import java_source, method

ROOT = Path(__file__).resolve().parents[2]
STRIP = (ROOT / "main/java/app/nimarkogram/messenger/infocards/InfoCardStripView.java").read_text()


def edge_source():
    source = java_source()
    extra = method(STRIP, "protected void dispatchDraw(")
    source = source.replace("private BaseInfoCard current()", extra + "\n private BaseInfoCard current()")
    source = source.replace("int usableCardWidth(){return 200;}", """
  boolean hasPresentedCard;boolean isHostVisible(float alpha){return true;}
  int usableCardWidth(){return 1000;}
""")
    fixture = (ROOT / "test/fixtures/InfoCardEdgeGeometryHarness.java.txt").read_text()
    source = source.replace("public static void main(String[] args){", fixture + "\n public static void main(String[] args){")
    source = source.replace('switch(args[0]){', '''switch(args[0]){
   case "bounds":bounds();break;case "resize":resize();break;
   case "settleBounds":settleBounds();break;case "finiteBounds":finiteBounds();break;
   case "draw":draw();break;
''')
    return source


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "JDK required")
class InfoCardEdgeGeometryTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="infocard-edge-")
        cls.addClassCleanup(cls.temp.cleanup)
        cls.source = edge_source()
        cls.compile(cls.temp.name, cls.source)

    @staticmethod
    def compile(folder, source):
        path = Path(folder) / "InfoCardDragContinuityHarness.java"
        path.write_text(source)
        result = subprocess.run(["javac", str(path)], capture_output=True, text=True)
        if result.returncode:
            raise AssertionError(result.stderr)

    def run_case(self, case, folder=None):
        return subprocess.run(["java", "-cp", folder or self.temp.name,
                               "InfoCardDragContinuityHarness", case], capture_output=True, text=True)

    def test_complete_capsules_in_live_viewport(self):
        for case in ("bounds", "resize", "settleBounds", "finiteBounds", "draw"):
            with self.subTest(case=case):
                result = self.run_case(case)
                self.assertEqual(result.returncode, 0, result.stderr)
                print(result.stdout.strip())

    def test_existing_gesture_regressions_with_real_geometry(self):
        for case in ("release", "reversal", "cancel", "retouch", "tap"):
            with self.subTest(case=case):
                result = self.run_case(case)
                self.assertEqual(result.returncode, 0, result.stderr)
                print(result.stdout.strip())

    def test_no_mask_or_measurement_workaround(self):
        for forbidden in ("DST_OUT", "saveLayer", "LinearGradient", "carouselEdgePaint"):
            self.assertNotIn(forbidden, STRIP)
        geometry = method(STRIP, "private void applyCarouselTransforms(")
        for forbidden in (".measure(", ".layout(", "setMaxChipWidth", "setLayoutParams"):
            self.assertNotIn(forbidden, geometry)
        self.assertIn("inlineFolderStyle && carouselWidth() != getMeasuredWidth()",
                      method(STRIP, "private void applyDrag("))
        self.assertIn("animator = createSettleAnimator(1f);",
                      method(STRIP, "private void animateCommit("))

    def test_negative_controls(self):
        old_motion = """private void applyCarouselTransforms(int incomingIdx, float p, boolean up, float h) {
         BaseInfoCard cur=current(),in=pills.get(incomingIdx);float dir=up?-1:1;
         setCarouselTransform(cur,1-.28f*p,cur.getTop()+cur.getHeight()/2f+dir*h*1.35f*p);
         setCarouselTransform(in,.72f+.28f*p,in.getTop()+in.getHeight()/2f-dir*h*1.35f*(1-p));
         cur.setAlpha(1-p);in.setAlpha(p);
        }"""
        for case, before, after in (
            ("bounds", method(STRIP, "private void applyCarouselTransforms("), old_motion),
            ("bounds", "outScale = Math.min(outScale, getWidth() / (float) Math.max(1, cur.getWidth()));", ""),
            ("resize", "applyCarouselTransforms(incomingIndex, dragProgress, dragUp, dragHeight());", ""),
        ):
            with self.subTest(case=case, mutation=before[:60]), tempfile.TemporaryDirectory(prefix="infocard-edge-negative-") as folder:
                self.assertIn(before, self.source)
                self.compile(folder, self.source.replace(before, after))
                result = self.run_case(case, folder)
                self.assertNotEqual(result.returncode, 0, "broken geometry must fail")
                self.assertIn("AssertionError", result.stderr)


if __name__ == "__main__":
    unittest.main()
