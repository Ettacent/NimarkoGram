/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components;

public final class RecyclerPagerModel {
    private RecyclerPagerModel() {}

    public interface ScrollHost {
        float distanceToTabs();
        int scrollCommon(int dy);
        int scrollPage(int dy);
    }

    public static int preScroll(ScrollHost host, boolean commonSource, int dy) {
        if (commonSource) {
            boolean pageConsumesFirst = dy < 0 || host.distanceToTabs() <= 0.5f;
            if (pageConsumesFirst) {
                return host.scrollPage(dy);
            }
            return 0;
        }
        if (dy > 0) {
            float distanceToPin = host.distanceToTabs();
            int commonRequest = Float.isInfinite(distanceToPin)
                    ? dy : Math.min(dy, Math.max(0, Math.round(distanceToPin)));
            return host.scrollCommon(commonRequest);
        }
        return 0;
    }

    public static int postScroll(ScrollHost host, boolean commonSource, int sourceConsumed, int unconsumed) {
        if (commonSource && sourceConsumed > 0 && unconsumed > 0) {
            return host.scrollPage(unconsumed);
        }
        if (!commonSource && unconsumed < 0) {
            return host.scrollCommon(unconsumed);
        }
        return 0;
    }

    public static float collapse(float pageOffset, float pinTop, float hiddenTop) {
        float maximumCollapse = Math.max(0f, pinTop - hiddenTop);
        return Math.max(0f, Math.min(pageOffset, maximumCollapse));
    }

    public static float pageTranslation(float boundaryTop, float pinTop) {
        return Math.max(0f, boundaryTop - pinTop);
    }

    public static final class Transition {
        private float sourceBoundary;
        private float sourceTail;
        private float targetTail;
        private float pin;
        private float progress;
        private boolean sourceHidden;

        public void begin(float boundaryTop, float pageOffset, float pinTop, float hiddenTop) {
            sourceBoundary = boundaryTop;
            pin = pinTop;
            sourceTail = boundaryTop - collapse(pageOffset, pinTop, hiddenTop);
            targetTail = Math.max(boundaryTop, pinTop);
            sourceHidden = sourceTail <= hiddenTop + 0.5f;
            progress = 0f;
        }

        public void setProgress(float value) {
            progress = Math.max(0f, Math.min(1f, value));
        }
        public float getProgress() {
            return progress;
        }
        public boolean wasCommonHidden() {
            return sourceHidden;
        }
        public float commonTail() {
            return sourceHidden ? targetTail : sourceTail + (targetTail - sourceTail) * progress;
        }
        public float tabsTop() {
            return Math.max(pin, commonTail());
        }
        public float commonTranslationX(float incomingPageX) {
            return sourceHidden ? incomingPageX : 0f;
        }
        public float incomingPageTranslation() {
            return tabsTop() - pin;
        }
        public float outgoingPageTranslation() {
            return pageTranslation(sourceBoundary, pin) + (sourceHidden ? 0f : commonTail() - sourceTail);
        }
    }
}
