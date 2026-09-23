"""Current production edit bookkeeping/easing on JVM stubs, not GPU or shaping QA.

The separate text-animation-offset script checks the production editor's
native/overlay canvas handoff. No tests of the reverted ShapedRun renderer.
"""
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

from test_gif_loop_transition import method

SOURCE = (Path(__file__).resolve().parents[2] /
          'main/java/app/nimarkogram/messenger/textanim/NimarkoTextAnim.java').read_text()


def harness():
    constants = '\n'.join(re.search(r'private static final int ' + name + r' = \d+;', SOURCE)[0]
                          for name in ('MASS_DELETE_THRESHOLD', 'MAX_DELETE_GLYPHS',
                                       'MASS_INSERT_THRESHOLD'))
    production = '\n'.join(method(SOURCE, signature) for signature in (
        'private static final class CharData', 'private static void handleBeforeDraw(',
        'private static boolean isCjkOrSymbol(', 'private static float easeOutQuint(',
        'private static float appearanceProgress('))
    return r'''
import java.util.*;
public class TextEdits {
 static boolean smoothCursorEnabled, deleteEnabled=true;
 interface Spannable {}
 static class Editable implements Spannable {
  String value=""; public String toString(){return value;}
 }
 static class EditText {
  Editable text=new Editable(); State state=new State();
  boolean isFocused(){return true;} Editable getText(){return text;}
 }
 static class State {
  int drawingDepth,prevLen; boolean focused,hasAnimatingChars;
  String prevText=""; Map<Integer,CharData> charStartTimes=new HashMap<>();
  List<String> particles=new ArrayList<>();
 }
 static State getState(EditText e){return e.state;}
 static void startHeartbeat(EditText e){} static void setupCursor(EditText e,State s){}
 static void removeSpan(EditText e,State s){} static void updateHiddenSpan(EditText e,State s){}
 static void startAnimationLoop(EditText e,State s){}
 static boolean hasReplacementSpan(Spannable s,int from,int to){return false;}
 static void spawnDeleteParticles(EditText e,State s,int idx,String ch){s.particles.add(ch);}
 static void check(boolean ok,String why){if(!ok)throw new AssertionError(why);}
 static void edit(EditText e,String text){e.text.value=text;handleBeforeDraw(e);}
 /* PRODUCTION */
 public static void main(String[] args){
  EditText e=new EditText();
  switch(args[0]){
   case "rapid": {
    edit(e,"Т"); CharData first=e.state.charStartTimes.get(0);
    edit(e,"Ти");edit(e,"Тиш");
    check(e.state.charStartTimes.size()==3,"each typed letter retained");
    check(e.state.charStartTimes.get(0)==first,"typing must not restart earlier letters");
    edit(e,"аТиш");check(e.state.charStartTimes.get(1)==first,"insertion rebases running letters");
    edit(e,"Тиш");check(e.state.charStartTimes.get(0)==first,"deletion rebases running letters");
    edit(e,"Тиш");check(e.state.charStartTimes.get(0)==first,"redraw does not restart effect");
    break;
   }
   case "replacement": {
    edit(e,"типо");CharData prefix=e.state.charStartTimes.get(0);
    edit(e,"типа");CharData replaced=e.state.charStartTimes.get(3);
    check(replaced!=null&&replaced.replace&&replaced.text.equals("а"),"T9 replacement animates");
    check(e.state.charStartTimes.get(0)==prefix,"unchanged prefix retains timeline");
    edit(e,"ти");check(!e.state.charStartTimes.containsKey(3),"removed glyph retired");
    break;
   }
   case "bulk": {
    edit(e,"x".repeat(10000));
    check(e.state.charStartTimes.isEmpty(),"large paste must not animate every glyph");
    e.state.particles.add("old");edit(e,"");
    check(e.state.particles.isEmpty(),"bulk delete clears old particle cloud");
    edit(e,"abcdef");edit(e,"");
    check(e.state.particles.size()==MAX_DELETE_GLYPHS,"bounded delete emissions");
    e.state.particles.clear();deleteEnabled=false;edit(e,"abc");edit(e,"");
    check(e.state.particles.isEmpty(),"disabled deletion emits nothing");
    edit(e,"😀");check(e.state.charStartTimes.isEmpty(),"surrogate pair not split into glyphs");
    break;
   }
   case "easing": {
    for(boolean replacement:new boolean[]{false,true})for(int hz:new int[]{60,90,120,144}){
     check(appearanceProgress(-20,300,replacement)==0,"no early appearance");
     float previous=0;
     for(int frame=0;frame<=hz;frame++){
      float p=appearanceProgress(frame*1000L/hz,300,replacement);
      check(p>=previous&&p<=1,"bounded monotonic progress");previous=p;
     }
     check(previous==1,"transition finishes");
     float midpoint=appearanceProgress(150,300,replacement);
     check(midpoint>0&&midpoint<1,"midpoint is not a hard switch");
    }
    break;
   }
   default: throw new AssertionError("unknown scenario");
  }
 }
}
'''.replace('/* PRODUCTION */', constants + '\n' + production)


class CurrentTextAnimationTests(unittest.TestCase):
    def execute(self, source, scenario):
        with tempfile.TemporaryDirectory(prefix='text-edits-') as directory:
            java = Path(directory) / 'TextEdits.java'
            java.write_text(source)
            built = subprocess.run(['javac', str(java)], capture_output=True, text=True, timeout=30)
            self.assertEqual(built.returncode, 0, built.stderr)
            return subprocess.run(['java', '-cp', directory, 'TextEdits', scenario],
                                  capture_output=True, text=True, timeout=15)

    def test_current_edit_lifecycle(self):
        for scenario in ('rapid', 'replacement', 'bulk', 'easing'):
            with self.subTest(scenario=scenario):
                result = self.execute(harness(), scenario)
                self.assertEqual(result.returncode, 0, result.stderr)

    def test_negative_controls(self):
        for before, after, scenario, diagnostic in (
            ('shifted.put(n, e.getValue());', 'shifted.put(idx, e.getValue());', 'rapid', 'insertion rebases'),
            ('boolean isReplace = (delCount > 0 && insCount > 0);', 'boolean isReplace = false;',
             'replacement', 'T9 replacement animates'),
            ('st.particles.clear();', ';', 'bulk', 'bulk delete clears'),
        ):
            with self.subTest(scenario=scenario):
                source = harness()
                self.assertEqual(source.count(before), 1, 'mutation must change production once')
                result = self.execute(source.replace(before, after, 1), scenario)
                self.assertNotEqual(result.returncode, 0, 'regression escaped the check')
                self.assertIn(diagnostic, result.stderr)


if __name__ == '__main__':
    unittest.main()
