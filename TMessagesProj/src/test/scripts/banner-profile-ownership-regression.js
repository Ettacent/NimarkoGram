const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const cp = require('node:child_process');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../main/java');
const source = fs.readFileSync(path.join(root, 'app/nimarkogram/messenger/banners/NimarkoBannerRenderer.java'), 'utf8');
const profile = fs.readFileSync(path.join(root, 'org/telegram/ui/ProfileActivity.java'), 'utf8');
function method(signature) {
    const start = source.indexOf('    ' + signature);
    assert(start >= 0, signature);
    let end = source.indexOf('{', start), depth = 0;
    do {
        if (source[end] === '{') depth++;
        if (source[end] === '}') depth--;
        end++;
    } while (depth);
    return source.slice(start, end).replaceAll('android.view.ViewParent', 'Object');
}
const prepare = method('public FrameDecision prepareFrame(');
const ownerGate = prepare.indexOf('!isCurrentProfile(topView, account, eid)');
const headerMutation = prepare.indexOf('headerExtraHint = headerExtra');
assert(ownerGate >= 0 && headerMutation > ownerGate);
assert(prepare.includes('&& videoPlayer != null && isVideoAttachedTo(topView)'));
assert(prepare.includes('isVideoAttachedTo(topView) && texShown'));
assert(method('public float getForegroundProgress(').includes('isVideoAttachedTo(topView) && pathEq(bf, curVidPath)'));
assert(method('private void resumePlayerIfReady()').indexOf('!isVideoAttachedTo(currentTopView)') < method('private void resumePlayerIfReady()').indexOf('player.setTextureView(null)'));
assert(method('private void addVidViews(').includes('watchVideoFrame();'));
assert(profile.includes('r.isCurrentProfile(TopView.this, currentAccount, getDialogId())'));
assert(profile.includes('.getForegroundProgress(TopView.this, currentAccount, getDialogId())'));
assert(!profile.includes('.getForegroundProgress(getDialogId())'));
assert(source.includes('videoTexture.getSurfaceTexture() == surfaceTexture'));
assert(!source.includes('NimarkoBannerTrace'));

