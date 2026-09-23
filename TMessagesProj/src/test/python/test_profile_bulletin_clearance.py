"""Profile bulletin must never consume the organizer's own clearance."""
import shutil
import unittest

from test_recording_composer_lifecycle import SRC, method
from test_sender_infocard_transitions import run_java


class ProfileBulletinClearanceTests(unittest.TestCase):
    @unittest.skipUnless(shutil.which('javac'), 'JDK required')
    def test_hidden_and_moving_action_rows_preserve_bottom_safe_area(self):
        source = (SRC / 'java/org/telegram/ui/ProfileActivity.java').read_text()
        source = source[source.index('Bulletin.addDelegate(this, new Bulletin.Delegate()'):]
        body = method(source, 'public int getBottomOffset(int tag)')
        java = r'''
public class Transitions {
 static float density;
 static int dp(float x){return (int)Math.ceil(x*density);}
 static class View {float y;float getTranslationY(){return y;}}
 static class SharedMediaLayout {
  static final int TAB_STORIES=0,TAB_ARCHIVED_STORIES=1;
  float stories,archived;
  float getTabVisibility(int tab,boolean b){return tab==0?stories:archived;}
 }
 View bottomButtonsContainer;
 View[] bottomButtonContainer={new View(),new View()};
 SharedMediaLayout sharedMediaLayout=new SharedMediaLayout();
 int navigationBarHeight,additionFloatingButtonOffset;
 BODY
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 public static void main(String[] args){
  for(float d:new float[]{1,1.5f,2.625f,3,4})for(int nav:new int[]{0,24,48})for(boolean tabs:new boolean[]{false,true}){
   density=d;Transitions t=new Transitions();
   t.navigationBarHeight=dp(nav);t.additionFloatingButtonOffset=tabs?dp(64):0;
   int base=t.navigationBarHeight+t.additionFloatingButtonOffset;
   check(t.getBottomOffset(0)==base,"plain profile unchanged");
   t.bottomButtonsContainer=new View();
   for(float story:new float[]{0,.5f,1})for(float archive:new float[]{0,.5f,1}){
    t.sharedMediaLayout.stories=story;t.sharedMediaLayout.archived=archive;
    t.bottomButtonContainer[0].y=dp(60)*story;t.bottomButtonContainer[1].y=dp(60)*archive;
    int prev=Integer.MAX_VALUE;
    for(int frame=0;frame<=120;frame++){
     t.bottomButtonsContainer.y=dp(60)*frame/120f;
     int offset=t.getBottomOffset(0);
     check(offset>=base,"hidden action row must not eat bulletin padding");
     check(offset<=prev,"hiding row smoothly releases only its own reservation");prev=offset;
    }
    check(t.getBottomOffset(0)==base,"fully hidden row restores normal gap");
   }
   t.bottomButtonsContainer.y=0;t.bottomButtonContainer[0].y=t.bottomButtonContainer[1].y=0;
   check(t.getBottomOffset(0)==base+dp(52),"visible buttons still protected");
   check(base+dp(52)-dp(60)<base,"old expression overlaps organizer clearance");
  }
 }
}
'''.replace('BODY', body)
        run_java(java)


if __name__ == '__main__':
    unittest.main()
