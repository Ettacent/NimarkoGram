package com.exteragram.messenger.badges.source;

import com.exteragram.messenger.api.dto.BadgeDTO;
import com.exteragram.messenger.api.model.ProfileStatus;

public final class BadgeInfo extends com.exteragram.messenger.badges.BadgeEntry {
    public BadgeInfo(BadgeDTO badge, ProfileStatus status, boolean canChangeBadge) {
        super(badge, status, canChangeBadge);
    }
}
