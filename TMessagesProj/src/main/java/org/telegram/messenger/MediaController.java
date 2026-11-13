/* Modifications Copyright (C) 2026 Ettacent */

/*
 * This is the source code of Telegram for Android v. 1.3.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.messenger;

import static org.telegram.ui.PhotoViewer.getTempFileAbsolutePath;

import android.Manifest;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.DownloadManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothProfile;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.BitmapDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.system.Os;
import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.LruCache;
import android.util.Pair;
import android.util.SparseArray;
import android.view.HapticFeedbackConstants;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.MimeTypeMap;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.Nullable;
import androidx.annotation.OptIn;
import androidx.exifinterface.media.ExifInterface;

import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.common.Player;
import androidx.media3.extractor.jpeg.MotionPhotoDescription;
import androidx.media3.extractor.jpeg.XmpMotionPhotoDescriptionParser;

import org.telegram.ui.AspectRatioFrameLayout;
import com.google.android.gms.cast.MediaMetadata;
import com.google.android.gms.common.images.WebImage;

import org.telegram.messenger.audioinfo.AudioInfo;
import org.telegram.messenger.chromecast.ChromecastController;
import org.telegram.messenger.chromecast.ChromecastFileServer;
import org.telegram.messenger.chromecast.ChromecastMedia;
import org.telegram.messenger.chromecast.ChromecastMediaVariations;
import org.telegram.messenger.video.MediaCodecVideoConvertor;
import org.telegram.messenger.voip.VoIPService;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.InputSerializedData;
import org.telegram.tgnet.OutputSerializedData;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_stories;
import org.telegram.utils.recorder.audio.AudioRecordingSession;
import org.telegram.utils.settings.SharedSettings;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Adapters.FiltersView;
import org.telegram.ui.CastSync;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.EmbedBottomSheet;
import org.telegram.ui.Components.PermissionRequest;
import org.telegram.ui.Components.PhotoFilterView;
import org.telegram.ui.Components.PipRoundVideoView;
import org.telegram.ui.Components.Reactions.ReactionsLayoutInBubble;
import org.telegram.ui.Components.VideoPlayer;
import org.telegram.ui.LaunchActivity;
import org.telegram.ui.PhotoViewer;
import org.telegram.ui.Stories.DarkThemeResourceProvider;
import org.telegram.ui.Stories.recorder.StoryEntry;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

import java.util.concurrent.atomic.AtomicLong;
public class MediaController implements AudioManager.OnAudioFocusChangeListener, NotificationCenter.NotificationCenterDelegate, SensorEventListener {

    private native int startRecord(String path, int sampleRate);

    private native int writeFrame(ByteBuffer frame, int len);

    private native void stopRecord();

    public static native boolean cropOpusFile(String source, String destination, long startMs, long endMs);

    public static native boolean joinOpusFiles(String file1, String file2, String dest);

    public static native int isOpusFile(String path);

    public static native byte[] getWaveform(String path);

    public native byte[] getWaveform2(short[] array, int length);

    public boolean isBuffering() {
        if (audioPlayer != null) {
            return audioPlayer.isBuffering();
        }
        return false;
    }

    public VideoConvertMessage getCurrentForegroundConverMessage() {
        return currentForegroundConvertingVideo;
    }

    private static class AudioBuffer {
        public AudioBuffer(int capacity) {
            buffer = ByteBuffer.allocateDirect(capacity);
            bufferBytes = new byte[capacity];
        }

        ByteBuffer buffer;
        byte[] bufferBytes;
        int size;
        int finished;
        long pcmOffset;
    }

    private static final String[] projectionPhotos = {
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.DATA,
            Build.VERSION.SDK_INT > 28 ? MediaStore.Images.Media.DATE_MODIFIED : MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.ORIENTATION,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.SIZE
    };

    private static final String[] projectionVideo = {
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.BUCKET_ID,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Video.Media.DATA,
            Build.VERSION.SDK_INT > 28 ? MediaStore.Images.Media.DATE_MODIFIED : MediaStore.Video.Media.DATE_TAKEN,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            MediaStore.Video.Media.SIZE
    };

    public static class AudioEntry {
        public long id;
        public String author;
        public String title;
        public String genre;
        public int duration;
        public String path;
        public MessageObject messageObject;
    }

    public static class AlbumEntry {
        public int bucketId;
        public boolean videoOnly;
        public String bucketName;
        public PhotoEntry coverPhoto;
        public ArrayList<PhotoEntry> photos = new ArrayList<>();
        public SparseArray<PhotoEntry> photosByIds = new SparseArray<>();

        public AlbumEntry(int bucketId, String bucketName, PhotoEntry coverPhoto) {
            this.bucketId = bucketId;
            this.bucketName = bucketName;
            this.coverPhoto = coverPhoto;
        }

        public void addPhoto(PhotoEntry photoEntry) {
            photos.add(photoEntry);
            photosByIds.put(photoEntry.imageId, photoEntry);
        }
    }

    public static class SavedFilterState {
        public float enhanceValue;
        public float softenSkinValue;
        public float exposureValue;
        public float contrastValue;
        public float warmthValue;
        public float saturationValue;
        public float fadeValue;
        public int tintShadowsColor;
        public int tintHighlightsColor;
        public float highlightsValue;
        public float shadowsValue;
        public float vignetteValue;
        public float grainValue;
        public int blurType;
        public float sharpenValue;
        public PhotoFilterView.CurvesToolValue curvesToolValue = new PhotoFilterView.CurvesToolValue();
        public float blurExcludeSize;
        public PointF blurExcludePoint;
        public float blurExcludeBlurSize;
        public float blurAngle;

        public void serializeToStream(OutputSerializedData stream) {
            stream.writeFloat(enhanceValue);
            stream.writeFloat(softenSkinValue);
            stream.writeFloat(exposureValue);
            stream.writeFloat(contrastValue);
            stream.writeFloat(warmthValue);
            stream.writeFloat(saturationValue);
            stream.writeFloat(fadeValue);
            stream.writeInt32(tintShadowsColor);
            stream.writeInt32(tintHighlightsColor);
            stream.writeFloat(highlightsValue);
            stream.writeFloat(shadowsValue);
            stream.writeFloat(vignetteValue);
            stream.writeFloat(grainValue);
            stream.writeInt32(blurType);
            stream.writeFloat(sharpenValue);
            curvesToolValue.serializeToStream(stream);
            stream.writeFloat(blurExcludeSize);
            if (blurExcludePoint == null) {
                stream.writeInt32(0x56730bcc);
            } else {
                stream.writeInt32(0xDEADBEEF);
                stream.writeFloat(blurExcludePoint.x);
                stream.writeFloat(blurExcludePoint.y);
            }
            stream.writeFloat(blurExcludeBlurSize);
            stream.writeFloat(blurAngle);
        }

        public void readParams(InputSerializedData stream, boolean exception) {
            enhanceValue = stream.readFloat(exception);
            softenSkinValue = stream.readFloat(exception);
            exposureValue = stream.readFloat(exception);
            contrastValue = stream.readFloat(exception);
            warmthValue = stream.readFloat(exception);
            saturationValue = stream.readFloat(exception);
            fadeValue = stream.readFloat(exception);
            tintShadowsColor = stream.readInt32(exception);
            tintHighlightsColor = stream.readInt32(exception);
            highlightsValue = stream.readFloat(exception);
            shadowsValue = stream.readFloat(exception);
            vignetteValue = stream.readFloat(exception);
            grainValue = stream.readFloat(exception);
            blurType = stream.readInt32(exception);
            sharpenValue = stream.readFloat(exception);
            curvesToolValue.readParams(stream, exception);
            blurExcludeSize = stream.readFloat(exception);
            int magic = stream.readInt32(exception);
            if (magic == 0x56730bcc) {
                blurExcludePoint = null;
            } else {
                if (blurExcludePoint == null) {
                    blurExcludePoint = new PointF();
                }
                blurExcludePoint.x = stream.readFloat(exception);
                blurExcludePoint.y = stream.readFloat(exception);
            }
            blurExcludeBlurSize = stream.readFloat(exception);
            blurAngle = stream.readFloat(exception);
        }

        public boolean isEmpty() {
            return (
                Math.abs(enhanceValue) < 0.1f &&
                Math.abs(softenSkinValue) < 0.1f &&
                Math.abs(exposureValue) < 0.1f &&
                Math.abs(contrastValue) < 0.1f &&
                Math.abs(warmthValue) < 0.1f &&
                Math.abs(saturationValue) < 0.1f &&
                Math.abs(fadeValue) < 0.1f &&
                tintShadowsColor == 0 &&
                tintHighlightsColor == 0 &&
                Math.abs(highlightsValue) < 0.1f &&
                Math.abs(shadowsValue) < 0.1f &&
                Math.abs(vignetteValue) < 0.1f &&
                Math.abs(grainValue) < 0.1f &&
                blurType == 0 &&
                Math.abs(sharpenValue) < 0.1f
            );
        }
    }

    public static class CropState extends TLObject {
        public static final int constructor = 0x44a3abcd;

        public float cropPx;
        public float cropPy;
        public float cropScale = 1;
        public float cropRotate;
        public float cropPw = 1;
        public float cropPh = 1;
        public int transformWidth;
        public int transformHeight;
        public int transformRotation;
        public boolean mirrored;

        public float stateScale;
        public float scale;
        public Matrix matrix;
        public int width;
        public int height;
        public boolean freeform;
        public float lockedAspectRatio;
        public int orientation;

        public Matrix useMatrix;

        public boolean initied;

        @Override
        public CropState clone() {
            CropState cloned = new CropState();

            cloned.cropPx = this.cropPx;
            cloned.cropPy = this.cropPy;
            cloned.cropScale = this.cropScale;
            cloned.cropRotate = this.cropRotate;
            cloned.cropPw = this.cropPw;
            cloned.cropPh = this.cropPh;
            cloned.transformWidth = this.transformWidth;
            cloned.transformHeight = this.transformHeight;
            cloned.transformRotation = this.transformRotation;
            cloned.mirrored = this.mirrored;

            cloned.stateScale = this.stateScale;
            cloned.scale = this.scale;
            cloned.matrix = this.matrix;
            cloned.width = this.width;
            cloned.height = this.height;
            cloned.freeform = this.freeform;
            cloned.lockedAspectRatio = this.lockedAspectRatio;
            cloned.orientation = this.orientation;

            cloned.initied = this.initied;
            cloned.useMatrix = this.useMatrix;
            return cloned;
        }

        public boolean isEmpty() {
            return (matrix == null || matrix.isIdentity()) && (useMatrix == null || useMatrix.isIdentity()) && cropPw == 1 && cropPh == 1 &&
                    cropScale == 1 && cropRotate == 0 && transformWidth == 0 && transformHeight == 0 &&
                    transformRotation == 0 && !mirrored && stateScale == 0 && scale == 0 && width == 0 && height == 0 && !freeform && lockedAspectRatio == 0;

        }

        @Override
        public void readParams(InputSerializedData stream, boolean exception) {
            cropPx = stream.readFloat(exception);
            cropPy = stream.readFloat(exception);
            cropScale = stream.readFloat(exception);
            cropRotate = stream.readFloat(exception);
            cropPw = stream.readFloat(exception);
            cropPh = stream.readFloat(exception);
            transformWidth = stream.readInt32(exception);
            transformHeight = stream.readInt32(exception);
            transformRotation = stream.readInt32(exception);
            mirrored = stream.readBool(exception);
            stateScale = stream.readFloat(exception);
            scale = stream.readFloat(exception);
            final float[] values = new float[9];
            for (int i = 0; i < values.length; ++i) {
                values[i] = stream.readFloat(exception);
            }
            matrix = new Matrix();
            matrix.setValues(values);
            width = stream.readInt32(exception);
            height = stream.readInt32(exception);
            freeform = stream.readBool(exception);
            lockedAspectRatio = stream.readFloat(exception);
            int magic = stream.readInt32(exception);
            if (magic == 0xaa23a61) {
                for (int i = 0; i < values.length; ++i) {
                    values[i] = stream.readFloat(exception);
                }
                useMatrix = new Matrix();
                useMatrix.setValues(values);
            }
            initied = stream.readBool(exception);
            orientation = stream.readInt32(exception);
        }

        @Override
        public void serializeToStream(OutputSerializedData stream) {
            stream.writeInt32(constructor);
            stream.writeFloat(cropPx);
            stream.writeFloat(cropPy);
            stream.writeFloat(cropScale);
            stream.writeFloat(cropRotate);
            stream.writeFloat(cropPw);
            stream.writeFloat(cropPh);
            stream.writeInt32(transformWidth);
            stream.writeInt32(transformHeight);
            stream.writeInt32(transformRotation);
            stream.writeBool(mirrored);
            stream.writeFloat(stateScale);
            stream.writeFloat(scale);
            final float[] values = new float[9];
            if (matrix != null) {
                matrix.getValues(values);
            } else for (int i = 0; i < values.length; ++i) {
                values[i] = 0.0f;
            }
            for (int i = 0; i < values.length; ++i) {
                stream.writeFloat(values[i]);
            }
            stream.writeInt32(width);
            stream.writeInt32(height);
            stream.writeBool(freeform);
            stream.writeFloat(lockedAspectRatio);
            if (useMatrix == null) {
                stream.writeInt32(TLRPC.TL_null.constructor);
            } else {
                stream.writeInt32(0xaa23a61);
                useMatrix.getValues(values);
                for (int i = 0; i < values.length; ++i) {
                    stream.writeFloat(values[i]);
                }
            }
            stream.writeBool(initied);
            stream.writeInt32(orientation);
        }
    }

    public static class MediaEditState {

        public CharSequence caption;

        public boolean isVideo;
        public String thumbPath;
        public long coverSavedPosition;
        public TLRPC.Photo coverPhoto;
        public Object coverPhotoParentObject;
        public String coverPath;
        public String imagePath;
        public String filterPath;
        public String paintPath;
        public String croppedPaintPath;
        public String fullPaintPath;

        public boolean isLivePhoto() {
            return this instanceof PhotoEntry && ((PhotoEntry) this).isLivePhoto();
        }

        public long livePhotoVideoOffset;

        public ArrayList<TLRPC.MessageEntity> entities;
        public SavedFilterState savedFilterState;
        public ArrayList<VideoEditedInfo.MediaEntity> mediaEntities;
        public ArrayList<VideoEditedInfo.MediaEntity> croppedMediaEntities;
        public ArrayList<TLRPC.InputDocument> stickers;
        public VideoEditedInfo editedInfo;
        public long averageDuration;
        public boolean isFiltered;
        public boolean isPainted;
        public boolean isCropped;
        public int ttl;
        public long effectId;
        @Nullable
        public Boolean highQuality;
        /** Request-scoped override used by PhotoViewer's custom-sticker send path. */
        public boolean forceAsSticker;
        public boolean isHighQuality() {
            if (highQuality == null) {
                if (!forceAsSticker && app.nimarkogram.messenger.AppConfig.largePhotos) {
                    return true;
                }
                return SharedConfig.photoHighQualityDefault;
            }
            return highQuality;
        }

        public CropState cropState;

        public boolean hasVideoEditsLostAsDocument() {
            if (!isVideo) {
                return false;
            }
            if (editedInfo != null && (editedInfo.muted || editedInfo.startTime > 0 || editedInfo.endTime > 0)) {
                return true;
            }
            return cropState != null && !cropState.isEmpty()
                || savedFilterState != null
                || paintPath != null
                || mediaEntities != null && !mediaEntities.isEmpty()
                || coverPath != null;
        }
        public String getPath() {
            return null;
        }

        public void reset() {
            caption = null;
            coverPath = null;
            coverPhoto = null;
            coverPhotoParentObject = null;
            thumbPath = null;
            filterPath = null;
            imagePath = null;
            paintPath = null;
            fullPaintPath = null;
            croppedPaintPath = null;
            isFiltered = false;
            isPainted = false;
            isCropped = false;
            ttl = 0;
            mediaEntities = null;
            editedInfo = null;
            entities = null;
            savedFilterState = null;
            stickers = null;
            cropState = null;
            highQuality = null;
            forceAsSticker = false;
        }

        public void copyFrom(MediaEditState state) {
            caption = state.caption;

            thumbPath = state.thumbPath;
            imagePath = state.imagePath;
            filterPath = state.filterPath;
            paintPath = state.paintPath;
            croppedPaintPath = state.croppedPaintPath;
            fullPaintPath = state.fullPaintPath;

            entities = state.entities;
            savedFilterState = state.savedFilterState;
            mediaEntities = state.mediaEntities;
            croppedMediaEntities = state.croppedMediaEntities;
            stickers = state.stickers;
            editedInfo = state.editedInfo;
            averageDuration = state.averageDuration;
            isFiltered = state.isFiltered;
            isPainted = state.isPainted;
            isCropped = state.isCropped;
            livePhotoVideoOffset = state.livePhotoVideoOffset;
            ttl = state.ttl;

            cropState = state.cropState;
            coverPath = state.coverPath;
            highQuality = state.highQuality;
            forceAsSticker = state.forceAsSticker;
        }
    }

    public static class PhotoEntry extends MediaEditState {
        public int bucketId;
        public int imageId;
        public long dateTaken;
        public int duration;
        public int width;
        public int height;
        public long size;
        public String path;
        public int orientation;
        public int invert;
        public boolean isMuted;
        public boolean canDeleteAfter;
        public boolean hasSpoiler;
        public long starsAmount;
        public String emoji;
        public Boolean discardLivePhoto;

        public boolean isUnalivePhoto() {
            if (discardLivePhoto == null)
                return !SharedConfig.photoLiveDefault;
            return discardLivePhoto;
        }

        public long livePhotoTimestampUs;

        public int videoOrientation = -1;

        public boolean isChatPreviewSpoilerRevealed;
        public boolean isAttachSpoilerRevealed;
        public TLRPC.VideoSize emojiMarkup;

        public int gradientTopColor, gradientBottomColor;

        public BitmapDrawable thumb;
        private volatile boolean parsedXmp;
        public boolean isLivePhoto;

        private boolean loadingLivePhoto;
        private ArrayList<Runnable> onLivePhotoLoaded;

        private static DispatchQueue livePhotoQueue;
        @Override
        public boolean isLivePhoto() {
            if (parsedXmp) return isLivePhoto;
            synchronized (this) {
                if (!parsedXmp) {
                    if (!isVideo) {
                        parseXmp();
                    }
            parsedXmp = true;
                }
            }
            return isLivePhoto;
        }

        public boolean isLivePhotoLoaded() {
            return parsedXmp || isVideo && !loadingLivePhoto;
        }

        public boolean loadLivePhoto(Runnable onLoaded) {
            if (isLivePhotoLoaded()) return true;
            if (onLoaded != null) {
                if (onLivePhotoLoaded == null) {
                    onLivePhotoLoaded = new ArrayList<>();
                }
                if (!onLivePhotoLoaded.contains(onLoaded)) {
                    onLivePhotoLoaded.add(onLoaded);
                }
            }
            if (!loadingLivePhoto) {
                loadingLivePhoto = true;
                if (livePhotoQueue == null) {
                    livePhotoQueue = new DispatchQueue("livePhotoQueue");
                }
                livePhotoQueue.postRunnable(() -> {
                    isLivePhoto();
                    AndroidUtilities.runOnUIThread(() -> {
                        loadingLivePhoto = false;
                        final ArrayList<Runnable> callbacks = onLivePhotoLoaded;
                        onLivePhotoLoaded = null;
                        if (callbacks != null) {
                            for (int i = 0; i < callbacks.size(); ++i) {
                                callbacks.get(i).run();
                            }
                        }
                    });
                });
            }
            return false;
        }

        private void parseXmp() {
            final long start = System.currentTimeMillis();
            HeifMotionPhoto heif = null;
            try {
                heif = HeifMotionPhoto.parse(new File(path));
            } catch (Exception e) {
                FileLog.e(e);
            }
            if (heif != null) {
                if (heif.isMotionPhoto()) {
                    isVideo = true;
                    isLivePhoto = true;
                    livePhotoVideoOffset = heif.videoOffset;
                    livePhotoTimestampUs = heif.timestampUs;
                }
            } else try {
                final ExifInterface ei = new ExifInterface(new File(path));
                final String xmp = ei.getAttribute(ExifInterface.TAG_XMP);
                if (xmp != null) {
                    final MotionPhotoDescription motionPhoto = XmpMotionPhotoDescriptionParser.parse(xmp);
                    if (motionPhoto != null) {
                        MotionPhotoDescription.ContainerItem photo = null, video = null;
                        for (int i = 0; i < motionPhoto.items.size(); ++i) {
                            final MotionPhotoDescription.ContainerItem item = motionPhoto.items.get(i);
                            if ("Primary".equalsIgnoreCase(item.semantic)) {
                                photo = item;
                            } else if ("MotionPhoto".equalsIgnoreCase(item.semantic)) {
                                video = item;
                            }
                        }
                        if (photo != null && video != null && video.length > 0) {
                            try {
                                final File wholeFile = new File(path);
                                final long videoStart = wholeFile.length() - video.length;

                                isVideo = true;
                                isLivePhoto = true;

                                livePhotoVideoOffset = videoStart;
                                livePhotoTimestampUs = motionPhoto.photoPresentationTimestampUs;
                            } catch (Exception e) {
                                FileLog.e(e);
                            }
                        }
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
                isLivePhoto = false;
            }
            FileLog.d("parsed isLivePhoto()="+isLivePhoto+" in " + (System.currentTimeMillis() - start) + "ms");
        }

        public PhotoEntry(int bucketId, int imageId, long dateTaken, String path, int orientationOrDuration, boolean isVideo, int width, int height, long size) {
            this.bucketId = bucketId;
            this.imageId = imageId;
            this.dateTaken = dateTaken;
            this.path = path;
            this.width = width;
            this.height = height;
            this.size = size;
            if (isVideo) {
                this.duration = orientationOrDuration;
            } else {
                this.orientation = orientationOrDuration;
            }
            this.isVideo = isVideo;
        }

        public PhotoEntry(int bucketId, int imageId, long dateTaken, String path, int orientation, int duration, boolean isVideo, int width, int height, long size) {
            this.bucketId = bucketId;
            this.imageId = imageId;
            this.dateTaken = dateTaken;
            this.path = path;
            this.width = width;
            this.height = height;
            this.size = size;
            this.duration = duration;
            this.orientation = orientation;
            this.isVideo = isVideo;
        }

        public PhotoEntry setOrientation(Pair<Integer, Integer> rotationAndInvert) {
            this.orientation = rotationAndInvert.first;
            this.invert = rotationAndInvert.second;
            return this;
        }

        public PhotoEntry setOrientation(int rotation, int invert) {
            this.orientation = rotation;
            this.invert = invert;
            return this;
        }

        @Override
        public void copyFrom(MediaEditState state) {
            super.copyFrom(state);
            this.hasSpoiler = state instanceof PhotoEntry && ((PhotoEntry) state).hasSpoiler;
            this.starsAmount = state instanceof PhotoEntry ? ((PhotoEntry) state).starsAmount : 0;
            this.parsedXmp = state instanceof PhotoEntry && ((PhotoEntry) state).parsedXmp;
            this.isLivePhoto = state instanceof PhotoEntry && ((PhotoEntry) state).isLivePhoto;
            this.livePhotoVideoOffset = state instanceof PhotoEntry ? ((PhotoEntry) state).livePhotoVideoOffset : 0;
            this.livePhotoTimestampUs = state instanceof PhotoEntry ? ((PhotoEntry) state).livePhotoTimestampUs : 0;
        }

        public PhotoEntry clone() {
            PhotoEntry photoEntry = new PhotoEntry(bucketId, imageId, dateTaken, path, orientation, duration, isVideo, width, height, size);
            photoEntry.invert = invert;
            photoEntry.isMuted = isMuted;
            photoEntry.canDeleteAfter = canDeleteAfter;
            photoEntry.hasSpoiler = hasSpoiler;
            photoEntry.starsAmount = starsAmount;
            photoEntry.isChatPreviewSpoilerRevealed = isChatPreviewSpoilerRevealed;
            photoEntry.isAttachSpoilerRevealed = isAttachSpoilerRevealed;
            photoEntry.emojiMarkup = emojiMarkup;
            photoEntry.gradientTopColor = gradientTopColor;
            photoEntry.gradientBottomColor = gradientBottomColor;
            photoEntry.discardLivePhoto = discardLivePhoto;
            photoEntry.copyFrom(this);
            return photoEntry;
        }

        @Override
        public String getPath() {
            return path;
        }

        @Override
        public void reset() {
            if (isVideo) {
                if (filterPath != null) {
                    new File(filterPath).delete();
                    filterPath = null;
                }
            }
            hasSpoiler = false;
            starsAmount = 0;
            super.reset();
        }

        public void deleteAll() {
            if (path != null) {
                try {
                    new File(path).delete();
                } catch (Exception ignore) {}
            }
            if (fullPaintPath != null) {
                try {
                    new File(fullPaintPath).delete();
                } catch (Exception ignore) {}
            }
            if (paintPath != null) {
                try {
                    new File(paintPath).delete();
                } catch (Exception ignore) {}
            }
            if (imagePath != null) {
                try {
                    new File(imagePath).delete();
                } catch (Exception ignore) {}
            }
            if (filterPath != null) {
                try {
                    new File(filterPath).delete();
                } catch (Exception ignore) {}
            }
            if (croppedPaintPath != null) {
                try {
                    new File(croppedPaintPath).delete();
                } catch (Exception ignore) {}
            }
        }

        public void rebuildPhoto(boolean highQuality) {
            Bitmap bitmap = null;
            Bitmap b = null;
            Bitmap paintSource = null;
            Bitmap paintBitmap = null;
            Bitmap resultBitmap = null;
            String outputPath = null;
            try {
                final Pair<Integer, Integer> orientation = AndroidUtilities.getImageOrientation(filterPath != null ? filterPath : path);
                final Bitmap.CompressFormat compressFormat = Bitmap.CompressFormat.JPEG;
                bitmap = StoryEntry.getScaledBitmap(opts -> BitmapFactory.decodeFile(filterPath != null ? filterPath : path, opts), AndroidUtilities.getPhotoSize(highQuality), AndroidUtilities.getPhotoSize(highQuality), false, true);
                if (bitmap == null) return;

                if (cropState != null) {
                    b = PhotoViewer.createCroppedBitmap(bitmap, cropState, new int[] { orientation.first, orientation.second }, true);
                } else {
                    if (orientation.first != 0) {
                        Matrix matrix = new Matrix();
                        matrix.postRotate(orientation.first);
                        if (orientation.second == 1) {
                            matrix.postScale(-1, 1);
                        } else if (orientation.second == 2) {
                            matrix.postScale(1, -1);
                        }
                        b = Bitmaps.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                    } else {
                        b = bitmap;
                    }
                }
                if (b == null) return;
                if (b != bitmap && !bitmap.isRecycled()) bitmap.recycle();
                if (fullPaintPath == null) {
                    TLRPC.PhotoSize size = ImageLoader.scaleAndSaveImage(b, compressFormat, AndroidUtilities.getPhotoSize(highQuality), AndroidUtilities.getPhotoSize(highQuality), highQuality ? 99 : 87, false, 101, 101);
                    if (size == null) return;
                    outputPath = FileLoader.getInstance(UserConfig.selectedAccount).getPathToAttach(size, true).toString();
                } else {
                    paintSource = BitmapFactory.decodeFile(fullPaintPath);
                    if (paintSource == null) return;
                    if (cropState != null) {
                        paintBitmap = PhotoViewer.createCroppedBitmap(paintSource, cropState, null, false);
                    } else {
                        paintBitmap = paintSource;
                    }
                    if (paintBitmap == null) return;
                    if (paintBitmap != paintSource && !paintSource.isRecycled()) paintSource.recycle();
                    final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                    resultBitmap = Bitmap.createBitmap(b.getWidth(), b.getHeight(), Bitmap.Config.ARGB_8888);
                    final Canvas canvas = new Canvas(resultBitmap);

                    canvas.drawBitmap(b, 0, 0, bitmapPaint);
                    canvas.scale(b.getWidth() / (float) paintBitmap.getWidth(), b.getHeight() / (float) paintBitmap.getHeight());
                    canvas.drawBitmap(paintBitmap, 0, 0, bitmapPaint);

                    outputPath = getTempFileAbsolutePath();
                    try (FileOutputStream stream = new FileOutputStream(outputPath)) {
                        if (!resultBitmap.compress(Bitmap.CompressFormat.JPEG, highQuality ? 99 : 87, stream)) return;
                    }
                }
                File output = new File(outputPath);
                if (!output.isFile() || output.length() == 0) return;
                String previousPath = imagePath;
                imagePath = outputPath;
                outputPath = null;
                if (previousPath != null && !previousPath.equals(imagePath)
                        && !previousPath.equals(path) && !previousPath.equals(filterPath)
                        && !previousPath.equals(fullPaintPath)) {
                    new File(previousPath).delete();
                }
            } catch (Exception e) {
                FileLog.e(e);
            } finally {
                if (outputPath != null && !outputPath.equals(imagePath)) new File(outputPath).delete();
                if (resultBitmap != null && !resultBitmap.isRecycled()) resultBitmap.recycle();
                if (paintBitmap != null && !paintBitmap.isRecycled()) paintBitmap.recycle();
                if (paintSource != null && !paintSource.isRecycled()) paintSource.recycle();
                if (b != null && !b.isRecycled()) b.recycle();
                if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            }
        }
    }

    public static class SearchImage extends MediaEditState {
        public String id;
        public String imageUrl;
        public String thumbUrl;
        public int width;
        public int height;
        public int size;
        public int type;
        public int date;
        public CharSequence caption;
        public TLRPC.Document document;
        public TLRPC.Photo photo;
        public TLRPC.PhotoSize photoSize;
        public TLRPC.PhotoSize thumbPhotoSize;
        public TLRPC.BotInlineResult inlineResult;
        public HashMap<String, String> params;

        @Override
        public String getPath() {
            if (photoSize != null) {
                return FileLoader.getInstance(UserConfig.selectedAccount).getPathToAttach(photoSize, true).getAbsolutePath();
            } else if (document != null) {
                return FileLoader.getInstance(UserConfig.selectedAccount).getPathToAttach(document, true).getAbsolutePath();
            } else {
                return ImageLoader.getHttpFilePath(imageUrl, "jpg").getAbsolutePath();
            }
        }

        @Override
        public void reset() {
            super.reset();
        }

        public String getAttachName() {
            if (photoSize != null) {
                return FileLoader.getAttachFileName(photoSize);
            } else if (document != null) {
                return FileLoader.getAttachFileName(document);
            }
            return Utilities.MD5(imageUrl) + "." + ImageLoader.getHttpUrlExtension(imageUrl, "jpg");
        }

        public String getPathToAttach() {
            if (photoSize != null) {
                return FileLoader.getInstance(UserConfig.selectedAccount).getPathToAttach(photoSize, true).getAbsolutePath();
            } else if (document != null) {
                return FileLoader.getInstance(UserConfig.selectedAccount).getPathToAttach(document, true).getAbsolutePath();
            } else {
                return imageUrl;
            }
        }

        public SearchImage clone() {
            SearchImage searchImage = new SearchImage();
            searchImage.id = id;
            searchImage.imageUrl = imageUrl;
            searchImage.thumbUrl = thumbUrl;
            searchImage.width = width;
            searchImage.height = height;
            searchImage.size = size;
            searchImage.type = type;
            searchImage.date = date;
            searchImage.caption = caption;
            searchImage.document = document;
            searchImage.photo = photo;
            searchImage.photoSize = photoSize;
            searchImage.thumbPhotoSize = thumbPhotoSize;
            searchImage.inlineResult = inlineResult;
            searchImage.params = params;
            return searchImage;
        }
    }

    AudioManager.OnAudioFocusChangeListener audioRecordFocusChangedListener = focusChange -> {
        if (focusChange != AudioManager.AUDIOFOCUS_GAIN) {
            hasRecordAudioFocus = false;
        }
    };

    public final static int VIDEO_BITRATE_1080 = 6800_000;
    public final static int VIDEO_BITRATE_720 = 2621_440;
    public final static int VIDEO_BITRATE_480 = 1000_000;
    public final static int VIDEO_BITRATE_360 = 750_000;

    public final static String VIDEO_MIME_TYPE = "video/avc";
    public final static String AUDIO_MIME_TYPE = "audio/mp4a-latm";


    private SensorManager sensorManager;
    private boolean ignoreProximity;
    private PowerManager.WakeLock proximityWakeLock;
    private Sensor proximitySensor;
    private Sensor accelerometerSensor;
    private Sensor linearSensor;
    private Sensor gravitySensor;
    private boolean raiseToEarRecord;
    private ChatActivity raiseChat;
    private boolean accelerometerVertical;
    private long lastAccelerometerDetected;
    private int raisedToTop;
    private int raisedToTopSign;
    private int raisedToBack;
    private int countLess;
    private long timeSinceRaise;
    private long lastTimestamp = 0;
    private boolean proximityTouched;
    private boolean proximityHasDifferentValues;
    private float lastProximityValue = -100;
    private boolean useFrontSpeaker;
    private boolean inputFieldHasText;
    private boolean allowStartRecord;
    private boolean ignoreOnPause;
    private boolean sensorsStarted;
    private float previousAccValue;
    private float[] gravity = new float[3];
    private float[] gravityFast = new float[3];
    private float[] linearAcceleration = new float[3];

    private int hasAudioFocus;
    private boolean hasRecordAudioFocus;
    private boolean callInProgress;
    private int audioFocus = AUDIO_NO_FOCUS_NO_DUCK;
    private boolean resumeAudioOnFocusGain;

    private final AtomicLong playbackIntentGeneration = new AtomicLong();
    private volatile MessageObject playbackIntentMessage;
    private volatile boolean playbackIntentPaused;
    private int playbackIntentAccount = -1, playbackIntentLoginTime;
    private long playbackIntentUserId;
    private volatile AudioManager.OnAudioFocusChangeListener playbackFocusListener;
    private Object playbackFocusRequest;
    private long playbackFocusRequestGeneration;
    private volatile long playbackFocusLeaseGeneration;
    private volatile boolean playbackFocusGranted;
    private final AtomicLong playbackFocusEventGeneration = new AtomicLong();
    private volatile long focusLossIntent = -1;
    private volatile boolean focusLossWantsResume;
    private int playbackFocusAccount = -1, playbackFocusLoginTime;
    private long playbackFocusUserId;
    private MessageObject focusResumeMessage;
    private int focusResumePlayerNum;
    private long focusResumeIntent;
    private static final float VOLUME_DUCK = 0.2f;
    private static final float VOLUME_NORMAL = 1.0f;
    private static final int AUDIO_NO_FOCUS_NO_DUCK = 0;
    private static final int AUDIO_NO_FOCUS_CAN_DUCK = 1;
    private static final int AUDIO_FOCUSED = 2;
    private static final ConcurrentHashMap<String, Integer> cachedEncoderBitrates = new ConcurrentHashMap<>();

    private ArrayList<VideoConvertMessage> foregroundConvertingMessages = new ArrayList<>();
    private VideoConvertMessage currentForegroundConvertingVideo;

    public static class VideoConvertMessage {
        public MessageObject messageObject;
        public VideoEditedInfo videoEditedInfo;
        public int currentAccount;
        public boolean foreground;
        public boolean foregroundConversion;

        private final MessageObject sourceMessage;
        private final TLRPC.Message sourceMessageOwner;
        private final VideoEditedInfo sourceVideoEditedInfo;
        private final VideoEditedInfo messageVideoEditedInfo;
        private final int sourceAccount;
        private final long userId;
        private final long session;
        private final long dialogId;
        private final String outputPath;
        private final Object owner = new Object();
        private final AtomicBoolean firstWrite = new AtomicBoolean(true);
        private volatile boolean canceled;
        private boolean retired;
        private boolean terminalDelivered;
        private boolean terminalDispatched;
        private boolean foregroundRetired;
        private Runnable onPhysicalFinished;
        private boolean legacyConsumer = true;
        private boolean consumerPrepared, consumerTerminal, consumerFailed;
        private long consumerAvailable, consumerFinal, consumerRevision;
        private float consumerProgress;
        private final ArrayList<VideoConvertSubscription> consumers = new ArrayList<>();
        public VideoConvertMessage(MessageObject object, VideoEditedInfo info, boolean foreground, boolean conversion) {
            messageObject = object;
            currentAccount = messageObject.currentAccount;
            videoEditedInfo = info;
            this.foreground = foreground;
            this.foregroundConversion = conversion;
            sourceMessage = object;
            sourceMessageOwner = object.messageOwner;
            sourceVideoEditedInfo = info;
            messageVideoEditedInfo = object.videoEditedInfo;
            sourceAccount = object.currentAccount;
            userId = UserConfig.getInstance(sourceAccount).getClientUserId();
            session = app.nimarkogram.messenger.notifications.InAppNotifications.session(sourceAccount);
            dialogId = object.getDialogId();
            outputPath = sourceMessageOwner.attachPath;
        }

        private boolean isCurrentAccount() {
            return app.nimarkogram.messenger.notifications.InAppNotifications.isCurrent(sourceAccount, userId, session);
        }
    }

    private ArrayList<VideoConvertMessage> videoConvertQueue = new ArrayList<>();
    private VideoConvertMessage activeConversion;
    private boolean startingVideoConversion;
    private final WeakHashMap<MessageObject, Object> videoConversionOwners = new WeakHashMap<>();
    private final WeakHashMap<MessageObject, Object> videoConversionLatestOwners = new WeakHashMap<>();
    private final WeakHashMap<MessageObject, WeakReference<VideoConvertMessage>> videoConversionRecords = new WeakHashMap<>();
    private final WeakHashMap<VideoConvertMessage, Boolean> foregroundConversionOwners = new WeakHashMap<>();
    private final ConcurrentHashMap<String, Object> generatingWaveform = new ConcurrentHashMap<>();
    private final LruCache<String, byte[]> generatedWaveforms = new LruCache<>(128);

    private boolean voiceMessagesPlaylistUnread;
    private ArrayList<MessageObject> voiceMessagesPlaylist;
    private HashMap<String, MessageObject> voiceMessagesPlaylistMap;

    private static Runnable refreshGalleryRunnable;
    public static AlbumEntry allMediaAlbumEntry;
    public static AlbumEntry allPhotosAlbumEntry;
    public static AlbumEntry allVideosAlbumEntry;
    public static ArrayList<AlbumEntry> allMediaAlbums = new ArrayList<>();
    public static ArrayList<AlbumEntry> allPhotoAlbums = new ArrayList<>();
    private static Runnable broadcastPhotosRunnable;

    public boolean isSilent = false;
    private boolean isPaused = false;
    private boolean wasPlayingAudioBeforePause = false;
    private VideoPlayer audioPlayer = null;
    private VideoPlayer emojiSoundPlayer = null;
    private int emojiSoundPlayerNum = 0;
    private boolean isStreamingCurrentAudio;
    private int playerNum;
    private String shouldSavePositionForCurrentAudio;
    private long lastSaveTime;
    private float currentPlaybackSpeed = 1.0f;
    private float currentMusicPlaybackSpeed = 1.0f;
    private float fastPlaybackSpeed = 1.0f;
    private float fastMusicPlaybackSpeed = 1.0f;
    private float seekToProgressPending;
    private long lastProgress = 0;
    private MessageObject playingMessageObject;
    private MessageObject goingToShowMessageObject;
    private MusicListenReporter reporter;
    private boolean manualRecording;
    private Timer progressTimer = null;
    private final Object progressTimerSync = new Object();
    /**
     * The playback timer runs off the UI thread. Keep at most one progress
     * update in the main queue: otherwise a busy chat can accumulate 60
     * runnables per second until it catches up.
     */
    private final AtomicBoolean progressUpdatePending = new AtomicBoolean();
    private volatile int progressTimerGeneration;
    private boolean downloadingCurrentMessage;
    private boolean playMusicAgain;
    private PlaylistGlobalSearchParams playlistGlobalSearchParams;
    private AudioInfo audioInfo;
    private final DispatchQueue audioInfoQueue = new DispatchQueue("audioInfoQueue");
    private int audioInfoLoadGeneration;
    private String cachedAudioInfoKey;
    private AudioInfo cachedAudioInfo;
    private VideoPlayer videoPlayer;
    private boolean playerWasReady;
    private TextureView currentTextureView;
    private PipRoundVideoView pipRoundVideoView;
    private int pipSwitchingState;
    private boolean pipClosingToInline;
    private boolean showPipAfterInlineClose;
    private Activity baseActivity;
    private BaseFragment flagSecureFragment;
    private View feedbackView;
    private AspectRatioFrameLayout currentAspectRatioFrameLayout;
    private boolean isDrawingWasReady;
    private volatile long roundVideoFrameReadyAt;
    private volatile boolean roundVideoFirstFrameRendered;
    private volatile SurfaceTexture roundVideoPendingSurface;
    private volatile int roundVideoOutputGeneration;
    private ImageView roundVideoPlaybackCover;
    private Bitmap roundVideoPlaybackBitmap;
    private TextureView roundVideoCoverTexture;
    private boolean roundVideoCoverPending;
    private int roundVideoCoverGeneration;
    private FrameLayout currentTextureViewContainer;
    private int currentAspectRatioFrameLayoutRotation;
    private float currentAspectRatioFrameLayoutRatio;
    private boolean currentAspectRatioFrameLayoutReady;

    public MessagesController.SavedMusicList currentSavedMusicList;
    private ArrayList<MessageObject> playlist = new ArrayList<>();
    private HashMap<String, MessageObject> playlistMap = new HashMap<>();
    private ArrayList<MessageObject> shuffledPlaylist = new ArrayList<>();
    private int currentPlaylistNum;
    private boolean forceLoopCurrentPlaylist;
    private boolean[] playlistEndReached = new boolean[]{false, false};
    private boolean loadingPlaylist;
    private long playlistMergeDialogId;
    private int playlistClassGuid;
    private long playlistTopicId;
    private int[] playlistMaxId = new int[]{Integer.MAX_VALUE, Integer.MAX_VALUE};

    private PlaylistLoadOwner playlistLoadOwner;
    private long loadingPlaylistDialogId;
    private int loadingPlaylistMaxId;
    private int playlistPageRequestIndex;

    private static class PlaylistLoadOwner {
        final int account;
        final long userId;
        final int loginTime;
        final long dialogId;
        final long mergeDialogId;
        final long topicId;
        final int guid;
        boolean cacheLoaded;

        PlaylistLoadOwner(int account, long dialogId, long mergeDialogId, long topicId, int guid) {
            this.account = account;
            UserConfig config = UserConfig.getInstance(account);
            userId = config.getClientUserId();
            loginTime = config.loginTime;
            this.dialogId = dialogId;
            this.mergeDialogId = mergeDialogId;
            this.topicId = topicId;
            this.guid = guid;
        }

        boolean isCurrentAccount() {
            UserConfig config = UserConfig.getInstance(account);
            return userId != 0 && config.getClientUserId() == userId && config.loginTime == loginTime;
        }
    }

    private static String playlistKey(MessageObject message) {
        return message.currentAccount + "_" + message.getDialogId() + "_" + message.getId();
    }

    private MessageObject playlistAnchor() {
        if (playingMessageObject != null && playingMessageObject.isMusic()) {
            return playlistMap.containsKey(playlistKey(playingMessageObject)) ? playingMessageObject : null;
        }
        if (savedMusicPlaylistState != null && playlistMap.containsKey(playlistKey(savedMusicPlaylistState.playingMessage))) {
            return savedMusicPlaylistState.playingMessage;
        }
        return null;
    }

    private boolean isCurrentPlaylistOwner(PlaylistLoadOwner owner) {
        MessageObject anchor = playlistAnchor();
        return owner != null && owner == playlistLoadOwner && owner.isCurrentAccount()
                && currentSavedMusicList == null && anchor != null && !anchor.scheduled
                && anchor.currentAccount == owner.account && playlistMap.containsKey(playlistKey(anchor));
    }

    public boolean isCurrentMusicLoad(int account, Object... args) {
        PlaylistLoadOwner owner = playlistLoadOwner;
        return args.length >= 5 && args[4] == owner && isCurrentPlaylistOwner(owner)
                && owner.account == account && playlistGlobalSearchParams == null
                && owner.dialogId == (Long) args[0] && owner.topicId == (Long) args[3];
    }

    private void updatePlaylistIndex(MessageObject anchor) {
        int index = anchor == null ? -1 : playlist.indexOf(playlistMap.get(playlistKey(anchor)));
        if (index >= 0) {
            currentPlaylistNum = index;
        }
        if (SharedConfig.shuffleMusic) {
            buildShuffledPlayList();
        }
    }
    private Runnable setLoadingRunnable = new Runnable() {
        @Override
        public void run() {
            if (playingMessageObject == null) {
                return;
            }
            FileLoader.getInstance(playingMessageObject.currentAccount).setLoadingVideo(playingMessageObject.getDocument(), true, false);
        }
    };

    private boolean audioRecorderPaused;
    private AudioRecord audioRecorder;
    public volatile TLRPC.TL_document recordingAudio;
    private int recordingGuid = -1;
    private final AtomicLong recordAudioFocusGeneration = new AtomicLong();
    private volatile long recordingAudioFocusGeneration;
    private volatile long pendingVoiceRecordingGeneration = -1;
    private final WeakHashMap<RecordingPreviewOwner, Boolean> recordingPreviewOwners = new WeakHashMap<>();
    private RecordingPreviewOwner recordingPreviewOwner;
    private final Object recordingEncoderLock = new Object();
    private final Object recordingTrimLock = new Object();
    private volatile Object pendingRecordingTrim;
    private volatile long recordingTrimGeneration;
    private File recordingEncoderFile;
    private volatile TLRPC.TL_document recordingEncodingFailed;
    private int recordingCurrentAccount;
    private File recordingPrevAudioFile;
    private volatile File recordingAudioFile;
    private long recordStartTime;
    public long recordTimeCount;
    public int writtenFrame;
    private long recordDialogId;
    private long recordTopicId;
    private long recordMonoForumPeerId;
    private MessageSuggestionParams recordMonoForumSuggestionParams;
    private MessageObject recordReplyingMsg;
    private MessageObject recordReplyingTopMsg;
    private TL_stories.StoryItem recordReplyingStory;
    private SendMessageChatArguments recordSendMessageChatArguments;
    public short[] recordSamples = new short[1024];
    public long samplesCount;

    private final Object sync = new Object();

    private ArrayList<ByteBuffer> recordBuffers = new ArrayList<>();
    private ByteBuffer fileBuffer;
    public int recordBufferSize = 1280;
    private int recordInputChannels = 1;
    public int sampleRate = 48000;
    private int sendAfterDone;
    private boolean sendAfterDoneNotify;
    private int sendAfterDoneScheduleDate;
    private boolean sendAfterDoneOnce;
    private long sendAfterDonePayStars;

    private AudioRecorderV2Controller audioRecorderV2Controller;
    private Runnable audioRecorderV2StartRunnable;
    private boolean audioRecorderV2Active;
    private boolean audioRecorderV2Stopping;
    private boolean audioRecorderV2VoiceOnce;
    private AudioRecordingSession.Result audioRecorderV2PendingResult;
    private AudioRecorderV2Owner audioRecorderV2Owner;

    private static final class AudioRecorderV2Owner {
        final int account;
        int guid;
        final long userId;
        final int loginTime;
        final long loginSession;
        final long dialogId, topicId;
        long focusGeneration;

        MediaDataController.DraftVoice draft;
        NotificationCenter.NotificationCenterDelegate logoutObserver;

        AudioRecorderV2Owner(int account, int guid, long focusGeneration, long dialogId, long topicId) {
            this.account = account;
            this.guid = guid;
            UserConfig config = UserConfig.getInstance(account);
            this.userId = config.getClientUserId();
            this.loginTime = config.loginTime;
            this.loginSession = app.nimarkogram.messenger.notifications.InAppNotifications.session(account);
            this.dialogId = dialogId;
            this.topicId = topicId;
            this.focusGeneration = focusGeneration;
        }
    }
    private Runnable recordStartRunnable;
    private DispatchQueue2 recordQueue;
    private DispatchQueue2 fileEncodingQueue;
    private Runnable recordRunnable = new Runnable() {
        @Override
        public void run() {
            if (audioRecorder != null) {
                ByteBuffer buffer;
                if (!recordBuffers.isEmpty()) {
                    buffer = recordBuffers.get(0);
                    recordBuffers.remove(0);
                } else {
                    buffer = ByteBuffer.allocateDirect(recordBufferSize);
                    buffer.order(ByteOrder.nativeOrder());
                }
                int inputBufferSize = recordBufferSize * recordInputChannels;
                if (buffer.capacity() < inputBufferSize) {
                    buffer = ByteBuffer.allocateDirect(inputBufferSize);
                    buffer.order(ByteOrder.nativeOrder());
                }
                buffer.clear();
                int len = audioRecorder.read(buffer, buffer.capacity());
                if (len > 0) {
                    final boolean flush = len != buffer.capacity();
                    if (recordInputChannels == 2) {
                        len = app.nimarkogram.messenger.camera.CameraXAudioCapture.downmixStereo16(buffer, len);
                    }
                    buffer.limit(len);
                    double sum = 0;
                    try {
                        long newSamplesCount = samplesCount + len / 2;
                        int currentPart = (int) (((double) samplesCount / (double) newSamplesCount) * recordSamples.length);
                        int newPart = recordSamples.length - currentPart;
                        float sampleStep;
                        if (currentPart != 0) {
                            sampleStep = (float) recordSamples.length / (float) currentPart;
                            float currentNum = 0;
                            for (int a = 0; a < currentPart; a++) {
                                recordSamples[a] = recordSamples[(int) currentNum];
                                currentNum += sampleStep;
                            }
                        }
                        int currentNum = currentPart;
                        float nextNum = 0;
                        sampleStep = (float) len / 2 / (float) newPart;
                        for (int i = 0; i < len / 2; i++) {
                            short peak = buffer.getShort();
                            if (Build.VERSION.SDK_INT < 21) {
                                if (peak > 2500) {
                                    sum += peak * peak;
                                }
                            } else {
                                sum += peak * peak;
                            }
                            if (i == (int) nextNum && currentNum < recordSamples.length) {
                                recordSamples[currentNum] = peak;
                                nextNum += sampleStep;
                                currentNum++;
                            }
                        }
                        samplesCount = newSamplesCount;
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                    buffer.position(0);
                    final double amplitude = Math.sqrt(sum / len / 2);
                    final ByteBuffer finalBuffer = buffer;
                    final TLRPC.TL_document audio = recordingAudio;
                    final File file = recordingAudioFile;
                    if (!fileEncodingQueue.postRunnable(() -> encodeRecordingBuffer(audio, file, finalBuffer, flush))) {
                        if (audio != null && audio == recordingAudio && file == recordingAudioFile) {
                            recordingEncodingFailed = audio;
                        }
                        recordBuffers.add(finalBuffer);
                    }
                    recordQueue.postRunnable(recordRunnable);
                    AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(recordingCurrentAccount).postNotificationName(NotificationCenter.recordProgressChanged, recordingGuid, amplitude));
                } else {
                    recordBuffers.add(buffer);
                    if (sendAfterDone != 3 && sendAfterDone != 4) {
                        stopRecordingInternal(sendAfterDone, sendAfterDoneNotify, sendAfterDoneScheduleDate, sendAfterDoneOnce, sendAfterDonePayStars);
                    }
                }
            }
        }
    };

    private float audioVolume;
    private ValueAnimator audioVolumeAnimator;

    private final ValueAnimator.AnimatorUpdateListener audioVolumeUpdateListener = new ValueAnimator.AnimatorUpdateListener() {
        @Override
        public void onAnimationUpdate(ValueAnimator valueAnimator) {
            audioVolume = (float) valueAnimator.getAnimatedValue();
            setPlayerVolume();
        }
    };

    private class InternalObserver extends ContentObserver {
        public InternalObserver() {
            super(null);
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            processMediaObserver(MediaStore.Images.Media.INTERNAL_CONTENT_URI);
        }
    }

    private class ExternalObserver extends ContentObserver {
        public ExternalObserver() {
            super(null);
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            processMediaObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        }
    }

    private static class GalleryObserverInternal extends ContentObserver {
        public GalleryObserverInternal() {
            super(null);
        }

        private void scheduleReloadRunnable() {
            AndroidUtilities.runOnUIThread(refreshGalleryRunnable = () -> {
                if (PhotoViewer.getInstance().isVisible()) {
                    scheduleReloadRunnable();
                    return;
                }
                refreshGalleryRunnable = null;
                loadGalleryPhotosAlbums(0);
            }, 2000);
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            if (refreshGalleryRunnable != null) {
                AndroidUtilities.cancelRunOnUIThread(refreshGalleryRunnable);
            }
            scheduleReloadRunnable();
        }
    }

    private static class GalleryObserverExternal extends ContentObserver {
        public GalleryObserverExternal() {
            super(null);
        }

        @Override
        public void onChange(boolean selfChange) {
            super.onChange(selfChange);
            if (refreshGalleryRunnable != null) {
                AndroidUtilities.cancelRunOnUIThread(refreshGalleryRunnable);
            }
            AndroidUtilities.runOnUIThread(refreshGalleryRunnable = () -> {
                refreshGalleryRunnable = null;
                loadGalleryPhotosAlbums(0);
            }, 2000);
        }
    }

    public static void checkGallery() {
        if (Build.VERSION.SDK_INT < 24 || allPhotosAlbumEntry == null) {
            return;
        }
        final int prevSize = allPhotosAlbumEntry.photos.size();
        Utilities.globalQueue.postRunnable(() -> {
            int count = 0;
            Cursor cursor = null;
            try {
                // NimarkoGram (CG parity): use PermissionsUtils.isImagesPermissionGranted —
                // narrower than the upstream DrKLO union check; only query when the
                // matching READ_MEDIA_IMAGES (SDK33+) / READ_EXTERNAL_STORAGE (<33) is held.
                if (app.nimarkogram.messenger.utils.PermissionsUtils.isImagesPermissionGranted()) {
                    cursor = MediaStore.Images.Media.query(ApplicationLoader.applicationContext.getContentResolver(), MediaStore.Images.Media.EXTERNAL_CONTENT_URI, new String[]{"COUNT(_id)"}, null, null, null);
                    if (cursor != null) {
                        if (cursor.moveToNext()) {
                            count += cursor.getInt(0);
                        }
                    }
                }
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
            try {
                // NimarkoGram (CG parity): use PermissionsUtils.isVideoPermissionGranted.
                if (app.nimarkogram.messenger.utils.PermissionsUtils.isVideoPermissionGranted()) {
                    cursor = MediaStore.Images.Media.query(ApplicationLoader.applicationContext.getContentResolver(), MediaStore.Video.Media.EXTERNAL_CONTENT_URI, new String[]{"COUNT(_id)"}, null, null, null);
                    if (cursor != null) {
                        if (cursor.moveToNext()) {
                            count += cursor.getInt(0);
                        }
                    }
                }
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
            if (prevSize != count) {
                if (refreshGalleryRunnable != null) {
                    AndroidUtilities.cancelRunOnUIThread(refreshGalleryRunnable);
                    refreshGalleryRunnable = null;
                }
                loadGalleryPhotosAlbums(0);
            }
        }, 2000);
    }

    private ExternalObserver externalObserver;
    private InternalObserver internalObserver;
    private long lastChatEnterTime;
    private int lastChatAccount;
    private long lastChatLeaveTime;
    private long lastMediaCheckTime;
    private TLRPC.EncryptedChat lastSecretChat;
    private TLRPC.User lastUser;
    private int lastMessageId;
    private ArrayList<Long> lastChatVisibleMessages;
    private int startObserverToken;
    private StopMediaObserverRunnable stopMediaObserverRunnable;

    private final class StopMediaObserverRunnable implements Runnable {
        public int currentObserverToken = 0;

        @Override
        public void run() {
            if (currentObserverToken == startObserverToken) {
                try {
                    if (internalObserver != null) {
                        ApplicationLoader.applicationContext.getContentResolver().unregisterContentObserver(internalObserver);
                        internalObserver = null;
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                }
                try {
                    if (externalObserver != null) {
                        ApplicationLoader.applicationContext.getContentResolver().unregisterContentObserver(externalObserver);
                        externalObserver = null;
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
        }
    }

    private String[] mediaProjections;

    private static volatile MediaController Instance;

    public static MediaController getInstance() {
        MediaController localInstance = Instance;
        if (localInstance == null) {
            synchronized (MediaController.class) {
                localInstance = Instance;
                if (localInstance == null) {
                    Instance = localInstance = new MediaController();
                }
            }
        }
        return localInstance;
    }

    public MediaController() {
        recordQueue = new DispatchQueue2("recordQueue");
        recordQueue.setPriority(Thread.MAX_PRIORITY);
        fileEncodingQueue = new DispatchQueue2("fileEncodingQueue");
        fileEncodingQueue.setPriority(Thread.MAX_PRIORITY);

        recordQueue.postRunnable(() -> {
            try {
                sampleRate = 48000;
                int minBuferSize = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
                if (minBuferSize <= 0) {
                    minBuferSize = 1280;
                }
                recordBufferSize = minBuferSize;

                for (int a = 0; a < 5; a++) {
                    ByteBuffer buffer = ByteBuffer.allocateDirect(recordBufferSize);
                    buffer.order(ByteOrder.nativeOrder());
                    recordBuffers.add(buffer);
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        });
        Utilities.globalQueue.postRunnable(() -> {
            try {
                currentPlaybackSpeed = MessagesController.getGlobalMainSettings().getFloat("playbackSpeed", 1.0f);
                currentMusicPlaybackSpeed = MessagesController.getGlobalMainSettings().getFloat("musicPlaybackSpeed", 1.0f);
                fastPlaybackSpeed = MessagesController.getGlobalMainSettings().getFloat("fastPlaybackSpeed", 1.8f);
                fastMusicPlaybackSpeed = MessagesController.getGlobalMainSettings().getFloat("fastMusicPlaybackSpeed", 1.8f);
                sensorManager = (SensorManager) ApplicationLoader.applicationContext.getSystemService(Context.SENSOR_SERVICE);
                linearSensor = sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION);
                gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY);
                if (linearSensor == null || gravitySensor == null) {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("gravity or linear sensor not found");
                    }
                    accelerometerSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
                    linearSensor = null;
                    gravitySensor = null;
                }
                proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY);
                PowerManager powerManager = (PowerManager) ApplicationLoader.applicationContext.getSystemService(Context.POWER_SERVICE);
                proximityWakeLock = powerManager.newWakeLock(0x00000020, "telegram:proximity_lock");
            } catch (Exception e) {
                FileLog.e(e);
            }

            try {
                PhoneStateListener phoneStateListener = new PhoneStateListener() {
                    @Override
                    public void onCallStateChanged(final int state, String incomingNumber) {
                        if (state == TelephonyManager.CALL_STATE_RINGING || state == TelephonyManager.CALL_STATE_OFFHOOK) {
                            cancelAutomaticMusicReturn();
                            playbackIntentPaused = true;
                            playbackIntentGeneration.incrementAndGet();
                        }
                        AndroidUtilities.runOnUIThread(() -> {
                            if (state == TelephonyManager.CALL_STATE_RINGING) {
                                cancelAutomaticMusicReturn();
                                if (playingMessageObject != null && (!isMessagePaused() || downloadingCurrentMessage)) {
                                    pauseMessage(playingMessageObject);
                                } else if (isRecordingAudio()) {
                                    stopRecording(2, false, 0, false, 0);
                                }
                                EmbedBottomSheet embedBottomSheet = EmbedBottomSheet.getInstance();
                                if (embedBottomSheet != null) {
                                    embedBottomSheet.pause();
                                }
                                callInProgress = true;
                            } else if (state == TelephonyManager.CALL_STATE_IDLE) {
                                callInProgress = false;
                            } else if (state == TelephonyManager.CALL_STATE_OFFHOOK) {
                                cancelAutomaticMusicReturn();
                                pauseMessage(playingMessageObject, false);
                                EmbedBottomSheet embedBottomSheet = EmbedBottomSheet.getInstance();
                                if (embedBottomSheet != null) {
                                    embedBottomSheet.pause();
                                }
                                callInProgress = true;
                            }
                        });
                    }
                };
                TelephonyManager mgr = (TelephonyManager) ApplicationLoader.applicationContext.getSystemService(Context.TELEPHONY_SERVICE);
                if (mgr != null) {
                    mgr.listen(phoneStateListener, PhoneStateListener.LISTEN_CALL_STATE);
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        });

        fileBuffer = ByteBuffer.allocateDirect(1920);

        AndroidUtilities.runOnUIThread(() -> {
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                NotificationCenter.getInstance(a).addObserver(MediaController.this, NotificationCenter.fileLoaded);
                NotificationCenter.getInstance(a).addObserver(MediaController.this, NotificationCenter.httpFileDidLoad);
                NotificationCenter.getInstance(a).addObserver(MediaController.this, NotificationCenter.fileLoadFailed);
                NotificationCenter.getInstance(a).addObserver(MediaController.this, NotificationCenter.httpFileDidFailedLoad);
                NotificationCenter.getInstance(a).addObserver(MediaController.this, NotificationCenter.didReceiveNewMessages);
                NotificationCenter.getInstance(a).addObserver(MediaController.this, NotificationCenter.messagesDeleted);
                NotificationCenter.getInstance(a).addObserver(MediaController.this, NotificationCenter.removeAllMessagesFromDialog);
                NotificationCenter.getInstance(a).addObserver(MediaController.this, NotificationCenter.musicDidLoad);
                NotificationCenter.getInstance(a).addObserver(MediaController.this, NotificationCenter.mediaDidLoad);
                NotificationCenter.getInstance(a).addObserver(MediaController.this, NotificationCenter.musicListLoaded);
                NotificationCenter.getGlobalInstance().addObserver(MediaController.this, NotificationCenter.playerDidStartPlaying);
            }
        });

        mediaProjections = new String[]{
                MediaStore.Images.ImageColumns.DATA,
                MediaStore.Images.ImageColumns.DISPLAY_NAME,
                MediaStore.Images.ImageColumns.BUCKET_DISPLAY_NAME,
                Build.VERSION.SDK_INT > 28 ? MediaStore.Images.ImageColumns.DATE_MODIFIED : MediaStore.Images.ImageColumns.DATE_TAKEN,
                MediaStore.Images.ImageColumns.TITLE,
                MediaStore.Images.ImageColumns.WIDTH,
                MediaStore.Images.ImageColumns.HEIGHT
        };

        ContentResolver contentResolver = ApplicationLoader.applicationContext.getContentResolver();
        try {
            contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, new GalleryObserverExternal());
        } catch (Exception e) {
            FileLog.e(e);
        }
        try {
            contentResolver.registerContentObserver(MediaStore.Images.Media.INTERNAL_CONTENT_URI, true, new GalleryObserverInternal());
        } catch (Exception e) {
            FileLog.e(e);
        }
        try {
            contentResolver.registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, new GalleryObserverExternal());
        } catch (Exception e) {
            FileLog.e(e);
        }
        try {
            contentResolver.registerContentObserver(MediaStore.Video.Media.INTERNAL_CONTENT_URI, true, new GalleryObserverInternal());
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    @Override
    public void onAudioFocusChange(int focusChange) {
        AudioManager.OnAudioFocusChangeListener listener = playbackFocusListener;
        if (listener != null) listener.onAudioFocusChange(focusChange);
    }

    private void onPlaybackFocusChange(long lease, int focusChange) {
        if (lease != playbackFocusLeaseGeneration || playbackFocusListener == null) return;
        final MessageObject owner = playbackIntentMessage;
        final long intent = playbackIntentGeneration.get();
        final long event = playbackFocusEventGeneration.incrementAndGet();
        final boolean wanted = !playbackIntentPaused || focusLossIntent == intent && focusLossWantsResume;
        final boolean loss = focusChange == AudioManager.AUDIOFOCUS_LOSS || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT
                || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK;
        if (loss) {
            focusLossIntent = intent;
            focusLossWantsResume = wanted;
            cancelAutomaticMusicReturn();
            playbackFocusGranted = false;
            playbackIntentPaused = true;
        }
        AndroidUtilities.runOnUIThread(() -> {
            if (lease != playbackFocusLeaseGeneration || playbackFocusListener == null
                    || intent != playbackIntentGeneration.get() || !currentPlaybackIntent(owner)) return;
            if (focusChange == AudioManager.AUDIOFOCUS_GAIN) {
                if (event != playbackFocusEventGeneration.get()) return;
                playbackFocusGranted = true;
                audioFocus = AUDIO_FOCUSED;
                if (resumeAudioOnFocusGain && focusResumeMessage == owner && focusResumePlayerNum == playerNum
                        && focusResumeIntent == intent && playingMessageObject == owner && isPaused
                        && canStartLocalPlayback(owner)) {
                    resumeAudioOnFocusGain = false;
                    playbackIntentPaused = false;
                    playMessage(owner);
                }
            } else if (loss) {
                audioFocus = AUDIO_NO_FOCUS_NO_DUCK;
                int tag = playerNum;
                if (playingMessageObject == owner) pauseMessage(owner, false, true);
                if (focusChange != AudioManager.AUDIOFOCUS_LOSS && wanted && playingMessageObject == owner
                        && playerNum == tag && playbackIntentGeneration.get() == intent
                        && lease == playbackFocusLeaseGeneration && playbackFocusListener != null) {
                    resumeAudioOnFocusGain = true;
                    focusResumeMessage = owner;
                    focusResumePlayerNum = tag;
                    focusResumeIntent = playbackIntentGeneration.get();
                } else if (focusChange == AudioManager.AUDIOFOCUS_LOSS && playbackIntentGeneration.get() == intent
                        && playingMessageObject == owner && currentPlaybackIntent(owner)) {
                    abandonPlaybackFocus(playbackFocusListener, lease);
                }
            }
            setPlayerVolume();
        });
    }

    private static boolean isTemporaryPlayback(MessageObject message) {
        return message != null && !message.isMusic();
    }

    private boolean currentPlaybackIntent(MessageObject message) {
        if (message == null || playbackIntentMessage != message || message.currentAccount != playbackIntentAccount) return false;
        UserConfig config = UserConfig.getInstance(playbackIntentAccount);
        return config.getClientUserId() == playbackIntentUserId && config.loginTime == playbackIntentLoginTime;
    }

    private boolean canStartLocalPlayback(MessageObject message) {
        return !isTemporaryPlayback(message) || !callInProgress && !VoIPService.isAnyKindOfCallActive()
                && !isRecordingAudio() && !hasRecordAudioFocus && pendingVoiceRecordingGeneration < 0 && !raiseToEarRecord;
    }

    private void beginPlaybackIntent(MessageObject message, boolean paused) {
        UserConfig config = UserConfig.getInstance(message.currentAccount);
        long user = config.getClientUserId();
        if (playbackFocusListener != null && (playbackFocusAccount != message.currentAccount
                || playbackFocusUserId != user || playbackFocusLoginTime != config.loginTime)) {
            abandonPlaybackFocus(playbackFocusListener, playbackFocusLeaseGeneration);
        }
        playbackIntentGeneration.incrementAndGet();
        resumeAudioOnFocusGain = false;
        focusResumeMessage = null;
        playbackIntentAccount = message.currentAccount;
        playbackIntentUserId = user;
        playbackIntentLoginTime = config.loginTime;
        playbackIntentPaused = paused;
        playbackIntentMessage = message;
    }

    private void abandonPlaybackFocus(AudioManager.OnAudioFocusChangeListener expected, long lease) {
        if (expected == null || playbackFocusListener != expected || playbackFocusLeaseGeneration != lease) return;
        Object request = playbackFocusRequest;
        playbackFocusRequest = null;
        ++playbackFocusRequestGeneration;
        playbackFocusListener = null;
        ++playbackFocusLeaseGeneration;
        playbackFocusGranted = false;
                hasAudioFocus = 0;
                audioFocus = AUDIO_NO_FOCUS_NO_DUCK;
                    resumeAudioOnFocusGain = false;
        focusResumeMessage = null;
        try {
            if (Build.VERSION.SDK_INT >= 26 && request != null) {
                PlaybackFocusApi26.abandon(NotificationsController.audioManager, request);
            } else {
                NotificationsController.audioManager.abandonAudioFocus(expected);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private static final class PlaybackFocusApi26 {
        static Object create(AudioManager.OnAudioFocusChangeListener listener, int stream, int gain) {
            return new android.media.AudioFocusRequest.Builder(gain)
                    .setAudioAttributes(new android.media.AudioAttributes.Builder().setLegacyStreamType(stream).build())
                    .setWillPauseWhenDucked(true)
                    .setOnAudioFocusChangeListener(listener)
                    .build();
        }

        static int request(AudioManager manager, Object request) {
            return manager.requestAudioFocus((android.media.AudioFocusRequest) request);
        }

        static void abandon(AudioManager manager, Object request) {
            manager.abandonAudioFocusRequest((android.media.AudioFocusRequest) request);
        }
    }

    private void retirePlaybackIntent(MessageObject expected, long intent) {
        if (playbackIntentGeneration.get() != intent || playbackIntentMessage != expected) return;
        playbackIntentGeneration.incrementAndGet();
        playbackIntentMessage = null;
        playbackIntentPaused = true;
        abandonPlaybackFocus(playbackFocusListener, playbackFocusLeaseGeneration);
    }

    private void failCurrentPlayback(MessageObject message, int tag, VideoPlayer failedPlayer) {
        if (!currentPlaybackIntent(message) || tag != playerNum
                || failedPlayer != null && !isCurrentPlayer(failedPlayer)
                || playingMessageObject != null && playingMessageObject != message) return;
        cancelAutomaticMusicReturn();
        if (playingMessageObject == null) playingMessageObject = message;
        cleanupPlayer(true, true);
    }

    private void pendingPlaybackFileFailed(int account, String fileName) {
        MessageObject message = playingMessageObject;
        if (!downloadingCurrentMessage || message == null || message.currentAccount != account
                || !FileLoader.getAttachFileName(message.getDocument()).equals(fileName)) return;
        if (!currentPlaybackIntent(message)) {
            cleanupPlayer(false, true, false, false, account);
            return;
        }
        if (FileLoader.getInstance(account).isLoadingFile(fileName)) return;
        if (FileLoader.getInstance(account).getPathToMessage(message.messageOwner).exists()) return;
        failCurrentPlayback(message, playerNum, null);
    }

    private void setPlayerVolume() {
        try {
            float volume;
            if (isSilent) {
                volume = 0;
            } else if (audioFocus != AUDIO_NO_FOCUS_CAN_DUCK) {
                volume = VOLUME_NORMAL;
            } else {
                volume = VOLUME_DUCK;
            }
            if (audioPlayer != null) {
                audioPlayer.setVolume(CastSync.isActive() ? 0.0f : volume * audioVolume);
            } else if (videoPlayer != null) {
                videoPlayer.setVolume(CastSync.isActive() ? 0.0f : volume);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public VideoPlayer getVideoPlayer() {
        return videoPlayer;
    }

    private void startProgressTimer(final MessageObject currentPlayingMessageObject) {
        synchronized (progressTimerSync) {
            if (progressTimer != null) {
                try {
                    progressTimer.cancel();
                    progressTimer = null;
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
            final int generation = ++progressTimerGeneration;
            final String fileName = currentPlayingMessageObject.getFileName();
            progressTimer = new Timer();
            progressTimer.schedule(new TimerTask() {
                @Override
                public void run() {
                    synchronized (sync) {
                        if (!progressUpdatePending.compareAndSet(false, true)) {
                            return;
                        }
                        try {
                            AndroidUtilities.runOnUIThread(() -> {
                                try {
                                    if (generation != progressTimerGeneration
                                            || playingMessageObject != currentPlayingMessageObject
                                            || (audioPlayer == null && videoPlayer == null)
                                            || isPaused) {
                                        return;
                                    }
                                    long duration;
                                    long progress;
                                    float value;
                                    float bufferedValue;
                                    if (videoPlayer != null) {
                                        duration = videoPlayer.getDuration();
                                        progress = videoPlayer.getCurrentPosition();
                                        if (progress < 0 || duration <= 0) {
                                            return;
                                        }
                                        bufferedValue = videoPlayer.getBufferedPosition() / (float) duration;
                                        value = progress / (float) duration;
                                        if (value >= 1) {
                                            return;
                                        }
                                    } else {
                                        duration = audioPlayer.getDuration();
                                        progress = audioPlayer.getCurrentPosition();
                                        value = duration >= 0 ? (progress / (float) duration) : 0.0f;
                                        bufferedValue = audioPlayer.getBufferedPosition() / (float) duration;
                                        if (duration == C.TIME_UNSET || progress < 0 || seekToProgressPending != 0) {
                                            return;
                                        }
                                    }
                                    lastProgress = progress;
                                    currentPlayingMessageObject.audioPlayerDuration = (int) (duration / 1000);
                                    currentPlayingMessageObject.audioProgress = value;
                                    currentPlayingMessageObject.audioProgressSec = (int) (lastProgress / 1000);
                                    currentPlayingMessageObject.bufferedProgress = bufferedValue;
                                    if (value >= 0 && shouldSavePositionForCurrentAudio != null && SystemClock.elapsedRealtime() - lastSaveTime >= 1000) {
                                        final String saveFor = shouldSavePositionForCurrentAudio;
                                        lastSaveTime = SystemClock.elapsedRealtime();
                                        Utilities.globalQueue.postRunnable(() -> {
                                            SharedPreferences.Editor editor = ApplicationLoader.applicationContext.getSharedPreferences("media_saved_pos", Activity.MODE_PRIVATE).edit();
                                            editor.putFloat(saveFor, value).commit();
                                        });
                                    }
                                    NotificationCenter.getInstance(currentPlayingMessageObject.currentAccount).postNotificationName(NotificationCenter.messagePlayingProgressDidChanged, currentPlayingMessageObject.getId(), value);
                                } catch (Exception e) {
                                    FileLog.e(e);
                                } finally {
                                    progressUpdatePending.set(false);
                                }
                            });
                        } catch (Exception e) {
                            progressUpdatePending.set(false);
                            FileLog.e(e);
                        }
                    }
                }
            }, 0, 17);
        }
    }

    private void stopProgressTimer() {
        synchronized (progressTimerSync) {
            progressTimerGeneration++;
            if (progressTimer != null) {
                try {
                    progressTimer.cancel();
                    progressTimer = null;
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
        }
    }

    public void cleanup() {
        cleanup(-1);
    }

    public void cleanup(int account) {
        if (account < -1 || account >= UserConfig.MAX_ACCOUNT_COUNT) {
            return;
        }
        final MessageObject retiringIntent = playbackIntentMessage;
        final long retiringGeneration = playbackIntentGeneration.get();
        PendingMusicReturn returning = pendingMusicReturn;
        if (returning != null && (account < 0 || returning.state.account == account
                || returning.state.spokenMessage != null && returning.state.spokenAccount == account)) {
            cancelAutomaticMusicReturn();
            retirePendingMusicReturn(true);
        }
        if (savedMusicPlaylistState != null && (account < 0
                || savedMusicPlaylistState.account == account
                || savedMusicPlaylistState.spokenMessage != null && savedMusicPlaylistState.spokenAccount == account)) {
            cancelAutomaticMusicReturn();
            clearMusicPlaylistState();
        }
        ArrayList<VideoConvertMessage> retired = retireVideoConversions(account);
        boolean stopPlayback = account < 0 || playingMessageObject != null && playingMessageObject.currentAccount == account;
        if (account < 0 || isPlaylistForAccount(account)) {
            clearPlaylist();
        } else if (savedMusicPlaylistState != null && savedMusicPlaylistState.playingMessage.currentAccount == account) {
            savedMusicPlaylistState = null;
        }
        if (account < 0) {
            voiceMessagesPlaylist = null;
            voiceMessagesPlaylistMap = null;
            generatingWaveform.clear();
            generatedWaveforms.evictAll();
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                DownloadController.getInstance(a).cleanup();
            }
        } else {
            if (voiceMessagesPlaylist != null) {
                for (int a = voiceMessagesPlaylist.size() - 1; a >= 0; a--) {
                    MessageObject message = voiceMessagesPlaylist.get(a);
                    if (message.currentAccount == account) {
                        voiceMessagesPlaylist.remove(a);
                        if (voiceMessagesPlaylistMap != null) {
                            voiceMessagesPlaylistMap.remove(playlistKey(message));
                        }
                    }
                }
                if (voiceMessagesPlaylist.isEmpty()) {
                    voiceMessagesPlaylist = null;
                    voiceMessagesPlaylistMap = null;
                }
            }
            String prefix = account + "_";
            for (String key : generatingWaveform.keySet()) {
                if (key.startsWith(prefix)) {
                    generatingWaveform.remove(key);
                }
            }
            for (String key : generatedWaveforms.snapshot().keySet()) {
                if (key.startsWith(prefix)) {
                    generatedWaveforms.remove(key);
                }
            }
            DownloadController.getInstance(account).cleanup();
        }
        if (stopPlayback) {
        audioInfoLoadGeneration++;
        audioInfo = null;
        playMusicAgain = false;
            cleanupPlayer(true, true, false, false, account);
        }
        if (retiringIntent != null && (account < 0 || retiringIntent.currentAccount == account)) {
            retirePlaybackIntent(retiringIntent, retiringGeneration);
        }
        for (int a = 0; a < retired.size(); a++) {
            VideoConvertMessage message = retired.get(a);
            if (message == activeConversion || message.terminalDispatched) {
                checkForegroundConvertMessage(message, true);
            } else {
                dispatchVideoConversionResult(message, VideoConvertResult.failed(), false);
            }
        }
        startVideoConvertFromQueue();
    }

    public void cleanupPlayback() {
        clearPlaylist();
        voiceMessagesPlaylist = null;
        voiceMessagesPlaylistMap = null;
        audioInfoLoadGeneration++;
        audioInfo = null;
        playMusicAgain = false;
        cleanupPlayer(true, true);
    }

    private boolean isPlaylistForAccount(int account) {
        if (currentSavedMusicList != null) {
            return currentSavedMusicList.currentAccount == account;
        }
        if (playlistLoadOwner != null) {
            return playlistLoadOwner.account == account;
        }
        if (playlistGlobalSearchParams != null && playlistGlobalSearchParams.sourceAccount >= 0) {
            return playlistGlobalSearchParams.sourceAccount == account;
        }
        if (!playlist.isEmpty()) {
            return playlist.get(0).currentAccount == account;
        }
        if (!shuffledPlaylist.isEmpty()) {
            return shuffledPlaylist.get(0).currentAccount == account;
        }
        return savedMusicPlaylistState != null && savedMusicPlaylistState.playingMessage.currentAccount == account;
    }

    private void clearPlaylist() {
        playlistLoadOwner = null;
        currentSavedMusicList = null;
        playlist.clear();
        playlistMap.clear();
        shuffledPlaylist.clear();
        playlistClassGuid = 0;
        playlistEndReached[0] = playlistEndReached[1] = false;
        playlistMergeDialogId = 0;
        playlistTopicId = 0;
        playlistMaxId[0] = playlistMaxId[1] = Integer.MAX_VALUE;
        loadingPlaylist = false;
        loadingPlaylistDialogId = 0;
        loadingPlaylistMaxId = 0;
        playlistGlobalSearchParams = null;
        clearMusicPlaylistState();
    }

    public void startMediaObserver() {
        ApplicationLoader.applicationHandler.removeCallbacks(stopMediaObserverRunnable);
        startObserverToken++;
        try {
            if (internalObserver == null) {
                ApplicationLoader.applicationContext.getContentResolver().registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, externalObserver = new ExternalObserver());
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        try {
            if (externalObserver == null) {
                ApplicationLoader.applicationContext.getContentResolver().registerContentObserver(MediaStore.Images.Media.INTERNAL_CONTENT_URI, false, internalObserver = new InternalObserver());
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    public void stopMediaObserver() {
        if (stopMediaObserverRunnable == null) {
            stopMediaObserverRunnable = new StopMediaObserverRunnable();
        }
        stopMediaObserverRunnable.currentObserverToken = startObserverToken;
        ApplicationLoader.applicationHandler.postDelayed(stopMediaObserverRunnable, 5000);
    }

    private void processMediaObserver(Uri uri) {
        Cursor cursor = null;
        try {
            Point size = AndroidUtilities.getRealScreenSize();

            cursor = ApplicationLoader.applicationContext.getContentResolver().query(uri, mediaProjections, null, null, "date_added DESC LIMIT 1");
            final ArrayList<Long> screenshotDates = new ArrayList<>();
            if (cursor != null) {
                while (cursor.moveToNext()) {
                    String val = "";
                    String data = cursor.getString(0);
                    String display_name = cursor.getString(1);
                    String album_name = cursor.getString(2);
                    long date = cursor.getLong(3);
                    String title = cursor.getString(4);
                    int photoW = cursor.getInt(5);
                    int photoH = cursor.getInt(6);
                    if (data != null && data.toLowerCase().contains("screenshot") ||
                            display_name != null && display_name.toLowerCase().contains("screenshot") ||
                            album_name != null && album_name.toLowerCase().contains("screenshot") ||
                            title != null && title.toLowerCase().contains("screenshot")) {
                        try {
                            if (photoW == 0 || photoH == 0) {
                                BitmapFactory.Options bmOptions = new BitmapFactory.Options();
                                bmOptions.inJustDecodeBounds = true;
                                BitmapFactory.decodeFile(data, bmOptions);
                                photoW = bmOptions.outWidth;
                                photoH = bmOptions.outHeight;
                            }
                            if (photoW <= 0 || photoH <= 0 || (photoW == size.x && photoH == size.y || photoH == size.x && photoW == size.y)) {
                                screenshotDates.add(date);
                            }
                        } catch (Exception e) {
                            screenshotDates.add(date);
                        }
                    }
                }
                cursor.close();
            }
            if (!screenshotDates.isEmpty()) {
                AndroidUtilities.runOnUIThread(() -> {
                    NotificationCenter.getInstance(lastChatAccount).postNotificationName(NotificationCenter.screenshotTook);
                    checkScreenshots(screenshotDates);
                });
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            try {
                if (cursor != null) {
                    cursor.close();
                }
            } catch (Exception ignore) {

            }
        }
    }

    private void checkScreenshots(ArrayList<Long> dates) {
        if (dates == null || dates.isEmpty() || lastChatEnterTime == 0 || (lastUser == null && !(lastSecretChat instanceof TLRPC.TL_encryptedChat))) {
            return;
        }
        long dt = 2000;
        boolean send = false;
        for (int a = 0; a < dates.size(); a++) {
            Long date = dates.get(a);
            if (lastMediaCheckTime != 0 && date <= lastMediaCheckTime) {
                continue;
            }

            if (date >= lastChatEnterTime) {
                if (lastChatLeaveTime == 0 || date <= lastChatLeaveTime + dt) {
                    lastMediaCheckTime = Math.max(lastMediaCheckTime, date);
                    send = true;
                }
            }
        }
        if (send) {
            if (lastSecretChat != null) {
                SecretChatHelper.getInstance(lastChatAccount).sendScreenshotMessage(lastSecretChat, lastChatVisibleMessages, null);
            } else {
                SendMessagesHelper.getInstance(lastChatAccount).sendScreenshotMessage(lastUser, lastMessageId, null);
            }
        }
    }

    public void setLastVisibleMessageIds(int account, long enterTime, long leaveTime, TLRPC.User user, TLRPC.EncryptedChat encryptedChat, ArrayList<Long> visibleMessages, int visibleMessage) {
        lastChatEnterTime = enterTime;
        lastChatLeaveTime = leaveTime;
        lastChatAccount = account;
        lastSecretChat = encryptedChat;
        lastUser = user;
        lastMessageId = visibleMessage;
        lastChatVisibleMessages = visibleMessages;
    }

    private static boolean isDeletedPlaybackMessage(MessageObject message, int account, long channelId, Collection<Integer> ids) {
        return message != null && message.currentAccount == account && message.messageOwner.peer_id != null
                && message.messageOwner.peer_id.channel_id == channelId && ids.contains(message.getId());
    }
    @SuppressWarnings("unchecked")
    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.fileLoadFailed || id == NotificationCenter.httpFileDidFailedLoad) {
            pendingPlaybackFileFailed(account, (String) args[0]);
        } else if (id == NotificationCenter.fileLoaded || id == NotificationCenter.httpFileDidLoad) {
            String fileName = (String) args[0];
            if (playingMessageObject != null && playingMessageObject.currentAccount == account) {
                String file = FileLoader.getAttachFileName(playingMessageObject.getDocument());
                if (file.equals(fileName)) {
                    if (downloadingCurrentMessage) {
                        if (!currentPlaybackIntent(playingMessageObject)) {
                            cleanupPlayer(false, true, false, false, playingMessageObject.currentAccount);
                            return;
                        }
                        playMusicAgain = true;
                        PendingMusicReturn returning = pendingMusicReturn;
                        SavedMusicPlaylistState state = savedMusicPlaylistState;
                        if (returning != null && returning.message == playingMessageObject) {
                            resumePendingMusicReturn(returning);
                        } else if (ownsRoundPlayback(state, playingMessageObject, playerNum)) {
                            playMessage(playingMessageObject, false, state, state.roundPaused);
                        } else playMessage(playingMessageObject, isSilent, null, playbackIntentPaused);
                    } else if (playingMessageObject.isMusic()) {
                        File cacheFile = FileLoader.getInstance(playingMessageObject.currentAccount).getPathToMessage(playingMessageObject.messageOwner);
                        loadAudioInfoAsync(playingMessageObject, cacheFile);
                    }
                }
            }
        } else if (id == NotificationCenter.messagesDeleted) {
            boolean scheduled = (Boolean) args[2];
            if (scheduled) {
                return;
            }
            long channelId = (Long) args[1];
            ArrayList<Integer> markAsDeletedMessages = (ArrayList<Integer>) args[0];
            if (isDeletedPlaybackMessage(playingMessageObject, account, channelId, markAsDeletedMessages)) {
                        cleanupPlayer(true, true);
                    }
            if (voiceMessagesPlaylist != null && !voiceMessagesPlaylist.isEmpty()) {
                final HashSet<Integer> deletedIds = new HashSet<>(markAsDeletedMessages);
                for (int a = voiceMessagesPlaylist.size() - 1; a >= 0; a--) {
                    MessageObject messageObject = voiceMessagesPlaylist.get(a);
                    if (isDeletedPlaybackMessage(messageObject, account, channelId, deletedIds)) {
                        voiceMessagesPlaylist.remove(a);
                        voiceMessagesPlaylistMap.remove(playlistKey(messageObject));
                    }
                }
            }
        } else if (id == NotificationCenter.removeAllMessagesFromDialog) {
            long did = (Long) args[0];
            if (playingMessageObject != null && playingMessageObject.currentAccount == account && playingMessageObject.getDialogId() == did) {
                cleanupPlayer(false, true);
            }
        } else if (id == NotificationCenter.musicDidLoad) {
            if (isCurrentMusicLoad(account, args) && !playlistLoadOwner.cacheLoaded) {
                playlistLoadOwner.cacheLoaded = true;
                MessageObject anchor = playlistAnchor();
                ArrayList<MessageObject> arrayListBegin = (ArrayList<MessageObject>) args[1];
                ArrayList<MessageObject> arrayListEnd = (ArrayList<MessageObject>) args[2];
                for (int part = 0; part < 2; part++) {
                    ArrayList<MessageObject> objects = part == 0 ? arrayListBegin : arrayListEnd;
                    for (int a = 0; a < objects.size(); a++) {
                        MessageObject object = objects.get(a);
                        if (object.currentAccount != account || object.getDialogId() != playlistLoadOwner.dialogId
                                || !object.isMusic() || object.isVoiceOnce()) {
                            continue;
                        }
                        if (!playlistMap.containsKey(playlistKey(object))) {
                            playlist.add(object);
                            playlistMap.put(playlistKey(object), object);
                        }
                        if (object.getId() > 0) {
                            playlistMaxId[0] = Math.min(playlistMaxId[0], object.getId());
                        }
                    }
                }
                sortPlaylist();
                updatePlaylistIndex(anchor);
            }
        } else if (id == NotificationCenter.mediaDidLoad) {
            int guid = (Integer) args[3];
            long did = (Long) args[0];
            if (isCurrentPlaylistOwner(playlistLoadOwner) && account == playlistLoadOwner.account
                    && guid == playlistClassGuid && playlistGlobalSearchParams == null && loadingPlaylist
                    && did == loadingPlaylistDialogId && (Integer) args[4] == MediaDataController.MEDIA_MUSIC
                    && (Integer) args[7] == playlistPageRequestIndex && !(Boolean) args[6]) {
                MessageObject anchor = playlistAnchor();
                ArrayList<MessageObject> arr = (ArrayList<MessageObject>) args[2];
                int loadIndex = did == playlistLoadOwner.dialogId ? 0 : 1;
                int nextMaxId = loadingPlaylistMaxId;
                int addedCount = 0;
                for (int a = 0; a < arr.size(); a++) {
                    MessageObject message = arr.get(a);
                    if (message.currentAccount != account || message.getDialogId() != did) {
                        continue;
                    }
                    if (message.getId() > 0) {
                        nextMaxId = Math.min(nextMaxId, message.getId());
                    }
                    if (!message.isMusic() || message.isVoiceOnce() || playlistMap.containsKey(playlistKey(message))) {
                        continue;
                    }
                    addedCount++;
                    playlist.add(0, message);
                    playlistMap.put(playlistKey(message), message);
                }
                playlistMaxId[loadIndex] = Math.min(playlistMaxId[loadIndex], nextMaxId);
                playlistEndReached[loadIndex] = (Boolean) args[5] || arr.isEmpty() || nextMaxId == loadingPlaylistMaxId;
                sortPlaylist();
                updatePlaylistIndex(anchor);
                loadingPlaylist = false;
                if (addedCount != 0) {
                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.moreMusicDidLoad, addedCount);
                } else if (!playlistEndReached[loadIndex] || loadIndex == 0 && playlistMergeDialogId != 0 && !playlistEndReached[1]) {
                    loadMoreMusic();
                }
            }
        } else if (id == NotificationCenter.didReceiveNewMessages) {
            boolean scheduled = (Boolean) args[2];
            if (scheduled) {
                return;
            }
            if (voiceMessagesPlaylist != null && !voiceMessagesPlaylist.isEmpty()) {
                MessageObject messageObject = voiceMessagesPlaylist.get(0);
            long did = (Long) args[0];
                if (messageObject.currentAccount == account && did == messageObject.getDialogId()) {
                    ArrayList<MessageObject> arr = (ArrayList<MessageObject>) args[1];
                    for (int a = 0; a < arr.size(); a++) {
                        messageObject = arr.get(a);
                        if (messageObject.currentAccount == account && messageObject.getDialogId() == did
                                && (messageObject.isVoice() || messageObject.isRoundVideo()) && !messageObject.isVoiceOnce()
                                && !messageObject.isRoundOnce() && (!voiceMessagesPlaylistUnread || messageObject.isContentUnread() && !messageObject.isOut())
                                && !voiceMessagesPlaylistMap.containsKey(playlistKey(messageObject))) {
                            voiceMessagesPlaylist.add(messageObject);
                            voiceMessagesPlaylistMap.put(playlistKey(messageObject), messageObject);
                        }
                    }
                }
            }
        } else if (id == NotificationCenter.playerDidStartPlaying) {
            VideoPlayer p = (VideoPlayer) args[0];
            if (!isCurrentPlayer(p)) {
                cancelAutomaticMusicReturn();
                MessageObject message = getPlayingMessageObject();
                if(message != null && isPlayingMessage(message) && !isMessagePaused() && (message.isMusic() || message.isVoice())){
                    wasPlayingAudioBeforePause = true;
                }
                pauseMessage(message);
            }
        } else if (id == NotificationCenter.musicListLoaded) {
            if (currentSavedMusicList != null && args[0] == currentSavedMusicList) {
                long generation = musicReturnIntentGeneration.get();
                PendingMusicReturn returnOwner = pendingMusicReturn;
                SavedMusicPlaylistState bookmark = savedMusicPlaylistState != null ? savedMusicPlaylistState
                        : returnOwner == null ? null : returnOwner.state;
                boolean spoken = ownsRoundPlayback(bookmark, playingMessageObject, playerNum);
                boolean returning = returnOwner != null && returnOwner.message == playingMessageObject && downloadingCurrentMessage;
                boolean unlistedMusic = playingMessageObject != null && playingMessageObject.isMusic()
                        && !containsMusicPlaylistMessage(playingMessageObject);
                int addedCount = currentSavedMusicList.list.size() - playlist.size();
                playlist.clear();
                playlist.addAll(currentSavedMusicList.list);
                sortPlaylist();
                if (spoken || returning) {
                    int index = -1;
                    for (int i = 0; i < playlist.size(); ++i) {
                        if (bookmark.matchesSong(playlist.get(i))) { index = i; break; }
                    }
                    boolean valid = (bookmark.standalone || index >= 0) && bookmark.currentSource();
                    if (generation != musicReturnIntentGeneration.get() || spoken && savedMusicPlaylistState != bookmark
                            || returning && pendingMusicReturn != returnOwner) return;
                    if (!valid) {
                        bookmark.resumeAllowed = false;
                        if (returning) retirePendingMusicReturn(true);
                    } else if (!bookmark.standalone) {
                        currentPlaylistNum = index;
                        if (returning) {
                            returnOwner.message = playlist.get(index);
                            playingMessageObject = returnOwner.message;
                            restoreMusicSeek(bookmark, returnOwner.message);
                        }
                    }
                    if (SharedConfig.shuffleMusic) buildShuffledPlayList();
                    if (addedCount != 0) NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.moreMusicDidLoad, addedCount);
                    return;
                }
                if (unlistedMusic) {
                    currentPlaylistNum = -1;
                    if (SharedConfig.shuffleMusic) buildShuffledPlayList();
                    if (addedCount != 0) NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.moreMusicDidLoad, addedCount);
                    return;
                }
                if (SharedConfig.shuffleMusic) {
                    buildShuffledPlayList();
                } else if (playingMessageObject != null) {
                    int newIndex = playlist.indexOf(playingMessageObject);
                    if (newIndex >= 0) {
                        currentPlaylistNum = newIndex;
                    } else {
                        if (currentPlaylistNum < 0 || currentPlaylistNum >= playlist.size()) {
                            currentPlaylistNum = 0;
                        }
                        if (playlist.size() == 0) {
                            cleanupPlayback();
                        } else {
                            playMessage(playlist.get(0));
                        }
                    }
                }
                if (addedCount != 0 && playingMessageObject != null) {
                    NotificationCenter.getInstance(playingMessageObject.currentAccount).postNotificationName(NotificationCenter.moreMusicDidLoad, addedCount);
                }
            }
        }
    }

    public boolean isRecordingAudio() {
        return recordStartRunnable != null || audioRecorderV2StartRunnable != null || recordingAudio != null;
    }

    public int getRecordingGuid() {
        return recordingGuid;
    }

    private boolean isNearToSensor(float value) {
        return value < 5.0f && value != proximitySensor.getMaximumRange();
    }

    public boolean isRecordingOrListeningByProximity() {
        return proximityTouched && (isRecordingAudio() || playingMessageObject != null && (playingMessageObject.isVoice() || playingMessageObject.isRoundVideo()));
    }

    private boolean forbidRaiseToListen() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                AudioDeviceInfo[] devices = NotificationsController.audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS);
                for (AudioDeviceInfo device : devices) {
                    final int type = device.getType();
                    if ((
                        type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                        type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                        type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                        type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
                        type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                        type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                    ) && device.isSink()) {
                        return true;
                    }
                }
                return false;
            } else {
                return NotificationsController.audioManager.isWiredHeadsetOn() || NotificationsController.audioManager.isBluetoothA2dpOn() || NotificationsController.audioManager.isBluetoothScoOn();
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return false;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!sensorsStarted || VoIPService.getSharedInstance() != null) {
            return;
        }
        if (event.sensor.getType() == Sensor.TYPE_PROXIMITY) {
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("proximity changed to " + event.values[0] + " max value = " + event.sensor.getMaximumRange());
            }
            if (lastProximityValue != event.values[0]) {
                proximityHasDifferentValues = true;
            }
            lastProximityValue = event.values[0];
            if (proximityHasDifferentValues) {
                proximityTouched = isNearToSensor(event.values[0]);
            }
        } else if (event.sensor == accelerometerSensor) {
            final double alpha = lastTimestamp == 0 ? 0.98f : 1.0 / (1.0 + (event.timestamp - lastTimestamp) / 1000000000.0);
            final float alphaFast = 0.8f;
            lastTimestamp = event.timestamp;
            gravity[0] = (float) (alpha * gravity[0] + (1.0 - alpha) * event.values[0]);
            gravity[1] = (float) (alpha * gravity[1] + (1.0 - alpha) * event.values[1]);
            gravity[2] = (float) (alpha * gravity[2] + (1.0 - alpha) * event.values[2]);
            gravityFast[0] = (alphaFast * gravity[0] + (1.0f - alphaFast) * event.values[0]);
            gravityFast[1] = (alphaFast * gravity[1] + (1.0f - alphaFast) * event.values[1]);
            gravityFast[2] = (alphaFast * gravity[2] + (1.0f - alphaFast) * event.values[2]);

            linearAcceleration[0] = event.values[0] - gravity[0];
            linearAcceleration[1] = event.values[1] - gravity[1];
            linearAcceleration[2] = event.values[2] - gravity[2];
        } else if (event.sensor == linearSensor) {
            linearAcceleration[0] = event.values[0];
            linearAcceleration[1] = event.values[1];
            linearAcceleration[2] = event.values[2];
        } else if (event.sensor == gravitySensor) {
            gravityFast[0] = gravity[0] = event.values[0];
            gravityFast[1] = gravity[1] = event.values[1];
            gravityFast[2] = gravity[2] = event.values[2];
        }
        final float minDist = 15.0f;
        final int minCount = 6;
        final int countLessMax = 10;
        if (event.sensor == linearSensor || event.sensor == gravitySensor || event.sensor == accelerometerSensor) {
            float val = gravity[0] * linearAcceleration[0] + gravity[1] * linearAcceleration[1] + gravity[2] * linearAcceleration[2];
            if (raisedToBack != minCount) {
                if (val > 0 && previousAccValue > 0 || val < 0 && previousAccValue < 0) {
                    boolean goodValue;
                    int sign;
                    if (val > 0) {
                        goodValue = val > minDist;
                        sign = 1;
                    } else {
                        goodValue = val < -minDist;
                        sign = 2;
                    }
                    if (raisedToTopSign != 0 && raisedToTopSign != sign) {
                        if (raisedToTop == minCount && goodValue) {
                            if (raisedToBack < minCount) {
                                raisedToBack++;
                                if (raisedToBack == minCount) {
                                    raisedToTop = 0;
                                    raisedToTopSign = 0;
                                    countLess = 0;
                                    timeSinceRaise = System.currentTimeMillis();
                                    if (BuildVars.LOGS_ENABLED && BuildVars.DEBUG_PRIVATE_VERSION) {
                                        FileLog.d("motion detected");
                                    }
                                }
                            }
                        } else {
                            if (!goodValue) {
                                countLess++;
                            }
                            if (countLess == countLessMax || raisedToTop != minCount || raisedToBack != 0) {
                                raisedToTop = 0;
                                raisedToTopSign = 0;
                                raisedToBack = 0;
                                countLess = 0;
                            }
                        }
                    } else {
                        if (goodValue && raisedToBack == 0 && (raisedToTopSign == 0 || raisedToTopSign == sign)) {
                            if (raisedToTop < minCount && !proximityTouched) {
                                raisedToTopSign = sign;
                                raisedToTop++;
                                if (raisedToTop == minCount) {
                                    countLess = 0;
                                }
                            }
                        } else {
                            if (!goodValue) {
                                countLess++;
                            }
                            if (raisedToTopSign != sign || countLess == countLessMax || raisedToTop != minCount || raisedToBack != 0) {
                                raisedToBack = 0;
                                raisedToTop = 0;
                                raisedToTopSign = 0;
                                countLess = 0;
                            }
                        }
                    }
                }
                /*if (val > 0 && previousAccValue > 0) {
                    if (val > minDist && raisedToBack == 0) {
                        if (raisedToTop < minCount && !proximityTouched) {
                            raisedToTop++;
                            if (raisedToTop == minCount) {
                                countLess = 0;
                            }
                        }
                    } else {
                        if (val < minDist) {
                            countLess++;
                        }
                        if (countLess == countLessMax || raisedToTop != minCount || raisedToBack != 0) {
                            raisedToBack = 0;
                            raisedToTop = 0;
                            countLess = 0;
                        }
                    }
                } else if (val < 0 && previousAccValue < 0) {
                    if (raisedToTop == minCount && val < -minDist) {
                        if (raisedToBack < minCount) {
                            raisedToBack++;
                            if (raisedToBack == minCount) {
                                raisedToTop = 0;
                                countLess = 0;
                                timeSinceRaise = System.currentTimeMillis();
                                if (BuildVars.LOGS_ENABLED && BuildVars.DEBUG_PRIVATE_VERSION) {
                                    FileLog.e("motion detected");
                                }
                            }
                        }
                    } else {
                        if (val > -minDist) {
                            countLess++;
                        }
                        if (countLess == countLessMax || raisedToTop != minCount || raisedToBack != 0) {
                            raisedToTop = 0;
                            raisedToBack = 0;
                            countLess = 0;
                        }
                    }
                }*/
                /*if (BuildVars.LOGS_ENABLED && BuildVars.DEBUG_PRIVATE_VERSION) {
                    FileLog.e("raise2 to top = " + raisedToTop + " to back = " + raisedToBack + " val = " + val + " countLess = " + countLess);
                }*/
            }
            previousAccValue = val;
            accelerometerVertical = gravityFast[1] > 2.5f && Math.abs(gravityFast[2]) < 4.0f && Math.abs(gravityFast[0]) > 1.5f;
            /*if (BuildVars.LOGS_ENABLED && BuildVars.DEBUG_PRIVATE_VERSION) {
                FileLog.d(accelerometerVertical + "    val = " + val + " acc (" + linearAcceleration[0] + ", " + linearAcceleration[1] + ", " + linearAcceleration[2] + ") grav (" + gravityFast[0] + ", " + gravityFast[1] + ", " + gravityFast[2] + ")");
            }*/
        }
        if (raisedToBack == minCount || accelerometerVertical) {
            lastAccelerometerDetected = System.currentTimeMillis();
        }
        final boolean allowRecording = !manualRecording && playingMessageObject == null && SharedConfig.enabledRaiseTo(true) && ApplicationLoader.isScreenOn && !inputFieldHasText && allowStartRecord && raiseChat != null && !callInProgress;
        final boolean allowListening = SharedConfig.enabledRaiseTo(false) && playingMessageObject != null && (playingMessageObject.isVoice() || playingMessageObject.isRoundVideo());
        final boolean proximityDetected = proximityTouched;
        final boolean accelerometerDetected = raisedToBack == minCount || accelerometerVertical || System.currentTimeMillis() - lastAccelerometerDetected < 60;
        final boolean alreadyPlaying = useFrontSpeaker || raiseToEarRecord;
        final boolean wakelockAllowed = (
            accelerometerDetected ||
            alreadyPlaying
        ) && !forbidRaiseToListen() && !VoIPService.isAnyKindOfCallActive() && (allowRecording || allowListening) && !PhotoViewer.getInstance().isVisible();
        if (proximityWakeLock != null) {
            final boolean held = proximityWakeLock.isHeld();
            if (held && !wakelockAllowed) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("wake lock releasing (proximityDetected=" + proximityDetected + ", accelerometerDetected=" + accelerometerDetected + ", alreadyPlaying=" + alreadyPlaying + ")");
                }
                proximityWakeLock.release();
            } else if (!held && wakelockAllowed) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("wake lock acquiring (proximityDetected=" + proximityDetected + ", accelerometerDetected=" + accelerometerDetected + ", alreadyPlaying=" + alreadyPlaying + ")");
                }
                proximityWakeLock.acquire();
            }
        }
        if (proximityTouched && wakelockAllowed) {
            if (allowRecording && recordStartRunnable == null) {
                if (!raiseToEarRecord) {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("start record");
                    }
                    useFrontSpeaker = true;
                    if (recordingAudio != null || !raiseChat.playFirstUnreadVoiceMessage()) {
                        raiseToEarRecord = true;
                        useFrontSpeaker = false;
                        raiseToSpeakUpdated(true);
                    }
                    if (useFrontSpeaker) {
                        setUseFrontSpeaker(true);
                    }
//                    ignoreOnPause = true;
//                    if (proximityHasDifferentValues && proximityWakeLock != null && !proximityWakeLock.isHeld()) {
//                        proximityWakeLock.acquire();
//                    }
                }
            } else if (allowListening) {
                if (!useFrontSpeaker) {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("start listen");
                    }
//                    if (proximityHasDifferentValues && proximityWakeLock != null && !proximityWakeLock.isHeld()) {
//                        proximityWakeLock.acquire();
//                    }
                    setUseFrontSpeaker(true);
                    startAudioAgain(false);
//                    ignoreOnPause = true;
                }
            }
            raisedToBack = 0;
            raisedToTop = 0;
            raisedToTopSign = 0;
            countLess = 0;
        } else if (proximityTouched && ((accelerometerSensor == null || linearSensor == null) && gravitySensor == null) && !VoIPService.isAnyKindOfCallActive()) {
            if (playingMessageObject != null && !ApplicationLoader.mainInterfacePaused && allowListening) {
                if (!useFrontSpeaker && !manualRecording && !forbidRaiseToListen()) {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("start listen by proximity only");
                    }
//                    if (proximityHasDifferentValues && proximityWakeLock != null && !proximityWakeLock.isHeld()) {
//                        proximityWakeLock.acquire();
//                    }
                    setUseFrontSpeaker(true);
                    startAudioAgain(false);
//                    ignoreOnPause = true;
                }
            }
        } else if (!proximityTouched && !manualRecording) {
            if (raiseToEarRecord) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("stop record");
                }
                raiseToSpeakUpdated(false);
                raiseToEarRecord = false;
                ignoreOnPause = false;
//                if (!ignoreAccelerometerGestures() && proximityHasDifferentValues && proximityWakeLock != null && proximityWakeLock.isHeld()) {
//                    proximityWakeLock.release();
//                }
            } else if (useFrontSpeaker) {
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("stop listen");
                }
                useFrontSpeaker = false;
                startAudioAgain(true);
                ignoreOnPause = false;
//                if (!ignoreAccelerometerGestures() && proximityHasDifferentValues && proximityWakeLock != null && proximityWakeLock.isHeld()) {
//                    proximityWakeLock.release();
//                }
            }
        }
        if (timeSinceRaise != 0 && raisedToBack == minCount && Math.abs(System.currentTimeMillis() - timeSinceRaise) > 1000) {
            raisedToBack = 0;
            raisedToTop = 0;
            raisedToTopSign = 0;
            countLess = 0;
            timeSinceRaise = 0;
        }
    }

    private void raiseToSpeakUpdated(boolean raised) {
        if (recordingAudio != null) {
            toggleRecordingPause(false);
        } else if (raised) {
            startRecording(raiseChat.getCurrentAccount(), raiseChat.getDialogId(), null, raiseChat.getThreadMessage(), null, raiseChat.getClassGuid(), false, raiseChat != null ? raiseChat.getMessageChatSendParams() : null, raiseChat != null ? raiseChat.getSendMonoForumPeerId(): 0, raiseChat != null ? raiseChat.getSendMessageSuggestionParams(): null);
        } else {
            stopRecording(2, false, 0, false, 0);
        }
    }

    private void setUseFrontSpeaker(boolean value) {
        useFrontSpeaker = value;
        AudioManager audioManager = NotificationsController.audioManager;
        if (useFrontSpeaker) {
            audioManager.setBluetoothScoOn(false);
            audioManager.setSpeakerphoneOn(false);
        } else {
            audioManager.setSpeakerphoneOn(true);
        }
    }

    public void startRecordingIfFromSpeaker() {
        if (!useFrontSpeaker || raiseChat == null || !allowStartRecord || !SharedConfig.enabledRaiseTo(true)) {
            return;
        }
        raiseToEarRecord = true;
        startRecording(raiseChat.getCurrentAccount(), raiseChat.getDialogId(), null, raiseChat.getThreadMessage(), null, raiseChat.getClassGuid(), false, raiseChat != null ? raiseChat.getMessageChatSendParams() : null, raiseChat != null ? raiseChat.getSendMonoForumPeerId(): 0, raiseChat != null ? raiseChat.getSendMessageSuggestionParams(): null);
        ignoreOnPause = true;
    }

    private void startAudioAgain(boolean paused) {
        if (playingMessageObject == null) {
            return;
        }

        MessageObject routeMessage = playingMessageObject;
        int routePlayerNum = playerNum;
        long routeGeneration = musicReturnIntentGeneration.get();
        long routeIntent = playbackIntentGeneration.get();
        SavedMusicPlaylistState routeOwner = savedMusicPlaylistState;
        if (!ownsRoundPlayback(routeOwner, routeMessage, routePlayerNum)) routeOwner = null;
        NotificationCenter.getInstance(playingMessageObject.currentAccount).postNotificationName(NotificationCenter.audioRouteChanged, useFrontSpeaker);
        if (playingMessageObject != routeMessage || playerNum != routePlayerNum || routeGeneration != musicReturnIntentGeneration.get()) return;
        if (pendingMusicReturn != null && pendingMusicReturn.message == routeMessage) {
            if (paused) pauseMessage(routeMessage);
            return;
        }
        if (videoPlayer != null) {
            videoPlayer.setStreamType(useFrontSpeaker ? AudioManager.STREAM_VOICE_CALL : AudioManager.STREAM_MUSIC);
            if (!paused) {
                if (!checkAudioFocus(routeMessage) || playingMessageObject != routeMessage || playerNum != routePlayerNum
                        || routeGeneration != musicReturnIntentGeneration.get()) return;
                if (videoPlayer.getCurrentPosition() < 1000) {
                    videoPlayer.seekTo(0, playingMessageObject != null && playingMessageObject.isRoundVideo());
                }
                videoPlayer.play();
            } else {
                pauseMessage(playingMessageObject);
            }
        } else {
            final MessageObject currentMessageObject = playingMessageObject;
            float progress = playingMessageObject.audioProgress;
            int duration = playingMessageObject.audioPlayerDuration;
            if (paused || audioPlayer == null || !audioPlayer.isPlaying() || duration * progress > 1f) {
                currentMessageObject.audioProgress = progress;
            } else {
                currentMessageObject.audioProgress = 0;
            }
            if (routeOwner != null) {
                routeOwner.roundPaused |= paused;
                cleanupPlayer(false, false, false, false, -1, routeOwner);
                if (savedMusicPlaylistState == routeOwner && playingMessageObject == null
                        && routeGeneration == musicReturnIntentGeneration.get()) {
                    playMessage(currentMessageObject, isSilent, routeOwner, routeOwner.roundPaused);
                }
                if (playingMessageObject == null) retirePlaybackIntent(currentMessageObject, routeIntent);
                return;
            }
            cleanupPlayer(false, true, false, false, -1, null, true);
            if (playingMessageObject == null && routeGeneration == musicReturnIntentGeneration.get()) {
                playMessage(currentMessageObject, isSilent, null, paused);
            }
            if (playingMessageObject == null) retirePlaybackIntent(currentMessageObject, routeIntent);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {

    }

    public void setInputFieldHasText(boolean value) {
        inputFieldHasText = value;
    }

    public void setAllowStartRecord(boolean value) {
        allowStartRecord = value;
    }

    public void startRaiseToEarSensors(ChatActivity chatActivity) {
        if (chatActivity == null || accelerometerSensor == null && (gravitySensor == null || linearAcceleration == null) || proximitySensor == null) {
            return;
        }
        if (!SharedConfig.enabledRaiseTo(false) && (playingMessageObject == null || !playingMessageObject.isVoice() && !playingMessageObject.isRoundVideo())) {
            return;
        }
        raiseChat = chatActivity;
        if (!sensorsStarted) {
            gravity[0] = gravity[1] = gravity[2] = 0;
            linearAcceleration[0] = linearAcceleration[1] = linearAcceleration[2] = 0;
            gravityFast[0] = gravityFast[1] = gravityFast[2] = 0;
            lastTimestamp = 0;
            previousAccValue = 0;
            raisedToTop = 0;
            raisedToTopSign = 0;
            countLess = 0;
            raisedToBack = 0;
            Utilities.globalQueue.postRunnable(() -> {
                if (gravitySensor != null) {
                    sensorManager.registerListener(MediaController.this, gravitySensor, 30000);
                }
                if (linearSensor != null) {
                    sensorManager.registerListener(MediaController.this, linearSensor, 30000);
                }
                if (accelerometerSensor != null) {
                    sensorManager.registerListener(MediaController.this, accelerometerSensor, 30000);
                }
                sensorManager.registerListener(MediaController.this, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL);
            });
            sensorsStarted = true;
        }
    }

    public void stopRaiseToEarSensors(ChatActivity chatActivity, boolean fromChat, boolean stopRecording) {
        if (ignoreOnPause) {
            ignoreOnPause = false;
            return;
        }
        if (stopRecording) {
            if (recordingAudio != null && !isRecordingPaused()) {
                toggleRecordingPause(false);
            } else {
                stopRecording(fromChat ? 2 : 0, false, 0, false, 0);
            }
        }
        if (!sensorsStarted || ignoreOnPause || accelerometerSensor == null && (gravitySensor == null || linearAcceleration == null) || proximitySensor == null || raiseChat != chatActivity) {
            return;
        }
        raiseChat = null;
        sensorsStarted = false;
        accelerometerVertical = false;
        proximityTouched = false;
        raiseToEarRecord = false;
        useFrontSpeaker = false;
        Utilities.globalQueue.postRunnable(() -> {
            if (linearSensor != null) {
                sensorManager.unregisterListener(MediaController.this, linearSensor);
            }
            if (gravitySensor != null) {
                sensorManager.unregisterListener(MediaController.this, gravitySensor);
            }
            if (accelerometerSensor != null) {
                sensorManager.unregisterListener(MediaController.this, accelerometerSensor);
            }
            sensorManager.unregisterListener(MediaController.this, proximitySensor);
        });
        if (proximityWakeLock != null && proximityWakeLock.isHeld()) {
            proximityWakeLock.release();
        }
    }

    public void cleanupPlayer(boolean notify, boolean stopService) {
        cleanupPlayer(notify, stopService, false, false);
    }

    public void cleanupPlayer(boolean notify, boolean stopService, boolean byVoiceEnd, boolean transferPlayerToPhotoViewer) {
        PendingMusicReturn returning = pendingMusicReturn;
        cancelAutomaticMusicReturn();
        long generation = musicReturnIntentGeneration.get();
        retirePendingMusicReturn(true);
        if (generation != musicReturnIntentGeneration.get()) return;
        if (transferPlayerToPhotoViewer) clearMusicPlaylistState();
        cleanupPlayer(notify, stopService, byVoiceEnd, transferPlayerToPhotoViewer, -1);
        if (returning != null && returning.state.currentSource() && generation == musicReturnIntentGeneration.get()) {
            restoreMusicSeek(returning.state, returning.message);
        }
    }

    private void cleanupPlayer(boolean notify, boolean stopService, boolean byVoiceEnd, boolean transferPlayerToPhotoViewer, int cleanupAccount) {
        cleanupPlayer(notify, stopService, byVoiceEnd, transferPlayerToPhotoViewer, cleanupAccount, null);
    }

    private void cleanupPlayer(boolean notify, boolean stopService, boolean byVoiceEnd, boolean transferPlayerToPhotoViewer, int cleanupAccount, SavedMusicPlaylistState completion) {
        cleanupPlayer(notify, stopService, byVoiceEnd, transferPlayerToPhotoViewer, cleanupAccount, completion, completion != null);
    }

    private void cleanupPlayer(boolean notify, boolean stopService, boolean byVoiceEnd, boolean transferPlayerToPhotoViewer, int cleanupAccount, SavedMusicPlaylistState completion, boolean keepFocus) {
        final long returnGeneration = musicReturnIntentGeneration.get();
        final MessageObject retiringIntent = playbackIntentMessage;
        final long retiringGeneration = playbackIntentGeneration.get();
        if (completion == null && stopService && cleanupAccount < 0 && !transferPlayerToPhotoViewer && restoreMusicPlaylistState()) {
            return;
        }

        final boolean playNextVoice = byVoiceEnd && !app.nimarkogram.messenger.AppConfig.disableVoiceMessagesAutoNext;
        if (audioPlayer != null) {
            ++playerNum;
            audioPlayer.setAudioVisualizerDelegate(null);
            audioPlayer.markPlaybackFinishing();
            if (reporter != null) {
                reporter.destroy();
                reporter = null;
            }
            if (audioVolumeAnimator != null) {
                audioVolumeAnimator.removeAllListeners();
                audioVolumeAnimator.removeAllUpdateListeners();
                audioVolumeAnimator.cancel();
                audioVolumeAnimator = null;
            }

            if (!CastSync.isActive() && audioPlayer.isPlaying() && playingMessageObject != null && !playingMessageObject.isVoice()) {
                VideoPlayer playerFinal = audioPlayer;
                ValueAnimator valueAnimator = ValueAnimator.ofFloat(audioVolume, 0);
                valueAnimator.addUpdateListener(valueAnimator1 -> {
                    float volume;
                    if (audioFocus != AUDIO_NO_FOCUS_CAN_DUCK) {
                        volume = VOLUME_NORMAL;
                    } else {
                        volume = VOLUME_DUCK;
                    }
                    playerFinal.setVolume(volume * (float) valueAnimator1.getAnimatedValue());
                });
                valueAnimator.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        try {
                            playerFinal.releasePlayer(true);
                        } catch (Exception e) {
                            FileLog.e(e);
                        }
                    }
                });
                valueAnimator.setDuration(300);
                valueAnimator.start();
            } else {
                try {
                    audioPlayer.releasePlayer(true);
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
            audioPlayer = null;
            Theme.unrefAudioVisualizeDrawable(playingMessageObject);
        } else if (videoPlayer != null) {
            if (playNextVoice && voiceMessagesPlaylist != null && playingMessageObject != null) {
                int next = voiceMessagesPlaylist.indexOf(playingMessageObject) + 1;
                if (next > 0 && next < voiceMessagesPlaylist.size()
                        && voiceMessagesPlaylist.get(next).isRoundVideo()) {
                    retainInlineRoundVideoFrame();
                }
            }
            if (pipRoundVideoView != null && playingMessageObject != null && playingMessageObject.isRoundVideo()) {
                pipRoundVideoView.retainPlaybackFrame();
            }
            ++playerNum;
            ++roundVideoOutputGeneration;
            currentAspectRatioFrameLayout = null;
            currentTextureViewContainer = null;
            currentAspectRatioFrameLayoutReady = false;
            isDrawingWasReady = false;
            roundVideoFrameReadyAt = 0;
            roundVideoFirstFrameRendered = false;
            roundVideoPendingSurface = null;
            currentTextureView = null;
            goingToShowMessageObject = null;
            if (transferPlayerToPhotoViewer) {
                PhotoViewer.getInstance().injectVideoPlayer(videoPlayer);
                goingToShowMessageObject = playingMessageObject;
                NotificationCenter.getInstance(playingMessageObject.currentAccount).postNotificationName(NotificationCenter.messagePlayingGoingToStop, playingMessageObject, true);
            } else {
                final VideoPlayer retiringVideoPlayer = videoPlayer;
                final MessageObject retiringVideoMessage = playingMessageObject;
                final int retiringPlayerNum = playerNum;
                long position = retiringVideoPlayer.getCurrentPosition();
                videoPlayer = null;
                if (retiringVideoMessage != null && retiringVideoMessage.isVideo() && position > 0) {
                    retiringVideoMessage.audioProgressMs = (int) position;
                    NotificationCenter.getInstance(retiringVideoMessage.currentAccount).postNotificationName(NotificationCenter.messagePlayingGoingToStop, retiringVideoMessage, false);
                }
                try {
                    retiringVideoPlayer.releasePlayer(true);
                } catch (Exception e) {
                    FileLog.e(e);
                }
                if (returnGeneration != musicReturnIntentGeneration.get() || playerNum != retiringPlayerNum
                        || playingMessageObject != retiringVideoMessage || videoPlayer != null || audioPlayer != null) return;
            }
            try {
                baseActivity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } catch (Exception e) {
                FileLog.e(e);
            }
            if (playingMessageObject != null && !transferPlayerToPhotoViewer) {
                AndroidUtilities.cancelRunOnUIThread(setLoadingRunnable);
                FileLoader.getInstance(playingMessageObject.currentAccount).removeLoadingVideo(playingMessageObject.getDocument(), true, false);
            }
        }
        stopProgressTimer();
        lastProgress = 0;
        isPaused = false;
        boolean playingNext = false;
        if (playingMessageObject != null) {
            if (downloadingCurrentMessage) {
                downloadingCurrentMessage = false;
                FileLoader.getInstance(playingMessageObject.currentAccount).cancelLoadFile(playingMessageObject.getDocument());
            }
            MessageObject lastFile = playingMessageObject;
            int releasedPlayerNum = playerNum;
            if (notify) {
                playingMessageObject.resetPlayingProgress();
                NotificationCenter.getInstance(lastFile.currentAccount).postNotificationName(NotificationCenter.messagePlayingProgressDidChanged, playingMessageObject.getId(), 0);
                if (returnGeneration != musicReturnIntentGeneration.get() || playingMessageObject != lastFile || playerNum != releasedPlayerNum) return;
            }
            playingMessageObject = null;
            downloadingCurrentMessage = false;
            if (notify) {
                int index = -1;
                if (cleanupAccount < 0 && voiceMessagesPlaylist != null) {
                    if (playNextVoice && (index = voiceMessagesPlaylist.indexOf(lastFile)) >= 0) {
                        voiceMessagesPlaylist.remove(index);
                        voiceMessagesPlaylistMap.remove(playlistKey(lastFile));
                        if (voiceMessagesPlaylist.isEmpty()) {
                            voiceMessagesPlaylist = null;
                            voiceMessagesPlaylistMap = null;
                        }
                    } else {
                        voiceMessagesPlaylist = null;
                        voiceMessagesPlaylistMap = null;
                    }
                }
                if (cleanupAccount < 0 && voiceMessagesPlaylist != null && index >= 0 && index < voiceMessagesPlaylist.size()) {
                    MessageObject nextVoiceMessage = voiceMessagesPlaylist.get(index);
                    if (completion != null) {
                        if (completion == savedMusicPlaylistState && completion.currentSpokenAccount()
                                && nextVoiceMessage.currentAccount == lastFile.currentAccount
                                && (nextVoiceMessage.isRoundVideo() || nextVoiceMessage.isVoice())) {
                            playingNext = playMessage(nextVoiceMessage, false, completion, false);
                        }
                        if (!playingNext && savedMusicPlaylistState == completion) {
                            if (completion.currentSource()) restoreMusicSeek(completion, completion.playingMessage);
                            clearMusicPlaylistState();
                        }
                    } else {
                        playingNext = nextVoiceMessage.currentAccount == lastFile.currentAccount && playMessage(nextVoiceMessage);
                    }
                    if (returnGeneration != musicReturnIntentGeneration.get()) return;
                    if (!nextVoiceMessage.isRoundVideo() && pipRoundVideoView != null) {
                        closePipRoundVideo(true);
                    }
                } else {
                    if ((lastFile.isVoice() || lastFile.isRoundVideo()) && lastFile.getId() != 0) {
                        startRecordingIfFromSpeaker();
                    }
                    NotificationCenter.getInstance(lastFile.currentAccount).postNotificationName(NotificationCenter.messagePlayingDidReset, lastFile.getId(), stopService);
                    if (returnGeneration != musicReturnIntentGeneration.get() || playingMessageObject != null) return;
                    pipSwitchingState = 0;
                    if (pipRoundVideoView != null) {
                        closePipRoundVideo(true);
                    }
                }
            }
            if (stopService && !playingNext) {
                Intent intent = new Intent(ApplicationLoader.applicationContext, MusicPlayerService.class);
                ApplicationLoader.applicationContext.stopService(intent);
            }
        }
        if (returnGeneration != musicReturnIntentGeneration.get()) return;
        if (!playingNext && !keepFocus) retirePlaybackIntent(retiringIntent, retiringGeneration);
        if (notify && !playingNext) {
            clearInlineRoundVideoFrame();
        }
        if (!playingNext && byVoiceEnd && !SharedConfig.enabledRaiseTo(true)) {
            ChatActivity chat = raiseChat;
            stopRaiseToEarSensors(raiseChat, false, false);
            raiseChat = chat;
        }
        if (stopService && !playingNext) {
            CastSync.stop();
        }
    }

    public boolean isGoingToShowMessageObject(MessageObject messageObject) {
        return goingToShowMessageObject == messageObject;
    }

    public void resetGoingToShowMessageObject() {
        goingToShowMessageObject = null;
    }

    private boolean isSamePlayingMessage(MessageObject messageObject) {
        return playingMessageObject != null && playingMessageObject.currentAccount == messageObject.currentAccount
                && playingMessageObject.getDialogId() == messageObject.getDialogId()
                && playingMessageObject.getId() == messageObject.getId()
                && ((playingMessageObject.eventId == 0) == (messageObject.eventId == 0));
    }

    public boolean seekToProgress(MessageObject messageObject, float progress) {
        final MessageObject playingMessageObject = this.playingMessageObject;
        if (audioPlayer == null && videoPlayer == null || messageObject == null || playingMessageObject == null || !isSamePlayingMessage(messageObject)) {
            return false;
        }
        try {
            if (audioPlayer != null) {
                long duration = audioPlayer.getDuration();
                if (duration == C.TIME_UNSET) {
                    seekToProgressPending = progress;
                } else {
                    playingMessageObject.audioProgress = progress;
                    int seekTo = (int) (duration * progress);
                    audioPlayer.seekTo(seekTo);
                    lastProgress = seekTo;
                    if (!ignorePlayerUpdate) {
                        CastSync.seekTo(seekTo);
                    }
                }
            } else if (videoPlayer != null) {
                videoPlayer.seekTo((long) (videoPlayer.getDuration() * progress), playingMessageObject.isRoundVideo());
                if (!ignorePlayerUpdate) {
                    CastSync.seekTo((long) (videoPlayer.getDuration() * progress));
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
        NotificationCenter.getInstance(messageObject.currentAccount).postNotificationName(NotificationCenter.messagePlayingDidSeek, playingMessageObject.getId(), progress);
        return true;
    }

    public boolean seekToProgressMs(MessageObject messageObject, long progressMs) {
        final MessageObject playingMessageObject = this.playingMessageObject;
        if (audioPlayer == null && videoPlayer == null || messageObject == null || playingMessageObject == null || !isSamePlayingMessage(messageObject)) {
            return false;
        }
        long duration = 1;
        try {
            if (audioPlayer != null) {
                duration = audioPlayer.getDuration();
                if (duration != C.TIME_UNSET) {
                    playingMessageObject.audioProgress = Utilities.clamp01((float) progressMs / duration);
                }
                audioPlayer.seekTo(progressMs);
                lastProgress = progressMs;
                if (!ignorePlayerUpdate) {
                    CastSync.seekTo(progressMs);
                }
            } else if (videoPlayer != null) {
                duration = videoPlayer.getDuration();
                videoPlayer.seekTo(progressMs, playingMessageObject.isRoundVideo());
                if (!ignorePlayerUpdate) {
                    CastSync.seekTo(progressMs);
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
        if (duration != 0) {
            NotificationCenter.getInstance(messageObject.currentAccount).postNotificationName(NotificationCenter.messagePlayingDidSeek, playingMessageObject.getId(), Utilities.clamp01((float) progressMs / duration));
        }
        return true;
    }

    public long getProgressMs(MessageObject messageObject) {
        final MessageObject playingMessageObject = this.playingMessageObject;
        if (audioPlayer == null && videoPlayer == null || messageObject == null || playingMessageObject == null || !isSamePlayingMessage(messageObject)) {
            return -1;
        }
        try {
            if (audioPlayer != null) {
                return audioPlayer.getCurrentPosition();
            } else if (videoPlayer != null) {
                return videoPlayer.getCurrentPosition();
            }
        } catch (Exception e) {}
        return -1;
    }

    public long getDuration() {
        if (audioPlayer == null) {
            return 0;
        }
        return audioPlayer.getDuration();
    }

    public MessageObject getPlayingMessageObject() {
        return playingMessageObject;
    }

    public int getPlayingMessageObjectNum() {
        return currentPlaylistNum;
    }

    private void buildShuffledPlayList() {
        if (playlist.isEmpty()) {
            return;
        }
        ArrayList<MessageObject> all = new ArrayList<>(playlist);
        shuffledPlaylist.clear();

        MessageObject messageObject = null;
        if (currentPlaylistNum >= 0 && currentPlaylistNum < playlist.size()) {
            messageObject = playlist.get(currentPlaylistNum);
            all.remove(currentPlaylistNum);
        }

        int count = all.size();
        for (int a = 0; a < count; a++) {
            int index = Utilities.random.nextInt(all.size());
            shuffledPlaylist.add(all.get(index));
            all.remove(index);
        }
        if (messageObject != null) {
            shuffledPlaylist.add(messageObject);
            currentPlaylistNum = shuffledPlaylist.size() - 1;
        }
    }

    public void loadMoreMusic() {
        if (currentSavedMusicList != null) {
            currentSavedMusicList.load();
            return;
        }
        final PlaylistLoadOwner owner = playlistLoadOwner;
        if (loadingPlaylist || !isCurrentPlaylistOwner(owner) || DialogObject.isEncryptedDialog(owner.dialogId) || playlistClassGuid == 0) {
            return;
        }
        if (playlistGlobalSearchParams != null) {
            final PlaylistGlobalSearchParams params = playlistGlobalSearchParams;
            if (!params.endReached && !playlist.isEmpty()) {
                final String requestCursor = params.cursorKey();
                int currentAccount = owner.account;
                TLObject request;
                if (params.dialogId != 0 && params.communityId == 0) {
                    final TLRPC.TL_messages_search req = new TLRPC.TL_messages_search();
                    req.q = params.query;
                    req.limit = 20;
                    req.filter = params.messagesFilter == null ? new TLRPC.TL_inputMessagesFilterEmpty() : params.messagesFilter;
                    req.peer = MessagesController.getInstance(currentAccount).getInputPeer(params.dialogId);
                    req.offset_id = params.offsetId;
                    if (params.minDate > 0) {
                        req.min_date = (int) (params.minDate / 1000);
                    }
                    if (params.maxDate > 0) {
                        req.max_date = (int) (params.maxDate / 1000);
                    }
                    request = req;
                } else {
                    final TLRPC.TL_messages_searchGlobal req = new TLRPC.TL_messages_searchGlobal();
                    req.limit = 20;
                    req.q = params.query;
                    req.filter = params.messagesFilter == null ? new TLRPC.TL_inputMessagesFilterEmpty() : params.messagesFilter;
                    req.community = params.communityPeer != null ? params.communityPeer
                            : MessagesController.getInstance(currentAccount).getInputChannel(params.communityId);
                    req.offset_id = params.offsetId;
                    req.offset_rate = params.nextSearchRate;
                    req.flags |= 1;
                    req.folder_id = params.folderId;
                    req.offset_peer = params.offsetId == 0 ? new TLRPC.TL_inputPeerEmpty()
                            : MessagesController.getInstance(currentAccount).getInputPeer(params.offsetPeerId);
                    if (params.minDate > 0) {
                        req.min_date = (int) (params.minDate / 1000);
                    }
                    if (params.maxDate > 0) {
                        req.max_date = (int) (params.maxDate / 1000);
                    }
                    request = req;
                }
                final int requestIndex = ++playlistPageRequestIndex;
                loadingPlaylist = true;
                int requestId = ConnectionsManager.getInstance(currentAccount).sendRequest(request, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                    if (!isCurrentPlaylistOwner(owner) || playlistGlobalSearchParams != params || !loadingPlaylist
                            || requestIndex != playlistPageRequestIndex || !requestCursor.equals(params.cursorKey())) {
                        return;
                    }
                    if (error != null || !(response instanceof TLRPC.messages_Messages)) {
                    loadingPlaylist = false;
                        return;
                    }

                    TLRPC.messages_Messages res = (TLRPC.messages_Messages) response;
                    MessagesStorage.getInstance(currentAccount).putUsersAndChats(res.users, res.chats, true, true);
                    MessagesController.getInstance(currentAccount).putUsers(res.users, false);
                    MessagesController.getInstance(currentAccount).putChats(res.chats, false);
                    if (!isCurrentPlaylistOwner(owner) || playlistGlobalSearchParams != params || requestIndex != playlistPageRequestIndex) {
                        return;
                    }
                    MessageObject anchor = playlistAnchor();
                    params.deliveredCursors.add(requestCursor);
                    params.nextSearchRate = res.next_rate;
                    int n = res.messages.size();
                    int addedCount = 0;
                    for (int i = 0; i < n; i++) {
                        MessageObject messageObject = new MessageObject(currentAccount, res.messages.get(i), false, true);
                        if (!messageObject.isMusic() || messageObject.isVoiceOnce() || playlistMap.containsKey(playlistKey(messageObject))) {
                            continue;
                        }
                        playlist.add(0, messageObject);
                        playlistMap.put(playlistKey(messageObject), messageObject);
                        addedCount++;
                    }
                    if (n > 0) {
                        TLRPC.Message tail = res.messages.get(n - 1);
                        params.offsetId = tail.id;
                        params.offsetPeerId = MessageObject.getPeerId(tail.peer_id);
                    }
                    params.totalCount = res.count;
                    params.endReached = n == 0 || !params.deliveredCursors.add(params.cursorKey())
                            || res instanceof TLRPC.TL_messages_messages;
                    sortPlaylist();
                    updatePlaylistIndex(anchor);
                    loadingPlaylist = false;
                    if (addedCount != 0) {
                        NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.moreMusicDidLoad, addedCount);
                    } else if (!params.endReached) {
                        loadMoreMusic();
                    }
                }));
                ConnectionsManager.getInstance(currentAccount).bindRequestToGuid(requestId, owner.guid);
            }
            return;
        }
        int loadIndex = !playlistEndReached[0] ? 0 : playlistMergeDialogId != 0 && !playlistEndReached[1] ? 1 : -1;
        if (loadIndex >= 0) {
            loadingPlaylist = true;
            loadingPlaylistDialogId = loadIndex == 0 ? owner.dialogId : owner.mergeDialogId;
            loadingPlaylistMaxId = playlistMaxId[loadIndex];
            final int requestIndex = ++playlistPageRequestIndex;
            MediaDataController.getInstance(owner.account).loadMedia(loadingPlaylistDialogId, 50, loadingPlaylistMaxId, 0, MediaDataController.MEDIA_MUSIC,
                    loadIndex == 0 ? owner.topicId : 0, 1, owner.guid, requestIndex, null, null, () -> {
                        if (playlistLoadOwner == owner && playlistGlobalSearchParams == null && requestIndex == playlistPageRequestIndex) {
                            loadingPlaylist = false;
                        }
                    });
        }
    }

    public boolean setPlaylist(ArrayList<MessageObject> messageObjects, MessageObject current, long mergeDialogId, PlaylistGlobalSearchParams globalSearchParams) {
        return setPlaylist(messageObjects, current, mergeDialogId, true, globalSearchParams);
    }

    public boolean setPlaylist(ArrayList<MessageObject> messageObjects, MessageObject current, long mergeDialogId) {
        return setPlaylist(messageObjects, current, mergeDialogId, true, null);
    }

    public boolean setPlaylist(ArrayList<MessageObject> messageObjects, MessageObject current, long mergeDialogId, boolean loadMusic, PlaylistGlobalSearchParams params) {
        return setPlaylist(messageObjects, current, mergeDialogId, loadMusic, params, 0);
    }

    public boolean setPlaylist(ArrayList<MessageObject> messageObjects, MessageObject current, long mergeDialogId, boolean loadMusic, PlaylistGlobalSearchParams params, long topicId) {
        if (current == null) {
            return false;
        }
        cancelAutomaticMusicReturn();
        if (playingMessageObject == current && PlaylistGlobalSearchParams.sameSource(playlistGlobalSearchParams, params)
                && isCurrentPlaylistOwner(playlistLoadOwner) && playlistMergeDialogId == mergeDialogId
                && playlistTopicId == topicId && (playlistClassGuid != 0) == loadMusic) {
            int newIdx = (SharedConfig.shuffleMusic ? shuffledPlaylist : playlist).indexOf(current);
            if (newIdx >= 0) {
                currentPlaylistNum = newIdx;
            }
            return playMessage(current);
        }
        PlaylistGlobalSearchParams nextParams = params == null ? null : new PlaylistGlobalSearchParams(params);
        if (nextParams != null) {
            if (!nextParams.cursorCaptured) {
                nextParams.captureCursor(current.currentAccount, messageObjects);
            }
            if (!nextParams.isCurrentAccount(current.currentAccount)) {
                return false;
            }
        }
        long dialogId = current.getDialogId();
        if (mergeDialogId != 0 && dialogId == mergeDialogId) {
            for (int a = 0; a < messageObjects.size(); a++) {
                MessageObject object = messageObjects.get(a);
                if (object.currentAccount == current.currentAccount && object.isMusic() && object.getDialogId() != mergeDialogId) {
                    dialogId = object.getDialogId();
                    break;
                }
            }
            if (dialogId == mergeDialogId) {
                TLRPC.Chat chat = MessagesController.getInstance(current.currentAccount).getChat(-mergeDialogId);
                if (chat != null && chat.migrated_to != null) {
                    dialogId = -chat.migrated_to.channel_id;
                }
            }
            if (dialogId == mergeDialogId) {
                mergeDialogId = 0;
            }
        }
        forceLoopCurrentPlaylist = !loadMusic;
        playMusicAgain = !playlist.isEmpty();
        clearPlaylist();
        playlistMergeDialogId = mergeDialogId;
        playlistGlobalSearchParams = nextParams;
        playlistTopicId = topicId;
        playlistClassGuid = loadMusic && current.isMusic() && !current.scheduled ? ConnectionsManager.generateClassGuid() : 0;
        final PlaylistLoadOwner owner = playlistLoadOwner = new PlaylistLoadOwner(current.currentAccount, dialogId, mergeDialogId, topicId, playlistClassGuid);
        boolean isSecretChat = DialogObject.isEncryptedDialog(dialogId);
        int minId = Integer.MAX_VALUE;
        int maxId = Integer.MIN_VALUE;
        for (int a = messageObjects.size() - 1; a >= 0; a--) {
            MessageObject messageObject = messageObjects.get(a);
            if (messageObject.currentAccount == current.currentAccount && messageObject.isMusic() && !messageObject.isVoiceOnce()) {
                int id = messageObject.getId();
                if (messageObject.getDialogId() == dialogId && (id > 0 || isSecretChat)) {
                    minId = Math.min(minId, id);
                    maxId = Math.max(maxId, id);
                }
                int loadIndex = mergeDialogId != 0 && messageObject.getDialogId() == mergeDialogId ? 1 : 0;
                if (id > 0 && (messageObject.getDialogId() == dialogId || loadIndex == 1)) {
                    playlistMaxId[loadIndex] = Math.min(playlistMaxId[loadIndex], id);
                }
                if (playlistMap.containsKey(playlistKey(messageObject))) {
                    continue;
                }
                playlist.add(messageObject);
                playlistMap.put(playlistKey(messageObject), messageObject);
            }
        }
        MessageObject existing = playlistMap.get(playlistKey(current));
        if (existing != null && existing != current) {
            playlist.set(playlist.indexOf(existing), current);
            playlistMap.put(playlistKey(current), current);
        } else if (existing == null) {
            playlist.clear();
            playlistMap.clear();
            playlist.add(current);
            playlistMap.put(playlistKey(current), current);
            playlistMaxId[0] = playlistMaxId[1] = Integer.MAX_VALUE;
            minId = Integer.MAX_VALUE;
            maxId = Integer.MIN_VALUE;
            if (current.getId() > 0 || isSecretChat) {
                if (current.getDialogId() == dialogId) {
                    minId = maxId = current.getId();
                }
                playlistMaxId[mergeDialogId != 0 && current.getDialogId() == mergeDialogId ? 1 : 0] = current.getId();
            }
        }
        sortPlaylist();
        currentPlaylistNum = playlist.indexOf(current);
        if (current.isMusic() && !current.scheduled) {
            if (SharedConfig.shuffleMusic) {
                buildShuffledPlayList();
            }
            if (loadMusic) {
                if (playlistGlobalSearchParams == null) {
                    MediaDataController.getInstance(owner.account).loadMusic(owner.dialogId, owner.topicId, minId, maxId, owner);
                }
            }
        }
        return playMessage(current);
    }

    private void sortPlaylist() {
        Collections.sort(playlist, (o1, o2) -> {
            int mid1 = o1.getId();
            int mid2 = o2.getId();
            long group1 = o1.messageOwner.grouped_id;
            long group2 = o2.messageOwner.grouped_id;
            if (mid1 < 0 && mid2 < 0) {
                if (group1 != 0 && group1 == group2) {
                    return -Integer.compare(mid1, mid2);
                }
                return Integer.compare(mid2, mid1);
            } else {
                if (group1 != 0 && group1 == group2) {
                    return -Integer.compare(mid2, mid1);
                }
                return Integer.compare(mid1, mid2);
            }
        });
    }

    public boolean hasNoNextVoiceOrRoundVideoMessage() {
        return app.nimarkogram.messenger.AppConfig.disableVoiceMessagesAutoNext || playingMessageObject == null || (!playingMessageObject.isVoice() && !playingMessageObject.isRoundVideo()) || voiceMessagesPlaylist == null || voiceMessagesPlaylist.size() <= 1 || !voiceMessagesPlaylist.contains(playingMessageObject) || voiceMessagesPlaylist.indexOf(playingMessageObject) >= (voiceMessagesPlaylist.size() - 1);
    }

    public void playNextMessage() {
        cancelAutomaticMusicReturn();
        playNextMessageWithoutOrder(false);
    }

    public boolean findMessageInPlaylistAndPlay(MessageObject messageObject) {
        int index = playlist.indexOf(messageObject);
        if (index == -1) {
            return playMessage(messageObject);
        } else {
            playMessageAtIndex(index);
        }
        return true;
    }

    public void playMessageAtIndex(int index) {
        if (index < 0 || index >= playlist.size()) {
            return;
        }
        cancelAutomaticMusicReturn();
        currentPlaylistNum = index;
        playMusicAgain = true;
        MessageObject messageObject = playlist.get(currentPlaylistNum);
        if (playingMessageObject != null && !isSamePlayingMessage(messageObject)) {
            playingMessageObject.resetPlayingProgress();
        }
        playMessage(messageObject);
    }

    private void playNextMessageWithoutOrder(boolean byStop) {
        ArrayList<MessageObject> currentPlayList = SharedConfig.shuffleMusic ? shuffledPlaylist : playlist;

        if (byStop && playingMessageObject != null && playingMessageObject.isMusic()
                && !containsMusicPlaylistMessage(playingMessageObject)) {
            cleanupPlayer(true, true);
            return;
        }
        if (byStop && (SharedConfig.repeatMode == 2 || SharedConfig.repeatMode == 1 && currentPlayList.size() == 1) && !forceLoopCurrentPlaylist) {
            cleanupPlayer(false, false);
            if (currentPlaylistNum < 0 || currentPlaylistNum >= currentPlayList.size()) {
                return;
            }
            MessageObject messageObject = currentPlayList.get(currentPlaylistNum);
            messageObject.audioProgress = 0;
            messageObject.audioProgressSec = 0;
            playMessage(messageObject);
            return;
        }

        boolean last = traversePlaylist(currentPlayList, isPlaylistOrderReversed() ? +1 : -1);
        if (last && byStop && SharedConfig.repeatMode == 0 && !forceLoopCurrentPlaylist) {
            final MessageObject completedMessage = playingMessageObject;
            final long completedIntent = playbackIntentGeneration.get();
            if (audioPlayer != null || videoPlayer != null) {
                if (audioPlayer != null) {
                    if (reporter != null) {
                        reporter.destroy();
                        reporter = null;
                    }
                    try {
                        audioPlayer.releasePlayer(true);
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                    audioPlayer = null;
                    Theme.unrefAudioVisualizeDrawable(playingMessageObject);
                } else {
                    currentAspectRatioFrameLayout = null;
                    currentTextureViewContainer = null;
                    currentAspectRatioFrameLayoutReady = false;
                    currentTextureView = null;
                    videoPlayer.releasePlayer(true);
                    videoPlayer = null;
                    try {
                        baseActivity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                    AndroidUtilities.cancelRunOnUIThread(setLoadingRunnable);
                    FileLoader.getInstance(playingMessageObject.currentAccount).removeLoadingVideo(playingMessageObject.getDocument(), true, false);
                }
                stopProgressTimer();
                lastProgress = 0;
                isPaused = true;
                playingMessageObject.audioProgress = 0.0f;
                playingMessageObject.audioProgressSec = 0;
                NotificationCenter.getInstance(playingMessageObject.currentAccount).postNotificationName(NotificationCenter.messagePlayingProgressDidChanged, playingMessageObject.getId(), 0);
                if (playingMessageObject != completedMessage || playbackIntentGeneration.get() != completedIntent) return;
                NotificationCenter.getInstance(playingMessageObject.currentAccount).postNotificationName(NotificationCenter.messagePlayingPlayStateChanged, playingMessageObject.getId());
            }
            retirePlaybackIntent(completedMessage, completedIntent);
            return;
        }
        if (currentPlaylistNum < 0 || currentPlaylistNum >= currentPlayList.size()) {
            return;
        }
        if (playingMessageObject != null) {
            playingMessageObject.resetPlayingProgress();
        }
        playMusicAgain = true;
        playMessage(currentPlayList.get(currentPlaylistNum));
    }

    public void playPreviousMessage() {
        cancelAutomaticMusicReturn();
        ArrayList<MessageObject> currentPlayList = SharedConfig.shuffleMusic ? shuffledPlaylist : playlist;
        if (currentPlayList.isEmpty() || currentPlaylistNum < 0 || currentPlaylistNum >= currentPlayList.size()) {
            return;
        }
        MessageObject currentSong = currentPlayList.get(currentPlaylistNum);
        if (currentSong.audioProgressSec > 10) {
            seekToProgress(currentSong, 0);
            return;
        }

        traversePlaylist(currentPlayList, isPlaylistOrderReversed() ? -1 : 1);
        if (currentPlaylistNum >= currentPlayList.size()) {
            return;
        }
        playMusicAgain = true;
        playMessage(currentPlayList.get(currentPlaylistNum));
    }
    private boolean isPlaylistOrderReversed() {
        return currentSavedMusicList != null ? !SharedConfig.playOrderReversed : SharedConfig.playOrderReversed;
    }

    private boolean traversePlaylist(ArrayList<MessageObject> playlist, int direction) {
        boolean last = false;
        final int wasCurrentPlaylistNum = currentPlaylistNum;
        MessageObject accountOwner = playingMessageObject;
        if (accountOwner == null && currentPlaylistNum >= 0 && currentPlaylistNum < playlist.size()) {
            accountOwner = playlist.get(currentPlaylistNum);
        }
        int playbackAccount = accountOwner != null ? accountOwner.currentAccount : UserConfig.selectedAccount;
        int connectionState = ConnectionsManager.getInstance(playbackAccount).getConnectionState();
        boolean offline = connectionState == ConnectionsManager.ConnectionStateWaitingForNetwork;
        currentPlaylistNum += direction;
        if (offline) {
            while (currentPlaylistNum < playlist.size() && currentPlaylistNum >= 0) {
                MessageObject audio = playlist.get(currentPlaylistNum);
                if (audio != null && audio.mediaExists) {
                    break;
                }
                currentPlaylistNum += direction;
            }
        }
        if (currentPlaylistNum >= playlist.size() || currentPlaylistNum < 0) {
            currentPlaylistNum = currentPlaylistNum >= playlist.size() ? 0 : playlist.size() - 1;
            if (offline) {
                while (currentPlaylistNum >= 0 && currentPlaylistNum < playlist.size() && (direction > 0 ? currentPlaylistNum <= wasCurrentPlaylistNum : currentPlaylistNum >= wasCurrentPlaylistNum)) {
                    MessageObject audio = playlist.get(currentPlaylistNum);
                    if (audio != null && audio.mediaExists) {
                        break;
                    }
                    currentPlaylistNum += direction;
                }
                if (currentPlaylistNum >= playlist.size() || currentPlaylistNum < 0) {
                    currentPlaylistNum = currentPlaylistNum >= playlist.size() ? 0 : playlist.size() - 1;
                }
            }
            last = true;
        }
        return last;
    }

    protected void checkIsNextMediaFileDownloaded() {
        if (playingMessageObject == null || !playingMessageObject.isMusic()) {
            return;
        }
        checkIsNextMusicFileDownloaded(playingMessageObject.currentAccount);
    }

    private void checkIsNextVoiceFileDownloaded(int currentAccount) {
        if (voiceMessagesPlaylist == null || voiceMessagesPlaylist.size() < 2) {
            return;
        }
        MessageObject nextAudio = voiceMessagesPlaylist.get(1);
        File file = null;
        if (nextAudio.messageOwner.attachPath != null && nextAudio.messageOwner.attachPath.length() > 0) {
            file = new File(nextAudio.messageOwner.attachPath);
            if (!file.exists()) {
                file = null;
            }
        }
        final File cacheFile = file != null ? file : FileLoader.getInstance(currentAccount).getPathToMessage(nextAudio.messageOwner);
        boolean exist = cacheFile.exists();
        if (cacheFile != file && !cacheFile.exists()) {
            FileLoader.getInstance(currentAccount).loadFile(nextAudio.getDocument(), nextAudio, FileLoader.PRIORITY_LOW, nextAudio.shouldEncryptPhotoOrVideo() ? 2 : 0);
        }
    }

    private void checkIsNextMusicFileDownloaded(int currentAccount) {
        if (!DownloadController.getInstance(currentAccount).canDownloadNextTrack()) {
            return;
        }
        ArrayList<MessageObject> currentPlayList = SharedConfig.shuffleMusic ? shuffledPlaylist : playlist;
        if (currentPlayList == null || currentPlayList.size() < 2) {
            return;
        }
        int nextIndex;
        if (isPlaylistOrderReversed()) {
            nextIndex = currentPlaylistNum + 1;
            if (nextIndex >= currentPlayList.size()) {
                nextIndex = 0;
            }
        } else {
            nextIndex = currentPlaylistNum - 1;
            if (nextIndex < 0) {
                nextIndex = currentPlayList.size() - 1;
            }
        }
        if (nextIndex < 0 || nextIndex >= currentPlayList.size()) {
            return;
        }

        MessageObject nextAudio = currentPlayList.get(nextIndex);
        File file = null;
        if (!TextUtils.isEmpty(nextAudio.messageOwner.attachPath)) {
            file = new File(nextAudio.messageOwner.attachPath);
            if (!file.exists()) {
                file = null;
            }
        }
        final File cacheFile = file != null ? file : FileLoader.getInstance(currentAccount).getPathToMessage(nextAudio.messageOwner);
        boolean exist = cacheFile.exists();
        if (cacheFile != file && !cacheFile.exists() && nextAudio.isMusic()) {
            FileLoader.getInstance(currentAccount).loadFile(nextAudio.getDocument(), nextAudio, FileLoader.PRIORITY_LOW, nextAudio.shouldEncryptPhotoOrVideo() ? 2 : 0);
        }
    }

    public void setVoiceMessagesPlaylist(ArrayList<MessageObject> playlist, boolean unread) {
        SavedMusicPlaylistState state = savedMusicPlaylistState;
        if (state != null && state.roundLed && playlist != null && !playlist.contains(state.spokenMessage)) {
            state.resumeAllowed = false;
        }
        voiceMessagesPlaylist = playlist != null ? new ArrayList<>(playlist) : null;
        voiceMessagesPlaylistMap = voiceMessagesPlaylist == null ? null : new HashMap<>();
        if (voiceMessagesPlaylist != null) {
            voiceMessagesPlaylistUnread = unread;
            for (int a = 0; a < voiceMessagesPlaylist.size(); a++) {
                MessageObject messageObject = voiceMessagesPlaylist.get(a);
                voiceMessagesPlaylistMap.put(playlistKey(messageObject), messageObject);
            }
        }
    }

    private boolean checkAudioFocus(MessageObject messageObject) {
        if (!currentPlaybackIntent(messageObject)) return false;
        if (isSilent || CastSync.isActive()) return true;
        if (playbackIntentPaused || !canStartLocalPlayback(messageObject)) {
            abandonPlaybackFocus(playbackFocusListener, playbackFocusLeaseGeneration);
            return false;
        }
        int neededAudioFocus = messageObject.isMusic() ? 1 : useFrontSpeaker ? 3 : 2;
        if (playbackFocusListener != null && playbackFocusGranted
                && (hasAudioFocus == neededAudioFocus || hasAudioFocus > 1 && neededAudioFocus > 1)) {
            hasAudioFocus = neededAudioFocus;
            return true;
        }
        if (playbackFocusListener == null) {
            final long lease = ++playbackFocusLeaseGeneration;
            playbackFocusAccount = playbackIntentAccount;
            playbackFocusUserId = playbackIntentUserId;
            playbackFocusLoginTime = playbackIntentLoginTime;
            playbackFocusListener = change -> onPlaybackFocusChange(lease, change);
        }
        final AudioManager.OnAudioFocusChangeListener listener = playbackFocusListener;
        final long lease = playbackFocusLeaseGeneration;
        final long intent = playbackIntentGeneration.get();
        final long requestGeneration = ++playbackFocusRequestGeneration;
        Object request = null;
            int result;
        try {
            int stream = useFrontSpeaker && neededAudioFocus > 1 ? AudioManager.STREAM_VOICE_CALL : AudioManager.STREAM_MUSIC;
            int gain = neededAudioFocus == 1 ? AudioManager.AUDIOFOCUS_GAIN : AudioManager.AUDIOFOCUS_GAIN_TRANSIENT;
            if (Build.VERSION.SDK_INT >= 26) {
                request = PlaybackFocusApi26.create(listener, stream, gain);
                result = PlaybackFocusApi26.request(NotificationsController.audioManager, request);
            } else {
                result = NotificationsController.audioManager.requestAudioFocus(listener, stream, gain);
            }
        } catch (Exception e) {
            FileLog.e(e);
            result = AudioManager.AUDIOFOCUS_REQUEST_FAILED;
        }
        if (listener != playbackFocusListener || lease != playbackFocusLeaseGeneration
                || requestGeneration != playbackFocusRequestGeneration) return false;
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED || playbackFocusRequest == null) playbackFocusRequest = request;
        if (intent != playbackIntentGeneration.get()) {
            if (playbackIntentMessage == messageObject && playbackIntentPaused) abandonPlaybackFocus(listener, lease);
            return false;
        }
        if (result != AudioManager.AUDIOFOCUS_REQUEST_GRANTED || !currentPlaybackIntent(messageObject) || playbackIntentPaused) {
            abandonPlaybackFocus(listener, lease);
            return false;
        }
        hasAudioFocus = neededAudioFocus;
        playbackFocusGranted = true;
                audioFocus = AUDIO_FOCUSED;
        return true;
    }

    public boolean isPiPShown() {
        return pipRoundVideoView != null;
    }

    private void closePipRoundVideo(boolean animated) {
        PipRoundVideoView closingPip = pipRoundVideoView;
        if (closingPip == null) {
            return;
        }
        closingPip.close(animated, () -> {
            if (pipRoundVideoView == closingPip) {
                pipRoundVideoView = null;
            }
        });
    }

    private void finishPipCloseForSurfaceTransfer() {
        if (!pipClosingToInline) {
            return;
        }
        PipRoundVideoView closingPip = pipRoundVideoView;
        pipRoundVideoView = null;
        pipClosingToInline = false;
        showPipAfterInlineClose = false;
        if (closingPip != null) {
            closingPip.close(false);
        }
    }

    public void setCurrentVideoVisible(boolean visible) {
        if (videoPlayer == null || currentTextureView == null || currentAspectRatioFrameLayout == null || currentTextureViewContainer == null) {
            return;
        }
        if (visible) {
            showPipAfterInlineClose = false;
            if (pipClosingToInline) {
                return;
            }
            if (pipRoundVideoView != null) {
                PipRoundVideoView closingPip = pipRoundVideoView;
                pipClosingToInline = true;
                pipSwitchingState = 0;
                if (currentAspectRatioFrameLayout.getParent() == null) {
                    currentTextureViewContainer.addView(currentAspectRatioFrameLayout);
                }
                isDrawingWasReady = false;
                currentAspectRatioFrameLayout.setDrawingReady(false);
                if (playingMessageObject != null && playingMessageObject.isRoundVideo()) {

                    roundVideoFirstFrameRendered |= roundVideoFrameReadyAt != 0;
                    roundVideoFrameReadyAt = 0;
                    roundVideoPendingSurface = null;
                    ++roundVideoOutputGeneration;
                    currentTextureView.animate().cancel();
                    currentTextureView.setAlpha(0f);
                }

                videoPlayer.setTextureView(currentTextureView);
                closingPip.close(true, () -> {
                    if (pipRoundVideoView != closingPip) {
                        return;
                    }
                    pipRoundVideoView = null;
                    pipClosingToInline = false;
                    if (showPipAfterInlineClose) {
                        showPipAfterInlineClose = false;
                        setCurrentVideoVisible(false);
                    }
                });
            } else {
                if (currentAspectRatioFrameLayout.getParent() == null) {
                    currentTextureViewContainer.addView(currentAspectRatioFrameLayout);
                }
                videoPlayer.setTextureView(currentTextureView);
            }
        } else {
            if (pipClosingToInline) {

                showPipAfterInlineClose = true;
                return;
            }
            if (currentAspectRatioFrameLayout.getParent() != null) {
                pipSwitchingState = 1;
                currentTextureViewContainer.removeView(currentAspectRatioFrameLayout);
            } else {
                if (pipRoundVideoView == null) {
                    try {
                        pipRoundVideoView = new PipRoundVideoView();
                        pipRoundVideoView.show(baseActivity, () -> cleanupPlayer(true, true));
                    } catch (Exception e) {
                        pipRoundVideoView = null;
                    }
                }
                if (pipRoundVideoView != null) {
                    bindRoundVideoPip();
                }
            }
        }
    }

    public void setTextureView(TextureView textureView, AspectRatioFrameLayout aspectRatioFrameLayout, FrameLayout container, boolean set) {
        setTextureView(textureView, aspectRatioFrameLayout, container, set, null);
    }

    public void setTextureView(TextureView textureView, AspectRatioFrameLayout aspectRatioFrameLayout, FrameLayout container, boolean set, Runnable afterPip) {
        if (textureView == null) {
            return;
        }
        if (!set) {
            if (currentTextureView == textureView) {
                pipSwitchingState = 1;
                currentTextureView = null;
                currentAspectRatioFrameLayout = null;
                currentTextureViewContainer = null;
            }
            return;
        }
        if (videoPlayer == null || textureView == currentTextureView) {
            return;
        }
        if (roundVideoCoverTexture != null && roundVideoCoverTexture != textureView) {
            clearInlineRoundVideoFrame();
        }
        if (playingMessageObject != null && playingMessageObject.isRoundVideo()) {

            roundVideoFirstFrameRendered |= roundVideoFrameReadyAt != 0;
            roundVideoFrameReadyAt = 0;
            roundVideoPendingSurface = null;
            ++roundVideoOutputGeneration;
            textureView.animate().cancel();
            textureView.setAlpha(0f);
            if (aspectRatioFrameLayout != null) {
                aspectRatioFrameLayout.setDrawingReady(false);
            }
        }
        isDrawingWasReady = aspectRatioFrameLayout != null && aspectRatioFrameLayout.isDrawingReady();
        currentTextureView = textureView;
        if (afterPip != null && pipRoundVideoView == null) {
            try {
                pipRoundVideoView = new PipRoundVideoView();
                pipRoundVideoView.show(baseActivity, () -> cleanupPlayer(true, true));
            } catch (Exception e) {
                pipRoundVideoView = null;
            }
        }
        if (pipRoundVideoView != null && !pipClosingToInline) {
            bindRoundVideoPip();
        } else {
            videoPlayer.setTextureView(currentTextureView);
        }
        currentAspectRatioFrameLayout = aspectRatioFrameLayout;
        currentTextureViewContainer = container;
        if (currentAspectRatioFrameLayoutReady && currentAspectRatioFrameLayout != null) {
            currentAspectRatioFrameLayout.setAspectRatio(currentAspectRatioFrameLayoutRatio, currentAspectRatioFrameLayoutRotation);
            //if (currentTextureViewContainer.getVisibility() != View.VISIBLE) {
            //    currentTextureViewContainer.setVisibility(View.VISIBLE);
            //}
        }
    }

    public void setBaseActivity(Activity activity, boolean set) {
        if (set) {
            baseActivity = activity;
        } else if (baseActivity == activity) {
            baseActivity = null;
        }
    }

    public void setFeedbackView(View view, boolean set) {
        if (set) {
            feedbackView = view;
        } else if (feedbackView == view) {
            feedbackView = null;
        }
    }

    public void setPlaybackSpeed(boolean music, float speed) {
        if (music) {
            if (currentMusicPlaybackSpeed >= 6 && speed == 1f && playingMessageObject != null) {
                audioPlayer.pause();
                float p = playingMessageObject.audioProgress;
                final MessageObject currentMessage = playingMessageObject;
                final VideoPlayer currentPlayer = audioPlayer;
                final long currentIntent = playbackIntentGeneration.get();
                AndroidUtilities.runOnUIThread(() -> {
                    if (audioPlayer == currentPlayer && playingMessageObject == currentMessage && !isPaused && !playbackIntentPaused
                            && currentIntent == playbackIntentGeneration.get()) {
                        if (isSamePlayingMessage(currentMessage)) {
                            seekToProgress(playingMessageObject, p);
                        }
                        if (checkAudioFocus(currentMessage) && audioPlayer == currentPlayer && playingMessageObject == currentMessage
                                && currentIntent == playbackIntentGeneration.get()) currentPlayer.play();
                    }
                }, 50);
            }
            currentMusicPlaybackSpeed = speed;
            if (Math.abs(speed - 1.0f) > 0.001f) {
                fastMusicPlaybackSpeed = speed;
            }
        } else {
            currentPlaybackSpeed = speed;
            if (Math.abs(speed - 1.0f) > 0.001f) {
                fastPlaybackSpeed = speed;
            }
        }
        if (audioPlayer != null) {
            audioPlayer.setPlaybackSpeed(Math.round(speed * 10f) / 10f);
        } else if (videoPlayer != null) {
            videoPlayer.setPlaybackSpeed(Math.round(speed * 10f) / 10f);
        }
        MessagesController.getGlobalMainSettings().edit()
                .putFloat(music ? "musicPlaybackSpeed" : "playbackSpeed", speed)
                .putFloat(music ? "fastMusicPlaybackSpeed" : "fastPlaybackSpeed", music ? fastMusicPlaybackSpeed : fastPlaybackSpeed)
                .commit();
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.messagePlayingSpeedChanged);
        if (!ignorePlayerUpdate) {
            CastSync.setSpeed(speed);
        }
    }

    public float getPlaybackSpeed(boolean music) {
        return music ? currentMusicPlaybackSpeed : currentPlaybackSpeed;
    }

    public float getFastPlaybackSpeed(boolean music) {
        return music ? fastMusicPlaybackSpeed : fastPlaybackSpeed;
    }

    private void markVideoFrameAvailable() {
        if (currentAspectRatioFrameLayout != null && !currentAspectRatioFrameLayout.isDrawingReady()) {
            isDrawingWasReady = true;
            roundVideoFrameReadyAt = SystemClock.uptimeMillis();
            currentAspectRatioFrameLayout.setDrawingReady(true);
            if (currentTextureViewContainer != null) {
                currentTextureViewContainer.setTag(1);
            }
            if (playingMessageObject != null && playingMessageObject.isRoundVideo()) {
                if (currentTextureView != null) {

                    currentTextureView.animate().cancel();
                    currentTextureView.setAlpha(1f);
                    if (roundVideoPlaybackCover != null && roundVideoCoverTexture == currentTextureView) {
                        roundVideoCoverPending = false;
                        final int generation = roundVideoCoverGeneration;
                        roundVideoPlaybackCover.animate().alpha(0f).setDuration(180).withEndAction(() -> {
                            if (generation == roundVideoCoverGeneration && !roundVideoCoverPending) {
                                clearInlineRoundVideoFrame();
                            }
                        }).start();
                    }
                }
                NotificationCenter.getInstance(playingMessageObject.currentAccount).postNotificationName(
                        NotificationCenter.messagePlayingProgressDidChanged, playingMessageObject.getId(), 0);
            }
        }
    }

    private void markRoundVideoSurfaceFrameAvailable(SurfaceTexture surfaceTexture) {
        if (surfaceTexture == null || roundVideoFrameReadyAt != 0 || currentTextureView == null
                || currentTextureView.getSurfaceTexture() != surfaceTexture) {
            return;
        }
        if (!roundVideoFirstFrameRendered) {

            roundVideoPendingSurface = surfaceTexture;
            return;
        }
        roundVideoPendingSurface = null;
        markVideoFrameAvailable();
    }

    private void bindRoundVideoPip() {

        pipRoundVideoView.beginPlaybackTransition(playerNum, roundVideoFirstFrameRendered);
        videoPlayer.setTextureView(pipRoundVideoView.getTextureView());
    }

    private void retainInlineRoundVideoFrame() {
        if (pipRoundVideoView != null || playingMessageObject == null || !playingMessageObject.isRoundVideo()
                || currentTextureView == null || currentAspectRatioFrameLayout == null) return;
        if (roundVideoCoverPending && roundVideoCoverTexture == currentTextureView) return;
        if (!currentAspectRatioFrameLayout.isDrawingReady() && roundVideoPlaybackCover == null) return;
        try {
            if (!currentTextureView.isAvailable()) return;
            Bitmap frame = currentTextureView.getBitmap(AndroidUtilities.dp(120), AndroidUtilities.dp(120));
            if (frame == null) return;
            if (roundVideoPlaybackBitmap != null && roundVideoPlaybackCover != null
                    && roundVideoCoverTexture == currentTextureView) {
                Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
                paint.setAlpha(Math.round(255 * roundVideoPlaybackCover.getAlpha()));
                new Canvas(frame).drawBitmap(roundVideoPlaybackBitmap, null,
                        new android.graphics.RectF(0, 0, frame.getWidth(), frame.getHeight()), paint);
            }
            clearInlineRoundVideoFrame();
            roundVideoCoverTexture = currentTextureView;
            roundVideoPlaybackBitmap = frame;
            roundVideoPlaybackCover = new ImageView(currentTextureView.getContext());
            roundVideoPlaybackCover.setScaleType(ImageView.ScaleType.FIT_XY);
            roundVideoPlaybackCover.setImageBitmap(frame);
            roundVideoPlaybackCover.setScaleX(currentTextureView.getScaleX());
            roundVideoPlaybackCover.setScaleY(currentTextureView.getScaleY());
            currentAspectRatioFrameLayout.addView(roundVideoPlaybackCover,
                    new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            roundVideoCoverPending = true;
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private void clearInlineRoundVideoFrame() {
        ++roundVideoCoverGeneration;
        if (roundVideoPlaybackCover != null) {
            roundVideoPlaybackCover.animate().cancel();
            AndroidUtilities.removeFromParent(roundVideoPlaybackCover);
            roundVideoPlaybackCover.setImageDrawable(null);
        }

        roundVideoPlaybackCover = null;
        roundVideoPlaybackBitmap = null;
        roundVideoCoverTexture = null;
        roundVideoCoverPending = false;
    }

    private void onVideoFirstFrame(int tag, boolean round) {
        if (tag != playerNum) return;
        if (round) {
            roundVideoFirstFrameRendered = true;
            if (pipRoundVideoView != null) {
                pipRoundVideoView.onPlaybackFirstFrame(tag);
            }
            if (currentTextureView != null && roundVideoPendingSurface != null
                    && roundVideoPendingSurface == currentTextureView.getSurfaceTexture()) {
                markRoundVideoSurfaceFrameAvailable(roundVideoPendingSurface);
            }
        } else {
            markVideoFrameAvailable();
        }
        if (videoPlayer != null && CastSync.isActive()) {
            videoPlayer.setMute(true);
        }
    }

    private void onRoundVideoSurfaceUpdated(int tag, SurfaceTexture surfaceTexture) {
        if (tag != playerNum) return;
        final int outputGeneration = roundVideoOutputGeneration;
        final PipRoundVideoView pip = pipRoundVideoView;
        if (!(pip != null && pip.needsPlaybackSurfaceUpdate(tag, surfaceTexture))
                && !(roundVideoFrameReadyAt == 0 && currentTextureView != null
                    && currentTextureView.getSurfaceTexture() == surfaceTexture
                    && roundVideoPendingSurface != surfaceTexture)) {
            return;
        }

        AndroidUtilities.runOnUIThread(() -> {
            if (tag != playerNum) return;
            if (pip != null && pip == pipRoundVideoView && pip.needsPlaybackSurfaceUpdate(tag, surfaceTexture)) {
                pip.onPlaybackSurfaceUpdated(tag, surfaceTexture);
            }
            if (outputGeneration == roundVideoOutputGeneration) {
                markRoundVideoSurfaceFrameAvailable(surfaceTexture);
            }
        });
    }

    public float getRoundVideoThumbnailAlpha() {
        if (roundVideoPlaybackCover != null && roundVideoCoverTexture == currentTextureView) {

            return 0f;
        }
        if (!isVideoDrawingReady() || roundVideoFrameReadyAt == 0) {
            return 1f;
        }
        return Math.max(0f, 1f - (SystemClock.uptimeMillis() - roundVideoFrameReadyAt) / 180f);
    }

    public void onRoundVideoTextureDetached(TextureView textureView) {
        if (roundVideoCoverTexture == textureView) clearInlineRoundVideoFrame();
        if (currentTextureView == textureView && playingMessageObject != null && playingMessageObject.isRoundVideo()) {

            roundVideoFirstFrameRendered |= roundVideoFrameReadyAt != 0;
            roundVideoFrameReadyAt = 0;
            roundVideoPendingSurface = null;
            ++roundVideoOutputGeneration;
        }
    }

    public boolean isRoundVideoThumbnailReadyToHide() {
        return getRoundVideoThumbnailAlpha() <= 0f;
    }

    private void updateVideoState(MessageObject messageObject, int[] playCount, boolean destroyAtEnd, boolean playWhenReady, int playbackState) {
        if (videoPlayer == null) {
            return;
        }
        VideoPlayer statePlayer = videoPlayer;
        int statePlayerNum = playerNum;
        if (playbackState != ExoPlayer.STATE_ENDED && playbackState != ExoPlayer.STATE_IDLE) {
            try {
                baseActivity.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } catch (Exception e) {
                FileLog.e(e);
            }
        } else {
            try {
                baseActivity.getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            } catch (Exception e) {
                FileLog.e(e);
            }
        }
        if (videoPlayer != statePlayer || playerNum != statePlayerNum) return;
        if (playbackState == ExoPlayer.STATE_READY) {
            playerWasReady = true;
            if (playingMessageObject != null && (playingMessageObject.isVideo() || playingMessageObject.isRoundVideo())) {
                AndroidUtilities.cancelRunOnUIThread(setLoadingRunnable);
                FileLoader.getInstance(messageObject.currentAccount).removeLoadingVideo(playingMessageObject.getDocument(), true, false);
            }
            currentAspectRatioFrameLayoutReady = true;
        } else if (playbackState == ExoPlayer.STATE_BUFFERING) {
            if (playWhenReady && playingMessageObject != null && (playingMessageObject.isVideo() || playingMessageObject.isRoundVideo())) {
                if (playerWasReady) {
                    setLoadingRunnable.run();
                } else {
                    AndroidUtilities.runOnUIThread(setLoadingRunnable, 1000);
                }
            }
        } else if (videoPlayer.isPlaying() && playbackState == ExoPlayer.STATE_ENDED) {
            if (playingMessageObject != messageObject) return;
            if (isTemporaryPlayback(messageObject) && (!playWhenReady || isPaused || playbackIntentPaused)) return;
            if (playingMessageObject != null && playingMessageObject.isVideo() && !destroyAtEnd && (playCount == null || playCount[0] < 4)) {
                videoPlayer.seekTo(0);
                if (playCount != null) {
                    playCount[0]++;
                }
            } else {
                if (finishRoundPlayback(messageObject, playerNum, playWhenReady, playbackState)) return;
                final boolean restored = restoreMusicPlaylistState();
                if (!restored) {
                    cleanupPlayer(true, hasNoNextVoiceOrRoundVideoMessage(), true, false);
                }
            }
        }
    }

    public void injectVideoPlayer(VideoPlayer player, MessageObject messageObject) {
        if (player == null || messageObject == null) {
            return;
        }
        final boolean shouldPlay = player.isPlaying();
        player.pause();
        final boolean muted = player.isMuted();
        final long generation = musicReturnIntentGeneration.get();
        final long admissionIntent = playbackIntentGeneration.get();
        final MessageObject previousMessage = playingMessageObject;
        boolean saved = savedMusicPlaylistState != null;
        if (isTemporaryPlayback(messageObject) && previousMessage != null && previousMessage.isMusic()) {
            saved = saveMusicPlaylistStateIfNeeded();
        }
        if (player != videoPlayer && player != audioPlayer) {
            cleanupPlayer(false, false, false, false, -1, null, true);
        }
        if (generation != musicReturnIntentGeneration.get() || admissionIntent != playbackIntentGeneration.get()) {
            if (player != videoPlayer && player != audioPlayer) {
                try {
                    player.releasePlayer(true);
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
            return;
        }
        isSilent = muted;
        beginPlaybackIntent(messageObject, !shouldPlay);
        final long intent = playbackIntentGeneration.get();
        if (saved && savedMusicPlaylistState != null && isTemporaryPlayback(messageObject)) {
            savedMusicPlaylistState.beginRound(messageObject);
            savedMusicPlaylistState.roundPaused = !shouldPlay;
            savedMusicPlaylistState.resumeAllowed &= !muted;
        }
        FileLoader.getInstance(messageObject.currentAccount).setLoadingVideoForPlayer(messageObject.getDocument(), true);
        playerWasReady = false;
        boolean destroyAtEnd = true;
        int[] playCount = null;
        if (!saved) clearPlaylist();
        if (!messageObject.isRoundVideo()) {
            clearInlineRoundVideoFrame();
        }
        if (messageObject.isRoundVideo() && !isSamePlayingMessage(messageObject)) {
            retainInlineRoundVideoFrame();
        }
        if (pipRoundVideoView != null && playingMessageObject != null && !isSamePlayingMessage(messageObject)) {
            pipRoundVideoView.retainPlaybackFrame();
        }
        videoPlayer = player;
        playingMessageObject = messageObject;
        int tag = ++playerNum;
        ++roundVideoOutputGeneration;
        roundVideoFrameReadyAt = 0;
        roundVideoPendingSurface = null;

        roundVideoFirstFrameRendered = messageObject.isRoundVideo();
        if (messageObject.isRoundVideo() && currentAspectRatioFrameLayout != null) {
            isDrawingWasReady = false;
            currentAspectRatioFrameLayout.setDrawingReady(false);
        }
        videoPlayer.setDelegate(new VideoPlayer.VideoPlayerDelegate() {
            @Override
            public void onStateChanged(boolean playWhenReady, int playbackState) {
                if (tag != playerNum) {
                    return;
                }
                updateVideoState(messageObject, playCount, destroyAtEnd, playWhenReady, playbackState);
            }

            @Override
            public void onError(VideoPlayer player, Exception e) {
                failCurrentPlayback(messageObject, tag, player);
                FileLog.e(e);
            }

            @Override
            public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {
                currentAspectRatioFrameLayoutRotation = unappliedRotationDegrees;
                if (unappliedRotationDegrees == 90 || unappliedRotationDegrees == 270) {
                    int temp = width;
                    width = height;
                    height = temp;
                }
                currentAspectRatioFrameLayoutRatio = height == 0 ? 1 : (width * pixelWidthHeightRatio) / height;

                if (currentAspectRatioFrameLayout != null) {
                    currentAspectRatioFrameLayout.setAspectRatio(currentAspectRatioFrameLayoutRatio, currentAspectRatioFrameLayoutRotation);
                }
            }

            @Override
            public void onRenderedFirstFrame() {
                onVideoFirstFrame(tag, messageObject.isRoundVideo());
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
                if (messageObject.isRoundVideo()) onRoundVideoSurfaceUpdated(tag, surfaceTexture);
            }

            @Override
            public boolean onSurfaceDestroyed(SurfaceTexture surfaceTexture) {
                if (tag != playerNum) return false;
                if (videoPlayer == null) {
                    // Do not leave a stale transition token behind when the
                    // player is released concurrently with TextureView.
                    pipSwitchingState = 0;
                    return false;
                }
                if (pipSwitchingState == 1) {
                    finishPipCloseForSurfaceTransfer();
                    if (baseActivity != null) {
                        if (pipRoundVideoView == null) {
                            try {
                                pipRoundVideoView = new PipRoundVideoView();
                                pipRoundVideoView.show(baseActivity, () -> cleanupPlayer(true, true));
                            } catch (Exception e) {
                                pipRoundVideoView = null;
                            }
                        }
                        if (pipRoundVideoView != null) {
                            if (pipRoundVideoView.getTextureView().getSurfaceTexture() != surfaceTexture) {
                                pipRoundVideoView.getTextureView().setSurfaceTexture(surfaceTexture);
                            }
                            bindRoundVideoPip();
                        }
                    }
                    pipSwitchingState = 0;
                    return true;
                } else if (PhotoViewer.hasInstance() && PhotoViewer.getInstance().isInjectingVideoPlayer()) {
                    PhotoViewer.getInstance().injectVideoPlayerSurface(surfaceTexture);
                    return true;
                }
                return false;
            }
        });
        currentAspectRatioFrameLayoutReady = false;
        if (pipRoundVideoView != null && !pipClosingToInline) {
            bindRoundVideoPip();
        } else if (currentTextureView != null) {
            videoPlayer.setTextureView(currentTextureView);
        }

        final boolean granted = !shouldPlay || checkAudioFocus(messageObject);
        if (intent != playbackIntentGeneration.get() || generation != musicReturnIntentGeneration.get()
                || videoPlayer != player || playingMessageObject != messageObject) return;
        setPlayerVolume();

        isPaused = !shouldPlay || !granted;
        playbackIntentPaused = isPaused;
        lastProgress = 0;
        playingMessageObject = messageObject;
        if (savedMusicPlaylistState != null && savedMusicPlaylistState.roundLed) savedMusicPlaylistState.spokenPlayerNum = playerNum;
        if (!SharedConfig.enabledRaiseTo(true)) {
            startRaiseToEarSensors(raiseChat);
        }
        if (!isPaused) startProgressTimer(playingMessageObject);
        NotificationCenter.getInstance(messageObject.currentAccount).postNotificationName(NotificationCenter.messagePlayingDidStart, messageObject, previousMessage);
        if (intent != playbackIntentGeneration.get() || generation != musicReturnIntentGeneration.get()
                || videoPlayer != player || playingMessageObject != messageObject) return;
        if (!isPaused && !playbackIntentPaused && granted) player.play();

        /*try {
            if (playingMessageObject.audioProgress != 0) {
                long duration = videoPlayer.getDuration();
                if (duration == C.TIME_UNSET) {
                    duration = (long) playingMessageObject.getDuration() * 1000;
                }
                int seekTo = (int) (duration * playingMessageObject.audioProgress);
                if (playingMessageObject.audioProgressMs != 0) {
                    seekTo = playingMessageObject.audioProgressMs;
                    playingMessageObject.audioProgressMs = 0;
                }
                videoPlayer.seekTo(seekTo, playingMessageObject.isRoundVideo());
            }
        } catch (Exception e2) {
            playingMessageObject.audioProgress = 0;
            playingMessageObject.audioProgressSec = 0;
            NotificationCenter.getInstance(messageObject.currentAccount).postNotificationName(NotificationCenter.messagePlayingProgressDidChanged, playingMessageObject.getId(), 0);
            FileLog.e(e2);
        }*/
    }

    public void playEmojiSound(AccountInstance accountInstance, String emoji, MessagesController.EmojiSound sound, boolean loadOnly) {
        if (sound == null) {
            return;
        }
        Utilities.stageQueue.postRunnable(() -> {
            TLRPC.Document document = new TLRPC.TL_document();
            document.access_hash = sound.accessHash;
            document.id = sound.id;
            document.mime_type = "sound/ogg";
            document.file_reference = sound.fileReference;
            document.dc_id = accountInstance.getConnectionsManager().getCurrentDatacenterId();
            File file = FileLoader.getInstance(accountInstance.getCurrentAccount()).getPathToAttach(document, true);
            if (file.exists()) {
                if (loadOnly) {
                    return;
                }
                AndroidUtilities.runOnUIThread(() -> {
                    try {
                        int tag = ++emojiSoundPlayerNum;
                        if (emojiSoundPlayer != null) {
                            emojiSoundPlayer.releasePlayer(true);
                        }
                        emojiSoundPlayer = new VideoPlayer(false, false);
                        emojiSoundPlayer.setDelegate(new VideoPlayer.VideoPlayerDelegate() {
                            @Override
                            public void onStateChanged(boolean playWhenReady, int playbackState) {
                                AndroidUtilities.runOnUIThread(() -> {
                                    if (tag != emojiSoundPlayerNum) {
                                        return;
                                    }
                                    if (playbackState == ExoPlayer.STATE_ENDED) {
                                        if (emojiSoundPlayer != null) {
                                            try {
                                                emojiSoundPlayer.releasePlayer(true);
                                                emojiSoundPlayer = null;
                                            } catch (Exception e) {
                                                FileLog.e(e);
                                            }
                                        }
                                    }
                                });
                            }

                            @Override
                            public void onError(VideoPlayer player, Exception e) {

                            }

                            @Override
                            public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {

                            }

                            @Override
                            public void onRenderedFirstFrame() {

                            }
                        });
                        emojiSoundPlayer.preparePlayer(Uri.fromFile(file), "other");
                        emojiSoundPlayer.setStreamType(AudioManager.STREAM_MUSIC);
                        emojiSoundPlayer.play();
                    } catch (Exception e) {
                        FileLog.e(e);
                        if (emojiSoundPlayer != null) {
                            emojiSoundPlayer.releasePlayer(true);
                            emojiSoundPlayer = null;
                        }
                    }
                });
            } else {
                AndroidUtilities.runOnUIThread(() -> accountInstance.getFileLoader().loadFile(document, null, FileLoader.PRIORITY_NORMAL, 1));
            }
        });
    }

    private static long volumeBarLastTimeShown;
    public void checkVolumeBarUI() {
        if (isSilent) {
            return;
        }
        try {
            final long now = System.currentTimeMillis();
            if (Math.abs(now - volumeBarLastTimeShown) < 5000) {
                return;
            }
            AudioManager audioManager = (AudioManager) ApplicationLoader.applicationContext.getSystemService(Context.AUDIO_SERVICE);
            int stream = useFrontSpeaker ? AudioManager.STREAM_VOICE_CALL : AudioManager.STREAM_MUSIC;
            int volume = audioManager.getStreamVolume(stream);
            if (volume == 0) {
                audioManager.adjustStreamVolume(stream, volume, AudioManager.FLAG_SHOW_UI);
                volumeBarLastTimeShown = now;
            }
        } catch (Exception ignore) {}
    }

    private void setBluetoothScoOn(boolean scoOn) {
        AudioManager am = (AudioManager) ApplicationLoader.applicationContext.getSystemService(Context.AUDIO_SERVICE);
        if (SharedConfig.recordViaSco && !PermissionRequest.hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            SharedConfig.recordViaSco = false;
            SharedConfig.saveConfig();
        }
        if (am.isBluetoothScoAvailableOffCall() && SharedConfig.recordViaSco || !scoOn) {
            BluetoothAdapter btAdapter = BluetoothAdapter.getDefaultAdapter();
            try {
                if (btAdapter != null && btAdapter.getProfileConnectionState(BluetoothProfile.HEADSET) == BluetoothAdapter.STATE_CONNECTED || !scoOn) {
                    if (scoOn && !am.isBluetoothScoOn()) {
                        am.startBluetoothSco();
                    } else if (!scoOn && am.isBluetoothScoOn()) {
                        am.stopBluetoothSco();
                    }
                }
            } catch (SecurityException ignored) {
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }
    }

    public boolean playMessage(final MessageObject messageObject) {
        return playMessage(messageObject, false);
    }

    public boolean playMessage(final MessageObject messageObject, boolean silent) {
        if (messageObject == null) return false;
        playbackIntentGeneration.incrementAndGet();
        resumeAudioOnFocusGain = false;
        focusResumeMessage = null;
        if (isSamePlayingMessage(messageObject)) playbackIntentPaused = false;
        final long selectionGeneration = musicReturnIntentGeneration.incrementAndGet();
        PendingMusicReturn returning = pendingMusicReturn;
        int returnPosition = returning != null && returning.state.matchesSong(messageObject) ? returning.state.progressMs : 0;
        if (returnPosition > 0) restoreMusicSeek(returning.state, messageObject);
        retirePendingMusicReturn(true);
        if (selectionGeneration != musicReturnIntentGeneration.get()) return false;
        SavedMusicPlaylistState continuation = savedMusicPlaylistState;
        if (messageObject.isMusic()) {
            clearMusicPlaylistState();
            continuation = null;
        } else if (ownsRoundPlayback(continuation, playingMessageObject, playerNum)
                && continuation.currentSpokenAccount() && messageObject.currentAccount == playingMessageObject.currentAccount
                && isTemporaryPlayback(messageObject)) {
            resumeAudioOnFocusGain = false;
            continuation.roundPaused = false;
        } else {
            if (continuation != null && continuation.roundLed) clearMusicPlaylistState();
            continuation = null;
        }
        if (selectionGeneration != musicReturnIntentGeneration.get()) return false;
        if (downloadingCurrentMessage && isSamePlayingMessage(messageObject) && currentPlaybackIntent(playingMessageObject)) {
            isSilent = silent;
            playbackIntentPaused = !canStartLocalPlayback(messageObject);
            isPaused = playbackIntentPaused;
            if (silent || CastSync.isActive()) abandonPlaybackFocus(playbackFocusListener, playbackFocusLeaseGeneration);
            return !playbackIntentPaused;
        }
        return playMessage(messageObject, silent, continuation, false, returnPosition);
    }

    private boolean playMessage(final MessageObject messageObject, boolean silent, SavedMusicPlaylistState continuation, boolean startPaused) {
        return playMessage(messageObject, silent, continuation, startPaused, 0);
    }

    private boolean playMessage(final MessageObject messageObject, boolean silent, SavedMusicPlaylistState continuation, boolean startPaused, int restorePositionMs) {
        return playMessage(messageObject, silent, continuation, startPaused, restorePositionMs, null);
    }

    private boolean playMessage(final MessageObject messageObject, boolean silent, SavedMusicPlaylistState continuation, boolean startPaused, int restorePositionMs, PendingMusicReturn returning) {
        if (messageObject == null) {
            return false;
        }
        final long returnGeneration = musicReturnIntentGeneration.get();
        if (returning != null) {
            if (!currentMusicReturn(returning, messageObject)) return false;
            startPaused |= !activeMusicReturn(returning);
        }
        if (continuation != null && (savedMusicPlaylistState != continuation || !continuation.currentSpokenAccount())) return false;
        if (downloadingCurrentMessage && playingMessageObject == messageObject && currentPlaybackIntent(messageObject)) {
            startPaused |= playbackIntentPaused;
        }
        isSilent = silent;
        checkVolumeBarUI();
        if (returnGeneration != musicReturnIntentGeneration.get()) return false;
        if ((audioPlayer != null || videoPlayer != null) && isSamePlayingMessage(messageObject)
                && currentPlaybackIntent(playingMessageObject)) {
            if (!startPaused && (isPaused || playbackIntentPaused || !playbackFocusGranted)) {
                if (!resumeAudio(messageObject)) return false;
            }
            if (!SharedConfig.enabledRaiseTo(true)) {
                startRaiseToEarSensors(raiseChat);
            }
            return true;
        }
        if (!messageObject.isOut() && (messageObject.isContentUnread())) {
            MessagesController.getInstance(messageObject.currentAccount).markMessageContentAsRead(messageObject);
        }
        if (returnGeneration != musicReturnIntentGeneration.get()) return false;
        boolean saved = continuation != null;
        boolean notify = !playMusicAgain;
        MessageObject oldMessageObject = playingMessageObject;
        if (messageObject.isRoundVideo()) {
            retainInlineRoundVideoFrame();
        }
        if (playingMessageObject != null) {
            if (playingMessageObject.isMusic() && isTemporaryPlayback(messageObject)) {
                saved = saveMusicPlaylistStateIfNeeded();
            }
            if (saved && playingMessageObject.isMusic() && isTemporaryPlayback(messageObject)) {
                savedMusicPlaylistState.beginRound(messageObject);
                savedMusicPlaylistState.resumeAllowed &= !silent;
                continuation = savedMusicPlaylistState;
            }
            notify = false;
            if (!playMusicAgain && !(playingMessageObject == messageObject && restorePositionMs > 0)) {
                playingMessageObject.resetPlayingProgress();
                NotificationCenter.getInstance(playingMessageObject.currentAccount).postNotificationName(NotificationCenter.messagePlayingProgressDidChanged, playingMessageObject.getId(), 0);
                if (returnGeneration != musicReturnIntentGeneration.get()) return false;
            }
        }
        cleanupPlayer(notify, false, false, false, -1, null, true);
        if (returnGeneration != musicReturnIntentGeneration.get()) return false;
        if (!currentPlaybackIntent(messageObject)) beginPlaybackIntent(messageObject, startPaused);
        else playbackIntentPaused |= startPaused;
        startPaused |= playbackIntentPaused;
        final long playbackIntent = playbackIntentGeneration.get();
        if (startPaused && messageObject.isMusic() || silent || CastSync.isActive()
                || returning == null && messageObject.isMusic() && hasAudioFocus > 1) {
            abandonPlaybackFocus(playbackFocusListener, playbackFocusLeaseGeneration);
        }
        if (continuation != null) continuation.spokenMessage = messageObject;
        if (continuation != null) continuation.spokenPlayerNum = playerNum;
        if (!messageObject.isRoundVideo()) clearInlineRoundVideoFrame();
        shouldSavePositionForCurrentAudio = null;
        lastSaveTime = 0;
        playMusicAgain = false;
        seekToProgressPending = 0;
        File file = null;
        boolean exists = false;
        if (messageObject.messageOwner.attachPath != null && messageObject.messageOwner.attachPath.length() > 0) {
            file = new File(messageObject.messageOwner.attachPath);
            exists = file.exists();
            if (!exists) {
                file = null;
            }
        }
        final File cacheFile = file != null ? file : FileLoader.getInstance(messageObject.currentAccount).getPathToMessage(messageObject.messageOwner);
        boolean canStream = SharedConfig.streamMedia && (messageObject.isMusic() || messageObject.isRoundVideo() || messageObject.isVideo() && messageObject.canStreamVideo()) && !messageObject.shouldEncryptPhotoOrVideo() && !DialogObject.isEncryptedDialog(messageObject.getDialogId());
        if (cacheFile != file && !(exists = cacheFile.exists()) && !canStream) {
            try {
            FileLoader.getInstance(messageObject.currentAccount).loadFile(messageObject.getDocument(), messageObject, FileLoader.PRIORITY_LOW, messageObject.shouldEncryptPhotoOrVideo() ? 2 : 0);
            } catch (Exception e) {
                FileLog.e(e);
                retirePlaybackIntent(messageObject, playbackIntent);
                return false;
            }
            if (returnGeneration != musicReturnIntentGeneration.get()) return false;
            if (!currentPlaybackIntent(messageObject) || playbackIntent != playbackIntentGeneration.get()) return false;
            startPaused |= playbackIntentPaused;
            if (returning != null) {
                if (!currentMusicReturn(returning, messageObject)) return false;
                returning.paused |= startPaused || !activeMusicReturn(returning);
                startPaused = returning.paused;
            }
            downloadingCurrentMessage = true;
            isPaused = startPaused;
            lastProgress = 0;
            audioInfoLoadGeneration++;
            audioInfo = null;
            playingMessageObject = messageObject;
            if (continuation != null) continuation.spokenPlayerNum = playerNum;
            if (canStartMusicPlayerService()) {
                Intent intent = new Intent(ApplicationLoader.applicationContext, MusicPlayerService.class);
                try {
                    /*if (Build.VERSION.SDK_INT >= 26) {
                        ApplicationLoader.applicationContext.startForegroundService(intent);
                    } else {*/
                    ApplicationLoader.applicationContext.startService(intent);
                    //}
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            } else {
                Intent intent = new Intent(ApplicationLoader.applicationContext, MusicPlayerService.class);
                ApplicationLoader.applicationContext.stopService(intent);
            }
            NotificationCenter.getInstance(playingMessageObject.currentAccount).postNotificationName(NotificationCenter.messagePlayingPlayStateChanged, playingMessageObject.getId());
            return true;
        } else {
            downloadingCurrentMessage = false;
        }
        if (returning != null) {
            if (!currentMusicReturn(returning, messageObject) || returnGeneration != musicReturnIntentGeneration.get()) return false;
            returning.paused |= startPaused || !activeMusicReturn(returning);
            startPaused = returning.paused;
            if (!startPaused) {
                boolean granted = acquireMusicReturnFocus();
                if (!currentMusicReturn(returning, messageObject) || returnGeneration != musicReturnIntentGeneration.get()) return false;
                if (!granted) {
                    returning.paused = true;
                }
                returning.paused |= !activeMusicReturn(returning);
                startPaused = returning.paused;
            }
        }
        if (startPaused) {
            if (messageObject.isMusic()) abandonPlaybackFocus(playbackFocusListener, playbackFocusLeaseGeneration);
        } else if (silent || CastSync.isActive()) {
            abandonPlaybackFocus(playbackFocusListener, playbackFocusLeaseGeneration);
        } else if (!checkAudioFocus(messageObject)) {
            if (playbackIntent == playbackIntentGeneration.get() && currentPlaybackIntent(messageObject)) {
                playbackIntentPaused = isPaused = true;
                playingMessageObject = messageObject;
                if (continuation != null) continuation.roundPaused = true;
            }
            return false;
        }
        if (returnGeneration != musicReturnIntentGeneration.get() || playbackIntent != playbackIntentGeneration.get()) return false;
        if (messageObject.isMusic()) {
            checkIsNextMusicFileDownloaded(messageObject.currentAccount);
        } else {
            checkIsNextVoiceFileDownloaded(messageObject.currentAccount);
        }
        roundVideoFrameReadyAt = 0;
        roundVideoFirstFrameRendered = false;
        roundVideoPendingSurface = null;
        if (currentAspectRatioFrameLayout != null) {
            isDrawingWasReady = false;
            currentAspectRatioFrameLayout.setDrawingReady(false);
        }
        boolean isVideo = messageObject.isVideo();
        if (messageObject.isRoundVideo() || isVideo) {
            FileLoader.getInstance(messageObject.currentAccount).setLoadingVideoForPlayer(messageObject.getDocument(), true);
            playerWasReady = false;
            boolean destroyAtEnd = !isVideo || messageObject.messageOwner.peer_id.channel_id == 0 && messageObject.audioProgress <= 0.1f;
            int[] playCount = isVideo && messageObject.getDuration() <= 30 ? new int[]{1} : null;
            if (!saved) {
                clearPlaylist();
            }
            videoPlayer = new VideoPlayer();
            videoPlayer.setLooping(silent);
            int tag = ++playerNum;
            videoPlayer.setDelegate(new VideoPlayer.VideoPlayerDelegate() {
                @Override
                public void onStateChanged(boolean playWhenReady, int playbackState) {
                    if (tag != playerNum) {
                        return;
                    }
                    updateVideoState(messageObject, playCount, destroyAtEnd, playWhenReady, playbackState);
                }

                @Override
                public void onError(VideoPlayer player, Exception e) {
                    failCurrentPlayback(messageObject, tag, player);
                    FileLog.e(e);
                }

                @Override
                public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {
                    currentAspectRatioFrameLayoutRotation = unappliedRotationDegrees;
                    if (unappliedRotationDegrees == 90 || unappliedRotationDegrees == 270) {
                        int temp = width;
                        width = height;
                        height = temp;
                    }
                    currentAspectRatioFrameLayoutRatio = height == 0 ? 1 : (width * pixelWidthHeightRatio) / height;

                    if (currentAspectRatioFrameLayout != null) {
                        currentAspectRatioFrameLayout.setAspectRatio(currentAspectRatioFrameLayoutRatio, currentAspectRatioFrameLayoutRotation);
                    }
                }

                @Override
                public void onRenderedFirstFrame() {
                    onVideoFirstFrame(tag, messageObject.isRoundVideo());
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {
                    if (messageObject.isRoundVideo()) onRoundVideoSurfaceUpdated(tag, surfaceTexture);
                }

                @Override
                public boolean onSurfaceDestroyed(SurfaceTexture surfaceTexture) {
                    if (tag != playerNum) return false;
                    if (videoPlayer == null) {
                        // Do not leave a stale transition token behind when the
                        // player is released concurrently with TextureView.
                        pipSwitchingState = 0;
                        return false;
                    }
                    if (pipSwitchingState == 1) {
                        finishPipCloseForSurfaceTransfer();
                        if (baseActivity != null) {
                            if (pipRoundVideoView == null) {
                                try {
                                    pipRoundVideoView = new PipRoundVideoView();
                                    pipRoundVideoView.show(baseActivity, () -> cleanupPlayer(true, true));
                                } catch (Exception e) {
                                    pipRoundVideoView = null;
                                }
                            }
                            if (pipRoundVideoView != null) {
                                if (pipRoundVideoView.getTextureView().getSurfaceTexture() != surfaceTexture) {
                                    pipRoundVideoView.getTextureView().setSurfaceTexture(surfaceTexture);
                                }
                                bindRoundVideoPip();
                            }
                        }
                        pipSwitchingState = 0;
                        return true;
                    } else if (PhotoViewer.hasInstance() && PhotoViewer.getInstance().isInjectingVideoPlayer()) {
                        PhotoViewer.getInstance().injectVideoPlayerSurface(surfaceTexture);
                        return true;
                    }
                    return false;
                }
            });
            currentAspectRatioFrameLayoutReady = false;
            if (pipClosingToInline && currentTextureView != null) {
                videoPlayer.setTextureView(currentTextureView);
            } else if (pipRoundVideoView != null || !MessagesController.getInstance(messageObject.currentAccount).isDialogVisible(messageObject.getDialogId(), messageObject.scheduled)) {
                if (pipRoundVideoView == null) {
                    try {
                        pipRoundVideoView = new PipRoundVideoView();
                        pipRoundVideoView.show(baseActivity, () -> cleanupPlayer(true, true));
                    } catch (Exception e) {
                        pipRoundVideoView = null;
                    }
                }
                if (pipRoundVideoView != null) {
                    bindRoundVideoPip();
                }
            } else if (currentTextureView != null) {
                videoPlayer.setTextureView(currentTextureView);
            }

            if (exists) {
                if (!messageObject.mediaExists && cacheFile != file) {
                    AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(messageObject.currentAccount).postNotificationName(NotificationCenter.fileLoaded, FileLoader.getAttachFileName(messageObject.getDocument()), cacheFile));
                }
                try {
                videoPlayer.preparePlayer(Uri.fromFile(cacheFile), "other");
                } catch (Exception e) {
                    FileLog.e(e);
                    failCurrentPlayback(messageObject, tag, videoPlayer);
                    return false;
                }
            } else {
                try {
                    int reference = FileLoader.getInstance(messageObject.currentAccount).getFileReference(messageObject);
                    TLRPC.Document document = messageObject.getDocument();
                    String params = "?account=" + messageObject.currentAccount +
                            "&id=" + document.id +
                            "&hash=" + document.access_hash +
                            "&dc=" + document.dc_id +
                            "&size=" + document.size +
                            "&mime=" + URLEncoder.encode(document.mime_type, "UTF-8") +
                            "&rid=" + reference +
                            "&name=" + URLEncoder.encode(FileLoader.getDocumentFileName(document), "UTF-8") +
                            "&reference=" + Utilities.bytesToHex(document.file_reference != null ? document.file_reference : new byte[0]);
                    Uri uri = Uri.parse("tg://" + messageObject.getFileName() + params);
                    videoPlayer.preparePlayer(uri, "other");
                } catch (Exception e) {
                    FileLog.e(e);
                    failCurrentPlayback(messageObject, tag, videoPlayer);
                    return false;
                }
            }
            if (returnGeneration != musicReturnIntentGeneration.get() || playbackIntent != playbackIntentGeneration.get()
                    || videoPlayer == null) return false;
            if (messageObject.isRoundVideo()) {
                videoPlayer.setStreamType(useFrontSpeaker ? AudioManager.STREAM_VOICE_CALL : AudioManager.STREAM_MUSIC);
                if (Math.abs(currentPlaybackSpeed - 1.0f) > 0.001f) {
                    videoPlayer.setPlaybackSpeed(Math.round(currentPlaybackSpeed * 10f) / 10f);
                }

                if (messageObject.forceSeekTo >= 0) {
                    messageObject.audioProgress = seekToProgressPending = messageObject.forceSeekTo;
                    messageObject.forceSeekTo = -1;
                }
            } else {
                videoPlayer.setStreamType(AudioManager.STREAM_MUSIC);
            }
        } else {
            if (pipRoundVideoView != null) {
                closePipRoundVideo(true);
            }
            try {
                audioPlayer = new VideoPlayer();
                int tag = ++playerNum;
                audioPlayer.setDelegate(new VideoPlayer.VideoPlayerDelegate() {
                    @Override
                    public void onStateChanged(boolean playWhenReady, int playbackState) {
                        if (tag != playerNum) {
                            return;
                        }
                        if (playbackState == ExoPlayer.STATE_ENDED || !isTemporaryPlayback(messageObject) && (playbackState == ExoPlayer.STATE_IDLE || playbackState == ExoPlayer.STATE_BUFFERING) && playWhenReady && messageObject.audioProgress >= 0.999f) {
                            if (playingMessageObject != messageObject) return;
                            if (isTemporaryPlayback(messageObject) && (!playWhenReady || isPaused || playbackIntentPaused)) return;
                            if (ownsRoundPlayback(savedMusicPlaylistState, messageObject, tag)
                                    && (playbackState != ExoPlayer.STATE_ENDED || !playWhenReady || isPaused)) return;
                            messageObject.audioProgress = 1f;
                            NotificationCenter.getInstance(messageObject.currentAccount).postNotificationName(NotificationCenter.messagePlayingProgressDidChanged, messageObject.getId(), 0);
                            if (tag != playerNum || playingMessageObject != messageObject) return;
                            if (finishRoundPlayback(messageObject, tag, playWhenReady, playbackState)) return;
                            final boolean restored = restoreMusicPlaylistState();
                            if (!restored) {
                                if (!isTemporaryPlayback(messageObject) && !playlist.isEmpty()) {
                                    playNextMessageWithoutOrder(true);
                                } else {
                                    cleanupPlayer(true, hasNoNextVoiceOrRoundVideoMessage(), isTemporaryPlayback(messageObject), false);
                                }
                            }
                        } else if (audioPlayer != null && seekToProgressPending != 0 && (playbackState == ExoPlayer.STATE_READY || playbackState == ExoPlayer.STATE_IDLE)) {
                            int seekTo = (int) (audioPlayer.getDuration() * seekToProgressPending);
                            audioPlayer.seekTo(seekTo);
                            lastProgress = seekTo;
                            seekToProgressPending = 0;
                        }
                        if (audioPlayer != null && CastSync.isActive()) {
                            audioPlayer.setMute(true);
                        }
                    }

                    @Override
                    public void onError(VideoPlayer player, Exception e) {

                        failCurrentPlayback(messageObject, tag, player);
                    }

                    @Override
                    public void onVideoSizeChanged(int width, int height, int unappliedRotationDegrees, float pixelWidthHeightRatio) {

                    }

                    @Override
                    public void onRenderedFirstFrame() {
                    }
                });
                audioPlayer.setAudioVisualizerDelegate(new VideoPlayer.AudioVisualizerDelegate() {
                    @Override
                    public void onVisualizerUpdate(boolean playing, boolean animate, float[] values) {
                        Theme.getCurrentAudiVisualizerDrawable().setWaveform(playing, animate, values);
                    }

                    @Override
                    public boolean needUpdate() {
                        return Theme.getCurrentAudiVisualizerDrawable().getParentView() != null;
                    }
                });
                if (exists) {
                    if (!messageObject.mediaExists && cacheFile != file) {
                        AndroidUtilities.runOnUIThread(() -> NotificationCenter.getInstance(messageObject.currentAccount).postNotificationName(NotificationCenter.fileLoaded, FileLoader.getAttachFileName(messageObject.getDocument()), cacheFile));
                    }
                    audioPlayer.preparePlayer(Uri.fromFile(cacheFile), "other");
                    isStreamingCurrentAudio = false;
                } else {
                    int reference = FileLoader.getInstance(messageObject.currentAccount).getFileReference(messageObject);
                    TLRPC.Document document = messageObject.getDocument();
                    String params = "?account=" + messageObject.currentAccount +
                            "&id=" + document.id +
                            "&hash=" + document.access_hash +
                            "&dc=" + document.dc_id +
                            "&size=" + document.size +
                            "&mime=" + URLEncoder.encode(document.mime_type, "UTF-8") +
                            "&rid=" + reference +
                            "&name=" + URLEncoder.encode(FileLoader.getDocumentFileName(document), "UTF-8") +
                            "&reference=" + Utilities.bytesToHex(document.file_reference != null ? document.file_reference : new byte[0]);
                    Uri uri = Uri.parse("tg://" + messageObject.getFileName() + params);
                    audioPlayer.preparePlayer(uri, "other");
                    isStreamingCurrentAudio = true;
                }
                if (messageObject.isVoice()) {
                    String name = messageObject.getFileName();
                    if (name != null && messageObject.getDuration() >= 5 * 60) {
                        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("media_saved_pos", Activity.MODE_PRIVATE);
                        float pos = preferences.getFloat(name, -1);
                        if (pos > 0 && pos < 0.99f) {
                            messageObject.audioProgress = seekToProgressPending = pos;
                        }
                        shouldSavePositionForCurrentAudio = name;
                    }
                    if (Math.abs(currentPlaybackSpeed - 1.0f) > 0.001f) {
                        audioPlayer.setPlaybackSpeed(Math.round(currentPlaybackSpeed * 10f) / 10f);
                    }
                    audioInfoLoadGeneration++;
                    audioInfo = null;
                    if (!saved) {
                        clearPlaylist();
                    }
                } else {
                    audioInfo = null;
                    loadAudioInfoAsync(messageObject, cacheFile);
                    String name = messageObject.getFileName();
                    if (!TextUtils.isEmpty(name) && messageObject.getDuration() >= 10 * 60) {
                        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("media_saved_pos", Activity.MODE_PRIVATE);
                        float pos = preferences.getFloat(name, -1);
                        if (pos > 0 && pos < 0.999f) {
                            messageObject.audioProgress = seekToProgressPending = pos;
                        }
                        shouldSavePositionForCurrentAudio = name;
                        if (Math.abs(currentMusicPlaybackSpeed - 1.0f) > 0.001f) {
                            audioPlayer.setPlaybackSpeed(Math.round(currentMusicPlaybackSpeed * 10f) / 10f);
                        }
                    }
                }
                if (messageObject.forceSeekTo >= 0) {
                    messageObject.audioProgress = seekToProgressPending = messageObject.forceSeekTo;
                    messageObject.forceSeekTo = -1;
                }
                if (messageObject != null && messageObject.isMusic() && messageObject.getDocument() != null && !DialogObject.isEncryptedDialog(messageObject.getDialogId())) {
                    if (reporter == null || reporter.currentAccount != messageObject.currentAccount) {
                        if (reporter != null) {
                            reporter.destroy();
                        }
                        reporter = new MusicListenReporter(messageObject.currentAccount);
                    }
                    final TLRPC.Document document = messageObject.getDocument();
                    final TLRPC.TL_inputDocument id = new TLRPC.TL_inputDocument();
                    id.id = document.id;
                    id.access_hash = document.access_hash;
                    id.file_reference = document.file_reference;
                    if (id.file_reference == null) {
                        id.file_reference = new byte[0];
                    }
                    reporter.setup(id);
                } else if (reporter != null) {
                    reporter.destroy();
                    reporter = null;
                }
                if (audioPlayer.player != null && reporter != null) {
                    audioPlayer.player.addListener(reporter.getPlayerListener(audioPlayer.player));
                }
                audioPlayer.setStreamType(useFrontSpeaker ? AudioManager.STREAM_VOICE_CALL : AudioManager.STREAM_MUSIC);
                if (returning != null) {
                    if (!currentMusicReturn(returning, messageObject) || returnGeneration != musicReturnIntentGeneration.get()) return false;
                    returning.player = audioPlayer;
                    returning.paused |= !activeMusicReturn(returning);
                    startPaused |= returning.paused;
                }
                if (returnGeneration != musicReturnIntentGeneration.get()) return false;
                if (!messageObject.isVoice()) {
                    if (audioVolumeAnimator != null) {
                        audioVolumeAnimator.removeAllListeners();
                        audioVolumeAnimator.cancel();
                    }
                    audioVolumeAnimator = ValueAnimator.ofFloat(audioVolume, 1f);
                    audioVolumeAnimator.addUpdateListener(audioVolumeUpdateListener);
                    audioVolumeAnimator.setDuration(300);
                    audioVolumeAnimator.start();
                } else {
                    audioVolume = 1f;
                    setPlayerVolume();
                }
            } catch (Exception e) {
                FileLog.e(e);
                NotificationCenter.getInstance(messageObject.currentAccount).postNotificationName(NotificationCenter.messagePlayingPlayStateChanged, playingMessageObject != null ? playingMessageObject.getId() : 0);
                if (returnGeneration != musicReturnIntentGeneration.get()) return false;
                if (audioPlayer != null) {
                    if (reporter != null) {
                        reporter.destroy();
                        reporter = null;
                    }
                    audioPlayer.releasePlayer(true);
                    audioPlayer = null;
                    Theme.unrefAudioVisualizeDrawable(playingMessageObject);
                    isPaused = false;
                    playingMessageObject = null;
                    downloadingCurrentMessage = false;
                }
                SavedMusicPlaylistState failedBookmark = savedMusicPlaylistState;
                if (failedBookmark != null && failedBookmark.roundLed) {
                    if (failedBookmark.currentSource()) restoreMusicSeek(failedBookmark, failedBookmark.playingMessage);
                    clearMusicPlaylistState();
                }
                retirePlaybackIntent(messageObject, playbackIntent);
                return false;
            }
        }
        if (!currentPlaybackIntent(messageObject) || playbackIntent != playbackIntentGeneration.get()) return false;
        startPaused |= playbackIntentPaused;
        setPlayerVolume();

        isPaused = startPaused;
        lastProgress = 0;
        playingMessageObject = messageObject;
        if (continuation != null) continuation.spokenPlayerNum = playerNum;
        if (resumeAudioOnFocusGain && focusResumeMessage == messageObject
                && focusResumeIntent == playbackIntentGeneration.get()) focusResumePlayerNum = playerNum;
        if (returning != null && pendingMusicReturn == returning) pendingMusicReturn = null;
        if (!SharedConfig.enabledRaiseTo(true)) {
            startRaiseToEarSensors(raiseChat);
        }
        if (!ApplicationLoader.mainInterfacePaused && proximityWakeLock != null && !proximityWakeLock.isHeld() && (playingMessageObject.isVoice() || playingMessageObject.isRoundVideo()) && SharedConfig.enabledRaiseTo(false)) {
//            proximityWakeLock.acquire();
        }
        if (!startPaused) startProgressTimer(playingMessageObject);
        NotificationCenter.getInstance(messageObject.currentAccount).postNotificationName(NotificationCenter.messagePlayingDidStart, messageObject, oldMessageObject);

        if (returnGeneration != musicReturnIntentGeneration.get() || playingMessageObject != messageObject) return false;
        if (videoPlayer != null) {
            try {
                if (playingMessageObject.audioProgress != 0) {
                    long duration = videoPlayer.getDuration();
                    if (duration == C.TIME_UNSET) {
                        duration = (long) playingMessageObject.getDuration() * 1000;
                    }
                    int seekTo = (int) (duration * playingMessageObject.audioProgress);
                    if (playingMessageObject.audioProgressMs != 0) {
                        seekTo = playingMessageObject.audioProgressMs;
                        playingMessageObject.audioProgressMs = 0;
                    }
                    videoPlayer.seekTo(seekTo, playingMessageObject.isRoundVideo());
                }
            } catch (Exception e2) {
                playingMessageObject.audioProgress = 0;
                playingMessageObject.audioProgressSec = 0;
                NotificationCenter.getInstance(messageObject.currentAccount).postNotificationName(NotificationCenter.messagePlayingProgressDidChanged, playingMessageObject.getId(), 0);
                FileLog.e(e2);
            }
        } else if (audioPlayer != null) {
            try {
                if (playingMessageObject.audioProgress != 0) {
                    long duration = audioPlayer.getDuration();
                    if (duration == C.TIME_UNSET) {
                        duration = (long) playingMessageObject.getDuration() * 1000;
                    }
                    int seekTo = (int) (duration * playingMessageObject.audioProgress);
                    if (restorePositionMs > 0) seekTo = restorePositionMs;
                    audioPlayer.seekTo(seekTo);
                    if (!ignorePlayerUpdate) {
                        CastSync.seekTo(seekTo);
                    }
                }
            } catch (Exception e2) {
                playingMessageObject.resetPlayingProgress();
                NotificationCenter.getInstance(messageObject.currentAccount).postNotificationName(NotificationCenter.messagePlayingProgressDidChanged, playingMessageObject.getId(), 0);
                FileLog.e(e2);
            }
        }
        if (returnGeneration != musicReturnIntentGeneration.get() || playingMessageObject != messageObject
                || playbackIntent != playbackIntentGeneration.get()) return false;
        startPaused |= playbackIntentPaused || isPaused;
        if (!startPaused) {
            if (!checkAudioFocus(messageObject)) {
                playbackIntentPaused = isPaused = true;
                return false;
            }
            if (returnGeneration != musicReturnIntentGeneration.get() || playingMessageObject != messageObject
                    || playbackIntent != playbackIntentGeneration.get()) return false;
            if (videoPlayer != null) videoPlayer.play();
            else if (audioPlayer != null) audioPlayer.play();
        }
        if (canStartMusicPlayerService()) {
            Intent intent = new Intent(ApplicationLoader.applicationContext, MusicPlayerService.class);
            try {
                /*if (Build.VERSION.SDK_INT >= 26) {
                    ApplicationLoader.applicationContext.startForegroundService(intent);
                } else {*/
                ApplicationLoader.applicationContext.startService(intent);
                //}
            } catch (Throwable e) {
                FileLog.e(e);
            }
        } else {
            Intent intent = new Intent(ApplicationLoader.applicationContext, MusicPlayerService.class);
            ApplicationLoader.applicationContext.stopService(intent);
        }

        try {
            CastSync.check(CastSync.TYPE_MUSIC);
            if (!ignorePlayerUpdate) {
                if (ChromecastController.getInstance().isCasting()) {
                    ChromecastController.getInstance().setCurrentMediaAndCastIfNeeded(getCurrentChromecastMedia());
                }
                CastSync.setPlaying(!startPaused);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }

        return true;
    }

    private boolean ignorePlayerUpdate;
    public void syncCastedPlayer() {
        if (playingMessageObject == null) return;

        ignorePlayerUpdate = true;

        if (CastSync.isActive() && !CastSync.isUpdatePending()) {
            final long castedPosition = CastSync.getPosition();
            final long currentPosition = getProgressMs(playingMessageObject);
            if (currentPosition >= 0 && castedPosition >= 0 && Math.abs(currentPosition - castedPosition) > 1000) {
                seekToProgressMs(playingMessageObject, castedPosition);
            }
            if (CastSync.isPlaying()) {
                playMessage(playingMessageObject);
            } else {
                pauseMessage(playingMessageObject);
            }
            setPlaybackSpeed(true, CastSync.getSpeed());
        }
        setPlayerVolume();

        ignorePlayerUpdate = false;
    }

    public long getCurrentPosition() {
        if (playingMessageObject == null) return -1;
        return getProgressMs(playingMessageObject);
    }

    public ChromecastMediaVariations getCurrentChromecastMedia() {
        if (playingMessageObject == null) {
            return null;
        }

        final String title = playingMessageObject.getMusicTitle();
        final String subtitle = playingMessageObject.getMusicAuthor();
        final TLRPC.Document document = playingMessageObject.getDocument();

        if (playingMessageObject.isRoundVideo() || playingMessageObject.isVideo() || playingMessageObject.isMusic()) {
            File file = null;
            if (playingMessageObject.attachPathExists && playingMessageObject.messageOwner != null) {
                file = new File(playingMessageObject.messageOwner.attachPath);
            }
            if (file == null || !file.exists()) {
                file = FileLoader.getInstance(playingMessageObject.currentAccount).getPathToMessage(playingMessageObject.messageOwner);;
            }
            if (file != null && file.exists()) {
                final String mime = playingMessageObject.getMimeType();
                final Uri uri = Uri.parse("file://" + file.getAbsolutePath());

                final MediaMetadata metadata = new MediaMetadata();
                if (audioInfo != null) {
                    if (!TextUtils.isEmpty(audioInfo.getTitle())) {
                        metadata.putString(MediaMetadata.KEY_TITLE, audioInfo.getTitle());
                    }
                    if (!TextUtils.isEmpty(audioInfo.getArtist())) {
                        metadata.putString(MediaMetadata.KEY_ARTIST, audioInfo.getArtist());
                    }
                    if (!TextUtils.isEmpty(audioInfo.getAlbum())) {
                        metadata.putString(MediaMetadata.KEY_ALBUM_TITLE, audioInfo.getAlbum());
                    }
                    if (!TextUtils.isEmpty(audioInfo.getAlbumArtist())) {
                        metadata.putString(MediaMetadata.KEY_ALBUM_ARTIST, audioInfo.getAlbumArtist());
                    }
                    if (!TextUtils.isEmpty(audioInfo.getComposer())) {
                        metadata.putString(MediaMetadata.KEY_COMPOSER, audioInfo.getComposer());
                    }
                    if (audioInfo.getDisc() != 0) {
                        metadata.putInt(MediaMetadata.KEY_DISC_NUMBER, (int) audioInfo.getDisc());
                    }
                    if (audioInfo.getTrack() != 0) {
                        metadata.putInt(MediaMetadata.KEY_TRACK_NUMBER, (int) audioInfo.getTrack());
                    }
                    if (audioInfo.getCover() != null) {
                        File coverFile = audioInfo.getCoverFile();
                        if (coverFile == null || !coverFile.exists()) {
                            coverFile = StoryEntry.makeCacheFile(UserConfig.selectedAccount, "jpg");
                            FileOutputStream stream = null;
                            try {
                                audioInfo.getCover().compress(Bitmap.CompressFormat.JPEG, 80, stream = new FileOutputStream(coverFile));
                            } catch (Exception e) {
                                FileLog.e(e);
                                coverFile = null;
                            } finally {
                                if (stream != null) {
                                    try {
                                        stream.close();
                                    } catch (Exception e2) {
                                        FileLog.e(e2);
                                    }
                                }
                            }
                            audioInfo.setCoverFile(coverFile);
                        }
                        if (coverFile != null && coverFile.exists()) {
                            final String path = ChromecastController.getInstance().setCover(coverFile);
                            metadata.addImage(new WebImage(Uri.parse(ChromecastFileServer.getUrlToSource(ChromecastFileServer.getHost(), path))));
                        }
                    }
                }
                final ChromecastMedia media = ChromecastMedia.Builder.fromUri(uri, "/player_" + playingMessageObject.getId(), mime)
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setMetadata(metadata)
                    .build();

                return ChromecastMediaVariations.of(media);
            }
        }

        if (videoPlayer != null) {
            return videoPlayer.getCurrentChromecastMedia((document != null ? document.id : playingMessageObject.getId()) + "", title, subtitle);
        }

        if (audioPlayer != null) {
            return audioPlayer.getCurrentChromecastMedia((document != null ? document.id : playingMessageObject.getId()) + "", title, subtitle);
        }

        return null;
    }

    private boolean canStartMusicPlayerService() {
        return playingMessageObject != null && (playingMessageObject.isMusic() || playingMessageObject.isVoice() || playingMessageObject.isRoundVideo()) && !playingMessageObject.isVoiceOnce() && !playingMessageObject.isRoundOnce();
    }

    public void updateSilent(boolean value) {
        isSilent = value;
        if (videoPlayer != null) {
            videoPlayer.setLooping(value);
        }
        setPlayerVolume();
        checkVolumeBarUI();
        if (playingMessageObject != null) {
            NotificationCenter.getInstance(playingMessageObject.currentAccount).postNotificationName(NotificationCenter.messagePlayingPlayStateChanged, playingMessageObject != null ? playingMessageObject.getId() : 0);
        }
    }

    public AudioInfo getAudioInfo() {
        return audioInfo;
    }

    private void loadAudioInfoAsync(MessageObject messageObject, File file) {
        if (messageObject == null || file == null || !file.exists() || !messageObject.isMusic()) {
            return;
        }
        final int generation = ++audioInfoLoadGeneration;
        final int account = messageObject.currentAccount;
        final int messageId = messageObject.getId();
        final long dialogId = messageObject.getDialogId();
        final String cacheKey = file.getAbsolutePath() + ':' + file.length() + ':' + file.lastModified();
        if (cacheKey.equals(cachedAudioInfoKey) && cachedAudioInfo != null) {
            audioInfo = cachedAudioInfo;
            notifyPlayingMetadataChanged(messageObject);
            return;
        }
        audioInfoQueue.postRunnable(() -> {
            AudioInfo loadedAudioInfo = null;
            try {
                loadedAudioInfo = AudioInfo.getAudioInfo(file);
            } catch (Exception e) {
                FileLog.e(e);
            }
            final AudioInfo result = loadedAudioInfo;
            AndroidUtilities.runOnUIThread(() -> {
                MessageObject playing = playingMessageObject;
                if (generation != audioInfoLoadGeneration
                        || playing == null
                        || playing.currentAccount != account
                        || playing.getId() != messageId
                        || playing.getDialogId() != dialogId) {
                    return;
                }
                if (result != null) {
                    cachedAudioInfoKey = cacheKey;
                    cachedAudioInfo = result;
                    audioInfo = result;
                    notifyPlayingMetadataChanged(playing);
                }
            });
        });
    }

    private void notifyPlayingMetadataChanged(MessageObject messageObject) {
        NotificationCenter.getInstance(messageObject.currentAccount).postNotificationName(
                NotificationCenter.messagePlayingMetadataChanged,
                messageObject
        );
    }

    public void setPlaybackOrderType(int type) {
        cancelAutomaticMusicReturn();
        boolean oldShuffle = SharedConfig.shuffleMusic;
        SharedConfig.setPlaybackOrderType(type);
        if (oldShuffle != SharedConfig.shuffleMusic) {
            if (SharedConfig.shuffleMusic) {
                buildShuffledPlayList();
            } else {
                if (playingMessageObject != null) {
                    currentPlaylistNum = playlist.indexOf(playingMessageObject);
                    if (currentPlaylistNum == -1) {
                        clearPlaylist();
                        cleanupPlayer(true, true);
                    }
                }
            }
        }
    }

    public boolean isStreamingCurrentAudio() {
        return isStreamingCurrentAudio;
    }

    public boolean isCurrentPlayer(VideoPlayer player) {
        return videoPlayer == player || audioPlayer == player;
    }

    public void tryResumePausedAudio() {
        MessageObject message = getPlayingMessageObject();
        if (message!= null && isMessagePaused() && wasPlayingAudioBeforePause && (message.isVoice() || message.isMusic())) {
            playMessage(message);
        }
        wasPlayingAudioBeforePause = false;
    }

    void onAudioBecomingNoisy() {
        cancelAutomaticMusicReturn();
        pauseMessage(playingMessageObject);
    }
    public boolean pauseMessage(MessageObject messageObject) {
        return pauseMessage(messageObject, true);
    }

    public boolean pauseMessage(MessageObject messageObject, boolean smoothFade) {
        return pauseMessage(messageObject, smoothFade, false);
    }

    private boolean pauseMessage(MessageObject messageObject, boolean smoothFade, boolean fromFocus) {
        if (messageObject != null && (playbackIntentMessage == messageObject || isSamePlayingMessage(messageObject))) {
            if (!fromFocus) playbackIntentGeneration.incrementAndGet();
            playbackIntentPaused = true;
            resumeAudioOnFocusGain = false;
            focusResumeMessage = null;
        }
        SavedMusicPlaylistState state = savedMusicPlaylistState;
        PendingMusicReturn returning = pendingMusicReturn;
        if (messageObject != null && returning != null && returning.state.matchesSong(messageObject)) {
            musicReturnIntentGeneration.incrementAndGet();
            returning.paused = true;
            resumeAudioOnFocusGain = false;
            if (playingMessageObject == returning.message) isPaused = true;
            if (returning.player != null && audioPlayer == returning.player) returning.player.pause();
        }
        if (messageObject != null && state != null && state.roundLed && state.matchesSong(messageObject)) cancelAutomaticMusicReturn();
        if (messageObject != null && ownsRoundPlayback(state, playingMessageObject, playerNum) && isSamePlayingMessage(messageObject)) {
            musicReturnIntentGeneration.incrementAndGet();
            resumeAudioOnFocusGain = false;
            state.roundPaused = true;
        }
        if (downloadingCurrentMessage && messageObject != null && isSamePlayingMessage(messageObject)) {
            isPaused = true;
            NotificationCenter.getInstance(messageObject.currentAccount).postNotificationName(NotificationCenter.messagePlayingPlayStateChanged, messageObject.getId());
            return true;
        }
        if (audioPlayer == null && videoPlayer == null || messageObject == null || playingMessageObject == null || !isSamePlayingMessage(messageObject)) {
            return false;
        }
        stopProgressTimer();
        try {
            isPaused = true;
            if (audioPlayer != null) {
                if (smoothFade && !CastSync.isActive() && !playingMessageObject.isVoice() && (playingMessageObject.getDuration() * (1f - playingMessageObject.audioProgress) > 1) && LaunchActivity.isResumed) {
                    if (audioVolumeAnimator != null) {
                        audioVolumeAnimator.removeAllListeners();
                        audioVolumeAnimator.removeAllUpdateListeners();
                        audioVolumeAnimator.cancel();
                    }
                    final VideoPlayer pausingPlayer = audioPlayer;
                    audioVolumeAnimator = ValueAnimator.ofFloat(audioVolume, 0);
                    audioVolumeAnimator.addUpdateListener(audioVolumeUpdateListener);
                    audioVolumeAnimator.setDuration(300);
                    audioVolumeAnimator.addListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationEnd(Animator animation) {
                            if (audioVolumeAnimator == animation && audioPlayer == pausingPlayer && isPaused) {
                                pausingPlayer.pause();
                            }
                        }
                    });
                    audioVolumeAnimator.start();
                } else {
                    audioPlayer.pause();
                }
            } else if (videoPlayer != null) {
                videoPlayer.pause();
            }
            NotificationCenter.getInstance(playingMessageObject.currentAccount).postNotificationName(NotificationCenter.messagePlayingPlayStateChanged, playingMessageObject.getId());
        } catch (Exception e) {
            FileLog.e(e);
            isPaused = false;
            return false;
        }

        try {
            CastSync.check(CastSync.TYPE_MUSIC);
            if (!ignorePlayerUpdate) {
                if (ChromecastController.getInstance().isCasting()) {
                    ChromecastController.getInstance().setCurrentMediaAndCastIfNeeded(getCurrentChromecastMedia());
                }
                CastSync.setPlaying(false);
            }
        } catch (Exception e) {
            FileLog.e(e);
        }

        return true;
    }

    private boolean resumeAudio(MessageObject messageObject) {
        if (audioPlayer == null && videoPlayer == null || messageObject == null || playingMessageObject == null || !isSamePlayingMessage(messageObject)) {
            return false;
        }

        final MessageObject owner = playingMessageObject;
        final int tag = playerNum;
        final long intent = playbackIntentGeneration.get();
        if (!checkAudioFocus(owner)) {
            playbackIntentPaused = true;
            return false;
        }
        if (playingMessageObject != owner || playerNum != tag || intent != playbackIntentGeneration.get()) return false;
        try {
            startProgressTimer(playingMessageObject);
            if (audioVolumeAnimator != null) {
                audioVolumeAnimator.removeAllListeners();
                audioVolumeAnimator.cancel();
            }
            if (!messageObject.isVoice() && !messageObject.isRoundVideo()) {
                audioVolumeAnimator = ValueAnimator.ofFloat(audioVolume, 1f);
                audioVolumeAnimator.addUpdateListener(audioVolumeUpdateListener);
                audioVolumeAnimator.setDuration(300);
                audioVolumeAnimator.start();
            } else {
                audioVolume = 1f;
                setPlayerVolume();
            }
            if (audioPlayer != null) {
                audioPlayer.play();
            } else if (videoPlayer != null) {
                videoPlayer.play();
            }
            isPaused = false;
            NotificationCenter.getInstance(playingMessageObject.currentAccount).postNotificationName(NotificationCenter.messagePlayingPlayStateChanged, playingMessageObject.getId());

            try {
                CastSync.check(CastSync.TYPE_MUSIC);
                if (!ignorePlayerUpdate) {
                    CastSync.setPlaying(true);
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
        } catch (Exception e) {
            FileLog.e(e);
            return false;
        }
        return true;
    }

    public boolean isVideoDrawingReady() {
        return currentAspectRatioFrameLayout != null && currentAspectRatioFrameLayout.isDrawingReady();
    }

    public ArrayList<MessageObject> getPlaylist() {
        return playlist;
    }

    public MessagesController.SavedMusicList getMusicList() {
        return currentSavedMusicList;
    }

    public boolean isPlayingMessage(MessageObject messageObject) {
        if (messageObject != null && messageObject.isRepostPreview) {
            return false;
        }
        if (audioPlayer == null && videoPlayer == null || messageObject == null || playingMessageObject == null) {
            return false;
        }
        if (playingMessageObject.currentAccount == messageObject.currentAccount
                && playingMessageObject.eventId != 0 && playingMessageObject.eventId == messageObject.eventId) {
            return !downloadingCurrentMessage;
        }
        if (isSamePlayingMessage(messageObject)) {
            return !downloadingCurrentMessage;
        }
        //
        return false;
    }

    public boolean isPlayingMessageAndReadyToDraw(MessageObject messageObject) {
        return isDrawingWasReady && isPlayingMessage(messageObject);
    }

    public boolean isMessagePaused() {
        return isPaused || downloadingCurrentMessage;
    }

    public boolean isDownloadingCurrentMessage() {
        return downloadingCurrentMessage;
    }

    public void setReplyingMessage(MessageObject replyToMsg, MessageObject replyToTopMsg, TL_stories.StoryItem storyItem) {
        recordReplyingMsg = replyToMsg;
        recordReplyingTopMsg = replyToTopMsg;
        recordReplyingStory = storyItem;
    }

    public long acquireRecordAudioFocus() {
        cancelAutomaticMusicReturn();
        final long generation;
            synchronized (recordAudioFocusGeneration) {
            generation = recordAudioFocusGeneration.incrementAndGet();
            }
            if (!hasRecordAudioFocus && SharedConfig.pauseMusicOnRecord) {
                int result = NotificationsController.audioManager.requestAudioFocus(audioRecordFocusChangedListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT);
                if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    hasRecordAudioFocus = true;
                }
            }
        return generation;
    }

    public void requestRecordAudioFocus(boolean request) {
        if (request) {
            acquireRecordAudioFocus();
        } else if (hasRecordAudioFocus) {
                NotificationsController.audioManager.abandonAudioFocus(audioRecordFocusChangedListener);
                hasRecordAudioFocus = false;
        }
    }

    public boolean isAudioRecorderV2Enabled(int account) {
        return shouldUseAudioRecorderV2(account);
    }

    public static boolean shouldUseAudioRecorderV2(int account) {
        return SharedSettings.audioRecorderV2Enabled.isSet() ?
            SharedSettings.audioRecorderV2Enabled.get() :
            !MessagesController.getInstance(account).config.audioRecorderV2Disabled.get();
    }

    private void startRecordingV2(
            int currentAccount,
            long dialogId,
            MessageObject replyToMsg,
            MessageObject replyToTopMsg,
            TL_stories.StoryItem replyStory,
            int guid,
            boolean paused,
            SendMessageChatArguments sendMessageChatArguments,
            long monoForumPeerId,
            MessageSuggestionParams suggestionParams
    ) {
        final long topicId = replyToTopMsg == null
                ? 0
                : MessageObject.getTopicId(currentAccount, replyToTopMsg.messageOwner, false);
        final AudioRecorderV2Owner owner = new AudioRecorderV2Owner(currentAccount, guid,
                recordAudioFocusGeneration.get(), dialogId, topicId);
        audioRecorderV2Owner = owner;
        recordingAudioFocusGeneration = owner.focusGeneration;
        audioRecorderV2Active = true;
        audioRecorderV2Stopping = false;
        audioRecorderV2VoiceOnce = false;
        recordingCurrentAccount = currentAccount;
        recordingGuid = guid;
        recordDialogId = dialogId;
        recordMonoForumPeerId = monoForumPeerId;
        recordMonoForumSuggestionParams = suggestionParams;
        recordTopicId = topicId;
        recordReplyingMsg = replyToMsg;
        recordReplyingTopMsg = replyToTopMsg;
        recordReplyingStory = replyStory;
        recordSendMessageChatArguments = sendMessageChatArguments;
        recordingPrevAudioFile = null;
        recordingAudioFile = null;
        recordTimeCount = 0L;
        writtenFrame = 0;
        samplesCount = 0L;
        sendAfterDone = 0;

        owner.logoutObserver = (id, account, args) -> {
            if (id == NotificationCenter.appDidLogout && account == owner.account && audioRecorderV2Owner == owner
                    && !ownsAudioRecorderV2Session(owner)) {
                cleanRecording(true);
            }
        };
        NotificationCenter.getInstance(currentAccount).addObserver(owner.logoutObserver, NotificationCenter.appDidLogout);
        audioRecorderV2StartRunnable = () -> {
            if (!ownsAudioRecorderV2(owner)) return;
            audioRecorderV2StartRunnable = null;
            setBluetoothScoOn(true);

            recordingAudio = new TLRPC.TL_document();
            recordingAudio.file_reference = new byte[0];
            recordingAudio.dc_id = Integer.MIN_VALUE;
            recordingAudio.id = SharedConfig.getLastLocalId();
            recordingAudio.user_id = UserConfig.getInstance(currentAccount).getClientUserId();
            recordingAudio.mime_type = AudioRecordingSession.MIME_TYPE;
            SharedConfig.saveConfig();

            try {
                audioRecorderV2Controller = new AudioRecorderV2Controller(
                        ApplicationLoader.applicationContext,
                        FileLoader.getDirectory(FileLoader.MEDIA_DIR_AUDIO),
                        createAudioRecorderV2Listener(owner)
                );
                if (!audioRecorderV2Controller.start()) {
                    throw new IllegalStateException("Unable to start AudioRecorderV2");
                }
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("AudioRecorderV2 selected: account=" + currentAccount
                            + ", dialog=" + dialogId
                            + ", guid=" + guid);
                }
            } catch (Exception error) {
                onAudioRecorderV2Error(owner, error);
            }
        };
        AndroidUtilities.runOnUIThread(audioRecorderV2StartRunnable, paused ? 500L : 50L);
    }

    private boolean ownsAudioRecorderV2(AudioRecorderV2Owner owner) {
        return ownsAudioRecorderV2Session(owner)
                && recordAudioFocusGeneration.get() == owner.focusGeneration;
    }

    private boolean ownsAudioRecorderV2Session(AudioRecorderV2Owner owner) {
        return owner != null && audioRecorderV2Owner == owner && audioRecorderV2Active
                && recordingCurrentAccount == owner.account
                && app.nimarkogram.messenger.notifications.InAppNotifications.isCurrent(owner.account, owner.userId, owner.loginSession)
                && UserConfig.getInstance(owner.account).loginTime == owner.loginTime;
    }

    private boolean isAudioRecorderV2PreviewDraftCurrent(AudioRecorderV2Owner owner, AudioRecorderV2Controller controller) {
        if (!ownsAudioRecorderV2Session(owner) || controller == null || controller != audioRecorderV2Controller
                || !controller.isPreviewing() || recordDialogId != owner.dialogId || recordTopicId != owner.topicId
                || recordingAudio == null || recordingAudio.user_id != owner.userId || recordingAudioFile == null) {
            return false;
        }
        final MediaDataController.DraftVoice draft = owner.draft;
        return draft != null && !draft.isDiscarded() && draft.id == recordingAudio.id
                && draft.path != null && draft.path.equals(recordingAudioFile.getAbsolutePath())
                && recordingAudioFile.exists()
                && MediaDataController.getInstance(owner.account).getDraftVoice(owner.dialogId, owner.topicId) == draft;
    }

    private AudioRecorderV2Controller.Listener createAudioRecorderV2Listener(AudioRecorderV2Owner owner) {
        return new AudioRecorderV2Controller.Listener() {
            @Override
            public void onOutputStarted(File file) {
                if (!ownsAudioRecorderV2(owner)) return;
                AutoDeleteMediaTask.lockFile(file);
                recordingAudioFile = file;
            }

            @Override
            public void onOutputInvalidated(File file) {
                AutoDeleteMediaTask.unlockFile(file);
                if (audioRecorderV2Owner == owner && file.equals(recordingAudioFile)) {
                    recordingAudioFile = null;
                }
            }

            @Override
            public void onRecordingStarted() {
                if (!ownsAudioRecorderV2(owner)) return;
                recordStartTime = System.currentTimeMillis();
                if (!audioRecorderV2Stopping) {
                    NotificationCenter.getInstance(recordingCurrentAccount).postNotificationName(
                            NotificationCenter.recordStarted,
                            recordingGuid,
                            true
                    );
                }
            }

            @Override
            public void onRecordingResumed() {
                if (!ownsAudioRecorderV2(owner)) return;
                requestRecordAudioFocus(true);
                owner.focusGeneration = recordAudioFocusGeneration.get();
                recordingAudioFocusGeneration = owner.focusGeneration;
                pendingVoiceRecordingGeneration = owner.focusGeneration;
                NotificationCenter.getInstance(owner.account).postNotificationName(NotificationCenter.recordResumed, owner.guid);
            }

            @Override
            public void onRecordingProgress(long durationMs, double amplitude) {
                if (!ownsAudioRecorderV2(owner)) return;
                recordTimeCount = durationMs;
                NotificationCenter.getInstance(recordingCurrentAccount).postNotificationName(
                        NotificationCenter.recordProgressChanged,
                        recordingGuid,
                        amplitude / 2d
                );
            }

            @Override
            public void onPreviewReady(long durationMs, File file, byte[] waveform) {
                if (!ownsAudioRecorderV2(owner)) return;
                final long previewFocusGeneration = owner.focusGeneration;
                recordingAudioFile = file;
                recordTimeCount = durationMs;
                updateAudioRecorderV2Document(file, durationMs, waveform);
                owner.draft = MediaDataController.DraftVoice.of(
                                MediaController.this,
                                file.getAbsolutePath(),
                                audioRecorderV2VoiceOnce,
                                0f,
                                1f
                );
                MediaDataController.getInstance(owner.account).pushDraftVoiceMessage(
                        owner.dialogId,
                        owner.topicId,
                        owner.draft
                );
                if (!ownsAudioRecorderV2(owner) || owner.focusGeneration != previewFocusGeneration) return;
                NotificationCenter.getInstance(owner.account).postNotificationName(NotificationCenter.recordPaused, owner.guid);
                if (!ownsAudioRecorderV2(owner) || owner.focusGeneration != previewFocusGeneration) return;
                NotificationCenter.getInstance(recordingCurrentAccount).postNotificationName(
                        NotificationCenter.audioDidSent,
                        recordingGuid,
                        recordingAudio,
                        file.getAbsolutePath()
                );
                if (ownsAudioRecorderV2(owner) && owner.focusGeneration == previewFocusGeneration) {
                    requestRecordAudioFocus(false);
                    setBluetoothScoOn(false);
                }
            }

            @Override
            public void onCompleted(AudioRecordingSession.Result result) {
                if (ownsAudioRecorderV2(owner)) completeAudioRecordingV2(owner, result);
            }

            @Override
            public void onRecordingTooShort(long durationMs) {
                if (!ownsAudioRecorderV2(owner)) return;
                finishAudioRecorderV2Lifecycle(owner, false);
                NotificationCenter.getInstance(owner.account).postNotificationName(
                        NotificationCenter.audioRecordTooShort,
                        owner.guid,
                        false,
                        (int) durationMs
                );
            }

            @Override
            public void onError(Exception error) {
                onAudioRecorderV2Error(owner, error);
            }
        };
    }

    private void updateAudioRecorderV2Document(
            File file,
            long durationMs,
            byte[] waveform
    ) {
        if (recordingAudio == null) return;
        recordingAudio.date = ConnectionsManager.getInstance(recordingCurrentAccount).getCurrentTime();
        recordingAudio.size = file.length();
        TLRPC.TL_documentAttributeAudio attributeAudio = new TLRPC.TL_documentAttributeAudio();
        attributeAudio.voice = true;
        attributeAudio.waveform = waveform;
        if (waveform != null) {
            attributeAudio.flags |= 4;
        }
        attributeAudio.duration = durationMs / 1000.0;
        recordingAudio.attributes.clear();
        recordingAudio.attributes.add(attributeAudio);
    }

    private void completeAudioRecordingV2(AudioRecorderV2Owner owner, AudioRecordingSession.Result result) {
        if (!ownsAudioRecorderV2(owner)) return;
        File file = result.getFile();
        recordingAudioFile = file;
        recordTimeCount = result.getDurationMs();
        updateAudioRecorderV2Document(file, result.getDurationMs(), result.getWaveform());
        if (sendAfterDone == 3) {
            audioRecorderV2PendingResult = result;
            if (audioRecorderV2Controller != null) {
                audioRecorderV2Controller.release();
                audioRecorderV2Controller = null;
            }
            releaseRecordingAudioFocus(owner.focusGeneration);
            setBluetoothScoOn(false);
            return;
        }
        final TLRPC.TL_document completedAudio = recordingAudio;
        final int completedSend = sendAfterDone;
        final long completedDialogId = recordDialogId, completedTopicId = recordTopicId;
        SendMessagesHelper.SendMessageParams params = null;
        if (completedSend == 1) {
            params = SendMessagesHelper.SendMessageParams.of(
                    recordingAudio,
                    null,
                    file.getAbsolutePath(),
                    recordDialogId,
                    recordReplyingMsg,
                    recordReplyingTopMsg,
                    null,
                    null,
                    null,
                    null,
                    sendAfterDoneNotify,
                    sendAfterDoneScheduleDate,
                    0,
                    sendAfterDoneOnce ? 0x7FFFFFFF : 0,
                    null,
                    null,
                    false
            );
            params.monoForumPeer = recordMonoForumPeerId;
            params.suggestionParams = recordMonoForumSuggestionParams;
            params.replyToStoryItem = recordReplyingStory;
            params.sendMessageChatArguments = recordSendMessageChatArguments;
            params.payStars = sendAfterDonePayStars;
        }
        finishAudioRecorderV2Lifecycle(owner, true);
        MediaDataController.getInstance(owner.account).pushDraftVoiceMessage(
                completedDialogId, completedTopicId, null);
        if (params != null) SendMessagesHelper.getInstance(owner.account).sendMessage(params);
        NotificationCenter.getInstance(owner.account).postNotificationName(
                NotificationCenter.audioDidSent,
                owner.guid,
                completedSend == 2 ? completedAudio : null,
                completedSend == 2 ? file.getAbsolutePath() : null
        );
    }

    private void onAudioRecorderV2Error(AudioRecorderV2Owner owner, Exception error) {
        FileLog.e("AudioRecorderV2 failed", error);
        if (!ownsAudioRecorderV2(owner)) return;
        finishAudioRecorderV2Lifecycle(owner, false);
        NotificationCenter.getInstance(owner.account).postNotificationName(
                NotificationCenter.recordStartError,
                owner.guid
        );
    }

    private void finishAudioRecorderV2Lifecycle(boolean keepOutput) {
        finishAudioRecorderV2Lifecycle(audioRecorderV2Owner, keepOutput);
    }

    private void finishAudioRecorderV2Lifecycle(AudioRecorderV2Owner owner, boolean keepOutput) {
        if (owner == null || audioRecorderV2Owner != owner) return;
        final AudioRecorderV2Controller controller = audioRecorderV2Controller;
        audioRecorderV2Controller = null;
        audioRecorderV2Owner = null;
        if (owner.logoutObserver != null) {
            NotificationCenter.getInstance(owner.account).removeObserver(owner.logoutObserver, NotificationCenter.appDidLogout);
            owner.logoutObserver = null;
        }
        if (recordAudioFocusGeneration.get() == owner.focusGeneration) {
            releaseRecordingAudioFocus(owner.focusGeneration);
            setBluetoothScoOn(false);
        }
        if (!keepOutput && recordingAudioFile != null) {
            AutoDeleteMediaTask.unlockFile(recordingAudioFile);
            recordingAudioFile.delete();
        }
        resetAudioRecorderV2State();
        manualRecording = false;
        raiseToEarRecord = false;
        ignoreOnPause = false;
        if (controller != null) controller.release();
    }

    private void resetAudioRecorderV2State() {
        recordingAudio = null;
        recordingPrevAudioFile = null;
        recordingAudioFile = null;
        audioRecorderV2Active = false;
        audioRecorderV2Stopping = false;
        audioRecorderV2VoiceOnce = false;
        audioRecorderV2PendingResult = null;
        sendAfterDone = 0;
    }

    private boolean releaseAudioRecorderV2ForNewRecording() {
        final AudioRecorderV2Owner owner = audioRecorderV2Owner;
        if (!ownsAudioRecorderV2Session(owner)) {
            cleanRecording(true);
            return true;
        }
        if (audioRecorderV2PendingResult != null) {
            finishAudioRecorderV2Lifecycle(owner, false);
            return true;
        }
        final AudioRecorderV2Controller controller = audioRecorderV2Controller;
        if (controller == null) {
            if (!ownsAudioRecorderV2(owner)) {
                cleanRecording(true);
                return true;
            }
            return false;
        }
        if (audioRecorderV2Stopping || !controller.isPreviewing()) {
            return false;
        }
        if (!isAudioRecorderV2PreviewDraftCurrent(owner, controller)) {
            cleanRecording(true);
            return true;
        }
        if (!controller.detachPreviewOutputForDraft()) {
            return false;
        }
        finishAudioRecorderV2Lifecycle(owner, true);
        return true;
    }

    private void setSendAfterDone(int send, boolean notify, int scheduleDate, boolean once, long payStars) {
        sendAfterDone = send;
        sendAfterDoneNotify = notify;
        sendAfterDoneScheduleDate = scheduleDate;
        sendAfterDoneOnce = once;
        sendAfterDonePayStars = payStars;
    }

    private void stopRecordingV2(
            int send,
            boolean notify,
            int scheduleDate,
            boolean once,
            long payStars
    ) {
        final AudioRecorderV2Owner owner = audioRecorderV2Owner;
        if (!ownsAudioRecorderV2(owner)) return;
        final long stoppedFocusGeneration = owner.focusGeneration;
        final int stoppedGuid = owner.guid;
        if (audioRecorderV2PendingResult != null) {
            if (send == 2) return;
            AudioRecordingSession.Result result = audioRecorderV2PendingResult;
            audioRecorderV2PendingResult = null;
            if (send == 0) {
                finishAudioRecorderV2Lifecycle(false);
            } else {
                setSendAfterDone(send, notify, scheduleDate, once, payStars);
                completeAudioRecordingV2(owner, result);
            }
            return;
        }
        if (audioRecorderV2Stopping) {
            if (sendAfterDone == 3 && send != 2) {
                setSendAfterDone(send, notify, scheduleDate, once, payStars);
                if (send == 0) {
                    if (audioRecorderV2Controller != null) {
                        audioRecorderV2Controller.cancel();
                    }
                    finishAudioRecorderV2Lifecycle(false);
                }
            }
            return;
        }
        if (send == 2 && audioRecorderV2Controller != null && audioRecorderV2Controller.isPreviewing()) {
            manualRecording = false;
            raiseToEarRecord = false;
            ignoreOnPause = false;
            return;
        }
        if (audioRecorderV2StartRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(audioRecorderV2StartRunnable);
            audioRecorderV2StartRunnable = null;
        }
        audioRecorderV2Stopping = true;
        setSendAfterDone(send, notify, scheduleDate, once, payStars);

        if (audioRecorderV2Controller == null) {
            finishAudioRecorderV2Lifecycle(false);
        } else if (send == 0) {
            audioRecorderV2Controller.cancel();
            finishAudioRecorderV2Lifecycle(false);
        } else {
            audioRecorderV2Controller.finishWhenReady();
        }
        if (!app.nimarkogram.messenger.AppConfig.disableVibration) try {
            feedbackView.performHapticFeedback(
                    HapticFeedbackConstants.KEYBOARD_TAP,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
            );
        } catch (Exception ignore) {
        }
        AndroidUtilities.runOnUIThread(() -> {
            if (UserConfig.getInstance(owner.account).getClientUserId() != owner.userId
                    || recordAudioFocusGeneration.get() != stoppedFocusGeneration
                    || owner.guid != stoppedGuid) return;
        NotificationCenter.getInstance(owner.account).postNotificationName(
                NotificationCenter.recordStopped,
                stoppedGuid,
                send == 2 ? 1 : 0
        );
        });
    }

    private boolean showAudioRecorderV2Draft(
            int currentAccount,
            long dialogId,
            long topicId,
            java.util.function.BooleanSupplier presentationCurrent,
            MediaDataController.DraftVoice draft,
            MessageObject replyToMsg,
            MessageObject replyToTopMsg,
            TL_stories.StoryItem replyStory,
            int guid,
            SendMessageChatArguments sendMessageChatArguments,
            long monoForumPeerId,
            MessageSuggestionParams suggestionParams
    ) {
        final AudioRecorderV2Owner owner = audioRecorderV2Owner;
        if (!ownsAudioRecorderV2(owner)
                || owner.account != currentAccount || owner.dialogId != dialogId || owner.topicId != topicId
                || owner.draft != draft
                || !isAudioRecorderV2PreviewDraftCurrent(owner, audioRecorderV2Controller)) {
            return false;
        }
        recordingGuid = guid;
        owner.guid = guid;
        final long previewFocusGeneration = owner.focusGeneration;
        recordReplyingMsg = replyToMsg;
        recordReplyingTopMsg = replyToTopMsg;
        recordReplyingStory = replyStory;
        recordSendMessageChatArguments = sendMessageChatArguments;
        recordMonoForumPeerId = monoForumPeerId;
        recordMonoForumSuggestionParams = suggestionParams;
        NotificationCenter.getInstance(owner.account).postNotificationName(NotificationCenter.recordPaused, guid);
        if (ownsAudioRecorderV2(owner) && owner.guid == guid
                && owner.focusGeneration == previewFocusGeneration && presentationCurrent.getAsBoolean()) {
            NotificationCenter.getInstance(owner.account).postNotificationName(NotificationCenter.audioDidSent, guid, recordingAudio, recordingAudioFile.getAbsolutePath(), true, draft.left, draft.right);
        }
        return true;
    }

    public void prepareResumedRecording(int currentAccount, MediaDataController.DraftVoice draft, long dialogId, MessageObject replyToMsg, MessageObject replyToTopMsg, TL_stories.StoryItem replyStory, int guid, SendMessageChatArguments sendMessageChatArguments, long monoForumPeerId, MessageSuggestionParams suggestionParams, java.util.function.BooleanSupplier ownerCurrent, java.util.function.BooleanSupplier presentationCurrent) {
        final long userId = UserConfig.getInstance(currentAccount).getClientUserId();
        if (userId == 0 || draft == null || draft.path == null) {
            return;
        }
        final boolean needsFileWaveform = draft.recordSamples == null;
        final RecordingPreview preview = new RecordingPreview(draft.id, draft.path, draft.recordTimeCount,
                draft.samplesCount, draft.writedFrame,
                draft.recordSamples == null ? new short[1024] : draft.recordSamples, draft.once);
        final short[] restoredSamples = preview.samples.clone();
        final float left = draft.left, right = draft.right;
        final long topicId = replyToTopMsg == null ? 0 : MessageObject.getTopicId(currentAccount, replyToTopMsg.messageOwner, false);
        if (draft.isDiscarded() || MediaDataController.getInstance(currentAccount).getDraftVoice(dialogId, topicId) != draft) return;
        final java.util.function.BooleanSupplier draftCurrent = () -> !draft.isDiscarded()
                && (ownerCurrent == null || ownerCurrent.getAsBoolean());
        final Runnable reportRestoreError = () -> {
            if (draftCurrent.getAsBoolean() && (presentationCurrent == null || presentationCurrent.getAsBoolean())) {
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.recordStartError, guid, true);
            }
        };
        final File restoredFile = new File(preview.path);
        if (!restoredFile.exists()) {
            final long initialFocusGeneration = recordAudioFocusGeneration.get();
            final long initialTrimGeneration = recordingTrimGeneration;
            AndroidUtilities.runOnUIThread(() -> {
                if (recordAudioFocusGeneration.get() == initialFocusGeneration
                        && recordingTrimGeneration == initialTrimGeneration
                        && draftCurrent.getAsBoolean()
                        && (presentationCurrent == null || presentationCurrent.getAsBoolean())
                        && UserConfig.getInstance(currentAccount).getClientUserId() == userId) {
                    reportRestoreError.run();
                }
            });
            return;
        }
        final java.util.function.BooleanSupplier restoreCurrent = () -> draftCurrent.getAsBoolean()
                && UserConfig.getInstance(currentAccount).getClientUserId() == userId
                && MediaDataController.getInstance(currentAccount).getDraftVoice(dialogId, topicId) == draft;
        if (!restoreCurrent.getAsBoolean()) return;
        if (audioRecorderV2Active) {
            if (presentationCurrent != null && !presentationCurrent.getAsBoolean()) return;
            if (showAudioRecorderV2Draft(currentAccount, dialogId, topicId, restoreCurrent, draft, replyToMsg, replyToTopMsg, replyStory, guid, sendMessageChatArguments, monoForumPeerId, suggestionParams)
                    || !releaseAudioRecorderV2ForNewRecording()) {
                return;
            }
        }
        cancelRecordingTrim(null);
        final long trimGeneration = recordingTrimGeneration;
        manualRecording = false;
        requestRecordAudioFocus(true);
        final long focusGeneration = recordAudioFocusGeneration.get();
        pendingVoiceRecordingGeneration = focusGeneration;
        final RecordingPreviewOwner previewOwner = newRecordingPreviewOwner(currentAccount, dialogId, topicId);
        final java.util.function.BooleanSupplier requestCurrent = () ->
                recordAudioFocusGeneration.get() == focusGeneration
                && recordingTrimGeneration == trimGeneration
                && UserConfig.getInstance(currentAccount).getClientUserId() == userId
                && isRecordingPreviewOwnerCurrent(previewOwner);
        recordQueue.cancelRunnable(recordStartRunnable);
        recordQueue.postRunnable(() -> {
            if (!requestCurrent.getAsBoolean()) {
                return;
            }
            if (!draftCurrent.getAsBoolean()) {
                AndroidUtilities.runOnUIThread(() -> releaseRecordingAudioFocus(focusGeneration));
                return;
            }
            if (!restoredFile.exists()) {
                if (recordingAudio == null && recordingAudioFile == null && audioRecorder == null) {
                    finishIdleRecording(focusGeneration, trimGeneration,
                            () -> requestCurrent.getAsBoolean() && recordingAudio == null && recordingAudioFile == null,
                            () -> UserConfig.getInstance(currentAccount).getClientUserId() == userId,
                            reportRestoreError);
                } else {
                    AndroidUtilities.runOnUIThread(() -> {
                        if (requestCurrent.getAsBoolean()) {
                            reportRestoreError.run();
                        }
                    });
                }
                return;
            }
            manualRecording = false;
            setBluetoothScoOn(true);
            sendAfterDone = 0;
            recordingAudio = new TLRPC.TL_document();
            recordingGuid = guid;
            recordingAudioFocusGeneration = focusGeneration;
            recordingPreviewOwner = previewOwner;
            recordingAudio.dc_id = Integer.MIN_VALUE;
            recordingAudio.id = preview.id;
            recordingAudio.user_id = userId;
            recordingAudio.mime_type = "audio/ogg";
            recordingAudio.file_reference = new byte[0];
            SharedConfig.saveConfig();

            recordingAudioFile = restoredFile;
            FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE).mkdirs();
            AutoDeleteMediaTask.lockFile(recordingAudioFile);
                audioRecorderPaused = true;
            recordTimeCount = preview.duration;
            writtenFrame = preview.frames;
            samplesCount = preview.samplesCount;
            recordSamples = restoredSamples;
                recordDialogId = dialogId;
                recordMonoForumPeerId = monoForumPeerId;
                recordMonoForumSuggestionParams = suggestionParams;
            recordTopicId = topicId;
                recordingCurrentAccount = currentAccount;
                recordReplyingMsg = replyToMsg;
                recordReplyingTopMsg = replyToTopMsg;
                recordReplyingStory = replyStory;
                recordSendMessageChatArguments = sendMessageChatArguments;

            final TLRPC.TL_document audioToSend = recordingAudio;
            final File recordingAudioFileToSend = recordingAudioFile;
            final java.util.function.BooleanSupplier deliveryCurrent = () -> requestCurrent.getAsBoolean()
                    && recordingAudio == audioToSend && recordingAudioFile == recordingAudioFileToSend
                    && recordingPreviewOwner == previewOwner && audioRecorderPaused
                    && recordingAudioFocusGeneration == focusGeneration;
            final Runnable failRestore = () -> recordQueue.postRunnable(() -> finishIdleRecording(
                    focusGeneration, trimGeneration, deliveryCurrent,
                    () -> UserConfig.getInstance(currentAccount).getClientUserId() == userId,
                    reportRestoreError));
            final Runnable cancelRestore = () -> recordQueue.postRunnable(() -> finishIdleRecording(
                    focusGeneration, trimGeneration, deliveryCurrent,
                    () -> UserConfig.getInstance(currentAccount).getClientUserId() == userId, null));
            final byte[] fileWaveform = needsFileWaveform && recordingAudioFileToSend.exists()
                    ? getWaveform(recordingAudioFileToSend.getAbsolutePath())
                    : null;
            AndroidUtilities.runOnUIThread(() -> {
                if (!deliveryCurrent.getAsBoolean()) {
                    return;
                }
                if (!draftCurrent.getAsBoolean() || presentationCurrent != null && !presentationCurrent.getAsBoolean()
                        || MediaDataController.getInstance(currentAccount).getDraftVoice(dialogId, topicId) != draft) {
                    cancelRestore.run();
                    return;
                }
                if (!recordingAudioFileToSend.exists()) {
                    failRestore.run();
                    return;
                }
                audioToSend.date = ConnectionsManager.getInstance(currentAccount).getCurrentTime();
                audioToSend.size = (int) recordingAudioFileToSend.length();
                TLRPC.TL_documentAttributeAudio attributeAudio = new TLRPC.TL_documentAttributeAudio();
                attributeAudio.voice = true;
                attributeAudio.waveform = fileWaveform != null
                        ? fileWaveform
                        : getWaveform2(preview.samples, preview.samples.length);
                if (attributeAudio.waveform != null) {
                    attributeAudio.flags |= 4;
                }
                attributeAudio.duration = preview.duration / 1000.0;
                audioToSend.attributes.clear();
                audioToSend.attributes.add(attributeAudio);
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.recordPaused, guid);
                if (deliveryCurrent.getAsBoolean()) {
                    if (!draftCurrent.getAsBoolean() || presentationCurrent != null && !presentationCurrent.getAsBoolean()
                            || MediaDataController.getInstance(currentAccount).getDraftVoice(dialogId, topicId) != draft) {
                        cancelRestore.run();
                    } else if (!recordingAudioFileToSend.exists()) {
                        failRestore.run();
                    } else {
                        NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.audioDidSent, guid, audioToSend, recordingAudioFileToSend.getAbsolutePath(), true, left, right);
                    }
                }
            });
        });
    }

    public void prepareResumedRecording(int currentAccount, MediaDataController.DraftVoice draft, long dialogId, MessageObject replyToMsg, MessageObject replyToTopMsg, TL_stories.StoryItem replyStory, int guid, SendMessageChatArguments sendMessageChatArguments, long monoForumPeerId, MessageSuggestionParams suggestionParams) {
        prepareResumedRecording(currentAccount, draft, dialogId, replyToMsg, replyToTopMsg, replyStory, guid, sendMessageChatArguments, monoForumPeerId, suggestionParams, null, null);
    }
    public boolean isRecordingPaused() {
        if (audioRecorderV2Active) {
            return audioRecorderV2Controller != null && audioRecorderV2Controller.isPreviewing();
        }
        return audioRecorderPaused;
    }

    private void writeRecordingFrame(int bytes) throws IOException {
        if (writeFrame(fileBuffer, bytes) == 0) {
            throw new IOException("Unable to encode voice recording");
        }
        recordTimeCount += bytes * 1000L / (sampleRate * 2L);
        writtenFrame++;
        fileBuffer.rewind();
    }

    private void encodeRecordingBuffer(TLRPC.TL_document audio, File file, ByteBuffer buffer, boolean flush) {
        final int originalLimit = buffer.limit();
        try {
            synchronized (recordingEncoderLock) {
                if (audio == null || audio != recordingAudio || file != recordingAudioFile || recordingEncodingFailed == audio) {
                    return;
                }
                try {
                    if (recordingEncoderFile != file) {
                        throw new IOException("Voice recording encoder is closed");
                    }
                    while (buffer.hasRemaining()) {
                        buffer.limit(buffer.position() + Math.min(buffer.remaining(), fileBuffer.remaining()));
                        fileBuffer.put(buffer);
                        if (!fileBuffer.hasRemaining() || flush) {
                            writeRecordingFrame(fileBuffer.position());
                        }
                        buffer.limit(originalLimit);
                    }
                } catch (Exception e) {
                    recordingEncodingFailed = audio;
                    fileBuffer.rewind();
                    FileLog.e(e);
                }
            }
        } finally {
            buffer.limit(originalLimit);
            recordQueue.postRunnable(() -> recordBuffers.add(buffer));
        }
    }

    private void finishRecordingEncoder(TLRPC.TL_document audio, File file) {
        synchronized (recordingEncoderLock) {
            if (recordingEncoderFile == file && audio != null && audio == recordingAudio
                    && file == recordingAudioFile && recordingEncodingFailed != audio && fileBuffer.position() > 0) {
                try {
                    writeRecordingFrame(fileBuffer.position());
                } catch (Exception e) {
                    recordingEncodingFailed = audio;
                    fileBuffer.rewind();
                    FileLog.e(e);
                }
            }
            stopRecordingEncoder(file);
        }
    }

    private int startRecordingEncoder(File file) {
        synchronized (recordingEncoderLock) {
            int result = startRecord(file.getPath(), sampleRate);
            recordingEncoderFile = result == 0 ? null : file;
            return result;
        }
    }

    private void stopRecordingEncoder(File file) {
        synchronized (recordingEncoderLock) {
            if (recordingEncoderFile == file) {
                stopRecord();
                recordingEncoderFile = null;
            }
        }
    }
    private File joinRecord() {
        return joinRecord(recordingPrevAudioFile, recordingAudioFile, recordingAudio);
    }

    private File joinRecord(File prevFile, File currentFile, TLRPC.TL_document document) {
        if (document != null && recordingEncodingFailed == document) {
            return null;
        }
        if (prevFile != null && currentFile != null) {
            final File newFile;
            try {
                newFile = File.createTempFile("voice_join_", ".ogg", FileLoader.getDirectory(FileLoader.MEDIA_DIR_AUDIO));
            } catch (IOException | RuntimeException e) {
                return null;
            }
            boolean joined = false;
            try {
                joined = joinOpusFiles(prevFile.getAbsolutePath(), currentFile.getAbsolutePath(), newFile.getAbsolutePath());
            } catch (RuntimeException e) {
                return null;
            } finally {
                if (!joined) {
                    try {
                        newFile.delete();
                    } catch (RuntimeException ignored) {
                    }
                }
            }
            if (!joined) {
                return null;
            }
            currentFile.delete();
            if (document == recordingAudio && currentFile == recordingAudioFile) {
                recordingAudioFile = newFile;
            }
            currentFile = newFile;
            if (prevFile != null) {
                prevFile.delete();
                if (document == recordingAudio && prevFile == recordingPrevAudioFile) {
                    recordingPrevAudioFile = null;
                }
            }
        }
        return currentFile;
    }

    private void onRecordingJoinFailed(int account, int guid, long focusGeneration, long userId) {
        AndroidUtilities.runOnUIThread(() -> {
            if (userId == 0 || UserConfig.getInstance(account).getClientUserId() != userId) {
                return;
            }
            if (!recordAudioFocusGeneration.compareAndSet(focusGeneration, focusGeneration + 1)) {
                return;
            }
            requestRecordAudioFocus(false);
            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.recordStartError, guid, true);
        });
    }

    private static final class RecordingPreviewOwner {
        final int account;
        final long dialogId, topicId;

        RecordingPreviewOwner(int account, long dialogId, long topicId) {
            this.account = account;
            this.dialogId = dialogId;
            this.topicId = topicId;
        }
    }

    private RecordingPreviewOwner newRecordingPreviewOwner(int account, long dialogId, long topicId) {
        synchronized (recordingPreviewOwners) {
            recordingPreviewOwners.replaceAll((owner, superseded) -> superseded
                    || owner.account == account && owner.dialogId == dialogId && owner.topicId == topicId);
            RecordingPreviewOwner owner = new RecordingPreviewOwner(account, dialogId, topicId);
            recordingPreviewOwners.put(owner, false);
            return owner;
        }
    }

    private boolean takeRecordingPreviewOwner(RecordingPreviewOwner owner) {
        synchronized (recordingPreviewOwners) {
            return Boolean.FALSE.equals(recordingPreviewOwners.remove(owner));
        }
    }

    private boolean isRecordingPreviewOwnerCurrent(RecordingPreviewOwner owner) {
        synchronized (recordingPreviewOwners) {
            return Boolean.FALSE.equals(recordingPreviewOwners.get(owner));
        }
    }

    private static final class RecordingPreview {
        final long id, duration, samplesCount;
        final int frames;
        final String path;
        final boolean once;
        private final short[] samples;

        RecordingPreview(long id, String path, long duration, long samplesCount, int frames, short[] samples, boolean once) {
            this.id = id;
            this.path = path;
            this.duration = duration;
            this.samplesCount = samplesCount;
            this.frames = frames;
            this.samples = samples.clone();
            this.once = once;
        }

        MediaDataController.DraftVoice toDraft() {
            MediaDataController.DraftVoice draft = new MediaDataController.DraftVoice();
            draft.id = id;
            draft.path = path;
            draft.recordTimeCount = duration;
            draft.samplesCount = samplesCount;
            draft.writedFrame = frames;
            draft.recordSamples = samples.clone();
            draft.once = once;
            draft.left = 0f;
            draft.right = 1f;
            return draft;
        }
    }

    private boolean finishRecordingOnEncodingQueue(Runnable completion) {
        final CountDownLatch finished = new CountDownLatch(1);
        if (!fileEncodingQueue.postRunnable(() -> {
            try {
                completion.run();
            } finally {
                finished.countDown();
            }
        })) {
            return false;
        }
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    finished.await();
                    return true;
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    public void releaseRecordingAudioFocus(long generation) {
        synchronized (recordAudioFocusGeneration) {
            if (recordAudioFocusGeneration.get() == generation) {
                requestRecordAudioFocus(false);
                if (pendingVoiceRecordingGeneration == generation) {
                    pendingVoiceRecordingGeneration = -1;
                }
            }
        }
    }

    public void trimCurrentRecording(long startMs, long endMs, Runnable done) {
        trimCurrentRecording(startMs, endMs, done, null);
    }
    public boolean isTrimmingCurrentRecording() {
        return pendingRecordingTrim != null;
    }

    public void cancelRecordingTrim(TLRPC.Document expectedAudio) {
        synchronized (recordingTrimLock) {
            if (expectedAudio == null || recordingAudio == expectedAudio) {
                pendingRecordingTrim = null;
                recordingTrimGeneration++;
            }
        }
    }

    private boolean ownsRecordingTrim(Object token, TLRPC.TL_document audio, File file, long generation) {
        return pendingRecordingTrim == token && recordingAudio == audio && recordingAudioFile == file
                && audioRecorderPaused && recordingAudioFocusGeneration == generation
                && recordAudioFocusGeneration.get() == generation;
    }

    public void trimCurrentRecording(long startMs, long endMs, Runnable done, Runnable failed) {
        if (audioRecorderV2Active) {
            final AudioRecorderV2Owner owner = audioRecorderV2Owner;
            if (!ownsAudioRecorderV2(owner)) return;
            final long focusGeneration = owner.focusGeneration;
            final long trimGeneration = recordingTrimGeneration;
            final boolean accepted = audioRecorderV2Controller != null
                    && audioRecorderV2Controller.setTrimRange(startMs, endMs);
            final Runnable callback = accepted ? done : failed;
            if (callback != null) {
                AndroidUtilities.runOnUIThread(() -> {
                    if (ownsAudioRecorderV2(owner) && owner.focusGeneration == focusGeneration
                            && recordingTrimGeneration == trimGeneration) callback.run();
                });
            }
            return;
        }
        final TLRPC.TL_document audio = recordingAudio;
        final File file = recordingAudioFile;
        final long generation = recordingAudioFocusGeneration;
        final Object token = new Object();
        synchronized (recordingTrimLock) {
            if (pendingRecordingTrim != null) return;
            if (audio == null || file == null || !audioRecorderPaused || startMs < 0 || endMs <= startMs) {
                if (failed != null) AndroidUtilities.runOnUIThread(failed);
                return;
            }
            pendingRecordingTrim = token;
        }
        recordQueue.postRunnable(() -> {
            File cropped = null;
            try {
                synchronized (recordingTrimLock) {
                    if (!ownsRecordingTrim(token, audio, file, generation)) {
                        if (pendingRecordingTrim == token) pendingRecordingTrim = null;
                        return;
                    }
                }
                cropped = File.createTempFile("voice-trim-", ".ogg", file.getAbsoluteFile().getParentFile());
                if (!cropOpusFile(file.getAbsolutePath(), cropped.getAbsolutePath(), startMs, endMs)
                        || cropped.length() == 0) throw new IOException("Unable to trim voice recording");
                final byte[] waveform = getWaveform(cropped.getAbsolutePath());
                final File result = cropped;
                final int account = recordingCurrentAccount;
                final long dialogId = recordDialogId;
                final long topicId = recordTopicId;
                AndroidUtilities.runOnUIThread(() -> {
                    synchronized (recordingTrimLock) {
                        try {
                            if (!ownsRecordingTrim(token, audio, file, generation)) return;
                            Os.rename(result.getAbsolutePath(), file.getAbsolutePath());
                            recordTimeCount = endMs - startMs;
                            audio.size = file.length();
                            for (TLRPC.DocumentAttribute attribute : audio.attributes) {
                                if (attribute instanceof TLRPC.TL_documentAttributeAudio) {
                                    attribute.duration = recordTimeCount / 1000.0;
                                    attribute.waveform = waveform;
                                    if (waveform != null) attribute.flags |= 4;
                                    else attribute.flags &= ~4;
                                }
                            }
                            try {
                                MediaDataController controller = MediaDataController.getInstance(account);
                                MediaDataController.DraftVoice draft = controller.getDraftVoice(dialogId, topicId);
                                if (draft != null && draft.id == audio.id && file.getAbsolutePath().equals(draft.path)) {
                                    draft.recordTimeCount = recordTimeCount;
                                    draft.left = 0f;
                                    draft.right = 1f;
                                    controller.pushDraftVoiceMessage(dialogId, topicId, draft);
                                }
                            } catch (Exception e) {
                                FileLog.e(e);
                            }
                            pendingRecordingTrim = null;
                            if (done != null) done.run();
                        } catch (Exception e) {
                            FileLog.e(e);
                            if (pendingRecordingTrim == token) {
                                pendingRecordingTrim = null;
                                if (failed != null) failed.run();
                            }
                        } finally {
                            result.delete();
                            if (pendingRecordingTrim == token) pendingRecordingTrim = null;
                        }
                    }
                });
                cropped = null;
            } catch (Exception e) {
                FileLog.e(e);
                AndroidUtilities.runOnUIThread(() -> {
                    synchronized (recordingTrimLock) {
                        if (pendingRecordingTrim != token) return;
                        boolean current = ownsRecordingTrim(token, audio, file, generation);
                        pendingRecordingTrim = null;
                        if (current && failed != null) failed.run();
                    }
                });
            } finally {
                if (cropped != null) cropped.delete();
            }
        });
    }

    public void toggleRecordingPause(boolean voiceOnce, TLRPC.TL_document expectedAudio) {
        toggleRecordingPause(voiceOnce, expectedAudio, true);
    }

    public void toggleRecordingPause(boolean voiceOnce) {
        toggleRecordingPause(voiceOnce, null, false);
    }

    private void toggleRecordingPause(boolean voiceOnce, TLRPC.TL_document expectedAudio, boolean scoped) {
        if (audioRecorderV2Active) {
            if (!ownsAudioRecorderV2(audioRecorderV2Owner)) return;
            if (scoped && recordingAudio != expectedAudio) return;
            audioRecorderV2VoiceOnce = voiceOnce;
            if (audioRecorderV2Controller != null) {
                if (audioRecorderV2Controller.isPreviewing()) {
                    requestRecordAudioFocus(true);
                    audioRecorderV2Owner.focusGeneration = recordAudioFocusGeneration.get();
                    recordingAudioFocusGeneration = audioRecorderV2Owner.focusGeneration;
                    pendingVoiceRecordingGeneration = audioRecorderV2Owner.focusGeneration;
                    setBluetoothScoOn(true);
                }
                audioRecorderV2Controller.togglePause();
            }
            return;
        }
        final long trimGeneration = recordingTrimGeneration;
        recordQueue.postRunnable(() -> {
            if (pendingRecordingTrim != null || scoped && recordingAudio != expectedAudio
                    || scoped && recordingTrimGeneration != trimGeneration
                    || recordingAudio == null || recordingAudioFile == null) {
                return;
            }
            audioRecorderPaused = !audioRecorderPaused;
            final boolean isPaused = audioRecorderPaused;
            if (isPaused) {
                if (audioRecorder == null) {
                    return;
                }
                sendAfterDone = 4;
                audioRecorder.stop();
                audioRecorder.release();
                audioRecorder = null;
                final TLRPC.TL_document audioToSend = recordingAudio;
                final File previousFile = recordingPrevAudioFile;
                final File currentFile = recordingAudioFile;
                final int account = recordingCurrentAccount;
                final int guid = recordingGuid;
                final long focusGeneration = recordingAudioFocusGeneration;
                final long userId = audioToSend == null ? 0 : audioToSend.user_id;
                final long dialogId = recordDialogId;
                final long topicId = recordTopicId;
                final RecordingPreviewOwner previewOwner = recordingPreviewOwner;
                recordQueue.postRunnable(() -> {
                    if (recordingAudio != audioToSend || recordingAudioFocusGeneration != focusGeneration) {
                        return;
                    }
                    final File[] joinedFile = new File[1];
                    final boolean finished = finishRecordingOnEncodingQueue(() -> {
                        finishRecordingEncoder(audioToSend, currentFile);
                        joinedFile[0] = joinRecord(previousFile, currentFile, audioToSend);
                    });
                    final File recordingAudioFileToSend = finished ? joinedFile[0] : null;
                    if (audioToSend == null || recordingAudioFileToSend == null) {
                        cleanRecording(false, focusGeneration);
                        audioRecorderPaused = false;
                        onRecordingJoinFailed(account, guid, focusGeneration, audioToSend == null ? 0 : audioToSend.user_id);
                        return;
                    }
                    final RecordingPreview preview = new RecordingPreview(audioToSend.id,
                            recordingAudioFileToSend.getAbsolutePath(), recordTimeCount, samplesCount, writtenFrame, recordSamples, voiceOnce);
                    AndroidUtilities.runOnUIThread(() -> {
                        if (userId == 0 || UserConfig.getInstance(account).getClientUserId() != userId) {
                            return;
                        }
                        boolean fileExist = recordingAudioFileToSend.exists();
                        if (!fileExist && BuildVars.DEBUG_VERSION) {
                            FileLog.e(new RuntimeException("file not found :( recordTimeCount " + preview.duration + " writedFrames" + preview.frames));
                        }
                        if (fileExist) {
                            MediaDataController controller = MediaDataController.getInstance(account);
                            MediaDataController.DraftVoice draft = controller.getDraftVoice(dialogId, topicId);
                            final boolean sameDraft = draft != null && draft.id == audioToSend.id
                                    && (TextUtils.equals(draft.path, currentFile.getAbsolutePath())
                                    || previousFile != null && TextUtils.equals(draft.path, previousFile.getAbsolutePath())
                                    || TextUtils.equals(draft.path, recordingAudioFileToSend.getAbsolutePath()));
                            if (isRecordingPreviewOwnerCurrent(previewOwner) && (draft == null || sameDraft)) {
                                controller.pushDraftVoiceMessage(dialogId, topicId, preview.toDraft());
                            }
                        }
                        if (recordingAudio != audioToSend || recordingAudioFile != recordingAudioFileToSend
                                || !audioRecorderPaused || recordAudioFocusGeneration.get() != focusGeneration) {
                            return;
                        }
                        audioToSend.date = ConnectionsManager.getInstance(account).getCurrentTime();
                        audioToSend.size = (int) recordingAudioFileToSend.length();
                        TLRPC.TL_documentAttributeAudio attributeAudio = new TLRPC.TL_documentAttributeAudio();
                        attributeAudio.voice = true;
                        attributeAudio.waveform = getWaveform(recordingAudioFileToSend.getAbsolutePath());
                        if (attributeAudio.waveform != null) {
                            attributeAudio.flags |= 4;
                        }
                        attributeAudio.duration = preview.duration / 1000.0;
                        audioToSend.attributes.clear();
                        audioToSend.attributes.add(attributeAudio);
                        NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.recordPaused, guid);
                        if (recordingAudio == audioToSend && recordAudioFocusGeneration.get() == focusGeneration
                                && UserConfig.getInstance(account).getClientUserId() == userId) {
                            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.audioDidSent, guid, audioToSend, recordingAudioFileToSend.getAbsolutePath());
                            releaseRecordingAudioFocus(focusGeneration);
                        }
                    });
                });
            } else {
                final TLRPC.TL_document pausedAudio = recordingAudio;
                final File pausedFile = recordingAudioFile;
                recordQueue.cancelRunnable(recordRunnable);
                recordQueue.postRunnable(() -> {
                    if (recordingAudio != pausedAudio || recordingAudioFile != pausedFile || audioRecorderPaused) return;
                    if (scoped && recordingTrimGeneration != trimGeneration) {
                        audioRecorderPaused = true;
                        return;
                    }
                    recordingPrevAudioFile = recordingAudioFile;
                    recordingAudioFile = new File(FileLoader.getDirectory(FileLoader.MEDIA_DIR_AUDIO), System.currentTimeMillis() + "_" + FileLoader.getAttachFileName(recordingAudio)) {
                        @Override
                        public boolean delete() {
                            if (BuildVars.LOGS_ENABLED) {
                                FileLog.e("delete voice file (from resume)");
                            }
                            return super.delete();
                        }
                    };
                    if (startRecordingEncoder(recordingAudioFile) == 0) {
                        File failedFile = recordingAudioFile;
                        final long failureFocusGeneration = recordAudioFocusGeneration.get();
                        final int failureAccount = recordingCurrentAccount;
                        final int failureGuid = recordingGuid;
                        AutoDeleteMediaTask.unlockFile(failedFile);
                        if (failedFile != null) {
                            failedFile.delete();
                        }
                        recordingAudioFile = recordingPrevAudioFile;
                        recordingPrevAudioFile = null;
                        audioRecorderPaused = true;
                        AndroidUtilities.runOnUIThread(() -> {
                            if (recordAudioFocusGeneration.get() != failureFocusGeneration) {
                                return;
                            }
                            recordStartRunnable = null;
                            NotificationCenter.getInstance(failureAccount).postNotificationName(NotificationCenter.recordStartError, failureGuid);
                        });
                        if (BuildVars.LOGS_ENABLED) {
                            FileLog.d("cant resume audio encoder");
                        }
                        return;
                    }

                    final TLRPC.TL_document resumedAudio = recordingAudio;
                    final File resumedFile = recordingAudioFile;
                    final int resumedAccount = recordingCurrentAccount, resumedGuid = recordingGuid;
                    AndroidUtilities.runOnUIThread(() -> {
                        if (recordingAudio != resumedAudio || recordingAudioFile != resumedFile || audioRecorderPaused
                                || recordingCurrentAccount != resumedAccount || recordingGuid != resumedGuid
                                || recordAudioFocusGeneration.get() != recordingAudioFocusGeneration) {
                            return;
                        }
                        requestRecordAudioFocus(true);
                        recordingAudioFocusGeneration = recordAudioFocusGeneration.get();
                    });
                    try {
                        recordStartTime = System.currentTimeMillis();
                        writtenFrame = 0;
                        samplesCount = 0;
                        fileBuffer.rewind();
                        audioRecorder = createVoiceAudioRecorder();
                        recordQueue.postRunnable(recordRunnable);
                        AndroidUtilities.runOnUIThread(() -> {
                            if (recordingAudio == resumedAudio && recordingAudioFile == resumedFile && !audioRecorderPaused
                                    && recordingCurrentAccount == resumedAccount && recordingGuid == resumedGuid) {
                                NotificationCenter.getInstance(resumedAccount).postNotificationName(NotificationCenter.recordResumed, resumedGuid);
                            }
                        });
                    } catch (RuntimeException error) {
                        FileLog.e(error);
                        stopRecordingEncoder(recordingAudioFile);
                        recordingAudioFile.delete();
                        recordingAudioFile = recordingPrevAudioFile;
                        recordingPrevAudioFile = null;
                        audioRecorderPaused = true;
                        final File restoredFile = recordingAudioFile;
                        AndroidUtilities.runOnUIThread(() -> {
                            if (recordingAudio == resumedAudio && recordingAudioFile == restoredFile && audioRecorderPaused
                                    && recordingCurrentAccount == resumedAccount && recordingGuid == resumedGuid) {
                            requestRecordAudioFocus(false);
                                NotificationCenter.getInstance(resumedAccount).postNotificationName(NotificationCenter.recordStartError, resumedGuid);
                            }
                        });
                    }
                });
            }
        });
    }

    public void startRecording(int currentAccount, long dialogId, MessageObject replyToMsg, MessageObject replyToTopMsg, TL_stories.StoryItem replyStory, int guid, boolean manual, SendMessageChatArguments sendMessageChatArguments, long monoForumPeerId, MessageSuggestionParams suggestionParams) {
        if (UserConfig.getInstance(currentAccount).getClientUserId() == 0) return;
        if (audioRecorderV2Active && !releaseAudioRecorderV2ForNewRecording()) {
            final long userId = UserConfig.getInstance(currentAccount).getClientUserId();
            AndroidUtilities.runOnUIThread(() -> {
                if (UserConfig.getInstance(currentAccount).getClientUserId() == userId) {
                    NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.recordStartError, guid);
                }
            });
            return;
        }
        cancelRecordingTrim(null);
        boolean paused = false;
        if (playingMessageObject != null && isPlayingMessage(playingMessageObject) && !isMessagePaused()) {
            paused = true;
        }
        manualRecording = manual;
        requestRecordAudioFocus(true);

        final long focusGeneration = recordAudioFocusGeneration.get();
        pendingVoiceRecordingGeneration = focusGeneration;
        final RecordingPreviewOwner previewOwner = newRecordingPreviewOwner(currentAccount, dialogId,
                replyToTopMsg == null ? 0 : MessageObject.getTopicId(currentAccount, replyToTopMsg.messageOwner, false));
        if (!app.nimarkogram.messenger.AppConfig.disableVibration) {
            try {
                feedbackView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
            } catch (Exception ignore) {}
        }

        if (isAudioRecorderV2Enabled(currentAccount)) {
            recordingPreviewOwner = previewOwner;
            startRecordingV2(
                    currentAccount,
                    dialogId,
                    replyToMsg,
                    replyToTopMsg,
                    replyStory,
                    guid,
                    paused,
                    sendMessageChatArguments,
                    monoForumPeerId,
                    suggestionParams
            );
            return;
        }
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("AudioRecorder legacy selected: account=" + currentAccount
                    + ", dialog=" + dialogId
                    + ", guid=" + guid);
        }

        recordQueue.postRunnable(recordStartRunnable = () -> {
            if (audioRecorder != null) {
                AndroidUtilities.runOnUIThread(() -> {
                    recordStartRunnable = null;
                    NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.recordStartError, guid);
                });
                return;
            }

            setBluetoothScoOn(true);

            sendAfterDone = 0;
            recordingAudio = new TLRPC.TL_document();
            recordingGuid = guid;
            recordingAudioFocusGeneration = focusGeneration;
            recordingPreviewOwner = previewOwner;
            recordingAudio.file_reference = new byte[0];
            recordingAudio.dc_id = Integer.MIN_VALUE;
            recordingAudio.id = SharedConfig.getLastLocalId();
            recordingAudio.user_id = UserConfig.getInstance(currentAccount).getClientUserId();
            recordingAudio.mime_type = "audio/ogg";
            recordingAudio.file_reference = new byte[0];
            SharedConfig.saveConfig();

            recordingAudioFile = new File(FileLoader.getDirectory(FileLoader.MEDIA_DIR_AUDIO), System.currentTimeMillis() + "_" + FileLoader.getAttachFileName(recordingAudio)) {
                @Override
                public boolean delete() {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.e("delete voice file");
                    }
                    return super.delete();
                }
            };
            FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE).mkdirs();
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("start recording internal " + recordingAudioFile.getPath() + " " + recordingAudioFile.exists());
            }
            AutoDeleteMediaTask.lockFile(recordingAudioFile);
            try {
                if (startRecordingEncoder(recordingAudioFile) == 0) {
                    File failedFile = recordingAudioFile;
                    final long failureFocusGeneration = recordAudioFocusGeneration.get();
                    recordingAudio = null;
                    recordingAudioFile = null;
                    AutoDeleteMediaTask.unlockFile(failedFile);
                    if (failedFile != null) {
                        failedFile.delete();
                    }
                    setBluetoothScoOn(false);
                    AndroidUtilities.runOnUIThread(() -> {
                        if (recordAudioFocusGeneration.get() != failureFocusGeneration) {
                            return;
                        }
                        requestRecordAudioFocus(false);
                        recordStartRunnable = null;
                        NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.recordStartError, guid);
                    });
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("cant init encoder");
                    }
                    return;
                }

                audioRecorderPaused = false;
                recordStartTime = System.currentTimeMillis();
                recordTimeCount = 0;
                writtenFrame = 0;
                samplesCount = 0;
                recordDialogId = dialogId;
                recordMonoForumPeerId = monoForumPeerId;
                recordMonoForumSuggestionParams = suggestionParams;
                recordTopicId = replyToTopMsg == null ? 0 : MessageObject.getTopicId(recordingCurrentAccount, replyToTopMsg.messageOwner, false);
                recordingCurrentAccount = currentAccount;
                recordReplyingMsg = replyToMsg;
                recordReplyingTopMsg = replyToTopMsg;
                recordReplyingStory = replyStory;
                recordSendMessageChatArguments = sendMessageChatArguments;
                fileBuffer.rewind();

                audioRecorder = createVoiceAudioRecorder();
            } catch (Exception e) {
                FileLog.e(e);
                recordingAudio = null;
                stopRecordingEncoder(recordingAudioFile);
                AutoDeleteMediaTask.unlockFile(recordingAudioFile);
                recordingAudioFile.delete();
                recordingAudioFile = null;
                if (recordingPrevAudioFile != null) {
                    recordingPrevAudioFile.delete();
                    recordingPrevAudioFile = null;
                }
                try {
                    audioRecorder.release();
                    audioRecorder = null;
                } catch (Exception e2) {
                    FileLog.e(e2);
                }
                setBluetoothScoOn(false);

                AndroidUtilities.runOnUIThread(() -> {
                    recordStartRunnable = null;
                    NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.recordStartError, guid);
                });
                return;
            }

            recordQueue.postRunnable(recordRunnable);
            AndroidUtilities.runOnUIThread(() -> {
                recordStartRunnable = null;
                NotificationCenter.getInstance(currentAccount).postNotificationName(NotificationCenter.recordStarted, guid, true);
            });
        }, paused ? 500 : 50);
    }

    private AudioRecord createVoiceAudioRecorder() {
        AudioRecord recorder = app.nimarkogram.messenger.camera.VoiceAudioCapture.createStarted(
                ApplicationLoader.applicationContext,
                app.nimarkogram.messenger.AppConfig.getMediaRecorderAudioSource(), sampleRate, recordBufferSize,
                app.nimarkogram.messenger.AppConfig.cameraXMultiMicrophone);
        recordInputChannels = recorder.getChannelCount();
        return recorder;
    }

    public static final class MessageWaveformReady {
        private final String key;

        private MessageWaveformReady(String key) {
            this.key = key;
        }

        public boolean matches(MessageObject messageObject) {
            TLRPC.Document document = messageObject.getDocument();
            int account = messageObject.currentAccount;
            long userId = UserConfig.getInstance(account).getClientUserId();
            long session = app.nimarkogram.messenger.notifications.InAppNotifications.session(account);
            return document != null
                    && app.nimarkogram.messenger.notifications.InAppNotifications.isCurrent(account, userId, session)
                    && key.equals(waveformKey(messageObject, document, userId, session));
        }
    }

    private static String waveformKey(MessageObject messageObject, TLRPC.Document document, long userId, long session) {
        TLRPC.MessageMedia media = MessageObject.getMedia(messageObject.messageOwner);
        return playlistKey(messageObject) + "_" + userId + "_" + session + "_" + messageObject.scheduled
                + "_" + document.id + "_" + document.dc_id + "_" + document.size
                + "_" + (media == null ? 0 : media.ttl_seconds) + "_" + (document.localPath == null ? "" : document.localPath);
    }
    public void generateWaveform(MessageObject messageObject) {
        messageObject.getWaveform();
    }

    byte[] getOrGenerateWaveform(MessageObject messageObject, TLRPC.Document document) {
        final TLRPC.Message owner = messageObject.messageOwner;
        final int account = messageObject.currentAccount;
        final long userId = UserConfig.getInstance(account).getClientUserId();
        final long session = app.nimarkogram.messenger.notifications.InAppNotifications.session(account);
        if (!app.nimarkogram.messenger.notifications.InAppNotifications.isCurrent(account, userId, session)) return null;
        final String id = waveformKey(messageObject, document, userId, session);
        final byte[] cached = generatedWaveforms.get(id);
        if (cached != null) return cached;
        final Object request = new Object();
        if (generatingWaveform.putIfAbsent(id, request) != null) return null;
        boolean posted = Utilities.globalQueue.postRunnable(() -> {
            byte[] decoded = null;
            try {
                if (generatingWaveform.get(id) == request
                        && app.nimarkogram.messenger.notifications.InAppNotifications.isCurrent(account, userId, session)) {
                    File file = FileLoader.getInstance(account).getPathToMessage(owner);
                    if (file.isFile()) {
                        decoded = getWaveform(file.getAbsolutePath());
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            final byte[] waveform = decoded;
            AndroidUtilities.runOnUIThread(() -> {
                if (!generatingWaveform.remove(id, request) || waveform == null || waveform.length == 0
                        || !app.nimarkogram.messenger.notifications.InAppNotifications.isCurrent(account, userId, session)
                        || messageObject.messageOwner != owner || messageObject.getDocument() != document
                        || !id.equals(waveformKey(messageObject, document, userId, session))) return;
                generatedWaveforms.put(id, waveform);
                NotificationCenter.getInstance(account).postNotificationName(
                        NotificationCenter.messageWaveformDidLoad, new MessageWaveformReady(id));
            });
        });
        if (!posted) generatingWaveform.remove(id, request);
        return null;
    }

    public void cleanRecording(boolean delete) {
        if (audioRecorderV2Active) {
            if (audioRecorderV2StartRunnable != null) {
                AndroidUtilities.cancelRunOnUIThread(audioRecorderV2StartRunnable);
                audioRecorderV2StartRunnable = null;
            }
            boolean detachedPreview = false;
            if (!delete && audioRecorderV2Controller != null) {
                detachedPreview = audioRecorderV2Controller.detachPreviewOutput();
            }
            if (!detachedPreview && audioRecorderV2Controller != null) {
                audioRecorderV2Controller.cancel();
            }
            if (detachedPreview && recordingAudioFile != null) {
                AutoDeleteMediaTask.unlockFile(recordingAudioFile);
            }
            finishAudioRecorderV2Lifecycle(!delete);
            return;
        }
        cleanRecording(delete, -1);
    }

    private void cleanRecording(boolean delete, long focusGeneration) {
        synchronized (recordingTrimLock) {
            pendingRecordingTrim = null;
            recordingTrimGeneration++;
            recordingAudio = null;
            AutoDeleteMediaTask.unlockFile(recordingAudioFile);
            if (delete && recordingAudioFile != null) {
                try {
                    recordingAudioFile.delete();
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
            recordingAudioFile = null;
            if (delete && recordingPrevAudioFile != null) {
                recordingPrevAudioFile.delete();
            }
            recordingPrevAudioFile = null;
            if (focusGeneration < 0 || recordAudioFocusGeneration.get() == focusGeneration) {
            manualRecording = false;
            raiseToEarRecord = false;
            ignoreOnPause = false;
        }
    }

    }
    private void stopRecordingInternal(final int send, boolean notify, int scheduleDate, boolean once, long payStars) {
        final long focusGeneration = recordingAudioFocusGeneration;
        final RecordingPreviewOwner previewOwner = recordingPreviewOwner;
        if (send != 0 && recordingAudioFile != null) {
            final TLRPC.TL_document audioToSend = recordingAudio;
            final long userId = audioToSend.user_id;
            final long audioId = audioToSend.id;
            final File recordingAudioFileToSend_ = recordingAudioFile;
            final File recordingPrevAudioFileToSend_ = recordingPrevAudioFile;
            final int account = recordingCurrentAccount;
            final int guid = recordingGuid;
            final long dialogId = recordDialogId;
            final long topicId = recordTopicId;
            final long monoForumPeerId = recordMonoForumPeerId;
            final MessageSuggestionParams suggestionParams = recordMonoForumSuggestionParams;
            final MessageObject replyToMsg = recordReplyingMsg;
            final MessageObject replyToTopMsg = recordReplyingTopMsg;
            final TL_stories.StoryItem replyStory = recordReplyingStory;
            final SendMessageChatArguments sendMessageChatArguments = recordSendMessageChatArguments;
            if (BuildVars.LOGS_ENABLED) {
                FileLog.d("stop recording internal filename " + (recordingAudioFile.getPath()));
            }
            if (!finishRecordingOnEncodingQueue(() -> {
                finishRecordingEncoder(audioToSend, recordingAudioFileToSend_);
                final File recordingAudioFileToSend = joinRecord(recordingPrevAudioFileToSend_, recordingAudioFileToSend_, audioToSend);
                if (recordingAudioFileToSend == null) {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("stop recording recordingAudioFileToSend == null in queue");
                    }
                    onRecordingJoinFailed(account, guid, focusGeneration, userId);
                    return;
                }
                final long duration = recordTimeCount;
                final int frames = writtenFrame;
                final RecordingPreview preview = send == 2 && duration > 700
                        ? new RecordingPreview(audioId, recordingAudioFileToSend.getAbsolutePath(), duration, samplesCount, frames, recordSamples, once)
                        : null;
                if (BuildVars.LOGS_ENABLED) {
                    FileLog.d("stop recording internal in queue " + (recordingAudioFileToSend.exists() + " " + recordingAudioFileToSend.length()));
                }
                AndroidUtilities.runOnUIThread(() -> {
                    if (userId == 0 || UserConfig.getInstance(account).getClientUserId() != userId) {
                        return;
                    }
                    final boolean previewCurrent = takeRecordingPreviewOwner(previewOwner);
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.d("stop recording internal " + (recordingAudioFileToSend == null ? "null" : recordingAudioFileToSend.exists() + " " + recordingAudioFileToSend.length() + " " + " recordTimeCount " + duration + " writedFrames" + frames));
                    }
                    boolean fileExist = recordingAudioFileToSend != null && recordingAudioFileToSend.exists();
                    if (!fileExist && BuildVars.DEBUG_VERSION) {
                        FileLog.e(new RuntimeException("file not found :( recordTimeCount " + duration + " writedFrames" + frames));
                    }
                    MediaDataController mediaDataController = MediaDataController.getInstance(account);
                    MediaDataController.DraftVoice draft = mediaDataController.getDraftVoice(dialogId, topicId);
                    final boolean sameDraft = draft != null && draft.id == audioId
                            && (TextUtils.equals(draft.path, recordingAudioFileToSend_.getAbsolutePath())
                            || recordingPrevAudioFileToSend_ != null && TextUtils.equals(draft.path, recordingPrevAudioFileToSend_.getAbsolutePath())
                            || TextUtils.equals(draft.path, recordingAudioFileToSend.getAbsolutePath()));
                    if (preview != null) {
                        if (previewCurrent && (draft == null || sameDraft)) {
                            mediaDataController.pushDraftVoiceMessage(dialogId, topicId, preview.toDraft());
                        }
                    } else if (sameDraft) {
                        mediaDataController.pushDraftVoiceMessage(dialogId, topicId, null);
                    }
                    audioToSend.date = ConnectionsManager.getInstance(account).getCurrentTime();
                    audioToSend.size = recordingAudioFileToSend == null ? 0 : (int) recordingAudioFileToSend.length();
                    TLRPC.TL_documentAttributeAudio attributeAudio = new TLRPC.TL_documentAttributeAudio();
                    attributeAudio.voice = true;
                    attributeAudio.waveform = getWaveform(recordingAudioFileToSend.getAbsolutePath());
                    if (attributeAudio.waveform != null) {
                        attributeAudio.flags |= 4;
                    }
                    attributeAudio.duration = duration / 1000.0;
                    audioToSend.attributes.clear();
                    audioToSend.attributes.add(attributeAudio);
                    if (duration > 700) {
                        if (send == 1) {
                            SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(audioToSend, null, recordingAudioFileToSend.getAbsolutePath(), dialogId, replyToMsg, replyToTopMsg, null, null, null, null, notify, scheduleDate, 0, once ? 0x7FFFFFFF : 0, null, null, false);
                            params.monoForumPeer = monoForumPeerId;
                            params.suggestionParams = suggestionParams;
                            params.replyToStoryItem = replyStory;
                            params.sendMessageChatArguments = sendMessageChatArguments;
                            params.payStars = payStars;
                            SendMessagesHelper.getInstance(account).sendMessage(params);
                        }
                        if (UserConfig.getInstance(account).getClientUserId() == userId
                                && recordAudioFocusGeneration.get() == focusGeneration) {
                            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.audioDidSent, guid, send == 2 ? audioToSend : null, send == 2 ? recordingAudioFileToSend.getAbsolutePath() : null);
                        }
                    } else {
                        if (UserConfig.getInstance(account).getClientUserId() == userId
                                && recordAudioFocusGeneration.get() == focusGeneration) {
                            NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.audioRecordTooShort, guid, false, (int) duration);
                        }
                        if (recordingAudioFileToSend != null) {
                            AutoDeleteMediaTask.unlockFile(recordingAudioFileToSend);
                            recordingAudioFileToSend.delete();
                        }
                    }
                    if (UserConfig.getInstance(account).getClientUserId() == userId) {
                        releaseRecordingAudioFocus(focusGeneration);
                    }
                });
            })) {
                onRecordingJoinFailed(account, guid, focusGeneration, userId);
            }
        } else {
            AutoDeleteMediaTask.unlockFile(recordingAudioFile);
            if (recordingAudioFile != null) {
                recordingAudioFile.delete();
            }
            releaseRecordingAudioFocus(focusGeneration);
        }
        try {
            if (audioRecorder != null) {
                audioRecorder.release();
                audioRecorder = null;
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        recordingAudio = null;
        recordingPreviewOwner = null;
        recordingPrevAudioFile = null;
        recordingAudioFile = null;
        if (recordAudioFocusGeneration.get() == focusGeneration) {
        manualRecording = false;
        raiseToEarRecord = false;
        ignoreOnPause = false;
    }

    }

    private void finishIdleRecording(long focusGeneration, long trimGeneration,
            java.util.function.BooleanSupplier ownerCurrent, java.util.function.BooleanSupplier userCurrent, Runnable completed) {
        synchronized (recordingTrimLock) {
            if (recordAudioFocusGeneration.get() != focusGeneration || recordingTrimGeneration != trimGeneration
                    || audioRecorder != null || !ownerCurrent.getAsBoolean() || !userCurrent.getAsBoolean()) {
                return;
            }
            cleanRecording(false, focusGeneration);
            audioRecorderPaused = false;
            recordingPreviewOwner = null;
            if (pendingVoiceRecordingGeneration == focusGeneration) {
                pendingVoiceRecordingGeneration = -1;
            }
            final long terminalTrimGeneration = recordingTrimGeneration;
            AndroidUtilities.runOnUIThread(() -> {
                if (recordAudioFocusGeneration.get() != focusGeneration || recordingTrimGeneration != terminalTrimGeneration
                        || audioRecorder != null || recordingAudio != null) {
                    return;
                }
                setBluetoothScoOn(false);
                releaseRecordingAudioFocus(focusGeneration);
                if (completed != null && userCurrent.getAsBoolean()) completed.run();
            });
        }
    }
    public void stopRecording(final int send, boolean notify, int scheduleDate, boolean once, long payStars) {
        if (audioRecorderV2Active) {
            stopRecordingV2(send, notify, scheduleDate, once, payStars);
            return;
        }
        final long stopFocusGeneration = recordAudioFocusGeneration.get();
        final TLRPC.TL_document stopAudio = recordingAudioFocusGeneration == stopFocusGeneration ? recordingAudio : null;
        final boolean stopOwnsVoiceRequest = pendingVoiceRecordingGeneration == stopFocusGeneration;
        cancelRecordingTrim(null);
        final long stopTrimGeneration = recordingTrimGeneration;
        if (recordStartRunnable != null) {
            recordQueue.cancelRunnable(recordStartRunnable);
            recordStartRunnable = null;
        }
        recordQueue.postRunnable(() -> {
            final boolean ownsInstalledRecording = (recordingAudioFocusGeneration == stopFocusGeneration
                    || stopAudio != null && recordingAudio == stopAudio && recordingTrimGeneration == stopTrimGeneration)
                    && (audioRecorder != null || sendAfterDone == 3);
            if (!ownsInstalledRecording
                    && (recordAudioFocusGeneration.get() != stopFocusGeneration || recordingTrimGeneration != stopTrimGeneration)) {
                return;
            }
            if (sendAfterDone == 3) {
                sendAfterDone = 0;
                stopRecordingInternal(send, notify, scheduleDate, once, payStars);
                return;
            }
            if (audioRecorder == null) {
                final boolean ownsIdleRecording = recordingAudioFocusGeneration == stopFocusGeneration
                        && (recordingAudio != null || recordingAudioFile != null);
                if (!stopOwnsVoiceRequest && !ownsIdleRecording) return;
                recordQueue.postRunnable(() -> {
                    if (recordAudioFocusGeneration.get() != stopFocusGeneration || recordingTrimGeneration != stopTrimGeneration
                            || audioRecorder != null) return;
                    final TLRPC.TL_document idleAudio = recordingAudio;
                    final File idleFile = recordingAudioFile;
                    if (idleFile != null && !finishRecordingOnEncodingQueue(() -> stopRecordingEncoder(idleFile))) {
                        stopRecordingEncoder(idleFile);
                    }
                    finishIdleRecording(stopFocusGeneration, stopTrimGeneration,
                            () -> recordingAudio == idleAudio && recordingAudioFile == idleFile, () -> true, null);
                });
                return;
            }
            final int account = recordingCurrentAccount;
            final int guid = recordingGuid;
            final long focusGeneration = recordingAudioFocusGeneration;
            try {
                sendAfterDone = send;
                sendAfterDoneNotify = notify;
                sendAfterDoneScheduleDate = scheduleDate;
                sendAfterDoneOnce = once;
                sendAfterDonePayStars = payStars;
                audioRecorder.stop();
                setBluetoothScoOn(false);
            } catch (Exception e) {
                FileLog.e(e);
                if (recordingAudioFile != null) {
                    if (BuildVars.LOGS_ENABLED) {
                        FileLog.e("delete voice file");
                    }
                    recordingAudioFile.delete();
                }
            }
            if (send == 0) {
                stopRecordingInternal(0, false, 0, false, 0);
            }
            if (!app.nimarkogram.messenger.AppConfig.disableVibration) {
                try {
                    feedbackView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
                } catch (Exception ignore) {}
            }
            AndroidUtilities.runOnUIThread(() -> {
                if (recordAudioFocusGeneration.get() == focusGeneration) {
                    NotificationCenter.getInstance(account).postNotificationName(NotificationCenter.recordStopped, guid, send == 2 ? 1 : 0);
                }
            });
        });
    }

    private static class MediaLoader implements NotificationCenter.NotificationCenterDelegate {

        private final AccountInstance currentAccount;
        private final AlertDialog progressDialog;
        private final ArrayList<MessageObject> messageObjects;
        private final HashMap<String, CountDownLatch> loadingMessageObjects = new HashMap<>();
        private final Object waitLock = new Object();
        private final AtomicBoolean completionPosted = new AtomicBoolean();
        private final long ownerUserId, ownerSession;
        private float finishedProgress;
        private volatile boolean cancelled;
        private volatile boolean finished;
        private boolean uiDetached;
        private int copiedFiles;
        private CountDownLatch waitingForFile;
        private MessagesStorage.IntCallback onFinishRunnable;
        private boolean isMusic;

        public MediaLoader(Context context, AccountInstance accountInstance, ArrayList<MessageObject> messages, MessagesStorage.IntCallback onFinish) {
            currentAccount = accountInstance;
            ownerUserId = accountInstance.getUserConfig().getClientUserId();
            ownerSession = app.nimarkogram.messenger.notifications.InAppNotifications.session(accountInstance.getCurrentAccount());
            messageObjects = new ArrayList<>(messages);
            onFinishRunnable = onFinish;
            isMusic = messages.get(0).isMusic();
            final Theme.ResourcesProvider resourcesProvider = PhotoViewer.getInstance().isVisible() ? new DarkThemeResourceProvider() : null;
            progressDialog = new AlertDialog(context, AlertDialog.ALERT_TYPE_LOADING, resourcesProvider);
            progressDialog.setMessage(LocaleController.getString(R.string.Loading));
            progressDialog.setCancelable(true);
            progressDialog.setCancelDialog(true);
            progressDialog.setOnCancelListener(d -> cancel());
            currentAccount.getNotificationCenter().addObserver(this, NotificationCenter.fileLoaded);
            currentAccount.getNotificationCenter().addObserver(this, NotificationCenter.fileLoadProgressChanged);
            currentAccount.getNotificationCenter().addObserver(this, NotificationCenter.fileLoadFailed);
            currentAccount.getNotificationCenter().addObserver(this, NotificationCenter.appDidLogout);
        }

        public void start() {
            AndroidUtilities.runOnUIThread(() -> {
                if (!isOwnerCurrent()) {
                    cancel();
                    return;
                }
                if (!finished && !cancelled) {
                    try {
                    progressDialog.show();
                    } catch (Exception e) {
                        FileLog.e(e);
                        cancel();
                    }
                }
            }, 250);

            Thread worker = new Thread(() -> {
                try {
                    if (cancelled) return;
                    if (Build.VERSION.SDK_INT >= 29) {
                        for (int b = 0, N = messageObjects.size(); b < N; b++) {
                            if (cancelled) break;
                            MessageObject message = messageObjects.get(b);
                            if (processLivePhotoMessage(message)) {
                                continue;
                            }
                            String path = message.messageOwner.attachPath;
                            TLRPC.Document document = message.getDocument();
                            if (message.qualityToSave != null) {
                                document = message.qualityToSave;
                                path = null;
                            }
                            String name = FileLoader.getDocumentFileName(document);
                            if (path != null && path.length() > 0) {
                                File temp = new File(path);
                                if (!temp.exists()) {
                                    path = null;
                                }
                            }
                            if (TextUtils.isEmpty(path)) {
                                final FileLoader fileLoader = FileLoader.getInstance(currentAccount.getCurrentAccount());
                                final TLRPC.MessageMedia media = MessageObject.getMedia(message);
                                File file = null;
                                if (message.qualityToSave != null) {
                                    file = fileLoader.getPathToAttach(message.qualityToSave, null, false, true);
                                } else {
                                    file = fileLoader.getPathToMessage(message.messageOwner, true);
                                    if (media instanceof TLRPC.TL_messageMediaDocument) {
                                        final TLRPC.TL_messageMediaDocument mediaDocument = (TLRPC.TL_messageMediaDocument) media;
                                        if (!mediaDocument.alt_documents.isEmpty()) {
                                            file = fileLoader.getPathToAttach(mediaDocument.alt_documents.get(0), null, false, true);
                                        }
                                    }
                                }
                                path = file.toString();
                            }
                            File sourceFile = new File(path);
                            if (!sourceFile.exists()) {
                                CountDownLatch wait = beginWait(1);
                                if (wait == null) break;
                                addMessageToLoad(message, wait);
                                if (!awaitFiles(wait)) break;
                            }
                            if (cancelled) {
                                break;
                            }
                            if (!sourceFile.exists()) {
                                sourceFile = FileLoader.getInstance(currentAccount.getCurrentAccount()).getPathToAttach(message.messageOwner, true);
                                FileLog.d("saving file: correcting path from " + path + " to " + (sourceFile == null ? null : sourceFile.getAbsolutePath()));
                            }
                            if (sourceFile != null && sourceFile.exists()) {
                                if (saveFileInternal(isMusic ? 3 : 2, sourceFile, name, () -> cancelled) != null) copiedFiles++;
                            }
                        }
                    } else {
                        File dir;
                        if (isMusic) {
                            dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
                        } else {
                            dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                        }
                        dir.mkdir();
                        for (int b = 0, N = messageObjects.size(); b < N; b++) {
                            if (cancelled) break;
                            MessageObject message = messageObjects.get(b);
                            if (processLivePhotoMessage(message)) {
                                continue;
                            }
                            TLRPC.Document document = message.getDocument();
                            if (message.qualityToSave != null) {
                                document = message.qualityToSave;
                            }
                            String name = FileLoader.getDocumentFileName(document);
                            String path = message.messageOwner.attachPath;
                            if (message.qualityToSave != null) {
                                path = null;
                            }
                            if (path != null && path.length() > 0) {
                                File temp = new File(path);
                                if (!temp.exists()) {
                                    path = null;
                                }
                            }
                            File sourceFile;
                            if (message.qualityToSave != null) {
                                sourceFile = FileLoader.getInstance(currentAccount.getCurrentAccount()).getPathToAttach(message.qualityToSave, null, false, true);
                            } else {
                                if (path == null || path.length() == 0) {
                                    path = FileLoader.getInstance(currentAccount.getCurrentAccount()).getPathToMessage(message.messageOwner).toString();
                                }
                                sourceFile = new File(path);
                            }
                            if (!sourceFile.exists()) {
                                CountDownLatch wait = beginWait(1);
                                if (wait == null) break;
                                addMessageToLoad(message, wait);
                                if (!awaitFiles(wait)) break;
                            }
                            if (cancelled) break;
                            if (sourceFile.exists()) {
                                File destFile = reserveDestination(dir, name);
                                if (destFile == null) break;
                                boolean copied = false;
                                try {
                                    if (!cancelled) copied = copyFile(sourceFile, destFile, message.getMimeType());
                                } finally {
                                    if (!copied) destFile.delete();
                                }
                                if (copied) copiedFiles++;
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } catch (Exception e) {
                    if (!cancelled) FileLog.e(e);
                } finally {
                    finish();
                }
            });
            try {
                worker.start();
            } catch (RuntimeException e) {
                FileLog.e(e);
                finish();
            }
        }

        private void cancel() {
            synchronized (waitLock) {
                cancelled = true;
                releaseWait(waitingForFile);
                waitingForFile = null;
            }
            detachUi();
        }

        private static void releaseWait(CountDownLatch wait) {
            if (wait != null) while (wait.getCount() > 0) wait.countDown();
        }

        private CountDownLatch beginWait(int count) {
            synchronized (waitLock) {
                if (cancelled || completionPosted.get()) return null;
                return waitingForFile = new CountDownLatch(count);
            }
        }

        private boolean isWaitActive(CountDownLatch wait) {
            synchronized (waitLock) {
                return wait != null && !cancelled && !finished && !completionPosted.get() && waitingForFile == wait;
            }
        }

        private boolean awaitFiles(CountDownLatch wait) throws InterruptedException {
            if (wait == null) return false;
            try {
                if (cancelled) return false;
                wait.await();
                return !cancelled;
            } finally {
                synchronized (waitLock) {
                    if (waitingForFile == wait) waitingForFile = null;
                }
            }
        }

        private void detachUi() {
                        finished = true;
            if (uiDetached) return;
            uiDetached = true;
            loadingMessageObjects.clear();
                currentAccount.getNotificationCenter().removeObserver(this, NotificationCenter.fileLoaded);
                currentAccount.getNotificationCenter().removeObserver(this, NotificationCenter.fileLoadProgressChanged);
                currentAccount.getNotificationCenter().removeObserver(this, NotificationCenter.fileLoadFailed);
            currentAccount.getNotificationCenter().removeObserver(this, NotificationCenter.appDidLogout);
            try {
                progressDialog.dismiss();
            } catch (Exception e) {
                FileLog.e(e);
            }
        }

        private void finish() {
            if (!completionPosted.compareAndSet(false, true)) return;
            synchronized (waitLock) {
                releaseWait(waitingForFile);
                waitingForFile = null;
            }
            final int saved = copiedFiles;
            AndroidUtilities.runOnUIThread(() -> {
                detachUi();
                final MessagesStorage.IntCallback callback = onFinishRunnable;
                onFinishRunnable = null;
                if (callback != null) {
                    AndroidUtilities.runOnUIThread(() -> {
                        if (isOwnerCurrent()) callback.run(saved);
                    });
                }
            });
        }

        private File reserveDestination(File directory, String name) throws IOException {
            int dot = name.lastIndexOf('.');
            for (int suffix = 0; suffix < 10000; suffix++) {
                if (cancelled) return null;
                String candidateName = suffix == 0 ? name : dot >= 0
                        ? name.substring(0, dot) + "(" + suffix + ")" + name.substring(dot)
                        : name + "(" + suffix + ")";
                File candidate = new File(directory, candidateName);
                if (candidate.createNewFile()) return candidate;
            }
            throw new IOException("No unused export filename");
        }

        private void updateProgress(int progress) {
            AndroidUtilities.runOnUIThread(() -> {
                if (finished || cancelled) return;
                try {
                    progressDialog.setProgress(progress);
                } catch (Exception e) {
                    FileLog.e(e);
                }
            });
        }

        private boolean processLivePhotoMessage(MessageObject messageObject) throws Exception {
            if (!messageObject.isLivePhoto()) {
                return false;
            }
            if (cancelled) return true;
            final TLRPC.MessageMedia media = MessageObject.getMedia(messageObject.messageOwner);
            if (media == null || media.photo == null || media.document == null) {
                return false;
            }
            final TLRPC.PhotoSize photoSize = FileLoader.getClosestPhotoSizeWithSize(media.photo.sizes, AndroidUtilities.getPhotoSize(true), false, null, true);
            if (photoSize == null) {
                return false;
            }
            final FileLoader fileLoader = FileLoader.getInstance(currentAccount.getCurrentAccount());
            File photoFile = fileLoader.getPathToAttach(photoSize, null, false, true);
            File videoFile = fileLoader.getPathToAttach(media.document, null, false, true);

            final boolean needPhoto = photoFile == null || !photoFile.exists();
            final boolean needVideo = videoFile == null || !videoFile.exists();
            final int needCount = (needPhoto ? 1 : 0) + (needVideo ? 1 : 0);
            if (needCount > 0) {
                final CountDownLatch wait = beginWait(needCount);
                if (wait == null) return true;
                final TLRPC.Photo finalPhoto = media.photo;
                final TLRPC.Document finalDoc = media.document;
                AndroidUtilities.runOnUIThread(() -> {
                    if (!isWaitActive(wait)) return;
                    if (!isOwnerCurrent()) {
                        cancel();
                        return;
                    }
                    try {
                    if (needPhoto) {
                        String fileName = FileLoader.getAttachFileName(photoSize);
                            loadingMessageObjects.put(fileName, wait);
                        currentAccount.getFileLoader().loadFile(ImageLocation.getForPhoto(photoSize, finalPhoto), messageObject, "jpg", FileLoader.PRIORITY_HIGH, 0);
                    }
                        if (needVideo && isWaitActive(wait)) {
                        String fileName = FileLoader.getAttachFileName(finalDoc);
                            loadingMessageObjects.put(fileName, wait);
                        currentAccount.getFileLoader().loadFile(finalDoc, messageObject, FileLoader.PRIORITY_HIGH, 0);
                    }
                    } catch (RuntimeException e) {
                        loadingMessageObjects.values().removeIf(owner -> owner == wait);
                        releaseWait(wait);
                        if (!cancelled) FileLog.e(e);
                    }
                });
                if (!awaitFiles(wait)) return true;
            }
            if (cancelled) {
                return true;
            }
            if (photoFile == null || !photoFile.exists()) {
                photoFile = fileLoader.getPathToAttach(photoSize, null, true, true);
            }
            if (videoFile == null || !videoFile.exists()) {
                videoFile = fileLoader.getPathToAttach(media.document, null, true, true);
            }
            if (photoFile == null || !photoFile.exists() || videoFile == null || !videoFile.exists()) {
                return true;
            }

            String coverExt = FileLoader.getFileExtension(photoFile);
            if (TextUtils.isEmpty(coverExt)) {
                coverExt = "jpg";
            }
            String coverMime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(coverExt.toLowerCase());
            if (TextUtils.isEmpty(coverMime)) {
                coverMime = "image/jpeg";
            }
            final String filename = AndroidUtilities.generateFileName(0, coverExt);
            final String outputMime = coverMime;
            if (Build.VERSION.SDK_INT >= 29) {
                final ContentValues cv = new ContentValues();
                final Uri uriToInsert = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                final File dirDest = new File(Environment.DIRECTORY_DOWNLOADS, "NimarkoGram");
                cv.put(MediaStore.MediaColumns.RELATIVE_PATH, dirDest + File.separator);
                cv.put(MediaStore.Downloads.DISPLAY_NAME, filename);
                cv.put(MediaStore.MediaColumns.MIME_TYPE, outputMime);
                final Uri dst = ApplicationLoader.applicationContext.getContentResolver().insert(uriToInsert, cv);
                if (dst != null) {
                    boolean ok = false;
                    try {
                        boolean written = false;
                    try (OutputStream os = ApplicationLoader.applicationContext.getContentResolver().openOutputStream(dst)) {
                        if (os != null) {
                                writeLivePhoto(photoFile, videoFile, os);
                                written = true;
                            }
                        }
                        if (written && !cancelled) {
                        copiedFiles++;
                            ok = true;
                        }
                    } finally {
                        if (!ok) {
                        try {
                            ApplicationLoader.applicationContext.getContentResolver().delete(dst, null, null);
                            } catch (Exception e) { FileLog.e(e); }
                        }
                    }
                }
            } else {
                final File destDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "NimarkoGram");
                destDir.mkdirs();
                File destFile = reserveDestination(destDir, filename);
                if (destFile == null) return true;
                boolean ok = false;
                try {
                try (FileOutputStream fos = new FileOutputStream(destFile)) {
                        writeLivePhoto(photoFile, videoFile, fos);
                    }
                    if (!cancelled) {
                    DownloadManager downloadManager = (DownloadManager) ApplicationLoader.applicationContext.getSystemService(Context.DOWNLOAD_SERVICE);
                    downloadManager.addCompletedDownload(destFile.getName(), destFile.getName(), false, outputMime, destFile.getAbsolutePath(), destFile.length(), true);
                    copiedFiles++;
                        ok = true;
                    }
                } finally {
                    if (!ok) destFile.delete();
                }
            }
            finishedProgress += 100.0f / messageObjects.size();
            updateProgress((int) finishedProgress);
            return true;
        }

        private void writeLivePhoto(File photo, File video, OutputStream output) throws IOException {
            writeMotionPhoto(photo, video, new OutputStream() {
                @Override public void write(int value) throws IOException {
                    if (cancelled) throw new IOException("Export cancelled");
                    output.write(value);
                }
                @Override public void write(byte[] bytes, int offset, int length) throws IOException {
                    if (cancelled) throw new IOException("Export cancelled");
                    output.write(bytes, offset, length);
                }
            }, null);
        }

        private boolean isOwnerCurrent() {
            return app.nimarkogram.messenger.notifications.InAppNotifications.isCurrent(
                    currentAccount.getCurrentAccount(), ownerUserId, ownerSession);
        }

        private void addMessageToLoad(MessageObject messageObject, CountDownLatch wait) {
            AndroidUtilities.runOnUIThread(() -> {
                if (!isWaitActive(wait)) return;
                if (!isOwnerCurrent()) {
                    cancel();
                    return;
                }
                TLRPC.Document document = messageObject.getDocument();
                if (messageObject.qualityToSave != null) {
                    document = messageObject.qualityToSave;
                }
                if (document == null) {
                    releaseWait(wait);
                    return;
                }
                String fileName = FileLoader.getAttachFileName(document);
                loadingMessageObjects.put(fileName, wait);
                try {
                currentAccount.getFileLoader().loadFile(document, messageObject, FileLoader.PRIORITY_HIGH, messageObject.shouldEncryptPhotoOrVideo() ? 2 : 0);
                } catch (RuntimeException e) {
                    loadingMessageObjects.remove(fileName, wait);
                    releaseWait(wait);
                    if (!cancelled) FileLog.e(e);
                }
            });
        }

        private boolean copyFile(File sourceFile, File destFile, String mime) {
            if (AndroidUtilities.isInternalUri(Uri.fromFile(sourceFile))) {
                return false;
            }
            try (FileInputStream inputStream = new FileInputStream(sourceFile); FileChannel source = inputStream.getChannel(); FileChannel destination = new FileOutputStream(destFile).getChannel()) {
                long size = source.size();
                try {
                    @SuppressLint("DiscouragedPrivateApi") Method getInt = FileDescriptor.class.getDeclaredMethod("getInt$");
                    int fdint = (Integer) getInt.invoke(inputStream.getFD());
                    if (AndroidUtilities.isInternalUri(fdint)) {
                        if (progressDialog != null) {
                            AndroidUtilities.runOnUIThread(() -> {
                                try {
                                    progressDialog.dismiss();
                                } catch (Exception e) {
                                    FileLog.e(e);
                                }
                            });
                        }
                        return false;
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                }
                long lastProgress = 0;
                for (long a = 0; a < size;) {
                    if (cancelled) {
                        break;
                    }
                    long copied = destination.transferFrom(source, a, Math.min(4096, size - a));
                    if (copied <= 0) throw new IOException("Incomplete export copy");
                    a += copied;
                    if (a >= size || lastProgress <= SystemClock.elapsedRealtime() - 500) {
                        lastProgress = SystemClock.elapsedRealtime();
                        final int progress = (int) (finishedProgress + 100.0f / messageObjects.size() * a / size);
                        updateProgress(progress);
                    }
                }
                if (!cancelled) {
                    if (isMusic) {
                        AndroidUtilities.addMediaToGallery(destFile);
                    } else {
                        DownloadManager downloadManager = (DownloadManager) ApplicationLoader.applicationContext.getSystemService(Context.DOWNLOAD_SERVICE);
                        String mimeType = mime;
                        if (TextUtils.isEmpty(mimeType)) {
                            MimeTypeMap myMime = MimeTypeMap.getSingleton();
                            String name = destFile.getName();
                            int idx = name.lastIndexOf('.');
                            if (idx != -1) {
                                String ext = name.substring(idx + 1);
                                mimeType = myMime.getMimeTypeFromExtension(ext.toLowerCase());
                                if (TextUtils.isEmpty(mimeType)) {
                                    mimeType = "text/plain";
                                }
                            } else {
                                mimeType = "text/plain";
                            }
                        }
                        downloadManager.addCompletedDownload(destFile.getName(), destFile.getName(), false, mimeType, destFile.getAbsolutePath(), destFile.length(), true);
                    }
                    finishedProgress += 100.0f / messageObjects.size();
                    updateProgress((int) finishedProgress);
                    return true;
                }
                        } catch (Exception e) {
                            FileLog.e(e);
                        }
            return false;
        }

        @Override
        public void didReceivedNotification(int id, int account, Object... args) {
            if (account != currentAccount.getCurrentAccount() || finished) return;
            if (id == NotificationCenter.appDidLogout) {
                cancel();
                return;
            }
            if (cancelled) return;
            if (id == NotificationCenter.fileLoaded || id == NotificationCenter.fileLoadFailed) {
                String fileName = (String) args[0];
                CountDownLatch wait = loadingMessageObjects.remove(fileName);
                if (wait != null) wait.countDown();
            } else if (id == NotificationCenter.fileLoadProgressChanged) {
                String fileName = (String) args[0];
                if (loadingMessageObjects.containsKey(fileName)) {
                    Long loadedSize = (Long) args[1];
                    Long totalSize = (Long) args[2];
                    float loadProgress = loadedSize / (float) totalSize;
                    final int progress = (int) (finishedProgress + loadProgress / messageObjects.size() * 100);
                    updateProgress(progress);
                }
            }
        }
    }

    public static void saveFilesFromMessages(Context context, AccountInstance accountInstance, ArrayList<MessageObject> messageObjects, final MessagesStorage.IntCallback onSaved) {
        if (messageObjects == null || messageObjects.isEmpty()) {
            return;
        }
        new MediaLoader(context, accountInstance, messageObjects, onSaved).start();
    }

    public static void saveFile(String fullPath, Context context, final int type, final String name, final String mime) {
        saveFile(fullPath, context, type, name, mime, null);
    }

    public static void saveFile(String fullPath, Context context, final int type, final String name, final String mime, final Utilities.Callback<Uri> onSaved) {
        saveFile(fullPath, context, type, name, mime, onSaved, true);
    }

    public static void saveFile(String fullPath, Context context, final int type, final String name, final String mime, final Utilities.Callback<Uri> onSaved, boolean showProgress) {
        if (fullPath == null || context == null) {
            return;
        }

        File file = null;
        if (!TextUtils.isEmpty(fullPath)) {
            file = new File(fullPath);
            if (!file.exists() || AndroidUtilities.isInternalUri(Uri.fromFile(file))) {
                file = null;
            }
        }

        if (file == null) {
            return;
        }

        final File sourceFile = file;
        final boolean[] cancelled = new boolean[]{false};
        if (sourceFile.exists()) {

            AlertDialog progressDialog = null;
            final boolean[] finished = new boolean[1];
            if (context != null && type != 0) {
                try {
                    final AlertDialog dialog = new AlertDialog(context, AlertDialog.ALERT_TYPE_LOADING);
                    dialog.setMessage(LocaleController.getString(R.string.Loading));
                    dialog.setCanceledOnTouchOutside(false);
                    dialog.setCancelable(true);
                    dialog.setOnCancelListener(d -> cancelled[0] = true);
                    AndroidUtilities.runOnUIThread(() -> {
                        if (!finished[0]) {
                            dialog.show();
                        }
                    }, 250);
                    progressDialog = dialog;
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }

            final AlertDialog finalProgress = progressDialog;

            new Thread(() -> {
                try {
                    Uri uri;
                    boolean result = true;
                    if (Build.VERSION.SDK_INT >= 29) {
                        uri = saveFileInternal(type, sourceFile, null);
                        result = uri != null;
                    } else {
                        File destFile;
                        if (type == 0) {
                            destFile = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "NimarkoGram");
                            destFile.mkdirs();
                            destFile = new File(destFile, AndroidUtilities.generateFileName(0, FileLoader.getFileExtension(sourceFile)));
                        } else if (type == 1) {
                            destFile = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "NimarkoGram");
                            destFile.mkdirs();
                            destFile = new File(destFile, AndroidUtilities.generateFileName(1, FileLoader.getFileExtension(sourceFile)));
                        } else {
                            File dir;
                            if (type == 2) {
                                dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                            } else {
                                dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC);
                            }
                            dir = new File(dir, "NimarkoGram");
                            dir.mkdirs();
                            destFile = new File(dir, name);
                            if (destFile.exists()) {
                                int idx = name.lastIndexOf('.');
                                for (int a = 0; a < 10; a++) {
                                    String newName;
                                    if (idx != -1) {
                                        newName = name.substring(0, idx) + "(" + (a + 1) + ")" + name.substring(idx);
                                    } else {
                                        newName = name + "(" + (a + 1) + ")";
                                    }
                                    destFile = new File(dir, newName);
                                    if (!destFile.exists()) {
                                        break;
                                    }
                                }
                            }
                        }
                        if (!destFile.exists()) {
                            destFile.createNewFile();
                        }
                        long lastProgress = System.currentTimeMillis() - 500;
                        try (FileInputStream inputStream = new FileInputStream(sourceFile); FileChannel source = inputStream.getChannel(); FileChannel destination = new FileOutputStream(destFile).getChannel()) {
                            long size = source.size();
                            try {
                                @SuppressLint("DiscouragedPrivateApi") Method getInt = FileDescriptor.class.getDeclaredMethod("getInt$");
                                int fdint = (Integer) getInt.invoke(inputStream.getFD());
                                if (AndroidUtilities.isInternalUri(fdint)) {
                                    if (finalProgress != null) {
                                        AndroidUtilities.runOnUIThread(() -> {
                                            try {
                                                finalProgress.dismiss();
                                            } catch (Exception e) {
                                                FileLog.e(e);
                                            }
                                        });
                                    }
                                    return;
                                }
                            } catch (Throwable e) {
                                FileLog.e(e);
                            }
                            for (long a = 0; a < size; a += 4096) {
                                if (cancelled[0]) {
                                    break;
                                }
                                destination.transferFrom(source, a, Math.min(4096, size - a));
                                if (finalProgress != null) {
                                    if (lastProgress <= System.currentTimeMillis() - 500) {
                                        lastProgress = System.currentTimeMillis();
                                        final int progress = (int) ((float) a / (float) size * 100);
                                        AndroidUtilities.runOnUIThread(() -> {
                                            try {
                                                finalProgress.setProgress(progress);
                                            } catch (Exception e) {
                                                FileLog.e(e);
                                            }
                                        });
                                    }
                                }
                            }
                        } catch (Exception e) {
                            FileLog.e(e);
                            result = false;
                        }
                        if (cancelled[0]) {
                            destFile.delete();
                            result = false;
                        }
                        if (result) {
                            if (type == 2) {
                                DownloadManager downloadManager = (DownloadManager) ApplicationLoader.applicationContext.getSystemService(Context.DOWNLOAD_SERVICE);
                                downloadManager.addCompletedDownload(destFile.getName(), destFile.getName(), false, mime, destFile.getAbsolutePath(), destFile.length(), true);
                            } else {
                                AndroidUtilities.addMediaToGallery(destFile.getAbsoluteFile());
                            }
                        }
                        uri = Uri.fromFile(destFile);
                    }
                    if (result && onSaved != null) {
                        AndroidUtilities.runOnUIThread(() -> onSaved.run(uri));
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                }
                if (finalProgress != null) {
                    AndroidUtilities.runOnUIThread(() -> {
                        try {
                            if (finalProgress.isShowing()) {
                                finalProgress.dismiss();
                            } else {
                                finished[0] = true;
                            }
                        } catch (Exception e) {
                            FileLog.e(e);
                        }
                    });
                }
            }).start();
        }
    }

    public static void saveFile(String fullPath, String liveVideoPath, Context context, final Utilities.Callback<Uri> onSaved) {
        if (TextUtils.isEmpty(fullPath) || TextUtils.isEmpty(liveVideoPath) || context == null) {
            return;
        }
        final File photoFile = new File(fullPath);
        final File videoFile = new File(liveVideoPath);
        if (!photoFile.exists() || !videoFile.exists()) {
            saveFile(fullPath, context, 0, null, null, onSaved);
            return;
        }
        if (AndroidUtilities.isInternalUri(Uri.fromFile(photoFile)) || AndroidUtilities.isInternalUri(Uri.fromFile(videoFile))) {
            return;
        }

        final boolean[] cancelled = new boolean[]{false};
        AlertDialog progressDialog = null;
        final boolean[] finished = new boolean[1];
        try {
            final AlertDialog dialog = new AlertDialog(context, AlertDialog.ALERT_TYPE_LOADING);
            dialog.setMessage(LocaleController.getString(R.string.Loading));
            dialog.setCanceledOnTouchOutside(false);
            dialog.setCancelable(true);
            dialog.setOnCancelListener(d -> cancelled[0] = true);
            AndroidUtilities.runOnUIThread(() -> {
                if (!finished[0]) {
                    dialog.show();
                }
            }, 250);
            progressDialog = dialog;
        } catch (Exception e) {
            FileLog.e(e);
        }
        final AlertDialog finalProgress = progressDialog;

        new Thread(() -> {
            Uri savedUri = null;
            boolean ok = false;
            try {
                if (Build.VERSION.SDK_INT >= 29) {
                    final String filename = AndroidUtilities.generateFileName(0, "jpg");
                    final ContentValues cv = new ContentValues();
                    final Uri uriToInsert = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                    final File dirDest = new File(Environment.DIRECTORY_PICTURES, "NimarkoGram");
                    cv.put(MediaStore.MediaColumns.RELATIVE_PATH, dirDest + File.separator);
                    cv.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
                    cv.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                    cv.put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg");
                    final Uri dst = ApplicationLoader.applicationContext.getContentResolver().insert(uriToInsert, cv);
                    if (dst != null) {
                        try (OutputStream os = ApplicationLoader.applicationContext.getContentResolver().openOutputStream(dst)) {
                            if (os != null) {
                                writeMotionPhoto(photoFile, videoFile, os, cancelled);
                                ok = !cancelled[0];
                            }
                        }
                        if (ok) {
                            savedUri = dst;
                        } else {
                            try {
                                ApplicationLoader.applicationContext.getContentResolver().delete(dst, null, null);
                            } catch (Exception ignored) {}
                        }
                    }
                } else {
                    final File destDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "NimarkoGram");
                    destDir.mkdirs();
                    File destFile = new File(destDir, AndroidUtilities.generateFileName(0, "jpg"));
                    if (!destFile.exists()) {
                        destFile.createNewFile();
                    }
                    try (FileOutputStream fos = new FileOutputStream(destFile)) {
                        writeMotionPhoto(photoFile, videoFile, fos, cancelled);
                    }
                    if (cancelled[0]) {
                        destFile.delete();
                    } else {
                        AndroidUtilities.addMediaToGallery(destFile.getAbsoluteFile());
                        savedUri = Uri.fromFile(destFile);
                        ok = true;
                    }
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            if (ok && onSaved != null) {
                final Uri finalUri = savedUri;
                AndroidUtilities.runOnUIThread(() -> onSaved.run(finalUri));
            }
            if (finalProgress != null) {
                AndroidUtilities.runOnUIThread(() -> {
                    try {
                        if (finalProgress.isShowing()) {
                            finalProgress.dismiss();
                        } else {
                            finished[0] = true;
                        }
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                });
            }
        }).start();
    }

    private static void writeMotionPhoto(File photoFile, File videoFile, OutputStream out, boolean[] cancelled) throws IOException {
        final long videoLength = videoFile.length();
        final String xmp = buildMotionPhotoXmp(videoLength);
        final byte[] xmpHeader = "http://ns.adobe.com/xap/1.0/ ".getBytes("UTF-8");
        final byte[] xmpBytes = xmp.getBytes("UTF-8");
        final int segLen = xmpHeader.length + xmpBytes.length + 2;
        if (segLen > 65535) {
            throw new IOException("XMP segment too large: " + segLen);
        }

        try (FileInputStream pis = new FileInputStream(photoFile)) {
            final int b0 = pis.read();
            final int b1 = pis.read();
            if (b0 != 0xFF || b1 != 0xD8) {
                throw new IOException("Not a JPEG: " + photoFile);
            }
            out.write(0xFF);
            out.write(0xD8);
            out.write(0xFF);
            out.write(0xE1);
            out.write((segLen >> 8) & 0xFF);
            out.write(segLen & 0xFF);
            out.write(xmpHeader);
            out.write(xmpBytes);
            final byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = pis.read(buf)) > 0) {
                if (cancelled != null && cancelled[0]) return;
                out.write(buf, 0, n);
            }
        }
        try (FileInputStream vis = new FileInputStream(videoFile)) {
            final byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = vis.read(buf)) > 0) {
                if (cancelled != null && cancelled[0]) return;
                out.write(buf, 0, n);
            }
        }
    }

    private static String buildMotionPhotoXmp(long videoLength) {
        return "<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>" +
                "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\">" +
                "<rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">" +
                "<rdf:Description rdf:about=\"\" " +
                "xmlns:GCamera=\"http://ns.google.com/photos/1.0/camera/\" " +
                "xmlns:Container=\"http://ns.google.com/photos/1.0/container/\" " +
                "xmlns:Item=\"http://ns.google.com/photos/1.0/container/item/\" " +
                "GCamera:MotionPhoto=\"1\" " +
                "GCamera:MotionPhotoVersion=\"1\" " +
                "GCamera:MotionPhotoPresentationTimestampUs=\"0\">" +
                "<Container:Directory>" +
                "<rdf:Seq>" +
                "<rdf:li rdf:parseType=\"Resource\">" +
                "<Container:Item Item:Mime=\"image/jpeg\" Item:Semantic=\"Primary\" Item:Length=\"0\" Item:Padding=\"0\"/>" +
                "</rdf:li>" +
                "<rdf:li rdf:parseType=\"Resource\">" +
                "<Container:Item Item:Mime=\"video/mp4\" Item:Semantic=\"MotionPhoto\" Item:Length=\"" + videoLength + "\" Item:Padding=\"0\"/>" +
                "</rdf:li>" +
                "</rdf:Seq>" +
                "</Container:Directory>" +
                "</rdf:Description>" +
                "</rdf:RDF>" +
                "</x:xmpmeta>" +
                "<?xpacket end=\"w\"?>";
    }

    private static Uri saveFileInternal(int type, File sourceFile, String filename) {
        return saveFileInternal(type, sourceFile, filename, () -> false);
    }

    private static Uri saveFileInternal(int type, File sourceFile, String filename, java.util.function.BooleanSupplier cancelled) {
        ContentResolver resolver = null;
        Uri dstUri = null;
        boolean saved = false;
        try {
            if (cancelled.getAsBoolean()) return null;
            resolver = ApplicationLoader.applicationContext.getContentResolver();
            int selectedType = type;
            ContentValues contentValues = new ContentValues();
            String extension = FileLoader.getFileExtension(sourceFile);
            String mimeType = null;
            if (extension != null) {
                mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            }
            Uri uriToInsert = null;
            if ((type == 0 || type == 1) && mimeType != null) {
                if (mimeType.startsWith("image")) {
                    selectedType = 0;
                }
                if (mimeType.startsWith("video")) {
                    selectedType = 1;
                }
            }
            if (selectedType == 0) {
                if (filename == null) {
                    filename = AndroidUtilities.generateFileName(0, extension);
                }
                uriToInsert = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                File dirDest = new File(Environment.DIRECTORY_PICTURES, "NimarkoGram");
                contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, dirDest + File.separator);
                contentValues.put(MediaStore.Images.Media.DISPLAY_NAME, filename);
                contentValues.put(MediaStore.Images.Media.MIME_TYPE, mimeType);
            } else if (selectedType == 1) {
                if (filename == null) {
                    filename = AndroidUtilities.generateFileName(1, extension);
                }
                File dirDest = new File(Environment.DIRECTORY_MOVIES, "NimarkoGram");
                contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, dirDest + File.separator);
                uriToInsert = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                contentValues.put(MediaStore.Video.Media.DISPLAY_NAME, filename);
            } else if (selectedType == 2) {
                if (filename == null) {
                    filename = sourceFile.getName();
                }
                File dirDest = new File(Environment.DIRECTORY_DOWNLOADS, "NimarkoGram");
                contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, dirDest + File.separator);
                uriToInsert = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                contentValues.put(MediaStore.Downloads.DISPLAY_NAME, filename);
            } else {
                if (filename == null) {
                    filename = sourceFile.getName();
                }
                File dirDest = new File(Environment.DIRECTORY_MUSIC, "NimarkoGram");
                contentValues.put(MediaStore.MediaColumns.RELATIVE_PATH, dirDest + File.separator);
                uriToInsert = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                contentValues.put(MediaStore.Audio.Media.DISPLAY_NAME, filename);
            }

            contentValues.put(MediaStore.MediaColumns.MIME_TYPE, mimeType);

            if (cancelled.getAsBoolean()) return null;
            dstUri = resolver.insert(uriToInsert, contentValues);
            if (dstUri == null) return null;
            try (FileInputStream input = new FileInputStream(sourceFile);
                 OutputStream output = resolver.openOutputStream(dstUri)) {
                if (output == null) return null;
                byte[] buffer = new byte[4096];
                int read;
                while ((read = input.read(buffer)) > 0) {
                    if (cancelled.getAsBoolean()) return null;
                    Thread.yield();
                    output.write(buffer, 0, read);
                }
            }
            if (cancelled.getAsBoolean()) return null;
            saved = true;
            return dstUri;
        } catch (Exception e) {
            if (!cancelled.getAsBoolean()) FileLog.e(e);
            return null;
        } finally {
            if (!saved && dstUri != null && resolver != null) {
                try {
                    resolver.delete(dstUri, null, null);
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
        }
    }

    public static String getStickerExt(Uri uri) {
        InputStream inputStream = null;
        try {
            try {
                inputStream = ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri);
            } catch (Exception e) {
                inputStream = null;
            }
            if (inputStream == null) {
                File file = new File(uri.getPath());
                if (file.exists()) {
                    inputStream = new FileInputStream(file);
                }
            }

            byte[] header = new byte[12];
            if (inputStream.read(header, 0, 12) == 12) {
                if (header[0] == (byte) 0x89 && header[1] == (byte) 0x50 && header[2] == (byte) 0x4E && header[3] == (byte) 0x47 && header[4] == (byte) 0x0D && header[5] == (byte) 0x0A && header[6] == (byte) 0x1A && header[7] == (byte) 0x0A) {
                    return "png";
                }
                if (header[0] == 0x1f && header[1] == (byte) 0x8b) {
                    return "tgs";
                }
                String str = new String(header);
                if (str != null) {
                    str = str.toLowerCase();
                    if (str.startsWith("riff") && str.endsWith("webp")) {
                        return "webp";
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            try {
                if (inputStream != null) {
                    inputStream.close();
                }
            } catch (Exception e2) {
                FileLog.e(e2);
            }
        }
        return null;
    }

    public static boolean isWebp(Uri uri) {
        InputStream inputStream = null;
        try {
            inputStream = ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri);
            byte[] header = new byte[12];
            if (inputStream.read(header, 0, 12) == 12) {
                String str = new String(header);
                str = str.toLowerCase();
                if (str.startsWith("riff") && str.endsWith("webp")) {
                    return true;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            try {
                if (inputStream != null) {
                    inputStream.close();
                }
            } catch (Exception e2) {
                FileLog.e(e2);
            }
        }
        return false;
    }

    public static boolean isGif(Uri uri) {
        InputStream inputStream = null;
        try {
            inputStream = ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri);
            byte[] header = new byte[3];
            if (inputStream.read(header, 0, 3) == 3) {
                String str = new String(header);
                if (str.equalsIgnoreCase("gif")) {
                    return true;
                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            try {
                if (inputStream != null) {
                    inputStream.close();
                }
            } catch (Exception e2) {
                FileLog.e(e2);
            }
        }
        return false;
    }

    public static String getFileName(Uri uri) {
        if (uri == null) {
            return "";
        }
        try {
            String result = null;
            if (uri.getScheme().equals("content")) {
                try (Cursor cursor = ApplicationLoader.applicationContext.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
                    if (cursor.moveToFirst()) {
                        result = cursor.getString(cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME));
                    }
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }
            if (result == null) {
                result = uri.getPath();
                int cut = result.lastIndexOf('/');
                if (cut != -1) {
                    result = result.substring(cut + 1);
                }
            }
            return result;
        } catch (Exception e) {
            FileLog.e(e);
        }
        return "";
    }

    public static File createFileInCache(String name, String ext) {
        File f = null;
        try {
            f = AndroidUtilities.getSharingDirectory();
            f.mkdirs();
            if (AndroidUtilities.isInternalUri(Uri.fromFile(f))) {
                return null;
            }
            int count = 0;
            do {
                f = AndroidUtilities.getSharingDirectory();
                if (count == 0) {
                    f = new File(f, name);
                } else {
                    int lastDotIndex = name.lastIndexOf(".");
                    if (lastDotIndex > 0) {
                        f = new File(f, name.substring(0, lastDotIndex) + " (" + count + ")" + name.substring(lastDotIndex));
                    } else {
                        f = new File(f, name + " (" + count + ")");
                    }
                }
                count++;
            } while (f.exists());
            return f;
        } catch (Exception e) {
            FileLog.e(e);
        }
        return null;
    }

    public static String copyFileToCache(Uri uri, String ext) {
        return copyFileToCache(uri, ext, -1);
    }

    @SuppressLint("DiscouragedPrivateApi")
    public static String copyFileToCache(Uri uri, String ext, long sizeLimit) {
        return copyFileToCache(uri, ext, sizeLimit, () -> false);
    }

    @SuppressLint("DiscouragedPrivateApi")
    public static String copyFileToCache(Uri uri, String ext, long sizeLimit, java.util.function.BooleanSupplier cancelled) {
        return copyFileToCache(uri, ext, sizeLimit, cancelled, null);
    }

    @SuppressLint("DiscouragedPrivateApi")
    public static String copyFileToCache(Uri uri, String ext, long sizeLimit, java.util.function.BooleanSupplier cancelled, Object retainedOwner) {
        InputStream inputStream = null;
        FileOutputStream output = null;
        long totalLen = 0;
        File f = null;
        boolean created = false;
        boolean complete = false;
        final Object leaseOwner = retainedOwner != null ? retainedOwner : new Object();
        String leasedPath = null;
        try {
            if (cancelled.getAsBoolean()) return null;
            String name = FileLoader.fixFileName(getFileName(uri));
            if (name == null) {
                int id = SharedConfig.getLastLocalId();
                SharedConfig.saveConfig();
                name = String.format(Locale.US, "%d.%s", id, ext);
            }
            f = AndroidUtilities.getSharingDirectory();
            f.mkdirs();
            if (AndroidUtilities.isInternalUri(Uri.fromFile(f))) {
                return null;
            }
            int count = 0;
            do {
                f = AndroidUtilities.getSharingDirectory();
                if (count == 0) {
                    f = new File(f, name);
                } else {
                    int lastDotIndex = name.lastIndexOf(".");
                    if (lastDotIndex > 0) {
                        f = new File(f, name.substring(0, lastDotIndex) + " (" + count + ")" + name.substring(lastDotIndex));
                    } else {
                        f = new File(f, name + " (" + count + ")");
                    }
                }
                count++;
                if (cancelled.getAsBoolean()) return null;
                final String candidate = f.getAbsolutePath();
                AutoDeleteMediaTask.lockFile(candidate, leaseOwner);
                try {
                    created = f.createNewFile();
                    if (created) leasedPath = candidate;
                } finally {
                    if (!created) AutoDeleteMediaTask.unlockFile(candidate, leaseOwner);
                }
            } while (!created);
            inputStream = ApplicationLoader.applicationContext.getContentResolver().openInputStream(uri);
            if (inputStream instanceof FileInputStream) {
                FileInputStream fileInputStream = (FileInputStream) inputStream;
                try {
                    Method getInt = FileDescriptor.class.getDeclaredMethod("getInt$");
                    int fdint = (Integer) getInt.invoke(fileInputStream.getFD());
                    if (AndroidUtilities.isInternalUri(fdint)) {
                        return null;
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }
            output = new FileOutputStream(f);
            byte[] buffer = new byte[1024 * 20];
            int len;
            while ((len = inputStream.read(buffer)) != -1) {
                if (cancelled.getAsBoolean()) return null;
                if (sizeLimit > 0 && len > sizeLimit - totalLen) return null;
                output.write(buffer, 0, len);
                totalLen += len;
            }
            output.close();
            output = null;
            if (cancelled.getAsBoolean()) return null;
            complete = true;
            return leasedPath;
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            try {
                if (inputStream != null) {
                    inputStream.close();
                }
            } catch (Exception e2) {
                FileLog.e(e2);
            }
            try {
                if (output != null) {
                    output.close();
                }
            } catch (Exception e2) {
                FileLog.e(e2);
            }
            try {
                if (created && !complete) f.delete();
            } finally {
                if (!complete || retainedOwner == null) AutoDeleteMediaTask.unlockFile(leasedPath, leaseOwner);
            }
        }
        return null;
    }

    public static void loadGalleryPhotosAlbums(final int guid) {
        Thread thread = new Thread(() -> {
            final ArrayList<AlbumEntry> mediaAlbumsSorted = new ArrayList<>();
            final ArrayList<AlbumEntry> photoAlbumsSorted = new ArrayList<>();
            SparseArray<AlbumEntry> mediaAlbums = new SparseArray<>();
            SparseArray<AlbumEntry> photoAlbums = new SparseArray<>();
            AlbumEntry allPhotosAlbum = null;
            AlbumEntry allVideosAlbum = null;
            AlbumEntry allMediaAlbum = null;
            String cameraFolder = null;
            try {
                cameraFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).getAbsolutePath() + "/" + "Camera/";
            } catch (Exception e) {
                FileLog.e(e);
            }
            Integer mediaCameraAlbumId = null;
            Integer photoCameraAlbumId = null;

            Cursor cursor = null;
            try {
                // NimarkoGram (CG parity): use PermissionsUtils.isImagesPermissionGranted —
                // matches CG's narrower per-content-type check.
                if (Build.VERSION.SDK_INT < 23 || app.nimarkogram.messenger.utils.PermissionsUtils.isImagesPermissionGranted()) {
                    cursor = MediaStore.Images.Media.query(ApplicationLoader.applicationContext.getContentResolver(), MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projectionPhotos, null, null, (Build.VERSION.SDK_INT > 28 ? MediaStore.Images.Media.DATE_MODIFIED : MediaStore.Images.Media.DATE_TAKEN) + " DESC");
                    if (cursor != null) {
                        int imageIdColumn = cursor.getColumnIndex(MediaStore.Images.Media._ID);
                        int bucketIdColumn = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_ID);
                        int bucketNameColumn = cursor.getColumnIndex(MediaStore.Images.Media.BUCKET_DISPLAY_NAME);
                        int dataColumn = cursor.getColumnIndex(MediaStore.Images.Media.DATA);
                        int dateColumn = cursor.getColumnIndex(Build.VERSION.SDK_INT > 28 ? MediaStore.Images.Media.DATE_MODIFIED : MediaStore.Images.Media.DATE_TAKEN);
                        int orientationColumn = cursor.getColumnIndex(MediaStore.Images.Media.ORIENTATION);
                        int widthColumn = cursor.getColumnIndex(MediaStore.Images.Media.WIDTH);
                        int heightColumn = cursor.getColumnIndex(MediaStore.Images.Media.HEIGHT);
                        int sizeColumn = cursor.getColumnIndex(MediaStore.Images.Media.SIZE);

                        while (cursor.moveToNext()) {
                            String path = cursor.getString(dataColumn);
                            if (TextUtils.isEmpty(path)) {
                                continue;
                            }

                            int imageId = cursor.getInt(imageIdColumn);
                            int bucketId = cursor.getInt(bucketIdColumn);
                            String bucketName = cursor.getString(bucketNameColumn);
                            long dateTaken = cursor.getLong(dateColumn);
                            int orientation = cursor.getInt(orientationColumn);
                            int width = cursor.getInt(widthColumn);
                            int height = cursor.getInt(heightColumn);
                            long size = cursor.getLong(sizeColumn);

                            PhotoEntry photoEntry = new PhotoEntry(bucketId, imageId, dateTaken, path, orientation, 0, false, width, height, size);

                            if (allPhotosAlbum == null) {
                                allPhotosAlbum = new AlbumEntry(0, LocaleController.getString(R.string.AllPhotos), photoEntry);
                                photoAlbumsSorted.add(0, allPhotosAlbum);
                            }
                            if (allMediaAlbum == null) {
                                allMediaAlbum = new AlbumEntry(0, LocaleController.getString(R.string.AllMedia), photoEntry);
                                mediaAlbumsSorted.add(0, allMediaAlbum);
                            }
                            if (allPhotosAlbum.photos.size() < 15) {
                                // preload XMP of first few photos
                                photoEntry.isLivePhoto();
                            }
                            allPhotosAlbum.addPhoto(photoEntry);
                            allMediaAlbum.addPhoto(photoEntry);

                            AlbumEntry albumEntry = mediaAlbums.get(bucketId);
                            if (albumEntry == null) {
                                albumEntry = new AlbumEntry(bucketId, bucketName, photoEntry);
                                mediaAlbums.put(bucketId, albumEntry);
                                if (mediaCameraAlbumId == null && cameraFolder != null && path != null && path.startsWith(cameraFolder)) {
                                    mediaAlbumsSorted.add(0, albumEntry);
                                    mediaCameraAlbumId = bucketId;
                                } else {
                                    mediaAlbumsSorted.add(albumEntry);
                                }
                            }
                            albumEntry.addPhoto(photoEntry);

                            albumEntry = photoAlbums.get(bucketId);
                            if (albumEntry == null) {
                                albumEntry = new AlbumEntry(bucketId, bucketName, photoEntry);
                                photoAlbums.put(bucketId, albumEntry);
                                if (photoCameraAlbumId == null && cameraFolder != null && path != null && path.startsWith(cameraFolder)) {
                                    photoAlbumsSorted.add(0, albumEntry);
                                    photoCameraAlbumId = bucketId;
                                } else {
                                    photoAlbumsSorted.add(albumEntry);
                                }
                            }
                            albumEntry.addPhoto(photoEntry);
                        }
                    }
                }
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                if (cursor != null) {
                    try {
                        cursor.close();
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                }
            }

            try {
                // NimarkoGram (CG parity): use PermissionsUtils.isVideoPermissionGranted.
                if (Build.VERSION.SDK_INT < 23 || app.nimarkogram.messenger.utils.PermissionsUtils.isVideoPermissionGranted()) {
                    cursor = MediaStore.Images.Media.query(ApplicationLoader.applicationContext.getContentResolver(), MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projectionVideo, null, null, (Build.VERSION.SDK_INT > 28 ? MediaStore.Video.Media.DATE_MODIFIED : MediaStore.Video.Media.DATE_TAKEN) + " DESC");
                    if (cursor != null) {
                        int imageIdColumn = cursor.getColumnIndex(MediaStore.Video.Media._ID);
                        int bucketIdColumn = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_ID);
                        int bucketNameColumn = cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME);
                        int dataColumn = cursor.getColumnIndex(MediaStore.Video.Media.DATA);
                        int dateColumn = cursor.getColumnIndex(Build.VERSION.SDK_INT > 28 ? MediaStore.Video.Media.DATE_MODIFIED : MediaStore.Video.Media.DATE_TAKEN);
                        int durationColumn = cursor.getColumnIndex(MediaStore.Video.Media.DURATION);
                        int widthColumn = cursor.getColumnIndex(MediaStore.Video.Media.WIDTH);
                        int heightColumn = cursor.getColumnIndex(MediaStore.Video.Media.HEIGHT);
                        int sizeColumn = cursor.getColumnIndex(MediaStore.Video.Media.SIZE);
                        int orientationColumn = cursor.getColumnIndex(MediaStore.Video.Media.ORIENTATION);

                        while (cursor.moveToNext()) {
                            String path = cursor.getString(dataColumn);
                            if (TextUtils.isEmpty(path)) {
                                continue;
                            }

                            int imageId = cursor.getInt(imageIdColumn);
                            int bucketId = cursor.getInt(bucketIdColumn);
                            String bucketName = cursor.getString(bucketNameColumn);
                            long dateTaken = cursor.getLong(dateColumn);
                            long duration = cursor.getLong(durationColumn);
                            int width = cursor.getInt(widthColumn);
                            int height = cursor.getInt(heightColumn);
                            long size = cursor.getLong(sizeColumn);
                            int orientation = 0;

                            PhotoEntry photoEntry = new PhotoEntry(bucketId, imageId, dateTaken, path, orientation, (int) (duration / 1000), true, width, height, size);

                            if (allVideosAlbum == null) {
                                allVideosAlbum = new AlbumEntry(0, LocaleController.getString(R.string.AllVideos), photoEntry);
                                allVideosAlbum.videoOnly = true;
                                int index = 0;
                                if (allMediaAlbum != null) {
                                    index++;
                                }
                                if (allPhotosAlbum != null) {
                                    index++;
                                }
                                mediaAlbumsSorted.add(index, allVideosAlbum);
                            }
                            if (allMediaAlbum == null) {
                                allMediaAlbum = new AlbumEntry(0, LocaleController.getString(R.string.AllMedia), photoEntry);
                                mediaAlbumsSorted.add(0, allMediaAlbum);
                            }
                            allVideosAlbum.addPhoto(photoEntry);
                            allMediaAlbum.addPhoto(photoEntry);

                            AlbumEntry albumEntry = mediaAlbums.get(bucketId);
                            if (albumEntry == null) {
                                albumEntry = new AlbumEntry(bucketId, bucketName, photoEntry);
                                mediaAlbums.put(bucketId, albumEntry);
                                if (mediaCameraAlbumId == null && cameraFolder != null && path != null && path.startsWith(cameraFolder)) {
                                    mediaAlbumsSorted.add(0, albumEntry);
                                    mediaCameraAlbumId = bucketId;
                                } else {
                                    mediaAlbumsSorted.add(albumEntry);
                                }
                            }

                            albumEntry.addPhoto(photoEntry);
                        }
                    }
                }
            } catch (Throwable e) {
                FileLog.e(e);
            } finally {
                if (cursor != null) {
                    try {
                        cursor.close();
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                }
            }
            for (int a = 0; a < mediaAlbumsSorted.size(); a++) {
                Collections.sort(mediaAlbumsSorted.get(a).photos, (o1, o2) -> {
                    if (o1.dateTaken < o2.dateTaken) {
                        return 1;
                    } else if (o1.dateTaken > o2.dateTaken) {
                        return -1;
                    }
                    return 0;
                });
            }
            broadcastNewPhotos(guid, mediaAlbumsSorted, photoAlbumsSorted, mediaCameraAlbumId, allMediaAlbum, allPhotosAlbum, allVideosAlbum, 0);
        });
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    public static boolean forceBroadcastNewPhotos;
    private static void broadcastNewPhotos(final int guid, final ArrayList<AlbumEntry> mediaAlbumsSorted, final ArrayList<AlbumEntry> photoAlbumsSorted, final Integer cameraAlbumIdFinal, final AlbumEntry allMediaAlbumFinal, final AlbumEntry allPhotosAlbumFinal, final AlbumEntry allVideosAlbumFinal, int delay) {
        if (broadcastPhotosRunnable != null) {
            AndroidUtilities.cancelRunOnUIThread(broadcastPhotosRunnable);
        }
        AndroidUtilities.runOnUIThread(broadcastPhotosRunnable = () -> {
            if (PhotoViewer.getInstance().isVisible() && !forceBroadcastNewPhotos) {
                broadcastNewPhotos(guid, mediaAlbumsSorted, photoAlbumsSorted, cameraAlbumIdFinal, allMediaAlbumFinal, allPhotosAlbumFinal, allVideosAlbumFinal, 1000);
                return;
            }
            allMediaAlbums = mediaAlbumsSorted;
            allPhotoAlbums = photoAlbumsSorted;
            broadcastPhotosRunnable = null;
            allPhotosAlbumEntry = allPhotosAlbumFinal;
            allMediaAlbumEntry = allMediaAlbumFinal;
            allVideosAlbumEntry = allVideosAlbumFinal;
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.albumsDidLoad, guid, mediaAlbumsSorted, photoAlbumsSorted, cameraAlbumIdFinal);
        }, delay);
    }

    public interface VideoConvertCallback {
        void onPreparingStarted(VideoConvertSubscription subscription);
        void onChunkAvailable(VideoConvertSubscription subscription, long availableSize, long finalSize, float progress);
        void onFailed(VideoConvertSubscription subscription);
    }

    public final class VideoConvertSubscription {
        private final VideoConvertMessage operation;
        private final VideoConvertCallback callback;
        private boolean cancelled, finished, prepared;
        private long revision;

        private VideoConvertSubscription(VideoConvertMessage operation, VideoConvertCallback callback) {
            this.operation = operation;
            this.callback = callback;
        }

        private boolean isCurrent() {
            return !cancelled && !finished && operation.isCurrentAccount()
                    && videoConversionLatestOwners.get(operation.sourceMessage) == operation.owner
                    && operation.sourceMessage.currentAccount == operation.sourceAccount
                    && operation.sourceMessage.messageOwner == operation.sourceMessageOwner
                    && operation.sourceMessage.videoEditedInfo == operation.messageVideoEditedInfo
                    && TextUtils.equals(operation.sourceMessageOwner.attachPath, operation.outputPath)
                    && (operation.consumerTerminal || !operation.canceled && !operation.retired);
        }

        private void deliver() {
            if (!isCurrent()) return;
            if (operation.consumerPrepared && !prepared && !operation.consumerFailed) {
                prepared = true;
                callback.onPreparingStarted(this);
                if (!isCurrent()) return;
            }
            if (operation.consumerRevision == revision) return;
            revision = operation.consumerRevision;
            if (operation.consumerTerminal) {
                finished = true;
                operation.consumers.remove(this);
            }
            if (operation.consumerFailed) callback.onFailed(this);
            else callback.onChunkAvailable(this, operation.consumerAvailable, operation.consumerFinal, operation.consumerProgress);
        }

        public void cancel() {
            if (cancelled) return;
            cancelled = true;
            operation.consumers.remove(this);
            if (!finished && !operation.consumerTerminal && !operation.legacyConsumer && operation.consumers.isEmpty()) {
                cancelVideoConvertOperation(operation);
            }
        }
    }

    public VideoConvertSubscription subscribeVideoConvert(MessageObject object, boolean scheduleIfMissing, VideoConvertCallback callback) {
        if (object == null || object.messageOwner == null || object.messageOwner.attachPath == null || object.videoEditedInfo == null || callback == null) return null;
        WeakReference<VideoConvertMessage> reference = videoConversionRecords.get(object);
        VideoConvertMessage operation = reference == null ? null : reference.get();
        if (operation != null && (operation.canceled || operation.consumerFailed || !operation.isCurrentAccount()
                || object.currentAccount != operation.sourceAccount || videoConversionLatestOwners.get(object) != operation.owner || object.messageOwner != operation.sourceMessageOwner
                || object.videoEditedInfo != operation.messageVideoEditedInfo || !TextUtils.equals(object.messageOwner.attachPath, operation.outputPath))) {
            operation = null;
        }
        final boolean enqueue = operation == null;
        if (enqueue) {
            if (!scheduleIfMissing) return null;
            operation = new VideoConvertMessage(object, object.videoEditedInfo, true, false);
            if (!operation.isCurrentAccount()) return null;
            operation.legacyConsumer = false;
        }
        VideoConvertSubscription subscription = new VideoConvertSubscription(operation, callback);
        operation.consumers.add(subscription);
        if (enqueue) enqueueVideoConvert(operation, false);
        AndroidUtilities.runOnUIThread(subscription::deliver);
        return subscription;
    }

    private void publishVideoConvertData(VideoConvertMessage operation, long available, long finalSize, float progress, boolean terminal, boolean failed) {
        operation.consumerPrepared |= !failed;
        operation.consumerAvailable = available;
        operation.consumerFinal = finalSize;
        operation.consumerProgress = progress;
        operation.consumerTerminal = terminal;
        operation.consumerFailed = failed;
        operation.consumerRevision++;
        for (VideoConvertSubscription subscription : new ArrayList<>(operation.consumers)) {
            try {
                subscription.deliver();
            } catch (Exception failure) {
                FileLog.e(failure);
            }
        }
    }
    public void scheduleVideoConvert(MessageObject messageObject) {
        scheduleVideoConvert(messageObject, false, true, false);
    }

    public void scheduleVideoConvert(MessageObject messageObject, VideoEditedInfo videoEditedInfo) {
        scheduleVideoConvert(messageObject, videoEditedInfo, false, true, false);
    }

    public boolean scheduleVideoConvert(MessageObject messageObject, boolean isEmpty, boolean withForeground, boolean forConversion) {
        return scheduleVideoConvert(messageObject, messageObject != null ? messageObject.videoEditedInfo : null, isEmpty, withForeground, forConversion);
    }

    public boolean scheduleVideoConvert(MessageObject messageObject, VideoEditedInfo videoEditedInfo, boolean isEmpty, boolean withForeground, boolean forConversion) {
        return scheduleVideoConvert(messageObject, videoEditedInfo, isEmpty, withForeground, forConversion, null);
    }

    public boolean scheduleVideoConvert(MessageObject messageObject, VideoEditedInfo videoEditedInfo, boolean isEmpty, boolean withForeground, boolean forConversion, Runnable onPhysicalFinished) {
        if (messageObject == null || messageObject.messageOwner == null || messageObject.messageOwner.attachPath == null || videoEditedInfo == null) {
            return false;
        }
        if (isEmpty && (activeConversion != null || !videoConvertQueue.isEmpty())) {
            return false;
        }
        VideoConvertMessage videoConvertMessage = new VideoConvertMessage(messageObject, videoEditedInfo, withForeground, forConversion);
        if (!videoConvertMessage.isCurrentAccount()) {
            return false;
        }
        videoConvertMessage.onPhysicalFinished = onPhysicalFinished;
        enqueueVideoConvert(videoConvertMessage, isEmpty);
        return true;
    }

    private void enqueueVideoConvert(VideoConvertMessage videoConvertMessage, boolean isEmpty) {
        MessageObject messageObject = videoConvertMessage.sourceMessage;
        videoConversionOwners.put(messageObject, videoConvertMessage.owner);
        videoConversionLatestOwners.put(messageObject, videoConvertMessage.owner);
        videoConversionRecords.put(messageObject, new WeakReference<>(videoConvertMessage));
        videoConvertQueue.add(videoConvertMessage);
        if (isEmpty) {
            new File(videoConvertMessage.outputPath).delete();
        }
        if (videoConvertMessage.foreground) {
            foregroundConversionOwners.put(videoConvertMessage, Boolean.TRUE);
            foregroundConvertingMessages.add(videoConvertMessage);
            checkForegroundConvertMessage(null, false);
        }
            startVideoConvertFromQueue();
        }

    public void cancelVideoConvert(MessageObject messageObject) {
        if (messageObject == null) {
            return;
        }
                for (int a = 0; a < videoConvertQueue.size(); a++) {
            VideoConvertMessage message = videoConvertQueue.get(a);
            if (message.sourceMessage == messageObject && message.sourceAccount == messageObject.currentAccount
                    && videoConversionOwners.get(messageObject) == message.owner) {
                cancelVideoConvertOperation(message);
                        break;
                    }
                }
            }
    private void cancelVideoConvertOperation(VideoConvertMessage message) {
        if (message.canceled || message.terminalDispatched) return;
        videoConvertQueue.remove(message);
        message.canceled = true;
        message.retired = true;
        if (message != activeConversion) {
            message.terminalDelivered = true;
            foregroundConvertingMessages.remove(message);
            updateForegroundConvertMessage();
            AndroidUtilities.runOnUIThread(() -> dispatchVideoConversionResult(message, VideoConvertResult.failed(), false));
        }
    }

    private ArrayList<VideoConvertMessage> retireVideoConversions(int account) {
        ArrayList<VideoConvertMessage> retired = new ArrayList<>();
        for (int a = videoConvertQueue.size() - 1; a >= 0; a--) {
            VideoConvertMessage message = videoConvertQueue.get(a);
            if (account < 0 || message.sourceAccount == account) {
                videoConvertQueue.remove(a);
                message.canceled = true;
                message.retired = true;
                message.terminalDelivered = message != activeConversion;
                foregroundConvertingMessages.remove(message);
                retired.add(0, message);
            }
        }
        VideoConvertMessage active = activeConversion;
        if (active != null && (account < 0 || active.sourceAccount == account) && !retired.contains(active)) {
            active.canceled = true;
            active.retired = true;
            foregroundConvertingMessages.remove(active);
            retired.add(0, active);
        }
        for (VideoConvertMessage message : new ArrayList<>(foregroundConversionOwners.keySet())) {
            if ((account < 0 || message.sourceAccount == account) && !retired.contains(message)) {
                message.canceled = true;
                message.retired = true;
                message.terminalDelivered = true;
                foregroundConvertingMessages.remove(message);
                retired.add(message);
            }
        }
        updateForegroundConvertMessage();
        return retired;
    }

    private void updateForegroundConvertMessage() {
        if (!foregroundConvertingMessages.isEmpty()) {
            currentForegroundConvertingVideo = foregroundConvertingMessages.get(0);
        } else {
            currentForegroundConvertingVideo = null;
        }
    }

    private void checkForegroundConvertMessage(VideoConvertMessage finished, boolean cancelled) {
        updateForegroundConvertMessage();
        boolean notifyFinished = finished != null && !finished.foregroundRetired && (cancelled || finished.foregroundConversion);
        if (notifyFinished) {
            finished.foregroundRetired = true;
            foregroundConversionOwners.remove(finished);
        }
        if (currentForegroundConvertingVideo != null) {
            VideoEncodingService.start(false);
        }
        if (notifyFinished) {
            VideoEncodingService.onConversionFinished(finished);
        }
    }

    private boolean startVideoConvertFromQueue() {
        if (startingVideoConversion || activeConversion != null) {
            return false;
        }
        startingVideoConversion = true;
        try {
            while (activeConversion == null && !videoConvertQueue.isEmpty()) {
                VideoConvertMessage message = videoConvertQueue.get(0);
                if (message.canceled || message.retired || !isCurrentVideoConversion(message)) {
                    videoConvertQueue.remove(0);
                    message.canceled = true;
                    message.retired = true;
                    message.terminalDelivered = true;
                    foregroundConvertingMessages.remove(message);
                    dispatchVideoConversionResult(message, VideoConvertResult.failed(), false);
                    continue;
                }
                activeConversion = message;
                VideoConvertRunnable.runConversion(this, message);
            return true;
        }
        return false;
        } finally {
            startingVideoConversion = false;
        }
    }

    private boolean isCurrentVideoConversion(VideoConvertMessage message) {
        return videoConversionOwners.get(message.sourceMessage) == message.owner && message.isCurrentAccount()
                && message.sourceMessage.currentAccount == message.sourceAccount
                && message.sourceMessage.messageOwner == message.sourceMessageOwner
                && message.sourceMessage.videoEditedInfo == message.messageVideoEditedInfo
                && TextUtils.equals(message.sourceMessageOwner.attachPath, message.outputPath);
    }

    @SuppressLint("NewApi")
    public static MediaCodecInfo selectCodec(String mimeType) {
        int numCodecs = MediaCodecList.getCodecCount();
        MediaCodecInfo lastCodecInfo = null;
        for (int i = 0; i < numCodecs; i++) {
            MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);
            if (!codecInfo.isEncoder()) {
                continue;
            }
            String[] types = codecInfo.getSupportedTypes();
            for (String type : types) {
                if (type.equalsIgnoreCase(mimeType)) {
                    lastCodecInfo = codecInfo;
                    String name = lastCodecInfo.getName();
                    if (name != null) {
                        if (!name.equals("OMX.SEC.avc.enc")) {
                            return lastCodecInfo;
                        } else if (name.equals("OMX.SEC.AVC.Encoder")) {
                            return lastCodecInfo;
                        }
                    }
                }
            }
        }
        return lastCodecInfo;
    }

    private static boolean isRecognizedFormat(int colorFormat) {
        switch (colorFormat) {
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar:
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedPlanar:
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar:
            case MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420PackedSemiPlanar:
            case MediaCodecInfo.CodecCapabilities.COLOR_TI_FormatYUV420PackedSemiPlanar:
                return true;
            default:
                return false;
        }
    }

    @SuppressLint("NewApi")
    public static int selectColorFormat(MediaCodecInfo codecInfo, String mimeType) {
        MediaCodecInfo.CodecCapabilities capabilities = codecInfo.getCapabilitiesForType(mimeType);
        int lastColorFormat = 0;
        for (int i = 0; i < capabilities.colorFormats.length; i++) {
            int colorFormat = capabilities.colorFormats[i];
            if (isRecognizedFormat(colorFormat)) {
                lastColorFormat = colorFormat;
                if (!(codecInfo.getName().equals("OMX.SEC.AVC.Encoder") && colorFormat == 19)) {
                    return colorFormat;
                }
            }
        }
        return lastColorFormat;
    }

    public static int findTrack(MediaExtractor extractor, boolean audio) {
        int numTracks = extractor.getTrackCount();
        for (int i = 0; i < numTracks; i++) {
            MediaFormat format = extractor.getTrackFormat(i);
            String mime = format.getString(MediaFormat.KEY_MIME);
            if (audio) {
                if (mime.startsWith("audio/")) {
                    return i;
                }
            } else {
                if (mime.startsWith("video/")) {
                    return i;
                }
            }
        }
        return -5;
    }

    public static boolean isH264Video(String videoPath) {
        MediaExtractor extractor = new MediaExtractor();
        try {
            extractor.setDataSource(videoPath);
            int videoIndex = MediaController.findTrack(extractor, false);
            return videoIndex >= 0 && extractor.getTrackFormat(videoIndex).getString(MediaFormat.KEY_MIME).equals(MediaController.VIDEO_MIME_TYPE);
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            extractor.release();
        }
        return false;
    }

    private void didWriteData(final VideoConvertMessage message, long availableSize, final float progress) {
        final boolean firstWrite = message.firstWrite.getAndSet(false);
        AndroidUtilities.runOnUIThread(() -> {
            if (activeConversion != message || message.canceled || message.retired || message.terminalDelivered || !isCurrentVideoConversion(message)) {
                return;
            }
        if (firstWrite) {
                NotificationCenter.getInstance(message.sourceAccount).postNotificationName(NotificationCenter.filePreparingStarted, message.sourceMessage, message.outputPath, progress, 0L);
                if (activeConversion != message || message.canceled || message.retired || !isCurrentVideoConversion(message)) {
                    return;
                }
            }
            NotificationCenter.getInstance(message.sourceAccount).postNotificationName(NotificationCenter.fileNewChunkAvailable, message.sourceMessage, message.outputPath, availableSize, 0L, progress, 0L);
            if (activeConversion == message && !message.canceled && !message.retired && isCurrentVideoConversion(message)) {
                publishVideoConvertData(message, availableSize, 0, progress, false, false);
            }
        });
    }

    private void onVideoConversionFinished(VideoConvertMessage message, VideoConvertResult result) {
        final boolean firstWrite = message.firstWrite.getAndSet(false);
        AndroidUtilities.runOnUIThread(() -> {
            if (activeConversion != message || message.terminalDelivered) {
                return;
            }
            activeConversion = null;
                videoConvertQueue.remove(message);
                foregroundConvertingMessages.remove(message);
            message.retired = true;
            message.terminalDelivered = true;
            updateForegroundConvertMessage();
            dispatchVideoConversionResult(message, result, firstWrite);
        });
    }

    private void dispatchVideoConversionResult(VideoConvertMessage message, VideoConvertResult result, boolean firstWrite) {
        if (message.terminalDispatched) {
            return;
        }
        message.terminalDispatched = true;
        try {
            Runnable onPhysicalFinished = message.onPhysicalFinished;
            message.onPhysicalFinished = null;
            if (onPhysicalFinished != null) {
                try {
                    onPhysicalFinished.run();
                } catch (Exception failure) {
                    FileLog.e(failure);
                }
            }
            boolean error = result.error || message.canceled || !isCurrentVideoConversion(message);
            checkForegroundConvertMessage(message, error);
            if (!isCurrentVideoConversion(message)) {
                return;
            }
            if (error) {
                NotificationCenter.getInstance(message.sourceAccount).postNotificationName(NotificationCenter.filePreparingFailed, message.sourceMessage, message.outputPath, 1f, result.lastFrameTimestamp);
                if (isCurrentVideoConversion(message)) publishVideoConvertData(message, 0, 0, 1f, true, true);
            } else {
                if (firstWrite) {
                    NotificationCenter.getInstance(message.sourceAccount).postNotificationName(NotificationCenter.filePreparingStarted, message.sourceMessage, message.outputPath, 1f, result.lastFrameTimestamp);
                    if (!isCurrentVideoConversion(message)) {
                        return;
                    }
                }
                NotificationCenter.getInstance(message.sourceAccount).postNotificationName(NotificationCenter.fileNewChunkAvailable, message.sourceMessage, message.outputPath, result.size, result.size, 1f, result.lastFrameTimestamp);
                if (isCurrentVideoConversion(message)) publishVideoConvertData(message, result.size, result.size, 1f, true, false);
            }
        } finally {
            if (videoConversionOwners.get(message.sourceMessage) == message.owner) {
                videoConversionOwners.remove(message.sourceMessage);
            }
                startVideoConvertFromQueue();
            }
    }

    private static class VideoConvertResult {
        final boolean error;
        final long lastFrameTimestamp;
        final long size;

        VideoConvertResult(boolean error, long lastFrameTimestamp, long size) {
            this.error = error;
            this.lastFrameTimestamp = lastFrameTimestamp;
            this.size = size;
        }

        static VideoConvertResult failed() {
            return new VideoConvertResult(true, 0, 0);
        }
    }

    public void pauseByRewind() {
        if (audioPlayer != null) {
            audioPlayer.pause();
        }
    }

    public void resumeByRewind() {
        if (audioPlayer != null && playingMessageObject != null && !isPaused && !playbackIntentPaused) {
            if (audioPlayer.isBuffering()) {
                MessageObject currentMessageObject = playingMessageObject;
                long generation = musicReturnIntentGeneration.get();
                long intent = playbackIntentGeneration.get();
                SavedMusicPlaylistState state = savedMusicPlaylistState;
                SavedMusicPlaylistState continuation = ownsRoundPlayback(state, currentMessageObject, playerNum) ? state : null;
                cleanupPlayer(false, false, false, false, -1, null, true);
                if (generation == musicReturnIntentGeneration.get() && intent == playbackIntentGeneration.get()
                        && currentPlaybackIntent(currentMessageObject)) {
                    playMessage(currentMessageObject, isSilent, continuation, false);
                }
                if (playingMessageObject == null) retirePlaybackIntent(currentMessageObject, intent);
            } else {
                final VideoPlayer current = audioPlayer;
                final MessageObject message = playingMessageObject;
                final long intent = playbackIntentGeneration.get();
                if (checkAudioFocus(message) && audioPlayer == current && playingMessageObject == message
                        && intent == playbackIntentGeneration.get()) current.play();
            }
        }
    }

    private static class VideoConvertRunnable implements Runnable {

        private final MediaController controller;
        private final VideoConvertMessage convertMessage;
        private VideoConvertResult result;

        private VideoConvertRunnable(MediaController controller, VideoConvertMessage message) {
            this.controller = controller;
            convertMessage = message;
        }

        @Override
        public void run() {
            try {
                result = controller.convertVideo(convertMessage);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        }

        public static void runConversion(final MediaController controller, final VideoConvertMessage obj) {
            try {
            new Thread(() -> {
                    VideoConvertRunnable wrapper = null;
                    Thread worker = null;
                    boolean interrupted = false;
                    try {
                        wrapper = new VideoConvertRunnable(controller, obj);
                        worker = new Thread(wrapper, "VideoConvertRunnable");
                        worker.start();
                    } catch (Throwable e) {
                        FileLog.e(e);
                    } finally {
                        if (worker != null) {
                            for (;;) {
                                try {
                                    worker.join();
                                    break;
                                } catch (InterruptedException e) {
                                    interrupted = true;
                                }
                            }
                        }
                        VideoConvertResult result = wrapper == null || wrapper.result == null ? VideoConvertResult.failed() : wrapper.result;
                        controller.onVideoConversionFinished(obj, result);
                        if (interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }, "VideoConvertWatcher").start();
            } catch (Throwable e) {
                FileLog.e(e);
                controller.onVideoConversionFinished(obj, VideoConvertResult.failed());
            }
        }
    }

    private VideoConvertResult convertVideo(final VideoConvertMessage convertMessage) {
        VideoEditedInfo info = convertMessage.sourceVideoEditedInfo;
        if (convertMessage.canceled || !convertMessage.isCurrentAccount() || info == null) {
            return VideoConvertResult.failed();
        }
        String videoPath = info.originalPath;
        long videoOffset = info.videoOffset;
        long startTime = info.startTime;
        long avatarStartTime = info.avatarStartTime;
        long endTime = info.endTime;
        int resultWidth = info.resultWidth;
        int resultHeight = info.resultHeight;
        int rotationValue = info.rotationValue;
        int originalWidth = info.originalWidth;
        int originalHeight = info.originalHeight;
        int framerate = info.framerate;
        int bitrate = info.bitrate;
        int originalBitrate = info.originalBitrate;
        boolean isSecret = DialogObject.isEncryptedDialog(convertMessage.dialogId) || info.forceFragmenting;
        final File cacheFile = new File(convertMessage.outputPath);
        if (cacheFile.exists()) {
            cacheFile.delete();
        }
        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("begin convert " + videoPath + " startTime = " + startTime + " avatarStartTime = " + avatarStartTime + " endTime " + endTime + " rWidth = " + resultWidth + " rHeight = " + resultHeight + " rotation = " + rotationValue + " oWidth = " + originalWidth + " oHeight = " + originalHeight + " framerate = " + framerate + " bitrate = " + bitrate + " originalBitrate = " + originalBitrate);
        }

        if (videoPath == null) {
            videoPath = "";
        }

        long duration;
        if (startTime > 0 && endTime > 0) {
            duration = endTime - startTime;
        } else if (endTime > 0) {
            duration = endTime;
        } else if (startTime > 0) {
            duration = info.originalDuration - startTime;
        } else {
            duration = info.originalDuration;
        }

        if (framerate == 0) {
            framerate = 25;
        } else if (framerate > 59) {
            framerate = 59;
        }

        if (rotationValue == 90 || rotationValue == 270) {
            int temp = resultHeight;
            resultHeight = resultWidth;
            resultWidth = temp;
        }

        if (!info.shouldLimitFps && framerate > 40 && (Math.min(resultHeight, resultWidth) <= 480)) {
            framerate = 30;
        }

        boolean needCompress = avatarStartTime != -1 || info.cropState != null || info.mediaEntities != null || info.paintPath != null || info.filterState != null ||
                resultWidth != originalWidth || resultHeight != originalHeight || rotationValue != 0 || info.roundVideo || startTime != -1 || !info.mixedSoundInfos.isEmpty();

        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("videoconvert", Activity.MODE_PRIVATE);

        long time = System.currentTimeMillis();

        VideoConvertorListener callback = new VideoConvertorListener() {

            private long lastAvailableSize = 0;

            @Override
            public boolean checkConversionCanceled() {
                return convertMessage.canceled || !convertMessage.isCurrentAccount();
            }

            @Override
            public void didWriteData(long availableSize, float progress) {
                if (checkConversionCanceled()) {
                    return;
                }
                if (availableSize < 0) {
                    availableSize = cacheFile.length();
                }

                if (!info.needUpdateProgress && lastAvailableSize == availableSize) {
                    return;
                }

                lastAvailableSize = availableSize;
                MediaController.this.didWriteData(convertMessage, availableSize, progress);
            }
        };


        MediaCodecVideoConvertor videoConvertor = new MediaCodecVideoConvertor();
        MediaCodecVideoConvertor.ConvertVideoParams convertVideoParams = MediaCodecVideoConvertor.ConvertVideoParams.of(
                videoPath, cacheFile, videoOffset,
                rotationValue, isSecret,
                originalWidth, originalHeight,
                resultWidth, resultHeight,
                framerate, bitrate, originalBitrate,
                startTime, endTime, avatarStartTime,
                needCompress, duration,
                callback,
                info
        );
        convertVideoParams.soundInfos.addAll(info.mixedSoundInfos);
        boolean error = videoConvertor.convertVideo(convertVideoParams);

        boolean canceled = callback.checkConversionCanceled();

        if (BuildVars.LOGS_ENABLED) {
            FileLog.d("time=" + (System.currentTimeMillis() - time) + " canceled=" + canceled);
        }

        preferences.edit().putBoolean("isPreviousOk", true).apply();
        return new VideoConvertResult(error || canceled, videoConvertor.getLastFrameTimestamp(), cacheFile.length());
    }

    public static int getVideoBitrate(String path) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        int bitrate = 0;
        try {
            retriever.setDataSource(path);
            bitrate = Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE));
        } catch (Exception e) {
            FileLog.e(e);
        }

        try {
            retriever.release();
        } catch (Throwable throwable) {
            FileLog.e(throwable);
        }
        return bitrate;
    }

    public static int makeVideoBitrate(int originalHeight, int originalWidth, int originalBitrate, int height, int width) {
        float compressFactor;
        float minCompressFactor;
        int maxBitrate;
        if (Math.min(height, width) >= 1080) {
            maxBitrate = 6800_000;
            compressFactor = 1f;
            minCompressFactor = 1f;
        } else if (Math.min(height, width) >= 720) {
            maxBitrate = 2600_000;
            compressFactor = 1f;
            minCompressFactor = 1f;
        } else if (Math.min(height, width) >= 480) {
            maxBitrate = 1000_000;
            compressFactor = 0.75f;
            minCompressFactor = 0.9f;
        } else {
            maxBitrate = 750_000;
            compressFactor = 0.6f;
            minCompressFactor = 0.7f;
        }
        int remeasuredBitrate = (int) (originalBitrate / (Math.min(originalHeight / (float) (height), originalWidth / (float) (width))));
        remeasuredBitrate *= compressFactor;
        int minBitrate = (int) (getVideoBitrateWithFactor(minCompressFactor) / (1280f * 720f / (width * height)));
        if (originalBitrate < minBitrate) {
            return remeasuredBitrate;
        }
        if (remeasuredBitrate > maxBitrate) {
            return maxBitrate;
        }
        return Math.max(remeasuredBitrate, minBitrate);
    }

    /**
     * Some encoders(e.g. OMX.Exynos) can forcibly raise bitrate during encoder initialization.
     */
    public static int extractRealEncoderBitrate(int width, int height, int bitrate, boolean tryHevc) {
        String cacheKey = width + "" + height + "" + bitrate;
        Integer cachedBitrate = cachedEncoderBitrates.get(cacheKey);
        if (cachedBitrate != null) return cachedBitrate;
        try {
            MediaCodec encoder = null;
            if (tryHevc) {
                try {
                    encoder = MediaCodec.createEncoderByType("video/hevc");
                } catch (Exception ignore) {}
            }
            if (encoder == null) {
                encoder = MediaCodec.createEncoderByType(MediaController.VIDEO_MIME_TYPE);
            }
            MediaFormat outputFormat = MediaFormat.createVideoFormat(MediaController.VIDEO_MIME_TYPE, width, height);
            outputFormat.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            outputFormat.setInteger("max-bitrate", bitrate);
            outputFormat.setInteger(MediaFormat.KEY_BIT_RATE, bitrate);
            outputFormat.setInteger(MediaFormat.KEY_FRAME_RATE, 30);
            outputFormat.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
            encoder.configure(outputFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            int encoderBitrate = (int) (encoder.getOutputFormat().getInteger(MediaFormat.KEY_BIT_RATE));
            cachedEncoderBitrates.put(cacheKey, encoderBitrate);
            encoder.release();
            return encoderBitrate;
        } catch (Exception e) {
            return bitrate;
        }
    }

    private static int getVideoBitrateWithFactor(float f) {
        return (int) (f * 2000f * 1000f * 1.13f);
    }

    public interface VideoConvertorListener {
        boolean checkConversionCanceled();

        void didWriteData(long availableSize, float progress);
    }

    public static class PlaylistGlobalSearchParams {
        final String query;
        final FiltersView.MediaFilterData filter;
        final TLRPC.MessagesFilter messagesFilter;
        final long dialogId;
        public long topicId;
        final long minDate;
        final long maxDate;
        public int totalCount;
        public boolean endReached;
        public int nextSearchRate;
        public int folderId;

        public long communityId;
        public TLRPC.InputChannel communityPeer;
        private int sourceAccount = -1;
        private long sourceUserId;
        private int sourceLoginTime;
        private int offsetId;
        private long offsetPeerId;
        private boolean cursorCaptured;
        private Object sourceIdentity = new Object();
        private final HashSet<String> deliveredCursors = new HashSet<>();
        public ReactionsLayoutInBubble.VisibleReaction reaction;

        public PlaylistGlobalSearchParams(String query, long dialogId, long minDate, long maxDate, FiltersView.MediaFilterData filter) {
            this.filter = filter;
            messagesFilter = filter == null ? null : filter.filter;
            this.query = query;
            this.dialogId = dialogId;
            this.minDate = minDate;
            this.maxDate = maxDate;
        }
        private PlaylistGlobalSearchParams(PlaylistGlobalSearchParams source) {
            filter = source.filter;
            messagesFilter = source.messagesFilter;
            query = source.query;
            dialogId = source.dialogId;
            topicId = source.topicId;
            minDate = source.minDate;
            maxDate = source.maxDate;
            totalCount = source.totalCount;
            endReached = source.endReached;
            nextSearchRate = source.nextSearchRate;
            folderId = source.folderId;
            communityId = source.communityId;
            communityPeer = source.communityPeer;
            reaction = source.reaction;
            sourceAccount = source.sourceAccount;
            sourceUserId = source.sourceUserId;
            sourceLoginTime = source.sourceLoginTime;
            offsetId = source.offsetId;
            offsetPeerId = source.offsetPeerId;
            cursorCaptured = source.cursorCaptured;
            sourceIdentity = source.sourceIdentity;
        }

        public void captureCursor(int account, ArrayList<MessageObject> messages) {
            sourceAccount = account;
            UserConfig config = UserConfig.getInstance(account);
            sourceUserId = config.getClientUserId();
            sourceLoginTime = config.loginTime;
            cursorCaptured = true;
            offsetId = 0;
            offsetPeerId = 0;
            if (!messages.isEmpty()) {
                MessageObject tail = messages.get(messages.size() - 1);
                offsetId = tail.getId();
                offsetPeerId = MessageObject.getPeerId(tail.messageOwner.peer_id);
            }
        }

        public boolean isCurrentAccount(int account) {
            UserConfig config = UserConfig.getInstance(account);
            return sourceAccount == account && sourceUserId != 0
                    && config.getClientUserId() == sourceUserId && config.loginTime == sourceLoginTime;
        }

        public void setSourceIdentity(Object identity) {
            if (identity != null) sourceIdentity = identity;
        }

        private static boolean sameSource(PlaylistGlobalSearchParams first, PlaylistGlobalSearchParams second) {
            return first == null ? second == null : second != null && first.sourceIdentity == second.sourceIdentity;
        }

        private String cursorKey() {
            return dialogId != 0 && communityId == 0 ? String.valueOf(offsetId)
                    : offsetId + "_" + offsetPeerId + "_" + nextSearchRate;
        }
    }

    public boolean currentPlaylistIsGlobalSearch() {
        return playlistGlobalSearchParams != null;
    }

    /* */

    private SavedMusicPlaylistState savedMusicPlaylistState;

    private PendingMusicReturn pendingMusicReturn;
    private final AtomicLong musicReturnIntentGeneration = new AtomicLong();
    private final AtomicLong musicReturnCancellation = new AtomicLong();

    private final class SavedMusicPlaylistState {
        public final MessageObject playingMessage;
        public final float progress;
        public final int progressMs;
        public final int progressSec;

        final long userId, documentId, eventId, recordFocusGeneration, dialogId, cancellation;
        final int account, messageId, loginTime;
        final boolean scheduled, initiallyPlaying, standalone;
        final PlaylistLoadOwner sourceOwner;
        final MessagesController.SavedMusicList savedList;
        final PlaylistGlobalSearchParams searchSource;
        boolean roundLed, roundPaused;
        volatile boolean resumeAllowed;
        MessageObject spokenMessage;
        long spokenUserId;
        int spokenAccount, spokenLoginTime, spokenPlayerNum;
        public SavedMusicPlaylistState(MessageObject playingMessage) {
            this.playingMessage = playingMessage;
            this.progress = playingMessage.audioProgress;
            this.progressMs = playingMessage.audioProgressMs;
            this.progressSec = playingMessage.audioProgressSec;
            account = playingMessage.currentAccount;
            dialogId = playingMessage.getDialogId();
            messageId = playingMessage.getId();
            UserConfig config = UserConfig.getInstance(playingMessage.currentAccount);
            userId = config.getClientUserId();
            loginTime = config.loginTime;
            documentId = playingMessage.getDocument() == null ? 0 : playingMessage.getDocument().id;
            eventId = playingMessage.eventId;
            recordFocusGeneration = recordAudioFocusGeneration.get();
            cancellation = musicReturnCancellation.get();
            scheduled = playingMessage.scheduled;
            initiallyPlaying = !isMessagePaused() && audioPlayer != null && audioPlayer.isPlaying() && !CastSync.isActive();
            resumeAllowed = initiallyPlaying;
            sourceOwner = playlistLoadOwner;
            savedList = currentSavedMusicList;
            searchSource = playlistGlobalSearchParams;
            standalone = !containsMusicPlaylistMessage(playingMessage);
        }

        void beginRound(MessageObject message) {
            roundLed = true;
            spokenAccount = message.currentAccount;
            UserConfig config = UserConfig.getInstance(message.currentAccount);
            spokenUserId = config.getClientUserId();
            spokenLoginTime = config.loginTime;
            spokenMessage = message;
        }

        boolean currentSpokenAccount() {
            if (spokenMessage == null) return false;
            UserConfig config = UserConfig.getInstance(spokenAccount);
            return spokenUserId != 0 && spokenUserId == config.getClientUserId() && spokenLoginTime == config.loginTime;
        }

        boolean matchesSong(MessageObject message) {
            return message != null && message.isMusic() && message.currentAccount == account
                    && message.getDialogId() == dialogId && message.getId() == messageId
                    && message.eventId == eventId && message.scheduled == scheduled
                    && (message.getDocument() == null ? 0 : message.getDocument().id) == documentId;
        }

        boolean currentSource() {
            UserConfig config = UserConfig.getInstance(account);
            return userId != 0 && userId == config.getClientUserId() && loginTime == config.loginTime
                    && playlistLoadOwner == sourceOwner && currentSavedMusicList == savedList
                    && playlistGlobalSearchParams == searchSource;
        }
    }

    private void clearMusicPlaylistState() {
        retirePendingMusicReturn(true);
        if (savedMusicPlaylistState != null && savedMusicPlaylistState.roundLed) resumeAudioOnFocusGain = false;
        savedMusicPlaylistState = null;
    }

    private void cancelAutomaticMusicReturn() {
        musicReturnCancellation.incrementAndGet();
        musicReturnIntentGeneration.incrementAndGet();
    }

    private final class PendingMusicReturn {
        final SavedMusicPlaylistState state;
        final boolean wantsActive;
        MessageObject message;
        VideoPlayer player;
        boolean paused;
        PendingMusicReturn(SavedMusicPlaylistState state, MessageObject message, boolean active) {
            this.state = state;
            this.message = message;
            wantsActive = active;
            paused = !active;
        }
    }

    private boolean currentMusicReturn(PendingMusicReturn owner, MessageObject message) {
        long generation = musicReturnIntentGeneration.get();
        if (owner == null || pendingMusicReturn != owner || owner.message != message
                || !owner.state.currentSource() || !owner.state.matchesSong(message)) return false;
        if (owner.state.standalone) return pendingMusicReturn == owner && generation == musicReturnIntentGeneration.get();
        for (MessageObject song : playlist) {
            if (owner.state.matchesSong(song)) return pendingMusicReturn == owner && generation == musicReturnIntentGeneration.get();
        }
        return false;
    }

    private boolean activeMusicReturn(PendingMusicReturn owner) {
        long generation = musicReturnIntentGeneration.get();
        SavedMusicPlaylistState state = owner.state;
        return owner.wantsActive && !owner.paused && state.resumeAllowed
                && state.cancellation == musicReturnCancellation.get() && state.currentSpokenAccount()
                && !callInProgress && !VoIPService.isAnyKindOfCallActive() && !isRecordingAudio()
                && pendingVoiceRecordingGeneration < 0 && recordAudioFocusGeneration.get() == state.recordFocusGeneration
                && !raiseToEarRecord && !CastSync.isActive()
                && pendingMusicReturn == owner && generation == musicReturnIntentGeneration.get();
    }

    private void restoreMusicSeek(SavedMusicPlaylistState state, MessageObject message) {
        message.forceSeekTo = state.progress;
        message.audioProgress = state.progress;
        message.audioProgressMs = state.progressMs;
        message.audioProgressSec = state.progressSec;
    }

    private void retirePendingMusicReturn(boolean cancelDownload) {
        PendingMusicReturn owner = pendingMusicReturn;
        pendingMusicReturn = null;
        if (owner == null) return;
        resumeAudioOnFocusGain = false;
        VideoPlayer retiring = null;
        if (owner.player != null && audioPlayer == owner.player && playingMessageObject == null) {
            retiring = owner.player;
            audioPlayer = null;
        }
        TLRPC.Document cancelDocument = null;
        if (playingMessageObject == owner.message && downloadingCurrentMessage) {
            downloadingCurrentMessage = false;
            isPaused = true;
            if (cancelDownload) cancelDocument = owner.message.getDocument();
        }
        if (retiring != null) {
            try { retiring.releasePlayer(true); } catch (Exception e) { FileLog.e(e); }
        }
        if (cancelDocument != null) FileLoader.getInstance(owner.message.currentAccount).cancelLoadFile(cancelDocument);
    }

    private void resumePendingMusicReturn(PendingMusicReturn owner) {
        MessageObject message = playingMessageObject;
        if (!currentMusicReturn(owner, message)) {
            if (pendingMusicReturn == owner) retirePendingMusicReturn(true);
            return;
        }
        owner.paused |= !activeMusicReturn(owner);
        restoreMusicSeek(owner.state, message);
        boolean started = playMessage(message, false, null, owner.paused, owner.state.progressMs, owner);
        finishMusicReturnAdmission(owner, started);
    }

    private void finishMusicReturnAdmission(PendingMusicReturn owner, boolean started) {
        if (pendingMusicReturn != owner || started && downloadingCurrentMessage) return;
        if (!started && owner.paused && owner.player != null && audioPlayer == owner.player && playingMessageObject == null
                && owner.state.currentSource()) {
            owner.player.pause();
            playingMessageObject = owner.message;
            isPaused = true;
        }
        retirePendingMusicReturn(false);
    }

    private boolean ownsRoundPlayback(SavedMusicPlaylistState state, MessageObject message, int tag) {
        return state != null && state == savedMusicPlaylistState && state.roundLed
                && state.spokenMessage == message && playingMessageObject == message
                && state.spokenPlayerNum == tag && playerNum == tag;
    }

    private boolean finishRoundPlayback(MessageObject message, int tag, boolean playWhenReady, int playbackState) {
        SavedMusicPlaylistState state = savedMusicPlaylistState;
        if (!ownsRoundPlayback(state, message, tag)) return false;
        if (playbackState != ExoPlayer.STATE_ENDED || !playWhenReady || isPaused || playbackIntentPaused || state.roundPaused) return true;
        if (!state.currentSpokenAccount()) {
            clearMusicPlaylistState();
            cleanupPlayer(true, true, false, false, message.currentAccount);
            return true;
        }
        long returnGeneration = musicReturnIntentGeneration.get();
        long focusIntent = playbackIntentGeneration.get();
        cleanupPlayer(true, hasNoNextVoiceOrRoundVideoMessage(), true, false, -1, state);
        if (savedMusicPlaylistState == state && musicReturnIntentGeneration.get() == returnGeneration && playingMessageObject == null) {
            restoreMusicPlaylistState(true);
        }
        if (playingMessageObject == null) retirePlaybackIntent(message, focusIntent);
        return true;
    }

    private boolean acquireMusicReturnFocus() {
        long generation = musicReturnIntentGeneration.get();
        PendingMusicReturn owner = pendingMusicReturn;
        boolean granted = owner != null && checkAudioFocus(owner.message);
        if (!granted || generation != musicReturnIntentGeneration.get()
                || pendingMusicReturn != owner) return false;
        return true;
    }
    private boolean saveMusicPlaylistStateIfNeeded() {
        if (playingMessageObject != null && playingMessageObject.isMusic()) {
            savedMusicPlaylistState = new SavedMusicPlaylistState(playingMessageObject);
            return true;
        } else {
            return savedMusicPlaylistState != null;
        }
    }

    private boolean containsMusicPlaylistMessage(MessageObject message) {
        if (message == null) return false;
        String key = playlistKey(message);
        for (MessageObject song : playlist) {
            if (playlistKey(song).equals(key)) return true;
        }
        return false;
    }
    private boolean restoreMusicPlaylistState() {
        return restoreMusicPlaylistState(false);
    }

    private boolean restoreMusicPlaylistState(boolean naturalCompletion) {
        if (savedMusicPlaylistState == null) {
            return false;
        }

        final SavedMusicPlaylistState state = savedMusicPlaylistState;
        final long returnGeneration = musicReturnIntentGeneration.get();
        savedMusicPlaylistState = null;

        if (state.roundLed) resumeAudioOnFocusGain = false;
        if (!state.currentSource() || returnGeneration != musicReturnIntentGeneration.get()) return false;
        final ArrayList<MessageObject> currentPlayList = SharedConfig.shuffleMusic ? shuffledPlaylist : playlist;
        int index = -1;
        for (int i = 0; i < currentPlayList.size(); ++i) {
            if (state.matchesSong(currentPlayList.get(i))) { index = i; break; }
        }
        if (!state.standalone && index < 0 || returnGeneration != musicReturnIntentGeneration.get()) return false;
        MessageObject messageObject = state.standalone ? state.playingMessage : currentPlayList.get(index);
        boolean active = naturalCompletion && state.roundLed && state.resumeAllowed && state.initiallyPlaying
                && state.cancellation == musicReturnCancellation.get()
                && !state.roundPaused && state.currentSpokenAccount() && !callInProgress
                && !VoIPService.isAnyKindOfCallActive() && !isRecordingAudio() && pendingVoiceRecordingGeneration < 0
                && recordAudioFocusGeneration.get() == state.recordFocusGeneration
                && !raiseToEarRecord && !CastSync.isActive();
        boolean sourceCurrent = state.currentSource();
        if (!sourceCurrent || returnGeneration != musicReturnIntentGeneration.get()) return true;
        currentPlaylistNum = state.standalone ? -1 : index;

        playMusicAgain = false;
        restoreMusicSeek(state, messageObject);
        PendingMusicReturn owner = pendingMusicReturn = new PendingMusicReturn(state, messageObject, active);
        boolean started = playMessage(messageObject, false, null, !active, state.progressMs, owner);
        finishMusicReturnAdmission(owner, started);

        return true;
    }

    private static class MusicListenReporter {

        public final int currentAccount;
        private TLRPC.InputDocument audio;

        public MusicListenReporter(int currentAccount) {
            this.currentAccount = currentAccount;
        }

        public void setup(TLRPC.InputDocument document) {
            AndroidUtilities.cancelRunOnUIThread(reportRunnable);
            if (document != null && document.id == 0) {
                document = null;
            }
            audio = document;
            rangeStart = C.TIME_UNSET;
            ranges.clear();
        }

        private long rangeStart = C.TIME_UNSET;
        public Player.Listener getPlayerListener(ExoPlayer player) {
            return new Player.Listener() {
                private void closeRange() {
                    long pos = player.getCurrentPosition();
                    if (rangeStart != C.TIME_UNSET && pos > rangeStart) {
                        listenedRange(rangeStart, pos);
                    }
                    rangeStart = C.TIME_UNSET;
                }

                @Override
                public void onIsPlayingChanged(boolean isPlaying) {
                    if (isPlaying) {
                        rangeStart = player.getCurrentPosition();
                    } else {
                        closeRange();
                    }

                    AndroidUtilities.cancelRunOnUIThread(reportRunnable);
                    if (!isPlaying) {
                        AndroidUtilities.runOnUIThread(reportRunnable, 60_000);
                    }
                }

                @Override
                public void onPositionDiscontinuity(
                    Player.PositionInfo oldPosition,
                    Player.PositionInfo newPosition,
                    int reason
                ) {
                    if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                        if (rangeStart != C.TIME_UNSET) {
                            listenedRange(rangeStart, oldPosition.positionMs);
                        }
                        rangeStart = player.isPlaying() ? newPosition.positionMs : C.TIME_UNSET;

                        AndroidUtilities.cancelRunOnUIThread(reportRunnable);
                    }
                }
            };
        }

        private final ArrayList<Pair<Long, Long>> ranges = new ArrayList<>();
        private void listenedRange(long fromMs, long toMs) {
            int idx = 0;
            while (idx < ranges.size() && ranges.get(idx).first <= fromMs) idx++;
            ranges.add(idx, new Pair<>(fromMs, toMs));
            int i = Math.max(0, idx - 1);
            while (i < ranges.size() - 1) {
                Pair<Long, Long> cur  = ranges.get(i);
                Pair<Long, Long> next = ranges.get(i + 1);
                if (cur.second >= next.first) {
                    ranges.set(i, new Pair<>(cur.first, Math.max(cur.second, next.second)));
                    ranges.remove(i + 1);
                } else {
                    i++;
                }
            }
        }
        private long getTotalListened() {
            long total = 0L;
            for (Pair<Long, Long> r : ranges) {
                total += r.second - r.first;
            }
            return total;
        }

        private final Runnable reportRunnable = this::report;
        private void report() {
            AndroidUtilities.cancelRunOnUIThread(reportRunnable);
            if (audio == null) return;

            final long ms = getTotalListened();
            if (ms < 3_000) return;

            final TLRPC.TL_messages_reportMusicListen req = new TLRPC.TL_messages_reportMusicListen();
            req.id = audio;
            req.listened_duration = (int) Math.round(getTotalListened() / 1000.0);
            ConnectionsManager.getInstance(currentAccount).sendRequest(req, null);

            rangeStart = C.TIME_UNSET;
            ranges.clear();
        }

        public void destroy() {
            if (audio == null) return;

            report();
            audio = null;
        }
    }
}
