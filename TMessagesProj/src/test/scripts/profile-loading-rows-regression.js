const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const cp = require('node:child_process');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../main/java/org/telegram/ui');
const source = fs.readFileSync(path.join(root, 'Components/SharedMediaLayout.java'), 'utf8');
const flicker = fs.readFileSync(path.join(root, 'Components/FlickerLoadingView.java'), 'utf8');

function member(text, signature) {
    const start = text.indexOf(signature);
    assert(start >= 0, signature);
    const tokens = /\/\/[^\n]*|\/\*[\s\S]*?\*\/|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[{}]/g;
    tokens.lastIndex = text.indexOf('{', start);
    let depth = 0;
    for (let m; (m = tokens.exec(text));) {
        if (m[0] === '{') depth++;
        if (m[0] === '}' && --depth === 0) return text.slice(start, tokens.lastIndex);
    }
    throw new Error(signature);
}
const progress = member(source, 'mediaPages[a].progressView = new FlickerLoadingView(');
assert(!member(progress, 'public int getViewType()').includes('setIsSingleCell'), 'type lookup must not mutate measurement');
assert(progress.includes('new FlickerLoadingView(context, resourcesProvider)'), 'respect profile theme provider');
const links = member(source, 'private class SharedLinksAdapter');
assert(member(links, 'public int getCountForSection(').includes('getProfileLoadingRows(TAB_LINKS)'), 'bounded initial link rows');
assert(links.includes('setGlobalGradientView(globalGradientView)'), 'same shimmer phase for link rows');
const documents = member(source, 'private class SharedDocumentsAdapter');
assert(documents.includes('currentType == TAB_VOICE || currentType == TAB_AUDIO'), 'music and voice use audio skeleton');
const common = member(source, 'private class CommonGroupsAdapter');
assert(common.includes('getProfileLoadingRows(TAB_COMMON_GROUPS)'), 'common policy for group adapter');
assert(!common.includes('setAlpha(0'), 'do not restart whole-page group fade');
const animation = member(source, 'private void animateItemsEnter(');
const guard = animation.indexOf('adapter != expectedAdapter');
assert(guard > 0 && guard < animation.indexOf('messageAlphaEnter.put'), 'stale adapter rejected before changing opacity');
assert(animation.includes('!finalListView.isAttachedToWindow()'), 'no delayed fade after detach');
const entryCall = source.match(/if \(listView != null && addedMesages.size\(\) > 0 && \(([^\n]+)\)\) \{\s*animateItemsEnter\(listView, oldMessagesCount == 0 \? 0 : oldItemCount, addedMesages\);/);
assert(entryCall, 'initial content must ignore loading-row count; unchanged data must not fade again');

const harness = `
import java.util.*;
public class ProfileLoadingRowsHarness {
    ${[...source.matchAll(/public static final int TAB_[A-Z_]+ = \d+;/g)].map(m => m[0]).join('\n')}
    static boolean isAnyStoryPageType(int type) { return type == TAB_STORIES || type == TAB_ARCHIVED_STORIES || type >= 65536; }
    static float density;
    static int dp(float value) { return (int)Math.ceil(value * density); }
    static class SharedConfig { static boolean useThreeLinesLayout; }
    static class ReactedUsersListView { static int ITEM_HEIGHT_DP = 50; }
    static class MeasureSpec {
        static final int UNSPECIFIED = 0, EXACTLY = 1 << 30, AT_MOST = 2 << 30;
        static int makeMeasureSpec(int size, int mode) { return size | mode; }
        static int getSize(int spec) { return spec & 0x3fffffff; }
        static int getMode(int spec) { return spec & 0xc0000000; }
    }
    static class View {
        int width, height;
        protected void onMeasure(int w, int h) { setMeasuredDimension(MeasureSpec.getSize(w), MeasureSpec.getSize(h)); }
        void setMeasuredDimension(int w, int h) { width = w; height = h; }
        int getMeasuredWidth() { return width; }
        int getMeasuredHeight() { return height; }
    }
    static class FlickerLoadingView extends View {
        ${[...flicker.matchAll(/public (?:final static|static final) int [A-Z0-9_]+ = \d+;/g)].map(m => m[0]).join('\n')}
        boolean isSingleCell, ignoreHeightCheck;
        int itemsCount = 1;
        void setIsSingleCell(boolean value) { isSingleCell = value; }
        void setItemsCount(int value) { itemsCount = value; }
        int getAdditionalHeight() { return 0; }
        int getViewType() { return 0; }
        int getColumnsCount() { return 3; }
        ${member(flicker, 'protected void onMeasure(')}
        ${member(flicker, 'private int getCellHeight(')}
        int rowHeight(int width) { return getCellHeight(width); }
    }
    int[] hasMedia = new int[9];
    int[] mediaColumnsCount = {3, 3};
    static class ChatInfo { int participants_count; }
    static class ChatUsersAdapter { ChatInfo chatInfo; }
    ChatUsersAdapter chatUsersAdapter = new ChatUsersAdapter();
    static class MediaPage { int selectedType; }
    MediaPage mediaPage = new MediaPage();
    ${member(source, 'private static int boundedProfileLoadingRows(')}
    ${member(source, 'private int getProfileLoadingRows(')}
    class Progress extends FlickerLoadingView {
        ${member(progress, 'protected void onMeasure(')}
        ${member(progress, 'public int getColumnsCount()')}
        ${member(progress, 'public int getViewType()')}
    }
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) {
        int checks = 0;
        ProfileLoadingRowsHarness host = new ProfileLoadingRowsHarness();
        Progress view = host.new Progress();
        int[][] rows = {{TAB_FILES, 56}, {TAB_VOICE, 56}, {TAB_AUDIO, 56}, {TAB_LINKS, 80},
            {TAB_COMMON_GROUPS, 60}, {TAB_GROUPUSERS, 58}, {TAB_RECOMMENDED_CHANNELS, 60}, {TAB_SAVED_DIALOGS, 72}};
        for (float d : new float[]{1f, 1.5f, 2f, 2.75f, 3.5f}) {
            density = d;
            for (int[] row : rows) {
                int type = row[0]; host.mediaPage.selectedType = type;
                for (int count : new int[]{-1, 0, 1, 2, 3, 8, 9, 1000, Integer.MAX_VALUE}) {
                    Arrays.fill(host.hasMedia, count);
                    host.chatUsersAdapter.chatInfo = new ChatInfo();
                    host.chatUsersAdapter.chatInfo.participants_count = count;
                    int expectedRows = type == TAB_SAVED_DIALOGS || type == TAB_RECOMMENDED_CHANNELS
                        ? 3 : Math.min(8, count > 0 ? count : 3);
                    check(host.getProfileLoadingRows(type) == expectedRows, "bounded/unknown count " + type);
                    int rowHeight = dp(row[1]) + (type == TAB_COMMON_GROUPS || type == TAB_RECOMMENDED_CHANNELS ? 1 : 0);
                    if (type == TAB_SAVED_DIALOGS) rowHeight = dp(row[1] + 1);
                    check(view.rowHeight(dp(320)) == rowHeight, "native row height " + type);
                    for (int width : new int[]{220, 360, 800}) {
                        for (int height : new int[]{0, 30, 100, 400, 1200}) {
                            view.onMeasure(MeasureSpec.makeMeasureSpec(dp(width), MeasureSpec.EXACTLY),
                                MeasureSpec.makeMeasureSpec(dp(height), MeasureSpec.EXACTLY));
                            check(view.isSingleCell && view.itemsCount == expectedRows, "finite row drawing");
                            check(view.height == Math.min(dp(height), rowHeight * expectedRows), "no page-height stretch " + type);
                            check(view.width == dp(width), "width preserved");
                            checks++;
                        }
                    }
                }
            }
            host.chatUsersAdapter.chatInfo = null;
            check(host.getProfileLoadingRows(TAB_GROUPUSERS) == 3, "unknown participants");
            for (int type : new int[]{TAB_PHOTOVIDEO, TAB_GIF, TAB_STORIES, TAB_ARCHIVED_STORIES, 65537, TAB_GIFTS, TAB_BOT_PREVIEWS}) {
                host.mediaPage.selectedType = type;
                view.onMeasure(MeasureSpec.makeMeasureSpec(dp(360), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(dp(800), MeasureSpec.EXACTLY));
                check(!view.isSingleCell && view.height == dp(800), "grid reset after list " + type);
            }
            SharedConfig.useThreeLinesLayout = true;
            host.mediaPage.selectedType = TAB_SAVED_DIALOGS;
            check(view.rowHeight(dp(360)) == dp(79), "three-line saved dialog layout");
            SharedConfig.useThreeLinesLayout = false;
        }
        System.out.println("PASS: " + checks + " measured profile skeleton layouts; list/grid switching and density/viewport bounds");
    }
}`;
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-profile-loading-'));
try {
    const file = path.join(tmp, 'ProfileLoadingRowsHarness.java');
    fs.writeFileSync(file, harness);
    cp.execFileSync('javac', ['-d', tmp, file], { stdio: 'inherit', timeout: 30000 });
    cp.execFileSync('java', ['-cp', tmp, 'ProfileLoadingRowsHarness'], { stdio: 'inherit', timeout: 30000 });
    fs.writeFileSync(file, harness.replace('setIsSingleCell(rows > 0);', 'setIsSingleCell(false);'));
    cp.execFileSync('javac', ['-d', tmp, file], { stdio: 'inherit', timeout: 30000 });
    const negative = cp.spawnSync('java', ['-cp', tmp, 'ProfileLoadingRowsHarness'], { encoding: 'utf8', timeout: 30000 });
    assert.notEqual(negative.status, 0, 'old full-page skeleton mode must fail');
    assert.match(negative.stderr, /finite row drawing|no page-height stretch/);
} finally {
    fs.rmSync(tmp, { recursive: true, force: true });
}
console.log('PASS: adapter batches, native audio/user templates, repeat-load and stale-tab animation guards');
