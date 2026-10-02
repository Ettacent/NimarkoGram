/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.preferences;

import android.os.Bundle;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.DialogsActivity;

import app.nimarkogram.messenger.security.NimarkoBiometricPrompt;
import app.nimarkogram.messenger.utils.LockedChats;

public class LockedChatsPreferencesActivity extends BasePreferencesActivity implements NotificationCenter.NotificationCenterDelegate {

    private static final int ID_ADD = 1_000_001;

    private static final int ID_DIALOG_BASE = 2_000_000;
    private final ArrayList<Long> rowDialogIds = new ArrayList<>();
    private final Runnable refreshNames = () -> {
        if (listView != null && listView.adapter != null) listView.adapter.update(true);
    };

    @Override
    public boolean onFragmentCreate() {
        if (!super.onFragmentCreate()) return false;
        NotificationCenter center = NotificationCenter.getInstance(currentAccount);
        center.addObserver(this, NotificationCenter.updateInterfaces);
        center.addObserver(this, NotificationCenter.userInfoDidLoad);
        center.addObserver(this, NotificationCenter.chatInfoDidLoad);
        center.addObserver(this, NotificationCenter.contactsDidLoad);
        return true;
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter center = NotificationCenter.getInstance(currentAccount);
        center.removeObserver(this, NotificationCenter.updateInterfaces);
        center.removeObserver(this, NotificationCenter.userInfoDidLoad);
        center.removeObserver(this, NotificationCenter.chatInfoDidLoad);
        center.removeObserver(this, NotificationCenter.contactsDidLoad);
        if (listView != null) listView.removeCallbacks(refreshNames);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (account != currentAccount || listView == null) return;
        if (id == NotificationCenter.updateInterfaces) {
            int mask = (Integer) args[0];
            if ((mask & (MessagesController.UPDATE_MASK_NAME | MessagesController.UPDATE_MASK_CHAT_NAME
                    | MessagesController.UPDATE_MASK_USER_PHONE)) == 0) return;
        }
        listView.removeCallbacks(refreshNames);
        listView.post(refreshNames);
    }

    @Override
    public String getTitle() {
        return LocaleController.getString(R.string.NM_PR_LockedChats);
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(asSettingsLink(ID_ADD, IconBackgroundColors.BLUE,
                R.drawable.msg_contact_add, LocaleController.getString(R.string.FilterAddChats)));

        rowDialogIds.clear();
        List<String> all = LockedChats.getAll(currentAccount);
        if (all.isEmpty()) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.NM_PR_NoLockedChats)));
        } else {
            items.add(UItem.asShadow(null));
            items.add(UItem.asHeader(LocaleController.getString(R.string.NM_PR_LockedChats)));
            MessagesController messagesController = MessagesController.getInstance(currentAccount);
            for (String s : all) {
                long did;
                try { did = Long.parseLong(s); } catch (Throwable t) { continue; }
                String name = displayNameFor(messagesController, did);

                int rowId = ID_DIALOG_BASE + rowDialogIds.size();
                rowDialogIds.add(did);
                UItem row = UItem.asCheck(rowId, name).setChecked(true);
                row.longValue = did;
                items.add(row);
            }
            items.add(UItem.asShadow(LocaleController.getString(R.string.NM_PR_LockedChatsHint)));
        }
    }

    private static String displayNameFor(MessagesController messagesController, long dialogId) {
        if (messagesController == null) return String.valueOf(dialogId);
        if (DialogObject.isEncryptedDialog(dialogId)) {
            TLRPC.EncryptedChat encrypted = messagesController.getEncryptedChat(DialogObject.getEncryptedChatId(dialogId));
            if (encrypted == null) return String.valueOf(dialogId);
            dialogId = encrypted.user_id;
        }
        if (dialogId >= 0) {
            TLRPC.User u = messagesController.getUser(dialogId);
            if (u != null) {
                String name = UserObject.getUserName(u);
                if (!android.text.TextUtils.isEmpty(name)) return name;
                String username = UserObject.getPublicUsername(u, false);
                return android.text.TextUtils.isEmpty(username) ? String.valueOf(dialogId) : "@" + username;
            }
        } else {
            TLRPC.Chat c = messagesController.getChat(-dialogId);
            if (c != null && c.title != null) return c.title;
        }
        return String.valueOf(dialogId);
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        int id = item.id;
        if (id == ID_ADD) {
            final int account = currentAccount;
            final long ownerUid = UserConfig.getInstance(account).getClientUserId();
            if (ownerUid <= 0) {
                showAuthenticationRequired();
                return;
            }
            Bundle args = new Bundle();
            args.putBoolean("onlySelect", true);
            args.putInt("dialogsType", DialogsActivity.DIALOGS_TYPE_FORWARD);
            DialogsActivity picker = new DialogsActivity(args);
            picker.setDelegate((fragment, dids, message, param, notify, scheduleDate, scheduleRepeatPeriod, topicsFragment) -> {
                boolean applied = false;
                if (dids != null) {
                    for (org.telegram.messenger.MessagesStorage.TopicKey k : dids) {
                        if (k != null) {
                            applied |= LockedChats.setLocked(account, ownerUid, k.dialogId, true);
                        }
                    }
                }
                if (!applied) {
                    return false;
                }
                if (listView != null && listView.adapter != null) {
                    listView.adapter.update(true);
                }

                fragment.finishFragment();
                return true;
            });
            presentFragment(picker);
            return;
        }
        if (id >= ID_DIALOG_BASE && item.longValue != 0) {
            final int account = currentAccount;
            final long ownerUid = UserConfig.getInstance(account).getClientUserId();
            final long did = item.longValue;
            if (getParentActivity() == null) {
                showAuthenticationRequired();
                return;
            }
            NimarkoBiometricPrompt.prompt(getParentActivity(), account, () -> {
                if (!LockedChats.setLocked(account, ownerUid, did, false)) return;
                if (listView != null && listView.adapter != null) listView.adapter.update(true);
            }, this::showAuthenticationRequired);
        }
    }

    private void showAuthenticationRequired() {
        BulletinFactory.of(this).createErrorBulletin(
                LocaleController.getString(R.string.NM_PR_AuthenticationRequired)
        ).show();
    }

}
