"""Regression checks for the alpha 0 -> 1 jump observed in search-v3 logs."""
from pathlib import Path
import subprocess
import tempfile
import unittest
from test_search_fallback_entrance import block

UI = Path(__file__).resolve().parents[2] / "main/java/org/telegram/ui"


class SearchRebindContinuityTests(unittest.TestCase):
    def test_same_holder_content_rebind_does_not_reset_appearance(self):
        source = (UI / "Components/SearchViewPager.java").read_text()
        method = block(source, "public boolean animateChange(RecyclerView.ViewHolder oldHolder")
        java = """
class RecyclerView {
 static class ViewHolder { float alpha; int locks=2; }
}
class ItemHolderInfo {}
class Base {
 int delegated, finished;
 void dispatchChangeFinished(RecyclerView.ViewHolder h, boolean old) { finished++;h.locks--; }
 public boolean animateChange(RecyclerView.ViewHolder old, RecyclerView.ViewHolder next,
   ItemHolderInfo info,int x,int y,int toX,int toY){delegated++;old.alpha=1;return true;}
}
public class Harness extends Base {
""" + method + """
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  Harness h=new Harness();
  for(float alpha:new float[]{0f,.1f,.5f,.99f,1f}){
   RecyclerView.ViewHolder row=new RecyclerView.ViewHolder();row.alpha=alpha;
   check(!h.animateChange(row,row,new ItemHolderInfo(),0,181,0,181));
   check(row.alpha==alpha && row.locks==1 && h.delegated==0);
  }
  RecyclerView.ViewHolder row=new RecyclerView.ViewHolder();
  check(h.animateChange(row,row,new ItemHolderInfo(),0,181,0,362));
  check(h.delegated==1);
  check(h.animateChange(row,new RecyclerView.ViewHolder(),new ItemHolderInfo(),0,181,0,181));
  check(h.delegated==2 && h.finished==5);
 }
}
"""
        with tempfile.TemporaryDirectory(prefix="search-rebind-") as directory:
            path = Path(directory) / "Harness.java"
            path.write_text(java)
            result = subprocess.run(["javac", str(path)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            result = subprocess.run(["java", "-cp", directory, "Harness"], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("itemAnimator.setDelayAnimations(false)", source)

    def test_reset_callback_is_not_published_and_query_ids_do_not_repeat(self):
        source = (UI / "Adapters/DialogsSearchAdapter.java").read_text()
        callback = block(source, "public void onDataSetChanged(int searchId)")
        self.assertLess(callback.index("if (searchId == 0 || searchId != lastSearchId)"), callback.index("waitingResponseCount--;"))
        search = block(source, "public void searchDialogs(String text, int folderId, boolean allowPublicPosts)")
        self.assertIn("lastSearchId = ++searchGeneration;", search)
        self.assertNotIn("lastSearchId++;", search)
        self.assertNotIn("searchGeneration = 0", source)


if __name__ == "__main__":
    unittest.main()
