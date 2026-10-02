/* Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.camera;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;

public final class VoiceAudioCapture {
    private VoiceAudioCapture() {}

    public static AudioRecord createStarted(Context context, int configuredSource, int sampleRate,
                                            int bufferSize, boolean wideCapture) {
        boolean wide = wideCapture && !hasExternalInput(context);
        int[] sources = wide
                ? new int[]{MediaRecorder.AudioSource.CAMCORDER, configuredSource,
                        MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.DEFAULT}
                : new int[]{configuredSource, MediaRecorder.AudioSource.MIC, MediaRecorder.AudioSource.DEFAULT};
        RuntimeException lastError = null;
        for (int channels = wide ? 2 : 1; channels >= 1; channels--) {
            int mask = channels == 2 ? AudioFormat.CHANNEL_IN_STEREO : AudioFormat.CHANNEL_IN_MONO;
            int minimum = AudioRecord.getMinBufferSize(sampleRate, mask, AudioFormat.ENCODING_PCM_16BIT);
            if (minimum <= 0) continue;
            for (int i = 0; i < sources.length; i++) {
                boolean duplicate = false;
                for (int j = 0; j < i; j++) {
                    if (sources[j] == sources[i]) duplicate = true;
                }
                if (duplicate) continue;
                AudioRecord candidate = null;
                boolean started = false;
                try {
                    candidate = new AudioRecord(sources[i], sampleRate, mask,
                            AudioFormat.ENCODING_PCM_16BIT, Math.max(minimum, bufferSize * channels));
                    if (candidate.getState() != AudioRecord.STATE_INITIALIZED) continue;
                    if (wide) CameraXAudioCapture.requestWideCapture(candidate);
                    candidate.startRecording();
                    if (candidate.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) continue;
                    started = true;
                    return candidate;
                } catch (SecurityException error) {
                    throw error;
                } catch (RuntimeException error) {
                    lastError = error;
                } finally {
                    if (!started && candidate != null) {
                        try {
                            candidate.release();
                        } catch (RuntimeException ignored) {}
                    }
                }
            }
        }
        throw new IllegalStateException("No usable voice recording input", lastError);
    }

    private static boolean hasExternalInput(Context context) {
        try {
            AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (manager == null) return true;
            for (AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
                int type = device.getType();
                if (type == AudioDeviceInfo.TYPE_WIRED_HEADSET || type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                        || type == AudioDeviceInfo.TYPE_USB_DEVICE || type == AudioDeviceInfo.TYPE_USB_HEADSET
                        || type == AudioDeviceInfo.TYPE_BLE_HEADSET) return true;
            }
            return false;
        } catch (RuntimeException ignored) {
            return true;
        }
    }
}
