 
package app.nimarkogram.messenger.utils.chats;

import android.view.View;

import app.nimarkogram.messenger.NimarkoConfig;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;

public final class DoubleTapUtils {

    private DoubleTapUtils() {}

    public static int sanitizeSetting(int i) {
        if (i < 0) {
            return 0;
        }
        return Math.min(i, 9);
    }

    public static int getActionId(int i, boolean outgoing) {
        int sanitized = (i == 9) ? 9 : sanitizeSetting(i);
        if (!outgoing) {
            switch (sanitized) {
                case 1:
                    return 1;
                case 2:
                    return 2;
                case 3:
                    return 3;
                case 4:
                    return 4;
                case 5:
                    return 6;
                case 6:
                    return 7;
                case 7:
                    return 8;
                case 8:
                    return 9;
                default:
                    return 0;
            }
        }
        switch (sanitized) {
            case 1:
                return 1;
            case 2:
                return 2;
            case 3:
                return 3;
            case 4:
                return 4;
            case 5:
                return 5;
            case 6:
                return 6;
            case 7:
                return 7;
            case 8:
                return 8;
            case 9:
                return 9;
            default:
                return 0;
        }
    }

    public static int getDoubleTapActionIcon(int i, boolean outgoing) {
        int[] icons = getDoubleTapIcons(outgoing);
        int index = Math.min(Math.max(i, 0), icons.length - 1);
        return icons[index];
    }

    public static CharSequence getDoubleTapActionLabel(int i, boolean outgoing) {
        CharSequence[] actions = getDoubleTapActions(outgoing);
        int index = Math.min(Math.max(i, 0), actions.length - 1);
        return actions[index];
    }

    public static CharSequence[] getDoubleTapActions(boolean outgoing) {
        if (!outgoing) {
            return new CharSequence[]{
                    LocaleController.getString(R.string.Disable),
                    LocaleController.getString(R.string.Reactions),
                    LocaleController.getString(R.string.Reply),
                    LocaleController.getString(R.string.Copy),
                    LocaleController.getString(R.string.Forward),
                    LocaleController.getString(R.string.Save),
                    LocaleController.getString(R.string.Repeat),
                    LocaleController.getString(R.string.Delete),
                    LocaleController.getString(R.string.TranslateMessage)
            };
        }
        return new CharSequence[]{
                LocaleController.getString(R.string.Disable),
                LocaleController.getString(R.string.Reactions),
                LocaleController.getString(R.string.Reply),
                LocaleController.getString(R.string.Copy),
                LocaleController.getString(R.string.Forward),
                LocaleController.getString(R.string.Edit),
                LocaleController.getString(R.string.Save),
                LocaleController.getString(R.string.Repeat),
                LocaleController.getString(R.string.Delete),
                LocaleController.getString(R.string.TranslateMessage)
        };
    }

    public static int[] getDoubleTapIcons(boolean outgoing) {
        if (!outgoing) {
            return new int[]{
                    R.drawable.msg_block,
                    R.drawable.msg_reactions2,
                    R.drawable.menu_reply,
                    R.drawable.msg_copy,
                    R.drawable.msg_forward,
                    R.drawable.msg_saved,
                    R.drawable.msg_retry,
                    R.drawable.msg_delete,
                    R.drawable.msg_translate
            };
        }
        return new int[]{
                R.drawable.msg_block,
                R.drawable.msg_reactions2,
                R.drawable.menu_reply,
                R.drawable.msg_copy,
                R.drawable.msg_forward,
                R.drawable.msg_edit,
                R.drawable.msg_saved,
                R.drawable.msg_retry,
                R.drawable.msg_delete,
                R.drawable.msg_translate
        };
    }

    public static boolean isCustomActionEnabled() {
        int a = NimarkoConfig.doubleTapAction;
        return a != NimarkoConfig.DTAP_NONE && a != NimarkoConfig.DTAP_REACTION;
    }

    public static boolean isDoubleTapEnabled() {
        return NimarkoConfig.doubleTapAction != NimarkoConfig.DTAP_NONE;
    }

    public static boolean canHandle(MessageObject message) {
        if (message == null) return false;
        int action = NimarkoConfig.doubleTapAction;
        switch (action) {
            case NimarkoConfig.DTAP_REPLY:
                return !message.isSending() && message.getId() > 0 && !message.isSponsored();
            case NimarkoConfig.DTAP_SAVE:
                return !message.isSending() && !message.isSponsored() && !message.isSecret();
            case NimarkoConfig.DTAP_EDIT:
                return message.canEditMessage(null);
            case NimarkoConfig.DTAP_EDIT_OR_REACTION:
                // Editable (own) → we'll edit; otherwise → we'll fall through to a reaction.
                return message.canEditMessage(null) || message.canSetReaction();
            case NimarkoConfig.DTAP_TRANSLATE:
                return message.messageOwner != null && message.messageOwner.message != null
                        && message.messageOwner.message.length() > 0;
            default:
                return false;
        }
    }

    public static boolean dispatch(Object chatActivity, View cell, MessageObject message) {
        if (message == null || chatActivity == null) return false;
        try {
            switch (NimarkoConfig.doubleTapAction) {
                case NimarkoConfig.DTAP_REPLY: {
                    chatActivity.getClass()
                            .getMethod("showFieldPanelForReply", MessageObject.class)
                            .invoke(chatActivity, message);
                    return true;
                }
                case NimarkoConfig.DTAP_SAVE: {
                    
                    long selfId = org.telegram.messenger.UserConfig.getInstance(message.currentAccount).getClientUserId();
                    long targetId = NimarkoConfig.getEffectiveSavedMessagesDialogId(message.currentAccount, selfId);
                    java.util.ArrayList<MessageObject> list = new java.util.ArrayList<>();
                    list.add(message);
                    org.telegram.messenger.SendMessagesHelper.getInstance(message.currentAccount)
                            .sendMessage(list, targetId, false, false, true, 0, null, -1, 0L);
                    return true;
                }
                case NimarkoConfig.DTAP_EDIT: {
                    
                    java.lang.reflect.Method m = chatActivity.getClass()
                            .getDeclaredMethod("startEditingMessageObject", MessageObject.class);
                    m.setAccessible(true);
                    m.invoke(chatActivity, message);
                    return true;
                }
                case NimarkoConfig.DTAP_EDIT_OR_REACTION: {
                    
                    if (!message.canEditMessage(null)) return false;
                    java.lang.reflect.Method m = chatActivity.getClass()
                            .getDeclaredMethod("startEditingMessageObject", MessageObject.class);
                    m.setAccessible(true);
                    m.invoke(chatActivity, message);
                    return true;
                }
                case NimarkoConfig.DTAP_TRANSLATE: {
                    
                    if (cell == null) return false;
                    org.telegram.ui.Components.TranslateAlert2.showAlert(
                            cell.getContext(),
                            null,
                            message.currentAccount,
                            "und",
                            org.telegram.messenger.LocaleController.getInstance().getCurrentLocale().getLanguage(),
                            message.messageOwner != null ? message.messageOwner.message : "",
                            message.messageOwner != null ? message.messageOwner.entities : null,
                            false,
                            null,
                            null
                    );
                    return true;
                }
                default:
                    return false;
            }
        } catch (Throwable t) {
            FileLog.e("DoubleTapUtils dispatch failure", t);
            return false;
        }
    }
}
