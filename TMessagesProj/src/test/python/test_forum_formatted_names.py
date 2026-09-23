"""Execute DialogCell's production formatter; only Android/data dependencies are stubbed.

Run with python3 -B -m unittest discover -s TMessagesProj/src/test/python
    -p test_forum_formatted_names.py -v
"""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


CELL = Path(__file__).resolve().parents[2] / "main/java/org/telegram/ui/Cells/DialogCell.java"


def formatter_source():
    source = CELL.read_text()
    start = source.index("    private static class ForumFormattedNames {")
    end = source.index("\n    private ColorFilter[] adaptiveEmojiColorFilter;", start)
    return source[start:end]


HARNESS = r"""
import java.util.*;
public class Harness {
    static class Paint {}
    static class Theme {
        static Paint[] dialogs_messagePaint = {new Paint()};
        static int key_chats_name;
    }
    static class R { static class string {
        static int Loading=1, NoTopicsCreated=2, NoMonoforumTopicsCreated=3;
    }}
    static String getString(int id) {
        return id == 1 ? "Loading" : id == 2 ? "NoTopics" : "NoMonoforumTopics";
    }
    static int dp(int value) { return value; }
    static class AndroidUtilities { static Object bold() { return null; } }
    static class TypefaceSpan { TypefaceSpan(Object a,int b,int c,Object d) {} }
    interface Spanned { int SPAN_EXCLUSIVE_EXCLUSIVE=33; }
    static class SpannableStringBuilder implements CharSequence {
        StringBuilder text=new StringBuilder();
        static SpannableStringBuilder valueOf(CharSequence value) {
            return new SpannableStringBuilder().append(value);
        }
        SpannableStringBuilder append(CharSequence value) { text.append(value); return this; }
        void insert(int index,String value) { text.insert(index,value); }
        void setSpan(Object span,int start,int end,int flags) {}
        public int length() { return text.length(); }
        public char charAt(int index) { return text.charAt(index); }
        public CharSequence subSequence(int start,int end) { return text.substring(start,end); }
        public String toString() { return text.toString(); }
    }
    static class TLRPC {
        static class Chat { long id; boolean mono; Chat(long id) { this.id=id; } }
        static class Peer { long id; Peer(long id) { this.id=id; } }
        static class Message { long topic; }
        static class TL_forumTopic {
            int id, top_message, unread_count; String title; Peer from_id;
            TL_forumTopic(int id,String title,int top) {
                this.id=id;this.title=title;top_message=top;from_id=new Peer(id);
            }
        }
    }
    static class MessageObject {
        int id; boolean out; TLRPC.Message messageOwner=new TLRPC.Message();
        MessageObject(int id,long topic) { this.id=id;messageOwner.topic=topic; }
        int getId() { return id; }
        boolean isOutOwner() { return out; }
        static long getTopicId(int account,TLRPC.Message message,boolean flag) { return message.topic; }
    }
    static class ChatObject { static boolean isMonoForum(TLRPC.Chat chat) { return chat.mono; } }
    static class DialogObject {
        static long getPeerDialogId(TLRPC.Peer peer) { return peer.id; }
        static String getName(long id) { return "Peer"+id; }
    }
    static class AvatarSpan {
        final int account; boolean needDrawShadow;
        AvatarSpan(DialogCell parent,int account) { this.account=account; }
        void setDialogId(long id) {}
    }
    static class ForumUtilities {
        static CharSequence getTopicSpannedName(TLRPC.TL_forumTopic topic,Paint paint,boolean flag) {
            return topic.title;
        }
    }
    static class TopicsController {
        Map<Long,List<TLRPC.TL_forumTopic>> topics=new HashMap<>();
        Set<Long> ended=new HashSet<>(); int preloads;
        List<TLRPC.TL_forumTopic> getTopics(long chat) { return topics.get(chat); }
        boolean endIsReached(long chat) { return ended.contains(chat); }
        void preloadTopics(long chat) { preloads++; }
        TLRPC.TL_forumTopic findTopic(long chat,long id) {
            List<TLRPC.TL_forumTopic> list=topics.get(chat);
            if(list!=null)for(TLRPC.TL_forumTopic topic:list)if(topic.id==id)return topic;
            return null;
        }
    }
    static class MessagesController {
        static Map<Integer,MessagesController> instances=new HashMap<>();
        TopicsController topics=new TopicsController();
        static MessagesController getInstance(int account) {
            return instances.computeIfAbsent(account,k->new MessagesController());
        }
        TopicsController getTopicsController() { return topics; }
    }
    static class DialogCell {
        static class FixedWidthSpan { FixedWidthSpan(int width) {} }
        /* FORMATTER */
    }
    static void check(boolean value,String reason) { if(!value)throw new AssertionError(reason); }
    static String text(DialogCell.ForumFormattedNames formatter) {
        return formatter.formattedNames == null ? "<null>" : formatter.formattedNames.toString();
    }
    static void seed(int account,long chat,String title) {
        MessagesController.getInstance(account).topics.topics.put(chat,
                new ArrayList<>(Arrays.asList(new TLRPC.TL_forumTopic(7,title,42))));
    }
    public static void main(String[] args) {
        DialogCell.ForumFormattedNames f=new DialogCell.ForumFormattedNames(new DialogCell());
        TLRPC.Chat chat=new TLRPC.Chat(10);
        MessageObject message=new MessageObject(42,7);
        TopicsController controller=MessagesController.getInstance(0).topics;
        switch(args[0]) {
            case "initial-zero":
                f.formatTopicsNames(0,null,chat);
                check(f.isLoadingState && text(f).equals("Loading") && controller.preloads==1,
                        "first bind without a top message must request genuine loading");
                break;
            case "cached-zero":
                seed(0,10,"Cached");f.formatTopicsNames(0,null,chat);
                check(text(f).equals("Cached") && !f.isLoadingState && controller.preloads==0,
                        "cached topics render even without a top message");
                break;
            case "other-chat":
                seed(0,10,"Alpha");seed(0,20,"Beta");f.formatTopicsNames(0,message,chat);
                f.formatTopicsNames(0,message,new TLRPC.Chat(20));
                check(text(f).equals("Beta"),"same message id in another chat must not reuse names");
                break;
            case "other-account":
                seed(0,10,"Alpha");seed(1,10,"Beta");f.formatTopicsNames(0,message,chat);
                f.formatTopicsNames(1,message,chat);
                check(text(f).equals("Beta"),"account scopes identical chat and message ids");
                break;
            case "mutated-topic":
                seed(0,10,"Before");f.formatTopicsNames(0,message,chat);
                TLRPC.TL_forumTopic topic=controller.findTopic(10,7);
                topic.title="After";topic.unread_count=1;f.formatTopicsNames(0,message,chat);
                check(text(f).startsWith("After") && f.lastTopicMessageUnread
                        && f.topMessageTopicEndIndex==5,"mutable title and unread must refresh");
                message.out=true;f.formatTopicsNames(0,message,chat);
                check(!f.lastTopicMessageUnread,"outgoing ownership must refresh unread highlight");
                break;
            case "reload":
                seed(0,10,"Cached");f.formatTopicsNames(0,message,chat);
                controller.topics.remove(10L);f.formatTopicsNames(0,message,chat);
                check(f.isLoadingState && text(f).equals("Loading"),"cache invalidation must enter loading");
                seed(0,10,"Reloaded");f.formatTopicsNames(0,message,chat);
                check(!f.isLoadingState && text(f).equals("Reloaded"),"loaded names replace loading");
                break;
            case "empty-ended":
                controller.topics.put(10L,new ArrayList<>());f.formatTopicsNames(0,message,chat);
                check(f.isLoadingState,"unknown empty cache still loads");
                controller.ended.add(10L);f.formatTopicsNames(0,message,chat);
                check(!f.isLoadingState && text(f).equals("NoTopics"),"confirmed empty is not loading");
                seed(0,10,"Created");f.formatTopicsNames(0,message,chat);
                check(text(f).equals("Created"),"new topics replace an empty result without a new message");
                break;
            case "null-chat":
                seed(0,10,"Old");chat.mono=true;f.formatTopicsNames(0,message,chat);
                check(f.avatarSpans!=null,"monoforum installs avatars");
                f.formatTopicsNames(0,null,null);
                check(f.formattedNames==null && f.avatarSpans==null && !f.isLoadingState
                        && !f.lastTopicMessageUnread && f.topMessageTopicEndIndex==0,
                        "null owner must release stale text and spans");
                break;
            default: throw new AssertionError(args[0]);
        }
        System.out.println("PASS: "+args[0]);
    }
}
"""


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "JDK required")
class ForumFormattedNamesTests(unittest.TestCase):
    scenarios = (
        "initial-zero", "cached-zero", "other-chat", "other-account",
        "mutated-topic", "reload", "empty-ended", "null-chat",
    )

    def run_cases(self, formatter, expected_success):
        with tempfile.TemporaryDirectory(prefix="forum-formatter-") as folder:
            path = Path(folder) / "Harness.java"
            path.write_text(HARNESS.replace("/* FORMATTER */", formatter))
            build = subprocess.run(["javac", str(path)], capture_output=True, text=True, timeout=30)
            self.assertEqual(build.returncode, 0, build.stderr)
            for scenario in self.scenarios:
                with self.subTest(scenario=scenario):
                    run = subprocess.run(["java", "-cp", folder, "Harness", scenario],
                                         capture_output=True, text=True, timeout=10)
                    if expected_success:
                        self.assertEqual(run.returncode, 0, run.stdout + run.stderr)
                    else:
                        self.assertNotEqual(run.returncode, 0, "old cache must fail: " + scenario)
                        self.assertIn("AssertionError", run.stderr)

    def test_production_formatter(self):
        self.run_cases(formatter_source(), True)

    def test_old_message_only_cache_negative_control(self):
        source = formatter_source()
        source = source.replace("private final DialogCell parent;",
                                "private final DialogCell parent; int lastMessageId;", 1)
        signature = "private void formatTopicsNames(int currentAccount, MessageObject message, TLRPC.Chat chat) {"
        self.assertIn(signature, source)
        source = source.replace(signature, signature + """
            int messageId = message == null || chat == null ? 0 : message.getId();
            if (lastMessageId == messageId && !isLoadingState) return;
            lastMessageId = messageId;
        """, 1).replace("            formattedNames = null;\n", "", 1)
        self.run_cases(source, False)


if __name__ == "__main__":
    unittest.main()
