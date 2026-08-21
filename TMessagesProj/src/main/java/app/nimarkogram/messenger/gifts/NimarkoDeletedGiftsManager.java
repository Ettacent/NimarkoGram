package app.nimarkogram.messenger.gifts;

import android.os.Looper;
import android.os.SystemClock;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.ui.Stars.StarsController;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import app.nimarkogram.messenger.NimarkoConfig;

public final class NimarkoDeletedGiftsManager {

    private static final int INSERT_POSITION = 11;
    private static final String ASSET_DIR = "deleted_gifts";
    private static final long VERIFY_INTERVAL_MS = 5 * 60 * 1000L;
    private static final long STICKER_DOC_BASE_ID = 7_700_000_000_000_000_001L;

    private static final StickerSpec[] STICKERS = {
            new StickerSpec("gift_deleted_placeholder", 2_710),
            new StickerSpec("gift_newyear_bear", 44_297),
            new StickerSpec("gift_christmas_tree", 28_793),
            new StickerSpec("gift_valentine_bear", 63_397),
            new StickerSpec("gift_march8_bear", 63_587),
            new StickerSpec("gift_valentine_heart", 56_944),
            new StickerSpec("gift_leprechaun_bear", 65_119),
            new StickerSpec("gift_aprilfools_bear", 64_579),
            new StickerSpec("gift_easter_bear", 65_227),
            new StickerSpec("gift_builder_bear", 59_610),
            new StickerSpec("gift_championship_bear", 40_283),
            new StickerSpec("gift_terrorist_bear", 56_642)
    };

    public static final class Entry {
        public final long id;
        public final long price;
        public final int stickerNumber;

        Entry(long id, long price, int stickerNumber) {
            this.id = id;
            this.price = price;
            this.stickerNumber = stickerNumber;
        }
    }

    private static final List<Entry> GIFTS = Collections.unmodifiableList(Arrays.asList(
            new Entry(5956217000635139069L, 50L, 1),
            new Entry(5922558454332916696L, 50L, 2),
            new Entry(5800655655995968830L, 50L, 3),
            new Entry(5866352046986232958L, 50L, 4),
            new Entry(5801108895304779062L, 50L, 5),
            new Entry(5893356958802511476L, 50L, 6),
            new Entry(5935895822435615975L, 50L, 7),
            new Entry(5969796561943660080L, 50L, 8),
            new Entry(6026193266406327981L, 50L, 9),
            new Entry(5974210632977745012L, 50L, 10),
            new Entry(6046178578163303744L, 50L, 11)
    ));

    private static final Set<Long> GIFT_IDS = buildGiftIds();
    private static final TLRPC.Document[] LOCAL_DOCUMENTS = buildLocalDocuments();
    private static final Object PREPARE_LOCK = new Object();
    private static final Set<Integer> pendingAccounts = new HashSet<>();
    private static volatile boolean assetsReady;
    private static boolean prepareRunning;
    private static boolean prepareAgain;
    private static long lastVerifiedAt;

    private NimarkoDeletedGiftsManager() {
    }

    public static List<Entry> getGifts() {
        return GIFTS;
    }

    public static void onMediaDirectoriesChanged() {
        boolean enabled = NimarkoConfig.deletedGiftsInject;
        synchronized (PREPARE_LOCK) {
            assetsReady = false;
            lastVerifiedAt = 0;
            if (prepareRunning) {
                prepareAgain = true;
                return;
            }
        }
        if (enabled) {
            scheduleAssetPreparation(-1);
        }
    }

