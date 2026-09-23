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
for (const file of ['SettingsActivity', 'UserInfoActivity']) {
    assert(read(`org/telegram/ui/${file}.java`).includes('switchToAccountAnimated(account)'));
}
const tabs = read('org/telegram/ui/MainTabsActivity.java');
assert.match(tabs, /o.dismissWithAccountSwitch\(popup -> \{/);
assert.match(tabs, /switchToAccountAnimated\(account, popup\)/);
assert.doesNotMatch(tabs, /dismissThen/);
assert(read('org/telegram/ui/DialogsActivity.java').includes('launchActivity.switchToAccount(account, true);'));
assert(launch.includes('switchToAccount(intentAccount[0], true);'));
assert.doesNotMatch(source, /Thread.sleep|CountDownLatch|\.await\(|setLayerType|clearViews|setBackgroundColor|FileOutputStream/);
assert.match(source, /PixelCopy.request\(window/);
assert.match(launch, /accountSwitchTransition.start\(frameLayout, getWindow\(\)/);
const options = read('org/telegram/ui/Components/ItemOptions.java');
function block(text, signature, from = 0) {
 const start = text.indexOf(signature, from);
 assert(start >= 0, signature);
 const open = text.indexOf('{', start);
 let depth = 1, end = open + 1;
 for (; depth; end++) {
  if (text[end] === '{') depth++;
  if (text[end] === '}') depth--;
 }
 return text.slice(start, end);
}
const concurrent = block(options, 'public void dismissWithAccountSwitch(');
const popupClass = block(options, 'private final class AccountSwitchPopup');
const popupOverrides = block(options, 'actionBarPopupWindow = new ActionBarPopupWindow(');
const popupDismiss = block(options, 'public void onDismiss()');
// This selector uses a window enter style, not the ActionBar backScaleY/child
// opener. Introducing that independent animator would need an explicit handoff.
assert.doesNotMatch(options, /(?:actionBarPopupWindow|ActionBarPopupWindow)\.startAnimation\(/);
assert.doesNotMatch(block(read('org/telegram/ui/ActionBar/ActionBarPopupWindow.java'), 'public void showAtLocation('), /startAnimation\(/);
assert.match(popupClass, /window.setAnimationStyle\(0\);\s*window.update\(\);/);
const java = `import java.util.*;import java.util.function.BooleanSupplier;import java.util.function.Consumer;
class Animator {}
class AnimatorListenerAdapter {public void onAnimationEnd(Animator a){}}
class ValueAnimator extends Animator {
 interface Update {void update(ValueAnimator a);}
 static ValueAnimator last;static List<ValueAnimator> running=new ArrayList<>();
 List<Update> updates=new ArrayList<>();List<AnimatorListenerAdapter> listeners=new ArrayList<>();
 float value;long duration;Object interpolator;
 static ValueAnimator ofFloat(float a,float b){return new ValueAnimator();}
 void setDuration(long d){duration=d;}void setInterpolator(Object i){interpolator=i;}
 void addUpdateListener(Update u){updates.add(u);}void addListener(AnimatorListenerAdapter l){listeners.add(l);}
 void removeAllListeners(){listeners.clear();}void removeAllUpdateListeners(){updates.clear();}
 Object getAnimatedValue(){return value;}
 void start(){last=this;running.add(this);tick(0);}
 void tick(float progress){value=progress;for(Update u:new ArrayList<>(updates))u.update(this);}
 void finish(){tick(1);running.remove(this);for(AnimatorListenerAdapter l:new ArrayList<>(listeners))l.onAnimationEnd(this);}
 void cancel(){running.remove(this);for(AnimatorListenerAdapter l:new ArrayList<>(listeners))l.onAnimationEnd(this);}
}
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
 static final int IMPORTANT_FOR_ACCESSIBILITY_NO=2,VISIBLE=0;
 interface OnAttachStateChangeListener {void onViewAttachedToWindow(View v);void onViewDetachedFromWindow(View v);}
 interface OnTouchListener {boolean onTouch(View v,Object event);}
 List<OnAttachStateChangeListener> attachListeners=new ArrayList<>();
 void addOnAttachStateChangeListener(OnAttachStateChangeListener l){attachListeners.add(l);}
 void removeOnAttachStateChangeListener(OnAttachStateChangeListener l){attachListeners.remove(l);}
 ViewGroup parent;float alpha=1,scaleX=1,scaleY=1,translationY;boolean clickable;
 View(Object c){} void setBackgroundColor(int c){}void setClickable(boolean b){clickable=b;}
 void setImportantForAccessibility(int i){}void setAlpha(float a){alpha=a;}Object getParent(){return parent;}
 float getAlpha(){return alpha;}float getScaleX(){return scaleX;}float getScaleY(){return scaleY;}float getTranslationY(){return translationY;}
 void setScaleX(float f){scaleX=f;}void setScaleY(float f){scaleY=f;}void setTranslationY(float f){translationY=f;}void setVisibility(int i){}
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
 void detach(){attached=false;for(OnAttachStateChangeListener l:new ArrayList<>(attachListeners))l.onViewDetachedFromWindow(this);}
}
${source}
class LayoutHelper {static final int WRAP_CONTENT=-2;}
class AndroidUtilities {static void removeFromParent(View v){if(v.parent!=null)v.parent.removeView(v);}}
class PopupWindow {interface OnDismissListener {void onDismiss();}}
class ActionBarPopupWindow extends PopupWindow {
 View content;boolean showing=true,closing;int immediateDismisses,animatedDismisses,style;boolean focus=true,outside=true;
 OnDismissListener listener;View.OnTouchListener touch;
 ActionBarPopupWindow(View content,int w,int h){this.content=content;}
 View getContentView(){return content;}boolean isShowing(){return showing;}
 void setOnDismissListener(OnDismissListener l){listener=l;}void setTouchInterceptor(View.OnTouchListener l){touch=l;}
 void setOutsideTouchable(boolean b){outside=b;}void setFocusable(boolean b){focus=b;}void setAnimationStyle(int i){style=i;}void update(){}
 public void dismiss(){dismiss(true);}
 public void dismiss(boolean animated){if(!showing)return;if(animated){animatedDismisses++;closing=true;}else{immediateDismisses++;finishDismiss();}}
 void finishDismiss(){if(!showing)return;showing=false;closing=false;if(listener!=null)listener.onDismiss();}
}
class ItemOptions {
 ActionBarPopupWindow actionBarPopupWindow;FrameLayout pointContainer;View layout=new View(null),scrimView=new View(null);
 class DimView extends View {boolean blurCaptureActive=true;DimView(){super(null);}void restoreBlurAnchor(){}}
 DimView dimView;ValueAnimator dimAnimator;ViewTreeObserver.OnPreDrawListener preDrawListener;
 ViewTreeObserver preDrawObserver;
 Runnable dismissListener;AccountSwitchPopup accountSwitchPopup;
 boolean dontDismiss,hideScrimUnder,scaleOut;int hoverClears,followClears,dimDismisses;
 static int dp(int n){return n;}
 void clearHoverListener(){hoverClears++;}void removeFollowListeners(){followClears++;}
 void dismissDim(FrameLayout container){if(dimView==null)return;dimDismisses++;AndroidUtilities.removeFromParent(dimView);dimView=null;container.observer.removeOnPreDrawListener(preDrawListener);}
 ItemOptions(FrameLayout owner){pointContainer=owner;dimView=new DimView();owner.addView(dimView,new ViewGroup.LayoutParams(-1,-1));preDrawListener=()->true;preDrawObserver=owner.observer;owner.observer.addOnPreDrawListener(preDrawListener);
  final FrameLayout container=owner;
  ${popupOverrides};
  actionBarPopupWindow.setOnDismissListener(new PopupWindow.OnDismissListener(){${popupDismiss}});
 }
 ${block(options, 'public boolean isShown()')}
 ${block(options, 'public ItemOptions setOnDismiss(')}
 ${concurrent}
 ${block(options, 'private void notifyDismissListener()')}
 ${popupClass}
 ${block(options, 'public void dismiss()', options.indexOf('public void dismissWithAccountSwitch('))}
}
public class AccountSwitchTest {
 static int checks,changes;static boolean valid=true;
 static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 static void empty(FrameLayout f,AccountSwitchTransition t){check(!t.isRunning()&&f.children.isEmpty()&&f.frames.isEmpty()&&f.delayed.isEmpty()&&f.observer.listeners.isEmpty()&&f.attachListeners.isEmpty()&&ValueAnimator.running.isEmpty(),"complete cleanup");}
 static void captured(FrameLayout f){PixelCopy.complete(0);f.observer.draw();f.frame();}
 static void revealed(FrameLayout f){f.observer.draw();f.frame();}
 static void popupChecks(){
  FrameLayout f=new FrameLayout(),anchor=new FrameLayout();Window w=new Window();AccountSwitchTransition t=new AccountSwitchTransition();
  ItemOptions menu=new ItemOptions(anchor);ActionBarPopupWindow window=menu.actionBarPopupWindow;int[] notices={0},starts={0},commits={0};
  menu.scaleOut=true;menu.setOnDismiss(()->notices[0]++);
  menu.dismissWithAccountSwitch(p->{starts[0]++;t.start(f,w,()->true,()->{
   check(window.showing&&!window.closing,"real popup survives account teardown");
   check(anchor.children.isEmpty()&&anchor.observer.listeners.isEmpty(),"captured dim released before anchor teardown");
   anchor.detach();commits[0]++;
  },p);});
  check(starts[0]==1&&commits[0]==0&&PixelCopy.pending.size()==1,"capture starts immediately, not from dismiss completion");
  check(window.showing&&!window.closing&&window.immediateDismisses==0&&window.animatedDismisses==0,"no premature native dismissal");
  check(!anchor.children.isEmpty()&&notices[0]==0,"dim remains visible for activity capture");
  check(menu.hoverClears==1&&menu.followClears==1&&!window.focus&&!window.outside&&window.touch.onTouch(null,null),"freeze anchor tracking and consume popup input");
  menu.dismissWithAccountSwitch(p->starts[0]++);menu.dismiss();window.dismiss(false);
  check(starts[0]==1&&window.showing&&notices[0]==0,"duplicate selection and anchor-driven dismiss cannot end owned window");
  Bitmap bitmap=PixelCopy.complete(0);check(!anchor.children.isEmpty(),"dim stays until captured cover has drawn");
  f.observer.draw();f.frame();check(commits[0]==1&&notices[0]==1&&window.showing,"commit without waiting for popup exit");
  check(ValueAnimator.running.isEmpty()&&window.content.alpha==1,"both surfaces wait for incoming layout together");
  revealed(f);View cover=f.cover();ValueAnimator fade=ValueAnimator.last;
  check(ValueAnimator.running.size()==1&&fade.duration==180,"one 180ms clock for both windows");
  for(float progress:new float[]{0,.1f,.5f,.9f}){
   fade.tick(progress);
   check(cover.alpha==1-progress&&window.content.alpha==cover.alpha&&window.showing,"popup and account alpha share every frame");
   check(window.content.scaleX==1-.2f*progress&&window.content.scaleY==window.content.scaleX,"popup scale exit follows common progress");
  }
  ValueAnimator.Update staleUpdate=fade.updates.get(0);AnimatorListenerAdapter staleEnd=fade.listeners.get(0);
  fade.finish();empty(f,t);
  check(!window.showing&&window.immediateDismisses==1&&window.animatedDismisses==0&&window.content.alpha==0&&bitmap.recycled,"native popup removed only after fade completes");
  check(menu.accountSwitchPopup==null&&menu.actionBarPopupWindow==null&&notices[0]==1,"one dismiss notification and no retained popup");
  menu.dismiss();check(notices[0]==1,"repeat dismissal cannot rerun listener");

  ItemOptions newer=new ItemOptions(new FrameLayout());ActionBarPopupWindow next=newer.actionBarPopupWindow;
  newer.dismissWithAccountSwitch(p->t.start(f,w,()->true,()->{},p));captured(f);revealed(f);
  ValueAnimator nextFade=ValueAnimator.last;staleUpdate.update(fade);staleEnd.onAnimationEnd(fade);
  check(t.isRunning()&&next.showing&&next.content.alpha==1,"stale animation cannot close replacement popup");
  nextFade.tick(.5f);check(next.content.translationY==-2.5f,"non-scale popup exit follows common progress");
  nextFade.finish();empty(f,t);

  // Pause/destroy/rotation/passcode use cancel(); root detachment must work too,
  // at every asynchronous boundary, including an in-flight PixelCopy.
  for(int stage=0;stage<6;stage++)for(int detach=0;detach<2;detach++){
   FrameLayout host=new FrameLayout(),oldFragment=new FrameLayout();AccountSwitchTransition transition=new AccountSwitchTransition();
   ItemOptions item=new ItemOptions(oldFragment);ActionBarPopupWindow nativeWindow=item.actionBarPopupWindow;int[] dismissed={0},changed={0};
   item.setOnDismiss(()->dismissed[0]++);item.dismissWithAccountSwitch(p->transition.start(host,w,()->true,()->changed[0]++,p));
   Bitmap copy=PixelCopy.pending.get(0).bitmap;
   if(stage>0)PixelCopy.complete(0);if(stage>1){host.observer.draw();host.frame();}if(stage>2)host.observer.draw();if(stage>3)host.frame();if(stage>4)ValueAnimator.last.tick(.4f);
   if(detach==0)transition.cancel();else host.detach();
   if(stage==0){check(!copy.recycled,"in-flight copy not recycled on popup cancellation");PixelCopy.complete(0);}
   host.frame();host.timeout();empty(host,transition);
   check(!nativeWindow.showing&&copy.recycled&&dismissed[0]==1&&item.accountSwitchPopup==null,"lifecycle releases window and capture exactly once");
   check(oldFragment.children.isEmpty()&&oldFragment.observer.listeners.isEmpty(),"lifecycle removes original dim observer");
   check(changed[0]==(stage>1?1:0),"lifecycle never commits a deferred account");
  }

  // Preserve generic menus: they still use their own animated dismissal and
  // their existing start listener, not account-transition ownership.
  ItemOptions ordinary=new ItemOptions(new FrameLayout());ActionBarPopupWindow normal=ordinary.actionBarPopupWindow;int[] normalNotices={0};
  ordinary.setOnDismiss(()->{normalNotices[0]++;ordinary.dismiss();});ordinary.dismiss();
  check(normal.closing&&normal.showing&&normalNotices[0]==1&&ordinary.dimDismisses==1,"ordinary menu keeps animated dismiss and reentrant listener");
  normal.finishDismiss();ordinary.dismiss();check(normalNotices[0]==1&&ordinary.accountSwitchPopup==null,"ordinary completion preserves exactly-once listener");

  // Invalidations, missing snapshots and exceptions cannot strand the native
  // popup, run navigation twice or replace another menu's dismiss listener.
  for(int failure=0;failure<8;failure++){
   FrameLayout host=new FrameLayout(),oldFragment=new FrameLayout();AccountSwitchTransition transition=new AccountSwitchTransition();
   ItemOptions item=new ItemOptions(oldFragment);ActionBarPopupWindow nativeWindow=item.actionBarPopupWindow;int[] dismissed={0},changed={0};
   int mode=failure;item.setOnDismiss(()->dismissed[0]++);
   if(mode==1)w.attrs.flags=WindowManager.LayoutParams.FLAG_SECURE;
   if(mode==2)Build.VERSION.SDK_INT=25;
   if(mode==3)Bitmap.fail=true;
   if(mode==4)PixelCopy.fail=true;
   item.dismissWithAccountSwitch(p->transition.start(host,w,()->mode!=0,()->changed[0]++,p));
   w.attrs.flags=0;Build.VERSION.SDK_INT=36;Bitmap.fail=false;PixelCopy.fail=false;
   if(mode==5){PixelCopy.complete(1);host.frame();}
   if(mode==6){host.timeout();PixelCopy.complete(0);}
   if(mode==7){valid=true;transition.cancel();PixelCopy.complete(0);}
   if(mode>=3&&mode<=6){
    check(!transition.isRunning()&&ValueAnimator.running.isEmpty(),"no transparent-cover fade after capture failure or timeout");
   }
   if(transition.isRunning()){
    revealed(host);check(nativeWindow.showing&&ValueAnimator.last.duration==180,"captured frame fades with real popup");ValueAnimator.last.finish();
   }
   empty(host,transition);check(!nativeWindow.showing&&dismissed[0]==1&&oldFragment.children.isEmpty(),"fallback releases popup and dim");
   check(changed[0]==(mode==0||mode==7?0:1),"fallback commits at most once");
  }
  for(int reentrant=0;reentrant<3;reentrant++){
   FrameLayout host=new FrameLayout();AccountSwitchTransition transition=new AccountSwitchTransition();ItemOptions item=new ItemOptions(new FrameLayout());ActionBarPopupWindow nativeWindow=item.actionBarPopupWindow;
   int mode=reentrant;int[] changed={0};
   item.setOnDismiss(()->{if(mode==0)transition.cancel();if(mode==1)valid=false;if(mode==2)throw new IllegalStateException("dismiss callback");});
   valid=true;item.dismissWithAccountSwitch(p->transition.start(host,w,()->valid,()->changed[0]++,p));
   try{captured(host);check(mode!=2,"dismiss exception must propagate");}catch(IllegalStateException expected){check(mode==2,"expected dismiss exception");}
   valid=true;empty(host,transition);check(changed[0]==0&&!nativeWindow.showing,"dismiss callback cannot commit cancelled or invalid account");
  }
  ItemOptions throwing=new ItemOptions(new FrameLayout());ActionBarPopupWindow throwingWindow=throwing.actionBarPopupWindow;
  throwing.dismissWithAccountSwitch(p->t.start(f,w,()->true,()->{throw new IllegalStateException("change");},p));
  try{captured(f);throw new AssertionError("expected change failure");}catch(IllegalStateException expected){}
  empty(f,t);check(!throwingWindow.showing&&!t.isApplying(),"failed account commit closes popup");

  ItemOptions noLaunch=new ItemOptions(new FrameLayout());ActionBarPopupWindow abandoned=noLaunch.actionBarPopupWindow;
  noLaunch.dismissWithAccountSwitch(p->p.finish());check(!abandoned.showing&&noLaunch.accountSwitchPopup==null,"missing activity releases owned window");
  int[] missingStarts={0};noLaunch.dismissWithAccountSwitch(p->{check(p==null,"already dismissed popup needs no overlay");missingStarts[0]++;});
  check(missingStarts[0]==1,"already dismissed popup starts account without waiting");

  ItemOptions replaced=new ItemOptions(new FrameLayout());ActionBarPopupWindow replacedWindow=replaced.actionBarPopupWindow;
  replaced.setOnDismiss(()->t.start(f,w,()->true,()->{}));
  replaced.dismissWithAccountSwitch(p->t.start(f,w,()->true,()->{},p));
  ItemOptions superseded=new ItemOptions(new FrameLayout());ActionBarPopupWindow supersededWindow=superseded.actionBarPopupWindow;
  superseded.dismissWithAccountSwitch(p->t.start(f,w,()->true,()->{throw new AssertionError("superseded start");},p));
  check(!replacedWindow.showing&&!supersededWindow.showing&&t.isRunning(),"reentrant dismiss starts newer transition without being overwritten");
  PixelCopy.complete(0);captured(f);revealed(f);ValueAnimator.last.finish();empty(f,t);

  ItemOptions rebuiltObserver=new ItemOptions(new FrameLayout());ViewTreeObserver originalObserver=rebuiltObserver.pointContainer.observer;
  rebuiltObserver.dismissWithAccountSwitch(p->t.start(f,w,()->true,()->{},p));
  rebuiltObserver.pointContainer.observer=new ViewTreeObserver();t.cancel();PixelCopy.complete(0);
  empty(f,t);check(originalObserver.listeners.isEmpty(),"dim listener removed from original observer after detach/rebuild");

  ItemOptions lateLogout=new ItemOptions(new FrameLayout());ActionBarPopupWindow logoutWindow=lateLogout.actionBarPopupWindow;
  valid=true;lateLogout.dismissWithAccountSwitch(p->t.start(f,w,()->valid,()->{throw new AssertionError("logged out");},p));
  valid=false;captured(f);valid=true;empty(f,t);check(!logoutWindow.showing,"logout while copying closes popup without account commit");

  ItemOptions cancelledByListener=new ItemOptions(new FrameLayout());ActionBarPopupWindow listenerWindow=cancelledByListener.actionBarPopupWindow;
  cancelledByListener.setOnDismiss(()->t.start(f,w,()->true,()->{}));
  cancelledByListener.dismissWithAccountSwitch(p->t.start(f,w,()->true,()->{throw new AssertionError("old listener commit");},p));
  captured(f);check(!listenerWindow.showing&&PixelCopy.pending.size()==1&&t.isRunning(),"capture listener can replace navigation safely");
  captured(f);revealed(f);ValueAnimator.last.finish();empty(f,t);

  ItemOptions failedCaptureListener=new ItemOptions(new FrameLayout());int[] failedCommit={0};
  failedCaptureListener.setOnDismiss(()->t.start(f,w,()->true,()->{}));
  failedCaptureListener.dismissWithAccountSwitch(p->t.start(f,w,()->true,()->failedCommit[0]++,p));
  PixelCopy.complete(1);f.frame();
  check(failedCommit[0]==0&&t.isRunning()&&PixelCopy.pending.size()==1,
    "failed capture dismiss callback can replace navigation without old account commit");
  captured(f);revealed(f);ValueAnimator.last.finish();empty(f,t);

  ItemOptions failingReplacement=new ItemOptions(new FrameLayout());
  failingReplacement.dismissWithAccountSwitch(p->t.start(f,w,()->true,()->{t.start(f,w,()->true,()->{});throw new IllegalStateException("old change");},p));
  try{captured(f);throw new AssertionError("expected old change exception");}catch(IllegalStateException expected){}
  check(t.isRunning()&&PixelCopy.pending.size()==1,"old change exception cannot cancel replacement");
  captured(f);revealed(f);ValueAnimator.last.finish();empty(f,t);
 }
 static void contentReadinessChecks(){
  FrameLayout f=new FrameLayout();Window w=new Window();AccountSwitchTransition t=new AccountSwitchTransition();
  boolean[] ready={false};int[] committed={0};
  t.start(f,w,()->true,()->committed[0]++,null,()->ready[0]);
  check(!t.isPreparing(),"uncaptured live screen is not hidden");
  captured(f);check(committed[0]==1&&t.isPreparing(),"captured screen owns cold target preparation");
  for(int frame=0;frame<4;frame++){
   revealed(f);
   check(ValueAnimator.running.isEmpty()&&f.cover().alpha==1&&f.observer.listeners.size()==1,
     "root layout alone must not expose the cold empty adapter");
  }
  ready[0]=true;revealed(f);
  check(!t.isPreparing()&&ValueAnimator.last.duration==180,"ready content uses one unchanged 180ms clock");
  ValueAnimator.last.finish();empty(f,t);
  ready[0]=false;
  t.start(f,w,()->true,()->{},null,()->ready[0]);captured(f);revealed(f);f.timeout();
  check(!t.isPreparing()&&ValueAnimator.running.size()==1,"offline readiness remains bounded");
  ValueAnimator.last.finish();empty(f,t);
  t.start(f,w,()->true,()->{},null,()->false);captured(f);
  ViewTreeObserver.OnPreDrawListener stale=f.observer.listeners.get(0);
  t.cancel();stale.onPreDraw();f.frame();f.timeout();empty(f,t);
  check(committed[0]==1,"waiting for local data never repeats account commit");
 }
 public static void main(String[] args){
  FrameLayout f=new FrameLayout();Window w=new Window();AccountSwitchTransition t=new AccountSwitchTransition();
  Runnable change=()->{check(t.isApplying(),"synchronous switch ownership");changes++;};
  t.start(f,w,()->valid,change);View first=f.cover();
  check(first.clickable&&first.alpha==1&&changes==0&&((ImageView)first).bitmap==null,"transparent blocker leaves live screen visible during async copy");
  Bitmap copy=PixelCopy.complete(PixelCopy.SUCCESS);
  check(changes==0&&((ImageView)first).bitmap==copy,"old screen captured before commit");
  f.frame();check(changes==0&&copy.prepared,"GPU warmup and snapshot draw precede rebuilding");f.observer.draw();f.frame();check(changes==1&&!t.isApplying()&&first.alpha==1,"single account commit behind old screen");
  f.observer.draw();check(ValueAnimator.running.isEmpty(),"wait for incoming layout frame");f.frame();
  check(ValueAnimator.last.duration==180,"crossfade timing");
  check(ValueAnimator.last.interpolator==CubicBezierInterpolator.EASE_BOTH,"gentle acceleration and deceleration");
  ValueAnimator.last.finish();empty(f,t);check(copy.recycled,"snapshot released");
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
  f.timeout();check(ValueAnimator.last.duration==180&&f.observer.listeners.isEmpty(),"bounded readiness fallback");ValueAnimator.last.finish();empty(f,t);
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
  check(t.isRunning()&&((ImageView)first).bitmap==null,"old capture cannot affect replacement");PixelCopy.complete(0);f.observer.draw();f.frame();f.observer.draw();f.frame();ValueAnimator.last.finish();empty(f,t);
  t.start(f,w,()->true,()->{t.cancel();});PixelCopy.complete(0);f.observer.draw();f.frame();empty(f,t);
  t.start(f,w,()->true,()->{throw new IllegalStateException("test");});PixelCopy.complete(0);
  f.observer.draw();try{f.frame();throw new AssertionError("missing exception");}catch(IllegalStateException expected){}empty(f,t);
  check(!t.isApplying(),"failed commit releases ownership");
  popupChecks();contentReadinessChecks();
  System.out.println("PASS: "+checks+" account/popup checks: shared 180ms motion, separate window, capture, anchor teardown, listeners, lifecycle, stale callbacks, secure windows and failures");
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
 for (const [label, needle, replacement, expected] of [
  ['popup clock', 'if (popup != null) popup.setProgress(progress);', '', /popup and account alpha share every frame/],
  ['popup retention', 'if (popup != null) popup.onCaptured();', 'if (popup != null) { popup.onCaptured(); popup.finish(); }', /real popup survives account teardown/],
  ['dim handoff', 'if (popup != null) popup.onCaptured();', '', /captured dim released before anchor teardown/],
  ['popup lifecycle', 'if (oldOverlay != null) oldOverlay.finish();', '', /native popup removed only after fade completes/],
  ['cold content readiness', 'if (snapshot != null && !contentReady.getAsBoolean()) return true;', '', /root layout alone must not expose the cold empty adapter/]
 ]) {
  const mutation=java.replace(needle,replacement);assert.notEqual(mutation,java,label);
  fs.writeFileSync(path.join(dir,'AccountSwitchTest.java'),mutation);
  cp.execFileSync('javac',['AccountSwitchTest.java'],{cwd:dir,stdio:'pipe'});
  const result=cp.spawnSync('java',['AccountSwitchTest'],{cwd:dir,encoding:'utf8'});
  assert.notEqual(result.status,0,label);assert.match(result.stderr,expected,label);
  console.log('PASS: '+label+' negative control');
 }
} finally {fs.rmSync(dir,{recursive:true,force:true});}
