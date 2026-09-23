"""Cancel envelopes run through production RecordCircle/BlobDrawable on the JVM.

No APK build: Android Canvas/View/animators are host stubs, not GPU/device tests.
The existing animator properties are sampled at 60/90/120/144 Hz. Geometry,
paint alpha, owner gates and the gesture handoff execute actual Java methods.
"""
import shutil
import unittest

from test_recording_composer_lifecycle import ENTER, SRC, method
from test_recording_wave_geometry import wave_harness
from test_sender_infocard_transitions import run_java


def exit_harness():
    # Use the real cubic curve (not the geometry harness's smoothstep stand-in).
    # Only unused Android PathInterpolator constants are omitted from the host.
    source = (SRC / 'java/org/telegram/ui/Components/CubicBezierInterpolator.java').read_text()
    curve = method(source, 'public class CubicBezierInterpolator').replace(
        'public class CubicBezierInterpolator', 'static class CubicBezierInterpolator', 1)
    curve = '\n'.join(line for line in curve.splitlines()
                      if not line.strip().startswith('public static final Interpolator '))
    curve = '''interface Interpolator {float getInterpolation(float p);}
 static class PointF {float x,y;PointF(){}PointF(float a,float b){x=a;y=b;}}
 ''' + curve
    checks = r'''
 static RecordCircle ready(Transitions t) {
  RecordCircle r=t.circle(dp(24));
  r.amplitude=r.animateToAmplitude=.8f;r.progressToSendButton=1;
  for(BlobDrawable b:new BlobDrawable[]{r.bigWaveDrawable,r.tinyWaveDrawable}){
   b.setValue(.8f);Arrays.fill(b.speed,0);
  }
  r.bigWaveDrawable.paint.setAlpha(99);r.tinyWaveDrawable.paint.setAlpha(37);
  return r;
 }
 static void cancelFrames(boolean gesture,float start,boolean locked,boolean show) {
  for(float d:new float[]{1,2.625f,4})for(int hz:new int[]{60,90,120,144}){
   density=d;Transitions t=new Transitions(dp(24));t.sendButtonVisible=locked;
   RecordCircle r=ready(t);r.slideToCancelProgress=start;
   // Cancel before a loud sample has finished its attack. A fixed-core test
   // would miss this: all three reactive amplitudes must freeze at handoff.
   r.setAmplitude(1800);
   r.slideDelta=-(int)(dp(140)*(1-start));
   r.showWaves(show,false);
   Canvas before=r.frame();
   float[] widths={(float)before.waveBounds.get(0).getWidth(),(float)before.waveBounds.get(1).getWidth()};
   t.recordingAudioVideo=false;t.recordIsCanceled=true;
   if(gesture)r.canceledByGesture();
   Canvas handoff=r.frame();
   check(handoff.paths==2,"cancel handoff retains both contours");
   near(handoff.r,before.r,.002f,"no radius pop on gesture handoff");
   near((float)handoff.waveBounds.get(0).getWidth(),widths[0],.002f,"no wave pop on handoff");
   check(handoff.coreAlpha==before.coreAlpha,"no alpha pop on handoff");
   // A decoder/delay may hold progress at zero AFTER capture stops. Keep drawing
   // until the existing exit owner advances, including showWaves=false (video warm-up).
   int frames=r.frames;r.frame();check(r.frames==frames+1,"cancel delay retains animation owner");
   int count=(int)Math.ceil((gesture?200:360)*hz/1000f);
   float lastRadius=before.r,lastWidth=widths[0];int lastAlpha=255;
   for(int i=0;i<=count;i++){
    if(i==count/2)r.setAmplitude(0); // queued final sample after recording stops
    float fraction=i/(float)count;
    // ObjectAnimator's default accelerate/decelerate curve; gesture uses EASE_BOTH.
    float p=gesture?CubicBezierInterpolator.EASE_BOTH.getInterpolation(fraction)
      :(float)(.5-.5*Math.cos(Math.PI*fraction));
    if(gesture){r.slideToCancelProgress=start+(1-start)*p;
     r.slideDelta=-(int)(dp(140)*(1-r.slideToCancelProgress));}
    else r.exitTransition=p;
    Canvas c=r.frame();
    check(c.waveBounds.size()==2,"no boolean gate may drop the cancelling contours at p="+p);
    near(c.r,before.r*(1-p),.002f,"core uses full cancel duration");
    check(c.r<=lastRadius+.002f && c.coreAlpha<=lastAlpha,"no core rebound");
    near(c.coreAlpha,255*(1-p),1.01f,"continuous core fade");
    near(c.iconAlpha,255*(1-p),1.01f,"icon fades with core, including gesture");
    for(int k=0;k<2;k++){
     near((float)c.waveBounds.get(k).getWidth(),widths[k]*(1-p),.003f,"wave/core share envelope");
     near(c.waveAlphas.get(k),(k==0?99:37)*(1-p),.51f,"theme alpha fades continuously");
    }
    check(c.waveBounds.get(0).getWidth()<=lastWidth+.003f,"returning gesture cannot regrow waves");
    check(r.bigWaveDrawable.paint.getAlpha()==99 && r.tinyWaveDrawable.paint.getAlpha()==37,
     "draw restores both theme paints");
    if(!gesture && show && p>.2f && p<.5f)check(c.exposedArea()>5,"shell not swallowed by solid core");
    lastRadius=c.r;lastAlpha=c.coreAlpha;lastWidth=(float)c.waveBounds.get(0).getWidth();
   }
   Canvas end=r.frame();check(end.paths==0 && end.coreAlpha==0 && end.r==0,"fully transparent before cleanup");
   frames=r.frames;r.frame();check(r.frames==frames,"finished cancel stops scheduling");
   // New recording/external send owner must never inherit a multiplied-down paint.
   t.recordIsCanceled=false;t.recordingAudioVideo=true;r.canceledByGesture=false;
   r.exitTransition=0;r.slideToCancelProgress=1;r.slideDelta=0;r.visibility=0;r.showWaves(true,false);
   Canvas next=r.frame();check(next.paths==2 && next.coreAlpha==255,"next recording restored");
   check(next.waveAlphas.get(0)==99 && next.waveAlphas.get(1)==37,"next theme alpha restored");
  }
 }
 static void gates(){
  density=1;Transitions t=new Transitions(dp(24));RecordCircle r=ready(t);
  t.recordingAudioVideo=false;t.recordIsCanceled=true;r.exitTransition=.55f;
  // LiteMode disables waves, not the smooth core fade.
  LiteMode.enabled=false;Canvas lite=r.frame();check(lite.paths==0,"LiteMode respected");
  near(lite.r,.7f*(dpf2(41)+dp(30)*.8f)*.45f,.002f,"LiteMode keeps compact core envelope");LiteMode.enabled=true;
  r.skipDraw=true;check(r.frame().paths==0,"skipDraw owner respected");r.skipDraw=false;
  r.voiceEnterTransitionInProgress=true;int frames=r.frames;Canvas owned=r.frame();
  check(owned.paths==0 && owned.icons==0 && r.frames==frames,"external send owner respected");
  Canvas external=new Canvas();r.drawWaves(external,40,50,.75f);
  check(external.paths==2 && external.waveAlphas.get(0)==99,"external draw has independent alpha");
  r.voiceEnterTransitionInProgress=false;
  // Existing preview/send staging is not replaced by cancellation staging.
  r.audioTimelineView=new View();r.audioTimelineView.parent=t.sizeNotifierLayout;
  r.transformToSeekbar=.8f;check(r.frame().paths==0,"preview still owns the seekbar phase");
  r.transformToSeekbar=0;r.messageTransitionIsRunning=true;r.exitTransition=.9f;
  check(r.frame().paths==0,"message transition retains original gate");r.messageTransitionIsRunning=false;
  t.separatedComposerLayout=false;r.exitTransition=.9f;
  check(r.frame().paths==0,"native exit staging unchanged");
  r.exitTransition=0;r.slideToCancelProgress=.5f;r.canceledByGesture();
  check(r.frame().paths==0,"native gesture gate unchanged");
 }
 static void edgeCases(){
  density=1;Transitions t=new Transitions(dp(24));RecordCircle r=ready(t);
  // Cancelling while waves are still entering must not continue the entry ramp
  // during the bin decoder/490ms delay, or the outgoing effect grows first.
  r.showWaves(false,false);r.showWaves(true,true);Canvas before=r.frame();
  float enter=r.wavesEnterAnimation;
  t.recordingAudioVideo=false;t.recordIsCanceled=true;
  for(int i=0;i<30;i++){
   Canvas c=r.frame();near(r.wavesEnterAnimation,enter,0,"cancel freezes partial wave entry");
   near((float)c.waveBounds.get(0).getWidth(),(float)before.waveBounds.get(0).getWidth(),.002f,
    "delayed cancel cannot grow partially entered shell");
  }
  // Full left drag reaches exactly zero before cancellation is requested.
  t.recordIsCanceled=false;r.slideToCancelProgress=0;before=r.frame();
  check(before.paths==0,"full drag already hid waves");
  t.recordIsCanceled=true;r.canceledByGesture();
  Canvas handoff=r.frame();near(handoff.r,before.r,.002f,"full drag core is continuous");
  for(int i=1;i<=20;i++){
   r.slideToCancelProgress=i/20f;Canvas c=r.frame();
   for(Rectangle2D b:c.waveBounds)near((float)b.getWidth(),0,.001f,"zero-drag waves never reappear");
   near(c.r,before.r*(1-i/20f),.002f,"full drag core fades normally");
  }
  // Degenerate/out-of-range values stay bounded, including reuse after cancellation.
  for(float start:new float[]{0,.999999f,1}){
   r.slideToCancelProgress=start;r.canceledByGesture();
   check(Float.isFinite(r.getCancelProgress()),"no zero-distance division");
   r.slideToCancelProgress=1;near(r.getCancelProgress(),1,0,"gesture endpoint clamped");
  }
  r.canceledByGesture=false;r.exitTransition=-1;near(r.getCancelProgress(),0,0,"lower bound");
  r.exitTransition=2;near(r.getCancelProgress(),1,0,"upper bound");
 }
 public static void main(String[] args){
  for(boolean locked:new boolean[]{false,true})for(boolean show:new boolean[]{false,true}){
   cancelFrames(false,1,locked,show);
   for(float start:new float[]{.2f,.69f,.9f})cancelFrames(true,start,locked,show);
  }
  gates();edgeCases();System.out.println("PASS: cancel envelopes, gesture handoff, alpha, owners, refresh rates");
 }
'''
    java = wave_harness()
    java = java.replace(method(java, 'static class CubicBezierInterpolator'), curve)
    main = method(java, 'public static void main(String[] args)')
    return java.replace(main, checks)


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'JDK required')
class RecordingWaveExitTests(unittest.TestCase):
    def test_production_cancel_frames(self):
        run_java(exit_harness())

    def test_rejects_amplitude_attack_continuing_during_cancel(self):
        java = exit_harness().replace(
            'if (!smoothCancel && animateToAmplitude != amplitude)',
            'if (animateToAmplitude != amplitude)')
        self.assertNotEqual(java, exit_harness())
        with self.assertRaisesRegex(AssertionError, 'radius pop|core uses full cancel duration'):
            run_java(java)

    def test_rejects_wave_attack_continuing_during_cancel(self):
        java = exit_harness().replace(
            'if (!smoothCancel) {\n                    bigWaveDrawable.updateAmplitude(dt);',
            'if (true) {\n                    bigWaveDrawable.updateAmplitude(dt);')
        self.assertNotEqual(java, exit_harness())
        with self.assertRaisesRegex(AssertionError, 'wave pop on handoff|wave/core share envelope'):
            run_java(java)

    def test_rejects_abrupt_wave_gate(self):
        java = exit_harness().replace(
            '(smoothCancel || (exitProgress2 < 0.4f && !canceledByGesture))',
            '(exitProgress2 < 0.4f && !canceledByGesture)')
        self.assertNotEqual(java, exit_harness())
        with self.assertRaisesRegex(AssertionError, 'boolean gate|handoff retains'):
            run_java(java)

    def test_rejects_core_only_final_stage(self):
        java = exit_harness().replace(
            'radius *= 1f - cancelProgress;',
            'radius *= 1f - Math.max(0f, (cancelProgress - .6f) / .4f);')
        self.assertNotEqual(java, exit_harness())
        with self.assertRaisesRegex(AssertionError, 'core uses full cancel duration'):
            run_java(java)

    def test_rejects_paint_alpha_leaking_into_next_frame(self):
        java = exit_harness().replace('bigWaveDrawable.paint.setAlpha(bigAlpha);', '')
        self.assertNotEqual(java, exit_harness())
        with self.assertRaisesRegex(AssertionError, 'restores both theme paints'):
            run_java(java)

    def test_rejects_gesture_wave_regrowth(self):
        java = exit_harness().replace(
            '? cancelGestureStartProgress : slideToCancelProgress;',
            '? slideToCancelProgress : slideToCancelProgress;')
        self.assertNotEqual(java, exit_harness())
        with self.assertRaisesRegex(AssertionError, 'wave/core share envelope'):
            run_java(java)

    def test_rejects_wave_entry_continuing_after_cancel(self):
        java = exit_harness().replace('showWaves && !smoothCancel', 'showWaves')
        self.assertNotEqual(java, exit_harness())
        with self.assertRaisesRegex(AssertionError, 'cancel freezes partial wave entry'):
            run_java(java)

    def test_existing_animators_keep_ownership_and_timing(self):
        entry = method(ENTER, 'protected void updateRecordInterface(')
        gesture = entry.split('if (recordState == RECORD_STATE_CANCEL_BY_GESTURE) {', 1)[1]
        self.assertLess(gesture.index('recordCircle.canceledByGesture();'),
                        gesture.index('"slideToCancelProgress", 1f).setDuration(200)'))
        self.assertIn('recordCircleAnimator.setDuration(360)', gesture)
        self.assertIn('recordCircleAnimator.setStartDelay(490)', gesture)
        self.assertIn('recordDot.startDeleteExit(runningAnimationAudio)', entry)
        circle = ENTER.split('public class RecordCircle extends View', 1)[1]
        reset = method(circle, 'public void resetLockTranslation(')
        self.assertIn('canceledByGesture = false', reset)
        self.assertIn('cancelGestureStartProgress = 0f', reset)
        self.assertIn('exitTransition = 0', reset)
        self.assertNotIn('ValueAnimator', method(circle, 'private float getCancelProgress()'))


if __name__ == '__main__':
    unittest.main()
