const fs = require('node:fs'), path = require('node:path'), os = require('node:os');
const cp = require('node:child_process'), assert = require('node:assert/strict');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/java/org/telegram/ui/LaunchActivity.java'), 'utf8');
const start = source.indexOf('    public void openInAppNotification(Intent intent) {');
assert(start >= 0);
let end = source.indexOf('{', start), depth = 0;
do { if (source[end] === '{') depth++; if (source[end] === '}') depth--; end++; } while (depth);
const method = source.slice(start, end).replaceAll('app.nimarkogram.messenger.notifications.', '');
const java = `import java.util.*;import java.util.function.BooleanSupplier;import java.util.concurrent.atomic.AtomicLong;
public class NotificationAccountNavigationTest {
 static int checks;static void check(boolean ok,String reason){checks++;if(!ok)throw new AssertionError(reason);}
 static class Intent {
  String action="com.tmessages.openchat";Map<String,Long> extras=new HashMap<>();
  Intent(){}Intent(Intent other){action=other.action;extras.putAll(other.extras);}
  String getAction(){return action;}boolean hasExtra(String key){return extras.containsKey(key);}
  long getLongExtra(String k,long d){return extras.getOrDefault(k,d);}int getIntExtra(String k,int d){return (int)getLongExtra(k,d);}
 }
 static class Frame {boolean attached=true;boolean isAttachedToWindow(){return attached;}}
 static class SharedConfig {static boolean animations=true;static boolean animationsEnabled(){return animations;}}
 static class UserConfig {
  static int selectedAccount;static long[] owners={101,202};static UserConfig getInstance(int a){return new UserConfig(a);}
  int account;UserConfig(int a){account=a;}long getClientUserId(){return owners[account];}
 }
 static class NimarkoInAppNotifications {
  static boolean available=true;static long[] epochs={0,0};static long session(int a){return epochs[a];}
  static boolean isAvailable(){return available;}
  static boolean isCurrent(int a,long owner,long session){return a>=0&&a<2&&owner!=0&&UserConfig.owners[a]==owner&&epochs[a]==session;}
 }
 static class AndroidUtilities {static void hideKeyboard(Object focus){}}
 static class Transition {
  BooleanSupplier valid;Runnable change;boolean applying;int starts;
  void start(Object root,Object window,BooleanSupplier valid,Runnable change){this.valid=valid;this.change=change;starts++;}
  void finish(){if(valid.getAsBoolean()){applying=true;try{change.run();}finally{applying=false;}}}
 }
 static class Launch {
  Frame frameLayout=new Frame();boolean finishing,destroyed,focus=true;int opens;boolean underCover;Intent opened;
  Transition accountSwitchTransition=new Transition();AtomicLong navigationRequestGeneration=new AtomicLong();
  boolean isFinishing(){return finishing;}boolean isDestroyed(){return destroyed;}boolean hasWindowFocus(){return focus;}
  Object getCurrentFocus(){return null;}Object getWindow(){return null;}
  void handleIntent(Intent i,boolean a,boolean b,boolean c,Object d,boolean rebuild,boolean external){
   check(!rebuild&&!external,"internal route does not rebuild external intent stack");
   opens++;opened=i;underCover=accountSwitchTransition.applying;UserConfig.selectedAccount=i.getIntExtra("currentAccount",-1);
  }
  ${method}
 }
 static Intent intent(int account){Intent i=new Intent();i.extras.put("currentAccount",(long)account);i.extras.put("nm_banner_owner",UserConfig.owners[account]);i.extras.put("nm_banner_session",0L);return i;}
 static Launch reset(){UserConfig.selectedAccount=0;UserConfig.owners=new long[]{101,202};NimarkoInAppNotifications.epochs=new long[]{0,0};NimarkoInAppNotifications.available=true;SharedConfig.animations=true;return new Launch();}
 public static void main(String[] args){
  Launch app=reset();app.openInAppNotification(intent(0));check(app.opens==1&&app.accountSwitchTransition.starts==0,"same account uses native chat transition");
  app=reset();Intent destination=intent(1);app.openInAppNotification(destination);
  check(app.opens==0&&UserConfig.selectedAccount==0&&app.accountSwitchTransition.starts==1,"capture before changing account");
  destination.extras.put("currentAccount",0L);app.accountSwitchTransition.finish();
  check(app.opens==1&&app.underCover&&UserConfig.selectedAccount==1,"switch and destination open together under cover; intent copied");
  for(int invalid=0;invalid<8;invalid++){
   app=reset();app.openInAppNotification(intent(1));
   if(invalid==0)NimarkoInAppNotifications.epochs[1]++;
   if(invalid==1)UserConfig.owners[1]=999;
   if(invalid==2)NimarkoInAppNotifications.epochs[0]++;
   if(invalid==3)UserConfig.owners[0]=999;
   if(invalid==4)app.navigationRequestGeneration.incrementAndGet();
   if(invalid==5)app.focus=false;
   if(invalid==6)NimarkoInAppNotifications.available=false;
   if(invalid==7)app.destroyed=true;
   app.accountSwitchTransition.finish();check(app.opens==0,"stale or private transition rejected "+invalid);
  }
  app=reset();SharedConfig.animations=false;app.openInAppNotification(intent(1));check(app.opens==1&&app.accountSwitchTransition.starts==0,"animation preference respected");
  app=reset();Intent stale=intent(1);stale.extras.put("nm_banner_owner",999L);app.openInAppNotification(stale);check(app.opens==0&&app.accountSwitchTransition.starts==0,"reject stale destination before capture");
  System.out.println("PASS: "+checks+" cross-account notification navigation checks");
 }
}`;
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-notification-account-'));
try {
 fs.writeFileSync(path.join(dir, 'NotificationAccountNavigationTest.java'), java);
 cp.execFileSync('javac', ['NotificationAccountNavigationTest.java'], {cwd:dir, stdio:'pipe'});
 process.stdout.write(cp.execFileSync('java', ['NotificationAccountNavigationTest'], {cwd:dir, encoding:'utf8'}));
 for (const guard of ['request == navigationRequestGeneration.get() && ',
     '&& NimarkoInAppNotifications.isCurrent(source, sourceOwner, sourceSession)']) {
  const broken = java.replace(guard, '');
  assert.notEqual(broken, java);
  fs.writeFileSync(path.join(dir, 'NotificationAccountNavigationTest.java'), broken);
  cp.execFileSync('javac', ['NotificationAccountNavigationTest.java'], {cwd:dir, stdio:'pipe'});
  const result = cp.spawnSync('java', ['NotificationAccountNavigationTest'], {cwd:dir, encoding:'utf8'});
  assert.notEqual(result.status, 0);
  assert.match(result.stderr, /stale or private transition rejected/);
 }
 console.log('PASS: stale navigation and source-account reuse negative controls');
} finally { fs.rmSync(dir, {recursive:true, force:true}); }
