package app.nimarkogram.messenger.plugins.ui.components;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.widget.AppCompatImageView;
import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaDataController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EffectsTextView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Switch;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.LaunchActivity;

import java.io.PrintWriter;
import java.io.StringWriter;

import app.nimarkogram.messenger.NimarkoConfig;
import app.nimarkogram.messenger.plugins.Plugin;
import app.nimarkogram.messenger.plugins.PluginsController;
import app.nimarkogram.messenger.utils.text.LocaleUtils;

@SuppressLint({"ViewConstructor"})
public class PluginCell extends FrameLayout implements NotificationCenter.NotificationCenterDelegate {
    public static final long NO_UI_OPERATION_EPOCH = 0L;

    private final Switch checkBox;
    private final EffectsTextView descriptionView;
    private final FrameLayout islandView;
    private final LinearLayout rowLayout;
    private final BackupImageView imageView;
    private final ImageView placeholderIcon;
    private final ImageView kebabButton;
    
    private final ProgressBar loadingSpinner;
    private Plugin plugin;
    private PluginCellDelegate pluginCellDelegate;
    private final TextView pluginNameView;
    private final EffectsTextView subtitleView;
    private final TextView requirementsView;
    private boolean compact;
    private long bindingEpoch;

    public PluginCell(Context context) {
        this(context, null);
    }

    public PluginCell(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);

        setClickable(false);
        setClipChildren(false);
        setClipToPadding(false);

        islandView = new FrameLayout(context);
        islandView.setBackground(Theme.createRoundRectDrawable(
                AndroidUtilities.dp(16.0f),
                Theme.getColor(Theme.key_windowBackgroundWhite)));
        addView(islandView, LayoutHelper.createFrame(-1, -2.0f, 0, 12.0f, 4.0f, 12.0f, 4.0f));

        LinearLayout islandStack = new LinearLayout(context);
        islandStack.setOrientation(LinearLayout.VERTICAL);
        islandView.addView(islandStack, LayoutHelper.createFrame(-1, -2.0f));

