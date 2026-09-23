"""Production resume/crossfade ownership on JVM stubs; not Android GPU QA."""
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

from test_gif_loop_transition import method

SOURCE = (Path(__file__).resolve().parents[2] /
          'main/java/app/nimarkogram/messenger/banners/NimarkoBannerRenderer.java').read_text()


class BannerResumeCrossfadeTests(unittest.TestCase):
    def test_retained_surface_resume_and_interruptions(self):
        production = re.search(r'private static final long RESUME_FADE = \d+;', SOURCE)[0] + '\n'
        production += '\n'.join(method(SOURCE, name) for name in (
            'private void resumePlayerIfReady()', 'private void cancelResumeCapture()',
            'private void finishResumeCapture(', 'private void armResumeCrossfade(Bitmap',
            'private void startResumeCrossfadeOnFrame()',
            'private void doFreezeSwap(final TextureView tex, final ImageView fv, final Bitmap old, final long dur)'))
        source = r'''
import java.util.*;
public class ResumeTest {
 static class Bitmap {boolean recycled;}
 static class View {static final int VISIBLE=0;}
 static class Animator {
  Surface owner;Runnable end;long duration;float target;
  Animator(Surface s){owner=s;}void cancel(){end=null;}
  Animator alpha(float a){target=a;return this;}Animator setDuration(long d){duration=d;return this;}
  Animator setInterpolator(Object x){return this;}Animator withEndAction(Runnable r){end=r;return this;}
  void start(){}void finish(){owner.alpha=target;Runnable r=end;end=null;if(r!=null)r.run();}
 }
 static class Surface {float alpha=1;int visibility;Animator animator=new Animator(this);
  Animator animate(){return animator;}void setAlpha(float a){alpha=a;}float getAlpha(){return alpha;}
  void setVisibility(int v){visibility=v;}}
 static class TextureView extends Surface {boolean available=true;boolean isAvailable(){return available;}}
 static class ImageView extends Surface {Bitmap bitmap;Object drawable;
  void setImageBitmap(Bitmap b){bitmap=b;drawable=new Object();}Object getDrawable(){return drawable;}}
 static class VideoPlayer {boolean playing;int plays;
  boolean isPlaying(){return playing;}void play(){playing=true;plays++;}}
 static class android {static class view {static class animation {static class LinearInterpolator {}}}}
 static class AndroidUtilities {
  static List<Runnable> timers=new ArrayList<>();
  static void runOnUIThread(Runnable r){r.run();}
  static void runOnUIThread(Runnable r,long delay){timers.add(r);}
  static void cancelRunOnUIThread(Runnable r){timers.remove(r);}
 }
 interface VideoFrameCallback {void onFrame(Bitmap b);}
 TextureView videoTexture=new TextureView();ImageView vidFreeze=new ImageView();
 VideoPlayer videoPlayer=new VideoPlayer();String curVidPath="a";long videoSessionId=1;
 boolean active=true,attached=true,vidReady=true,videoFrameReady=true,isProfileOpen=true;
 boolean appPaused,videoPausedByTab,overlayOpen,waitFrame;
 double vidFirstFrameTime=10;Object currentTopView=new Object();
 int resumeCaptureGeneration,captures;Runnable resumeCaptureTimeout;
 Bitmap videoCrossfadeBitmap,freezeBmp;Animator vidXfade;boolean resumeFadeWaitingForFrame;
 VideoFrameCallback callback;
 boolean isCurrentVideoSession(long s,VideoPlayer p,String path){
  return active&&s==videoSessionId&&p==videoPlayer&&Objects.equals(path,curVidPath);}
 boolean isVideoAttachedTo(Object top){return attached;}
 void invalidateTopView(){}void startBlur(){}
 void captureVideoFrameAsync(long s,String p,VideoFrameCallback c){captures++;callback=c;}
 static boolean okBmp(Bitmap b){return b!=null&&!b.recycled;}
 static void recycle(Bitmap b){if(b!=null)b.recycled=true;}
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 /* PRODUCTION */
 public static void main(String[] args){
  ResumeTest r=new ResumeTest();r.resumePlayerIfReady();r.resumePlayerIfReady();
  check(r.captures==1&&r.videoPlayer.plays==0,"one capture, retain paused frame");
  check(r.videoTexture.alpha==1&&r.videoTexture.visibility==0,"surface never hidden");
  Bitmap b=new Bitmap();r.callback.onFrame(b);
  check(r.videoPlayer.plays==1&&r.vidFreeze.bitmap==b,"last frame covers resumed video");
  check(r.resumeFadeWaitingForFrame&&r.vidFreeze.animator.duration==0,"hold cover while decoder resumes");
  r.appPaused=true;r.startResumeCrossfadeOnFrame();
  check(r.resumeFadeWaitingForFrame,"background frames do not spend the fade");
  r.appPaused=false;r.startResumeCrossfadeOnFrame();
  check(!r.resumeFadeWaitingForFrame,"first resumed frame starts fade once");
  check(r.vidFreeze.alpha==1&&r.vidFreeze.animator.target==0,"cover fades out, not in");
  check(r.vidFreeze.animator.duration==700,"resume duration");
  check(!r.waitFrame&&r.vidFirstFrameTime==10,"no second first-frame dependency");
  r.resumePlayerIfReady();check(r.captures==1,"playing video not recaptured");
  r.vidFreeze.animator.finish();check(b.recycled&&r.vidFreeze.bitmap==null,"completed cover released");
  check(r.vidFreeze.getDrawable()!=null,"Android retains an empty drawable wrapper");
  for(int cycle=0;cycle<100;cycle++){
   r.videoPlayer.playing=false;int count=r.captures;
   r.resumePlayerIfReady();check(r.captures==count+1,"each return captures again");
   b=new Bitmap();r.callback.onFrame(b);r.startResumeCrossfadeOnFrame();
   r.vidFreeze.animator.finish();check(b.recycled&&r.vidFreeze.bitmap==null,"each fade completes");
  }
  r=new ResumeTest();r.resumePlayerIfReady();b=new Bitmap();r.callback.onFrame(b);
  r.startResumeCrossfadeOnFrame();r.vidFreeze.alpha=.4f;r.vidFreeze.animator.cancel();
  r.videoPlayer.playing=false;r.resumePlayerIfReady();Bitmap replacement=new Bitmap();r.callback.onFrame(replacement);
  check(b.recycled&&r.vidFreeze.bitmap==replacement,"cancelled fade can be replaced on return");
  r.startResumeCrossfadeOnFrame();r.vidFreeze.animator.finish();check(replacement.recycled,"replacement cleaned up");
  for(int mode=0;mode<8;mode++){
   r=new ResumeTest();r.resumePlayerIfReady();VideoFrameCallback late=r.callback;
   switch(mode){
    case 0:r.cancelResumeCapture();break;
    case 1:r.videoSessionId++;break;
    case 2:r.videoTexture=new TextureView();break;
    case 3:r.active=false;break;
    case 4:r.appPaused=true;break;
    case 5:r.videoPausedByTab=true;break;
    case 6:r.overlayOpen=true;break;
    case 7:r.attached=false;break;
   }
   b=new Bitmap();late.onFrame(b);
   check(b.recycled&&r.videoPlayer.plays==0&&r.vidFreeze.bitmap==null,"stale capture rejected "+mode);
  }
  r=new ResumeTest();r.resumePlayerIfReady();VideoFrameCallback late=r.callback;
  r.resumeCaptureTimeout.run();b=new Bitmap();late.onFrame(b);
  check(r.videoPlayer.plays==1&&b.recycled&&r.vidFreeze.bitmap==null,"timeout resumes without late fade");
  r=new ResumeTest();r.resumePlayerIfReady();r.callback.onFrame(null);
  check(r.videoPlayer.plays==1&&r.vidFreeze.bitmap==null,"failed capture remains visible");
  r=new ResumeTest();r.resumePlayerIfReady();late=r.callback;r.cancelResumeCapture();
  r.resumePlayerIfReady();b=new Bitmap();late.onFrame(b);Bitmap fresh=new Bitmap();r.callback.onFrame(fresh);
  check(b.recycled&&r.vidFreeze.bitmap==fresh&&r.videoPlayer.plays==1,"rapid re-entry owns latest request");
  r=new ResumeTest();r.vidFirstFrameTime=0;r.resumePlayerIfReady();
  check(r.captures==0&&r.videoPlayer.plays==1,"cold open unchanged");
  r=new ResumeTest();r.freezeBmp=new Bitmap();r.vidFreeze.setImageBitmap(r.freezeBmp);r.vidFreeze.alpha=.4f;r.resumePlayerIfReady();
  check(r.captures==0&&r.vidFreeze.alpha==.4f,"first-frame cover not replaced");
 }
}
'''.replace('/* PRODUCTION */', production)
        with tempfile.TemporaryDirectory(prefix='banner-resume-') as directory:
            java = Path(directory) / 'ResumeTest.java'
            java.write_text(source)
            for command in (['javac', str(java)], ['java', '-cp', directory, 'ResumeTest']):
                result = subprocess.run(command, capture_output=True, text=True, timeout=30)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_pause_and_teardown_invalidate_pending_copies(self):
        for signature in ('public void onProfilePaused(ViewGroup', 'public void onOverlayOpen(Object',
                          'public void onAppPause()', 'public void onTabVisibilityChanged(',
                          'private void removeVidViews(boolean', 'private void releasePlayer()'):
            self.assertIn('cancelResumeCapture();', method(SOURCE, signature))
        self.assertIn('resumePlayerIfReady();', method(SOURCE, 'public void onTabVisibilityChanged('))
        ready = method(SOURCE, '@Override public void onStateChanged(')
        self.assertIn('resumePlayerIfReady();', ready)
        self.assertNotIn('player.play();', ready)
        self.assertIn('startResumeCrossfadeOnFrame();', method(SOURCE, '@Override public void onSurfaceTextureUpdated('))
        teardown = method(SOURCE, 'private void removeVidViews(boolean')
        self.assertIn('bmps.add(videoCrossfadeBitmap)', teardown)
        self.assertIn('videoCrossfadeBitmap = null', teardown)
        for signature in ('private void resumePlayerIfReady()', 'private void finishResumeCapture(',
                          'private void armResumeCrossfade(Bitmap'):
            body = method(SOURCE, signature)
            self.assertNotIn('setTextureView(', body)
            self.assertNotIn('setVisibility(View.INVISIBLE)', body)
            self.assertNotIn('vidFirstFrameTime = 0', body)


if __name__ == '__main__':
    unittest.main()
