const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),cp=require('node:child_process'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'../../main/java/org/telegram/ui');
const source=fs.readFileSync(path.join(root,'Components/SharedMediaLayout.java'),'utf8');
function method(signature){const start=source.indexOf(signature);assert(start>=0);let end=source.indexOf('{',start),depth=1;while(depth&&++end<source.length){if(source[end]==='{')depth++;if(source[end]==='}')depth--;}return source.slice(start,end+1).replaceAll('org.telegram.ui.Components.chat.ViewPositionWatcher','ViewPositionWatcher');}
const java=`
class Point {float x,y;}
class View {static final int VISIBLE=0;float translation,ancestorY;int height=50,top,invalidations;int getVisibility(){return 0;}float getAlpha(){return 1;}float getY(){return top+translation;}float getTranslationY(){return translation;}int getTop(){return top;}int getHeight(){return height;}void setTranslationY(float v){translation=v;}void invalidate(){invalidations++;}}
class ViewGroup extends View {}
class Panel extends View {float getLayoutVisibility(){return 1;}float getAnimatedHeightWithPadding(){return height;}}
class BlurredBackgroundDrawable {float x,y;int updates;float getSourceOffsetX(){return x;}float getSourceOffsetY(){return y;}void setSourceOffset(float a,float b){x=a;y=b;updates++;}}
class Factory {View root=new ViewGroup();View getSourceRootView(){return root;}}
class ViewPositionWatcher {static boolean computeCoordinatesInParent(View v,ViewGroup root,Point p){p.x=0;p.y=v.getY()+v.ancestorY;return true;}}
public class TabGlassTest {
 static final int VISIBLE=0;Factory notificationGlassFactory=new Factory();Point notificationGlassPosition=new Point();
 View scrollSlidingTextTabStrip=new View(),storiesContainer=new View(),fragmentContextView=new View();Panel topPanelLayout=new Panel();
 BlurredBackgroundDrawable notificationTabsBackground=new BlurredBackgroundDrawable(),notificationPlayerBackground=new BlurredBackgroundDrawable();
 float notificationControlsOffset;int topPadding;int dp(int n){return n;}void invalidate(){}void invalidateBlur(){}
 void checkUi_topPanelLayoutY(){topPanelLayout.setTranslationY(topPadding+notificationControlsOffset);}
 ${method('public float getNotificationTabsVisibleTop()')}
 ${method('public float getNotificationControlsBottom()')}
 ${method('private void syncNotificationGlass(')}
 ${method('public void setNotificationControlsOffset(')}
 static int checks;static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 public static void main(String[] args){
  TabGlassTest t=new TabGlassTest();t.topPanelLayout.top=34;t.topPanelLayout.height=60;t.notificationPlayerBackground.y=34;
  for(int cycle=0;cycle<4;cycle++)for(int i=0;i<201;i++){
   float next=cycle%2==0?i:200-i;
   t.setNotificationControlsOffset(next);
   check(t.notificationTabsBackground.y==t.scrollSlidingTextTabStrip.getY()+t.scrollSlidingTextTabStrip.ancestorY,"glass must follow tabs in the same frame");
   check(t.notificationPlayerBackground.y==t.topPanelLayout.getY()+t.topPanelLayout.ancestorY,"player glass follows its controls");
   if(next>0){check(t.getNotificationControlsBottom()>=t.scrollSlidingTextTabStrip.getY()+50,"capture covers tab bottom");check(t.getNotificationControlsBottom()>=t.topPanelLayout.getY()+60,"capture covers player bottom");}
   int updates=t.notificationTabsBackground.updates;t.setNotificationControlsOffset(next);check(t.notificationTabsBackground.updates==updates,"stable position must not recreate glass");
  }
  t.setNotificationControlsOffset(30);t.scrollSlidingTextTabStrip.ancestorY=105;t.setNotificationControlsOffset(30);
  check(t.notificationTabsBackground.y==135,"ancestor layout change must not leave stale glass when offset stays equal");
  t.setNotificationControlsOffset(0);check(t.notificationTabsBackground.y==105,"reset also synchronizes glass");
  for(int height=1;height<300;height++){
   float visibleBottom=84+height-4;float mediaTop=0;
   float offset=Math.max(0,84+height+(8-4)-mediaTop-t.getNotificationTabsVisibleTop());
   check(mediaTop+offset+t.getNotificationTabsVisibleTop()-visibleBottom==8,"visible edge gap is 8dp including internal padding");
  }
  System.out.println("PASS: "+checks+" extracted tab-glass synchronization, capture extent and visible-gap checks");
 }
}`;
const dir=fs.mkdtempSync(path.join(os.tmpdir(),'nimarko-tab-glass-'));
function run(code){fs.writeFileSync(path.join(dir,'TabGlassTest.java'),code);cp.execFileSync('javac',['TabGlassTest.java'],{cwd:dir,stdio:'pipe'});return cp.spawnSync('java',['TabGlassTest'],{cwd:dir,encoding:'utf8'});}
try{const r=run(java);assert.equal(r.status,0,r.stderr);process.stdout.write(r.stdout);const bad=run(java.replaceAll('syncNotificationGlass(scrollSlidingTextTabStrip, notificationTabsBackground);',''));assert.notEqual(bad.status,0);assert.match(bad.stderr,/glass must follow tabs/);console.log('PASS: removing same-frame synchronization reproduces stale tab-glass coordinates');}finally{fs.rmSync(dir,{recursive:true,force:true});}
const profile=fs.readFileSync(path.join(root,'ProfileActivity.java'),'utf8');
assert.match(profile,/notificationControlsCapturePosition.y\s*\+ sharedMediaLayout.getNotificationControlsBottom\(\) \+ additionalList/);
