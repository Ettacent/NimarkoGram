const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),cp=require('node:child_process'),assert=require('node:assert/strict');
const source=fs.readFileSync(path.resolve(__dirname,'../../main/java/app/nimarkogram/messenger/notifications/NimarkoInAppNotifications.java'),'utf8');
function method(signature,sourceText=source){
 const source=sourceText;
 const start=source.indexOf(signature);assert(start>=0,signature);
 const tokens=/\/\/[^\n]*|\/\*[\s\S]*?\*\/|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[{}]/g;
 tokens.lastIndex=source.indexOf('{',start);let depth=0;
 for(let token;(token=tokens.exec(source));){if(token[0]==='{')depth++;if(token[0]==='}'&&--depth===0)return source.slice(start,tokens.lastIndex);}
 throw Error(signature);
}
const bannerSource=method('private static final class Banner');
const slotSource=method('private static final class Slot');
const methods=['void cancelExpansion()','void setExpansion(float value)','void setPullOffset(float offset)','void settlePull(float target, long duration, Runnable completion)','void settleGeometry(', 'void settleExpansion(boolean open)','void beginGesture(MotionEvent e)','void restoreGesture()','public boolean dispatchTouchEvent(MotionEvent e)','public boolean onInterceptTouchEvent(MotionEvent e)','public boolean onTouchEvent(MotionEvent e)','protected void onMeasure(int w, int h)','protected void onLayout(boolean changed, int left, int top, int right, int bottom)','private void centerCollapsedContent(View child, int contentHeight, int childHeight)'].map(signature=>method(signature,bannerSource)).join('\n');
const slotMethods=['void attach(Banner value)','protected void onMeasure(int widthSpec, int heightSpec)','public boolean dispatchTouchEvent(MotionEvent event)'].map(signature=>method(signature,slotSource)).join('\n');
for(const signature of ['private static void remove(Banner old)','void hide()','void animateOpenChat()','protected void onDetachedFromWindow()'])assert(method(signature).includes('cancelExpansion();'));
assert.match(method('void pauseInteraction()'),/settleExpansion\(expanded\)/);
assert.match(source,/expandedBody.setText\(body.getText\(\)\)/);
assert.match(source,/body.setText\(preview \? message : getString\(R.string.NotificationHiddenMessage\)\)/);
assert.doesNotMatch(method('void setExpansion(float value)'),/setText\(|setMaxLines\(|new /);
const java=`import java.util.*;import java.util.function.Consumer;
class ViewParent {boolean disallowed;void requestDisallowInterceptTouchEvent(boolean value){disallowed=value;}}
class View {static int IMPORTANT_FOR_ACCESSIBILITY_NO=2,IMPORTANT_FOR_ACCESSIBILITY_AUTO=0,GONE=8;
 ViewParent parent=new ViewParent();ViewParent getParent(){return parent;}
 int top,height,visibility;int getVisibility(){return visibility;}int getTop(){return top;}int getHeight(){return height;}int getMeasuredHeight(){return height;}void offsetTopAndBottom(int offset){top+=offset;}}
class MotionEvent {
 static final int ACTION_DOWN=0,ACTION_UP=1,ACTION_MOVE=2,ACTION_CANCEL=3,ACTION_POINTER_DOWN=5;
 int action,pointers;float x,y;MotionEvent(int a,float x,float y,int p){action=a;this.x=x;this.y=y;pointers=p;}
 int getActionMasked(){return action;}int getPointerCount(){return pointers;}
 float ox,oy;boolean recycled;static MotionEvent lastCopy;
 static MotionEvent obtain(MotionEvent e){MotionEvent copy=new MotionEvent(e.action,e.x,e.y,e.pointers);copy.ox=e.ox;copy.oy=e.oy;return lastCopy=copy;}
 void offsetLocation(float dx,float dy){ox+=dx;oy+=dy;}void recycle(){recycled=true;}
 float getRawX(){return x;}float getRawY(){return y;}float getX(){return x+ox;}float getY(){return y+oy;}
}
class VelocityTracker {static float nextVelocity,lastX,lastY;static VelocityTracker obtain(){return new VelocityTracker();}void recycle(){}void addMovement(MotionEvent e){lastX=e.getX();lastY=e.getY();}void computeCurrentVelocity(int u,int max){}float getYVelocity(){return nextVelocity;}}
interface Interpolator {float getInterpolation(float t);}
class CubicBezierInterpolator {static Interpolator EASE_OUT_QUINT=t->t,EASE_OUT=t->t,EASE_BOTH=t->t;}
class SystemClock {static long now=1000;static long elapsedRealtime(){return 1000;}static long uptimeMillis(){return now;}}
class Label extends View {float alpha;int accessibility,maxHeight;void setAlpha(float a){alpha=a;}
 void setImportantForAccessibility(int a){accessibility=a;}int getMaxHeight(){return maxHeight;}void setMaxHeight(int h){maxHeight=h;}
 int getLineHeight(){return 17;}int getMaxLines(){return 8;}void setMaxLines(int n){}int getMeasuredHeight(){return height;}}
class Animator {}
class AnimatorListenerAdapter {public void onAnimationEnd(Animator animation){}}
class ValueAnimator extends Animator {
 float from,target,value;boolean cancelled,ended;Consumer<ValueAnimator> listener;AnimatorListenerAdapter endListener;
 static ValueAnimator ofFloat(float f,float t){ValueAnimator a=new ValueAnimator();a.from=a.value=f;a.target=t;return a;}
 // Android cancellation synchronously delivers onAnimationEnd: guards must already be retired.
 Interpolator interpolation;void cancel(){cancelled=true;end();}void setDuration(long d){}void setInterpolator(Interpolator i){interpolation=i;}
 void addListener(AnimatorListenerAdapter l){endListener=l;}
 void addUpdateListener(Consumer<ValueAnimator> l){listener=l;}Float getAnimatedValue(){return value;}void start(){}
 void step(float p){if(!cancelled&&!ended){value=from+(target-from)*p;listener.accept(this);if(p==1)end();}}
 void end(){if(!ended){ended=true;if(endListener!=null)endListener.onAnimationEnd(this);}}
 void lateUpdate(float v){value=v;listener.accept(this);}void lateEnd(){if(endListener!=null)endListener.onAnimationEnd(this);}
}
class Base extends View {
 static class MeasureSpec {static int EXACTLY=1,UNSPECIFIED=0;static int getSize(int n){return n;}static int makeMeasureSpec(int n,int m){return n;}}
 float y,alpha=1;int layouts,clicks,hides,measuredWidth,measuredHeight;float density=1;
 int fullHeight=240,padding=12;Anim anim=new Anim();
 int dp(int n){return Math.round(n*density);}float getTranslationY(){return y;}
 View getRootView(){View v=new View();v.height=dp(800);return v;}
 void setTranslationY(float f){y=f;}void setAlpha(float f){alpha=f;}float getAlpha(){return alpha;}int getHeight(){return dp(80);}
 void setPressed(boolean b){}void drawableHotspotChanged(float x,float y){}void requestLayout(){layouts++;}
 Anim animate(){return anim;}void performClick(){clicks++;}void hide(){hides++;}
 int getMeasuredHeight(){return measuredHeight;}int getMeasuredWidth(){return measuredWidth;}
 int getPaddingTop(){return padding;}int getPaddingBottom(){return padding;}
 protected void onMeasure(int w,int h){measuredWidth=w;measuredHeight=h==0?fullHeight:Math.min(h,fullHeight);}
 protected void onLayout(boolean changed,int left,int top,int right,int bottom){}
 void setMeasuredDimension(int w,int h){measuredWidth=w;measuredHeight=h;}
 boolean onInterceptTouchEvent(MotionEvent e){return false;}boolean onTouchEvent(MotionEvent e){return false;}
 boolean dispatchTouchEvent(MotionEvent e){onInterceptTouchEvent(e);return onTouchEvent(e);}
 class Anim {void cancel(){}Anim withEndAction(Runnable r){return this;}Anim alpha(float f){return this;}
 Anim translationY(float f){return this;}Anim scaleX(float f){return this;}Anim scaleY(float f){return this;}
 Anim setDuration(long d){return this;}Anim setInterpolator(Object i){return this;}void start(){}}
}
class LayoutHelper {static int MATCH_PARENT=-1,WRAP_CONTENT=-2;static Object createFrame(int w,int h,int gravity){return new Object();}}
class Gravity {static int TOP=1,CENTER_HORIZONTAL=2;}
class Panel {float totalHeight;int paddingTop;boolean visible,animated;int getPaddingTop(){return paddingTop;}Panel getMetadata(){return this;}float getTotalHeight(){return totalHeight;}
 void setViewVisible(Slot s,boolean v,boolean a){visible=v;animated=a;}}
class Slot extends Base {
 boolean directResize;float retainedCoverage;int minimumHeight;Panel panel=new Panel();ArrayList<Banner> children=new ArrayList<>();
 int getChildCount(){return children.size();}Banner getChildAt(int i){return children.get(i);}
 int getMinimumHeight(){return minimumHeight;}float getY(){return top+y;}int getHeight(){return getMeasuredHeight();}
 void setMinimumHeight(int h){minimumHeight=h;}void addView(Banner b,Object params){children.add(b);}
 ${slotMethods}
}
class Banner extends Base {
 float gestureExpansion,gestureOffset;boolean gestureFramePending;long gestureFrameTime;Runnable frame;
 void postOnAnimation(Runnable r){frame=r;}void removeCallbacks(Runnable r){if(frame==r)frame=null;}
 ${method('final Runnable gestureFrame',bannerSource)};
 ${method('void setGestureGeometry(',bannerSource)}
 void finishGestureFrames(){for(int i=0;frame!=null&&i<100;i++){SystemClock.now+=16;Runnable r=frame;frame=null;r.run();}}
 boolean closing,opening,touching,dragged,expanded,multiplePointers,preview=true;
 float expansion,downExpansion,downTranslation,downX,downY;int collapsedHeight=80,expandedHeight=240,slop=8;
 long expiresAt;Label title=new Label(),body=new Label(),expandedBody=new Label();ValueAnimator expansionAnimator,pullAnimator;
 float pullOffset,releaseVelocity;VelocityTracker velocityTracker;Slot slot;int measuredAvailableHeight;
 int availableHeight(){return dp(600);}
 void measure(int width,int height){} // Slot geometry fixtures have already measured these banners.
 View avatar=new View(),close=new View(),text=new View();
 Banner(){body.alpha=1;}
 void tracePreview(String stage){}
 ${methods.replaceAll('android.view.ViewParent','ViewParent')}
 void event(int action,float x,float y,int fingers){dispatchTouchEvent(new MotionEvent(action,x,y,fingers));if(action==2)finishGestureFrames();}
 void end(){if(expansionAnimator!=null)expansionAnimator.step(1);if(pullAnimator!=null)pullAnimator.step(1);}
}
public class NotificationPullTest {
 static int checks;static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 static Banner pull(float dy){Banner b=new Banner();b.event(0,0,0,1);b.event(2,0,dy,1);return b;}
public static void main(String[] args){
  for(int interval:new int[]{8,16,33}){
   Banner motion=new Banner();motion.event(0,0,0,1);
   motion.dispatchTouchEvent(new MotionEvent(2,0,120,1));
   check(motion.expansion==0&&motion.frame!=null,"input does not resize list between display frames");
   SystemClock.now+=interval;Runnable next=motion.frame;motion.frame=null;next.run();
   check(motion.expansion>0&&motion.expansion<.75f,"large touch step is spread across display frames");
   float previous=motion.expansion;motion.finishGestureFrames();
   check(motion.expansion>=previous&&motion.expansion==.75f,"coordinated geometry converges without overshoot");
   motion.dispatchTouchEvent(new MotionEvent(2,0,220,1));Runnable stale=motion.frame;
   motion.cancelExpansion();float frozen=motion.expansion;stale.run();
   check(motion.expansion==frozen,"cancelled display frame cannot move list");
  }
  Banner b=pull(100);check(b.expansion>0&&b.expansion<1&&b.y==0,"expansion follows finger without also translating card");
  Banner elastic=pull(260);check(elastic.expansion==1&&elastic.y>0&&elastic.y<36,"overscroll starts only after complete expansion");
  b.event(1,0,100,1);check(b.expanded&&b.clicks==0&&b.hides==0,"pull expands without navigating");b.end();check(b.expansion==1&&b.expiresAt==9000,"expanded reading time");
  b.event(0,0,0,1);b.event(2,0,-100,1);b.event(1,0,-100,1);b.end();check(!b.expanded&&b.expansion==0&&b.hides==0,"up collapses expanded card first");
  b.event(0,0,0,1);b.event(2,0,-60,1);b.event(1,0,-60,1);check(b.hides==1,"up dismisses collapsed card");
  b=new Banner();b.expanded=true;b.expansion=1;
  b.event(0,0,0,1);b.event(2,0,-160,1);
  check(b.expansion==0&&b.pullOffset==0,"continuous upward drag reaches collapsed boundary");
  b.event(2,0,-180,1);check(b.expansion==0&&b.pullOffset==-20,"same gesture continues above collapsed boundary");
  b.event(2,0,-210,1);b.event(1,0,-210,1);
  check(b.hides==1,"expanded card can dismiss in one continuous upward gesture");
  b=new Banner();b.expanded=true;b.expansion=1;
  b.event(0,0,0,1);b.event(2,0,-200,1);b.event(2,0,-120,1);
  check(b.pullOffset==0&&Math.abs(b.expansion-.25f)<.001f,"reversal crosses collapse boundary without a jump");
  b.event(3,0,-120,1);b.end();
  check(b.hides==0&&b.expansion==1&&b.pullOffset==0,"cancelled expanded swipe restores original state");
  b=pull(100);b.event(3,0,100,1);b.end();check(b.expansion==0&&b.clicks==0&&b.hides==0,"cancel returns collapsed origin");
  b=pull(100);b.event(1,0,100,1);b.pullAnimator.step(.4f);float before=b.expansion;ValueAnimator old=b.pullAnimator;
  b.event(0,0,0,1);check(old.cancelled&&b.expansion==before,"re-touch preserves in-flight expansion");old.step(1);check(b.expansion==before,"retired animator cannot jump geometry");
  b.event(2,0,-10,1);check(b.expansion<before,"reverse drag starts at current expansion");
  b=pull(100);b.event(2,0,0,1);b.event(1,0,0,1);check(b.clicks==0&&!b.expanded,"return-to-origin drag is not a tap");
  b=pull(10);b.event(1,0,10,1);b.end();check(!b.expanded&&b.expansion==0&&b.clicks==0,"short pull gently returns");
  b=new Banner();b.event(0,0,0,1);b.event(1,0,1,1);check(b.clicks==1,"normal tap retained");
  b=pull(100);b.event(5,0,150,2);float multi=b.expansion;b.event(2,0,900,2);check(b.expansion==multi,"second finger cannot jump expansion");
  b.event(1,0,900,1);b.end();check(b.expansion==0&&b.clicks==0&&!b.expanded,"multi-touch cancels without accidental action");
  b=new Banner();b.preview=false;b.event(0,0,0,1);b.event(2,0,150,1);b.event(1,0,150,1);b.end();check(b.expansion==0&&!b.expanded,"hidden content is never expanded");
  b=new Banner();b.expandedHeight=b.collapsedHeight;b.event(0,0,0,1);b.event(2,0,150,1);b.event(1,0,150,1);check(!b.expanded,"short message has no empty expansion");
  b=new Banner();b.onInterceptTouchEvent(new MotionEvent(0,0,0,1));check(b.onInterceptTouchEvent(new MotionEvent(2,0,70,1)),"drag from close button intercepted");
  b.onTouchEvent(new MotionEvent(2,0,70,1));b.onTouchEvent(new MotionEvent(1,0,70,1));check(b.expanded&&b.clicks==0,"child drag uses original touch coordinates");
  VelocityTracker.nextVelocity=1000;b=pull(18);b.event(1,0,18,1);b.end();check(b.expanded&&b.expansion==1,"short fast downward flick expands");
  VelocityTracker.nextVelocity=-1000;b=pull(-18);b.event(1,0,-18,1);check(b.hides==1,"short fast upward flick dismisses");
  b=pull(200);b.event(2,0,300,1);b.event(2,0,120,1);b.event(1,0,110,1);
  check(b.hides==1,"final upward fling dismisses despite positive net displacement");
  b=new Banner();b.expanded=true;b.expansion=1;b.event(0,0,0,1);b.event(2,0,90,1);b.event(2,0,30,1);b.event(1,0,20,1);
  check(b.hides==1,"expanded notification dismisses on final upward fling");
  VelocityTracker.nextVelocity=1000;b=pull(-60);b.event(2,0,-40,1);b.event(1,0,-30,1);
  check(b.hides==0,"downward reversal overrides old upward displacement");
  VelocityTracker.nextVelocity=0;b=new Banner();MotionEvent translated=new MotionEvent(0,240,530,1);translated.offsetLocation(-140,-470);
  b.dispatchTouchEvent(translated);
  check(VelocityTracker.lastX==240&&VelocityTracker.lastY==530,"velocity samples use screen coordinates, not moving banner coordinates");
  check(translated.getX()==100&&translated.getY()==60&&!translated.recycled&&MotionEvent.lastCopy.recycled,"original event preserved and pooled copy recycled");
  b.event(3,240,530,1);
  VelocityTracker.nextVelocity=-1000;
  b=new Banner();b.event(0,0,0,1);b.event(1,0,1,1);check(b.clicks==1&&b.hides==0,"velocity alone cannot turn a tap into dismiss");
  b=pull(100);b.event(3,0,100,1);b.end();check(!b.expanded&&b.releaseVelocity==0,"cancel discards velocity");
  VelocityTracker.nextVelocity=0;b=new Banner();b.setExpansion(.25f);b.setPullOffset(20);b.settleExpansion(true);
  ValueAnimator geometry=b.pullAnimator;for(int i=0;i<=10;i++){float p=i/10f;geometry.step(p);check(Math.abs(b.expansion-(.25f+.75f*p))<.001f&&Math.abs(b.pullOffset-20*(1-p))<.001f,"one clock drives expansion and occupied height");}
  // Execute production pull cancellation, including synchronous end-on-cancel and stale callbacks.
  b=new Banner();b.slot=new Slot();b.slot.children.add(b);final int[] completions={0};
  b.setPullOffset(30);check(b.pullOffset==30&&b.y==30&&b.slot.directResize&&b.slot.layouts==1,"pull requests direct slot resize");
  b.y=-8;b.setPullOffset(30);check(b.y==30&&b.slot.layouts==1,"same offset still repairs entrance translation without relayout");
  b.settlePull(-80,210,()->completions[0]++);ValueAnimator cancelledPull=b.pullAnimator;cancelledPull.step(.4f);
  float cancelledOffset=b.pullOffset;b.cancelExpansion();
  check(cancelledPull.cancelled&&b.pullAnimator==null&&completions[0]==0,"cancel retires pull before synchronous end callback");
  cancelledPull.lateUpdate(-80);cancelledPull.lateEnd();
  check(b.pullOffset==cancelledOffset&&completions[0]==0,"cancelled pull cannot resize or complete later");
  b.settlePull(-80,210,()->completions[0]++);ValueAnimator replacedPull=b.pullAnimator;replacedPull.step(.2f);
  b.settlePull(0,260,()->completions[0]+=10);ValueAnimator currentPull=b.pullAnimator;
  float startOffset=b.pullOffset;
  check(replacedPull.cancelled&&completions[0]==0&&currentPull.from==0,"replacement cancels without firing old completion and starts normalized progress");
  replacedPull.lateUpdate(-80);replacedPull.lateEnd();
  check(b.pullAnimator==currentPull&&completions[0]==0&&b.pullOffset==startOffset,"retired pull cannot clear its replacement");
  currentPull.step(1);currentPull.lateEnd();
  check(b.pullAnimator==null&&b.pullOffset==0&&b.y==0&&completions[0]==10,"current pull completes exactly once at target");
  b.setExpansion(.5f);b.settleExpansion(true);ValueAnimator activeExpansion=b.pullAnimator;
  b.settlePull(20,100,()->completions[0]++);ValueAnimator activePull=b.pullAnimator;
  b.beginGesture(new MotionEvent(0,0,0,1));
  check(activeExpansion.cancelled&&activePull.cancelled&&b.expansionAnimator==null&&b.pullAnimator==null&&completions[0]==10,"retouch cancels both animators without navigation completion");
  b.restoreGesture();b.end();check(b.pullOffset==0&&b.y==0,"gesture restore settles real pull geometry");
  // Slot reserves the maximum occupied child extent, not the sum during crossfade.
  Slot attached=new Slot();attached.directResize=true;attached.minimumHeight=90;Banner incoming=new Banner();attached.attach(incoming);
  check(incoming.slot==attached&&attached.children.size()==1&&attached.panel.visible&&attached.panel.animated,"attach binds and reveals native slot");
  check(!attached.directResize&&attached.minimumHeight==0,"attach resets gesture resize and retiring minimum for animated replacement");
  Slot slot=new Slot();Banner first=new Banner(),second=new Banner();slot.children.add(first);slot.children.add(second);
  first.measuredHeight=100;second.measuredHeight=140;first.pullOffset=25.4f;second.pullOffset=-30;
  slot.onMeasure(360,800);check(slot.measuredHeight==125,"slot rounds pull offset and reserves maximum extent");
  first.pullOffset=-200;second.visibility=View.GONE;slot.onMeasure(360,800);check(slot.measuredHeight==0,"slot clamps negative extent and excludes gone children");
  slot.minimumHeight=64;slot.onMeasure(360,800);check(slot.measuredHeight==64,"retiring empty slot retains minimum height for collapse");
  slot.minimumHeight=0;first.pullOffset=20;second.visibility=0;second.pullOffset=15;
  slot.onMeasure(360,800);check(slot.measuredHeight==155,"crossfade reserves taller pulled incoming card only once");
  slot.top=10;slot.panel.paddingTop=20;slot.panel.totalHeight=60;
  check(!slot.dispatchTouchEvent(new MotionEvent(0,0,-1,1)),"slot rejects touch above allocated band");
  check(!slot.dispatchTouchEvent(new MotionEvent(0,0,70,1)),"slot rejects touch at animated lower boundary");
  slot.panel.totalHeight=1000;check(!slot.dispatchTouchEvent(new MotionEvent(0,0,155,1)),"slot rejects touch beyond own height");
  for(float density:new float[]{1,1.5f,2.75f,3,4})for(float font:new float[]{1,1.3f,1.8f,2})for(int lines:new int[]{1,2}){
   b=new Banner();b.density=density;b.padding=b.dp(7);b.title.height=b.dp(Math.round(19*font));b.body.height=b.dp(Math.round(17*font*lines));
   b.avatar.height=b.dp(36);b.close.height=b.dp(48);b.fullHeight=b.dp(600);
   b.onMeasure(b.dp(360),b.dp(800));b.onLayout(true,0,0,b.measuredWidth,b.measuredHeight);
   int row=b.title.height+b.dp(3)+b.body.height;
   check(Math.abs(b.avatar.top*2+b.avatar.height-b.collapsedHeight)<=1,"avatar centered in collapsed card");
   check(Math.abs(b.close.top*2+b.close.height-b.collapsedHeight)<=1,"close centered with avatar");
   check(Math.abs(b.text.top*2+row-b.collapsedHeight)<=1,"title and body balanced vertically");
   check(b.text.top>=b.padding&&b.avatar.top>=b.padding,"content respects vertical inset");
   if(font==1&&lines==1)check(b.collapsedHeight==b.dp(68),"short notification does not reserve empty second line");
   int avatarTop=b.avatar.top,textTop=b.text.top;
   for(int step=0;step<=10;step++){
    b.setExpansion(step/10f);b.onMeasure(b.dp(360),b.dp(800));b.onLayout(false,0,0,b.measuredWidth,b.measuredHeight);
    check(b.avatar.top==avatarTop&&b.text.top==textTop,"pull expansion keeps header and avatar anchored");
   }
  }
  for(float density:new float[]{1,1.5f,2,3,4})for(int height:new int[]{140,240,600,1200}){
   b=new Banner();b.density=density;b.padding=b.dp(12);b.title.height=b.dp(20);b.body.height=b.dp(36);b.fullHeight=Math.min(height,b.dp(260));
   int previous=0;for(int i=0;i<=100;i++){
    b.setExpansion(i/100f);b.onMeasure(b.dp(400),height);
    check(b.measuredHeight>=previous&&b.measuredHeight<=height,"bounded monotonic layout");
    check(Math.abs(b.body.alpha+b.expandedBody.alpha-1f)<.001f,"continuous text crossfade");previous=b.measuredHeight;
   }
  }
  for(int end:new int[]{MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL}){
   Banner gesture=new Banner();gesture.event(0,0,0,1);check(gesture.parent.disallowed,"claim parent gesture on down");
   gesture.event(2,0,90,1);check(gesture.dragged&&gesture.parent.disallowed,"vertical drag exclusive");
   check(gesture.onInterceptTouchEvent(new MotionEvent(2,500,91,1)),"drag stays latched after horizontal deviation");
   gesture.event(2,500,91,1);check(gesture.parent.disallowed,"horizontal deviation cannot switch tabs");
   gesture.event(end,500,91,1);check(!gesture.parent.disallowed,"release parent after up or cancellation");
  }
  for(float velocity:new float[]{0,-100,-650,-1800,-4000}){
   Banner dismissal=new Banner();dismissal.closing=true;dismissal.pullOffset=-20;dismissal.alpha=.7f;dismissal.releaseVelocity=velocity;
   dismissal.settleGeometry(0,-80,200,null);
   Interpolator curve=dismissal.pullAnimator.interpolation;
   float previous=0;
   for(int i=0;i<=100;i++){
    float value=curve.getInterpolation(i/100f);
    check(value>=previous-.00001f&&value<=1.00001f,"dismiss curve monotonic without overshoot");previous=value;
   }
   check(curve.getInterpolation(.001f)>.0009f,"dismiss continues immediately without ease-in pause");
   check(Math.abs(1-curve.getInterpolation(.999f))<.00001f,"dismiss settles at zero velocity");
   dismissal.pullAnimator.step(.5f);check(Math.abs(dismissal.alpha-.35f)<.001f,"opacity shares geometry clock");
   dismissal.pullAnimator.step(1);check(dismissal.pullOffset==-80&&dismissal.alpha==0,"no opacity or geometry tail");
  }
  System.out.println("PASS: "+checks+" native pull/expand checks: reversal, cancel, multitouch, privacy, child interception, re-touch and bounded layout");
 }
}`;
const dir=fs.mkdtempSync(path.join(os.tmpdir(),'nimarko-notification-pull-'));
try{
 fs.writeFileSync(path.join(dir,'NotificationPullTest.java'),java);
 cp.execFileSync('javac',['NotificationPullTest.java'],{cwd:dir,stdio:'pipe'});
 process.stdout.write(cp.execFileSync('java',['NotificationPullTest'],{cwd:dir,encoding:'utf8'}));
 const broken=java.replace('dp(36) * overscroll / (overscroll + dp(120))', '0f');
 assert.notEqual(broken,java);
 fs.writeFileSync(path.join(dir,'NotificationPullTest.java'),broken);
 cp.execFileSync('javac',['NotificationPullTest.java'],{cwd:dir,stdio:'pipe'});
 const result=cp.spawnSync('java',['NotificationPullTest'],{cwd:dir,encoding:'utf8'});
 assert.notEqual(result.status,0);
 assert.match(result.stderr,/overscroll starts only after complete expansion/);
 console.log('PASS: immobile downward-pull negative control');
 const mutate=(needle,replacement,why)=>{
  const broken=java.replace(needle,replacement);assert.notEqual(broken,java,why+' mutation applied');
  fs.writeFileSync(path.join(dir,'NotificationPullTest.java'),broken);
  cp.execFileSync('javac',['NotificationPullTest.java'],{cwd:dir,stdio:'pipe'});
  const result=cp.spawnSync('java',['NotificationPullTest'],{cwd:dir,encoding:'utf8'});
  assert.notEqual(result.status,0,why+' must fail');assert.match(result.stderr,/AssertionError/);
 };
 mutate('pullAnimator = null;\n                previous.cancel();','previous.cancel();\n                pullAnimator = null;','synchronous cancelled-pull completion');
 mutate('if (pullAnimator != a) return;','/* missing stale-frame guard */','stale pull geometry');
 mutate('setTranslationY(offset);','/* lost translation synchronization */','pull translation synchronization');
 mutate('travel < 0 ? travel','travel < 0 ? downExpansion > 0 ? 0 : travel','same gesture continues above collapsed boundary');
 mutate('1f - (float) Math.exp(-Math.min(32, Math.max(1, now - gestureFrameTime)) / 18f)','1f','large touch step is spread across display frames');
 mutate('child.getMeasuredHeight() + Math.round(child.pullOffset)','child.getMeasuredHeight()','missing slot pull allocation');
 mutate('directResize = false;','/* leaked gesture resize */','replacement must restore animated slot sizing');
 mutate('releaseVelocity < -dp(650)\n                                || releaseVelocity <= dp(650)', 'remainingTravel < 0 && releaseVelocity < -dp(650)\n                                || releaseVelocity <= dp(650)', 'final upward fling must not depend on net displacement');
 mutate('screenEvent.offsetLocation(e.getRawX() - e.getX(), e.getRawY() - e.getY());', '', 'velocity must not follow moving local coordinates');
 console.log('PASS: cancellation, stale-pull, translation and slot-allocation negative controls');
}finally{fs.rmSync(dir,{recursive:true,force:true});}
