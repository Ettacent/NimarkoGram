const fs=require('node:fs'),path=require('node:path'),os=require('node:os'),cp=require('node:child_process'),assert=require('node:assert/strict');
const src=fs.readFileSync(path.resolve(__dirname,'../../main/java/org/telegram/ui/ProfileActivity.java'),'utf8');
const start=src.indexOf('    private void updateProfileNotificationControls(');
const end=src.indexOf('\n    public void resetMainTabScroll()',start);
assert(start>=0&&end>start);
const method=src.slice(start,end).replaceAll('android.view.ViewParent','ViewParent');
const java=`
interface ViewParent {ViewParent getParent();}
class View implements ViewParent {ViewParent parent;float y;public ViewParent getParent(){return parent;}float getY(){return y;}int getScrollY(){return 0;}}
class Media extends View {float offset=99;void setNotificationControlsOffset(float v){offset=v;}float getNotificationTabsVisibleTop(){return 7;}}
class Panel extends View {float height=80;float getAnimatedHeightWithPadding(){return height;}int getPaddingBottom(){return 4;}float getLayoutVisibility(){return 1;}}
public class DestroyTest {
 View fragmentView;Media sharedMediaLayout;Panel notificationInlinePanel;boolean profileLifecycleDestroyed;
 int dp(int v){return v;}
 ${method}
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  DestroyTest t=new DestroyTest();View host=new View();
  t.sharedMediaLayout=new Media();t.sharedMediaLayout.parent=host;
  t.updateProfileNotificationControls(-1);check(t.sharedMediaLayout.offset==0);
  t.updateProfileNotificationControls(80);check(t.sharedMediaLayout.offset==0);
  t.fragmentView=new View();t.fragmentView.parent=host;
  t.updateProfileNotificationControls(80);check(t.sharedMediaLayout.offset==0);
  t.notificationInlinePanel=new Panel();t.notificationInlinePanel.parent=host;
  t.updateProfileNotificationControls(80);check(t.sharedMediaLayout.offset==157);
  t.updateProfileNotificationControls(-1);check(t.sharedMediaLayout.offset==0);
  t.profileLifecycleDestroyed=true;t.updateProfileNotificationControls(80);check(t.sharedMediaLayout.offset==0);
  t.profileLifecycleDestroyed=false;t.fragmentView.parent=null;t.updateProfileNotificationControls(80);check(t.sharedMediaLayout.offset==0);
  t.fragmentView.parent=host;t.notificationInlinePanel.parent=new View();t.updateProfileNotificationControls(80);check(t.sharedMediaLayout.offset==0);
  t.notificationInlinePanel.parent=host;t.sharedMediaLayout.parent=new View();t.updateProfileNotificationControls(80);check(t.sharedMediaLayout.offset==0);
  t.sharedMediaLayout=null;t.updateProfileNotificationControls(-1);
  System.out.println("PASS: destroyed/null/detached profile, release, stale host callbacks and live geometry");
 }
}`;
const dir=fs.mkdtempSync(path.join(os.tmpdir(),'profile-destroy-'));
function run(code){fs.writeFileSync(path.join(dir,'DestroyTest.java'),code);cp.execFileSync('javac',['DestroyTest.java'],{cwd:dir});return cp.spawnSync('java',['DestroyTest'],{cwd:dir,encoding:'utf8'});}
try {
 const ok=run(java);assert.equal(ok.status,0,ok.stderr);process.stdout.write(ok.stdout);
 const unsafe=method.replace(/            if \(notificationTop < 0[\s\S]*?                return;\n            }\n/,'');
 assert.notEqual(unsafe,method);
 const bad=run(java.replace(method,unsafe));
 assert.notEqual(bad.status,0);assert.match(bad.stderr,/NullPointerException/);
 console.log('PASS: removing the lifecycle guard reproduces the reported null-view crash');
} finally {fs.rmSync(dir,{recursive:true,force:true});}
