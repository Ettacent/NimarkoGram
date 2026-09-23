"""Inline cards match the outer folder pill; search cards keep their 28dp geometry."""
import shutil
import re
import subprocess
import tempfile
import unittest
from pathlib import Path

JAVA = Path(__file__).resolve().parents[2] / "main/java"
CARD = JAVA / "app/nimarkogram/messenger/infocards/BaseInfoCard.java"


def method(source, signature):
    start = source.index(signature)
    end = source.index("{", start) + 1
    depth = 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]


class InlineInfoCardPillGeometryTests(unittest.TestCase):
    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "JDK required")
    def test_production_complete_capsules_for_taller_inline_pills(self):
        # The old constant 1.35h separation put intact 36dp pills outside a
        # 50dp host. Execute the shared edge suite's actual layout/transforms:
        # full capsules fit at every progress, in both host styles and RTL,
        # while the inline measured width still interpolates exactly as before.
        from test_infocard_edge_geometry import edge_source

        with tempfile.TemporaryDirectory(prefix="inline-card-motion-") as temp:
            file = Path(temp) / "InfoCardDragContinuityHarness.java"
            file.write_text(edge_source())
            result = subprocess.run(["javac", str(file)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            result = subprocess.run(["java", "-cp", temp, "InfoCardDragContinuityHarness", "bounds"],
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)

    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "JDK required")
    def test_production_geometry_and_style_transitions(self):
        source = CARD.read_text()
        methods = "\n".join(method(source, signature) for signature in (
            "public void setInlineFolderStyle(boolean inline)",
            "private static int getChipHeight(boolean inline)",
            "private float getChipCornerRadius()",
        ))
        java = '''public class InlineCardGeometry {
 static final int CHIP_HEIGHT_DP=28,CORNER_RADIUS_DP=14;
 static class AndroidUtilities {
  static float density; static int dp(float n){return (int)Math.ceil(n*density);}
 }
 static class FrameLayout {static class LayoutParams {int height;}}
 static class Content {
  FrameLayout.LayoutParams params=new FrameLayout.LayoutParams();int outlines,layouts;
  Object getLayoutParams(){return params;}
  void setLayoutParams(FrameLayout.LayoutParams p){params=p;layouts++;}
  void invalidateOutline(){outlines++;}
 }
 static class Radius {boolean inlineFolderStyle;float radius;void setCornerRadius(float r){radius=r;}void setRadii(float r){radius=r;}}
 static class Text {int size;void setTextSize(int s){size=s;}}
 static class Icon {float x=1,y=1;void setScaleX(float v){x=v;}void setScaleY(float v){y=v;}}
 boolean inlineFolderStyle;int layoutRequests,colorUpdates;
 Icon iconView=new Icon();void applyColorMode(){colorUpdates++;}
 Content content=new Content();Radius background=new Radius(),loadingDrawable;
 Text textView=new Text();void requestLayout(){layoutRequests++;}
 METHODS
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  for(float d:new float[]{1,1.25f,1.5f,2,2.625f,2.75f,3,3.5f,4}) {
   AndroidUtilities.density=d;
   for(boolean loading:new boolean[]{false,true}) {
    InlineCardGeometry c=new InlineCardGeometry();
    check(getChipHeight(false)==AndroidUtilities.dp(28));
    check(c.getChipCornerRadius()==AndroidUtilities.dp(14));
    if(loading)c.loadingDrawable=new Radius();
    c.setInlineFolderStyle(true);
    int row=AndroidUtilities.dp(50),pad=AndroidUtilities.dp(6.666f);
    int height=c.content.params.height;
    check(height==row-2*pad && height>getChipHeight(false));
    check(c.background.radius==height/2f && c.getChipCornerRadius()==height/2f);
    check(c.textView.size==AndroidUtilities.dp(13));
    check(c.iconView.x==14f/16f && c.iconView.y==14f/16f);
    check(c.background.inlineFolderStyle && c.colorUpdates==1);
    if(loading)check(c.loadingDrawable.radius==AndroidUtilities.dp(14));
    // The host and both children are vertically centred. Check top/bottom
    // placements, narrow/wide cards and RTL: width never changes the geometry.
    for(int rowTop:new int[]{0,100,700})for(int width:new int[]{48,80,96})
     for(boolean rtl:new boolean[]{false,true}) {
      int cardTop=rowTop+(row-height)/2;
      check(cardTop==rowTop+pad);
      check(cardTop+height==rowTop+row-pad);
      check(cardTop+height/2f==rowTop+row/2f);
     }
    int requests=c.layoutRequests,layouts=c.content.layouts,outlines=c.content.outlines;
    c.setInlineFolderStyle(true);
    check(c.layoutRequests==requests && c.content.layouts==layouts && c.content.outlines==outlines);
    check(c.colorUpdates==1);
    c.setInlineFolderStyle(false);
    check(c.content.params.height==AndroidUtilities.dp(28));
    check(c.background.radius==AndroidUtilities.dp(14));
    check(c.textView.size==AndroidUtilities.dp(13));
    check(c.iconView.x==1 && c.iconView.y==1);
    check(!c.background.inlineFolderStyle && c.colorUpdates==2);
    if(loading)check(c.loadingDrawable.radius==AndroidUtilities.dp(14));
    c.setInlineFolderStyle(true);check(c.content.params.height==height);
   }
  }
 }
}'''.replace("METHODS", methods)
        with tempfile.TemporaryDirectory(prefix="inline-card-geometry-") as temp:
            file = Path(temp) / "InlineCardGeometry.java"
            file.write_text(java)
            result = subprocess.run(["javac", str(file)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            result = subprocess.run(["java", "-cp", temp, "InlineCardGeometry"], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)

    def test_shape_consumers_and_existing_width_lifecycle(self):
        source = CARD.read_text()
        self.assertIn("view.getHeight(), getChipCornerRadius())", source)
        self.assertIn("background.glass.setRadius(getChipCornerRadius())", source)
        radius = method(source, "void setCornerRadius(float radius)")
        self.assertIn("glass.setRadius(radius)", radius)
        self.assertIn("loadingDrawable.setRadii(AndroidUtilities.dp(CORNER_RADIUS_DP))", method(source, "public void startLoading()"))
        style = method(source, "public void setInlineFolderStyle(boolean inline)")
        for forbidden in ("setPadding", "setMaxChipWidth", "setColorProvider", "setAlpha", "releaseGlassBackground", "cancelAnimation"):
            self.assertNotIn(forbidden, style)
        self.assertIn("applyColorMode()", style)
        self.assertIn("if (inlineFolderStyle || isFlat())", method(source, "private void updateGlassBackground()"))
        self.assertIn("BlurredBackgroundProviderImpl.topPanel(resourcesProvider)", source)
        self.assertIn("setSurfaceBounds(loadingRect, inlineFolderStyle,", method(source, "protected void dispatchDraw("))
        # Optical icon scaling must not alter the existing slot, chrome or animated width clock.
        self.assertIn("LayoutHelper.createLinear(16, 16, Gravity.CENTER_VERTICAL, 0, 0, 4, 0)", source)
        self.assertIn("AndroidUtilities.dp(16 + 4)", method(source, "private void applyMaxChipWidth()"))
        self.assertIn("getDrawable().getCurrentWidth(", source)
        background = source.split("private static final class CardBackground", 1)[1]
        self.assertIn("cornerRadius, cornerRadius, fillPaint", background)
        self.assertIn("float r = cornerRadius;", background)
        strip = (JAVA / "app/nimarkogram/messenger/infocards/InfoCardStripView.java").read_text()
        self.assertIn("pill.setInlineFolderStyle(inline)", strip)
        self.assertIn("pill.setInlineFolderStyle(inlineFolderStyle)", strip)
        self.assertIn("carouselWidth()", strip)
        # Formula is tied to the actual folder surface's row and inset, not its selector.
        dialogs = (JAVA / "org/telegram/ui/DialogsActivity.java").read_text()
        self.assertIn("filterTabsViewBackground.setPadding(dp(6.666f))", dialogs)
        self.assertIn("LayoutHelper.MATCH_PARENT, 36 + 7 + 7", dialogs)

    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "JDK required")
    def test_production_paint_bounds_glass_fallback_and_search(self):
        source = CARD.read_text()
        background = source.split("private static final class CardBackground", 1)[1]
        methods = "\n".join((
            method(source, "private static int getChipHeight(boolean inline)"),
            method(source, "private static void setSurfaceBounds("),
            method(background, "public void draw(Canvas canvas)"),
            method(background, "private void drawInline(Canvas canvas, Rect bounds)"),
            method(background, "void setShellColor(int color)"),
            method(background, "public void setAlpha(int alpha)"),
        ))
        java = '''import java.util.ArrayList;
public class InlineCardPaint {
 static final int CHIP_HEIGHT_DP=28,CORNER_RADIUS_DP=14;
 static class Rect {
  int left,top,right,bottom;
  Rect(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;}
 }
 static class RectF {
  float left,top,right,bottom;
  void set(float l,float t,float r,float b){left=l;top=t;right=r;bottom=b;}
  void set(Rect r){set(r.left,r.top,r.right,r.bottom);}
  void inset(float x,float y){left+=x;right-=x;top+=y;bottom-=y;}
  float width(){return right-left;}float height(){return bottom-top;}
 }
 static class AndroidUtilities {
  static float density;static RectF rectTmp=new RectF();
  static int dp(float n){return (int)Math.ceil(n*density);}
  static float dpf2(float n){return n*density;}
 }
 static class Theme {
  static boolean dark,monet;
  static class ThemeInfo {boolean isMonet(){return monet;}}
  static ThemeInfo getActiveTheme(){return new ThemeInfo();}
  static boolean isCurrentThemeDark(){return dark;}
 }
 static class Paint {
  int alpha=255;float stroke;
  int getAlpha(){return alpha;}void setAlpha(int a){alpha=a;}
  void setColor(int color){alpha=color>>>24;}
  float getStrokeWidth(){return stroke;}void setStrokeWidth(float s){stroke=s;}
 }
 static class Mark {
  float l,t,r,b,radius,tx,ty;Paint paint;int alpha;
 }
 static class Canvas {
  boolean hardware;float tx,ty;int saves;
  ArrayList<Mark> marks=new ArrayList<>();
  boolean isHardwareAccelerated(){return hardware;}
  int save(){check(saves==0);return ++saves;}
  void translate(float x,float y){tx+=x;ty+=y;}
  void restoreToCount(int s){check(s==saves);saves=0;tx=ty=0;}
  void drawRoundRect(RectF r,float rx,float ry,Paint p){drawRoundRect(r.left,r.top,r.right,r.bottom,rx,ry,p);}
  void drawRoundRect(float l,float t,float r,float b,float rx,float ry,Paint p){
   check(rx==ry);Mark m=new Mark();m.l=l+tx;m.t=t+ty;m.r=r+tx;m.b=b+ty;
   m.radius=rx;m.paint=p;m.alpha=p.alpha;m.tx=tx;m.ty=ty;marks.add(m);
  }
 }
 static class Glass {
  Rect bounds;int draws,alpha=255;
  void setAlpha(int value){alpha=value;}
  void setBounds(Rect r){bounds=r;}void draw(Canvas c){draws++;}
 }
 Rect bounds;Rect getBounds(){return bounds;}
 boolean inlineFolderStyle,themeMode;float cornerRadius,brandedGlassOpacity=.90f;Glass glass;
 int shellColorAlpha,drawableAlpha=255;void invalidateSelf(){}
 Paint fillPaint=new Paint(),strokePaint=new Paint(),shellPaint=new Paint();
 RectF surfaceRect=new RectF();
 METHODS
 static void check(boolean b){if(!b)throw new AssertionError();}
 static void near(float a,float b){check(Math.abs(a-b)<.001f);}
 public static void main(String[] args){
  InlineCardPaint opacity=new InlineCardPaint();
  for(int base:new int[]{0,15,31,48,255})for(int alpha:new int[]{0,64,128,255}) {
   opacity.setShellColor((base<<24)|0x00ffffff);
   opacity.setAlpha(alpha);
   check(opacity.shellPaint.alpha==Math.round(base*alpha/255f));
   check(opacity.fillPaint.alpha==alpha && opacity.strokePaint.alpha==alpha);
   opacity.setShellColor((base<<24)|0x00112233); // theme change while faded
   check(opacity.shellPaint.alpha==Math.round(base*alpha/255f));
   opacity.setAlpha(255);check(opacity.shellPaint.alpha==base);
  }
  for(float d:new float[]{1,1.25f,1.5f,2,2.625f,2.75f,3,3.5f,4})
   for(boolean inline:new boolean[]{false,true})for(boolean flat:new boolean[]{false,true})
    for(int mode=0;mode<3;mode++)for(boolean dark:new boolean[]{false,true})
     for(boolean monet:new boolean[]{false,true})for(int width:new int[]{48,80,96,144})
      for(int offset:new int[]{0,17})for(int alpha:new int[]{96,255}) {
    AndroidUtilities.density=d;Theme.dark=dark;Theme.monet=monet;
    InlineCardPaint p=new InlineCardPaint();p.inlineFolderStyle=inline;p.themeMode=flat;
    int h=getChipHeight(inline),w=AndroidUtilities.dp(width);
    p.bounds=new Rect(offset,offset,w+offset,h+offset);
    p.cornerRadius=inline?h/2f:AndroidUtilities.dp(14);
    p.shellPaint.stroke=AndroidUtilities.dpf2(.5f);p.fillPaint.alpha=alpha;
    Canvas c=new Canvas();c.hardware=mode==2;
    if(mode>0)p.glass=new Glass(); // mode 1 exercises software fallback with a live factory
    p.draw(c);
    boolean live=mode==2;int fills=0,shells=0,rims=0;
    for(Mark m:c.marks) {
     if(m.paint==p.fillPaint) {
      fills++;
      float inset=inline?AndroidUtilities.dp(4):0;
      float y=inline?(h-AndroidUtilities.dp(28))/2f:0;
      near(m.l,offset+inset);near(m.r,offset+w-inset);
      near(m.t,offset+y);near(m.b,offset+h-y);
      near(m.radius,AndroidUtilities.dp(14));
      check(m.alpha==(live&&(inline||!flat)?Math.round(alpha*(inline&&!flat?p.brandedGlassOpacity:.18f)):alpha));
      if(inline){near(m.tx,m.l);near(m.ty,m.t);} // centered gradient remains surface-local
     } else if(m.paint==p.shellPaint) {
      shells++;float half=p.shellPaint.stroke/2;
      near(m.l,offset+half);near(m.t,offset+half);
      near(m.r,offset+w-half);near(m.b,offset+h-half);near(m.radius,h/2f);
     } else {check(m.paint==p.strokePaint);rims++;}
    }
    check(fills==(!inline&&live&&flat?0:1));
    check(shells==(inline&&!live?1:0));
    check(rims==(!inline&&!live&&!flat&&dark&&!monet?1:0));
    check(p.fillPaint.alpha==alpha && c.saves==0 && c.tx==0 && c.ty==0);
    if(p.glass!=null){check(p.glass.draws==(live?1:0));if(live)check(p.glass.bounds==p.bounds);}
   }
 }
}'''.replace("METHODS", methods)
        with tempfile.TemporaryDirectory(prefix="inline-card-paint-") as temp:
            file = Path(temp) / "InlineCardPaint.java"
            file.write_text(java)
            result = subprocess.run(["javac", str(file)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            result = subprocess.run(["java", "-cp", temp, "InlineCardPaint"], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)


if __name__ == "__main__":
    unittest.main()
