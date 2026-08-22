package app.nimarkogram.messenger.preferences;

import android.os.Bundle;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.DialogsActivity;

import app.nimarkogram.messenger.security.NimarkoBiometricPrompt;
import app.nimarkogram.messenger.utils.LockedChats;

public class LockedChatsPreferencesActivity extends BasePreferencesActivity {

    private static final int ID_ADD = 1_000_001;
    private static final int ID_DIALOG_BASE = 2_000_000;
    private final ArrayList<Long> rowDialogIds = new ArrayList<>();

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
                items.add(UItem.asCheck(rowId, name).setChecked(true));
            }
            items.add(UItem.asShadow(LocaleController.getString(R.string.NM_PR_LockedChatsHint)));
        }
    }

    private static String displayNameFor(MessagesController messagesController, long dialogId) {
        if (messagesController == null) return String.valueOf(dialogId);
        if (dialogId >= 0) {
            TLRPC.User u = messagesController.getUser(dialogId);
            if (u != null) {
                String first = u.first_name == null ? "" : u.first_name;
                String last = u.last_name == null ? "" : u.last_name;
                String combined = (first + " " + last).trim();
                return combined.isEmpty() ? String.valueOf(dialogId) : combined;
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
        int idx = id - ID_DIALOG_BASE;
        if (idx >= 0 && idx < rowDialogIds.size()) {
            final int account = currentAccount;
            final long ownerUid = UserConfig.getInstance(account).getClientUserId();
            final long did = rowDialogIds.get(idx);
            if (getParentActivity() == null) {
                showAuthenticationRequired();
                return;
            }
            NimarkoBiometricPrompt.prompt(getParentActivity(), account, () -> {
                if (!LockedChats.setLocked(account, ownerUid, did, false)) return;
                if (listView != null && listView.adapter != null) listView.adapter.update(true);
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(false);
                }
            }, this::showAuthenticationRequired);
        }
    }

    private void showAuthenticationRequired() {
        BulletinFactory.of(this).createErrorBulletin(
                LocaleController.getString(R.string.NM_PR_AuthenticationRequired)
        ).show();
    }

}
