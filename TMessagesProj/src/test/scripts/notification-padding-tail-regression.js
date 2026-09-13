const fs = require('node:fs'), path = require('node:path'), os = require('node:os');
const cp = require('node:child_process'), assert = require('node:assert/strict');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/java/org/telegram/ui/Components/AnimatedLinearLayout.java'), 'utf8');
const start = source.indexOf('private void checkViewsVisibility()');
let end = source.indexOf('{', start), depth = 1;
while (depth && ++end < source.length) {
    if (source[end] === '{') depth++;
    if (source[end] === '}') depth--;
}
const method = source.slice(start, end + 1);
function extract(marker){
 const start=source.indexOf(marker);let end=source.indexOf('{',start),depth=1;
 while(depth&&++end<source.length){if(source[end]==='{')depth++;if(source[end]==='}')depth--;}
 return source.slice(start,end+1);
}
const java = `
import java.util.*;
public class PaddingTailTest {
 static class RectF {float top,left;}
 static class View {float top,left;int getTop(){return (int)top;}int getLeft(){return (int)left;}void setTranslationX(float f){}void setTranslationY(float f){}}
 static class Holder {View view=new View();}
 interface IndependentPanel {float getLayoutCoverage();}
 static class Slot extends View implements IndependentPanel {float coverage=1;public float getLayoutCoverage(){return coverage;}}
 static class ListAnimator {static class Entry<T> {T item;float visibility=1;RectF getRectF(){return new RectF();}float getVisibility(){return visibility;}}}
 static final int VERTICAL=1;
 int getOrientation(){return VERTICAL;}int getPaddingTop(){return 0;}int getPaddingLeft(){return 0;}
 void setChildVisibilityFactor(View v,float f){}
 ArrayList<ListAnimator.Entry<Holder>> listAnimator=new ArrayList<>();
 static class Metadata {float height,visibility;float getTotalHeight(){return height;}float getTotalVisibility(){return visibility;}}
 Metadata metadata=new Metadata();Metadata getMetadata(){return metadata;}
 ${extract('public float getLayoutVisibility()')}
 ${extract('public float getAnimatedHeightWithPadding(float padding)')}
 float lastAnimatedHeight,lastAnimatedVisibility;
 int padding=675,callbacks;
 Runnable onAnimatedHeightChanged=()->{callbacks++;padding=675+Math.round(getAnimatedHeightWithPadding(27));};
 ${method}
 static int checks;static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 void frame(float h,float visibility){metadata.height=h;metadata.visibility=visibility;checkViewsVisibility();}
 public static void main(String[] args){
  PaddingTailTest p=new PaddingTailTest();
  p.frame(220,1);
  for(int height:new int[]{119,33,26,19,13,9,5,3,1,0})p.frame(height,1);
  check(p.padding==702,"recorded empty slot still reserves 27px while visible");
  for(int i=1;i<=60;i++){
   float visibility=1-i/60f;int before=p.padding;
   p.frame(0,visibility);
   check(p.padding==675+Math.round(27*visibility),"zero-height visibility animation must update list padding every frame");
   check(p.padding<=before&&before-p.padding<=1,"remaining inset shrinks continuously");
  }
  check(p.padding==675,"removal leaves no 27px for a later unrelated layout");
  int callbacks=p.callbacks;p.frame(0,0);
  check(p.callbacks==callbacks,"identical geometry does not request another layout");
  for(float base:new float[]{0,64,220})for(int i=0;i<=100;i++){
   float v=i/100f;p.frame(base,v);
   check(p.padding==675+Math.round(base+27*v),"padding-only changes also notify with a shared panel");
  }
  ListAnimator.Entry<Holder> entry=new ListAnimator.Entry<>();entry.item=new Holder();Slot slot=new Slot();entry.item.view=slot;p.listAnimator.add(entry);
  for(int cycle=0;cycle<3;cycle++){
   for(int i=0;i<=220;i++){
    slot.coverage=(220-i)/220f;p.frame(220-i,1);
    check(p.padding==675+Math.round((220+27)*slot.coverage),"slot and padding use the same dismissal progress");
   }
   check(p.padding==675,"no second lowering phase remains after the card disappears");
   for(int i=0;i<=60;i++){
    entry.visibility=1-i/60f;p.frame(0,entry.visibility);
    check(p.padding==675,"native slot cleanup cannot move the list again");
   }
   entry.visibility=1;
  }
  ListAnimator.Entry<Holder> shared=new ListAnimator.Entry<>();shared.item=new Holder();p.listAnimator.add(shared);
  p.frame(64,1);check(p.padding==766,"existing music or pinned panel keeps its own inset");
  shared.visibility=.5f;p.frame(32,1);check(p.getLayoutVisibility()==.5f,"native sibling can disappear while notification has no coverage");
  slot.coverage=1;entry.visibility=.5f;check(p.getLayoutVisibility()==1,"overlapping incoming and outgoing panels do not create a gap");
  p.onAnimatedHeightChanged=null;p.frame(0,0);
  check(p.lastAnimatedVisibility==0&&p.lastAnimatedHeight==0,"cache updates without a listener");
  System.out.println("PASS: "+checks+" recorded visibility/padding-tail checks");
 }
}`;
const dir=fs.mkdtempSync(path.join(os.tmpdir(),'nimarko-padding-tail-'));
function run(code){
 fs.writeFileSync(path.join(dir,'PaddingTailTest.java'),code);
 const c=cp.spawnSync('javac',[path.join(dir,'PaddingTailTest.java')],{encoding:'utf8'});
 assert.equal(c.status,0,c.stderr);
 return cp.spawnSync('java',['-cp',dir,'PaddingTailTest'],{encoding:'utf8'});
}
try {
 const ok=run(java);assert.equal(ok.status,0,ok.stderr);process.stdout.write(ok.stdout);
 const broken=java.replace(' || lastAnimatedVisibility != animatedVisibility','');
 assert.notEqual(broken,java);
 const bad=run(broken);assert.notEqual(bad.status,0);
 assert.match(bad.stderr,/zero-height visibility animation/);
 console.log('PASS: previous height-only callback reproduces the recorded stale 27px inset');
 const delayed=java.replace(extract('public float getLayoutVisibility()'),
  'public float getLayoutVisibility(){return getMetadata().getTotalVisibility();}');
 assert.notEqual(delayed,java);
 const secondPhase=run(delayed);assert.notEqual(secondPhase.status,0);
 assert.match(secondPhase.stderr,/slot and padding use the same dismissal progress/);
 console.log('PASS: independent padding timing reproduces the unwanted second phase');
} finally {fs.rmSync(dir,{recursive:true,force:true});}
