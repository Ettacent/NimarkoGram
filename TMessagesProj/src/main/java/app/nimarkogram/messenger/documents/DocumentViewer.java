/* Modifications Copyright (C) 2026 Ettacent */
package app.nimarkogram.messenger.documents;

import android.app.Activity;
import android.os.Looper;
import android.view.WindowManager;

import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FlagSecureReason;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.BubbleActivity;
import org.telegram.ui.LaunchActivity;

import java.io.File;

import java.util.Locale;
import app.nimarkogram.messenger.AppConfig;
import app.nimarkogram.messenger.utils.chats.ChatsPasswordHelper;

public final class DocumentViewer {
    private DocumentViewer() {}

    public static BaseFragment findOwner(Activity activity) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return null;
        BaseFragment current = LaunchActivity.getSafeLastFragment();
        if (current != null && current.getParentActivity() == activity) return current;
        if (activity instanceof LaunchActivity && ((LaunchActivity) activity).getActionBarLayout() != null) {
            current = ((LaunchActivity) activity).getActionBarLayout().getSafeLastFragment();
        } else if (activity instanceof BubbleActivity && ((BubbleActivity) activity).actionBarLayout != null) {
            current = ((BubbleActivity) activity).actionBarLayout.getSafeLastFragment();
        }
        return current != null && current.getParentActivity() == activity ? current : null;
    }

    public static boolean tryOpen(File file, String fileName, String mimeType, Activity activity,
                                  boolean restrict, MessageObject source, BaseFragment owner) {
        return tryOpen(file, fileName, mimeType, activity, restrict, source, owner, null);
    }

    public static boolean tryOpen(File file, String fileName, String mimeType, Activity activity,
                                  boolean restrict, MessageObject source, BaseFragment owner, TLRPC.Document openedDocument) {
        boolean secure = isProtected(source, activity);
        if (source != null && !isSourceCurrent(source, activity, owner)) return true;
        if (!AppConfig.documentViewer && !secure) return false;
        DocumentTypes.Kind kind = DocumentTypes.detect(fileName, mimeType);
        if (kind == DocumentTypes.Kind.NONE) {
            if (canInstallPackage(fileName, source, secure)) return false;
            if (secure && activity != null && !activity.isFinishing() && Looper.myLooper() == Looper.getMainLooper()) {
                new AlertDialog.Builder(activity).setMessage(LocaleController.getString(R.string.NM_DocProtectedError))
                        .setPositiveButton(LocaleController.getString(R.string.OK), null).show();
            }
            return secure;
        }
        if (file == null || !file.isFile() || Looper.myLooper() != Looper.getMainLooper()) return secure;
        if (owner == null) owner = findOwner(activity);
        if (owner == null || owner.getParentActivity() != activity || activity.isFinishing() || activity.isDestroyed()) return secure;
        final int account = source == null ? owner.getCurrentAccount() : source.currentAccount;
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT || account != owner.getCurrentAccount()
                || !UserConfig.getInstance(account).isClientActivated()) return true;
        String displayName = resolveDisplayName(file, fileName, source);
        if (openedDocument != null && FileLoader.getAttachFileName(openedDocument).equals(fileName)) {
            String documentName = FileLoader.getDocumentFileName(openedDocument);
            if (documentName != null && !documentName.isEmpty()) displayName = documentName;
        }
        DocumentViewerActivity viewer = new DocumentViewerActivity(file, displayName, mimeType, kind, source, account, secure, restrict);
        boolean presented = owner.presentFragment(viewer);
        return presented || secure;
    }

    private static boolean canInstallPackage(String fileName, MessageObject source, boolean secure) {
        if (fileName == null || !fileName.toLowerCase(Locale.ROOT).endsWith(".apk")) return false;
        if (source == null) return !secure;
        long dialogId = source.getDialogId();
        return !DialogObject.isEncryptedDialog(dialogId) && !source.isSecretMedia()
                && !source.hasRevealedExtendedMedia()
                && !ChatsPasswordHelper.isChatLocked(source.currentAccount, dialogId);
    }

    private static String resolveDisplayName(File file, String fileName, MessageObject source) {
        if (source == null || source.messageOwner == null) return fileName;
        TLRPC.Document document = source.getDocument();
        if (document == null) return fileName;
        String documentName = FileLoader.getDocumentFileName(document);
        if (documentName == null || documentName.isEmpty()
                || !FileLoader.getAttachFileName(document).equals(fileName)) return fileName;
        File openedFile = file.getAbsoluteFile();
        String attachPath = source.messageOwner.attachPath;
        if (attachPath != null && !attachPath.isEmpty()
                && openedFile.equals(new File(attachPath).getAbsoluteFile())) return documentName;
        File sourceFile = FileLoader.getInstance(source.currentAccount).getPathToMessage(source.messageOwner);
        return sourceFile != null && openedFile.equals(sourceFile.getAbsoluteFile()) ? documentName : fileName;
    }

    public static boolean isSourceCurrent(MessageObject source, Activity activity, BaseFragment owner) {
        if (source == null || source.currentAccount < 0 || source.currentAccount >= UserConfig.MAX_ACCOUNT_COUNT
                || !UserConfig.getInstance(source.currentAccount).isClientActivated()) return false;
        if (owner == null) owner = findOwner(activity);
        return owner == null || owner.getParentActivity() == activity && owner.getCurrentAccount() == source.currentAccount;
    }

    public static boolean isProtected(MessageObject source, Activity activity) {
        if (activity != null && (FlagSecureReason.isSecuredNow(activity.getWindow())
                || (activity.getWindow().getAttributes().flags & WindowManager.LayoutParams.FLAG_SECURE) != 0)) return true;
        if (source == null) return false;
        if (source.currentAccount < 0 || source.currentAccount >= UserConfig.MAX_ACCOUNT_COUNT) return true;
        long dialogId = source.getDialogId();
        return DialogObject.isEncryptedDialog(dialogId) || source.isSecretMedia()
                || source.messageOwner != null && source.messageOwner.noforwards
                || source.hasRevealedExtendedMedia()
                || MessagesController.getInstance(source.currentAccount).isPeerNoForwards(dialogId)
                || ChatsPasswordHelper.isChatLocked(source.currentAccount, dialogId);
    }
}
