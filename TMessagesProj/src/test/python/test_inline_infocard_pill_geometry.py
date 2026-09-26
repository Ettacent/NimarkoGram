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
            "private static float getInlineSurfaceInset(",
        ))
        methods += "\n" + re.search(r"private static final int CONTENT_PADDING_DP\s*=\s*\d+;", source)[0]
        java = '''public class InlineCardGeometry {
 static final int CHIP_HEIGHT_DP=28,CORNER_RADIUS_DP=14;
 static class AndroidUtilities {
  static float density; static int dp(float n){return (int)Math.ceil(n*density);}
 }
 static class FrameLayout {static class LayoutParams {int height;}}
 static class Content {
  FrameLayout.LayoutParams params=new FrameLayout.LayoutParams();int outlines,layouts;
  int paddingLeft=AndroidUtilities.dp(8),paddingRight=AndroidUtilities.dp(8),paddingTop,paddingBottom,paddingCalls;
  Object getLayoutParams(){return params;}
  void setLayoutParams(FrameLayout.LayoutParams p){params=p;layouts++;}
  void invalidateOutline(){outlines++;}
  void setPadding(int l,int t,int r,int b){paddingLeft=l;paddingTop=t;paddingRight=r;paddingBottom=b;paddingCalls++;}
 }
 static class Radius {boolean inlineFolderStyle;float radius;void setCornerRadius(float r){radius=r;}void setRadii(float r){radius=r;}}
 static class Text {int size;void setTextSize(int s){size=s;}}
 static class Icon {float x=1,y=1;void setScaleX(float v){x=v;}void setScaleY(float v){y=v;}}
 boolean inlineFolderStyle;int layoutRequests,colorUpdates,maxWidthUpdates,paddingAtMaxWidthUpdate;
 Icon iconView=new Icon();void applyColorMode(){colorUpdates++;}
 Content content=new Content();Radius background=new Radius(),loadingDrawable;
 Text textView=new Text();void requestLayout(){layoutRequests++;}
 void applyMaxChipWidth(){maxWidthUpdates++;paddingAtMaxWidthUpdate=content.paddingLeft;}
 METHODS
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  for(float d:new float[]{1,1.25f,1.5f,2,2.625f,2.75f,3,3.5f,4}) {
   AndroidUtilities.density=d;
   for(boolean loading:new boolean[]{false,true}) {
    InlineCardGeometry c=new InlineCardGeometry();
    check(getChipHeight(false)==AndroidUtilities.dp(28));
    check(c.getChipCornerRadius()==AndroidUtilities.dp(14));
    check(CONTENT_PADDING_DP==8);
    check(getInlineSurfaceInset(AndroidUtilities.dp(28))==0);
    check(getInlineSurfaceInset(AndroidUtilities.dp(20))==0);
    if(loading)c.loadingDrawable=new Radius();
    c.setInlineFolderStyle(true);
    int row=AndroidUtilities.dp(50),pad=AndroidUtilities.dp(6.666f);
    int height=c.content.params.height;
    check(height==row-2*pad && height>getChipHeight(false));
    float inset=(height-AndroidUtilities.dp(28))/2f;
    int contentPadding=AndroidUtilities.dp(8)+(int)Math.ceil(inset);
    check(getInlineSurfaceInset(height)==inset);
    check(c.content.paddingLeft==contentPadding && c.content.paddingRight==contentPadding);
    check(c.content.paddingTop==0 && c.content.paddingBottom==0);
    // Integer padding preserves 8dp inside the painted capsule; half-pixel
    // insets can add at most 0.5px (8..8.5px at density 1), on either side.
    for(int padding:new int[]{c.content.paddingLeft,c.content.paddingRight}) {
     float clearance=padding-inset;
     check(clearance>=AndroidUtilities.dp(8) && clearance<=AndroidUtilities.dp(8)+.5f);
    }
    check(c.content.paddingCalls==1 && c.maxWidthUpdates==1);
    check(c.paddingAtMaxWidthUpdate==contentPadding); // width budget sees the new padding
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
    check(c.content.paddingCalls==1 && c.maxWidthUpdates==1);
    c.setInlineFolderStyle(false);
    check(c.content.params.height==AndroidUtilities.dp(28));
    check(c.background.radius==AndroidUtilities.dp(14));
    check(c.content.paddingLeft==AndroidUtilities.dp(8) && c.content.paddingRight==AndroidUtilities.dp(8));
    check(c.content.paddingTop==0 && c.content.paddingBottom==0);
    check(c.content.paddingCalls==2 && c.maxWidthUpdates==2);
    check(c.paddingAtMaxWidthUpdate==AndroidUtilities.dp(8));
    check(c.textView.size==AndroidUtilities.dp(13));
    check(c.iconView.x==1 && c.iconView.y==1);
    check(!c.background.inlineFolderStyle && c.colorUpdates==2);
    if(loading)check(c.loadingDrawable.radius==AndroidUtilities.dp(14));
    c.setInlineFolderStyle(true);check(c.content.params.height==height);
    check(c.content.paddingLeft==contentPadding && c.content.paddingRight==contentPadding);
    check(c.content.paddingCalls==3 && c.maxWidthUpdates==3);
    check(c.paddingAtMaxWidthUpdate==contentPadding);
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
        for forbidden in ("setMaxChipWidth", "setColorProvider", "setAlpha", "releaseGlassBackground", "cancelAnimation"):
            self.assertNotIn(forbidden, style)
        self.assertIn("content.setPadding(padding, 0, padding, 0)", style)
        self.assertLess(style.index("content.setPadding("), style.index("applyMaxChipWidth()"))
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
    def test_production_surface_lifecycle_and_material_gates(self):
        source = CARD.read_text()
        methods = "\n".join(method(source, signature) for signature in (
            "public void setGlassBackgroundFactory(",
            "private void releaseGlassBackground()",
            "private void updateGlassBackground()",
            "protected void onDetachedFromWindow()",
            "private static int getChipHeight(boolean inline)",
            "private float getChipCornerRadius()",
            "private static float getBrandedGlassOpacity(",
        ))
        java = '''import java.util.HashSet;
