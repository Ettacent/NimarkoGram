const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const cp = require('node:child_process');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../main/java');
const read = file => fs.readFileSync(path.join(root, file), 'utf8');
const popup = read('org/telegram/ui/ActionBar/ActionBarPopupWindow.java');
const signature = 'public boolean containsVisiblePoint(float x, float y)';
const start = popup.indexOf(signature);
assert(start >= 0);
const end = popup.indexOf('\n        }', start) + '\n        }'.length;
const method = popup.slice(start, end);
assert(popup.includes('content.containsVisiblePoint(e.getX() - contentView.getX(), e.getY() - contentView.getY())'));
const header = read('org/telegram/ui/ActionBar/ActionBarMenuItem.java');
assert(header.includes('popupLayout.containsVisiblePoint(event.getX(), event.getY())'));
const injector = read('app/nimarkogram/messenger/utils/chats/NimarkoChatMenuInjector.java');
assert(injector.includes('ItemOptions.swipeback(headerItem.getPopupLayout(), chatActivity.getResourceProvider())'));
assert(injector.includes('title, content)'));
assert(injector.includes('content.addView(options.getLinearLayout(), new ScrollView.LayoutParams('));
assert(injector.includes('options.getLinearLayout().getChildCount() > 2'));
const dialogs = read('org/telegram/ui/DialogsActivity.java');
const options = dialogs.slice(dialogs.indexOf('private void showItemOptions()'), dialogs.indexOf('public boolean isSupportEdgeToEdge()', dialogs.indexOf('private void showItemOptions()')));
assert(!options.includes('setTranslationY(-dp(64))'));
assert(!options.includes('Theme.key_actionBarDefaultTitle'));
assert(options.includes('io.setGravity(Gravity.RIGHT)'));
assert(options.includes('io.setSwipebackGravity(true, false)'));
assert.equal(options.split('io.getLast().setRightIcon(R.drawable.msg_arrowright)').length - 1, 1);

const java = `public class PopupBoundsTest {
 static int checks;
 int width, height; float backScaleX, backScaleY; boolean shownFromBottom;
 static class Swipe { boolean stickToRight; }
 Swipe swipeBackLayout = new Swipe();
 int getMeasuredWidth() { return width; } int getMeasuredHeight() { return height; }
 ${method}
 static void check(boolean result, String why) { checks++; if (!result) throw new AssertionError(why); }
 public static void main(String[] args) {
  PopupBoundsTest p = new PopupBoundsTest();
  p.width=280; p.height=600; p.backScaleX=1; p.backScaleY=.2f;
  check(!p.containsVisiblePoint(80, 300), "invisible reserved height must dismiss");
  check(p.containsVisiblePoint(80, 20), "first segment above a gap remains clickable");
  check(p.containsVisiblePoint(80, 100), "last segment below a gap remains clickable");
  for (int w : new int[]{196,240,320,560}) for (int h : new int[]{96,256,500,900})
  for (int right=0;right<2;right++) for(int bottom=0;bottom<2;bottom++)
  for (int frame=0;frame<=20;frame++) {
   p.width=w; p.height=h; p.shownFromBottom=bottom!=0; p.swipeBackLayout.stickToRight=right!=0;
   float progress=frame/20f;
   p.backScaleX=.6f+.4f*progress; p.backScaleY=.2f+.8f*progress;
   float vw=w*p.backScaleX, vh=h*p.backScaleY;
   float left=right!=0&&bottom==0?w-vw:0, top=bottom!=0?h-vh:0;
   float cx=left+vw/2, cy=top+vh/2;
   check(p.containsVisiblePoint(cx,cy), "visible center during resize");
   check(p.containsVisiblePoint(left+.01f,top+.01f), "visible leading corner");
   check(p.containsVisiblePoint(left+vw-.01f,top+vh-.01f), "visible trailing corner");
   check(!p.containsVisiblePoint(left-.01f,cy), "outside left");
   check(!p.containsVisiblePoint(left+vw+.01f,cy), "outside right");
   check(!p.containsVisiblePoint(cx,top-.01f), "outside top");
   check(!p.containsVisiblePoint(cx,top+vh+.01f), "outside bottom");
   for(float tx : new float[]{-48,0,128}) for(float ty : new float[]{-64,0,180}) {
    float eventX=cx+tx,eventY=cy+ty;
    check(p.containsVisiblePoint(eventX-tx,eventY-ty), "window-to-content translation");
    check(!p.containsVisiblePoint(eventX-tx,top+vh+1), "translated blank height");
   }
  }
  p.backScaleX=0; p.backScaleY=0;
  check(!p.containsVisiblePoint(0,0), "collapsed page has no touch area");
  System.out.println("PASS: " + checks + " actual popup bounds checks, both anchors, size transitions and gaps");
 }
}`;
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-popup-bounds-'));
try {
    const file = path.join(dir, 'PopupBoundsTest.java');
    const run = code => {
        fs.writeFileSync(file, code);
        const compile = cp.spawnSync('javac', ['-d', dir, file], {encoding:'utf8'});
        assert.equal(compile.status, 0, compile.stderr);
        return cp.spawnSync('java', ['-cp', dir, 'PopupBoundsTest'], {encoding:'utf8'});
    };
    const result = run(java);
    assert.equal(result.status, 0, result.stderr);
    console.log(result.stdout.trim());
    for (const [body, reason] of [
        ['return x >= 0 && x < getMeasuredWidth() && y >= 0 && y < getMeasuredHeight();', 'invisible reserved height'],
        ['return x >= 0 && x < getMeasuredWidth() && y >= 80 && y < 120;', 'first segment above a gap'],
    ]) {
        const broken = run(java.replace(method, signature + '{' + body + '}'));
        assert.notEqual(broken.status, 0);
        assert(broken.stderr.includes(reason), broken.stderr);
    }
    console.log('PASS: both old bounds errors fail negative controls; native submenu/anchor/style contracts pass. Device rendering not tested.');
} finally {
    fs.rmSync(dir, {recursive:true, force:true});
}
