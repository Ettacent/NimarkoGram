const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const cp = require('node:child_process');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../main/java');
const source = fs.readFileSync(path.join(root, 'org/telegram/ui/ProfileActivity.java'), 'utf8');
function method(signature) {
    const start = source.indexOf('\n    ' + signature);
    assert(start >= 0, signature);
    const tokens = /\/\/[^\n]*|\/\*[\s\S]*?\*\/|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[{}]/g;
    tokens.lastIndex = source.indexOf('{', start);
    let depth = 0;
    for (let token; (token = tokens.exec(source));) {
        if (token[0] === '{') depth++;
        if (token[0] === '}' && --depth === 0) return source.slice(start, tokens.lastIndex);
    }
    throw Error(signature);
}
assert.match(source, /if \(openSimilar \|\| openGifts\) \{\s*updateRowsIds\(\);\s*scrollToSharedMedia\(\);/);
assert(!source.includes('openSimilar || openGifts || openCommonChats'), 'Common groups must not pre-position the profile');
assert.match(method('public void onTransitionAnimationEnd('), /if \(!backward && openCommonChats\) \{\s*scrollToCommonChatsAfterOpen\(\);/);
assert.match(source, /if \(openCommonChats\) \{\s*initialTab = SharedMediaLayout.TAB_COMMON_GROUPS;/);
const java = `
public class ProfileCommonScrollTest {
 static class RecyclerListView {
  Runnable beforeDraw; int padding;
  void invalidate(){} int getPaddingTop(){return padding;}
  void draw(){Runnable r=beforeDraw;beforeDraw=null;if(r!=null)r.run();}
 }
 static class OneShotPreDrawListener {
  static void add(RecyclerListView v,Runnable r){v.beforeDraw=r;}
 }
 static class LinearSmoothScrollerCustom {
  static final int POSITION_TOP=2;
  int target,offset; final float multiplier;
  LinearSmoothScrollerCustom(Object context,int position,float multiplier){this.multiplier=multiplier;}
  void setTargetPosition(int position){target=position;} void setOffset(int offset){this.offset=offset;}
 }
 static class LayoutManager {
  int smooth,jumps; LinearSmoothScrollerCustom scroller;
  void startSmoothScroll(LinearSmoothScrollerCustom s){smooth++;scroller=s;}
  void scrollToPositionWithOffset(int position,int offset){jumps++;}
 }
 RecyclerListView listView=new RecyclerListView(); LayoutManager layoutManager=new LayoutManager();
 boolean profileLifecycleDestroyed,isPaused,isFragmentOpened=true; int sharedMediaRow=10;
 Object getContext(){return this;}
 ${method('private void scrollToCommonChatsAfterOpen(')}
 ${method('public void scrollToSharedMedia(boolean animated)')}
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 public static void main(String[] args){
  for(int padding:new int[]{0,120,360,1080}){
   ProfileCommonScrollTest t=new ProfileCommonScrollTest();t.scrollToCommonChatsAfterOpen();
   check(t.layoutManager.smooth==0&&t.layoutManager.jumps==0,"Scroll before layout settled");
   t.listView.padding=padding;t.sharedMediaRow=14;t.listView.draw();t.listView.draw();
   check(t.layoutManager.smooth==1&&t.layoutManager.jumps==0,"Must scroll smoothly once");
   check(t.layoutManager.scroller.target==14,"Must resolve current row after layout");
   check(t.layoutManager.scroller.offset==-padding,"Must use current header padding");
  }
  for(int scenario=0;scenario<6;scenario++){
   ProfileCommonScrollTest t=new ProfileCommonScrollTest();RecyclerListView old=t.listView;
   LayoutManager manager=t.layoutManager;t.scrollToCommonChatsAfterOpen();
   if(scenario==0)t.profileLifecycleDestroyed=true;
   if(scenario==1)t.isPaused=true;
   if(scenario==2)t.isFragmentOpened=false;
   if(scenario==3)t.listView=new RecyclerListView();
   if(scenario==4)t.layoutManager=null;
   if(scenario==5)t.sharedMediaRow=-1;
   old.draw();check(manager.smooth==0&&manager.jumps==0,"Stale or absent target scroll: "+scenario);
  }
  ProfileCommonScrollTest t=new ProfileCommonScrollTest();t.scrollToSharedMedia(false);
  check(t.layoutManager.jumps==1,"Existing direct-position callers changed");
  System.out.println("PASS: common groups scroll after layout, current target/padding, close/recreate guards, direct-position callers unchanged");
 }
}`;
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-common-scroll-'));
fs.writeFileSync(path.join(dir, 'ProfileCommonScrollTest.java'), java);
cp.execFileSync('javac', [path.join(dir, 'ProfileCommonScrollTest.java')], {stdio:'inherit'});
cp.execFileSync('java', ['-cp', dir, 'ProfileCommonScrollTest'], {stdio:'inherit'});
