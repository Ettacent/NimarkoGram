package org.telegram.messenger;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;

import org.telegram.ui.Components.ForegroundDetector;

public class ANRDetector implements ForegroundDetector.Listener {

    private static final long TIMEOUT_MS = 5000;

    private static final int MSG_UI_PING = 1;

    private final Object lock = new Object();

    private final Handler mainHandler;
    private final Thread detectorThread;
    private final Runnable anrDetected;

    private volatile boolean foreground;
    private volatile boolean destroyed;

    private volatile int generation;

    private int nextPingId;

    private volatile int acknowledgedPingId = -1;

    private volatile boolean anrReported;

    public ANRDetector(Runnable anrDetected) {
        this.anrDetected = anrDetected;

        mainHandler = new Handler(Looper.getMainLooper()) {
            @Override
            public void handleMessage(Message msg) {
                if (msg.what != MSG_UI_PING) {
                    return;
                }

                acknowledgedPingId = msg.arg1;

                anrReported = false;
            }
        };

        ForegroundDetector foregroundDetector = ForegroundDetector.getInstance();

        foreground = foregroundDetector.isForeground();

        foregroundDetector.addListener(this);

        detectorThread = new Thread(this::run, "ANRDetector");
        detectorThread.start();
    }

    private void run() {
        while (true) {
            final int checkGeneration;
            final int pingId;

            synchronized (lock) {

                while (!foreground && !destroyed) {
                    try {
                        lock.wait();
                    } catch (InterruptedException ignore) {
                    }
                }

                if (destroyed) {
                    return;
                }

                checkGeneration = generation;
                pingId = ++nextPingId;
            }

            Message message = mainHandler.obtainMessage(
                    MSG_UI_PING,
                    pingId,
                    checkGeneration
            );
            message.sendToTarget();

            try {
                Thread.sleep(TIMEOUT_MS);
            } catch (InterruptedException ignore) {

                continue;
            }

            if (destroyed) {
                return;
            }

            if (!foreground || generation != checkGeneration) {
                continue;
            }

            if (acknowledgedPingId == pingId) {
                continue;
            }

            if (!anrReported) {
                anrReported = true;

                try {
                    anrDetected.run();
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
        }
    }

    @Override
    public void onBecameForeground() {
        synchronized (lock) {
            if (destroyed) {
                return;
            }

            generation++;
            foreground = true;

            anrReported = false;

            lock.notifyAll();
        }

        detectorThread.interrupt();
    }

    @Override
    public void onBecameBackground() {
        synchronized (lock) {
            if (destroyed) {
                return;
            }

            generation++;
            foreground = false;
        }

        mainHandler.removeMessages(MSG_UI_PING);

        detectorThread.interrupt();
    }

    public void destroy() {
        synchronized (lock) {
            if (destroyed) {
                return;
            }

            destroyed = true;
            foreground = false;
            generation++;

            lock.notifyAll();
        }

        ForegroundDetector.getInstance().removeListener(this);

        mainHandler.removeMessages(MSG_UI_PING);

        detectorThread.interrupt();
    }
}