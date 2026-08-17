package app.nimarkogram.messenger;

import androidx.annotation.DrawableRes;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;

public final class NimarkoResourcesHelper {

    private NimarkoResourcesHelper() {}

    public static String getLeftActionButtonText(boolean noForwards) {
        if (noForwards) return LocaleController.getString(R.string.Reply);
        switch (NimarkoConfig.actionsBarLeftButton) {
            case NimarkoConfig.ACTIONS_LEFT_SAVE_MESSAGE:
                return LocaleController.getString(R.string.NM_MI_SaveToSaved);
            case NimarkoConfig.ACTIONS_LEFT_DIRECT_SHARE:
                return LocaleController.getString(R.string.DirectShare);
            case NimarkoConfig.ACTIONS_LEFT_FORWARD_WO_AUTHORSHIP:
                return LocaleController.getString(R.string.NM_MI_ForwardWoAuthorship);
            case NimarkoConfig.ACTIONS_LEFT_REPLY:
            default:
                return LocaleController.getString(R.string.Reply);
        }
    }

    @DrawableRes
    public static int getLeftActionButtonDrawable(boolean noForwards) {
        if (noForwards) return R.drawable.input_reply;
        switch (NimarkoConfig.actionsBarLeftButton) {
            case NimarkoConfig.ACTIONS_LEFT_SAVE_MESSAGE:
                return R.drawable.msg_saved;
            case NimarkoConfig.ACTIONS_LEFT_DIRECT_SHARE:
                return R.drawable.msg_share;
            case NimarkoConfig.ACTIONS_LEFT_FORWARD_WO_AUTHORSHIP:
                return R.drawable.msg_forward;
            case NimarkoConfig.ACTIONS_LEFT_REPLY:
            default:
                return R.drawable.input_reply;
        }
    }

    @DrawableRes
    public static int getReplyIconDrawable() {
        switch (NimarkoConfig.messageSlideAction) {
            case NimarkoConfig.MESSAGE_SLIDE_ACTION_SAVE:
                return R.drawable.msg_saved_filled_solar;
            case NimarkoConfig.MESSAGE_SLIDE_ACTION_DIRECT_SHARE:
                return R.drawable.msg_share_filled;
            case NimarkoConfig.MESSAGE_SLIDE_ACTION_TRANSLATE:
                return R.drawable.msg_translate_filled_solar;
            case NimarkoConfig.MESSAGE_SLIDE_ACTION_REPLY:
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
