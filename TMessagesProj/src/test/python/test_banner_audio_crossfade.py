"""Production banner audio transition methods with deterministic UI/player boundaries."""
import unittest
from test_banner_background_stability import block, RENDERER
from test_sender_infocard_transitions import run_java


class BannerAudioCrossfadeTest(unittest.TestCase):
    def test_audio_follows_video_photo_and_lifecycle(self):
        source = RENDERER.read_text()
        methods = '\n'.join(block(source, name) for name in (
            'private boolean bannerAudioBlocked(', 'private float bannerExpansionVolume(',
            'private float incomingBannerAudioProgress(', 'private void releaseOutgoingAudio(',
            'private void updateOutgoingAudio(')).replace('android.os.SystemClock.uptimeMillis()', 'clock')
        run_java('''
import java.util.*;
public class Transitions {
 static long clock;
 static class VideoPlayer {float volume,source,envelope=1;boolean playing=true,released;VideoPlayer peer;
  void setVolume(float v){source=v;volume=source*envelope;}
  void setPlaybackEnvelope(float v){envelope=v;volume=source*envelope;}boolean isPlaying(){return playing;}
  void setConcurrentPlaybackPeer(VideoPlayer p){peer=p;}void releasePlayer(boolean b){released=true;playing=false;}}
 static class View {float alpha=1;float getAlpha(){return alpha;}}
 static class AndroidUtilities {static int posts;static void cancelRunOnUIThread(Runnable r){}
  static void runOnUIThread(Runnable r,long delay){posts++;}}
 static class NimarkoBannerConfig {static boolean enabled=true;}
 boolean inCall,profileExitActive,isProfileOpen=true,appPaused,overlayOpen,videoPausedByTab,profileCoveredByNavigation;
 boolean curIv=true,videoFrameReady=true;float lastAudioExtra=400,maxEh=400;
 static final float AUDIO_MUTE_EPSILON=.001f,BASE_VOL=.05f;
 double vidFirstFrameTime=1,FADE_DUR=.85;long viewedProfileId=42;
 Map<Long,String> photoFadeKey=new HashMap<>();Map<Long,Double> photoFadeStart=new HashMap<>();String curBf="new.jpg";
 Object videoCrossfadeBitmap=new Object();View videoTexture=new View(),vidFreeze=new View();
 VideoPlayer outgoingAudioPlayer=new VideoPlayer(),videoPlayer=new VideoPlayer();long outgoingAudioStarted;
 Runnable outgoingAudioTick=()->{};
 float clamp01(float v){return Math.max(0,Math.min(1,v));}double t(){return clock/1000d;}
 void applyAudioVolume(float extra){videoPlayer.setVolume(bannerExpansionVolume());videoPlayer.setPlaybackEnvelope(incomingBannerAudioProgress());}
 METHODS
 static void near(float a,float b){if(Math.abs(a-b)>.00001)throw new AssertionError(a+" != "+b);}
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  Transitions t=new Transitions();VideoPlayer old=t.outgoingAudioPlayer;
  t.videoFrameReady=false;t.updateOutgoingAudio();near(old.volume,.05f);near(t.videoPlayer.volume,0);
  t.videoFrameReady=true;
  for(int step=0;step<100;step++){
   t.vidFreeze.alpha=1-step/100f;t.updateOutgoingAudio();
   near(old.volume+t.videoPlayer.volume,.05f);near(old.volume,.05f*t.vidFreeze.alpha);
  }
  t.vidFreeze.alpha=0;t.updateOutgoingAudio();check(old.released&&t.outgoingAudioPlayer==null);
  t=new Transitions();old=t.outgoingAudioPlayer;t.lastAudioExtra=200;t.vidFreeze.alpha=.4f;
  t.updateOutgoingAudio();near(old.volume+t.videoPlayer.volume,.0125f);
  t.lastAudioExtra=0;t.updateOutgoingAudio();near(old.volume,0);near(t.videoPlayer.volume,0);
  t=new Transitions();old=t.outgoingAudioPlayer;t.curIv=false;
  t.photoFadeKey.put(42L,"fnew.jpg");t.photoFadeStart.put(42L,0d);clock=425;
  t.updateOutgoingAudio();near(old.volume,.025f);clock=850;t.updateOutgoingAudio();check(old.released);
  for(int reason=0;reason<8;reason++){
   t=new Transitions();old=t.outgoingAudioPlayer;
   switch(reason){case 0:t.inCall=true;break;case 1:t.profileExitActive=true;break;
    case 2:t.isProfileOpen=false;break;case 3:t.appPaused=true;break;case 4:t.overlayOpen=true;break;
    case 5:t.videoPausedByTab=true;break;case 6:t.profileCoveredByNavigation=true;break;case 7:old.playing=false;}
   t.updateOutgoingAudio();check(old.released&&t.outgoingAudioPlayer==null);near(old.volume,0);
  }
  t=new Transitions();old=t.outgoingAudioPlayer;t.videoFrameReady=false;clock=2350;
  t.updateOutgoingAudio();near(old.volume,.025f);clock=2700;t.updateOutgoingAudio();check(old.released);
 }
}'''.replace('METHODS', methods))

    def test_decoder_retention_is_only_for_replacement(self):
        source = RENDERER.read_text()
        self.assertIn('releasePlayer(false)', block(source, 'private void releasePlayer()'))
        self.assertIn('releasePlayer(true)', block(source, 'private void setupVideo('))
        release = block(source, 'private void releasePlayer(boolean retainAudio)')
        self.assertIn('retainAudio && p != null && curVidSound', release)
        self.assertIn('outgoingAudioPlayer = p', release)
        self.assertIn('p.setTextureView(null)', release)
        prepare = block(source, 'private boolean prepVideo(')
        self.assertLess(prepare.index('setConcurrentPlaybackPeer(player)'), prepare.index('player.preparePlayer('))
        frame = block(source, 'public FrameDecision prepareFrame(')
        photo = frame[frame.index('try { captureXfadeBmp(); } catch (Throwable ignored) {}\n                        // Returning'):]
        self.assertLess(photo.index('photoFadeKey.remove(eid)'), photo.index('scheduleDestroyVideo()'))
        self.assertLess(photo.index('photoFadeStart.remove(eid)'), photo.index('scheduleDestroyVideo()'))


if __name__ == '__main__':
    unittest.main()
