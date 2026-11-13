/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.text.Layout;
import android.text.TextPaint;
import android.view.MotionEvent;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesStorage;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.RichMessageLayout;
import org.telegram.messenger.SharedConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_iv;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Cells.ChatMessageTransitionParams;
import org.telegram.ui.Cells.TextSelectionHelper;

import java.util.concurrent.atomic.AtomicInteger;
import app.nimarkogram.messenger.utils.ui.SystemTextPaint;
import org.telegram.utils.twidget.WidgetMedia;
import org.telegram.utils.twidget.WidgetSandboxMessage;
import org.telegram.utils.twidget.WidgetSandboxSession;
import org.telegram.utils.twidget.WidgetSandboxTheme;
import org.telegram.utils.twidget.WidgetText;

public final class RichWidgetBlock extends RichMessageLayout.RichBlock
        implements WidgetSandboxPlayer.Host, NotificationCenter.NotificationCenterDelegate {
    public final TL_iv.pageBlockEmbed embed;
    public WidgetSandboxMessage message;
    public final boolean first;
    private int themeSignature;
    private final Path clip = new Path();
    private final float[] radii = new float[8];
    private static final AtomicInteger serials = new AtomicInteger();
    public final int serial = serials.incrementAndGet();
    private WidgetSandboxPlayer player;
    private final boolean generatedWidget;
    private MessagesStorage.WidgetStateToken widgetStateToken;
    private String boundUrl;
    private boolean attached, refreshingWidget, layoutRefreshRequested;
    private TL_iv.RichMessage layoutRefreshTarget;
    private boolean running;
    private static final long SETTLE_MS = 1500, SETTLE_DELAY_MS = 200;
    private long runSince;
    private boolean loadedHeight;
    private final Runnable settledResize = this::onSettled;
    private int laidOutHeight, bleedLeft;
    private String heightSource;
    private WidgetText selectable;
    private TextSelectionHelper.TextLayoutBlock[] texts;
    private boolean resizedAway;
    private static final long RESIZE_ANIMATION_WAIT_MS = 250;
    private long resizeAnimationSince;
    private boolean resizeAnimating;
    private boolean timeOver;
    private float drawnBottom;

    public static boolean supports(TL_iv.pageBlockEmbed embed) {
        return embed != null
                && embed.html != null
                && WidgetMedia.isWidgetUrl(embed.url);
    }

    public static int contentWidth(TL_iv.pageBlockEmbed embed, int maxWidth) {
        if (!WidgetMedia.isWidgetUrl(embed.url)) return maxWidth;
        return WidgetMedia.widgetWidthPx(embed.url, maxWidth + 2 * bleed()) - 2 * bleed();
    }

    public static int bleed() {
        int extra = SharedConfig.bubbleRadius >= 15 ? dp(2) : SharedConfig.bubbleRadius >= 11 ? dp(1) : 0;
        return dp(9) + extra - dp(2);
    }

    public RichWidgetBlock(
            RichMessageLayout root, Rect padding, int maxWidth, TL_iv.pageBlockEmbed embed,
            boolean first) {
        super(root, padding, maxWidth);
        this.embed = embed;
        this.first = first;
        generatedWidget = root.messageObject != null && root.messageObject.messageOwner != null
                && root.messageObject.messageOwner.rich_message == null
                && MessageObject.getMedia(root.messageObject.messageOwner) instanceof TLRPC.TL_messageMediaWidget;
        widgetStateToken = MessagesStorage.getInstance(root.currentAccount).getWidgetStateToken();
        boundUrl = embed.url;
        this.message = WidgetMedia.find(boundUrl);
        laidOutHeight = contentHeight();
        heightSource = WidgetMedia.heightSource(embed.url, message, widthPx());
        if (heightSource.equals("placeholder") || heightSource.startsWith("cache of width")) WidgetMedia.measure(embed.url, widthPx(), root.isOut());
        RichWidgetBlock previous = WidgetMedia.registered(embed.url);
        if (previous != null && previous != this && previous.laidOutHeight != laidOutHeight) {
            logHeight("new layout, width " + css(previous.widthPx()) + " -> " + css(widthPx()), previous.laidOutHeight, previous.heightSource, false);
        }
        WidgetMedia.register(embed.url, this);
        estimateSafeArea();
    }

    private void estimateSafeArea() {
        org.telegram.messenger.MessageObject object = root.messageObject;
        if (message == null || object == null || object.messageOwner == null) return;
        boolean over = !(object.isCompactMode() && !object.isOutOwner())
                && (android.text.TextUtils.isEmpty(object.messageOwner.message) || object.messageOwner.invert_media);
        if (!over) {
            message.setSafeArea(0, 0, false);
            return;
        }
        boolean edited = !object.scheduled && !object.messageOwner.edit_hide
                && ((object.messageOwner.flags & org.telegram.tgnet.TLRPC.MESSAGE_FLAG_EDITED) != 0 || object.isEditing());
        CharSequence time = ChatMessageCell.timeText(object, object, edited, object.messageOwner.edit_date);
        TextPaint paint = Theme.chat_timePaint;
        if (paint == null) {
            paint = new SystemTextPaint(Paint.ANTI_ALIAS_FLAG);
            paint.setTextSize(dp(12));
        }
        int width = (int) Math.ceil(Layout.getDesiredWidth(time, paint));
        if ((object.messageOwner.flags & org.telegram.tgnet.TLRPC.MESSAGE_FLAG_HAS_VIEWS) != 0 && Theme.chat_msgInViewsDrawable != null) {
            String views = LocaleController.formatShortNumber(Math.max(1, object.messageOwner.views), null);
            Drawable icon = Theme.chat_msgInViewsDrawable;
            width += (int) Math.ceil(paint.measureText(views)) + icon.getIntrinsicWidth() * (paint.getTextSize() - dp(2)) / icon.getIntrinsicHeight() + dp(10);
        }
        message.setSafeArea(cssCeil(timeBand()), cssCeil(timeCorner(width, object.isOutOwner())), false);
    }

    public static int timeBand() {
        return dp(23);
    }

    public static int timeCorner(int timeWidth, boolean out) {
        return dp(6) + timeWidth + 2 * timePadding() + (out ? dp(20) : 0) + dp(4);
    }

    public static int timePadding() {
        return dp(SharedConfig.bubbleRadius >= 10 ? 6 : 4);
    }

    public void setSafeArea(int bottom, int corner) {
        timeOver = bottom > 0;
        if (message != null) message.setSafeArea(cssCeil(bottom), cssCeil(corner), true);
    }

    private static float cssCeil(int px) {
        return (float) Math.ceil(px / AndroidUtilities.density - 0.01f);
    }

    public float drawnRight() {
        return padding.left - bleedLeft + widthPx();
    }

    public float drawnBottom() {
        return padding.top + drawnBottom;
    }

    public int widthPx() {
        return maxWidth + 2 * bleed();
    }

    private static boolean shownInList(View cell) {
        if (!(cell.getParent() instanceof androidx.recyclerview.widget.RecyclerView)) return false;
        View list = (View) cell.getParent();
        return cell.getBottom() > list.getPaddingTop() && cell.getTop() < list.getHeight() - list.getPaddingBottom();
    }

    private boolean anchorBottom() {
        return WidgetMedia.anchorBottom(boundUrl, message);
    }

    private int contentHeight() {
        return WidgetMedia.contentHeightPx(boundUrl, message, widthPx());
    }

    @Override
    public int getHeight() {
        return padding.top + laidOutHeight + padding.bottom;
    }

    public void onResizedAway(String reason) {
        if (view != null) {
            boolean drawn = message != null && message.heightPx() > 0;
            if (!drawn && contentHeight() != laidOutHeight) relayout(false, "on screen: " + reason);
            return;
        }
        int height = contentHeight();
        if (height == laidOutHeight) return;
        int from = laidOutHeight;
        String fromSource = heightSource;
        laidOutHeight = height;
        heightSource = WidgetMedia.heightSource(embed.url, message, widthPx());
        logHeight("away: " + reason, from, fromSource, false);
        root.reposition();
        if (root.messageObject != null) root.messageObject.forceUpdate = true;
        resizedAway = true;
    }

    @Override
    protected void onAttachedToWindow() {
        attached = true;
        NotificationCenter center = NotificationCenter.getInstance(root.currentAccount);
        center.addObserver(this, NotificationCenter.onDatabaseReset);
        center.addObserver(this, NotificationCenter.onDatabaseOpened);
        center.addObserver(this, NotificationCenter.appDidLogout);
        if (!refreshWidget() && message == null && boundUrl != null && widgetStateToken.isCurrent()) {
            message = WidgetMedia.find(boundUrl);
            if (message != null) estimateSafeArea();
        }
        if (message != null) bindWidget();
    }

    private boolean refreshWidget() {
        if (refreshingWidget) return message != null;
        refreshingWidget = true;
        try {
            TL_iv.pageBlockEmbed current = generatedWidget ? root.messageObject.getWidgetEmbed() : null;
            String url = generatedWidget ? (current == null ? null : current.url)
                    : widgetStateToken.isCurrent() ? embed.url : null;
            if (generatedWidget) refreshLayout(root.messageObject.getDisplayRichMessage());
            if (java.util.Objects.equals(boundUrl, url) && (url == null || widgetStateToken.isCurrent())) return message != null;
            String previous = boundUrl;
            stop();
            if (player != null) player.unbind();
            message = null;
            boundUrl = null;
            AndroidUtilities.cancelRunOnUIThread(settledResize);
            boolean hadText = texts != null;
            selectable = null;
            texts = null;
            if (hadText) root.reposition();
            if (previous != null) WidgetMedia.detach(previous, this);
            if (url == null) return false;
            widgetStateToken = MessagesStorage.getInstance(root.currentAccount).getWidgetStateToken();
            if (!widgetStateToken.isCurrent()) return false;
            boundUrl = url;
            embed.url = url;
            message = WidgetMedia.find(url);
            if (message == null) return false;
            estimateSafeArea();
            if (attached) bindWidget();
            return true;
        } finally {
            refreshingWidget = false;
        }
    }

    private void bindWidget() {
        if (!attached || message == null || boundUrl == null || !widgetStateToken.isCurrent()) return;
        if (player == null) player = new WidgetSandboxPlayer(this, AndroidUtilities.density);
        if (WidgetMedia.attach(boundUrl, this)) {
            if (!running) run();
        } else {
            stop();
            player.preview(message);
        }
    }

    private void refreshLayout(TL_iv.RichMessage rich) {
        MessageObject object = root.messageObject;
        if (!attached || object == null || rich == null || object.richLayout != root || rich == root.richMessage
                || layoutRefreshRequested && layoutRefreshTarget == rich) return;
        layoutRefreshRequested = true;
        layoutRefreshTarget = rich;
        AndroidUtilities.runOnUIThread(() -> {
            if (!attached || object.richLayout != root || object.getDisplayRichMessage() != rich) {
                if (layoutRefreshTarget == rich) layoutRefreshRequested = false;
                return;
            }
            View host = view;
            ChatMessageCell cell = root.getCell();
            ChatMessageCell.ChatMessageCellDelegate delegate = root.getDelegate();
            object.generateLayout(null);
            object.forceUpdate = true;
            if (cell != null && delegate != null) delegate.forceUpdateNoAnimation(cell, false, false);
            else if (host != null) host.requestLayout();
            if (host != null) host.invalidate();
        });
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (!attached || account != root.currentAccount) return;
        refreshWidget();
        if (view != null) view.invalidate();
    }

    private void run() {
        if (running || !attached || message == null || boundUrl == null || !widgetStateToken.isCurrent()) return;
        running = true;
        runSince = android.os.SystemClock.uptimeMillis();
        loadedHeight = message.hasLoadedFrame();
        message.setWidthPx(widthPx());
        updateTheme();
        player.bind(message);
        player.setVisible(true);
        WidgetMedia.register(boundUrl, this);
        WidgetMedia.onShown(message);
        boolean stale = resizedAway && root.messageObject != null && root.messageObject.forceUpdate;
        resizedAway = false;
        if (stale || contentHeight() != laidOutHeight) {
            WidgetSandboxMessage expected = message;
            String url = boundUrl;
            AndroidUtilities.runOnUIThread(() -> {
                if (attached && message == expected && java.util.Objects.equals(boundUrl, url) && widgetStateToken.isCurrent())
                    relayout(false, stale ? "shown after a change away" : "shown, height differs");
            });
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        attached = false;
        NotificationCenter center = NotificationCenter.getInstance(root.currentAccount);
        center.removeObserver(this, NotificationCenter.onDatabaseReset);
        center.removeObserver(this, NotificationCenter.onDatabaseOpened);
        center.removeObserver(this, NotificationCenter.appDidLogout);
        if (player == null) return;
        stop();
        player.unbind();
        if (boundUrl != null) WidgetMedia.detach(boundUrl, this);
    }

    private void stop() {
        if (!running) return;
        running = false;
        AndroidUtilities.cancelRunOnUIThread(settledResize);
        player.setVisible(false);
        player.unbind();
        if (message != null) WidgetMedia.onHidden(message);
    }

    public void restartWidget() {
        if (!running || message == null) return;
        stop();
        run();
    }
    public void demote() {
        if (!running || player == null) return;
        stop();
        if (widgetStateToken.isCurrent()) player.preview(message);
        else player.unbind();
        if (view != null) view.invalidate();
    }

    public void promote() {
        if (!attached || view == null || !refreshWidget()) return;
        bindWidget();
        view.invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!refreshWidget() || player == null) return;
        if (themeSignature != WidgetSandboxTheme.signature(resourcesProvider(), root.isOut())) updateTheme();
        bleedLeft = isInQuote() ? 0 : Math.max(0, Math.min(bleed(), root.padLeft - dp(2)));
        int width = widthPx();
        float top = 0, bottom = laidOutHeight, shift = 0;
        ChatMessageTransitionParams transition = resizeAnimationSince != 0 && root.getCell() != null ? root.getCell().getTransitionParams() : null;
        if (transition != null && transition.animateBackgroundBoundsInner) {
            resizeAnimating = true;
            top += transition.deltaTop;
            bottom += transition.deltaBottom;
            shift = anchorBottom() ? transition.deltaBottom : transition.deltaTop;
        } else if (transition != null && (resizeAnimating || android.os.SystemClock.uptimeMillis() - resizeAnimationSince > RESIZE_ANIMATION_WAIT_MS)) {
            resizeAnimationSince = 0;
            resizeAnimating = false;
        }
        if (anchorBottom()) shift += laidOutHeight - contentHeight();
        drawnBottom = bottom;
        canvas.save();
        canvas.translate(-bleedLeft, 0);
        clip.rewind();
        cornerRadii();
        AndroidUtilities.rectTmp.set(0, top, width, bottom);
        clip.addRoundRect(AndroidUtilities.rectTmp, radii, Path.Direction.CW);
        canvas.clipPath(clip);
        canvas.translate(0, shift);
        if (player.draw(canvas, width) && view != null) view.postInvalidateOnAnimation();
        canvas.restore();
    }

    private void updateTheme() {
        Theme.ResourcesProvider provider = resourcesProvider();
        themeSignature = WidgetSandboxTheme.signature(provider, root.isOut());
        message.setTheme(WidgetSandboxTheme.build(provider, root.isOut()));
    }

    private Theme.ResourcesProvider resourcesProvider() {
        ChatMessageCell cell = root.getCell();
        return cell != null ? cell.getResourcesProvider() : null;
    }

    @Override
    protected TextSelectionHelper.TextLayoutBlock[] getText() {
        return widgetStateToken.isCurrent() ? texts : null;
    }

    private void syncText() {
        WidgetSandboxSession.Frame frame = message == null ? null : message.frame();
        WidgetText text = frame == null ? null : frame.text;
        if (text == selectable) return;
        selectable = text;
        if (text == null) {
            texts = null;
        } else {
            texts = new TextSelectionHelper.TextLayoutBlock[text.paragraphs.size()];
            for (int i = 0; i < texts.length; i++) texts[i] = new ParagraphBlock(text.paragraphs.get(i));
        }
        root.reposition();
    }

    private final class ParagraphBlock implements TextSelectionHelper.TextLayoutBlock {
        private final WidgetText.Paragraph paragraph;

        ParagraphBlock(WidgetText.Paragraph paragraph) {
            this.paragraph = paragraph;
        }

        @Override
        public android.text.Layout getLayout() {
            return paragraph.layout();
        }

        @Override
        public int getX() {
            return layoutX - bleedLeft + paragraph.left;
        }

        @Override
        public int getY() {
            return layoutY + paragraph.top;
        }

        @Override
        public int getRow() {
            return layoutRow;
        }
    }

    public View hostView() {
        return view;
    }

    private void cornerRadii() {
        int radius = SharedConfig.bubbleRadius > 2 ? dp(SharedConfig.bubbleRadius - 2) : dp(SharedConfig.bubbleRadius);
        int near = Math.min(dp(3), radius);
        float topLeft = first && !root.hasNameOffset() && (root.isOut() || !root.isPinnedTop()) ? radius : near;
        float topRight = first && (!root.isOut() || !root.isPinnedTop()) ? radius : near;
        radii[0] = radii[1] = topLeft;
        radii[2] = radii[3] = topRight;
        float bottomLeft = timeOver && root.isOut() ? radius : near;
        float bottomRight = timeOver && !root.isOut() ? radius : near;
        radii[4] = radii[5] = bottomRight;
        radii[6] = radii[7] = bottomLeft;
    }

    public void onHoverEvent(MotionEvent event) {
        if (!refreshWidget() || player == null || !running) return;
        event.offsetLocation(-padding.left + bleedLeft, -padding.top);
        try {
            boolean inside = event.getX() >= 0 && event.getX() < widthPx() && event.getY() >= 0 && event.getY() < laidOutHeight;
            player.onHoverEvent(event, inside);
        } finally {
            event.offsetLocation(padding.left - bleedLeft, padding.top);
        }
    }

    @Override
    public void setCursor(String cursor) {
        if (view != null && android.os.Build.VERSION.SDK_INT >= 24) Cursors.set(view, cursor);
    }

    @androidx.annotation.RequiresApi(24)
    private static final class Cursors {
        static void set(View view, String cursor) {
            int type;
            switch (cursor) {
                case "pointer": type = android.view.PointerIcon.TYPE_HAND; break;
                case "text": type = android.view.PointerIcon.TYPE_TEXT; break;
                case "vertical-text": type = android.view.PointerIcon.TYPE_VERTICAL_TEXT; break;
                case "crosshair": type = android.view.PointerIcon.TYPE_CROSSHAIR; break;
                case "move":
                case "all-scroll": type = android.view.PointerIcon.TYPE_ALL_SCROLL; break;
                case "grab": type = android.view.PointerIcon.TYPE_GRAB; break;
                case "grabbing": type = android.view.PointerIcon.TYPE_GRABBING; break;
                case "help": type = android.view.PointerIcon.TYPE_HELP; break;
                case "wait":
                case "progress": type = android.view.PointerIcon.TYPE_WAIT; break;
                case "copy": type = android.view.PointerIcon.TYPE_COPY; break;
                case "alias": type = android.view.PointerIcon.TYPE_ALIAS; break;
                case "cell": type = android.view.PointerIcon.TYPE_CELL; break;
                case "context-menu": type = android.view.PointerIcon.TYPE_CONTEXT_MENU; break;
                case "not-allowed":
                case "no-drop": type = android.view.PointerIcon.TYPE_NO_DROP; break;
                case "zoom-in": type = android.view.PointerIcon.TYPE_ZOOM_IN; break;
                case "zoom-out": type = android.view.PointerIcon.TYPE_ZOOM_OUT; break;
                case "ew-resize":
                case "col-resize":
                case "e-resize":
                case "w-resize": type = android.view.PointerIcon.TYPE_HORIZONTAL_DOUBLE_ARROW; break;
                case "ns-resize":
                case "row-resize":
                case "n-resize":
                case "s-resize": type = android.view.PointerIcon.TYPE_VERTICAL_DOUBLE_ARROW; break;
                case "nesw-resize":
                case "ne-resize":
                case "sw-resize": type = android.view.PointerIcon.TYPE_TOP_RIGHT_DIAGONAL_DOUBLE_ARROW; break;
                case "nwse-resize":
                case "nw-resize":
                case "se-resize": type = android.view.PointerIcon.TYPE_TOP_LEFT_DIAGONAL_DOUBLE_ARROW; break;
                case "none": type = android.view.PointerIcon.TYPE_NULL; break;
                default:
                    view.setPointerIcon(null);
                    return;
            }
            view.setPointerIcon(android.view.PointerIcon.getSystemIcon(view.getContext(), type));
        }
    }

    @Override
    protected boolean onTouchEvent(MotionEvent event) {
        if (!refreshWidget() || player == null || !running) return false;
        if (WidgetMedia.isPreview(boundUrl)) return false;
        event.offsetLocation(bleedLeft, 0);
        try {
            return player.onTouchEvent(event);
        } finally {
            event.offsetLocation(-bleedLeft, 0);
        }
    }

    @Override
    public boolean isHorizontallyDragging() {
        return player != null && running && player.blocksMessageMenu();
    }

    public boolean mayGiveSwipe() {
        return player != null && running && player.mayGiveSwipe();
    }

    public long lastDownTime() {
        return player != null ? player.lastDownTime() : 0;
    }

    private void relayout(boolean animated, String reason) {
        int height = contentHeight();
        boolean changed = height != laidOutHeight;
        int from = laidOutHeight;
        String fromSource = heightSource;
        laidOutHeight = height;
        heightSource = WidgetMedia.heightSource(embed.url, message, widthPx());
        if (changed) logHeight(reason, from, fromSource, animated);
        if (changed) root.reposition();
        if (changed && animated) {
            resizeAnimationSince = android.os.SystemClock.uptimeMillis();
            resizeAnimating = false;
        }
        if (root.messageObject != null) root.messageObject.forceUpdate = true;
        ChatMessageCell cell = root.getCell();
        ChatMessageCell.ChatMessageCellDelegate delegate = root.getDelegate();
        if (view != null && cell != null && delegate != null) {
            boolean keepTop = changed && !anchorBottom() && shownInList(cell);
            if (animated) delegate.forceUpdate(cell, keepTop, keepTop);
            else delegate.forceUpdateNoAnimation(cell, keepTop, keepTop);
        } else if (view != null) view.requestLayout();
    }

    @Override
    public void onWidgetResized() {
        if (!refreshWidget()) return;
        AndroidUtilities.cancelRunOnUIThread(settledResize);
        if (!loadedHeight) {
            if (!message.hasLoadedFrame()) {
                AndroidUtilities.runOnUIThread(settledResize, SETTLE_MS);
                return;
            }
            loadedHeight = true;
            runSince = android.os.SystemClock.uptimeMillis();
            if (contentHeight() != laidOutHeight) relayout(true, "on screen, loaded");
            return;
        }
        if (android.os.SystemClock.uptimeMillis() - runSince < SETTLE_MS) {
            AndroidUtilities.runOnUIThread(settledResize, SETTLE_DELAY_MS);
            return;
        }
        if (contentHeight() != laidOutHeight) relayout(true, "on screen");
    }

    private void onSettled() {
        if (running && contentHeight() != laidOutHeight) relayout(true, "on screen, settled after start");
    }

    private void logHeight(String reason, int from, String fromSource, boolean animated) {
        if (!BuildVars.LOGS_ENABLED) return;
        long age = running ? android.os.SystemClock.uptimeMillis() - runSince : -1;
        long touch = player != null ? player.msSinceTouch() : -1;
        FileLog.d("widget height " + embed.url.substring(WidgetMedia.URL_PREFIX.length())
                + ": " + reason
                + ", " + css(from) + " (" + fromSource + ") -> " + css(laidOutHeight) + " (" + heightSource + ")"
                + ", width " + css(widthPx())
                + (animated ? ", animated" : "")
                + (view != null ? ", on screen" : ", off screen")
                + (age >= 0 ? ", running " + age + " ms" : ", not running")
                + (message != null ? ", session " + message.sessions() + (message.hasSnapshot() ? " (snapshot)" : "")
                        + (message.scriptsRunning() ? "" : ", scripts held") : ", no widget yet")
                + (touch >= 0 && touch < 5000 ? ", touched " + touch + " ms ago" : ""));
    }

    private static int css(int px) {
        return Math.round(px / AndroidUtilities.density);
    }

    @Override
    public void invalidateWidget() {
        if (!refreshWidget()) return;
        syncText();
        if (view != null) view.invalidate();
    }

    @Override
    public void onBubbleHold() {
        ChatMessageCell cell = root.getCell();
        if (cell != null) cell.onWidgetHold();
    }

    @Override
    public void disallowParentIntercept(boolean disallow) {
        requestDisallowParentIntercept(disallow);
    }
}
