/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.plugins;

import android.content.Context;

import com.chaquo.python.android.AndroidPlatform;
import com.chaquo.python.internal.Common;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

final class PythonStartupPlatform extends AndroidPlatform {
    PythonStartupPlatform(Context context) {
        super(context);
    }

    @Override
    public String getPath() {
        String path = super.getPath();
        try {
            JSONObject assets;
            try (InputStream input = getApplication().getAssets().open(
                    Common.ASSET_DIR + "/" + Common.ASSET_BUILD_JSON);
                    ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    output.write(buffer, 0, count);
                }
                assets = new JSONObject(output.toString("UTF-8")).getJSONObject("assets");
            }

            List<File> invalid = findInvalidAssets(assets);
            if (!invalid.isEmpty()) {
                for (File file : invalid) {
                    if (file.exists() && !file.delete()) {
                        throw new IOException("Cannot replace a damaged Python bootstrap asset");
                    }
                }
                path = super.getPath();
                if (!findInvalidAssets(assets).isEmpty()) {
                    throw new IOException("Python bootstrap asset integrity check failed");
                }
            }
            return path;
        } catch (IOException | JSONException | NoSuchAlgorithmException error) {
            throw new IllegalStateException("Python bootstrap assets could not be verified", error);
        }
    }

    private List<File> findInvalidAssets(JSONObject assets)
            throws IOException, JSONException, NoSuchAlgorithmException {
        File directory = new File(getApplication().getFilesDir(), Common.ASSET_DIR);
        String stdlib = Common.assetZip(Common.ASSET_STDLIB, Common.ABI_COMMON);
        String bootstrap = Common.assetZip(Common.ASSET_BOOTSTRAP);
        String nativePrefix = Common.ASSET_BOOTSTRAP_NATIVE + "/" + ABI + "/";
        List<File> invalid = new ArrayList<>();
        for (Iterator<String> names = assets.keys(); names.hasNext();) {
            String name = names.next();
            if (name.equals(stdlib) || name.equals(bootstrap) || name.startsWith(nativePrefix)) {
                File file = new File(directory, name);
                if (!file.isFile() || !matchesHash(file, assets.getString(name))) {
                    invalid.add(file);
                }
            }
        }
        return invalid;
    }

    private static boolean matchesHash(File file, String expected)
            throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-1");
        try (InputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[32768];
            int count;
            while ((count = input.read(buffer)) != -1) {
                digest.update(buffer, 0, count);
            }
        }
        StringBuilder actual = new StringBuilder(40);
        for (byte value : digest.digest()) {
            actual.append(Character.forDigit((value >>> 4) & 15, 16));
            actual.append(Character.forDigit(value & 15, 16));
        }
        return expected.contentEquals(actual);
    }
}
