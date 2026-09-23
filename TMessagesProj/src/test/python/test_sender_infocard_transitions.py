"""Production transition decisions exercised on JVM stubs, not device rendering."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path
from test_recording_composer_lifecycle import ENTER, SRC, method

STRIP = (SRC / 'java/app/nimarkogram/messenger/infocards/InfoCardStripView.java').read_text()
DIALOGS = (SRC / 'java/org/telegram/ui/DialogsActivity.java').read_text()


def run_java(code):
    with tempfile.TemporaryDirectory(prefix='sender-card-transitions-') as folder:
        path = Path(folder) / 'Transitions.java'
        path.write_text(code)
        build = subprocess.run(['javac', str(path)], capture_output=True, text=True)
        assert build.returncode == 0, build.stderr
        run = subprocess.run(['java', '-cp', folder, 'Transitions'], capture_output=True, text=True)
        assert run.returncode == 0, run.stderr


class TransitionTests(unittest.TestCase):
    @unittest.skipUnless(shutil.which('javac'), 'JDK required')
    def test_background_ticks_retain_card_then_animate_latest(self):
        body = method(STRIP, 'private void followSharedActiveCard()')
        run_java('''import java.util.*;
public class Transitions {
 static final int VISIBLE=0;
 static class InfoCardsConfig {static int target;static int getLastActiveCardId(){return target;}}
 static class BaseInfoCard {int id;BaseInfoCard(int n){id=n;}int getCardId(){return id;}}
 ArrayList<BaseInfoCard> pills=new ArrayList<>();int currentIndex=0,pendingActiveCardId=-1;
 int window=0,visibility=0,commits,snaps,prepared=-1,next=-1;
 boolean focus=true,shown=true,dragging,dragUp;Object animator;
 float alpha=1,visibilityFactor=1,dragProgress;
 int getWindowVisibility(){return window;}boolean hasWindowFocus(){return focus;}
 int getVisibility(){return visibility;}boolean isShown(){return shown;}float getAlpha(){return alpha;}
 BaseInfoCard current(){return pills.get(currentIndex);}
 void setPendingActiveCard(int id){pendingActiveCardId=id;}
 boolean isHostVisible(float minimum){return window==0&&focus&&visibility==0&&shown&&alpha>=minimum&&visibilityFactor>=minimum;}
 void ensureIncomingPrepared(int n){prepared=n;}
 void animateCommit(int n){commits++;next=n;}
 void syncToActiveCard(){snaps++;for(int i=0;i<pills.size();i++)if(pills.get(i).id==InfoCardsConfig.target)currentIndex=i;}
 BODY
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  Transitions t=new Transitions();for(int i=0;i<4;i++)t.pills.add(new BaseInfoCard(i));
  t.window=4;InfoCardsConfig.target=1;t.followSharedActiveCard();
  InfoCardsConfig.target=3;t.followSharedActiveCard();
  check(t.currentIndex==0&&t.snaps==0&&t.commits==0&&t.pendingActiveCardId==3);
  t.window=0;t.focus=false;t.followSharedActiveCard();check(t.commits==0);
  t.focus=true;t.followSharedActiveCard();check(t.commits==1&&t.next==3&&t.prepared==3&&t.currentIndex==0);
  t.currentIndex=3;t.followSharedActiveCard();check(t.commits==1&&t.pendingActiveCardId==-1);
  t.dragging=true;InfoCardsConfig.target=1;t.followSharedActiveCard();check(t.pendingActiveCardId==1&&t.commits==1);
  t.dragging=false;t.alpha=0;t.followSharedActiveCard();check(t.snaps==0&&t.currentIndex==3&&t.pendingActiveCardId==1);
  t.alpha=1;t.followSharedActiveCard();check(t.commits==2&&t.next==1&&t.snaps==0);
 }
}'''.replace('BODY', body))

    def test_avatar_uses_native_travel_without_moving_recording_text(self):
        update = method(ENTER, 'private void updateRecordingSenderVisibility(')
        self.assertIn('getSenderHiddenTranslationX()', update)
        self.assertIn('senderSelectView.getTranslationX()', update)
        self.assertIn('senderSelectView.getAlpha()', update)
        self.assertNotIn('messageTextTranslationX =', update)
        self.assertIn('cancelRecordingSenderAnimator();', method(ENTER, 'public void onDestroy()'))

    def test_wiring_search_and_resume(self):
        self.assertIn('contentView.indexOfChild(filterTabsView) + 1', DIALOGS)
        self.assertIn('contentView.addView(homeInfoCards, Math.max(0, cardLayer)', DIALOGS)
        window = method(STRIP, 'protected void onWindowVisibilityChanged(')
        self.assertNotIn('syncToActiveCard(false)', window)
        self.assertIn('postOnAnimation(resumeActiveCard)', window)
        self.assertIn('removeCallbacks(resumeActiveCard)', method(STRIP, 'protected void onDetachedFromWindow()'))
        measure = method(ENTER[ENTER.index('int botCommandLastPosition'):], 'protected void onMeasure(')
        self.assertEqual(measure.count('|| recordingSenderSlotReserved'), 2)
