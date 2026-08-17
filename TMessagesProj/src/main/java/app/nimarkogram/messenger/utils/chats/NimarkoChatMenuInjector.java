/*
 * Copyright github.com/arsLan4k1390, 2022-2026.
 * Licensed under GNU GPL v2 or later. See LICENSE.
 */

package app.nimarkogram.messenger.utils.chats;

import static org.telegram.messenger.LocaleController.getString;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Base64;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionIntroActivity;
import org.telegram.ui.CallLogActivity;
import org.telegram.ui.CameraScanActivity;
import org.telegram.ui.ChannelCreateActivity;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.ChatActivityEnterView;
import org.telegram.ui.Components.ChatAttachAlert;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.Gifts.GiftSheet;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.ProxyListActivity;

import java.util.List;

import app.nimarkogram.messenger.NimarkoConfig;
import app.nimarkogram.messenger.utils.LockedChats;

public final class NimarkoChatMenuInjector {

    private NimarkoChatMenuInjector() {}

    public static final int ADMIN_OPTION_REACTIONS      = NimarkoChatActivityHelper.OPTION_FOR_ADMINS_REACTIONS;
    public static final int ADMIN_OPTION_PERMISSIONS    = NimarkoChatActivityHelper.OPTION_FOR_ADMINS_PERMISSIONS;
    public static final int ADMIN_OPTION_ADMINISTRATORS = NimarkoChatActivityHelper.OPTION_FOR_ADMINS_ADMINISTRATORS;
    public static final int ADMIN_OPTION_MEMBERS        = NimarkoChatActivityHelper.OPTION_FOR_ADMINS_MEMBERS;
    public static final int ADMIN_OPTION_BLACKLIST      = NimarkoChatActivityHelper.OPTION_FOR_ADMINS_PERMISSIONS;
    public static final int ADMIN_OPTION_STATISTICS     = NimarkoChatActivityHelper.OPTION_FOR_ADMINS_STATISTICS;
    public static final int ADMIN_OPTION_RECENT_ACTIONS = NimarkoChatActivityHelper.OPTION_FOR_ADMINS_RECENT_ACTIONS;

    public static void injectAttachItem(
            ActionBarMenuItem headerItem,
            ActionBarMenu.LazyItem attachItem,
            ChatActivityEnterView chatActivityEnterView,
            ChatAttachAlert chatAttachAlert,
            Context context,
            Theme.ResourcesProvider resourcesProvider
    ) {
        if (headerItem == null) return;
        
        headerItem.setOnClickListener(v -> {
            if (chatActivityEnterView != null
                    && chatActivityEnterView.hasText()
                    && TextUtils.isEmpty(chatActivityEnterView.getSlowModeTimer())) {
                
                if (chatAttachAlert != null) {
                    chatAttachAlert.setEditingMessageObject(0, null);
                }
                if (chatActivityEnterView.getAttachButton() != null) {
                    chatActivityEnterView.getAttachButton().performClick();
                }
            } else {
                
                headerItem.toggleSubMenu(null, null);
            }
        });
    }

    public static void injectPrivacyShortcuts(
            ActionBarMenuItem headerItem,
            ChatActivity chatActivity,
            TLRPC.Chat currentChat,
            TLRPC.User currentUser,
            boolean hasEncrypted
    ) {
        if (headerItem == null) return;

        final boolean requireBiometrics = NimarkoConfig.askBiometricsToOpenChat && !hasEncrypted;
        final boolean upgradeGroupVisible = currentChat != null
                && !ChatObject.isChannel(currentChat) && currentChat.creator;

        if (!requireBiometrics && !upgradeGroupVisible) return;

        if (requireBiometrics) {
            
            final long dialogId = currentUser != null ? currentUser.id
                    : (currentChat != null ? -currentChat.id : 0L);
            if (dialogId != 0L && LockedChats.isLocked(chatActivity.getCurrentAccount(), dialogId)) {
                headerItem.lazilyAddSubItem(
                        NimarkoChatActivityHelper.OPTION_DO_NOT_ASK_PASSCODE,
                        R.drawable.msg_secret,
                        getString(R.string.NM_CH_DoNotAskPasscode));
            } else {
                headerItem.lazilyAddSubItem(
                        NimarkoChatActivityHelper.OPTION_ASK_PASSCODE,
                        R.drawable.msg_secret,
                        getString(R.string.NM_CH_AskPasscode));
            }
        }

        if (upgradeGroupVisible) {
            headerItem.lazilyAddSubItem(
                    NimarkoChatActivityHelper.OPTION_UPGRADE_GROUP,
                    R.drawable.ic_upward_solar,
                    getString(R.string.NM_CH_UpgradeGroup));
        }
    }

