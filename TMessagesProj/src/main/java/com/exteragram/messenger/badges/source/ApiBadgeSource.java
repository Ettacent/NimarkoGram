package com.exteragram.messenger.badges.source;

import com.exteragram.messenger.api.dto.BadgeDTO;
import com.exteragram.messenger.badges.BadgeEntry;

import java.util.concurrent.ConcurrentHashMap;

public final class ApiBadgeSource extends com.exteragram.messenger.badges.ApiBadgeSource {
    public final ConcurrentHashMap<Long, BadgeEntry> cache;

    public ApiBadgeSource(app.nimarkogram.messenger.badges.ApiBadgeSource real) {
        super(real);
        this.cache = super.cache;
    }

    public BadgeDTO getBadge(long id, boolean includeCustom) {
        BadgeEntry entry = cache.get(id);
        return entry != null ? entry.getBadge() : null;
    }

    public boolean canChangeBadge(long id) {
        BadgeEntry entry = cache.get(id);
        return entry != null && entry.getCanChangeBadge();
    }

    public Object loadToCache(kotlin.coroutines.Continuation<?> continuation) {
        return kotlin.Unit.INSTANCE;
    }
}
