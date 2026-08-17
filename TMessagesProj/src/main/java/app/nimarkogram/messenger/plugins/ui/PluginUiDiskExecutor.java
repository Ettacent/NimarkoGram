package app.nimarkogram.messenger.plugins.ui;

import android.os.Process;

import org.telegram.messenger.FileLog;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class PluginUiDiskExecutor {
    static final int MAX_QUEUED_OPERATIONS = 16;

    private static final AtomicInteger THREAD_IDS = new AtomicInteger();
    private static final ThreadPoolExecutor EXECUTOR = new ThreadPoolExecutor(
            1,
            1,
            30L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(MAX_QUEUED_OPERATIONS),
            new IoThreadFactory(),
            new ThreadPoolExecutor.AbortPolicy());

    static {
        EXECUTOR.allowCoreThreadTimeOut(true);
    }

    private PluginUiDiskExecutor() {
    }

    public static boolean execute(String operation, Runnable runnable) {
        if (runnable == null) {
            return false;
        }
        try {
            EXECUTOR.execute(() -> {
                try {
                    runnable.run();
                } catch (Throwable failure) {
                    FileLog.e(
                            "nimarko: plugin UI disk I/O failed: "
                                    + operation,
                            failure);
                }
            });
            return true;
        } catch (RejectedExecutionException rejected) {
            FileLog.e(
                    "nimarko: plugin UI disk I/O queue is full: "
                            + operation,
                    rejected);
            return false;
        }
    }

    private static final class IoThreadFactory implements ThreadFactory {
        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(() -> {
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                runnable.run();
            }, "PluginUiDisk-" + THREAD_IDS.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        }
    }
}
