const fs = require('node:fs'), path = require('node:path'), os = require('node:os');
const cp = require('node:child_process'), assert = require('node:assert/strict');
const read = name => fs.readFileSync(path.resolve(__dirname, '../../main/java/app/nimarkogram/messenger/updater', name), 'utf8');
const updater = read('NimarkoUpdater.java'), sheet = read('NimarkoUpdaterSheet.java');
function extract(source, signature) {
    const start = source.indexOf(signature);
    assert(start >= 0, signature);
    const tokens = /\/\/[^\n]*|\/\*[\s\S]*?\*\/|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[{}]/g;
    tokens.lastIndex = source.indexOf('{', start);
    let depth = 0;
    for (let t; (t = tokens.exec(source));) {
        if (t[0] === '{') depth++;
        if (t[0] === '}' && --depth === 0) return source.slice(start, tokens.lastIndex);
    }
    throw Error(signature);
}
const methods = ['public static boolean isUpdateDownloaded()', 'public static Update getOrRestoreLastUpdate()',
    'public static DownloadUiState getDownloadUiState()', 'public static void cleanOtaDir()',
    'public static final class DownloadUiState', 'public static class Update']
    .map(s => extract(updater, s)).join('\n');
const java = `
class File {boolean present=true;File(){}File(File parent,String child){}boolean isFile(){return present;}}
class Context {File external=new File();File getExternalFilesDir(Object ignored){return external;}}
class ApplicationLoader {static Context applicationContext=new Context();}
class BaseFragment {Object getParentActivity(){return this;}Context getContext(){return ApplicationLoader.applicationContext;}}
class NimarkoUpdateConfig {
 static int code;static boolean available,inProgress;static float progress;static String hash="digest";
 static void setUpdateAvailable(boolean v){available=v;}
 static void setUpdateIsDownloading(boolean v){inProgress=v;}
 static void setUpdateDownloadingProgress(float v){progress=v;}
 static float getUpdateDownloadingProgress(){return progress;}
 static void setApkSha256(String v){hash=v;}
 static void clearPausedDownload(){}
 static String getLastUpdateVersion(){return "12.10.1";}
 static int getLastUpdateVersionCode(){return code;}
 static String getLastUpdateUrl(){return "https://example.org/update.apk";}
 static String getLastUpdateChangelog(){return "Changes";}
 static String getLastUpdateSize(){return "1 MB";}
 static String getLastUpdateDate(){return "Today";}
}
class NimarkoUpdater {
 static Object downloadBindingLock=new Object(),validatedApk;
 static File apkFile;static Update lastUpdate;static int installed=100,dlShownProgress,dlRealProgress,deleted,cancelled;
 static boolean updateDownloaded,downloading,downloadPaused,persisted;
 static boolean hasPersistedDownloadLocked(){return persisted;}
 static int getCurrentVersionCode(){return installed;}
 static void cleanFolder(File f){deleted++;if(apkFile!=null)apkFile.present=false;}
 static void cancelUpdateNotification(){cancelled++;}
 ${methods}
}
public class UpdaterInstalledStateTest {
 static int checks;static boolean shownAvailable;
 static void check(boolean value,String reason){checks++;if(!value)throw new AssertionError(reason);}
 static void showPreparedAlert(BaseFragment f,boolean available,NimarkoUpdater.Update update){shownAvailable=available;}
 ${extract(sheet, 'public static void showAlert(')}
 static NimarkoUpdater.Update update(int code){return new NimarkoUpdater.Update("12.10.1",code,"Changes","1 MB","https://example.org/update.apk","");}
 static void setup(int target){
  NimarkoUpdater.lastUpdate=update(target);NimarkoUpdateConfig.code=target;
  NimarkoUpdater.apkFile=new File();NimarkoUpdater.updateDownloaded=true;
  NimarkoUpdater.downloading=NimarkoUpdater.downloadPaused=NimarkoUpdater.persisted=false;
  NimarkoUpdater.validatedApk=new Object();NimarkoUpdater.dlRealProgress=NimarkoUpdater.dlShownProgress=100;
  NimarkoUpdateConfig.progress=100;NimarkoUpdater.deleted=NimarkoUpdater.cancelled=0;
 }
 public static void main(String[] args){
  BaseFragment f=new BaseFragment();
  for(int target:new int[]{99,100,101})for(boolean cold:new boolean[]{false,true}){
   setup(target);if(cold)NimarkoUpdater.lastUpdate=null;
   boolean newer=target>100;
   check((NimarkoUpdater.getOrRestoreLastUpdate()!=null)==newer,"restored metadata must be newer");
   check(NimarkoUpdater.isUpdateDownloaded()==newer,"ready APK must be newer");
   check(NimarkoUpdater.getDownloadUiState().finished==newer,"UI must not trust stale completion flag");
   showAlert(f,false,null);check(shownAvailable==newer,"settings sheet cannot resurrect installed update");
   showAlert(f,true,update(target));check(shownAvailable==newer,"explicit update link checks installed version");
  }
  setup(101);NimarkoUpdater.apkFile.present=false;
  check(!NimarkoUpdater.getDownloadUiState().finished,"deleted file cannot remain ready");
  setup(101);NimarkoUpdater.cleanOtaDir();
  check(!NimarkoUpdater.updateDownloaded&&NimarkoUpdater.apkFile==null&&NimarkoUpdater.validatedApk==null,"cleanup clears ready file and validation cache");
  check(NimarkoUpdater.dlRealProgress==0&&NimarkoUpdater.dlShownProgress==0&&NimarkoUpdateConfig.progress==0,"cleanup resets progress");
  check(NimarkoUpdater.cancelled==1&&NimarkoUpdater.deleted==1,"cleanup cancels obsolete notification");
  check(!NimarkoUpdater.getDownloadUiState().finished,"cleanup immediately reflected in UI");
  showAlert(f,false,null);check(!shownAvailable,"cleanup cannot reopen completed download");
  for(int state=0;state<3;state++){
   setup(101);NimarkoUpdater.downloading=state==0;NimarkoUpdater.downloadPaused=state==1;NimarkoUpdater.persisted=state==2;
   NimarkoUpdater.cleanOtaDir();
   check(NimarkoUpdater.deleted==0&&NimarkoUpdater.apkFile!=null,"cleanup must not delete active or resumable download");
   showAlert(f,false,null);check(shownAvailable,"active newer download still reopens");
  }
  setup(100);NimarkoUpdater.updateDownloaded=false;
  showAlert(f,true,update(100));check(!shownAvailable,"explicit obsolete sheet blocked without download state");
  System.out.println("PASS: "+checks+" actual updater state checks, warm/cold metadata, cleanup, same versionName, active downloads");
 }
}`;
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-updater-state-'));
try {
    function run(code) {
        fs.writeFileSync(path.join(dir, 'UpdaterInstalledStateTest.java'), code);
        cp.execFileSync('javac', ['UpdaterInstalledStateTest.java'], {cwd:dir});
        return cp.spawnSync('java', ['UpdaterInstalledStateTest'], {cwd:dir,encoding:'utf8'});
    }
    const result = run(java);assert.equal(result.status,0,result.stderr);process.stdout.write(result.stdout);
    for (const [before,after] of [
        ['lastUpdate.isNew() ? lastUpdate : null','lastUpdate'],
        ['new DownloadUiState(downloading, paused, isUpdateDownloaded(),','new DownloadUiState(downloading, paused, updateDownloaded,'],
        ['updateDownloaded = false;','updateDownloaded = true;'],
        ['available && update != null && update.isNew()','available'],
    ]) {
        const broken=java.replaceAll(before,after);assert.notEqual(broken,java);
        const failed=run(broken);assert.equal(failed.status,1,failed.stderr);assert.match(failed.stderr,/AssertionError/);
    }
    console.log('PASS: stale metadata, raw completion flag, incomplete cleanup and obsolete deep-link negative controls rejected');
} finally {fs.rmSync(dir,{recursive:true,force:true});}
