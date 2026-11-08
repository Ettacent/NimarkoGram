/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.badges;

import app.nimarkogram.messenger.api.dto.BadgeDTO;
import app.nimarkogram.messenger.api.model.ProfileStatus;

public final class BadgeEntry {

    private volatile BadgeDTO badge;
    private transient volatile BadgePluginOwners.Owner pluginOwner;
    private final ProfileStatus status;
    private final boolean canChangeBadge;

    public BadgeEntry(BadgeDTO badge, ProfileStatus status, boolean canChangeBadge) {
        this(badge, status, canChangeBadge, BadgePluginOwners.capture());
    }

    private BadgeEntry(BadgeDTO badge, ProfileStatus status, boolean canChangeBadge,
                       BadgePluginOwners.Owner owner) {
        this.badge = badge;
        this.pluginOwner = owner;
        this.status = status != null ? status : ProfileStatus.DEFAULT;
        this.canChangeBadge = canChangeBadge;
    }

    public BadgeDTO getBadge() { return badge; }
    public ProfileStatus getStatus() { return status; }
    public boolean getCanChangeBadge() { return canChangeBadge; }

    public synchronized void setBadge(BadgeDTO badge) {
        this.pluginOwner = null;
        this.badge = badge;
        this.pluginOwner = BadgePluginOwners.capture();
    }

    BadgePluginOwners.Owner getPluginOwner() { return pluginOwner; }
    synchronized BadgeEntry snapshot() {
        return new BadgeEntry(badge, status, canChangeBadge, pluginOwner);
    }
    synchronized void restorePluginOwner(String pluginId) {
        pluginOwner = pluginId == null || pluginId.isEmpty() || pluginId.length() > 256
                ? null : new BadgePluginOwners.Owner(pluginId, null);
    }

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
