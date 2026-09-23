"""Exercise production press-mask geometry; Android ripple rendering needs a device."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from test_recording_composer_lifecycle import ENTER, method


class CancelFeedbackTests(unittest.TestCase):
    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "JDK required")
    def test_production_capsule_states_radius_and_alpha(self):
        drawable = method(ENTER, "private static class RecordingCancelFeedbackDrawable")
        java = '''public class FeedbackStates {
 static class android {static class R {static class attr {
  static final int state_enabled=1,state_pressed=2;
 }}}
 static class Color {static int alpha(int c){return c>>>24;}}
 static class ColorFilter {}
 static class PixelFormat {static final int TRANSLUCENT=-3;}
 static class CubicBezierInterpolator {static final Object EASE_OUT=new Object();}
 static class AnimatedFloat {
  float value; static float fraction=1;
  AnimatedFloat(float v,Runnable r,int delay,int duration,Object curve){value=v;}
  float set(boolean b){value+=((b?1:0)-value)*fraction;return value;}
  float set(boolean b,boolean force){return value=b?1:0;}
 }
 static class Rect {
  int left,top,right,bottom;
  int width(){return right-left;} int height(){return bottom-top;}
 }
 static class Paint {
  static final int ANTI_ALIAS_FLAG=1; int alpha;
  Paint(int f){check(f==ANTI_ALIAS_FLAG);} void setColor(int c){}
  void setAlpha(int a){alpha=a;} void setColorFilter(ColorFilter c){}
 }
 static class Canvas {
  float rx,ry;int alpha;
  void drawRoundRect(float l,float t,float r,float b,float x,float y,Paint p){rx=x;ry=y;alpha=p.alpha;}
 }
 static abstract class Drawable {
  Rect bounds=new Rect();int invalidations;
  void invalidateSelf(){invalidations++;} Rect getBounds(){return bounds;}
  public boolean isStateful(){return false;}
  protected boolean onStateChange(int[] s){return false;}
  public abstract void draw(Canvas c);
  public abstract void setAlpha(int a);
  public int getAlpha(){return 255;}
  public abstract void setColorFilter(ColorFilter c);
  public abstract int getOpacity();public void jumpToCurrentState(){}
 }
 DRAWABLE
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  RecordingCancelFeedbackDrawable d=new RecordingCancelFeedbackDrawable(0x1a123456);
  Canvas c=new Canvas(); d.bounds.right=225;d.bounds.bottom=96;
  check(d.isStateful());d.draw(c);check(c.alpha==0);
  check(d.onStateChange(new int[]{1,2}));d.draw(c);
  check(c.alpha==26&&c.rx==48&&c.ry==48);
  check(!d.onStateChange(new int[]{1,2}));
  d.setAlpha(128);d.draw(c);check(c.alpha==13);
  d.setAlpha(255);AnimatedFloat.fraction=.5f;
  d.onStateChange(new int[]{1});d.draw(c);check(c.alpha==13&&c.rx==48);
  d.onStateChange(new int[]{1,2});d.draw(c);check(c.alpha==20);
  // Disabled/cancelled controls must not retain a pressed fill.
  d.onStateChange(new int[]{2});d.jumpToCurrentState();d.draw(c);check(c.alpha==0);
  d.bounds.right=20;d.draw(c);check(c.rx==10&&c.ry==10);
  check(d.getAlpha()==255&&d.invalidations>0);
 }
}'''.replace("DRAWABLE", drawable)
        with tempfile.TemporaryDirectory(prefix="cancel-states-") as directory:
            file = Path(directory) / "FeedbackStates.java"
            file.write_text(java)
            subprocess.run(["javac", str(file)], check=True, capture_output=True)
            subprocess.run(["java", "-cp", directory, "FeedbackStates"], check=True, capture_output=True)

    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "JDK required")
    def test_mask_fits_without_cutting_round_corners(self):
        geometry = method(ENTER, "private static void getRecordingCancelFeedbackBounds(") + method(ENTER, "private static float getRecordingCancelLeft(")
        java = '''public class CancelFeedback {
 static float density;
 static int dp(float n){return (int)Math.ceil(n*density);}
 static class Rect {
  int left,top,right,bottom;
  void set(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;}
 }
 GEOMETRY
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  for(float d:new float[]{1,1.5f,2,2.625f,3,4}){
   density=d;
   for(int w:new int[]{180,250,320,500})for(int timer:new int[]{42,62,84})
    for(int label:new int[]{40,60,100,160}){
     float safeLeft=dp(timer+8),safeRight=dp(w-8);
     float labelWidth=Math.max(1,Math.min(dp(label),safeRight-safeLeft-dp(24)));
     float x=getRecordingCancelLeft(dp(w),safeLeft,safeRight,labelWidth);
     Rect r=new Rect();
     getRecordingCancelFeedbackBounds(r,x,labelWidth,safeLeft,safeRight,dp(44));
     check(r.left>=safeLeft && r.right<=safeRight && r.right>r.left);
     check(r.left<=Math.ceil(x) && r.right>=Math.floor(x+labelWidth));
     check(Math.abs((x-r.left)-(r.right-x-labelWidth))<=1);
     check(x-r.left>=dp(12)-1 && r.right-x-labelWidth>=dp(12)-1);
     check(r.top==dp(6) && r.bottom==dp(44)-dp(6));
     check(r.top+r.bottom==dp(44));
    }
  }
  // Recorded case: old circle extends past safe-left and gets a straight cut.
  density=3; Rect r=new Rect();
  getRecordingCancelFeedbackBounds(r,285,180,276,726,132);
  check(r.left==276 && r.right==474 && r.top==18 && r.bottom==114);
  check(285-dp(16)<276);
  // Reserve the complete capsule before positioning, not just the letters.
  float x=getRecordingCancelLeft(750,276,726,180);
  getRecordingCancelFeedbackBounds(r,x,180,276,726,132);
  check(x==312 && r.left==276 && r.right==528);
  // Legacy/non-iOS: no 60dp-radius ripple that escapes the 44dp row.
  getRecordingCancelFeedbackBounds(r,285,180,0,750,132);
  check(r.left==249 && r.right==501 && r.bottom-r.top==96);
 }
}'''.replace("GEOMETRY", geometry)
        with tempfile.TemporaryDirectory(prefix="cancel-feedback-") as directory:
            file = Path(directory) / "CancelFeedback.java"
            file.write_text(java)
            subprocess.run(["javac", str(file)], check=True, capture_output=True)
            subprocess.run(["java", "-cp", directory, "CancelFeedback"], check=True, capture_output=True)

    def test_style_changes_recreate_mask_without_relaying_out_recording(self):
        setter = method(ENTER, "public void setSeparatedComposerLayout(boolean enabled)")
        self.assertIn("slideText.updateColors();", setter)
        slide = ENTER.split("private class SlideTextView", 1)[1]
        colors = method(slide, "public void updateColors()")
        self.assertIn("selectableBackground.setCallback(null)", colors)
        self.assertIn("new RecordingCancelFeedbackDrawable(pressColor)", colors)
        self.assertNotIn("createSimpleSelectorCircleDrawable", colors)
        self.assertIn("selectableBackground.setState(getDrawableState())", colors)
        self.assertGreater(slide.index("canvas.clipRect(actionLeft, 0, actionRight"),
                           slide.index("selectableBackground.draw(canvas)"))

    def test_send_as_avatar_waits_for_invisible_bin_or_record_panel_cleanup(self):
        visibility = method(ENTER, "public void updateSendAsButton(boolean forceHide, boolean animated)")
        self.assertIn("!isRecordingSenderBlocked()", visibility)
        gate = method(ENTER, "private boolean isRecordingSenderBlocked()")
        self.assertIn("isRecordingAudioVideo()", gate)
        self.assertIn("recordedAudioPanel.getVisibility() == VISIBLE", gate)
        self.assertIn("recordPanel.getVisibility() == VISIBLE", gate)
        self.assertIn("recordIsCanceled && runningAnimationAudio != null && runningAnimationAudio.isRunning()", gate)
        self.assertIn("recordDot != null && recordDot.getAlpha() == 0f", gate)
        # Do not infer absence from scale or blink alpha: native/fast exits may
        # retain outer ALPHA=1. Queued decode must keep panel ownership too.
        self.assertNotIn("getScale", gate)
        self.assertNotIn("recordDot.alpha", gate)
        watch = method(ENTER, "private void updateRecordingSenderRestoreWatch()")
        self.assertIn("addOnPreDrawListener(recordingSenderRestoreListener)", watch)
        finish = method(ENTER, "private void finishRecordingSenderVisibility(")
        self.assertLess(finish.index("if (!hasRecordingSurface())"), finish.index("recordingSenderSlotReserved = false"))
        cleanup = method(ENTER, "private void cancelRecordInterfaceInternal()")
        self.assertLess(cleanup.index("recordPanel.setVisibility(GONE)"), cleanup.index("updateSendAsButton()"))
