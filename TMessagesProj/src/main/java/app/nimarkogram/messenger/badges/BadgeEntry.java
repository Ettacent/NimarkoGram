package app.nimarkogram.messenger.badges;

import app.nimarkogram.messenger.api.dto.BadgeDTO;
import app.nimarkogram.messenger.api.model.ProfileStatus;

public final class BadgeEntry {

    private BadgeDTO badge;
    private final ProfileStatus status;
    private final boolean canChangeBadge;

    public BadgeEntry(BadgeDTO badge, ProfileStatus status, boolean canChangeBadge) {
        this.badge = badge;
        this.status = status != null ? status : ProfileStatus.DEFAULT;
        this.canChangeBadge = canChangeBadge;
    }

    public BadgeDTO getBadge() { return badge; }
    public ProfileStatus getStatus() { return status; }
    public boolean getCanChangeBadge() { return canChangeBadge; }

    public void setBadge(BadgeDTO badge) { this.badge = badge; }

    public BadgeEntry copy(BadgeDTO badge, ProfileStatus status, boolean canChangeBadge) {
        return new BadgeEntry(badge, status, canChangeBadge);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof BadgeEntry)) return false;
        BadgeEntry o = (BadgeEntry) other;
        if (canChangeBadge != o.canChangeBadge) return false;
        if (status != o.status) return false;
        return badge == null ? o.badge == null : badge.equals(o.badge);
    }

    @Override
    public int hashCode() {
        int h = badge == null ? 0 : badge.hashCode();
        h = h * 31 + status.hashCode();
        h = h * 31 + Boolean.hashCode(canChangeBadge);
        return h;
    }

    @Override
    public String toString() {
        return "BadgeInfo(badge=" + badge + ", status=" + status
                + ", canChangeBadge=" + canChangeBadge + ')';
    }
}
