package org.telegram.messenger.video;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.SurfaceTexture;
import android.net.Uri;
import android.os.Build;
import android.view.Surface;
import android.view.SurfaceView;
import android.view.TextureView;

import com.google.android.exoplayer2.ExoPlayer;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.FileStreamLoadOperation;
import org.telegram.messenger.Utilities;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.VideoPlayer;

//used for player in background thread
public class VideoPlayerHolderBase {

    public boolean paused;
    public TLRPC.Document document;
    VideoPlayer videoPlayer;
    Runnable initRunnable;
    public volatile boolean released;
    public boolean firstFrameRendered;

    public float progress;
    int lastState;
    public volatile long currentPosition;
    private int currentAccount;
    long playerDuration;
    boolean audioDisabled;
    public boolean stubAvailable;

    private TextureView textureView;
    private SurfaceView surfaceView;
    private Surface surface;
    public Bitmap playerStubBitmap;
    public Paint playerStubPaint;
    private volatile long mediaGeneration;
    private volatile long playerGeneration;
    private Uri boundMediaUri;
    private long stubCaptureGeneration = 1;
    private long stubCaptureInFlightGeneration = -1;
    private Bitmap reusableStubBitmap;
    public long pendingSeekTo;
    Uri contentUri;

    public VideoPlayerHolderBase() {

    }

    public VideoPlayerHolderBase with(SurfaceView surfaceView) {
        if (this.surfaceView != surfaceView) {
            invalidateStubCapture();
        }
        this.surfaceView = surfaceView;
        this.textureView = null;
        this.surface = null;
        return this;
    }

    public VideoPlayerHolderBase with(TextureView textureView) {
        if (this.textureView != textureView) {
            invalidateStubCapture();
        }
        this.surfaceView = null;
        this.textureView = textureView;
        this.surface = null;
        return this;
    }


    public VideoPlayerHolderBase with(Surface surface) {
        if (this.surface != surface) {
            invalidateStubCapture();
        }
        this.surfaceView = null;
        this.textureView = null;
        this.surface = surface;
        return this;
    }


    final DispatchQueue dispatchQueue = Utilities.getOrCreatePlayerQueue();
    public Uri uri;

    Runnable progressRunnable = new Runnable() {
        @Override
        public void run() {
            if (videoPlayer != null) {
                if (lastState == ExoPlayer.STATE_ENDED) {
                    progress = 1f;
                } else {
                    currentPosition = videoPlayer.getCurrentPosition();
                    playerDuration = videoPlayer.getDuration();
                }
                if (lastState == ExoPlayer.STATE_READY || lastState == ExoPlayer.STATE_BUFFERING) {
                    dispatchQueue.cancelRunnable(progressRunnable);
                    dispatchQueue.postRunnable(progressRunnable, 16);
                }
            }
        }
    };

    long startTime;

    public void preparePlayer(Uri uri, boolean audioDisabled, float speed) {
        bindMedia(uri);
        this.audioDisabled = audioDisabled;
        this.currentAccount = currentAccount;
        this.contentUri = uri;
        paused = true;
        if (initRunnable != null) {
            dispatchQueue.cancelRunnable(initRunnable);
        }
        dispatchQueue.postRunnable(initRunnable = () -> {
            if (released) {
                return;
            }
            ensurePlayerCreated(audioDisabled);
            videoPlayer.setPlaybackSpeed(speed);
            FileLog.d("videoplayerholderbase.preparePlayer(): preparePlayer new player as preload uri=" + uri);
            videoPlayer.preparePlayer(uri, "other", FileLoader.PRIORITY_LOW, 0);
            videoPlayer.setPlayWhenReady(false);
            videoPlayer.setWorkerQueue(dispatchQueue);
        });
    }

