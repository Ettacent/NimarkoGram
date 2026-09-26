"""Production-method JVM checks for metadata completion and emoji host ownership."""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

JAVA = Path(__file__).resolve().parents[2] / "main/java/org/telegram/ui"


def method(text, signature):
    start = text.index(signature)
    end = text.index("{", start) + 1
    depth = 1
    while depth:
        depth += (text[end] == "{") - (text[end] == "}")
        end += 1
    return text[start:end]


def run_java(code):
    with tempfile.TemporaryDirectory(prefix="nm-emoji-audit-") as folder:
        file = Path(folder) / "Harness.java"
        file.write_text(code)
        for command in (["javac", str(file)], ["java", "-cp", folder, "Harness"]):
            result = subprocess.run(command, capture_output=True, text=True, timeout=30)
            if result.returncode:
                raise AssertionError(result.stdout + result.stderr)


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "JDK required")
class EmojiAuditTests(unittest.TestCase):
    def test_metadata_completion_retry_readiness_and_owner_identity(self):
        text = (JAVA / "Components/AnimatedEmojiDrawable.java").read_text()
        signatures = [
            "public void fetchDocument(long id, ReceivedDocument onDone)",
            "private void fetchDocumentInternal(",
            "private void loadFromServer(ArrayList<Long> loadFromServerIds)",
            "private void loadFromServer(ArrayList<Long> loadFromServerIds, int attempt)",
            "private void finishFailedDocuments(",
            "public void processDocuments(",
            "private void deliverDocuments(",
        ]
        production = "\n".join(method(text, sig) for sig in signatures)
        request = method(text, "private void requestDocument()")
        run_java(r"""
import java.util.*;
public class Harness {
 static void check(boolean ok){if(!ok)throw new AssertionError();}
 interface ReceivedDocument {void run(TLRPC.Document d);}
 static class TLRPC {
  static class Document {long id;Document(long id){this.id=id;}}
  static class TL_messages_getCustomEmojiDocuments {ArrayList<Long> document_id;}
 }
 static class Vector {ArrayList<Object> objects=new ArrayList<>();Vector(long... ids){for(long id:ids)objects.add(new TLRPC.Document(id));}}
 static class Looper {static final Thread main=Thread.currentThread();static Looper getMainLooper(){return new Looper();}Thread getThread(){return main;}}
 static class AndroidUtilities {
  static ArrayDeque<Runnable> ready=new ArrayDeque<>(),delayed=new ArrayDeque<>();
  static void runOnUIThread(Runnable r){ready.add(r);}
  static void runOnUIThread(Runnable r,long delay){delayed.add(r);}
  static void drain(){int n=0;while(!ready.isEmpty()){check(n++<100);ready.remove().run();}}
  static void retry(){while(!delayed.isEmpty())ready.add(delayed.remove());drain();}
 }
 static class Choreographer {
  interface Frame {void run(long time);}static Choreographer getInstance(){return new Choreographer();}
  void postFrameCallback(Frame f){AndroidUtilities.runOnUIThread(()->f.run(0));}
 }
 static class FileLog {static void e(Exception e){}}
 static class ConnectionsManager {
  static final int ConnectionStateConnected=1,ConnectionStateUpdating=2;
  interface Callback {void run(Object res,Object error);}
  static Map<Integer,ConnectionsManager> managers=new HashMap<>();
  static ConnectionsManager getInstance(int account){return managers.computeIfAbsent(account,k->new ConnectionsManager());}
  int state=1;ArrayList<Callback> callbacks=new ArrayList<>();ArrayList<ArrayList<Long>> ids=new ArrayList<>();
  int getConnectionState(){return state;}
  void sendRequest(TLRPC.TL_messages_getCustomEmojiDocuments req,Callback cb){callbacks.add(cb);ids.add(req.document_id);}
  void reply(int index,Object response){callbacks.get(index).run(response,response==null?new Object():null);AndroidUtilities.drain();}
 }
 static class Fetcher {
  HashMap<Long,TLRPC.Document> emojiDocumentsCache=new HashMap<>();
  HashMap<Long,ArrayList<ReceivedDocument>> loadingDocuments;
  HashSet<Long> toFetchDocuments;Runnable fetchRunnable,uiDbCallback;int currentAccount;
  Fetcher(int account){currentAccount=account;}
  boolean checkThread(){return Thread.currentThread()==Looper.main;}
  void loadFromDatabase(ArrayList<Long> ids,boolean async){loadFromServer(ids);}
  void putToStorage(ArrayList<Object> objects){}
  void putDocument(TLRPC.Document d){emojiDocumentsCache.put(d.id,d);}
  void updateLiteModeValues(){}
  METHODS
 }
 static Fetcher drawableFetcher;
 static Fetcher getDocumentFetcher(int account){return drawableFetcher;}
 TLRPC.Document document;boolean documentRequestPending;long documentId=20;int currentAccount=20,initialized;
 void initDocument(boolean force){initialized++;}
 REQUEST
 public static void main(String[] args)throws Exception {
  check(Thread.currentThread()==Looper.main);
  Fetcher f=new Fetcher(1);ConnectionsManager c=ConnectionsManager.getInstance(1);int[] successes={0},failures={0};
  f.fetchDocument(1,d->{check(d!=null);successes[0]++;});
  f.fetchDocumentInternal(1,d->{if(d==null)failures[0]++;});AndroidUtilities.drain();
  check(c.callbacks.size()==1);c.reply(0,null);check(failures[0]==0);
  AndroidUtilities.retry();check(c.callbacks.size()==2);c.reply(1,new Vector());
  check(f.loadingDocuments.isEmpty()&&failures[0]==1&&successes[0]==0&&AndroidUtilities.delayed.isEmpty());
  f.fetchDocument(1,d->successes[0]++);AndroidUtilities.drain();c.reply(2,new Vector(1));
  check(successes[0]==1);f.fetchDocument(1,d->successes[0]++);check(successes[0]==2&&c.callbacks.size()==3);
  // Partial batch: only the missing ID is retried, then retired on terminal failure.
  f.fetchDocument(2,d->successes[0]++);f.fetchDocumentInternal(3,d->{if(d==null)failures[0]++;});
  AndroidUtilities.drain();c.reply(3,new Vector(2));AndroidUtilities.retry();
  check(c.ids.get(4).equals(Arrays.asList(3L)));c.reply(4,new Vector());check(f.loadingDocuments.isEmpty());
  // Readiness is rechecked when the delayed retry actually runs.
  f.fetchDocumentInternal(4,d->{if(d==null)failures[0]++;});AndroidUtilities.drain();c.reply(5,null);
  c.state=0;AndroidUtilities.retry();check(c.callbacks.size()==6&&f.loadingDocuments.isEmpty());c.state=1;
  // A delayed retry cannot steal a newly created owner for the same ID.
  f.fetchDocument(5,d->{});AndroidUtilities.drain();c.reply(6,null);
  f.finishFailedDocuments(new HashMap<>(f.loadingDocuments));
  f.fetchDocument(5,d->successes[0]++);AndroidUtilities.drain();
  ArrayList<ReceivedDocument> newer=f.loadingDocuments.get(5L);AndroidUtilities.retry();
  check(c.callbacks.size()==8&&f.loadingDocuments.get(5L)==newer);c.reply(7,new Vector(5));
  // Independent delivery drains pending before a scheduled retry.
  f.fetchDocument(6,d->{});AndroidUtilities.drain();c.reply(8,null);
  f.processDocuments(new Vector(6).objects);AndroidUtilities.retry();check(c.callbacks.size()==9);
  // One throwing consumer must not strand another document in a delivery batch.
  f.fetchDocument(7,d->{throw new RuntimeException();});f.fetchDocument(7,d->successes[0]++);
  f.fetchDocument(8,d->successes[0]++);AndroidUtilities.drain();c.reply(9,new Vector(7,8));check(f.loadingDocuments.isEmpty());
  // Retire before a terminal callback immediately requests the same key again.
  c.state=0;f.fetchDocumentInternal(9,d->{if(d==null)f.fetchDocument(9,x->successes[0]++);});
  AndroidUtilities.drain();c.reply(10,null);check(c.callbacks.size()==12&&f.loadingDocuments.containsKey(9L));c.reply(11,new Vector(9));
  // Account isolation.
  Fetcher other=new Fetcher(2);other.fetchDocument(1,d->{});AndroidUtilities.drain();check(ConnectionsManager.getInstance(2).callbacks.size()==1);
  // Off-main construction never sets a pending latch before UI dispatch.
  drawableFetcher=new Fetcher(20);Harness drawable=new Harness();
  Thread worker=new Thread(drawable::requestDocument);worker.start();worker.join();check(!drawable.documentRequestPending);
  AndroidUtilities.drain();check(drawable.documentRequestPending);
  ConnectionsManager dc=ConnectionsManager.getInstance(20);dc.state=0;dc.reply(0,null);check(!drawable.documentRequestPending);
  drawable.requestDocument();AndroidUtilities.drain();dc.reply(1,new Vector(20));
  check(drawable.document!=null&&drawable.initialized==1&&!drawable.documentRequestPending);
  drawable.requestDocument();check(dc.callbacks.size()==2&&drawable.initialized==1);
 }
}
""".replace("METHODS", production).replace("REQUEST", request))

    def test_inline_button_holder_is_balanced_and_does_not_clear_other_hosts(self):
        text = (JAVA / "Cells/ChatMessageCell.java").read_text()
        for name, value in (("onAttachedToWindow", "true"), ("onDetachedFromWindow", "false")):
            body = method(text, "protected void " + name + "()")
            self.assertIn("setBotButtonEmojiAttached(botButtons, " + value + ")", body)
            self.assertIn("setBotButtonEmojiAttached(transitionParams.transitionBotButtons, " + value + ")", body)
        self.assertNotIn("botButton.animatedEmojiDrawable.clear()", text)
        self.assertNotIn("animatedEmojiDrawable.addView(this::invalidateOutbounds)", text)
        production = method(text, "private void setBotButtonEmojiAttached(")
        run_java(r"""
import java.util.*;
public class Harness {
 static void check(boolean ok){if(!ok)throw new AssertionError();}
 static class Emoji {Set<Object> hosts=new HashSet<>();int frame=12;float alpha=.4f;void addView(Object h){hosts.add(h);}void removeView(Object h){hosts.remove(h);}}
 static class BotButton {Emoji animatedEmojiDrawable=new Emoji();}
 final Object botButtonEmojiHolder=new Object();
 METHOD
 public static void main(String[] args){Harness h=new Harness();BotButton b=new BotButton();Object other=new Object();b.animatedEmojiDrawable.addView(other);
  ArrayList<BotButton> buttons=new ArrayList<>(Arrays.asList(b));
  h.setBotButtonEmojiAttached(buttons,true);h.setBotButtonEmojiAttached(buttons,true);check(b.animatedEmojiDrawable.hosts.size()==2);
  h.setBotButtonEmojiAttached(buttons,false);h.setBotButtonEmojiAttached(buttons,false);check(b.animatedEmojiDrawable.hosts.equals(Collections.singleton(other)));
  h.setBotButtonEmojiAttached(buttons,true);check(b.animatedEmojiDrawable.frame==12&&b.animatedEmojiDrawable.alpha==.4f&&b.animatedEmojiDrawable.hosts.size()==2);
 }
}
""".replace("METHOD", production))

    def test_text_parts_detach_and_reattach_without_restarting_transition(self):
        text = (JAVA / "Components/AnimatedTextView.java").read_text()
        body = method(text, "public void setEmojiAttached(")
        attach = method(text, "public void attach()")
        detach = method(text, "public void detach()")
        for file in ("Components/AnimatedTextView.java", "bots/BotButtons.java"):
            content = (JAVA / file).read_text()
            self.assertIn("setEmojiAttached(true)", method(content, "protected void onAttachedToWindow()"))
            self.assertIn("setEmojiAttached(false)", method(content, "protected void onDetachedFromWindow()"))
        run_java(r"""
public class Harness {
 static void check(boolean ok){if(!ok)throw new AssertionError();}
 static class View {}
 static class AnimatedEmojiSpan {
  static int adds,releases;static View lastRelease;
  static class EmojiGroupedSpans {}
  static EmojiGroupedSpans update(int type,View v,EmojiGroupedSpans e,Object layout){adds++;return new EmojiGroupedSpans();}
  static void release(View v,EmojiGroupedSpans e){check(e!=null);releases++;lastRelease=v;}
 }
 boolean emojiAttached=true;int emojiCacheType;Object callback=new View();float transition=.37f;
 Part[] currentParts,oldParts;Object getCallback(){return callback;}
 class Part {
  AnimatedEmojiSpan.EmojiGroupedSpans emoji;View emojiHost;Object layout=new Object();
  ATTACH
  DETACH
 }
 METHOD
 public static void main(String[] args){Harness h=new Harness();h.setEmojiAttached(false);
  Part a=h.new Part(),b=h.new Part();h.currentParts=new Part[]{a};h.oldParts=new Part[]{b};
  a.attach();b.attach();check(AnimatedEmojiSpan.adds==0);
  h.setEmojiAttached(true);h.setEmojiAttached(true);check(AnimatedEmojiSpan.adds==2);
  Object layout=a.layout;View old=(View)h.callback;h.callback=new View();h.setEmojiAttached(false);
  check(AnimatedEmojiSpan.releases==2&&AnimatedEmojiSpan.lastRelease==old&&a.emoji==null);
  h.setEmojiAttached(false);check(AnimatedEmojiSpan.releases==2);
  h.setEmojiAttached(true);check(AnimatedEmojiSpan.adds==4&&a.emojiHost==h.callback&&a.layout==layout&&h.transition==.37f);
  b.detach();b.detach();check(AnimatedEmojiSpan.releases==3&&a.emoji!=null);
 }
}
""".replace("ATTACH", attach).replace("DETACH", detach).replace("METHOD", body))


if __name__ == "__main__":
    unittest.main()
