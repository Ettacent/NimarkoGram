/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.banners;

import androidx.media3.common.C;
import androidx.media3.common.audio.BaseAudioProcessor;

import java.nio.ByteBuffer;

public final class BannerVolumeProcessor extends BaseAudioProcessor {
    private static final int RAMP_MS = 10;
    private volatile float targetGain;
    private float currentGain;
    private float rampTarget;
    private float rampStep;
    private int remainingFrames;

    public void setGain(float gain) {
        targetGain = Float.isNaN(gain) ? 0f : Math.max(0f, Math.min(1f, gain));
    }

    @Override
    protected AudioFormat onConfigure(AudioFormat format) throws UnhandledAudioFormatException {
        if (format.encoding != C.ENCODING_PCM_16BIT || format.channelCount <= 0 || format.sampleRate <= 0) {
            throw new UnhandledAudioFormatException(format);
        }
        return format;
    }

    @Override
    public void queueInput(ByteBuffer input) {
        if (!input.hasRemaining()) return;
        int frameSize = inputAudioFormat.channelCount * 2;
        if (frameSize <= 0 || input.remaining() % frameSize != 0) {
            throw new IllegalArgumentException("Incomplete banner PCM frame");
        }
        float target = targetGain;
        if (target != rampTarget) {
            rampTarget = target;
            remainingFrames = Math.max(1, inputAudioFormat.sampleRate * RAMP_MS / 1000);
            rampStep = (target - currentGain) / remainingFrames;
        }
        ByteBuffer output = replaceOutputBuffer(input.remaining());
        if (remainingFrames == 0 && currentGain == 1f) {
            output.put(input);
        } else {
            while (input.hasRemaining()) {
                if (remainingFrames > 0) {
                    currentGain = --remainingFrames == 0 ? rampTarget : currentGain + rampStep;
                }
                for (int channel = 0; channel < inputAudioFormat.channelCount; channel++) {
                    output.putShort((short) (input.getShort() * currentGain));
                }
            }
        }
        output.flip();
    }

    @Override
    protected void onFlush() {
        currentGain = rampTarget = targetGain;
        remainingFrames = 0;
        rampStep = 0f;
    }
}
