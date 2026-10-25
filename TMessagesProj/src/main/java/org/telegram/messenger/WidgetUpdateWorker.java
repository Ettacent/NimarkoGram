/* Copyright (C) 2026 Ettacent */

/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.messenger;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProviderInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.concurrent.futures.CallbackToFutureAdapter;
import androidx.work.Data;
import androidx.work.ExistingWorkPolicy;
import androidx.work.ListenableWorker;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;
import androidx.work.WorkerParameters;

import com.google.common.util.concurrent.ListenableFuture;

import org.telegram.ui.EditWidgetActivity;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public final class WidgetUpdateWorker extends ListenableWorker {
    static final String ACCOUNT = "widget_account";
    static final String USER = "widget_user";
    static final String DELETED = "widget_deleted";
    private static final String ID = "widget_id";
    private static final String REMOVE = "widget_remove";
    private static final ConcurrentHashMap<String, UUID> requests = new ConcurrentHashMap<>();

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean finished = new AtomicBoolean();
    private CallbackToFutureAdapter.Completer<Result> completion;
    private final int widgetId;
    private final String requestKey;
    private SharedPreferences preferences;
    private AppWidgetManager manager;

    public WidgetUpdateWorker(@NonNull Context context, @NonNull WorkerParameters parameters) {
        super(context, parameters);
        widgetId = getInputData().getInt(ID, AppWidgetManager.INVALID_APPWIDGET_ID);
        requestKey = workName(widgetId, getInputData().getBoolean(REMOVE, false));
    }

    private static String workName(int id, boolean remove) {
        return (remove ? "widget-remove-" : "widget-update-") + id;
    }

    static ListenableFuture<Boolean> enqueue(Context context, int id, boolean remove) {
        Context app = context.getApplicationContext();
        if (app == null) {
            app = context;
        }
        final Context application = app;
        return CallbackToFutureAdapter.getFuture(completer -> {
            Runnable submit = () -> {
                if (id <= AppWidgetManager.INVALID_APPWIDGET_ID) {
                    completer.set(true);
                    return;
                }
                OneTimeWorkRequest request = new OneTimeWorkRequest.Builder(WidgetUpdateWorker.class)
                        .setInputData(new Data.Builder().putInt(ID, id).putBoolean(REMOVE, remove).build())
                        .build();
                String key = workName(id, remove);
                requests.put(key, request.getId());
                try {
                    ListenableFuture<?> future = WorkManager.getInstance(application)
                            .enqueueUniqueWork(key, ExistingWorkPolicy.REPLACE, request).getResult();
                    future.addListener(() -> {
                        try {
                            future.get();
                            completer.set(true);
                        } catch (Exception e) {
                            requests.remove(key, request.getId());
                            FileLog.e(e);
                            completer.setException(e);
                        }
                    }, Runnable::run);
                } catch (Exception e) {
                    requests.remove(key, request.getId());
                    FileLog.e(e);
                    completer.setException(e);
                }
            };
            if (Looper.myLooper() == Looper.getMainLooper()) {
                submit.run();
            } else {
                AndroidUtilities.runOnUIThread(submit);
            }
            return "widget-enqueue-" + id;
        });
    }

    @NonNull
    @Override
    public ListenableFuture<Result> startWork() {
        return CallbackToFutureAdapter.getFuture(completer -> {
            completion = completer;
            completer.addCancellationListener(() -> {
                cancelled.set(true);
                requests.remove(requestKey, getId());
            }, Runnable::run);
            getBackgroundExecutor().execute(() -> {
                try {
                    preferences = getApplicationContext().getSharedPreferences("shortcut_widget", Context.MODE_PRIVATE);
                    preferences.getAll();
                    manager = AppWidgetManager.getInstance(getApplicationContext());
                    AndroidUtilities.runOnUIThread(this::begin);
                } catch (Exception e) {
                    fail(e);
                }
            });
            return "widget-update-" + widgetId;
        });
    }

    private boolean current() {
        UUID latest = requests.get(requestKey);
        return !cancelled.get() && !isStopped() && !finished.get()
                && (latest == null || latest.equals(getId()));
    }

    private int providerType() {
        AppWidgetProviderInfo info = manager.getAppWidgetInfo(widgetId);
        if (info == null) {
            return -1;
        }
        Context context = getApplicationContext();
        if (new ComponentName(context, ChatsWidgetProvider.class).equals(info.provider)) {
            return EditWidgetActivity.TYPE_CHATS;
        }
        if (new ComponentName(context, ContactsWidgetProvider.class).equals(info.provider)) {
            return EditWidgetActivity.TYPE_CONTACTS;
        }
        return -1;
    }

    private void begin() {
        try {
            if (!current() || widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
                complete(Result.success());
                return;
            }
            if (getInputData().getBoolean(REMOVE, false)) {
                remove();
                return;
            }
            int type = providerType();
            if (type == -1) {
                complete(Result.success());
                return;
            }
            ApplicationLoader.postInitApplication();
            if (!current()) {
                complete(Result.success());
                return;
            }
            int account = preferences.getInt("account" + widgetId, -1);
            boolean deleted = preferences.getBoolean("deleted" + widgetId, false);
            if (account == -1 && !deleted) {
                account = UserConfig.selectedAccount;
                preferences.edit().putInt("account" + widgetId, account).putInt("type" + widgetId, type).apply();
            } else if (preferences.getInt("type" + widgetId, type) != type) {
                preferences.edit().putInt("type" + widgetId, type).apply();
            }
            boolean valid = account >= 0 && account < UserConfig.MAX_ACCOUNT_COUNT;
            long uid = valid ? UserConfig.getInstance(account).getClientUserId() : 0;
            deleted |= !valid || uid == 0 || !UserConfig.getInstance(account).isClientActivated();
            if (deleted) {
                apply(type, account, uid, true, 0);
                return;
            }
            final int owner = account;
            final long ownerUid = uid;
            AccountInstance.getInstance(owner).getMessagesStorage().getWidgetDialogCountAsync(widgetId, type,
                    count -> {
                        try {
                            if (!current() || providerType() != type
                                    || preferences.getInt("account" + widgetId, -1) != owner
                                    || preferences.getBoolean("deleted" + widgetId, false)
                                    || UserConfig.getInstance(owner).getClientUserId() != ownerUid) {
                                complete(Result.success());
                            } else if (count < 0) {
                                complete(Result.retry());
                            } else {
                                apply(type, owner, ownerUid, false, count);
                            }
                        } catch (Exception e) {
                            fail(e);
                        }
                    });
        } catch (Exception e) {
            fail(e);
        }
    }

    private void apply(int type, int account, long uid, boolean deleted, int count) {
        if (!current() || providerType() != type) {
            complete(Result.success());
            return;
        }
        if (type == EditWidgetActivity.TYPE_CHATS) {
            ChatsWidgetProvider.renderWidget(getApplicationContext(), manager, widgetId, count, account, uid, deleted);
        } else {
            ContactsWidgetProvider.renderWidget(getApplicationContext(), manager, widgetId, count, account, uid, deleted);
        }
        complete(Result.success());
    }

    private void remove() {
        if (manager.getAppWidgetInfo(widgetId) != null) {
            complete(Result.success());
            return;
        }
        int account = preferences.getInt("account" + widgetId, -1);
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            removePreferences();
            complete(Result.success());
            return;
        }
        ApplicationLoader.postInitApplication();
        if (!current() || manager.getAppWidgetInfo(widgetId) != null) {
            complete(Result.success());
            return;
        }
        long uid = UserConfig.getInstance(account).getClientUserId();
        AccountInstance.getInstance(account).getMessagesStorage().clearWidgetDialogsAsync(widgetId, success -> {
            try {
                if (!current() || manager.getAppWidgetInfo(widgetId) != null
                        || preferences.getInt("account" + widgetId, -1) != account
                        || UserConfig.getInstance(account).getClientUserId() != uid) {
                    complete(Result.success());
                } else if (!success) {
                    complete(Result.retry());
                } else {
                    removePreferences();
                    complete(Result.success());
                }
            } catch (Exception e) {
                fail(e);
            }
        });
    }

    private void removePreferences() {
        preferences.edit().remove("account" + widgetId).remove("type" + widgetId).remove("deleted" + widgetId).apply();
    }

    private void complete(Result result) {
        if (finished.compareAndSet(false, true)) {
            requests.remove(requestKey, getId());
            completion.set(result);
        }
    }

    private void fail(Exception error) {
        FileLog.e(error);
        if (finished.compareAndSet(false, true)) {
            requests.remove(requestKey, getId());
            completion.setException(error);
        }
    }
}
