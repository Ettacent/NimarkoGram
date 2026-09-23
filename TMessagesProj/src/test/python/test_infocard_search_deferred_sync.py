"""Production carousel decisions on JVM stubs; no Gradle/APK or Android rendering.

Independent of the sender/avatar harness so concurrent composer work stays isolated.
The actual follow/sync/settle/lifecycle methods and resume callback are compiled;
stubs provide only Android transport, card rendering, and the shared config bus.
"""
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

SRC = Path(__file__).resolve().parents[2] / 'main/java'
STRIP = (SRC / 'app/nimarkogram/messenger/infocards/InfoCardStripView.java').read_text()
DIALOGS = (SRC / 'org/telegram/ui/DialogsActivity.java').read_text()


def method(source, signature):
    start = source.index(signature)
    opening = source.index('{', start)
    depth = 0
    for token in re.finditer(r'//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"|[{}]', source[opening:]):
        if token[0] == '{':
            depth += 1
        elif token[0] == '}':
            depth -= 1
            if depth == 0:
                return source[start:opening + token.end()]
    raise AssertionError(signature)


METHODS = '\n'.join(method(STRIP, signature) for signature in (
    'private boolean isHostVisible(',
    'private void setPendingActiveCard(',
    'private boolean resumePendingActiveCard()',
    'private void followSharedActiveCard()',
    'public void syncToActiveCard()',
    'private void syncToActiveCard(boolean',
    'private boolean reconcilePendingActiveCard()',
    'private BaseInfoCard current()',
    'private ValueAnimator createSettleAnimator(',
    'private void animateCommit(',
    'private void animateSnapBack(',
    'private void cancelAnim()',
    'private void cancelAnimResume()',
    'public void setVisibilityFactor(',
    'public void setHostVisibility(',
    'private void resetForWindowLifecycle()',
    'protected void dispatchDraw(',
    'protected void onAttachedToWindow()',
    'protected void onDetachedFromWindow()',
    'protected void onWindowVisibilityChanged(',
    'public void onWindowFocusChanged(',
))
RESUME = method(STRIP, 'private final Runnable resumeActiveCard =') + ';'
LISTENER = re.search(r'private final ViewTreeObserver.OnPreDrawListener pendingActiveCardListener = [^;]+;', STRIP)[0]

