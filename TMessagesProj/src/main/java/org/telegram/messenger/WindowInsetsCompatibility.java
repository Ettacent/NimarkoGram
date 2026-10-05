/* Modifications Copyright (C) 2026 Ettacent */
package org.telegram.messenger;
import android.os.Build;
import androidx.core.view.WindowInsetsCompat;
public final class WindowInsetsCompatibility {
    private static final int SYSTEM_BARS = resolveSystemBars();
    private WindowInsetsCompatibility() {
    }
    public static int systemBars() {
        return SYSTEM_BARS;
    }
    private static int resolveSystemBars() {
        int types = WindowInsetsCompat.Type.statusBars()
                | WindowInsetsCompat.Type.navigationBars()
                | WindowInsetsCompat.Type.captionBar();
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                Class.forName("android.view.WindowInsets$Type")
                        .getMethod("systemOverlays");
                types |= WindowInsetsCompat.Type.systemOverlays();
            } catch (ReflectiveOperationException | LinkageError | SecurityException ignored) {
            }
        }
        return types;
    }
}
