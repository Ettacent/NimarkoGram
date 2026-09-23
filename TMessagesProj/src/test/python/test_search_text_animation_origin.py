"""Animation uses the centred text origin, retaining Caption's own draw scope."""
from pathlib import Path
import unittest
from test_search_fallback_entrance import block
from test_sender_infocard_transitions import run_java

SOURCE = (Path(__file__).resolve().parents[2] /
          'main/java/app/nimarkogram/messenger/textanim/NimarkoTextAnim.java').read_text()


class SearchTextOriginTests(unittest.TestCase):
    def test_search_gravity_and_scroll_do_not_move_overlay_off_native_line(self):
        production = '\n'.join(block(SOURCE, marker) for marker in (
            'private static int animationTextTop(', 'private static int animationTextLeft('))
        production = production.replace('org.telegram.ui.Components.EditTextCaption', 'Caption')
        run_java('''
public class Transitions {
 static class EditText {
  int top=0,total=18,left=4,compound=12,scroll=23;
  int getPaddingTop(){return top;}int getTotalPaddingTop(){return total;}
  int getPaddingLeft(){return left;}int getCompoundPaddingLeft(){return compound;}
  int getScrollX(){return scroll;}
 }
 static class Caption extends EditText {}
 PRODUCTION
 public static void main(String[] args){
  EditText search=new EditText();Caption composer=new Caption();
  for(int height=0;height<100;height++){
   search.total=height;
   if(animationTextTop(search)!=height || animationTextLeft(search)!=12)throw new AssertionError();
   if(animationTextTop(composer)!=0 || animationTextLeft(composer)!=-19)throw new AssertionError();
  }
 }
}
'''.replace('PRODUCTION', production))
        for marker in ('private static void drawAnimatedChars(',
                       'private static void spawnDeleteParticles(',
                       'private static void drawCursor('):
            self.assertIn('animationTextTop(edit)', block(SOURCE, marker))


if __name__ == '__main__':
    unittest.main()
