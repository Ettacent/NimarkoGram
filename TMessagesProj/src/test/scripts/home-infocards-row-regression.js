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
const position = method('private void positionHomeInfoCards()');
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
assert(source.includes('homeInfoCardsAnimator.removeAllUpdateListeners();'));
const draw = source.slice(source.indexOf('private final Paint cardEdgePaint'), source.indexOf('public boolean onInterceptTouchEvent(MotionEvent ev)', source.indexOf('private final Paint cardEdgePaint')));
assert(!draw.slice(draw.indexOf('public void draw(Canvas canvas)')).includes('new '), 'no per-frame shader allocation');
const java = `
public class HomeCardsRowTest {
 static float density;
 static int dp(float v) { return (int)Math.ceil(density*v); }
 static class MeasureSpec {static final int EXACTLY=1,AT_MOST=2;static int makeMeasureSpec(int size,int mode){return size;}}
 static class AndroidUtilities { static int statusBarHeight; static int dp(float v){return HomeCardsRowTest.dp(v);} }
 static class View {
  static final int VISIBLE=0;
  int visibility=VISIBLE, width=dp(84), height, top, children=1, measuredWidth;
  void measure(int w,int h){measuredWidth=Math.min(width,w);height=h;} int getMeasuredWidth(){return measuredWidth;}
  void setInlineFolderStyle(boolean inline){}
  float y, alpha=1, factor=1, translationY;
  int getVisibility(){return visibility;} int getMeasuredHeight(){return height;}
  int getHeight(){return height;} int getTop(){return top;} int getChildCount(){return children;}
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
 class SearchAnimator {float value;float getFloatValue(){return value;}}
 SearchAnimator animatorSearchVisible=new SearchAnimator();
 int homeInfoCardSlotWidth;
 boolean shouldHideHomeSearchField(){return hide;} boolean foldersAtBottom(){return bottom;}
 ${gate}
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
    h.homeInfoCards.height=dp(40); h.homeInfoCards.top=dp(3); h.filterTabsView.height=dp(50);
    h.topPanelLayout=new Panel();h.topPanelLayout.meta.visibility=1;h.topPanelLayout.y=dp(250);h.topPanelLayout.height=dp(40);
    h.dialogStoriesCell=new View();h.dialogStoriesCell.y=dp(60);h.dialogStoriesCell.height=dp(100);
    for(boolean bottom:new boolean[]{false,true})for(float scroll:new float[]{-100,-30,0,40})for(float alpha:new float[]{0,.2f,.5f,1}){
     h.bottom=bottom;h.filterTabsView.y=dp(bottom?700:160)+scroll*d;h.filterTabsView.alpha=alpha;
     h.homeInfoCards.factor=.8f;h.positionHomeInfoCards();
     near(h.homeInfoCards.top+h.homeInfoCards.translationY+h.homeInfoCards.height/2f,
          h.filterTabsView.y+h.filterTabsView.height/2f);
     near(h.homeInfoCards.alpha,.8f*alpha);cases++;
    }
    h.hide=false;h.measureSlot(w);check(h.homeInfoCardSlotWidth==0,"visible search retains original layout");
    h.hide=true;h.canShowFilterTabsView=false;h.measureSlot(w);check(h.homeInfoCardSlotWidth==0,"no folders fallback");
    h.actionBar.height=dp(56);h.positionHomeInfoCards();
    near(h.homeInfoCards.top+h.homeInfoCards.translationY,dp(250)+dp(40)+dp(21)+dp(2));
    float anchored=h.homeInfoCards.top+h.homeInfoCards.translationY;
    for(int phase=0;phase<=40;phase++){
     float search=phase<=20?phase/20f:(40-phase)/20f;
     h.animatorSearchVisible.value=search;h.positionHomeInfoCards();
     near(h.homeInfoCards.alpha,h.homeInfoCards.factor*(1-search));
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
    console.log('PASS: fade-mask wiring, no per-frame allocations, hidden-card touch guard and animator cleanup; device rendering not tested');
} finally { fs.rmSync(tmp, {recursive:true, force:true}); }
