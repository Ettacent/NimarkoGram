/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components;

import java.util.Objects;

final class ProfileGalleryBinding {
    final int account, mediaIndex, role;
    final long dialogId, photoId;
    final boolean activeVideo;
    final String request;

    ProfileGalleryBinding(int account, long dialogId, long photoId, int mediaIndex,
                          int role, boolean activeVideo, String request) {
        this.account = account;
        this.dialogId = dialogId;
        this.photoId = photoId;
        this.mediaIndex = mediaIndex;
        this.role = role;
        this.activeVideo = activeVideo;
        this.request = request;
    }

    static int realPosition(int position, int count, int extra) {
        if (position < extra) return count - extra * 2 - (extra - position - 1) - 1;
        if (position >= count - extra) return position - (count - extra);
        return position - extra;
    }

    @Override
    public boolean equals(Object object) {
        if (this == object) return true;
        if (!(object instanceof ProfileGalleryBinding)) return false;
        ProfileGalleryBinding other = (ProfileGalleryBinding) object;
        return account == other.account && dialogId == other.dialogId && photoId == other.photoId
                && mediaIndex == other.mediaIndex && role == other.role && activeVideo == other.activeVideo
                && Objects.equals(request, other.request);
    }

    @Override
    public int hashCode() {
        return Objects.hash(account, dialogId, photoId, mediaIndex, role, activeVideo, request);
    }
}
