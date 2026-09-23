"""Cold-frame, monotonic-clock, and pager handoff contracts for navigation."""
from pathlib import Path
import re
import subprocess
import tempfile
import unittest


JAVA = Path(__file__).resolve().parents[2] / "main/java/org/telegram/ui"
LAYOUT = (JAVA / "ActionBar/ActionBarLayout.java").read_text()
PAGER = (JAVA / "ViewPagerActivity.java").read_text()


def body(source, signature):
    start = source.index(signature)
    opening = source.index("{", start)
    depth = 1
    end = opening + 1
    while depth:
        if source[end] == "{":
            depth += 1
        elif source[end] == "}":
            depth -= 1
        end += 1
    return source[opening + 1:end - 1]


class SpringNavigationBoundedTest(unittest.TestCase):
    def test_navigation_timeout_and_fallback_frames_share_uptime_clock(self):
        self.assertIn("import android.os.SystemClock;", LAYOUT)
        self.assertNotIn("System.currentTimeMillis()", LAYOUT)
        self.assertNotIn("System.nanoTime()", LAYOUT)
        assignments = re.findall(r"transitionAnimationStartTime\s*=\s*([^;]+);", LAYOUT)
        self.assertGreaterEqual(len(assignments), 7)
        self.assertTrue(all(value.strip() in ("0", "SystemClock.uptimeMillis()") for value in assignments), assignments)
        self.assertIn("lastFrameTime = SystemClock.uptimeMillis()", LAYOUT)
        self.assertIn("long newTime = SystemClock.uptimeMillis()", LAYOUT)

    def test_spring_first_frame_is_seeded_for_open_close_and_cold_width(self):
        spring = body(LAYOUT, "private void startLayoutAnimation(")
        start = spring.index("            if (!preview) {\n                final float travel")
        end = spring.index("            if (USE_ACTIONBAR_CROSSFADE)", start)
        seed = spring[start:end]
        self.assertLess(spring.index("containerView.setTranslationX(travel > 0f"), spring.index("springAnimation.start()"))
        self.assertIn("if (springTravel <= 0f)", spring)
        self.assertIn("if (widthNoPaddings <= 0f)", spring)
        source = """
public class NavigationSeed {
 static class Display {int x=1080;}
 static class AndroidUtilities {static Display displaySize=new Display();}
 static class Container {float x;void setTranslationX(float value){x=value;}}
 Container containerView=new Container();int width,left,right;boolean preview,open;
 int getWidth(){return width;}int getPaddingLeft(){return left;}int getPaddingRight(){return right;}
 void seed(){
SEED
 }
 static void check(boolean condition,String what){if(!condition)throw new AssertionError(what);}
 public static void main(String[] args){
  NavigationSeed h=new NavigationSeed();h.width=1080;h.open=true;h.seed();
  check(h.containerView.x==1080,"open begins offscreen at its measured width");
  h.open=false;h.seed();check(h.containerView.x==-378,"close begins at first spring parallax frame");
  h.width=600;h.open=true;h.seed();check(h.containerView.x==600,"pane width is not replaced by display width");
  h.width=0;h.seed();check(h.containerView.x==1080,"unmeasured first open stays offscreen");
  h.preview=true;h.containerView.x=17;h.seed();check(h.containerView.x==17,"preview keeps its own geometry");
  System.out.println("PASS: spring navigation first-frame seed");
 }
}
""".replace("SEED", seed)
        with tempfile.TemporaryDirectory(prefix="spring-navigation-") as tmp:
            file = Path(tmp) / "NavigationSeed.java"
            file.write_text(source)
            build = subprocess.run(["javac", str(file)], capture_output=True, text=True, timeout=30)
            self.assertEqual(build.returncode, 0, build.stderr)
            run = subprocess.run(["java", "-cp", tmp, "NavigationSeed"], capture_output=True, text=True, timeout=30)
            self.assertEqual(run.returncode, 0, run.stdout + run.stderr)

    def test_pager_visibility_waits_for_authoritative_translation(self):
        bind = body(PAGER, "public void bindView(View view, int position, int viewType)")
        self.assertNotIn("checkFragmentsVisibility()", bind)
        create = body(PAGER, "public View createView(Context context)")
        self.assertLess(create.index("contentView.addView(viewPager"), create.index("checkFragmentsVisibility();"))
        self.assertIn("checkFragmentsVisibility()", body(PAGER, "protected void onItemSelected("))
        self.assertIn("checkFragmentsVisibility()", body(PAGER, "public void onTabAnimationUpdate(boolean manual)"))
        visibility = body(PAGER, "private void checkFragmentsVisibility()")
        self.assertIn("position == currentPosition", visibility)
        self.assertIn("position == nextPosition", visibility)
        self.assertNotIn("getPositionVisibility(position)", visibility)

    def test_cancelled_back_streams_settle_before_next_navigation(self):
        touch = body(LAYOUT, "public boolean onTouchEvent(MotionEvent ev)")
        self.assertLess(touch.index("action == MotionEvent.ACTION_CANCEL"), touch.index("action == MotionEvent.ACTION_UP"))
        self.assertIn("cancelActiveSlideTracking();", touch)
        cancellation = body(LAYOUT, "private void cancelActiveSlideTracking()")
        self.assertIn("animateBackEndAnimation(true, 0f);", cancellation)
        self.assertIn("animateBackEndAnimation(true, 0f);", body(LAYOUT, "public void onBackCancelled()"))
        self.assertIn("finishSettlingSlideForNextBack();", body(LAYOUT, "public boolean onBackStarted("))
        self.assertIn("finishSettlingSlideForNextBack();", body(LAYOUT, "public void onBackPressed()"))


if __name__ == "__main__":
    unittest.main()