    public void start(boolean attach, boolean paused, Uri uri, long position, boolean audioDisabled, float speed) {
        bindMedia(uri);
        startTime = System.currentTimeMillis();
        this.audioDisabled = audioDisabled;
        this.paused = paused;
        this.triesCount = 3;
        if (position > 0) {
            currentPosition = position;
        }
        dispatchQueue.postRunnable(initRunnable = () -> {
            if (released) {
                FileLog.d("videoplayerholderbase returned from start: released");
                return;
            }
            if (videoPlayer == null) {
                ensurePlayerCreated(audioDisabled);
                videoPlayer.setPlaybackSpeed(speed);
                FileLog.d("videoplayerholderbase.start(): preparePlayer new player uri=" + uri);
                videoPlayer.preparePlayer(uri, "other");
                videoPlayer.setWorkerQueue(dispatchQueue);
                if (!paused) {
                    if (surface != null) {
                        videoPlayer.setSurface(surface);
                    } else if (surfaceView != null) {
                        videoPlayer.setSurfaceView(surfaceView);
                    } else {
                        videoPlayer.setTextureView(textureView);
                    }
                    videoPlayer.setPlayWhenReady(true);
                } else if (attach) {
                    if (surface != null) {
                        videoPlayer.setSurface(surface);
                    } else if (surfaceView != null) {
                        videoPlayer.setSurfaceView(surfaceView);
                    } else {
                        videoPlayer.setTextureView(textureView);
                    }
                    videoPlayer.setPlayWhenReady(false);
                }
            } else {
                FileLog.d("videoplayerholderbase.start(): player already exist");
                if (!paused) {
                    if (surface != null) {
                        videoPlayer.setSurface(surface);
                    } else if (surfaceView != null) {
                        videoPlayer.setSurfaceView(surfaceView);
                    } else {
                        videoPlayer.setTextureView(textureView);
                    }
                    videoPlayer.play();
                } else if (attach) {
                    if (surface != null) {
                        videoPlayer.setSurface(surface);
                    } else if (surfaceView != null) {
                        videoPlayer.setSurfaceView(surfaceView);
                    } else {
                        videoPlayer.setTextureView(textureView);
                    }
                    videoPlayer.setPlayWhenReady(false);
                }
            }
            if (position > 0) {
                videoPlayer.seekTo(position);
            }

           // videoPlayer.setVolume(isInSilentMode ? 0 : 1f);
            AndroidUtilities.runOnUIThread(() -> initRunnable = null);
        });
    }

    private boolean allowMultipleInstances;
    public void allowMultipleInstances(boolean allow) {
        this.allowMultipleInstances = allow;
    }

    private volatile int triesCount = 3;

