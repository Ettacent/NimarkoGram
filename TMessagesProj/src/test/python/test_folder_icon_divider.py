"""Control separator uses provider colors; ordinary theme dividers stay unchanged."""
from pathlib import Path
import unittest
from test_gif_loop_transition import method
from test_sender_infocard_transitions import run_java


class FolderIconDividerTests(unittest.TestCase):
    def test_theme_refresh(self):
        java = Path(__file__).resolve().parents[2] / 'main/java'
        source = (java / 'org/telegram/ui/Cells/EditEmojiTextCell.java').read_text()
        run_java(r'''
public class Transitions {
 static class View {int color;void setColorFilter(int c){color=c;}void setBackgroundColor(int c){color=c;}}
 static class Theme {
  static final int key_windowBackgroundWhiteBlueIcon=0,key_windowBackgroundWhite=1,
   key_windowBackgroundWhiteBlackText=2,key_divider=3;
  static class ResourcesProvider {int[] colors={123,255,0,7};}
  static int getColor(int key,ResourcesProvider p){return p.colors[key];}
 }
 static class MonetHelper {static boolean active;static boolean isActiveMonetTheme(){return active;}}
 static class ColorUtils {static int blendARGB(int a,int b,float p){return Math.round(a*(1-p)+b*p);}}
 View iconImage=new View(),iconDivider=new View();
 final Theme.ResourcesProvider iconResourcesProvider=new Theme.ResourcesProvider();
 BODY
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  Transitions t=new Transitions();t.updateIconColors();check(t.iconDivider.color==7);
  MonetHelper.active=true;t.updateIconColors();check(t.iconDivider.color==184);
  t.iconResourcesProvider.colors[1]=0;t.iconResourcesProvider.colors[2]=255;
  t.updateIconColors();check(t.iconDivider.color==71);check(t.iconImage.color==123);
  MonetHelper.active=false;t.updateIconColors();check(t.iconDivider.color==7);
  t.iconImage=null;t.iconDivider=null;t.updateIconColors();
 }
}
'''.replace('BODY', method(source, 'public void updateIconColors()')))
        self.assertIn('updateIconColors();', method(source, 'public void setIcon('))
        self.assertIn('updateIconColors();', method(source, 'protected void onAttachedToWindow()'))
        screen = (java / 'org/telegram/ui/FilterCreateActivity.java').read_text()
        self.assertIn('nameEditTextCell.updateIconColors();', method(screen, 'public ArrayList<ThemeDescription> getThemeDescriptions()'))
