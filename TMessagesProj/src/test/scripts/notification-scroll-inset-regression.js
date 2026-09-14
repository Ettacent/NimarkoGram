const fs = require('node:fs'), path = require('node:path'), os = require('node:os');
const cp = require('node:child_process'), assert = require('node:assert/strict');
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'notification-scroll-inset-'));
const source = fs.readFileSync(path.resolve(__dirname, '../../main/java/app/nimarkogram/messenger/notifications/NotificationScrollInset.java'), 'utf8').replace(/^package .*;|^import .*;/gm, '');
const java = `
class ViewGroup {static class MarginLayoutParams {int topMargin=20;}}
class ViewTreeObserver {
 interface OnPreDrawListener {boolean onPreDraw();}
 OnPreDrawListener listener;boolean isAlive(){return true;}
 void addOnPreDrawListener(OnPreDrawListener l){listener=l;}
 void removeOnPreDrawListener(OnPreDrawListener l){if(listener==l)listener=null;}
}
class ScrollView {
 boolean pending;ViewTreeObserver observer=new ViewTreeObserver();
 ViewTreeObserver getViewTreeObserver(){return observer;}boolean isLayoutRequested(){return pending;}
 void frame(){pending=false;if(observer.listener!=null)observer.listener.onPreDraw();}
 int top=12,bottom=24,left=8,right=8,x,scrollY,updates;boolean clip=true;
 ViewGroup.MarginLayoutParams params=new ViewGroup.MarginLayoutParams();
 Object getLayoutParams(){return params;}int getPaddingTop(){return top;}int getPaddingBottom(){return bottom;}
 int getPaddingLeft(){return left;}int getPaddingRight(){return right;}int getScrollX(){return x;}int getScrollY(){return scrollY;}
 boolean getClipToPadding(){return clip;}void setClipToPadding(boolean v){clip=v;}
 void setPadding(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;updates++;pending=true;}
 void scrollTo(int nx,int ny){if(pending)throw new AssertionError("scroll compensated before layout");x=nx;scrollY=ny;}
}
class NotificationSectionsScrollView extends ScrollView {void prepareNotificationInset(){}}
${source}
public class ScrollInsetTest {
 static void check(boolean ok){if(!ok)throw new AssertionError();}
 public static void main(String[] args){
  ScrollView top=new ScrollView();NotificationScrollInset inset=new NotificationScrollInset(top);
  for(int i=0;i<=60;i++){float f=i/60f;int h=Math.round(90*f);inset.apply(h,80,f);top.frame();
   check(top.top==12+h+Math.round(48*f));check(top.scrollY==0&&top.params.topMargin==20&&top.bottom==24&&top.clip==(i==0));}
  top.scrollY=300;inset.apply(80,80,.8f);check(top.scrollY==300);top.frame();check(top.top==130&&top.scrollY==280);
  inset.apply(0,0,0);top.frame();check(top.top==12&&top.scrollY==162&&top.clip);
  ScrollView scrolled=new ScrollView();scrolled.scrollY=300;NotificationScrollInset floating=new NotificationScrollInset(scrolled);
  floating.apply(90,80,1);check(scrolled.scrollY==300);scrolled.frame();check(scrolled.top==150&&scrolled.scrollY==438&&!scrolled.clip);
  scrolled.scrollY=250;floating.apply(70,80,.8f);scrolled.frame();check(scrolled.top==120&&scrolled.scrollY==220);
  scrolled.scrollY=0;floating.apply(50,80,.6f);scrolled.frame();check(scrolled.top==91&&scrolled.scrollY==0);
  floating.release();scrolled.frame();check(scrolled.top==12&&scrolled.bottom==24&&scrolled.clip);
  scrolled.scrollY=300;floating.apply(40,20,1);floating.apply(80,20,1);
  check(scrolled.scrollY==300);scrolled.frame();check(scrolled.scrollY==380);
  System.out.println("PASS: scroll pages keep a real notification inset, preserve a scrolled viewport and expose the reservation at the top");
 }
}`;
try {
 fs.writeFileSync(path.join(dir,'ScrollInsetTest.java'),java);cp.execFileSync('javac',[path.join(dir,'ScrollInsetTest.java')]);
 const ok=cp.spawnSync('java',['-cp',dir,'ScrollInsetTest'],{encoding:'utf8'});assert.equal(ok.status,0,ok.stderr);process.stdout.write(ok.stdout);
 for(const broken of [java.replace('oldScroll + delta','oldScroll'),java.replace('int next = height +','int next = 0 * height +')]){
  fs.writeFileSync(path.join(dir,'ScrollInsetTest.java'),broken);cp.execFileSync('javac',[path.join(dir,'ScrollInsetTest.java')]);
  assert.notEqual(cp.spawnSync('java',['-cp',dir,'ScrollInsetTest']).status,0);
 }
} finally {fs.rmSync(dir,{recursive:true,force:true});}
