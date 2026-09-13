const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),cp=require('node:child_process'),assert=require('node:assert/strict');
const root=path.resolve(__dirname,'../../main/java'),read=p=>fs.readFileSync(path.join(root,p),'utf8');
const source=read('app/nimarkogram/messenger/notifications/NotificationGlassSurface.java');
const pager=read('org/telegram/ui/ViewPagerActivity.java').match(/    public boolean isPageTransitionRunning\(\) \{[\s\S]*?\n    \}/)[0];
const fixed=read('org/telegram/ui/Components/ViewPagerFixed.java').match(/    public boolean isPageTransitionRunning\(\) \{[\s\S]*?\n    \}/)[0];
assert.doesNotMatch(source,/NotificationTrace|traceFrame|traceEvent|PixelCopy|Bitmap|Thread|postDelayed|FileOutputStream/);
assert.match(source,/factory.createForOverlay\(view,/);
assert.match(source,/glass.setAlpha\(255\)/);
assert.doesNotMatch(source,/material.setCornerRadius|material.setStroke/);
assert.match(read('app/nimarkogram/messenger/notifications/NimarkoInAppNotifications.java'),/setClipToOutline\(true\)/);
for(const p of ['org/telegram/ui/Components/blur3/RenderNodeWithHash.java','org/telegram/ui/Components/blur3/drawable/BlurredBackgroundDrawableRenderNode.java','org/telegram/ui/Components/blur3/source/BlurredBackgroundSourceRenderNode.java'])assert.doesNotMatch(read(p),/notificationTracing|NotificationTrace/);
const java=`import java.util.*;
class Color {static final int WHITE=-1,TRANSPARENT=0;}
class ColorFilter{}class PixelFormat {static final int TRANSLUCENT=-3;}
class ColorUtils {static double calculateLuminance(int c){return ((c>>16)&255)/255d;}static int blendARGB(int a,int b,float f){return a;}static int setAlphaComponent(int c,int a){return c;}}
class Rect {int left,top,right,bottom;Rect(){}Rect(int l,int t,int r,int b){left=l;top=t;right=r;bottom=b;}}
class Canvas {double color,alpha;int saves;ArrayDeque<double[]> stack=new ArrayDeque<>();
 void paint(double c,double a){color=c*a+color*(1-a);alpha=a+alpha*(1-a);}
 int saveLayerAlpha(float l,float t,float r,float b,int a){stack.push(new double[]{color,alpha,a/255d});color=alpha=0;saves++;return stack.size();}
 void restoreToCount(int count){double[] d=stack.pop();double a=alpha*d[2];color=color*d[2]+d[0]*(1-a);alpha=a+d[1]*(1-a);}
}
class Drawable {interface Callback {void invalidateDrawable(Drawable w);void scheduleDrawable(Drawable w,Runnable r,long t);void unscheduleDrawable(Drawable w,Runnable r);}
 Rect bounds=new Rect();Callback callback;int alpha=255;void draw(Canvas c){}void setBounds(Rect b){bounds=b;onBoundsChange(b);}Rect getBounds(){return bounds;}
 protected void onBoundsChange(Rect b){}void setCallback(Callback c){callback=c;}void invalidateSelf(){if(callback!=null)callback.invalidateDrawable(this);}
 void scheduleSelf(Runnable r,long t){}void unscheduleSelf(Runnable r){}public void setAlpha(int a){alpha=a;}public int getAlpha(){return alpha;}public void setColorFilter(ColorFilter f){}public int getOpacity(){return -3;}}
class GradientDrawable extends Drawable {enum Orientation{TL_BR}boolean sheen;
 GradientDrawable(Orientation o,int[] c){sheen=c.length==3;}void setCornerRadius(float r){}void setStroke(int w,int c){}
 void draw(Canvas c){if(sheen)c.paint(.8,.04);else c.paint(.2,1);}}
class Outline {float radius;void setRoundRect(int l,int t,int r,int b,float v){radius=v;}}
abstract class ViewOutlineProvider {abstract void getOutline(View v,Outline o);}
class SystemClock {static long now;static long uptimeMillis(){return now;}}
class NotificationColorTrace {static class Session {void frame(Object... values){}void event(String s,Object o){}void close(){}}static Session start(View v){return new Session();}static void stop(Object o){}}
class BlurredBackgroundSource {}
class BlurredBackgroundSourceRenderNode extends BlurredBackgroundSource {boolean ready=true;boolean isDisplayListReady(){return ready;}}
class ViewTreeObserver {interface OnPreDrawListener{boolean onPreDraw();}Set<OnPreDrawListener> listeners=new HashSet<>();boolean isAlive(){return true;}
 void addOnPreDrawListener(OnPreDrawListener l){listeners.add(l);}void removeOnPreDrawListener(OnPreDrawListener l){listeners.remove(l);}}
class View {boolean attached,hardware=true;int x=24,y=144,w=360,h=72,l=2,t=12,r=3,b=12,backgrounds,invalidates;Drawable bg;ViewOutlineProvider outline;ViewTreeObserver observer=new ViewTreeObserver();
 void getLocationOnScreen(int[] p){p[0]=x;p[1]=y;}boolean isAttachedToWindow(){return attached;}boolean isHardwareAccelerated(){return hardware;}
 void setOutlineProvider(ViewOutlineProvider p){outline=p;}int getWidth(){return w;}int getHeight(){return h;}void invalidate(){invalidates++;}void postInvalidateOnAnimation(){invalidates++;}
 ViewTreeObserver getViewTreeObserver(){return observer;}int getPaddingLeft(){return l;}int getPaddingTop(){return t;}int getPaddingRight(){return r;}int getPaddingBottom(){return b;}
 void setPadding(int l,int t,int r,int b){this.l=l;this.t=t;this.r=r;this.b=b;}
 void setBackground(Drawable d){bg=d;backgrounds++;l=t=r=b=0;d.setBounds(new Rect(0,0,w,h));}}
class Build {static class VERSION {static int SDK_INT=36;}}
class AndroidUtilities {static int dp(float v){return (int)v;}static float dpf2(float v){return v;}}
class SharedConfig {static boolean blur=true;static boolean chatBlurEnabled(){return blur;}}
class Theme {static int key_windowBackgroundWhite=1,key_windowBackgroundWhiteBlueText=2,bg=0xfffafafa;static int getColor(int k){return k==1?bg:0xff4455aa;}static int multAlpha(int c,float a){return c;}}
class NavigationLayout {boolean moving;boolean isTransitionAnimationInProgress(){return moving;}boolean isSwipeInProgress(){return false;}}
class BaseFragment {View view=new View();BlurredBackgroundDrawableViewFactory factory=new BlurredBackgroundDrawableViewFactory();NavigationLayout layout=new NavigationLayout();
 View getFragmentView(){return view;}BlurredBackgroundDrawableViewFactory getNotificationGlassFactory(){return factory;}NavigationLayout getParentLayout(){return layout;}}
class Pager {boolean startedTracking,tabsAnimationInProgress,manual;boolean isManualScrolling(){return manual;}${fixed}}
class ViewPagerActivity extends BaseFragment {Pager viewPager=new Pager();${pager}}
class LaunchActivity {static BaseFragment current,root;static BaseFragment getLastFragmentIncludeMainTabs(){return current;}static BaseFragment getLastFragment(){return root==null?current:root;}}
class BlurredBackgroundDrawable extends Drawable {BlurredBackgroundSourceRenderNode source=new BlurredBackgroundSourceRenderNode();BlurredBackgroundSource getUnwrappedSource(){return source;}double pixel=.6;boolean released;float x,y;void setSourceOffset(float x,float y){this.x=x;this.y=y;}float getSourceOffsetX(){return x;}float getSourceOffsetY(){return y;}
 BlurredBackgroundDrawable setRadius(float v){return this;}BlurredBackgroundDrawable setPadding(int v){return this;}
 void draw(Canvas c){if(released)throw new AssertionError("drawing released source");c.paint(pixel,.85*alpha/255d);}}
class BlurredBackgroundDrawableViewFactory {static int live,peak;View root=new View();{root.attached=true;root.x=root.y=0;}int created,released;double pixel=.6;BlurredBackgroundDrawable last;
 View getSourceRootView(){return root;}BlurredBackgroundDrawable createForOverlay(View v,Object p){created++;live++;peak=Math.max(peak,live);last=new BlurredBackgroundDrawable();last.pixel=pixel;return last;}
 void release(View v,BlurredBackgroundDrawable d){if(d.released)throw new AssertionError("double release");d.released=true;d.callback=null;released++;live--;}}
class BlurredBackgroundProviderBuilder {interface Fn{int color(Object r,boolean d);}BlurredBackgroundProviderBuilder(Object r){}
 BlurredBackgroundProviderBuilder setBackgroundColor(Fn f){return this;}BlurredBackgroundProviderBuilder setStrokeColorTop(int a,int b){return this;}
 BlurredBackgroundProviderBuilder setStrokeColorBottom(int a,int b){return this;}BlurredBackgroundProviderBuilder setShadowColor(int a,int b){return this;}
 BlurredBackgroundProviderBuilder setShadowLayer(float a,float b,float c){return this;}BlurredBackgroundProviderBuilder setStrokeWidth(float a,float b){return this;}Object build(){return this;}}
${source.replace(/^package .*;\n|^import .*;\n/gm,'')}
public class NotificationGlassTest {static int checks;static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
 static View drawingView;static void frame(NotificationGlassSurface s){SystemClock.now+=16;s.onPreDraw();if(drawingView!=null)drawingView.bg.draw(new Canvas());}static void settle(NotificationGlassSurface s){for(int i=0;i<32;i++)frame(s);}
 static double pixel(View v){Canvas c=new Canvas();v.bg.draw(c);check(c.stack.isEmpty()&&Math.abs(c.alpha-1)<1e-8,"balanced, fully opaque material");return c.color;}
 static void same(double a,double b,String why){check(Math.abs(a-b)<1e-8,why);}
 public static void main(String[] args){
  View v=new View();drawingView=v;BaseFragment a=new BaseFragment();a.view.attached=true;LaunchActivity.current=a;
  NotificationGlassSurface s=new NotificationGlassSurface(v);check(a.factory.created==0,"no native source detached");v.attached=true;s.attach();
  check(v.l==2&&v.t==12&&v.r==3&&v.b==12,"padding preserved");settle(s);double original=pixel(v);int backgrounds=v.backgrounds;
  for(int i=0;i<1200;i++){frame(s);check(a.factory.created==1&&v.backgrounds==backgrounds,"no steady allocation");}
  Canvas idle=new Canvas();v.bg.draw(idle);check(idle.saves==0,"no offscreen blending when idle");
  ViewPagerActivity tabs=new ViewPagerActivity();LaunchActivity.root=tabs;tabs.viewPager.manual=true;
  for(int x:new int[]{0,-115,-343,-529,-751,-948,0}){a.factory.root.x=x;frame(s);same(pixel(v),original,"recorded navigation cannot dim the material");check(a.factory.last.x==24,"source origin stays pinned");}
  BaseFragment settings=new BaseFragment();settings.view.attached=true;settings.factory=null;LaunchActivity.current=settings;tabs.viewPager.manual=false;settle(s);
  same(pixel(v),original,"screen without source keeps glass");check(a.factory.released==0,"fallback does not destroy active material");
  tabs.viewPager.startedTracking=true;v.y=120;frame(s);check(a.factory.last.y==120,"overlay gesture remains independent");v.y=144;tabs.viewPager.startedTracking=false;
  LaunchActivity.current=a;settle(s);check(a.factory.created==1,"return to original tab reuses source");same(pixel(v),original,"no return pulse");
  BaseFragment b=new BaseFragment();b.view.attached=true;LaunchActivity.current=b;frame(s);check(a.factory.released==0&&b.factory.created==1,"old source remains during replacement");
  for(int i=0;i<20;i++){frame(s);same(pixel(v),original,"equal materials stay identical through every blend frame");}
  check(a.factory.released==1&&b.factory.last.alpha==255,"retire old only after full replacement");
  a.factory.pixel=.3;LaunchActivity.current=a;frame(s);double previous=pixel(v);
  a.factory.last.source.ready=false;
  for(int i=0;i<40;i++){frame(s);same(pixel(v),previous,"cold source cannot consume the blend before capture");}
  a.factory.last.source.ready=true;
  for(int i=0;i<40;i++){SystemClock.now+=16;s.onPreDraw();}
  double firstRendered=pixel(v);check(firstRendered<previous&&firstRendered>previous-.03,"skipped draws cannot fast-forward the entire blend");previous=firstRendered;
  for(int i=0;i<20;i++){frame(s);double next=pixel(v);check(next<=previous+1e-8&&next>.2,"new material blends monotonically without fallback dip");previous=next;}
  for(int i=0;i<120;i++){LaunchActivity.current=i%2==0?a:b;frame(s);pixel(v);check(BlurredBackgroundDrawableViewFactory.live<=2,"at most two sources during rapid reversal");}
  LaunchActivity.current=b;settle(s);same(pixel(v),original,"rapid reversal settles on latest screen");
  Theme.bg=0xff181818;frame(s);settle(s);check(BlurredBackgroundDrawableViewFactory.live==1,"theme releases both previous sources");
  v.h=140;v.bg.setBounds(new Rect(0,0,360,140));check(b.factory.last.getBounds().bottom==140,"expanded banner updates glass bounds");
  SharedConfig.blur=false;frame(s);check(BlurredBackgroundDrawableViewFactory.live==0,"lite mode releases glass");
  SharedConfig.blur=true;Build.VERSION.SDK_INT=30;frame(s);check(BlurredBackgroundDrawableViewFactory.live==0,"old Android uses material");Build.VERSION.SDK_INT=36;
  v.hardware=false;frame(s);check(BlurredBackgroundDrawableViewFactory.live==0,"software canvas does not allocate native source");v.hardware=true;settle(s);
  LaunchActivity.current=null;LaunchActivity.root=null;settle(s);check(BlurredBackgroundDrawableViewFactory.live==0,"missing host releases retained contents");
  s.detach();s.detach();check(v.observer.listeners.isEmpty(),"idempotent detach");
  LaunchActivity.current=a;LaunchActivity.root=tabs;tabs.viewPager.manual=true;s.attach();settle(s);check(BlurredBackgroundDrawableViewFactory.live==0,"cold arrival during swipe waits for a stable source");
  tabs.viewPager.manual=false;settle(s);check(BlurredBackgroundDrawableViewFactory.live==1,"source appears after swipe");
  LaunchActivity.current=b;frame(s);s.detach();check(BlurredBackgroundDrawableViewFactory.live==0,"detach releases both crossfade sources");
  frame(s);check(BlurredBackgroundDrawableViewFactory.live==0,"late pre-draw cannot resurrect detached surface");
  check(BlurredBackgroundDrawableViewFactory.peak<=2,"bounded native layers");
  System.out.println("PASS: "+checks+" glass lifecycle and actual draw compositing checks");
 }
}`;
const dir=fs.mkdtempSync(path.join(os.tmpdir(),'nimarko-notification-glass-'));
try{const run=text=>{fs.writeFileSync(path.join(dir,'NotificationGlassTest.java'),text);cp.execFileSync('javac',['NotificationGlassTest.java'],{cwd:dir});return cp.spawnSync('java',['NotificationGlassTest'],{cwd:dir,encoding:'utf8'});};
 const result=run(java);assert.equal(result.status,0,result.stderr);process.stdout.write(result.stdout);
 for(const broken of [java.replace('if (isNavigationRunning() || incoming != null) return;','if (isNavigationRunning()) { releaseLayers(); return; } if (incoming != null) return;'),
 java.replace('material.draw(canvas);\n                incoming.glass.draw(canvas);','incoming.glass.draw(canvas);'),
 java.replace('view.setPadding(l, t, r, b);',''),
 java.replace('incoming.rendered && incoming.isReady()', 'true'),
 java.replace('incoming.rendered && incoming.isReady()', 'incoming.isReady()')]){assert.notEqual(broken,java);assert.notEqual(run(broken).status,0,'negative control must fail');}
 console.log('PASS: recorded fade-out and translucent-layer brightness pulse fail negative controls');
}finally{fs.rmSync(dir,{recursive:true,force:true});}
