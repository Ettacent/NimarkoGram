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
assert(method(cell, 'public void drawReactionsLayout(').includes('areReactionsVisible()'));
assert(method(cell, 'public boolean drawReactionsLayoutOverlay(').includes('areReactionsVisible()'));
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
 static class Params { boolean animateChange; float animateChangeProgress; }
 static class Cell {
  boolean fullyDraw, reactionsVisible; int childPosition, visibleHeight;
  Reactions reactionsLayoutInBubble = new Reactions(); Params transitionParams = new Params();
  ${method(cell, 'private boolean areReactionsVisible(')}
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
  c.fullyDraw=false; c.transitionParams.animateChange=false; c.reactionsVisible=true; check(c.areReactionsVisible());
  c.reactionsVisible=false; check(!c.areReactionsVisible());
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
  System.out.println("PASS: " + checks + " animated reaction viewport checks; negative control, overlays, outgoing reactions, QR preparation ordering and stale callbacks");
 }
}`;
fs.writeFileSync(path.join(temp, 'Regression.java'), java);
cp.execFileSync('javac', [path.join(temp, 'Regression.java')], {stdio:'inherit'});
cp.execFileSync('java', ['-cp',temp,'Regression'], {stdio:'inherit'});
