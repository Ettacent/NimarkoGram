"""Run production infocard touch/transform/settle methods on JVM transport stubs.

No APK/Gradle. The real easing implementation is included so short-release timing
is checked in milliseconds, not just by inspecting a duration literal.
"""
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
STRIP = ROOT / 'main/java/app/nimarkogram/messenger/infocards/InfoCardStripView.java'


def method(source, signature):
    start = source.index(signature)
    opening = source.index('{', start)
    depth = 0
    for token in re.finditer(r'//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"|[{}]', source[opening:]):
        if token[0] == '{':
            depth += 1
        elif token[0] == '}':
            depth -= 1
            if depth == 0:
                return source[start:opening + token.end()]
    raise AssertionError(signature)


def java_source():
    source = STRIP.read_text()
    methods = '\n'.join(method(source, signature) for signature in (
        'private BaseInfoCard current()', 'private int neighbor(', 'private float dragHeight()',
        'public boolean onInterceptTouchEvent(', 'public boolean onTouchEvent(',
        'private void setCardsPressed(', 'private void beginDrag(',
        'private void ensureIncomingPrepared(', 'private void applyDrag(',
        'private void applyCarouselTransforms(', 'private void setCarouselTransform(',
        'protected void onLayout(',
        'private int carouselWidth()', 'private void applyResting()',
        'private ValueAnimator createSettleAnimator(',
        'private void applyResting(boolean', 'private void animateCommit(',
        'private void animateSnapBack(', 'private void cancelAnim()',
        'private void cancelAnimResume()', 'private void releaseTracker()',
    ))
    constants = '\n'.join(re.findall(
        r'private static final (?:int|float) (?:DRAG_DISTANCE_DP|COMMIT_FRACTION|FLING_DP_PER_S) = [^;]+;', source))
    easing = (ROOT / 'main/java/org/telegram/ui/Components/CubicBezierInterpolator.java').read_text()
    easing = re.sub(r'^(?:package|import) [^;]+;\s*', '', easing, flags=re.M)
    easing = easing.replace('public class CubicBezierInterpolator', 'class CubicBezierInterpolator')
    template = (ROOT / 'test/fixtures/InfoCardDragContinuityHarness.java.txt').read_text()
    return template.replace('PRODUCTION_METHODS', methods).replace('PRODUCTION_CONSTANTS', constants).replace('PRODUCTION_EASING', easing)


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'JDK required')
class InfoCardDragContinuityTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='infocard-drag-')
        cls.addClassCleanup(cls.temp.cleanup)
        cls.source = java_source()
        cls.compile(cls.temp.name, cls.source)

    @staticmethod
    def compile(folder, source):
        path = Path(folder) / 'InfoCardDragContinuityHarness.java'
        path.write_text(source)
        result = subprocess.run(['javac', str(path)], capture_output=True, text=True)
        if result.returncode:
            raise AssertionError(result.stderr)

    def run_case(self, case, folder=None):
        return subprocess.run(['java', '-cp', folder or self.temp.name,
                               'InfoCardDragContinuityHarness', case], capture_output=True, text=True)

    def test_production_gesture_paths(self):
        for case in ('release', 'reversal', 'cancel', 'retouch', 'tap', 'momentum', 'continuous'):
            with self.subTest(case=case):
                result = self.run_case(case)
                self.assertEqual(result.returncode, 0, result.stderr)

    def test_regression_negative_controls(self):
        for case, before, after in (
            ('release', 'result.setDuration(duration);',
             'result.setDuration(Math.round(duration * distance));'),
            ('retouch', 'if (neighbor(dragUp) < 0) {', 'if (false) {'),
            ('momentum', 'Math.min(3f, velocity * duration / (1000f * distance))', '0f'),
            ('momentum', '!flingReturns && (dragProgress > COMMIT_FRACTION || flingMatches)',
             '(dragProgress > COMMIT_FRACTION || flingMatches)'),
            ('retouch', 'setCardsPressed(potentialTap);', 'setCardsPressed(true);'),
            ('continuous', 'int pages = (int) (Math.abs(dy) / h);', 'int pages = 0;'),
            ('continuous', 'int next = (currentIndex + (dragUp ? step : count - step)) % count;',
             'int next = Math.max(0, Math.min(count - 1, currentIndex + (dragUp ? pages : -pages)));'),
            ('continuous', 'if (crossed && current() != null) {', 'if (false) {'),
        ):
            with self.subTest(case=case), tempfile.TemporaryDirectory(prefix='infocard-drag-negative-') as folder:
                self.assertIn(before, self.source)
                self.compile(folder, self.source.replace(before, after))
                result = self.run_case(case, folder)
                self.assertNotEqual(result.returncode, 0, 'old behavior must fail')
                self.assertIn('AssertionError', result.stderr)


if __name__ == '__main__':
    unittest.main()
