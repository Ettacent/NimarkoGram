const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const cp = require('node:child_process');
const assert = require('node:assert/strict');

const javaRoot = path.resolve(__dirname, '../../main/java');
const source = fs.readFileSync(path.join(javaRoot, 'org/telegram/messenger/MediaController.java'), 'utf8');
function method(signature) {
    const start = source.indexOf(signature);
    assert(start >= 0, signature);
    const body = source.indexOf('{', start);
    let depth = 1, end = body + 1;
    while (depth && end < source.length) {
        if (source[end] === '{') depth++;
        if (source[end] === '}') depth--;
        end++;
    }
    assert.equal(depth, 0);
    return source.slice(start, end);
}
assert(!source.includes('currentTextureView.setSurfaceTexture(surfaceTexture)'));
assert(!source.includes('pipSwitchingState = 2'));
assert.equal((source.match(/finishPipCloseForSurfaceTransfer\(\);/g) || []).length, 2);
assert(source.includes('if (pipClosingToInline && currentTextureView != null)'));
for (const file of ['org/telegram/messenger/MediaController.java', 'org/telegram/ui/ChatActivity.java',
    'org/telegram/ui/Components/VideoPlayer.java', 'org/telegram/ui/Components/PipRoundVideoView.java']) {
    assert(!fs.readFileSync(path.join(javaRoot, file), 'utf8').includes('RoundVideoDebug'));
}

