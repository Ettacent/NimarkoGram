/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components;

public final class NestedScrollCoupling {
    public interface Host {
        float viewportTop();
        float outerStopTop();
        float sharedStartTop();
        float innerOffset();
        int virtualOffset();
        void virtualOffset(int offset);
        int scrollOuter(int dy);
        int scrollInner(int dy);
    }

    private final Host host;

    public NestedScrollCoupling(Host host) {
        this.host = host;
    }

    private int distance(float from, float to) {
        return Math.max(0, Math.round(from - to));
    }

    private int collapse(float top) {
        return distance(host.sharedStartTop(), Math.max(host.outerStopTop(), top));
    }

    public int preScroll(boolean sourceIsOuter, int dy) {
        if (dy == 0) {
            return 0;
        }
        if (!sourceIsOuter) {
            if (dy > 0) {
                return host.scrollOuter(Math.min(dy,
                        distance(host.viewportTop(), host.sharedStartTop())));
            }
            int release = Math.min(-dy, host.virtualOffset());
            if (release > 0) {
                host.virtualOffset(host.virtualOffset() - release);
                host.scrollOuter(-Math.min(release, collapse(host.viewportTop())));
            }
            return -release;
        }
        if (dy > 0) {
            return distance(host.viewportTop(), host.outerStopTop()) == 0
                    ? host.scrollInner(dy) : 0;
        }

        int shared = collapse(host.viewportTop());
        float offset = host.innerOffset() + host.virtualOffset();
        int request = Float.isInfinite(offset) ? dy
                : -Math.min(-dy, distance(offset, shared));
        int used = host.scrollInner(request);
        if (used < 0) {
            float after = host.innerOffset() + host.virtualOffset();
            if (!Float.isInfinite(after) && after < shared) {
                used += host.scrollInner(Math.min(-used, distance(shared, after)));
            }
        }
        return used;
    }

    public int postScroll(boolean sourceIsOuter, int dyConsumed, int dyUnconsumed) {
        if (sourceIsOuter) {
            float top = host.viewportTop();
            int sharedDelta = collapse(top) - collapse(top + dyConsumed);
            if (sharedDelta > 0) {
                int real = host.scrollInner(sharedDelta);
                host.virtualOffset(host.virtualOffset() + sharedDelta - real);
            } else if (sharedDelta < 0) {
                int release = Math.min(-sharedDelta, host.virtualOffset());
                host.virtualOffset(host.virtualOffset() - release);
                host.scrollInner(sharedDelta + release);
            }
            return dyUnconsumed > 0
                    && distance(host.viewportTop(), host.outerStopTop()) == 0
                    ? host.scrollInner(dyUnconsumed) : 0;
        }

        if (dyConsumed > 0) {
            host.scrollOuter(Math.min(dyConsumed,
                    distance(host.viewportTop(), host.outerStopTop())));
        } else if (dyConsumed < 0) {
            float offset = host.innerOffset() + host.virtualOffset();
            if (!Float.isInfinite(offset)) {
                int expand = Math.min(-dyConsumed, distance(collapse(host.viewportTop()), offset));
                host.scrollOuter(-expand);
            }
        }
        if (dyUnconsumed > 0) {
            int used = host.scrollOuter(Math.min(dyUnconsumed,
                    distance(host.viewportTop(), host.outerStopTop())));
            host.virtualOffset(host.virtualOffset() + used);
            return used;
        }
        if (dyUnconsumed < 0) {
            return host.scrollOuter(dyUnconsumed);
        }
        return 0;
    }
}
