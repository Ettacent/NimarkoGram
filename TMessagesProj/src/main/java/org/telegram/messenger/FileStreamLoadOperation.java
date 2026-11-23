/* Modifications Copyright (C) 2026 Ettacent */

/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;


import static androidx.media3.common.util.Assertions.checkNotNull;

import android.net.Uri;

import androidx.annotation.Nullable;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSourceException;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import org.telegram.tgnet.TLRPC;

import java.io.EOFException;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

import java.util.concurrent.TimeUnit;
@OptIn(markerClass = UnstableApi.class)
public class FileStreamLoadOperation implements DataSource, FileLoadOperationStream {

    public static final ConcurrentHashMap<Long, FileStreamLoadOperation> allStreams = new ConcurrentHashMap<>();

    private volatile FileLoadOperation loadOperation;
    private final FileLoader.PlayerStreamScope playerStreamScope;
    private final Object sourceStateLock = new Object();
    private volatile FileLoader.StreamRegistration streamRegistration;
    private FileLoader.StreamRegistration streamOwner;
    private int streamOwnerAccount = -1;
    private long openGeneration;

    private Uri uri;
    private long bytesRemaining;
    private long bytesTransferred;
    private long requestedLength;
    private volatile boolean opened;
    private long currentOffset;
    private volatile CountDownLatch countDownLatch;
    private RandomAccessFile file;
    private boolean finalDescriptor;
    private TLRPC.Document document;
    private Object parentObject;
    private int currentAccount;
    File currentFile;

    private static final ConcurrentHashMap<Long, Integer> priorityMap = new ConcurrentHashMap<>();

    public FileStreamLoadOperation() {
        this(null, null);
    }

    public FileStreamLoadOperation(@Nullable TransferListener listener, FileLoader.PlayerStreamScope playerStreamScope) {
        this.playerStreamScope = playerStreamScope;
        this.isNetwork = true;
        this.listeners = new ArrayList<>(/* initialCapacity= */ 1);
        if (listener != null) addTransferListener(listener);
    }

    @Deprecated
    public FileStreamLoadOperation(@Nullable TransferListener listener) {
        this(listener, null);
    }

    public static int getStreamPrioriy(TLRPC.Document document) {
        if (document == null) {
            return FileLoader.PRIORITY_HIGH;
        }
        Integer integer = priorityMap.get(document.id);
        if (integer == null) {
            return FileLoader.PRIORITY_HIGH;
        }
        return integer;
    }

    @Override
    public long open(DataSpec dataSpec) throws IOException {
        final long generation;
        synchronized (sourceStateLock) {
            if (opened) throw new IOException("Media stream is already open");
            generation = ++openGeneration;
            opened = true;
        uri = dataSpec.uri;
        }
        boolean success = false;
        try {
            long length = openStream(dataSpec, generation);
            success = true;
            return length;
        } finally {
            if (!success) closeStream(generation);
        }
    }

