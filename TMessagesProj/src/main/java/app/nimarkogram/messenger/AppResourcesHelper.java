/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger;

import androidx.annotation.DrawableRes;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;

public final class AppResourcesHelper {

    private AppResourcesHelper() {}

    public static String getLeftActionButtonText(boolean noForwards) {
        if (noForwards) return LocaleController.getString(R.string.Reply);
        switch (AppConfig.actionsBarLeftButton) {
            case AppConfig.ACTIONS_LEFT_SAVE_MESSAGE:
                return LocaleController.getString(R.string.NM_MI_SaveToSaved);
            case AppConfig.ACTIONS_LEFT_DIRECT_SHARE:
                return LocaleController.getString(R.string.DirectShare);
            case AppConfig.ACTIONS_LEFT_FORWARD_WO_AUTHORSHIP:
                return LocaleController.getString(R.string.NM_MI_ForwardWoAuthorship);
            case AppConfig.ACTIONS_LEFT_REPLY:
            default:
                return LocaleController.getString(R.string.Reply);
        }
    }

    @DrawableRes
    public static int getLeftActionButtonDrawable(boolean noForwards) {
        if (noForwards) return R.drawable.input_reply;
        switch (AppConfig.actionsBarLeftButton) {
            case AppConfig.ACTIONS_LEFT_SAVE_MESSAGE:
                return R.drawable.msg_saved;
            case AppConfig.ACTIONS_LEFT_DIRECT_SHARE:
                return R.drawable.msg_share;
            case AppConfig.ACTIONS_LEFT_FORWARD_WO_AUTHORSHIP:
                return R.drawable.msg_forward;
            case AppConfig.ACTIONS_LEFT_REPLY:
            default:
                return R.drawable.input_reply;
        }
    }

    @DrawableRes
    public static int getReplyIconDrawable() {
        switch (AppConfig.messageSlideAction) {
            case AppConfig.MESSAGE_SLIDE_ACTION_SAVE:
                return R.drawable.msg_saved_filled_solar;
            case AppConfig.MESSAGE_SLIDE_ACTION_DIRECT_SHARE:
                return R.drawable.msg_share_filled;
            case AppConfig.MESSAGE_SLIDE_ACTION_TRANSLATE:
                return R.drawable.msg_translate_filled_solar;
            case AppConfig.MESSAGE_SLIDE_ACTION_REPLY:
            default:
                return R.drawable.filled_button_reply;
        }
    }

    @DrawableRes
    public static int getProperNotificationIcon() {
        return R.drawable.notification;
    }

    @DrawableRes
    public static int getResidentNotificationIcon() {
        return R.drawable.notification;
    }
}
