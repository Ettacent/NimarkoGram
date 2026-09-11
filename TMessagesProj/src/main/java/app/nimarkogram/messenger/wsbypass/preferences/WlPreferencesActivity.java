package app.nimarkogram.messenger.wsbypass.preferences;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.view.View;

import java.lang.ref.WeakReference;
import java.util.ArrayList;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.Components.IconBackgroundColors;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.ActionBar.AlertDialog;

import app.nimarkogram.messenger.preferences.BasePreferencesActivity;
import app.nimarkogram.messenger.wsbypass.WlAccess;

public final class WlPreferencesActivity extends BasePreferencesActivity {
    private static final int PICK_REQUEST = 9913;
    private static final int ENABLE = 301, STATUS = 302, PICK = 303, NORMAL = 306, DETAILS = 307;
    private int account = UserConfig.selectedAccount;
    private long owner = UserConfig.getInstance(account).getClientUserId();
    private long pickOwner;
    private int pickAccount;
    private boolean picking;
    private boolean visible;
    private int generation;
    private boolean busy;
    private String status = "loading";
    private final Runnable accessChanged = this::onAccessChanged;
    private final Runnable statusPoll = () -> {
        if (visible && !busy && !picking && shouldPoll()) refresh(false);
    };

    @Override public String getTitle() { return text(R.string.NM_WL_Title); }

    @Override public void onResume() {
        super.onResume();
        visible = true;
        WlAccess.addListener(accessChanged);
        syncAccount();
        if (!busy && !picking) refresh(false);
        schedulePoll();
    }
    private boolean syncAccount() {
        int selected = UserConfig.selectedAccount;
        long selectedUid = UserConfig.getInstance(selected).getClientUserId();
        if (account != selected || owner != selectedUid) {
            account = selected; owner = selectedUid;
            generation++; busy = false; status = "loading";
            return true;
        }
        return false;
    }
    private void onAccessChanged() {
        if (!visible) return;
        boolean changedAccount = syncAccount();
        if (changedAccount || WlAccess.cached() == null && "approved".equals(status)) {
            status = "loading";
            if (!busy && !picking) refresh(false);
        }
        reload();
        schedulePoll();
    }

    @Override public void onPause() {
        visible = false;
        WlAccess.removeListener(accessChanged);
        AndroidUtilities.cancelRunOnUIThread(statusPoll);
        super.onPause();
    }

    @Override public void onFragmentDestroy() {
        visible = false;
        picking = false;
        generation++;
        WlAccess.removeListener(accessChanged);
        AndroidUtilities.cancelRunOnUIThread(statusPoll);
        super.onFragmentDestroy();
    }

