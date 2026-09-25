"""Regression coverage for gift/caption keyboards without search UI."""
from pathlib import Path
import unittest
from test_search_fallback_entrance import block
from test_sender_infocard_transitions import run_java


SOURCE = Path(__file__).resolve().parents[2] / 'main/java/org/telegram/ui/Components/EmojiView.java'


class OptionalSearchTest(unittest.TestCase):
    def test_tab_reset_without_search_ui_and_cleanup(self):
        source = SOURCE.read_text()
        adapter = source[source.index('class EmojiSearchAdapter'):]
        search = block(adapter, 'public void search(String text, boolean delay)')
        # Execute the production reset branch, before the asynchronous search body.
        reset = search[:search.index('if (!TextUtils.isEmpty(lastSearchEmojiString))')] + '}'
        cleanup = block(source, 'private void cancelSearches()')
        run_java('''
public class Transitions {
 static class TextUtils { static boolean isEmpty(String s) { return s == null || s.isEmpty(); } }
 static class Field { int calls; void showProgress(boolean b) { calls++; } }
 static class Adapter { int calls; void cancelSearch() { calls++; } }
 static class Grid { Object adapter; Object getAdapter(){return adapter;} void setAdapter(Object a){adapter=a;} }
 static class Animator { void setValue(boolean b, boolean immediate) {} }
 Field emojiSearchField, stickersSearchField;
 Adapter emojiSearchAdapter=new Adapter(), stickersSearchGridAdapter=new Adapter();
 Grid emojiGridView=new Grid(); Object emojiAdapter=new Object();
 Animator animatorSearchEmojiPackSelected=new Animator();
 String lastSearchEmojiString="old"; boolean searchWas=true; long selectedPackId=123;
 int cancellations, changes;
 void cancelSearch(){cancellations++;} void notifyDataSetChanged(){changes++;}
 RESET
 CLEANUP
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  for(String query:new String[]{null,"","smile"}) {
   Transitions t=new Transitions(); t.search(query,true);
   check(t.lastSearchEmojiString==null && t.selectedPackId==0 && !t.searchWas);
   check(t.emojiGridView.getAdapter()==t.emojiAdapter && t.cancellations==1 && t.changes==1);
  }
  for(int mask=0;mask<16;mask++) {
   Transitions t=new Transitions();
   if((mask&1)!=0)t.emojiSearchField=new Field();
   if((mask&2)!=0)t.stickersSearchField=new Field();
   if((mask&4)!=0)t.emojiSearchAdapter=null;
   if((mask&8)!=0)t.stickersSearchGridAdapter=null;
   t.cancelSearches();
   check(t.emojiSearchAdapter==null || t.emojiSearchAdapter.calls==1);
   check(t.stickersSearchGridAdapter==null || t.stickersSearchGridAdapter.calls==1);
  }
  Transitions t=new Transitions();t.emojiSearchField=new Field();t.search("SMILE",true);
  check("smile".equals(t.lastSearchEmojiString));
  t.search(null,true);check(t.emojiSearchField.calls==1 && t.lastSearchEmojiString==null);
 }
}
'''.replace('RESET', reset).replace('CLEANUP', cleanup))


if __name__ == '__main__':
    unittest.main()
