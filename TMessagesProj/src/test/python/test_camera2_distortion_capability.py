"""Execute the production request override; no device/HAL visual verification."""
from pathlib import Path
import unittest

from test_search_fallback_entrance import block
from test_sender_infocard_transitions import run_java

JAVA = Path(__file__).resolve().parents[2] / 'main/java'


class Camera2DistortionCapabilityTest(unittest.TestCase):
    def test_actual_request_preserves_template_when_not_supported(self):
        source = (JAVA / 'org/telegram/messenger/camera/Camera2Session.java').read_text()
        helper = block(source, 'private static Integer choosePreviewDistortionMode(')
        request = block(source, 'private boolean updateCaptureRequest()')
        start = request.index('// Match CameraX')
        override = block(request[start:], 'try {')
        run_java('''
public class Transitions {
 static class Build {
  static class VERSION {static int SDK_INT=36;}
  static class VERSION_CODES {static int P=28;}
 }
 static class CaptureRequest {
  static int DISTORTION_CORRECTION_MODE_FAST=1;
  static Object DISTORTION_CORRECTION_MODE=new Object();
  static class Builder {
   int writes; Integer value;
   void set(Object key,Integer v){writes++;value=v;}
  }
 }
 static class CameraCharacteristics {
  static Object DISTORTION_CORRECTION_AVAILABLE_MODES=new Object();
  int[] modes; int reads;
  int[] get(Object key){reads++;return modes;}
 }
 CameraCharacteristics cameraCharacteristics=new CameraCharacteristics();
 CaptureRequest.Builder captureRequestBuilder;
 HELPER
 void apply(){OVERRIDE catch(Throwable ignored){}}
 static void check(boolean v){if(!v)throw new AssertionError();}
 public static void main(String[] args){
  Transitions t=new Transitions();
  // Null is the actual CPH2791 trace. Empty, OFF-only, HQ-only and unknown
  // modes must also avoid injecting an unsupported request on other devices.
  int[][] absent={null,new int[]{},new int[]{0},new int[]{2},new int[]{99}};
  for(int[] modes:absent) for(Integer template:new Integer[]{null,0,1,2}) {
   t.cameraCharacteristics.modes=modes;
   t.captureRequestBuilder=new CaptureRequest.Builder();
   t.captureRequestBuilder.value=template;
   for(int rebuild=0;rebuild<20;rebuild++)t.apply();
   check(t.captureRequestBuilder.writes==0);
   check(t.captureRequestBuilder.value==template);
  }
  for(int[] modes:new int[][]{new int[]{0,1,2},new int[]{2,1},new int[]{1}}){
   t.cameraCharacteristics.modes=modes;
   t.captureRequestBuilder=new CaptureRequest.Builder();t.captureRequestBuilder.value=2;
   t.apply();check(t.captureRequestBuilder.writes==1&&t.captureRequestBuilder.value==1);
  }
  Build.VERSION.SDK_INT=27;t.cameraCharacteristics.reads=0;
  t.captureRequestBuilder=new CaptureRequest.Builder();t.apply();
  check(t.cameraCharacteristics.reads==0&&t.captureRequestBuilder.writes==0);
 }
}
'''.replace('HELPER', helper).replace('OVERRIDE', override))

    def test_override_is_limited_to_live_request(self):
        source = (JAVA / 'org/telegram/messenger/camera/Camera2Session.java').read_text()
        request = block(source, 'private boolean updateCaptureRequest()')
        self.assertNotIn('CaptureRequest.DISTORTION_CORRECTION_MODE_HIGH_QUALITY', request)
        self.assertIn('if (distortionMode != null)', request)
        self.assertIn('captureRequestBuilder = cameraDevice.createCaptureRequest(template)', request)
        # No stream reconfiguration, logical-lens pinning or geometry workaround.
        self.assertNotIn('setDefaultBufferSize', request)
        self.assertNotIn('setPhysicalCameraId', request)
        self.assertIn('CaptureRequest.CONTROL_ZOOM_RATIO, currentZoom', request)

    def test_camera_diagnostics_removed(self):
        source = (JAVA / 'org/telegram/messenger/camera/Camera2Session.java').read_text()
        self.assertNotIn('geometryDiagnostics', source)
        self.assertNotIn('geometryCallback', source)
        for name in ('Diagnostics', 'Probe', 'Trace'):
            self.assertFalse((JAVA / ('app/nimarkogram/messenger/camera/Camera2Geometry' + name + '.java')).exists())


if __name__ == '__main__':
    unittest.main()
