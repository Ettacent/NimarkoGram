/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.badges;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.UserConfig;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ApiBadgeSource {

    public final ConcurrentHashMap<Long, BadgeEntry> cache =
            new ConcurrentHashMap<>();

    private final AtomicBoolean notifyPending = new AtomicBoolean(false);
    private final Runnable notifyRunnable = () -> {
        notifyPending.set(false);
        try {
            
            int mask = MessagesController.UPDATE_MASK_EMOJI_STATUS;
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (UserConfig.isValidAccount(a)) {
                    NotificationCenter.getInstance(a)
                            .postNotificationName(NotificationCenter.updateInterfaces, mask);
                }
            }
        } catch (Throwable ignored) {}
    };

    void scheduleNotify() {
        if (!notifyPending.compareAndSet(false, true)) return;
        
        AndroidUtilities.runOnUIThread(notifyRunnable, 750);
    }

    public void forceNotify() {
        scheduleNotify();
    }

    ApiBadgeSource() {}
}
