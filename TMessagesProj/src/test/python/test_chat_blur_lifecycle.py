"""Exercise early and late glass callbacks using the production scheduling method."""
from pathlib import Path
import unittest
from test_search_fallback_entrance import block
from test_sender_infocard_transitions import run_java

SOURCE = Path(__file__).resolve().parents[2] / 'main/java/org/telegram/ui/ChatActivity.java'


class ChatBlurLifecycleTest(unittest.TestCase):
    def test_callbacks_before_view_creation_and_after_destroy(self):
        source = SOURCE.read_text()
        method = block(source, 'private void invalidateMergedVisibleBlurredPositionsAndSources(int flags)')
        create = source[source.index('contentView.addView(invalidateBlurredSourcesView);'):]
        flush = block(create, 'if (pendingBlurInvalidationFlags != 0)')
        destroy = block(source, 'public void onFragmentDestroy()')
        self.assertEqual(destroy.count('setOnDrawablesRelativePositionChangeListener(null)'), 2)
        run_java('''
public class Transitions {
 static class Build {static class VERSION {static int SDK_INT=31;} static class VERSION_CODES {static final int S=31;}}
 static class View {int flags,calls;void invalidate(int f){flags|=f;calls++;}}
 boolean isFinished;Object scrollableViewNoiseSuppressor=new Object();
 Transitions parentChatActivity;View invalidateBlurredSourcesView;int pendingBlurInvalidationFlags;
 METHOD
 void create(){invalidateBlurredSourcesView=new View(); FLUSH}
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  Transitions t=new Transitions();
  t.invalidateMergedVisibleBlurredPositionsAndSources(2);
  t.invalidateMergedVisibleBlurredPositionsAndSources(1);
  check(t.pendingBlurInvalidationFlags==3);t.create();
  check(t.pendingBlurInvalidationFlags==0 && t.invalidateBlurredSourcesView.flags==3);
  t.invalidateMergedVisibleBlurredPositionsAndSources(4);
  check(t.invalidateBlurredSourcesView.flags==7 && t.invalidateBlurredSourcesView.calls==2);
  t.isFinished=true;t.invalidateMergedVisibleBlurredPositionsAndSources(2);
  check(t.invalidateBlurredSourcesView.calls==2);
  t=new Transitions();t.parentChatActivity=new Transitions();t.parentChatActivity.create();
  t.invalidateMergedVisibleBlurredPositionsAndSources(2);
  check(t.pendingBlurInvalidationFlags==2 && t.parentChatActivity.invalidateBlurredSourcesView.flags==2);
  t=new Transitions();t.scrollableViewNoiseSuppressor=null;
  t.invalidateMergedVisibleBlurredPositionsAndSources(2);check(t.pendingBlurInvalidationFlags==0);
 }
}
'''.replace('METHOD', method).replace('FLUSH', flush))


if __name__ == '__main__':
    unittest.main()
