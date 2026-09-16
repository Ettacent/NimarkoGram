const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const cp = require('node:child_process');
const assert = require('node:assert/strict');
const qr = fs.readFileSync(path.resolve(__dirname, '../../main/java/org/telegram/ui/QrActivity.java'), 'utf8');
function method(signature) {
    const start = qr.indexOf('    private void ' + signature);
    assert(start >= 0);
    const open = qr.indexOf('{', start);
    let depth = 1, end = open + 1;
    for (; depth > 0; end++) {
        if (qr[end] === '{') depth++;
        if (qr[end] === '}') depth--;
    }
    return qr.slice(start, end);
}
assert(qr.indexOf('setInitialPageAlpha(0f);') < qr.indexOf('final View openingView = fragmentView;'));
assert(qr.indexOf('description.setColor(getThemedColor(description.getCurrentKey()), false, false);') < qr.indexOf('revealInitialPage();'));
assert(qr.includes('initialPageAnimator.end();'), 'Share must capture fully opaque content');
const destroy = qr.slice(qr.indexOf('public void onFragmentDestroy()'), qr.indexOf('public void onTransitionAnimationEnd('));
assert(destroy.includes('cancelInitialPageAnimation();'));
assert(destroy.includes('patternIntensityAnimator.cancel();'));
const pattern = method('onPatternLoaded(');
assert(pattern.indexOf('patternPreviousDrawable = new MotionBackgroundDrawable();') < pattern.indexOf('drawable.setPatternBitmap('));
assert(pattern.includes('patternTransitionProgress = animate ? 0f : 1f;'));
assert(pattern.includes('drawable.setPatternAlpha(1f);'));
assert(qr.includes('Math.round(255 * patternTransitionProgress)'));
assert(qr.includes('currMotionDrawable.setPhase(prevMotionDrawable.getPhase());'));
const cached = qr.slice(qr.indexOf('Bitmap preparedPattern = qrPatternCache.get('), qr.indexOf('Bitmap preparedLogo = null;'));
assert(cached.includes('preparedPattern.isRecycled()'));
assert(cached.includes('SvgHelper.getBitmap(R.raw.default_pattern'));
assert(cached.indexOf('currMotionDrawable.setPatternBitmap(') < cached.indexOf('revealInitialPage();'));
assert(!cached.includes('initialPageRevealed = true;'));
assert(!cached.includes('setInitialPageAlpha(1f);'));
assert(!qr.includes('applyInitialThemeAfterTransition'), 'Initial theme must not be selected twice');
assert(qr.includes('24 * 1024 * 1024'), 'Bound QR pattern cache memory');
assert(!qr.includes('backgroundView.setClipBounds('), 'Background must extend behind the rounded theme sheet');
assert(qr.includes('Math.round(255 * initialPageAlpha)'), 'Composite the page before applying reveal alpha');
assert(!method('setInitialPageAlpha(').includes('.setAlpha('), 'Do not fade overlapping children separately');
const key = qr.slice(qr.indexOf('private String qrPatternKey('), qr.indexOf('private void onPatternLoaded('));
for (const part of ['currentAccount', 'dark', 'wallpaper.id', 'settings.intensity', 'patternBgGradientColor3']) {
    assert(key.includes(part), 'Cache key must include ' + part);
}
assert(pattern.includes('if (currMotionDrawable == drawable)'));

