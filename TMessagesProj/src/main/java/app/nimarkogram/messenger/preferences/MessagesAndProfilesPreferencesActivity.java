/* Modifications Copyright (C) 2026 Ettacent */

/**
 * This is the source code of Cherrygram for Android.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 * Please, be respectful and credit the original author if you use this code.
 *
 * Copyright github.com/arsLan4k1390, 2022-2026.
 *
 * Ported into NimarkoGram (GPL-2.0):
 *   - package renamed to app.nimarkogram.messenger.preferences
 *   - CherrygramAppearanceConfig.INSTANCE.getX/setX → NimarkoConfig.* + toggle*
 *   - Constants.* inlined (CHERRY_EMOJI_ID -> 0 since NG ships no Cherry asset;
 *     PROFILE_BACKGROUND_COLOR_ID_RED / REPLY_BACKGROUND_COLOR_ID kept as
 *     literal palette indices)
 *   - CGResourcesHelper.getDCName/getDCGeo inlined locally
 *   - Extra.INSTANCE.getRegistrationDate / addBirthdayToCalendar are NOT
 *     ported (CG ships them behind a closed gitlab pull; per task scope they
 *     are dropped — image-click handlers become no-ops)
 *   - FirebaseAnalyticsHelper.trackEventWithEmptyBundle dropped
 *   - ProfileChannelCell.setCherry() → setNimarko() (sibling method added to
 *     org.telegram.ui.Cells.ProfileChannelCell in this branch)
 *   - ThemePreviewMessagesCell.TYPE_PEER_COLOR_CHERRY is not patched in;
 *     when user lacks a custom emoji, the upstream TYPE_PEER_COLOR is used
 *     instead (no default Cherry emoji injection)
 *
 * Inner-class layout (Page / ColoredActionBar / ProfilePreview) is preserved
 * verbatim per task instruction ("either as inner classes or extracted top-
 * level — your call").
 *
 * Original CG attribution header kept above per GPL-2.0.
 */

package app.nimarkogram.messenger.preferences;

import org.telegram.messenger.NotificationCenter;
import static org.telegram.messenger.AndroidUtilities.distance;
import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.util.SparseIntArray;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.ViewCompat;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.Emoji;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.MessageDrawable;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.NotificationsCheckCell;
import org.telegram.ui.Cells.ProfileChannelCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextDetailCell;
import org.telegram.ui.Cells.ThemePreviewMessagesCell;
import org.telegram.ui.Components.AnimatedColor;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Premium.PremiumGradient;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.SimpleThemeDescription;
import org.telegram.ui.Components.ViewPagerFixed;
import org.telegram.ui.Stars.StarGiftPatterns;
import org.telegram.ui.Stories.StoriesUtilities;
import org.telegram.ui.UserInfoActivity;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Locale;

import app.nimarkogram.messenger.AppConfig;

import app.nimarkogram.messenger.utils.AppExtra;
public class MessagesAndProfilesPreferencesActivity extends BaseFragment {

    static final int SETTING_SHOW_SECONDS = 1;
    static final int SETTING_PREMIUM_STATUSES = 2;
    static final int SETTING_REPLY_BACKGROUND = 3;
    static final int SETTING_REPLY_COLORS = 4;
    static final int SETTING_REPLY_EMOJI = 5;
    static final int SETTING_PROFILE_CHANNEL = 101;
    static final int SETTING_PROFILE_ID_DC = 102;
    static final int SETTING_PROFILE_BIRTHDAY = 103;
    static final int SETTING_PROFILE_BUSINESS = 104;
    static final int SETTING_PROFILE_COLOR = 105;
    static final int SETTING_PROFILE_EMOJI = 106;

    // Palette indices reused from CG Constants. These are upstream Telegram
    // peer-color IDs — kept as literals to avoid copying CG's full Constants
    // class for two integers.
    private static final int PROFILE_BACKGROUND_COLOR_ID_RED = 14;
    private static final int REPLY_BACKGROUND_COLOR_ID = 13;
    // CG injects its branded "cherry" custom emoji here. NimarkoGram ships
    // no equivalent custom emoji asset, so the fallback is "no override".
    private static final long NIMARKO_EMOJI_ID_FALLBACK = 0L;

    private MessageProfileLayout contentView;
    private ColoredActionBar colorBar;

    private final int PAGE_MESSAGE = 0;
    private final int PAGE_PROFILE = 1;

    public Page messagePage;
    public Page profilePage;

    public Page getCurrentPage() {
        return viewPager.getCurrentPosition() == 0 ? messagePage : profilePage;
    }

    private class Page extends FrameLayout {

        private ProfilePreviewCard profileCard;

        private RecyclerListView listView;
        private RecyclerView.Adapter listAdapter;

        private SettingsSearchHighlight settingHighlight;
        private int selectedColor;
        private long selectedEmoji;
        private ThemePreviewMessagesCell messagesCellPreview;

        int rowCount;

        int previewRow = -1;
        int messagePreviewDivisorRow = -1;
        int headerRow = -1;

        int timeWithSecondsSwitchRow = -1;
        int premiumStatusSwitchRow = -1;
        int replyBackgroundSwitchRow = -1;
        int replyColorSwitchRow = -1;
        int replyEmojiSwitchRow = -1;

        int infoHeaderRow;
        int idDcPreviewRow;
        int birthdayPreviewRow;
        int businessHoursPreviewRow;
        int businessLocationPreviewRow;
        int channelPreviewRow;
        int channelPreviewDivisorRow = -1;

        int channelPreviewSwitchRow = -1;
        int showDcIdSwitchRow = -1;
        int birthdayPreviewSwitchRow = -1;
        int businessPreviewSwitchRow = -1;
        int profilePreviewDivisorRow = -1;
        int profileBackgroundSwitchRow = -1;
        int profileEmojiSwitchRow = -1;

        private final ArrayList<Integer> rowKeys = new ArrayList<>();

        private final int VIEW_TYPE_MESSAGE = 0;
        private final int VIEW_TYPE_HEADER = 1;
        private final int VIEW_TYPE_SWITCH = 2;
        private final int VIEW_TYPE_TEXT_DETAIL = 4;
        private final int VIEW_TYPE_CHANNEL = 5;
        private final int VIEW_TYPE_SHADOW = 6;