    public static void injectAdminShortcuts(ActionBarMenuItem headerItem, TLRPC.Chat currentChat) {
        if (headerItem == null || currentChat == null) return;

        if (!ChatObject.hasAdminRights(currentChat)) return;

        final boolean any = NimarkoConfig.adminsReactions || NimarkoConfig.adminsPermissions
                || NimarkoConfig.adminsAdministrators || NimarkoConfig.adminsMembers
                || NimarkoConfig.adminsStatistics || NimarkoConfig.adminsRecentActions;

        if (any) headerItem.lazilyAddColoredGap();

        if (NimarkoConfig.adminsReactions && ChatObject.canChangeChatInfo(currentChat)) {
            headerItem.lazilyAddSubItem(ADMIN_OPTION_REACTIONS, R.drawable.msg_reactions2, getString(R.string.Reactions));
        }
        
        if (NimarkoConfig.adminsPermissions
                && !(ChatObject.isChannel(currentChat) && !currentChat.megagroup)
                && !currentChat.gigagroup) {
            headerItem.lazilyAddSubItem(ADMIN_OPTION_PERMISSIONS, R.drawable.msg_permissions, getString(R.string.ChannelPermissions));
        }
        if (NimarkoConfig.adminsAdministrators) {
            headerItem.lazilyAddSubItem(ADMIN_OPTION_ADMINISTRATORS, R.drawable.msg_admins, getString(R.string.ChannelAdministrators));
        }
        if (NimarkoConfig.adminsMembers) {
            headerItem.lazilyAddSubItem(ADMIN_OPTION_MEMBERS, R.drawable.msg_groups, getString(R.string.ChannelMembers));
        }
        if (NimarkoConfig.adminsPermissions
                && ((ChatObject.isChannel(currentChat) && !currentChat.megagroup) || currentChat.gigagroup)) {
            headerItem.lazilyAddSubItem(ADMIN_OPTION_BLACKLIST, R.drawable.msg_user_remove, getString(R.string.ChannelBlacklist));
        }
        if (NimarkoConfig.adminsStatistics && ChatObject.isBoostSupported(currentChat)) {
            headerItem.lazilyAddSubItem(ADMIN_OPTION_STATISTICS, R.drawable.msg_stats, getString(R.string.StatisticsAndBoosts));
        }
        if (NimarkoConfig.adminsRecentActions) {
            headerItem.lazilyAddSubItem(ADMIN_OPTION_RECENT_ACTIONS, R.drawable.msg_log, getString(R.string.EventLog));
        }
    }

    public static void injectCreateChannel(ItemOptions io, BaseFragment fragment) {
        if (io == null || fragment == null) return;
        io.add(R.drawable.msg_channel, getString(R.string.NewChannel), () -> {
            SharedPreferences prefs = fragment.getMessagesController().getMainSettings();
            if (!BuildVars.DEBUG_VERSION && prefs.getBoolean("channel_intro", false)) {
                Bundle args = new Bundle();
                args.putInt("step", 0);
                fragment.presentFragment(new ChannelCreateActivity(args));
            } else {
                fragment.presentFragment(new ActionIntroActivity(ActionIntroActivity.ACTION_TYPE_CHANNEL_CREATE));
                prefs.edit().putBoolean("channel_intro", true).apply();
            }
        });
    }

