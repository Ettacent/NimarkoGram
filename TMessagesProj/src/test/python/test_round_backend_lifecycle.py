"""Round recorder merge regressions: production methods on JVM stubs, no APK build."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[4]
JAVA = ROOT / "TMessagesProj/src/main/java"
BASE = (JAVA / "org/telegram/ui/Components/InstantCameraViewBase.java").read_text()
VIEW = (JAVA / "org/telegram/ui/Components/InstantCameraView.java").read_text()


def method(source, signature):
    start = source.index(signature)
    opening = source.index("{", start)
    end, depth = opening + 1, 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]


def run_java(code):
    with tempfile.TemporaryDirectory(prefix="round-backend-regression-") as folder:
        path = Path(folder) / "RoundHarness.java"
        path.write_text(code)
        for command in (["javac", str(path)], ["java", "-cp", folder, "RoundHarness"]):
            result = subprocess.run(command, capture_output=True, text=True, timeout=30)
            if result.returncode:
                raise AssertionError(result.stdout + result.stderr)


class RoundBackendLifecycleTests(unittest.TestCase):
    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "JDK required")
    def test_factory_and_query_preserve_backend_and_saved_preference(self):
        methods = "\n".join(method(BASE, signature) for signature in (
            "public static InstantCameraViewBase create(",
            "public static void setUseCamera2Implementation(",
            "public static boolean isUsingCamera2Implementation(",
        ))
        run_java('''public class RoundHarness {
 static class Context {}
 static class Theme {static class ResourcesProvider {}}
 static class NimarkoConfig {static final int CAMERA_X=1;static int cameraType;}
 static class Setting {boolean value;boolean get(){return value;}void set(boolean v){value=v;}}
 static class SharedSettings {static Setting roundVideoCamera2Enabled=new Setting();}
 static class InstantCameraViewBase {}
 static class InstantCameraView extends InstantCameraViewBase {
  interface Delegate {}
  InstantCameraView(Context c,Delegate d,Theme.ResourcesProvider r,boolean n){}
 }
 static class InstantCameraView2 extends InstantCameraViewBase {
  InstantCameraView2(Context c,InstantCameraView.Delegate d,Theme.ResourcesProvider r,boolean n){}
 }
 METHODS
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  for(int backend=0;backend<4;backend++)for(boolean flag:new boolean[]{false,true}){
   NimarkoConfig.cameraType=backend;setUseCamera2Implementation(flag);
   boolean expected=backend!=NimarkoConfig.CAMERA_X&&flag;
   check(isUsingCamera2Implementation()==expected);
   check((create(null,null,null,true) instanceof InstantCameraView2)==expected);
   check(SharedSettings.roundVideoCamera2Enabled.get()==flag);
   check(NimarkoConfig.cameraType==backend);
  }
  NimarkoConfig.cameraType=1;setUseCamera2Implementation(true);
  check(!isUsingCamera2Implementation());
  NimarkoConfig.cameraType=2;check(isUsingCamera2Implementation());
  NimarkoConfig.cameraType=1;setUseCamera2Implementation(false);
  NimarkoConfig.cameraType=2;check(!isUsingCamera2Implementation());
 }
}'''.replace("METHODS", methods))

    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "JDK required")
    def test_heavy_operation_owner_duplicate_stale_and_error_stops(self):
        body = method(VIEW, "private void setHeavyOperationsStopped(boolean stopped)")
        run_java('''import java.util.*;
public class RoundHarness {
 VideoRecorder heavyOperationsOwner;
 static class AndroidUtilities {
  static ArrayList<Runnable> queue=new ArrayList<>();
  static void runOnUIThread(Runnable r){queue.add(r);}
  static void drain(){while(!queue.isEmpty())queue.remove(0).run();}
 }
 static class NotificationCenter {
  static final int stopAllHeavyOperations=1,startAllHeavyOperations=2;
  static NotificationCenter instance=new NotificationCenter();
  static ArrayList<Integer> events=new ArrayList<>();
  static NotificationCenter getGlobalInstance(){return instance;}
  void postNotificationName(int event,int mask){check(mask==512);events.add(event);}
 }
 class VideoRecorder {BODY}
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  RoundHarness h=new RoundHarness();
  VideoRecorder a=h.new VideoRecorder(),b=h.new VideoRecorder();
  a.setHeavyOperationsStopped(false);AndroidUtilities.drain();
  check(NotificationCenter.events.isEmpty());
  a.setHeavyOperationsStopped(true);a.setHeavyOperationsStopped(true);
  check(h.heavyOperationsOwner==null); // ownership changes only on the UI queue
  AndroidUtilities.drain();check(NotificationCenter.events.equals(Arrays.asList(1)));
  b.setHeavyOperationsStopped(true);a.setHeavyOperationsStopped(false);
  AndroidUtilities.drain();check(h.heavyOperationsOwner==b);
  check(NotificationCenter.events.equals(Arrays.asList(1))); // old stop cannot resume new owner
  b.setHeavyOperationsStopped(false);b.setHeavyOperationsStopped(false);
  AndroidUtilities.drain();check(h.heavyOperationsOwner==null);
  check(NotificationCenter.events.equals(Arrays.asList(1,2)));
  // Startup fails before the queued UI notification has run.
  a.setHeavyOperationsStopped(true);a.setHeavyOperationsStopped(false);
  AndroidUtilities.drain();check(h.heavyOperationsOwner==null);
  check(NotificationCenter.events.equals(Arrays.asList(1,2,1,2)));
  // Ordinary stop followed immediately by a new recording stays suspended.
  a.setHeavyOperationsStopped(true);a.setHeavyOperationsStopped(false);
  b.setHeavyOperationsStopped(true);a.setHeavyOperationsStopped(false);
  AndroidUtilities.drain();check(h.heavyOperationsOwner==b);
  check(NotificationCenter.events.equals(Arrays.asList(1,2,1,2,1,2,1)));
 }
}'''.replace("BODY", body))

    def test_encoder_error_and_normal_stop_both_release_suspension(self):
        start = method(VIEW, "public void startRecording(File outputFile,")
        stop = method(VIEW, "public void stopRecording(int send,")
        finish = method(VIEW, "private void handleStopRecording(")
        self.assertIn("setHeavyOperationsStopped(true);", start)
        self.assertIn("setHeavyOperationsStopped(false);", stop)
        self.assertIn("encoder.handleStopRecording(0, null);", VIEW)
        self.assertLess(finish.index("setHeavyOperationsStopped(false);"), finish.index("final boolean runDone;"))

    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "JDK required")
    def test_audio_timestamp_failure_uses_continuing_fallback(self):
        start = VIEW.index("                        long timestamp;")
        end = VIEW.index("                        buffer.offset[a] = timestamp;", start)
        run_java('''public class RoundHarness {
 static class AudioTimestamp {static final int TIMEBASE_MONOTONIC=1;long nanoTime=123456000;}
 static class AudioRecord {
  static final int SUCCESS=0;int status;boolean fail;
  int getTimestamp(AudioTimestamp t,int base){if(fail)throw new IllegalStateException();return status;}
 }
 static class FileLog {static void e(Exception e){}}
 AudioRecord audioRecorder=new AudioRecord();AudioTimestamp audioTimestamp=new AudioTimestamp();
 boolean shouldUseTimestamp=true;long audioPresentationTimeUs=-1;
 long timestamp(){BODY return timestamp;}
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  RoundHarness h=new RoundHarness();check(h.timestamp()==123456&&h.shouldUseTimestamp);
  h.audioRecorder.status=-3;long before=System.nanoTime()/1000;long fallback=h.timestamp();
  check(fallback>=before&&!h.shouldUseTimestamp&&fallback==h.audioPresentationTimeUs);
  h.audioPresentationTimeUs+=42000;check(h.timestamp()==fallback+42000);
  h.shouldUseTimestamp=true;h.audioRecorder.fail=true;before=System.nanoTime()/1000;
  check(h.timestamp()>=before&&!h.shouldUseTimestamp);
 }
}'''.replace("BODY", VIEW[start:end]))

    def test_monotonic_clock_metadata_and_production_default_wiring(self):
        self.assertIn("recordStartTime = SystemClock.elapsedRealtime();", VIEW)
        self.assertIn("recordedTime = SystemClock.elapsedRealtime() - recordStartTime + recordPlusTime;", VIEW)
        self.assertIn("boolean shouldUseTimestamp = Build.VERSION.SDK_INT >= Build.VERSION_CODES.N;", VIEW)
        self.assertEqual(VIEW.count("resultWidth = videoEditedInfo.originalWidth = videoWidth;"), 2)
        self.assertEqual(VIEW.count("resultHeight = videoEditedInfo.originalHeight = videoHeight;"), 2)
        settings = (JAVA / "org/telegram/utils/settings/SharedSettings.java").read_text()
        self.assertIn('BooleanSetting.of("round_video_camera2_enabled", BuildConfig.DEBUG_VERSION)', settings)
        gradle = (ROOT / "TMessagesProj/build.gradle").read_text()
        build_types = method(gradle, "buildTypes {")
        for variant in ("standalone {", "release {"):
            self.assertIn('buildConfigField "boolean", "DEBUG_VERSION", "false"', method(build_types, variant))


if __name__ == "__main__":
    unittest.main()
