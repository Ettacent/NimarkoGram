"""Focused audit fixes: extracted production decisions on JVM stubs, no Android build."""
import shutil
import unittest
from test_round_backend_lifecycle import JAVA, VIEW, method, run_java

PLAYER = (JAVA / 'org/telegram/ui/Components/VideoPlayer.java').read_text()
LOADER = (JAVA / 'org/telegram/messenger/FileLoader.java').read_text()
ALERT = (JAVA / 'org/telegram/ui/Components/AudioPlayerAlert.java').read_text()
STORIES = (JAVA / 'org/telegram/ui/Stories/PeerStoriesView.java').read_text()


@unittest.skipUnless(shutil.which('javac') and shutil.which('java'), 'JDK required')
class MediaAuditLifecycleTests(unittest.TestCase):
    def test_storyboard_isolation_keeps_normal_downloads(self):
        run_java('''public class RoundHarness {
 static class TLRPC {static class Document {String mime_type;Document(String m){mime_type=m;}}}
 BODY
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  check(!isVideoStoryboardDocument(null));
  for(String mime:new String[]{null,"video/mp4","audio/ogg","application/pdf"})
   check(!isVideoStoryboardDocument(new TLRPC.Document(mime)));
  for(String mime:new String[]{"application/x-tgstoryboard","APPLICATION/X-TGSTORYBOARDMAP"})
   check(isVideoStoryboardDocument(new TLRPC.Document(mime)));
 }
}'''.replace('BODY', method(LOADER, 'private static boolean isVideoStoryboardDocument(')))
        for signature in ('public void didFinishLoadingFile(', 'public void didFailedLoadingFile('):
            self.assertIn('!isVideoStoryboardDocument(document)', method(LOADER, signature))
        self.assertEqual(LOADER.count('!isVideoStoryboardDocument(document)'), 3)
        preview = (JAVA / 'org/telegram/ui/Components/VideoSeekPreviewImage.java').read_text()
        self.assertIn('loadFile(storyboardMapDocument, messageObject,', preview)

    def test_silent_muted_paused_and_mixed_audio_notification(self):
        body = '\n'.join(method(PLAYER, s) for s in (
            'private static boolean hasPlayingAudio(', 'private void maybeNotifyPlayingWithAudio()'))
        run_java('''public class RoundHarness {
 static class C {static int TRACK_TYPE_AUDIO=1;}
 static class Player {static int STATE_READY=3;}
 static class Tracks {boolean audio;boolean isTypeSelected(int type){return audio;}}
 static class ExoPlayer {
  boolean playing=true;int state=3;float volume=1;Tracks tracks=new Tracks();
  boolean getPlayWhenReady(){return playing;}int getPlaybackState(){return state;}
  float getVolume(){return volume;}Tracks getCurrentTracks(){return tracks;}
 }
 static class NotificationCenter {
  static int playerDidStartPlaying=1,count;static NotificationCenter instance=new NotificationCenter();
  static NotificationCenter getGlobalInstance(){return instance;}
  void postNotificationName(int event,Object source){count++;}
 }
 ExoPlayer player=new ExoPlayer(),audioPlayer;boolean released,shouldPauseOther=true,mixedAudio;
 BODY
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  RoundHarness h=new RoundHarness();h.maybeNotifyPlayingWithAudio();check(NotificationCenter.count==0);
  h.player.tracks.audio=true;h.player.volume=0;h.maybeNotifyPlayingWithAudio();check(NotificationCenter.count==0);
  h.player.volume=1;h.player.playing=false;h.maybeNotifyPlayingWithAudio();check(NotificationCenter.count==0);
  h.player.playing=true;h.player.state=2;h.maybeNotifyPlayingWithAudio();check(NotificationCenter.count==0);
  h.player.state=3;h.maybeNotifyPlayingWithAudio();check(NotificationCenter.count==1);
  h.player.tracks.audio=false;h.mixedAudio=true;h.audioPlayer=new ExoPlayer();h.audioPlayer.tracks.audio=true;
  h.maybeNotifyPlayingWithAudio();check(NotificationCenter.count==2);
  h.released=true;h.maybeNotifyPlayingWithAudio();check(NotificationCenter.count==2);
  h.released=false;h.shouldPauseOther=false;h.maybeNotifyPlayingWithAudio();check(NotificationCenter.count==2);
 }
}'''.replace('BODY', body))
        for signature in ('public void onPlayerStateChanged(', 'public void onTracksChanged(', 'public void onVolumeChanged('):
            self.assertIn('maybeNotifyPlayingWithAudio();', method(PLAYER, signature))

    def test_profile_save_error_false_and_success_callbacks(self):
        save = method(ALERT, 'private void saveToProfile(')
        start = save.rindex('} else if (err != null) {') + len('} else ')
        tail = save[start:save.rindex('        });')]
        run_java('''public class RoundHarness {
 static class TLRPC {
  static class TL_boolTrue {} static class Document {long id=7;}
  static class UserFull {int flags2;Document saved_music;}
 }
 static class TLObject {static int FLAG_21=1<<21;}
 static class AndroidUtilities {static void runOnUIThread(Runnable r){r.run();}}
 static class R {static class string {static int UnknownError=1;}}
 static class FrameLayout {}
 static class BulletinFactory {
  static int errors;static BulletinFactory of(FrameLayout v,Object p){return new BulletinFactory();}
  void showForError(Object e){errors++;}BulletinFactory createErrorBulletin(String s){errors++;return this;}void show(){}
 }
 static class MessagesController {
  static MessagesController instance=new MessagesController();int updates;TLRPC.UserFull user=new TLRPC.UserFull();
  static MessagesController getInstance(int a){return instance;}MessagesController getSavedMusicIds(){return this;}
  void update(long id,boolean save){updates++;}TLRPC.UserFull getUserFull(long id){return user;}
 }
 static class UserConfig {static UserConfig getInstance(int a){return new UserConfig();}long getClientUserId(){return 1;}}
 static class MessagesStorage {static MessagesStorage getInstance(int a){return new MessagesStorage();}void updateUserInfo(Object u,boolean b){}}
 static class NotificationCenter {static int profileMusicUpdated=1;static NotificationCenter getInstance(int a){return new NotificationCenter();}void postNotificationName(int n,long id){}}
 int currentAccount,doneCount;long documentId=7;TLRPC.Document document=new TLRPC.Document();
 FrameLayout containerView=new FrameLayout();Object resourcesProvider;Runnable done=()->doneCount++;
 boolean isDismissed(){return false;}String getString(int id){return "error";}
 void complete(Object res,Object err,boolean save){BODY}
 static void check(boolean b){if(!b)throw new AssertionError();}
 public static void main(String[] args){
  RoundHarness h=new RoundHarness();h.complete(null,new Object(),true);
  check(MessagesController.instance.updates==0&&h.doneCount==0&&BulletinFactory.errors==1);
  h.complete(new Object(),null,true);check(MessagesController.instance.updates==0&&h.doneCount==0&&BulletinFactory.errors==2);
  h.complete(new TLRPC.TL_boolTrue(),null,true);check(MessagesController.instance.updates==1&&h.doneCount==1);
  check(MessagesController.instance.user.saved_music==h.document);
  h.complete(new TLRPC.TL_boolTrue(),null,false);check(MessagesController.instance.updates==2&&h.doneCount==2);
  check(MessagesController.instance.user.saved_music==null);
 }
}'''.replace('BODY', tail))
        self.assertNotIn('saveToProfile(messageObject1, true, () -> {}, false)', ALERT)
        self.assertNotIn('saveToProfile(messageObject1, false, () -> {}, false)', ALERT)
        start = STORIES.index('final int account = currentAccount;', STORIES.index('getString(R.string.StoryAudioAddToProfile)'))
        story = STORIES[start:STORIES.index('if (panel.isRepostMessage', start)]
        self.assertIn('err != null || !(res instanceof TLRPC.TL_boolTrue)', story)
        self.assertLess(story.index('return;'), story.index('list.add(music)'))
        self.assertIn('list.currentAccount == account', story)

    def test_encoder_stop_failure_does_not_skip_release(self):
        run_java('''public class RoundHarness {
 static class FileLog {static void e(Exception e){}}
 static class MediaCodec {boolean failStop,failRelease;int stopped,released;
  void stop(){stopped++;if(failStop)throw new IllegalStateException();}
  void release(){released++;if(failRelease)throw new IllegalStateException();}
 }
 BODY
 public static void main(String[] args){
  RoundHarness h=new RoundHarness();h.releaseEncoder(null);
  for(boolean stop:new boolean[]{false,true})for(boolean release:new boolean[]{false,true}){
   MediaCodec c=new MediaCodec();c.failStop=stop;c.failRelease=release;h.releaseEncoder(c);
   if(c.stopped!=1||c.released!=1)throw new AssertionError();
  }
 }
}'''.replace('BODY', method(VIEW, 'private void releaseEncoder(')))

    def test_startup_order_no_waits_and_guarded_cleanup(self):
        prepare = method(VIEW, 'private void prepareEncoder(')
        self.assertTrue(prepare.rstrip().endswith('thread.start();\n        }'))
        self.assertGreater(prepare.index('thread.start();'), prepare.rindex('throw new'))
        self.assertNotIn('stopAudioReader', VIEW)
        self.assertNotIn('audioRecorderThread', VIEW)
        self.assertNotIn('.join(', prepare)
        failure = method(VIEW, 'private void handleStartRecordingError()')
        self.assertLess(failure.index('startupFailed = true'), failure.index('handleStopRecording('))
        self.assertLess(failure.index('running = false'), failure.index('handleStopRecording('))
        finish = method(VIEW, 'private void handleStopRecording(')
        self.assertIn('if (!startupFailed) drainEncoder(true);', finish)
        self.assertIn('eglDisplay != null', finish)
        self.assertIn('eglSurface != null', finish)
        overlay = method(finish, 'if (overlayHelper != null)')
        self.assertIn('catch (Exception e)', overlay)
        self.assertLess(finish.index('overlayHelper.destroy();'), finish.index('handler.exit();'))


if __name__ == '__main__':
    unittest.main()
