"""Notification sound wiring and extracted production URI logic on JVM stubs.

Does not emulate Android's permission service or actual sound playback.
"""
from pathlib import Path
import subprocess
import tempfile
import unittest

from test_gif_loop_transition import method

JAVA = Path(__file__).resolve().parents[2] / "main/java/org/telegram"


class ExternalNotificationSoundTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.controller = (JAVA / "messenger/NotificationsController.java").read_text()
        cls.profile = (JAVA / "ui/ProfileNotificationsActivity.java").read_text()
        cls.picker = (JAVA / "ui/NotificationsSoundActivity.java").read_text()

    def test_external_and_cached_files_share_resolution(self):
        delivery = self.controller.split('if (soundPath != null && !soundPath.equalsIgnoreCase("NoSound"))', 1)[1].split('if (ledColor != 0)', 1)[0]
        self.assertIn('resolveNotificationSound(soundPath, defaultPath, isInternalSoundFile)', delivery)
        self.assertIn('sound = resolvedSound;', delivery)
        self.assertIn('mBuilder.setSound(resolvedSound, AudioManager.STREAM_NOTIFICATION)', delivery)
        self.assertNotIn('Uri.parse(soundPath)', delivery)

    def test_file_conversion_and_private_file_boundary(self):
        resolver = self.controller.split('private static Uri resolveNotificationSound', 1)[1].split('@TargetApi(26)', 1)[0]
        self.assertIn('sound.getScheme() == null || "file".equals(sound.getScheme())', resolver)
        self.assertIn('Uri.fromFile(file)', resolver)
        self.assertIn('!internalSound && AndroidUtilities.isInternalUri(sound)', resolver)
        self.assertIn('FileProvider.getUriForFile', resolver)
        self.assertIn('grantNotificationSoundPermission(sound)', resolver)

    def test_default_and_silent_sentinels(self):
        resolver = self.controller.split('private static Uri resolveNotificationSound', 1)[1].split('@TargetApi(26)', 1)[0]
        self.assertIn('path == null || "NoSound".equalsIgnoreCase(path)', resolver)
        self.assertIn('"Default".equalsIgnoreCase(path) || path.equals(defaultPath)', resolver)
        self.assertNotIn('DEFAULT_RINGTONE_URI', resolver)

    def test_grants_renew_on_channel_reuse_and_system_edits(self):
        channel = self.controller.split('private String validateChannelId', 1)[1].split('private int bannerDeliveryGeneration', 1)[0]
        self.assertLess(channel.index('grantNotificationSoundPermission(sound)'), channel.index('if (channelId != null)'))
        self.assertIn('grantNotificationSoundPermission(channelSound)', channel)

    def test_only_offered_read_grants_are_persisted(self):
        permissions = self.controller.split('public static void retainNotificationSoundPermission', 1)[1].split('private static Uri resolveNotificationSound', 1)[0]
        self.assertIn('Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION) != 0', permissions)
        self.assertIn('Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0', permissions)
        self.assertIn('takePersistableUriPermission', permissions)
        self.assertIn('catch (SecurityException e)', permissions)
        self.assertIn('catch (RuntimeException e)', permissions)

    def test_profile_selection_clears_cloud_precedence_before_channel_reset(self):
        result = self.profile.split('public void onActivityResultFragment', 1)[1].split('@SuppressLint', 1)[0]
        self.assertIn('requestCode != 12 && requestCode != 13', result)
        self.assertIn('retainNotificationSoundPermission(ringtone, data)', result)
        self.assertIn('editor.remove("sound_document_id_" + key)', result)
        self.assertIn('editor.putBoolean("custom_" + key, true)', result)
        self.assertIn('editor.putBoolean("sound_enabled_" + key, true)', result)
        self.assertLess(result.index('editor.apply()'), result.index('deleteNotificationChannel(dialogId, topicId)'))

    def test_picker_grants_selected_local_sound_and_ignores_cancellation(self):
        self.assertIn('retainNotificationSoundPermission(Uri.parse(selectedTone.uri), null)', self.picker)
        self.assertIn('resultCode != Activity.RESULT_OK || data == null', self.picker)

    def test_uri_resolution_and_permission_lifecycle_on_jvm(self):
        production = '\n'.join(method(self.controller, marker) for marker in (
            'public static void retainNotificationSoundPermission(',
            'private static void grantNotificationSoundPermission(',
            'private static Uri resolveNotificationSound('))
        source = r'''
import java.io.File;
import java.net.URI;
public class SoundHarness {
 static class Uri {
  final URI uri; Uri(URI u){uri=u;}
  static Uri parse(String s){return new Uri(URI.create(s.replace(" ","%20")));}
  static Uri fromFile(File f){return new Uri(f.toURI());}
  String getScheme(){return uri.getScheme();} String getPath(){return uri.getPath();}
  public String toString(){return uri.toString();}
 }
 static class Build {static class VERSION {static int SDK_INT=36;}}
 static class Intent {
  static final int FLAG_GRANT_READ_URI_PERMISSION=1, FLAG_GRANT_PERSISTABLE_URI_PERMISSION=64;
  int flags; Intent(int f){flags=f;} int getFlags(){return flags;}
 }
 static class Settings {static class System {
  static final Uri DEFAULT_NOTIFICATION_URI=Uri.parse("content://settings/system/notification_sound");
 }}
 static class FileLog {static void e(Throwable t){}}
 static class Context {
  int persisted,granted; boolean reject; Uri last;
  Context getContentResolver(){return this;}
  void takePersistableUriPermission(Uri u,int flags){
   check(flags==1,"persist read only"); if(reject)throw new SecurityException(); persisted++;
  }
  void grantUriPermission(String pkg,Uri u,int flags){
   check(pkg.equals("com.android.systemui")&&flags==1,"system reader only");
   if(reject)throw new SecurityException();granted++;last=u;
  }
 }
 static class ApplicationLoader {
  static Context applicationContext=new Context(); static String getApplicationId(){return "test.app";}
 }
 static class AndroidUtilities {
  static boolean isInternalUri(Uri u){return u.getPath().startsWith("/private/");}
 }
 static class FileProvider {
  static String lastPath; static boolean reject;
  static Uri getUriForFile(Context c,String authority,File f){
   if(reject)throw new IllegalArgumentException();
   lastPath=f.getPath(); return Uri.parse("content://"+authority+"/tone");
  }
 }
 static void check(boolean b,String m){if(!b)throw new AssertionError(m);}
 /* PRODUCTION */
 public static void main(String[] args){
  String def="/system/notification_sound";
  check(resolveNotificationSound(null,def,false)==null,"null silent");
  check(resolveNotificationSound("NoSound",def,false)==null,"explicit silent");
  for(String s:new String[]{"Default",def})
   check(resolveNotificationSound(s,def,false)==Settings.System.DEFAULT_NOTIFICATION_URI,"default");
  for(int sdk:new int[]{23,24,26,36}){
   Build.VERSION.SDK_INT=sdk;
   Uri u=resolveNotificationSound("file:///storage/Music/my%20tone.mp3",def,false);
   check(u.getScheme().equals(sdk>=24?"content":"file"),"file routing "+sdk);
  }
  Uri u=resolveNotificationSound("content://media/external/audio/media/42",def,false);
  check(u.toString().endsWith("/42")&&ApplicationLoader.applicationContext.last==u,"external grants");
  check(resolveNotificationSound("/private/tone.ogg",def,false)==Settings.System.DEFAULT_NOTIFICATION_URI,"private boundary");
  resolveNotificationSound("/private/my #1?.ogg",def,true);
  check(FileProvider.lastPath.equals("/private/my #1?.ogg"),"raw filename retained");
  Context c=ApplicationLoader.applicationContext; int before=c.persisted;
  for(int flags:new int[]{0,1,64})retainNotificationSoundPermission(u,new Intent(flags));
  check(c.persisted==before,"do not invent persistable permission");
  retainNotificationSoundPermission(u,new Intent(65));
  check(c.persisted==before+1,"offered read permission persists");
  c.reject=true;retainNotificationSoundPermission(u,new Intent(65));
  check(resolveNotificationSound(u.toString(),def,false).toString().equals(u.toString()),"optional grant failure retains URI");
  FileProvider.reject=true;
  check(resolveNotificationSound("file:///unavailable/tone",def,false)==Settings.System.DEFAULT_NOTIFICATION_URI,"unshareable file safe fallback");
 }
}
'''.replace('/* PRODUCTION */', production)
        with tempfile.TemporaryDirectory(prefix='notification-sound-') as folder:
            path = Path(folder) / 'SoundHarness.java'
            path.write_text(source)
            for command in (['javac', str(path)], ['java', '-cp', folder, 'SoundHarness']):
                result = subprocess.run(command, capture_output=True, text=True, timeout=30)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)


if __name__ == "__main__":
    unittest.main()
