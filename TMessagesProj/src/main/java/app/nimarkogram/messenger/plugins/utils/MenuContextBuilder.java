package app.nimarkogram.messenger.plugins.utils;

import android.content.Context;
import java.util.HashMap;
import java.util.Map;
import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_bots;
import org.telegram.ui.ActionBar.BaseFragment;

public class MenuContextBuilder {
    private final Map<String, Object> contextData = new HashMap();

    private MenuContextBuilder() {
    }

    public static MenuContextBuilder create() {
        return new MenuContextBuilder();
    }

    public static MenuContextBuilder from(BaseFragment baseFragment) {
        if (baseFragment == null) {
            return create();
        }
        return create().withCustom("fragment", baseFragment).withAccount(baseFragment.getCurrentAccount()).withContext(baseFragment.getParentActivity());
    }

    public MenuContextBuilder withAccount(int i) {
        this.contextData.put("account", Integer.valueOf(i));
        return this;
    }

    public MenuContextBuilder withContext(Context context) {
        if (context != null) {
            this.contextData.put("context", context);
        }
        return this;
    }

    public MenuContextBuilder withEncryptedChat(TLRPC.EncryptedChat encryptedChat) {
        if (encryptedChat != null) {
            this.contextData.put("encryptedChat", encryptedChat);
        }
        return this;
    }

    public MenuContextBuilder withChat(TLRPC.Chat chat) {
        if (chat != null) {
            this.contextData.put("chat", chat);
            this.contextData.put("chatId", Long.valueOf(chat.id));
        }
        return this;
    }

    public MenuContextBuilder withChatFull(TLRPC.ChatFull chatFull) {
        if (chatFull != null) {
            this.contextData.put("chatFull", chatFull);
        }
        return this;
    }

    public MenuContextBuilder withUser(TLRPC.User user) {
        if (user != null) {
            this.contextData.put("user", user);
            this.contextData.put("userId", Long.valueOf(user.id));
        }
        return this;
    }

    public MenuContextBuilder withUserFull(TLRPC.UserFull userFull) {
        if (userFull != null) {
            this.contextData.put("userFull", userFull);
        }
        return this;
    }

    public MenuContextBuilder withBotInfo(TL_bots.BotInfo botInfo) {
        if (botInfo != null) {
            this.contextData.put("botInfo", botInfo);
        }
        return this;
    }

    public MenuContextBuilder withDialogId(long j) {
        this.contextData.put("dialog_id", Long.valueOf(j));
        return this;
    }

    public MenuContextBuilder withMessage(MessageObject messageObject) {
        if (messageObject != null) {
            this.contextData.put("message", messageObject);
        }
        return this;
    }

    public MenuContextBuilder withGroupedMessage(MessageObject.GroupedMessages groupedMessages) {
        if (groupedMessages != null) {
            this.contextData.put("groupedMessages", groupedMessages);
        }
        return this;
    }

    public MenuContextBuilder withCustom(String str, Object obj) {
        if (str != null && obj != null) {
            this.contextData.put(str, obj);
        }
        return this;
    }

    public Map<String, Object> build() {
        return this.contextData;
    }
}