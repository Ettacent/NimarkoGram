const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const cp = require('node:child_process');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../../..');
const profile = fs.readFileSync(path.join(root, 'TMessagesProj/src/main/java/org/telegram/ui/ProfileActivity.java'), 'utf8');
const pager = fs.readFileSync(path.join(root, 'TMessagesProj/src/main/java/org/telegram/ui/ViewPagerActivity.java'), 'utf8');
function method(source, signature) {
    const start = source.indexOf(signature);
    assert(start >= 0, signature);
    const tokens = /\/\/[^\n]*|\/\*[\s\S]*?\*\/|"(?:\\.|[^"\\])*"|[{}]/g;
    tokens.lastIndex = source.indexOf('{', start);
    let depth = 0;
    for (let token; (token = tokens.exec(source));) {
        if (token[0] === '{') depth++;
        if (token[0] === '}' && --depth === 0) return source.slice(start, tokens.lastIndex);
    }
    throw new Error(signature);
}
const resize = method(profile.slice(profile.indexOf('private boolean wasPortrait;')),
    'protected void onSizeChanged(int w, int h, int oldw, int oldh)');
const dispatch = method(pager, 'public void onConfigurationChanged(Configuration newConfig)');
assert(method(profile, 'public void onConfigurationChanged(Configuration newConfig)').includes('fixLayout();'));
function harness(resizeMethod, dispatchMethod) {
    return `import java.util.*;
public class ProfileRotationTest {
 static class Configuration {}
 static class ViewTreeObserver {
  interface OnPreDrawListener {boolean onPreDraw();}
  ArrayList<OnPreDrawListener> listeners=new ArrayList<>();
  void addOnPreDrawListener(OnPreDrawListener l) {listeners.add(l);}
  void removeOnPreDrawListener(OnPreDrawListener l) {listeners.remove(l);}
  void draw() {for (OnPreDrawListener l:new ArrayList<>(listeners)) l.onPreDraw();}
 }
 static class FrameLayout {static class LayoutParams {int width=100;}}
 static class View {
  int width;float x;FrameLayout.LayoutParams params=new FrameLayout.LayoutParams();
  ViewTreeObserver observer=new ViewTreeObserver();
  ViewTreeObserver getViewTreeObserver() {return observer;}
  int getMeasuredWidth() {return width;}
  Object getLayoutParams() {return params;}
  void setTranslationX(float value) {x=value;}
 }
 static class BaseFragment {
  View view=new View();int configurations;Runnable action;
  View getFragmentView() {return view;}
  public void onConfigurationChanged(Configuration c) {configurations++;if(action!=null)action.run();}
 }
 static class FragmentState {
  BaseFragment fragment;boolean onCreateCalled=true;
  FragmentState(BaseFragment f) {fragment=f;}
 }
 static class States extends ArrayList<FragmentState> {FragmentState valueAt(int i) {return get(i);}}
 static class Pager extends BaseFragment {
  States fragmentsArr=new States();
  ${dispatchMethod}
 }
 static class SizeBase {protected void onSizeChanged(int w,int h,int oldw,int oldh) {}}
 static class Blur3Utils {static void checkBitmapSourceMatrixScale(Object bitmap,View view) {}}
 static class BlurFactory {void invalidateAllLinkedViews() {}}
 static class Profile {
  View listView=new View(),avatarContainer=new View(),fragmentView=new View();
  Object scrimBlur3SourceBitmap;BlurFactory scrimBlur3Factory=new BlurFactory();
  float avatarX,avatarScale=.96f,prevAvatarTranslation=25,avatarAnimationProgress=1;
  boolean openAnimationInProgress;int layouts;
  static float lerp(float a,float b,float f) {return a+(b-a)*f;}
  void checkListViewScroll() {}
  void needLayout(boolean animated) {layouts++;fixAvatarImageInCenter();}
  ${method(profile, 'private void fixAvatarImageInCenter()')}
  ${method(profile, 'private void fixLayout()')}
  class Content extends SizeBase {${resizeMethod}}
 }
 static void check(boolean value,String reason) {if(!value)throw new AssertionError(reason);}
 static void near(float expected,float actual) {check(Math.abs(expected-actual)<.01f,"stale avatar centre: "+actual+" != "+expected);}
 public static void main(String[] args) {
  for(float density:new float[]{1,1.5f,2,3,4}) {
   for(float scale:new float[]{.24f,.96f,1.38f}) {
    Profile p=new Profile();Profile.Content c=p.new Content();
    p.avatarScale=scale;p.avatarContainer.params.width=(int)(100*density);
    int width=(int)(360*density),height=(int)(800*density);p.listView.width=width;p.needLayout(false);
    c.onSizeChanged(width,height,0,0);check(p.fragmentView.observer.listeners.isEmpty(),"do not restart initial layout");
    for(int step=0;step<8;step++) {
     int nextWidth=height,nextHeight=width;p.listView.width=nextWidth;
     c.onSizeChanged(nextWidth,nextHeight,width,height);
     p.fragmentView.observer.draw();
     near(nextWidth/2f,p.avatarContainer.x+p.avatarContainer.params.width*scale/2f);
     check(p.fragmentView.observer.listeners.isEmpty(),"one-shot resize callback");
     width=nextWidth;height=nextHeight;
    }
    p.listView.width=width/2;c.onSizeChanged(width/2,height,width,height);p.fragmentView.observer.draw();
    near(p.listView.width/2f,p.avatarContainer.x+p.avatarContainer.params.width*scale/2f);
    c.onSizeChanged(width/2,height,width/2,height);check(p.fragmentView.observer.listeners.isEmpty(),"no repeated reflow for unchanged bounds");
    p.openAnimationInProgress=true;p.avatarAnimationProgress=.5f;p.needLayout(false);
    near(Profile.lerp(25,p.listView.width/2f-p.avatarContainer.params.width*scale/2f,.5f),p.avatarContainer.x);
   }
  }
  Pager pager=new Pager();BaseFragment visible=new BaseFragment(),cached=new BaseFragment(),uncreated=new BaseFragment(),noView=new BaseFragment();
  FragmentState inactive=new FragmentState(uncreated);inactive.onCreateCalled=false;noView.view=null;
  pager.fragmentsArr.add(new FragmentState(visible));pager.fragmentsArr.add(new FragmentState(cached));
  pager.fragmentsArr.add(inactive);pager.fragmentsArr.add(new FragmentState(noView));pager.fragmentsArr.add(null);
  visible.action=()->pager.fragmentsArr.clear();
  pager.onConfigurationChanged(new Configuration());
  check(pager.configurations==1 && visible.configurations==1 && cached.configurations==1,"dispatch reaches all existing tabs even if callbacks change the tab list");
  check(uncreated.configurations==0 && noView.configurations==0,"do not initialize absent tabs");
  System.out.println("PASS: profile rotation/resize centering, unchanged header height, transition interpolation, and configuration dispatch to existing tabs");
 }
}`;
}
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'profile-rotation-'));
function run(java) {
    fs.writeFileSync(path.join(dir, 'ProfileRotationTest.java'), java);
    cp.execFileSync('javac', ['ProfileRotationTest.java'], {cwd: dir});
    return cp.spawnSync('java', ['ProfileRotationTest'], {cwd: dir, encoding: 'utf8'});
}
const fixed = run(harness(resize, dispatch));
assert.equal(fixed.status, 0, fixed.stderr);
process.stdout.write(fixed.stdout);
const oldResize = resize.replace(/\s*if \(oldw > 0 && oldh > 0 && \(w != oldw \|\| h != oldh\)\) \{\s*fixLayout\(\);\s*\}/, '');
assert.notEqual(oldResize, resize);
assert.notEqual(run(harness(oldResize, dispatch)).status, 0, 'old resize path must reproduce stale centering');
assert.notEqual(run(harness(resize, 'public void onConfigurationChanged(Configuration c) {super.onConfigurationChanged(c);}')).status, 0,
    'old tab host must fail configuration delivery');
console.log('PASS: both previous paths fail negative controls; host tests do not replace device rotation testing');
