package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.lerp;
import static org.telegram.messenger.LocaleController.getString;


import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.DynamicDrawableSpan;
import android.text.style.ImageSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.collection.LongSparseArray;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import org.telegram.ui.recyclerview.LinearSmoothScrollerCustom;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AccountInstance;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.AnimationNotificationsLocker;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.TopicsController;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.utils.OnPostDrawView;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.tgnet.tl.TL_stories;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.ActionBarMenuSubItem;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Adapters.DialogsAdapter;
import org.telegram.ui.Adapters.FiltersView;
import org.telegram.ui.Cells.DialogCell;
import org.telegram.ui.Cells.GraySectionCell;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ProfileSearchCell;
import org.telegram.ui.Cells.TopicSearchCell;
import org.telegram.ui.Cells.UserCell;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.AnimatedEmojiDrawable;
import org.telegram.ui.Components.BlurredRecyclerView;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ChatActivityInterface;
import org.telegram.ui.Components.ChatAvatarContainer;
import org.telegram.ui.Components.ChatNotificationsPopupWrapper;
import org.telegram.ui.Components.ColoredImageSpan;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.DialogsActivityTopPanelLayout;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.FlickerLoadingView;
import org.telegram.ui.Components.Forum.ForumBubbleDrawable;
import org.telegram.ui.Components.Forum.ForumUtilities;
import org.telegram.ui.Components.FragmentContextView;
import org.telegram.ui.Components.FragmentFloatingButton;
import org.telegram.ui.Components.InviteMembersBottomSheet;
import org.telegram.ui.Components.JoinGroupAlert;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.ListView.AdapterWithDiffUtils;
import org.telegram.ui.Components.PullForegroundDrawable;
import org.telegram.ui.Components.RLottieDrawable;
import org.telegram.ui.Components.RadialProgressView;
import org.telegram.ui.Components.RecyclerAnimationScrollHelper;
import org.telegram.ui.Components.RecyclerItemsEnterAnimator;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.SearchDownloadsContainer;
import org.telegram.ui.Components.SearchViewPager;
import org.telegram.ui.Components.SizeNotifierFrameLayout;
import org.telegram.ui.Components.StickerEmptyView;
import org.telegram.ui.Components.UnreadCounterTextView;
import org.telegram.ui.Components.ViewPagerFixed;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.DownscaleScrollableNoiseSuppressor;
import org.telegram.ui.Components.blur3.ViewGroupPartRenderer;
import org.telegram.ui.Components.blur3.capture.IBlur3Capture;
import org.telegram.ui.Components.blur3.capture.IBlur3Hash;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceColor;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceRenderNode;
import org.telegram.ui.Components.chat.ViewPositionWatcher;
import org.telegram.ui.Components.voip.VoIPHelper;
import org.telegram.ui.Delegates.ChatActivityMemberRequestsDelegate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.concurrent.CountDownLatch;

public class TopicsFragment extends BaseFragment implements NotificationCenter.NotificationCenterDelegate, ChatActivityInterface, RightSlidingDialogContainer.BaseFragmentWithFullscreen, MainTabsActivity.TabFragmentDelegate {

    @Override
    public boolean isActionBarCrossfadeEnabled() {
        // This fragment animates its own glass identity island. Letting
        // ActionBarLayout manually draw it a second time duplicates the
        // title/avatar over profile and interrupted back transitions.
        return false;
    }

    private final static int BOTTOM_BUTTON_TYPE_JOIN = 0;
    private final static int BOTTOM_BUTTON_TYPE_REPORT = 1;
    final long chatId;
    ArrayList<Item> forumTopics = new ArrayList<>();
    private int topicsMeasureGeneration;

    private int lastItemsCount;
    private ArrayList<Item> frozenForumTopicsList = new ArrayList<>();
    private boolean forumTopicsListFrozen;

    SizeNotifierFrameLayout contentView;
    FrameLayout fullscreenView;
    ChatAvatarContainer avatarContainer;
    ChatActivity.ThemeDelegate themeDelegate;
    FragmentFloatingButton floatingButton;
    private EmptyViewContainer emptyViewContainer;
    Adapter adapter = new Adapter();
    private final TopicsController topicsController;
    OnTopicSelectedListener onTopicSelectedListener;
    private PullForegroundDrawable pullForegroundDrawable;
    private int hiddenCount = 0;
    private int pullViewState;
    private boolean hiddenShown = true;

    private final static int ARCHIVE_ITEM_STATE_PINNED = 0;
    private final static int ARCHIVE_ITEM_STATE_SHOWED = 1;
    private final static int ARCHIVE_ITEM_STATE_HIDDEN = 2;

    LinearLayoutManager layoutManager;
    boolean animatedUpdateEnabled = true;

    private int prevPosition;
    private int prevTop;
    private boolean scrollUpdated;

    private final static int VIEW_TYPE_TOPIC = 0;
    private final static int VIEW_TYPE_LOADING_CELL = 1;
    private final static int VIEW_TYPE_EMPTY = 2;
    private final static int VIEW_TYPE_TOPIC_CREATE = 3;

    private static final int toggle_id = 1;
    private static final int add_member_id = 2;
    private static final int create_topic_id = 3;
    private static final int pin_id = 4;
    private static final int unpin_id = 5;
    private static final int mute_id = 6;
    private static final int delete_id = 7;
    private static final int read_id = 8;
    private static final int close_topic_id = 9;
    private static final int restart_topic_id = 10;
    private static final int delete_chat_id = 11;
    private static final int hide_id = 12;
    private static final int show_id = 13;
    private static final int boost_group_id = 14;
    private static final int report = 15;

    private boolean removeFragmentOnTransitionEnd;
    private boolean finishDialogRightSlidingPreviewOnTransitionEnd;
    TLRPC.ChatFull chatFull;
    boolean canShowCreateTopic;
    private UnreadCounterTextView bottomOverlayChatText;
    private int bottomButtonType;
    private TopicsRecyclerView recyclerListView;
    private RecyclerAnimationScrollHelper scrollHelper;
    private ItemTouchHelper itemTouchHelper;
    private TouchHelperCallback itemTouchHelperCallback;
    private ActionBarMenuSubItem createTopicSubmenu;
    private ActionBarMenuSubItem addMemberSubMenu;
    private ActionBarMenuSubItem deleteChatSubmenu;
    private ActionBarMenuSubItem boostGroupSubmenu;
    private ActionBarMenuSubItem reportSubmenu;
    private boolean bottomPannelVisible = true;
    private float searchAnimationProgress = 0f;
    private float topPanelAnimatedInset;
    private TL_stories.TL_premium_boostsStatus boostsStatus;

    private long startArchivePullingTime;
    private boolean scrollingManually;
    private boolean canShowHiddenArchive;
    private boolean disableActionBarScrolling;

    HashSet<Integer> selectedTopics = new HashSet<>();
    private boolean reordering;
    private boolean ignoreDiffUtil;
    private ActionBarMenuItem pinItem;
    private ActionBarMenuItem unpinItem;
    private ActionBarMenuItem muteItem;
    private ActionBarMenuItem deleteItem;
    private ActionBarMenuItem hideItem;
    private ActionBarMenuItem showItem;
    private ActionBarMenuSubItem readItem;
    private ActionBarMenuSubItem closeTopic;
    private ActionBarMenuSubItem restartTopic;
    ActionBarMenuItem otherItem;
    private RadialProgressView bottomOverlayProgress;
    private FrameLayout bottomOverlayContainer;
    private ActionBarMenuItem searchItem;
    private ActionBarMenuItem other;
    private MessagesSearchContainer searchContainer;
    public boolean searching;
    private final boolean openedForSelect;
    private final boolean openedForForward;
    private final boolean openedForQuote;
    private final boolean openedForReply;
    private final boolean openedForBotShare;
    private String voiceChatHash;
    private boolean openVideoChat;
    HashSet<Integer> excludeTopics;
    private boolean mute = false;

    private boolean scrollToTop;
    private boolean endReached;
    StickerEmptyView topicsEmptyView;
    private View emptyView;

    private FrameLayout fragmentContextViewWrapper;
    FragmentContextView fragmentContextView;
    private ChatObject.Call groupCall;
    private DefaultItemAnimator itemAnimator;
    private boolean loadingTopics;
    RecyclerItemsEnterAnimator itemsEnterAnimator;
    DialogsActivity dialogsActivity;
    public DialogsActivity parentDialogsActivity;

    private boolean updateAnimated;

    private final AnimationNotificationsLocker notificationsLocker = new AnimationNotificationsLocker(new int[]{
            NotificationCenter.topicsDidLoaded
    });
    private View blurredView;
    private long selectedTopicForTablet;

    private boolean joinRequested;
    private ChatActivityMemberRequestsDelegate pendingRequestsDelegate;

    float slideFragmentProgress = 1f;
    boolean isSlideBackTransition;
    boolean isDrawerTransition;
    ValueAnimator slideBackTransitionAnimator;

    private DialogsActivityTopPanelLayout topPanelLayout;
    private boolean canShowProgress;
    private ImageView closeReportSpam;

    @Override
    public View getFullscreenView() {
        return fullscreenView;
    }

