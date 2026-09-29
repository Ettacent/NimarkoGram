package app.nimarkogram.messenger.camera;

import android.media.AudioRecord;
import android.media.MicrophoneDirection;
import android.os.Build;

import java.nio.ByteBuffer;

public final class CameraXAudioCapture {
    private CameraXAudioCapture() {}

    public static void requestWideCapture(AudioRecord recorder) {
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                recorder.setPreferredMicrophoneDirection(MicrophoneDirection.MIC_DIRECTION_UNSPECIFIED);
                recorder.setPreferredMicrophoneFieldDimension(-1f);
            } catch (RuntimeException ignored) {

            }
        }
    }

    public static long durationUs(int bytes, int sampleRate, int channels) {
        return 1_000_000L * bytes / (sampleRate * channels * 2L);
    }

    public static long bufferTimeUs(long timestampNanos, long timestampFrame,
                                    long firstBufferFrame, int sampleRate) {
        return timestampNanos / 1000L + (firstBufferFrame - timestampFrame) * 1_000_000L / sampleRate;
    }

    public static int downmixStereo16(ByteBuffer buffer, int bytes) {
        int frames = bytes / 4;
        for (int frame = 0; frame < frames; frame++) {
            int input = frame * 4;
            int left = (short) ((buffer.get(input) & 255) | (buffer.get(input + 1) << 8));
            int right = (short) ((buffer.get(input + 2) & 255) | (buffer.get(input + 3) << 8));
            int mono = (left + right) / 2;
            buffer.put(frame * 2, (byte) mono);
            buffer.put(frame * 2 + 1, (byte) (mono >> 8));
        }
        buffer.position(0);
        buffer.limit(frames * 2);
        return frames * 2;
    }
}
