"""Execute production stabilization policy/request writes; not device/HAL verification."""
from pathlib import Path
import unittest

from test_search_fallback_entrance import block
from test_sender_infocard_transitions import run_java

SOURCE = Path(__file__).resolve().parents[2] / 'main/java/org/telegram/messenger/camera/Camera2Session.java'


def run_policy(main):
    source = SOURCE.read_text()
    helpers = '\n'.join(block(source, signature) for signature in (
        'private static Integer chooseStabilizationMode(',
        'private static Integer[] chooseStabilizationModes(',
        'private void applyStabilizationModes(',
    ))
    request = block(source, 'private boolean updateCaptureRequest()')
    override = block(request[request.index('// Validate both toggles'):], 'try {')
    run_java('''
import java.util.*;
public class Transitions {
 static class CaptureRequest {
  static final int LENS_OPTICAL_STABILIZATION_MODE_OFF=0, LENS_OPTICAL_STABILIZATION_MODE_ON=1;
  static final int CONTROL_VIDEO_STABILIZATION_MODE_OFF=0, CONTROL_VIDEO_STABILIZATION_MODE_ON=1;
  static class Key<T> {}
  static final Key<Integer> LENS_OPTICAL_STABILIZATION_MODE=new Key<>();
  static final Key<Integer> CONTROL_VIDEO_STABILIZATION_MODE=new Key<>();
  static class Builder {
   Integer optical,video; int writes,attempts; Key<?> fail;
   CameraCharacteristics cc;
   Integer get(Key<Integer> key){return key==LENS_OPTICAL_STABILIZATION_MODE?optical:video;}
   void set(Key<Integer> key,Integer value){
    attempts++;
    check(cc.keys.contains(key),"unadvertised request key");
    check(has(key==LENS_OPTICAL_STABILIZATION_MODE?cc.optical:cc.video,value),"unsupported mode");
    if(key==fail)throw new IllegalArgumentException("simulated HAL rejection");
    writes++;
    if(key==LENS_OPTICAL_STABILIZATION_MODE)optical=value;else video=value;
   }
  }
 }
 static class CameraCharacteristics {
  static final Object LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION=new Object();
  static final Object CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES=new Object();
  int[] optical,video; boolean failRead;
  List<CaptureRequest.Key<?>> keys=Arrays.asList(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
    CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE);
  List<CaptureRequest.Key<?>> getAvailableCaptureRequestKeys(){return keys;}
  int[] get(Object key){
   if(failRead)throw new IllegalArgumentException("unreadable metadata");
   return key==LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION?optical:video;
  }
 }
 static class app {static class nimarkogram {static class messenger {static class NimarkoConfig {
  static boolean cameraOpticalStabilization,cameraStabilisation;
 }}}}
 CameraCharacteristics cameraCharacteristics;
 CaptureRequest.Builder captureRequestBuilder;
 HELPERS
 void apply(boolean optical,boolean video){
  app.nimarkogram.messenger.NimarkoConfig.cameraOpticalStabilization=optical;
  app.nimarkogram.messenger.NimarkoConfig.cameraStabilisation=video;
  OVERRIDE catch(Throwable ignored){}
 }
 static Transitions setup(int[] optical,int[] video,Integer templateOptical,Integer templateVideo){
  Transitions t=new Transitions();t.cameraCharacteristics=new CameraCharacteristics();
  t.cameraCharacteristics.optical=optical;t.cameraCharacteristics.video=video;
  t.captureRequestBuilder=new CaptureRequest.Builder();
  t.captureRequestBuilder.cc=t.cameraCharacteristics;
  t.captureRequestBuilder.optical=templateOptical;t.captureRequestBuilder.video=templateVideo;
  return t;
 }
 static boolean has(int[] modes,Integer value){
  if(modes!=null&&value!=null)for(int mode:modes)if(mode==value)return true;
  return false;
 }
 static boolean active(Integer mode){return mode!=null&&mode!=0;}
 static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
 static void pair(Transitions t,Integer optical,Integer video){
  check(Objects.equals(t.captureRequestBuilder.optical,optical),"optical expected "+optical);
  check(Objects.equals(t.captureRequestBuilder.video,video),"video expected "+video);
 }
 public static void main(String[] args){MAIN}
}
'''.replace('HELPERS', helpers).replace('OVERRIDE', override).replace('MAIN', main))


