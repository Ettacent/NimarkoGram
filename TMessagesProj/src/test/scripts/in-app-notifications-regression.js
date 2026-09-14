// Standalone: node TMessagesProj/src/test/scripts/in-app-notifications-regression.js
// Requires a JDK. Runs extracted production Java against small state/transport stubs;
// gesture, settings and Android integration checks are source contracts, not device tests.
'use strict';
const assert = require('node:assert/strict');
const cp = require('node:child_process');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const main = path.resolve(__dirname, '../../main');
const read = name => fs.readFileSync(path.join(main, name), 'utf8');
const source = read('java/app/nimarkogram/messenger/notifications/NimarkoInAppNotifications.java');
const notifications = read('java/org/telegram/messenger/NotificationsController.java');
const launch = read('java/org/telegram/ui/LaunchActivity.java');
const facade = read('java/org/telegram/messenger/NotificationsSettingsFacade.java');
const privacy = read('java/app/nimarkogram/messenger/utils/chats/NimarkoChatsPasswordHelper.java');
const compat = read('java/app/nimarkogram/messenger/utils/CGCompat.java');
const dialogs = read('java/org/telegram/messenger/DialogObject.java');
const userConfig = read('java/org/telegram/messenger/UserConfig.java');
const preferences = read('java/app/nimarkogram/messenger/preferences/GeneralPreferencesActivity.java');
const weather = read('java/app/nimarkogram/messenger/infocards/WeatherCard.java');

function extract(text, signature) {
    const start = text.indexOf(signature);
    assert(start >= 0, `missing production method: ${signature}`);
    // Ignore braces inside comments, strings and character literals.
    const tokens = /\/\/[^\n]*|\/\*[\s\S]*?\*\/|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[{}]/g;
    tokens.lastIndex = text.indexOf('{', start);
    let depth = 0;
    for (let token; (token = tokens.exec(text));) {
        if (token[0] === '{') depth++;
        if (token[0] === '}' && --depth === 0) return text.slice(start, tokens.lastIndex);
    }
    throw new Error(`unterminated method: ${signature}`);
}
const method = signature => extract(source, signature);
const methods = [
    'public static long session(', 'public static boolean isCurrent(', 'public static void onAccountLoggedOut(',
    'public static void onResume(', 'public static void onPause(',
    'public static void onWindowFocusChanged(', 'public static boolean isAvailable()',
    'private static boolean isHostVisible()', 'private static boolean mayRemain(',
    'public static boolean canPreview(', 'public static boolean offer(',
    'private static boolean allowed(', 'public static void dismiss()',
].map(method).join('\n');

