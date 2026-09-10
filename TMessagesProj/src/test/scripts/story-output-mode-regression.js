const fs = require('node:fs'), cp = require('node:child_process');
const path = require('node:path'), os = require('node:os'), assert = require('node:assert/strict');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/java/org/telegram/ui/Stories/StoryViewer.java'), 'utf8');
const open = source.slice(source.indexOf('BaseFragment fragment = LaunchActivity.getLastFragment();'));
const expression = open.match(/ATTACH_TO_FRAGMENT = ([\s\S]*?);/)[1];
const attach = new Function('AndroidUtilities', 'fromBottomSheet', 'fragment', 'return ' + expression);
let cases = 0;
for (const tablet of [false, true]) for (const sheet of [false, true])
for (const exists of [false, true]) for (const layout of [false, true])
for (const edge of [false, true]) for (const preference of [false, true]) {
    const fragment = exists ? {getLayoutContainer: () => layout ? {} : null, isSupportEdgeToEdge: () => edge} : null;
    const actual = attach({isTablet: () => tablet}, sheet, fragment);
    const expected = !tablet && !sheet && exists && layout && !edge;
    assert.equal(actual, expected);
    assert.equal(preference && actual, preference && expected);
    cases++;
}
assert.equal((open.match(/ATTACH_TO_FRAGMENT\s*=/g) || []).length, 1);
assert(open.indexOf('ATTACH_TO_FRAGMENT =') < open.indexOf('USE_SURFACE_VIEW ='));
assert(open.indexOf('USE_SURFACE_VIEW =') < open.indexOf('ensureVideoOutput(context);'));
assert(open.indexOf('ensureVideoOutput(context);') < open.indexOf('storiesViewPager.setDays(storiesList.dialogId, storiesList.getDays(), currentAccount)'));
assert(open.indexOf('ensureVideoOutput(context);') < open.indexOf('storiesViewPager.setPeerIds(peerIds, currentAccount, position)'));
const start = source.indexOf('private void ensureVideoOutput(Context context)');
let end = source.indexOf('{', start) + 1, depth = 1;
for (; depth; end++) { if (source[end] === '{') depth++; if (source[end] === '}') depth--; }
const method = source.slice(start, end);
const harness = `import java.util.*;
public class StoryOutputHarness {
 static class Context {}
 static class View { Frame parent; void invalidate() {} }
 static class Frame extends View { List<View> children=new ArrayList<>();
  void addView(View v,int i){if(v.parent!=null)throw new AssertionError();children.add(i,v);v.parent=this;} }
 static class SurfaceView extends View { SurfaceView(Context c){} void setZOrderMediaOverlay(boolean b){}
  void setZOrderOnTop(boolean b){} }
 static class TextureView extends View {}
 static class HwTextureView extends TextureView { HwTextureView(Context c){} }
 static class AndroidUtilities { static void removeFromParent(View v){if(v.parent!=null){v.parent.children.remove(v);v.parent=null;}} }
 boolean USE_SURFACE_VIEW; SurfaceView surfaceView; TextureView textureView;
 Frame aspectRatioFrameLayout=new Frame(); View currentPlayerScope=new View();
 ${method}
 public static void main(String[] args) {
  StoryOutputHarness h=new StoryOutputHarness(); Context c=new Context(); View live=new View();
  h.aspectRatioFrameLayout.addView(live,0);
  for(int i=0;i<100;i++) {
   h.USE_SURFACE_VIEW=i%2==1;
   View old=h.surfaceView!=null?h.surfaceView:h.textureView;
   h.ensureVideoOutput(c);
   View now=h.USE_SURFACE_VIEW?h.surfaceView:h.textureView;
   if(now==null || h.aspectRatioFrameLayout.children.size()!=2 || h.aspectRatioFrameLayout.children.get(0)!=now
    || h.aspectRatioFrameLayout.children.get(1)!=live || (old!=null&&old.parent!=null))throw new AssertionError();
   h.ensureVideoOutput(c);
   if(now!=(h.USE_SURFACE_VIEW?h.surfaceView:h.textureView) || h.aspectRatioFrameLayout.children.size()!=2)throw new AssertionError();
   if(h.USE_SURFACE_VIEW ? h.textureView!=null : h.surfaceView!=null)throw new AssertionError();
  }
  System.out.println("PASS: actual output helper: repeated opens, 100 mode switches, one output, live layer preserved");
 }
}`;
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'story-output-test-'));
try {
    const java = path.join(tmp, 'StoryOutputHarness.java');
    fs.writeFileSync(java, harness);
    cp.execFileSync('javac', [java], {stdio:'inherit'});
    cp.execFileSync('java', ['-cp', tmp, 'StoryOutputHarness'], {stdio:'inherit'});
} finally { fs.rmSync(tmp, {recursive:true, force:true}); }
console.log('PASS: ' + cases + ' host/preference combinations; output selected before player/page initialization.');
