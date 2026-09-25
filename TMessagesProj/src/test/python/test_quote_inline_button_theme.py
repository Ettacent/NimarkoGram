"""Quotes must use themed paints even without a per-chat resource provider."""
from pathlib import Path
import unittest
from test_search_fallback_entrance import block
from test_sender_infocard_transitions import run_java

JAVA = Path(__file__).resolve().parents[2] / 'main/java'


class QuoteInlineButtonThemeTest(unittest.TestCase):
    def test_theme_fallback_and_story_isolation(self):
        entity = (JAVA / 'org/telegram/ui/Components/Paint/Views/MessageEntityView.java').read_text()
        theme = (JAVA / 'org/telegram/ui/ActionBar/Theme.java').read_text()
        run_java('''
class Paint {int color; Paint(int c){color=c;}}
class Theme {
 static final String key_paint_chatActionBackgroundSelected="selected",
 key_paint_chatActionBackgroundDarken="darken",key_paint_chatActionText="text",
 key_paint_chatActionText2="text2",key_paint_chatBotButton="bot";
 static Paint current=new Paint(0xffffffff);
 interface ResourcesProvider {default Paint getPaint(String key){return current;}}
 static Paint getThemePaint(String key){return current;}
 FALLBACK
}
public class Transitions implements Theme.ResourcesProvider {
 boolean staticPresentation;
 Theme.ResourcesProvider staticResourcesProvider;
 Paint chat_actionBackgroundSelectedPaint=new Paint(0),chat_actionBackgroundGradientDarkenPaint=new Paint(0),
 chat_actionTextPaint=new Paint(0),chat_actionTextPaint2=new Paint(0),chat_botButtonPaint=new Paint(0xff000000);
 METHOD
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  Transitions q=new Transitions(); q.staticPresentation=true;
  for(int color:new int[]{0xffffffff,0xff253a56,0xffeeeeff}){
   Theme.current=new Paint(color);
   check(q.getPaint("bot")==Theme.current);
   check(q.getPaint("bot").color==color);
   check(q.getPaint("selected")==Theme.current);
  }
  Paint custom=new Paint(0xffeeaaff);
  q.staticResourcesProvider=new Theme.ResourcesProvider(){public Paint getPaint(String k){return custom;}};
  check(q.getPaint("bot")==custom);
  q.staticResourcesProvider=new Theme.ResourcesProvider(){public Paint getPaint(String k){return null;}};
  check(q.getPaint("bot")==Theme.current);
  q.staticPresentation=false;q.staticResourcesProvider=null;
  check(q.getPaint("bot")==q.chat_botButtonPaint);
 }
}
'''.replace('METHOD', block(entity, 'public Paint getPaint(String paintKey)'))
             .replace('FALLBACK', block(theme, 'public static Paint getThemePaint(String key, ResourcesProvider resourcesProvider)')))

    def test_quote_configured_before_attachment(self):
        source = (JAVA / 'app/nimarkogram/messenger/quotes/NimarkoQuoteCreator.java').read_text()
        method = block(source, 'private void addMessageEntity(')
        self.assertLess(method.index('entity.setStaticPresentation'), method.index('addView(entity'))
