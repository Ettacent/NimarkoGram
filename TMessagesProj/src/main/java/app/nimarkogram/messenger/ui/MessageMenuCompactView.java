 
package app.nimarkogram.messenger.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import java.util.ArrayList;
import java.util.List;

import app.nimarkogram.messenger.NimarkoConfig;
import app.nimarkogram.messenger.NimarkoMessageMenuInjector;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ScaleStateListAnimator;

@SuppressLint("ViewConstructor")
public class MessageMenuCompactView extends LinearLayout {

    private static final int MAX_ACTIONS = 4;
    private static final int MIN_PANEL_WIDTH_DP = 224;
    private static final int TARGET_PANEL_WIDTH_DP = 256;
    private static final int ACTION_HEIGHT_DP = 60;

    public LinearLayout linearLayout;

    private final Theme.ResourcesProvider resourcesProvider;
    private View initialFocusView;

    public MessageMenuCompactView(
            ChatActivity chatActivity, MessageObject messageObject, int optionsSize,
            boolean allowReply, boolean allowEdit, boolean allowForward,
            boolean allowCopy, boolean allowCopyPhoto, boolean allowCopyLink,
            boolean allowDelete
    ) {
        this(chatActivity.getContext(), null);
    }

    private MessageMenuCompactView(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        linearLayout = this;

        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER);
        setPadding(
                AndroidUtilities.dp(4),
                AndroidUtilities.dp(4),
                AndroidUtilities.dp(4),
                AndroidUtilities.dp(4)
        );
        int availableWidth = getResources().getDisplayMetrics().widthPixels - AndroidUtilities.dp(40);
        int adaptiveWidth = Math.min(
                AndroidUtilities.dp(TARGET_PANEL_WIDTH_DP),
                availableWidth
        );
        setMinimumWidth(Math.max(
                AndroidUtilities.dp(MIN_PANEL_WIDTH_DP),
                adaptiveWidth
        ));
        setMinimumHeight(AndroidUtilities.dp(ACTION_HEIGHT_DP + 8));

        int foreground = Theme.getColor(Theme.key_actionBarDefaultSubmenuItem, resourcesProvider);
        float blend = Theme.isCurrentThemeDark() ? 0.10f : 0.055f;
        setBackground(Theme.createRoundRectDrawable(
                AndroidUtilities.dp(12),
                Theme.multAlpha(foreground, blend)
        ));
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    public static boolean allowCompactStyle() {
        return NimarkoConfig.msgMenuItemsCompactView;
    }

    public static boolean allowCompactStyle(long dialogId) {
        return NimarkoConfig.isCompactForChat(dialogId);
    }

    public static boolean install(
            ActionBarPopupWindow.ActionBarPopupWindowLayout popupLayout,
            long dialogId,
            ActionBarMenuSubItem[] rows,
            List<Integer> options,
            Theme.ResourcesProvider resourcesProvider
    ) {
        if (popupLayout == null || rows == null || options == null || !allowCompactStyle(dialogId)) {
            return false;
        }
        for (int i = 0; i < popupLayout.getItemsCount(); i++) {
            if (popupLayout.getItemAt(i) instanceof MessageMenuCompactView) {
                return true;
            }
        }

        ArrayList<Action> available = new ArrayList<>();
        int count = Math.min(rows.length, options.size());
        for (int i = 0; i < count; i++) {
            ActionBarMenuSubItem row = rows[i];
            Integer option = options.get(i);
            if (row == null || option == null || !isUsable(row)) {
                continue;
            }
            available.add(new Action(option, row));
        }

        ArrayList<Action> selected = new ArrayList<>(MAX_ACTIONS);

        if (!addFirst(selected, available, ChatActivity.OPTION_REPLY)) {
            addFirst(selected, available, ChatActivity.OPTION_EDIT);
        }
        addFirst(
                selected,
                available,
                ChatActivity.OPTION_COPY,
                ChatActivity.OPTION_COPY_LINK,
                NimarkoMessageMenuInjector.OPTION_COPY_PHOTO,
                NimarkoMessageMenuInjector.OPTION_COPY_PHOTO_AS_STICKER,
                ChatActivity.OPTION_SAVE_TO_GALLERY,
                ChatActivity.OPTION_SAVE_TO_GALLERY2,
                ChatActivity.OPTION_SAVE_TO_DOWNLOADS_OR_MUSIC
        );
        addFirst(
                selected,
                available,
                ChatActivity.OPTION_FORWARD,
                NimarkoMessageMenuInjector.OPTION_FORWARD_WO_AUTHOR,
                ChatActivity.OPTION_SHARE
        );
        addFirst(selected, available, ChatActivity.OPTION_DELETE);

        addFirst(selected, available, ChatActivity.OPTION_EDIT);
        addFirst(selected, available, ChatActivity.OPTION_SHARE);
        addFirst(selected, available, ChatActivity.OPTION_SAVE_TO_GALLERY, ChatActivity.OPTION_SAVE_TO_GALLERY2);

        if (selected.size() < 2) {
            return false;
        }

        MessageMenuCompactView panel = new MessageMenuCompactView(
                popupLayout.getContext(),
                resourcesProvider
        );
        for (Action action : selected) {
            panel.addAction(action);
        }

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT
        );
        int margin = AndroidUtilities.dp(4);
        params.setMargins(margin, margin, margin, margin);
        
