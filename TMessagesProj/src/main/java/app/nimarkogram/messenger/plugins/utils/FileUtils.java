package app.nimarkogram.messenger.plugins.utils;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class FileUtils {
    public static final FileUtils INSTANCE = new FileUtils();

    private FileUtils() {}

    public static void deleteRecursive(File dir, boolean deleteSelf) {
        if (dir == null || !dir.exists()) {
            return;
        }
        if (deleteSelf) {
            deleteTree(dir);
            return;
        }
        File[] children = dir.listFiles();
        if (children != null) {
            for (File f : children) {
                deleteTree(f);
            }
        }
    }

    public static void deleteRecursive(File dir) {
        deleteRecursive(dir, true);
    }

    private static boolean deleteTree(File f) {
        if (f == null || !f.exists()) return true;
        if (f.isDirectory()) {
            File[] children = f.listFiles();
            if (children != null) {
                for (File c : children) deleteTree(c);
            }
        }
        return f.delete();
    }

    public static boolean moveRecursive(File source, File target) {
        if (source == null || target == null) return false;
        if (source.renameTo(target)) {
            return true;
        }
        if (!source.isDirectory()) {
            return false;
        }
        if (!target.exists() && !target.mkdirs()) return false;
        File[] children = source.listFiles();
        if (children == null) return false;
        for (File f : children) {
            if (!moveRecursive(f, new File(target, f.getName()))) {
                return false;
            }
        }
        return source.delete();
    }

    public static void unzip(InputStream inputStream, File targetDir) throws IOException {
        String canonicalPath = targetDir.getCanonicalPath();
        ZipInputStream zin = new ZipInputStream(new BufferedInputStream(inputStream));
        try {
            ZipEntry entry;
            while ((entry = zin.getNextEntry()) != null) {
                File file = new File(targetDir, entry.getName());
                String canonical = file.getCanonicalPath();
                if (!canonical.startsWith(canonicalPath + File.separator)
                        && !canonical.equals(canonicalPath)) {
                    throw new SecurityException("Zip Slip vulnerability detected: " + entry.getName());
                }
                if (entry.isDirectory()) {
                    file.mkdirs();
                } else {
                    File parent = file.getParentFile();
                    if (parent != null) parent.mkdirs();
                    FileOutputStream out = new FileOutputStream(file);
                    try {
                        copyStream(zin, out);
                    } finally {
                        try { out.close(); } catch (Throwable ignored) {}
                    }
                }
            }
        } finally {
            try { zin.close(); } catch (Throwable ignored) {}
        }
    }

    private static void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        out.flush();
    }
}
