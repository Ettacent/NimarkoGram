"""Execute extracted production lifecycle methods with controllable JVM queues."""
import shutil
import unittest

from test_round_backend_lifecycle import JAVA, method, run_java

CAMERA = (JAVA / 'app/nimarkogram/messenger/camera/NimarkoCameraXView.java').read_text()
SERVICE = (JAVA / 'org/telegram/messenger/MusicPlayerService.java').read_text()


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'JDK required')
class AttachFinalizeTests(unittest.TestCase):
    def test_finalize_ownership_and_cancellation(self):
        body = '\n'.join(method(CAMERA, signature) for signature in (
            'public void destroyCamera()', 'public void stopVideoRecording(',
            'private void onRecordingFinalized(', 'private void completeRecordingSession(',
            'private void finishDeferredDestroy()', 'private boolean isRecordingCaptureActive()',
        ))
        run_java('''import java.io.*;import java.util.*;
public class RoundHarness {
 interface VideoSavedCallback {void onFinishVideoRecording(String thumb,long duration);}
 static class Recording {int stops;void stop(){stops++;}}
 static class RecordingSession {
  File file;VideoSavedCallback callback;Recording recording=new Recording();
  boolean abandoned,stopRequested,finalizing;
  RecordingSession(File f,VideoSavedCallback c){file=f;callback=c;}
 }
 static class VideoRecordEvent {
  static class Finalize {
   boolean error;Finalize(boolean e){error=e;}boolean hasError(){return error;}
   Finalize getRecordingStats(){return this;}long getRecordedDurationNanos(){return 2000000000L;}
  }
 }
 static class Queue {ArrayDeque<Runnable> tasks=new ArrayDeque<>();
  void postRunnable(Runnable r){tasks.add(r);}void drain(){while(!tasks.isEmpty())tasks.remove().run();}}
 static class Utilities {static Queue globalQueue=new Queue();}
 static class AndroidUtilities {static Queue ui=new Queue();static void runOnUIThread(Runnable r){ui.postRunnable(r);}}
 static class FileLog {static void e(Throwable t){}static void d(String s){}}
 RecordingSession recordingSession;boolean destroyAfterRecordingFinalizes,streamingEnabled=true,initied=true;
 Object camera=new Object();int teardowns,unbinds,delivered;String lastThumb;
 void teardownCamera(){teardowns++;camera=null;}
 void unbindOwnedUseCases(){unbinds++;}
 long readVideoDuration(File f){return 2000;}
 String generateVideoThumb(File f){try {
  File thumb=new File(f.getPath()+".jpg");thumb.createNewFile();lastThumb=thumb.getPath();return lastThumb;
 }catch(IOException e){throw new RuntimeException(e);}}
 BODY
 static void check(boolean b){if(!b)throw new AssertionError();}
 static File output()throws Exception{File f=File.createTempFile("cx-finalize-",".mp4");
  f.deleteOnExit();try(FileOutputStream o=new FileOutputStream(f)){o.write(1);}return f;}
 RecordingSession begin()throws Exception{
  recordingSession=new RecordingSession(output(),(thumb,d)->{check(d==2000);delivered++;});return recordingSession;}
 static void flush(){Utilities.globalQueue.drain();AndroidUtilities.ui.drain();}
 public static void main(String[] args)throws Exception{
  // Destruction before Finalize waits for CameraX, not for thumbnail work.
  RoundHarness h=new RoundHarness();RecordingSession s=h.begin();h.destroyCamera();
  check(h.teardowns==0&&s.recording.stops==1&&h.isRecordingCaptureActive());
  h.onRecordingFinalized(s,new VideoRecordEvent.Finalize(true));
  check(h.teardowns==1&&!h.isRecordingCaptureActive()&&h.delivered==0&&s.file.exists());
  // Duplicate Finalize cannot queue another result. Old delivery cannot tear down a replacement.
  h.onRecordingFinalized(s,new VideoRecordEvent.Finalize(false));
  RecordingSession replacement=new RecordingSession(output(),null);h.recordingSession=replacement;
  h.destroyAfterRecordingFinalizes=true;flush();
  check(h.teardowns==1&&h.recordingSession==replacement&&h.destroyAfterRecordingFinalizes);
  check(h.delivered==1&&s.file.exists());new File(h.lastThumb).delete();
  // Destruction after Finalize also releases immediately; valid output survives.
  h=new RoundHarness();s=h.begin();h.onRecordingFinalized(s,new VideoRecordEvent.Finalize(false));
  h.destroyCamera();check(h.teardowns==1);flush();check(h.delivered==1&&s.file.exists());
  new File(h.lastThumb).delete();
  // Cancel while thumbnail work is pending is sticky, including a later save-stop.
  h=new RoundHarness();s=h.begin();h.onRecordingFinalized(s,new VideoRecordEvent.Finalize(false));
  Utilities.globalQueue.drain();h.stopVideoRecording(true);h.stopVideoRecording(false);
  AndroidUtilities.ui.drain();check(h.delivered==0&&!s.file.exists()&&!new File(h.lastThumb).exists());
  check(h.recordingSession==null);
  // Pre-Finalize cancellation and hidden-stream teardown require no thumbnail.
  h=new RoundHarness();s=h.begin();h.streamingEnabled=false;h.stopVideoRecording(true);
  h.onRecordingFinalized(s,new VideoRecordEvent.Finalize(false));
  check(h.unbinds==1&&h.delivered==0&&!s.file.exists()&&Utilities.globalQueue.tasks.isEmpty());
 }
}'''.replace('BODY', body))

    def test_hidden_stream_and_rebind_use_capture_not_thumbnail_state(self):
        streaming = method(CAMERA, 'public void setStreamingEnabled(')
        self.assertEqual(streaming.count('!isRecordingCaptureActive()'), 2)
        binding = method(CAMERA, 'private boolean bindUseCases(')
        self.assertIn('isRecordingCaptureActive()', binding)
        completion = method(CAMERA, 'private void completeRecordingSession(')
        self.assertNotIn('finishDeferredDestroy', completion)
        self.assertIn('recordingSession == session', completion)

    def test_media_session_stop_matches_notification_cleanup(self):
        stop = method(SERVICE, 'public void onStop()')
        self.assertIn('MediaController.getInstance().cleanupPlayer(true, true);',
                      method(SERVICE, 'public int onStartCommand('))
        run_java('''public class RoundHarness {
 static class MediaController {
  static MediaController instance=new MediaController();int calls;
  static MediaController getInstance(){return instance;}
  void cleanupPlayer(boolean notify,boolean stopService){
   if(!notify||!stopService)throw new AssertionError();calls++;
  }
 }
 BODY
 public static void main(String[] args){new RoundHarness().onStop();
  if(MediaController.instance.calls!=1)throw new AssertionError();}
}'''.replace('BODY', stop))
