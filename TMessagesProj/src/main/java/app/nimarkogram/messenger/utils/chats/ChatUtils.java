package app.nimarkogram.messenger.utils.chats;

import android.text.TextUtils;

import java.io.File;
import java.util.concurrent.atomic.AtomicReferenceArray;

import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;

public final class ChatUtils {
    private static final String TAG = "ChatUtils";

    private final int selectedAccount;
    private static final AtomicReferenceArray<ChatUtils> Instance = new AtomicReferenceArray<>(16);
    private static final Object[] lockObjects = new Object[16];

    static {
        for (int i = 0; i < lockObjects.length; i++) lockObjects[i] = new Object();
    }

    public ChatUtils() {
        this(UserConfig.selectedAccount);
    }

    public ChatUtils(int i) {
        this.selectedAccount = i;
    }

    public ChatUtils(Object... args) {
        this.selectedAccount = UserConfig.selectedAccount;
    }

    public static ChatUtils getInstance() {
        return getInstance(UserConfig.selectedAccount);
    }

    public static ChatUtils getInstance(int i) {
        ChatUtils c = Instance.get(i);
        if (c != null) return c;
        synchronized (lockObjects[i]) {
            c = Instance.get(i);
            if (c == null) {
                c = new ChatUtils(i);
                Instance.set(i, c);
            }
        }
        return c;
    }

    public FileLoader getFileLoader() {
        return FileLoader.getInstance(this.selectedAccount);
    }

    private static boolean fileExists(String str) {
        if (TextUtils.isEmpty(str)) return false;
        File file = new File(str);
        return file.exists() && file.isFile();
    }

    public String getPathToMessage(MessageObject messageObject) {
        if (messageObject == null) return null;
        TLRPC.Message message = messageObject.messageOwner;
        if (message != null && !TextUtils.isEmpty(message.attachPath)) {
            String str = messageObject.messageOwner.attachPath;
            if (fileExists(str)) return str;
        }
        try {
            if (messageObject.messageOwner != null) {
                String s = getFileLoader().getPathToMessage(messageObject.messageOwner).toString();
                if (fileExists(s)) return s;
            }
            if (messageObject.getDocument() != null) {
                String s2 = getFileLoader().getPathToAttach(messageObject.getDocument(), true).toString();
                if (fileExists(s2)) return s2;
            }
        } catch (Throwable t) {
            FileLog.e("ChatUtils.getPathToMessage", t);
        }
        return null;
    }
}
