const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const cp = require('node:child_process');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../main/java');
const reactions = fs.readFileSync(path.join(root, 'org/telegram/ui/Components/Reactions/ReactionsLayoutInBubble.java'), 'utf8');
const cell = fs.readFileSync(path.join(root, 'org/telegram/ui/Cells/ChatMessageCell.java'), 'utf8');
const qr = fs.readFileSync(path.join(root, 'org/telegram/ui/QrActivity.java'), 'utf8');
function method(text, signature) {
    const start = text.indexOf('\n    ' + signature);
    assert(start >= 0, signature);
    const tokens = /\/\/[^\n]*|\/\*[\s\S]*?\*\/|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[{}]/g;
    tokens.lastIndex = text.indexOf('{', start);
    let depth = 0;
    for (let t; (t = tokens.exec(text));) {
        if (t[0] === '{') depth++;
        if (t[0] === '}' && --depth === 0) return text.slice(start, tokens.lastIndex);
    }
    throw Error(signature);
}
assert.equal((reactions.match(/float totalY = getCurrentY\(animationProgress\)/g) || []).length, 2);
assert(method(cell, 'public void drawReactionsLayout(').includes('areReactionsVisible(canvas)'));
assert(method(cell, 'public boolean drawReactionsLayoutOverlay(').includes('areReactionsVisible(canvas)'));
assert(method(cell, 'public void setVisiblePart(').includes('reactionsViewportInitialized = true;'));
assert(!qr.includes('0xFF9BC38F'), 'No green placeholder');
assert(qr.includes('if (!initialBackgroundReady) return;'));
assert(qr.indexOf('resumeDelayedFragmentAnimation();') < qr.indexOf('Bitmap preparedLogo = null;'));
assert(qr.includes('fragmentView != openingView'));
assert(qr.includes('currMotionDrawable != requestedDrawable'));
assert(qr.includes('prevQrColors = newQrColors.clone();'));
const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-quote-qr-'));
const java = `import java.util.*;
public class Regression {
 static void check(boolean v) { if (!v) throw new AssertionError(); }
 static class Reactions {
  float y, fromY, lastDrawnY;
  boolean isEmpty, animateMove, animateHeight;
  int height, totalHeight, lastDrawTotalHeight, animateFromTotalHeight;
  ArrayList<Object> outButtons = new ArrayList<>();
  ${method(reactions, 'public float getCurrentY(')}
  ${method(reactions, 'public boolean isVisible(')}
  ${method(reactions, 'public float getCurrentTotalHeight(')}
 }
 static class Rect { int top, bottom; }
 static class Canvas {
  int top = -428, bottom = 2118, reads; boolean empty;
  boolean getClipBounds(Rect rect) { reads++; rect.top=top; rect.bottom=bottom; return !empty; }
 }
 static class Params { boolean animateChange, animateBackgroundBoundsInner; float animateChangeProgress; }
 static class Cell {
  boolean fullyDraw, reactionsVisible, reactionsViewportInitialized = true; int childPosition, visibleHeight;
  final Rect reactionsClipBounds = new Rect();
  final Canvas canvas = new Canvas();
  Reactions reactionsLayoutInBubble = new Reactions(); Params transitionParams = new Params();
  ${method(cell, 'private boolean areReactionsVisible(')}
  boolean areReactionsVisible() { return areReactionsVisible(canvas); }
 }
 static class Qr {
  boolean openTransitionFinished, initialThemeColorsReady, initialThemeApplied, initialBackgroundReady;
  Object themesViewController = new Object(), currentTheme = new Object();
  int selectedPosition = -1, count, lastPosition; boolean lastAnimated;
  void onItemSelected(Object theme, int pos, boolean animated) { count++; lastPosition=pos; lastAnimated=animated; }
  ${method(qr, 'private void applyInitialThemeAfterTransition(')}
  ${method(qr, 'public boolean needDelayOpenAnimation(')}
 }
 public static void main(String[] args) {
  int checks = 0;
  for (int hz : new int[]{60,90,120,144}) for (int start : new int[]{-100,140,500,940})
   for (int end : new int[]{-100,140,500,940}) for (int rows : new int[]{1,3,8}) {
    Cell c = new Cell(); Reactions r = c.reactionsLayoutInBubble;
    c.childPosition=100; c.visibleHeight=520; c.transitionParams.animateChange=true;
    r.animateMove=true; r.fromY=start; r.y=end; r.height=rows*30; r.totalHeight=r.height+8;
    for (int i=0;i<=hz;i++) {
     float p=i/(float)hz; c.transitionParams.animateChangeProgress=p;
     float y=end*p+start*(1-p);
     boolean expected=y<=620 && y+r.totalHeight>=100;
     c.reactionsVisible=!expected;
     check(r.getCurrentY(p)==y); check(c.areReactionsVisible()==expected); checks++;
    }
   }
  Cell c = new Cell(); Reactions r=c.reactionsLayoutInBubble;
  c.childPosition=0; c.visibleHeight=500; c.transitionParams.animateChange=true;
  r.animateMove=true; r.fromY=160; r.y=760; r.height=30; r.totalHeight=38;
  check(!(r.y<=500 && r.y+r.height>=0)); check(c.areReactionsVisible());
  r.isEmpty=true; check(!c.areReactionsVisible());
  r.lastDrawnY=160; r.lastDrawTotalHeight=38; r.outButtons.add(new Object()); check(c.areReactionsVisible());
  c.fullyDraw=true; r.lastDrawnY=900; check(c.areReactionsVisible());
  // The parent can stop updating the viewport as soon as the animator finishes.
  c.fullyDraw=false; r.isEmpty=false; r.outButtons.clear(); r.height=30; r.totalHeight=38;
  r.fromY=760; r.y=160; r.animateMove=true;
  c.childPosition=0; c.visibleHeight=500; c.reactionsVisible=false;
  c.transitionParams.animateChange=true; c.transitionParams.animateChangeProgress=1f;
  check(c.areReactionsVisible());
  c.transitionParams.animateChange=false; r.animateMove=false;
  if (!c.areReactionsVisible()) throw new AssertionError("Collapsed quote loses reactions on animation handoff");
  c.reactionsVisible=true; r.y=760;
  check(!c.areReactionsVisible());
  c.reactionsViewportInitialized=false;
  check(c.areReactionsVisible());
  c.reactionsViewportInitialized=true;
  c.visibleHeight=0; r.y=160; check(!c.areReactionsVisible());
  c.fullyDraw=true; check(c.areReactionsVisible());
  for (int hz : new int[]{60,90,120,144}) for (int direction : new int[]{-1,1})
   for (int rows : new int[]{1,3,8}) for (int edge : new int[]{-1,0,1,499,500,501}) {
    Cell end = new Cell(); Reactions er=end.reactionsLayoutInBubble;
    end.visibleHeight=500; er.y=edge; er.fromY=edge+direction*600;
    er.height=rows*30; er.totalHeight=er.height+8; er.animateMove=true;
    end.transitionParams.animateChange=true;
    for (int i=0;i<=hz;i++) {
     float p=i/(float)hz; end.transitionParams.animateChangeProgress=p;
     float y=er.y*p+er.fromY*(1-p);
     boolean expected=y<=500 && y+er.totalHeight>=0;
     end.reactionsVisible=!expected;
     check(end.areReactionsVisible()==expected); checks++;
    }
    boolean lastFrame=end.areReactionsVisible();
    end.transitionParams.animateChange=false; er.animateMove=false;
    check(end.areReactionsVisible()==lastFrame);
    for (float interruptedAt : new float[]{0f,0.25f,0.75f}) {
     end.transitionParams.animateChange=true; er.animateMove=true;
     end.transitionParams.animateChangeProgress=interruptedAt;
     end.reactionsVisible=!lastFrame;
     end.areReactionsVisible();
     end.transitionParams.animateChange=false; er.animateMove=false;
     check(end.areReactionsVisible()==lastFrame);
    }
   }
  for (int hz : new int[]{60,90,120,144}) for (boolean collapse : new boolean[]{true,false}) {
   Cell observed = new Cell(); Reactions or = observed.reactionsLayoutInBubble;
   observed.childPosition=0; observed.visibleHeight=collapse ? 1548 : 1853;
   observed.transitionParams.animateChange=observed.transitionParams.animateBackgroundBoundsInner=true;
   or.fromY=collapse ? 1571 : 1266; or.y=collapse ? 1266 : 1571;
   or.height=78; or.totalHeight=138; or.animateMove=true;
   for(int i=0;i<=hz;i++) {
    observed.transitionParams.animateChangeProgress=i/(float)hz;
    check(observed.areReactionsVisible()); checks++;
   }
   observed.transitionParams.animateBackgroundBoundsInner=false;
   observed.transitionParams.animateChange=false; or.animateMove=false;
   int clipReads=observed.canvas.reads;
   check(observed.areReactionsVisible());
   check(observed.canvas.reads==clipReads);
   observed.transitionParams.animateBackgroundBoundsInner=true;
   observed.canvas.empty=true; check(!observed.areReactionsVisible());
   observed.fullyDraw=true; check(observed.areReactionsVisible());
   observed.fullyDraw=false; observed.canvas.empty=false;
   observed.canvas.top=2000; observed.canvas.bottom=2118;
   check(!observed.areReactionsVisible());
   observed.canvas.top=-428; observed.canvas.bottom=1000;
   check(!observed.areReactionsVisible());
   or.isEmpty=true; observed.canvas.bottom=2118;
   check(!observed.areReactionsVisible());
   or.lastDrawnY=1571; or.lastDrawTotalHeight=138; or.outButtons.add(new Object());
   check(observed.areReactionsVisible());
  }
  for (boolean colorsFirst : new boolean[]{true,false}) {
   Qr q=new Qr(); check(q.needDelayOpenAnimation());
   if (colorsFirst) q.initialThemeColorsReady=true; else q.openTransitionFinished=true;
   q.applyInitialThemeAfterTransition(); check(q.count==0);
   q.initialThemeColorsReady=true; q.openTransitionFinished=true; q.initialBackgroundReady=true;
   q.applyInitialThemeAfterTransition(); check(q.count==1 && !q.lastAnimated && q.lastPosition==0);
   q.applyInitialThemeAfterTransition(); check(q.count==1 && !q.needDelayOpenAnimation());
  }
  Qr q=new Qr(); q.openTransitionFinished=q.initialThemeColorsReady=true;
  q.initialThemeApplied=true; q.selectedPosition=4; q.applyInitialThemeAfterTransition(); check(q.count==0);
  q.initialThemeApplied=false; q.themesViewController=null; q.applyInitialThemeAfterTransition(); check(q.count==0);
  System.out.println("PASS: " + checks + " reaction viewport checks; close/open handoff, cancellation, overlays, outgoing reactions and QR ordering");
 }
}`;
fs.writeFileSync(path.join(temp, 'Regression.java'), java);
cp.execFileSync('javac', [path.join(temp, 'Regression.java')], {stdio:'inherit'});
cp.execFileSync('java', ['-cp',temp,'Regression'], {stdio:'inherit'});
const oldViewport = java.replace('if (transitionParams.animateBackgroundBoundsInner) {', 'if (false) {');
assert.notEqual(oldViewport, java);
fs.writeFileSync(path.join(temp, 'Regression.java'), oldViewport);
cp.execFileSync('javac', [path.join(temp, 'Regression.java')], {stdio:'inherit'});
const negative = cp.spawnSync('java', ['-cp', temp, 'Regression'], {encoding:'utf8', timeout:30000});
assert.ifError(negative.error);
assert.notEqual(negative.status, 0);
assert(negative.stderr.includes('AssertionError'));
console.log('PASS: recorded collapse/expand geometry and clip handoff; old destination-viewport culling fails');
