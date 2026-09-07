const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const cp = require('node:child_process');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../main/java');
const read = name => fs.readFileSync(path.join(root, name), 'utf8');
const quote = read('app/nimarkogram/messenger/quotes/NimarkoQuoteCreator.java');
const cell = read('org/telegram/ui/Cells/ChatMessageCell.java');
const qr = read('org/telegram/ui/QrActivity.java');
const between = (source, begin, end) => {
    const start = source.indexOf(begin);
    const stop = source.indexOf(end, start + begin.length);
    assert(start >= 0 && stop > start, begin);
    return source.slice(start, stop);
};

const caption = between(cell, 'private void drawCaptionLayout(', 'public boolean shouldDrawTimeOnMedia(');
assert(!caption.includes('drawCommentLayout(canvas'), 'Caption crossfade must not paint comments');
assert.equal((cell.match(/drawCommentLayout\(canvas, 1f\)/g) || []).length, 1);
for (const name of ['ChatActivity', 'TextMessageEnterTransition', 'MessageSendPreview',
    'Components/MessagePreviewView', 'Components/ThanosEffect', 'Components/Paint/Views/MessageEntityView']) {
    const source = read(`org/telegram/ui/${name}.java`);
    assert(source.includes('.drawCommentLayout(canvas,'), name + ' retains its separate comment pass');
}
const comments = between(cell, 'public void drawCommentLayout(', 'private boolean areReactionsVisible(');
assert(comments.includes('float buttonBottom = layoutHeight + transitionParams.deltaBottom;'));
assert(comments.includes('float ly = buttonY;'));
assert(comments.includes('(int) (buttonBottom - dp(h) + 1)'));
assert(comments.includes('Theme.chat_replyLinePaint.getAlpha() * alpha'));

const snapshot = between(quote, 'private void createBitmapPreview(', 'private void completePreview(');
assert(snapshot.includes('if (!canSnapshotPreview(sourceWidth, sourceHeight))'));
assert(snapshot.includes('int bitmapWidth = sourceWidth;'));
assert(snapshot.includes('int bitmapHeight = sourceHeight;'));
assert(!snapshot.includes('canvas.scale('));
assert(!snapshot.includes('Math.sqrt('));
const entity = read('org/telegram/ui/Components/Paint/Views/MessageEntityView.java');
assert(entity.includes('canvas.getClipBounds(staticPresentationClip)'));
assert(quote.includes('card.updateVisibleMediaState();'));
assert(qr.includes('contentBitmapAlpha.setDuration(oldBitmap == null ? 450 : 2000);'));
assert(qr.includes('contentBitmapAlpha.set(SharedConfig.animationsEnabled() ? 0f : 1f, true);'));
assert(!qr.includes('if (!firstPrepare)'));
assert(qr.includes('contentBitmapAlpha.set(1f, !SharedConfig.animationsEnabled())'));
assert(qr.includes('openTransitionFinished || elapsed > 150'));

const policy = between(quote, '    private static boolean canSnapshotPreview(', '\n    public static boolean onRequestPermissionsResult(');
const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-preview-policy-'));
const source = `public class PreviewPolicy {
    static final long MAX_SNAPSHOT_PREVIEW_PIXELS = 2_000_000L;
    static final int MAX_SNAPSHOT_PREVIEW_SIDE = 4096;
    ${policy}
    static void check(boolean v) { if (!v) throw new AssertionError(); }
    public static void main(String[] args) {
        check(!canSnapshotPreview(0, 100));
        check(!canSnapshotPreview(100, -1));
        check(canSnapshotPreview(1000, 2000));
        check(!canSnapshotPreview(1000, 2001));
        check(canSnapshotPreview(400, 4096));
        check(!canSnapshotPreview(400, 4097));
        check(!canSnapshotPreview(Integer.MAX_VALUE, Integer.MAX_VALUE));
        int count = 0;
        for (int width : new int[]{360, 720, 1080, 1440, 2160}) {
            for (int messages = 1; messages <= 20; messages++) {
                for (int height : new int[]{100, 1000, 5000}) {
                    int totalHeight = height * messages;
                    boolean expected = width <= 4096 && totalHeight <= 4096
                        && (long) width * totalHeight <= 2_000_000L;
                    check(canSnapshotPreview(width, totalHeight) == expected);
                    count++;
                }
            }
        }
        System.out.println("PASS: " + count + " preview size cases, memory/texture bounds and full-resolution fallback");
    }
}`;
fs.writeFileSync(path.join(directory, 'PreviewPolicy.java'), source);
cp.execFileSync('javac', ['PreviewPolicy.java'], { cwd: directory, stdio: 'inherit' });
cp.execFileSync('java', ['PreviewPolicy'], { cwd: directory, stdio: 'inherit' });
console.log('PASS: comment drawing ownership, animated button bounds, QR first reveal and animations-disabled path');
