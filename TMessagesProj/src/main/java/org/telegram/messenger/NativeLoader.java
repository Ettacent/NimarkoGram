/* Modifications Copyright (C) 2026 Ettacent */

/*
 * This is the source code of Telegram for Android v. 1.3.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Build;

import android.os.Process;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import java.util.zip.CRC32;
public class NativeLoader {

    private final static int LIB_VERSION = 49;
    private final static String LIB_NAME = "tmessages." + LIB_VERSION;
    private final static String LIB_SO_NAME = "lib" + LIB_NAME + ".so";
    private final static String LOCALE_LIB_SO_NAME = "lib" + LIB_NAME + "loc.so";

    private static volatile boolean nativeLoaded = false;
    private static volatile Error loadError;
    private static final ArrayList<String> loadFailures = new ArrayList<>();
    public static StringBuilder log = new StringBuilder();

    private static void recordLoadFailure(String stage, Throwable failure) {
        String detail = stage + ": " + failure;
        if (detail.length() > 512) {
            detail = detail.substring(0, 512);
        }
        if (loadFailures.size() == 8) {
            loadFailures.remove(0);
        }
        loadFailures.add(detail);
        StringBuilder message = new StringBuilder("Native library loading failed");
        for (String attempt : loadFailures) {
            message.append('\n').append(attempt);
        }
        UnsatisfiedLinkError error = new UnsatisfiedLinkError(message.toString());
        error.initCause(failure);
        loadError = error;
        log.append(detail).append('\n');
    }

    private static void markLoaded() {
        nativeLoaded = true;
        loadError = null;
        loadFailures.clear();
    }
    private static File getNativeLibraryDir(Context context) {
        File f = null;
        if (context != null) {
            try {
                f = new File((String)ApplicationInfo.class.getField("nativeLibraryDir").get(context.getApplicationInfo()));
            } catch (Throwable th) {
                th.printStackTrace();
            }
        }
        if (f == null) {
            f = new File(context.getApplicationInfo().dataDir, "lib");
        }
        if (f.isDirectory()) {
            return f;
        }
        return null;
    }

    private static boolean matchesLibrary(File file, ZipEntry entry) throws IOException {
        if (!file.isFile() || entry.getSize() < 0 || entry.getCrc() < 0 || file.length() != entry.getSize()) {
            return false;
        }
        CRC32 crc = new CRC32();
        byte[] buffer = new byte[65536];
        try (InputStream input = new FileInputStream(file)) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                crc.update(buffer, 0, count);
            }
        }
        return crc.getValue() == entry.getCrc();
    }
    @SuppressLint({"UnsafeDynamicallyLoadedCode", "SetWorldReadable"})
    private static boolean loadFromZip(Context context, String folder) {
        ApplicationInfo info = context.getApplicationInfo();
        Set<String> sources = new LinkedHashSet<>();
        if (info.sourceDir != null) {
            sources.add(info.sourceDir);
        }
        if (info.splitSourceDirs != null) {
            for (String split : info.splitSourceDirs) {
                if (split != null) {
                    sources.add(split);
                }
            }
        }
        String libraryPath = "lib/" + folder + "/" + LIB_SO_NAME;
        boolean found = false;
        for (String source : sources) {
            try (ZipFile zipFile = new ZipFile(source)) {
                ZipEntry entry = zipFile.getEntry(libraryPath);
            if (entry == null) {
                    continue;
                }
                found = true;
                if (entry.getMethod() == ZipEntry.STORED) {
                    try {
                        System.load(source + "!/" + libraryPath);
                        markLoaded();
                        return true;
                    } catch (Error error) {
                        recordLoadFailure("APK load " + folder, error);
                    }
                }
                File files = context.getFilesDir();
                if (files == null) {
                    throw new IOException("Files directory is unavailable");
                }
                File destDir = new File(files, "lib");
                if (!destDir.isDirectory() && !destDir.mkdirs()) {
                    throw new IOException("Cannot create native cache directory");
                }
                File destLocalFile = new File(destDir, LOCALE_LIB_SO_NAME);
                boolean cached = false;
                try {
                    cached = matchesLibrary(destLocalFile, entry);
                } catch (IOException error) {
                    recordLoadFailure("Cache validation " + folder, error);
                }
                if (!cached) {
                    File temporary = File.createTempFile(LOCALE_LIB_SO_NAME, ".tmp", destDir);
                    try {
                        CRC32 crc = new CRC32();
                        long written = 0;
                        try (InputStream input = zipFile.getInputStream(entry);
                             OutputStream output = new FileOutputStream(temporary)) {
                            byte[] buffer = new byte[65536];
                            int count;
                            while ((count = input.read(buffer)) != -1) {
                                output.write(buffer, 0, count);
                                crc.update(buffer, 0, count);
                                written += count;
                            }
                        }
                        if (written != entry.getSize() || crc.getValue() != entry.getCrc()) {
                            throw new IOException("Incomplete native library extraction");
                        }
                        temporary.setReadable(true, false);
                        temporary.setExecutable(true, false);
                        temporary.setWritable(true);
                        if (!temporary.renameTo(destLocalFile)) {
                            throw new IOException("Cannot replace native cache file");
                        }
                    } finally {
                        temporary.delete();
                    }
                }

            try {
                System.load(destLocalFile.getAbsolutePath());
                    markLoaded();
                    return true;
                } catch (Error error) {
                    recordLoadFailure("Extracted load " + folder, error);
                    FileLog.e(error);
                }
            } catch (Exception error) {
                recordLoadFailure("APK extraction " + folder, error);
                FileLog.e(error);
            }
        }
        if (!found) {
            recordLoadFailure("APK lookup " + folder, new IOException("Missing " + libraryPath));
        }
        return false;
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    private static boolean loadFromNativeLibraryDir(Context context) {
        File nativeDir = getNativeLibraryDir(context);
        if (nativeDir == null) {
            return false;
        }
        File file = new File(nativeDir, LIB_SO_NAME);
        if (!file.isFile()) {
            return false;
        }
        try {
            System.load(file.getAbsolutePath());
            markLoaded();
            return true;
        } catch (Error error) {
            recordLoadFailure("Native directory load", error);
            FileLog.e(error);
            return false;
        }
    }

    private static ArrayList<String> getAbiFolders() {
        Set<String> folders = new LinkedHashSet<>();
        String preferred = normalizeAbiFolder(getAbiFolder());
        try {
            String[] abis = Process.is64Bit() ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS;
            if (abis != null) {
                for (String abi : abis) {
                    if (abi != null && preferred.equals(normalizeAbiFolder(abi))) {
                        folders.add(preferred);
                    }
                }
                for (String abi : abis) {
                    if (abi != null && !abi.isEmpty()) {
                        folders.add(normalizeAbiFolder(abi));
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        if (folders.isEmpty()) {
            folders.add(preferred);
        }
        return new ArrayList<>(folders);
    }

    private static String normalizeAbiFolder(String abi) {
        if ("arm64".equalsIgnoreCase(abi) || "aarch64".equalsIgnoreCase(abi)) {
            return "arm64-v8a";
        }
        if ("arm".equalsIgnoreCase(abi)) {
            return "armeabi-v7a";
        }
        return abi;
    }

    @SuppressLint("UnsafeDynamicallyLoadedCode")
    public static synchronized void initNativeLibs(Context context) {
        if (nativeLoaded) {
            return;
        }

        loadError = null;
        loadFailures.clear();
        log.setLength(0);
            try {
                System.loadLibrary(LIB_NAME);
            markLoaded();
                return;
        } catch (Error error) {
            recordLoadFailure("Default load", error);
            FileLog.e(error);
        }
        try {
            if (loadFromNativeLibraryDir(context)) {
                    return;
            }
            for (String folder : getAbiFolders()) {
                if (loadFromZip(context, folder)) {
                return;
            }
            }
        } catch (Throwable error) {
            recordLoadFailure("Loader fallback", error);
            FileLog.e(error);
        }

        try {
            System.loadLibrary(LIB_NAME);
            markLoaded();
        } catch (Error error) {
            recordLoadFailure("Final default load", error);
            FileLog.e(error);
        }
    }

    public static String getAbiFolder() {
        String folder;
        try {
            String str = Build.CPU_ABI;
            if (Build.CPU_ABI.equalsIgnoreCase("x86_64")) {
                folder = "x86_64";
            } else if (Build.CPU_ABI.equalsIgnoreCase("arm64-v8a")) {
                folder = "arm64-v8a";
            } else if (Build.CPU_ABI.equalsIgnoreCase("armeabi-v7a")) {
                folder = "armeabi-v7a";
            } else if (Build.CPU_ABI.equalsIgnoreCase("armeabi")) {
                folder = "armeabi";
            } else if (Build.CPU_ABI.equalsIgnoreCase("x86")) {
                folder = "x86";
            } else if (Build.CPU_ABI.equalsIgnoreCase("mips")) {
                folder = "mips";
            } else {
                folder = "armeabi";
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.e("Unsupported arch: " + Build.CPU_ABI);
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
            folder = "armeabi";
        }

        String javaArch = System.getProperty("os.arch");
        if (javaArch != null && javaArch.contains("686")) {
            folder = "x86";
        }
        return folder;
    }

    public static boolean loaded() {
        return nativeLoaded;
    }
    //public static native void crash();
    public static Error getLoadError() {
        return loadError;
    }
}
