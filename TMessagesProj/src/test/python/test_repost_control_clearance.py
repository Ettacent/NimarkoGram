"""Exercise the actual clearance methods without building an APK."""
from pathlib import Path
import unittest
from test_search_fallback_entrance import block
from test_sender_infocard_transitions import run_java

ROOT = Path(__file__).resolve().parents[2] / 'main/java/org/telegram/ui'


class RepostClearanceTest(unittest.TestCase):
    def test_temporary_diagnostics_removed(self):
        self.assertFalse((ROOT.parents[2] / 'app/nimarkogram/messenger/SearchRepostTrace.java').exists())
        for path in ('Cells/ChatMessageCell.java', 'ChatActivity.java'):
            source = (ROOT / path).read_text()
            for marker in ('SearchRepostTrace', 'traceSearchRepost', 'searchRepostWatch'):
                self.assertNotIn(marker, source)

    def test_geometry_and_continuity(self):
        rail = (ROOT / 'Components/chat/layouts/ChatActivitySideControlsButtonsLayout.java').read_text()
        chat = (ROOT / 'ChatActivity.java').read_text()
        run_java('''
class V {
 static final int VISIBLE=0;
 float x,y,alpha=1,sx=1,sy=1; int w=168,h=192,vis=0; Object parent;
 float getX(){return x;} float getY(){return y;} float getAlpha(){return alpha;}
 float getScaleX(){return sx;} float getScaleY(){return sy;}
 float getPivotX(){return w/2f;} float getPivotY(){return h/2f;}
 int getWidth(){return w;} int getVisibility(){return vis;} Object getParent(){return parent;}
}
class ChatActivityBlurredRoundPageDownButton extends V {}
class ChatMessageCell extends V {float getPaddingTopAnimated(){return 0;}}
class Rail extends V {
 static class ButtonHolder {ChatActivityBlurredRoundPageDownButton button;}
 ButtonHolder[] buttonHolders=new ButtonHolder[7];
 RAIL
}
public class Transitions {
 V chatListView=new V(); Rail sideControlsButtonsLayout=new Rail();
 static int dp(int n){return n*3;}
 METHOD
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  Transitions t=new Transitions(); Object root=new Object();
  t.chatListView.parent=root;t.chatListView.y=-96;
  t.sideControlsButtonsLayout.parent=root;t.sideControlsButtonsLayout.x=909;
  ChatMessageCell cell=new ChatMessageCell();cell.parent=t.chatListView;cell.y=708;
  Rail.ButtonHolder h=new Rail.ButtonHolder(); h.button=new ChatActivityBlurredRoundPageDownButton();
  h.button.y=1943;t.sideControlsButtonsLayout.buttonHolders[1]=h;
  float y=t.getUnobscuredShareButtonY(cell,933,1370,96);
  check(y+cell.y+t.chatListView.y+96+dp(6)==1943);
  check(y>=dp(4) && y<=1370);
  // No discontinuity crossing the rail boundary in either scroll direction.
  float previous=Float.NaN;
  for(float cy=100;cy<1800;cy+=.25f){
   cell.y=cy;float current=t.getUnobscuredShareButtonY(cell,933,1370,96)+cy-96;
   if(!Float.isNaN(previous)) check(Math.abs(current-previous)<=.251f);
   previous=current;
  }
  cell.y=708;
  check(t.getUnobscuredShareButtonY(cell,0,1370,96)==1370); // other side
  h.button.alpha=0;check(t.getUnobscuredShareButtonY(cell,933,1370,96)==1370);
  h.button.alpha=1;h.button.vis=8;check(t.getUnobscuredShareButtonY(cell,933,1370,96)==1370);
  h.button.vis=0;t.sideControlsButtonsLayout.alpha=0;
  check(t.getUnobscuredShareButtonY(cell,933,1370,96)==1370);
  t.sideControlsButtonsLayout.alpha=1;cell.parent=new Object();
  check(t.getUnobscuredShareButtonY(cell,933,1370,96)==1370); // another list
 }
}
'''.replace('RAIL', block(rail, 'public float getObstructionTop('))
             .replace('METHOD', block(chat, 'public float getUnobscuredShareButtonY(')))

    def test_draw_hit_target_and_animation_owner(self):
        cell = (ROOT / 'Cells/ChatMessageCell.java').read_text()
        draw = block(cell, 'public void drawSideButton(Canvas canvas, boolean fromQuickShare)')
        self.assertIn('!fromQuickShare && drawSideButton == 1 && !currentMessageObject.isOutOwner()', draw)
        self.assertIn('sideStartY = delegate.getUnobscuredShareButtonY', draw)
        self.assertLess(draw.index('getUnobscuredShareButtonY'), draw.index('sideButtonVisible = true'))
        self.assertNotIn('quoteHighlight', draw[:draw.index('sideButtonVisible = true')])
        rail = (ROOT / 'Components/chat/layouts/ChatActivitySideControlsButtonsLayout.java').read_text()
        self.assertIn('onPositionsChanged.run()', block(rail, 'private void checkButtonsPositionsAndVisibility()'))
