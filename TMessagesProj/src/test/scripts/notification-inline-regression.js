const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const cp = require('node:child_process');
const assert = require('node:assert/strict');
const read = file => fs.readFileSync(path.resolve(__dirname, '../../main/java', file), 'utf8');
const inline = read('app/nimarkogram/messenger/notifications/NotificationInlinePanel.java');
const source = read('app/nimarkogram/messenger/notifications/NimarkoInAppNotifications.java');
function extract(text, signature) {
    const start = text.indexOf(signature);
    assert(start >= 0, signature);
    let end = text.indexOf('{', start), depth = 1;
    while (depth && ++end < text.length) {
        if (text[end] === '{') depth++;
        if (text[end] === '}') depth--;
    }
    assert.equal(depth, 0);
    return text.slice(start, end + 1);
}
const layout = read('org/telegram/ui/Components/AnimatedLinearLayout.java');
assert.match(layout, /directResize && !listAnimator.isAnimating\(\)\) listAnimator.measureImpl\(false\)/);
assert.match(layout, /if \(holder.trackSize\)/);
assert.match(layout, /trackedLayout.equals\(visibleHolders\)/);
assert.match(layout, /!sameItems \|\| skipNextAnimation \|\| otherSizeChanged/);
assert.match(source, /retainedCoverage = getLayoutCoverage\(\)/);
assert.match(extract(source, 'void attach(Banner value)'), /setMinimumHeight\(0\)/);
assert.match(source, /panel.setTrackChildSize\(this\)/);
assert.match(source, /panel.setPriority\(this, -100\)/);
assert.match(source, /boolean animate = isAttachedToWindow\(\) && isShown\(\) && value.getVisibility\(\) == VISIBLE && !value.moving/);
assert.match(source, /getChildCount\(\) == 0\)[\s\S]*?panel.setViewVisible\(this, false, animate\)/);
assert.match(source, /!animate && panel instanceof NotificationInlinePanel[\s\S]*?onContentRemoved\(\)/);
const detach = extract(source, 'protected void onDetachedFromWindow()');
assert.match(detach, /if \(!moving\)[\s\S]*closing = true;[\s\S]*setVisibility\(INVISIBLE\)/);
assert.match(detach, /AndroidUtilities.runOnUIThread\([\s\S]*getParent\(\) == previous\) previous.release\(this\)/);
assert.doesNotMatch(extract(source, 'private static boolean show('), /contentGesture/);
assert.match(extract(source, 'private static boolean show('), /panel = resolvePanel\(next\)/);
assert.doesNotMatch(extract(source, 'private static boolean show('), /getDecorView/);
assert.match(read('org/telegram/ui/LaunchActivity.java'), /dispatchTouchEvent\(android.view.MotionEvent event\)[\s\S]*onContentTouch\(event\)/);
assert.match(read('org/telegram/ui/ActionBar/BaseFragment.java'), /child.getLayoutParams\(\).height == ViewGroup.LayoutParams.MATCH_PARENT/);
assert.match(read('org/telegram/ui/ChatActivity.java'), /return inPreviewMode \|\| isInsideContainer \? null : topPanelLayout/);
assert.match(extract(read('org/telegram/ui/DialogsActivity.java'), 'getInAppNotificationPanel()'), /return topPanelLayout;/);
const java = `
import java.util.*;
import java.util.function.IntSupplier;
class NotificationListInset {boolean apply(int h,int a,float v){return false;}}
class View {
 Object parent; FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(); int padding, updates, invalidations;
 void invalidate(){invalidations++;}
 int getTop(){return 0;}
 Object getParent(){return parent;} Object getLayoutParams(){return params;} int getPaddingTop(){return padding;}
 void setLayoutParams(FrameLayout.LayoutParams p){params=p;updates++;}
}
class FrameLayout {int updates,height=2354;int getHeight(){return height;} void requestLayout(){updates++;}
 static class LayoutParams {int topMargin, bottomMargin;}
}
class Metadata {float visibility;float getTotalVisibility(){return visibility;}}
class Canvas {float clip;int save(){return 1;}void clipRect(int l,int t,int r,float b){clip=b;}void restoreToCount(int count){}}
class DrawingBase {protected void dispatchDraw(Canvas c){}}
class ActionBar extends View {static int getCurrentActionBarHeight(){return 168;}boolean getOccupyStatusBar(){return true;}}
class AndroidUtilities {static int statusBarHeight=120;static int dp(int value){return value*3;}}
class Fragment {View view=new View();View getFragmentView(){return view;}ActionBar getActionBar(){return new ActionBar();}}
public class NotificationInlineTest extends DrawingBase {
 final Fragment fragment=new Fragment();
 IntSupplier overlayAnchor;
 void updateCompactReservation(){} // Tested with the extracted compact-content methods separately.
 int getPaddingTop(){return 12;}int getPaddingBottom(){return 12;}
 final FrameLayout root = new FrameLayout(); View[] contents; int[] offsets; int reserved,anchorTop;
 FrameLayout.LayoutParams[] contentParams=new FrameLayout.LayoutParams[1];
 NotificationListInset[] listInsets=new NotificationListInset[1];
 float clipHeight=-1;boolean dirty=true;int invalidations;
 int physicalHeight;void invalidate(){dirty=true;invalidations++;}int getWidth(){return 1080;}int getHeight(){return physicalHeight;}
 float height; final Metadata metadata = new Metadata();
 float getAnimatedHeightWithPadding(){return height;} Metadata getMetadata(){return metadata;}
 boolean isOverlay(){return contents.length==0;}
 float getLayoutVisibility(){return metadata.visibility;}
 NotificationInlineTest(int top,int padding){
  View content = new View();content.parent=root;content.params.topMargin=top;content.params.bottomMargin=27;content.padding=padding;
  contents=new View[]{content};offsets=new int[1];
 }
 ${extract(inline, 'private void updateReservedHeight()')}
 ${extract(inline, 'private int overlayTop()')}
 ${extract(inline, 'public int getAvailableContentHeight(int viewportBottom)')}
 ${extract(inline, 'protected void dispatchDraw(Canvas canvas)')}
 void render(Canvas canvas){if(dirty){dispatchDraw(canvas);dirty=false;}}
 static int checks;
 static void check(boolean ok,String label){checks++;if(!ok)throw new AssertionError(label);}
 void frame(float fraction,int fullHeight){height=fraction*fullHeight;metadata.visibility=fraction;updateReservedHeight();}
 public static void main(String[] args){
  NotificationInlineTest cold=new NotificationInlineTest(0,0);Canvas displayList=new Canvas();
  for(int repeat=0;repeat<3;repeat++){
   for(float clip:new float[]{0,83.82817f,154.72517f,212.06029f,216,212.06029f,154.72517f,83.82817f,0}){
    cold.height=clip;cold.metadata.visibility=clip/216f;cold.updateReservedHeight();cold.render(displayList);
    check(displayList.clip==clip,"cached clip must follow first reveal, close and reopen");
    int invalidations=cold.invalidations;cold.updateReservedHeight();cold.render(displayList);
    check(cold.invalidations==invalidations,"stable geometry does not redraw continuously");
   }
  }
  cold.height=100.1f;cold.updateReservedHeight();cold.render(displayList);int layouts=cold.root.updates;
  cold.height=100.2f;cold.updateReservedHeight();cold.render(displayList);
  check(displayList.clip==100.2f&&cold.root.updates==layouts,"subpixel clip refresh does not require rounded inset change");
  cold.physicalHeight=260;cold.dirty=true;cold.render(displayList);
  check(displayList.clip==260,"expanded card is not cut to a trailing animated clip");
  NotificationInlineTest profile=new NotificationInlineTest(0,0);
  View profileContent=profile.contents[0];profile.contents=new View[0];profile.offsets=new int[0];profile.contentParams=new FrameLayout.LayoutParams[0];
  for(int cycle=0;cycle<4;cycle++)for(int i=0;i<=120;i++){
   float f=i<=60?i/60f:(120-i)/60f;profile.anchorTop=84;
   profile.frame(f,240);
   check(profileContent.params.topMargin==0&&profileContent.updates==0,"overlay must never move or resize the profile");
   check(profileContent.params.bottomMargin==27,"profile keeps bottom inset");
  }
  check(profile.fragment.view.invalidations>0,"overlay geometry invalidates profile glass source");
  check(profile.root.updates==0,"overlay animation cannot relayout the whole profile");
  profile.fragment.view.parent=profile.root;
  profile.overlayAnchor=()->288+678+24;
  check(profile.overlayTop()==990,"expanded profile notification sits below full header, not on avatar");
  profile.overlayAnchor=()->288+222+24;
  check(profile.overlayTop()==534,"collapsed profile follows its current header edge");
  for(int extra=222;extra<=678;extra++){
   final int header=extra;profile.overlayAnchor=()->288+header+24;
   check(profile.overlayTop()==288+extra+24,"header movement maps continuously without another animator");
  }
  profile.root.height=900;profile.overlayAnchor=()->1400;
  check(profile.overlayTop()+profile.getAvailableContentHeight(900)+24+192<=900,"landscape keeps compact notification above bottom controls");
  NotificationInlineTest rebound=new NotificationInlineTest(0,0);
  rebound.frame(1,72);rebound.contents[0].params=new FrameLayout.LayoutParams();rebound.contents[0].params.topMargin=13;rebound.frame(0,72);
  check(rebound.contents[0].params.topMargin==13,"pager rebind with fresh parameters must not subtract old inset");
  for(int top:new int[]{0,12,56,80})for(int padding:new int[]{0,24,80})for(int anchor:new int[]{0,56,80}){
   NotificationInlineTest p=new NotificationInlineTest(top,padding);p.anchorTop=anchor;
   for(int cycle=0;cycle<5;cycle++){
    for(int i=0;i<=120;i++){
     float f=i<=60?i/60f:(120-i)/60f;p.frame(f,72);
     int expected=top+Math.round(f*72)+Math.round(Math.max(0,anchor-top-padding)*f);
     check(p.contents[0].params.topMargin==expected,"animated inset preserves original top");
     int updates=p.root.updates;p.updateReservedHeight();p.updateReservedHeight();
     check(p.root.updates==updates,"repeated callbacks before layout cannot accumulate inset");
     check(p.contents[0].params.bottomMargin==27,"bottom controls remain anchored");
    }
    check(p.offsets[0]==0&&p.contents[0].params.topMargin==top,"complete collapse restores content");
   }
   p.frame(1,72);p.frame(1,240);check(p.contents[0].params.topMargin==top+240+Math.max(0,anchor-top-padding),"expanded content reserves full height");
   p.contents[0].params.topMargin+=10;p.frame(0,72);check(p.contents[0].params.topMargin==top+10,"external margin changes retained");
   p.contents[0].parent=null;int updates=p.contents[0].updates;p.frame(1,72);check(p.contents[0].updates==updates,"detached content untouched");
  }
  System.out.println("PASS: "+checks+" actual-method inline inset checks; repetition, expansion, existing margins, bottom anchoring and detach");
 }
}`;
const nativeJava = `
import java.util.*;
class NativeBase {
 interface IndependentPanel {boolean isDirectResize();}
 static final int VISIBLE=0;
 ArrayList<View> children=new ArrayList<>();
 int getChildCount(){return children.size();} View getChildAt(int i){return children.get(i);}
 protected void onLayout(boolean c,int l,int t,int r,int b){}
 static class View {int width=300,height=64,visibility;int getMeasuredWidth(){return width;}int getMeasuredHeight(){return height;}int getVisibility(){return visibility;}}
 static class Slot extends View implements IndependentPanel {boolean direct=true;public boolean isDirectResize(){return direct;}}
}
public class NotificationNativeLayoutTest extends NativeBase {
 static class Holder {View view;boolean isVisible=true,hasInAnimator,trackSize;int priority,order,width,height;Holder(View v){view=v;}}
 static class Animator {int resets,measures,height;boolean animating,lastMeasureAnimated;List<Holder> current;
  void reset(List<Holder> items,boolean animate){resets++;current=new ArrayList<>(items);height=items.stream().mapToInt(h->h.view.height).sum();}
  boolean isAnimating(){return animating;}
  void measure(boolean animated){measures++;lastMeasureAnimated=animated;height=current.stream().mapToInt(h->h.view.height).sum();}
  void measureImpl(boolean animated){measure(animated);}
 }
 HashMap<View,Holder> viewHolders=new HashMap<>();ArrayList<Holder> visibleHolders=new ArrayList<>(),trackedLayout;
 Animator listAnimator=new Animator();boolean skipNextAnimation;
 static final Comparator<Holder> comparator=Comparator.comparingInt((Holder h)->h.priority).thenComparingInt(h->h.order);
 void checkViewsVisibility(){}
 static void tracePanelMotion(int stage,NotificationNativeLayoutTest panel){}
 static void tracePanelMotion(int stage,NotificationNativeLayoutTest panel,int reasons){}
 float getSharedBackgroundOffset(){return visibleHolders.stream().anyMatch(h->!(h.view instanceof IndependentPanel))?64:-1;}
 ${extract(layout, 'protected void onLayout(boolean changed, int l, int t, int r, int b)').replaceAll('app.nimarkogram.messenger.notifications.NimarkoInAppNotifications.tracePanelMotion', 'tracePanelMotion')}
 Holder add(boolean tracked){View v=tracked?new Slot():new View();children.add(v);Holder h=new Holder(v);h.trackSize=tracked;viewHolders.put(v,h);if(tracked)trackedLayout=new ArrayList<>();return h;}
 void frame(){onLayout(false,0,0,300,500);}
 static int checks;static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 public static void main(String[] args){
  NotificationNativeLayoutTest p=new NotificationNativeLayoutTest();Holder slot=p.add(true),pinned=p.add(false);p.frame();
  int resets=p.listAnimator.resets;p.frame();check(p.listAnimator.resets==resets,"stable holder identities skip reset without comparing Entry against Holder");
  for(int i=64;i<=240;i++){slot.view.height=i;p.frame();check(p.listAnimator.height==i+64,"drag geometry tracks measured height");check(!p.listAnimator.lastMeasureAnimated,"settled panel tracks gesture directly");}
  check(p.listAnimator.resets==resets,"drag does not restart list insertion");
  p.listAnimator.animating=true;slot.view.height=201;p.frame();check(p.listAnimator.lastMeasureAnimated,"gesture preserves an existing shared panel animation");
  NotificationNativeLayoutTest single=new NotificationNativeLayoutTest();Holder only=single.add(true);single.frame();single.listAnimator.animating=true;
  only.view.height=120;single.frame();check(single.listAnimator.lastMeasureAnimated&&single.listAnimator.animating,"single card preserves its partial entrance height on an early gesture");
  single.listAnimator.animating=false;only.view.height=150;single.frame();check(!single.listAnimator.lastMeasureAnimated,"completed entrance follows the finger directly");
  ((Slot)slot.view).direct=false;slot.view.height=200;p.frame();check(p.listAnimator.lastMeasureAnimated,"new text height animates independently from direct gesture");
  pinned.view.height=100;p.frame();check(p.listAnimator.resets==++resets,"native pinned panel resizes retain reset path");
  slot.isVisible=false;p.frame();check(p.listAnimator.height==100&&p.listAnimator.resets==++resets,"removal updates native stack");
  slot.isVisible=true;p.frame();check(p.listAnimator.height==300&&p.listAnimator.resets==++resets,"replacement reappears");
  p.skipNextAnimation=true;p.frame();check(p.listAnimator.resets==++resets,"unanimated detach applies visibility");
  NotificationNativeLayoutTest original=new NotificationNativeLayoutTest();original.add(false);original.frame();original.frame();
  check(original.listAnimator.resets==2,"panels without notification retain original behavior");
  System.out.println("PASS: "+checks+" extracted native layout checks; holder identity, expansion, sibling resizing, removal and untracked panels");
 }
}`;
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-notification-inline-'));
try {
    for(const [name,code] of [['NotificationInlineTest',java],['NotificationNativeLayoutTest',nativeJava]]) {
        fs.writeFileSync(path.join(dir, name+'.java'), code);
        const compile = cp.spawnSync('javac', [path.join(dir, name+'.java')], {encoding:'utf8'});
        assert.equal(compile.status, 0, compile.stderr);
        const run = cp.spawnSync('java', ['-cp', dir, name], {encoding:'utf8'});
        assert.equal(run.status, 0, run.stdout+run.stderr);
        process.stdout.write(run.stdout);
    }
    const broken=java.replace('            invalidate();','');
    assert.notEqual(broken,java);
    fs.writeFileSync(path.join(dir,'NotificationInlineTest.java'),broken);
    const compile=cp.spawnSync('javac',[path.join(dir,'NotificationInlineTest.java')],{encoding:'utf8'});
    assert.equal(compile.status,0,compile.stderr);
    const run=cp.spawnSync('java',['-cp',dir,'NotificationInlineTest'],{encoding:'utf8'});
    assert.notEqual(run.status,0,'missing clip invalidation must fail the cold-start regression');
    assert.match(run.stderr,/cached clip must follow first reveal/);
    console.log('PASS: missing-invalidation negative control reproduces a stale cold-start clip in the display-list model');
    const snapping = nativeJava.replace('if (directResize && !listAnimator.isAnimating())',
        'if (directResize && (!listAnimator.isAnimating() || getSharedBackgroundOffset() < 0))');
    assert.notEqual(snapping, nativeJava);
    fs.writeFileSync(path.join(dir, 'NotificationNativeLayoutTest.java'), snapping);
    const snappingCompile = cp.spawnSync('javac', [path.join(dir, 'NotificationNativeLayoutTest.java')], {encoding:'utf8'});
    assert.equal(snappingCompile.status, 0, snappingCompile.stderr);
    const snappingRun = cp.spawnSync('java', ['-cp', dir, 'NotificationNativeLayoutTest'], {encoding:'utf8'});
    assert.notEqual(snappingRun.status, 0);
    assert.match(snappingRun.stderr, /preserves its partial entrance height/);
    console.log('PASS: early-gesture negative control rejects forced full panel height during entrance');
    console.log('PASS: native panel host, targeted size tracking, deferred detach cleanup and touch gating contracts. Device rendering not tested.');
} finally {fs.rmSync(dir, {recursive:true,force:true});}
