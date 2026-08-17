package app.nimarkogram.messenger.infocards;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.os.Bundle;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;
import java.util.List;

public class InfoCardStripView extends FrameLayout implements NotificationCenter.NotificationCenterDelegate {

    private static final int DRAG_DISTANCE_DP = 28;
    private static final float COMMIT_FRACTION = 0.25f;   
    private static final int FLING_DP_PER_S = 700;        
    public static final long AUTO_SCROLL_MS = 15000;      

    private final Theme.ResourcesProvider resourcesProvider;
    private final ArrayList<BaseInfoCard> pills = new ArrayList<>();
    private int currentIndex = 0;

    private final int touchSlop;
    private VelocityTracker velocityTracker;
    private boolean dragging;
    private float downX, downY;
    private float dragProgress;   
    private boolean dragUp;       
    private int incomingIndex = -1;
    private ValueAnimator animator;
    
    private int pendingActiveCardId = -1;
    
    private int measuredCardWidthLimit;

    private boolean potentialTap;
    private boolean longPressFired;
    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            if (!potentialTap || dragging) return;
            BaseInfoCard cur = current();
            if (cur != null) {
                longPressFired = true;
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                cur.onCardLongClicked();
                setCardsPressed(false);
            }
        }
    };

    private float visibilityFactor = 1f;
    
    private boolean opaqueCards;

    public InfoCardStripView(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        this.touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        setClipChildren(false);
        setClipToPadding(false);
    }

    public void setOpaqueCards(boolean v) {
        opaqueCards = v;
        for (BaseInfoCard p : pills) p.setOpaqueFlat(v);
    }

    public void rebuildIfChanged() {
        if (!InfoCardsConfig.isEnabled()) {
            
            if (!pills.isEmpty()) {
                cancelAnim();
                removeAllViews();
                pills.clear();
                currentIndex = 0;
                incomingIndex = -1;
                requestLayout();
            }
            disarmGlobalTicker();
            disarmRateTicker();
            return;
        }
        if (pills.isEmpty()) { rebuild(); return; }
        List<Integer> active = InfoCardsConfig.getActiveCards();
        if (active.size() == pills.size()) {
            boolean same = true;
            for (int i = 0; i < active.size(); i++) {
                if (pills.get(i).getCardId() != active.get(i)) { same = false; break; }
            }
            if (same) {
                
                updateColors();
                return;
            }
        }
        rebuild();
    }

    public void rebuild() {
        cancelAnim();
        pendingActiveCardId = -1;
        removeAllViews();
        
        int prevId = -1;
        BaseInfoCard prev = current();
        if (prev != null) prevId = prev.getCardId();
        pills.clear();
        currentIndex = 0;
        List<Integer> active = InfoCardsConfig.getActiveCards();
        for (int id : active) {
            BaseInfoCard pill = InfoCardRegistry.create(id, getContext(), resourcesProvider);
            if (pill != null) {
                pill.setOpaqueFlat(opaqueCards); 
                pill.setAccessibilityDelegate(new View.AccessibilityDelegate() {
                    @Override
                    public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                        super.onInitializeAccessibilityNodeInfo(host, info);
                        int index = pills.indexOf(host);
                        if (index >= 0) {
                            info.setCollectionItemInfo(AccessibilityNodeInfo.CollectionItemInfo.obtain(
                                    0, 1, index, 1, false, index == currentIndex));
                        }
                        
                        boolean canForward = pills.size() > 1 && neighbor(true) >= 0;
                        boolean canBack = pills.size() > 1 && neighbor(false) >= 0;
                        info.setScrollable(canForward || canBack);
                        if (canForward) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
                        if (canBack) info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
                    }

                    @Override
                    public boolean performAccessibilityAction(View host, int action, Bundle args) {
                        if (action == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
                            return moveFromAccessibility(true);
                        }
                        if (action == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
                            return moveFromAccessibility(false);
                        }
                        return super.performAccessibilityAction(host, action, args);
                    }
                });
                pills.add(pill);
                
                int g = Gravity.CENTER_VERTICAL | (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT);
                addView(pill, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, g));
            }
        }
        
        int target = prevId >= 0 ? prevId : InfoCardsConfig.getLastActiveCardId();
        if (target >= 0) {
            for (int i = 0; i < pills.size(); i++) {
                if (pills.get(i).getCardId() == target) { currentIndex = i; break; }
            }
        }
        
        int cap = usableCardWidth();
        int hSpec = android.view.View.MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(40), android.view.View.MeasureSpec.AT_MOST);
        for (BaseInfoCard p : pills) {
            p.setMaxChipWidth(cap); 
            p.measure(android.view.View.MeasureSpec.makeMeasureSpec(cap, android.view.View.MeasureSpec.AT_MOST), hSpec);
        }
        requestLayout();
        applyResting();
    }

    public boolean isLayoutSuppressed() {
        return dragging || animator != null;
    }

    public boolean canAnimateCardResize() {
        return !isLayoutSuppressed() && visibilityFactor > 0.999f;
    }

    private int usableCardWidth() {
        if (measuredCardWidthLimit > 0) {
            return measuredCardWidthLimit;
        }
        int parentW = 0;
        if (getParent() instanceof View) parentW = ((View) getParent()).getWidth();
        if (parentW <= 0) parentW = getWidth();
        if (parentW <= 0) parentW = AndroidUtilities.displaySize.x;
        if (parentW <= 0) parentW = AndroidUtilities.dp(400);
        
        int leadingReserve = opaqueCards ? 0 : AndroidUtilities.dp(56);
        int usable = parentW - leadingReserve;
        int floor = AndroidUtilities.dp(48); 
        return Math.max(1, Math.min(parentW, Math.max(Math.min(floor, parentW), usable)));
    }

    private int usableCardWidth(int widthMeasureSpec) {
        int mode = View.MeasureSpec.getMode(widthMeasureSpec);
        int available = View.MeasureSpec.getSize(widthMeasureSpec);
        if (mode == View.MeasureSpec.UNSPECIFIED || available <= 0) {
            return usableCardWidth();
        }
        int floor = AndroidUtilities.dp(48);
        int leadingReserve = opaqueCards ? 0 : AndroidUtilities.dp(56);
        return Math.max(1, Math.min(available,
                Math.max(Math.min(floor, available), available - leadingReserve)));
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        
        measuredCardWidthLimit = usableCardWidth(widthMeasureSpec);
        for (BaseInfoCard pill : pills) {
            pill.setMaxChipWidth(measuredCardWidthLimit);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    private void applyResting() {
        applyResting(true);
    }

    private void applyResting(boolean notifySelected) {
        
        int usable = usableCardWidth();
        for (int i = 0; i < pills.size(); i++) {
            BaseInfoCard p = pills.get(i);
            p.setMaxChipWidth(usable);
            boolean cur = i == currentIndex;
            
            p.setVisibility(cur ? VISIBLE : GONE);
            p.setAlpha(cur ? 1f : 0f);
            p.setScaleX(cur ? 1f : 0.8f);
            p.setScaleY(cur ? 1f : 0.8f);
            p.setTranslationX(0);
            p.setTranslationY(0);
        }
        incomingIndex = -1;
        BaseInfoCard cur = current();
        if (notifySelected && cur != null) cur.onCardSelected();
    }

    private void resetForWindowLifecycle() {
        cancelAnim();
        dragging = false;
        dragProgress = 0f;
        incomingIndex = -1;
        pendingActiveCardId = -1;
        potentialTap = false;
        longPressFired = false;
        removeCallbacks(longPressRunnable);
        setCardsPressed(false);
        releaseTracker();
        for (BaseInfoCard pill : pills) {
            pill.finishResizeAnimation();
        }
        applyResting(false);
        requestLayout();
        invalidate();
    }

    private BaseInfoCard current() {
        return (currentIndex >= 0 && currentIndex < pills.size()) ? pills.get(currentIndex) : null;
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        
        final int padTight = AndroidUtilities.dp(1);
        final int padLoose = AndroidUtilities.dp(20);
        final int padLeft = LocaleController.isRTL ? padTight : padLoose;
        final int padRight = LocaleController.isRTL ? padLoose : padTight;
        canvas.save();
        canvas.clipRect(-padLeft, 0, getWidth() + padRight, getHeight());
        super.dispatchDraw(canvas);
        canvas.restore();
    }

    public void syncToActiveCard() {
        syncToActiveCard(true);
    }

    private void syncToActiveCard(boolean notifySelected) {
        if (pills.isEmpty()) return;
        cancelAnim();
        pendingActiveCardId = -1;
        dragging = false;
        dragProgress = 0;
        incomingIndex = -1;
        int target = InfoCardsConfig.getLastActiveCardId();
        if (target >= 0) {
            for (int i = 0; i < pills.size(); i++) {
                if (pills.get(i).getCardId() == target) { currentIndex = i; break; }
            }
        }
        BaseInfoCard cur = current();
        if (cur != null) cur.renderNextInstant(); 
        applyResting(notifySelected);
    }

    private int neighbor(boolean up) {
        int n = pills.size();
        if (n == 0) return -1;
        boolean inf = InfoCardsConfig.isInfiniteScrolling();
        int idx = up ? currentIndex + 1 : currentIndex - 1;
        if (idx < 0) return inf ? n - 1 : -1;
        if (idx >= n) return inf ? 0 : -1;
        return idx;
    }

    private boolean moveFromAccessibility(boolean forward) {
        int next = neighbor(forward);
        if (next < 0 || next == currentIndex) return false;
        cancelAnimResume();
        BaseInfoCard old = current();
        if (old != null) old.onCardUnselected();
        currentIndex = next;
        dragProgress = 0;
        dragging = false;
        applyResting();
        BaseInfoCard selected = current();
        if (selected != null) {
            InfoCardsConfig.setLastActiveCardId(selected.getCardId());
            selected.sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_SELECTED);
        }
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.infoCardsActiveCardChanged);
        restartGlobalTicker();
        return true;
    }

    private float dragHeight() {
        
        return AndroidUtilities.dp(DRAG_DISTANCE_DP);
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (pills.isEmpty()) return false;
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                
                downX = ev.getX();
                downY = ev.getY();
                dragging = false;
                cancelAnimResume();
                return true;
            case MotionEvent.ACTION_MOVE:
                float dy = ev.getY() - downY, dx = ev.getX() - downX;
                if (!dragging && pills.size() >= 2 && Math.abs(dy) > touchSlop && Math.abs(dy) >= Math.abs(dx)) {
                    beginDrag(ev);
                    return true;
                }
                break;
        }
        return dragging;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (pills.isEmpty()) return false;
        if (velocityTracker == null) velocityTracker = VelocityTracker.obtain();
        velocityTracker.addMovement(ev);
        float h = dragHeight();
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downY = ev.getY();
                downX = ev.getX();
                cancelAnimResume();
                restartGlobalTicker(); 
                
                potentialTap = true;
                longPressFired = false;
                setCardsPressed(true);
                removeCallbacks(longPressRunnable);
                postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout());
                return true;
            case MotionEvent.ACTION_MOVE: {
                
                if (longPressFired) break;
                if (!dragging) {
                    float dy = ev.getY() - downY, dx = ev.getX() - downX;
                    if (Math.abs(dy) > touchSlop || Math.abs(dx) > touchSlop) {
                        
                        if (potentialTap) {
                            potentialTap = false;
                            removeCallbacks(longPressRunnable);
                            setCardsPressed(false);
                        }
                    }
                    if (pills.size() >= 2 && Math.abs(dy) > touchSlop && Math.abs(dy) >= Math.abs(dx)) {
                        beginDrag(ev);
                    } else {
                        break;
                    }
                }
                
                float dy = ev.getY() - downY;
                dragUp = dy < 0;
                int nb = neighbor(dragUp);
                ensureIncomingPrepared(nb);
                float prog;
                if (nb < 0) {
                    
                    float raw = Math.abs(dy) / h;
                    prog = (float) (1.0 - 1.0 / (raw * 0.18f + 1.0));
                } else {
                    prog = Math.min(1f, Math.abs(dy) / h);
                }
                dragProgress = prog;
                applyDrag(nb, dragProgress, dragUp, h);
                break;
            }
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL: {
                removeCallbacks(longPressRunnable);
                
                if (ev.getActionMasked() == MotionEvent.ACTION_UP
                        && potentialTap && !dragging && !longPressFired) {
                    BaseInfoCard cur = current();
                    setCardsPressed(false);
                    if (cur != null) cur.onCardClicked();
                    potentialTap = false;
                    return true;
                }
                potentialTap = false;
                setCardsPressed(false);
                if (!dragging) {
                    releaseTracker();
                    return true;
                }
                velocityTracker.computeCurrentVelocity(1000);
                float vy = velocityTracker.getYVelocity();
                releaseTracker();
                int nb = neighbor(dragUp);
                
                boolean flingMatches = Math.abs(vy) > AndroidUtilities.dp(FLING_DP_PER_S)
                        && (vy < 0) == dragUp;
                boolean commit = dragging && nb >= 0
                        && (dragProgress > COMMIT_FRACTION || flingMatches);
                if (commit) animateCommit(nb);
                else animateSnapBack(nb);
                dragging = false;
                break;
            }
        }
        return true;
    }

    private void setCardsPressed(boolean pressed) {
        BaseInfoCard cur = current();
        if (cur != null) cur.setPressed(pressed);
    }

    private void beginDrag(MotionEvent ev) {
        dragging = true;
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        
        float h = dragHeight();
        float offset = dragUp ? -(dragProgress * h) : (dragProgress * h);
        downY = ev.getY() - offset;
        downX = ev.getX();
    }

    private void ensureIncomingPrepared(int nb) {
        if (nb == incomingIndex) return;
        
        if (incomingIndex >= 0 && incomingIndex < pills.size() && incomingIndex != currentIndex) {
            BaseInfoCard old = pills.get(incomingIndex);
            old.setVisibility(GONE);
        }
        incomingIndex = nb;
        if (nb >= 0 && nb < pills.size()) {
            BaseInfoCard in = pills.get(nb);
            
            in.renderNextInstant(); 
            in.setVisibility(VISIBLE);
            try { in.onUpdateData(false); } catch (Throwable ignore) {}
            
            int sw = getWidth(), sh = getHeight();
            if (sw > 0 && sh > 0) {
                
                int cap = usableCardWidth();
                in.setMaxChipWidth(cap); 
                in.measure(
                        android.view.View.MeasureSpec.makeMeasureSpec(cap, android.view.View.MeasureSpec.AT_MOST),
                        android.view.View.MeasureSpec.makeMeasureSpec(sh, android.view.View.MeasureSpec.AT_MOST));
                int mw = in.getMeasuredWidth(), mh = in.getMeasuredHeight();
                
                mw = Math.min(mw, cap);
                int top = Math.max(0, (sh - mh) / 2);
                if (LocaleController.isRTL) {
                    in.layout(0, top, mw, top + mh);
                } else {
                    in.layout(sw - mw, top, sw, top + mh); 
                }
            }
        }
    }

    private void applyDrag(int incomingIdx, float prog, boolean up, float h) {
        
        BaseInfoCard cur = current();
        if (cur != null) {
            float dir = up ? -1f : 1f;
            cur.setTranslationY(dir * h * 1.35f * prog);
            cur.setAlpha(Math.max(0f, 1f - prog));
            float s = 1f - 0.28f * prog;
            cur.setScaleX(s);
            cur.setScaleY(s);
        }
        if (incomingIdx >= 0 && incomingIdx < pills.size()) {
            BaseInfoCard in = pills.get(incomingIdx);
            float dir = up ? 1f : -1f;
            in.setTranslationY(dir * h * 1.35f * (1f - prog));
            in.setAlpha(Math.min(1f, Math.max(0f, prog)));
            float s = 0.72f + 0.28f * prog;
            in.setScaleX(s);
            in.setScaleY(s);
        }
    }

    private void animateCommit(final int incomingIdx) {
        final BaseInfoCard cur = current();
        final BaseInfoCard in = pills.get(incomingIdx);
        final boolean up = dragUp;
        final float h = dragHeight();
        cancelAnim();
        final boolean[] cancelled = {false};
        animator = ValueAnimator.ofFloat(dragProgress, 1f);
        animator.setDuration(330);
        
        animator.setInterpolator(new android.view.animation.OvershootInterpolator(2.2f));
        animator.addUpdateListener(a -> applyDrag(incomingIdx, (float) a.getAnimatedValue(), up, h));
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(Animator animation) { cancelled[0] = true; }
            @Override
            public void onAnimationEnd(Animator animation) {
                if (cancelled[0]) return;
                animator = null;
                if (cur != null) cur.onCardUnselected();
                currentIndex = pills.indexOf(in);
                dragProgress = 0;
                
                applyResting(false);
                if (reconcilePendingActiveCard()) {
                    return;
                }
                
                applyResting();
                InfoCardsConfig.setLastActiveCardId(in.getCardId());
                
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.infoCardsActiveCardChanged);
            }
        });
        animator.start();
    }

    private void animateSnapBack(final int incomingIdx) {
        final boolean up = dragUp;
        final float h = dragHeight();
        cancelAnim();
        final boolean[] cancelled = {false};
        animator = ValueAnimator.ofFloat(dragProgress, 0f);
        animator.setDuration(200);
        animator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        animator.addUpdateListener(a -> applyDrag(incomingIdx, (float) a.getAnimatedValue(), up, h));
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(Animator animation) { cancelled[0] = true; }
            @Override
            public void onAnimationEnd(Animator animation) {
                if (cancelled[0]) return;
                animator = null;
                dragProgress = 0;
                applyResting(false);
                if (!reconcilePendingActiveCard()) {
                    applyResting();
                }
            }
        });
        animator.start();
    }

    private boolean reconcilePendingActiveCard() {
        int pending = pendingActiveCardId;
        pendingActiveCardId = -1;
        BaseInfoCard cur = current();
        if (pending < 0 || (cur != null && cur.getCardId() == pending)) {
            return false;
        }
        syncToActiveCard();
        return true;
    }

    private void cancelAnim() {
        if (animator != null) {
            animator.cancel();
            animator = null;
        }
    }

    private void cancelAnimResume() {
        ValueAnimator a = animator;
        if (a != null) {
            animator = null;
            a.end();
        }
    }

    private void releaseTracker() {
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
    }

    public void setVisibilityFactor(float f) {
        if (visibilityFactor == f) return;
        float previousFactor = visibilityFactor;
        visibilityFactor = f;
        
        if (f < 0.999f && (dragging || animator != null)) {
            cancelAnimResume();     
            if (dragging) {         
                dragging = false;
                dragProgress = 0;
                applyResting(false);
                if (!reconcilePendingActiveCard()) {
                    applyResting();
                }
            }
        }
        if (previousFactor >= 0.999f && f < 0.999f) {
            BaseInfoCard cur = current();
            if (cur != null) cur.finishResizeAnimation();
        }
        
        setAlpha(f);
        float scale = AndroidUtilities.lerp(0.6f, 1.0f, f);
        setScaleX(scale);
        setScaleY(scale);
        
        setVisibility(f <= 0.01f ? GONE : VISIBLE);
    }

    public float getVisibilityFactor() {
        return visibilityFactor;
    }

    public int getCardsCount() {
        return pills.size();
    }

    public void updateColors() {
        for (BaseInfoCard p : pills) {
            p.updateColors();
            
            p.applyColorMode();
        }
    }

    private static boolean globalTickerArmed = false;
    private static final Runnable GLOBAL_AUTOSCROLL = () -> {
        globalTickerArmed = false;
        if (!InfoCardsConfig.isEnabled() || !InfoCardsConfig.isAutoScroll()) return; 
        List<Integer> active = InfoCardsConfig.getActiveCards();
        if (active.size() >= 2) {
            int curId = InfoCardsConfig.getLastActiveCardId();
            int idx = active.indexOf(curId);
            int next = active.get(idx < 0 ? 0 : (idx + 1) % active.size());
            InfoCardsConfig.setLastActiveCardId(next);
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.infoCardsActiveCardChanged);
        }
        armGlobalTicker(); 
    };

    static void armGlobalTicker() {
        if (globalTickerArmed || !InfoCardsConfig.isEnabled() || !InfoCardsConfig.isAutoScroll()) return;
        globalTickerArmed = true;
        AndroidUtilities.runOnUIThread(GLOBAL_AUTOSCROLL, AUTO_SCROLL_MS);
    }

    static void restartGlobalTicker() {
        globalTickerArmed = false;
        AndroidUtilities.cancelRunOnUIThread(GLOBAL_AUTOSCROLL);
        armGlobalTicker();
    }

    static void disarmGlobalTicker() {
        globalTickerArmed = false;
        AndroidUtilities.cancelRunOnUIThread(GLOBAL_AUTOSCROLL);
    }

    private static final long RATE_REFRESH_MS = 90000;
    private static boolean rateTickerArmed = false;
    private static final Runnable GLOBAL_RATE_REFRESH = () -> {
        rateTickerArmed = false;
        if (!InfoCardsConfig.isEnabled()) return; 
        if (hasActiveCryptoCard()) {
            InfoCardRates.fetch(false, null); 
        }
        armRateTicker(); 
    };

    private static boolean hasActiveCryptoCard() {
        List<Integer> active = InfoCardsConfig.getActiveCards();
        return active.contains(InfoCardType.TON.id)
                || active.contains(InfoCardType.BTC.id)
                || active.contains(InfoCardType.USD.id);
    }

    static void armRateTicker() {
        if (rateTickerArmed || !InfoCardsConfig.isEnabled()) return;
        rateTickerArmed = true;
        AndroidUtilities.runOnUIThread(GLOBAL_RATE_REFRESH, RATE_REFRESH_MS);
    }

    static void disarmRateTicker() {
        rateTickerArmed = false;
        AndroidUtilities.cancelRunOnUIThread(GLOBAL_RATE_REFRESH);
    }

    @Override
    protected void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        
        for (BaseInfoCard pill : pills) {
            pill.updateLayoutDirection();
            android.widget.FrameLayout.LayoutParams lp =
                    (android.widget.FrameLayout.LayoutParams) pill.getLayoutParams();
            int gravity = Gravity.CENTER_VERTICAL | (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT);
            if (lp != null && lp.gravity != gravity) {
                lp.gravity = gravity;
                pill.setLayoutParams(lp);
            }
            InfoCardRegistry.CardInfo info = InfoCardRegistry.get(pill.getCardId());
            if (info != null) pill.setCardAccessibilityLabel(info.getName());
            try { pill.onUpdateData(false); } catch (Throwable ignored) {}
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.infoCardsLayoutChanged);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.infoCardsSettingsChanged);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.infoCardsColorModeChanged);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.infoCardsActiveCardChanged);
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.didSetNewTheme);
        
        rebuildIfChanged();
        
        syncToActiveCard();
        armGlobalTicker(); 
        armRateTicker();   
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        
        resetForWindowLifecycle();
        if (visibility == View.VISIBLE) {
            
            syncToActiveCard(false);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.infoCardsLayoutChanged);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.infoCardsSettingsChanged);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.infoCardsColorModeChanged);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.infoCardsActiveCardChanged);
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.didSetNewTheme);
        resetForWindowLifecycle();
        
        potentialTap = false;
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.infoCardsLayoutChanged) {
            
            rebuildIfChanged();
        } else if (id == NotificationCenter.infoCardsColorModeChanged
                || id == NotificationCenter.didSetNewTheme) {
            
            updateColors();
        } else if (id == NotificationCenter.infoCardsSettingsChanged) {
            
            boolean refreshAll = args == null || args.length == 0;
            for (BaseInfoCard p : pills) {
                boolean affected = refreshAll;
                if (!affected) {
                    for (Object arg : args) {
                        if (arg instanceof Number && ((Number) arg).intValue() == p.getCardId()) {
                            affected = true;
                            break;
                        }
                    }
                }
                if (affected) {
                    try { p.onUpdateData(false); } catch (Throwable ignore) {}
                }
            }
            
            if (InfoCardsConfig.isAutoScroll()) armGlobalTicker(); else disarmGlobalTicker();
        } else if (id == NotificationCenter.infoCardsActiveCardChanged) {
            
            BaseInfoCard cur = current();
            int targetId = InfoCardsConfig.getLastActiveCardId();
            if (dragging || animator != null) {
                
                pendingActiveCardId = targetId;
            } else if (cur != null && cur.getCardId() == targetId) {
                
            } else if (getVisibility() == VISIBLE && isShown()
                    && getWindowVisibility() == View.VISIBLE && hasWindowFocus()
                    && visibilityFactor > 0.99f && pills.size() >= 2
                    && pills.get((currentIndex + 1) % pills.size()).getCardId() == targetId) {
                int nb = (currentIndex + 1) % pills.size();
                dragUp = true;                 
                ensureIncomingPrepared(nb);
                dragProgress = 0f;
                animateCommit(nb);             
            } else {
                syncToActiveCard();            
            }
        }
    }
}