    private void ensurePlayerCreated(boolean audioDisabled) {
        if (videoPlayer != null) {
            videoPlayer.releasePlayer(true);
        }
        final VideoPlayer player = new VideoPlayer(false, audioDisabled);
        final long delegatePlayerGeneration = ++playerGeneration;
        final long delegateMediaGeneration = mediaGeneration;
        videoPlayer = player;
        player.allowMultipleInstances = allowMultipleInstances;
        player.setDelegate(new VideoPlayer.VideoPlayerDelegate() {
            @Override
            public void onStateChanged(boolean playWhenReady, int playbackState) {
                if (released
                        || videoPlayer != player
                        || playerGeneration != delegatePlayerGeneration
                        || mediaGeneration != delegateMediaGeneration) {
                    return;
                }
                lastState = playbackState;
                currentPosition = player.getCurrentPosition();
                playerDuration = player.getDuration();
                if (playbackState == ExoPlayer.STATE_READY || playbackState == ExoPlayer.STATE_BUFFERING) {
                    dispatchQueue.cancelRunnable(progressRunnable);
                    dispatchQueue.postRunnable(progressRunnable);
                } else if (playbackState == ExoPlayer.STATE_ENDED) {
                    if (needRepeat()) {
                        progress = 0;
                        player.seekTo(0);
                        player.play();
                    } else {
                        progress = 1f;
                    }
                }
                VideoPlayerHolderBase.this.onStateChanged(playWhenReady, playbackState);
            }

            @Override
            public void onError(VideoPlayer callbackPlayer, Exception e) {
                if (released
                        || callbackPlayer != player
                        || videoPlayer != player
                        || playerGeneration != delegatePlayerGeneration
                        || mediaGeneration != delegateMediaGeneration) {
                    return;
                }
                FileLog.e(e);
                final long positionMs = player.getCurrentPosition();
                triesCount--;
                if (triesCount > 0) {
                    dispatchQueue.postRunnable(initRunnable = () -> {
                        if (released
                                || videoPlayer != player
                                || playerGeneration != delegatePlayerGeneration
                                || mediaGeneration != delegateMediaGeneration
                                || uri == null) {
                            return;
                        }
                        player.preparePlayer(uri, "other");
                        player.seekTo(positionMs);
                    });
                } else {
                    AndroidUtilities.runOnUIThread(() -> {
                        if (released
                                || videoPlayer != player
                                || playerGeneration != delegatePlayerGeneration
                                || mediaGeneration != delegateMediaGeneration) {
                            return;
                        }
                        if (onErrorListener != null) {
                            onErrorListener.run();
                            onErrorListener = null;
                        }
                    });
                }
            }

            @Override
            public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {
                if (released
                        || videoPlayer != player
                        || playerGeneration != delegatePlayerGeneration
                        || mediaGeneration != delegateMediaGeneration) {
                    return;
                }
                VideoPlayerHolderBase.this.onVideoSizeChanged(width, height, unappliedRotationDegrees, pixelWidthHeightRatio);
            }

            @Override
            public void onRenderedFirstFrame() {
                final long frameToken = VideoPlayerHolderBase.this.getRenderedFrameToken();
                AndroidUtilities.runOnUIThread(() -> {
                    if (released
                            || videoPlayer != player
                            || playerGeneration != delegatePlayerGeneration
                            || mediaGeneration != delegateMediaGeneration
                            || !VideoPlayerHolderBase.this.isRenderedFrameTokenValid(frameToken)) {
                        clearReadyListener(delegatePlayerGeneration, delegateMediaGeneration, frameToken);
                        return;
                    }
                    VideoPlayerHolderBase.this.onRenderedFirstFrame(frameToken);
                    runReadyListener(delegatePlayerGeneration, delegateMediaGeneration, frameToken);
                }, surface != null ? 0 : surfaceView == null ? 16 : 32);
            }
        });
        player.setIsStory();
    }

    protected void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {

    }

    private Runnable onReadyListener;
    private long onReadyPlayerGeneration = -1;
    private long onReadyMediaGeneration = -1;
    private long onReadyFrameToken;

    public void setOnReadyListener(Runnable listener) {
        onReadyListener = listener;
        if (listener == null) {
            onReadyPlayerGeneration = -1;
            onReadyMediaGeneration = -1;
            return;
        }
        onReadyPlayerGeneration = playerGeneration;
        onReadyMediaGeneration = mediaGeneration;
        onReadyFrameToken = getRenderedFrameToken();
    }
    private Runnable onErrorListener;
    public void setOnErrorListener(Runnable listener) {
        onErrorListener = listener;
    }

    public boolean release(Runnable whenReleased) {
        TLRPC.Document document = this.document;
        if (document != null) {
            int priority = FileStreamLoadOperation.getStreamPrioriy(document);
            if (priority != FileLoader.PRIORITY_LOW) {
                FileStreamLoadOperation.setPriorityForDocument(document, FileLoader.PRIORITY_LOW);
                FileLoader.getInstance(currentAccount).changePriority(FileLoader.PRIORITY_LOW, document, null, null, null, null, null);
            }
        }
        released = true;
        invalidateStubCapture();
        setOnReadyListener(null);
        dispatchQueue.cancelRunnable(initRunnable);
        dispatchQueue.cancelRunnable(progressRunnable);
        initRunnable = null;
        dispatchQueue.postRunnable(() -> {
            if (videoPlayer != null) {
                videoPlayer.setSurface(null);
                videoPlayer.setTextureView(null);
                videoPlayer.setSurfaceView(null);
                videoPlayer.releasePlayer(false);
            }
            if (document != null) {
                FileLoader.getInstance(currentAccount).cancelLoadFile(document);
            }
            if (whenReleased != null) {
                AndroidUtilities.runOnUIThread(whenReleased);
            }
            videoPlayer = null;
            dispatchQueue.cancelRunnable(progressRunnable);
        });
        if (playerStubBitmap != null) {
            AndroidUtilities.recycleBitmap(playerStubBitmap);
            playerStubBitmap = null;
        }
        if (reusableStubBitmap != null) {
            AndroidUtilities.recycleBitmap(reusableStubBitmap);
            reusableStubBitmap = null;
        }
        return true;
    }

