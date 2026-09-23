"""No-build contracts/algebra and source-bound ownership model for round camera GL.

Run: python3 -B TMessagesProj/src/test/python/test_camera_snapshot_pipeline.py
No Java compilation, Android runtime or GPU is used. The lifetime model exercises
queue order/shutdown; source contracts bind its assumptions to the production code.
These tests do not replace a device recording/decoded-pixel check.
"""
import re
import unittest
from pathlib import Path


SOURCE = (Path(__file__).resolve().parents[2] / "main/java/org/telegram/ui/Components/InstantCameraView.java").read_text()


def body(source, marker):
    start = source.index(marker)
    opening = source.index("{", start)
    depth = 0
    for token in re.finditer(r'//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"|[{}]', source[opening:]):
        if token[0] == "{":
            depth += 1
        elif token[0] == "}":
            depth -= 1
            if depth == 0:
                return source[opening + 1:opening + token.start()]
    raise AssertionError(marker)


def ordered(source, *parts):
    position = 0
    for part in parts:
        found = source.find(part, position)
        assert found >= 0, f"missing/out-of-order: {part}"
        position = found + len(part)


def ownership_contract(source):
    capture = body(source, "private boolean captureCameraXSingleSwitchSnapshot()")
    ordered(capture, "clearCameraXSnapshot();", "return false;")
    ordered(capture, "capturedTimestamp = cameraFrameTimestamp[surfaceIndex]",
            "GLES20.glFinish();", "new CameraSnapshot(",
            "cameraXSingleSwitchSnapshotTimestamp = capturedTimestamp;",
            "cameraXSingleSwitchSnapshotHandle = snapshot;")
    assert "retiredCameraXSingleSwitchSnapshot" not in source
    clear = body(source, "private void clearCameraXSnapshot()")
    ordered(clear, "cameraXSingleSwitchSnapshotHandle = null;",
            "cameraXSingleSwitchSnapshot = 0;", "cameraXSingleSwitchSnapshotTimestamp = 0;",
            "snapshot.release();")
    snapshot = body(source, "private final class CameraSnapshot")
    for field in ("final int texture;", "final int width;", "final int height;", "final long timestamp;"):
        assert field in snapshot
    retain = body(snapshot, "synchronized boolean retain()")
    ordered(retain, "if (references == 0) return false;", "references++;", "return true;")
    release = body(snapshot, "void release()")
    ordered(release, "if (references == 0 || --references != 0) return;",
            "ownerHandler.post(() ->", "if (snapshotContextClosed",
            "egl10.eglMakeCurrent", "GLES20.glFinish();", "GLES20.glDeleteTextures")
    state = body(source, "private CameraVideoFrameState captureFrameState(")
    ordered(state, "snapshot = cameraXSingleSwitchSnapshotHandle;", "snapshot.retain()",
            "new CameraVideoFrameState(", "transition ? snapshot : null")
    for split_field in ("cameraXSingleSwitchSnapshotWidth", "cameraXSingleSwitchSnapshotHeight",
                        "cameraXSingleSwitchSnapshotTimestamp"):
        assert split_field not in state, "encoder must read one immutable publication"
    handled = body(source, "private void handleVideoFrameAvailable(")
    ordered(handled, "renderVideoFrame(", "finally", "GLES20.glFinish();",
            "finally", "pendingVideoFrames.remove(frameState);", "frameState.snapshot.release();")
    submit = body(source, "private void submitVideoFrame(")
    ordered(submit, "synchronized (sync)", "captureFrameState(cameraId, source)",
            "pendingVideoFrames.add(frameState);", "if (!handler.sendMessage",
            "pendingVideoFrames.remove(frameState);", "frameState.snapshot.release();")
    recorder = body(source, "private class VideoRecorder")
    run = body(recorder, "public void run() {\n            Looper.prepare();")
    ordered(run, "Looper.loop();", "finally", "ready = false;",
            "for (CameraVideoFrameState frameState : pendingVideoFrames)",
            "frameState.snapshot.release();", "pendingVideoFrames.clear();")
    gl = body(source, "public class CameraGLThread")
    finish = body(gl, "public void finish()")
    ordered(finish, "snapshotContextClosed = true;", "clearCameraXSnapshot();", "eglDestroyContext")


