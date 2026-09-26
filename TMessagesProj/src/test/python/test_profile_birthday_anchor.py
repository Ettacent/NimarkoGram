"""Birthday playback must wait for a visible, bound profile date."""
from pathlib import Path
import unittest
from test_search_fallback_entrance import block
from test_sender_infocard_transitions import run_java

JAVA = Path(__file__).resolve().parents[2] / 'main/java/org/telegram/ui'


class BirthdayAnchorTest(unittest.TestCase):
    def test_start_requires_anchor_and_resets_playback(self):
        source = (JAVA / 'ProfileBirthdayEffect.java').read_text()
        start = block(source, 'public boolean start()')
        autoplay = block(source, 'private void tryAutoplay()')
        run_java('''
public class Transitions {
 static class SystemClock {static long elapsedRealtime(){return 12345;}}
 static class Animation {int restarts;void setCurrentFrame(int n,boolean b){} void restart(boolean b){restarts++;}}
 static class Asset {Animation animation=new Animation();Animation getLottieAnimation(){return animation;}}
 static class Fetcher {boolean loaded;Asset interactionAsset=new Asset();}
 static class Profile {boolean profileTransitionInProgress;}
 static class Animator {void cancel(){}}
 Fetcher fetcher=new Fetcher();Profile profileActivity=new Profile();
 boolean attached,anchor,autoplayed,isPlaying;float t=1,alpha;long lastTime;
 boolean isAttachedToWindow(){return attached;}
 boolean updateSourcePoint(){return anchor;}
 Animator animate(){return new Animator();} void setAlpha(float a){alpha=a;} void invalidate(){}
 START
 AUTOPLAY
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  Transitions v=new Transitions();
  v.tryAutoplay();check(!v.autoplayed&&!v.isPlaying);
  v.attached=true;v.fetcher.loaded=true;v.tryAutoplay();check(!v.autoplayed);
  v.anchor=true;v.profileActivity.profileTransitionInProgress=true;v.tryAutoplay();check(!v.autoplayed);
  v.profileActivity.profileTransitionInProgress=false;v.tryAutoplay();
  check(v.autoplayed&&v.isPlaying&&v.t==0&&v.lastTime==12345&&v.alpha==1);
  check(!v.start());v.t=1;v.isPlaying=false;v.tryAutoplay();check(!v.isPlaying);
  check(v.start());check(v.fetcher.interactionAsset.animation.restarts==2);
  v.t=1;v.anchor=false;check(!v.start());v.anchor=true;v.attached=false;check(!v.start());
 }
}
'''.replace('START', start).replace('AUTOPLAY', autoplay))

    def test_geometry_binding_and_lifecycle_wiring(self):
        source = (JAVA / 'ProfileBirthdayEffect.java').read_text()
        geometry = block(source, 'private boolean updateSourcePoint()')
        self.assertIn('position < 0 || listView == null', geometry)
        self.assertIn('textView.getGlobalVisibleRect(visibleText)', geometry)
        self.assertIn('TextUtils.isEmpty(textView.getText())', geometry)
        self.assertIn('ProfileActivity.profileViewToRoot(this, sourceMatrix)', geometry)
        self.assertIn('ProfileActivity.profileViewToRoot(textView, sourceMatrix)', geometry)
        self.assertIn('overlayInverse.mapPoints(anchor)', geometry)
        self.assertIn('LAYOUT_DIRECTION_RTL', geometry)
        draw = block(source, 'protected void onDraw(Canvas canvas)')
        self.assertIn('if (!updateSourcePoint())', draw)
        self.assertNotIn('post(()', draw)
        self.assertIn('removeOnPreDrawListener(anchorListener)', source)
        self.assertIn('if (isAttachedToWindow()) this.fetcher.addView(this)', source)
        profile = (JAVA / 'ProfileActivity.java').read_text()
        binding = profile[profile.index('} else if (position == birthdayRow) {', profile.index('case VIEW_TYPE_TEXT_DETAIL:', profile.index('public void onBindViewHolder'))):]
        binding = binding[:binding.index('} else if (position == phoneRow)')]
        self.assertIn('TLRPC.UserFull userFull = userInfo;', binding)
        self.assertNotIn('getUserFull(userId)', binding)
        loaded = profile[profile.index('} else if (id == NotificationCenter.userInfoDidLoad)'):]
        updater = block(loaded, 'if (imageUpdater != null)')
        self.assertIn('if (myProfile)', updater)
        self.assertIn('requestProfileRowsUpdate(false, false)', updater)


if __name__ == '__main__':
    unittest.main()
