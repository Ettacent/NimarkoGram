const fs = require('node:fs'), path = require('node:path'), os = require('node:os');
const cp = require('node:child_process'), assert = require('node:assert/strict');
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'notification-inset-'));
const source = fs.readFileSync(path.resolve(__dirname, '../../main/java/app/nimarkogram/messenger/notifications/NotificationListInset.java'), 'utf8').replace(/^package .*;|^import .*;/gm, '');
const java = `
class FrameLayout {static class LayoutParams {int topMargin=20;}}
class View {}
class LinearLayoutManager {
 int offset,position=-1,firstPosition=0,decoratedTop=12; boolean pendingScroll; View first=new View();
 boolean hasPendingScrollPosition(){return pendingScroll;}
 int getOrientation(){return 1;}boolean getReverseLayout(){return false;}boolean getStackFromEnd(){return false;}
 boolean isSmoothScrolling(){return false;}int findFirstVisibleItemPosition(){return firstPosition;}
 View findViewByPosition(int p){return first;}int getDecoratedTop(View v){return decoratedTop;}
 void scrollToPositionWithOffset(int p,int o){position=p;offset=o;pendingScroll=true;}
}
class RecyclerView {
 static final int VERTICAL=1,NO_POSITION=-1;boolean pending;
 LinearLayoutManager manager=new LinearLayoutManager();
 boolean isLayoutRequested(){return pending;}boolean hasPendingAdapterUpdates(){return false;}
 boolean isComputingLayout(){return false;}Object getLayoutManager(){return manager;}
 int top=12,bottom=24,left=8,right=8,updates; boolean clip=true;
 FrameLayout.LayoutParams params=new FrameLayout.LayoutParams();
 Object getLayoutParams(){return params;} int getPaddingTop(){return top;}
 int getPaddingBottom(){return bottom;} int getPaddingLeft(){return left;} int getPaddingRight(){return right;}
 boolean getClipToPadding(){return clip;} void setClipToPadding(boolean v){clip=v;}
 void setPadding(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;updates++;pending=true;}
}
${source}
public class InsetTest {
 static void check(boolean ok){if(!ok)throw new AssertionError();}
 public static void main(String[] args){
  for(boolean clip:new boolean[]{true,false}) for(int anchor:new int[]{0,56,80}){
   RecyclerView list=new RecyclerView();list.clip=clip;NotificationListInset inset=new NotificationListInset(list);
   for(int cycle=0;cycle<5;cycle++)for(int i=0;i<=120;i++){
    float f=i<=60?i/60f:(120-i)/60f;int height=Math.round(90*f);
    inset.apply(height,anchor,f);
    check(list.top==12+height+Math.round(Math.max(0,anchor-32)*f));
    check(list.params.topMargin==20 && list.bottom==24 && list.left==8 && list.right==8);
    check(list.clip==(f==0?clip:false));
    int updates=list.updates;inset.apply(height,anchor,f);check(list.updates==updates);
   }
   inset.apply(90,anchor,1);list.bottom=47;inset.release();check(list.top==12&&list.bottom==47&&list.clip==clip);
   inset.apply(90,anchor,1);list.top=30;inset.apply(90,anchor,1);
   inset.release();check(list.top==30&&list.params.topMargin==20&&list.clip==clip);
  }
  RecyclerView list=new RecyclerView();NotificationListInset inset=new NotificationListInset(list);
  inset.apply(80,0,1);check(list.manager.position==0&&list.manager.offset==0&&list.top==92);
  inset.apply(90,0,1);check(list.manager.offset==0&&list.top==102);
  list.pending=false;list.manager.pendingScroll=false;list.manager.decoratedTop=102;inset.release();check(list.manager.offset==0&&list.top==12);
  RecyclerView measuring=new RecyclerView();measuring.pending=true;
  NotificationListInset duringLayout=new NotificationListInset(measuring);
  duringLayout.apply(80,0,1);check(measuring.manager.position==0&&measuring.manager.offset==0&&measuring.top==92);
  duringLayout.apply(90,0,1);check(measuring.manager.position==0&&measuring.manager.offset==0&&measuring.top==102);
  RecyclerView targeted=new RecyclerView();targeted.manager.firstPosition=3;targeted.manager.decoratedTop=-25;
  targeted.manager.pendingScroll=true;targeted.manager.position=9;targeted.manager.offset=27;
  new NotificationListInset(targeted).apply(80,0,1);check(targeted.manager.position==9&&targeted.manager.offset==27);
  for(int first:new int[]{1,3,20}){
   RecyclerView scrolled=new RecyclerView();scrolled.manager.firstPosition=first;scrolled.manager.decoratedTop=-25;
   NotificationListInset floating=new NotificationListInset(scrolled);floating.apply(90,80,1);
   check(scrolled.top==150&&scrolled.manager.position==first&&scrolled.manager.offset==-175&&!scrolled.clip);
   scrolled.manager.pendingScroll=false;scrolled.manager.firstPosition=0;scrolled.manager.decoratedTop=150;
   floating.apply(80,80,1);check(scrolled.top==140&&scrolled.manager.position==0&&scrolled.manager.offset==0);
   floating.apply(0,0,0);check(scrolled.top==12&&scrolled.clip);
  }
  RecyclerView partial=new RecyclerView();partial.manager.decoratedTop=10;
  new NotificationListInset(partial).apply(80,0,1);check(partial.top==92&&partial.manager.position==0&&partial.manager.offset==-82);
  RecyclerView bounded=new RecyclerView();NotificationListInset boundedInset=new NotificationListInset(bounded);
  boundedInset.apply(90,80,1);check(bounded.top==150&&bounded.manager.offset==0);
  bounded.manager.pendingScroll=false;bounded.manager.decoratedTop=100;boundedInset.release();
  check(bounded.top==12&&bounded.manager.offset==0&&bounded.clip);
  System.out.println("PASS: list viewport preserved, rounded edges remain backed by scrolling content, repeated frames, expansion, insets and release");
 }
}
`;
try {
 fs.writeFileSync(path.join(dir, 'InsetTest.java'), java);
 cp.execFileSync('javac', [path.join(dir, 'InsetTest.java')]);
 cp.execFileSync('java', ['-cp', dir, 'InsetTest'], {stdio:'inherit'});
 for (const [label, broken] of [
  ['pending target', java.replace('&& !layout.hasPendingScrollPosition()', '')],
  ['old anchor', java.replace('targetTop - writtenTop', 'top - previousPadding')],
  ['missing reservation', java.replace('int next = height + Math.round', 'int next = 0 * height + Math.round')]
 ]) {
  assert.notEqual(broken, java);
  fs.writeFileSync(path.join(dir, 'InsetTest.java'), broken);
  cp.execFileSync('javac', [path.join(dir, 'InsetTest.java')]);
  const result = cp.spawnSync('java', ['-cp', dir, 'InsetTest'], {encoding:'utf8'});
  assert.notEqual(result.status, 0, `${label} negative control must fail`);
  assert.match(result.stderr, /AssertionError/);
 }
 assert(!source.includes('setBackground'));
} finally {fs.rmSync(dir, {recursive:true,force:true});}
