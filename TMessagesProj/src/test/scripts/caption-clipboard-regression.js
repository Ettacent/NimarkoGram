const fs = require('node:fs');
const path = require('node:path');
const cp = require('node:child_process');
const os = require('node:os');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../main/java');
const read = file => fs.readFileSync(path.join(root, file), 'utf8');
const caption = read('org/telegram/ui/Components/EditTextCaption.java');
const chat = read('org/telegram/ui/Components/ChatActivityEnterView.java');
function method(source, signature) {
    const start = source.indexOf(signature);
    assert(start >= 0, signature);
    let end = source.indexOf('{', start) + 1, depth = 1;
    for (; depth && end < source.length; end++) {
        if (source[end] === '{') depth++;
        if (source[end] === '}') depth--;
    }
    return source.slice(start, end).replace(/@android.annotation.TargetApi\(33\)/g, '');
}
const connection = method(caption, 'public InputConnection onCreateInputConnection(EditorInfo editorInfo)');
const restore = method(caption, 'protected CharSequence restoreClipboardEntities(CharSequence text)');
const clipboard = method(caption, 'private String getTelegramEntitiesClipboardHtml(CharSequence expectedPlainText)');
const chatRestore = method(chat, 'protected CharSequence restoreClipboardEntities(CharSequence text)');
const parse = method(caption, 'private SpannableStringBuilder parseClipboardHtml(String html)');
assert(parse.includes('CopyUtilities.fromHTML(html)'));
assert(parse.includes('span.applyFontMetrics('));
assert(parse.includes('QuoteSpan.normalizeQuotes(pasted)'));
assert(method(caption, 'public boolean onTextContextMenuItem(int id)').includes('pasteTelegramEntitiesFromClipboard(null)'));
assert(!chat.includes('new InputConnectionWrapper('));
assert(chat.includes('return InputConnectionCompat.createWrapper(ic, editorInfo, callback)'));
assert(read('org/telegram/ui/Components/EditTextEmoji.java').includes('new EditTextCaption('));
assert(read('org/telegram/ui/Stories/recorder/CaptionContainerView.java').includes('new EditTextEmoji('));
assert(read('org/telegram/ui/Components/ChatAttachAlert.java').includes('new EditTextEmoji('));