const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'qr-page-reveal-'));
const java = `
public class QrPageReveal {
 static class EmojiThemes {}
 static class List<T> {}
 List<EmojiThemes> pendingLoadedThemes;
 boolean openTransitionFinished;
 int pendingThemesGeneration, themesApplied;
 void applyLoadedThemes(List<EmojiThemes> themes, int generation) { themesApplied++; }
 ${method('applyPendingThemesWhenReady(')}
 static class View { void invalidate() {} }
 static class Animator {}
 static class AnimatorListenerAdapter { public void onAnimationEnd(Animator a) {} }
 interface Update { void update(ValueAnimator a); }
 static class ValueAnimator extends Animator {
  Update update; AnimatorListenerAdapter listener; float value; long duration;
  static ValueAnimator ofFloat(float a, float b) { return new ValueAnimator(); }
  void setDuration(long d) { duration=d; }
  void setInterpolator(Object o) {}
  void addUpdateListener(Update u) { update=u; }
  void addListener(AnimatorListenerAdapter l) { listener=l; }
  void start() { tick(0); }
  void tick(float p) { value=p; update.update(this); }
  Object getAnimatedValue() { return value; }
  void cancel() { listener.onAnimationEnd(this); }
  void end() { tick(1); listener.onAnimationEnd(this); }
 }
 static class SharedConfig { static boolean enabled=true; static boolean animationsEnabled() { return enabled; } }
 static class CubicBezierInterpolator { static final Object EASE_BOTH=new Object(); }
 View backgroundView=new View(), qrView=new View(), avatarImageView=new View(), logoImageView=new View(), themeLayout=new View();
 View fragmentView=new View(); Object themesViewController=new Object();
 boolean initialPageRevealed, initialBackgroundReady; ValueAnimator initialPageAnimator;
 float initialPageAlpha=1f;
 ${method('setInitialPageAlpha(')}
 ${method('cancelInitialPageAnimation(')}
 ${method('revealInitialPage(')}
 static void check(boolean v) { if (!v) throw new AssertionError(); }
 void checkAlpha(float a) {
  check(initialPageAlpha==a);
 }
 public static void main(String[] args) {
  QrPageReveal q=new QrPageReveal(); q.setInitialPageAlpha(0);
  q.revealInitialPage(); check(q.initialPageAnimator==null); q.checkAlpha(0);
  q.initialBackgroundReady=true; q.revealInitialPage();
  ValueAnimator a=q.initialPageAnimator; check(a!=null && a.duration==350); q.checkAlpha(0);
  q.revealInitialPage(); check(q.initialPageAnimator==a);
  for(int hz:new int[]{60,90,120,144}) for(int i=0;i<=hz;i++) {
   float progress=i/(float)hz; a.tick(progress); q.checkAlpha(progress);
  }
  a.tick(.4f); q.cancelInitialPageAnimation(); q.checkAlpha(.4f);
  a.tick(1); q.checkAlpha(.4f);
  q.initialPageRevealed=false; q.fragmentView=new View(); q.revealInitialPage();
  ValueAnimator b=q.initialPageAnimator; a.end(); check(q.initialPageAnimator==b); q.checkAlpha(0);
  b.end(); q.checkAlpha(1); check(q.initialPageAnimator==null);
  q.pendingLoadedThemes=new List<>(); q.applyPendingThemesWhenReady(); check(q.themesApplied==0);
  q.openTransitionFinished=true; q.applyPendingThemesWhenReady(); check(q.themesApplied==1);
  q.applyPendingThemesWhenReady(); check(q.themesApplied==1);
  q.initialPageRevealed=false; q.revealInitialPage(); b=q.initialPageAnimator;
  q.pendingLoadedThemes=new List<>(); q.applyPendingThemesWhenReady(); check(q.themesApplied==1);
  q.themesViewController=null; b.tick(.5f); q.checkAlpha(0);
  q.cancelInitialPageAnimation(); q.checkAlpha(0);
  SharedConfig.enabled=false; q=new QrPageReveal(); q.initialBackgroundReady=true;
  q.setInitialPageAlpha(0); q.revealInitialPage(); q.checkAlpha(1); check(q.initialPageAnimator==null);
  System.out.println("PASS: unified page alpha, readiness, frame progress, disabled animations, repeat, cancellation, stale callback, close and share completion");
 }
}`;
fs.writeFileSync(path.join(dir, 'QrPageReveal.java'), java);
cp.execFileSync('javac', ['QrPageReveal.java'], {cwd:dir, stdio:'inherit'});
cp.execFileSync('java', ['QrPageReveal'], {cwd:dir, stdio:'inherit'});