        private final int VIEW_TYPE_PROFILE_PREVIEW = 7;
        private final int type;
        public Page(Context context, int type) {
            super(context);
            this.type = type;

            TLRPC.User user = getUserConfig().getCurrentUser();

            if (type == PAGE_PROFILE) {
                if (user != null && user.premium && UserObject.getProfileColorId(user) != -1) {
                    selectedColor = AppConfig.profileBackgroundColor ? UserObject.getProfileColorId(user) : -1;
                } else {
                    selectedColor = AppConfig.profileBackgroundColor ? PROFILE_BACKGROUND_COLOR_ID_RED : -1;
                }

                if (user != null && user.premium && UserObject.getProfileEmojiId(user) != 0) {
                    selectedEmoji = AppConfig.profileBackgroundEmoji ? UserObject.getProfileEmojiId(user) : 0;
                } else {
                    selectedEmoji = AppConfig.profileBackgroundEmoji ? NIMARKO_EMOJI_ID_FALLBACK : 0;
                }
            } else {
                if (user != null && user.premium && UserObject.getColorId(user) != -1) {
                    selectedColor = AppConfig.replyCustomColors ? UserObject.getColorId(user) : -1;
                } else {
                    selectedColor = AppConfig.replyCustomColors ? REPLY_BACKGROUND_COLOR_ID : -1;
                }

                if (user != null && user.premium && UserObject.getEmojiId(user) != 0) {
                    selectedEmoji = AppConfig.replyBackgroundEmoji ? UserObject.getEmojiId(user) : 0;
                } else {
                    selectedEmoji = AppConfig.replyBackgroundEmoji ? NIMARKO_EMOJI_ID_FALLBACK : 0;
                }
            }

            listView = new RecyclerListView(getContext(), getResourceProvider());
            listView.setClipToPadding(false);
            ((DefaultItemAnimator) listView.getItemAnimator()).setSupportsChangeAnimations(false);
            listView.setLayoutManager(new LinearLayoutManager(getContext()));
            listView.setAdapter(listAdapter = new RecyclerListView.SelectionAdapter() {
                @Override
                public boolean isEnabled(RecyclerView.ViewHolder holder) {
                    return holder.getItemViewType() == VIEW_TYPE_SWITCH
                            || holder.getItemViewType() == VIEW_TYPE_TEXT_DETAIL || holder.getItemViewType() == VIEW_TYPE_CHANNEL;
                }

                @NonNull
                @Override
                public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                    View view;
                    switch (viewType) {
                        case VIEW_TYPE_MESSAGE:
                            // NG note: CG used TYPE_PEER_COLOR_CHERRY (1390) when the
                            // user had no emoji set, to inject a default Cherry emoji
                            // into the preview. NG drops that branch (no Cherry asset)
                            // and always uses TYPE_PEER_COLOR.
                            ThemePreviewMessagesCell messagesCell = messagesCellPreview = new ThemePreviewMessagesCell(
                                    getContext(), parentLayout, ThemePreviewMessagesCell.TYPE_PEER_COLOR
                            );
                            messagesCell.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
                            messagesCell.fragment = MessagesAndProfilesPreferencesActivity.this;
                            // NimarkoGram: TYPE_PEER_COLOR sample messages are built with notime=true
                            // (link-preview look), so this page's preview shows NO bottom time. Here we
                            // DO want the time visible so the "show time with seconds" toggle previews
                            // correctly. Clear notime on THIS preview's cells only — PeerColorActivity /
                            // ChannelColorActivity keep their time-less previews untouched.
                            try {
                                for (org.telegram.ui.Cells.ChatMessageCell pc : messagesCell.getCells()) {
                                    if (pc != null && pc.getMessageObject() != null) {
                                        pc.getMessageObject().notime = false;
                                        pc.forceResetMessageObject();
                                    }
                                }
                            } catch (Throwable ignored) {}
                            view = messagesCell;
                            break;
                        case VIEW_TYPE_HEADER:
                            HeaderCell header = new HeaderCell(getContext());
                            view = header;
                            break;
                        case VIEW_TYPE_SWITCH:
                            NotificationsCheckCell switchCell = new NotificationsCheckCell(
                                    getContext(), 21, 60, false, getResourceProvider());
                            view = switchCell;
                            break;
                        case VIEW_TYPE_TEXT_DETAIL:
                            TextDetailCell textDetailCell = new TextDetailCell(getContext(), getResourceProvider(), true, true);
                            textDetailCell.setContentDescriptionValueFirst(true);
                            view = textDetailCell;
                            break;
                        case VIEW_TYPE_CHANNEL:
                            view = new ProfileChannelCell(MessagesAndProfilesPreferencesActivity.this);
                            break;
                        case VIEW_TYPE_PROFILE_PREVIEW:
                            view = new ProfilePreviewCard(getContext());
                            break;
                        case VIEW_TYPE_SHADOW:
                        default: {
                            view = new ShadowSectionCell(getContext());
                            break;
                        }
                    }
                    return new RecyclerListView.Holder(view);
                }

                @Override
                public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                    switch (getItemViewType(position)) {
                        case VIEW_TYPE_PROFILE_PREVIEW:
                            profileCard = (ProfilePreviewCard) holder.itemView;
                            profileCard.bind(selectedColor, selectedEmoji);
                            break;
                        case VIEW_TYPE_HEADER: {
                            HeaderCell headerCell = (HeaderCell) holder.itemView;
                            headerCell.setText("");
                            if (position == infoHeaderRow) {
                                headerCell.setText(getString(R.string.Info));
                            } else if (position == headerRow) {
                                if (type == PAGE_MESSAGE) {
                                    headerCell.setText(getString(R.string.NM_MP_CustomizeMessage));
                                } else {
                                    headerCell.setText(getString(R.string.NM_MP_CustomizeProfile));
                                }
                            }
                            break;
                        }
                        case VIEW_TYPE_SWITCH: {
                            NotificationsCheckCell switchCell = (NotificationsCheckCell) holder.itemView;
                            if (position == timeWithSecondsSwitchRow) {
                                bindSwitch(switchCell, R.string.NM_MP_ShowSeconds,
                                        R.string.NM_MP_ShowSeconds_Desc, AppConfig.showSeconds, true);
                            } else if (position == premiumStatusSwitchRow) {
                                bindSwitch(switchCell, R.string.NM_MP_DisablePremiumStatuses,
                                        R.string.NM_MP_DisablePremiumStatuses_Desc, AppConfig.disablePremiumStatuses, true);
                            } else if (position == replyBackgroundSwitchRow) {
                                bindSwitch(switchCell, R.string.NM_MP_ReplyBackground,
                                        R.string.NM_MP_ReplyBackground_Desc, AppConfig.replyBackground, false);
                            } else if (position == replyColorSwitchRow) {
                                bindSwitch(switchCell, R.string.NM_MP_ReplyCustomColors,
                                        R.string.NM_MP_ReplyCustomColors_Desc, AppConfig.replyCustomColors, false);
                            } else if (position == replyEmojiSwitchRow) {
                                bindSwitch(switchCell, R.string.NM_MP_ReplyBackgroundEmoji,
                                        R.string.NM_MP_ReplyBackgroundEmoji_Desc, AppConfig.replyBackgroundEmoji, false);
                            } else if (position == channelPreviewSwitchRow) {
                                bindSwitch(switchCell, R.string.NM_MP_ProfileChannelPreview,
                                        R.string.NM_MP_ProfileChannelPreview_Desc, AppConfig.profileChannelPreview, false);
                            } else if (position == showDcIdSwitchRow) {
                                bindSwitch(switchCell, R.string.NM_MP_ShowIdDc,
                                        R.string.NM_MP_ShowIdDc_Desc, AppConfig.showIDDC, false);
                            } else if (position == birthdayPreviewSwitchRow) {
                                bindSwitch(switchCell, R.string.NM_MP_ProfileBirthDatePreview,
                                        R.string.NM_MP_ProfileBirthDatePreview_Desc, AppConfig.profileBirthDatePreview, false);
                            } else if (position == businessPreviewSwitchRow) {
                                bindSwitch(switchCell, R.string.NM_MP_ProfileBusinessPreview,
                                        R.string.NM_MP_ProfileBusinessPreview_Desc, AppConfig.profileBusinessPreview, false);
                            } else if (position == profileBackgroundSwitchRow) {
                                bindSwitch(switchCell, R.string.NM_MP_ProfileBackgroundColor,
                                        R.string.NM_MP_ProfileBackgroundColor_Desc, AppConfig.profileBackgroundColor, false);
                            } else if (position == profileEmojiSwitchRow) {
                                bindSwitch(switchCell, R.string.NM_MP_ProfileBackgroundEmoji,
                                        R.string.NM_MP_ProfileBackgroundEmoji_Desc, AppConfig.profileBackgroundEmoji, true);
                            }
                            break;
                        }
                        case VIEW_TYPE_TEXT_DETAIL: {
                            TextDetailCell detailCell = (TextDetailCell) holder.itemView;
                            final TLRPC.User me = getUserConfig().getCurrentUser();

                            if (position == idDcPreviewRow) {
                                DecimalFormat df = new DecimalFormat("#,###", new DecimalFormatSymbols(Locale.US) {{ setGroupingSeparator(' '); }});
                                long previewId = me != null ? me.id : getUserConfig().getClientUserId();
                                detailCell.setTextAndValue(LocaleController.formatString(R.string.NM_ProfileInfo_Id,
                                                previewId != 0 ? df.format(previewId) : getString(R.string.NM_ProfileInfo_Unknown)),
                                        AppExtra.previewDCLabel(AppExtra.resolveDcId(currentAccount, me, null)), false);

                                Drawable drawable = ContextCompat.getDrawable(detailCell.getContext(), R.drawable.msg_calendar2);
                                detailCell.setImage(drawable);
                                if (drawable != null && colorBar != null && colorBar.getActionBarButtonColor() != 0) {
                                    final int buttonColor = processColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader, getResourceProvider()));
                                    drawable.setColorFilter(new PorterDuffColorFilter(buttonColor, PorterDuff.Mode.MULTIPLY));
                                }
                                // NG note: CG opens Extra.INSTANCE.getRegistrationDate
                                // on click — that helper lives in a closed gitlab pull,
                                // so the click handler is intentionally a no-op here.
                                detailCell.setImageClickListener(v -> {});
                            } else if (position == birthdayPreviewRow) {
                                TLRPC.UserFull meFull = me != null
                                        ? getMessagesController().getUserFull(me.id) : null;
                                if (meFull != null && meFull.birthday != null) {
                                    String date = UserInfoActivity.birthdayString(meFull.birthday);
                                    detailCell.setTextAndValue(date, getString(R.string.NM_ProfilePreviewBirthday), false);
                                } else {
                                    final int age = Period.between(LocalDate.of(2022, 1, 15), LocalDate.now()).getYears();
                                    String date = LocaleController.formatString(R.string.NM_ProfilePreviewBirthdayAge, birthdayString(), age);
                                    detailCell.setTextAndValue(date, getString(R.string.NM_ProfilePreviewBirthday), false);
                                }
                                detailCell.textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, getResourceProvider()));

                                Drawable drawable = ContextCompat.getDrawable(detailCell.getContext(), R.drawable.input_calendar_add_solar);
                                detailCell.setImage(drawable);
                                if (drawable != null && colorBar != null && colorBar.getActionBarButtonColor() != 0) {
                                    final int buttonColor = processColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader, getResourceProvider()));
                                    drawable.setColorFilter(new PorterDuffColorFilter(buttonColor, PorterDuff.Mode.MULTIPLY));
                                }
                                // NG note: CG hooks Extra.INSTANCE.addBirthdayToCalendar
                                // (closed pull). Click handler dropped.
                                detailCell.setImageClickListener(v -> {});
                            } else if (position == businessHoursPreviewRow) {
                                detailCell.textView.setTextColor(Theme.getColor(Theme.key_avatar_nameInMessageGreen, getResourceProvider()));
                                detailCell.setTextAndValue(getString(R.string.BusinessHoursProfileNowOpen), getString(R.string.BusinessHoursProfile), false);
                            } else if (position == businessLocationPreviewRow) {
                                detailCell.textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, getResourceProvider()));
                                detailCell.setTextAndValue(LocaleController.formatString(R.string.NM_Settings_WorldwidePreview, getString(R.string.AppName)), getString(R.string.BusinessProfileLocation), false);
                            }
                            break;
                        }
                        case VIEW_TYPE_CHANNEL:
                            if (position == channelPreviewRow) {
                                ((ProfileChannelCell) holder.itemView).setNimarko();
                            }
                            break;
                    }
                }

                @Override
                public int getItemCount() {
                    return rowCount;
                }

                @Override
                public void onViewRecycled(@NonNull RecyclerView.ViewHolder holder) {
                    if (holder.itemView == profileCard) profileCard = null;
                    super.onViewRecycled(holder);
                }

                @Override
                public int getItemViewType(int position) {
                    if (position == previewRow) {
                        return type == PAGE_PROFILE ? VIEW_TYPE_PROFILE_PREVIEW : VIEW_TYPE_MESSAGE;
                    }
                    if (position == infoHeaderRow || position == headerRow) {
                        return VIEW_TYPE_HEADER;
                    }
                    if (position == timeWithSecondsSwitchRow || position == premiumStatusSwitchRow || position == replyBackgroundSwitchRow || position == replyColorSwitchRow || position == replyEmojiSwitchRow
                            || position == channelPreviewSwitchRow || position == showDcIdSwitchRow || position == birthdayPreviewSwitchRow || position == businessPreviewSwitchRow || position == profileBackgroundSwitchRow || position == profileEmojiSwitchRow) {
                        return VIEW_TYPE_SWITCH;
                    }
                    if (position == idDcPreviewRow || position == birthdayPreviewRow
                            || position == businessHoursPreviewRow || position == businessLocationPreviewRow) {
                        return VIEW_TYPE_TEXT_DETAIL;
                    }
                    if (position == channelPreviewRow) {
                        return VIEW_TYPE_CHANNEL;
                    }
                    return VIEW_TYPE_SHADOW;
                }
            });
            listView.setOnItemClickListener((view, position) -> {
                final TLRPC.User me = getUserConfig().getCurrentUser();

                if (position == timeWithSecondsSwitchRow) {
                    AppConfig.toggleShowSeconds();
                    setSwitchChecked(view, AppConfig.showSeconds);
                    LocaleController.getInstance().recreateFormatters();
                    // Refresh the in-page sample preview immediately: force each preview
                    // ChatMessageCell to fully rebuild (forceResetMessageObject -> re-runs
                    // measureTime, which rebuilds currentTimeString via the now-changed
                    // getFormatterDay()) so the sample message time switches to/from HH:mm:ss
                    // right away. A plain invalidate() doesn't recompute the time string/width.
                    if (messagesCellPreview != null) {
                        for (ChatMessageCell previewCell : messagesCellPreview.getCells()) {
                            if (previewCell != null && previewCell.getMessageObject() != null) {
                                previewCell.forceResetMessageObject();
                                previewCell.requestLayout();
                                previewCell.invalidate();
                            }
                        }
                    }
                    // Defer the heavy rebuild so the switch toggle animation plays first
                    // (an immediate rebuildAllFragmentViews recreates this row, killing it).
                    // Broadcast reloadInterface so EVERY open fragment (chat/dialogs/profile)
                    // recreates its cells and re-measures the now-wider HH:mm:ss time; otherwise
                    // already-laid-out back-stack cells keep their stale HH:MM width and the
                    // seconds get clipped at the bottom of the bubble until they rebind on scroll.
                    NotificationCenter.getGlobalInstance()
                            .postNotificationName(NotificationCenter.reloadInterface);
                } else if (position == premiumStatusSwitchRow) {
                    AppConfig.toggleDisablePremiumStatuses();
                    setSwitchChecked(view, AppConfig.disablePremiumStatuses);

                    if (profilePage.profileCard != null) profilePage.profileCard.preview.updatePremiumStatus(true);

                    updateMessages();
                    if (type == PAGE_PROFILE) {
                        messagePage.updateMessages();
                        messagePage.refreshPremiumStatusRow();
                    } else {
                        profilePage.refreshPremiumStatusRow();
                    }
                } else if (position == replyBackgroundSwitchRow) {
                    AppConfig.toggleReplyBackground();
                    setSwitchChecked(view, AppConfig.replyBackground);

                    updateMessages();
                } else if (position == replyColorSwitchRow) {
                    AppConfig.toggleReplyCustomColors();
                    setSwitchChecked(view, AppConfig.replyCustomColors);

                    if (me != null && me.premium && UserObject.getColorId(me) != -1) {
                        selectedColor = AppConfig.replyCustomColors ? UserObject.getColorId(me) : -1;
                    } else {
                        selectedColor = AppConfig.replyCustomColors ? REPLY_BACKGROUND_COLOR_ID : -1;
                    }

                    updateMessages();
                } else if (position == replyEmojiSwitchRow) {
                    AppConfig.toggleReplyBackgroundEmoji();
                    setSwitchChecked(view, AppConfig.replyBackgroundEmoji);

                    if (me != null && me.premium && UserObject.getEmojiId(me) != 0) {
                        selectedEmoji = AppConfig.replyBackgroundEmoji ? UserObject.getEmojiId(me) : 0;
                    } else {
                        selectedEmoji = AppConfig.replyBackgroundEmoji ? NIMARKO_EMOJI_ID_FALLBACK : 0;
                    }

                    updateMessages();
                } else if (position == channelPreviewRow) {
                    // CG opened Constants.CG_CHANNEL_URL; NG has no equivalent
                    // promo channel, so the mock preview is non-interactive.
                } else if (position == channelPreviewSwitchRow) {
                    AppConfig.toggleProfileChannelPreview();
                    setSwitchChecked(view, AppConfig.profileChannelPreview);

                    profilePage.updateRows();
                } else if (position == showDcIdSwitchRow) {
                    AppConfig.toggleShowIDDC();
                    setSwitchChecked(view, AppConfig.showIDDC);

                    profilePage.updateRows();
                } else if (position == birthdayPreviewSwitchRow) {
                    AppConfig.toggleProfileBirthDatePreview();
                    setSwitchChecked(view, AppConfig.profileBirthDatePreview);

                    profilePage.updateRows();
                } else if (position == businessPreviewSwitchRow) {
                    AppConfig.toggleProfileBusinessPreview();
                    setSwitchChecked(view, AppConfig.profileBusinessPreview);

                    profilePage.updateRows();
                } else if (position == profileBackgroundSwitchRow) {
                    AppConfig.toggleProfileBackgroundColor();
                    setSwitchChecked(view, AppConfig.profileBackgroundColor);

                    if (me != null && me.premium && UserObject.getProfileColorId(me) != -1) {
                        selectedColor = AppConfig.profileBackgroundColor ? UserObject.getProfileColorId(me) : -1;
                    } else {
                        selectedColor = AppConfig.profileBackgroundColor ? PROFILE_BACKGROUND_COLOR_ID_RED : -1;
                    }

                    updateMessages();
                    if (type == PAGE_PROFILE) {
                        messagePage.updateMessages();
                    }
                    if (type == PAGE_PROFILE && colorBar != null) {
                        colorBar.setColor(currentAccount, selectedColor, true);
                    }
                    if (profileCard != null) {
                        profileCard.setColor(selectedColor, true);
                        profileCard.preview.overrideAvatarColor(messagePage.selectedColor);
                    }
                } else if (position == profileEmojiSwitchRow) {
                    AppConfig.toggleProfileBackgroundEmoji();
                    setSwitchChecked(view, AppConfig.profileBackgroundEmoji);

                    if (me != null && me.premium && UserObject.getProfileEmojiId(me) != 0) {
                        selectedEmoji = AppConfig.profileBackgroundEmoji ? UserObject.getProfileEmojiId(me) : 0;
                    } else {
                        selectedEmoji = AppConfig.profileBackgroundEmoji ? NIMARKO_EMOJI_ID_FALLBACK : 0;
                    }

                    updateMessages();
                    if (type == PAGE_PROFILE) {
                        messagePage.updateMessages();
                    }
                    if (profileCard != null) {
                        profileCard.preview.setEmoji(selectedEmoji, true);
                    }
                }
            });
            listView.setSections(true);
            addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

            DefaultItemAnimator itemAnimator = new DefaultItemAnimator();
            itemAnimator.setDurations(350);
            itemAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
            itemAnimator.setDelayAnimations(false);
            itemAnimator.setSupportsChangeAnimations(false);
            listView.setItemAnimator(itemAnimator);


            updateColors();
            updateRows();

        }

        private void bindSwitch(NotificationsCheckCell cell, int titleRes, int descriptionRes,
                                boolean checked, boolean divider) {
            cell.setTextAndValueAndCheck(getString(titleRes), getString(descriptionRes),
                    checked, 0, true, divider);
        }

        private void setSwitchChecked(View view, boolean checked) {
            if (view instanceof NotificationsCheckCell) {
                ((NotificationsCheckCell) view).setChecked(checked);
            }
        }

        private void setBottomInset(int bottom) {
            listView.setPadding(listView.getPaddingLeft(), listView.getPaddingTop(), listView.getPaddingRight(), bottom);
        }

        private void updateRows() {
            final ArrayList<Integer> previousRows = new ArrayList<>(rowKeys);
            rowKeys.clear();
            rowCount = 0;
            previewRow = messagePreviewDivisorRow = headerRow = -1;
            timeWithSecondsSwitchRow = premiumStatusSwitchRow = -1;
            replyBackgroundSwitchRow = replyColorSwitchRow = replyEmojiSwitchRow = -1;
            infoHeaderRow = idDcPreviewRow = birthdayPreviewRow = -1;
            businessHoursPreviewRow = businessLocationPreviewRow = -1;
            channelPreviewRow = channelPreviewDivisorRow = -1;
            channelPreviewSwitchRow = showDcIdSwitchRow = birthdayPreviewSwitchRow = -1;
            businessPreviewSwitchRow = profilePreviewDivisorRow = -1;
            profileBackgroundSwitchRow = profileEmojiSwitchRow = -1;

            if (type == PAGE_MESSAGE) {
                previewRow = addRow(1);
                messagePreviewDivisorRow = addRow(2);
                headerRow = addRow(3);
                timeWithSecondsSwitchRow = addRow(4);

                // premiumStatusSwitchRow is a static row — it is always present, so the
                // old remove+insert here only produced a spurious change animation when
                // toggling any other switch. Use notifyItemChanged if a refresh is needed.
                premiumStatusSwitchRow = addRow(5);
                replyBackgroundSwitchRow = addRow(6);
                replyColorSwitchRow = addRow(7);
                replyEmojiSwitchRow = addRow(8);
            }
            if (type == PAGE_PROFILE) {
                // Channel preview
                previewRow = addRow(18);
                addRow(19);
                if (AppConfig.profileChannelPreview) {
                    channelPreviewRow = addRow(20);
                    channelPreviewDivisorRow = addRow(21);
                }
                // Channel preview

                if (AppConfig.showIDDC
                        || AppConfig.profileBirthDatePreview
                        || AppConfig.profileBusinessPreview
                ) {
                    infoHeaderRow = addRow(22);
                }

                // DC ID
                if (AppConfig.showIDDC) {
                    idDcPreviewRow = addRow(23);
                }
                // DC ID

                // Birth date preview
                if (AppConfig.profileBirthDatePreview) {
                    birthdayPreviewRow = addRow(24);
                }
                // Birth date preview

                // Business preview
                if (AppConfig.profileBusinessPreview) {
                    businessHoursPreviewRow = addRow(25);
                }

                if (AppConfig.profileBusinessPreview) {
                    businessLocationPreviewRow = addRow(26);
                }
                // Business preview

                if (infoHeaderRow >= 0) profilePreviewDivisorRow = addRow(27);
                headerRow = addRow(28);
                channelPreviewSwitchRow = addRow(29);
                showDcIdSwitchRow = addRow(30);
                birthdayPreviewSwitchRow = addRow(31);
                businessPreviewSwitchRow = addRow(32);
                // premiumStatusSwitchRow is a static row — see PAGE_MESSAGE note above.
                premiumStatusSwitchRow = addRow(33);
                profileBackgroundSwitchRow = addRow(34);
                profileEmojiSwitchRow = addRow(35);
            }
            if (listAdapter != null) {
                if (previousRows.isEmpty() && rowCount > 0) {
                    listAdapter.notifyItemRangeInserted(0, rowCount);
                } else {
                    DiffUtil.calculateDiff(new DiffUtil.Callback() {
                        @Override
                        public int getOldListSize() {
                            return previousRows.size();
                        }

                        @Override
                        public int getNewListSize() {
                            return rowKeys.size();
                        }

                        @Override
                        public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                            return previousRows.get(oldItemPosition).equals(rowKeys.get(newItemPosition));
                        }

                        @Override
                        public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                            return true;
                        }
                    }).dispatchUpdatesTo(listAdapter);
                }
            }
        }

        private int addRow(int key) {
            int position = rowCount++;
            rowKeys.add(key);
            return position;
        }

        private void refreshPremiumStatusRow() {
            if (listAdapter != null && premiumStatusSwitchRow >= 0) {
                listAdapter.notifyItemChanged(premiumStatusSwitchRow);
            }
        }

        private int getSettingRow(int settingId) {
            int position = -1;
            if (type == PAGE_MESSAGE) {
                if (settingId == SETTING_SHOW_SECONDS) position = timeWithSecondsSwitchRow;
                else if (settingId == SETTING_PREMIUM_STATUSES) position = premiumStatusSwitchRow;
                else if (settingId == SETTING_REPLY_BACKGROUND) position = replyBackgroundSwitchRow;
                else if (settingId == SETTING_REPLY_COLORS) position = replyColorSwitchRow;
                else if (settingId == SETTING_REPLY_EMOJI) position = replyEmojiSwitchRow;
            } else {
                if (settingId == SETTING_PROFILE_CHANNEL) position = channelPreviewSwitchRow;
                else if (settingId == SETTING_PROFILE_ID_DC) position = showDcIdSwitchRow;
                else if (settingId == SETTING_PROFILE_BIRTHDAY) position = birthdayPreviewSwitchRow;
                else if (settingId == SETTING_PROFILE_BUSINESS) position = businessPreviewSwitchRow;
                else if (settingId == SETTING_PROFILE_COLOR) position = profileBackgroundSwitchRow;
                else if (settingId == SETTING_PROFILE_EMOJI) position = profileEmojiSwitchRow;
            }
            return position;
        }

        private void scrollToSetting(int settingId) {
            clearSettingHighlight();
            if (listView == null) return;
            settingHighlight = new SettingsSearchHighlight(listView, () -> getSettingRow(settingId));
        }

        private void clearSettingHighlight() {
            if (settingHighlight != null) {
                settingHighlight.run();
                settingHighlight = null;
            }
        }

        private void updateMessages() {
            if (messagesCellPreview != null) {
                ChatMessageCell[] cells = messagesCellPreview.getCells();
                for (ChatMessageCell cell : cells) {
                    if (cell != null) {
                        MessageObject msg = cell.getMessageObject();
                        if (msg != null) {
                            msg.overrideLinkColor = selectedColor;
                            msg.overrideLinkEmoji = selectedEmoji;
                            cell.setAvatar(msg);

                            if (cell.currentNameStatusDrawable == null) {
                                cell.currentNameStatusDrawable = new AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable(this, dp(26));
                            }
                            if (!AppConfig.disablePremiumStatuses) {
                                TLRPC.User currentUser = getUserConfig().getCurrentUser();
                                Long emojiStatusId = currentUser != null ? UserObject.getEmojiStatusDocumentId(currentUser.emoji_status) : null;
                                if (emojiStatusId != null) {
                                    cell.currentNameStatusDrawable.set(emojiStatusId, false);
                                    cell.currentNameStatusDrawable.play();
                                } else {
                                    cell.currentNameStatusDrawable.set(PremiumGradient.getInstance().premiumStarDrawableMini, false);
                                }
                            } else {
                                cell.currentNameStatusDrawable.set((Drawable) null, false);
                            }
                            cell.invalidate();
                        }
                    }
                }
            }
        }

        public void updateColors() {
            listView.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
            if (type == PAGE_PROFILE && colorBar != null) {
                colorBar.setColor(currentAccount, selectedColor, true);
            }
            if (messagesCellPreview != null) {
                messagesCellPreview.invalidate();
            }
            if (type == PAGE_PROFILE) {
                if (profileCard != null) profileCard.bind(selectedColor, selectedEmoji);
                AndroidUtilities.forEachViews(listView, view -> {
                    if (view instanceof ProfileChannelCell) {
                        ((ProfileChannelCell) view).updateColors();
                    }
                });
            }
        }

    }

    private Theme.ResourcesProvider parentResourcesProvider;
    private final SparseIntArray currentColors = new SparseIntArray();
    private final MessageDrawable msgInDrawable, msgInDrawableSelected;

    public MessagesAndProfilesPreferencesActivity() {
        super();

        resourceProvider = new Theme.ResourcesProvider() {
            @Override
            public int getColor(int key) {
                int index = currentColors.indexOfKey(key);
                if (index >= 0) {
                    return currentColors.valueAt(index);
                }
                if (parentResourcesProvider != null) {
                    return parentResourcesProvider.getColor(key);
                }
                return Theme.getColor(key);
            }

            @Override
            public Drawable getDrawable(String drawableKey) {
                if (drawableKey.equals(Theme.key_drawable_msgIn)) {
                    return msgInDrawable;
                }
                if (drawableKey.equals(Theme.key_drawable_msgInSelected)) {
                    return msgInDrawableSelected;
                }
                if (parentResourcesProvider != null) {
                    return parentResourcesProvider.getDrawable(drawableKey);
                }
                return Theme.getThemeDrawable(drawableKey);
            }

            @Override
            public Paint getPaint(String paintKey) {
                return Theme.ResourcesProvider.super.getPaint(paintKey);
            }
        };
        msgInDrawable = new MessageDrawable(MessageDrawable.TYPE_TEXT, false, false, resourceProvider);
        msgInDrawableSelected = new MessageDrawable(MessageDrawable.TYPE_TEXT, false, true, resourceProvider);
    }

    @Override
    public void setResourceProvider(Theme.ResourcesProvider resourceProvider) {
        parentResourcesProvider = resourceProvider;
    }

    private boolean startAtProfile;
    private int initialSettingId;

    public MessagesAndProfilesPreferencesActivity startOnProfile() {
        this.startAtProfile = true;
        return this;
    }

    public MessagesAndProfilesPreferencesActivity openAtSetting(int settingId) {
        initialSettingId = settingId;
        startAtProfile = settingId >= 100;
        return this;
    }

    @Override
    public boolean onFragmentCreate() {
        Bulletin.addDelegate(this, new Bulletin.Delegate() {
            @Override
            public int getBottomOffset(int tag) {
                return dp(62);
            }

            @Override
            public boolean clipWithGradient(int tag) {
                return true;
            }
        });
        getMediaDataController().loadReplyIcons();
        if (getMessagesController().peerColors == null && BuildVars.DEBUG_PRIVATE_VERSION) {
            getMessagesController().loadAppConfig(true);
        }
        return super.onFragmentCreate();
    }

    private ViewPagerFixed viewPager;
    private ImageView backButton;
    private MessageProfileLayout.Tabs tabsView;
    private View initialSettingScrollHost;
    private Runnable initialSettingScroll;

    private void cancelInitialSettingScroll() {
        if (initialSettingScrollHost != null && initialSettingScroll != null) {
            initialSettingScrollHost.removeCallbacks(initialSettingScroll);
        }
        initialSettingScrollHost = null;
        initialSettingScroll = null;
        if (messagePage != null) messagePage.clearSettingHighlight();
        if (profilePage != null) profilePage.clearSettingHighlight();
    }

    @Override
    public View createView(Context context) {
        cancelInitialSettingScroll();
        setHasOwnBackground(true);

        actionBar.setCastShadows(false);
        actionBar.setVisibility(View.GONE);
        actionBar.setAllowOverlayTitle(false);

        actionBar.setAddToContainer(false);

        colorBar = new ColoredActionBar(context, resourceProvider) {
            private int lastButtonColor;

            @Override
            protected void onUpdateColor() {
                if (colorBar != this) return;
                updateLightStatusBar();
                int color = getActionBarButtonColor();
                if (backButton != null && color != lastButtonColor) {
                    lastButtonColor = color;
                    backButton.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
                }
                if (tabsView != null) {
                    updateTabColors(tabsView);
                }
            }
        };
        messagePage = new Page(context, PAGE_MESSAGE);
        profilePage = new Page(context, PAGE_PROFILE);
        colorBar.setColor(currentAccount, profilePage.selectedColor, false);
        viewPager = new ViewPagerFixed(context) {
            @Override
            public void onTabAnimationUpdate(boolean manual) {
                if (viewPager != this || tabsView == null) return;
                float position = getPositionVisibility(PAGE_PROFILE);
                tabsView.setPosition(position);
                colorBar.setProgressToGradient(position);
            }
        };
        FrameLayout controls = new FrameLayout(context);
        tabsView = new MessageProfileLayout.Tabs(context, getString(R.string.Message), getString(R.string.UserColorTabProfile),
                tab -> viewPager.scrollToPosition(tab));
        controls.addView(tabsView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT, Gravity.CENTER, 54, 0, 54, 0));

        backButton = new ImageView(context);
        backButton.setScaleType(ImageView.ScaleType.CENTER);
        backButton.setContentDescription(getString(R.string.AccDescrGoBack));
        backButton.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_actionBarDefaultSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
        backButton.setImageResource(R.drawable.ic_ab_back);
        backButton.setOnClickListener(v -> {
            if (onBackPressed(true)) finishFragment();
        });
        controls.addView(backButton, LayoutHelper.createFrame(54, LayoutHelper.MATCH_PARENT, Gravity.LEFT | Gravity.CENTER_VERTICAL));

        contentView = new MessageProfileLayout(context, colorBar, controls, viewPager);
        fragmentView = contentView;
        contentView.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        contentView.setDividerColor(getThemedColor(Theme.key_divider));
        viewPager.setAdapter(new ViewPagerFixed.Adapter() {
            @Override
            public int getItemCount() { return 2; }

            @Override
            public View createView(int viewType) { return viewType == PAGE_PROFILE ? profilePage : messagePage; }

            @Override
            public int getItemViewType(int position) { return position; }

            @Override
            public void bindView(View view, int position, int viewType) {}
        });
        if (startAtProfile) viewPager.setPosition(PAGE_PROFILE);
        tabsView.setPosition(viewPager.getCurrentPosition());
        colorBar.setProgressToGradient(viewPager.getCurrentPosition());

        colorBar.updateColors();

        onInsets(mSystemInsets.left, mSystemInsets.top, mSystemInsets.right, mSystemInsets.bottom);
        ViewCompat.setOnApplyWindowInsetsListener(contentView, this::onInsetsInternal);
        ViewCompat.requestApplyInsets(contentView);
        if (initialSettingId != 0) {
            final View openingView = contentView;
            final Page targetPage = startAtProfile ? profilePage : messagePage;
            final int targetSetting = initialSettingId;
            initialSettingScrollHost = openingView;
            initialSettingScroll = () -> {
                if (initialSettingScrollHost != openingView || contentView != openingView || isFinished) return;
                initialSettingScrollHost = null;
                initialSettingScroll = null;
                targetPage.scrollToSetting(targetSetting);
            };
            openingView.post(initialSettingScroll);
        }

        // NG note: CG calls FirebaseAnalyticsHelper.INSTANCE.trackEventWithEmptyBundle here.
        // NimarkoGram ships no Firebase analytics, so the event tracking is dropped.

        return contentView;
    }

    @Override
    public void onFragmentClosed() {
        super.onFragmentClosed();
        Bulletin.removeDelegate(this);
    }

    @Override
    public void onPause() {
        cancelInitialSettingScroll();
        super.onPause();
    }

    @Override
    public void onFragmentDestroy() {
        cancelInitialSettingScroll();
        super.onFragmentDestroy();
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        return SimpleThemeDescription.createThemeDescriptions(this::updateColors,
                Theme.key_actionBarDefault,
                Theme.key_actionBarDefaultTitle,
                Theme.key_actionBarDefaultIcon,
                Theme.key_actionBarDefaultSelector,
                Theme.key_divider,
                Theme.key_windowBackgroundWhiteBlueText,
                Theme.key_windowBackgroundWhiteBlueIcon,
                Theme.key_windowBackgroundWhite,
                Theme.key_windowBackgroundWhiteBlackText,
                Theme.key_windowBackgroundWhiteGrayText2,
                Theme.key_listSelector,
                Theme.key_windowBackgroundGray,
                Theme.key_windowBackgroundWhiteGrayText4,
                Theme.key_text_RedRegular,
                Theme.key_windowBackgroundChecked,
                Theme.key_windowBackgroundCheckText,
                Theme.key_switchTrackBlue,
                Theme.key_switchTrackBlueChecked,
                Theme.key_switchTrackBlueThumb,
                Theme.key_switchTrackBlueThumbChecked
        );
    }

    @SuppressLint("NotifyDataSetChanged")
    private void updateColors() {
        contentView.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
        contentView.setDividerColor(getThemedColor(Theme.key_divider));
        backButton.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_actionBarDefaultSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
        messagePage.updateColors();
        profilePage.updateColors();
        if (colorBar != null) {
            colorBar.updateColors();
        }
        setNavigationBarColor(getNavigationBarColor());
    }

    private static class ColoredActionBar extends View {

        private int defaultColor;
        private final Theme.ResourcesProvider resourcesProvider;

        public ColoredActionBar(Context context, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            this.resourcesProvider = resourcesProvider;
            defaultColor = Theme.getColor(Theme.key_windowBackgroundGray, resourcesProvider);
            setColor(-1, -1, false);
        }

        public void setColor(int currentAccount, int colorId, boolean animated) {
            MessagesController.PeerColor peerColor = null;
            if (colorId >= 0 && currentAccount >= 0) {
                MessagesController.PeerColors peerColors = MessagesController.getInstance(currentAccount).profilePeerColors;
                peerColor = peerColors == null ? null : peerColors.getColor(colorId);
            }
            setColor(peerColor, animated);
        }

        public void setColor(MessagesController.PeerColor peerColor, boolean animated) {
            isDefault = false;
            if (peerColor == null) {
                isDefault = true;
                color1 = color2 = Theme.getColor(Theme.key_actionBarDefault, resourcesProvider);
            } else {
                final boolean isDark = resourcesProvider != null ? resourcesProvider.isDark() : Theme.isCurrentThemeDark();
                color1 = peerColor.getBgColor1(isDark);
                color2 = peerColor.getBgColor2(isDark);
            }
            if (!animated) {
                color1Animated.set(color1, true);
                color2Animated.set(color2, true);
            }
            invalidate();
        }

        private float progressToGradient = 0;
        public void setProgressToGradient(float progress) {
            if (Math.abs(progressToGradient - progress) > 0.001f) {
                progressToGradient = progress;
                onUpdateColor();
                invalidate();
            }
        }

        public boolean isDefault;
        public int color1, color2;
        private final AnimatedColor color1Animated = new AnimatedColor(this, 350, CubicBezierInterpolator.EASE_OUT_QUINT);
        private final AnimatedColor color2Animated = new AnimatedColor(this, 350, CubicBezierInterpolator.EASE_OUT_QUINT);

        private int backgroundGradientColor1, backgroundGradientColor2, backgroundGradientWidth, backgroundGradientHeight;
        private RadialGradient backgroundGradient;
        private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        protected void onUpdateColor() {

        }

        @Override
        protected void dispatchDraw(@NonNull Canvas canvas) {
            if (getWidth() == 0 || getHeight() == 0) return;
            final int color1 = color1Animated.set(this.color1);
            final int color2 = color2Animated.set(this.color2);
            if (backgroundGradient == null || backgroundGradientColor1 != color1 || backgroundGradientColor2 != color2 || backgroundGradientWidth != getWidth() || backgroundGradientHeight != getHeight()) {
                backgroundGradientWidth = getWidth();
                backgroundGradientHeight = getHeight();
                backgroundGradient = new RadialGradient(
                    backgroundGradientWidth / 2f, backgroundGradientHeight * 0.40f,
                    distance(0, 0, backgroundGradientWidth, backgroundGradientHeight) * 0.75f,
                    new int[] { backgroundGradientColor2 = color2, backgroundGradientColor1 = color1 },
                    new float[] { 0, 1 },
                    Shader.TileMode.CLAMP
                );
                backgroundPaint.setShader(backgroundGradient);
                onUpdateColor();
            }
            if (progressToGradient < 1) {
                canvas.drawColor(defaultColor);
            }
            if (progressToGradient > 0) {
                backgroundPaint.setAlpha((int) (0xFF * progressToGradient));
                canvas.drawRect(0, 0, getWidth(), getHeight(), backgroundPaint);
            }
        }


        public void updateColors() {
            defaultColor = Theme.getColor(Theme.key_windowBackgroundGray, resourcesProvider);
            onUpdateColor();
            invalidate();
        }

        public int getColor() {
            return ColorUtils.blendARGB(defaultColor, ColorUtils.blendARGB(color1Animated.get(), color2Animated.get(), .75f), progressToGradient);
        }

        void updateTabColors(MessageProfileLayout.Tabs tabs) {
            int header = ColorUtils.setAlphaComponent(getColor(), 255);
            int track = Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider);
            int pill = defaultColor;
            int normal = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider);
            int selected = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider);
            if (!isDefault && progressToGradient > 0f) {
                int profileColor = ColorUtils.blendARGB(color1Animated.get(), color2Animated.get(), .75f);
                int tint = AndroidUtilities.computePerceivedBrightness(profileColor) > .721f
                        ? Theme.getColor(Theme.key_windowBackgroundWhiteBlueIcon, resourcesProvider)
                        : Theme.adaptHSV(profileColor, .08f, -.08f);
                tint = MessageProfileLayout.Tabs.tabInk(tint, Color.WHITE);
                track = ColorUtils.blendARGB(track, tint, progressToGradient);
                pill = ColorUtils.blendARGB(pill, Color.WHITE, progressToGradient);
                normal = ColorUtils.blendARGB(normal, Color.WHITE, progressToGradient);
                selected = ColorUtils.blendARGB(selected, tint, progressToGradient);
            }
            track = ColorUtils.compositeColors(track, header);
            tabs.setColors(track, ColorUtils.compositeColors(pill, track), normal, selected);
        }

        public int getActionBarButtonColor() {
            return MessageProfileLayout.readableColor(Theme.getColor(Theme.key_actionBarDefaultIcon, resourcesProvider), getColor());
        }
    }

    private class ProfilePreviewCard extends FrameLayout {
        private final ColoredActionBar backdrop;
        private final ProfilePreview preview;

        ProfilePreviewCard(Context context) {
            super(context);
            setLayoutParams(new RecyclerView.LayoutParams(LayoutHelper.MATCH_PARENT, dp(184)));
            setBackground(Theme.createRoundRectDrawable(dp(16), Color.TRANSPARENT));
            setClipToOutline(true);
            backdrop = new ColoredActionBar(context, resourceProvider);
            backdrop.setProgressToGradient(1f);
            addView(backdrop, LayoutHelper.createFrameMatchParent());
            preview = new ProfilePreview(context, currentAccount, resourceProvider);
            addView(preview, LayoutHelper.createFrameMatchParent());
        }

        void setColor(int colorId, boolean animated) {
            MessagesController.PeerColors colors = getMessagesController().profilePeerColors;
            MessagesController.PeerColor color = colorId < 0 || colors == null ? null : colors.getColor(colorId);
            backdrop.setColor(color, animated);
            preview.setColor(color, animated);
        }

        void bind(int color, long emoji) {
            setColor(color, false);
            preview.setEmoji(emoji, false);
            if (messagePage != null) preview.overrideAvatarColor(messagePage.selectedColor);
            preview.updatePremiumStatus(false);
        }
    }

    static class PreviewNameRow extends SimpleTextView {
        PreviewNameRow(Context context) {
            super(context);
            setWidthWrapContent(true);
            setRightDrawableOutside(true);
            setOutsideRightDrawableTextClipInset(0);
        }

        @Override
        protected int getOutsideRightDrawablesStartX(int textOffsetX) {
            return Math.max(0, getMeasuredWidth() - getRightDrawablesWidth());
        }
    }

    private class ProfilePreview extends FrameLayout {

        private final Theme.ResourcesProvider resourcesProvider;
        private final int previewAccount;
        private final TLRPC.User previewUser;

        protected final ImageReceiver imageReceiver = new ImageReceiver(this);
        protected final AvatarDrawable avatarDrawable = new AvatarDrawable();
        protected final SimpleTextView titleView, subtitleView;

        private final AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable statusEmoji;
        private final AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable emoji = new AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable(this, false, dp(20), AnimatedEmojiDrawable.CACHE_TYPE_ALERT_PREVIEW_STATIC);
        private final StoriesUtilities.StoryGradientTools storyGradient = new StoriesUtilities.StoryGradientTools(this, false);

        public ProfilePreview(Context context, int currentAccount, Theme.ResourcesProvider resourcesProvider) {
            super(context);

            this.resourcesProvider = resourcesProvider;
            this.previewAccount = currentAccount;
            this.previewUser = UserConfig.getInstance(currentAccount).getCurrentUser();

            titleView = new PreviewNameRow(context) {
                @Override
                protected void onAttachedToWindow() {
                    super.onAttachedToWindow();
                    statusEmoji.attach();
                }

                @Override
                protected void onDetachedFromWindow() {
                    super.onDetachedFromWindow();
                    statusEmoji.detach();
                }
            };
            statusEmoji = new AnimatedEmojiDrawable.SwapAnimatedEmojiDrawable(titleView, dp(24));
            titleView.setRightDrawableOnClick(v -> statusEmoji.play());
            titleView.setTextColor(0xFFFFFFFF);
            titleView.setTextSize(20);
            titleView.setTypeface(AndroidUtilities.bold());
            addView(titleView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM, 16, 0, 16, 40.33f));

            subtitleView = new SimpleTextView(context);
            subtitleView.setTextSize(14);
            subtitleView.setTextColor(0x80FFFFFF);
            subtitleView.setGravity(Gravity.CENTER_HORIZONTAL);
            addView(subtitleView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM, 16, 0, 16, 20.66f));

            imageReceiver.setRoundRadius(dp(96));
            CharSequence title;
            TLRPC.User user = previewUser;
            title = user != null ? UserObject.getUserName(user) : getString(R.string.AppName);

            if (user != null) {
                avatarDrawable.setInfo(currentAccount, user);
                imageReceiver.setForUserOrChat(user, avatarDrawable);
            } else {
                avatarDrawable.setInfo(0, getString(R.string.AppName), null);
                imageReceiver.setImageBitmap(avatarDrawable);
            }
            // NG: the status emoji drawable was created but never given any content, so the star next to the
            // nick rendered as nothing. Feed it the user's custom emoji status if set, otherwise the default
            // Telegram premium star — so toggling premium statuses back ON actually shows the star.
            try {
                Long statusDocId = user != null ? UserObject.getEmojiStatusDocumentId(user.emoji_status) : null;
                if (statusDocId != null && statusDocId != 0) {
                    statusEmoji.set(statusDocId, false);
                } else if (user != null && user.premium) {
                    statusEmoji.set(PremiumGradient.getInstance().premiumStarDrawableMini, false);
                }
            } catch (Throwable ignored) {}
            try {
                title = Emoji.replaceEmoji(title, null, false);
            } catch (Exception ignore) {
            }

            titleView.setText(title);
            updatePremiumStatus(false);

            setWillNotDraw(false);
        }

        void updatePremiumStatus(boolean animate) {
            Drawable badge = AppConfig.disablePremiumStatuses ? null : statusEmoji;
            if (titleView.getRightDrawable() != badge) {
                titleView.setRightDrawable(badge);
                titleView.requestLayout();
            }
            if (animate && badge != null) statusEmoji.play();
            String suffix = AppConfig.disablePremiumStatuses ? " | " + getString(R.string.TelegramPremium) : "";
            subtitleView.setText(getString(R.string.Online) + suffix);
        }

        public void overrideAvatarColor(int colorId) {
            if (colorId < 0) {
                if (previewUser != null) avatarDrawable.setInfo(previewAccount, previewUser);
                else avatarDrawable.setInfo(0, getString(R.string.AppName), null);
                invalidate();
                return;
            }
            final int color1, color2;
            if (colorId >= 14) {
                MessagesController.PeerColors peerColors = getMessagesController().peerColors;
                MessagesController.PeerColor peerColor = peerColors != null ? peerColors.getColor(colorId) : null;
                if (peerColor != null) {
                    final int peerColorValue = peerColor.getColor1();
                    color1 = getThemedColor(Theme.keys_avatar_background[AvatarDrawable.getPeerColorIndex(peerColorValue)]);
                    color2 = getThemedColor(Theme.keys_avatar_background2[AvatarDrawable.getPeerColorIndex(peerColorValue)]);
                } else {
                    color1 = getThemedColor(Theme.keys_avatar_background[AvatarDrawable.getColorIndex(colorId)]);
                    color2 = getThemedColor(Theme.keys_avatar_background2[AvatarDrawable.getColorIndex(colorId)]);
                }
            } else {
                color1 = getThemedColor(Theme.keys_avatar_background[AvatarDrawable.getColorIndex(colorId)]);
                color2 = getThemedColor(Theme.keys_avatar_background2[AvatarDrawable.getColorIndex(colorId)]);
            }
            avatarDrawable.setColor(color1, color2);
            invalidate();
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            emoji.attach();
            imageReceiver.onAttachedToWindow();
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            emoji.detach();
            imageReceiver.onDetachedFromWindow();
        }

        private int getThemedColor(int key) {
            return Theme.getColor(key, resourcesProvider);
        }

        private MessagesController.PeerColor peerColor;

        public void setColor(MessagesController.PeerColor peerColor, boolean animated) {
            this.peerColor = peerColor;
            final boolean isDark = resourcesProvider != null ? resourcesProvider.isDark() : Theme.isCurrentThemeDark();
            if (peerColor != null) {
                if (peerColor.patternColor != 0) {
                    emoji.setColor(peerColor.patternColor);
                } else {
                    emoji.setColor(adaptProfileEmojiColor(peerColor.getBgColor1(isDark)));
                }
                statusEmoji.setColor(ColorUtils.blendARGB(peerColor.getStoryColor1(Theme.isCurrentThemeDark()), 0xFFFFFFFF, 0.25f));
                final int accentColor = ColorUtils.blendARGB(peerColor.getStoryColor1(isDark), peerColor.getStoryColor2(isDark), .5f);
                if (!Theme.hasHue(getThemedColor(Theme.key_actionBarDefault))) {
                    subtitleView.setTextColor(accentColor);
                } else {
                    subtitleView.setTextColor(Theme.changeColorAccent(getThemedColor(Theme.key_actionBarDefault), accentColor, getThemedColor(Theme.key_avatar_subtitleInProfileBlue), isDark, accentColor));
                }
                titleView.setTextColor(Color.WHITE);
            } else {
                if (AndroidUtilities.computePerceivedBrightness(getThemedColor(Theme.key_actionBarDefault)) > .8f) {
                    emoji.setColor(getThemedColor(Theme.key_windowBackgroundWhiteBlueText));
                } else if (AndroidUtilities.computePerceivedBrightness(getThemedColor(Theme.key_actionBarDefault)) < .2f) {
                    emoji.setColor(Theme.multAlpha(getThemedColor(Theme.key_actionBarDefaultTitle), .5f));
                } else {
                    emoji.setColor(adaptProfileEmojiColor(getThemedColor(Theme.key_actionBarDefault)));
                }
                statusEmoji.setColor(Theme.getColor(Theme.key_profile_verifiedBackground, resourcesProvider));
                subtitleView.setTextColor(getThemedColor(Theme.key_actionBarDefaultSubtitle));
                titleView.setTextColor(getThemedColor(Theme.key_actionBarDefaultTitle));
            }

            storyGradient.setColor(peerColor, animated);
            invalidate();
        }

        public void setEmoji(long docId, boolean animated) {
            if (docId == 0) {
                emoji.set((Drawable) null, animated);
            } else {
                emoji.set(docId, animated);
            }
            final boolean isDark = resourcesProvider != null ? resourcesProvider.isDark() : Theme.isCurrentThemeDark();
            if (peerColor != null) {
                if (peerColor.patternColor != 0) {
                    emoji.setColor(peerColor.patternColor);
                } else {
                    emoji.setColor(adaptProfileEmojiColor(peerColor.getBgColor1(isDark)));
                }
            } else if (AndroidUtilities.computePerceivedBrightness(getThemedColor(Theme.key_actionBarDefault)) > .8f) {
                emoji.setColor(getThemedColor(Theme.key_windowBackgroundWhiteBlueText));
            } else if (AndroidUtilities.computePerceivedBrightness(getThemedColor(Theme.key_actionBarDefault)) < .2f) {
                emoji.setColor(Theme.multAlpha(Theme.getColor(Theme.key_actionBarDefaultTitle), .5f));
            } else {
                emoji.setColor(adaptProfileEmojiColor(Theme.getColor(Theme.key_actionBarDefault)));
            }
            if (peerColor != null) {
                statusEmoji.setColor(ColorUtils.blendARGB(peerColor.getColor(1, resourcesProvider), peerColor.hasColor6(isDark) ? peerColor.getColor(4, resourcesProvider) : peerColor.getColor(2, resourcesProvider), .5f));
            } else {
                statusEmoji.setColor(Theme.getColor(Theme.key_profile_verifiedBackground, resourcesProvider));
            }
            invalidate();
        }

        private final RectF rectF = new RectF();
        @Override
        protected void dispatchDraw(@NonNull Canvas canvas) {
            rectF.set(
                (getWidth() - dp(86)) / 2f,
                getHeight() - dp(82 + 86),
                (getWidth() + dp(86)) / 2f,
                getHeight() - dp(82)
            );
            imageReceiver.setRoundRadius(dp(54));
            imageReceiver.setImageCoords(rectF);
            imageReceiver.draw(canvas);

            final float r = rectF.width() / 2f + dp(4);
            final float rr = dp(58);
            canvas.drawRoundRect(
                rectF.centerX() - r,
                rectF.centerY() - r,
                rectF.centerX() + r,
                rectF.centerY() + r,
                rr, rr,
                storyGradient.getPaint(rectF)
            );

            StarGiftPatterns.drawProfileAnimatedPattern(
                canvas,
                emoji,
                getWidth(),
                getHeight(),
                1.0f,
                rectF,
                1.0f
            );

            super.dispatchDraw(canvas);
        }
    }

    public int adaptProfileEmojiColor(int color) {
        final boolean isDark = AndroidUtilities.computePerceivedBrightness(color) < .2f;
        return Theme.adaptHSV(color, +.5f, isDark ? +.28f : -.28f);
    }

    private String birthdayString() {
        Calendar calendar = Calendar.getInstance();
        calendar.set(Calendar.YEAR, 2022);
        calendar.set(Calendar.MONTH, 0);
        calendar.set(Calendar.DAY_OF_MONTH, 15);
        return LocaleController.getInstance().getFormatterBoostExpired().format(calendar.getTimeInMillis());
    }

    public int processColor(int color) {
        return color;
    }

    @Override
    public boolean isLightStatusBar() {
        if (colorBar == null) {
            return super.isLightStatusBar();
        }
        return MessageProfileLayout.readableColor(Color.WHITE, colorBar.getColor()) == Color.BLACK;
    }

    public void updateLightStatusBar() {
        if (getParentActivity() == null) return;
        AndroidUtilities.setLightStatusBar(getParentActivity().getWindow(), isLightStatusBar());
    }

    @Override
    public boolean isActionBarCrossfadeEnabled() {
        return false;
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    public boolean drawEdgeNavigationBar() {
        return false;
    }

    // ---- DC name / geo (inlined from CG CGResourcesHelper.getDCName/getDCGeo) ----

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        if (contentView != null) contentView.setSafeArea(left, top, right);
        if (messagePage != null) messagePage.setBottomInset(bottom);
        if (profilePage != null) profilePage.setBottomInset(bottom);
    }

}
