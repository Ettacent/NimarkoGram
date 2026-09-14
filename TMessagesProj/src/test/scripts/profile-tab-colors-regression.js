const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),cp=require('node:child_process'),assert=require('node:assert/strict');
const source=fs.readFileSync(path.resolve(__dirname,'../../main/java/org/telegram/ui/Components/ScrollSlidingTextTabStrip.java'),'utf8');
const start=source.indexOf('public void updateColors()');assert(start>=0);
let end=source.indexOf('{',start),depth=1;while(depth&&++end<source.length){if(source[end]==='{')depth++;if(source[end]==='}')depth--;}
const method=source.slice(start,end+1);
const java=`
import java.util.*;
class Drawable {int color;void setColor(int c){color=c;}}
class InsetDrawable extends Drawable {InsetDrawable(Drawable d,int a,int b,int c,int e){}}
class TextView {Drawable background;int color;Drawable getBackground(){return background;}void setBackground(Drawable b){background=b;}void setTextColor(int c){color=c;}}
class Container {ArrayList<TextView> tabs=new ArrayList<>();int getChildCount(){return tabs.size();}TextView getChildAt(int n){return tabs.get(n);}}
class Theme {static int active=-8213761,inactive=-5723209;static final int RIPPLE_MASK_ROUNDRECT_6DP=1;static int getColor(int key,Object r){return key==1?active:inactive;}static int multAlpha(int color,float alpha){return color;}static Drawable createSelectorDrawable(int c,int mask,int radius){return new Drawable();}}
public class ProfileTabColorsTest {
 Container tabsContainer=new Container();int currentPosition,activeTextColorKey=1,unactiveTextColorKey=2;Object resourcesProvider;
 boolean appliedColors;int appliedActiveColor,appliedInactiveColor;Drawable selectorDrawable=new Drawable();int invalidations;
 int processColor(int c){return c;}int dp(int n){return n;}void invalidate(){invalidations++;}
 ${method}
 static int checks;static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 public static void main(String[] args){
  ProfileTabColorsTest t=new ProfileTabColorsTest();t.updateColors();
  for(int i=0;i<3;i++)t.tabsContainer.tabs.add(new TextView());t.updateColors();
  Drawable[] backgrounds=t.tabsContainer.tabs.stream().map(v->v.background).toArray(Drawable[]::new);
  for(int repeat=0;repeat<100;repeat++)for(int selected=0;selected<3;selected++){
   t.currentPosition=selected;
   for(int i=0;i<3;i++)t.tabsContainer.tabs.get(i).color=-7623956+i;
   int invalidations=t.invalidations;t.updateColors();
   for(int i=0;i<3;i++){
    check(t.tabsContainer.tabs.get(i).background==backgrounds[i],"same-palette refresh must not replace pressed ripple");
    check(t.tabsContainer.tabs.get(i).color==-7623956+i,"same-palette refresh must preserve intermediate colors");
   }
   check(t.invalidations==invalidations,"same-palette refresh does not invalidate selector");
  }
  t.tabsContainer.tabs.add(new TextView());t.updateColors();
  check(t.tabsContainer.tabs.get(3).background!=null,"new tabs still receive a background");
  check(t.tabsContainer.tabs.get(3).color==Theme.inactive,"new tab receives inactive color");
  check(t.tabsContainer.tabs.get(0).background==backgrounds[0],"new tab does not reset existing ripples");
  Theme.active=-16711936;Theme.inactive=-1;t.updateColors();
  check(t.selectorDrawable.color==Theme.active,"real palette change reaches selector");
  for(int i=0;i<4;i++)check(t.tabsContainer.tabs.get(i).color==(i==t.currentPosition?Theme.active:Theme.inactive),"real palette change reaches text");
  System.out.println("PASS: "+checks+" extracted tab palette/ripple identity checks");
 }
}`;
const dir=fs.mkdtempSync(path.join(os.tmpdir(),'nimarko-profile-colors-'));
function run(code){fs.writeFileSync(path.join(dir,'ProfileTabColorsTest.java'),code);const c=cp.spawnSync('javac',[path.join(dir,'ProfileTabColorsTest.java')],{encoding:'utf8'});assert.equal(c.status,0,c.stderr);return cp.spawnSync('java',['-cp',dir,'ProfileTabColorsTest'],{encoding:'utf8'});}
try{const r=run(java);assert.equal(r.status,0,r.stdout+r.stderr);process.stdout.write(r.stdout);const broken=java.replace('if (!changed && tab.getBackground() != null) continue;','');assert.notEqual(java,broken);const old=run(broken);assert.notEqual(old.status,0);assert.match(old.stderr,/same-palette refresh must not replace/);console.log('PASS: unconditional background replacement reproduces recorded reset');}finally{fs.rmSync(dir,{recursive:true,force:true});}
