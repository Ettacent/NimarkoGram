const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),cp=require('node:child_process'),assert=require('node:assert/strict');
const src=fs.readFileSync(path.resolve(__dirname,'../../main/java/app/nimarkogram/messenger/notifications/NimarkoInAppNotifications.java'),'utf8');
function method(signature){
 const start=src.indexOf(signature);assert(start>=0,signature);
 const tokens=/\/\/[^\n]*|\/\*[\s\S]*?\*\/|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[{}]/g;
 tokens.lastIndex=src.indexOf('{',start);let depth=0;
 for(let token;(token=tokens.exec(src));){if(token[0]==='{')depth++;if(token[0]==='}'&&--depth===0)return src.slice(start,tokens.lastIndex);}
 throw Error(signature);
}
const show=method('private static boolean show('),replace=method('boolean replaceMessage(');
const transitions=['void replaceText(', 'void fadeText(', 'void cancelContentTransition()', 'void cancelExpansion()', 'void setExpansion(', 'void setPullOffset(', 'void settlePull(', 'void settleGeometry('].map(signature=>method(signature)).join('\n');
const expiry=src.match(/else if \((focused && !touching && !contentGesture[\s\S]*?)\) \{\s*hide\(\);/)[1];
// Retirement must preserve the outgoing pull position: fade alpha, never snap/translate it.
assert.match(show,/old.animate\(\).alpha\(0\).setDuration\(230\)/);
assert.doesNotMatch(show.slice(show.indexOf('Banner old = retiringBanner;'),show.indexOf('banner = next;')),/\.(?:setTranslation[XY]|translation[XY]|setScale[XY]|scale[XY])\(/);
assert(show.indexOf('old.cancelExpansion()')<show.indexOf('old.animate().alpha(0)'));
assert(show.indexOf('old.cancelContentTransition()')<show.indexOf('old.animate().alpha(0)'));
assert(show.indexOf('remove(retiringBanner)')<show.indexOf('retiringBanner = banner'));
const preview=method('public static void preview()');
assert(preview.indexOf('allowed(')<preview.indexOf('show('));
assert.doesNotMatch(preview,/startPreview|postDelayed|runOnUIThread/);
assert.doesNotMatch(src,/tracePreview\(/);
assert.match(method('void hide()'),/cancelContentTransition\(\)/);
assert.match(method('protected void onDetachedFromWindow()'),/cancelContentTransition\(\)/);
assert.doesNotMatch(method('void animateOpenChat()'),/cancelContentTransition\(\)/);
const java=`
import java.util.*;import java.lang.ref.*;import java.util.function.Consumer;
public class NotificationBurstTest {
 static class Animator {}
 static class AnimatorListenerAdapter {public void onAnimationEnd(Animator animation){}}
 static class ValueAnimator extends Animator {
  static int starts;float from,target,value,fraction;boolean cancelled,ended;Consumer<ValueAnimator> update;AnimatorListenerAdapter listener;
  static ValueAnimator ofFloat(float from,float target){ValueAnimator a=new ValueAnimator();a.from=a.value=from;a.target=target;return a;}
  void setDuration(long d){}void setInterpolator(Interpolator i){}void addUpdateListener(Consumer<ValueAnimator> l){update=l;}
  void addListener(AnimatorListenerAdapter l){listener=l;}Float getAnimatedValue(){return value;}float getAnimatedFraction(){return fraction;}void start(){starts++;}
  // Android cancel() also calls onAnimationEnd synchronously; do not hide cancellation races.
  void cancel(){cancelled=true;end();}void end(){if(!ended){ended=true;if(listener!=null)listener.onAnimationEnd(this);}}
  void step(float p){if(!cancelled&&!ended){fraction=p;value=from+(target-from)*p;update.accept(this);if(p==1)end();}}
  void lateUpdate(float v){value=v;update.accept(this);}void lateEnd(){if(listener!=null)listener.onAnimationEnd(this);}
 }
 static class View {static int IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS=4;}
 static class LaunchActivity {static Object getLastFragmentIncludeMainTabs(){return null;}}
 static class AnimatedLinearLayout {Slot slot=new Slot();}
 static class Delivery {boolean active=true;boolean isActive(){return active;}}
 static class NotificationColorTrace {static Object startPreview(Object view){return new Object();}}
 static class SystemClock {static long now=1000;static long elapsedRealtime(){return now;}}
 interface Interpolator {float getInterpolation(float t);}
 static class CubicBezierInterpolator {static Interpolator EASE_BOTH=t->t,Emphasized=t->t,EASE_OUT_QUINT=t->t,EASE_OUT=t->t;}
 static class R {static class string{static int AppName=1,NotificationHiddenMessage=2;}}
 static class TextUtils {static boolean equals(CharSequence a,CharSequence b){return Objects.equals(a,b);}}
 static class UserConfig {static int selectedAccount=-1;static int getActivatedAccountsCount(){return 2;}static UserConfig getInstance(int a){return new UserConfig();}Object getCurrentUser(){return null;}}
 static class UserObject {static String getFirstName(Object u){return "account";}}
 static String getString(int id){return id==1?"NimarkoGram":"hidden";}static int dp(int n){return n;}
 static WeakReference<LaunchActivity> host=new WeakReference<>(new LaunchActivity());static AnimatedLinearLayout panel=new AnimatedLinearLayout();
 static Banner banner,retiringBanner;static boolean contentGesture,navigation,focused=true;static ArrayList<Runnable> frames=new ArrayList<>();
 static boolean navigationRunning(Object fragment){return navigation;}
 static AnimatedLinearLayout resolvePanel(){return panel;}static AnimatedLinearLayout resolvePanel(Banner b){return panel;}
 static boolean isCurrent(int a,long o,long s){return true;}static boolean allowed(int a,long o,long d,boolean sample){return true;}
 static void remove(Banner b){if(b==null)return;b.closing=true;b.cancelExpansion();b.cancelContentTransition();b.attached=false;if(b.slot!=null)b.slot.children.remove(b);b.anim.end=null;}
 static void removeCurrent(){remove(banner);remove(retiringBanner);banner=retiringBanner=null;}
 static class Slot {AnimatedLinearLayout panel;boolean directResize;int layouts;ArrayList<Banner> children=new ArrayList<>();
  void requestLayout(){layouts++;}
  static Slot obtain(AnimatedLinearLayout p){p.slot.panel=p;return p.slot;}void attach(Banner b){directResize=false;b.slot=this;b.attached=true;children.add(b);}
 }
 static class Label {String text="";float alpha=1;void setText(CharSequence v){text=v.toString();}String getText(){return text;}
  void setAlpha(float v){alpha=v;}float getAlpha(){return alpha;}}
 static class Banner {
  boolean gestureFramePending;Runnable gestureFrame;
  Object previewTrace;
  int account,messageId;long owner=9,loginSession=1,dialogId,topicId;boolean preview=true,sample,closing,opening,touching,expanded,attached;
  Delivery delivery=new Delivery();Slot slot;long expiresAt;int watches,avatarRefresh;String avatarHeading="person";
  Label title=new Label(),body=new Label(),expandedBody=new Label(),text=new Label(),bodies=new Label();Object watch=new Object();Anim anim=new Anim(this);
  ValueAnimator contentAnimator,expansionAnimator,pullAnimator;boolean contentFadeOut;String pendingName,pendingMessage;float pullOffset,y,scaleX=1,scaleY=1,alpha=1;
  Banner(long d,int m){dialogId=d;messageId=m;body.text=expandedBody.text="m"+m;}
  void tracePreview(String s){}void setAlpha(float v){alpha=v;}void setTranslationY(float v){y=v;}void setScaleX(float v){scaleX=v;}void setScaleY(float v){scaleY=v;}
  float expansion,releaseVelocity;float getAlpha(){return alpha;}void requestLayout(){}int getHeight(){return 80;}
  void setImportantForAccessibility(int v){}void removeCallbacks(Object r){}void postDelayed(Object r,long delay){watches++;}
  void postOnAnimation(Runnable r){frames.add(r);}boolean isAttachedToWindow(){return attached;}void refreshAvatar(){avatarRefresh++;}
  Anim animate(){return anim;}
  ${replace}
  ${transitions}
  void finishContent(){int steps=0;while(contentAnimator!=null){if(++steps>4)throw new AssertionError("unbounded content fade");contentAnimator.step(1);}}
  boolean shouldExpire(){return ${expiry};}
 }
 static class Anim {final Banner owner;Anim(Banner b){owner=b;}Runnable end;int starts;void cancel(){end=null;}Anim alpha(float a){owner.alpha=a;return this;}Anim translationY(float a){owner.y=a;return this;}
  Anim scaleX(float a){owner.scaleX=a;return this;}Anim scaleY(float a){owner.scaleY=a;return this;}Anim setDuration(long a){return this;}Anim setInterpolator(Object a){return this;}
  Anim withEndAction(Runnable r){end=r;return this;}void start(){starts++;}}
 ${show}
 static int checks;static void check(boolean v,String why){checks++;if(!v)throw new AssertionError(why);}
 static void frame(){ArrayList<Runnable> tasks=new ArrayList<>(frames);frames.clear();for(Runnable r:tasks)r.run();}
 static void reset(){removeCurrent();panel=new AnimatedLinearLayout();contentGesture=false;frames.clear();}
 static boolean update(Banner b,int id,boolean preview){return b.replaceMessage(b.account,b.owner,b.loginSession,b.dialogId,b.topicId,id,"person","message"+id,preview,new Delivery());}
 public static void main(String[] args){
  reset();Banner stable=new Banner(100,1);stable.title.text="person";show(stable);frame();
  UserConfig.selectedAccount=stable.account;
  check(update(stable,2,true)&&stable.pendingName.equals("person"),"current account has no redundant name suffix");
  stable.contentAnimator.step(.5f);
  check(stable.title.alpha==1&&stable.text.alpha==1&&stable.bodies.alpha==.5f,"unchanged sender stays opaque while body fades");
  ValueAnimator unchanged=stable.contentAnimator;update(stable,3,true);
  check(stable.contentAnimator==unchanged&&stable.title.alpha==1,"same-sender burst does not restart heading animation");
  stable.contentAnimator.step(1);
  check(stable.title.alpha==1&&stable.bodies.alpha==0,"sender remains visible when new message is committed");
  stable.contentAnimator.step(.4f);
  check(stable.title.alpha==1&&stable.bodies.alpha==.4f,"sender remains visible during body fade-in");
  stable.finishContent();
  UserConfig.selectedAccount=stable.account+1;
  update(stable,4,true);stable.contentAnimator.step(.5f);
  check(stable.pendingName.equals("person · account")&&stable.title.alpha==.5f,"other account is identified and actual heading changes animate");
  float headingAlpha=stable.title.alpha,bodyAlpha=stable.bodies.alpha;
  UserConfig.selectedAccount=stable.account;update(stable,5,true);
  check(stable.title.alpha==headingAlpha&&stable.bodies.alpha==bodyAlpha,"changed heading target preserves current opacity");
  stable.finishContent();
  check(stable.title.text.equals("person")&&stable.title.alpha==1&&stable.bodies.alpha==1,"return to unchanged heading settles without a flash");
  stable.setExpansion(.5f);update(stable,6,true);stable.contentAnimator.step(.5f);
  check(stable.body.alpha==.5f&&stable.expandedBody.alpha==.5f,"content fading does not override expanded/collapsed crossfade");
  stable.finishContent();
  UserConfig.selectedAccount=-1;
  reset();Banner b=new Banner(100,1);show(b);frame();int starts=b.anim.starts;
  int fadeStarts=ValueAnimator.starts;
  for(int i=2;i<=1000;i++){b.delivery.active=false;check(update(b,i,true),"same peer coalesces");check(banner==b&&b.messageId==i&&b.delivery.active,"latest destination and delivery token");check(panel.slot.children.size()==1&&retiringBanner==null,"one card for burst");
   check(b.pendingMessage.equals("message"+i)&&b.body.text.equals("m1")&&ValueAnimator.starts==fadeStarts+1,"burst coalesces latest pending text without restarting fade-out");}
  b.finishContent();check(b.body.text.equals("message1000")&&b.expandedBody.text.equals(b.body.text)&&b.bodies.alpha==1&&b.pendingMessage==null,"burst settles to latest collapsed and expanded text");
  check(b.anim.starts==starts&&b.watches==1,"burst does not restart entrance or duplicate watchdogs");
  b.expanded=true;update(b,1001,true);check(b.expanded&&b.expiresAt==9000,"expanded state and reading time preserved");b.finishContent();
  Delivery currentDelivery=b.delivery;long currentExpiry=b.expiresAt;SystemClock.now+=1000;
  update(b,900,true);check(b.messageId==1001&&b.body.text.equals("message1001"),"out of order older message does not replace latest content");
  check(b.delivery==currentDelivery&&b.expiresAt==currentExpiry,"older message cannot replace cancellation token or extend expiry");
  for(int reason=0;reason<13;reason++){
   reset();b=new Banner(100,1);show(b);frame();
   int a=b.account;long o=b.owner,s=b.loginSession,d=b.dialogId,t=b.topicId;boolean p=true;
   switch(reason){case 0:a++;break;case 1:o++;break;case 2:s++;break;case 3:d++;break;case 4:t++;break;case 5:p=false;break;
    case 6:b.touching=true;break;case 7:b.opening=true;break;case 8:b.closing=true;break;case 9:b.sample=true;break;
    case 10:contentGesture=true;break;case 11:panel=new AnimatedLinearLayout();break;case 12:b.attached=false;break;}
   check(b.replaceMessage(a,o,s,d,t,2,"person","new",p,new Delivery())==(reason==10),"scroll permits replacement but ownership/privacy/banner gesture/host mismatch blocks it "+reason);
  }
  reset();b=new Banner(100,1);b.preview=false;show(b);frame();update(b,2,false);check(b.pendingMessage.equals("hidden"),"hidden preview queues only redacted text");b.finishContent();check(b.body.text.equals("hidden")&&b.expandedBody.text.equals("hidden"),"hidden preview remains hidden");
  // Execute extracted fade methods, not a synchronous setText stand-in.
  reset();b=new Banner(100,1);show(b);frame();update(b,2,true);ValueAnimator fadeOut=b.contentAnimator;fadeOut.step(.4f);
  float midAlpha=b.bodies.alpha;update(b,3,true);
  check(b.contentAnimator==fadeOut&&b.bodies.alpha==midAlpha&&b.body.text.equals("m1")&&b.pendingMessage.equals("message3"),"in-flight fade-out retains old text and coalesces latest message");
  b.slot.directResize=true;fadeOut.step(1);ValueAnimator fadeIn=b.contentAnimator;
  check(fadeIn!=null&&fadeIn!=fadeOut&&!b.contentFadeOut&&b.bodies.alpha==0&&!b.slot.directResize,"commit at zero alpha switches to animated content sizing and fade-in");
  check(b.title.text.equals("person · account")&&b.body.text.equals("message3")&&b.expandedBody.text.equals("message3")&&b.pendingName==null&&b.pendingMessage==null,"fade-out commits latest title and both message labels atomically");
  fadeIn.step(.4f);float visibleAlpha=b.bodies.alpha;update(b,4,true);ValueAnimator replacementFade=b.contentAnimator;
  check(fadeIn.cancelled&&replacementFade!=fadeIn&&b.contentFadeOut&&Math.abs(replacementFade.from-visibleAlpha)<.001f&&b.bodies.alpha==visibleAlpha,"arrival during fade-in reverses from current alpha without a flash");
  fadeIn.lateUpdate(1);fadeIn.lateEnd();fadeOut.lateUpdate(0);fadeOut.lateEnd();
  check(b.contentAnimator==replacementFade&&b.bodies.alpha==visibleAlpha&&b.body.text.equals("message3")&&b.pendingMessage.equals("message4"),"retired fade callbacks cannot mutate alpha content or current animator");
  update(b,5,true);update(b,6,true);check(b.contentAnimator==replacementFade&&b.pendingMessage.equals("message6"),"second burst still shares its single fade-out");
  update(b,5,true);check(b.messageId==6&&b.pendingMessage.equals("message6"),"older message cannot overwrite pending latest text");
  b.finishContent();check(b.body.text.equals("message6")&&b.expandedBody.text.equals("message6")&&b.bodies.alpha==1&&b.contentAnimator==null,"latest message wins after fade reversal");
  // Cancellation applies pending text only for a live card; a closing card must not flash.
  for(boolean closing:new boolean[]{false,true}){
   reset();b=new Banner(100,1);show(b);frame();update(b,2,true);ValueAnimator cancelledFade=b.contentAnimator;cancelledFade.step(.5f);
   b.closing=closing;int beforeCancel=ValueAnimator.starts;float beforeAlpha=b.bodies.alpha;b.cancelContentTransition();
   check(cancelledFade.cancelled&&b.contentAnimator==null&&b.pendingName==null&&b.pendingMessage==null&&ValueAnimator.starts==beforeCancel,"cancel retires fade before synchronous end and leaves no pending work");
   check(b.body.text.equals(closing?"m1":"message2")&&b.expandedBody.text.equals(b.body.text),"cancel applies pending latest text only when not closing");
   check(b.bodies.alpha==(closing?beforeAlpha:1f),"closing cancellation preserves midfade alpha while live cancellation restores visibility");
   cancelledFade.lateUpdate(0);cancelledFade.lateEnd();
   check(b.contentAnimator==null&&b.bodies.alpha==(closing?beforeAlpha:1f)&&b.body.text.equals(closing?"m1":"message2"),"cancelled fade cannot resurrect text alpha or transitions");
  }
  reset();b=new Banner(100,1);show(b);frame();update(b,2,true);b.contentAnimator.step(1);b.contentAnimator.step(.25f);
  b.cancelContentTransition();check(b.body.text.equals("message2")&&b.bodies.alpha==1&&b.contentAnimator==null,"cancelling fade-in keeps committed text visible without pending content");
  reset();b=new Banner(100,1);show(b);frame();update(b,2,true);b.opening=true;b.finishContent();
  check(b.body.text.equals("message2")&&b.expandedBody.text.equals("message2")&&b.bodies.alpha==1,"opening does not block latest content fade completion");
  // show() must cancel both old animation families, but never move or brighten the retiring card.
  reset();b=new Banner(100,1);show(b);frame();update(b,2,true);ValueAnimator retiringFade=b.contentAnimator;retiringFade.step(.5f);
  final int[] pullCompletions={0};b.setPullOffset(24);b.settlePull(0,260,()->pullCompletions[0]++);ValueAnimator retiringPull=b.pullAnimator;retiringPull.step(.25f);
  float retiringY=b.y,retiringOffset=b.pullOffset,retiringTextAlpha=b.bodies.alpha;b.scaleX=.96f;b.scaleY=.98f;
  Banner successor=new Banner(200,1);show(successor);
  check(retiringBanner==b&&b.closing&&retiringFade.cancelled&&retiringPull.cancelled&&b.contentAnimator==null&&b.pullAnimator==null,"show retires content and pull animators");
  check(b.y==retiringY&&b.pullOffset==retiringOffset&&b.scaleX==.96f&&b.scaleY==.98f&&b.bodies.alpha==retiringTextAlpha&&b.body.text.equals("m1"),"retirement fades parent alpha without spatial change or midfade text flash");
  retiringPull.lateUpdate(-80);retiringPull.lateEnd();retiringFade.lateUpdate(1);retiringFade.lateEnd();
  check(banner==successor&&b.y==retiringY&&b.bodies.alpha==retiringTextAlpha&&pullCompletions[0]==0,"retired callbacks cannot navigate move or brighten outgoing card");
  reset();ArrayList<Runnable> stale=new ArrayList<>();
  for(int i=0;i<1000;i++){Banner next=new Banner(i+100,1);show(next);frame();check(panel.slot.children.size()<=2,"different chats have at most one retiring card");if(retiringBanner!=null&&retiringBanner.anim.end!=null)stale.add(retiringBanner.anim.end);}
  Banner latest=banner,outgoing=retiringBanner;stale.get(0).run();check(retiringBanner==outgoing&&panel.slot.children.size()==2,"stale callback cannot orphan a newer outgoing card");
  for(Runnable r:stale)r.run();check(banner==latest&&panel.slot.children.size()==1,"stale exit callbacks cannot remove current card");
  reset();show(new Banner(100,1));frame();b=new Banner(200,1);show(b);frame();check(retiringBanner!=null,"different peer crossfade exists");update(b,2,true);check(retiringBanner==null&&panel.slot.children.size()==1,"next same-peer message clears outgoing remnants");
  reset();b=new Banner(100,1);show(b);frame();b.closing=true;show(new Banner(100,2));frame();check(banner!=b&&banner.messageId==2,"arrival during closing uses new card");
  reset();b=new Banner(100,1);b.delivery.active=false;check(!show(b)&&panel.slot.children.isEmpty(),"cancelled request never attaches");
  reset();contentGesture=true;b=new Banner(100,1);check(show(b),"content scrolling must not downgrade delivery to a system notification");frame();
  check(banner==b&&panel.slot.children.size()==1&&b.watches==1,"scrolling delivers and schedules the same native banner normally");
  reset();show(new Banner(100,1));show(new Banner(200,1));frame();check(banner.watches==1,"stale entrance callbacks do not schedule duplicate timers");
  for(int flags=0;flags<32;flags++){
   contentGesture=(flags&1)!=0;navigation=(flags&2)!=0;banner.touching=(flags&4)!=0;focused=(flags&8)!=0;
   banner.expiresAt=SystemClock.now+((flags&16)!=0?1:-1);
   boolean expected=!contentGesture&&!navigation&&!banner.touching&&focused&&(flags&16)==0;
   check(banner.shouldExpire()==expected,"ordinary expiry never collapses layout during gesture/navigation");
  }
  System.out.println("PASS: "+checks+" actual-method notification burst checks; 1000-message series, overlap, privacy, ownership, stale callbacks and gestures");
 }
}`;
const dir=fs.mkdtempSync(path.join(os.tmpdir(),'nimarko-notification-burst-'));
try{
 const run=code=>{fs.writeFileSync(path.join(dir,'NotificationBurstTest.java'),code);let r=cp.spawnSync('javac',[path.join(dir,'NotificationBurstTest.java')],{encoding:'utf8'});assert.equal(r.status,0,r.stderr);return cp.spawnSync('java',['-cp',dir,'NotificationBurstTest'],{encoding:'utf8'});};
 let result=run(java);assert.equal(result.status,0,result.stdout+result.stderr);process.stdout.write(result.stdout);
 for(const mutation of [
  java.replace('if (this.messageId > 0 && messageId > 0 && messageId < this.messageId) return true;',''),
  java.replace('if (retiringBanner == old)', 'if (true)'),
  java.replace('this.topicId != topicId','false'),
 ]){result=run(mutation);assert.notEqual(result.status,0,'negative control must fail');}
 console.log('PASS: old-content, stale-exit and cross-topic negative controls');
}finally{fs.rmSync(dir,{recursive:true,force:true});}