    public static void maybeInject(int account) {
        if (!NimarkoConfig.deletedGiftsInject || !isActiveAccount(account)) {
            return;
        }
        if (!assetsReady) {
            scheduleAssetPreparation(account);
            return;
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            tryInject(account);
        } else {
            AndroidUtilities.runOnUIThread(() -> {
                if (tryInject(account)) {
                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.starGiftsLoaded);
                }
            });
        }
        scheduleAssetPreparation(account);
    }

    public static void removeInjected(int account) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            AndroidUtilities.runOnUIThread(() -> removeInjected(account));
            return;
        }
        try {
            StarsController controller = StarsController.getInstance(account);
            if (controller == null) {
                return;
            }
            stripList(controller.gifts);
            stripList(controller.sortedGifts);
            stripList(controller.birthdaySortedGifts);
        } catch (Throwable t) {
            FileLog.e("nimarko-gifts: failed to remove local gifts", t);
        }
    }

    private static boolean isActiveAccount(int account) {
        return account >= 0
                && account < UserConfig.MAX_ACCOUNT_COUNT
                && UserConfig.getInstance(account).isClientActivated();
    }

    private static void scheduleAssetPreparation(int account) {
        synchronized (PREPARE_LOCK) {
            if (prepareRunning) {
                if (isActiveAccount(account)) {
                    pendingAccounts.add(account);
                }
                return;
            }
            if (assetsReady && SystemClock.elapsedRealtime() - lastVerifiedAt < VERIFY_INTERVAL_MS) {
                return;
            }
            if (isActiveAccount(account)) {
                pendingAccounts.add(account);
            }
            prepareRunning = true;
        }

        Utilities.globalQueue.postRunnable(() -> {
            PreparationResult result;
            try {
                result = prepareAssets();
            } catch (Throwable t) {
                FileLog.e("nimarko-gifts: local TGS preparation failed", t);
                result = new PreparationResult(false, false);
            }
            ArrayList<Integer> accounts = new ArrayList<>();
            boolean rerun;
            synchronized (PREPARE_LOCK) {
                rerun = prepareAgain;
                prepareAgain = false;
                prepareRunning = false;
                if (rerun || !result.success) {
                    assetsReady = false;
                    lastVerifiedAt = 0;
                } else {
                    assetsReady = true;
                    lastVerifiedAt = SystemClock.elapsedRealtime();
                    accounts = new ArrayList<>(pendingAccounts);
                    pendingAccounts.clear();
                }
            }
            if (rerun) {
                if (NimarkoConfig.deletedGiftsInject) {
                    scheduleAssetPreparation(-1);
                }
                return;
            }
            if (!result.success || accounts.isEmpty()) {
                return;
            }
            final boolean assetsChanged = result.changed;
            final ArrayList<Integer> accountsToNotify = accounts;
            AndroidUtilities.runOnUIThread(() -> {
                for (int accountId : accountsToNotify) {
                    if (!NimarkoConfig.deletedGiftsInject || !isActiveAccount(accountId)) {
                        continue;
                    }
                    boolean changed = tryInject(accountId);
                    if (changed || assetsChanged) {
                        NotificationCenter.getInstance(accountId)
                                .postNotificationName(NotificationCenter.starGiftsLoaded);
                    }
                }
            });
        });
    }

    private static PreparationResult prepareAssets() {
        try {
            File documentDir = FileLoader.getDirectory(FileLoader.MEDIA_DIR_DOCUMENT);
            File cacheDir = FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE);
            if (documentDir == null || cacheDir == null) {
                return new PreparationResult(false, false);
            }

            boolean changed = false;
            for (int i = 0; i < STICKERS.length; i++) {
                StickerSpec spec = STICKERS[i];
                String loaderName = FileLoader.getAttachFileName(LOCAL_DOCUMENTS[i]);
                int documentResult = ensureAssetFile(spec, new File(documentDir, loaderName));
                int cacheResult = documentDir.equals(cacheDir)
                        ? documentResult
                        : ensureAssetFile(spec, new File(cacheDir, loaderName));
                if (documentResult < 0 || cacheResult < 0) {
                    return new PreparationResult(false, changed);
                }
                changed |= documentResult > 0 || cacheResult > 0;
            }
            return new PreparationResult(true, changed);
        } catch (Throwable t) {
            FileLog.e("nimarko-gifts: local TGS preparation failed", t);
            return new PreparationResult(false, false);
        }
    }

    private static int ensureAssetFile(StickerSpec spec, File destination) {
        if (destination.exists() && destination.length() == spec.size) {
            return 0;
        }
        File parent = destination.getParentFile();
        if (parent == null || !parent.exists() && !parent.mkdirs()) {
            return -1;
        }

        File temporary = new File(parent, destination.getName() + ".nimarko.tmp");
        if (temporary.exists() && !temporary.delete()) {
            return -1;
        }
        long copied = 0;
        try (InputStream input = ApplicationLoader.applicationContext.getAssets()
                .open(ASSET_DIR + "/" + spec.name + ".tgs");
             FileOutputStream output = new FileOutputStream(temporary)) {
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
                copied += read;
            }
            output.flush();
        } catch (Throwable t) {
            temporary.delete();
            FileLog.e("nimarko-gifts: failed to extract " + spec.name, t);
            return -1;
        }
        if (copied != spec.size || temporary.length() != spec.size) {
            temporary.delete();
            return -1;
        }
        if (destination.exists() && !destination.delete()) {
            temporary.delete();
            return -1;
        }
        if (!temporary.renameTo(destination)) {
            try {
                AndroidUtilities.copyFile(temporary, destination);
            } catch (Throwable t) {
                temporary.delete();
                return -1;
            }
            temporary.delete();
        }
        return destination.exists() && destination.length() == spec.size ? 1 : -1;
    }

    private static boolean tryInject(int account) {
        if (!NimarkoConfig.deletedGiftsInject || !isActiveAccount(account)) {
            return false;
        }
        StarsController controller;
        try {
            controller = StarsController.getInstance(account);
        } catch (Throwable t) {
            return false;
        }
        if (controller == null || controller.gifts == null || controller.gifts.isEmpty()) {
            return false;
        }

        Set<Long> existing = new HashSet<>(controller.gifts.size() + GIFTS.size());
        for (TL_stars.StarGift gift : controller.gifts) {
            if (gift != null) {
                existing.add(gift.id);
            }
        }

        int insertPosition = Math.min(INSERT_POSITION, controller.gifts.size());
        ArrayList<TL_stars.StarGift> inserted = new ArrayList<>(GIFTS.size());
        for (Entry entry : GIFTS) {
            if (existing.contains(entry.id)) {
                continue;
            }
            TL_stars.TL_starGift gift = new TL_stars.TL_starGift();
            gift.id = entry.id;
            gift.gift_id = entry.id;
            gift.stars = entry.price;
            gift.sticker = LOCAL_DOCUMENTS[entry.stickerNumber];
            gift.attributes = new ArrayList<>();
            int position = Math.min(insertPosition + inserted.size(), controller.gifts.size());
            controller.gifts.add(position, gift);
            inserted.add(gift);
        }
        if (inserted.isEmpty()) {
            return false;
        }

        mirrorInto(controller.sortedGifts, inserted, insertPosition);
        mirrorInto(controller.birthdaySortedGifts, inserted, insertPosition);
        return true;
    }

    private static void mirrorInto(ArrayList<TL_stars.StarGift> target,
                                   ArrayList<TL_stars.StarGift> inserted,
                                   int insertPosition) {
        if (target == null) {
            return;
        }
        Set<Long> existing = new HashSet<>(target.size());
        for (TL_stars.StarGift gift : target) {
            if (gift != null) {
                existing.add(gift.id);
            }
        }
        int offset = 0;
        for (TL_stars.StarGift gift : inserted) {
            if (existing.add(gift.id)) {
                target.add(Math.min(insertPosition + offset, target.size()), gift);
                offset++;
            }
        }
    }

    private static void stripList(ArrayList<TL_stars.StarGift> list) {
        if (list == null) {
            return;
        }
        for (int i = list.size() - 1; i >= 0; i--) {
            TL_stars.StarGift gift = list.get(i);
            if (gift != null && GIFT_IDS.contains(gift.id)) {
                list.remove(i);
            }
        }
    }

    private static Set<Long> buildGiftIds() {
        Set<Long> ids = new HashSet<>(GIFTS.size());
        for (Entry gift : GIFTS) {
            ids.add(gift.id);
        }
        return Collections.unmodifiableSet(ids);
    }

    private static TLRPC.Document[] buildLocalDocuments() {
        TLRPC.Document[] documents = new TLRPC.Document[STICKERS.length];
        for (int i = 0; i < STICKERS.length; i++) {
            documents[i] = buildLocalDocument(STICKER_DOC_BASE_ID + i, STICKERS[i]);
        }
        return documents;
    }

    private static TLRPC.Document buildLocalDocument(long id, StickerSpec spec) {
        TLRPC.TL_document document = new TLRPC.TL_document();
        document.id = id;
        document.access_hash = 0;
        document.dc_id = 4;
        document.date = 0;
        document.mime_type = "application/x-tgsticker";
        document.size = spec.size;
        document.file_reference = new byte[0];
        document.thumbs = new ArrayList<>();
        document.video_thumbs = new ArrayList<>();

        TLRPC.TL_documentAttributeFilename filename = new TLRPC.TL_documentAttributeFilename();
        filename.file_name = spec.name + ".tgs";
        document.attributes.add(filename);
        document.attributes.add(new TLRPC.TL_documentAttributeAnimated());

        TLRPC.TL_documentAttributeSticker sticker = new TLRPC.TL_documentAttributeSticker();
        sticker.alt = "🎁";
        sticker.stickerset = new TLRPC.TL_inputStickerSetEmpty();
        document.attributes.add(sticker);
        return document;
    }

    private static final class StickerSpec {
        final String name;
        final int size;

        StickerSpec(String name, int size) {
            this.name = name;
            this.size = size;
        }
    }

    private static final class PreparationResult {
        final boolean success;
        final boolean changed;

        PreparationResult(boolean success, boolean changed) {
            this.success = success;
            this.changed = changed;
        }
    }
}
