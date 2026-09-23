"""Execute the per-occurrence appearance state from production Java."""
from pathlib import Path
import subprocess
import tempfile
import unittest
from test_emoji_first_frame_fade import method

JAVA = Path(__file__).resolve().parents[2] / "main/java/org/telegram/ui/Components/AnimatedEmojiSpan.java"


class EmojiSpanLoadAppearance(unittest.TestCase):
    def test_shared_receiver_and_warm_cache(self):
        state = method(JAVA.read_text(), "static final class LoadAppearance")
        code = "public class Harness {\n" + state + """
          static void check(boolean b) { if (!b) throw new AssertionError(); }
          public static void main(String[] args) {
            LoadAppearance warm = new LoadAppearance();
            check(warm.update(true, 1, 0) == 1);
            LoadAppearance delayed = new LoadAppearance();
            check(delayed.update(false, 1, 0) == 1);
            check(delayed.update(true, 1, 1000) == 0);
            check(delayed.update(true, 1, 1090) == .5f);
            check(delayed.update(true, 1, 1180) == 1);
            check(delayed.update(true, 1, 1200) == 1);
            LoadAppearance sharedFade = new LoadAppearance();
            sharedFade.update(false, 1, 0);
            check(sharedFade.update(true, .2f, 20) == 1);
            check(sharedFade.update(true, 1, 200) == 1);
            LoadAppearance otherOccurrence = new LoadAppearance();
            otherOccurrence.update(false, 1, 200);
            check(otherOccurrence.update(true, 1, 300) == 0);
            check(delayed.update(true, 1, 300) == 1);
            otherOccurrence.update(false, 1, 350);
            check(otherOccurrence.update(true, 1, 400) == 0);
          }
        }
        """
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "Harness.java"
            source.write_text(code)
            subprocess.run(["javac", str(source)], check=True, capture_output=True)
            subprocess.run(["java", "-cp", tmp, "Harness"], check=True, capture_output=True)

    def test_ui_and_worker_use_local_opacity(self):
        source = JAVA.read_text()
        self.assertIn("alpha *= loadAlpha;", source)
        self.assertIn("backgroundDrawHolder[threadIndex].overrideAlpha = alpha * loadAlpha;", source)
        self.assertIn("if (holder.span.spanDrawn) holder.updateLoadAppearance(alpha * holder.alpha);", source)


if __name__ == "__main__":
    unittest.main()