        rowLayout = new LinearLayout(context);
        rowLayout.setOrientation(LinearLayout.HORIZONTAL);
        rowLayout.setGravity(Gravity.CENTER_VERTICAL);
        rowLayout.setMinimumHeight(AndroidUtilities.dp(64));
        rowLayout.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(8),
                AndroidUtilities.dp(12), AndroidUtilities.dp(8));
        
        rowLayout.setClipChildren(false);
        rowLayout.setClipToPadding(false);
        islandStack.addView(rowLayout, LayoutHelper.createLinear(-1, -2));

        FrameLayout iconFrame = new FrameLayout(context);
        rowLayout.addView(iconFrame, LayoutHelper.createLinear(40, 40, Gravity.CENTER_VERTICAL,
                0, 0, 14, 0));

        placeholderIcon = new AppCompatImageView(context);
        placeholderIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        placeholderIcon.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.menu_intro));
        placeholderIcon.setColorFilter(new PorterDuffColorFilter(
                Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.MULTIPLY));
        placeholderIcon.setBackground(Theme.createCircleDrawable(AndroidUtilities.dp(40),
                Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText), 0.12f)));
        iconFrame.addView(placeholderIcon, LayoutHelper.createFrame(40, 40, Gravity.CENTER));

        imageView = new BackupImageView(context) {
            @Override
            @SuppressLint({"DrawAllocation"})
            public void onDraw(Canvas canvas) {
                Path path = new Path();
                float r = AndroidUtilities.dp(20.0f);
                path.addRoundRect(new RectF(0.0f, 0.0f, getWidth(), getHeight()), r, r, Path.Direction.CW);
                canvas.save();
                canvas.clipPath(path);
                super.onDraw(canvas);
            }
        };
        imageView.setRoundRadius(AndroidUtilities.dp(20.0f));
        
        imageView.getImageReceiver().setAutoRepeat(2);
        imageView.getImageReceiver().setAutoRepeatCount(-1);
        imageView.setVisibility(View.GONE);
        iconFrame.addView(imageView, LayoutHelper.createFrame(40, 40, Gravity.CENTER));

        LinearLayout textColumn = new LinearLayout(context);
        textColumn.setOrientation(LinearLayout.VERTICAL);
        textColumn.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams textColumnLp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f);
        textColumnLp.gravity = Gravity.CENTER_VERTICAL;
        rowLayout.addView(textColumn, textColumnLp);

        pluginNameView = new TextView(context);
        pluginNameView.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        pluginNameView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        pluginNameView.setTextSize(1, 16.0f);
        pluginNameView.setTypeface(AndroidUtilities.bold());
        pluginNameView.setEllipsize(TextUtils.TruncateAt.END);
        pluginNameView.setSingleLine(true);
        textColumn.addView(pluginNameView, LayoutHelper.createLinear(-1, -2));

        subtitleView = new EffectsTextView(context);
        subtitleView.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        subtitleView.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_REGULAR));
        subtitleView.setMovementMethod(new AndroidUtilities.LinkMovementMethodMy());
        subtitleView.setLinkTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteLinkText));
        subtitleView.setTextSize(1, 13.0f);
        subtitleView.setEllipsize(TextUtils.TruncateAt.END);
        subtitleView.setSingleLine(true);
        subtitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        textColumn.addView(subtitleView, LayoutHelper.createLinear(-1, -2, 0.0f, 2.0f, 0.0f, 0.0f));

        requirementsView = new TextView(context);
        requirementsView.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        requirementsView.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_REGULAR));
        requirementsView.setTextSize(1, 12.0f);
        requirementsView.setEllipsize(TextUtils.TruncateAt.END);
        requirementsView.setSingleLine(true);
        requirementsView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteValueText));
        requirementsView.setVisibility(View.GONE);
        textColumn.addView(requirementsView, LayoutHelper.createLinear(-1, -2, 0.0f, 2.0f, 0.0f, 0.0f));

        descriptionView = new EffectsTextView(context);
        descriptionView.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        descriptionView.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_REGULAR));
        descriptionView.setMovementMethod(new AndroidUtilities.LinkMovementMethodMy());
        descriptionView.setLinkTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteLinkText));
        descriptionView.setTextSize(1, 13.0f);
        descriptionView.setVisibility(View.GONE);
        descriptionView.setPadding(AndroidUtilities.dp(14), 0, AndroidUtilities.dp(12),
                AndroidUtilities.dp(8));
        islandStack.addView(descriptionView, LayoutHelper.createLinear(-1, -2));

        FrameLayout actionsFrame = new FrameLayout(context);
        rowLayout.addView(actionsFrame, LayoutHelper.createLinear(84, 40, Gravity.CENTER_VERTICAL));

        kebabButton = new AppCompatImageView(context);
        kebabButton.setScaleType(ImageView.ScaleType.CENTER);
        kebabButton.setImageDrawable(ContextCompat.getDrawable(context, R.drawable.ic_ab_other).mutate());
        kebabButton.setColorFilter(new PorterDuffColorFilter(
                Theme.getColor(Theme.key_windowBackgroundWhiteGrayIcon), PorterDuff.Mode.MULTIPLY));
        kebabButton.setBackground(Theme.createSelectorDrawable(
                Theme.getColor(Theme.key_dialogButtonSelector), 1, AndroidUtilities.dp(18.0f)));
        kebabButton.setContentDescription(LocaleController.getString(R.string.AccDescrMoreOptions));
        kebabButton.setOnClickListener(this::onKebabClicked);
        actionsFrame.addView(kebabButton, LayoutHelper.createFrame(40, 40,
                Gravity.START | Gravity.CENTER_VERTICAL));

        FrameLayout trailingSlot = new FrameLayout(context);
        actionsFrame.addView(trailingSlot, LayoutHelper.createFrame(39, 40,
                Gravity.END | Gravity.CENTER_VERTICAL));

        checkBox = new Switch(context, resourcesProvider);
        int trackKey = Theme.key_switchTrack;
        int trackCheckedKey = Theme.key_switchTrackChecked;
        int thumbKey = Theme.key_windowBackgroundWhite;
        checkBox.setColors(trackKey, trackCheckedKey, thumbKey, thumbKey);
        checkBox.setFocusable(true);
        checkBox.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        trailingSlot.addView(checkBox, LayoutHelper.createFrame(39, 40, Gravity.CENTER));

        loadingSpinner = new ProgressBar(context);
        loadingSpinner.setIndeterminate(true);
        loadingSpinner.setIndeterminateTintList(android.content.res.ColorStateList.valueOf(
                Theme.getColor(Theme.key_switchTrackChecked)));
        loadingSpinner.setVisibility(View.GONE);
        loadingSpinner.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        trailingSlot.addView(loadingSpinner, LayoutHelper.createFrame(24, 24, Gravity.CENTER));
    }

    public void setLoading(boolean loading) {
        loadingSpinner.setVisibility(loading ? View.VISIBLE : View.GONE);
        checkBox.setVisibility(loading ? View.INVISIBLE : View.VISIBLE);
        if (plugin != null) {
            loadingSpinner.setContentDescription(plugin.getName() + ", "
                    + LocaleController.getString(R.string.Loading));
        }
    }

    public boolean isLoading() {
        return loadingSpinner.getVisibility() == View.VISIBLE;
    }

    private void onKebabClicked(View view) {
        if (pluginCellDelegate != null) {
            pluginCellDelegate.showKebabMenu(view);
        }
    }

    public boolean isPointOnInteractive(float x, float y) {
        if (isInsideViewRelativeToSelf(kebabButton, x, y)) {
            return true;
        }
        
        if (loadingSpinner != null && loadingSpinner.getVisibility() == View.VISIBLE
                && isInsideViewRelativeToSelf(loadingSpinner, x, y)) {
            return true;
        }
        
        if (subtitleView != null && subtitleView.getVisibility() == View.VISIBLE
                && isPointOnUrlSpan(subtitleView, x, y)) {
            return true;
        }
        return checkBox != null && checkBox.getVisibility() == View.VISIBLE
                && isInsideViewRelativeToSelf(checkBox, x, y);
    }

    private boolean isPointOnUrlSpan(android.widget.TextView text, float x, float y) {
        if (text == null || text.getVisibility() != View.VISIBLE) {
            return false;
        }
        float left = 0f, top = 0f;
        View walker = text;
        while (walker != null && walker != this) {
            left += walker.getX();
            top += walker.getY();
            if (walker.getParent() instanceof View) {
                walker = (View) walker.getParent();
            } else {
                walker = null;
            }
        }
        float localX = x - left - text.getPaddingLeft();
        float localY = y - top - text.getPaddingTop();
        if (localX < 0 || localY < 0
                || localX > text.getWidth() - text.getPaddingLeft() - text.getPaddingRight()
                || localY > text.getHeight() - text.getPaddingTop() - text.getPaddingBottom()) {
            return false;
        }
        android.text.Layout layout = text.getLayout();
        CharSequence cs = text.getText();
        if (layout == null || !(cs instanceof android.text.Spanned)) {
            return false;
        }
        int line = layout.getLineForVertical((int) localY);
        int offset = layout.getOffsetForHorizontal(line, localX);
        
        if (localX > layout.getLineRight(line) || localX < layout.getLineLeft(line)) {
            return false;
        }
        android.text.style.URLSpan[] spans = ((android.text.Spanned) cs)
                .getSpans(offset, offset, android.text.style.URLSpan.class);
        return spans != null && spans.length > 0;
    }

    private boolean isInsideViewRelativeToSelf(View child, float x, float y) {
        if (child == null || child.getVisibility() != View.VISIBLE) {
            return false;
        }
        
        float left = 0f;
        float top = 0f;
        View walker = child;
        while (walker != null && walker != this) {
            left += walker.getX();
            top += walker.getY();
            if (walker.getParent() instanceof View) {
                walker = (View) walker.getParent();
            } else {
                walker = null;
            }
        }
        float right = left + child.getWidth();
        
        float slop = AndroidUtilities.dp(8);
        return x >= left - slop && x <= right + slop
                && y >= 0 && y <= getHeight();
    }

    public void setCompact(boolean z) {
        this.compact = z;
        
        rowLayout.setMinimumHeight(AndroidUtilities.dp(z ? 48 : 64));
        int vPad = AndroidUtilities.dp(z ? 4 : 8);
        rowLayout.setPadding(AndroidUtilities.dp(14), vPad,
                AndroidUtilities.dp(12), vPad);
        
        ViewGroup.LayoutParams iconLp = imageView.getLayoutParams();
        int iconSize = AndroidUtilities.dp(z ? 28 : 40);
        if (iconLp != null) {
            iconLp.width = iconSize;
            iconLp.height = iconSize;
            imageView.setLayoutParams(iconLp);
        }
        ViewGroup.LayoutParams placeholderLp = placeholderIcon.getLayoutParams();
        if (placeholderLp != null) {
            placeholderLp.width = iconSize;
            placeholderLp.height = iconSize;
            placeholderIcon.setLayoutParams(placeholderLp);
        }
        if (placeholderIcon.getParent() instanceof View) {
            ViewGroup.LayoutParams frameLp = ((View) placeholderIcon.getParent()).getLayoutParams();
            if (frameLp instanceof LinearLayout.LayoutParams) {
                frameLp.width = iconSize;
                frameLp.height = iconSize;
                ((View) placeholderIcon.getParent()).setLayoutParams(frameLp);
            }
        }
        placeholderIcon.setBackground(Theme.createCircleDrawable(iconSize,
                Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText), 0.12f)));
        imageView.setRoundRadius(iconSize / 2);
        
        subtitleView.setVisibility(z ? View.GONE : View.VISIBLE);
        rowLayout.requestLayout();
    }

    @Override
    protected void onMeasure(int i, int i2) {
        super.onMeasure(View.MeasureSpec.makeMeasureSpec(View.MeasureSpec.getSize(i), MeasureSpec.EXACTLY), i2);
    }

    public void set(Plugin plugin, final PluginCellDelegate pluginCellDelegate) {
        set(plugin, pluginCellDelegate, NO_UI_OPERATION_EPOCH);
    }

    public void set(
            Plugin plugin,
            final PluginCellDelegate pluginCellDelegate,
            long uiOperationEpoch) {
        if (plugin == null || pluginCellDelegate == null) {
            return;
        }
        bindingEpoch++;
        this.pluginCellDelegate = pluginCellDelegate;
        this.plugin = plugin;
        setPinned(PluginsController.isPluginPinned(plugin.getId()));
        
        setCompact(NimarkoConfig.pluginsCompactView);

        boolean hasImage = plugin.getPack() != null && plugin.getIndex() >= 0;
        this.imageView.setVisibility(hasImage ? View.VISIBLE : View.GONE);
        this.placeholderIcon.setVisibility(hasImage ? View.GONE : View.VISIBLE);
        if (hasImage) {
           MediaDataController.getInstance(UserConfig.selectedAccount).setPlaceholderImageByIndex(this.imageView, plugin.getPack(), plugin.getIndex(), "100_100");
           
           this.imageView.getImageReceiver().setAutoRepeat(2);
           this.imageView.getImageReceiver().setAutoRepeatCount(-1);
        } else {
            this.imageView.setImage((ImageLocation) null, (String) null, (Drawable) null, 0, (Object) null);
        }
        this.pluginNameView.setText(plugin.getName());
        this.kebabButton.setContentDescription(plugin.getName() + ", "
                + LocaleController.getString(R.string.AccDescrMoreOptions));

        SpannableStringBuilder sub = new SpannableStringBuilder("v")
                .append(plugin.getVersion())
                .append(" · ")
                .append(LocaleUtils.formatWithUsernames(plugin.getAuthor()));
        this.subtitleView.setText(sub);

        java.util.List<String> reqNames = plugin.getRequirementNames();
        if (!this.compact && reqNames != null && !reqNames.isEmpty()) {
            this.requirementsView.setText("⬢ " + TextUtils.join("  ·  ", reqNames));
            this.requirementsView.setVisibility(View.VISIBLE);
        } else {
            this.requirementsView.setVisibility(View.GONE);
        }

        if (plugin.hasError()) {
            bindErrorState();
        } else {
            bindNormalState();
        }
        PluginsController controller = PluginsController.getInstance();
        boolean requestedEnabled =
                controller.getRequestedPluginEnabled(plugin.getId());
        this.checkBox.setChecked(requestedEnabled, false);
        this.checkBox.setContentDescription(plugin.getName() + ", "
                + LocaleController.getString(
                requestedEnabled ? R.string.Disable : R.string.Enable));
        this.checkBox.setOnClickListener(view -> pluginCellDelegate.togglePlugin(this));
        
        setLoading(uiOperationEpoch != NO_UI_OPERATION_EPOCH
                || controller.isTogglingInProgress(plugin.getId())
                || controller.isEnablingInProgress(plugin.getId()));
    }

    private void bindErrorState() {
        this.descriptionView.setVisibility(View.VISIBLE);
        this.descriptionView.setText(this.plugin.getError().getLocalizedMessage()
                + "\n" + LocaleController.getString(R.string.NM_TapToReEnable));
        this.descriptionView.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
        this.descriptionView.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MONO));
        this.descriptionView.setTextSize(1, 12.0f);
        
        this.descriptionView.setOnClickListener(v -> pluginCellDelegate.togglePlugin(this));
        this.descriptionView.setOnLongClickListener(v -> { onErrorClicked(v); return true; });
        
        this.checkBox.setVisibility(View.VISIBLE);
    }

    private void onErrorClicked(View view) {
        if (AndroidUtilities.addToClipboard(stackTraceToString(this.plugin.getError()))) {
            BulletinFactory.of(LaunchActivity.getSafeLastFragment()).createCopyBulletin(LocaleController.getString(R.string.TextCopied)).show();
        }
    }

    public static String stackTraceToString(Throwable th) {
        StringWriter stringWriter = new StringWriter();
        th.printStackTrace(new PrintWriter(stringWriter));
        return stringWriter.toString();
    }

    private void bindNormalState() {
        
        this.descriptionView.setVisibility(View.GONE);
        this.descriptionView.setOnClickListener(null);
        this.descriptionView.setOnLongClickListener(null);
        this.checkBox.setVisibility(View.VISIBLE);
    }

    public void setChecked(boolean z, boolean z2) {
        this.checkBox.setChecked(z, z2);
    }

    public String getPluginId() {
        return plugin != null ? plugin.getId() : null;
    }

    public long getBindingEpoch() {
        return bindingEpoch;
    }

    public boolean isBoundTo(String pluginId, long expectedBindingEpoch) {
        return bindingEpoch == expectedBindingEpoch
                && plugin != null
                && TextUtils.equals(plugin.getId(), pluginId);
    }

    public void setPinned(boolean z) {
        
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.pluginSettingsRegistered);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.pluginSettingsUnregistered);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.pluginSettingsRegistered);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.pluginSettingsUnregistered);
    }

    @Override
    public void didReceivedNotification(int i, int i2, Object... objArr) {
        
    }

    public static class Factory extends UItem.UItemFactory {
        static {
            UItem.UItemFactory.setup(new Factory());
        }

        @Override
        public PluginCell createView(Context context, org.telegram.ui.Components.RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
            return new PluginCell(context, resourcesProvider);
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
            if (view instanceof PluginCell) {
                PluginCellDelegate delegate = (PluginCellDelegate) item.object;
                ((PluginCell) view).set(item.plugin, delegate, item.longValue);
            }
        }

        public static UItem as(Plugin plugin, PluginCellDelegate delegate) {
            return as(plugin, delegate, NO_UI_OPERATION_EPOCH);
        }

        public static UItem as(
                Plugin plugin,
                PluginCellDelegate delegate,
                long uiOperationEpoch) {
            UItem item = UItem.ofFactory(Factory.class);
            item.plugin = plugin;
            item.object = delegate;
            item.longValue = uiOperationEpoch;
            return item;
        }
    }
}
