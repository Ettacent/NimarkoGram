const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const cp = require('node:child_process');
const assert = require('node:assert/strict');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/java/org/telegram/ui/Components/ChatActivityEnterView.java'), 'utf8');
function method(name) {
    const start = source.indexOf('    private ' + name);
    const end = source.indexOf('\n    private ', start + 1);
    assert(start >= 0 && end > start, name);
    return source.slice(start, end);
}
const reserve = method('int getSeparatedComposerTextRightMargin(');
const geometry = method('void applyRecordedAudioPanelTransitionGeometry(');
const restoreDelay = method('long getRecordTextRestoreDelay(');
assert(source.includes('getRecordTextRestoreDelay(iconsAnimator,'));
assert(source.includes('getRecordTextRestoreDelay(iconsEndAnimator, 750)'));
assert(source.includes('if (!separatedComposerLayout && emojiButtonPaddingAlpha == 1f)'));
const attachStart = source.indexOf('private void attachRecordedAudioPanelToComposerHost()');
const attachEnd = source.indexOf('private void resetRecordedState()', attachStart);
assert(source.slice(attachStart, attachEnd).includes('params.leftMargin = sideInset;'));
assert(!source.includes('recordDraftTransitionProgress'));
assert(source.includes('getSeparatedComposerTextRightMargin(attachVisible)'));
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-composer-controls-'));
const java = `import java.util.*;
public class ComposerControls {
    static final int GONE = 8, DEFAULT_HEIGHT = 44;
    static float density;
    static int dp(float x) { return (int) Math.ceil(density * x); }
    static class View {
        int visibility, measuredWidth, updates;
        ViewGroup.LayoutParams params;
        int getVisibility() { return visibility; }
        int getMeasuredWidth() { return measuredWidth; }
        ViewGroup.LayoutParams getLayoutParams() { return params; }
        void setLayoutParams(ViewGroup.LayoutParams value) { params = value; updates++; }
    }
    static class ViewGroup extends View {
        static class LayoutParams { int width; }
        static class MarginLayoutParams extends LayoutParams { int leftMargin, rightMargin; }
        ArrayList<View> children = new ArrayList<>();
        int getChildCount() { return children.size(); }
        View getChildAt(int i) { return children.get(i); }
        int getPaddingLeft() { return 0; }
        int getPaddingRight() { return 0; }
    }
    static class FrameLayout { static class LayoutParams extends ViewGroup.MarginLayoutParams {} }
    static class ChatInputViewsContainer { static final int SEPARATED_COMPOSER_SIDE_SIZE = 44, SEPARATED_COMPOSER_GAP = 4; }
    static class Send { int width() { return dp(64); } }
    ViewGroup attachLayout = new ViewGroup();
    View recordedAudioPanel = new View();
    Send sendButton = new Send();
    Object editingMessageObject;
    boolean separatedComposerLayout = true;
    static class Animator {
        long delay, duration;
        long getStartDelay() { return delay; }
        long getDuration() { return duration; }
    }
    ${reserve}
    ${geometry}
    ${restoreDelay}
    static void check(boolean b) { if (!b) throw new AssertionError(); }
    public static void main(String[] args) {
        for (int[] timing : new int[][]{{700,150,300}, {700,150,700}, {600,150,750}}) {
            ComposerControls c = new ComposerControls();
            Animator trash = new Animator(); trash.delay=timing[0]; trash.duration=timing[1];
            check(c.getRecordTextRestoreDelay(trash,timing[2]) == timing[0]+timing[1]);
            for (int hz : new int[]{60,90,120,144}) {
                for (double t=0;t<1200;t+=1000d/hz) {
                    boolean textVisible=t>c.getRecordTextRestoreDelay(trash,timing[2]);
                    boolean trashVisible=t<trash.delay+trash.duration;
                    check(!(textVisible && trashVisible));
                }
            }
            c.separatedComposerLayout=false;
            check(c.getRecordTextRestoreDelay(trash,timing[2]) == Math.max(timing[2],timing[0]+timing[1]));
        }
        int cases = 0;
        for (float d : new float[]{1, 1.5f, 2, 2.75f, 3, 4}) {
            density = d;
            for (int controls = 0; controls <= 4; controls++) {
                ComposerControls c = new ComposerControls();
                FrameLayout.LayoutParams row = new FrameLayout.LayoutParams();
                row.rightMargin = dp(44);
                c.attachLayout.params = row;
                for (int i = 0; i < controls; i++) {
                    View button = new View();
                    button.params = new ViewGroup.MarginLayoutParams();
                    button.params.width = dp(44);
                    c.attachLayout.children.add(button);
                }
                for (int state = 0; state <= 2; state++) {
                    int expected = state == 0 ? dp(50) : Math.max(dp(50), controls * dp(44) + dp(44) + dp(6));
                    check(c.getSeparatedComposerTextRightMargin(state) == expected);
                    cases++;
                }
                if (controls > 0) {
                    c.attachLayout.children.get(0).visibility = GONE;
                    check(c.getSeparatedComposerTextRightMargin(1) == Math.max(dp(50), (controls - 1) * dp(44) + dp(44) + dp(6)));
                }
                c.recordedAudioPanel.params = new FrameLayout.LayoutParams();
                for (int frame = 0; frame <= 100; frame++) {
                    c.applyRecordedAudioPanelTransitionGeometry();
                    FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) c.recordedAudioPanel.params;
                    check(p.leftMargin == dp(48) && p.rightMargin == dp(48));
                }
                check(c.recordedAudioPanel.updates == 1);
                c.separatedComposerLayout = false;
                c.applyRecordedAudioPanelTransitionGeometry();
                FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) c.recordedAudioPanel.params;
                check(p.leftMargin == 0 && p.rightMargin == dp(64) - dp(44));
            }
        }
        System.out.println("PASS: " + cases + " trailing-control layouts, hidden controls, stable draft bounds and native composer fallback");
    }
}`;
fs.writeFileSync(path.join(dir, 'ComposerControls.java'), java);
cp.execFileSync('javac', ['ComposerControls.java'], { cwd: dir, stdio: 'inherit' });
cp.execFileSync('java', ['ComposerControls'], { cwd: dir, stdio: 'inherit' });
const oldReserve = java.replace('return Math.max(margin, controlsWidth + params.rightMargin + dp(6));', 'return dp(50);');
assert.notEqual(oldReserve, java);
fs.writeFileSync(path.join(dir, 'ComposerControls.java'), oldReserve);
cp.execFileSync('javac', ['ComposerControls.java'], { cwd: dir, stdio: 'inherit' });
const negative = cp.spawnSync('java', ['ComposerControls'], { cwd: dir, encoding: 'utf8' });
assert.notEqual(negative.status, 0);
assert(negative.stderr.includes('AssertionError'));
console.log('PASS: previous fixed text inset fails when the paid-post button is present');
