const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const cp = require('node:child_process');
const assert = require('node:assert/strict');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/java/app/nimarkogram/messenger/infocards/ProxyCard.java'), 'utf8');
const start = source.indexOf('private static boolean isOwnBypass(');
const end = source.indexOf('\n    @Override', start);
assert(start > 0 && end > start);
assert(!source.includes('protected int fillColorOverride()'));
const java = `public class ProxyPingTest {
 static class ProxySettings {
  String host="remote";int port=443;
  boolean isValid(){return true;}String getAddress(){return host;}int getPort(){return port;}
 }
 static class SharedConfig {static class ProxyInfo {
  ProxySettings settings=new ProxySettings();boolean checking,available;long ping,availableCheckTime;
 }}
 static class SystemClock {static long now=1000;static long elapsedRealtime(){return now;}}
 static class AndroidUtilities {static void runOnUIThread(Runnable r){r.run();}}
 static class WsBypassCore {static final String LOCAL_PROXY_HOST="127.0.0.1";}
 static class NimarkoWsBypassConfig {static int localPort=12345;}
 static class ConnectionsManager {
  static int calls,live;static java.util.function.LongConsumer callback;
  static ConnectionsManager getInstance(int a){return new ConnectionsManager();}
  static int native_getCurrentMainPingTime(int a){return live;}
  void checkProxy(ProxySettings s,java.util.function.LongConsumer c){calls++;callback=c;}
 }
 static class NotificationCenter {
  static int proxyCheckDone=1,events;
  static NotificationCenter getGlobalInstance(){return new NotificationCenter();}
  void postNotificationName(int id,Object p){events++;}
 }
 int observedAccount;
 ${source.slice(start, end)}
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  ProxyPingTest card=new ProxyPingTest();SharedConfig.ProxyInfo p=new SharedConfig.ProxyInfo();
  card.kickProxyCheck(p,false);check(ConnectionsManager.calls==1&&p.checking);
  card.kickProxyCheck(p,true);check(ConnectionsManager.calls==1);
  ConnectionsManager.callback.accept(-1);check(!p.checking&&p.ping==0);
  SystemClock.now+=5000;card.kickProxyCheck(p,false);check(ConnectionsManager.calls==1);
  SystemClock.now+=10000;card.kickProxyCheck(p,false);check(ConnectionsManager.calls==2);
  card.observedAccount=1;ConnectionsManager.callback.accept(125);check(p.ping==125&&p.available);
  SystemClock.now+=15000;card.kickProxyCheck(p,false);check(ConnectionsManager.calls==2);
  card.kickProxyCheck(p,true);check(ConnectionsManager.calls==3);
  p.settings=new ProxySettings();ConnectionsManager.callback.accept(999);check(p.ping==125);
  p.settings.host=WsBypassCore.LOCAL_PROXY_HOST;p.settings.port=NimarkoWsBypassConfig.localPort;
  ConnectionsManager.live=42;card.kickProxyCheck(p,false);check(displayPing(p,1)==42&&ConnectionsManager.calls==3);
  ConnectionsManager.live=0;card.kickProxyCheck(p,false);check(displayPing(p,1)==0&&p.ping==125);
  p.ping=0;p.availableCheckTime=0;
  card.kickProxyCheck(p,false);check(ConnectionsManager.calls==3&&!p.checking&&p.ping==0);
  card.kickProxyCheck(p,true);check(ConnectionsManager.calls==3);
  ConnectionsManager.live=60;card.kickProxyCheck(p,false);check(displayPing(p,1)==60&&p.ping==0&&ConnectionsManager.calls==3);
  System.out.println("PASS: first check, in-flight deduplication, failed retry, reconnect, account/settings changes, local tunnel RTT; default card fill retained");
 }
}`;
const tmp = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-proxy-card-test-'));
try {
    fs.writeFileSync(path.join(tmp, 'ProxyPingTest.java'), java);
    cp.execFileSync('javac', ['ProxyPingTest.java'], {cwd: tmp});
    process.stdout.write(cp.execFileSync('java', ['ProxyPingTest'], {cwd: tmp, encoding: 'utf8'}));
} finally {
    fs.rmSync(tmp, {recursive: true, force: true});
}