        popupLayout.addView(panel, params);
        for (Action action : selected) {
            ViewParent parent = action.source.getParent();
            if (parent instanceof ViewGroup) {
                ((ViewGroup) parent).removeView(action.source);
            }
        }
        updateRemainingRowCorners(rows);
        popupLayout.requestLayout();
        return true;
    }

    public static View findInitialFocusTarget(
            ActionBarPopupWindow.ActionBarPopupWindowLayout popupLayout,
            ActionBarMenuSubItem[] fallbackRows
    ) {
        MessageMenuCompactView compactView = null;
        if (popupLayout != null) {
            for (int i = 0; i < popupLayout.getItemsCount(); i++) {
                View child = popupLayout.getItemAt(i);
                if (child.getVisibility() != VISIBLE) {
                    continue;
                }
                if (child instanceof ActionBarMenuSubItem && child.isFocusable()) {
                    return child;
                }
                if (child instanceof MessageMenuCompactView) {
                    compactView = (MessageMenuCompactView) child;
                }
            }
        }
        if (compactView != null && compactView.initialFocusView != null) {
            return compactView.initialFocusView;
        }
        if (fallbackRows != null) {
            for (ActionBarMenuSubItem row : fallbackRows) {
                if (row != null && row.getParent() != null && row.getVisibility() == VISIBLE) {
                    return row;
                }
            }
        }
        return null;
    }

    private static boolean isUsable(ActionBarMenuSubItem row) {
        if (row.getParent() == null || row.getVisibility() != VISIBLE || !row.isEnabled()) {
            return false;
        }
        if (row.getTextView() == null || TextUtils.isEmpty(row.getTextView().getText())) {
            return false;
        }
        
        return row.subtextView == null
                || row.subtextView.getVisibility() != VISIBLE
                || TextUtils.isEmpty(row.subtextView.getText());
    }

    private static void updateRemainingRowCorners(ActionBarMenuSubItem[] rows) {
        ActionBarMenuSubItem first = null;
        ActionBarMenuSubItem last = null;
        for (ActionBarMenuSubItem row : rows) {
            if (row == null || row.getParent() == null || row.getVisibility() != VISIBLE) {
                continue;
            }
            if (first == null) {
                first = row;
            }
            last = row;
        }
        if (first == null) {
            return;
        }
        for (ActionBarMenuSubItem row : rows) {
            if (row != null && row.getParent() != null && row.getVisibility() == VISIBLE) {
                row.updateSelectorBackground(row == first, row == last);
            }
        }
    }

    private static boolean addFirst(
            ArrayList<Action> selected,
            ArrayList<Action> available,
            int... optionIds
    ) {
        if (selected.size() >= MAX_ACTIONS) {
            return false;
        }
        for (int optionId : optionIds) {
            for (Action action : available) {
                if (action.option == optionId && !selected.contains(action)) {
                    selected.add(action);
                    return true;
                }
            }
        }
        return false;
    }

    private void addAction(Action action) {
        ActionBarMenuSubItem source = action.source;
        CharSequence title = source.getTextView().getText();
        int normalText = source.getTextView().getCurrentTextColor();
        int normalIcon = Theme.getColor(
                Theme.key_actionBarDefaultSubmenuItemIcon,
                resourcesProvider
        );
        int textColor = normalText;
        int iconColor = normalIcon;
        if (action.option == ChatActivity.OPTION_DELETE) {
            textColor = iconColor = Theme.getColor(Theme.key_text_RedRegular, resourcesProvider);
        }

        LinearLayout tile = new LinearLayout(getContext()) {
            @Override
            public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(info);
                info.setClassName("android.widget.Button");
            }
        };
        tile.setOrientation(VERTICAL);
        tile.setGravity(Gravity.CENTER);
        tile.setPadding(
                AndroidUtilities.dp(2),
                AndroidUtilities.dp(4),
                AndroidUtilities.dp(2),
                AndroidUtilities.dp(3)
        );
        tile.setMinimumWidth(AndroidUtilities.dp(52));
        tile.setMinimumHeight(AndroidUtilities.dp(ACTION_HEIGHT_DP));
        tile.setClickable(true);
        tile.setFocusable(true);
        tile.setEnabled(source.isEnabled());
        tile.setContentDescription(title);
        tile.setBackground(Theme.createRadSelectorDrawable(
                Theme.getColor(Theme.key_dialogButtonSelector, resourcesProvider),
                10,
                10
        ));
        ScaleStateListAnimator.apply(tile, 0.035f, 1.5f);

        FrameLayout iconContainer = new FrameLayout(getContext());
        ImageView iconView = new ImageView(getContext());
        iconView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        iconView.setImageDrawable(copyIcon(source));
        iconView.setColorFilter(new PorterDuffColorFilter(iconColor, PorterDuff.Mode.MULTIPLY));
        iconView.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        iconContainer.addView(iconView, LayoutHelper.createFrame(24, 24, Gravity.CENTER));

        if (source.isLongClickable()) {
            View dot = new View(getContext());
            dot.setBackground(Theme.createRoundRectDrawable(
                    AndroidUtilities.dp(2),
                    textColor
            ));
            iconContainer.addView(
                    dot,
                    LayoutHelper.createFrame(
                            4,
                            4,
                            (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.TOP,
                            LocaleController.isRTL ? 3 : 0,
                            1,
                            LocaleController.isRTL ? 0 : 3,
                            0
                    )
            );
            tile.setLongClickable(true);
            tile.setOnLongClickListener(v -> source.performLongClick());
        }

        TextView label = new TextView(getContext());
        label.setText(title);
        label.setTextColor(textColor);
        label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 11);
        label.setGravity(Gravity.CENTER);
        label.setSingleLine(true);
        label.setEllipsize(TextUtils.TruncateAt.END);
        label.setIncludeFontPadding(false);
        label.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);

        tile.addView(iconContainer, LayoutHelper.createLinear(32, 28, Gravity.CENTER_HORIZONTAL));
        tile.addView(label, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT,
                17,
                Gravity.CENTER_HORIZONTAL,
                2,
                1,
                2,
                0
        ));
        tile.setOnClickListener(v -> {
            if (source.isEnabled()) {
                source.performClick();
            }
        });

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                AndroidUtilities.dp(ACTION_HEIGHT_DP),
                1f
        );
        int spacing = AndroidUtilities.dp(2);
        params.setMargins(spacing, 0, spacing, 0);
        addView(tile, params);
        if (initialFocusView == null) {
            initialFocusView = tile;
        }
    }

    private Drawable copyIcon(ActionBarMenuSubItem source) {
        int iconRes = source.getIconResId();
        if (iconRes != 0) {
            Drawable drawable = ContextCompat.getDrawable(getContext(), iconRes);
            return drawable == null ? null : drawable.mutate();
        }
        Drawable sourceDrawable = source.getImageView() == null
                ? null
                : source.getImageView().getDrawable();
        if (sourceDrawable == null) {
            return null;
        }
        Drawable.ConstantState state = sourceDrawable.getConstantState();
        return state == null
                ? sourceDrawable
                : state.newDrawable(getResources()).mutate();
    }

    private static final class Action {
        final int option;
        final ActionBarMenuSubItem source;

        Action(int option, ActionBarMenuSubItem source) {
            this.option = option;
            this.source = source;
        }
    }

}
