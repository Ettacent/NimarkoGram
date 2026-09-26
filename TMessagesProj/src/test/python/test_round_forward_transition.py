"""Round forward-card geometry and group opacity; production snippets on JVM."""
from pathlib import Path
import unittest
from test_recording_composer_lifecycle import method
from test_sender_infocard_transitions import run_java

CELL = (Path(__file__).resolve().parents[2] / 'main/java/org/telegram/ui/Cells/ChatMessageCell.java').read_text()


class RoundForwardTransitionTest(unittest.TestCase):
    def test_geometry_tracks_resize_in_both_directions(self):
        geometry = method(CELL, 'private float getRoundForwardNameX(')
        run_java('''
public class Transitions {
 boolean isRoundVideo=true;
 static class Message {boolean out; boolean isOutOwner(){return out;}}
 static class Params {boolean animateBackgroundBoundsInner=true;float deltaLeft,deltaRight;}
 Message currentMessageObject=new Message(); Params transitionParams=new Params();
 GEOMETRY
 static void eq(float a,float b){if(Math.abs(a-b)>.001)throw new AssertionError(a+" != "+b);}
 public static void main(String[] args){
  Transitions c=new Transitions();
  for(boolean opening:new boolean[]{true,false}){
   float before=opening?244:360,after=opening?360:244;
   for(int frame=0;frame<=100;frame++){
    float p=frame/100f;
    c.transitionParams.deltaRight=(before-after)*(1-p);
    eq(c.getRoundForwardNameX(after),before+(after-before)*p);
   }
  }
  c.transitionParams.deltaRight=-116;c.transitionParams.deltaLeft=0;
  eq(c.getRoundForwardNameX(360),244); // Old target-only path jumped 116dp.
  c.currentMessageObject.out=true;eq(c.getRoundForwardNameX(23),23);
  c.currentMessageObject.out=false;c.isRoundVideo=false;eq(c.getRoundForwardNameX(360),360);
  c.isRoundVideo=true;c.transitionParams.animateBackgroundBoundsInner=false;
  eq(c.getRoundForwardNameX(360),360);
 }
}'''.replace('GEOMETRY', geometry))

    def test_group_fades_text_avatar_and_ripple_once(self):
        fade = method(CELL, 'if (isRoundVideo && animatingAlpha * replyForwardAlpha < 1f)')
        run_java('''
public class Transitions {
 static class Rect {float left=240,top=6,right=320,bottom=54;}
 static class Canvas {
  float opacity=1; int layers;
  int saveLayerAlpha(float l,float t,float r,float b,int a){
   if(!(l<240&&t<6&&r>320&&b>54))throw new AssertionError("clipped card");
   opacity=a/255f;return ++layers;
  }
 }
 static int dp(int n){return n;}
 static void eq(float a,float b){if(Math.abs(a-b)>.005)throw new AssertionError();}
 public static void main(String[] args){
  boolean isRoundVideo=true;Rect rect=new Rect();
  for(int frame=0;frame<=100;frame++){
   Canvas canvas=new Canvas();int forwardCompositeSave=-1;
   float animatingAlpha=.8f,replyForwardAlpha=frame/100f;
   float expected=animatingAlpha*replyForwardAlpha;
   FADE
   eq(canvas.opacity*animatingAlpha*replyForwardAlpha,expected); // Text paint.
   eq(canvas.opacity,expected); // Ripple / independent drawable.
   if(canvas.layers!=1)throw new AssertionError("one bounded layer");
  }
  Canvas canvas=new Canvas();int forwardCompositeSave=-1;
  float animatingAlpha=1,replyForwardAlpha=1;
  FADE
  if(canvas.layers!=0)throw new AssertionError("no steady-state layer");
 }
}'''.replace('FADE', fade))

    def test_callers_restore_opacity_for_reply_and_canvas(self):
        draw = method(CELL, 'public void drawNamesLayout(')
        self.assertIn('getRoundForwardNameX(backgroundDrawableLeft + backgroundDrawableRight + dp(17))', draw)
        self.assertIn('canvas.restoreToCount(forwardCompositeSave)', draw)
        self.assertIn('animatingAlpha = originalAnimatingAlpha;', draw)
        self.assertIn('replyForwardAlpha = originalReplyForwardAlpha;', draw)
        self.assertLess(draw.index('replyForwardAlpha = originalReplyForwardAlpha;'), draw.index('float replyStartX = this.replyStartX;'))


if __name__ == '__main__':
    unittest.main()
