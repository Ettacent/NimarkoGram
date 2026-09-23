const fs = require('node:fs');
const path = require('node:path');
const cp = require('node:child_process');
const os = require('node:os');
const assert = require('node:assert/strict');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/java/org/telegram/ui/DialogsActivity.java'), 'utf8');
const tabsSource = fs.readFileSync(path.resolve(__dirname, '../../main/java/org/telegram/ui/Components/FilterTabsView.java'), 'utf8');
const overlayEdge = tabsSource.match(/public int getTrailingOverlayEdge\(\) \{[^}]*\}/)[0];
const start = source.indexOf('public void draw(Canvas canvas)', source.indexOf('private final Paint cardEdgePaint'));
const end = source.indexOf('@Override', start);
const draw = source.slice(start, end).trim();
assert(draw.includes('canvas.drawPath(cardClipPath, cardClipPaint)'));
assert(!draw.includes('canvas.clipPath(cardClipPath)'));
assert(source.includes('cardClipPaint = new Paint(Paint.ANTI_ALIAS_FLAG)'));
assert(draw.indexOf('getBackground().draw(canvas)') < draw.indexOf('canvas.saveLayer'));
assert(!draw.includes('requestLayout') && !draw.includes('setPadding') && !draw.includes('new '));
assert(tabsSource.includes('child == listView && drawSelectorWithChildren()'));
assert(source.includes('protected boolean drawSelectorWithChildren() {\n                    return homeInfoCardSlotWidth <= 0;'));
const boundsStart = tabsSource.indexOf('final float add = additionalTabWidth / 2f;', tabsSource.indexOf('protected void drawSelector(Canvas canvas, float visibleLeft'));
const bounds = tabsSource.slice(boundsStart, tabsSource.indexOf('canvas.save();', boundsStart));
const java = `
public class EdgeTest {
 static float density;static int checks;
 static int dp(float x){return (int)Math.ceil(x*density);}
 static void check(boolean b){checks++;if(!b)throw new AssertionError("check "+checks);}
 static class LocaleController {static boolean isRTL;}
 static class Paint {}
 static class NimarkoConfig {static boolean tabStyleStroke,glareOnElements;}
 static class Rect {int left,top,right,bottom;}
 static class Path {
  enum FillType {INVERSE_WINDING}enum Direction {CW}
  float left,right,top,bottom,radius;FillType fill;
  void rewind(){}void setFillType(FillType f){fill=f;}
  void addRoundRect(float l,float t,float r,float b,float rx,float ry,Direction d){left=l;top=t;right=r;bottom=b;radius=rx;}
 }
 static class BlurredBackgroundDrawable {float radius;void setRadius(float r){radius=r;}}
 static class Background extends BlurredBackgroundDrawable {
  int draws,left,top,right,bottom;
  void setBounds(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;}
  void draw(Canvas c){draws++;check(!c.layer);c.background=this;}
 }
 static class Parent {
  Background bg=new Background();int normalDraws,selectors;
  Background getBackground(){return bg;}public void draw(Canvas c){normalDraws++;}
  void dispatchDraw(Canvas c){check(c.layer);}
  void drawSelector(Canvas c,float left,float right){
   check(!c.layer&&c.mask!=null);check(left>c.mask.left&&right<c.mask.right);selectors++;
   float gap=(c.background.bottom-2*dp(6.666f)-dp(28))/2f;
   check(Math.abs(left-c.background.left-dp(6.666f)-gap)<.001f);
   check(Math.abs(c.background.right-dp(6.666f)-right-gap)<.001f);
   check(Math.abs(c.background.radius-dp(28)/2f-gap)<.001f);
  }
 }
 static class Card {
  float x;int width;float getX(){return x;}int getWidth(){return width;}
  float getY(){return 0;}int getHeight(){return dp(40);}float getScaleX(){return 1;}float getAlpha(){return 1;}
 }
 static class Canvas {
  boolean layer;int layers;float translate,scale=1;Path mask;Background background;
  void getClipBounds(Rect r){}
  int saveLayer(int l,int t,int r,int b,Object p){layer=true;layers++;return 1;}
  void drawPath(Path path,Paint p){check(layer);mask=path;}
  void save(){}void translate(float x,float y){translate=x;}void scale(float x,float y){scale=x;}
  void drawRect(int l,int t,int r,int b,Paint p){}void restore(){}void restoreToCount(int n){layer=false;}
  float remaining(float x){return 1-Math.max(0,Math.min(1,(x-translate)*scale/dp(8)));}
 }
 static class Tabs extends Parent {
  int width,height,homeInfoCardSlotWidth;Card homeInfoCards=new Card();
  int trailingOverlayInset; boolean trailingOverlayRtl;
  ${overlayEdge}
  float cardBackgroundRadius=-1;
  Paint cardEdgePaint=new Paint(),cardClipPaint=new Paint();Path cardClipPath=new Path();
  int getWidth(){return width;}int getHeight(){return height;}float getX(){return dp(4);}
  boolean foldersAtBottom(){return true;}float getFilterTabsVisibilityFactor(boolean s){return 1;}
  ${draw}
 }
 static class ListView {
  float scale=1,translation,pivot;
  float getScaleX(){return scale;}float getTranslationX(){return translation;}
  float getPivotX(){return pivot;}float getX(){return 0;}
  int getPaddingLeft(){return 0;}int getPaddingRight(){return 0;}int getWidth(){return dp(360);}float getAlpha(){return 1;}
 }
 static class Selector {
  static final float TAB_INTERNAL_PADDING=12.5f;
  int additionalTabWidth,left,right;boolean visible;ListView listView=new ListView();
  float top,bottom;
  float selectorCornerRadius=14;Canvas canvas=new Canvas();
  void measure(float indicatorX,float indicatorWidth,float visibleLeft,float visibleRight){
   visible=false;
   int height=dp(50);
   ${bounds}
   this.left=left;this.right=right;top=y+offsetY;bottom=top+dp(28);visible=true;
  }
  float screen(float x){return (x-listView.pivot)*listView.scale+listView.pivot+listView.translation;}
 }
 public static void main(String[] args){
  for(float d:new float[]{1,1.5f,2,2.625f,3,4})for(int screen:new int[]{240,360,600})
   for(int card:new int[]{48,72,90}){
    density=d;float leftRemaining=-1;
    for(boolean rtl:new boolean[]{false,true}){
     LocaleController.isRTL=rtl;Tabs t=new Tabs();t.width=dp(screen)-2*dp(4);t.height=dp(50);
     t.homeInfoCardSlotWidth=t.homeInfoCards.width=dp(card);
     t.trailingOverlayInset=dp(card)+dp(8);t.trailingOverlayRtl=rtl;
     t.homeInfoCards.x=rtl?dp(10):dp(screen)-dp(10)-dp(card);
     Canvas c=new Canvas();t.draw(c);Path p=c.mask;
     check(c.layers==1&&!c.layer&&t.bg.draws==1&&p.fill==Path.FillType.INVERSE_WINDING);
     check(t.selectors==1);
     float edge=rtl?p.left:p.right;
     check(c.remaining(edge)==0);
     check(c.remaining(edge+(rtl?1:-1)*dp(8))==1);
     check(p.top==dp(9)&&p.bottom==dp(50)-dp(9)&&p.radius==dp(16));
     float previous=0;
     for(int i=0;i<=dp(8);i++){
      float a=c.remaining(edge+(rtl?i:-i));check(a>=previous);previous=a;
     }
     float mid=c.remaining(edge+(rtl?1:-1)*dp(4));
     if(!rtl)leftRemaining=mid;else check(Math.abs(leftRemaining-mid)<.0001f);
     t.homeInfoCardSlotWidth=0;c=new Canvas();t.draw(c);check(t.normalDraws==1&&c.layers==0);
    }
   }
  for(float d:new float[]{1,1.5f,2,3,4})for(float scale:new float[]{0,.8f,1,1.2f})
   for(int x:new int[]{-300,-20,0,50,190,230,300,500})for(int width:new int[]{16,50,150,300})
    for(int extra:new int[]{0,12,40}){
     density=d;Selector s=new Selector();s.listView.scale=scale;s.listView.pivot=dp(180);
     s.listView.translation=dp(3);s.additionalTabWidth=dp(extra);
     s.measure(dp(x),dp(width),dp(11),dp(245));
     if(s.visible){
      check(s.screen(s.left)>=dp(11)-1.21f&&s.screen(s.right)<=dp(245)+1.21f);
     check(s.right>s.left);
     check(Math.abs(s.top+s.bottom-dp(50))<.001f);
     }
     if(scale==0)check(!s.visible);
     s.measure(dp(x),dp(width),-Float.MAX_VALUE,Float.MAX_VALUE);
     if(scale>0){
      check(s.visible&&s.left==(int)(dp(x)-dp(12.5f)-dp(extra)/2f));
      check(s.right==(int)(dp(x)+dp(width)+dp(12.5f)+dp(extra)/2f));
     }
    }
  System.out.println("PASS: "+checks+" actual draw geometry checks: transparent edge, mirrored fade, round mask, normal fallback and one layer");
 }
}`;
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-folder-edge-'));
try {
    function run(code) {
        fs.writeFileSync(path.join(tmp, 'EdgeTest.java'), code);
        cp.execFileSync('javac', ['EdgeTest.java'], {cwd: tmp, stdio: 'pipe'});
        return cp.execFileSync('java', ['EdgeTest'], {cwd: tmp, encoding: 'utf8', stdio: 'pipe'});
    }
    process.stdout.write(run(java));
    assert.throws(() => run(java.replace('contentRight - dp(8)', 'homeInfoCards.getX() - getX() - dp(10)')));
    assert.throws(() => run(java.replace('canvas.restoreToCount(layer);', '')
        .replace('drawSelector(canvas, left + selectorInset, right - selectorInset);',
            'drawSelector(canvas, left + selectorInset, right - selectorInset);canvas.restoreToCount(layer);')));
    assert.throws(() => run(java.replace('drawSelector(canvas, left + selectorInset, right - selectorInset);',
        'drawSelector(canvas, contentLeft + dp(2), contentRight - dp(2));')));
    assert.throws(() => run(java.replace('(getHeight() - backgroundPadding * 2) / 2f', 'dp(18)')));
    assert.throws(() => run(java.replace('(height - dp(28)) / 2f - y', '0f')));
    assert.throws(() => run(java.replace('(visibleRight - translation - pivot) / scale + pivot', 'Float.MAX_VALUE')));
    console.log('PASS: previous fade leaves opacity at clipped edge and fails; Android GPU antialiasing still needs device verification');
} finally {fs.rmSync(tmp, {recursive: true, force: true});}
