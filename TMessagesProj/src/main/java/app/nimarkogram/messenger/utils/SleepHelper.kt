/*
 * Copyright github.com/arsLan4k1390, 2022-2026.
 * Licensed under GNU GPL v2 or later. See LICENSE.
 */

package app.nimarkogram.messenger.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import org.telegram.messenger.MediaController
import app.nimarkogram.messenger.NimarkoConfig

class SleepHelper : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (!MediaController.getInstance().isMessagePaused) {
            MediaController.getInstance().pauseMessage(MediaController.getInstance().playingMessageObject)
        }
        NimarkoConfig.setSleepTimer(false)
    }

}
