const assert = require('node:assert/strict');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const cp = require('node:child_process');

const root = path.resolve(__dirname, '../../main/java');
const profile = fs.readFileSync(path.join(root, 'org/telegram/ui/ProfileActivity.java'), 'utf8');
const renderer = fs.readFileSync(path.join(root, 'app/nimarkogram/messenger/banners/NimarkoBannerRenderer.java'), 'utf8');

function method(source, signature) {
    const start = source.indexOf(signature);
    assert(start >= 0, signature);
    let at = source.indexOf('{', start);
    let depth = 0;
    do {
        if (source[at] === '{') depth++;
        if (source[at] === '}') depth--;
        at++;
    } while (depth > 0 && at < source.length);
    assert.equal(depth, 0, signature);
    return source.slice(start, at);
}

const resume = method(profile, 'public void onResume()');
assert.match(resume, /if \(!initialResume && listAdapter != null\)\s*\{[\s\S]*?requestProfileRowsUpdate\(false, false\)/);
assert.doesNotMatch(resume, /listAdapter\.notifyDataSetChanged\(\)/);
assert.match(method(profile, 'public void onBecomeFullyHidden()'), /refreshGiftsOnReturn = true;/);
assert.match(method(profile, 'private void refreshVisibleProfile()'), /if \(refreshGiftsOnReturn\)[\s\S]*?gifts\.refresh\(\)/);
assert.match(method(profile, 'private boolean isEmojiLoaded()'), /emoji\.isNotEmpty\(\) > 0f/);

const photoDraw = method(renderer, 'public void drawImageBanner(');
const gapDraw = method(renderer, 'private void drawXfadeOnly(');
assert.match(photoDraw, /int y1q = \(y1 \+ 3\) & ~3;/);
assert.match(gapDraw, /ensurePhotoGradient\(y1\)/);

const source = `
public class ProfileReturnBannerRenderTest {
    static class Config { static boolean profileBackgroundEmoji = true; }
    static class FloatValue { float value; void force(boolean b) { value = b ? 1 : 0; } }
    static class Emoji {
        long id; boolean outgoing;
        void set(long v, boolean animated) { outgoing = animated && id != 0 && id != v; id = v; }
        void setColor(int color) {}
        float isNotEmpty() { return outgoing ? 1 : 0; }
    }
    static class Shader { enum TileMode { CLAMP } }
    static class Color { static int argb(int a, int r, int g, int b) { return a; } }
    static class LinearGradient {
        final float bottom;
        LinearGradient(float x0, float y0, float x1, float y1, int c0, int c1, Shader.TileMode mode) { bottom = y1; }
    }
    final Emoji emoji = new Emoji();
    final FloatValue emojiLoadedT = new FloatValue(), emojiFullT = new FloatValue();
    long backgroundEmojiId = Long.MIN_VALUE;
    boolean emojiLoaded, hasEmoji, emojiIsCollectible;
    int emojiColor, gradKeyY1q = -1, invalidations;
    LinearGradient grad;
    void invalidate() { invalidations++; }
    ${method(profile, 'public void setBackgroundEmojiId(').replaceAll('app.nimarkogram.messenger.NimarkoConfig', 'Config')}
    ${method(renderer, 'private void ensurePhotoGradient(')}
    static void check(boolean ok, String what) { if (!ok) throw new AssertionError(what); }
    public static void main(String[] args) {
        ProfileReturnBannerRenderTest t = new ProfileReturnBannerRenderTest();
        t.emojiLoaded = true; t.emojiLoadedT.value = 1;
        t.setBackgroundEmojiId(42, false, true);
        check(t.hasEmoji && !t.emojiLoaded && t.emojiLoadedT.value == 0, "new premium pattern waits for its image");
        t.emojiLoaded = true; t.emojiLoadedT.value = 1;
        t.setBackgroundEmojiId(42, false, true);
        check(t.emojiLoaded && t.emojiLoadedT.value == 1, "unchanged pattern does not restart reveal");
        t.setBackgroundEmojiId(43, false, true);
        check(t.hasEmoji && !t.emojiLoaded && t.emojiLoadedT.value == 1 && t.emoji.outgoing,
              "ready outgoing pattern remains visible during replacement load");
        t.setBackgroundEmojiId(0, false, true);
        check(!t.hasEmoji && !t.emojiLoaded && t.emojiLoadedT.value == 0, "removed pattern cannot stick");
        t.ensurePhotoGradient(100);
        LinearGradient first = t.grad;
        t.ensurePhotoGradient(101);
        check(t.grad != first && t.gradKeyY1q == 104 && t.grad.bottom == 104, "decode-gap gradient follows resized header");
        LinearGradient second = t.grad;
        t.ensurePhotoGradient(103);
        check(t.grad == second, "stable quantized height reuses gradient");
        System.out.println("PASS: return row/gift guards and banner/premium rendering state");
    }
}
`;
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'profile-return-banner-'));
try {
    fs.writeFileSync(path.join(dir, 'ProfileReturnBannerRenderTest.java'), source);
    cp.execFileSync('javac', ['ProfileReturnBannerRenderTest.java'], { cwd: dir, stdio: 'inherit' });
    cp.execFileSync('java', ['ProfileReturnBannerRenderTest'], { cwd: dir, stdio: 'inherit' });
} finally {
    fs.rmSync(dir, { recursive: true, force: true });
}
