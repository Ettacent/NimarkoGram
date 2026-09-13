const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const cp = require('node:child_process');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../main/java/org/telegram/ui/Components/Reactions');
const source = fs.readFileSync(path.join(root, 'ReactionsLayoutInBubble.java'), 'utf8');
const overlay = fs.readFileSync(path.join(root, 'ReactionsEffectOverlay.java'), 'utf8');
function block(text, marker) {
    const start = text.indexOf(marker);
    assert(start >= 0, marker);
    const tokens = /\/\/[^\n]*|\/\*[\s\S]*?\*\/|"(?:\\.|[^"\\])*"|[{}]/g;
    tokens.lastIndex = text.indexOf('{', start);
    let depth = 0;
    for (let t; (t = tokens.exec(text));) {
        if (t[0] === '{') depth++;
        if (t[0] === '}' && --depth === 0) return text.slice(start, tokens.lastIndex);
    }
    throw new Error(marker);
}
const draw = block(source, 'public void draw(Canvas canvas, float x, float y,');
const prefix = draw.slice(0, draw.indexOf('            if (choosen)')) + '\n largeBranch++; }';
const start = block(overlay, 'else if (reactionButton != null)').replace(/^else /, '');
const destination = block(overlay.slice(overlay.indexOf('toX = loc[0];')), 'if (reactionButton != null)');
assert(draw.indexOf('updateImageBounds(x, y);') < draw.indexOf('getImageReceiver()'));
assert(draw.includes('drawImage(canvas, drawingImageRect, alpha);'));
const java = `
public class ReactionOriginTest {
 static float density=1;static int dp(int n){return (int)Math.ceil(n*density);}
 static class AndroidUtilities{static int dp(int n){return ReactionOriginTest.dp(n);}}
 static class Rect{int left,top,right,bottom;void set(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;}
  int height(){return bottom-top;}int width(){return right-left;}boolean isEmpty(){return width()<=0||height()<=0;}}
 static class ImageReceiver{void setAlpha(float a){}void setRoundRadius(int r){}float getImageX(){return 0;}}
 static class AnimatedEmoji{ImageReceiver receiver;ImageReceiver getImageReceiver(){return receiver;}}
 static class Canvas{}
 static class Cell{int padding;void getLocationInWindow(int[] loc){loc[0]=37;loc[1]=91;}int getPaddingTop(){return padding;}}
 static class ChatMessageCell extends Cell{boolean drawPinnedBottom,onMedia;boolean shouldDrawTimeOnMedia(){return onMedia;}}
 static class Button{
  boolean isSmall,isTag,paid,wasDrawn;int height,largeBranch,imageDraws;
  Rect drawingImageRect=new Rect();ImageReceiver imageReceiver=new ImageReceiver();AnimatedEmoji animatedEmojiDrawable;
  ${block(source, 'private void updateImageBounds(')}
  ${prefix}
  void drawImage(Canvas c,Rect r,float a){imageDraws++;}
 }
 static void check(boolean b,String s){if(!b)throw new AssertionError(s);}
 static void origin(Button reactionButton,Cell cell){
  int[] loc=new int[2];float fromX,fromY,fromHeight;
  ${start} else {throw new AssertionError("missing reaction");}
  java.util.function.DoubleSupplier originSnapshot=()->fromX+fromY+fromHeight;
  check(Double.isFinite(originSnapshot.getAsDouble()),"captured origin");
  float toX=loc[0],toY=loc[1]+cell.getPaddingTop(),toH=dp(20);boolean isStories=false;
  if(cell instanceof ChatMessageCell&&((ChatMessageCell)cell).drawPinnedBottom&&!((ChatMessageCell)cell).shouldDrawTimeOnMedia())toY+=dp(2);
  ${destination}
  check(fromX==toX&&fromY==toY,"overlay origin must match icon, not unused receiver at zero");
  check(fromHeight==toH&&toH>0,"overlay handoff size");
 }
 public static void main(String[] args){int cases=0;
  for(float d:new float[]{1,1.5f,2,2.75f,3,4}){density=d;
   for(boolean small:new boolean[]{false,true})for(boolean tag:new boolean[]{false,true})for(int type=0;type<3;type++){
    Button b=new Button();b.isSmall=small;b.isTag=tag;b.paid=type==2;b.height=dp(small?14:26);
    if(type==1)b.animatedEmojiDrawable=new AnimatedEmoji();
    for(float x:new float[]{-20.5f,0,75.3f,250,740.9f})for(float y:new float[]{-30.4f,0,290.8f,1200}){
     if(b.animatedEmojiDrawable!=null)b.animatedEmojiDrawable.receiver=null;
     b.draw(new Canvas(),x,y,1,1,false,false,0);
     Rect r=b.drawingImageRect;int l=r.left,t=r.top,w=r.width(),h=r.height();
     int size=dp(small?14:type==2?22:type==1?24:20);
     check(w==size&&h==size,"icon bounds must exist before loading and have fixed dimensions");
     check(l==(int)x+(small?0:dp(type==2?4:type==1?6:8)-(tag?dp(2):0)),"icon left inset");
     if(small)check(b.largeBranch==0,"unloaded compact reaction must not become a large button");
     for(int host=0;host<5;host++){
      Cell cell=host==0?new Cell():new ChatMessageCell();cell.padding=host*7;
      if(cell instanceof ChatMessageCell){((ChatMessageCell)cell).drawPinnedBottom=host%2==0;((ChatMessageCell)cell).onMedia=host>=3;}
      origin(b,cell);
     }
     if(b.animatedEmojiDrawable!=null)b.animatedEmojiDrawable.receiver=new ImageReceiver();
     b.draw(new Canvas(),x,y,1,1,false,false,0);
     check(r.left==l&&r.top==t&&r.width()==w&&r.height()==h,"loading must not change reaction geometry");cases++;
    }
   }
  }
  System.out.println("PASS: "+cases+" reaction bounds, cold/warm premium icons, regular/paid/tags/compact and overlay origins");
 }
}`;
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-reaction-origin-'));
try {
    const file = path.join(dir, 'ReactionOriginTest.java');
    const run = code => {
        fs.writeFileSync(file, code);
        cp.execFileSync('javac', [file]);
        return cp.spawnSync('java', ['-cp', dir, 'ReactionOriginTest'], {encoding:'utf8'});
    };
    const result = run(java);
    assert.equal(result.status, 0, result.stderr);
    console.log(result.stdout.trim());
    for (const broken of [
        java.replace('fromX = loc[0] + reactionButton.drawingImageRect.left;', 'fromX = loc[0] + reactionButton.imageReceiver.getImageX();'),
        java.replace('drawingImageRect.set(left, top, left + size, top + size);', 'drawingImageRect.set(left, top, size, size);'),
        java.replace('updateImageBounds(x, y);', 'if (animatedEmojiDrawable == null || animatedEmojiDrawable.getImageReceiver() != null) updateImageBounds(x, y);'),
    ]) {
        assert.notEqual(broken, java);
        const negative = run(broken);
        assert.notEqual(negative.status, 0);
        assert(negative.stderr.includes('AssertionError'), negative.stderr);
    }
    console.log('PASS: unused-receiver origin, malformed bounds and load-dependent geometry fail negative controls');
} finally {
    fs.rmSync(dir, {recursive:true, force:true});
}
