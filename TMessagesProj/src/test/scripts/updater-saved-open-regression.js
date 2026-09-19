const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const cp = require('node:child_process');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../main/java');
const updater = fs.readFileSync(path.join(root, 'app/nimarkogram/messenger/updater/NimarkoUpdater.java'), 'utf8');
const chat = fs.readFileSync(path.join(root, 'org/telegram/ui/ChatActivity.java'), 'utf8');
const versionMethod = updater.match(/public static int getCurrentVersionCode\(\) \{[\s\S]*?\n    \}/)[0];
const visible = chat.slice(chat.indexOf('\n    public void onBecomeFullyVisible()'));
const callback = visible.match(/AndroidUtilities\.runOnUIThread\(\(\) -> \{([\s\S]*?)\n            \}, 64\);/)[1];
assert.match(chat, /chatMode != MODE_EDIT_BUSINESS_LINK\s*&& !\(deferSavedMessagesPrefetchUntilVisible && isOwnSavedMessagesChat\(\)\)/);
const java = `
class android {static class content {static class pm {static class PackageInfo {int version=75545;}}}}
class androidx {static class core {static class content {static class pm {static class PackageInfoCompat {
 static long getLongVersionCode(android.content.pm.PackageInfo i){return i.version;}
}}}}}
class PM {int calls;boolean fail;android.content.pm.PackageInfo getPackageInfo(String p,int f){calls++;if(fail)throw new RuntimeException();return new android.content.pm.PackageInfo();}}
class Context {PM pm=new PM();PM getPackageManager(){return pm;}String getPackageName(){return "test";}}
class ApplicationLoader {static Context applicationContext=new Context();}
class FileLog {static void e(Exception e){}}
public class OpenWorkTest {
 static volatile int installedVersionCode;
 ${versionMethod}
 boolean isFinished,paused,isFullyVisible=true,deferSavedMessagesPrefetchUntilVisible=true;
 int preloads,scrollChecks;
 OpenWorkTest getMessagesController(){return this;}
 OpenWorkTest getSavedMessagesController(){return this;}
 void preloadDialogs(boolean cache){preloads++;}
 void checkScrollForLoad(boolean scroll){scrollChecks++;}
 void afterVisible(){${callback}}
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  PM pm=ApplicationLoader.applicationContext.pm;pm.fail=true;
  check(getCurrentVersionCode()==0);pm.fail=false;
  check(getCurrentVersionCode()==75545);
  for(int i=0;i<100;i++)check(getCurrentVersionCode()==75545);
  check(pm.calls==2);
  OpenWorkTest c=new OpenWorkTest();c.paused=true;c.afterVisible();
  check(c.preloads==0&&c.deferSavedMessagesPrefetchUntilVisible);
  c.paused=false;c.isFullyVisible=false;c.afterVisible();
  check(c.preloads==0&&c.deferSavedMessagesPrefetchUntilVisible);
  c.isFullyVisible=true;c.afterVisible();c.afterVisible();
  check(c.preloads==1&&c.scrollChecks==1&&!c.deferSavedMessagesPrefetchUntilVisible);
  OpenWorkTest destroyed=new OpenWorkTest();destroyed.isFinished=true;destroyed.afterVisible();
  check(destroyed.preloads==0);
  System.out.println("PASS: actual Java version cache, lookup failure retry, deferred Saved Messages lifecycle and duplicate callbacks");
 }
}`;
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-open-work-'));
try {
    fs.writeFileSync(path.join(dir, 'OpenWorkTest.java'), java);
    cp.execFileSync('javac', ['OpenWorkTest.java'], {cwd: dir});
    process.stdout.write(cp.execFileSync('java', ['OpenWorkTest'], {cwd: dir}));
} finally {
    fs.rmSync(dir, {recursive: true, force: true});
}
