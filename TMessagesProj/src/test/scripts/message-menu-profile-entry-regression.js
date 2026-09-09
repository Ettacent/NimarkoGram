const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const cp = require('node:child_process');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../main/java');
const read = file => fs.readFileSync(path.join(root, file), 'utf8');
const menu = read('app/nimarkogram/messenger/ui/MessageMenuTelegramPlus.java');
const chat = read('org/telegram/ui/ChatActivity.java');
const profile = read('org/telegram/ui/ProfileActivity.java');
const radius = +menu.match(/CORNER_RADIUS_DP = (\d+)/)[1];
const padding = +menu.match(/BACKGROUND_PADDING_DP = (\d+)/)[1];
assert.equal(radius, 16);
assert.equal(padding, 8);
assert(chat.includes('.setRadius(dp(MessageMenuTelegramPlus.CORNER_RADIUS_DP))'));
assert(chat.includes('telegramPlusMessageMenu ? MessageMenuTelegramPlus.CORNER_RADIUS_DP : 12'));
assert(menu.includes('float strokeInset = strokePaint.getStrokeWidth() / 2f;'));
assert(menu.includes('AndroidUtilities.dp(CORNER_RADIUS_DP) - strokeInset'));
assert(menu.includes('AndroidUtilities.dp(CORNER_RADIUS_DP) - strokePaint.getStrokeWidth() / 2f'));
for (const density of [1, 1.5, 2, 2.75, 3, 4]) {
    const stroke = Math.max(1, density * 0.72);
    const edge = Math.ceil(padding * density);
    const outerRadius = Math.ceil(radius * density);
    const inset = edge + stroke / 2;
    const innerRadius = outerRadius - stroke / 2;
    assert(Math.abs(inset + innerRadius - edge - outerRadius) < 1e-5);
}
const marker = 'private void resumeDelayedFragmentAnimationAfterLayout() {';
const start = profile.indexOf(marker);
const end = profile.indexOf('final int generation = ++delayedProfileOpenLayoutGeneration;', start);
assert(start >= 0 && end > start);
const guard = profile.slice(start, end);
const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-profile-entry-'));
const java = `public class ProfileEntry {
    boolean profileLifecycleDestroyed, fragmentOpened, isFragmentOpened,
        transitionAnimationInProress, openAnimationInProgress, openGifts, openSimilar, openCommonChats;
    int delayedProfileOpenLayoutGeneration, resumes, layouts, resets, position = 9;
    static class View { void post(Runnable callback) { } }
    static class ListView { boolean computing; boolean isComputingLayout() { return computing; } }
    View fragmentView = new View();
    ListView listView = new ListView();
    Object layoutManager = new Object();
    void resumeDelayedFragmentAnimation() { resumes++; }
    void needLayout(boolean animated) { layouts++; }
    ${guard}
        resets++;
        position = 0;
    }
    static void check(boolean value) { if (!value) throw new AssertionError(); }
    public static void main(String[] args) {
        for (int destination = 1; destination <= 2; destination++) {
            ProfileEntry p = new ProfileEntry();
            p.openGifts = destination == 1;
            p.openSimilar = destination == 2;
            for (int event = 0; event < 5; event++) p.resumeDelayedFragmentAnimationAfterLayout();
            check(p.position == 9 && p.resets == 0 && p.resumes == 5 && p.layouts == 5);
            check(p.delayedProfileOpenLayoutGeneration == 5);
        }
        ProfileEntry regular = new ProfileEntry();
        regular.resumeDelayedFragmentAnimationAfterLayout();
        check(regular.resets == 1 && regular.position == 0);
        ProfileEntry common = new ProfileEntry();
        common.openCommonChats = true;
        common.resumeDelayedFragmentAnimationAfterLayout();
        check(common.resets == 1 && common.position == 0 && common.openCommonChats);
        ProfileEntry destroyed = new ProfileEntry();
        destroyed.profileLifecycleDestroyed = true;
        destroyed.resumeDelayedFragmentAnimationAfterLayout();
        check(destroyed.resumes == 0 && destroyed.resets == 0);
        ProfileEntry active = new ProfileEntry();
        active.fragmentOpened = true;
        active.resumeDelayedFragmentAnimationAfterLayout();
        check(active.position == 9 && active.resets == 0 && active.resumes == 1);
        ProfileEntry pending = new ProfileEntry();
        pending.listView.computing = true;
        pending.resumeDelayedFragmentAnimationAfterLayout();
        check(pending.resets == 0 && pending.resumes == 0);
        System.out.println("PASS: explicit profile destinations preserve scroll/header ownership across repeated preload callbacks");
    }
}`;
fs.writeFileSync(path.join(directory, 'ProfileEntry.java'), java);
cp.execFileSync('javac', ['ProfileEntry.java'], { cwd: directory, stdio: 'inherit' });
cp.execFileSync('java', ['ProfileEntry'], { cwd: directory, stdio: 'inherit' });
const old = java.replace('|| openGifts || openSimilar', '');
assert.notEqual(old, java);
fs.writeFileSync(path.join(directory, 'ProfileEntry.java'), old);
cp.execFileSync('javac', ['ProfileEntry.java'], { cwd: directory, stdio: 'inherit' });
const negative = cp.spawnSync('java', ['ProfileEntry'], { cwd: directory, encoding: 'utf8' });
assert.notEqual(negative.status, 0);
assert(negative.stderr.includes('AssertionError'));
console.log('PASS: menu background/stroke geometry agrees; previous profile reset fails the regression');
