/* Modifications Copyright (C) 2026 Ettacent */

package app.nimarkogram.messenger.banners;

import android.content.Context;

import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.audio.AudioOffloadSupport;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider;
import androidx.media3.exoplayer.audio.ForwardingAudioSink;

public final class BannerAudioRenderersFactory extends DefaultRenderersFactory
        implements org.telegram.ui.Components.VideoPlayer.SourceVolumeController {
    private volatile float sourceVolume;
    private volatile BannerVolumeProcessor volumeProcessor;

    @Override
    public synchronized void setSourceVolume(float value) {
        sourceVolume = Float.isNaN(value) ? 0f : Math.max(0f, Math.min(1f, value));
        BannerVolumeProcessor processor = volumeProcessor;
        if (processor != null) processor.setGain(sourceVolume);
    }

    @Override
    public float getSourceVolume() {
        return sourceVolume;
    }
    public BannerAudioRenderersFactory(Context context) {
        super(context);
    }

    @Override
    protected AudioSink buildAudioSink(Context context, boolean enableFloatOutput,
                                       boolean enableAudioTrackPlaybackParams) {
        final BannerVolumeProcessor volume = new BannerVolumeProcessor();
        synchronized (this) {
            volumeProcessor = volume;
            volume.setGain(sourceVolume);
        }
        AudioSink sink = new DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(false)
                .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                .setAudioProcessors(new AudioProcessor[]{volume})
                .setAudioTrackBufferSizeProvider(new DefaultAudioTrackBufferSizeProvider.Builder()
                        .setMinPcmBufferDurationUs(80_000)
                        .setMaxPcmBufferDurationUs(120_000)
                        .setPcmBufferMultiplicationFactor(2)
                        .build())
                .build();
        return new ForwardingAudioSink(sink) {
            @Override
            public boolean supportsFormat(Format format) {
                return getFormatSupport(format) != SINK_FORMAT_UNSUPPORTED;
            }

            @Override
            public int getFormatSupport(Format format) {
                return MimeTypes.AUDIO_RAW.equals(format.sampleMimeType)
                        ? super.getFormatSupport(format) : SINK_FORMAT_UNSUPPORTED;
            }

            @Override
            public AudioOffloadSupport getFormatOffloadSupport(Format format) {
                return AudioOffloadSupport.DEFAULT_UNSUPPORTED;
            }

            @Override
            public void setOffloadMode(int mode) {
                super.setOffloadMode(OFFLOAD_MODE_DISABLED);
            }

            @Override
            public void setVolume(float gain) {
                super.setVolume(gain);
            }
        };
    }
}
