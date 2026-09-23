"""Host regressions for the real chat layout hooks and sampling tracker.

No APK/GPU is built. The frame harness models RecyclerView's commit ordering;
the tracker is compiled unchanged, rather than reimplementing its decisions.
"""
import pathlib
import subprocess
import tempfile
import unittest

from test_glass_capture_geometry import JAVA, BLUR, method


class ChatBackdropLayoutTests(unittest.TestCase):
    def run_java(self, name, source):
        with tempfile.TemporaryDirectory(prefix="chat-backdrop-") as temp:
            path = pathlib.Path(temp) / (name + ".java")
            path.write_text(source)
            for command in (
                ["javac", "-d", temp, str(BLUR / "ChatBackdropLayoutTracker.java"), str(path)],
                ["java", "-cp", temp, name],
            ):
                result = subprocess.run(command, capture_output=True, text=True)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_identity_recycling_and_sampling_displacement(self):
        self.run_java("Anchors", r"""
import org.telegram.ui.Components.blur3.ChatBackdropLayoutTracker;
public class Anchors {
 static void near(float a, float b) { if (Math.abs(a-b)>.0001f) throw new AssertionError(a+" != "+b); }
 public static void main(String[] args) {
  ChatBackdropLayoutTracker t = new ChatBackdropLayoutTracker();
  t.begin(); t.after(1,10,50); near(t.finish(),0); // cold: no old image
  t.begin(); t.before(1,10,50); t.after(1,10,50); near(t.finish(),0); // cached no-op
  t.begin(); t.before(1,10,50); t.before(1,11,90);
  // Adapter insertion and a recycled View do not change message identity.
  t.after(1,99,50); t.after(1,11,76.5f); near(t.finish(),13.5f);
  t.begin(); t.before(1,10,50); t.after(2,10,20); near(t.finish(),0); // merged dialog
  t.begin(); t.before(1,10,50); t.after(1,99,20); near(t.finish(),0); // whole window
  t.begin(); t.before(1,10,50); t.before(1,11,90);
  t.after(1,11,80); t.after(1,10,20); near(t.finish(),10); // one stable origin
  t.begin(); t.before(1,10,50); t.onScrolled(7); t.after(1,10,30);
  near(t.finish(),13); // never apply the reported movement twice
  t.begin(); t.before(1,10,50); t.onScrolled(-7); t.after(1,10,57);
  near(t.finish(),0); // regular reverse scroll already accounted for
  t.begin(); t.before(1,0,50); t.after(1,0,1); near(t.finish(),0); // no stable ID
  t.begin(); t.before(1,-4,50); t.after(1,-4,51.25f); near(t.finish(),-1.25f);
  near(t.finish(),0); // commit is one-shot
  t.onScrolled(100); t.begin(); t.before(1,10,50); t.after(1,10,50);
  near(t.finish(),0); // outside-layout finger scroll cannot leak into next commit
 }
}
""")

    def test_real_layout_hooks_commit_once_and_leave_touch_path_alone(self):
        chat = (JAVA / "org/telegram/ui/ChatActivity.java").read_text()
        hooks = "\n".join(method(chat, signature) for signature in (
            "public void onLayoutChildren(RecyclerView.Recycler recycler, RecyclerView.State state)",
            "public void onLayoutCompleted(RecyclerView.State state)",
        ))
        self.run_java("Layout", r"""
import org.telegram.ui.Components.blur3.ChatBackdropLayoutTracker;
class RecyclerView {
 static final int SCROLL_STATE_IDLE=0;
 static class Recycler {}
 static class State { boolean pre; boolean isPreLayout(){return pre;} }
}
class Parent {
 void onLayoutChildren(RecyclerView.Recycler r, RecyclerView.State s) {}
 void onLayoutCompleted(RecyclerView.State s) {}
}
public class Layout extends Parent {
 static class Build { static class VERSION {static int SDK_INT=31;}
                      static class VERSION_CODES {static final int S=31;} }
 static class BuildVars {static boolean DEBUG_PRIVATE_VERSION=false;}
 static class FileLog {static void e(Exception e){throw new AssertionError(e);} }
 static class AndroidUtilities {static void runOnUIThread(Runnable r){r.run();} }
 static class Adapter {void notifyDataSetChanged(boolean b){} }
 static class ListView {int state; boolean fast; int getScrollState(){return state;}
                       boolean isFastScrollAnimationRunning(){return fast;} }
 static class Suppressor {float y; int calls; void onScrolled(float x,float y){this.y+=y; calls++;} }
 Object parentChatActivity;
 Adapter chatAdapter=new Adapter(); ListView chatListView=new ListView();
 Suppressor scrollableViewNoiseSuppressor=new Suppressor();
 ChatBackdropLayoutTracker backdropLayoutTracker=new ChatBackdropLayoutTracker();
 static final int BLUR_INVALIDATE_FLAG_SCROLL=1, BLUR_INVALIDATE_FLAG_POSITIONS=2;
 int flags, captures, scans, message=10; float y=50;
 void recordBackdropLayoutAnchors(boolean before) {
  scans++;
  if(before)backdropLayoutTracker.before(1,message,y);
  else backdropLayoutTracker.after(1,message,y);
 }
 void invalidateMergedVisibleBlurredPositionsAndSources(int f){flags|=f;}
 void preDraw(){if(flags!=0){captures++; flags=0;}}
 static void check(boolean value){if(!value)throw new AssertionError();}
 HOOKS
 public static void main(String[] args) {
  Layout h=new Layout(); RecyclerView.Recycler r=new RecyclerView.Recycler();
  RecyclerView.State s=new RecyclerView.State();
  // Predictive + auto-measure can invoke layout multiple times before commit.
  s.pre=true; h.onLayoutChildren(r,s); h.y=40; h.onLayoutCompleted(s);
  check(h.flags==0 && h.scans==1 && h.scrollableViewNoiseSuppressor.calls==0);
  s.pre=false; h.onLayoutChildren(r,s); h.y=30; h.onLayoutCompleted(s);
  check(h.flags==3 && h.scans==2 && h.scrollableViewNoiseSuppressor.y==20);
  h.preDraw(); h.preDraw(); check(h.captures==1); // no self-invalidating loop
  // Whole-window replacement STILL captures, despite no shared anchor/dy.
  h.onLayoutChildren(r,s); h.message=99; h.onLayoutCompleted(s);
  check(h.flags==3 && h.scrollableViewNoiseSuppressor.calls==1);
  h.preDraw(); check(h.captures==2);
  // Neither drag nor settling/fling scans anchors or changes phase.
  for(int state:new int[]{1,2}) {
   h.chatListView.state=state; int scans=h.scans;
   h.onLayoutChildren(r,s); h.y-=100; h.onLayoutCompleted(s);
   check(h.scans==scans && h.scrollableViewNoiseSuppressor.calls==1);
   h.preDraw();
  }
  h.chatListView.state=0; h.chatListView.fast=true; int scans=h.scans;
  h.onLayoutChildren(r,s); h.onLayoutCompleted(s); check(h.scans==scans);
  // Finger takes over after predictive layout: discard phase correction.
  h.chatListView.fast=false; h.onLayoutChildren(r,s); h.y-=50;
  h.chatListView.state=1; h.onLayoutCompleted(s);
  check(h.scrollableViewNoiseSuppressor.calls==1 && !h.backdropLayoutTracker.isTracking());
  // Parent's combined source is invalidated, never phase-shifted for one child.
  h.chatListView.state=0; h.parentChatActivity=new Object(); scans=h.scans;
  h.onLayoutChildren(r,s); h.onLayoutCompleted(s); check(h.scans==scans && h.flags==3);
 }
}
""".replace("HOOKS", hooks))

    def test_capture_and_alpha_contracts_are_not_replaced_by_a_fade(self):
        chat = (JAVA / "org/telegram/ui/ChatActivity.java").read_text()
        body = method(chat, "private void recordBackdropLayoutAnchors(")
        self.assertIn("message.getDialogId(), message.getId(), child.getY()", body)
        self.assertNotIn("getChildAdapterPosition", body)
        self.assertIn("child.getAlpha() == 0", body)
        body = method(chat, "private void invalidateMergedVisibleBlurredPositionsAndSourcesImpl(")
        self.assertLess(body.index("setupRenderNodes"), body.index("invalidateResultRenderNodes"))
        self.assertIn("contentView::drawList", body)
        # No source-over crossfade (which dims transparent message pixels),
        # snapshot references to recycled Views, delayed capture or alpha tweak.
        for forbidden in ("saveLayerAlpha", "postDelayed", "setAlpha", "ValueAnimator"):
            self.assertNotIn(forbidden, body)
        recycler = (JAVA / "androidx/recyclerview/widget/RecyclerView.java").read_text()
        self.assertIn("dispatchOnScrolled(0, 0)", recycler)
        self.assertLess(recycler.index("mLayout.onLayoutCompleted(mState)"),
                        recycler.index("dispatchOnScrolled(0, 0)"))

    def test_real_capture_refreshes_stable_crops_after_window_replacement(self):
        suppressor = (BLUR / "DownscaleScrollableNoiseSuppressor.java").read_text()
        capture = method(suppressor, "public boolean invalidateResultRenderNodes(IBlur3Capture")
        self.run_java("Capture", r"""
import java.util.*;
public class Capture {
 static class Rect {int left,top,right=100,bottom=100;}
 static class RectF {void set(Rect r){} }
 static class Canvas {void save(){} void translate(int x,int y){} void restore(){} }
 static class Node {boolean ready; boolean hasDisplayList(){return ready;} long getUniqueId(){return 1;} }
 static class SourcePart {Rect position=new Rect(); long lastHash; Node renderNode=new Node();}
 static class Hash {
  long h; boolean unsupported;
  void start(){h=0; unsupported=false;} void add(long v){h=h*31+v;}
  long get(){return h;} boolean isUnsupported(){return unsupported;}
 }
 interface IBlur3Capture {
  void capture(Canvas c, RectF r);
  default void captureCalculateHash(Hash h,RectF r){h.unsupported=true;}
 }
 ArrayList<SourcePart> rectRenderNodes=new ArrayList<>(); int rectRenderNodesCount=1;
 RectF tmpRectF=new RectF(); Hash builder=new Hash(); int recordings, generation, captured;
 void updateGlassMode(){}
 Canvas beginRecordingRect(int a){recordings++; return new Canvas();}
 void endRecordingRect(){rectRenderNodes.get(0).renderNode.ready=true;}
 // Composition may stay cached: its existing RenderNode references see the
 // new raw recording. The caller MUST still invoke this capture method.
 boolean invalidateResultRenderNodes(int w,int h){return false;}
 CAPTURE
 static void check(boolean ok){if(!ok)throw new AssertionError();}
 public static void main(String[] args) {
  Capture h=new Capture(); h.rectRenderNodes.add(new SourcePart());
  IBlur3Capture chat=(c,r)->h.captured=h.generation;
  h.generation=1; h.invalidateResultRenderNodes(chat,100,100);
  check(h.recordings==1 && h.captured==1); // cold
  h.generation=2; h.invalidateResultRenderNodes(chat,100,100);
  check(h.recordings==2 && h.captured==2); // same crop, unrelated recycled window
  IBlur3Capture hashed=new IBlur3Capture() {
   public void capture(Canvas c,RectF r){h.captured=h.generation;}
   public void captureCalculateHash(Hash b,RectF r){b.add(h.generation);}
  };
  h.invalidateResultRenderNodes(hashed,100,100); int n=h.recordings;
  h.invalidateResultRenderNodes(hashed,100,100); check(h.recordings==n); // real cache hit
  h.generation=3; h.invalidateResultRenderNodes(hashed,100,100);
  check(h.recordings==n+1 && h.captured==3); // content changed, composition still cached
  h.rectRenderNodes.get(0).position.top=16;
  h.invalidateResultRenderNodes(hashed,100,100); check(h.recordings==n+2); // crop moved
  h.rectRenderNodes.get(0).renderNode.ready=false;
  h.invalidateResultRenderNodes(hashed,100,100); check(h.recordings==n+3); // cold cached hash
 }
}
""".replace("CAPTURE", capture))


if __name__ == "__main__":
    unittest.main()
