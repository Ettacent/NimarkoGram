"""Production hint envelope and audio gain gates on JVM boundary stubs."""
from pathlib import Path
import unittest
from test_recording_composer_lifecycle import method
from test_sender_infocard_transitions import run_java

JAVA = Path(__file__).resolve().parents[2] / 'main/java'


class HintAndGainTests(unittest.TestCase):
    def test_playback_envelope_follows_return_cover(self):
        source = (JAVA / 'app/nimarkogram/messenger/banners/NimarkoBannerRenderer.java').read_text()
        body = method(source, 'private float incomingBannerAudioProgress()')
        run_java('''
public class Transitions {
 static class View {float alpha;float getAlpha(){return alpha;}}
 boolean curIv=true,videoFrameReady=true,resumeAudioUnchanged;
 long vidFirstFrameTime=1,viewedProfileId=1;
 View videoTexture=new View(),vidFreeze=new View();Object videoCrossfadeBitmap=new Object();
 String curBf="x";double FADE_DUR=.85;
 java.util.Map<Long,String> photoFadeKey=new java.util.HashMap<>();
 java.util.Map<Long,Double> photoFadeStart=new java.util.HashMap<>();
 double t(){return 1;}float clamp01(float v){return Math.max(0,Math.min(1,v));}
 BODY
 static void check(boolean v){if(!v)throw new AssertionError();}
 public static void main(String[] args){
  Transitions t=new Transitions();t.vidFreeze.alpha=1;
  check(t.incomingBannerAudioProgress()==0); // replacement still fades
  t.resumeAudioUnchanged=true;
  for(int i=0;i<=100;i++){t.vidFreeze.alpha=i/100f;check(Math.abs(t.incomingBannerAudioProgress()-(1-i/100f))<.001f);}
  t.videoFrameReady=false;check(t.incomingBannerAudioProgress()==0);
  t.videoFrameReady=true;t.resumeAudioUnchanged=false;t.vidFreeze.alpha=.5f;
  check(t.incomingBannerAudioProgress()==.5f);
 }
}'''.replace('BODY', body))
        self.assertIn('resumeAudioUnchanged = false;', method(source, 'private void releasePlayer(boolean'))
        self.assertIn('resumeAudioUnchanged = false;', method(source, 'private boolean prepVideo('))

    def test_hint_reveal_interruptions(self):
        source = (JAVA / 'org/telegram/ui/Components/EditTextBoldCursor.java').read_text()
        body = method(source, 'private float getHintTextRevealAlpha()')
        run_java('''
public class Transitions {
 static class app {static class nimarkogram {static class messenger {static class NimarkoConfig {
  static boolean nimarkoTextAnim=true;
 }}}}
 static class SystemClock {static long now=100;static long uptimeMillis(){return now;}}
 long hintTextRevealStart=-1;boolean transformHintToHeader,hintVisible=true;int size,frames;
 int length(){return size;}void postInvalidateOnAnimation(){frames++;}
 BODY
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  Transitions t=new Transitions();check(t.getHintTextRevealAlpha()==0);
  float last=0;for(int i=0;i<=300;i++){
   SystemClock.now=100+i;float a=t.getHintTextRevealAlpha();check(a>=last&&a<=1);last=a;
   if(i==150)check(Math.abs(a-.5f)<.0001f);
  }
  check(last==1&&t.hintTextRevealStart==0);int frames=t.frames;
  t.getHintTextRevealAlpha();check(t.frames==frames);
  t.hintTextRevealStart=-1;t.size=1;check(t.getHintTextRevealAlpha()==1&&t.hintTextRevealStart==0);
  t.size=0;t.hintTextRevealStart=-1;t.transformHintToHeader=true;
  check(t.getHintTextRevealAlpha()==1&&t.hintTextRevealStart==0);
  t.transformHintToHeader=false;t.hintTextRevealStart=-1;
  app.nimarkogram.messenger.NimarkoConfig.nimarkoTextAnim=false;
  check(t.getHintTextRevealAlpha()==1&&t.hintTextRevealStart==0);
 }
}'''.replace('BODY', body))
        changed = method(source, 'protected void onTextChanged(')
        self.assertIn('lengthBefore > 0', changed)
        self.assertIn('getWindowToken() != null ? -1 : 0', changed)
        draw = method(source, 'private void drawHint(')
        self.assertEqual(draw.count('* drawHintAlpha'), 4)
        self.assertIn('hintAlpha * getHintTextRevealAlpha()', draw)
        self.assertIn('hintTextRevealStart = 0;', method(source, 'protected void onDetachedFromWindow()'))

    def test_visual_capture_does_not_mute_audio(self):
        source = (JAVA / 'app/nimarkogram/messenger/banners/NimarkoBannerRenderer.java').read_text()
        body = method(source, 'public void applyAudioVolume(')
        run_java('''
public class Transitions {
 static class VideoPlayer {float gain,envelope;int writes;boolean playing=true;
  void setPlaybackEnvelope(float v){envelope=v;}
  boolean isPlaying(){return playing;}void setVolume(float v){gain=v;writes++;}}
 static class NimarkoBannerConfig {static boolean enabled=true;}
 VideoPlayer videoPlayer=new VideoPlayer(),outgoingAudioPlayer;float lastAudioExtra,lastVol=-1,maxEh=400,progress=1;
 boolean curVidSound=true,showingPh,inCall,profileExitActive,isProfileOpen=true;
 boolean appPaused,overlayOpen,videoPausedByTab,profileCoveredByNavigation;Runnable resumeCaptureTimeout;
 static final float BASE_VOL=.05f,AUDIO_MUTE_EPSILON=.001f,AUDIO_VOL_UPDATE_THRESHOLD=.0001f;
 float clamp01(float x){return Math.max(0,Math.min(1,x));}float incomingBannerAudioProgress(){return progress;}
 boolean bannerAudioBlocked(){return inCall||profileExitActive||!NimarkoBannerConfig.enabled||!isProfileOpen||appPaused||overlayOpen||videoPausedByTab||profileCoveredByNavigation;}
 BODY
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  Transitions t=new Transitions();t.applyAudioVolume(400);check(t.videoPlayer.gain==BASE_VOL);
  t.profileCoveredByNavigation=true;t.applyAudioVolume(400);check(t.videoPlayer.gain==BASE_VOL&&t.videoPlayer.envelope==0);
  t.profileCoveredByNavigation=false;t.resumeCaptureTimeout=()->{};
  t.applyAudioVolume(400);check(t.videoPlayer.gain==BASE_VOL);
  t.resumeCaptureTimeout=null;t.progress=0;t.applyAudioVolume(400);check(t.videoPlayer.gain==BASE_VOL);
  t.outgoingAudioPlayer=new VideoPlayer();t.applyAudioVolume(400);check(t.videoPlayer.gain==BASE_VOL&&t.videoPlayer.envelope==0);
  float last=0;for(int i=1;i<=100;i++){
   t.progress=i/100f;t.applyAudioVolume(400);check(t.videoPlayer.gain==BASE_VOL&&t.videoPlayer.envelope>=last);last=t.videoPlayer.envelope;
  }
  check(last==1&&t.videoPlayer.writes<10);
  t.applyAudioVolume(0);check(t.videoPlayer.gain==0);
  t.videoPlayer.gain=t.lastVol=.02f;
  t.videoPlayer.playing=false;t.profileCoveredByNavigation=true;
  t.applyAudioVolume(400);check(t.videoPlayer.gain==.02f); // pause must not queue silence
  t.inCall=true;t.applyAudioVolume(400);check(t.videoPlayer.gain==0); // call still mutes
  t.inCall=false;t.profileCoveredByNavigation=false;t.videoPlayer.playing=true;
  t.applyAudioVolume(400);check(t.videoPlayer.gain==BASE_VOL);
  t.profileCoveredByNavigation=true;t.applyAudioVolume(400);check(t.videoPlayer.gain==BASE_VOL&&t.videoPlayer.envelope==0);
  t.inCall=true;t.applyAudioVolume(400);check(t.videoPlayer.gain==0);
 }
}'''.replace('BODY', body))
        swap = method(source, 'private void doFreezeSwap(final TextureView tex, final ImageView fv, final Bitmap old, final long dur)')
        self.assertIn('.setUpdateListener(', swap)
        self.assertIn('applyAudioVolume(lastAudioExtra);', swap)
        resume = method(source, 'private void finishResumeCapture(')
        self.assertLess(resume.index('applyAudioVolume(lastAudioExtra);'), resume.index('player.play()'))


if __name__ == '__main__':
    unittest.main()
