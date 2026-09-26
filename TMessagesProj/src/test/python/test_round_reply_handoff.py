"""No APK: execute production routing/readiness/capture methods on JVM stubs.

This checks ownership and ordering, not TextureView/RenderThread pixels.
"""
from pathlib import Path
import re
import unittest
from unittest.mock import patch

from test_recording_composer_lifecycle import method
from test_sender_infocard_transitions import run_java

ROOT = Path(__file__).resolve().parents[2] / 'main/java/org/telegram'
PIP = (ROOT / 'ui/Components/PipRoundVideoView.java').read_text()
CELL = (ROOT / 'ui/Cells/ChatMessageCell.java').read_text()
MEDIA = (ROOT / 'messenger/MediaController.java').read_text()


class RoundReplyHandoffTests(unittest.TestCase):
    def test_real_binding_capture_and_callbacks(self):
        pip_methods = '\n'.join(method(PIP, signature) for signature in (
            'public void retainPlaybackFrame()',
            'public void beginPlaybackTransition(',
            'public void onPlaybackFirstFrame(',
            'public boolean needsPlaybackSurfaceUpdate(',
            'public void onPlaybackSurfaceUpdated(',
            'private void finishPlaybackTransitionIfReady()',
        ))
        media_methods = '\n'.join(method(MEDIA, signature) for signature in (
            'private void bindRoundVideoPip()',
            'private void onVideoFirstFrame(',
            'private void onRoundVideoSurfaceUpdated(',
            'private void markRoundVideoSurfaceFrameAvailable(',
            'private void markVideoFrameAvailable()',
            'private void retainInlineRoundVideoFrame()',
            'private void clearInlineRoundVideoFrame()',
            'public float getRoundVideoThumbnailAlpha()',
            'public void onRoundVideoTextureDetached(',
        ))
        inject = method(MEDIA, 'public void injectVideoPlayer(')
        non_round_injection = method(inject, 'if (!messageObject.isRoundVideo())')
        run_java('''import java.util.*;
public class Transitions {
 static class SurfaceTexture {}
 static class Bitmap {float value; Bitmap(float v){value=v;} int getWidth(){return 120;} int getHeight(){return 120;}}
 static class RectF {RectF(int a,int b,int c,int d){}}
 static class Paint {
  static final int ANTI_ALIAS_FLAG=1,FILTER_BITMAP_FLAG=2; int alpha;
  Paint(int flags){} void setAlpha(int a){alpha=a;}
 }
 static class Canvas {
  Bitmap target; Canvas(Bitmap b){target=b;}
  void drawBitmap(Bitmap b,Object src,RectF dst,Paint p){
   target.value=target.value*(1-p.alpha/255f)+b.value*p.alpha/255f;
  }
 }
 static class FileLog {static void e(Throwable e){throw new AssertionError(e);}}
 static class View {
  static final int INVISIBLE=4,VISIBLE=0;
  float alpha=1,targetAlpha=1,pixel=10; int visibility=INVISIBLE,starts,captures;
  boolean available=true; Runnable end;
  SurfaceTexture surface=new SurfaceTexture();
  SurfaceTexture getSurfaceTexture(){return surface;}
  View animate(){return this;} void cancel(){end=null;}
  void setAlpha(float a){alpha=a;} float getAlpha(){return alpha;}
  View alpha(float a){targetAlpha=a;return this;}
  View setDuration(long d){check(d==180,"duration");return this;}
  View withEndAction(Runnable r){end=r;return this;} void start(){starts++;}
  void setVisibility(int v){visibility=v;} int getVisibility(){return visibility;}
  boolean isAvailable(){return available;}
  Bitmap getBitmap(int w,int h){captures++;return new Bitmap(pixel);}
  void setImageBitmap(Bitmap b){} float getScaleX(){return 1;} float getScaleY(){return 1;}
  void setScaleX(float f){} void setScaleY(float f){}
  Object getContext(){return this;} void setImageDrawable(Object d){} void setTag(int t){}
 }
 static class ImageView extends View {
  static class ScaleType {static final int FIT_XY=1;}
  ImageView(Object context){} void setScaleType(int type){}
 }
 static class ViewGroup {static class LayoutParams {static final int MATCH_PARENT=-1;}}
 static class FrameLayout extends View {
  static class LayoutParams {LayoutParams(int w,int h){}}
  void addView(View v,LayoutParams params){}
 }
 static class AspectRatioFrameLayout extends FrameLayout {
  boolean ready;boolean isDrawingReady(){return ready;}void setDrawingReady(boolean r){ready=r;}
 }
 static class Message {int currentAccount;boolean round=true;boolean isRoundVideo(){return round;}int getId(){return 1;}}
 static class SystemClock {static long now=123;static long uptimeMillis(){return now;}}
 static class NotificationCenter {
  static int messagePlayingProgressDidChanged;static NotificationCenter getInstance(int a){return new NotificationCenter();}
  void postNotificationName(int n,int id,int progress){}
 }
 static class AndroidUtilities {
  static List<Runnable> queue=new ArrayList<>(); static int posts;
  static int dp(int n){return n;}
  static void removeFromParent(View v){}
  static void runOnUIThread(Runnable r){posts++;queue.add(r);}
  static void drain(){List<Runnable> tasks=new ArrayList<>(queue);queue.clear();for(Runnable r:tasks)r.run();}
 }
 static class PipRoundVideoView {
  boolean closing,closed,playbackTransitionPending,playbackFrameRendered;
  int playbackPlayerTag=-1,playbackTransitionGeneration;
  SurfaceTexture playbackUpdatedSurface;
  Bitmap bitmap; View textureView=new View(),imageView=new View();
  View getTextureView(){return textureView;}
  void releaseSnapshot(){bitmap=null;}
  PIP_METHODS
 }
 static class Player {
  View output;int replacements;void setMute(boolean b){}
  void setTextureView(View v){if(output==v)return;output=v;replacements++;}
 }
 static class CastSync {static boolean isActive(){return false;}}
 int playerNum=1,roundVideoOutputGeneration;
 long roundVideoFrameReadyAt; boolean roundVideoFirstFrameRendered;
 SurfaceTexture roundVideoPendingSurface;View currentTextureView;
 ImageView roundVideoPlaybackCover;Bitmap roundVideoPlaybackBitmap;View roundVideoCoverTexture;
 boolean roundVideoCoverPending,isDrawingWasReady;int roundVideoCoverGeneration;
 AspectRatioFrameLayout currentAspectRatioFrameLayout=new AspectRatioFrameLayout();
 FrameLayout currentTextureViewContainer=new FrameLayout();Message playingMessageObject=new Message();
 Player videoPlayer=new Player();PipRoundVideoView pipRoundVideoView=new PipRoundVideoView();
 boolean isVideoDrawingReady(){return currentAspectRatioFrameLayout.isDrawingReady();}
 MEDIA_METHODS
 void injectCoverBoundary(Message messageObject) { NON_ROUND_INJECTION }
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 static Transitions handoff(){
  Transitions t=new Transitions();t.videoPlayer.output=t.pipRoundVideoView.textureView;
  t.pipRoundVideoView.retainPlaybackFrame();t.bindRoundVideoPip();return t;
 }
 public static void main(String[] args){
  // Cold inline tap: the opaque preview is above the video. Fading both
  // layers exposes the chat background, which reads as a white flash.
  for(boolean surfaceFirst:new boolean[]{true,false}) {
   Transitions cold=new Transitions();cold.pipRoundVideoView=null;
   cold.currentTextureView=new View();cold.currentTextureView.setAlpha(0);
   SurfaceTexture output=cold.currentTextureView.surface;
   check(cold.getRoundVideoThumbnailAlpha()==1,"preview owns cold tap");
   if(surfaceFirst){cold.onRoundVideoSurfaceUpdated(1,output);AndroidUtilities.drain();}
   else cold.onVideoFirstFrame(1,true);
   check(cold.currentTextureView.alpha==0&&cold.getRoundVideoThumbnailAlpha()==1,
     "one callback must not reveal an unlatched frame");
   if(surfaceFirst)cold.onVideoFirstFrame(1,true);
   else {cold.onRoundVideoSurfaceUpdated(1,output);AndroidUtilities.drain();}
   check(cold.currentTextureView.alpha==1&&cold.currentTextureView.starts==0,
     "first video frame must be opaque beneath the fading preview");
   long firstFrame=SystemClock.now;
   for(int ms=0;ms<=240;ms+=5) {
    SystemClock.now=firstFrame+ms;
    float preview=cold.getRoundVideoThumbnailAlpha();
    float coverage=preview+(1-preview)*cold.currentTextureView.alpha;
    check(Math.abs(coverage-1)<.00001,"no background leak during cold crossfade");
   }
   check(cold.getRoundVideoThumbnailAlpha()==0,"preview fully leaves after handoff");
   cold.onVideoFirstFrame(1,true);cold.onRoundVideoSurfaceUpdated(1,output);AndroidUtilities.drain();
   check(cold.roundVideoFrameReadyAt==firstFrame&&cold.currentTextureView.starts==0,
     "duplicate callbacks must not restart the cold transition");
  }
  for(boolean surfaceFirst:new boolean[]{true,false}){
   Transitions t=handoff();PipRoundVideoView p=t.pipRoundVideoView;
   SurfaceTexture s=p.textureView.surface;
   check(t.videoPlayer.replacements==0,"test must reuse exact texture");
   if(surfaceFirst){t.onRoundVideoSurfaceUpdated(1,s);AndroidUtilities.drain();}
   else t.onVideoFirstFrame(1,true);
   t.bindRoundVideoPip(); // redundant real routing must not erase either half
   check(p.imageView.starts==0,"must wait for both halves");
   if(surfaceFirst)t.onVideoFirstFrame(1,true);
   else {t.onRoundVideoSurfaceUpdated(1,s);AndroidUtilities.drain();}
   check(p.imageView.starts==1&&!p.playbackTransitionPending,"exactly one crossfade");
   t.bindRoundVideoPip();t.onVideoFirstFrame(1,true);
   int posts=AndroidUtilities.posts;
   for(int i=0;i<100;i++)t.onRoundVideoSurfaceUpdated(1,s);
   check(AndroidUtilities.posts==posts&&p.imageView.starts==1,"settled output stays settled");
   check(p.textureView.alpha==1,"opaque live underlay");
  }
  // Live player moved to PiP already sent decoder event, no new one follows.
  Transitions reuse=new Transitions();reuse.roundVideoFirstFrameRendered=true;
  reuse.bindRoundVideoPip();reuse.bindRoundVideoPip();
  check(reuse.pipRoundVideoView.textureView.alpha==1,"never blank reused live output");
  reuse.onRoundVideoSurfaceUpdated(1,reuse.pipRoundVideoView.textureView.surface);
  AndroidUtilities.drain();
  check(!reuse.pipRoundVideoView.playbackTransitionPending,"no second decoder event needed");
  // First-frame callback before queued delivery; inline timestamp cannot gate PiP.
  Transitions t=handoff();PipRoundVideoView p=t.pipRoundVideoView;SurfaceTexture s=p.textureView.surface;
  t.roundVideoFrameReadyAt=123;t.onRoundVideoSurfaceUpdated(1,s);t.onVideoFirstFrame(1,true);
  check(p.playbackTransitionPending,"queued update has not reached output");AndroidUtilities.drain();
  check(p.imageView.starts==1,"inline timestamp does not suppress PiP");
  // A -> B -> C while B is halfway exposed: capture displayed blend.
  Runnable oldEnd=p.imageView.end;p.imageView.alpha=.5f;p.textureView.pixel=30;
  p.retainPlaybackFrame();check(Math.abs(p.bitmap.value-20)<.05,"retain composed A/B, not B alone");
  t.playerNum=2;t.roundVideoFirstFrameRendered=false;t.bindRoundVideoPip();
  t.onRoundVideoSurfaceUpdated(2,s); // B work stays queued
  Bitmap cover=p.bitmap;p.retainPlaybackFrame();
  check(p.bitmap==cover,"unready successor preserves last good cover");
  t.playerNum=3;t.bindRoundVideoPip();t.onVideoFirstFrame(2,true);
  check(!p.playbackFrameRendered,"stale first frame");
  t.onVideoFirstFrame(3,true);t.onRoundVideoSurfaceUpdated(3,s);AndroidUtilities.drain();
  check(p.imageView.starts==2,"C only update is not swallowed by B task");
  oldEnd.run();check(p.bitmap==cover,"old animation cannot release successor cover");
  p.imageView.end.run();check(p.bitmap==null,"current completion releases cover");
  // Reused tag + same bitmap still needs a distinct animation generation.
  t=handoff();p=t.pipRoundVideoView;s=p.textureView.surface;
  t.onVideoFirstFrame(1,true);t.onRoundVideoSurfaceUpdated(1,s);AndroidUtilities.drain();
  oldEnd=p.imageView.end;p.textureView.available=false;p.retainPlaybackFrame();
  t.bindRoundVideoPip();t.onRoundVideoSurfaceUpdated(1,s);AndroidUtilities.drain();
  oldEnd.run();check(p.bitmap!=null,"tag+bitmap alone cannot own completion");
  // PiP replacement with same surface cannot inherit queued work.
  t=handoff();p=t.pipRoundVideoView;s=p.textureView.surface;t.onRoundVideoSurfaceUpdated(1,s);
  PipRoundVideoView replacement=new PipRoundVideoView();replacement.textureView.surface=s;
  t.pipRoundVideoView=replacement;t.bindRoundVideoPip();t.onVideoFirstFrame(1,true);AndroidUtilities.drain();
  check(replacement.playbackTransitionPending,"queued event belongs to old window");
  t.onRoundVideoSurfaceUpdated(1,s);AndroidUtilities.drain();check(!replacement.playbackTransitionPending,"replacement actual update");
  // Paused inline first frame in both orders.
  for(boolean surfaceFirst:new boolean[]{true,false}){
   t=new Transitions();t.pipRoundVideoView=null;t.currentTextureView=new View();s=t.currentTextureView.surface;
   if(surfaceFirst){t.onRoundVideoSurfaceUpdated(1,s);AndroidUtilities.drain();}
   else t.onVideoFirstFrame(1,true);
   if(surfaceFirst)t.onVideoFirstFrame(1,true);
   else {t.onRoundVideoSurfaceUpdated(1,s);AndroidUtilities.drain();}
   check(t.roundVideoFrameReadyAt!=0,"paused inline first frame");
  }
  t=new Transitions();t.pipRoundVideoView=null;t.currentTextureView=new View();s=t.currentTextureView.surface;
  t.onRoundVideoSurfaceUpdated(1,s);t.roundVideoOutputGeneration++;t.onVideoFirstFrame(1,true);
  AndroidUtilities.drain();check(t.roundVideoFrameReadyAt==0,"old output generation rejected");
  t.onRoundVideoSurfaceUpdated(1,s);AndroidUtilities.drain();check(t.roundVideoFrameReadyAt!=0,"new output update accepted");
  t=handoff();p=t.pipRoundVideoView;p.closed=true;t.onVideoFirstFrame(1,true);
  t.onRoundVideoSurfaceUpdated(1,p.textureView.surface);AndroidUtilities.drain();
  check(p.imageView.starts==0,"closed window ignores callbacks");
  // Actual inline route: list preview sits above the reused video container.
  // Retaining A must suppress B's thumbnail while the opaque A cover is present.
  t=new Transitions();t.pipRoundVideoView=null;t.currentTextureView=new View();
  t.currentTextureView.pixel=10;t.currentAspectRatioFrameLayout.ready=true;
  t.retainInlineRoundVideoFrame();ImageView inlineCover=t.roundVideoPlaybackCover;
  check(inlineCover!=null&&t.getRoundVideoThumbnailAlpha()==0,"visible inline cover beats cell thumbnail");
  t.currentAspectRatioFrameLayout.ready=false;t.currentTextureView.setAlpha(0);
  s=t.currentTextureView.surface;t.onVideoFirstFrame(1,true);
  check(inlineCover.starts==0,"decoder alone cannot fade inline cover");
  t.onRoundVideoSurfaceUpdated(1,s);AndroidUtilities.drain();
  check(inlineCover.starts==1&&t.currentTextureView.alpha==1,"only cover fades over opaque video");
  check(t.getRoundVideoThumbnailAlpha()==0,"B cell thumbnail must not obscure crossfade");
  oldEnd=inlineCover.end;inlineCover.alpha=.5f;t.currentTextureView.pixel=30;
  t.retainInlineRoundVideoFrame();
  check(Math.abs(t.roundVideoPlaybackBitmap.value-20)<.05,"inline C retains visible A/B mixture");
  ImageView nextCover=t.roundVideoPlaybackCover;oldEnd.run();
  check(t.roundVideoPlaybackCover==nextCover,"old inline completion cannot clear C cover");
  t.retainInlineRoundVideoFrame();check(t.roundVideoPlaybackCover==nextCover,"pending inline cover survives rapid switch");
  t.currentAspectRatioFrameLayout.ready=false;t.roundVideoFrameReadyAt=0;t.roundVideoFirstFrameRendered=false;
  t.playerNum=3;t.onRoundVideoSurfaceUpdated(3,s);AndroidUtilities.drain();
  check(nextCover.starts==0,"inline surface-first waits for C decoder");
  t.onVideoFirstFrame(3,true);check(nextCover.starts==1,"inline C starts exactly once");
  SystemClock.now+=180;nextCover.end.run();
  check(t.roundVideoPlaybackCover==null&&t.getRoundVideoThumbnailAlpha()==0,"settled inline does not flash thumbnail");
  // A late detach from another texture cannot retire B's cover or readiness.
  t=new Transitions();t.pipRoundVideoView=null;t.currentTextureView=new View();
  t.currentAspectRatioFrameLayout.ready=true;t.retainInlineRoundVideoFrame();
  inlineCover=t.roundVideoPlaybackCover;t.currentAspectRatioFrameLayout.ready=false;
  t.onRoundVideoTextureDetached(new View());
  check(t.roundVideoPlaybackCover==inlineCover&&t.getRoundVideoThumbnailAlpha()==0,
    "foreign detach cannot expose B thumbnail");
  check(t.roundVideoOutputGeneration==0,"foreign detach cannot retire B readiness");
  // Actual output removal restores the fallback, but does not certify B's decoder.
  s=t.currentTextureView.surface;t.onRoundVideoSurfaceUpdated(1,s);
  t.onRoundVideoTextureDetached(t.currentTextureView);AndroidUtilities.drain();
  check(t.roundVideoPlaybackCover==null&&!t.roundVideoFirstFrameRendered&&t.roundVideoPendingSurface==null,
    "detach cannot manufacture successor first frame");
  t.onRoundVideoSurfaceUpdated(1,s);AndroidUtilities.drain();
  check(t.roundVideoFrameReadyAt==0&&t.getRoundVideoThumbnailAlpha()==1,
    "thumbnail stays until reattached B decoder is ready");
  t.onVideoFirstFrame(1,true);check(t.roundVideoFrameReadyAt!=0,"B actual first frame completes readiness");
  // Known decoder readiness survives same-player output recreation.
  t.onRoundVideoTextureDetached(t.currentTextureView);t.currentAspectRatioFrameLayout.ready=false;
  check(t.roundVideoFirstFrameRendered,"detach preserves known first frame");
  t.onRoundVideoSurfaceUpdated(1,s);AndroidUtilities.drain();
  check(t.roundVideoFrameReadyAt!=0,"known decoder needs only new output update");
  t.retainInlineRoundVideoFrame();
  Message nonRound=new Message();nonRound.round=false;t.injectCoverBoundary(nonRound);
  check(t.roundVideoPlaybackCover==null&&t.roundVideoPlaybackBitmap==null&&t.roundVideoCoverTexture==null,
    "non-round injection clears retained inline cover");
 }
}'''.replace('PIP_METHODS', pip_methods)
            .replace('MEDIA_METHODS', media_methods.replace('android.graphics.RectF', 'RectF').replace('TextureView textureView', 'View textureView'))
            .replace('NON_ROUND_INJECTION', non_round_injection))

    def test_all_output_routes_and_both_delegates_are_wired(self):
        self.assertEqual(MEDIA.count('videoPlayer.setTextureView(pipRoundVideoView.getTextureView());'), 1)
        self.assertEqual(MEDIA.count('bindRoundVideoPip();'), 6)
        for start, end in (('public void injectVideoPlayer(', 'private static long volumeBarLastTimeShown'),
                           ('videoPlayer.setLooping(silent);', 'videoPlayer.preparePlayer(Uri.fromFile(cacheFile)')):
            block = MEDIA[MEDIA.index(start):MEDIA.index(end)]
            self.assertIn('onVideoFirstFrame(tag, messageObject.isRoundVideo());', block)
            self.assertIn('onRoundVideoSurfaceUpdated(tag, surfaceTexture);', block)
            self.assertIn('if (tag != playerNum) return false;', block)
        inject = method(MEDIA, 'public void injectVideoPlayer(')
        self.assertIn('roundVideoFirstFrameRendered = messageObject.isRoundVideo();', inject)
        self.assertIn('currentAspectRatioFrameLayout.setDrawingReady(false);', inject)
        cleanup = method(MEDIA, 'public void cleanupPlayer(boolean notify, boolean stopService,')
        self.assertLess(cleanup.index('retainPlaybackFrame()'), cleanup.index('videoPlayer.releasePlayer(true)'))
        self.assertLess(cleanup.index('++playerNum'), cleanup.index('videoPlayer.releasePlayer(true)'))
        self.assertIn('voiceMessagesPlaylist.get(next).isRoundVideo()', cleanup)
        self.assertLess(cleanup.index('retainInlineRoundVideoFrame()'), cleanup.index('videoPlayer.releasePlayer(true)'))
        play = method(MEDIA, 'public boolean playMessage(final MessageObject messageObject, boolean silent)')
        self.assertLess(play.index('retainInlineRoundVideoFrame()'), play.index('cleanupPlayer(notify, false)'))
        self.assertIn('retainInlineRoundVideoFrame();', inject)
        self.assertLess(inject.index('if (!messageObject.isRoundVideo())'), inject.index('videoPlayer = player;'))
        self.assertIn('cleanupPlayer(notify, false);\n        if (!messageObject.isRoundVideo()) clearInlineRoundVideoFrame();', play)
        self.assertIn('if (notify && !playingNext) {\n            clearInlineRoundVideoFrame();', cleanup)
        visible = method(MEDIA, 'public void setCurrentVideoVisible(boolean visible)')
        self.assertNotIn('roundVideoFirstFrameRendered = true;', visible)
        self.assertIn('roundVideoThumbnailAlpha = MediaController.getInstance().getRoundVideoThumbnailAlpha();', CELL)

    def test_negative_controls_detect_missing_visible_handoff(self):
        mutations = (
            ('PIP', 'if (playbackPlayerTag == playerTag) {',
             'if (false) {', 'exactly one crossfade'),
            ('MEDIA', 'pipRoundVideoView.beginPlaybackTransition(playerNum, roundVideoFirstFrameRendered);',
             '// missing real route', 'exactly one crossfade'),
            ('MEDIA', 'if (roundVideoPlaybackCover != null && roundVideoCoverTexture == currentTextureView) {\n            // The list',
             'if (false) {\n            // The list', 'visible inline cover beats cell thumbnail'),
            ('PIP', '&& playbackTransitionGeneration == expectedGeneration',
             '', 'tag+bitmap alone cannot own completion'),
        )
        for name, before, after, failure in mutations:
            with self.subTest(mutation=failure):
                source = globals()[name]
                self.assertEqual(source.count(before), 1)
                with patch.dict(globals(), {name: source.replace(before, after)}):
                    with self.assertRaisesRegex(AssertionError, re.escape(failure)):
                        self.test_real_binding_capture_and_callbacks()

    def test_close_preserves_pending_cover(self):
        close = method(PIP, 'public void close(boolean animated, Runnable onComplete)')
        self.assertLess(close.index('playbackPlayerTag = -1'), close.index('if (animated)'))
        self.assertIn('if (!playbackTransitionPending', close)
        self.assertNotIn('bitmap = null;', close)
        self.assertNotIn('.recycle()', method(PIP, 'private void releaseSnapshot()'))
        self.assertNotIn('playbackCoverTimeout', PIP)

    def test_reply_request_does_not_change_on_download(self):
        binding = CELL[CELL.index('boolean hasReplySpoiler = false;'):CELL.index('if (DialogObject.isEncryptedDialog', CELL.index('boolean hasReplySpoiler = false;'))]
        self.assertNotIn('.mediaExists', binding)
        self.assertNotIn('AndroidUtilities.getPhotoSize()', binding)
        self.assertIn('replyMessageObject.photoThumbs, 320', binding)
        self.assertIn('isRoundVideoDocument(messageObject.messageOwner.reply_to.reply_media.document)', binding)


if __name__ == '__main__':
    unittest.main()
