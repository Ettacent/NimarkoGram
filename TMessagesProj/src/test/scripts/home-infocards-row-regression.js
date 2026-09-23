const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const cp = require('node:child_process');
const assert = require('node:assert/strict');
const file = path.resolve(__dirname, '../../main/java/org/telegram/ui/DialogsActivity.java');
const source = fs.readFileSync(file, 'utf8');
function method(marker) {
    const start = source.indexOf(marker);
    assert(start >= 0, marker);
    const tokens = /\/\/[^\n]*|\/\*[\s\S]*?\*\/|"(?:\\.|[^"\\])*"|[{}]/g;
    tokens.lastIndex = source.indexOf('{', start);
    let depth = 0;
    for (let token; (token = tokens.exec(source));) {
        if (token[0] === '{') depth++;
        else if (token[0] === '}' && --depth === 0) return source.slice(start, tokens.lastIndex);
    }
    throw new Error(marker);
}
const gate = method('private boolean useInlineHomeInfoCards()').replaceAll(
    'app.nimarkogram.messenger.infocards.InfoCardsConfig', 'InfoCardsConfig');
const position = method('private void positionHomeInfoCards()').replaceAll(
    'app.nimarkogram.messenger.infocards.InfoCardsConfig', 'InfoCardsConfig');
const applyVisibility = method('private void applyHomeInfoCardsVisibility(');
const slot = source.match(/homeInfoCardSlotWidth = useInlineHomeInfoCards\(\) \?[\s\S]*?homeInfoCardSlotWidth = homeInfoCards.getMeasuredWidth\(\);\s*}/)[0];
assert(!source.includes('measureChildWithMargins(child, widthMeasureSpec, homeInfoCardSlotWidth + dp(8), heightMeasureSpec, 0)'));
assert(!source.includes('child == filterTabsView && homeInfoCardSlotWidth > 0'));
assert(source.includes('homeInfoCardSlotWidth + dp(8) : 0, LocaleController.isRTL'));
assert(source.includes('homeInfoCardSlotWidth, MeasureSpec.AT_MOST'));
assert(!source.includes('protected boolean interpolateCarouselWidth()'));
assert(source.includes('homeInfoCards.setInlineFolderStyle(useInlineHomeInfoCards())'));
assert(!source.includes('return !useInlineHomeInfoCards() && super.canAnimateCardResize();'));
assert(source.includes('cardEdgePaint.setXfermode(new android.graphics.PorterDuffXfermode(PorterDuff.Mode.DST_OUT))'));
assert(source.includes('canvas.translate(contentLeft + dp(8), 0);'));
assert(source.includes('canvas.translate(contentRight - dp(8), 0);'));
assert(source.includes('canvas.drawRect(0, 0, getWidth(), getHeight(), cardEdgePaint)'));
assert(source.includes('canvas.scale(-1, 1);'));
assert(source.includes('MotionEvent.ACTION_DOWN && getAlpha() <= 0.01f'));
assert(!source.includes('homeInfoCardsAnimator'), 'home uses the folder/search clock, not a second animator');
const draw = source.slice(source.indexOf('private final Paint cardEdgePaint'), source.indexOf('public boolean onInterceptTouchEvent(MotionEvent ev)', source.indexOf('private final Paint cardEdgePaint')));
assert(!draw.slice(draw.indexOf('public void draw(Canvas canvas)')).includes('new '), 'no per-frame shader allocation');
const java = `
public class HomeCardsRowTest {
 static float density;
 static int dp(float v) { return (int)Math.ceil(density*v); }
 static float lerp(float a,float b,float t){return a+(b-a)*t;}
 static class MeasureSpec {static final int EXACTLY=1,AT_MOST=2;static int makeMeasureSpec(int size,int mode){return size;}}
 static class AndroidUtilities { static int statusBarHeight; static int dp(float v){return HomeCardsRowTest.dp(v);} }
 static class LayoutParams {int height=dp(50);}
 static class View {
  static final int VISIBLE=0, INVISIBLE=4, GONE=8;
  LayoutParams layoutParams=new LayoutParams();
  LayoutParams getLayoutParams(){return layoutParams;}
  int visibility=VISIBLE, width=dp(84), height, top, children=1, measuredWidth;
  void measure(int w,int h){measuredWidth=Math.min(width,w);height=h;} int getMeasuredWidth(){return measuredWidth;}
  void setInlineFolderStyle(boolean inline){}
  float y, alpha=1, factor=1, translationY;
  float sx=1,sy=1;
  float getScaleX(){return sx;} float getScaleY(){return sy;}
  void setScaleX(float v){sx=v;} void setScaleY(float v){sy=v;}
  int getVisibility(){return visibility;} int getMeasuredHeight(){return height;}
  int getHeight(){return height;} int getTop(){return top;} int getChildCount(){return children;}
  int getWidth(){return width;} int getLeft(){return 0;}
  float getPivotX(){return width/2f;} float getPivotY(){return height/2f;} float getTranslationX(){return 0;}
  void setPivotX(float f){}void setPivotY(float f){}void setTranslationX(float f){}void syncToActiveCard(){}
  void setHostVisibility(float f,float x,float y,int v){factor=alpha=f;sx=x;sy=y;visibility=v;}
  float getY(){return y;} float getAlpha(){return alpha;} float getVisibilityFactor(){return factor;}
  void setTranslationY(float v){translationY=v;} void setAlpha(float v){alpha=v;}
 }
 static class ActionBar extends View { boolean getOccupyStatusBar(){return true;} static int getCurrentActionBarHeight(){return dp(56);} }
 static class Metadata { float visibility; float getTotalVisibility(){return visibility;} }
 static class Panel extends View { Metadata meta=new Metadata(); Metadata getMetadata(){return meta;} float getLayoutVisibility(){return meta.visibility;} float getAnimatedHeightWithPadding(int p){return height+p;} }
 static class InfoCardsConfig {static boolean enabled=true; static boolean isEnabled(){return enabled;}}
 View homeInfoCards=new View(), filterTabsView=new View(), dialogStoriesCell;
 ActionBar actionBar=new ActionBar(); Panel topPanelLayout;
 boolean canShowFilterTabsView=true, hide=true, bottom;
 boolean filterTabsBootstrapPending;float progressToActionMode;
 class SearchAnimator {float value;float getFloatValue(){return value;}}
 SearchAnimator animatorSearchVisible=new SearchAnimator();
 SearchAnimator animatorActionModeVisible=new SearchAnimator(),animatorDoneButtonVisible=new SearchAnimator();
 float getRightSlidingProgress(){return 0;}
 int homeInfoCardSlotWidth;
 boolean shouldHideHomeSearchField(){return hide;} boolean foldersAtBottom(){return bottom;}
 void blur3_InvalidateBlur(){}
 ${gate}
 ${applyVisibility}
 ${position}
 void measureSlot(int widthSize){${slot}}
 static void near(float a,float b){if(Math.abs(a-b)>.001)throw new AssertionError(a+" != "+b);}
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 public static void main(String[] args){int cases=0;
  for(float d:new float[]{1,1.5f,2,2.625f,3,4}){
   density=d;
   for(int widthDp:new int[]{200,240,320,360,400,600,960})for(boolean rtl:new boolean[]{false,true})for(int contentWidth:new int[]{48,80,84,144,280}){
    HomeCardsRowTest h=new HomeCardsRowTest(); int w=dp(widthDp);h.homeInfoCards.width=dp(contentWidth);h.measureSlot(w);
    int tabsW=w-dp(8);
    int tabsLeft=dp(4);
    int cardsLeft=rtl?dp(10):w-dp(10)-h.homeInfoCardSlotWidth;
    check(tabsW==w-dp(8) && h.homeInfoCardSlotWidth<=w/4,"folder viewport keeps full width");
    int inset=h.homeInfoCardSlotWidth+dp(8);
    int trailing=dp(11.5f)+Math.max(0,inset-dp(6.666f));
    int lastEdge=rtl?tabsLeft+trailing+dp(12):tabsLeft+tabsW-trailing-dp(12);
    int fadeEdge=rtl?cardsLeft+h.homeInfoCardSlotWidth+dp(10):cardsLeft-dp(10);
    check(rtl?lastEdge>=fadeEdge:lastEdge<=fadeEdge,"last folder fits outside the card and fade");
    check(Math.abs(lastEdge-fadeEdge)<=dp(10),"native label padding without a maximum-slot blank tail");
    check(h.homeInfoCardSlotWidth==Math.min(dp(contentWidth),Math.min(dp(96),w/4)),"reserve actual bounded content width");
    check(h.homeInfoCardSlotWidth==h.homeInfoCards.getMeasuredWidth(),"card fills the reserved slot");
    check(h.homeInfoCards.height==dp(50),"inline host matches folder row rather than old 40dp host");
    h.filterTabsView.layoutParams=null;h.measureSlot(w);
    check(h.homeInfoCards.height==dp(50),"missing layout params use 50dp fallback");
    h.filterTabsView.layoutParams=new LayoutParams();
    for(int invalidHeight:new int[]{-2,-1,0}){
     h.filterTabsView.layoutParams.height=invalidHeight;h.measureSlot(w);
     check(h.homeInfoCards.height==dp(50),"nonpositive layout height uses 50dp fallback");
    }
    h.filterTabsView.layoutParams.height=dp(50)+1;h.measureSlot(w);
    check(h.homeInfoCards.height==dp(50)+1,"positive layout height is used exactly in pixels");
    h.filterTabsView.layoutParams.height=dp(50);h.measureSlot(w);
    h.homeInfoCards.top=dp(3); h.filterTabsView.height=dp(50);
    h.topPanelLayout=new Panel();h.topPanelLayout.meta.visibility=1;h.topPanelLayout.y=dp(250);h.topPanelLayout.height=dp(40);
    h.dialogStoriesCell=new View();h.dialogStoriesCell.y=dp(60);h.dialogStoriesCell.height=dp(100);
    for(boolean bottom:new boolean[]{false,true})for(float scroll:new float[]{-100,-30,0,40})for(float alpha:new float[]{0,.2f,.5f,1}){
     h.bottom=bottom;h.filterTabsView.y=dp(bottom?700:160)+scroll*d;h.filterTabsView.alpha=alpha;
     h.filterTabsView.sx=h.filterTabsView.sy=lerp(.98f,1,alpha);
     h.homeInfoCards.factor=.8f;h.positionHomeInfoCards();
     near(h.homeInfoCards.top+h.homeInfoCards.translationY+h.homeInfoCards.height/2f,
          h.filterTabsView.y+h.filterTabsView.height/2f);
     near(h.homeInfoCards.alpha,alpha);cases++;
     near(h.homeInfoCards.sx,h.filterTabsView.sx);near(h.homeInfoCards.sy,h.filterTabsView.sy);
    }
    h.hide=false;h.measureSlot(w);check(h.homeInfoCardSlotWidth==0,"visible search retains original layout");
    h.hide=true;h.canShowFilterTabsView=false;h.measureSlot(w);check(h.homeInfoCardSlotWidth==0,"no folders fallback");
    h.actionBar.height=dp(56);h.positionHomeInfoCards();
    near(h.homeInfoCards.top+h.homeInfoCards.translationY,dp(250)+dp(40)+dp(21)+dp(2));
    float anchored=h.homeInfoCards.top+h.homeInfoCards.translationY;
    for(int phase=0;phase<=40;phase++){
     float search=phase<=20?phase/20f:(40-phase)/20f;
     h.animatorSearchVisible.value=search;h.positionHomeInfoCards();
     near(h.homeInfoCards.alpha,1-search);
     near(h.homeInfoCards.sx,lerp(.98f,1,h.homeInfoCards.alpha));
     near(h.homeInfoCards.top+h.homeInfoCards.translationY,anchored);
    }
    h.canShowFilterTabsView=true;h.homeInfoCardSlotWidth=0;h.positionHomeInfoCards();
    near(h.homeInfoCards.alpha,0);
    h.canShowFilterTabsView=true;h.homeInfoCards.children=0;h.measureSlot(w);check(h.homeInfoCardSlotWidth==0,"empty cards reclaim width");
    h.homeInfoCards.children=1;InfoCardsConfig.enabled=false;h.measureSlot(w);check(h.homeInfoCardSlotWidth==0,"disabled cards reclaim width");InfoCardsConfig.enabled=true;
   }
  }
  System.out.println("PASS: "+cases+" row positions/alphas, full-width folders, adaptive overlay, end reachability, RTL and search fallbacks");
 }
}`;
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-infocard-row-'));
try {
    fs.writeFileSync(path.join(tmp, 'HomeCardsRowTest.java'), java);
    cp.execFileSync('javac', ['HomeCardsRowTest.java'], {cwd:tmp, stdio:'pipe'});
    process.stdout.write(cp.execFileSync('java', ['HomeCardsRowTest'], {cwd:tmp, encoding:'utf8'}));
    console.log('PASS: fade-mask wiring, no per-frame allocations, hidden-card touch guard and shared animation ownership; device rendering not tested');
} finally { fs.rmSync(tmp, {recursive:true, force:true}); }
