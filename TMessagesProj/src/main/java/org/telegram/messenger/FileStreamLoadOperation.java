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
import static androidx.media3.common.util.Util.castNonNull;

import android.net.Uri;

import androidx.annotation.Nullable;

import androidx.annotation.OptIn;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;

import org.telegram.tgnet.TLRPC;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
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
        final FileLoader.StreamRegistration previous;
        synchronized (sourceStateLock) {
            generation = ++openGeneration;
            opened = true;
            previous = streamRegistration;
            streamRegistration = null;
        }
        if (previous != null) previous.close();
        uri = dataSpec.uri;
        transferInitializing(dataSpec);
        currentAccount = Utilities.parseInt(uri.getQueryParameter("account"));
        parentObject = FileLoader.getInstance(currentAccount).getParentObject(Utilities.parseInt(uri.getQueryParameter("rid")));
        document = new TLRPC.TL_document();
        document.access_hash = Utilities.parseLong(uri.getQueryParameter("hash"));
        document.id = Utilities.parseLong(uri.getQueryParameter("id"));
        document.size = Utilities.parseLong(uri.getQueryParameter("size"));
        document.dc_id = Utilities.parseInt(uri.getQueryParameter("dc"));
        document.mime_type = uri.getQueryParameter("mime");
        document.file_reference = Utilities.hexToBytes(uri.getQueryParameter("reference"));
        TLRPC.TL_documentAttributeFilename filename = new TLRPC.TL_documentAttributeFilename();
        filename.file_name = uri.getQueryParameter("name");
        document.attributes.add(filename);
        if (document.mime_type.startsWith("video")) {
            document.attributes.add(new TLRPC.TL_documentAttributeVideo());
        } else if (document.mime_type.startsWith("audio")) {
            document.attributes.add(new TLRPC.TL_documentAttributeAudio());
        }
        currentOffset = dataSpec.position;
        requestedLength = dataSpec.length;
        final FileLoader.StreamRegistration inherited;
        synchronized (sourceStateLock) {
            inherited = streamOwnerAccount == currentAccount ? streamOwner : null;
        }
        final FileLoader.StreamRegistration registration = inherited == null
                ? FileLoader.getInstance(currentAccount).createStreamRegistration(this, playerStreamScope) : inherited.fork();
        boolean accepted;
        synchronized (sourceStateLock) {
            accepted = opened && openGeneration == generation;
            if (accepted) {
                streamRegistration = registration;
                streamOwner = registration;
                streamOwnerAccount = currentAccount;
            }
        }
        if (!accepted) {
            registration.close();
            throw new IOException("Media stream was closed during admission");
        }
        final FileLoadOperation operation = acquireStreamOperation(registration);
        synchronized (sourceStateLock) {
            accepted = opened && openGeneration == generation && streamRegistration == registration;
            if (accepted) loadOperation = operation;
        }
        if (!accepted || operation == null || registration.isClosed()) {
            registration.close();
            if (accepted) closeStream(registration);
            throw new IOException("Media stream admission was cancelled");
        }
        allStreams.put(document.id, this);
        if (!registration.isAttachedTo(operation)) {
            allStreams.remove(document.id, this);
            closeStream(registration);
            throw new IOException("Media stream was closed during admission");
        }
        bytesTransferred = 0;
        bytesRemaining = document.size - dataSpec.position;
        if (requestedLength != C.LENGTH_UNSET) {
            bytesRemaining = Math.min(bytesRemaining, requestedLength - bytesTransferred);
        }
        transferStarted(dataSpec);
        try {
            replaceStreamFile(operation.getCurrentFile(), operation, registration);
        } catch (IOException ignore) {
        }
        if (!registration.isAttachedTo(operation)) {
            closeStream(registration);
            throw new IOException("Media stream was closed");
        }
        FileLog.e("FileStreamLoadOperation " + document.id + " open operation=" + loadOperation + " currentFile=" + currentFile + " file=" + file + " bytesRemaining=" + bytesRemaining + " me=" + this);
        FileLog.e("FileStreamLoadOperation " + document.id + " " + MessageObject.getVideoWidth(document) + "x" + MessageObject.getVideoWidth(document) + " mime_type="+document.mime_type+" codec="+MessageObject.getVideoCodec(document)+" size="+ document.size);
        return bytesRemaining;
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
        return FileLoader.getInstance(currentAccount).loadStreamFileWithRegistration(this, document, null, parentObject,
                currentOffset, false, getCurrentPriority(), 0,
                () -> opened && streamRegistration == registration, registration);
    }

    FileLoader.StreamRegistration registrationForOperation(FileLoadOperation operation) {
        FileLoader.StreamRegistration registration = streamRegistration;
        return opened && registration != null && registration.isAttachedTo(operation) ? registration : null;
    }

    private void acceptStreamOperation(FileLoadOperation operation, FileLoader.StreamRegistration registration) throws IOException {
        synchronized (sourceStateLock) {
            if (operation == null || !opened || streamRegistration != registration || !registration.isAttachedTo(operation)) {
                throw new IOException("Media stream admission was cancelled");
            }
            loadOperation = operation;
        }
    }

    private void replaceStreamFile(File nextFile, FileLoadOperation operation, FileLoader.StreamRegistration registration) throws IOException {
        final RandomAccessFile previous;
        final long position;
        synchronized (sourceStateLock) {
            if (!opened || streamRegistration != registration || !registration.isAttachedTo(operation)) {
                throw new IOException("Media stream was closed");
            }
            previous = file;
            file = null;
            currentFile = nextFile;
            position = currentOffset;
        }
        if (previous != null) previous.close();
        if (nextFile == null) return;
        RandomAccessFile candidate = new RandomAccessFile(nextFile, "r");
        try {
            candidate.seek(position);
            long length = operation.isFinished() ? candidate.length() : -1;
            synchronized (sourceStateLock) {
                if (!opened || streamRegistration != registration || !registration.isAttachedTo(operation)) {
                    throw new IOException("Media stream was closed");
                }
                file = candidate;
                candidate = null;
                if (length >= 0) {
                    isNetwork = false;
                    bytesRemaining = length - position;
                    if (requestedLength != C.LENGTH_UNSET) {
                        bytesRemaining = Math.min(bytesRemaining, requestedLength - bytesTransferred);
                    }
                }
            }
        } finally {
            if (candidate != null) candidate.close();
        }
    }

    @Override
    public int read(byte[] buffer, int offset, int readLength) throws IOException {
        if (readLength == 0) {
//            FileLog.e("FileStreamLoadOperation " + document.id + " read 0 return");
            return 0;
        } else if (bytesRemaining == 0) {
//            FileLog.e("FileStreamLoadOperation " + document.id + " read RESULT_END_OF_INPUT");
            return C.RESULT_END_OF_INPUT;
        } else {
            final FileLoader.StreamRegistration registration = streamRegistration;
            if (registration == null || registration.isClosed()) throw new IOException("Media stream was closed");
            int availableLength = 0;
            int bytesRead;
            try {
                if (bytesRemaining < readLength) {
                    readLength = (int) bytesRemaining;
                }
                while (opened && streamRegistration == registration && (availableLength == 0 || file == null)) {
                    FileLoadOperation operation = loadOperation;
                    if (registration.isClosed() || operation == null) {
                        throw new IOException("Media stream was closed");
                    }
                    final CountDownLatch wakeup = new CountDownLatch(1);
                    synchronized (sourceStateLock) {
                        if (!opened || streamRegistration != registration) return 0;
                    countDownLatch = wakeup;
                    }
                    long[] result = operation.getDownloadedLengthFromOffset(currentOffset, readLength);
                    if (result[2] != 0) {
                        throw new IOException("Media download failed or was cancelled");
                    }
                    availableLength = (int) result[0];
                    if (availableLength == 0) {
                        operation = acquireStreamOperation(registration);
                        acceptStreamOperation(operation, registration);
//                        FileLog.e("FileStreamLoadOperation " + document.id + " read sleeping.... Zzz");
                        if (opened) {
                            wakeup.await(1, TimeUnit.SECONDS);
                        }
                    }
//                    FileLog.e("FileStreamLoadOperation " + document.id + " read availableLength=" + availableLength);
                    File currentFileFast = operation.getCurrentFileFast();
                    if (file == null || !Objects.equals(currentFile, currentFileFast)) {
                        if (BuildVars.LOGS_ENABLED) {
                            FileLog.d("check stream file " + currentFileFast);
                        }
                        try {
                            replaceStreamFile(currentFileFast, operation, registration);
                        } catch (IOException e) {
                            if (!registration.isAttachedTo(operation)) throw e;
                            if (operation.isFinished() && currentFileFast != null && !currentFileFast.exists()) {
                                if (!FileLoader.getInstance(currentAccount).recoverStreamFile(operation, registration)) throw e;
                                acceptStreamOperation(acquireStreamOperation(registration), registration);
                            }
                        }
                    } else {
//                        FileLog.e("FileStreamLoadOperation " + document.id + " read have exact same file");
                    }
                }
                if (!opened || streamRegistration != registration) {
//                    FileLog.e("FileStreamLoadOperation " + document.id + " read return, not opened");
                    return 0;
                }
                final RandomAccessFile current;
                synchronized (sourceStateLock) {
                    if (!opened || streamRegistration != registration || !registration.isAttachedTo(loadOperation)) {
                        throw new IOException("Media stream was closed");
                    }
                    current = file;
                }
                bytesRead = current.read(buffer, offset, availableLength);
                if (bytesRead > 0) {
                    synchronized (sourceStateLock) {
                        if (!opened || streamRegistration != registration) throw new IOException("Media stream was closed");
                    currentOffset += bytesRead;
                    bytesRemaining -= bytesRead;
                    bytesTransferred += bytesRead;
                    }
                    bytesTransferred(bytesRead);
                }
            } catch (InterruptedException e) {
                FileLog.e(e);
                Thread.currentThread().interrupt();
                return C.RESULT_NOTHING_READ;
            } catch (Exception e) {
                throw new IOException(e);
            } finally {
                synchronized (sourceStateLock) {
                    if (streamRegistration == registration) countDownLatch = null;
                }
            }
            return bytesRead;
        }
    }

    @Override
    public Uri getUri() {
        return uri;
    }

    @Override
    public void close() {
        closeStream(null);
    }

    private void closeStream(FileLoader.StreamRegistration expected) {
        final FileLoader.StreamRegistration registration;
        final RandomAccessFile previousFile;
        final CountDownLatch wakeup;
        final boolean transferred;
        synchronized (sourceStateLock) {
            if (expected != null && streamRegistration != expected) return;
            ++openGeneration;
            opened = false;
            registration = streamRegistration;
            streamRegistration = null;
            previousFile = file;
            file = null;
            loadOperation = null;
            wakeup = countDownLatch;
            countDownLatch = null;
            transferred = this.dataSpec != null;
        }
        FileLog.e("FileStreamLoadOperation " + (document == null ? 0 : document.id) + " close me=" + this);
        if (wakeup != null) wakeup.countDown();
        if (registration != null) registration.close();
        if (previousFile != null) {
            try {
                previousFile.close();
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        uri = null;
        if (document != null) allStreams.remove(document.id, this);
        if (transferred) transferEnded();
    }

    @Override
    public void newDataAvailable() {
//        FileLog.e("FileStreamLoadOperation " + document.id + " newDataAvailable me=" + this);
        CountDownLatch latch = countDownLatch;
        countDownLatch = null;
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
                "&mime=" + URLEncoder.encode(document.mime_type, "UTF-8") +
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

    private int listenerCount;
    @Nullable private DataSpec dataSpec;

    @Override
    public final void addTransferListener(TransferListener transferListener) {
        checkNotNull(transferListener);
        if (!listeners.contains(transferListener)) {
            listeners.add(transferListener);
            listenerCount++;
        }
    }

    /**
     * Notifies listeners that data transfer for the specified {@link DataSpec} is being initialized.
     *
     * @param dataSpec {@link DataSpec} describing the data for initializing transfer.
     */
    protected final void transferInitializing(DataSpec dataSpec) {
        for (int i = 0; i < listenerCount; i++) {
            listeners.get(i).onTransferInitializing(/* source= */ this, dataSpec, isNetwork);
        }
    }

    /**
     * Notifies listeners that data transfer for the specified {@link DataSpec} started.
     *
     * @param dataSpec {@link DataSpec} describing the data being transferred.
     */
    protected final void transferStarted(DataSpec dataSpec) {
        this.dataSpec = dataSpec;
        for (int i = 0; i < listenerCount; i++) {
            listeners.get(i).onTransferStart(/* source= */ this, dataSpec, isNetwork);
        }
    }

    /**
     * Notifies listeners that bytes were transferred.
     *
     * @param bytesTransferred The number of bytes transferred since the previous call to this method
     *     (or if the first call, since the transfer was started).
     */
    protected final void bytesTransferred(int bytesTransferred) {
        DataSpec dataSpec = castNonNull(this.dataSpec);
        for (int i = 0; i < listenerCount; i++) {
            listeners
                    .get(i)
                    .onBytesTransferred(/* source= */ this, dataSpec, isNetwork, bytesTransferred);
        }
    }

    /** Notifies listeners that a transfer ended. */
    protected final void transferEnded() {
        DataSpec dataSpec = castNonNull(this.dataSpec);
        for (int i = 0; i < listenerCount; i++) {
            listeners.get(i).onTransferEnd(/* source= */ this, dataSpec, isNetwork);
        }
        this.dataSpec = null;
    }
}
