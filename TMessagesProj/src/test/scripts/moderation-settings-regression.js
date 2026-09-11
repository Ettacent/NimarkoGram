const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const cp = require('node:child_process');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../main');
const read = file => fs.readFileSync(path.join(root, 'java/app/nimarkogram/messenger', file), 'utf8');
const wl = read('wsbypass/preferences/WlPreferencesActivity.java');
const access = read('wsbypass/WlAccess.java');
const bypass = read('wsbypass/preferences/WsBypassPreferencesActivity.java');
const banner = read('preferences/BannerPreferencesActivity.java');
const controller = read('banners/NimarkoBannerController.java');
function method(source, signature) {
    const start = source.indexOf(signature);
    assert(start >= 0, signature);
    let end = source.indexOf('{', start) + 1, depth = 1;
    while (depth && end < source.length) {
        if (source[end] === '{') depth++;
        if (source[end] === '}') depth--;
        end++;
    }
    return source.slice(start, end);
}
assert(!/NM_WL_Send\b/.test(wl));
assert(!wl.includes('NM_WL_Refresh'));
assert(!wl.includes('item.id == STATUS'));
assert(!banner.includes('case ID_STATUS:'));
assert(wl.includes('asRadio(ENABLE, text(R.string.NM_WL_Network))'));
const fill = method(bypass, 'public void fillItems(');
assert(fill.indexOf('asSettingsLink(ID_STATUS') < fill.indexOf('asSettingsLink(ID_WL'));
assert(fill.indexOf('asSettingsLink(ID_WL') < fill.indexOf('asSettingsLink(ID_OPEN_PROXY'));
assert(banner.includes('asSettingsLink(ID_STATUS'));
assert(!banner.includes('asSettingsValue(ID_STATUS'));
assert(!banner.includes('asSettingsLink(ID_REFRESH'));
assert(banner.includes('File.createTempFile('));
assert(banner.includes('if (!ownsSelection(account, owner))'));
assert(banner.includes('ctrl.refreshStatus(false)'));
assert(method(banner, 'public void onPause()').includes('cancelRunOnUIThread(statusPoll)'));
assert(method(controller, 'public void refreshStatus(boolean notify)').includes('settingsRefreshing.add(operationScope)'));
assert(method(controller, 'public void refreshStatus(boolean notify)').includes('responseCode == 429 ? 300_000'));
assert(method(controller, 'public void submitModeration(').includes('moderationSending.remove(operationScope)'));
for (const dir of ['values', 'values-ru', 'values-zh-rCN', 'values-zh-rTW']) {
    for (const [file, keys] of [
        ['wl.xml', ['NM_WL_Details', 'NM_WL_AutoSubmit', 'NM_WL_SharedAccess']],
        ['banner_settings.xml', ['NM_BAN_Attach', 'NM_BAN_SelectHint', 'NM_BAN_AutoStatus', 'NM_BAN_AccountChanged']]
    ]) {
        const xml = fs.readFileSync(path.join(root, 'res', dir, file), 'utf8');
        for (const key of keys) assert(xml.includes(`name="${key}"`), `${dir}/${key}`);
    }
}
const fields = wl.slice(wl.indexOf('    private int account'), wl.indexOf('    @Override public String getTitle'));
const methods = ['public void onResume()', 'public void onPause()', 'public void onFragmentDestroy()',
    'public void onActivityResultFragment(', 'private void refresh(', 'private WlAccess.Callback callback()',
    'private boolean syncAccount()', 'private void onAccessChanged()', 'private boolean canSubmit()',
    'private int statusString()', 'private boolean shouldPoll()', 'private void schedulePoll()'].map(signature => method(wl, signature)).join('\n');