// No Android View implementations are simulated. Banner is a payload holder; only
// its actual openChat method runs. Intent overloads preserve Integer/Long types.
const java = `import java.util.*;
import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
public class InAppNotificationsTest {
 static int checks, shown, removed;
 static void check(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
 static class SharedPreferences {
  Map<String,Boolean> values=new HashMap<>();
  boolean getBoolean(String key,boolean fallback){return values.getOrDefault(key,fallback);}
  boolean contains(String key){return values.containsKey(key);}
  void put(String key,boolean value){values.put(key,value);}
 }
 static class NimarkoConfig {static boolean inAppNotifications,askBiometricsToOpenArchive,
  askBiometricsToOpenChat,askBiometricsToOpenSavedMessages;}
 static class Handler {
  boolean accepts=true;
  boolean post(Runnable task){if(!accepts)return false;AndroidUtilities.queue.add(task);return true;}
 }
 static class ApplicationLoader {static boolean mainInterfacePaused,isScreenOn;static Handler applicationHandler;}
 static class SharedConfig {static boolean appLocked,isWaitingForPasscodeEnter,showNotificationsForAllAccounts;}
 static class AndroidUtilities {
  static boolean passcode;static List<Runnable> queue=new ArrayList<>();
  static boolean needShowPasscode(){return passcode;}
  static void runOnUIThread(Runnable r){queue.add(r);}
  static void runOnUIThread(Runnable r,long delay){queue.add(r);}
  static void drain(){List<Runnable> copy=new ArrayList<>(queue);queue.clear();for(Runnable r:copy)r.run();}
 }
 static class SystemClock {static long now;static long elapsedRealtime(){return now;}}
 static class BuildVars {static final boolean LOGS_ENABLED=false;}
 static class FileLog {static void e(Exception e){throw new AssertionError(e);}}
 static class UserConfig {
  static final int MAX_ACCOUNT_COUNT=3;static int selectedAccount;static UserConfig[] accounts;
  long uid;boolean active=true;
  UserConfig(long id){uid=id;}static UserConfig getInstance(int account){return accounts[account];}
  long getClientUserId(){return uid;}boolean isClientActivated(){return active;}
  ${extract(userConfig, 'public static boolean isValidAccount(')}
 }
 static class TLRPC {
  static class Chat {boolean channel,megagroup;Chat(boolean c,boolean m){channel=c;megagroup=m;}}
  static class Dialog {int folder_id;Dialog(int folder){folder_id=folder;}}
 }
 static class ChatObject {static boolean isChannel(TLRPC.Chat c){return c!=null&&c.channel;}}
 static class DialogObject {
  ${extract(dialogs, 'public static boolean isEncryptedDialog(')}
  ${extract(dialogs, 'public static long makeEncryptedDialogId(')}
  ${extract(dialogs, 'public static int getEncryptedChatId(')}
 }
 static class LockedChats {
  static Set<String> locked=new HashSet<>();
  static boolean isLocked(int a,long d){return locked.contains(a+":"+d);}
 }
 static class CGCompat {${extract(compat, 'public static boolean isChatLocked(int account, long dialogId)')}}
 static class NimarkoChatsPasswordHelper {
  ${extract(privacy, 'public static boolean isChatLocked(int currentAccount, long dialogId)')}
  ${extract(privacy, 'public static boolean isSavedMessagesProtected(')}
 }
 static class MessagesController {
  static MessagesController[] accounts;SharedPreferences preferences=new SharedPreferences();
  Map<Long,TLRPC.Chat> chats=new HashMap<>();Map<Long,TLRPC.Dialog> dialogs_dict=new HashMap<>();
  static MessagesController getInstance(int a){return accounts[a];}
  static SharedPreferences getNotificationsSettings(int a){return accounts[a].preferences;}
  TLRPC.Chat getChat(long id){return chats.get(id);}
 }
 static class NotificationsSettingsFacade {
  static final String PROPERTY_CONTENT_PREVIEW="content_preview_";final int currentAccount;
  NotificationsSettingsFacade(int a){currentAccount=a;}
  ${extract(facade, 'private SharedPreferences getPreferences()')}
  ${extract(facade, 'public boolean getProperty(String property, long dialogId, long topicId, boolean defaultValue)')}
 }
 static class NotificationsController {
  int account;NotificationsController(int a){account=a;}
  static NotificationsController getInstance(int a){return new NotificationsController(a);}
  NotificationsSettingsFacade getNotificationsSettingsFacade(){return new NotificationsSettingsFacade(account);}
  // Same key format as production; cache mechanics are outside this test.
  static String getSharedPrefKey(long d,long t){return t==0?Long.toString(d):d+"_"+t;}
 }
 static class BaseFragment {int account;int getCurrentAccount(){return account;}}
 static boolean navigating;
 static boolean navigationRunning(BaseFragment fragment){return navigating;}
 static class ChatActivity extends BaseFragment {
  long dialog;ChatActivity(int a,long d){account=a;dialog=d;}long getDialogId(){return dialog;}
 }
 static class LaunchActivity {
  boolean focus=true,finishing,destroyed;Intent last;int opens;static BaseFragment fragment;
  boolean hasWindowFocus(){return focus;}boolean isFinishing(){return finishing;}boolean isDestroyed(){return destroyed;}
  static BaseFragment getLastFragmentIncludeMainTabs(){return fragment;}
  void onNewIntent(Intent intent){check(banner==null,"dismiss before routing");last=intent;opens++;}
  void openInAppNotification(Intent intent){
   if(intent.getIntExtra("currentAccount",-1)!=UserConfig.selectedAccount){check(banner!=null,"cross-account keeps outgoing geometry until switch");removeCurrent();}
   onNewIntent(intent);
  }
 }
 static class Intent {
  int getIntExtra(String key,int fallback){Object value=extras.get(key);return value instanceof Integer?(Integer)value:fallback;}
  final LaunchActivity owner;final Class<?> target;String action;Map<String,Object> extras=new HashMap<>();
  Intent(LaunchActivity a,Class<?> c){owner=a;target=c;}Intent setAction(String a){action=a;return this;}
  Intent putExtra(String key,int value){extras.put(key,Integer.valueOf(value));return this;}
  Intent putExtra(String key,long value){extras.put(key,Long.valueOf(value));return this;}
 }
 static volatile WeakReference<LaunchActivity> host=new WeakReference<>(null);
 static volatile long generation;static volatile boolean focused;static boolean contentGesture;static Banner banner,retiringBanner;
 static long focusLostAt=-1;
 static final AtomicLongArray sessions=new AtomicLongArray(UserConfig.MAX_ACCOUNT_COUNT);
 static final LinkedHashMap<String,Long> recent=new LinkedHashMap<>();
 static void removeCurrent(){banner=null;retiringBanner=null;removed++;}
 static void remove(Banner old){if(old!=null){old.closing=true;old.touching=false;removed++;}}
 static boolean showSucceeds=true,showThrows;
 static boolean show(Banner next){if(showThrows)throw new RuntimeException("test attachment failure");if(!showSucceeds)return false;banner=next;shown++;return true;}
 ${method('public static final class Delivery')}
 ${methods}
 static class ControllerDelivery {
  int bannerDeliveryGeneration;Delivery pendingBannerDelivery;
  ${extract(notifications, 'private void cancelBannerDelivery()')}
 }
 static class Banner {
  final int account,messageId;final long owner,loginSession,dialogId,topicId;final boolean sample,preview;final Delivery delivery;boolean closing,touching;
  long expiresAt;void pauseInteraction(){touching=false;}
  Banner(LaunchActivity a,int account,long owner,long loginSession,long dialogId,long topicId,int messageId,
    String title,String text,boolean preview,boolean sample,Delivery delivery){
   this.account=account;this.owner=owner;this.loginSession=loginSession;this.dialogId=dialogId;this.topicId=topicId;
   this.messageId=messageId;this.sample=sample;this.preview=preview;this.delivery=delivery;
  }
  ${method('void openChat()')}
  boolean replaceMessage(int a,long o,long s,long d,long t,int m,String h,String text,boolean p,Delivery delivery){return false;}
 }
 static LaunchActivity reset(){
  host=new WeakReference<>(null);generation=0;focused=false;banner=null;retiringBanner=null;recent.clear();shown=removed=0;
  showSucceeds=true;showThrows=false;for(int i=0;i<sessions.length();i++)sessions.set(i,0);
  NimarkoConfig.inAppNotifications=true;NimarkoConfig.askBiometricsToOpenArchive=false;
  NimarkoConfig.askBiometricsToOpenChat=false;NimarkoConfig.askBiometricsToOpenSavedMessages=false;
  ApplicationLoader.mainInterfacePaused=false;ApplicationLoader.isScreenOn=true;
  ApplicationLoader.applicationHandler=new Handler();
  SharedConfig.appLocked=false;SharedConfig.isWaitingForPasscodeEnter=false;SharedConfig.showNotificationsForAllAccounts=true;
  AndroidUtilities.passcode=false;AndroidUtilities.queue.clear();SystemClock.now=1000;LockedChats.locked.clear();
  UserConfig.selectedAccount=0;UserConfig.accounts=new UserConfig[]{new UserConfig(101),new UserConfig(202),new UserConfig(303)};
  MessagesController.accounts=new MessagesController[]{new MessagesController(),new MessagesController(),new MessagesController()};
  LaunchActivity.fragment=null;LaunchActivity activity=new LaunchActivity();onResume(activity);return activity;
 }
 static void previewTests(){
  // Topic unset inherits parent; explicit topic true/false overrides parent.
  for(int a=0;a<2;a++)for(int parent=-1;parent<=1;parent++)for(int topic=-1;topic<=1;topic++){
   reset();SharedPreferences p=MessagesController.getNotificationsSettings(a);
   if(parent>=0)p.put("content_preview_700",parent==1);
   if(topic>=0)p.put("content_preview_700_42",topic==1);
   boolean inherited=parent!=0;
   check(canPreview(a,700,42)==(topic<0?inherited:topic==1),"topic fallback/override");
   check(canPreview(a,700,43)==inherited,"sibling topic isolation");
   check(canPreview(a,700,0)==inherited,"parent preview");
   check(canPreview(1-a,700,42),"account preview isolation");
   p.put("EnableInAppPreview",false);check(!canPreview(a,700,42),"in-app preview master");
  }
  for(int type=0;type<4;type++){
   reset();long d=type==0?700:-700;
   if(type>0)MessagesController.getInstance(0).chats.put(700L,new TLRPC.Chat(type>1,type==3));
   String key=type==0?"EnablePreviewAll":type==2?"EnablePreviewChannel":"EnablePreviewGroup";
   check(canPreview(0,d,0),"known peer preview default");
   MessagesController.getNotificationsSettings(0).put(key,false);
   check(!canPreview(0,d,0),"correct global peer category");
  }
  reset();check(!canPreview(0,-700,0),"unknown group/channel fails closed");
  check(!canPreview(0,DialogObject.makeEncryptedDialogId(9),0),"encrypted preview hidden independent of biometric settings");
  NimarkoConfig.askBiometricsToOpenChat=true;LockedChats.locked.add("0:700");
  check(!canPreview(0,700,0),"locked chat hidden");check(canPreview(1,700,0),"lock account isolation");
  NimarkoConfig.askBiometricsToOpenSavedMessages=true;
  check(!canPreview(0,101,0),"protected saved messages");check(canPreview(1,101,0),"saved identity account isolation");
 }
 static void guardTests(){
  for(int reason=0;reason<7;reason++){
   LaunchActivity a=reset();check(isAvailable()&&allowed(0,101,700,false),"baseline available");
   switch(reason){case 0:NimarkoConfig.inAppNotifications=false;break;
    case 1:ApplicationLoader.mainInterfacePaused=true;break;case 2:ApplicationLoader.isScreenOn=false;break;
    case 3:SharedConfig.appLocked=true;break;case 4:SharedConfig.isWaitingForPasscodeEnter=true;break;
    case 5:AndroidUtilities.passcode=true;break;case 6:onWindowFocusChanged(a,false);break;}
   check(!isAvailable()&&!allowed(0,101,700,false),"availability guard "+reason);
   check(!allowed(0,101,0,true),"sample cannot bypass lifecycle "+reason);
  }
  reset();host.clear();check(!isAvailable(),"missing host");
  for(int reason=0;reason<3;reason++){
   LaunchActivity a=reset();if(reason==0)a.finishing=true;if(reason==1)a.destroyed=true;if(reason==2)a.focus=false;
   check(!allowed(0,101,700,false),"actual activity guard "+reason);
  }
  LaunchActivity a=reset();long g=generation;LaunchActivity other=new LaunchActivity();
  onPause(other);onWindowFocusChanged(other,false);
  check(host.get()==a&&generation==g&&isAvailable(),"old activity callbacks cannot affect new host");
  onWindowFocusChanged(a,false);check(generation>g&&!focused,"focus loss invalidates offers");
  onWindowFocusChanged(a,true);check(isAvailable(),"focus restored");
  Banner preserved=new Banner(a,0,101,session(0),700,0,1,"title","text",true,false,null);
  banner=preserved;preserved.expiresAt=SystemClock.elapsedRealtime()+5000;
  long deadline=preserved.expiresAt;
  onWindowFocusChanged(a,false);
  check(banner==preserved&&mayRemain(0,101,700,false)&&!allowed(0,101,700,false),"shade preserves visible banner but blocks new offers and taps");
  SystemClock.now+=6000;onWindowFocusChanged(a,false);SystemClock.now+=4000;
  onWindowFocusChanged(a,true);check(banner==preserved&&preserved.expiresAt==deadline+10000,"focus return preserves timeout including repeated focus-loss callback");
  SharedConfig.appLocked=true;check(!mayRemain(0,101,700,false),"focus preservation never bypasses passcode");SharedConfig.appLocked=false;
  onPause(a);check(host.get()==null&&!isAvailable(),"pause clears host");
  onResume(other);check(host.get()==other&&isAvailable(),"resume replacement host");
  reset();check(!allowed(-1,101,700,false)&&!allowed(3,101,700,false),"invalid account bounds");
  check(!allowed(0,999,700,false)&&!allowed(0,999,0,true),"UID mismatch including sample");
  UserConfig.accounts[0].active=false;check(!allowed(0,101,700,false),"logged out account");
  reset();SharedConfig.showNotificationsForAllAccounts=false;
  check(!allowed(1,202,700,false)&&allowed(0,101,700,false),"selected-account-only policy");
  SharedConfig.showNotificationsForAllAccounts=true;check(allowed(1,202,700,false),"all accounts enabled");
  MessagesController.getNotificationsSettings(1).put("EnableInAppPopup",false);
  check(!allowed(1,202,700,false)&&allowed(0,101,700,false),"account popup preference");
  reset();NimarkoConfig.askBiometricsToOpenArchive=true;
  check(!allowed(0,101,700,false),"unknown archive membership fails closed");
  MessagesController.getInstance(0).dialogs_dict.put(700L,new TLRPC.Dialog(1));
  check(!allowed(0,101,700,false),"archived chat suppressed");
  MessagesController.getInstance(0).dialogs_dict.put(700L,new TLRPC.Dialog(0));
  check(allowed(0,101,700,false),"known unarchived chat allowed");
  reset();LaunchActivity.fragment=new ChatActivity(0,700);
  check(!allowed(0,101,700,false),"already open dialog suppressed");
  check(allowed(1,202,700,false)&&allowed(0,101,701,false),"open-dialog account/peer isolation");
 }
 static boolean offerMessage(int a,long d,int mid){return offer(a,UserConfig.getInstance(a).uid,session(a),d,42,mid,99,"private title","private body",true,new Delivery(),v->{});}
 static void offerTests(){
  for(int reason=0;reason<5;reason++){
   LaunchActivity a=reset();check(offerMessage(0,700,1),"offer accepted for queue");
   check(shown==0,"offer runs on UI queue");
   switch(reason){case 0:dismiss();break;case 1:onPause(a);break;case 2:onWindowFocusChanged(a,false);break;
    case 3:UserConfig.accounts[0].uid=999;break;case 4:SharedConfig.appLocked=true;break;}
   AndroidUtilities.drain();check(shown==0,"queued offer rechecks generation/identity/lifecycle "+reason);
  }
  reset();offerMessage(0,700,1);AndroidUtilities.drain();check(shown==1,"eligible offer displayed");
  offerMessage(0,700,1);AndroidUtilities.drain();check(shown==1,"message dedup");
  offerMessage(1,700,1);AndroidUtilities.drain();check(shown==2,"message ownership isolation");
  offerMessage(0,701,1);AndroidUtilities.drain();check(shown==3,"dialog message-id isolation");
  SystemClock.now+=120001;offerMessage(0,700,1);AndroidUtilities.drain();check(shown==4,"dedup expiry");
  for(int i=2;i<75;i++){offerMessage(0,700,i);AndroidUtilities.drain();}check(recent.size()==64,"bounded dedup cache");
  reset();offerMessage(0,700,1);NimarkoConfig.askBiometricsToOpenChat=true;LockedChats.locked.add("0:700");
  AndroidUtilities.drain();check(banner!=null&&!banner.preview,"privacy rechecked at bind time");
  reset();check(!offer(-1,101,0,700,0,1,0,"","",true,new Delivery(),v->{})&&!offer(3,101,0,700,0,1,0,"","",true,new Delivery(),v->{}),"offer validates account before UID lookup");
  reset();offerMessage(0,700,1);AndroidUtilities.drain();Banner first=banner;first.touching=true;
  offerMessage(0,700,2);AndroidUtilities.drain();check(banner==first&&shown==1,"touching banner cannot be replaced");
  first.touching=false;offerMessage(0,700,2);AndroidUtilities.drain();check(shown==2,"dropped touching offer was not deduped");
  reset();showSucceeds=false;offerMessage(0,700,1);AndroidUtilities.drain();check(recent.isEmpty(),"failed show not deduped");
  showSucceeds=true;offerMessage(0,700,1);AndroidUtilities.drain();check(shown==1,"failed show can retry");
 }
 static void sessionTests(){
  reset();check(session(-1)==-1&&session(3)==-1,"session bounds");
  check(!isCurrent(-1,101,0)&&!isCurrent(3,101,0)&&!isCurrent(0,0,0)&&!isCurrent(0,101,-1),"invalid identity/epoch");
  check(isCurrent(0,101,0)&&!isCurrent(0,202,0)&&!isCurrent(0,101,1),"current identity/epoch");
  long old=session(0);onAccountLoggedOut(-1);onAccountLoggedOut(3);check(session(0)==old,"invalid logout no-op");
  onAccountLoggedOut(0);check(session(0)==old+1&&!isCurrent(0,101,old),"logout invalidates synchronously before UI drain");
  check(isCurrent(1,202,0),"logout epoch account isolation");
  check(!offer(0,101,old,700,0,1,0,"old","old",true,new Delivery(),v->{}),"reject old epoch even with same UID logged in");
  AndroidUtilities.drain();check(isCurrent(0,101,session(0)),"same UID new login epoch valid");
  reset();offerMessage(0,700,1);onAccountLoggedOut(0);AndroidUtilities.drain();
  check(shown==0,"logout between enqueue and display rejects same-UID stale offer");
  reset();offerMessage(0,700,1);AndroidUtilities.drain();offerMessage(1,700,1);AndroidUtilities.drain();
  Banner other=banner;onAccountLoggedOut(0);AndroidUtilities.drain();
  check(banner==other&&recent.size()==1&&recent.keySet().iterator().next().startsWith("1:"),"logout clears only matching account dedup/banner");
  onAccountLoggedOut(1);AndroidUtilities.drain();check(banner==null&&recent.isEmpty(),"logout clears owned banner/cache");
  reset();LaunchActivity a=host.get();Banner stale=routeBanner(0,700,0,1,false);
  onAccountLoggedOut(0);stale.openChat();check(a.opens==0,"tap rejects old epoch before logout UI cleanup");
  reset();Banner retiring=routeBanner(0,700,0,1,false);Banner active=routeBanner(1,800,0,2,false);
  retiringBanner=retiring;onAccountLoggedOut(0);AndroidUtilities.drain();
  check(banner==active&&retiringBanner==null&&retiring.closing&&!active.closing,"logout of retiring account preserves current notification");
  reset();retiring=routeBanner(1,800,0,1,false);active=routeBanner(0,700,0,2,false);
  retiringBanner=retiring;onAccountLoggedOut(0);AndroidUtilities.drain();
  check(banner==null&&retiringBanner==retiring&&!retiring.closing&&active.closing,"logout removes only current account notification");
  reset();onAccountLoggedOut(0);active=routeBanner(0,701,0,2,false);
  recent.put("0:101:0:700:1",1000L);recent.put("0:101:1:701:2",1000L);AndroidUtilities.drain();
  check(banner==active&&!active.closing&&recent.size()==1&&recent.containsKey("0:101:1:701:2"),"delayed logout preserves new login notification and dedup");
  reset();a=host.get();stale=routeBanner(0,700,0,1,false);active=routeBanner(1,800,0,2,false);
  stale.openChat();check(a.opens==0&&banner==active,"late tap on replaced notification cannot navigate or dismiss replacement");
  reset();a=host.get();stale=routeBanner(0,700,0,1,false);dismiss();UserConfig.selectedAccount=1;
  stale.openChat();check(a.opens==0&&banner==null,"late tap after account switch cannot open previous account");
  reset();offerMessage(0,700,1);dismiss();UserConfig.selectedAccount=1;AndroidUtilities.drain();
  check(banner==null&&shown==0,"pending notification is cancelled by account switch");
 }
 static void completionTests(){
  // Synchronous rejection returns false without scheduling a callback; accepted
  // work calls completion exactly once, including every early UI return.
  for(int reason=0;reason<11;reason++){
   LaunchActivity a=reset();List<Boolean> completions=new ArrayList<>();
   if(reason==1){offerMessage(0,700,1);AndroidUtilities.drain();}
   if(reason==2){offerMessage(0,701,2);AndroidUtilities.drain();banner.touching=true;}
   if(reason==3)showSucceeds=false;if(reason==4)showThrows=true;
   Delivery delivery=new Delivery();
   boolean accepted=offer(0,101,session(0),700,42,1,99,"title","body",true,delivery,completions::add);
   check(accepted&&completions.isEmpty(),"accepted offer completion is deferred "+reason);
   if(reason==5)dismiss();if(reason==6)onAccountLoggedOut(0);
   if(reason==7)onWindowFocusChanged(a,false);
   if(reason==8)MessagesController.getNotificationsSettings(0).put("EnableInAppPopup",false);
   if(reason==9)UserConfig.accounts[0].uid=999;
   if(reason==10)delivery.cancel();
   AndroidUtilities.drain();
   if(reason==2){check(completions.isEmpty(),"touch defers rather than falls back");SystemClock.now+=4001;AndroidUtilities.drain();}
   check(completions.equals(Arrays.asList(reason<2)),"exactly once handled/fallback completion "+reason);
   AndroidUtilities.drain();check(completions.size()==1,"no duplicate completion "+reason);
  }
  for(int reason=0;reason<4;reason++){
   reset();List<Boolean> completions=new ArrayList<>();
   if(reason==0)NimarkoConfig.inAppNotifications=false;if(reason==1)SharedConfig.appLocked=true;
   if(reason==2)onAccountLoggedOut(0);if(reason==3)UserConfig.accounts[0].uid=999;
   check(!offer(0,101,0,700,0,1,0,"","",true,new Delivery(),completions::add),"synchronous rejection "+reason);
   AndroidUtilities.drain();check(completions.isEmpty(),"synchronous fallback has no second completion "+reason);
  }
  for(boolean missing:new boolean[]{false,true}){
   reset();List<Boolean> completions=new ArrayList<>();
   if(missing)ApplicationLoader.applicationHandler=null;else ApplicationLoader.applicationHandler.accepts=false;
   check(!offer(0,101,0,700,0,1,0,"","",true,new Delivery(),completions::add),"handler post failure returns false");
   check(AndroidUtilities.queue.isEmpty(),"failed handler post did not enqueue");
   AndroidUtilities.drain();check(completions.isEmpty()&&shown==0,"failed post leaves synchronous fallback ownership");
  }
 }
 static void deliveryTests() throws Exception {
  reset();Delivery delivery=new Delivery();check(delivery.isActive()&&!delivery.isComplete(),"new delivery active and incomplete");
  check(delivery.complete()&&!delivery.complete(),"delivery completes exactly once");
  check(delivery.isComplete(),"completion observable");
  check(delivery.isActive(),"completion does not cancel a displayed banner");
  delivery.cancel();delivery.cancel();check(!delivery.isActive()&&!delivery.complete(),"cancel idempotent; completed stays completed");
  Delivery cancelled=new Delivery();cancelled.cancel();check(cancelled.complete()&&!cancelled.complete(),"cancelled callback/fallback can retire once");
  Delivery race=new Delivery();AtomicInteger wins=new AtomicInteger();
  java.util.concurrent.CountDownLatch start=new java.util.concurrent.CountDownLatch(1);
  List<Thread> contenders=new ArrayList<>();
  for(int i=0;i<16;i++){
   Thread thread=new Thread(()->{try{start.await();if(race.complete())wins.incrementAndGet();}
    catch(InterruptedException e){throw new AssertionError(e);}});contenders.add(thread);thread.start();
  }
  start.countDown();for(Thread thread:contenders)thread.join();check(wins.get()==1,"atomic completion has exactly one concurrent winner");
  ControllerDelivery controller=new ControllerDelivery();Delivery pending=new Delivery();controller.pendingBannerDelivery=pending;
  controller.cancelBannerDelivery();check(controller.bannerDeliveryGeneration==1&&!pending.isActive()&&controller.pendingBannerDelivery==null,"actual controller cancellation retires pending delivery");
  controller.cancelBannerDelivery();check(controller.bannerDeliveryGeneration==2,"cancellation advances generation even without pending work");
  Delivery visible=new Delivery();visible.complete();controller.pendingBannerDelivery=visible;
  controller.cancelBannerDelivery();check(visible.isActive()&&controller.pendingBannerDelivery==null&&controller.bannerDeliveryGeneration==3,"unrelated rebuild preserves completed visible banner");
  LaunchActivity a=reset();Banner b=routeBanner(0,700,0,1,false);b.delivery.cancel();b.openChat();
  check(a.opens==0,"cancelled banner tap does not navigate");
  // Exercise fallback winning before a blocked UI runnable using the real token.
  reset();Delivery timedOut=new Delivery();List<Boolean> callbacks=new ArrayList<>();
  offer(0,101,0,700,0,1,0,"","",true,timedOut,callbacks::add);
  check(timedOut.complete(),"fallback claims completion");timedOut.cancel();AndroidUtilities.drain();
  check(shown==0&&callbacks.equals(Arrays.asList(false))&&!timedOut.complete(),"late UI cannot display or claim fallback completion again");
 }
 static Banner routeBanner(int a,long d,long topic,int mid,boolean sample){
  return banner=new Banner(host.get(),a,UserConfig.getInstance(a).uid,session(a),d,topic,mid,"","",false,sample,sample?null:new Delivery());
 }
 static void routingTests(){
  long large=5000000001L,topic=5000000002L;
  for(long d:new long[]{large,-large,DialogObject.makeEncryptedDialogId(37),202}){
   LaunchActivity a=reset();routeBanner(1,d,topic,867,false).openChat();Intent i=a.last;
   check(i!=null&&a.opens==1&&i.owner==a&&i.target==LaunchActivity.class,"explicit existing-host route");
   check("com.tmessages.openchat".equals(i.action),"existing openchat action");
   check(Integer.valueOf(1).equals(i.extras.get("currentAccount")),"origin account not selected account");
   check(Integer.valueOf(867).equals(i.extras.get("message_id")),"integer message id");
   Map<String,Object> expected=new HashMap<>();expected.put("currentAccount",1);expected.put("message_id",867);
   expected.put("nm_banner_owner",202L);expected.put("nm_banner_session",session(1));
   if(DialogObject.isEncryptedDialog(d))expected.put("encId",37);
   else if(d>0)expected.put("userId",d);
   else {expected.put("chatId",-d);expected.put("topicId",topic);}
   check(i.extras.equals(expected),"exact typed peer/topic extras; no mixed ownership or snake-case peer keys");
  }
  for(int reason=0;reason<5;reason++){
   LaunchActivity a=reset();Banner b=routeBanner(1,700,0,1,reason==0);
   if(reason==1)b.closing=true;if(reason==2)UserConfig.accounts[1].uid=999;
   if(reason==3)SharedConfig.appLocked=true;if(reason==4)onWindowFocusChanged(a,false);
   b.openChat();check(a.opens==0,"sample/stale/locked/closing tap does not navigate "+reason);
  }
  LaunchActivity a=reset();routeBanner(0,700,0,-1,false).openChat();
  check(Integer.valueOf(0).equals(a.last.extras.get("message_id")),"negative local id does not become jump target");
 }
 static void navigationDeliveryTests(){
  for(int reason=0;reason<6;reason++){
   LaunchActivity activity=reset();navigating=true;List<Boolean> results=new ArrayList<>();Delivery delivery=new Delivery();
   check(offer(0,101,session(0),700,0,5,0,"name","message",true,delivery,results::add),"navigation accepts pending delivery");
   for(int i=0;i<20;i++){SystemClock.now+=32;AndroidUtilities.drain();}
   check(results.isEmpty()&&shown==0&&AndroidUtilities.queue.size()==1,"navigation keeps one retry without system fallback");
   if(reason==0)navigating=false;
   if(reason==1)delivery.cancel();
   if(reason==2)onAccountLoggedOut(0);
   if(reason==3)onWindowFocusChanged(activity,false);
   if(reason==4)SharedConfig.appLocked=true;
   if(reason==5)SystemClock.now+=4001;
   AndroidUtilities.drain();AndroidUtilities.drain();
   check(results.equals(Arrays.asList(reason==0)),"pending delivery completes once or safely falls back "+reason);
   check(AndroidUtilities.queue.isEmpty(),"retry released after finish "+reason);
   navigating=false;
  }
 }
 public static void main(String[] args) throws Exception {previewTests();guardTests();offerTests();sessionTests();completionTests();deliveryTests();routingTests();navigationDeliveryTests();
  System.out.println("PASS: "+checks+" extracted-Java assertions (privacy, lifecycle, UID, queued offers, typed routing)");}
}`;