def frame_contract(source):
    draw = body(source, "private void onDraw(Integer cameraId,")
    for slot in (0, 1):
        ordered(draw, f"cameraSurface[{slot}].updateTexImage();",
                f"cameraFrameTimestamp[{slot}] = cameraSurface[{slot}].getTimestamp();",
                f"updatedTexImage{slot + 1} = cameraFrameTimestamp[{slot}] <= 0")
    ordered(draw, "surfaceIndex == 0 && updatedTexImage1",
            "surfaceIndex == 1 && updatedTexImage2",
            "cameraXRearLensTransition != null && activeCameraFrame",
            "onCameraXRearLensFrame(cameraFrameTimestamp[surfaceIndex]);",
            "videoEncoder.frameAvailable(")
    assert "updateTexImage1 || updateTexImage2 || !snapshotTransition" in draw
    assert "cameraXRearLensTransition == null" in draw
    for marker in ("private boolean captureCameraXSingleSwitchSnapshot()",
                   "private CameraVideoFrameState captureFrameState("):
        assert "!bothCameras || cameraXRearLensTransition != null" in body(source, marker)
    synthetic = body(source, "public void transitionFrameAvailable(")
    ordered(synthetic, "if (cameraXRearLensTransition != null || bothCameras) return;",
            "submitVideoFrame(")
    actual = body(source, "public void frameAvailable(")
    assert "28_000_000" not in actual
    encoded = body(source, "private void renderVideoFrame(")
    assert "renderDualVideoSwitch = !frameState.singleCameraXTransition" in encoded
    assert "|| frameState.replacementFrameReady" in encoded
    assert "frameState.snapshot.hasLiveSource()" in encoded
    assert "(!cameraTextureAvailable || !frameState.hasLiveSource())" in encoded
    assert "&& !frameState.singleCameraXTransition" in encoded


def encoder_color(source, old, new, progress):
    render = body(source, "private void renderVideoFrame(")
    factor = re.search(r'glBlendFunc\(GLES20\.(GL_ONE|GL_SRC_ALPHA),', render)[1]
    # Both production CameraX V2 variants premultiply RGB by alpha.
    shaders = body(source, "private String createFragmentShaderV2(")
    assert "gl_FragColor = vec4(textColor.rgb * alpha, alpha);" in shaders
    assert "gl_FragColor = color * alpha;" in shaders
    return new * progress * (1 if factor == "GL_ONE" else progress) + old * (1 - progress)


class QueueModel:
    """Ownership reference model, checked against ownership_contract before use."""
    def __init__(self, source):
        ownership_contract(source)
        self.live = set()
        self.refs = {}
        self.frames = []
        self.owner_tasks = []
        self.deleted = []
        self.closed = False
        self.preview = None

    def capture(self, texture):
        self.end_preview()
        self.live.add(texture)
        self.refs[texture] = 1
        self.preview = texture

    def enqueue(self):
        self.refs[self.preview] += 1
        self.frames.append(self.preview)

    def release(self, texture):
        self.refs[texture] -= 1
        assert self.refs[texture] >= 0
        if self.refs[texture] == 0 and not self.closed:
            self.owner_tasks.append(texture)

    def end_preview(self):
        if self.preview is not None:
            self.release(self.preview)
            self.preview = None

    def consume(self):
        texture = self.frames.pop(0)
        assert texture in self.live, "queued encoder used deleted snapshot"
        # Model encoder glFinish before release; actual order is source-checked.
        self.release(texture)

    def drain_owner(self):
        while self.owner_tasks:
            texture = self.owner_tasks.pop(0)
            if not self.closed:
                assert self.refs[texture] == 0
                self.live.remove(texture)
                self.deleted.append(texture)

    def shutdown_preview(self):
        self.closed = True
        self.end_preview()


