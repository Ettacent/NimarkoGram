const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const cp = require('node:child_process');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../main/java/org/telegram/ui');
const tabs = fs.readFileSync(path.join(root, 'Components/FilterTabsView.java'), 'utf8');
const dialogs = fs.readFileSync(path.join(root, 'DialogsActivity.java'), 'utf8');
function method(source, signature) {
    const start = source.indexOf(signature);
    assert(start >= 0, signature);
    const body = source.indexOf('{', start);
    let depth = 1;
    for (let i = body + 1; i < source.length; i++) {
        if (source[i] === '{') depth++;
        if (source[i] === '}' && --depth === 0) return source.slice(start, i + 1);
    }
    throw new Error(`Unclosed method: ${signature}`);
}
const java = `import java.util.*;
public class FolderSelection {
    ${method(dialogs, 'private static float getFolderSwipeProgress(')}
    static int checks;
    static void check(boolean condition) {
        checks++;
        if (!condition) throw new AssertionError("check " + checks);
    }
    static class IntMap extends HashMap<Integer, Integer> {
        int get(int key, int fallback) { return getOrDefault(key, fallback); }
        int get(int key) { return get(key, 0); }
    }
    static class ListView { void invalidateViews() {} void invalidate() {} int getScrollState(){return 0;} }
    static class MotionEvent {float x;MotionEvent(float x){this.x=x;}float getX(){return x;}}
    static class View {
        static final int VISIBLE=0;
        int selectedType;float translationX;
        void setEnabled(boolean e){}void setVisibility(int v){}void setTranslationX(float x){translationX=x;}
        int getMeasuredWidth(){return 1080;}void requestDisallowInterceptTouchEvent(boolean b){}
    }
    static class Motion {
        static class Tabs extends View {int next=1;int getNextPageId(boolean forward){return next;}}
        Tabs filterTabsView=new Tabs();View actionBar=new View(),parent=new View();
        View[] viewPages={new View(),new View()};
        boolean maybeStartTracking,startedTracking,animatingForward;int startedTrackingX;float additionalOffset;
        View getParent(){return parent;}void showScrollbars(boolean b){}void switchToCurrentSelectedMode(boolean b){}
        ${method(dialogs, 'private boolean prepareForMoving(MotionEvent ev, boolean forward)')}
    }
    static class Tabs {
        IntMap positionToStableId = new IntMap(), positionToId = new IntMap(), idToPosition = new IntMap();
        List<Integer> tabs = new ArrayList<>();
        int currentPosition, selectedTabId, manualScrollingToPosition = -1, manualScrollingToId = -1;
        int scrollingToChild=-1,resizeReferenceWidth;int getWidth(){return 400;}
        boolean animatingIndicator;
        void scrollWithPage(int position,float progress){scrollingToChild=position;}
        float animatingIndicatorProgress;
        ListView listView = new ListView();
        void invalidate() {} void scrollToChild(int position) {scrollingToChild=position;}
        void fill(int... stableIds) {
            tabs.clear(); positionToStableId.clear(); positionToId.clear(); idToPosition.clear();
            for (int i = 0; i < stableIds.length; i++) {
                int id = 10 + i * 7;
                tabs.add(id); positionToStableId.put(i, stableIds[i]);
                positionToId.put(i, id); idToPosition.put(id, i);
            }
        }
        ${method(tabs, 'public int getCurrentTabStableId()')}
        ${method(tabs, 'public int getStableId(int selectedType)')}
        ${method(tabs, 'public boolean selectTabWithStableId(int stableId)')}
        ${method(tabs, 'public void selectTabWithId(int id, float progress)')}
    }
    static class MessagesController {
        static class DialogFilter { int id; DialogFilter(int id) { this.id = id; } }
        DialogFilter[] selectedDialogFilter = new DialogFilter[2];
        int selections, updates;
        void selectDialogFilter(DialogFilter filter, int index) { selectedDialogFilter[index] = filter; selections++; }
        void updateFilterDialogs(DialogFilter filter) { updates++; }
    }
    static class Dialogs {
        static final int DIALOGS_TYPE_FORWARD = 3, DIALOGS_TYPE_FOLDER1 = 7, DIALOGS_TYPE_FOLDER2 = 8;
        boolean onlySelect;
        int initialDialogsType;
        MessagesController controller;
        MessagesController.DialogFilter[] localSelectedDialogFilters = new MessagesController.DialogFilter[2];
        Dialogs(MessagesController controller) { this.controller = controller; }
        MessagesController getMessagesController() { return controller; }
        ${method(dialogs, 'private boolean usesLocalDialogFilters()')}
        ${method(dialogs, 'public MessagesController.DialogFilter getSelectedDialogFilter(int dialogsType)')}
        ${method(dialogs, 'private void selectDialogFilter(MessagesController.DialogFilter filter, int index)')}
    }
    public static void main(String[] args) {
        for(int origin:new int[]{0,120,600,1000})for(int offset:new int[]{-300,0,300})for(int dx:new int[]{-60,-8,8,60}){
            Motion m=new Motion();m.startedTracking=true;m.startedTrackingX=origin;m.additionalOffset=offset;
            MotionEvent event=new MotionEvent(origin-offset+dx);
            check(m.prepareForMoving(event,dx<0));
            check((int)(event.getX()-m.startedTrackingX+m.additionalOffset)==dx);
            check((int)(event.getX()+2-m.startedTrackingX+m.additionalOffset)==dx+2);
            check(m.animatingForward==(dx<0));
        }
        Motion fresh=new Motion();fresh.additionalOffset=35;fresh.prepareForMoving(new MotionEvent(600),true);
        check(fresh.startedTrackingX==635&&fresh.startedTracking);
        Motion edge=new Motion();edge.startedTracking=true;edge.startedTrackingX=100;edge.filterTabsView.next=-1;
        check(!edge.prepareForMoving(new MotionEvent(115),false)&&edge.startedTrackingX==100);
        Tabs t = new Tabs();
        t.fill(42, 0, 93, 71);
        for (int from = 0; from < 4; from++) for (int to = 0; to < 4; to++) {
            t.selectTabWithStableId(t.getStableId(from));
            int id = t.positionToId.get(to), oldId = t.selectedTabId;
            for (int p = 0; p < 100; p++) {
                t.selectTabWithId(id, p / 100f);
                check(t.currentPosition == from && t.selectedTabId == oldId);
                check(p == 0 ? t.manualScrollingToPosition == -1 : t.manualScrollingToPosition == to);
            }
            t.selectTabWithId(id, 0);
            check(t.currentPosition == from && t.manualScrollingToId == -1);
            t.selectTabWithId(id, -1);
            check(t.currentPosition == from && t.animatingIndicatorProgress == 0);
            t.selectTabWithId(id, 1);
            check(t.currentPosition == to && t.selectedTabId == id && t.manualScrollingToPosition == -1);
            t.selectTabWithId(id, 2);
            check(t.currentPosition == to && t.animatingIndicatorProgress == 1);
            t.selectTabWithId(9999, 1);
            check(t.currentPosition == to && t.selectedTabId == id);
        }
        t.selectTabWithStableId(93);
        Tabs gesture = new Tabs();
        gesture.fill(11, 22, 33);
        for (int width : new int[]{320, 480, 1080, 1600}) for (int target : new int[]{0, 2}) {
            gesture.selectTabWithStableId(22);
            int id = gesture.positionToId.get(target);
            for (float distance : new float[]{0, .4f, .95f, 1, 1.6f, 1, .2f, 0}) {
                gesture.selectTabWithId(id, getFolderSwipeProgress((target == 0 ? 1 : -1) * width * distance, width));
                check(gesture.getCurrentTabStableId() == 22);
            }
            gesture.selectTabWithId(id, 1);
            check(gesture.currentPosition == target);
        }
        check(getFolderSwipeProgress(100, 0) == 0);
        int stable = t.getCurrentTabStableId();
        t.fill(71, 93, 42, 0);
        check(t.selectTabWithStableId(stable));
        check(t.currentPosition == 1 && t.getCurrentTabStableId() == 93 && t.selectedTabId == 17);
        check(!t.selectTabWithStableId(9999) && t.getCurrentTabStableId() == 93);
        MessagesController c = new MessagesController();
        MessagesController.DialogFilter a = new MessagesController.DialogFilter(42), b = new MessagesController.DialogFilter(93);
        c.selectedDialogFilter[0] = a; c.selectedDialogFilter[1] = b;
        Dialogs picker = new Dialogs(c);
        picker.onlySelect = true; picker.initialDialogsType = Dialogs.DIALOGS_TYPE_FORWARD;
        picker.selectDialogFilter(b, 0);
        picker.selectDialogFilter(a, 1);
        check(picker.getSelectedDialogFilter(7) == b && picker.getSelectedDialogFilter(8) == a);
        picker.selectDialogFilter(new MessagesController.DialogFilter(93), 1);
        check(picker.getSelectedDialogFilter(7) == null && picker.getSelectedDialogFilter(8).id == 93);
        picker.selectDialogFilter(null, 1);
        picker.selectDialogFilter(a, -1); picker.selectDialogFilter(a, 2);
        check(picker.getSelectedDialogFilter(8) == null && picker.getSelectedDialogFilter(0) == null);
        check(c.selectedDialogFilter[0] == a && c.selectedDialogFilter[1] == b && c.selections == 0 && c.updates == 3);
        Dialogs main = new Dialogs(c);
        main.selectDialogFilter(b, 0);
        check(c.selections == 1 && main.getSelectedDialogFilter(7) == b && main.localSelectedDialogFilters[0] == null);
        main.onlySelect = true;
        main.selectDialogFilter(a, 0);
        check(c.selections == 2 && c.selectedDialogFilter[0] == a);
        System.out.println("PASS: " + checks + " checks: tab progress/cancel/commit, stable IDs and forwarding selection isolation");
    }
}`;
const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-folder-selection-'));
try {
    const compile = source => {
        fs.writeFileSync(path.join(directory, 'FolderSelection.java'), source);
        cp.execFileSync('javac', ['FolderSelection.java'], { cwd: directory, stdio: 'inherit', timeout: 30000 });
    };
    compile(java);
    cp.execFileSync('java', ['FolderSelection'], { cwd: directory, stdio: 'inherit', timeout: 30000 });
    for (const [before, after] of [
        ['if (!startedTracking) {', 'if (true) {'],
        ['Math.min(.999f, Math.abs(offset) / width)', 'Math.min(1f, Math.abs(offset) / width)'],
        ['if (progress >= 1.0f)', 'if (progress > 0)'],
        ['return onlySelect && initialDialogsType == DIALOGS_TYPE_FORWARD;', 'return false;'],
    ]) {
        const broken = java.replace(before, after);
        assert.notEqual(broken, java);
        compile(broken);
        const result = cp.spawnSync('java', ['FolderSelection'], { cwd: directory, encoding: 'utf8', timeout: 30000 });
        assert.ifError(result.error);
        assert.notEqual(result.status, 0);
        assert(result.stderr.includes('AssertionError'));
    }
    console.log('PASS: early tab commit and shared picker state are rejected by negative controls');
} finally {
    fs.rmSync(directory, { recursive: true, force: true });
}
