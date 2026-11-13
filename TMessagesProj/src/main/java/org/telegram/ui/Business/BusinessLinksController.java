/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Business;

import android.text.TextUtils;

import org.telegram.SQLite.SQLiteCursor;
import org.telegram.SQLite.SQLiteDatabase;
import org.telegram.SQLite.SQLitePreparedStatement;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.NativeByteBuffer;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.BulletinFactory;

import java.util.ArrayList;

import java.util.HashMap;
import java.util.concurrent.atomic.AtomicBoolean;
public class BusinessLinksController {

    private static volatile BusinessLinksController[] Instance = new BusinessLinksController[UserConfig.MAX_ACCOUNT_COUNT];
    private static final Object[] lockObjects = new Object[UserConfig.MAX_ACCOUNT_COUNT];

    static {
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            lockObjects[i] = new Object();
        }
    }

    public static BusinessLinksController getInstance(int num) {
        BusinessLinksController localInstance = Instance[num];
        if (localInstance == null) {
            synchronized (lockObjects[num]) {
                localInstance = Instance[num];
                if (localInstance == null) {
                    Instance[num] = localInstance = new BusinessLinksController(num);
                }
            }
        }
        return localInstance;
    }

    public final int currentAccount;

    public final ArrayList<TL_account.TL_businessChatLink> links = new ArrayList<>();

    public interface EditorMessageSaveCallback {
        void onComplete(boolean success);
    }

    private static final class EditorMessageSaves {
        long nextSequence;
        long savedSequence;
        int pending;
    }

    private static final class EditorMessageSave {
        final String slug;
        final long userId;
        final long session;
        final EditorMessageSaves saves;
        final long sequence;
        final EditorMessageSaveCallback callback;
        final AtomicBoolean completed = new AtomicBoolean();

        EditorMessageSave(String slug, long userId, long session, EditorMessageSaves saves, EditorMessageSaveCallback callback) {
            this.slug = slug;
            this.userId = userId;
            this.session = session;
            this.saves = saves;
            sequence = ++saves.nextSequence;
            saves.pending++;
            this.callback = callback;
        }

        boolean isCurrent(int account) {
            return app.nimarkogram.messenger.notifications.InAppNotifications.isCurrent(account, userId, session);
        }
    }

    private final HashMap<String, EditorMessageSaves> editorMessageSaves = new HashMap<>();
    private boolean loading = false;
    private boolean loaded = false;

    private BusinessLinksController(int currentAccount) {
        this.currentAccount = currentAccount;
    }

    public static String stripHttps(String link) {
        if (link.startsWith("https://")) {
            return link.substring(8);
        }
        return link;
    }

    public boolean canAddNew() {
        return links.size() < MessagesController.getInstance(currentAccount).businessChatLinksLimit;
    }

    public void load(boolean forceReload) {
        if (!loaded) {
            load(true, forceReload);
        } else if (forceReload) {
            load(false, true);
        }
    }

    private void load(boolean fromCache, boolean forceReload) {
        if (loading || loaded && (!forceReload || fromCache)) {
            return;
        }

        loading = true;

        if (fromCache) {
            MessagesStorage storage = MessagesStorage.getInstance(currentAccount);
            storage.getStorageQueue().postRunnable(() -> {
                final ArrayList<TL_account.TL_businessChatLink> result = new ArrayList<>();
                final ArrayList<TLRPC.User> users = new ArrayList<>();
                final ArrayList<TLRPC.Chat> chats = new ArrayList<>();

                SQLiteCursor cursor = null;
                try {
                    SQLiteDatabase db = storage.getDatabase();
                    cursor = db.queryFinalized("SELECT data FROM business_links ORDER BY order_value ASC");
                    while (cursor.next()) {
                        NativeByteBuffer data = cursor.byteBufferValue(0);
                        TL_account.TL_businessChatLink link = TL_account.TL_businessChatLink.TLdeserialize(data, data.readInt32(false), false);
                        result.add(link);
                    }
                    cursor.dispose();

                    final ArrayList<Long> usersToLoad = new ArrayList<>();
                    final ArrayList<Long> chatsToLoad = new ArrayList<>();
                    for (int i = 0; i < result.size(); ++i) {
                        TL_account.TL_businessChatLink link = result.get(i);
                        if (!link.entities.isEmpty()) {
                            for (int a = 0; a < link.entities.size(); a++) {
                                TLRPC.MessageEntity entity = link.entities.get(a);
                                if (entity instanceof TLRPC.TL_messageEntityMentionName) {
                                    usersToLoad.add(((TLRPC.TL_messageEntityMentionName) entity).user_id);
                                } else if (entity instanceof TLRPC.TL_inputMessageEntityMentionName) {
                                    usersToLoad.add(((TLRPC.TL_inputMessageEntityMentionName) entity).user_id.user_id);
                                }
                            }
                        }
                    }
                    if (!usersToLoad.isEmpty()) {
                        storage.getUsersInternal(usersToLoad, users);
                    }
                    if (!chatsToLoad.isEmpty()) {
                        storage.getChatsInternal(TextUtils.join(",", chatsToLoad), chats);
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                } finally {
                    if (cursor != null) {
                        cursor.dispose();
                    }
                }

                AndroidUtilities.runOnUIThread(() -> {
                    links.clear();
                    links.addAll(result);
                    MessagesController.getInstance(currentAccount).putUsers(users, true);
                    MessagesController.getInstance(currentAccount).putChats(chats, true);

                    NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.quickRepliesUpdated);
                    loading = false;
                    load(false, forceReload);
                });
            });
        } else {
            TL_account.getBusinessChatLinks req = new TL_account.getBusinessChatLinks();
            ConnectionsManager.getInstance(currentAccount).sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
                if (res instanceof TL_account.businessChatLinks) {
                    TL_account.businessChatLinks businessChatLinks = (TL_account.businessChatLinks) res;

                    links.clear();
                    links.addAll(businessChatLinks.links);

                    MessagesController.getInstance(currentAccount).putUsers(businessChatLinks.users, false);
                    MessagesController.getInstance(currentAccount).putChats(businessChatLinks.chats, false);
                    MessagesStorage.getInstance(currentAccount).putUsersAndChats(businessChatLinks.users, businessChatLinks.chats, true, true);

                    NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.businessLinksUpdated);

                    saveToCache();
                } else {
                    FileLog.e(new RuntimeException("Unexpected response from server!"));
                }

                loading = false;
                loaded = true;
            }));
        }
    }

    public void createEmptyLink() {
        TL_account.createBusinessChatLink req = new TL_account.createBusinessChatLink();
        req.link = new TL_account.TL_inputBusinessChatLink();
        req.link.message = "";

        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
            if (res instanceof TL_account.TL_businessChatLink) {
                TL_account.TL_businessChatLink businessChatLink = (TL_account.TL_businessChatLink) res;
                links.add(businessChatLink);

                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.businessLinksUpdated);
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.businessLinkCreated, businessChatLink);

                saveToCache();
            }
        }));
    }

    public void deleteLinkUndoable(BaseFragment fragment, String slug) {
        TL_account.TL_businessChatLink link = findLink(slug);
        if (link != null) {
            int index = links.indexOf(link);
            links.remove(link);
            NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.businessLinksUpdated);

            BulletinFactory.of(fragment).createUndoBulletin(LocaleController.getString(R.string.BusinessLinkDeleted), true,
                    () -> {
                        links.add(index, link);
                        NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.businessLinksUpdated);
                    },
                    () -> {
                        TL_account.deleteBusinessChatLink req = new TL_account.deleteBusinessChatLink();
                        req.slug = slug;

                        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
                            if (res instanceof TLRPC.TL_boolTrue) {
                                if (links.contains(link)) {
                                    links.remove(link);
                                    NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.businessLinksUpdated);
                                }
                                saveToCache();
                            } else {
                                FileLog.e(new RuntimeException("Unexpected response from server!"));
                            }
                        }));
                    }).show();
        }
    }

    public void editLinkMessage(String slug, String message, ArrayList<TLRPC.MessageEntity> entities, Runnable onDone) {
        TL_account.TL_businessChatLink link = findLink(slug);
        if (link == null) {
            return;
        }

        TL_account.TL_inputBusinessChatLink inputLink = new TL_account.TL_inputBusinessChatLink();
        inputLink.message = message;
        inputLink.entities = entities;
        inputLink.title = link.title;

        editLink(link, inputLink, onDone);
    }

    public void editLinkMessageForEditor(String slug, String message, ArrayList<TLRPC.MessageEntity> entities, EditorMessageSaveCallback callback) {
        final long userId = UserConfig.getInstance(currentAccount).getClientUserId();
        final long session = app.nimarkogram.messenger.notifications.InAppNotifications.session(currentAccount);
        final TL_account.TL_businessChatLink link = findLink(slug);
        if (link == null || TextUtils.isEmpty(link.link) || message == null
                || !app.nimarkogram.messenger.notifications.InAppNotifications.isCurrent(currentAccount, userId, session)) {
            if (callback != null) callback.onComplete(false);
            return;
        }
        EditorMessageSaves saves = editorMessageSaves.get(link.link);
        if (saves == null) editorMessageSaves.put(link.link, saves = new EditorMessageSaves());
        final EditorMessageSave save = new EditorMessageSave(link.link, userId, session, saves, callback);
        final Runnable failed = () -> completeEditorMessageSave(save, null, null);
        try {
            TL_account.TL_inputBusinessChatLink inputLink = new TL_account.TL_inputBusinessChatLink();
            inputLink.message = message;
            inputLink.entities = entities == null ? new ArrayList<>() : new ArrayList<>(entities);
            inputLink.title = link.title;
            if (!inputLink.entities.isEmpty()) inputLink.flags |= 1;
            if (!TextUtils.isEmpty(inputLink.title)) inputLink.flags |= 2;
            final TL_account.editBusinessChatLink request = new TL_account.editBusinessChatLink();
            request.slug = save.slug;
            request.link = inputLink;
            if (!Utilities.stageQueue.postRunnable(() -> {
                if (save.completed.get()) return;
                if (!save.isCurrent(currentAccount)) {
                    AndroidUtilities.runOnUIThread(failed);
                    return;
                }
                try {
                    ConnectionsManager.getInstance(currentAccount).sendRequestSync(request,
                            new ConnectionsManager.RequestDelegateWithCancellation() {
                                @Override
                                public void run(TLObject response, TLRPC.TL_error error) {
                                    AndroidUtilities.runOnUIThread(() -> completeEditorMessageSave(save, response, error));
                                }

                                @Override
                                public void onCancelled() {
                                    AndroidUtilities.runOnUIThread(failed);
                                }
                            },
                            null, null, 0, ConnectionsManager.DEFAULT_DATACENTER_ID, ConnectionsManager.ConnectionTypeGeneric, true);
                } catch (Exception e) {
                    FileLog.e(e);
                    AndroidUtilities.runOnUIThread(failed);
                }
            })) failed.run();
        } catch (Exception e) {
            FileLog.e(e);
            failed.run();
        }
    }

    private void completeEditorMessageSave(EditorMessageSave save, TLObject response, TLRPC.TL_error error) {
        if (!save.completed.compareAndSet(false, true)) return;
        boolean success = false;
        TL_account.TL_businessChatLink current = null;
        try {
            if (save.isCurrent(currentAccount) && error == null && response instanceof TL_account.TL_businessChatLink) {
                current = findLink(save.slug);
                success = current != null;
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        final boolean publish = success && save.sequence > save.saves.savedSequence;
        if (publish) save.saves.savedSequence = save.sequence;
        if (--save.saves.pending == 0 && editorMessageSaves.get(save.slug) == save.saves) editorMessageSaves.remove(save.slug);
        try {
            if (publish) {
                int index = links.indexOf(current);
                if (index >= 0) {
                    links.set(index, (TL_account.TL_businessChatLink) response);
                    try {
                        NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.businessLinksUpdated);
                    } finally {
                        saveToCache(save.userId, save.session, true);
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (save.callback != null) save.callback.onComplete(success);
        }
    }
    public void editLinkTitle(String slug, String title) {
        TL_account.TL_businessChatLink link = findLink(slug);
        if (link == null) {
            return;
        }

        TL_account.TL_inputBusinessChatLink inputLink = new TL_account.TL_inputBusinessChatLink();
        inputLink.message = link.message;
        inputLink.entities = link.entities;
        inputLink.title = title;

        editLink(link, inputLink, null);
    }

    private void saveToCache() {
        saveToCache(0, 0, false);
    }

    private void saveToCache(long userId, long session, boolean checkOwner) {
        ArrayList<TL_account.TL_businessChatLink> linksCopy = new ArrayList<>(links);

        MessagesStorage storage = MessagesStorage.getInstance(currentAccount);
        storage.getStorageQueue().postRunnable(() -> {
            if (checkOwner && !app.nimarkogram.messenger.notifications.InAppNotifications.isCurrent(currentAccount, userId, session)) return;
            SQLitePreparedStatement state = null;
            try {
                SQLiteDatabase db = storage.getDatabase();
                db.executeFast("DELETE FROM business_links").stepThis().dispose();
                state = db.executeFast("REPLACE INTO business_links VALUES(?, ?)");
                for (int i = 0; i < linksCopy.size(); i++) {
                    TL_account.TL_businessChatLink link = linksCopy.get(i);
                    NativeByteBuffer data = new NativeByteBuffer(link.getObjectSize());
                    try {
                    link.serializeToStream(data);
                    state.requery();
                    state.bindByteBuffer(1, data);
                    state.bindInteger(2, i);
                    state.step();
                    } finally {
                        data.reuse();
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                if (state != null) {
                    state.dispose();
                }
            }
        });
    }

    private void editLink(TL_account.TL_businessChatLink link, TL_account.TL_inputBusinessChatLink inputLink, Runnable onDone) {
        TL_account.editBusinessChatLink req = new TL_account.editBusinessChatLink();
        req.slug = link.link;

        if (!inputLink.entities.isEmpty()) {
            inputLink.flags |= 1;
        }
        if (!TextUtils.isEmpty(inputLink.title)) {
            inputLink.flags |= 2;
        }
        req.link = inputLink;

        ConnectionsManager.getInstance(currentAccount).sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
            if (res instanceof TL_account.TL_businessChatLink) {
                TL_account.TL_businessChatLink updatedLink = (TL_account.TL_businessChatLink) res;
                int index = links.indexOf(link);
                if (index != -1) {
                    links.set(index, updatedLink);
                    NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.businessLinksUpdated);

                    if (onDone != null) {
                        onDone.run();
                    }

                    saveToCache();
                }
            }
        }));
    }

    public TL_account.TL_businessChatLink findLink(String slug) {
        for (int i = 0; i < links.size(); i++) {
            TL_account.TL_businessChatLink chatLink = links.get(i);
            if (TextUtils.equals(chatLink.link, slug) ||
                    TextUtils.equals(chatLink.link, "https://" + slug) ||
                    TextUtils.equals(chatLink.link, "https://t.me/m/" + slug) ||
                    TextUtils.equals(chatLink.link, "tg://message?slug=" + slug)) {
                return chatLink;
            }
        }

        return null;
    }
}
