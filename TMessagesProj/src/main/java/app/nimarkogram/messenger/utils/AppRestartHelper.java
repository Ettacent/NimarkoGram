package app.nimarkogram.messenger.utils;

import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Process;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;
import java.util.Arrays;

public final class AppRestartHelper {

    private static final String KEY_RESTART_INTENTS = "nimarko_restart_intents";
    private static final String KEY_MAIN_PROCESS_PID = "nimarko_main_process_pid";

    private AppRestartHelper() {}

    public static void triggerRebirth(Context context) {
        if (context == null) {
            context = ApplicationLoader.applicationContext;
        }
        if (context == null) {
            return;
        }
        Intent next = new Intent(context, LaunchActivity.class);
        triggerRebirth(context, next);
    }

    private static void triggerRebirth(Context context, Intent... nextIntents) {
        nextIntents[0].addFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK);
        Intent intent = new Intent(context, Trampoline.class);
        intent.addFlags(FLAG_ACTIVITY_NEW_TASK);
        intent.putParcelableArrayListExtra(KEY_RESTART_INTENTS, new ArrayList<>(Arrays.asList(nextIntents)));
        intent.putExtra(KEY_MAIN_PROCESS_PID, Process.myPid());
        context.startActivity(intent);
    }

    public static void killApp() {
        System.exit(0);
    }

    public static void restartApp(Context context) {
        triggerRebirth(context);
    }

    public static final class Trampoline extends Activity {
        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            int pid = getIntent().getIntExtra(KEY_MAIN_PROCESS_PID, -1);
            if (pid > 0) {
                Process.killProcess(pid);
            }
            ArrayList<Intent> intents = getIntent().getParcelableArrayListExtra(KEY_RESTART_INTENTS);
            if (intents != null && !intents.isEmpty()) {
                startActivities(intents.toArray(new Intent[0]));
            }
            finish();
            Runtime.getRuntime().exit(0);
        }
    }
}
