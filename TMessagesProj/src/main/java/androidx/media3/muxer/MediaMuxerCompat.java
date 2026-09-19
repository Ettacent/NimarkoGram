/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.muxer;

import static androidx.media3.muxer.MuxerUtil.getMuxerBufferInfoFromMediaCodecBufferInfo;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.annotation.ElementType.TYPE_USE;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.os.ParcelFileDescriptor;
import androidx.annotation.FloatRange;
import androidx.annotation.IntDef;
import androidx.media3.common.C;
import androidx.media3.common.util.MediaFormatUtil;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.container.MdtaMetadataEntry;
import androidx.media3.container.Mp4LocationData;
import androidx.media3.container.Mp4OrientationData;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.nio.ByteBuffer;








































public final class MediaMuxerCompat {

  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({OUTPUT_FORMAT_MP4})
  @UnstableApi
  public @interface OutputFormat {}


  public static final int OUTPUT_FORMAT_MP4 = MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4;

  private final FileOutputStream outputStream;
  private final Muxer muxer;

  private boolean startedMuxer;
  private boolean closedMuxer;












  public MediaMuxerCompat(FileDescriptor fileDescriptor, @OutputFormat int outputFormat)
      throws IOException {
    checkArgument(outputFormat == OUTPUT_FORMAT_MP4);
    outputStream = duplicateOutputStream(fileDescriptor);
    muxer = createMuxer(outputStream, outputFormat);
  }








  public MediaMuxerCompat(String filePath, @OutputFormat int outputFormat) throws IOException {
    checkArgument(outputFormat == OUTPUT_FORMAT_MP4);
    outputStream = new FileOutputStream(filePath);
    muxer = createMuxer(outputStream, outputFormat);
  }









  public void start() {
    checkState(!startedMuxer);
    checkState(!closedMuxer);
    startedMuxer = true;
  }














  public int addTrack(MediaFormat format) {
    checkState(!startedMuxer);
    try {
      float captureFps =
          getFloatFromIntOrFloat(
              format, MediaFormat.KEY_CAPTURE_RATE, C.RATE_UNSET);
      if (captureFps != C.RATE_UNSET) {
        MdtaMetadataEntry captureFpsMetadata =
            new MdtaMetadataEntry(
                MdtaMetadataEntry.KEY_ANDROID_CAPTURE_FPS,
                             Util.toByteArray(captureFps),
                MdtaMetadataEntry.TYPE_INDICATOR_FLOAT32);
        muxer.addMetadataEntry(captureFpsMetadata);
      }
      return muxer.addTrack(MediaFormatUtil.createFormatFromMediaFormat(format));
    } catch (MuxerException e) {
      throw new RuntimeException(e);
    }
  }
















  public void writeSampleData(
      int trackIndex, ByteBuffer byteBuffer, MediaCodec.BufferInfo bufferInfo) {
    checkState(startedMuxer);
    try {
      muxer.writeSampleData(
          trackIndex, byteBuffer, getMuxerBufferInfoFromMediaCodecBufferInfo(bufferInfo));
    } catch (MuxerException e) {
      throw new RuntimeException(e);
    }
  }










  public void setLocation(
      @FloatRange(from = -90.0, to = 90.0) float latitude,
      @FloatRange(from = -180.0, to = 180.0) float longitude) {
    checkState(!startedMuxer);
    muxer.addMetadataEntry(new Mp4LocationData(latitude, longitude));
  }










  public void setOrientationHint(int degrees) {
    checkState(!startedMuxer);
    muxer.addMetadataEntry(new Mp4OrientationData(degrees));
  }








  public void stop() {
    checkState(startedMuxer);
    closeMuxer();
  }








  public void release() {
    if (!closedMuxer) {
      closeMuxer();
    }
  }

  private void closeMuxer() {

    closedMuxer = true;
    startedMuxer = false;
    Throwable failure = null;
    try {
      muxer.close();
    } catch (MuxerException e) {
      RuntimeException wrapped = new RuntimeException(e);
      failure = wrapped;
      throw wrapped;
    } catch (RuntimeException | Error e) {
      failure = e;
      throw e;
    } finally {



      try {
        outputStream.close();
      } catch (IOException e) {
        if (failure != null) {
          failure.addSuppressed(e);
        } else {
          throw new RuntimeException(e);
        }
      }
    }
  }

  private static FileOutputStream duplicateOutputStream(FileDescriptor fileDescriptor)
      throws IOException {
    ParcelFileDescriptor duplicate = ParcelFileDescriptor.dup(fileDescriptor);
    try {
      return new ParcelFileDescriptor.AutoCloseOutputStream(duplicate);
    } catch (RuntimeException | Error e) {
      try {
        duplicate.close();
      } catch (IOException closeFailure) {
        e.addSuppressed(closeFailure);
      }
      throw e;
    }
  }


  private static float getFloatFromIntOrFloat(
      MediaFormat mediaFormat, String keyName, float defaultValue) {
    if (!mediaFormat.containsKey(keyName)) {
      return defaultValue;
    }
    if (Util.SDK_INT >= 29) {
      return mediaFormat.getValueTypeForKey(keyName) == MediaFormat.TYPE_FLOAT
          ? mediaFormat.getFloat(keyName)
          : mediaFormat.getInteger(keyName);
    }
    try {
      return mediaFormat.getFloat(keyName);
    } catch (ClassCastException e) {
      return mediaFormat.getInteger(keyName);
    }
  }

  private static Muxer createMuxer(
      FileOutputStream fileOutputStream, @OutputFormat int outputFormat) {
    try {
      checkArgument(outputFormat == OUTPUT_FORMAT_MP4);
      return new Mp4Muxer.Builder(fileOutputStream).build();
    } catch (RuntimeException | Error e) {

      try {
        fileOutputStream.close();
      } catch (IOException closeFailure) {
        e.addSuppressed(closeFailure);
      }
      throw e;
    }
  }
}
