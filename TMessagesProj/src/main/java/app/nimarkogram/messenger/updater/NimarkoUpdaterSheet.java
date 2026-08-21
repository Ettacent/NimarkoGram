package app.nimarkogram.messenger.updater;

import static org.telegram.messenger.LocaleController.getString;

import android.content.Context;
import android.graphics.Outline;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.SpannedString;
import android.text.TextUtils;
import android.text.style.ClickableSpan;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.core.widget.NestedScrollView;

import java.lang.ref.WeakReference;
import java.util.Locale;
import java.util.regex.Matcher;

import io.noties.markwon.AbstractMarkwonPlugin;
import io.noties.markwon.Markwon;
import io.noties.markwon.MarkwonConfiguration;
import io.noties.markwon.core.MarkwonTheme;
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.SimpleTextView;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Components.AnimatedTextView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LinkSpanDrawable;
import org.telegram.ui.Components.StickerImageView;
import org.telegram.ui.Components.URLSpanNoUnderline;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

public class NimarkoUpdaterSheet extends BottomSheet implements NimarkoUpdater.DownloadUiOwner {

    private static final String STICKER_PACK = "PixelAnimeGirls";
    private static final int STICKER_NUM = 11;
    private static final LruCache<ChangelogCacheKey, CharSequence> CHANGELOG_CACHE = new LruCache<>(4);

    private BaseFragment fragment;
    private Theme.ResourcesProvider resourcesProvider;
    private boolean downloadButtonClicked = false;
    private boolean downloadFinished = false;
    private boolean bindingDetached;
    private long downloadBindingToken;
    private ButtonWithCounterView downloadButton;

    public NimarkoUpdaterSheet(Context context, Theme.ResourcesProvider resourcesProvider, boolean available, NimarkoUpdater.Update update) {
        super(context, false, resourcesProvider);
        setOpenNoDelay(true);
        fixNavigationBar();
        setCanDismissWithSwipe(false);
        setCanDismissWithTouchOutside(true);

        LinearLayout rootLayout = new LinearLayout(context);
        rootLayout.setOrientation(LinearLayout.VERTICAL);

        UpdateContentScrollView scrollView = new UpdateContentScrollView(context, available);
        scrollView.setFillViewport(false);
        scrollView.setClipToPadding(false);
        scrollView.setVerticalFadingEdgeEnabled(true);
        scrollView.setFadingEdgeLength(AndroidUtilities.dp(16));

        LinearLayout contentLayout = new LinearLayout(context);
        contentLayout.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(contentLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        rootLayout.addView(scrollView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        if (available) {
            FrameLayout header = new FrameLayout(context);
            contentLayout.addView(header, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 80, 21, 10, 0, 0));

            StickerImageView imageView = new StickerImageView(context, currentAccount);
            imageView.setStickerPackName(STICKER_PACK);
            imageView.setStickerNum(STICKER_NUM);
            imageView.getImageReceiver().setAutoRepeat(1);
            imageView.setOutlineProvider(new ViewOutlineProvider() {
                @Override
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), AndroidUtilities.dp(16));
                }
            });
            imageView.setClipToOutline(true);
            header.addView(imageView, LayoutHelper.createFrame(60, 60, Gravity.LEFT | Gravity.CENTER_VERTICAL));

