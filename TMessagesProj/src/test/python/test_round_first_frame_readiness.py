"""No-APK checks for round CameraX first-frame and lens-result ownership."""
import unittest
from pathlib import Path

from test_recording_composer_lifecycle import method
from test_sender_infocard_transitions import run_java


ROOT = Path(__file__).resolve().parents[2] / 'main/java'
VIEW = (ROOT / 'org/telegram/ui/Components/InstantCameraView.java').read_text()
CONTROLLER = (ROOT / 'app/nimarkogram/messenger/camera/NimarkoCameraXController.java').read_text()
SESSION = (ROOT / 'app/nimarkogram/messenger/camera/NimarkoCameraXSurfaceSession.java').read_text()


class RoundFirstFrameTests(unittest.TestCase):
    def test_latched_lens_result_not_future_or_optimistic_state(self):
        readiness = method(CONTROLLER, 'public boolean isInitialLensReady(long surfaceTimestampNanos)')
        run_java('''import java.util.*;
public class Transitions {
 static class CameraXLensFrame {
  String physicalId;float zoomRatio;
  CameraXLensFrame(String id,float ratio){physicalId=id;zoomRatio=ratio;}
 }
 static class CameraXLensFrameTracker {
  static boolean isValidRatio(float r){return r>0&&!Float.isNaN(r)&&!Float.isInfinite(r);}
 }
 static class CameraXUtils {static boolean isOppoCph2791ConcurrentQuirk(){return false;}}
 static class ZoomState {
  float min;ZoomState(float min){this.min=min;}
  float getMinZoomRatio(){return min;}
 }
 static class LiveData {ZoomState state;ZoomState getValue(){return state;}}
 static class Info {LiveData data=new LiveData();LiveData getZoomState(){return data;}}
 static class Camera {Info info=new Info();Info getCameraInfo(){return info;}}
 static class Owner {
  boolean isFrontface,want=true,boundCameraReady=true;
  Object concurrentPeer;
  Camera boundCamera=new Camera();
  String expectedInitialPhysicalCameraId="wide";
  Map<Long,CameraXLensFrame> frames=new HashMap<>();
  boolean wantsInitialUltraWide(){return want;}
  CameraXLensFrame getLensFrame(long timestamp){return frames.get(timestamp);}
  READINESS
 }
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  Owner o=new Owner();o.boundCamera.info.data.state=new ZoomState(.6f);
  o.frames.put(200L,new CameraXLensFrame("wide",.6f));
  o.frames.put(100L,new CameraXLensFrame("main",1f));
  check(!o.isInitialLensReady(100L)); // future wide result cannot reveal old buffer
  check(o.isInitialLensReady(200L));
  o.expectedInitialPhysicalCameraId=null;
  check(!o.isInitialLensReady(100L)); // request/ZoomState cannot substitute
  check(o.isInitialLensReady(200L));
  o.frames.put(200L,new CameraXLensFrame(null,Float.NaN));
  check(!o.isInitialLensReady(200L));
  o.boundCameraReady=false;check(!o.isInitialLensReady(200L));
  o.isFrontface=true;check(o.isInitialLensReady(100L));
 }
}'''.replace('READINESS', readiness))
        self.assertIn('controller.isInitialLensReady(surfaceTimestampNanos)', SESSION)
        startup = method(VIEW, 'private boolean nmShouldHoldCameraXInitialWideFrame(')
        self.assertIn('session.isInitialLensReady(frameTimestamp)', startup)

    def test_first_frame_requires_current_request_and_successful_swap(self):
        listener = VIEW[VIEW.index('cameraSurface[a].setOnFrameAvailableListener('):
                        VIEW.index('createCamera(a, cameraSurface[a]', VIEW.index('cameraSurface[a].setOnFrameAvailableListener('))]
        self.assertNotIn('nmOnCameraXFrameAvailable(', listener)
        self.assertNotIn('cameraFrameAvailable[i] = true', listener)
        draw = method(VIEW, 'private void onDraw(Integer cameraId,')
        self.assertLess(draw.index('cameraSurface[0].updateTexImage();'),
                        draw.index('updatedTexImage1 && hasCurrentCameraXFrame(0)'))
        self.assertLess(draw.index('updatedTexImage1 && hasCurrentCameraXFrame(0)'),
                        draw.index('nmOnCameraXFrameAvailable(0);'))
        self.assertIn('!cameraFrameAvailable[surfaceIndex]', draw)
        swap = draw.index('eglSwapBuffers(eglDisplay, eglSurface)')
        self.assertLess(swap, draw.index('cameraReady = true;', swap))
        self.assertIn('pending.isInitialLensReady(frameTimestamp)', draw)
        self.assertIn('frameTimestamp > cameraXSingleSwitchSnapshotTimestamp', draw)
        session_message = VIEW[VIEW.index('case DO_SETSESSION_MESSAGE:'):VIEW.index('case DO_FLIP:')]
        self.assertLess(session_message.index('cameraFrameLatched[sessionSurface] = false;'),
                        session_message.index('updateSessionMatrix(newSession, sessionSurface);'))
        self.assertIn('firstCameraXSessionWithLatchedFrame', session_message)
        self.assertIn('cameraTextureAvailable = false;', method(VIEW, 'public void showCamera('))

    def test_front_first_dual_switch_waits_for_rear_frame_or_deadline(self):
        replay = method(VIEW, 'private void nmMaybeReplayCameraXSwitchAfterInitialWide()')
        run_java('''public class Transitions {
 static class AndroidUtilities {static void runOnUIThread(Runnable r){r.run();}}
 static class Button {int clicks;void performClick(){clicks++;}}
 boolean pendingCameraXSwitchAfterInitialWide, cameraXInitialWideWaitActive;
 boolean cancelled,bothCameras=true,isFrontface=true,cameraReady=true;
 Object cameraThread=new Object();Button switchCameraButton=new Button();
 boolean isCameraSessionInitiated(){return true;}
 REPLAY
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  var t=new Transitions();t.pendingCameraXSwitchAfterInitialWide=true;
  t.cameraXInitialWideWaitActive=true;t.nmMaybeReplayCameraXSwitchAfterInitialWide();
  check(t.switchCameraButton.clicks==0);
  t.cameraXInitialWideWaitActive=false;t.nmMaybeReplayCameraXSwitchAfterInitialWide();
  t.nmMaybeReplayCameraXSwitchAfterInitialWide();
  check(t.switchCameraButton.clicks==1&&!t.pendingCameraXSwitchAfterInitialWide);
  t.pendingCameraXSwitchAfterInitialWide=true;t.cancelled=true;
  t.nmMaybeReplayCameraXSwitchAfterInitialWide();
  check(t.switchCameraButton.clicks==1);
 }
}'''.replace('REPLAY', replay))
        click = method(VIEW, 'switchCameraButton.setOnClickListener(v -> {')
        self.assertLess(click.index('cameraXInitialWideWaitActive'),
                        click.index('switchCameraDrawable.setCurrentFrame(0)'))
        draw = method(VIEW, 'private void onDraw(Integer cameraId,')
        self.assertIn('nmShouldHoldCameraXInitialWideFrame(cameraFrameTimestamp[rearIndex])', draw)
        timeout = method(VIEW, 'private void nmArmCameraXInitialWideTimeout()')
        self.assertIn('nmMaybeReplayCameraXSwitchAfterInitialWide();', timeout)
        release = method(VIEW, 'private void nmReleaseCameraXInitialWideWait()')
        self.assertIn('nmMaybeReplayCameraXSwitchAfterInitialWide();', release)
        collapse = method(VIEW, 'public void onCameraXDualUnavailable()')
        self.assertIn('pendingCameraXSwitchAfterDualCollapse |= pendingCameraXSwitchAfterInitialWide', collapse)


if __name__ == '__main__':
    unittest.main()
