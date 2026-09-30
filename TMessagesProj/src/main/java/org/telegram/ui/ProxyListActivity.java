/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui;

import static org.telegram.messenger.LocaleController.getString;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.DefaultItemAnimator;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DownloadController;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.ProxyRotationController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.utils.proxy.ProxySettings;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BackDrawable;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeDescription;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.ShadowSectionCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.Components.CheckBox2;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.NumberTextView;
import org.telegram.ui.Components.ProxyCheckStatusView;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.SlideChooseView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ProxyListActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {
    private final static boolean IS_PROXY_ROTATION_AVAILABLE = true;
    private static final int MENU_DELETE = 0;
    private static final int MENU_SHARE = 1;

    private ListAdapter listAdapter;
    private RecyclerListView listView;
    @SuppressWarnings("FieldCanBeLocal")
    private LinearLayoutManager layoutManager;

    private int currentConnectionState;

    private boolean useProxySettings;
    private boolean useProxyForCalls;

    private int rowCount;
    private int nimarkoVpnRow;
    private int nimarkoVlessRow;
    private int nimarkoVpnInfoRow;
    @Keep
    private int useProxyRow;
    private int useProxyShadowRow;
    private int connectionsHeaderRow;
    private int proxyStartRow;
    private int proxyEndRow;
    @Keep
    private int proxyAddRow;
    private int proxyShadowRow;
    @Keep
    private int callsRow = -1;
    private int callsDetailRow = -1;
    private int rotationRow;
    private int rotationTimeoutRow;
    private int rotationTimeoutInfoRow;
    private int deleteAllRow;

    private ItemTouchHelper itemTouchHelper;
    private NumberTextView selectedCountTextView;
    private ActionBarMenuItem shareMenuItem;
    private ActionBarMenuItem deleteMenuItem;

    private List<SharedConfig.ProxyInfo> selectedItems = new ArrayList<>();
    private List<SharedConfig.ProxyInfo> proxyList = new ArrayList<>();
    private boolean wasCheckedAllList;

    private boolean rowsUpdateInProgress;
    private boolean rowsUpdatePosted;
    private boolean rowsUpdatePending;
    private boolean rowsUpdatePendingNotify;
    private boolean fragmentDestroyed;
    private final Runnable rowsUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            rowsUpdatePosted = false;
            if (fragmentDestroyed || !rowsUpdatePending) {
                rowsUpdatePending = false;
                rowsUpdatePendingNotify = false;
                return;
            }
            if (listView != null
                    && listView.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) {
                return;
            }
            if (listView != null && listView.isComputingLayout()) {
                postPendingRowsUpdate();
                return;
            }

            boolean notify = rowsUpdatePendingNotify;
            rowsUpdatePending = false;
            rowsUpdatePendingNotify = false;
            updateRows(notify);
        }
    };

    public class TextDetailProxyCell extends FrameLayout {

        private TextView textView;
        private ProxyCheckStatusView valueTextView;
        private ImageView checkImageView;
        private SharedConfig.ProxyInfo currentInfo;
        private Drawable checkDrawable;

        private CheckBox2 checkBox;
        private boolean isSelected;
        private boolean isSelectionEnabled;

        private int color;
        private boolean statusInitialized;
        private ValueAnimator statusAnimator;
        private boolean checked;
        private boolean checkInitialized;
        private float checkProgress;
        private ValueAnimator checkAnimator;
        private float selectionProgress;
        private ValueAnimator selectionAnimator;

        public TextDetailProxyCell(Context context) {
            super(context);

            textView = new TextView(context);
            textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            textView.setLines(1);
            textView.setMaxLines(1);
            textView.setSingleLine(true);
            textView.setEllipsize(TextUtils.TruncateAt.END);
            textView.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
            addView(textView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, (LocaleController.isRTL ? 56 : 21), 10, (LocaleController.isRTL ? 21 : 56), 0));

            valueTextView = new ProxyCheckStatusView(context);
            valueTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            valueTextView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
            valueTextView.setLines(1);
            valueTextView.setMaxLines(1);
            valueTextView.setSingleLine(true);
            valueTextView.setCompoundDrawablePadding(AndroidUtilities.dp(6));
            valueTextView.setEllipsize(TextUtils.TruncateAt.END);
            valueTextView.setPadding(0, 0, 0, 0);
            addView(valueTextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP, (LocaleController.isRTL ? 56 : 21), 35, (LocaleController.isRTL ? 21 : 56), 0));

            checkImageView = new ImageView(context);
            checkImageView.setImageResource(R.drawable.msg_info);
            checkImageView.setColorFilter(new PorterDuffColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3), PorterDuff.Mode.MULTIPLY));
            checkImageView.setScaleType(ImageView.ScaleType.CENTER);
            checkImageView.setContentDescription(getString(R.string.Edit));
            addView(checkImageView, LayoutHelper.createFrame(48, 48, (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.TOP, 8, 8, 8, 0));
            checkImageView.setOnClickListener(v -> presentFragment(new ProxySettingsActivity(currentInfo)));

            checkBox = new CheckBox2(context, 21);
            checkBox.setColor(Theme.key_checkbox, Theme.key_radioBackground, Theme.key_checkboxCheck);
            checkBox.setDrawBackgroundAsArc(14);
            checkBox.setVisibility(GONE);
            addView(checkBox, LayoutHelper.createFrame(24, 24, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL, 16, 0, 8, 0));

            setWillNotDraw(false);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(64) + 1, MeasureSpec.EXACTLY));
        }

        public void setProxy(SharedConfig.ProxyInfo proxyInfo) {
            if (currentInfo != proxyInfo) {
                cancelStatusAnimator();
                cancelCheckAnimator();
                statusInitialized = false;
                checkInitialized = false;
                valueTextView.resetTransition();
            }
            String label;
            if (isOwnWsBypass(proxyInfo)) {
                label = LocaleController.getString(R.string.NM_WSB_Title);
            } else {
                label = proxyInfo.getSettings().getType() == ProxySettings.Type.WEB
                        ? proxyInfo.getSettings().getAddress() + " (WEB)"
                        : proxyInfo.getSettings().getAddress() + ":" + proxyInfo.getSettings().getPort();
            }
            textView.setText(label);
            currentInfo = proxyInfo;
            updateStatus();
        }

        public void updateStatus() {
            if (currentInfo == null) {
                return;
            }
            int colorKey;
            CharSequence value;
            if (SharedConfig.currentProxy == currentInfo && useProxySettings) {
                boolean transportConnected = currentConnectionState == ConnectionsManager.ConnectionStateConnected
                        || currentConnectionState == ConnectionsManager.ConnectionStateUpdating;
                if (isOwnWsBypass(currentInfo)) {
                    transportConnected = transportConnected && app.nimarkogram.messenger.wsbypass.NimarkoWsBypassController.STATE_RUNNING.equals(
                            app.nimarkogram.messenger.wsbypass.NimarkoWsBypassController.getInstance().getConnectionState());
                }
                if (transportConnected) {
                    colorKey = Theme.key_windowBackgroundWhiteBlueText6;
                    long ping = isOwnWsBypass(currentInfo)
                            ? ConnectionsManager.native_getCurrentMainPingTime(currentAccount) : currentInfo.ping;
                    if (ping > 0) {
                        value = getString(R.string.Connected) + ", " + LocaleController.formatString("Ping", R.string.Ping, ping);
                    } else {
                        value = getString(R.string.Connected);
                    }
                    if (!currentInfo.checking && !currentInfo.available) {
                        currentInfo.availableCheckTime = 0;
                    }
                } else {
                    colorKey = Theme.key_windowBackgroundWhiteGrayText2;
                    value = getString(R.string.Connecting);
                }
            } else {
                if (currentInfo.checking) {
                    value = getString(R.string.Checking);
                    colorKey = Theme.key_windowBackgroundWhiteGrayText2;
                } else if (currentInfo.available) {
                    if (!isOwnWsBypass(currentInfo) && currentInfo.ping != 0) {
                        value = getString(R.string.Available) + ", " + LocaleController.formatString("Ping", R.string.Ping, currentInfo.ping);
                    } else {
                        value = getString(R.string.Available);
                    }
                    colorKey = Theme.key_windowBackgroundWhiteGreenText;
                } else {
                    value = getString(R.string.Unavailable);
                    colorKey = Theme.key_text_RedRegular;
                }
            }
            if (!isAttachedToWindow() || !SharedConfig.animationsEnabled()) {
                valueTextView.resetTransition();
            }
            valueTextView.setStatusText(value, statusInitialized);
            valueTextView.setTag(colorKey);
            final int targetColor = Theme.getColor(colorKey);
            if (statusInitialized && isAttachedToWindow() && SharedConfig.animationsEnabled()
                    && color == targetColor
                    && (statusAnimator != null || valueTextView.getCurrentTextColor() == targetColor)) {
                return;
            }
            cancelStatusAnimator();
            color = targetColor;
            if (!statusInitialized || !isAttachedToWindow() || !SharedConfig.animationsEnabled()) {
                statusInitialized = true;
                applyStatusColor(targetColor);
                return;
            }
            final int fromColor = valueTextView.getCurrentTextColor();
            statusAnimator = ValueAnimator.ofFloat(0f, 1f);
            statusAnimator.setDuration(200);
            statusAnimator.setInterpolator(CubicBezierInterpolator.DEFAULT);
            statusAnimator.addUpdateListener(animation -> applyStatusColor(ColorUtils.blendARGB(fromColor, Theme.getColor(colorKey), (float) animation.getAnimatedValue())));
            statusAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (statusAnimator == animation) {
                        statusAnimator = null;
                        color = Theme.getColor(colorKey);
                        applyStatusColor(color);
                    }
                }
            });
            statusAnimator.start();
        }
        private void applyStatusColor(int value) {
            valueTextView.setTextColor(value);
            if (checkDrawable != null) {
                checkDrawable.setColorFilter(new PorterDuffColorFilter(value, PorterDuff.Mode.MULTIPLY));
            }
        }
        private void cancelStatusAnimator() {
            if (statusAnimator != null) {
                ValueAnimator animator = statusAnimator;
                statusAnimator = null;
                animator.cancel();
            }
        }

        public void setSelectionEnabled(boolean enabled, boolean animated) {
            if (isSelectionEnabled == enabled && isAttachedToWindow() && SharedConfig.animationsEnabled()
                    && (animated || selectionAnimator != null)) {
                return;
            }
            isSelectionEnabled = enabled;
            cancelSelectionAnimator();
            if (!animated || !isAttachedToWindow() || !SharedConfig.animationsEnabled()) {
                applySelectionProgress(enabled ? 1f : 0f);
                checkImageView.setVisibility(enabled ? GONE : VISIBLE);
                checkBox.setVisibility(enabled ? VISIBLE : GONE);
            } else {
                checkBox.setVisibility(VISIBLE);
                checkImageView.setVisibility(VISIBLE);
                ValueAnimator animator = ValueAnimator.ofFloat(selectionProgress, enabled ? 1f : 0f).setDuration(200);
                selectionAnimator = animator;
                animator.setInterpolator(CubicBezierInterpolator.DEFAULT);
                animator.addUpdateListener(animation -> applySelectionProgress((float) animation.getAnimatedValue()));
                animator.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        if (selectionAnimator != animation) {
                            return;
                        }
                        selectionAnimator = null;
                        if (enabled) {
                            checkImageView.setVisibility(GONE);
                        } else {
                            checkBox.setVisibility(GONE);
                        }
                    }
                });
                animator.start();
            }
        }

        private void cancelSelectionAnimator() {
            if (selectionAnimator != null) {
                ValueAnimator animator = selectionAnimator;
                selectionAnimator = null;
                animator.cancel();
            }
        }
        private void applySelectionProgress(float progress) {
            selectionProgress = progress;
            float x = (LocaleController.isRTL ? -AndroidUtilities.dp(32) : AndroidUtilities.dp(32)) * progress;
            textView.setTranslationX(x);
            valueTextView.setTranslationX(x);
            checkImageView.setTranslationX(x);
            checkBox.setTranslationX((LocaleController.isRTL ? AndroidUtilities.dp(32) : -AndroidUtilities.dp(32)) + x);
            checkBox.setScaleX(0.5f + progress * 0.5f);
            checkBox.setScaleY(0.5f + progress * 0.5f);
            checkBox.setAlpha(progress);
            checkImageView.setScaleX(1f - progress * 0.5f);
            checkImageView.setScaleY(1f - progress * 0.5f);
            checkImageView.setAlpha(1f - progress);
        }
        public void setItemSelected(boolean selected, boolean animated) {
            if (selected == isSelected) {
                return;
            }
            isSelected = selected;
            checkBox.setChecked(selected, animated);
        }

        public void setChecked(boolean checked) {
            if (checkInitialized && this.checked == checked
                    && isAttachedToWindow() && SharedConfig.animationsEnabled()) {
                return;
            }
            cancelCheckAnimator();
            this.checked = checked;
            if (!checkInitialized || !isAttachedToWindow() || !SharedConfig.animationsEnabled()) {
                checkInitialized = true;
                applyCheckProgress(checked ? 1f : 0f);
                return;
            }
            checkAnimator = ValueAnimator.ofFloat(checkProgress, checked ? 1f : 0f);
            checkAnimator.setDuration(200);
            checkAnimator.setInterpolator(CubicBezierInterpolator.DEFAULT);
            checkAnimator.addUpdateListener(animation -> applyCheckProgress((float) animation.getAnimatedValue()));
            checkAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (checkAnimator == animation) {
                        checkAnimator = null;
                        applyCheckProgress(TextDetailProxyCell.this.checked ? 1f : 0f);
                    }
                }
            });
            checkAnimator.start();
        }
        private void cancelCheckAnimator() {
            if (checkAnimator != null) {
                ValueAnimator animator = checkAnimator;
                checkAnimator = null;
                animator.cancel();
            }
        }
        private void applyCheckProgress(float progress) {
            checkProgress = progress;
            valueTextView.setCompoundDrawablePadding(Math.round(AndroidUtilities.dp(6) * progress));
            if (progress <= 0f) {
                valueTextView.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null);
                return;
            }
            if (checkDrawable == null) {
                final Drawable icon = getResources().getDrawable(R.drawable.proxy_check).mutate();
                icon.setBounds(0, 0, icon.getIntrinsicWidth(), icon.getIntrinsicHeight());
                checkDrawable = new Drawable() {
                    @Override
                    public void draw(@NonNull Canvas canvas) {
                        int save = canvas.save();
                        canvas.translate(getBounds().left, getBounds().top);
                        canvas.scale(checkProgress, checkProgress, 0f, getIntrinsicHeight() / 2f);
                        icon.setAlpha(Math.round(255 * checkProgress));
                        icon.draw(canvas);
                        canvas.restoreToCount(save);
                    }
                    @Override
                    public void setAlpha(int alpha) {
                        icon.setAlpha(alpha);
                    }
                    @Override
                    public void setColorFilter(ColorFilter colorFilter) {
                        icon.setColorFilter(colorFilter);
                        invalidateSelf();
                    }
                    @Override
                    public int getOpacity() {
                        return PixelFormat.TRANSLUCENT;
                    }
                    @Override
                    public int getIntrinsicWidth() {
                        return icon.getIntrinsicWidth();
                    }
                    @Override
                    public int getIntrinsicHeight() {
                        return icon.getIntrinsicHeight();
                    }
                };
            }
            checkDrawable.setColorFilter(new PorterDuffColorFilter(valueTextView.getCurrentTextColor(), PorterDuff.Mode.MULTIPLY));
            checkDrawable.setBounds(0, 0, Math.round(checkDrawable.getIntrinsicWidth() * progress), checkDrawable.getIntrinsicHeight());
            if (LocaleController.isRTL) {
                valueTextView.setCompoundDrawables(null, null, checkDrawable, null);
            } else {
                valueTextView.setCompoundDrawables(checkDrawable, null, null, null);
            }
        }

        public void setValue(CharSequence value) {
            valueTextView.setStatusText(value, statusInitialized);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            updateStatus();
        }

        @Override
        protected void onDetachedFromWindow() {
            cancelStatusAnimator();
            cancelCheckAnimator();
            cancelSelectionAnimator();
            valueTextView.resetTransition();
            if (statusInitialized) {
                applyStatusColor(color);
            }
            applyCheckProgress(checked ? 1f : 0f);
            applySelectionProgress(isSelectionEnabled ? 1f : 0f);
            checkImageView.setVisibility(isSelectionEnabled ? GONE : VISIBLE);
            checkBox.setVisibility(isSelectionEnabled ? VISIBLE : GONE);
            super.onDetachedFromWindow();
        }
        @Override
        protected void onDraw(Canvas canvas) {
            canvas.drawLine(LocaleController.isRTL ? 0 : AndroidUtilities.dp(20), getMeasuredHeight() - 1, getMeasuredWidth() - (LocaleController.isRTL ? AndroidUtilities.dp(20) : 0), getMeasuredHeight() - 1, Theme.dividerPaint);
        }
    }

    @Override
    public boolean onFragmentCreate() {
        fragmentDestroyed = false;
        super.onFragmentCreate();

        SharedConfig.loadProxyList();
        currentConnectionState = ConnectionsManager.getInstance(currentAccount).getConnectionState();

        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.proxyChangedByRotation);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.proxySettingsChanged);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.proxyCheckDone);
        NotificationCenter.getInstance(currentAccount).addObserver(this, NotificationCenter.didUpdateConnectionState);

        reconcileProxyState();

        updateRows(true);

        return true;
    }

    @Override
    public void onFragmentDestroy() {
        fragmentDestroyed = true;
        rowsUpdatePending = false;
        rowsUpdatePendingNotify = false;
        rowsUpdatePosted = false;
        AndroidUtilities.cancelRunOnUIThread(rowsUpdateRunnable);
        super.onFragmentDestroy();
        AndroidUtilities.cancelRunOnUIThread(ownBypassPingPoll);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.proxyChangedByRotation);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.proxySettingsChanged);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.proxyCheckDone);
        NotificationCenter.getInstance(currentAccount).removeObserver(this, NotificationCenter.didUpdateConnectionState);
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonDrawable(new BackDrawable(false));
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(getString(R.string.ProxySettings));
        if (parentLayout != null && parentLayout.isLayersLayout()) {
            actionBar.setOccupyStatusBar(false);
        }
        actionBar.setAllowOverlayTitle(false);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        listAdapter = new ListAdapter(context);

        fragmentView = new FrameLayout(context);
        fragmentView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        FrameLayout frameLayout = (FrameLayout) fragmentView;

        listView = new RecyclerListView(context);
        listView.setSections();
        actionBar.setAdaptiveBackground(listView);
        ((DefaultItemAnimator) listView.getItemAnimator()).setDelayAnimations(false);
        ((DefaultItemAnimator) listView.getItemAnimator()).setTranslationInterpolator(CubicBezierInterpolator.DEFAULT);
        listView.setVerticalScrollBarEnabled(false);
        listView.setLayoutManager(layoutManager = new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        frameLayout.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.TOP | Gravity.LEFT));
        listView.setAdapter(listAdapter);
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                if (newState == RecyclerView.SCROLL_STATE_IDLE && rowsUpdatePending) {
                    postPendingRowsUpdate();
                }
            }
        });
        listView.setOnItemClickListener((view, position) -> {
            if (position == nimarkoVpnRow) {
                org.telegram.messenger.browser.Browser.openUrl(getParentActivity(), "https://t.me/NimarkoVPN_Bot");
                return;
            }
            if (position == nimarkoVlessRow) {
                org.telegram.messenger.browser.Browser.openUrl(getParentActivity(), "https://t.me/NimarkoVlessBot");
                return;
            }
            if (position == useProxyRow) {
                if (useProxySettings && isOwnWsBypass(SharedConfig.currentProxy)) {
                    app.nimarkogram.messenger.wsbypass.NimarkoWsBypassController.getInstance().setEnabled(false);
                    reconcileProxyState();
                    updateRows(true);
                    return;
                }
                if (SharedConfig.currentProxy == null) {
                    if (!proxyList.isEmpty()) {
                        SharedConfig.currentProxy = proxyList.get(0);

                        if (!useProxySettings) {
                            SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
                            SharedConfig.currentProxy.getSettings().toSharedPreferences(editor);
                            editor.commit();
                        }
                    } else {
                        presentFragment(new ProxySettingsActivity());
                        return;
                    }
                }
                useProxySettings = !useProxySettings;

                TextCheckCell textCheckCell = (TextCheckCell) view;
                textCheckCell.setChecked(useProxySettings);

                SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
                editor.putBoolean("proxy_enabled", useProxySettings);
                editor.commit();
                reconcileProxyState();
                updateRows(true);

                ConnectionsManager.setProxySettings(useProxySettings, SharedConfig.currentProxy.getSettings());
                NotificationCenter.getGlobalInstance().removeObserver(ProxyListActivity.this, NotificationCenter.proxySettingsChanged);
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
                NotificationCenter.getGlobalInstance().addObserver(ProxyListActivity.this, NotificationCenter.proxySettingsChanged);

                for (int a = proxyStartRow; a < proxyEndRow; a++) {
                    RecyclerListView.Holder holder = (RecyclerListView.Holder) listView.findViewHolderForAdapterPosition(a);
                    if (holder != null) {
                        TextDetailProxyCell cell = (TextDetailProxyCell) holder.itemView;
                        cell.updateStatus();
                    }
                }
            } else if (position == rotationRow) {
                SharedConfig.proxyRotationEnabled = !SharedConfig.proxyRotationEnabled;
                TextCheckCell textCheckCell = (TextCheckCell) view;
                textCheckCell.setChecked(SharedConfig.proxyRotationEnabled);
                SharedConfig.saveConfig();

                updateRows(true);
            } else if (position == callsRow) {
                reconcileProxyState();
                if (!canUseProxyForCalls()) {
                    updateRows(true);
                    return;
                }
                useProxyForCalls = !useProxyForCalls;
                MessagesController.getGlobalMainSettings().edit()
                        .putBoolean("proxy_enabled_calls", useProxyForCalls).apply();
                ((TextCheckCell) view).setChecked(useProxyForCalls);
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
            } else if (position >= proxyStartRow && position < proxyEndRow) {
                if (!selectedItems.isEmpty()) {
                    listAdapter.toggleSelected(position);
                    return;
                }
                SharedConfig.ProxyInfo info = proxyList.get(position - proxyStartRow);
                if (!isOwnWsBypass(info) && app.nimarkogram.messenger.wsbypass.NimarkoWsBypassConfig.enabled) {
                    app.nimarkogram.messenger.wsbypass.NimarkoWsBypassController.getInstance().setEnabled(false);
                }
                useProxySettings = true;
                SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
                info.getSettings().toSharedPreferences(editor);
                editor.putBoolean("proxy_enabled", useProxySettings);
                if (info.getSettings().getType() != ProxySettings.Type.SOCKS5) {
                    useProxyForCalls = false;
                    editor.putBoolean("proxy_enabled_calls", false);
                }
                editor.commit();
                SharedConfig.currentProxy = info;
                reconcileProxyState();
                for (int a = proxyStartRow; a < proxyEndRow; a++) {
                    RecyclerListView.Holder holder = (RecyclerListView.Holder) listView.findViewHolderForAdapterPosition(a);
                    if (holder != null) {
                        TextDetailProxyCell cell = (TextDetailProxyCell) holder.itemView;
                        cell.setChecked(cell.currentInfo == info);
                        cell.updateStatus();
                    }
                }
                updateRows(true);
                RecyclerListView.Holder holder = (RecyclerListView.Holder) listView.findViewHolderForAdapterPosition(useProxyRow);
                if (holder != null) {
                    TextCheckCell textCheckCell = (TextCheckCell) holder.itemView;
                    textCheckCell.setChecked(true);
                }
                ConnectionsManager.setProxySettings(useProxySettings, SharedConfig.currentProxy.getSettings());
            } else if (position == proxyAddRow) {
                presentFragment(new ProxySettingsActivity());
            } else if (position == deleteAllRow) {
                AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
                builder.setMessage(getString(R.string.DeleteAllProxiesConfirm));
                builder.setNegativeButton(getString(R.string.Cancel), null);
                builder.setTitle(getString(R.string.DeleteProxyTitle));
                builder.setPositiveButton(getString(R.string.Delete), (dialog, which) -> {
                    for (SharedConfig.ProxyInfo info : new ArrayList<>(proxyList)) {
                        if (isOwnWsBypass(info)) {
                            continue;
                        }
                        SharedConfig.deleteProxy(info);
                    }
                    reconcileProxyState();
                    NotificationCenter.getGlobalInstance().removeObserver(ProxyListActivity.this, NotificationCenter.proxySettingsChanged);
                    NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
                    NotificationCenter.getGlobalInstance().addObserver(ProxyListActivity.this, NotificationCenter.proxySettingsChanged);
                    updateRows(true);
                    if (listAdapter != null) {
                        listAdapter.notifyItemChanged(useProxyRow, ListAdapter.PAYLOAD_CHECKED_CHANGED);
                        listAdapter.clearSelected();
                    }
                });
                AlertDialog dialog = builder.create();
                showDialog(dialog);
                TextView button = (TextView) dialog.getButton(DialogInterface.BUTTON_POSITIVE);
                if (button != null) {
                    button.setTextColor(Theme.getColor(Theme.key_text_RedBold));
                }
            }
        });
        listView.setOnItemLongClickListener((view, position) -> {
            if (position >= proxyStartRow && position < proxyEndRow) {
                listAdapter.toggleSelected(position);
                return true;
            }
            return false;
        });

        ActionBarMenu actionMode = actionBar.createActionMode();
        selectedCountTextView = new NumberTextView(actionMode.getContext());
        selectedCountTextView.setTextSize(18);
        selectedCountTextView.setTypeface(AndroidUtilities.bold());
        selectedCountTextView.setTextColor(Theme.getColor(Theme.key_actionBarActionModeDefaultIcon));
        actionMode.addView(selectedCountTextView, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1.0f, 72, 0, 0, 0));
        selectedCountTextView.setOnTouchListener((v, event) -> true);

        shareMenuItem = actionMode.addItemWithWidth(MENU_SHARE, R.drawable.msg_share, AndroidUtilities.dp(54));
        shareMenuItem.setContentDescription(getString(R.string.StickersShare));
        deleteMenuItem = actionMode.addItemWithWidth(MENU_DELETE, R.drawable.msg_delete, AndroidUtilities.dp(54));
        deleteMenuItem.setContentDescription(getString(R.string.Delete));

        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                switch (id) {
                    case -1:
                        if (selectedItems.isEmpty()) {
                            finishFragment();
                        } else {
                            listAdapter.clearSelected();
                        }
                        break;
                    case MENU_DELETE:
                        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
                        builder.setMessage(getString(selectedItems.size() > 1 ? R.string.DeleteProxyMultiConfirm : R.string.DeleteProxyConfirm));
                        builder.setNegativeButton(getString(R.string.Cancel), null);
                        builder.setTitle(getString(R.string.DeleteProxyTitle));
                        builder.setPositiveButton(getString(R.string.Delete), (dialog, which) -> {
                            for (SharedConfig.ProxyInfo info : selectedItems) {
                                if (isOwnWsBypass(info)) {
                                    continue;
                                }
                                SharedConfig.deleteProxy(info);
                            }
                            reconcileProxyState();
                            NotificationCenter.getGlobalInstance().removeObserver(ProxyListActivity.this, NotificationCenter.proxySettingsChanged);
                            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
                            NotificationCenter.getGlobalInstance().addObserver(ProxyListActivity.this, NotificationCenter.proxySettingsChanged);
                            updateRows(true);
                            if (listAdapter != null) {
                                if (SharedConfig.currentProxy == null) {
                                    listAdapter.notifyItemChanged(useProxyRow, ListAdapter.PAYLOAD_CHECKED_CHANGED);
                                }
                                listAdapter.clearSelected();
                            }
                        });
                        AlertDialog dialog = builder.create();
                        showDialog(dialog);
                        TextView button = (TextView) dialog.getButton(DialogInterface.BUTTON_POSITIVE);
                        if (button != null) {
                            button.setTextColor(Theme.getColor(Theme.key_text_RedBold));
                        }
                        break;
                    case MENU_SHARE:
                        StringBuilder links = new StringBuilder();
                        for (SharedConfig.ProxyInfo info : selectedItems) {
                            if (links.length() > 0) {
                                links.append("\n\n");
                            }
                            links.append(info.getSettings().getLink());
                        }

                        Intent shareIntent = new Intent(Intent.ACTION_SEND);
                        shareIntent.setType("text/plain");
                        shareIntent.putExtra(Intent.EXTRA_TEXT, links.toString());
                        Intent chooserIntent = Intent.createChooser(shareIntent, getString(selectedItems.size() > 1 ? R.string.ShareLinks : R.string.ShareLink));
                        chooserIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        context.startActivity(chooserIntent);

                        if (listAdapter != null) {
                            listAdapter.clearSelected();
                        }
                        break;
                }
            }
        });

        return fragmentView;
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (!selectedItems.isEmpty()) {
            if (invoked) listAdapter.clearSelected();
            return false;
        }
        return super.onBackPressed(invoked);
    }

    private void requestRowsUpdate(boolean notify) {
        if (fragmentDestroyed) {
            return;
        }
        rowsUpdatePending = true;
        rowsUpdatePendingNotify |= notify;
        postPendingRowsUpdate();
    }

    private void postPendingRowsUpdate() {
        if (fragmentDestroyed || rowsUpdatePosted) {
            return;
        }
        if (listView != null
                && listView.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) {
            return;
        }
        rowsUpdatePosted = true;
        AndroidUtilities.runOnUIThread(rowsUpdateRunnable);
    }

    private void updateRows(boolean notify) {
        if (fragmentDestroyed) {
            return;
        }
        if (rowsUpdateInProgress
                || (listView != null && (listView.isComputingLayout()
                || listView.getScrollState() != RecyclerView.SCROLL_STATE_IDLE))) {
            requestRowsUpdate(notify);
            return;
        }

        rowsUpdateInProgress = true;
        try {
            updateRowsInternal(notify);
        } finally {
            rowsUpdateInProgress = false;
        }
    }

    private void updateRowsInternal(boolean notify) {
        rowCount = 0;
        nimarkoVpnRow = rowCount++;
        nimarkoVlessRow = rowCount++;
        nimarkoVpnInfoRow = rowCount++;
        useProxyRow = rowCount++;
        if (useProxySettings && SharedConfig.currentProxy != null && SharedConfig.currentProxy.getSettings().getType() != ProxySettings.Type.WEB && SharedConfig.proxyList.size() > 1 && IS_PROXY_ROTATION_AVAILABLE) {
            rotationRow = rowCount++;
            if (SharedConfig.proxyRotationEnabled) {
                rotationTimeoutRow = rowCount++;
                rotationTimeoutInfoRow = rowCount++;
            } else {
                rotationTimeoutRow = -1;
                rotationTimeoutInfoRow = -1;
            }
        } else {
            rotationRow = -1;
            rotationTimeoutRow = -1;
            rotationTimeoutInfoRow = -1;
        }
        if (rotationTimeoutInfoRow == -1) {
            useProxyShadowRow = rowCount++;
        } else {
            useProxyShadowRow = -1;
        }
        connectionsHeaderRow = rowCount++;

        if (notify) {
            proxyList.clear();
            proxyList.addAll(SharedConfig.proxyList);

            boolean checking = false;
            if (!wasCheckedAllList) {
                for (SharedConfig.ProxyInfo info : proxyList) {
                    if (info.checking || info.availableCheckTime == 0) {
                        checking = true;
                        break;
                    }
                }
                if (!checking) {
                    wasCheckedAllList = true;
                }
            }

            boolean isChecking = checking;
            Collections.sort(proxyList, (o1, o2) -> {
                long bias1 = SharedConfig.currentProxy == o1 ? -200000 : 0;
                if (!o1.available) {
                    bias1 += 100000;
                }
                long bias2 = SharedConfig.currentProxy == o2 ? -200000 : 0;
                if (!o2.available) {
                    bias2 += 100000;
                }
                return Long.compare(isChecking && o1 != SharedConfig.currentProxy ? SharedConfig.proxyList.indexOf(o1) * 10000L : o1.ping + bias1,
                        isChecking && o2 != SharedConfig.currentProxy ? SharedConfig.proxyList.indexOf(o2) * 10000L : o2.ping + bias2);
            });
        }

        if (!proxyList.isEmpty()) {
            proxyStartRow = rowCount;
            rowCount += proxyList.size();
            proxyEndRow = rowCount;
        } else {
            proxyStartRow = -1;
            proxyEndRow = -1;
        }
        proxyAddRow = rowCount++;
        proxyShadowRow = rowCount++;
        if (SharedConfig.currentProxy == null || SharedConfig.currentProxy.getSettings().getType() == ProxySettings.Type.SOCKS5) {
            boolean change = callsRow == -1;
            callsRow = rowCount++;
            callsDetailRow = rowCount++;
            if (!notify && change && listAdapter != null) {
                listAdapter.notifyItemChanged(proxyShadowRow);
                listAdapter.notifyItemRangeInserted(proxyShadowRow + 1, 2);
            }
        } else {
            boolean change = callsRow != -1;
            callsRow = -1;
            callsDetailRow = -1;
            if (!notify && change && listAdapter != null) {
                listAdapter.notifyItemChanged(proxyShadowRow);
                listAdapter.notifyItemRangeRemoved(proxyShadowRow + 1, 2);
            }
        }
        if (proxyList.size() >= 10) {
            deleteAllRow = rowCount++;
        } else {
            deleteAllRow = -1;
        }
        checkProxyList();
        if (notify && listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    private void checkProxyList() {
        for (int a = 0, count = proxyList.size(); a < count; a++) {
            final SharedConfig.ProxyInfo proxyInfo = proxyList.get(a);
            if (proxyInfo.checking || SystemClock.elapsedRealtime() - proxyInfo.availableCheckTime < 2 * 60 * 1000) {
                continue;
            }
            if (isOwnWsBypass(proxyInfo)) {
                proxyInfo.checking = false;
                app.nimarkogram.messenger.wsbypass.WsBypassCore core =
                        app.nimarkogram.messenger.wsbypass.WsBypassCore.getInstance();
                proxyInfo.available = core.isRunning() && core.isAcceptThreadAlive();
                proxyInfo.availableCheckTime = SystemClock.elapsedRealtime();
                if (!proxyInfo.available) {
                    proxyInfo.ping = 0;
                }
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxyCheckDone, proxyInfo);
                continue;
            }
            proxyInfo.checking = true;
            ConnectionsManager.getInstance(currentAccount).checkProxy(proxyInfo.getSettings(), time -> AndroidUtilities.runOnUIThread(() -> {
                proxyInfo.availableCheckTime = SystemClock.elapsedRealtime();
                proxyInfo.checking = false;
                if (time == -1) {
                    proxyInfo.available = false;
                    proxyInfo.ping = 0;
                } else {
                    proxyInfo.ping = time;
                    proxyInfo.available = true;
                }
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxyCheckDone, proxyInfo);
            }));
        }
    }

    private static boolean isOwnWsBypass(SharedConfig.ProxyInfo p) {
        return p != null
                && app.nimarkogram.messenger.wsbypass.WsBypassCore.LOCAL_PROXY_HOST.equals(p.getSettings().getAddress())
                && p.getSettings().getPort() == app.nimarkogram.messenger.wsbypass.NimarkoWsBypassConfig.localPort;
    }

    @Override
    protected void onDialogDismiss(Dialog dialog) {
        DownloadController.getInstance(currentAccount).checkAutodownloadSettings();
    }

    @Override
    public void onResume() {
        super.onResume();
        reconcileProxyState();
        updateRows(true);
        scheduleOwnBypassPing();
    }

    @Override
    public void onPause() {
        super.onPause();
        AndroidUtilities.cancelRunOnUIThread(ownBypassPingPoll);
        ownBypassPingScheduled = false;
    }

    private boolean ownBypassPingScheduled;
    private int lastOwnBypassPing = -1;
    private final Runnable ownBypassPingPoll = () -> {
        ownBypassPingScheduled = false;
        refreshOwnBypassPing();
        scheduleOwnBypassPing();
    };

    private void scheduleOwnBypassPing() {
        if (ownBypassPingScheduled) return;
        ownBypassPingScheduled = true;
        AndroidUtilities.runOnUIThread(ownBypassPingPoll, 2000);
    }

    private void refreshOwnBypassPing() {
        try {
            if (listView == null || SharedConfig.currentProxy == null || !useProxySettings) return;
            if (!isOwnWsBypass(SharedConfig.currentProxy)) return;
            if (currentConnectionState != ConnectionsManager.ConnectionStateConnected
                    && currentConnectionState != ConnectionsManager.ConnectionStateUpdating) return;
            int live = ConnectionsManager.native_getCurrentMainPingTime(currentAccount);
            SharedConfig.ProxyInfo info = SharedConfig.currentProxy;
            info.available = true;
            if (live == lastOwnBypassPing) return;
            lastOwnBypassPing = live;
            for (int a = proxyStartRow; a < proxyEndRow; a++) {
                RecyclerListView.Holder holder = (RecyclerListView.Holder) listView.findViewHolderForAdapterPosition(a);
                if (holder != null && holder.itemView instanceof TextDetailProxyCell) {
                    TextDetailProxyCell cell = (TextDetailProxyCell) holder.itemView;
                    if (cell.currentInfo == info) cell.updateStatus();
                }
            }
        } catch (Throwable ignored) {}
    }

    private void reconcileProxyState() {
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        useProxySettings = preferences.getBoolean("proxy_enabled", false)
                && SharedConfig.currentProxy != null && !SharedConfig.proxyList.isEmpty();
        useProxyForCalls = preferences.getBoolean("proxy_enabled_calls", false);
        if (!canUseProxyForCalls()) {
            useProxyForCalls = false;
        }
    }
    private boolean canUseProxyForCalls() {
        SharedConfig.ProxyInfo current = SharedConfig.currentProxy;
        if (!useProxySettings || current == null) {
            return false;
        }
        ProxySettings settings = current.getSettings();
        return settings.getType() == ProxySettings.Type.SOCKS5 && settings.isValid();
    }
    private static String callsString(String key, String fallback) {
        String value = LocaleController.getString(key);
        return TextUtils.isEmpty(value) || value.startsWith("LOC_ERR") ? fallback : value;
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.proxyChangedByRotation) {
            if (listView != null) {
                listView.forAllChild(view -> {
                    RecyclerView.ViewHolder holder = listView.getChildViewHolder(view);
                    if (holder.itemView instanceof TextDetailProxyCell) {
                        TextDetailProxyCell cell = (TextDetailProxyCell) holder.itemView;
                        cell.setChecked(cell.currentInfo == SharedConfig.currentProxy);
                        cell.updateStatus();
                    }
                });
            }

            updateRows(false);
        } else if (id == NotificationCenter.proxySettingsChanged) {
            reconcileProxyState();
            updateRows(true);
        } else if (id == NotificationCenter.didUpdateConnectionState) {
            int state = ConnectionsManager.getInstance(account).getConnectionState();
            if (currentConnectionState != state) {
                currentConnectionState = state;
                if (listView != null && SharedConfig.currentProxy != null) {
                    int idx = proxyList.indexOf(SharedConfig.currentProxy);
                    if (idx >= 0) {
                        RecyclerListView.Holder holder = (RecyclerListView.Holder) listView.findViewHolderForAdapterPosition(idx + proxyStartRow);
                        if (holder != null && holder.itemView instanceof TextDetailProxyCell) {
                            ((TextDetailProxyCell) holder.itemView).updateStatus();
                        }
                    }

                    if (currentConnectionState == ConnectionsManager.ConnectionStateConnected) {
                        updateRows(true);
                    }
                }
            }
        } else if (id == NotificationCenter.proxyCheckDone) {
            if (listView != null) {
                SharedConfig.ProxyInfo proxyInfo = (SharedConfig.ProxyInfo) args[0];
                int idx = proxyList.indexOf(proxyInfo);
                if (idx >= 0) {
                    RecyclerListView.Holder holder = (RecyclerListView.Holder) listView.findViewHolderForAdapterPosition(idx + proxyStartRow);
                    if (holder != null && holder.itemView instanceof TextDetailProxyCell) {
                        ((TextDetailProxyCell) holder.itemView).updateStatus();
                    }
                }

                boolean checking = false;
                if (!wasCheckedAllList) {
                    for (SharedConfig.ProxyInfo info : proxyList) {
                        if (info.checking || info.availableCheckTime == 0) {
                            checking = true;
                            break;
                        }
                    }
                    if (!checking) {
                        wasCheckedAllList = true;
                    }
                }
                if (!checking) {
                    updateRows(true);
                }
            }
        }
    }

    private class ListAdapter extends RecyclerListView.SelectionAdapter {
        private final static int VIEW_TYPE_SHADOW = 0,
            VIEW_TYPE_TEXT_SETTING = 1,
            VIEW_TYPE_HEADER = 2,
            VIEW_TYPE_TEXT_CHECK = 3,
            VIEW_TYPE_INFO = 4,
            VIEW_TYPE_PROXY_DETAIL = 5,
            VIEW_TYPE_SLIDE_CHOOSER = 6,
            VIEW_TYPE_PROMO = 7;

        public static final int PAYLOAD_CHECKED_CHANGED = 0;
        public static final int PAYLOAD_SELECTION_CHANGED = 1;
        public static final int PAYLOAD_SELECTION_MODE_CHANGED = 2;

        private Context mContext;

        public ListAdapter(Context context) {
            mContext = context;

            setHasStableIds(true);
        }

        public void toggleSelected(int position) {
            if (position < proxyStartRow || position >= proxyEndRow) {
                return;
            }
            SharedConfig.ProxyInfo info = proxyList.get(position - proxyStartRow);
            if (isOwnWsBypass(info)) {
                return;
            }
            if (selectedItems.contains(info)) {
                selectedItems.remove(info);
            } else {
                selectedItems.add(info);
            }
            notifyItemChanged(position, PAYLOAD_SELECTION_CHANGED);
            checkActionMode();
        }

        public void clearSelected() {
            selectedItems.clear();
            notifyItemRangeChanged(proxyStartRow, proxyEndRow - proxyStartRow, PAYLOAD_SELECTION_CHANGED);
            checkActionMode();
        }

        private void checkActionMode() {
            int selectedCount = selectedItems.size();
            boolean actionModeShowed = actionBar.isActionModeShowed();
            if (selectedCount > 0) {
                selectedCountTextView.setNumber(selectedCount, actionModeShowed);
                if (!actionModeShowed) {
                    actionBar.showActionMode();
                    notifyItemRangeChanged(proxyStartRow, proxyEndRow - proxyStartRow, PAYLOAD_SELECTION_MODE_CHANGED);
                }
            } else if (actionModeShowed) {
                actionBar.hideActionMode();
                notifyItemRangeChanged(proxyStartRow, proxyEndRow - proxyStartRow, PAYLOAD_SELECTION_MODE_CHANGED);
            }
        }

        @Override
        public int getItemCount() {
            return rowCount;
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            switch (holder.getItemViewType()) {
                case VIEW_TYPE_SHADOW: {
                    break;
                }
                case VIEW_TYPE_TEXT_SETTING: {
                    TextSettingsCell textCell = (TextSettingsCell) holder.itemView;
                    textCell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
                    if (position == proxyAddRow) {
                        textCell.setText(getString(R.string.AddProxy), deleteAllRow != -1);
                    } else if (position == deleteAllRow) {
                        textCell.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
                        textCell.setText(getString(R.string.DeleteAllProxies), false);
                    }
                    break;
                }
                case VIEW_TYPE_PROMO: {
                    TextCell cell = (TextCell) holder.itemView;
                    if (position == nimarkoVpnRow) {
                        cell.setTextAndColorfulIcon(getString(R.string.NM_ProxyVpnPromo),
                                R.drawable.pill_proxy, Theme.getColor(Theme.key_statisticChartLine_green), true);
                    } else if (position == nimarkoVlessRow) {
                        cell.setTextAndColorfulIcon(getString(R.string.NM_ProxyVlessPromo),
                                R.drawable.shield_network_filled_solar, Theme.getColor(Theme.key_statisticChartLine_blue), false);
                    }
                    break;
                }
                case VIEW_TYPE_HEADER: {
                    HeaderCell headerCell = (HeaderCell) holder.itemView;
                    if (position == connectionsHeaderRow) {
                        headerCell.setText(getString(R.string.ProxyConnections));
                    }
                    break;
                }
                case VIEW_TYPE_TEXT_CHECK: {
                    TextCheckCell checkCell = (TextCheckCell) holder.itemView;
                    checkCell.setEnabled(position != callsRow || canUseProxyForCalls());
                    if (position == useProxyRow) {
                        checkCell.setTextAndCheck(getString(R.string.UseProxySettings), useProxySettings, rotationRow != -1);
                    } else if (position == callsRow) {
                        checkCell.setTextAndCheck(callsString("UseProxyForCalls", "Use Proxy for Calls"), useProxyForCalls, false);
                    } else if (position == rotationRow) {
                        checkCell.setTextAndCheck(getString(R.string.UseProxyRotation), SharedConfig.proxyRotationEnabled, true);
                    }
                    break;
                }
                case VIEW_TYPE_INFO: {
                    TextInfoPrivacyCell cell = (TextInfoPrivacyCell) holder.itemView;
                    if (position == callsDetailRow) {
                        cell.setText(callsString("UseProxyForCallsInfo", "Proxy servers may decrease the quality of your calls."));
                    } else if (position == rotationTimeoutInfoRow) {
                        cell.setText(getString(R.string.ProxyRotationTimeoutInfo));
                    } else if (position == nimarkoVpnInfoRow) {
                        cell.setText(getString(R.string.NM_ProxyVpnPromo_Desc));
                    }
                    break;
                }
                case VIEW_TYPE_PROXY_DETAIL: {
                    TextDetailProxyCell cell = (TextDetailProxyCell) holder.itemView;
                    SharedConfig.ProxyInfo info = proxyList.get(position - proxyStartRow);
                    cell.setProxy(info);
                    cell.setChecked(SharedConfig.currentProxy == info);
                    cell.setItemSelected(selectedItems.contains(proxyList.get(position - proxyStartRow)), false);
                    cell.setSelectionEnabled(!selectedItems.isEmpty(), false);
                    break;
                }
                case VIEW_TYPE_SLIDE_CHOOSER: {
                    if (position == rotationTimeoutRow) {
                        SlideChooseView chooseView = (SlideChooseView) holder.itemView;
                        ArrayList<Integer> options = new ArrayList<>(ProxyRotationController.ROTATION_TIMEOUTS);
                        String[] values = new String[options.size()];
                        for (int i = 0; i < options.size(); i++) {
                            values[i] = LocaleController.formatString(R.string.ProxyRotationTimeoutSeconds, options.get(i));
                        }
                        chooseView.setCallback(i -> {
                            SharedConfig.proxyRotationTimeout = i;
                            SharedConfig.saveConfig();
                        });
                        chooseView.setOptions(SharedConfig.proxyRotationTimeout, values);
                    }
                    break;
                }
            }
        }

        @SuppressWarnings("unchecked")
        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, @NonNull List payloads) {
            if (holder.getItemViewType() == VIEW_TYPE_PROXY_DETAIL && !payloads.isEmpty()) {
                TextDetailProxyCell cell = (TextDetailProxyCell) holder.itemView;
                if (payloads.contains(PAYLOAD_SELECTION_CHANGED)) {
                    cell.setItemSelected(selectedItems.contains(proxyList.get(position - proxyStartRow)), true);
                }
                if (payloads.contains(PAYLOAD_SELECTION_MODE_CHANGED)) {
                    cell.setSelectionEnabled(!selectedItems.isEmpty(), true);
                }
            } else if (holder.getItemViewType() == VIEW_TYPE_TEXT_CHECK && payloads.contains(PAYLOAD_CHECKED_CHANGED)) {
                TextCheckCell checkCell = (TextCheckCell) holder.itemView;
                checkCell.setEnabled(position != callsRow || canUseProxyForCalls());
                if (position == useProxyRow) {
                    checkCell.setChecked(useProxySettings);
                } else if (position == callsRow) {
                    checkCell.setChecked(useProxyForCalls);
                } else if (position == rotationRow) {
                    checkCell.setChecked(SharedConfig.proxyRotationEnabled);
                }
            } else {
                super.onBindViewHolder(holder, position, payloads);
            }
        }

        @Override
        public void onViewAttachedToWindow(RecyclerView.ViewHolder holder) {
            int viewType = holder.getItemViewType();
            if (viewType == VIEW_TYPE_TEXT_CHECK) {
                TextCheckCell checkCell = (TextCheckCell) holder.itemView;
                int position = holder.getAdapterPosition();
                checkCell.setEnabled(position != callsRow || canUseProxyForCalls());
                if (position == useProxyRow) {
                    checkCell.setChecked(useProxySettings);
                } else if (position == callsRow) {
                    checkCell.setChecked(useProxyForCalls);
                } else if (position == rotationRow) {
                    checkCell.setChecked(SharedConfig.proxyRotationEnabled);
                }
            }
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int position = holder.getAdapterPosition();
            if (position == RecyclerView.NO_POSITION) return false;
            if (position == callsRow) return canUseProxyForCalls();
            return position == nimarkoVpnRow || position == nimarkoVlessRow || position == useProxyRow || position == rotationRow || position == proxyAddRow || position == deleteAllRow || position >= proxyStartRow && position < proxyEndRow;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            switch (viewType) {
                case VIEW_TYPE_SHADOW:
                    view = new ShadowSectionCell(mContext);
                    break;
                case VIEW_TYPE_TEXT_SETTING:
                    view = new TextSettingsCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case VIEW_TYPE_HEADER:
                    view = new HeaderCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case VIEW_TYPE_TEXT_CHECK:
                    view = new TextCheckCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case VIEW_TYPE_INFO:
                    view = new TextInfoPrivacyCell(mContext);
                    break;
                case VIEW_TYPE_PROMO:
                    view = new TextCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case VIEW_TYPE_SLIDE_CHOOSER:
                    view = new SlideChooseView(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case VIEW_TYPE_PROXY_DETAIL:
                default:
                    view = new TextDetailProxyCell(mContext);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(RecyclerView.LayoutParams.MATCH_PARENT, RecyclerView.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public long getItemId(int position) {
            if (position == useProxyShadowRow) {
                return -1;
            } else if (position == proxyShadowRow) {
                return -2;
            } else if (position == proxyAddRow) {
                return -3;
            } else if (position == useProxyRow) {
                return -4;
            } else if (position == callsRow) {
                return -5;
            } else if (position == callsDetailRow) {
                return -7;
            } else if (position == connectionsHeaderRow) {
                return -6;
            } else if (position == deleteAllRow) {
                return -8;
            } else if (position == rotationRow) {
                return -9;
            } else if (position == rotationTimeoutRow) {
                return -10;
            } else if (position == rotationTimeoutInfoRow) {
                return -11;
            } else if (position == nimarkoVpnRow) {
                return -12;
            } else if (position == nimarkoVlessRow) {
                return -13;
            } else if (position == nimarkoVpnInfoRow) {
                return -14;
            } else if (position >= proxyStartRow && position < proxyEndRow) {
                return proxyList.get(position - proxyStartRow).hashCode();
            } else {
                return -7;
            }
        }

        @Override
        public int getItemViewType(int position) {
            if (position == useProxyShadowRow || position == proxyShadowRow) {
                return VIEW_TYPE_SHADOW;
            } else if (position == nimarkoVpnRow || position == nimarkoVlessRow) {
                return VIEW_TYPE_PROMO;
            } else if (position == proxyAddRow || position == deleteAllRow) {
                return VIEW_TYPE_TEXT_SETTING;
            } else if (position == useProxyRow || position == rotationRow || position == callsRow) {
                return VIEW_TYPE_TEXT_CHECK;
            } else if (position == callsDetailRow) {
                return VIEW_TYPE_INFO;
            } else if (position == connectionsHeaderRow) {
                return VIEW_TYPE_HEADER;
            } else if (position == rotationTimeoutRow) {
                return VIEW_TYPE_SLIDE_CHOOSER;
            } else if (position >= proxyStartRow && position < proxyEndRow) {
                return VIEW_TYPE_PROXY_DETAIL;
            } else {
                return VIEW_TYPE_INFO;
            }
        }
    }

    @Override
    public ArrayList<ThemeDescription> getThemeDescriptions() {
        ArrayList<ThemeDescription> themeDescriptions = new ArrayList<>();

        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_CELLBACKGROUNDCOLOR, new Class[]{TextSettingsCell.class, TextCheckCell.class, HeaderCell.class, TextDetailProxyCell.class}, null, null, null, Theme.key_windowBackgroundWhite));
        themeDescriptions.add(new ThemeDescription(fragmentView, ThemeDescription.FLAG_BACKGROUND, null, null, null, null, Theme.key_windowBackgroundGray));

        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_LISTGLOWCOLOR, null, null, null, null, Theme.key_actionBarDefault));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_ITEMSCOLOR, null, null, null, null, Theme.key_actionBarDefaultIcon));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_TITLECOLOR, null, null, null, null, Theme.key_actionBarDefaultTitle));
        themeDescriptions.add(new ThemeDescription(actionBar, ThemeDescription.FLAG_AB_SELECTORCOLOR, null, null, null, null, Theme.key_actionBarDefaultSelector));

        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_SELECTOR, null, null, null, null, Theme.key_listSelector));

        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{View.class}, Theme.dividerPaint, null, null, Theme.key_divider));

        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextSettingsCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextSettingsCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteValueText));

        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextDetailProxyCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_TEXTCOLOR | ThemeDescription.FLAG_CHECKTAG | ThemeDescription.FLAG_IMAGECOLOR, new Class[]{TextDetailProxyCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteBlueText6));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_TEXTCOLOR | ThemeDescription.FLAG_CHECKTAG | ThemeDescription.FLAG_IMAGECOLOR, new Class[]{TextDetailProxyCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText2));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_TEXTCOLOR | ThemeDescription.FLAG_CHECKTAG | ThemeDescription.FLAG_IMAGECOLOR, new Class[]{TextDetailProxyCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteGreenText));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_TEXTCOLOR | ThemeDescription.FLAG_CHECKTAG | ThemeDescription.FLAG_IMAGECOLOR, new Class[]{TextDetailProxyCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_text_RedRegular));
        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_IMAGECOLOR, new Class[]{TextDetailProxyCell.class}, new String[]{"checkImageView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText3));

        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{HeaderCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlueHeader));

        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCheckCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteBlackText));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCheckCell.class}, new String[]{"valueTextView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText2));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCheckCell.class}, new String[]{"checkBox"}, null, null, null, Theme.key_switchTrack));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextCheckCell.class}, new String[]{"checkBox"}, null, null, null, Theme.key_switchTrackChecked));

        themeDescriptions.add(new ThemeDescription(listView, ThemeDescription.FLAG_BACKGROUNDFILTER, new Class[]{TextInfoPrivacyCell.class}, null, null, null, Theme.key_windowBackgroundGrayShadow));
        themeDescriptions.add(new ThemeDescription(listView, 0, new Class[]{TextInfoPrivacyCell.class}, new String[]{"textView"}, null, null, null, Theme.key_windowBackgroundWhiteGrayText4));

        return themeDescriptions;
    }
}
