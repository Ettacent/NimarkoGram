package org.telegram.ui;

import android.content.Context;
import android.database.ContentObserver;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Process;
import android.provider.Settings;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.gms.cast.Cast;
import com.google.android.gms.cast.MediaError;
import com.google.android.gms.cast.MediaSeekOptions;
import com.google.android.gms.cast.MediaStatus;
import com.google.android.gms.cast.framework.CastContext;
import com.google.android.gms.cast.framework.CastSession;
import com.google.android.gms.cast.framework.Session;
import com.google.android.gms.cast.framework.SessionManagerListener;
import com.google.android.gms.cast.framework.media.RemoteMediaClient;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.BaseFragment;

import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

public class CastSync {

    public static final int TYPE_PHOTOVIEWER = 0;
    public static final int TYPE_MUSIC = 1;

    public static int type;
    public static AtomicInteger pending;

    public interface CastContextCallback {
        void onResult(@Nullable CastContext context);
    }

    private static final Object contextLock = new Object();
    private static final DispatchQueue contextQueue = new DispatchQueue(
            "castContextQueue", true, Process.THREAD_PRIORITY_BACKGROUND);
    private static final Executor contextExecutor = command -> contextQueue.postRunnable(command);
    private static final ArrayList<CastContextCallback> contextCallbacks = new ArrayList<>();
    private static volatile CastContext sharedContext;
    private static boolean contextLoading;

    public static Context getContext() {
        Context context = LaunchActivity.instance;
        if (context == null) context = ApplicationLoader.applicationContext;
        return context;
    }

    public static void preload() {
        requestContext(null);
    }

    public static void withContext(@NonNull CastContextCallback callback) {
        requestContext(callback);
    }

    private static void requestContext(@Nullable CastContextCallback callback) {
        CastContext ready = sharedContext;
        if (ready != null) {
            if (callback != null) {
                CastContext result = ready;
                AndroidUtilities.runOnUIThread(() -> callback.onResult(result));
            }
            return;
        }

        Context context = getContext();
        if (context == null) {
            if (callback != null) {
                AndroidUtilities.runOnUIThread(() -> callback.onResult(null));
            }
            return;
        }

        boolean startLoading = false;
        synchronized (contextLock) {
            ready = sharedContext;
            if (ready == null) {
                if (callback != null) {
                    contextCallbacks.add(callback);
                }
                if (!contextLoading) {
                    contextLoading = true;
                    startLoading = true;
                }
            }
        }
        if (ready != null) {
            if (callback != null) {
                CastContext result = ready;
                AndroidUtilities.runOnUIThread(() -> callback.onResult(result));
            }
            return;
        }
        if (!startLoading) return;

        Context appContext = context.getApplicationContext();
        if (appContext == null) appContext = context;
        try {
            CastContext.getSharedInstance(appContext, contextExecutor)
                    .addOnCompleteListener(contextExecutor, task -> {
                        CastContext result = null;
                        Throwable error = null;
                        if (task.isSuccessful()) {
                            result = task.getResult();
                        } else {
                            error = task.getException();
                        }
                        CastContext finalResult = result;
                        Throwable finalError = error;
                        AndroidUtilities.runOnUIThread(() -> completeContextLoad(finalResult, finalError));
                    });
        } catch (Throwable error) {
            AndroidUtilities.runOnUIThread(() -> completeContextLoad(null, error));
        }
    }

    private static void completeContextLoad(@Nullable CastContext context, @Nullable Throwable error) {
        ArrayList<CastContextCallback> callbacks;
        synchronized (contextLock) {
            if (context != null) {
                sharedContext = context;
            }
            contextLoading = false;
            callbacks = new ArrayList<>(contextCallbacks);
            contextCallbacks.clear();
        }
        if (error != null) {
            FileLog.e(error);
        }
        for (int i = 0; i < callbacks.size(); i++) {
            try {
                callbacks.get(i).onResult(context);
            } catch (Throwable callbackError) {
                FileLog.e(callbackError);
            }
        }
    }

