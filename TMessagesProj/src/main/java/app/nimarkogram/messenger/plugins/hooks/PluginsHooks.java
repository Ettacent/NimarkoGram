package app.nimarkogram.messenger.plugins.hooks;

import org.telegram.messenger.SendMessagesHelper;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;

public interface PluginsHooks {
    PostRequestResult executePostRequestHook(String str, int i, TLObject tLObject, TLRPC.TL_error tL_error);

    TLObject executePreRequestHook(String str, int i, TLObject tLObject);

    SendMessagesHelper.SendMessageParams executeSendMessageHook(int i, SendMessagesHelper.SendMessageParams sendMessageParams);

    TLRPC.Update executeUpdateHook(String str, int i, TLRPC.Update update);

    TLRPC.Updates executeUpdatesHook(String str, int i, TLRPC.Updates updates);

    class PostRequestResult {
        public TLRPC.TL_error error;
        public TLObject response;

        public PostRequestResult(TLObject response, TLRPC.TL_error error) {
            this.response = response;
            this.error = error;
        }
    }
}