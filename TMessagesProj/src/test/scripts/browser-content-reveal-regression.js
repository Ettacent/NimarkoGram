const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const cp = require('node:child_process');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../main/java/org/telegram/ui');
const source = fs.readFileSync(path.join(root, 'web/BrowserContentReveal.java'), 'utf8')
    .replace(/^package .*;\s*/m, '').replace(/^import .*;\s*/gm, '');
const container = fs.readFileSync(path.join(root, 'web/BotWebViewContainer.java'), 'utf8');
const article = fs.readFileSync(path.join(root, 'ArticleViewer.java'), 'utf8');
assert(container.includes('browserContentReveal = isBot ? null : new BrowserContentReveal(this)'));
assert(container.includes('browserContentReveal.prepare(view, false)'));
assert(container.includes('TextUtils.equals(currentUrl, url)'));
assert(container.includes('browserContentReveal.ready(this)'));
assert(container.includes('browserContentReveal.clear()'));
assert(container.includes('restoringWebView && (webView.isPageLoaded || webView.browserContentReady)'));
assert(container.includes('browserContentReady = false;'));
assert(article.includes('webViewContainer.cancelBrowserContentReveal()'));
assert(article.includes('webViewContainer.resumeBrowserContentReveal()'));
assert.equal(article.split('page.webViewContainer.holdBrowserContentReveal(false)').length - 1, 2);
const java = `import java.util.*;
class Build {static class VERSION {static int SDK_INT=36;}static class VERSION_CODES {static int M=23;}}
class CubicBezierInterpolator {static Object EASE_BOTH=new Object();}
class View {
 float alpha=1;boolean attached=true;int animations;long duration;
 ArrayList<Runnable> frames=new ArrayList<>(),delayed=new ArrayList<>();
 boolean isAttachedToWindow(){return attached;}void setAlpha(float a){alpha=a;}
 void postDelayed(Runnable r,long delay){delayed.add(r);}void postOnAnimation(Runnable r){frames.add(r);}
 void removeCallbacks(Runnable r){frames.removeIf(x->x==r);delayed.removeIf(x->x==r);}
 Animator animator=new Animator();Animator animate(){return animator;}
 class Animator {
  float end=1;void cancel(){}Animator alpha(float a){end=a;return this;}
  Animator setDuration(long d){duration=d;return this;}Animator setInterpolator(Object i){return this;}
  Animator withLayer(){return this;}void start(){alpha=end;animations++;}
 }
 void frame(){ArrayList<Runnable> list=new ArrayList<>(frames);frames.clear();for(Runnable r:list)r.run();}
 void timeout(){ArrayList<Runnable> list=new ArrayList<>(delayed);delayed.clear();for(Runnable r:list)r.run();}
}
class WebView extends View {
 static abstract class VisualStateCallback {public abstract void onComplete(long id);}
 ArrayList<Runnable> visual=new ArrayList<>();boolean broken;
 void postVisualStateCallback(long id,VisualStateCallback c){if(broken)throw new IllegalStateException();visual.add(()->c.onComplete(id));}
 void commit(){ArrayList<Runnable> list=new ArrayList<>(visual);visual.clear();for(Runnable r:list)r.run();}
}
${source}
public class BrowserRevealTest {
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 public static void main(String[] args){
  View target=new View();WebView web=new WebView();BrowserContentReveal r=new BrowserContentReveal(target);
  r.prepare(web,false);check(target.alpha==0&&target.animations==0,"hide initial incomplete page");
  r.hold(true);r.ready(web);check(web.visual.isEmpty(),"wait until sheet opens");
  r.hold(false);check(web.visual.size()==1,"request frame readiness");
  r.ready(web);check(web.visual.size()==1,"no duplicate readiness request");
  web.commit();check(target.alpha==0,"wait for next frame");target.frame();
  check(target.alpha==1&&target.animations==1&&target.duration==240,"single mini-app style fade");
  target.timeout();r.ready(web);web.commit();target.frame();check(target.animations==1,"no repeated fade after finish");
  r.prepare(web,false);r.ready(web);Runnable stale=web.visual.remove(0);
  r.prepare(web,false);stale.run();target.frame();target.timeout();
  check(target.alpha==0,"redirect cannot reveal previous navigation");
  r.ready(web);target.timeout();check(target.alpha==1,"fallback when visual callback never arrives");
  int animations=target.animations;web.commit();target.frame();check(target.animations==animations,"late callback ignored");
  r.prepare(web,false);r.ready(web);r.hold(true);web.commit();target.frame();target.timeout();
  check(target.alpha==0,"held reveal stays hidden");r.hold(false);web.commit();target.frame();
  check(target.alpha==1,"held reveal resumes");
  r.detached();target.attached=web.attached=false;r.ready(web);target.frame();target.timeout();
  check(target.frames.isEmpty()&&target.delayed.isEmpty(),"detached callbacks cleared");
  target.attached=true;r.attached();web.attached=true;target.frame();web.commit();target.frame();
  check(target.alpha==1,"parent attaches before child");
  r.detached();r.hold(true);r.resume(web,false);check(target.alpha==0,"restored committed page hidden during transition");
  r.hold(false);web.commit();target.frame();check(target.alpha==1,"restore retains readiness before pageFinished");
  r.prepare(web,false);r.ready(web);stale=web.visual.remove(0);WebView replacement=new WebView();
  r.clear();r.prepare(replacement,false);stale.run();target.frame();target.timeout();
  check(target.alpha==0,"old webview cannot reveal replacement");
  r.ready(web);check(replacement.visual.isEmpty(),"wrong owner ignored");
  replacement.broken=true;r.ready(replacement);target.frame();check(target.alpha==1,"unsupported visual callback fallback");
  Build.VERSION.SDK_INT=22;r.prepare(replacement,true);target.frame();check(target.alpha==1,"pre-M fallback");
  Build.VERSION.SDK_INT=36;r.prepare(web,false);r.ready(web);r.clear();web.commit();target.frame();target.timeout();
  check(target.alpha==1&&target.delayed.isEmpty(),"destroy restores opacity and cancels callbacks");
  System.out.println("PASS: actual reveal controller; readiness, opening gate, redirect, replacement, detach/restore, stop-ready, timeout, unsupported WebView and cleanup");
 }
}`;
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-browser-reveal-'));
try {
    const file = path.join(dir, 'BrowserRevealTest.java');
    fs.writeFileSync(file, java);
    cp.execFileSync('javac', [file]);
    const result = cp.spawnSync('java', ['-cp', dir, 'BrowserRevealTest'], {encoding:'utf8'});
    assert.equal(result.status, 0, result.stderr);
    console.log(result.stdout.trim());
} finally {
    fs.rmSync(dir, {recursive:true, force:true});
}