    public static void injectArchived(ItemOptions io, BaseFragment fragment) {
        if (io == null || fragment == null) return;
        io.addIf(!NimarkoConfig.hideArchiveFromChatsList, R.drawable.msg_archive,
                getString(R.string.ArchivedChats),
                () -> openArchivedChats(fragment));
    }

    public static void injectCalls(ItemOptions io, BaseFragment fragment) {
        if (io == null || fragment == null) return;
        io.add(R.drawable.msg_calls, getString(R.string.Calls), () -> {
            Bundle args = new Bundle();
            args.putBoolean("needFinishFragment", false);
            args.putBoolean("hasMainTabs", false);
            fragment.presentFragment(new CallLogActivity(args));
        });
    }

    public static void injectGifts(ItemOptions io, int currentAccount, Context context) {
        if (io == null || context == null) return;
        final MessagesController mc = MessagesController.getInstance(currentAccount);
        final boolean available = mc != null && mc.starsGiftsEnabled && !mc.premiumFeaturesBlocked();
        if (!available) return;
        io.addGap();
        io.add(R.drawable.menu_gift, getString(R.string.Gift2TitleSelf1), () -> {
            AndroidUtilities.runOnUIThread(() -> {
                GiftSheet alert = new GiftSheet(context, currentAccount,
                        UserConfig.getInstance(currentAccount).getClientUserId(),
                        null, null);
                alert.show();
            });
        });
    }

