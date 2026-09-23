const fs = require('node:fs');
const path = require('node:path');
const os = require('node:os');
const cp = require('node:child_process');
const assert = require('node:assert/strict');
const read = p => fs.readFileSync(path.resolve(__dirname, '../../main/java', p), 'utf8');
function method(s, signature) {
    const start = s.indexOf(signature);
    assert(start >= 0, signature);
    let end = s.indexOf('{', start), depth = 1;
    while (depth && ++end < s.length) {
        if (s[end] === '{') depth++;
        if (s[end] === '}') depth--;
    }
    assert.equal(depth, 0);
    return s.slice(start, end + 1);
}
const stars = read('org/telegram/ui/Stars/StarsController.java');
const gifts = stars.slice(stars.indexOf('public static class GiftsList'));
const collections = stars.slice(stars.indexOf('public static class GiftsCollections'));
const profile = read('org/telegram/ui/ProfileActivity.java');
const tabs = read('org/telegram/ui/MainTabsActivity.java');
const channel = read('org/telegram/ui/Cells/ProfileChannelCell.java');
const sharedMedia = read('org/telegram/ui/Components/SharedMediaLayout.java');
const groups = sharedMedia.split('private class CommonGroupsAdapter')[1];
assert.match(method(profile, 'public void onBecomeFullyVisible()'), /refreshVisibleProfile\(\)/);
assert.doesNotMatch(profile, /profileWasVisible/);
assert.match(profile, /private boolean refreshGiftsOnReturn = true;/);
assert.match(method(profile, 'public org.telegram.ui.Components.AnimatedLinearLayout getInAppNotificationPanel()'), /new app\.nimarkogram\.messenger\.notifications\.NotificationInlinePanel\(this, host\)\s*\.withOverlayAnchor\(this::getProfileNotificationTop\)/);
assert.match(method(profile, 'private void updateRowsIds()'), /hasProfileGifts\(getDialogId\(\)\)/);
assert.match(sharedMedia, /hasProfileGifts\(dialog_id\)/);
assert.doesNotMatch(read('org/telegram/ui/Gifts/ProfileGiftsContainer.java'), /invalidateProfileGifts\(dialogId\)/);
assert.match(profile, /message\.isStarGiftAction\(\)[\s\S]*?refreshGiftsOnReturn = true;/);
assert.doesNotMatch(method(profile, 'public void onBecomeFullyHidden()'), /resetMainTabScroll/);
assert.match(method(tabs, 'protected void onViewPagerScrollEnd()'), /currentPosition != profilePosition && viewPager.getPositionVisibility\(profilePosition\) == 0/);
assert.doesNotMatch(method(tabs, 'protected void onViewPagerScrollEnd()'), /dropFragmentAtPosition\(profilePosition\)/);
assert.match(profile, /profileChannelMessageFetcher.subscribe\(this::onProfileChannelMessagesLoaded\);\s*}\s*profileChannelMessageFetcher.fetch\(userInfo\)/);
const java = `
import java.util.*;
import java.util.function.*;
class TLObject {
 boolean sort_by_value,exclude_unupgradable,exclude_upgradable,exclude_unlimited,exclude_unique,exclude_saved,exclude_unsaved,peer_color_available,chat_notifications_enabled;
 Object peer;String offset,next_offset;int limit,flags,collection_id,count;long gift_id;
 ArrayList<String> gifts=new ArrayList<>();ArrayList<TL_stars.TL_starGiftCollection> collections=new ArrayList<>();Object users,chats;long hash;
}
class TL_stars {
 static class getCraftStarGifts extends TLObject{}
 static class getSavedStarGifts extends TLObject{}
 static class TL_payments_savedStarGifts extends TLObject{}
 static class getStarGiftCollections extends TLObject{}
 static class TL_starGiftCollection{int collection_id;}
 static class TL_starGiftCollections extends TLObject{}
 static class TL_starGiftCollectionsNotModified extends TLObject{}
}
class AndroidUtilities {static void runOnUIThread(Runnable r){r.run();}}
class MessagesController {
 static MessagesController getInstance(int a){return new MessagesController();}
 int stargiftsPinnedToTopLimit=3;
 Object getInputPeer(long id){return id;}
 Object getInputUser(long id){return id;}TLRPC.EncryptedChat getEncryptedChat(long id){return new TLRPC.EncryptedChat();}
 void putUsers(Object o,boolean b){} void putChats(Object o,boolean b){}
 static int fullUsers,fullChats;Object getUser(long id){return id;}
 void loadFullUser(Object u,int guid,boolean force){fullUsers++;}void loadFullChat(long id,int guid,boolean force){fullChats++;}
}
class UserConfig {static int selectedAccount;long getClientUserId(){return 77;}}
class TLRPC {
 static class TL_inputPeerSelf{} static class TL_inputUserEmpty{} static class EncryptedChat{long user_id;}
 static class UserFull{int flags2,personal_channel_message;long personal_channel_id;}
 static class Chat{long id;Chat(long id){this.id=id;}}
 static class TL_messages_getCommonChats extends TLObject{Object user_id;long max_id;}
 static class messages_Chats extends TLObject{ArrayList<Chat> chats=new ArrayList<>();}
}
class DialogObject {static boolean isEncryptedDialog(long id){return false;}static long getEncryptedChatId(long id){return id;}}
class NotificationCenter {
 static final int starUserGiftsLoaded=1,starUserGiftCollectionsLoaded=2;
 static int posts;static NotificationCenter getInstance(int a){return new NotificationCenter();}
 void postNotificationName(int id,long did,Object list){posts++;}
}
class ConnectionsManager {
 static HashMap<Integer,ConnectionsManager> instances=new HashMap<>();
 static ConnectionsManager getInstance(int a){return instances.computeIfAbsent(a,k->new ConnectionsManager());}
 int next;HashMap<Integer,BiConsumer<TLObject,Object>> callbacks=new HashMap<>();HashMap<Integer,TLObject> requests=new HashMap<>();
 int sendRequest(TLObject req,BiConsumer<TLObject,Object> cb){int id=++next;callbacks.put(id,cb);requests.put(id,req);return id;}
 void cancelRequest(int id,boolean b){}
 void bindRequestToGuid(int id,int guid){}
 void response(int id,TLObject result){callbacks.get(id).accept(result,null);}
}
public class ProfileRefreshTest {
 static class GiftsList {
  int currentAccount;long dialogId=77;ArrayList<String> gifts=new ArrayList<>();
  boolean loading,endReached,shown,refreshPending,sort_by_date=true,peer_color_available,isCollection;
  int currentRequestId=-1,totalCount,collectionId;long craftingGiftId;String lastOffset;Boolean chat_notifications_enabled;
  GiftsList(){} GiftsList(int account,long id,boolean load){currentAccount=account;dialogId=id;}
  void setCollectionId(int id){collectionId=id;}
  boolean isInclude_limited(){return true;}boolean isInclude_upgradable(){return true;}boolean isInclude_unlimited(){return true;}
  boolean isInclude_unique(){return true;}boolean isInclude_displayed(){return true;}boolean isInclude_hidden(){return true;}
  ${method(gifts, 'public void invalidate(boolean load)')}
  ${method(gifts, 'public void refresh()')}
  ${method(gifts, 'public void load()')}
  ${method(gifts, 'private void load(boolean refreshing)')}
  ${method(gifts, 'public void cancel()')}
 }
 static class GiftsCollections {
  int currentAccount=2,currentRequestId=-1;long dialogId=77;boolean loading,loaded,shown;
  ArrayList<TL_stars.TL_starGiftCollection> collections=new ArrayList<>();HashMap<Integer,GiftsList> gifts=new HashMap<>();
  long getHash(Object a){return 0;}void refilterCollections(){}
  GiftsList getListById(int id){return gifts.get(id);}
  ${method(collections, 'public void load()')}
  ${method(collections, 'public void invalidate(boolean load)')}
 }
 static class Fetcher {
  int searchId,message_id,fetches;long channel_id;boolean loading,loaded;ArrayList<String> messageObjects=new ArrayList<>();
  void done(boolean b){} void fetch(long channel,int message){fetches++;}
  ${method(channel, 'public void fetch(TLRPC.UserFull userInfo)')}
 }
 static class RecyclerListView {
  int stops,position=-1;void stopScroll(){stops++;}void scrollToPosition(int p){position=p;}int getPaddingTop(){return 144;}
 }
 static class LayoutManager {int position=-1,offset;void scrollToPositionWithOffset(int p,int o){position=p;offset=o;}}
 static class Media {RecyclerListView list=new RecyclerListView();RecyclerListView getCurrentListView(){return list;}}
 static class StarsController {
  static HashMap<Integer,StarsController> accounts=new HashMap<>();HashMap<Long,GiftsList> lists=new HashMap<>();
  static StarsController getInstance(int account){return accounts.computeIfAbsent(account,k->new StarsController());}
  GiftsList getProfileGiftsList(long id,boolean create){return create?getProfileGiftsList(id):lists.get(id);}
  GiftsList getProfileGiftsList(long id){return lists.computeIfAbsent(id,k->{GiftsList g=new GiftsList();g.currentAccount=9;g.load();return g;});}
  ${method(stars, 'public boolean hasProfileGifts(long dialogId)')}
 }
 static class BannerConfig {static boolean enabled=true;}
 static class BannerController {static int calls;static BannerController getInstance(){return new BannerController();}void refreshStatus(boolean force){calls++;}}
 static class Preloader {int calls;void refreshMediaCounts(){calls++;}}
 static class GiftsContainer {GiftsList selected;GiftsList getCurrentList(){return selected;}}
 static class RefreshMedia {GiftsContainer giftsContainer=new GiftsContainer();}
 static class VisibleProfile {
  boolean profileLifecycleDestroyed,settings,initialFullInfoRequested,initialMediaCountsRequested;
  ${profile.match(/private boolean refreshGiftsOnReturn = true;/)[0]}
  long userId=77,chatId;int currentAccount=9,classGuid;
  RefreshMedia sharedMediaLayout=new RefreshMedia();Preloader sharedMediaPreloader=new Preloader();
  boolean isSettings(){return settings;}long getDialogId(){return userId!=0?userId:-chatId;}
  MessagesController getMessagesController(){return MessagesController.getInstance(currentAccount);}UserConfig getUserConfig(){return new UserConfig();}
  ${method(profile, 'private void refreshVisibleProfile()').replaceAll('StarsController.GiftsList','GiftsList').replaceAll('app.nimarkogram.messenger.banners.NimarkoBannerConfig','BannerConfig').replaceAll('app.nimarkogram.messenger.banners.NimarkoBannerController','BannerController')}
 }
 static class View {static int VISIBLE=0,GONE=8;}
 static class Page {int selectedType,visibility;float x;int getVisibility(){return visibility;}float getTranslationX(){return x;}}
 static class TabGeometry {
  int width=1080;Page[] mediaPages={new Page(),new Page()};int getWidth(){return width;}
  boolean isTab(int selected,int type,boolean subtabs){return selected==type||subtabs&&type==1&&selected==3;}
  ${method(sharedMedia, 'public float getTabTranslationX(int type, boolean includeSubtabs)')}
  ${method(sharedMedia, 'public float getTabVisibility(int type, boolean includeSubtabs)')}
 }
 static class Profile {
  boolean myProfile=true,profileLifecycleDestroyed,refreshGiftsOnReturn,savedScrollToSharedMedia=true;
  int savedScrollPosition=8,collapses;RecyclerListView listView=new RecyclerListView();LayoutManager layoutManager=new LayoutManager();Media sharedMediaLayout=new Media();
  int getHeaderExtraHeight(){return 120;}void collapseAvatarInstant(){collapses++;}
  MessagesController getMessagesController(){return MessagesController.getInstance(3);}ConnectionsManager getConnectionsManager(){return ConnectionsManager.getInstance(3);}int getClassGuid(){return 1;}
  ${method(profile, 'public void resetMainTabScroll()')}
 }
 static class Groups {
  Profile profileActivity=new Profile();long dialog_id=77;boolean destroyed,loading,endReached,refreshPending;
  ArrayList<TLRPC.Chat> chats=new ArrayList<>();void notifyDataSetChanged(){}
  ${method(groups, 'private void refresh()')}
  ${method(groups, 'private void getChats(long max_id, final int count)')}
 }
 static int checks;
 static void check(boolean b,String why){checks++;if(!b)throw new AssertionError(why);}
 static TLObject page(String offset,String...items){TL_stars.TL_payments_savedStarGifts r=new TL_stars.TL_payments_savedStarGifts();r.gifts.addAll(Arrays.asList(items));r.next_offset=offset;r.count=4;return r;}
 public static void main(String[]args){
  VisibleProfile visible=new VisibleProfile();StarsController controller=StarsController.getInstance(9);
  GiftsList cached=new GiftsList();cached.currentAccount=9;cached.gifts.add("cached");cached.endReached=true;controller.lists.put(77L,cached);
  visible.refreshVisibleProfile();ConnectionsManager vc=ConnectionsManager.getInstance(9);
  check(cached.loading&&cached.gifts.equals(List.of("cached")),"first visible retained profile refreshes stale cache without removing contents");
  int firstRequest=vc.next;visible.refreshVisibleProfile();
  check(vc.next==firstRequest&&!cached.refreshPending,"repeated visible callback does not restart gift refresh");
  vc.response(firstRequest,page(null,"received","cached"));
  check(controller.hasProfileGifts(77)&&cached.gifts.get(0).equals("received"),"fresh list drives gift presence independently from stale full-user count");
  check(!controller.hasProfileGifts(123)&&!controller.lists.containsKey(123L),"presence query cannot create a list or a network request");
  visible.refreshGiftsOnReturn=true;visible.refreshVisibleProfile();
  check(vc.next==firstRequest+1,"next main-tab visit refreshes existing profile");vc.response(vc.next,null);
  visible.refreshGiftsOnReturn=true;visible.profileLifecycleDestroyed=true;visible.refreshVisibleProfile();
  check(vc.next==firstRequest+1,"destroyed profile cannot refresh");
  VisibleProfile empty=new VisibleProfile();empty.userId=123;empty.refreshVisibleProfile();
  check(controller.lists.get(123L).loading,"missing cache starts request on first visible profile");
  TabGeometry geometry=new TabGeometry();geometry.mediaPages[0].selectedType=1;geometry.mediaPages[1].selectedType=2;geometry.mediaPages[1].visibility=View.GONE;
  check(geometry.getTabVisibility(2,false)==0&&geometry.getTabTranslationX(2,false)==1080,"hidden recycled page cannot expose gift button");
  geometry.mediaPages[1].visibility=View.VISIBLE;
  for(int direction:new int[]{-1,1})for(int step=0;step<=100;step++){
   float f=step/100f;geometry.mediaPages[0].x=-direction*f*1080;geometry.mediaPages[1].x=direction*(1-f)*1080;
   check(Math.abs(geometry.getTabVisibility(1,false)-(1-f))<0.0001f&&Math.abs(geometry.getTabVisibility(2,false)-f)<0.0001f,"both swipe directions retain continuous button visibility");
  }
  geometry.mediaPages[0].x=geometry.mediaPages[1].x=0;geometry.mediaPages[1].selectedType=3;
  check(geometry.getTabVisibility(1,true)==1&&geometry.getTabTranslationX(1,true)==0,"shared album button stays stationary with bounded visibility");
  geometry.width=0;check(geometry.getTabVisibility(1,true)==0,"unmeasured layout never produces NaN");
  GiftsList g=new GiftsList();ConnectionsManager c=ConnectionsManager.getInstance(0);
  g.gifts.add("old");g.totalCount=1;g.endReached=true;
  g.load();check(!g.loading,"completed pagination remains cached");
  g.refresh();int id=g.currentRequestId;
  check(g.loading&&g.gifts.equals(List.of("old")),"refresh retains visible gifts");
  check(c.requests.get(id).offset.equals(""),"refresh starts at first page");
  c.response(id,page("next","new","old"));
  check(g.gifts.equals(List.of("new","old"))&&!g.endReached,"success replaces snapshot and restores paging");
  g.load();id=g.currentRequestId;check(c.requests.get(id).offset.equals("next"),"pagination uses fresh cursor");
  g.refresh();g.refresh();check(g.refreshPending&&g.currentRequestId==id,"parallel invalidations coalesce");
  c.response(id,page(null,"tail"));int refreshId=g.currentRequestId;
  check(refreshId!=id&&g.loading&&!g.refreshPending,"pending refresh runs after active page");
  check(g.gifts.size()==3,"pending refresh keeps loaded contents");
  c.response(refreshId,null);check(g.gifts.size()==3&&g.endReached&&!g.loading,"failed refresh keeps previous snapshot and terminal cursor");
  g.refresh();id=g.currentRequestId;c.response(id,page("cursor","replacement"));
  g.refresh();id=g.currentRequestId;c.response(id,null);
  check(g.lastOffset.equals("cursor")&&!g.endReached,"failed refresh retains valid continuation");
  g.refresh();id=g.currentRequestId;g.refresh();g.cancel();
  check(!g.refreshPending&&!g.loading,"cancel drops deferred refresh");
  c.response(id,page(null,"stale"));check(g.gifts.equals(List.of("replacement")),"cancelled callback cannot replace current gifts");
  g.refresh();id=g.currentRequestId;g.invalidate(true);int newId=g.currentRequestId;
  check(g.gifts.isEmpty()&&newId!=id,"filter invalidation still clears incompatible snapshot");
  c.response(id,page(null,"wrong-filter"));check(g.gifts.isEmpty(),"previous filter response ignored");
  c.response(newId,page(null,"filtered"));check(g.gifts.equals(List.of("filtered")),"new filter applied");
  GiftsList other=new GiftsList();other.currentAccount=1;other.refresh();
  check(ConnectionsManager.getInstance(1).requests.size()==1,"requests remain account scoped");
  check(g.gifts.equals(List.of("filtered")),"other account cannot change current snapshot");
  Fetcher f=new Fetcher();f.channel_id=99;f.message_id=5;f.loading=true;f.messageObjects.add("previous");f.fetch(null);
  check(!f.loading&&f.loaded&&f.channel_id==0&&f.message_id==0&&f.messageObjects.isEmpty(),"removed personal channel resets identity and loading");
  TLRPC.UserFull info=new TLRPC.UserFull();info.flags2=64;info.personal_channel_id=99;info.personal_channel_message=5;f.fetch(info);
  check(f.fetches==1,"reattached channel can fetch again");
  GiftsCollections lists=new GiftsCollections();ConnectionsManager cc=ConnectionsManager.getInstance(2);
  lists.load();id=lists.currentRequestId;cc.response(id,null);
  check(!lists.loading&&!lists.loaded&&lists.currentRequestId==-1,"collection network failure permits retry");
  lists.load();id=lists.currentRequestId;
  TL_stars.TL_starGiftCollections result=new TL_stars.TL_starGiftCollections();TL_stars.TL_starGiftCollection collection=new TL_stars.TL_starGiftCollection();collection.collection_id=12;result.collections.add(collection);
  cc.response(id,result);check(lists.loaded&&lists.collections.size()==1,"collection retry publishes result");
  lists.invalidate(true);id=lists.currentRequestId;lists.invalidate(true);newId=lists.currentRequestId;
  cc.response(id,new TL_stars.TL_starGiftCollections());check(lists.collections.size()==1&&lists.loading,"stale collections response cannot overwrite current content");
  cc.response(newId,new TL_stars.TL_starGiftCollectionsNotModified());check(lists.loaded&&!lists.loading&&lists.collections.size()==1,"hash response retains collections");
  Profile p=new Profile();p.resetMainTabScroll();
  check(p.listView.stops==1&&p.sharedMediaLayout.list.stops==1&&p.sharedMediaLayout.list.position==0,"tab departure stops outer and nested flings");
  check(p.layoutManager.position==0&&p.layoutManager.offset==-24,"outer profile returns to collapsed header using actual padding");
  check(p.savedScrollPosition==-1&&!p.savedScrollToSharedMedia&&p.refreshGiftsOnReturn,"old media anchor cleared and fresh gifts requested for next visit");
  p=new Profile();p.myProfile=false;p.resetMainTabScroll();check(p.listView.stops==0&&!p.refreshGiftsOnReturn,"other profiles untouched");
  p=new Profile();p.profileLifecycleDestroyed=true;p.resetMainTabScroll();check(p.listView.stops==0,"destroyed profile untouched");
  p=new Profile();p.sharedMediaLayout=null;p.resetMainTabScroll();check(p.layoutManager.position==0,"profile without publications resets safely");
  Groups groups=new Groups();groups.chats.add(new TLRPC.Chat(1));ConnectionsManager gc=ConnectionsManager.getInstance(3);
  groups.refresh();id=gc.next;check(groups.chats.size()==1,"groups stay visible during refresh");
  TLRPC.messages_Chats groupResult=new TLRPC.messages_Chats();groupResult.chats.add(new TLRPC.Chat(2));gc.response(id,groupResult);
  check(groups.chats.size()==1&&groups.chats.get(0).id==2,"groups refresh replaces rather than appends snapshot");
  groups.refresh();id=gc.next;gc.callbacks.get(id).accept(null,new Object());
  check(!groups.loading&&groups.chats.get(0).id==2,"groups survive network error");
  groups.getChats(2,100);id=gc.next;groups.refresh();groups.refresh();gc.response(id,groupResult);
  check(groups.loading&&!groups.refreshPending&&gc.next==id+1,"group refresh during pagination coalesced");
  gc.response(gc.next,new TLRPC.messages_Chats());check(groups.chats.isEmpty(),"removed common groups clear on successful response");
  groups.chats.add(new TLRPC.Chat(3));groups.refresh();id=gc.next;groups.destroyed=true;gc.response(id,new TLRPC.messages_Chats());
  check(groups.chats.size()==1,"closed profile rejects group response");
  System.out.println("PASS: "+checks+" actual gift request and channel invalidation checks");
 }
}`;
const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'nimarko-profile-refresh-'));
function run(code) {
    fs.writeFileSync(path.join(dir, 'ProfileRefreshTest.java'), code);
    const build=cp.spawnSync('javac',['ProfileRefreshTest.java'],{cwd:dir,encoding:'utf8'});
    assert.equal(build.status,0,build.stderr);
    return cp.spawnSync('java',['ProfileRefreshTest'],{cwd:dir,encoding:'utf8'});
}
try {
    const result=run(java);assert.equal(result.status,0,result.stderr);process.stdout.write(result.stdout);
    const stale=run(java.replace('if (reqId[0] != currentRequestId) return;',''));
    assert.notEqual(stale.status,0,'stale response guard must be tested');
    const cleared=run(java.replace('load(true);','gifts.clear(); load(true);'));
    assert.notEqual(cleared.status,0,'clearing visible gifts must fail');
    const blocked=run(java.replace('currentRequestId = -1;\n                loading = false;', 'currentRequestId = -1;'));
    assert.notEqual(blocked.status,0,'collection error must release loading');
    console.log('PASS: stale callback and destructive refresh negative controls rejected');
} finally {fs.rmSync(dir,{recursive:true,force:true});}
