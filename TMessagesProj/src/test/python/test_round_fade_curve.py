"""Execute the production fade clock at different display refresh rates."""
import unittest
from test_emoji_first_frame_fade import JAVA, method
from test_sender_infocard_transitions import run_java


class RoundFadeCurveTest(unittest.TestCase):
    def test_rebind_waits_for_presentation_not_just_decode(self):
        source = (JAVA / 'messenger/ImageReceiver.java').read_text()
        query = method(source, 'public boolean hasFullyVisibleImage(')
        run_java('''
public class Transitions {
 static class Drawable {boolean ready;}
 static class Appearance {boolean done;boolean complete(long time,boolean fin){return done;}}
 static class SystemClock {static long uptimeMillis(){return 1000;}}
 Object currentMediaLocation=new Object(),crossfadeOnReadyDrawable;
 Drawable currentMediaDrawable=new Drawable(),currentImageDrawable=new Drawable();
 boolean forcePreview,forceNotMedia;float currentAlpha=1;Appearance previewAppearance;
 boolean isDrawableReadyForDraw(Drawable d){return d.ready;}
 QUERY
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  Transitions t=new Transitions();t.currentImageDrawable.ready=true;
  check(!t.hasFullyVisibleImage());t.currentMediaDrawable.ready=true;
  t.crossfadeOnReadyDrawable=t.currentMediaDrawable;check(!t.hasFullyVisibleImage());
  t.crossfadeOnReadyDrawable=null;t.currentAlpha=.999f;check(!t.hasFullyVisibleImage());
  t.currentAlpha=1;t.previewAppearance=new Appearance();check(!t.hasFullyVisibleImage());
  t.previewAppearance.done=true;check(t.hasFullyVisibleImage());
  t.currentMediaDrawable=null;check(!t.hasFullyVisibleImage());
  t.forceNotMedia=true;check(t.hasFullyVisibleImage());
 }
}'''.replace('QUERY', query))
        cell = (JAVA / 'ui/Cells/ChatMessageCell.java').read_text()
        self.assertEqual(cell.count('!isRoundVideo || photoImage.hasFullyVisibleImage()'), 2)

    def test_curve_endpoints_cadence_and_resume(self):
        source = (JAVA / 'messenger/ImageReceiver.java').read_text()
        clock = method(source, 'private void checkAlphaAnimation(')
        run_java('''
public class Transitions {
 static class SystemClock {static long now=1000;static long uptimeMillis(){return now;}}
 boolean manualAlphaAnimator,isRoundVideo=true,crossfadeOnReady=true,crossfadeWithOldImage,crossfadingWithThumb;
 Object crossfadeOnReadyDrawable,crossfadeImage,crossfadeShader,presentedImagePreview;
 boolean roundPreviewFromThumb,crossfadeFromImage;int presentedImagePreviewGeneration;
 float currentAlpha,previousAlpha=1;long lastUpdateAlphaTime=1000;int crossfadeDuration=220;
 boolean hasRoundVideoPreview(){return false;}void invalidate(){}void recycleBitmap(Object o,int type){}
 CLOCK
 static void near(float a,float b){if(Math.abs(a-b)>.0001)throw new AssertionError(a+" != "+b);}
 static void check(boolean b){if(!b)throw new AssertionError();}
 static float sample(int step,int end){
  Transitions t=new Transitions();SystemClock.now=1000;
  for(int elapsed=step;elapsed<end;elapsed+=step){SystemClock.now=1000+elapsed;t.checkAlphaAnimation(false);}
  SystemClock.now=1000+end;t.checkAlphaAnimation(false);return t.currentAlpha;
 }
 public static void main(String[] args){
  for(int elapsed:new int[]{0,8,16,55,110,165,204,212,220,250}){
   float p=Math.min(1,elapsed/220f),expected=p*p*(3-2*p);
   for(int step:new int[]{4,8,11,16,33})near(sample(step,elapsed),expected);
  }
  check(sample(8,8)<.005f);check(1-sample(8,212)<.005f);
  check(sample(8,110)-sample(8,102)>sample(8,8));
  Transitions t=new Transitions();t.currentAlpha=.5f;t.previousAlpha=.3f;
  SystemClock.now=1008;t.checkAlphaAnimation(false);
  near(t.currentAlpha,sample(8,118));
  SystemClock.now=1008;
  float before=t.currentAlpha;t.checkAlphaAnimation(false);near(t.currentAlpha,before);
  t.manualAlphaAnimator=true;SystemClock.now=1100;t.checkAlphaAnimation(false);near(t.currentAlpha,before);
  t=new Transitions();t.isRoundVideo=false;SystemClock.now=1022;t.checkAlphaAnimation(false);near(t.currentAlpha,.1f);
  t=new Transitions();t.crossfadeWithOldImage=true;SystemClock.now=1022;t.checkAlphaAnimation(false);near(t.currentAlpha,.1f);
  t=new Transitions();SystemClock.now=1200;t.checkAlphaAnimation(true);near(t.currentAlpha,0);
 }
}'''.replace('CLOCK', clock))


if __name__ == '__main__':
    unittest.main()
