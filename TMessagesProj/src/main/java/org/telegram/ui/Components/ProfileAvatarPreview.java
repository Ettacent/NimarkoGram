/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Looper;

import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.ImageReceiver;
import org.telegram.tgnet.TLRPC;

public final class ProfileAvatarPreview {
    private static final int MAX_EDGE = 512;
    private final Shared shared;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF destination = new RectF();
    private boolean released;

    private static final class Shared {
        final int account;
        final long dialogId, photoId;
        Bitmap bitmap;
        int references = 1;
        Shared(Bitmap bitmap, int account, long dialogId, long photoId) {
            this.bitmap = bitmap;
            this.account = account;
            this.dialogId = dialogId;
            this.photoId = photoId;
        }
    }

    private ProfileAvatarPreview(Shared shared) { this.shared = shared; }

    public static ProfileAvatarPreview capture(ImageReceiver source, int account, long dialogId, long photoId) {
        if (Looper.myLooper() != Looper.getMainLooper() || !matchesPhoto(source, account, dialogId, photoId)
                || !source.getVisible() || !(source.getAlpha() > 0f) || !finite(source.getAlpha())
                || source.isForcePreview() || !hasReadyContent(source) || !captureCompositionAllowed(source)) return null;
        Bitmap copy = null;
        try {
            String identity = captureIdentity(source);
            if (identity == null) return null;
            float x = source.getImageX(), y = source.getImageY();
            float width = source.getImageWidth(), height = source.getImageHeight();
            if (!finite(x) || !finite(y) || !finite(width) || !finite(height) || !(width > 0f) || !(height > 0f)) return null;
            float scale = Math.min(1f, MAX_EDGE / Math.max(width, height));
            int outWidth = Math.max(1, Math.round(width * scale));
            int outHeight = Math.max(1, Math.round(height * scale));
            copy = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(copy);
            canvas.scale(outWidth / width, outHeight / height);
            canvas.translate(-x, -y);
            final float outerAlpha = source.getAlpha();
            boolean drew;
            source.setAlpha(1f);
            try {
                drew = source.draw(canvas);
            } finally {
                source.setAlpha(outerAlpha);
            }
            if (!drew || !source.getVisible() || !(source.getAlpha() > 0f)
                    || x != source.getImageX() || y != source.getImageY()
                    || width != source.getImageWidth() || height != source.getImageHeight()
                    || !matchesPhoto(source, account, dialogId, photoId)
                    || !identity.equals(captureIdentity(source)) || !hasReadyContent(source)
                    || !captureCompositionAllowed(source)) {
                copy.recycle();
                return null;
            }
            return new ProfileAvatarPreview(new Shared(copy, account, dialogId, photoId));
        } catch (RuntimeException | OutOfMemoryError ignored) {
            if (copy != null && !copy.isRecycled()) copy.recycle();
            return null;
        }
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private static boolean captureCompositionAllowed(ImageReceiver source) {
        float alpha = source.getCurrentAlpha();
        if (!(alpha > 0f) || !finite(alpha)) return false;
        if (source.isCrossfadingWithOldImage() && !source.hasFullyVisibleImage()
                && !(alpha >= 1f && renderable(source.getImageDrawable()))) return false;
        Drawable fallback = source.getStaticThumb();
        return alpha >= 1f || !(fallback instanceof BitmapDrawable)
                || fallback instanceof VectorAvatarThumbDrawable;
    }

    private static String captureIdentity(ImageReceiver source) {
        Object parent = source.getParentObject();
        ImageLocation media = source.getMediaLocation(), image = source.getImageLocation(), thumb = source.getThumbLocation();
        ImageLocation stripped = media != null ? media : image != null ? image : thumb;
        if (!slotKeyMatches(source, media, source.getMediaKey(), source.getMediaFilter(), parent, null, true)
                || !slotKeyMatches(source, image, source.getImageKey(), source.getImageFilter(), parent, null, true)
                || !slotKeyMatches(source, thumb, source.getThumbKey(), source.getThumbFilter(), parent, stripped, false)) return null;
        boolean selected = renderable(source.getMediaDrawable()) && media != null
                || renderable(source.getImageDrawable()) && image != null
                || renderable(source.getThumb()) && thumb != null;
        if (!selected) return null;
        return source.getCurrentAccount() + ":" + source.getMediaKey() + "|" + source.getImageKey() + "|" + source.getThumbKey();
    }

    private static boolean slotKeyMatches(ImageReceiver source, ImageLocation location, String stored,
                                           String filter, Object parent, ImageLocation stripped, boolean prefixed) {
        if (location == null) return stored == null;
        String key = location.getKey(parent, stripped, false);
        if (key == null || stored == null) return false;
        if (filter != null) key += "@" + filter;
        if (prefixed && source.getUniqKeyPrefix() != null) key = source.getUniqKeyPrefix() + key;
        return key.equals(stored);
    }

    public static boolean matchesPhoto(ImageReceiver source, int account, long dialogId, long photoId) {
        if (source == null || account < 0 || dialogId == 0 || photoId <= 0 || source.getCurrentAccount() != account) return false;
        Object parent = source.getParentObject();
        boolean peer;
        if (parent instanceof TLRPC.User) {
            TLRPC.User user = (TLRPC.User) parent;
            if (dialogId <= 0 || user.id != dialogId || user.photo == null || user.photo.photo_id != photoId) return false;
            peer = true;
        } else if (parent instanceof TLRPC.Chat) {
            TLRPC.Chat chat = (TLRPC.Chat) parent;
            if (dialogId >= 0 || chat.id != -dialogId || chat.photo == null || chat.photo.photo_id != photoId) return false;
            peer = true;
        } else {
            peer = matchesGalleryParent(parent, dialogId);
        }
        boolean seen = false;
        for (int index = 0; index < 3; index++) {
            ImageLocation location = index == 0 ? source.getMediaLocation()
                    : index == 1 ? source.getImageLocation() : source.getThumbLocation();
            if (location == null) continue;
            long id = location.photoId != 0 ? location.photoId : location.photo != null ? location.photo.id : 0;
            if (id != 0 && id != photoId) return false;
            if (id == photoId) seen = true;
            if (location.photoPeer != null) {
                long locationPeer = location.photoPeer.user_id != 0 ? location.photoPeer.user_id
                        : location.photoPeer.channel_id != 0 ? -location.photoPeer.channel_id : -location.photoPeer.chat_id;
                if (locationPeer != 0 && locationPeer != dialogId) return false;
                if (locationPeer == dialogId) peer = true;
            }
        }
        return seen && peer;
    }

    private static boolean matchesGalleryParent(Object parent, long dialogId) {
        if (!(parent instanceof String)) return false;
        String text = (String) parent;
        if (!text.startsWith("avatar_") || text.length() <= 7 || text.length() > 27) return false;
        int index = 7;
        boolean negative = text.charAt(index) == '-';
        if (negative && ++index == text.length()) return false;
        long value = 0, limit = negative ? Long.MIN_VALUE : -Long.MAX_VALUE;
        for (; index < text.length(); index++) {
            int digit = text.charAt(index) - '0';
            if (digit < 0 || digit > 9 || value < limit / 10) return false;
            value *= 10;
            if (value < limit + digit) return false;
            value -= digit;
        }
        return (negative ? value : -value) == dialogId;
    }

    public static boolean hasReadyContent(ImageReceiver source) {
        return source != null && (renderable(source.getMediaDrawable()) || renderable(source.getImageDrawable())
                || renderable(source.getThumb()) || renderable(source.getStaticThumb()));
    }

    private static boolean renderable(Drawable drawable) {
        if (drawable instanceof AnimatedFileDrawable) {
            AnimatedFileDrawable animation = (AnimatedFileDrawable) drawable;
            return !animation.isRecycled() && animation.hasRenderingBitmap();
        } else if (drawable instanceof RLottieDrawable) {
            RLottieDrawable animation = (RLottieDrawable) drawable;
            return !animation.isRecycled() && animation.hasRenderingBitmap();
        } else if (drawable instanceof BitmapDrawable) {
            Bitmap bitmap = ((BitmapDrawable) drawable).getBitmap();
            return bitmap != null && !bitmap.isRecycled();
        }
        return drawable instanceof VectorAvatarThumbDrawable;
    }

    public boolean matches(int account, long dialogId, long photoId) {
        synchronized (shared) {
            return !released && shared.bitmap != null && shared.account == account
                    && shared.dialogId == dialogId && shared.photoId == photoId;
        }
    }

    public ProfileAvatarPreview retain() {
        synchronized (shared) {
            if (released || shared.bitmap == null) return null;
            shared.references++;
            return new ProfileAvatarPreview(shared);
        }
    }

    public void release() {
        synchronized (shared) {
            if (released) return;
            released = true;
            if (--shared.references == 0) {
                shared.bitmap.recycle();
                shared.bitmap = null;
            }
        }
    }

    public void draw(Canvas canvas, float x, float y, float width, float height, float alpha) {
        synchronized (shared) {
            if (released || shared.bitmap == null || !(width > 0f) || !(height > 0f) || !(alpha > 0f)
                    || Float.isInfinite(width) || Float.isInfinite(height) || Float.isNaN(x) || Float.isNaN(y)
                    || Float.isInfinite(x) || Float.isInfinite(y)) return;
            Bitmap bitmap = shared.bitmap;
            float scale = Math.max(width / bitmap.getWidth(), height / bitmap.getHeight());
            float w = bitmap.getWidth() * scale, h = bitmap.getHeight() * scale;
            destination.set(x + (width - w) / 2f, y + (height - h) / 2f,
                    x + (width + w) / 2f, y + (height + h) / 2f);
            paint.setAlpha(Math.round(255f * Math.min(1f, alpha)));
            int save = canvas.save();
            canvas.clipRect(x, y, x + width, y + height);
            canvas.drawBitmap(bitmap, null, destination, paint);
            canvas.restoreToCount(save);
        }
    }
}
