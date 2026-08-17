 
package app.nimarkogram.messenger.chats;

import android.os.Bundle;

import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.DialogsActivity;

public final class CGChatMenuInjector {

    public static final CGChatMenuInjector INSTANCE = new CGChatMenuInjector();

    private CGChatMenuInjector() {}

    public void openArchivedChats(BaseFragment fragment) {
        if (fragment == null) return;
        Bundle args = new Bundle();
        args.putInt("folderId", 1);
        fragment.presentFragment(new DialogsActivity(args));
    }
}