    public static void injectScanQR(ItemOptions io, BaseFragment fragment) {
        if (io == null || fragment == null) return;
        io.add(R.drawable.msg_qrcode, getString(R.string.AuthAnotherClient), () -> {
            Activity activity = fragment.getParentActivity();
            if (activity == null) return;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                    && activity.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                activity.requestPermissions(
                        new String[]{Manifest.permission.CAMERA},
                        ActionIntroActivity.CAMERA_PERMISSION_REQUEST_CODE
                );
                return;
            }

            if (activity instanceof LaunchActivity) {
                LaunchActivity launch = (LaunchActivity) activity;
                openCameraScanActivity(fragment, launch.actionBarLayout, fragment.getCurrentAccount());

                if (AndroidUtilities.isTablet()
                        && launch.actionBarLayout != null
                        && launch.rightActionBarLayout != null) {
                    launch.actionBarLayout.rebuildFragments(INavigationLayout.REBUILD_FLAG_REBUILD_LAST);
                    launch.rightActionBarLayout.rebuildFragments(INavigationLayout.REBUILD_FLAG_REBUILD_LAST);
                }
            }
        });
    }

    public static void injectProxySettings(ItemOptions io, BaseFragment fragment) {
        if (io == null || fragment == null) return;
        boolean available = false;
        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            UserConfig userConfig = AccountInstance.getInstance(i).getUserConfig();
            if (userConfig == null) continue;
            TLRPC.User user = userConfig.getCurrentUser();
            if (user == null || TextUtils.isEmpty(user.phone)) continue;
            String phone = user.phone;
            if (phone.startsWith("7") || phone.startsWith("98") || phone.startsWith("964")) {
                available = true;
                break;
            }
        }
        
        if (available) {
            int state = org.telegram.tgnet.ConnectionsManager.getInstance(fragment.getCurrentAccount()).getConnectionState();
            boolean connected = state == org.telegram.tgnet.ConnectionsManager.ConnectionStateConnected
                    || state == org.telegram.tgnet.ConnectionsManager.ConnectionStateUpdating;
            android.content.SharedPreferences prefs = org.telegram.messenger.ApplicationLoader.applicationContext
                    .getSharedPreferences("mainconfig", android.content.Context.MODE_PRIVATE);
            boolean proxyEnabled = prefs.getBoolean("proxy_enabled", false);
            if (proxyEnabled && connected) available = false;
        }
        io.addGapIf(available);
        io.addIf(available, R.drawable.shield_network_filled_solar, getString(R.string.ProxySettings),
                () -> fragment.presentFragment(new ProxyListActivity()));
    }

    private static void openCameraScanActivity(BaseFragment fragment, INavigationLayout actionBarLayout, int account) {
        CameraScanActivity.showAsSheet(fragment, false, CameraScanActivity.TYPE_QR_LOGIN,
                new CameraScanActivity.CameraScanActivityDelegate() {
                    @Override
                    public boolean processQr(String link, Runnable onLoadEnd) {
                        AndroidUtilities.runOnUIThread(() -> {
                            try {
                                Uri uri = Uri.parse(link);
                                if (!"tg".equalsIgnoreCase(uri.getScheme())
                                        || !"login".equalsIgnoreCase(uri.getHost())) {
                                    throw new IllegalArgumentException("Not a Telegram login QR");
                                }
                                String code = uri.getQueryParameter("token");
                                if (TextUtils.isEmpty(code)) throw new IllegalArgumentException("Missing login token");
                                byte[] token = Base64.decode(code, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
                                if (token.length == 0 || token.length > 256) throw new IllegalArgumentException("Invalid login token");

                                TLRPC.TL_auth_acceptLoginToken req = new TLRPC.TL_auth_acceptLoginToken();
                                req.token = token;
                                ConnectionsManager.getInstance(account).sendRequest(req, (res, err) -> AndroidUtilities.runOnUIThread(() -> {
                                    if (err != null && actionBarLayout != null) {
                                        List<BaseFragment> stack = actionBarLayout.getFragmentStack();
                                        if (stack != null && !stack.isEmpty()) {
                                            AlertsCreator.showSimpleAlert(stack.get(stack.size() - 1),
                                                    getString(R.string.AuthAnotherClient), err.text);
                                        }
                                    }
                                    if (onLoadEnd != null) onLoadEnd.run();
                                }));
                            } catch (Exception e) {
                                FileLog.e("Failed to pass qr code auth", e);
                                if (actionBarLayout != null) {
                                    List<BaseFragment> stack = actionBarLayout.getFragmentStack();
                                    if (stack != null && !stack.isEmpty()) {
                                        BaseFragment fr = stack.get(0);
                                        AndroidUtilities.runOnUIThread(() ->
                                                AlertsCreator.showSimpleAlert(fr,
                                                        getString(R.string.AuthAnotherClient),
                                                        getString(R.string.ErrorOccurred)));
                                    }
                                }
                                if (onLoadEnd != null) onLoadEnd.run();
                            }
                        }, 750);
                        return true;
                    }
                });
    }

    public static void openArchivedChats(BaseFragment fragment) {
        if (fragment == null) return;
        Bundle args = new Bundle();
        args.putInt("folderId", 1);
        fragment.presentFragment(new DialogsActivity(args));
    }

    public static void injectCallShortcuts(
            ActionBarMenuItem headerItem,
            TLRPC.UserFull userFull,
            int callActionId,
            int videoCallActionId
    ) {
        if (headerItem == null || userFull == null) return;
        if (!userFull.phone_calls_available) return;
        headerItem.lazilyAddSubItem(callActionId, R.drawable.msg_callback, getString(R.string.Call));
        if (userFull.video_calls_available) {
            headerItem.lazilyAddSubItem(videoCallActionId, R.drawable.msg_videocall, getString(R.string.VideoCall));
        }
    }

    public static void injectSaved(ItemOptions io, BaseFragment fragment) {
        if (io == null || fragment == null) return;
        io.addIf(
                !NimarkoConfig.showMainTabs,
                R.drawable.msg_saved,
                getString(R.string.SavedMessages),
                () -> fragment.presentFragment(ChatActivity.of(NimarkoChatHelper2.getCustomChatID(fragment.getCurrentAccount())))
        );
    }
}