// Explicit structural checks for Android-only surfaces. These are deliberately
// separate from the executable Java checks above.
function swipeContract(text) {
    const intercept = extract(text, 'public boolean onInterceptTouchEvent(');
    const touch = extract(text, 'public boolean onTouchEvent(');
    assert.match(intercept, /ACTION_DOWN[\s\S]*?beginGesture\(e\)/);
    assert.match(extract(text, 'void beginGesture('), /dragged\s*=\s*multiplePointers\s*=\s*false/);
    assert.match(intercept, /dragged\s*=\s*true;\s*animate\(\)\.cancel\(\);\s*return true/);
    const move = touch.slice(touch.indexOf('case MotionEvent.ACTION_MOVE:'), touch.indexOf('case MotionEvent.ACTION_UP:'));
    assert.match(move, /Math\.abs\(dy\)\s*>\s*slop\s*\|\|[\s\S]*?dragged\s*=\s*true/);
    assert(!/dragged\s*=\s*false/.test(move), 'drag stays latched after returning to origin');
    assert.match(touch, /if\s*\(!dragged\)\s*\{\s*settleExpansion\(expanded\);\s*performClick\(\)/);
    assert.match(touch, /case MotionEvent.ACTION_CANCEL:\s*touching\s*=\s*false/);
}
swipeContract(source);
assert.throws(() => swipeContract(source.replace('if (!dragged) { settleExpansion(expanded); performClick();', 'if (true) { settleExpansion(expanded); performClick();')),
    assert.AssertionError, 'negative control: unlatched click must fail source contract');

const detach = method('protected void onDetachedFromWindow()');
assert.match(detach, /if\s*\(banner\s*==\s*this\)\s*banner\s*=\s*null/);
assert.match(detach, /removeCallbacks\(watch\)/);
assert.match(detach, /animate\(\)\.cancel\(\)/);
assert.match(method('private static boolean show('), /resolvePanel\(next\)/);
assert.doesNotMatch(method('private static boolean show('), /getDecorView\(\)/);
assert.match(method('private static AnimatedLinearLayout resolvePanel(Banner request)'), /isAttachedToWindow\(\)/);
const dispatch = method('public boolean dispatchTouchEvent(MotionEvent e)');
const pushChats = launch.slice(launch.indexOf('} else if (push_user_id != 0) {'), launch.indexOf('} else if (showDialogsList) {', launch.indexOf('} else if (push_user_id != 0) {')));
assert.equal((pushChats.match(/setNoAnimation\(!animateInAppNavigation \|\| accountSwitchTransition.isApplying\(\)\)/g) || []).length, 9);
assert(!pushChats.includes('setNoAnimation(true)'));
assert.match(launch, /final boolean animateInAppNavigation = bannerNavigationOwner != 0 && isNew && !restore/);
assert.equal((launch.match(/if \(!animateInAppNavigation\) NotificationCenter.getInstance\(intentAccount\[0\]\).postNotificationName\(NotificationCenter.closeChats\)/g) || []).length, 3);
const notificationSections = extract(preferences, 'private void fillNotifications(');
assert.equal((notificationSections.match(/UItem.asHeader/g) || []).length, 3);
assert.equal((notificationSections.match(/UItem.asShadow\(null\)/g) || []).length, 2);
assert.match(notificationSections, /R.drawable.msg_played, getString\(R.string.NM_InAppNotificationsPreview\)/);
assert.match(extract(weather, 'private void showGrantState()'), /setText\(LocaleController.getString\(R.string.NM_CARDS_NameWeather\), true\)/);
assert.match(dispatch, /ACTION_DOWN\) \{\s*if \(getParent\(\) != null\) getParent\(\).requestDisallowInterceptTouchEvent\(true\);\s*touching = true/);
assert.match(dispatch, /ACTION_UP[\s\S]*?ACTION_CANCEL[\s\S]*?touching = false/);
assert.match(dispatch, /expiresAt = SystemClock.elapsedRealtime\(\) \+ 5000/);
assert.match(dispatch, /super.dispatchTouchEvent\(e\)/);
assert.match(source, /else if \(focused && !touching && !contentGesture[\s\S]*?!navigationRunning\(LaunchActivity.getLastFragmentIncludeMainTabs\(\)\) && SystemClock.elapsedRealtime\(\) >= expiresAt\)/);
assert.match(source, /!mayRemain\(account, owner, dialogId, sample\)/);
assert(!/TYPE_APPLICATION_OVERLAY|SYSTEM_ALERT_WINDOW|PopupNotificationActivity/.test(source));
assert.match(source, /TLObject peer\s*=\s*preview\s*&&\s*!sample/);
assert.match(source, /String name\s*=\s*preview\s*\?\s*heading\s*:\s*getString\(R.string.AppName\)/);
assert.match(source, /body.setText\(preview\s*\?\s*message\s*:\s*getString\(R.string.NotificationHiddenMessage\)/);
assert.match(source, /!sample\s*&&\s*preview\s*&&\s*!canPreview\(account, dialogId, topicId\)/);

for (const [signature, call] of [
    ['protected void onResume()', 'onResume(this)'],
    ['protected void onPause()', 'onPause(this)'],
    ['protected void onDestroy()', 'onPause(this)'],
    ['public void onWindowFocusChanged(boolean hasFocus)', 'onWindowFocusChanged(this, hasFocus)'],
    ['public void showPasscodeActivity(', 'dismiss()'],
    ['public void onNewIntent(Intent intent)', 'dismiss()'],
    ['public void onNewIntent(Intent intent, Browser.Progress progress)', 'dismiss()'],
    ['public void switchToAccount(int account, boolean removeAll,', 'dismiss()'],
]) {
    assert(extract(launch, signature).includes(`NimarkoInAppNotifications.${call}`), `LaunchActivity hook: ${signature}`);
}
const nativeOffer = extract(notifications, 'private boolean offerInAppNotification(');
for (const guard of ['EnableInAppPopup', 'isSilentMessage(message)', 'message.isStoryPush',
    'message.isStoryMentionPush', 'message.isStoryReactionPush', 'message.isOauthPush',
    'age < -5', 'age > 120', '"dismissDate" + dialogId', 'message.messageOwner.rich_message == null']) {
    assert(nativeOffer.includes(guard), `native notification policy: ${guard}`);
}
assert.match(nativeOffer, /canPreview\(currentAccount, dialogId, topicId\)/);
assert.match(nativeOffer, /getShortStringForMessage\(message, sender, hasPreview\)/);
assert.match(nativeOffer, /preview\s*=\s*hasPreview\[0\]\s*&&/);
assert.match(nativeOffer, /\.offer\(currentAccount, owner, loginSession, dialogId,\s*topicId, message.getId\(\), message.messageOwner.random_id/);
assert.match(notifications, /if\s*\(isInApp\s*&&\s*notifyAboutLast\s*&&\s*!notifyDisabled\s*&&[\s\S]*?offerInAppNotification\(lastMessageObject, bannerOwner, bannerSession, delivery, handled ->/);
assert.match(notifications, /notifyDisabled \|= Boolean.TRUE.equals\(inAppHandled\) && NimarkoInAppNotifications.isAvailable\(\)/);
assert.match(notifications, /notificationBuilder.setSilent\(isSilent\)/);
assert.match(notifications, /new NotificationCompat.Builder\(ApplicationLoader.applicationContext\)\s*\.setSilent\(isSilent\)/);
assert.match(notifications, /bannerDelivery != bannerDeliveryGeneration\s*\|\|\s*![\w.]+\.isCurrent\(currentAccount, bannerOwner, bannerSession\)\s*\|\|\s*!SharedConfig.showNotificationsForAllAccounts && currentAccount != UserConfig.selectedAccount\) return;/);
const offerSite = notifications.indexOf('if (isInApp && notifyAboutLast && !notifyDisabled');
assert(notifications.lastIndexOf('NimarkoConfig.silenceNonContacts', offerSite) >= 0, 'offer follows existing non-contact policy');
assert(notifications.indexOf('showExtraNotifications(mBuilder', offerSite) > offerSite, 'native delivery remains after overlay offer');
const entry = extract(notifications, 'private void showOrUpdateNotification(boolean notifyAboutLast)');
assert.match(entry, /cancelBannerDelivery\(\);\s*showOrUpdateNotification\(notifyAboutLast, null\)/);
const update = extract(notifications, 'private void showOrUpdateNotification(boolean notifyAboutLast, Boolean inAppHandled)');
assert(!update.includes('cancelBannerDelivery();'), 'resuming native delivery must not cancel the accepted banner');
assert.match(update, /\{\s*final int bannerDelivery = bannerDeliveryGeneration;\s*final long bannerPrivacy = bannerPrivacyRevision.get\(\);\s*final long bannerSession = [\w.]+\.session\(currentAccount\);\s*final long bannerOwner = getUserConfig\(\).getClientUserId\(\);/);
assert.match(update, /if \(!delivery.complete\(\)\) return;\s*notificationsQueue.cancelRunnable\(fallback\);\s*notificationsQueue.postRunnable/);
assert.match(update, /bannerDelivery == bannerDeliveryGeneration && delivery.complete\(\)\)\s*\{\s*delivery.cancel\(\);\s*showOrUpdateNotification\(false\)/);
assert.match(update, /notificationsQueue.postRunnable\(fallback, 5000\);\s*return;/);
assert.match(update, /if \(bannerPrivacy != bannerPrivacyRevision.get\(\)\s*\|\| previewAllowed != NimarkoInAppNotifications.canPreview\(currentAccount, bannerDialog, bannerTopic\)\s*\|\| pushMessages.isEmpty\(\) \|\| pushMessages.get\(0\) != lastMessageObject\)\s*\{\s*showOrUpdateNotification\(false\);\s*return;/);
assert.match(update, /showOrUpdateNotification\(notifyAboutLast, handled && NimarkoInAppNotifications.isAvailable\(\)\)/);
assert.match(update, /inAppHandled == null && !notifyDisabled && dialog_id == override_dialog_id/);
assert(update.indexOf('offerInAppNotification(') < update.indexOf('String customSoundPath;'),
    'banner precedes ringtone lookup, avatar decoding and Android channel preparation');
assert.match(extract(notifications, 'public void processReadMessages('), /if \(refreshPendingBanner\) showOrUpdateNotification\(false\);/);
assert.match(method('private static boolean show('), /next.delivery != null && !next.delivery.isActive\(\)/);
assert.match(extract(launch, 'private boolean isNavigationRequestCurrent('), /generation != bannerNavigationGeneration/);
for (const signature of ['public void processReadMessages(', 'public void hideNotifications()', 'private void dismissNotification()']) {
    assert(extract(notifications, signature).includes('cancelBannerDelivery();'), `delivery cancellation: ${signature}`);
}
assert.match(nativeOffer, /title, text, preview, delivery, completion\)/);
assert.match(method('public static boolean offer('), /return ApplicationLoader.applicationHandler != null && ApplicationLoader.applicationHandler.post\(present\);/);
assert(!nativeOffer.includes('getClientUserId()'), 'do not recapture notification owner after formatting');
assert.match(extract(userConfig, 'public void clearConfig()'), /\{\s*[\w.]+\.onAccountLoggedOut\(currentAccount\);/);
assert.match(source, /!isCurrent\(account, owner, loginSession\)\s*\|\|\s*!allowed/);
assert.match(method('void openChat()'), /delivery != null && !delivery.isActive\(\)/);
assert.match(method('void openChat()'), /activity.openInAppNotification\(intent\)/);
assert.doesNotMatch(method('void animateOpenChat()'), /settlePull|settleGeometry/);
assert.match(extract(launch, 'public void openInAppNotification('), /handleIntent\(intent, true, false, false, null, false, false\)/);
const handleIntent = extract(launch, 'private boolean handleIntent(Intent intent, boolean isNew, boolean restore, boolean fromPassword, Browser.Progress');
assert.match(handleIntent, /\{\s*if \(intent != null && intent.hasExtra\("nm_banner_owner"\)[\s\S]*?\.isCurrent\([\s\S]*?getLongExtra\("nm_banner_session", -1\)\)\) return false;/);
assert.match(extract(launch, 'private boolean isNavigationRequestCurrent('), /\.isCurrent\(targetAccount, bannerNavigationOwner, bannerNavigationSession\)/);

const config = read('java/app/nimarkogram/messenger/NimarkoConfig.java');
const settings = read('java/app/nimarkogram/messenger/preferences/GeneralPreferencesActivity.java');
const search = read('java/app/nimarkogram/messenger/preferences/NimarkoSettingsSearchIndex.java');
assert.match(config, /inAppNotifications\s*=\s*getPreferences\(\)\.getBoolean\("inAppNotifications", true\)/);
assert.match(extract(config, 'public static void toggleInAppNotifications()'), /putBoolean\("inAppNotifications", inAppNotifications\)\.apply\(\)/);
assert.match(settings, /asSwitchCG\(inAppNotificationsRow,[\s\S]*?\.setChecked\(NimarkoConfig.inAppNotifications\)/);
assert.match(settings, /if\s*\(NimarkoConfig.inAppNotifications\)\s*\{[\s\S]*?inAppNotificationsPreviewRow/);
assert.match(settings, /item.id == inAppNotificationsRow[\s\S]*?toggleInAppNotifications\(\)[\s\S]*?NimarkoInAppNotifications.dismiss\(\)/);
assert.match(settings, /item.id == inAppNotificationsPreviewRow[\s\S]*?NimarkoInAppNotifications.preview\(\)/);
assert.match(extract(settings, 'public org.telegram.ui.Components.AnimatedLinearLayout getInAppNotificationPanel()'),
    /verticalPadding\s*=\s*AndroidUtilities\.dp\(7\)[\s\S]*?setPadding\(panel\.getPaddingLeft\(\), verticalPadding, panel\.getPaddingRight\(\), 0\)/);
const row = settings.match(/inAppNotificationsRow\s*=\s*(\d+)/);
assert(row, 'settings row ID');
assert(new RegExp(`SCREEN_GENERAL,\\s*${row[1]},\\s*R.string.NM_InAppNotifications,`).test(search), 'search routes to actual settings row');
for (const locale of ['values', 'values-ru', 'values-zh-rCN']) {
    const xml = read(`res/${locale}/strings_nimarko.xml`);
    for (const key of ['NM_InAppNotifications', 'NM_InAppNotificationsDesc', 'NM_InAppNotificationsPreview', 'NM_InAppNotificationsSample']) {
        const matches = [...xml.matchAll(new RegExp(`<string\\s+name="${key}"[^>]*>([\\s\\S]*?)<\\/string>`, 'g'))];
        assert.equal(matches.length, 1, `${locale}: exactly one ${key}`);
        assert(matches[0][1].trim().length > 0, `${locale}: nonempty ${key}`);
        if (locale === 'values-ru') assert(/[А-Яа-яЁё]/.test(matches[0][1]), `${key}: Russian translation`);
        if (locale === 'values-zh-rCN') assert(/[\u3400-\u9fff]/.test(matches[0][1]), `${key}: Chinese translation`);
    }
}
console.log('PASS: source contracts (swipe latching + negative control, decor/privacy/cleanup, lifecycle hooks, native policy, settings/search, 3 languages)');

const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-in-app-notifications-'));
try {
    const file = path.join(dir, 'InAppNotificationsTest.java');
    function compile(code) {
        fs.writeFileSync(file, code);
        const result = cp.spawnSync('javac', ['-encoding', 'UTF-8', '-d', dir, file], {encoding: 'utf8'});
        assert.equal(result.status, 0, `javac failed (JDK required): ${result.error || result.stderr}`);
    }
    function run() {
        return cp.spawnSync('java', ['-cp', dir, 'InAppNotificationsTest'], {encoding: 'utf8'});
    }
    compile(java);
    const result = run();
    assert.equal(result.status, 0, result.error || result.stderr);
    console.log(result.stdout.trim());
    // Mutate only temporary harnesses, never production. Compilation must succeed;
    // an assertion with the expected reason proves each test detects the regression.
    for (const [name, from, to, reason] of [
        ['topic fallback', 'dialogId, topicId, true)', 'dialogId, 0L, true)', 'topic fallback/override'],
        ['UID ownership', 'UserConfig.getInstance(account).getClientUserId() != owner', 'false', 'UID mismatch'],
        ['stale tap', 'if (banner != this) return;', '', 'late tap on replaced notification'],
        ['new login cleanup', '&& banner.loginSession < nextSession', '', 'delayed logout preserves new login'],
    ]) {
        assert(java.includes(from), `negative-control target: ${name}`);
        compile(java.replace(from, to));
        const broken = run();
        assert.notEqual(broken.status, 0, `${name} regression must fail`);
        assert(broken.stderr.includes(reason), `${name} must fail for expected assertion: ${broken.stderr}`);
    }
    console.log('PASS: compiled negative controls reject privacy, ownership, stale-tap and login-cleanup regressions');
} finally {
    fs.rmSync(dir, {recursive: true, force: true});
}
