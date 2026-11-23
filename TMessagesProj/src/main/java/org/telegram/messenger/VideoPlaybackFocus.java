/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.messenger;

import android.content.Context;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

public final class VideoPlaybackFocus {
    private static final Set<VideoPlaybackFocus> active = Collections.newSetFromMap(new IdentityHashMap<>());
    public interface Client {
        boolean isCurrent();
        default boolean canPlayAudio() { return true; }
        void pauseForFocus();
        void resumeForFocus();
    }

    private final AudioManager manager;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Client client;
    private AudioManager.OnAudioFocusChangeListener listener;
    private Object request;
    private long generation;
    private boolean granted, suspended, wanted, requesting;
    private boolean autoStartCancelled;

    public boolean canAutoStart() { return !autoStartCancelled; }

    public static void onAudioBecomingNoisy() {
        for (VideoPlaybackFocus focus : new ArrayList<>(active)) {
            focus.wanted = false;
            focus.autoStartCancelled = true;
            Client owner = focus.client;
            if (owner != null && owner.isCurrent()) focus.notifyClient(owner, false);
        }
    }

    public VideoPlaybackFocus() {
        manager = (AudioManager) ApplicationLoader.applicationContext.getSystemService(Context.AUDIO_SERVICE);
    }

    public void attach(Client next) {
        if (client != null && client != next) throw new IllegalStateException("Focus owner must detach first");
        client = next;
        wanted = false;
    }

    public boolean beforePlay(Client owner, boolean audible) {
        if (owner != client || !owner.isCurrent()) return false;
        wanted = true;
        autoStartCancelled = false;
        if (!audible) {
            abandon();
            return true;
        }
        if (!owner.canPlayAudio()) {
            wanted = false;
            final long blocked = generation;
            notifyClient(owner, false);
            if (generation == blocked) abandon();
            return false;
        }
        if (granted) return true;
        if (suspended) {
            notifyClient(owner, false);
            return false;
        }
        final long lease = ++generation;
        final AudioManager.OnAudioFocusChangeListener next = change -> {
            if (Looper.myLooper() == Looper.getMainLooper()) onFocus(lease, change);
            else handler.post(() -> onFocus(lease, change));
        };
        listener = next;
        requesting = true;
        active.add(this);
        Object issued = null;
        int result;
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                issued = Api26.create(next, handler);
                request = issued;
                result = Api26.request(manager, issued);
            } else {
                result = manager.requestAudioFocus(next, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
            }
        } catch (Exception error) {
            FileLog.e(error);
            result = AudioManager.AUDIOFOCUS_REQUEST_FAILED;
        }
        if (generation != lease || listener != next) {
            abandonRequest(next, issued);
            return false;
        }
        requesting = false;
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED || !owner.isCurrent() || !wanted) {
            abandon();
            if (owner == client && owner.isCurrent()) notifyClient(owner, false);
            return false;
        }
        if (suspended) return false;
        granted = true;
        return true;
    }

    public void pause(Client owner) {
        if (owner == client) wanted = false;
    }

    public boolean hasLease(Client owner) {
        return owner == client && listener != null;
    }

    public void releasePaused(Client owner) {
        if (hasLease(owner) && !wanted && !requesting) abandon();
    }

    public void finish(Client owner) {
        if (owner != client) return;
        wanted = false;
        abandon();
    }

    public void detach(Client owner, boolean handoff) {
        if (owner != client) return;
        client = null;
        if (!handoff) release();
    }

    public void release() {
        client = null;
        wanted = false;
        abandon();
    }

    private void onFocus(long lease, int change) {
        if (generation != lease || listener == null) return;
        final Client owner = client;
        if (owner != null && !owner.isCurrent()) {
            wanted = false;
            abandon();
            return;
        }
        if (change == AudioManager.AUDIOFOCUS_GAIN) {
            boolean resume = suspended && wanted;
            granted = true;
            suspended = false;
            if (resume && owner != null) {
                if (owner.canPlayAudio()) notifyClient(owner, true);
                else {
                    wanted = false;
                    notifyClient(owner, false);
                    if (generation == lease) abandon();
                }
            }
        } else if (change == AudioManager.AUDIOFOCUS_LOSS
                || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK) {
            granted = false;
            suspended = true;
            final boolean permanent = change == AudioManager.AUDIOFOCUS_LOSS;
            if (permanent) {
                wanted = false;
                autoStartCancelled = true;
            }
            try {
                if (owner != null) notifyClient(owner, false);
            } finally {
                if (permanent && generation == lease) abandon();
            }
        }
    }

    private void notifyClient(Client owner, boolean resume) {
        try {
            if (resume) owner.resumeForFocus();
            else owner.pauseForFocus();
        } catch (Exception error) {
            FileLog.e(error);
            if (client == owner) {
                wanted = false;
                abandon();
            }
        }
    }

    private void abandon() {
        AudioManager.OnAudioFocusChangeListener oldListener = listener;
        Object oldRequest = request;
        listener = null;
        active.remove(this);
        request = null;
        ++generation;
        granted = suspended = requesting = false;
        abandonRequest(oldListener, oldRequest);
    }

    private void abandonRequest(AudioManager.OnAudioFocusChangeListener oldListener, Object oldRequest) {
        if (oldListener == null) return;
        try {
            if (Build.VERSION.SDK_INT >= 26 && oldRequest != null) Api26.abandon(manager, oldRequest);
            else manager.abandonAudioFocus(oldListener);
        } catch (Exception error) {
            FileLog.e(error);
        }
    }

    private static final class Api26 {
        static Object create(AudioManager.OnAudioFocusChangeListener listener, Handler handler) {
            return new android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE).build())
                .setWillPauseWhenDucked(true)
                .setOnAudioFocusChangeListener(listener, handler).build();
        }
        static int request(AudioManager manager, Object request) {
            return manager.requestAudioFocus((android.media.AudioFocusRequest) request);
        }
        static void abandon(AudioManager manager, Object request) {
            manager.abandonAudioFocusRequest((android.media.AudioFocusRequest) request);
        }
    }
}
