"""Host-only regression tests for shared-file staging; no Android/Gradle build."""
from pathlib import Path
import shutil
import unittest

from test_search_fallback_entrance import block
from test_sender_infocard_transitions import run_java

JAVA = Path(__file__).resolve().parents[2] / "main/java"
LAUNCH = JAVA / "org/telegram/ui/LaunchActivity.java"
MEDIA = JAVA / "org/telegram/messenger/MediaController.java"


@unittest.skipUnless(shutil.which("javac") and shutil.which("java"), "JDK required")
class SharedFileStagingTests(unittest.TestCase):
    def test_copy_cleanup_limit_cancellation_and_atomic_names(self):
        source = MEDIA.read_text()
        body = block(source, "public static String copyFileToCache(Uri uri, String ext, long sizeLimit, java.util.function.BooleanSupplier cancelled)")
        self.assertIn("long totalLen", body)
        run_java(r'''
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.lang.reflect.Method;
public class Transitions {
 static File directory;
 static CountDownLatch concurrent;
 static class Uri {
  InputStream stream; Uri(InputStream s){stream=s;}
  static Uri fromFile(File f){return new Uri(null);}
 }
 static class Resolver {InputStream openInputStream(Uri u) throws Exception {
  if(concurrent!=null){concurrent.countDown();if(!concurrent.await(5,TimeUnit.SECONDS))throw new IOException("timeout");}
  return u.stream;
 }}
 static class Context {Resolver getContentResolver(){return new Resolver();}}
 static class ApplicationLoader {static Context applicationContext=new Context();}
 static class FileLoader {static String fixFileName(String s){return s;}}
 static class SharedConfig {static int getLastLocalId(){return 1;}static void saveConfig(){}}
 static class AndroidUtilities {
  static File getSharingDirectory(){return directory;}
  static boolean isInternalUri(Uri u){return false;}
  static boolean isInternalUri(int fd){return false;}
 }
 static class FileLog {static void e(Throwable ignored){}}
 static String getFileName(Uri u){return "payload.bin";}
 BODY
 static void check(boolean b){if(!b)throw new AssertionError();}
 static int count(){return directory.list().length;}
 static Uri bytes(){return new Uri(new ByteArrayInputStream(new byte[]{1,2,3,4,5,6}));}
 public static void main(String[] args) throws Exception {
  directory=Files.createTempDirectory("share-copy-test-").toFile();
  try {
   File existing=new File(directory,"payload.bin");Files.write(existing.toPath(),new byte[]{9});
   String ok=copyFileToCache(bytes(),"file",6,()->false);
   check(ok!=null&&new File(ok).length()==6&&count()==2);
   check(Files.readAllBytes(existing.toPath())[0]==9);
   check(copyFileToCache(bytes(),"file",3,()->false)==null&&count()==2);
   check(copyFileToCache(bytes(),"file",-1,()->true)==null&&count()==2);
   AtomicBoolean closed=new AtomicBoolean();
   InputStream failing=new InputStream(){int reads;
    public int read(){return 0;}
    public int read(byte[] b)throws IOException{if(reads++>0)throw new IOException("injected");b[0]=42;return 1;}
    public void close(){closed.set(true);}
   };
   check(copyFileToCache(new Uri(failing),"file",-1,()->false)==null&&closed.get()&&count()==2);
   AtomicBoolean cancelled=new AtomicBoolean();
   InputStream cancelAtEof=new ByteArrayInputStream(new byte[]{1}){
    public synchronized int read(byte[] b){int n=super.read(b,0,b.length);if(n<0)cancelled.set(true);return n;}
   };
   check(copyFileToCache(new Uri(cancelAtEof),"file",-1,cancelled::get)==null&&count()==2);
   check(copyFileToCache(new Uri(null),"file",-1,()->false)==null&&count()==2);
   concurrent=new CountDownLatch(2);
   CompletableFuture<String> a=CompletableFuture.supplyAsync(()->copyFileToCache(bytes(),"file",-1,()->false));
   CompletableFuture<String> b=CompletableFuture.supplyAsync(()->copyFileToCache(bytes(),"file",-1,()->false));
   String pa=a.get(10,TimeUnit.SECONDS),pb=b.get(10,TimeUnit.SECONDS);
   check(pa!=null&&pb!=null&&!pa.equals(pb)&&new File(pa).length()==6&&new File(pb).length()==6&&count()==4);
  } finally {for(File f:directory.listFiles())f.delete();directory.delete();}
 }
}
'''.replace("BODY", body))

    def test_staging_handoff_and_obsolete_completion(self):
        body = block(LAUNCH.read_text(), "private void stageSharedFile(")
        run_java(r'''
import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
public class Transitions {
 static class Uri {public String toString(){return "content://test/payload";}}
 static class Queue {ArrayDeque<Runnable> pending=new ArrayDeque<>();
  void postRunnable(Runnable r){pending.add(r);}void run(){pending.remove().run();}}
 static Queue sharedFileCopyQueue=new Queue();
 static class Utilities {static Queue globalQueue=new Queue();}
 static class AndroidUtilities {
  static Queue ui=new Queue();static boolean locked;
  static void runOnUIThread(Runnable r){ui.postRunnable(r);}
  static boolean needShowPasscode(boolean b){return locked;}
 }
 static class SharedConfig {static boolean appLocked,isWaitingForPasscodeEnter;}
 static class View {boolean attached;Runnable pending;
  boolean isAttachedToWindow(){return attached;}
  void postDelayed(Runnable r,int delay){pending=r;}
  void removeCallbacks(Runnable r){if(pending==r)pending=null;}
  void attach(){attached=true;if(pending!=null){Runnable r=pending;pending=null;r.run();}}
 }
 static class Window {View decor=new View();View getDecorView(){return decor;}}
 Window window=new Window();Window getWindow(){return window;}
 interface Cancel {void run(Object ignored);}
 static class AlertDialog {static final int ALERT_TYPE_SPINNER=1;static AlertDialog last;
  Cancel cancel;boolean shown,dismissed;
  AlertDialog(Object context,int type){last=this;}
  void setOnCancelListener(Cancel c){cancel=c;}void show(){shown=true;}void dismiss(){dismissed=true;}
 }
 static class Toast {static final int LENGTH_SHORT=0;static int errors;
  static Toast makeText(Object c,String text,int len){return new Toast();}void show(){errors++;}}
 static class MediaController {static String path;static boolean opus;static int copies;
  static String copyFileToCache(Uri u,String ext,long limit,java.util.function.BooleanSupplier cancelled){
   copies++;return cancelled.getAsBoolean()?null:path;}
  static int isOpusFile(String p){return opus?1:0;}}
 AtomicLong navigationRequestGeneration=new AtomicLong(7);
 int currentAccount=2,opens;long recipient;
 boolean alive=true;
 ArrayList<Uri> documentsUrisArray=new ArrayList<>();
 ArrayList<String> documentsPathsArray,documentsOriginalPathsArray;
 String documentsMimeType="old",videoPath,voicePath;
 CharSequence sendingText="old";
 static CharSequence caption;
 boolean isNavigationRequestCurrent(int account,long generation){return alive&&account==currentAccount&&generation==navigationRequestGeneration.get();}
 void openSharedContent(int account,long dialogId){
  check(account==currentAccount);opens++;recipient=dialogId;
  // Opening the chooser changes the stack. It must not invalidate an already handed-off file.
  navigationRequestGeneration.incrementAndGet();
 }
 BODY
 static void check(boolean b){if(!b)throw new AssertionError();}
 static Transitions start(String mime){
  Transitions t=new Transitions();MediaController.path="/nonexistent-test-cache/payload";
  caption=new StringBuilder("caption");
  MediaController.opus=false;t.stageSharedFile(new Uri(),mime,caption,2,123,7);
  check(t.opens==0&&t.documentsUrisArray==null&&t.sendingText==null);
  return t;
 }
 static void finish(){sharedFileCopyQueue.run();AndroidUtilities.ui.run();}
 public static void main(String[] args){
  Transitions t=start("application/pdf");check(!AlertDialog.last.shown);t.window.decor.attach();check(AlertDialog.last.shown);
  finish();check(t.opens==1&&t.recipient==123&&t.sendingText==caption&&t.sendingText.toString().equals("caption"));
  check(t.documentsMimeType.equals("application/pdf")&&t.documentsPathsArray.get(0).equals(MediaController.path));
  check(t.documentsOriginalPathsArray.get(0).equals("content://test/payload")&&AlertDialog.last.dismissed);
  t=start("video/mp4");finish();t.window.decor.attach();check(!AlertDialog.last.shown&&t.videoPath!=null&&t.documentsPathsArray==null);
  t=start("audio/ogg; codecs=opus");MediaController.opus=true;finish();check(t.voicePath!=null&&t.videoPath==null);
  t=start("application/pdf");sharedFileCopyQueue.run();t.navigationRequestGeneration.incrementAndGet();AndroidUtilities.ui.run();
  check(t.opens==0&&t.documentsPathsArray==null);Utilities.globalQueue.run();
  t=start("application/pdf");sharedFileCopyQueue.run();t.currentAccount=3;AndroidUtilities.ui.run();check(t.opens==0);Utilities.globalQueue.run();
  t=start("application/pdf");sharedFileCopyQueue.run();t.alive=false;AndroidUtilities.ui.run();check(t.opens==0);Utilities.globalQueue.run();
  t=start("application/pdf");sharedFileCopyQueue.run();AlertDialog.last.cancel.run(null);AndroidUtilities.ui.run();check(t.opens==0);Utilities.globalQueue.run();
  t=start("application/pdf");SharedConfig.appLocked=true;finish();check(t.opens==0);Utilities.globalQueue.run();SharedConfig.appLocked=false;
  t=start("application/pdf");MediaController.path=null;finish();check(t.opens==0&&t.sendingText==null&&Toast.errors==1);
 }
}
'''.replace("BODY", body))

    def test_launch_stages_after_cold_start_and_keeps_other_share_paths(self):
        source = LAUNCH.read_text()
        handle = block(source, "private boolean handleIntent(Intent intent, boolean isNew, boolean restore, boolean fromPassword, Browser.Progress")
        self.assertNotIn("MediaController.copyFileToCache", handle)
        self.assertIn("sharedFileToCache = uri", handle)
        self.assertIn("if (exportingChatUri == null)", handle)
        self.assertIn("ContactsContract.Contacts.CONTENT_VCARD_TYPE", handle)
        self.assertIn("Intent.ACTION_SEND_MULTIPLE", handle)
        self.assertLess(handle.index("if (!pushOpened && !isNew)"), handle.index("pendingSharedFileCopy.run()"))
        self.assertIn("copyAccount, copyDialogId, navigationRequestGeneration.get()", handle)
        self.assertIn("final CharSequence copyCaption = sendingText", handle)
        stage = block(source, "private void stageSharedFile(")
        self.assertIn("sharedFileCopyQueue.postRunnable", stage)
        self.assertNotIn("takePersistableUriPermission", stage)
        self.assertNotIn("documentsUrisArray.add", stage)
        self.assertLess(stage.index("isNavigationRequestCurrent(account, generation)", stage.index("AndroidUtilities.runOnUIThread")), stage.index("sendingText = caption"))
        handoff = block(source, "private void openSharedContent(")
        self.assertIn("openDialogsToSend(false)", handoff)
        self.assertIn("didSelectDialogs(null, dids", handoff)


if __name__ == "__main__":
    unittest.main()
