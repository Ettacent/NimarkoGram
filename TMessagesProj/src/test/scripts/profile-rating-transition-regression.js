// Node.js + JDK: execute extracted production transition and offset methods.
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const cp = require('node:child_process');
const assert = require('node:assert/strict');

const repo = path.resolve(__dirname, '../../../..');
const relative = 'TMessagesProj/src/main/java/org/telegram/ui/ProfileActivity.java';
const source = fs.readFileSync(path.join(repo, relative), 'utf8');
function method(text, signature) {
    const lineStart = text.indexOf('\n    ' + signature);
    assert(lineStart >= 0, signature);
    const start = lineStart + 5;
    const tokens = /\/\/[^\n]*|\/\*[\s\S]*?\*\/|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[{}]/g;
    tokens.lastIndex = text.indexOf('{', start);
    let depth = 0;
    for (let match; (match = tokens.exec(text));) {
        if (match[0] === '{') depth++;
        if (match[0] === '}' && --depth === 0) return text.slice(start, tokens.lastIndex);
    }
    throw new Error('Unclosed method: ' + signature);
}

const cell = fs.readFileSync(path.join(repo,
    'TMessagesProj/src/main/java/org/telegram/ui/Cells/UserInfoCell.java'), 'utf8');
const chat = fs.readFileSync(path.join(repo,
    'TMessagesProj/src/main/java/org/telegram/ui/ChatActivity.java'), 'utf8');
assert(method(cell, 'public boolean onTouchEvent(').includes('openThisProfile()'));
assert(method(chat, 'public void openThisProfile()').includes('openProfile(true)'));
assert(source.includes('playProfileAnimation = 1;'),
    'The avatar transition hands off to the compact transition mode');

