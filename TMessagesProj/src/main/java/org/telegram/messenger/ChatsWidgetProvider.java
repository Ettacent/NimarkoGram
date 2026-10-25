/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.messenger;

import android.app.PendingIntent;
import android.appwidget.AppWidgetManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.RemoteViews;

import org.telegram.ui.LaunchActivity;

public class ChatsWidgetProvider extends AsyncWidgetProvider {

    private static int getCellsForSize(int size) {
        return Math.max(1, Math.min(4, (int) ((size - 1L) / 72)));
    }

    public static void updateWidget(Context context, AppWidgetManager appWidgetManager, int appWidgetId) {
        WidgetUpdateWorker.enqueue(context, appWidgetId, false);
    }

    static void renderWidget(Context context, AppWidgetManager appWidgetManager, int appWidgetId,
                             int dialogCount, int account, long uid, boolean deleted) {
        Bundle options = appWidgetManager.getAppWidgetOptions(appWidgetId);
        int rows = getCellsForSize(options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT));
        int count = dialogCount;
        int id;
        if (deleted || rows == 1 || count <= 1) {
                id = R.layout.shortcut_widget_layout_1;
        } else if (rows == 2 || count <= 2) {
                id = R.layout.shortcut_widget_layout_2;
        } else if (rows == 3 || count <= 3) {
                id = R.layout.shortcut_widget_layout_3;
            } else {
                id = R.layout.shortcut_widget_layout_4;
            }
        Intent intent2 = new Intent(context, ChatsWidgetService.class);
        intent2.putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId);
        intent2.putExtra(WidgetUpdateWorker.ACCOUNT, account);
        intent2.putExtra(WidgetUpdateWorker.USER, uid);
        intent2.putExtra(WidgetUpdateWorker.DELETED, deleted);
        intent2.setData(Uri.parse(intent2.toUri(Intent.URI_INTENT_SCHEME)));
        RemoteViews rv = new RemoteViews(context.getPackageName(), id);
        rv.setRemoteAdapter(appWidgetId, R.id.list_view, intent2);
        rv.setEmptyView(R.id.list_view, R.id.empty_view);

        Intent intent = new Intent(ApplicationLoader.applicationContext, LaunchActivity.class);
        intent.setAction("com.tmessages.openchat" + Math.random() + Integer.MAX_VALUE);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        PendingIntent contentIntent = PendingIntent.getActivity(ApplicationLoader.applicationContext, 0, intent, PendingIntent.FLAG_MUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        rv.setPendingIntentTemplate(R.id.list_view, contentIntent);
        appWidgetManager.updateAppWidget(appWidgetId, rv);
        appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.list_view);
    }
}
