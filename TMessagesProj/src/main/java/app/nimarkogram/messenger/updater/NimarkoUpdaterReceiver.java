package app.nimarkogram.messenger.updater;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class NimarkoUpdaterReceiver extends BroadcastReceiver {

    public static final String ACTION_CANCEL = "app.nimarkogram.messenger.UPDATE_CANCEL";
    public static final String ACTION_PAUSE = "app.nimarkogram.messenger.UPDATE_PAUSE";
    public static final String ACTION_RESUME = "app.nimarkogram.messenger.UPDATE_RESUME";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        final String action = intent.getAction();
        try {
            if (ACTION_CANCEL.equals(action)) {
                NimarkoUpdater.cancelDownload(context, 0);
            } else if (ACTION_PAUSE.equals(action)) {
                NimarkoUpdater.pauseDownload();
            } else if (ACTION_RESUME.equals(action)) {
                NimarkoUpdater.resumeDownload(context.getApplicationContext());
            }
        } catch (Throwable ignore) {}
    }
}
