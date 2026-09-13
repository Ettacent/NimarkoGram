const fs=require('node:fs'),path=require('node:path'),cp=require('node:child_process'),os=require('node:os'),assert=require('node:assert/strict');
const source=fs.readFileSync(path.resolve(__dirname,'../../main/java/org/telegram/ui/Components/FilterTabsView.java'),'utf8');
const dialogs=fs.readFileSync(path.resolve(__dirname,'../../main/java/org/telegram/ui/DialogsActivity.java'),'utf8');
function method(sig){
 const start=source.indexOf(sig);assert(start>=0,sig);let depth=0;
 for(let i=source.indexOf('{',start);i<source.length;i++){
  if(source[i]==='{')depth++;if(source[i]==='}'&&--depth===0)return source.slice(start,i+1);
 }throw Error(sig);
}
const java=`import java.util.*;
public class SyncedScrollTest {
 static int checks;static void check(boolean b){checks++;if(!b)throw new AssertionError("check "+checks);}
 static class LocaleController {static boolean isRTL;}
 static int dp(float n){return Math.round(n*3);}static float lerp(float a,float b,float p){return a+(b-a)*p;}
 static final int TAB_PADDING_WIDTH=24;
 static class View {int left;int getLeft(){return left;}}
 static class Tabs {
  ArrayList<Integer> tabs=new ArrayList<>();HashMap<Integer,Integer> positionToWidth=new HashMap<>(),positionToX=new HashMap<>();
  int additionalTabWidth,listViewPaddingH=35,currentPosition,scrollingToChild;
  int pageScrollFrom=-1,pageScrollTo=-1,pageScrollStart,pageScrollEnd;float pageScrollProgressStart;
  int scroll,first=0,stops,calls;boolean computing,missing;
  class ListView {
   int getPaddingLeft(){return listViewPaddingH+(LocaleController.isRTL?336:0);}
   int getPaddingRight(){return listViewPaddingH+(LocaleController.isRTL?0:336);}
   int getWidth(){return 1056;}boolean isComputingLayout(){return computing;}
   void stopScroll(){stops++;}void scrollBy(int dx,int dy){scroll+=dx;calls++;}
  }
  class LayoutManager {
   int findFirstVisibleItemPosition(){return first;}
   View findViewByPosition(int pos){if(missing)return null;View v=new View();v.left=getTabContentLeft(pos)-scroll;return v;}
  }
  ListView listView=new ListView();LayoutManager layoutManager=new LayoutManager();
  Tabs(){int x=listViewPaddingH;for(int w:new int[]{220,255,210,200,281,281,211}){
   int i=tabs.size();tabs.add(i);positionToWidth.put(i,w);positionToX.put(i,x);x+=w+dp(TAB_PADDING_WIDTH);
  }}
  ${method('private int getTabCellWidth(')}
  ${method('private int getTabContentWidth(')}
  ${method('private int getTabContentLeft(')}
  ${method('private void scrollWithPage(')}
  float center(int from,int to,float p){return lerp(getTabContentLeft(from)+getTabCellWidth(from)/2f,getTabContentLeft(to)+getTabCellWidth(to)/2f,p)-scroll;}
 }
 public static void main(String[] args){
  for(boolean rtl:new boolean[]{false,true}){
   LocaleController.isRTL=rtl;
   for(int repeat=0;repeat<20;repeat++){
    Tabs t=new Tabs();t.currentPosition=5;t.first=4;
    t.scroll=rtl?t.getTabContentLeft(5)-t.listView.getPaddingLeft():t.getTabContentLeft(5)+t.getTabCellWidth(5)-t.listView.getWidth()+t.listView.getPaddingRight();
    t.scroll=Math.max(0,t.scroll);
    float previous=t.center(5,6,0);int start=t.scroll;
    for(int frame=0;frame<=120;frame++){
     float p=frame/120f;t.scrollWithPage(6,p);
     float center=t.center(5,6,p);
     check(rtl?center<=previous+1f:center>=previous-1f);previous=center;
    }
    check(t.stops<=2);check(t.pageScrollFrom==-1);
    int end=t.scroll;t.currentPosition=6;t.scrollWithPage(6,1);check(t.scroll==end);
    t.currentPosition=6;previous=t.center(6,5,0);
    for(int frame=0;frame<=120;frame++){
     float p=frame/120f;t.scrollWithPage(5,p);float center=t.center(6,5,p);
     check(rtl?center>=previous-1f:center<=previous+1f);previous=center;
    }
    t.currentPosition=5;t.scrollWithPage(6,0);int before=t.scroll;
    t.scrollWithPage(6,.25f);t.scrollWithPage(6,.5f);t.scrollWithPage(6,.25f);t.scrollWithPage(6,0);check(t.scroll==before);
   }
  }
  LocaleController.isRTL=false;Tabs t=new Tabs();t.currentPosition=5;t.first=4;
  t.scroll=t.getTabContentLeft(5)-206;
  float previous=t.center(5,6,.111f);
  for(float p:new float[]{.111f,.145f,.148f,.233f,.321f,.392f,.456f,.516f,.576f,.641f,.658f,.684f,.713f,.808f,.95f,1}){
   t.scrollWithPage(6,p);float center=t.center(5,6,p);check(center>=previous-1f);previous=center;
  }
  int before=t.calls;t.computing=true;t.scrollWithPage(6,.4f);check(t.calls==before);
  t.computing=false;t.missing=true;t.scrollWithPage(6,.5f);check(t.calls==before);
  System.out.println("PASS: "+checks+" checks: recorded final swipe, no reverse indicator motion, RTL, cancellation, repeats, completed transition and layout guards");
 }
}`;
const follow=method('private void scrollWithPage(');
assert(!follow.includes('smoothScroll'));
assert(!source.includes('progress < 0.5f ? currentPosition : position'));
const draw=dialogs.slice(dialogs.indexOf('private final Paint cardEdgePaint'),dialogs.indexOf('protected void onDefaultTabMoved()',dialogs.indexOf('private final Paint cardEdgePaint')));
assert(draw.indexOf('getBackground().draw(canvas)')<draw.indexOf('canvas.saveLayer('));
assert(draw.includes('cardClipPath.addRoundRect'));
assert(draw.includes('super.dispatchDraw(canvas)'));
assert(draw.includes('getBackground().setBounds(0, 0, getWidth(), getHeight())'));
const tmp=fs.mkdtempSync(path.join(os.tmpdir(),'nimarko-synced-scroll-'));
try{
 fs.writeFileSync(path.join(tmp,'SyncedScrollTest.java'),java);
 cp.execFileSync('javac',['SyncedScrollTest.java'],{cwd:tmp,stdio:'pipe'});
 process.stdout.write(cp.execFileSync('java',['SyncedScrollTest'],{cwd:tmp,encoding:'utf8',stdio:'pipe'}));
 const delayed=java.replace('lerp((float) pageScrollStart, pageScrollEnd, fraction)',
  'lerp((float) pageScrollStart, pageScrollEnd, fraction < .5f ? 0f : 1f)');
 assert.notEqual(delayed,java);
 fs.writeFileSync(path.join(tmp,'SyncedScrollTest.java'),delayed);
 cp.execFileSync('javac',['SyncedScrollTest.java'],{cwd:tmp,stdio:'pipe'});
 assert.throws(()=>cp.execFileSync('java',['SyncedScrollTest'],{cwd:tmp,stdio:'pipe'}));
 const old=[409,384,384,365,353,354,388];
 assert(old.some((x,i)=>i>0&&x<old[i-1]),'recorded old path reverses');
 console.log('PASS: delayed independent scroll fails; background is outside fade layer; rounded clip and normal bounds restored');
}finally{fs.rmSync(tmp,{recursive:true,force:true});}
