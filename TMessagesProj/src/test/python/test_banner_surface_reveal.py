"""Exercise production frame callbacks; Android surface/GPU behavior needs device QA."""
from pathlib import Path
import unittest
from test_recording_composer_lifecycle import method
from test_sender_infocard_transitions import run_java

SOURCE = (Path(__file__).resolve().parents[2] /
          'main/java/app/nimarkogram/messenger/banners/NimarkoBannerRenderer.java').read_text()


class BannerSurfaceRevealTests(unittest.TestCase):
    def test_decoder_callback_cannot_reveal_unlatched_frame(self):
        callbacks = '\n'.join(method(SOURCE, signature) for signature in (
            'public void onRenderedFirstFrame()',
            'public void onSurfaceTextureUpdated(android.graphics.SurfaceTexture surfaceTexture)'))
        run_java('''
public class Transitions {
 static class android {static class graphics {static class SurfaceTexture {}}}
 static class Texture {android.graphics.SurfaceTexture surface=new android.graphics.SurfaceTexture();
  android.graphics.SurfaceTexture getSurfaceTexture(){return surface;}}
 Texture videoTexture=new Texture();Object player=new Object(),currentTopView=new Object();
 int sessionId=1,vidW=100,vidH=100,captures,reveals,resumes,invalidations;
 String cp="a",curVidPath="a";boolean current=true,attached=true,videoFrameReady,videoDecoderFrameReady,waitFrame=true;
 boolean isCurrentVideoSession(int id,Object p,String path){return current;}
 int resumeRequests;
 void resumePlayerIfReady(){resumeRequests++;}
 boolean isVideoAttachedTo(Object top){return attached;}
 void dbg(String s){}void invalidateTopView(){invalidations++;}
 interface Callback {void frame(Object frame);}
 void captureVideoFrameAsync(int id,String path,Callback cb){captures++;}
 void publishLatestVideoFrame(Object frame,String path,int id){}
 void dismissFreeze(){if(!videoFrameReady)throw new AssertionError();reveals++;waitFrame=false;}
 void startResumeCrossfadeOnFrame(){resumes++;}
 CALLBACKS
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  Transitions t=new Transitions();
  t.onSurfaceTextureUpdated(t.videoTexture.surface);check(!t.videoFrameReady);
  t.onRenderedFirstFrame();t.onRenderedFirstFrame();
  check(!t.videoFrameReady&&t.reveals==0&&t.resumes==0&&t.captures==0);
  t.onSurfaceTextureUpdated(new android.graphics.SurfaceTexture());
  check(!t.videoFrameReady&&t.reveals==0);
  t.attached=false;t.onSurfaceTextureUpdated(t.videoTexture.surface);
  check(!t.videoFrameReady);t.attached=true;t.current=false;
  t.onSurfaceTextureUpdated(t.videoTexture.surface);check(!t.videoFrameReady);
  t.current=true;t.onSurfaceTextureUpdated(t.videoTexture.surface);
  check(t.videoFrameReady&&t.reveals==1&&t.captures==1&&t.resumes==1);
  t.onRenderedFirstFrame();check(t.resumes==1&&t.reveals==1);
  t.onSurfaceTextureUpdated(t.videoTexture.surface);
  check(t.reveals==1&&t.captures==1&&t.resumes==2);
  t.videoFrameReady=false;t.videoDecoderFrameReady=false;t.waitFrame=true;t.videoTexture=new Texture();
  t.onSurfaceTextureUpdated(t.videoTexture.surface);check(!t.videoFrameReady);
  t.onRenderedFirstFrame();
  t.onSurfaceTextureUpdated(t.videoTexture.surface);
  check(t.reveals==2&&t.captures==2);
  t.onSurfaceTextureUpdated(t.videoTexture.surface);check(t.resumeRequests==0);
 }
}'''.replace('CALLBACKS', callbacks))

    def test_reveal_gates_and_curve_wiring(self):
        self.assertIn('videoDecoderFrameReady = false;', method(SOURCE, 'private void addVidViews('))
        reveal = method(SOURCE, 'private void dismissFreeze()')
        for gate in ('!videoFrameReady', '!vidReady', 'appPaused', 'videoPausedByTab',
                     'overlayOpen', 'profileCoveredByNavigation', '!isProfileOpen',
                     'vidW <= 0', 'videoTexture.getWidth() <= 0'):
            self.assertLess(reveal.index(gate), reveal.index('vidFirstFrameTime = t()'))
        swap = method(SOURCE, 'private void doFreezeSwap(final TextureView tex, final ImageView fv, final Bitmap old, final long dur)')
        for path in (reveal, swap):
            self.assertIn('new android.view.animation.AccelerateDecelerateInterpolator()', path)
        self.assertIn('tex.setAlpha(1f)', swap)  # opaque underlay: no mid-fade brightness dip


if __name__ == '__main__':
    unittest.main()
