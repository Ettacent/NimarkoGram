/* Modifications Copyright (C) 2026 Ettacent */
package app.nimarkogram.messenger.documents;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.app.Dialog;
import android.os.Build;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.FileProvider;
import androidx.core.graphics.ColorUtils;
import androidx.core.graphics.Insets;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.ViewCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FlagSecureReason;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.RadialProgressView;
import org.telegram.ui.Components.AnimatedTextView;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.ChatAvatarContainer;
import org.telegram.ui.Components.inset.WindowAnimatedInsetsProvider;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;

public final class DocumentViewerActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {
    private final File file;
    private final String fileName;
    private final String mimeType;
    private final MessageObject source;
    private final long ownerUid;
    private boolean protectionLatched;
    private final boolean restrict;
    private final boolean pdf;
    private final String officeFormat;
    private OfficeDocumentView officeView;
    private PdfDocumentView pdfView;
    private boolean nativePdfFallback;
    private String pendingPdfPassword;
    private int documentGeneration;
    private TextDocumentView textView;
    private DocumentChromeLayout root;
    private LinearLayout controls;
    private LinearLayout errorView;
    private AnimatedTextView position;
    private CharSequence counterMeasureText;
    private int counterLayoutWidth = -1, counterLayoutTextHeight = -1, counterLayoutControlsHeight = -1;
    private boolean settingCounterText;
    private boolean desiredCounterWholeLabel;
    private boolean positionNumeric;
    private boolean positionSearch;
    private boolean searchPending;
    private Runnable pendingSearchStatus;
    private LinearLayout positionArea;
    private TextView positionLabel;
    private ChatAvatarContainer titleContainer;
    private ImageView previous;
    private ImageView next;
    private ImageView zoomOut;
    private ImageView zoomIn;
    private boolean initialLoadPending;
    private boolean fullyVisible;
    private final Runnable initialLoad = this::startInitialLoad;
    private TextView errorText;
    private TextView retry;
    private RadialProgressView progress;
    private ActionBarMenuItem options;
    private ActionBarMenuItem searchItem;
    private EditTextBoldCursor dialogInput;
    private boolean contentReady, contentFailed, resumed;
    private boolean destroyed;
    private int viewGeneration;
    private Dialog pageDialog;
    private DocumentNavigationSheet navigationSheet;
    private int pendingPage = -1;
    private int pendingPageGeneration = -1;
    private WindowAnimatedInsetsProvider animatedInsetsProvider;
    private boolean insetsSettling;
    private boolean modalImeHandoff;
    private int modalBottomInset;
    private final ViewTreeObserver.OnPreDrawListener pendingPageDraw = () -> {
        dispatchPendingPage();
        return true;
    };
    private final Runnable settleInsets = () -> {
        if (root == null || destroyed || animatedInsetsProvider != null && animatedInsetsProvider.hasActiveAnimations()) return;
        WindowInsetsCompat insets = WindowAnimatedInsetsProvider.calculateWindowInsets(root);
        if (insets != null) {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            if (pageDialog == null && visibleDialog == null && root.hasWindowFocus()
                    && insets.getInsets(WindowInsetsCompat.Type.ime()).bottom <= bars.bottom) modalImeHandoff = false;
            applyDocumentInsets(insets);
        }
        insetsSettling = false;
        root.invalidate();
    };
    private final WindowAnimatedInsetsProvider.Listener animatedInsetsListener = new WindowAnimatedInsetsProvider.Listener() {
        @Override public View getAnimatedInsetsTargetView() { return root; }
        @Override public void onAnimatedInsetsStarted() {
            insetsSettling = true;
            if (root != null) root.removeCallbacks(settleInsets);
        }
        @Override public void onAnimatedInsetsChanged(View view, WindowInsetsCompat insets) {
            if (view == root && !destroyed) applyDocumentInsets(insets);
        }
        @Override public void onAnimatedInsetsFinished() {  scheduleInsetsSettlement(); }
    };
    private final ThemeDescription.ThemeDescriptionDelegate colorDelegate = this::applyColors;
    private FlagSecureReason secureReason;
    private int savedPage;
    private int lastError;
    private float fontScale = 1f;
    private String encoding;
    private String query = "";
    private static final int SEARCH = 1, OPTIONS = 2, EXTERNAL = 3, SHARE = 4, ENCODING = 5;
    private static final int[] COLOR_KEYS = {
            Theme.key_windowBackgroundGray, Theme.key_windowBackgroundWhite,
            Theme.key_windowBackgroundWhiteBlackText, Theme.key_windowBackgroundWhiteBlueText,
            Theme.key_windowBackgroundWhiteGrayText, Theme.key_divider, Theme.key_listSelector,
            Theme.key_actionBarDefault, Theme.key_actionBarDefaultTitle, Theme.key_actionBarDefaultSubtitle,
            Theme.key_actionBarDefaultIcon, Theme.key_actionBarDefaultSelector,
            Theme.key_actionBarDefaultSearch, Theme.key_actionBarDefaultSearchPlaceholder,
            Theme.key_actionBarDefaultSubmenuBackground, Theme.key_actionBarDefaultSubmenuItem,
            Theme.key_actionBarDefaultSubmenuItemIcon, Theme.key_dialogButtonSelector,
            Theme.key_dialogBackground, Theme.key_dialogTextBlack, Theme.key_dialogTextHint,
            Theme.key_dialogIcon, Theme.key_dialogButton, Theme.key_dialogInputField,
            Theme.key_dialogInputFieldActivated, Theme.key_text_RedRegular,
            Theme.key_chat_inTextSelectionHighlight, Theme.key_chat_TextSelectionCursor, Theme.key_chat_topPanelBackground,
            Theme.key_dialogTextGray2, Theme.key_dialogTextBlue, Theme.key_dialogRadioBackground,
            Theme.key_dialogRadioBackgroundChecked, Theme.key_dialogSearchBackground, Theme.key_dialogSearchIcon,
            Theme.key_dialogSearchText, Theme.key_dialogSearchHint, Theme.key_switchTrack, Theme.key_player_progressBackground,
            Theme.key_featuredStickers_addButton, Theme.key_featuredStickers_buttonText, Theme.key_featuredStickers_addedIcon
    };
    private static final int[] DIALOG_BUTTONS = {
            AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL
    };
    private final int[] displayedColors = new int[COLOR_KEYS.length];
    private final int[] startColors = new int[COLOR_KEYS.length];
    private final int[] targetColors = new int[COLOR_KEYS.length];
    private boolean paletteInitialized;
    private int[] appliedContentColors;
    private ValueAnimator paletteAnimator;
    private final Runnable themeRefresh = () -> {
        if (!destroyed && resumed && root != null && !Theme.isAnimatingColor()) animatePalette();
    };

    public DocumentViewerActivity(File file, String fileName, String mimeType, DocumentTypes.Kind kind, MessageObject source,
                                  int account, boolean secured, boolean restrict) {
        this.file = file;
        this.fileName = TextUtils.isEmpty(fileName) ? file.getName() : fileName;
        this.mimeType = mimeType;
        this.source = source;
        protectionLatched = secured;
        this.restrict = restrict;
        pdf = kind == DocumentTypes.Kind.PDF;
        officeFormat = kind == DocumentTypes.Kind.OFFICE ? DocumentTypes.officeFormat(fileName, mimeType) : "";
        setCurrentAccount(account);
        ownerUid = UserConfig.getInstance(account).getClientUserId();
    }

