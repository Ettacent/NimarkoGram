/**
 * This file is part of NimarkoGram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * NimarkoGram modifications:
 * Copyright Ettacent, 2026.
 *
 * Portions derived from Cherrygram:
 * Copyright github.com/arsLan4k1390, 2022-2026.
 */

package app.nimarkogram.messenger.preferences.helpers;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.LayoutHelper;

import app.nimarkogram.messenger.NimarkoConfig;
import app.nimarkogram.messenger.preferences.cells.StickerSliderCell;

public class NimarkoAlertDialogSwitchers {

    public static void showMessageSize(BaseFragment fragment) {
        if (fragment.getParentActivity() == null) {
            return;
        }
        Context context = fragment.getParentActivity();
        Theme.ResourcesProvider resourcesProvider = fragment.getResourceProvider();

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, AndroidUtilities.dp(2), 0, AndroidUtilities.dp(14));

        TextCheckCell largerVoiceMessagesLayout = new TextCheckCell(context, 23, false, resourcesProvider);
        largerVoiceMessagesLayout.setBackground(Theme.AdaptiveRipple.filledRect(
                Theme.getColor(Theme.key_dialogBackgroundGray, resourcesProvider),
                16));
        largerVoiceMessagesLayout.setTextAndCheck(getString(R.string.NM_MZ_LargerVoiceMessages), NimarkoConfig.largerVoiceMessagesLayout, true);
        largerVoiceMessagesLayout.setOnClickListener(v -> {
            NimarkoConfig.toggleLargerVoiceMessagesLayout();
            largerVoiceMessagesLayout.setChecked(NimarkoConfig.largerVoiceMessagesLayout);
        });
        content.addView(largerVoiceMessagesLayout, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT,
                56,
                Gravity.FILL_HORIZONTAL,
                12,
                2,
                12,
                8));

        StickerSliderCell mediaSize = new StickerSliderCell(context, resourcesProvider)
                .setTitle(getString(R.string.NM_MZ_MediaAmplifier))
                .setHint(getString(R.string.NM_MZ_MediaAmplifier_Hint))
                .setContract(new StickerSliderCell.TGSLContract() {
                    @Override
                    public void setValue(int value) {
                        NimarkoConfig.setSlider_mediaAmplifier(value);
                    }

                    @Override
                    public int getPreferenceValue() {
                        return NimarkoConfig.slider_mediaAmplifier;
                    }

                    @Override
                    public int getMin() {
                        return 50;
                    }

                    @Override
                    public int getMax() {
                        return 100;
                    }

                    @Override
                    public CharSequence getAccessibilityLabel() {
                        return getString(R.string.NM_MZ_MediaAmplifier);
                    }

                    @Override
                    public CharSequence formatValue(int value) {
                        return value + "%";
                    }
                });
        content.addView(mediaSize, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT,
                Gravity.FILL_HORIZONTAL,
                12,
                0,
                12,
                8));

        StickerSliderCell gifSize = new StickerSliderCell(context, resourcesProvider)
                .setTitle(getString(R.string.AccDescrGIFs))
                .setContract(new StickerSliderCell.TGSLContract() {
                    @Override
                    public void setValue(int value) {
                        NimarkoConfig.setSlider_gifsAmplifier(value);
                    }

                    @Override
                    public int getPreferenceValue() {
                        return NimarkoConfig.slider_gifsAmplifier;
                    }

                    @Override
                    public int getMin() {
                        return 50;
                    }

                    @Override
                    public int getMax() {
                        return 100;
                    }

                    @Override
                    public CharSequence getAccessibilityLabel() {
                        return getString(R.string.AccDescrGIFs);
                    }

                    @Override
                    public CharSequence formatValue(int value) {
                        return value + "%";
                    }
                });
        content.addView(gifSize, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT,
                Gravity.FILL_HORIZONTAL,
                12,
                0,
                12,
                0));

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(false);
        scrollView.setClipToPadding(false);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.addView(content, LayoutHelper.createScroll(
                LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT,
                Gravity.TOP));

        BottomSheet.Builder builder = new BottomSheet.Builder(context, false, resourcesProvider)
                .setTitle(getString(R.string.CP_Messages_Size), true)
                .setTitleMultipleLines(true)
                .setApplyTopPadding(false)
                .setApplyBottomPadding(false)
                .setCustomView(scrollView);
        fragment.showDialog(builder.create());
    }

    public static void showRecentEmojisAndStickers(BaseFragment fragment) {
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        Context context = fragment.getParentActivity();
        Theme.ResourcesProvider resourcesProvider = fragment.getResourceProvider();

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, AndroidUtilities.dp(2), 0, AndroidUtilities.dp(14));

        StickerSliderCell emojiCount = new StickerSliderCell(context, resourcesProvider)
                .setTitle(getString(R.string.Emoji))
                .setContract(new StickerSliderCell.TGSLContract() {
                    @Override
                    public void setValue(int value) {
                        NimarkoConfig.setRecentEmojisAmplifier(value);
                    }

                    @Override
                    public int getPreferenceValue() {
                        return NimarkoConfig.recentEmojisAmplifier;
                    }

                    @Override
                    public int getMin() {
                        return 25;
                    }

                    @Override
                    public int getMax() {
                        return 80;
                    }

                    @Override
                    public CharSequence getAccessibilityLabel() {
                        return getString(R.string.Emoji);
                    }
                });
        content.addView(emojiCount, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT,
                Gravity.FILL_HORIZONTAL,
                12,
                2,
                12,
                8));

        StickerSliderCell stickerCount = new StickerSliderCell(context, resourcesProvider)
                .setTitle(getString(R.string.AccDescrStickers))
                .setContract(new StickerSliderCell.TGSLContract() {
                    @Override
                    public void setValue(int value) {
                        NimarkoConfig.setRecentStickersAmplifier(value);
                    }

                    @Override
                    public int getPreferenceValue() {
                        return NimarkoConfig.recentStickersAmplifier;
                    }

                    @Override
                    public int getMin() {
                        return 10;
                    }

                    @Override
                    public int getMax() {
                        return 50;
                    }

                    @Override
                    public CharSequence getAccessibilityLabel() {
                        return getString(R.string.AccDescrStickers);
                    }
                });
        content.addView(stickerCount, LayoutHelper.createLinear(
                LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT,
                Gravity.FILL_HORIZONTAL,
                12,
                0,
                12,
                0));

        ScrollView scrollView = new ScrollView(context);
        scrollView.setFillViewport(false);
        scrollView.setClipToPadding(false);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.addView(content, LayoutHelper.createScroll(
                LayoutHelper.MATCH_PARENT,
                LayoutHelper.WRAP_CONTENT,
                Gravity.TOP));

        BottomSheet sheet = new BottomSheet.Builder(context, false, resourcesProvider)
                .setTitle(getString(R.string.NM_CH_RecentEmojisStickers), true)
                .setTitleMultipleLines(true)
                .setApplyTopPadding(false)
                .setApplyBottomPadding(false)
                .setCustomView(scrollView)
                .create();
        sheet.setOnDismissListener(dialog -> {
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.emojiLoaded);
            NotificationCenter.getInstance(fragment.getCurrentAccount()).postNotificationName(
                    NotificationCenter.recentDocumentsDidLoad,
                    false,
                    MediaDataController.TYPE_IMAGE);
        });
        fragment.showDialog(sheet);
    }

}
