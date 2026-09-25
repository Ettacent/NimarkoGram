"""Late document thumbnail delivery and downloader album reply regressions."""
from pathlib import Path
import unittest
from test_sender_infocard_transitions import run_java

JAVA = Path(__file__).resolve().parents[2] / 'main/java'


class FilePreviewAlbumReplyTest(unittest.TestCase):
    def test_late_document_preview_and_album_headers(self):
        receiver = (JAVA / 'org/telegram/messenger/ImageReceiver.java').read_text()
        start = receiver.index('final boolean fadeLateDocumentThumb =')
        declaration = receiver[start:receiver.index(';', start) + 1]
        controller = (JAVA / 'app/nimarkogram/messenger/media/NimarkoMediaController.java').read_text()
        start = controller.index('MessageObject replyForThis =')
        reply = controller[start:controller.index(';', start) + 1]
        run_java('''
public class Transitions {
 static class MessageObject {static final int TYPE_FILE=9;int type;MessageObject(int t){type=t;}}
 Object currentParentObject;boolean animations=true;
 int loadingPlaceholderGeneration=-1,loadingPresentationGeneration=4;
 boolean canAnimateLoadingTransition(){return animations;}
 boolean fade(){DECLARATION return fadeLateDocumentThumb;}
 MessageObject reply(int chunkIdx,MessageObject replyTo){REPLY return replyForThis;}
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  Transitions t=new Transitions();t.currentParentObject=new MessageObject(9);
  check(!t.fade());t.loadingPlaceholderGeneration=4;check(t.fade());
  t.loadingPlaceholderGeneration=3;check(!t.fade());
  t.loadingPlaceholderGeneration=4;t.animations=false;check(!t.fade());
  t.animations=true;t.currentParentObject=new MessageObject(1);check(!t.fade());
  t.currentParentObject=null;check(!t.fade());
  MessageObject reply=new MessageObject(0);
  for(int count:new int[]{1,2,3,10,11,21})for(int i=0;i<count;i++){
   check(t.reply(i/10,reply)==(i<10?reply:null));
   check(t.reply(i/10,null)==null);
  }
 }
}
'''.replace('DECLARATION', declaration).replace('REPLY', reply))


if __name__ == '__main__':
    unittest.main()