            SimpleTextView nameView = new SimpleTextView(context);
            nameView.setTextSize(20);
            nameView.setTypeface(AndroidUtilities.bold());
            nameView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
            nameView.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
            nameView.setText(getString(R.string.UP_UpdateAvailable));
            header.addView(nameView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 30, Gravity.LEFT, 75, 5, 0, 0));

            if (!TextUtils.isEmpty(update.uploadDate)) {
                AnimatedTextView timeView = new AnimatedTextView(context, true, true, false);
                timeView.setAnimationProperties(0.7f, 0, 450, CubicBezierInterpolator.EASE_OUT_QUINT);
                timeView.setIgnoreRTL(!LocaleController.isRTL);
                timeView.adaptWidth = false;
                timeView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
                timeView.setTextSize(AndroidUtilities.dp(13));
                timeView.setTypeface(AndroidUtilities.bold());
                timeView.setGravity(Gravity.LEFT | Gravity.CENTER_VERTICAL);
                timeView.setText(update.uploadDate);
                header.addView(timeView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 20, Gravity.LEFT, 75, 35, 0, 0));
            }
        }

        TextCell version = new TextCell(context, resourcesProvider);
        version.setBackground(createRowPressedBackground(resourcesProvider));
        if (available) {
            version.setTextAndValueAndIcon(getString(R.string.UP_Version), update.version.replaceAll("v|-beta|-force", ""), R.drawable.msg_info, true);
        } else {
            version.setTextAndValueAndIcon(getString(R.string.UP_CurrentVersion), NimarkoUpdater.getCurrentVersionName(), R.drawable.msg_info, false);
        }
        version.setOnClickListener(v -> copyText(version.getTextView().getText() + ": " + version.getValueTextView().getText()));
        contentLayout.addView(version);

        if (available && !TextUtils.isEmpty(update.changelog)) {
            HeaderCell changelogHeader = new HeaderCell(context, resourcesProvider);
            changelogHeader.setText(getString(R.string.UP_Changelog));
            contentLayout.addView(changelogHeader);

            LinkSpanDrawable.LinksTextView changelogView = new LinkSpanDrawable.LinksTextView(context, resourcesProvider);
            int textColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider);
            int linkColor = Theme.getColor(Theme.key_windowBackgroundWhiteLinkText, resourcesProvider);
            changelogView.setTextColor(textColor);
            changelogView.setLinkTextColor(linkColor);
            changelogView.setHighlightColor(Theme.multAlpha(linkColor, 0.18f));
            changelogView.setTextSize(15);
            changelogView.setLineSpacing(AndroidUtilities.dp(3), 1.0f);
            changelogView.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP);
            changelogView.setMovementMethod(new AndroidUtilities.LinkMovementMethodMy());
            changelogView.setPadding(AndroidUtilities.dp(21), 0, AndroidUtilities.dp(21), AndroidUtilities.dp(16));
            String markdown = normalizeMarkdown(update.changelog);
            CharSequence formatted = CHANGELOG_CACHE.get(new ChangelogCacheKey(markdown, ChangelogStyle.from(resourcesProvider)));
            changelogView.setText(formatted != null ? formatted : markdown);
            contentLayout.addView(changelogView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        }

        LinearLayout footerLayout = new LinearLayout(context);
        footerLayout.setOrientation(LinearLayout.VERTICAL);
        footerLayout.setBackgroundColor(getThemedColor(Theme.key_dialogBackground));

        if (available) {
            downloadButton = new ButtonWithCounterView(context, resourcesProvider).setRound();
            downloadButton.setFilled(true);
            downloadButton.setText(getUpdateSizeString(update), false);
            if (NimarkoUpdater.isUpdateDownloaded()) {
                downloadFinished = true;
                downloadButton.setText(getString(R.string.UP_Install), false);
            }
            downloadBindingToken = NimarkoUpdater.bindDownloadUi(downloadButton, this);
            NimarkoUpdater.DownloadUiState downloadState = NimarkoUpdater.getDownloadUiState();
            if (downloadState.finished) {
                downloadFinished = true;
                downloadButton.setText(getString(R.string.UP_Install), false);
            } else if (downloadState.downloading || downloadState.paused) {
                downloadButtonClicked = true;
                downloadButton.setText(LocaleController.formatString(R.string.AppUpdateDownloading, downloadState.progress), false);
            }
            downloadButton.setOnClickListener(v -> {
                if (downloadFinished) {
                    if (NimarkoUpdater.apkFile != null) {
                        NimarkoUpdater.installApk(getContext(), NimarkoUpdater.apkFile.getAbsolutePath());
                    }
                    return;
                }
                NimarkoUpdater.DownloadUiState currentState = NimarkoUpdater.getDownloadUiState();
                if (currentState.paused) {
                    downloadButtonClicked = true;
                    NimarkoUpdater.resumeDownload(getContext().getApplicationContext());
                    return;
                }
                if (!downloadButtonClicked) {
                    downloadButtonClicked = true;
                    NimarkoUpdater.downloadApk(getContext(), update.downloadURL,
                            "NimarkoGram " + update.version, downloadBindingToken);
                }
            });
            footerLayout.addView(downloadButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 14, 16, 0));

            ButtonWithCounterView scheduleButton = new ButtonWithCounterView(context, resourcesProvider).setRound();
            scheduleButton.setText(getString(R.string.AppUpdateRemindMeLater), false);
            scheduleButton.setOnClickListener(v -> {
                NimarkoUpdateConfig.setUpdateScheduleTimestamp(System.currentTimeMillis());
                dismiss();
            });
            footerLayout.addView(scheduleButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 8, 16, 12));
        } else {
            TextCell checkOnLaunch = new TextCell(context, 23, false, true, resourcesProvider);
            checkOnLaunch.setBackground(createRowPressedBackground(resourcesProvider));
            checkOnLaunch.setTextAndCheckAndIcon(getString(R.string.UP_Auto_OTA), NimarkoUpdateConfig.getAutoOTA(), R.drawable.msg_retry, false);
            checkOnLaunch.setOnClickListener(v -> {
                NimarkoUpdateConfig.setAutoOTA(!NimarkoUpdateConfig.getAutoOTA());
                checkOnLaunch.setChecked(!checkOnLaunch.isChecked());
            });
            contentLayout.addView(checkOnLaunch);

            TextCell clearUpdates = new TextCell(context, resourcesProvider);
            clearUpdates.setBackground(createRowPressedBackground(resourcesProvider));
            clearUpdates.setTextAndIcon(getString(R.string.UP_ClearUpdatesCache), R.drawable.msg_clear, false);
            clearUpdates.setOnClickListener(v -> {
                if (NimarkoUpdater.getOtaDirSize().replaceAll("\\D+", "").equals("0")) {
                    BulletinFactory.of(getContainer(), null).createErrorBulletin(getString(R.string.UP_NothingToClear)).show();
                } else {
                    BulletinFactory.of(getContainer(), null).createErrorBulletin(LocaleController.formatString(R.string.UP_ClearedUpdatesCache, NimarkoUpdater.getOtaDirSize())).show();
                    NimarkoUpdater.cleanOtaDir();
                }
                NimarkoUpdater.cancelDownload(getContext(), NimarkoUpdater.id);
                NimarkoUpdateConfig.setUpdateAvailable(false);
            });
            contentLayout.addView(clearUpdates);

            ButtonWithCounterView checkUpdatesButton = new ButtonWithCounterView(context, resourcesProvider).setRound();
            checkUpdatesButton.setText(getString(R.string.UP_CheckForUpdates), true);
            checkUpdatesButton.setOnClickListener(v ->
                    NimarkoUpdater.checkUpdates(fragment, true,
                            () -> BulletinFactory.of(getContainer(), resourcesProvider).createErrorBulletin(getString(R.string.UP_Not_Found)).show(),
                            this::dismiss,
                            () -> BulletinFactory.of(getContainer(), resourcesProvider).createErrorBulletin(getString(R.string.UP_CheckFailed)).show()));
            footerLayout.addView(checkUpdatesButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 16, 16, 16));
        }

        View divider = new View(context);
        divider.setBackgroundColor(Theme.getColor(Theme.key_divider, resourcesProvider));
        rootLayout.addView(divider, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 1));
        rootLayout.addView(footerLayout, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        setCustomView(rootLayout);
        setOnDismissListener(() -> {
            bindingDetached = true;
            long token = downloadBindingToken;
            downloadBindingToken = 0L;
            if (token != 0L) NimarkoUpdater.unbindDownloadUi(token);
        });
    }

    private static CharSequence formatChangelog(Context context, ChangelogStyle style, String markdown) {
        ChangelogCacheKey key = new ChangelogCacheKey(markdown, style);
        CharSequence cached = CHANGELOG_CACHE.get(key);
        if (cached != null) return cached;
        try {
            Markwon markwon = Markwon.builder(context)
                    .usePlugin(StrikethroughPlugin.create())
                    .usePlugin(new AbstractMarkwonPlugin() {
                        @Override
                        public void configureTheme(@NonNull MarkwonTheme.Builder builder) {
                            builder
                                    .linkColor(style.linkColor)
                                    .isLinkUnderlined(false)
                                    .blockMargin(AndroidUtilities.dp(16))
                                    .blockQuoteWidth(AndroidUtilities.dp(3))
                                    .blockQuoteColor(Theme.multAlpha(style.linkColor, 0.72f))
                                    .listItemColor(style.linkColor)
                                    .bulletListItemStrokeWidth(AndroidUtilities.dp(1))
                                    .bulletWidth(AndroidUtilities.dp(6))
                                    .codeTextColor(style.textColor)
                                    .codeBlockTextColor(style.textColor)
                                    .codeBackgroundColor(Theme.multAlpha(style.textColor, 0.08f))
                                    .codeBlockBackgroundColor(Theme.multAlpha(style.textColor, 0.08f))
                                    .codeBlockMargin(AndroidUtilities.dp(12))
                                    .codeTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MONO))
                                    .codeBlockTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MONO))
                                    .codeTextSize(AndroidUtilities.dp(14))
                                    .codeBlockTextSize(AndroidUtilities.dp(14))
                                    .headingTypeface(AndroidUtilities.bold())
                                    .headingTextSizeMultipliers(new float[]{1.30f, 1.24f, 1.18f, 1.12f, 1.06f, 1.0f})
                                    .headingBreakColor(style.dividerColor)
                                    .headingBreakHeight(0)
                                    .thematicBreakColor(style.dividerColor)
                                    .thematicBreakHeight(AndroidUtilities.dp(1));
                        }

                        @Override
                        public void configureConfiguration(@NonNull MarkwonConfiguration.Builder builder) {
                            builder.linkResolver((view, link) -> {
                                String url = normalizeUrl(link);
                                if (url != null) {
                                    Browser.openUrl(view.getContext(), Uri.parse(url));
                                }
                            });
                        }
                    })
                    .build();
            SpannableStringBuilder result = new SpannableStringBuilder(markwon.toMarkdown(markdown));
            addBareLinks(result);
            CharSequence formatted = new SpannedString(result);
            CHANGELOG_CACHE.put(key, formatted);
            return formatted;
        } catch (Throwable e) {
            FileLog.e(e);
            SpannableStringBuilder result = new SpannableStringBuilder(markdown);
            addBareLinks(result);
            CharSequence formatted = new SpannedString(result);
            CHANGELOG_CACHE.put(key, formatted);
            return formatted;
        }
    }

    private static String normalizeMarkdown(String source) {
        return source == null ? "" : source.replace("\r\n", "\n").replace('\r', '\n').trim();
    }

    private static final class ChangelogStyle {
        final int textColor;
        final int linkColor;
        final int dividerColor;

        ChangelogStyle(int textColor, int linkColor, int dividerColor) {
            this.textColor = textColor;
            this.linkColor = linkColor;
            this.dividerColor = dividerColor;
        }

        static ChangelogStyle from(Theme.ResourcesProvider resourcesProvider) {
            return new ChangelogStyle(
                    Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider),
                    Theme.getColor(Theme.key_windowBackgroundWhiteLinkText, resourcesProvider),
                    Theme.getColor(Theme.key_divider, resourcesProvider));
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) return true;
            if (!(object instanceof ChangelogStyle)) return false;
            ChangelogStyle other = (ChangelogStyle) object;
            return textColor == other.textColor
                    && linkColor == other.linkColor
                    && dividerColor == other.dividerColor;
        }

        @Override
        public int hashCode() {
            int result = textColor;
            result = 31 * result + linkColor;
            return 31 * result + dividerColor;
        }
    }

    private static final class ChangelogCacheKey {
        final String markdown;
        final ChangelogStyle style;

        ChangelogCacheKey(String markdown, ChangelogStyle style) {
            this.markdown = markdown;
            this.style = style;
        }

        @Override
        public boolean equals(Object object) {
            if (this == object) return true;
            if (!(object instanceof ChangelogCacheKey)) return false;
            ChangelogCacheKey other = (ChangelogCacheKey) object;
            return markdown.equals(other.markdown) && style.equals(other.style);
        }

        @Override
        public int hashCode() {
            return 31 * markdown.hashCode() + style.hashCode();
        }
    }

    private static void addBareLinks(SpannableStringBuilder text) {
        if (text.length() == 0 || AndroidUtilities.WEB_URL == null) return;
        Matcher matcher = AndroidUtilities.WEB_URL.matcher(text);
        while (matcher.find()) {
            if (text.getSpans(matcher.start(), matcher.end(), ClickableSpan.class).length != 0) continue;
            String url = normalizeUrl(matcher.group());
            if (url != null) {
                text.setSpan(new URLSpanNoUnderline(url, true), matcher.start(), matcher.end(),
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
    }

    private static String normalizeUrl(String value) {
        if (TextUtils.isEmpty(value)) return null;
        String url = value.trim();
        Uri uri;
        try {
            uri = Uri.parse(url);
            if (TextUtils.isEmpty(uri.getScheme())) {
                if (AndroidUtilities.WEB_URL == null || !AndroidUtilities.WEB_URL.matcher(url).matches()) {
                    return null;
                }
                url = "https://" + url;
                uri = Uri.parse(url);
            }
        } catch (Throwable ignored) {
            return null;
        }
        String scheme = uri.getScheme();
        if (scheme == null) return null;
        scheme = scheme.toLowerCase(Locale.US);
        if ("tg".equals(scheme)) return url;
        if (!"http".equals(scheme) && !"https".equals(scheme)
                && !"ton".equals(scheme) && !"tonsite".equals(scheme)) {
            return null;
        }
        return TextUtils.isEmpty(uri.getHost()) ? null : url;
    }

    private static final class UpdateContentScrollView extends NestedScrollView {
        private final int maxHeight;

        UpdateContentScrollView(Context context, boolean updateAvailable) {
            super(context);
            int reservedHeight = AndroidUtilities.dp(updateAvailable ? 176 : 116);
            maxHeight = Math.min(
                    AndroidUtilities.dp(640),
                    Math.max(AndroidUtilities.dp(120), AndroidUtilities.displaySize.y - reservedHeight));
            setOverScrollMode(OVER_SCROLL_IF_CONTENT_SCROLLS);
            setNestedScrollingEnabled(true);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int limit = maxHeight;
            if (MeasureSpec.getMode(heightMeasureSpec) != MeasureSpec.UNSPECIFIED) {
                limit = Math.min(limit, MeasureSpec.getSize(heightMeasureSpec));
            }
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST));
        }
    }

    private static Drawable createRowPressedBackground(
            Theme.ResourcesProvider resourcesProvider) {
        return Theme.createRadSelectorDrawable(
                Theme.getColor(Theme.key_listSelector, resourcesProvider), 12, 12);
    }

    @Override
    public void onDownloadComplete() {
        if (bindingDetached || downloadButton == null || downloadBindingToken == 0L) return;
        downloadFinished = true;
        downloadButtonClicked = true;
        downloadButton.setText(getString(R.string.UP_Install), true);
    }

    @Override
    public void onDownloadError() {
        if (bindingDetached || downloadBindingToken == 0L) return;
        downloadButtonClicked = false;
    }

    private StringBuilder getUpdateSizeString(NimarkoUpdater.Update update) {
        StringBuilder sb = new StringBuilder();
        sb.append(getString(R.string.AppUpdateDownloadNow));
        if (!TextUtils.isEmpty(update.size)) {
            sb.append(" (").append(update.size).append(")");
        }
        return sb;
    }

    private void copyText(CharSequence text) {
        AndroidUtilities.addToClipboard(text);
        BulletinFactory.of(getContainer(), resourcesProvider).createCopyBulletin(getString(R.string.TextCopied)).show();
    }

    public void setFragmentParams(BaseFragment fragment) {
        this.fragment = fragment;
        this.resourcesProvider = fragment.getResourceProvider();
    }

    public static void showAlert(BaseFragment fragment, boolean available, NimarkoUpdater.Update update) {
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.appUpdateAvailable);
        if (fragment == null || fragment.getParentActivity() == null || fragment.getContext() == null) {
            return;
        }
        if (available && update != null && !TextUtils.isEmpty(update.changelog)) {
            showWhenChangelogReady(fragment, update);
            return;
        }
        showPreparedAlert(fragment, available, update);
    }

    private static void showWhenChangelogReady(BaseFragment fragment, NimarkoUpdater.Update update) {
        String markdown = normalizeMarkdown(update.changelog);
        ChangelogStyle style = ChangelogStyle.from(fragment.getResourceProvider());
        ChangelogCacheKey key = new ChangelogCacheKey(markdown, style);
        if (CHANGELOG_CACHE.get(key) != null) {
            showPreparedAlert(fragment, true, update);
            return;
        }
        WeakReference<BaseFragment> fragmentRef = new WeakReference<>(fragment);
        Context context = fragment.getContext();
        Context appContext = context.getApplicationContext() != null ? context.getApplicationContext() : context;
        Utilities.globalQueue.postRunnable(() -> {
            formatChangelog(appContext, style, markdown);
            AndroidUtilities.runOnUIThread(() -> {
                BaseFragment current = fragmentRef.get();
                if (current == null || current.isFinished
                        || current.getParentActivity() == null || current.getContext() == null) {
                    return;
                }
                showWhenChangelogReady(current, update);
            });
        });
    }

    private static void showPreparedAlert(BaseFragment fragment, boolean available, NimarkoUpdater.Update update) {
        NimarkoUpdaterSheet alert = new NimarkoUpdaterSheet(fragment.getContext(), fragment.getResourceProvider(), available, update);
        alert.setFragmentParams(fragment);
        fragment.showDialog(alert);
    }
}
