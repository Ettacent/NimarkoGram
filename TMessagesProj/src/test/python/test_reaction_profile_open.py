"""First-layout reaction-profile handoff, using production methods on JVM stubs."""
import shutil
import unittest
from pathlib import Path
from test_recording_composer_lifecycle import method
from test_sender_infocard_transitions import run_java

JAVA = Path(__file__).resolve().parents[2] / 'main/java'
PROFILE = (JAVA / 'org/telegram/ui/ProfileActivity.java').read_text()
NAV = (JAVA / 'org/telegram/ui/ActionBar/ActionBarLayout.java').read_text()


@unittest.skipUnless(shutil.which('javac'), 'JDK required')
class ReactionProfileOpenTests(unittest.TestCase):
    def test_first_layout_no_network_wait_and_stale_owner_cancellation(self):
        bodies = '\n'.join(method(PROFILE, signature) for signature in (
            'private void scheduleReactionProfileOpenAfterLayout()',
            'private void cancelReactionProfileOpenAfterLayout()',
        ))
        resume = method(NAV, 'public void resumeDelayedFragmentAnimation()')
        run_java('''import java.util.*;
public class Transitions {
 static void check(boolean b){if(!b)throw new AssertionError();}
 static class AndroidUtilities {static void cancelRunOnUIThread(Runnable r){}}
 static class ViewTreeObserver {
  interface OnPreDrawListener {boolean onPreDraw();}
  List<OnPreDrawListener> listeners=new ArrayList<>();
  boolean isAlive(){return true;}
  void addOnPreDrawListener(OnPreDrawListener l){listeners.add(l);}
  void removeOnPreDrawListener(OnPreDrawListener l){listeners.remove(l);}
  void frame(){for(var l:new ArrayList<>(listeners))check(l.onPreDraw());}
 }
 static class View {
  ViewTreeObserver observer=new ViewTreeObserver();List<Runnable> tasks=new ArrayList<>();
  int width=400,height=800;int getWidth(){return width;}int getHeight(){return height;}
  ViewTreeObserver getViewTreeObserver(){return observer;}
  void post(Runnable r){tasks.add(r);}void removeCallbacks(Runnable r){tasks.remove(r);}
  void drain(){for(var r:new ArrayList<>(tasks)){tasks.remove(r);r.run();}}
 }
 static class ParentLayout {
  Object last;int opens;boolean delayedAnimationResumed;
  Runnable delayedOpenAnimationRunnable=()->opens++,waitingForKeyboardCloseRunnable;
  Object getLastFragment(){return last;}
  RESUME
 }
 static class ProfileActivity {
  boolean openFromReaction=true,profileLifecycleDestroyed,fragmentOpened,transitionAnimationInProress;
  int playProfileAnimation,profileLifecycleGeneration=1;
  View fragmentView=new View(),reactionProfileOpenView;
  ParentLayout parentLayout=new ParentLayout();
  ViewTreeObserver.OnPreDrawListener reactionProfileOpenPreDrawListener;
  Runnable reactionProfileOpenRunnable;
  ProfileActivity(){parentLayout.last=this;}
  void resumeDelayedFragmentAnimation(){parentLayout.resumeDelayedFragmentAnimation();}
  BODIES
 }
 public static void main(String[] args){
  var p=new ProfileActivity();p.scheduleReactionProfileOpenAfterLayout();
  check(p.parentLayout.opens==0);p.fragmentView.observer.frame();
  check(p.parentLayout.opens==0); // never navigation inside measure/draw traversal
  p.fragmentView.drain();check(p.parentLayout.opens==1);
  p.fragmentView.observer.frame();p.fragmentView.drain();check(p.parentLayout.opens==1);
  check(p.reactionProfileOpenView==null&&p.reactionProfileOpenRunnable==null);

  p=new ProfileActivity();p.fragmentView.width=0;p.scheduleReactionProfileOpenAfterLayout();
  p.fragmentView.observer.frame();p.fragmentView.drain();check(p.parentLayout.opens==0);
  p.fragmentView.width=400;p.fragmentView.observer.frame();p.fragmentView.drain();
  check(p.parentLayout.opens==1);
  p=new ProfileActivity();p.openFromReaction=false;p.scheduleReactionProfileOpenAfterLayout();
  check(p.fragmentView.observer.listeners.isEmpty());
  p=new ProfileActivity();p.playProfileAnimation=1;p.scheduleReactionProfileOpenAfterLayout();
  check(p.fragmentView.observer.listeners.isEmpty()); // avatar morph untouched

  p=new ProfileActivity();p.scheduleReactionProfileOpenAfterLayout();p.fragmentView.observer.frame();
  var old=p.reactionProfileOpenRunnable;var oldView=p.fragmentView;
  p.cancelReactionProfileOpenAfterLayout();p.fragmentView=new View();
  p.profileLifecycleGeneration++;p.scheduleReactionProfileOpenAfterLayout();old.run();
  check(p.parentLayout.opens==0&&oldView.tasks.isEmpty());
  p.fragmentView.observer.frame();p.fragmentView.drain();check(p.parentLayout.opens==1);
  p=new ProfileActivity();p.scheduleReactionProfileOpenAfterLayout();p.fragmentView.observer.frame();
  old=p.reactionProfileOpenRunnable;p.profileLifecycleDestroyed=true;
  p.cancelReactionProfileOpenAfterLayout();old.run();check(p.parentLayout.opens==0);

  p=new ProfileActivity();p.scheduleReactionProfileOpenAfterLayout();p.fragmentView.observer.frame();
  p.parentLayout.last=new Object();p.fragmentView.drain();check(p.parentLayout.opens==0);
  p=new ProfileActivity();p.scheduleReactionProfileOpenAfterLayout();p.fragmentView.observer.frame();
  p.transitionAnimationInProress=true;p.fragmentView.drain();check(p.parentLayout.opens==0);

  p=new ProfileActivity();p.parentLayout.waitingForKeyboardCloseRunnable=()->{};
  p.scheduleReactionProfileOpenAfterLayout();p.fragmentView.observer.frame();p.fragmentView.drain();
  check(p.parentLayout.opens==0&&p.parentLayout.delayedAnimationResumed);
  check(p.parentLayout.delayedOpenAnimationRunnable!=null); // keyboard owner not bypassed
 }
}'''.replace('BODIES', bodies).replace('RESUME', resume))

    def test_scoped_entry_and_lifecycle_wiring(self):
        self.assertIn('arguments.getBoolean("from_reaction", false)', PROFILE)
        for signature in ('public void onFragmentDestroy()', 'public View createView(Context context)',
                          'public void onTransitionAnimationStart('):
            self.assertIn('cancelReactionProfileOpenAfterLayout();', method(PROFILE, signature))
        create = method(PROFILE, 'public View createView(Context context)')
        self.assertLess(create.index('scheduleReactionProfileOpenAfterLayout();'),
                        create.index('scheduleSearchAdapterAfterFirstFrame(context);'))
        schedule = method(PROFILE, 'private void scheduleReactionProfileOpenAfterLayout()')
        for forbidden in ('userInfo != null', 'mediaCount', 'loadFullUser', 'Thread.sleep',
                          'scrollToPosition', 'setPadding'):
            self.assertNotIn(forbidden, schedule)

