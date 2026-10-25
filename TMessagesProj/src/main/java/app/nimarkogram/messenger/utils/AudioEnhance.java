
/* Modifications Copyright (C) 2026 Ettacent */

/*
 * Copyright github.com/arsLan4k1390, 2022-2026.
 * Licensed under GNU GPL v2 or later. See LICENSE.
 */

package app.nimarkogram.messenger.utils;

import app.nimarkogram.messenger.AppConfig;

public final class AudioEnhance {

    private AudioEnhance() {}

    public static int getAudioSource() {
        return AppConfig.getMediaRecorderAudioSource();
    }

}
