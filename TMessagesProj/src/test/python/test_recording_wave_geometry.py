"""Host/JVM rendering geometry, not an Android/GPU or microphone integration test.

Runs production RecordCircle.onDraw, blob Beziers, smoothing, and coordinate
mapping against small Android stubs. Java2D Area measures the wave left visible
AFTER the opaque core is drawn (checking nominal radii missed the old failure).
Upstream comparison: DrKLO/Telegram origin/master 9552e5541e.
"""
import shutil
import unittest

from test_recording_composer_lifecycle import ENTER, SRC, method
from test_sender_infocard_transitions import run_java


def wave_harness():
    circle = ENTER.split('public class RecordCircle extends View', 1)[1]
    blob = (SRC / 'java/org/telegram/ui/Components/BlobDrawable.java').read_text()
    blob = method(blob, 'public class BlobDrawable').replace(
        'public class BlobDrawable', 'static class BlobDrawable', 1)
    scale_start = circle.index('private static final float RECORDING_VISUAL_SCALE')
    scale = circle[scale_start:circle.index(';', scale_start) + 1]
    helpers = scale + '\n' + '\n'.join(method(circle, signature) for signature in (
        'public void setAmplitude(', 'private void updateWaveGeometry()',
        'private float getWaveScale(', 'protected void onDraw(Canvas canvas)',
        'public void drawWaves(', 'private void drawWaves(', 'private void drawIconInternal(',
        'public void canceledByGesture()', 'private float getCancelProgress()',
        'public void showWaves(',
    ))
    center = method(ENTER, 'private boolean getRecordingButtonCenter(')
    return r'''
import java.util.*;
import java.awt.Shape;
import java.awt.geom.*;
public class Transitions {
 static float density=1;
 static class SystemClock {static long now=1000;static long uptimeMillis(){return now;}}
 static int dp(float x){return (int)Math.ceil(x*density);}
 static float dpf2(float x){return x*density;}
 static float lerp(float a,float b,float p){return a+(b-a)*p;}
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 static void near(float a,float b,float eps,String why){check(Math.abs(a-b)<=eps,why+": "+a+" != "+b);}
 static final int GONE=8;
 static class AndroidUtilities {static int dp2(float x){return (int)Math.floor(x*density);}}
 static class ChatInputViewsContainer {static final int SEPARATED_COMPOSER_SIDE_SIZE=44;}
 static class LiteMode {static final int FLAGS_CHAT=1,FLAG_CALLS_ANIMATIONS=2;
  static boolean enabled=true;static boolean isEnabled(int f){return enabled;}}
 static class WaveDrawable {static final float MAX_AMPLITUDE=1800,animationSpeedCircle=.5f;}
 static class CubicBezierInterpolator {
  static final CubicBezierInterpolator EASE_OUT=new CubicBezierInterpolator(),EASE_BOTH=EASE_OUT;
  float getInterpolation(float x){return x*x*(3-2*x);}
 }
 static class Theme {static final int key_chat_messagePanelVoiceBackground=1,key_chat_recordedVoiceBackground=2;}
 static class ColorUtils {static int blendARGB(int a,int b,float p){return a;}}
 static class Matrix {
  AffineTransform m=new AffineTransform();
  void reset(){m.setToIdentity();}
  void setRotate(float angle,float x,float y){m.setToRotation(Math.toRadians(angle),x,y);}
  void mapPoints(float[] p){m.transform(p,0,p,0,p.length/2);}
  boolean invert(Matrix out){try{out.m=m.createInverse();return true;}catch(NoninvertibleTransformException e){return false;}}
 }
 static class Paint {
  static final int ANTI_ALIAS_FLAG=1;int alpha=255;
  Paint(int flags){}void setColor(int c){alpha=c>>>24;}
  void setAlpha(int a){alpha=a;}int getAlpha(){return alpha;}
 }
 static class Path {
  Path2D.Float p=new Path2D.Float();void reset(){p.reset();}
  void moveTo(float x,float y){p.moveTo(x,y);}
  void cubicTo(float a,float b,float c,float d,float e,float f){p.curveTo(a,b,c,d,e,f);}
 }
 static class Rect {
  int left,top,right,bottom;
  void set(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;}
  int centerX(){return (left+right)/2;}int centerY(){return (top+bottom)/2;}
 }
 static class RectF {void set(float l,float t,float r,float b){}}
 static class Drawable {
  Rect r=new Rect();int alpha=255;
  int getIntrinsicWidth(){return dp(24);}int getIntrinsicHeight(){return dp(24);}
  Rect getBounds(){return r;}void setBounds(Rect b){r=b;}
  void setBounds(int l,int t,int rr,int b){r=new Rect();r.set(l,t,rr,b);}
  void setAlpha(int a){alpha=a;}void draw(Canvas c){c.icons++;c.iconAlpha=alpha;}
 }
 static class Canvas {
  AffineTransform m=new AffineTransform();Deque<AffineTransform> stack=new ArrayDeque<>();
  Area shell=new Area();float cx,cy,r;int coreAlpha,icons,paths,iconAlpha;
  ArrayList<Integer> waveAlphas=new ArrayList<>();
  ArrayList<Rectangle2D> waveBounds=new ArrayList<>();
  void save(){stack.push(new AffineTransform(m));}void restore(){m=stack.pop();}
  void scale(float x,float y,float cx,float cy){m.translate(cx,cy);m.scale(x,y);m.translate(-cx,-cy);}
  void translate(float x,float y){m.translate(x,y);}
  void drawPath(Path p,Paint paint){
   Shape shape=m.createTransformedShape(p.p);
   waveAlphas.add(paint.alpha);waveBounds.add(shape.getBounds2D());
   if(paint.alpha>0){shell.add(new Area(shape));paths++;}
  }
  void drawCircle(float x,float y,float radius,Paint paint){
   cx=x;cy=y;r=radius;coreAlpha=paint.alpha;
   shell.subtract(new Area(m.createTransformedShape(new Ellipse2D.Float(x-radius,y-radius,2*radius,2*radius))));
  }
  float exposedArea(){
   // Integrate the flattened contours, including holes cut by the solid core.
   // This is independent of screen density and avoids sampling every pixel of
   // the much larger upstream waves in each cancellation frame.
   PathIterator it=shell.getPathIterator(null,dpf2(.02f));double[] p=new double[6];
   double x=0,y=0,startX=0,startY=0,twiceArea=0;
   while(!it.isDone()){
    switch(it.currentSegment(p)){
     case PathIterator.SEG_MOVETO:x=startX=p[0];y=startY=p[1];break;
     case PathIterator.SEG_LINETO:twiceArea+=x*p[1]-p[0]*y;x=p[0];y=p[1];break;
     case PathIterator.SEG_CLOSE:twiceArea+=x*startY-startX*y;break;
    }
    it.next();
   }
   return (float)(Math.abs(twiceArea)/2/(density*density));
  }
 }
 static class View {
  static final int GONE=8;
  View parent;int left,top,width,height,scrollX,scrollY,visibility;
  float tx,ty,sx=1,sy=1;Matrix matrix=new Matrix();int frames;
  View(){}View(View p,int l,int t,int w,int h){parent=p;left=l;top=t;width=w;height=h;}
  Object getParent(){return parent;}int getLeft(){return left;}int getTop(){return top;}
  float getX(){return left+tx;}float getY(){return top+ty;}
  int getWidth(){return width;}int getHeight(){return height;}
  int getMeasuredWidth(){return width;}int getMeasuredHeight(){return height;}
  int getScrollX(){return scrollX;}int getScrollY(){return scrollY;}
  Matrix getMatrix(){matrix.reset();matrix.m.translate(tx+width/2f,ty+height/2f);
   matrix.m.scale(sx,sy);matrix.m.translate(-width/2f,-height/2f);return matrix;}
  void invalidate(){}void postInvalidateOnAnimation(){frames++;}
  void setAlpha(float a){}void setVisibility(int v){visibility=v;}
  void drawIn(Canvas c,RectF r,float p){}
 }
 BLOB
 boolean separatedComposerLayout=true,recordingAudioVideo=true,sendButtonVisible,recordIsCanceled;
 final float[] recordingButtonCenter=new float[2];
 final Matrix recordingButtonInverseMatrix=new Matrix();
 View sizeNotifierLayout,host,composer,text,send,audioVideoButtonContainer,audioVideoSendButton=new View();
 CENTER
 class RecordCircle extends View {
  float amplitude,animateToAmplitude,animateAmplitudeDiff,progressToSeekbarStep3,progressToSendButton;
  long lastUpdateTime;
  float scale=1,slideToCancelProgress=1,transformToSeekbar,exitTransition,idleProgress,horizontalPadding;
  int slideDelta,paintAlpha=255;
  boolean skipDraw,canceledByGesture,messageTransitionIsRunning,voiceEnterTransitionInProgress,incIdle,isInVideoMode;
  boolean showWaves=true;float wavesEnterAnimation=1,cancelGestureStartProgress;
  float circleRadius=dpf2(41),circleRadiusAmplitude=dp(30),drawingCx,drawingCy,drawingCircleRadius;
  BlobDrawable tinyWaveDrawable=new BlobDrawable(11,LiteMode.FLAGS_CHAT),bigWaveDrawable=new BlobDrawable(12,LiteMode.FLAGS_CHAT);
  Paint paint=new Paint(1);Rect sendRect=new Rect();RectF rectF=new RectF();
  Drawable micDrawable=new Drawable(),cameraDrawable=new Drawable(),sendDrawable=new Drawable();
  View audioTimelineView;
  RecordCircle(){parent=sizeNotifierLayout;width=sizeNotifierLayout.width;height=dp(194);
   tinyWaveDrawable.random.setSeed(11);bigWaveDrawable.random.setSeed(12);updateWaveGeometry();}
  int getThemedColor(int k){return 0xffaaccff;}void checkDrawables(){}
  boolean isSendButtonVisible(){return sendButtonVisible;}boolean isInVideoMode(){return isInVideoMode;}
  HELPERS
  Canvas frameAfter(long elapsed){SystemClock.now+=elapsed;Canvas c=new Canvas();onDraw(c);return c;}
  Canvas frame(){return frameAfter(16);}
 }
 Transitions(int inset){
  sizeNotifierLayout=new View(null,0,0,dp(400),dp(800));
  host=new View(sizeNotifierLayout,0,0,dp(400),dp(800));
  composer=new View(host,dp(7),dp(800)-inset-dp(7)-dp(44),dp(400)-2*dp(7),dp(44));
  text=new View(composer,0,0,composer.width,dp(44));
  send=new View(text,text.width-dp(100),0,dp(100),dp(44));
  audioVideoButtonContainer=new View(send,send.width-dp(44),0,dp(44),dp(44));
 }
 RecordCircle circle(int inset){RecordCircle r=new RecordCircle();r.left=-dp(3);r.top=dp(800)-inset-dp(7)-r.height;return r;}
 static void waves(){
  for(float d:new float[]{1,1.5f,2.625f,3,3.5f,4}){
   density=d;Transitions t=new Transitions(dp(24));RecordCircle r=t.circle(dp(24));
   // Same production path before lock, while locked, and after microphone samples settle.
   for(boolean locked:new boolean[]{false,true}){
    t.sendButtonVisible=locked;float silent=0,previous=0;
    for(float a:new float[]{0,.1f,.3f,.5f,.8f,1}){
     r.setAmplitude(a*1800);r.amplitude=a;r.bigWaveDrawable.setValue(a);r.tinyWaveDrawable.setValue(a);
     // Freeze only vertex progress to compare amplitude on identical real contours.
     for(BlobDrawable b:new BlobDrawable[]{r.bigWaveDrawable,r.tinyWaveDrawable})Arrays.fill(b.speed,0);
     int frames=r.frames;Canvas c=r.frame();float area=c.exposedArea();
     check(c.paths==2,"two actual blob paths, including locked Send");
     near(c.r,.7f*(dpf2(41)+dp(30)*a),.01f,"whole recording background is 30 percent smaller, including locked Send");
     check(area>75,"visible exposed Bezier area at amplitude "+a+" density "+d+": "+area);
     Rectangle2D bounds=c.shell.getBounds2D();
     float limit=.7f*Math.max(r.bigWaveDrawable.maxRadius*(BlobDrawable.SCALE_BIG_MIN+.98f*a),
      r.tinyWaveDrawable.maxRadius*(BlobDrawable.SCALE_SMALL_MIN+.98f*a))+dpf2(.05f);
     check(bounds.getMinX()>=c.cx-limit && bounds.getMaxX()<=c.cx+limit
      && bounds.getMinY()>=c.cy-limit && bounds.getMaxY()<=c.cy+limit,"actual Beziers stay in upstream envelope");
     check(c.coreAlpha==255,"no opacity workaround");
     if(a==0)silent=area;else check(area>previous+15,"audible amplitude changes exposed area");
     if(a==1)check(area>silent+400,"substantial visible amplitude travel");
     previous=area;check(r.frames==frames+1,"locked/equal sample waves request next frame");
     for(boolean big:new boolean[]{false,true}){
      BlobDrawable b=big?r.bigWaveDrawable:r.tinyWaveDrawable;
      check((b.maxRadius-b.minRadius)*r.getWaveScale(b,big)>dpf2(1.9f),"visible lobes, not near-circles");
      near(r.getWaveScale(b,big),.7f*((big?BlobDrawable.SCALE_BIG_MIN:BlobDrawable.SCALE_SMALL_MIN)+.98f*a),
       .002f,"entire wave envelope shrinks with recording background");
     }
     // External transition renderer uses the identical shells and origin.
     Canvas transition=new Canvas();r.drawWaves(transition,40,50,.5f);
     check(transition.paths==2,"transition draws both shells");
    }
   }
   // Actual upstream attack/release plus contour interpolation continues while locked.
   r.bigWaveDrawable.generateBlob();r.tinyWaveDrawable.generateBlob();
   t.sendButtonVisible=true;r.setAmplitude(0);
   for(int i=0;i<40;i++)r.frame();
   r.setAmplitude(1800);float before=r.bigWaveDrawable.amplitude;
   r.frame();check(r.bigWaveDrawable.amplitude>before,"microphone drives locked wave");
   check(r.bigWaveDrawable.amplitude>r.tinyWaveDrawable.amplitude,"independent upstream attack speeds");
   for(int i=0;i<40;i++)r.frame();
   near(r.bigWaveDrawable.amplitude,1,.001f,"attack reaches target");
   float vertexProgress=r.bigWaveDrawable.progress[0];r.frame();
   check(r.bigWaveDrawable.progress[0]!=vertexProgress,"settled locked amplitude still morphs contour");
   r.setAmplitude(0);r.frame();check(r.bigWaveDrawable.amplitude<1,"release reacts");
   t.recordingAudioVideo=false;int frames=r.frames;r.frame();check(r.frames==frames,"cancel stops self scheduling");
   LiteMode.enabled=false;check(r.frame().paths==0,"respect chat animation setting");LiteMode.enabled=true;
  }
 }
 static void origins(){
  for(float d:new float[]{1,1.5f,2.625f,3,3.5f,4})for(int insetDp:new int[]{0,24,280}){
   density=d;int inset=dp(insetDp);Transitions t=new Transitions(inset);RecordCircle r=t.circle(inset);
   t.sendButtonVisible=true;
   // The normal glass island and record/send share this center; old code was -3dp/-2dp.
   float expectedX=dp(400)-dp(7)-dp(44)/2f;
   float expectedY=dp(800)-inset-dp(7)-dp(44)/2f;
   Canvas first=r.frame();near(first.cx+r.left,expectedX,.51f,"trailing background X");
   near(first.cy+r.top,expectedY,.51f,"trailing background Y");
   near(r.sendRect.centerX(),first.cx,.01f,"send icon and wave center X");
   near(r.sendRect.centerY(),first.cy,.01f,"send icon and wave center Y");
   // Exercise ancestor scrolling, live translations and the overlay's independent translation.
   t.host.scrollX=dp(2);t.host.scrollY=dp(5);t.composer.ty=-dpf2(13.25f);
   t.send.tx=dpf2(7.25f);r.tx=dpf2(7.25f);r.ty=-dpf2(1.5f);
   t.sizeNotifierLayout.scrollX=dp(1);t.sizeNotifierLayout.scrollY=dp(3);
   expectedX+=t.send.tx-t.host.scrollX-t.sizeNotifierLayout.scrollX;
   expectedY+=t.composer.ty-t.host.scrollY-t.sizeNotifierLayout.scrollY;
   t.recordingAudioVideo=false;
   for(int hz:new int[]{60,90,120,144})for(int frame=0;frame<=hz;frame++){
    float p=frame/(float)hz;
    // Cancel sets button scale to zero before restoring it; center must NOT use an inverse of it.
    t.audioVideoButtonContainer.sx=t.audioVideoButtonContainer.sy=p;
    r.exitTransition=p;Canvas c=r.frame();
    near(c.cx+r.getX()-t.sizeNotifierLayout.scrollX,expectedX,.51f,"cancel concentric X");
    near(c.cy+r.getY()-t.sizeNotifierLayout.scrollY,expectedY,.51f,"cancel concentric Y");
    check(c.r<=dpf2(41),"no cancel size jump");
   }
   r.exitTransition=0;r.slideDelta=-dp(40);Canvas dragged=r.frame();
   near(dragged.cx+r.getX()-t.sizeNotifierLayout.scrollX,expectedX-dp(40),.51f,"gesture offset applied exactly once");
   t.separatedComposerLayout=false;r.slideDelta=0;r.amplitude=.5f;r.animateToAmplitude=.5f;
   Canvas nativeFrame=r.frame();near(nativeFrame.cx,r.width-AndroidUtilities.dp2(26),.01f,"native X unchanged");
   near(nativeFrame.cy,dp(170),.01f,"native Y unchanged");
   near(nativeFrame.r,.7f*(dpf2(41)+dp(30)*.5f),.01f,"native layout uses same compact recording radius");
   r.bigWaveDrawable.amplitude=.5f;
   near(r.getWaveScale(r.bigWaveDrawable,true),.7f*(BlobDrawable.SCALE_BIG_MIN+.98f*.5f),.001f,"same reduced wave gain in native layout");
  }
 }
 static void frameTiming(){
  density=1;Transitions t=new Transitions(dp(24));RecordCircle r=t.circle(dp(24));
  t.sendButtonVisible=true;r.incIdle=true;r.setAmplitude(1800);
  // First draw and resumed draw must not spend minutes of amplitude/send travel.
  r.frameAfter(0);
  check(r.amplitude>0 && r.amplitude<.1f,"first frame amplitude is bounded");
  check(r.progressToSendButton>0 && r.progressToSendButton<.2f,"first send morph is bounded");
  SystemClock.now+=60000;r.frameAfter(0);
  check(r.amplitude<.2f,"resumed frame amplitude is bounded");
  check(r.progressToSendButton<.5f,"resumed send morph is bounded");
  // Integrate the entry envelope by elapsed time, not by display frame count.
  r.wavesEnterAnimation=0;r.lastUpdateTime=SystemClock.now;
  r.frameAfter(16);float p60=r.wavesEnterAnimation;
  r.frameAfter(8);float p120=r.wavesEnterAnimation-p60;
  near(p60,2*p120,.002f,"120 Hz wave entry keeps the 60 Hz speed");
  r.incIdle=true;r.idleProgress=0;r.lastUpdateTime=SystemClock.now;
  r.frameAfter(16);float idle60=r.idleProgress;
  r.frameAfter(8);float idle120=r.idleProgress-idle60;
  near(idle60,2*idle120,.002f,"idle pulse keeps the same speed");
 }
 public static void main(String[] args){waves();origins();frameTiming();System.out.println("PASS: wave areas, lock, smoothing, cancel centers and timing");}
}
'''.replace('BLOB', blob).replace('CENTER', center).replace('HELPERS', helpers)


