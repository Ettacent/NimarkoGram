package app.nimarkogram.messenger.notifications;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.animation.ValueAnimator;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.Paint;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.VelocityTracker;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.lang.ref.WeakReference;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import app.nimarkogram.messenger.NimarkoConfig;
import app.nimarkogram.messenger.utils.chats.NimarkoChatsPasswordHelper;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationsController;
import org.telegram.messenger.NotificationsSettingsFacade;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.DialogsActivity;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.AnimatedLinearLayout;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.LaunchActivity;

public final class NimarkoInAppNotifications {
    private static volatile WeakReference<LaunchActivity> host = new WeakReference<>(null);
    private static volatile long generation;
    private static volatile boolean focused;
    private static long focusLostAt = -1;
    private static Banner banner;
    private static Banner retiringBanner;
    private static final LinkedHashMap<String, RecentDelivery> recent = new LinkedHashMap<>();
    private static final AtomicLongArray sessions = new AtomicLongArray(UserConfig.MAX_ACCOUNT_COUNT);
    private static boolean contentGesture;
    private static long previewRequest;

    public static void onContentTouch(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) contentGesture = true;
        else if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) contentGesture = false;
    }

    private static boolean navigationRunning(BaseFragment fragment) {
        BaseFragment root = LaunchActivity.getLastFragment();
        return fragment == null || fragment.isInPreviewMode() || fragment.hasShownSheet()
                || fragment.visibleDialog != null && fragment.visibleDialog.isShowing()
                || root != null && root != fragment && (root.isInPreviewMode() || root.hasShownSheet()
                || root.visibleDialog != null && root.visibleDialog.isShowing())
                || root instanceof org.telegram.ui.ViewPagerActivity && ((org.telegram.ui.ViewPagerActivity) root).isPageTransitionRunning()
                || fragment.getParentLayout() != null && (fragment.getParentLayout().isTransitionAnimationInProgress()
                || fragment.getParentLayout().isSwipeInProgress());
    }

    private static AnimatedLinearLayout resolvePanel() {
        return resolvePanel(null);
    }

    private static AnimatedLinearLayout resolvePanel(Banner request) {
        BaseFragment fragment = LaunchActivity.getLastFragmentIncludeMainTabs();
        if (navigationRunning(fragment)) return null;
        AnimatedLinearLayout panel = fragment.getInAppNotificationPanel();
        return panel != null && panel.isAttachedToWindow() && panel.isShown() ? panel : null;
    }

    private static final class Slot extends FrameLayout implements AnimatedLinearLayout.IndependentPanel, NotificationInlinePanel.CompactContent {
        final AnimatedLinearLayout panel;
        private Paint edgeFadePaint;
        private int contentAlpha = 255;
        private final android.graphics.RectF contentBounds = new android.graphics.RectF();
        private final android.graphics.RectF childBounds = new android.graphics.RectF();
        private final android.graphics.Matrix edgeFadeMatrix = new android.graphics.Matrix();
        boolean directResize;
        float retainedCoverage = 1f;
        int retainedCompactHeight;
        float retainedCompactVisibleHeight;
        @Override public int getCompactHeight() {
            if (getChildCount() == 0) return retainedCompactHeight;
            int height = 0;
            for (int i = 0; i < getChildCount(); i++) {
                Banner child = (Banner) getChildAt(i);
                if (child.getVisibility() != GONE) height = Math.max(height, child.collapsedHeight);
            }
            return height;
        }
        @Override public float getCompactVisibleHeight() {
            if (getChildCount() == 0) return retainedCompactVisibleHeight;
            float height = 0f;
            for (int i = 0; i < getChildCount(); i++) {
                Banner child = (Banner) getChildAt(i);
                if (child.getVisibility() == GONE) continue;
                height = Math.max(height, Math.max(0f, Math.min(child.collapsedHeight,
                        child.getMeasuredHeight() + child.pullOffset)));
            }
            return height;
        }
        @Override public boolean isDirectResize() { return directResize; }
        @Override public float getLayoutCoverage() {
            if (getChildCount() == 0) return retainedCoverage;
            float coverage = 0f;
            for (int i = 0; i < getChildCount(); i++) {
                Banner child = (Banner) getChildAt(i);
                if (child.getVisibility() == GONE) continue;
                int height = child.getMeasuredHeight();
                coverage = Math.max(coverage, height > 0
                        ? Math.max(0f, Math.min(1f, (height + child.pullOffset) / height)) : 1f);
            }
            return coverage;
        }
        Slot(AnimatedLinearLayout panel) {
            super(panel.getContext());
            this.panel = panel;
            setClipChildren(true);
            panel.addView(this, new android.widget.LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            panel.setPriority(this, -100);
            panel.setTrackChildSize(this);
        }
        static Slot obtain(AnimatedLinearLayout panel) {
            for (int i = 0; i < panel.getChildCount(); i++) if (panel.getChildAt(i) instanceof Slot) return (Slot) panel.getChildAt(i);
            return new Slot(panel);
        }
        void attach(Banner value) {
            value.slot = this;
            directResize = false;
            retainedCoverage = 1f;
            setMinimumHeight(0);
            addView(value, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.CENTER_HORIZONTAL));
            panel.setViewVisible(this, true, true);
        }
        void release(Banner value) {
            boolean animate = isAttachedToWindow() && isShown() && value.getVisibility() == VISIBLE
                    && value.getAlpha() > 0f && !value.moving && !value.opening
                    && !navigationRunning(LaunchActivity.getLastFragmentIncludeMainTabs());
            if (value.getParent() == this && getChildCount() == 1) {
                retainedCoverage = getLayoutCoverage();
                retainedCompactHeight = getCompactHeight();
                retainedCompactVisibleHeight = getCompactVisibleHeight();
                setMinimumHeight(retainedCoverage == 0f ? 0 : getMeasuredHeight());
            }
            if (value.getParent() == this) removeView(value);
            if (getChildCount() == 0) {
                if (!animate) {
                    retainedCoverage = 0f;
                    retainedCompactHeight = 0;
                    retainedCompactVisibleHeight = 0f;
                    setMinimumHeight(0);
                }
                panel.setViewVisible(this, false, animate);
                if (!animate && panel instanceof NotificationInlinePanel) {
                    ((NotificationInlinePanel) panel).onContentRemoved();
                }
            }
        }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            int height = getMinimumHeight();
            int width = MeasureSpec.getSize(widthSpec);
            for (int i = 0; i < getChildCount(); i++) {
                Banner child = (Banner) getChildAt(i);
                if (child.getVisibility() == GONE) continue;
                child.measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
                height = Math.max(height, Math.max(0, child.getMeasuredHeight() + Math.round(child.pullOffset)));
            }
            if (getChildCount() > 0 && height > 0) {
                for (int i = 0; i < panel.getChildCount(); i++) {
                    View sibling = panel.getChildAt(i);
                    if (!(sibling instanceof AnimatedLinearLayout.IndependentPanel) && panel.isViewVisible(sibling)) {
                        height += Math.round(dp(8) * getLayoutCoverage());
                        break;
                    }
                }
            }
            setMeasuredDimension(width, height);
        }
        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                float bottom = panel.getPaddingTop() + panel.getMetadata().getTotalHeight() - getY();
                if (event.getY() < 0 || event.getY() >= Math.min(getHeight(), bottom)) return false;
            }
            return super.dispatchTouchEvent(event);
        }
        @Override protected boolean onSetAlpha(int alpha) {
            contentAlpha = alpha;
            invalidate();
            return true;
        }
        @Override protected void dispatchDraw(android.graphics.Canvas canvas) {
            if (contentAlpha == 0) return;
            int layer = -1;
            if (contentAlpha < 255) {
                contentBounds.set(0, 0, getWidth(), getHeight());
                for (int i = 0; i < getChildCount(); i++) {
                    Banner child = (Banner) getChildAt(i);
                    if (child.getVisibility() != VISIBLE) continue;
                    float outset = child.surface.getShadowOutset();
                    childBounds.set(-outset, -outset, child.getWidth() + outset, child.getHeight() + outset);
                    child.getMatrix().mapRect(childBounds);
                    childBounds.offset(child.getLeft(), child.getTop());
                    contentBounds.union(childBounds);
                }
                layer = canvas.saveLayerAlpha(contentBounds.left, contentBounds.top,
                        contentBounds.right, contentBounds.bottom, contentAlpha);
            }
            super.dispatchDraw(canvas);
            if (layer != -1) canvas.restoreToCount(layer);
        }
        private float getEdgeFadeHeight(float offset) {
            return Math.min(dp(12), Math.max(0f, -offset));
        }
        private float getVisibleCardHeight(float height, float offset) {
            return Math.max(0f, Math.min(getHeight(), height + offset));
        }
        @Override protected boolean drawChild(android.graphics.Canvas canvas, View child, long drawingTime) {
            Banner value = (Banner) child;
            float visibleHeight = getVisibleCardHeight(child.getHeight(), value.pullOffset);
            if (getWidth() <= 0 || visibleHeight <= 0f) return false;
            float fadeHeight = getEdgeFadeHeight(value.pullOffset);
            if (fadeHeight > 0f && edgeFadePaint == null) {
                edgeFadePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                edgeFadePaint.setShader(new android.graphics.LinearGradient(0, 0, 0, 1,
                        android.graphics.Color.BLACK, android.graphics.Color.TRANSPARENT, android.graphics.Shader.TileMode.CLAMP));
                edgeFadePaint.setXfermode(new android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT));
            }
            float outset = value.surface.getShadowOutset();
            int layer = fadeHeight > 0f ? canvas.saveLayer(-outset, 0, getWidth() + outset, visibleHeight + outset, null) : -1;
            int save = canvas.save();
            canvas.translate(child.getLeft(), child.getTop());
            canvas.concat(child.getMatrix());
            value.surface.drawShadow(canvas, child.getAlpha());
            canvas.restoreToCount(save);
            boolean result = super.drawChild(canvas, child, drawingTime);
            if (layer != -1) {
                edgeFadeMatrix.setScale(1f, fadeHeight);
                edgeFadePaint.getShader().setLocalMatrix(edgeFadeMatrix);
                canvas.drawRect(-outset, 0, getWidth() + outset, fadeHeight, edgeFadePaint);
                canvas.restoreToCount(layer);
            }
            return result;
        }
    }

    private NimarkoInAppNotifications() {}

    public static final class Delivery {
        private final AtomicBoolean active = new AtomicBoolean(true);
        private final AtomicBoolean completed = new AtomicBoolean();
        public boolean isActive() { return active.get(); }
        public boolean isComplete() { return completed.get(); }
        public void cancel() { active.set(false); }
        public boolean complete() { return completed.compareAndSet(false, true); }
    }
    private static final class RecentDelivery {
        final long shownAt;
        final Delivery delivery;
        RecentDelivery(long shownAt, Delivery delivery) {
            this.shownAt = shownAt;
            this.delivery = delivery;
        }
    }

    public static long session(int account) {
        return account >= 0 && account < sessions.length() ? sessions.get(account) : -1;
    }

    public static boolean isCurrent(int account, long owner, long session) {
        return session >= 0 && session(account) == session && owner != 0
                && UserConfig.isValidAccount(account) && UserConfig.getInstance(account).isClientActivated()
                && UserConfig.getInstance(account).getClientUserId() == owner;
    }

    public static void onAccountLoggedOut(int account) {
        if (account < 0 || account >= sessions.length()) return;
        long nextSession = sessions.incrementAndGet(account);
        AndroidUtilities.runOnUIThread(() -> {
            if (banner != null && banner.account == account && banner.loginSession < nextSession) {
                Banner old = banner;
                banner = null;
                remove(old);
            }
            if (retiringBanner != null && retiringBanner.account == account && retiringBanner.loginSession < nextSession) {
                Banner old = retiringBanner;
                retiringBanner = null;
                remove(old);
            }
            recent.keySet().removeIf(key -> key.startsWith(account + ":")
                    && Long.parseLong(key.split(":", 4)[2]) < nextSession);
        });
    }

    public static void onResume(LaunchActivity activity) {
        contentGesture = false;
        dismiss();
        host = new WeakReference<>(activity);
        focused = activity.hasWindowFocus();
        focusLostAt = -1;
    }

    public static void onPause(LaunchActivity activity) {
        contentGesture = false;
        if (host.get() == activity) {
            host.clear();
            focused = false;
            focusLostAt = -1;
            dismiss();
        }
    }

    public static void onWindowFocusChanged(LaunchActivity activity, boolean hasFocus) {
        if (host.get() != activity || focused == hasFocus) return;
        focused = hasFocus;
        if (!hasFocus) {
            generation++;
            focusLostAt = SystemClock.elapsedRealtime();
            if (banner != null) banner.pauseInteraction();
        } else if (focusLostAt >= 0) {
            if (banner != null) banner.expiresAt += Math.max(0, SystemClock.elapsedRealtime() - focusLostAt);
            focusLostAt = -1;
        }
    }

    public static boolean isAvailable() {
        return focused && isHostVisible();
    }

    private static boolean isHostVisible() {
        return NimarkoConfig.inAppNotifications && host.get() != null
                && !ApplicationLoader.mainInterfacePaused && ApplicationLoader.isScreenOn
                && !SharedConfig.appLocked && !SharedConfig.isWaitingForPasscodeEnter && !AndroidUtilities.needShowPasscode();
    }

    public static boolean canPreview(int account, long dialogId, long topicId) {
        if (DialogObject.isEncryptedDialog(dialogId)
                || NimarkoChatsPasswordHelper.isChatLocked(account, dialogId)) return false;
        SharedPreferences p = MessagesController.getNotificationsSettings(account);
        if (!p.getBoolean("EnableInAppPreview", true) || !NotificationsController.getInstance(account)
                .getNotificationsSettingsFacade().getProperty(NotificationsSettingsFacade.PROPERTY_CONTENT_PREVIEW,
                        dialogId, topicId, true)) return false;
        TLRPC.Chat chat = dialogId < 0 ? MessagesController.getInstance(account).getChat(-dialogId) : null;
        if (dialogId < 0 && chat == null) return false;
        String key = dialogId > 0 ? "EnablePreviewAll"
                : ChatObject.isChannel(chat) && !chat.megagroup ? "EnablePreviewChannel" : "EnablePreviewGroup";
        return p.getBoolean(key, true);
    }

    public static boolean offer(int account, long owner, long loginSession, long dialogId, long topicId, int messageId, long randomId,
                             String title, String text, boolean preview, Delivery delivery, Consumer<Boolean> completion) {
        if (!isAvailable() || !isCurrent(account, owner, loginSession)
                || !SharedConfig.showNotificationsForAllAccounts && account != UserConfig.selectedAccount) return false;
        final long session = generation;
        final String key = messageId == 0 && randomId == 0 ? null
                : account + ":" + owner + ":" + loginSession + ":" + dialogId + ":" + topicId
                + ":" + (messageId != 0 ? "m" + messageId : "r" + randomId);
        final long deadline = SystemClock.elapsedRealtime() + 4000;
        Runnable present = new Runnable() {
            long archiveWaitStarted = -1;
            long archiveWaitDuration;
            @Override
            public void run() {
                boolean handled = false;
                boolean deferred = false;
                try {
                    if (!delivery.isActive() || session != generation || !isCurrent(account, owner, loginSession) || !allowed(account, owner, dialogId, false)) return;
                    long now = SystemClock.elapsedRealtime();
                    if (archiveWaitStarted >= 0) {
                        archiveWaitDuration += Math.max(0, now - archiveWaitStarted);
                        archiveWaitStarted = -1;
                    }
                    if (now >= deadline + archiveWaitDuration) return;
                    RecentDelivery shown = recent.get(key);
                    if (shown != null && shown.delivery.isActive() && now - shown.shownAt < 120_000) {
                        handled = true;
                        return;
                    }
                    if (archivePullGestureInProgress()) {
                        archiveWaitStarted = now;
                        deferred = true;
                        AndroidUtilities.runOnUIThread(this, 32);
                        return;
                    }
                    if (presentationBusy() || retiringBanner != null && banner != null
                            && !banner.sameConversation(account, owner, loginSession, dialogId, topicId)) {
                        deferred = true;
                        AndroidUtilities.runOnUIThread(this, 32);
                        return;
                    }
                    boolean contentPreview = preview && canPreview(account, dialogId, topicId);
                    if (banner != null && banner.replaceMessage(account, owner, loginSession, dialogId, topicId, messageId,
                            title, text, contentPreview, delivery)
                            || show(new Banner(host.get(), account, owner, loginSession, dialogId, topicId, messageId,
                            title, text, contentPreview, false, delivery))) {
                        handled = true;
                        if (key != null) recent.put(key, new RecentDelivery(now, delivery));
                        while (recent.size() > 64) recent.remove(recent.keySet().iterator().next());
                    }
                } catch (RuntimeException e) {
                    removeCurrent();
                    if (BuildVars.LOGS_ENABLED) FileLog.e(e);
                } finally {
                    if (!deferred) completion.accept(handled);
                }
            }
        };
        return ApplicationLoader.applicationHandler != null && ApplicationLoader.applicationHandler.post(present);
    }

    public static void preview() {
        LaunchActivity activity = host.get();
        int account = UserConfig.selectedAccount;
        long owner = UserConfig.getInstance(account).getClientUserId();
        if (!allowed(account, owner, 0, true)) return;
        final long loginSession = session(account), hostGeneration = generation, request = ++previewRequest;
        final long deadline = SystemClock.elapsedRealtime() + 4000;
        final BaseFragment source = LaunchActivity.getLastFragmentIncludeMainTabs();
        Runnable present = new Runnable() {
            @Override public void run() {
                if (request != previewRequest || hostGeneration != generation || host.get() != activity
                        || UserConfig.selectedAccount != account || !isCurrent(account, owner, loginSession)
                        || !allowed(account, owner, 0, true) || SystemClock.elapsedRealtime() >= deadline
                        || LaunchActivity.getLastFragmentIncludeMainTabs() != source) return;
                if (presentationBusy()) {
                    AndroidUtilities.runOnUIThread(this, 32);
                    return;
                }
                if (banner != null && banner.sample && banner.sameConversation(account, owner, loginSession, 0, 0)
                        && banner.slot != null && banner.isAttachedToWindow() && banner.slot.panel == resolvePanel()) {
                    banner.expiresAt = SystemClock.elapsedRealtime() + (banner.expanded ? 8000 : 5000);
                    return;
                }
                show(new Banner(activity, account, owner, loginSession, 0, 0, 0, getString(R.string.AppName),
                        getString(R.string.NM_InAppNotificationsSample), true, true, null));
            }
        };
        AndroidUtilities.runOnUIThread(present);
    }
    private static boolean presentationBusy() {
        return navigationRunning(LaunchActivity.getLastFragmentIncludeMainTabs())
                || banner != null && (banner.touching || banner.opening || banner.closing || banner.pullAnimator != null);
    }
    private static boolean archivePullGestureInProgress() {
        BaseFragment fragment = LaunchActivity.getLastFragmentIncludeMainTabs();
        return fragment instanceof DialogsActivity
                && ((DialogsActivity) fragment).isArchivePullGestureInProgress();
    }

    private static boolean allowed(int account, long owner, long dialogId, boolean sample) {
        LaunchActivity activity = host.get();
        return focused && activity != null && activity.hasWindowFocus() && mayRemain(account, owner, dialogId, sample);
    }

    private static boolean mayRemain(int account, long owner, long dialogId, boolean sample) {
        LaunchActivity activity = host.get();
        if (!isHostVisible() || activity == null || activity.isFinishing() || activity.isDestroyed()
                || !UserConfig.isValidAccount(account)
                || !UserConfig.getInstance(account).isClientActivated()
                || UserConfig.getInstance(account).getClientUserId() != owner) return false;
        if (sample) return true;
        if (!SharedConfig.showNotificationsForAllAccounts && account != UserConfig.selectedAccount) return false;
        if (!MessagesController.getNotificationsSettings(account).getBoolean("EnableInAppPopup", true)) return false;
        if (NimarkoConfig.askBiometricsToOpenArchive) {
            TLRPC.Dialog dialog = MessagesController.getInstance(account).dialogs_dict.get(dialogId);
            if (dialog == null || dialog.folder_id == 1) return false;
        }
        BaseFragment fragment = LaunchActivity.getLastFragmentIncludeMainTabs();
        return !(fragment instanceof ChatActivity && fragment.getCurrentAccount() == account
                && ((ChatActivity) fragment).getDialogId() == dialogId);
    }

    private static boolean show(Banner next) {
        LaunchActivity activity = host.get();
        AnimatedLinearLayout panel = resolvePanel(next);
        if (next.delivery != null && !next.delivery.isActive()
                || activity == null || panel == null || !isCurrent(next.account, next.owner, next.loginSession)
                || !allowed(next.account, next.owner, next.dialogId, next.sample)) return false;
        remove(retiringBanner);
        retiringBanner = banner;
        if (retiringBanner != null) {
            Banner old = retiringBanner;
            old.closing = true;
            old.cancelExpansion();
            old.cancelContentTransition();
            old.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            old.removeCallbacks(old.watch);
            old.animate().cancel();
            old.animate().alpha(0).setDuration(230)
                    .setInterpolator(CubicBezierInterpolator.EASE_BOTH)
                    .withEndAction(() -> {
                        if (retiringBanner == old) {
                            retiringBanner = null;
                            remove(old);
                        }
                    }).start();
        }
        banner = next;
        next.setAlpha(0f);
        next.setTranslationY(-dp(8));
        next.setScaleX(.97f);
        next.setScaleY(.97f);
        Slot.obtain(panel).attach(next);
        next.animate().alpha(1f).translationY(0).scaleX(1f).scaleY(1f).setDuration(360)
                .setInterpolator(CubicBezierInterpolator.Emphasized).start();
        next.postOnAnimation(() -> {
            if (banner == next && !next.opening && !next.closing
                    && (next.delivery != null && !next.delivery.isActive()
                    || !isCurrent(next.account, next.owner, next.loginSession)
                    || !allowed(next.account, next.owner, next.dialogId, next.sample))) removeCurrent();
        });
        next.expiresAt = SystemClock.elapsedRealtime() + 5000;
        next.postDelayed(next.watch, 250);
        return true;
    }

    public static void dismiss() {
        generation++;
        removeCurrent();
    }

    private static void removeCurrent() {
        Banner old = banner;
        banner = null;
        remove(old);
        old = retiringBanner;
        retiringBanner = null;
        remove(old);
    }

    private static void remove(Banner old) {
        if (old == null) return;
        old.closing = true;
        old.touching = false;
        old.navigationRequestCurrent = null;
        old.setPressed(false);
        old.cancelExpansion();
        old.cancelContentTransition();
        old.removeCallbacks(old.watch);
        old.animate().cancel();
        old.animate().withEndAction(null);
        if (old.slot != null) old.slot.release(old);
        else if (old.getParent() instanceof ViewGroup) ((ViewGroup) old.getParent()).removeView(old);
        old.opening = false;
    }

    private static void bindAvatar(BackupImageView avatar, int account, long dialogId,
                                   String heading, boolean preview, boolean sample) {
        avatar.getImageReceiver().setCurrentAccount(account);
        avatar.getImageReceiver().setCrossfadeDuration(180);
        avatar.getImageReceiver().setForceCrossfade(avatar.isAttachedToWindow());
        avatar.getImageReceiver().setCrossfadeWithOldImage(true);
        TLObject peer = preview && !sample ? dialogId > 0
                ? MessagesController.getInstance(account).getUser(dialogId)
                : MessagesController.getInstance(account).getChat(-dialogId) : null;
        AvatarDrawable placeholder = new AvatarDrawable();
        if (peer != null) {
            placeholder.setInfo(account, peer);
            avatar.setImage(org.telegram.messenger.ImageLocation.getForUserOrChat(account, peer,
                    org.telegram.messenger.ImageLocation.TYPE_SMALL), "50_50", placeholder, 0, peer);
        } else {
            String name = preview && heading != null && !heading.trim().isEmpty()
                    ? heading : getString(R.string.AppName);
            placeholder.setInfo(preview && !sample ? dialogId : 0, name, null);
            avatar.setImageDrawable(placeholder);
        }
    }

    private static final class Banner extends FrameLayout {
        final int account;
        int messageId;
        final long owner, loginSession, dialogId, topicId;
        final boolean preview, sample;
        Delivery delivery;
        Slot slot;
        boolean moving;
        long expiresAt;
        boolean touching, closing, dragged, opening;
        boolean expanded, multiplePointers, verticalDrag;
        BooleanSupplier navigationRequestCurrent;
        float expansion, downExpansion, downTranslation;
        int collapsedHeight, expandedHeight;
        TextView title, body, expandedBody;
        BackupImageView avatar;
        LinearLayout text;
        FrameLayout bodies;
        ImageView close;
        String avatarHeading;
        String boundAvatarHeading;
        long avatarPhotoId = Long.MIN_VALUE, avatarVolumeId;
        int avatarDcId, avatarLocalId;
        boolean avatarPeerAvailable;
        ValueAnimator pullAnimator, contentAnimator;
        VelocityTracker velocityTracker;
        float releaseVelocity;
        float pullOffset;
        float gestureExpansion, gestureOffset;
        boolean gestureFramePending;
        long gestureFrameTime;
        final Runnable gestureFrame = new Runnable() {
            @Override public void run() {
                if (!gestureFramePending) return;
                gestureFramePending = false;
                if (!touching || closing || opening) return;
                long now = SystemClock.uptimeMillis();
                float follow = 1f - (float) Math.exp(-Math.min(32, Math.max(1, now - gestureFrameTime)) / 18f);
                gestureFrameTime = now;
                float nextExpansion = expansion + (gestureExpansion - expansion) * follow;
                float nextOffset = pullOffset + (gestureOffset - pullOffset) * follow;
                boolean settled = Math.abs(nextExpansion - gestureExpansion) * Math.max(1, expandedHeight - collapsedHeight) < .5f
                        && Math.abs(nextOffset - gestureOffset) < .5f;
                setExpansion(settled ? gestureExpansion : nextExpansion);
                setPullOffset(settled ? gestureOffset : nextOffset);
                setAlpha(Math.max(.2f, 1f + Math.min(0, pullOffset) / Math.max(1, getHeight())));
                if (!settled) {
                    gestureFramePending = true;
                    postOnAnimation(this);
                }
            }
        };
        boolean contentFadeOut;
        String pendingName, pendingMessage;
        final NotificationGlassSurface surface;
        final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Rect visibleFrame = new Rect();
        final int[] viewportLocation = new int[2];
        int measuredAvailableHeight;
        int viewportWidth = -1, viewportHeight, viewportTop, viewportInset;
        float downX, downY;
        final int slop;
        final Runnable watch = new Runnable() {
            @Override public void run() {
                if (banner != Banner.this || closing) return;
                if (delivery != null && !delivery.isActive() || !isCurrent(account, owner, loginSession) || !mayRemain(account, owner, dialogId, sample)
                        || !sample && preview && !canPreview(account, dialogId, topicId)) {
                    removeCurrent();
                } else if (focused && !touching && !contentGesture && !archivePullGestureInProgress()
                        && !navigationRunning(LaunchActivity.getLastFragmentIncludeMainTabs()) && SystemClock.elapsedRealtime() >= expiresAt) {
                    hide();
                } else {
                    refreshAvatar();
                    followScreen();
                    postDelayed(this, 250);
                }
            }
        };

        Banner(LaunchActivity activity, int account, long owner, long loginSession, long dialogId, long topicId, int messageId,
               String heading, String message, boolean preview, boolean sample, Delivery delivery) {
            super(activity);
            this.account = account; this.owner = owner; this.dialogId = dialogId;
            this.loginSession = loginSession;
            this.delivery = delivery;
            avatarHeading = heading;
            this.topicId = topicId; this.messageId = messageId; this.preview = preview; this.sample = sample;
            slop = ViewConfiguration.get(activity).getScaledTouchSlop();
            setMinimumHeight(dp(64));
            setPadding(0, dp(10), 0, dp(10));
            surface = new NotificationGlassSurface(this);
            animate().setUpdateListener(animation -> {
                if (slot != null) slot.invalidate();
            });
            setForeground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 2));
            setClipToOutline(true);
            setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);

            avatar = new BackupImageView(activity);
            avatar.setRoundRadius(dp(18));
            refreshAvatar();
            LayoutParams avatarParams = LayoutHelper.createFrame(36, 36, Gravity.START | Gravity.TOP);
            avatarParams.setMarginStart(dp(10));
            addView(avatar, avatarParams);
            avatar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

            text = new LinearLayout(activity);
            text.setOrientation(LinearLayout.VERTICAL);
            LayoutParams textParams = LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP);
            textParams.setMarginStart(dp(56)); textParams.setMarginEnd(dp(48));
            addView(text, textParams);
            title = label(activity, 15, 1);
            title.setTypeface(Typeface.DEFAULT_BOLD);
            String name = preview ? heading : getString(R.string.AppName);
            if (preview && !sample && account != UserConfig.selectedAccount) {
                name += " · " + UserObject.getFirstName(UserConfig.getInstance(account).getCurrentUser());
            }
            title.setText(name);
            title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            text.addView(title);
            body = label(activity, 14, 2);
            body.setText(preview ? message : getString(R.string.NotificationHiddenMessage));
            body.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT);
            bodyParams.topMargin = dp(3);
            bodies = new FrameLayout(activity);
            bodies.addView(body, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            expandedBody = label(activity, 14, 8);
            expandedBody.setText(body.getText());
            expandedBody.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            expandedBody.setAlpha(0f);
            expandedBody.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            bodies.addView(expandedBody, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            text.addView(bodies, bodyParams);
            close = new ImageView(activity);
            close.setImageResource(R.drawable.msg_close);
            close.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            close.setScaleType(ImageView.ScaleType.FIT_CENTER);
            close.setPadding(dp(12), dp(12), dp(12), dp(12));
            close.setBackground(Theme.createSelectorDrawable(Theme.getColor(Theme.key_listSelector), 1));
            close.setContentDescription(getString(R.string.Close));
            LayoutParams closeParams = LayoutHelper.createFrame(48, 48, Gravity.END | Gravity.TOP);
            closeParams.setMarginEnd(dp(2));
            addView(close, closeParams);
            close.setOnClickListener(v -> hide());
            setOnClickListener(v -> animateOpenChat());
        }

        private TextView label(LaunchActivity context, int size, int lines) {
            TextView view = new TextView(context);
            view.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
            view.setIncludeFontPadding(false);
            view.setMaxLines(lines);
            view.setEllipsize(TextUtils.TruncateAt.END);
            view.setGravity(Gravity.START);
            return view;
        }
        boolean sameConversation(int account, long owner, long loginSession, long dialogId, long topicId) {
            return this.account == account && this.owner == owner && this.loginSession == loginSession
                    && this.dialogId == dialogId && this.topicId == topicId;
        }

        boolean replaceMessage(int account, long owner, long loginSession, long dialogId, long topicId, int messageId,
                               String heading, String message, boolean preview, Delivery delivery) {
            if (sample || closing || opening || touching || banner != this || this.preview != preview
                    || !sameConversation(account, owner, loginSession, dialogId, topicId)) return false;
            if (this.messageId > 0 && messageId > 0 && messageId < this.messageId
                    && (this.delivery == null || this.delivery.isActive())) return true;
            if (slot == null || !isAttachedToWindow() || slot.panel != resolvePanel()) return false;
            this.delivery = delivery;
            this.messageId = messageId;
            avatarHeading = heading;
            String name = preview ? heading : getString(R.string.AppName);
            if (preview && account != UserConfig.selectedAccount) {
                name += " · " + UserObject.getFirstName(UserConfig.getInstance(account).getCurrentUser());
            }
            replaceText(name, preview ? message : getString(R.string.NotificationHiddenMessage));
            refreshAvatar();
            expiresAt = SystemClock.elapsedRealtime() + (expanded ? 8000 : 5000);
            return true;
        }

        void replaceText(String name, String message) {
            if (TextUtils.equals(title.getText(), name) && TextUtils.equals(body.getText(), message)) {
                pendingName = pendingMessage = null;
                if (contentAnimator != null && contentFadeOut) fadeText(false);
                return;
            }
            boolean wasChangingTitle = !TextUtils.equals(title.getText(), pendingName);
            pendingName = name;
            pendingMessage = message;
            boolean changingTitle = !TextUtils.equals(title.getText(), name);
            if (contentAnimator != null && contentFadeOut && wasChangingTitle == changingTitle) return;
            fadeText(true);
        }

        void fadeText(boolean out) {
            if (contentAnimator != null) {
                ValueAnimator previous = contentAnimator;
                contentAnimator = null;
                previous.cancel();
            }
            contentFadeOut = out;
            float titleAlpha = title.getAlpha();
            float targetTitleAlpha = out && !TextUtils.equals(title.getText(), pendingName) ? 0f : 1f;
            ValueAnimator animator = ValueAnimator.ofFloat(bodies.getAlpha(), out ? 0 : 1);
            contentAnimator = animator;
            animator.setDuration(out ? 100 : 160);
            animator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
            animator.addUpdateListener(a -> {
                if (contentAnimator != a) return;
                bodies.setAlpha((float) a.getAnimatedValue());
                title.setAlpha(titleAlpha + (targetTitleAlpha - titleAlpha) * a.getAnimatedFraction());
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) {
                    if (contentAnimator != animation) return;
                    contentAnimator = null;
                    if (out && !closing) {
                        if (slot != null) slot.directResize = false;
                        if (!TextUtils.equals(title.getText(), pendingName)) title.setText(pendingName);
                        body.setText(pendingMessage);
                        expandedBody.setText(body.getText());
                        pendingName = pendingMessage = null;
                        fadeText(false);
                    }
                }
            });
            animator.start();
        }

        void cancelContentTransition() {
            if (contentAnimator != null) {
                ValueAnimator previous = contentAnimator;
                contentAnimator = null;
                previous.cancel();
            }
            if (pendingName != null && !closing) {
                if (!TextUtils.equals(title.getText(), pendingName)) title.setText(pendingName);
                body.setText(pendingMessage);
                expandedBody.setText(body.getText());
            }
            pendingName = pendingMessage = null;
            if (!closing) {
                title.setAlpha(1f);
                bodies.setAlpha(1f);
            }
        }

        private void refreshAvatar() {
            TLObject peer = preview && !sample ? dialogId > 0
                    ? MessagesController.getInstance(account).getUser(dialogId)
                    : MessagesController.getInstance(account).getChat(-dialogId) : null;
            long photoId = 0;
            int dcId = 0;
            TLRPC.FileLocation location = null;
            if (peer instanceof TLRPC.User && ((TLRPC.User) peer).photo != null) {
                TLRPC.UserProfilePhoto photo = ((TLRPC.User) peer).photo;
                photoId = photo.photo_id;
                dcId = photo.dc_id;
                location = photo.photo_small;
            } else if (peer instanceof TLRPC.Chat && ((TLRPC.Chat) peer).photo != null) {
                TLRPC.ChatPhoto photo = ((TLRPC.Chat) peer).photo;
                photoId = photo.photo_id;
                dcId = photo.dc_id;
                location = photo.photo_small;
            }
            long volumeId = location == null ? 0 : location.volume_id;
            int localId = location == null ? 0 : location.local_id;
            boolean available = peer != null;
            if (photoId == avatarPhotoId && dcId == avatarDcId && volumeId == avatarVolumeId
                    && localId == avatarLocalId && available == avatarPeerAvailable
                    && TextUtils.equals(avatarHeading, boundAvatarHeading)) return;
            avatarPhotoId = photoId;
            avatarDcId = dcId;
            avatarVolumeId = volumeId;
            avatarLocalId = localId;
            avatarPeerAvailable = available;
            boundAvatarHeading = avatarHeading;
            bindAvatar(avatar, account, dialogId, avatarHeading, preview, sample);
        }

        private void followScreen() {
            if (touching || closing || opening || contentGesture) return;
            BaseFragment fragment = LaunchActivity.getLastFragmentIncludeMainTabs();
            if (navigationRunning(fragment)) return;
            AnimatedLinearLayout next = resolvePanel();
            if (next == null) { hide(); return; }
            if (slot != null && slot.panel == next) return;
            moving = true;
            try {
                if (slot != null) slot.release(this);
                Slot.obtain(next).attach(this);
            } finally { moving = false; }
            settleExpansion(expanded);
            setAlpha(0f);
            animate().alpha(1f).setDuration(180).start();
        }

        int availableHeight() {
            View root = getRootView();
            root.getLocationOnScreen(viewportLocation);
            int bottom = viewportLocation[1] + root.getHeight();
            WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(this);
            int inset = insets == null ? 0 : insets.getInsets(WindowInsetsCompat.Type.ime() | WindowInsetsCompat.Type.systemBars()).bottom;
            if (viewportWidth != root.getWidth() || viewportHeight != root.getHeight()
                    || viewportTop != viewportLocation[1] || viewportInset != inset) {
                viewportWidth = root.getWidth();
                viewportHeight = root.getHeight();
                viewportTop = viewportLocation[1];
                viewportInset = inset;
                getWindowVisibleDisplayFrame(visibleFrame);
            }
            if (!visibleFrame.isEmpty()) bottom = Math.min(bottom, visibleFrame.bottom);
            else bottom -= inset;
            if (slot != null && slot.panel instanceof NotificationInlinePanel) {
                NotificationInlinePanel inline = (NotificationInlinePanel) slot.panel;
                if (inline.isOverlay() && inline.getParent() instanceof View) {
                    ((View) inline.getParent()).getLocationOnScreen(viewportLocation);
                    return inline.getAvailableContentHeight(bottom - viewportLocation[1]);
                }
            }
            View anchor = slot != null ? slot : this;
            anchor.getLocationOnScreen(viewportLocation);
            return Math.max(dp(68), bottom - viewportLocation[1] - dp(64));
        }

        @Override protected void onMeasure(int w, int h) {
            int width = Math.min(dp(560), MeasureSpec.getSize(w));
            measuredAvailableHeight = availableHeight();
            int bodyLimit = Math.max(dp(34), Math.min(dp(220), measuredAvailableHeight
                    - getPaddingTop() - getPaddingBottom() - title.getLineHeight() - dp(3)));
            int lines = Math.max(2, Math.min(8, bodyLimit / Math.max(1, expandedBody.getLineHeight())));
            if (expandedBody.getMaxLines() != lines) expandedBody.setMaxLines(lines);
            super.onMeasure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            expandedHeight = getMeasuredHeight();
            collapsedHeight = Math.min(expandedHeight, Math.max(dp(68), getPaddingTop() + getPaddingBottom()
                    + title.getMeasuredHeight() + dp(3) + body.getMeasuredHeight()));
            setMeasuredDimension(getMeasuredWidth(), Math.round(collapsedHeight + (expandedHeight - collapsedHeight) * expansion));
        }

        @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            super.onLayout(changed, left, top, right, bottom);
            int contentHeight = collapsedHeight - getPaddingTop() - getPaddingBottom();
            centerCollapsedContent(avatar, contentHeight, avatar.getMeasuredHeight());
            centerCollapsedContent(close, contentHeight, close.getMeasuredHeight());
            centerCollapsedContent(text, contentHeight, title.getMeasuredHeight() + dp(3) + body.getMeasuredHeight());
            if (availableHeight() != measuredAvailableHeight) requestLayout();
        }

        private void centerCollapsedContent(View child, int contentHeight, int childHeight) {
            int childTop = getPaddingTop() + Math.max(0, (contentHeight - childHeight) / 2);
            child.offsetTopAndBottom(childTop - child.getTop());
        }

        void cancelExpansion() {
            gestureFramePending = false;
            removeCallbacks(gestureFrame);
            if (pullAnimator != null) {
                ValueAnimator previous = pullAnimator;
                pullAnimator = null;
                previous.cancel();
            }
        }

        void setExpansion(float value) {
            float next = Math.max(0f, Math.min(1f, value));
            if (expansion == next) return;
            expansion = next;
            body.setAlpha(1f - next);
            expandedBody.setAlpha(next);
            if (slot != null) slot.directResize = true;
            requestLayout();
        }

        void setPullOffset(float offset) {
            offset = Math.max(-getHeight(), offset);
            setTranslationY(offset);
            if (pullOffset == offset) return;
            pullOffset = offset;
            if (slot != null) {
                slot.directResize = true;
                slot.requestLayout();
            }
        }

        void settlePull(float target, long duration, Runnable completion) {
            settleGeometry(target < 0 ? expansion : expanded ? 1f : 0f, target, duration, completion);
        }

        void settleGeometry(float targetExpansion, float targetOffset, long duration, Runnable completion) {
            cancelExpansion();
            float fromExpansion = expansion;
            float fromOffset = pullOffset;
            float fromAlpha = getAlpha();
            float fromScaleX = getScaleX();
            float fromScaleY = getScaleY();
            ValueAnimator animator = ValueAnimator.ofFloat(0f, 1f);
            pullAnimator = animator;
            animator.setDuration(duration);
            if (closing) {
                float distance = Math.max(1f, fromOffset - targetOffset);
                final float slope = Math.max(1.5f, Math.min(3f, -releaseVelocity * duration / (1000f * distance)));
                animator.setInterpolator(t -> t * (slope + t * (3f - 2f * slope + t * (slope - 2f))));
            } else {
                animator.setInterpolator(CubicBezierInterpolator.EASE_OUT);
            }
            animator.addUpdateListener(a -> {
                if (pullAnimator != a) return;
                float progress = (float) a.getAnimatedValue();
                setExpansion(fromExpansion + (targetExpansion - fromExpansion) * progress);
                setPullOffset(fromOffset + (targetOffset - fromOffset) * progress);
                if (closing || opening) setAlpha(fromAlpha * (1f - progress));
                if (opening) {
                    setScaleX(fromScaleX + (.98f - fromScaleX) * progress);
                    setScaleY(fromScaleY + (.98f - fromScaleY) * progress);
                }
            });
            animator.addListener(new AnimatorListenerAdapter() {
                @Override public void onAnimationEnd(Animator animation) {
                    if (pullAnimator != animation) return;
                    pullAnimator = null;
                    if (completion != null) completion.run();
                }
            });
            animator.start();
        }

        void settleExpansion(boolean open) {
            expanded = preview && open && expandedHeight > collapsedHeight;
            body.setImportantForAccessibility(expanded ? View.IMPORTANT_FOR_ACCESSIBILITY_NO : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
            expandedBody.setImportantForAccessibility(expanded ? View.IMPORTANT_FOR_ACCESSIBILITY_AUTO : View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            float target = expanded ? 1f : 0f;
            float distance = Math.abs((target - expansion) * (expandedHeight - collapsedHeight) - pullOffset);
            long duration = Math.max(180, Math.min(340, Math.round(220 + distance * 120 / Math.max(1, dp(200))
                    - Math.min(100, Math.abs(releaseVelocity) * 100 / Math.max(1, dp(1800))))));
            settleGeometry(target, 0, duration, null);
        }

        void beginGesture(MotionEvent e) {
            downX = e.getRawX(); downY = e.getRawY();
            downExpansion = expansion;
            downTranslation = pullOffset;
            dragged = multiplePointers = verticalDrag = false;
            touching = true;
            cancelExpansion();
            releaseVelocity = 0;
        }
        void trackGestureDirection(MotionEvent e) {
            if (dragged) return;
            float dx = Math.abs(e.getRawX() - downX), dy = Math.abs(e.getRawY() - downY);
            if (Math.max(dx, dy) <= slop) return;
            dragged = true;
            verticalDrag = dy > dx;
            setPressed(false);
            animate().cancel();
            downTranslation = getTranslationY();
            setPullOffset(downTranslation);
        }
        float gestureTravel(float dy) {
            float offset = downTranslation > 0
                    ? dp(120) * downTranslation / Math.max(1f, dp(36) - downTranslation) : downTranslation;
            return downExpansion * (preview ? Math.max(0, expandedHeight - collapsedHeight) : 0) + offset + dy;
        }

        void setGestureGeometry(float targetExpansion, float targetOffset) {
            gestureExpansion = Math.max(0, Math.min(1, targetExpansion));
            gestureOffset = Math.max(-collapsedHeight, targetOffset);
            if (!gestureFramePending) {
                gestureFrameTime = SystemClock.uptimeMillis();
                gestureFramePending = true;
                postOnAnimation(gestureFrame);
            }
        }

        void restoreGesture() {
            animate().withEndAction(null).alpha(1).scaleX(1f).scaleY(1f).setDuration(260)
                    .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
            expiresAt = SystemClock.elapsedRealtime() + (expanded ? 8000 : 5000);
        }

        @Override public WindowInsets onApplyWindowInsets(WindowInsets insets) {
            return insets;
        }

        void hide() {
            if (closing || banner != this) return;
            closing = true;
            setPressed(false);
            cancelExpansion();
            removeCallbacks(watch);
            animate().cancel();
            cancelContentTransition();
            float distance = Math.max(0, getHeight() + pullOffset);
            long duration = releaseVelocity < -dp(100)
                    ? Math.max(100, Math.min(200, Math.round(2000f * distance / -releaseVelocity))) : 180;
            settlePull(-getHeight(), duration, () -> { if (banner == this) removeCurrent(); });
        }

        void animateOpenChat() {
            if (closing || opening || banner != this) return;
            BaseFragment source = LaunchActivity.getLastFragmentIncludeMainTabs();
            if (navigationRunning(source) || !allowed(account, owner, dialogId, sample)) return;
            if (!sample) {
                final LaunchActivity activity = host.get();
                final long hostGeneration = generation;
                final int sourceAccount = UserConfig.selectedAccount;
                final long sourceOwner = UserConfig.getInstance(sourceAccount).getClientUserId();
                final long sourceSession = session(sourceAccount);
                final BooleanSupplier sourceCurrent = source.captureNavigationRequest();
                navigationRequestCurrent = () -> host.get() == activity && generation == hostGeneration
                        && UserConfig.selectedAccount == sourceAccount
                        && isCurrent(sourceAccount, sourceOwner, sourceSession)
                        && LaunchActivity.getLastFragmentIncludeMainTabs() == source
                        && !navigationRunning(source) && sourceCurrent.getAsBoolean();
            }
            opening = true;
            touching = true;
            setPressed(false);
            cancelExpansion();
            animate().cancel();
            if (!sample && account != UserConfig.selectedAccount) {
                openChat();
                return;
            }
            settleGeometry(expansion, -getHeight(), 220,
                    () -> { if (banner == this && opening) openChat(); });
        }

        void pauseInteraction() {
            touching = false;
            navigationRequestCurrent = null;
            setPressed(false);
            if (!closing && (opening || dragged)) {
                animate().cancel();
                opening = false;
                dragged = false;
                settleExpansion(expanded);
                animate().withEndAction(null).alpha(1f).scaleX(1f).scaleY(1f)
                        .setDuration(300).setInterpolator(CubicBezierInterpolator.EASE_OUT).start();
            }
        }

        void openChat() {
            if (banner != this) return;
            if (closing || delivery != null && !delivery.isActive() || !isCurrent(account, owner, loginSession) || !allowed(account, owner, dialogId, sample)) { removeCurrent(); return; }
            if (!sample && (navigationRequestCurrent == null || !navigationRequestCurrent.getAsBoolean())) {
                pauseInteraction();
                return;
            }
            LaunchActivity activity = host.get();
            if (sample || activity == null) { removeCurrent(); return; }
            if (account == UserConfig.selectedAccount) removeCurrent();
            Intent intent = new Intent(activity, LaunchActivity.class).setAction("com.tmessages.openchat")
                    .putExtra("nm_banner_owner", owner).putExtra("nm_banner_session", loginSession)
                    .putExtra("currentAccount", account).putExtra("message_id", Math.max(0, messageId));
            if (DialogObject.isEncryptedDialog(dialogId)) intent.putExtra("encId", DialogObject.getEncryptedChatId(dialogId));
            else if (dialogId > 0) intent.putExtra("userId", dialogId);
            else intent.putExtra("chatId", -dialogId).putExtra("topicId", topicId);
            activity.openInAppNotification(intent);
            if (banner == this) {
                pauseInteraction();
                expiresAt = SystemClock.elapsedRealtime() + (expanded ? 8000 : 5000);
            }
        }

        @Override public boolean dispatchTouchEvent(MotionEvent e) {
            if (opening || closing) return true;
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                touching = true;
                if (velocityTracker != null) velocityTracker.recycle();
                velocityTracker = VelocityTracker.obtain();
            }
            if (velocityTracker != null) {
                MotionEvent screenEvent = MotionEvent.obtain(e);
                try {
                    screenEvent.offsetLocation(e.getRawX() - e.getX(), e.getRawY() - e.getY());
                    velocityTracker.addMovement(screenEvent);
                } finally {
                    screenEvent.recycle();
                }
            }
            if (e.getPointerCount() > 1 && !multiplePointers) {
                multiplePointers = dragged = true;
                cancelExpansion();
                setPressed(false);
            }
            if (e.getActionMasked() == MotionEvent.ACTION_UP || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                touching = false;
                expiresAt = SystemClock.elapsedRealtime() + (expanded ? 8000 : 5000);
                if (velocityTracker != null) {
                    velocityTracker.computeCurrentVelocity(1000, dp(4000));
                    releaseVelocity = e.getActionMasked() == MotionEvent.ACTION_UP && !multiplePointers ? velocityTracker.getYVelocity() : 0;
                    velocityTracker.recycle();
                    velocityTracker = null;
                }
            }
            android.view.ViewParent touchParent = getParent();
            try {
                return super.dispatchTouchEvent(e);
            } finally {
                if (touchParent != null && (e.getActionMasked() == MotionEvent.ACTION_UP
                        || e.getActionMasked() == MotionEvent.ACTION_CANCEL)) {
                    touchParent.requestDisallowInterceptTouchEvent(false);
                }
            }
        }

        @Override public boolean onInterceptTouchEvent(MotionEvent e) {
            if (e.getActionMasked() == MotionEvent.ACTION_DOWN) {
                beginGesture(e);
            }
            if (dragged || multiplePointers) return true;
            if (e.getActionMasked() == MotionEvent.ACTION_MOVE) trackGestureDirection(e);
            return dragged;
        }

        @Override public boolean onTouchEvent(MotionEvent e) {
            if (closing) return true;
            float dy = e.getRawY() - downY;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    beginGesture(e);
                    drawableHotspotChanged(e.getX(), e.getY());
                    setPressed(true);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (multiplePointers) return true;
                    trackGestureDirection(e);
                    if (!dragged || !verticalDrag) return true;
                    setPressed(false);
                    animate().cancel();
                    touching = true;
                    float range = preview ? Math.max(0, expandedHeight - collapsedHeight) : 0;
                    float travel = gestureTravel(dy);
                    float overscroll = Math.max(0, travel - range);
                    float offset = travel < 0 ? travel : dp(36) * overscroll / (overscroll + dp(120));
                    setGestureGeometry(range > 0 ? travel / range : 0, offset);
                    return true;
                case MotionEvent.ACTION_UP:
                    touching = false;
                    setPressed(false);
                    if (!multiplePointers) {
                        if (!dragged) { settleExpansion(expanded); performClick(); return true; }
                        if (verticalDrag) {
                            float remainingTravel = gestureTravel(dy);
                            if (remainingTravel < 0 && (releaseVelocity < -dp(650)
                                    || releaseVelocity <= dp(650) && remainingTravel < -dp(24))) { hide(); return true; }
                            settleExpansion(preview && (releaseVelocity > dp(650)
                                    || releaseVelocity >= -dp(650) && remainingTravel >= (expandedHeight - collapsedHeight) * .5f));
                            restoreGesture();
                            return true;
                        }
                    }

                case MotionEvent.ACTION_CANCEL:
                    touching = false;
                    setPressed(false);
                    settleExpansion(expanded);
                    restoreGesture();
                    return true;
            }
            return true;
        }

        @Override protected void dispatchDraw(android.graphics.Canvas canvas) {
            super.dispatchDraw(canvas);
            if (preview && expandedHeight > collapsedHeight) {
                handlePaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
                handlePaint.setAlpha(85);
                canvas.drawRoundRect(getWidth() / 2f - dp(12), getHeight() - dp(5),
                        getWidth() / 2f + dp(12), getHeight() - dp(3), dp(1), dp(1), handlePaint);
            }
        }

        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            if (preview && expandedHeight > collapsedHeight) {
                info.addAction(expanded ? AccessibilityNodeInfo.AccessibilityAction.ACTION_COLLAPSE
                        : AccessibilityNodeInfo.AccessibilityAction.ACTION_EXPAND);
            }
            info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS);
        }

        @Override public boolean performAccessibilityAction(int action, Bundle args) {
            if (action == AccessibilityNodeInfo.ACTION_DISMISS) { hide(); return true; }
            if (preview && expandedHeight > collapsedHeight && (action == AccessibilityNodeInfo.ACTION_EXPAND
                    || action == AccessibilityNodeInfo.ACTION_COLLAPSE)) {
                releaseVelocity = 0;
                settleExpansion(action == AccessibilityNodeInfo.ACTION_EXPAND);
                restoreGesture();
                return true;
            }
            return super.performAccessibilityAction(action, args);
        }

        @Override protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            viewportWidth = -1;
            avatar.getImageReceiver().setForceCrossfade(true);
            surface.attach();
        }

        @Override protected void onDetachedFromWindow() {
            avatar.getImageReceiver().setForceCrossfade(false);
            surface.detach();
            if (!moving) {
                closing = true;
                setVisibility(INVISIBLE);
                if (banner == this) banner = null;
                if (retiringBanner == this) retiringBanner = null;
                removeCallbacks(watch);
                Slot previous = slot;
                AndroidUtilities.runOnUIThread(() -> {
                    if (previous != null && getParent() == previous) previous.release(this);
                });
            }
            cancelExpansion();
            if (velocityTracker != null) {
                velocityTracker.recycle();
                velocityTracker = null;
            }
            cancelContentTransition();
            animate().cancel();
            super.onDetachedFromWindow();
        }
    }
}