class CameraSnapshotPipelineTests(unittest.TestCase):
    def test_publication_consumption_and_shutdown_contracts(self):
        ownership_contract(SOURCE)

    def test_successful_active_frame_and_scoped_dual_guards(self):
        frame_contract(SOURCE)

    def test_equal_color_and_crossfade_algebra(self):
        for old, new in ((1., 1.), (.2, .8), (.8, .2)):
            for p in (0., .25, .5, .75, 1.):
                self.assertAlmostEqual(encoder_color(SOURCE, old, new, p), old * (1-p) + new * p)
        render = body(SOURCE, "private void renderVideoFrame(")
        ordered(render, "GLES20.glBlendFunc(GLES20.GL_ONE,",
                "drawCameraXSnapshot(frameState, vertexBuffer);",
                "GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA,", "overlayHelper.render();")

    def test_legacy_buffer_fallback_and_consumer_buffer_positions(self):
        state = body(SOURCE, "private CameraVideoFrameState captureFrameState(")
        self.assertRegex(state, r"cameraTextureBuffers\[index\] != null\s*"
                               r"\? cameraTextureBuffers\[index\] : InstantCameraView.this.textureBuffer")
        frame = body(SOURCE, "private static final class CameraVideoFrameState")
        for statement in ("snapshot.textureBuffer.duplicate()", "liveTextureBuffer.duplicate()",
                          "liveSTMatrix.clone()", "liveMVPMatrix.clone()"):
            self.assertIn(statement, frame)
        encoder_draw = body(SOURCE, "private void drawCameraXSnapshot(CameraVideoFrameState")
        self.assertIn("frameState.snapshotTextureBuffer", encoder_draw)
        self.assertNotIn("cameraXSnapshotTextureBuffer", encoder_draw)
        guard = body(SOURCE, "boolean hasLiveSource()")
        self.assertIn("!snapshotContextClosed && isCurrentGeneration()", guard)

    def test_four_rapid_transitions_with_backlogged_encoder(self):
        q = QueueModel(SOURCE)
        for texture in range(1, 5):
            q.capture(texture)
            for _ in range(3):
                q.enqueue()
            q.drain_owner()
        q.end_preview()  # Final image must retire even without a fifth switch.
        self.assertEqual(q.live, {1, 2, 3, 4})
        for texture in range(1, 5):
            for _ in range(2):
                q.consume()
                q.drain_owner()
                self.assertIn(texture, q.live)
            q.consume()
            self.assertIn(texture, q.live)  # Deletion belongs to owner GL queue.
            q.drain_owner()
            self.assertNotIn(texture, q.live)
        self.assertEqual(q.live, set())

    def test_shutdown_with_four_queued_generations_and_abandoned_frames(self):
        q = QueueModel(SOURCE)
        for texture in range(1, 5):
            q.capture(texture)
            q.enqueue()
            q.enqueue()
        q.shutdown_preview()
        while q.frames:
            q.consume()
            q.drain_owner()
        self.assertEqual(q.deleted, [])
        self.assertEqual(q.live, {1, 2, 3, 4})  # Surviving encoder share group owns them.
        self.assertTrue(all(count == 0 for count in q.refs.values()))
        q.live.clear()  # Last EGL context destruction, not a preview-side delete.

        q = QueueModel(SOURCE)
        q.capture(9)
        q.enqueue()
        q.end_preview()
        q.release(q.frames.pop())  # Encoder Looper abandons an undrawn message.
        q.shutdown_preview()  # Owner deletion was queued before teardown.
        q.drain_owner()
        self.assertEqual(q.deleted, [])
        self.assertIn(9, q.live)

    def test_ownership_negative_controls(self):
        mutations = [
            ("snapshot.retain()", "true"),
            ("if (references == 0 || --references != 0) return;", "if (references == 0) return;"),
            ("cameraXSingleSwitchSnapshotTimestamp = 0;", "// stale timestamp"),
            ("cameraXSingleSwitchSnapshotHandle = snapshot;", "// missing publication"),
            ("snapshotContextClosed = true;", "snapshotContextClosed = false;"),
            ("pendingVideoFrames.clear();", "// abandoned references"),
        ]
        for before, after in mutations:
            with self.subTest(mutation=before):
                self.assertIn(before, SOURCE)
                with self.assertRaises(AssertionError):
                    ownership_contract(SOURCE.replace(before, after, 1))

    def test_frame_and_alpha_negative_controls(self):
        for before, after in (
            ("cameraXRearLensTransition != null && activeCameraFrame", "cameraXRearLensTransition != null"),
            ("if (cameraXRearLensTransition != null || bothCameras) return;", "// synthetic rear frames"),
            ("renderDualVideoSwitch = !frameState.singleCameraXTransition", "renderDualVideoSwitch = true"),
        ):
            with self.subTest(mutation=before), self.assertRaises(AssertionError):
                frame_contract(SOURCE.replace(before, after, 1))
        bad = SOURCE.replace("GLES20.glBlendFunc(GLES20.GL_ONE,", "GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA,", 1)
        self.assertEqual(encoder_color(bad, 1., 1., .5), .75)
        self.assertNotEqual(encoder_color(bad, 1., 1., .5), 1.)


if __name__ == "__main__":
    unittest.main()
