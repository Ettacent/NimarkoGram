/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.documents;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.os.SystemClock;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.system.StructPollfd;
import android.system.StructStat;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PdfRenderClient implements AutoCloseable {
    public static final int ERROR_PASSWORD = 1;
    public static final int ERROR_UNSUPPORTED_PASSWORD = 2;
    public static final int ERROR_FILE = 3;
    public static final int ERROR_RENDER = 4;
    public static final int ERROR_SERVICE = 5;
    public static final int ERROR_TIMEOUT = 6;
    private static final long MAX_FILE_BYTES = 256L * 1024 * 1024;
    private static final int MAX_PENDING = 8;
    private static final long TIMEOUT_MS = 15000;

    public interface Callback {
        void onOpened(int pageCount);
        void onPageSize(int page, int width, int height);
        void onTile(int page, int renderWidth, int renderHeight, int x, int y, Bitmap bitmap);
        void onError(int code);
    }

    private final Context context;
    private final File file;
    private String password;
    private Callback callback;
    private final Object lock = new Object();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final IBinder owner = new Binder();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0,
            TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(MAX_PENDING),
            runnable -> new Thread(runnable, "pdf-client"));
    private final ScheduledThreadPoolExecutor control = new ScheduledThreadPoolExecutor(1,
            runnable -> new Thread(runnable, "pdf-client-control"));
    private boolean started;
    private boolean closed;
    private boolean ready;
    private boolean bindingRegistered;
    private boolean connected;
    private int pageCount;
    private int pending;
    private IPdfDocument document;
    private IBinder serviceBinder;
    private ScheduledFuture<?> openingAlarm;
    private final IBinder.DeathRecipient death = () -> fail(ERROR_SERVICE);

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            runControl(() -> connected(binder));
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            fail(ERROR_SERVICE);
        }

        @Override
        public void onBindingDied(ComponentName name) {
            fail(ERROR_SERVICE);
        }

        @Override
        public void onNullBinding(ComponentName name) {
            fail(ERROR_SERVICE);
        }
    };

    public PdfRenderClient(Context context, File file, String password, Callback callback) {
        if (context == null || context.getApplicationContext() == null || file == null || callback == null) {
            throw new IllegalArgumentException();
        }
        this.context = context.getApplicationContext();
        this.file = file;
        this.password = password;
        this.callback = callback;
        control.setRemoveOnCancelPolicy(true);
        control.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    public void open() {
        synchronized (lock) {
            if (started || closed) {
                return;
            }
            started = true;
            runControl(() -> {
                synchronized (lock) {
                    if (closed) {
                        return;
                    }
                }
                openingAlarm = control.schedule(() -> {
                    synchronized (lock) {
                        if (!ready) {
                            fail(ERROR_TIMEOUT);
                        }
                    }
                }, TIMEOUT_MS, TimeUnit.MILLISECONDS);
                try {
                    bindingRegistered = true;
                    if (!context.bindService(new Intent(context, PdfRenderService.class), connection,
                            Context.BIND_AUTO_CREATE)) {
                        fail(ERROR_SERVICE);
                    }
                } catch (RuntimeException exception) {
                    fail(ERROR_SERVICE);
                }
            });
        }
    }

    private void connected(IBinder binder) {
        synchronized (lock) {
            if (closed || connected) {
                return;
            }
            connected = true;
            try {
                worker.execute(() -> openDocument(binder));
            } catch (RejectedExecutionException exception) {
                fail(ERROR_SERVICE);
            }
        }
    }

    private void openDocument(IBinder binder) {
        IPdfDocument opened = null;
        boolean installed = false;
        try {
            synchronized (lock) {
                if (closed) {
                    return;
                }
            }
            binder.linkToDeath(death, 0);
            synchronized (lock) {
                serviceBinder = binder;
                if (closed) {
                    return;
                }
            }
            try (ParcelFileDescriptor source = openSource()) {
                StructStat stat = Os.fstat(source.getFileDescriptor());
                if (!OsConstants.S_ISREG(stat.st_mode) || stat.st_size <= 0 || stat.st_size > MAX_FILE_BYTES) {
                    throw new PdfRenderException(ERROR_FILE);
                }
                Os.lseek(source.getFileDescriptor(), 0, OsConstants.SEEK_SET);
                String suppliedPassword;
                synchronized (lock) {
                    suppliedPassword = password;
                    password = null;
                }
                opened = IDocumentRenderer.Stub.asInterface(binder).open(source, owner, suppliedPassword);
            }
            if (opened == null) {
                throw new PdfRenderException(ERROR_SERVICE);
            }
            int count = opened.getPageCount();
            if (count <= 0 || count > 10000) {
                throw new PdfRenderException(ERROR_FILE);
            }
            synchronized (lock) {
                if (closed) {
                    return;
                }
                document = opened;
                pageCount = count;
                ready = true;
                installed = true;
                runControl(() -> {
                    if (openingAlarm != null) {
                        openingAlarm.cancel(false);
                    }
                });
                main.post(() -> {
                    synchronized (lock) {
                        if (!closed && callback != null) {
                            callback.onOpened(count);
                        }
                    }
                });
            }
        } catch (Exception exception) {
            fail(code(exception, ERROR_FILE));
        } catch (OutOfMemoryError error) {
            fail(ERROR_FILE);
        } finally {
            if (!installed) {
                closeDocument(opened);
            }
        }
    }

    private ParcelFileDescriptor openSource() throws Exception {
        FileDescriptor descriptor = Os.open(file.getAbsolutePath(),
                OsConstants.O_RDONLY | OsConstants.O_CLOEXEC | OsConstants.O_NONBLOCK, 0);
        try {
            return ParcelFileDescriptor.dup(descriptor);
        } finally {
            Os.close(descriptor);
        }
    }

    public boolean requestPageSize(int page) {
        return submit(page, finished -> {
            int[] size = document.getPageSize(page);
            if (size == null || size.length != 2 || size[0] <= 0 || size[1] <= 0) {
                throw new PdfRenderException(ERROR_RENDER);
            }
            postResult(null, target -> target.onPageSize(page, size[0], size[1]), finished);
        });
    }

    public boolean renderTile(int page, int renderWidth, int renderHeight, int x, int y,
            int width, int height) {
        if (!PdfRenderService.validTile(renderWidth, renderHeight, x, y, width, height)) {
            return false;
        }
        return submit(page, finished -> {
            Bitmap bitmap = null;
            ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
            try {
                int flags = Os.fcntlInt(pipe[0].getFileDescriptor(), OsConstants.F_GETFL, 0);
                Os.fcntlInt(pipe[0].getFileDescriptor(), OsConstants.F_SETFL, flags | OsConstants.O_NONBLOCK);
                long deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS;
                try {
                    document.render(page, renderWidth, renderHeight, x, y, width, height, pipe[1]);
                } finally {
                    pipe[1].close();
                }
                byte[] status = new byte[4];
                readExactly(pipe[0], status, deadline);
                int result = ByteBuffer.wrap(status).order(ByteOrder.BIG_ENDIAN).getInt();
                if (result != 0) {
                    throw new PdfRenderException(normalize(result, ERROR_RENDER));
                }
                byte[] pixels = new byte[width * height * 4];
                readExactly(pipe[0], pixels, deadline);
                synchronized (lock) {
                    if (closed) {
                        throw new PdfRenderException(ERROR_SERVICE);
                    }
                }
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                if (bitmap.getByteCount() != pixels.length) {
                    throw new PdfRenderException(ERROR_RENDER);
                }
                bitmap.copyPixelsFromBuffer(ByteBuffer.wrap(pixels));
                prepareTileForDraw(bitmap, finished);
                Bitmap delivered = bitmap;
                postResult(delivered, target -> target.onTile(page, renderWidth, renderHeight, x, y, delivered), finished);
                bitmap = null;
            } finally {
                if (bitmap != null) {
                    bitmap.recycle();
                }
                closeFd(pipe[0]);
                closeFd(pipe[1]);
            }
        });
    }

    private void prepareTileForDraw(Bitmap bitmap, AtomicBoolean finished) {
        synchronized (lock) {
            if (closed || finished.get()) return;
        }
        try {
            bitmap.prepareToDraw();
        } catch (RuntimeException | OutOfMemoryError ignored) {
        }
    }

    private boolean submit(int page, Operation operation) {
        synchronized (lock) {
            if (closed || !ready || page < 0 || page >= pageCount || pending >= MAX_PENDING) {
                return false;
            }
            pending++;
            try {
                worker.execute(() -> {
                    ScheduledFuture<?> alarm = null;
                    AtomicBoolean finished = new AtomicBoolean();
                    try {
                        synchronized (lock) {
                            if (closed) {
                                return;
                            }
                            alarm = control.schedule(() -> {
                                if (finished.compareAndSet(false, true)) {
                                    fail(ERROR_TIMEOUT);
                                }
                            }, TIMEOUT_MS, TimeUnit.MILLISECONDS);
                        }
                        operation.run(finished);
                    } catch (Exception exception) {
                        if (finished.compareAndSet(false, true)) fail(code(exception, ERROR_RENDER));
                    } catch (OutOfMemoryError error) {
                        if (finished.compareAndSet(false, true)) fail(ERROR_RENDER);
                    } finally {
                        finished.set(true);
                        if (alarm != null) {
                            alarm.cancel(false);
                        }
                    }
                });
                return true;
            } catch (RejectedExecutionException exception) {
                pending--;
                return false;
            }
        }
    }

    private void postResult(Bitmap bitmap, Delivery delivery, AtomicBoolean finished) {
        if (!finished.compareAndSet(false, true)) {
            if (bitmap != null) bitmap.recycle();
            return;
        }
        boolean posted = main.post(() -> {
            boolean published = false;
            try {
                synchronized (lock) {
                    pending--;
                    if (!closed && callback != null) {
                        published = true;
                        delivery.deliver(callback);
                    }
                }
            } finally {
                if (!published && bitmap != null) {
                    bitmap.recycle();
                }
            }
        });
        if (!posted) {
            if (bitmap != null) {
                bitmap.recycle();
            }
            fail(ERROR_SERVICE);
        }
    }

    private void readExactly(ParcelFileDescriptor input, byte[] buffer, long deadline) throws Exception {
        StructPollfd descriptor = new StructPollfd();
        descriptor.fd = input.getFileDescriptor();
        descriptor.events = (short) OsConstants.POLLIN;
        StructPollfd[] descriptors = {descriptor};
        int offset = 0;
        while (offset < buffer.length) {
            synchronized (lock) {
                if (closed) {
                    throw new PdfRenderException(ERROR_SERVICE);
                }
            }
            long remaining = deadline - SystemClock.elapsedRealtime();
            if (remaining <= 0) {
                throw new PdfRenderException(ERROR_TIMEOUT);
            }
            try {
                if (Os.poll(descriptors, (int) Math.min(remaining, 250)) == 0) {
                    continue;
                }
                int count = Os.read(input.getFileDescriptor(), buffer, offset, buffer.length - offset);
                if (count <= 0) {
                    throw new IOException();
                }
                offset += count;
            } catch (ErrnoException exception) {
                if (exception.errno != OsConstants.EAGAIN && exception.errno != OsConstants.EINTR) {
                    throw exception;
                }
            }
        }
    }

    private static int normalize(int code, int fallback) {
        return code >= ERROR_PASSWORD && code <= ERROR_TIMEOUT ? code : fallback;
    }

    private static int code(Exception exception, int fallback) {
        if (exception instanceof IllegalStateException) {
            return PdfRenderException.code(exception, fallback);
        }
        if (exception instanceof RemoteException) {
            return ERROR_SERVICE;
        }
        return fallback;
    }

    private void fail(int code) {
        synchronized (lock) {
            if (closed) {
                return;
            }
            terminate();
            main.post(() -> {
                synchronized (lock) {
                    Callback target = callback;
                    callback = null;
                    if (target != null) {
                        target.onError(code);
                    }
                }
            });
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            callback = null;
            if (!closed) {
                terminate();
            }
        }
    }

    private void terminate() {
        closed = true;
        ready = false;
        password = null;
        worker.getQueue().clear();
        worker.execute(() -> {
            closeDocument(document);
            document = null;
            IBinder binder;
            synchronized (lock) {
                binder = serviceBinder;
                serviceBinder = null;
            }
            if (binder != null) {
                try {
                    binder.unlinkToDeath(death, 0);
                } catch (RuntimeException ignored) {
                }
            }
        });
        worker.shutdown();
        runControl(() -> {
            if (openingAlarm != null) {
                openingAlarm.cancel(false);
            }
            if (bindingRegistered) {
                bindingRegistered = false;
                try {
                    context.unbindService(connection);
                } catch (RuntimeException ignored) {
                }
            }
            control.shutdown();
        });
    }

    private void runControl(Runnable runnable) {
        try {
            control.execute(runnable);
        } catch (RejectedExecutionException ignored) {
        }
    }

    private static void closeDocument(IPdfDocument document) {
        if (document != null) {
            try {
                document.close();
            } catch (RemoteException | RuntimeException ignored) {
            }
        }
    }

    private static void closeFd(ParcelFileDescriptor descriptor) {
        try {
            descriptor.close();
        } catch (IOException ignored) {
        }
    }

    private interface Operation {
        void run(AtomicBoolean finished) throws Exception;
    }

    private interface Delivery {
        void deliver(Callback callback);
    }
}
