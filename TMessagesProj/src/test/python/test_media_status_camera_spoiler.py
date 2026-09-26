"""Geometry, defaults, and spoiler controls must agree with their interaction state."""
from pathlib import Path
import unittest
from test_search_fallback_entrance import block
from test_sender_infocard_transitions import run_java

JAVA = Path(__file__).resolve().parents[2] / 'main/java'
CELL = JAVA / 'org/telegram/ui/Cells/ChatMessageCell.java'


class MediaStatusCameraSpoilerTest(unittest.TestCase):
    def test_pending_status_tracks_time_not_views_width(self):
        source = CELL.read_text()
        run_java('''
public class Transitions {
 static int dp(int n){return n;}
 GEOMETRY
 public static void main(String[] args){
  for(float timeLeft:new float[]{50,120,333.5f}){
   for(int width:new int[]{12,14,24}){
    if(pendingStatusLeft(timeLeft,width)+width != timeLeft-4)throw new AssertionError();
   }
  }
 }
}
'''.replace('GEOMETRY', block(source, 'private static float pendingStatusLeft(')))
        draw = block(source, 'private void drawTimeInternal(')
        calls = [line for line in draw.splitlines() if 'drawClockOrErrorLayout(' in line]
        self.assertEqual(len(calls), 6)
        self.assertTrue(all('timeTitleTimeX + additionalX + timeLayout.getLineLeft(0)' in line for line in calls))

    def test_stock_camera_default_preserves_existing_selection(self):
        source = (JAVA / 'app/nimarkogram/messenger/NimarkoConfig.java').read_text()
        init = block(source, 'private static int initCameraType()')
        run_java('''
public class Transitions {
 static final int TELEGRAM_CAMERA=0;
 static Integer stored;
 static class Prefs {int getInt(String key,int fallback){return stored==null?fallback:stored;}}
 static Prefs getPreferences(){return new Prefs();}
 INIT
 public static void main(String[] args){
  if(initCameraType()!=0)throw new AssertionError();
  for(int i=0;i<4;i++){stored=i;if(initCameraType()!=i)throw new AssertionError();}
 }
}
'''.replace('INIT', init))
        self.assertNotIn('cameraTypeMigratedToV20', source)

    def test_spoiler_controls_are_reveal_first_but_sending_is_cancellable(self):
        source = CELL.read_text()
        run_java('''
public class Transitions {
 static class Message {
  boolean spoiler=true,isMediaSpoilersRevealed,secret,sending,editing;
  boolean hasMediaSpoilers(){return spoiler;} boolean needDrawBluredPreview(){return secret;}
  boolean isSending(){return sending;} boolean isEditing(){return editing;}
 }
 Message currentMessageObject=new Message();float mediaSpoilerRevealProgress;
 BLOCKING
 ALPHA
 static void check(boolean value){if(!value)throw new AssertionError();}
 public static void main(String[] args){
  Transitions v=new Transitions();check(v.isMediaSpoilerBlockingControls());check(v.mediaSpoilerControlsAlpha()==0);
  v.mediaSpoilerRevealProgress=.5f;check(v.mediaSpoilerControlsAlpha()==.5f);
  v.currentMessageObject.isMediaSpoilersRevealed=true;check(v.mediaSpoilerControlsAlpha()==1);
  v.currentMessageObject.isMediaSpoilersRevealed=false;v.currentMessageObject.sending=true;check(!v.isMediaSpoilerBlockingControls());
  v.currentMessageObject.sending=false;v.currentMessageObject.editing=true;check(!v.isMediaSpoilerBlockingControls());
  v.currentMessageObject.editing=false;v.currentMessageObject.secret=true;check(!v.isMediaSpoilerBlockingControls());
  v.currentMessageObject=null;check(v.mediaSpoilerControlsAlpha()==1);
 }
}
'''.replace('BLOCKING', block(source, 'private boolean isMediaSpoilerBlockingControls()'))
   .replace('ALPHA', block(source, 'private float mediaSpoilerControlsAlpha()')))
        touch = block(source, 'public boolean checkPhotoImageMotionEvent(')
        self.assertIn('boolean allowClickButtons = !isMediaSpoilerBlockingControls();', touch)
        self.assertIn('if (area2 && allowClickButtons)', touch)
        self.assertIn('if (isMediaSpoilerBlockingControls())', touch)
        self.assertNotIn('showSpoilerLoading', source)
        radial = block(source, 'protected void drawRadialProgress(')
        self.assertIn('radialProgress.setOverrideAlpha(previousAlpha * spoilerAlpha)', radial)
        self.assertIn('radialProgress.setOverrideAlpha(previousAlpha)', radial)


if __name__ == '__main__':
    unittest.main()
