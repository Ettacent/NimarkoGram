"""Run the media cell's actual spoiler lifecycle and blur replacement methods."""
from pathlib import Path
import subprocess
import tempfile
import unittest
from test_material_gesture_ownership import body

SRC = Path(__file__).resolve().parents[2] / "main/java/org/telegram/ui/Cells/SharedPhotoVideoCell2.java"


class SharedMediaSpoilerLifecycleTest(unittest.TestCase):
    def test_actual_lifecycle(self):
        source = SRC.read_text()
        harness = """
public class SpoilerLifecycleCheck {
 static class Bitmap {boolean recycled;boolean isRecycled(){return recycled;}void recycle(){recycled=true;}}
 static class Receiver {
  Bitmap bitmap;int writes;
  Bitmap getBitmap(){return bitmap;}
  void setImageBitmap(Bitmap next){if(bitmap!=null&&bitmap.isRecycled())throw new AssertionError("recycled before replacement");bitmap=next;writes++;}
 }
 static class TLRPC {static class MessageMedia {Photo photo=new Photo();} static class Photo {long id=42;}}
 static class Owner {TLRPC.MessageMedia media=new TLRPC.MessageMedia();}
 static class Document {long id;}
 static class Message {int id=10;Owner messageOwner=new Owner();boolean spoiler=true,isMediaSpoilersRevealedInSharedMedia;boolean hasMediaSpoilers(){return spoiler;}long getDialogId(){return -100;}int getId(){return id;}Document getDocument(){return null;}}
 static class Utilities {static int calls;static Bitmap stackBlurBitmapMax(Bitmap b){calls++;return new Bitmap();}}
 static class SpoilerEffect2 {
  static int created;boolean destroyed;int index=-1,next;
  static boolean supports(){return true;}
  static SpoilerEffect2 getInstance(Object v){created++;SpoilerEffect2 s=new SpoilerEffect2();s.attach(v);return s;}
  void attach(Object v){if(index<0)index=next++;}
  void detach(Object v){index=-1;}
  int getAttachIndex(Object v){return index;}
  void reassignAttach(Object v,int i){index=i;}
 }
 boolean attached;Message currentMessageObject=new Message();
 Receiver imageReceiver=new Receiver(),blurImageReceiver=new Receiver();
 String spoilerBlurKey;int currentAccount;
 SpoilerEffect2 mediaSpoilerEffect2;int mediaSpoilerAttachIndex=-1;
 int getMeasuredHeight(){return 100;}int getMeasuredWidth(){return 100;}
 void updateSpoilers2(){SPOILERS}
 void updateSpoilerBlur(){BLUR}
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 public static void main(String[] args){
  SpoilerLifecycleCheck h=new SpoilerLifecycleCheck();
  h.updateSpoilers2();check(SpoilerEffect2.created==0,"detached cell attached renderer");
  h.attached=true;h.updateSpoilers2();int original=h.mediaSpoilerAttachIndex;
  h.mediaSpoilerEffect2.detach(h);h.updateSpoilers2();
  check(h.mediaSpoilerEffect2.getAttachIndex(h)==original,"pattern rotated on reattach");
  h.updateSpoilers2();check(SpoilerEffect2.created==1,"renderer recreated on measure");
  h.mediaSpoilerEffect2.destroyed=true;h.updateSpoilers2();
  check(h.mediaSpoilerEffect2.getAttachIndex(h)==original,"pattern changed on renderer recreation");
  SpoilerLifecycleCheck replacement=new SpoilerLifecycleCheck();replacement.attached=true;replacement.updateSpoilers2();
  check(replacement.mediaSpoilerAttachIndex==original,"same message changed pattern in a new cell");
  h.imageReceiver.bitmap=new Bitmap();h.updateSpoilerBlur();Bitmap first=h.blurImageReceiver.bitmap;
  h.updateSpoilerBlur();check(Utilities.calls==1&&h.blurImageReceiver.bitmap==first&&!first.recycled,"same source reblurred");
  h.imageReceiver.bitmap=new Bitmap();h.updateSpoilerBlur();
  check(Utilities.calls==1&&h.blurImageReceiver.bitmap==first&&!first.recycled,"quality upgrade flashed blur");
  h.imageReceiver.bitmap=null;h.updateSpoilerBlur();
  check(h.blurImageReceiver.bitmap==first&&!first.recycled,"temporary receiver gap cleared blur");
  h.imageReceiver.bitmap=new Bitmap();
  h.currentMessageObject.messageOwner.media.photo.id++;h.updateSpoilerBlur();
  check(Utilities.calls==2&&first.recycled&&h.blurImageReceiver.bitmap!=first,"replacement ownership");
  Bitmap second=h.blurImageReceiver.bitmap;h.currentMessageObject.isMediaSpoilersRevealedInSharedMedia=true;h.updateSpoilerBlur();
  check(h.blurImageReceiver.bitmap==null&&second.recycled,"revealed blur retained");
  h.currentMessageObject=null;h.updateSpoilerBlur();h.updateSpoilers2();
  check(h.mediaSpoilerEffect2==null,"renderer retained on clear");
 }
}
""".replace("SPOILERS", body(source, "private void updateSpoilers2(")).replace("BLUR", body(source, "private void updateSpoilerBlur("))
        with tempfile.TemporaryDirectory() as directory:
            runner = Path(directory) / "SpoilerLifecycleCheck.java"
            runner.write_text(harness)
            subprocess.run(["javac", str(runner)], check=True)
            subprocess.run(["java", "-cp", directory, "SpoilerLifecycleCheck"], check=True)

    def test_loaded_thumbnail_does_not_restart_shimmer(self):
        source = SRC.read_text()
        draw = body(source, "private void drawImpl(")
        self.assertIn("if ((currentMessageObject == null && style != STYLE_CACHE) || !imageReceiver.hasBitmapImage())", draw)
        self.assertNotIn("|| imageReceiver.getCurrentAlpha() != 1.0f || imageAlpha != 1f", draw)
        self.assertIn("imageReceiver.setCrossfadeOnReady(false)", source)
        self.assertIn("currentMessageObject.getDialogId() == messageObject.getDialogId()", source)


if __name__ == "__main__":
    unittest.main()
