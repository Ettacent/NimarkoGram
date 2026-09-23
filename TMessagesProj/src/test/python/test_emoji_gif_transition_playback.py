"""Run EmojiView's GIF page-transition playback gate against small JVM stubs."""
import shutil
import unittest

from test_recording_composer_lifecycle import SRC, method
from test_sender_infocard_transitions import run_java

EMOJI = SRC / "java/org/telegram/ui/Components/EmojiView.java"


def playback_harness():
    production = method(EMOJI.read_text(), "private void startStopVisibleGifs(")
    return r'''import java.util.*;
public class Transitions {
 static final int VISIBLE=0, INVISIBLE=4;
 static class ImageReceiver {
  boolean allow, running;int starts, stops;
  ImageReceiver(boolean allow,boolean running){this.allow=allow;this.running=running;}
  boolean getAllowStartAnimation(){return allow;}
  boolean isAnimationRunning(){return running;}
  void setAllowStartAnimation(boolean a){allow=a;}
  void startAnimation(){starts++;running=true;}
  void stopAnimation(){stops++;running=false;}
 }
 static class View {}
 static class ContextLinkCell extends View {
  ImageReceiver image;ContextLinkCell(ImageReceiver image){this.image=image;}
  ImageReceiver getPhotoImage(){return image;}
 }
 static class Grid {
  ArrayList<View> children=new ArrayList<>();
  int getChildCount(){return children.size();}
  View getChildAt(int index){return children.get(index);}
 }
 Grid gifGridView=new Grid();boolean attached=true,shown=true;int windowVisibility=VISIBLE;
 boolean isAttachedToWindow(){return attached;}
 boolean isShown(){return shown;}
 int getWindowVisibility(){return windowVisibility;}
 /* PRODUCTION */
 static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);}
 public static void main(String[] args){
  Transitions t=new Transitions();
  ImageReceiver entering=new ImageReceiver(false,false);
  ImageReceiver alreadyPlaying=new ImageReceiver(true,true);
  t.gifGridView.children.add(new ContextLinkCell(entering));
  t.gifGridView.children.add(new View());
  t.gifGridView.children.add(new ContextLinkCell(alreadyPlaying));
  for(int i=0;i<120;i++)t.startStopVisibleGifs(true);
  check(entering.starts==1 && alreadyPlaying.starts==0,"scroll frames must not restart GIF decoders");
  for(int i=0;i<120;i++)t.startStopVisibleGifs(false);
  check(entering.stops==1 && alreadyPlaying.stops==1,"scroll frames must not restop GIF decoders");
  // A rebinding ContextLinkCell can arm itself while its page is hidden.
  ImageReceiver rebound=new ImageReceiver(true,true);
  t.gifGridView.children.add(new ContextLinkCell(rebound));
  t.startStopVisibleGifs(false);
  check(rebound.stops==1 && !rebound.allow,"hidden rebound is stopped");
  t.shown=false;t.startStopVisibleGifs(true);
  check(entering.starts==1 && rebound.starts==0,"hidden host cannot restart playback");
  t.gifGridView=null;t.startStopVisibleGifs(true);
 }
}'''.replace('/* PRODUCTION */', production)


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'JDK required')
class EmojiGifTransitionPlaybackTests(unittest.TestCase):
    def test_repeated_pager_scroll_only_changes_decoder_on_state_change(self):
        run_java(playback_harness())

    def test_negative_controls(self):
        java = playback_harness()
        for before, after, failure in (
            ('if (!imageReceiver.getAllowStartAnimation())', 'if (true)', 'must not restart'),
            ('} else if (imageReceiver.getAllowStartAnimation() || imageReceiver.isAnimationRunning()) {',
             '} else {', 'must not restop'),
        ):
            self.assertIn(before, java)
            with self.assertRaisesRegex(AssertionError, failure):
                run_java(java.replace(before, after))


if __name__ == '__main__':
    unittest.main()