    public TopicsFragment(Bundle bundle) {
        super(bundle);
        chatId = arguments.getLong("chat_id", 0);
        openedForSelect = arguments.getBoolean("for_select", false);
        openedForForward = arguments.getBoolean("forward_to", false);
        openedForBotShare = arguments.getBoolean("bot_share_to", false);
        openedForQuote = arguments.getBoolean("quote", false);
        openedForReply = arguments.getBoolean("reply_to", false);
        voiceChatHash = arguments.getString("voicechat", null);
        openVideoChat = arguments.getBoolean("videochat", false);
        topicsController = getMessagesController().getTopicsController();
        canShowProgress = !getUserConfig().getPreferences().getBoolean("topics_end_reached_" + chatId, false);

        iBlur3SourceColor = new BlurredBackgroundSourceColor();
        iBlur3SourceColor.setColor(getThemedColor(Theme.key_windowBackgroundWhite));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            scrollableViewNoiseSuppressor = new DownscaleScrollableNoiseSuppressor();
            iBlur3SourceGlassFrosted = new BlurredBackgroundSourceRenderNode(null);
            iBlur3SourceGlass = new BlurredBackgroundSourceRenderNode(null);
            iBlur3FactoryLiquidGlass = new BlurredBackgroundDrawableViewFactory(iBlur3SourceGlass);
            iBlur3FactoryLiquidGlass.setLiquidGlassEffectAllowed(LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS));
        } else {
            scrollableViewNoiseSuppressor = null;
            iBlur3SourceGlassFrosted = null;
            iBlur3SourceGlass = null;
            iBlur3FactoryLiquidGlass = new BlurredBackgroundDrawableViewFactory(iBlur3SourceColor);
        }
    }

    private boolean lastCallCheckFromServer;
    private boolean createGroupCall;

    private void checkGroupCallJoin(boolean fromServer) {
        TLRPC.Chat currentChat = getMessagesController().getChat(chatId);
        TLRPC.ChatFull chatInfo = getMessagesController().getChatFull(chatId);
        if (groupCall == null || voiceChatHash == null && !openVideoChat || !openAnimationEnded) {
            if (voiceChatHash != null && fromServer && chatInfo != null && chatInfo.call == null && fragmentView != null && getParentActivity() != null) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.linkbroken, getString(R.string.LinkHashExpired)).show();
                voiceChatHash = null;
            }
            lastCallCheckFromServer = !openAnimationEnded;
            return;
        }
        VoIPHelper.startCall(currentChat, null, voiceChatHash, createGroupCall, !groupCall.call.rtmp_stream, getParentActivity(), TopicsFragment.this, getAccountInstance());
        voiceChatHash = null;
        openVideoChat = false;
    }

    public static BaseFragment getTopicsOrChat(BaseFragment parentFragment, Bundle args) {
        return getTopicsOrChat(parentFragment.getMessagesController(), parentFragment.getMessagesStorage(), args);
    }

    public static BaseFragment getTopicsOrChat(LaunchActivity launchActivity, Bundle args) {
        return getTopicsOrChat(MessagesController.getInstance(launchActivity.currentAccount), MessagesStorage.getInstance(launchActivity.currentAccount), args);
    }

    private static BaseFragment getTopicsOrChat(MessagesController messagesController, MessagesStorage messagesStorage, Bundle args) {
        long chatId = args.getLong("chat_id");
        if (chatId != 0L) {
            TLRPC.Dialog dialog = messagesController.getDialog(-chatId);
            if (dialog != null) {
                // Dialog flags are kept in sync with ChatFull and persisted by
                // MessagesStorage. For a normal tap the dialog is already in
                // memory, so do not block the UI thread on a redundant SQLite
                // read before the navigation animation can even start.
                return dialog.view_forum_as_messages
                        ? new ChatActivity(args)
                        : new TopicsFragment(args);
            }
            TLRPC.ChatFull chatFull = messagesController.getChatFull(chatId);
            if (chatFull == null) {
                chatFull = messagesStorage.loadChatInfo(chatId, true, new CountDownLatch(1), false, false);
            }
            if (chatFull != null && chatFull.view_forum_as_messages) {
                return new ChatActivity(args);
            }
        }
        return new TopicsFragment(args);
    }

    public static void prepareToSwitchAnimation(ChatActivity chatActivity) {
        if (chatActivity.getParentLayout() == null) {
            return;
        }
        boolean needCreateTopicsFragment = false;
        if (chatActivity.getParentLayout().getFragmentStack().size() <= 1) {
            needCreateTopicsFragment = true;
        } else {
            BaseFragment previousFragment = chatActivity.getParentLayout().getFragmentStack().get(chatActivity.getParentLayout().getFragmentStack().size() - 2);
            if (previousFragment instanceof TopicsFragment) {
                TopicsFragment topicsFragment = (TopicsFragment) previousFragment;
                if (topicsFragment.chatId != -chatActivity.getDialogId()) {
                    needCreateTopicsFragment = true;
                }
            } else {
                needCreateTopicsFragment = true;
            }
        }
        if (needCreateTopicsFragment) {
            Bundle bundle = new Bundle();
            bundle.putLong("chat_id", -chatActivity.getDialogId());
            TopicsFragment topicsFragment = new TopicsFragment(bundle);
            chatActivity.getParentLayout().addFragmentToStack(topicsFragment, chatActivity.getParentLayout().getFragmentStack().size() - 1);
        }
        chatActivity.setSwitchFromTopics(true);
        chatActivity.finishFragment();
    }

    @Override
    public View createView(Context context) {
        additionNavigationBarHeight = parentDialogsActivity != null && parentDialogsActivity.hasMainTabs ? dp(DialogsActivity.MAIN_TABS_HEIGHT_WITH_MARGINS) : 0;
        additionFloatingButtonOffset = parentDialogsActivity != null && parentDialogsActivity.hasMainTabs ? dp(DialogsActivity.MAIN_TABS_HEIGHT + DialogsActivity.MAIN_TABS_MARGIN) : 0;

        fragmentView = contentView = new SizeNotifierFrameLayout(context) {
            {
                setWillNotDraw(false);
            }

            public int getActionBarFullHeight() {
                float h = actionBar.getHeight();
                float searchTabsHeight = 0;
                if (searchTabsView != null && searchTabsView.getVisibility() != View.GONE) {
                    searchTabsHeight = searchTabsView.getMeasuredHeight();
                }
                h += searchTabsHeight * searchAnimationProgress;
                return (int) h;
            }

            @Override
            protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
                if (child == actionBar && !isInPreviewMode()) {
                    int y = (int) (actionBar.getY() + getActionBarFullHeight());
                    // The forum header uses the same floating liquid-glass islands
                    // as ChatActivity. A full-width shadow underneath would visually
                    // reconnect the islands into the old solid toolbar.
                    if (searchAnimationProgress > 0) {
                        if (searchAnimationProgress < 1) {
                            int a = Theme.dividerPaint.getAlpha();
                            Theme.dividerPaint.setAlpha((int) (a * searchAnimationProgress));
                            canvas.drawLine(0, y, getMeasuredWidth(), y, Theme.dividerPaint);
                            Theme.dividerPaint.setAlpha(a);
                        } else {
                            canvas.drawLine(0, y, getMeasuredWidth(), y, Theme.dividerPaint);
                        }
                    }
                }
                return super.drawChild(canvas, child, drawingTime);
            }

            private boolean ignoreLayout;

            @Override
            protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                int width = MeasureSpec.getSize(widthMeasureSpec);
                int height = MeasureSpec.getSize(heightMeasureSpec);

                if (bottomOverlayContainer != null) {
                    ignoreLayout = true;
                    bottomOverlayContainer.getLayoutParams().height = dp(51) + navigationBarHeight;
                    bottomOverlayContainer.setPadding(0, 0, 0, navigationBarHeight);
                    ignoreLayout = false;
                }

                int actionBarHeight = 0;
                for (int i = 0; i < getChildCount(); i++) {
                    View child = getChildAt(i);
                    if (child instanceof ActionBar) {
                        child.measure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                        actionBarHeight = child.getMeasuredHeight();
                    }
                }
                for (int i = 0; i < getChildCount(); i++) {
                    View child = getChildAt(i);
                    if (!(child instanceof ActionBar)) {
                        if (child.getFitsSystemWindows()) {
                            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, 0);
                        } else {
                            measureChildWithMargins(child, widthMeasureSpec, 0, heightMeasureSpec, actionBarHeight);
                        }
                    }
                }
                setMeasuredDimension(width, height);
            }

            @Override
            public void requestLayout() {
                if (ignoreLayout) {
                    return;
                }
                super.requestLayout();
            }

            @Override
            protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
                final int count = getChildCount();

                final int parentLeft = getPaddingLeft();
                final int parentRight = right - left - getPaddingRight();

                final int parentTop = getPaddingTop();
                final int parentBottom = bottom - top - getPaddingBottom();

                for (int i = 0; i < count; i++) {
                    final View child = getChildAt(i);
                    if (child.getVisibility() != GONE) {
                        final LayoutParams lp = (LayoutParams) child.getLayoutParams();

                        final int width = child.getMeasuredWidth();
                        final int height = child.getMeasuredHeight();

                        int childLeft;
                        int childTop;

                        int gravity = lp.gravity;
                        if (gravity == -1) {
                            gravity = Gravity.NO_GRAVITY;
                        }

                        boolean forceLeftGravity = false;
                        final int layoutDirection;
                        layoutDirection = getLayoutDirection();
                        final int absoluteGravity = Gravity.getAbsoluteGravity(gravity, layoutDirection);
                        final int verticalGravity = gravity & Gravity.VERTICAL_GRAVITY_MASK;

                        switch (absoluteGravity & Gravity.HORIZONTAL_GRAVITY_MASK) {
                            case Gravity.CENTER_HORIZONTAL:
                                childLeft = parentLeft + (parentRight - parentLeft - width) / 2 +
                                        lp.leftMargin - lp.rightMargin;
                                break;
                            case Gravity.RIGHT:
                                if (!forceLeftGravity) {
                                    childLeft = parentRight - width - lp.rightMargin;
                                    break;
                                }
                            case Gravity.LEFT:
                            default:
                                childLeft = parentLeft + lp.leftMargin;
                        }

                        switch (verticalGravity) {
                            case Gravity.CENTER_VERTICAL:
                                childTop = parentTop + (parentBottom - parentTop - height) / 2 +
                                        lp.topMargin - lp.bottomMargin;
                                break;
                            case Gravity.BOTTOM:
                                childTop = parentBottom - height - lp.bottomMargin;
                                break;
                            case Gravity.TOP:
                            default:
                                childTop = parentTop + lp.topMargin;
                                if (!(child instanceof ActionBar) && !isInPreviewMode()) {
                                    childTop += actionBar.getTop() + actionBar.getMeasuredHeight();
                                }
                        }

                        child.layout(childLeft, childTop, childLeft + width, childTop + height);
                    }
                }
            }

            @Override
            protected void drawList(Canvas blurCanvas, boolean top, ArrayList<IViewWithInvalidateCallback> views) {
                for (int i = 0; i < recyclerListView.getChildCount(); i++) {
                    View child = recyclerListView.getChildAt(i);
                    if (child.getY() < AndroidUtilities.dp(100) && child.getVisibility() == View.VISIBLE) {
                        int restore = blurCanvas.save();
                        blurCanvas.translate(recyclerListView.getX() + child.getX(), getY() + recyclerListView.getY() + child.getY());
                        if (views != null && child instanceof IViewWithInvalidateCallback) {
                            views.add((IViewWithInvalidateCallback) child);
                        }
                        child.draw(blurCanvas);
                        blurCanvas.restoreToCount(restore);
                    }
                }
            }

            private Paint actionBarPaint = new Paint();

            @Override
            protected void dispatchDraw(Canvas canvas) {
                if (Build.VERSION.SDK_INT >= 31 && scrollableViewNoiseSuppressor != null) {
                    final int width = parentDialogsActivity != null ? parentDialogsActivity.fragmentView.getMeasuredWidth() : getMeasuredWidth();
                    final int height = parentDialogsActivity != null ? parentDialogsActivity.fragmentView.getMeasuredHeight() : getMeasuredHeight();

                    if (iBlur3SourceGlassFrosted != null && !iBlur3SourceGlassFrosted.inRecording()) {
                        if (iBlur3SourceGlassFrosted.needUpdateDisplayList(width, height) || iBlur3Invalidated) {
                            final Canvas c = iBlur3SourceGlassFrosted.beginRecording(width, height);
                            // Captured RecyclerView children do not cover the
                            // elastic gap above the first row. Seed the source
                            // with the real page colour so transparent pixels
                            // never become a black slab under the glass header.
                            c.drawColor(getThemedColor(Theme.key_windowBackgroundWhite));
                            scrollableViewNoiseSuppressor.draw(c, DownscaleScrollableNoiseSuppressor.DRAW_FROSTED_GLASS);
                            iBlur3SourceGlassFrosted.endRecording();
                        }
                    }
                    if (iBlur3SourceGlass != null && !iBlur3SourceGlass.inRecording()) {
                        if (iBlur3SourceGlass.needUpdateDisplayList(width, height) || iBlur3Invalidated) {
                            final Canvas c = iBlur3SourceGlass.beginRecording(width, height);
                            c.drawColor(getThemedColor(Theme.key_windowBackgroundWhite));
                            scrollableViewNoiseSuppressor.draw(c, DownscaleScrollableNoiseSuppressor.DRAW_GLASS);
                            iBlur3SourceGlass.endRecording();
                        }
                    }
                    iBlur3Invalidated = false;
                }

                super.dispatchDraw(canvas);
                if (isInPreviewMode()) {
                    actionBarPaint.setColor(getThemedColor(Theme.key_windowBackgroundWhite));
                    actionBarPaint.setAlpha((int) (255 * searchAnimationProgress));
                    canvas.drawRect(0, 0, getWidth(), AndroidUtilities.statusBarHeight, actionBarPaint);
                    canvas.drawLine(0, 0, 0, getHeight(), Theme.dividerPaint);
                }
                if (parentDialogsActivity == null) {
                    AndroidUtilities.drawNavigationBarProtection(canvas, this, getThemedColor(Theme.key_windowBackgroundWhite), navigationBarHeight);
                }
            }

            @Override
            public void drawBlurRect(Canvas canvas, float y, Rect rectTmp, Paint blurScrimPaint, boolean top) {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !SharedConfig.chatBlurEnabled() || iBlur3SourceGlassFrosted == null) {
                    canvas.drawRect(rectTmp, blurScrimPaint);
                    return;
                }

                canvas.save();
                canvas.translate(0, -y);
                iBlur3SourceGlassFrosted.draw(canvas, rectTmp.left, rectTmp.top + y, rectTmp.right, rectTmp.bottom + y);
                canvas.restore();

                final int oldScrimAlpha = blurScrimPaint.getAlpha();
                blurScrimPaint.setAlpha(ChatActivity.ACTION_BAR_BLUR_ALPHA);
                canvas.drawRect(rectTmp, blurScrimPaint);
                blurScrimPaint.setAlpha(oldScrimAlpha);
            }
        };

        contentView.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        actionBar.setAddToContainer(false);
        actionBar.setCastShadows(false);
        actionBar.setClipContent(true);
        actionBar.setOccupyStatusBar(!AndroidUtilities.isTablet() && !inPreviewMode);
        if (inPreviewMode) {
            actionBar.setBackgroundColor(Color.TRANSPARENT);
            actionBar.setInterceptTouches(false);
        }

        actionBar.setBackButtonDrawable(new BackDrawable(false));
        updateUnreadBackBadge();

        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    if (selectedTopics.size() > 0) {
                        clearSelectedTopics();
                        return;
                    }
                    finishFragment();
                    return;
                }
                TLRPC.TL_forumTopic topic;
                switch (id) {
                    case toggle_id:
                        getMessagesController().getTopicsController().toggleViewForumAsMessages(chatId, true);
                        finishDialogRightSlidingPreviewOnTransitionEnd = true;
                        Bundle bundle = new Bundle();
                        bundle.putLong("chat_id", chatId);
                        ChatActivity chatActivity = new ChatActivity(bundle);
                        chatActivity.setSwitchFromTopics(true);
                        presentFragment(chatActivity);
                        break;
                    case add_member_id:
                        TLRPC.ChatFull chatFull = getMessagesController().getChatFull(chatId);
                        if (TopicsFragment.this.chatFull != null && TopicsFragment.this.chatFull.participants != null) {
                            chatFull.participants = TopicsFragment.this.chatFull.participants;
                        }
                        if (chatFull != null) {
                            LongSparseArray<TLObject> users = new LongSparseArray<>();
                            if (chatFull.participants != null) {
                                for (int a = 0; a < chatFull.participants.participants.size(); a++) {
                                    users.put(chatFull.participants.participants.get(a).user_id, null);
                                }
                            }
                            long chatId = chatFull.id;
                            InviteMembersBottomSheet bottomSheet = new InviteMembersBottomSheet(context, currentAccount, users, chatFull.id, TopicsFragment.this, themeDelegate) {
                                @Override
                                protected boolean canGenerateLink() {
                                    TLRPC.Chat chat = getMessagesController().getChat(chatId);
                                    return chat != null && ChatObject.canUserDoAdminAction(chat, ChatObject.ACTION_INVITE);
                                }
                            };
                            bottomSheet.setDelegate((users1, fwdCount) -> {
                                int N = users1.size();
                                int[] finished = new int[1];
                                TLRPC.TL_messages_invitedUsers totalInvitedUsers = new TLRPC.TL_messages_invitedUsers();
                                totalInvitedUsers.updates = new TLRPC.TL_updates();
                                for (int a = 0; a < N; a++) {
                                    TLRPC.User user = users1.get(a);
                                    getMessagesController().addUserToChat(chatId, user, fwdCount, null, TopicsFragment.this, false, () -> {}, null, invitedUsers -> {
                                        if (invitedUsers != null) {
                                            totalInvitedUsers.missing_invitees.addAll(invitedUsers.missing_invitees);
                                        }
                                        finished[0]++;
                                        if (finished[0] == N) {
                                            if (totalInvitedUsers.missing_invitees.isEmpty()) {
                                                BulletinFactory.of(TopicsFragment.this).createUsersAddedBulletin(users1, getMessagesController().getChat(chatId)).show();
                                            } else {
                                                TLRPC.Chat chat = getMessagesController().getChat(chatId);
                                                AlertsCreator.checkRestrictedInviteUsers(currentAccount, chat, totalInvitedUsers);
                                            }
                                        }
                                    });
                                }
                            });
                            bottomSheet.show();
                        }
                        break;
                    case boost_group_id: {
                        TLRPC.Chat chatLocal = getMessagesController().getChat(chatId);
                        if (ChatObject.hasAdminRights(chatLocal)) {
                            BoostsActivity boostsActivity = new BoostsActivity(-chatId);
                            boostsActivity.setBoostsStatus(boostsStatus);
                            presentFragment(boostsActivity);
                        } else {
                            getNotificationCenter().postNotificationName(NotificationCenter.openBoostForUsersDialog, -chatId);
                        }
                        break;
                    }
                    case create_topic_id:
                        TopicCreateFragment fragment = TopicCreateFragment.create(chatId, 0);
                        presentFragment(fragment);
                        AndroidUtilities.runOnUIThread(() -> {
                            fragment.showKeyboard();
                        }, 200);
                        break;
                    case delete_chat_id:
                        TLRPC.Chat chatLocal = getMessagesController().getChat(chatId);
                        AlertsCreator.createClearOrDeleteDialogAlert(TopicsFragment.this, false, chatLocal, null, false, true, false, false, (param) -> {
                            getNotificationCenter().removeObserver(TopicsFragment.this, NotificationCenter.closeChats);
                            getNotificationCenter().postNotificationName(NotificationCenter.closeChats);
                            finishFragment();
                            getNotificationCenter().postNotificationName(NotificationCenter.needDeleteDialog, -chatLocal.id, null, chatLocal, param);
                        });
                        break;
                    case delete_id:
                        deleteTopics(selectedTopics, () -> {
                            clearSelectedTopics();
                        });
                        break;
                    case hide_id:
                    case show_id:
                        topic = null;
                        TopicDialogCell dialogCell = null;
                        for (int i = 0; i < recyclerListView.getChildCount(); ++i) {
                            View child = recyclerListView.getChildAt(i);
                            if (child instanceof TopicDialogCell && ((TopicDialogCell) child).forumTopic != null && ((TopicDialogCell) child).forumTopic.id == 1) {
                                dialogCell = (TopicDialogCell) child;
                                topic = dialogCell.forumTopic;
                                break;
                            }
                        }
                        if (topic == null) {
                            for (int i = 0; i < forumTopics.size(); ++i) {
                                if (forumTopics.get(i) != null && forumTopics.get(i).topic != null && forumTopics.get(i).topic.id == 1) {
                                    topic = forumTopics.get(i).topic;
                                    break;
                                }
                            }
                        }
                        if (topic != null) {
                            if (hiddenCount <= 0) {
                                hiddenShown = true;
                                pullViewState = ARCHIVE_ITEM_STATE_HIDDEN;
                            }
                            getMessagesController().getTopicsController().toggleShowTopic(chatId, 1, topic.hidden);
                            if (dialogCell != null) {
                                generalTopicViewMoving = dialogCell;
                            }
                            recyclerListView.setArchiveHidden(!topic.hidden, dialogCell);
                            updateTopicsList(true, true);
                            if (dialogCell != null) {
                                dialogCell.setTopicIcon(dialogCell.currentTopic);
                            }
                        }
                        clearSelectedTopics();
                        break;
                    case pin_id:
                    case unpin_id:
                        if (selectedTopics.size() > 0) {
                            scrollToTop = true;
                            updateAnimated = true;
                            topicsController.pinTopic(chatId, selectedTopics.iterator().next(), id == pin_id, TopicsFragment.this);
                        }
                        clearSelectedTopics();
                        break;
                    case mute_id:
                        Iterator<Integer> iterator = selectedTopics.iterator();
                        while (iterator.hasNext()) {
                            int topicId = iterator.next();
                            getNotificationsController().muteDialog(-chatId, topicId, mute);
                        }
                        clearSelectedTopics();
                        break;
                    case restart_topic_id:
                    case close_topic_id:
                        updateAnimated = true;
                        ArrayList<Integer> list = new ArrayList<>(selectedTopics);
                        for (int i = 0; i < list.size(); ++i) {
                            topicsController.toggleCloseTopic(chatId, list.get(i), id == close_topic_id);
                        }
                        clearSelectedTopics();
                        break;
                    case read_id:
                        list = new ArrayList<>(selectedTopics);
                        for (int i = 0; i < list.size(); ++i) {
                            topic = topicsController.findTopic(chatId, list.get(i));
                            if (topic != null) {
                                getMessagesController().markMentionsAsRead(-chatId, topic.id);
                                getMessagesController().markDialogAsRead(-chatId, topic.top_message, 0, topic.topMessage != null ? topic.topMessage.date : 0, false, topic.id, 0, true, 0);
                                getMessagesStorage().updateRepliesMaxReadId(chatId, topic.id, topic.top_message, 0, true);
                            }
                        }
                        clearSelectedTopics();
                        break;
                    case report:
                        ReportBottomSheet.openChat(TopicsFragment.this, -chatId);
                        break;
                }
                super.onItemClick(id);
            }
        });


        actionBar.setOnClickListener(v -> {
            if (!searching) {
                openProfile(false);
            }
        });
        ActionBarMenu menu = actionBar.createMenu();

        if (parentDialogsActivity != null) {
            searchItem = menu.addItem(0, R.drawable.outline_header_search);
            searchItem.setOnClickListener(e -> {
                openParentSearch();
            });
        } else {
            searchItem = menu.addItem(0, R.drawable.outline_header_search);
            searchItem.setIsSearchField(true).setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
                @Override
                public void onSearchExpand() {
                    animateToSearchView(true);
                    searchContainer.setSearchString("");
                    searchContainer.setAlpha(0);
                    searchContainer.emptyView.showProgress(true, false);
                }

                @Override
                public void onSearchCollapse() {
                    animateToSearchView(false);
                }

                @Override
                public void onTextChanged(EditText editText) {
                    String text = editText.getText().toString();
                    searchContainer.setSearchString(text);
                }

                @Override
                public void onSearchFilterCleared(FiltersView.MediaFilterData filterData) {

                }
            });
            // The complete identity island (avatar + title) fades while search is
            // expanded, so there is no avatar left to reserve 56dp for.  Keeping
            // the old forum padding shifted the hint/cursor far to the right and
            // left a conspicuous empty area at the start of the search pill.
            // Match ChatActivity's compact inset inside the glass search island.
            searchItem.setSearchPaddingStart(7);
            searchItem.setSearchFieldHint(getString(R.string.Search));
            // The field keeps the standard ActionBar search palette.  The
            // former window/player/chat keys belonged to three unrelated
            // surfaces and produced mismatched colours on custom themes.
        }
        other = menu.addItem(0, R.drawable.ic_ab_other, themeDelegate);
        other.setContentDescription(getString(R.string.AccDescrMoreOptions));
        other.addSubItem(toggle_id, R.drawable.msg_discussion, getString(R.string.TopicViewAsMessages));
        addMemberSubMenu = other.addSubItem(add_member_id, R.drawable.msg_addcontact, getString(R.string.AddMember));
        boostGroupSubmenu = other.addSubItem(boost_group_id, 0, new RLottieDrawable(R.raw.boosts, "" + R.raw.boosts, AndroidUtilities.dp(24), AndroidUtilities.dp(24)), getString(R.string.BoostingBoostGroupMenu), true, false);
        createTopicSubmenu = other.addSubItem(create_topic_id, R.drawable.msg_topic_create, getString(R.string.CreateTopic));
        reportSubmenu = other.addSubItem(report, R.drawable.msg_report, getString(R.string.ReportChat));
        deleteChatSubmenu = other.addSubItem(delete_chat_id, R.drawable.msg_leave, getString(R.string.LeaveMegaMenu), themeDelegate);

        avatarContainer = new ChatAvatarContainer(context, this, false, resourceProvider);
        avatarContainer.setGlassMode();
        avatarContainer.setActionBar(actionBar);
        avatarContainer.getAvatarImageView().setRoundRadius(AndroidUtilities.dp(21));
        avatarContainer.setOccupyStatusBar(!AndroidUtilities.isTablet() && !inPreviewMode);
        avatarContainer.allowDrawStories = getDialogId() < 0;
        avatarContainer.setClipChildren(false);
        actionBar.addView(avatarContainer, 0, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT, Gravity.TOP | Gravity.LEFT, 56, 0, 86, 0));

        if (!openedForSelect) {
            avatarContainer.setOnClickListener(v -> openProfile(false));
            avatarContainer.getAvatarImageView().setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openProfile(true);
                }
            });
        }
        recyclerListView = new TopicsRecyclerView(context) {
            @Override
            protected void onLayout(boolean changed, int l, int t, int r, int b) {
                super.onLayout(changed, l, t, r, b);
                checkForLoadMore();
            }

            @Override
            public boolean emptyViewIsVisible() {
                if (getAdapter() == null || isFastScrollAnimationRunning()) {
                    return false;
                }
                if (forumTopics != null && forumTopics.size() == 1 && forumTopics.get(0) != null && forumTopics.get(0).topic != null && forumTopics.get(0).topic.id == 1) {
                    return getAdapter().getItemCount() <= 2;
                }
                return getAdapter().getItemCount() <= 1;
            }
        };

        iBlur3FactoryLiquidGlass.setSourceRootView(new ViewPositionWatcher(contentView), contentView);
        actionBar.setupGlass(
                iBlur3FactoryLiquidGlass,
                BlurredBackgroundProviderImpl.topPanelChatActivity(resourceProvider),
                true);
        iBlur3Capture = new ViewGroupPartRenderer(recyclerListView, parentDialogsActivity != null ? (ViewGroup) parentDialogsActivity.getFragmentView() : contentView, recyclerListView::drawChild);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && scrollableViewNoiseSuppressor != null) {
            // Match ChatActivity's event-driven blur pipeline. Rebuilding the
            // capture from dispatchDraw made every unrelated animation frame
            // walk and hash the visible topic cells.
            invalidateBlurredSourcesView = new OnPostDrawView(context, true, flags -> blur3_UpdateBlur());
            contentView.addView(invalidateBlurredSourcesView);
        }
        recyclerListView.addEdgeEffectListener(() -> recyclerListView.postOnAnimation(this::blur3_InvalidateBlur));

        SpannableString generalIcon = new SpannableString("#");
        Drawable generalIconDrawable = ForumUtilities.createGeneralTopicDrawable(getContext(), .85f, Color.WHITE, false);
        generalIconDrawable.setBounds(0, AndroidUtilities.dp(2), AndroidUtilities.dp(16), AndroidUtilities.dp(18));
        generalIcon.setSpan(new ImageSpan(generalIconDrawable, DynamicDrawableSpan.ALIGN_CENTER), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        pullForegroundDrawable = new PullForegroundDrawable(
            AndroidUtilities.replaceCharSequence("#", getString(R.string.AccSwipeForGeneral), generalIcon),
            AndroidUtilities.replaceCharSequence("#", getString(R.string.AccReleaseForGeneral), generalIcon)
        ) {
            @Override
            protected float getViewOffset() {
                return recyclerListView.getViewOffset();
            }
        };
        if (false) {
            pullForegroundDrawable.showHidden();
        } else {
            pullForegroundDrawable.doNotShow();
        }
        pullViewState = hiddenShown ? ARCHIVE_ITEM_STATE_HIDDEN : ARCHIVE_ITEM_STATE_PINNED;
        pullForegroundDrawable.setWillDraw(pullViewState != ARCHIVE_ITEM_STATE_PINNED);
        DefaultItemAnimator defaultItemAnimator = new DefaultItemAnimator() {
            Runnable finishRunnable;
            int scrollAnimationIndex;

            @Override
            public void checkIsRunning() {
                if (scrollAnimationIndex == -1) {
                    scrollAnimationIndex = getNotificationCenter().setAnimationInProgress(scrollAnimationIndex, null, false);
                    if (finishRunnable != null) {
                        AndroidUtilities.cancelRunOnUIThread(finishRunnable);
                        finishRunnable = null;
                    }
                }
            }

            @Override
            protected void onAllAnimationsDone() {
                super.onAllAnimationsDone();
                if (recyclerListView == null || !recyclerListView.isArchiveSettleAnimationRunning()) {
                    finishGeneralTopicMoving();
                }
                if (finishRunnable != null) {
                    AndroidUtilities.cancelRunOnUIThread(finishRunnable);
                    finishRunnable = null;
                }
                AndroidUtilities.runOnUIThread(finishRunnable = () -> {
                    finishRunnable = null;
                    if (scrollAnimationIndex != -1) {
                        getNotificationCenter().onAnimationFinish(scrollAnimationIndex);
                        scrollAnimationIndex = -1;
                    }
                });
            }


            @Override
            public void endAnimations() {
                super.endAnimations();
                if (recyclerListView == null || !recyclerListView.isArchiveSettleAnimationRunning()) {
                    finishGeneralTopicMoving();
                }
                if (finishRunnable != null) {
                    AndroidUtilities.cancelRunOnUIThread(finishRunnable);
                }
                AndroidUtilities.runOnUIThread(finishRunnable = () -> {
                    finishRunnable = null;
                    if (scrollAnimationIndex != -1) {
                        getNotificationCenter().onAnimationFinish(scrollAnimationIndex);
                        scrollAnimationIndex = -1;
                    }
                });
            }

            @Override
            protected void afterAnimateMoveImpl(RecyclerView.ViewHolder holder) {
                if (generalTopicViewMoving == holder.itemView
                        && (recyclerListView == null || !recyclerListView.isArchiveSettleAnimationRunning())) {
                    finishGeneralTopicMoving();
                }
            }
        };
        recyclerListView.setHideIfEmpty(false);
        defaultItemAnimator.setSupportsChangeAnimations(false);
        defaultItemAnimator.setDelayAnimations(false);
        recyclerListView.setItemAnimator(itemAnimator = defaultItemAnimator);
        recyclerListView.setAnimateEmptyView(true, RecyclerListView.EMPTY_VIEW_ANIMATION_TYPE_ALPHA);
        itemsEnterAnimator = new RecyclerItemsEnterAnimator(recyclerListView, true);
        recyclerListView.setItemsEnterAnimator(itemsEnterAnimator);
        recyclerListView.setOnItemClickListener((view, position) -> {
            if (getParentLayout() == null || getParentLayout().isInPreviewMode()) {
                return;
            }
            final TLRPC.TL_forumTopic topic;
            if (view instanceof TopicDialogCell) {
                topic = ((TopicDialogCell) view).forumTopic;
            } else {
                return;
            }

            final boolean mono = getMessagesController().isMonoForum(-chatId);
            final long topicId = topic == null ? 0 : mono ? DialogObject.getPeerDialogId(topic.from_id) : topic.id;

            if (openedForSelect) {
                if (onTopicSelectedListener != null) {
                    onTopicSelectedListener.onTopicSelected(topic);
                }
                if (dialogsActivity != null) {
                    dialogsActivity.didSelectResult(-chatId, topicId, true, false, this);
                }
                return;
            }
            if (selectedTopics.size() > 0) {
                toggleSelection(view);
                return;
            }
            if (inPreviewMode && AndroidUtilities.isTablet()) {
                for (BaseFragment fragment : getParentLayout().getFragmentStack()) {
                    if (fragment instanceof DialogsActivity && ((DialogsActivity) fragment).isMainDialogList()) {
                        MessagesStorage.TopicKey topicKey = ((DialogsActivity) fragment).getOpenedDialogId();
                        if (topicKey.dialogId == -chatId && topicKey.topicId == topicId) {
                            return;
                        }
                    }
                }
                final long previousSelectedTopic = selectedTopicForTablet;
                selectedTopicForTablet = topicId;
                // Selection does not mutate the topic dataset. Rebuilding the
                // complete list and running DiffUtil here delayed navigation
                // just to repaint the old and new selected rows.
                notifyTopicSelectionChanged(previousSelectedTopic);
                notifyTopicSelectionChanged(selectedTopicForTablet);
            }
            ForumUtilities.openTopic(TopicsFragment.this, chatId, topic, 0);
        });
        recyclerListView.setOnItemLongClickListener((view, position, x, y) -> {
            if (openedForSelect || getParentLayout() == null || getParentLayout().isInPreviewMode()) {
                return false;
            }
            if (!actionBar.isActionModeShowed() && !AndroidUtilities.isTablet() && view instanceof TopicDialogCell) {
                TopicDialogCell cell = (TopicDialogCell) view;
                if (cell.isPointInsideAvatar(x, y)) {
                    showChatPreview(cell);
                    recyclerListView.cancelClickRunnables(true);
                    recyclerListView.dispatchTouchEvent(MotionEvent.obtain(0, 0, MotionEvent.ACTION_CANCEL, 0, 0, 0));
                    return false;
                }
            }
            toggleSelection(view);
            try {
                view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            } catch (Exception ignored) {}
            return true;
        });
        recyclerListView.setOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                checkForLoadMore();
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && scrollableViewNoiseSuppressor != null) {
                    scrollableViewNoiseSuppressor.onScrolled(dx, dy);
                    blur3_InvalidateBlur();
                }
            }

            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);
                if (newState == RecyclerView.SCROLL_STATE_IDLE
                        && !hiddenShown
                        && (itemAnimator == null || !itemAnimator.isRunning())
                        && !TopicsFragment.this.recyclerListView.isArchiveSettleAnimationRunning()) {
                    // A hidden General row does not always produce a DiffUtil
                    // move. In that case afterAnimateMoveImpl is never called,
                    // so release the manually drawn swipe copy when the
                    // vertical close animation settles.
                    finishGeneralTopicMoving();
                }
            }
        });
        recyclerListView.setLayoutManager(layoutManager = new LinearLayoutManager(context) {

            private boolean fixOffset;

            @Override
            public void scrollToPositionWithOffset(int position, int offset) {
                if (fixOffset) {
                    offset -= recyclerListView.getPaddingTop();
                }
                super.scrollToPositionWithOffset(position, offset);
            }

            @Override
            public void prepareForDrop(@NonNull View view, @NonNull View target, int x, int y) {
                fixOffset = true;
                super.prepareForDrop(view, target, x, y);
                fixOffset = false;
            }

            @Override
            public void smoothScrollToPosition(RecyclerView recyclerView, RecyclerView.State state, int position) {
                if (hiddenCount > 0 && position == 1) {
                    super.smoothScrollToPosition(recyclerView, state, position);
                } else {
                    LinearSmoothScrollerCustom linearSmoothScroller = new LinearSmoothScrollerCustom(recyclerView.getContext(), LinearSmoothScrollerCustom.POSITION_MIDDLE);
                    linearSmoothScroller.setTargetPosition(position);
                    startSmoothScroll(linearSmoothScroller);
                }
            }

            @Override
            public int scrollVerticallyBy(int dy, RecyclerView.Recycler recycler, RecyclerView.State state) {
                if (recyclerListView.fastScrollAnimationRunning) {
                    return 0;
                }
                boolean isDragging = recyclerListView.getScrollState() == RecyclerView.SCROLL_STATE_DRAGGING;

                int measuredDy = dy;
                int translatedDy = 0;
                int pTop = recyclerListView.getPaddingTop();
                if (dy < 0 && hiddenCount > 0 && pullViewState == ARCHIVE_ITEM_STATE_HIDDEN) {
                    recyclerListView.setOverScrollMode(View.OVER_SCROLL_ALWAYS);
                    int currentPosition = layoutManager.findFirstVisibleItemPosition();
                    if (currentPosition == 0) {
                        View view = layoutManager.findViewByPosition(currentPosition);
                        if (view != null) {
                            view.setTranslationX(0);
                        }
                        if (view != null && (view.getY() + view.getMeasuredHeight() - pTop) <= AndroidUtilities.dp(1)) {
                            currentPosition = 1;
                        }
                    }
                    if (!isDragging) {
                        View view = layoutManager.findViewByPosition(currentPosition);
                        if (view != null) {
                            int dialogHeight = AndroidUtilities.dp(SharedConfig.useThreeLinesLayout ? 78 : 72) + 1;
                            int canScrollDy = -(view.getTop() - pTop) + (currentPosition - 1) * dialogHeight;
                            int positiveDy = Math.abs(dy);
                            if (canScrollDy < positiveDy) {
                                measuredDy = -canScrollDy;
                            }
                        }
                    } else if (currentPosition == 0) {
                        View v = layoutManager.findViewByPosition(currentPosition);
                        float k = Utilities.clamp(
                                1f + ((v.getY() - pTop) / (float) v.getMeasuredHeight()),
                                1f, 0f);
                        recyclerListView.setOverScrollMode(View.OVER_SCROLL_NEVER);
                        measuredDy *= PullForegroundDrawable.startPullParallax - PullForegroundDrawable.endPullParallax * k;
                        if (measuredDy > -1) {
                            measuredDy = -1;
                        }
                    }
                }

                if (recyclerListView.getViewOffset() != 0 && dy > 0 && isDragging) {
                    // Consume the elastic pull offset first. If this MOVE also
                    // crosses zero, pass the remaining distance upward with
                    // the original positive sign. The old code assigned the
                    // negative temporary offset to measuredDy, reversing the
                    // list for one frame when the finger changed direction;
                    // General's text and its pull background then moved on
                    // opposite sides of the floating header.
                    float currentOffset = recyclerListView.getViewOffset();
                    float consumedOffset = Math.min(currentOffset, dy);
                    recyclerListView.setViewsOffset(currentOffset - consumedOffset);
                    translatedDy = Math.round(consumedOffset);
                    measuredDy = Math.round(dy - consumedOffset);
                }

                if (pullViewState != ARCHIVE_ITEM_STATE_PINNED && hiddenCount > 0) {
                    final float generalRevealBeforeScroll =
                            recyclerListView.getHiddenGeneralReveal();
                    final float viewOffsetBeforeScroll =
                            recyclerListView.getViewOffset();
                    int usedDy = super.scrollVerticallyBy(measuredDy, recycler, state);
                    if (pullForegroundDrawable != null) {
                        pullForegroundDrawable.scrollDy = usedDy + translatedDy;
                    }
                    int currentPosition = layoutManager.findFirstVisibleItemPosition();
                    View firstView = null;
                    if (currentPosition == 0) {
                        firstView = layoutManager.findViewByPosition(currentPosition);
                    }
                    if (firstView != null) {
                        firstView.setTranslationX(0);
                    }
                    if (currentPosition == 0 && firstView != null
                            && firstView.getBottom() - pTop >= AndroidUtilities.dp(4)) {
                        if (startArchivePullingTime == 0) {
                            startArchivePullingTime = System.currentTimeMillis();
                        }
                        if (pullViewState == ARCHIVE_ITEM_STATE_HIDDEN) {
                            if (pullForegroundDrawable != null) {
                                pullForegroundDrawable.showHidden();
                            }
                        }
                        float k = Utilities.clamp(
                                1f + ((firstView.getY() - pTop) / (float) firstView.getMeasuredHeight()),
                                1f, 0f);
                        long pullingTime = System.currentTimeMillis() - startArchivePullingTime;
                        boolean canShowInternal = k > PullForegroundDrawable.SNAP_HEIGHT && pullingTime > PullForegroundDrawable.minPullingTime + 20;
                        if (canShowHiddenArchive != canShowInternal) {
                            canShowHiddenArchive = canShowInternal;
                            if (pullViewState == ARCHIVE_ITEM_STATE_HIDDEN) {
                                try {
                                    recyclerListView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
                                } catch (Exception ignored) {}
                                if (pullForegroundDrawable != null) {
                                    pullForegroundDrawable.colorize(canShowInternal);
                                }
                            }
                        }
                        if (pullViewState == ARCHIVE_ITEM_STATE_HIDDEN && measuredDy - usedDy != 0 && dy < 0 && isDragging) {
                            float ty;
                            float tk = (recyclerListView.getViewOffset() / PullForegroundDrawable.getMaxOverscroll());
                            tk = 1f - tk;
                            ty = (recyclerListView.getViewOffset() - dy * PullForegroundDrawable.startPullOverScroll * tk);
                            recyclerListView.setViewsOffset(ty);
                        }
                        if (pullForegroundDrawable != null) {
                            pullForegroundDrawable.setPullProgress(k);
                            pullForegroundDrawable.setListView(recyclerListView);
                        }
                    } else {
                        startArchivePullingTime = 0;
                        canShowHiddenArchive = false;
                        pullViewState = ARCHIVE_ITEM_STATE_HIDDEN;
                        if (pullForegroundDrawable != null) {
                            pullForegroundDrawable.resetText();
                            pullForegroundDrawable.setPullProgress(0f);
                            pullForegroundDrawable.setListView(recyclerListView);
                        }
                    }
                    if (firstView != null) {
                        firstView.invalidate();
                    }
                    int consumedDy = usedDy + translatedDy;
                    if (isDragging
                            && dy > 0
                            && pullViewState == ARCHIVE_ITEM_STATE_HIDDEN
                            && firstView == null
                            && consumedDy < dy) {
                        // Once General is fully hidden, an unconsumed upward
                        // drag must not start an EdgeEffect. BlurredRecyclerView
                        // intentionally relaxes its top clip while an edge is
                        // active, which exposed the detached General holder
                        // behind the floating forum header.
                        recyclerListView.setOverScrollMode(View.OVER_SCROLL_NEVER);
                    }
                    // RecyclerView must be told about the distance consumed by
                    // the translated overscroll as well as the real layout
                    // scroll. Otherwise it treats that portion as unconsumed,
                    // feeds it into EdgeEffect and desynchronizes the next MOVE.
                    recyclerListView.trackHiddenGeneralPull(
                            isDragging, dy, generalRevealBeforeScroll, viewOffsetBeforeScroll);
                    if (dy > 0 && translatedDy > 0) {
                        return Math.min(dy, consumedDy);
                    }
                    return usedDy;
                }
                return super.scrollVerticallyBy(measuredDy, recycler, state);
            }

            @Override
            public void onLayoutChildren(RecyclerView.Recycler recycler, RecyclerView.State state) {
                if (BuildVars.DEBUG_PRIVATE_VERSION) {
                    try {
                        super.onLayoutChildren(recycler, state);
                    } catch (IndexOutOfBoundsException e) {
                        throw new RuntimeException("Inconsistency detected. ");
                    }
                } else {
                    try {
                        super.onLayoutChildren(recycler, state);
                    } catch (IndexOutOfBoundsException e) {
                        FileLog.e(e);
                        AndroidUtilities.runOnUIThread(() -> adapter.notifyDataSetChanged());
                    }
                }
            }
        });
        scrollHelper = new RecyclerAnimationScrollHelper(recyclerListView, layoutManager);
        recyclerListView.setAdapter(adapter);
        recyclerListView.setClipToPadding(false);
        recyclerListView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                    scrollingManually = true;
                    if (!TopicsFragment.this.recyclerListView.isArchiveSettleAnimationRunning()) {
                        disableActionBarScrolling = false;
                    }
                    // Padding/top-panel changes can reposition children without
                    // a meaningful scroll delta. Snapshot the real geometry at
                    // gesture start so the first MOVE is compared only with the
                    // user's movement.
                    int firstVisibleItem = layoutManager.findFirstVisibleItemPosition();
                    if (firstVisibleItem != RecyclerView.NO_POSITION) {
                        View firstView = layoutManager.findViewByPosition(firstVisibleItem);
                        prevPosition = firstVisibleItem;
                        prevTop = firstView != null ? firstView.getTop() : 0;
                        scrollUpdated = true;
                    }
                } else if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    scrollingManually = false;
                    if (!TopicsFragment.this.recyclerListView.isArchiveSettleAnimationRunning()) {
                        disableActionBarScrolling = false;
                    }
                }
            }

            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                int firstVisibleItem = layoutManager.findFirstVisibleItemPosition();
                if (firstVisibleItem != RecyclerView.NO_POSITION) {
                    RecyclerView.ViewHolder holder = recyclerView.findViewHolderForAdapterPosition(firstVisibleItem);

                    int firstViewTop = 0;
                    if (holder != null) {
                        firstViewTop = holder.itemView.getTop();
                    }
                    boolean goingDown;
                    boolean changed = true;
                    if (prevPosition == firstVisibleItem) {
                        final int topDelta = prevTop - firstViewTop;
                        goingDown = firstViewTop < prevTop;
                        changed = Math.abs(topDelta) > 1;
                    } else {
                        goingDown = firstVisibleItem > prevPosition;
                    }

                    // Initial anchoring, group-call padding and archive-settle
                    // layouts also dispatch onScrolled(). They must not hide the
                    // create-topic FAB. Only a real user drag/fling may change
                    // its scroll visibility, and the first callback establishes
                    // a baseline instead of comparing position 1 against zero.
                    if (changed && scrollUpdated
                            && scrollingManually
                            && !disableActionBarScrolling) {
                        hideFloatingButton(goingDown || !canShowCreateTopic, true);
                    }
                    prevPosition = firstVisibleItem;
                    prevTop = firstViewTop;
                    scrollUpdated = true;
                }
            }
        });
        itemTouchHelper = new ItemTouchHelper(itemTouchHelperCallback = new TouchHelperCallback()) {
            @Override
            protected boolean shouldSwipeBack() {
                return hiddenCount > 0;
            }
        };
        itemTouchHelper.attachToRecyclerView(recyclerListView);

        contentView.addView(recyclerListView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        ((ViewGroup.MarginLayoutParams) recyclerListView.getLayoutParams()).topMargin = -AndroidUtilities.dp(100);
        floatingButton = new FragmentFloatingButton(getContext(), resourceProvider);
        contentView.addView(floatingButton, FragmentFloatingButton.createDefaultLayoutParams());
        floatingButton.setOnClickListener(v -> presentFragment(TopicCreateFragment.create(chatId, 0)));
        floatingButton.imageView.setImageResource(R.drawable.ic_chatlist_add_2);
        floatingButton.imageView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        floatingButton.imageView.setPadding(dp(12), dp(12), dp(12), dp(12));
        floatingButton.setContentDescription(getString(R.string.CreateTopic));


        FlickerLoadingView flickerLoadingView = new FlickerLoadingView(context);
        flickerLoadingView.setViewType(FlickerLoadingView.TOPIC_CELL_TYPE);
        flickerLoadingView.setVisibility(View.GONE);
        flickerLoadingView.showDate(true);

        emptyViewContainer = new EmptyViewContainer(context);
        emptyViewContainer.textView.setAlpha(0);

        topicsEmptyView = new StickerEmptyView(context, flickerLoadingView, StickerEmptyView.STICKER_TYPE_NO_CONTACTS) {
            boolean showProgressInternal;

            @Override
            public void showProgress(boolean show, boolean animated) {
                super.showProgress(show, animated);
                showProgressInternal = show;
                if (animated) {
                    emptyViewContainer.textView.animate().alpha(show ? 0f : 1f).start();
                } else {
                    emptyViewContainer.textView.animate().cancel();
                    emptyViewContainer.textView.setAlpha(show ? 0f : 1f);
                }
            }
        };
        try {
            topicsEmptyView.stickerView.getImageReceiver().setAutoRepeat(2);
        } catch (Exception ignore) {
        }
        topicsEmptyView.showProgress(loadingTopics, fragmentBeginToShow);
        topicsEmptyView.title.setText(getString(R.string.NoTopics));
        updateTopicsEmptyViewText();

        emptyViewContainer.addView(flickerLoadingView);
        emptyViewContainer.addView(topicsEmptyView);
        contentView.addView(emptyViewContainer);

        recyclerListView.setEmptyView(emptyViewContainer);

        bottomOverlayContainer = new FrameLayout(context) {
            @Override
            protected void dispatchDraw(@NonNull Canvas canvas) {
                int bottom = Theme.chat_composeShadowDrawable.getIntrinsicHeight();
                Theme.chat_composeShadowDrawable.setBounds(0, 0, getMeasuredWidth(), bottom);
                Theme.chat_composeShadowDrawable.draw(canvas);
                super.dispatchDraw(canvas);
            }
        };
        bottomOverlayChatText = new UnreadCounterTextView(context);
        bottomOverlayContainer.addView(bottomOverlayChatText);
        contentView.addView(bottomOverlayContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 51, Gravity.BOTTOM));
        bottomOverlayChatText.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (bottomButtonType == BOTTOM_BUTTON_TYPE_REPORT) {
                    AlertsCreator.showBlockReportSpamAlert(TopicsFragment.this, -chatId, null, getCurrentChat(), null, false, chatFull, param -> {
                        if (param == 0) {
                            updateChatInfo();
                        } else {
                            finishFragment();
                        }
                    }, getResourceProvider());
                } else {
                    joinToGroup();
                }
            }
        });

        bottomOverlayProgress = new RadialProgressView(context, themeDelegate);
        bottomOverlayProgress.setSize(AndroidUtilities.dp(22));
        bottomOverlayProgress.setVisibility(View.INVISIBLE);
        bottomOverlayContainer.addView(bottomOverlayProgress, LayoutHelper.createFrame(30, 30, Gravity.CENTER));

        closeReportSpam = new ImageView(context);
        closeReportSpam.setImageResource(R.drawable.miniplayer_close);
        closeReportSpam.setContentDescription(getString(R.string.Close));
        closeReportSpam.setBackground(Theme.AdaptiveRipple.circle(getThemedColor(Theme.key_chat_topPanelClose)));
        closeReportSpam.setColorFilter(new PorterDuffColorFilter(getThemedColor(Theme.key_chat_topPanelClose), PorterDuff.Mode.MULTIPLY));
        closeReportSpam.setScaleType(ImageView.ScaleType.CENTER);
        bottomOverlayContainer.addView(closeReportSpam, LayoutHelper.createFrame(36, 36, Gravity.RIGHT | Gravity.TOP, 0, 6, 2, 0));
        closeReportSpam.setOnClickListener(v -> {
            getMessagesController().hidePeerSettingsBar(-chatId, null, getCurrentChat());
            updateChatInfo();
        });
        closeReportSpam.setVisibility(View.GONE);

        updateChatInfo();

        fullscreenView = new FrameLayout(context) {
            @Override
            protected boolean drawChild(@NonNull Canvas canvas, View child, long drawingTime) {
                if (child == searchTabsView && isInPreviewMode()) {
                    int y = (int) (searchTabsView.getY() + searchTabsView.getMeasuredHeight());
                    getParentLayout().drawHeaderShadow(canvas, (int) (255 * searchAnimationProgress), y);
//                    if (searchAnimationProgress > 0) {
//                        if (searchAnimationProgress < 1) {
//                            int a = Theme.dividerPaint.getAlpha();
//                            Theme.dividerPaint.setAlpha((int) (a * searchAnimationProgress));
//                            canvas.drawLine(0, y, getMeasuredWidth(), y, Theme.dividerPaint);
//                            Theme.dividerPaint.setAlpha(a);
//                        } else {
//                            canvas.drawLine(0, y, getMeasuredWidth(), y, Theme.dividerPaint);
//                        }
//                    }
                }
                return super.drawChild(canvas, child, drawingTime);
            }
        };
        if (parentDialogsActivity == null) {
            contentView.addView(fullscreenView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL));
        }
        searchContainer = new MessagesSearchContainer(context);
        searchContainer.setVisibility(View.GONE);
        fullscreenView.addView(searchContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL, 0, 44, 0, 0));

        searchContainer.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));

        getMessagesStorage().loadChatInfo(chatId, true, null, true, false, 0);

        topPanelLayout = new DialogsActivityTopPanelLayout(context);
        topPanelLayout.setPadding(dp(11), dp(21), dp(11), dp(21));
        BlurredBackgroundDrawable topPanelLayoutBackground = iBlur3FactoryLiquidGlass.create(topPanelLayout, BlurredBackgroundProviderImpl.topPanel(resourceProvider));
        topPanelLayoutBackground.setRadius(dp(24));
        topPanelLayoutBackground.setPadding(dp(7));
        topPanelLayout.setBlurredBackground(topPanelLayoutBackground);
        topPanelLayout.setOnAnimatedHeightChangedListener(() -> {
            blur3_InvalidateBlur();
            checkUi_listViewPadding();
            if (recyclerListView != null) {
                recyclerListView.requestLayout();
            }
        });
        topPanelLayout.addOnLayoutChangeListener((v, left, top, right, bottom,
                                                  oldLeft, oldTop, oldRight, oldBottom) -> {
            if (bottom - top != oldBottom - oldTop) {
                // AnimatedLinearLayout's metadata callback follows animation
                // frames, but an already-active group call can populate the
                // wrapper during its very first layout without producing such
                // a frame. Apply that measured height immediately; otherwise
                // the topics list keeps paddingTop=0 until the first scroll.
                blur3_InvalidateBlur();
                checkUi_listViewPadding();
                if (recyclerListView != null) {
                    recyclerListView.requestLayout();
                }
            }
        });

        contentView.addView(topPanelLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP, 0, -14, 0, 0));

        TLRPC.Chat currentChat = getCurrentChat();
        if (currentChat != null) {
            pendingRequestsDelegate = new ChatActivityMemberRequestsDelegate(this, currentChat);
            topPanelLayout.addView(pendingRequestsDelegate.getView(), LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40));
            topPanelLayout.setPriority(pendingRequestsDelegate.getView(), 3);
            topPanelLayout.setDebugName(pendingRequestsDelegate.getView(), "pendingRequestsDelegate");
            pendingRequestsDelegate.setDelegate((v, a) -> topPanelLayout.setViewVisible(pendingRequestsDelegate.getView(), v, a));
            pendingRequestsDelegate.setChatInfo(chatFull, false);
        }

        if (!inPreviewMode) {
            fragmentContextViewWrapper = new FrameLayout(context);
            topPanelLayout.addView(fragmentContextViewWrapper);
            topPanelLayout.setPriority(fragmentContextViewWrapper, 4);
            topPanelLayout.setDebugName(fragmentContextViewWrapper, "fragment context");
            topPanelLayout.setViewVisible(fragmentContextViewWrapper, true, false);

            fragmentContextView = new FragmentContextView(context, this, false, themeDelegate) {
                @Override
                public void setVisibility(int visibility) {
                    topPanelLayout.setViewVisible(fragmentContextViewWrapper, visibility == VISIBLE, true);
                }
            };
            fragmentContextViewWrapper.addView(fragmentContextView);
            topPanelLayout.setCallFragmentContextView(fragmentContextView);
        }

        // An already-running voice chat can become visible while the first
        // layout pass is being prepared. Reconcile the measured panel inset
        // before that frame reaches the screen; otherwise the topics are
        // drawn at y=0 once and only jump below the panel after a touch/scroll.
        topPanelLayout.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                ViewTreeObserver observer = topPanelLayout.getViewTreeObserver();
                if (observer.isAlive()) {
                    observer.removeOnPreDrawListener(this);
                }
                int oldPaddingTop = recyclerListView.getPaddingTop();
                checkUi_listViewPadding();
                boolean changed = oldPaddingTop != recyclerListView.getPaddingTop();
                if (changed) {
                    recyclerListView.requestLayout();
                    blur3_InvalidateBlur();
                }
                return !changed;
            }
        });

        FrameLayout.LayoutParams layoutParams = LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT);
        if (inPreviewMode) {
            layoutParams.topMargin = AndroidUtilities.statusBarHeight;
        }
        if (!isInPreviewMode()) {
            contentView.addView(actionBar, layoutParams);
        }

        checkForLoadMore();

        blurredView = new View(context) {
            @Override
            public void setAlpha(float alpha) {
                super.setAlpha(alpha);
                if (fragmentView != null) {
                    fragmentView.invalidate();
                }
            }
        };
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            blurredView.setForeground(new ColorDrawable(ColorUtils.setAlphaComponent(getThemedColor(Theme.key_windowBackgroundWhite), 100)));
        }
        blurredView.setFocusable(false);
        blurredView.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        blurredView.setOnClickListener(e -> {
            finishPreviewFragment();
        });
        blurredView.setFitsSystemWindows(true);

        bottomPannelVisible = true;

        if (inPreviewMode && AndroidUtilities.isTablet()) {
            for (BaseFragment fragment : getParentLayout().getFragmentStack()) {
                if (fragment instanceof DialogsActivity && ((DialogsActivity) fragment).isMainDialogList()) {
                    MessagesStorage.TopicKey topicKey = ((DialogsActivity) fragment).getOpenedDialogId();
                    if (topicKey.dialogId == -chatId) {
                        selectedTopicForTablet = topicKey.topicId;
                        break;
                    }
                }
            }
            updateTopicsList(false, false);
        }
        updateChatInfo();
        updateColors();

        if (ChatObject.isBoostSupported(getCurrentChat())) {
            getMessagesController().getBoostsController().getBoostsStats(-chatId, boostsStatus -> this.boostsStatus = boostsStatus);
        }

        ViewCompat.setOnApplyWindowInsetsListener(fragmentView, this::onApplyWindowInsets);
        return fragmentView;
    }

    private void updateTopicsEmptyViewText() {
        if (topicsEmptyView == null || topicsEmptyView.subtitle == null) {
            return;
        }
        if (topicsController.hasLoadError(chatId)) {
            topicsEmptyView.title.setText(getString(R.string.ErrorOccurred));
            topicsEmptyView.subtitle.setText(getString(R.string.TryAgain));
            topicsEmptyView.button.setText(getString(R.string.Retry));
            topicsEmptyView.button.setVisibility(View.VISIBLE);
            topicsEmptyView.button.setOnClickListener(v -> {
                topicsController.loadTopics(chatId, false, TopicsController.LOAD_TYPE_PRELOAD);
                checkLoading();
                updateTopicsEmptyViewText();
            });
            return;
        }
        topicsEmptyView.title.setText(getString(R.string.NoTopics));
        topicsEmptyView.button.setVisibility(View.GONE);
        topicsEmptyView.button.setOnClickListener(null);
        SpannableStringBuilder spannableStringBuilder = new SpannableStringBuilder("d");
        ColoredImageSpan coloredImageSpan = new ColoredImageSpan(R.drawable.ic_ab_other);
        coloredImageSpan.setSize(AndroidUtilities.dp(16));
        spannableStringBuilder.setSpan(coloredImageSpan, 0, 1, 0);
        if (ChatObject.canUserDoAdminAction(getCurrentChat(), ChatObject.ACTION_MANAGE_TOPICS)) {
            topicsEmptyView.subtitle.setText(
                    AndroidUtilities.replaceCharSequence("%s", AndroidUtilities.replaceTags(getString(R.string.NoTopicsDescription)), spannableStringBuilder)
            );
        } else {
            String general = getString(R.string.General);
            TLRPC.TL_forumTopic topic = getMessagesController().getTopicsController().findTopic(chatId, 1);
            if (topic != null) {
                general = topic.title;
            }
            topicsEmptyView.subtitle.setText(
                    AndroidUtilities.replaceTags(LocaleController.formatString("NoTopicsDescriptionUser", R.string.NoTopicsDescriptionUser, general))
            );
        }
    }

    private void updateColors() {
        if (bottomOverlayProgress != null) {
            bottomOverlayProgress.setProgressColor(getThemedColor(Theme.key_chat_fieldOverlayText));
        }
        if (floatingButton != null) {
            floatingButton.updateColors();
        }
        if (bottomOverlayContainer != null) {
            bottomOverlayContainer.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        }
        if (actionBar != null) {
            actionBar.setActionModeColor(getThemedColor(Theme.key_actionBarActionModeDefault));
            actionBar.updateColors();
        }
        if (topPanelLayout != null) {
            topPanelLayout.updateColors();
        }
        if (avatarContainer != null) {
            avatarContainer.updateColors();
        }
        if (fragmentContextView != null) {
            fragmentContextView.updateColors();
        }
        if (iBlur3SourceColor != null) {
            iBlur3SourceColor.setColor(getThemedColor(Theme.key_windowBackgroundWhite));
        }
        // The API 31+ source RenderNodes contain an explicit themed base fill.
        // Re-record them on a live theme change; updating only the fallback
        // color source leaves the previous light/dark fill cached.
        iBlur3Invalidated = true;
        if (contentView != null) {
            contentView.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
            contentView.invalidate();
        }
        if (searchContainer != null) {
            searchContainer.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        }
        blur3_InvalidateBlur();
    }

    private void openProfile(boolean byAvatar) {
        if (byAvatar) {
            TLRPC.Chat chat = getCurrentChat();
            if (chat != null && (chat.photo == null || chat.photo instanceof TLRPC.TL_chatPhotoEmpty)) {
                byAvatar = false;
            }
        }
        Bundle args = new Bundle();
        args.putLong("chat_id", chatId);
        ProfileActivity fragment = new ProfileActivity(args, avatarContainer.getSharedMediaPreloader());
        fragment.setChatInfo(chatFull);
        fragment.setPlayProfileAnimation(fragmentView.getMeasuredHeight() > fragmentView.getMeasuredWidth() && avatarContainer.getAvatarImageView().getImageReceiver().hasImageLoaded() && byAvatar ? 2 : 1);
        presentFragment(fragment);
    }

    public void switchToChat(boolean removeFragment) {
        removeFragmentOnTransitionEnd = removeFragment;

        Bundle bundle = new Bundle();
        bundle.putLong("chat_id", chatId);
        ChatActivity chatActivity = new ChatActivity(bundle);
        chatActivity.setSwitchFromTopics(true);
        presentFragment(chatActivity);
    }

    private void openParentSearch() {
        if (parentDialogsActivity != null && parentDialogsActivity.searchItem != null) {
            parentDialogsActivity.searchItem.performClick();
        }
    }

    @Override
    public boolean allowFinishFragmentInsteadOfRemoveFromStack() {
        return false;
    }

    public void checkUi_listViewPadding() {
        float top = 0;
        if (parentDialogsActivity != null) {
            top += parentDialogsActivity.getTopPanelAnimatedHeight();
            if (topPanelLayout != null) {
                topPanelLayout.setTranslationY(top - dp(7) * parentDialogsActivity.getTopPanelVisibility());
                top += topPanelLayout.getAnimatedHeightWithPadding(lerp(dp(14), dp(7), parentDialogsActivity.getTopPanelVisibility()));
            }
        } else {
            if (topPanelLayout != null) {
                top += topPanelLayout.getAnimatedHeightWithPadding(dp(14));
            }
        }

        topPanelAnimatedInset = top;
        updateSearchTopInset();

        final int bottom = navigationBarHeight + additionNavigationBarHeight
            + (bottomPannelVisible ? AndroidUtilities.dp(51) : 0);

        recyclerListView.setPadding(0, (int) (top), 0, bottom);
    }

    float transitionPadding;

    public void setTransitionPadding(int transitionPadding) {
        this.transitionPadding = transitionPadding;
        updateFloatingButtonOffset();
    }

    public void setParentDialogsActivity(DialogsActivity parentDialogsActivity) {
        this.parentDialogsActivity = parentDialogsActivity;
    }

    private class TopicsRecyclerView extends BlurredRecyclerView {

        private boolean firstLayout = true;
        private boolean ignoreLayout;
        private int archiveVisibilityAnimationGeneration;
        private ValueAnimator archiveSettleAnimator;
        private View manuallyDrawnGeneralTopicView;
        private boolean hiddenGeneralPulledThisGesture;

        Paint paint = new Paint();
        RectF rectF = new RectF();

        public TopicsRecyclerView(Context context) {
            super(context);
            useLayoutPositionOnClick = true;
            additionalClipBottom = AndroidUtilities.dp(200);
        }

        private float viewOffset;

        public void setViewsOffset(float viewOffset) {
            this.viewOffset = viewOffset;
            int n = getChildCount();
            for (int i = 0; i < n; i++) {
                getChildAt(i).setTranslationY(viewOffset);
            }

            if (selectorPosition != NO_POSITION) {
                View v = getLayoutManager().findViewByPosition(selectorPosition);
                if (v != null) {
                    selectorRect.set(v.getLeft(), (int) (v.getTop() + viewOffset), v.getRight(), (int) (v.getBottom() + viewOffset));
                    selectorDrawable.setBounds(selectorRect);
                }
            }
            invalidate();
            blur3_InvalidateBlur();
        }

        @Override
        public void captureCalculateHash(IBlur3Hash builder, RectF position) {
            super.captureCalculateHash(builder, position);
            // RecyclerListView hashes child display lists, not their transient
            // translation. The forum's elastic reveal moves every row through
            // viewOffset without a normal RecyclerView scroll, so include that
            // geometry (and animated call-panel padding) explicitly. This keeps
            // the glass source moving continuously instead of updating in one
            // abrupt jump when the first real scroll event arrives.
            builder.addF(viewOffset);
            builder.add(getPaddingTop());
            builder.addF(topPanelAnimatedInset);
        }

        public float getViewOffset() {
            return viewOffset;
        }

        private float getHiddenGeneralReveal() {
            if (layoutManager == null || hiddenCount <= 0
                    || pullViewState != ARCHIVE_ITEM_STATE_HIDDEN) {
                return 0f;
            }
            int generalPosition = findGeneralTopicPosition();
            View generalView = generalPosition == RecyclerView.NO_POSITION
                    ? null : layoutManager.findViewByPosition(generalPosition);
            if (generalView == null) {
                return 0f;
            }
            return Math.max(0f, generalView.getY()
                    + generalView.getMeasuredHeight() - getPaddingTop());
        }

        private void beginHiddenGeneralGesture() {
            hiddenGeneralPulledThisGesture = false;
        }

        private void trackHiddenGeneralPull(boolean isDragging, int dy,
                                            float revealBefore, float offsetBefore) {
            if (!isDragging || pullViewState != ARCHIVE_ITEM_STATE_HIDDEN) {
                return;
            }
            final float epsilon = AndroidUtilities.dpf2(0.5f);
            final float revealAfter = getHiddenGeneralReveal();
            final float offsetAfter = getViewOffset();
            if (dy < 0 && (revealAfter > revealBefore + epsilon
                    || offsetAfter > offsetBefore + epsilon)) {
                hiddenGeneralPulledThisGesture = true;
            } else if (dy > 0 && revealAfter <= epsilon && offsetAfter <= epsilon) {
                // The finger returned through the hidden anchor. Releasing now
                // is an ordinary list scroll and must not start archive settle.
                hiddenGeneralPulledThisGesture = false;
            }
        }

        private boolean consumeHiddenGeneralPull() {
            boolean pulled = hiddenGeneralPulledThisGesture;
            hiddenGeneralPulledThisGesture = false;
            return pulled;
        }

        private void resetHiddenGeneralPullGesture() {
            hiddenGeneralPulledThisGesture = false;
            startArchivePullingTime = 0;
            canShowHiddenArchive = false;
            pullViewState = ARCHIVE_ITEM_STATE_HIDDEN;
            setOverScrollMode(View.OVER_SCROLL_NEVER);
            if (viewOffset != 0f) {
                setViewsOffset(0f);
            }
            if (pullForegroundDrawable != null) {
                pullForegroundDrawable.colorize(false);
                pullForegroundDrawable.resetText();
                pullForegroundDrawable.setPullProgress(0f);
                pullForegroundDrawable.scrollDy = 0;
                pullForegroundDrawable.setListView(this);
            }
        }

        @Override
        public void addView(View child, int index, ViewGroup.LayoutParams params) {
            super.addView(child, index, params);
            child.setTranslationY(viewOffset);
            child.setTranslationX(0);
            child.setAlpha(1f);
        }

        @Override
        public void removeView(View view) {
            super.removeView(view);
            view.setTranslationY(0);
            view.setTranslationX(0);
            view.setAlpha(1f);
        }

        @Override
        public void onDraw(Canvas canvas) {
            if (pullForegroundDrawable != null && viewOffset != 0) {
                int save = canvas.save();
                canvas.clipRect(0, getPullForegroundClipTop(), getMeasuredWidth(),
                        getMeasuredHeight() + additionalClipBottom);
                int pTop = getPaddingTop();
                if (pTop != 0) {
                    canvas.translate(0, pTop);
                }
                pullForegroundDrawable.drawOverScroll(canvas);
                canvas.restoreToCount(save);
            }
            super.onDraw(canvas);
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            int screenClipSave = canvas.save();
            canvas.clipRect(0, getScreenContentClipTop(), getMeasuredWidth(),
                    getMeasuredHeight() + additionalClipBottom);
            // DiffUtil may temporarily expose old and rebound General holders.
            // Only the still-attached swiped holder may own the manual draw pass.
            View generalTopicView = isAttachedGeneralTopicView(generalTopicViewMoving)
                    ? generalTopicViewMoving : null;
            if (generalTopicView != null) {
                canvas.save();
                int generalTopicClipTop = getGeneralTopicClipTop();
                if (generalTopicClipTop != 0) {
                    // BlurredRecyclerView applies this clip inside its own
                    // dispatchDraw. The manually drawn General cell is drawn
                    // before that call. Unlike regular rows, hidden General
                    // must also stay below animated top panels (group call,
                    // pending requests), whose height is part of paddingTop
                    // but not blurTopPadding.
                    canvas.clipRect(0, generalTopicClipTop, getMeasuredWidth(), getMeasuredHeight() + additionalClipBottom);
                }
                canvas.translate(generalTopicView.getLeft(), generalTopicView.getY());
                generalTopicView.draw(canvas);
                canvas.restore();
            }
            manuallyDrawnGeneralTopicView = generalTopicView;
            try {
                super.dispatchDraw(canvas);
                if (drawMovingViewsOverlayed()) {
                    paint.setColor(getThemedColor(Theme.key_windowBackgroundWhite));
                    for (int i = 0; i < getChildCount(); i++) {
                        View view = getChildAt(i);
                        if (manuallyDrawnGeneralTopicView != null && isAttachedGeneralTopicView(view)) {
                            continue;
                        }

                        if ((view instanceof DialogCell && ((DialogCell) view).isMoving()) || (view instanceof DialogsAdapter.LastEmptyView && ((DialogsAdapter.LastEmptyView) view).moving)) {
                            int generalClipSave = -1;
                            int generalTopicClipTop = getGeneralTopicClipTop();
                            if (isAttachedGeneralTopicView(view) && generalTopicClipTop != 0) {
                                generalClipSave = canvas.save();
                                canvas.clipRect(0, generalTopicClipTop, getMeasuredWidth(), getMeasuredHeight() + additionalClipBottom);
                            }
                            try {
                                if (view.getAlpha() != 1f) {
                                    rectF.set(view.getX(), view.getY(), view.getX() + view.getMeasuredWidth(), view.getY() + view.getMeasuredHeight());
                                    canvas.saveLayerAlpha(rectF, (int) (255 * view.getAlpha()), Canvas.ALL_SAVE_FLAG);
                                } else {
                                    canvas.save();
                                }
                                canvas.translate(view.getX(), view.getY());
                                canvas.drawRect(0, 0, view.getMeasuredWidth(), view.getMeasuredHeight(), paint);
                                view.draw(canvas);
                                canvas.restore();
                            } finally {
                                if (generalClipSave >= 0) {
                                    canvas.restoreToCount(generalClipSave);
                                }
                            }
                        }
                    }
                    invalidate();
                }
            } finally {
                manuallyDrawnGeneralTopicView = null;
                canvas.restoreToCount(screenClipSave);
            }
        }

        private boolean isAttachedGeneralTopicView(View view) {
            if (!(view instanceof TopicDialogCell) || view.getParent() != this || indexOfChild(view) < 0) {
                return false;
            }
            TLRPC.TL_forumTopic topic = ((TopicDialogCell) view).forumTopic;
            return topic != null && topic.id == 1;
        }

        private int getGeneralTopicClipTop() {
            // BlurredRecyclerView clips ordinary content only at the fixed
            // action-bar blur boundary so it remains available as a glass
            // source. General is a hidden pull-to-reveal row, therefore it
            // must use the real content boundary including every animated
            // top-panel inset.
            return hiddenCount > 0
                    ? Math.max(blurTopPadding, getPaddingTop())
                    : blurTopPadding;
        }

        private int getScreenContentClipTop() {
            // BlurredRecyclerView normally drops this clip while an EdgeEffect
            // is active. The forum pull gesture intentionally toggles that
            // state, which made rows and the hidden-topic fill flash beneath
            // the floating glass header. Keep the final screen pass clipped;
            // blur capture still calls drawChild() directly with
            // alwaysDrawChild and therefore retains its full source.
            return Math.max(blurTopPadding, getPaddingTop());
        }

        private int getPullForegroundClipTop() {
            int clipTop = getScreenContentClipTop();
            if (actionBar != null && actionBar.getVisibility() == View.VISIBLE) {
                // recyclerListView is shifted upward by blurTopPadding while
                // ActionBar is positioned in contentView coordinates.
                // Convert the floating header's bottom into list coordinates
                // so archivePullDownBackground never becomes an opaque slab
                // behind the glass islands.
                clipTop = Math.max(clipTop, actionBar.getBottom() - getTop());
            }
            return clipTop;
        }

        private boolean drawMovingViewsOverlayed() {
            return getItemAnimator() != null && getItemAnimator().isRunning() && (dialogRemoveFinished != 0 || dialogInsertFinished != 0 || dialogChangeFinished != 0);
        }

        @Override
        public boolean drawChild(Canvas canvas, View child, long drawingTime) {
            boolean movingOverlay = drawMovingViewsOverlayed()
                    && child instanceof DialogCell && ((DialogCell) child).isMoving();
            boolean generalTopic = isAttachedGeneralTopicView(child);
            boolean manualGeneral = manuallyDrawnGeneralTopicView != null
                    && generalTopic;
            if (movingOverlay || manualGeneral) {
                return true;
            }
            if (generalTopic) {
                int generalTopicClipTop = getGeneralTopicClipTop();
                int save = canvas.save();
                canvas.clipRect(0, generalTopicClipTop, getMeasuredWidth(), getMeasuredHeight() + additionalClipBottom);
                try {
                    return super.drawChild(canvas, child, drawingTime);
                } finally {
                    canvas.restoreToCount(save);
                }
            }
            return super.drawChild(canvas, child, drawingTime);
        }

        @Override
        protected void onDetachedFromWindow() {
            cancelArchiveSettleAnimation();
            finishGeneralTopicMoving();
            super.onDetachedFromWindow();
        }

        @Override
        public void setAdapter(RecyclerView.Adapter adapter) {
            super.setAdapter(adapter);
            firstLayout = true;
        }

        private void checkIfAdapterValid() {
            RecyclerView.Adapter adapter = getAdapter();
            if (lastItemsCount != adapter.getItemCount() && !forumTopicsListFrozen) {
                ignoreLayout = true;
                adapter.notifyDataSetChanged();
                ignoreLayout = false;
            }
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            if (firstLayout && getMessagesController().dialogsLoaded) {
                if (hiddenCount > 0) {
                    ignoreLayout = true;
                    LinearLayoutManager layoutManager = (LinearLayoutManager) getLayoutManager();
                    layoutManager.scrollToPositionWithOffset(1, (int) actionBar.getTranslationY());
                    ignoreLayout = false;
                }
                firstLayout = false;
            }
            super.onMeasure(widthSpec, heightSpec);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
            super.onLayout(changed, l, t, r, b);

            if ((dialogRemoveFinished != 0 || dialogInsertFinished != 0 || dialogChangeFinished != 0) && !itemAnimator.isRunning()) {
                onDialogAnimationFinished();
            }
        }

        @Override
        public void requestLayout() {
            if (ignoreLayout) {
                return;
            }
            super.requestLayout();
        }

        private boolean isArchiveSettleAnimationRunning() {
            return archiveSettleAnimator != null;
        }

        private void cancelArchiveSettleAnimation() {
            archiveVisibilityAnimationGeneration++;
            if (archiveSettleAnimator != null) {
                archiveSettleAnimator.removeAllListeners();
                archiveSettleAnimator.cancel();
                archiveSettleAnimator = null;
            }
            waitingForScrollFinished = false;
            setScrollEnabled(true);
        }

        private int findGeneralTopicPosition() {
            for (int i = 0; i < forumTopics.size(); i++) {
                Item item = forumTopics.get(i);
                if (item != null && item.topic != null && item.topic.id == 1) {
                    return i;
                }
            }
            return RecyclerView.NO_POSITION;
        }

        private void scrollToGeneralTopicAnchor(boolean showGeneral) {
            int generalPosition = findGeneralTopicPosition();
            if (generalPosition == RecyclerView.NO_POSITION) {
                return;
            }
            int targetPosition = showGeneral ? generalPosition : generalPosition + 1;
            if (targetPosition >= 0 && targetPosition < adapter.getItemCount()) {
                layoutManager.scrollToPositionWithOffset(targetPosition, 0);
            }
        }

        private void animateArchiveSettle(int scrollDistance, Runnable endAction) {
            cancelArchiveSettleAnimation();
            final int animationGeneration = archiveVisibilityAnimationGeneration;
            final float startViewOffset = getViewOffset();
            if (Math.abs(scrollDistance) <= AndroidUtilities.dp(1)
                    && Math.abs(startViewOffset) <= AndroidUtilities.dpf2(0.5f)) {
                setViewsOffset(0f);
                if (endAction != null) {
                    endAction.run();
                }
                finishGeneralTopicMoving();
                return;
            }

            stopScroll();
            waitingForScrollFinished = true;
            setScrollEnabled(false);
            final int[] appliedScroll = new int[1];
            archiveSettleAnimator = ValueAnimator.ofFloat(0f, 1f);
            archiveSettleAnimator.addUpdateListener(animation -> {
                if (animationGeneration != archiveVisibilityAnimationGeneration) {
                    return;
                }
                float progress = (float) animation.getAnimatedValue();
                int targetScroll = Math.round(scrollDistance * progress);
                int delta = targetScroll - appliedScroll[0];
                if (delta != 0) {
                    scrollBy(0, delta);
                    appliedScroll[0] = targetScroll;
                }
                if (startViewOffset != 0f) {
                    setViewsOffset(startViewOffset * (1f - progress));
                }
                blur3_InvalidateBlur();
            });
            archiveSettleAnimator.setDuration(Math.max(180, Math.min(320,
                    180 + Math.round(140f * Math.min(1f,
                            (Math.abs(scrollDistance) + Math.abs(startViewOffset))
                                    / Math.max(1f, AndroidUtilities.dp(72)))))));
            archiveSettleAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
            archiveSettleAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (animationGeneration != archiveVisibilityAnimationGeneration) {
                        return;
                    }
                    archiveSettleAnimator = null;
                    if (getViewOffset() != 0f) {
                        setViewsOffset(0f);
                    }
                    waitingForScrollFinished = false;
                    setScrollEnabled(true);
                    if (endAction != null) {
                        endAction.run();
                    }
                    finishGeneralTopicMoving();
                    blur3_InvalidateBlur();
                }
            });
            archiveSettleAnimator.start();
        }

        private void setArchiveHidden(boolean shown, DialogCell dialogCell) {
            cancelArchiveSettleAnimation();
            final int animationGeneration = archiveVisibilityAnimationGeneration;
            hiddenShown = shown;
            if (!hiddenShown) {
                updatePullState();
                if (dialogCell != null) {
                    disableActionBarScrolling = true;
                    stopScroll();
                    waitingForScrollFinished = true;

                    // toggleShowTopic/updateTopicsList run immediately after
                    // this call and may move/rebind the General row. Wait for
                    // that layout and any DiffUtil move, then close it by its
                    // real on-screen height.
                    // The old immediate scrollToPositionWithOffset(1, 0)
                    // teleported every row and made the list appear to fly
                    // behind the floating ActionBar island.
                    AndroidUtilities.doOnPreDraw(this, () -> {
                        Runnable settleGeneralTopic = () -> {
                            if (animationGeneration != archiveVisibilityAnimationGeneration
                                    || hiddenShown || layoutManager == null) {
                                return;
                            }

                            int generalPosition = findGeneralTopicPosition();

                            View generalView = generalPosition != RecyclerView.NO_POSITION
                                    ? layoutManager.findViewByPosition(generalPosition) : null;
                            if (generalView != null) {
                                final int distance = generalView.getBottom() - getPaddingTop();
                                if (distance > AndroidUtilities.dp(1)) {
                                    animateArchiveSettle(distance, () -> {
                                        if (!hiddenShown) {
                                            scrollToGeneralTopicAnchor(false);
                                        }
                                    });
                                    return;
                                }
                            }
                            if (generalPosition != RecyclerView.NO_POSITION) {
                                scrollToGeneralTopicAnchor(false);
                            }
                            finishGeneralTopicMoving();
                            waitingForScrollFinished = false;
                            blur3_InvalidateBlur();
                        };
                        if (itemAnimator != null && itemAnimator.isRunning()) {
                            itemAnimator.isRunning(() ->
                                    AndroidUtilities.doOnPreDraw(this, settleGeneralTopic));
                        } else {
                            settleGeneralTopic.run();
                        }
                    });
                }
            } else {
                stopScroll();
                if (getViewOffset() != 0f) {
                    setViewsOffset(0f);
                }
                scrollToGeneralTopicAnchor(true);
                updatePullState();
                if (dialogCell != null) {
                    dialogCell.resetPinnedArchiveState();
                    dialogCell.invalidate();
                }
                // updateTopicsList() is called immediately after this method
                // and may finish without a move animation. Release the manual
                // General-row owner after that layout in both cases, otherwise
                // a stale holder can survive until the next pull gesture.
                AndroidUtilities.doOnPreDraw(this, () -> {
                    Runnable finishMovingGeneral = () -> {
                        if (animationGeneration == archiveVisibilityAnimationGeneration && hiddenShown) {
                            finishGeneralTopicMoving();
                        }
                    };
                    if (itemAnimator != null && itemAnimator.isRunning()) {
                        itemAnimator.isRunning(() -> finishMovingGeneral.run());
                    } else {
                        finishMovingGeneral.run();
                    }
                });
            }
            if (emptyView != null) {
                emptyView.forceLayout();
            }
        }

        private void updatePullState() {
            pullViewState = !hiddenShown ? ARCHIVE_ITEM_STATE_HIDDEN : ARCHIVE_ITEM_STATE_PINNED;
            if (pullForegroundDrawable != null) {
                pullForegroundDrawable.setWillDraw(pullViewState != ARCHIVE_ITEM_STATE_PINNED);
            }
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            int action = e.getActionMasked();
            if (fastScrollAnimationRunning || waitingForScrollFinished || dialogRemoveFinished != 0 || dialogInsertFinished != 0 || dialogChangeFinished != 0 || (getParentLayout() != null && getParentLayout().isInPreviewMode())) {
                return false;
            }
            if (action == MotionEvent.ACTION_DOWN) {
                beginHiddenGeneralGesture();
                setOverScrollMode(View.OVER_SCROLL_ALWAYS);
            }
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                if (!itemTouchHelper.isIdle() && itemTouchHelperCallback.swipingFolder) {
                    itemTouchHelperCallback.swipeFolderBack = true;
                    if (itemTouchHelper.checkHorizontalSwipe(null, ItemTouchHelper.LEFT) != 0) {
                        if (itemTouchHelperCallback.currentItemViewHolder != null) {
                            ViewHolder viewHolder = itemTouchHelperCallback.currentItemViewHolder;
                            if (viewHolder.itemView instanceof DialogCell) {
                                setArchiveHidden(!hiddenShown, (DialogCell) viewHolder.itemView);
                            }
                        }
                    }
                }
            }
            boolean result = super.onTouchEvent(e);
            final boolean finishGesture =
                    action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL;
            final boolean settleHiddenGeneral =
                    finishGesture && consumeHiddenGeneralPull();
            if (finishGesture && settleHiddenGeneral
                    && pullViewState == ARCHIVE_ITEM_STATE_HIDDEN && hiddenCount > 0) {
                LinearLayoutManager layoutManager = (LinearLayoutManager) getLayoutManager();
                int generalPosition = findGeneralTopicPosition();
                View view = generalPosition != RecyclerView.NO_POSITION
                        ? layoutManager.findViewByPosition(generalPosition) : null;
                if (view != null) {
                    int pTop = getPaddingTop();
                    int height = (int) (AndroidUtilities.dp(
                            SharedConfig.useThreeLinesLayout ? 78 : 72)
                            * PullForegroundDrawable.SNAP_HEIGHT);
                    // Use the visual position only for deciding whether the
                    // threshold was reached. The elastic translation is
                    // animated back to zero separately, so including it in the
                    // RecyclerView scroll distance moves General twice and
                    // leaves stale pull progress for the next gesture.
                    int visualDiff = Math.round(
                            view.getY() - pTop + view.getMeasuredHeight());
                    // Keep the sign: after an upward drag General may finish a
                    // few pixels above the hidden anchor. Clamping that value
                    // to zero skipped the settle animation, then the final
                    // scrollToPositionWithOffset snapped the whole list down.
                    int layoutOffset = view.getBottom() - pTop;
                    long pullingTime = startArchivePullingTime == 0 ? 0
                            : System.currentTimeMillis() - startArchivePullingTime;
                    final int settleDistance;
                    final boolean showGeneral;
                    if (visualDiff < height
                            || pullingTime < PullForegroundDrawable.minPullingTime) {
                        disableActionBarScrolling = true;
                        pullViewState = ARCHIVE_ITEM_STATE_HIDDEN;
                        settleDistance = layoutOffset;
                        showGeneral = false;
                    } else {
                        if (pullViewState != ARCHIVE_ITEM_STATE_SHOWED) {
                            disableActionBarScrolling = true;
                            if (!canShowHiddenArchive) {
                                canShowHiddenArchive = true;
                                try {
                                    performHapticFeedback(
                                            HapticFeedbackConstants.KEYBOARD_TAP,
                                            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
                                } catch (Exception ignored) {}
                                if (pullForegroundDrawable != null) {
                                    pullForegroundDrawable.colorize(true);
                                }
                            }
                            ((DialogCell) view).startOutAnimation();
                            pullViewState = ARCHIVE_ITEM_STATE_SHOWED;
                        }
                        settleDistance = view.getTop() - pTop;
                        showGeneral = true;
                    }

                    animateArchiveSettle(settleDistance, () -> {
                        scrollToGeneralTopicAnchor(showGeneral);
                        if (!showGeneral) {
                            resetHiddenGeneralPullGesture();
                        }
                    });
                } else if (getViewOffset() != 0f) {
                    // A translated child may already be considered off-screen
                    // by LayoutManager. Still settle and clear the gesture so
                    // the next upward drag cannot resurrect its clipped text.
                    animateArchiveSettle(0, () -> {
                        scrollToGeneralTopicAnchor(false);
                        resetHiddenGeneralPullGesture();
                    });
                } else {
                    resetHiddenGeneralPullGesture();
                }
            }
            return result;
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent e) {
            if (fastScrollAnimationRunning || waitingForScrollFinished || dialogRemoveFinished != 0 || dialogInsertFinished != 0 || dialogChangeFinished != 0 || (getParentLayout() != null && getParentLayout().isInPreviewMode())) {
                return false;
            }
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                allowSwipeDuringCurrentTouch = !actionBar.isActionModeShowed();
                checkIfAdapterValid();
            }
            return super.onInterceptTouchEvent(e);
        }

        @Override
        protected boolean allowSelectChildAtPosition(View child) {
            return !(child instanceof HeaderCell) || child.isClickable();
        }
    }

    private void onDialogAnimationFinished() {
        dialogRemoveFinished = 0;
        dialogInsertFinished = 0;
        dialogChangeFinished = 0;
        AndroidUtilities.runOnUIThread(() -> {
//            setDialogsListFrozen(false);
//            updateDialogIndices();
        });
    }

    private void deleteTopics(HashSet<Integer> selectedTopics, Runnable runnable) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle(LocaleController.getPluralString("DeleteTopics", selectedTopics.size()));
        ArrayList<Integer> topicsToRemove = new ArrayList<>(selectedTopics);
        if (selectedTopics.size() == 1) {
            TLRPC.TL_forumTopic topic = topicsController.findTopic(chatId, topicsToRemove.get(0));
            builder.setMessage(LocaleController.formatString(R.string.DeleteSelectedTopic, topic.title));
        } else {
            builder.setMessage(getString(R.string.DeleteSelectedTopics));
        }
        builder.setPositiveButton(getString(R.string.Delete), (dialog, which) -> {
            excludeTopics = new HashSet<>();
            excludeTopics.addAll(selectedTopics);
            updateTopicsList(true, false);
            BulletinFactory.of(TopicsFragment.this).createUndoBulletin(LocaleController.getPluralString("TopicsDeleted", selectedTopics.size()), () -> {
                excludeTopics = null;
                updateTopicsList(true, false);
            }, () -> {
                topicsController.deleteTopics(chatId, topicsToRemove);
                runnable.run();
            }).show();
            clearSelectedTopics();
            dialog.dismiss();
        });
        builder.setNegativeButton(getString(R.string.Cancel), (dialog, which) -> dialog.dismiss());
        AlertDialog alertDialog = builder.create();
        alertDialog.show();
        TextView button = (TextView) alertDialog.getButton(DialogInterface.BUTTON_POSITIVE);
        if (button != null) {
            button.setTextColor(getThemedColor(Theme.key_text_RedBold));
        }
    }

    private boolean showChatPreview(DialogCell cell) {
        try {
            cell.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        } catch (Exception ignored) {}
        final ActionBarPopupWindow.ActionBarPopupWindowLayout[] previewMenu = new ActionBarPopupWindow.ActionBarPopupWindowLayout[1];
        int flags = ActionBarPopupWindow.ActionBarPopupWindowLayout.FLAG_USE_SWIPEBACK;
        previewMenu[0] = new ActionBarPopupWindow.ActionBarPopupWindowLayout(getParentActivity(), R.drawable.popup_fixed_alert, getResourceProvider(), flags);

        TLRPC.TL_forumTopic topic = cell.forumTopic;
        ChatNotificationsPopupWrapper chatNotificationsPopupWrapper = new ChatNotificationsPopupWrapper(getContext(), currentAccount, previewMenu[0].getSwipeBack(), false, false, new ChatNotificationsPopupWrapper.Callback() {
            @Override
            public void dismiss() {
                finishPreviewFragment();
            }

            @Override
            public void toggleSound() {
                SharedPreferences preferences = MessagesController.getNotificationsSettings(currentAccount);
                boolean enabled = !preferences.getBoolean("sound_enabled_" + NotificationsController.getSharedPrefKey(-chatId, topic.id), true);
                preferences.edit().putBoolean("sound_enabled_" + NotificationsController.getSharedPrefKey(-chatId, topic.id), enabled).apply();
                finishPreviewFragment();
                if (BulletinFactory.canShowBulletin(TopicsFragment.this)) {
                    BulletinFactory.createSoundEnabledBulletin(TopicsFragment.this, enabled ? NotificationsController.SETTING_SOUND_ON : NotificationsController.SETTING_SOUND_OFF, getResourceProvider()).show();
                }

            }

            @Override
            public void muteFor(int timeInSeconds) {
                finishPreviewFragment();
                if (timeInSeconds == 0) {
                    if (getMessagesController().isDialogMuted(-chatId, topic.id)) {
                        getNotificationsController().muteDialog(-chatId, topic.id, false);
                    }
                    if (BulletinFactory.canShowBulletin(TopicsFragment.this)) {
                        BulletinFactory.createMuteBulletin(TopicsFragment.this, NotificationsController.SETTING_MUTE_UNMUTE, timeInSeconds, getResourceProvider()).show();
                    }
                } else {
                    getNotificationsController().muteUntil(-chatId, topic.id, timeInSeconds);
                    if (BulletinFactory.canShowBulletin(TopicsFragment.this)) {
                        BulletinFactory.createMuteBulletin(TopicsFragment.this, NotificationsController.SETTING_MUTE_CUSTOM, timeInSeconds, getResourceProvider()).show();
                    }
                }
            }

            @Override
            public void showCustomize() {
                finishPreviewFragment();
                AndroidUtilities.runOnUIThread(() -> {
                    Bundle args = new Bundle();
                    args.putLong("dialog_id", -chatId);
                    args.putLong("topic_id", topic.id);
                    presentFragment(new ProfileNotificationsActivity(args, themeDelegate));
                }, 500);
            }

            @Override
            public void toggleMute() {
                finishPreviewFragment();
                boolean mute = !getMessagesController().isDialogMuted(-chatId, topic.id);
                getNotificationsController().muteDialog(-chatId, topic.id, mute);

                if (BulletinFactory.canShowBulletin(TopicsFragment.this)) {
                    BulletinFactory.createMuteBulletin(TopicsFragment.this, mute ? NotificationsController.SETTING_MUTE_FOREVER : NotificationsController.SETTING_MUTE_UNMUTE, mute ? Integer.MAX_VALUE : 0, getResourceProvider()).show();
                }
            }
        }, getResourceProvider());

        int muteForegroundIndex = previewMenu[0].addViewToSwipeBack(chatNotificationsPopupWrapper.windowLayout);
        chatNotificationsPopupWrapper.type = ChatNotificationsPopupWrapper.TYPE_PREVIEW_MENU;
        chatNotificationsPopupWrapper.update(-chatId, topic.id, null);

        if (ChatObject.canManageTopics(getCurrentChat())) {
            ActionBarMenuSubItem pinItem = new ActionBarMenuSubItem(getParentActivity(), true, false);
            if (topic.pinned) {
                pinItem.setTextAndIcon(getString(R.string.DialogUnpin), R.drawable.msg_unpin);
            } else {
                pinItem.setTextAndIcon(getString(R.string.DialogPin), R.drawable.msg_pin);
            }
            pinItem.setMinimumWidth(160);
            pinItem.setOnClickListener(e -> {
                scrollToTop = true;
                updateAnimated = true;
                topicsController.pinTopic(chatId, topic.id, !topic.pinned, TopicsFragment.this);
                finishPreviewFragment();
            });

            previewMenu[0].addView(pinItem);
        }

        ActionBarMenuSubItem muteItem = new ActionBarMenuSubItem(getParentActivity(), false, false);
        if (getMessagesController().isDialogMuted(-chatId, topic.id)) {
            muteItem.setTextAndIcon(getString(R.string.Unmute), R.drawable.msg_mute);
        } else {
            muteItem.setTextAndIcon(getString(R.string.Mute), R.drawable.msg_unmute);
        }
        muteItem.setMinimumWidth(160);
        muteItem.setOnClickListener(e -> {
            if (getMessagesController().isDialogMuted(-chatId, topic.id)) {
                getNotificationsController().muteDialog(-chatId, topic.id, false);
                finishPreviewFragment();
                if (BulletinFactory.canShowBulletin(TopicsFragment.this)) {
                    BulletinFactory.createMuteBulletin(TopicsFragment.this, NotificationsController.SETTING_MUTE_UNMUTE, 0, getResourceProvider()).show();
                }
            } else {
                previewMenu[0].getSwipeBack().openForeground(muteForegroundIndex);
            }
        });
        previewMenu[0].addView(muteItem);

        if (ChatObject.canManageTopic(currentAccount, getCurrentChat(), topic)) {
            ActionBarMenuSubItem closeItem = new ActionBarMenuSubItem(getParentActivity(), false, false);
            if (topic.closed) {
                closeItem.setTextAndIcon(getString(R.string.RestartTopic), R.drawable.msg_topic_restart);
            } else {
                closeItem.setTextAndIcon(getString(R.string.CloseTopic), R.drawable.msg_topic_close);
            }
            closeItem.setMinimumWidth(160);
            closeItem.setOnClickListener(e -> {
                updateAnimated = true;
                topicsController.toggleCloseTopic(chatId, topic.id, !topic.closed);
                finishPreviewFragment();
            });
            previewMenu[0].addView(closeItem);
        }

        if (ChatObject.canDeleteTopic(currentAccount, getCurrentChat(), topic)) {
            ActionBarMenuSubItem deleteItem = new ActionBarMenuSubItem(getParentActivity(), false, true);
            deleteItem.setTextAndIcon(LocaleController.getPluralString("DeleteTopics", 1), R.drawable.msg_delete);
            deleteItem.setIconColor(getThemedColor(Theme.key_text_RedRegular));
            deleteItem.setTextColor(getThemedColor(Theme.key_text_RedBold));
            deleteItem.setMinimumWidth(160);
            deleteItem.setOnClickListener(e -> {
                HashSet<Integer> hashSet = new HashSet();
                hashSet.add(topic.id);
                deleteTopics(hashSet, this::finishPreviewFragment);
            });
            previewMenu[0].addView(deleteItem);
        }

        boolean mono = getMessagesController().isMonoForum(-chatId);

        prepareBlurBitmap();
        Bundle bundle = new Bundle();
        bundle.putLong("chat_id", chatId);
        ChatActivity chatActivity = new ChatActivity(bundle);
        ForumUtilities.applyTopic(chatActivity, MessagesStorage.TopicKey.of(-chatId, mono ? DialogObject.getPeerDialogId(cell.forumTopic.from_id) : cell.forumTopic.id));
        presentFragmentAsPreviewWithMenu(chatActivity, previewMenu[0]);
        return false;
    }

    private void checkLoading() {
        loadingTopics = topicsController.isLoading(chatId);
        if (topicsEmptyView != null && (forumTopics.size() == 0 || (forumTopics.size() == 1 && forumTopics.get(0).topic != null && forumTopics.get(0).topic.id == 1))) {
            topicsEmptyView.showProgress(loadingTopics, fragmentBeginToShow);
        }
        if (recyclerListView != null) {
            recyclerListView.checkIfEmpty();
        }
        updateCreateTopicButton(true);
    }

    ValueAnimator searchAnimator;
    ValueAnimator searchAnimator2;
    boolean animateSearchWithScale;
    private ViewPagerFixed.TabsView searchTabsView;

    private void animateToSearchView(boolean showSearch) {
        searching = showSearch;
        if (searchAnimator != null) {
            searchAnimator.removeAllListeners();
            searchAnimator.cancel();
        }
        if (searchTabsView == null) {
            searchTabsView = searchContainer.createTabsView(false, 8);
            if (parentDialogsActivity != null) {
                searchTabsView.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
            }
            fullscreenView.addView(searchTabsView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 44));
            updateSearchTopInset();
        }
        searchAnimator = ValueAnimator.ofFloat(searchAnimationProgress, showSearch ? 1f : 0);
        AndroidUtilities.updateViewVisibilityAnimated(searchContainer, false, 1f, true);
        if (parentDialogsActivity != null && parentDialogsActivity.rightSlidingDialogContainer != null) {
            parentDialogsActivity.rightSlidingDialogContainer.enabled = !showSearch;
        }
        animateSearchWithScale = !showSearch && searchContainer.getVisibility() == View.VISIBLE && searchContainer.getAlpha() == 1f;
        searchAnimator.addUpdateListener(animation -> updateSearchProgress((Float) animation.getAnimatedValue()));
        searchContainer.setVisibility(View.VISIBLE);
        if (!showSearch) {
            other.setVisibility(View.VISIBLE);
            actionBar.checkMenuItemsWidth();
        } else {
            // Remove the old menu island at the beginning of the morph.  Its
            // icon is already faded by ActionBarMenuItem; keeping the view in
            // layout until onAnimationEnd made the search pill change width a
            // second time after it had visually settled.
            other.setVisibility(View.GONE);
            actionBar.checkMenuItemsWidth();
            AndroidUtilities.requestAdjustResize(getParentActivity(), classGuid);
            updateCreateTopicButton(false);
        }
        searchAnimator.addListener(new AnimatorListenerAdapter() {

            @Override
            public void onAnimationEnd(Animator animation) {
                super.onAnimationEnd(animation);
                updateSearchProgress(showSearch ? 1f : 0);
                if (!showSearch) {
                    AndroidUtilities.setAdjustResizeToNothing(getParentActivity(), classGuid);
                    searchContainer.setVisibility(View.GONE);
                    updateCreateTopicButton(true);
                }
            }
        });
        // Match ChatActivity's liquid-glass search morph so content, field and
        // both rounded edges arrive together on every refresh rate.
        searchAnimator.setDuration(320);
        searchAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        searchAnimator.start();

        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.needCheckSystemBarColors, true);
    }

    private void updateCreateTopicButton(boolean animated) {
        if (createTopicSubmenu == null) {
            return;
        }
        TLRPC.Chat chatLocal = getMessagesController().getChat(chatId);
        canShowCreateTopic = !ChatObject.isNotInChat(getMessagesController().getChat(chatId)) && ChatObject.canCreateTopic(chatLocal) && !searching && !openedForSelect && !loadingTopics;
        createTopicSubmenu.setVisibility(canShowCreateTopic ? View.VISIBLE : View.GONE);
        hideFloatingButton(!canShowCreateTopic, animated);
    }

    private void updateSearchProgress(float value) {
        value = Utilities.clamp01(value);
        searchAnimationProgress = value;
        actionBar.setSearchFactor(value);
        int color1 = getThemedColor(Theme.key_actionBarDefaultIcon);
        actionBar.setItemsColor(ColorUtils.blendARGB(color1, getThemedColor(Theme.key_actionBarActionModeDefaultIcon), searchAnimationProgress), false);
        actionBar.setItemsColor(ColorUtils.blendARGB(getThemedColor(Theme.key_actionBarActionModeDefaultIcon), getThemedColor(Theme.key_actionBarActionModeDefaultIcon), searchAnimationProgress), true);

        color1 = getThemedColor(Theme.key_actionBarDefaultSelector);
        int color2 = getThemedColor(Theme.key_actionBarActionModeDefaultSelector);
        actionBar.setItemsBackgroundColor(ColorUtils.blendARGB(color1, color2, searchAnimationProgress), false);

        // Fade the complete identity island; fading only text left its avatar
        // floating above the expanded search field.
        avatarContainer.setAlpha(1f - value);
        if (searchTabsView != null) {
            searchTabsView.setAlpha(value);
        }
        updateSearchTopInset();
        searchContainer.setAlpha(value);

        if (isInPreviewMode()) {
            fullscreenView.invalidate();
        }
        contentView.invalidate();

        recyclerListView.setAlpha(1f - value);
        if (animateSearchWithScale) {
            float scale = 0.98f + 0.02f * (1f - searchAnimationProgress);
            recyclerListView.setScaleX(scale);
            recyclerListView.setScaleY(scale);
        }
    }

    private void updateSearchTopInset() {
        float translationY = topPanelAnimatedInset
                - AndroidUtilities.dp(16) * (1f - searchAnimationProgress);
        if (searchTabsView != null) {
            searchTabsView.setTranslationY(translationY);
        }
        if (searchContainer != null) {
            searchContainer.setTranslationY(translationY);
        }
    }

    private ArrayList<TLRPC.TL_forumTopic> getSelectedTopics() {
        ArrayList<TLRPC.TL_forumTopic> topics = new ArrayList<>();
        Iterator<Integer> iterator = selectedTopics.iterator();
        while (iterator.hasNext()) {
            int topicId = iterator.next();
            TLRPC.TL_forumTopic topic = topicsController.findTopic(chatId, topicId);
            if (topic != null) {
                topics.add(topic);
            }
        }
        return topics;
    }

    private void joinToGroup() {
        getMessagesController().addUserToChat(chatId, getUserConfig().getCurrentUser(), 0, null, this, false, () -> {
            joinRequested = false;
            updateChatInfo(true);
        }, e -> {
            if (e != null && "INVITE_REQUEST_SENT".equals(e.text)) {
                SharedPreferences preferences = MessagesController.getNotificationsSettings(currentAccount);
                preferences.edit().putLong("dialog_join_requested_time_" + -chatId, System.currentTimeMillis()).commit();
                JoinGroupAlert.showBulletin(getContext(), this, ChatObject.isChannelAndNotMegaGroup(getCurrentChat()));
                updateChatInfo(true);
                return false;
            }
            return true;
        });
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.closeSearchByActiveAction);
        updateChatInfo();
    }

    private void clearSelectedTopics() {
        selectedTopics.clear();
        actionBar.hideActionMode();
        AndroidUtilities.updateVisibleRows(recyclerListView);
        updateReordering();
    }

    private void toggleSelection(View view) {
        if (view instanceof TopicDialogCell) {
            TopicDialogCell cell = (TopicDialogCell) view;
            if (cell.forumTopic == null) {
                return;
            }
            int id = cell.forumTopic.id;
            if (!selectedTopics.remove(id)) {
                selectedTopics.add(id);
            }
            cell.setChecked(selectedTopics.contains(id), true);

            TLRPC.Chat currentChat = getMessagesController().getChat(chatId);

            if (!selectedTopics.isEmpty()) {
                chekActionMode();
                if (inPreviewMode) {
                    ((View) fragmentView.getParent()).invalidate();
                }
                // Keep the forum identity visible while selection actions
                // appear on the right. The action mode deliberately has no
                // numeric counter, so there is nothing to draw over the avatar.
                actionBar.showActionMode(true);
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.needCheckSystemBarColors);
                Iterator<Integer> iterator = selectedTopics.iterator();
                int unreadCount = 0, readCount = 0;
                int canPinCount = 0, canUnpinCount = 0;
                int canMuteCount = 0, canUnmuteCount = 0;
                while (iterator.hasNext()) {
                    int topicId = iterator.next();
                    TLRPC.TL_forumTopic topic = topicsController.findTopic(chatId, topicId);
                    if (topic != null) {
                        if (topic.unread_count != 0) {
                            unreadCount++;
                        } else {
                            readCount++;
                        }
                        if (ChatObject.canManageTopics(currentChat) && !topic.hidden) {
                            if (topic.pinned) {
                                canUnpinCount++;
                            } else {
                                canPinCount++;
                            }
                        }
                    }
                    if (getMessagesController().isDialogMuted(-chatId, topicId)) {
                        canUnmuteCount++;
                    } else {
                        canMuteCount++;
                    }
                }

                if (unreadCount > 0) {
                    readItem.setVisibility(View.VISIBLE);
                    readItem.setTextAndIcon(getString(R.string.MarkAsRead), R.drawable.msg_markread);
                } else {
                    readItem.setVisibility(View.GONE);
                }
                if (canUnmuteCount != 0) {
                    mute = false;
                    muteItem.setIcon(R.drawable.msg_unmute);
                    muteItem.setContentDescription(getString(R.string.ChatsUnmute));
                } else {
                    mute = true;
                    muteItem.setIcon(R.drawable.msg_mute);
                    muteItem.setContentDescription(getString(R.string.ChatsMute));
                }

                pinItem.setVisibility(canPinCount == 1 && canUnpinCount == 0 ? View.VISIBLE : View.GONE);
                unpinItem.setVisibility(canUnpinCount == 1 && canPinCount == 0 ? View.VISIBLE : View.GONE);
            } else {
                actionBar.hideActionMode();
                return;
            }
            int canPin = 0;
            int canDeleteCount = 0;
            int closedTopicsCount = 0;
            int openTopicsCount = 0;
            int canHideCount = 0;
            int canShowCount = 0;
            Iterator<Integer> iterator = selectedTopics.iterator();
            while (iterator.hasNext()) {
                int topicId = iterator.next();
                TLRPC.TL_forumTopic topic = topicsController.findTopic(chatId, topicId);
                if (topic != null) {
                    if (ChatObject.canDeleteTopic(currentAccount, currentChat, topic)) {
                        canDeleteCount++;
                    }
                    if (ChatObject.canManageTopic(currentAccount, currentChat, topic)) {
                        if (topic.id == 1) {
                            if (topic.hidden) {
                                canShowCount++;
                            } else {
                                canHideCount++;
                            }
                        }
                        if (!topic.hidden) {
                            if (topic.closed) {
                                closedTopicsCount++;
                            } else {
                                openTopicsCount++;
                            }
                        }
                    }
                }
            }
            closeTopic.setVisibility(closedTopicsCount == 0 && openTopicsCount > 0 ? View.VISIBLE : View.GONE);
            closeTopic.setText(openTopicsCount > 1 ? getString(R.string.CloseTopics) : getString(R.string.CloseTopic));
            restartTopic.setVisibility(openTopicsCount == 0 && closedTopicsCount > 0 ? View.VISIBLE : View.GONE);
            restartTopic.setText(closedTopicsCount > 1 ? getString(R.string.RestartTopics) : getString(R.string.RestartTopic));
            deleteItem.setVisibility(canDeleteCount == selectedTopics.size() ? View.VISIBLE : View.GONE);
            hideItem.setVisibility(canHideCount == 1 && selectedTopics.size() == 1 ? View.VISIBLE : View.GONE);
            showItem.setVisibility(canShowCount == 1 && selectedTopics.size() == 1 ? View.VISIBLE : View.GONE);

            otherItem.checkHideMenuItem();

            updateReordering();
        }
    }

    public void updateReordering() {
        boolean canReorderPins = ChatObject.canManageTopics(getCurrentChat());
        boolean newReordering = canReorderPins && !selectedTopics.isEmpty();
        if (reordering != newReordering) {
            reordering = newReordering;
            adapter.notifyItemRangeChanged(0, adapter.getItemCount());
        }
    }

    public void sendReorder() {
        ArrayList<Integer> newOrder = new ArrayList<>();
        for (int i = 0; i < forumTopics.size(); ++i) {
            TLRPC.TL_forumTopic topic = forumTopics.get(i).topic;
            if (topic != null && topic.pinned) {
                newOrder.add(topic.id);
            }
        }
        getMessagesController().getTopicsController().reorderPinnedTopics(chatId, newOrder);
        ignoreDiffUtil = true;
    }

    private void chekActionMode() {
        if (actionBar.actionModeIsExist(null)) {
            return;
        }
        final ActionBarMenu actionMode = actionBar.createActionMode(false, null);

        if (inPreviewMode) {
            actionMode.setBackgroundColor(Color.TRANSPARENT);
            actionMode.drawBlur = false;
        }
        // Flexible empty space keeps the action buttons on the right without
        // replacing the forum avatar/title with a selected-items number.
        View actionModeSpacer = new View(actionMode.getContext());
        actionMode.addView(actionModeSpacer,
                LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1.0f, 72, 0, 0, 0));
        actionModeSpacer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        pinItem = actionMode.addItemWithWidth(pin_id, R.drawable.msg_pin, AndroidUtilities.dp(54));
        unpinItem = actionMode.addItemWithWidth(unpin_id, R.drawable.msg_unpin, AndroidUtilities.dp(54));
        muteItem = actionMode.addItemWithWidth(mute_id, R.drawable.msg_mute, AndroidUtilities.dp(54));
        deleteItem = actionMode.addItemWithWidth(delete_id, R.drawable.msg_delete, AndroidUtilities.dp(54), getString(R.string.Delete));
        hideItem = actionMode.addItemWithWidth(hide_id, R.drawable.msg_archive_hide, AndroidUtilities.dp(54), getString(R.string.Hide));
        hideItem.setVisibility(View.GONE);
        showItem = actionMode.addItemWithWidth(show_id, R.drawable.msg_archive_show, AndroidUtilities.dp(54), getString(R.string.Show));
        showItem.setVisibility(View.GONE);

        otherItem = actionMode.addItemWithWidth(0, R.drawable.ic_ab_other, AndroidUtilities.dp(54), getString(R.string.AccDescrMoreOptions));
        readItem = otherItem.addSubItem(read_id, R.drawable.msg_markread, getString(R.string.MarkAsRead));
        closeTopic = otherItem.addSubItem(close_topic_id, R.drawable.msg_topic_close, getString(R.string.CloseTopic));
        restartTopic = otherItem.addSubItem(restart_topic_id, R.drawable.msg_topic_restart, getString(R.string.RestartTopic));
    }

    private DialogCell slidingView;
    private DialogCell movingView;
    private boolean allowMoving;
    private boolean movingWas;
    private ArrayList<MessagesController.DialogFilter> movingDialogFilters = new ArrayList<>();
    private boolean waitingForScrollFinished;
    private boolean allowSwipeDuringCurrentTouch;
    private boolean updatePullAfterScroll;
    private int dialogRemoveFinished;
    private int dialogInsertFinished;
    private int dialogChangeFinished;
    private View generalTopicViewMoving;

    private void finishGeneralTopicMoving() {
        if (generalTopicViewMoving == null) {
            return;
        }
        View movingView = generalTopicViewMoving;
        generalTopicViewMoving = null;
        movingView.setTranslationX(0f);
        if (itemTouchHelper != null) {
            itemTouchHelper.clearRecoverAnimations();
        }
        if (movingView instanceof TopicDialogCell) {
            TopicDialogCell cell = (TopicDialogCell) movingView;
            cell.setTopicIcon(cell.currentTopic);
        }
        if (recyclerListView != null) {
            recyclerListView.invalidate();
        }
    }

    public class TouchHelperCallback extends ItemTouchHelper.Callback {

        private RecyclerView.ViewHolder currentItemViewHolder;
        private boolean swipingFolder;
        private boolean swipeFolderBack;

        @Override
        public boolean isLongPressDragEnabled() {
            return !selectedTopics.isEmpty();
        }

        @Override
        public int getMovementFlags(@NonNull RecyclerView recyclerView, RecyclerView.ViewHolder viewHolder) {
            int position = viewHolder.getAdapterPosition();
            if (position < 0 || position >= forumTopics.size() || forumTopics.get(position).topic == null || !ChatObject.canManageTopics(getCurrentChat())) {
                return makeMovementFlags(0, 0);
            }
            TLRPC.TL_forumTopic topic = forumTopics.get(position).topic;
            if (selectedTopics.isEmpty() && viewHolder.itemView instanceof TopicDialogCell && topic.id == 1) {
                TopicDialogCell dialogCell = (TopicDialogCell) viewHolder.itemView;
                swipeFolderBack = false;
                swipingFolder = true;
                dialogCell.setSliding(true);
                return makeMovementFlags(0, ItemTouchHelper.LEFT);
            }
            if (!topic.pinned) {
                return makeMovementFlags(0, 0);
            }
            return makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0);
        }

        @Override
        public boolean onMove(@NonNull RecyclerView recyclerView, RecyclerView.ViewHolder source, RecyclerView.ViewHolder target) {
            if (source.getItemViewType() != target.getItemViewType()) {
                return false;
            }
            int position = target.getAdapterPosition();
            if (position < 0 || position >= forumTopics.size() || forumTopics.get(position).topic == null || !forumTopics.get(position).topic.pinned) {
                return false;
            }
            adapter.swapElements(source.getAdapterPosition(), target.getAdapterPosition());
            return true;
        }

        @Override
        public void onChildDraw(@NonNull Canvas c, @NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, float dX, float dY, int actionState, boolean isCurrentlyActive) {
            super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);
        }

        @Override
        public void onSelectedChanged(RecyclerView.ViewHolder viewHolder, int actionState) {
            if (actionState == ItemTouchHelper.ACTION_STATE_IDLE) {
                sendReorder();
            } else {
                recyclerListView.cancelClickRunnables(false);
                viewHolder.itemView.setPressed(true);
            }
            super.onSelectedChanged(viewHolder, actionState);
        }

        @Override
        public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
            if (viewHolder != null) {
                TopicDialogCell dialogCell = (TopicDialogCell) viewHolder.itemView;
                if (dialogCell.forumTopic != null) {
                    getMessagesController().getTopicsController().toggleShowTopic(chatId, dialogCell.forumTopic.id, dialogCell.forumTopic.hidden);
                }
                generalTopicViewMoving = dialogCell;
                recyclerListView.setArchiveHidden(!dialogCell.forumTopic.hidden, dialogCell);
                updateTopicsList(true, true);
                if (dialogCell.currentTopic != null) {
                    dialogCell.setTopicIcon(dialogCell.currentTopic);
                }
            }
        }

        @Override
        public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
            super.clearView(recyclerView, viewHolder);
            viewHolder.itemView.setPressed(false);
        }
    }

    private void updateChatInfo() {
        updateChatInfo(false);
    }

    private void updateChatInfo(boolean forceAnimate) {
        if (fragmentView == null || avatarContainer == null) {
            return;
        }
        TLRPC.Chat chatLocal = getMessagesController().getChat(chatId);
        TLRPC.User userLocal = getMessagesController().getUser(-chatId);

        if (UserObject.isBotForum(userLocal)) {
            avatarContainer.setUserAvatar(userLocal);
        } else if (ChatObject.isMonoForum(chatLocal)) {
            final TLRPC.Chat mfChatLocal = getMessagesController().getChat(chatLocal.linked_monoforum_id);
            if (mfChatLocal != null) {
                avatarContainer.setChatAvatar(mfChatLocal);
            }
        } else {
            avatarContainer.setChatAvatar(chatLocal);
        }

        long dialog_id = -chatId;
        SharedPreferences preferences = MessagesController.getNotificationsSettings(currentAccount);
        boolean show = preferences.getInt("dialog_bar_vis3" + dialog_id, 0) == 2;
        boolean showReport = preferences.getBoolean("dialog_bar_report" + (-chatId), false);
        boolean showBlock = preferences.getBoolean("dialog_bar_block" + (-chatId), false);

        if (!openedForSelect) {
            if (chatLocal != null) {
                avatarContainer.setTitle(chatLocal.title, chatLocal.scam, chatLocal.fake,
                        chatLocal.verified, false, chatLocal.emoji_status, forceAnimate);
                Drawable rightIcon = null;
                if (getMessagesController().isDialogMuted(-chatId, 0)) {
                    rightIcon = getThemedDrawable(Theme.key_drawable_muteIconDrawable);
                }
                avatarContainer.setTitleIcons(null, rightIcon);
            }
            updateSubtitle();
        } else {
            if (openedForReply) {
                avatarContainer.setTitle(getString(R.string.ReplyToDialog));
            } else if (openedForQuote) {
                avatarContainer.setTitle(getString(R.string.QuoteTo));
            } else if (openedForBotShare) {
                avatarContainer.setTitle(getString(R.string.BotShareToTopic));
            } else if (openedForForward) {
                avatarContainer.setTitle(getString(R.string.ForwardTo));
            } else {
                avatarContainer.setTitle(getString(R.string.SelectTopic));
            }
            searchItem.setVisibility(View.GONE);
            if (avatarContainer != null && avatarContainer.getLayoutParams() != null) {
                ((ViewGroup.MarginLayoutParams) avatarContainer.getLayoutParams()).rightMargin = AndroidUtilities.dp(searchItem.getVisibility() == View.VISIBLE ? 86 : 40);
            }
            avatarContainer.updateSubtitle();
            avatarContainer.getSubtitleTextView().setVisibility(View.GONE);
        }
        boolean animated = fragmentBeginToShow || forceAnimate;
        boolean bottomPannelVisibleLocal;
        long requestedTime = MessagesController.getNotificationsSettings(currentAccount).getLong("dialog_join_requested_time_" + -chatId, -1);
        if (chatLocal != null && ChatObject.isNotInChat(chatLocal) && (requestedTime > 0 && System.currentTimeMillis() - requestedTime < 1000 * 60 * 2)) {
            bottomPannelVisibleLocal = true;

            bottomOverlayChatText.setText(getString(R.string.ChannelJoinRequestSent), animated);
            bottomOverlayChatText.setEnabled(false);
            AndroidUtilities.updateViewVisibilityAnimated(bottomOverlayProgress, false, 0.5f, animated);
            AndroidUtilities.updateViewVisibilityAnimated(bottomOverlayChatText, true, 0.5f, animated);
            setButtonType(BOTTOM_BUTTON_TYPE_JOIN);
        } else if (chatLocal != null && !openedForSelect && (ChatObject.isNotInChat(chatLocal) || getMessagesController().isJoiningChannel(chatLocal.id))) {
            bottomPannelVisibleLocal = true;

            boolean showProgress = false;
            if (getMessagesController().isJoiningChannel(chatLocal.id)) {
                showProgress = true;
            } else {
                if (chatLocal.join_request) {
                    bottomOverlayChatText.setText(getString(R.string.ChannelJoinRequest));
                } else {
                    bottomOverlayChatText.setText(getString(R.string.ChannelJoin));
                }
                bottomOverlayChatText.setClickable(true);
                bottomOverlayChatText.setEnabled(true);
            }

            AndroidUtilities.updateViewVisibilityAnimated(bottomOverlayProgress, showProgress, 0.5f, animated);
            AndroidUtilities.updateViewVisibilityAnimated(bottomOverlayChatText, !showProgress, 0.5f, animated);
            setButtonType(BOTTOM_BUTTON_TYPE_JOIN);
        } else if (show && (showBlock || showReport)) {
            bottomOverlayChatText.setText(getString(R.string.ReportSpamAndLeaveNoCaps));
            bottomOverlayChatText.setClickable(true);
            bottomOverlayChatText.setEnabled(true);

            AndroidUtilities.updateViewVisibilityAnimated(bottomOverlayProgress, false, 0.5f, false);
            AndroidUtilities.updateViewVisibilityAnimated(bottomOverlayChatText, true, 0.5f, false);

            setButtonType(BOTTOM_BUTTON_TYPE_REPORT);
            bottomPannelVisibleLocal = true;
        } else {
            bottomPannelVisibleLocal = false;
        }

        if (bottomPannelVisible != bottomPannelVisibleLocal) {
            bottomPannelVisible = bottomPannelVisibleLocal;
            bottomOverlayContainer.animate().setListener(null).cancel();
            if (!animated) {
                bottomOverlayContainer.setVisibility(bottomPannelVisibleLocal ? View.VISIBLE : View.GONE);
                bottomOverlayContainer.setTranslationY(bottomPannelVisibleLocal ? 0 : AndroidUtilities.dp(53));
            } else {
                bottomOverlayContainer.animate().translationY(bottomPannelVisibleLocal ? 0 : AndroidUtilities.dp(53)).setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (!bottomPannelVisibleLocal) {
                            bottomOverlayContainer.setVisibility(View.GONE);
                        }
                    }
                });
            }
        }
        other.setVisibility(openedForSelect ? View.GONE : View.VISIBLE);
        addMemberSubMenu.setVisibility(ChatObject.canAddUsers(chatLocal) ? View.VISIBLE : View.GONE);
        boostGroupSubmenu.setVisibility(ChatObject.isBoostSupported(chatLocal) && (getUserConfig().isPremium() || ChatObject.isBoosted(chatFull) || ChatObject.hasAdminRights(chatLocal)) ? View.VISIBLE : View.GONE);
        deleteChatSubmenu.setVisibility(chatLocal != null && !chatLocal.creator && !ChatObject.isNotInChat(chatLocal) ? View.VISIBLE : View.GONE);
        reportSubmenu.setVisibility(chatLocal != null && !chatLocal.creator && !ChatObject.hasAdminRights(chatLocal) ? View.VISIBLE : View.GONE);
        updateCreateTopicButton(true);
        groupCall = getMessagesController().getGroupCall(chatId, true);
        if (fragmentContextView != null) {
            fragmentContextView.checkCall(!fragmentBeginToShow);
        }
        if (topPanelLayout != null) {
            topPanelLayout.requestLayout();
        }
        checkUi_listViewPadding();
        if (recyclerListView != null) {
            recyclerListView.requestLayout();
        }

        checkGroupCallJoin(false);
    }

    private void setButtonType(int bottomButtonType) {
        if (this.bottomButtonType != bottomButtonType) {
            this.bottomButtonType = bottomButtonType;
            bottomOverlayChatText.setTextColorKey(bottomButtonType == BOTTOM_BUTTON_TYPE_JOIN ? Theme.key_chat_fieldOverlayText : Theme.key_text_RedBold);
            closeReportSpam.setVisibility(bottomButtonType == BOTTOM_BUTTON_TYPE_REPORT ? View.VISIBLE : View.GONE);
            updateChatInfo();
        }
    }

    private void updateSubtitle() {
        TLRPC.ChatFull chatFull = getMessagesController().getChatFull(chatId);
        if (chatFull != null && this.chatFull != null && this.chatFull.participants != null) {
            chatFull.participants = this.chatFull.participants;
        }
        this.chatFull = chatFull;
        String newSubtitle;
        if (chatFull != null) {
            if (chatFull.participants_count <= 0) {
                TLRPC.Chat chat = getMessagesController().getChat(chatId);
                if (chat == null) {
                    newSubtitle = getString(R.string.Loading);
                } else if (ChatObject.isPublic(chat)) {
                    newSubtitle = getString(R.string.MegaPublic).toLowerCase();
                } else {
                    newSubtitle = getString(R.string.MegaPrivate).toLowerCase();
                }
            } else {
                newSubtitle = LocaleController.formatPluralString("Members", chatFull.participants_count);
            }
        } else {
            newSubtitle = getString(R.string.Loading);
        }

        avatarContainer.setSubtitle(newSubtitle);
    }

    private static final Object settingsPreloadLock = new Object();
    private static final HashSet<String> settingsPreloadInFlight = new HashSet<>();
    private static final HashSet<String> settingsPreloaded = new HashSet<>();
    private boolean emojiPickerPreloadScheduled;
    private final Runnable emojiPickerPreloadRunnable = () ->
            SelectAnimatedEmojiDialog.preload(currentAccount);

    private void scheduleEmojiPickerPreload() {
        if (emojiPickerPreloadScheduled) {
            return;
        }
        emojiPickerPreloadScheduled = true;
        // The picker data isn't required to render the topic list. Starting
        // all emoji/status/category preloads from onFragmentCreate competes
        // with the first layout and the navigation animation, which is most
        // visible in forums containing many custom topic icons.
        AndroidUtilities.runOnUIThread(emojiPickerPreloadRunnable, 250);
    }

    @Override
    public boolean onFragmentCreate() {
        getMessagesController().loadFullChat(chatId, 0, true);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.storiesUpdated);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.chatWasBoostedByUser);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.chatInfoDidLoad);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.topicsDidLoaded);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.updateInterfaces);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.dialogsNeedReload);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.groupCallUpdated);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.notificationsSettingsUpdated);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.chatSwitchedForum);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.closeChats);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.openedChatChanged);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.didUpdateConnectionState);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.dialogsUnreadCounterChanged);

        updateTopicsList(false, false);

        TLRPC.Chat chatLocal = getMessagesController().getChat(chatId);
        if (ChatObject.isChannel(chatLocal)) {
            getMessagesController().startShortPoll(chatLocal, classGuid, false);
        }
        //TODO remove when server start send in get diff
        String settingsPreloadKey = currentAccount + ":" + chatId;
        boolean shouldPreloadSettings;
        synchronized (settingsPreloadLock) {
            shouldPreloadSettings = !settingsPreloaded.contains(settingsPreloadKey)
                    && settingsPreloadInFlight.add(settingsPreloadKey);
        }
        if (shouldPreloadSettings) {
            TL_account.getNotifyExceptions exceptionsReq = new TL_account.getNotifyExceptions();
            exceptionsReq.peer = new TLRPC.TL_inputNotifyPeer();
            exceptionsReq.flags |= 1;
            ((TLRPC.TL_inputNotifyPeer) exceptionsReq.peer).peer = getMessagesController().getInputPeer(-chatId);
            getConnectionsManager().sendRequest(exceptionsReq, (response, error) -> {
                synchronized (settingsPreloadLock) {
                    settingsPreloadInFlight.remove(settingsPreloadKey);
                    if (error == null) {
                        settingsPreloaded.add(settingsPreloadKey);
                    }
                }
            });
        }
        return true;
    }

    @Override
    public void onFragmentDestroy() {
        AndroidUtilities.cancelRunOnUIThread(emojiPickerPreloadRunnable);
        if (blurredPreviewBitmap != null) {
            if (blurredView != null) {
                blurredView.setBackground(null);
            }
            if (!blurredPreviewBitmap.isRecycled()) {
                blurredPreviewBitmap.recycle();
            }
            blurredPreviewBitmap = null;
            blurredPreviewDrawable = null;
        }
        if (avatarContainer != null) {
            avatarContainer.onDestroy();
        }
        notificationsLocker.unlock();
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.storiesUpdated);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.chatWasBoostedByUser);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.chatInfoDidLoad);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.topicsDidLoaded);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.updateInterfaces);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.dialogsNeedReload);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.groupCallUpdated);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.notificationsSettingsUpdated);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.chatSwitchedForum);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.closeChats);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.openedChatChanged);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.didUpdateConnectionState);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.dialogsUnreadCounterChanged);

        TLRPC.Chat chatLocal = getMessagesController().getChat(chatId);
        if (ChatObject.isChannel(chatLocal)) {
            getMessagesController().startShortPoll(chatLocal, classGuid, true);
        }
        super.onFragmentDestroy();

        if (parentDialogsActivity != null && parentDialogsActivity.rightSlidingDialogContainer != null) {
            parentDialogsActivity.getActionBar().setSearchAvatarImageView(null);
            parentDialogsActivity.rightSlidingDialogContainer.enabled = true;
        }
    }

    private void updateTopicsList(boolean animated, boolean enalbeEnterAnimation) {
        topicsMeasureGeneration++;
        if (!animated && updateAnimated) {
            animated = true;
        }
        updateAnimated = false;
        ArrayList<TLRPC.TL_forumTopic> topics = topicsController.getTopics(chatId);

        if (topics != null) {
            int oldCount = forumTopics.size();
            ArrayList<Item> oldItems = new ArrayList<>(forumTopics);
            int previousHiddenCount = hiddenCount;
            hiddenCount = 0;
            forumTopics.clear();
            if (UserObject.isBotForum(currentAccount, -chatId) && openedForForward) {
                forumTopics.add(new Item(VIEW_TYPE_TOPIC_CREATE, null));
            }
            for (int i = 0; i < topics.size(); i++) {
                TLRPC.TL_forumTopic topic = topics.get(i);
                if (excludeTopics != null && excludeTopics.contains(topic.id)) {
                    continue;
                }
                forumTopics.add(new Item(VIEW_TYPE_TOPIC, topic));
                if (topic.hidden) {
                    hiddenCount++;
                }
            }
            if (!forumTopics.isEmpty() && !topicsController.endIsReached(chatId) && canShowProgress) {
                forumTopics.add(new Item(VIEW_TYPE_LOADING_CELL, null));
            }

            for (int i = 0; i < forumTopics.size(); i++) {
                Item item = forumTopics.get(i);
                TLRPC.TL_forumTopic nextTopic = i + 1 < forumTopics.size()
                        ? forumTopics.get(i + 1).topic : null;
                item.captureListContext(i, nextTopic);
            }

            int newCount = forumTopics.size();
            if (fragmentBeginToShow && enalbeEnterAnimation && newCount > oldCount) {
                itemsEnterAnimator.showItemsAnimated(oldCount + 4);
                animated = false;
            }

            if (recyclerListView != null && previousHiddenCount != hiddenCount) {
                recyclerListView.setArchiveHidden(hiddenCount == 0, null);
            }

            if (recyclerListView != null && recyclerListView.getItemAnimator() != (animated ? itemAnimator : null)) {
                recyclerListView.setItemAnimator(animated ? itemAnimator : null);
            }

            if (adapter != null) {
                adapter.setItems(oldItems, forumTopics);
                if (previousHiddenCount != hiddenCount) {
                    for (int i = 0; i < forumTopics.size(); i++) {
                        TLRPC.TL_forumTopic topic = forumTopics.get(i).topic;
                        if (topic != null && topic.id == 1) {
                            adapter.notifyItemChanged(i);
                            break;
                        }
                    }
                }
            }

            if ((scrollToTop || oldCount == 0) && layoutManager != null) {
                layoutManager.scrollToPositionWithOffset(0, 0);
                scrollToTop = false;
            }
        } else if (!forumTopics.isEmpty()) {
            ArrayList<Item> oldItems = new ArrayList<>(forumTopics);
            forumTopics.clear();
            hiddenCount = 0;
            if (recyclerListView != null) {
                recyclerListView.setArchiveHidden(true, null);
            }
            if (adapter != null) {
                adapter.setItems(oldItems, forumTopics);
            }
        }

        checkLoading();
        updateTopicsEmptyViewText();
    }

    private void notifyTopicSelectionChanged(long topicId) {
        if (topicId == 0 || adapter == null) {
            return;
        }
        final boolean monoForum = getMessagesController().isMonoForum(-chatId);
        for (int i = 0; i < forumTopics.size(); i++) {
            TLRPC.TL_forumTopic topic = forumTopics.get(i).topic;
            if (topic == null) {
                continue;
            }
            long itemTopicId = monoForum
                    ? DialogObject.getPeerDialogId(topic.from_id) : topic.id;
            if (itemTopicId == topicId) {
                adapter.notifyItemChanged(i);
                return;
            }
        }
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.dialogsUnreadCounterChanged) {
            updateUnreadBackBadge();
        } else if (id == NotificationCenter.chatInfoDidLoad) {
            TLRPC.ChatFull chatFull = (TLRPC.ChatFull) args[0];
            if (chatFull.participants != null && this.chatFull != null) {
                this.chatFull.participants = chatFull.participants;
            }
            if (chatFull.id == chatId) {
                updateChatInfo();
                if (pendingRequestsDelegate != null) {
                    pendingRequestsDelegate.setChatInfo(chatFull, true);
                }
                checkGroupCallJoin((Boolean) args[3]);
            }
        } else if (id == NotificationCenter.storiesUpdated) {
            updateChatInfo();
        } else if (id == NotificationCenter.chatWasBoostedByUser) {
            if (chatId == -(long) args[2]) {
                boostsStatus = (TL_stories.TL_premium_boostsStatus) args[0];
            }
        } else if (id == NotificationCenter.topicsDidLoaded) {
            Long chatId = (Long) args[0];
            if (this.chatId == chatId) {
                updateTopicsList(false, true);
                if (args.length > 1 && (Boolean) args[1]) {
                    checkForLoadMore();
                }
                checkLoading();
            }
        } else if (id == NotificationCenter.updateInterfaces) {
            int mask = (Integer) args[0];
            if ((mask & (MessagesController.UPDATE_MASK_CHAT
                    | MessagesController.UPDATE_MASK_CHAT_AVATAR
                    | MessagesController.UPDATE_MASK_CHAT_NAME
                    | MessagesController.UPDATE_MASK_CHAT_MEMBERS
                    | MessagesController.UPDATE_MASK_EMOJI_STATUS
                    | MessagesController.UPDATE_MASK_AVATAR
                    | MessagesController.UPDATE_MASK_NAME)) != 0) {
                updateChatInfo();
            }
            if ((mask & MessagesController.UPDATE_MASK_SELECT_DIALOG) > 0) {
                getMessagesController().getTopicsController().sortTopics(chatId, false);
                boolean wasOnTop = !recyclerListView.canScrollVertically(-1);
                updateTopicsList(true, false);
                if (wasOnTop) {
                    layoutManager.scrollToPosition(0);
                }
            }
        } else if (id == NotificationCenter.dialogsNeedReload) {
            updateTopicsList(false, false);
        } else if (id == NotificationCenter.groupCallUpdated) {
            Long chatId = (Long) args[0];
            if (this.chatId == chatId) {
                groupCall = getMessagesController().getGroupCall(chatId, false);
                if (fragmentContextView != null) {
                    fragmentContextView.checkCall(!fragmentBeginToShow);
                }
                if (topPanelLayout != null) {
                    topPanelLayout.requestLayout();
                }
                checkUi_listViewPadding();
                if (recyclerListView != null) {
                    recyclerListView.requestLayout();
                }
                checkGroupCallJoin(false);
            }
        } else if (id == NotificationCenter.notificationsSettingsUpdated) {
            updateTopicsList(false, false);
            updateChatInfo(true);
        } else if (id == NotificationCenter.didUpdateConnectionState) {
            if (topicsController.hasLoadError(chatId)
                    && getConnectionsManager().getConnectionState()
                    == ConnectionsManager.ConnectionStateConnected) {
                topicsController.loadTopics(chatId, false, TopicsController.LOAD_TYPE_PRELOAD);
                checkLoading();
                updateTopicsEmptyViewText();
            }
        } else if (id == NotificationCenter.chatSwitchedForum) {

        } else if (id == NotificationCenter.closeChats) {
            removeSelfFromStack(true);
        }
        if (id == NotificationCenter.openedChatChanged) {
            if (getParentActivity() == null || !(inPreviewMode && AndroidUtilities.isTablet())) {
                return;
            }
            boolean close = (Boolean) args[2];
            long dialog_id = (Long) args[0];
            long topicId = (Long) args[1];
            if (dialog_id == -chatId && !close) {
                if (selectedTopicForTablet != topicId) {
                    selectedTopicForTablet = topicId;
                    updateTopicsList(false, false);
                }
            } else {
                if (selectedTopicForTablet != 0) {
                    selectedTopicForTablet = 0;
                    updateTopicsList(false, false);
                }
            }
        }
    }

    private void checkForLoadMore() {
        if (topicsController.endIsReached(chatId) || layoutManager == null) {
            return;
        }
        int lastPosition = layoutManager.findLastVisibleItemPosition();
        if (forumTopics.isEmpty() || lastPosition >= adapter.getItemCount() - 5) {
            topicsController.loadTopics(chatId);
        }
        checkLoading();
    }

    public void setExcludeTopics(HashSet<Integer> exceptionsTopics) {
        this.excludeTopics = exceptionsTopics;
    }

    @Override
    public ChatObject.Call getGroupCall() {
        return groupCall != null && groupCall.call instanceof TLRPC.TL_groupCall ? groupCall : null;
    }

    @Override
    public TLRPC.Chat getCurrentChat() {
        return getMessagesController().getChat(chatId);
    }

    @Override
    public long getDialogId() {
        return -chatId;
    }

    public void setForwardFromDialogFragment(DialogsActivity dialogsActivity) {
        this.dialogsActivity = dialogsActivity;
    }

    private class Adapter extends AdapterWithDiffUtils {

        @Override
        public int getItemViewType(int position) {
            if (position == getItemCount() - 1) {
                return VIEW_TYPE_EMPTY;
            }
            return forumTopics.get(position).viewType;
        }

        public ArrayList<TopicsFragment.Item> getArray() {
            return (forumTopicsListFrozen ? frozenForumTopicsList : forumTopics);
        }

        @NonNull
        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            if (viewType == VIEW_TYPE_TOPIC || viewType == VIEW_TYPE_TOPIC_CREATE) {
                TopicDialogCell dialogCell = new TopicDialogCell(null, parent.getContext(), true, false);
                if (viewType == VIEW_TYPE_TOPIC_CREATE) {

                    final boolean canManageTopics = UserObject.isBotForumWithEditableTopics(currentAccount, -chatId);

                    dialogCell.setForumIcon(ForumUtilities.createTopicDrawable("", ForumBubbleDrawable.serverSupportedColor[0], false));
                    dialogCell.setTitleOverride(getString(!canManageTopics ? R.string.BotForumAskForStartOffNewChatTitle : R.string.BotForumAskForStartNewChatTitle));
                    dialogCell.setCustomMessage(getString(!canManageTopics ? R.string.BotForumAskForStartOffNewChatForward : R.string.BotForumAskForStartNewChatForward));
                }
                dialogCell.inPreviewMode = inPreviewMode;
                dialogCell.setArchivedPullAnimation(pullForegroundDrawable);
                return new RecyclerListView.Holder(dialogCell);
            } else if (viewType == VIEW_TYPE_EMPTY) {
                return new RecyclerListView.Holder(emptyView = new View(getContext()) {
                    HashMap<String, Boolean> precalcEllipsized = new HashMap<>();
                    int cachedGeneration = -1;
                    int cachedWidth = -1;
                    int cachedHiddenCount;
                    int cachedChildrenHeight;
                    int cachedGeneralHeight;
                    float cachedNameTextSize;
                    float cachedTimeTextSize;
                    boolean cachedRtl;
                    boolean cachedPreview;

                    @Override
                    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                        int width = MeasureSpec.getSize(widthMeasureSpec);
                        boolean preview = isInPreviewMode();
                        float nameTextSize = Theme.dialogs_namePaint[0].getTextSize();
                        float timeTextSize = Theme.dialogs_timePaint.getTextSize();
                        if (cachedGeneration != topicsMeasureGeneration || cachedWidth != width
                                || cachedRtl != LocaleController.isRTL || cachedPreview != preview
                                || cachedNameTextSize != nameTextSize || cachedTimeTextSize != timeTextSize) {
                            cachedGeneration = topicsMeasureGeneration;
                            cachedWidth = width;
                            cachedRtl = LocaleController.isRTL;
                            cachedPreview = preview;
                            cachedNameTextSize = nameTextSize;
                            cachedTimeTextSize = timeTextSize;
                            cachedHiddenCount = 0;
                            cachedChildrenHeight = 0;
                            cachedGeneralHeight = AndroidUtilities.dp(64);
                            precalcEllipsized.clear();
                            for (int i = 0; i < getArray().size(); ++i) {
                                if (getArray().get(i) == null || getArray().get(i).topic == null) {
                                    continue;
                                }
                                String title = getArray().get(i).topic.title;
                                Boolean oneline = precalcEllipsized.get(title);
                                if (oneline == null) {
                                    int nameLeft = AndroidUtilities.dp(!LocaleController.isRTL ? (preview ? 11 : 50) + 4 : 18);
                                    int nameWidth = !LocaleController.isRTL ?
                                            width - nameLeft - AndroidUtilities.dp(14 + 8) :
                                            width - nameLeft - AndroidUtilities.dp((preview ? 11 : 50) + 5 + 8);
                                    nameWidth -= (int) Math.ceil(Theme.dialogs_timePaint.measureText("00:00"));
                                    oneline = Theme.dialogs_namePaint[0].measureText(title) <= nameWidth;
                                    precalcEllipsized.put(title, oneline);
                                }
                                int childHeight = AndroidUtilities.dp(64 + (!oneline ? 20 : 0));
                                if (getArray().get(i).topic.id == 1) {
                                    cachedGeneralHeight = childHeight;
                                }
                                if (getArray().get(i).topic.hidden) {
                                    cachedHiddenCount++;
                                }
                                cachedChildrenHeight += childHeight;
                            }
                        }
                        int height = Math.max(0, cachedHiddenCount > 0 ? recyclerListView.getMeasuredHeight() - recyclerListView.getPaddingTop() - recyclerListView.getPaddingBottom() - cachedChildrenHeight + cachedGeneralHeight : 0);
                        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
                    }
                });
            } else {
                FlickerLoadingView flickerLoadingView = new FlickerLoadingView(parent.getContext());
                flickerLoadingView.setViewType(FlickerLoadingView.TOPIC_CELL_TYPE);
                flickerLoadingView.setIsSingleCell(true);
                flickerLoadingView.showDate(true);
                return new RecyclerListView.Holder(flickerLoadingView);
            }
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
            if (holder.getItemViewType() == VIEW_TYPE_TOPIC) {
                TopicsFragment.Item item = getArray().get(position);
                TLRPC.TL_forumTopic topic = item.topic;
                TLRPC.TL_forumTopic nextTopic = null;
                if (position + 1 < getArray().size()) {
                    nextTopic = getArray().get(position + 1).topic;
                }
                TopicDialogCell dialogCell = (TopicDialogCell) holder.itemView;

                TLRPC.Message tlMessage = topic.topMessage != null
                        ? topic.topMessage : topic.topicStartMessage;
                int oldId = dialogCell.forumTopic == null ? 0 : dialogCell.forumTopic.id;
                int newId = topic.id;
                boolean animated = oldId == newId && dialogCell.position == position && animatedUpdateEnabled;
                MessageObject messageObject = item.getMessageObject(currentAccount, tlMessage);
                dialogCell.forumTopic = topic;
                dialogCell.position = position;
                if (getMessagesController().isMonoForum(-chatId)) {
                    dialogCell.isMonoForumTopicDialog = true;
                    dialogCell.drawAvatar = true;

                    dialogCell.messagePaddingStart = 72;
                    dialogCell.chekBoxPaddingTop = 42;
                    dialogCell.heightDefault = 72;
                    dialogCell.heightThreeLines = 78;

                    dialogCell.setDialog(
                            DialogObject.getPeerDialogId(topic.from_id),
                            messageObject,
                            tlMessage == null ? 0 : tlMessage.date,
                            false,
                            animated);
                    dialogCell.isSavedDialogCell = true;
                    dialogCell.useSeparator = position + 1 < getItemCount();
                } else {
                    dialogCell.setForumTopic(topic, -chatId, messageObject, isInPreviewMode(), animated);
                    dialogCell.drawDivider = position != forumTopics.size() - 1 || recyclerListView.emptyViewIsVisible();
                    dialogCell.fullSeparator = topic.pinned && (nextTopic == null || !nextTopic.pinned);
                    dialogCell.setPinForced(topic.pinned && !topic.hidden);
                }

                if (!getMessagesController().isMonoForum(-chatId)) {
                    dialogCell.setTopicIcon(topic);
                }

                dialogCell.setChecked(selectedTopics.contains(newId), animated);
                dialogCell.setDialogSelected(selectedTopicForTablet == newId);
                dialogCell.onReorderStateChanged(reordering, true);
            } else if (holder.getItemViewType() == VIEW_TYPE_TOPIC_CREATE) {
                TopicDialogCell dialogCell = (TopicDialogCell) holder.itemView;
                dialogCell.setCurrentDialogId(-chatId);
                dialogCell.drawDivider = position != forumTopics.size() - 1 || recyclerListView.emptyViewIsVisible();
                dialogCell.position = position;
            }
        }

        @Override
        public int getItemCount() {
            return getArray().size() + 1;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return holder.getItemViewType() == VIEW_TYPE_TOPIC || holder.getItemViewType() == VIEW_TYPE_TOPIC_CREATE;
        }

        public void swapElements(int from, int to) {
            if (forumTopicsListFrozen) {
                return;
            }

            forumTopics.add(to, forumTopics.remove(from));
            if (recyclerListView.getItemAnimator() != itemAnimator) {
                recyclerListView.setItemAnimator(itemAnimator);
            }
            notifyItemMoved(from, to);
        }

        @Override
        public void notifyDataSetChanged() {
            lastItemsCount = getItemCount();
            super.notifyDataSetChanged();
        }
    }

    public class TopicDialogCell extends DialogCell {

        public boolean drawDivider;
        public int position = -1;

        public TopicDialogCell(DialogsActivity fragment, Context context, boolean needCheck, boolean forceThreeLines) {
            super(fragment, context, needCheck, forceThreeLines);
            drawAvatar = false;
            messagePaddingStart = isInPreviewMode() ? 11 : 50;
            chekBoxPaddingTop = 24;
            heightDefault = 64;
            heightThreeLines = 76;
            forbidVerified = true;
        }

        private TLRPC.TL_forumTopic currentTopic;
        private AnimatedEmojiDrawable animatedEmojiDrawable;
        private Drawable forumIcon;
        boolean attached;
        private boolean isGeneral;
        private boolean closed;

        @Override
        public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            if (canMoveTopicByAccessibility(-1)) {
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                        R.id.acc_action_topic_move_up, getString(R.string.NM_BT_MoveUp)));
            }
            if (canMoveTopicByAccessibility(1)) {
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(
                        R.id.acc_action_topic_move_down, getString(R.string.NM_BT_MoveDown)));
            }
        }

        @Override
        public boolean performAccessibilityAction(int action, Bundle arguments) {
            if (action == R.id.acc_action_topic_move_up) {
                return moveTopicByAccessibility(-1);
            }
            if (action == R.id.acc_action_topic_move_down) {
                return moveTopicByAccessibility(1);
            }
            return super.performAccessibilityAction(action, arguments);
        }

        private boolean canMoveTopicByAccessibility(int direction) {
            if (forumTopic == null || !forumTopic.pinned
                    || !ChatObject.canManageTopics(getCurrentChat())) {
                return false;
            }
            int currentPosition = findCurrentTopicPosition();
            if (currentPosition == RecyclerView.NO_POSITION) {
                return false;
            }
            int target = currentPosition + direction;
            return target >= 0 && target < forumTopics.size()
                    && forumTopics.get(target).topic != null
                    && forumTopics.get(target).topic.pinned;
        }

        private boolean moveTopicByAccessibility(int direction) {
            if (!canMoveTopicByAccessibility(direction)) {
                return false;
            }
            int currentPosition = findCurrentTopicPosition();
            adapter.swapElements(currentPosition, currentPosition + direction);
            sendReorder();
            sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED);
            return true;
        }

        private int findCurrentTopicPosition() {
            if (forumTopic == null) {
                return RecyclerView.NO_POSITION;
            }
            for (int i = 0; i < forumTopics.size(); i++) {
                TLRPC.TL_forumTopic candidate = forumTopics.get(i).topic;
                if (candidate != null && candidate.id == forumTopic.id) {
                    return i;
                }
            }
            return RecyclerView.NO_POSITION;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (getMessagesController().isMonoForum(-chatId)) {
                super.onDraw(canvas);
                return;
            }

            xOffset = inPreviewMode && checkBox != null ? checkBox.getProgress() * AndroidUtilities.dp(30) : 0;
            canvas.save();
            canvas.translate(xOffset, translateY = -AndroidUtilities.dp(4));
            canvas.drawColor(getThemedColor(Theme.key_windowBackgroundWhite));
            super.onDraw(canvas);
            canvas.restore();
            canvas.save();
            canvas.translate(super.translationX, 0);
            if (drawDivider) {
                int left = fullSeparator ? 0 : AndroidUtilities.dp(messagePaddingStart);
                if (LocaleController.isRTL) {
                    canvas.drawLine(0 - super.translationX, getMeasuredHeight() - 1, getMeasuredWidth() - left, getMeasuredHeight() - 1, Theme.dividerPaint);
                } else {
                    canvas.drawLine(left - super.translationX, getMeasuredHeight() - 1, getMeasuredWidth(), getMeasuredHeight() - 1, Theme.dividerPaint);
                }
            }
            if ((!isGeneral || archivedChatsDrawable == null || archivedChatsDrawable.outProgress != 0.0f) && (animatedEmojiDrawable != null || forumIcon != null)) {
                int padding = AndroidUtilities.dp(10);
                int paddingTop = AndroidUtilities.dp(10);
                int size = AndroidUtilities.dp(28);
                if (animatedEmojiDrawable != null) {
                    if (LocaleController.isRTL) {
                        animatedEmojiDrawable.setBounds(getWidth() - padding - size, paddingTop, getWidth() - padding, paddingTop + size);
                    } else {
                        animatedEmojiDrawable.setBounds(padding, paddingTop, padding + size, paddingTop + size);
                    }
                    animatedEmojiDrawable.draw(canvas);
                } else {
                    if (LocaleController.isRTL) {
                        forumIcon.setBounds(getWidth() - padding - size, paddingTop, getWidth() - padding, paddingTop + size);
                    } else {
                        forumIcon.setBounds(padding, paddingTop, padding + size, paddingTop + size);
                    }
                    forumIcon.draw(canvas);
                }
            }
            canvas.restore();
        }

        @Override
        public void buildLayout() {
            super.buildLayout();
            setHiddenT();
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            attached = true;
            if (animatedEmojiDrawable != null) {
                animatedEmojiDrawable.addView(this);
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            attached = false;
            if (animatedEmojiDrawable != null) {
                animatedEmojiDrawable.removeView(this);
            }
        }

        public void setAnimatedEmojiDrawable(AnimatedEmojiDrawable animatedEmojiDrawable) {
            if (this.animatedEmojiDrawable == animatedEmojiDrawable) {
                return;
            }
            if (this.animatedEmojiDrawable != null && attached) {
                this.animatedEmojiDrawable.removeView(this);
            }
            if (animatedEmojiDrawable != null) {
                animatedEmojiDrawable.setColorFilter(Theme.chat_animatedEmojiTextColorFilter);
            }
            this.animatedEmojiDrawable = animatedEmojiDrawable;
            if (animatedEmojiDrawable != null && attached) {
                animatedEmojiDrawable.addView(this);
            }
        }

        public void setForumIcon(Drawable drawable) {
            forumIcon = drawable;
        }

        public void setTopicIcon(TLRPC.TL_forumTopic topic) {
            currentTopic = topic;
            closed = topic != null && topic.closed;
            if (inPreviewMode) {
                updateHidden(topic != null && topic.hidden, true);
            }
            isGeneral = topic != null && topic.id == 1;
            if (topic != null && this != generalTopicViewMoving) {
                if (topic.hidden) {
                    overrideSwipeAction = true;
                    overrideSwipeActionBackgroundColorKey = Theme.key_chats_archivePinBackground;
                    overrideSwipeActionRevealBackgroundColorKey = Theme.key_chats_archiveBackground;
                    overrideSwipeActionStringKey = "Unhide";
                    overrideSwipeActionStringId = R.string.Unhide;
                    overrideSwipeActionDrawable = Theme.dialogs_unpinArchiveDrawable;
                } else {
                    overrideSwipeAction = true;
                    overrideSwipeActionBackgroundColorKey = Theme.key_chats_archiveBackground;
                    overrideSwipeActionRevealBackgroundColorKey = Theme.key_chats_archivePinBackground;
                    overrideSwipeActionStringKey = "Hide";
                    overrideSwipeActionStringId = R.string.Hide;
                    overrideSwipeActionDrawable = Theme.dialogs_pinArchiveDrawable;
                }
                invalidate();
            }

            if (inPreviewMode) {
                return;
            }
            if (topic != null && topic.id == 1) {
                setAnimatedEmojiDrawable(null);
                setForumIcon(ForumUtilities.createGeneralTopicDrawable(getContext(), 1f, getThemedColor(Theme.key_chat_inMenu), false));
            } else if (topic != null && topic.icon_emoji_id != 0) {
                setForumIcon(null);
                if (animatedEmojiDrawable == null || animatedEmojiDrawable.getDocumentId() != topic.icon_emoji_id) {
                    setAnimatedEmojiDrawable(AnimatedEmojiDrawable.make(
                            currentAccount,
                            openedForForward
                                    ? AnimatedEmojiDrawable.CACHE_TYPE_ALERT_PREVIEW_STATIC
                                    : AnimatedEmojiDrawable.CACHE_TYPE_FORUM_TOPIC,
                            topic.icon_emoji_id));
                }
            } else {
                setAnimatedEmojiDrawable(null);
                setForumIcon(ForumUtilities.createTopicDrawable(topic, false));
            }
            updateHidden(topic != null && topic.hidden, true);

            buildLayout();
        }

        private Boolean hidden;
        private float hiddenT;
        private ValueAnimator hiddenAnimator;

        private void updateHidden(boolean hidden, boolean animated) {
            if (this.hidden == null) {
                animated = false;
            }

            if (hiddenAnimator != null) {
                hiddenAnimator.cancel();
                hiddenAnimator = null;
            }

            this.hidden = hidden;
            if (animated) {
                hiddenAnimator = ValueAnimator.ofFloat(hiddenT, hidden ? 1f : 0f);
                hiddenAnimator.addUpdateListener(anm -> {
                    hiddenT = (float) anm.getAnimatedValue();
                    setHiddenT();
                });
                hiddenAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT);
                hiddenAnimator.start();
            } else {
                hiddenT = hidden ? 1f : 0f;
                setHiddenT();
            }
        }

        private void setHiddenT() {
            if (forumIcon instanceof ForumUtilities.GeneralTopicDrawable) {
                ((ForumUtilities.GeneralTopicDrawable) forumIcon).setColor(
                        ColorUtils.blendARGB(getThemedColor(Theme.key_chats_archivePullDownBackground), getThemedColor(Theme.key_avatar_background2Saved), hiddenT)
                );
            }
            if (topicIconInName != null && topicIconInName[0] instanceof ForumUtilities.GeneralTopicDrawable) {
                ((ForumUtilities.GeneralTopicDrawable) topicIconInName[0]).setColor(
                        ColorUtils.blendARGB(getThemedColor(Theme.key_chats_archivePullDownBackground), getThemedColor(Theme.key_avatar_background2Saved), hiddenT)
                );
            }
            invalidate();
        }

        @Override
        protected boolean drawLock2() {
            return closed;
        }
    }

    private void hideFloatingButton(boolean hide, boolean animated) {
        floatingButton.setButtonVisible(!hide, fragmentBeginToShow && animated);
    }

    private void updateFloatingButtonOffset() {
        floatingButton.setTranslationY(-transitionPadding - navigationBarHeight - additionFloatingButtonOffset);
    }

    @Override
    public void onBecomeFullyHidden() {
        if (actionBar != null) {
            actionBar.closeSearchField();
        }
    }

    private class EmptyViewContainer extends FrameLayout {

        TextView textView;

        public EmptyViewContainer(Context context) {
            super(context);
            textView = new TextView(context);
            SpannableStringBuilder spannableStringBuilder;
            if (LocaleController.isRTL) {
                spannableStringBuilder = new SpannableStringBuilder("  ");
                spannableStringBuilder.setSpan(new ColoredImageSpan(R.drawable.attach_arrow_left), 0, 1, 0);
                spannableStringBuilder.append(getString(R.string.TapToCreateTopicHint));
            } else {
                spannableStringBuilder = new SpannableStringBuilder(getString(R.string.TapToCreateTopicHint));
                spannableStringBuilder.append("  ");
                spannableStringBuilder.setSpan(new ColoredImageSpan(R.drawable.arrow_newchat), spannableStringBuilder.length() - 1, spannableStringBuilder.length(), 0);
            }
            textView.setText(spannableStringBuilder);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            textView.setLayerType(LAYER_TYPE_HARDWARE, null);
            textView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
            addView(textView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, LocaleController.isRTL ? 72 : 32, 0, LocaleController.isRTL ? 32 : 72, 32));
        }

        float progress;
        boolean increment;
        long lastFrameTime;

        @Override
        protected void dispatchDraw(Canvas canvas) {
            super.dispatchDraw(canvas);
            long now = SystemClock.uptimeMillis();
            float dt = lastFrameTime == 0 ? 16f : Math.max(0f, Math.min(64f, now - lastFrameTime));
            lastFrameTime = now;
            if (increment) {
                progress += dt / 1200f;
                if (progress > 1) {
                    increment = false;
                    progress = 1;
                }
            } else {
                progress -= dt / 1200f;
                if (progress < 0) {
                    increment = true;
                    progress = 0;
                }
            }
            textView.setTranslationX(CubicBezierInterpolator.DEFAULT.getInterpolation(progress) * AndroidUtilities.dp(8) * (LocaleController.isRTL ? -1 : 1));
            invalidate();
        }
    }

    @Override
    public boolean isLightStatusBar() {
        int color = searching ? getThemedColor(Theme.key_windowBackgroundWhite) : getThemedColor(Theme.key_actionBarDefault);
        if (actionBar.isActionModeShowed()) {
            color = getThemedColor(Theme.key_actionBarActionModeDefault);
        }
        return ColorUtils.calculateLuminance(color) > 0.7f;
    }

    private class MessagesSearchContainer extends ViewPagerFixed implements FilteredSearchView.UiCallback {

        FrameLayout searchContainer;

        RecyclerListView recyclerView;
        LinearLayoutManager layoutManager;
        SearchAdapter searchAdapter;
        Runnable searchRunnable;
        String searchString = "empty";

        ArrayList<TLRPC.TL_forumTopic> searchResultTopics = new ArrayList<>();
        ArrayList<MessageObject> searchResultMessages = new ArrayList<>();

        int topicsHeaderRow;
        int topicsStartRow;
        int topicsEndRow;

        int messagesHeaderRow;
        int messagesStartRow;
        int messagesEndRow;

        int rowCount;

        boolean isLoading;
        boolean canLoadMore;

        FlickerLoadingView flickerLoadingView;
        StickerEmptyView emptyView;
        RecyclerItemsEnterAnimator itemsEnterAnimator;
        boolean messagesIsLoading;
        private int keyboardSize;
        private ViewPagerAdapter viewPagerAdapter;
        SearchViewPager.ChatPreviewDelegate chatPreviewDelegate;

        public MessagesSearchContainer(@NonNull Context context) {
            super(context);

            searchContainer = new FrameLayout(context);
            chatPreviewDelegate = new SearchViewPager.ChatPreviewDelegate() {
                @Override
                public void startChatPreview(RecyclerListView listView, DialogCell cell) {
                    showChatPreview(cell);
                }

                @Override
                public void move(float dy) {
                    if (AndroidUtilities.displaySize.x > AndroidUtilities.displaySize.y) {
                        movePreviewFragment(dy);
                    }
                }

                @Override
                public void finish() {
                    if (AndroidUtilities.displaySize.x > AndroidUtilities.displaySize.y) {
                        finishPreviewFragment();
                    }
                }
            };

            recyclerView = new RecyclerListView(context);
            recyclerView.setAdapter(searchAdapter = new SearchAdapter());
            recyclerView.setLayoutManager(layoutManager = new LinearLayoutManager(context));
            recyclerView.setOnItemClickListener((view, position) -> {
                if (view instanceof TopicSearchCell) {
                    TopicSearchCell cell = (TopicSearchCell) view;
                    ForumUtilities.openTopic(TopicsFragment.this, chatId, cell.getTopic(), 0);
                } else if (view instanceof TopicDialogCell) {
                    TopicDialogCell cell = (TopicDialogCell) view;
                    ForumUtilities.openTopic(TopicsFragment.this, chatId, cell.forumTopic, cell.getMessageId());
                }
            });
            recyclerView.setOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                    super.onScrolled(recyclerView, dx, dy);
                    if (canLoadMore) {
                        int lastPosition = layoutManager.findLastVisibleItemPosition();
                        if (lastPosition + 5 >= rowCount) {
                            loadMessages(searchString);
                        }
                    }

                    if (searching && (dx != 0 || dy != 0)) {
                        AndroidUtilities.hideKeyboard(searchItem.getSearchField());
                    }
                }
            });

            flickerLoadingView = new FlickerLoadingView(context);
            flickerLoadingView.setViewType(FlickerLoadingView.DIALOG_CELL_TYPE);
            flickerLoadingView.showDate(false);
            flickerLoadingView.setUseHeaderOffset(true);

            emptyView = new StickerEmptyView(context, flickerLoadingView, StickerEmptyView.STICKER_TYPE_SEARCH);
            emptyView.title.setText(getString(R.string.NoResult));
            emptyView.subtitle.setVisibility(View.GONE);
            emptyView.setVisibility(View.GONE);
            emptyView.addView(flickerLoadingView, 0);
            emptyView.setAnimateLayoutChange(true);

            recyclerView.setEmptyView(emptyView);
            recyclerView.setAnimateEmptyView(true, RecyclerListView.EMPTY_VIEW_ANIMATION_TYPE_ALPHA);
            searchContainer.addView(emptyView);
            searchContainer.addView(recyclerView);
            updateRows();

            itemsEnterAnimator = new RecyclerItemsEnterAnimator(recyclerView, true);
            recyclerView.setItemsEnterAnimator(itemsEnterAnimator);

            setAdapter(viewPagerAdapter = new ViewPagerAdapter());
        }

        class Item {
            private final int type;
            int filterIndex;

            private Item(int type) {
                this.type = type;
            }
        }

        private class ViewPagerAdapter extends ViewPagerFixed.Adapter {

            ArrayList<Item> items = new ArrayList<>();

            public ViewPagerAdapter() {
                items.add(new Item(DIALOGS_TYPE));
                Item item = new Item(FILTER_TYPE);
                item.filterIndex = 0;
                items.add(item);
//                items.add(new Item(DOWNLOADS_TYPE));
                item = new Item(FILTER_TYPE);
                item.filterIndex = 1;
                items.add(item);
                item = new Item(FILTER_TYPE);
                item.filterIndex = 2;
                items.add(item);
                item = new Item(FILTER_TYPE);
                item.filterIndex = 3;
                items.add(item);
                item = new Item(FILTER_TYPE);
                item.filterIndex = 4;
                items.add(item);
            }

            private final static int DIALOGS_TYPE = 0;
            private final static int DOWNLOADS_TYPE = 1;
            private final static int FILTER_TYPE = 2;

            @Override
            public int getItemCount() {
                return items.size();
            }

            @Override
            public View createView(int viewType) {
                if (viewType == 1) {
                    return searchContainer;
                } else if (viewType == 2) {
                    SearchDownloadsContainer downloadsContainer = new SearchDownloadsContainer(TopicsFragment.this, currentAccount);
                    downloadsContainer.recyclerListView.addOnScrollListener(new RecyclerView.OnScrollListener() {
                        @Override
                        public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                            super.onScrolled(recyclerView, dx, dy);
//                                fragmentView.invalidateBlur();
                        }
                    });
                    downloadsContainer.setUiCallback(MessagesSearchContainer.this);
                    return downloadsContainer;
                } else {
                    FilteredSearchView filteredSearchView = new FilteredSearchView(TopicsFragment.this);
                    filteredSearchView.setChatPreviewDelegate(chatPreviewDelegate);
                    filteredSearchView.setUiCallback(MessagesSearchContainer.this);
                    filteredSearchView.recyclerListView.addOnScrollListener(new RecyclerView.OnScrollListener() {
                        @Override
                        public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                            super.onScrolled(recyclerView, dx, dy);
//                                fragmentView.invalidateBlur();
                        }
                    });
                    return filteredSearchView;
                }
            }

            @Override
            public String getItemTitle(int position) {
                if (items.get(position).type == DIALOGS_TYPE) {
                    return getString(R.string.SearchMessages);
                } else if (items.get(position).type == DOWNLOADS_TYPE) {
                    return getString(R.string.DownloadsTabs);
                } else {
                    return FiltersView.filters[items.get(position).filterIndex].getTitle();
                }
            }

            @Override
            public int getItemViewType(int position) {
                if (items.get(position).type == DIALOGS_TYPE) {
                    return 1;
                }
                if (items.get(position).type == DOWNLOADS_TYPE) {
                    return 2;
                }
                return items.get(position).type + position;
            }

            @Override
            public void bindView(View view, int position, int viewType) {
                search(view, position, searchString, true);
            }
        }

        @Override
        public void goToMessage(MessageObject messageObject) {
            Bundle args = new Bundle();
            long dialogId = messageObject.getDialogId();
            if (DialogObject.isEncryptedDialog(dialogId)) {
                args.putInt("enc_id", DialogObject.getEncryptedChatId(dialogId));
            } else if (DialogObject.isUserDialog(dialogId)) {
                args.putLong("user_id", dialogId);
            } else {
                TLRPC.Chat chat = AccountInstance.getInstance(currentAccount).getMessagesController().getChat(-dialogId);
                if (chat != null && chat.migrated_to != null) {
                    args.putLong("migrated_to", dialogId);
                    dialogId = -chat.migrated_to.channel_id;
                }
                args.putLong("chat_id", -dialogId);
            }
            args.putInt("message_id", messageObject.getId());
            TopicsFragment.this.presentFragment(new ChatActivity(args));
//                showActionMode(false);
        }

        private ArrayList<MessageObject> selectedItems = new ArrayList<>();

        @Override
        public boolean actionModeShowing() {
            return actionBar.isActionModeShowed();
        }

        @Override
        public void toggleItemSelection(MessageObject item, View view, int a) {
            if (!selectedItems.remove(item)) {
                selectedItems.add(item);
            }
            if (selectedItems.isEmpty()) {
                actionBar.hideActionMode();
            }
        }

        @Override
        public boolean isSelected(FilteredSearchView.MessageHashId messageHashId) {
            if (messageHashId == null) {
                return false;
            }
            for (int i = 0; i < selectedItems.size(); ++i) {
                MessageObject msg = selectedItems.get(i);
                if (msg != null && msg.getId() == messageHashId.messageId && msg.getDialogId() == messageHashId.dialogId) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public void showActionMode() {
            actionBar.showActionMode();
        }

        @Override
        public int getFolderId() {
            return 0;
        }

        private void search(View view, int position, String query, boolean reset) {
            long minDate = 0;
            long maxDate = 0;
            boolean includeFolder = false;

            this.searchString = query;
            if (view == searchContainer) {
                searchMessages(query);
            } else if (view instanceof FilteredSearchView) {
                ((FilteredSearchView) view).setKeyboardHeight(keyboardSize, false);
                Item item = viewPagerAdapter.items.get(position);
                ((FilteredSearchView) view).search(-chatId, 0, minDate, maxDate, FiltersView.filters[item.filterIndex], includeFolder, query, reset);
            } else if (view instanceof SearchDownloadsContainer) {
                ((SearchDownloadsContainer) view).setKeyboardHeight(keyboardSize, false);
                ((SearchDownloadsContainer) view).search(query);
            }
        }

        private void searchMessages(String searchString) {
            if (searchRunnable != null) {
                AndroidUtilities.cancelRunOnUIThread(searchRunnable);
                searchRunnable = null;
            }

//            AndroidUtilities.updateViewVisibilityAnimated(TopicsFragment.this.searchContainer, !TextUtils.isEmpty(searchString), 1f, true);

            messagesIsLoading = false;
            canLoadMore = false;
            searchResultTopics.clear();
            searchResultMessages.clear();
            if (TextUtils.isEmpty(searchString)) {
                isLoading = false;
                searchResultTopics.clear();
                for (int i = 0; i < forumTopics.size(); i++) {
                    if (forumTopics.get(i).topic != null) {
                        searchResultTopics.add(forumTopics.get(i).topic);
                        forumTopics.get(i).topic.searchQuery = null;
                    }
                }
                updateRows();
                // emptyView.showProgress(true, true);
                return;
            } else {
                updateRows();
            }

            isLoading = true;
            emptyView.showProgress(isLoading, true);

            searchRunnable = () -> {
                String searchTrimmed = searchString.trim().toLowerCase();
                ArrayList<TLRPC.TL_forumTopic> topics = new ArrayList<>();
                for (int i = 0; i < forumTopics.size(); i++) {
                    if (forumTopics.get(i).topic != null && forumTopics.get(i).topic.title.toLowerCase().contains(searchTrimmed)) {
                        topics.add(forumTopics.get(i).topic);
                        forumTopics.get(i).topic.searchQuery = searchTrimmed;
                    }
                }

                searchResultTopics.clear();
                searchResultTopics.addAll(topics);
                updateRows();

                if (!searchResultTopics.isEmpty()) {
                    isLoading = false;
                    //   emptyView.showProgress(isLoading, true);
                    itemsEnterAnimator.showItemsAnimated(0);
                }

                loadMessages(searchString);
            };
            AndroidUtilities.runOnUIThread(searchRunnable, 200);
        }

        public void setSearchString(String searchString) {
            if (this.searchString.equals(searchString)) {
                return;
            }
            search(viewPages[0], getCurrentPosition(), searchString, false);
        }

        private void loadMessages(String searchString) {
            if (messagesIsLoading) {
                return;
            }
            TLRPC.TL_messages_search req = new TLRPC.TL_messages_search();
            req.peer = getMessagesController().getInputPeer(-chatId);
            req.filter = new TLRPC.TL_inputMessagesFilterEmpty();
            req.limit = 20;
            req.q = searchString;
            if (!searchResultMessages.isEmpty()) {
                req.offset_id = searchResultMessages.get(searchResultMessages.size() - 1).getId();
            }
            messagesIsLoading = true;
//            if (query.equals(lastMessagesSearchString) && !searchResultMessages.isEmpty()) {
//                MessageObject lastMessage = searchResultMessages.get(searchResultMessages.size() - 1);
//                req.offset_id = lastMessage.getId();
//                req.offset_rate = nextSearchRate;
//                long id = MessageObject.getPeerId(lastMessage.messageOwner.peer_id);
//                req.offset_peer = MessagesController.getInstance(currentAccount).getInputPeer(id);
//            } else {
//                req.offset_rate = 0;
//                req.offset_id = 0;
//                req.offset_peer = new TLRPC.TL_inputPeerEmpty();
//            }
            ConnectionsManager.getInstance(currentAccount).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                if (searchString.equals(this.searchString)) {
                    int oldRowCount = rowCount;
                    messagesIsLoading = false;
                    isLoading = false;
                    if (response instanceof TLRPC.messages_Messages) {
                        TLRPC.messages_Messages messages = (TLRPC.messages_Messages) response;

                        for (int i = 0; i < messages.messages.size(); i++) {
                            TLRPC.Message message = messages.messages.get(i);
                            MessageObject messageObject = new MessageObject(currentAccount, message, false, false);
                            messageObject.setQuery(searchString);
                            searchResultMessages.add(messageObject);
                        }
                        updateRows();
                        canLoadMore = searchResultMessages.size() < messages.count && !messages.messages.isEmpty();
                    } else {
                        canLoadMore = false;
                    }

                    if (rowCount == 0) {
                        emptyView.showProgress(isLoading, true);
                    }
                    itemsEnterAnimator.showItemsAnimated(oldRowCount);
                }
            }));
        }

        private void updateRows() {
            topicsHeaderRow = -1;
            topicsStartRow = -1;
            topicsEndRow = -1;
            messagesHeaderRow = -1;
            messagesStartRow = -1;
            messagesEndRow = -1;

            rowCount = 0;

            if (!searchResultTopics.isEmpty()) {
                topicsHeaderRow = rowCount++;
                topicsStartRow = rowCount;
                rowCount += searchResultTopics.size();
                topicsEndRow = rowCount;
            }

            if (!searchResultMessages.isEmpty()) {
                messagesHeaderRow = rowCount++;
                messagesStartRow = rowCount;
                rowCount += searchResultMessages.size();
                messagesEndRow = rowCount;
            }

            searchAdapter.notifyDataSetChanged();
        }

        private class SearchAdapter extends RecyclerListView.SelectionAdapter {

            private final static int VIEW_TYPE_HEADER = 1;
            private final static int VIEW_TYPE_TOPIC = 2;
            private final static int VIEW_TYPE_MESSAGE = 3;

            @NonNull
            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
                View view;
                switch (viewType) {
                    case VIEW_TYPE_HEADER:
                        view = new GraySectionCell(parent.getContext());
                        break;
                    case VIEW_TYPE_TOPIC:
                        view = new TopicSearchCell(parent.getContext());
                        break;
                    case VIEW_TYPE_MESSAGE:
                        view = new TopicDialogCell(null, parent.getContext(), false, true);
                        ((TopicDialogCell) view).inPreviewMode = inPreviewMode;
                        break;
                    default:
                        throw new RuntimeException("unsupported view type");
                }


                view.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                return new RecyclerListView.Holder(view);
            }

            @Override
            public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
                if (getItemViewType(position) == VIEW_TYPE_HEADER) {
                    GraySectionCell headerCell = (GraySectionCell) holder.itemView;
                    if (position == topicsHeaderRow) {
                        headerCell.setText(getString(R.string.Topics));
                    }
                    if (position == messagesHeaderRow) {
                        headerCell.setText(getString(R.string.SearchMessages));
                    }
                }
                if (getItemViewType(position) == VIEW_TYPE_TOPIC) {
                    TLRPC.TL_forumTopic topic = searchResultTopics.get(position - topicsStartRow);
                    TopicSearchCell topicSearchCell = (TopicSearchCell) holder.itemView;
                    topicSearchCell.setTopic(topic);
                    topicSearchCell.drawDivider = position != topicsEndRow - 1;
                }
                if (getItemViewType(position) == VIEW_TYPE_MESSAGE) {
                    MessageObject message = searchResultMessages.get(position - messagesStartRow);
                    TopicDialogCell dialogCell = (TopicDialogCell) holder.itemView;
                    dialogCell.drawDivider = position != messagesEndRow - 1;
                    long topicId = MessageObject.getTopicId(currentAccount, message.messageOwner, true);
                    if (topicId == 0) {
                        topicId = 1;
                    }
                    TLRPC.TL_forumTopic topic = topicsController.findTopic(chatId, topicId);
                    if (topic == null) {
                        FileLog.d("cant find topic " + topicId);
                    } else {
                        dialogCell.setForumTopic(topic, message.getDialogId(), message, false, false);
                        dialogCell.setTopicIcon(topic);
                    }
                }
            }

            @Override
            public int getItemViewType(int position) {
                if (position == messagesHeaderRow || position == topicsHeaderRow) {
                    return VIEW_TYPE_HEADER;
                }
                if (position >= topicsStartRow && position < topicsEndRow) {
                    return VIEW_TYPE_TOPIC;
                }
                if (position >= messagesStartRow && position < messagesEndRow) {
                    return VIEW_TYPE_MESSAGE;
                }
                return 0;
            }

            @Override
            public int getItemCount() {
                if (isLoading) {
                    return 0;
                }
                return rowCount;
            }

            @Override
            public boolean isEnabled(RecyclerView.ViewHolder holder) {
                return holder.getItemViewType() == VIEW_TYPE_MESSAGE || holder.getItemViewType() == VIEW_TYPE_TOPIC;
            }
        }
    }

    public void setOnTopicSelectedListener(OnTopicSelectedListener listener) {
        onTopicSelectedListener = listener;
    }

    public interface OnTopicSelectedListener {
        void onTopicSelected(TLRPC.TL_forumTopic topic);
    }

    @Override
    public void onResume() {
        super.onResume();
        normalizeHeaderGlassState();
        updateUnreadBackBadge();
        getMessagesController().getTopicsController().onTopicFragmentResume(chatId);
        animatedUpdateEnabled = false;
        AndroidUtilities.updateVisibleRows(recyclerListView);
        animatedUpdateEnabled = true;
        Bulletin.addDelegate(this, new Bulletin.Delegate() {
            @Override
            public int getBottomOffset(int tag) {
                return bottomOverlayContainer != null && bottomOverlayContainer.getVisibility() == View.VISIBLE ? bottomOverlayContainer.getMeasuredHeight() : 0;
            }
        });
        if (inPreviewMode && !getMessagesController().isForum(-chatId)) {
            finishFragment();
        }
    }

    @Override
    public void onBecomeFullyVisible() {
        super.onBecomeFullyVisible();
        normalizeHeaderGlassState();
        scheduleEmojiPickerPreload();
    }

    private void normalizeHeaderGlassState() {
        if (actionBar != null) {
            if (avatarContainer != null) {
                // Rebind defensively after ActionBar/container detach-reattach.
                // The centre drawable deliberately has no fallback owner.
                actionBar.setChatAvatarContainer(avatarContainer);
            }
            // A completed/interrupted ProfileActivity transition may leave the
            // Topics glass hidden while its title children are visible.
            actionBar.setSkipDrawChild(false);
            actionBar.setGlassAlpha(1f);
            if (searchAnimator == null || !searchAnimator.isRunning()) {
                updateSearchProgress(searching ? 1f : 0f);
            }
        }
    }

    private void updateUnreadBackBadge() {
        if (actionBar != null && actionBar.backButtonImageView != null) {
            actionBar.backButtonImageView.checkUnreadView(
                    getMessagesStorage().getMainUnreadCount());
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        getMessagesController().getTopicsController().onTopicFragmentPause(chatId);
        Bulletin.removeDelegate(this);
    }

    @Override
    public void prepareFragmentToSlide(boolean topFragment, boolean beginSlide) {
        if (!topFragment && beginSlide) {
            // ProfileActivity leaves the source glass at alpha=0 while fully
            // open. Restore it before ActionBarLayout exposes this fragment for
            // an interactive/predictive back gesture, not after the gesture.
            normalizeHeaderGlassState();
            isSlideBackTransition = true;
            setFragmentIsSliding(true);
        } else {
            slideBackTransitionAnimator = null;
            isSlideBackTransition = false;
            setFragmentIsSliding(false);
            setSlideTransitionProgress(1f);
            if (!topFragment && actionBar != null) {
                // ActionBarLayout immediately hides the lower container when a
                // gesture is cancelled, so keeping its stable visible endpoint
                // cannot leak below the profile. More importantly, a later
                // non-interactive return cannot inherit alpha=0.
                actionBar.setGlassAlpha(1f);
            }
        }
    }

    private void setFragmentIsSliding(boolean sliding) {
        if (SharedConfig.getDevicePerformanceClass() == SharedConfig.PERFORMANCE_CLASS_LOW) {
            return;
        }
        ViewGroup v = contentView;
        if (v != null) {
            if (sliding) {
                v.setLayerType(View.LAYER_TYPE_HARDWARE, null);
                v.setClipChildren(false);
                v.setClipToPadding(false);
            } else {
                v.setLayerType(View.LAYER_TYPE_NONE, null);
                v.setClipChildren(true);
                v.setClipToPadding(true);
            }
        }
        contentView.requestLayout();
        actionBar.requestLayout();
    }

    @Override
    public void onSlideProgress(boolean isOpen, float progress) {
        if (SharedConfig.getDevicePerformanceClass() == SharedConfig.PERFORMANCE_CLASS_LOW) {
            return;
        }
        if (isSlideBackTransition && slideBackTransitionAnimator == null) {
            setSlideTransitionProgress(progress);
        }
    }

    private void setSlideTransitionProgress(float progress) {
        if (SharedConfig.getDevicePerformanceClass() == SharedConfig.PERFORMANCE_CLASS_LOW) {
            return;
        }
        slideFragmentProgress = progress;
        if (fragmentView != null) {
            fragmentView.invalidate();
        }

        View v = recyclerListView;
        if (v != null) {
            float s = 1f - 0.05f * (1f - slideFragmentProgress);
            v.setPivotX(0);
            v.setPivotY(0);
            v.setScaleX(s);
            v.setScaleY(s);

            actionBar.setPivotX(0);
            actionBar.setPivotY(0);
            actionBar.setScaleX(s);
            actionBar.setScaleY(s);

            if (topPanelLayout != null) {
                topPanelLayout.setPivotX(0);
                topPanelLayout.setPivotY(0);
                topPanelLayout.setScaleX(s);
                topPanelLayout.setScaleY(s);
            }
        }
    }

    private boolean openAnimationEnded;

    @Override
    public void onTransitionAnimationStart(boolean isOpen, boolean backward) {
        super.onTransitionAnimationStart(isOpen, backward);
        if (isOpen) {
            openAnimationEnded = false;
        }
        notificationsLocker.lock();
    }

    @Override
    public void onTransitionAnimationEnd(boolean isOpen, boolean backward) {
        super.onTransitionAnimationEnd(isOpen, backward);
        if (isOpen && blurredView != null) {
            if (blurredView.getParent() != null) {
                ((ViewGroup) blurredView.getParent()).removeView(blurredView);
            }
            blurredView.setBackground(null);
        }
        if (isOpen) {
            openAnimationEnded = true;
            checkGroupCallJoin(lastCallCheckFromServer);
            scheduleEmojiPickerPreload();
        }

        notificationsLocker.unlock();

        if (!isOpen) {
            if (openedForSelect && removeFragmentOnTransitionEnd) {
                removeSelfFromStack();
                if (dialogsActivity != null) {
                    dialogsActivity.removeSelfFromStack();
                }
            } else if (finishDialogRightSlidingPreviewOnTransitionEnd) {
                removeSelfFromStack();
                if (parentDialogsActivity != null && parentDialogsActivity.rightSlidingDialogContainer != null) {
                    if (parentDialogsActivity.rightSlidingDialogContainer.hasFragment()) {
                        parentDialogsActivity.rightSlidingDialogContainer.finishPreview();
                    }
                }
            }
        }
    }

    private Bitmap blurredPreviewBitmap;
    private BitmapDrawable blurredPreviewDrawable;

    private void prepareBlurBitmap() {
        if (blurredView == null || parentLayout == null) {
            return;
        }
        int w = (int) (fragmentView.getMeasuredWidth() / 6.0f);
        int h = (int) (fragmentView.getMeasuredHeight() / 6.0f);
        if (w <= 0 || h <= 0) {
            return;
        }
        View sourceView = parentLayout.getView();
        if (blurredPreviewBitmap == null
                || blurredPreviewBitmap.isRecycled()
                || blurredPreviewBitmap.getWidth() != w
                || blurredPreviewBitmap.getHeight() != h) {
            if (blurredPreviewBitmap != null && !blurredPreviewBitmap.isRecycled()) {
                blurredView.setBackground(null);
                blurredPreviewBitmap.recycle();
            }
            blurredPreviewBitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
            blurredPreviewDrawable = new BitmapDrawable(fragmentView.getResources(), blurredPreviewBitmap);
        } else {
            new Canvas(blurredPreviewBitmap).drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR);
        }
        Canvas canvas = new Canvas(blurredPreviewBitmap);
        canvas.scale(1.0f / 6.0f, 1.0f / 6.0f);
        sourceView.draw(canvas);
        Utilities.stackBlurBitmap(blurredPreviewBitmap, Math.max(7, Math.max(w, h) / 180));
        blurredView.setBackground(blurredPreviewDrawable);
        blurredView.setAlpha(0.0f);
        if (blurredView.getParent() != null) {
            ((ViewGroup) blurredView.getParent()).removeView(blurredView);
        }
        parentLayout.getOverlayContainerView().addView(blurredView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (!selectedTopics.isEmpty()) {
            if (invoked) clearSelectedTopics();
            return false;
        }
        if (searching) {
            if (invoked) actionBar.onSearchFieldVisibilityChanged(searchItem.toggleSearch(false));
            return false;
        }
        return super.onBackPressed(invoked);
    }

    @Override
    public void onTransitionAnimationProgress(boolean isOpen, float progress) {
        if (blurredView != null && blurredView.getVisibility() == View.VISIBLE) {
            if (isOpen) {
                blurredView.setAlpha(1.0f - progress);
            } else {
                blurredView.setAlpha(progress);
            }
        }
    }

    private class Item extends AdapterWithDiffUtils.Item {

        final TLRPC.TL_forumTopic topic;
        private long contentsHash;
        private TLRPC.Message cachedMessageSource;
        private long cachedMessageFingerprint = Long.MIN_VALUE;
        private MessageObject cachedMessageObject;

        public Item(int viewType, TLRPC.TL_forumTopic topic) {
            super(viewType, true);
            this.topic = topic;
            contentsHash = calculateContentsHash(topic);
        }

        private MessageObject getMessageObject(int account, TLRPC.Message message) {
            if (message == null) {
                cachedMessageSource = null;
                cachedMessageFingerprint = Long.MIN_VALUE;
                cachedMessageObject = null;
                return null;
            }
            long fingerprint = message.id;
            fingerprint = mix(fingerprint, message.date);
            fingerprint = mix(fingerprint, message.edit_date);
            fingerprint = mix(fingerprint, message.flags);
            fingerprint = mix(fingerprint, message.message);
            fingerprint = mix(fingerprint, message.media);
            if (cachedMessageSource != message || cachedMessageFingerprint != fingerprint) {
                cachedMessageSource = message;
                cachedMessageFingerprint = fingerprint;
                cachedMessageObject = new MessageObject(account, message, false, false);
            }
            return cachedMessageObject;
        }

        private long mix(long hash, long value) {
            return (hash ^ value) * 0x100000001b3L;
        }

        private long mix(long hash, Object value) {
            return mix(hash, value == null ? 0 : value.hashCode());
        }

        private long calculateContentsHash(TLRPC.TL_forumTopic topic) {
            long hash = 0xcbf29ce484222325L;
            if (topic == null) {
                return hash;
            }
            hash = mix(hash, topic.id);
            hash = mix(hash, topic.title);
            hash = mix(hash, topic.icon_color);
            hash = mix(hash, topic.icon_emoji_id);
            hash = mix(hash, topic.closed ? 1 : 0);
            hash = mix(hash, topic.pinned ? 1 : 0);
            hash = mix(hash, topic.hidden ? 1 : 0);
            hash = mix(hash, topic.pinnedOrder);
            hash = mix(hash, topic.top_message);
            hash = mix(hash, topic.read_inbox_max_id);
            hash = mix(hash, topic.read_outbox_max_id);
            hash = mix(hash, topic.unread_count);
            hash = mix(hash, topic.unread_mentions_count);
            hash = mix(hash, topic.unread_reactions_count);
            hash = mix(hash, topic.unread_poll_votes_count);
            hash = mix(hash, topic.totalMessagesCount);
            hash = mix(hash, DialogObject.getPeerDialogId(topic.from_id));
            if (topic.notify_settings != null) {
                hash = mix(hash, topic.notify_settings.flags);
                hash = mix(hash, topic.notify_settings.mute_until);
                hash = mix(hash, topic.notify_settings.silent ? 1 : 0);
            }
            if (topic.draft != null) {
                hash = mix(hash, topic.draft.flags);
                hash = mix(hash, topic.draft.date);
                hash = mix(hash, topic.draft.message);
            }
            TLRPC.Message message = topic.topMessage != null
                    ? topic.topMessage : topic.topicStartMessage;
            if (message != null) {
                hash = mix(hash, message.id);
                hash = mix(hash, message.date);
                hash = mix(hash, message.edit_date);
                hash = mix(hash, message.flags);
                hash = mix(hash, message.flags2);
                hash = mix(hash, message.message);
                hash = mix(hash, message.views);
                hash = mix(hash, message.forwards);
                hash = mix(hash, message.send_state);
                hash = mix(hash, message.unread ? 1 : 0);
                hash = mix(hash, System.identityHashCode(message.media));
                hash = mix(hash, System.identityHashCode(message.action));
                hash = mix(hash, System.identityHashCode(message.replies));
                hash = mix(hash, System.identityHashCode(message.reactions));
            }
            hash = mix(hash, topic.groupedMessages == null ? 0 : topic.groupedMessages.size());
            return hash;
        }

        void captureListContext(int position, TLRPC.TL_forumTopic nextTopic) {
            contentsHash = mix(contentsHash, position);
            contentsHash = mix(contentsHash, nextTopic != null && nextTopic.pinned ? 1 : 0);
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Item item = (Item) o;
            return viewType == item.viewType
                    && (viewType != VIEW_TYPE_TOPIC || topic.id == item.topic.id);

        }

        @Override
        protected boolean contentsEquals(AdapterWithDiffUtils.Item other) {
            return other instanceof Item && contentsHash == ((Item) other).contentsHash;

        }
    }

    @Override
    public ChatAvatarContainer getAvatarContainer() {
        return avatarContainer;
    }

    @Override
    public SizeNotifierFrameLayout getContentView() {
        return contentView;
    }

    @Override
    public void setPreviewOpenedProgress(float progress) {
        if (avatarContainer != null) {
            avatarContainer.setAlpha(progress);
            other.setAlpha(progress);
            if (searchItem != null) {
                searchItem.setAlpha(progress);
            }
            actionBar.getBackButton().setAlpha(progress);
        }
    }

    @Override
    public void setPreviewReplaceProgress(float progress) {
        if (avatarContainer != null) {
            avatarContainer.setAlpha(progress);
            avatarContainer.setTranslationX((1f - progress) * AndroidUtilities.dp(40));
        }
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ThemeDescription.ThemeDescriptionDelegate cellDelegate = () -> {
            for (int b = 0; b < 2; b++) {
                RecyclerListView list = null;
                if (b == 0) {
                    list = recyclerListView;
                } else if (searchContainer != null) {
                    list = searchContainer.recyclerView;
                }
                if (list == null) {
                    continue;
                }
                int count = list.getChildCount();
                for (int a = 0; a < count; a++) {
                    View child = list.getChildAt(a);
                    if (child instanceof ProfileSearchCell) {
                        ((ProfileSearchCell) child).update(0);
                    } else if (child instanceof DialogCell) {
                        ((DialogCell) child).update(0);
                    } else if (child instanceof UserCell) {
                        ((UserCell) child).update(0);
                    }
                }
            }
            if (actionBar != null) {
                actionBar.setPopupBackgroundColor(getThemedColor(Theme.key_actionBarDefaultSubmenuBackground), true);
                actionBar.setPopupItemsColor(getThemedColor(Theme.key_actionBarDefaultSubmenuItem), false, true);
                actionBar.setPopupItemsColor(getThemedColor(Theme.key_actionBarDefaultSubmenuItemIcon), true, true);
                actionBar.setPopupItemsSelectorColor(getThemedColor(Theme.key_dialogButtonSelector), true);
            }
            if (blurredView != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    blurredView.setForeground(new ColorDrawable(ColorUtils.setAlphaComponent(getThemedColor(Theme.key_windowBackgroundWhite), 100)));
                }
            }
            updateColors();
        };

        ArrayList<ThemeDescription> arrayList = new ArrayList<>();

        arrayList.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundWhite));
        arrayList.add(new ThemeDescription(null, 0, null, null, null, cellDelegate, Theme.key_windowBackgroundWhite));
        arrayList.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_actionBarDefaultIcon));
        arrayList.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_actionBarDefaultTitle));
        arrayList.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR, null, null, null, null, Theme.key_actionBarDefaultSelector));
        arrayList.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SEARCH, null, null, null, null, Theme.key_actionBarDefaultSearch));
        arrayList.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SEARCHPLACEHOLDER, null, null, null, null, Theme.key_actionBarDefaultSearchPlaceholder));

        if (searchContainer != null && searchContainer.recyclerView != null) {
            GraySectionCell.createThemeDescriptions(arrayList, searchContainer.recyclerView);
        }
        return arrayList;
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }
    @Override
    public boolean drawEdgeNavigationBar() {
        return false;
    }

    private int additionNavigationBarHeight;
    private int additionFloatingButtonOffset;
    private int navigationBarHeight;

    @NonNull
    private WindowInsetsCompat onApplyWindowInsets(@NonNull View v, @NonNull WindowInsetsCompat insets) {
        navigationBarHeight = insets.getInsets(WindowInsetsCompat.Type.systemBars()).bottom;
        if (searchContainer != null) {
            searchContainer.setPadding(0, 0, 0, navigationBarHeight);
        }
        if (emptyViewContainer != null) {
            emptyViewContainer.textView.setTranslationY(-navigationBarHeight - additionFloatingButtonOffset);
        }
        updateFloatingButtonOffset();
        checkUi_listViewPadding();

        return WindowInsetsCompat.CONSUMED;
    }



    /* Blur */

    private final @Nullable DownscaleScrollableNoiseSuppressor scrollableViewNoiseSuppressor;
    private final @Nullable BlurredBackgroundSourceRenderNode iBlur3SourceGlassFrosted;
    private final @Nullable BlurredBackgroundSourceRenderNode iBlur3SourceGlass;
    private final @NonNull BlurredBackgroundSourceColor iBlur3SourceColor;
    private final @NonNull BlurredBackgroundDrawableViewFactory iBlur3FactoryLiquidGlass;

    private IBlur3Capture iBlur3Capture;
    private boolean iBlur3Invalidated;
    private OnPostDrawView invalidateBlurredSourcesView;

    private final ArrayList<RectF> iBlur3Positions = new ArrayList<>();
    private final RectF iBlur3PositionActionBar = new RectF();
    private final RectF iBlur3PositionMainTabs = new RectF(); {
        iBlur3Positions.add(iBlur3PositionActionBar);
        iBlur3Positions.add(iBlur3PositionMainTabs);
    }

    private void blur3_InvalidateBlur() {
        if (invalidateBlurredSourcesView != null
                && BlurredBackgroundProviderImpl.checkBlurEnabled(currentAccount, resourceProvider)) {
            invalidateBlurredSourcesView.invalidate(1);
        }
    }

    private void blur3_UpdateBlur() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || scrollableViewNoiseSuppressor == null
                || topPanelLayout == null
                || !BlurredBackgroundProviderImpl.checkBlurEnabled(currentAccount, resourceProvider)) {
            return;
        }

        final int additionalList = dp(48);
        final int additionalSearch = dp(DialogsActivity.SEARCH_FIELD_HEIGHT) + (int) topPanelLayout.getAnimatedHeightWithPadding(dp(14));

        final View fragmentView = parentDialogsActivity != null ? parentDialogsActivity.fragmentView : this.fragmentView;
        final View actionBar = parentDialogsActivity != null ? parentDialogsActivity.getActionBar() : this.actionBar;
        if (fragmentView == null || actionBar == null
                || fragmentView.getMeasuredWidth() <= 0 || fragmentView.getMeasuredHeight() <= 0) {
            return;
        }


        final int mainTabBottom = fragmentView.getMeasuredHeight() - navigationBarHeight - dp(DialogsActivity.MAIN_TABS_MARGIN);
        final int mainTabTop = mainTabBottom - dp(DialogsActivity.MAIN_TABS_HEIGHT);

        iBlur3PositionActionBar.set(0, -additionalList, fragmentView.getMeasuredWidth(), actionBar.getMeasuredHeight() + additionalList + additionalSearch );
        iBlur3PositionMainTabs.set(0, mainTabTop, fragmentView.getMeasuredWidth(), mainTabBottom);
        iBlur3PositionMainTabs.inset(0, LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS) ? 0 : -dp(48));

        scrollableViewNoiseSuppressor.setupRenderNodes(iBlur3Positions, parentDialogsActivity != null ? 2 : 1);
        scrollableViewNoiseSuppressor.invalidateResultRenderNodes(iBlur3Capture, fragmentView.getMeasuredWidth(), fragmentView.getMeasuredHeight());
    }

    @Override
    public BlurredBackgroundSourceRenderNode getGlassSource() {
        return iBlur3SourceGlass;
    }

    public BlurredBackgroundSourceRenderNode getFrostedGlassSource() {
        return iBlur3SourceGlassFrosted;
    }

    @Override
    public void onParentScrollToTop() {
        recyclerListView.smoothScrollToPosition(0);
    }
}
