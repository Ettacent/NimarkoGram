const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const cp = require('node:child_process');
const assert = require('node:assert/strict');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/java/app/nimarkogram/messenger/notifications/NimarkoInAppNotifications.java'), 'utf8');
const start = source.indexOf('    private static void bindAvatar(');
const end = source.indexOf('    private static final class Banner', start);
assert(start > 0 && end > start);
assert(source.includes('bindAvatar(avatar, account, dialogId, avatarHeading, preview, sample);'));
assert(source.includes('refreshAvatar();\n                    followScreen();\n                    postDelayed(this, 250);'));
const refreshStart=source.indexOf('        private void refreshAvatar()');
const refreshEnd=source.indexOf('        private void followScreen()',refreshStart);
assert(refreshStart>0&&refreshEnd>refreshStart);
assert(source.includes('avatar.setRoundRadius(dp(18))'));
assert(source.includes('LayoutHelper.createFrame(36, 36, Gravity.START | Gravity.TOP)'));
assert(!source.includes('R.drawable.msg_notifications'));
const java = `
class TLObject {}
class ImageLocation {static int TYPE_SMALL=1;static ImageLocation getForUserOrChat(int a,TLObject p,int type){return new ImageLocation();}}
class TLRPC {
 static class FileLocation {long volume_id;int local_id;}
 static class UserProfilePhoto {long photo_id;int dc_id;FileLocation photo_small;}
 static class ChatPhoto extends UserProfilePhoto {}
 static class User extends TLObject {UserProfilePhoto photo;}
 static class Chat extends TLObject {ChatPhoto photo;}
}
class MessagesController {
 static MessagesController instance=new MessagesController();static int account,lookups;static long id;
 static TLObject user,chat;
 static MessagesController getInstance(int a){account=a;lookups++;return instance;}
 TLObject getUser(long i){id=i;return user;} TLObject getChat(long i){id=i;return chat;}
}
class AvatarDrawable {int account;long id;String name;TLObject peer;
 void setInfo(int a,TLObject p){account=a;peer=p;}
 void setInfo(long i,String n,String last){id=i;name=n;}
}
class BackupImageView {
 static class Receiver {int account,duration;boolean force,old;void setCurrentAccount(int a){account=a;}void setCrossfadeDuration(int d){duration=d;}void setForceCrossfade(boolean v){force=v;}void setCrossfadeWithOldImage(boolean v){old=v;}}
 Receiver receiver=new Receiver();TLObject peer;AvatarDrawable drawable;int binds;
 Receiver getImageReceiver(){return receiver;}
 void setForUserOrChat(TLObject p,AvatarDrawable d){peer=p;drawable=d;binds++;}
 void setImage(ImageLocation l,String filter,AvatarDrawable d,int size,Object p){if(!receiver.force||!receiver.old)throw new AssertionError("cached and late photos must fade");peer=(TLObject)p;drawable=d;binds++;}
 void setImageDrawable(AvatarDrawable d){peer=null;drawable=d;binds++;}
}
public class NotificationAvatarTest {
 static class R {static class string {static int AppName=1;}}
 static String getString(int id){return "NimarkoGram";}
 ${source.slice(start,end).replaceAll('org.telegram.messenger.ImageLocation','ImageLocation')}
 BackupImageView avatar=new BackupImageView();int account=2;long dialogId=123;boolean preview=true,sample;
 String avatarHeading="Alice";long avatarPhotoId=Long.MIN_VALUE,avatarVolumeId;int avatarDcId,avatarLocalId;boolean avatarPeerAvailable;
 ${source.slice(refreshStart,refreshEnd)}
 static int checks;
 static void check(boolean value){checks++;if(!value)throw new AssertionError("check "+checks);}
 public static void main(String[] args){
  BackupImageView a=new BackupImageView();MessagesController.user=new TLObject();MessagesController.chat=new TLObject();
  bindAvatar(a,3,123,"Alice",true,false);
  check(a.peer==MessagesController.user&&a.drawable.peer==a.peer&&a.drawable.account==3);
  check(a.receiver.account==3&&MessagesController.account==3&&a.receiver.duration==180);
  bindAvatar(a,2,-456,"Group",true,false);check(a.peer==MessagesController.chat&&MessagesController.id==456);
  MessagesController.user=null;
  for(String name:new String[]{"Alice Smith","Иван","张三","😀 Test"}){
   bindAvatar(a,1,789,name,true,false);check(a.peer==null&&name.equals(a.drawable.name)&&a.drawable.id==789);
  }
  for(String name:new String[]{null,"","   "}){
   bindAvatar(a,1,789,name,true,false);check("NimarkoGram".equals(a.drawable.name));
  }
  int lookups=MessagesController.lookups;
  bindAvatar(a,1,789,"Private Sender",false,false);
  check(MessagesController.lookups==lookups&&a.peer==null&&a.drawable.id==0&&"NimarkoGram".equals(a.drawable.name));
  NotificationAvatarTest t=new NotificationAvatarTest();MessagesController.user=null;t.refreshAvatar();
  check(t.avatar.binds==1&&t.avatar.peer==null);
  TLRPC.User user=new TLRPC.User();MessagesController.user=user;t.refreshAvatar();check(t.avatar.binds==2&&t.avatar.peer==user);
  user.photo=new TLRPC.UserProfilePhoto();user.photo.photo_id=456;user.photo.dc_id=4;t.refreshAvatar();
  check(t.avatar.binds==3&&t.avatarPhotoId==456&&t.avatar.receiver.account==2);
  for(int i=0;i<1000;i++){t.refreshAvatar();check(t.avatar.binds==3);}
  user.photo.dc_id=5;t.refreshAvatar();check(t.avatar.binds==4);
  user.photo.photo_small=new TLRPC.FileLocation();user.photo.photo_small.volume_id=987;t.refreshAvatar();check(t.avatar.binds==5);
  user.photo.photo_small.local_id=7;t.refreshAvatar();check(t.avatar.binds==6);
  user.photo=null;t.refreshAvatar();check(t.avatar.binds==7&&t.avatarPhotoId==0);
  TLRPC.Chat chat=new TLRPC.Chat();chat.photo=new TLRPC.ChatPhoto();chat.photo.photo_id=789;MessagesController.chat=chat;
  t.dialogId=-999;t.refreshAvatar();check(t.avatarPhotoId==789&&t.avatar.peer==chat);
  t.preview=false;lookups=MessagesController.lookups;t.refreshAvatar();
  check(t.avatar.peer==null&&MessagesController.lookups==lookups&&"NimarkoGram".equals(t.avatar.drawable.name));
  bindAvatar(a,1,0,"NimarkoGram",true,true);
  check(MessagesController.lookups==lookups&&a.peer==null&&a.drawable.id==0&&"NimarkoGram".equals(a.drawable.name));
  System.out.println("PASS: "+checks+" notification avatar checks: peers, account colors, Unicode names, missing data, hidden preview and sample");
 }
}`;
const dir=fs.mkdtempSync(path.join(os.tmpdir(),'nimarko-notification-avatar-'));
try {
    fs.writeFileSync(path.join(dir,'NotificationAvatarTest.java'),java);
    cp.execFileSync('javac',['NotificationAvatarTest.java'],{cwd:dir});
    process.stdout.write(cp.execFileSync('java',['NotificationAvatarTest'],{cwd:dir,encoding:'utf8'}));
    for (const broken of [
        java.replace('String name = preview && heading', 'String name = heading'),
        java.replace('setForceCrossfade(true)', 'setForceCrossfade(false)'),
        java.replace('setCrossfadeWithOldImage(true)', 'setCrossfadeWithOldImage(false)'),
    ]) {
        assert.notEqual(broken, java);
        fs.writeFileSync(path.join(dir,'NotificationAvatarTest.java'),broken);
        cp.execFileSync('javac',['NotificationAvatarTest.java'],{cwd:dir});
        assert.notEqual(cp.spawnSync('java',['NotificationAvatarTest'],{cwd:dir}).status,0);
    }
} finally {fs.rmSync(dir,{recursive:true,force:true});}
