/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.documents;

import android.content.Context;
import android.os.Build;
import android.os.Process;

public final class DocumentProcess {
    private DocumentProcess() {
    }

    public static boolean isIsolated(Context context) {
        if (Build.VERSION.SDK_INT >= 28) {
            return Process.isIsolated();
        }
        if (context == null) {
            return false;
        }
        try {
            return Process.myUid() != context.getApplicationInfo().uid;
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
