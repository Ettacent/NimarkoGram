const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const cp = require('node:child_process');
const assert = require('node:assert/strict');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/java/org/telegram/ui/Cells/ChatMessageCell.java'), 'utf8');
function block(marker, from = 0) {
    const start = source.indexOf(marker, from);
    assert(start >= 0, marker);
    const tokens = /\/\/[^\n]*|\/\*[\s\S]*?\*\/|"(?:\\.|[^"\\])*"|[{}]/g;
    tokens.lastIndex = source.indexOf('{', start);
    let depth = 0;
    for (let t; (t = tokens.exec(source));) {
        if (t[0] === '{') depth++;
        if (t[0] === '}' && --depth === 0) return source.slice(start, tokens.lastIndex);
    }
    throw new Error(marker);
}
const update = block('public void updatePath()', source.indexOf('public void createSelectorDrawable('));
const touch = block('if (selectorDrawable[1] != null)', source.indexOf('commentButtonPressed = true;'));
assert(touch.indexOf('setBounds(commentButtonRect)') < touch.indexOf('setHotspot(x, y)'));
assert(touch.includes('selectorMaskDrawable[1].invalidateSelf()'));
const java = `import java.awt.geom.*;
public class CommentMaskTest {
 static class RectF {
  float left,top,right,bottom;
  void set(float l,float t,float r,float b){left=l;top=t;right=r;bottom=b;}
  float centerX(){return (left+right)/2;}float centerY(){return(top+bottom)/2;}
 }
 static class android {static class graphics {static class Rect {int left,top,right,bottom;}}}
 static class AndroidUtilities {static RectF rectTmp=new RectF();}
 static class SharedConfig {static int bubbleRadius;}
 static class Message {boolean out;boolean isOutOwner(){return out;}}
 static class Path {
  enum Direction {CW}Path2D.Float p=new Path2D.Float();
  void rewind(){p.reset();}void moveTo(float x,float y){p.moveTo(x,y);}void lineTo(float x,float y){p.lineTo(x,y);}
  void close(){p.closePath();}
  void arcTo(RectF r,float start,float sweep,boolean force){
   if(r.right<=r.left||r.bottom<=r.top)return;
   p.append(new Arc2D.Float(r.left,r.top,r.right-r.left,r.bottom-r.top,-start,-sweep,Arc2D.OPEN),!force);
  }
  void addCircle(float x,float y,float r,Direction d){p.append(new Ellipse2D.Float(x-r,y-r,2*r,2*r),false);}
  void addRoundRect(RectF r,float[] radii,Direction d){addRoundRect(r,radii[4],radii[5],d);}
  void addRoundRect(RectF r,float rx,float ry,Direction d){p.append(new RoundRectangle2D.Float(r.left,r.top,r.right-r.left,r.bottom-r.top,2*rx,2*ry),false);}
 }
 static float density;static int dp(float x){return (int)Math.ceil(x*density);}
 int num,pathX,pathY;int[] selectorDrawableMaskType={2,2};
 boolean instantTextNewLine,mediaBackground,pinnedBottom,drawPinnedBottom,pollInstantViewTouchesBottom;
 Object currentPosition;Message currentMessageObject=new Message();
 RectF rect=new RectF();Path path=new Path();float[] radii=new float[8];
 android.graphics.Rect bounds=new android.graphics.Rect();
 android.graphics.Rect getBounds(){return bounds;}
 ${update}
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 public static void main(String[] args){
  int cases=0;
  for(float d:new float[]{1,1.5f,2,3,4}){
   density=d;CommentMaskTest cell=new CommentMaskTest();
   for(int radius:new int[]{0,4,12,17})for(int slot:new int[]{0,1})
    for(boolean newline:new boolean[]{false,true})for(boolean out:new boolean[]{false,true})
     for(boolean pinned:new boolean[]{false,true})for(boolean grouped:new boolean[]{false,true}){
      SharedConfig.bubbleRadius=radius;cell.num=slot;cell.instantTextNewLine=newline;
      cell.currentMessageObject.out=out;cell.pinnedBottom=cell.drawPinnedBottom=pinned;
      cell.currentPosition=grouped?new Object():null;
      for(int y:new int[]{100,500,130}){
       cell.bounds.left=dp(7);cell.bounds.right=dp(267);cell.bounds.top=dp(y);cell.bounds.bottom=dp(y+43);
       cell.updatePath();
       for(int x:new int[]{45,100,150,200})for(int dy:new int[]{5,20,35})
        check(cell.path.p.contains(dp(x),dp(y+dy)),"comment mask clipped diagonally: slot="+slot+" radius="+radius+" newline="+newline);
       if(slot==1&&!out&&!pinned)check(cell.radii[4]==dp(radius),"comments inherited poll newline flag");
       check(!cell.path.p.contains(dp(100),dp(y-10)),"stale mask after reuse");
       cases++;
      }
     }
  }
  System.out.println("PASS: "+cases+" selector masks; both slots, square/rounded, poll flags, rebind bounds, incoming/outgoing, pinned and albums");
 }
}`;
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-comment-mask-'));
try {
    const file = path.join(dir, 'CommentMaskTest.java');
    const run = code => {
        fs.writeFileSync(file, code);
        cp.execFileSync('javac', [file]);
        return cp.spawnSync('java', ['-Djava.awt.headless=true', '-cp', dir, 'CommentMaskTest'], {encoding:'utf8'});
    };
    const result = run(java);
    assert.equal(result.status, 0, result.stderr);
    console.log(result.stdout.trim());
    const broken = java.replace('path.lineTo(rect.right, rect.bottom);', '');
    assert.notEqual(broken, java);
    const negative = run(broken);
    assert.notEqual(negative.status, 0);
    assert(negative.stderr.includes('clipped diagonally'));
    const stale = run(java.replace('num == 1 || !instantTextNewLine', '!instantTextNewLine'));
    assert.notEqual(stale.status, 0);
    assert(stale.stderr.includes('inherited poll newline flag'));
    console.log('PASS: missing square corner and leaked poll state both fail negative controls; Android ripple rendering still needs device verification');
} finally {
    fs.rmSync(dir, {recursive:true, force:true});
}
