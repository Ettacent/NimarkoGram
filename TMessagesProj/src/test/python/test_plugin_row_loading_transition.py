"""Exercise PluginCell's actual loading renderer with deterministic JVM animators."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


SOURCE = (Path(__file__).resolve().parents[2] /
          'main/java/app/nimarkogram/messenger/plugins/ui/components/PluginCell.java')


def method(source, signature):
    start = source.index(signature)
    end = source.index('{', start) + 1
    depth = 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]


class PluginRowLoadingTransitionTest(unittest.TestCase):
    def test_binding_error_and_detach_wiring(self):
        source = SOURCE.read_text()
        bind = method(source, 'public void set(\n            Plugin plugin,')
        self.assertIn('controller.isEnablingInProgress(plugin.getId()), samePlugin)', bind)
        self.assertIn('uiOperationEpoch != NO_UI_OPERATION_EPOCH', bind)
        self.assertNotIn('checkBox.setVisibility', method(source, 'private void bindErrorState()'))
        detach = method(source, 'protected void onDetachedFromWindow()')
        self.assertIn('setLoading(loading, false);', detach)
        self.assertIn('checkBox.setChecked(checkBox.isChecked(), false);', detach)
        self.assertIn('isInsideViewRelativeToSelf(trailingSlot, x, y)',
                      method(source, 'public boolean isPointOnInteractive('))

    @unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'JDK required')
    def test_crossfade_reversal_failure_rebind_and_reduced_motion(self):
        source = SOURCE.read_text()
        methods = '\n'.join(method(source, signature) for signature in (
            'public void setLoading(boolean loading)',
            'private boolean canAnimateLoading()',
            'private void setLoading(boolean loading, boolean animated)',
            'private void cancelLoadingAnimator()',
            'private void applyLoadingProgress(float progress)',
            'public boolean isLoading()',
        ))
        harness = r'''
import java.util.function.Consumer;
public class PluginLoadingHarness {
 static class View {
  static final int VISIBLE=0, INVISIBLE=4, GONE=8;
  static final int IMPORTANT_FOR_ACCESSIBILITY_NO=2, IMPORTANT_FOR_ACCESSIBILITY_YES=1;
  float alpha=1; int visibility=VISIBLE, accessibility; boolean enabled=true;
  void setAlpha(float a){alpha=a;} void setVisibility(int v){visibility=v;}
  void setEnabled(boolean e){enabled=e;}
  void setImportantForAccessibility(int a){accessibility=a;}
  void setContentDescription(String s){}
 }
 static class Animator {}
 static class AnimatorListenerAdapter {public void onAnimationEnd(Animator a){}}
 static class ValueAnimator extends Animator {
  static boolean enabled=true; static int starts;
  float from,to,value; Consumer<ValueAnimator> update; AnimatorListenerAdapter listener;
  static ValueAnimator ofFloat(float f,float t){ValueAnimator a=new ValueAnimator();a.from=f;a.to=t;return a;}
  static boolean areAnimatorsEnabled(){return enabled;}
  void setDuration(long d){check(d==180,"duration");} void setInterpolator(Object o){}
  void addUpdateListener(Consumer<ValueAnimator> c){update=c;}
  void addListener(AnimatorListenerAdapter l){listener=l;}
  Object getAnimatedValue(){return value;}
  void start(){starts++;tick(0);}
  void tick(float fraction){value=from+(to-from)*fraction;update.accept(this);}
  void end(){tick(1);listener.onAnimationEnd(this);}
  void cancel(){listener.onAnimationEnd(this);}
 }
 static class Build {
  static class VERSION {static int SDK_INT=35;}
  static class VERSION_CODES {static final int O=26;}
 }
 static class SharedConfig {static boolean enabled=true;static boolean animationsEnabled(){return enabled;}}
 static class AndroidUtilities {static float scale=1;static float getAnimatorDurationScale(){return scale;}}
 static class CubicBezierInterpolator {static final Object EASE_OUT=new Object();}
 static class Plugin {String getName(){return "plugin";}}
 static class LocaleController {static String getString(int r){return "Loading";}}
 static class R {static class string {static final int Loading=1;}}
 View checkBox=new View(),loadingSpinner=new View(); Plugin plugin=new Plugin();
 boolean loading,attached=true,shown=true; int window=View.VISIBLE;
 float loadingProgress; ValueAnimator loadingAnimator;
 boolean isAttachedToWindow(){return attached;} boolean isShown(){return shown;}
 int getWindowVisibility(){return window;}
 METHODS
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 static void near(float a,float b){check(Math.abs(a-b)<.0001f,"alpha "+a+" != "+b);}
 void endpoint(boolean pending){
  near(loadingProgress,pending?1:0);near(checkBox.alpha,pending?0:1);
  near(loadingSpinner.alpha,pending?1:0);
  check(checkBox.visibility==(pending?View.INVISIBLE:View.VISIBLE),"switch visibility");
  check(loadingSpinner.visibility==(pending?View.VISIBLE:View.GONE),"spinner visibility");
  check(loadingAnimator==null,"animator cleared");
 }
 public static void main(String[] args){
  PluginLoadingHarness c=new PluginLoadingHarness();c.setLoading(false,false);c.endpoint(false);
  c.setLoading(true);ValueAnimator first=c.loadingAnimator;
  check(c.isLoading()&&!c.checkBox.enabled,"pending immediately blocks toggle");
  near(c.loadingSpinner.alpha,0);first.tick(.4f);
  near(c.loadingSpinner.alpha,.4f);near(c.checkBox.alpha,.6f);
  check(c.loadingSpinner.visibility==View.VISIBLE&&c.checkBox.visibility==View.VISIBLE,"overlap");
  c.setLoading(true);check(first==c.loadingAnimator,"same-plugin rebind must not restart");
  first.end();c.endpoint(true);
  c.setLoading(false);ValueAnimator success=c.loadingAnimator;
  check(!c.isLoading()&&c.checkBox.enabled,"completion semantic state immediate");
  check(c.loadingSpinner.accessibility==View.IMPORTANT_FOR_ACCESSIBILITY_NO,"outgoing spinner hidden from accessibility");
  success.tick(.5f);near(c.loadingProgress,.5f);success.end();c.endpoint(false);
  // Short operation/error: reverse before loading has finished appearing.
  c.setLoading(true);ValueAnimator enter=c.loadingAnimator;enter.tick(.3f);
  c.setLoading(false);ValueAnimator failure=c.loadingAnimator;near(failure.from,.3f);
  enter.end();near(c.loadingProgress,.3f);check(c.loadingAnimator==failure,"stale completion ignored");
  failure.tick(.5f);near(c.loadingProgress,.15f);
  c.setLoading(true);ValueAnimator retry=c.loadingAnimator;near(retry.from,.15f);
  failure.end();near(c.loadingProgress,.15f);retry.end();c.endpoint(true);
  // A different plugin binds into this attached view: no inherited fade.
  c.setLoading(false);ValueAnimator old=c.loadingAnimator;old.tick(.2f);
  c.setLoading(false,false);c.endpoint(false);old.end();c.endpoint(false);
  c.setLoading(true);old=c.loadingAnimator;old.tick(.4f);
  c.attached=false;c.setLoading(c.loading,false);c.endpoint(true);old.end();c.endpoint(true);
  c.setLoading(false);c.endpoint(false);c.attached=true;
  int starts=ValueAnimator.starts;
  SharedConfig.enabled=false;c.setLoading(true);c.endpoint(true);
  SharedConfig.enabled=true;ValueAnimator.enabled=false;c.setLoading(false);c.endpoint(false);
  ValueAnimator.enabled=true;Build.VERSION.SDK_INT=25;AndroidUtilities.scale=0;
  c.setLoading(true);c.endpoint(true);
  Build.VERSION.SDK_INT=35;c.shown=false;c.setLoading(false);c.endpoint(false);
  check(starts==ValueAnimator.starts,"hidden/reduced motion must not animate");
 }
}
'''.replace('METHODS', methods)
        with tempfile.TemporaryDirectory(prefix='plugin-row-loading-') as directory:
            path = Path(directory) / 'PluginLoadingHarness.java'
            path.write_text(harness)
            compiled = subprocess.run(['javac', '-d', directory, str(path)],
                                      capture_output=True, text=True, timeout=30)
            self.assertEqual(compiled.returncode, 0, compiled.stderr)
            run = subprocess.run(['java', '-ea', '-cp', directory, 'PluginLoadingHarness'],
                                 capture_output=True, text=True, timeout=10)
            self.assertEqual(run.returncode, 0, run.stdout + run.stderr)


if __name__ == '__main__':
    unittest.main()
