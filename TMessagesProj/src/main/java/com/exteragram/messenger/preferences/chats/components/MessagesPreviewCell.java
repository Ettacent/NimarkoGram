package com.exteragram.messenger.preferences.chats.components;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.view.MotionEvent;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Components.BackgroundGradientDrawable;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.MotionBackgroundDrawable;

@SuppressLint("ViewConstructor")
public class MessagesPreviewCell extends LinearLayout {
    private final ChatMessageCell[] cells;
    private final MessageObject[] messageObjects;
    private final Drawable shadowDrawable;
    private final int type;

    public MessagesPreviewCell(Context context, INavigationLayout navigationLayout) {
        this(context, navigationLayout, 0);
    }

    public MessagesPreviewCell(Context context, INavigationLayout navigationLayout, int type) {
        super(context);
        this.type = type;
        int count = type == 1 ? 1 : 2;
        cells = new ChatMessageCell[count];
        messageObjects = new MessageObject[count];

        setWillNotDraw(false);
        setOrientation(VERTICAL);
        setPadding(0, AndroidUtilities.dp(11), 0, AndroidUtilities.dp(11));
        shadowDrawable = Theme.getThemedDrawable(
                context, R.drawable.greydivider_bottom,
                Theme.getColor(Theme.key_windowBackgroundGrayShadow));

        for (int i = 0; i < count; i++) {
            messageObjects[i] = createPlaceholder(i, type == 1 || i == 1);
            ChatMessageCell cell = new ChatMessageCell(context, UserConfig.selectedAccount);
            cell.setDelegate(new ChatMessageCell.ChatMessageCellDelegate() { });
            cell.isChat = false;
            cell.setFullyDraw(true);
            cells[i] = cell;
            addView(cell, LayoutHelper.createLinear(
                    LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }
        refreshMessages();
    }

    private static MessageObject createPlaceholder(int index, boolean incoming) {
        int account = UserConfig.selectedAccount;
        int now = (int) (System.currentTimeMillis() / 1000L);
        TLRPC.TL_message message = new TLRPC.TL_message();
        message.id = index + 1;
        message.date = now - 60 + index;
        message.dialog_id = incoming ? -1L : 1L;
        message.flags = 33027;
        message.message = incoming
                ? "Message preview"
                : "NimarkoGram";
        message.media = new TLRPC.TL_messageMediaEmpty();
        message.out = !incoming;
        message.from_id = new TLRPC.TL_peerUser();
        message.from_id.user_id = incoming
                ? 1L
                : UserConfig.getInstance(account).getClientUserId();
        message.peer_id = new TLRPC.TL_peerUser();
        message.peer_id.user_id = incoming ? 1L : 0L;

        MessageObject object = new MessageObject(account, message, true, false);
        object.forceAvatar = incoming;
        object.customReplyName = incoming ? "Telegram" : "NimarkoGram";
        object.eventId = index + 1L;
        object.resetLayout();
        return object;
    }

    public void refreshMessages() {
        for (int i = 0; i < cells.length; i++) {
            MessageObject object = messageObjects[i];
            if (object == null) continue;
            object.forceUpdate = true;
            cells[i].setMessageObject(object, null, false, false, false);
            cells[i].requestLayout();
            cells[i].invalidate();
        }
        requestLayout();
        super.invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Drawable wallpaper = Theme.getCachedWallpaperNonBlocking();
        if (wallpaper == null) {
            canvas.drawColor(Theme.getColor(Theme.key_chat_wallpaper));
        } else if (wallpaper instanceof BitmapDrawable) {
            BitmapDrawable bitmap = (BitmapDrawable) wallpaper;
            int save = canvas.save();
            if (bitmap.getTileModeX() == Shader.TileMode.REPEAT) {
                float scale = 2.0f / AndroidUtilities.density;
                canvas.scale(scale, scale);
                wallpaper.setBounds(0, 0,
                        (int) Math.ceil(getMeasuredWidth() / scale),
                        (int) Math.ceil(getMeasuredHeight() / scale));
            } else if (wallpaper.getIntrinsicWidth() > 0 && wallpaper.getIntrinsicHeight() > 0) {
                float scale = Math.max(
                        getMeasuredWidth() / (float) wallpaper.getIntrinsicWidth(),
                        getMeasuredHeight() / (float) wallpaper.getIntrinsicHeight());
                int width = (int) Math.ceil(wallpaper.getIntrinsicWidth() * scale);
                int height = (int) Math.ceil(wallpaper.getIntrinsicHeight() * scale);
                int left = (getMeasuredWidth() - width) / 2;
                int top = (getMeasuredHeight() - height) / 2;
                canvas.clipRect(0, 0, getMeasuredWidth(), getMeasuredHeight());
                wallpaper.setBounds(left, top, left + width, top + height);
            }
            wallpaper.draw(canvas);
            canvas.restoreToCount(save);
        } else if (wallpaper instanceof ColorDrawable
                || wallpaper instanceof GradientDrawable
                || wallpaper instanceof MotionBackgroundDrawable
                || wallpaper instanceof BackgroundGradientDrawable) {
            wallpaper.setBounds(0, 0, getMeasuredWidth(), getMeasuredHeight());
            wallpaper.draw(canvas);
        }
        shadowDrawable.setBounds(0, 0, getMeasuredWidth(), getMeasuredHeight());
        shadowDrawable.draw(canvas);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        return type == 1 ? false : super.onInterceptTouchEvent(event);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        return type == 1 ? false : super.dispatchTouchEvent(event);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        return type == 1 ? false : super.onTouchEvent(event);
    }
}
