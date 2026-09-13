const fs = require('node:fs'), path = require('node:path'), os = require('node:os');
const cp = require('node:child_process'), assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../main/java');
const adapter = fs.readFileSync(path.join(root, 'org/telegram/ui/Adapters/DialogsAdapter.java'), 'utf8');
const cell = fs.readFileSync(path.join(root, 'org/telegram/ui/Cells/DialogCell.java'), 'utf8');
function extract(source, signature) {
    const start = source.indexOf(signature);
    assert(start >= 0, signature);
    let depth = 0;
    for (let i = source.indexOf('{', start); i < source.length; i++) {
        if (source[i] === '{') depth++;
        if (source[i] === '}' && --depth === 0) return source.slice(start, i + 1);
    }
    throw new Error(signature);
}
const sumStart = adapter.indexOf('int cellHeight =', adapter.indexOf('public class LastEmptyView'));
const sum = adapter.slice(sumStart, adapter.indexOf('int archiveHeight =', sumStart));
const java = `import java.util.*;
public class FolderRowHeightTest {
 static float density; static int checks;
 static void check(boolean b, String s) { checks++; if (!b) throw new AssertionError(s); }
 static int dp(float n) { return (int)Math.ceil(n * density); }
 static class AndroidUtilities { static int dp(float n) { return FolderRowHeightTest.dp(n); } }
 static class SharedConfig { static boolean useThreeLinesLayout; }
 static final int VIEW_TYPE_DIALOG=1, VIEW_TYPE_FLICKER=2, VIEW_TYPE_LAST_EMPTY=3;
 static class Item { int viewType; boolean isForumCell; Item(int t, boolean f) {viewType=t; isForumCell=f;} }
 static class Cell {
  boolean forum, collapsed, useSeparator, isTransitionSupport, useForceThreeLines, twoLinesForName;
  int heightThreeLines=76, heightDefault=70, addForumHeightForTags=9, addHeightForTags=3;
  boolean isForumCell() { return forum; } boolean hasTags() { return false; }
  ${extract(cell, 'private int computeHeight()')}
  ${extract(cell, 'private int getCollapsedHeight()')}
 }
 ArrayList<Item> itemInternals = new ArrayList<>(); ArrayList<Object> onlineContacts;
 boolean collapsedView;
 ${extract(adapter, 'public int getItemHeight(')}
 int measuredContent() {
  int size=itemInternals.size();
  ${sum}
  return dialogsHeight;
 }
 public static void main(String[] args) {
  for (float d : new float[]{1,1.5f,2.625f,3,4}) for (boolean three : new boolean[]{false,true})
   for (boolean collapsed : new boolean[]{false,true}) for (int forums=0; forums<=5; forums++) {
    density=d; SharedConfig.useThreeLinesLayout=three;
    FolderRowHeightTest a=new FolderRowHeightTest(); a.collapsedView=collapsed;
    int actual=0;
    for (int i=0;i<5;i++) {
     Cell c=new Cell(); c.forum=i<forums; c.collapsed=collapsed;
     a.itemInternals.add(new Item(VIEW_TYPE_DIALOG,c.forum));
     actual+=c.computeHeight();
     check(a.getItemHeight(i)==c.computeHeight(), "scroll limit matches actual row, including forum without divider");
    }
    a.itemInternals.add(new Item(VIEW_TYPE_LAST_EMPTY,false));
    check(a.measuredContent()==actual, "filler does not steal pixels for nonexistent forum dividers");
    if (forums==5 && !collapsed) {
     int oldEstimate=5*dp(three?86:91)+5;
     check(oldEstimate-actual==5, "old formula reproduces a five-pixel gap");
    }
   }
  System.out.println("PASS: " + checks + " folder row/filler geometry assertions");
 }
}`;
assert.match(adapter, /cell.useSeparator = false;/);
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-folder-row-height-'));
try {
    fs.writeFileSync(path.join(dir, 'FolderRowHeightTest.java'), java);
    cp.execFileSync('javac', ['FolderRowHeightTest.java'], {cwd: dir});
    process.stdout.write(cp.execFileSync('java', ['FolderRowHeightTest'], {cwd: dir}));
} finally { fs.rmSync(dir, {recursive: true, force: true}); }
