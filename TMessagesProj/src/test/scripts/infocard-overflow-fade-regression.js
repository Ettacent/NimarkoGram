const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const cp = require('node:child_process');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../main/java');
const text = fs.readFileSync(path.join(root, 'org/telegram/ui/Components/AnimatedTextView.java'), 'utf8');
const card = fs.readFileSync(path.join(root, 'app/nimarkogram/messenger/infocards/BaseInfoCard.java'), 'utf8');
const start = text.indexOf('private boolean needsEllipsizeGradient()');
const end = text.indexOf('\n        @Override', start);
assert(start >= 0 && end > start);
assert(card.includes('textView.getDrawable().setFadeOverflow(true)'));
assert(card.includes('if (textWidthLimit > 0) avail = Math.min(avail, textWidthLimit)'));
assert(text.includes('Layout.getDesiredWidth(textPart, textPaint)'));
assert(text.includes('canvas.clipRect(AndroidUtilities.rectTmp)'));
assert(text.includes('fadeOverflow && isRTL && !ignoreRTL'));
assert(text.includes('if (!drawable.fadeOverflow) {\n                drawable.setText(drawable.getText(), false, true);'));
assert(card.includes('getDrawable().getCurrentWidth(Math.max(0, avail - padding))'));
const widthStart = text.indexOf('public float getCurrentWidth(float maxWidth)');
const widthEnd = text.indexOf('public float getMaxWidth(', widthStart);
assert(widthStart > 0 && widthEnd > widthStart);
const strengthStart = text.indexOf('float strength = Math.max(0f, Math.min(1f, (currentWidth - available) / w));');
const strengthEnd = text.indexOf('ellipsizePaint.setAlpha', strengthStart);
assert(strengthStart > 0 && strengthEnd > strengthStart);
const java = `public class FadeTest {
 boolean fadeOverflow, ellipsizeByGradient;
 float currentWidth, oldWidth, rightPadding, t;
 Object[] currentParts, oldParts;
 static class Bounds {int width(){return 80;}}
 Bounds bounds = new Bounds();
 boolean hasEllipsizedPart(Object[] parts){return parts != null && parts.length > 0;}
 ${text.slice(start, end)}
 ${text.slice(widthStart, widthEnd)}
 float lerp(float a,float b,float progress){return a+(b-a)*progress;}
 float strength(float available,float w){
  ${text.slice(strengthStart, strengthEnd)}
  return strength;
 }
 void check(boolean expected){if(needsEllipsizeGradient()!=expected)throw new AssertionError();}
 public static void main(String[] args){
  FadeTest v=new FadeTest();v.currentWidth=150;v.check(false);
  v.fadeOverflow=true;v.check(true);
  v.currentWidth=80;v.check(false);
  v.currentWidth=40;v.check(false);
  v.rightPadding=50;v.check(true);v.rightPadding=0;
  v.oldParts=new Object[0];v.oldWidth=150;v.t=.5f;v.check(true);
  v.t=1;v.check(false);
  v.fadeOverflow=false;v.ellipsizeByGradient=true;
  v.currentParts=new Object[]{1};v.check(true);
  v.currentParts=null;v.check(false);
  v.currentParts=new Object[0];v.oldParts=new Object[0];
  for(float cap:new float[]{20,60,100})for(float from:new float[]{10,50,90,300})for(float to:new float[]{10,50,90,300}){
   v.oldWidth=from;v.currentWidth=to;
   for(int frame=0;frame<=100;frame++){
    v.t=frame/100f;float expected=v.lerp(Math.min(from,cap),Math.min(to,cap),v.t);
    if(Math.abs(v.getCurrentWidth(cap)-expected)>.001f)throw new AssertionError("resize plateau");
   }
  }
  v.oldParts=null;v.currentWidth=300;if(v.getCurrentWidth(60)!=60)throw new AssertionError();
  v.currentWidth=80;float prev=1;
  for(int i=0;i<=200;i++){
   float actual=v.strength(64+i*.1f,16);
   if(actual<0||actual>1||actual>prev||prev-actual>.007f)throw new AssertionError("fade discontinuity");
   prev=actual;
  }
  if(prev!=0)throw new AssertionError("fade must disappear completely");
  System.out.println("PASS: live overflow, fit, padding, outgoing text, and legacy gradient behaviour");
 }
}`;
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-card-fade-'));
try {
    fs.writeFileSync(path.join(tmp, 'FadeTest.java'), java);
    cp.execFileSync('javac', ['FadeTest.java'], {cwd: tmp});
    process.stdout.write(cp.execFileSync('java', ['FadeTest'], {cwd: tmp, encoding: 'utf8'}));
} finally {
    fs.rmSync(tmp, {recursive: true, force: true});
}
