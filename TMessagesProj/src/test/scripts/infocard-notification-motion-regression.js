const fs = require('node:fs');
const path = require('node:path');
const cp = require('node:child_process');
const os = require('node:os');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../main/java');
const cards = fs.readFileSync(path.join(root, 'app/nimarkogram/messenger/infocards/InfoCardStripView.java'), 'utf8');
const notifications = fs.readFileSync(path.join(root, 'app/nimarkogram/messenger/notifications/NimarkoInAppNotifications.java'), 'utf8');
const tabs = fs.readFileSync(path.join(root, 'org/telegram/ui/Components/FilterTabsView.java'), 'utf8');
const baseCard = fs.readFileSync(path.join(root, 'app/nimarkogram/messenger/infocards/BaseInfoCard.java'), 'utf8');
function method(source, marker) {
    const start = source.indexOf(marker);
    assert(start >= 0, marker);
    const tokens = /\/\/[^\n]*|\/\*[\s\S]*?\*\/|"(?:\\.|[^"\\])*"|[{}]/g;
    tokens.lastIndex = source.indexOf('{', start);
    let depth = 0;
    for (let match; (match = tokens.exec(source));) {
        if (match[0] === '{') depth++;
        if (match[0] === '}' && --depth === 0) return source.slice(start, tokens.lastIndex);
    }
    throw Error(marker);
}
const java = `import java.util.*;
public class MotionTest {
 static int assertions;
 static void check(boolean value) { assertions++; if (!value) throw new AssertionError(assertions); }
 static class BaseInfoCard {int width;BaseInfoCard(int w){width=w;}int getMeasuredWidth(){return width;}}
 static class Widths {
  ArrayList<BaseInfoCard> pills=new ArrayList<>();int incomingIndex=-1;float dragProgress;
  BaseInfoCard current(){return pills.isEmpty()?null:pills.get(0);}
  ${method(cards, 'private int carouselWidth()')}
 }
 static class Animator {}
 static class AnimatorListenerAdapter {public void onAnimationEnd(Animator animation){}}
 // Deterministic animator transport, not an Android rendering simulation. cancel()
 // delivers onAnimationEnd too: production must clear ownership before cancelling.
 static class ValueAnimator extends Animator {
  boolean cancelled,ended;int starts;long duration;float from,to,value,fraction;
  ArrayList<java.util.function.Consumer<ValueAnimator>> updates=new ArrayList<>();
  ArrayList<AnimatorListenerAdapter> listeners=new ArrayList<>();
  static ValueAnimator ofFloat(float from,float to){ValueAnimator a=new ValueAnimator();a.from=from;a.to=to;a.value=from;return a;}
  void setDuration(long d){duration=d;}void setInterpolator(Interpolator i){}
  void addUpdateListener(java.util.function.Consumer<ValueAnimator> l){updates.add(l);}
  void addListener(AnimatorListenerAdapter l){listeners.add(l);}void start(){starts++;}
  Object getAnimatedValue(){return value;}float getAnimatedFraction(){return fraction;}
  void frame(float fraction){this.fraction=fraction;value=from+(to-from)*fraction;for(var l:updates)l.accept(this);}
  void notifyEnd(){for(var l:listeners)l.onAnimationEnd(this);}
  void cancel(){cancelled=true;notifyEnd();}void end(){ended=true;frame(1);notifyEnd();}
 }
 static class Carousel {
  ValueAnimator animator;boolean inlineFolderStyle,dragging,settlingToNext=true;float dragProgress=.5f;
  float dragHeight(){return 84;}
  void cancelAnim(){animator.cancel();animator=null;}
  ${method(cards, 'private void cancelAnimResume()')}
 }
 static class Animation {
  Runnable end; int starts; long duration;
  void cancel(){end=null;} Animation alpha(float a){return this;} Animation scaleX(float a){return this;}
  Animation scaleY(float a){return this;} Animation translationY(float a){return this;}
  Animation setDuration(long d){duration=d;return this;} Animation setInterpolator(Object i){return this;}
  Animation withEndAction(Runnable r){end=r;return this;} void start(){starts++;}
  void finish(){Runnable r=end;end=null;if(r!=null)r.run();}
 }
 interface Interpolator {float getInterpolation(float t);}
 static class CubicBezierInterpolator {static final Interpolator EASE_BOTH=t->t,EASE_OUT=t->t,EASE_OUT_QUINT=t->t;}
 static class View {
  static final int IMPORTANT_FOR_ACCESSIBILITY_NO=2,IMPORTANT_FOR_ACCESSIBILITY_AUTO=0;
  String value;float alpha=1;void setText(String s){value=s;}String getText(){return value;}
  void setAlpha(float a){alpha=a;}float getAlpha(){return alpha;}void setImportantForAccessibility(int mode){}
 }
 static class Slot {boolean directResize;int layouts;void requestLayout(){layouts++;}}
 static class TextUtils {static boolean equals(CharSequence a,CharSequence b){return Objects.equals(a,b);}}
 static class UserConfig {static int selectedAccount;}
 static class Banner {
  int account;boolean sample;
  boolean gestureFramePending;Runnable gestureFrame;void removeCallbacks(Runnable r){}
  static Banner banner; boolean closing,opening,touching,dragged,expanded; int opens;
  int height=80,expandedHeight=160,collapsedHeight=80,layouts;
  float pullOffset,translationY,expansion,releaseVelocity;boolean preview=true;Slot slot=new Slot();
  ValueAnimator pullAnimator,expansionAnimator,contentAnimator;boolean contentFadeOut;
  String pendingName,pendingMessage;View title=new View(),body=new View(),expandedBody=new View(),text=new View(),bodies=new View();
  float alpha=1;float getAlpha(){return alpha;}void setAlpha(float a){alpha=a;}
  int getHeight(){return height;}void setTranslationY(float y){translationY=y;}void requestLayout(){layouts++;}
  Animation animator=new Animation(); Animation animate(){return animator;} int dp(int n){return n;}
  void openChat(){opens++;}
  void setPressed(boolean p){}
  ${method(notifications, 'void fadeText(')}
  ${method(notifications, 'void cancelContentTransition()')}
  ${method(notifications, 'void cancelExpansion()')}
  ${method(notifications, 'void setExpansion(')}
  ${method(notifications, 'void settleExpansion(')}
  ${method(notifications, 'void setPullOffset(')}
  ${method(notifications, 'void settlePull(')}
  ${method(notifications, 'void settleGeometry(')}
  ${method(notifications, 'void animateOpenChat()')}
  ${method(notifications, 'void pauseInteraction()')}
 }
 static class LayoutParent { protected void onLayout(boolean c,int l,int t,int r,int b){} }
 static class AndroidUtilities {static void cancelRunOnUIThread(Runnable r){} static int dp(int n){return n;} }
 static class StyleCard {
  boolean inlineFolderStyle;int layouts;
  static class Text {int size=13;void setTextSize(int s){size=s;}}
  Text textView=new Text();
  void requestLayout(){layouts++;}
  ${method(baseCard, 'public void setInlineFolderStyle(boolean inline)')}
 }
 static class Delegate {int completions;void onPageScrolled(float p){completions++;}}
 static class Tabs extends LayoutParent {
  int resizeReferenceWidth,previousResizeReferenceWidth,prevLayoutWidth,scrollingToChild;
  boolean animatingIndicator=true; Delegate delegate=new Delegate();Runnable animationRunnable;
  void setEnabled(boolean e){}
  ${method(tabs, 'public void setResizeReferenceWidth(int width)')}
  ${method(tabs, 'protected void onLayout(boolean changed, int l, int t, int r, int b)')}
 }
 public static void main(String[] args){
  Widths sizes=new Widths();check(sizes.carouselWidth()==0);
  for(int a:new int[]{48,72,96,144})for(int b:new int[]{48,72,96,144}){
   sizes.pills.clear();sizes.pills.add(new BaseInfoCard(a));sizes.pills.add(new BaseInfoCard(b));sizes.incomingIndex=1;
   for(int step=0;step<=120;step++){
    sizes.dragProgress=step/120f;check(sizes.carouselWidth()==Math.round(a+(b-a)*sizes.dragProgress));
   }
   for(int step=120;step>=0;step--){sizes.dragProgress=step/120f;check(sizes.carouselWidth()==Math.round(a+(b-a)*sizes.dragProgress));}
   sizes.incomingIndex=-1;check(sizes.carouselWidth()==a);
  }
  StyleCard style=new StyleCard();style.setInlineFolderStyle(true);
  check(style.inlineFolderStyle&&style.textView.size==14&&style.layouts==1);
  style.setInlineFolderStyle(true);check(style.layouts==1);
  style.setInlineFolderStyle(false);check(!style.inlineFolderStyle&&style.textView.size==13&&style.layouts==2);
  Carousel c=new Carousel();ValueAnimator animation=new ValueAnimator();c.animator=animation;c.inlineFolderStyle=true;
  c.cancelAnimResume();check(c.dragging&&animation.cancelled&&!animation.ended&&c.animator==null);
  c=new Carousel();animation=new ValueAnimator();c.animator=animation;c.cancelAnimResume();
  check(animation.ended&&!animation.cancelled&&!c.dragging);
  Banner n;
  n=new Banner();Banner.banner=n;n.account=1;n.animateOpenChat();
  check(n.opens==1&&n.opening&&n.touching&&n.animator.starts==0&&n.alpha==1&&n.slot.layouts==0);
  n.animateOpenChat();check(n.opens==1);
  for(int height:new int[]{64,160,420}){
   n=new Banner();Banner.banner=n;n.height=height;n.animateOpenChat();
   check(n.opening&&n.touching&&n.opens==0&&n.animator.duration==160&&n.pullAnimator==null);
   n.animateOpenChat();check(n.animator.starts==1&&n.pullAnimator==null);
   check(n.slot.layouts==0&&n.pullOffset==0&&n.translationY==0);
   n.animator.finish();check(n.opens==1);n.animator.finish();check(n.opens==1);
  }
  n=new Banner();Banner.banner=n;n.animateOpenChat();Banner.banner=null;
  n.animator.finish();check(n.opens==0);
  n=new Banner();Banner.banner=n;n.closing=true;n.animateOpenChat();check(n.animator.starts==0);
  n=new Banner();Banner.banner=new Banner();n.animateOpenChat();check(n.animator.starts==0);
  n=new Banner();Banner.banner=n;n.animateOpenChat();Runnable oldTap=n.animator.end;
  n.pauseInteraction();ValueAnimator restore=n.pullAnimator;
  check(!n.opening&&!n.touching&&n.opens==0&&restore!=null);
  oldTap.run();n.animator.finish();restore.end();check(n.opens==0&&n.pullOffset==0);
  n=new Banner();n.settlePull(-40,210,null);ValueAnimator pull=n.pullAnimator;pull.frame(.5f);
  n.settlePull(0,210,null);ValueAnimator replacement=n.pullAnimator;
  check(pull.cancelled&&replacement!=pull&&n.opens==0);
  pull.frame(1);pull.notifyEnd();check(n.pullOffset==-20&&n.pullAnimator==replacement);
  replacement.end();check(n.pullAnimator==null&&n.pullOffset==0);
  // Tapping mid-fade cancels expansion, not the text transition: forcing text
  // alpha back to 1 here would flash before the parent's exit animation finishes.
  n=new Banner();Banner.banner=n;n.title.value="old";n.body.value="old body";
  n.pendingName="updated";n.pendingMessage="body";n.fadeText(true);
  ValueAnimator content=n.contentAnimator;content.frame(.8f);float fadedAlpha=n.bodies.alpha;
  n.settleExpansion(true);ValueAnimator expansion=n.pullAnimator;
  n.animateOpenChat();check(!content.cancelled&&expansion.cancelled&&n.contentAnimator==content&&n.pullAnimator!=expansion);
  check(n.bodies.alpha==fadedAlpha&&"old".equals(n.title.value)&&"updated".equals(n.pendingName)&&n.opens==0);
  check(n.opens==0&&n.contentAnimator==content);
  content.end();ValueAnimator fadeIn=n.contentAnimator;
  check(fadeIn!=null&&fadeIn!=content&&fadeIn.duration==160&&n.bodies.alpha==0);
  check(n.pendingName==null&&n.pendingMessage==null&&"updated".equals(n.title.value));
  check("body".equals(n.body.value)&&"body".equals(n.expandedBody.value)&&n.opens==0);
  fadeIn.frame(.5f);n.pauseInteraction();
  check(!n.opening&&!n.touching&&n.contentAnimator==fadeIn&&!fadeIn.cancelled&&n.bodies.alpha==.5f);
  fadeIn.end();check(n.contentAnimator==null&&n.bodies.alpha==1&&n.opens==0);
  n.pullAnimator.end();check(n.pullOffset==0&&n.opens==0);
  // Explicit removal/detach cleanup still retires content and pending text.
  n=new Banner();n.title.value="old";n.pendingName="late";n.pendingMessage="late";
  n.fadeText(true);content=n.contentAnimator;content.frame(.5f);n.closing=true;
  n.cancelContentTransition();check(content.cancelled&&n.contentAnimator==null);
  check("old".equals(n.title.value)&&n.pendingName==null&&n.pendingMessage==null&&n.bodies.alpha==.5f);
  content.frame(1);content.notifyEnd();check(n.contentAnimator==null&&n.bodies.alpha==.5f&&"old".equals(n.title.value));
  n=new Banner();n.pendingName="updated";n.pendingMessage="body";n.fadeText(true);
  content=n.contentAnimator;content.frame(.5f);n.cancelContentTransition();
  check(content.cancelled&&n.contentAnimator==null&&n.bodies.alpha==1&&"updated".equals(n.title.value));
  check(n.pendingName==null&&n.pendingMessage==null&&"body".equals(n.expandedBody.value));
  Tabs tab=new Tabs();tab.setResizeReferenceWidth(400);tab.onLayout(true,0,0,344,50);
  tab.animatingIndicator=true;tab.delegate.completions=0;tab.scrollingToChild=3;
  for(int width=343;width>=296;width--){tab.onLayout(true,0,0,width,50);check(tab.animatingIndicator&&tab.delegate.completions==0&&tab.scrollingToChild==3);}
  tab.animatingIndicator=false;
  for(int width=296;width<=344;width++){tab.onLayout(true,0,0,width,50);check(tab.delegate.completions==0);}
  tab.animatingIndicator=true;
  tab.setResizeReferenceWidth(800);tab.onLayout(true,0,0,696,50);check(!tab.animatingIndicator&&tab.delegate.completions==1);
  tab.setResizeReferenceWidth(0);tab.animatingIndicator=true;tab.onLayout(true,0,0,680,50);check(!tab.animatingIndicator);
  System.out.println("PASS: "+assertions+" motion assertions: stable inline layout, reversal, tap lifecycle, folder scroll isolation");
 }
}`;
assert.doesNotMatch(method(cards, 'private void applyDrag('), /\.measure\(/);
assert.match(method(cards, 'private void applyDrag('), /inlineFolderStyle && carouselWidth\(\) != getMeasuredWidth\(\)/);
assert.match(method(cards, 'protected void onMeasure('), /setMeasuredDimension\(Math.min\(MeasureSpec.getSize\(widthMeasureSpec\), carouselWidth\(\)\)/);
assert.doesNotMatch(method(baseCard, 'public void setInlineFolderStyle(boolean inline)'), /setLayoutParams|MATCH_PARENT|weight/);
assert.match(method(baseCard, 'private void onAnimatedTextWidthUpdated()'), /textView.requestLayout\(\)/);
assert.match(cards, /boolean commit = ev.getActionMasked\(\) == MotionEvent.ACTION_UP/);
assert.match(cards, /potentialTap = !dragging/);
assert.match(cards, /downY \+= \(dragUp \? 1f : -1f\) \* dragProgress \* h/);
assert.match(notifications, /LayoutHelper.createFrame\(48, 48, Gravity.END/);
assert.match(notifications, /setForeground\(Theme.createSelectorDrawable/);
assert.match(notifications, /drawableHotspotChanged\(e.getX\(\), e.getY\(\)\)/);
assert.match(notifications, /banner != next \|\| next.opening \|\| next.closing/);
assert.match(method(notifications, 'void animateOpenChat()'), /withEndAction\(\(\) -> \{ if \(banner == this && opening\) openChat\(\)/);
assert.doesNotMatch(method(notifications, 'void animateOpenChat()'), /\.translationY\(|settlePull\(/);
for(const marker of ['private static void remove(Banner old)', 'protected void onDetachedFromWindow()']) {
 const cleanup=method(notifications,marker);
 assert.match(cleanup,/cancelExpansion\(\)/);
 assert.match(cleanup,/cancelContentTransition\(\)/);
 assert.match(cleanup,/animate\(\)\.cancel\(\)/);
}
assert.match(baseCard, /if \(ov != 0\) fg = ov/);
assert.match(baseCard, /if \(colorMode == InfoCardsConfig.COLOR_MODE_THEME\) return/);
for (const signature of ['private int currentContentColor()', 'protected void applyColorMode()', 'protected void setTextColor(int color)']) {
 assert(!method(baseCard, signature).includes('inlineFolderStyle'), 'inline geometry must preserve configured colors');
}
const tmp=fs.mkdtempSync(path.join(os.tmpdir(),'nimarko-motion-'));
try {
 fs.writeFileSync(path.join(tmp,'MotionTest.java'),java);
 cp.execFileSync('javac',['MotionTest.java'],{cwd:tmp,stdio:'pipe'});
 process.stdout.write(cp.execFileSync('java',['MotionTest'],{cwd:tmp,encoding:'utf8'}));
 fs.writeFileSync(path.join(tmp,'MotionTest.java'),java.replace('animatingIndicator && hostResized','animatingIndicator'));
 cp.execFileSync('javac',['MotionTest.java'],{cwd:tmp,stdio:'pipe'});
 assert.throws(()=>cp.execFileSync('java',['MotionTest'],{cwd:tmp,stdio:'pipe'}), 'old resize behavior must interrupt folder transition');
 console.log('PASS: previous resize callback fails the same folder-transition test');
 const stalePull=java.replace('if (pullAnimator != animation) return;', '/* missing animator ownership guard */');
 assert.notEqual(stalePull,java,'negative control must replace actual settlePull guard');
 fs.writeFileSync(path.join(tmp,'MotionTest.java'),stalePull);
 cp.execFileSync('javac',['MotionTest.java'],{cwd:tmp,stdio:'pipe'});
 assert.throws(()=>cp.execFileSync('java',['MotionTest'],{cwd:tmp,stdio:'pipe'}), 'stale pull completion must fail navigation/cancel assertions');
 console.log('PASS: missing pull ownership guard fails delayed-navigation/cancellation test');
} finally {fs.rmSync(tmp,{recursive:true,force:true});}
