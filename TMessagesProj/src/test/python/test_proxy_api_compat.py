"""Run the production proxy model/dispatch on the JVM, with Android/JNI boundaries stubbed."""
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAVA = ROOT / "main/java"


def block(source, marker):
    start = source.index(marker)
    brace = source.index("{", start)
    depth = 1
    end = brace + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]


class ProxyCompatibilityTests(unittest.TestCase):
    @unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "JDK required")
    def test_production_model_and_dispatch(self):
        settings = (JAVA / "org/telegram/proxy/ProxySettings.java").read_text()
        settings = block(settings, "public final class ProxySettings")
        settings = settings.replace(block(settings, "public static ProxySettings fromUri"), "")
        settings = settings.replace("public final class", "public static final class", 1)
        shared = (JAVA / "org/telegram/messenger/SharedConfig.java").read_text()
        info = block(shared, "public static class ProxyInfo")
        conn = (JAVA / "org/telegram/tgnet/ConnectionsManager.java").read_text()
        dispatch = conn[conn.index("private static final ThreadLocal<ProxySettings> proxySettingsDispatch"):
                        conn.index("public static native void native_switchBackend")]
        dispatch = dispatch.replace("public static void setProxySettings(boolean enabled, String address",
                                    "public static void legacyBody(boolean enabled, String address", 1)
        legacy = block(conn, "private static ProxySettings legacyProxySettings")
        source = HARNESS.replace("// SETTINGS", settings).replace("// INFO", info)
        source = source.replace("// DISPATCH", dispatch + legacy).replace("@NonNull", "")
        with tempfile.TemporaryDirectory(prefix="proxy-compat-") as folder:
            path = Path(folder) / "ProxyCompatHarness.java"
            path.write_text(source)
            compile_result = subprocess.run(["javac", str(path)], capture_output=True, text=True)
            self.assertEqual(compile_result.returncode, 0, compile_result.stderr)
            result = subprocess.run(["java", "-cp", folder, "ProxyCompatHarness"], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertIn("PASS", result.stdout)

    def test_internal_consumers_use_reconciliation(self):
        paths = ["org/telegram/ui/ProxyListActivity.java", "org/telegram/ui/ProxySettingsActivity.java",
                 "org/telegram/messenger/ProxyRotationController.java",
                 "app/nimarkogram/messenger/infocards/ProxyCard.java",
                 "app/nimarkogram/messenger/wsbypass/ProxyApplier.java"]
        for path in paths:
            source = (JAVA / path).read_text()
            self.assertIsNone(re.search(r"\b(?:currentProxy|currentProxyInfo|proxyInfo|proxy|curr|p|info|localProxy)\.settings\b", source), path)
        rules = (ROOT.parent / "proguard-rules.pro").read_text()
        self.assertIn("-keep class org.telegram.messenger.SharedConfig$ProxyInfo { *; }", rules)
        self.assertIn("-keep class org.telegram.proxy.ProxySettings$Builder { *; }", rules)


HARNESS = r'''
import java.util.*;
import java.net.URLEncoder;
import java.io.UnsupportedEncodingException;
public class ProxyCompatHarness {
  static final int PROXY_SCHEMA_V2 = 2, PROXY_SCHEMA_V3 = 3;
  static class TextUtils { static boolean isEmpty(CharSequence s) { return s == null || s.length() == 0; } }
  static class SharedPreferences {
    Map<String, Object> values = new HashMap<>();
    String getString(String k, String d) { return (String) values.getOrDefault(k, d); }
    int getInt(String k, int d) { return (int) values.getOrDefault(k, d); }
    boolean contains(String k) { return values.containsKey(k); }
    class Editor {
      Editor putString(String k, String v) { values.put(k,v); return this; }
      Editor putInt(String k, int v) { values.put(k,v); return this; }
      Editor remove(String k) { values.remove(k); return this; }
    }
    Editor edit() { return new Editor(); }
  }
  static class OutputSerializedData {
    List<Object> values = new ArrayList<>();
    void writeString(String v) { values.add(v); }
    void writeInt32(int v) { values.add(v); }
    void writeInt64(long v) { values.add(v); }
  }
  static class InputSerializedData extends OutputSerializedData {
    int pos;
    String readString(boolean ignored) { return (String) values.get(pos++); }
    int readInt32(boolean ignored) { return (int) values.get(pos++); }
    long readInt64(boolean ignored) { return (long) values.get(pos++); }
  }
  // SETTINGS
  // INFO
  static class UserConfig { static final int MAX_ACCOUNT_COUNT=3; boolean isClientActivated(){return true;} }
  static class Controller { void checkPromoInfo(boolean force) {} }
  static class AccountInstance {
    static AccountInstance getInstance(int a) { return new AccountInstance(); }
    UserConfig getUserConfig(){ return new UserConfig(); }
    Controller getMessagesController(){ return new Controller(); }
  }
  static class WebProxyTransport {
    static int starts, stops;
    static boolean fail;
    static int start(String a, String s) { starts++; if(fail) throw new IllegalStateException("test"); return 7777; }
    static void stop(){ stops++; }
  }
  static int hooks;
  static boolean rewrite, nested;
  static List<String> applied = new ArrayList<>();
  static void native_setProxySettings(int a, String host, int port, String user, String pass, String secret) {
    applied.add(a+":"+host+":"+port+":"+user+":"+secret);
  }
  // Simulate a before-hook at the public legacy entry point without an ART dependency.
  public static void setProxySettings(boolean enabled, String address, int port, String user, String pass, String secret) {
    hooks++;
    if (nested) { nested=false; setProxySettings(true,"nested",1234,"","",""); }
    if (rewrite) { rewrite=false; legacyBody(true,"rewritten",8080,"u","p",""); }
    else legacyBody(enabled,address,port,user,pass,secret);
  }
  // DISPATCH
  static void check(boolean value, String label) { if (!value) throw new AssertionError(label); }
  public static void main(String[] args) throws Exception {
    ProxyInfo old = new ProxyInfo("local",1080,"u","p","");
    check(old.getSettings().getType()==ProxySettings.Type.SOCKS5 && old.username.equals("u"), "legacy constructor");
    old.port=9090; old.address="changed";
    check(old.getSettings().getPort()==9090 && old.getSettings().getAddress().equals("changed"), "legacy writes");
    old.secret="abc";
    check(old.getSettings().getType()==ProxySettings.Type.MTPROTO && old.username.isEmpty(), "legacy type conversion");
    old.secret=""; old.username=null; old.password=null;
    check(old.getSettings().getType()==ProxySettings.Type.SOCKS5 && old.username.equals(""), "null aliases normalized");
    ProxySettings web=ProxySettings.builder().setType(ProxySettings.Type.WEB).setAddress("web.test").setSecret("token").build();
    old.settings=web; old.address="stale concurrent alias";
    check(old.getSettings()==web && old.address.equals("web.test"), "modern replacement wins");
    old.address="new.web";
    check(old.getSettings().getType()==ProxySettings.Type.WEB && old.getSettings().getPort()==0, "web type preserved");
    old.setSettings(web);
    check(old.address.equals(web.getAddress()) && old.secret.equals("token"), "modern setter publishes aliases");
    InputSerializedData data=new InputSerializedData(); old.ping=42; old.availableCheckTime=123;
    old.toSerializedData(data);
    ProxyInfo restored=ProxyInfo.fromSerializedData(PROXY_SCHEMA_V3,data);
    check(restored.getSettings().equals(web) && restored.address.equals("web.test") && restored.ping==42, "serialization roundtrip");
    InputSerializedData legacyData=new InputSerializedData();
    legacyData.writeString("old"); legacyData.writeInt32(123); legacyData.writeString("u"); legacyData.writeString("p"); legacyData.writeString("");
    check(ProxyInfo.fromSerializedData(0,legacyData).username.equals("u"), "legacy stored list");
    old.setSettings(ProxySettings.builder().setAddress("original").setPort(5).build()); old.port=77;
    InputSerializedData changed=new InputSerializedData(); old.toSerializedData(changed);
    check(ProxyInfo.fromSerializedData(PROXY_SCHEMA_V3,changed).port==77, "serialize reconciles legacy writes");
    check(old.getLink().contains("port=77"), "legacy link API");
    SharedPreferences prefs=new SharedPreferences(); web.toSharedPreferences(prefs.edit());
    check(ProxySettings.fromSharedPreferences(prefs).equals(web), "web preferences roundtrip");
    prefs.edit().putString("proxy_ip","local").putInt("proxy_port",9000).putString("proxy_secret","").putString("proxy_user","u");
    check(ProxySettings.fromSharedPreferences(prefs).getType()==ProxySettings.Type.SOCKS5, "stale web type after legacy write");
    prefs.edit().putInt("proxy_type",1);
    check(ProxySettings.fromSharedPreferences(prefs).getUser().equals("u"), "stale mtproto type after legacy write");
    setProxySettings(true,web);
    check(hooks==1 && WebProxyTransport.starts==1 && applied.size()==3, "modern web dispatch once for all accounts");
    check(applied.get(0).equals("0:127.0.0.1:7777::token"), "web transport preserved");
    check(proxySettingsDispatch.get()==null, "dispatch scope cleared");
    setProxySettings(true,"legacy",1080,"u","p","");
    check(hooks==2 && applied.get(3).equals("0:legacy:1080:u:"), "old setter preserved");
    rewrite=true; setProxySettings(true,web);
    check(applied.get(6).equals("0:rewritten:8080:u:") && WebProxyTransport.starts==1, "legacy hook can override modern request");
    nested=true; setProxySettings(true,web);
    check(applied.get(9).contains("nested") && applied.get(12).contains("7777"), "nested legacy hook preserves outer WEB request");
    setProxySettings(false,web);
    check(applied.get(15).equals("0::1080::"), "disable clears all account routes");
    setProxySettings(true,(ProxySettings)null);
    check(applied.get(18).equals("0::1080::"), "null modern settings disable safely");
    WebProxyTransport.fail=true;
    try {setProxySettings(true,web); throw new AssertionError("expected failure");} catch(IllegalStateException expected){}
    check(proxySettingsDispatch.get()==null,"exception clears dispatch scope");
    Thread t=new Thread(()->check(proxySettingsDispatch.get()==null,"isolated threads")); t.start(); t.join();
    System.out.println("PASS");
  }
}
'''


if __name__ == "__main__":
    unittest.main()
