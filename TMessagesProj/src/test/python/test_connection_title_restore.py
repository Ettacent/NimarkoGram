from pathlib import Path
import subprocess
import tempfile
import unittest
from test_in_app_notification_lifecycle import method

UI = Path(__file__).resolve().parents[2] / "main/java/org/telegram/ui"


class ConnectionTitleRestore(unittest.TestCase):
    def test_direct_title_refresh_preserves_overlay(self):
        setter = method((UI / "ActionBar/ActionBar.java").read_text(),
                        "public void setTitle(CharSequence value, Drawable rightDrawable, boolean gilroy)")
        code = """
class Drawable {}
class AnimatedEmojiDrawable {
 static class SwapAnimatedEmojiDrawable extends Drawable {void setParentView(Object o){}}
}
class Text {
 CharSequence text; Drawable right;
 void setVisibility(int v){} void setText(CharSequence s){text=s;}
 void setRightDrawable(Drawable d){right=d;}
 void setRightDrawableOnClick(Object o){}
}
public class Harness {
 boolean titleOverlayShown,isSearchFieldVisible,attached,fromBottom;
 static final int VISIBLE=0,INVISIBLE=4;
 Text[] titleTextView={new Text()};
 CharSequence lastTitle; Drawable lastRightDrawable; Object rightDrawableOnClickListener;
 void createTitleTextView(int index,boolean gilroy){titleTextView[index]=new Text();}
""" + setter + """
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  Harness h=new Harness(); Drawable badge=new Drawable();
  h.setTitle("Connecting",null,true);
  h.titleOverlayShown=true;
  h.setTitle("NimarkoGram",badge,true);
  check("Connecting".contentEquals(h.titleTextView[0].text));
  check("NimarkoGram".contentEquals(h.lastTitle));
  check(h.lastRightDrawable==badge);
  h.setTitle("Folder",badge,true);
  check("Connecting".contentEquals(h.titleTextView[0].text));
  h.titleOverlayShown=false;
  h.setTitle(h.lastTitle,h.lastRightDrawable,true);
  check("Folder".contentEquals(h.titleTextView[0].text));
 }
}
"""
        with tempfile.TemporaryDirectory() as tmp:
            source = Path(tmp) / "Harness.java"
            source.write_text(code)
            subprocess.run(["javac", str(source)], check=True, capture_output=True)
            subprocess.run(["java", "-cp", tmp, "Harness"], check=True, capture_output=True)

    def test_story_refresh_does_not_force_running_fade(self):
        source = (UI / "Stories/DialogStoriesCell.java").read_text()
        self.assertIn("animated || animatorHasTitleText.isAnimating()", source)
        header = method(source, "private void setHeaderText(")
        self.assertIn("TextUtils.equals(titleView.getText(), text)", header)
        self.assertIn("animated || titleView.getDrawable().isAnimating()", header)


if __name__ == "__main__":
    unittest.main()
