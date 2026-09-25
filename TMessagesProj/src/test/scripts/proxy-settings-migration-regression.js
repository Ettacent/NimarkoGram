// Host-only production-Java regression harness. Run with node; requires javac/java.
// Android preferences, native networking and WebView transport are test doubles.
const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const cp = require('node:child_process');
const assert = require('node:assert/strict');
const root = path.resolve(__dirname, '../../main/java');
const read = p => fs.readFileSync(path.join(root, p), 'utf8');
const applier = read('app/nimarkogram/messenger/wsbypass/ProxyApplier.java');
const cm = read('org/telegram/tgnet/ConnectionsManager.java');
function block(source, marker) {
    const start = source.indexOf(marker);
    assert(start >= 0, `Missing production block: ${marker}`);
    const tokens = /\/\/[^\n]*|\/\*[\s\S]*?\*\/|"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'|[{}]/g;
    tokens.lastIndex = source.indexOf('{', start);
    let depth = 0;
    for (let token; (token = tokens.exec(source));) {
        if (token[0] === '{') depth++;
        if (token[0] === '}' && --depth === 0) return source.slice(start, tokens.lastIndex);
    }
    throw new Error(`Unclosed production block: ${marker}`);
}
const snapshotMethods = [
    'private static final class ProxySnapshot',
    'private static synchronized void captureSnapshotIfMissing(',
    'private static boolean loadPersistedSnapshot(',
    'private static ProxySnapshot readSnapshot(',
    'private static void persistSnapshot(',
    'private static void writeSnapshot(',
    'private static synchronized boolean restoreSnapshot(',
    'private static boolean applyToAllAccounts(',
    'private static ProxySettings localSettings(',
    'private static boolean isApplyVerified(boolean enable, ProxySettings expected) {',
    'private static boolean isApplyVerified(boolean enable, ProxySettings expected,',
].map(m => block(applier, m)).join('\n');
const connectionMethods = [
    'private static ProxySettings legacyProxySettings(',
    'public long checkProxy(String address,',
    'public long checkProxy(ProxySettings settings,',
    'private void checkWebProxyInternal(',
    'public static void setProxySettings(boolean enabled, String address,',
    'public static void setProxySettings(boolean enabled, ProxySettings settings)',
    'private static void applyProxySettings(',
].map(m => block(cm, m)).join('\n');
const constants = applier.match(/private static final String SNAP_\w+ = "[^"]+";/g);
assert(constants && constants.length >= 10);
const files = {
    'org/telegram/utils/proxy/ProxySettings.java': read('org/telegram/utils/proxy/ProxySettings.java'),
    'android/util/Base64.java': `package android.util;
public class Base64 {
 public static final int URL_SAFE=8, NO_WRAP=2, NO_PADDING=1;
 public static byte[] decode(String s,int flags){return java.util.Base64.getUrlDecoder().decode(s);}
 public static String encodeToString(byte[] b,int flags){return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b);}
}`,
    'android/content/SharedPreferences.java': `package android.content;
public interface SharedPreferences {
 String getString(String k,String d); int getInt(String k,int d); boolean getBoolean(String k,boolean d);
 boolean contains(String k); Editor edit();
 interface Editor { Editor putString(String k,String v); Editor putInt(String k,int v);
 Editor putBoolean(String k,boolean v); Editor remove(String k); void apply(); boolean commit(); }
}`,
    'android/text/TextUtils.java': `package android.text;
public class TextUtils { public static boolean isEmpty(CharSequence s){return s==null||s.length()==0;} }`,
    'androidx/annotation/NonNull.java': `package androidx.annotation;
@java.lang.annotation.Target({java.lang.annotation.ElementType.TYPE_USE,java.lang.annotation.ElementType.PARAMETER,
java.lang.annotation.ElementType.METHOD,java.lang.annotation.ElementType.FIELD}) public @interface NonNull {}`,
    'org/telegram/messenger/AndroidUtilities.java': `package org.telegram.messenger;
public class AndroidUtilities { public static boolean checkHostForPunycode(String s){return false;} }`,
    'android/net/Uri.java': `package android.net;
public class Uri {
 private final java.net.URI uri; private Uri(String s){uri=java.net.URI.create(s);}
 public static Uri parse(String s){return new Uri(s);} public String getScheme(){return uri.getScheme();}
 public String getHost(){return uri.getHost();} public String getPath(){return uri.getPath();}
 public String getQueryParameter(String key){String q=uri.getRawQuery();if(q==null)return null;
 for(String part:q.split("&")){String[] pair=part.split("=",2);if(pair[0].equals(key))
 return java.net.URLDecoder.decode(pair.length==2?pair[1]:"",java.nio.charset.StandardCharsets.UTF_8);}return null;}
 public String toString(){return uri.toString();}
}`,
    'ProxyMigrationHostTest.java': `
import java.util.*;
import android.content.SharedPreferences;
import android.text.TextUtils;
import org.telegram.utils.proxy.ProxySettings;
public class ProxyMigrationHostTest {
 ${constants.join('\n')}
 static final Object PROXY_LIST_LOCK=new Object();
 static ProxySnapshot snapshot; static boolean vpnSuspended,vpn;
 static boolean isSystemVpnActive(){return vpn;} static void setVpnSuspended(boolean value){vpnSuspended=value;}
 static class NimarkoWsBypassConfig {static boolean suspendOnVpn=true;static int localPort=10888;}
 static class FileLog {static void e(Object... ignored){}}
 static class Prefs implements SharedPreferences {
  final Map<String,Object> values=new HashMap<>();
  public String getString(String k,String d){return (String)values.getOrDefault(k,d);}
  public int getInt(String k,int d){return (Integer)values.getOrDefault(k,d);}
  public boolean getBoolean(String k,boolean d){return (Boolean)values.getOrDefault(k,d);}
  public boolean contains(String k){return values.containsKey(k);}
  public Editor edit(){return new Editor(){
   final Map<String,Object> pending=new HashMap<>();
   public Editor putString(String k,String v){pending.put(k,v);return this;}
   public Editor putInt(String k,int v){pending.put(k,v);return this;}
   public Editor putBoolean(String k,boolean v){pending.put(k,v);return this;}
   public Editor remove(String k){pending.put(k,null);return this;}
   public void apply(){for(Map.Entry<String,Object> e:pending.entrySet()){
    if(e.getValue()==null)values.remove(e.getKey());else values.put(e.getKey(),e.getValue());}}
   public boolean commit(){apply();return true;}
  };}
 }
 static class MessagesController {static Prefs prefs=new Prefs();
  static SharedPreferences getGlobalMainSettings(){return prefs;}void checkPromoInfo(boolean ignored){} }
 static class SharedConfig {
  static class ProxyInfo {ProxySettings settings;ProxyInfo(ProxySettings s){settings=s;}
   ProxySettings getSettings(){return settings;}}
  static ProxyInfo currentProxy;static ArrayList<ProxyInfo> proxyList=new ArrayList<>();
  static void loadProxyList(){} static void saveProxyList(){} static void saveConfig(){}
  static ProxyInfo addProxy(ProxyInfo info){for(ProxyInfo p:proxyList)if(p.settings.equals(info.settings))return p;
   proxyList.add(info);return info;}
 }
 static class UserConfig {static final int MAX_ACCOUNT_COUNT=4;static UserConfig getInstance(int a){return new UserConfig();}
  boolean isClientActivated(){return true;} }
 static class AccountInstance {static AccountInstance getInstance(int a){return new AccountInstance();}
  UserConfig getUserConfig(){return new UserConfig();}MessagesController getMessagesController(){return new MessagesController();}}
 interface RequestTimeDelegate {void run(long time);}
 interface TestImplementation {void doCheck(ProxySettings settings,int port,RequestTimeDelegate callback);}
 static class WebProxyConnectionTester {
  static final WebProxyConnectionTester INSTANCE=new WebProxyConnectionTester();static Runnable pending;
  static WebProxyConnectionTester getInstance(){return INSTANCE;}
  void checkProxy(ProxySettings s,RequestTimeDelegate d,TestImplementation impl){pending=()->impl.doCheck(s,23001,d);}
 }
 static class WebProxyTransport {static int starts,stops;static String host;
  static int start(String h,String secret){starts++;host=h;return 23001;}static void stop(){stops++;}}
 static class ConnectionsManager {
  private static final ThreadLocal<ProxySettings> proxySettingsDispatch = new ThreadLocal<>();
  int currentAccount;static int applies,checks,reconnects;static boolean failApply;
  static String lastAddress,lastSecret;static int lastPort;
  static ConnectionsManager getInstance(int a){ConnectionsManager c=new ConnectionsManager();c.currentAccount=a;return c;}
  void checkConnection(){reconnects++;}
  static void native_setProxySettings(int a,String host,int port,String user,String pass,String secret){
   if(failApply)throw new IllegalStateException("injected native failure");applies++;lastAddress=host;lastPort=port;lastSecret=secret;}
  static long native_checkProxy(int a,String host,int port,String user,String pass,String secret,RequestTimeDelegate d){
   checks++;lastAddress=host;lastPort=port;lastSecret=secret;d.run(42);return 77;}
  ${connectionMethods}
 }
 ${snapshotMethods}
 static int assertions;
 static void check(boolean value,String message){assertions++;if(!value)throw new AssertionError(message);}
 static final String SECRET="0123456789abcdef0123456789abcdef";
 static ProxySettings proxy(ProxySettings.Type type){return ProxySettings.builder().setType(type)
  .setAddress("relay.example.org").setPort(443).setUser("alice").setPassword("pass").setSecret(SECRET).build();}
 static void reset(){snapshot=null;vpn=false;vpnSuspended=false;MessagesController.prefs=new Prefs();
  SharedConfig.currentProxy=null;SharedConfig.proxyList.clear();ConnectionsManager.applies=0;
  ConnectionsManager.reconnects=0;ConnectionsManager.failApply=false;WebProxyTransport.starts=0;WebProxyTransport.stops=0;}
 static void save(ProxySettings settings,boolean enabled){SharedPreferences.Editor ed=MessagesController.prefs.edit();
  settings.toSharedPreferences(ed);ed.putBoolean("proxy_enabled",enabled).apply();}
 public static void main(String[] args){
  ArrayList<ProxySettings> modes=new ArrayList<>();modes.add(ProxySettings.EMPTY);
  for(ProxySettings.Type type:ProxySettings.Type.values())modes.add(proxy(type));
  for(ProxySettings original:modes)for(boolean enabled:new boolean[]{false,true})for(boolean calls:new boolean[]{false,true}){
   if(enabled&&!original.isValid())continue;reset();save(original,enabled);
   MessagesController.prefs.edit().putBoolean("proxy_enabled_calls",calls).apply();
   captureSnapshotIfMissing("127.0.0.1");
   check(snapshot!=null&&snapshot.settings.equals(original),"capture uses typed settings including WEB with port zero");
   ProxySettings local=localSettings("127.0.0.1",10888,SECRET);save(local,true);
   SharedConfig.currentProxy=SharedConfig.addProxy(new SharedConfig.ProxyInfo(local));
   snapshot=null; // Simulated process restart must read the persisted snapshot.
   check(restoreSnapshot(),"restore succeeds for every valid enabled/disabled mode");
   check(original.equals(ProxySettings.fromSharedPreferences(MessagesController.prefs)),"restore exact settings");
   check(MessagesController.prefs.getBoolean("proxy_enabled",!enabled)==enabled,"restore enabled flag");
   for(String key:new String[]{"proxy_enabled_calls","proxy_calls_enabled","calls_use_proxy"})
    check(MessagesController.prefs.getBoolean(key,!calls)==calls,"restore calls flag "+key);
   check(snapshot==null&&!MessagesController.prefs.contains(SNAP_PRESENT),"successful restore retires snapshot");
   check(!MessagesController.prefs.contains(SNAP_TYPE),"snapshot type retired too");
   check(ConnectionsManager.applies==4&&ConnectionsManager.reconnects==4,"all accounts applied and rechecked");
   if(original.isValid())check(SharedConfig.currentProxy.settings.equals(original),"disabled selection preserved too");
   if(enabled&&original.getType()==ProxySettings.Type.WEB){
    check(WebProxyTransport.starts==1&&WebProxyTransport.host.equals(original.getAddress()),"WEB transport started for saved identity");
    check(ConnectionsManager.lastAddress.equals("127.0.0.1")&&ConnectionsManager.lastPort==23001,"native uses transient WEB port");
    check(ProxySettings.fromUri(android.net.Uri.parse(original.getLink())).equals(original),"WEB link identity roundtrip");
   }else check(WebProxyTransport.starts==0&&WebProxyTransport.stops==1,"non-WEB/direct stops WEB carrier");
  }
  for(String secret:new String[]{"",SECRET}){
   reset();MessagesController.prefs.edit().putBoolean(SNAP_PRESENT,true).putBoolean(SNAP_ENABLED,true)
    .putString(SNAP_HOST,"old.example.org").putInt(SNAP_PORT,1080).putString(SNAP_USER,"alice")
    .putString(SNAP_PASS,"pass").putString(SNAP_SECRET,secret).putInt("proxy_type",2).apply();
   check(loadPersistedSnapshot(),"old snapshot loaded");
   check(snapshot.settings.getType()==(secret.isEmpty()?ProxySettings.Type.SOCKS5:ProxySettings.Type.MTPROTO),"old type inferred independent of live WEB type");
   check(restoreSnapshot(),"legacy snapshot restores");
   check(ConnectionsManager.lastAddress.equals("old.example.org")&&ConnectionsManager.lastPort==1080,"legacy endpoint restored");
   if(secret.isEmpty())check(SharedConfig.currentProxy.settings.getUser().equals("alice"),"legacy SOCKS credentials preserved");
  }
  reset();check(!loadPersistedSnapshot(),"missing snapshot absent");
  ProxySettings local=localSettings("127.0.0.1",10888,SECRET);
  check(local.getType()==ProxySettings.Type.MTPROTO,"bypass is explicit MTProto");
  SharedConfig.currentProxy=SharedConfig.addProxy(new SharedConfig.ProxyInfo(local));save(local,true);
  for(int type:new int[]{0,2}){
   MessagesController.prefs.edit().putInt("proxy_type",type).apply();
   check(isApplyVerified(true,local),"legacy scalar selection overrides stale type for compatibility");
   save(local,true);check(isApplyVerified(true,local),"typed bypass write repairs stale type");
   check(MessagesController.prefs.getInt("proxy_type",-1)==ProxySettings.typeToInt(local.getType()),"typed write persists canonical type");
  }
  SharedConfig.proxyList.clear();check(!isApplyVerified(true,local),"missing list identity rejected");
  reset();save(proxy(ProxySettings.Type.WEB),true);captureSnapshotIfMissing("127.0.0.1");save(local,true);
  vpn=true;check(!restoreSnapshot()&&snapshot!=null,"VPN retains prior WEB snapshot");
  vpn=false;ConnectionsManager.failApply=true;
  check(!restoreSnapshot()&&snapshot!=null&&MessagesController.prefs.contains(SNAP_PRESENT),"failed native apply retains snapshot");
  ConnectionsManager.failApply=false;check(restoreSnapshot(),"retained WEB snapshot can be retried");
  reset();ConnectionsManager manager=ConnectionsManager.getInstance(2);long[] result={-99};
  check(manager.checkProxy((ProxySettings)null,t->result[0]=t)==0&&result[0]==-99,"invalid check does not invoke delegate");
  check(manager.checkProxy("127.0.0.1",10888,null,null,SECRET,t->result[0]=t)==77&&result[0]==42,"legacy check forwards native id and callback");
  check(ConnectionsManager.lastSecret.equals(SECRET),"legacy secret retained");
  result[0]=-99;int nativeChecks=ConnectionsManager.checks;
  check(manager.checkProxy(proxy(ProxySettings.Type.WEB),t->result[0]=t)==0,"WEB check returns zero without synchronous failure");
  check(result[0]==-99&&ConnectionsManager.checks==nativeChecks,"WEB check is deferred");
  WebProxyConnectionTester.pending.run();check(result[0]==42,"WEB callback later completes");
  check(ConnectionsManager.lastAddress.equals("127.0.0.1")&&ConnectionsManager.lastPort==23001,"WEB native check uses local transport");
  ConnectionsManager.setProxySettings(true,"127.0.0.1",10888,null,null,SECRET);
  check(ConnectionsManager.applies==4&&ConnectionsManager.lastSecret.equals(SECRET),"legacy setter applies all accounts with inferred MTProto");
  check(ConnectionsManager.legacyProxySettings(null,1080,null,null,null).getType()==ProxySettings.Type.SOCKS5,"null legacy secret maps to SOCKS");
  System.out.println("PASS: "+assertions+" assertions; actual ProxySettings, snapshot capture/persistence/restore, legacy migration, type verification, multiaccount and check dispatch");
 }
}`,
};
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-proxy-migration-'));
try {
    for (const [name, source] of Object.entries(files)) {
        const file = path.join(dir, name);
        fs.mkdirSync(path.dirname(file), {recursive: true});
        fs.writeFileSync(file, source);
    }
    cp.execFileSync('javac', ['-d', dir, ...Object.keys(files).map(name => path.join(dir, name))], {stdio: 'pipe'});
    const result = cp.spawnSync('java', ['-cp', dir, 'ProxyMigrationHostTest'], {encoding: 'utf8', timeout: 30000});
    assert.equal(result.status, 0, result.stderr || String(result.error));
    console.log(result.stdout.trim());
} finally {
    fs.rmSync(dir, {recursive: true, force: true});
}
