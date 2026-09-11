const fs = require('node:fs');
const path = require('node:path');
const cp = require('node:child_process');
const os = require('node:os');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../main/java');
const source = fs.readFileSync(path.join(root, 'org/telegram/ui/Components/EditTextEffects.java'), 'utf8');
function method(text, signature) {
    const start = text.indexOf(signature);
    assert(start >= 0);
    let i = text.indexOf('{', start) + 1, depth = 1;
    for (; depth && i < text.length; i++) {
        if (text[i] === '{') depth++;
        if (text[i] === '}') depth--;
    }
    return text.slice(start, i);
}
const invalidate = method(source, 'public void invalidateQuotes(boolean force)');
assert(method(source, 'public void invalidateEffects()').includes('lastQuoteLayout = null;'));
assert(!fs.existsSync(path.join(root, 'app/nimarkogram/messenger/TextEditDebug.java')));
for (const file of [
    'app/nimarkogram/messenger/textanim/NimarkoTextAnim.java',
    'org/telegram/ui/Components/ChatActivityEnterView.java',
    'org/telegram/ui/Components/EditTextCaption.java',
    'org/telegram/ui/Components/EditTextEffects.java',
    'org/telegram/ui/Components/QuoteSpan.java',
    'org/telegram/ui/iv/RichEditor.java',
]) {
    assert(!fs.readFileSync(path.join(root, file), 'utf8').includes('TextEditDebug'));
}

const java = `import java.util.ArrayList;
public class EditorQuoteLayoutTest {
    static class Layout {
        int width, height, lines;
        String text = "same text, same formatting";
        Layout(int w, int h, int l) { width=w; height=h; lines=l; }
        int getWidth() { return width; }
        int getHeight() { return height; }
        int getLineCount() { return lines; }
        String getText() { return text; }
    }
    static class QuoteSpan {
        static int builds;
        static boolean reenter;
        static boolean fail;
        static class Block {
            final int width, top;
            Block(Layout layout) { width=layout.width; top=layout.height/2; }
        }
        static ArrayList<Block> updateQuoteBlocks(Editor editor, Layout layout, ArrayList<Block> blocks, boolean[] relayout) {
            builds++;
            if (fail) { fail=false; throw new IllegalStateException("test layout failure"); }
            blocks.clear();
            if (layout != null) blocks.add(new Block(layout));
            if (reenter) { reenter=false; editor.invalidateQuotes(true); }
            return blocks;
        }
    }
    static class Editor {
        int lastText2Length, lastQuoteLayoutWidth, lastQuoteLayoutHeight, lastQuoteLineCount, quoteUpdatesTries;
        Layout lastQuoteLayout, layout;
        boolean[] quoteUpdateLayout;
        boolean quoteBlocksUpdating, editedWhileQuoteUpdating;
        ArrayList<QuoteSpan.Block> quoteBlocks = new ArrayList<>();
        Layout getLayout() { return layout; }
        int length() { return layout == null ? 0 : layout.text.length(); }
        void resetFontMetricsCache() {}
        ${invalidate}
    }
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    static void checkGeometry(Editor e) {
        e.invalidateQuotes(false);
        check(e.quoteBlocks.get(0).width == e.layout.width);
        check(e.quoteBlocks.get(0).top == e.layout.height/2);
    }
    public static void main(String[] args) {
        Editor e = new Editor();
        e.layout = new Layout(564, 13148, 208);
        checkGeometry(e);
        e.invalidateQuotes(false);
        int before = QuoteSpan.builds;
        for (int i=0;i<1000;i++) e.invalidateQuotes(false);
        check(before == QuoteSpan.builds);
        e.layout = new Layout(708, 10605, 167);
        checkGeometry(e);
        e.layout.height=9000; e.layout.lines=140;
        checkGeometry(e);
        e.layout.width=540;
        checkGeometry(e);
        int count=QuoteSpan.builds;
        e.lastQuoteLayout=null;
        checkGeometry(e);
        check(QuoteSpan.builds > count);
        QuoteSpan.reenter=true;
        e.invalidateQuotes(true);
        check(!e.quoteBlocksUpdating && !e.editedWhileQuoteUpdating);
        QuoteSpan.fail=true;
        try { e.invalidateQuotes(true); throw new AssertionError("failure swallowed"); }
        catch (IllegalStateException expected) {}
        check(!e.quoteBlocksUpdating && !e.editedWhileQuoteUpdating);
        checkGeometry(e);
        e.layout=null;
        e.invalidateQuotes(false);
        check(e.quoteBlocks.isEmpty());
        e.layout=new Layout(708, 10605, 167);
        checkGeometry(e);
        System.out.println("PASS: real quote invalidation method, reflow without text edits, style invalidation, stable frames, re-entry and detach");
    }
}`;
const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-editor-quotes-'));
fs.writeFileSync(path.join(directory, 'EditorQuoteLayoutTest.java'), java);
cp.execFileSync('javac', ['EditorQuoteLayoutTest.java'], {cwd:directory, stdio:'inherit'});
cp.execFileSync('java', ['EditorQuoteLayoutTest'], {cwd:directory, stdio:'inherit'});
const lengthOnly = invalidate.replace(/if \(force \|\| lastText2Length != newTextLength \|\| lastQuoteLayout != layout\s*\|\| lastQuoteLayoutWidth != width \|\| lastQuoteLayoutHeight != height \|\| lastQuoteLineCount != lines\)/,
    'if (force || lastText2Length != newTextLength)');
assert.notEqual(lengthOnly, invalidate);
const negative = java.replace(invalidate, lengthOnly);
fs.writeFileSync(path.join(directory, 'EditorQuoteLayoutTest.java'), negative);
cp.execFileSync('javac', ['EditorQuoteLayoutTest.java'], {cwd:directory, stdio:'inherit'});
const result = cp.spawnSync('java', ['EditorQuoteLayoutTest'], {cwd:directory, encoding:'utf8'});
assert.notEqual(result.status, 0);
assert(result.stderr.includes('AssertionError'));
console.log('PASS: length-only cache fails unchanged-text reflow; editor diagnostics removed');
