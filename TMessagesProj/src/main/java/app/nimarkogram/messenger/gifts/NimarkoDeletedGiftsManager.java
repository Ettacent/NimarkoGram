package app.nimarkogram.messenger.gifts;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.ui.Stars.StarsController;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import app.nimarkogram.messenger.NimarkoConfig;

public final class NimarkoDeletedGiftsManager {

    private NimarkoDeletedGiftsManager() {}

    private static final int INSERT_POSITION = 11;

    private static final String ASSET_DIR = "deleted_gifts";
    private static final String[] STICKER_FILES = {
            "gift_deleted_placeholder",  
            "gift_newyear_bear",         
            "gift_christmas_tree",       
            "gift_valentine_bear",       
            "gift_march8_bear",          
            "gift_valentine_heart",      
            "gift_leprechaun_bear",      
            "gift_aprilfools_bear",      
            "gift_easter_bear",          
            "gift_builder_bear"          
    };
     
    private static final long STICKER_DOC_BASE_ID = 7_700_000_000_000_000_001L;

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
            new Entry(6026193266406327981L, 50L, 9)
    ));

    private static final TLRPC.Document[] localDocs = new TLRPC.Document[STICKER_FILES.length];
    private static volatile boolean localReady = false;

    public static List<Entry> getGifts() {
        return GIFTS;
    }

    public static void maybeInject(int account) {
        if (!NimarkoConfig.deletedGiftsInject) return;
        
        Utilities.globalQueue.postRunnable(() -> {
            ensureLocalStickers();
            AndroidUtilities.runOnUIThread(() -> tryInject(account, 0));
        });
    }

    public static void removeInjected(int account) {
        try {
            StarsController controller = StarsController.getInstance(account);
            if (controller == null) return;
            Set<Long> injectedIds = injectedIdSet();
            stripList(controller.gifts, injectedIds);
            stripList(controller.sortedGifts, injectedIds);
            stripList(controller.birthdaySortedGifts, injectedIds);
        } catch (Throwable ignored) {
        }
    }

    private static Set<Long> injectedIdSet() {
        Set<Long> ids = new HashSet<>(GIFTS.size());
        for (Entry g : GIFTS) ids.add(g.id);
        return ids;
    }

    private static void stripList(ArrayList<TL_stars.StarGift> list, Set<Long> injectedIds) {
        if (list == null) return;
        for (int i = list.size() - 1; i >= 0; i--) {
            TL_stars.StarGift g = list.get(i);
            if (g != null && injectedIds.contains(g.id)) {
                list.remove(i);
            }
        }
    }

    private static synchronized void ensureLocalStickers() {
        try {
            File stageDir = new File(ApplicationLoader.applicationContext.getFilesDir(), ASSET_DIR);
            //noinspection ResultOfMethodCallIgnored
            stageDir.mkdirs();
            File docDir = FileLoader.getDirectory(FileLoader.MEDIA_DIR_DOCUMENT);
            File cacheDir = FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE);
            for (int i = 0; i < STICKER_FILES.length; i++) {
                File stage = new File(stageDir, STICKER_FILES[i] + ".tgs");
                if (!stage.exists() || stage.length() == 0) {
                    extractAsset(ASSET_DIR + "/" + STICKER_FILES[i] + ".tgs", stage);
                }
                if (!stage.exists() || stage.length() == 0) continue;
                if (localDocs[i] == null) {
                    localDocs[i] = buildLocalDocument(STICKER_DOC_BASE_ID + i, stage);
                }
                
                if (localDocs[i] != null) {
                    String name = FileLoader.getAttachFileName(localDocs[i]);
                    seedInto(docDir, name, stage);
                    seedInto(cacheDir, name, stage);
                }
            }
        } catch (Throwable ignored) {
        }
        localReady = true;
    }

    private static void seedInto(File dir, String name, File src) {
        if (dir == null) return;
        File seeded = new File(dir, name);
        if (!seeded.exists() || seeded.length() != src.length()) {
            try {
                AndroidUtilities.copyFile(src, seeded);
            } catch (Throwable ignored) {
            }
        }
    }

    private static void extractAsset(String assetPath, File out) {
        try (InputStream in = ApplicationLoader.applicationContext.getAssets().open(assetPath);
             FileOutputStream os = new FileOutputStream(out)) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        } catch (Throwable ignored) {
        }
    }

    private static TLRPC.Document buildLocalDocument(long id, File file) {
        TLRPC.TL_document doc = new TLRPC.TL_document();
        doc.id = id;
        doc.access_hash = 0;
        doc.dc_id = 4;
        doc.date = 0;
        doc.mime_type = "application/x-tgsticker";
        doc.size = file.length();
        doc.file_reference = new byte[0];
        doc.thumbs = new ArrayList<>();
        doc.video_thumbs = new ArrayList<>();

        TLRPC.TL_documentAttributeFilename name = new TLRPC.TL_documentAttributeFilename();
        name.file_name = file.getName();
        doc.attributes.add(name);

        doc.attributes.add(new TLRPC.TL_documentAttributeAnimated());

        TLRPC.TL_documentAttributeSticker sticker = new TLRPC.TL_documentAttributeSticker();
        sticker.alt = "🎁"; 
        sticker.stickerset = new TLRPC.TL_inputStickerSetEmpty();
        doc.attributes.add(sticker);

        return doc;
    }

    private static TLRPC.Document previewSticker(int stickerNumber) {
        if (!localReady) return null;
        int idx = stickerNumber;
        if (idx < 0 || idx >= localDocs.length) idx = 0;
        return localDocs[idx];
    }

    private static void tryInject(int account, int retry) {
        if (!NimarkoConfig.deletedGiftsInject) return;
        StarsController controller;
        try {
            controller = StarsController.getInstance(account);
        } catch (Throwable t) {
            return;
        }
        if (controller == null) return;
        ArrayList<TL_stars.StarGift> giftsList = controller.gifts;
        if (giftsList == null || giftsList.isEmpty()) {
            if (retry < 15) {
                AndroidUtilities.runOnUIThread(() -> tryInject(account, retry + 1), 500);
            }
            return;
        }
        if (!localReady && retry < 15) {
            AndroidUtilities.runOnUIThread(() -> tryInject(account, retry + 1), 500);
            return;
        }

        Set<Long> existing = new HashSet<>();
        for (TL_stars.StarGift g : giftsList) {
            if (g != null) existing.add(g.id);
        }
        List<Entry> toInject = new ArrayList<>();
        for (Entry e : GIFTS) {
            if (!existing.contains(e.id)) toInject.add(e);
        }
        if (toInject.isEmpty()) return;

        TL_stars.StarGift donor = giftsList.get(0);
        if (donor == null) return;

        int insertPos = Math.min(INSERT_POSITION, giftsList.size());
        for (int i = 0; i < toInject.size(); i++) {
            Entry e = toInject.get(i);
            TL_stars.TL_starGift clone = cloneDonor(donor);
            if (clone == null) continue;
            clone.id = e.id;
            clone.gift_id = e.id;
            clone.stars = e.price;
            clone.sticker = previewSticker(e.stickerNumber);
            clone.attributes = new ArrayList<>();
            
            clone.sold_out = false;
            clone.limited = false;
            clone.birthday = false;
            clone.availability_remains = 0;
            clone.availability_total = 0;
            int pos = Math.min(insertPos + i, giftsList.size());
            try {
                giftsList.add(pos, clone);
            } catch (Throwable ignored) {
            }
        }

        try {
            mirrorInto(controller.sortedGifts, giftsList, insertPos, toInject.size());
        } catch (Throwable ignored) {
        }
        try {
            mirrorInto(controller.birthdaySortedGifts, giftsList, insertPos, toInject.size());
        } catch (Throwable ignored) {
        }

        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.starGiftsLoaded);
    }

    private static void mirrorInto(ArrayList<TL_stars.StarGift> target,
                                   ArrayList<TL_stars.StarGift> source,
                                   int insertPos, int count) {
        if (target == null || target == source) return;
        for (int i = 0; i < count; i++) {
            int srcIdx = insertPos + i;
            if (srcIdx >= source.size()) break;
            int dstIdx = Math.min(insertPos + i, target.size());
            target.add(dstIdx, source.get(srcIdx));
        }
    }

    private static TL_stars.TL_starGift cloneDonor(TL_stars.StarGift donor) {
        try {
            TL_stars.TL_starGift clone = new TL_stars.TL_starGift();
            Class<?> c = donor.getClass();
            while (c != null && c != Object.class) {
                for (Field f : c.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    if (Modifier.isFinal(f.getModifiers())) continue;
                    try {
                        f.setAccessible(true);
                        f.set(clone, f.get(donor));
                    } catch (Throwable ignored) {}
                }
                c = c.getSuperclass();
            }
            return clone;
        } catch (Throwable ignored) {
            return null;
        }
    }
}