    private long openStream(DataSpec dataSpec, long generation) throws IOException {
        final Uri uri = dataSpec.uri;
        final int account = Utilities.parseInt(uri.getQueryParameter("account"));
        if (account < 0 || account >= UserConfig.MAX_ACCOUNT_COUNT) throw new IOException("Invalid stream account");
        Object parent = FileLoader.getInstance(account).getParentObject(Utilities.parseInt(uri.getQueryParameter("rid")));
        TLRPC.Document doc = new TLRPC.TL_document();
        doc.access_hash = Utilities.parseLong(uri.getQueryParameter("hash"));
        doc.id = Utilities.parseLong(uri.getQueryParameter("id"));
        doc.size = Utilities.parseLong(uri.getQueryParameter("size"));
        doc.dc_id = Utilities.parseInt(uri.getQueryParameter("dc"));
        doc.mime_type = uri.getQueryParameter("mime");
        if (doc.mime_type == null) doc.mime_type = "application/octet-stream";
        doc.file_reference = Utilities.hexToBytes(uri.getQueryParameter("reference"));
        TLRPC.TL_documentAttributeFilename filename = new TLRPC.TL_documentAttributeFilename();
        filename.file_name = uri.getQueryParameter("name");
        doc.attributes.add(filename);
        if (doc.mime_type.startsWith("video")) {
            doc.attributes.add(new TLRPC.TL_documentAttributeVideo());
        } else if (doc.mime_type.startsWith("audio")) {
            doc.attributes.add(new TLRPC.TL_documentAttributeAudio());
        }
        final FileLoader.StreamRegistration inherited;
        synchronized (sourceStateLock) {
            if (!opened || generation != openGeneration) throw new IOException("Media stream was closed");
            document = doc;
            currentAccount = account;
            parentObject = parent;
        currentOffset = dataSpec.position;
        requestedLength = dataSpec.length;
            bytesTransferred = 0;
            bytesRemaining = doc.size - currentOffset;
            if (requestedLength != C.LENGTH_UNSET) bytesRemaining = Math.min(bytesRemaining, requestedLength);
            inherited = streamOwnerAccount == account ? streamOwner : null;
        }
        final FileLoader.StreamRegistration registration = inherited == null
                ? FileLoader.getInstance(account).createStreamRegistration(this, playerStreamScope) : inherited.fork();
        boolean accepted;
        synchronized (sourceStateLock) {
            accepted = opened && openGeneration == generation;
            if (accepted) {
                streamRegistration = registration;
                streamOwner = registration;
                streamOwnerAccount = account;
            }
        }
        if (!accepted) {
            registration.close();
            throw new IOException("Media stream was closed during admission");
        }
        FileLoadOperation operation = acquireStreamOperation(registration);
        boolean cached;
        for (int attempt = 0; ; attempt++) {
            try {
                acceptStreamOperation(operation, registration);
                File initial = operation.getCurrentFile();
                try {
                    cached = replaceStreamFile(initial, operation, registration);
                } catch (FileNotFoundException e) {
                    if (initial == null || initial.exists()) throw e;
                    cached = false;
                }
                break;
            } catch (IOException e) {
                if (attempt != 0 || !registration.canRecover(operation)) throw e;
                operation = acquireStreamOperation(registration);
            }
        }
        if (registration.isClosed()) throw new IOException("Media stream was closed");
        final long resolvedLength;
        synchronized (sourceStateLock) {
            if (!opened || streamRegistration != registration) throw new IOException("Media stream was closed");
            if (bytesRemaining < 0) throw new DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE);
            isNetwork = !cached;
            allStreams.put(doc.id, this);
            resolvedLength = dataSpec.length == C.LENGTH_UNSET ? bytesRemaining : dataSpec.length;
        }
        final Transfer transfer = new Transfer(dataSpec, !cached, transferListeners());
        for (TransferListener listener : transfer.listeners) {
            listener.onTransferInitializing(this, dataSpec, transfer.network);
        }
        if (registration.isClosed()) throw new IOException("Media stream was closed");
        synchronized (sourceStateLock) {
            if (!opened || openGeneration != generation || streamRegistration != registration) {
            throw new IOException("Media stream was closed");
        }
            activeTransfer = transfer;
            transfer.dispatches++;
        }
        dispatchTransferStarted(transfer);
        if (!registration.isAttachedTo(operation) && !registration.canRecover(operation)) throw new IOException("Media stream was closed");
        return resolvedLength;
    }

    private int getCurrentPriority() {
        if (playerStreamScope != null) return playerStreamScope.getPriority();
        Integer priority = priorityMap.getOrDefault(document.id, null);
        if (priority != null) {
            return priority;
        }
        return FileLoader.PRIORITY_HIGH;
    }

    private FileLoadOperation acquireStreamOperation(FileLoader.StreamRegistration registration) {
        final int account, priority;
        final long position;
        final TLRPC.Document target;
        final Object parent;
        synchronized (sourceStateLock) {
            if (!opened || streamRegistration != registration) return null;
            account = currentAccount;
            target = document;
            parent = parentObject;
            position = currentOffset;
            priority = getCurrentPriority();
        }
        return FileLoader.getInstance(account).loadStreamFileWithRegistration(this, target, null, parent,
                position, false, priority, 0,
                () -> opened && streamRegistration == registration, registration);
    }

    FileLoader.StreamRegistration registrationForOperation(FileLoadOperation operation) {
        FileLoader.StreamRegistration registration = streamRegistration;
        return opened && registration != null && registration.isAttachedTo(operation) ? registration : null;
    }

    private void acceptStreamOperation(FileLoadOperation operation, FileLoader.StreamRegistration registration) throws IOException {
        RandomAccessFile previous = null;
        synchronized (sourceStateLock) {
            if (operation == null || !opened || streamRegistration != registration || !registration.isAttachedTo(operation)) {
                throw new IOException("Media stream admission was cancelled");
            }
            if (loadOperation != operation) {
                previous = file;
                file = null;
                currentFile = null;
                finalDescriptor = false;
            }
            loadOperation = operation;
        }
        if (previous != null) previous.close();
    }

    private boolean replaceStreamFile(File nextFile, FileLoadOperation operation, FileLoader.StreamRegistration registration) throws IOException {
        final RandomAccessFile previous;
        final long position;
        synchronized (sourceStateLock) {
            if (!opened || streamRegistration != registration || !registration.isAttachedTo(operation)) {
                throw new IOException("Media stream was closed");
            }
            previous = file;
            file = null;
            finalDescriptor = false;
            currentFile = nextFile;
            position = currentOffset;
        }
        if (previous != null) previous.close();
        if (nextFile == null) return false;
        RandomAccessFile candidate = new RandomAccessFile(nextFile, "r");
        try {
            candidate.seek(position);
            long length = operation.isPublishedStreamFile(nextFile) ? candidate.length() : -1;
            synchronized (sourceStateLock) {
                if (!opened || streamRegistration != registration || !registration.isAttachedTo(operation)) {
                    throw new IOException("Media stream was closed");
                }
                if (length >= 0) useFinalLength(length, position);
                file = candidate;
                candidate = null;
            }
            return length >= 0;
        } finally {
            if (candidate != null) candidate.close();
        }
    }

    private void useFinalLength(long length, long position) throws DataSourceException {
        if (position > length) throw new DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE);
        bytesRemaining = length - position;
        if (requestedLength != C.LENGTH_UNSET) bytesRemaining = Math.min(bytesRemaining, requestedLength - bytesTransferred);
        finalDescriptor = true;
    }
    @Override
    public int read(byte[] buffer, int offset, int readLength) throws IOException {
        if (readLength == 0) return 0;
            final FileLoader.StreamRegistration registration = streamRegistration;
            if (registration == null || registration.isClosed()) throw new IOException("Media stream was closed");
        boolean recovered = false;
        while (true) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
            final FileLoadOperation operation = loadOperation;
            final long position;
            final int wanted;
                    final CountDownLatch wakeup = new CountDownLatch(1);
            try {
                synchronized (sourceStateLock) {
                    if (!opened || streamRegistration != registration || !registration.isAttachedTo(operation)) throw new IOException("Media stream was closed");
                    if (activeTransfer == null || !activeTransfer.ready) throw new IOException("Media transfer has not started");
                    if (bytesRemaining == 0) return C.RESULT_END_OF_INPUT;
                    position = currentOffset;
                    wanted = (int) Math.min(bytesRemaining, readLength);
                    countDownLatch = wakeup;
                    }
                long[] result = operation.getDownloadedLengthFromOffset(position, wanted);
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                if (result[2] != 0 || registration.isClosed()) throw new IOException("Media download failed or was cancelled");
                File path = operation.getCurrentFileFast();
                final RandomAccessFile previous;
                final boolean changed;
                final boolean checkLength;
                synchronized (sourceStateLock) {
                    if (!opened || streamRegistration != registration || !registration.isAttachedTo(operation)) throw new IOException("Media stream was closed");
                    previous = file;
                    changed = previous == null || !Objects.equals(currentFile, path);
                    checkLength = !changed && !finalDescriptor && operation.isPublishedStreamFile(path);
                }
                if (changed) {
                    try {
                        replaceStreamFile(path, operation, registration);
                    } catch (FileNotFoundException e) {
                        if (!registration.isAttachedTo(operation) || path == null || path.exists()) throw e;
                        if (operation.isPublishedStreamFile(path)) {
                            if (recovered || !FileLoader.getInstance(currentAccount).recoverStreamFile(operation, registration)) throw e;
                            recovered = true;
                            acceptStreamOperation(acquireStreamOperation(registration), registration);
                            continue;
                        }
                        if (operation.isFinished() && !operation.isPreloadFinished()) {
                            if (Objects.equals(path, operation.getCurrentFileFast())) wakeup.await(1, TimeUnit.SECONDS);
                            continue;
                        }
                        if (result[0] > 0 && Objects.equals(path, operation.getCurrentFileFast())) throw e;
                    }
                } else if (checkLength) {
                    long length = previous.length();
                    synchronized (sourceStateLock) {
                        if (streamRegistration != registration || file != previous) throw new IOException("Media stream was closed");
                        useFinalLength(length, position);
                    }
                }
                final RandomAccessFile current;
                final int available;
                synchronized (sourceStateLock) {
                    if (!opened || streamRegistration != registration || !registration.isAttachedTo(operation)) throw new IOException("Media stream was closed");
                    if (bytesRemaining == 0) return C.RESULT_END_OF_INPUT;
                    current = file;
                    available = (int) Math.min(result[0], Math.min(bytesRemaining, wanted));
                }
                if (current != null && available > 0) {
                    int read = current.read(buffer, offset, available);
                    if (read <= 0) throw new EOFException("Downloaded range is shorter than reported");
                    final Transfer transfer;
                    synchronized (sourceStateLock) {
                        if (!opened || streamRegistration != registration || !registration.isAttachedTo(operation)) throw new IOException("Media stream was closed");
                        transfer = activeTransfer;
                        if (transfer == null) throw new IOException("Media transfer was closed");
                        transfer.dispatches++;
                        currentOffset += read;
                        bytesRemaining -= read;
                        bytesTransferred += read;
                    }
                    dispatchBytes(transfer, read);
                    return read;
                }
                FileLoadOperation requested = acquireStreamOperation(registration);
                acceptStreamOperation(requested, registration);
                if (requested == operation) wakeup.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                InterruptedIOException interrupted = new InterruptedIOException("Media stream read interrupted");
                interrupted.initCause(e);
                throw interrupted;
                        } catch (IOException e) {
                if (recovered || Thread.currentThread().isInterrupted() || !opened || streamRegistration != registration
                        || !registration.canRecover(operation)) throw e;
                recovered = true;
                                acceptStreamOperation(acquireStreamOperation(registration), registration);
            } finally {
                synchronized (sourceStateLock) {
                    if (countDownLatch == wakeup) countDownLatch = null;
                }
            }
        }
    }

    @Override
    public Uri getUri() {
        return uri;
    }

    @Override
    public void close() {
        closeStream(-1);
    }

    private void closeStream(long generation) {
        final FileLoader.StreamRegistration registration;
        final RandomAccessFile previousFile;
        final CountDownLatch wakeup;
        final Transfer ended;
        synchronized (sourceStateLock) {
            if (!opened || generation != -1 && openGeneration != generation) return;
            ++openGeneration;
            opened = false;
            registration = streamRegistration;
            streamRegistration = null;
            previousFile = file;
            file = null;
            currentFile = null;
            finalDescriptor = false;
            loadOperation = null;
            wakeup = countDownLatch;
            countDownLatch = null;
            ended = retireTransferLocked();
        uri = null;
        if (document != null) allStreams.remove(document.id, this);
        }
        if (wakeup != null) wakeup.countDown();
        try {
            if (registration != null) registration.close();
        } finally {
            if (previousFile != null) {
                try {
                    previousFile.close();
                } catch (IOException e) {
                    FileLog.e(e);
                }
            }
            if (ended != null) dispatchTransferEnded(ended);
        }
    }

    @Override
    public void newDataAvailable() {
//        FileLog.e("FileStreamLoadOperation " + document.id + " newDataAvailable me=" + this);
        final CountDownLatch latch;
        synchronized (sourceStateLock) {
            latch = countDownLatch;
        countDownLatch = null;
        }
        if (latch != null) {
            latch.countDown();
        }
    }

    public static void setPriorityForDocument(TLRPC.Document document, int priority) {
        if (document != null) {
            priorityMap.put(document.id, priority);
        }
    }

    @Nullable
    public static Uri prepareUri(int currentAccount, TLRPC.Document document, Object parent) {
        String attachFileName = FileLoader.getAttachFileName(document);
        File file = FileLoader.getInstance(currentAccount).getPathToAttach(document);

        if (file != null && file.exists()) {
            return Uri.fromFile(file);
        }
        try {
            String params =
                "?account=" + currentAccount +
                "&id=" + document.id +
                "&hash=" + document.access_hash +
                "&dc=" + document.dc_id +
                "&size=" + document.size +
                "&mime=" + URLEncoder.encode(document.mime_type != null ? document.mime_type : "application/octet-stream", "UTF-8") +
                "&rid=" + FileLoader.getInstance(currentAccount).getFileReference(parent) +
                "&name=" + URLEncoder.encode(FileLoader.getDocumentFileName(document), "UTF-8") +
                "&reference=" + Utilities.bytesToHex(document.file_reference != null ? document.file_reference : new byte[0]);
            return Uri.parse("tg://" + attachFileName + params);
        } catch (UnsupportedEncodingException e) {
            FileLog.e(e);
        }
        return null;
    }




    protected boolean isNetwork;
    private final ArrayList<TransferListener> listeners;

    @Nullable private Transfer activeTransfer;

    private static final class Transfer {
        final DataSpec spec;
        final boolean network;
        final TransferListener[] listeners;
        int dispatches, started;
        boolean ready, retired;

        Transfer(DataSpec spec, boolean network, TransferListener[] listeners) {
            this.spec = spec;
            this.network = network;
            this.listeners = listeners;
        }
    }

    @Override
    public final void addTransferListener(TransferListener listener) {
        checkNotNull(listener);
        synchronized (sourceStateLock) {
            if (!listeners.contains(listener)) listeners.add(listener);
        }
    }

    private TransferListener[] transferListeners() {
        synchronized (sourceStateLock) {
            return listeners.toArray(new TransferListener[0]);
        }
    }

    protected final void transferInitializing(DataSpec spec) {
        boolean network = isNetwork;
        for (TransferListener listener : transferListeners()) listener.onTransferInitializing(this, spec, network);
    }

    protected final void transferStarted(DataSpec spec) {
        final Transfer t = new Transfer(spec, isNetwork, transferListeners());
        synchronized (sourceStateLock) {
            if (!opened || activeTransfer != null) return;
            activeTransfer = t;
            t.dispatches++;
        }
        dispatchTransferStarted(t);
    }

    private void dispatchTransferStarted(Transfer t) {
        try {
            for (TransferListener listener : t.listeners) {
                t.started++;
                listener.onTransferStart(this, t.spec, t.network);
            }
            synchronized (sourceStateLock) {
                t.ready = true;
            }
        } finally {
            finishDispatch(t);
        }
    }

    protected final void bytesTransferred(int count) {
        final Transfer t;
        synchronized (sourceStateLock) {
            t = activeTransfer;
            if (t == null || !t.ready) return;
            t.dispatches++;
        }
        dispatchBytes(t, count);
    }

    private void dispatchBytes(Transfer t, int count) {
        try {
            for (TransferListener listener : t.listeners) listener.onBytesTransferred(this, t.spec, t.network, count);
        } finally {
            finishDispatch(t);
        }
    }

    private void finishDispatch(Transfer t) {
        synchronized (sourceStateLock) {
            if (--t.dispatches != 0 || !t.retired) return;
        }
        dispatchTransferEnded(t);
    }

    private Transfer retireTransferLocked() {
        Transfer t = activeTransfer;
        activeTransfer = null;
        if (t == null) return null;
        t.retired = true;
        return t.dispatches == 0 ? t : null;
    }

    /** Notifies listeners that a transfer ended. */
    protected final void transferEnded() {
        final Transfer t;
        synchronized (sourceStateLock) {
            t = retireTransferLocked();
        }
        if (t != null) dispatchTransferEnded(t);
    }

    private void dispatchTransferEnded(Transfer t) {
        for (int i = 0; i < t.started; i++) t.listeners[i].onTransferEnd(this, t.spec, t.network);
    }
}
