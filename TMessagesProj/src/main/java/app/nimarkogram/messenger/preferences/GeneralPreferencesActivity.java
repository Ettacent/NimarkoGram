/*
 * This file is part of NimarkoGram for Android.
 * Licensed under GNU GPL v2 or later. See LICENSE.
 * Copyright Ettacent, 2026.
 */

package app.nimarkogram.messenger.preferences;

import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.net.Uri;
import android.os.Build;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationsService;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stars;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Reactions.ReactionsLayoutInBubble;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.SelectAnimatedEmojiDialog;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import app.nimarkogram.messenger.NimarkoConfig;
import app.nimarkogram.messenger.utils.NimarkoConfigBackup;
import app.nimarkogram.messenger.preferences.helpers.PopupHelper;
import app.nimarkogram.messenger.preferences.helpers.SettingsHelper;

public class GeneralPreferencesActivity extends NimarkoUniversalPreferencesActivity {

    private static final int MAX_CONFIG_IMPORT_BYTES = 2 * 1024 * 1024;

    private final int springAnimationRow = 1;
    private final int actionbarCrossfadeRow = 2;
    private final int predictiveBackRow = 3;

    private final int silenceNonContactsRow = 4;
    private final int residentNotificationRow = 6;
    private final int notificationReactionsRow = 17;
    private final int notificationReactionEmojiRow = 18;

    private final int exportConfigRow = 19;
    private final int importConfigRow = 20;

    private final int hideStoriesRow = 7;
    private final int archiveStoriesRow = 8;

    private final int useSystemEmojiRow = 9;
    private final int useSystemFontsRow = 10;
    private final int tabledModeRow = 11;

    private final int downloadSpeedBoostRow = 12;
    private final int uploadSpeedBoostRow = 13;
    private final int slowNetworkMode = 14;

    private final int deletedGiftsRow = 15;
    private final int localPremiumEmojisRow = 16;

    private boolean uiAlive;
    private int uiGeneration;
    private Runnable premiumBulletinRunnable;

    @Override
    public boolean onFragmentCreate() {
        uiAlive = true;
        uiGeneration++;
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        uiAlive = false;
        uiGeneration++;
        if (premiumBulletinRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(premiumBulletinRunnable);
            premiumBulletinRunnable = null;
        }
        if (selectReactionDialog != null) {
            try {
                selectReactionDialog.dismiss();
            } catch (Throwable ignored) {
            }
            selectReactionDialog = null;
        }
        super.onFragmentDestroy();
    }

    @Override
    protected CharSequence getTitle() {
        return getString(R.string.AP_Header_General);
    }

    @Override
    public View createView(Context context) {
        setMD3(true);
        return super.createView(context);
    }

