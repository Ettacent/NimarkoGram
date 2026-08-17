 
package app.nimarkogram.messenger.utils;

import static org.telegram.messenger.LocaleController.getString;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

import org.telegram.PhoneFormat.PhoneFormat;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BaseController;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.EmojiPacksAlert;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.TypefaceSpan;
import org.telegram.ui.PeerColorActivity;
import org.telegram.ui.Stories.ChannelBoostUtilities;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicLong;

import app.nimarkogram.messenger.NimarkoConfig;

public class NimarkoProfileActivityHelper extends BaseController {

    private static final NimarkoProfileActivityHelper[] Instance = new NimarkoProfileActivityHelper[UserConfig.MAX_ACCOUNT_COUNT];
    private final AtomicLong profileBackgroundRequestId = new AtomicLong();
    private final AtomicLong applyProfileBackgroundRequestId = new AtomicLong();

    public NimarkoProfileActivityHelper(int num) {
        super(num);
    }

    public static synchronized NimarkoProfileActivityHelper getInstance(int num) {
        NimarkoProfileActivityHelper localInstance = Instance[num];
        if (localInstance == null) {
            Instance[num] = localInstance = new NimarkoProfileActivityHelper(num);
        }
        return localInstance;
    }

    private boolean isFragmentOwnerLive(
            BaseFragment fragment,
            View ownerView,
            Activity ownerActivity,
            long ownerUserId
    ) {
        return fragment != null
                && ownerView != null
                && ownerActivity != null
                && ownerUserId > 0
                && fragment.getCurrentAccount() == currentAccount
                && getUserConfig().getClientUserId() == ownerUserId
                && !fragment.isFinished
                && !fragment.isRemovingFromStack()
                && fragment.isLastFragment()
                && fragment.getFragmentView() == ownerView
                && ownerView.isAttachedToWindow()
                && fragment.getParentActivity() == ownerActivity
                && !ownerActivity.isFinishing()
                && (Build.VERSION.SDK_INT < Build.VERSION_CODES.JELLY_BEAN_MR1
                || !ownerActivity.isDestroyed());
    }

    public final static int OPTION_RESTART = 1000;
    public final static int OPTION_BOOST_CHANNEL = 1001;
    public final static int OPTION_GET_PROFILE_BACKGROUND = 1002;
    public final static int OPTION_APPLY_PROFILE_BACKGROUND = 1003;
    public final static int OPTION_USER_INFO = 1004;

    public void injectProfileMenu(
            ActionBarMenuItem otherItem,
            TLRPC.User user,
            TLRPC.EncryptedChat currentEncryptedChat,
            boolean isBot
    ) {
        if (otherItem == null) return;
        otherItem.addColoredGap();

        if (user != null) {
            long emojiDocumentId = UserObject.getProfileEmojiId(user);
            if (!UserObject.isUserSelf(user) && currentEncryptedChat == null && !isBot) {
                if (emojiDocumentId != 0 && NimarkoConfig.profileBackgroundEmoji) {
                    otherItem.addSubItem(
                            OPTION_GET_PROFILE_BACKGROUND,
                            R.drawable.msg_emoji_stickers,
                            getString(R.string.NM_GetProfileBackground)
                    );
                }
                if (emojiDocumentId != 0
                        && getUserConfig().isPremium()
                        && UserObject.getProfileEmojiId(getUserConfig().getCurrentUser()) != emojiDocumentId
                        && NimarkoConfig.profileBackgroundEmoji) {
                    otherItem.addSubItem(
                            OPTION_APPLY_PROFILE_BACKGROUND,
                            R.drawable.msg_emoji_stickers,
                            getString(R.string.NM_ApplyProfileBackground)
                    );
                }
            }
        }

        otherItem.addSubItem(
                OPTION_USER_INFO,
                R.drawable.icon_json_solar,
                getString(R.string.NM_UserInfo)
        );
    }

    public void injectChatInfo(ActionBarMenuItem otherItem) {
        if (otherItem == null) return;
        otherItem.addColoredGap();
        otherItem.addSubItem(
                OPTION_USER_INFO,
                R.drawable.icon_json_solar,
                getString(R.string.NM_UserInfo)
        );
    }

    public void injectRestart(ActionBarMenuItem otherItem) {
        if (otherItem == null) return;
        otherItem.addSubItem(
                OPTION_RESTART,
                R.drawable.msg_retry,
                getString(R.string.NM_Restart)
        );
    }

    public void injectBoostChannel(ActionBarMenuItem otherItem) {
        if (otherItem == null) return;
        otherItem.addSubItem(
                OPTION_BOOST_CHANNEL,
                R.drawable.msg_premium_prolfilestar,
                getString(R.string.BoostChannel)
        );
    }

