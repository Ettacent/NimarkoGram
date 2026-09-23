const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const cp = require('node:child_process');
const assert = require('node:assert/strict');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/java/org/telegram/ui/DialogsActivity.java'), 'utf8');
function method(signature) {
    const start = source.indexOf(signature);
    assert(start >= 0, signature);
    let depth = 0;
    const tokens = /\/\/[^\n]*|\/\*[\s\S]*?\*\/|"(?:\\.|[^"\\])*"|[{}]/g;
    tokens.lastIndex = source.indexOf('{', start);
    for (let t; (t = tokens.exec(source));) {
        if (t[0] === '{') depth++;
        else if (t[0] === '}' && --depth === 0) return source.slice(start, tokens.lastIndex);
    }
    throw Error(signature);
}
assert(!source.includes('additionFloatingButtonOffset += dp(SEARCH_TABS_HEIGHT)'));
assert(method('public void onResume()').includes('if (foldersAtBottom() && !filterTabsBootstrapPending) {\n            updateFilterTabsVisibility(true);'));
assert(method('private void checkUi_filterTabsVisible()').includes('updateFloatingButtonOffset();'));
assert(method('private int calculateListViewPaddingBottom()').includes('Math.round(getBottomFolderOffset())'));
assert(method('private void updateFilterTabs(boolean force').includes('            canShowFilterTabsView = false;\n            updateFilterTabsVisibility('));
const java = `
public class BottomFolderTest {
 static int checks;static float density=1;
 static void check(boolean b){checks++;if(!b)throw new AssertionError("check "+checks);}
 static int dp(float x){return (int)Math.ceil(x*density);}
 static class View {float y;void setTranslationY(float v){y=v;}}
 static class Tabs {int counters;void checkTabsCounter(boolean a){check(!a);counters++;}}
 static class Animator {
  boolean target,animated;float factor;int calls;
  float getFloatValue(){return factor;}
  void setValue(boolean value,boolean animate){target=value;animated=animate;calls++;if(!animate)factor=value?1:0;}
 }
 static class AndroidUtilities {static void cancelRunOnUIThread(Runnable r){}}
 static class LaunchActivity {boolean preparing;boolean isAccountSwitchPreparing(){return preparing;}}
 static class NimarkoFoldersHelper {
  static float getFloatingButtonsOffset(Tabs t){return 0;}
  static void updateFoldersOffset(Object o,boolean b){}
 }
 static class Host {
  static final int SEARCH_TABS_HEIGHT=50;
  boolean bottom=true,forward,isPaused,searchIsShowed,canShowFilterTabsView,filterTabsBootstrapPending=true,onlySelect;
  float searchFactor,rightFactor,additionalFloatingTranslation,floatingButtonPanOffset;
  int navigationBarHeight,additionFloatingButtonOffset;
  Object fragmentView=new Object(),databaseMigrationHint;
  Tabs filterTabsView=new Tabs();Animator animatorFilterTabsVisible=new Animator();
  View floatingButton3=new View(),floatingButtonStories=new View(),storyHint=new View();
  Runnable filterTabsBootstrapTimeout=()->{};
  LaunchActivity parent=new LaunchActivity();Object getParentActivity(){return parent;}
  boolean foldersAtBottom(){return bottom;}
  void checkUi_searchFieldVisibility(){}
  float getFilterTabsVisibilityFactor(boolean includeSearch){return (1-searchFactor)*(1-rightFactor)*animatorFilterTabsVisible.factor;}
  ${method('private float getBottomFolderOffset()')}
  ${method('private void updateFloatingButtonOffset()')}
  ${method('private void updateFilterTabsVisibility(boolean animated)')}
  ${method('private void finishFilterTabsBootstrap()')}
  ${method('private boolean isCoveredByAccountSwitch()')}
 }
 public static void main(String[] args){
  for(float d:new float[]{1,1.5f,2.625f,3,4})for(boolean mainTabs:new boolean[]{false,true}){
   density=d;Host h=new Host();h.navigationBarHeight=dp(24);h.additionFloatingButtonOffset=mainTabs?dp(64):0;
   float base=-h.navigationBarHeight-h.additionFloatingButtonOffset;
   h.finishFilterTabsBootstrap();h.updateFloatingButtonOffset();
   check(h.floatingButton3.y==base);check(h.getBottomFolderOffset()==0);
   h.canShowFilterTabsView=true;h.filterTabsBootstrapPending=true;h.isPaused=true;
   h.finishFilterTabsBootstrap();check(!h.filterTabsBootstrapPending);check(h.filterTabsView.counters==1);
   check(h.animatorFilterTabsVisible.calls==0);check(h.getBottomFolderOffset()==0);
   h.isPaused=false;h.updateFilterTabsVisibility(true);check(h.animatorFilterTabsVisible.target&&h.animatorFilterTabsVisible.animated);
   float previous=base;
   for(int frame=0;frame<=100;frame++){
    h.animatorFilterTabsVisible.factor=frame/100f;h.updateFloatingButtonOffset();
    check(Math.abs(h.floatingButton3.y-(base-dp(50)*frame/100f))<.001f);
    check(h.floatingButton3.y<=previous);previous=h.floatingButton3.y;
    check(Math.abs(h.floatingButtonStories.y-h.floatingButton3.y+dp(52))<.001f);
    check(h.storyHint.y==h.floatingButtonStories.y);
   }
   h.canShowFilterTabsView=false;h.updateFilterTabsVisibility(true);
   check(!h.animatorFilterTabsVisible.target&&h.animatorFilterTabsVisible.animated);
   for(int frame=100;frame>=0;frame--){h.animatorFilterTabsVisible.factor=frame/100f;h.updateFloatingButtonOffset();check(h.floatingButton3.y>=previous);previous=h.floatingButton3.y;}
   check(h.floatingButton3.y==base);
   h.canShowFilterTabsView=true;h.updateFilterTabsVisibility(false);check(h.animatorFilterTabsVisible.animated);
   h.animatorFilterTabsVisible.factor=1;h.searchFactor=.5f;check(h.getBottomFolderOffset()==dp(50)*.5f);
   h.searchFactor=1;check(h.getBottomFolderOffset()==0);h.searchFactor=0;
   h.rightFactor=1;check(h.getBottomFolderOffset()==0);h.rightFactor=0;
   h.bottom=false;check(h.getBottomFolderOffset()==0);h.bottom=true;
   h.forward=true;check(h.getBottomFolderOffset()==dp(50));h.forward=false;
   h.filterTabsView=null;check(h.getBottomFolderOffset()==0);
   h.floatingButton3=h.floatingButtonStories=h.storyHint=null;h.updateFloatingButtonOffset();
  }
  Host covered=new Host();covered.canShowFilterTabsView=true;covered.parent.preparing=true;
  covered.finishFilterTabsBootstrap();
  check(covered.animatorFilterTabsVisible.target&&!covered.animatorFilterTabsVisible.animated);
  check(covered.animatorFilterTabsVisible.factor==1&&covered.getBottomFolderOffset()==dp(50));
  covered.parent.preparing=false;covered.animatorFilterTabsVisible.factor=0;
  covered.updateFilterTabsVisibility(true);check(covered.animatorFilterTabsVisible.animated);
  Host h=new Host();h.canShowFilterTabsView=true;h.searchIsShowed=true;
  h.updateFilterTabsVisibility(true);check(h.animatorFilterTabsVisible.calls==0);
  h.searchIsShowed=false;h.databaseMigrationHint=new Object();h.updateFilterTabsVisibility(true);
  check(!h.animatorFilterTabsVisible.animated);
  h.fragmentView=null;int calls=h.animatorFilterTabsVisible.calls;h.updateFilterTabsVisibility(true);check(h.animatorFilterTabsVisible.calls==calls);
  System.out.println("Bottom folder account regression: "+checks+" checks passed");
 }
}`;
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-bottom-folder-test-'));
try {
    fs.writeFileSync(path.join(dir, 'BottomFolderTest.java'), java);
    cp.execFileSync('javac', ['BottomFolderTest.java'], {cwd: dir, stdio: 'inherit'});
    cp.execFileSync('java', ['BottomFolderTest'], {cwd: dir, stdio: 'inherit'});
} finally {
    fs.rmSync(dir, {recursive: true, force: true});
}
