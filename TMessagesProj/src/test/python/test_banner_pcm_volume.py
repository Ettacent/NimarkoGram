"""Executable PCM regression tests, without an Android/Gradle build.

Compiles the unchanged production BannerVolumeProcessor, BaseAudioProcessor and
AudioProcessor. Only Android-independent API dependencies are stubbed; no gain,
ramp, buffer lifecycle or processor-interface behavior is substituted.
Run: python3 -B TMessagesProj/src/test/python/test_banner_pcm_volume.py -v
Requires a JDK on PATH. All generated Java/classes live in a temporary directory.
This does not exercise AudioTrack, the renderer factory, or device capture latency.
"""

import subprocess
import tempfile
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[4]
COMMON = ROOT / "TMessagesProj_Modules/media/libraries/common/src/main/java"
PROCESSOR = ROOT / (
    "TMessagesProj/src/main/java/app/nimarkogram/messenger/banners/"
    "BannerVolumeProcessor.java"
)

STUBS = {
    "androidx/annotation/CallSuper.java":
        "package androidx.annotation; public @interface CallSuper {}",
    "androidx/annotation/Nullable.java":
        "package androidx.annotation; public @interface Nullable {}",
    "androidx/media3/common/util/UnstableApi.java":
        "package androidx.media3.common.util; public @interface UnstableApi {}",
    "androidx/media3/common/C.java": """
        package androidx.media3.common;
        public final class C {
            public static final int ENCODING_PCM_16BIT = 2;
            public static final int ENCODING_PCM_8BIT = 3;
            public static final int ENCODING_PCM_FLOAT = 4;
            public @interface PcmEncoding {}
        }
    """,
    "androidx/media3/common/Format.java": """
        package androidx.media3.common;
        public final class Format {
            public static final int NO_VALUE = -1;
            public int sampleRate, channelCount, pcmEncoding;
        }
    """,
    "androidx/media3/common/util/Util.java": """
        package androidx.media3.common.util;
        import androidx.media3.common.C;
        public final class Util {
            public static boolean isEncodingLinearPcm(int encoding) {
                return encoding == C.ENCODING_PCM_16BIT
                    || encoding == C.ENCODING_PCM_8BIT
                    || encoding == C.ENCODING_PCM_FLOAT;
            }
            public static int getPcmFrameSize(int encoding, int channels) {
                switch (encoding) {
                    case C.ENCODING_PCM_16BIT: return channels * 2;
                    case C.ENCODING_PCM_8BIT: return channels;
                    case C.ENCODING_PCM_FLOAT: return channels * 4;
                    default: throw new IllegalArgumentException("unsupported stub encoding");
                }
            }
        }
    """,
}


class BannerPcmVolumeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp = tempfile.TemporaryDirectory(prefix="banner-pcm-volume-")
        cls.addClassCleanup(cls.temp.cleanup)
        directory = Path(cls.temp.name)
        sources = [PROCESSOR, COMMON / "androidx/media3/common/audio/BaseAudioProcessor.java",
                   COMMON / "androidx/media3/common/audio/AudioProcessor.java"]
        for name, source in {**STUBS, "BannerPcmHarness.java": HARNESS}.items():
            path = directory / name
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(source, encoding="utf-8")
            sources.append(path)
        result = subprocess.run(
            ["javac", "-encoding", "UTF-8", "-d", str(directory),
             *map(str, sources)], capture_output=True, text=True, timeout=30)
        if result.returncode:
            raise AssertionError("Production processor compilation failed:\n"
                                 + result.stdout + result.stderr)

    def execute(self, scenario):
        result = subprocess.run(
            ["java", "-ea", "-cp", self.temp.name, "BannerPcmHarness", scenario],
            capture_output=True, text=True, timeout=15)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("PASS " + scenario, result.stdout)

    def test_initial_mute_and_active_pipeline(self):
        self.execute("mute")

    def test_unity_is_bit_exact_and_remains_active(self):
        self.execute("unity")

    def test_fractional_signed_samples(self):
        self.execute("fractional")

    def test_stereo_ramp_is_frame_based_across_buffers(self):
        self.execute("stereo")

    def test_gain_change_retargets_in_flight_ramp(self):
        self.execute("changing")

    def test_flush_discards_output_and_applies_latest_gain(self):
        self.execute("flush")

    def test_invalid_formats_and_incomplete_frames(self):
        self.execute("formats")

    def test_nonfinite_and_out_of_range_gain(self):
        self.execute("clamp")

    def test_end_of_stream_and_reset_reconfiguration(self):
        self.execute("lifecycle")


