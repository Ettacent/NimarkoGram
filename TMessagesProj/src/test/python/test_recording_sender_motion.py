"""Execute production sender helpers and slot measurement on JVM stubs (no APK).

The harness models cancel/end ordering, not Android rendering or bin timing.
"""
import shutil
import unittest

from test_recording_composer_lifecycle import ENTER, method
from test_sender_infocard_transitions import run_java


def sender_harness():
    helpers = '\n'.join(method(ENTER, signature) for signature in (
        'private float getSenderHiddenTranslationX()',
        'private void cancelRecordingSenderAnimator()',
        'private boolean hasRecordingSurface()',
        'private void updateRecordingSenderVisibility(',
        'private void finishRecordingSenderVisibility(',
        'private void setEmojiSenderTransitionX(',
    ))
    update = method(ENTER, 'public void updateSendAsButton(boolean forceHide, boolean animated)')
    takeover = update[update.index('        if (!recordingSenderSlotReserved'):update.index('        boolean wasVisible')]
    measure = method(ENTER[ENTER.index('int botCommandLastPosition'):], 'protected void onMeasure(')
    measure = measure[measure.index('        if (separatedComposerLayout)'):measure.index('        updateBotCommandsMenuContainerTopPadding();')]
    watch = ENTER[ENTER.index('    private ViewTreeObserver recordingSenderRestoreObserver;'):
                  ENTER.index('    private float getSenderHiddenTranslationX()')]
    return r'''import java.util.function.Consumer;
public class Transitions {
 static final int VISIBLE=0,INVISIBLE=4,GONE=8;
 static float density;
 static int dp(float n){return (int)Math.ceil(n*density);}
 static class Animator {}
 static class AnimatorSet {boolean running;boolean isRunning(){return running;}}
 static class ViewTreeObserver {
  interface OnPreDrawListener {boolean onPreDraw();}
  OnPreDrawListener listener;boolean alive=true;
  boolean isAlive(){return alive;}
  void addOnPreDrawListener(OnPreDrawListener l){check(listener==null,"single watcher");listener=l;}
  void removeOnPreDrawListener(OnPreDrawListener l){if(listener==l)listener=null;}
  void draw(){if(listener!=null)listener.onPreDraw();}
 }
 static class AnimatorListenerAdapter {
  public void onAnimationEnd(Animator a){} public void onAnimationCancel(Animator a){}
 }
 static class ValueAnimator extends Animator {
  float value;Consumer<ValueAnimator> update;AnimatorListenerAdapter listener;boolean cancelled;
  static ValueAnimator ofFloat(float a,float b){return new ValueAnimator();}
  void removeAllListeners(){listener=null;}void removeAllUpdateListeners(){update=null;}
  void cancel(){cancelled=true;if(listener!=null){listener.onAnimationCancel(this);listener.onAnimationEnd(this);}}
  void setDuration(int n){}void setInterpolator(Object o){}void start(){frame(0);}
  void addUpdateListener(Consumer<ValueAnimator> c){update=c;}void addListener(AnimatorListenerAdapter l){listener=l;}
  Object getAnimatedValue(){return value;}
  void frame(float p){value=p;if(update!=null)update.accept(this);}
  void end(){frame(1);if(listener!=null)listener.onAnimationEnd(this);}
 }
 static class CubicBezierInterpolator {static final Object EASE_OUT=new Object();}
 static class MarginLayoutParams {int width,height,leftMargin;}
 static class FrameLayout {static class LayoutParams extends MarginLayoutParams {}}
 static class MeasureSpec {static final int EXACTLY=1;static int makeMeasureSpec(int n,int mode){return n;}}
 static class ChatInputViewsContainer {static final int SEPARATED_COMPOSER_SIDE_SIZE=44,SEPARATED_COMPOSER_GAP=8;}
 static class View {
  static final int VISIBLE=0;
  float alpha=1,x;int visibility=VISIBLE;Object tag;
  FrameLayout.LayoutParams params=new FrameLayout.LayoutParams();
  void setTranslationX(float v){x=v;}float getTranslationX(){return x;}
  void setVisibility(int v){visibility=v;}int getVisibility(){return visibility;}
  float getAlpha(){return alpha;}void setAlpha(float a){alpha=a;}
  Object getTag(){return tag;}void setTag(Object o){tag=o;}
  FrameLayout.LayoutParams getLayoutParams(){return params;}
  Object getParent(){return null;}void measure(int w,int h){}int getMeasuredWidth(){return params.width;}
 }
 View senderSelectView=new View(),emojiButton=new View(),messageEditText=new View(),richDraftPreview=new View();
 View recordPanel=new View(),recordedAudioPanel=new View(),deleteRichDraftButton=new View();
 View recordDot=new View();AnimatorSet runningAnimationAudio=new AnimatorSet();
 View botCommandsMenuButton,aiButton,separatedComposerLeadingContainer;
 boolean recordingSenderSlotReserved,recordingSenderTargetVisible,recording,separatedComposerLayout,recordIsCanceled,destroyed;
 float messageTextTranslationX;
 ValueAnimator recordingSenderAnimator;int layouts;
 ViewTreeObserver observer=new ViewTreeObserver();
 ViewTreeObserver getViewTreeObserver(){return observer;}
 boolean isRecordingAudioVideo(){return recording;}void requestLayout(){layouts++;}
 HELPERS
 WATCH
 void updateSendAsButton(){update(true,true);}
 void update(boolean isVisible,boolean animated){TAKEOVER}
 void measure(){int widthMeasureSpec=0,heightMeasureSpec=0;MEASURE}
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 static void near(float a,float b,String why){check(Math.abs(a-b)<.002f,why+": "+a+" != "+b);}
 Transitions(boolean separated){
  separatedComposerLayout=separated;senderSelectView.params.width=dp(36);
  senderSelectView.params.height=dp(36);senderSelectView.params.leftMargin=dp(4.66f);
  recordPanel.visibility=recordedAudioPanel.visibility=GONE;
 }
 void assertSlot(int text,int emoji,int draft){
  measure();check(messageEditText.params.leftMargin==text,"hint slot must not jump");
  check(emojiButton.params.leftMargin==emoji,"emoji slot must not jump");
  check(richDraftPreview.params.leftMargin==draft,"draft slot must not jump");
 }
 static void exercise(boolean separated){
  Transitions t=new Transitions(separated);t.measure();
  int text=t.messageEditText.params.leftMargin,emoji=t.emojiButton.params.leftMargin,draft=t.richDraftPreview.params.leftMargin;
  float travel=t.getSenderHiddenTranslationX();
  near(travel,-dp(36)-dp(4.66f)-dp(2),"original native avatar travel");
  t.recording=true;t.recordPanel.visibility=VISIBLE;t.update(false,true);
  ValueAnimator hide=t.recordingSenderAnimator;
  check(t.recordingSenderSlotReserved,"entry reserves slot");
  hide.frame(.5f);near(t.senderSelectView.alpha,.5f,"hide opacity");
  near(t.senderSelectView.x,travel/2,"horizontal departure");t.assertSlot(text,emoji,draft);
  // Fast stop reverses departure without forcing either endpoint first.
  t.recording=false;t.recordPanel.visibility=GONE;t.update(true,true);
  near(t.senderSelectView.alpha,.5f,"departure reversal alpha");
  near(t.senderSelectView.x,travel/2,"departure reversal X");
  hide.end();near(t.senderSelectView.x,travel/2,"retired hide end cannot move arrival");
  t.recording=true;t.recordPanel.visibility=VISIBLE;t.update(false,true);
  hide=t.recordingSenderAnimator;
  t.update(false,true);check(t.recordingSenderAnimator==hide,"duplicate hide keeps clock");hide.end();
  check(t.senderSelectView.visibility==INVISIBLE,"hidden pixels keep slot");
  near(t.senderSelectView.x,travel,"offscreen endpoint");t.assertSlot(text,emoji,draft);
  t.recording=false;
  // Recorder stopped, but the bin is still visible: even an early show request waits.
  t.update(true,true);near(t.senderSelectView.alpha,0,"no bin overlap");
  check(t.recordingSenderAnimator==null,"no arrival during bin");
  t.recordPanel.visibility=GONE;t.recordedAudioPanel.visibility=VISIBLE;
  t.update(true,true);near(t.senderSelectView.alpha,0,"no preview overlap");
  t.recordedAudioPanel.visibility=GONE;t.update(true,true);
  ValueAnimator show=t.recordingSenderAnimator;
  near(t.senderSelectView.alpha,0,"arrival cannot flash opaque");
  near(t.senderSelectView.x,travel,"arrival cannot reset position");
  for(int hz:new int[]{60,90,120,144}){
   float last=travel;
   for(float ms=0;ms<200;ms+=1000f/hz){
    show.frame(ms/200);check(t.senderSelectView.x>=last,"monotonic arrival");
    check(t.senderSelectView.x<=0&&t.senderSelectView.x>=travel,"bounded arrival");
    t.assertSlot(text,emoji,draft);last=t.senderSelectView.x;
   }
  }
  show.frame(.4f);float x=t.senderSelectView.x,alpha=t.senderSelectView.alpha;
  t.update(true,false);check(t.recordingSenderAnimator==show,"duplicate nonanimated update cannot force opacity");
  near(t.senderSelectView.alpha,alpha,"no premature opacity completion");
  Consumer<ValueAnimator> staleFrame=show.update;AnimatorListenerAdapter staleEnd=show.listener;
  t.recording=true;t.recordPanel.visibility=VISIBLE;t.update(false,true);
  near(t.senderSelectView.x,x,"reversal preserves X");near(t.senderSelectView.alpha,alpha,"reversal preserves alpha");
  check(show.cancelled,"retire old animation");
  // Exercise callbacks retained by a dispatch, in addition to cleared listeners.
  show.value=1;staleFrame.accept(show);staleEnd.onAnimationEnd(show);show.end();
  near(t.senderSelectView.x,x,"stale update cannot overwrite reversal");
  near(t.senderSelectView.alpha,alpha,"cancel/end cannot force old opacity");
  check(t.recordingSenderSlotReserved,"stale end cannot release slot");
  t.recordingSenderAnimator.end();t.recording=false;t.recordPanel.visibility=GONE;
  t.update(true,true);t.recordingSenderAnimator.end();
  near(t.senderSelectView.alpha,1,"arrival completes");near(t.senderSelectView.x,0,"arrival endpoint");
  check(!t.recordingSenderSlotReserved&&t.recordingSenderAnimator==null,"release completed reservation");
  t.assertSlot(text,emoji,draft);
  // Actual measured hidden/visible deltas differ from avatar travel, at every density.
  t.senderSelectView.visibility=GONE;t.measure();
  int textDelta=text-t.messageEditText.params.leftMargin;
  check(textDelta==(separated?dp(36):dp(54)+dp(36)-dp(50)),"actual slot delta");
  check(Math.abs(textDelta+travel)>0,"avatar travel is NOT the content margin delta");
 }
 static void takeover(boolean separated){
  Transitions t=new Transitions(separated);float travel=t.getSenderHiddenTranslationX();
  t.senderSelectView.x=travel*.7f;t.senderSelectView.alpha=.3f;
  t.messageTextTranslationX=dp(11);t.emojiButton.x=separated?dp(3):travel*.7f;
  ValueAnimator old=new ValueAnimator();old.listener=new AnimatorListenerAdapter(){
   public void onAnimationCancel(Animator a){throw new AssertionError("legacy cancel endpoint ran");}
   public void onAnimationEnd(Animator a){throw new AssertionError("legacy end removed slot");}
  };old.update=a->{throw new AssertionError("legacy update ran");};t.senderSelectView.tag=old;
  t.recording=true;t.update(false,true);
  check(old.cancelled&&t.senderSelectView.tag==null,"legacy ownership retired");old.end();
  near(t.senderSelectView.x,travel*.7f,"takeover keeps live X");
  near(t.senderSelectView.alpha,.3f,"takeover keeps live opacity");
  near(t.messageTextTranslationX,dp(11),"do not reset recording text owner");
  near(t.emojiButton.x,separated?dp(3):travel*.7f,"takeover keeps emoji X");
  t.recordingSenderAnimator.frame(.5f);
  near(t.emojiButton.x,separated?dp(3):travel*.35f,"native emoji offset retires smoothly");
  t.recordingSenderAnimator.end();t.recording=false;
  // Permission/peer loss or explicit hiding during recording still cleans up.
  t.update(false,true);check(!t.recordingSenderSlotReserved,"hidden cleanup releases slot");
  check(t.senderSelectView.visibility==GONE,"hidden cleanup removes avatar");
  t=new Transitions(separated);t.recording=true;t.update(false,false);
  check(t.recordingSenderAnimator==null&&t.senderSelectView.alpha==0,"nonanimated entry");
  t.recording=false;t.update(true,false);
  check(!t.recordingSenderSlotReserved&&t.senderSelectView.x==0&&t.senderSelectView.alpha==1,"nonanimated restore");
  t.recording=true;t.update(false,true);ValueAnimator destroyed=t.recordingSenderAnimator;
  float alpha=t.senderSelectView.alpha;t.cancelRecordingSenderAnimator();destroyed.end();
  check(t.recordingSenderAnimator==null,"destroy retires animator");near(t.senderSelectView.alpha,alpha,"destroy cannot flash");
 }
 static void binHandoff(boolean separated,int hz,int decodeDelay){
  Transitions t=new Transitions(separated);t.recording=true;t.recordPanel.visibility=VISIBLE;
  t.update(false,true);t.recordingSenderAnimator.end();t.recording=false;t.recordIsCanceled=true;
  t.update(false,true);check(t.observer.listener!=null,"watch cancellation without a timer");
  // Panel ownership is authoritative until the queued parent really starts.
  t.recordDot.alpha=0;t.observer.draw();
  check(t.recordingSenderAnimator==null&&t.senderSelectView.alpha==0,"queued start cannot release avatar");
  t.recordDot.alpha=1;
  for(float ms=0;ms<decodeDelay+1016;ms+=1000f/hz){
   t.runningAnimationAudio.running=ms>=decodeDelay;
   t.recordDot.alpha=1-Math.max(0,Math.min(1,(ms-decodeDelay-866)/150f));
   t.observer.draw();
   check(t.recordingSenderAnimator==null&&t.senderSelectView.alpha==0,"no overlap through full bin fade");
  }
  t.recordDot.alpha=0;t.observer.draw();ValueAnimator show=t.recordingSenderAnimator;
  check(show!=null&&t.recordPanel.visibility==VISIBLE,"arrival starts BEFORE parent cleanup");
  check(t.observer.listener==null,"watch retires after visual handoff");
  near(t.senderSelectView.alpha,0,"handoff never forces opacity");
  show.frame(.5f);near(t.senderSelectView.x,t.getSenderHiddenTranslationX()/2,"handoff has horizontal motion");
  check(t.recordingSenderSlotReserved,"keep geometry until parent cleanup");
  t.runningAnimationAudio.running=false;t.recordPanel.visibility=GONE;t.update(true,true);
  check(t.recordingSenderAnimator==show,"panel cleanup cannot restart arrival");show.end();
  check(!t.recordingSenderSlotReserved&&t.recordingSenderAnimator==null,"handoff fully cleans up");
  // A new recording and destruction each retire a pending observer.
  t.recording=true;t.recordPanel.visibility=VISIBLE;t.recordDot.alpha=1;t.recordIsCanceled=false;
  t.update(false,true);t.recordingSenderAnimator.end();t.recording=false;t.recordIsCanceled=true;t.update(false,true);
  t.recording=true;t.observer.draw();check(t.observer.listener==null,"new recording removes watch");
  t.recording=false;t.update(false,true);t.destroyed=true;t.observer.draw();
  check(t.observer.listener==null&&t.recordingSenderRestoreObserver==null,"destroy removes watch");
 }
 static void opaqueBinNeedsCleanup(boolean separated){
  for(boolean cancel:new boolean[]{false,true}){
   Transitions t=new Transitions(separated);t.recording=true;t.recordPanel.visibility=VISIBLE;
   t.update(false,true);t.recordingSenderAnimator.end();t.recording=false;t.recordIsCanceled=cancel;
   t.runningAnimationAudio.running=true;t.recordDot.alpha=1;t.update(true,true);
   t.observer.draw();check(t.recordingSenderAnimator==null,"ALPHA1 bin blocks native/fast exit arrival");
   t.runningAnimationAudio.running=false;t.update(true,true);t.observer.draw();
   check(t.senderSelectView.alpha==0,"opaque bin cannot release on stopped parent alone");
   t.recordPanel.visibility=GONE;t.update(true,true);
   check(t.recordingSenderAnimator!=null,"panel cleanup restores native/fast exit");
   t.recordingSenderAnimator.end();check(!t.recordingSenderSlotReserved,"opaque-bin cleanup complete");
  }
 }
 public static void main(String[] args){
  for(float d:new float[]{1,1.5f,2,2.75f,3,4}){density=d;
   for(boolean separated:new boolean[]{false,true}){exercise(separated);takeover(separated);opaqueBinNeedsCleanup(separated);
    for(int hz:new int[]{60,90,120,144})for(int delay:new int[]{0,300})binHandoff(separated,hz,delay);
   }
  }
 }
}'''.replace('HELPERS', helpers).replace('WATCH', watch).replace('TAKEOVER', takeover).replace('MEASURE', measure)


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'JDK required')
class RecordingSenderMotionTests(unittest.TestCase):
    def test_native_and_separated_motion_geometry_and_races(self):
        run_java(sender_harness())

    def test_negative_control_fade_only_cannot_pass(self):
        code = sender_harness()
        wrong = code.replace('startX + (endX - startX) * progress', '0f')
        self.assertNotEqual(code, wrong)
        with self.assertRaisesRegex(AssertionError, 'horizontal departure'):
            run_java(wrong)

    def test_negative_control_forced_opacity_cannot_pass(self):
        code = sender_harness()
        wrong = code.replace('final float startAlpha = senderSelectView.getAlpha();',
                             'senderSelectView.setAlpha(1f); final float startAlpha = senderSelectView.getAlpha();')
        self.assertNotEqual(code, wrong)
        with self.assertRaisesRegex(AssertionError, 'departure reversal alpha'):
            run_java(wrong)


if __name__ == '__main__':
    unittest.main()
