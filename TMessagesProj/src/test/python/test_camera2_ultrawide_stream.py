"""Device-scoped stream trial; hardware image quality needs device validation."""
from pathlib import Path
import unittest

from test_search_fallback_entrance import block
from test_sender_infocard_transitions import run_java

JAVA = Path(__file__).resolve().parents[2] / 'main/java'


class Camera2UltrawideStreamTest(unittest.TestCase):
    def test_device_scope_and_advertised_stream_only(self):
        source = (JAVA / 'org/telegram/messenger/camera/Camera2Session.java').read_text()
        helper = block(source, 'static Size chooseRoundVideoCompatibilitySize(')
        run_java('''
public class Transitions {
 static class Size {
  final int w,h; Size(int w,int h){this.w=w;this.h=h;}
  int getWidth(){return w;} int getHeight(){return h;}
 }
 HELPER
 static void check(boolean ok){if(!ok)throw new AssertionError();}
 public static void main(String[] args){
  Size square=new Size(1088,1088), wide=new Size(1920,1088);
  Size[] choices={new Size(4096,3072),square,null,new Size(1920,1080),wide};
  check(chooseRoundVideoCompatibilitySize("OPPO","CPH2791",false,true,true,square,choices)==wide);
  check(chooseRoundVideoCompatibilitySize("oppo","cph2791",false,true,true,square,choices)==wide);
  for(boolean front:new boolean[]{false,true})
   for(boolean logical:new boolean[]{false,true})
    for(boolean round:new boolean[]{false,true}){
     Size result=chooseRoundVideoCompatibilitySize("OPPO","CPH2791",front,logical,round,square,choices);
     check(result==(!front&&logical&&round?wide:square));
    }
  for(String maker:new String[]{null,"","Samsung","OnePlus"})
   check(chooseRoundVideoCompatibilitySize(maker,"CPH2791",false,true,true,square,choices)==square);
  for(String model:new String[]{null,"","CPH2792"})
   check(chooseRoundVideoCompatibilitySize("OPPO",model,false,true,true,square,choices)==square);
  for(Size selected:new Size[]{null,new Size(720,720),new Size(1440,1440),wide})
   check(chooseRoundVideoCompatibilitySize("OPPO","CPH2791",false,true,true,selected,choices)==selected);
  for(Size[] absent:new Size[][]{null,new Size[]{},new Size[]{square,null,new Size(1920,1080)}})
   check(chooseRoundVideoCompatibilitySize("OPPO","CPH2791",false,true,true,square,absent)==square);
  // Input order/objects remain intact; final encoding size is not a parameter.
  check(choices[1]==square && choices[4]==wide);
 }
}
'''.replace('HELPER', helper))

    def test_existing_crop_preserves_square_without_stretch(self):
        source = (JAVA / 'org/telegram/ui/Components/InstantCameraView.java').read_text()
        update = block(source, 'private void updateScale(int index)')
        rebuild = block(source, 'private void rebuildTextureBuffer(int index)')
        run_java('''
import java.nio.*;
public class Transitions {
 static class Size {
  int w,h; Size(int w,int h){this.w=w;this.h=h;}
  int getWidth(){return w;} int getHeight(){return h;}
 }
 static class FileLog {static void d(String s){}}
 Size[] previewSize={new Size(1920,1088)};
 int surfaceWidth=996,surfaceHeight=996,surfaceIndex=0;
 float scaleX,scaleY; FloatBuffer textureBuffer;
 FloatBuffer[] cameraTextureBuffers=new FloatBuffer[1];
 UPDATE
 REBUILD
 public static void main(String[] args){
  Transitions t=new Transitions();
  t.rebuildTextureBuffer(0);
  FloatBuffer uv=t.textureBuffer;
  // Observed ST rotates -90 and flips: x=1-v, y=1-u.
  float left=1-uv.get(5),right=1-uv.get(1);
  float width=(right-left)*1920, height=(uv.get(2)-uv.get(0))*1088;
  if(Math.abs(width-1088)>0.001 || Math.abs(height-1088)>0.001
     || Math.abs((left+right)*.5f-.5f)>0.00001 || uv.position()!=0)
   throw new AssertionError("crop "+width+"x"+height);
 }
}
'''.replace('UPDATE', update).replace('REBUILD', rebuild))

    def test_selection_precedes_session_and_request_is_not_modified(self):
        source = (JAVA / 'org/telegram/messenger/camera/Camera2Session.java').read_text()
        create = block(source, 'public static Camera2Session create(boolean front, int viewWidth, int viewHeight, boolean preferLogical, int requestedHeight, boolean noStillSurface)')
        self.assertLess(create.index('chooseRoundVideoCompatibilitySize('), create.index('new Camera2Session('))
        self.assertIn('bestSize = size;', create)
        request = block(source, 'private boolean updateCaptureRequest()')
        self.assertNotIn('chooseRoundVideoCompatibilitySize', request)
        self.assertNotIn('setDefaultBufferSize', request)
        self.assertIn('CaptureRequest.CONTROL_ZOOM_RATIO, currentZoom', request)


if __name__ == '__main__':
    unittest.main()
