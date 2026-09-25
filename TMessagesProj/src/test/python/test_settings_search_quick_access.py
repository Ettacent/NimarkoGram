"""Empty settings search remains useful without history or network FAQ."""
from pathlib import Path
import re
import unittest
from test_search_fallback_entrance import block
from test_sender_infocard_transitions import run_java

SOURCE = Path(__file__).resolve().parents[2] / 'main/java/org/telegram/ui/ProfileActivity.java'
CELL = SOURCE.parent / 'Cells/SettingsSearchCell.java'


class QuickAccessTest(unittest.TestCase):
    def test_empty_history_recent_deduplication_and_optional_entries(self):
        source = SOURCE.read_text()
        body = block(source, 'private void appendSettingsQuickAccess(ArrayList<UItem> items)')
        fill = block(source, 'public void fillItems(ArrayList<UItem> items)')
        self.assertNotIn('appendSettingsQuickAccess', fill[:fill.index('} else {')])
        self.assertIn('query == null ? "" : query.trim()', block(source, 'public void search(String query)'))
        run_java('''
import java.util.*;
public class Transitions {
 static class SearchResult {int guid;String searchTitle;SearchResult(int id,String title){guid=id;searchTitle=title;}}
 static class TextUtils {static boolean isEmpty(String s){return s==null||s.isEmpty();}}
 static class R {static class string {static int NM_SettingsQuickAccess=1;}}
 static String getString(int id){return "Quick access";}
 static class UItem {Object object;static UItem asGraySection(String s){return new UItem();}}
 static class SettingsSearchCell {static class Factory {static UItem ofStandard(String title,SearchResult r){UItem i=new UItem();i.object=r;return i;}}}
 ArrayList<Object> recentSearches=new ArrayList<>();SearchResult[] searchArray;
 BODY
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  Transitions t=new Transitions();SearchResult a=new SearchResult(100,"Privacy"),b=new SearchResult(1,"Notifications");
  t.searchArray=new SearchResult[]{b,null,a,a,new SearchResult(2,"Private notifications"),new SearchResult(200,"")};
  ArrayList<UItem> items=new ArrayList<>();t.appendSettingsQuickAccess(items);
  check(items.size()==3&&items.get(1).object==a&&items.get(2).object==b);
  items.clear();t.recentSearches.add(new SearchResult(100,"Privacy"));t.recentSearches.add("FAQ");
  t.appendSettingsQuickAccess(items);check(items.size()==2&&items.get(1).object==b);
  items.clear();t.recentSearches.add(b);t.appendSettingsQuickAccess(items);check(items.isEmpty());
  t.recentSearches.clear();t.searchArray=new SearchResult[1000];
  for(int i=0;i<1000;i++)t.searchArray[i]=new SearchResult(i,"Entry "+i);
  t.appendSettingsQuickAccess(items);check(items.size()==11);
  int[] expected={100,1,300,700,200,110,220,400,600,900};
  for(int i=0;i<expected.length;i++)check(((SearchResult)items.get(i+1).object).guid==expected[i]);
 }
}
'''.replace('BODY', body))

    def test_semantic_icons_and_nonzero_fallback_use_real_resources(self):
        body = block(CELL.read_text(), 'public static int standardIcon(')
        names = sorted(set(re.findall(r'R\.drawable\.(\w+)', body)))
        res = SOURCE.parents[4] / 'res'
        for name in names:
            self.assertTrue(list(res.glob('drawable*/' + name + '.*')), name)
        constants = ';'.join('static int %s=%d' % (name, i + 1)
                             for i, name in enumerate(names)) + ';'
        run_java('''
public class Transitions {
 static class R {static class drawable {CONSTANTS}}
 static class ProfileActivity {static class SearchAdapter {static class SearchResult {
  int guid,iconResId;SearchResult(int g,int i){guid=g;iconResId=i;}
 }}}
 BODY
 static int icon(int guid,int original){return standardIcon(new ProfileActivity.SearchAdapter.SearchResult(guid,original));}
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  check(icon(500,0)==R.drawable.msg_edit);
  check(icon(501,0)==R.drawable.msg_calls);
  check(icon(502,0)==R.drawable.msg_add);
  check(icon(110,123)==R.drawable.settings_devices);
  check(icon(220,123)==R.drawable.pill_proxy);
  check(icon(125,123)==R.drawable.msg2_email);
  check(icon(604,123)==R.drawable.msg2_archived_stickers);
  check(icon(9999,0)==R.drawable.msg_settings);
  check(icon(9999,321)==321);
  ProfileActivity.SearchAdapter.SearchResult r=new ProfileActivity.SearchAdapter.SearchResult(500,0);
  standardIcon(r);check(r.iconResId==0);
 }
}
'''.replace('CONSTANTS', constants).replace('BODY', body))

    def test_standard_factory_preserves_navigation_and_nimarko_is_opt_in(self):
        source = SOURCE.read_text()
        fill = block(source, 'public void fillItems(ArrayList<UItem> items)')
        self.assertNotIn('SettingsSearchCell.Factory.of(', fill)
        factory = block(CELL.read_text(), 'public static UItem ofStandard(')
        run_java('''
public class Transitions {
 static class UItem {Object object;CharSequence text;int intValue;}
 static UItem of(CharSequence text,Object result){UItem i=new UItem();i.text=text;i.object=result;return i;}
 BODY
 public static void main(String[] args){
  Object result=new Object();UItem i=ofStandard("title",result);
  if(i.object!=result||!i.text.equals("title")||i.intValue!=1)throw new AssertionError();
  if(of("normal",result).intValue!=0)throw new AssertionError();
 }
}
'''.replace('BODY', factory))
        nimarko = SOURCE.parents[3] / 'app/nimarkogram/messenger/preferences/MainPreferencesActivity.java'
        self.assertNotIn('ofStandard(', nimarko.read_text())

    def test_standard_geometry_and_recycled_cells(self):
        source = CELL.read_text()
        layout = block(source, 'private void setStandardLayout(')
        self.assertIn('standardLayout = standard', layout)
        self.assertIn('standard ? ImageView.ScaleType.FIT_CENTER : ImageView.ScaleType.CENTER', layout)
        self.assertIn('createFrame(standard ? 24 : 48, standard ? 24 : 48', layout)
        self.assertIn('LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT', layout)
        self.assertIn('standard ? 22 : 10, standard ? 20 : 8, standard ? 22 : 10', layout)
        self.assertIn('setEllipsize(standard ? TextUtils.TruncateAt.END : null)', layout)
        binding = block(source, 'public void bindView(')
        self.assertIn('cell.setStandardLayout(standard)', binding)
        self.assertIn('standard ? standardIcon(r) : r.iconResId', binding)
        self.assertIn('cell.setTextAndValueAndIcon(item.text, r.path, R.drawable.msg2_help, divider)', binding)
        self.assertIn('cell.setTextAndValue(item.text, r.path, true, divider)', binding)
        text = block(source, 'public void setTextAndValueAndIcon(')
        self.assertIn('value != null && value.length > 0', text)
        self.assertIn('left = standardLayout ? textInset() : 69', text)
        self.assertIn('requestLayout()', text)
        self.assertEqual(text.count('LocaleController.isRTL ? 16 : textInset()'), 2)

    def test_full_query_results_recent_and_faq_are_preserved(self):
        source = SOURCE.read_text()
        methods = block(source, 'public void fillItems(ArrayList<UItem> items)')
        methods += block(source, 'private void appendSettingsQuickAccess(ArrayList<UItem> items)')
        run_java('''
import java.util.*;
public class Transitions {
 static class SearchResult {int guid;String searchTitle;SearchResult(int g,String t){guid=g;searchTitle=t;}}
 static class MessagesController {static class FaqSearchResult {String title="FAQ";}}
 static class TextUtils {static boolean isEmpty(String s){return s==null||s.isEmpty();}}
 static class R {static class string {static int NM_SettingsQuickAccess=1,SettingsRecent=2,SettingsFaqSearchTitle=3;}}
 static String getString(int id){return "section "+id;}
 static class UItem {Object object;CharSequence text;static UItem asGraySection(String s){UItem i=new UItem();i.text=s;return i;}}
 static class SettingsSearchCell {static class Factory {
  static UItem ofStandard(CharSequence title,Object r){UItem i=new UItem();i.object=r;i.text=title;return i;}
 }}
 boolean searchWas;
 SearchResult[] searchArray;
 ArrayList<SearchResult> searchResults=new ArrayList<>();
 ArrayList<MessagesController.FaqSearchResult> faqSearchResults=new ArrayList<>(),faqSearchArray=new ArrayList<>();
 ArrayList<Object> recentSearches=new ArrayList<>();
 ArrayList<CharSequence> resultNames=new ArrayList<>();
 METHODS
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  Transitions t=new Transitions();SearchResult privacy=new SearchResult(100,"Privacy"),nested=new SearchResult(109,"Two-step verification");
  MessagesController.FaqSearchResult faq=new MessagesController.FaqSearchResult();
  t.searchArray=new SearchResult[]{privacy,nested};t.recentSearches.add(nested);t.faqSearchArray.add(faq);
  ArrayList<UItem> items=new ArrayList<>();t.fillItems(items);
  check(items.size()==6&&items.get(1).object==nested&&items.get(3).object==privacy&&items.get(5).object==faq);
  t.searchWas=true;t.searchResults.add(nested);t.resultNames.add("highlighted setting");
  t.faqSearchResults.add(faq);t.resultNames.add("highlighted FAQ");items.clear();t.fillItems(items);
  check(items.size()==3&&items.get(0).object==nested&&items.get(2).object==faq);
  check(items.get(0).text.equals("highlighted setting")&&items.get(2).text.equals("highlighted FAQ"));
  t.searchResults.clear();t.faqSearchResults.clear();items.clear();t.fillItems(items);check(items.isEmpty());
 }
}
'''.replace('METHODS', methods))


if __name__ == '__main__':
    unittest.main()
