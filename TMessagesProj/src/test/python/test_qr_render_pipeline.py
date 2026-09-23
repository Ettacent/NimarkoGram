"""Production-method JVM tests. No Android build, renderer, or timing claims."""
from pathlib import Path
import subprocess
import tempfile
import unittest

from test_recording_composer_lifecycle import method

PATH = Path(__file__).resolve().parents[2] / "main/java/org/telegram/ui/QrActivity.java"
QR = PATH.read_text()


def run_java(source, *args):
    with tempfile.TemporaryDirectory(prefix="qr-pipeline-") as temp:
        path = Path(temp) / "Harness.java"
        path.write_text(source)
        result = subprocess.run(["javac", str(path)], capture_output=True, text=True)
        if result.returncode:
            raise AssertionError(result.stderr)
        result = subprocess.run(["java", "-cp", temp, "Harness", *args], capture_output=True, text=True)
        if result.returncode:
            raise AssertionError(result.stderr)
        return result.stdout


class QrRenderPipelineTests(unittest.TestCase):
    def test_full_java_syntax(self):
        run_java(r'''
import java.util.*;
import javax.tools.*;
import com.sun.source.util.JavacTask;
public class Harness {
 public static void main(String[] args) throws Exception {
  JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();
  DiagnosticCollector<JavaFileObject> diagnostics=new DiagnosticCollector<>();
  try(StandardJavaFileManager files=compiler.getStandardFileManager(diagnostics,null,null)) {
   JavacTask task=(JavacTask)compiler.getTask(null,files,diagnostics,List.of("-proc:none"),null,files.getJavaFileObjects(args));
   task.parse(); // Deliberately no attribution/build of Android sources.
   for(Diagnostic<?> d:diagnostics.getDiagnostics()) if(d.getKind()==Diagnostic.Kind.ERROR) throw new AssertionError(d);
  }
 }
}''', str(PATH))

    def test_layout_encoding_and_share_boundaries(self):
        size = method(QR, "protected void onSizeChanged(")
        self.assertNotIn("Bitmap.createBitmap", size)
        self.assertNotIn("setShadowLayer", size)
        self.assertIn("requestContent();", size)
        worker = method(QR, "private void prepareContent(")
        for forbidden in ("getParent()", "getWidth()", "getHeight()"):
            # Bitmap/layout dimensions are fine; view state must be a snapshot.
            self.assertNotIn("this." + forbidden, worker)
        self.assertNotIn("getParent()", worker)
        request = method(QR, "private void requestContent(")
        self.assertLess(request.index("final String renderLink = link"), request.index("prepareRunnable = () ->"))
        self.assertIn("Utilities.themeQueue.postRunnable(prepareRunnable)", request)
        self.assertIn("RuntimeException | OutOfMemoryError", request)
        self.assertIn("applyPreparedContent(generation, safeBackground, null, null)", request)
        self.assertIn("drawable == null ? 0", worker)
        self.assertIn("!isPhone && drawable != null", worker)
        self.assertIn("TelegramQRCodeWriter writer = new TelegramQRCodeWriter()", worker)
        self.assertEqual(QR.count("writer.encode("), 1)
        share = method(QR, "public void performShare(")
        self.assertLess(share.index("pendingShare = () ->"), share.index("Bitmap.createBitmap"))
        self.assertLess(share.index("if (qrView.hasContent())"), share.index("fragmentView.draw(canvas)"))
        self.assertLess(share.index("contentBitmapAlpha.set(1f, true)"), share.index("fragmentView.draw(canvas)"))
        self.assertIn("RuntimeException | OutOfMemoryError", share)
        self.assertIn("!TextUtils.equals(sharedLink, qrView.link)", share)
        self.assertIn("sharedExpires <= System.currentTimeMillis() / 1000", share)
        self.assertIn("sharingUser != UserConfig.getInstance(currentAccount).getClientUserId()", share)

    def test_production_render_scheduling_failure_staleness_and_share_readiness(self):
        methods = "\n".join(method(QR, s) for s in (
            "private void requestContent(", "private void applyPreparedContent(",
            "private boolean isReady(", "private boolean hasContent(",
            "private void cancelPreparation(", "private void dispose(",
        ))
        run_java(r'''
import java.util.*;
public class Harness {
 static void check(boolean b){if(!b)throw new AssertionError();}
 static boolean worker; static int failure, rendered; static String seenLink;
 static class Context {}
 static class View {int getMeasuredWidth(){return 400;} int getMeasuredHeight(){return 800;}}
 static class Bitmap {boolean recycled; void recycle(){check(!recycled);recycled=true;}}
 static class Rect {int left,top,right,bottom;}
 static class Alpha {float value;void setDuration(int d){}void set(float f,boolean force){value=f;}}
 static class TextUtils {}
 static class SharedConfig {static boolean animationsEnabled(){return true;}}
 static class Queue {
  List<Runnable> pending=new ArrayList<>();
  void postRunnable(Runnable r){pending.add(r);}
  void cancelRunnable(Runnable r){pending.remove(r);}
  void flush(){while(!pending.isEmpty()){worker=true;pending.remove(0).run();worker=false;}}
 }
 static class Utilities {static Queue themeQueue=new Queue();}
 static class AndroidUtilities {
  static float density=2; static List<Runnable> ui=new ArrayList<>();
  static void runOnUIThread(Runnable r){ui.add(r);}
  static void cancelRunOnUIThread(Runnable r){ui.remove(r);}
  static void flush(){while(!ui.isEmpty())ui.remove(0).run();}
 }
 static class FileLog {static void e(Throwable e){}}
 interface Center {void onCenterChanged(int a,int b,int c,int d);}
 static class Renderer {
  int renderGeneration,renderedGeneration=-1,w=260,h=330,linkExpires;
  boolean disposed,renderFailed,hasTimer,isPhone,logoCenterSet;
  String link="one",username="person"; Runnable prepareRunnable,readyListener,checkTimerToken=()->{};
  Bitmap backgroundBitmap,contentBitmap,oldContentBitmap; Center centerChangedListener;
  Alpha contentBitmapAlpha=new Alpha();
  int getWidth(){check(!worker);return w;} int getHeight(){check(!worker);return h;}
  Object getParent(){check(!worker);return new View();} Context getContext(){check(!worker);return new Context();}
  void invalidate(){check(!worker);}
  Bitmap prepareBackground(int w,int h,float d){check(worker);if(failure==1)throw new OutOfMemoryError("shadow");return new Bitmap();}
  void prepareContent(int w,int h,String userText,String link,boolean phone,boolean timer,boolean portrait,Context c,int g,Bitmap bg){
   check(worker);rendered++;seenLink=link;
   if(failure==2)throw new IllegalArgumentException("resource/raster");
   if(failure==3)throw new OutOfMemoryError("QR bitmap");
   Bitmap content=new Bitmap();AndroidUtilities.runOnUIThread(()->applyPreparedContent(g,bg,content,new Rect()));
  }
  METHODS
 }
 public static void main(String[] args){
  Renderer r=new Renderer();int[] ready={0};r.readyListener=()->ready[0]++;
  r.requestContent();check(!r.isReady() && rendered==0);r.link="changed-after-snapshot";
  Utilities.themeQueue.flush();check(seenLink.equals("one") && !r.isReady());
  AndroidUtilities.flush();check(r.isReady() && r.hasContent());
  // Coalesce obsolete queued sizes, and retire an already-rendered callback.
  r.requestContent();Utilities.themeQueue.flush();r.h=310;r.requestContent();
  AndroidUtilities.flush();check(!r.isReady());r.requestContent();check(Utilities.themeQueue.pending.size()==1);
  Utilities.themeQueue.flush();AndroidUtilities.flush();check(r.isReady());
  Bitmap stale=new Bitmap(),shared=new Bitmap();r.applyPreparedContent(r.renderGeneration-1,shared,stale,null);
  check(stale.recycled && !shared.recycled);
  for(failure=1;failure<=3;failure++){
   r.requestContent();Utilities.themeQueue.flush();AndroidUtilities.flush();
   check(r.isReady() && !r.hasContent()); // terminal failure unblocks reveal, never share
  }
  failure=0;r.requestContent();Utilities.themeQueue.flush();AndroidUtilities.flush();check(r.hasContent());
  r.hasTimer=true;r.linkExpires=(int)(System.currentTimeMillis()/1000)-1;check(!r.hasContent());
  r.linkExpires+=100;check(r.hasContent());
  r.requestContent();Utilities.themeQueue.flush();r.dispose();AndroidUtilities.flush();
  check(!r.isReady() && !r.hasContent() && r.contentBitmap==null);
  r=new Renderer();r.w=0;r.requestContent();check(Utilities.themeQueue.pending.isEmpty());
  r.w=260;r.requestContent();r.cancelPreparation();check(Utilities.themeQueue.pending.isEmpty());
  check(ready[0]>0);
 }
}'''.replace("METHODS", methods))

    def test_contact_token_callback_releases_pending_before_early_return(self):
        timer = method(QR, "private void checkTimerToken()")
        self.assertIn("private final Runnable checkTimerToken = this::checkTimerToken;", QR)
        self.assertIn("MessagesController.getInstance(currentAccount).requestContactToken", timer)
        self.assertNotIn("UserConfig.selectedAccount", timer)
        self.assertLess(timer.index("tokenRequestPending = false"), timer.index("if (disposed || token == null)"))
        self.assertNotIn("!isAttachedToWindow() || token", timer)
        self.assertIn("!tokenRequestPending &&", timer)
        self.assertIn("this.link = token.url", timer)
        self.assertNotIn("setData(token.url, null", timer)  # preserve private user's share name


if __name__ == "__main__":
    unittest.main()