    public void injectPhoneNumber(
            BaseFragment fragment,
            ItemOptions itemOptions,
            String phone
    ) {
        if (fragment == null || itemOptions == null || phone == null) return;
        itemOptions.addGap();

        TextView phoneInfoView = new TextView(fragment.getContext());
        phoneInfoView.setPadding(AndroidUtilities.dp(13), AndroidUtilities.dp(8), AndroidUtilities.dp(13), AndroidUtilities.dp(8));
        phoneInfoView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        phoneInfoView.setTextColor(Theme.getColor(Theme.key_actionBarDefaultSubmenuItem, fragment.getResourceProvider()));
        phoneInfoView.setLinkTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteValueText, fragment.getResourceProvider()));
        phoneInfoView.setBackground(Theme.createRadSelectorDrawable(Theme.getColor(Theme.key_dialogButtonSelector, fragment.getResourceProvider()), 0, 6));

        boolean isFragmentPhoneNumber = phone.matches("888\\d{8}");

        String phoneInfoString = LocaleController.getString(isFragmentPhoneNumber ? R.string.AnonymousNumber : R.string.PhoneMobile)
                + ": "
                + "*"
                + PhoneFormat.getInstance().format("+" + phone)
                + "*";

        SpannableStringBuilder spanned = new SpannableStringBuilder(AndroidUtilities.replaceTags(phoneInfoString));

        int startIndex = TextUtils.indexOf(spanned, '*');
        int lastIndex = TextUtils.lastIndexOf(spanned, '*');
        if (startIndex != -1 && lastIndex != -1 && startIndex != lastIndex) {
            spanned.replace(lastIndex, lastIndex + 1, "");
            spanned.replace(startIndex, startIndex + 1, "");
            spanned.setSpan(new TypefaceSpan(AndroidUtilities.bold()), startIndex, lastIndex - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            spanned.setSpan(new ForegroundColorSpan(phoneInfoView.getLinkTextColors().getDefaultColor()), startIndex, lastIndex - 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        phoneInfoView.setText(spanned);
        phoneInfoView.setOnClickListener(v -> {
            try {
                Intent intent = new Intent(Intent.ACTION_DIAL, Uri.parse("tel:+" + phone));
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                fragment.getParentActivity().startActivityForResult(intent, 500);
            } catch (Exception e) {
                FileLog.e(e);
            }
            itemOptions.dismiss();
        });

        itemOptions.addView(phoneInfoView);
    }

    public void restartApp(Context context) {
        AppRestartHelper.restartApp(context);
    }

    public void boostChannel(Context context, long dialogID) {
        if (context == null) return;
        Browser.openUrl(context, ChannelBoostUtilities.createLink(currentAccount, dialogID));
    }

    public void getProfileBackground(BaseFragment fragment, long dialogID) {
        final long requestId = profileBackgroundRequestId.incrementAndGet();
        if (fragment == null) {
            return;
        }

        final View ownerView = fragment.getFragmentView();
        final Activity ownerActivity = fragment.getParentActivity();
        final long ownerUserId = getUserConfig().getClientUserId();
        if (!isFragmentOwnerLive(fragment, ownerView, ownerActivity, ownerUserId)
                || fragment.getResourceProvider() == null) {
            return;
        }

        TLRPC.User sourceUser = getMessagesController().getUser(dialogID);
        long emojiDocumentId = UserObject.getProfileEmojiId(sourceUser);
        if (emojiDocumentId == 0) return;

        AnimatedEmojiDrawable.getDocumentFetcher(currentAccount).fetchDocument(
                emojiDocumentId,
                document -> AndroidUtilities.runOnUIThread(() -> {
                    if (document == null
                            || profileBackgroundRequestId.get() != requestId
                            || !isFragmentOwnerLive(
                                    fragment, ownerView, ownerActivity, ownerUserId)
                            || fragment.getResourceProvider() == null) {
                        return;
                    }
                    TLRPC.InputStickerSet inputStickerSet =
                            MessageObject.getInputStickerSet(document);
                    if (inputStickerSet == null) return;
                    ArrayList<TLRPC.InputStickerSet> inputSets = new ArrayList<>(1);
                    inputSets.add(inputStickerSet);
                    EmojiPacksAlert alert = new EmojiPacksAlert(
                            fragment,
                            ownerActivity,
                            fragment.getResourceProvider(),
                            inputSets
                    );
                    alert.show();
                })
        );
    }

    public void applyProfileBackground(BaseFragment fragment, long dialogID) {
        final long requestId = applyProfileBackgroundRequestId.incrementAndGet();
        if (fragment == null) return;

        final View ownerView = fragment.getFragmentView();
        final Activity ownerActivity = fragment.getParentActivity();
        final long ownerUserId = getUserConfig().getClientUserId();
        if (!isFragmentOwnerLive(fragment, ownerView, ownerActivity, ownerUserId)) {
            return;
        }

        TLRPC.User sourceUser = getMessagesController().getUser(dialogID);
        long emojiDocumentId = UserObject.getProfileEmojiId(sourceUser);
        int colorId = UserObject.getProfileColorId(sourceUser);
        TLRPC.User me = getUserConfig().getCurrentUser();
        if (me == null) return;

        if (me.profile_color == null) {
            me.profile_color = new TLRPC.PeerColor();
        }
        TL_account.updateColor req = new TL_account.updateColor();
        req.for_profile = true;
        me.flags2 |= 512;

        if (colorId >= 0) {
            me.profile_color.flags |= 1;
            if (req.color == null) {
                req.flags |= 4;
                req.color = new TLRPC.TL_peerColor();
            }
            req.color.flags |= 1;
            req.color.color = me.profile_color.color = colorId;
        } else {
            me.profile_color.flags &= ~1;
        }

        if (emojiDocumentId != 0) {
            me.profile_color.flags |= 2;
            if (req.color == null) {
                req.flags |= 4;
                req.color = new TLRPC.TL_peerColor();
            }
            req.color.flags |= 2;
            req.color.background_emoji_id = me.profile_color.background_emoji_id = emojiDocumentId;
        } else {
            me.profile_color.flags &= ~2;
            me.profile_color.background_emoji_id = 0;
            if (req.color != null) {
                req.color.flags &= ~2;
                req.color.background_emoji_id = 0;
            }
        }

        getConnectionsManager().sendRequest(req, (res, err) -> {
            if (res != null) {
                AndroidUtilities.runOnUIThread(
                        () -> {
                            if (applyProfileBackgroundRequestId.get() != requestId
                                    || !isFragmentOwnerLive(
                                            fragment, ownerView, ownerActivity, ownerUserId)
                                    || UserConfig.selectedAccount != currentAccount) {
                                return;
                            }
                            PeerColorActivity colorActivity = new PeerColorActivity(0);
                            colorActivity.setCurrentAccount(currentAccount);
                            fragment.presentFragment(
                                    colorActivity.startOnProfile().setOnApplied(fragment));
                        },
                        300
                );
            }
        });
    }

    public void showUserInfo(BaseFragment baseFragment, long userID) {
        if (baseFragment == null
                || baseFragment.getCurrentAccount() != currentAccount
                || baseFragment.getParentActivity() == null) {
            return;
        }

        TLRPC.User user = getMessagesController().getUser(userID);

        StringBuilder sb = new StringBuilder();
        sb.append("ID: ").append(userID);

        sb.append('\n').append(NimarkoExtra.getProfileDC(currentAccount, user, null));

        NimarkoExtra.RegistrationInfo regInfo = NimarkoExtra.resolveRegistrationInfo(baseFragment, userID);
        if (regInfo.unixTimestamp > 0L) {
            sb.append("\nRegistered: ").append(regInfo.formattedDate);
            if (!regInfo.dayOfWeek.isEmpty()) {
                sb.append(" (").append(regInfo.dayOfWeek).append(')');
            }
            if (!regInfo.relativeYearsAgo.isEmpty()) {
                sb.append(" — ").append(regInfo.relativeYearsAgo);
            }
        } else if (regInfo.rawTelegramMonth != null) {
            sb.append("\nRegistered: ").append(regInfo.rawTelegramMonth);
        }

        String pattern = LastSeenTracker.getPatternHint(currentAccount, userID);
        if (pattern != null) {
            sb.append('\n').append(pattern);
        }

        if (user != null) {
            sb.append("\n\nPremium: ").append(user.premium);
            sb.append("\nVerified: ").append(user.verified);
            sb.append("\nBot: ").append(user.bot);
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(
                baseFragment.getParentActivity(),
                baseFragment.getResourceProvider()
        );
        builder.setTitle(getString(R.string.Info));
        builder.setMessage(sb);
        builder.setPositiveButton(getString(R.string.OK), null);
        baseFragment.showDialog(builder.create());
    }
     
    public void showRestrictionReason(BaseFragment baseFragment, TLRPC.Chat chat) {
        if (baseFragment == null || baseFragment.getParentActivity() == null || chat == null) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(
                baseFragment.getParentActivity(),
                baseFragment.getResourceProvider()
        );
        builder.setTitle(getString(R.string.Info));

        String formatted = getRestrictionReasons(chat.restriction_reason);
        if (formatted != null && formatted.length() > 0) {
            builder.setMessage(formatted);
        } else {
            builder.setMessage("Chat or channel is not restricted.");
        }

        builder.setPositiveButton(getString(R.string.OK), null);
        baseFragment.showDialog(builder.create());
    }
     
    public static String getRestrictionReasons(ArrayList<TLRPC.RestrictionReason> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            return null;
        }

        StringBuilder sb = new StringBuilder();

        for (TLRPC.RestrictionReason reason : reasons) {
            sb.append("Platform: ").append(reason.platform)
                    .append("\nReason: ").append(reason.reason)
                    .append("\nText: ").append(reason.text)
                    .append("\n\n");
        }

        return sb.toString().trim();
    }

}
