/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.messenger;

import static org.telegram.messenger.CacheByChatsController.KEEP_MEDIA_TYPE_STORIES;

import android.util.SparseArray;



import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class AutoDeleteMediaTask {

    public static Set<String> usingFilePaths = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private static final ConcurrentHashMap<String, Set<Object>> fileOwners = new ConcurrentHashMap<>();
    private static final Object[] pathLocks = new Object[64];

    static {
        for (int i = 0; i < pathLocks.length; i++) pathLocks[i] = new Object();
    }

    private static Object pathLock(String path) {
        return pathLocks[path.hashCode() & (pathLocks.length - 1)];
    }

    private static boolean isInUse(String path) {
        return usingFilePaths.contains(path) || fileOwners.containsKey(path);
    }

    public static boolean deleteIfUnused(File file) {
        final String path = file.getAbsolutePath();
        synchronized (pathLock(path)) {
            return !isInUse(path) && file.delete();
        }
    }

    public static void protectPersistedVoiceDraftFiles() {
        for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
            if (UserConfig.getInstance(account).isClientActivated()) {
                MediaDataController.getInstance(account).loadDraftVoiceMessages();
            }
        }
    }
    public static void run() {
        int time = (int) (System.currentTimeMillis() / 1000);
        if (Math.abs(time - SharedConfig.lastKeepMediaCheckTime) < 24 * 60 * 60) {
            return;
        }
        SharedConfig.lastKeepMediaCheckTime = time;
        File cacheDir = FileLoader.checkDirectory(FileLoader.MEDIA_DIR_CACHE);

        Utilities.cacheClearQueue.postRunnable(() -> {
            protectPersistedVoiceDraftFiles();
            long startTime = System.currentTimeMillis();
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("checkKeepMedia start task");
            }
            boolean hasExceptions = false;
            ArrayList<CacheByChatsController> cacheByChatsControllers = new ArrayList<>();
            for (int account = 0; account < UserConfig.MAX_ACCOUNT_COUNT; account++) {
                if (UserConfig.getInstance(account).isClientActivated()) {
                    CacheByChatsController cacheByChatsController = UserConfig.getInstance(account).getMessagesController().getCacheByChatsController();
                    cacheByChatsControllers.add(cacheByChatsController);
                    if (cacheByChatsController.getKeepMediaExceptionsByDialogs().size() > 0) {
                        hasExceptions = true;
                    }
                }
            }

            int[] keepMediaByTypes = new int[4];
            boolean allKeepMediaTypesForever = true;
            long keepMediaMinSeconds = Long.MAX_VALUE;
            for (int i = 0; i < 4; i++) {
                keepMediaByTypes[i] = SharedConfig.getPreferences().getInt("keep_media_type_" + i, CacheByChatsController.getDefault(i));
                if (keepMediaByTypes[i] != CacheByChatsController.KEEP_MEDIA_FOREVER) {
                    allKeepMediaTypesForever = false;
                }
                long days = CacheByChatsController.getDaysInSeconds(keepMediaByTypes[i]);
                if (days < keepMediaMinSeconds) {
                    keepMediaMinSeconds = days;
                }
            }
            if (hasExceptions) {
                allKeepMediaTypesForever = false;
            }
            int autoDeletedFiles = 0;
            long autoDeletedFilesSize = 0;

            int deletedFilesBySize = 0;
            long deletedFilesBySizeSize = 0;
            int skippedFiles = 0;

            //if (!allKeepMediaTypesForever) {
                //long currentTime = time - 60 * 60 * 24 * days;
                final SparseArray<File> paths = ImageLoader.getInstance().createMediaPaths();
                for (int a = 0; a < paths.size(); a++) {
                    if (allKeepMediaTypesForever && (paths.keyAt(a) == FileLoader.MEDIA_DIR_AUDIO || paths.keyAt(a) == FileLoader.MEDIA_DIR_DOCUMENT)) {
                        continue;
                    }
                    boolean isCacheDir = false;
                    if (paths.keyAt(a) == FileLoader.MEDIA_DIR_CACHE) {
                        isCacheDir = true;
                    }
                    File dir = paths.valueAt(a);
                    try {
                        File[] files = dir.listFiles();
                        ArrayList<CacheByChatsController.KeepMediaFile> keepMediaFiles = new ArrayList<>();
                        if (files != null) {
                            for (int i = 0; i < files.length; i++) {
                                if (files[i].isDirectory() || isInUse(files[i].getAbsolutePath())) {
                                    continue;
                                }
                                keepMediaFiles.add(new CacheByChatsController.KeepMediaFile(files[i]));
                            }
                        }
                        for (int i = 0; i < cacheByChatsControllers.size(); i++) {
                            cacheByChatsControllers.get(i).lookupFiles(keepMediaFiles);
                        }
                        for (int i = 0; i < keepMediaFiles.size(); i++) {
                            CacheByChatsController.KeepMediaFile file = keepMediaFiles.get(i);
                            long timeLocal;
                            if (file.isStory) {
                                long seconds = CacheByChatsController.getDaysInSeconds(keepMediaByTypes[KEEP_MEDIA_TYPE_STORIES]);
                                timeLocal = time - seconds;
                            } else {
                                if (file.keepMedia == CacheByChatsController.KEEP_MEDIA_FOREVER) {
                                    continue;
                                }
                                long seconds;
                                if (file.keepMedia >= 0) {
                                    seconds = CacheByChatsController.getDaysInSeconds(file.keepMedia);
                                } else if (file.dialogType >= 0) {
                                    seconds = CacheByChatsController.getDaysInSeconds(keepMediaByTypes[file.dialogType]);
                                } else if (isCacheDir) {
                                    continue;
                                } else {
                                    seconds = keepMediaMinSeconds;
                                }
                                if (seconds == Long.MAX_VALUE) {
                                    continue;
                                }

                                timeLocal = time - seconds;
                            }
                            long lastUsageTime = Utilities.getLastUsageFileTime(file.file.getAbsolutePath());
                            boolean needDelete = lastUsageTime > 316000000 && lastUsageTime < timeLocal;
                            if (needDelete) {
                                try {
                                    final long size = BuildVars.LOGS_ENABLED ? file.file.length() : 0;
                                    if (!deleteIfUnused(file.file)) continue;
                                    if (BuildVars.LOGS_ENABLED) {
                                        autoDeletedFiles++;
                                        autoDeletedFilesSize += size;
                                    }
                                    if (BuildVars.DEBUG_PRIVATE_VERSION) {
                                        FileLog.d("delete file " + file.file.getPath() + " last_usage_time=" + lastUsageTime + " time_local=" + timeLocal + " story=" + file.isStory);
                                    }
                                } catch (Exception exception) {
                                    FileLog.e(exception);
                                }
                            }
                        }
                    } catch (Throwable e) {
                        FileLog.e(e);
                    }
                }
            //}

            int maxCacheGb = SharedConfig.getPreferences().getInt("cache_limit", Integer.MAX_VALUE);
            if (maxCacheGb != Integer.MAX_VALUE) {
                long maxCacheSize;
                if (maxCacheGb == 1) {
                    maxCacheSize = 1024L * 1024L * 300L;
                } else {
                    maxCacheSize = maxCacheGb * 1024L * 1024L * 1000L;
                }
                long totalSize = 0;
                for (int a = 0; a < paths.size(); a++) {
                    totalSize += Utilities.getDirSize(paths.valueAt(a).getAbsolutePath(), 0, true);
                }
                if (totalSize > maxCacheSize) {
                    ArrayList<FileInfoInternal> allFiles = new ArrayList<>();
                    for (int a = 0; a < paths.size(); a++) {
                        File dir = paths.valueAt(a);
                        fillFilesRecursive(dir, allFiles);
                    }
                    for (int i = 0; i < cacheByChatsControllers.size(); i++) {
                        cacheByChatsControllers.get(i).lookupFiles(allFiles);
                    }
                    Collections.sort(allFiles, (o1, o2) -> {
                        if (o2.lastUsageDate > o1.lastUsageDate) {
                            return -1;
                        } else if (o2.lastUsageDate < o1.lastUsageDate) {
                            return 1;
                        }
                        return 0;
                    });

                    for (int i = 0; i < allFiles.size(); i++) {
                        if (allFiles.get(i).keepMedia == CacheByChatsController.KEEP_MEDIA_FOREVER) {
                            continue;
                        }
                        if (allFiles.get(i).lastUsageDate <= 0) {
                            skippedFiles++;
                            continue;
                        }
                        try {
                            final File file = allFiles.get(i).file;
                            final long size = file.length();
                            if (!deleteIfUnused(file)) {
                                skippedFiles++;
                                continue;
                            }
                        totalSize -= size;

                            deletedFilesBySize++;
                            deletedFilesBySizeSize += size;
                        } catch (Exception e) {

                        }

                        if (totalSize <= maxCacheSize) {
                            break;
                        }
                    }
                }
            }

            File stickersPath = new File(cacheDir, "acache");
            if (stickersPath.exists()) {
                long currentTime = time - 60 * 60 * 24;
                try {
                    Utilities.clearDir(stickersPath.getAbsolutePath(), 0, currentTime, false);
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
            MessagesController.getGlobalMainSettings().edit()
                    .putInt("lastKeepMediaCheckTime", SharedConfig.lastKeepMediaCheckTime)
                    .apply();

            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("checkKeepMedia task end time " + (System.currentTimeMillis() - startTime) + " auto deleted info: files " + autoDeletedFiles + " size " + AndroidUtilities.formatFileSize(autoDeletedFilesSize) + "   deleted by size limit info: files " + deletedFilesBySize + " size " + AndroidUtilities.formatFileSize(deletedFilesBySizeSize) + " unknownTimeFiles " + skippedFiles);
            }
        });
    }

    private static void fillFilesRecursive(final File fromFolder, ArrayList<FileInfoInternal> fileInfoList) {
        if (fromFolder == null) {
            return;
        }
        File[] files = fromFolder.listFiles();
        if (files == null) {
            return;
        }
        for (final File fileEntry : files) {
            if (fileEntry.isDirectory()) {
                fillFilesRecursive(fileEntry, fileInfoList);
            } else {
                if (fileEntry.getName().equals(".nomedia")) {
                    continue;
                }
                if (isInUse(fileEntry.getAbsolutePath())) {
                    continue;
                }
                fileInfoList.add(new FileInfoInternal(fileEntry));
            }
        }
    }

    private static class FileInfoInternal extends CacheByChatsController.KeepMediaFile {
        final long lastUsageDate;

        private FileInfoInternal(File file) {
            super(file);
            this.lastUsageDate = Utilities.getLastUsageFileTime(file.getAbsolutePath());
        }
    }

    public static void lockFile(File file) {
        if (file == null) {
            return;
        }
        lockFile(file.getAbsolutePath());
    }

    public static void unlockFile(File file) {
        if (file == null) {
            return;
        }
        unlockFile(file.getAbsolutePath());
    }

    public static void lockFile(String file) {
        if (file == null) {
            return;
        }
        final String path = new File(file).getAbsolutePath();
        synchronized (pathLock(path)) {
            usingFilePaths.add(path);
        }
    }

    public static void unlockFile(String file) {
        if (file == null) {
            return;
        }
        final String path = new File(file).getAbsolutePath();
        synchronized (pathLock(path)) {
            usingFilePaths.remove(path);
        }
    }

    public static void lockFile(String file, Object owner) {
        if (file == null || owner == null) return;
        final String path = new File(file).getAbsolutePath();
        synchronized (pathLock(path)) {
            Set<Object> owners = fileOwners.get(path);
            if (owners == null) {
                owners = Collections.newSetFromMap(new IdentityHashMap<>());
                fileOwners.put(path, owners);
            }
            owners.add(owner);
        }
    }

    public static void unlockFile(String file, Object owner) {
        if (file == null || owner == null) return;
        final String path = new File(file).getAbsolutePath();
        synchronized (pathLock(path)) {
            Set<Object> owners = fileOwners.get(path);
            if (owners != null && owners.remove(owner) && owners.isEmpty()) {
                fileOwners.remove(path);
            }
        }
    }

}