    public void pause() {
        if (released) {
            return;
        }
        if (paused) {
            return;
        }
        paused = true;
        prepareStub();
        dispatchQueue.postRunnable(() -> {
            if (videoPlayer != null) {
                videoPlayer.pause();
            }
        });
    }

    public void prepareStub() {
        if (surfaceView != null && firstFrameRendered && surfaceView.getHolder().getSurface().isValid()) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                final long generation = stubCaptureGeneration;
                if (stubCaptureInFlightGeneration == generation) {
                    return;
                }
                final long capturedMediaGeneration = mediaGeneration;
                final SurfaceView capturedSurfaceView = surfaceView;
                final Bitmap capturedBitmap;
                if (reusableStubBitmap != null && !reusableStubBitmap.isRecycled()
                        && reusableStubBitmap.getWidth() == 720 && reusableStubBitmap.getHeight() == 1280) {
                    capturedBitmap = reusableStubBitmap;
                    reusableStubBitmap = null;
                } else {
                    capturedBitmap = Bitmap.createBitmap(720, 1280, Bitmap.Config.ARGB_8888);
                }
                stubCaptureInFlightGeneration = generation;
                AndroidUtilities.getBitmapFromSurface(capturedSurfaceView, capturedBitmap, success -> {
                    if (stubCaptureInFlightGeneration == generation) {
                        stubCaptureInFlightGeneration = -1;
                    }
                    if (released
                            || !success
                            || generation != stubCaptureGeneration
                            || capturedMediaGeneration != mediaGeneration
                            || capturedSurfaceView != surfaceView) {
                        releaseStubBitmap(capturedBitmap);
                        return;
                    }
                    if (capturedBitmap.getPixel(0, 0) == Color.TRANSPARENT) {
                        releaseStubBitmap(capturedBitmap);
                        stubAvailable = playerStubBitmap != null;
                        return;
                    }
                    Bitmap oldBitmap = playerStubBitmap;
                    playerStubBitmap = capturedBitmap;
                    if (playerStubPaint == null) {
                        playerStubPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                    }
                    stubAvailable = true;
                    if (oldBitmap != null && oldBitmap != capturedBitmap) {
                        releaseStubBitmap(oldBitmap);
                    }
                });
            } else {
                stubAvailable = playerStubBitmap != null;
            }
        }
    }

    public void setSpeed(float speed) {
        if (released) {
            return;
        }
        dispatchQueue.postRunnable(() -> {
            if (videoPlayer != null) {
                videoPlayer.setPlaybackSpeed(speed);
            }
        });
    }

    public void play() {
        if (released) {
            return;
        }
        if (!paused) {
            return;
        }
        paused = false;
        dispatchQueue.postRunnable(() -> {
            if (videoPlayer != null) {
                if (surface != null) {
                    videoPlayer.setSurface(surface);
                } else if (surfaceView != null) {
                    videoPlayer.setSurfaceView(surfaceView);
                } else {
                    videoPlayer.setTextureView(textureView);
                }
                if (pendingSeekTo > 0) {
                    videoPlayer.seekTo(pendingSeekTo);
                    pendingSeekTo = 0;
                }
                videoPlayer.setPlayWhenReady(true);
            }
        });
    }

    public void play(float speed) {
        if (released) {
            return;
        }
        if (!paused) {
            return;
        }
        paused = false;
        dispatchQueue.postRunnable(() -> {
            if (videoPlayer != null) {
                if (surface != null) {
                    videoPlayer.setSurface(surface);
                } else if (surfaceView != null) {
                    videoPlayer.setSurfaceView(surfaceView);
                } else {
                    videoPlayer.setTextureView(textureView);
                }
                if (pendingSeekTo > 0) {
                    videoPlayer.seekTo(pendingSeekTo);
                    pendingSeekTo = 0;
                }
                videoPlayer.setPlaybackSpeed(speed);
                videoPlayer.setPlayWhenReady(true);
            }
        });
    }

    public void setAudioEnabled(boolean enabled, boolean prepared) {
        boolean disabled = !enabled;
        if (audioDisabled == disabled) {
            return;
        }
        audioDisabled = disabled;
        this.triesCount = 3;
        dispatchQueue.postRunnable(() -> {
            if (videoPlayer == null) {
                return;
            }
            boolean playing = videoPlayer.isPlaying();
            if (enabled && !videoPlayer.createdWithAudioTrack()) {
                //release and create new with audio track
                videoPlayer.pause();
                long position = videoPlayer.getCurrentPosition();
                videoPlayer.releasePlayer(false);
                videoPlayer = null;
                ensurePlayerCreated(audioDisabled);
                final Uri uri = this.uri == null ? contentUri : this.uri;
                FileLog.d("videoplayerholderbase.setAudioEnabled(): repreparePlayer as audio track is enabled back uri=" + uri);
                videoPlayer.preparePlayer(uri, "other");
                videoPlayer.setWorkerQueue(dispatchQueue);
                if (!prepared) {
                    if (surface != null) {
                        videoPlayer.setSurface(surface);
                    } else if (surfaceView != null) {
                        videoPlayer.setSurfaceView(surfaceView);
                    } else  {
                        videoPlayer.setTextureView(textureView);
                    }
                }
                //    videoPlayer.setTextureView(textureView);
                videoPlayer.seekTo(position + 50);
                if (playing && !prepared) {
                    videoPlayer.setPlayWhenReady(true);
                    videoPlayer.play();
                } else {
                    videoPlayer.setPlayWhenReady(false);
                    videoPlayer.pause();
                }
            } else {
                videoPlayer.setVolume(enabled ? 1f : 0);
            }
        });
    }

    public float getPlaybackProgress(long totalDuration) {
        if (lastState == ExoPlayer.STATE_ENDED) {
            progress = 1f;
        } else {
            float localProgress;
            if (totalDuration != 0) {
                localProgress = currentPosition / (float) totalDuration;
            } else {
                localProgress = currentPosition / (float) playerDuration;
            }
//            if (localProgress < progress) {
//                return progress;
//            }
            progress = localProgress;
            if (!seeking) {
                currentSeek = progress;
                lastSeek = currentPosition;
            }
        }
        return progress;
    }

    public void loopBack() {
        progress = 0;
        lastState = ExoPlayer.STATE_IDLE;
        dispatchQueue.postRunnable(() -> {
            if (videoPlayer != null) {
                videoPlayer.seekTo(0);
            }
            progress = 0;
            currentPosition = 0;
        });
    }

    public void setVolume(float v) {
        dispatchQueue.postRunnable(() -> {
            if (videoPlayer != null) {
                videoPlayer.setVolume(v);
            }
        });
    }

    public boolean isBuffering() {
        return !released && lastState == ExoPlayer.STATE_BUFFERING;
    }

    public long getCurrentPosition() {
        return currentPosition;
    }

    public long getDuration() {
        return playerDuration;
    }

    public boolean isPlaying() {
        return !paused;
    }

    public void onRenderedFirstFrame() {

    }

    /**
     * Lets holders reject a decoder callback which was queued for an older
     * binding before it reaches the UI thread.
     */
    protected long getRenderedFrameToken() {
        return 0L;
    }

    protected boolean isRenderedFrameTokenValid(long frameToken) {
        return true;
    }

    public void onRenderedFirstFrame(long frameToken) {
        onRenderedFirstFrame();
    }

    private void bindMedia(Uri mediaUri) {
        boolean sameMedia = boundMediaUri == null ? mediaUri == null : boundMediaUri.equals(mediaUri);
        if (sameMedia) {
            return;
        }
        boundMediaUri = mediaUri;
        mediaGeneration++;
        invalidateStubCapture();
        stubAvailable = false;
        setOnReadyListener(null);
    }

    private void invalidateStubCapture() {
        stubCaptureGeneration++;
    }

    private void releaseStubBitmap(Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            return;
        }
        if (!released && reusableStubBitmap == null) {
            reusableStubBitmap = bitmap;
        } else {
            AndroidUtilities.recycleBitmap(bitmap);
        }
    }

    private void runReadyListener(long callbackPlayerGeneration, long callbackMediaGeneration, long frameToken) {
        if (onReadyListener == null) {
            return;
        }
        if (onReadyPlayerGeneration != callbackPlayerGeneration
                || onReadyMediaGeneration != callbackMediaGeneration
                || onReadyFrameToken != frameToken) {
            if (onReadyMediaGeneration != mediaGeneration
                    || !isRenderedFrameTokenValid(onReadyFrameToken)) {
                setOnReadyListener(null);
            }
            return;
        }
        Runnable listener = onReadyListener;
        setOnReadyListener(null);
        listener.run();
    }

    private void clearReadyListener(long callbackPlayerGeneration, long callbackMediaGeneration, long frameToken) {
        if (onReadyListener != null
                && onReadyPlayerGeneration == callbackPlayerGeneration
                && onReadyMediaGeneration == callbackMediaGeneration
                && onReadyFrameToken == frameToken) {
            setOnReadyListener(null);
        }
    }

    public void onStateChanged(boolean playWhenReady, int playbackState) {

    }

    public boolean needRepeat() {
        return false;
    }

    public void seekTo(long position) {
        dispatchQueue.postRunnable(() -> {
            if (videoPlayer == null) {
                pendingSeekTo = position;
                return;
            }
            videoPlayer.seekTo(position);
        });
    }

    public void seekTo(long position, boolean fast, Runnable done) {
        dispatchQueue.postRunnable(() -> {
            if (videoPlayer == null) {
                pendingSeekTo = position;
                return;
            }
            videoPlayer.seekTo(position, fast, done);
        });
    }

    public Uri getCurrentUri() {
        return contentUri;
    }

    private Runnable onSeekUpdate;
    public void setOnSeekUpdate(Runnable onSeekUpdate) {
        this.onSeekUpdate = onSeekUpdate;
    }


    private volatile boolean firstSeek = true;
    private volatile long lastSeek = -1;
    private long lastBetterSeek = -1;
    public float currentSeek = 0;
    public volatile float currentSeekThread = 0;
    private volatile long duration;

    private final Runnable betterSeek = () -> {
        if (videoPlayer != null) {
//            videoPlayer.seekTo(lastBetterSeek, false);
        }
    };

    private final Runnable updateSeek = () -> {
        if (videoPlayer == null) {
            return;
        }
        long position = (long) (currentSeekThread * duration);
        if (lastSeek <= -1) {
            lastSeek = position;
        }
        if (Math.abs(position - lastSeek) >= (firstSeek ? 350 : 40)) {
            firstSeek = false;
            lastBetterSeek = position;
            dispatchQueue.cancelRunnable(betterSeek);
            dispatchQueue.postRunnable(betterSeek, 300);
            videoPlayer.seekTo(lastSeek = position, true);
        }
    };

    private volatile boolean seeking;
    public void setSeeking(boolean seeking) {
        if (seeking && !this.seeking) {
            firstSeek = true;
        }
        this.seeking = seeking;
        if (!seeking) {
            dispatchQueue.cancelRunnable(betterSeek);
        }
    }

    public float seek(float delta, final long duration) {
        if (videoPlayer == null) {
            return currentSeek;
        }
        this.duration = duration;
        currentSeek = Utilities.clamp(currentSeek + delta, 1, 0);
        currentSeekThread = currentSeek;
        dispatchQueue.cancelRunnable(updateSeek);
        dispatchQueue.postRunnable(updateSeek);
        return currentSeek;
    }
}
