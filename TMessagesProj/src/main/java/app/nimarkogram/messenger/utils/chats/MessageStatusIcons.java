package app.nimarkogram.messenger.utils.chats;

import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.util.SparseArray;

import androidx.core.content.ContextCompat;

import org.telegram.messenger.ApplicationLoader;

import java.util.Objects;


final class MessageStatusIcons {
    private static final SparseArray<Bitmap> masks = new SparseArray<>();
    private static int cachedDensity;

    private MessageStatusIcons() { }

    static synchronized Drawable create(int resourceId) {
        Resources resources = ApplicationLoader.applicationContext.getResources();
        int density = resources.getDisplayMetrics().densityDpi;
        if (cachedDensity != density) {

            masks.clear();
            cachedDensity = density;
        }
        Bitmap mask = masks.get(resourceId);
        if (mask == null) {
            Drawable vector = Objects.requireNonNull(ContextCompat.getDrawable(
                    ApplicationLoader.applicationContext, resourceId)).mutate();
            int width = Math.max(1, vector.getIntrinsicWidth());
            int height = Math.max(1, vector.getIntrinsicHeight());
            mask = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
            mask.setDensity(density);
            vector.setBounds(0, 0, width, height);
            vector.draw(new Canvas(mask));
            masks.put(resourceId, mask);
        }





        return new BitmapDrawable(resources, mask).mutate();
    }
}
