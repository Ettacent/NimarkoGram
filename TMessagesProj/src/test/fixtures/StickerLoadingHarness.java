import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class StickerLoadingHarness {
    static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    static void drain() {
        int guard = 200;
        while (!Queue.background.isEmpty() || !AndroidUtilities.ui.isEmpty()) {
            check(--guard > 0, "unbounded retry loop");
            if (!Queue.background.isEmpty()) {
                AndroidUtilities.onUi = false;
                Queue.background.remove().run();
                AndroidUtilities.onUi = true;
            }
            while (!AndroidUtilities.ui.isEmpty()) AndroidUtilities.ui.remove().run();
        }
    }
    static TLRPC.InputStickerSet key(long id) { TLRPC.InputStickerSet k = new TLRPC.TL_inputStickerSetID(); k.id = id; return k; }
    static TLRPC.TL_messages_stickerSet pack(long id) {
        TLRPC.TL_messages_stickerSet p = new TLRPC.TL_messages_stickerSet();
        p.set = new TLRPC.StickerSet(); p.set.id = id; p.set.hash = (int) id; p.set.short_name = "pack" + id;
        p.documents.add(new TLRPC.Document()); return p;
    }
    static TLRPC.TL_messages_allStickers list(long... ids) {
        TLRPC.TL_messages_allStickers l = new TLRPC.TL_messages_allStickers(); l.hash2 = 987;
        for (long id : ids) l.sets.add(pack(id).set); return l;
    }
    static TLRPC.TL_error error() { TLRPC.TL_error e = new TLRPC.TL_error(); e.text = "FAIL"; return e; }
    static void fetch(MediaDataController c, boolean cache, Utilities.Callback<TLRPC.TL_messages_stickerSet> cb) {
        c.getStickerSet(key(1), null, cache, false, cb);
    }
    static void rpc(MediaDataController c, int id, TLObject result, TLRPC.TL_error error) { c.net.reply(id, result, error); drain(); }
    static void load(MediaDataController c, Utilities.Callback<ArrayList<TLRPC.TL_messages_stickerSet>> cb) { c.loadStickers(0, false, false, false, cb); }
    static void assertIdle(MediaDataController c) {
        check(!c.loadingStickers[0], "loading flag stuck");
        check(c.requestsEmpty(), "request leaked");
        check(c.center.observers.isEmpty(), "connection observer leaked");
    }
    public static void main(String[] args) {
        MediaDataController c = new MediaDataController(0);
        int[] calls = {0};
        Utilities.Callback<TLRPC.TL_messages_stickerSet> cb = p -> { check(AndroidUtilities.onUi, "callback off UI"); calls[0]++; };
        switch (args[0]) {
            case "cache_probe_then_network":
                fetch(c, true, null); fetch(c, false, cb); drain();
                check(c.net.sent == 1, "cache-only probe suppressed remote");
                rpc(c, 1, pack(1), null); check(calls[0] == 1, "callback missing"); break;
            case "cache_probe_keeps_active_key":
                fetch(c, false, cb); drain();
                fetch(c, true, cb); drain();
                check(c.loadingStickerSetsKeys.size() == 1, "cache probe removed active key");
                fetch(c, false, null); drain(); check(c.net.sent == 1, "duplicate RPC");
                rpc(c, 1, pack(1), null); check(calls[0] == 2, "consumers not completed"); break;
            case "malformed_cache":
                c.storage.cached = new TLRPC.TL_messages_stickerSet();
                fetch(c, false, cb); drain(); check(c.net.sent == 1, "invalid cache must fetch");
                TLRPC.TL_messages_stickerSet unnamed = pack(1); unnamed.set.short_name = null;
                rpc(c, 1, unnamed, null); check(calls[0] == 1, "null name lost callback"); break;
            case "fetch_error_reentry":
                fetch(c, false, p -> { calls[0]++; fetch(c, false, cb); });
                fetch(c, false, cb); drain(); check(c.net.sent == 1, "not coalesced");
                rpc(c, 1, null, error()); check(calls[0] == 2 && c.net.sent == 2, "reentry lost or consumed as old failure");
                check(c.loadingStickerSetsKeys.size() == 1, "old consumer removed retry key");
                rpc(c, 2, pack(1), null); check(calls[0] == 3, "retry not delivered"); break;
            case "fetch_throwing_consumer":
                fetch(c, false, p -> { throw new IllegalStateException("consumer"); }); fetch(c, false, cb); drain();
                rpc(c, 1, null, error()); check(calls[0] == 1 && c.loadingStickerSets.isEmpty(), "throw stranded consumers"); break;
            case "fetch_unexpected_response":
                fetch(c, false, cb); drain(); rpc(c, 1, new TLObject(), null);
                check(calls[0] == 1 && c.loadingStickerSets.isEmpty(), "invalid response pinned key"); break;
            case "fanout_partial_failure_retry":
                load(c, p -> calls[0]++); rpc(c, 1, list(1, 2), null);
                check(calls[0] == 0 && c.loadingStickers[0], "aggregate finished before children");
                rpc(c, 3, null, error()); rpc(c, 2, pack(1), null); assertIdle(c);
                check(calls[0] == 1 && c.loadHash[0] == 0 && c.storage.db.writes == 0, "partial list cached as complete");
                c.loadRecents(0, false, true, false); check(c.net.sent == 4, "keyboard reopen did not retry");
                rpc(c, 4, list(1, 2), null); rpc(c, 6, pack(2), null); rpc(c, 5, pack(1), null);
                assertIdle(c); check(c.packCount() == 2 && c.loadHash[0] == 987, "retry didn't recover"); break;
            case "fanout_cancel":
                load(c, p -> calls[0]++); rpc(c, 1, list(1, 2), null);
                c.cancelStickerRequest(2); rpc(c, 3, pack(2), null); assertIdle(c);
                check(calls[0] == 1 && c.loadHash[0] == 0, "cancel not counted by aggregate");
                rpc(c, 2, pack(1), null); check(calls[0] == 1, "late cancelled response applied"); break;
            case "fanout_duplicate_ids":
                load(c, p -> calls[0]++); rpc(c, 1, list(1, 1), null);
                rpc(c, 2, pack(1), null); rpc(c, 3, pack(1), null); assertIdle(c);
                check(calls[0] == 1, "aggregate counted ids instead of completions"); break;
            case "rpc_error_hash_zero":
            case "rpc_error_existing_hash":
                c.loadHash[0] = args[0].endsWith("existing_hash") ? 77 : 0;
                load(c, p -> { check(AndroidUtilities.onUi, "finish off UI"); calls[0]++; });
                rpc(c, 1, null, error()); AndroidUtilities.expire(); drain(); assertIdle(c);
                check(c.net.sent == 1 && calls[0] == 1 && c.loadDate[0] == 0 && c.storage.db.writes == 0, "error retried or marked fresh");
                c.checkStickers(0); check(c.net.sent == 2, "explicit retry suppressed"); break;
            case "not_modified":
                c.loadHash[0] = 77; load(c, p -> calls[0]++); rpc(c, 1, new TLRPC.TL_messages_allStickersNotModified(), null);
                assertIdle(c); check(calls[0] == 1 && c.loadDate[0] > 0 && c.loadHash[0] == 77, "NotModified failed");
                check(c.storage.db.sql.contains("WHERE id = ?") && c.storage.db.boundType == 1, "date update affects other types"); break;
            case "publish_before_finish":
                load(c, p -> { check(c.packCount() == 1, "callback before publication"); calls[0]++; });
                rpc(c, 1, list(1), null);
                c.net.reply(2, pack(1), null); while (!AndroidUtilities.ui.isEmpty()) AndroidUtilities.ui.remove().run();
                check(c.loadingStickers[0] && calls[0] == 0, "loading retired before stage publish");
                drain(); check(calls[0] == 1, "finish missing"); break;
            case "scheduled_reentry":
                load(c, p -> { calls[0]++; load(c, q -> calls[0]++); });
                c.loadStickers(0, false, false, true, p -> calls[0]++);
                rpc(c, 1, list(), null); check(c.net.sent == 2, "reentry duplicate");
                rpc(c, 2, list(), null); check(c.net.sent == 3, "scheduled request lost on reentry");
                rpc(c, 3, list(), null); check(calls[0] == 3, "scheduled completion missing"); break;
            case "cleanup_stale_rpc":
                load(c, p -> calls[0]++); c.cleanup(); load(c, p -> calls[0]++);
                rpc(c, 1, list(1), null); check(c.loadingStickers[0] && c.net.sent == 2, "old response changed new load");
                rpc(c, 2, list(), null); check(calls[0] == 1, "old session callback");
                for (int i = 0; i < 7; i++) { c.loadingStickers[i] = true; c.scheduledLoadStickers[i] = () -> {}; }
                c.cleanup(); for (int i = 0; i < 7; i++) check(!c.loadingStickers[i] && c.scheduledLoadStickers[i] == null, "type " + i + " not cleaned"); break;
            case "cleanup_stale_storage":
                fetch(c, false, cb); c.cleanup(); fetch(c, false, cb); drain();
                check(c.net.sent == 1, "stale storage read started network"); rpc(c, 1, pack(1), null); check(calls[0] == 1, "old DB callback"); break;
            case "cleanup_stale_stage":
                load(c, p -> calls[0]++); c.net.reply(1, list(), null);
                while (!AndroidUtilities.ui.isEmpty()) AndroidUtilities.ui.remove().run();
                c.cleanup(); load(c, p -> calls[0]++); drain();
                check(c.loadingStickers[0] && c.storage.db.writes == 0, "stale stage published");
                rpc(c, 2, list(), null); check(calls[0] == 1, "stale stage callback"); break;
            case "multiaccount":
                MediaDataController b = new MediaDataController(1); fetch(c, false, cb); fetch(b, false, cb); drain();
                c.cleanup(); rpc(c, 1, pack(1), null); rpc(b, 1, pack(1), null);
                check(calls[0] == 1 && b.stickerSetsById.size() == 1 && c.stickerSetsById.size() == 0, "accounts crossed"); break;
            case "connected_timeout_retry":
                load(c, p -> calls[0]++); AndroidUtilities.expire(); drain(); assertIdle(c);
                check(calls[0] == 1 && c.net.cancelled.contains(1), "stuck RPC not retired");
                c.loadRecents(0, false, true, false); rpc(c, 1, list(9), null); rpc(c, 2, list(), null);
                assertIdle(c); check(c.packCount() == 0 && c.net.sent == 2, "late response overwrote retry"); break;
            case "offline_wait_reconnect":
                c.net.state = 1; load(c, p -> calls[0]++); AndroidUtilities.expire(); drain();
                check(c.loadingStickers[0] && calls[0] == 0 && c.net.cancelled.isEmpty(), "offline normal request cancelled");
                c.connection(3); rpc(c, 1, list(), null); assertIdle(c); check(calls[0] == 1 && c.net.sent == 1, "reconnect didn't reuse request"); break;
            case "disconnect_pauses_timeout":
                load(c, p -> calls[0]++); c.connection(1); AndroidUtilities.expire();
                check(c.loadingStickers[0] && c.net.cancelled.isEmpty(), "disconnect didn't pause");
                c.connection(3); AndroidUtilities.expire(); drain(); assertIdle(c); check(calls[0] == 1, "reconnect didn't rearm"); break;
            case "processing_failure_retry":
                load(c, p -> { check(AndroidUtilities.onUi, "processing failure off UI"); calls[0]++; });
                rpc(c, 1, list(1), null);
                TLRPC.TL_messages_stickerSet broken = pack(1); broken.documents = null;
                rpc(c, 2, broken, null); assertIdle(c);
                check(calls[0] == 1 && c.loadHash[0] == 0 && c.storage.db.writes == 0, "processing exception didn't fail cleanly");
                c.loadRecents(0, false, true, false); rpc(c, 3, list(), null); assertIdle(c); break;
            case "processing_null_name":
                load(c, p -> calls[0]++); rpc(c, 1, list(1), null);
                TLRPC.TL_messages_stickerSet noName = pack(1); noName.set.short_name = null;
                rpc(c, 2, noName, null); assertIdle(c);
                check(calls[0] == 1 && c.packCount() == 1, "null name broke ConcurrentHashMap publication");
                load(c, p -> calls[0]++); rpc(c, 3, list(), null); assertIdle(c); break;
            case "cache_parse_failure_retry":
                c.storage.db.cacheRow = new TLRPC.TL_messages_stickerSet();
                c.loadStickers(0, true, false, false, p -> { check(AndroidUtilities.onUi, "cache failure off UI"); calls[0]++; });
                drain(); assertIdle(c); check(calls[0] == 1 && c.loadDate[0] == 0, "null cache metadata pinned loading");
                c.loadRecents(0, false, true, false); check(c.net.sent == 1, "bad disk cache prevented remote retry");
                rpc(c, 1, list(), null); assertIdle(c); break;
            case "cleanup_cache_write":
                load(c, p -> calls[0]++); c.net.reply(1, list(), null);
                while (!AndroidUtilities.ui.isEmpty()) AndroidUtilities.ui.remove().run();
                AndroidUtilities.onUi = false; Queue.background.remove().run(); AndroidUtilities.onUi = true;
                while (!AndroidUtilities.ui.isEmpty()) AndroidUtilities.ui.remove().run();
                c.cleanup(); drain(); check(c.storage.db.writes == 0, "old session wrote queued cache"); break;
            default:
                popup(args[0], c); break;
        }
        System.out.println("PASS " + args[0]);
    }
    static void popup(String scenario, MediaDataController c) {
        StickersAlert a = new StickersAlert(0); a.loadStickerSet(false); a.loadStickerSet(false);
        check(c.net.sent == 1, "popup reentry duplicate");
        switch (scenario) {
            case "popup_error_reopen":
                rpc(c, 1, null, error()); check(a.dismissed && a.stickerSetReqId == 0, "error spinner stuck");
                a = new StickersAlert(0); a.loadStickerSet(false); rpc(c, 2, pack(1), null);
                check(!a.dismissed && a.stickerSet != null, "popup reopen didn't recover"); break;
            case "popup_cancel_late":
                a.dismiss(); rpc(c, 1, pack(1), null);
                check(a.stickerSet == null && c.net.cancelled.contains(1) && c.requestsEmpty(), "cancelled popup mutated"); break;
            case "popup_force_generation":
                a.loadStickerSet(true); rpc(c, 1, pack(9), null);
                check(a.stickerSet == null && a.stickerSetReqId == 2, "old response cleared new id");
                rpc(c, 2, pack(1), null); check(a.stickerSet.set.id == 1, "new popup result lost"); break;
            case "popup_cache_supersedes_rpc":
                a.updateStickerSet(pack(2)); rpc(c, 1, pack(1), null);
                check(a.stickerSet.set.id == 2 && c.net.cancelled.contains(1), "late RPC overwrote explicit update"); break;
            case "popup_timeout_reopen":
                AndroidUtilities.expire(); check(a.dismissed, "popup timeout spinner stuck");
                a = new StickersAlert(0); a.loadStickerSet(false); rpc(c, 2, pack(1), null);
                check(a.stickerSet != null && !a.dismissed, "timeout reopen failed"); break;
            default: throw new AssertionError(scenario);
        }
    }
}

