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
import android.view.ViewTreeObserver;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class InfoCardStripView extends FrameLayout implements NotificationCenter.NotificationCenterDelegate {

    private static final int DRAG_DISTANCE_DP = 28;
    private static final float COMMIT_FRACTION = 0.25f;   
    private static final int FLING_DP_PER_S = 700;        
    public static final long AUTO_SCROLL_MS = 15000;      

    private final Theme.ResourcesProvider resourcesProvider;
    private final ArrayList<BaseInfoCard> pills = new ArrayList<>();
    private final ArrayList<BaseInfoCard> preparedCards = new ArrayList<>();
    private int currentIndex = 0;

    private final int touchSlop;
    private VelocityTracker velocityTracker;
    private boolean dragging;
    private boolean touchActive;
    private float downX, downY;
    private float dragProgress;   
    private boolean dragUp;       
    private int incomingIndex = -1;
    private ValueAnimator animator;
    private boolean settlingToNext;
    private float releaseVelocity;
    private boolean dragCrossedCard;
    
    private int pendingActiveCardId = -1;
    private ViewTreeObserver pendingActiveCardObserver;
    private final ViewTreeObserver.OnPreDrawListener pendingActiveCardListener = this::resumePendingActiveCard;
    
    private int measuredCardWidthLimit;

    private boolean potentialTap;
    private boolean longPressFired;
    private final Runnable longPressRunnable = new Runnable() {
        @Override
        public void run() {
            if (!touchActive || !potentialTap || dragging || !isHostVisible(1f)) return;
            BaseInfoCard cur = current();
            if (cur == null) return;
            longPressFired = true;
            potentialTap = false;
            setCardsPressed(false);
            if (cur.onCardLongClicked()) {
                performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
            }
        }
    };

    private float visibilityFactor = 1f;
    private boolean hasPresentedCard;
    private final Runnable resumeActiveCard = () -> {
        if (isAttachedToWindow() && getWindowVisibility() == VISIBLE && hasWindowFocus()) {
            followSharedActiveCard();
        }
    };
    
    private boolean opaqueCards;
    private boolean inlineFolderStyle;
    private BlurredBackgroundDrawableViewFactory glassBackgroundFactory;
    public void setGlassBackgroundFactory(BlurredBackgroundDrawableViewFactory factory) {
        glassBackgroundFactory = factory;
        for (BaseInfoCard pill : pills) pill.setGlassBackgroundFactory(factory);
    }
    public void setInlineFolderStyle(boolean inline) {
        if (inlineFolderStyle == inline) return;
        inlineFolderStyle = inline;
        for (BaseInfoCard pill : pills) {
            pill.setInlineFolderStyle(inline);
        }
        requestLayout();
    }

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
            setPendingActiveCard(-1);
            
            if (!pills.isEmpty()) {
                resetForWindowLifecycle();
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
        setTouchActive(false);
        preparedCards.clear();
        dragging = false;
        dragProgress = 0f;
        incomingIndex = -1;
        potentialTap = false;
        longPressFired = false;
        removeCallbacks(longPressRunnable);
        setCardsPressed(false);
        releaseTracker();
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        setPendingActiveCard(-1);
        
        int prevId = -1;
        BaseInfoCard prev = current();
        if (prev != null) prevId = prev.getCardId();
        Map<Integer, BaseInfoCard> reusable = new HashMap<>();
        for (BaseInfoCard pill : pills) {
            reusable.put(pill.getCardId(), pill);
        }
        ArrayList<BaseInfoCard> nextPills = new ArrayList<>();
        currentIndex = 0;
        List<Integer> active = InfoCardsConfig.getActiveCards();
        for (int id : active) {
            BaseInfoCard pill = reusable.remove(id);
            if (pill == null) {
                pill = InfoCardRegistry.create(id, getContext(), resourcesProvider);
            }
            if (pill != null) {
                pill.setOpaqueFlat(opaqueCards); 
                pill.setGlassBackgroundFactory(glassBackgroundFactory);
                pill.setInlineFolderStyle(inlineFolderStyle);
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
                nextPills.add(pill);
                if (pill.getParent() == null) {
                    int g = Gravity.CENTER_VERTICAL | (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT);
                    addView(pill, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT,
                            LayoutHelper.WRAP_CONTENT, g));
                }
            }
        }
        for (BaseInfoCard removed : reusable.values()) {
            removeView(removed);
        }
        pills.clear();
        pills.addAll(nextPills);
        for (BaseInfoCard pill : pills) {
            bringChildToFront(pill);
        }
        
        int target = prevId >= 0 ? prevId : InfoCardsConfig.getLastActiveCardId();
        if (target >= 0) {
            for (int i = 0; i < pills.size(); i++) {
                if (pills.get(i).getCardId() == target) { currentIndex = i; break; }
            }
        }
        
        applyResting(false);
        requestLayout();
    }

    public boolean isLayoutSuppressed() {
        return dragging || animator != null;
    }

    public boolean canAnimateCardResize() {
        return !isLayoutSuppressed() && visibilityFactor > 0.999f;
    }
    private int carouselWidth() {
        BaseInfoCard cur = current();
        if (cur == null) return 0;
        int width = cur.getMeasuredWidth();
        if (incomingIndex >= 0 && incomingIndex < pills.size()) {
            float progress = Math.max(0f, Math.min(1f, dragProgress));
            width = Math.round(width + (pills.get(incomingIndex).getMeasuredWidth() - width) * progress);
        }
        return width;
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
        if (inlineFolderStyle && MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.AT_MOST) {
            setMeasuredDimension(Math.min(MeasureSpec.getSize(widthMeasureSpec), carouselWidth()), getMeasuredHeight());
        }
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
            p.setCardLayerType(View.LAYER_TYPE_NONE);
            p.applyDeferredCarouselData();
        }
        incomingIndex = -1;
        if (!isLayoutSuppressed()) preparedCards.clear();
        BaseInfoCard cur = current();
        if (notifySelected && cur != null) cur.onCardSelected();
    }

    private void resetForWindowLifecycle() {
        cancelAnim();
        setTouchActive(false);
        dragging = false;
        dragCrossedCard = false;
        dragProgress = 0f;
        incomingIndex = -1;
        setPendingActiveCard(-1);
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
        
        if (current() != null && isHostVisible(.01f)) hasPresentedCard = true;
        canvas.save();
        canvas.clipRect(0, 0, getWidth(), getHeight());
        super.dispatchDraw(canvas);
        canvas.restore();
    }

    public void syncToActiveCard() {
        if (hasPresentedCard) {
            followSharedActiveCard();
        } else {
            syncToActiveCard(true);
        }
    }

    private void syncToActiveCard(boolean notifySelected) {
        if (pills.isEmpty()) return;
        cancelAnim();
        setPendingActiveCard(-1);
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
        applyResting(false);
        if (notifySelected && cur != null) cur.updateDataInstantly();
    }

    private int neighbor(boolean up) {
        int n = pills.size();
        if (n < 2) return -1;
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
                
                return true;
            case MotionEvent.ACTION_MOVE:
                float dy = ev.getY() - downY, dx = ev.getX() - downX;
                if (!dragging && pills.size() >= 2 && Math.abs(dy) > touchSlop && Math.abs(dy) >= Math.abs(dx)) {
                    beginDrag();
                    return true;
                }
                break;
        }
        return dragging;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (pills.isEmpty()) return false;
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) {
            releaseTracker();
        } else if (!touchActive) {
            return true;
        }
        if (velocityTracker == null) velocityTracker = VelocityTracker.obtain();
        velocityTracker.addMovement(ev);
        float h = dragHeight();
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                setTouchActive(true);
                downY = ev.getY();
                downX = ev.getX();
                releaseVelocity = 0f;
                dragging = incomingIndex >= 0 || dragProgress > 0f;
                cancelAnimResume();
                setPendingActiveCard(-1);
                if (!dragging) dragCrossedCard = false;
                
                if (dragging) {
                    if (incomingIndex < 0) {
                        float raw = dragProgress / (0.18f * Math.max(0.0001f, 1f - dragProgress));
                        downY += (dragUp ? 1f : -1f) * raw * h;
                    } else {
                        downY += (dragUp ? 1f : -1f) * dragProgress * h;
                    }
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                }
                potentialTap = !dragging;
                longPressFired = false;
                setCardsPressed(potentialTap);
                removeCallbacks(longPressRunnable);
                if (potentialTap) postDelayed(longPressRunnable, ViewConfiguration.getLongPressTimeout());
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
                        beginDrag();
                    } else {
                        break;
                    }
                }
                
                float dy = ev.getY() - downY;
                boolean up = dy < 0;
                int nb = up == dragUp && incomingIndex >= 0 ? incomingIndex : neighbor(up);
                dragUp = up;
                int count = pills.size();
                int pages = (int) (Math.abs(dy) / h);
                if (!InfoCardsConfig.isInfiniteScrolling()) {
                    int availablePages = nb < 0 ? 0 : 1 + (dragUp ? count - 1 - nb : nb);
                    pages = Math.min(pages, availablePages);
                }
                if (pages > 0 && count > 1) {
                    BaseInfoCard previous = current();
                    if (previous != null) previous.onCardUnselected();
                    int step = (pages - 1) % count;
                    int next = (nb + (dragUp ? step : count - step)) % count;
                    ensureIncomingPrepared(next);
                    currentIndex = next;
                    downY += (dragUp ? -1f : 1f) * pages * h;
                    dy = ev.getY() - downY;
                    dragProgress = 0f;
                    applyResting(false);
                    dragCrossedCard = true;
                    nb = neighbor(dragUp);
                }
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
                setTouchActive(false);
                removeCallbacks(longPressRunnable);
                
                if (ev.getActionMasked() == MotionEvent.ACTION_UP
                        && potentialTap && !dragging && !longPressFired) {
                    BaseInfoCard cur = current();
                    setCardsPressed(false);
                    if (cur != null) cur.onCardClicked();
                    potentialTap = false;
                    releaseTracker();
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
                int nb = incomingIndex;
                final boolean fling = Math.abs(vy) > AndroidUtilities.dp(FLING_DP_PER_S);
                final boolean flingMatches = fling && (vy < 0) == dragUp;
                final boolean flingReturns = fling && !flingMatches;
                releaseVelocity = ev.getActionMasked() == MotionEvent.ACTION_UP
                        ? (dragUp ? -vy : vy) / h : 0f;
                if (nb < 0) {
                    releaseVelocity *= 0.18f * (1f - dragProgress) * (1f - dragProgress);
                }
                boolean commit = ev.getActionMasked() == MotionEvent.ACTION_UP && dragging && nb >= 0
                        && !flingReturns && (dragProgress > COMMIT_FRACTION || flingMatches);
                dragging = false;
                if (commit) animateCommit(nb);
                else animateSnapBack(nb);
                break;
            }
        }
        return true;
    }

    private void setCardsPressed(boolean pressed) {
        BaseInfoCard cur = current();
        if (cur != null) cur.setPressed(pressed);
    }

    private void beginDrag() {
        dragging = true;
        potentialTap = false;
        removeCallbacks(longPressRunnable);
        setCardsPressed(false);
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
        
    }

    private void ensureIncomingPrepared(int nb) {
        if (nb == incomingIndex) return;
        BaseInfoCard cur = current();
        if (cur != null && !preparedCards.contains(cur)) preparedCards.add(cur);
        
        if (incomingIndex >= 0 && incomingIndex < pills.size() && incomingIndex != currentIndex) {
            BaseInfoCard old = pills.get(incomingIndex);
            old.setVisibility(GONE);
            old.applyDeferredCarouselData();
        }
        incomingIndex = nb;
        if (nb >= 0 && nb < pills.size()) {
            BaseInfoCard in = pills.get(nb);
            if (!preparedCards.contains(in)) {
                try { in.updateDataInstantly(); } catch (Throwable ignore) {}
                preparedCards.add(in);
            }
            
            in.setVisibility(VISIBLE);
            
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
        dragProgress = Math.max(0f, Math.min(1f, prog));
        if (inlineFolderStyle && carouselWidth() != getMeasuredWidth()) requestLayout();
        applyCarouselTransforms(incomingIdx, dragProgress, up, h);
    }
    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        if (incomingIndex >= 0 || dragProgress > 0f) {
            applyCarouselTransforms(incomingIndex, dragProgress, dragUp, dragHeight());
        }
    }
    private void applyCarouselTransforms(int incomingIdx, float progress, boolean up, float h) {
        
        BaseInfoCard cur = current();
        if (cur == null || getWidth() <= 0 || getHeight() <= 0 || cur.getHeight() <= 0) return;
        float p = Math.max(0f, Math.min(1f, progress));
        float dir = up ? -1f : 1f;
        float curCenter = cur.getTop() + cur.getHeight() / 2f;
        if (incomingIdx < 0 || incomingIdx >= pills.size() || incomingIdx == currentIndex) {
            float scale = Math.min(1f - 0.28f * p, getWidth() / (float) Math.max(1, cur.getWidth()));
            scale = Math.min(scale, getHeight() / (float) cur.getHeight());
            float half = cur.getHeight() * scale / 2f;
            float room = Math.max(0f, up ? curCenter - half : getHeight() - curCenter - half);
            float travel = h * 1.35f * p;
            float offset = room > 0f ? room * travel / (room + travel) : 0f;
            setCarouselTransform(cur, scale, curCenter + dir * offset);
            cur.setAlpha(1f);
            return;
        }
        BaseInfoCard in = pills.get(incomingIdx);
        if (in.getHeight() <= 0) return;
        float outScale = 1f - 0.16f * p;
        float inScale = 0.84f + 0.16f * p;
        outScale = Math.min(outScale, getWidth() / (float) Math.max(1, cur.getWidth()));
        inScale = Math.min(inScale, getWidth() / (float) Math.max(1, in.getWidth()));
        float center = curCenter + (in.getTop() + in.getHeight() / 2f - curCenter) * p;
        float available = Math.max(0f, 2f * Math.min(center, getHeight() - center));
        outScale = Math.min(outScale, available / cur.getHeight());
        inScale = Math.min(inScale, available / in.getHeight());
        float outRoom = Math.max(0f, available - cur.getHeight() * outScale) / 2f;
        float inRoom = Math.max(0f, available - in.getHeight() * inScale) / 2f;
        float travel = h * 0.55f;
        setCarouselTransform(cur, outScale, center + dir * Math.min(outRoom, travel) * p);
        setCarouselTransform(in, inScale, center - dir * Math.min(inRoom, travel) * (1f - p));
        cur.setAlpha(1f - p);
        in.setAlpha(p);
    }
    private void setCarouselTransform(BaseInfoCard card, float scale, float centerY) {
        card.setPivotX(LocaleController.isRTL ? 0f : card.getWidth());
        card.setPivotY(card.getHeight() / 2f);
        card.setScaleX(scale);
        card.setScaleY(scale);
        card.setTranslationX(0f);
        card.setTranslationY(centerY - card.getTop() - card.getHeight() / 2f);
    }
    private ValueAnimator createSettleAnimator(float target) {
        final float distance = Math.abs(target - dragProgress);
        final float velocity = Math.max(0f, (target >= dragProgress ? 1f : -1f) * releaseVelocity);
        releaseVelocity = 0f;
        final boolean commit = target == 1f;
        long duration = distance == 0f ? 0 : commit ? Math.round(120 + 140 * distance) : 200;
        if (velocity > 0f) {
            duration = Math.min(duration, Math.max(commit ? 100 : 160,
                    Math.round(2000f * distance / velocity)));
        }
        final float slope = distance > 0f
                ? Math.min(3f, velocity * duration / (1000f * distance)) : 0f;
        ValueAnimator result = ValueAnimator.ofFloat(dragProgress, target);
        result.setDuration(duration);
        result.setInterpolator(t -> t * (slope + t * (3f - 2f * slope + t * (slope - 2f))));
        return result;
    }

    private void animateCommit(final int incomingIdx) {
        final BaseInfoCard cur = current();
        final BaseInfoCard in = pills.get(incomingIdx);
        final boolean up = dragUp;
        final float h = dragHeight();
        cancelAnim();
        settlingToNext = true;
        final boolean[] cancelled = {false};
        animator = createSettleAnimator(1f);
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
                dragCrossedCard = false;
                
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
        settlingToNext = false;
        final boolean[] cancelled = {false};
        animator = createSettleAnimator(0f);
        animator.addUpdateListener(a -> applyDrag(incomingIdx, (float) a.getAnimatedValue(), up, h));
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationCancel(Animator animation) { cancelled[0] = true; }
            @Override
            public void onAnimationEnd(Animator animation) {
                if (cancelled[0]) return;
                animator = null;
                dragProgress = 0;
                boolean crossed = dragCrossedCard;
                dragCrossedCard = false;
                applyResting(false);
                if (!reconcilePendingActiveCard()) {
                    applyResting();
                    if (current() != null && (crossed
                            || InfoCardsConfig.getLastActiveCardId() != current().getCardId())) {
                        InfoCardsConfig.setLastActiveCardId(current().getCardId());
                        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.infoCardsActiveCardChanged);
                    }
                }
            }
        });
        animator.start();
    }

    private boolean reconcilePendingActiveCard() {
        if (pendingActiveCardId < 0) return false;
        int target = InfoCardsConfig.getLastActiveCardId();
        BaseInfoCard cur = current();
        followSharedActiveCard();
        return cur == null || cur.getCardId() != target;
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
            float target = settlingToNext ? 1f : 0f;
            if (Math.abs(target - dragProgress) * dragHeight() * 1.35f <= 1f) {
                animator = null;
                dragging = false;
                a.end();
                return;
            }
            cancelAnim();
            dragging = true;
        }
    }

    private void releaseTracker() {
        if (velocityTracker != null) {
            velocityTracker.recycle();
            velocityTracker = null;
        }
    }
    private void setTouchActive(boolean active) {
        if (touchActive == active) return;
        touchActive = active;
        if (active) {
            activeTouchCount++;
            setPendingActiveCard(-1);
            disarmGlobalTicker();
        } else {
            activeTouchCount--;
            if (activeTouchCount == 0) restartGlobalTicker();
        }
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(active);
    }

    public void setVisibilityFactor(float f) {
        float scale = AndroidUtilities.lerp(0.6f, 1.0f, f);
        setHostVisibility(f, scale, scale, f <= 0.01f ? GONE : VISIBLE);
    }
    public void setHostVisibility(float f, float scaleX, float scaleY, int visibility) {
        float previousFactor = visibilityFactor;
        visibilityFactor = f;
        
        final boolean hidden = visibility != VISIBLE || f <= 0f;
        if (hidden) {
            setTouchActive(false);
            releaseTracker();
            potentialTap = false;
            removeCallbacks(longPressRunnable);
            setCardsPressed(false);
        }
        if (hidden && (dragging || animator != null)) {
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
        if (previousFactor > 0f && hidden) {
            BaseInfoCard cur = current();
            if (cur != null) cur.finishResizeAnimation();
        }
        
        setAlpha(f);
        setScaleX(scaleX);
        setScaleY(scaleY);
        setVisibility(visibility);
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
    private static int activeTouchCount;
    private static final Runnable GLOBAL_AUTOSCROLL = () -> {
        globalTickerArmed = false;
        if (!InfoCardsConfig.isEnabled() || !InfoCardsConfig.isAutoScroll() || activeTouchCount > 0) return;
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
        if (globalTickerArmed || activeTouchCount > 0 || !InfoCardsConfig.isEnabled() || !InfoCardsConfig.isAutoScroll()) return;
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
        
        if (hasPresentedCard) {
            removeCallbacks(resumeActiveCard);
            postOnAnimation(resumeActiveCard);
        } else {
            syncToActiveCard();
        }
        armGlobalTicker(); 
        armRateTicker();   
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        
        removeCallbacks(resumeActiveCard);
        if (visibility == View.VISIBLE) {
            
            postOnAnimation(resumeActiveCard);
        } else {
            resetForWindowLifecycle();
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        removeCallbacks(resumeActiveCard);
        if (hasFocus) postOnAnimation(resumeActiveCard);
    }
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(resumeActiveCard);
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
            
            followSharedActiveCard();
        }
    }
    private boolean isHostVisible(float minimumAlpha) {
        if (!isAttachedToWindow() || getWindowVisibility() != VISIBLE || !hasWindowFocus()
                || !isShown() || visibilityFactor < minimumAlpha) return false;
        for (View view = this; view != null;
                view = view.getParent() instanceof View ? (View) view.getParent() : null) {
            if (view.getAlpha() < minimumAlpha) return false;
        }
        return true;
    }
    private void setPendingActiveCard(int targetId) {
        pendingActiveCardId = targetId;
        if (targetId < 0) {
            if (pendingActiveCardObserver != null) {
                if (pendingActiveCardObserver.isAlive()) {
                    pendingActiveCardObserver.removeOnPreDrawListener(pendingActiveCardListener);
                }
                pendingActiveCardObserver = null;
            }
        } else if (pendingActiveCardObserver == null && isAttachedToWindow()) {
            pendingActiveCardObserver = getViewTreeObserver();
            pendingActiveCardObserver.addOnPreDrawListener(pendingActiveCardListener);
        }
    }
    private boolean resumePendingActiveCard() {
        if (pendingActiveCardId >= 0 && !touchActive && !dragging && animator == null && isHostVisible(1f)) {
            followSharedActiveCard();
        }
        return true;
    }
    private void followSharedActiveCard() {
        BaseInfoCard cur = current();
        int targetId = InfoCardsConfig.getLastActiveCardId();
        int next = -1;
        for (int i = 0; i < pills.size(); i++) {
            if (pills.get(i).getCardId() == targetId) { next = i; break; }
        }
        if (next < 0) {
            setPendingActiveCard(-1);
        } else if (touchActive || dragging || animator != null) {
            setPendingActiveCard(targetId);
        } else if (cur != null && cur.getCardId() == targetId) {
            setPendingActiveCard(-1);
        } else if (!isHostVisible(1f)) {
            setPendingActiveCard(targetId);
        } else {
            setPendingActiveCard(-1);
            dragUp = true;
            ensureIncomingPrepared(next);
            dragProgress = 0f;
            animateCommit(next);
        }
    }
}
