"""Focused source/JVM checks; no Android rendering or frame-time claims."""
from pathlib import Path
import unittest

from test_qr_render_pipeline import run_java
from test_recording_composer_lifecycle import method


JAVA = Path(__file__).resolve().parents[2] / "main/java"
PREFS = JAVA / "app/nimarkogram/messenger/preferences"
QR = JAVA / "org/telegram/ui/QrActivity.java"
SHEET = JAVA / "org/telegram/ui/Components/QRCodeBottomSheet.java"
MENU = JAVA / "org/telegram/ui/Components/ItemOptions.java"


class BoundedUiTests(unittest.TestCase):
    def test_touched_java_sources_parse(self):
        files = [
            PREFS / "BottomTabsPreferencesActivity.java",
            PREFS / "MainPreferencesActivity.java",
            PREFS / "MessagesAndProfilesPreferencesActivity.java",
            QR, SHEET, MENU,
        ]
        run_java(r'''
import java.util.*;
import javax.tools.*;
import com.sun.source.util.JavacTask;
public class Harness {
 public static void main(String[] args) throws Exception {
  JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();
  DiagnosticCollector<JavaFileObject> diagnostics=new DiagnosticCollector<>();
  try(StandardJavaFileManager files=compiler.getStandardFileManager(diagnostics,null,null)) {
   JavacTask task=(JavacTask)compiler.getTask(null,files,diagnostics,List.of("-proc:none"),null,
    files.getJavaFileObjects(args));
   task.parse();
   for(Diagnostic<?> d:diagnostics.getDiagnostics())
    if(d.getKind()==Diagnostic.Kind.ERROR)throw new AssertionError(d);
  }
 }
}''', *map(str, files))

    def test_settings_deferred_work_is_bound_to_its_view(self):
        main = (PREFS / "MainPreferencesActivity.java").read_text()
        refresh = method(main, "private void refreshSearch(")
        self.assertIn("host.postOnAnimation(searchRefresh)", refresh)
        self.assertIn("searchRefreshHost != host || listView != host || isFinished", refresh)
        self.assertIn("cancelSearchRefresh();", method(main, "public View createView("))
        self.assertIn("cancelSearchRefresh();", method(main, "public void onFragmentDestroy("))

        pages = (PREFS / "MessagesAndProfilesPreferencesActivity.java").read_text()
        self.assertIn("contentView != openingView || isFinished", pages)
        self.assertIn("openingView.post(initialSettingScroll)", pages)
        self.assertIn("cancelInitialSettingScroll();", method(pages, "public void onFragmentDestroy("))

        tabs = (PREFS / "BottomTabsPreferencesActivity.java").read_text()
        delayed = tabs[tabs.index("private final Runnable delayedStructureRefresh"):
                       tabs.index("};", tabs.index("private final Runnable delayedStructureRefresh"))]
        self.assertIn("listView.adapter.update(true)", delayed)
        self.assertNotIn("notifyDataSetChanged", delayed)

    def test_qr_opening_failure_and_detach_are_bounded(self):
        qr = QR.read_text()
        create = method(qr, "public View createView(")
        self.assertIn("AndroidUtilities.runOnUIThread(initialThemeFallback, 1000)", create)
        self.assertIn("initialBackgroundReady = true;", create)
        self.assertIn("RuntimeException | OutOfMemoryError", create)
        self.assertIn("|| initialBackgroundReady", create)  # ignore a late wallpaper
        self.assertIn("cancelInitialThemeFallback();", method(qr, "public void onFragmentDestroy("))
        token = method(qr, "private void checkTimerToken()")
        self.assertIn("if (isAttachedToWindow()) {\n                            requestContent();", token)

    def test_null_encoded_qr_cannot_be_shared(self):
        sheet = SHEET.read_text()
        prepare = method(sheet, "private static PreparedQr prepareQr(")
        run_java(r'''
import java.util.*;
public class Harness {
 enum EncodeHintType { ERROR_CORRECTION, MARGIN }
 enum ErrorCorrectionLevel { M }
 static class Bitmap {}
 static class PreparedQr {
  Bitmap bitmap;int imageSize;
  PreparedQr(Bitmap b,int s){bitmap=b;imageSize=s;}
 }
 static class TelegramQRCodeWriter {
  static int mode;
  Bitmap encode(String key,int w,int h,HashMap<EncodeHintType,Object> hints,Bitmap oldBitmap) {
   if(mode==2)throw new IllegalArgumentException();
   return mode==0?null:new Bitmap();
  }
  int getImageSize(){return 42;}
 }
 static class FileLog {static void e(Throwable t){}}
 PREPARE
 public static void main(String[] args) {
  if(prepareQr("link",null)!=null)throw new AssertionError("null advertised");
  TelegramQRCodeWriter.mode=1;
  PreparedQr qr=prepareQr("link",null);
  if(qr==null || qr.bitmap==null || qr.imageSize!=42)throw new AssertionError("good QR lost");
  TelegramQRCodeWriter.mode=2;
  if(prepareQr("link",null)!=null)throw new AssertionError("failure advertised");
 }
}'''.replace("PREPARE", prepare))
        self.assertIn("Utilities.themeQueue.postRunnable(() ->", sheet)
        self.assertIn("generation != shareGeneration || qrCode != bitmap || isDismissed()", sheet)
        self.assertIn("cancelQrSharing();", method(sheet, "private void releaseQr("))

    def test_menu_dim_listener_and_anchor_are_retired(self):
        menu = MENU.read_text()
        dismiss = method(menu, "private void dismissDim(")
        self.assertIn("final ViewTreeObserver observer = preDrawObserver", dismiss)
        self.assertIn("final ViewTreeObserver.OnPreDrawListener listener = preDrawListener", dismiss)
        self.assertIn("if (dimAnimator == animation) dimAnimator = null", dismiss)
        self.assertIn("dimViewFinal.restoreBlurAnchor();", dismiss)
        self.assertIn("dim.restoreBlurAnchor();", menu)
        self.assertIn("if (!blurCaptureActive)", menu)


if __name__ == "__main__":
    unittest.main()
