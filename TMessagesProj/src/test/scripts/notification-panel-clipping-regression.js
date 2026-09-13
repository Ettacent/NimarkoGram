const fs = require('node:fs'), path = require('node:path'), os = require('node:os');
const cp = require('node:child_process'), assert = require('node:assert/strict');
const read = name => fs.readFileSync(path.resolve(__dirname, '../../main/java', name), 'utf8');
function method(source, signature) {
    const start = source.indexOf(signature); assert(start >= 0, signature);
    let end = source.indexOf('{', start), depth = 1;
    while (depth && ++end < source.length) { if (source[end] === '{') depth++; if (source[end] === '}') depth--; }
    return source.slice(start, end + 1).replaceAll('@NonNull ', '');
}
const chat = read('org/telegram/ui/Components/ChatActivityTopPanelLayout.java');
const dialogs = read('org/telegram/ui/Components/DialogsActivityTopPanelLayout.java');
const notifications = read('app/nimarkogram/messenger/notifications/NimarkoInAppNotifications.java');
for (const source of [chat, dialogs]) {
    assert.match(source, /setClipChildren\(false\)/);
    assert.match(source, /setClipToPadding\(false\)/);
    assert.match(source, /canvas.restore\(\);\s*super.dispatchDraw\(canvas\)/);
}
const java = `
class View {boolean call;int top,height,width=400;int getWidth(){return width;}int getHeight(){return height;}void getLocationOnScreen(int[] a){a[1]=top;}}
class Rect {int bottom;boolean isEmpty(){return bottom<=0;}}
class WindowInsetsCompat {
 int bottom;WindowInsetsCompat getInsets(int type){return this;}
 static class Type {static int ime(){return 1;}static int systemBars(){return 2;}}
}
class ViewCompat {static WindowInsetsCompat insets=new WindowInsetsCompat();static WindowInsetsCompat getRootWindowInsets(View v){return insets;}}
class Viewport extends View {
 View root=new View(),slot=new View();Rect visibleFrame=new Rect();int reportedBottom;int[] viewportLocation=new int[2];
 int viewportWidth=-1,viewportHeight,viewportTop,viewportInset,frameReads;
 View getRootView(){return root;}int dp(int value){return value;}
 void getWindowVisibleDisplayFrame(Rect out){frameReads++;out.bottom=reportedBottom;}
 ${method(notifications, 'int availableHeight()')}
}
interface IndependentPanel {}
class Slot extends View implements IndependentPanel {}
class Path {float bottom=20;}
class Canvas {
 float bottom=1000,saved;int saves;
 int save(){saved=bottom;saves++;return saves;}
 void clipPath(Path path){bottom=Math.min(bottom,path.bottom);}
 void clipRect(int l,int t,int r,int b){bottom=Math.min(bottom,b);}
 void restoreToCount(int n){bottom=saved;}
}
class Base {
 int height=260;float drawnBottom;
 int getHeight(){return height;}int getWidth(){return 400;}
 protected boolean drawChild(Canvas c,View child,long time){drawnBottom=c.bottom;return true;}
}
class Chat extends Base {Path clipPath=new Path();
 ${method(chat, 'protected boolean drawChild(')}
}
class Dialogs extends Base {Path clipPath=new Path();boolean exceptCall,onlyCall;
 boolean isCallView(View child){return child.call;}
 ${method(dialogs, 'protected boolean drawChild(')}
}
public class NotificationPanelClipTest {
 static int checks;static void check(boolean b,String reason){checks++;if(!b)throw new AssertionError(reason);}
 public static void main(String[] args){
  Viewport viewport=new Viewport();viewport.root.height=1000;viewport.slot.top=80;viewport.reportedBottom=700;ViewCompat.insets.bottom=300;
  check(viewport.availableHeight()==556,"IME visible frame limits expansion below anchor");
  for(int i=0;i<120;i++)viewport.availableHeight();
  check(viewport.frameReads==1,"gesture frames reuse window bounds without repeated system queries");
  ViewCompat.insets.bottom=200;viewport.reportedBottom=800;
  check(viewport.availableHeight()==656&&viewport.frameReads==2,"IME changes invalidate cached bounds");
  ViewCompat.insets.bottom=300;viewport.reportedBottom=700;
  viewport.root.height=700;check(viewport.availableHeight()==556,"resized window must not subtract keyboard twice");
  viewport.root.height=1000;viewport.reportedBottom=0;check(viewport.availableHeight()==556,"insets fallback handles unavailable visible frame");
  viewport.slot.top=650;check(viewport.availableHeight()==68,"tiny viewport retains compact lower bound");
  for(int h:new int[]{68,100,180,260,500}){
   Canvas canvas=new Canvas();Chat chat=new Chat();chat.height=h;
   check(chat.drawChild(canvas,new Slot(),0)&&chat.drawnBottom==h,"notification uses physical height, not trailing shared clip");
   check(canvas.bottom==1000,"notification clip does not leak to siblings");
   chat.drawChild(canvas,new View(),0);check(chat.drawnBottom==20,"native panels retain original rounded clip");
   Dialogs dialogs=new Dialogs();dialogs.height=h;
   dialogs.drawChild(canvas,new Slot(),0);check(dialogs.drawnBottom==h,"dialogs notification has independent clipping");
   dialogs.drawChild(canvas,new View(),0);check(dialogs.drawnBottom==20,"dialogs native panel clipping retained");
   View call=new View();call.call=true;dialogs.exceptCall=true;
   check(!dialogs.drawChild(canvas,call,0),"first pass excludes separately drawn call");
   dialogs.exceptCall=false;dialogs.onlyCall=true;
   check(!dialogs.drawChild(canvas,new Slot(),0),"call pass cannot duplicate notification");
   check(dialogs.drawChild(canvas,call,0)&&dialogs.drawnBottom==1000,"call blob keeps original unclipped pass");
  }
  System.out.println("PASS: "+checks+" extracted native clipping checks; independent notifications, native panels and call pass");
 }
}`;
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-notification-clip-'));
try {
    function run(code) {
        fs.writeFileSync(path.join(dir, 'NotificationPanelClipTest.java'), code);
        const built = cp.spawnSync('javac', ['NotificationPanelClipTest.java'], {cwd:dir, encoding:'utf8'});
        assert.equal(built.status, 0, built.stderr);
        return cp.spawnSync('java', ['NotificationPanelClipTest'], {cwd:dir, encoding:'utf8'});
    }
    const result = run(java); assert.equal(result.status, 0, result.stderr); process.stdout.write(result.stdout);
    const broken = java.replaceAll('if (!(child instanceof IndependentPanel))', 'if (true)');
    assert.notEqual(broken, java); const negative = run(broken);
    assert.notEqual(negative.status, 0); assert.match(negative.stderr, /notification uses physical height/);
    console.log('PASS: shared clipping negative control; host tests do not prove Android rendering');
} finally {fs.rmSync(dir, {recursive:true, force:true});}
