/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.ui.Components;

import android.graphics.Bitmap;
import android.os.Build;
import android.os.Trace;

import org.telegram.messenger.AnimatedFileDrawableStream;
import org.telegram.messenger.BuildConfig;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class AnimatedFileNative {

    public interface SourceLease extends AutoCloseable {
        int fd();
        long length();
        @Override void close();
    }

    public static final class TimedDecoder {
        public static final long UNKNOWN = Long.MIN_VALUE;
        public static final int ACQUIRED = 1, EOF = 2, AGAIN = 3, YIELD = 4,
                CANCELLED = 5, WRITE_ERROR = 6, READ_ERROR = 7, PROTOCOL_ERROR = 8,
                MATERIALIZED = 9, COMMITTED = 10, DISCARDED = 11, SEEKED = 12;
        public static final int COMMIT = 1, DISCARD = 2;

        public static final class FrameSlot {
            private final long[] raw = new long[4];
            public long generation, id, pts = UNKNOWN, duration = UNKNOWN, flags;
        }

        private final Object gate = new Object();
        private final long[] source;
        private final AnimatedFileDrawableStream stream;
        private final SourceLease sourceLease;
        private long ptr, intent = 1;
        private Lease owner;
        private int cancelling;
        private boolean retired, destroying;

        private TimedDecoder(long ptr, long[] source, AnimatedFileDrawableStream stream, SourceLease sourceLease) {
            this.ptr = ptr;
            this.source = source;
            this.stream = stream;
            this.sourceLease = sourceLease;
        }

        public static TimedDecoder openPath(String path, int account, long length,
                                            AnimatedFileDrawableStream stream, boolean preview) {
            if (path == null || length < 0 || (stream == null ? length != 0 : length <= 0 || length > Integer.MAX_VALUE)) {
                throw new IllegalArgumentException("path/stream length");
            }
            long[] source = new long[14];
            long ptr = nCreateTimedPath(path, account, length, stream, preview, source);
            return opened(ptr, source, stream, null);
        }

        public static TimedDecoder openSnapshot(SourceLease lease) {
            if (lease == null) throw new IllegalArgumentException("source lease");
            int fd = lease.fd();
            long length = lease.length();
            if (fd < 0 || length <= 0) throw new IllegalArgumentException("snapshot fd/length");
            long[] source = new long[14];
            long ptr = nCreateTimedSnapshot(fd, length, source);
            return opened(ptr, source, null, lease);
        }

        private static TimedDecoder opened(long ptr, long[] source, AnimatedFileDrawableStream stream, SourceLease lease) {
            if (ptr == 0) return null;
            try {
                if (source[0] != 1 || source[1] < 0 || (source[9] & 1) == 0 || source[5] <= 0 || source[6] <= 0) {
                    throw new IllegalStateException("timed decoder schema/source mismatch");
                }
                return new TimedDecoder(ptr, source, stream, lease);
            } catch (Throwable error) {
                nDestroyTimed(ptr);
                throw error;
            }
        }

        public void copySource(long[] destination) {
            if (destination == null || destination.length != 14) throw new IllegalArgumentException("source[14]");
            System.arraycopy(source, 0, destination, 0, 14);
        }

        public Lease tryLease(long generation) {
            synchronized (gate) {
                if (retired || ptr == 0 || intent <= 0 || owner != null || cancelling != 0) return null;
                owner = new Lease(generation, intent);
                return owner;
            }
        }

        public void requestCancel() { cancel(false); }
        public void retire() { cancel(true); }

        private void cancel(boolean retire) {
            synchronized (gate) {
                if (retire) retired = true;
                if (ptr == 0) return;
                intent = nCancelTimed(ptr);
                ++cancelling;
            }
            try {
                if (stream != null) stream.cancel();
            } finally {
                synchronized (gate) { --cancelling; }
            }
        }

        public boolean destroyRetired() {
            long destroy;
            synchronized (gate) {
                if (!retired) throw new IllegalStateException("retire first");
                if (owner != null || cancelling != 0 || destroying) return false;
                destroy = ptr;
                ptr = 0;
                destroying = destroy != 0;
            }
            if (destroy != 0) {
                try { nDestroyTimed(destroy); }
                finally {
                    try { if (sourceLease != null) sourceLease.close(); }
                    finally { synchronized (gate) { destroying = false; } }
                }
            }
            return true;
        }

        public final class Lease implements AutoCloseable {
            public final long generation;
            private final long capturedIntent;
            private boolean inCall, closed;

            private Lease(long generation, long intent) {
                this.generation = generation;
                this.capturedIntent = intent;
            }

            private long enter() {
                synchronized (gate) {
                    if (closed || owner != this || inCall || ptr == 0) throw new IllegalStateException("inactive or concurrent lease");
                    inCall = true;
                    return ptr;
                }
            }

            private int leave(int result) {
                synchronized (gate) {
                    inCall = false;
                    return retired || capturedIntent != intent ? CANCELLED : result;
                }
            }

            private void leaveOnThrow() {
                synchronized (gate) { inCall = false; }
            }

            public boolean isCurrent() {
                synchronized (gate) { return !closed && owner == this && !retired && capturedIntent == intent; }
            }

            public int acquire(FrameSlot frame, int steps, long budgetUs) {
                if (frame == null || steps <= 0 || budgetUs <= 0) throw new IllegalArgumentException("frame/budget");
                long p = enter();
                int result;
                try { result = nAcquireTimed(p, capturedIntent, frame.raw, steps, budgetUs); }
                catch (Throwable error) { leaveOnThrow(); throw error; }
                synchronized (gate) {
                    result = leave(result);
                    if (result == ACQUIRED) {
                        frame.generation = generation;
                        frame.id = frame.raw[0]; frame.pts = frame.raw[1];
                        frame.duration = frame.raw[2]; frame.flags = frame.raw[3];
                    }
                    return result;
                }
            }

            public int materialize(long id, Bitmap bitmap) {
                if (bitmap == null) throw new IllegalArgumentException("bitmap");
                long p = enter();
                int result;
                try { result = nMaterializeTimed(p, capturedIntent, id, bitmap); }
                catch (Throwable error) { leaveOnThrow(); throw error; }
                return leave(result);
            }

            public int resolve(long id, int disposition) {
                if (disposition != COMMIT && disposition != DISCARD) throw new IllegalArgumentException("disposition");
                long p = enter();
                int result;
                try { result = nResolveTimed(p, capturedIntent, id, disposition); }
                catch (Throwable error) { leaveOnThrow(); throw error; }
                return leave(result);
            }

            public int seek(long rawTicks) {
                if (rawTicks == UNKNOWN) throw new IllegalArgumentException("raw seek");
                long p = enter();
                int result;
                try {
                    synchronized (gate) {
                        if (retired || capturedIntent != intent || cancelling != 0) {
                            return leave(CANCELLED);
                        }
                        if (stream != null) stream.reset();
                    }
                    result = nSeekTimed(p, capturedIntent, rawTicks);
                } catch (Throwable error) { leaveOnThrow(); throw error; }
                return leave(result);
            }

            @Override public void close() {
                synchronized (gate) {
                    if (closed) return;
                    if (inCall || owner != this) throw new IllegalStateException("lease still in call");
                    closed = true;
                    owner = null;
                }
            }
        }
    }

    private static native long nCreateTimedPath(String path, int account, long length, Object stream, boolean preview, long[] source);
    private static native long nCreateTimedSnapshot(int fd, long length, long[] source);
    private static native long nCancelTimed(long ptr);
    private static native int nAcquireTimed(long ptr, long intent, long[] frame, int steps, long budgetUs);
    private static native int nMaterializeTimed(long ptr, long intent, long id, Bitmap bitmap);
    private static native int nResolveTimed(long ptr, long intent, long id, int disposition);
    private static native int nSeekTimed(long ptr, long intent, long rawTicks);
    private static native void nDestroyTimed(long ptr);

    static final int NO_FRAME = 0;
    static final int FRAME_READY = 1;
    static final int FRAME_EOF = 2;
    private final int[] mMetaData;
    private long mNativePtr;
    private final AtomicBoolean mRecycled = new AtomicBoolean(false);

    private AnimatedFileNative(long nativePtr, int[] metaData) {
        mNativePtr = nativePtr;
        mMetaData = metaData;
    }

    public static AnimatedFileNative createDecoderFrom(String src, int[] params, int account, long streamFileSize, AnimatedFileDrawableStream readCallback, boolean preview) {
        long ptr = createDecoder(src, params, account, streamFileSize, readCallback, preview);
        if (ptr == 0) {
            return null;
        }
        return new AnimatedFileNative(ptr, params);
    }

    public int getWidth() {
        return mMetaData[0];
    }

    public int getHeight() {
        return mMetaData[1];
    }

    public int getRotation() {
        return mMetaData[2];
    }

    public int getProgress(TimeUnit timeUnit) {
        return (int) timeUnit.convert(mMetaData[3], TimeUnit.MILLISECONDS);
    }

    public int getDuration(TimeUnit timeUnit) {
        return (int) timeUnit.convert(mMetaData[4], TimeUnit.MILLISECONDS);
    }

    public int getFps() {
        return mMetaData[5];
    }

    public boolean isLastFrameOpaque() {
        return mMetaData[6] == 1;
    }

    public boolean isStaticVideoDetected() {
        return mMetaData[7] == 1;
    }

    public void stopDecoder() {
        checkNotDestroyed();
        stopDecoder(mNativePtr);
    }

    public int getVideoFrame(Bitmap bitmap, boolean preview, float startTimeSeconds, float endTimeSeconds, boolean loop) {
        return getVideoFrameWithStatus(bitmap, preview, startTimeSeconds, endTimeSeconds, loop) == FRAME_READY ? FRAME_READY : NO_FRAME;
    }

    int getVideoFrameWithStatus(Bitmap bitmap, boolean preview, float startTimeSeconds, float endTimeSeconds, boolean loop) {
        checkNotDestroyed();
        return getVideoFrame(mNativePtr, bitmap, mMetaData, preview, startTimeSeconds, endTimeSeconds, loop);
    }

    public void seekToMs(long ms, boolean precise) {
        checkNotDestroyed();
        seekToMs(mNativePtr, ms, mMetaData, precise);
    }

    public int getFrameAtTime(long ms, Bitmap bitmap) {
        checkNotDestroyed();
        return getFrameAtTime(mNativePtr, ms, bitmap, mMetaData);
    }

    public void prepareToSeek() {
        checkNotDestroyed();
        prepareToSeek(mNativePtr);
    }

    public boolean isDestroyed() {
        return mRecycled.get();
    }

    public void recycle() {
        if (mRecycled.compareAndSet(false, true)) {
            long ptr = mNativePtr;
            mNativePtr = 0;
            if (ptr != 0) {
                destroyDecoder(ptr);
            }
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (!mRecycled.get()) {
                recycle();
            }
        } finally {
            super.finalize();
        }
    }

    private void checkNotDestroyed() {
        if (mRecycled.get()) {
            if (BuildConfig.DEBUG_PRIVATE_VERSION) {
                throw new IllegalStateException("Called method on a destroyed AnimatedFileNative instance");
            }
        }
    }

    private static long createDecoder(String src, int[] params, int account, long streamFileSize, AnimatedFileDrawableStream readCallback, boolean preview) {
        Trace.beginSection("AnimatedFileNative#createDecoder");
        try {
            return nCreateDecoder(src, params, account, streamFileSize, readCallback, preview);
        } finally {
            Trace.endSection();
        }
    }

    private static void destroyDecoder(long ptr) {
        Trace.beginSection("AnimatedFileNative#destroyDecoder");
        try {
            nDestroyDecoder(ptr);
        } finally {
            Trace.endSection();
        }
    }

    private static void stopDecoder(long ptr) {
        Trace.beginSection("AnimatedFileNative#stopDecoder");
        try {
            nStopDecoder(ptr);
        } finally {
            Trace.endSection();
        }
    }

    private static int getVideoFrame(long ptr, Bitmap bitmap, int[] params, boolean preview, float startTimeSeconds, float endTimeSeconds, boolean loop) {
        Trace.beginSection("AnimatedFileNative#getVideoFrame");
        try {
            return nGetVideoFrame(ptr, bitmap, params, preview, startTimeSeconds, endTimeSeconds, loop);
        } finally {
            Trace.endSection();
        }
    }

    private static void seekToMs(long ptr, long ms, int[] params, boolean precise) {
        Trace.beginSection("AnimatedFileNative#seekToMs");
        try {
            nSeekToMs(ptr, ms, params, precise);
        } finally {
            Trace.endSection();
        }
    }

    private static int getFrameAtTime(long ptr, long ms, Bitmap bitmap, int[] data) {
        Trace.beginSection("AnimatedFileNative#getFrameAtTime");
        try {
            return nGetFrameAtTime(ptr, ms, bitmap, data);
        } finally {
            Trace.endSection();
        }
    }

    private static void prepareToSeek(long ptr) {
        Trace.beginSection("AnimatedFileNative#prepareToSeek");
        try {
            nPrepareToSeek(ptr);
        } finally {
            Trace.endSection();
        }
    }

    public static void getVideoInfo(String src, int[] params, long fileOffset) {
        Trace.beginSection("AnimatedFileNative#getVideoInfo");
        try {
            nGetVideoInfo(src, params, fileOffset);
        } finally {
            Trace.endSection();
        }
    }



    private static native long nCreateDecoder(String src, int[] params, int account, long streamFileSize, Object readCallback, boolean preview);

    private static native void nDestroyDecoder(long ptr);

    private static native void nStopDecoder(long ptr);

    private static native int nGetVideoFrame(long ptr, Bitmap bitmap, int[] params, boolean preview, float startTimeSeconds, float endTimeSeconds, boolean loop);

    private static native void nSeekToMs(long ptr, long ms, int[] params, boolean precise);

    private static native int nGetFrameAtTime(long ptr, long ms, Bitmap bitmap, int[] data);

    private static native void nPrepareToSeek(long ptr);

    private static native void nGetVideoInfo(String src, int[] params, long fileOffset);
}