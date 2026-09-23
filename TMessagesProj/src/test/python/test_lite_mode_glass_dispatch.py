"""Material invalidation follows effective settings in both directions."""
import re
import shutil
import unittest

from test_recording_composer_lifecycle import SRC, method
from test_sender_infocard_transitions import run_java


class LiteModeGlassDispatchTests(unittest.TestCase):
    def test_cards_subscribe_only_while_attached_without_reloading_data(self):
        source = (SRC / 'java/app/nimarkogram/messenger/infocards/BaseInfoCard.java').read_text()
        attach = method(source, 'protected void onAttachedToWindow()')
        detach = method(source, 'protected void onDetachedFromWindow()')
        self.assertIn('LiteMode.addOnGlassSettingsChangedListener(glassSettingsChanged)', attach)
        self.assertIn('LiteMode.removeOnGlassSettingsChangedListener(glassSettingsChanged)', detach)
        self.assertIn('Runnable glassSettingsChanged = this::updateGlassBackground', source)
        refresh = method(source, 'private void updateGlassBackground()')
        self.assertNotIn('onUpdateData(', refresh)
        self.assertNotIn('requestLayout(', refresh)

    @unittest.skipUnless(shutil.which('javac'), 'JDK required')
    def test_toggle_publication_and_power_saver_transitions(self):
        source = (SRC / 'java/org/telegram/messenger/LiteMode.java').read_text()
        bodies = '\n'.join(method(source, name) for name in (
            'public static void setAllFlags(', 'private static void onGlassFlagsUpdate(',
            'private static void onFlagsUpdate(',
            'public static void addOnGlassSettingsChangedListener(',
            'public static void removeOnGlassSettingsChangedListener(',
        )).replace('org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory', 'Factory')
        flags = '\n'.join(re.findall(
            r'public static final int (?:FLAG_LIQUID_GLASS|FLAG_CHAT_BLUR|FLAG_CHAT_BACKGROUND) = [^;]+;', source))
        run_java(r'''
import java.util.*;
public class Transitions {
 PRODUCTION_CONSTANTS
 static final int FLAGS_ANIMATED_EMOJI=1;
 static int value,saved,invalidations;static boolean saver;
 static final HashSet<Runnable> glassSettingsListeners=new HashSet<>();
 static int getValue(){return saver?0:value;}
 static void savePreference(){saved=value;}
 static class AnimatedEmojiDrawable{static void updateAll(){}}
 static class SvgHelper{static class SvgDrawable{static void updateLiteValues(){}}}
 static class Theme{static void reloadWallpaper(boolean b){}}
 static class Factory {
  static int observed;
  static void invalidateGlassSettings(){invalidations++;observed=getValue();check(saved==value,"settings published before callback");}
 }
 static class AndroidUtilities{
  static ArrayList<Runnable> callbacks=new ArrayList<>();
  static void runOnUIThread(Runnable r){callbacks.add(r);}
  static void drain(){for(Runnable r:new ArrayList<>(callbacks))r.run();callbacks.clear();}
 }
 BODIES
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 public static void main(String[] args){
  setAllFlags(FLAG_LIQUID_GLASS);AndroidUtilities.drain();
  check(invalidations==1&&Factory.observed==FLAG_LIQUID_GLASS,"enable live");
  setAllFlags(0);AndroidUtilities.drain();check(invalidations==2&&Factory.observed==0,"disable live");
  setAllFlags(0);setAllFlags(1);AndroidUtilities.drain();check(invalidations==2,"unrelated/no-op avoids redraw");
  setAllFlags(FLAG_CHAT_BLUR);AndroidUtilities.drain();check(invalidations==3,"blur gate updates");
  setAllFlags(0);AndroidUtilities.drain();check(invalidations==4,"blur disabled updates");
  saver=true;setAllFlags(FLAG_LIQUID_GLASS);AndroidUtilities.drain();
  check(invalidations==4&&saved==FLAG_LIQUID_GLASS,"do not bypass active power saving");
  saver=false;onFlagsUpdate(0,value);AndroidUtilities.drain();
  check(invalidations==5&&Factory.observed==FLAG_LIQUID_GLASS,"leaving saver refreshes retained factories");
  saver=true;onFlagsUpdate(value,0);AndroidUtilities.drain();
  check(invalidations==6&&Factory.observed==0,"entering saver clears shader");
  saver=false;setAllFlags(0);setAllFlags(FLAG_LIQUID_GLASS);setAllFlags(0);AndroidUtilities.drain();
  check(Factory.observed==0,"queued refresh reads latest choice, never stale toggle");
  int[] updates={0};Runnable listener=()->updates[0]++;
  addOnGlassSettingsChangedListener(listener);addOnGlassSettingsChangedListener(listener);
  setAllFlags(FLAG_LIQUID_GLASS);AndroidUtilities.drain();
  check(updates[0]==1,"attached card with no drawable is notified once");
  setAllFlags(0);removeOnGlassSettingsChangedListener(listener);AndroidUtilities.drain();
  check(updates[0]==1,"detached card is not retained by queued update");
 }
}
'''.replace('PRODUCTION_CONSTANTS', flags).replace('BODIES', bodies))


if __name__ == '__main__':
    unittest.main()