    @Override public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(text(R.string.NM_WL_Route)));
        items.add(UItem.asRadio(NORMAL, text(R.string.NM_WL_Ordinary)).setChecked(!WlAccess.enabled()));
        items.add(UItem.asRadio(ENABLE, text(R.string.NM_WL_Network))
                .setChecked(WlAccess.enabled()));
        items.add(UItem.asShadow(text(R.string.NM_WL_About)));
        items.add(UItem.asHeader(text(R.string.NM_WL_Request)));
        items.add(asSettingsLink(STATUS, IconBackgroundColors.BLUE, R.drawable.msg_info,
                text(R.string.NM_WL_Status), text(statusString())).setEnabled(false));
        if (canSubmit()) {
            items.add(asSettingsLink(PICK, IconBackgroundColors.PURPLE, R.drawable.msg_photos,
                    text(R.string.NM_WL_Choose),
                    text(R.string.NM_WL_FileHint)).setEnabled(!busy && !picking));
        }
        items.add(asSettingsLink(DETAILS, IconBackgroundColors.CYAN, R.drawable.msg_help,
                text(R.string.NM_WL_Details)));
        items.add(UItem.asShadow(text(WlAccess.cached() != null
                ? R.string.NM_WL_SharedAccess : R.string.NM_WL_AutoSubmit)));
    }

    @Override public void onClick(UItem item, View view, int position, float x, float y) {
        if (item == null) return;
        if (item.id == DETAILS && getParentActivity() != null) {
            showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                    .setTitle(text(R.string.NM_WL_Details))
                    .setMessage(text(R.string.NM_WL_Privacy))
                    .setPositiveButton(text(R.string.OK), null).create());
            return;
        }
        if (item.id == NORMAL) {
            WlAccess.setEnabled(false);
            reload();
            return;
        }
        if (item.id == ENABLE) {
            if (WlAccess.cached() != null) {
                WlAccess.setEnabled(true);
                reload();
            } else if (!busy && !picking) refresh(true);
            return;
        }
        if (busy || picking) return;
        if (item.id == PICK && canSubmit() && getParentActivity() != null) {
            try {
                pickOwner = owner;
                pickAccount = account;
                picking = true;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*")
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/png", "image/jpeg", "image/webp"});
                getParentActivity().startActivityForResult(Intent.createChooser(intent, text(R.string.NM_WL_Choose)), PICK_REQUEST);
            } catch (Exception e) { picking = false; status = "error"; reload(); }
        }
    }

    @Override public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode != PICK_REQUEST || !picking) return;
        picking = false;
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            schedulePoll();
            reload();
            return;
        }
        if (account != pickAccount || UserConfig.selectedAccount != pickAccount
                || owner != pickOwner || UserConfig.getInstance(pickAccount).getClientUserId() != pickOwner) {
            status = "account_changed"; reload(); return;
        }
        if (WlAccess.cached() != null) {
            reload();
            schedulePoll();
            return;
        }
        Uri screenshot = data.getData();
        busy = true;
        status = "sending";
        AndroidUtilities.cancelRunOnUIThread(statusPoll);
        reload();
        WlAccess.submit(account, screenshot, callback());
    }

    private void refresh(boolean showProgress) {
        busy = true;
        if (showProgress) status = "loading";
        reload();
        WlAccess.refresh(account, callback());
    }

    private WlAccess.Callback callback() {
        int expected = ++generation;
        long expectedUid = owner;
        WeakReference<WlPreferencesActivity> ref = new WeakReference<>(this);
        return state -> {
            WlPreferencesActivity screen = ref.get();
            if (screen == null || screen.generation != expected || screen.owner != expectedUid
                    || UserConfig.getInstance(screen.account).getClientUserId() != expectedUid) return;
            screen.busy = false; screen.status = state;
            screen.reload();
            screen.schedulePoll();
        };
    }

    private int statusString() {
        if (WlAccess.cached() != null) return WlAccess.hasAccountGrant(account)
                ? R.string.NM_WL_Approved : R.string.NM_WL_OtherAccount;
        if ("sending".equals(status)) return R.string.NM_WL_Sending;
        if ("loading".equals(status)) return R.string.NM_WL_Checking;
        switch (status) {
            case "approved": return R.string.NM_WL_Approved;
            case "pending": return R.string.NM_WL_Pending;
            case "blocked": return R.string.NM_WL_Blocked;
            case "rejected": return R.string.NM_WL_Rejected;
            case "expired": return R.string.NM_WL_Expired;
            case "revoked": return R.string.NM_WL_Revoked;
            case "busy": return R.string.NM_WL_Busy;
            case "none": return R.string.NM_WL_None;
            case "file_too_large": return R.string.NM_WL_FileLarge;
            case "invalid_screenshot": return R.string.NM_WL_FileInvalid;
            case "authentication_required": return R.string.NM_WL_AuthRequired;
            case "account_changed": return R.string.NM_WL_AccountChanged;
            default: return R.string.NM_WL_Error;
        }
    }

    private static String text(int id) { return LocaleController.getString(id); }
    private boolean canSubmit() {
        return WlAccess.cached() == null && !"loading".equals(status) && !"sending".equals(status)
                && !"approved".equals(status) && !"blocked".equals(status) && !"pending".equals(status);
    }
    private boolean shouldPoll() {
        return WlAccess.cached() != null || "loading".equals(status) || "approved".equals(status)
                || "pending".equals(status) || "error".equals(status) || "busy".equals(status)
                || "authentication_required".equals(status);
    }
    private void schedulePoll() {
        AndroidUtilities.cancelRunOnUIThread(statusPoll);
        if (visible && !busy && !picking && shouldPoll()) {
            AndroidUtilities.runOnUIThread(statusPoll, "pending".equals(status) ? 30_000 : 60_000);
        }
    }
    private void reload() { if (listView != null && listView.adapter != null) listView.adapter.update(true); }
}
