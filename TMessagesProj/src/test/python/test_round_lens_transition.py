"""Production rear-lens state machine and UI coordination on JVM stubs, not camera hardware."""
import shutil
import unittest
from pathlib import Path
from test_recording_composer_lifecycle import method
from test_sender_infocard_transitions import run_java

ROOT = Path(__file__).resolve().parents[2] / 'main/java'
VIEW = (ROOT / 'org/telegram/ui/Components/InstantCameraView.java').read_text()
STATE = (ROOT / 'app/nimarkogram/messenger/camera/CameraXRoundLensTransition.java').read_text()
STATE = STATE[STATE.index('public final class'):].replace(
    'public final class CameraXRoundLensTransition', 'static final class CameraXRoundLensTransition', 1)


@unittest.skipUnless(shutil.which('javac'), 'JDK required')
class RoundLensTests(unittest.TestCase):
    def test_frame_readiness_and_bounded_vendor_fallback(self):
        run_java('''public class Transitions {
 STATE
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  check(CameraXRoundLensTransition.crossesWideBoundary(.6f,1f,.6f));
  check(CameraXRoundLensTransition.crossesWideBoundary(2f,.9f,.6f));
  check(!CameraXRoundLensTransition.crossesWideBoundary(1f,3f,.6f));
  check(!CameraXRoundLensTransition.crossesWideBoundary(.6f,.8f,.6f));
  check(!CameraXRoundLensTransition.crossesWideBoundary(.6f,1f,1f));
  check(!CameraXRoundLensTransition.crossesWideBoundary(Float.NaN,1f,.6f));
  var s=new CameraXRoundLensTransition();
  check(!s.onFrame(200,200,1f,"main",1000)); // no command submitted
  s.submit(100,1.1f,"wide",0);
  check(!s.onFrame(100,100,1.1f,"main",400)); // same cached texture
  check(!s.onFrame(150,200,1.1f,"main",50)); // metadata for a future buffer
  check(!s.onFrame(150,100,1.1f,"main",50)); // old graph/frame
  check(!s.onFrame(150,150,.6f,"wide",50)); // queued old lens
  check(!s.onFrame(160,160,1.1f,"wide",80)); // zoom accepted, sensor unchanged
  check(s.onFrame(170,170,1.1f,"main",90));
  check(!s.onFrame(180,180,1.2f,"main",100)); // reveal exactly once
  s=new CameraXRoundLensTransition();s.submit(200,.6f,"main",1000);
  check(!s.onFrame(230,230,1f,"main",1100));
  check(s.onFrame(240,240,.6f,"wide",1150)); // reverse direction
  s=new CameraXRoundLensTransition();s.submit(10,1.1f,null,0);
  check(s.onFrame(20,20,1.1f,null,50)); // valid ratio with no physical IDs
  s=new CameraXRoundLensTransition();s.submit(10,1.1f,"wide",0);
  check(!s.onFrame(20,0,Float.NaN,null,349));
  check(!s.onFrame(10,0,Float.NaN,null,500)); // fallback still needs fresh frame
  check(s.onFrame(21,0,Float.NaN,null,350));
  s=new CameraXRoundLensTransition();s.submit(10,1.1f,"wide",0);
  check(s.onFrame(20,20,1.1f,"wide",350)); // HAL elects not to swap sensor
 }
}'''.replace('STATE', STATE))

    def test_capture_order_coalescing_reversal_failure_and_lifecycle(self):
        bodies = '\n'.join(method(VIEW, signature) for signature in (
            'private void requestRoundCameraXZoom(',
            'private void completeCameraXRearLensTransition(',
            'private void cancelCameraXRearLensTransition(',
            'private float currentRoundZoomRatio()',
        ))
        run_java('''import java.util.*;
public class Transitions {
 STATE
 static void check(boolean b){if(!b)throw new AssertionError();}
 static List<String> events=new ArrayList<>();
 static class SystemClock {static long elapsedRealtime(){return 100;}}
 static class AndroidUtilities {
  static List<Runnable> timers=new ArrayList<>();
  static void runOnUIThread(Runnable r,long d){timers.add(r);}
  static void cancelRunOnUIThread(Runnable r){timers.remove(r);}
 }
 static class NimarkoCameraXSurfaceSession {
  boolean front;float ratio=.6f,min=.6f;
  boolean isFrontFacing(){return front;}float getZoomRatio(){return ratio;}
  float getZoom(){return ratio;}float getMinZoomRatio(){return min;}
  String getActivePhysicalCameraId(){return "wide";}
  CameraXLensFrame getLensFrame(long timestamp){return new CameraXLensFrame("wide");}
  void setZoomRatio(float r){ratio=r;events.add("zoom:"+r);}
 }
 static class CameraXLensFrame {
  final String physicalId;
  CameraXLensFrame(String physicalId){this.physicalId=physicalId;}
 }
 static class Helper {
  NimarkoCameraXSurfaceSession session=new NimarkoCameraXSurfaceSession();
  NimarkoCameraXSurfaceSession getCurrentSession(){return session;}
  float getZoomRatio(){return session.ratio;}
 }
 class CameraGLThread {
  Runnable callback;int captures;
  void captureCameraXSingleSwitchSnapshot(Runnable r){callback=r;captures++;events.add("capture");}
  void requestRender(boolean a,boolean b){}
  void finish(boolean ok){cameraXSingleSwitchSnapshot=ok?42:0;callback.run();}
 }
 Helper videoMessagesHelper=new Helper();CameraGLThread cameraThread=new CameraGLThread();
 NimarkoCameraXSurfaceSession cameraXRearLensSession,camera2SessionCurrent;
 CameraXRoundLensTransition cameraXRearLensTransition;
 boolean cancelled,flipAnimationInProgress,cameraXSingleSwitchAwaitingBind,useCamera2;
 boolean cameraReady=true,recording=true,useCameraX=true,active;
 float cameraXPendingZoomRatio=Float.NaN,legacyZoom;
 Runnable cameraXRearLensTimeout;int cameraXSingleSwitchSnapshot;
 long cameraXSingleSwitchSnapshotTimestamp=100;
 void clearCameraXVideoTransition(){active=false;}
 void startCameraXVideoTransition(){active=true;events.add("animate");}
 BODIES
 public static void main(String[] args){
  var t=new Transitions();t.requestRoundCameraXZoom(1.1f);
  check(events.equals(List.of("capture"))&&t.videoMessagesHelper.session.ratio==.6f);
  t.requestRoundCameraXZoom(1.2f);t.requestRoundCameraXZoom(1.4f);
  check(t.cameraThread.captures==1&&t.currentRoundZoomRatio()==1.4f);
  t.cameraThread.finish(true);
  check(events.equals(List.of("capture","animate","zoom:1.4")));
  t.requestRoundCameraXZoom(.8f);t.requestRoundCameraXZoom(.7f);
  check(t.videoMessagesHelper.session.ratio==1.4f); // no overlapping handoffs
  t.completeCameraXRearLensTransition(true);
  check(t.cameraThread.captures==2);t.cameraThread.finish(true);
  check(t.videoMessagesHelper.session.ratio==.7f);
  t.completeCameraXRearLensTransition(true);
  check(t.cameraXRearLensTransition==null&&!t.flipAnimationInProgress);

  t=new Transitions();t.requestRoundCameraXZoom(1.1f);t.requestRoundCameraXZoom(.8f);
  t.cameraThread.finish(true); // reversal before snapshot: no blur
  check(!t.active&&t.cameraXRearLensTransition==null&&t.videoMessagesHelper.session.ratio==.8f);
  t=new Transitions();t.requestRoundCameraXZoom(1.1f);t.cameraThread.finish(false);
  check(!t.active&&t.videoMessagesHelper.session.ratio==1.1f&&!t.flipAnimationInProgress);
  t=new Transitions();t.requestRoundCameraXZoom(1.1f);var old=t.cameraThread.callback;
  t.cancelCameraXRearLensTransition();old.run();
  check(t.videoMessagesHelper.session.ratio==.6f&&!t.active);
  t=new Transitions();t.requestRoundCameraXZoom(1.1f);
  t.videoMessagesHelper.session=new NimarkoCameraXSurfaceSession();t.cameraThread.finish(true);
  check(t.videoMessagesHelper.session.ratio==.6f&&t.cameraXRearLensTransition==null);
  t=new Transitions();t.requestRoundCameraXZoom(1.1f);t.cameraXRearLensTimeout.run();
  check(t.videoMessagesHelper.session.ratio==1.1f&&t.cameraThread.captures==1);
  check(t.cameraXRearLensTransition==null); // no endless recapture on a stalled GL thread
  t=new Transitions();t.videoMessagesHelper.session.front=true;t.requestRoundCameraXZoom(2f);
  check(t.cameraThread.captures==0&&t.videoMessagesHelper.session.ratio==2f);
  t=new Transitions();t.requestRoundCameraXZoom(.8f);
  check(t.cameraThread.captures==0); // ordinary digital zoom stays live
  t.requestRoundCameraXZoom(Float.NaN);check(t.videoMessagesHelper.session.ratio==.8f);

  t=new Transitions();t.requestRoundCameraXZoom(1.1f);t.cameraThread.finish(true);
  t.requestRoundCameraXZoom(1.8f);check(t.videoMessagesHelper.session.ratio==1.8f);
  t.requestRoundCameraXZoom(.9f); // opposite side is coalesced, not sent early
  check(t.videoMessagesHelper.session.ratio==1.8f);
  t.requestRoundCameraXZoom(2.2f);t.completeCameraXRearLensTransition(true);
  check(t.videoMessagesHelper.session.ratio==2.2f&&t.cameraThread.captures==1);
  // Thousands of 240 Hz gesture changes, including reversals before the GL
  // capture callback. At most one capture is live; final intent always wins.
  Random random=new Random(9071);t=new Transitions();
  for(int cycle=0;cycle<100;cycle++){
   float last=t.videoMessagesHelper.session.ratio;
   for(int i=0;i<100;i++){
    last=.6f+random.nextFloat()*3f;t.requestRoundCameraXZoom(last);
    if(i%8==0&&t.cameraXRearLensTransition!=null&&!t.active)t.cameraThread.finish(true);
    if(i%45==0&&t.active)t.completeCameraXRearLensTransition(true);
   }
   if(t.cameraXRearLensTransition!=null&&!t.active)t.cameraThread.finish(true);
   if(t.active)t.completeCameraXRearLensTransition(true);
   if(t.cameraXRearLensTransition!=null){t.cameraThread.finish(true);t.completeCameraXRearLensTransition(true);}
   check(t.cameraXRearLensTransition==null&&t.videoMessagesHelper.session.ratio==last);
  }
 }
}'''.replace('STATE', STATE).replace('BODIES', bodies))

    def test_lifecycle_and_all_round_zoom_entrypoints(self):
        for signature in ('public void destroy(', 'public void cancel(', 'public void send('):
            self.assertIn('cancelCameraXVideoTransitions();', method(VIEW, signature))
        self.assertIn('cancelCameraXRearLensTransition();',
                      method(VIEW, 'private void cancelCameraXVideoTransitions()'))
        for signature in ('public void onCameraXAttemptStarting(',
                          'public void onCameraXDualUnavailable(', 'public void onCameraXTransitionStarting('):
            self.assertIn('cancelCameraXRearLensTransition();', method(VIEW, signature))
        self.assertNotIn('videoMessagesHelper.setZoomRatio(', VIEW)
        self.assertIn('currentRoundZoomRatio()', method(VIEW, 'public void finishZoom()'))
        readiness = method(VIEW, 'private void onCameraXRearLensFrame(')
        self.assertIn('session.getLensFrame(timestamp)', readiness)
        self.assertNotIn('isInitialLensReady', readiness)
