/* Copyright (C) 2026 Ettacent */

/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.messenger;

import android.appwidget.AppWidgetManager;
import android.appwidget.AppWidgetProvider;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicInteger;

public abstract class AsyncWidgetProvider extends AppWidgetProvider {
    private ArrayList<ListenableFuture<?>> enqueues;

    @Override
    public void onReceive(Context context, Intent intent) {
        ArrayList<ListenableFuture<?>> submitted = new ArrayList<>();
        enqueues = submitted;
        try {
            super.onReceive(context, intent);
        } finally {
            enqueues = null;
            if (!submitted.isEmpty()) {
                PendingResult result = goAsync();
                if (result != null) {
                    AtomicInteger remaining = new AtomicInteger(submitted.size());
                    for (ListenableFuture<?> future : submitted) {
                        future.addListener(() -> {
                            if (remaining.decrementAndGet() == 0) {
                                result.finish();
                            }
                        }, Runnable::run);
                    }
                }
            }
        }
    }

    private void enqueue(Context context, int widgetId, boolean deleted) {
        ListenableFuture<?> future = WidgetUpdateWorker.enqueue(context, widgetId, deleted);
        if (enqueues != null) {
            enqueues.add(future);
        }
    }

    @Override
    public void onUpdate(Context context, AppWidgetManager manager, int[] ids) {
        for (int id : ids) {
            enqueue(context, id, false);
        }
    }

    @Override
    public void onAppWidgetOptionsChanged(Context context, AppWidgetManager manager, int id, Bundle options) {
        enqueue(context, id, false);
    }

    @Override
    public void onDeleted(Context context, int[] ids) {
        for (int id : ids) {
            enqueue(context, id, true);
        }
    }
}
