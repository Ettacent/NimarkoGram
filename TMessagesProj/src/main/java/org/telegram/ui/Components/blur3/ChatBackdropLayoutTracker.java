package org.telegram.ui.Components.blur3;

import java.util.ArrayList;


public final class ChatBackdropLayoutTracker {
    private static final class Anchor {
        long dialogId;
        int messageId;
        float y;
    }

    private final ArrayList<Anchor> anchors = new ArrayList<>();
    private int count;
    private boolean tracking;
    private boolean matched;
    private float displacement;
    private float reportedScroll;

    public boolean isTracking() {
        return tracking;
    }

    public void begin() {
        tracking = true;
        count = 0;
        matched = false;
        displacement = reportedScroll = 0;
    }

    public void before(long dialogId, int messageId, float y) {
        if (!tracking || messageId == 0) return;
        if (count == anchors.size()) anchors.add(new Anchor());
        Anchor anchor = anchors.get(count++);
        anchor.dialogId = dialogId;
        anchor.messageId = messageId;
        anchor.y = y;
    }

    public void onScrolled(int dy) {
        if (tracking) reportedScroll += dy;
    }

    public void after(long dialogId, int messageId, float y) {
        if (!tracking || matched || messageId == 0) return;
        for (int i = 0; i < count; i++) {
            Anchor anchor = anchors.get(i);

            if (anchor.dialogId == dialogId && anchor.messageId == messageId) {
                displacement = anchor.y - y;
                matched = true;
                return;
            }
        }
    }

    public float finish() {


        float result = tracking && matched ? displacement - reportedScroll : 0;
        tracking = false;
        count = 0;
        return result;
    }
}