    private static volatile boolean listened;
    private static boolean listenerRequested;
    public static void check(int type) {
        CastSync.type = type;
        synchronized (contextLock) {
            if (listened || listenerRequested) return;
            listenerRequested = true;
        }
        withContext(context -> {
            synchronized (contextLock) {
                listenerRequested = false;
            }
            attachSessionListener(context);
        });
    }

    private static void attachSessionListener(@Nullable CastContext castContext) {
        if (castContext == null || listened) return;
        try {
            castContext.getSessionManager().addSessionManagerListener(new SessionManagerListener<CastSession>() {
                @Override
                public void onSessionEnded(@NonNull CastSession session, int i) {
                    doSyncVolume(false);
                    syncInterface();
                }

                @Override
                public void onSessionEnding(@NonNull CastSession session) {
                    doSyncVolume(false);
                    syncInterface();
                }

                @Override
                public void onSessionResumeFailed(@NonNull CastSession session, int i) {

                }

                @Override
                public void onSessionResumed(@NonNull CastSession session, boolean b) {

                }

                @Override
                public void onSessionResuming(@NonNull CastSession session, @NonNull String s) {

                }

                @Override
                public void onSessionStartFailed(@NonNull CastSession session, int i) {

                }

                @Override
                public void onSessionStarted(@NonNull CastSession session, @NonNull String s) {
                    if (session == null) return;
                    final RemoteMediaClient client = session.getRemoteMediaClient();
                    if (client == null) return;
                    if (pending != null) {
                        pending.set(0);
                    }
                    client.registerCallback(new RemoteMediaClient.Callback() {
                        @Override
                        public void onStatusUpdated() {
                            FileLog.d("onStatusUpdated");
                            syncInterface();
                        }

                        @Override
                        public void onMediaError(@NonNull MediaError mediaError) {
                            FileLog.e("Chromecast Media Error: " + mediaError);
                        }
                    });
                    client.queueSetRepeatMode(MediaStatus.REPEAT_MODE_REPEAT_SINGLE, null);
                    long currentPosition = -1;
                    if (type == TYPE_PHOTOVIEWER) {
                        currentPosition = PhotoViewer.getInstance().getCurrentPosition();
                    } else if (type == TYPE_MUSIC) {
                        currentPosition = MediaController.getInstance().getCurrentPosition();
                    }
                    if (currentPosition >= 0) {
                        seekTo(currentPosition);
                    }
                    doSyncVolume(true);
                }

                @Override
                public void onSessionStarting(@NonNull CastSession session) {

                }

                @Override
                public void onSessionSuspended(@NonNull CastSession session, int i) {

                }
            }, CastSession.class);
            listened = true;
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static void stop() {
        CastContext castContext = sharedContext;
        if (castContext == null) return;
        try {
            castContext.getSessionManager().endCurrentSession(true);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public static boolean isActive() {
        CastContext castContext = sharedContext;
        if (castContext == null) return false;
        try {
            final CastSession castSession = castContext.getSessionManager().getCurrentCastSession();
            return castSession != null && (castSession.isConnecting() || castSession.isConnected());
        } catch (Exception e) {
            FileLog.e(e);
        }
        return false;
    }

    public static RemoteMediaClient getClient() {
        CastContext castContext = sharedContext;
        if (castContext == null) return null;
        try {
            final CastSession castSession = castContext.getSessionManager().getCurrentCastSession();
            if (castSession != null && castSession.isConnected()) {
                return castSession.getRemoteMediaClient();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return null;
    }

    public static long getPosition() {
        final RemoteMediaClient client = getClient();
        if (client == null) return -1;
        return client.getApproximateStreamPosition();
    }

    public static void seekTo(long position) {
        final RemoteMediaClient client = getClient();
        if (client == null) return;
        if (pending == null) {
            pending = new AtomicInteger(0);
        }
        pending.incrementAndGet();
        client.seek(
            new MediaSeekOptions.Builder()
                .setPosition(position)
                .build()
        ).addStatusListener(s -> pending.decrementAndGet());
    }

    public static void syncPosition(long position) {
        if (position < 0) return;
        final long currentPosition = getPosition();
        if (currentPosition == -1 || Math.abs(currentPosition - position) > 1_500) {
            seekTo(position);
        }
    }

    public static void setVolume(float volume) {
        final RemoteMediaClient client = getClient();
        if (client == null) return;
        if (pending == null) {
            pending = new AtomicInteger(0);
        }
        pending.incrementAndGet();
        client.setStreamVolume(volume).addStatusListener(s -> pending.decrementAndGet());
    }

    public static float getVolume() {
        final RemoteMediaClient client = getClient();
        if (client == null) return 0.5f;
        final MediaStatus status = client.getMediaStatus();
        if (status == null) return 0.5f;
        return (float) status.getStreamVolume();
    }

    public static boolean isPlaying() {
        final RemoteMediaClient client = getClient();
        if (client == null) return false;
        if (type == TYPE_PHOTOVIEWER) {
            return !client.isPaused();
        } else {
            return client.isPlaying();
        }
    }

    public static void setPlaying(boolean play) {
        final RemoteMediaClient client = getClient();
        if (client == null) return;
        if (play != client.isPlaying()) {
            if (pending == null) {
                pending = new AtomicInteger(0);
            }
            pending.incrementAndGet();
            if (play) {
                client.play().addStatusListener(s -> pending.decrementAndGet());
            } else {
                client.pause().addStatusListener(s -> pending.decrementAndGet());
            }
        }
    }
    public static void setSpeed(float speed) {
        final RemoteMediaClient client = getClient();
        if (client == null) return;
        if (pending == null) {
            pending = new AtomicInteger(0);
        }
        pending.incrementAndGet();
        client.setPlaybackRate(speed).addStatusListener(s -> pending.decrementAndGet());
    }

    public static boolean isUpdatePending() {
        return pending != null && pending.get() > 0;
    }

    public static float getSpeed() {
        final RemoteMediaClient client = getClient();
        if (client == null) return 1.0f;
        final MediaStatus status = client.getMediaStatus();
        if (status == null) return 1.0f;
        return (float) status.getPlaybackRate();
    }

    private static int savedVolume;
    private static ContentObserver syncingVolume;
    public static void doSyncVolume(boolean sync) {
        if ((syncingVolume != null) != sync) {
            if (sync) {
                final Context context = getContext();
                if (context == null) return;
                AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                if (audioManager == null) return;

                savedVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);

                context.getContentResolver().registerContentObserver(Settings.System.CONTENT_URI, true, syncingVolume = new ContentObserver(new Handler()) {
                    @Override
                    public void onChange(boolean selfChange) {
                        setVolume(getDeviceVolume());
                    }
                });
                setVolume(getDeviceVolume());

                final int stream = AudioManager.STREAM_MUSIC;
                audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI);
            } else if (syncingVolume != null) {
                final Context context = getContext();
                if (context == null) return;
                context.getContentResolver().unregisterContentObserver(syncingVolume);
                syncingVolume = null;
                AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
                if (audioManager == null) return;
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0);

                syncInterface();
            }
        }
    }

    public static void syncInterface() {
        if (type == TYPE_PHOTOVIEWER) {
            PhotoViewer.getInstance().syncCastedPlayer();
        } else if (type == TYPE_MUSIC) {
            MediaController.getInstance().syncCastedPlayer();
        }
    }

    public static float getDeviceVolume() {
        final Context context = getContext();
        if (context == null) return 0.0f;
        final AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) return 0.0f;

        int currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        int maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
        int minVolume = 0;
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            minVolume = audioManager.getStreamMinVolume(AudioManager.STREAM_MUSIC);
        }

        return Utilities.clamp01((float) (currentVolume - minVolume) / (maxVolume - minVolume));
    }

}
