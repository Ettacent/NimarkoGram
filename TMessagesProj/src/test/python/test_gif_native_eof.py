"""Actual JNI/reader/writer control flow; Android and conversion libraries faked.

No Android/native app build. This intentionally does NOT claim to exercise the
device codec, libswscale, AndroidBitmap_lockPixels or hardware Canvas.
"""
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

from test_gif_loop_transition import SRC, method


class GifNativeEofTests(unittest.TestCase):
    def source(self):
        jni = SRC.parent / "jni"
        reader = (jni / "gifvideo/video_frame_reader.h").read_text()
        reader = reader[reader.index("class VideoFrameReader {"):]
        entry = method((jni / "gifvideo.cpp").read_text(),
                       'extern "C" JNIEXPORT jint JNICALL Java_org_telegram_ui_Components_AnimatedFileNative_nGetVideoFrame(')
        return (SRC / "test/fixtures/GifNativeEofHarness.cpp.txt").read_text().replace(
            "/* READER */", reader).replace("/* GET_VIDEO_FRAME */", entry)

    def run_native(self, source):
        self.assertIsNotNone(shutil.which("g++"), "g++ required for native control-flow tests")
        with tempfile.TemporaryDirectory(prefix="gif-native-eof-") as directory:
            path = Path(directory) / "harness.cpp"
            path.write_text(source)
            binary = str(Path(directory) / "harness")
            result = subprocess.run(["g++", "-std=c++17", "-Wall", "-Wextra", str(path), "-o", binary],
                                    capture_output=True, text=True, timeout=30)
            self.assertEqual(result.returncode, 0, result.stderr)
            return subprocess.run([binary], capture_output=True, text=True, timeout=10)

    def bitmap_source(self):
        source = (SRC.parent / "jni/gifvideo.cpp").read_text()
        template = (SRC / "test/fixtures/GifBitmapWriteHarness.cpp.txt").read_text()
        for slot, marker in [
            ("WRITE_FRAME", "static inline " + ("bool" if "static inline bool writeFrameToBitmap" in source else "void") + " writeFrameToBitmap("),
            ("GET_VIDEO_FRAME", 'extern "C" JNIEXPORT jint JNICALL Java_org_telegram_ui_Components_AnimatedFileNative_nGetVideoFrame('),
            ("GET_FRAME_AT_TIME", 'extern "C" JNIEXPORT int JNICALL Java_org_telegram_ui_Components_AnimatedFileNative_nGetFrameAtTime('),
        ]:
            template = template.replace(f"/* {slot} */", method(source, marker))
        return template.replace("/* METADATA */", method(source, "void push_time(") + "\n" + method(source, "void push_single_frame("))

    def test_actual_bitmap_writer_and_both_jni_results(self):
        result = self.run_native(self.bitmap_source())
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_bitmap_write_negative_controls(self):
        source = self.bitmap_source()
        for before, after, failure in [
            ("return writeFrameToBitmap(env, info, frame, data, bitmap) ? 1 : 0;",
             "writeFrameToBitmap(env, info, frame, data, bitmap); return 1;",
             "lock failure must reach JNI as no frame"),
            ("result = writeFrameToBitmap(env, info, frame, data, bitmap) ? 1 : 0;",
             "writeFrameToBitmap(env, info, frame, data, bitmap); result = 1;",
             "lock failure must reach JNI as no frame"),
            ("result = writeFrameToBitmap(env, info, held, data, bitmap) ? 1 : 0;",
             "writeFrameToBitmap(env, info, held, data, bitmap); result = 1;",
             "lock failure must reach JNI as no frame"),
            (") == bitmapHeight;", ") > 0;", "short/failed sws conversion must return failure"),
            ("bool written = false;", "bool written = true;", "unsupported fallback must return failure"),
            ("if (written && alignedStride == bitmapStride)", "if (alignedStride == bitmapStride)",
             "failed scratch conversion must not copy partial pixels"),
            ("} else if (written) {", "} else {", "failed scratch conversion must not copy partial pixels"),
            ("const int32_t wantedWidth = dataArr[0];",
             "dataArr[3] = 0; dataArr[6] = 1; const int32_t wantedWidth = dataArr[0];",
             "failed write must not publish timestamp/opacity"),
        ]:
            with self.subTest(mutation=before):
                self.assertIn(before, source)
                result = self.run_native(source.replace(before, after, 1))
                self.assertNotEqual(result.returncode, 0, "bitmap-write mutation escaped tests")
                self.assertIn(failure, result.stderr)

    def test_production_eof_drain_seek_and_no_frame(self):
        result = self.run_native(self.source())
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_negative_controls(self):
        source = self.source()
        for before, after, failure in [
            ("ret = avcodec_send_packet(m_dec, nullptr);", "return FeedResult::Eof;",
             "success must write actual frame, not an EOF placeholder"),
            ("avcodec_flush_buffers(m_dec);", "/* omitted flush */",
             "every delayed tail frame and restarted first frame must decode"),
            (method(source, "if (st != VideoFrameReader::Status::Ok)"),
             "if (st != VideoFrameReader::Status::Ok) { return 1; }",
             "Again/abort/error must return no frame"),
        ]:
            with self.subTest(mutation=before):
                self.assertEqual(source.count(before), 1)
                result = self.run_native(source.replace(before, after, 1))
                self.assertNotEqual(result.returncode, 0, "native mutation escaped tests")
                self.assertIn(failure, result.stderr)


if __name__ == "__main__":
    unittest.main()
