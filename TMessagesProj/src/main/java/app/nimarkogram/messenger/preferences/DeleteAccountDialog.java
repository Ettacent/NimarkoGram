 
package app.nimarkogram.messenger.preferences;

import static org.telegram.messenger.LocaleController.getString;

import android.os.CountDownTimer;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import java.util.Locale;

public final class DeleteAccountDialog {

    private DeleteAccountDialog() {}

    public static void showDeleteAccountDialog(BaseFragment fragment) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }

        final int currentAccount = fragment.getCurrentAccount();

        AlertDialog.Builder builder = new AlertDialog.Builder(fragment.getParentActivity());
        builder.setMessage(getString(R.string.TosDeclineDeleteAccount));
        builder.setTitle(getString(R.string.NM_PR_DeleteAccount));
        builder.setPositiveButton(getString(R.string.Deactivate), (dialogInterface, which) -> {
            
            if (BuildConfig.DEBUG) return;

            final AlertDialog progressDialog = new AlertDialog(fragment.getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
            progressDialog.setCanCancel(false);

            TL_account.deleteAccount req = new TL_account.deleteAccount();
            req.reason = "Nimarko";
            ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                    try {
                        progressDialog.dismiss();
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                    if (response instanceof TLRPC.TL_boolTrue) {
                        MessagesController.getInstance(currentAccount).performLogout(0);
                    } else if (error == null || error.code != -1000) {
                        String errorText = getString(R.string.ErrorOccurred);
                        if (error != null) {
                            errorText += "\n" + error.text;
                        }
                        AlertDialog.Builder builder1 = new AlertDialog.Builder(fragment.getParentActivity());
                        builder1.setTitle(getString(R.string.exteraAppName));
                        builder1.setMessage(errorText);
                        builder1.setPositiveButton(getString(R.string.OK), null);
                        builder1.show();
                    }
            }));
            progressDialog.show();
        });
        builder.setNegativeButton(getString(R.string.Cancel), null);

        AlertDialog dialog = builder.create();
        dialog.setOnShowListener(d -> {
            TextView button = (TextView) dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            if (button == null) return;
            button.setTextColor(fragment.getThemedColor(Theme.key_text_RedBold));
            button.setEnabled(false);
            CharSequence buttonText = button.getText();
            new CountDownTimer(20000, 100) {
                @Override
                public void onTick(long millisUntilFinished) {
                    button.setText(String.format(Locale.getDefault(), "%s (%d)", buttonText, millisUntilFinished / 1000 + 1));
                }

                @Override
                public void onFinish() {
                    button.setText(buttonText);
                    button.setEnabled(true);
                }
            }.start();
        });
        fragment.showDialog(dialog);
    }
}
