package app.nimarkogram.messenger.badges;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ApiBadgeSource {

    public final ConcurrentHashMap<Long, BadgeEntry> cache =
            new ConcurrentHashMap<>();

    private final AtomicBoolean notifyPending = new AtomicBoolean(false);
    private final Runnable notifyRunnable = () -> {
        notifyPending.set(false);
        try {
            
            int mask = org.telegram.messenger.MessagesController.UPDATE_MASK_EMOJI_STATUS;
            for (int a = 0; a < org.telegram.messenger.UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (org.telegram.messenger.UserConfig.isValidAccount(a)) {
                    org.telegram.messenger.NotificationCenter.getInstance(a)
                            .postNotificationName(org.telegram.messenger.NotificationCenter.updateInterfaces, mask);
                }
            }
        } catch (Throwable ignored) {}
    };

    void scheduleNotify() {
        if (!notifyPending.compareAndSet(false, true)) return;
        
        org.telegram.messenger.AndroidUtilities.runOnUIThread(notifyRunnable, 750);
    }

    public void forceNotify() {
        scheduleNotify();
    }

    ApiBadgeSource() {}
}
