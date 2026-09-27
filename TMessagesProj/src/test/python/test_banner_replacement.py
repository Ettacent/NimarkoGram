"""Execute production replacement methods with deterministic Android boundary fakes."""
import subprocess
import re
import tempfile
import unittest
from pathlib import Path

from test_banner_background_stability import block, RENDERER


class BannerReplacementTests(unittest.TestCase):
    def harness(self):
        source = RENDERER.read_text()
        methods = '\n'.join(block(source, signature) for signature in (
            'private void setupVideo(', 'private void adoptReopenFreeze()',
            'private boolean prepareReplacementFrame(', 'private void finishReplacementFrame(',
            'private boolean captureFreezeFrame()', 'public void destroyVideo()',
            'private void armPhotoXfade(', 'private String photoReplacementPath(',
            'private boolean videoReplacementInFlight()',
        )).replace('android.view.ViewParent', 'Object')
        # Execute the real cover-selection/binding statements in addVidViews.
        add = block(source, 'private void addVidViews(')
        selection = add[add.index('adoptReopenFreeze();'):add.index('android.content.Context ctx')]
        binding = add[add.index('if (hasFr) vidFreeze.setImageBitmap'):add.index('waitFrame = true; tv.addView')]
        photo = block(source, 'public void drawImageBanner(')
        fade = photo[photo.index('String dk ='):photo.index('initPaints();')]
        fade = fade.replace('return;', 'return 0;')
        return (TEMPLATE.replace('/* METHODS */', methods)
                .replace('/* SELECT */', selection).replace('/* BIND */', binding)
                .replace('/* PHOTO */', fade))

    def execute(self, source):
        with tempfile.TemporaryDirectory(prefix='banner-replacement-') as directory:
            java = Path(directory) / 'Replacement.java'
            java.write_text(source)
            result = subprocess.run(['javac', str(java)], capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stderr)
            return subprocess.run(['java', '-ea', '-cp', directory, 'Replacement'],
                                  capture_output=True, text=True, timeout=30)

    def test_production_replacement(self):
        result = self.execute(self.harness())
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_negative_controls(self):
        source = self.harness()
        for before, after, message in (
            ('boolean hasFr = okBmp(freezeBmp);', 'boolean hasFr = false;', 'replacement cover'),
            ('&& !pathEq(reopenFreezePath, curVidPath)', '', 'ordinary reopen'),
            ('&& reopenFreezeEid == viewedProfileId', '', 'other peer'),
            ('&& reopenFreezeAccount == viewedAccount', '', 'other account'),
            ('!(ownsXfade && okBmp(xfadeBmp)) && ', '', 'photo starts at zero'),
            ('removeVidViews(okBmp(freezeBmp));', 'removeVidViews(false);', 'replacement cover'),
            ('if (isVideoAttachedTo(tv) && videoReplacementInFlight())', 'if (false)', 'rapid video waits'),
            ('return visiblePath;', 'return requestedPath;', 'rapid photo waits'),
        ):
            with self.subTest(message=message):
                self.assertEqual(source.count(before), 1)
                result = self.execute(source.replace(before, after))
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(message, result.stderr)

    def test_lifecycle_and_controller_wiring(self):
        source = RENDERER.read_text()
        release = block(source, 'private void releasePlayer(boolean retainAudio)')
        self.assertIn('++replacementCaptureGeneration', release)
        self.assertIn('cancelRunOnUIThread(replacementCaptureTimeout)', release)
        self.assertIn('replacementCapturePath = null', release)
        dismiss = block(source, 'private void dismissFreeze()')
        self.assertLess(dismiss.index('if (!videoFrameReady'), dismiss.index('doFreezeSwap('))
        self.assertLess(dismiss.index('if (vidW <= 0'), dismiss.index('doFreezeSwap('))
        self.assertIn('tex.setAlpha(1f)', block(source, 'private void doFreezeSwap(final TextureView tex, final ImageView fv, final Bitmap old, final long dur)'))
        photo = block(source, 'public void drawImageBanner(')
        self.assertLess(photo.index('armPhotoXfade(prevPath)'), photo.index('Bitmap bmp = null'))
        self.assertIn('drawXfadeOnly(canvas, w, y1, extra)', photo)
        self.assertIn('xa = 255;', photo)
        self.assertIn('if (y1 <= 0) return;', photo)
        self.assertIn('matKeyW != w || matKeyY1 != y1', photo)
        self.assertEqual(source.count('Math.max(w / (double)'), 3)
        self.assertIn('BANNER_BLEED', block(source, 'private void drawXfadeOnly('))
        controller = RENDERER.with_name('NimarkoBannerController.java').read_text()
        sync = block(controller, 'private void syncBanner(')
        self.assertLess(sync.index('!validDownload(candidate'), sync.index('cachedBanners.put(k, path)'))
        self.assertLess(sync.index('cachedBanners.put(k, path)'), sync.index('safeRemove(new File(previousPath))'))

    def test_photo_src_over_has_no_header_dip(self):
        photo = block(RENDERER.read_text(), 'public void drawImageBanner(')
        underlay = photo[photo.index('int xa = 0;'):photo.index('pBmp.setAlpha(fa);')]
        alpha = int(re.search(r'xa = (\d+);', underlay[underlay.index('if (ownsXfade'):])[1]) / 255
        self.assertIn('pBmp.setAlpha(xa);', underlay)
        self.assertIn('canvas.drawBitmap(xfadeBmp, xfadeMatrix, pBmp);', underlay)
        for step in range(101):
            p = step / 100
            self.assertAlmostEqual(p + alpha * (1 - p), 1, msg='opaque old underlay')
        # A complementary pair of SRC_OVER alphas is not a crossfade.
        self.assertEqual(.5 + .5 * (1 - .5), .75)


