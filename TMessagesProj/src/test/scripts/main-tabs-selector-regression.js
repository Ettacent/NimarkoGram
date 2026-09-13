const fs = require('node:fs');
const path = require('node:path');
const cp = require('node:child_process');
const os = require('node:os');
const assert = require('node:assert/strict');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/java/org/telegram/ui/Components/glass/GlassTabView.java'), 'utf8');
function method(signature) {
 const start = source.indexOf(signature); assert(start >= 0);
 let depth=0; for(let p=source.indexOf('{',start);p<source.length;p++) {
  if(source[p]==='{')depth++;if(source[p]==='}'&&!--depth)return source.slice(start,p+1);
 }
 throw Error(signature);
}
const visible = source.match(/final float selectedFactor = ([^;]+);/)[1];
assert.match(source, /isSelectedAnimator.setValue\(selected, animated\);\s*updateSelectorTarget\(selected, animated\);/);
assert.match(source, /blendARGB\(colorDefault, colorSelected, isSelectedAnimator.getFloatValue\(\)\)/);
const cases = [[false,.8043848,0],[true,.83496094,1],[false,.8043848,0],[true,.83496094,1],[false,.5909766,0],[true,.9784277,1],[false,.47696292,0],[true,.9081152,1],[false,.5121191,0],[true,.994834,1],[false,.005165994,0],[true,.9980859,1],[false,.3451562,0],[true,.87530273,1],[false,.8498535,0],[true,.83749026,1],[false,.16503906,0],[true,.969375,1],[false,.0625,0],[true,.83749026,1],[false,.0625,0]];
const java=`
class MathUtils {static float clamp(float f,float a,float b){return Math.max(a,Math.min(b,f));}}
class BoolAnimator {boolean value;float factor;boolean getValue(){return value;}float getFloatValue(){return factor;}}
class FactorAnimator {float factor,target;int starts;float getFactor(){return factor;}float getToFactor(){return target;}
 void forceFactor(float f){factor=target=f;}void animateTo(float f){target=f;starts++;}}
public class SelectorTest {
 BoolAnimator isSelectedAnimator=new BoolAnimator();FactorAnimator selectorAnimator=new FactorAnimator();
 boolean hasGestureSelectedOverride;float lastGesture;void invalidate(){}
 ${method('public void setGestureSelectedOverride(')}
 ${method('private void updateSelectorTarget(')}
 float visible(){return ${visible};}
 void gesture(float f,boolean allow){lastGesture=f;setGestureSelectedOverride(f,allow);}
 static int checks;static void check(boolean b,String s){checks++;if(!b)throw new AssertionError(s);}
 static void replay(boolean selected,float color,float progress){
  SelectorTest t=new SelectorTest();t.isSelectedAnimator.value=selected;t.isSelectedAnimator.factor=color;
  t.gesture(progress,true);float before=t.visible();t.updateSelectorTarget(selected,true);t.gesture(progress,false);
  check(t.visible()==before,"recorded swipe handoff resurrects highlight");
  check(t.isSelectedAnimator.factor==color,"color animation must not jump with selector");
  check(t.selectorAnimator.getToFactor()==(selected?1:0),"correct selected target");
 }
 public static void main(String[] args){
  ${cases.map(([v,c,p])=>`replay(${v},${c}f,${p}f);`).join('\n')}
  for(int i=0;i<=1000;i++)for(boolean selected:new boolean[]{false,true}){
   float progress=i/1000f;replay(selected,1f-progress,progress);
   SelectorTest t=new SelectorTest();t.isSelectedAnimator.value=selected;t.gesture(progress,true);t.gesture(progress,false);
   int starts=t.selectorAnimator.starts;t.updateSelectorTarget(selected,true);t.gesture(0,false);
   check(starts==t.selectorAnimator.starts,"repeated callbacks must not restart handoff");
   t.gesture(1f-progress,true);check(t.visible()==1f-progress,"retouch follows finger");
   t.isSelectedAnimator.value=!selected;t.updateSelectorTarget(!selected,true);check(t.visible()==1f-progress,"selection during swipe cannot replace finger progress");
   t.gesture(1f-progress,false);t.updateSelectorTarget(selected,false);check(t.visible()==(selected?1:0),"instant initialization");
  }
  System.out.println("PASS: "+checks+" selector checks including 21 recorded handoffs, reversal, repeat updates and independent icon colors");
 }
}`;
const dir=fs.mkdtempSync(path.join(os.tmpdir(),'nimarko-selector-'));
try {
 const run=code=>{fs.writeFileSync(path.join(dir,'SelectorTest.java'),code);cp.execFileSync('javac',['SelectorTest.java'],{cwd:dir});return cp.spawnSync('java',['SelectorTest'],{cwd:dir,encoding:'utf8'});};
 const good=run(java);assert.equal(good.status,0,good.stderr);process.stdout.write(good.stdout);
 const bad=run(java.replace(`return ${visible};`,'return hasGestureSelectedOverride ? lastGesture : isSelectedAnimator.getFloatValue();'));
 assert.notEqual(bad.status,0);assert.match(bad.stderr,/recorded swipe handoff resurrects highlight/);
 console.log('PASS: previous implementation reproduces recorded flicker');
} finally {fs.rmSync(dir,{recursive:true,force:true});}