    @Override
    public void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(getString(R.string.LiteMode)));
        items.add(UItem.asButton(springAnimationRow, getString(R.string.EP_NavigationAnimation), getSpringValue()));
        if (NimarkoConfig.springAnimation == NimarkoConfig.SPRING_SPRING) {
            items.add(SettingsHelper.asSwitchCG(actionbarCrossfadeRow, getString(R.string.EP_NavigationAnimationCrossfading))
                    .setChecked(NimarkoConfig.actionbarCrossfade)
            );
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            items.add(SettingsHelper.asSwitchCG(predictiveBackRow, getString(R.string.NM_PredictiveBackAnimation))
                    .setChecked(NimarkoConfig.predictiveBack)
            );
        }
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.SettingsNotifications)));
        items.add(SettingsHelper.asSwitchCG(silenceNonContactsRow, getString(R.string.CP_SilenceNonContacts), getString(R.string.CP_SilenceNonContacts_Desc))
                .setChecked(NimarkoConfig.silenceNonContacts)
        );
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            items.add(SettingsHelper.asSwitchCG(residentNotificationRow, getString(R.string.NM_ResidentNotification), getString(R.string.NotificationsService))
                    .setChecked(NimarkoConfig.residentNotification)
            );
        }
        items.add(SettingsHelper.asSwitchCG(notificationReactionsRow,
                        getString(R.string.NM_NotificationReactions),
                        getString(R.string.NM_NotificationReactions_Desc))
                .setChecked(NimarkoConfig.notificationReactions)
        );
        if (NimarkoConfig.notificationReactions) {
            if (notificationReactionCell == null) {
                notificationReactionCell = new NotificationReactionCell(getContext());
            }
            notificationReactionCell.update(false);
            items.add(UItem.asCustom(notificationReactionEmojiRow, notificationReactionCell));
        }
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.FilterStories)));
        items.add(SettingsHelper.asSwitchCG(hideStoriesRow, getString(R.string.CP_HideStories), getString(R.string.CP_HideStories_Desc))
                .setChecked(NimarkoConfig.hideStories)
        );
        items.add(SettingsHelper.asTextDetail(archiveStoriesRow, R.drawable.msg_archive, getString(R.string.CP_ArchiveStories), getString(R.string.CP_ArchiveStories_Desc)));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.LocalMiscellaneousCache)));
        items.add(SettingsHelper.asSwitchCG(useSystemEmojiRow, getString(R.string.AP_SystemEmoji))
                .setChecked(NimarkoConfig.systemEmoji)
        );
        items.add(SettingsHelper.asSwitchCG(useSystemFontsRow, getString(R.string.AP_SystemFonts))
                .setChecked(NimarkoConfig.systemFonts)
        );
        items.add(UItem.asButton(tabledModeRow, getString(R.string.AP_Tablet_Mode), getTabletModeValue()));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.EP_Network)));
        items.add(UItem.asButton(downloadSpeedBoostRow, getString(R.string.EP_DownloadSpeedBoost), getDownloadSpeedBoostText()));
        items.add(SettingsHelper.asSwitchCG(uploadSpeedBoostRow, getString(R.string.NM_GE_UploadSpeedBoost))
                .setChecked(NimarkoConfig.uploadSpeedBoost)
        );
        items.add(SettingsHelper.asSwitchCG(slowNetworkMode, getString(R.string.EP_SlowNetworkMode))
                .setChecked(NimarkoConfig.slowNetworkMode)
        );
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.NM_GEN_MiscHeader)));
        items.add(SettingsHelper.asSwitchCG(deletedGiftsRow, getString(R.string.NM_GEN_DeletedGifts), getString(R.string.NM_GEN_DeletedGifts_Desc))
                .setChecked(NimarkoConfig.deletedGiftsInject)
        );
        items.add(SettingsHelper.asSwitchCG(localPremiumEmojisRow, getString(R.string.NM_GEN_LocalPremiumEmoji), getString(R.string.NM_GEN_LocalPremiumEmoji_Desc))
                .setChecked(NimarkoConfig.localPremiumEmojis)
        );
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(getString(R.string.NM_Config_Header)));
        items.add(UItem.asButton(exportConfigRow, getString(R.string.NM_Config_Export)));
        items.add(UItem.asButton(importConfigRow, getString(R.string.NM_Config_Import)));
        items.add(UItem.asShadow(getString(R.string.NM_Config_Info)));
    }

    @Override
    public void onClick(UItem item, View view, int position, float x, float y) {
        if (item.id == springAnimationRow) {
            ArrayList<String> configStringKeys = new ArrayList<>();
            ArrayList<Integer> configValues = new ArrayList<>();

            configStringKeys.add(getString(R.string.EP_NavigationAnimationSpring));
            configValues.add(NimarkoConfig.SPRING_SPRING);

            configStringKeys.add(getString(R.string.EP_NavigationAnimationBezier));
            configValues.add(NimarkoConfig.SPRING_CLASSIC);

            PopupHelper.show(configStringKeys, getString(R.string.EP_NavigationAnimation), configValues.indexOf(NimarkoConfig.springAnimation), getContext(), i -> {
                NimarkoConfig.setSpringAnimation(configValues.get(i));
                SettingsHelper.updateButtonValue(view, getSpringValue());

                listView.adapter.update(true);

                showRestartBulletin();
            });
        } else if (item.id == actionbarCrossfadeRow) {
            NimarkoConfig.toggleActionbarCrossfade();
            SettingsHelper.updateCheckState(view, NimarkoConfig.actionbarCrossfade);

            if (NimarkoConfig.actionbarCrossfade && NimarkoConfig.predictiveBack) {
                NimarkoConfig.togglePredictiveBack();
                listView.adapter.update(true);
            }

            showRestartBulletin();
        } else if (item.id == predictiveBackRow) {
            NimarkoConfig.togglePredictiveBack();
            SettingsHelper.updateCheckState(view, NimarkoConfig.predictiveBack);

            if (NimarkoConfig.predictiveBack && NimarkoConfig.actionbarCrossfade) {
                NimarkoConfig.toggleActionbarCrossfade();
                listView.adapter.update(true);
            }

            showRestartBulletin();
        } else if (item.id == silenceNonContactsRow) {
            NimarkoConfig.toggleSilenceNonContacts();
            SettingsHelper.updateCheckState(view, NimarkoConfig.silenceNonContacts);
        } else if (item.id == residentNotificationRow) {
            NimarkoConfig.toggleResidentNotification();
            SettingsHelper.updateCheckState(view, NimarkoConfig.residentNotification);

            ApplicationLoader.applicationContext.stopService(new Intent(ApplicationLoader.applicationContext, NotificationsService.class));
            ApplicationLoader.startPushService();

            showRestartBulletin();
        } else if (item.id == notificationReactionsRow) {
            NimarkoConfig.toggleNotificationReactions();
            SettingsHelper.updateCheckState(view, NimarkoConfig.notificationReactions);
            listView.adapter.update(true);
        } else if (item.id == notificationReactionEmojiRow) {
            showNotificationReactionDialog(view);
        } else if (item.id == hideStoriesRow) {
            NimarkoConfig.toggleHideStories();
            SettingsHelper.updateCheckState(view, NimarkoConfig.hideStories);

            showRestartBulletin();
        } else if (item.id == archiveStoriesRow) {
            showStoriesArchiveConfigurator();
        } else if (item.id == useSystemEmojiRow) {
            NimarkoConfig.toggleSystemEmoji();
            SettingsHelper.updateCheckState(view, NimarkoConfig.systemEmoji);

            showRestartBulletin();
        } else if (item.id == useSystemFontsRow) {
            NimarkoConfig.toggleSystemFonts();
            SettingsHelper.updateCheckState(view, NimarkoConfig.systemFonts);

            showRestartBulletin();
        } else if (item.id == tabledModeRow) {
            showTabletModeSelector(() -> {
                SettingsHelper.updateButtonValue(view, getTabletModeValue());

                AndroidUtilities.resetTabletFlag();
                if (getParentActivity() instanceof LaunchActivity launchActivity) {
                    launchActivity.invalidateTabletMode();
                }
            });
        } else if (item.id == downloadSpeedBoostRow) {
            ArrayList<String> configStringKeys = new ArrayList<>();
            ArrayList<Integer> configValues = new ArrayList<>();

            configStringKeys.add(getString(R.string.LiteBatteryDisabled));
            configValues.add(NimarkoConfig.DL_BOOST_NONE);

            configStringKeys.add(getString(R.string.LiteBatteryEnabled));
            configValues.add(NimarkoConfig.DL_BOOST_AVERAGE);

            configStringKeys.add(getString(R.string.EP_DownloadSpeedBoostExtreme));
            configValues.add(NimarkoConfig.DL_BOOST_EXTREME);

            PopupHelper.show(configStringKeys, getString(R.string.EP_DownloadSpeedBoost), configValues.indexOf(NimarkoConfig.downloadSpeedBoost), getContext(), i -> {
                NimarkoConfig.setDownloadSpeedBoost(configValues.get(i));
                SettingsHelper.updateButtonValue(view, getDownloadSpeedBoostText());
            });
        } else if (item.id == uploadSpeedBoostRow) {
            NimarkoConfig.toggleUploadSpeedBoost();
            SettingsHelper.updateCheckState(view, NimarkoConfig.uploadSpeedBoost);

            showRestartBulletin();
        } else if (item.id == slowNetworkMode) {
            NimarkoConfig.toggleSlowNetworkMode();
            SettingsHelper.updateCheckState(view, NimarkoConfig.slowNetworkMode);

            showRestartBulletin();
        } else if (item.id == deletedGiftsRow) {
            NimarkoConfig.toggleDeletedGiftsInject();
            SettingsHelper.updateCheckState(view, NimarkoConfig.deletedGiftsInject);

            for (int account = 0; account < org.telegram.messenger.UserConfig.MAX_ACCOUNT_COUNT; account++) {
                if (NimarkoConfig.deletedGiftsInject) {
                    app.nimarkogram.messenger.gifts.NimarkoDeletedGiftsManager.maybeInject(account);
                } else {
                    app.nimarkogram.messenger.gifts.NimarkoDeletedGiftsManager.removeInjected(account);
                }
                try {
                    org.telegram.messenger.NotificationCenter.getInstance(account)
                            .postNotificationName(org.telegram.messenger.NotificationCenter.starGiftsLoaded);
                } catch (Throwable ignored) {}
            }
        } else if (item.id == localPremiumEmojisRow) {
            NimarkoConfig.toggleLocalPremiumEmojis();
            SettingsHelper.updateCheckState(view, NimarkoConfig.localPremiumEmojis);
        } else if (item.id == exportConfigRow) {
            exportConfig();
        } else if (item.id == importConfigRow) {
            importConfig();
        }
    }

    @Override
    public boolean onLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    private String getSpringValue()  {
        return switch (NimarkoConfig.springAnimation) {
            case NimarkoConfig.SPRING_CLASSIC -> getString(R.string.EP_NavigationAnimationBezier);
            default -> getString(R.string.EP_NavigationAnimationSpring);
        };
    }

    private String getDownloadSpeedBoostText()  {
        return switch (NimarkoConfig.downloadSpeedBoost) {
            case NimarkoConfig.DL_BOOST_NONE -> getString(R.string.LiteBatteryDisabled);
            case NimarkoConfig.DL_BOOST_AVERAGE -> getString(R.string.LiteBatteryEnabled);
            case NimarkoConfig.DL_BOOST_EXTREME -> getString(R.string.EP_DownloadSpeedBoostExtreme);
            default -> getString(R.string.LiteBatteryDisabled);
        };
    }

    private void showTabletModeSelector(Runnable runnable) {
        ArrayList<String> configStringKeys = new ArrayList<>();
        ArrayList<Integer> configValues = new ArrayList<>();

        configStringKeys.add(getString(R.string.QualityAuto));
        configValues.add(NimarkoConfig.TABLET_AUTO);

        configStringKeys.add(getString(R.string.LiteBatteryEnabled));
        configValues.add(NimarkoConfig.TABLET_ENABLE);

        configStringKeys.add(getString(R.string.LiteBatteryDisabled));
        configValues.add(NimarkoConfig.TABLET_DISABLE);

        PopupHelper.show(configStringKeys, getString(R.string.AP_Tablet_Mode), configValues.indexOf(NimarkoConfig.tabletMode), getContext(), i -> {
            NimarkoConfig.setTabletMode(configValues.get(i));
            if (runnable != null) runnable.run();
        });
    }

    private String getTabletModeValue()  {
        return switch (NimarkoConfig.tabletMode) {
            case NimarkoConfig.TABLET_ENABLE -> getString(R.string.LiteBatteryEnabled);
            case NimarkoConfig.TABLET_DISABLE -> getString(R.string.LiteBatteryDisabled);
            default -> getString(R.string.QualityAuto);
        };
    }

    private void showStoriesArchiveConfigurator() {
        ArrayList<String> prefTitle = new ArrayList<>();
        ArrayList<Integer> prefIcon = new ArrayList<>();
        ArrayList<Boolean> prefCheck = new ArrayList<>();
        ArrayList<Boolean> prefDivider = new ArrayList<>();
        ArrayList<Runnable> clickListener = new ArrayList<>();

        prefTitle.add(getString(R.string.FilterContacts));
        prefIcon.add(R.drawable.msg_contacts);
        prefCheck.add(NimarkoConfig.archiveStoriesFromUsers);
        prefDivider.add(false);
        clickListener.add(NimarkoConfig::toggleArchiveStoriesFromUsers);

        prefTitle.add(getString(R.string.FilterChannels));
        prefIcon.add(R.drawable.msg_channel);
        prefCheck.add(NimarkoConfig.archiveStoriesFromChannels);
        prefDivider.add(false);
        clickListener.add(NimarkoConfig::toggleArchiveStoriesFromChannels);

        PopupHelper.showSwitchAlert(
                getString(R.string.CP_ArchiveStories),
                GeneralPreferencesActivity.this,
                prefTitle,
                prefIcon,
                prefCheck,
                null,
                null,
                prefDivider,
                clickListener,
                null
        );
    }

    private NotificationReactionCell notificationReactionCell;
    private SelectAnimatedEmojiDialog.SelectAnimatedEmojiDialogWindow selectReactionDialog;

    private void showNotificationReactionDialog(View anchor) {
        if (selectReactionDialog != null) {
            return;
        }
        final SelectAnimatedEmojiDialog.SelectAnimatedEmojiDialogWindow[] popup = new SelectAnimatedEmojiDialog.SelectAnimatedEmojiDialogWindow[1];
        SelectAnimatedEmojiDialog popupLayout = new SelectAnimatedEmojiDialog(this, getContext(), false, 0, SelectAnimatedEmojiDialog.TYPE_SET_DEFAULT_REACTION, null) {
            @Override
            protected void onEmojiSelected(View emojiView, Long documentId, TLRPC.Document document, TL_stars.TL_starGiftUnique gift, Integer until) {
                if (documentId == null) {
                    return;
                }
                if (!org.telegram.messenger.UserConfig.getInstance(currentAccount).isPremium()) {
                    if (popup[0] != null) {
                        selectReactionDialog = null;
                        popup[0].dismiss();
                    }
                    schedulePremiumReactionBulletin();
                    return;
                }
                NimarkoConfig.setNotificationReaction(currentAccount, "animated_" + documentId);
                NimarkoConfig.setNotificationReactionEmoji(currentAccount, document != null ? MessageObject.getEmoji(document) : null);
                if (notificationReactionCell != null) notificationReactionCell.update(true);
                if (popup[0] != null) {
                    selectReactionDialog = null;
                    popup[0].dismiss();
                }
            }

            @Override
            protected void onReactionClick(ImageViewEmoji emoji, ReactionsLayoutInBubble.VisibleReaction reaction) {
                NimarkoConfig.setNotificationReaction(currentAccount, reaction.emojicon);
                NimarkoConfig.setNotificationReactionEmoji(currentAccount, reaction.emojicon);
                if (notificationReactionCell != null) notificationReactionCell.update(true);
                if (popup[0] != null) {
                    selectReactionDialog = null;
                    popup[0].dismiss();
                }
            }
        };
        String selectedReaction = NimarkoConfig.getNotificationReaction(currentAccount);
        if (selectedReaction != null && selectedReaction.startsWith("animated_")) {
            try {
                popupLayout.setSelected(Long.parseLong(selectedReaction.substring(9)));
            } catch (Exception ignored) {}
        }
        List<TLRPC.TL_availableReaction> availableReactions = getMediaDataController().getReactionsList();
        ArrayList<ReactionsLayoutInBubble.VisibleReaction> reactions = new ArrayList<>(20);
        for (int i = 0; i < availableReactions.size(); ++i) {
            ReactionsLayoutInBubble.VisibleReaction reaction = new ReactionsLayoutInBubble.VisibleReaction();
            reaction.emojicon = availableReactions.get(i).reaction;
            reactions.add(reaction);
        }
        popupLayout.setRecentReactions(reactions);
        popupLayout.setSaveState(3);
        popup[0] = selectReactionDialog = new SelectAnimatedEmojiDialog.SelectAnimatedEmojiDialogWindow(popupLayout, LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT) {
            @Override
            public void dismiss() {
                super.dismiss();
                selectReactionDialog = null;
            }
        };
        popup[0].showAsDropDown(anchor, 0, -anchor.getMeasuredHeight() / 2, Gravity.TOP | Gravity.RIGHT);
        popup[0].dimBehind();
    }

    private void schedulePremiumReactionBulletin() {
        if (premiumBulletinRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(premiumBulletinRunnable);
        }
        final int generation = uiGeneration;
        premiumBulletinRunnable = () -> {
            if (!uiAlive || generation != uiGeneration || getParentActivity() == null) {
                return;
            }
            BulletinFactory.of(GeneralPreferencesActivity.this)
                    .createSimpleBulletin(R.raw.info, getString(R.string.NM_PremiumReactionRequired))
                    .show();
        };
        AndroidUtilities.runOnUIThread(premiumBulletinRunnable, 200);
    }

    private static final int REQ_EXPORT_CONFIG = 49801;
    private static final int REQ_IMPORT_CONFIG = 49802;

    private void exportConfig() {
        try {
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("application/json");
            intent.putExtra(Intent.EXTRA_TITLE, "nimarkogram_config.json");
            startActivityForResult(intent, REQ_EXPORT_CONFIG);
        } catch (Throwable t) {
            Toast.makeText(getContext(), getString(R.string.NM_Config_Error), Toast.LENGTH_SHORT).show();
        }
    }

    private void importConfig() {
        try {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, REQ_IMPORT_CONFIG);
        } catch (Throwable t) {
            Toast.makeText(getContext(), getString(R.string.NM_Config_Error), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        Activity parentActivity = getParentActivity();
        Context context = getContext();
        if (!uiAlive || parentActivity == null || context == null) return;
        Uri uri = data.getData();
        if (requestCode == REQ_EXPORT_CONFIG) {
            try (OutputStream os = parentActivity.getContentResolver().openOutputStream(uri)) {
                os.write(NimarkoConfigBackup.exportJson().getBytes(StandardCharsets.UTF_8));
                os.flush();
                Toast.makeText(context, getString(R.string.NM_Config_Exported), Toast.LENGTH_SHORT).show();
            } catch (Throwable t) {
                Toast.makeText(context, getString(R.string.NM_Config_Error), Toast.LENGTH_SHORT).show();
            }
        } else if (requestCode == REQ_IMPORT_CONFIG) {
            try (InputStream is = parentActivity.getContentResolver().openInputStream(uri)) {
                if (is == null) throw new java.io.IOException("Unable to open config");
                try (android.content.res.AssetFileDescriptor afd = parentActivity.getContentResolver()
                        .openAssetFileDescriptor(uri, "r")) {
                    if (afd != null && afd.getLength() > MAX_CONFIG_IMPORT_BYTES) {
                        throw new java.io.IOException("Config is too large");
                    }
                }
                ByteArrayOutputStream bos = new ByteArrayOutputStream(8192);
                byte[] buf = new byte[4096];
                int n;
                int total = 0;
                while ((n = is.read(buf)) != -1) {
                    if (n > MAX_CONFIG_IMPORT_BYTES - total) {
                        throw new java.io.IOException("Config is too large");
                    }
                    bos.write(buf, 0, n);
                    total += n;
                }
                String json = new String(bos.toByteArray(), StandardCharsets.UTF_8);
                if (NimarkoConfigBackup.looksLikeBackup(json) && NimarkoConfigBackup.importJson(json)) {
                    showRestartBulletin();
                } else {
                    Toast.makeText(context, getString(R.string.NM_Config_ImportEmpty), Toast.LENGTH_SHORT).show();
                }
            } catch (Throwable t) {
                Toast.makeText(context, getString(R.string.NM_Config_Error), Toast.LENGTH_SHORT).show();
            }
        }
    }

    private class NotificationReactionCell extends FrameLayout {
        private final TextView textView;
        private final AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable imageDrawable;

        NotificationReactionCell(Context context) {
            super(context);
            setBackground(Theme.getSelectorDrawable(false));
            textView = new TextView(context);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            textView.setText(getString(R.string.NM_NotificationReactionEmoji));
            addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL | Gravity.FILL_HORIZONTAL, 21, 0, 56, 0));
            imageDrawable = new AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable(this, AndroidUtilities.dp(24));
            setOnClickListener(v -> showNotificationReactionDialog(NotificationReactionCell.this));
        }

        void update(boolean animated) {
            String r = NimarkoConfig.getNotificationReaction(currentAccount);
            if (r == null || r.isEmpty()) {
                r = getMediaDataController().getDoubleTapReaction();
            }
            if (r != null && r.startsWith("animated_")) {
                try {
                    imageDrawable.set(Long.parseLong(r.substring(9)), animated);
                    return;
                } catch (Exception ignore) {}
            }
            if (r != null) {
                TLRPC.TL_availableReaction reaction = getMediaDataController().getReactionsMap().get(r);
                if (reaction != null) {
                    imageDrawable.set(reaction.static_icon, animated);
                    return;
                }
            }
            imageDrawable.set((TLRPC.Document) null, animated);
        }

        private void updateImageBounds() {
            imageDrawable.setBounds(
                    getWidth() - imageDrawable.getIntrinsicWidth() - AndroidUtilities.dp(21),
                    (getHeight() - imageDrawable.getIntrinsicHeight()) / 2,
                    getWidth() - AndroidUtilities.dp(21),
                    (getHeight() + imageDrawable.getIntrinsicHeight()) / 2
            );
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            super.dispatchDraw(canvas);
            updateImageBounds();
            imageDrawable.draw(canvas);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(
                    MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(50), MeasureSpec.EXACTLY)
            );
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            imageDrawable.detach();
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            imageDrawable.attach();
        }
    }

}