const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'banner-owner-'));
fs.writeFileSync(path.join(dir, 'BannerTest.java'), `import java.util.*;
public class BannerTest {
 static final ArrayList<Runnable> pending = new ArrayList<>();
 static class UserConfig {
  static int selectedAccount;
  static final UserConfig[] accounts={new UserConfig(101),new UserConfig(202)};
  long uid;
  UserConfig(long uid) { this.uid=uid; }
  static UserConfig getInstance(int account) { return accounts[account]; }
  long getClientUserId() { return uid; }
 }
 static class AndroidUtilities {
  static void runOnUIThread(Runnable r, long delay) { pending.add(r); }
 }
 static class ViewGroup {
  int invalidations;
  void post(Runnable r) { pending.add(r); }
  void postInvalidateOnAnimation() { invalidations++; }
 }
 static class TextureView {
  Object parent;
  TextureView(Object p) { parent=p; }
  Object getParent() { return parent; }
  boolean isAvailable() { return true; }
 }
 static class VideoPlayer {
  int plays, binds;
  void play() { plays++; }
  void setTextureView(TextureView v) { binds++; }
 }
 ViewGroup currentTopView = new ViewGroup();
 TextureView videoTexture = new TextureView(currentTopView);
 VideoPlayer videoPlayer = new VideoPlayer();
 long viewedProfileId=7, videoSessionId=1, videoHierarchyGeneration, vidTexAttachedTvId;
 int viewedAccount=0, rendererAccount=0;
 long rendererUserId=101;
 String curVidPath="video", curBf="video", frozenPath;
 boolean isProfileOpen=true, curIv=true, vidReady=true, waitFrame=true, freshAttachPending=true;
 boolean appPaused, videoPausedByTab, overlayOpen, reparentCover;
 double frameTime=100, lastVidAttach, VID_ATTACH_INT=.15, FAIL_CD_S=30;
 Object freezeBmp;
 int videoViewH, resumeWatchGen, reveals, adds;
 Map<String,Double> failVids=new HashMap<>();
 double t() { return frameTime; }
 double getOrD(Map<String,Double> map,String key) { return map.getOrDefault(key,0d); }
 boolean pathEq(String a,String b) { return Objects.equals(a,b); }
 boolean okBmp(Object b) { return b != null; }
 void dbg(String s) {} void av(String s) {} void ensureCallObserver() {}
 void removeVidViews() { removeVidViews(false); }
 void removeVidViews(boolean keep) { videoTexture=null; resumeWatchGen++; }
 void destroyVideo() { removeVidViews(); videoPlayer=null; }
 void freezeAndRelease() { destroyVideo(); }
 void captureFreezeFrame() { freezeBmp=new Object(); frozenPath=curVidPath; }
 boolean prepVideo(String path) { if(videoPlayer==null)videoPlayer=new VideoPlayer();curVidPath=path;return true; }
 void addVidViews(ViewGroup tv) { adds++;videoTexture=new TextureView(tv);waitFrame=true;freshAttachPending=true;watchVideoFrame(); }
 void dismissFreeze() { reveals++;waitFrame=false;resumeWatchGen++; }
 ${method('public boolean isCurrentProfile(')}
 ${method('private boolean isActiveAccount(')}
 ${method('private boolean isVideoAttachedTo(')}
 ${method('private boolean isCurrentVideoSession(')}
 ${method('private void scheduleSetupVideo(')}
 ${method('private void setupVideo(')}
 ${method('private void watchVideoFrame()')}
 ${method('private void resumeFrameWatchdog(')}
 static void check(boolean value) { if(!value)throw new AssertionError(); }
 static void drain() { ArrayList<Runnable> batch=new ArrayList<>(pending);pending.clear();for(Runnable r:batch)r.run(); }
 public static void main(String[] args) {
  for(boolean waiting : new boolean[]{false,true}) {
   BannerTest t=new BannerTest(); ViewGroup a=t.currentTopView,b=new ViewGroup();
   t.waitFrame=waiting;t.currentTopView=b;t.lastVidAttach=t.frameTime;
   check(!t.isCurrentProfile(a,0,7) && t.isCurrentProfile(b,0,7));
   check(!t.isCurrentProfile(b,0,8) && !t.isCurrentProfile(null,0,7));
   check(!t.isCurrentProfile(b,1,7)); // same dialog/view cannot alias another account
   t.scheduleSetupVideo(b,"video",1080,900);drain();
   check(t.adds==1 && t.isVideoAttachedTo(b));
   t.setupVideo(b,"video",1080,900);check(t.adds==1);
   drain();check(t.reveals==0);drain();check(t.reveals==1);
   t.currentTopView=a;t.setupVideo(a,"video",1080,900);
   check(t.adds==2 && t.isVideoAttachedTo(a));drain();drain();check(t.reveals==2);
  }
  BannerTest t=new BannerTest();ViewGroup a=t.currentTopView,b=new ViewGroup();
  UserConfig.selectedAccount=1;
  check(!t.isCurrentProfile(a,0,7) && !t.isCurrentProfile(a,1,7));
  UserConfig.selectedAccount=0;
  UserConfig.accounts[0].uid=303; // logout/login reuses the same account slot
  check(!t.isCurrentProfile(a,0,7));
  UserConfig.accounts[0].uid=101;
  t.viewedAccount=1;
  check(!t.isCurrentProfile(a,0,7));
  t.viewedAccount=0;
  check(t.isCurrentProfile(a,0,7));
  t.scheduleSetupVideo(a,"video",1080,900);t.currentTopView=b;drain();check(t.adds==0);
  t.scheduleSetupVideo(b,"video",1080,900);t.curBf="new-video";drain();check(t.adds==0);
  t=new BannerTest();t.watchVideoFrame();t.currentTopView=new ViewGroup();drain();check(t.reveals==0 && pending.isEmpty());
  t=new BannerTest();t.watchVideoFrame();t.resumeWatchGen++;drain();check(t.reveals==0 && pending.isEmpty());
  t=new BannerTest();t.watchVideoFrame();t.videoSessionId++;drain();check(t.reveals==0);
  for(int pause=0;pause<4;pause++) {
   t=new BannerTest();t.watchVideoFrame();
   if(pause==0)t.overlayOpen=true;if(pause==1)t.appPaused=true;
   if(pause==2)t.videoPausedByTab=true;if(pause==3)t.isProfileOpen=false;
   drain();check(t.reveals==0 && pending.isEmpty());
  }
  t=new BannerTest();t.watchVideoFrame();t.dismissFreeze();drain();check(t.reveals==1 && pending.isEmpty());
  t=new BannerTest();t.vidReady=false;t.watchVideoFrame();check(pending.isEmpty());
  t=new BannerTest();t.videoTexture=null;t.watchVideoFrame();check(pending.isEmpty());
  System.out.println("PASS: account/UID/view ownership, same-peer host handoff both directions, in-flight reveal, READY reuse, stale transactions/callbacks, pause gates and single reveal");
 }
}`);
cp.execFileSync('javac', ['BannerTest.java'], {cwd:dir, stdio:'inherit'});
cp.execFileSync('java', ['BannerTest'], {cwd:dir, stdio:'inherit'});
console.log('PASS: profile ownership gates, header opacity, ready-player recovery and debug removal');
