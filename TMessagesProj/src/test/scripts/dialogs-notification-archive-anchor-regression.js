const fs = require('node:fs'), path = require('node:path'), os = require('node:os');
const cp = require('node:child_process'), assert = require('node:assert/strict');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/java/org/telegram/ui/DialogsActivity.java'), 'utf8');
const start = source.indexOf('            if (pos != RecyclerView.NO_POSITION && parentPage.itemTouchhelper.isIdle()');
const end = source.indexOf(' else if (pos == RecyclerView.NO_POSITION && firstLayout)', start);
assert(start >= 0 && end > start);
const anchor = source.slice(start, end);
const limitStart = source.indexOf('                                canScrollDy = Math.max(0, canScrollDy);');
const limitEnd = source.indexOf('\n                            }', limitStart);
assert(limitStart >= 0 && limitEnd > limitStart);
const clamp = source.slice(limitStart, limitEnd);
const java = `
class View { int top; View(int t) { top=t; } int getTop() { return top; } }
class RecyclerView {
 static final int NO_POSITION=-1, SCROLL_STATE_DRAGGING=1;
 static class ViewHolder { View itemView; ViewHolder(View v) { itemView=v; } }
}
public class AnchorTest {
 static final int DIALOGS_TYPE_DEFAULT=0, ARCHIVE_ITEM_STATE_HIDDEN=0;
 int lastListPadding=400, scrollAdditionalOffset; boolean ignoreLayout;
 boolean hidden=true; Page parentPage=new Page();
 boolean hasHiddenArchive() { return hidden; }
 int notificationScrollCompensation(int p,int t,int f,int d,int s) { return d; }
 class Page {
  int dialogsType, archivePullViewState, pageAdditionalOffset;
  Layout layoutManager=new Layout(); Touch itemTouchhelper=new Touch(); List listView=new List();
 }
 class Touch { boolean isIdle() { return true; } }
 class Layout {
  int position=-1, offset;
  boolean hasPendingScrollPosition() { return false; }
  View findViewByPosition(int p) { return new View(200+p*72); }
  void scrollToPositionWithOffset(int p,int o) { position=p; offset=o; }
 }
 class List {
  int state;
  int getScrollState() { return state; }
  RecyclerView.ViewHolder findViewHolderForAdapterPosition(int p) {
   return new RecyclerView.ViewHolder(parentPage.layoutManager.findViewByPosition(p));
  }
 }
 void measure(int pos,int notificationDelta) { ${anchor} }
 static int limit(int canScrollDy,int dy) { int measuredDy=dy; ${clamp} return measuredDy; }
 public static void main(String[] args) {
  for(boolean hidden:new boolean[]{false,true}) for(int state:new int[]{0,1,2})
   for(int delta:new int[]{-80,-1,1,80}) for(int pos=0;pos<4;pos++) {
    AnchorTest t=new AnchorTest();t.hidden=hidden;t.parentPage.listView.state=state;t.measure(pos,delta);
    int p=hidden?Math.max(1,pos):pos;
    int actual=t.lastListPadding+delta+t.parentPage.layoutManager.offset;
    if(t.parentPage.layoutManager.position!=p || actual!=200+p*72)
     throw new AssertionError("anchor moved: "+actual+" expected "+(200+p*72));
   }
  for(int room=-200;room<=200;room++) for(int dy=-100;dy<0;dy++) {
   int actual=limit(room,dy);
   if(actual>0 || actual<dy || actual!=-Math.min(Math.max(0,room),-dy))
    throw new AssertionError("reversed scroll: "+actual);
  }
  System.out.println("PASS: archive anchors retain row coordinates during drag/settle and notification resize; stories limit never reverses scroll");
 }
}`;
const dir=fs.mkdtempSync(path.join(os.tmpdir(),'notification-anchor-'));
try {
 fs.writeFileSync(path.join(dir,'AnchorTest.java'),java);
 cp.execFileSync('javac',['AnchorTest.java'],{cwd:dir});
 process.stdout.write(cp.execFileSync('java',['AnchorTest'],{cwd:dir}));
} finally { fs.rmSync(dir,{recursive:true,force:true}); }
