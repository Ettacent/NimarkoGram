"""Navigation migration and restart prompt regressions (no APK required)."""
from pathlib import Path
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET

from test_material_gesture_ownership import body

SRC = Path(__file__).resolve().parents[2]
JAVA = SRC / "main/java"


class MaterialDefaultRestartTest(unittest.TestCase):
    def test_actual_migration_once(self):
        config = (JAVA / "app/nimarkogram/messenger/NimarkoConfig.java").read_text()
        harness = """
import java.util.*;
public class MigrationCheck {
 static final int SPRING_MATERIAL=2;
 static Map<String,Object> values=new HashMap<>(); static int writes;
 static class Prefs {
  boolean getBoolean(String key,boolean fallback){return (Boolean)values.getOrDefault(key,fallback);}
 }
 static class Editor {
  Editor putInt(String key,int value){values.put(key,value);return this;}
  Editor putBoolean(String key,boolean value){values.put(key,value);return this;}
  void apply(){writes++;}
 }
 static Prefs getPreferences(){return new Prefs();}
 static Editor getEditor(){return new Editor();}
 static int getIntSafe(String key,int fallback){Object v=values.get(key);return v instanceof Integer?(Integer)v:fallback;}
 static int migrateMaterialNavigationDefault(){BODY}
 public static void main(String[] args){
  for(Object old:new Object[]{null,0,1,2,true}) {
   values.clear();writes=0;
   if(old!=null)values.put("springAnimation",old);
   if(migrateMaterialNavigationDefault()!=2||writes!=1)throw new AssertionError("initial migration");
   for(int choice:new int[]{0,1,2}) {
    values.put("springAnimation",choice);
    if(migrateMaterialNavigationDefault()!=choice||writes!=1)throw new AssertionError("choice reset");
   }
   values.remove("springAnimation");
   if(migrateMaterialNavigationDefault()!=2)throw new AssertionError("default");
  }
 }
}
""".replace("BODY", body(config, "private static int migrateMaterialNavigationDefault("))
        with tempfile.TemporaryDirectory() as directory:
            runner = Path(directory) / "MigrationCheck.java"
            runner.write_text(harness)
            subprocess.run(["javac", str(runner)], check=True)
            subprocess.run(["java", "-cp", directory, "MigrationCheck"], check=True)

    def test_restart_locales_and_safe_area(self):
        expected = {"values": "Restart required", "values-ru": "Нужен перезапуск",
                    "values-zh-rCN": "需要重启", "values-zh-rTW": "需要重新啟動"}
        for directory, text in expected.items():
            strings = {s.attrib.get("name"): s.text for s in ET.parse(SRC / "main/res" / directory / "strings.xml").getroot()}
            self.assertEqual(strings["NM_RestartRequired"], text)
            self.assertEqual(strings["CG_RestartToApply"], text)
        universal = (JAVA / "org/telegram/ui/Components/UniversalFragment.java").read_text()
        self.assertIn("return getBottomInset();", body(universal, "public int getBottomOffset("))
        helper = (JAVA / "app/nimarkogram/messenger/ui/RestartBulletin.java").read_text()
        self.assertIn("wrapper.getPaddingBottom() + AndroidUtilities.dp(12)", helper)
        for filename in ("app/nimarkogram/messenger/preferences/BasePreferencesActivity.java",
                         "org/telegram/ui/Components/UniversalFragment.java",
                         "app/nimarkogram/messenger/ui/BulletinCreator.kt"):
            self.assertIn("RestartBulletin.show(", (JAVA / filename).read_text())


if __name__ == "__main__":
    unittest.main()