const signatures = [
    'public void onTransitionAnimationEnd(boolean isOpen, boolean backward)',
    'private float getOnlineTextViewTranslationXWithOffsets(float onlineX)',
    'private float getOnlineTextViewTranslationYWithOffsets(float onlineY)',
    'private float getRatingViewTranslationXOffset()',
    'private float getRatingViewTranslationYOffset()',
];
function harness(text) {
    return `
public class ProfileRatingHarness {
    static class View {
        static final int VISIBLE = 0, GONE = 8;
        int visibility = VISIBLE;
        int getVisibility() { return visibility; }
        void setVisibility(int value) { visibility = value; }
        void setBackground(Object ignored) {}
        void requestLayout() {}
    }
    static class Header extends View {
        boolean isOpeningLayout = true;
        float expanded, visibilityFactor = 1;
        void setParentExpanded(float value) { expanded = value; }
        float getVisibilityFactor() { return visibilityFactor; }
    }
    static class Media { void onProfileTransitionFinished() {} }
    static class Notifications { void onAnimationFinish(int ignored) {} }
    static class Utilities {
        static float clamp01(float value) { return Math.max(0, Math.min(1, value)); }
    }
    int playProfileAnimation = 2, transitionIndex;
    boolean allowProfileAnimation = true, openAnimationInProgress = true, isPulledDown = true;
    boolean recreateMenuAfterAnimation, fragmentOpened, invalidateScroll, transitionAnimationInProress = true;
    float currentExpandAnimatorValue = 1, avatarAnimationProgress = 1;
    float customPhotoOffset, lastOnlineTextViewX, lastOnlineTextViewY, density = 1;
    Header ratingView = new Header(), actionsView = new Header(), musicView = new Header();
    View fragmentView = new View(), blurredView = new View();
    Media sharedMediaLayout = new Media();
    Notifications getNotificationCenter() { return new Notifications(); }
    int dp(float value) { return (int) Math.ceil(density * value); }
    void checkListViewScroll() {}
    void createActionBarMenu(boolean ignored) {}
    void checkPhotoDescriptionAlpha() {}
    void flushPendingProfileRowsUpdate() {}
    void scheduleMusicHeaderAnimation() {}
    ${signatures.map(signature => method(text, signature)).join('\n')}

    float badgeX() {
        // Real margins: rating=103dp, subtitle=105dp, expanded subtitle anchor=16dp.
        return dp(103) + getOnlineTextViewTranslationXWithOffsets(dp(16) - dp(105))
                - getRatingViewTranslationXOffset();
    }
    static void equal(float expected, float actual, String message) {
        if (Math.abs(expected - actual) > .001f) {
            throw new AssertionError(message + ": expected=" + expected + ", actual=" + actual);
        }
    }
    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        for (float density : new float[] {1, 1.5f, 2, 3}) {
            for (float visibility : new float[] {1, .5f, .25f, 0}) {
                for (int custom : new int[] {0, 28}) {
                    ProfileRatingHarness h = new ProfileRatingHarness();
                    h.density = density;
                    h.customPhotoOffset = h.dp(custom);
                    h.ratingView.visibilityFactor = visibility;
                    float beforeX = h.badgeX();
                    float beforeY = h.getOnlineTextViewTranslationYWithOffsets(200);
                    h.playProfileAnimation = 1; // Real type 2 -> type 1 finalizer hand-off.
                    h.onTransitionAnimationEnd(true, false);
                    equal(beforeX, h.badgeX(), "expanded rating shifted at transition end");
                    equal(beforeY, h.getOnlineTextViewTranslationYWithOffsets(200), "subtitle Y shifted");
                    equal(1, h.currentExpandAnimatorValue, "expanded state lost");
                    equal(1, h.ratingView.expanded, "rating colors reset");
                    equal(1, h.actionsView.expanded, "action buttons reset");
                    equal(1, h.musicView.expanded, "music reset");
                    check(h.badgeX() >= 0, "badge clipped at left edge");
                    h.onTransitionAnimationEnd(true, false);
                    equal(beforeX, h.badgeX(), "repeated completion moved badge");
                }
            }
        }
        ProfileRatingHarness compact = new ProfileRatingHarness();
        compact.playProfileAnimation = 1;
        compact.isPulledDown = false;
        compact.onTransitionAnimationEnd(true, false);
        equal(0, compact.currentExpandAnimatorValue, "compact title tap expanded avatar");
        equal(0, compact.ratingView.expanded, "compact rating colors changed");
        equal(0, compact.actionsView.expanded, "compact actions changed");
        equal(0, compact.musicView.expanded, "compact music changed");

        ProfileRatingHarness partial = new ProfileRatingHarness();
        partial.playProfileAnimation = 1;
        partial.currentExpandAnimatorValue = .4f;
        partial.onTransitionAnimationEnd(true, true);
        equal(.4f, partial.currentExpandAnimatorValue, "return from another page reset gesture state");
        partial.onTransitionAnimationEnd(false, true);
        equal(.4f, partial.currentExpandAnimatorValue, "closing reset gesture state");

        ProfileRatingHarness absent = new ProfileRatingHarness();
        absent.playProfileAnimation = 1;
        absent.ratingView = absent.actionsView = absent.musicView = null;
        absent.sharedMediaLayout = null;
        absent.onTransitionAnimationEnd(true, false);
        equal(1, absent.currentExpandAnimatorValue, "optional views changed expansion state");
        System.out.println("PASS: rating XY continuity, densities, visibility animation, custom photo offset, compact/expanded/return/close/null states");
    }
}
`;
}

const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'ng-profile-rating-test-'));
function run(text) {
    const java = path.join(temporary, 'ProfileRatingHarness.java');
    fs.writeFileSync(java, harness(text));
    cp.execFileSync('javac', [java], {stdio: 'pipe'});
    return cp.spawnSync('java', ['-cp', temporary, 'ProfileRatingHarness'], {encoding: 'utf8'});
}
try {
    const fixed = run(source);
    assert.equal(fixed.status, 0, fixed.stderr);
    process.stdout.write(fixed.stdout);
    // Negative control: undo only this fix, leaving every other method unchanged.
    const old = source.replace('final float expanded = isPulledDown ? 1f : 0f;',
        'final float expanded = 0f;');
    assert.notEqual(old, source);
    const broken = run(old);
    assert.notEqual(broken.status, 0, 'Regression must reproduce without the fix');
    assert(broken.stderr.includes('expanded rating shifted at transition end'), broken.stderr);
    console.log('PASS: negative control reproduces the previous leftward jump');
} finally {
    fs.rmSync(temporary, {recursive: true, force: true});
}
