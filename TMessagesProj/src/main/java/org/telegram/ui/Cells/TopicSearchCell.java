/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Cells;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeBind;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.Forum.ForumUtilities;
import org.telegram.ui.Components.LayoutHelper;

public class TopicSearchCell extends FrameLayout implements Theme.Colorable {

    BackupImageView backupImageView;
    TextView textView;
    TLRPC.TL_forumTopic topic;
    public boolean drawDivider;

    private final int searchAccount;
    private final Theme.ResourcesProvider resourcesProvider;
    public TopicSearchCell(@NonNull Context context) {
        this(context, -1, null);
    }

    public TopicSearchCell(@NonNull Context context, int account, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.searchAccount = account;
        this.resourcesProvider = resourcesProvider;
        backupImageView = account < 0 ? new BackupImageView(context) : new BackupImageView(context) {
            private final Rect bounds = new Rect();
            private Drawable placeholder;

            @Override protected void onDraw(Canvas canvas) {
                AnimatedEmojiDrawable emoji = getAnimatedEmojiDrawable();
                if (emoji == null) {
                    super.onDraw(canvas);
                    return;
                }
                bounds.set(0, 0, getWidth(), getHeight());
                if (emoji.getImageReceiver() == null || !emoji.getImageReceiver().hasReadyImage()) {
                    if (placeholder == null) placeholder = Theme.getThemedDrawable(getContext(), R.drawable.msg_topics,
                            Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon, resourcesProvider));
                    placeholder.setBounds(bounds);
                    placeholder.draw(canvas);
                }
                emoji.drawForTopicSearch(canvas, bounds, this);
            }
        };
        if (account >= 0) backupImageView.getImageReceiver().setCurrentAccount(account);

        textView = new TextView(context);
        ThemeBind.textColor(textView, Theme.key_windowBackgroundWhiteBlackText);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        textView.setTypeface(AndroidUtilities.bold());

        if (LocaleController.isRTL) {
            addView(backupImageView, LayoutHelper.createFrame(30, 30, Gravity.CENTER_VERTICAL | Gravity.RIGHT, 12, 0, 12, 0));
            addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL | Gravity.RIGHT, 12, 0, 56, 0));
        } else {
            addView(backupImageView, LayoutHelper.createFrame(30, 30, Gravity.CENTER_VERTICAL, 12, 0, 12, 0));
            addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL, 56, 0, 12, 0));
        }
    }


    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(48), MeasureSpec.EXACTLY));
    }

    public void setTopic(TLRPC.TL_forumTopic topic) {
        setTopic(topic, topic.searchQuery);
    }

    public void setTopic(TLRPC.TL_forumTopic topic, String query) {
        this.topic = topic;
        if (TextUtils.isEmpty(query)) {
            textView.setText(AndroidUtilities.removeDiacritics(topic.title));
        } else {
            textView.setText(AndroidUtilities.highlightText(AndroidUtilities.removeDiacritics(topic.title), query, resourcesProvider));
        }
        if (searchAccount >= 0 && topic.id != 1 && topic.icon_emoji_id != 0) {
            AnimatedEmojiDrawable emoji = AnimatedEmojiDrawable.make(searchAccount, AnimatedEmojiDrawable.CACHE_TYPE_FORUM_TOPIC, topic.icon_emoji_id);
            emoji.setColorFilter(Theme.getAnimatedEmojiColorFilter(resourcesProvider));
            if (backupImageView.getAnimatedEmojiDrawable() != emoji) {
                backupImageView.setImageDrawable(null);
                backupImageView.setAnimatedEmojiDrawable(emoji);
            }
        } else {
            ForumUtilities.setTopicIcon(backupImageView, topic, false, false, resourcesProvider);
        }
        if (backupImageView != null && backupImageView.getImageReceiver() != null && backupImageView.getImageReceiver().getDrawable() instanceof ForumUtilities.GeneralTopicDrawable) {
            ((ForumUtilities.GeneralTopicDrawable) backupImageView.getImageReceiver().getDrawable()).setColor(Theme.getColor(Theme.key_chats_archiveBackground));
        }
    }

    @Override
    public void updateColors() {
        try {
            if (backupImageView != null && backupImageView.getImageReceiver() != null && backupImageView.getImageReceiver().getDrawable() instanceof ForumUtilities.GeneralTopicDrawable) {
                ((ForumUtilities.GeneralTopicDrawable) backupImageView.getImageReceiver().getDrawable()).setColor(Theme.getColor(Theme.key_chats_archiveBackground));
                backupImageView.invalidate();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }
    public TLRPC.TL_forumTopic getTopic() {
        return topic;
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (drawDivider) {
            int left = AndroidUtilities.dp(56);
            if (LocaleController.isRTL) {
                canvas.drawLine(0, getMeasuredHeight() - 1, getMeasuredWidth() - left, getMeasuredHeight() - 1, Theme.dividerPaint);
            } else {
                canvas.drawLine(left, getMeasuredHeight() - 1, getMeasuredWidth(), getMeasuredHeight() - 1, Theme.dividerPaint);
            }
        }
    }
}
