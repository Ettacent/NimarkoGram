const fs = require('node:fs'), path = require('node:path'), os = require('node:os');
const cp = require('node:child_process'), assert = require('node:assert/strict');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/java/org/telegram/ui/ProfileActivity.java'), 'utf8');
function method(marker) {
    const start = source.indexOf(marker);
    assert(start >= 0);
    let end = source.indexOf('{', start), depth = 1;
    while (depth && ++end < source.length) {
        if (source[end] === '{') depth++;
        if (source[end] === '}') depth--;
    }
    return source.slice(start, end + 1);
}
const save = method('private void saveScrollPosition()');
const ready = method('private void resumeDelayedFragmentAnimationAfterLayout()');
const java = `import java.util.*;
public class ProfileAnchorTest {
 static class View {int top, position; View(int p,int t){position=p;top=t;} int getTop(){return top;}}
 static class RecyclerListView {
  static final int NO_POSITION=-1; int padding=226; List<View> children=new ArrayList<>(); ArrayDeque<Runnable> callbacks=new ArrayDeque<>();
  int getChildCount(){return children.size();} View getChildAt(int i){return children.get(i);}
  int getChildAdapterPosition(View v){return v.position;} int getPaddingTop(){return padding;}
  int getPaddingBottom(){return 0;} void setPadding(int l,int t,int r,int b){padding=t;}
  boolean isComputingLayout(){return false;} void stopScroll(){} void requestLayout(){}
  void post(Runnable r){callbacks.add(r);} void postOnAnimation(Runnable r){callbacks.add(r);}
 }
 static class Layout {
  RecyclerListView list;int position=-1,offset;Layout(RecyclerListView l){list=l;}
  View findViewByPosition(int p){return list.children.stream().filter(v->v.position==p).findFirst().orElse(null);}
  void scrollToPositionWithOffset(int p,int o){position=p;offset=o;}
  void apply(){View v=findViewByPosition(position);if(v!=null)v.top=list.padding+offset;}
 }
 static class Profile {
  RecyclerListView listView=new RecyclerListView(),fragmentView=listView; Layout layoutManager=new Layout(listView);
  int savedScrollPosition=-1,savedScrollOffset,delayedProfileOpenLayoutGeneration,header=226,resumes;
  boolean savedScrollToSharedMedia,allowPullingDown,profileLifecycleDestroyed,fragmentOpened,isFragmentOpened,
   transitionAnimationInProress,openAnimationInProgress,openGifts,openSimilar;
  float extraHeight,initialAnimationExtraHeight;
  int getHeaderExtraHeight(){return header;} void needLayout(boolean b){} void resumeDelayedFragmentAnimation(){resumes++;}
  ${save}
  ${ready}
 }
 static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
 public static void main(String[] args){
  for(int padding:new int[]{226,480,1080})for(int top:new int[]{0,100,226,227,229,400}){
   Profile p=new Profile();p.listView.padding=padding;p.listView.children.add(new View(0,top));p.saveScrollPosition();p.layoutManager.apply();
   check(p.listView.children.get(0).top==Math.min(top,226),"same clamped anchor for immediate/deferred restore");
   check(p.savedScrollOffset==p.listView.children.get(0).top,"no stale out-of-range offset");
   p=new Profile();p.allowPullingDown=true;p.listView.padding=padding;p.listView.children.add(new View(0,top));p.saveScrollPosition();p.layoutManager.apply();
   check(p.listView.children.get(0).top==top,"expanded avatar retained");
  }
  Profile p=new Profile();p.listView.children.add(new View(3,-14));p.saveScrollPosition();p.layoutManager.apply();
  check(p.listView.children.get(0).top==-14,"scrolled profile preserved");
  p=new Profile();p.listView.children.add(new View(0,226));p.resumeDelayedFragmentAnimationAfterLayout();
  p.listView.padding=1080;p.header=251;p.listView.children.get(0).top=253;
  p.fragmentView.callbacks.remove().run();p.layoutManager.apply();
  check(p.listView.children.get(0).top==251,"retry uses live header and padding");
  p.fragmentView.callbacks.remove().run();check(p.resumes==1,"ready without exhausting retries");
  p=new Profile();p.listView.children.add(new View(0,227));p.resumeDelayedFragmentAnimationAfterLayout();
  p.fragmentView.callbacks.remove().run();p.layoutManager.apply();p.fragmentView.callbacks.remove().run();
  check(p.listView.children.get(0).top==226 && p.resumes==1,"one-pixel drift corrected before entry");
  System.out.println("PASS: profile anchors, live padding/header, expanded/scrolled state, exact entry geometry");
 }
}`;
const tmp=fs.mkdtempSync(path.join(os.tmpdir(),'nimarko-profile-anchor-'));
try {
 fs.writeFileSync(path.join(tmp,'ProfileAnchorTest.java'),java);
 cp.execFileSync('javac',['ProfileAnchorTest.java'],{cwd:tmp,stdio:'pipe'});
 process.stdout.write(cp.execFileSync('java',['ProfileAnchorTest'],{cwd:tmp,encoding:'utf8'}));
} finally { fs.rmSync(tmp,{recursive:true,force:true}); }