    @Override
    public boolean onFragmentCreate() {
        if (!super.onFragmentCreate()) return false;
        getNotificationCenter().addObserver(this, NotificationCenter.appDidLogout);
        getNotificationCenter().addObserver(this, NotificationCenter.messagesDeleted);
        getNotificationCenter().addObserver(this, NotificationCenter.updateInterfaces);
        getNotificationCenter().addObserver(this, NotificationCenter.chatInfoDidLoad);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.didSetNewTheme);
        return true;
    }

    private boolean current(int generation) {
        return !destroyed && generation == viewGeneration && root != null
                && UserConfig.getInstance(currentAccount).getClientUserId() == ownerUid;
    }

    @Override public org.telegram.ui.Components.AnimatedLinearLayout getInAppNotificationPanel() {
        if (destroyed || root == null || actionBar == null || !resumed || !fullyVisible) return null;
        if (notificationInlinePanel == null || notificationInlinePanel.getParent() != root) {
            releaseInAppNotificationPanel();
            notificationInlinePanel = new app.nimarkogram.messenger.notifications.NotificationInlinePanel(this, root)
                    .withOverlayAnchor(() -> actionBar == null ? 0
                            : Math.round(actionBar.getY() + actionBar.getHeight()));
        }
        return notificationInlinePanel;
    }

    @Override public org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory getNotificationGlassFactory() {
        return destroyed || root == null ? null : root.getGlassFactory();
    }

    private boolean canExport() {
        return !destroyed && !protectionLatched && getParentActivity() != null
                && UserConfig.getInstance(currentAccount).getClientUserId() == ownerUid
                && !DocumentViewer.isProtected(source, getParentActivity());
    }

    @Override
    public View createView(Context context) {
        if (root != null) closeContent();
        cancelPendingPage();
        contentReady = contentFailed = false;
        appliedContentColors = null;
        counterMeasureText = null;
        counterLayoutWidth = counterLayoutTextHeight = counterLayoutControlsHeight = -1;
        settingCounterText = false;
        desiredCounterWholeLabel = false;
        positionNumeric = positionSearch = false;
        searchPending = false;
        pendingSearchStatus = null;
        final int generation = ++viewGeneration;
        actionBar.closeSearchField(false);
        actionBar.createMenu().clearItems();
        searchItem = null;
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(false);
        actionBar.setTitle(null);
        actionBar.setSubtitle(null);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
                else if (id == EXTERNAL) openExternally();
                else if (id == SHARE) share();
                else if (id == ENCODING) chooseEncoding();
            }
        });
        if (!pdf || OfficeDocumentView.usesWebPdf() && !nativePdfFallback) {
            ActionBarMenuItem search = searchItem = actionBar.createMenu().addItem(SEARCH, R.drawable.msg_search).setIsSearchField(true)
                    .setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
                        @Override public void onSearchExpand() {
                            if (options != null) options.setVisibility(View.GONE);
                        }
                        @Override public void onTextChanged(EditText editText) {
                            String nextQuery = editText.getText().toString();
                            if (query.equals(nextQuery)) return;
                            query = nextQuery;
                            if (root != null) root.setSearchActive(!query.isEmpty());
                            if (position != null && !query.isEmpty()) {
                                showSearchPending();
                            } else if (position != null) {
                                finishSearchPending();
                            }
                            if (textView != null) {
                                textView.setQuery(query);
                                if (query.isEmpty()) updateTextPosition(0, 0, false);
                            }
                            if (officeView != null) {
                                officeView.setQuery(query);
                                if (query.isEmpty()) updateOfficePosition();
                            }
                        }
                        @Override public void onSearchCollapse() {
                            if (options != null) options.setVisibility(protectionLatched && (pdf || !officeFormat.isEmpty())
                                    ? View.GONE : View.VISIBLE);
                            query = "";
                            if (root != null) root.setSearchActive(false);
                            finishSearchPending();
                            if (textView != null) { textView.setQuery(""); updateTextPosition(0, 0, false); }
                            if (officeView != null) { officeView.setQuery(""); updateOfficePosition(); }
                        }
                    });
            search.setSearchFieldHint(LocaleController.getString(R.string.NM_DocSearch));
            EditTextBoldCursor searchField = search.getSearchField();
            searchField.setImeOptions(EditorInfo.IME_ACTION_NEXT | EditorInfo.IME_FLAG_NO_FULLSCREEN
                    | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
            searchField.setOnKeyListener((view, key, event) -> handleSearchKey(key, event));
            searchField.setOnEditorActionListener((view, action, event) -> {
                if (event != null) return handleSearchKey(event.getKeyCode(), event);
                if (action != EditorInfo.IME_ACTION_SEARCH && action != EditorInfo.IME_ACTION_NEXT
                        && action != EditorInfo.IME_ACTION_PREVIOUS) return false;
                navigateSearch(action != EditorInfo.IME_ACTION_PREVIOUS);
                return true;
            });
            if (!officeFormat.isEmpty()) search.getSearchField().setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(512)});
        }
        options = actionBar.createMenu().addItem(OPTIONS, R.drawable.ic_ab_other);
        options.setContentDescription(LocaleController.getString(R.string.AccDescrMoreOptions));
        if (!pdf && officeFormat.isEmpty()) options.addSubItem(ENCODING, R.drawable.msg_language, LocaleController.getString(R.string.NM_DocEncoding));
        if (!protectionLatched) {
            options.addSubItem(EXTERNAL, R.drawable.msg_openin, LocaleController.getString(R.string.OpenInExternalApp));
            options.addSubItem(SHARE, R.drawable.msg_share, LocaleController.getString(R.string.ShareFile));
        }
        if (protectionLatched && (pdf || !officeFormat.isEmpty())) options.setVisibility(View.GONE);
        root = new DocumentChromeLayout(context, currentAccount) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                if (position != null) {
                    counterLayoutTextHeight = position.getTextHeight();
                    counterLayoutControlsHeight = counterControlsHeight(counterLayoutTextHeight);
                    counterLayoutWidth = counterReservedWidth();
                    position.getLayoutParams().height = counterLayoutTextHeight;
                    controls.getLayoutParams().height = counterLayoutControlsHeight;
                    setCounterWidth(counterLayoutWidth);
                }
                super.onMeasure(widthSpec, heightSpec);
            }
            @Override protected void onContentInsetsChanged(int top, int bottom) {
                if (pdfView != null) { pdfView.setTopInset(top); pdfView.setBottomInset(bottom); }
                if (officeView != null) { officeView.setTopInset(top); officeView.setBottomInset(bottom); }
                if (textView != null) { textView.setTopInset(top); textView.setBottomInset(bottom); }
            }
            @Override public void onWindowFocusChanged(boolean hasWindowFocus) {
                super.onWindowFocusChanged(hasWindowFocus);
                if (hasWindowFocus && current(generation)) scheduleInsetsSettlement();
            }
        };
        root.setFocusableInTouchMode(true);
        root.setSearchActive(!query.isEmpty());
        root.getViewTreeObserver().addOnPreDrawListener(pendingPageDraw);
        root.setBottomInset(getBottomInset());
        fragmentView = root;
        actionBar.setAddToContainer(false);
        actionBar.setupGlass(root.getGlassFactory(), BlurredBackgroundProviderImpl.topPanelChatActivity(currentAccount, this::uiColor));
        titleContainer = new ChatAvatarContainer(context, this, false, this::uiColor) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                super.onMeasure(widthSpec, heightSpec);
                final int textWidthSpec = MeasureSpec.makeMeasureSpec(
                        Math.max(0, getMeasuredWidth() - getLeftPadding() - AndroidUtilities.dp(12)),
                        MeasureSpec.EXACTLY);
                final SimpleTextView title = getTitleTextView();
                title.measure(textWidthSpec, MeasureSpec.makeMeasureSpec(title.getMeasuredHeight(), MeasureSpec.EXACTLY));
                final View subtitle = getSubtitleTextView();
                subtitle.measure(textWidthSpec, MeasureSpec.makeMeasureSpec(subtitle.getMeasuredHeight(), MeasureSpec.EXACTLY));
            }
        };
        titleContainer.setGlassMode();
        titleContainer.setOccupyStatusBar(actionBar.getOccupyStatusBar());
        titleContainer.getAvatarImageView().setVisibility(View.GONE);
        titleContainer.setEnabled(false);
        titleContainer.getTitleTextView().setText(fileName);
        titleContainer.getTitleTextView().setScrollNonFitText(false);
        ((SimpleTextView) titleContainer.getSubtitleTextView()).setText(
                (pdf ? "PDF" : !officeFormat.isEmpty() ? officeFormat.toUpperCase(java.util.Locale.ROOT) : LocaleController.getString(R.string.NM_DocText))
                        + " · " + AndroidUtilities.formatFileSize(file.length()));
        actionBar.addView(titleContainer, 0, LayoutHelper.createFrame(-2, -1, Gravity.TOP | Gravity.LEFT, 52, 0, searchItem == null ? 72 : 120, 0));
        titleContainer.setActionBar(actionBar);
        actionBar.setChatAvatarContainer(titleContainer);
        actionBar.setStandardSearchGlassProgress(true);
        actionBar.setCastShadows(false);
        if (getParentActivity() instanceof LaunchActivity) {
            animatedInsetsProvider = ((LaunchActivity) getParentActivity()).getRootAnimatedInsetsListener();
            if (animatedInsetsProvider != null) animatedInsetsProvider.subscribeToWindowInsetsAnimation(animatedInsetsListener);
        }
        if (secureReason != null) secureReason.detach();
        if (getParentActivity() != null) {
            secureReason = new FlagSecureReason(getParentActivity().getWindow(),
                    () -> protectionLatched);
            secureReason.attach();
        }
        createReaderContent(context, generation);
        controls = root.controls;
        zoomOut = iconButton(context, R.drawable.msg_zoomout, R.string.NM_DocZoomOut, () -> zoom(false));
        root.zoom.addView(zoomOut, new LinearLayout.LayoutParams(AndroidUtilities.dp(48), -1));
        previous = iconButton(context, R.drawable.msg_arrowright, R.string.NM_DocPrevious, () -> {
            cancelPendingPage();
            if (pdfView != null) pdfView.goToPage(pdfView.getCurrentPage() - 1);
            else if (officeView != null) { if (query.isEmpty()) officeView.previous(); else officeView.findNext(false); }
            else if (textView != null) textView.nextMatch(false);
        });
        previous.setScaleX(-1f);
        root.navigation.addView(previous, new LinearLayout.LayoutParams(AndroidUtilities.dp(48), -1));
        positionArea = new LinearLayout(context) {
            @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
                super.onSizeChanged(w, h, oldw, oldh);
                setBackground(Theme.createSimpleSelectorRoundRectDrawable(Math.min(w, h) / 2, 0, uiColor(Theme.key_listSelector)));
            }
        };
        positionArea.setOrientation(LinearLayout.VERTICAL);
        positionArea.setGravity(Gravity.CENTER);
        positionArea.setFocusable(true);
        positionLabel = new TextView(context);
        positionLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        positionLabel.setSingleLine(true);
        positionLabel.setIncludeFontPadding(false);
        positionLabel.setEllipsize(TextUtils.TruncateAt.END);
        positionLabel.setGravity(Gravity.CENTER);
        positionLabel.setVisibility(View.GONE);
        positionLabel.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        positionArea.addView(positionLabel, new LinearLayout.LayoutParams(-1, -2));
        position = new AnimatedTextView(context, false, true, true) {
            @Override public void requestLayout() {
                if (!settingCounterText) super.requestLayout();
            }
            @Override public void setText(CharSequence text, boolean animated, boolean moveDown) {
                if (position == this && !isAnimating()) applyCounterMotion();
                boolean previous = settingCounterText;
                settingCounterText = true;
                try { super.setText(text, animated, moveDown); }
                finally { settingCounterText = previous; }
            }
        };
        position.adaptWidth = false;
        position.setGravity(Gravity.CENTER);
        position.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        position.setTypeface(AndroidUtilities.bold());
        position.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14, context.getResources().getDisplayMetrics()));
        position.setEllipsizeByGradient(true);
        position.setAnimationProperties(.2f, 0, 220, CubicBezierInterpolator.EASE_OUT_QUINT);
        positionArea.setContentDescription(LocaleController.getString(pdf || !officeFormat.isEmpty() ? R.string.NM_DocGoToPage : R.string.NM_DocSearch));
        positionArea.setOnClickListener(v -> {
            if (destroyed) return;
            if (pdfView != null || officeView != null && query.isEmpty()) goToPageDialog();
            else if (searchItem != null) actionBar.openSearchField(query, true);
        });
        positionArea.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        positionArea.addView(position, new LinearLayout.LayoutParams(-1, position.getTextHeight()));
        root.navigation.addView(positionArea, new LinearLayout.LayoutParams(0, -1, 1));
        next = iconButton(context, R.drawable.msg_arrowright, R.string.NM_DocNext, () -> {
            cancelPendingPage();
            if (pdfView != null) pdfView.goToPage(pdfView.getCurrentPage() + 1);
            else if (officeView != null) { if (query.isEmpty()) officeView.next(); else officeView.findNext(true); }
            else if (textView != null) textView.nextMatch(true);
        });
        root.navigation.addView(next, new LinearLayout.LayoutParams(AndroidUtilities.dp(48), -1));
        zoomIn = iconButton(context, R.drawable.msg_zoomin, R.string.NM_DocZoomIn, () -> zoom(true));
        root.zoom.addView(zoomIn, new LinearLayout.LayoutParams(AndroidUtilities.dp(48), -1));
        controls.setVisibility(View.INVISIBLE);
        progress = new RadialProgressView(context);
        root.addView(progress, LayoutHelper.createFrame(44, 44, Gravity.CENTER));
        errorView = new LinearLayout(context);
        errorView.setOrientation(LinearLayout.VERTICAL);
        errorView.setGravity(Gravity.CENTER);
        errorView.setPadding(AndroidUtilities.dp(28), AndroidUtilities.dp(16), AndroidUtilities.dp(28), AndroidUtilities.dp(16));
        errorText = new TextView(context);
        errorText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        errorText.setGravity(Gravity.CENTER);
        errorView.addView(errorText, new LinearLayout.LayoutParams(-1, -2));
        retry = button(context, LocaleController.getString(R.string.NM_DocRetry), R.string.NM_DocRetry, () -> {
            if (pdf && lastError == PdfRenderClient.ERROR_PASSWORD) passwordDialog(); else load(null);
        });
        retry.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        LinearLayout.LayoutParams retryParams = new LinearLayout.LayoutParams(-1, AndroidUtilities.dp(48));
        retryParams.topMargin = AndroidUtilities.dp(12);
        errorView.addView(retry, retryParams);
        root.addView(errorView, LayoutHelper.createFrame(-1, -1, Gravity.FILL));
        errorView.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        errorView.setVisibility(View.GONE);
        root.setHeader(actionBar);
        applyColors();
        initialLoadPending = true;
        if (fullyVisible) root.postOnAnimation(initialLoad);
        return fragmentView;
    }

    private void zoom(boolean increase) {
        cancelPendingPage();
        if (pdfView != null) { if (increase) pdfView.zoomIn(); else pdfView.zoomOut(); }
        else if (officeView != null) { if (increase) officeView.zoomIn(); else officeView.zoomOut(); }
        else setFontScale((textView == null ? fontScale : textView.getRequestedFontScale()) + (increase ? .1f : -.1f));
    }

    private void navigateSearch(boolean forward) {
        if (destroyed || !resumed || !fullyVisible || !contentReady || contentFailed || searchPending || query.isEmpty()) return;
        cancelPendingPage();
        if (officeView != null) officeView.findNext(forward);
        else if (textView != null) textView.nextMatch(forward);
    }

    private boolean handleSearchKey(int key, KeyEvent event) {
        if (key != KeyEvent.KEYCODE_ENTER && key != KeyEvent.KEYCODE_NUMPAD_ENTER
                && key != KeyEvent.KEYCODE_SEARCH) return false;
        if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            navigateSearch(!event.isShiftPressed());
        }
        return true;
    }

    private void startInitialLoad() {
        if (current(viewGeneration) && resumed && fullyVisible && initialLoadPending) {
            initialLoadPending = false;
            load(null);
        }
    }

    private ImageView iconButton(Context context, int icon, int description, Runnable onClick) {
        ImageView view = new ImageView(context);
        view.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        view.setImageResource(icon);
        view.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(12), AndroidUtilities.dp(12), AndroidUtilities.dp(12));
        view.setContentDescription(LocaleController.getString(description));
        view.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), Theme.RIPPLE_MASK_CIRCLE_20DP));
        view.setFocusable(true);
        view.setOnClickListener(v -> { if (!destroyed) onClick.run(); });
        return view;
    }

    private TextView button(Context context, String text, int description, Runnable onClick) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
        view.setGravity(Gravity.CENTER);
        view.setContentDescription(LocaleController.getString(description));
        view.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector)));
        view.setOnClickListener(v -> { if (!destroyed) onClick.run(); });
        return view;
    }

    private boolean currentDocument(int view, int document) {
        return current(view) && documentGeneration == document;
    }

    private void createReaderContent(Context context, int generation) {
        final int owner = ++documentGeneration;
        if (pdf && (nativePdfFallback || !OfficeDocumentView.usesWebPdf())) {
            pdfView = new PdfDocumentView(context, new PdfDocumentView.Listener() {
                @Override public void onReady(int count) {
                    if (!currentDocument(generation, owner)) return;
                    pendingPdfPassword = null;
                    pdfView.goToPage(Math.min(savedPage, count - 1));
                    updatePage(pdfView.getCurrentPage(), count);
                }
                @Override public void onPageChanged(int page, int count) {
                    if (currentDocument(generation, owner)) { savedPage = page; updatePage(page, count); }
                }
                @Override public void onFailure(int code) {
                    if (currentDocument(generation, owner)) { pendingPdfPassword = null; showError(code); }
                }
                @Override public void onFirstPageRendered() { if (currentDocument(generation, owner)) showReady(); }
            });
            pdfView.setRenderingPaused(!resumed || !fullyVisible);
            root.setContent(pdfView);
        } else if (pdf || !officeFormat.isEmpty()) {
            officeView = new OfficeDocumentView(context, new OfficeDocumentView.Listener() {
                @Override public boolean isResumeFrameStateSettled() {
                    return currentDocument(generation, owner) && resumeFrameStateSettled();
                }
                @Override public void onReady(int count) {
                    if (!currentDocument(generation, owner)) return;
                    pendingPdfPassword = null;
                    showReady();
                    if (query.isEmpty()) updateOfficePosition();
                }
                @Override public void onPosition(int index, int count, String label) {
                    if (!currentDocument(generation, owner)) return;
                    savedPage = index;
                    if (query.isEmpty()) updateOfficePosition();
                }
                @Override public void onSearch(int index, int count, boolean truncated, boolean pending) {
                    if (currentDocument(generation, owner) && !query.isEmpty()) {
                        if (pending) showSearchPending();
                        else updateTextPosition(index, count, truncated);
                    }
                }
                @Override public void onFailure(int code) {
                    if (!currentDocument(generation, owner)) return;
                    pendingPdfPassword = null;
                    showError(pdf ? (code == OfficeDocumentView.ERROR_PASSWORD ? PdfRenderClient.ERROR_PASSWORD
                            : code == OfficeDocumentView.ERROR_TIMEOUT ? PdfRenderClient.ERROR_TIMEOUT
                            : PdfRenderClient.ERROR_FILE) : code);
                }
                @Override public void onNativePdfRequired() {
                    if (!currentDocument(generation, owner) || !pdf || nativePdfFallback) return;
                    nativePdfFallback = true;
                    String password = pendingPdfPassword;
                    if (officeView != null) {
                        OfficeDocumentView old = officeView;
                        officeView = null;
                        old.close();
                        if (old.getParent() == root) root.removeView(old);
                    }
                    actionBar.closeSearchField(false);
                    query = "";
                    finishSearchPending();
                    root.setSearchActive(false);
                    if (searchItem != null) searchItem.setVisibility(View.GONE);
                    if (titleContainer != null && titleContainer.getLayoutParams() instanceof android.widget.FrameLayout.LayoutParams) {
                        android.widget.FrameLayout.LayoutParams params = (android.widget.FrameLayout.LayoutParams) titleContainer.getLayoutParams();
                        params.rightMargin = AndroidUtilities.dp(72);
                        titleContainer.setLayoutParams(params);
                    }
                    createReaderContent(context, generation);
                    appliedContentColors = null;
                    applyContentColors();
                    load(password);
                }
            });
            if (!resumed || !fullyVisible) officeView.pause();
            officeView.setAllowCopy(!protectionLatched);
            root.setContent(officeView);
        } else {
            textView = new TextDocumentView(context, !protectionLatched, new TextDocumentView.Listener() {
                @Override public void onReady(int blocks) {
                    if (!currentDocument(generation, owner)) return;
                    showReady();
                    if (query.isEmpty()) updateTextPosition(0, 0, false);
                    else showSearchPending();
                    textView.setFontScale(fontScale);
                    textView.setQuery(query);
                }
                @Override public void onSearchChanged(int index, int count, boolean truncated) {
                    if (currentDocument(generation, owner)) updateTextPosition(index, count, truncated);
                }
                @Override public void onScaleChanged(float scale) {
                    if (!currentDocument(generation, owner)) return;
                    fontScale = scale;
                    if (query.isEmpty() && position != null) {
                        String percentage = Math.round(scale * 100) + "%";
                        if (!TextUtils.equals(position.getText(), percentage)) {
                            setCounterText(percentage, false);
                            positionArea.setContentDescription(LocaleController.getString(R.string.NM_DocSearch)
                                    + ", " + position.getText());
                        }
                    }
                }
                @Override public void onFailure(int code) { if (currentDocument(generation, owner)) showError(code); }
            });
            textView.setMonospaced(DocumentTypes.isMonospaced(fileName));
            root.setContent(textView);
        }
    }

    private void load(String password) {
        if (!current(viewGeneration)) return;
        cancelPendingPage();
        if (root != null) root.beginContentLoad(contentView());
        contentReady = contentFailed = false;
        settleTransitions();
        errorView.setVisibility(View.GONE);
        progress.setVisibility(View.VISIBLE);
        controls.setVisibility(View.INVISIBLE);
        pendingPdfPassword = pdf ? password : null;
        if (pdfView != null) pdfView.open(file, password);
        else if (officeView != null) {
            if (pdf) officeView.openPdf(file, savedPage, password);
            else officeView.open(file, officeFormat, savedPage);
            officeView.setQuery(query);
        }
        else if (textView != null) textView.open(file, encoding);
    }

    private View contentView() {
        return pdfView != null ? pdfView : officeView != null ? officeView : textView;
    }

    private void showReady() {
        if (contentReady || contentFailed) return;
        contentReady = true;
        contentFailed = false;
        if (!resumed || !fullyVisible) {
            settleTransitions();
            return;
        }
        if (motionEnabled()) root.beginControlsParentReveal();
        fade(controls, true);
        fade(progress, false);
        View content = contentView();
        if (content != null && officeView == null) {
            fade(content, true);
        }
    }

    private void fade(View view, boolean visible) {
        view.animate().cancel();
        if (!resumed || !fullyVisible || !motionEnabled()) {
            view.setAlpha(1f);
            view.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
            return;
        }
        if (visible && view.getVisibility() != View.VISIBLE) view.setAlpha(0f);
        view.setVisibility(View.VISIBLE);
        view.animate().alpha(visible ? 1f : 0f).setDuration(220).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).withEndAction(() -> {
            if (!visible) view.setVisibility(View.INVISIBLE);
        }).start();
    }

    private void settleTransitions() {
        if (root == null) return;
        for (View view : new View[]{contentView(), controls, progress, errorView}) {
            if (view == null) continue;
            view.animate().cancel();
            view.setAlpha(1f);
        }
        View content = contentView();
        if (content != null) {
            content.setVisibility(contentFailed ? View.INVISIBLE : View.VISIBLE);
            content.setAlpha(contentReady || officeView != null ? 1f : pdfView != null ? .01f : 0f);
        }
        controls.setVisibility(contentReady ? View.VISIBLE : View.INVISIBLE);
        progress.setVisibility(!contentReady && !contentFailed ? View.VISIBLE : View.INVISIBLE);
        errorView.setVisibility(contentFailed ? View.VISIBLE : View.GONE);
    }

    private void updatePage(int page, int count) {
        counterMeasureText = LocaleController.formatString(R.string.NM_DocPageOf, count, count);
        setPositionText(LocaleController.formatString(R.string.NM_DocPageOf, page + 1, count));
        positionArea.setContentDescription(LocaleController.getString(R.string.NM_DocGoToPage) + ", " + position.getText());
        setAvailable(previous, page > 0);
        setAvailable(next, page + 1 < count);
        if (pdfView != null) pdfView.setContentDescription(position.getText());
    }

    private void updateTextPosition(int index, int count, boolean truncated) {
        if (position == null) return;
        finishSearchPending();
        counterMeasureText = query.isEmpty() ? "200%" : searchCounterMeasureText();
        setPositionText(query.isEmpty() ? Math.round(fontScale * 100) + "%"
                : count == 0 ? LocaleController.getString(truncated ? R.string.NM_DocSearchLimited : R.string.NM_DocNoMatches)
                : index < 0 ? LocaleController.formatString(R.string.NM_DocMatchesFound, count) + (truncated ? "+" : "")
                : LocaleController.formatString(R.string.NM_DocMatchOf, index + 1, count) + (truncated ? "+" : ""), null,
                query.isEmpty() || count > 0 && index >= 0);
        positionArea.setContentDescription(LocaleController.getString(R.string.NM_DocSearch) + ", " + position.getText()
                + (truncated && count > 0 ? ", " + LocaleController.getString(R.string.NM_DocSearchLimited) : ""));
        setAvailable(previous, !query.isEmpty() && count > 0);
        setAvailable(next, !query.isEmpty() && count > 0);
    }

    private CharSequence searchCounterMeasureText() {
        CharSequence widest = LocaleController.formatString(R.string.NM_DocMatchOf,
                TextDocumentView.MAX_MATCHES, TextDocumentView.MAX_MATCHES) + "+";
        String unselected = LocaleController.formatString(R.string.NM_DocMatchesFound, TextDocumentView.MAX_MATCHES) + "+";
        if (position.getPaint().measureText(unselected) > position.getPaint().measureText(widest.toString())) widest = unselected;
        float width = position.getPaint().measureText(widest.toString());
        for (int resource : new int[]{R.string.NM_DocSearching, R.string.NM_DocNoMatches, R.string.NM_DocSearchLimited}) {
            String label = LocaleController.getString(resource);
            float measured = position.getPaint().measureText(label);
            if (measured > width) {
                widest = label;
                width = measured;
            }
        }
        return widest;
    }

    private void showSearchPending() {
        counterMeasureText = searchCounterMeasureText();
        requestCounterLayoutIfNeeded(false);
        positionArea.setContentDescription(LocaleController.getString(R.string.NM_DocSearching) + ", " + query);
        setAvailable(previous, false);
        setAvailable(next, false);
        if (searchPending) return;
        searchPending = true;
        if (positionSearch && !positionNumeric) {
            String searching = LocaleController.getString(R.string.NM_DocSearching);
            if (!TextUtils.equals(position.getText(), searching)) setPositionText(searching, null, false);
            setSearchEmphasis(true);
            return;
        }
        setSearchEmphasis(true);
        int generation = viewGeneration;
        pendingSearchStatus = () -> {
            if (!current(generation) || !searchPending || query.isEmpty()) return;
            pendingSearchStatus = null;
            setPositionText(LocaleController.getString(R.string.NM_DocSearching), null, false);
            setSearchEmphasis(false);
        };
        root.postDelayed(pendingSearchStatus, 120);
    }

    private void finishSearchPending() {
        searchPending = false;
        if (pendingSearchStatus != null && root != null) root.removeCallbacks(pendingSearchStatus);
        pendingSearchStatus = null;
        setSearchEmphasis(false);
    }

    private void setSearchEmphasis(boolean pending) {
        if (position == null) return;
        position.animate().cancel();
        float alpha = pending ? .5f : 1f;
        if (position.getAlpha() == alpha) return;
        if (resumed && fullyVisible && motionEnabled()) position.animate().alpha(alpha).setDuration(120).start();
        else position.setAlpha(alpha);
    }

    private void updateOfficePosition() {
        if (officeView == null || position == null) return;
        int index = officeView.getCurrentIndex();
        int count = officeView.getCount();
        counterMeasureText = LocaleController.formatString(R.string.NM_DocPageOf, count, count);
        String name = officeView.getCurrentSheetName();
        if ("xlsx".equals(officeFormat) && !TextUtils.isEmpty(name)) {
            setPositionText((index + 1) + " / " + count, name);
            positionArea.setContentDescription(LocaleController.getString(R.string.NM_DocChooseSheet) + ": " + name + ", " + position.getText());
        } else {
            setPositionText(LocaleController.formatString(R.string.NM_DocPageOf, index + 1, count));
            positionArea.setContentDescription(LocaleController.getString(R.string.NM_DocGoToPage) + ", " + position.getText());
        }
        setAvailable(previous, index > 0);
        setAvailable(next, count > 0 && index < count - 1);
    }

    private void setPositionText(CharSequence value) {
        setPositionText(value, null);
    }

    private void setPositionText(CharSequence value, CharSequence label) {
        setPositionText(value, label, true);
    }

    private void setPositionText(CharSequence value, CharSequence label, boolean numeric) {
        boolean showLabel = !TextUtils.isEmpty(label);
        boolean visibilityChanged = (positionLabel.getVisibility() == View.VISIBLE) != showLabel;
        boolean labelChanged = showLabel ? !TextUtils.equals(positionLabel.getText(), label) : positionLabel.length() != 0;
        if (labelChanged) {
            positionLabel.setText(label);
        }
        if (visibilityChanged) positionLabel.setVisibility(showLabel ? View.VISIBLE : View.GONE);
        boolean searchTransition = !query.isEmpty() || positionSearch;
        boolean leavingSearch = positionSearch && query.isEmpty();
        desiredCounterWholeLabel = searchTransition
                || position.isAnimating() && position.getDrawable().updateAll;
        position.setAllowCancel(false);
        setCounterText(value, resumed && fullyVisible && motionEnabled()
                && !leavingSearch && (searchTransition || numeric && positionNumeric));
        positionNumeric = numeric;
        positionSearch = !query.isEmpty();
        requestCounterLayoutIfNeeded(visibilityChanged || showLabel && labelChanged);
    }

    private void setCounterText(CharSequence value, boolean animated) {
        boolean previous = settingCounterText;
        settingCounterText = true;
        try { position.setText(value, animated); }
        finally { settingCounterText = previous; }
    }

    private void applyCounterMotion() {
        boolean wholeLabel = desiredCounterWholeLabel;
        position.getDrawable().updateAll = wholeLabel;
        position.setAnimationProperties(wholeLabel ? 0 : .2f, 0, wholeLabel ? 160 : 220, wholeLabel ? 0 : 1,
                wholeLabel ? CubicBezierInterpolator.EASE_OUT : CubicBezierInterpolator.EASE_OUT_QUINT);
    }

    private int counterReservedWidth() {
        CharSequence text = counterMeasureText != null ? counterMeasureText : position.getText();
        int width = (int) Math.ceil(position.getPaint().measureText(text == null ? "" : text.toString()));
        return Math.max(0, width + (query.isEmpty() ? AndroidUtilities.dp(16) : 0));
    }

    private int counterControlsHeight(int textHeight) {
        return Math.max(AndroidUtilities.dp(56), textHeight
                + (positionLabel.getVisibility() == View.VISIBLE ? positionLabel.getLineHeight() : 0) + AndroidUtilities.dp(16));
    }

    private void requestCounterLayoutIfNeeded(boolean force) {
        if (root == null || position == null || positionLabel == null) return;
        int width = counterReservedWidth();
        int textHeight = position.getTextHeight();
        int controlsHeight = counterControlsHeight(textHeight);
        if (force || width != counterLayoutWidth || textHeight != counterLayoutTextHeight
                || controlsHeight != counterLayoutControlsHeight) {
            counterLayoutWidth = width;
            counterLayoutTextHeight = textHeight;
            counterLayoutControlsHeight = controlsHeight;
            root.requestLayout();
        }
    }

    private void setAvailable(View view, boolean available) {
        if (view == null) return;
        if (view.isEnabled() == available) return;
        view.setEnabled(available);
        view.animate().cancel();
        if (resumed && fullyVisible && motionEnabled()) view.animate().alpha(available ? 1f : .35f).setDuration(160).start();
        else view.setAlpha(available ? 1f : .35f);
    }

    private void setFontScale(float value) {
        float scale = Math.max(TextDocumentView.MIN_SCALE, Math.min(TextDocumentView.MAX_SCALE, value));
        if (textView != null) textView.setFontScale(scale);
        else {
            fontScale = scale;
            if (query.isEmpty()) updateTextPosition(0, 0, false);
        }
    }

    private void showError(int code) {
        cancelPendingPage();
        lastError = code;
        contentReady = false;
        contentFailed = true;
        settleTransitions();
        int message = R.string.NM_DocOpenError;
        if (pdf) {
            if (code == PdfRenderClient.ERROR_PASSWORD) message = R.string.NM_DocPasswordRequired;
            else if (code == PdfRenderClient.ERROR_UNSUPPORTED_PASSWORD) message = R.string.NM_DocPasswordUnsupported;
            else if (code == PdfRenderClient.ERROR_TIMEOUT) message = R.string.NM_DocTimeout;
        } else if (officeView != null) {
            if (code == OfficeDocumentView.ERROR_TOO_LARGE) message = R.string.NM_DocOfficeTooLarge;
            else if (code == OfficeDocumentView.ERROR_WEBVIEW) message = R.string.NM_DocOfficeWebView;
            else if (code == OfficeDocumentView.ERROR_PASSWORD) message = R.string.NM_DocOfficePassword;
            else if (code == OfficeDocumentView.ERROR_TIMEOUT) message = R.string.NM_DocTimeout;
        } else if (code == TextDocumentView.ERROR_TOO_LARGE) message = R.string.NM_DocTextTooLarge;
        else if (code == TextDocumentView.ERROR_ENCODING) message = R.string.NM_DocEncodingError;
        errorText.setText(LocaleController.getString(message));
        retry.setText(LocaleController.getString(pdf && code == PdfRenderClient.ERROR_PASSWORD
                ? R.string.NM_DocEnterPassword : R.string.NM_DocRetry));
        retry.setContentDescription(retry.getText());
        errorView.setVisibility(View.VISIBLE);
    }

    private void goToPageDialog() {
        if (getParentActivity() == null || destroyed || !contentReady) return;
        cancelPendingPage();
        final int generation = viewGeneration;
        int count = pdfView != null ? pdfView.getPageCount() : officeView != null ? officeView.getCount() : 0;
        if (count <= 0) return;
        int current = pdfView != null ? pdfView.getCurrentPage() : officeView.getCurrentIndex();
        String[] names = "xlsx".equals(officeFormat) ? officeView.getSheetNames() : null;
        if (names != null && names.length != count) names = null;
        navigationSheet = new DocumentNavigationSheet(getParentActivity(), this::uiColor, count, current, names,
                (sheet, index) -> submitPage(sheet.getDialog(), sheet.getEditor(), index, generation));
        navigationSheet.setProtected(protectionLatched);
        pageDialog = navigationSheet.getDialog();
        if (showDialog(pageDialog) == null) { pageDialog = null; navigationSheet = null; }
    }

    private void submitPage(Dialog dialog, EditText input, int index, int generation) {
        if (!current(generation) || pageDialog != dialog || pendingPage >= 0) return;
        int count = pdfView != null ? pdfView.getPageCount() : officeView != null ? officeView.getCount() : 0;
        if (index < 0 || index >= count) return;
        pendingPage = index;
        pendingPageGeneration = generation;
        AndroidUtilities.hideKeyboard(input != null ? input : root);
        if (input != null) {
            input.clearFocus();
        }
        dialog.dismiss();
    }

    @Override public Dialog showDialog(Dialog dialog, boolean allowInTransition, Dialog.OnDismissListener onDismissListener) {
        boolean wasModal = modalImeHandoff;
        if (root != null && dialog != null) {
            if (!modalImeHandoff) modalBottomInset = root.getBottomInset();
            modalImeHandoff = true;
        }
        Dialog shown = super.showDialog(dialog, allowInTransition, onDismissListener);
        if (shown == null) {
            modalImeHandoff = wasModal;
            if (visibleDialog == dialog) visibleDialog = null;
            scheduleInsetsSettlement();
        }
        return shown;
    }

    @Override protected void onDialogDismiss(Dialog dialog) {
        super.onDialogDismiss(dialog);
        if (dialog == visibleDialog) dialogInput = null;
        if (modalImeHandoff) scheduleInsetsSettlement();
        if (dialog != pageDialog) return;
        pageDialog = null;
        navigationSheet = null;
        dialogInput = null;
        if (root != null) {
            root.requestFocus();
            ViewCompat.requestApplyInsets(root);
            scheduleInsetsSettlement();
        }
    }

    private void cancelPendingPage() {
        pendingPage = -1;
        pendingPageGeneration = -1;
    }

    private void dispatchPendingPage() {
        if (pendingPage < 0) return;
        if (!current(pendingPageGeneration) || !resumed || !fullyVisible || !contentReady || contentFailed) {
            cancelPendingPage();
            return;
        }
        if (pageDialog != null || visibleDialog != null || !root.hasWindowFocus() || !root.isLaidOut()
                || modalImeHandoff || insetsSettling || animatedInsetsProvider != null && animatedInsetsProvider.hasActiveAnimations()) return;
        WindowInsetsCompat insets = WindowAnimatedInsetsProvider.calculateWindowInsets(root);
        if (insets == null) return;
        Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
        if (insets.getInsets(WindowInsetsCompat.Type.ime()).bottom > bars.bottom) return;
        applyDocumentInsets(insets);
        if (root.isLayoutRequested()) return;
        int page = pendingPage;
        cancelPendingPage();
        if (pdfView != null && page < pdfView.getPageCount()) pdfView.goToPage(page);
        else if (officeView != null && page < officeView.getCount()) officeView.goToIndex(page);
    }

    private EditText input(Context context, int type) {
        EditTextBoldCursor input = new EditTextBoldCursor(context);
        dialogInput = input;
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        input.setSingleLine(true);
        input.setInputType(type);
        input.setBackground(null);
        applyInputColors();
        return input;
    }

    private void applyInputColors() {
        if (dialogInput == null) return;
        dialogInput.setTextColor(uiColor(Theme.key_dialogTextBlack));
        dialogInput.setHintTextColor(uiColor(Theme.key_dialogTextHint));
        dialogInput.setCursorColor(uiColor(Theme.key_dialogTextBlack));
        dialogInput.setHandlesColor(uiColor(Theme.key_chat_TextSelectionCursor));
        dialogInput.setHighlightColor(uiColor(Theme.key_chat_inTextSelectionHighlight));
        dialogInput.setLineColors(uiColor(Theme.key_dialogInputField),
                uiColor(Theme.key_dialogInputFieldActivated), uiColor(Theme.key_text_RedRegular));
        dialogInput.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(12), AndroidUtilities.dp(24), AndroidUtilities.dp(12));
    }

    private void passwordDialog() {
        if (getParentActivity() == null) return;
        EditText input = input(getParentActivity(), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                .setTitle(LocaleController.getString(R.string.NM_DocEnterPassword)).setView(input)
                .setPositiveButton(LocaleController.getString(R.string.OK), (dialog, which) -> {
                    String password = input.getText().toString();
                    input.setText("");
                    load(password);
                }).setNegativeButton(LocaleController.getString(R.string.Cancel), null).create());
        input.requestFocus();
        AndroidUtilities.showKeyboard(input);
    }

    private void chooseEncoding() {
        if (getParentActivity() == null || textView == null) return;
        String[] encodings = {"UTF-8", "UTF-16LE", "UTF-16BE", "windows-1251", "GB18030"};
        showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                .setTitle(LocaleController.getString(R.string.NM_DocEncoding))
                .setItems(encodings, (dialog, which) -> { encoding = encodings[which]; load(null); }).create());
    }

    private void openExternally() {
        if (!canExport()) return;
        try {
            if (!AndroidUtilities.openForViewExternal(file, fileName, mimeType, getParentActivity(), getResourceProvider(), restrict)) {
                showActionError();
            }
        } catch (Exception e) { showActionError(); }
    }

    private void share() {
        if (!canExport()) return;
        try {
            android.net.Uri uri = FileProvider.getUriForFile(getParentActivity(), ApplicationLoader.getApplicationId() + ".provider", file);
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType(pdf ? "application/pdf" : !officeFormat.isEmpty() ? DocumentTypes.officeMime(officeFormat) : "text/plain");
            intent.putExtra(Intent.EXTRA_STREAM, uri);
            intent.setClipData(android.content.ClipData.newRawUri(fileName, uri));
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getParentActivity().startActivity(Intent.createChooser(intent, LocaleController.getString(R.string.ShareFile)));
        } catch (Exception e) { showActionError(); }
    }

    private void showActionError() {
        if (getParentActivity() != null && !destroyed) showDialog(new AlertDialog.Builder(getParentActivity(), getResourceProvider())
                .setMessage(LocaleController.getString(R.string.NM_DocExternalError))
                .setPositiveButton(LocaleController.getString(R.string.OK), null).create());
    }

    private void applyColors() {
        cancelPaletteAnimation();
        if (root == null || destroyed) return;
        for (int i = 0; i < COLOR_KEYS.length; i++) displayedColors[i] = getThemedColor(COLOR_KEYS[i]);
        paletteInitialized = true;
        applyDisplayedColors();
    }

    private int uiColor(int key) {
        if (paletteInitialized) {
            for (int i = 0; i < COLOR_KEYS.length; i++) {
                if (COLOR_KEYS[i] == key) return displayedColors[i];
            }
        }
        return getThemedColor(key);
    }

    private void cancelPaletteAnimation() {
        if (paletteAnimator == null) return;
        ValueAnimator animator = paletteAnimator;
        paletteAnimator = null;
        animator.removeAllUpdateListeners();
        animator.cancel();
    }

    private void animatePalette() {
        cancelPaletteAnimation();
        if (!paletteInitialized || !motionEnabled()) { applyColors(); return; }
        boolean changed = false;
        for (int i = 0; i < COLOR_KEYS.length; i++) {
            startColors[i] = displayedColors[i];
            targetColors[i] = getThemedColor(COLOR_KEYS[i]);
            changed |= startColors[i] != targetColors[i];
        }
        if (!changed) {
            applyContentColors();
            return;
        }
        ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
        paletteAnimator = animator;
        animator.setDuration(180);
        animator.addUpdateListener(animation -> {
            if (paletteAnimator != animation) return;
            if (destroyed || !resumed || root == null) { cancelPaletteAnimation(); return; }
            if (Theme.isAnimatingColor() || !motionEnabled()) { applyColors(); return; }
            float fraction = (float) animation.getAnimatedValue();
            for (int i = 0; i < COLOR_KEYS.length; i++) {
                displayedColors[i] = ColorUtils.blendARGB(startColors[i], targetColors[i], fraction);
            }
            applyDisplayedColors();
        });
        animator.start();
    }

    private void applyDisplayedColors() {
        if (root == null || destroyed) return;
        actionBar.updateColors();
        actionBar.setBackground(null);
        titleContainer.setTitleColors(uiColor(Theme.key_actionBarDefaultTitle), uiColor(Theme.key_actionBarDefaultSubtitle));
        actionBar.setItemsColor(uiColor(Theme.key_actionBarDefaultIcon), false);
        actionBar.setItemsBackgroundColor(uiColor(Theme.key_actionBarDefaultSelector), false);
        actionBar.setSearchTextColor(uiColor(Theme.key_actionBarDefaultSearch), false);
        actionBar.setSearchTextColor(uiColor(Theme.key_actionBarDefaultSearchPlaceholder), true);
        actionBar.setPopupBackgroundColor(uiColor(Theme.key_actionBarDefaultSubmenuBackground), false);
        actionBar.setPopupItemsColor(uiColor(Theme.key_actionBarDefaultSubmenuItem), false, false);
        actionBar.setPopupItemsColor(uiColor(Theme.key_actionBarDefaultSubmenuItemIcon), true, false);
        actionBar.setPopupItemsSelectorColor(uiColor(Theme.key_dialogButtonSelector), false);
        if (searchItem != null) {
            EditTextBoldCursor search = searchItem.getSearchField();
            search.setCursorColor(uiColor(Theme.key_actionBarDefaultSearch));
            search.setHighlightColor(uiColor(Theme.key_chat_inTextSelectionHighlight));
            search.setHandlesColor(uiColor(Theme.key_chat_TextSelectionCursor));
        }
        int background = uiColor(Theme.key_windowBackgroundGray);
        int foreground = uiColor(Theme.key_windowBackgroundWhiteBlackText);
        int accent = uiColor(Theme.key_windowBackgroundWhiteBlueText);
        root.setColors(background, uiColor(Theme.key_windowBackgroundWhite), uiColor(Theme.key_divider));
        position.setTextColor(foreground);
        positionLabel.setTextColor(uiColor(Theme.key_windowBackgroundWhiteGrayText));
        Theme.setSelectorDrawableColor(positionArea.getBackground(), uiColor(Theme.key_listSelector), true);
        for (ImageView control : new ImageView[]{previous, next, zoomOut, zoomIn}) {
            control.setColorFilter(new PorterDuffColorFilter(accent, PorterDuff.Mode.SRC_IN));
            Theme.setSelectorDrawableColor(control.getBackground(), uiColor(Theme.key_listSelector), true);
        }
        errorView.setBackgroundColor(background);
        errorText.setTextColor(foreground);
        retry.setTextColor(accent);
        Theme.setSelectorDrawableColor(retry.getBackground(), uiColor(Theme.key_listSelector), true);
        progress.setProgressColor(accent);
        applyContentColors();
        applyInputColors();
        if (navigationSheet != null) navigationSheet.updateColors();
        if (visibleDialog instanceof AlertDialog) {
            AlertDialog dialog = (AlertDialog) visibleDialog;
            dialog.setBackgroundColor(uiColor(Theme.key_dialogBackground));
            dialog.setTextColor(uiColor(Theme.key_dialogTextBlack));
            for (int i = 0; i < dialog.getItemsCount(); i++) {
                dialog.setItemColor(i, uiColor(Theme.key_dialogTextBlack), uiColor(Theme.key_dialogIcon));
            }
            for (int id : DIALOG_BUTTONS) {
                View button = dialog.getButton(id);
                if (button instanceof TextView) {
                    ((TextView) button).setTextColor(uiColor(Theme.key_dialogButton));
                    Theme.setSelectorDrawableColor(button.getBackground(), uiColor(Theme.key_dialogButtonSelector), true);
                }
            }
        }
    }

    private void applyContentColors() {
        if (root == null || destroyed) return;
        boolean nightPaper = Theme.isCurrentThemeDark();
        int background = uiColor(Theme.key_windowBackgroundGray);
        int foreground = uiColor(Theme.key_windowBackgroundWhiteBlackText);
        int accent = uiColor(Theme.key_windowBackgroundWhiteBlueText);
        int[] contentColors = {background, foreground, accent, uiColor(Theme.key_divider),
                uiColor(Theme.key_windowBackgroundWhiteGrayText), uiColor(Theme.key_windowBackgroundWhite),
                nightPaper ? 1 : 0};
        if (!Arrays.equals(appliedContentColors, contentColors)) {
            appliedContentColors = contentColors;
            if (pdfView != null) {
                pdfView.setColors(background, contentColors[3], contentColors[4]);
                pdfView.setReadingColors(contentColors[5], foreground, nightPaper);
            }
            if (officeView != null) {
                officeView.setColors(background, foreground, accent);
                officeView.setReadingColors(contentColors[5], foreground, nightPaper, true);
            }
            if (textView != null) textView.setColors(contentColors[5], foreground, contentColors[4], (accent & 0x00ffffff) | 0x55000000);
        }
    }

    @Override public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> descriptions = super.getThemeDescriptions();
        if (descriptions == null) descriptions = new ArrayList<>();
        for (int i = 0; i < COLOR_KEYS.length; i++) {
            final int index = i;
            descriptions.add(new ThemeDescription(null, 0, null, null, null, colorDelegate, COLOR_KEYS[i]) {
                private boolean captureStart = true;

                @Override public int getSetColor() {
                    return captureStart && paletteInitialized ? displayedColors[index] : super.getSetColor();
                }

                @Override public ThemeDescriptionDelegate setDelegateDisabled() {
                    captureStart = false;
                    cancelPaletteAnimation();
                    if (root != null) root.removeCallbacks(themeRefresh);
                    return super.setDelegateDisabled();
                }
            });
        }
        return descriptions;
    }

    @Override public boolean isSwipeBackEnabled(MotionEvent event) {
        return officeView == null && (pdfView == null || !pdfView.isZoomed())
                && (textView == null || !textView.isZoomed() && (event == null || event.getPointerCount() < 2))
                && super.isSwipeBackEnabled(event);
    }

    @Override public boolean isSupportEdgeToEdge() { return true; }

    @Override public WindowInsetsCompat onInsetsInternal(View view, WindowInsetsCompat insets) {
        if (!insetsSettling && (animatedInsetsProvider == null || !animatedInsetsProvider.hasActiveAnimations())) {
            applyDocumentInsets(insets);
            if (modalImeHandoff) scheduleInsetsSettlement();
        }
        return WindowInsetsCompat.CONSUMED;
    }

    private void applyDocumentInsets(WindowInsetsCompat insets) {
        Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
        Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
        onInsets(bars.left, bars.top, bars.right, Math.max(bars.bottom, modalImeHandoff ? modalBottomInset : ime.bottom));
    }

    private void scheduleInsetsSettlement() {
        if (root == null || destroyed) return;
        insetsSettling = true;
        root.removeCallbacks(settleInsets);
        root.postOnAnimation(settleInsets);
    }

    public boolean isReadyForResumeSnapshot() {
        if (!current(viewGeneration) || contentFailed || !contentReady || officeView == null) return true;
        return resumeFrameStateSettled() && officeView.isReadyForResumeSnapshot();
    }

    private boolean resumeFrameStateSettled() {
        return root != null && resumed && fullyVisible && !root.isLayoutRequested() && !insetsSettling
                && (animatedInsetsProvider == null || !animatedInsetsProvider.hasActiveAnimations());
    }

    @Override public void onInsets(int left, int top, int right, int bottom) {
        if (root != null) {
            root.setPadding(left, 0, right, 0);
            root.setBottomInset(bottom);
        }
    }

    private void stopDocumentScrolling() {
        cancelPendingPage();
        if (root != null) {
            long now = android.os.SystemClock.uptimeMillis();
            MotionEvent cancel = MotionEvent.obtain(now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0);
            try {
                root.dispatchTouchEvent(cancel);
            } finally {
                cancel.recycle();
            }
        }
        if (textView != null) textView.stopScrolling();
        if (officeView != null) officeView.stopScrolling();
        if (pdfView != null) {
            pdfView.setRenderingPaused(true);
            pdfView.setRenderingPaused(!resumed || !fullyVisible);
        }
    }

    @Override public void onTransitionAnimationStart(boolean isOpen, boolean backward) {
        super.onTransitionAnimationStart(isOpen, backward);
        if (!isOpen) stopDocumentScrolling();
    }

    @Override public void prepareFragmentToSlide(boolean topFragment, boolean beginSlide) {
        super.prepareFragmentToSlide(topFragment, beginSlide);
        if (topFragment && beginSlide) stopDocumentScrolling();
    }

    @Override public void onBecomeFullyVisible() {
        super.onBecomeFullyVisible();
        fullyVisible = true;
        if (!current(viewGeneration)) return;
        if (root != null && resumed) scheduleInsetsSettlement();
        if (officeView != null) { if (resumed) officeView.resume(); else officeView.pause(); }
        if (pdfView != null) pdfView.setRenderingPaused(!resumed);
        if (root != null) {
            root.setActive(resumed);
            root.removeCallbacks(initialLoad);
            root.postOnAnimation(initialLoad);
        }
    }

    @Override public void onResume() {
        super.onResume();
        resumed = true;
        if (position != null) position.setAnimationPaused(false);
        if (UserConfig.getInstance(currentAccount).getClientUserId() != ownerUid) { dismissContent(); return; }
        refreshProtection();
        if (secureReason != null) secureReason.attach();
        applyColors();
        if (root != null) scheduleInsetsSettlement();
        if (officeView != null) { if (fullyVisible) officeView.resume(); else officeView.pause(); }
        if (pdfView != null) pdfView.setRenderingPaused(!fullyVisible);
        if (root != null) {
            root.setActive(fullyVisible);
            if (fullyVisible && initialLoadPending) root.postOnAnimation(initialLoad);
        }
    }

    @Override public void onPause() {
        resumed = false;
        stopDocumentScrolling();
        if (pdfView != null) pdfView.setRenderingPaused(true);
        cancelPendingPage();
        cancelPaletteAnimation();
        if (root != null) {
            root.removeCallbacks(themeRefresh);
            root.removeCallbacks(initialLoad);
            root.setActive(false);
        }
        if (position != null) position.setAnimationPaused(true);
        for (ImageView control : new ImageView[]{previous, next, zoomOut, zoomIn}) {
            if (control != null) { control.animate().cancel(); control.setAlpha(control.isEnabled() ? 1f : .35f); }
        }
        settleTransitions();
        if (officeView != null) officeView.pause();
        super.onPause();
    }

    @Override public void onBecomeFullyHidden() {
        super.onBecomeFullyHidden();
        fullyVisible = false;
        if (officeView != null) officeView.pause();
        if (pdfView != null) pdfView.setRenderingPaused(true);
        cancelPendingPage();
        if (root != null) root.setActive(false);
        if (secureReason != null) secureReason.detach();
    }

    @Override public void clearViews() {
        closeContent();
        root = null;
        super.clearViews();
    }

    private void closeContent() {
        releaseInAppNotificationPanel();
        cancelPendingPage();
        if (pageDialog != null) {
            Dialog dialog = pageDialog;
            pageDialog = null;
            dialog.dismiss();
        }
        navigationSheet = null;
        if (animatedInsetsProvider != null) {
            animatedInsetsProvider.unsubscribeFromWindowInsetsAnimation(animatedInsetsListener);
            animatedInsetsProvider = null;
        }
        insetsSettling = false;
        modalImeHandoff = false;
        modalBottomInset = 0;
        if (actionBar != null) {
            actionBar.setStandardSearchGlassProgress(false);
            if (titleContainer != null) {
                titleContainer.setActionBar(null);
                actionBar.clearChatAvatarContainer(titleContainer);
                actionBar.removeView(titleContainer);
                titleContainer = null;
            }
        }
        cancelPaletteAnimation();
        if (root != null) {
            root.removeCallbacks(settleInsets);
            root.getViewTreeObserver().removeOnPreDrawListener(pendingPageDraw);
            root.removeCallbacks(themeRefresh);
            root.removeCallbacks(initialLoad);
            root.release();
        }
        initialLoadPending = false;
        pendingPdfPassword = null;
        ++documentGeneration;
        settleTransitions();
        dialogInput = null;
        viewGeneration++;
        if (pdfView != null) { savedPage = pdfView.getCurrentPage(); pdfView.close(); pdfView = null; }
        if (textView != null) { textView.close(); textView = null; }
        if (officeView != null) { savedPage = officeView.getCurrentIndex(); officeView.close(); officeView = null; }
        root = null;
    }

    @Override public void onFragmentDestroy() {
        destroyed = true;
        closeContent();
        if (secureReason != null) { secureReason.detach(); secureReason = null; }
        getNotificationCenter().removeObserver(this, NotificationCenter.appDidLogout);
        getNotificationCenter().removeObserver(this, NotificationCenter.messagesDeleted);
        getNotificationCenter().removeObserver(this, NotificationCenter.updateInterfaces);
        getNotificationCenter().removeObserver(this, NotificationCenter.chatInfoDidLoad);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.didSetNewTheme);
        super.onFragmentDestroy();
    }

    @Override public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.didSetNewTheme) {
            if (root != null && resumed && !destroyed) {
                root.removeCallbacks(themeRefresh);
                root.postOnAnimation(themeRefresh);
            }
        } else if (id == NotificationCenter.appDidLogout) dismissContent();
        else if (id == NotificationCenter.updateInterfaces || id == NotificationCenter.chatInfoDidLoad) refreshProtection();
        else if (id == NotificationCenter.messagesDeleted && source != null && args.length > 2
                && args[0] instanceof ArrayList && ((ArrayList<?>) args[0]).contains(source.getId())
                && args[1] instanceof Long && (Long) args[1] == source.getChannelId()
                && args[2] instanceof Boolean && (Boolean) args[2] == source.scheduled) {
            dismissContent();
        }
    }

    private void refreshProtection() {
        boolean wasProtected = protectionLatched;
        if (DocumentViewer.isProtected(source, getParentActivity())) protectionLatched = true;
        if (!wasProtected && protectionLatched && root != null) root.invalidateGlassCaptureForPrivacy();
        if (secureReason != null) secureReason.invalidate();
        if (protectionLatched) {
            if (navigationSheet != null) navigationSheet.setProtected(true);
            if (textView != null) textView.setAllowCopy(false);
            if (officeView != null) officeView.setAllowCopy(false);
            if (options != null) {
                options.hideSubItem(EXTERNAL);
                options.hideSubItem(SHARE);
                if (pdf || !officeFormat.isEmpty()) options.setVisibility(View.GONE);
            }
        }
    }

    private void dismissContent() {
        if (root != null) root.setVisibility(View.INVISIBLE);
        closeContent();
        finishFragment();
    }

    private static boolean motionEnabled() {
        return SharedConfig.animationsEnabled() && (Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled());
    }
}
