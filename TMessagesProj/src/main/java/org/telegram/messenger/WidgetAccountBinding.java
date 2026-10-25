/* Copyright (C) 2026 Ettacent */

/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.messenger;

import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Looper;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class WidgetAccountBinding {
    private final Context context;
    private final Intent intent;
    private final int widgetId;
    private SharedPreferences preferences;
    private AccountInstance account;
    private int slot;
    private long uid;
    private boolean unavailable;
    private volatile boolean closed;

    WidgetAccountBinding(Context context, Intent intent) {
        this.context = context;
        this.intent = intent;
        widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);
    }

    void initialize() {
        preferences = context.getSharedPreferences("shortcut_widget", Context.MODE_PRIVATE);
        int saved = preferences.getInt("account" + widgetId, -1);
        slot = intent.getIntExtra(WidgetUpdateWorker.ACCOUNT, saved);
        unavailable = intent.getBooleanExtra(WidgetUpdateWorker.DELETED, false);
        if (slot >= 0 && slot < UserConfig.MAX_ACCOUNT_COUNT && slot == saved) {
            account = AccountInstance.getInstance(slot);
            uid = intent.getLongExtra(WidgetUpdateWorker.USER, account.getUserConfig().getClientUserId());
        } else {
            unavailable = true;
        }
    }

    AccountInstance account() {
        return account;
    }

    boolean active() {
        return !closed && !unavailable && account != null && uid > 0
                && account.getUserConfig().isClientActivated()
                && account.getUserConfig().getClientUserId() == uid
                && preferences.getInt("account" + widgetId, -1) == slot
                && !preferences.getBoolean("deleted" + widgetId, false);
    }

    boolean commitOnUiThread(Runnable operation) {
        CountDownLatch done = new CountDownLatch(1);
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicBoolean applied = new AtomicBoolean();
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        Runnable commit = () -> {
            try {
                if (!cancelled.get() && active()) {
                    operation.run();
                    applied.set(active());
                }
            } catch (RuntimeException e) {
                failure.set(e);
            } finally {
                done.countDown();
            }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) {
            commit.run();
        } else {
            AndroidUtilities.runOnUIThread(commit);
            try {
                done.await();
            } catch (InterruptedException e) {
                cancelled.set(true);
                Thread.currentThread().interrupt();
                return false;
            }
        }
        if (failure.get() != null) {
            throw failure.get();
        }
        return applied.get();
    }

    void close() {
        closed = true;
    }
}
