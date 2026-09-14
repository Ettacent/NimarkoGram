const fs = require('node:fs'), path = require('node:path'), os = require('node:os');
const cp = require('node:child_process'), assert = require('node:assert/strict');
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'notification-inset-'));
const source = fs.readFileSync(path.resolve(__dirname, '../../main/java/app/nimarkogram/messenger/notifications/NotificationListInset.java'), 'utf8').replace(/^package .*;|^import .*;/gm, '');
const java = `
class FrameLayout {static class LayoutParams {int topMargin=20;}}
class View {}
class LinearLayoutManager {
 int offset,position=-1; View first=new View();
 int getOrientation(){return 1;}boolean getReverseLayout(){return false;}boolean getStackFromEnd(){return false;}
 boolean isSmoothScrolling(){return false;}int findFirstVisibleItemPosition(){return 3;}
 View findViewByPosition(int p){return first;}int getDecoratedTop(View v){return 7;}
 void scrollToPositionWithOffset(int p,int o){position=p;offset=o;}
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
  inset.apply(80,0,1);check(list.manager.position==3&&list.manager.offset==-5);
  inset.apply(90,0,1);check(list.manager.offset==-5);
  list.pending=false;inset.release();check(list.manager.offset==7-102&&list.top==12);
  System.out.println("PASS: list viewport preserved, rounded edges remain backed by scrolling content, repeated frames, expansion, insets and release");
 }
}
`;
try {
 fs.writeFileSync(path.join(dir, 'InsetTest.java'), java);
 cp.execFileSync('javac', [path.join(dir, 'InsetTest.java')]);
 cp.execFileSync('java', ['-cp', dir, 'InsetTest'], {stdio:'inherit'});
 assert(!source.includes('setBackground'));
} finally {fs.rmSync(dir, {recursive:true,force:true});}
