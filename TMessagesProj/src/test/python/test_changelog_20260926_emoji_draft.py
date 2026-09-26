"""Host-JVM regression checks for changelog audit findings 5 and 6. No APK/Gradle."""
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import unittest


JAVA = Path(__file__).resolve().parents[2] / 'main/java'


def block(source, signature):
    start = source.index(signature)
    end = source.index('{', start) + 1
    depth = 1
    while depth:
        depth += (source[end] == '{') - (source[end] == '}')
        end += 1
    return source[start:end]


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'JDK required')
class EmojiDraftAuditTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        local = (JAVA / 'app/nimarkogram/messenger/utils/NimarkoLocalEmoji.java').read_text()
        media = (JAVA / 'org/telegram/messenger/MediaDataController.java').read_text()
        tl = (JAVA / 'org/telegram/tgnet/TLRPC.java').read_text()
        message = (JAVA / 'org/telegram/messenger/MessageObject.java').read_text()
        cls.media = media
        extraction = block(media, 'public ArrayList<TLRPC.MessageEntity> getEntities(CharSequence[] message, '
                           'boolean allowStrike, boolean parseMarkdown)')
        editor_loops = '\n'.join(extraction[extraction.index(start):extraction.index(end)] for start, end in (
            ('AnimatedEmojiSpan[] animatedEmojiSpans', 'CodeHighlighting.Span[] codeSpans'),
            ('QuoteSpan[] quoteSpans', 'FormattedDateSpan[] dateSpans'),
            ('FormattedDateSpan[] dateSpans', 'if (spannable instanceof Spannable)'),
        ))
        local_methods = '\n'.join((
            block(local, 'public static boolean canUse('),
            block(local, 'public static void replaceCustomEmojis(int account, long dialogId,\n'
                         '                                           ArrayList<TLRPC.MessageEntity> entities, boolean force)'),
        ))
        entity_fields = {
            'TL_messageEntityBold': '', 'TL_messageEntityItalic': '',
            'TL_messageEntityTextUrl': '', 'TL_messageEntityPre': '',
            'TL_messageEntityBlockquote': '',
            'TL_messageEntityCustomEmoji': 'long document_id; Document document; boolean local;',
            'TL_messageEntityMentionName': 'long user_id;',
            'TL_inputMessageEntityMentionName': 'InputUser user_id;',
            'TL_messageEntityFormattedDate': '''boolean relative, short_time, long_time,
                short_date, long_date, day_of_week; int date;''',
            'TL_messageEntityDiffReplace': 'String old_text;',
        }
        classes = []
        for name, fields in entity_fields.items():
            original = block(tl, f'public static class {name} ')
            constructor = re.search(r'public static final int constructor = ([^;]+);', original)[1]
            serializer = block(original, 'public void serializeToStream(')
            classes.append(f'static class {name} extends MessageEntity {{'
                           f'static final int constructor={constructor};{fields}{serializer}}}')
        # Use the real nested InputUser serializer too (identity is not equality).
        original = block(tl, 'public static class TL_inputUser ')
        constructor = re.search(r'public static final int constructor = ([^;]+);', original)[1]
        classes.append('static class TL_inputUser extends InputUser {'
                       f'static final int constructor={constructor};'
                       + block(original, 'public void serializeToStream(') + '}')
        code = r'''
import java.util.*;
import java.io.*;
public class AuditEmojiDraftHarness {
 static class OutputSerializedData {
  ByteArrayOutputStream bytes=new ByteArrayOutputStream();
  DataOutputStream out=new DataOutputStream(bytes);
  void writeInt32(int n){try{out.writeInt(n);}catch(IOException e){throw new RuntimeException(e);}}
  void writeInt64(long n){try{out.writeLong(n);}catch(IOException e){throw new RuntimeException(e);}}
  void writeString(String s){byte[] b=s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
   writeInt32(b.length);try{out.write(b);}catch(IOException e){throw new RuntimeException(e);}}
 }
 static class SerializedData extends OutputSerializedData {
  static int cleaned;
  byte[] toByteArray(){return bytes.toByteArray();} void cleanup(){cleaned++;}
 }
 static class FileLog {static int errors;static void e(Exception e){errors++;}}
 static class TLRPC {
  static final int FLAG_0=1,FLAG_1=2,FLAG_2=4,FLAG_3=8,FLAG_4=16,FLAG_5=32;
  static int setFlag(int f,int bit,boolean enabled){return enabled?f|bit:f&~bit;}
  static class MessageEntity {
   int offset,length,flags;boolean collapsed;String url,language;
   public void serializeToStream(OutputSerializedData s){throw new IllegalStateException("invalid entity");}
  }
  static class InputUser {long user_id,access_hash;public void serializeToStream(OutputSerializedData s){}}
  static class Document {long id;ArrayList<DocumentAttribute> attributes=new ArrayList<>();}
  static class DocumentAttribute {}
  static class TL_documentAttributeCustomEmoji extends DocumentAttribute {boolean free;}
  static class ChatFull {Object emojiset;}
  static class TL_messages_stickerSet {ArrayList<Document> documents=new ArrayList<>();}
  ENTITY_CLASSES
 }
 static class NimarkoConfig {static boolean localPremiumEmojis=true;}
 static class UserConfig {
  static UserConfig[] accounts={new UserConfig(),new UserConfig()};boolean premium;long self=100;
  static UserConfig getInstance(int a){return accounts[a];}
  boolean isPremium(){return premium;}long getClientUserId(){return self;}
 }
 static class AnimatedEmojiDrawable {
  static Map<String,TLRPC.Document> docs=new HashMap<>();static int lookups;
  static TLRPC.Document findDocument(int a,long id){lookups++;return docs.get(a+":"+id);}
 }
 static class MessagesController {
  static MessagesController instance=new MessagesController();TLRPC.ChatFull full;
  static MessagesController getInstance(int a){return instance;}
  TLRPC.ChatFull getChatFull(long id){return full;}
 }
 static class MediaDataController {
  static MediaDataController instance=new MediaDataController();TLRPC.TL_messages_stickerSet group;
  static MediaDataController getInstance(int a){return instance;}
  TLRPC.TL_messages_stickerSet getGroupStickerSetById(Object set){return group;}
 }
 static class MessageObject {FREE_CHECK}
 interface Spanned extends CharSequence {
  <T> T[] getSpans(int start,int end,Class<T> type);
  int getSpanStart(Object span);int getSpanEnd(Object span);
 }
 static class AnimatedEmojiSpan {
  long id;TLRPC.Document document;long getDocumentId(){return id;}
 }
 static class QuoteSpan {boolean isCollapsing;}
 static class FormattedDateSpan {TLRPC.TL_messageEntityFormattedDate entity;}
 static class EditorSpans implements Spanned {
  Object[] spans;EditorSpans(Object... spans){this.spans=spans;}
  public int length(){return 4;}public char charAt(int i){return "text".charAt(i);}
  public CharSequence subSequence(int start,int end){return "text".substring(start,end);}
  public int getSpanStart(Object span){return 0;}public int getSpanEnd(Object span){return 2;}
  @SuppressWarnings("unchecked") public <T> T[] getSpans(int start,int end,Class<T> type){
   ArrayList<T> result=new ArrayList<>();for(Object span:spans)if(type.isInstance(span))result.add(type.cast(span));
   return result.toArray((T[])java.lang.reflect.Array.newInstance(type,result.size()));
  }
 }
 static ArrayList<TLRPC.MessageEntity> extractEditorEntities(Spanned spannable){
  CharSequence[] message={spannable};ArrayList<TLRPC.MessageEntity> entities=null;
  EDITOR_LOOPS
  return entities;
 }
 static final String PREFIX="tg://emoji?id=";
 LOCAL_METHODS
 EQUALITY
 static void check(boolean b,String why){if(!b)throw new AssertionError(why);}
 static TLRPC.Document doc(long id,boolean free){
  TLRPC.Document d=new TLRPC.Document();d.id=id;
  TLRPC.TL_documentAttributeCustomEmoji a=new TLRPC.TL_documentAttributeCustomEmoji();a.free=free;
  d.attributes.add(a);return d;
 }
 static TLRPC.TL_messageEntityCustomEmoji emoji(long id){
  TLRPC.TL_messageEntityCustomEmoji e=new TLRPC.TL_messageEntityCustomEmoji();e.document_id=id;e.offset=3;e.length=2;return e;
 }
 static ArrayList<TLRPC.MessageEntity> list(TLRPC.MessageEntity... es){return new ArrayList<>(Arrays.asList(es));}
 static void nativeEmoji(int account,long peer,TLRPC.TL_messageEntityCustomEmoji e,boolean force){
  ArrayList<TLRPC.MessageEntity> es=list(e);replaceCustomEmojis(account,peer,es,force);
  check(es.get(0)==e,"native emoji changed");
 }
 static void converted(int account,long peer,TLRPC.TL_messageEntityCustomEmoji e,boolean force){
  ArrayList<TLRPC.MessageEntity> es=list(e);replaceCustomEmojis(account,peer,es,force);
  check(es.get(0) instanceof TLRPC.TL_messageEntityTextUrl,"missing local bridge");
  check(es.get(0).url.equals(PREFIX+e.document_id),"document id lost");
  check(es.get(0).offset==e.offset&&es.get(0).length==e.length,"range lost");
 }
 static void freeEmoji(){
  TLRPC.TL_messageEntityCustomEmoji e=emoji(7);e.document=doc(7,true);nativeEmoji(0,200,e,false);
  AnimatedEmojiDrawable.docs.put("0:7",doc(7,true));nativeEmoji(0,200,emoji(7),false);
  converted(1,200,emoji(7),false); // metadata must be account-scoped
  e=emoji(7);e.document=doc(99,false);nativeEmoji(0,200,e,false); // stale inline doc
  e=emoji(8);e.document=doc(8,false);converted(0,200,e,false);
  converted(0,200,emoji(404),false); // unknown metadata keeps legacy fallback
  TLRPC.TL_messageEntityBold b=new TLRPC.TL_messageEntityBold();
  ArrayList<TLRPC.MessageEntity> mixed=list(emoji(7),emoji(8),b);
  replaceCustomEmojis(0,200,mixed,false);
  check(mixed.get(0) instanceof TLRPC.TL_messageEntityCustomEmoji&&mixed.get(2)==b,"unrelated entities");
 }
 static void policy(){
  UserConfig.accounts[0].premium=true;nativeEmoji(0,200,emoji(8),false);
  UserConfig.accounts[0].premium=false;nativeEmoji(0,100,emoji(8),false);
  NimarkoConfig.localPremiumEmojis=false;nativeEmoji(0,200,emoji(8),false);
  NimarkoConfig.localPremiumEmojis=true;
  MessagesController.instance.full=new TLRPC.ChatFull();MessagesController.instance.full.emojiset=new Object();
  MediaDataController.instance.group=new TLRPC.TL_messages_stickerSet();
  MediaDataController.instance.group.documents.add(doc(8,false));nativeEmoji(0,-20,emoji(8),false);
  converted(0,200,emoji(8),false);
  nativeEmoji(0,-20,emoji(8),true); // force only converts local-marked entities
  TLRPC.TL_messageEntityCustomEmoji local=emoji(8);local.local=true;local.document=doc(8,true);
  UserConfig.accounts[0].premium=true;NimarkoConfig.localPremiumEmojis=false;
  converted(0,-20,local,true);converted(0,100,local,true);
 }
 static void different(TLRPC.MessageEntity a,TLRPC.MessageEntity b){
  check(!draftEntitiesEqual(list(a),list(b)),"changed entity lost: "+a.getClass());
 }
 static void equality(){
  check(draftEntitiesEqual(null,new ArrayList<>()),"null vs empty");
  check(!draftEntitiesEqual(null,list(emoji(1))),"added entity");
  check(!draftEntitiesEqual(list(emoji(1)),null),"removed entity");
  check(draftEntitiesEqual(list(emoji(1)),list(emoji(1))),"same emoji recreated");
  different(emoji(1),emoji(2));
  TLRPC.MessageEntity a=emoji(1),b=emoji(1);b.offset++;different(a,b);
  b=emoji(1);b.length++;different(a,b);
  different(new TLRPC.TL_messageEntityBold(),new TLRPC.TL_messageEntityItalic());
  TLRPC.TL_messageEntityTextUrl u=new TLRPC.TL_messageEntityTextUrl(),v=new TLRPC.TL_messageEntityTextUrl();
  u.url="tg://emoji?id=1";v.url="tg://emoji?id=2";different(u,v);
  TLRPC.TL_messageEntityPre p=new TLRPC.TL_messageEntityPre(),q=new TLRPC.TL_messageEntityPre();
  p.language="java";q.language="python";different(p,q);
  TLRPC.TL_messageEntityBlockquote x=new TLRPC.TL_messageEntityBlockquote(),y=new TLRPC.TL_messageEntityBlockquote();
  y.collapsed=true;different(x,y);
  TLRPC.TL_messageEntityFormattedDate d=new TLRPC.TL_messageEntityFormattedDate(),f=new TLRPC.TL_messageEntityFormattedDate();
  d.date=1;f.date=2;different(d,f);f.date=1;f.relative=true;different(d,f);
  TLRPC.TL_messageEntityDiffReplace r=new TLRPC.TL_messageEntityDiffReplace(),s=new TLRPC.TL_messageEntityDiffReplace();
  r.old_text="old";s.old_text="other";different(r,s);
  TLRPC.TL_messageEntityMentionName m=new TLRPC.TL_messageEntityMentionName(),n=new TLRPC.TL_messageEntityMentionName();
  m.user_id=1;n.user_id=2;different(m,n);
  TLRPC.TL_inputMessageEntityMentionName im=new TLRPC.TL_inputMessageEntityMentionName(),in=new TLRPC.TL_inputMessageEntityMentionName();
  im.user_id=new TLRPC.TL_inputUser();in.user_id=new TLRPC.TL_inputUser();
  im.user_id.user_id=in.user_id.user_id=42;im.user_id.access_hash=in.user_id.access_hash=123;
  check(draftEntitiesEqual(list(im),list(in)),"nested value equality, not identity");
  in.user_id.access_hash++;different(im,in);
  TLRPC.TL_messageEntityCustomEmoji cache=emoji(1);cache.local=true;cache.document=doc(1,false);
  check(draftEntitiesEqual(list(cache),list(emoji(1))),"transient metadata is not persisted");
  check(!draftEntitiesEqual(list(m,n),list(n,m)),"entity order changed");
  int cleaned=SerializedData.cleaned;
  different(new TLRPC.MessageEntity(),new TLRPC.MessageEntity());
  check(SerializedData.cleaned==cleaned+2,"buffers cleaned after failure");
 }
 static void editorOwnership(){
  AnimatedEmojiSpan emojiSpan=new AnimatedEmojiSpan();emojiSpan.id=1;emojiSpan.document=doc(1,false);
  QuoteSpan quoteSpan=new QuoteSpan();
  FormattedDateSpan dateSpan=new FormattedDateSpan();dateSpan.entity=new TLRPC.TL_messageEntityFormattedDate();dateSpan.entity.date=100;
  EditorSpans editor=new EditorSpans(emojiSpan,quoteSpan,dateSpan);
  ArrayList<TLRPC.MessageEntity> saved=extractEditorEntities(editor);
  ArrayList<TLRPC.MessageEntity> same=extractEditorEntities(editor);
  check(saved!=same&&draftEntitiesEqual(saved,same),"fresh equivalent editor list");
  for(int i=0;i<saved.size();i++)check(saved.get(i)!=same.get(i),"editor entity alias");
  check(((TLRPC.TL_messageEntityCustomEmoji)saved.get(0)).document==emojiSpan.document,"retain document cache");
  check(saved.get(2)!=dateSpan.entity,"formatted span entity must not be stored directly");
  emojiSpan.id=2;quoteSpan.isCollapsing=true;dateSpan.entity.date=101;dateSpan.entity.relative=true;
  ArrayList<TLRPC.MessageEntity> edited=extractEditorEntities(editor);
  check(((TLRPC.TL_messageEntityCustomEmoji)saved.get(0)).document_id==1,"old emoji id mutated");
  check(!saved.get(1).collapsed,"old quote mutated");
  check(((TLRPC.TL_messageEntityFormattedDate)saved.get(2)).date==100,"old date mutated");
  for(int i=0;i<saved.size();i++)different(saved.get(i),edited.get(i));
 }
 public static void main(String[] args){
  switch(args[0]){case "free":freeEmoji();break;case "policy":policy();break;case "equality":equality();break;case "ownership":editorOwnership();break;}
 }
}
'''
        code = (code.replace('ENTITY_CLASSES', '\n'.join(classes))
                .replace('FREE_CHECK', block(message, 'public static boolean isFreeEmoji('))
                .replace('EDITOR_LOOPS', editor_loops)
                .replace('LOCAL_METHODS', local_methods)
                .replace('EQUALITY', block(media, 'private static boolean draftEntitiesEqual(')))
        cls.temp = tempfile.TemporaryDirectory(prefix='audit-emoji-draft-')
        cls.addClassCleanup(cls.temp.cleanup)
        path = Path(cls.temp.name) / 'AuditEmojiDraftHarness.java'
        path.write_text(code)
        result = subprocess.run(['javac', '-d', cls.temp.name, str(path)],
                                capture_output=True, text=True, timeout=30)
        if result.returncode:
            raise AssertionError(result.stderr)

    def run_case(self, case):
        result = subprocess.run(['java', '-ea', '-cp', self.temp.name,
                                 'AuditEmojiDraftHarness', case],
                                capture_output=True, text=True, timeout=10)
        self.assertEqual(result.returncode, 0, result.stderr)

    def test_free_inline_and_cached_documents(self):
        self.run_case('free')

    def test_premium_self_group_and_force_semantics(self):
        self.run_case('policy')

    def test_all_serialized_entity_data(self):
        self.run_case('equality')

    def test_normal_editor_extraction_does_not_alias_saved_entities(self):
        self.run_case('ownership')

    def test_draft_guard_compares_entities_before_early_return(self):
        save = block(self.media, 'public void saveDraft(long dialogId, long threadId, CharSequence message, '
                     'ArrayList<TLRPC.MessageEntity> entities, TLRPC.Message replyToMessage, '
                     'ChatActivity.ReplyQuote quote, TLRPC.SuggestedPost suggestedPost, long effectId, '
                     'boolean noWebpage, boolean clean, TL_iv.RichMessage richMessage)')
        self.assertIn('&& draftEntitiesEqual(currentDraft.entities, draftMessage.entities)', save)
        self.assertLess(save.index('draftEntitiesEqual('), save.index('if (sameDraft)'))
        self.assertLess(save.index('if (sameDraft)'), save.index('saveDraft(dialogId, threadId, draftMessage'))


if __name__ == '__main__':
    unittest.main()
