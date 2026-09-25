"""Plugin toggles must rebind rows, not remove and re-add them."""
from pathlib import Path
import unittest
from test_search_fallback_entrance import block
from test_sender_infocard_transitions import run_java

SOURCE = Path(__file__).resolve().parents[2] / 'main/java/app/nimarkogram/messenger/plugins/ui/components/PluginCell.java'


class PluginRowIdentityTest(unittest.TestCase):
    def test_identity_ignores_new_delegate_and_operation_epoch(self):
        source = SOURCE.read_text()
        methods = '\n'.join(block(source, signature) for signature in (
            'public boolean equals(UItem a, UItem b)',
            'public boolean contentsEquals(UItem a, UItem b)',
        ))
        run_java('''
public class Transitions {
 static class TextUtils {static boolean equals(String a,String b){return java.util.Objects.equals(a,b);}}
 static class Plugin {String id;Plugin(String id){this.id=id;}String getId(){return id;}}
 static class UItem {Plugin plugin;Object object=new Object();long longValue;UItem(String id){plugin=new Plugin(id);}}
 METHODS
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  Transitions f=new Transitions();UItem old=new UItem("voice"),updated=new UItem("voice"),other=new UItem("privacy");
  updated.longValue=12;
  check(f.equals(old,updated));check(!f.contentsEquals(old,updated));
  check(!f.equals(old,other));
  updated.longValue=0;check(f.equals(old,updated));
  updated.plugin=null;check(!f.equals(old,updated));
 }
}
'''.replace('METHODS', methods))


if __name__ == '__main__':
    unittest.main()
