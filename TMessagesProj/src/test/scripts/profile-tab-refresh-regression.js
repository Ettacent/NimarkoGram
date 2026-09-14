const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const cp = require('node:child_process');
const assert = require('node:assert/strict');
const base = path.resolve(__dirname, '../../main/java/org/telegram/ui/Components');
const media = fs.readFileSync(path.join(base, 'SharedMediaLayout.java'), 'utf8');
const strip = fs.readFileSync(path.join(base, 'ScrollSlidingTextTabStrip.java'), 'utf8');
function method(source, signature) {
    const start = source.indexOf(signature);
    assert(start >= 0, signature);
    let end = source.indexOf('{', start), depth = 1;
    while (depth && ++end < source.length) {
        if (source[end] === '{') depth++;
        if (source[end] === '}') depth--;
    }
    assert.equal(depth, 0);
    return source.slice(start, end + 1);
}
const update = method(media, 'private void updateTabs(boolean animated, boolean completingProfileTransition)');
assert(update.indexOf('if (deferTabUpdate(animated)) return;') < update.indexOf('mediaPages[0].selectedType = id;'));
assert(update.indexOf('if (deferTabUpdate(animated)) return;') < update.indexOf('finishAddingTabs()'));
assert.match(method(media, 'private void checkCurrentTabValid()'), /if \(deferTabUpdate\(false\)\) return;/);
assert.equal((media.match(/applyPendingTabUpdate\(\);/g) || []).length, 3, 'tap, album and gesture completions flush deferred updates');
const java = `
import java.util.*;
class View {static final int VISIBLE=0,GONE=8;int visibility=VISIBLE,selectedType;int getVisibility(){return visibility;}}
class Params {int width;}
class TextView {int color;Object tag;Params params=new Params();void setTag(Object v){tag=v;}void setTextColor(int c){color=c;}void requestLayout(){}Params getLayoutParams(){return params;}}
class Container {TextView[] tabs={new TextView(),new TextView()};int getChildCount(){return tabs.length;}TextView getChildAt(int i){return tabs[i];}}
class IntMap {int size(){return 0;}int get(int i){return 0;}void clear(){}void put(int i,int v){}int keyAt(int i){return 0;}int valueAt(int i){return 0;}}
class Theme {static int getColor(int key,Object provider){return key;}}
class LayoutHelper {static final int WRAP_CONTENT=-2;}
public class ProfileTabRefreshTest {
 static int checks;
 static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
 static class Strip {
  Container tabsContainer=new Container();int currentPosition,activeTextColorKey=255,unactiveTextColorKey=0;
  boolean useMinimalWidth;Object resourcesProvider;IntMap prevPositionToWidth=new IntMap(),positionToWidth=new IntMap();
  int processColor(int c){return c;}
  ${method(strip, 'public void finishAddingTabs()')}
 }
 static class Layout {
  View[] mediaPages={new View(),new View()};Strip strip=new Strip();
  boolean pendingTabUpdate,pendingTabUpdateAnimated,destroyed;int selected=9,updates,validations;boolean lastAnimated;
  Layout(){mediaPages[0].selectedType=9;mediaPages[1].selectedType=14;mediaPages[1].visibility=View.GONE;}
  ${method(media, 'private boolean isMediaPageTransitionRunning()')}
  ${method(media, 'private boolean deferTabUpdate(boolean animated)')}
  ${method(media, 'private void applyPendingTabUpdate()')}
  void updateTabs(boolean animated){
   if(deferTabUpdate(animated))return;
   updates++;lastAnimated=animated;mediaPages[0].selectedType=selected;
   strip.finishAddingTabs();
  }
  void checkCurrentTabValid(){if(deferTabUpdate(false))return;validations++;}
  void start(int destination,boolean tap){
   mediaPages[1].selectedType=destination;mediaPages[1].visibility=View.VISIBLE;
   if(tap){selected=destination;strip.currentPosition=1;}
   strip.tabsContainer.tabs[0].color=127;strip.tabsContainer.tabs[1].color=128;
  }
  void finish(boolean cancel){
   if(!cancel){View old=mediaPages[0];mediaPages[0]=mediaPages[1];mediaPages[1]=old;}
   selected=mediaPages[0].selectedType;mediaPages[1].visibility=View.GONE;
   strip.currentPosition=cancel?0:1;
   applyPendingTabUpdate();
  }
 }
 public static void main(String[] args){
  for(boolean tap:new boolean[]{true,false})for(boolean cancel:new boolean[]{true,false}){
   Layout l=new Layout();l.start(14,tap);
   for(int i=0;i<20;i++){
    l.updateTabs(i==10);
    check(l.mediaPages[0].selectedType==9,"outgoing archive must not become gifts mid-transition");
    check(l.strip.tabsContainer.tabs[0].color==127 && l.strip.tabsContainer.tabs[1].color==128,"refresh must preserve intermediate blue selection colors");
    l.applyPendingTabUpdate();check(l.updates==0,"flush must wait for transition");
   }
   l.finish(cancel);
   check(l.updates==1 && l.lastAnimated,"coalesce requests and preserve animated update");
   check(l.mediaPages[0].selectedType==(cancel?9:14),"correct final page on completion or cancellation");
   check(l.strip.tabsContainer.tabs[cancel?0:1].color==255,"selection gets final color only after transition");
   l.applyPendingTabUpdate();check(l.updates==1,"no repeated flush");
   l.start(8,tap);l.updateTabs(false);l.finish(false);
   check(l.mediaPages[0].selectedType==8 && l.updates==2 && !l.lastAnimated,"next transition independent");
  }
  Layout invalid=new Layout();invalid.start(14,true);invalid.checkCurrentTabValid();
  check(invalid.pendingTabUpdate && invalid.validations==0,"validation waits too");
  invalid.finish(false);check(invalid.validations==1,"deferred validation delivered");
  Layout dead=new Layout();dead.start(14,true);dead.updateTabs(true);dead.destroyed=true;dead.finish(false);
  check(dead.updates==0,"destroyed layout not refreshed");
  System.out.println("PASS: "+checks+" profile transition and extracted tab-color checks");
 }
}`;
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-profile-tabs-'));
function run(code) {
    fs.writeFileSync(path.join(dir, 'ProfileTabRefreshTest.java'), code);
    const compile = cp.spawnSync('javac', [path.join(dir, 'ProfileTabRefreshTest.java')], {encoding:'utf8'});
    assert.equal(compile.status, 0, compile.stderr);
    return cp.spawnSync('java', ['-cp', dir, 'ProfileTabRefreshTest'], {encoding:'utf8'});
}
try {
    const result = run(java);
    assert.equal(result.status, 0, result.stdout + result.stderr);
    process.stdout.write(result.stdout);
    const broken = java.replace('if(deferTabUpdate(animated))return;', '');
    assert.notEqual(broken, java);
    const failure = run(broken);
    assert.notEqual(failure.status, 0);
    assert.match(failure.stderr, /outgoing archive must not become gifts/);
    const colorOnly = run(java.replace(
        'if(deferTabUpdate(animated))return;', 'if(deferTabUpdate(animated)){strip.finishAddingTabs();return;}'));
    assert.notEqual(colorOnly.status, 0);
    assert.match(colorOnly.stderr, /intermediate blue selection colors/);
    console.log('PASS: negative controls reproduce outgoing-page mutation and premature blue-color reset');
} finally {fs.rmSync(dir, {recursive:true,force:true});}
