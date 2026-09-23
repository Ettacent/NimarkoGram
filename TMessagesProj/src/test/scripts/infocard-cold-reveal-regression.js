const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const cp = require('node:child_process');
const assert = require('node:assert/strict');
const base = fs.readFileSync(path.resolve(__dirname, '../../main/java/app/nimarkogram/messenger/infocards/BaseInfoCard.java'), 'utf8');
const strip = fs.readFileSync(path.resolve(__dirname, '../../main/java/app/nimarkogram/messenger/infocards/InfoCardStripView.java'), 'utf8');
function method(signature) {
    const start = base.indexOf(signature);
    assert(start >= 0);
    let depth = 0;
    const re = /\/\/[^\n]*|\/\*[\s\S]*?\*\/|"(?:\\.|[^"\\])*"|[{}]/g;
    re.lastIndex = base.indexOf('{', start);
    for (let t; (t = re.exec(base));) {
        if (t[0] === '{') depth++;
        else if (t[0] === '}' && --depth === 0) return base.slice(start, re.lastIndex);
    }
    throw Error(signature);
}
assert(base.includes('textView.setText("", false, false);'));
assert(!base.includes('nextRenderInstant'));
assert(!strip.includes('renderNextInstant'));
assert(strip.includes('if (notifySelected && cur != null) cur.updateDataInstantly();'));
assert(strip.includes('in.updateDataInstantly();'));
const java = `
public class ColdRevealTest {
 static int checks;
 static void check(boolean b){checks++;if(!b)throw new AssertionError("check "+checks);}
 static class View {static final int VISIBLE=0;}
 static class InfoCardStripView {boolean resizing=true;boolean canAnimateCardResize(){return resizing;}}
 static class Text {
  CharSequence text;boolean first=true,animated;
  CharSequence getText(){return text;}
  void setText(CharSequence s,boolean a){setText(s,a,false);}
  void setText(CharSequence s,boolean a,boolean down){animated=a&&!first&&text!=null;first=false;text=s;}
 }
 static class Content {void requestLayout(){}void invalidateOutline(){}}
 static class Card {
  static final int VISIBLE=0;
  boolean renderingInstantly,hasRenderedValue,attached=true,shown=true;int visibility;
  float translationY,alpha=1,scale=1;
  CharSequence accessibilityValue;
  Text textView=new Text();Content content=new Content();InfoCardStripView parent=new InfoCardStripView();
  Runnable update=()->{};
  Card(){textView.setText("",false,false);}
  void updateAccessibilityDescription(){}
  boolean isLaidOut(){return true;}
  void finishResizeAnimation(){}
  boolean isEmpty(CharSequence text){return text==null||text.length()==0;}
  boolean isAttachedToWindow(){return attached;}int getWindowVisibility(){return 0;}
  int getVisibility(){return visibility;}boolean isShown(){return shown;}
  float getTranslationX(){return 0;}float getTranslationY(){return translationY;}
  float getAlpha(){return alpha;}float getScaleX(){return scale;}float getScaleY(){return scale;}
  Object getParent(){return parent;}void onUpdateData(boolean force){update.run();}
  ${method('protected void setText(').replaceAll('android.text.TextUtils.equals', 'java.util.Objects.equals').replaceAll('android.text.TextUtils.isEmpty', 'isEmpty')}
  ${method('void updateDataInstantly()')}
 }
 public static void main(String[] args){
  Card c=new Card();c.updateDataInstantly();check(!c.renderingInstantly);
  c.setText("84.24 ₽",true);check(c.textView.animated);
  c=new Card();final Card cached=c;c.update=()->cached.setText("84.24 ₽",true);
  c.updateDataInstantly();check(!c.textView.animated&&!c.renderingInstantly);
  c.setText("85.00 ₽",true);check(c.textView.animated);
  c=new Card();c.update=()->{throw new IllegalStateException();};
  try{c.updateDataInstantly();}catch(IllegalStateException expected){}
  check(!c.renderingInstantly);c.setText("84.24 ₽",true);check(c.textView.animated);
  c=new Card();c.renderingInstantly=true;c.updateDataInstantly();check(c.renderingInstantly);
  for(int mode=0;mode<7;mode++){
   c=new Card();
   if(mode==0)c.attached=false;if(mode==1)c.shown=false;if(mode==2)c.visibility=8;
   if(mode==3)c.translationY=10;if(mode==4)c.alpha=.5f;if(mode==5)c.scale=.8f;
   if(mode==6)c.parent.resizing=false;
   c.setText("84.24 ₽",true);check(!c.textView.animated);
  }
  System.out.println("PASS: "+checks+" actual-method cold/cached/async/failure/nested/hidden/carousel reveal checks");
 }
}`;
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-cold-reveal-'));
try {
    function run(code) {
        fs.writeFileSync(path.join(tmp, 'ColdRevealTest.java'), code);
        cp.execFileSync('javac', ['ColdRevealTest.java'], {cwd: tmp, stdio: 'pipe'});
        return cp.execFileSync('java', ['ColdRevealTest'], {cwd: tmp, encoding: 'utf8', stdio: 'pipe'});
    }
    process.stdout.write(run(java));
    assert.throws(() => run(java.replace('textView.setText("",false,false);', '')));
    assert.throws(() => run(java.replace('renderingInstantly = previous;', '')));
    console.log('PASS: uninitialized first text and leaked instant-render flag fail negative controls; device rendering not tested');
} finally {fs.rmSync(tmp, {recursive: true, force: true});}