TEMPLATE = r'''
import java.util.*;
public class Replacement {
 static class Bitmap {boolean recycled;}
 static class Animator {void cancel(){}}
 static class ImageView {Bitmap bitmap;float alpha;Animator animate(){return new Animator();}
  void setImageBitmap(Bitmap b){bitmap=b;}void setAlpha(float a){alpha=a;}}
 static class ViewGroup {void postInvalidateOnAnimation(){}}
 static class TextureView {Object parent;float alpha=1f;TextureView(Object p){parent=p;}float getAlpha(){return alpha;}
  Object getParent(){return parent;}boolean isAvailable(){return true;}}
 static class VideoPlayer {}
 static class AndroidUtilities {
  static void runOnUIThread(Runnable r){r.run();}
  static void runOnUIThread(Runnable r,long ms){}
  static void cancelRunOnUIThread(Runnable r){}
 }
 interface Callback {void onFrame(Bitmap b);}
 static class Executor {void submit(Runnable r){r.run();}}
 static class Controller {boolean shouldHideAvatar(long eid){return true;}}
 Executor executor=new Executor();Controller ctrl=new Controller();
 ViewGroup currentTopView=new ViewGroup();TextureView videoTexture=new TextureView(currentTopView);
 VideoPlayer videoPlayer=new VideoPlayer();ImageView vidFreeze=new ImageView();
 Bitmap freezeBmp,reopenFreeze,latestVideoFrame,xfadeBmp,videoCrossfadeBitmap;
 String frozenPath,reopenFreezePath,latestVideoFramePath,xfadeMatKey;
 String curVidPath="old.mp4",curBf="new.mp4",replacementCapturePath;
 long viewedProfileId=42,reopenFreezeEid,videoSessionId=1,vidTexAttachedTvId;
 int viewedAccount=0,reopenFreezeAccount=-1,replacementCaptureGeneration,videoViewH;
 int captures,adds;Callback callback;Runnable replacementCaptureTimeout;
 boolean curIv=true,isProfileOpen=true,videoFrameReady=true,vidReady=true,videoPausedByTab,reparentCover,waitFrame;
 double frameTime=100,lastVidAttach,VID_ATTACH_INT=.15,FAIL_CD_S=30,xfadeStart;
 double vidFirstFrameTime;int maxVh,lastDa,lastLh;float lastBa,lastVol;
 final Object latestVideoFrameLock=new Object();
 Map<String,Double> failVids=new HashMap<>();Map<String,Bitmap> bitmaps=new HashMap<>();
 Map<Long,String> photoFadeKey=new HashMap<>();Map<Long,Double> photoFadeStart=new HashMap<>();
 Set<Long> avAnim=new HashSet<>();Map<Long,Float> avAlpha=new HashMap<>();double FADE_DUR=.85;
 static boolean okBmp(Bitmap b){return b!=null&&!b.recycled;}
 static float clamp01(double v){return (float)Math.max(0,Math.min(1,v));}
 static void recycle(Bitmap b){if(b!=null){if(b.recycled)throw new AssertionError("double recycle");b.recycled=true;}}
 static boolean pathEq(String a,String b){return Objects.equals(a,b);}
 static boolean isVideoPath(String p){return p.endsWith(".mp4");}
 double t(){return frameTime;}double getOrD(Map<String,Double> m,String p){return m.getOrDefault(p,0d);}
 float getOr(Map<Long,Float> m,long id,float d){return m.getOrDefault(id,d);}
 void dbg(String s){}void av(String s){}void ensureCallObserver(){}void invalidateTopView(){}
 boolean isVideoAttachedTo(ViewGroup host){return videoTexture!=null&&videoTexture.parent==host;}
 void stopBlur(){}
 void releasePlayer(boolean retainAudio){releasePlayer();}
 void releasePlayer(){videoPlayer=null;curVidPath=null;videoSessionId++;recycle(latestVideoFrame);latestVideoFrame=null;
  replacementCaptureGeneration++;replacementCaptureTimeout=null;replacementCapturePath=null;}
 void removeVidViews(){removeVidViews(false);}
 void removeVidViews(boolean keep){videoTexture=null;vidFreeze=null;if(!keep){recycle(freezeBmp);freezeBmp=null;frozenPath=null;}}
 boolean prepVideo(String path){curVidPath=path;if(videoPlayer==null)videoPlayer=new VideoPlayer();return true;}
 void addVidViews(ViewGroup host){/* SELECT */ videoTexture=new TextureView(host);vidFreeze=new ImageView();/* BIND */ adds++;}
 void captureVideoFrameAsync(long s,String p,Callback c){captures++;callback=c;}
 void publishLatestVideoFrame(Bitmap b,String path,long s){recycle(latestVideoFrame);latestVideoFrame=b;latestVideoFramePath=path;}
 String avatarFadeKey(long id){return "avatar";}void postInv(){}void drawXfadeOnly(Object c,int w,int h,float e){}
 int photoAlpha(String bf,double now,boolean ownsXfade){long eid=42;Object canvas=null;int w=100,y1=100;float extra=100;
  /* PHOTO */ return fa;}
 /* METHODS */
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static Replacement stash(String path,int account,long eid){Replacement r=new Replacement();
  r.reopenFreeze=new Bitmap();r.reopenFreezePath=path;r.reopenFreezeEid=eid;r.reopenFreezeAccount=account;return r;}
 public static void main(String[] args){
  Replacement r=new Replacement();r.setupVideo(r.currentTopView,"new.mp4",100,100);
  check(r.captures==1&&r.adds==0&&r.videoTexture!=null,"keep live surface during capture");
  r.setupVideo(r.currentTopView,"new.mp4",100,100);check(r.captures==1,"single flight");
  Bitmap fresh=new Bitmap();r.callback.onFrame(fresh);r.setupVideo(r.currentTopView,"new.mp4",100,100);
  check(r.adds==1&&r.freezeBmp==fresh&&r.vidFreeze.bitmap==fresh&&r.vidFreeze.alpha==1,"replacement cover");
  r.frameTime++;r.setupVideo(r.currentTopView,"new.mp4",100,100);check(r.adds==1,"idempotent");
  r.waitFrame=true;r.curBf="third.mp4";r.setupVideo(r.currentTopView,"third.mp4",100,100);
  check(r.adds==1&&r.curVidPath.equals("new.mp4"),"rapid video waits for first frame");
  r.waitFrame=false;r.videoCrossfadeBitmap=r.freezeBmp;r.freezeBmp=null;
  r.setupVideo(r.currentTopView,"third.mp4",100,100);
  check(r.adds==1&&r.curVidPath.equals("new.mp4"),"rapid video waits for fade");
  r.videoCrossfadeBitmap=null;r.setupVideo(r.currentTopView,"third.mp4",100,100);
  check(r.captures==2,"rapid video eventually captures next version");
  r=new Replacement();r.vidFirstFrameTime=99.9;r.videoTexture.alpha=.4f;
  r.setupVideo(r.currentTopView,"new.mp4",100,100);
  check(r.captures==0&&r.adds==0,"cache refresh waits for initial reveal");
  r.videoTexture.alpha=1;r.setupVideo(r.currentTopView,"new.mp4",100,100);
  check(r.captures==1,"cache refresh proceeds after initial reveal");
  r=new Replacement();r.freezeBmp=new Bitmap();r.frozenPath="old.mp4";r.waitFrame=true;
  r.currentTopView=new ViewGroup();r.setupVideo(r.currentTopView,"new.mp4",100,100);
  check(r.adds==1&&r.vidFreeze.alpha==1,"host change cannot wait for detached first frame");
  r=stash("old.mp4",0,42);r.curVidPath="new.mp4";Bitmap old=r.reopenFreeze;r.addVidViews(r.currentTopView);
  check(r.freezeBmp==old&&!old.recycled&&r.vidFreeze.alpha==1,"changed reopen cover");
  r=stash("old.mp4",0,42);old=r.reopenFreeze;r.addVidViews(r.currentTopView);
  check(r.freezeBmp==null&&old.recycled&&r.vidFreeze.alpha==0,"ordinary reopen");
  r=stash("older.mp4",0,99);old=r.reopenFreeze;r.addVidViews(r.currentTopView);
  check(r.freezeBmp==null&&old.recycled,"other peer");
  r=stash("older.mp4",1,42);old=r.reopenFreeze;r.addVidViews(r.currentTopView);
  check(r.freezeBmp==null&&old.recycled,"other account");
  r=new Replacement();r.latestVideoFrame=new Bitmap();r.latestVideoFramePath="old.mp4";
  r.currentTopView=new ViewGroup();r.setupVideo(r.currentTopView,"old.mp4",100,100);
  check(r.freezeBmp!=null&&r.vidFreeze.alpha==1,"same-content live reparent cover");
  r=new Replacement();old=new Bitmap();r.latestVideoFrame=old;r.latestVideoFramePath="old.mp4";r.destroyVideo();
  check(r.reopenFreeze==old&&!old.recycled&&r.reopenFreezeEid==42,"close retains owned pixels");
  for(int mode=0;mode<6;mode++){
   r=new Replacement();r.prepareReplacementFrame("new.mp4");Callback late=r.callback;
   switch(mode){case 0:r.videoSessionId++;break;case 1:r.currentTopView=new ViewGroup();break;
    case 2:r.isProfileOpen=false;break;case 3:r.curBf="third.mp4";break;
    case 4:r.curIv=false;break;case 5:r.replacementCaptureGeneration++;break;}
   Bitmap stale=new Bitmap();late.onFrame(stale);check(stale.recycled&&r.latestVideoFrame==null,"stale copy "+mode);
  }
  r=new Replacement();r.prepareReplacementFrame("new.mp4");Callback late=r.callback;r.replacementCaptureTimeout.run();
  check(r.prepareReplacementFrame("new.mp4"),"timeout permits progress");old=new Bitmap();late.onFrame(old);
  check(old.recycled&&r.latestVideoFrame==null,"late timeout result rejected");
  r=new Replacement();r.latestVideoFrame=new Bitmap();r.latestVideoFramePath="old.mp4";
  r.prepareReplacementFrame("new.mp4");r.callback.onFrame(null);r.setupVideo(r.currentTopView,"new.mp4",100,100);
  check(r.freezeBmp!=null,"failed capture fallback");
  r=new Replacement();old=new Bitmap();r.videoCrossfadeBitmap=old;
  r.vidFreeze.setImageBitmap(old);r.vidFreeze.setAlpha(.35f);
  Bitmap live=new Bitmap();r.latestVideoFrame=live;r.latestVideoFramePath="old.mp4";
  check(!r.captureFreezeFrame(),"in-flight cover keeps ownership");
  check(r.vidFreeze.bitmap==old&&r.vidFreeze.alpha==.35f&&!old.recycled
    &&r.latestVideoFrame==live&&r.freezeBmp==null,"snapshot cannot cut an active fade");
  r.videoCrossfadeBitmap=null;
  check(r.captureFreezeFrame()&&r.freezeBmp==live,"capture resumes after fade");
  r=new Replacement();old=new Bitmap();r.bitmaps.put("old.jpg",old);r.armPhotoXfade("old.jpg");
  check(r.xfadeBmp==old&&!r.bitmaps.containsKey("old.jpg"),"photo ownership transfer");
  r.armPhotoXfade("old.jpg");check(r.xfadeBmp==old&&!old.recycled,"decode gap retains old photo");
  r.avAnim.add(42L);r.avAlpha.put(42L,0f);
  check(r.photoAlpha("new.jpg",100,true)==0,"photo starts at zero");
  int mid=r.photoAlpha("new.jpg",100.425,true);check(mid>=126&&mid<=128,"photo follows own clock");
  Bitmap incoming=new Bitmap();r.bitmaps.put("new.jpg",incoming);
  check(r.photoReplacementPath(42,"third.jpg",100.425).equals("new.jpg"),"rapid photo waits for fade");
  check(r.photoReplacementPath(42,"fourth.jpg",101).equals("fourth.jpg"),"rapid photo advances to latest");
  r.armPhotoXfade("new.jpg");check(old.recycled&&r.xfadeBmp==incoming&&!incoming.recycled,"rapid photo ownership");
  check(r.photoAlpha("new.jpg",101,true)==255,"photo settles");
  r=new Replacement();r.bitmaps.put("cached.jpg",new Bitmap());
  r.photoFadeKey.put(42L,"fcached.jpg");r.photoFadeStart.put(42L,100d);
  check(r.xfadeBmp==null&&r.photoReplacementPath(42,"new.jpg",100.2).equals("cached.jpg"),
    "photo cache refresh cannot promote a half-visible initial image");
  check(r.photoReplacementPath(42,"new.jpg",101).equals("new.jpg"),"photo refresh proceeds after reveal");
  r=new Replacement();r.avAnim.add(42L);r.avAlpha.put(42L,0f);
  check(r.photoAlpha("cold.jpg",100,true)==255,"ordinary avatar-coupled entrance unchanged");
 }
}
'''


if __name__ == '__main__':
    unittest.main()
