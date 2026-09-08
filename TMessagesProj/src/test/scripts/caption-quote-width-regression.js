const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const cp = require('node:child_process');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../main/java');
const source = fs.readFileSync(path.join(root, 'org/telegram/messenger/MessageObject.java'), 'utf8');
const caption = source.slice(source.indexOf('public TextLayoutBlocks('));
const start = caption.indexOf('                    if (lastLeft > 0)', caption.indexOf('if (currentBlockLinesCount > 1)'));
const end = caption.indexOf('\n                }\n                if (block.languageLayout', start);
assert(start >= 0 && end > start);
const singleLine = caption.slice(start, end);
const cell = fs.readFileSync(path.join(root, 'org/telegram/ui/Cells/ChatMessageCell.java'), 'utf8');
assert(cell.includes('captionWidth = captionLayout.textWidth;'));
assert(cell.includes('photoWidth = captionWidth + dp(10);'));
assert(caption.includes('lineWidth += AndroidUtilities.dp(32);'));
assert(caption.includes('lineWidth += AndroidUtilities.dp(15);'));
assert(caption.includes('width -= AndroidUtilities.dp(32);'));
const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-caption-width-'));
const java = `public class CaptionWidth {
    static class AndroidUtilities {
        static float density;
        static int dp(float value) { return (int) Math.ceil(value * density); }
    }
    static class TextLayoutBlock {
        static final int FLAG_RTL = 1, FLAG_NOT_RTL = 2;
        boolean quote, code;
        int directionFlags;
    }
    static int measure(int width, int linesMaxWidth, boolean quote, boolean code,
            float lastLeft, float textXOffset, int blocksCount, int textWidth) {
        TextLayoutBlock block = new TextLayoutBlock();
        block.quote = quote;
        block.code = code;
        boolean hasRtl = false;
        ${singleLine}
        if (block.directionFlags != (lastLeft > 0 ? TextLayoutBlock.FLAG_RTL : TextLayoutBlock.FLAG_NOT_RTL)) {
            throw new AssertionError("Direction flags changed");
        }
        return textWidth;
    }
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        int cases = 0;
        for (float density : new float[]{1f, 1.5f, 2f, 2.75f, 3f, 4f}) {
            AndroidUtilities.density = density;
            int width = AndroidUtilities.dp(320);
            for (int kind = 0; kind < 3; kind++) {
                int padding = AndroidUtilities.dp(kind == 1 ? 32 : kind == 2 ? 15 : 0);
                for (int text : new int[]{20, 120, 245, 300, 340}) {
                    int line = AndroidUtilities.dp(text);
                    for (int scale = 50; scale <= 100; scale += 5) {
                        for (int previous : new int[]{0, width}) {
                            for (int rtl = 0; rtl < 3; rtl++) {
                                float left = rtl == 0 ? 0 : AndroidUtilities.dp(20);
                                float offset = rtl == 2 ? 0 : left;
                                int measured = measure(width, line, kind == 1, kind == 2, left, offset, 2, previous);
                                int adjusted = line + (rtl == 2 ? (int) left : 0);
                                int expected = Math.max(previous, Math.min(width, adjusted + padding));
                                check(measured == expected);
                                int photo = AndroidUtilities.dp(320 * scale / 100f);
                                int actualBubble = Math.max(photo, measured + AndroidUtilities.dp(10));
                                check(actualBubble >= Math.min(width, adjusted + padding) + AndroidUtilities.dp(10));
                                cases++;
                            }
                        }
                    }
                }
            }
        }
        AndroidUtilities.density = 1f;
        check(measure(320, 245, true, false, 0, 0, 1, 0) == 277);
        check(measure(320, 245, false, false, 0, 0, 1, 0) == 245);
        System.out.println("PASS: " + cases + " caption width cases; quote/code padding, plain text, RTL, previous blocks and 50-100% media sizes");
    }
}`;
fs.writeFileSync(path.join(directory, 'CaptionWidth.java'), java);
cp.execFileSync('javac', ['CaptionWidth.java'], { cwd: directory, stdio: 'inherit' });
cp.execFileSync('java', ['CaptionWidth'], { cwd: directory, stdio: 'inherit' });
const old = java.replace(/int blockWidth = linesMaxWidth;[\s\S]*?textWidth = Math.max\(textWidth, Math.min\(width, blockWidth\)\);/,
    'textWidth = Math.max(textWidth, Math.min(width, linesMaxWidth));');
assert.notEqual(old, java);
fs.writeFileSync(path.join(directory, 'CaptionWidth.java'), old);
cp.execFileSync('javac', ['CaptionWidth.java'], { cwd: directory, stdio: 'inherit' });
const negative = cp.spawnSync('java', ['CaptionWidth'], { cwd: directory, encoding: 'utf8' });
assert.notEqual(negative.status, 0, 'The test must reject the previous caption measurement');
assert(negative.stderr.includes('AssertionError'));
console.log('PASS: previous implementation fails the same regression test');
