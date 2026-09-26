"""Exercise the production dim-owner retirement without Android rendering."""
import shutil
import unittest
from test_round_backend_lifecycle import JAVA, method, run_java

SOURCE = (JAVA / 'org/telegram/ui/Components/ItemOptions.java').read_text()
DIM = SOURCE[SOURCE.index('public class DimView extends View'):]


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'JDK required')
class AccountMenuDimOwnerTests(unittest.TestCase):
    def test_owner_retirement_detach_and_replacement(self):
        methods = '\n'.join(method(DIM, signature) for signature in (
            'private void stopObservingOwner()', 'public boolean onPreDraw()',
            'protected void onDetachedFromWindow()',
        ))
        run_java('''import java.util.*;
public class RoundHarness {
 static class ViewTreeObserver {
  interface OnPreDrawListener {boolean onPreDraw();}
  Set<OnPreDrawListener> listeners=new HashSet<>();boolean alive=true;
  boolean isAlive(){return alive;}void removeOnPreDrawListener(OnPreDrawListener l){listeners.remove(l);}
 }
 static class View {
  float alpha=1;boolean attached=true;int invalidates,detaches;
  void setAlpha(float a){alpha=a;}void invalidate(){invalidates++;}
  protected void onDetachedFromWindow(){detaches++;}
 }
 static class AndroidUtilities {
  static void removeFromParent(View v){if(v.attached){v.attached=false;v.onDetachedFromWindow();}}
 }
 static class Fragment {boolean isFinished;}
 static class Window {int closes;void dismiss(boolean animated){if(animated)throw new AssertionError();closes++;}}
 Fragment fragment=new Fragment();DimView dimView;Object accountSwitchPopup;
 Window actionBarPopupWindow=new Window();ViewTreeObserver preDrawObserver;
 ViewTreeObserver.OnPreDrawListener preDrawListener;
 class DimView extends View implements ViewTreeObserver.OnPreDrawListener {
  ViewTreeObserver ownerObserver;boolean blurCaptureActive=true;int restores;
  void restoreBlurAnchor(){restores++;}
  METHODS
 }
 DimView install(ViewTreeObserver observer){
  DimView d=new DimView();dimView=d;preDrawListener=d;preDrawObserver=observer;
  d.ownerObserver=observer;observer.listeners.add(d);return d;
 }
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  RoundHarness h=new RoundHarness();ViewTreeObserver o=new ViewTreeObserver();DimView d=h.install(o);
  check(d.onPreDraw()&&d.invalidates==1&&d.alpha==1&&d.blurCaptureActive);
  h.fragment.isFinished=true;check(d.onPreDraw());
  check(!d.attached&&d.alpha==0&&!d.blurCaptureActive&&h.dimView==null);
  check(o.listeners.isEmpty()&&h.preDrawListener==null&&h.preDrawObserver==null);
  check(h.actionBarPopupWindow.closes==1&&d.detaches==1);
  d.onPreDraw();check(h.actionBarPopupWindow.closes==1); // stale callback is harmless
  // Shared-clock popup survives owner retirement until the transition finishes.
  h=new RoundHarness();d=h.install(new ViewTreeObserver());h.accountSwitchPopup=new Object();
  h.fragment.isFinished=true;d.onPreDraw();check(!d.attached&&h.actionBarPopupWindow.closes==0);
  // Old fading dim must not remove a replacement's observer or dismiss its window.
  h=new RoundHarness();o=new ViewTreeObserver();DimView old=h.install(o);DimView fresh=h.install(o);
  h.fragment.isFinished=true;old.onPreDraw();
  check(h.dimView==fresh&&h.preDrawListener==fresh&&h.preDrawObserver==o);
  check(o.listeners.size()==1&&o.listeners.contains(fresh)&&h.actionBarPopupWindow.closes==0);
  // Ordinary detach retires callbacks, including a late bitmap publication.
  h=new RoundHarness();o=new ViewTreeObserver();d=h.install(o);AndroidUtilities.removeFromParent(d);
  check(!d.blurCaptureActive&&o.listeners.isEmpty()&&d.ownerObserver==null);
  // Container-only menus have no fragment owner and remain valid.
  h=new RoundHarness();h.fragment=null;d=h.install(new ViewTreeObserver());d.onPreDraw();
  check(d.attached&&d.alpha==1&&h.actionBarPopupWindow.closes==0);
 }
}'''.replace('METHODS', methods))

    def test_registration_and_late_capture_guard(self):
        show = method(SOURCE, 'public ItemOptions show()')
        self.assertIn('preDrawListener = dimViewLocal;', show)
        self.assertIn('dimViewLocal.ownerObserver = preDrawObserver;', show)
        self.assertLess(show.index('dimViewLocal.ownerObserver ='), show.index('container.addView(dimView,'))
        self.assertIn('if (!blurCaptureActive)', DIM)
        self.assertIn('bitmapBg.recycle()', DIM)


if __name__ == '__main__':
    unittest.main()
