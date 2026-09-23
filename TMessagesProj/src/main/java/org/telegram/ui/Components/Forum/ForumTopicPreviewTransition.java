package org.telegram.ui.Components.Forum;


public final class ForumTopicPreviewTransition {
    private int account = -1;
    private long dialog;
    private boolean bound;
    private boolean loading;
    private boolean animating;
    private long startedAt = -1;
    private float alpha = 1f;
    private float outgoingAlpha;
    private float outgoingStartAlpha;


    public boolean bind(int account, long dialog, boolean loading, boolean animate) {
        final boolean sameOwner = bound && this.account == account && this.dialog == dialog;
        final boolean resolved = sameOwner && this.loading && !loading;
        if (!sameOwner || this.loading != loading) {
            outgoingStartAlpha = resolved && animate ? alpha : 0f;
            outgoingAlpha = outgoingStartAlpha;
            animating = animate && (loading || resolved);
            alpha = animating ? 0f : 1f;
            startedAt = -1;
        }
        this.account = account;
        this.dialog = dialog;
        this.loading = loading;
        bound = true;
        if (!animate) finish();
        return resolved && animate && outgoingStartAlpha > 0f;
    }

    public void draw(long now) {
        if (!animating) return;
        if (startedAt < 0) startedAt = now;
        final float t = Math.max(0f, Math.min(1f, (now - startedAt) / (loading ? 180f : 300f)));

        alpha = t * t * (3f - 2f * t);
        outgoingAlpha = outgoingStartAlpha * (1f - alpha);
        if (t >= 1f) finish();
    }

    public float alpha() { return alpha; }
    public float outgoingAlpha() { return outgoingAlpha; }
    public boolean isAnimating() { return animating; }

    public void finish() {
        animating = false;
        alpha = 1f;
        outgoingAlpha = outgoingStartAlpha = 0f;
        startedAt = -1;
    }

    public void reset() {
        bound = false;
        account = -1;
        dialog = 0;
        loading = false;
        finish();
    }
}