import java.util.function.BiFunction;
class CardHost {
 boolean attached;
 boolean isAttachedToWindow(){return attached;}
 protected void onDetachedFromWindow(){attached=false;}
}
public class InlineCardLifecycle extends CardHost {
 static final int CHIP_HEIGHT_DP=28,CORNER_RADIUS_DP=14;
 static class Build {
  static class VERSION {static int SDK_INT;}
  static class VERSION_CODES {static final int TIRAMISU=33;}
 }
 static class View {static final int LAYER_TYPE_NONE=0;}
 static class AndroidUtilities {
  static float density=1;
  static int dp(float n){return (int)Math.ceil(n*density);}
  static float dpf2(float n){return n*density;}
  static void cancelRunOnUIThread(Runnable r){}
 }
 static class SharedConfig {static boolean blur;static boolean chatBlurEnabled(){return blur;}}
 static class LiteMode {
  static final int FLAG_LIQUID_GLASS=1;static boolean enabled;
  static boolean isEnabled(int flag){return enabled;}
  static void removeOnGlassSettingsChangedListener(Runnable r){}
 }
 static class Theme {static int multAlpha(int color,float alpha){return Math.round(255*alpha);}}
 static class ColorUtils {static int blendARGB(int a,int b,float f){return a;}}
 static class Provider {
  Object resources;boolean neutral;
  Provider(Object r,boolean n){resources=r;neutral=n;}
 }
 static class BlurredBackgroundProviderImpl {
  static Provider topPanel(Object r){return new Provider(r,true);}
 }
 static class BlurredBackgroundProviderBuilder {
  Object resources;
  BlurredBackgroundProviderBuilder(Object r){resources=r;}
  BlurredBackgroundProviderBuilder setBackgroundColor(BiFunction<Object,Boolean,Integer> f){return this;}
  BlurredBackgroundProviderBuilder setStrokeColorTop(int a,int b){return this;}
  BlurredBackgroundProviderBuilder setStrokeColorBottom(int a,int b){return this;}
  BlurredBackgroundProviderBuilder setShadowColor(int a,int b){return this;}
  BlurredBackgroundProviderBuilder setShadowLayer(float a,float b,float c){return this;}
  BlurredBackgroundProviderBuilder setStrokeWidth(float a,float b){return this;}
  Provider build(){return new Provider(resources,false);}
 }
 static class Content {int invalidations;void invalidate(){invalidations++;}}
 static class Glass {
  Object callback;float radius;int alpha=255;Provider provider;
  void setCallback(Object c){callback=c;}void setRadius(float r){radius=r;}
  void setAlpha(int a){alpha=a;}void setColorProvider(Provider p){provider=p;}
 }
 static class Background {Glass glass;int drawableAlpha=255;float brandedGlassOpacity;}
 static class BlurredBackgroundDrawableViewFactory {
  boolean capable;int creates,releases;Content owner;HashSet<Glass> active=new HashSet<>();
  BlurredBackgroundDrawableViewFactory(boolean c){capable=c;}
  boolean supportsLiquidGlass(){return capable;}
  Glass create(Content c){
   check(owner==null||owner==c,"factory content owner");owner=c;creates++;
   Glass g=new Glass();active.add(g);return g;
  }
  void release(Content c,Glass g){
   check(owner==c&&active.remove(g),"release exactly once through owning factory");
   releases++;g.setAlpha(0);g.setCallback(null);
  }
 }
 boolean inlineFolderStyle,flat;int brandTop,brandBottom,layerCalls;
 final Object resourcesProvider=new Object();
 final Content content=new Content();final Background background=new Background();
 BlurredBackgroundDrawableViewFactory glassBackgroundFactory;
 final Runnable glassSettingsChanged=this::updateGlassBackground,autoRefresh=()->{};
 boolean isFlat(){return flat;}
 void finishResizeAnimation(){}
 void setLayerType(int type,Object paint){check(type==View.LAYER_TYPE_NONE,"live backdrop must not cache");layerCalls++;}
 METHODS
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 void checkSurface(boolean expected,int alpha) {
  Glass g=background.glass;
  check((g!=null)==expected,"surface existence");
  if(g!=null){
   check(g.callback==background,"nested drawable callback");
   float radius=inlineFolderStyle?(AndroidUtilities.dp(50)-2*AndroidUtilities.dp(6.666f))/2f:AndroidUtilities.dp(14);
   check(g.radius==radius,"full outer radius");check(g.alpha==alpha,"initial drawable alpha");
   check(g.provider.resources==resourcesProvider,"theme resources");
   check(g.provider.neutral==(inlineFolderStyle||flat),"folder provider vs search brand provider");
  }
 }
 public static void main(String[] args){
  // Cold attach in each mode, including factories with no liquid capability.
  for(int sdk:new int[]{29,31,32,33,35})for(boolean capable:new boolean[]{false,true})
   for(boolean blur:new boolean[]{false,true})for(boolean flag:new boolean[]{false,true})
    for(boolean inline:new boolean[]{false,true})for(boolean flat:new boolean[]{false,true})
     for(boolean dark:new boolean[]{false,true})for(int alpha:new int[]{0,96,255}) {
   Build.VERSION.SDK_INT=sdk;SharedConfig.blur=blur;LiteMode.enabled=flag;
   AndroidUtilities.density=dark?2.625f:1;
   InlineCardLifecycle c=new InlineCardLifecycle();c.inlineFolderStyle=inline;c.flat=flat;
   c.background.drawableAlpha=alpha;c.background.brandedGlassOpacity=getBrandedGlassOpacity(dark);
   BlurredBackgroundDrawableViewFactory f=new BlurredBackgroundDrawableViewFactory(capable);
   c.setGlassBackgroundFactory(f);check(f.creates==0,"detached factory assignment");
   c.attached=true;c.updateGlassBackground();
   boolean expected=inline||(sdk>=33&&capable&&blur&&flag);
   c.checkSurface(expected,alpha);Glass first=c.background.glass;
   c.updateGlassBackground();c.setGlassBackgroundFactory(f);
   check(c.background.glass==first&&f.creates==(expected?1:0),"idempotent refresh");
   check(c.layerCalls==(expected?1:0),"uncache only on creation");
   c.onDetachedFromWindow();
   check(c.background.glass==null&&f.active.isEmpty(),"detach releases surface");
   check(f.releases==(expected?1:0),"detach release count");
   if(first!=null)check(first.callback==null&&first.alpha==0,"released drawable cleared");
   c.updateGlassBackground();check(f.creates==(expected?1:0),"detached refresh stays released");
   c.attached=true;c.updateGlassBackground();c.checkSurface(expected,alpha);
   check(f.creates==(expected?2:0),"reattach creates once");
   if(expected)check(c.background.glass!=first,"released drawable not resurrected");
   c.onDetachedFromWindow();
  }
  Build.VERSION.SDK_INT=33;AndroidUtilities.density=1;
  for(boolean initial:new boolean[]{false,true})for(boolean flat:new boolean[]{false,true})
   for(boolean dark:new boolean[]{false,true})for(int alpha:new int[]{0,96,255}) {
   SharedConfig.blur=true;LiteMode.enabled=initial;
   InlineCardLifecycle c=new InlineCardLifecycle();c.attached=true;c.inlineFolderStyle=true;c.flat=flat;
   c.background.drawableAlpha=alpha;c.background.brandedGlassOpacity=getBrandedGlassOpacity(dark);
   c.updateGlassBackground();c.checkSurface(false,alpha); // no factory
   BlurredBackgroundDrawableViewFactory f=new BlurredBackgroundDrawableViewFactory(true);
   c.setGlassBackgroundFactory(f);Glass first=c.background.glass;
   for(boolean blur:new boolean[]{true,false,true})for(boolean flag:new boolean[]{false,true,false,true}) {
    SharedConfig.blur=blur;LiteMode.enabled=flag;c.updateGlassBackground();c.checkSurface(true,alpha);
    check(c.background.glass==first&&f.creates==1&&f.releases==0,"inline persists off/on and blur toggles");
   }
   // Returning to search restores the liquid-only lifecycle and search radius.
   LiteMode.enabled=false;c.inlineFolderStyle=false;c.updateGlassBackground();
   c.checkSurface(false,alpha);check(f.releases==1,"search off releases inline surface");
   for(boolean flag:new boolean[]{true,false,true,false}) {
    LiteMode.enabled=flag;c.updateGlassBackground();c.checkSurface(flag,alpha);
   }
   check(f.creates==3&&f.releases==3,"search toggles create/release exactly once");
   c.inlineFolderStyle=true;c.updateGlassBackground();c.checkSurface(true,alpha);
   // A fallback factory is still a valid inline surface; replacement releases the old owner.
   BlurredBackgroundDrawableViewFactory fallback=new BlurredBackgroundDrawableViewFactory(false);
   c.setGlassBackgroundFactory(fallback);c.checkSurface(true,alpha);
   check(f.active.isEmpty()&&f.releases==4&&fallback.creates==1,"factory replacement");
   c.setGlassBackgroundFactory(null);c.checkSurface(false,alpha);
   check(fallback.active.isEmpty()&&fallback.releases==1,"null factory releases");
   c.setGlassBackgroundFactory(fallback);c.checkSurface(true,alpha);
   c.onDetachedFromWindow();check(fallback.releases==2,"replacement detach release");
  }
 }
}'''.replace("METHODS", methods)
        with tempfile.TemporaryDirectory(prefix="inline-card-lifecycle-") as temp:
            file = Path(temp) / "InlineCardLifecycle.java"
            file.write_text(java)
            result = subprocess.run(["javac", str(file)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            result = subprocess.run(["java", "-cp", temp, "InlineCardLifecycle"],
                                    capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)

    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "JDK required")
    def test_production_paint_bounds_glass_fallback_and_search(self):
        source = CARD.read_text()
        background = source.split("private static final class CardBackground", 1)[1]
        methods = "\n".join((
            method(source, "private static int getChipHeight(boolean inline)"),
            method(source, "private static float getInlineSurfaceInset("),
            method(source, "private static void setSurfaceBounds("),
            method(source, "private static float getBrandedGlassOpacity("),
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
 static class BlurredBackgroundDrawableViewFactory {
  static boolean glassEnabled;
  static boolean isLiquidGlassEnabled(){return glassEnabled;}
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
  void setBounds(Rect r){bounds=r;}
  void draw(Canvas c){check(c.marks.isEmpty());draws++;}
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
    for(boolean surface:new boolean[]{false,true})for(boolean hardware:new boolean[]{false,true})
     for(boolean dark:new boolean[]{false,true})
     for(boolean monet:new boolean[]{false,true})for(int width:new int[]{48,80,96,144})
      for(int offset:new int[]{0,17})for(int alpha:new int[]{96,255}) {
    AndroidUtilities.density=d;Theme.dark=dark;Theme.monet=monet;
    InlineCardPaint p=new InlineCardPaint();p.inlineFolderStyle=inline;p.themeMode=flat;
    int h=getChipHeight(inline),w=AndroidUtilities.dp(width);
    p.bounds=new Rect(offset,offset,w+offset,h+offset);
    p.cornerRadius=inline?h/2f:AndroidUtilities.dp(14);
    p.brandedGlassOpacity=getBrandedGlassOpacity(dark);
    p.shellPaint.stroke=AndroidUtilities.dpf2(.5f);
    if(surface)p.glass=new Glass();
    p.setAlpha(alpha);
    // Keep the same surface while settings change, including software snapshots.
    for(boolean glassFlag:new boolean[]{false,true,false,true}) {
    BlurredBackgroundDrawableViewFactory.glassEnabled=glassFlag;
    Canvas c=new Canvas();c.hardware=hardware;
    int previousDraws=surface?p.glass.draws:0;
    p.draw(c);
    boolean drawnSurface=surface&&(inline||hardware);
    boolean attenuate=surface&&hardware&&(!inline||glassFlag);
    int fills=0,shells=0,rims=0;
    for(Mark m:c.marks) {
     if(m.paint==p.fillPaint) {
      fills++;
      float inset=inline?Math.max(0,(h-AndroidUtilities.dp(28))/2f):0;
      near(m.l,offset+inset);near(m.r,offset+w-inset);
      near(m.t,offset+inset);near(m.b,offset+h-inset);
      near(m.l-offset,m.t-offset); // exactly the same rim on both axes
      near(m.radius,AndroidUtilities.dp(14));
      check(m.alpha==(attenuate&&(inline||!flat)?Math.round(alpha*(inline&&!flat?p.brandedGlassOpacity:.18f)):alpha));
      if(inline){near(m.tx,m.l);near(m.ty,m.t);} // centered gradient remains surface-local
     } else if(m.paint==p.shellPaint) {
      shells++;float half=p.shellPaint.stroke/2;
      near(m.l,offset+half);near(m.t,offset+half);
      near(m.r,offset+w-half);near(m.b,offset+h-half);near(m.radius,h/2f);
     } else {check(m.paint==p.strokePaint);rims++;}
    }
    check(fills==(!inline&&drawnSurface&&flat?0:1));
    check(shells==(inline&&!surface?1:0));
    check(rims==(!inline&&!drawnSurface&&!flat&&dark&&!monet?1:0));
    check(p.fillPaint.alpha==alpha && c.saves==0 && c.tx==0 && c.ty==0);
    if(surface){
     check(p.glass.draws==previousDraws+(drawnSurface?1:0));
     check(p.glass.alpha==alpha);
     if(drawnSurface)check(p.glass.bounds==p.bounds);
    }
    }
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