const methods = [
    'private void finishPipCloseForSurfaceTransfer()',
    'public void setCurrentVideoVisible(boolean visible)',
    'public void setTextureView(TextureView textureView, AspectRatioFrameLayout aspectRatioFrameLayout, FrameLayout container, boolean set, Runnable afterPip)',
].map(method).join('\n');
const harness = `import java.util.*;
public class RoundReturnHarness {
 static final List<String> events = new ArrayList<>();
 static class SurfaceTexture {}
 static class MessageObject { boolean round; MessageObject(boolean value){round=value;} boolean isRoundVideo(){return round;} }
 static class ViewPropertyAnimator { void cancel(){} }
 static class TextureView {
  float alpha=1f; ViewPropertyAnimator animator=new ViewPropertyAnimator();
  ViewPropertyAnimator animate(){return animator;}
  void setAlpha(float value){alpha=value;}
 }
 static class AspectRatioFrameLayout {
  FrameLayout parent; boolean ready=true;
  FrameLayout getParent(){return parent;}
  boolean isDrawingReady(){return ready;}
  void setDrawingReady(boolean value){ready=value;}
  void setAspectRatio(float ratio,int rotation){}
 }
 static class FrameLayout {
  void addView(AspectRatioFrameLayout view){view.parent=this;events.add("attach");}
  void removeView(AspectRatioFrameLayout view){view.parent=null;events.add("detach");}
 }
 static class VideoPlayer {
  TextureView texture; int binds;
  void setTextureView(TextureView value){texture=value;binds++;events.add("bind");}
 }
 static class PipRoundVideoView {
  TextureView texture=new TextureView(); Runnable completion; boolean closed; int closes;
  void show(Object activity,Runnable onClose){}
  TextureView getTextureView(){return texture;}
  void close(boolean animated){close(animated,null);}
  void close(boolean animated,Runnable callback){
   closes++;events.add("close"); if(callback!=null)completion=callback;
   if(!animated)complete();
  }
  void complete(){closed=true;Runnable r=completion;completion=null;if(r!=null)r.run();}
 }
 VideoPlayer videoPlayer=new VideoPlayer();
 TextureView currentTextureView=new TextureView();
 FrameLayout currentTextureViewContainer=new FrameLayout();
 AspectRatioFrameLayout currentAspectRatioFrameLayout=new AspectRatioFrameLayout();
 PipRoundVideoView pipRoundVideoView=new PipRoundVideoView();
 boolean pipClosingToInline,showPipAfterInlineClose,isDrawingWasReady=true,currentAspectRatioFrameLayoutReady;
 MessageObject playingMessageObject;
 long roundVideoFrameReadyAt;
 boolean roundVideoFirstFrameRendered;
 SurfaceTexture roundVideoPendingSurface;
 int pipSwitchingState,currentAspectRatioFrameLayoutRotation;float currentAspectRatioFrameLayoutRatio;
 Object baseActivity=new Object();
 void cleanupPlayer(boolean notify,boolean stop){}
 ${methods}
 static void check(boolean ok,String name){if(!ok)throw new AssertionError(name);}
 static RoundReturnHarness fresh(){events.clear();return new RoundReturnHarness();}
 public static void main(String[] args){
  RoundReturnHarness h=fresh();
  TextureView inline=h.currentTextureView; PipRoundVideoView old=h.pipRoundVideoView;
  h.setCurrentVideoVisible(true);
  check(h.videoPlayer.texture==inline,"bind native inline view");
  check(events.indexOf("bind")<events.indexOf("close"),"bind before PiP teardown");
  check(!h.currentAspectRatioFrameLayout.ready&&!h.isDrawingWasReady,"wait for actual inline frame");
  check(h.pipSwitchingState==0,"no retained PiP surface transfer");
  int binds=h.videoPlayer.binds; h.setCurrentVideoVisible(true);
  check(h.videoPlayer.binds==binds&&old.closes==1,"repeated visibility does not restart close");
  old.complete();check(h.pipRoundVideoView==null&&!h.pipClosingToInline,"close completes");

  h=fresh();h.playingMessageObject=new MessageObject(true);
  h.roundVideoFrameReadyAt=123;h.roundVideoPendingSurface=new SurfaceTexture();
  h.setCurrentVideoVisible(true);
  check(h.roundVideoFirstFrameRendered&&h.roundVideoFrameReadyAt==0&&h.roundVideoPendingSurface==null,
    "round return retires previous surface readiness");
  check(h.currentTextureView.alpha==0f,"round return waits behind preview for inline frame");

  h=fresh();old=h.pipRoundVideoView;h.setCurrentVideoVisible(true);
  h.setCurrentVideoVisible(false);check(h.showPipAfterInlineClose,"defer hide while closing");
  old.complete();check(h.pipSwitchingState==1&&h.currentAspectRatioFrameLayout.parent==null,"resume exit after close");

  h=fresh();old=h.pipRoundVideoView;h.setCurrentVideoVisible(true);
  h.setCurrentVideoVisible(false);h.setCurrentVideoVisible(true);old.complete();
  check(h.currentAspectRatioFrameLayout.parent!=null&&!h.showPipAfterInlineClose,"cancel deferred hide");

  h=fresh();old=h.pipRoundVideoView;h.setCurrentVideoVisible(true);
  TextureView next=new TextureView();AspectRatioFrameLayout layout=new AspectRatioFrameLayout();
  FrameLayout container=new FrameLayout();
  h.setTextureView(next,layout,container,true,null);
  check(h.videoPlayer.texture==next,"new owner does not rebind closing PiP");
  h.setTextureView(new TextureView(),null,null,false,null);
  check(h.currentTextureView==next&&h.currentAspectRatioFrameLayout==layout,"foreign detach cannot steal ownership");
  h.setTextureView(next,null,null,false,null);
  check(h.currentTextureView==null&&h.pipSwitchingState==1,"owner detach requests PiP");
  Runnable stale=old.completion;
  h.finishPipCloseForSurfaceTransfer();
  check(old.closed&&h.pipRoundVideoView==null&&!h.pipClosingToInline,"finish old PiP before a new transfer");
  PipRoundVideoView replacement=new PipRoundVideoView();h.pipRoundVideoView=replacement;
  h.pipClosingToInline=true;h.showPipAfterInlineClose=true;stale.run();
  check(h.pipRoundVideoView==replacement&&h.pipClosingToInline&&h.showPipAfterInlineClose,"stale completion cannot change new transition");

  h=fresh();h.videoPlayer=null;h.setCurrentVideoVisible(true);h.setCurrentVideoVisible(false);
  check(h.pipRoundVideoView.closes==0,"released player is ignored");
  h=fresh();old=h.pipRoundVideoView;h.setCurrentVideoVisible(true);h.videoPlayer=null;
  h.currentTextureView=null;h.setCurrentVideoVisible(false);old.complete();
  check(!h.pipClosingToInline&&h.pipRoundVideoView==null,"cleanup during close completes safely");
  System.out.println("PASS: native output, round preview handoff, frame readiness, repeat/hide/cancel, ownership, fast exit, stale completion, cleanup");
 }
}`;
const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-round-return-test-'));
try {
    const file = path.join(directory, 'RoundReturnHarness.java');
    fs.writeFileSync(file, harness);
    cp.execFileSync('javac', [file], {stdio: 'inherit'});
    cp.execFileSync('java', ['-cp', directory, 'RoundReturnHarness'], {stdio: 'inherit'});
} finally {
    fs.rmSync(directory, {recursive: true, force: true});
}
