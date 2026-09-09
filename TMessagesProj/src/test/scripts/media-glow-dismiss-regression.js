const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const cp = require('node:child_process');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../main/java');
const source = fs.readFileSync(path.join(root, 'app/nimarkogram/messenger/mediaglow/MediaGlowController.java'), 'utf8');
assert(!source.includes('PorterDuff.Mode.ADD'), 'Glow must preserve window transparency');
assert(!source.includes('canvas.saveLayer'), 'Do not allocate a full-screen blending layer');
function method(signature) {
    const start = source.indexOf('\n    ' + signature);
    assert(start >= 0, signature);
    const tokens = /\/\/[^\n]*|\/\*[\s\S]*?\*\/|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[{}]/g;
    tokens.lastIndex = source.indexOf('{', start);
    let depth = 0;
    for (let token; (token = tokens.exec(source));) {
        if (token[0] === '{') depth++;
        if (token[0] === '}' && --depth === 0) return source.slice(start, tokens.lastIndex);
    }
    throw Error(signature);
}
const signatures = [
    'public void draw(', 'private void drawCover(', 'private void drawCloseCover(',
    'private void prepareCloseGeometry(', 'private float transitionProgress(',
    'private static float mediaAlpha(', 'private static float strengthAlpha(',
    'private static float easeInOut(', 'private static float easeOut(', 'private static float fadeDurationMs('
];
if (source.includes('private static float previousLayerAlpha(')) signatures.push('private static float previousLayerAlpha(');
const java = `import java.lang.ref.WeakReference;
public class GlowDismiss {
 static class Bitmap {
  float r,g,b; boolean recycled;
  Bitmap(float r,float g,float b){this.r=r;this.g=g;this.b=b;}
  boolean isRecycled(){return recycled;} int getWidth(){return 256;} int getHeight(){return 144;}
 }
 static class Rect { void set(int a,int b,int c,int d){} }
 static class RectF { float l,t,r,b; void set(float l,float t,float r,float b){this.l=l;this.t=t;this.r=r;this.b=b;} }
 static class PorterDuff { enum Mode { ADD } }
 static class PorterDuffXfermode { PorterDuffXfermode(PorterDuff.Mode m){} }
 static class Paint {
  int alpha; PorterDuffXfermode mode;
  void setFilterBitmap(boolean b){} void setAlpha(int a){alpha=a;}
  void setXfermode(PorterDuffXfermode m){mode=m;}
 }
 static class Canvas {
  float r,g,b,a;
  Canvas(float backgroundAlpha){a=backgroundAlpha;}
  int getWidth(){return 1080;} int getHeight(){return 2400;}
  void drawBitmap(Bitmap image,Rect src,RectF dst,Paint p){
   check(!image.recycled,"Drawing recycled glow");
   check(dst.l<=0 && dst.t<=0 && dst.r>=getWidth() && dst.b>=getHeight(),"Cover geometry");
   float sa=p.alpha/255f;
   if(p.mode!=null){r=Math.min(1,r+image.r*sa);g=Math.min(1,g+image.g*sa);b=Math.min(1,b+image.b*sa);a=Math.min(1,a+sa);}
   else {r=image.r*sa+r*(1-sa);g=image.g*sa+g*(1-sa);b=image.b*sa+b*(1-sa);a=sa+a*(1-sa);}
  }
 }
 static class View {} static class TextureView extends View {} static class ImageReceiver {}
 static class NimarkoConfig { static boolean mediaGlow=true; }
 static class SystemClock { static long now; static long elapsedRealtime(){return now;} }
 static final float VIDEO_FADE_MS=280;
 static final long NEW_MEDIA_CAPTURE_TIMEOUT_MS=1800, PHOTO_RETRY_INTERVAL_MS=120;
 static final PorterDuffXfermode ADD_XFERMODE=new PorterDuffXfermode(PorterDuff.Mode.ADD);
 Bitmap current,previous,pending;
 boolean currentIsVideo=true,previousIsVideo=true,active=true,capturedThisOpen=true,darkTheme;
 int capturingGeneration=-1,captureGeneration,lastDrawWidth,lastDrawHeight;
 long closeFadeStartedAt,fadeStartedAt=1000,mediaChangedAt,lastPhotoCaptureAttemptAt;
 float liveDismiss,closeDismiss,closeTransitionProgress=1;
 boolean closeGeomValid,closePrevGeomValid;
 float closeDstLeft,closeDstTop,closeDstRight,closeDstBottom;
 float closePrevDstLeft,closePrevDstTop,closePrevDstRight,closePrevDstBottom;
 WeakReference<View> containerRef; WeakReference<TextureView> videoTextureRef;
 final Paint coverPaint=new Paint(); final Rect srcRect=new Rect(); final RectF dstRect=new RectF();
 int transitionsFinished;
 boolean isOwner(Object owner){return owner==this;}
 void release(Object owner){throw new AssertionError("Unexpected release");}
 void nmReleaseNow(){throw new AssertionError("Unexpected release");}
 boolean supportsAsyncVideoCapture(){return true;} boolean isViewReady(View v){return v!=null;}
 void maybeCapture(ImageReceiver i,TextureView t,boolean video,int gen){throw new AssertionError("Unexpected capture");}
 void requestFrame(View v){} void finishTransition(){previous=null;transitionsFinished++;}
 boolean startPendingTransition(){return false;}
 ${signatures.map(method).join('\n')}
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 static void near(float actual,float expected,String message){check(Math.abs(actual-expected)<0.008f,message+": "+actual+" != "+expected);}
 static void verify(GlowDismiss glow,Canvas canvas,Bitmap oldFrame,Bitmap newFrame,float p,float dismiss,float background){
  float base=strengthAlpha()*(glow.darkTheme?0.85f:1)*dismiss;
  float oldWeight=base*mediaAlpha(glow.previousIsVideo)*(1-p);
  float newWeight=base*mediaAlpha(glow.currentIsVideo)*p;
  near(canvas.a,oldWeight+newWeight+background*(1-oldWeight-newWeight),"Swipe crossfade opacity flash");
  near(canvas.r,oldFrame.r*oldWeight+newFrame.r*newWeight,"Red flash");
  near(canvas.g,oldFrame.g*oldWeight+newFrame.g*newWeight,"Green flash");
  near(canvas.b,oldFrame.b*oldWeight+newFrame.b*newWeight,"Blue flash");
  check(glow.coverPaint.mode==null,"Paint blend mode leaked");
 }
 public static void main(String[] args){
  int checks=0;
  for(int hz:new int[]{60,90,120,144}) for(boolean dark:new boolean[]{false,true})
   for(boolean oldVideo:new boolean[]{false,true}) for(boolean newVideo:new boolean[]{false,true})
   for(boolean sameFrame:new boolean[]{false,true}) for(int direction:new int[]{-1,1})
   for(int gesture:new int[]{0,1,2,3,4}) {
    for(int frame=0;frame<=hz;frame++) {
     GlowDismiss glow=new GlowDismiss();glow.darkTheme=dark;
     Bitmap oldFrame=new Bitmap(.2f,.6f,.9f), newFrame=sameFrame?new Bitmap(.2f,.6f,.9f):new Bitmap(.9f,.1f,.3f);
     glow.current=newFrame;glow.previous=oldFrame;glow.currentIsVideo=newVideo;glow.previousIsVideo=oldVideo;
     SystemClock.now=1000+(long)((newVideo?VIDEO_FADE_MS:fadeDurationMs())*frame/hz);
     float p=glow.transitionProgress(SystemClock.now);
     float distance=gesture==0?(float)frame/hz:gesture==1?1f-(float)frame/hz:gesture==2?.5f:gesture==3?0:1;
     float translation=direction*270f*distance;
     float dismiss=1-Math.min(Math.abs(translation),270)/270;
     float background=Math.max(127/255f,dismiss);
     Canvas canvas=new Canvas(background);
     glow.draw(glow,canvas,null,null,null,dismiss);
     verify(glow,canvas,oldFrame,newFrame,p,dismiss,background);checks++;
     GlowDismiss close=new GlowDismiss();close.current=newFrame;close.previous=oldFrame;
     close.darkTheme=dark;close.currentIsVideo=newVideo;close.previousIsVideo=oldVideo;
     close.closeFadeStartedAt=1;close.closeTransitionProgress=p;close.closeDismiss=dismiss;
     Canvas closing=new Canvas(background);close.draw(close,closing,null,null,null,1);
     verify(close,closing,oldFrame,newFrame,p,dismiss,background);checks++;
    }
   }
  // The same video frame must not pulse when the refresh ends halfway through a drag.
  GlowDismiss glow=new GlowDismiss();glow.current=new Bitmap(.4f,.7f,.2f);glow.previous=new Bitmap(.4f,.7f,.2f);
  SystemClock.now=1279;Canvas before=new Canvas(.5f);glow.draw(glow,before,null,null,null,.5f);
  SystemClock.now=1280;Canvas atEnd=new Canvas(.5f);glow.draw(glow,atEnd,null,null,null,.5f);
  Canvas after=new Canvas(.5f);glow.draw(glow,after,null,null,null,.5f);
  near(before.a,atEnd.a,"Last refresh frame flash");near(atEnd.a,after.a,"Refresh completion flash");
  near(before.r,after.r,"Unchanged video frame brightness");
  check(glow.transitionsFinished==1,"Finish transition once");
  System.out.println("PASS: "+checks+" vertical swipe/close compositing cases and video-refresh endpoint continuity");
 }
}`;
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-glow-dismiss-'));
fs.writeFileSync(path.join(dir, 'GlowDismiss.java'), java);
cp.execFileSync('javac', [path.join(dir, 'GlowDismiss.java')], {stdio:'inherit'});
cp.execFileSync('java', ['-cp',dir,'GlowDismiss'], {stdio:'inherit'});