class BaseController { final int currentAccount; BaseController(int a) { currentAccount=a; } }
class MediaDataController extends BaseController {
    static final int TYPE_IMAGE=0, TYPE_MASK=1, TYPE_FEATURED=3, TYPE_EMOJI=4, TYPE_EMOJIPACKS=5, TYPE_FEATURED_EMOJIPACKS=6;
    static final Map<Integer, MediaDataController> instances = new HashMap<>();
    static MediaDataController getInstance(int a) { return instances.get(a); }
    final ConnectionsManager net = new ConnectionsManager(); final Storage storage = new Storage(); final NotificationCenter center = new NotificationCenter();
    MediaDataController(int a) { super(a); center.account=a; instances.put(a,this); }
    ConnectionsManager getConnectionsManager() { return net; } Storage getMessagesStorage() { return storage; }
    NotificationCenter getNotificationCenter() { return center; } MessagesController getMessagesController() { return new MessagesController(); }
    void connection(int state) { net.state=state; center.postNotificationName(NotificationCenter.didUpdateConnectionState); }
    boolean[] loadingStickers = new boolean[7], stickersLoaded = new boolean[7], stickerLoadFailed = new boolean[7];
    int[] stickerLoadGeneration = new int[7], loadDate = new int[7]; long[] loadHash = new long[7];
    Runnable[] scheduledLoadStickers = new Runnable[7]; volatile int stickerSetGeneration;
    LongSparseArray<TLRPC.TL_messages_stickerSet> stickerSetsById=new LongSparseArray<>(), installedStickerSetsById=new LongSparseArray<>();
    ConcurrentHashMap<String,TLRPC.TL_messages_stickerSet> stickerSetsByName=new ConcurrentHashMap<>();
    TLRPC.TL_messages_stickerSet stickerSetDefaultStatuses, stickerSetDefaultChannelStatuses;
    HashMap<String,Object> loadingStickerSetsKeys=new HashMap<>();
    HashMap<String,ArrayList<Utilities.Callback2<Boolean,TLRPC.TL_messages_stickerSet>>> loadingStickerSets=new HashMap<>();
    LongSparseArray<Runnable> removingStickerSetsUndos=new LongSparseArray<>();
    HashMap<String,ArrayList<TLRPC.Document>> allStickers=new HashMap<>(), allStickersFeatured=new HashMap<>();
    LongSparseArray<String> stickersByEmoji=new LongSparseArray<>();
    ArrayList<TLRPC.StickerSetCovered>[] featuredStickerSets = new ArrayList[]{new ArrayList<>(),new ArrayList<>()}; long[] loadFeaturedHash = new long[2];
    ArrayList[] recentStickers = new ArrayList[9]; boolean[] loadingRecentStickers=new boolean[9], recentStickersLoaded=new boolean[9];
    void loadArchivedStickersCount(int t, boolean c) {}
    static long calcHash(long a, long b) { return a*31+b; }
    TLRPC.TL_messages_stickerSet getCachedStickerSetInternal(long id, Integer hash) { return storage.cached; }
    TLRPC.TL_messages_stickerSet getCachedStickerSetInternal(String name, Integer hash) { return storage.cached; }
    void saveStickerSetIntoCache(TLRPC.TL_messages_stickerSet set) {}
    TLRPC.TL_messages_stickerSet getStickerSetById(long id) { return stickerSetsById.get(id); }
    TLRPC.TL_messages_stickerSet getStickerSetByName(String name) { return stickerSetsByName.get(name); }
    void putStickerSet(TLRPC.TL_messages_stickerSet set, boolean notify) { stickerSetsById.put(set.set.id,set); }
    void preloadStickerSetThumb(TLRPC.TL_messages_stickerSet set) {}
    boolean requestsEmpty() { return stickerRequestTimeouts.isEmpty(); }
    int packCount() { return stickerSets[0].size(); }

}
class TLObject {}
interface RequestDelegate { void run(TLObject response, TLRPC.TL_error error); }
class TLRPC {
    static class TL_error { int code; String text; }
    static class InputStickerSet { long id,access_hash; String short_name; }
    static class TL_inputStickerSetID extends InputStickerSet {}
    static class TL_inputStickerSetShortName extends InputStickerSet {}
    static class TL_inputStickerSetEmpty extends InputStickerSet {}
    static class TL_inputStickerSetAnimatedEmoji extends InputStickerSet {}
    static class TL_inputStickerSetEmojiGenericAnimations extends InputStickerSet {}
    static class TL_inputStickerSetEmojiChannelDefaultStatuses extends InputStickerSet {}
    static class TL_inputStickerSetDice extends InputStickerSet { String emoticon; }
    static class TL_inputStickerSetPremiumGifts extends InputStickerSet {}
    static class TL_inputStickerSetEmojiDefaultTopicIcons extends InputStickerSet {}
    static class TL_inputStickerSetEmojiDefaultStatuses extends InputStickerSet {}
    static class TL_inputStickerSetTonGifts extends InputStickerSet {}
    static class StickerSet { long id,access_hash; int hash; String short_name; boolean archived,installed,official,masks; }
    static class StickerSetCovered { StickerSet set; }
    static class Document { long id; } static class TL_documentEmpty extends Document {}
    static class TL_stickerPack { String emoticon; ArrayList<Long> documents=new ArrayList<>(); }
    static class TL_messages_stickerSet extends TLObject {
        StickerSet set; ArrayList<Document> documents=new ArrayList<>(); ArrayList<TL_stickerPack> packs=new ArrayList<>();
        static TL_messages_stickerSet TLdeserialize(NativeByteBuffer b,int v,boolean f) { return b.set; }
        int getObjectSize() { return 4; } void serializeToStream(NativeByteBuffer b) {}
    }
    static class TL_messages_allStickers extends TLObject { long hash2; ArrayList<StickerSet> sets=new ArrayList<>(); }
    static class TL_messages_allStickersNotModified extends TLObject {}
    static class TL_messages_getStickerSet extends TLObject { InputStickerSet stickerset; }
    static class TL_messages_getAllStickers extends TLObject { long hash; }
    static class TL_messages_getEmojiStickers extends TLObject { long hash; }
    static class TL_messages_getMaskStickers extends TLObject { long hash; }
}
class ConnectionsManager {
    static final int RequestFlagFailOnServerErrors=2, ConnectionStateConnected=3, ConnectionStateUpdating=5;
    int state=3, sent; final Map<Integer,RequestDelegate> delegates=new HashMap<>(); final Set<Integer> cancelled=new HashSet<>();
    int getConnectionState() { return state; }
    int sendRequest(TLObject request, RequestDelegate delegate, int flags) { delegates.put(++sent,delegate); return sent; }
    void cancelRequest(int id, boolean server) { cancelled.add(id); }
    void reply(int id,TLObject response,TLRPC.TL_error error) {
        AndroidUtilities.onUi=false; delegates.get(id).run(response,error); AndroidUtilities.onUi=true;
    }
}
class AndroidUtilities {
    static boolean onUi=true; static ArrayDeque<Runnable> ui=new ArrayDeque<>(); static LinkedHashSet<Runnable> timers=new LinkedHashSet<>();
    static void runOnUIThread(Runnable r) { if(onUi) r.run(); else ui.add(r); }
    static void runOnUIThread(Runnable r,long delay) { timers.add(r); }
    static void cancelRunOnUIThread(Runnable r) { timers.remove(r); ui.remove(r); }
    static void expire() { List<Runnable> copy=new ArrayList<>(timers); timers.clear(); for(Runnable r:copy) r.run(); }
}
class Queue { static ArrayDeque<Runnable> background=new ArrayDeque<>(); void postRunnable(Runnable r) { background.add(r); } }
class Utilities { static Queue stageQueue=new Queue(); interface Callback<T> { void run(T v); } interface Callback2<T,U> { void run(T v,U w); } }
class NotificationCenter {
    static final int groupStickersDidLoad=1, stickersDidLoad=2, didUpdateConnectionState=3; int account;
    interface NotificationCenterDelegate { void didReceivedNotification(int id,int account,Object...args); }
    Set<NotificationCenterDelegate> observers=new HashSet<>();
    void addObserver(NotificationCenterDelegate d,int id) { observers.add(d); } void removeObserver(NotificationCenterDelegate d,int id) { observers.remove(d); }
    void postNotificationName(int id,Object...args) {
        if(id==didUpdateConnectionState) for(NotificationCenterDelegate d:new ArrayList<>(observers)) d.didReceivedNotification(id,account,args);
    }
}
class LongSparseArray<T> {
    LinkedHashMap<Long,T> map=new LinkedHashMap<>(); T get(long k) { return map.get(k); } void put(long k,T v) { map.put(k,v); }
    boolean containsKey(long k) { return map.containsKey(k); } void remove(long k) { map.remove(k); } void clear() { map.clear(); }
    int size() { return map.size(); } int indexOfKey(long k) { return new ArrayList<>(map.keySet()).indexOf(k); }
    long keyAt(int i) { return new ArrayList<>(map.keySet()).get(i); } T valueAt(int i) { return new ArrayList<>(map.values()).get(i); }
}
class Storage { Queue queue=new Queue(); Database db=new Database(); TLRPC.TL_messages_stickerSet cached; Queue getStorageQueue() { return queue; } Database getDatabase() { return db; } }
class Database { int writes,boundType; String sql=""; TLRPC.TL_messages_stickerSet cacheRow; SQLiteCursor queryFinalized(String sql) { return new SQLiteCursor(cacheRow); } SQLitePreparedStatement executeFast(String s) { sql=s; return new SQLitePreparedStatement(this); } }
class SQLiteCursor {
    TLRPC.TL_messages_stickerSet row; SQLiteCursor(TLRPC.TL_messages_stickerSet r) { row=r; }
    boolean next() { return row!=null; } NativeByteBuffer byteBufferValue(int i) { NativeByteBuffer b=new NativeByteBuffer(4); b.set=row; return b; }
    int intValue(int i) { return (int)(System.currentTimeMillis()/1000); } void dispose() {}
}
class SQLitePreparedStatement {
    Database db; SQLitePreparedStatement(Database d) { db=d; } void requery() {} void bindInteger(int i,int v) { if(i==2) db.boundType=v; }
    void bindLong(int i,long v) {} void bindByteBuffer(int i,NativeByteBuffer b) {} void step() { db.writes++; } void dispose() {}
}
class NativeByteBuffer { TLRPC.TL_messages_stickerSet set; NativeByteBuffer(int size) {} int readInt32(boolean e) { return 1; } void writeInt32(int v) {} void reuse() {} }
class FileLog { static void e(Throwable t) {} }
class BuildVars { static final boolean DEBUG_PRIVATE_VERSION=false; }
class MessagesController { boolean preloadFeaturedStickers=true; }