class RecordingWaveGeometryTests(unittest.TestCase):
    @unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'JDK required')
    def test_production_waves_and_cancel_origins(self):
        run_java(wave_harness())

    @unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'JDK required')
    def test_rejects_unbounded_or_frame_counted_wave_timing(self):
        java = wave_harness()
        for before, after, failure in (
            ('Math.min(32, Math.max(0, now - lastUpdateTime))',
             'Math.max(0, now - lastUpdateTime)', 'resumed frame amplitude is bounded'),
            ('wavesEnterAnimation += 0.04f * dt / 16.6667f;',
             'wavesEnterAnimation += 0.04f;', '120 Hz wave entry'),
        ):
            self.assertIn(before, java)
            with self.assertRaisesRegex(AssertionError, failure):
                run_java(java.replace(before, after))

    def test_recording_controls_and_cancel_hint_share_bounded_clock(self):
        controls = ENTER.split('public class ControlsView extends FrameLayout', 1)[1]
        controls = controls.split('public class RecordCircle extends View', 1)[0]
        slide = ENTER.split('private class SlideTextView extends View', 1)[1]
        for source in (controls, slide):
            self.assertIn('SystemClock.uptimeMillis()', source)
            self.assertIn('lastUpdateTime == 0 ? 16 : Math.min(32, Math.max(0, now - lastUpdateTime))', source)

    @unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'JDK required')
    def test_regression_rejects_previous_compact_wave_formula(self):
        java = wave_harness()
        geometry = method(java, 'private void updateWaveGeometry()')
        java = java.replace(geometry, '''private void updateWaveGeometry() {
            tinyWaveDrawable.minRadius=dp(25);
            tinyWaveDrawable.maxRadius=dp(25)+dp(1.5f)*BlobDrawable.FORM_SMALL_MAX;
            bigWaveDrawable.minRadius=dp(26);
            bigWaveDrawable.maxRadius=dp(26)+dp(1.5f)*BlobDrawable.FORM_BIG_MAX;
            tinyWaveDrawable.generateBlob();bigWaveDrawable.generateBlob();
        }''')
        wave_scale = method(java, 'private float getWaveScale(')
        java = java.replace(wave_scale, '''private float getWaveScale(BlobDrawable wave, boolean big) {
            return (big ? BlobDrawable.SCALE_BIG_MIN : BlobDrawable.SCALE_SMALL_MIN)
                    + .18f * wave.amplitude;
        }''')
        # The old nominal-radius assertions passed this design. The actual
        # visible contour checks must reject it; do not turn them into snapshots.
        with self.assertRaisesRegex(AssertionError, 'visible exposed Bezier area|visible lobes'):
            run_java(java)

    @unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'JDK required')
    def test_regression_rejects_fixed_button_and_narrow_wave_slot(self):
        java = wave_harness()
        for broken in (
            java.replace('(circleRadius + circleRadiusAmplitude * amplitude)', 'dp(22)'),
            java.replace(method(java, 'private float getWaveScale('), '''
              private float getWaveScale(BlobDrawable wave, boolean big) {
                return dpf2((big ? 25 : 24) + 4f * wave.amplitude) / wave.maxRadius;
              }'''),
        ):
            self.assertNotEqual(java, broken)
            with self.assertRaisesRegex(AssertionError, 'whole recording background|visible exposed Bezier area'):
                run_java(broken)

    def test_rejects_gain_only_reduction(self):
        java = wave_harness()
        broken = java.replace('RECORDING_VISUAL_SCALE = 0.7f', 'RECORDING_VISUAL_SCALE = 1f')
        self.assertNotEqual(java, broken)
        with self.assertRaisesRegex(AssertionError, 'whole recording background'):
            run_java(broken)

    @unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'JDK required')
    def test_regression_rejects_previous_cancel_origin(self):
        java = wave_harness().replace('waves();origins();', 'origins();')
        center = method(java, 'private boolean getRecordingButtonCenter(')
        java = java.replace(center, '''private boolean getRecordingButtonCenter(View overlay, float[] point) {
            point[0]=overlay.getMeasuredWidth()-AndroidUtilities.dp2(29);
            point[1]=dp(170);
            return true;
        }''')
        with self.assertRaisesRegex(AssertionError, 'trailing background X'):
            run_java(java)

    def test_lock_and_normal_draw_share_wave_path(self):
        circle = ENTER.split('public class RecordCircle extends View', 1)[1]
        draw = method(circle, 'protected void onDraw(Canvas canvas)')
        self.assertIn('drawWaves(canvas, cx + slideDelta, cy, 1f - progressToSeekbarStep1)', draw)
        self.assertNotIn('0.18f', circle)
        wave_block = draw.split('if (LiteMode.isEnabled(LiteMode.FLAGS_CHAT) &&', 1)[1].split(
            'if (canceledByGesture && slideToCancelProgress < 1f)', 1)[0]
        self.assertNotIn('sendButtonVisible', wave_block)
        self.assertNotIn('isRecordLocked', wave_block)
        self.assertIn('postInvalidateOnAnimation()', wave_block)
        controls = ENTER.split('class ControlsView', 1)[1].split('public class RecordCircle', 1)[0]
        self.assertIn('getRecordingButtonCenter(this, recordingButtonCenter)', controls)


if __name__ == '__main__':
    unittest.main()
