"""First-frame capture eligibility and wallpaper dependency regression tests."""
import pathlib
import subprocess
import tempfile
import unittest
from test_glass_capture_geometry import JAVA, BLUR, method


class GlassFirstFrameTests(unittest.TestCase):
    def test_pending_capture_keeps_under_source_without_old_crop(self):
        src = (BLUR / "source/BlurredBackgroundSourceRenderNode.java").read_text()
        body = method(src, "public void draw(Canvas canvas, float left, float top, float right, float bottom)")
        self.assertLess(body.index("underSource.draw(canvas, left, top, right, bottom)"),
                        body.index("scrollableNoiseSuppressor.draw(canvas, getEffectiveSuppressorIndex())"))
        suppressor = (BLUR / "DownscaleScrollableNoiseSuppressor.java").read_text()
        draw = method(suppressor, "public void draw(Canvas canvas, int index)")
        self.assertIn("if (isDisplayListReadyAt(a))", draw)

    def test_new_island_is_captured_before_it_has_a_display_list(self):
        src = (BLUR / "source/BlurredBackgroundSourceRenderNode.java").read_text()
        body = method(src, "public int getVisiblePositions(")
        harness = r"""import java.util.*;
public class FirstFrame {
 static class RectF {
  float left,top,right,bottom;
  RectF(){} RectF(float l,float t,float r,float b){left=l;top=t;right=r;bottom=b;}
  boolean isEmpty(){return left>=right||top>=bottom;}
  void inset(float x,float y){left+=x;right-=x;top+=y;bottom-=y;}
 }
 static class BlurredBackgroundDrawableRenderNode {
  int alpha=255; boolean recorded=false;
  RectF bounds=new RectF(10,20,80,70);
  int getAlpha(){return alpha;} boolean hasDisplayList(){return recorded;}
  RectF getPaddedBounds(){return bounds;}
  void getPositionRelativeSource(RectF r){r.left=bounds.left;r.top=bounds.top;r.right=bounds.right;r.bottom=bounds.bottom;}
 }
 List<BlurredBackgroundDrawableRenderNode> drawables=new ArrayList<>();
 BODY
 static void check(boolean ok){if(!ok)throw new AssertionError();}
 public static void main(String[] args){
  FirstFrame h=new FirstFrame();
  BlurredBackgroundDrawableRenderNode d=new BlurredBackgroundDrawableRenderNode();
  h.drawables.add(d);List<RectF> out=new ArrayList<>();
  check(h.getVisiblePositions(out,0,24)==1); // never drawn, geometry ready
  check(out.get(0).left==-14 && out.get(0).bottom==94);
  d.recorded=true;check(h.getVisiblePositions(out,0,24)==1);
  d.alpha=0;check(h.getVisiblePositions(out,0,24)==0); // released/hidden
  d.alpha=255;d.bounds=new RectF();check(h.getVisiblePositions(out,0,24)==0);
  d.bounds=new RectF(10,20,80,70);d.recorded=false;
  check(h.getVisiblePositions(out,0,24)==1); // re-attach without old display list
 }
} """.replace("BODY", body)
        with tempfile.TemporaryDirectory(prefix="glass-first-") as temp:
            file = pathlib.Path(temp) / "FirstFrame.java"
            file.write_text(harness)
            subprocess.run(["javac", str(file)], check=True, capture_output=True)
            subprocess.run(["java", "-cp", temp, "FirstFrame"], check=True, capture_output=True)

    def test_wallpaper_replacement_invalidates_recorded_dependencies(self):
        chat = (JAVA / "org/telegram/ui/ChatActivity.java").read_text()
        body = method(chat, "public void onUpdateBackgroundDrawable(Drawable drawable)")
        set_at = body.index("navbarContentSourceWallpaper.setSource(source)")
        self.assertLess(set_at, body.index("invalidateWallpaperSource()"))
        invalidation = method(chat, "private void invalidateWallpaperSource()")
        redraw_at = invalidation.index("invalidateAllGlassAttachedViews()")
        for name in ("glassBackgroundSourceRenderNode", "glassBackgroundSourceFrostedRenderNode"):
            self.assertLess(invalidation.index(name + ".invalidateDisplayListForDrawables()"), redraw_at)
        self.assertIn("if (invalidateBlurredSourcesView != null)", invalidation)

    def test_composer_prepares_exact_geometry_without_rendering(self):
        src = (JAVA / "org/telegram/ui/Components/chat/ChatInputViewsContainer.java").read_text()
        body = method(src, "private void updateComposerBackground(")
        self.assertIn("if (canvas != null && drawInputCenterBackground)", body)
        self.assertIn("if (canvas != null) leadingDrawable.draw(canvas)", body)
        self.assertIn("if (canvas != null) trailingDrawable.draw(canvas)", body)
        self.assertIn("getInputBubbleDrawableBounds(tmpRect)", body)
        self.assertIn("updateComposerBackground(null, inputBubbleAlpha)",
                      method(src, "public void prepareBackgroundForCapture()"))
        chat = (JAVA / "org/telegram/ui/ChatActivity.java").read_text()
        self.assertIn("chatInputViewsContainer.prepareBackgroundForCapture()",
                      method(chat, "private int getVisibleBlurredPositions("))


if __name__ == "__main__":
    unittest.main()
