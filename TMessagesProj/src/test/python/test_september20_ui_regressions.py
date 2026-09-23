"""Production control-flow/geometry checks. These do not replace GPU/device QA."""
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

from test_recording_composer_lifecycle import method

JAVA = Path(__file__).resolve().parents[2] / "main/java"


def source(name):
    return (JAVA / (name + ".java")).read_text()


def run_java(body):
    with tempfile.TemporaryDirectory(prefix="ng-ui-regression-") as temp:
        path = Path(temp) / "Harness.java"
        path.write_text(body)
        subprocess.run(["javac", str(path)], check=True, capture_output=True)
        result = subprocess.run(["java", "-cp", temp, "Harness"], capture_output=True, text=True)
        if result.returncode:
            raise AssertionError(result.stderr)


class September20Regressions(unittest.TestCase):
    def test_cancel_does_not_need_velocity_tracker(self):
        src = source("org/telegram/ui/DialogsActivity")
        content = method(src, "private class ContentView") if "private class ContentView" in src else src
        start = content.index("final boolean cancelled = ev == null")
        end = content.index("\n                    if (startedTracking)", start)
        calculation = content[start:end]
        disallow = method(content, "public void requestDisallowInterceptTouchEvent(")
        self.assertIn("disallowIntercept && maybeStartTracking && !startedTracking", disallow)
        self.assertIn("if (cancelled || viewPages[1].isLocked)", content)
        run_java("""
public class Harness {
 static class MotionEvent { static final int ACTION_CANCEL=3; int action;
  MotionEvent(int a){action=a;} int getActionMasked(){return action;} }
 static class Tracker { int calls; void computeCurrentVelocity(int units,float max){calls++;}
  float getXVelocity(){return 4000;} float getYVelocity(){return 1;} }
 Tracker velocityTracker; float maximumVelocity=8000; boolean startedTracking; int moves;
 void prepareForMoving(MotionEvent ev,boolean left){moves++;}
 float velocity(MotionEvent ev){
""" + calculation + """
 return velX; }
 public static void main(String[] args){
  Harness h=new Harness();
  if(h.velocity(null)!=0 || h.velocity(new MotionEvent(3))!=0 || h.velocity(new MotionEvent(1))!=0)
   throw new AssertionError("missing tracker must be safe");
  h.velocityTracker=new Tracker(); h.velocity(null); h.velocity(new MotionEvent(3));
  if(h.moves!=0 || h.velocityTracker.calls!=0)throw new AssertionError("cancel must not fling");
  if(h.velocity(new MotionEvent(1))!=4000 || h.moves!=1 || h.velocityTracker.calls!=1)
   throw new AssertionError("real release velocity lost");
 }
}
""")

    def test_story_single_line_alignment_is_independent_of_composer_height(self):
        src = source("org/telegram/ui/Stories/PeerStoriesView")
        start = src.index("final float compactFieldTranslation;")
        end = src.index("editField.setTranslationY(", start)
        calculation = src[start:end]
        run_java("""
public class Harness {
 static class Edit { int lines=1; int getLineCount(){return lines;} }
 static class Composer { int height; float top; int getMeasuredHeight(){return height;}
  float getTopViewEnterProgress(){return top;} }
 static class Rect {float centerY(){return 176;}}
 static class Resources { Rect rect2=new Rect(); }
 Edit editField=new Edit(); Composer chatActivityEnterView=new Composer();
 Resources sharedResources=new Resources();
 static float dp(float x){return x;} float getUntranslatedCenterY(Edit field){return 180;}
 float translation(){
""" + calculation + """
 return compactFieldTranslation; }
 public static void main(String[] args){
  Harness h=new Harness();
  for(int height:new int[]{46,50,58,64,72}){
   h.chatActivityEnterView.height=height;
   if(h.translation()!=-4)throw new AssertionError("single line pushed below capsule at "+height);
  }
  h.editField.lines=3;h.chatActivityEnterView.height=100;
  if(h.translation()!=48)throw new AssertionError("multiline behaviour changed");
  h.editField.lines=1;h.chatActivityEnterView.top=1;
  if(h.translation()!=48)throw new AssertionError("reply/top view behaviour changed");
 }
}
""")

    def test_compact_recording_waves_are_visible_and_bounded(self):
        # Execute actual contour/scale/cancellation methods. The previous
        # numeric-only assertion missed the locked button and drawable origins.
        from test_recording_wave_geometry import wave_harness
        from test_sender_infocard_transitions import run_java as run_wave_java
        run_wave_java(wave_harness())

    def test_glass_uses_live_composition_not_baked_crop_positions(self):
        src = source("org/telegram/ui/Components/blur3/source/BlurredBackgroundSourceRenderNode")
        draw = method(src, "public void draw(")
        self.assertIn("scrollableNoiseSuppressor.draw(canvas, getEffectiveSuppressorIndex())", draw)
        self.assertNotIn("drawInline(", draw)
        suppressor = source("org/telegram/ui/Components/blur3/DownscaleScrollableNoiseSuppressor")
        # Recording a drawable must retain a stable composition node. Replacing
        # its crop list later must be visible without rerecording the drawable.
        run_java("""
import java.util.*;
public class Harness {
 static class RenderNode {String pixels;RenderNode(String p){pixels=p;}}
 static class Canvas {List<RenderNode> commands=new ArrayList<>();
  boolean isHardwareAccelerated(){return true;} void drawRenderNode(RenderNode node){commands.add(node);}
  String replay(){return commands.get(0).pixels;}}
 static final int DRAW_GLASS=-2, DRAW_FROSTED_GLASS_NO_SATURATION=-3, DRAW_FROSTED_GLASS=-4;
 boolean isLiquidGlassEnabled=true, simpleMode;
 RenderNode[] resultRenderNodes={new RenderNode("header + composer"),new RenderNode("frosted")};
 void updateGlassMode(){}
 boolean isDisplayListReadyAt(int a){return a>=0;}
""" + method(suppressor, "private int resolveInlineIndex(") +
        method(suppressor, "public void draw(Canvas canvas, int index)") + """
 public static void main(String[] args){
  Harness h=new Harness();Canvas drawable=new Canvas();h.draw(drawable,DRAW_GLASS);
  h.resultRenderNodes[0].pixels="keyboard changed crops";
  if(!drawable.replay().equals("keyboard changed crops"))throw new AssertionError("stale capture geometry");
  h.resultRenderNodes[0].pixels="";
  if(!drawable.replay().isEmpty())throw new AssertionError("removed crop remained visible");
 }
}
""")

    def test_popup_switch_shares_account_transition_clock(self):
        src = source("org/telegram/ui/Components/ItemOptions")
        start = method(src, "public void dismissWithAccountSwitch(")
        self.assertIn("start.accept(popup);", start)
        self.assertNotIn("dismissThen", src)
        captured = method(src, "public void onCaptured()")
        self.assertIn("removeDim();", captured)
        self.assertNotIn("window.dismiss", captured)
        complete = method(src, "public void finish()")
        self.assertIn("window.dismiss(false);", complete)
        self.assertIn("notifyDismissListener();", complete)
        transition = source("org/telegram/ui/Components/AccountSwitchTransition")
        self.assertIn("popup.setProgress(progress)", transition)
        self.assertIn("view.setAlpha(1f - progress)", transition)
        duration = re.search(r'REVEAL_DURATION_MS\s*=\s*(\d+)', transition)
        self.assertIsNotNone(duration)
        self.assertGreater(int(duration[1]), 0)
        self.assertLessEqual(int(duration[1]), 240)
        self.assertIn("animator.setDuration(REVEAL_DURATION_MS)", transition)
        tabs = source("org/telegram/ui/MainTabsActivity")
        self.assertIn("o.dismissWithAccountSwitch(popup -> {", tabs)
        self.assertIn("switchToAccountAnimated(account, popup)", tabs)


if __name__ == "__main__":
    unittest.main()