assert(method(wl, 'public void fillItems(').includes('if (canSubmit())'));
assert(method(wl, 'public void onClick(').includes('item.id == PICK && canSubmit()'));
const wlStrings = [...new Set([...wl.matchAll(/R\.string\.(\w+)/g)].map(match => match[1]))];
const test = `import java.util.*;
import java.lang.ref.WeakReference;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
public class ModerationSettingsTest {
 static class Uri {}
 static class Intent { Uri uri = new Uri(); Uri getData() { return uri; } }
 static class Activity { static final int RESULT_OK = -1; }
 static class UserConfig {
  static int selectedAccount;
  static final int MAX_ACCOUNT_COUNT=2;
  static final UserConfig[] users = {new UserConfig(11), new UserConfig(22)};
  long uid; UserConfig(long id) {uid=id;}
  static UserConfig getInstance(int i) {return users[i];}
  long getClientUserId() {return uid;}
  boolean isClientActivated() {return uid>0;}
 }
 static class ConnectionsManager {
  static int now=1000;
  static ConnectionsManager getInstance(int account) {return new ConnectionsManager();}
  int getCurrentTime() {return now;}
 }
 static class AndroidUtilities {
  static final Set<Runnable> scheduled = new HashSet<>();
  static void cancelRunOnUIThread(Runnable r) {scheduled.remove(r);}
  static void runOnUIThread(Runnable r,long delay) {scheduled.add(r);}
  static void runOnUIThread(Runnable r) {r.run();}
 }
 static class WlAccess {
  interface Callback {void done(String state);}
  static int submissions, refreshes;
  static Callback last;
  static class Grant {long uid,expires;Grant(long id,long expiry) {uid=id;expires=expiry;}}
  static final ConcurrentHashMap<Long,Grant> GRANTS=new ConcurrentHashMap<>();
  static final CopyOnWriteArraySet<Runnable> LISTENERS=new CopyOnWriteArraySet<>();
  static final AtomicBoolean NOTIFY_PENDING=new AtomicBoolean();
  ${['private static long uid(', 'private static int accountFor(', 'public static Grant cached()',
     'private static Grant accountGrant(', 'public static boolean hasAccountGrant(',
     'public static void addListener(', 'public static void removeListener(', 'private static void notifyChanged()']
     .map(signature => method(access, signature)).join('\n')}
  static void submit(int account,Uri uri,Callback cb) {submissions++;last=cb;}
  static void refresh(int account,Callback cb) {refreshes++;last=cb;}
 }
 static class Base {
  void onResume() {} void onPause() {} void onFragmentDestroy() {}
 }
 static class WlPreferencesActivity extends Base {
  static final int PICK_REQUEST=9913;
  int reloads;
  ${fields}
  ${methods}
  void reload() {reloads++;}
  void pick() {pickAccount=account;pickOwner=owner;picking=true;}
 }
 static class SystemClock {static long now;static long elapsedRealtime() {return now;}}
 static class R {static class string {
  static final int NM_BAN_StatusUpdated=1,NM_BAN_RateLimited=2,NM_BAN_StatusRefreshFailed=3;
  ${wlStrings.map((name, index) => `static final int ${name}=${index + 10};`).join('\n')}
 }}
 static class BannerStatus {
  static final int STATUS_SKIPPED=-2;
  static class Scope {long uid=11;}
  static class CacheKey {}
  static class Executor {
   ArrayList<Runnable> tasks=new ArrayList<>();
   void submit(Runnable r) {tasks.add(r);}
   void drain() {ArrayList<Runnable> work=new ArrayList<>(tasks);tasks.clear();work.forEach(Runnable::run);}
  }
  final Object statusStateLock=new Object();
  final Set<Scope> moderationSending=new HashSet<>(),settingsRefreshing=new HashSet<>();
  final Set<CacheKey> usersNoBanner=new HashSet<>();
  final Executor executor=new Executor();
  Scope current=new Scope(),settingsRefreshOwner;
  long settingsRefreshAfter;int settingsRefreshError,response=200,requests,bulletins;
  Scope scope() {return current;}
  CacheKey key(Scope s,long uid) {return new CacheKey();}
  int fetchStatus(Scope s) {requests++;return response;}
  boolean isCurrentScope(Scope s) {return s==current;}
  void reloadSettings() {} void uiOk(int r) {bulletins++;} void uiErr(int r) {bulletins++;}
  ${method(controller, 'public void refreshStatus(boolean notify)')}
 }
 static void check(boolean ok,String message) {if(!ok) throw new AssertionError(message);}
 static WlPreferencesActivity fresh() {
  UserConfig.selectedAccount=0;UserConfig.users[0].uid=11;UserConfig.users[1].uid=22;
  WlAccess.GRANTS.clear();WlAccess.LISTENERS.clear();ConnectionsManager.now=1000;
  WlAccess.submissions=0;WlAccess.refreshes=0;
  AndroidUtilities.scheduled.clear();return new WlPreferencesActivity();
 }
 public static void main(String[] args) {
  WlPreferencesActivity s=fresh();s.pick();s.onPause();s.onResume();
  check(WlAccess.refreshes==0,"picker resume must not refresh");
  s.onActivityResultFragment(9913,-1,new Intent());
  check(WlAccess.submissions==1 && s.busy && s.status.equals("sending"),"auto submit");
  s.onActivityResultFragment(9913,-1,new Intent());
  s.onResume();check(WlAccess.submissions==1 && WlAccess.refreshes==0,"no duplicate or competing request");
  WlAccess.last.done("pending");check(!s.busy && AndroidUtilities.scheduled.size()==1,"pending poll");
  s.onPause();check(AndroidUtilities.scheduled.isEmpty(),"pause cancels poll");
  s=fresh();s.pick();s.onActivityResultFragment(9913,0,null);
  check(!s.picking && WlAccess.submissions==0,"cancel is harmless");
  s=fresh();s.pick();UserConfig.selectedAccount=1;
  s.onActivityResultFragment(9913,-1,new Intent());
  check(WlAccess.submissions==0 && s.status.equals("account_changed"),"account switch rejects file");
  s=fresh();s.pick();UserConfig.users[0].uid=33;
  s.onActivityResultFragment(9913,-1,new Intent());check(WlAccess.submissions==0,"reused account slot");
  s=fresh();s.pick();s.onActivityResultFragment(9913,-1,new Intent());
  WlAccess.Callback stale=WlAccess.last;s.onFragmentDestroy();int reloads=s.reloads;
  stale.done("pending");check(s.reloads==reloads,"destroy rejects callback");
  s=fresh();s.pick();s.onActivityResultFragment(9913,-1,new Intent());stale=WlAccess.last;
  UserConfig.selectedAccount=1;s.onResume();stale.done("pending");
  check(!s.status.equals("pending"),"old account result cannot overwrite new account");
  s=fresh();s.pick();s.onActivityResultFragment(9913,-1,new Intent());
  WlAccess.last.done("file_too_large");check(!s.busy && s.status.equals("file_too_large"),"upload failure allows retry");
  s=fresh();s.onResume();WlAccess.last.done("error");
  check(AndroidUtilities.scheduled.size()==1,"network failure automatically retries without a button");
  s.onPause();check(AndroidUtilities.scheduled.isEmpty(),"error retry stops when hidden");
  s=fresh();check(!s.canSubmit(),"no application prompt during initial grant restore");
  WlAccess.GRANTS.put(22L,new WlAccess.Grant(22,10_000));
  for (String state : new String[]{"none","loading","approved","pending","blocked","rejected","error","authentication_required","expired","revoked"}) {
   s.status=state;
   check(s.statusString()==R.string.NM_WL_OtherAccount && !s.canSubmit(),"shared access overrides personal status: "+state);
  }
  WlAccess.GRANTS.put(11L,new WlAccess.Grant(11,10_000));
  check(s.statusString()==R.string.NM_WL_Approved,"own grant has priority in status");
  UserConfig.users[1].uid=0;
  check(WlAccess.cached()!=null && !s.canSubmit(),"logout of one approved account keeps another grant");
  UserConfig.users[0].uid=33;
  check(WlAccess.cached()==null,"logged-out grants never authorize a reused slot");
  s=fresh();s.status="none";check(s.canSubmit(),"unapproved device can apply");
  s.onResume();WlAccess.last.done("none");int before=s.reloads;
  WlAccess.GRANTS.put(22L,new WlAccess.Grant(22,10_000));WlAccess.notifyChanged();
  check(s.reloads>before && !s.canSubmit() && s.statusString()==R.string.NM_WL_OtherAccount,"restored grant updates visible screen immediately");
  check(AndroidUtilities.scheduled.size()==1,"shared access periodically rechecked");
  WlAccess.GRANTS.remove(22L);WlAccess.notifyChanged();
  check(s.canSubmit() && s.statusString()==R.string.NM_WL_None,"last sponsor removed reveals own application state");
  s.onPause();before=s.reloads;WlAccess.notifyChanged();
  check(WlAccess.LISTENERS.isEmpty() && s.reloads==before,"hidden screen unsubscribes");
  s=fresh();s.pick();WlAccess.GRANTS.put(22L,new WlAccess.Grant(22,10_000));
  s.onActivityResultFragment(9913,-1,new Intent());
  check(!s.picking && WlAccess.submissions==0,"skip redundant upload if shared access arrives during picker");
  s=fresh();WlAccess.GRANTS.put(22L,new WlAccess.Grant(22,1061));
  check(!s.canSubmit(),"valid grant accepted");ConnectionsManager.now++;
  check(WlAccess.cached()==null,"expiring grant cannot keep shared access visible");
  s=fresh();s.onResume();WlAccess.last.done("approved");
  WlAccess.notifyChanged();
  check(s.busy && s.status.equals("loading"),"loss of own grant triggers recheck");
  s.onFragmentDestroy();check(WlAccess.LISTENERS.isEmpty(),"destroy removes listener");
  s=fresh();s.onResume();stale=WlAccess.last;
  UserConfig.selectedAccount=1;WlAccess.notifyChanged();stale.done("approved");
  check(s.account==1 && s.status.equals("loading") && s.busy,"listener switches identity and ignores previous result");
  WlAccess.last.done("pending");
  WlAccess.GRANTS.put(11L,new WlAccess.Grant(11,10_000));WlAccess.notifyChanged();
  check(!s.canSubmit() && s.statusString()==R.string.NM_WL_OtherAccount,"another approval takes precedence over pending application");
  WlAccess.GRANTS.remove(11L);WlAccess.notifyChanged();
  check(!s.canSubmit() && s.statusString()==R.string.NM_WL_Pending,"own pending application survives sponsor logout");
  BannerStatus b=new BannerStatus();SystemClock.now=1000;
  b.refreshStatus(false);b.refreshStatus(false);check(b.executor.tasks.size()==1,"coalesce status request");
  b.executor.drain();check(b.requests==1 && b.bulletins==0,"silent refresh");
  b.refreshStatus(false);check(b.executor.tasks.isEmpty(),"throttle repeated resume");
  SystemClock.now=31_000;b.response=429;b.refreshStatus(false);b.executor.drain();
  check(b.settingsRefreshError==429,"429 visible");
  SystemClock.now=61_000;b.refreshStatus(true);check(b.executor.tasks.isEmpty(),"429 cooldown");
  SystemClock.now=331_000;b.response=200;b.refreshStatus(false);b.executor.drain();
  check(b.settingsRefreshError==0,"successful retry clears error");
  SystemClock.now=400_000;b.moderationSending.add(b.current);b.refreshStatus(false);
  check(b.executor.tasks.isEmpty(),"no status request during upload");
  b.moderationSending.clear();b.refreshStatus(false);b.current=new BannerStatus.Scope();
  b.refreshStatus(false);b.executor.drain();check(b.settingsRefreshing.isEmpty(),"account-scoped completion cleanup");
  SystemClock.now=500_000;b.response=-1;b.refreshStatus(false);b.executor.drain();
  check(b.settingsRefreshError==-1,"network failure is not mistaken for coalescing");
  System.out.println("PASS: auto-submit, cancellation, duplicate result, account switch/logout, stale callbacks, polling lifecycle and errors");
  System.out.println("PASS: actual grant selection, shared/own status, cold restore, expiry, logout, slot reuse, listeners and duplicate-application guards");
 }
}`;
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'moderation-settings-'));
fs.writeFileSync(path.join(dir, 'ModerationSettingsTest.java'), test);
cp.execFileSync('javac', ['ModerationSettingsTest.java'], {cwd: dir, stdio: 'inherit'});
cp.execFileSync('java', ['ModerationSettingsTest'], {cwd: dir, stdio: 'inherit'});
console.log('PASS: route order, unclipped status layout, upload ownership, refresh throttling and RU/EN/ZH resources');
