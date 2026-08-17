/*
 * This is the source code of NimarkoGram.
 * It is licensed under GNU GPL v. 2 or later.
 *
 * Verbatim port (GPL-2.0) of CherryGram's image-to-clipboard pipeline:
 *   uz.unnarsx.cherrygram.chats.helpers.ChatsHelper.addMessageToClipboard /
 *   addFileToClipboard / getPathToMessage
 *   uz.unnarsx.cherrygram.helpers.network.StickersManager.addMessageToClipboardAsSticker
 *
 * Wraps a MessageObject's on-disk attachment into a ClipData backed by NG's
 * existing FileProvider authority (${applicationId}.provider) so that other
 * apps (and Telegram's own paste handlers) can consume the image. The sticker
 * variant re-encodes the bitmap as a WebP file before clipping.
 */
package app.nimarkogram.messenger.utils;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.text.TextUtils;

import androidx.core.content.FileProvider;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.UserConfig;

import java.io.File;
import java.io.FileOutputStream;

public final class ImageClipboardUtils {

    private ImageClipboardUtils() {}

    public static String getPathToMessage(MessageObject messageObject) {
        if (messageObject == null || messageObject.messageOwner == null) return null;
        int account = messageObject.currentAccount >= 0
                ? messageObject.currentAccount : UserConfig.selectedAccount;

        String path = messageObject.messageOwner.attachPath;
        if (!TextUtils.isEmpty(path)) {
            File temp = new File(path);
            if (!temp.exists()) {
                path = null;
            }
        }
        if (TextUtils.isEmpty(path)) {
            try {
                path = FileLoader.getInstance(account)
                    .getPathToMessage(messageObject.messageOwner).toString();
                File temp = new File(path);
                if (!temp.exists()) {
                    path = null;
                }
            } catch (Throwable t) {
                path = null;
            }
        }
        if (TextUtils.isEmpty(path)) {
            try {
                path = FileLoader.getInstance(account)
                    .getPathToAttach(messageObject.getDocument(), true).toString();
                File temp = new File(path);
                if (!temp.exists()) {
                    return null;
                }
            } catch (Throwable t) {
                return null;
            }
        }
        return path;
    }

    public static void addFileToClipboard(File file, Runnable callback) {
        try {
            Context context = ApplicationLoader.applicationContext;
            ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
            String authority = ApplicationLoader.getApplicationId() + ".provider";
            Uri uri = FileProvider.getUriForFile(context, authority, file);
            ClipData clip = ClipData.newUri(context.getContentResolver(), "label", uri);
            if (clipboard != null) {
                clipboard.setPrimaryClip(clip);
            }
            if (callback != null) callback.run();
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static void addMessageToClipboard(MessageObject selectedObject, Runnable callback) {
        String path = getPathToMessage(selectedObject);
        if (TextUtils.isEmpty(path)) return;
        addFileToClipboard(new File(path), callback);
    }

    public static void addMessageToClipboardAsSticker(MessageObject selectedObject, Runnable callback) {
        String path = getPathToMessage(selectedObject);
        if (TextUtils.isEmpty(path)) return;

        new Thread(() -> {
            try {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(path, bounds);
                int sample = 1;
                while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) {
                    sample <<= 1;
                }
                BitmapFactory.Options decode = new BitmapFactory.Options();
                decode.inSampleSize = sample;
                decode.inPreferredConfig = Bitmap.Config.ARGB_8888;
                Bitmap image = BitmapFactory.decodeFile(path, decode);
                if (image == null) return;

                Bitmap sticker = image;
                try {
                    int maxSide = Math.max(image.getWidth(), image.getHeight());
                    if (maxSide > 512) {
                        float scale = 512f / maxSide;
                        sticker = Bitmap.createScaledBitmap(image,
                                Math.max(1, Math.round(image.getWidth() * scale)),
                                Math.max(1, Math.round(image.getHeight() * scale)), true);
                    }
                    File dir = new File(ApplicationLoader.getFilesDirFixed(), "cache/clipboard");
                    if (!dir.exists() && !dir.mkdirs()) return;
                    File webp = File.createTempFile("clipboard_sticker_", ".webp", dir);

                    FileOutputStream stream = new FileOutputStream(webp);
                    try {
                        if (!sticker.compress(Bitmap.CompressFormat.WEBP, 90, stream)) {
                            try { webp.delete(); } catch (Throwable ignored) {}
                            return;
                        }
                    } finally {
                        try { stream.close(); } catch (Throwable ignored) {}
                    }

                    AndroidUtilities.runOnUIThread(() -> addFileToClipboard(webp, callback));
                } finally {
                    if (sticker != image) {
                        try { sticker.recycle(); } catch (Throwable ignored) {}
                    }
                    try { image.recycle(); } catch (Throwable ignored) {}
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        }).start();
    }
}
