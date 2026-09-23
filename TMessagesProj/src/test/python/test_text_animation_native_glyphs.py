"""Production-Java contracts for animation/native text handoff; no device rendering."""
import pathlib
import re
import subprocess
import tempfile
import unittest

from test_frosted_scroll_sampling import block

JAVA = pathlib.Path(__file__).resolve().parents[2] / "main/java"
SOURCE = JAVA / "app/nimarkogram/messenger/textanim/NimarkoTextAnim.java"


class NativeGlyphAnimationTests(unittest.TestCase):
    def test_production_cached_renderer_retains_effects_and_native_handoff(self):
        source = SOURCE.read_text()
        constants = "\n".join(re.search(
            r"private static final int " + name + r" = \d+;", source).group(0)
            for name in ("APPEAR_DURATION", "REPLACE_DURATION", "SPOILER_DURATION",
                         "BLUR_TEXT_DELAY_PCT", "SLIDE_DIST_PX"))
        production = "\n".join(block(source, signature) for signature in (
            "private static final class CharData", "private static void drawShapedRuns(",
            "private static float appearanceProgress(", "private static float easeOutQuint("))
        java = r"""
import java.util.*;
public class CachedRendererTest {
  static boolean appearEnabled=true,spoilerEnabled;
  static class SpoilerParticle {}
  static class Bitmap { int getHeight(){return 100;} }
  static class Paint {
    static final int ANTI_ALIAS_FLAG=1; int alpha;
    Paint(int flags){} void reset(){alpha=255;} void setAlpha(int a){alpha=a;}
    void setFilterBitmap(boolean filter){}
  }
  static class ShapedRun {
    int start=0,padding=22; boolean rtl=false;
    float width=93.445312f,x=-22,y=-22,textX=22;
    float[] advances={26.445312f,28,39};
    CharData[] animations=new CharData[3];
    Bitmap crisp=new Bitmap(),blur=new Bitmap();
  }
  static class State {
    Paint sharedPaint; List<ShapedRun> hiddenSpans=new ArrayList<>();
    Map<Integer,CharData> charStartTimes=new HashMap<>();
  }
  static class EditText {
    int getCompoundPaddingLeft(){return 0;} int getTotalPaddingTop(){return 22;}
  }
  static class Canvas {
    ShapedRun run; List<Integer> alpha=new ArrayList<>(),blurAlpha=new ArrayList<>();
    List<Float> ys=new ArrayList<>(),lefts=new ArrayList<>();
    Canvas(ShapedRun r){run=r;} void save(){} void restore(){}
    void clipRect(float l,float t,float r,float b){lefts.add(l);}
    void drawBitmap(Bitmap b,float x,float y,Paint p){
      if(b==run.crisp){alpha.add(p.alpha);ys.add(y);}
      else blurAlpha.add(p.alpha);
    }
  }
""" + constants + production + r"""
  static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
  public static void main(String[] args){
    State single=new State(); ShapedRun one=new ShapedRun();single.hiddenSpans.add(one);
    for(int i=0;i<3;i++)one.animations[i]=new CharData(1000,"x",1,false);
    Canvas first=new Canvas(one);drawShapedRuns(new EditText(),first,single,1000);
    check(first.blurAlpha.isEmpty(),"first frame must not flash a fully opaque blur");
    Canvas fractional=new Canvas(one);drawShapedRuns(new EditText(),fractional,single,1037);
    check(fractional.ys.get(0)!=Math.round(fractional.ys.get(0)),"motion must retain subpixel positions");
    for(boolean replacement:new boolean[]{false,true})
    for(boolean spoiler:new boolean[]{false,true})
    for(boolean appear:new boolean[]{false,true})
    for(int hz:new int[]{60,90,120,144}) {
      spoilerEnabled=spoiler;appearEnabled=appear;
      State s=new State(); ShapedRun r=new ShapedRun();s.hiddenSpans.add(r);
      for(int i=0;i<3;i++)s.charStartTimes.put(i,new CharData(1000+i*33,"Тиш".substring(i,i+1),1,replacement));
      for(int i=0;i<3;i++)r.animations[i]=s.charStartTimes.get(i);
      int[] previous={-1,-1,-1};
      for(int frame=0;frame<150;frame++){
        long now=1000+frame*1000L/hz;Canvas c=new Canvas(r);
        drawShapedRuns(new EditText(),c,s,now);
        check(c.alpha.size()==3,"all Cyrillic glyph effects retained");
        check(c.lefts.get(1)==26 && c.lefts.get(2)==54,"native shaped slice boundaries");
        for(int i=0;i<3;i++){
          int alpha=c.alpha.get(i);float y=c.ys.get(i);
          check(alpha>=previous[i] && alpha<=255,"monotonic fade");previous[i]=alpha;
          check(y<=0 && y>=-20,"bounded slide");
          if(replacement||!appear)check(y==0,"replacement/no-slide stays in place");
          int duration=(replacement?REPLACE_DURATION:APPEAR_DURATION)+(spoiler?SPOILER_DURATION:0);
          if(now>=1000+i*33+duration-1)check(alpha==255 && y==0,"native handoff");
        }
      }
    }
  }
}
"""
        with tempfile.TemporaryDirectory(prefix="cached-text-renderer-") as temp:
            file = pathlib.Path(temp) / "CachedRendererTest.java"
            file.write_text(java)
            compiled = subprocess.run(["javac", str(file)], capture_output=True, text=True)
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            result = subprocess.run(["java", "-cp", temp, "CachedRendererTest"],
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_cached_renderer_has_no_isolated_glyph_or_per_frame_shaping_path(self):
        source = SOURCE.read_text()
        draw = block(source, "private static void drawShapedRuns(")
        draw = re.sub(r"//[^\n]*", "", draw)
        for forbidden in ("new ", "getTextRunAdvances", "StaticLayout", "setSpan", "drawText"):
            self.assertNotIn(forbidden, draw.replace(
                "new Paint(Paint.ANTI_ALIAS_FLAG)", "lazy paint initialization"))
        run = block(source, "private static final class ShapedRun")
        self.assertIn("implements NoCopySpan, UpdateAppearance", run)
        self.assertIn("nativeRun.draw(raster)", run)
        self.assertNotIn(".recycle()", run)
        self.assertIn("crisp = null", run)
        self.assertIn("blur = null", run)
        self.assertLess(run.index("Math.min(BLUR_BITMAP_MAX_BYTES, byteBudget)"),
                        run.index("Bitmap.createBitmap"))
        update = block(source, "private static void updateHiddenSpan(")
        self.assertNotIn("frameSpan", source)
        self.assertNotIn("AnimatedGlyphSpan", source)
        self.assertNotIn("drawCharBlurred", source)
        self.assertIn("sameRunPaint", update)
        rebuild = block(source, "private static void rebuildShapedRuns(")
        self.assertIn("supportsRunStyles(text, from, to)", rebuild)
        self.assertIn("catch (OutOfMemoryError ignored)", rebuild)
        self.assertIn("BLUR_CACHE_MAX_BYTES - bytes", rebuild)

    def test_expiry_and_rapid_edit_rebasing_still_preserve_existing_effects(self):
        source = SOURCE.read_text()
        edits = block(source, "private static void handleTextChanged(")
        self.assertIn("idx + data.length <= editStart", edits)
        self.assertIn("idx >= oldEnd", edits)
        self.assertIn("int n = idx + delta", edits)
        self.assertIn("new CharData(nowMs", edits)
        self.assertIn("hasReplacementSpan(text", block(source, "private static void updateHiddenSpan("))
        loop = block(source, "private static void startAnimationLoop(")
        # Constant hiding spans invalidate native text only on add/remove;
        # steady frames are animated entirely by the cached overlay.
        self.assertIn("updateHiddenSpan(edit, st);", block(source, "private static void handleBeforeDraw("))
        self.assertNotIn("frameSpan", block(source, "private static void updateHiddenSpan("))
        self.assertIn("editor.invalidate();", loop)
        self.assertIn("edit.postOnAnimation(st.animationRunnable);", loop)
        self.assertIn("removeSpan(edit, st);", block(source, "private static void stopAnimation("))
        self.assertIn("REPLACE_DURATION", block(source, "private static void updateHiddenSpan("))
        update = block(source, "private static void updateHiddenSpan(")
        retirement = update[update.index("retireChar(st, data);"):update.index("continue;")]
        self.assertNotIn("st.spansDirty = true", retirement)

    def test_caret_catches_up_after_rapid_typing_without_losing_smooth_motion(self):
        source = SOURCE.read_text()
        callback = block(source, "public static void onEditorTextChanged(")
        cursor = block(source, "private static void drawCursor(")
        self.assertIn("getState(edit).lastTextChangeMs = SystemClock.uptimeMillis();", callback)
        self.assertIn("st.lastTextChangeMs < CURSOR_TYPING_WINDOW_MS", cursor)
        self.assertIn("cursorEase(typing ? CURSOR_TYPING_SPEED : CURSOR_SPEED, dtNorm)", cursor)
        self.assertIn("(typing ? 120f : 60f) * dtNorm", cursor)
        constants = "\n".join(re.search(
            r"private static final int " + name + r" = \d+;", source).group(0)
            for name in ("CURSOR_SPEED", "CURSOR_TYPING_SPEED"))
        java = "public class TypingCursorTest {\n" + constants + "\n" + block(
            source, "private static float cursorEase(") + r"""
  static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);}
  public static void main(String[] args){
    check(cursorEase(CURSOR_TYPING_SPEED,1f)>cursorEase(CURSOR_SPEED,1f),
      "typing must catch up faster than deliberate caret moves");
    for(int hz:new int[]{60,90,120,144}){
      float dt=1000f/hz,dtNorm=dt/16.6667f,x=0f,target=75f;
      boolean checked=false;
      for(int frame=1;frame*dt<=50f;frame++){
        float delta=target-x;
        x+=Math.min(delta*cursorEase(CURSOR_TYPING_SPEED,dtNorm),120f*dtNorm);
        check(x<=target && x>=0f,"caret must approach without overshoot");
        if(!checked && frame*dt>=17f){
          check(target-x<20f,"caret trails across an already visible word");
          checked=true;
        }
      }
      check(checked,"all refresh rates exercised");
    }
  }
}
"""
        with tempfile.TemporaryDirectory(prefix="typing-cursor-") as temp:
            file = pathlib.Path(temp) / "TypingCursorTest.java"
            file.write_text(java)
            compiled = subprocess.run(["javac", str(file)], capture_output=True, text=True)
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            result = subprocess.run(["java", "-cp", temp, "TypingCursorTest"],
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_ime_diff_window_does_not_copy_or_animate_whole_draft_replacements(self):
        source = SOURCE.read_text()
        before = block(source, "public static void beforeEditorTextChanged(")
        changed = block(source, "private static void handleTextChanged(")
        self.assertIn("st.removedText = canDiffEdit(count, after)", before)
        self.assertIn("boolean boundedEdit = canDiffEdit(before, count)", changed)
        self.assertIn("if (boundedEdit && (appearEnabled || spoilerEnabled)", changed)
        constant = re.search(r"private static final int MAX_TEXT_DIFF_WINDOW = \d+;", source).group(0)
        java = ("public class DiffWindowTest {\n" + constant + "\n"
                + block(source, "private static boolean canDiffEdit(") + "\n"
                + "public static void main(String[] args) {\n"
                + "if (!canDiffEdit(24, 24) || !canDiffEdit(512, 1)"
                + " || canDiffEdit(100000, 1) || canDiffEdit(1, 100000))"
                + " throw new AssertionError(\"unbounded edit diff\");\n}\n}\n")
        with tempfile.TemporaryDirectory(prefix="text-diff-window-") as temp:
            file = pathlib.Path(temp) / "DiffWindowTest.java"
            file.write_text(java)
            compiled = subprocess.run(["javac", str(file)], capture_output=True, text=True)
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            result = subprocess.run(["java", "-cp", temp, "DiffWindowTest"],
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