class Camera2StabilizationCapabilityTest(unittest.TestCase):
    def test_single_mode_policy_null_empty_off_on_and_unknown_modes(self):
        run_policy('''
 int[][] arrays={null,new int[]{},new int[]{0},new int[]{1},new int[]{0,1},
   new int[]{1,0},new int[]{0,1,2},new int[]{0,2},new int[]{2},new int[]{99}};
 for(int[] modes:arrays)for(boolean enabled:new boolean[]{false,true}){
  Integer expected=enabled&&has(modes,1)?Integer.valueOf(1):has(modes,0)?Integer.valueOf(0):null;
  Integer actual=chooseStabilizationMode(modes,enabled,1,0);
  check(Objects.equals(actual,expected),"single-mode policy "+Arrays.toString(modes));
 }
 // Policy uses supplied constants rather than assuming any numeric mode is ON.
 check(chooseStabilizationMode(new int[]{7,9},true,9,7)==9,"custom ON");
 check(chooseStabilizationMode(new int[]{7},true,9,7)==7,"custom OFF fallback");
''')

    def test_trace_regression_and_selected_mode_mutual_exclusion(self):
        run_policy('''
 int[] both={0,1};int[] off={0};int[] preview={0,1,2};
 for(boolean software:new boolean[]{false,true}){
  // Recorded TEMPLATE_RECORD values: OIS OFF-only, video supports ON/preview.
  Transitions t=setup(off,preview,0,0);
  for(int rebuild=0;rebuild<20;rebuild++){
   t.apply(true,software);pair(t,0,software?1:0);
  }
 }
 for(boolean optical:new boolean[]{false,true})for(boolean software:new boolean[]{false,true}){
  Transitions t=setup(both,both,0,0);t.apply(optical,software);
  pair(t,optical?1:0,!optical&&software?1:0);
 }
 Transitions t=setup(both,new int[]{0,2},0,2);t.apply(false,true);pair(t,0,0);
 // Unknown optical capability preserves template OIS, not the user's preference.
 t=setup(null,both,0,0);t.apply(true,true);pair(t,0,1);
 t=setup(null,both,1,0);t.apply(false,true);pair(t,1,0);
 t=setup(null,both,null,0);t.apply(true,true);pair(t,null,0);
 // Unknown video capability preserves ON / preview stabilization; don't add OIS.
 for(Integer template:new Integer[]{null,1,2}){
  t=setup(both,null,0,template);t.apply(true,true);pair(t,0,template);
 }
 t=setup(both,null,0,0);t.apply(true,true);pair(t,1,0);
 // Null/empty capabilities must never write even OFF or erase a template value.
 for(int[] absent:new int[][]{null,new int[]{}})
  for(Integer o:new Integer[]{null,0,1})for(Integer v:new Integer[]{null,0,1,2}){
   t=setup(absent,absent,o,v);t.apply(true,true);pair(t,o,v);
   check(t.captureRequestBuilder.writes==0,"unknown capability writes");
  }
''')

    def test_cross_product_and_request_key_guards(self):
        run_policy('''
 int[][] arrays={null,new int[]{},new int[]{0},new int[]{1},new int[]{0,1},
   new int[]{0,2},new int[]{2},new int[]{99}};
 for(int[] optical:arrays)for(int[] video:arrays)
  for(Integer to:new Integer[]{null,0,1})for(Integer tv:new Integer[]{null,0,1,2})
  for(boolean wantO:new boolean[]{false,true})for(boolean wantV:new boolean[]{false,true})
  for(int keyMask=0;keyMask<4;keyMask++){
   Transitions t=setup(optical,video,to,tv);
   t.cameraCharacteristics.keys=new ArrayList<>();
   if((keyMask&1)!=0)t.cameraCharacteristics.keys.add(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE);
   if((keyMask&2)!=0)t.cameraCharacteristics.keys.add(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE);
   int[] writableO=(keyMask&1)!=0?optical:null,writableV=(keyMask&2)!=0?video:null;
   Integer[] chosen=chooseStabilizationModes(writableO,writableV,to,tv,wantO,wantV);
   for(int i=0;i<2;i++)if(chosen[i]!=null)
    check(has(i==0?writableO:writableV,chosen[i]),"unadvertised choice");
   t.apply(wantO,wantV);
   pair(t,chosen[0]!=null?chosen[0]:to,chosen[1]!=null?chosen[1]:tv);
   check(t.captureRequestBuilder.attempts==t.captureRequestBuilder.writes,"rejected write");
   // Do not create dual stabilization from a non-conflicting template.
   // Already-conflicting immutable templates cannot be repaired without capability data.
   if(!(active(to)&&active(tv)))
    check(!(active(t.captureRequestBuilder.optical)&&active(t.captureRequestBuilder.video)),"dual stabilization");
  }
 Transitions t=setup(new int[]{0,1},new int[]{0,1},0,0);
 t.cameraCharacteristics.keys=null;t.apply(true,true);pair(t,0,0);
 check(t.captureRequestBuilder.writes==0,"unknown request keys");
 t.cameraCharacteristics=null;t.apply(true,true);pair(t,0,0);
 check(t.captureRequestBuilder.writes==0,"unknown camera");
''')

    def test_failed_off_write_does_not_enable_the_other_path(self):
        run_policy('''
 int[] both={0,1};
 Transitions t=setup(both,both,1,0);
 t.captureRequestBuilder.fail=CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE;
 t.apply(false,true);pair(t,1,0);
 check(t.captureRequestBuilder.attempts==1,"continued after OIS OFF failed");
 t=setup(both,both,0,2);
 t.captureRequestBuilder.fail=CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE;
 t.apply(true,true);pair(t,0,2);
 check(t.captureRequestBuilder.attempts==1,"continued after video OFF failed");
 t=setup(both,both,0,0);t.cameraCharacteristics.failRead=true;
 t.apply(true,true);pair(t,0,0);
 check(t.captureRequestBuilder.attempts==0,"writes after capability read failed");
''')

    def test_live_request_has_no_unconditional_stabilization_writes(self):
        request = block(SOURCE.read_text(), 'private boolean updateCaptureRequest()')
        self.assertEqual(request.count('applyStabilizationModes('), 1)
        self.assertNotIn('set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE', request)
        self.assertNotIn('set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE', request)
        self.assertLess(request.index('createCaptureRequest(template)'), request.index('applyStabilizationModes('))
        self.assertLess(request.index('applyStabilizationModes('), request.index('setRepeatingRequest('))


if __name__ == '__main__':
    unittest.main()
