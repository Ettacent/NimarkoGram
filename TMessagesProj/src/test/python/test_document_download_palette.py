"""Execute the document-thumbnail controls block across loading/render passes."""
from pathlib import Path
import subprocess
import tempfile
import unittest


class DocumentDownloadPaletteTest(unittest.TestCase):
    def test_palette_does_not_follow_thumbnail_draw_pass(self):
        root = Path(__file__).resolve().parents[2]
        source = (root / "main/java/org/telegram/ui/Cells/ChatMessageCell.java").read_text()
        start = source.index("// Match getIconForCurrentState(): a thumbnail")
        end = source.index("            } else {\n                setDrawableBounds(menuDrawable, otherX = (int) buttonX", start)
        block = source[start:end]
        self.assertNotIn("key_chat_inLoader", block)
        self.assertNotIn("key_chat_outLoader", block)
        harness = """
import java.util.Arrays;
public class DocumentPaletteCheck {
 static class Theme {
  static final int key_chat_mediaLoaderPhoto=1,key_chat_mediaLoaderPhotoSelected=2,
   key_chat_mediaLoaderPhotoIcon=3,key_chat_mediaLoaderPhotoIconSelected=4,key_chat_mediaProgress=5;
 }
 static class MediaActionDrawable {static final int ICON_NONE=-1;}
 static class Progress {
  int[] keys;int icon=0;
  void setColorKeys(int a,int b,int c,int d){keys=new int[]{a,b,c,d};}
  void setProgressColor(int color){}
  int getIcon(){return icon;}
  void setIcon(int value,boolean same,boolean animated){icon=value;}
 }
 static class Image {boolean bitmap;float alpha;boolean hasBitmapImage(){return bitmap;}float getCurrentAlpha(){return alpha;}}
 Progress radialProgress=new Progress(),videoRadialProgress=new Progress();Image photoImage=new Image();
 boolean imageDrawn;int buttonState;
 int getThemedColor(int key){return key;}
 void draw(){BLOCK}
 public static void main(String[] args){
  DocumentPaletteCheck h=new DocumentPaletteCheck();
  // Simulate state-update resets between normal, skipped and blur render passes.
  for(int pass=0;pass<3;pass++)for(boolean drawn:new boolean[]{true,false})
   for(boolean bitmap:new boolean[]{true,false})for(float alpha:new float[]{0,.2f,.8f,.998f,1})
    for(int state:new int[]{-1,0,1}) {
     h.radialProgress.setColorKeys(10,11,12,13);h.videoRadialProgress.setColorKeys(10,11,12,13);
     h.radialProgress.icon=0;h.imageDrawn=drawn;h.photoImage.bitmap=bitmap;h.photoImage.alpha=alpha;h.buttonState=state;
     h.draw();
     if(!Arrays.equals(h.radialProgress.keys,new int[]{1,2,3,4})
      ||!Arrays.equals(h.videoRadialProgress.keys,h.radialProgress.keys))throw new AssertionError("palette flicker");
     boolean hide=drawn&&bitmap&&alpha>=.999f&&state==-1;
     if((h.radialProgress.icon==-1)!=hide)throw new AssertionError("download control removed early");
    }
 }
}
""".replace("BLOCK", block)
        with tempfile.TemporaryDirectory() as directory:
            runner = Path(directory) / "DocumentPaletteCheck.java"
            runner.write_text(harness)
            subprocess.run(["javac", str(runner)], check=True)
            subprocess.run(["java", "-cp", directory, "DocumentPaletteCheck"], check=True)


if __name__ == "__main__":
    unittest.main()
