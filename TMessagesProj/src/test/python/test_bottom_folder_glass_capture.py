"""Execute DialogsActivity's region assembly without an Android/app build."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

JAVA = Path(__file__).resolve().parents[2] / "main/java"
DIALOGS = JAVA / "org/telegram/ui/DialogsActivity.java"


def method(source, signature):
    start = source.index(signature)
    brace = source.index("{", start)
    depth = 1
    end = brace + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]


class BottomFolderGlassCaptureTests(unittest.TestCase):
    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "JDK required")
    def test_production_regions(self):
        source = DIALOGS.read_text()
        methods = "\n".join(method(source, signature) for signature in (
            "private void blur3_UpdateBlur(int flags)",
            "private void addBlur3ViewCapturePosition(",
            "private void addBlur3CapturePosition(",
        ))
        rect = '''package android.graphics;
public class RectF {
 public float left, top, right, bottom;
 public RectF() {} public RectF(RectF r) {set(r);}
 public void set(RectF r) {set(r.left,r.top,r.right,r.bottom);}
 public void set(float l,float t,float r,float b) {left=l;top=t;right=r;bottom=b;}
 public void setEmpty() {set(0,0,0,0);}
 public boolean isEmpty() {return left>=right || top>=bottom;}
 public void inset(float x,float y) {left+=x;right-=x;top+=y;bottom-=y;}
 public boolean intersect(float l,float t,float r,float b) {
  if (left<r && l<right && top<b && t<bottom) {
   set(Math.max(left,l),Math.max(top,t),Math.min(right,r),Math.min(bottom,b));return true;
  } return false;
 }
}'''
        harness = '''import android.graphics.RectF;
import java.util.ArrayList;
import org.telegram.messenger.utils.RectFMergeBounding;
public class DialogsActivity {
 static final int MAIN_TABS_MARGIN=12, MAIN_TABS_HEIGHT=48;
 static class View {
  static final int VISIBLE=0, GONE=8;
  int width=400,height=50,visibility=VISIBLE; float x,y,alpha=1; boolean attached=true;
  int getWidth(){return width;} int getHeight(){return height;}
  int getMeasuredWidth(){return width;} int getMeasuredHeight(){return height;}
  int getVisibility(){return visibility;} float getAlpha(){return alpha;}
  int getSumHeightOfAllVisibleChild(){return height;}
 }
 static class ViewPositionWatcher {
  static boolean computeRectInParent(View v,View root,RectF out) {
   if(!v.attached)return false; out.set(v.x,v.y,v.x+v.width,v.y+v.height);return true;
  }
 }
 static class LiteMode {static final int FLAG_LIQUID_GLASS=1;
  static boolean enabled=true; static boolean isEnabled(int f){return enabled;}}
 static class DialogStoriesCell {static final int HEIGHT_IN_DP=80;}
 static class Animator {float getFloatValue(){return 0;}}
 static class Source {void setSize(int w,int h){} void updateDisplayListIfNeeded(){}}
 static class Suppressor {
  ArrayList<RectF> regions=new ArrayList<>(); int setups;
  void setupRenderNodes(ArrayList<RectF> p,int count){
   setups++;regions.clear();for(int i=0;i<count;i++)regions.add(new RectF(p.get(i)));
  }
  void invalidateResultRenderNodes(Object capture,int w,int h){}
 }
 View fragmentView=new View(),actionBar=new View(),filterTabsView=new View();
 View homeInfoCards,topPanelLayout,searchTabsAndFiltersLayout,commentView,chatInputViewsContainer;
 Object iBlur3Capture=new Object(); boolean bottom=true,hasMainTabs,hasStories;
 int navigationBarHeight=24; float scrollYOffset;
 Animator animatorSearchVisible=new Animator();
 Source iBlur3SourceGlassFrosted=new Source(),iBlur3SourceGlass=new Source();
 Suppressor scrollableViewNoiseSuppressor=new Suppressor();
 ArrayList<RectF> iBlur3Positions=new ArrayList<>(),iBlur3PositionsMerged=new ArrayList<>();
 RectF iBlur3PositionActionBar=new RectF(),iBlur3PositionMainTabs=new RectF();
 RectF iBlur3PositionFolders=new RectF(),iBlur3PositionHomeInfoCards=new RectF();
 boolean isBlur3Enabled(){return true;} boolean foldersAtBottom(){return bottom;}
 int dp(float n){return Math.round(n);} int getSearchFieldReservedHeight(){return 50;}
 int calculateListViewPaddingBottom(){return 120;}
 float lerp(float a,float b,float t){return a+(b-a)*t;}
 METHODS
 static void check(boolean ok){if(!ok)throw new AssertionError();}
 boolean covers(float y){for(RectF r:scrollableViewNoiseSuppressor.regions)
  if(r.top<=y && r.bottom>=y)return true;return false;}
 void update(){int before=scrollableViewNoiseSuppressor.setups;blur3_UpdateBlur(1);
  check(scrollableViewNoiseSuppressor.setups==before+1);
  for(RectF r:scrollableViewNoiseSuppressor.regions)
   check(!r.isEmpty() && r.left>=0 && r.top>=0 && r.right<=400 && r.bottom<=fragmentView.height);
 }
 public static void main(String[] args){
  DialogsActivity a=new DialogsActivity();a.fragmentView.height=800;
  a.filterTabsView.x=4;a.filterTabsView.width=392;a.filterTabsView.y=650;
  a.update();check(a.covers(675));check(a.scrollableViewNoiseSuppressor.regions.size()==2);
  check(a.iBlur3PositionActionBar.bottom==148); // bottom strip is not part of header
  a.hasMainTabs=true;a.update();check(a.covers(750));
  check(a.scrollableViewNoiseSuppressor.regions.size()==2); // bottom kernels merge
  a.hasMainTabs=false;a.filterTabsView.y=550;a.update();
  check(a.covers(575) && !a.covers(675)); // moved by IME/margin, no stale capture
  a.filterTabsView.visibility=View.GONE;a.update();check(!a.covers(575));
  a.filterTabsView.visibility=View.VISIBLE;a.filterTabsView.alpha=0;a.update();check(!a.covers(575));
  a.filterTabsView.alpha=1;a.filterTabsView.attached=false;a.update();check(!a.covers(575));
  a.filterTabsView.attached=true;a.bottom=false;a.filterTabsView.y=100;a.update();
  check(a.scrollableViewNoiseSuppressor.regions.size()==1 && a.covers(125));
  a.homeInfoCards=new View();a.homeInfoCards.y=350;a.homeInfoCards.x=260;a.homeInfoCards.width=120;
  a.update();check(a.covers(375)); // independent floating chip outside header
  a.homeInfoCards.alpha=0;a.update();check(!a.covers(375));
  a.bottom=true;a.fragmentView.height=500;a.filterTabsView.y=470;a.update();check(a.covers(495));
  a.filterTabsView.y=600;a.update();check(a.scrollableViewNoiseSuppressor.regions.size()==1);
  LiteMode.enabled=false;a.filterTabsView.y=350;a.update();
  check(a.iBlur3PositionFolders.top==302); // frosted fallback retains its larger kernel
  a.commentView=new View();a.chatInputViewsContainer=new View();a.update();check(a.covers(480));
 }
}'''.replace("METHODS", methods)
        with tempfile.TemporaryDirectory(prefix="bottom-folder-glass-") as temp:
            root = Path(temp)
            rect_path = root / "android/graphics/RectF.java"
            rect_path.parent.mkdir(parents=True)
            rect_path.write_text(rect)
            harness_path = root / "DialogsActivity.java"
            harness_path.write_text(harness)
            merge = JAVA / "org/telegram/messenger/utils/RectFMergeBounding.java"
            result = subprocess.run(["javac", "-d", temp, str(rect_path), str(merge), str(harness_path)],
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            result = subprocess.run(["java", "-cp", temp, "DialogsActivity"], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)

    def test_capture_excludes_glass_overlays_and_lifecycle_is_wired(self):
        source = DIALOGS.read_text()
        capture = source.split("iBlur3Capture = (canvas, position) -> {", 1)[1].split("\n        };", 1)[0]
        self.assertIn("viewPage.listView", capture)
        self.assertIn("searchViewPager", capture)
        for forbidden in ("contentView.draw", "filterTabsView", "homeInfoCards"):
            self.assertNotIn(forbidden, capture)
        for view in ("filterTabsView", "homeInfoCards"):
            self.assertIn(f"viewPositionWatcher.subscribe({view}, contentView,", source)
        self.assertIn("homeInfoCards.setGlassBackgroundFactory(iBlur3FactoryLiquidGlass)", source)
        self.assertIn("fragmentSearchField.setInfoCardsGlassBackgroundFactory(iBlur3FactoryLiquidGlass)", source)
        visibility = method(source, "private void checkUi_filterTabsVisible()")
        self.assertIn("blur3_InvalidateBlur()", visibility)
        update = method(source, "private void blur3_UpdateBlur(int flags)")
        self.assertNotIn("NimarkoFoldersHelper", update)
        self.assertEqual(update.count("scrollableViewNoiseSuppressor.setupRenderNodes("), 1)


if __name__ == "__main__":
    unittest.main()
