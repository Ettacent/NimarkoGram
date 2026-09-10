const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const cp = require('node:child_process');
const assert = require('node:assert/strict');

const source = fs.readFileSync(path.resolve(__dirname,
    '../../main/java/org/telegram/messenger/video/VideoPlayerHolderBase.java'), 'utf8');
function method(signature) {
    const start = source.indexOf(signature);
    assert(start >= 0, signature);
    let end = source.indexOf('{', start) + 1, depth = 1;
    for (; depth && end < source.length; end++) {
        if (source[end] === '{') depth++;
        if (source[end] === '}') depth--;
    }
    assert.equal(depth, 0);
    return source.slice(start, end);
}
const methods = ['public void pause()', 'public void prepareStub()', 'public void play()',
    'public void play(float speed)', 'private void invalidateStubCapture()',
    'private void releaseStubBitmap(Bitmap bitmap)'].map(method).join('\n');
const harness = `import java.util.*; import java.util.function.Consumer;
public class StoryPauseHarness {
 boolean released, paused, firstFrameRendered=true, stubAvailable;
 long stubCaptureGeneration=1, stubCaptureInFlightGeneration=-1, mediaGeneration=1, pendingSeekTo;
 SurfaceView surfaceView=new SurfaceView(); Object surface, textureView;
 Bitmap playerStubBitmap, reusableStubBitmap; Paint playerStubPaint;
 Queue dispatchQueue=new Queue(); Player videoPlayer=new Player();
 static class Queue { List<Runnable> tasks=new ArrayList<>(); void postRunnable(Runnable r){tasks.add(r);}
  void flush(){while(!tasks.isEmpty())tasks.remove(0).run();} }
 static class Player { int pauses, seeks; void pause(){pauses++;} void setSurface(Object s){}
  void setSurfaceView(Object s){} void setTextureView(Object s){} void seekTo(long p){seeks++;}
  void setPlayWhenReady(boolean b){} void setPlaybackSpeed(float s){} }
 static class SurfaceView { SurfaceView getHolder(){return this;} SurfaceView getSurface(){return this;}
  boolean isValid(){return true;} }
 static class Bitmap { int frame=1; boolean recycled; static class Config{static Object ARGB_8888=new Object();}
  static Bitmap createBitmap(int w,int h,Object c){return new Bitmap();} boolean isRecycled(){return recycled;}
  int getWidth(){return 720;} int getHeight(){return 1280;} int getPixel(int x,int y){return frame;} }
 static class Paint { static int ANTI_ALIAS_FLAG=1; Paint(int flags){} }
 static class Color { static int TRANSPARENT=0; }
 static class Build { static class VERSION {static int SDK_INT=36;} static class VERSION_CODES{static int N=24;} }
 static class Capture { Bitmap bitmap; Consumer<Boolean> callback;
  Capture(Bitmap b,Consumer<Boolean> c){bitmap=b;callback=c;}
  void complete(int frame, boolean success){bitmap.frame=frame;callback.accept(success);} }
 static class AndroidUtilities { static List<Capture> captures=new ArrayList<>();
  static void getBitmapFromSurface(SurfaceView v,Bitmap b,Consumer<Boolean> c){captures.add(new Capture(b,c));}
  static void recycleBitmap(Bitmap b){b.recycled=true;} }
 ${methods}
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 static StoryPauseHarness fresh(){AndroidUtilities.captures.clear();StoryPauseHarness h=new StoryPauseHarness();
  h.playerStubBitmap=new Bitmap();h.stubAvailable=true;return h;}
 static Capture last(){return AndroidUtilities.captures.get(AndroidUtilities.captures.size()-1);}
 public static void main(String[] args){
  StoryPauseHarness h=fresh();h.pause();
  check(!h.stubAvailable,"pause must not display the first/previous cached frame");
  Capture capture=last();h.pause();check(last()==capture,"repeated pause must not recapture");
  capture.complete(90,true);check(h.stubAvailable&&h.playerStubBitmap.frame==90,"fresh pause frame accepted");
  h.dispatchQueue.flush();check(h.videoPlayer.pauses==1&&h.videoPlayer.seeks==0,"pause without seek/reset");

  h=fresh();h.pause();Capture old=last();h.play(1f);
  old.complete(10,true);check(!h.stubAvailable,"late capture cannot publish after speed resume");
  h.pause();Capture latest=last();latest.complete(95,true);
  check(h.stubAvailable&&h.playerStubBitmap.frame==95,"next pause uses its own frame");
  h.play();check(!h.stubAvailable,"plain resume invalidates snapshot too");

  h=fresh();h.pause();old=last();h.play();h.pause();latest=last();latest.complete(99,true);
  old.complete(12,true);check(h.stubAvailable&&h.playerStubBitmap.frame==99,"out of order capture cannot replace new frame");

  for(boolean transparent:new boolean[]{false,true}){
   h=fresh();h.pause();last().complete(transparent?0:15,transparent);
   check(!h.stubAvailable,"failed/transparent capture cannot revive old frame");
  }
  h=fresh();h.surfaceView=null;h.pause();check(!h.stubAvailable,"missing surface cannot reuse stale snapshot");
  h=fresh();h.firstFrameRendered=false;h.pause();check(!h.stubAvailable,"no decoded frame cannot reuse snapshot");
  h=fresh();h.pause();h.released=true;last().complete(20,true);check(!h.stubAvailable,"released capture rejected");
  System.out.println("PASS: actual holder methods: stale first frame, repeat pause, both resume paths, out-of-order capture, failed/transparent capture, missing surface/frame, release");
 }
}`;
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'story-pause-test-'));
try {
    const file = path.join(tmp, 'StoryPauseHarness.java');
    fs.writeFileSync(file, harness);
    cp.execFileSync('javac', [file], {stdio: 'inherit'});
    cp.execFileSync('java', ['-cp', tmp, 'StoryPauseHarness'], {stdio: 'inherit'});
} finally {
    fs.rmSync(tmp, {recursive: true, force: true});
}