class BottomSheet { boolean dismissed; boolean isDismissed() { return dismissed; } public void dismiss() { dismissed=true; } }
class StickersAlert extends BottomSheet {
    int currentAccount,stickerSetReqId,stickerSetRequestGeneration,scrollOffsetY; boolean showEmoji;
    TLRPC.InputStickerSet inputStickerSet=StickerLoadingHarness.key(1); TLRPC.TL_messages_stickerSet stickerSet;
    View containerView=new View(),container=new View(),gridView=new View(),titleTextView=new View(),optionsButton=new View();
    Adapter adapter=new Adapter(); Object parentFragment=new Object();
    StickersAlert(int account) { currentAccount=account; }
    void setScrollOffsetY(int y) {} void checkPremiumStickers() {} void updateSendButton() {} void updateFields() {} void updateDescription() {}

}
class View { static int VISIBLE=0; int getTop() { return 0; } void setAlpha(float v) {} void setTranslationY(float v) {} void setVisibility(int v) {} }
class ViewGroup extends View {}
class Adapter { void notifyDataSetChanged() {} }
class Animator {}
class ValueAnimator extends Animator { static ValueAnimator ofFloat(float a,float b) { return new ValueAnimator(); } void setDuration(int d) {} void addUpdateListener(Utilities.Callback<ValueAnimator> c) {} float getAnimatedFraction() { return 1; } }
abstract class Transition { abstract public void captureStartValues(TransitionValues v); abstract public void captureEndValues(TransitionValues v); abstract public Animator createAnimator(ViewGroup g,TransitionValues a,TransitionValues b); void addTarget(View v) {} }
class TransitionValues { Map<String,Object> values=new HashMap<>(); }
class TransitionManager { static void beginDelayedTransition(View v,Transition t) {} }
class BulletinFactory { static BulletinFactory of(Object p) { return new BulletinFactory(); } BulletinFactory createErrorBulletin(String s) { return this; } void show() {} }
class LocaleController { static String getString(int s) { return "error"; } }
class R { static class string { static int AddStickersNotFound=1,UnknownError=2; } }
