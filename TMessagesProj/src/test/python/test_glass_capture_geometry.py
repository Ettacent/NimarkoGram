"""Execute production crop normalization/merge with a tiny RectF host stub.

This tests geometry/cache contracts, not Android's GPU renderer.
"""
import pathlib
import subprocess
import tempfile
import unittest

JAVA = pathlib.Path(__file__).resolve().parents[2] / "main/java"
BLUR = JAVA / "org/telegram/ui/Components/blur3"


def method(source, signature):
    start = source.index(signature)
    opening = source.index("{", start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]


class CaptureGeometryTests(unittest.TestCase):
    def test_capture_keeps_reentrant_next_frame_request(self):
        source = (JAVA / "org/telegram/messenger/utils/OnPostDrawView.java").read_text()
        harness = """public class FrameHarness {
          interface Callback { void onPostDraw(int flags); }
          boolean onPreDrawMode=true; int invalidateFlags, frames, invalidations;
          Callback callback;
          void invalidate(){invalidations++;}
        """ + method(source, "public void invalidate(int flags)") + method(source, "public boolean onPreDraw()") + """
          public static void main(String[] args) {
            FrameHarness h=new FrameHarness();
            h.callback=(flags)->{
              h.frames++;
              if(flags!=h.frames)throw new AssertionError("lost flags");
              if(h.frames==1)h.invalidate(2);
            };
            h.invalidate(1); h.onPreDraw();
            if(h.invalidateFlags!=2)throw new AssertionError("next frame lost");
            h.onPreDraw(); h.onPreDraw();
            if(h.frames!=2 || h.invalidations!=2 || h.invalidateFlags!=0)
              throw new AssertionError("unexpected cadence");
          }
        }"""
        with tempfile.TemporaryDirectory(prefix="glass-frames-") as temp:
            file = pathlib.Path(temp) / "FrameHarness.java"
            file.write_text(harness)
            subprocess.run(["javac", str(file)], check=True, capture_output=True)
            subprocess.run(["java", "-cp", temp, "FrameHarness"], check=True, capture_output=True)

    def test_default_preserves_existing_choice_and_power_saver(self):
        source = (JAVA / "org/telegram/messenger/LiteMode.java").read_text()
        body = method(source, "public static void loadPreference()")
        self.assertIn("defaultValue &= ~FLAG_LIQUID_GLASS;", body)
        self.assertNotIn("defaultValue |= FLAG_LIQUID_GLASS", body)
        self.assertIn('value = preferences.getInt("lite_mode6", defaultValue);', body)
        self.assertIn("public static int PRESET_POWER_SAVER = 0;", source)
        self.assertIn("return PRESET_POWER_SAVER;", method(source, "public static int getValue(boolean"))

    def test_production_geometry(self):
        source = (BLUR / "DownscaleScrollableNoiseSuppressor.java").read_text()
        methods = "\n".join(method(source, s) for s in (
            "private static int roundDown(", "public static int roundUp(",
            "public void setupRenderNodes("))
        rect = """package android.graphics;
public class RectF {
 public float left, top, right, bottom;
 public RectF() {} public RectF(float l,float t,float r,float b){set(l,t,r,b);}
 public void set(float l,float t,float r,float b){left=l;top=t;right=r;bottom=b;}
 public void set(RectF r){set(r.left,r.top,r.right,r.bottom);}
 public boolean isEmpty(){return left>=right || top>=bottom;}
}
"""
        harness = """import java.util.*; import android.graphics.RectF;
import org.telegram.messenger.utils.RectFMergeBounding;
public class CaptureHarness {
 private int rectRenderNodesCount;
 private int invalidations;
 private final ArrayList<RectF> alignedPositions = new ArrayList<>();
 private final ArrayList<RectF> mergedAlignedPositions = new ArrayList<>();
 private final ArrayList<SourcePart> rectRenderNodes = new ArrayList<>();
 private static class SourcePart { RectF r = new RectF(); void setPosition(RectF p){r.set(p);} }
 private void invalidateCapturePositions() { invalidations++; }
""" + methods + """
 static void check(boolean ok) { if(!ok) throw new AssertionError(); }
 public static void main(String[] args) {
  CaptureHarness h = new CaptureHarness();
  // Separate islands overlap after grid alignment. They must be painted ONCE.
  h.setupRenderNodes(Arrays.asList(new RectF(0,0,100,17),new RectF(0,20,100,35)),2);
  check(h.rectRenderNodesCount == 1);
  check(h.rectRenderNodes.get(0).r.bottom == 48);
  // Negative overscan and exact grid edges must round outward, idempotently.
  check(roundDown(-.5f,16)==-16); check(roundUp(-.5f,16)==0);
  check(roundUp(32,16)==32); check(roundDown(-32,16)==-32);
  for(int i=-500;i<=500;i++) {
   float x=i/3f; check(roundDown(x,16)<=x); check(roundUp(x,16)>=x);
   check(roundDown(roundDown(x,16),16)==roundDown(x,16));
   check(roundUp(roundUp(x,16),16)==roundUp(x,16));
  }
  // Invalid / zero-area crops never acquire a RenderNode.
  h.setupRenderNodes(Arrays.asList(new RectF(0,30,50,10),new RectF(0,0,0,10),
   new RectF(Float.NaN,0,10,10),new RectF(0,64,100,80)),4);
  check(h.rectRenderNodesCount==1); check(h.rectRenderNodes.get(0).r.top==64);
  // Remove all panels, then restore separated panels using the same buffers.
  h.setupRenderNodes(Collections.emptyList(),0); check(h.rectRenderNodesCount==0);
  h.setupRenderNodes(Arrays.asList(new RectF(0,0,50,16),new RectF(0,80,50,96)),2);
  check(h.rectRenderNodesCount==2);
  check(h.rectRenderNodes.get(0).r.bottom<=h.rectRenderNodes.get(1).r.top);
  check(h.invalidations>0);
 }
}
"""
        with tempfile.TemporaryDirectory(prefix="glass-geometry-") as temp:
            root = pathlib.Path(temp)
            (root / "RectF.java").write_text(rect)
            (root / "RectFMergeBounding.java").write_text(
                (JAVA / "org/telegram/messenger/utils/RectFMergeBounding.java").read_text())
            (root / "CaptureHarness.java").write_text(harness)
            subprocess.run(["javac", "-d", temp, *map(str, root.glob("*.java"))], check=True, capture_output=True)
            subprocess.run(["java", "-cp", temp, "CaptureHarness"], check=True, capture_output=True)

    def test_cache_reconciles_removed_crops(self):
        source = (BLUR / "DownscaleScrollableNoiseSuppressor.java").read_text()
        body = method(source, "public boolean invalidateResultRenderNodes(IBlur3Capture")
        self.assertNotIn("if (updatedCount > 0)", body)
        for edge in ("left", "top", "right", "bottom"):
            self.assertIn(f"builder.add(position.{edge});", body)
        drawable = (BLUR / "drawable/BlurredBackgroundDrawableRenderNode.java").read_text()
        self.assertIn("(oldAlpha == 0) != (alpha == 0)", drawable)

    def test_capture_readiness_and_snapshot_invalidation_in_both_modes(self):
        source = (BLUR / "DownscaleScrollableNoiseSuppressor.java").read_text()
        source_dispatch = method((BLUR / "source/BlurredBackgroundSourceRenderNode.java").read_text(),
                                 "public void dispatchOnDrawablesRelativePositionChange()")
        self.assertIn("modeGraphChanged = true;", method(source, "private void updateGlassMode()"))
        production = "\n".join(method(source, signature) for signature in (
            "public void draw(Canvas canvas, int index)",
            "private int resolveInlineIndex(int index)",
            "public boolean isDisplayListReady(int index)",
            "private boolean isDisplayListReadyAt(int a)",
            "public void invalidateCapturePositions()",
            "public void onScrolled(float dx, float dy)",
            "public boolean invalidateResultRenderNodes(IBlur3Capture capture, int width, int height)"))
        harness = r"""import java.util.*;
public class CaptureLifecycle {
  static class Build {
    static class VERSION { static int SDK_INT=35; }
    static class VERSION_CODES { static final int S=31; }
  }
  static class BackdropSource {
    CaptureLifecycle scrollableNoiseSuppressor;
    Runnable onDrawablesRelativePositionChangeListener;
    SOURCE_DISPATCH
  }
  static final int DRAW_GLASS=-2, DRAW_FROSTED_GLASS=-3, DRAW_FROSTED_GLASS_NO_SATURATION=-4;
  static class Canvas {
    RenderNode drawn;
    boolean isHardwareAccelerated(){return true;}
    void drawRenderNode(RenderNode node){drawn=node;}
    void save(){} void restore(){} void translate(float x,float y){}
  }
  static class RenderNode {
    int width,height,recordings;
    RenderNode(String name){}
    int getWidth(){return width;} int getHeight(){return height;}
    boolean hasDisplayList(){return recordings>0;}
    void setPosition(int l,int t,int r,int b){width=r-l;height=b-t;}
    Canvas beginRecording(){recordings++;return new Canvas();}
    void endRecording(){}
  }
  static class Rect {
    int left,top,right,bottom;
    Rect(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;}
    int width(){return right-left;} int height(){return bottom-top;}
  }
  static class RectF {
    void set(Rect r){}
  }
  static class HashBuilder {
    long hash;
    void start(){hash=0;}
    void add(long v){hash=hash*31+v;}
    long get(){return hash;}
    boolean isUnsupported(){return false;}
  }
  interface IBlur3Capture {
    void captureCalculateHash(HashBuilder b,RectF p);
    void capture(Canvas c,RectF p);
  }
  class SourcePart {
    boolean derivedReady=true;
    boolean isReady(){return renderNode.hasDisplayList() && derivedReady;}
    final Rect position=new Rect(0,0,64,48);
    final RenderNode renderNode=new RenderNode("crop");
    long lastHash;
    void updateSamplingPhase(){}
  }
  boolean isLiquidGlassEnabled, simpleMode, compositionDirty=true, modeGraphChanged;
  float scrollPhaseX,scrollPhaseY;
  long capturePositionsGeneration,capturedPositionsGeneration=-1;
  Rect recordingPos;
  final ArrayList<SourcePart> rectRenderNodes=new ArrayList<>();
  final RenderNode[] resultRenderNodes={new RenderNode("glass"),new RenderNode("frost")};
  int rectRenderNodesCount;
  final RectF tmpRectF=new RectF();
  final HashBuilder builder=new HashBuilder();
  CaptureLifecycle(boolean liquid,boolean simple){
    isLiquidGlassEnabled=liquid;simpleMode=simple;
    rectRenderNodes.add(new SourcePart());rectRenderNodesCount=1;
  }
  void updateGlassMode(){}
  RenderNode getRenderNode(int a,int b){return rectRenderNodes.get(b).renderNode;}
  Canvas beginRecordingRect(int index){
    SourcePart part=rectRenderNodes.get(index);
    part.renderNode.setPosition(0,0,part.position.width(),part.position.height());
    return part.renderNode.beginRecording();
  }
  void endRecordingRect(){rectRenderNodes.get(0).renderNode.endRecording();rectRenderNodes.get(0).derivedReady=true;}
  boolean invalidateResultRenderNodes(int width,int height){
    if(!compositionDirty)return false;
    for(RenderNode node:resultRenderNodes){
      node.setPosition(0,0,width,height);node.beginRecording();node.endRecording();
    }
    compositionDirty=false;return true;
  }
  PRODUCTION
  static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
  public static void main(String[] args){
    for(boolean liquid:new boolean[]{false,true})for(boolean simple:new boolean[]{false,true}){
      CaptureLifecycle h=new CaptureLifecycle(liquid,simple);
      int index=DRAW_GLASS;
      Canvas cold=new Canvas();h.draw(cold,index);
      check(cold.drawn==null && !h.isDisplayListReady(index),"cold frame exposed crop");
      final int[] version={1},captures={0};
      IBlur3Capture capture=new IBlur3Capture(){
        public void captureCalculateHash(HashBuilder b,RectF p){b.add(version[0]);}
        public void capture(Canvas c,RectF p){captures[0]++;}
      };
      check(h.invalidateResultRenderNodes(capture,320,240),"first capture not published");
      check(h.isDisplayListReady(index) && captures[0]==1,"first frame not ready");
      Canvas warm=new Canvas();h.draw(warm,index);
      int expected=!liquid&&!simple?1:0;
      check(warm.drawn==h.resultRenderNodes[expected],"wrong material slot");
      check(!h.invalidateResultRenderNodes(capture,320,240) && captures[0]==1,
            "unchanged snapshot republished");
      h.modeGraphChanged=true;
      check(h.invalidateResultRenderNodes(capture,320,240) && !h.modeGraphChanged
            && captures[0]==1,"material graph change not published");
      check(!h.invalidateResultRenderNodes(capture,320,240),
            "material change republished twice");
      version[0]++;
      check(h.invalidateResultRenderNodes(capture,320,240) && captures[0]==2,
            "new snapshot hidden by stable composition IDs");
      long generation=h.capturePositionsGeneration;
      h.onScrolled(0,0);
      check(h.capturePositionsGeneration==generation && h.isDisplayListReady(index),
            "zero scroll invalidated snapshot");
      h.onScrolled(.25f,-.5f);
      check(h.capturePositionsGeneration==generation+1 && !h.isDisplayListReady(index),
            "scroll exposed old snapshot");
      Canvas pending=new Canvas();h.draw(pending,index);
      check(pending.drawn==null,"pending snapshot drawn");
      check(h.invalidateResultRenderNodes(capture,320,240) && captures[0]==3,
            "scroll with stable content hash did not recapture");
      check(h.isDisplayListReady(index),"scroll capture did not restore readiness");
      BackdropSource source=new BackdropSource();
      source.scrollableNoiseSuppressor=h;
      final int[] requests={0};
      source.onDrawablesRelativePositionChangeListener=()->requests[0]++;
      for(int frame=0;frame<30;frame++){
        source.dispatchOnDrawablesRelativePositionChange();
        Canvas resizing=new Canvas();h.draw(resizing,index);
        check(resizing.drawn!=null && h.isDisplayListReady(index),
              "resizing an island erased the committed backdrop");
      }
      check(requests[0]==30,"new island geometry not scheduled for capture");
      h.invalidateCapturePositions();
      check(!h.isDisplayListReady(index),"position request exposed old crop");
      check(h.invalidateResultRenderNodes(capture,320,240) && captures[0]==4,
            "position request not published");
      check(h.isDisplayListReady(index),"position capture not ready");
      // A descendant display list can be discarded even with unchanged
      // geometry, content hash and a surviving root recording.
      h.rectRenderNodes.get(0).derivedReady=false;
      check(!h.isDisplayListReady(index),"lost derived graph reported ready");
      check(h.invalidateResultRenderNodes(capture,320,240) && captures[0]==5,
            "stable content hash skipped lost graph recovery");
      check(h.isDisplayListReady(index),"derived graph not recovered");
      check(!h.invalidateResultRenderNodes(capture,320,240) && captures[0]==5,
            "recovered graph recaptured while idle");
      h.rectRenderNodesCount=0;
      h.invalidateCapturePositions();
      check(h.invalidateResultRenderNodes(capture,320,240),
            "removed crop not published");
      Canvas hidden=new Canvas();h.draw(hidden,index);
      check(hidden.drawn==null && !h.isDisplayListReady(index),
            "removed crop remains visible");
    }
  }
}
""".replace("PRODUCTION", production).replace("SOURCE_DISPATCH", source_dispatch)
        with tempfile.TemporaryDirectory(prefix="glass-lifecycle-") as temp:
            file = pathlib.Path(temp) / "CaptureLifecycle.java"
            file.write_text(harness)
            result = subprocess.run(["javac", str(file)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            result = subprocess.run(["java", "-cp", temp, "CaptureLifecycle"],
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)

    def test_info_cards_opt_in_and_release_backdrop(self):
        source = (JAVA / "app/nimarkogram/messenger/infocards/BaseInfoCard.java").read_text()
        body = method(source, "private void updateGlassBackground()")
        self.assertIn("glassBackgroundFactory.supportsLiquidGlass()", body)
        self.assertIn("LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS)", body)
        self.assertIn("SharedConfig.chatBlurEnabled()", body)
        self.assertIn("releaseGlassBackground()", method(source, "protected void onDetachedFromWindow()"))
        self.assertIn("View.LAYER_TYPE_NONE", method(source, "public void setCardLayerType("))


if __name__ == "__main__":
    unittest.main()
