"""Portable emoji indexing and raw shared-media cursor regressions (no Android build)."""
import shutil
import unittest
from test_round_backend_lifecycle import JAVA, method, run_java

LOCAL = (JAVA / 'app/nimarkogram/messenger/utils/NimarkoLocalEmoji.java').read_text()
MEDIA = (JAVA / 'org/telegram/messenger/MediaDataController.java').read_text()
UI = (JAVA / 'org/telegram/ui/Components/SharedMediaLayout.java').read_text()


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'JDK required')
class LocalEmojiLinksTests(unittest.TestCase):
    def test_classification_and_raw_cursors(self):
        classify = method(MEDIA, 'public static int getMediaType(')
        hidden = method(MEDIA, 'public static boolean isLocalEmojiOnlyLinkMessage(')
        qualify = 'app.nimarkogram.messenger.utils.NimarkoLocalEmoji.'
        classes = '\n'.join('static class '+name+' extends '+base+' {}' for name, base in (
            ('TL_messageEntityTextUrl', 'MessageEntity'), ('TL_messageEntityUrl', 'MessageEntity'),
            ('TL_messageEntityEmail', 'MessageEntity'), ('TL_messageEntityCustomEmoji', 'MessageEntity'),
            ('TL_messageMediaEmpty', 'MessageMedia'), ('TL_messageMediaPhoto', 'MessageMedia'),
            ('TL_messageMediaPoll', 'MessageMedia'), ('TL_messageMediaDocument', 'MessageMedia'),
            ('TL_messageMediaWebPage', 'MessageMedia'),
            ('TL_documentAttributeVideo', 'DocumentAttribute'), ('TL_documentAttributeAudio', 'DocumentAttribute'),
            ('TL_documentAttributeAnimated', 'DocumentAttribute'), ('TL_documentAttributeSticker', 'DocumentAttribute'),
        ))
        run_java('''import java.util.*;
public class RoundHarness {
 static class TLRPC {
  static class Message {String message="😀";ArrayList<MessageEntity> entities=new ArrayList<>();MessageMedia media;int id;}
  static class MessageEntity {String url;int offset,length;boolean local;}
  static class MessageMedia {Document document;}
  static class Document {ArrayList<DocumentAttribute> attributes=new ArrayList<>();}
  static class DocumentAttribute {boolean round_message,voice;}
  CLASSES
 }
 static class Emoji {
  static class EmojiSpanRange {}
  static ArrayList<EmojiSpanRange> parseEmojis(String text,int[] only){
   ArrayList<EmojiSpanRange> a=new ArrayList<>();
   if(text.equals("😀")){only[0]=1;a.add(new EmojiSpanRange());}return a;
  }
 }
 static class MessageObject {
  TLRPC.Message messageOwner;String monthKey="month";
  MessageObject(TLRPC.Message m){messageOwner=m;}int getId(){return messageOwner.id;}
  boolean isVideo(){return false;}boolean isPhoto(){return false;}
  static TLRPC.MessageMedia getMedia(TLRPC.Message m){return m.media;}
 }
 static class NimarkoLocalEmoji {static final String PREFIX="tg://emoji?id=";PREDICATE}
 static class MediaDataController {
  static final int MEDIA_URL=3,MEDIA_POLL=8,MEDIA_PHOTOVIDEO=0,MEDIA_AUDIO=2,MEDIA_GIF=5,MEDIA_MUSIC=4,MEDIA_FILE=1;
  CLASSIFY HIDDEN
 }
 static class SparseArray<T> {HashMap<Integer,T> map=new HashMap<>();
  int indexOfKey(int key){return map.containsKey(key)?0:-1;}void put(int key,T value){map.put(key,value);}}
 ArrayList<MessageObject> messages=new ArrayList<>();ArrayList<String> sections=new ArrayList<>();
 HashMap<String,ArrayList<MessageObject>> sectionArrays=new HashMap<>();
 SparseArray<MessageObject>[] messagesDict=new SparseArray[]{new SparseArray<>(),new SparseArray<>()};
 int[] max_id={Integer.MAX_VALUE,Integer.MAX_VALUE};int min_id;boolean hasVideos,hasPhotos;
 ADD
 static void check(boolean v){if(!v)throw new AssertionError();}
 static TLRPC.Message local(int id){TLRPC.Message m=new TLRPC.Message();m.id=id;
  TLRPC.MessageEntity e=new TLRPC.TL_messageEntityTextUrl();e.url="tg://emoji?id=123";e.length=2;m.entities.add(e);return m;}
 public static void main(String[] args){
  TLRPC.Message m=local(100);check(MediaDataController.getMediaType(m)==-1);
  check(MediaDataController.isLocalEmojiOnlyLinkMessage(m));
  for(String bad:new String[]{"tg://emoji?id=x","tg://emoji?id=123&x=y","https://example.org"}){
   m=local(100);m.entities.get(0).url=bad;check(MediaDataController.getMediaType(m)==3);
   check(!MediaDataController.isLocalEmojiOnlyLinkMessage(m));
  }
  m=local(100);m.entities.get(0).offset=Integer.MAX_VALUE;check(MediaDataController.getMediaType(m)==3);
  m=local(100);m.message="ab";check(MediaDataController.getMediaType(m)==3);
  m=local(100);m.entities.add(new TLRPC.TL_messageEntityEmail());
  check(MediaDataController.getMediaType(m)==3&&!MediaDataController.isLocalEmojiOnlyLinkMessage(m));
  m=local(100);m.media=new TLRPC.TL_messageMediaPhoto();
  check(MediaDataController.getMediaType(m)==0&&!MediaDataController.isLocalEmojiOnlyLinkMessage(m));
  m=local(100);m.media=new TLRPC.TL_messageMediaWebPage();
  check(MediaDataController.getMediaType(m)==3&&!MediaDataController.isLocalEmojiOnlyLinkMessage(m));
  // Raw cached/server-only pages advance without creating rows/sections.
  RoundHarness h=new RoundHarness();check(!h.addMessage(new MessageObject(local(100)),0,false,false));
  check(!h.addMessage(new MessageObject(local(90)),0,false,false));
  check(h.max_id[0]==90&&h.min_id==100&&h.messages.isEmpty()&&h.sections.isEmpty());
  m=local(80);m.entities.add(new TLRPC.TL_messageEntityUrl());
  check(h.addMessage(new MessageObject(m),0,false,false)&&h.messages.size()==1&&h.max_id[0]==80);
  // Already reconstructed local entities and upward/merged/encrypted cursors.
  m=local(120);m.entities.clear();TLRPC.MessageEntity e=new TLRPC.TL_messageEntityCustomEmoji();e.local=true;m.entities.add(e);
  check(!h.addMessage(new MessageObject(m),0,true,false)&&h.min_id==120);
  check(!h.addMessage(new MessageObject(local(50)),1,false,false)&&h.max_id[1]==50);
  h=new RoundHarness();h.max_id[0]=Integer.MIN_VALUE;h.min_id=Integer.MAX_VALUE;
  check(!h.addMessage(new MessageObject(local(-50)),0,false,true));
  check(h.max_id[0]==-50&&h.min_id==-50);
 }
}'''.replace('CLASSES', classes)
            .replace('PREDICATE', method(LOCAL, 'public static boolean isLocalEmojiLink('))
            .replace('CLASSIFY', classify.replace(qualify, 'NimarkoLocalEmoji.'))
            .replace('HIDDEN', hidden.replace(qualify, 'NimarkoLocalEmoji.'))
            .replace('ADD', method(UI, 'public boolean addMessage(')))

    def test_raw_pages_and_forward_progress_are_preserved(self):
        process = method(MEDIA, 'private void processLoadedMedia(')
        self.assertNotIn('isLocalEmojiOnlyLinkMessage', process)
        self.assertIn('int totalCount = res.count;', process)
        database = method(MEDIA, 'private void putMediaDatabase(')
        self.assertIn('canAddMessageToMedia(message)', database)
        self.assertIn('messages.get(messages.size() - 1).id', database)
        self.assertIn('nextPageCursor != oldPageCursor && !(Boolean) args[5]', UI)
        self.assertIn('fromStart ? 0 : nextPageCursor, fromStart ? nextPageCursor : 0', UI)
        self.assertIn('type == MediaDataController.MEDIA_URL ? sharedMediaData[type].max_id[0] : 0', UI)
        storage = (JAVA / 'org/telegram/messenger/MessagesStorage.java').read_text()
        self.assertIn('MediaDataController.canAddMessageToMedia(message)', storage)

    def test_shared_link_cell_skips_transport_before_title_and_links(self):
        cell = (JAVA / 'org/telegram/ui/Cells/SharedLinkCell.java').read_text()
        loop = cell[cell.index('TLRPC.MessageEntity entity = message.messageOwner.entities.get(a);'):]
        guard = loop.index('NimarkoLocalEmoji.isLocalEmojiLink(')
        self.assertLess(guard, loop.index('entity.length <= 0'))
        self.assertIn('continue;', loop[guard:loop.index('entity.length <= 0')])
        self.assertLess(guard, loop.index('title = link.toString();'))
        self.assertLess(guard, loop.index('links.add(sb);'))
        self.assertIn('if (webPageLink != null && links.isEmpty())', loop)