JAVA = r'''
import java.util.*;
import java.util.function.Consumer;
public class SearchDeferredSync {
 static final int VISIBLE=0, INVISIBLE=4, GONE=8;
 static int checks;
 static void check(boolean value, String message) {
  checks++; if (!value) throw new AssertionError(message + " (check " + checks + ")");
 }
 static class ViewTreeObserver {
  interface OnPreDrawListener { boolean onPreDraw(); }
  boolean alive=true; int additions, removals;
  List<OnPreDrawListener> listeners=new ArrayList<>();
  boolean isAlive(){return alive;}
  void addOnPreDrawListener(OnPreDrawListener l){check(!listeners.contains(l), "no duplicate listener");listeners.add(l);additions++;}
  void removeOnPreDrawListener(OnPreDrawListener l){listeners.remove(l);removals++;}
  void frame(){for(var l:new ArrayList<>(listeners))check(l.onPreDraw(), "never cancel a host frame");}
 }
 static class View {
  static final int VISIBLE=0; View parent; float alpha=1; int visibility;
  boolean attached=true, focus=true; int window, invalidations, layouts, posts;
  ViewTreeObserver observer=new ViewTreeObserver(); List<Runnable> callbacks=new ArrayList<>();
  View getParent(){return parent;} float getAlpha(){return alpha;} void setAlpha(float f){alpha=f;}
  boolean isAttachedToWindow(){return attached;} boolean hasWindowFocus(){return focus;}
  int getWindowVisibility(){return window;} int getVisibility(){return visibility;}
  boolean isShown(){return visibility==VISIBLE && (parent==null || parent.isShown());}
  ViewTreeObserver getViewTreeObserver(){return observer;}
  void setVisibility(int v){visibility=v;} void setScaleX(float f){} void setScaleY(float f){}
  void requestLayout(){layouts++;} void invalidate(){invalidations++;}
  void removeCallbacks(Runnable r){callbacks.removeIf(c->c==r);}
  void postOnAnimation(Runnable r){callbacks.add(r);posts++;}
  void runPosted(){var batch=new ArrayList<>(callbacks);callbacks.clear();for(var r:batch)r.run();}
  int getWidth(){return 150;} int getHeight(){return 28;}
  protected void dispatchDraw(Canvas c){}
  protected void onAttachedToWindow(){attached=true;}
  protected void onDetachedFromWindow(){attached=false;}
  protected void onWindowVisibilityChanged(int v){window=v;}
  public void onWindowFocusChanged(boolean f){focus=f;}
 }
 static class Canvas {void save(){} void restore(){} void clipRect(int l,int t,int r,int b){}
  int saveLayer(int l,int t,int r,int b,Object p){return 1;}void restoreToCount(int n){} }
 static class AndroidUtilities {static int dp(int n){return n;}static float lerp(float a,float b,float f){return a+(b-a)*f;}}
 static class LocaleController {static boolean isRTL;}
 interface Interpolator {float getInterpolation(float t);}
 static class android {static class view {static class animation {static class OvershootInterpolator {OvershootInterpolator(float n){}}}}}
 static class Animator {}
 static class AnimatorListenerAdapter {public void onAnimationCancel(Animator a){} public void onAnimationEnd(Animator a){}}
 static class ValueAnimator extends Animator {
  float value, from, to; boolean cancelled;
  List<Consumer<ValueAnimator>> updates=new ArrayList<>();List<AnimatorListenerAdapter> listeners=new ArrayList<>();
  static ValueAnimator ofFloat(float a,float b){var v=new ValueAnimator();v.from=v.value=a;v.to=b;return v;}
  void setDuration(long n){} void setInterpolator(Interpolator i){} void start(){}
  void addUpdateListener(Consumer<ValueAnimator> u){updates.add(u);}void addListener(AnimatorListenerAdapter l){listeners.add(l);}
  Object getAnimatedValue(){return value;}
  void frame(float f){value=from+(to-from)*f;for(var u:updates)u.accept(this);}
  void end(){frame(1);for(var l:listeners)l.onAnimationEnd(this);}
  void cancel(){cancelled=true;for(var l:listeners)l.onAnimationCancel(this);for(var l:listeners)l.onAnimationEnd(this);}
 }
 static class BaseInfoCard {
  int id, refreshes, selections;BaseInfoCard(int id){this.id=id;}
  int getCardId(){return id;}void updateDataInstantly(){refreshes++;}
  void onCardUnselected(){}void onCardSelected(){selections++;}void finishResizeAnimation(){}
 }
 static class InfoCardsConfig {
  static int target, writes;
  static int getLastActiveCardId(){return target;}
  static void setLastActiveCardId(int id){target=id;writes++;}
 }
 static class NotificationCenter {
  static final int infoCardsLayoutChanged=1,infoCardsSettingsChanged=2,infoCardsColorModeChanged=3,
   infoCardsActiveCardChanged=4,didSetNewTheme=5;
  static final NotificationCenter instance=new NotificationCenter();List<Strip> active=new ArrayList<>();
  static NotificationCenter getGlobalInstance(){return instance;}
  void addObserver(Strip s,int id){if(id==infoCardsActiveCardChanged&&!active.contains(s))active.add(s);}
  void removeObserver(Strip s,int id){if(id==infoCardsActiveCardChanged)active.remove(s);}
  void postNotificationName(int id){for(var s:new ArrayList<>(active))s.followSharedActiveCard();}
 }
 static class Strip extends View {
  ArrayList<BaseInfoCard> pills=new ArrayList<>();int currentIndex, incomingIndex=-1, pendingActiveCardId=-1;
  boolean dragging, dragUp, settlingToNext, hasPresentedCard, inlineFolderStyle, potentialTap, longPressFired;
  float visibilityFactor=1, dragProgress, releaseVelocity; ValueAnimator animator;
  boolean dragCrossedCard;
  ViewTreeObserver pendingActiveCardObserver;Runnable longPressRunnable=()->{};
  List<Integer> prepared=new ArrayList<>();int rests;
  LISTENER
  RESUME
  Strip(){for(int i=0;i<4;i++)pills.add(new BaseInfoCard(i));}
  void ensureIncomingPrepared(int n){incomingIndex=n;prepared.add(n);pills.get(n).updateDataInstantly();}
  void applyResting(){applyResting(true);}
  void applyResting(boolean notify){rests++;incomingIndex=-1;if(notify&&current()!=null)current().onCardSelected();}
  float dragHeight(){return 28;}void applyDrag(int n,float p,boolean up,float h){dragProgress=p;}
  void setCardsPressed(boolean b){}void releaseTracker(){}
  void rebuildIfChanged(){}void armGlobalTicker(){}void armRateTicker(){}
  int refreshes(){int count=0;for(var p:pills)count+=p.refreshes;return count;}
  void present(){dispatchDraw(new Canvas());check(hasPresentedCard,"card presented");}
  // Drawing-mask geometry is executed separately by test_infocard_edge_fade.
  float getCarouselEdgeFade(){return 0;}void drawCarouselEdgeFade(Canvas c,float e,int l,int r){}
  void finish(){check(animator!=null,"animation exists");animator.end();}
  METHODS
 }
 static void tick(int id, Strip... strips){InfoCardsConfig.target=id;for(var s:strips)s.followSharedActiveCard();}
 static void pending(Strip s,int id){
  check(s.currentIndex==0 && s.animator==null && s.prepared.isEmpty(),"retain displayed card while hidden");
  check(s.pendingActiveCardId==id,"latest target retained");
 }
 static void shown(Strip s,int id){
  s.observer.frame();check(s.currentIndex==0 && s.animator!=null,"return starts animation, not snap");
  check(s.prepared.equals(Arrays.asList(id)),"only latest incoming card prepared");
  check(s.pendingActiveCardId==-1 && s.observer.listeners.isEmpty(),"consume target and listener once");
  var running=s.animator;for(int i=0;i<20;i++)s.observer.frame();
  check(s.animator==running && s.prepared.size()==1,"no replay on later host frames");
  s.finish();check(s.currentIndex==id && s.animator==null,"lands on latest card");
 }
 static void search(){
  Strip s=new Strip();s.present();s.alpha=0;
  tick(1,s);tick(2,s);tick(3,s);pending(s,3);
  // Exact on-home-appear call order: visible, public sync, then host fade.
  s.setVisibility(VISIBLE);s.syncToActiveCard();pending(s,3);
  for(float f:new float[]{0,.1f,.5f,.99f,.999f}){s.alpha=f;s.observer.frame();pending(s,3);}
  check(s.refreshes()==0 && s.posts==0 && s.invalidations==0 && s.layouts==0,"hidden path does no refresh or frame work");
  s.alpha=1;shown(s,3);
  check(s.refreshes()==1,"prepare only selected incoming card");
 }
 static void ancestors(){
  for(int level=0;level<3;level++){
   Strip s=new Strip();View parent=new View(),root=new View();s.parent=parent;parent.parent=root;s.present();
   View hidden=level==0?s:level==1?parent:root;
   hidden.alpha=0;tick(2,s);pending(s,2);
   for(int i=0;i<100;i++)s.observer.frame();
   check(s.observer.additions==1 && s.posts==0 && s.invalidations==0,"no perpetual invalidation or re-registration");
   hidden.alpha=.9999f;s.observer.frame();pending(s,2);hidden.alpha=1;shown(s,2);
  }
  Strip s=new Strip();View root=new View();s.parent=root;root.alpha=0;
  s.dispatchDraw(new Canvas());check(!s.hasPresentedCard,"hidden ancestor is not a presentation");
 }
 static void visibility(){
  for(int mode=0;mode<5;mode++){
   Strip s=new Strip();View root=new View();s.parent=root;s.present();
   if(mode==0)s.visibility=GONE;if(mode==1)root.visibility=INVISIBLE;
   if(mode==2)s.visibilityFactor=.999f;if(mode==3)s.window=INVISIBLE;if(mode==4)s.focus=false;
   tick(2,s);s.observer.frame();pending(s,2);
   s.visibility=root.visibility=VISIBLE;s.visibilityFactor=1;s.window=VISIBLE;s.focus=true;shown(s,2);
  }
  Strip s=new Strip();s.present();s.setVisibilityFactor(0);tick(3,s);
  s.syncToActiveCard();s.setVisibilityFactor(.999f);s.observer.frame();pending(s,3);
  s.setVisibilityFactor(1);shown(s,3);
 }
 static void latest(){
  Strip s=new Strip();s.present();s.alpha=0;tick(2,s);tick(0,s);
  check(s.pendingActiveCardId==-1 && s.observer.listeners.isEmpty(),"wrap to displayed card cancels deferred work");
  s.alpha=1;s.observer.frame();check(s.animator==null && s.refreshes()==0,"no redundant animation");
  s.alpha=0;tick(1,s);InfoCardsConfig.target=3;s.alpha=1;shown(s,3);
  s=new Strip();s.present();s.alpha=0;tick(3,s);s.pills.remove(3);s.alpha=1;s.observer.frame();
  check(s.animator==null && s.pendingActiveCardId==-1 && s.observer.listeners.isEmpty(),"removed target clears work");
  s.pills.clear();tick(99,s);check(s.animator==null && s.observer.listeners.isEmpty(),"empty/invalid target safe");
  s.pills.add(new BaseInfoCard(0));tick(0,s);check(s.animator==null,"single card never animates to itself");
 }
 static void busy(){
  Strip s=new Strip();s.present();tick(1,s);var first=s.animator;
  tick(2,s);tick(3,s);s.observer.frame();check(s.animator==first && s.prepared.size()==1,"busy animation owns transforms");
  s.alpha=0;first.end();
  check(s.currentIndex==1 && s.pendingActiveCardId==3 && s.animator==null,"completion defers newer target under hidden host");
  check(InfoCardsConfig.target==3 && InfoCardsConfig.writes==0,"old completion cannot overwrite latest shared id");
  s.alpha=1;s.observer.frame();check(s.prepared.equals(Arrays.asList(1,3)),"skip intermediate busy ticks");
  s.finish();check(s.currentIndex==3 && InfoCardsConfig.target==3,"latest wins after busy return");
  s=new Strip();s.present();s.dragging=true;tick(1,s);tick(3,s);s.alpha=0;s.setVisibilityFactor(0);
  pending(s,3);s.setVisibilityFactor(1);shown(s,3);
  s=new Strip();s.present();s.dragging=true;tick(2,s);s.dragging=false;s.animateSnapBack(-1);s.alpha=0;s.finish();
  pending(s,2);s.alpha=1;shown(s,2);
 }
 static void both(){
  Strip home=new Strip(),search=new Strip();home.present();search.present();
  NotificationCenter.instance.active.add(home);NotificationCenter.instance.active.add(search);
  home.alpha=0;tick(1,home,search);search.finish();
  tick(2,home,search);tick(3,home,search);search.finish();search.finish();
  check(search.currentIndex==3 && home.currentIndex==0 && home.refreshes()==0,"hidden home retains while search advances");
  search.alpha=0;home.alpha=.5f;home.syncToActiveCard();home.observer.frame();pending(home,3);
  home.alpha=1;shown(home,3);
  check(search.pendingActiveCardId==-1 && search.animator==null,"matching other strip does not echo/replay");
  // Reverse ownership: home advances while the search ancestor is transparent.
  View root=new View();search.parent=root;search.alpha=1;root.alpha=0;
  tick(0,home,search);home.finish();check(search.currentIndex==3 && search.pendingActiveCardId==0,"search defers ancestor-hidden tick");
  root.alpha=1;search.observer.frame();search.finish();
  check(home.currentIndex==0 && search.currentIndex==0,"both strips converge without stale shared writes");
 }
 static void lifecycle(){
  Strip s=new Strip();s.present();s.alpha=0;tick(2,s);ViewTreeObserver old=s.observer;
  s.onDetachedFromWindow();check(old.listeners.isEmpty() && s.pendingActiveCardObserver==null,"detach releases observer");
  InfoCardsConfig.target=3;s.observer=new ViewTreeObserver();s.onAttachedToWindow();s.runPosted();pending(s,3);
  check(s.observer.listeners.size()==1,"reattach listens on new live observer");s.alpha=1;shown(s,3);
  s=new Strip();s.present();s.onWindowVisibilityChanged(INVISIBLE);tick(1,s);tick(3,s);
  s.onWindowVisibilityChanged(VISIBLE);s.onWindowFocusChanged(false);s.runPosted();pending(s,3);
  s.onWindowFocusChanged(true);s.alpha=.5f;s.runPosted();pending(s,3);s.alpha=1;shown(s,3);
  s=new Strip();s.present();s.alpha=0;tick(2,s);s.observer.alive=false;s.onDetachedFromWindow();
  check(s.pendingActiveCardObserver==null && s.callbacks.isEmpty(),"dead observer and posted callbacks cleaned up");
 }
 static void cold(){
  Strip s=new Strip();s.alpha=0;InfoCardsConfig.target=2;s.syncToActiveCard();
  check(s.currentIndex==2 && s.animator==null && s.refreshes()==1,"cold seed stays instant");
  s.alpha=.5f;s.present();s.alpha=0;tick(3,s);s.syncToActiveCard();
  check(s.currentIndex==2 && s.pendingActiveCardId==3 && s.refreshes()==1,"even partial presentation is retained");
  s.alpha=1;s.observer.frame();s.finish();int refreshes=s.refreshes();
  s.syncToActiveCard();check(s.refreshes()==refreshes && s.animator==null,"repeat home appear does not refetch or reanimate");
 }
 public static void main(String[] args){
  switch(args[0]){
   case "search":search();break;case "ancestors":ancestors();break;case "visibility":visibility();break;
   case "latest":latest();break;case "busy":busy();break;case "both":both();break;
   case "lifecycle":lifecycle();break;case "cold":cold();break;default:throw new AssertionError(args[0]);
  }
  System.out.println("PASS " + args[0] + ": " + checks + " production-method assertions");
 }
}
'''.replace('METHODS', METHODS).replace('RESUME', RESUME).replace('LISTENER', LISTENER)


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'JDK required')
class SearchDeferredSyncTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='infocard-search-sync-')
        cls.addClassCleanup(cls.temp.cleanup)
        path = Path(cls.temp.name) / 'SearchDeferredSync.java'
        path.write_text(JAVA)
        result = subprocess.run(['javac', str(path)], capture_output=True, text=True)
        if result.returncode:
            raise AssertionError(result.stderr)

    def run_case(self, case):
        result = subprocess.run(['java', '-cp', self.temp.name, 'SearchDeferredSync', case], capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_search_home_appear_defers_until_fully_shown(self):
        self.run_case('search')

    def test_ancestor_alpha_and_passive_observation(self):
        self.run_case('ancestors')

    def test_visibility_factor_window_and_focus_gates(self):
        self.run_case('visibility')

    def test_latest_only_wraparound_removed_and_empty_cards(self):
        self.run_case('latest')

    def test_busy_settle_and_drag_do_not_snap_or_overwrite_shared_target(self):
        self.run_case('busy')

    def test_both_strips_converge_without_echo_replay(self):
        self.run_case('both')

    def test_detach_reattach_window_focus_and_dead_observer(self):
        self.run_case('lifecycle')

    def test_cold_seed_and_public_sync_after_presentation(self):
        self.run_case('cold')

    def test_negative_controls_reject_hidden_snap_and_ancestor_blindness(self):
        hidden_branch = '''} else if (!isHostVisible(1f)) {
            setPendingActiveCard(targetId);'''
        ancestor_step = 'view = view.getParent() instanceof View ? (View) view.getParent() : null'
        for case, before, after in (
            ('search', hidden_branch, '''} else if (!isHostVisible(1f)) {
            syncToActiveCard(true);'''),
            ('ancestors', ancestor_step, 'view = null'),
        ):
            with self.subTest(case=case), tempfile.TemporaryDirectory(prefix='infocard-search-negative-') as folder:
                self.assertIn(before, JAVA)
                path = Path(folder) / 'SearchDeferredSync.java'
                path.write_text(JAVA.replace(before, after))
                build = subprocess.run(['javac', str(path)], capture_output=True, text=True)
                self.assertEqual(build.returncode, 0, build.stderr)
                run = subprocess.run(['java', '-cp', folder, 'SearchDeferredSync', case], capture_output=True, text=True)
                self.assertNotEqual(run.returncode, 0, 'regression mutation must fail the production-method checks')
                self.assertIn('AssertionError', run.stderr)

    def test_host_wiring_and_cleanup(self):
        self.assertIn('homeInfoCards.syncToActiveCard();', DIALOGS)
        self.assertIn('contentView.indexOfChild(filterTabsView) + 1', DIALOGS)
        self.assertIn('contentView.addView(homeInfoCards, Math.max(0, cardLayer)', DIALOGS)
        for signature in ('public void rebuildIfChanged()', 'public void rebuild()', 'private void resetForWindowLifecycle()'):
            self.assertIn('setPendingActiveCard(-1)', method(STRIP, signature))
        # Deferral itself must not introduce data/network work or drive frames.
        for signature in ('private void setPendingActiveCard(', 'private boolean resumePendingActiveCard()'):
            body = method(STRIP, signature)
            for forbidden in ('invalidate(', 'postOnAnimation(', 'postDelayed(', 'updateDataInstantly(', 'onUpdateData('):
                self.assertNotIn(forbidden, body)


if __name__ == '__main__':
    unittest.main()
