const fs = require('node:fs');
const path = require('node:path');
const cp = require('node:child_process');
const os = require('node:os');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../main/java');
const read = file => fs.readFileSync(path.join(root, file), 'utf8');
const source = read('org/telegram/ui/Components/AccountSwitchTransition.java').replace(/^package .*;\n|^import .*;\n/gm, '').replace('public final class AccountSwitchTransition', 'final class AccountSwitchTransition');
const launch = read('org/telegram/ui/LaunchActivity.java');
assert.match(launch, /addFragmentToStack\(mainTabsActivity, removeAll \? 0 : INavigationLayout.FORCE_ATTACH_VIEW_AS_FIRST\)/);
assert.match(launch, /if \(removeAll\) \{\s*actionBarLayout.showLastFragment\(\);\s*\} else \{\s*actionBarLayout.rebuildFragments/);
for (const signature of ['protected void onPause()', 'protected void onDestroy()', 'private void onFinish()', 'public void onConfigurationChanged(Configuration newConfig)', 'public void showPasscodeActivity(boolean fingerprint, boolean animated, int x, int y, Runnable onShow, Runnable onStart)']) {
    assert(launch.includes(signature + ' {\n        accountSwitchTransition.cancel();'), signature);
}
assert.match(launch, /if \(!accountSwitchTransition.isApplying\(\)\) accountSwitchTransition.cancel\(\);/);
assert.match(launch, /UserConfig.selectedAccount == source\s*&& [\w.]+.isCurrent\(account, owner, session\)/);
for (const file of ['MainTabsActivity', 'SettingsActivity', 'UserInfoActivity']) {
    assert(read(`org/telegram/ui/${file}.java`).includes('switchToAccountAnimated(account)'));
}
assert(read('org/telegram/ui/DialogsActivity.java').includes('launchActivity.switchToAccount(account, true);'));
assert(launch.includes('switchToAccount(intentAccount[0], true);'));
assert.doesNotMatch(source, /Thread.sleep|CountDownLatch|\.await\(|setLayerType|clearViews|setBackgroundColor|FileOutputStream/);
assert.match(source, /PixelCopy.request\(window/);
assert.match(launch, /accountSwitchTransition.start\(frameLayout, getWindow\(\)/);
const java = `import java.util.*;import java.util.function.BooleanSupplier;
class Build {static class VERSION {static int SDK_INT=36;}}
class Rect {int left,top,right,bottom;Rect(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;}}
class WindowManager {static class LayoutParams {static final int FLAG_SECURE=8192;int flags;}}
class Window {WindowManager.LayoutParams attrs=new WindowManager.LayoutParams();WindowManager.LayoutParams getAttributes(){return attrs;}}
class Bitmap {
 enum Config {ARGB_8888}static boolean fail;int width,height;boolean recycled;
 static Bitmap createBitmap(int w,int h,Config c){if(fail)throw new OutOfMemoryError();Bitmap b=new Bitmap();b.width=w;b.height=h;return b;}
 void prepareToDraw(){prepared=true;} boolean prepared;
 void recycle(){if(recycled)throw new AssertionError("double recycle");recycled=true;}
}
class PixelCopy {
 static final int SUCCESS=0;static boolean fail;interface Listener {void done(int result);}
 static class Request {Bitmap bitmap;Listener listener;Rect rect;Request(Bitmap b,Listener l,Rect r){bitmap=b;listener=l;rect=r;}}
 static List<Request> pending=new ArrayList<>();
 static void request(Window w,Rect r,Bitmap b,Listener l,Object h){if(fail)throw new IllegalArgumentException();pending.add(new Request(b,l,r));}
 static Bitmap complete(int result){Request r=pending.remove(0);if(r.bitmap.recycled)throw new AssertionError("recycled during copy");r.listener.done(result);return r.bitmap;}
}
class CubicBezierInterpolator {static Object EASE_OUT_QUINT=new Object(),EASE_BOTH=new Object();}
class View {
 static final int IMPORTANT_FOR_ACCESSIBILITY_NO=2;
 ViewGroup parent;float alpha=1;boolean clickable;Animator animator=new Animator();
 View(Object c){} void setBackgroundColor(int c){}void setClickable(boolean b){clickable=b;}
 void setImportantForAccessibility(int i){}void setAlpha(float a){alpha=a;}Object getParent(){return parent;}
 Animator animate(){return animator;}
 class Animator {Runnable end;long duration;float target;
  Animator alpha(float f){target=f;return this;}Animator setDuration(long d){duration=d;return this;}
  Animator setInterpolator(Object i){return this;}Animator withEndAction(Runnable r){end=r;return this;}
  void start(){}void cancel(){end=null;}void finish(){alpha=target;Runnable r=end;end=null;if(r!=null)r.run();}
 }
}
class ViewGroup extends View {
 ViewGroup(){super(null);}List<View> children=new ArrayList<>();
 static class LayoutParams {static int MATCH_PARENT=-1;LayoutParams(int a,int b){}}
 void addView(View v,LayoutParams p){children.add(v);v.parent=this;}
 void removeView(View v){children.remove(v);v.parent=null;}
}
class ImageView extends View {
 enum ScaleType {FIT_XY}Bitmap bitmap;ImageView(Object c){super(c);}void setScaleType(ScaleType t){}
 void setImageBitmap(Bitmap b){bitmap=b;}void setImageDrawable(Object d){bitmap=null;}
}
class ViewTreeObserver {
 interface OnPreDrawListener {boolean onPreDraw();}
 List<OnPreDrawListener> listeners=new ArrayList<>();
 boolean isAlive(){return true;}void addOnPreDrawListener(OnPreDrawListener r){listeners.add(r);}
 void removeOnPreDrawListener(OnPreDrawListener r){listeners.remove(r);}
 void draw(){for(OnPreDrawListener r:new ArrayList<>(listeners))r.onPreDraw();}
}
class FrameLayout extends ViewGroup {
 static class LayoutParams extends ViewGroup.LayoutParams {LayoutParams(int a,int b){super(a,b);}}
 int width=1080,height=2400;boolean attached=true;ViewTreeObserver observer=new ViewTreeObserver();
 int getWidth(){return width;}int getHeight(){return height;}Object getHandler(){return null;}
 void getLocationInWindow(int[] out){out[0]=10;out[1]=20;}
 List<Runnable> frames=new ArrayList<>(),delayed=new ArrayList<>();
 Object getContext(){return null;}boolean isAttachedToWindow(){return attached;}
 ViewTreeObserver getViewTreeObserver(){return observer;}void requestLayout(){}
 void postOnAnimation(Runnable r){frames.add(r);}void postDelayed(Runnable r,long d){delayed.add(r);}
 void removeCallbacks(Runnable r){frames.remove(r);delayed.remove(r);}
 void frame(){List<Runnable> list=new ArrayList<>(frames);frames.clear();for(Runnable r:list)r.run();}
 void timeout(){List<Runnable> list=new ArrayList<>(delayed);delayed.clear();for(Runnable r:list)r.run();}
 View cover(){return children.get(0);}
}
${source}
public class AccountSwitchTest {
 static int checks,changes;static boolean valid=true;
 static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 static void empty(FrameLayout f,AccountSwitchTransition t){check(!t.isRunning()&&f.children.isEmpty()&&f.frames.isEmpty()&&f.delayed.isEmpty()&&f.observer.listeners.isEmpty(),"complete cleanup");}
 public static void main(String[] args){
  FrameLayout f=new FrameLayout();Window w=new Window();AccountSwitchTransition t=new AccountSwitchTransition();
  Runnable change=()->{check(t.isApplying(),"synchronous switch ownership");changes++;};
  t.start(f,w,()->valid,change);View first=f.cover();
  check(first.clickable&&first.alpha==1&&changes==0&&((ImageView)first).bitmap==null,"transparent blocker leaves live screen visible during async copy");
  Bitmap copy=PixelCopy.complete(PixelCopy.SUCCESS);
  check(changes==0&&((ImageView)first).bitmap==copy,"old screen captured before commit");
  f.frame();check(changes==0&&copy.prepared,"GPU warmup and snapshot draw precede rebuilding");f.observer.draw();f.frame();check(changes==1&&!t.isApplying()&&first.alpha==1,"single account commit behind old screen");
  f.observer.draw();check(first.animator.duration==0,"wait for incoming layout frame");f.frame();
  check(first.animator.duration==200,"crossfade timing");first.animator.finish();empty(f,t);check(copy.recycled,"snapshot released");
  for(int stage=0;stage<4;stage++){
   int before=changes;t.start(f,w,()->true,change);first=f.cover();Bitmap pending=PixelCopy.pending.get(0).bitmap;
   if(stage>0)PixelCopy.complete(0);if(stage>1){f.observer.draw();f.frame();}if(stage>2)f.observer.draw();
   t.cancel();if(stage==0){check(!pending.recycled,"cancel waits for pixel copy before freeing");PixelCopy.complete(0);}f.frame();f.timeout();empty(f,t);
   check(changes==before+(stage>1?1:0),"cancel never commits delayed account");
   check(pending.recycled,"cancel frees completed snapshot");
  }
  int before=changes;t.start(f,w,()->false,change);empty(f,t);check(changes==before&&PixelCopy.pending.isEmpty(),"logged-out target rejected");
  t.start(f,w,()->valid,change);valid=false;PixelCopy.complete(0);f.observer.draw();f.frame();empty(f,t);valid=true;
  t.start(f,w,()->true,change);PixelCopy.complete(0);f.attached=false;f.observer.draw();f.frame();empty(f,t);f.attached=true;
  t.start(f,w,()->true,change);PixelCopy.complete(0);f.observer.draw();f.frame();
  ViewTreeObserver.OnPreDrawListener staleDraw=f.observer.listeners.get(0);Runnable staleReveal=f.delayed.get(0);
  t.start(f,w,()->true,change);View second=f.cover();PixelCopy.complete(0);f.observer.draw();f.frame();
  staleDraw.onPreDraw();staleReveal.run();check(f.observer.listeners.size()==1&&second.alpha==1,"old callbacks cannot reveal or unregister new account");
  f.timeout();check(second.animator.duration==200&&f.observer.listeners.isEmpty(),"bounded readiness fallback");second.animator.finish();empty(f,t);
  before=changes;t.start(f,w,()->true,change);f.timeout();empty(f,t);check(changes==before+1,"copy timeout commits normally");copy=PixelCopy.complete(0);check(copy.recycled&&changes==before+1,"late copy cannot revive timed-out transition");
  t.start(f,w,()->true,change);PixelCopy.complete(1);f.frame();empty(f,t);
  PixelCopy.fail=true;t.start(f,w,()->true,change);empty(f,t);PixelCopy.fail=false;
  Bitmap.fail=true;t.start(f,w,()->true,change);empty(f,t);Bitmap.fail=false;
  w.attrs.flags=WindowManager.LayoutParams.FLAG_SECURE;t.start(f,w,()->true,change);empty(f,t);w.attrs.flags=0;
  Build.VERSION.SDK_INT=25;t.start(f,w,()->true,change);empty(f,t);Build.VERSION.SDK_INT=36;
  check(PixelCopy.pending.isEmpty(),"secure and old devices never capture");
  f.width=4000;f.height=6000;t.start(f,w,()->true,change);PixelCopy.Request request=PixelCopy.pending.get(0);
  check((long)request.bitmap.width*request.bitmap.height<=4000000,"bounded memory");
  check(request.rect.left==10&&request.rect.top==20&&request.rect.right==4010&&request.rect.bottom==6020,"crop matches parent in window coordinates");
  t.cancel();PixelCopy.complete(0);empty(f,t);f.width=1080;f.height=2400;
  t.start(f,w,()->true,change);t.start(f,w,()->true,change);first=f.cover();PixelCopy.complete(0);
  check(t.isRunning()&&((ImageView)first).bitmap==null,"old capture cannot affect replacement");PixelCopy.complete(0);f.observer.draw();f.frame();f.observer.draw();f.frame();first.animator.finish();empty(f,t);
  t.start(f,w,()->true,()->{t.cancel();});PixelCopy.complete(0);f.observer.draw();f.frame();empty(f,t);
  t.start(f,w,()->true,()->{throw new IllegalStateException("test");});PixelCopy.complete(0);
  f.observer.draw();try{f.frame();throw new AssertionError("missing exception");}catch(IllegalStateException expected){}empty(f,t);
  check(!t.isApplying(),"failed commit releases ownership");
  System.out.println("PASS: "+checks+" account crossfade checks: async capture, secure windows, memory, lifecycle, stale callbacks, logout, timeout and failure cleanup");
 }
}`;
const dir=fs.mkdtempSync(path.join(os.tmpdir(),'nimarko-account-motion-'));
try {
 fs.writeFileSync(path.join(dir,'AccountSwitchTest.java'),java);
 cp.execFileSync('javac',['AccountSwitchTest.java'],{cwd:dir,stdio:'pipe'});
 process.stdout.write(cp.execFileSync('java',['AccountSwitchTest'],{cwd:dir,encoding:'utf8'}));
 const broken=java.replace('if (token != generation) return true;', '');
 assert.notEqual(broken,java);
 fs.writeFileSync(path.join(dir,'AccountSwitchTest.java'),broken);
 cp.execFileSync('javac',['AccountSwitchTest.java'],{cwd:dir,stdio:'pipe'});
 const result=cp.spawnSync('java',['AccountSwitchTest'],{cwd:dir,encoding:'utf8'});
 assert.notEqual(result.status,0);
 assert.match(result.stderr,/old callbacks cannot reveal or unregister new account/);
 console.log('PASS: stale layout callback negative control');
} finally {fs.rmSync(dir,{recursive:true,force:true});}
