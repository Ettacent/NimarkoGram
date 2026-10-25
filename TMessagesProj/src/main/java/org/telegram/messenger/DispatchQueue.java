/* Modifications Copyright (C) 2026 Ettacent */

/*
 * This is the source code of Telegram for Android v. 7.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2020.
 */

package org.telegram.messenger;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;

public class DispatchQueue extends Thread {

    private static final int THREAD_PRIORITY_DEFAULT = -1000;

    private volatile Handler handler = null;
    private CountDownLatch syncLatch = new CountDownLatch(1);
    private long lastTaskTime;
    private static int indexPointer = 0;
    public final int index = indexPointer++;
    private int threadPriority = THREAD_PRIORITY_DEFAULT;

    private Runnable initialTask;
    private volatile boolean asynchronousStartup;
    private ArrayList<PendingTask> pendingTasks;

    private static final class PendingTask {
        final Runnable runnable;
        final long delay;

        PendingTask(Runnable runnable, long delay) {
            this.runnable = runnable;
            this.delay = delay;
        }
    }
    public DispatchQueue(final String threadName) {
        this(threadName, true);
    }

    public DispatchQueue(final String threadName, boolean start) {
        setName(threadName);
        if (start) {
            start();
        }
    }

    public DispatchQueue(final String threadName, boolean start, int priority) {
        this.threadPriority = priority;
        setName(threadName);
        if (start) {
            start();
        }
    }

    public synchronized void start(Runnable initialTask) {
        if (getState() != State.NEW) {
            throw new IllegalThreadStateException();
        }
        this.initialTask = initialTask;
        asynchronousStartup = true;
        if (initialTask != null) {
            lastTaskTime = SystemClock.elapsedRealtime();
        }
        super.start();
    }

    public void sendMessage(Message msg, int delay) {
        try {
            syncLatch.await();
            if (delay <= 0) {
                handler.sendMessage(msg);
            } else {
                handler.sendMessageDelayed(msg, delay);
            }
        } catch (Exception ignore) {

        }
    }

    public void cancelRunnable(Runnable runnable) {
        try {
            syncLatch.await();
            handler.removeCallbacks(runnable);
        } catch (Exception e) {
            FileLog.e(e, false);
        }
    }

    public void cancelRunnables(Runnable[] runnables) {
        try {
            syncLatch.await();
            for (int i = 0; i < runnables.length; i++) {
                handler.removeCallbacks(runnables[i]);
            }
        } catch (Exception e) {
            FileLog.e(e, false);
        }
    }

    public boolean postRunnable(Runnable runnable) {
        lastTaskTime = SystemClock.elapsedRealtime();
        return postRunnable(runnable, 0);
    }

    public boolean postToFrontRunnable(Runnable runnable) {
        try {
            syncLatch.await();
        } catch (Exception e) {
            FileLog.e(e, false);
        }
        return handler.postAtFrontOfQueue(runnable);
    }

    public boolean postRunnable(Runnable runnable, long delay) {
        if (asynchronousStartup) {
            if (handler == null && enqueueBeforeReady(runnable, delay)) {
                return true;
            }
        } else {
        try {
            syncLatch.await();
        } catch (Exception e) {
            FileLog.e(e, false);
            }
        }
        if (delay <= 0) {
            return handler.post(runnable);
        } else {
            return handler.postDelayed(runnable, delay);
        }
    }

    private synchronized boolean enqueueBeforeReady(Runnable runnable, long delay) {
        if (!asynchronousStartup || handler != null) {
            return false;
        }
        if (pendingTasks == null) {
            pendingTasks = new ArrayList<>();
        }
        pendingTasks.add(new PendingTask(runnable, delay));
        return true;
    }

    public void cleanupQueue() {
        try {
            syncLatch.await();
            handler.removeCallbacksAndMessages(null);
        } catch (Exception e) {
            FileLog.e(e, false);
        }
    }

    public void handleMessage(Message inputMessage) {

    }

    public long getLastTaskTime() {
        return lastTaskTime;
    }

    public void recycle() {
        handler.getLooper().quit();
    }

    @Override
    public void run() {
        Looper.prepare();
        Handler preparedHandler = new Handler(Looper.myLooper(), msg -> {
            DispatchQueue.this.handleMessage(msg);
            return true;
        });
        synchronized (this) {
            if (initialTask != null) {
                preparedHandler.post(initialTask);
                initialTask = null;
            }
            if (pendingTasks != null) {
                for (PendingTask task : pendingTasks) {
                    if (task.delay <= 0) {
                        preparedHandler.post(task.runnable);
                    } else {
                        preparedHandler.postDelayed(task.runnable, task.delay);
                    }
                }
                pendingTasks = null;
            }
            handler = preparedHandler;
        }
        syncLatch.countDown();
        if (threadPriority != THREAD_PRIORITY_DEFAULT) {
            Process.setThreadPriority(threadPriority);
        }
        Looper.loop();
    }

    public boolean isReady() {
        return syncLatch.getCount() == 0;
    }

    public Handler getHandler() {
        return handler;
    }
}
