from pathlib import Path
import json
import unittest

from test_sender_infocard_transitions import run_java

ROOT = Path(__file__).resolve().parents[4]
UPDATER = ROOT / 'TMessagesProj/src/main/java/app/nimarkogram/messenger/updater'


class LocalizedChangelogTest(unittest.TestCase):
    def test_actual_language_resolver(self):
        source = (UPDATER / 'LocalizedChangelog.java').read_text()
        source = source.replace('package app.nimarkogram.messenger.updater;', '')
        source = source.replace('public final class LocalizedChangelog', 'final class LocalizedChangelog')
        run_java(source + '''
public class Transitions {
 static void eq(String want,String got){if(!want.equals(got))throw new AssertionError(want+" != "+got);}
 public static void main(String[] args){
  Map<String,String> map=new LinkedHashMap<>();
  map.put("ru","Русский");map.put("en","English");map.put("zh","中文");
  map.put("pt-BR","Brasil");map.put("zh_Hant","繁體");map.put("zh-Hans","简体");
  map.put("de","  ");map.put("uk",null);
  LocalizedChangelog notes=new LocalizedChangelog("legacy",map);
  eq("Русский",notes.resolve("ru_RU"));eq("Русский",notes.resolve(" RU-ru "));
  eq("English",notes.resolve("en-US"));eq("English",notes.resolve("fr"));
  eq("English",notes.resolve("de"));eq("English",notes.resolve("uk"));
  eq("Русский",notes.resolve("custompack", "ru", "en"));
  eq("Brasil",notes.resolve("pt_br"));eq("English",notes.resolve("pt-PT"));
  eq("繁體",notes.resolve("zh-TW"));eq("繁體",notes.resolve("zh-Hant-HK"));
  eq("简体",notes.resolve("zh-CN"));eq("中文",notes.resolve("zh"));
  eq("English",notes.resolve(null,""));
  // Reusing a cached Update after changing the app's language must reselect.
  eq("Русский",notes.resolve("ru"));eq("English",notes.resolve("en"));
  map.put("ru","changed");eq("Русский",notes.resolve("ru"));
  try {notes.getTranslations().put("ru","bad");throw new AssertionError();}
  catch(UnsupportedOperationException expected){}
  eq("legacy",new LocalizedChangelog("legacy",null).resolve("ru"));
  eq("",new LocalizedChangelog(null,null).resolve("ru"));
  eq("Русский",new LocalizedChangelog("legacy",notes.getTranslations()).resolve("ru"));
 }
}
''')

    def test_cache_and_presentation_wiring(self):
        updater = (UPDATER / 'NimarkoUpdater.java').read_text()
        config = (UPDATER / 'NimarkoUpdateConfig.java').read_text()
        sheet = (UPDATER / 'NimarkoUpdaterSheet.java').read_text()
        self.assertIn('obj.optJSONObject("changelogs")', updater)
        self.assertIn('NimarkoUpdateConfig.getLastUpdateChangelogs()', updater)
        self.assertIn('update.getChangelogsJson()', updater)
        self.assertIn('value instanceof String', updater)
        self.assertIn('catch (org.json.JSONException ignored)', updater)
        self.assertIn('.putString("lastUpdateChangelogs"', config)
        self.assertIn('update.getLocalizedChangelog()', sheet)
        self.assertNotIn('update.changelog', sheet)
        self.assertIn('new ChangelogCacheKey(markdown, style)', sheet)

    def test_published_translation_source(self):
        notes = json.loads((ROOT / 'docs/releases/75551.changelogs.json').read_text())
        self.assertEqual(set(notes), {'ru', 'en', 'zh'})
        for text in notes.values():
            self.assertEqual(text.count('\n- '), 4)
            self.assertEqual(text.count('### '), 2)


if __name__ == '__main__':
    unittest.main()
