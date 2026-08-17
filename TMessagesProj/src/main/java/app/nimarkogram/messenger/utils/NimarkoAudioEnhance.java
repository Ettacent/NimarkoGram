/*
 * Copyright github.com/arsLan4k1390, 2022-2026.
 * Licensed under GNU GPL v2 or later. See LICENSE.
 */

package app.nimarkogram.messenger.utils;

import app.nimarkogram.messenger.NimarkoConfig;

public final class NimarkoAudioEnhance {

    private NimarkoAudioEnhance() {}

    public static int getAudioSource() {
        return NimarkoConfig.getMediaRecorderAudioSource();
    }

}
