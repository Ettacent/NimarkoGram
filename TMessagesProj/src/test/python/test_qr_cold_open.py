"""Cold QR opening: production frame selection and thread-boundary contracts.

Host tests do not measure Android frame times or render the view.
"""
from pathlib import Path
import subprocess
import tempfile
import unittest

from test_recording_composer_lifecycle import method

JAVA = Path(__file__).resolve().parents[2] / "main/java"
QR = (JAVA / "org/telegram/ui/QrActivity.java").read_text()


class QrColdOpenTests(unittest.TestCase):
    def test_initial_theme_icon_never_waits_for_decoder(self):
        production = method(QR, "public void setForceDark(")
        harness = r"""
public class Harness {
 boolean forceDark;
 static class Drawable {
  int frame=-1, end=-1, calls;
  int getFramesCount(){return 60;}
  void setCustomEndFrame(int value){end=value;}
  void setCurrentFrame(int value,boolean async,boolean reset){
   if(!async)throw new AssertionError("UI thread waited for cold Lottie decoder");
   if(!reset)throw new AssertionError("static frame must be refreshed");
   frame=value;calls++;
  }
 }
 static class View {int plays;void playAnimation(){plays++;}void invalidate(){}}
 Drawable darkThemeDrawable=new Drawable();View darkThemeView;
 PRODUCTION
 public static void main(String[] args){
  Harness h=new Harness();
  // createView selects a frame before constructing / attaching the ImageView.
  h.setForceDark(true,false);
  if(h.darkThemeDrawable.frame!=59 || h.darkThemeDrawable.end!=59)throw new AssertionError();
  h.setForceDark(true,false);
  if(h.darkThemeDrawable.calls!=1)throw new AssertionError("duplicate initial decode");
  h.darkThemeView=new View();h.setForceDark(false,false);
  if(h.darkThemeDrawable.frame!=0)throw new AssertionError("light initial frame");
  h.setForceDark(true,true);
  if(h.darkThemeDrawable.calls!=2 || h.darkThemeView.plays!=1 || h.darkThemeDrawable.end!=59)
   throw new AssertionError("interactive theme animation changed");
 }
}
""".replace("PRODUCTION", production)
        with tempfile.TemporaryDirectory(prefix="qr-cold-open-") as temp:
            path = Path(temp) / "Harness.java"
            path.write_text(harness)
            subprocess.run(["javac", str(path)], check=True, capture_output=True)
            result = subprocess.run(["java", "-cp", temp, "Harness"], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            # Negative control: the previous synchronous implementation must fail.
            path.write_text(harness.replace("setCurrentFrame(frame, true, true)", "setCurrentFrame(frame, false, true)"))
            subprocess.run(["javac", str(path)], check=True, capture_output=True)
            result = subprocess.run(["java", "-cp", temp, "Harness"], capture_output=True, text=True)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn("UI thread waited", result.stderr)

    def test_initial_palette_is_prepared_before_ui_callback(self):
        create = method(QR, "public View createView(")
        queue = create.index("Utilities.themeQueue.postRunnable(() -> {")
        prepared = create.index("final SparseIntArray openingColors = openingTheme.createColors(")
        callback = create.index("AndroidUtilities.runOnUIThread(() -> {", queue)
        self.assertLess(queue, prepared)
        self.assertLess(prepared, callback)
        apply = create.index("resourcesProvider.setColors(openingColors)", callback)
        guard = create.index("fragmentView != openingView || themesViewController == null", callback)
        self.assertLess(guard, apply)
        self.assertLess(apply, create.index("revealInitialPage();", apply))
        self.assertNotIn("resourcesProvider.initColors(", create)
        setter = method(QR, "void setColors(SparseIntArray preparedColors)")
        self.assertIn("colors = preparedColors;", setter)
        self.assertNotIn("createColors", setter)

    def test_theme_request_is_retired_with_view(self):
        self.assertNotIn(".requestAllChatThemes(", QR)
        request = method(QR, "private void requestQrThemes(")
        self.assertIn("Utilities.themeQueue::postRunnable", request)
        self.assertLess(request.index("getParentActivity() == null"), request.index("Toast.makeText("))
        retire = method(QR, "private void retireQrThemes(")
        self.assertLess(retire.index("subscription.cancelled = true"), retire.index("Utilities.themeQueue.postRunnable"))
        self.assertIn("request.connections.cancelRequest(request.id, true)", retire)
        for signature in ("public View createView(", "public void onFragmentDestroy("):
            self.assertIn("retireQrThemes();", method(QR, signature))


if __name__ == "__main__":
    unittest.main()
