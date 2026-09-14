const fs = require('node:fs'), path = require('node:path'), os = require('node:os');
const cp = require('node:child_process'), assert = require('node:assert/strict');
const base = path.resolve(__dirname, '../../main/java/app/nimarkogram/messenger/notifications');
const read = name => fs.readFileSync(path.join(base, name + '.java'), 'utf8');
function method(source, signature) {
    const start = source.indexOf(signature); assert(start >= 0, signature);
    let end = source.indexOf('{', start), depth = 1;
    while (depth && ++end < source.length) { if (source[end] === '{') depth++; if (source[end] === '}') depth--; }
    return source.slice(start, end + 1);
}
const placement = read('ProfileNotificationPlacement').replace(/^package .*;\n/m, '').replace(/^import .*;\n/gm, '').replace('public final class', 'final class');
const notices = read('NimarkoInAppNotifications'), panel = read('NotificationInlinePanel');
const java = `import java.util.*; import java.util.function.*;
class Rect {int left,top,right,bottom;void set(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;}}
class View {static final int GONE=8;int top=1000,height=6,position,visibility;float y;
 interface OnLayoutChangeListener {void onLayoutChange(View v,int l,int t,int r,int b,int ol,int ot,int or,int ob);}
 static class MeasureSpec {static final int EXACTLY=0x40000000;static int makeMeasureSpec(int size,int mode){return size|mode;}}
 int getBottom(){return top+height;}int getVisibility(){return visibility;}int getMeasuredHeight(){return height;}}
class RecyclerView extends View {
 static class ItemDecoration {public void getItemOffsets(Rect r,View v,RecyclerView p,State s){}}
 static class State {}
 boolean shown=true,computing,pending,layoutRequested;int invalidations,removals;int paddingTop=1200;
 float y=84;Set<Runnable> callbacks=new HashSet<>();ItemDecoration decoration;
 View.OnLayoutChangeListener listener;int layoutPasses;
 void addOnLayoutChangeListener(View.OnLayoutChangeListener l){listener=l;}void removeOnLayoutChangeListener(View.OnLayoutChangeListener l){listener=null;}
 int getMeasuredWidth(){return 1080;}int getMeasuredHeight(){return 2000;}
 int getLeft(){return 0;}int getTop(){return 84;}int getRight(){return 1080;}int getBottom(){return 2084;}
 void measure(int w,int h){if(w!=(1080|View.MeasureSpec.EXACTLY)||h!=(2000|View.MeasureSpec.EXACTLY))throw new AssertionError("viewport resized");}
 void layout(int l,int t,int r,int b){if(l!=0||t!=84||r!=1080||b!=2084)throw new AssertionError("viewport moved");layoutPasses++;finishLayout();}
 void addItemDecoration(ItemDecoration d){decoration=d;layoutRequested=true;}
 void removeItemDecoration(ItemDecoration d){removals++;if(decoration==d)decoration=null;}
 void removeCallbacks(Runnable r){callbacks.remove(r);}void postOnAnimation(Runnable r){callbacks.add(r);}void post(Runnable r){callbacks.add(r);}
 boolean isShown(){return shown;}boolean isComputingLayout(){return computing;}boolean hasPendingAdapterUpdates(){return pending;}
 boolean isLayoutRequested(){return layoutRequested;}int getPaddingTop(){return paddingTop;}float getY(){return y;}
 int getChildAdapterPosition(View v){return v.position;}
 void invalidateItemDecorations(){if(computing)throw new AssertionError("mutation during layout");layoutRequested=true;invalidations++;}
 void finishLayout(){layoutRequested=false;pending=false;if(listener!=null)listener.onLayoutChange(this,0,84,1080,2084,0,84,1080,2084);}void frame(){var copy=new ArrayList<>(callbacks);callbacks.clear();for(var r:copy)r.run();}
}
class LinearLayoutManager {
 Map<Integer,View> rows=new HashMap<>();boolean pending,smooth;int scrollCalls,scrollPosition,scrollOffset;
 View findViewByPosition(int position){return rows.get(position);}int getDecoratedTop(View view){return view.top;}
 boolean hasPendingScrollPosition(){return pending;}boolean isSmoothScrolling(){return smooth;}
 void scrollToPositionWithOffset(int p,int o){scrollCalls++;scrollPosition=p;scrollOffset=o;}
}
${placement}
class NotificationInlinePanel {
 ${method(panel, 'public interface CompactContent')}
 static class Holder {CompactContent view;Holder(CompactContent v){view=v;}}
 static class Entry {Holder item;float visibility=1;Entry(CompactContent v){item=new Holder(v);}float getVisibility(){return visibility;}}
 List<Entry> entries=new ArrayList<>();int height;IntConsumer reservationListener=value->height=value;
 int getPaddingTop(){return 4;}int getPaddingBottom(){return 4;}int getEntriesCount(){return entries.size();}Entry getEntry(int i){return entries.get(i);}
 ${method(panel, 'private void updateCompactReservation()').replace('private void', 'void')}
}
class Banner extends View {int collapsedHeight;float pullOffset;}
class Slot extends View implements NotificationInlinePanel.CompactContent {
 List<Banner> children=new ArrayList<>();int retainedCompactHeight;float retainedCompactVisibleHeight;
 int getChildCount(){return children.size();}View getChildAt(int i){return children.get(i);}
 ${method(notices, 'public int getCompactHeight()')}
 ${method(notices, 'public float getCompactVisibleHeight()')}
}
public class PlacementTest {
 static int checks;
 static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
 public static void main(String[] args){
  Slot slot=new Slot();Banner banner=new Banner();slot.children.add(banner);
  NotificationInlinePanel panel=new NotificationInlinePanel();var entry=new NotificationInlinePanel.Entry(slot);panel.entries.add(entry);
  for(int compact:new int[]{48,68,96,144}){
   banner.collapsedHeight=compact;
   for(int expanded=compact;expanded<=320;expanded+=7){
    banner.height=expanded;
    for(int pull=-expanded;pull<=60;pull++){
     banner.pullOffset=pull;panel.updateCompactReservation();
     float visible=Math.min(compact,Math.max(0,expanded+pull));
     check(panel.height==Math.round(visible+8*(visible/compact)),"compact reservation follows only the remaining visible compact area");
     if(pull>=0)check(panel.height==compact+8,"expansion and overscroll never enlarge profile reservation");
     if(expanded+pull<=0)check(panel.height==0,"no empty slot once the card has left");
    }
   }
  }
  banner.height=banner.collapsedHeight=68;banner.pullOffset=0;
  for(int i=0;i<=120;i++){entry.visibility=i/120f;panel.updateCompactReservation();check(panel.height==Math.round(76*i/120f),"entrance and removal use the panel visibility");}
  Banner replacement=new Banner();replacement.height=replacement.collapsedHeight=96;slot.children.add(replacement);
  panel.updateCompactReservation();check(panel.height==104,"replacement reserves maximum height, not two cards");
  replacement.visibility=View.GONE;panel.updateCompactReservation();check(panel.height==76,"gone replacement reserves nothing");
  slot.retainedCompactHeight=68;slot.retainedCompactVisibleHeight=20;slot.children.clear();entry.visibility=.5f;panel.updateCompactReservation();
  check(panel.height==Math.round(.5f*(20+8*20f/68)),"retiring empty slot preserves fractional coverage");

  RecyclerView list=new RecyclerView();LinearLayoutManager layout=new LinearLayoutManager();View first=new View();layout.rows.put(0,first);
  int[] row={0};var place=new ProfileNotificationPlacement(list,layout,()->row[0]);list.finishLayout();Rect offsets=new Rect();
  for(int repeat=0;repeat<4;repeat++)for(int height=0;height<=300;height++){
   int reserve=repeat%2==0?height:300-height;place.setReservedHeight(reserve);
   place.getItemOffsets(offsets,first,list,new RecyclerView.State());
   check(offsets.top==0&&offsets.bottom==reserve,"notification space must be AFTER the native anchor, never above it");
   check(first.top==1000&&first.height==6&&list.paddingTop==1200,"header geometry is invariant");
   check(place.getAnchorBottom(84)==1090,"notification follows actual row bottom without counting its own reservation");
   place.prepareForDraw();check(!list.layoutRequested,"late reservation layout is finished before drawing");
   int passes=list.layoutPasses;place.prepareForDraw();check(list.layoutPasses==passes,"stable placement never lays out repeatedly");
  }
  int calls=layout.scrollCalls;layout.pending=true;place.setReservedHeight(90);
  check(layout.scrollCalls==calls,"do not overwrite a native pending scroll target");layout.pending=false;layout.smooth=true;place.setReservedHeight(91);
  check(layout.scrollCalls==calls,"do not interrupt scrolling to gifts or groups");layout.smooth=false;list.finishLayout();
  list.computing=true;place.setReservedHeight(92);place.setReservedHeight(93);
  check(list.callbacks.size()==1,"coalesce mutations during layout");int passes=list.layoutPasses;place.prepareForDraw();check(list.layoutPasses==passes,"no reentrant layout");
  list.computing=false;list.frame();list.finishLayout();place.getItemOffsets(offsets,first,list,new RecyclerView.State());check(offsets.bottom==93,"apply latest pending height");
  list.pending=true;place.setReservedHeight(94);check(list.callbacks.size()==1,"wait for adapter updates");list.pending=false;list.frame();list.finishLayout();
  list.shown=false;place.setReservedHeight(95);check(list.callbacks.isEmpty(),"hidden profile cannot block drawing or spin retries");
  list.shown=true;place.setReservedHeight(95);list.finishLayout();place.getItemOffsets(offsets,first,list,new RecyclerView.State());check(offsets.bottom==95,"resume reservation when visible");
  int previous=Integer.MAX_VALUE;
  for(int top=1000;top>=-1000;top--){first.top=top;int anchor=Math.max(92,place.getAnchorBottom(84));
   if(previous!=Integer.MAX_VALUE)check(previous-anchor>=0&&previous-anchor<=1,"sticky handoff is continuous and monotonic");previous=anchor;}
  layout.rows.clear();check(place.getAnchorBottom(84)==84,"recycled anchor falls back to toolbar");
  row[0]=-1;place.getItemOffsets(offsets,first,list,new RecyclerView.State());check(offsets.bottom==0,"missing anchor cannot reserve an unrelated row");
  list.computing=true;place.setReservedHeight(100);place.release();list.computing=false;list.frame();
  check(list.decoration==null&&list.removals==1&&list.callbacks.isEmpty(),"release cancels pending writes and removes decoration once");
  place.setReservedHeight(200);place.prepareForDraw();place.release();check(list.callbacks.isEmpty()&&list.listener==null&&list.removals==1,"released owner cannot restart layout work");
  System.out.println("PASS: "+checks+" extracted-method placement checks (not device rendering tests)");
 }
}
`;
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-profile-placement-'));
try {
    fs.writeFileSync(path.join(tmp, 'PlacementTest.java'), java);
    cp.execFileSync('javac', ['PlacementTest.java'], {cwd:tmp,stdio:'pipe'});
    process.stdout.write(cp.execFileSync('java', ['PlacementTest'], {cwd:tmp,encoding:'utf8'}));
    const mutants = [
        ['outRect.bottom = reservedHeight;', 'outRect.top = reservedHeight;'],
        ['Math.round(list.getY()) + anchor.getBottom()', 'Math.round(list.getY()) + anchor.getBottom() + reservedHeight'],
        ['Math.min(child.collapsedHeight,', 'Math.min(child.getMeasuredHeight(),'],
    ];
    for (const [from,to] of mutants) {
        assert(java.includes(from), from);
        fs.writeFileSync(path.join(tmp, 'PlacementTest.java'), java.replace(from,to));
        cp.execFileSync('javac', ['PlacementTest.java'], {cwd:tmp,stdio:'pipe'});
        const run=cp.spawnSync('java',['PlacementTest'],{cwd:tmp,encoding:'utf8'});
        assert.notEqual(run.status,0,'negative control must fail: '+to);
        assert.match(run.stderr,/AssertionError/);
    }
    console.log('PASS: 3 negative controls reject header displacement, feedback and expansion-sized reservation');
} finally {fs.rmSync(tmp,{recursive:true,force:true});}