HARNESS = r"""
import app.nimarkogram.messenger.banners.BannerVolumeProcessor;
import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor.AudioFormat;
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public final class BannerPcmHarness {
    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
    static BannerVolumeProcessor configured(int rate, int channels, Float gain) throws Exception {
        BannerVolumeProcessor p = new BannerVolumeProcessor();
        if (gain != null) p.setGain(gain);
        AudioFormat format = new AudioFormat(rate, channels, C.ENCODING_PCM_16BIT);
        check(format.equals(p.configure(format)), "format changed");
        check(p.isActive(), "processor must stay active at all gains");
        p.flush();
        return p;
    }
    static ByteBuffer input(short... samples) {
        // Nonzero position/limited view guards against processing outside the input window.
        ByteBuffer b = ByteBuffer.allocateDirect(samples.length * 2 + 4)
            .order(ByteOrder.nativeOrder());
        b.putShort((short) 1234);
        for (short sample : samples) b.putShort(sample);
        b.putShort((short) 5678);
        b.limit(b.capacity() - 2);
        b.position(2);
        return b;
    }
    static short[] process(BannerVolumeProcessor p, short... samples) {
        ByteBuffer in = input(samples);
        p.queueInput(in);
        check(!in.hasRemaining(), "input not fully consumed");
        for (int i = 0; i < samples.length; i++)
            check(in.getShort(2 + i * 2) == samples[i], "input mutated");
        ByteBuffer out = p.getOutput();
        check(out.isDirect() && out.order().equals(ByteOrder.nativeOrder()), "output contract");
        check(out.remaining() == samples.length * 2, "output length changed");
        short[] actual = new short[samples.length];
        for (int i = 0; i < actual.length; i++) actual[i] = out.getShort();
        check(!p.getOutput().hasRemaining(), "output emitted twice");
        return actual;
    }
    static short[] tone(int frames, int channels) {
        short[] samples = new short[frames * channels];
        Arrays.fill(samples, (short) 10000);
        return samples;
    }
    static void equal(short[] actual, short... expected) {
        check(Arrays.equals(actual, expected), "actual=" + Arrays.toString(actual)
              + " expected=" + Arrays.toString(expected));
    }
    static void near(int actual, int expected, String message) {
        check(Math.abs(actual - expected) <= 1, message + ": " + actual + " != " + expected);
    }
    static void ramp(short[] actual, int channels, int offset, int length,
                     double start, double target) {
        for (int frame = 0; frame < actual.length / channels; frame++) {
            double gain = start + (target - start) * Math.min(offset + frame + 1, length) / length;
            for (int channel = 0; channel < channels; channel++) {
                near(actual[frame * channels + channel], (int)(10000 * gain), "ramp frame " + frame);
                check(actual[frame * channels + channel] == actual[frame * channels],
                      "channel-dependent gain");
            }
        }
    }
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
        case "mute": {
            BannerVolumeProcessor p = configured(48000, 2, null);
            equal(process(p, (short)-32768, (short)32767, (short)-1, (short)1),
                  (short)0, (short)0, (short)0, (short)0);
            check(p.isActive(), "mute bypassed");
            break;
        }
        case "unity": {
            BannerVolumeProcessor p = configured(48000, 1, 1f);
            short[] samples = {-32768, -30001, -1, 0, 1, 12345, 32767};
            equal(process(p, samples), samples);
            check(p.isActive(), "unity bypassed");
            p.setGain(0f);
            short[] down = process(p, tone(481, 1));
            ramp(down, 1, 0, 480, 1, 0);
            check(down[479] == 0 && down[480] == 0, "mute endpoint not exact");
            break;
        }
        case "fractional": {
            for (float gain : new float[]{0.05f, 0.25f, 0.5f}) {
                BannerVolumeProcessor p = configured(44100, 1, gain);
                short[] samples = {-32768, -10001, -1, 0, 1, 10001, 32767};
                short[] actual = process(p, samples);
                for (int i = 0; i < samples.length; i++)
                    check(actual[i] == (short)(samples[i] * gain), "signed fractional gain");
            }
            break;
        }
        case "stereo": {
            for (int rate : new int[]{44100, 48000}) {
                int length = rate / 100;
                BannerVolumeProcessor p = configured(rate, 2, 0f);
                p.setGain(1f);
                int split = 137;
                ramp(process(p, tone(split, 2)), 2, 0, length, 0, 1);
                p.setGain(1f); // Repeated target must not restart the ramp.
                ramp(process(p, tone(length - split + 3, 2)), 2, split, length, 0, 1);
                equal(process(p, (short)12345, (short)-12345), (short)12345, (short)-12345);
            }
            break;
        }
        case "changing": {
            BannerVolumeProcessor p = configured(48000, 2, 0f);
            p.setGain(1f);
            ramp(process(p, tone(240, 2)), 2, 0, 480, 0, 1);
            p.setGain(0.25f);
            ramp(process(p, tone(481, 2)), 2, 0, 480, 0.5, 0.25);
            equal(process(p, (short)10000, (short)-10000), (short)2500, (short)-2500);
            break;
        }
        case "flush": {
            BannerVolumeProcessor p = configured(48000, 2, 0f);
            p.setGain(1f);
            p.queueInput(input(tone(120, 2))); // Leave output pending.
            p.setGain(0.25f);
            p.flush();
            check(!p.getOutput().hasRemaining(), "flush retained old output");
            equal(process(p, (short)10000, (short)-10000), (short)2500, (short)-2500);
            p.setGain(0f);
            p.flush();
            equal(process(p, (short)32767, (short)-32768), (short)0, (short)0);
            p.queueInput(ByteBuffer.allocateDirect(0).order(ByteOrder.nativeOrder()));
            check(!p.getOutput().hasRemaining(), "empty input emitted output");
            break;
        }
        case "formats": {
            for (AudioFormat format : new AudioFormat[]{
                new AudioFormat(48000, 2, C.ENCODING_PCM_FLOAT),
                new AudioFormat(48000, 2, C.ENCODING_PCM_8BIT),
                new AudioFormat(48000, 2, -1),
                new AudioFormat(0, 2, C.ENCODING_PCM_16BIT),
                new AudioFormat(-1, 2, C.ENCODING_PCM_16BIT),
                new AudioFormat(48000, 0, C.ENCODING_PCM_16BIT),
                new AudioFormat(48000, -1, C.ENCODING_PCM_16BIT)}) {
                try {
                    new BannerVolumeProcessor().configure(format);
                    throw new AssertionError("accepted invalid " + format);
                } catch (UnhandledAudioFormatException expected) {
                    check(expected.inputAudioFormat.equals(format), "wrong rejected format");
                }
            }
            for (int bytes : new int[]{1, 2, 3, 5}) {
                BannerVolumeProcessor p = configured(48000, 2, 1f);
                try {
                    p.queueInput(ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder()));
                    throw new AssertionError("accepted incomplete stereo frame");
                } catch (IllegalArgumentException expected) { }
            }
            break;
        }
        case "clamp": {
            for (float gain : new float[]{Float.NaN, Float.NEGATIVE_INFINITY, -1f,
                                         Float.POSITIVE_INFINITY, 2f}) {
                BannerVolumeProcessor p = configured(48000, 1, gain);
                short expected = gain > 0 ? (short)10000 : 0;
                equal(process(p, (short)10000), expected);
            }
            break;
        }
        case "lifecycle": {
            BannerVolumeProcessor p = configured(48000, 1, 0.5f);
            p.queueInput(input((short)10000));
            p.queueEndOfStream();
            check(!p.isEnded(), "EOS dropped pending output");
            check(p.getOutput().getShort() == 5000, "EOS output");
            check(p.isEnded(), "EOS did not finish");
            p.flush();
            check(!p.isEnded(), "flush did not clear EOS");
            equal(process(p, (short)10000), (short)5000);
            p.reset();
            check(!p.isActive(), "reset retained configuration");
            p.setGain(0f);
            p.configure(new AudioFormat(44100, 2, C.ENCODING_PCM_16BIT));
            p.flush();
            equal(process(p, (short)10000, (short)10000), (short)0, (short)0);
            break;
        }
        default: throw new AssertionError("unknown scenario");
        }
        System.out.println("PASS " + args[0]);
    }
}
"""


if __name__ == "__main__":
    unittest.main()
