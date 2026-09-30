package org.telegram.ui.Components;
import android.content.Context;
import android.graphics.Canvas;
import android.text.TextUtils;
import org.telegram.messenger.SharedConfig;
public final class ProxyCheckStatusView extends ButtonSpan.TextViewButtons {
    private final MessagePreviewCrossfade crossfade = new MessagePreviewCrossfade(this);
    public ProxyCheckStatusView(Context context) {
        super(context);
    }
    public void setStatus(CharSequence text, int color) {
        if (TextUtils.equals(getText(), text) && getCurrentTextColor() == color) {
            return;
        }
        captureStatus(true);
        clear();
        setText(text);
        setTextColor(color);
    }
    public void setStatusText(CharSequence text, boolean animated) {
        if (TextUtils.equals(getText(), text)) {
            return;
        }
        captureStatus(animated);
        setText(text);
    }
    private void captureStatus(boolean animated) {
        if (animated && SharedConfig.animationsEnabled() && isShown() && getWidth() > 0
                && !TextUtils.isEmpty(getText())) {
            crossfade.capture(this::drawStatus);
        } else {
            crossfade.finish();
        }
    }
    public void resetTransition() {
        crossfade.finish();
    }
    private void drawStatus(Canvas canvas) {
        super.onDraw(canvas);
    }
    @Override
    protected void onDraw(Canvas canvas) {
        crossfade.draw(canvas, this::drawStatus);
    }
    @Override
    protected void onDetachedFromWindow() {
        crossfade.finish();
        super.onDetachedFromWindow();
    }
}
