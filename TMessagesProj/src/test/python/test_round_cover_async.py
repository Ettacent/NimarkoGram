"""JVM/source regression for the cold round-camera cover; no APK or device needed."""
import shutil
import unittest
from pathlib import Path

from test_recording_composer_lifecycle import method
from test_sender_infocard_transitions import run_java


VIEW = (Path(__file__).resolve().parents[2] / 'main/java/org/telegram/ui/Components/InstantCameraView.java').read_text()


@unittest.skipUnless(shutil.which('javac'), 'JDK required')
class RoundCoverAsyncTests(unittest.TestCase):
    def test_decode_runs_off_ui_and_late_results_are_discarded(self):
        load = method(VIEW, 'private void loadLastCameraBitmapAsync(')
        accept = method(VIEW, 'private void onLastCameraBitmapLoaded(')
        run_java('''import java.io.File;import java.util.*;
public class Transitions {
 static void check(boolean b){if(!b)throw new AssertionError();}
 static class Bitmap {boolean recycled;void recycle(){recycled=true;}}
 static class BitmapFactory {
  static int calls;static Bitmap newest;
  static Bitmap decodeFile(String path){calls++;return newest=new Bitmap();}
 }
 static class Queue {
  List<Runnable> tasks=new ArrayList<>();
  void postRunnable(Runnable r){tasks.add(r);}
  void drain(){var copy=new ArrayList<>(tasks);tasks.clear();copy.forEach(Runnable::run);}
 }
 static class Utilities {static Queue globalQueue=new Queue();}
 static class AndroidUtilities {
  static Queue ui=new Queue();static void runOnUIThread(Runnable r){ui.postRunnable(r);}
 }
 static class ApplicationLoader {static File getFilesDirFixed(){return new File("/tmp");}}
 static class FileLog {static void e(Throwable error){throw new AssertionError(error);}}
 static class Cover {Bitmap bitmap;int sets;void setImageBitmap(Bitmap b){bitmap=b;sets++;}}
 int cameraCoverGeneration;Object textureView=new Object();
 boolean opened=true,cancelled,cameraReady;Bitmap lastBitmap;
 Cover textureOverlayView=new Cover();
 LOAD
 ACCEPT
 public static void main(String[] args){
  var t=new Transitions();t.cameraCoverGeneration=1;
  t.loadLastCameraBitmapAsync(1);
  check(BitmapFactory.calls==0&&t.textureOverlayView.sets==0);
  Utilities.globalQueue.drain();
  check(BitmapFactory.calls==1&&t.lastBitmap==null);
  AndroidUtilities.ui.drain();
  check(t.lastBitmap==BitmapFactory.newest&&t.textureOverlayView.sets==1);

  t.lastBitmap=null;t.cameraCoverGeneration=2;t.loadLastCameraBitmapAsync(2);
  Utilities.globalQueue.drain();Bitmap stale=BitmapFactory.newest;
  t.cameraCoverGeneration=3;AndroidUtilities.ui.drain();
  check(stale.recycled&&t.lastBitmap==null&&t.textureOverlayView.sets==1);

  t.loadLastCameraBitmapAsync(3);Utilities.globalQueue.drain();
  Bitmap afterFrame=BitmapFactory.newest;t.cameraReady=true;
  AndroidUtilities.ui.drain();
  check(afterFrame.recycled&&t.textureOverlayView.sets==1);

  t.cameraReady=false;t.loadLastCameraBitmapAsync(3);
  Utilities.globalQueue.drain();Bitmap afterClose=BitmapFactory.newest;
  t.opened=false;AndroidUtilities.ui.drain();
  check(afterClose.recycled&&t.textureOverlayView.sets==1);

  t.opened=true;t.loadLastCameraBitmapAsync(3);
  Utilities.globalQueue.drain();Bitmap afterReplacement=BitmapFactory.newest;
  t.lastBitmap=new Bitmap();AndroidUtilities.ui.drain();
  check(afterReplacement.recycled&&t.textureOverlayView.sets==1);
 }
}'''.replace('LOAD', load).replace('ACCEPT', accept))

    def test_placeholder_and_first_frame_fade_ownership(self):
        show = method(VIEW, 'public void showCamera(boolean fromPaused)')
        self.assertNotIn('BitmapFactory.decodeFile(', show)
        self.assertLess(show.index('setImageResource(R.drawable.icplaceholder)'),
                        show.index('loadLastCameraBitmapAsync(coverGeneration)'))
        self.assertIn('final int coverGeneration = ++cameraCoverGeneration;', show)
        ready = method(VIEW, 'private void onCameraPreviewReady()')
        self.assertIn('++cameraCoverGeneration;', ready)
        self.assertIn('animatorSet.isRunning()', ready)
        opening = method(VIEW, 'public void startAnimation(boolean open, boolean fromPaused)')
        self.assertIn('openingAnimator.addListener(', opening)
        self.assertIn('fadeCameraPreviewCover();', opening)
        fade = method(VIEW, 'private void fadeCameraPreviewCover()')
        self.assertIn('textureView.isAvailable()', fade)
        self.assertIn('.alpha(0f)', fade)
        self.assertIn('.setDuration(120L)', fade)
        for signature in ('public void destroy(', 'public void togglePause()',
                          'public void send(', 'public void cancel('):
            self.assertIn('++cameraCoverGeneration;', method(VIEW, signature))
        self.assertIn('++cameraCoverGeneration;',
                      method(VIEW, 'public boolean onSurfaceTextureDestroyed('))
        self.assertIn('++cameraCoverGeneration;',
                      method(VIEW, 'protected void onDetachedFromWindow()'))


if __name__ == '__main__':
    unittest.main()
