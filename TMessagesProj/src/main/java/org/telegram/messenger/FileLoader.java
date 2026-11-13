/* Modifications Copyright (C) 2026 Ettacent */

/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import android.text.TextUtils;
import android.util.SparseArray;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stories;
import org.telegram.ui.LaunchActivity;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLongArray;
import java.util.concurrent.atomic.AtomicReferenceArray;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class FileLoader extends BaseController {

    private static final AtomicLongArray retiredSessions = new AtomicLongArray(UserConfig.MAX_ACCOUNT_COUNT);
    private static final AtomicReferenceArray<TransferOwner> clearingOwners = new AtomicReferenceArray<>(UserConfig.MAX_ACCOUNT_COUNT);

    public static final class TransferOwner {
        final int account;
        final long userId;
        final long session;
        final int loginTime;
        final boolean capturedCurrent;

        private TransferOwner(int account) {
            this.account = account;
            session = app.nimarkogram.messenger.notifications.InAppNotifications.session(account);
            TransferOwner clearing = clearingOwners.get(account);
            UserConfig config = UserConfig.getInstance(account);
            userId = config.getClientUserId();
            loginTime = config.loginTime;
            capturedCurrent = (clearing == null || clearing.userId != userId)
                    && clearing == clearingOwners.get(account)
                    && session == app.nimarkogram.messenger.notifications.InAppNotifications.session(account)
                    && userId == config.getClientUserId();
        }

        public boolean isCurrent() {
            return capturedCurrent && retiredSessions.get(account) <= session
                    && app.nimarkogram.messenger.notifications.InAppNotifications.isCurrent(account, userId, session)
                    && UserConfig.getInstance(account).loginTime == loginTime;
        }

        boolean matches(TransferOwner other) {
            return other != null && account == other.account && userId == other.userId && session == other.session && loginTime == other.loginTime;
        }
    }

    static TransferOwner captureTransferOwner(int account) {
        return new TransferOwner(account);
    }

    public static final class PlayerStreamScope {
        private final FileLoader loader;
        private final TransferOwner owner;
        private final IdentityHashMap<FileLoadOperation, Boolean> operations = new IdentityHashMap<>();
        private volatile boolean closed;
        private volatile int priority;

        private PlayerStreamScope(FileLoader loader, TransferOwner owner, int priority, boolean closed) {
            this.loader = loader;
            this.owner = owner;
            this.priority = priority;
            this.closed = closed;
        }

        public PlayerStreamScope fork(int priority) {
            return new PlayerStreamScope(loader, owner, priority, closed || !owner.isCurrent());
        }

        public boolean isClosed() {
            return closed || !owner.isCurrent();
        }

        public int getPriority() {
            return priority;
        }

        public void setPriority(int priority) {
            final ArrayList<FileLoadOperation> changed;
            synchronized (loader.streamOperationLock) {
                if (closed || this.priority == priority) return;
                this.priority = priority;
                changed = new ArrayList<>(operations.keySet());
            }
            for (FileLoadOperation operation : changed) loader.refreshStreamOperation(operation);
        }

        public void close() {
            final ArrayList<FileLoadOperation> changed;
            synchronized (loader.streamOperationLock) {
                if (closed) return;
                closed = true;
                changed = new ArrayList<>(operations.keySet());
                operations.clear();
                for (FileLoadOperation operation : changed) {
                    StreamOperationState state = loader.streamOperations.get(operation);
                    if (state != null) {
                        state.scopes.remove(this);
                        state.cleanupRequested = true;
                    }
                }
            }
            for (FileLoadOperation operation : changed) loader.refreshStreamOperation(operation);
        }
    }

    private static final class StreamCancellation {
        volatile boolean cancelled;
        StreamRegistration current;
    }

    public static final class StreamRegistration {
        private final FileLoader loader;
        private final TransferOwner owner;
        final FileLoadOperationStream reader;
        private final PlayerStreamScope scope;
        private final long generation;
        private final StreamCancellation cancellation;
        private volatile FileLoadOperation operation;
        private FileLoadOperation recoveryOperation;
        private volatile boolean closed;
        private volatile boolean terminallyCancelled;

        private StreamRegistration(FileLoader loader, TransferOwner owner, FileLoadOperationStream reader,
                                   PlayerStreamScope scope, long generation, boolean closed, StreamCancellation cancellation) {
            this.loader = loader;
            this.owner = owner;
            this.reader = reader;
            this.scope = scope;
            this.generation = generation;
            this.closed = closed;
            this.cancellation = cancellation;
        }

        boolean isAttachedTo(FileLoadOperation operation) {
            return operation instanceof AccountFileLoadOperation && !((AccountFileLoadOperation) operation).retired.get()
                    && !((AccountFileLoadOperation) operation).streamUserCancelled
                    && !closed && !terminallyCancelled && !cancellation.cancelled
                    && this.operation == operation && (scope == null || !scope.closed);
        }

        public boolean isClosed() {
            return closed || terminallyCancelled || cancellation.cancelled || !owner.isCurrent() || scope != null && scope.closed;
        }

        boolean isReaderCancelled() {
            return cancellation.cancelled;
        }

        StreamRegistration fork() {
            return loader.createStreamRegistration(reader, scope, owner, cancellation);
        }

        StreamRegistration restart() {
            return loader.createStreamRegistration(reader, scope, owner);
        }

        Runnable prepareCancel() {
            final AccountFileLoadOperation current;
            final ArrayList<StreamRegistration> cancelled;
            synchronized (loader.streamOperationLock) {
                if (!(operation instanceof AccountFileLoadOperation)) return null;
                current = (AccountFileLoadOperation) operation;
                if (!owner.matches(current.owner) || loader.loadOperationPaths.get(current.getFileName()) != current
                        || !current.retired.compareAndSet(false, true)) return null;
                current.streamUserCancelled = true;
                cancelled = loader.cancelStreamRegistrationsLocked(current, cancellation);
                loader.loadOperationPaths.remove(current.getFileName(), current);
            }
            return () -> {
                loader.closeCancelledStreamRegistrations(cancelled);
                current.finishRetirement(true);
                loader.clearLoadingUi(current.getFileName(), current);
                loader.forgetStreamOperation(current);
                loader.runUploadTask(() -> {
                    if (Thread.currentThread() == fileLoaderQueue) {
                        current.getQueue().remove(current);
                        current.getQueue().checkLoadingOperations();
                    }
                });
            };
        }

        public void close() {
            final FileLoadOperation previous;
            synchronized (loader.streamOperationLock) {
                if (closed) return;
                closed = true;
                previous = operation;
                operation = null;
                recoveryOperation = null;
                loader.pendingStreamRecoveries.remove(this);
                if (cancellation.current == this) cancellation.current = null;
                if (previous instanceof AccountFileLoadOperation) {
                    ((AccountFileLoadOperation) previous).streamRegistrations.remove(this);
                }
                StreamOperationState state = loader.streamOperations.get(previous);
                if (state != null) state.readers.remove(this);
            }
            if (previous != null) {
                previous.removeStreamListener(reader, this);
                loader.refreshStreamOperation(previous);
            }
        }
    }

    private static final class StreamOperationState {
        final TransferOwner owner;
        final IdentityHashMap<StreamRegistration, Integer> readers = new IdentityHashMap<>();
        final IdentityHashMap<PlayerStreamScope, Boolean> scopes = new IdentityHashMap<>();
        boolean nonStreamDemand;
        boolean cleanupRequested;
        boolean retiring;
        int nonStreamPriority;
        int warmupPriority;
        int legacyPriority = -1;
        int appliedPriority = -1;

        StreamOperationState(TransferOwner owner) {
            this.owner = owner;
        }

        int priority() {
            int priority = Math.max(PRIORITY_LOW, legacyPriority);
            boolean retained = legacyPriority >= 0 || nonStreamDemand;
            if (nonStreamDemand) priority = Math.max(priority, nonStreamPriority);
            for (PlayerStreamScope scope : scopes.keySet()) {
                if (!scope.closed) {
                    priority = Math.max(priority, scope.priority);
                    retained = true;
                }
            }
            for (StreamRegistration registration : readers.keySet()) {
                if (!registration.closed && registration.scope == null) {
                    priority = Math.max(priority, readers.get(registration));
                    retained = true;
                }
            }
            return retained ? priority : warmupPriority;
        }
    }

    public PlayerStreamScope createPlayerStreamScope(long userId, int priority) {
        TransferOwner owner = captureTransferOwner(currentAccount);
        return new PlayerStreamScope(this, owner, priority, userId == 0 || owner.userId != userId || !owner.isCurrent());
    }

    StreamRegistration createStreamRegistration(FileLoadOperationStream reader, PlayerStreamScope scope) {
        TransferOwner owner = scope == null ? captureTransferOwner(currentAccount) : scope.owner;
        return createStreamRegistration(reader, scope, owner);
    }

    private StreamRegistration createStreamRegistration(FileLoadOperationStream reader, PlayerStreamScope scope, TransferOwner owner) {
        return createStreamRegistration(reader, scope, owner, new StreamCancellation());
    }

    private StreamRegistration createStreamRegistration(FileLoadOperationStream reader, PlayerStreamScope scope, TransferOwner owner, StreamCancellation cancellation) {
        boolean closed = reader == null || owner.account != currentAccount || scope != null && scope.loader != this
                || !owner.isCurrent() || scope != null && scope.closed;
        synchronized (streamOperationLock) {
            return new StreamRegistration(this, owner, reader, scope, ++streamRegistrationGeneration, closed || cancellation.cancelled, cancellation);
        }
    }

    private void cancelStreamRegistrationLocked(StreamRegistration registration) {
        registration.terminallyCancelled = true;
        if (registration.cancellation.current == registration) registration.cancellation.cancelled = true;
        registration.recoveryOperation = null;
    }

    private ArrayList<StreamRegistration> cancelStreamRegistrationsLocked(AccountFileLoadOperation operation, StreamCancellation except) {
        ArrayList<StreamRegistration> cancelled = new ArrayList<>();
        for (StreamRegistration registration : operation.streamRegistrations.keySet()) {
            if (registration.cancellation != except && registration.operation == operation && !registration.closed) {
                cancelStreamRegistrationLocked(registration);
                cancelled.add(registration);
            }
        }
        for (StreamRegistration registration : cancelled) pendingStreamRecoveries.remove(registration);
        if (loadOperationPaths.get(operation.getFileName()) == operation) {
            cancelled.addAll(cancelPendingStreamRecoveriesLocked(operation.getFileName(), operation.owner, except));
        }
        return cancelled;
    }

    private ArrayList<StreamRegistration> cancelPendingStreamRecoveriesLocked(String name, TransferOwner owner, StreamCancellation except) {
        ArrayList<StreamRegistration> cancelled = new ArrayList<>();
        for (StreamRegistration registration : pendingStreamRecoveries.keySet()) {
            FileLoadOperation previous = registration.recoveryOperation;
            if (registration.cancellation != except && !registration.closed && previous != null
                    && owner.matches(registration.owner) && name.equals(previous.getFileName())) {
                cancelStreamRegistrationLocked(registration);
                cancelled.add(registration);
            }
        }
        for (StreamRegistration registration : cancelled) pendingStreamRecoveries.remove(registration);
        return cancelled;
    }

    private void closeCancelledStreamRegistrations(ArrayList<StreamRegistration> cancelled) {
        for (StreamRegistration registration : cancelled) {
            try {
                registration.close();
            } catch (RuntimeException e) {
                FileLog.e(e);
            }
            try {
                registration.reader.newDataAvailable();
            } catch (RuntimeException e) {
                FileLog.e(e);
            }
        }
    }

    private boolean admitStreamOperation(FileLoadOperation operation, StreamRegistration registration,
                                         boolean nonStreamDemand, int priority) {
        final TransferOwner owner = downloadOwner(operation);
        if (owner == null || !owner.isCurrent() || registration != null && !registration.owner.isCurrent()) return false;
        FileLoadOperation previous = null;
        synchronized (streamOperationLock) {
            if (((AccountFileLoadOperation) operation).retired.get()) return false;
            StreamOperationState state = streamOperations.get(operation);
            if (state != null && state.retiring) return false;
            if (registration != null && (registration.closed || registration.terminallyCancelled || registration.cancellation.cancelled || registration.loader != this
                    || !owner.matches(registration.owner) || registration.scope != null && registration.scope.closed)) return false;
            if (registration != null && registration.operation instanceof AccountFileLoadOperation) {
                AccountFileLoadOperation current = (AccountFileLoadOperation) registration.operation;
                if (current.streamUserCancelled || current.retired.get()
                        && !current.streamReleaseRetired && registration.recoveryOperation != current) return false;
            }
            if (loadOperationPaths.get(operation.getFileName()) != operation) return false;
            if (state == null) streamOperations.put(operation, state = new StreamOperationState(owner));
            state.warmupPriority = priority;
            if (nonStreamDemand) {
                state.nonStreamDemand = true;
                state.nonStreamPriority = priority;
            }
            if (registration != null) {
                previous = registration.operation;
                if (previous != null && previous != operation) {
                    ((AccountFileLoadOperation) previous).streamRegistrations.remove(registration);
                    StreamOperationState old = streamOperations.get(previous);
                    if (old != null) old.readers.remove(registration);
                }
                registration.operation = operation;
                registration.recoveryOperation = null;
                registration.cancellation.current = registration;
                pendingStreamRecoveries.remove(registration);
                ((AccountFileLoadOperation) operation).streamRegistrations.put(registration, true);
                state.readers.put(registration, priority);
                if (registration.scope != null) {
                    state.scopes.put(registration.scope, true);
                    registration.scope.operations.put(operation, true);
                }
            }
        }
        if (previous != null && previous != operation) {
            previous.removeStreamListener(registration.reader, registration);
            refreshStreamOperation(previous);
        }
        return true;
    }

    private int streamPriority(FileLoadOperation operation) {
        synchronized (streamOperationLock) {
            StreamOperationState state = streamOperations.get(operation);
            if (state == null) return PRIORITY_LOW;
            state.appliedPriority = state.priority();
            return state.appliedPriority;
        }
    }

    private void forgetStreamOperation(FileLoadOperation operation) {
        synchronized (streamOperationLock) {
            StreamOperationState state = streamOperations.remove(operation);
            if (state != null) {
                for (PlayerStreamScope scope : state.scopes.keySet()) scope.operations.remove(operation);
            }
        }
    }

    private void refreshStreamOperation(FileLoadOperation operation) {
        runUploadTask(() -> {
            final TransferOwner owner = downloadOwner(operation);
            if (owner == null || !owner.isCurrent()) return;
            final boolean onLoaderQueue = Thread.currentThread() == fileLoaderQueue;
            boolean retire = false;
            boolean cancel = false;
            int priority = -1;
            synchronized (streamOperationLock) {
                StreamOperationState state = streamOperations.get(operation);
                if (state == null || !owner.matches(state.owner) || state.retiring || loadOperationPaths.get(operation.getFileName()) != operation
                        || ((AccountFileLoadOperation) operation).retired.get()) return;
                LoadOperationUIObject ui = loadOperationPathsUI.get(operation.getFileName());
                Runnable pending = ui == null ? null : ui.loadInternalRunnable;
                boolean pendingDownload = ui != null && !ui.cancelled && owner.matches(ui.owner)
                        && pending instanceof ImageLoadRunnable && ((ImageLoadRunnable) pending).pendingAdmission;
                if (state.cleanupRequested && state.readers.isEmpty() && state.scopes.isEmpty()
                        && !state.nonStreamDemand && !pendingDownload) {
                    state.retiring = true;
                    retire = loadOperationPaths.remove(operation.getFileName(), operation);
                    if (retire) {
                        AccountFileLoadOperation current = (AccountFileLoadOperation) operation;
                        cancel = current.retired.compareAndSet(false, true);
                        current.streamReleaseRetired = cancel;
                    }
                } else if (onLoaderQueue) {
                    int effective = state.priority();
                    if (effective != state.appliedPriority) {
                        state.appliedPriority = effective;
                        priority = effective;
                    }
                }
            }
            if (retire) {
                ((AccountFileLoadOperation) operation).finishRetirement(cancel);
                clearLoadingUi(operation.getFileName(), operation);
                forgetStreamOperation(operation);
                if (onLoaderQueue) {
                    operation.getQueue().remove(operation);
                    operation.getQueue().checkLoadingOperations();
                }
            } else if (priority >= 0) {
                operation.setForceRequest(priority > PRIORITY_LOW);
                operation.setPriority(getPriorityValue(priority));
                operation.getQueue().add(operation);
                operation.getQueue().checkLoadingOperations();
            }
        });
    }

    public static TransferOwner onAccountLoggedOut(int account) {
        TransferOwner owner = captureTransferOwner(account);
        clearingOwners.set(account, owner);
        long retired = retiredSessions.get(account);
        while (retired <= owner.session && !retiredSessions.compareAndSet(account, retired, owner.session + 1)) {
            retired = retiredSessions.get(account);
        }
        final FileLoader loader;
        synchronized (FileLoader.class) {
            loader = Instance[account];
        }
        if (loader != null) {
            try {
                loader.retireTransfers(owner);
            } catch (RuntimeException e) {
                FileLog.e(e);
            }
        }
        return owner;
    }

    public static void onAccountOwnerCleared(TransferOwner owner) {
        clearingOwners.compareAndSet(owner.account, owner, null);
    }
    private static final int PRIORITY_STREAM = 4;
    public static final int PRIORITY_HIGH = 3;
    public static final int PRIORITY_NORMAL_UP = 2;
    public static final int PRIORITY_NORMAL = 1;
    public static final int PRIORITY_LOW = 0;

    private int priorityIncreasePointer;

    private static Pattern sentPattern;

    public static FilePathDatabase.FileMeta getFileMetadataFromParent(int currentAccount, Object parentObject) {
        if (parentObject instanceof String) {
            String str = (String) parentObject;
            if (str.startsWith("sent_")) {
                if (sentPattern == null) {
                    sentPattern = Pattern.compile("sent_.*_([0-9]+)_([0-9]+)_([0-9]+)_([0-9]+)");
                }
                try {
                    Matcher matcher = sentPattern.matcher(str);
                    if (matcher.matches()) {
                        FilePathDatabase.FileMeta fileMeta = new FilePathDatabase.FileMeta();
                        fileMeta.messageId = Integer.parseInt(matcher.group(1));
                        fileMeta.dialogId = Long.parseLong(matcher.group(2));
                        fileMeta.messageType = Integer.parseInt(matcher.group(3));
                        fileMeta.messageSize = Long.parseLong(matcher.group(4));
                        return fileMeta;
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
        } else if (parentObject instanceof MessageObject) {
            MessageObject messageObject = (MessageObject) parentObject;
            FilePathDatabase.FileMeta fileMeta = new FilePathDatabase.FileMeta();
            fileMeta.messageId = messageObject.getId();
            fileMeta.dialogId = messageObject.getDialogId();
            fileMeta.messageType = messageObject.type;
            fileMeta.messageSize = messageObject.getSize();
            return fileMeta;
        } else if (parentObject instanceof TL_stories.StoryItem) {
            TL_stories.StoryItem storyItem = (TL_stories.StoryItem) parentObject;
            FilePathDatabase.FileMeta fileMeta = new FilePathDatabase.FileMeta();
            fileMeta.dialogId = storyItem.dialogId;
            fileMeta.messageType = MessageObject.TYPE_STORY;
            return fileMeta;
        }
        return null;
    }

    public static TLRPC.VideoSize getVectorMarkupVideoSize(TLRPC.Photo photo) {
        if (photo == null || photo.video_sizes == null) {
            return null;
        }
        for (int i = 0; i < photo.video_sizes.size(); i++) {
            TLRPC.VideoSize videoSize = photo.video_sizes.get(i);
            if (videoSize instanceof TLRPC.TL_videoSizeEmojiMarkup || videoSize instanceof TLRPC.TL_videoSizeStickerMarkup) {
                return videoSize;
            }
        }
        return null;
    }

    public static TLRPC.VideoSize getEmojiMarkup(ArrayList<TLRPC.VideoSize> video_sizes) {
        for (int i = 0; i < video_sizes.size(); i++) {
            if (video_sizes.get(i) instanceof TLRPC.TL_videoSizeEmojiMarkup || video_sizes.get(i) instanceof TLRPC.TL_videoSizeStickerMarkup) {
                return video_sizes.get(i);
            }
        }
        return null;
    }

    private int getPriorityValue(int priorityType) {
        if (priorityType == PRIORITY_STREAM) {
            return Integer.MAX_VALUE;
        } else if (priorityType == PRIORITY_HIGH) {
            priorityIncreasePointer++;
            return FileLoaderPriorityQueue.PRIORITY_VALUE_MAX + priorityIncreasePointer;
        } else if (priorityType == PRIORITY_NORMAL_UP) {
            priorityIncreasePointer++;
            return FileLoaderPriorityQueue.PRIORITY_VALUE_NORMAL + priorityIncreasePointer;
        } else if (priorityType == PRIORITY_NORMAL) {
            return FileLoaderPriorityQueue.PRIORITY_VALUE_NORMAL;
        } else {
            return 0;
        }
    }

    public DispatchQueue2 getFileLoaderQueue() {
        return fileLoaderQueue;
    }

    public void setLocalPathTo(TLObject attach, String attachPath) {
        long documentId = 0;
        int dcId = 0;
        int type = 0;
        if (attach instanceof TLRPC.Document) {
            TLRPC.Document document = (TLRPC.Document) attach;
            if (document.key != null) {
                type = MEDIA_DIR_CACHE;
            } else {
                if (MessageObject.isVoiceDocument(document)) {
                    type = MEDIA_DIR_AUDIO;
                } else if (MessageObject.isVideoDocument(document)) {
                    type = MEDIA_DIR_VIDEO;
                } else {
                    type = MEDIA_DIR_DOCUMENT;
                }
            }
            documentId = document.id;
            dcId = document.dc_id;
            filePathDatabase.putPath(documentId, dcId, type, FilePathDatabase.FLAG_LOCALLY_CREATED, attachPath);
        } else if (attach instanceof TLRPC.PhotoSize) {
            TLRPC.PhotoSize photoSize = (TLRPC.PhotoSize) attach;
            if (photoSize instanceof TLRPC.TL_photoStrippedSize || photoSize instanceof TLRPC.TL_photoPathSize) {
                return;
            } else if (photoSize.location == null || photoSize.location.key != null || photoSize.location.volume_id == Integer.MIN_VALUE && photoSize.location.local_id < 0 || photoSize.size < 0) {
                type = MEDIA_DIR_CACHE;
            } else {
                type = MEDIA_DIR_IMAGE;
            }
            documentId = photoSize.location.volume_id;
            dcId = photoSize.location.dc_id + (photoSize.location.local_id << 16);
            filePathDatabase.putPath(documentId, dcId, type, FilePathDatabase.FLAG_LOCALLY_CREATED, attachPath);
        }
    }


    public interface FileLoaderDelegate {
        void fileUploadProgressChanged(FileUploadOperation operation, String location, long uploadedSize, long totalSize, boolean isEncrypted);

        void fileDidUploaded(String location, TLRPC.InputFile inputFile, TLRPC.InputEncryptedFile inputEncryptedFile, byte[] key, byte[] iv, long totalFileSize);

        void fileDidFailedUpload(String location, boolean isEncrypted);

        void fileDidLoaded(String location, File finalFile, Object parentObject, int type);

        void fileDidFailedLoad(String location, int state);

        void fileLoadProgressChanged(FileLoadOperation operation, String location, long uploadedSize, long totalSize);
        default void fileUploadProgressChanged(FileUploadOperation operation, String location, long loaded, long total, boolean encrypted, TransferOwner owner) {
            if (owner.isCurrent()) fileUploadProgressChanged(operation, location, loaded, total, encrypted);
        }

        default void fileDidUploaded(String location, TLRPC.InputFile file, TLRPC.InputEncryptedFile encryptedFile, byte[] key, byte[] iv, long size, TransferOwner owner, FileUploadOperation operation) {
            if (owner.isCurrent()) fileDidUploaded(location, file, encryptedFile, key, iv, size);
        }

        default void fileDidFailedUpload(String location, boolean encrypted, TransferOwner owner, FileUploadOperation operation) {
            if (owner.isCurrent()) fileDidFailedUpload(location, encrypted);
        }

        default void fileDidLoaded(String location, File file, Object parent, int type, TransferOwner owner) {
            if (owner.isCurrent()) fileDidLoaded(location, file, parent, type);
        }

        default void fileDidLoaded(String location, File file, Object parent, int type, TransferOwner owner, FileLoadOperation operation) {
            fileDidLoaded(location, file, parent, type, owner);
        }

        default void fileDidFailedLoad(String location, int state, TransferOwner owner) {
            if (owner.isCurrent()) fileDidFailedLoad(location, state);
        }

        default void fileDidFailedLoad(String location, int state, TransferOwner owner, FileLoadOperation operation) {
            fileDidFailedLoad(location, state, owner);
        }

        default void fileLoadProgressChanged(FileLoadOperation operation, String location, long loaded, long total, TransferOwner owner) {
            if (owner.isCurrent()) fileLoadProgressChanged(operation, location, loaded, total);
        }
    }

    public interface UploadFileCallback {
        void onProgress(UploadRequest request, long uploadedSize, long totalSize);
        void onUploaded(UploadRequest request, TLRPC.InputFile file);
        void onFailed(UploadRequest request);
    }

    public interface UploadConsumerCallback {
        void onProgress(UploadRequest request, long uploadedSize, long totalSize);
        void onUploaded(UploadRequest request, UploadResult result);
        void onFailed(UploadRequest request);
    }

    public static final class UploadResult {
        public final TLRPC.InputFile file;
        public final TLRPC.InputEncryptedFile encryptedFile;
        public final byte[] key;
        public final byte[] iv;
        public final long totalSize;

        private UploadResult(TLRPC.InputFile file, TLRPC.InputEncryptedFile encryptedFile, byte[] key, byte[] iv, long totalSize) {
            this.file = file;
            this.encryptedFile = encryptedFile;
            this.key = key == null ? null : key.clone();
            this.iv = iv == null ? null : iv.clone();
            this.totalSize = totalSize;
        }
    }

    private final class UploadOperationOwner {
        final TransferOwner transfer = captureTransferOwner(currentAccount);
        boolean hasLegacyConsumer;
        boolean cancelling;
        ArrayList<Runnable> deferredAdmissions;

        boolean isCurrent() {
            return transfer.isCurrent();
        }
    }

    public final class UploadRequest {
        private final String location;
        private final boolean encrypted;
        private final UploadConsumerCallback callback;
        private final UploadOperationOwner owner = new UploadOperationOwner();
        private FileUploadOperation operation;
        private volatile boolean cancelled;
        private volatile boolean finished;
        private long lastProgressTime;
        private long newAvailableSize;
        private long newFinalSize;
        private Float newProgress;
        private boolean newDataPending;
        private boolean dataFlushScheduled;
        private boolean dataFlushing;

        private UploadRequest(String location, boolean encrypted, UploadConsumerCallback callback) {
            this.location = location;
            this.encrypted = encrypted;
            this.callback = callback;
        }

        public void checkNewDataAvailable(long availableSize, long finalSize) {
            checkNewDataAvailable(availableSize, finalSize, null);
        }

        public void checkNewDataAvailable(long availableSize, long finalSize, Float progress) {
            if (availableSize < 0 || finalSize < 0) return;
            boolean schedule;
            synchronized (uploadOperationLock) {
                if (cancelled || finished || !owner.isCurrent()) return;
                newAvailableSize = Math.max(newAvailableSize, availableSize);
                if (newFinalSize == 0 && finalSize != 0) newFinalSize = finalSize;
                if (newFinalSize != 0) newAvailableSize = newFinalSize;
                if (progress != null) newProgress = progress;
                newDataPending = true;
                schedule = operation != null && !dataFlushScheduled && !dataFlushing;
                if (schedule) dataFlushScheduled = true;
            }
            if (schedule) runUploadTask(() -> flushUploadRequestData(this));
        }

        public void cancel() {
            cancelled = true;
            runUploadTask(() -> {
                final FileUploadOperation current;
                final boolean owned;
                final UploadOperationOwner operationOwner;
                synchronized (uploadOperationLock) {
                    if (finished) return;
                    finished = true;
                    clearUploadRequestData(this);
                    current = operation;
                    final ArrayList<UploadRequest> requests = uploadRequests.get(current);
                    boolean retained = false;
                    if (requests != null) {
                        requests.remove(this);
                        if (requests.isEmpty()) uploadRequests.remove(current);
                        else {
                            for (UploadRequest request : requests) {
                                if (!request.cancelled && !request.finished) {
                                    retained = true;
                                    break;
                                }
                            }
                        }
                    }
                    operationOwner = uploadOwners.get(current);
                    owned = !retained && operationOwner != null && !operationOwner.hasLegacyConsumer && !operationOwner.cancelling
                            && (encrypted ? uploadOperationPathsEnc : uploadOperationPaths).get(location) == current;
                    if (owned) operationOwner.cancelling = true;
                }
                if (owned) {
                    try {
                        if (current.cancelIfPending() && retireUploadOperation(location, encrypted, current)) {
                            finishUploadRequests(current, null);
                            if (delegate != null) delegate.fileDidFailedUpload(location, encrypted, current.getTransferOwner(), current);
                        }
                    } finally {
                        finishUploadCancellation(operationOwner);
                    }
                }
            });
        }
    }

    public static final int MEDIA_DIR_IMAGE = 0;
    public static final int MEDIA_DIR_AUDIO = 1;
    public static final int MEDIA_DIR_VIDEO = 2;
    public static final int MEDIA_DIR_DOCUMENT = 3;
    public static final int MEDIA_DIR_CACHE = 4;
    public static final int MEDIA_DIR_FILES = 5;
    public static final int MEDIA_DIR_STORIES = 6;

    public static final int MEDIA_DIR_IMAGE_PUBLIC = 100;
    public static final int MEDIA_DIR_VIDEO_PUBLIC = 101;

    public static final int IMAGE_TYPE_LOTTIE = 1;
    public static final int IMAGE_TYPE_ANIMATION = 2;
    public static final int IMAGE_TYPE_SVG = 3;
    public static final int IMAGE_TYPE_SVG_WHITE = 4;
    public static final int IMAGE_TYPE_THEME_PREVIEW = 5;

//    private final FileLoaderPriorityQueue largeFilesQueue = new FileLoaderPriorityQueue("large files queue", 2);
//    private final FileLoaderPriorityQueue filesQueue = new FileLoaderPriorityQueue("files queue", 3);
//    private final FileLoaderPriorityQueue imagesQueue = new FileLoaderPriorityQueue("imagesQueue queue", 6);
//    private final FileLoaderPriorityQueue audioQueue = new FileLoaderPriorityQueue("audioQueue queue", 3);

    private final FileLoaderPriorityQueue[] smallFilesQueue = new FileLoaderPriorityQueue[5];
    private final FileLoaderPriorityQueue[] largeFilesQueue = new FileLoaderPriorityQueue[5];

    public final static long DEFAULT_MAX_FILE_SIZE = 1024L * 1024L * 2000L;
    public final static long DEFAULT_MAX_FILE_SIZE_PREMIUM = DEFAULT_MAX_FILE_SIZE * 2L;

    public final static int PRELOAD_CACHE_TYPE = 11;

    private volatile static DispatchQueue2 fileLoaderQueue = new DispatchQueue2("fileUploadQueue");
    private final FilePathDatabase filePathDatabase;

    private final LinkedList<FileUploadOperation> uploadOperationQueue = new LinkedList<>();
    private final LinkedList<FileUploadOperation> uploadSmallOperationQueue = new LinkedList<>();
    private final ConcurrentHashMap<String, FileUploadOperation> uploadOperationPaths = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, FileUploadOperation> uploadOperationPathsEnc = new ConcurrentHashMap<>();
    private int currentUploadOperationsCount = 0;
    private int currentUploadSmallOperationsCount = 0;


    private final Object uploadOperationLock = new Object();
    private final Object streamOperationLock = new Object();
    private final IdentityHashMap<FileLoadOperation, StreamOperationState> streamOperations = new IdentityHashMap<>();
    private final IdentityHashMap<StreamRegistration, Boolean> pendingStreamRecoveries = new IdentityHashMap<>();
    private long streamRegistrationGeneration;
    private final IdentityHashMap<FileUploadOperation, Boolean> countedUploadOperations = new IdentityHashMap<>();
    private final IdentityHashMap<FileUploadOperation, ArrayList<UploadRequest>> uploadRequests = new IdentityHashMap<>();
    private final IdentityHashMap<FileUploadOperation, UploadOperationOwner> uploadOwners = new IdentityHashMap<>();
    private boolean drainingUploadOperations;
    private final ConcurrentHashMap<String, FileLoadOperation> loadOperationPaths = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LoadOperationUIObject> loadOperationPathsUI = new ConcurrentHashMap<>(10, 1, 2);
    private final HashMap<String, Long> uploadSizes = new HashMap<>();

    private final HashMap<String, Long> uploadSizesEnc = new HashMap<>();
    private TransferOwner uploadSizesOwner;

    private final ConcurrentHashMap<String, TransferOwner> loadingVideos = new ConcurrentHashMap<>();

    private String forceLoadingFile;

    private TransferOwner forceLoadingOwner;
    private static SparseArray<File> mediaDirs = null;
    private FileLoaderDelegate delegate = null;

    private int lastReferenceId;
    private final ConcurrentHashMap<Integer, ParentReference> parentObjectReferences = new ConcurrentHashMap<>();

    private static final class ParentReference {
        final Object object;
        final TransferOwner owner;

        ParentReference(Object object, TransferOwner owner) {
            this.object = object;
            this.owner = owner;
        }
    }

    private static final FileLoader[] Instance = new FileLoader[UserConfig.MAX_ACCOUNT_COUNT];

    public static FileLoader getInstance(int num) {
        FileLoader localInstance = Instance[num];
        if (localInstance == null) {
            synchronized (FileLoader.class) {
                localInstance = Instance[num];
                if (localInstance == null) {
                    Instance[num] = localInstance = new FileLoader(num);
                }
            }
        }
        return localInstance;
    }

    private static final class AccountFileLoadOperation extends FileLoadOperation {
        final TransferOwner owner;
        final AtomicBoolean retired = new AtomicBoolean();
        volatile LoadOperationUIObject admission;
        volatile boolean streamReleaseRetired;
        volatile boolean streamUserCancelled;
        final IdentityHashMap<StreamRegistration, Boolean> streamRegistrations = new IdentityHashMap<>();

        AccountFileLoadOperation(TransferOwner owner, SecureDocument document) {
            super(document);
            this.owner = owner;
        }

        AccountFileLoadOperation(TransferOwner owner, ImageLocation location, Object parent, String ext, long size) {
            super(location, parent, ext, size);
            this.owner = owner;
        }

        AccountFileLoadOperation(TransferOwner owner, TLRPC.Document document, Object parent) {
            super(document, parent);
            this.owner = owner;
        }

        AccountFileLoadOperation(TransferOwner owner, int account, WebFile document) {
            super(account, document);
            this.owner = owner;
        }

        void retire() {
            boolean cancel = retired.compareAndSet(false, true);
            finishRetirement(cancel);
        }

        void finishRetirement(boolean cancel) {
            retireCdnAuxRequests();
            try {
                pause();
            } catch (RuntimeException e) {
                FileLog.e(e);
            }
            if (cancel) cancel();
        }

        @Override
        protected boolean isRequestOwnerCurrent() {
            LoadOperationUIObject currentAdmission = admission;
            return owner != null && retired != null && !retired.get() && owner.isCurrent()
                    && (currentAdmission == null || !currentAdmission.cancelled);
        }

        @Override
        protected boolean startWithRegistration(FileLoadOperationStream stream, long offset, boolean priority, StreamRegistration registration) {
            beginLocalFileAdmission();
            try {
                if (retired.get() || !owner.isCurrent() || admission != null && admission.cancelled) {
                    retire();
                    return false;
                }
                boolean started = super.startWithRegistration(stream, offset, priority, registration);
                if (retired.get() || !owner.isCurrent() || admission != null && admission.cancelled) {
                    retire();
                    return false;
                }
                return started;
            } finally {
                endLocalFileAdmission();
            }
        }

        @Override
        protected void startDownloadRequest(int connectionType) {
            if (retired.get() || !owner.isCurrent() || admission != null && admission.cancelled) {
                retire();
                return;
            }
            super.startDownloadRequest(connectionType);
            if (!owner.isCurrent()) retire();
        }
    }

    static TransferOwner downloadOwner(FileLoadOperation operation) {
        return operation instanceof AccountFileLoadOperation ? ((AccountFileLoadOperation) operation).owner : null;
    }

    private abstract static class ImageLoadRunnable implements Runnable {
        volatile boolean pendingAdmission = true;

        abstract void cancelBeforeAdmission(FileLoadOperation operation);
    }

    private static final class LocalFileOperationOwner {
        final long generation;

        LocalFileOperationOwner(long generation) {
            this.generation = generation;
        }
    }

    private final ConcurrentHashMap<FileLoadOperation, LocalFileOperationOwner> localFileOperations = new ConcurrentHashMap<>();

    private void trackLocalFileOperation(FileLoadOperation operation) {
        long generation = operation.getLocalFileGeneration();
        LocalFileOperationOwner current = localFileOperations.get(operation);
        if (current != null && current.generation == generation) return;
        LocalFileOperationOwner owner = new LocalFileOperationOwner(generation);
        localFileOperations.put(operation, owner);
        operation.whenLocalFileWorkDrained(() -> localFileOperations.remove(operation, owner));
    }

    ArrayList<FileLoadOperation> getLocalFileOperations(String fileName, TransferOwner owner) {
        ArrayList<FileLoadOperation> result = new ArrayList<>();
        for (FileLoadOperation operation : localFileOperations.keySet()) {
            if (owner.matches(downloadOwner(operation)) && TextUtils.equals(fileName, operation.getFileName())) result.add(operation);
        }
        return result;
    }

    private void retireTransfers(TransferOwner owner) {
        final ArrayList<FileUploadOperation> uploads = new ArrayList<>();
        synchronized (uploadOperationLock) {
            for (FileUploadOperation operation : uploadOwners.keySet()) {
                if (owner.matches(operation.getTransferOwner())) uploads.add(operation);
            }
        }
        for (FileUploadOperation operation : uploads) operation.cancelIfPending();
        for (FileLoadOperation operation : loadOperationPaths.values()) {
            if (owner.matches(downloadOwner(operation))) ((AccountFileLoadOperation) operation).retire();
        }
        runUploadTask(() -> {
            synchronized (uploadOperationLock) {
                if (owner.matches(uploadSizesOwner)) {
                    uploadSizes.clear();
                    uploadSizesEnc.clear();
                    uploadSizesOwner = null;
                }
            }
            for (FileUploadOperation operation : uploads) {
                operation.cancelIfPending();
                if (retireUploadOperation(operation.getUploadingFilePath(), operation.isEncrypted(), operation)) {
                    finishUploadRequests(operation, null);
                    if (delegate != null) delegate.fileDidFailedUpload(operation.getUploadingFilePath(), operation.isEncrypted(), operation.getTransferOwner(), operation);
                }
            }
            for (String name : loadOperationPathsUI.keySet()) {
                LoadOperationUIObject ui = loadOperationPathsUI.get(name);
                if (ui != null && owner.matches(ui.owner) && loadOperationPathsUI.remove(name, ui)) {
                    ui.cancelled = true;
                    if (ui.loadInternalRunnable != null) fileLoaderQueue.cancelRunnable(ui.loadInternalRunnable);
                    FileLoadOperation operation = ui.operation;
                    if (operation == null) operation = loadOperationPaths.get(name);
                    if (operation != null && !owner.matches(downloadOwner(operation))) operation = null;
                    if (operation != null) ((AccountFileLoadOperation) operation).retire();
                    if (ui.loadInternalRunnable instanceof ImageLoadRunnable) {
                        ((ImageLoadRunnable) ui.loadInternalRunnable).cancelBeforeAdmission(operation);
                    }
                    if (delegate != null) delegate.fileDidFailedLoad(name, 1, ui.owner, operation);
                }
            }
            for (FileLoadOperation operation : loadOperationPaths.values()) {
                if (owner.matches(downloadOwner(operation))) {
                    ((AccountFileLoadOperation) operation).retire();
                    if (loadOperationPaths.remove(operation.getFileName(), operation)) {
                        forgetStreamOperation(operation);
                        if (Thread.currentThread() == fileLoaderQueue) {
                            operation.getQueue().remove(operation);
                            operation.getQueue().checkLoadingOperations();
                        }
                        if (delegate != null) delegate.fileDidFailedLoad(operation.getFileName(), 1, downloadOwner(operation), operation);
                    }
                }
            }
            for (String key : loadingVideos.keySet()) {
                TransferOwner loading = loadingVideos.get(key);
                if (owner.matches(loading)) loadingVideos.remove(key, loading);
            }
            if (owner.matches(forceLoadingOwner)) {
                forceLoadingFile = null;
                forceLoadingOwner = null;
            }
            for (Integer key : parentObjectReferences.keySet()) {
                ParentReference reference = parentObjectReferences.get(key);
                if (reference != null && owner.matches(reference.owner)) parentObjectReferences.remove(key, reference);
            }
        });
    }
    public FileLoader(int instance) {
        super(instance);
        filePathDatabase = new FilePathDatabase(instance);
        for (int i = 0; i < smallFilesQueue.length; i++)  {
            smallFilesQueue[i] = new FileLoaderPriorityQueue(instance, "smallFilesQueue dc" + (i + 1), FileLoaderPriorityQueue.TYPE_SMALL, fileLoaderQueue);
            largeFilesQueue[i] = new FileLoaderPriorityQueue(instance, "largeFilesQueue dc" + (i + 1), FileLoaderPriorityQueue.TYPE_LARGE, fileLoaderQueue);
        }
        dumpFilesQueue();
    }

    public static void setMediaDirs(SparseArray<File> dirs) {
        mediaDirs = dirs;
    }

    public static File checkDirectory(int type) {
        return mediaDirs.get(type);
    }

    public static File getDirectory(int type) {
        File dir = mediaDirs.get(type);
        if (dir == null && type != FileLoader.MEDIA_DIR_CACHE) {
            dir = mediaDirs.get(FileLoader.MEDIA_DIR_CACHE);
        }
        if (BuildVars.NO_SCOPED_STORAGE) {
            try {
                if (dir != null && !dir.isDirectory()) {
                    dir.mkdirs();
                }
            } catch (Exception e) {
                //don't prompt
            }
        }
        return dir;
    }

    public int getFileReference(Object parentObject) {
        int reference = lastReferenceId++;
        TransferOwner owner = captureTransferOwner(currentAccount);
        if (owner.isCurrent()) parentObjectReferences.put(reference, new ParentReference(parentObject, owner));
        return reference;
    }

    public Object getParentObject(int reference) {
        ParentReference parent = parentObjectReferences.get(reference);
        return parent != null && parent.owner.isCurrent() ? parent.object : null;
    }

    public void setLoadingVideoInternal(TLRPC.Document document, boolean player) {
        setLoadingVideoInternal(document, player, captureTransferOwner(currentAccount));
    }

    private void setLoadingVideoInternal(TLRPC.Document document, boolean player, TransferOwner owner) {
        if (!owner.isCurrent()) return;
        String key = getAttachFileName(document);
        String dKey = key + (player ? "p" : "");
        loadingVideos.put(dKey, owner);
        getNotificationCenter().postNotificationName(NotificationCenter.videoLoadingStateChanged, key);
    }

    public void setLoadingVideo(TLRPC.Document document, boolean player, boolean schedule) {
        if (document == null) {
            return;
        }
        if (schedule) {
            final TransferOwner owner = captureTransferOwner(currentAccount);
            AndroidUtilities.runOnUIThread(() -> setLoadingVideoInternal(document, player, owner));
        } else {
            setLoadingVideoInternal(document, player);
        }
    }

    public void setLoadingVideoForPlayer(TLRPC.Document document, boolean player) {
        if (document == null) {
            return;
        }
        String key = getAttachFileName(document);
        TransferOwner owner = loadingVideos.get(key + (player ? "" : "p"));
        if (owner != null && owner.isCurrent()) {
            loadingVideos.put(key + (player ? "p" : ""), owner);
            getNotificationCenter().postNotificationName(NotificationCenter.videoLoadingStateChanged, key);
        }
    }

    private void removeLoadingVideoInternal(TLRPC.Document document, boolean player) {
        removeLoadingVideoInternal(document, player, captureTransferOwner(currentAccount));
    }

    private void removeLoadingVideoInternal(TLRPC.Document document, boolean player, TransferOwner owner) {
        String key = getAttachFileName(document);
        String dKey = key + (player ? "p" : "");
        TransferOwner loading = loadingVideos.get(dKey);
        if (owner.matches(loading) && loadingVideos.remove(dKey, loading)) {
            getNotificationCenter().postNotificationName(NotificationCenter.videoLoadingStateChanged, key);
        }
    }

    public void removeLoadingVideo(TLRPC.Document document, boolean player, boolean schedule) {
        if (document == null) {
            return;
        }
        if (schedule) {
            final TransferOwner owner = captureTransferOwner(currentAccount);
            AndroidUtilities.runOnUIThread(() -> removeLoadingVideoInternal(document, player, owner));
        } else {
            removeLoadingVideoInternal(document, player);
        }
    }

    public boolean isLoadingVideo(TLRPC.Document document, boolean player) {
        TransferOwner owner = document == null ? null : loadingVideos.get(getAttachFileName(document) + (player ? "p" : ""));
        return owner != null && owner.isCurrent();
    }

    public boolean isLoadingVideoAny(TLRPC.Document document) {
        return isLoadingVideo(document, false) || isLoadingVideo(document, true);
    }

    private boolean postUploadTask(Runnable task) {
        try {
            return fileLoaderQueue.postRunnable(task);
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
    }

    private void runUploadTask(Runnable task) {
        if (!postUploadTask(task)) task.run();
    }

    private void startUploadOperations() {
        synchronized (uploadOperationLock) {
            if (drainingUploadOperations) return;
            drainingUploadOperations = true;
        }
        boolean released = false;
        try {
            while (true) {
                final FileUploadOperation operation;
                synchronized (uploadOperationLock) {
                    final boolean small;
                    if (currentUploadSmallOperationsCount < 1 && !uploadSmallOperationQueue.isEmpty()) {
                        small = true;
                        operation = uploadSmallOperationQueue.poll();
                    } else if (currentUploadOperationsCount < 1 && !uploadOperationQueue.isEmpty()) {
                        small = false;
                        operation = uploadOperationQueue.poll();
                    } else {
                        drainingUploadOperations = false;
                        released = true;
                        return;
                    }
                    if (operation.getTransferOwner().isCurrent()) {
                        if (small) currentUploadSmallOperationsCount++;
                        else currentUploadOperationsCount++;
                        countedUploadOperations.put(operation, small);
                    }
                }
                try {
                    if (operation.getTransferOwner().isCurrent()) operation.start();
                    else operation.cancelIfPending();
                } catch (RuntimeException e) {
                    FileLog.e(e);
                    operation.cancelIfPending();
                    if (retireUploadOperation(operation.getUploadingFilePath(), operation.isEncrypted(), operation)) {
                        finishUploadRequests(operation, null);
                        if (delegate != null) delegate.fileDidFailedUpload(operation.getUploadingFilePath(), operation.isEncrypted(), operation.getTransferOwner(), operation);
                    }
                }
            }
        } finally {
            if (!released) {
                synchronized (uploadOperationLock) {
                    drainingUploadOperations = false;
                }
            }
        }
    }

    private boolean retireUploadOperation(String location, boolean encrypted, FileUploadOperation operation) {
        final boolean owned;
        synchronized (uploadOperationLock) {
            final ConcurrentHashMap<String, FileUploadOperation> paths = encrypted ? uploadOperationPathsEnc : uploadOperationPaths;
            owned = paths.get(location) == operation && paths.remove(location, operation);
            uploadOwners.remove(operation);
            uploadOperationQueue.remove(operation);
            uploadSmallOperationQueue.remove(operation);
            final Boolean small = countedUploadOperations.remove(operation);
            if (small != null) {
                if (small) currentUploadSmallOperationsCount--;
                else currentUploadOperationsCount--;
            }
        }
        startUploadOperations();
        return owned;
    }

    boolean isCurrentUploadOperation(FileUploadOperation operation) {
        return operation != null && operation.isPending() && operation.getTransferOwner().isCurrent()
                && (operation.isEncrypted() ? uploadOperationPathsEnc : uploadOperationPaths).get(operation.getUploadingFilePath()) == operation;
    }

    boolean isCurrentLoadOperation(FileLoadOperation operation) {
        TransferOwner owner = downloadOwner(operation);
        return owner != null && owner.isCurrent() && loadOperationPaths.get(operation.getFileName()) == operation;
    }

    private void dispatchUploadCallback(Runnable callback) {
        if (Thread.holdsLock(uploadOperationLock)) ApplicationLoader.applicationHandler.post(callback);
        else AndroidUtilities.runOnUIThread(callback);
    }

    private boolean deferUploadAdmission(FileUploadOperation operation, Runnable admission) {
        UploadOperationOwner owner = uploadOwners.get(operation);
        if (owner == null || !owner.cancelling) return false;
        if (owner.deferredAdmissions == null) owner.deferredAdmissions = new ArrayList<>();
        owner.deferredAdmissions.add(admission);
        return true;
    }

    private void finishUploadCancellation(UploadOperationOwner owner) {
        final ArrayList<Runnable> deferred;
        synchronized (uploadOperationLock) {
            owner.cancelling = false;
            deferred = owner.deferredAdmissions;
            owner.deferredAdmissions = null;
        }
        if (deferred != null) {
            for (Runnable admission : deferred) runUploadTask(admission);
        }
    }

    private void clearUploadRequestData(UploadRequest request) {
        request.newDataPending = false;
        request.dataFlushScheduled = false;
        request.newAvailableSize = 0;
        request.newFinalSize = 0;
        request.newProgress = null;
    }

    private void flushUploadRequestData(UploadRequest request) {
        if (request == null) return;
        synchronized (uploadOperationLock) {
            if (request.dataFlushing) return;
            request.dataFlushScheduled = false;
            if (request.cancelled || request.finished || !request.owner.isCurrent()) {
                clearUploadRequestData(request);
                return;
            }
            if (!request.newDataPending || request.operation == null) return;
            request.dataFlushing = true;
        }
        try {
            while (true) {
                final FileUploadOperation operation;
                final long availableSize, finalSize;
                final Float progress;
                synchronized (uploadOperationLock) {
                    operation = request.operation;
                    if (request.cancelled || request.finished || !request.owner.isCurrent() || !operation.isPending()
                            || !request.owner.transfer.matches(operation.getTransferOwner())) {
                        clearUploadRequestData(request);
                        break;
                    }
                    if (!request.newDataPending) break;
                    availableSize = request.newAvailableSize;
                    finalSize = request.newFinalSize;
                    progress = request.newProgress;
                    request.newDataPending = false;
                }
                operation.checkNewDataAvailable(availableSize, finalSize, progress);
            }
        } finally {
            final boolean schedule;
            synchronized (uploadOperationLock) {
                request.dataFlushing = false;
                schedule = request.newDataPending && !request.cancelled && !request.finished && request.owner.isCurrent();
                if (schedule) request.dataFlushScheduled = true;
            }
            if (schedule) runUploadTask(() -> flushUploadRequestData(request));
        }
    }

    private void bindUploadRequest(UploadRequest request, FileUploadOperation operation) {
        final UploadOperationOwner owner = uploadOwners.get(operation);
        if (request == null) {
            if (owner != null) owner.hasLegacyConsumer = true;
            return;
        }
        if (request.finished) return;
        if (owner == null || !owner.transfer.matches(request.owner.transfer) || !request.owner.isCurrent()
                || request.encrypted != operation.isEncrypted() || request.operation != null && request.operation != operation) {
            finishUploadRequest(request, null);
            return;
        }
        request.operation = operation;
        if (request.cancelled) return;
        ArrayList<UploadRequest> requests = uploadRequests.get(operation);
        if (requests == null) uploadRequests.put(operation, requests = new ArrayList<>());
        if (!requests.contains(request)) requests.add(request);
    }

    private void finishUploadRequest(UploadRequest request, UploadResult result) {
        synchronized (uploadOperationLock) {
            if (request.finished) return;
            request.finished = true;
            clearUploadRequestData(request);
        }
        if (request.callback == null) return;
        final UploadResult owned = result == null ? null
                : new UploadResult(result.file, result.encryptedFile, result.key, result.iv, result.totalSize);
        dispatchUploadCallback(() -> {
            if (request.cancelled) return;
            if (owned != null && request.owner.isCurrent()) request.callback.onUploaded(request, owned);
            else request.callback.onFailed(request);
        });
    }

    private void finishUploadRequests(FileUploadOperation operation, UploadResult result) {
        final ArrayList<UploadRequest> requests;
        synchronized (uploadOperationLock) {
            requests = uploadRequests.remove(operation);
        }
        if (requests != null) {
            for (UploadRequest request : requests) finishUploadRequest(request, result);
        }
    }

    private void notifyUploadProgress(FileUploadOperation operation, long uploadedSize, long totalSize) {
        final ArrayList<UploadRequest> requests = uploadRequests.get(operation);
        if (requests == null) return;
        final long now = android.os.SystemClock.elapsedRealtime();
        for (UploadRequest request : requests) {
            if (request.callback == null || request.cancelled || request.finished || request.lastProgressTime != 0
                    && now - request.lastProgressTime < 100 && uploadedSize != totalSize) continue;
            request.lastProgressTime = now;
            dispatchUploadCallback(() -> {
                if (!request.cancelled && !request.finished && request.owner.isCurrent()) request.callback.onProgress(request, uploadedSize, totalSize);
            });
        }
    }
    public void cancelFileUpload(final String location, final boolean enc) {
        if (location == null) {
            return;
        }
        final TransferOwner owner = captureTransferOwner(currentAccount);
        final FileUploadOperation target = (enc ? uploadOperationPathsEnc : uploadOperationPaths).get(location);
        runUploadTask(() -> {
            if (!owner.isCurrent()) return;
            final FileUploadOperation operation;
            synchronized (uploadOperationLock) {
                operation = target == null ? (enc ? uploadOperationPathsEnc : uploadOperationPaths).get(location) : target;
                if (operation != null && (!owner.matches(operation.getTransferOwner())
                        || (enc ? uploadOperationPathsEnc : uploadOperationPaths).get(location) != operation)) return;
                (enc ? uploadSizesEnc : uploadSizes).remove(location);
            }
            if (operation != null && operation.cancelIfPending()) {
                if (retireUploadOperation(location, enc, operation)) {
                    finishUploadRequests(operation, null);
                    if (delegate != null) delegate.fileDidFailedUpload(location, enc, operation.getTransferOwner(), operation);
                }
            }
        });
    }

    public void checkUploadNewDataAvailable(final String location, final boolean encrypted, final long newAvailableSize, final long finalSize) {
        checkUploadNewDataAvailable(location, encrypted, newAvailableSize, finalSize, null);
    }

    public void checkUploadNewDataAvailable(final String location, final boolean encrypted, final long newAvailableSize, final long finalSize, final Float progress) {
        final TransferOwner owner = captureTransferOwner(currentAccount);
        final FileUploadOperation target = (encrypted ? uploadOperationPathsEnc : uploadOperationPaths).get(location);
        runUploadTask(() -> {
            if (!owner.isCurrent()) return;
            final FileUploadOperation operation;
            synchronized (uploadOperationLock) {
                operation = target == null ? (encrypted ? uploadOperationPathsEnc : uploadOperationPaths).get(location) : target;
                if (operation != null && (!owner.matches(operation.getTransferOwner())
                        || (encrypted ? uploadOperationPathsEnc : uploadOperationPaths).get(location) != operation)) return;
                if (operation == null && finalSize != 0) {
                    if (!owner.matches(uploadSizesOwner)) {
                        uploadSizes.clear();
                        uploadSizesEnc.clear();
                        uploadSizesOwner = owner;
                    }
                    (encrypted ? uploadSizesEnc : uploadSizes).put(location, finalSize);
                }
            }
            if (operation != null) {
                operation.checkNewDataAvailable(newAvailableSize, finalSize, progress);
            }
        });
    }

    public void onNetworkChanged(final boolean slow) {
        final TransferOwner owner = captureTransferOwner(currentAccount);
        runUploadTask(() -> {
            if (!owner.isCurrent()) return;
            for (ConcurrentHashMap.Entry<String, FileUploadOperation> entry : uploadOperationPaths.entrySet()) {
                if (owner.matches(entry.getValue().getTransferOwner())) entry.getValue().onNetworkChanged(slow);
            }
            for (ConcurrentHashMap.Entry<String, FileUploadOperation> entry : uploadOperationPathsEnc.entrySet()) {
                if (owner.matches(entry.getValue().getTransferOwner())) entry.getValue().onNetworkChanged(slow);
            }
        });
    }

    public void uploadFile(final String location, final boolean encrypted, final boolean small, final int type) {
        uploadFile(location, encrypted, small, 0, type, false);
    }

    public void uploadFile(final String location, final boolean encrypted, final boolean small, final long estimatedSize, final int type, boolean forceSmallFile) {
        uploadFile(location, encrypted, small, estimatedSize, type, forceSmallFile, null);
    }

    public UploadRequest uploadFile(String location, boolean small, int type, UploadFileCallback callback) {
        if (callback == null) {
            uploadFile(location, false, small, type);
            return null;
        }
        return uploadFileForConsumer(location, false, small, 0, type, false, new UploadConsumerCallback() {
            @Override
            public void onProgress(UploadRequest request, long uploadedSize, long totalSize) {
                callback.onProgress(request, uploadedSize, totalSize);
            }

            @Override
            public void onUploaded(UploadRequest request, UploadResult result) {
                callback.onUploaded(request, result.file);
            }

            @Override
            public void onFailed(UploadRequest request) {
                callback.onFailed(request);
            }
        });
    }

    public UploadRequest uploadFileForConsumer(String location, boolean encrypted, boolean small, long estimatedSize,
                                               int type, boolean forceSmallFile, UploadConsumerCallback callback) {
        final UploadRequest request = new UploadRequest(location, encrypted, callback);
        uploadFile(location, encrypted, small, estimatedSize, type, forceSmallFile, request);
        return request;
    }

    private void uploadFile(final String location, final boolean encrypted, final boolean small, final long estimatedSize, final int type, boolean forceSmallFile, UploadRequest request) {
        final UploadOperationOwner owner = request == null ? new UploadOperationOwner() : request.owner;
        uploadFile(location, encrypted, small, estimatedSize, type, forceSmallFile, request, owner);
    }

    private void uploadFile(final String location, final boolean encrypted, final boolean small, final long estimatedSize, final int type, boolean forceSmallFile, UploadRequest request, UploadOperationOwner owner) {
        if (location == null) {
            if (request != null) finishUploadRequest(request, null);
            return;
        }
        final Runnable upload = () -> {
            if (!owner.isCurrent() || request != null && (request.cancelled || request.finished)) {
                if (request != null) finishUploadRequest(request, null);
                return;
            }
            final FileUploadOperation operation;
            final boolean joined;
            FileUploadOperation stale = null;
            synchronized (uploadOperationLock) {
                if (!owner.isCurrent() || request != null && (request.cancelled || request.finished)) {
                    if (request != null) finishUploadRequest(request, null);
                    return;
                }
                final FileUploadOperation existing = (encrypted ? uploadOperationPathsEnc : uploadOperationPaths).get(location);
                if (existing != null && existing.getTransferOwner().matches(owner.transfer)) {
                    if (deferUploadAdmission(existing, () -> uploadFile(location, encrypted, small, estimatedSize, type, forceSmallFile, request, owner))) return;
                    bindUploadRequest(request, existing);
                    operation = existing;
                    joined = true;
                } else {
                    joined = false;
                    if (existing != null) {
                        stale = existing;
                        (encrypted ? uploadOperationPathsEnc : uploadOperationPaths).remove(location, existing);
            }
            long esimated = estimatedSize;
                    if (request != null && request.newFinalSize != 0
                            || request == null && owner.transfer.matches(uploadSizesOwner) && (encrypted ? uploadSizesEnc : uploadSizes).remove(location) != null) {
                    esimated = 0;
                    }
                    operation = new FileUploadOperation(currentAccount, location, encrypted, esimated, type, owner.transfer);
                    uploadOwners.put(operation, owner);
            if (encrypted) {
                uploadOperationPathsEnc.put(location, operation);
            } else {
                uploadOperationPaths.put(location, operation);
            }
                    bindUploadRequest(request, operation);
                    if (forceSmallFile) operation.setForceSmallFile();
            operation.setDelegate(new FileUploadOperation.FileUploadOperationDelegate() {
                @Override
                public void didFinishUploadingFile(final FileUploadOperation operation, final TLRPC.InputFile inputFile, final TLRPC.InputEncryptedFile inputEncryptedFile, final byte[] key, final byte[] iv) {
                            final long totalSize = operation.getTotalFileSize();
                            final UploadResult result = inputFile == null && inputEncryptedFile == null ? null
                                    : new UploadResult(inputFile, inputEncryptedFile, key, iv, totalSize);
                            runUploadTask(() -> {
                                final boolean owned = retireUploadOperation(location, encrypted, operation);
                                finishUploadRequests(operation, result);
                                if (owned && delegate != null) delegate.fileDidUploaded(location, inputFile, inputEncryptedFile, key, iv, totalSize, owner.transfer, operation);
                    });
                }

                @Override
                public void didFailedUploadingFile(final FileUploadOperation operation) {
                            runUploadTask(() -> {
                                final boolean owned = retireUploadOperation(location, encrypted, operation);
                                finishUploadRequests(operation, null);
                                if (owned && delegate != null) delegate.fileDidFailedUpload(location, encrypted, owner.transfer, operation);
                    });
                }

                @Override
                public void didChangedUploadProgress(FileUploadOperation operation, long uploadedSize, long totalSize) {
                            boolean current;
                            synchronized (uploadOperationLock) {
                                current = isCurrentUploadOperation(operation);
                                if (current) {
                                    notifyUploadProgress(operation, uploadedSize, totalSize);
                                }
                            }
                            if (current && delegate != null) delegate.fileUploadProgressChanged(operation, location, uploadedSize, totalSize, encrypted, owner.transfer);
                }
            });
                }
            }
            if (joined) {
                flushUploadRequestData(request);
                return;
            }
            if (stale != null) {
                stale.cancelIfPending();
                retireUploadOperation(location, encrypted, stale);
                finishUploadRequests(stale, null);
                if (delegate != null) delegate.fileDidFailedUpload(location, encrypted, stale.getTransferOwner(), stale);
            }
            operation.retainSourceFile();
            flushUploadRequestData(request);
            if (delegate != null && estimatedSize != 0 && isCurrentUploadOperation(operation)) {
                delegate.fileUploadProgressChanged(operation, location, 0, estimatedSize, encrypted, owner.transfer);
            }
            synchronized (uploadOperationLock) {
                if (isCurrentUploadOperation(operation)) {
                    if (small) uploadSmallOperationQueue.add(operation);
                    else uploadOperationQueue.add(operation);
                }
            }
            if (!owner.isCurrent()) {
                operation.cancelIfPending();
                retireUploadOperation(location, encrypted, operation);
                finishUploadRequests(operation, null);
            }
            startUploadOperations();
        };
        if (!postUploadTask(upload)) {
            bindRejectedUpload(location, encrypted, request, owner);
        }
    }

    private void bindRejectedUpload(String location, boolean encrypted, UploadRequest request, UploadOperationOwner owner) {
        final boolean failed;
        synchronized (uploadOperationLock) {
            if (request != null && (request.cancelled || request.finished)) {
                finishUploadRequest(request, null);
                return;
            }
            final FileUploadOperation existing = (encrypted ? uploadOperationPathsEnc : uploadOperationPaths).get(location);
            failed = !owner.isCurrent() || existing == null || !owner.transfer.matches(existing.getTransferOwner());
            if (!failed) {
                if (deferUploadAdmission(existing, () -> bindRejectedUpload(location, encrypted, request, owner))) return;
                bindUploadRequest(request, existing);
            }
        }
        if (failed) {
            if (request != null) finishUploadRequest(request, null);
            if (delegate != null) delegate.fileDidFailedUpload(location, encrypted, owner.transfer, null);
        } else {
            flushUploadRequestData(request);
        }
    }

    public void setForceStreamLoadingFile(TLRPC.FileLocation location, String ext) {
        if (location == null) {
            return;
        }
        final TransferOwner owner = captureTransferOwner(currentAccount);
        final String name = getAttachFileName(location, ext);
        final FileLoadOperation operation = loadOperationPaths.get(name);
        postUploadTask(() -> {
            if (!owner.isCurrent()) return;
            forceLoadingFile = name;
            forceLoadingOwner = owner;
            if (operation != null && owner.matches(downloadOwner(operation)) && loadOperationPaths.get(name) == operation) {
                synchronized (streamOperationLock) {
                    StreamOperationState state = streamOperations.get(operation);
                    if (state != null) {
                        if (state.retiring || ((AccountFileLoadOperation) operation).retired.get()) return;
                        state.legacyPriority = PRIORITY_STREAM;
                        state.appliedPriority = PRIORITY_STREAM;
                    }
                }
                if (operation.isPreloadVideoOperation()) {
                    operation.setIsPreloadVideoOperation(false);
                }
                operation.setForceRequest(true);
                operation.setPriority(getPriorityValue(PRIORITY_STREAM));
                operation.getQueue().remove(operation);
                operation.getQueue().add(operation);
                operation.getQueue().checkLoadingOperations();
            }
        });
    }

    public void cancelLoadFile(TLRPC.Document document) {
        cancelLoadFile(document, false);
    }

    public void cancelLoadFile(TLRPC.Document document, boolean deleteFile) {
        cancelLoadFile(document, null, null, null, null, null, deleteFile);
    }

    public void cancelLoadFile(SecureDocument document) {
        cancelLoadFile(null, document, null, null, null, null, false);
    }

    public void cancelLoadFile(WebFile document) {
        cancelLoadFile(null, null, document, null, null, null, false);
    }

    public void cancelLoadFile(TLRPC.PhotoSize photo) {
        cancelLoadFile(photo, false);
    }

    public void cancelLoadFile(TLRPC.PhotoSize photo, boolean deleteFile) {
        cancelLoadFile(null, null, null, photo.location, null, null, deleteFile);
    }

    public void cancelLoadFile(TLRPC.FileLocation location, String ext) {
        cancelLoadFile(location, ext, false);
    }

    public void cancelLoadFile(TLRPC.FileLocation location, String ext, boolean deleteFile) {
        cancelLoadFile(null, null, null, location, ext, null, deleteFile);
    }

    public void cancelLoadFile(String fileName) {
        cancelLoadFile(null, null, null, null, null, fileName, true);
    }

    public void cancelLoadFiles(ArrayList<String> fileNames) {
        for (int a = 0, N = fileNames.size(); a < N; a++) {
            cancelLoadFile(null, null, null, null, null, fileNames.get(a), true);
        }
    }

    private void cancelLoadFile(final TLRPC.Document document, final SecureDocument secureDocument, final WebFile webDocument, final TLRPC.FileLocation location, final String locationExt, String name, boolean deleteFile) {
        if (location == null && document == null && webDocument == null && secureDocument == null && TextUtils.isEmpty(name)) {
            return;
        }
        final String fileName;
        if (location != null) {
            fileName = getAttachFileName(location, locationExt);
        } else if (document != null) {
            fileName = getAttachFileName(document);
        } else if (secureDocument != null) {
            fileName = getAttachFileName(secureDocument);
        } else if (webDocument != null) {
            fileName = getAttachFileName(webDocument);
        } else {
            fileName = name;
        }
        final TransferOwner owner = captureTransferOwner(currentAccount);
        LoadOperationUIObject uiObject = loadOperationPathsUI.get(fileName);
        FileLoadOperation operation = uiObject != null && uiObject.operation != null ? uiObject.operation : loadOperationPaths.get(fileName);
        boolean removed = cancelDownload(fileName, operation, uiObject, owner);
        if (removed && document != null) {
            AndroidUtilities.runOnUIThread(() -> {
                if (!owner.isCurrent()) return;
                getNotificationCenter().postNotificationName(NotificationCenter.onDownloadingFilesChanged);
            });
        }
    }

    public void cancel(FileLoadOperation operation) {
        if (operation == null) return;
        final String fileName = operation.getFileName();
        final TransferOwner owner = downloadOwner(operation);
        if (owner == null) return;
        LoadOperationUIObject ui = loadOperationPathsUI.get(fileName);
        boolean removed = cancelDownload(fileName, operation, ui != null && ui.operation == operation ? ui : null, owner);
        if (removed) {
            AndroidUtilities.runOnUIThread(() -> {
                if (!owner.isCurrent()) return;
                getNotificationCenter().postNotificationName(NotificationCenter.onDownloadingFilesChanged);
            });
        }
    }

    private boolean cancelDownload(String name, FileLoadOperation operation, LoadOperationUIObject ui, TransferOwner owner) {
        return cancelDownload(name, operation, ui, owner, null);
    }

    boolean recoverStreamFile(FileLoadOperation operation, StreamRegistration registration) {
        if (!(operation instanceof AccountFileLoadOperation) || registration == null || registration.loader != this) return false;
        final String name = operation.getFileName();
        final TransferOwner owner = downloadOwner(operation);
        if (owner == null) return false;
        LoadOperationUIObject ui = loadOperationPathsUI.get(name);
        return cancelDownload(name, operation, ui != null && ui.operation == operation ? ui : null, owner, registration);
    }

    private boolean cancelDownload(String name, FileLoadOperation operation, LoadOperationUIObject ui, TransferOwner owner, StreamRegistration recovery) {
        if (!owner.isCurrent()) return false;
        final FileLoadOperation target;
        final AccountFileLoadOperation current;
        final ArrayList<StreamRegistration> cancelled;
        final boolean cancel;
        final boolean removed;
        synchronized (streamOperationLock) {
            target = operation != null ? operation : ui != null && loadOperationPathsUI.get(name) == ui ? ui.operation : null;
            current = target instanceof AccountFileLoadOperation && owner.matches(downloadOwner(target))
                    ? (AccountFileLoadOperation) target : null;
            if (recovery != null) {
                if (current == null || recovery.operation != current || recovery.closed || recovery.terminallyCancelled || recovery.cancellation.cancelled
                        || !owner.matches(recovery.owner) || recovery.scope != null && recovery.scope.closed
                        || current.streamUserCancelled || !current.retired.compareAndSet(false, true)) return false;
                recovery.recoveryOperation = current;
                pendingStreamRecoveries.put(recovery, true);
                cancel = true;
                cancelled = new ArrayList<>();
            } else if (current != null) {
                current.streamUserCancelled = true;
                cancelled = cancelStreamRegistrationsLocked(current, null);
                cancel = current.retired.compareAndSet(false, true);
            } else {
                cancelled = new ArrayList<>();
                cancel = false;
            }
            removed = ui != null && owner.matches(ui.owner) && (target == null || ui.operation == null || ui.operation == target)
                    && loadOperationPathsUI.remove(name, ui);
            if (removed) ui.cancelled = true;
            if (current == null && recovery == null && (removed || operation == null && ui == null)) {
                cancelled.addAll(cancelPendingStreamRecoveriesLocked(name, owner, null));
            }
        }
        closeCancelledStreamRegistrations(cancelled);
        try {
            if (removed) {
                if (ui.loadInternalRunnable != null) fileLoaderQueue.cancelRunnable(ui.loadInternalRunnable);
                if (ui.loadInternalRunnable instanceof ImageLoadRunnable) {
                    ((ImageLoadRunnable) ui.loadInternalRunnable).cancelBeforeAdmission(target != null ? target : ui.operation);
                }
            }
        } finally {
            if (current != null) {
                current.finishRetirement(cancel);
                runUploadTask(() -> {
                    if (loadOperationPaths.remove(name, target)) {
                        forgetStreamOperation(target);
                        if (Thread.currentThread() == fileLoaderQueue) {
                            target.getQueue().remove(target);
                            target.getQueue().checkLoadingOperations();
                        }
                    }
                    clearLoadingUi(name, target);
                });
            }
        }
        return recovery != null || removed || !cancelled.isEmpty();
    }

    public void changePriority(int priority, final TLRPC.Document document, final SecureDocument secureDocument, final WebFile webDocument, final TLRPC.FileLocation location, final String locationExt, String name) {
        if (location == null && document == null && webDocument == null && secureDocument == null && TextUtils.isEmpty(name)) {
            return;
        }
        final String fileName;
        if (location != null) {
            fileName = getAttachFileName(location, locationExt);
        } else if (document != null) {
            fileName = getAttachFileName(document);
        } else if (secureDocument != null) {
            fileName = getAttachFileName(secureDocument);
        } else if (webDocument != null) {
            fileName = getAttachFileName(webDocument);
        } else {
            fileName = name;
        }
        final TransferOwner owner = captureTransferOwner(currentAccount);
        final FileLoadOperation operation = loadOperationPaths.get(fileName);
        postUploadTask(() -> {
            if (owner.isCurrent() && operation != null && owner.matches(downloadOwner(operation)) && loadOperationPaths.get(fileName) == operation) {
                int effective = priority;
                synchronized (streamOperationLock) {
                    StreamOperationState state = streamOperations.get(operation);
                    if (state != null) {
                        if (state.retiring || ((AccountFileLoadOperation) operation).retired.get()) return;
                        state.legacyPriority = priority;
                        if (state.nonStreamDemand) state.nonStreamPriority = priority;
                        effective = state.priority();
                        state.appliedPriority = effective;
                    }
                }
                int newPriority = getPriorityValue(effective);
                if (operation.getPriority() == newPriority) {
                    return;
                }
                operation.setPriority(newPriority);
                FileLoaderPriorityQueue queue = operation.getQueue();
                queue.remove(operation);
                queue.add(operation);
                queue.checkLoadingOperations();
                FileLog.d("update priority " + fileName + " position in queue " + operation.getPositionInQueue() + " account=" + currentAccount);
            }
        });
    }


    public void cancelLoadAllFiles() {
        final TransferOwner owner = captureTransferOwner(currentAccount);
        for (String name : loadOperationPathsUI.keySet()) {
            LoadOperationUIObject ui = loadOperationPathsUI.get(name);
            if (ui != null) cancelDownload(name, ui.operation, ui, owner);
//            if (removed && document != null) {
//                AndroidUtilities.runOnUIThread(() -> {
//                    getNotificationCenter().postNotificationName(NotificationCenter.onDownloadingFilesChanged);
//                });
//            }
        }
    }

    public FileUploadOperation findUploadOperationByRequestToken(final int requestToken) {
        for (FileUploadOperation operation : uploadOperationPaths.values()) {
            if (isCurrentUploadOperation(operation) && operation.uiRequestTokens.contains(requestToken)) {
                return operation;
            }
        }
        return null;
    }

    public boolean checkUploadCaughtPremiumFloodWait(final String filename) {
        if (filename == null) return false;
        FileUploadOperation operation = uploadOperationPaths.get(filename);
        if (isCurrentUploadOperation(operation) && operation.caughtPremiumFloodWait) {
            operation.caughtPremiumFloodWait = false;
            return true;
        }
        return false;
    }

    public FileLoadOperation findLoadOperationByRequestToken(final int requestToken) {
        for (FileLoadOperation operation : loadOperationPaths.values()) {
            if (isCurrentLoadOperation(operation) && operation.uiRequestTokens.contains(requestToken)) {
                return operation;
            }
        }
        return null;
    }

    public boolean checkLoadCaughtPremiumFloodWait(final String filename) {
        if (filename == null) return false;
        FileLoadOperation operation = loadOperationPaths.get(filename);
        if (operation != null && isCurrentLoadOperation(operation) && operation.caughtPremiumFloodWait) {
            operation.caughtPremiumFloodWait = false;
            return true;
        }
        return false;
    }

    public boolean isLoadingFile(final String fileName) {
        LoadOperationUIObject ui = fileName == null ? null : loadOperationPathsUI.get(fileName);
        return ui != null && !ui.cancelled && ui.owner.isCurrent();
    }

    public float getBufferedProgressFromPosition(final float position, final String fileName) {
        if (TextUtils.isEmpty(fileName)) {
            return 0;
        }
        FileLoadOperation loadOperation = loadOperationPaths.get(fileName);
        if (loadOperation != null && isCurrentLoadOperation(loadOperation)) {
            return loadOperation.getDownloadedLengthFromOffset(position);
        } else {
            return 0.0f;
        }
    }

    public File getLocalFile(ImageLocation imageLocation) {
        if (imageLocation == null) return null;
        String fileName;
        if (imageLocation.location != null) {
            fileName = getAttachFileName(imageLocation.location, null);
        } else if (imageLocation.document != null) {
            fileName = getAttachFileName(imageLocation.document);
        } else if (imageLocation.webFile != null) {
            fileName = getAttachFileName(imageLocation.webFile);
        } else {
            fileName = null;
        }
        if (fileName == null) return null;
        File f;

        f = new File(getDirectory(MEDIA_DIR_CACHE), fileName);
        if (f.exists()) return f;

        f = new File(getDirectory(MEDIA_DIR_IMAGE), fileName);
        if (f.exists()) return f;

        f = new File(getDirectory(MEDIA_DIR_VIDEO), fileName);
        if (f.exists()) return f;

        f = new File(getDirectory(MEDIA_DIR_FILES), fileName);
        if (f.exists()) return f;

        return null;
    }

    public void loadFile(ImageLocation imageLocation, Object parentObject, String ext, int priority, int cacheType) {
        if (imageLocation == null) {
            return;
        }
        if (cacheType == 0 && (imageLocation.isEncrypted() || imageLocation.photoSize != null && imageLocation.getSize() == 0)) {
            cacheType = 1;
        }
        loadFile(imageLocation.document, imageLocation.secureDocument, imageLocation.webFile, imageLocation.location, imageLocation, parentObject, ext, imageLocation.getSize(), priority, cacheType);
    }

    public void loadFile(SecureDocument secureDocument, int priority) {
        if (secureDocument == null) {
            return;
        }
        loadFile(null, secureDocument, null, null, null, null, null, 0, priority, 1);
    }

    public void loadFile(TLRPC.Document document, Object parentObject, int priority, int cacheType) {
        if (document == null) {
            return;
        }
        if (cacheType == 0 && document.key != null) {
            cacheType = 1;
        }
        loadFile(document, null, null, null, null, parentObject, null, 0, priority, cacheType);
    }

    public void loadFile(WebFile document, int priority, int cacheType) {
        loadFile(null, null, document, null, null, null, null, 0, priority, cacheType);
    }
    void loadImageFile(ImageLocation imageLocation, Object parentObject, String ext, int priority, int cacheType, TransferOwner owner, Consumer<FileLoadOperation> admitted) {
        if (imageLocation == null || owner == null || owner.account != currentAccount || !owner.isCurrent()) {
            admitted.accept(null);
            return;
        }
        if (imageLocation.location != null) {
            if (cacheType == 0 && (imageLocation.isEncrypted() || imageLocation.photoSize != null && imageLocation.getSize() == 0)) cacheType = 1;
            loadFile(imageLocation.document, imageLocation.secureDocument, imageLocation.webFile, imageLocation.location,
                    imageLocation, parentObject, ext, imageLocation.getSize(), priority, cacheType, owner, admitted);
        } else if (imageLocation.document != null) {
            if (cacheType == 0 && imageLocation.document.key != null) cacheType = 1;
            loadFile(imageLocation.document, null, null, null, null, parentObject, null, 0, priority, cacheType, owner, admitted);
        } else if (imageLocation.secureDocument != null) {
            loadFile(null, imageLocation.secureDocument, null, null, null, null, null, 0, priority, 1, owner, admitted);
        } else if (imageLocation.webFile != null) {
            loadFile(null, null, imageLocation.webFile, null, null, null, null, 0, priority, cacheType, owner, admitted);
        } else {
            admitted.accept(null);
        }
    }
    private static boolean isVideoStoryboardDocument(TLRPC.Document document) {
        return document != null && ("application/x-tgstoryboard".equalsIgnoreCase(document.mime_type)
                || "application/x-tgstoryboardmap".equalsIgnoreCase(document.mime_type));
    }

    private FileLoadOperation loadFileInternal(final TLRPC.Document document, final SecureDocument secureDocument, final WebFile webDocument, TLRPC.TL_fileLocationToBeDeprecated location, final ImageLocation imageLocation, Object parentObject, final String locationExt, final long locationSize, int priority, FileLoadOperationStream stream, final long streamOffset, boolean streamPriority, final int cacheType, final TransferOwner owner, LoadOperationUIObject admission) {
        return loadFileInternal(document, secureDocument, webDocument, location, imageLocation, parentObject, locationExt,
                locationSize, priority, stream, streamOffset, streamPriority, cacheType, owner, admission, null);
    }

    private FileLoadOperation loadFileInternal(final TLRPC.Document document, final SecureDocument secureDocument, final WebFile webDocument, TLRPC.TL_fileLocationToBeDeprecated location, final ImageLocation imageLocation, Object parentObject, final String locationExt, final long locationSize, int priority, FileLoadOperationStream stream, final long streamOffset, boolean streamPriority, final int cacheType, final TransferOwner owner, LoadOperationUIObject admission, Consumer<FileLoadOperation> admitted) {
        return loadFileInternalScoped(document, secureDocument, webDocument, location, imageLocation, parentObject, locationExt,
                locationSize, priority, stream, streamOffset, streamPriority, cacheType, owner, admission, admitted, null, stream == null);
    }

    private FileLoadOperation loadFileInternalScoped(final TLRPC.Document document, final SecureDocument secureDocument, final WebFile webDocument, TLRPC.TL_fileLocationToBeDeprecated location, final ImageLocation imageLocation, Object parentObject, final String locationExt, final long locationSize, int priority, FileLoadOperationStream stream, final long streamOffset, boolean streamPriority, final int cacheType, final TransferOwner owner, LoadOperationUIObject admission, Consumer<FileLoadOperation> admitted, StreamRegistration registration, boolean nonStreamDemand) {
        if (!owner.isCurrent() || admission != null && admission.cancelled) return null;
        if (registration != null && registration.isClosed()) return null;
        String fileName;
        if (location != null) {
            fileName = getAttachFileName(location, locationExt);
        } else if (secureDocument != null) {
            fileName = getAttachFileName(secureDocument);
        } else if (document != null) {
            fileName = getAttachFileName(document);
        } else if (webDocument != null) {
            fileName = getAttachFileName(webDocument);
        } else {
            fileName = null;
        }
        if (fileName == null || fileName.contains("" + Integer.MIN_VALUE)) {
            return null;
        }
        if (fileName.startsWith("0_0")) {
            FileLog.e(new RuntimeException("cant get hash from " + document));
            return null;
        }
        LoadOperationUIObject operationUi = null;
        if (cacheType != 10 && !TextUtils.isEmpty(fileName) && !fileName.contains("" + Integer.MIN_VALUE)) {
            operationUi = admitLoadingUi(fileName, owner, admission == null ? new LoadOperationUIObject(owner) : admission);
            if (operationUi == null) return null;
        }

        if (document != null && !isVideoStoryboardDocument(document) && parentObject instanceof MessageObject && ((MessageObject) parentObject).putInDownloadsStore && !((MessageObject) parentObject).isAnyKindOfSticker()) {
            getDownloadController().startDownloadFile(document, (MessageObject) parentObject);
        }

        final String finalFileName = fileName;
        FileLoadOperation operation = loadOperationPaths.get(finalFileName);

        if (operation != null && (!owner.matches(downloadOwner(operation))
                || ((AccountFileLoadOperation) operation).retired.get())) {
            if (!owner.isCurrent()) return null;
            ((AccountFileLoadOperation) operation).retire();
            loadOperationPaths.remove(finalFileName, operation);
            forgetStreamOperation(operation);
            operation.getQueue().remove(operation);
            operation = null;
        }

        final int requestedPriority = priority;

        if (operation != null) {
            if (!owner.isCurrent()) return null;
            if (!admitStreamOperation(operation, registration, nonStreamDemand, requestedPriority)) {
                if (((AccountFileLoadOperation) operation).streamReleaseRetired && owner.isCurrent()
                        && (registration == null || !registration.isClosed())) {
                    return loadFileInternalScoped(document, secureDocument, webDocument, location, imageLocation, parentObject,
                            locationExt, locationSize, requestedPriority, stream, streamOffset, streamPriority, cacheType,
                            owner, admission, admitted, registration, nonStreamDemand);
                }
                return null;
            }
            priority = getPriorityValue(streamPriority(operation));
            if (operationUi != null) operationUi.operation = operation;
            if (admitted != null) admitted.accept(operation);
            if (cacheType != 10 && operation.isPreloadVideoOperation()) {
                operation.setIsPreloadVideoOperation(false);
            }
            operation.setForceRequest(priority > 0);
            operation.setStream(stream, streamPriority, streamOffset, registration);
            boolean priorityChanged = false;
            if (operation.getPriority() != priority) {
                priorityChanged = true;
                operation.setPriority(priority);
            }
            operation.getQueue().add(operation);
            operation.updateProgress();
            if (priorityChanged) {
                operation.getQueue().checkLoadingOperations();
            }
//            FileLog.d("load operation update position fileName=" + finalFileName + " position in queue " + operation.getPositionInQueue() + " preloadFinish " + operation.isPreloadFinished() + " priority=" + operation.getPriority());
            trackLocalFileOperation(operation);
            return operation;
        }

        File tempDir = getDirectory(MEDIA_DIR_CACHE);
        File storeDir = tempDir;
        int type = MEDIA_DIR_CACHE;
        long documentId = 0;
        int dcId = 0;

        if (secureDocument != null) {
            operation = new AccountFileLoadOperation(owner, secureDocument);
            type = MEDIA_DIR_DOCUMENT;
        } else if (location != null) {
            documentId = location.volume_id;
            dcId = location.dc_id + (location.local_id << 16);
            operation = new AccountFileLoadOperation(owner, imageLocation, parentObject, locationExt, locationSize);
            type = MEDIA_DIR_IMAGE;
        } else if (document != null) {
            operation = new AccountFileLoadOperation(owner, document, parentObject);
            if (MessageObject.isVoiceDocument(document)) {
                type = MEDIA_DIR_AUDIO;
            } else if (MessageObject.isVideoDocument(document)) {
                type = MEDIA_DIR_VIDEO;
                documentId = document.id;
                dcId = document.dc_id;
            } else {
                type = MEDIA_DIR_DOCUMENT;
                documentId = document.id;
                dcId = document.dc_id;
            }
            if (MessageObject.isRoundVideoDocument(document)) {
                documentId = 0;
                dcId = 0;
            }
        } else if (webDocument != null) {
            operation = new AccountFileLoadOperation(owner, currentAccount, webDocument);
            if (webDocument.location != null) {
                type = MEDIA_DIR_CACHE;
            } else if (MessageObject.isVoiceWebDocument(webDocument)) {
                type = MEDIA_DIR_AUDIO;
            } else if (MessageObject.isVideoWebDocument(webDocument)) {
                type = MEDIA_DIR_VIDEO;
            } else if (MessageObject.isImageWebDocument(webDocument)) {
                type = MEDIA_DIR_IMAGE;
            } else {
                type = MEDIA_DIR_DOCUMENT;
            }
        }

        FileLoaderPriorityQueue loaderQueue;
        int index = Utilities.clamp(operation.getDatacenterId() - 1, 4, 0);
        boolean isStory = parentObject instanceof TL_stories.StoryItem;
        if (operation.totalBytesCount > 20 * 1024 * 1024 || isStory) {
            loaderQueue = largeFilesQueue[index];
        } else {
            loaderQueue = smallFilesQueue[index];
        }

        String storeFileName = fileName;

        if (cacheType == 0 || cacheType == 10 || isStory) {
            if (documentId != 0) {
                String path = getFileDatabase().getPath(documentId, dcId, type, true);
                boolean customPath = false;
                if (path != null) {
                    File file = new File(path);
                    if (file.exists()) {
                        customPath = true;
                        storeFileName = file.getName();
                        storeDir = file.getParentFile();
                    }
                }
                if (!customPath) {
                    storeFileName = fileName;
                    storeDir = getDirectory(type);
                    boolean saveCustomPath = false;

                    if (isStory) {
                        File newDir = getDirectory(MEDIA_DIR_STORIES);
                        if (newDir != null) {
                            storeDir = newDir;
                            saveCustomPath = true;
                        }
                    } else if ((type == MEDIA_DIR_IMAGE || type == MEDIA_DIR_VIDEO) && canSaveToPublicStorage(parentObject)) {
                        File newDir;
                        if (type == MEDIA_DIR_IMAGE) {
                            newDir = getDirectory(MEDIA_DIR_IMAGE_PUBLIC);
                        } else {
                            newDir = getDirectory(MEDIA_DIR_VIDEO_PUBLIC);
                        }
                        if (newDir != null) {
                            storeDir = newDir;
                            saveCustomPath = true;
                        }
                    } else if (!TextUtils.isEmpty(getDocumentFileName(document)) && canSaveAsFile(parentObject)) {
                        storeFileName = getDocumentFileName(document);
                        File newDir = getDirectory(MEDIA_DIR_FILES);
                        if (newDir != null) {
                            storeDir = newDir;
                            saveCustomPath = true;
                        }
                    }

                    if (saveCustomPath) {
                        operation.pathSaveData = new FilePathDatabase.PathData(documentId, dcId, type);
                    }
                }
            } else {
                storeDir = getDirectory(type);
            }
        } else if (cacheType == ImageLoader.CACHE_TYPE_ENCRYPTED) {
            operation.setEncryptFile(true);
        }
        ((AccountFileLoadOperation) operation).admission = operationUi;
        operation.setPaths(currentAccount, fileName, loaderQueue, storeDir, tempDir, storeFileName);
        if (cacheType == 10) {
            operation.setIsPreloadVideoOperation(true);
        }

        final int finalType = type;

        FileLoadOperation.FileLoadOperationDelegate fileLoadOperationDelegate = new FileLoadOperation.FileLoadOperationDelegate() {

            @Override
            public void didPreFinishLoading(FileLoadOperation operation, File finalFile) {
                FileLoaderPriorityQueue queue = operation.getQueue();
                fileLoaderQueue.postRunnable(() -> {
                    if (!owner.isCurrent()) {
                        checkDownloadQueue(operation, queue);
                        return;
                    }
                    operation.preFinished = true;
                    queue.checkLoadingOperations();
                });
            }

            @Override
            public void didFinishLoadingFile(FileLoadOperation operation, File finalFile) {
                if (!owner.isCurrent()) {
                    if (delegate != null) delegate.fileDidFailedLoad(fileName, 1, owner, operation);
                    checkDownloadQueue(operation, operation.getQueue());
                    return;
                }
                if (!operation.isPreloadVideoOperation() && operation.isPreloadFinished()) {
                    checkDownloadQueue(operation, operation.getQueue(), 0);
                    return;
                }
                FilePathDatabase.FileMeta fileMeta = getFileMetadataFromParent(currentAccount, parentObject);
                if (fileMeta != null) {
                    getFileLoader().getFileDatabase().saveFileDialogId(finalFile, fileMeta);
                }
                if (parentObject instanceof MessageObject) {
                    MessageObject messageObject = (MessageObject) parentObject;
                    if (document != null && !isVideoStoryboardDocument(document) && messageObject.putInDownloadsStore) {
                        getDownloadController().onDownloadComplete(messageObject);
                    }
                }

                if (!operation.isPreloadVideoOperation()) {
                    clearLoadingUi(fileName, operation);
                    if (delegate != null) {
                        delegate.fileDidLoaded(fileName, finalFile, parentObject, finalType, owner, operation);
                    }
                }

                checkDownloadQueue(operation, operation.getQueue(), 0);
            }

            @Override
            public void didFailedLoadingFile(FileLoadOperation operation, int reason) {
                if (!owner.isCurrent()) {
                    if (delegate != null) delegate.fileDidFailedLoad(fileName, reason, owner, operation);
                    checkDownloadQueue(operation, operation.getQueue());
                    return;
                }
                if (reason == 1 && hasReplacementLoadingUi(fileName, operation)) {
                    checkDownloadQueue(operation, operation.getQueue());
                    return;
                }
                clearLoadingUi(fileName, operation);
                checkDownloadQueue(operation, operation.getQueue());
                if (delegate != null) {
                    delegate.fileDidFailedLoad(fileName, reason, owner, operation);
                }

                if (document != null && !isVideoStoryboardDocument(document) && parentObject instanceof MessageObject && reason == 0) {
                    getDownloadController().onDownloadFail((MessageObject) parentObject, reason);
                } else if (reason == -1) {
                    LaunchActivity.checkFreeDiscSpaceStatic(2);
                }
            }

            @Override
            public void didChangedLoadProgress(FileLoadOperation operation, long uploadedSize, long totalSize) {
                if (delegate != null && owner.isCurrent()) {
                    delegate.fileLoadProgressChanged(operation, fileName, uploadedSize, totalSize, owner);
                }
            }

            @Override
            public void saveFilePath(FilePathDatabase.PathData pathSaveData, File cacheFileFinal) {
                if (!owner.isCurrent()) return;
                getFileDatabase().putPath(pathSaveData.id, pathSaveData.dc, pathSaveData.type, 0, cacheFileFinal != null ? cacheFileFinal.toString() : null);
            }

            @Override
            public boolean hasAnotherRefOnFile(String path) {
                if (!owner.isCurrent()) return true;
                return getFileDatabase().hasAnotherRefOnFile(path);
            }

            @Override
            public boolean isLocallyCreatedFile(String path) {
                if (!owner.isCurrent()) return true;
                return getFileDatabase().isLocallyCreated(path);
            }
        };
        operation.setDelegate(fileLoadOperationDelegate);

        trackLocalFileOperation(operation);
        if (operationUi != null) operationUi.operation = operation;

        final FileLoadOperation admittedOperation = operation;
        final LoadOperationUIObject admittedUi = operationUi;
        if (loadOperationPaths.compute(finalFileName, (name, current) -> {
            if (!owner.isCurrent() || admittedUi != null && admittedUi.cancelled) return current;
            return current == null ? admittedOperation : current;
        }) != operation) {
            ((AccountFileLoadOperation) operation).retire();
            if (admitted != null) admitted.accept(operation);
            return null;
        }
        if (!admitStreamOperation(operation, registration, nonStreamDemand, requestedPriority)) {
            loadOperationPaths.remove(finalFileName, operation);
            ((AccountFileLoadOperation) operation).retire();
            clearLoadingUi(finalFileName, operation);
            if (admitted != null) admitted.accept(operation);
            return null;
        }
        if (admitted != null) admitted.accept(operation);
        priority = getPriorityValue(streamPriority(operation));
        operation.setForceRequest(priority > 0);
        operation.setPriority(priority);
        if (stream == null) {
            FileStreamLoadOperation hint = FileStreamLoadOperation.allStreams.get(documentId);
            StreamRegistration active = hint == null ? null : hint.registrationForOperation(operation);
            if (active != null) {
                stream = hint;
                registration = active;
            }
        }
        if (stream != null) {
            operation.setStream(stream, streamPriority, streamOffset, registration);
        }

        loaderQueue.add(operation);
        loaderQueue.checkLoadingOperations(operation.isStory && priority >= FileLoaderPriorityQueue.PRIORITY_VALUE_MAX);

        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("create load operation fileName=" + finalFileName + " documentName=" + getDocumentFileName(document) + " size=" + AndroidUtilities.formatFileSize(operation.totalBytesCount) + " position in queue " + operation.getPositionInQueue() + " account=" + currentAccount + " cacheType=" + cacheType + " priority=" + operation.getPriority() + " stream=" + stream);
        }
        return operation;
    }

    public static boolean canSaveAsFile(Object parentObject) {
        if (parentObject instanceof MessageObject) {
            MessageObject messageObject = (MessageObject) parentObject;
            if (!messageObject.isDocument() || messageObject.isRoundVideo() || messageObject.isVoice()) {
                return false;
            }
            return true;
        }
        return false;
    }

    private boolean canSaveToPublicStorage(Object parentObject) {
        if (BuildVars.NO_SCOPED_STORAGE) {
            return false;
        }
        FilePathDatabase.FileMeta metadata = getFileMetadataFromParent(currentAccount, parentObject);
        MessageObject messageObject = null;
        if (metadata != null) {
            int flag;
            long dialogId = metadata.dialogId;
            if (getMessagesController().isPeerNoForwards(dialogId) || DialogObject.isEncryptedDialog(dialogId)) {
                return false;
            }
            if (parentObject instanceof MessageObject) {
                messageObject = (MessageObject) parentObject;
                if (messageObject.isRoundVideo() || messageObject.isVoice() || messageObject.isAnyKindOfSticker() || messageObject.messageOwner.noforwards) {
                    return false;
                }
            } else {
                if (metadata.messageType == MessageObject.TYPE_ROUND_VIDEO || metadata.messageType == MessageObject.TYPE_STICKER || metadata.messageType == MessageObject.TYPE_VOICE) {
                    return false;
                }
            }
            if (dialogId >= 0) {
                flag = SharedConfig.SAVE_TO_GALLERY_FLAG_PEER;
            } else {
                if (ChatObject.isChannelAndNotMegaGroup(getMessagesController().getChat(-dialogId))) {
                    flag = SharedConfig.SAVE_TO_GALLERY_FLAG_CHANNELS;
                } else {
                    flag = SharedConfig.SAVE_TO_GALLERY_FLAG_GROUP;
                }
            }

            if (SaveToGallerySettingsHelper.needSave(flag, metadata, messageObject, currentAccount)) {
                return true;
            }
        }
        return false;
    }

    private void addOperationToQueue(FileLoadOperation operation, LinkedList<FileLoadOperation> queue) {
        int priority = operation.getPriority();
        if (priority > 0) {
            int index = queue.size();
            for (int a = 0, size = queue.size(); a < size; a++) {
                FileLoadOperation queuedOperation = queue.get(a);
                if (queuedOperation.getPriority() < priority) {
                    index = a;
                    break;
                }
            }
            queue.add(index, operation);
        } else {
            queue.add(operation);
        }
    }

    private void loadFile(final TLRPC.Document document, final SecureDocument secureDocument, final WebFile webDocument, TLRPC.TL_fileLocationToBeDeprecated location, final ImageLocation imageLocation, final Object parentObject, final String locationExt, final long locationSize, final int priority, final int cacheType) {
        loadFile(document, secureDocument, webDocument, location, imageLocation, parentObject, locationExt, locationSize,
                priority, cacheType, captureTransferOwner(currentAccount));
    }

    private void loadFile(final TLRPC.Document document, final SecureDocument secureDocument, final WebFile webDocument, TLRPC.TL_fileLocationToBeDeprecated location, final ImageLocation imageLocation, final Object parentObject, final String locationExt, final long locationSize, final int priority, final int cacheType, final TransferOwner owner) {
        loadFile(document, secureDocument, webDocument, location, imageLocation, parentObject, locationExt, locationSize,
                priority, cacheType, owner, null);
    }

    private void loadFile(final TLRPC.Document document, final SecureDocument secureDocument, final WebFile webDocument, TLRPC.TL_fileLocationToBeDeprecated location, final ImageLocation imageLocation, final Object parentObject, final String locationExt, final long locationSize, final int priority, final int cacheType, final TransferOwner owner, final Consumer<FileLoadOperation> admitted) {
        if (owner == null || owner.account != currentAccount || !owner.isCurrent()) {
            if (admitted != null) admitted.accept(null);
            return;
        }
        String fileName;
        if (location != null) {
            fileName = getAttachFileName(location, locationExt);
        } else if (secureDocument != null) {
            fileName = getAttachFileName(secureDocument);
        } else if (document != null) {
            fileName = getAttachFileName(document);
        } else if (webDocument != null) {
            fileName = getAttachFileName(webDocument);
        } else {
            fileName = null;
        }
        final boolean needsUi = cacheType != 10 && !TextUtils.isEmpty(fileName) && !fileName.contains("" + Integer.MIN_VALUE);
        final LoadOperationUIObject uiObject = needsUi ? admitLoadingUi(fileName, owner, new LoadOperationUIObject(owner)) : null;
        if (needsUi && uiObject == null) {
            if (admitted != null) admitted.accept(null);
            return;
        }
        final AtomicBoolean admissionReported = new AtomicBoolean();
        final AtomicBoolean admissionStarted = new AtomicBoolean();
        Consumer<FileLoadOperation> report = operation -> {
            if (admitted != null && admissionReported.compareAndSet(false, true)) admitted.accept(operation);
        };
        ImageLoadRunnable runnable = new ImageLoadRunnable() {
            @Override
            public void run() {
                admissionStarted.set(true);
                final FileLoadOperation[] admittedOperation = new FileLoadOperation[1];
                try {
                    if (owner.isCurrent()) {
                        loadFileInternal(document, secureDocument, webDocument, location, imageLocation, parentObject, locationExt, locationSize, priority, null, 0, false, cacheType, owner, uiObject,
                                operation -> admittedOperation[0] = operation);
                    } else if (uiObject != null && uiObject.operation == null && uiObject.loadInternalRunnable == this) {
                        loadOperationPathsUI.remove(fileName, uiObject);
                    }
                } finally {
                    pendingAdmission = false;
                    report.accept(admittedOperation[0] != null ? admittedOperation[0] : uiObject == null ? null : uiObject.operation);
                }
            }

            @Override
            public void cancelBeforeAdmission(FileLoadOperation operation) {
                if (!admissionStarted.get()) {
                    pendingAdmission = false;
                    report.accept(operation);
                }
            }
        };
        if (uiObject != null) uiObject.loadInternalRunnable = runnable;
        if (!postUploadTask(runnable)) {
            runnable.cancelBeforeAdmission(uiObject == null ? null : uiObject.operation);
            if (uiObject != null && uiObject.operation != null) refreshStreamOperation(uiObject.operation);
            if (uiObject != null && uiObject.operation == null) {
                uiObject.cancelled = true;
                loadOperationPathsUI.remove(fileName, uiObject);
                if (delegate != null) delegate.fileDidFailedLoad(fileName, 0, owner, null);
            }
        }
    }

    private static final FileLoadOperationStream pendingPlayerStream = () -> {};

    public void startVideoStream(TLRPC.Document document, Object parentObject) {
        final TransferOwner owner = captureTransferOwner(currentAccount);
        postUploadTask(() -> loadFileInternal(document, null, null, null, null, parentObject, null, 0, FileStreamLoadOperation.getStreamPrioriy(document), pendingPlayerStream, 0, false, 0, owner, null));
    }
    protected FileLoadOperation loadStreamFile(final FileLoadOperationStream stream, final TLRPC.Document document, final ImageLocation location, final Object parentObject, final long offset, final boolean priority, int loadingPriority) {
        return loadStreamFile(stream, document, location, parentObject, offset, priority, loadingPriority, document == null ? 1 : 0);
    }

    protected FileLoadOperation loadStreamFile(final FileLoadOperationStream stream, final TLRPC.Document document, final ImageLocation location, final Object parentObject, final long offset, final boolean priority, int loadingPriority, int cacheType) {
        return loadStreamFile(stream, document, location, parentObject, offset, priority, loadingPriority, cacheType, null);
    }

    protected FileLoadOperation loadStreamFile(final FileLoadOperationStream stream, final TLRPC.Document document, final ImageLocation location, final Object parentObject, final long offset, final boolean priority, int loadingPriority, int cacheType, BooleanSupplier stillNeeded) {
        return loadStreamFileWithRegistration(stream, document, location, parentObject, offset, priority, loadingPriority, cacheType, stillNeeded, null);
    }

    protected FileLoadOperation loadStreamFileWithRegistration(final FileLoadOperationStream stream, final TLRPC.Document document, final ImageLocation location, final Object parentObject, final long offset, final boolean priority, int loadingPriority, int cacheType, BooleanSupplier stillNeeded, StreamRegistration registration) {
        if (registration != null && registration.reader != stream) return null;
        final TransferOwner owner = registration == null ? captureTransferOwner(currentAccount) : registration.owner;
        final CountDownLatch semaphore = new CountDownLatch(1);
        final FileLoadOperation[] result = new FileLoadOperation[1];
        Runnable admission = () -> {
            try {
                if (owner.isCurrent() && (stillNeeded == null || stillNeeded.getAsBoolean()) && (registration == null || !registration.isClosed())) {
                    result[0] = loadFileInternalScoped(document, null, null, document == null && location != null ? location.location : null, location, parentObject, document == null && location != null ? "mp4" : null, document == null && location != null ? location.currentSize : 0, loadingPriority, stream, offset, priority, cacheType, owner, null, null, registration, stream == null);
                }
            } finally {
                semaphore.countDown();
            }
        };
        if (Thread.currentThread() == fileLoaderQueue) admission.run();
        else if (!postUploadTask(admission)) semaphore.countDown();
        awaitFileLoadOperation(semaphore, true);
        return owner.isCurrent() ? result[0] : null;
    }

    /**
     * Necessary to wait of the FileLoadOperation object, despite the interruption of the thread.
     * Thread can be interrupted by {@link ImageLoader.CacheOutTask#cancel}.
     * For cases when two {@link ImageReceiver} require loading of the same file and the first {@link ImageReceiver} decides to cancel the operation.
     * For example, to autoplay a video after sending a message.
     */
    private void awaitFileLoadOperation(CountDownLatch latch, boolean ignoreInterruption) {
        try {
            latch.await();
        } catch (Exception e) {
            FileLog.e(e, false);
            if (ignoreInterruption) awaitFileLoadOperation(latch, false);
        }
    }

    private void checkDownloadQueue(FileLoadOperation operation, FileLoaderPriorityQueue queue) {
        checkDownloadQueue(operation, queue, 0);
    }

    private void checkDownloadQueue(FileLoadOperation operation, FileLoaderPriorityQueue queue, long delay) {
        Runnable retire = () -> {
            forgetStreamOperation(operation);
            if (queue.remove(operation)) {
                loadOperationPaths.remove(operation.getFileName(), operation);
                clearLoadingUi(operation.getFileName(), operation);
                queue.checkLoadingOperations(operation.isStory);
            }
        };
        try {
            if (fileLoaderQueue.postRunnable(retire, delay)) return;
        } catch (RuntimeException e) {
            FileLog.e(e);
        }
        loadOperationPaths.remove(operation.getFileName(), operation);
        forgetStreamOperation(operation);
        clearLoadingUi(operation.getFileName(), operation);
    }

    public void setDelegate(FileLoaderDelegate fileLoaderDelegate) {
        delegate = fileLoaderDelegate;
    }

    public static String getMessageFileName(TLRPC.Message message) {
        if (message == null) {
            return "";
        }
        if (message instanceof TLRPC.TL_messageService) {
            if (message.action.photo != null) {
                ArrayList<TLRPC.PhotoSize> sizes = message.action.photo.sizes;
                if (sizes.size() > 0) {
                    TLRPC.PhotoSize sizeFull = getClosestPhotoSizeWithSize(sizes, AndroidUtilities.getPhotoSize());
                    if (sizeFull != null) {
                        return getAttachFileName(sizeFull);
                    }
                }
            }
        } else {
            if (MessageObject.getMedia(message) instanceof TLRPC.TL_messageMediaDocument) {
                return getAttachFileName(MessageObject.getMedia(message).document);
            } else if (MessageObject.getMedia(message) instanceof TLRPC.TL_messageMediaPhoto) {
                ArrayList<TLRPC.PhotoSize> sizes = MessageObject.getMedia(message).photo.sizes;
                if (sizes.size() > 0) {
                    TLRPC.PhotoSize sizeFull = getClosestPhotoSizeWithSize(sizes, AndroidUtilities.getPhotoSize(true), false, null, true);
                    if (sizeFull != null) {
                        return getAttachFileName(sizeFull);
                    }
                }
            } else if (MessageObject.getMedia(message) instanceof TLRPC.TL_messageMediaWebPage) {
                if (MessageObject.getMedia(message).webpage.document != null) {
                    return getAttachFileName(MessageObject.getMedia(message).webpage.document);
                } else if (MessageObject.getMedia(message).webpage.photo != null) {
                    ArrayList<TLRPC.PhotoSize> sizes = MessageObject.getMedia(message).webpage.photo.sizes;
                    if (sizes.size() > 0) {
                        TLRPC.PhotoSize sizeFull = getClosestPhotoSizeWithSize(sizes, AndroidUtilities.getPhotoSize());
                        if (sizeFull != null) {
                            return getAttachFileName(sizeFull);
                        }
                    }
                }
            } else if (MessageObject.getMedia(message) instanceof TLRPC.TL_messageMediaInvoice) {
                TLRPC.WebDocument document = ((TLRPC.TL_messageMediaInvoice) MessageObject.getMedia(message)).webPhoto;
                if (document != null) {
                    return Utilities.MD5(document.url) + "." + ImageLoader.getHttpUrlExtension(document.url, getMimeTypePart(document.mime_type));
                }
            }
        }
        return "";
    }

    public File getPathToMessage(TLRPC.Message message) {
        return getPathToMessage(message, true);
    }

    public File getPathToMessage(TLRPC.Message message, boolean useFileDatabaseQueue) {
        return getPathToMessage(message, false, useFileDatabaseQueue);
    }

    public File getPathToMessage(TLRPC.Message message, boolean forceCache, boolean useFileDatabaseQueue) {
        if (message == null) {
            return new File("");
        }
        if (message instanceof TLRPC.TL_messageService) {
            if (message.action.photo != null) {
                ArrayList<TLRPC.PhotoSize> sizes = message.action.photo.sizes;
                if (sizes.size() > 0) {
                    TLRPC.PhotoSize sizeFull = getClosestPhotoSizeWithSize(sizes, AndroidUtilities.getPhotoSize());
                    if (sizeFull != null) {
                        return getPathToAttach(sizeFull, null, forceCache, useFileDatabaseQueue);
                    }
                }
            }
        } else {
            if (MessageObject.getMedia(message) instanceof TLRPC.TL_messageMediaDocument) {
                return getPathToAttach(MessageObject.getMedia(message).document, null, forceCache || MessageObject.getMedia(message).ttl_seconds != 0, useFileDatabaseQueue);
            } else if (MessageObject.getMedia(message) instanceof TLRPC.TL_messageMediaPhoto) {
                ArrayList<TLRPC.PhotoSize> sizes = MessageObject.getMedia(message).photo.sizes;
                if (sizes.size() > 0) {
                    TLRPC.PhotoSize sizeFull = getClosestPhotoSizeWithSize(sizes, AndroidUtilities.getPhotoSize(true), false, null, true);
                    if (sizeFull != null) {
                        return getPathToAttach(sizeFull, null, forceCache || MessageObject.getMedia(message).ttl_seconds != 0, useFileDatabaseQueue);
                    }
                }
            } else if (MessageObject.getMedia(message) instanceof TLRPC.TL_messageMediaWebPage) {
                if (MessageObject.getMedia(message).webpage.document != null) {
                    return getPathToAttach(MessageObject.getMedia(message).webpage.document, null, forceCache, useFileDatabaseQueue);
                } else if (MessageObject.getMedia(message).webpage.photo != null) {
                    ArrayList<TLRPC.PhotoSize> sizes = MessageObject.getMedia(message).webpage.photo.sizes;
                    if (sizes.size() > 0) {
                        TLRPC.PhotoSize sizeFull = getClosestPhotoSizeWithSize(sizes, AndroidUtilities.getPhotoSize());
                        if (sizeFull != null) {
                            return getPathToAttach(sizeFull, null, forceCache, useFileDatabaseQueue);
                        }
                    }
                }
            } else if (MessageObject.getMedia(message) instanceof TLRPC.TL_messageMediaInvoice) {
                return getPathToAttach(((TLRPC.TL_messageMediaInvoice) MessageObject.getMedia(message)).photo, null, true, useFileDatabaseQueue);
            }
        }
        return new File("");
    }

    public File getPathToAttach(TLObject attach) {
        return getPathToAttach(attach, null, false);
    }

    public File getPathToAttach(TLObject attach, boolean forceCache) {
        return getPathToAttach(attach, null, forceCache);
    }

    public File getPathToAttach(TLObject attach, String ext, boolean forceCache) {
        return getPathToAttach(attach, null, ext, forceCache, true);
    }

    public File getPathToAttach(TLObject attach, String ext, boolean forceCache, boolean useFileDatabaseQueue) {
        return getPathToAttach(attach, null, ext, forceCache, useFileDatabaseQueue);
    }

    /**
     * Return real file name. Used before file.exist()
     */
    public File getPathToAttach(TLObject attach, String size, String ext, boolean forceCache, boolean useFileDatabaseQueue) {
        File dir = null;
        long documentId = 0;
        int dcId = 0;
        int type = 0;
        if (forceCache) {
            dir = getDirectory(MEDIA_DIR_CACHE);
        } else {
            if (attach instanceof TLRPC.Document) {
                TLRPC.Document document = (TLRPC.Document) attach;
                if (!TextUtils.isEmpty(document.localPath)) {
                    return new File(document.localPath);
                }
                if (document.key != null) {
                    type = MEDIA_DIR_CACHE;
                } else {
                    if (MessageObject.isVoiceDocument(document)) {
                        type = MEDIA_DIR_AUDIO;
                    } else if (MessageObject.isVideoDocument(document)) {
                        type = MEDIA_DIR_VIDEO;
                    } else {
                        type = MEDIA_DIR_DOCUMENT;
                    }
                }
                documentId = document.id;
                dcId = document.dc_id;
                dir = getDirectory(type);
            } else if (attach instanceof TLRPC.Photo) {
                TLRPC.PhotoSize photoSize = getClosestPhotoSizeWithSize(((TLRPC.Photo) attach).sizes, AndroidUtilities.getPhotoSize(true));
                return getPathToAttach(photoSize, ext, false, useFileDatabaseQueue);
            } else if (attach instanceof TLRPC.PhotoSize) {
                TLRPC.PhotoSize photoSize = (TLRPC.PhotoSize) attach;
                if (photoSize instanceof TLRPC.TL_photoStrippedSize || photoSize instanceof TLRPC.TL_photoPathSize) {
                    dir = null;
                } else if (photoSize.location == null || photoSize.location.key != null || photoSize.location.volume_id == Integer.MIN_VALUE && photoSize.location.local_id < 0 || photoSize.size < 0) {
                    dir = getDirectory(type = MEDIA_DIR_CACHE);
                } else {
                    dir = getDirectory(type = MEDIA_DIR_IMAGE);
                }
                documentId = photoSize.location.volume_id;
                dcId = photoSize.location.dc_id + (photoSize.location.local_id << 16);
            } else if (attach instanceof TLRPC.TL_videoSize) {
                TLRPC.TL_videoSize videoSize = (TLRPC.TL_videoSize) attach;
                if (videoSize.location == null || videoSize.location.key != null || videoSize.location.volume_id == Integer.MIN_VALUE && videoSize.location.local_id < 0 || videoSize.size < 0) {
                    dir = getDirectory(type = MEDIA_DIR_CACHE);
                } else {
                    dir = getDirectory(type = MEDIA_DIR_IMAGE);
                }
                documentId = videoSize.location.volume_id;
                dcId = videoSize.location.dc_id + (videoSize.location.local_id << 16);
            } else if (attach instanceof TLRPC.FileLocation) {
                TLRPC.FileLocation fileLocation = (TLRPC.FileLocation) attach;
                if (fileLocation.key != null || fileLocation.volume_id == Integer.MIN_VALUE && fileLocation.local_id < 0) {
                    dir = getDirectory(MEDIA_DIR_CACHE);
                } else {
                    documentId = fileLocation.volume_id;
                    dcId = fileLocation.dc_id + (fileLocation.local_id << 16);
                    dir = getDirectory(type = MEDIA_DIR_IMAGE);
                }
            } else if (attach instanceof TLRPC.UserProfilePhoto || attach instanceof TLRPC.ChatPhoto) {
                if (size == null) {
                    size = "s";
                }
                if ("s".equals(size)) {
                    dir = getDirectory(MEDIA_DIR_CACHE);
                } else {
                    dir = getDirectory(MEDIA_DIR_IMAGE);
                }
            } else if (attach instanceof WebFile) {
                WebFile document = (WebFile) attach;
                if (document.mime_type.startsWith("image/")) {
                    dir = getDirectory(MEDIA_DIR_IMAGE);
                } else if (document.mime_type.startsWith("audio/")) {
                    dir = getDirectory(MEDIA_DIR_AUDIO);
                } else if (document.mime_type.startsWith("video/")) {
                    dir = getDirectory(MEDIA_DIR_VIDEO);
                } else {
                    dir = getDirectory(MEDIA_DIR_DOCUMENT);
                }
            } else if (attach instanceof TLRPC.TL_secureFile || attach instanceof SecureDocument) {
                dir = getDirectory(MEDIA_DIR_CACHE);
            }
        }
        if (dir == null) {
            return new File("");
        }
        if (documentId != 0) {
            String path = getFileDatabase().getPath(documentId, dcId, type, useFileDatabaseQueue);
            if (path != null) {
                return new File(path);
            }
        }
        return new File(dir, getAttachFileName(attach, ext));
    }

    public FilePathDatabase getFileDatabase() {
        return filePathDatabase;
    }

    public static TLRPC.TL_photoStrippedSize getStrippedPhotoSize(ArrayList<TLRPC.PhotoSize> sizes) {
        if (sizes == null) return null;
        for (int i = 0; i < sizes.size(); ++i) {
            final TLRPC.PhotoSize photoSize = sizes.get(i);
            if (photoSize instanceof TLRPC.TL_photoStrippedSize) {
                return (TLRPC.TL_photoStrippedSize) photoSize;
            }
        }
        return null;
    }

    public static TLRPC.PhotoSize getClosestPhotoSizeWithSize(ArrayList<TLRPC.PhotoSize> sizes, int side) {
        return getClosestPhotoSizeWithSize(sizes, side, false);
    }

    public static TLRPC.PhotoSize getClosestPhotoSizeWithSize(ArrayList<TLRPC.PhotoSize> sizes, int side, boolean byMinSide) {
        return getClosestPhotoSizeWithSize(sizes, side, byMinSide, null, false);
    }

    public static TLRPC.PhotoSize getClosestPhotoSizeWithSize(ArrayList<TLRPC.PhotoSize> sizes, int side, boolean byMinSide, TLRPC.PhotoSize toIgnore, boolean ignoreStripped) {
        if (sizes == null || sizes.isEmpty()) {
            return null;
        }
        int lastSide = 0;
        TLRPC.PhotoSize closestObject = null;
        for (int a = 0; a < sizes.size(); a++) {
            TLRPC.PhotoSize obj = sizes.get(a);
            if (obj == null || obj == toIgnore || obj instanceof TLRPC.TL_photoSizeEmpty || obj instanceof TLRPC.TL_photoPathSize || ignoreStripped && obj instanceof TLRPC.TL_photoStrippedSize) {
                continue;
            }
            if (byMinSide) {
                int currentSide = Math.min(obj.h, obj.w);
                if (
                    closestObject == null ||
                    side > 100 && closestObject.location != null && closestObject.location.dc_id == Integer.MIN_VALUE ||
                    obj instanceof TLRPC.TL_photoCachedSize || side > lastSide && lastSide < currentSide
                ) {
                    closestObject = obj;
                    lastSide = currentSide;
                }
            } else {
                int currentSide = Math.max(obj.w, obj.h);
                if (
                    closestObject == null ||
                    side > 100 && closestObject.location != null && closestObject.location.dc_id == Integer.MIN_VALUE ||
                    obj instanceof TLRPC.TL_photoCachedSize ||
                    currentSide <= side && lastSide < currentSide
                ) {
                    closestObject = obj;
                    lastSide = currentSide;
                }
            }
        }
        return closestObject;
    }

    public static TLRPC.VideoSize getClosestVideoSizeWithSize(ArrayList<TLRPC.VideoSize> sizes, int side) {
        return getClosestVideoSizeWithSize(sizes, side, false);
    }

    public static TLRPC.VideoSize getClosestVideoSizeWithSize(ArrayList<TLRPC.VideoSize> sizes, int side, boolean byMinSide) {
        return getClosestVideoSizeWithSize(sizes, side, byMinSide, false);
    }

    public static TLRPC.VideoSize getClosestVideoSizeWithSize(ArrayList<TLRPC.VideoSize> sizes, int side, boolean byMinSide, boolean ignoreStripped) {
        if (sizes == null || sizes.isEmpty()) {
            return null;
        }
        int lastSide = 0;
        TLRPC.VideoSize closestObject = null;
        for (int a = 0; a < sizes.size(); a++) {
            TLRPC.VideoSize obj = sizes.get(a);
            if (obj == null || obj instanceof TLRPC.TL_videoSizeEmojiMarkup || obj instanceof TLRPC.TL_videoSizeStickerMarkup) {
                continue;
            }
            if (byMinSide) {
                int currentSide = Math.min(obj.h, obj.w);
                if (closestObject == null ||
                                side > 100 && closestObject.location != null && closestObject.location.dc_id == Integer.MIN_VALUE ||
                                side > lastSide && lastSide < currentSide) {
                    closestObject = obj;
                    lastSide = currentSide;
                }
            } else {
                int currentSide = Math.max(obj.w, obj.h);
                if (
                        closestObject == null ||
                                side > 100 && closestObject.location != null && closestObject.location.dc_id == Integer.MIN_VALUE ||
                                currentSide <= side && lastSide < currentSide
                ) {
                    closestObject = obj;
                    lastSide = currentSide;
                }
            }
        }
        return closestObject;
    }

    public static TLRPC.TL_photoPathSize getPathPhotoSize(ArrayList<TLRPC.PhotoSize> sizes) {
        if (sizes == null || sizes.isEmpty()) {
            return null;
        }
        for (int a = 0; a < sizes.size(); a++) {
            TLRPC.PhotoSize obj = sizes.get(a);
            if (obj instanceof TLRPC.TL_photoPathSize) {
                continue;
            }
            return (TLRPC.TL_photoPathSize) obj;
        }
        return null;
    }

    public static String getFileExtension(File file) {
        String name = file.getName();
        try {
            return name.substring(name.lastIndexOf('.') + 1);
        } catch (Exception e) {
            return "";
        }
    }

    public static String fixFileName(String fileName) {
        if (fileName != null) {
            fileName = fileName.replaceAll("[\u0001-\u001f<>\u202E:\"/\\\\|?*\u007f]+", "").trim();
        }
        return fileName;
    }

    public static String getDocumentFileName(TLRPC.Document document) {
        if (document == null) {
            return null;
        }
        if (document.file_name_fixed != null) {
            return document.file_name_fixed;
        }
        String fileName = null;
        if (document != null) {
            if (document.file_name != null) {
                fileName = document.file_name;
            } else {
                for (int a = 0; a < document.attributes.size(); a++) {
                    TLRPC.DocumentAttribute documentAttribute = document.attributes.get(a);
                    if (documentAttribute instanceof TLRPC.TL_documentAttributeFilename) {
                        fileName = documentAttribute.file_name;
                    }
                }
            }
        }
        fileName = fixFileName(fileName);
        return fileName != null ? fileName : "";
    }

    public static String getMimeTypePart(String mime) {
        int index;
        if ((index = mime.lastIndexOf('/')) != -1) {
            return mime.substring(index + 1);
        }
        return "";
    }

    public static String getExtensionByMimeType(String mime) {
        if (mime != null) {
            switch (mime) {
                case "video/mp4":
                    return ".mp4";
                case "video/x-matroska":
                    return ".mkv";
                case "audio/ogg":
                    return ".ogg";
            }
        }
        return "";
    }

    public static File getInternalCacheDir() {
        return ApplicationLoader.applicationContext.getCacheDir();
    }

    public static String getDocumentExtension(TLRPC.Document document) {
        String fileName = getDocumentFileName(document);
        int idx = fileName.lastIndexOf('.');
        String ext = null;
        if (idx != -1) {
            ext = fileName.substring(idx + 1);
        }
        if (ext == null || ext.length() == 0) {
            ext = document.mime_type;
        }
        if (ext == null) {
            ext = "";
        }
        ext = ext.toUpperCase();
        return ext;
    }

    public static String getAttachFileName(TLObject attach) {
        return getAttachFileName(attach, null);
    }

    public static String getAttachFileName(TLObject attach, String ext) {
        return getAttachFileName(attach, null, ext);
    }

    /**
     * file hash. contains docId, dcId, ext.
     */
    public static String getAttachFileName(TLObject attach, String size, String ext) {
        if (attach instanceof TLRPC.Document) {
            TLRPC.Document document = (TLRPC.Document) attach;
            String docExt;
            docExt = getDocumentFileName(document);
            int idx;
            if ((idx = docExt.lastIndexOf('.')) == -1) {
                docExt = "";
            } else {
                docExt = docExt.substring(idx);
            }
            if (docExt.length() <= 1) {
                docExt = getExtensionByMimeType(document.mime_type);
            }
            if (docExt.length() > 1) {
                return document.dc_id + "_" + document.id + docExt;
            } else {
                return document.dc_id + "_" + document.id;
            }
        } else if (attach instanceof SecureDocument) {
            SecureDocument secureDocument = (SecureDocument) attach;
            return secureDocument.secureFile.dc_id + "_" + secureDocument.secureFile.id + ".jpg";
        } else if (attach instanceof TLRPC.TL_secureFile) {
            TLRPC.TL_secureFile secureFile = (TLRPC.TL_secureFile) attach;
            return secureFile.dc_id + "_" + secureFile.id + ".jpg";
        } else if (attach instanceof WebFile) {
            WebFile document = (WebFile) attach;
            return Utilities.MD5(document.url) + "." + ImageLoader.getHttpUrlExtension(document.url, getMimeTypePart(document.mime_type));
        } else if (attach instanceof TLRPC.PhotoSize) {
            TLRPC.PhotoSize photo = (TLRPC.PhotoSize) attach;
            if (photo.location == null || photo.location instanceof TLRPC.TL_fileLocationUnavailable) {
                return "";
            }
            return photo.location.volume_id + "_" + photo.location.local_id + "." + (ext != null ? ext : "jpg");
        } else if (attach instanceof TLRPC.TL_videoSize) {
            TLRPC.TL_videoSize video = (TLRPC.TL_videoSize) attach;
            if (video.location == null || video.location instanceof TLRPC.TL_fileLocationUnavailable) {
                return "";
            }
            return video.location.volume_id + "_" + video.location.local_id + "." + (ext != null ? ext : "mp4");
        } else if (attach instanceof TLRPC.FileLocation) {
            if (attach instanceof TLRPC.TL_fileLocationUnavailable) {
                return "";
            }
            TLRPC.FileLocation location = (TLRPC.FileLocation) attach;
            return location.volume_id + "_" + location.local_id + "." + (ext != null ? ext : "jpg");
        } else if (attach instanceof TLRPC.UserProfilePhoto) {
            if (size == null) {
                size = "s";
            }
            TLRPC.UserProfilePhoto location = (TLRPC.UserProfilePhoto) attach;
            if (location.photo_small != null) {
                if ("s".equals(size)) {
                    return getAttachFileName(location.photo_small, ext);
                } else {
                    return getAttachFileName(location.photo_big, ext);
                }
            } else {
                return location.photo_id + "_" + size + "." + (ext != null ? ext : "jpg");
            }
        } else if (attach instanceof TLRPC.ChatPhoto) {
            TLRPC.ChatPhoto location = (TLRPC.ChatPhoto) attach;
            if (location.photo_small != null) {
                if ("s".equals(size)) {
                    return getAttachFileName(location.photo_small, ext);
                } else {
                    return getAttachFileName(location.photo_big, ext);
                }
            } else {
                return location.photo_id + "_" + size + "." + (ext != null ? ext : "jpg");
            }
        }
        return "";
    }

    public void deleteFiles(final ArrayList<File> files, final int type) {
        if (files == null || files.isEmpty()) {
            return;
        }
        fileLoaderQueue.postRunnable(() -> {
            for (int a = 0; a < files.size(); a++) {
                File file = files.get(a);
                File encrypted = new File(file.getAbsolutePath() + ".enc");
                if (encrypted.exists()) {
                    try {
                        if (!encrypted.delete()) {
                            encrypted.deleteOnExit();
                        }
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                    try {
                        File key = new File(FileLoader.getInternalCacheDir(), file.getName() + ".enc.key");
                        if (!key.delete()) {
                            key.deleteOnExit();
                        }
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                } else if (file.exists()) {
                    try {
                        if (!file.delete()) {
                            file.deleteOnExit();
                        }
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                }
                try {
                    File qFile = new File(file.getParentFile(), "q_" + file.getName());
                    if (qFile.exists()) {
                        if (!qFile.delete()) {
                            qFile.deleteOnExit();
                        }
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
            if (type == 2) {
                ImageLoader.getInstance().clearMemory();
            }
        });
    }

    public static boolean isVideoMimeType(String mime) {
        return "video/mp4".equals(mime) || SharedConfig.streamMkv && "video/x-matroska".equals(mime);
    }

    public static boolean copyFile(InputStream sourceFile, File destFile) throws IOException {
        return copyFile(sourceFile, destFile, -1);
    }

    public static boolean copyFile(InputStream sourceFile, File destFile, int maxSize) throws IOException {
        FileOutputStream out = new FileOutputStream(destFile);
        byte[] buf = new byte[4096];
        int len;
        int totalLen = 0;
        while ((len = sourceFile.read(buf)) > 0) {
            Thread.yield();
            out.write(buf, 0, len);
            totalLen += len;
            if (maxSize > 0 && totalLen >= maxSize) {
                break;
            }
        }
        out.getFD().sync();
        out.close();
        return true;
    }

    public static boolean isSamePhoto(TLObject photo1, TLObject photo2) {
        if (photo1 == null && photo2 != null || photo1 != null && photo2 == null) {
            return false;
        }
        if (photo1 == null && photo2 == null) {
            return true;
        }
        if (photo1.getClass() != photo2.getClass()) {
            return false;
        }
        if (photo1 instanceof TLRPC.UserProfilePhoto) {
            TLRPC.UserProfilePhoto p1 = (TLRPC.UserProfilePhoto) photo1;
            TLRPC.UserProfilePhoto p2 = (TLRPC.UserProfilePhoto) photo2;
            return p1.photo_id == p2.photo_id;
        } else if (photo1 instanceof TLRPC.ChatPhoto) {
            TLRPC.ChatPhoto p1 = (TLRPC.ChatPhoto) photo1;
            TLRPC.ChatPhoto p2 = (TLRPC.ChatPhoto) photo2;
            return p1.photo_id == p2.photo_id;
        }
        return false;
    }

    public static boolean isSamePhoto(TLRPC.FileLocation location, TLRPC.Photo photo) {
        if (location == null || !(photo instanceof TLRPC.TL_photo)) {
            return false;
        }
        for (int b = 0, N = photo.sizes.size(); b < N; b++) {
            TLRPC.PhotoSize size = photo.sizes.get(b);
            if (size.location != null && size.location.local_id == location.local_id && size.location.volume_id == location.volume_id) {
                return true;
            }
        }
        if (-location.volume_id == photo.id) {
            return true;
        }
        return false;
    }

    public static long getPhotoId(TLObject object) {
        if (object instanceof TLRPC.Photo) {
            return ((TLRPC.Photo) object).id;
        } else if (object instanceof TLRPC.ChatPhoto) {
            return ((TLRPC.ChatPhoto) object).photo_id;
        } else if (object instanceof TLRPC.UserProfilePhoto) {
            return ((TLRPC.UserProfilePhoto) object).photo_id;
        }
        return 0;
    }

    public void getCurrentLoadingFiles(ArrayList<MessageObject> currentLoadingFiles) {
        currentLoadingFiles.clear();
        currentLoadingFiles.addAll(getDownloadController().downloadingFiles);
        for (int i = 0; i < currentLoadingFiles.size(); i++) {
            currentLoadingFiles.get(i).isDownloadingFile = true;
        }
    }

    public void getRecentLoadingFiles(ArrayList<MessageObject> recentLoadingFiles) {
        recentLoadingFiles.clear();
        recentLoadingFiles.addAll(getDownloadController().recentDownloadingFiles);
        for (int i = 0; i < recentLoadingFiles.size(); i++) {
            recentLoadingFiles.get(i).isDownloadingFile = true;
        }
    }

    public void checkCurrentDownloadsFiles() {
        ArrayList<MessageObject> messagesToRemove = new ArrayList<>();
        ArrayList<MessageObject> messageObjects = new ArrayList<>(getDownloadController().recentDownloadingFiles);
        for (int i = 0; i < messageObjects.size(); i++) {
            messageObjects.get(i).checkMediaExistance();
            if (messageObjects.get(i).mediaExists) {
                messagesToRemove.add(messageObjects.get(i));
            }
        }
        if (!messagesToRemove.isEmpty()) {
            AndroidUtilities.runOnUIThread(() -> {
                getDownloadController().recentDownloadingFiles.removeAll(messagesToRemove);
                getNotificationCenter().postNotificationName(NotificationCenter.onDownloadingFilesChanged);
            });
        }
    }

    /**
     * optimezed for bulk messages
     */
    public void checkMediaExistance(ArrayList<MessageObject> messageObjects) {
        getFileDatabase().checkMediaExistance(messageObjects);
    }

    public interface FileResolver {
        File getFile();
    }

    public void clearRecentDownloadedFiles() {
        getDownloadController().clearRecentDownloadedFiles();
    }

    public void clearFilePaths() {
        filePathDatabase.clear();
    }

    public static boolean checkUploadFileSize(int currentAccount, long length) {
        boolean premium = AccountInstance.getInstance(currentAccount).getUserConfig().isPremium();
        if (length < DEFAULT_MAX_FILE_SIZE || (length < DEFAULT_MAX_FILE_SIZE_PREMIUM && premium)) {
            return true;
        }
        return false;
    }

    private LoadOperationUIObject admitLoadingUi(String name, TransferOwner owner, LoadOperationUIObject admission) {
        LoadOperationUIObject ui = loadOperationPathsUI.compute(name, (key, current) -> {
            if (!owner.isCurrent() || admission.cancelled) return current;
            return current != null && !current.cancelled && owner.matches(current.owner) ? current : admission;
        });
        return owner.isCurrent() && ui != null && !ui.cancelled && owner.matches(ui.owner) ? ui : null;
    }
    private static class LoadOperationUIObject {
        final TransferOwner owner;
        volatile boolean cancelled;
        volatile Runnable loadInternalRunnable;
        volatile FileLoadOperation operation;
        LoadOperationUIObject(TransferOwner owner) {
            this.owner = owner;
        }
    }

    private boolean hasReplacementLoadingUi(String fileName, FileLoadOperation operation) {
        FileLoadOperation active = loadOperationPaths.get(fileName);
        LoadOperationUIObject ui = loadOperationPathsUI.get(fileName);
        return active != null && active != operation || ui != null && ui.operation != operation;
    }

    private void clearLoadingUi(String fileName, FileLoadOperation operation) {
        LoadOperationUIObject ui = loadOperationPathsUI.get(fileName);
        if (ui != null && ui.operation == operation) {
            loadOperationPathsUI.remove(fileName, ui);
        }
    }

    public static byte[] longToBytes(long x) {
        ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES);
        buffer.putLong(x);
        return buffer.array();
    }

    public static long bytesToLong(byte[] bytes) {
        long l = 0;
        for (int i = 0; i < 8; i++) {
            l <<= 8;
            l ^= bytes[i] & 0xFF;
        }
        return l;
    }

    Runnable dumpFilesQueueRunnable = () -> {
        for (int i = 0; i < smallFilesQueue.length; i++) {
            if (smallFilesQueue[i].getCount() > 0 || largeFilesQueue[i].getCount() > 0) {
                FileLog.d("download queue: dc" + (i + 1) + " account=" + currentAccount + " small_operations=" + smallFilesQueue[i].getCount() + " large_operations=" + largeFilesQueue[i].getCount());
//                if (!largeFilesQueue[i].allOperations.isEmpty()) {
//                    largeFilesQueue[i].allOperations.get(0).dump();
//                }
            }
        }
        dumpFilesQueue();
    };

    public void dumpFilesQueue() {
        if (!BuildVars.LOGS_ENABLED) {
            return;
        }
        fileLoaderQueue.cancelRunnable(dumpFilesQueueRunnable);
        fileLoaderQueue.postRunnable(dumpFilesQueueRunnable, 10000);
    }

    public void uploadFile(final String path, Utilities.Callback<TLRPC.InputFile> done) {
        uploadFile(path, false, ConnectionsManager.FileTypeFile, new UploadFileCallback() {
            @Override
            public void onProgress(UploadRequest request, long uploadedSize, long totalSize) {
            }

            @Override
            public void onUploaded(UploadRequest request, TLRPC.InputFile file) {
                if (request.owner.isCurrent()) done.run(file);
            }

            @Override
            public void onFailed(UploadRequest request) {
                if (request.owner.isCurrent()) done.run(null);
            }
        });
    }
}
