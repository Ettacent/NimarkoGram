/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.banners;

import org.telegram.messenger.MediaController;
import org.telegram.messenger.VideoPlaybackFocus;
import org.telegram.ui.Components.VideoPlayer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

final class BannerAudioFocus implements VideoPlayer.CustomAudioFocusDelegate {
    private final Set<VideoPlayer> players = Collections.newSetFromMap(new IdentityHashMap<>());
    private VideoPlaybackFocus focus;
    private boolean attempted;
    private boolean granted;
    private long generation;
    private final VideoPlaybackFocus.Client client = new VideoPlaybackFocus.Client() {
        public boolean isCurrent() { return !players.isEmpty(); }
        public boolean canPlayAudio() { return MediaController.getInstance().canStartVideoPlayback(); }
        public void pauseForFocus() {
            granted = false;
            applyGrant();
            if (focus != null && !focus.canAutoStart()) focus.finish(this);
        }
        public void resumeForFocus() {
            granted = true;
            applyGrant();
        }
    };

    @Override
    public void onPlaybackChanged(VideoPlayer player, boolean audible) {
        if (!audible) {
            players.remove(player);
            player.setCustomAudioFocusGranted(this, false);
            if (players.isEmpty()) {
                granted = attempted = false;
                ++generation;
                if (focus != null) focus.finish(client);
            }
            return;
        }
        players.add(player);
        if (!attempted) {
            final long request = ++generation;
            attempted = true;
            if (focus == null) {
                focus = new VideoPlaybackFocus();
                focus.attach(client);
            }
            boolean result = focus.beforePlay(client, true);
            if (generation == request) granted = result && !players.isEmpty();
        }
        applyGrant();
    }

    @Override
    public void onReleased(VideoPlayer player) {
        onPlaybackChanged(player, false);
    }

    private void applyGrant() {
        for (VideoPlayer player : new ArrayList<>(players)) {
            if (players.contains(player)) player.setCustomAudioFocusGranted(this, granted);
        }
    }
}
