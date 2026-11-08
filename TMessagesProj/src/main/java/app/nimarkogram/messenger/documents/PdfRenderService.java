/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.documents;

import android.app.Service;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.pdf.PdfRenderer;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructStat;

import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PdfRenderService extends Service {
    private static final long MAX_FILE_BYTES = 256L * 1024 * 1024;
    private static final int MAX_PAGES = 10000;
    private static final int MAX_PENDING = 8;
    private final Semaphore slots = new Semaphore(2);
    private final ArrayList<Session> sessions = new ArrayList<>();
    private final Object lifecycleLock = new Object();
    private ThreadPoolExecutor worker;
    private ScheduledThreadPoolExecutor watchdog;
    private volatile boolean stopped;

    private final IDocumentRenderer.Stub broker = new IDocumentRenderer.Stub() {
        @Override
        public IPdfDocument open(ParcelFileDescriptor source, IBinder owner, String password) {
            try {
                checkCaller();
                if (source == null || owner == null || password != null && password.length() > 1024) {
                    throw error(PdfRenderClient.ERROR_FILE);
                }
                if (!slots.tryAcquire()) {
                    throw error(PdfRenderClient.ERROR_SERVICE);
                }
            } catch (RuntimeException exception) {
                closeQuietly(source);
                throw exception;
            }
            FutureTask<IPdfDocument> task = new FutureTask<>(() -> {
                PdfRenderer renderer = null;
                boolean transferred = false;
                try {
                    validateSource(source);
                    if (Build.VERSION.SDK_INT < 35 && password != null && !password.isEmpty()) {
                        throw error(PdfRenderClient.ERROR_UNSUPPORTED_PASSWORD);
                    }
                    try {
                        renderer = Build.VERSION.SDK_INT >= 35
                                ? Api35.open(source, password) : new PdfRenderer(source);
                    } catch (SecurityException exception) {
                        throw error(Build.VERSION.SDK_INT >= 35 ? PdfRenderClient.ERROR_PASSWORD
                                : PdfRenderClient.ERROR_UNSUPPORTED_PASSWORD);
                    }
                    int count = renderer.getPageCount();
                    if (count <= 0 || count > MAX_PAGES) {
                        throw error(PdfRenderClient.ERROR_FILE);
                    }
                    Session session = new Session(renderer, owner, count);
                    owner.linkToDeath(session, 0);
                    synchronized (lifecycleLock) {
                        if (stopped || session.closed.get() || !owner.isBinderAlive()) {
                            owner.unlinkToDeath(session, 0);
                            throw error(PdfRenderClient.ERROR_SERVICE);
                        }
                        sessions.add(session);
                        session.openingLease = watchdog.schedule(() -> {
                            synchronized (lifecycleLock) {
                                if (!session.acknowledged && session.closed.compareAndSet(false, true)) scheduleSweep();
                            }
                        }, 15, TimeUnit.SECONDS);
                        transferred = true;
                    }
                    return session;
                } catch (PdfRenderException exception) {
                    throw exception;
                } catch (Exception exception) {
                    throw error(PdfRenderClient.ERROR_FILE);
                } finally {
                    if (!transferred) {
                        try {
                            if (renderer != null) {
                                renderer.close();
                            }
                        } finally {
                            closeQuietly(source);
                            slots.release();
                        }
                    }
                }
            });
            try {
                execute(task);
            } catch (RuntimeException exception) {
                closeQuietly(source);
                slots.release();
                throw exception;
            }
            return await(task);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        if (!DocumentProcess.isIsolated(this)) {
            stopped = true;
            stopSelf();
            return;
        }
        watchdog = new ScheduledThreadPoolExecutor(1, runnable -> new Thread(runnable, "pdf-watchdog"));
        watchdog.setRemoveOnCancelPolicy(true);
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAX_PENDING), runnable -> new Thread(() -> {
                    Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
                    runnable.run();
                }, "pdf-renderer"));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return stopped || !DocumentProcess.isIsolated(this) ? null : broker;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        synchronized (lifecycleLock) {
            for (Session session : sessions) session.closed.set(true);
            scheduleSweep();
        }
        return false;
    }

    @Override
    public void onDestroy() {
        stopSessions();
        super.onDestroy();
    }

    private void stopSessions() {
        synchronized (lifecycleLock) {
            stopped = true;
            for (Session session : sessions) {
                session.closed.set(true);
            }
            if (worker != null && !worker.isShutdown()) {
                scheduleSweep();
                worker.shutdown();
            } else if (worker == null && watchdog != null) {
                watchdog.shutdown();
            }
        }
    }

    private void checkCaller() {
        if (stopped || !DocumentProcess.isIsolated(this)
                || Binder.getCallingUid() != getApplicationInfo().uid) {
            throw error(PdfRenderClient.ERROR_SERVICE);
        }
    }

    private void execute(Runnable task) {
        synchronized (lifecycleLock) {
            if (stopped || worker == null) {
                throw error(PdfRenderClient.ERROR_SERVICE);
            }
            try {
                worker.execute(guarded(task));
            } catch (RejectedExecutionException exception) {
                throw error(PdfRenderClient.ERROR_SERVICE);
            }
        }
    }

    private Runnable guarded(Runnable task) {
        return () -> {
            AtomicBoolean finished = new AtomicBoolean();
            ScheduledFuture<?> alarm = watchdog.schedule(() -> {
                if (finished.compareAndSet(false, true) && DocumentProcess.isIsolated(this)) {
                    Process.killProcess(Process.myPid());
                }
            }, 15, TimeUnit.SECONDS);
            try {
                task.run();
            } finally {
                try {
                    sweep();
                } finally {
                    finished.set(true);
                    alarm.cancel(false);
                    synchronized (lifecycleLock) {
                        if (stopped && worker.getQueue().isEmpty()) {
                            watchdog.shutdown();
                        }
                    }
                }
            }
        };
    }

    private void scheduleSweep() {
        synchronized (lifecycleLock) {
            if (worker != null && !worker.isShutdown()) {
                try {
                    worker.execute(guarded(() -> { }));
                } catch (RejectedExecutionException ignored) {
                }
            }
        }
    }

    private void sweep() {
        ArrayList<Session> removed = new ArrayList<>();
        synchronized (lifecycleLock) {
            for (int i = sessions.size() - 1; i >= 0; i--) {
                Session session = sessions.get(i);
                if (session.closed.get()) {
                    if (session.openingLease != null) session.openingLease.cancel(false);
                    sessions.remove(i);
                    removed.add(session);
                }
            }
        }
        for (Session session : removed) {
            try {
                try {
                    session.owner.unlinkToDeath(session, 0);
                } finally {
                    session.renderer.close();
                }
            } catch (RuntimeException ignored) {
            } finally {
                slots.release();
            }
        }
    }

    private static <T> T await(FutureTask<T> task) {
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return task.get();
                } catch (InterruptedException exception) {
                    interrupted = true;
                } catch (ExecutionException exception) {
                    Throwable cause = exception.getCause();
                    if (cause instanceof PdfRenderException) {
                        throw (PdfRenderException) cause;
                    }
                    throw error(PdfRenderClient.ERROR_SERVICE);
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static void validateSource(ParcelFileDescriptor source) throws Exception {
        StructStat stat = Os.fstat(source.getFileDescriptor());
        if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_size <= 0 || stat.st_size > MAX_FILE_BYTES
                || (Os.fcntlInt(source.getFileDescriptor(), OsConstants.F_GETFL, 0)
                & OsConstants.O_ACCMODE) != OsConstants.O_RDONLY) {
            throw error(PdfRenderClient.ERROR_FILE);
        }
        Os.lseek(source.getFileDescriptor(), 0, OsConstants.SEEK_SET);
    }

    static boolean validTile(int renderWidth, int renderHeight, int x, int y, int width, int height) {
        return renderWidth > 0 && renderHeight > 0
                && (long) renderWidth * renderHeight <= Integer.MAX_VALUE
                && x >= 0 && y >= 0 && width > 0 && height > 0 && width <= 512 && height <= 512
                && (long) x + width <= renderWidth && (long) y + height <= renderHeight;
    }

    private static PdfRenderException error(int code) {
        return new PdfRenderException(code);
    }

    private static void closeQuietly(Closeable resource) {
        if (resource != null) {
            try {
                resource.close();
            } catch (IOException ignored) {
            }
        }
    }

    private final class Session extends IPdfDocument.Stub implements IBinder.DeathRecipient {
        private final PdfRenderer renderer;
        private final IBinder owner;
        private final int pageCount;
        private final AtomicBoolean closed = new AtomicBoolean();
        private ScheduledFuture<?> openingLease;
        private boolean acknowledged;

        private Session(PdfRenderer renderer, IBinder owner, int pageCount) {
            this.renderer = renderer;
            this.owner = owner;
            this.pageCount = pageCount;
        }

        private void requireOpen() {
            if (closed.get() || stopped) {
                throw error(PdfRenderClient.ERROR_SERVICE);
            }
        }

        private void validatePage(int page) {
            if (page < 0 || page >= pageCount) {
                throw error(PdfRenderClient.ERROR_RENDER);
            }
        }

        private <T> T call(Callable<T> operation) {
            checkCaller();
            requireOpen();
            FutureTask<T> task = new FutureTask<>(() -> {
                requireOpen();
                return operation.call();
            });
            execute(task);
            return await(task);
        }

        @Override
        public int getPageCount() {
            checkCaller();
            synchronized (lifecycleLock) {
                requireOpen();
                acknowledged = true;
                if (openingLease != null) openingLease.cancel(false);
            }
            return pageCount;
        }

        @Override
        public int[] getPageSize(int page) {
            validatePage(page);
            return call(() -> {
                try (PdfRenderer.Page opened = renderer.openPage(page)) {
                    int width = opened.getWidth();
                    int height = opened.getHeight();
                    if (width <= 0 || height <= 0) {
                        throw error(PdfRenderClient.ERROR_RENDER);
                    }
                    return new int[]{width, height};
                } catch (RuntimeException exception) {
                    throw error(PdfRenderClient.ERROR_RENDER);
                }
            });
        }

        @Override
        public void render(int page, int renderWidth, int renderHeight, int x, int y,
                int width, int height, ParcelFileDescriptor output) {
            try {
                checkCaller();
                requireOpen();
                if (output == null) {
                    throw error(PdfRenderClient.ERROR_RENDER);
                }
                execute(() -> renderToPipe(page, renderWidth, renderHeight, x, y, width, height, output));
            } catch (RuntimeException exception) {
                closeQuietly(output);
                throw exception;
            }
        }

        private void renderToPipe(int page, int renderWidth, int renderHeight, int x, int y,
                int width, int height, ParcelFileDescriptor output) {
            Bitmap bitmap = null;
            try (DataOutputStream stream = new DataOutputStream(new ParcelFileDescriptor.AutoCloseOutputStream(output))) {
                byte[] pixels;
                try {
                    requireOpen();
                    validatePage(page);
                    if (!validTile(renderWidth, renderHeight, x, y, width, height)) {
                        throw error(PdfRenderClient.ERROR_RENDER);
                    }
                    try (PdfRenderer.Page opened = renderer.openPage(page)) {
                        if (opened.getWidth() <= 0 || opened.getHeight() <= 0) {
                            throw error(PdfRenderClient.ERROR_RENDER);
                        }
                        float scaleX = (float) renderWidth / opened.getWidth();
                        float scaleY = (float) renderHeight / opened.getHeight();
                        if (Float.isNaN(scaleX) || Float.isInfinite(scaleX) || scaleX <= 0
                                || Float.isNaN(scaleY) || Float.isInfinite(scaleY) || scaleY <= 0) {
                            throw error(PdfRenderClient.ERROR_RENDER);
                        }
                        bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                        bitmap.eraseColor(0xffffffff);
                        Matrix matrix = new Matrix();
                        matrix.setScale(scaleX, scaleY);
                        matrix.postTranslate(-x, -y);
                        opened.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
                    }
                    requireOpen();
                    int expected = width * height * 4;
                    if (bitmap.getByteCount() != expected) {
                        throw error(PdfRenderClient.ERROR_RENDER);
                    }
                    pixels = new byte[expected];
                    bitmap.copyPixelsToBuffer(ByteBuffer.wrap(pixels));
                } catch (RuntimeException | OutOfMemoryError exception) {
                    stream.writeInt(PdfRenderClient.ERROR_RENDER);
                    return;
                }
                stream.writeInt(0);
                stream.write(pixels);
            } catch (IOException ignored) {
            } finally {
                if (bitmap != null) {
                    bitmap.recycle();
                }
                closeQuietly(output);
            }
        }

        @Override
        public void close() {
            if (Binder.getCallingUid() != getApplicationInfo().uid) {
                throw error(PdfRenderClient.ERROR_SERVICE);
            }
            binderDied();
        }

        @Override
        public void binderDied() {
            if (closed.compareAndSet(false, true)) {
                scheduleSweep();
            }
        }
    }

    private static final class Api35 {
        private static PdfRenderer open(ParcelFileDescriptor source, String password) throws IOException {
            return new PdfRenderer(source, new android.graphics.pdf.LoadParams.Builder()
                    .setPassword(password).build());
        }
    }
}
