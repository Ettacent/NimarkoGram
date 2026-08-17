package app.nimarkogram.messenger.api.dto;

import app.nimarkogram.messenger.api.model.ProfileStatus;
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
}
