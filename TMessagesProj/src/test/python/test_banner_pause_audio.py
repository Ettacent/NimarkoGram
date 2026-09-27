"""Audio pause must not restart the decoder or destroy visual readiness."""
from pathlib import Path
import unittest
from test_gif_loop_transition import method
from test_sender_infocard_transitions import run_java

SOURCE = (Path(__file__).resolve().parents[2] /
          'main/java/app/nimarkogram/messenger/banners/NimarkoBannerRenderer.java').read_text()


class BannerPauseAudioTests(unittest.TestCase):
    def test_pause_preserves_prepared_video(self):
        body = method(SOURCE, 'private void pauseBannerVideo()')
        run_java(r'''
public class Transitions {
 static class org {static class telegram {static class messenger {
  static class FileLog {static void e(Throwable e){}}
 }}}
 static class VideoPlayer {
  boolean playing=true;int pauses;long position=12345;
  void pause(){playing=false;pauses++;}
 }
 VideoPlayer videoPlayer=new VideoPlayer();
 boolean vidReady=true,videoFrameReady=true,videoDecoderFrameReady=true;
 BODY
 public static void main(String[] args){
  Transitions t=new Transitions();VideoPlayer p=t.videoPlayer;
  for(int i=0;i<5;i++)t.pauseBannerVideo();
  if(p.playing||p.pauses!=5||p.position!=12345||t.videoPlayer!=p
     ||!t.vidReady||!t.videoFrameReady||!t.videoDecoderFrameReady)throw new AssertionError();
  t.videoPlayer=null;t.pauseBannerVideo();
 }
}
'''.replace('BODY', body))

    def test_no_audio_preroll_or_delayed_pause_and_visual_fades_retained(self):
        for obsolete in ('pausedAudioPreroll', 'bannerPauseFade', 'bannerPauseDrain',
                         'rebufferPausedCustomPlayback', 'resumePauseFadeIfNeeded'):
            self.assertNotIn(obsolete, SOURCE)
        finish = method(SOURCE, 'private void finishResumeCapture(')
        self.assertIn('armResumeCrossfade(frame);', finish)
        self.assertIn('player.play();', finish)
        self.assertIn('doFreezeSwap(', SOURCE)
        self.assertIn('applyAudioVolume(lastAudioExtra);',
                      method(SOURCE, 'public void beginProfileExit('))
