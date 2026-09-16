const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const cp = require('node:child_process');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../main/java');
const base = fs.readFileSync(path.join(root, 'org/telegram/ui/ActionBar/BaseFragment.java'), 'utf8');
const layout = fs.readFileSync(path.join(root, 'org/telegram/ui/ActionBar/ActionBarLayout.java'), 'utf8');
function method(source, marker) {
    const start = source.indexOf(marker);
    assert(start >= 0, marker);
    let end = source.indexOf('{', start), depth = 0;
    do {
        if (source[end] === '{') depth++;
        if (source[end] === '}') depth--;
        end++;
    } while (depth);
    return source.slice(start, end);
}
for (const name of ['onPause', 'onBeginSlide', 'onFragmentDestroy']) {
    assert.match(method(base, `public void ${name}()`), /navigationRequestGeneration\+\+/);
}
assert.match(layout, /fragment == null \|\| animationInProgress \|\| startedTracking \|\| predictiveBackInProgress \|\| predictiveInput \|\| checkTransitionAnimation\(\)/);
const java = `import java.util.function.BooleanSupplier;
public class LinkNavigationTest {
 static int checks;
 static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("check " + checks); }
 static class INavigationLayout {
  BaseFragment top; boolean swipe, transition;
  BaseFragment getLastFragment(){return top;}
  boolean isSwipeInProgress(){return swipe;}
  boolean isTransitionAnimationInProgress(){return transition;}
 }
 static class BaseFragment {
  boolean isFinished; long navigationRequestGeneration; INavigationLayout parentLayout;
  ${method(base, 'public java.util.function.BooleanSupplier captureNavigationRequest()')}
 }
 static BaseFragment fresh() {
  BaseFragment f = new BaseFragment(); f.parentLayout = new INavigationLayout(); f.parentLayout.top = f; return f;
 }
 public static void main(String[] args) {
  BaseFragment f=fresh(); BooleanSupplier old=f.captureNavigationRequest(); check(old.getAsBoolean());
  BooleanSupplier latest=f.captureNavigationRequest(); check(!old.getAsBoolean()); check(latest.getAsBoolean());
  for(int reason=0; reason<7; reason++) {
   f=fresh(); latest=f.captureNavigationRequest();
   if(reason==0)f.navigationRequestGeneration++;
   if(reason==1)f.isFinished=true;
   if(reason==2)f.parentLayout.top=new BaseFragment();
   if(reason==3)f.parentLayout=new INavigationLayout();
   if(reason==4)f.parentLayout.swipe=true;
   if(reason==5)f.parentLayout.transition=true;
   if(reason==6)f.parentLayout=null;
   check(!latest.getAsBoolean());
  }
  f=fresh(); BaseFragment child=new BaseFragment(); child.parentLayout=f.parentLayout;
  latest=child.captureNavigationRequest(); check(latest.getAsBoolean());
  f.navigationRequestGeneration++; check(!latest.getAsBoolean());
  f=fresh(); latest=f.captureNavigationRequest(); f.navigationRequestGeneration++;
  f.parentLayout.swipe=true; f.parentLayout.swipe=false;
  check(!latest.getAsBoolean()); check(f.captureNavigationRequest().getAsBoolean());
  check(!new BaseFragment().captureNavigationRequest().getAsBoolean());
  System.out.println("PASS: " + checks + " navigation lifecycle checks");
 }
}`;
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-link-navigation-'));
try {
    fs.writeFileSync(path.join(tmp, 'LinkNavigationTest.java'), java);
    cp.execFileSync('javac', ['LinkNavigationTest.java'], {cwd: tmp, stdio: 'inherit'});
    cp.execFileSync('java', ['LinkNavigationTest'], {cwd: tmp, stdio: 'inherit'});
} finally {
    fs.rmSync(tmp, {recursive: true, force: true});
}
