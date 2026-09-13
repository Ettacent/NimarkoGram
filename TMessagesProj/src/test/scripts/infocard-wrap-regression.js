const fs = require('node:fs');
const path = require('node:path');
const cp = require('node:child_process');
const os = require('node:os');
const assert = require('node:assert/strict');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/java/app/nimarkogram/messenger/infocards/InfoCardStripView.java'), 'utf8');
function method(signature) {
    const start = source.indexOf(signature);
    assert(start >= 0, signature);
    const tokens = /\/\/[^\n]*|\/\*[\s\S]*?\*\/|"(?:\\.|[^"\\])*"|[{}]/g;
    tokens.lastIndex = source.indexOf('{', start);
    let depth = 0;
    for (let m; (m = tokens.exec(source));) {
        if (m[0] === '{') depth++;
        if (m[0] === '}' && --depth === 0) return source.slice(start, tokens.lastIndex);
    }
    throw Error(signature);
}
const java = `import java.util.*;
public class WrapTest {
 static int checks;
 static void check(boolean b){checks++;if(!b)throw new AssertionError("check "+checks);}
 static class Animator {}
 static class AnimatorListenerAdapter {
  public void onAnimationCancel(Animator a){}public void onAnimationEnd(Animator a){}
 }
 interface Update {void run(ValueAnimator a);}
 static class ValueAnimator extends Animator {
  float from,to,value;long duration;boolean cancelled,ended;Update update;AnimatorListenerAdapter listener;
  static ValueAnimator ofFloat(float from,float to){ValueAnimator a=new ValueAnimator();a.from=from;a.to=to;a.value=from;return a;}
  void setDuration(long d){duration=d;}void setInterpolator(Object o){}
  void addUpdateListener(Update u){update=u;}void addListener(AnimatorListenerAdapter l){listener=l;}
  Object getAnimatedValue(){return value;}
  void start(){}
  void frame(float p){value=from+(to-from)*p;update.run(this);}
  void end(){ended=true;frame(1);listener.onAnimationEnd(this);}
  void cancel(){cancelled=true;listener.onAnimationCancel(this);listener.onAnimationEnd(this);}
 }
 static class android {static class view {static class animation {
  static class OvershootInterpolator {OvershootInterpolator(float f){}}
 }}}
 static class CubicBezierInterpolator {static Object EASE_OUT=new Object(),EASE_OUT_QUINT=new Object();}
 static class InfoCardsConfig {
  static boolean infinite=true;static int selected;
  static boolean isInfiniteScrolling(){return infinite;}
  static void setLastActiveCardId(int id){selected=id;}
 }
 static class NotificationCenter {
  static int infoCardsActiveCardChanged;static NotificationCenter getGlobalInstance(){return new NotificationCenter();}
  void postNotificationName(int id){}
 }
 static class BaseInfoCard {
  int id;BaseInfoCard(int id){this.id=id;}int getCardId(){return id;}
  void onCardUnselected(){}
 }
 static class Carousel {
  ArrayList<BaseInfoCard> pills=new ArrayList<>();int currentIndex,incomingIndex=-1;
  float dragProgress;boolean dragUp=true,dragging,inlineFolderStyle=true,settlingToNext;ValueAnimator animator;
  Carousel(int n){for(int i=0;i<n;i++)pills.add(new BaseInfoCard(i));}
  BaseInfoCard current(){return pills.get(currentIndex);}float dragHeight(){return 84;}
  void applyDrag(int index,float p,boolean up,float h){dragProgress=Math.max(0,Math.min(1,p));}
  void applyResting(){applyResting(true);}void applyResting(boolean b){incomingIndex=-1;}
  boolean reconcilePendingActiveCard(){return false;}
  ${method('private int neighbor(boolean up)')}
  ${method('private void animateCommit(')}
  ${method('private void animateSnapBack(')}
  ${method('private void cancelAnim()')}
  ${method('private void cancelAnimResume()')}
 }
 public static void main(String[] args){
  for(int n=2;n<=7;n++)for(boolean up:new boolean[]{true,false}){
   Carousel c=new Carousel(n);
   for(int i=0;i<150;i++){
    int old=c.currentIndex,next=(old+(up?1:n-1))%n;
    check(c.neighbor(up)==next);
    c.incomingIndex=next;c.dragUp=up;c.dragProgress=1;c.animateCommit(next);
    check(c.animator.duration==0);
    c.cancelAnimResume();
    check(c.currentIndex==next&&c.animator==null&&!c.dragging&&c.dragProgress==0);
    check(InfoCardsConfig.selected==next);
   }
  }
  Carousel c=new Carousel(4);c.currentIndex=2;c.incomingIndex=1;c.dragUp=false;c.dragProgress=.4f;
  c.animateCommit(1);check(c.animator.duration==198);ValueAnimator first=c.animator;
  first.frame(.5f);float visible=c.dragProgress;c.cancelAnimResume();
  check(c.currentIndex==2&&c.dragProgress==visible&&c.dragging&&first.cancelled);
  c.animateCommit(1);check(c.animator.duration==Math.round(330*(1-visible)));
  first.listener.onAnimationEnd(first);check(c.currentIndex==2);
  c.animator.frame(.995f);c.cancelAnimResume();check(c.currentIndex==1&&!c.dragging);
  c=new Carousel(4);c.incomingIndex=1;c.dragProgress=1;c.animateSnapBack(1);
  check(c.animator.duration==200);c.cancelAnimResume();
  check(c.currentIndex==0&&c.dragging&&c.dragProgress==1);
  c.dragProgress=.002f;c.animateSnapBack(1);c.cancelAnimResume();
  check(c.currentIndex==0&&!c.dragging&&c.animator==null);
  c=new Carousel(4);c.inlineFolderStyle=false;c.incomingIndex=1;c.dragProgress=.4f;c.animateCommit(1);
  check(c.animator.duration==330);c.cancelAnimResume();check(c.currentIndex==1);
  InfoCardsConfig.infinite=false;c=new Carousel(4);
  check(c.neighbor(false)==-1);c.currentIndex=3;check(c.neighbor(true)==-1);
  c.currentIndex=1;check(c.neighbor(false)==0&&c.neighbor(true)==2);
  c=new Carousel(0);check(c.neighbor(true)==-1);
  InfoCardsConfig.infinite=true;c=new Carousel(1);check(c.neighbor(true)==0&&c.neighbor(false)==0);
  System.out.println("PASS: "+checks+" checks: repeated wrapping, early re-touch, partial resume, cancelled callback, snap-back and finite edges");
 }
}`;
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-wrap-'));
try {
    function run(code) {
        fs.writeFileSync(path.join(tmp, 'WrapTest.java'), code);
        cp.execFileSync('javac', ['WrapTest.java'], {cwd: tmp, stdio: 'pipe'});
        return cp.execFileSync('java', ['WrapTest'], {cwd: tmp, encoding: 'utf8', stdio: 'pipe'});
    }
    process.stdout.write(run(java));
    assert.throws(() => run(java.replace('Math.abs(target - dragProgress) * dragHeight() * 1.35f <= 1f', 'false')));
    assert.throws(() => run(java.replace('Math.round(330 * (1f - dragProgress))', '330')));
    console.log('PASS: old endpoint handling and fixed settle duration fail negative controls');
} finally {
    fs.rmSync(tmp, {recursive: true, force: true});
}
