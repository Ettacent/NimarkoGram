"""Production draw/presentation bookkeeping on a JVM with minimal View fakes."""
from pathlib import Path
import subprocess
import tempfile
import unittest
from test_in_app_notification_lifecycle import method

SOURCE = Path(__file__).resolve().parents[2] / "main/java/org/telegram/ui/Components/SharedMediaLayout.java"


class MediaGridEntranceContinuity(unittest.TestCase):
    def test_already_painted_and_recycled_rows(self):
        source = SOURCE.read_text()
        inner = source[source.index("public static class InternalListView"):]
        draw = method(inner, "public boolean drawChild(")
        seen = method(inner, "boolean hasPresentedMessage(")
        mark = method(inner, "void markPresentedMessage(")
        code = """
class Canvas {}
class View {
  static final int VISIBLE=0; int id=1, top=0, bottom=100, visibility=0; float alpha=1;
  int getVisibility(){return visibility;} float getAlpha(){return alpha;}
  int getBottom(){return bottom;} int getTop(){return top;}
}
class SharedPhotoVideoCell2 extends View {}
class RecyclerListView { static class FastScrollAdapter {} }
class Alphas extends java.util.HashMap<Integer,Float> {
  float get(int id,float fallback){return getOrDefault(id,fallback);}
}
class Base { public boolean drawChild(Canvas c,View v,long t){return false;} }
public class Harness extends Base {
  java.util.WeakHashMap<View,Integer> presentedMessages=new java.util.WeakHashMap<>();
  Alphas alphas=new Alphas();
  RecyclerListView.FastScrollAdapter getMovingAdapter(){return null;}
  Object getAdapter(){return null;} boolean isThisListView(){return true;}
  boolean isChangeColumnsAnimation(){return false;}
  int getMessageId(View v){return v.id;} int getHeight(){return 1000;}
  Alphas getMessageAlphaEnter(){return alphas;}
""" + draw + seen + mark + """
  static void check(boolean v){if(!v)throw new AssertionError();}
  public static void main(String[] args){
    Harness h=new Harness(); View v=new View(); Canvas c=new Canvas();
    check(!h.hasPresentedMessage(v,1));
    check(!h.drawChild(c,v,0)); // false is a normal successful draw
    check(h.hasPresentedMessage(v,1));
    v.id=2; check(!h.hasPresentedMessage(v,2));
    h.alphas.put(2,0f); h.drawChild(c,v,0); check(!h.hasPresentedMessage(v,2));
    h.alphas.put(2,.5f); h.drawChild(c,v,0); check(h.hasPresentedMessage(v,2));
    v.id=3; v.alpha=0; h.drawChild(c,v,0); check(!h.hasPresentedMessage(v,3));
    v.alpha=1; v.top=1100; h.drawChild(c,v,0); check(!h.hasPresentedMessage(v,3));
  }
}
"""
        with tempfile.TemporaryDirectory() as tmp:
            file = Path(tmp) / "Harness.java"
            file.write_text(code)
            subprocess.run(["javac", str(file)], check=True, capture_output=True)
            subprocess.run(["java", "-cp", tmp, "Harness"], check=True, capture_output=True)

    def test_entrance_does_not_reset_presented_or_animating_rows(self):
        body = method(SOURCE.read_text(), "private void animateItemsEnter(")
        self.assertLess(body.index("messageAlphaEnter.get(messageId) == null"),
                        body.index("messageAlphaEnter.put(messageId, 0f)"))
        self.assertIn("hasPresentedMessage(child, messageId)", body)


if __name__ == "__main__":
    unittest.main()
