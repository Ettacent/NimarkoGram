const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const cp = require('node:child_process');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../main/java');
const profile = fs.readFileSync(path.join(root, 'org/telegram/ui/ProfileActivity.java'), 'utf8');
const pager = fs.readFileSync(path.join(root, 'org/telegram/ui/ViewPagerActivity.java'), 'utf8');
function method(source, marker) {
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
const sync = method(profile, 'private void synchronizeVisibleHeader()');
assert(method(profile, 'public void onBecomeFullyVisible()').includes('synchronizeVisibleHeader();'));
assert(method(profile, 'protected void onAllAnimationsDone()').includes('if (!profileLifecycleDestroyed && !isPaused)'));
assert(method(profile, 'protected void onAllAnimationsDone()').includes('fixLayout();'));
assert(!sync.includes('scrollToPosition') && !sync.includes('extraHeight ='), 'preserve scroll and avatar state');
const settings = fs.readFileSync(path.join(root, 'app/nimarkogram/messenger/infocards/preferences/InfoCardsPreferencesActivity.java'), 'utf8');
const row = fs.readFileSync(path.join(root, 'app/nimarkogram/messenger/infocards/preferences/InfoCardRowView.java'), 'utf8');
assert(!settings.includes('fetchWeak') && !settings.includes('computeAsyncWeak') && !settings.includes('valueFor'));
assert(!row.includes('valueText') && row.includes('addView(title, textParams)'));
const java = `public class VisibleHeaderTest {
 static class View {int layouts;void requestLayout(){layouts++;}}
 static class Profile {
  boolean profileLifecycleDestroyed,transitionAnimationInProress,openAnimationInProgress,fragmentOpened,isFragmentOpened,invalidateScroll;
  View fragmentView=new View(),listView=new View(); int fixes,starts,ends;boolean oldCode;
  void fixLayout(){fixes++;}
  ${sync}
  void onResume(){} void onPause(){} void onBecomeFullyHidden(){}
  void onBecomeFullyVisible(){if(!oldCode)synchronizeVisibleHeader();}
  void onTransitionAnimationStart(boolean open,boolean back){starts++;isFragmentOpened=open;transitionAnimationInProress=true;}
  void onTransitionAnimationProgress(boolean open,float progress){}
  void onTransitionAnimationEnd(boolean open,boolean back){ends++;if(open)fragmentOpened=true;transitionAnimationInProress=false;}
 }
 static class State {
  Profile fragment=new Profile();boolean isFullyVisible,isResumed,isInAnimation;float lastVisibility;
  ${method(pager, 'public void setVisibility(float visibilityByViewPage, float visibilityByParent, boolean parentIsFullyVisible, boolean parentIsResumed)')}
 }
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 public static void main(String[]args){
  State old=new State();old.fragment.oldCode=true;old.setVisibility(1,1,true,true);
  check(!old.fragment.fragmentOpened && !old.fragment.isFragmentOpened && old.fragment.starts==0,"negative control: instant pager entry skips transition callbacks");
  State instant=new State();instant.setVisibility(1,1,true,true);
  check(instant.fragment.fragmentOpened && instant.fragment.isFragmentOpened && instant.fragment.fixes==1,"instant open initializes geometry");
  check(instant.fragment.fragmentView.layouts==1,"first entry requests measured layout");
  instant.setVisibility(0,1,true,true);instant.setVisibility(1,1,true,true);
  check(instant.fragment.fragmentView.layouts==1 && instant.fragment.fixes==2,"return refreshes without resetting scroll");
  State animated=new State();animated.setVisibility(.2f,1,false,true);animated.setVisibility(.7f,1,false,true);animated.setVisibility(1,1,true,true);
  check(animated.fragment.starts==1 && animated.fragment.ends==1 && animated.fragment.fixes==1,"animated lifecycle preserved");
  State hidden=new State();hidden.setVisibility(1,0,false,false);hidden.setVisibility(1,1,true,true);
  check(hidden.fragment.fragmentOpened && hidden.fragment.fixes==1,"parent restored after notification chat");
  for(int mask=0;mask<16;mask++){
   Profile p=new Profile();p.profileLifecycleDestroyed=(mask&1)!=0;p.transitionAnimationInProress=(mask&2)!=0;p.openAnimationInProgress=(mask&4)!=0;if((mask&8)!=0)p.listView=null;
   p.synchronizeVisibleHeader();check(p.fixes==(mask==0?1:0),"lifecycle guard "+mask);
  }
  System.out.println("PASS: actual pager instant/animated/hidden-parent lifecycles, missing-callback negative control, header guards and scroll preservation");
 }
}`;
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-visible-header-'));
try {
    fs.writeFileSync(path.join(tmp, 'VisibleHeaderTest.java'), java);
    cp.execFileSync('javac', ['VisibleHeaderTest.java'], {cwd:tmp, stdio:'pipe'});
    process.stdout.write(cp.execFileSync('java', ['VisibleHeaderTest'], {cwd:tmp, encoding:'utf8'}));
    console.log('PASS: list-animation recheck and compact settings wiring; device notification launch still needs verification');
} finally { fs.rmSync(tmp, {recursive:true, force:true}); }
