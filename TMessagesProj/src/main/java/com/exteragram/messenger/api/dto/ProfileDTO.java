package com.exteragram.messenger.api.dto;

import com.exteragram.messenger.api.model.ProfileStatus;
import com.google.gson.annotations.SerializedName;

public final class ProfileDTO {

    @SerializedName("id")
    private final long id;

    @SerializedName("badge")
    private final BadgeDTO badge;

    @SerializedName("status")
    private final ProfileStatus status;

    @SerializedName("canChangeBadge")
    private final Boolean canChangeBadge;

    public ProfileDTO(long id, BadgeDTO badge, ProfileStatus status, Boolean canChangeBadge) {
        this.id = id;
        this.badge = badge;
        this.status = status;
        this.canChangeBadge = canChangeBadge;
    }

    public long getId() {
        return id;
    }

    public BadgeDTO getBadge() {
        return badge;
    }

    public ProfileStatus getStatus() {
        return status != null ? status : ProfileStatus.DEFAULT;
    }

    public Boolean getCanChangeBadge() {
        return canChangeBadge;
    }

    public app.nimarkogram.messenger.api.dto.ProfileDTO toReal() {
        return new app.nimarkogram.messenger.api.dto.ProfileDTO(
                id,
                badge != null ? badge.toReal() : null,
                status != null ? status.toReal() : null,
                canChangeBadge);
    }

    public static ProfileDTO fromReal(app.nimarkogram.messenger.api.dto.ProfileDTO real) {
        if (real == null) return null;
        return new ProfileDTO(
                real.getId(),
                BadgeDTO.fromReal(real.getBadge()),
                ProfileStatus.fromReal(real.getStatus()),
                real.getCanChangeBadge());
    }
}
