"""Exercise production QR loader logic with deterministic queues/network/prefs.

TL codecs and Android services are doubles, not an APK or real wire roundtrip.
The separate source-contract test verifies use of the repository's TL codec.
"""
import unittest

from test_qr_render_pipeline import QR, PATH, run_java
from test_recording_composer_lifecycle import method


class QrThemeLoaderTests(unittest.TestCase):
    def test_tl_schema_and_missing_color_contract(self):
        root = PATH.parents[2]
        controller = (root / "telegram/messenger/ChatThemeController.java").read_text()
        tlrpc = (root / "telegram/tgnet/TLRPC.java").read_text()
        self.assertIn("public static TL_theme TLdeserialize(InputSerializedData stream", tlrpc)
        read = method(QR, "private static QrThemes readQrThemes(")
        write = method(QR, "private static void saveQrThemes(")
        for token in ("TLRPC.Theme.TLdeserialize", "Utilities.hexToBytes", '"theme_" + i', '"count"', '"lastReload"', '"hash"'):
            self.assertIn(token, read)
            self.assertIn(token, controller)
        self.assertIn("theme.serializeToStream(data)", write)
        self.assertIn("Utilities.bytesToHex(data.toByteArray())", write)
        self.assertNotIn('getSharedPreferences("chatthemeconfig_', write)
        self.assertIn('getSharedPreferences("qrthemes_" + key', write)
        self.assertIn("data.cleanup()", read)
        self.assertIn("data.cleanup()", write)
        getter = method(QR, "public int getColor(int key)")
        run_java(r'''
public class Harness {
 static class SparseIntArray {
  int indexOfKey(int k){return k==1?0:-1;} int valueAt(int i){return 0;}
 }
 static class Theme {static int getColor(int key){return 123;}}
 SparseIntArray colors;
 GETTER
 public static void main(String[] args){
  Harness h=new Harness();if(h.getColor(1)!=123)throw new AssertionError();
  h.colors=new SparseIntArray();if(h.getColor(1)!=0 || h.getColor(2)!=123)throw new AssertionError();
 }
}'''.replace("GETTER", getter))

    def test_production_cache_request_retirement_accounts_recreation_and_corrupt_prefs(self):
        methods = "\n".join(method(QR, s) for s in (
            "private static class QrThemes {", "private static class QrThemeSubscription {", "private static class QrThemeRequest {",
            "private void requestQrThemes(", "private void retireQrThemes(", "private static void finishQrThemeRequest(",
            "private static boolean addQrTheme(", "private static QrThemes readQrThemes(", "private static void saveQrThemes(",
            "private void publishQrThemes(",
        ))
        run_java(r'''
import java.util.*;
import java.lang.ref.WeakReference;
import java.util.concurrent.Executor;
public class Harness {
 static boolean worker; static void check(boolean b){if(!b)throw new AssertionError();}
 static class Queue {
  List<Runnable> tasks=new ArrayList<>();
  void postRunnable(Runnable r){tasks.add(r);}
  void flush(){while(!tasks.isEmpty()){worker=true;tasks.remove(0).run();worker=false;}}
 }
 static class Utilities {
  interface Callback2<A,B>{void run(A a,B b);}
  static Queue themeQueue=new Queue();
  static byte[] hexToBytes(String value){return new byte[]{(byte)Integer.parseInt(value)};}
  static String bytesToHex(byte[] bytes){return Integer.toString(bytes[0]&255);}
 }
 static class AndroidUtilities {
  static List<Runnable> tasks=new ArrayList<>();
  static void runOnUIThread(Runnable r){tasks.add(r);}
  static void flush(){while(!tasks.isEmpty())tasks.remove(0).run();}
 }
 static void drain(){Utilities.themeQueue.flush();AndroidUtilities.flush();}
 static class Context {static final int MODE_PRIVATE=0;}
 static class View {}
 static class UserConfig {
  static UserConfig[] users={new UserConfig(100),new UserConfig(200)};
  long uid;UserConfig(long id){uid=id;}
  static UserConfig getInstance(int a){return users[a];}
  long getClientUserId(){return uid;}
 }
 static class TextUtils {
  static boolean isEmpty(String s){return s==null || s.isEmpty();}
  static boolean equals(String a,String b){return Objects.equals(a,b);}
 }
 static class FileLog {static void e(Throwable e){}}
 static class Toast {static final int LENGTH_SHORT=0;static int shown;
  static Toast makeText(Object c,String s,int d){check(!worker);return new Toast();}void show(){shown++;}}
 static class SharedPreferences {
  Map<String,Object> values=new HashMap<>();
  Map<String,?> getAll(){check(worker);return new HashMap<>(values);}
  Editor edit(){check(worker);return new Editor();}
  class Editor {
   Map<String,Object> next=new HashMap<>(values);
   Editor clear(){next.clear();return this;}
   Editor putLong(String k,long v){next.put(k,v);return this;}
   Editor putInt(String k,int v){next.put(k,v);return this;}
   Editor putString(String k,String v){next.put(k,v);return this;}
   void apply(){check(worker);values=next;}
  }
 }
 static class App {
  Map<String,SharedPreferences> files=new HashMap<>();
  SharedPreferences getSharedPreferences(String name,int mode){check(worker);return files.computeIfAbsent(name,k->new SharedPreferences());}
 }
 static class ApplicationLoader {static App applicationContext=new App();}
 static class SerializedData {
  static Map<Integer,TLRPC.TL_theme> wire=new HashMap<>();static int next=1;int id;
  SerializedData(int capacity){}SerializedData(byte[] b){id=b[0]&255;}
  int readInt32(boolean exception){if(!wire.containsKey(id))throw new IllegalArgumentException();return id;}
  byte[] toByteArray(){return new byte[]{(byte)id};}void cleanup(){}
 }
 static class TLRPC {
  static class TL_error {String text="offline";}
  static class Wallpaper {Object settings=new Object();}
  static class ThemeSettings {Object base_theme=new Object();Wallpaper wallpaper=new Wallpaper();}
  static class Theme {static TL_theme TLdeserialize(SerializedData d,int ctor,boolean exception){return SerializedData.wire.get(ctor);}}
  static class TL_theme extends Theme {
   long id;String emoticon;List<ThemeSettings> settings=new ArrayList<>();
   TL_theme(long id,String e){this.id=id;emoticon=e;settings.add(new ThemeSettings());settings.add(new ThemeSettings());}
   int getObjectSize(){return 1;}
   void serializeToStream(SerializedData d){d.id=SerializedData.next++;SerializedData.wire.put(d.id,this);}
  }
 }
 static class TL_account {
  static class getChatThemes {long hash;}
  static class TL_themes {long hash;List<TLRPC.TL_theme> themes=new ArrayList<>();}
  static class TL_themesNotModified {}
 }
 static class ConnectionsManager {
  static ConnectionsManager[] all={new ConnectionsManager(),new ConnectionsManager()};
  static class Request {Executor executor;Utilities.Callback2<Object,TLRPC.TL_error> callback;long hash;}
  int sends,cancels;Map<Integer,Request> requests=new HashMap<>();
  int sendRequestTyped(TL_account.getChatThemes q,Executor ex,Utilities.Callback2<Object,TLRPC.TL_error> cb){
   check(worker);Request r=new Request();r.executor=ex;r.callback=cb;r.hash=q.hash;requests.put(++sends,r);return sends;
  }
  void cancelRequest(int id,boolean force){check(worker);cancels++;}
  void complete(int id,Object response,TLRPC.TL_error error){Request r=requests.get(id);r.executor.execute(()->r.callback.run(response,error));}
 }
 static class EmojiThemes {
  String emoji;EmojiThemes(String e){emoji=e;}EmojiThemes(int account,TLRPC.TL_theme t,boolean stub){check(worker);emoji=t.emoticon;}
 }
 static class QrActivity {
  static final long THEME_RELOAD_MS=7200000;
  static final Map<String,QrThemes> qrThemesCache=new HashMap<>();
  static final HashMap<String,QrThemeRequest> qrThemeRequests=new HashMap<>();
  int currentAccount,deliveries;View fragmentView=new View();Object themesViewController=new Object();
  EmojiThemes homeTheme=new EmojiThemes("home");QrThemeSubscription themeSubscription;List<EmojiThemes> delivered;
  QrActivity(int a){currentAccount=a;}
  ConnectionsManager getConnectionsManager(){check(!worker);return ConnectionsManager.all[currentAccount];}
  Object getParentActivity(){return this;}
  void onDataLoaded(List<EmojiThemes> themes){check(!worker);deliveries++;delivered=themes;}
  METHODS
 }
 static SharedPreferences prefs(String name){return ApplicationLoader.applicationContext.files.computeIfAbsent(name,k->new SharedPreferences());}
 static void seed(String file,long hash,long time,TLRPC.TL_theme... themes){
  Map<String,Object> m=prefs(file).values;m.put("count",themes.length);m.put("hash",hash);m.put("lastReload",time);
  for(int i=0;i<themes.length;i++){SerializedData d=new SerializedData(1);themes[i].serializeToStream(d);m.put("theme_"+i,Utilities.bytesToHex(d.toByteArray()));}
 }
 static TL_account.TL_themes response(long hash,TLRPC.TL_theme... themes){TL_account.TL_themes r=new TL_account.TL_themes();r.hash=hash;r.themes.addAll(Arrays.asList(themes));return r;}
 public static void main(String[] args){
  TLRPC.TL_theme first=new TLRPC.TL_theme(1,"first"),second=new TLRPC.TL_theme(2,"second");
  seed("chatthemeconfig_0",77,1,second,first); // server order is intentional
  QrActivity a=new QrActivity(0);a.requestQrThemes(a.fragmentView);drain();
  ConnectionsManager net=ConnectionsManager.all[0];
  check(net.sends==1 && net.requests.get(1).hash==77 && a.delivered.size()==3 && a.delivered.get(1).emoji.equals("second"));
  QrActivity b=new QrActivity(0);b.requestQrThemes(b.fragmentView);drain();check(net.sends==1);
  a.retireQrThemes();drain();check(net.cancels==0);
  net.complete(1,response(88,first,second),null);drain();check(a.deliveries==1 && b.deliveries==2);
  check((long)prefs("chatthemeconfig_0").values.get("hash")==77); // controller state never written
  check((long)prefs("qrthemes_0:100").values.get("hash")==88);
  QrActivity c=new QrActivity(0);c.requestQrThemes(c.fragmentView);drain();check(net.sends==1 && c.delivered.size()==3);
  QrActivity.qrThemesCache.clear(); // process-cold, persisted warm path
  c.retireQrThemes();c.fragmentView=new View();c.requestQrThemes(c.fragmentView);drain();check(net.sends==1);
  QrActivity other=new QrActivity(1);other.requestQrThemes(other.fragmentView);drain();
  check(ConnectionsManager.all[1].sends==1 && other.delivered.size()==1);
  other.retireQrThemes();drain();check(ConnectionsManager.all[1].cancels==1);
  ConnectionsManager.all[1].complete(1,response(55,first),null);drain();check(!prefs("qrthemes_1:200").values.containsKey("count"));
  // Retire before the background task even starts: no extra API call.
  other=new QrActivity(1);other.requestQrThemes(other.fragmentView);other.retireQrThemes();drain();check(ConnectionsManager.all[1].sends==1);
  // Account-slot reuse while a response is in flight must not cache/publish it.
  other=new QrActivity(1);other.requestQrThemes(other.fragmentView);drain();int delivered=other.deliveries;
  UserConfig.users[1].uid=201;ConnectionsManager.all[1].complete(2,response(66,second),null);drain();
  check(other.deliveries==delivered && !prefs("qrthemes_1:200").values.containsKey("count"));
  // Corrupt/duplicate entries force hash=0 but don't lose later good entries.
  seed("bad",999,System.currentTimeMillis(),first,first,second);
  prefs("bad").values.put("theme_1","malformed");
  worker=true;QrActivity.QrThemes recovered=QrActivity.readQrThemes("bad");worker=false;
  check(recovered.hash==0 && recovered.loadedAt==0 && recovered.themes.size()==2);
  seed("duplicates",999,1,first,first,second);worker=true;recovered=QrActivity.readQrThemes("duplicates");worker=false;
  check(recovered.hash==0 && recovered.themes.size()==2);
  TLRPC.TL_theme broken=new TLRPC.TL_theme(3,"broken");broken.settings.get(1).wallpaper=null;
  check(!QrActivity.addQrTheme(recovered,broken));
  // Close after network delivery queued UI: the UI publication is still retired.
  other=new QrActivity(1);other.requestQrThemes(other.fragmentView);Utilities.themeQueue.flush();other.retireQrThemes();drain();check(other.deliveries==0);
  // A late old request must not retire a replacement request under the same key.
  QrActivity.QrThemeRequest old=new QrActivity.QrThemeRequest(),replacement=new QrActivity.QrThemeRequest();
  QrActivity.qrThemeRequests.put("x",replacement);QrActivity.finishQrThemeRequest("x",old,recovered,null);check(QrActivity.qrThemeRequests.get("x")==replacement);
 }
}'''.replace("METHODS", methods))


if __name__ == "__main__":
    unittest.main()
