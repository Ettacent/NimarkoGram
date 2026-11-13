/* Modifications Copyright (C) 2026 Ettacent */
package app.nimarkogram.messenger.badges;

import android.content.Context;
import android.util.AtomicFile;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class BadgeCacheFile {
    static final int MAX_BYTES = 4 * 1024 * 1024;
    private final AtomicFile file;

    BadgeCacheFile(Context context) {
        File directory = context.getCacheDir();
        if (directory == null) throw new IllegalStateException("Badge cache directory unavailable");
        file = new AtomicFile(new File(directory, "nimarko_badges.json"));
    }

    String read() throws IOException {
        try (FileInputStream input = file.openRead();
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (count > MAX_BYTES - bytes.size()) throw new IOException("Badge cache too large");
                bytes.write(buffer, 0, count);
            }
            return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
        } catch (FileNotFoundException absent) {
            File base = file.getBaseFile();
            if (base.exists() || new File(base.getPath() + ".bak").exists()) throw absent;
            return null;
        }
    }

    void write(String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IOException("Badge cache too large");
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(bytes);
            file.finishWrite(output);
        } catch (IOException | RuntimeException failure) {
            file.failWrite(output);
            throw failure;
        }
        if (!json.equals(read())) throw new IOException("Badge cache write verification failed");
    }
}
