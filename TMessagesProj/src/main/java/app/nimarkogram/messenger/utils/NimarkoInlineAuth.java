package app.nimarkogram.messenger.utils;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;

public final class NimarkoInlineAuth {

    public static final String GIVE_UP = "__nimarko_inline_auth_give_up__";

    public static final class Reg {
        public String code;
        public String botUsername;
        public boolean ok;
    }

    public interface Backend {
        String cachedToken();
        void cacheToken(String token);
        Reg register(long uid);
         
        String poll(long uid, String code);
    }

    public interface EnablePredicate {
        boolean isEnabled();
        default boolean runIfEnabled(Runnable action) {
            if (!isEnabled()) return false;
            action.run();
            return true;
        }
    }

    private static final EnablePredicate ALWAYS_ENABLED = () -> true;

    public static String ensureToken(int account, Object lock, Backend backend) {
        return ensureToken(account, lock, backend, ALWAYS_ENABLED);
    }

    public static String ensureToken(int account, Object lock, Backend backend,
                                     EnablePredicate enabled) {
        return ensureToken(account, 0L, lock, backend, enabled);
    }

    public static String ensureToken(int account, long expectedUid, Object lock, Backend backend,
                                     EnablePredicate enabled) {
        synchronized (lock) {
            long uid = accountUid(account);
            if (uid <= 0 || expectedUid > 0 && uid != expectedUid
                    || !isEnabledForAccount(enabled, account, uid)) {
                return null;
            }
            String existing = backend.cachedToken();
            if (existing != null && !existing.isEmpty()) {
                return isEnabledForAccount(enabled, account, uid) ? existing : null;
            }

            if (!isEnabledForAccount(enabled, account, uid)) return null;
            Reg reg = backend.register(uid);
            if (reg == null || !reg.ok || !isEnabledForAccount(enabled, account, uid)) return null;

            sendInlineVerification(account, reg.botUsername, reg.code, enabled, uid);

            for (int i = 0; i < 15; i++) {
                if (!isEnabledForAccount(enabled, account, uid)) return null;
                String token = backend.poll(uid, reg.code);
                if (GIVE_UP.equals(token)) return null;
                if (token != null) {
                    java.util.concurrent.atomic.AtomicBoolean committed =
                            new java.util.concurrent.atomic.AtomicBoolean(false);
                    if (!enabled.runIfEnabled(() -> {
                        if (accountUid(account) != uid) return;
                        backend.cacheToken(token);
                        committed.set(true);
                    }) || !committed.get()
                            || !isEnabledForAccount(enabled, account, uid)) {
                        return null;
                    }
                    return token;
                }
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return null;
                }
                if (!isEnabledForAccount(enabled, account, uid)) return null;
            }
            return null;
        }
    }

    public static void sendInlineVerification(int account, String botUsername, String code) {
        sendInlineVerification(account, botUsername, code, ALWAYS_ENABLED);
    }

    public static void sendInlineVerification(int account, String botUsername, String code,
                                              EnablePredicate enabled) {
        long uid = accountUid(account);
        if (uid <= 0) return;
        sendInlineVerification(account, botUsername, code, enabled, uid);
    }

    private static void sendInlineVerification(int account, String botUsername, String code,
                                               EnablePredicate enabled, long expectedUid) {
        try {
            if (!isEnabledForAccount(enabled, account, expectedUid)) return;
            TLRPC.TL_contacts_resolveUsername resolve = new TLRPC.TL_contacts_resolveUsername();
            resolve.username = botUsername;
            enabled.runIfEnabled(() -> {
                if (accountUid(account) != expectedUid) return;
                ConnectionsManager.getInstance(account).sendRequest(resolve, (response, error) -> {
                    if (!isEnabledForAccount(enabled, account, expectedUid)) return;
                    if (error != null || !(response instanceof TLRPC.TL_contacts_resolvedPeer)) return;
                    TLRPC.TL_contacts_resolvedPeer resolved = (TLRPC.TL_contacts_resolvedPeer) response;
                    if (resolved.users == null || resolved.users.isEmpty()) return;
                    TLRPC.User botUser = resolved.users.get(0);
                    if (botUser == null) return;

                    TLRPC.TL_messages_getInlineBotResults req = new TLRPC.TL_messages_getInlineBotResults();
                    TLRPC.TL_inputUser bot = new TLRPC.TL_inputUser();
                    bot.user_id = botUser.id;
                    bot.access_hash = botUser.access_hash;
                    req.bot = bot;
                    req.query = code;
                    req.offset = "";
                    req.peer = new TLRPC.TL_inputPeerEmpty();
                    enabled.runIfEnabled(() -> {
                        if (accountUid(account) != expectedUid) return;
                        ConnectionsManager.getInstance(account)
                                .sendRequest(req, (r2, e2) -> { });
                    });
                });
            });
        } catch (Throwable t) {
            FileLog.e("nimarko-inline-auth: sendInlineVerification failed", t);
        }
    }

    private static boolean isEnabledForAccount(EnablePredicate enabled, int account, long uid) {
        return enabled != null && uid > 0 && accountUid(account) == uid && enabled.isEnabled();
    }

    private static long accountUid(int account) {
        try {
            return UserConfig.getInstance(account).getClientUserId();
        } catch (Throwable t) {
            return 0L;
        }
    }

    private NimarkoInlineAuth() {}
}
