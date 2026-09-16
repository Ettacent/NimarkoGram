const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const cp = require('node:child_process');
const assert = require('node:assert/strict');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/java/org/telegram/ui/Components/EditTextCaption.java'), 'utf8');
const start = source.indexOf('    protected void onDraw(Canvas canvas)');
const end = source.indexOf('\n    @Override', start);
assert(start >= 0 && end > start);
const method = source.slice(start, end).replaceAll('app.nimarkogram.messenger.textanim.NimarkoTextAnim', 'TextAnim');
const java = `
import java.util.ArrayList;
class Canvas {
 float y; ArrayList<Float> saves=new ArrayList<>();
 int save() { saves.add(y); return saves.size(); }
 void translate(float x, float dy) { y+=dy; }
 void restore() { restoreToCount(saves.size()); }
 void restoreToCount(int n) { y=saves.get(n-1); while(saves.size()>=n) saves.remove(saves.size()-1); }
}
class Paint { int getColor(){return 0;} void setColor(int c){} }
class Layout { int getLineCount(){return 1;} float getLineRight(int i){return 1;} void draw(Canvas c){} }
class Text { void draw(Canvas c,float x,float y,int color,float alpha){} }
class FileLog { static void e(Exception e){} }
class Base {
 float nativeY; boolean failNative;
 protected void onDraw(Canvas c) { nativeY=c.y; if(failNative) throw new IllegalStateException(); }
 Paint getPaint(){return new Paint();} int length(){return 1;}
 Layout getLayout(){return new Layout();} int getHeight(){return 100;} int dp(int n){return n;}
}
class TextAnim {
 static int before,after; static float overlayY; static boolean fail;
 static void beforeEditorDraw(Object v){before++;}
 static void afterEditorDraw(Object v,Canvas c){after++; overlayY=c.y; if(fail)throw new IllegalStateException();}
}
public class TextOffsetTest extends Base {
 float offsetY,xOffset,yOffset; int userNameLength,hintColor;
 Layout captionLayout; Text rightText;
 ${method}
 static void check(boolean v){if(!v)throw new AssertionError();}
 public static void main(String[] args){
  TextOffsetTest v=new TextOffsetTest(); Canvas c=new Canvas();
  for(int i=0;i<1000;i++){
   v.offsetY=(i%81)-40.5f; c.y=-(i%13)*32;
   float original=c.y; v.onDraw(c);
   check(v.nativeY==TextAnim.overlayY && c.y==original && c.saves.isEmpty());
  }
  for(int mode=0;mode<2;mode++){
   v.failNative=mode==0; TextAnim.fail=mode==1;
   float original=c.y; int before=TextAnim.before,after=TextAnim.after;
   try{v.onDraw(c);throw new AssertionError();}catch(IllegalStateException expected){}
   check(c.y==original && c.saves.isEmpty());
   check(TextAnim.before==before+1 && TextAnim.after==after+1);
  }
  System.out.println("PASS: 1000 rapid line-offset/scroll changes; native and animated text aligned; canvas and callbacks balanced on failure");
 }
}`;
const dir=fs.mkdtempSync(path.join(os.tmpdir(), 'text-offset-'));
fs.writeFileSync(path.join(dir,'TextOffsetTest.java'),java);
cp.execFileSync('javac',['TextOffsetTest.java'],{cwd:dir,stdio:'inherit'});
cp.execFileSync('java',['TextOffsetTest'],{cwd:dir,stdio:'inherit'});