const java = `public class CaptionClipboardTest {
    static class EditorInfo {}
    static class TextAttribute {}
    interface InputConnection {
        boolean commitText(CharSequence text, int cursor);
        boolean commitText(CharSequence text, int cursor, TextAttribute attr);
    }
    static class Target implements InputConnection {
        CharSequence text; int cursor, commits; TextAttribute attr; boolean result=true;
        public boolean commitText(CharSequence t, int c) { text=t; cursor=c; attr=null; commits++; return result; }
        public boolean commitText(CharSequence t, int c, TextAttribute a) { text=t; cursor=c; attr=a; commits++; return result; }
    }
    static class InputConnectionWrapper implements InputConnection {
        final InputConnection target;
        InputConnectionWrapper(InputConnection t, boolean mutable) { target=t; }
        public boolean commitText(CharSequence t,int c) { return target.commitText(t,c); }
        public boolean commitText(CharSequence t,int c,TextAttribute a) { return target.commitText(t,c,a); }
    }
    static class TextUtils {
        static boolean equals(CharSequence a,CharSequence b) { return a==b || a!=null && b!=null && a.toString().equals(b.toString()); }
    }
    static class CustomHtml {
        static String remembered="formatted message";
        static boolean mayMatchTelegramEntitiesClipboard(CharSequence text) { return TextUtils.equals(text,remembered); }
        static boolean isTelegramEntitiesClipboardHtml(String html) { return html!=null && html.startsWith("tg:"); }
    }
    static class FileLog { static int errors; static void e(Throwable e) { errors++; } }
    static class Description { boolean html=true; boolean hasMimeType(String m) { return html; } }
    static class ClipData {
        int count=1; Description description=new Description(); Item item=new Item();
        int getItemCount() { return count; } Description getDescription() { return description; }
        Item getItemAt(int index) { return item; }
        static class Item {
            CharSequence text=CustomHtml.remembered; String html="tg:formatted";
            String getHtmlText() { return html; } CharSequence getText() { return text; }
            CharSequence coerceToText(Context c) { return CustomHtml.remembered; }
        }
    }
    static class ClipboardManager {
        ClipData clip=new ClipData(); int reads; boolean denied;
        ClipData getPrimaryClip() { reads++; if(denied) throw new SecurityException(); return clip; }
    }
    static class Context {
        static final String CLIPBOARD_SERVICE="clipboard";
        ClipboardManager manager=new ClipboardManager(); Object getSystemService(String s) { return manager; }
    }
    static class Base {
        final Context context=new Context(); InputConnection target=new Target();
        Context getContext() { return context; }
        public InputConnection onCreateInputConnection(EditorInfo info) { return target; }
    }
    static class Editor extends Base {
        final CharSequence rich=new StringBuilder("formatted message"); int parses; boolean fail;
        CharSequence parseClipboardHtml(String html) {
            if(html==null) return null;
            parses++; if(fail) throw new IllegalArgumentException(); return rich;
        }
        ${connection}
        ${restore}
        ${clipboard}
    }
    static class ChatEditor extends Editor {
        boolean isPaste;
        ${chatRestore}
    }
    static void check(boolean b) { if(!b) throw new AssertionError(); }
    public static void main(String[] args) {
        Editor e=new Editor(); Target t=(Target)e.target;
        InputConnection ic=e.onCreateInputConnection(new EditorInfo());
        check(ic.commitText(CustomHtml.remembered, -2));
        check(t.text==e.rich && t.cursor==-2 && t.commits==1);
        TextAttribute attr=new TextAttribute();
        ic.commitText(CustomHtml.remembered, 3, attr);
        check(t.text==e.rich && t.attr==attr && t.cursor==3 && t.commits==2);
        int reads=e.context.manager.reads;
        for(int i=0;i<1000;i++) ic.commitText("a", 1);
        check(e.context.manager.reads==reads && t.text.equals("a"));
        e.context.manager.clip.item.text="new clipboard";
        ic.commitText(CustomHtml.remembered, 1);
        check(t.text==CustomHtml.remembered);
        e.context.manager.clip=new ClipData(); e.context.manager.clip.item.html="external html";
        ic.commitText(CustomHtml.remembered, 1); check(t.text==CustomHtml.remembered);
        e.context.manager.clip=new ClipData(); e.context.manager.clip.count=2;
        ic.commitText(CustomHtml.remembered, 1); check(t.text==CustomHtml.remembered);
        e.context.manager.clip=new ClipData(); e.context.manager.clip.description=null;
        ic.commitText(CustomHtml.remembered, 1); check(t.text==CustomHtml.remembered);
        e.context.manager.clip=null;
        ic.commitText(CustomHtml.remembered, 1); check(t.text==CustomHtml.remembered);
        e.context.manager.clip=new ClipData(); e.fail=true;
        ic.commitText(CustomHtml.remembered, 1); check(t.text==CustomHtml.remembered);
        e.fail=false; e.context.manager.denied=true;
        ic.commitText(CustomHtml.remembered, 1); check(t.text==CustomHtml.remembered);
        e.context.manager=null;
        ic.commitText(CustomHtml.remembered, 1); check(t.text==CustomHtml.remembered);
        t.result=false; check(!ic.commitText("plain", 0));
        e.target=null; check(e.onCreateInputConnection(new EditorInfo())==null);
        ChatEditor chat=new ChatEditor(); InputConnection cc=chat.onCreateInputConnection(new EditorInfo());
        cc.commitText("plain",1); check(!chat.isPaste);
        cc.commitText(CustomHtml.remembered,1,attr); check(chat.isPaste);
        check(((Target)chat.target).text==chat.rich);
        System.out.println("PASS: shared caption IME routing, both commit overloads, spans identity, cursor/attributes/result forwarding, clipboard guards and chat paste flag");
    }
}`;
const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-caption-clipboard-'));
fs.writeFileSync(path.join(directory, 'CaptionClipboardTest.java'), java);
cp.execFileSync('javac', ['CaptionClipboardTest.java'], {cwd:directory, stdio:'inherit'});
cp.execFileSync('java', ['CaptionClipboardTest'], {cwd:directory, stdio:'inherit'});
console.log('PASS: attachment/viewer caption integration and existing menu-paste/parser source guards');
