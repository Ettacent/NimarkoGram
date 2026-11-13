/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.utils.twidget;

import org.telegram.utils.twidget.IWidgetEngineCallback;

interface IWidgetEngine {
    long create(in byte[] html, in byte[] theme, float width, float density, int cpuMultiplier, in byte[] locale,
            in byte[] storage, in byte[] sdk, in byte[] stoppedLabel, boolean visible, IWidgetEngineCallback callback);

    oneway void destroy(long session);
    oneway void visible(long session, boolean visible);
    oneway void theme(long session, in byte[] theme);
    oneway void width(long session, float width);
    oneway void data(long session, in byte[] data);
    oneway void safeArea(long session, in byte[] area);
    oneway void answerRequest(long session, long request, int failure, in byte[] result);
    oneway void repaint(long session);
    oneway void tick(long session, float timeMs, boolean frame, boolean animate, boolean paint);
    oneway void touch(long session, int action, float x, float y, int pointer, boolean primary, boolean mouse,
            int modifiers, int button);
    oneway void hover(long session, int action, float x, float y, int modifiers);

    oneway void warmup();

    void debug(boolean conformance, boolean attackScan, in byte[] limits);
}
