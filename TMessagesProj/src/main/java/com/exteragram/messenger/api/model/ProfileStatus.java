package com.exteragram.messenger.api.model;

public enum ProfileStatus {
    DEFAULT,
    DEVELOPER,
    SUPPORTER;

    public app.nimarkogram.messenger.api.model.ProfileStatus toReal() {
        switch (this) {
            case DEVELOPER: return app.nimarkogram.messenger.api.model.ProfileStatus.DEVELOPER;
            case SUPPORTER: return app.nimarkogram.messenger.api.model.ProfileStatus.SUPPORTER;
            default:        return app.nimarkogram.messenger.api.model.ProfileStatus.DEFAULT;
        }
    }

    public static ProfileStatus fromReal(app.nimarkogram.messenger.api.model.ProfileStatus real) {
        if (real == null) return DEFAULT;
        switch (real) {
            case DEVELOPER: return DEVELOPER;
            case SUPPORTER: return SUPPORTER;
            default:        return DEFAULT;
        }
    }
}
