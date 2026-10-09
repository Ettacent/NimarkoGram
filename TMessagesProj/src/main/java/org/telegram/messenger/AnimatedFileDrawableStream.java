/* Modifications Copyright (C) 2026 Ettacent */

package org.telegram.messenger;

import androidx.media3.exoplayer.ExoPlayer;


import org.telegram.tgnet.TLRPC;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class AnimatedFileDrawableStream implements FileLoadOperationStream {

    private FileLoadOperation loadOperation;
    private CountDownLatch countDownLatch;
    private TLRPC.Document document;
    private ImageLocation location;
    private Object parentObject;
    private int currentAccount;
    private volatile boolean canceled;
    private final Object sync = new Object();
    private boolean restartLoading;
    private long cancellationGeneration;
    private long lastOffset;
    private volatile boolean waitingForLoad;
    private boolean preview;
    private boolean finishedLoadingFile;
    private String finishedFilePath;
    private int loadingPriority;
    private final int cacheType;

    private int debugCanceledCount;
    private boolean debugReportSend;

    public AnimatedFileDrawableStream(TLRPC.Document d, ImageLocation l, Object p, int a, boolean prev, int loadingPriority, int cacheType) {
        document = d;
        location = l;
        parentObject = p;
        currentAccount = a;
        preview = prev;
        this.loadingPriority = loadingPriority;
        this.cacheType = cacheType;
        loadOperation = FileLoader.getInstance(currentAccount).loadStreamFile(this, document, location, parentObject, 0, preview, loadingPriority, cacheType);
    }

    public boolean isFinishedLoadingFile() {
        return finishedLoadingFile;
    }

    public String getFinishedFilePath() {
        return finishedFilePath;
    }

    public int read(int offset, int readLength) {
        final long generation;
        synchronized (sync) {
            if (canceled) {
                debugCanceledCount++;
                if (!debugReportSend && debugCanceledCount > 200) {
                    debugReportSend = true;
                    FileLog.e(new RuntimeException("infinity stream reading!!!"));
                }
                return 0;
            }
            generation = cancellationGeneration;
        }
        if (readLength == 0) {
            return 0;
        } else {
            long availableLength = 0;
            try {
                while (availableLength == 0) {
                    final CountDownLatch wakeup = new CountDownLatch(1);
                    final boolean restart;
                    synchronized (sync) {
                        if (canceled || generation != cancellationGeneration) return 0;
                        countDownLatch = wakeup;
                        restart = restartLoading;
                    }
                    if (restart && !reacquireLoadOperation(offset, generation)) return 0;
                    final FileLoadOperation operation;
                    synchronized (sync) {
                        if (canceled || generation != cancellationGeneration) return 0;
                        operation = loadOperation;
                    }
                    long[] result = operation.getDownloadedLengthFromOffset(offset, readLength);
                    synchronized (sync) {
                        if (canceled || generation != cancellationGeneration) return 0;
                        availableLength = result[0];
                        if (result[2] != 0) {
                            return 0;
                        }
                        if (!finishedLoadingFile && result[1] != 0) {
                            finishedLoadingFile = true;
                            finishedFilePath = operation.getCacheFileFinal().getAbsolutePath();
                        }
                        if (availableLength == 0 && result[1] != 0) {
                            return 0;
                        }
                    }
                    if (availableLength == 0) {
                        synchronized (sync) {
                            if (canceled || generation != cancellationGeneration) return 0;
                        }
                        if (operation.isPaused() || lastOffset != offset || preview) {
                            if (!reacquireLoadOperation(offset, generation)) return 0;
                            if (operation != loadOperation) continue;
                            lastOffset = offset + availableLength;
                        }
                        synchronized (sync) {
                            if (canceled || generation != cancellationGeneration) {
                                return 0;
                            }
                            waitingForLoad = true;
                        }
                        if (!preview) {
                            FileLoader.getInstance(currentAccount).setLoadingVideo(document, false, true);
                        }
                        try {
                            wakeup.await(1, TimeUnit.SECONDS);
                        } finally {
                            synchronized (sync) {
                                if (generation == cancellationGeneration) waitingForLoad = false;
                            }
                        }
                    }
                }
                synchronized (sync) {
                    if (canceled || generation != cancellationGeneration) return 0;
                    lastOffset = offset + availableLength;
                }
            } catch (Exception e) {
                FileLog.e(e, false);
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            } finally {
                synchronized (sync) {
                    if (generation == cancellationGeneration) {
                        countDownLatch = null;
                        waitingForLoad = false;
                    }
                }
            }
            return (int) availableLength;
        }
    }

    private boolean reacquireLoadOperation(int offset, long generation) {
        synchronized (sync) {
            if (canceled || generation != cancellationGeneration) return false;
        }
        FileLoadOperation replacement = FileLoader.getInstance(currentAccount).loadStreamFile(this, document, location, parentObject, offset, preview, loadingPriority, cacheType, () -> {
            synchronized (sync) {
                return !canceled && generation == cancellationGeneration;
            }
        });
        synchronized (sync) {
            if (canceled || generation != cancellationGeneration) {
                if (replacement != null && replacement != loadOperation) {
                    replacement.removeStreamListener(this);
                }
                return false;
            }
            if (replacement == null) return false;
            if (replacement != loadOperation) {
                loadOperation.removeStreamListener(this);
                loadOperation = replacement;
                finishedLoadingFile = false;
                finishedFilePath = null;
            }
            restartLoading = false;
            return true;
        }
    }

    public void cancel() {
        cancel(true);
    }

    public void cancel(boolean removeLoading) {
        synchronized (sync) {
            if (canceled) return;
            cancellationGeneration++;
            waitingForLoad = false;
            if (countDownLatch != null) {
                countDownLatch.countDown();
                countDownLatch = null;
                if (removeLoading && !canceled && !preview) {
                    FileLoader.getInstance(currentAccount).removeLoadingVideo(document, false, true);
                }
            }
            if (parentObject instanceof MessageObject) {
                MessageObject messageObject = (MessageObject) parentObject;
                if (DownloadController.getInstance(messageObject.currentAccount).isDownloading(messageObject.getId()))  {
                    removeLoading = false;
                }
            }
            if (removeLoading) {
                cancelLoadingInternal();
            }
            canceled = true;
        }
    }

    private void cancelLoadingInternal() {
        restartLoading = true;
        FileLoader.getInstance(currentAccount).cancelLoadFile(document);
        if (location != null) {
            FileLoader.getInstance(currentAccount).cancelLoadFile(location.location, "mp4");
        }
    }

    public void reset() {
        synchronized (sync) {
            canceled = false;
        }
    }

    public TLRPC.Document getDocument() {
        return document;
    }

    public ImageLocation getLocation() {
        return location;
    }

    public Object getParentObject() {
        return document;
    }

    public boolean isPreview() {
        return preview;
    }

    public int getCurrentAccount() {
        return currentAccount;
    }

    public boolean isWaitingForLoad() {
        return waitingForLoad;
    }

    @Override
    public void newDataAvailable() {
        synchronized (sync) {
            if (countDownLatch != null) {
                countDownLatch.countDown();
            }
        }
    }

    public boolean isCanceled() {
        return canceled;
    }
}
