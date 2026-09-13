const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const cp = require('node:child_process');
const assert = require('node:assert/strict');
const base = path.resolve(__dirname, '../../main/java');
const read = name => fs.readFileSync(path.join(base, name), 'utf8');
const tabs = read('org/telegram/ui/MainTabsActivity.java');
const pager = read('org/telegram/ui/ViewPagerActivity.java');
const profile = read('org/telegram/ui/ProfileActivity.java');
const scroll = tabs.slice(tabs.indexOf('protected void onViewPagerScrollEnd()'), tabs.indexOf('protected void onViewPagerTabAnimationUpdate'));
assert.doesNotMatch(scroll, /dropFragmentAtPosition\(profilePosition\)/);
assert.match(tabs, /public void onLowMemory\(\)[\s\S]*?getPositionVisibility\(position\) == 0[\s\S]*?dropFragmentAtPosition\(position\)/);
assert.match(pager, /final SparseArray<FragmentState> fragmentsArr = new SparseArray<>\(\)/);
assert.match(pager, /if \(fragment.getFragmentView\(\) == null\) \{\s*fragment.performCreateView/);
assert.match(pager, /state.fragment.onFragmentDestroy\(\);[\s\S]*?fragmentsArr.clear\(\)/);
assert.match(profile, /public void onPause\(\)[\s\S]*?onProfilePaused\(topView\)/);
assert.match(profile, /public void onResume\(\)[\s\S]*?onProfileResumed\(topView, getDialogId\(\)\)/);
for (const name of ['LaunchActivity.java', 'MainTabsActivity.java', 'ViewPagerActivity.java', 'Components/AccountSwitchTransition.java']) {
    assert(!read('org/telegram/ui/' + name).includes('AccountSwitchDebug'), name);
}
const start = pager.indexOf('    protected static class FragmentState {');
const end = pager.indexOf('    public class ViewPagerActivityPagerLayout', start);
assert(start > 0 && end > start);
const state = pager.slice(start, end).replaceAll('@NonNull ', '');
const java = `
class BaseFragment {
 Object fragmentView=new Object();int resumes,pauses,visible,hidden,starts,ends;boolean playing;
 void onResume(){resumes++;playing=true;}void onPause(){pauses++;playing=false;}
 void onBecomeFullyVisible(){visible++;}void onBecomeFullyHidden(){hidden++;}
 void onTransitionAnimationStart(boolean o,boolean b){starts++;}
 void onTransitionAnimationProgress(boolean o,float p){}
 void onTransitionAnimationEnd(boolean o,boolean b){ends++;}
}
public class ProfileLifecycleTest {
 ${state}
 static int checks;
 static void check(boolean b,String message){checks++;if(!b)throw new AssertionError(message);}
 public static void main(String[] args){
  BaseFragment p=new BaseFragment();FragmentState s=new FragmentState(p);Object view=p.fragmentView;
  for(int visit=1;visit<=30;visit++){
   s.setVisibility(0,1,true,true);check(!p.playing,"hidden profile must stay paused");
   s.setVisibility(.1f,1,true,true);s.setVisibility(.6f,1,true,true);s.setVisibility(1,1,true,true);
   check(p.playing&&p.resumes==visit,"one resume per visit");
   check(p.fragmentView==view,"preserve profile view");
   s.setVisibility(.6f,1,true,true);s.setVisibility(.1f,1,true,true);s.setVisibility(0,1,true,true);
   check(!p.playing&&p.pauses==visit&&p.hidden==visit,"leave stops banner and media exactly once");
   check(p.starts==p.ends,"balanced transition callbacks");
  }
  s.setVisibility(.2f,1,true,true);s.setVisibility(0,1,true,true);
  check(!p.playing,"cancelled swipe stops playback");
  s.setVisibility(1,1,true,true);s.setVisibility(1,0,false,false);
  check(!p.playing,"background pauses retained profile");
  s.setVisibility(1,1,true,true);check(p.playing,"foreground resumes profile");
  BaseFragment other=new BaseFragment();FragmentState otherState=new FragmentState(other);
  otherState.setVisibility(0,1,true,true);check(other.resumes==0,"new account has independent lifecycle");
  System.out.println("PASS: "+checks+" retained profile lifecycle checks");
 }
}`;
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-profile-lifecycle-'));
try {
    fs.writeFileSync(path.join(dir, 'ProfileLifecycleTest.java'), java);
    cp.execFileSync('javac', ['ProfileLifecycleTest.java'], {cwd:dir});
    process.stdout.write(cp.execFileSync('java', ['ProfileLifecycleTest'], {cwd:dir, encoding:'utf8'}));
    fs.writeFileSync(path.join(dir, 'ProfileLifecycleTest.java'), java.replace('fragment.onPause();', ''));
    cp.execFileSync('javac', ['ProfileLifecycleTest.java'], {cwd:dir});
    assert.notEqual(cp.spawnSync('java', ['ProfileLifecycleTest'], {cwd:dir}).status, 0);
} finally {
    fs.rmSync(dir, {recursive:true, force:true});
}
