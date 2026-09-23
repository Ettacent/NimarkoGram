"""Execute actual Dialogs host + strip methods together; no Gradle/APK/device rendering.

Transport/card stubs come from the deferred-selection suite. Presentation, factor
callbacks, shared selection and lifecycle decisions are all extracted from Java.
"""
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from test_infocard_search_deferred_sync import JAVA, STRIP, DIALOGS, method


def java_source():
    source = JAVA.replace('static final int VISIBLE=0; View parent;',
                          'static final int VISIBLE=0, INVISIBLE=4, GONE=8; View parent;')
    source = source.replace('void setScaleX(float f){} void setScaleY(float f){}',
                            'void setScaleX(float f){sx=f;} void setScaleY(float f){sy=f;}')
    source = source.replace('int getWidth(){return 150;} int getHeight(){return 28;}', '''
  int width=150,height=28,left,top,measuredWidth=-1,measuredHeight=-1;float sx=1,sy=1,px=75,py=14,tx,ty;
  int getWidth(){return width;}int getHeight(){return height;}
  int getMeasuredWidth(){return measuredWidth<0?width:measuredWidth;}
  int getMeasuredHeight(){return measuredHeight<0?height:measuredHeight;}
  LayoutParams getLayoutParams(){return new LayoutParams();}
  void measure(int w,int h){measuredWidth=Math.min(80,MeasureSpec.getSize(w));measuredHeight=MeasureSpec.getSize(h);}
  void layout(int l,int t,int r,int b){left=l;top=t;width=r-l;height=b-t;}
  void setResizeReferenceWidth(int w){}void setTrailingOverlayInset(int w,boolean rtl){}
  int getLeft(){return left;}int getTop(){return top;}float getX(){return left+tx;}float getY(){return top+ty;}
  float getScaleX(){return sx;}float getScaleY(){return sy;}float getPivotX(){return px;}float getPivotY(){return py;}
  float getTranslationX(){return tx;}void setTranslationX(float f){tx=f;}void setTranslationY(float f){ty=f;}
  void setPivotX(float f){px=f;}void setPivotY(float f){py=f;}
''')
    source = source.replace('static class InfoCardsConfig {', '''static class InfoCardsConfig {
  static boolean enabled=true;static boolean isEnabled(){return enabled;}
''')
    source = source.replace('static class AndroidUtilities {', 'static class AndroidUtilities {static int statusBarHeight=24;')
    source = source.replace('static class Strip extends View {', '''static class Strip extends View {
  int getChildCount(){return pills.size();}
  void setInlineFolderStyle(boolean inline){inlineFolderStyle=inline;}
''' + method(STRIP, 'public float getVisibilityFactor()'))
    source = source.replace('static class Canvas {', '''static class Canvas {
  float x,y,sx=1,sy=1;int clips;Stack<float[]> stack=new Stack<>();
  void translate(float dx,float dy){x+=sx*dx;y+=sy*dy;}
  void scale(float a,float b,float px,float py){translate(px,py);sx*=a;sy*=b;translate(-px,-py);}
''').replace('void save(){} void restore(){} void clipRect(int l,int t,int r,int b){}', '''
  void save(){stack.push(new float[]{x,y,sx,sy,clips});}
  void restore(){float[] s=stack.pop();x=s[0];y=s[1];sx=s[2];sy=s[3];clips=(int)s[4];}
  void clipRect(float l,float t,float r,float b){clips++;}
''')
    host_methods = '\n'.join(method(DIALOGS, signature) for signature in (
        'private boolean useInlineHomeInfoCards()', 'private void positionHomeInfoCards()',
        'private void applyHomeInfoCardsVisibility(',
        'private float getFilterTabsVisibilityFactor(', 'private void checkUi_filterTabsVisible()',
        'private void checkUi_searchFieldVisibility()', 'public void onFactorChanged(',
    )).replace('app.nimarkogram.messenger.infocards.InfoCardsConfig', 'InfoCardsConfig')
    constants = '\n'.join(re.findall(r'private static final int ANIMATOR_ID_\w+ = \d+;', DIALOGS))
    fixture = (Path(__file__).resolve().parents[1] / 'fixtures/InfoCardHostHarness.java.txt').read_text()
    fixture = fixture.replace('HOST_METHODS', host_methods).replace('HOST_CONSTANTS', constants)
    fixture = fixture.replace('DRAW_CHILD', method(DIALOGS, 'protected boolean drawChild('))
    # Execute the actual inline measure block and layout exclusion predicate. The
    # transport stub deliberately keeps measured dimensions separate from bounds.
    measure = method(DIALOGS, 'protected void onMeasure(final int widthMeasureSpec,')
    measure = measure[measure.index('homeInfoCardSlotWidth ='):measure.index('for (int i = 0; i < childCount; i++)')]
    layout = method(DIALOGS, 'protected void onLayout(boolean changed, int l, int t, int r, int b)')
    guard = re.search(r'if \(child == null \|\| child.getVisibility\(\) == GONE\) \{\s*continue;\s*\}', layout)[0]
    fixture = fixture.replace('INLINE_MEASURE', measure).replace('LAYOUT_GUARD', guard)
    source = source.replace(' public static void main(String[] args){', fixture + '\n public static void main(String[] args){')
    source = source.replace('switch(args[0]){', '''switch(args[0]){
   case "inlineHost":inlineHost();break;case "standaloneHost":standaloneHost();break;
   case "hostLifecycle":hostLifecycle();break;case "hostSelection":hostSelection();break;
   case "hostCanvas":hostCanvas();break;case "hostGates":hostGates();break;
   case "firstLayout":firstLayout();break;
''')
    return source


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'JDK required')
class InfoCardHostPresentationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix='infocard-host-')
        cls.addClassCleanup(cls.temp.cleanup)
        cls.source = java_source()
        cls.compile(cls.temp.name, cls.source)

    @staticmethod
    def compile(folder, source):
        path = Path(folder) / 'SearchDeferredSync.java'
        path.write_text(source)
        result = subprocess.run(['javac', str(path)], capture_output=True, text=True)
        if result.returncode:
            raise AssertionError(result.stderr)

    def run_case(self, case, folder=None):
        return subprocess.run(['java', '-cp', folder or self.temp.name, 'SearchDeferredSync', case],
                              capture_output=True, text=True)

    def test_production_host_sequences(self):
        for case in ('inlineHost', 'standaloneHost', 'hostLifecycle', 'hostSelection', 'hostCanvas', 'hostGates', 'firstLayout'):
            with self.subTest(case=case):
                result = self.run_case(case)
                self.assertEqual(result.returncode, 0, result.stderr)
                print(result.stdout.strip())

    def test_negative_controls(self):
        for case, before, after in (
            ('inlineHost', '? filterTabsView.getAlpha() : 0f;', '? homeInfoCards.getVisibilityFactor() * filterTabsView.getAlpha() : 0f;'),
            ('inlineHost', 'homeInfoCards.setPivotX(filterTabsView.getLeft() + filterTabsView.getPivotX() - homeInfoCards.getLeft());',
             'homeInfoCards.setPivotX(homeInfoCards.getWidth() / 2f);'),
            ('standaloneHost', '* (1f - getRightSlidingProgress()) * (1f - actionModeVisible)', '* (1f - actionModeVisible)'),
            ('hostLifecycle', 'float previousFactor = visibilityFactor;',
             'if (visibilityFactor == f) return; float previousFactor = visibilityFactor;'),
            ('hostCanvas', '|| child == homeInfoCards) {', '|| (child == homeInfoCards && homeInfoCardSlotWidth > 0)) {'),
            ('hostGates', '? View.VISIBLE : View.INVISIBLE;', '? View.VISIBLE : View.GONE;'),
            ('hostGates', 'if (changed) blur3_InvalidateBlur();', ''),
            ('firstLayout', 'applyHomeInfoCardsVisibility(0f, filterTabsView.getScaleX(), filterTabsView.getScaleY(), View.INVISIBLE);', ''),
            ('firstLayout', '&& homeInfoCards.getWidth() > 0 && homeInfoCards.getHeight() > 0;', ';'),
        ):
            with self.subTest(case=case), tempfile.TemporaryDirectory(prefix='infocard-host-negative-') as folder:
                self.assertIn(before, self.source)
                self.compile(folder, self.source.replace(before, after))
                result = self.run_case(case, folder)
                self.assertNotEqual(result.returncode, 0, 'broken ownership must fail behavior checks')
                self.assertIn('AssertionError', result.stderr)

    def test_single_owner_and_lifecycle_wiring(self):
        self.assertNotIn('homeInfoCardsAnimator', DIALOGS)
        self.assertNotIn('homeInfoCardsShown', DIALOGS)
        for write in ('homeInfoCards.setAlpha(', 'homeInfoCards.setScaleX(', 'homeInfoCards.setScaleY(',
                      'homeInfoCards.setVisibility(', 'homeInfoCards.setVisibilityFactor('):
            self.assertNotIn(write, DIALOGS)
        self.assertIn('checkUi_searchFieldVisibility();', method(DIALOGS, 'public void onResume()'))
        self.assertIn('homeInfoCards = null;', method(DIALOGS, 'public View createView('))
        self.assertIn('positionHomeInfoCards();', method(DIALOGS, 'private void updateContextViewPosition()'))
        layout = method(DIALOGS, 'protected void onLayout(boolean changed, int l, int t, int r, int b)')
        self.assertLess(layout.index('child.layout('), layout.index('updateContextViewPosition();'))
        self.assertNotIn('requestLayout()', method(DIALOGS, 'private void positionHomeInfoCards()'))


if __name__ == '__main__':
    unittest.main()
