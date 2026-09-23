"""Execute production dot/bin drawing against deterministic clock/renderer stubs."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path
from test_recording_composer_lifecycle import ENTER, method


class RecordDotHandoffTests(unittest.TestCase):
    @unittest.skipUnless(shutil.which('javac'), 'JDK required')
    def test_cold_decode_blink_phases_repeat_and_blend(self):
        body = (method(ENTER, 'private static float getRecordDeleteBlend(')
                + method(ENTER, 'private static float getRecordDotPulseAlpha(')
                + method(ENTER, 'private class RecordDot extends View'))
        java = '''public class DotHandoff {
 static int dp(float v){return Math.round(v);}int getThemedColor(int k){return k;}
 static class R {static class raw {static int chat_audio_record_delete_3=1;}}
 static class Theme {static int key_chat_recordedVoiceDot=1,key_chat_messagePanelBackground=2;}
 static class Context {}
 static class SystemClock {static long now;static long uptimeMillis(){return now;}}
 static class AnimatorSet {int starts;void start(){starts++;}}
 AnimatorSet runningAnimationAudio;
 static class AndroidUtilities {
  static Runnable pending;
  static void cancelRunOnUIThread(Runnable r){if(pending==r)pending=null;}
  static void runOnUIThread(Runnable r,long delay){pending=r;}
 }
 static class Paint {int alpha;void setColor(int c){}void setAlpha(int a){alpha=a;}}
 Paint redDotPaint=new Paint();
 static class Canvas {
  int dotAlpha,binAlpha;float radius,scale;
  void clear(){dotAlpha=binAlpha=0;radius=0;scale=1;}
  void drawCircle(float x,float y,float r,Paint p){dotAlpha=p.alpha;radius=r;}
  void save(){}void restore(){}void scale(float x,float y,float px,float py){scale=x;}
 }
 static class View {
  float viewAlpha=1;
  View(Context c){}void setAlpha(float a){viewAlpha=a;}void invalidate(){}
  int getMeasuredWidth(){return 28;}int getMeasuredHeight(){return 28;}
  protected void onAttachedToWindow(){}protected void onDetachedFromWindow(){}
  protected void onMeasure(int w,int h){}protected void onDraw(Canvas c){}
 }
 static class RLottieDrawable {
  boolean ready,running;int alpha;long readyAt;
  RLottieDrawable(int id,int w,int h,boolean warm,Object o){check(warm,"predecode first frame");}
  void setInvalidateOnProgressSet(boolean b){}void setMasterParent(Object o){}
  void beginApplyLayerColors(){}void setLayerColor(String s,int c){}void commitApplyLayerColors(){}
  void setBounds(int l,int t,int r,int b){}void stop(){running=false;}
  void setProgress(float p){ready=false;}void start(){running=true;ready=false;}
  void updateCurrentFrame(long now,boolean bg){if(now>=readyAt)ready=true;}
  boolean hasBitmap(){return ready;}void setAlpha(int a){alpha=a;}
  void draw(Canvas c){if(ready)c.binAlpha=alpha;}
 }
 BODY
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 public static void main(String[] args){
  for(int hz:new int[]{30,60,90,120,144}){
   DotHandoff h=new DotHandoff();RecordDot d=h.new RecordDot(new Context());Canvas c=new Canvas();
   SystemClock.now=0;d.onAttachedToWindow();d.resetAlpha();int previous=255;
   for(int frame=0;frame<hz*4;frame++){
    SystemClock.now=Math.round(frame*1000f/hz);c.clear();d.onDraw(c);
    check(c.dotAlpha>=89&&c.dotAlpha<=255,"recording dot never disappears between pulses");
    check(Math.abs(previous-c.dotAlpha)<=15,"continuous pulse at low and high refresh rates");
    previous=c.dotAlpha;
   }
   d.resetAlpha();d.enterAnimation=true;
   SystemClock.now+=200;d.onDraw(c);check(c.dotAlpha==255,"entry remains fully visible");
   d.enterAnimation=false;SystemClock.now+=8;d.onDraw(c);
   check(c.dotAlpha>=254,"entry hands off without restarting at a dim phase");
  }
  // The video cancelled near a pulse trough. A real pulse must hand its exact
  // last painted brightness to the deletion, never restart a second blink.
  for(int cancelAt:new int[]{500,580,600,620,700,1100}){
   DotHandoff h=new DotHandoff();RecordDot d=h.new RecordDot(new Context());Canvas c=new Canvas();
   SystemClock.now=0;d.onAttachedToWindow();d.resetAlpha();
   for(int t=0;t<=cancelAt;t+=10){SystemClock.now=t;c.clear();d.onDraw(c);}
   int lastPainted=c.dotAlpha;d.playDeleteAnimation();
   d.drawable.readyAt=SystemClock.now+40;SystemClock.now+=10;c.clear();d.onDraw(c);
   check(c.dotAlpha==lastPainted&&c.binAlpha==0,"hold pulse brightness while decoding");
   SystemClock.now+=40;c.clear();d.onDraw(c);
   check(c.dotAlpha==lastPainted,"first bin frame does not brighten dot");
   for(int t=0;t<=200;t+=10){SystemClock.now+=10;c.clear();d.onDraw(c);
    check(c.dotAlpha<=lastPainted,"dot cannot reappear after cancel");lastPainted=c.dotAlpha;
   }
   check(c.dotAlpha==0&&c.binAlpha==255,"single handoff ends on bin only");
  }
  for(int hz:new int[]{60,90,120,144})for(float brightness:new float[]{0,.1f,.5f,1})for(int delay:new int[]{0,40,180,400}){
   DotHandoff h=new DotHandoff();RecordDot d=h.new RecordDot(new Context());Canvas c=new Canvas();
   SystemClock.now=1000;d.onAttachedToWindow();d.resetAlpha();d.alpha=brightness;
   d.drawable.readyAt=1000+delay;d.playDeleteAnimation();
   AnimatorSet exit=h.runningAnimationAudio=new AnimatorSet();d.startDeleteExit(exit);
   check(d.alpha==brightness,"cancel must not brighten the dot");
   float lastBlend=0;int lastDot=255;
   for(int frame=0;frame<100;frame++){
    SystemClock.now=1000+Math.round(frame*1000f/hz);
    if(SystemClock.now>=1300&&AndroidUtilities.pending!=null)AndroidUtilities.pending.run();
    c.clear();d.onDraw(c);
    if(delay>=300 && SystemClock.now>=1300){
     check(exit.starts==1&&c.binAlpha==0&&d.deleteAnimationAbandoned,"late decoder cannot reintroduce truncated bin");
    }else if(SystemClock.now<1000+delay){
     check(exit.starts==0,"exit countdown waits for decoded frame");
     check(c.binAlpha==0,"no stale bin before decode");
     check(c.dotAlpha==Math.round(255*brightness),"hold actual dot alpha while waiting");
    }else{
     check(exit.starts==1,"exit starts exactly once when bin is ready");
     float p=getRecordDeleteBlend(SystemClock.now-d.deleteBlendStartMs);
     check(p>=lastBlend,"monotonic blend");lastBlend=p;
     check(c.binAlpha==Math.round(255*p),"bin starts from transparent");
     check(c.dotAlpha<=lastDot,"dot never flashes");lastDot=c.dotAlpha;
     check(c.scale>=.8f&&c.scale<=1,"local bin scale bounded");
    }
   }
   if(delay<300)check(c.binAlpha==255&&c.dotAlpha==0,"finished handoff");
   d.setAlpha(0);d.resetAlpha();check(d.viewAlpha==1&&!d.playing&&d.deleteBlendStartMs==-1,"next recording restores view");
   check(AndroidUtilities.pending==null,"no stale fallback after reset");
  }
  DotHandoff h=new DotHandoff();RecordDot d=h.new RecordDot(new Context());
  d.onAttachedToWindow();d.resetAlpha();d.playDeleteAnimation();
  AnimatorSet first=h.runningAnimationAudio=new AnimatorSet();d.startDeleteExit(first);
  Runnable fallback=AndroidUtilities.pending;fallback.run();
  check(first.starts==1&&AndroidUtilities.pending==null,"decode failure releases pending exit once");
  fallback.run();check(first.starts==1,"fallback is idempotent");
  d.resetAlpha();d.playDeleteAnimation();d.startDeleteExit(first);
  h.runningAnimationAudio=new AnimatorSet();AndroidUtilities.pending.run();
  check(first.starts==1,"old run cannot start after replacement");
  d.resetAlpha();d.playDeleteAnimation();d.drawable.readyAt=0;Canvas c=new Canvas();
  d.onDraw(c);SystemClock.now+=80;d.onDraw(c);d.onDetachedFromWindow();
  check(d.deleteAnimationAbandoned&&d.viewAlpha==0,"detach retires displayed handoff");
  d.onAttachedToWindow();c.clear();SystemClock.now+=500;d.onDraw(c);
  check(!d.drawable.running&&c.binAlpha==0&&d.viewAlpha==0,"reattach cannot flash opaque bin");
  d.resetAlpha();check(d.viewAlpha==1&&!d.deleteAnimationAbandoned,"new recording works after reattach");
 }
}'''.replace('BODY', body)
        with tempfile.TemporaryDirectory(prefix='dot-handoff-') as directory:
            file = Path(directory) / 'DotHandoff.java'
            file.write_text(java)
            build = subprocess.run(['javac', str(file)], capture_output=True, text=True)
            self.assertEqual(build.returncode, 0, build.stderr)
            result = subprocess.run(['java', '-cp', directory, 'DotHandoff'], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)

    def test_cancel_exit_fades_without_shrinking_to_a_pixel(self):
        branch = ENTER.split('} else if (recordState == RECORD_STATE_CANCEL || recordState == RECORD_STATE_CANCEL_BY_GESTURE)', 1)[1].split('if (controlsView != null)', 1)[0]
        self.assertIn('ObjectAnimator.ofFloat(recordDot, View.ALPHA, 0f)', branch)
        self.assertNotIn('View.SCALE_X, 0)', branch)
        self.assertIn('View.SCALE_X, .8f)', branch)
