"""Execute the real release driver with a controllable host-side animator clock."""
from pathlib import Path
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2] / "main/java/org/telegram/ui/ActionBar"


def body(source, signature):
    start = source.index("{", source.index(signature))
    end, depth = start + 1, 1
    while depth:
        if source[end] == "{":
            depth += 1
        elif source[end] == "}":
            depth -= 1
        end += 1
    return source[start + 1:end - 1]


class MaterialGestureOwnershipTest(unittest.TestCase):
    def test_main_tabs_uses_actual_surface_not_gray_fallback(self):
        tabs = (ROOT.parent / "MainTabsActivity.java").read_text()
        harness = """
public class TabSurfaceCheck {
 static class Theme {static int key_windowBackgroundGray=0,key_windowBackgroundWhite=1;}
 static class ColorUtils {
  static int setAlphaComponent(int c,int a){return (c&0xffffff)|(a<<24);}
  static int blendARGB(int a,int b,float p){int out=0;for(int s=0;s<=24;s+=8)out|=((int)(((a>>>s)&255)*(1-p)+((b>>>s)&255)*p))<<s;return out;}
 }
 static class Pager {float[] visibility={1,0,0};float getPositionVisibility(int p){return visibility[p];}}
 Pager viewPager=new Pager();int chats=0,settings=1;
 int posChats(){return chats;} int posSettings(){return settings;}
 int getThemedColor(int k){return k==0?0xff000000:0xff181b25;}
 int getEstBackgroundColor(){ EST }
 int getNavigationBackgroundColor(){ NAV }
 static void check(boolean ok){if(!ok)throw new AssertionError("wrong navigation surface");}
 public static void main(String[] args){
  TabSurfaceCheck h=new TabSurfaceCheck();
  check(h.getNavigationBackgroundColor()==0xff181b25);
  h.viewPager.visibility=new float[]{0,1,0};check(h.getNavigationBackgroundColor()==0xff181b25);
  h.viewPager.visibility=new float[]{0,0,1};check(h.getNavigationBackgroundColor()==0xff000000);
  h.chats=2;h.settings=0;check(h.getNavigationBackgroundColor()==0xff181b25);
  h.viewPager.visibility=new float[]{0,.5f,.5f};
  int mixed=h.getNavigationBackgroundColor();check((mixed>>>24)==255&&mixed!=0xff000000&&mixed!=0xff181b25);
  h.viewPager=null;check(h.getNavigationBackgroundColor()==0xff181b25);
 }
}
""".replace(" EST ", body(tabs, "private int getEstBackgroundColor(")).replace(" NAV ", body(tabs, "public int getNavigationBackgroundColor("))
        with tempfile.TemporaryDirectory() as directory:
            runner = Path(directory) / "TabSurfaceCheck.java"
            runner.write_text(harness)
            subprocess.run(["javac", "-d", directory, str(runner)], check=True)
            subprocess.run(["java", "-cp", directory, "TabSurfaceCheck"], check=True)

    def test_gesture_outline_is_restored_on_all_exit_paths(self):
        layout = (ROOT / "ActionBarLayout.java").read_text()
        gesture = body(layout, "private void applyMaterialGestureProgress(")
        self.assertIn("materialOriginalOutline = containerView.getOutlineProvider()", gesture)
        self.assertIn("materialOriginalClip = containerView.getClipToOutline()", gesture)
        self.assertIn("containerView.invalidateOutline()", gesture)
        restore = body(layout, "private void restoreMaterialGestureOutline()")
        self.assertIn("setOutlineProvider(materialOriginalOutline)", restore)
        self.assertIn("setClipToOutline(materialOriginalClip)", restore)
        for signature in ("private void onSlideAnimationEnd(", "private void resetAbortedSlideAnimation(", "private void cancelNavigationAnimationsForStackReset("):
            self.assertIn("restoreMaterialGestureOutline()", body(layout, signature))

    def test_timed_visibility_and_surface_colors(self):
        layout = (ROOT / "ActionBarLayout.java").read_text()
        surface = body(layout, "private int materialSurfaceColor(")
        self.assertIn("getBackgroundImage()", surface)
        self.assertIn("fragment instanceof DialogsActivity", surface)
        self.assertIn("Theme.key_windowBackgroundWhite", surface)
        self.assertNotIn("Theme.key_chat_wallpaper", surface)
        self.assertIn("getDrawable(false)", surface)
        self.assertIn("((MainTabsActivity) fragment).getNavigationBackgroundColor()", surface)
        self.assertLess(surface.index("fragment instanceof MainTabsActivity"), surface.index("root.getBackground()"))
        self.assertLess(surface.index("fragment instanceof DialogsActivity"), surface.index("getBackgroundImage()"))
        timed = body(layout, "private void applyMaterialSurfaces(")
        self.assertNotIn("materialBottomAlpha =", timed)
        self.assertIn("MaterialSharedAxisMotion.enteringAlpha(p)", timed)
        composition = body(layout, "private void configureMaterialComposition(")
        self.assertIn("materialBottomAlpha = 1f", composition)
        self.assertIn("ColorUtils.blendARGB(from, to, mix)", composition)

    def test_timed_and_interactive_routes_share_geometry(self):
        layout = (ROOT / "ActionBarLayout.java").read_text()
        self.assertIn("applyMaterialSurfaces(open, progress", body(layout, "private void applyMaterialLayoutProgress("))
        gesture = body(layout, "private void applyMaterialGestureProgress(")
        self.assertIn("configureMaterialComposition(containerViewBack, containerView", gesture)
        self.assertNotIn("applyMaterialSurfaces(", gesture)
        self.assertIn("MaterialSharedAxisMotion.gestureOffset", gesture)
        composition = body(layout, "private void configureMaterialComposition(")
        self.assertIn("entering.setAlpha(1f)", composition)
        self.assertIn("leaving.setAlpha(1f)", composition)
        self.assertIn("indexOfChild(entering) > indexOfChild(leaving)", composition)
        self.assertIn("applyMaterialGestureProgress(t)", body(layout, "public void onBackProgress("))
        touch = body(layout, "public boolean onTouchEvent(")
        self.assertIn("applyMaterialGestureProgress(MaterialSharedAxisMotion.dragProgress", touch)
        self.assertIn("MaterialSharedAxisMotion.cancelDrag", touch)
        self.assertIn("settleMaterialGesture(backAnimation, velX)", body(layout, "private void animateBackEndAnimation("))
        host_draw = layout[layout.rindex("protected boolean drawChild("):]
        self.assertIn("!materialTransitionActive && !isTransitionAnimationInProgress()", body(host_draw, "protected boolean drawChild("))
        for signature in ("private void onSlideAnimationEnd(", "private void resetAbortedSlideAnimation(", "private void cancelNavigationAnimationsForStackReset("):
            self.assertIn("materialGestureActive = false", body(layout, signature))
            self.assertIn("materialTransitionActive = false", body(layout, signature))

    def test_profile_morph_and_no_double_dialogs_transform(self):
        profile = (ROOT.parent / "ProfileActivity.java").read_text()
        self.assertNotIn("isMaterialNavigationEnabled", body(profile, "public AnimatorSet onCustomTransitionAnimation("))
        self.assertNotIn("isMaterialNavigationEnabled", body(profile, "public void setPlayProfileAnimation("))
        dialogs = (ROOT.parent / "DialogsActivity.java").read_text()
        slide = body(dialogs, "public void prepareFragmentToSlide(")
        self.assertLess(slide.index("isMaterialNavigationEnabled"), slide.index("isSlideBackTransition = true"))
        self.assertIn("setSlideTransitionProgress(1f)", slide)
        launch = (ROOT.parent / "LaunchActivity.java").read_text()
        self.assertIn("backEvent.getSwipeEdge() == BackEvent.EDGE_LEFT", launch)

    def test_release_cancel_and_stale_callbacks(self):
        layout = (ROOT / "ActionBarLayout.java").read_text()
        driver = body(layout, "private void settleMaterialGesture(").replace("android.view.animation.LinearInterpolator", "LinearInterpolator")
        harness = """
package org.telegram.ui.ActionBar;
import java.util.function.Consumer;
public class MaterialGestureCheck {
 static class Animator {}
 static class AnimatorListenerAdapter {
  public void onAnimationCancel(Animator a){} public void onAnimationEnd(Animator a){}
 }
 static class LinearInterpolator {}
 static class ValueAnimator {
  float start,end,value; Consumer<ValueAnimator> callback; long duration;
  static ValueAnimator ofFloat(float a,float b){ValueAnimator v=new ValueAnimator();v.start=a;v.end=b;v.value=a;return v;}
  void setDuration(long d){duration=d;} void setInterpolator(Object i){}
  void addUpdateListener(Consumer<ValueAnimator> c){callback=c;}
  Object getAnimatedValue(){return value;}
  void frame(float fraction){value=start+(end-start)*fraction;callback.accept(this);}
 }
 static class AnimatorSet extends Animator {
  ValueAnimator value; AnimatorListenerAdapter listener;
  void playTogether(ValueAnimator v){value=v;}
  void addListener(AnimatorListenerAdapter l){listener=l;}
  void start(){value.frame(0);}
  void end(){value.frame(1);listener.onAnimationEnd(this);}
  void cancel(){listener.onAnimationCancel(this);listener.onAnimationEnd(this);}
 }
 static class View {int getMeasuredWidth(){return 1080;}}
 long navigationEpoch; AnimatorSet backAnimator; boolean materialGestureActive=true;
 boolean predictiveBackInProgress=true,predictiveInput=true,predictiveBackHasProgress=true;
 boolean backAnimatorIsBack,animationInProgress,wasCancelled; int completions;
 float materialGestureProgress=.42f;
 View containerView=new View(),containerViewBack=new View(),layoutToIgnore;
 void applyMaterialGestureProgress(float p){materialGestureProgress=p;}
 void onSlideAnimationEnd(boolean cancel){wasCancelled=cancel;completions++;materialGestureActive=false;animationInProgress=false;}
 void settleMaterialGesture(boolean cancel,float velocity){ DRIVER }
 static void check(boolean condition,String name){if(!condition)throw new AssertionError(name);}
 static void near(float a,float b){check(Math.abs(a-b)<.00001f,"position jumped");}
 public static void main(String[] args){
  MaterialGestureCheck h=new MaterialGestureCheck(); h.settleMaterialGesture(false,1200);
  near(h.materialGestureProgress,.42f); AnimatorSet owner=h.backAnimator;
  owner.value.frame(.5f); check(h.materialGestureProgress>.42f,"commit direction");
  owner.end(); owner.end(); check(h.completions==1&&!h.wasCancelled,"commit once");
  check(!h.predictiveBackInProgress&&!h.predictiveInput&&!h.predictiveBackHasProgress,"predictive cleanup");
  h=new MaterialGestureCheck();h.settleMaterialGesture(true,-1200);owner=h.backAnimator;
  near(h.materialGestureProgress,.42f);owner.value.frame(.5f);
  check(h.materialGestureProgress<.42f,"cancel direction");owner.end();
  check(h.wasCancelled&&h.completions==1,"cancel once");
  h=new MaterialGestureCheck();h.settleMaterialGesture(false,0);owner=h.backAnimator;
  owner.cancel();check(h.wasCancelled&&h.completions==1,"interruption restores page");
  h=new MaterialGestureCheck();h.settleMaterialGesture(false,0);owner=h.backAnimator;
  h.navigationEpoch++;h.backAnimator=null;h.materialGestureProgress=.123f;
  owner.value.frame(1);owner.end();near(h.materialGestureProgress,.123f);
  check(h.completions==0,"stale callback touched replaced stack");
 }
}
""".replace("DRIVER", driver)
        with tempfile.TemporaryDirectory() as directory:
            runner = Path(directory) / "MaterialGestureCheck.java"
            runner.write_text(harness)
            subprocess.run(["javac", "-d", directory, str(ROOT / "MaterialSharedAxisMotion.java"), str(runner)], check=True)
            subprocess.run(["java", "-cp", directory, "org.telegram.ui.ActionBar.MaterialGestureCheck"], check=True)


if __name__ == "__main__":
    unittest.main()
