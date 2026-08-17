package com.exteragram.messenger.plugins.hooks;

public interface PluginsHooks extends app.nimarkogram.messenger.plugins.hooks.PluginsHooks {

    class PostRequestResult extends app.nimarkogram.messenger.plugins.hooks.PluginsHooks.PostRequestResult {

        public PostRequestResult(org.telegram.tgnet.TLObject response, org.telegram.tgnet.TLRPC.TL_error error) {
            super(response, error);
        }

        public org.telegram.tgnet.TLObject getResponse() {
            return this.response;
        }

        public void setResponse(org.telegram.tgnet.TLObject response) {
            this.response = response;
        }

        public org.telegram.tgnet.TLRPC.TL_error getError() {
            return this.error;
        }

        public void setError(org.telegram.tgnet.TLRPC.TL_error error) {
            this.error = error;
        }
    }
}
