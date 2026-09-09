const fs = require('node:fs');
const path = require('node:path');
const cp = require('node:child_process');
const os = require('node:os');
const assert = require('node:assert/strict');
const base = path.resolve(__dirname, '../../main/java');
const source = fs.readFileSync(path.join(base, 'org/telegram/messenger/RichMessageLayout.java'), 'utf8');
const cell = fs.readFileSync(path.join(base, 'org/telegram/ui/Cells/ChatMessageCell.java'), 'utf8');
function method(signature) {
    const start = source.indexOf(signature);
    assert(start >= 0, signature);
    const tokens = /\/\/[^\n]*|\/\*[\s\S]*?\*\/|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[{}]/g;
    tokens.lastIndex = source.indexOf('{', start);
    let depth = 0;
    for (let t; (t = tokens.exec(source));) {
        if (t[0] === '{') depth++;
        if (t[0] === '}' && --depth === 0) return source.slice(start, tokens.lastIndex);
    }
    throw Error(signature);
}
assert(cell.includes('if (richViewportChanged || firstVisibleRichBlock != newFirst'));
assert(cell.includes('RichMessageLayout.viewportChanged(childPosition, visibleHeight, position, height)'));
assert(!cell.includes('NimarkoSearchTrace'));
assert(!fs.existsSync(path.join(base, 'app/nimarkogram/messenger/debug/NimarkoSearchTrace.java')));
const java = `import java.util.*;
public class RichViewportTest {
 static float density=1; static int dp(float x){return (int)Math.ceil(x*density);}
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 static class AndroidUtilities{static float lerp(float a,float b,float t){return a+(b-a)*t;}}
 static class Rect{int bottom;}
 static class Canvas {
  static final int ALL_SAVE_FLAG=31;
  float top=-Float.MAX_VALUE,bottom=Float.MAX_VALUE,y; int draws;
  ArrayList<float[]> stack=new ArrayList<>();
  int save(){stack.add(new float[]{top,bottom,y});return stack.size();}
  void restore(){float[] s=stack.remove(stack.size()-1);top=s[0];bottom=s[1];y=s[2];}
  void restoreToCount(int n){while(stack.size()>=n)restore();}
  void translate(float x,float dy){y+=dy;}
  boolean clipRect(float l,float t,float r,float b){top=Math.max(top,t+y);bottom=Math.min(bottom,b+y);return bottom>top;}
  int saveLayerAlpha(float l,float t,float r,float b,int a,int flags){return save();}
 }
 static class ChatMessageCell{
  int childPosition,visibleHeight,textY;boolean fullyDraw;
  int getVisiblePartHeightForDraw(){return fullyDraw?0:visibleHeight;}
  int getVisiblePartPositionForDraw(){return fullyDraw?0:childPosition;}
  static class TransitionParams{float animateChangeProgress;}
 }
 static class RichBlock{
  float currY,prevY;int currH,prevH;boolean currVisible=true,prevVisible=true;
  Rect padding=new Rect();RichDetailsBlock parentDetails;int snapshots;
  int getHeight(){return currH;}
  void drawWithTyping(Canvas c){c.draws++;}
  void snapshot(){snapshots++;prevY=currY;prevH=currH;prevVisible=currVisible;}
 }
 static class RichDetailsBlock extends RichBlock{float animClipTop,animClipBottom;}
 static class RichDetailsEndBlock extends RichBlock{}
 static class CountedBlocks extends ArrayList<RichBlock>{int reads;public RichBlock get(int i){reads++;return super.get(i);}}
 static class Renderer{
  CountedBlocks blocks=new CountedBlocks();int height,padLeft,padRight;
  boolean detailsAnimating,blockquoteAnimating;float detailsAnimationProgress;
  ChatMessageCell cell;float lastTop,lastBottom;boolean lastClip;
  int getMinWidth(){return 900;} void updateTranslationLoading(){}
  void computeDetailsClips(float p){} void computeBlockquoteClips(float p){}
  void drawBackground(Canvas c,ChatMessageCell.TransitionParams p,boolean clip,float top,float bottom){lastClip=clip;lastTop=top;lastBottom=bottom;}
  ${method('private static int viewportBand(')}
  ${method('public static boolean viewportChanged(')}
  ${method('private int getBlockTop(')}
  ${method('private int getBlockBottom(')}
  ${method('public void snapshotForDetailsAnimation(')}
  ${method('private void drawInternal(Canvas canvas, ChatMessageCell.TransitionParams tp)')}
  ${method('private void drawInternal(Canvas canvas, ChatMessageCell.TransitionParams tp, boolean hasClip')}
 }
 static int oldBound(Renderer r,int index,boolean bottom){
  int y=0;for(int i=0;i<r.blocks.size();i++){RichBlock b=r.blocks.get(i);
   if(i==index&&!bottom)return y;
   if(b.currVisible)y+=b.currH;
   if(i==index)return y-(b.currVisible?Math.max(0,b.padding.bottom-dp(4)):0);
  }return r.height;
 }
 public static void main(String[] args){
  Random random=new Random(4471);int checks=0;
  for(float d:new float[]{1,1.5f,2,3,4}){density=d;
   Renderer r=new Renderer();int y=0;
   for(int i=0;i<300;i++){RichBlock b=new RichBlock();b.currVisible=i%4!=0;b.currY=y;
    b.currH=b.currVisible?20+random.nextInt(450):0;b.padding.bottom=random.nextInt(40);y+=b.currH;r.blocks.add(b);}
   r.height=y;
   for(int i=-1;i<=r.blocks.size();i++){check(r.getBlockTop(i,null)==oldBound(r,i,false),"top parity");check(r.getBlockBottom(i,null)==oldBound(r,i,true),"bottom parity");checks+=2;}
   r.blocks.reads=0;for(int i=0;i<300;i++){r.getBlockTop(i,null);r.getBlockBottom(i,null);}
   check(r.blocks.reads==600,"Bounds lookup must stay linear for all quotes");
   r.cell=new ChatMessageCell();r.cell.textY=100;r.cell.visibleHeight=2000;
   for(int pos=-300;pos<30000;pos+=23){r.cell.childPosition=pos;Canvas canvas=new Canvas();r.drawInternal(canvas,null);
    check(r.lastTop<=pos-100&&r.lastBottom>=pos+1900,"Viewport crops visible content");
    check(r.lastBottom-r.lastTop<=2000+4*dp(256),"Unbounded render window");
    check(canvas.stack.isEmpty(),"Canvas save leak");
    float oldTop=r.lastTop,oldBottom=r.lastBottom;
    if(!Renderer.viewportChanged(pos,2000,pos+1,2000)){r.cell.childPosition=pos+1;r.drawInternal(new Canvas(),null);check(oldTop==r.lastTop&&oldBottom==r.lastBottom,"Display list not invalidated");}checks+=3;
   }
   check(r.blocks.get(0).snapshots==0,"Static frames copy animation state");
   r.cell.fullyDraw=true;Canvas export=new Canvas();r.drawInternal(export,null);check(!r.lastClip,"Export must draw full article");
   r.cell.fullyDraw=false;r.cell.childPosition=5000;
   ChatMessageCell.TransitionParams tp=new ChatMessageCell.TransitionParams();
   r.detailsAnimating=true;tp.animateChangeProgress=.5f;r.drawInternal(new Canvas(),tp);check(!r.lastClip,"Transition cropped");
   tp.animateChangeProgress=1;r.drawInternal(new Canvas(),tp);check(r.lastClip,"Viewport not restored");
   check(r.blocks.get(0).snapshots==1,"Transition handoff not saved once");
   r.drawInternal(new Canvas(),tp);check(r.blocks.get(0).snapshots==1,"Repeated handoff");
   RichBlock b=r.blocks.get(3);b.prevY=15;b.prevH=50;b.prevVisible=true;r.blockquoteAnimating=true;
   for(int p=0;p<=120;p++){tp.animateChangeProgress=p/120f;
    check(r.getBlockTop(3,tp)==Math.round(AndroidUtilities.lerp(15,b.currY,tp.animateChangeProgress)),"Animated quote top");checks++;
   }
  }
  System.out.println("PASS: "+checks+" article geometry/viewport checks, O(1) bounds, export, scroll invalidation, transition handoff");
 }
}`;
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-rich-viewport-'));
fs.writeFileSync(path.join(dir, 'RichViewportTest.java'), java);
cp.execFileSync('javac', [path.join(dir, 'RichViewportTest.java')], {stdio:'inherit'});
cp.execFileSync('java', ['-cp', dir, 'RichViewportTest'], {stdio:'inherit'});
