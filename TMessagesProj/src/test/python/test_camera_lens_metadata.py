"""Exercise production lens history/capture logic on the JDK, without an APK build."""

import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path


CAMERA = (Path(__file__).resolve().parents[2] / "main/java"
          / "app/nimarkogram/messenger/camera")
CONTROLLER = (CAMERA / "NimarkoCameraXController.java").read_text()
SESSION = (CAMERA / "NimarkoCameraXSurfaceSession.java").read_text()


def method(source, signature):
    start = source.index(signature)
    opening = source.index("{", start)
    depth, end = 1, opening + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]


HARNESS = r"""
package app.nimarkogram.messenger.camera;

import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.Map;

public final class LensMetadataHarness {
    @interface NonNull {}
    @interface Nullable {}
    static final class Build {
        static final class VERSION { static int SDK_INT = 30; }
        static final class VERSION_CODES { static final int P = 28, R = 30; }
    }
    static final class NimarkoCameraLog {
        static final boolean DEBUG = false;
        static void log(String message) {}
    }
    static final class Key<T> {}
    static class CaptureResult {
        static final Key<Long> SENSOR_TIMESTAMP = new Key<>();
        static final Key<String> LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID = new Key<>();
        static final Key<Float> CONTROL_ZOOM_RATIO = new Key<>();
    }
    static final class TotalCaptureResult extends CaptureResult {
        final Map<Key<?>, Object> values = new HashMap<>();
        boolean throwTimestamp, throwRatio, throwPhysical;
        Runnable duringRead;
        <T> void put(Key<T> key, T value) { values.put(key, value); }
        @SuppressWarnings("unchecked")
        <T> T get(Key<T> key) {
            if (key == SENSOR_TIMESTAMP && duringRead != null) {
                Runnable task = duringRead; duringRead = null; task.run();
            }
            if (key == LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID) {
                check(Build.VERSION.SDK_INT >= 28, "physical key requires API 28");
                if (throwPhysical) throw new IllegalArgumentException();
            }
            if (key == CONTROL_ZOOM_RATIO) {
                check(Build.VERSION.SDK_INT >= 30, "ratio key requires API 30");
                if (throwRatio) throw new IllegalArgumentException();
            }
            if (key == SENSOR_TIMESTAMP && throwTimestamp) throw new IllegalArgumentException();
            return (T) values.get(key);
        }
        long getFrameNumber() { return 1L; }
    }
    static final class CaptureRequest {
        static final Key<Float> CONTROL_ZOOM_RATIO = new Key<>();
        Float ratio;
        boolean throwRatio;
        @SuppressWarnings("unchecked")
        <T> T get(Key<T> key) {
            check(Build.VERSION.SDK_INT >= 30, "request ratio requires API 30");
            if (throwRatio) throw new IllegalArgumentException();
            return (T) ratio;
        }
    }
    static final class CameraCaptureSession {
        abstract static class CaptureCallback {
            public void onCaptureCompleted(CameraCaptureSession session,
                                           CaptureRequest request, TotalCaptureResult result) {}
        }
    }
    static final class Owner {
        final CameraXLensFrameTracker lensFrameTracker = new CameraXLensFrameTracker();
        volatile boolean closed;
        volatile String activePhysicalCameraId, expectedInitialPhysicalCameraId;
        long latestPhysicalFrameTimestampNanos;
        int resets;
        void resetBoundCameraControls() { resets++; }
        PRODUCTION_METHODS
    }

    static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    static TotalCaptureResult result(long timestamp, String physical, Float ratio) {
        TotalCaptureResult r = new TotalCaptureResult();
        r.put(CaptureResult.SENSOR_TIMESTAMP, timestamp);
        r.put(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID, physical);
        r.put(CaptureResult.CONTROL_ZOOM_RATIO, ratio);
        return r;
    }
    static void matching() {
        CameraXLensFrameTracker t = new CameraXLensFrameTracker();
        Object token = t.beginGraph();
        check(t.getLensFrame(1L) == null, "empty metadata");
        t.record(token, "main", 1f, 1_000_000_000L);
        t.record(token, "wide", .6f, 1_080_000_000L);
        t.record(token, "main", .9f, 1_040_000_000L); // delivered out of order
        check(t.getLensFrame(999_999_999L) == null, "never use a future capture");
        CameraXLensFrame exact = t.getLensFrame(1_040_000_000L);
        check(exact.timestampNanos == 1_040_000_000L && exact.zoomRatio == .9f, "exact match");
        check(t.getLensFrame(1_079_999_999L) == exact, "nearest past, not newest arrival");
        check(t.getLensFrame(1_180_000_000L).physicalId.equals("wide"), "100ms inclusive");
        check(t.getLensFrame(1_180_000_001L) == null, "100ms plus 1ns rejected");
        check(t.getLensFrame(0) == null && t.getLensFrame(-1) == null, "invalid target timestamp");
        check(t.getLensFrame(Long.MAX_VALUE) == null, "large ages do not overflow");
        t.record(token, "wide", .7f, 1_080_000_000L);
        check(t.getLensFrame(1_080_000_000L).zoomRatio == .7f, "duplicate updates same timestamp");
    }
    static void bounds() throws Exception {
        CameraXLensFrameTracker t = new CameraXLensFrameTracker();
        Object token = t.beginGraph();
        for (int i = 1; i <= 40; i++) t.record(token, "main", 1f, i * 1_000_000L);
        check(t.getLensFrame(8_000_000L) == null, "32-entry capacity evicts oldest");
        CameraXLensFrame first = t.getLensFrame(9_000_000L);
        check(first != null, "retains 32 entries");
        for (int i = 0; i < 100; i++) t.record(token, "main", 2f, 40_000_000L);
        check(t.getLensFrame(9_000_000L) == first, "duplicates do not consume capacity");
        check(Modifier.isFinal(CameraXLensFrame.class.getModifiers()), "immutable class");
        for (String name : new String[]{"physicalId", "zoomRatio", "timestampNanos"}) {
            int modifiers = CameraXLensFrame.class.getField(name).getModifiers();
            check(Modifier.isPublic(modifiers) && Modifier.isFinal(modifiers), "immutable public field");
        }
        token = t.beginGraph();
        for (float bad : new float[]{Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0f, -1f}) {
            t.record(token, null, bad, 1L);
            check(t.getLensFrame(1L) == null, "no supported metadata returns null");
        }
        t.record(token, "", Float.NaN, 1L);
        check(t.getLensFrame(1L) == null, "empty physical id is unsupported");
        t.record(token, "wide", .6f, 0L);
        t.record(token, "wide", .6f, -1L);
        check(t.getLensFrame(1L) == null, "invalid sensor timestamps rejected");
        t.record(token, "wide", Float.NaN, 2L);
        check(Float.isNaN(t.getLensFrame(2L).zoomRatio), "physical-only sample");
        t.record(token, null, .6f, 3L);
        check(t.getLensFrame(3L).physicalId == null, "ratio-only sample");
    }
    static void capture() {
        Owner o = new Owner();
        Object token = o.lensFrameTracker.beginGraph();
        CameraCaptureSession.CaptureCallback cb = o.createLensCaptureCallback(token);
        CaptureRequest q = new CaptureRequest(); q.ratio = 4f;
        cb.onCaptureCompleted(null, q, result(1_000L, "wide", .6f));
        check(o.getLensFrame(1_000L).zoomRatio == .6f, "capture ratio wins over request");
        cb.onCaptureCompleted(null, q, result(2_000L, "wide", .8f));
        check(o.getLensFrame(2_000L).zoomRatio == .8f, "publish every frame with unchanged id");
        cb.onCaptureCompleted(null, q, result(3_000L, "main", 1f));
        cb.onCaptureCompleted(null, q, result(2_500L, "wide", .9f));
        check("main".equals(o.activePhysicalCameraId), "late frame does not regress startup id");
        check(o.getLensFrame(2_500L).zoomRatio == .9f, "late metadata remains matchable");
        cb.onCaptureCompleted(null, q, result(4_000L, "main", null));
        check(o.getLensFrame(4_000L).zoomRatio == 4f, "completed request ratio fallback");
        TotalCaptureResult bad = result(5_000L, "main", .6f); bad.throwRatio = true;
        cb.onCaptureCompleted(null, q, bad);
        check(o.getLensFrame(5_000L).zoomRatio == 4f, "vendor ratio failure uses request");
        cb.onCaptureCompleted(null, q, result(6_000L, "main", Float.NaN));
        check(o.getLensFrame(6_000L).zoomRatio == 4f, "invalid result ratio uses request");
    }
    static void unsupported() {
        Owner o = new Owner();
        Object token = o.lensFrameTracker.beginGraph();
        CameraCaptureSession.CaptureCallback cb = o.createLensCaptureCallback(token);
        CaptureRequest q = new CaptureRequest(); q.ratio = .6f;
        Build.VERSION.SDK_INT = 28;
        cb.onCaptureCompleted(null, q, result(1_000L, "wide", .6f));
        check(Float.isNaN(o.getLensFrame(1_000L).zoomRatio), "API28 physical only");
        check("wide".equals(o.activePhysicalCameraId), "startup physical tracking survives");
        o.retireLensCaptureGraph();
        token = o.lensFrameTracker.beginGraph(); cb = o.createLensCaptureCallback(token);
        Build.VERSION.SDK_INT = 27;
        cb.onCaptureCompleted(null, q, result(2_000L, "wide", .6f));
        check(o.getLensFrame(2_000L) == null, "API27 no lens keys");
        Build.VERSION.SDK_INT = 30;
        TotalCaptureResult r = result(3_000L, "wide", .6f); r.throwTimestamp = true;
        cb.onCaptureCompleted(null, q, r);
        check(o.getLensFrame(3_000L) == null, "missing timestamp cannot match texture");
        check("wide".equals(o.activePhysicalCameraId), "startup id works without timestamp");
        r = result(4_000L, null, null); r.throwPhysical = true; r.throwRatio = true;
        q.throwRatio = true;
        cb.onCaptureCompleted(null, q, r);
        check(o.getLensFrame(4_000L) == null, "unsupported/throwing vendor fields are harmless");
    }
    static void retirement() {
        Owner o = new Owner();
        check(!o.lensFrameTracker.hasActiveGraph(), "new controller cannot reuse a graph");
        Object token = o.lensFrameTracker.beginGraph();
        check(o.lensFrameTracker.hasActiveGraph(), "prepared graph may be reused");
        CameraCaptureSession.CaptureCallback old = o.createLensCaptureCallback(token);
        CaptureRequest q = new CaptureRequest();
        old.onCaptureCompleted(null, q, result(1_000L, "wide", .6f));
        o.invalidateBoundCameraControls();
        check(!o.lensFrameTracker.hasActiveGraph(), "retired prepared preview must rebuild");
        check(o.resets == 1 && o.activePhysicalCameraId == null, "invalidate retires id and controls");
        check(o.getLensFrame(1_000L) == null, "invalidate clears history");
        Object fresh = o.lensFrameTracker.beginGraph();
        check(fresh != token, "unique graph identity");
        old.onCaptureCompleted(null, q, result(2_000L, "wide", .6f));
        check(o.getLensFrame(2_000L) == null && o.activePhysicalCameraId == null,
                "old callback cannot publish into new graph");
        CameraCaptureSession.CaptureCallback cb = o.createLensCaptureCallback(fresh);
        TotalCaptureResult raced = result(3_000L, "wide", .6f);
        raced.duringRead = () -> { o.retireLensCaptureGraph(); o.lensFrameTracker.beginGraph(); };
        cb.onCaptureCompleted(null, q, raced);
        check(o.getLensFrame(3_000L) == null && o.activePhysicalCameraId == null,
                "retirement during metadata extraction cannot resurrect graph");
        fresh = o.lensFrameTracker.beginGraph(); cb = o.createLensCaptureCallback(fresh);
        cb.onCaptureCompleted(null, q, result(4_000L, "main", 1f));
        o.closed = true;
        cb.onCaptureCompleted(null, q, result(5_000L, "wide", .6f));
        check(o.getLensFrame(5_000L) == null && "main".equals(o.activePhysicalCameraId),
                "closed owner rejects publication and lookup");
    }
    static void rapidReversals() {
        Owner o = new Owner();
        CameraCaptureSession.CaptureCallback cb =
                o.createLensCaptureCallback(o.lensFrameTracker.beginGraph());
        CaptureRequest q = new CaptureRequest();
        cb.onCaptureCompleted(null, q, result(1_000_000_000L, "main", 1.2f));
        CameraXLensFrame snapshot = o.getLensFrame(1_000_000_000L);
        // Controls cross 1x before the HAL switches; retain that distinction.
        cb.onCaptureCompleted(null, q, result(1_033_000_000L, "main", .9f));
        check(o.getLensFrame(1_033_000_000L).physicalId.equals("main"),
                "ratio crossing does not fabricate a physical switch");
        cb.onCaptureCompleted(null, q, result(1_066_000_000L, "wide", .7f));
        cb.onCaptureCompleted(null, q, result(1_099_000_000L, "main", 1.1f));
        // A lagging capture callback must not overwrite the reversal's current ID.
        cb.onCaptureCompleted(null, q, result(1_080_000_000L, "wide", .8f));
        check("main".equals(o.activePhysicalCameraId), "reverse keeps latest sensor identity");
        check(o.getLensFrame(1_075_000_000L).physicalId.equals("wide"),
                "latched wide frame never matches future main metadata");
        check(o.getLensFrame(1_099_000_000L).physicalId.equals("main"),
                "reversal exact match wins over callback arrival order");
        check(snapshot.zoomRatio == 1.2f && snapshot.physicalId.equals("main"),
                "frozen metadata stays immutable during continuous zoom");
        for (int i = 1; i <= 20; i++) {
            float ratio = 1.1f + i * .02f;
            long timestamp = 1_100_000_000L + i * 1_000_000L;
            cb.onCaptureCompleted(null, q, result(timestamp, "main", ratio));
            check(o.getLensFrame(timestamp).zoomRatio == ratio,
                    "same-side live digital zoom publishes every capture");
        }
    }
    public static void main(String[] args) throws Exception {
        switch (args[0]) {
            case "matching": matching(); break;
            case "bounds": bounds(); break;
            case "capture": capture(); break;
            case "unsupported": unsupported(); break;
            case "retirement": retirement(); break;
            case "rapid": rapidReversals(); break;
            default: throw new AssertionError(args[0]);
        }
    }
}
"""


class CameraLensMetadataTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not shutil.which("javac") or not shutil.which("java"):
            raise unittest.SkipTest("JDK required")
        cls.temp = tempfile.TemporaryDirectory(prefix="camera-lens-metadata-")
        cls.addClassCleanup(cls.temp.cleanup)
        cls.root = Path(cls.temp.name)
        methods = "\n".join(method(CONTROLLER, signature) for signature in (
            "private CameraCaptureSession.CaptureCallback createLensCaptureCallback(",
            "private void retireLensCaptureGraph()",
            "private void invalidateBoundCameraControls()",
            "public CameraXLensFrame getLensFrame(",
        ))
        harness = cls.root / "LensMetadataHarness.java"
        harness.write_text(HARNESS.replace("PRODUCTION_METHODS", methods))
        subprocess.run([
            "javac", "-d", str(cls.root),
            str(CAMERA / "CameraXLensFrame.java"),
            str(CAMERA / "CameraXLensFrameTracker.java"), str(harness),
        ], check=True, capture_output=True, text=True, timeout=30)

    def run_harness(self, case):
        subprocess.run([
            "java", "-cp", str(self.root),
            "app.nimarkogram.messenger.camera.LensMetadataHarness", case,
        ], check=True, capture_output=True, text=True, timeout=10)

    def test_timestamp_matching(self):
        self.run_harness("matching")

    def test_bounded_history_and_immutable_fields(self):
        self.run_harness("bounds")

    def test_per_frame_capture_ratio_and_request_fallback(self):
        self.run_harness("capture")

    def test_unsupported_metadata_and_api_guards(self):
        self.run_harness("unsupported")

    def test_invalidation_close_and_stale_callback_race(self):
        self.run_harness("retirement")

    def test_rapid_reversals_and_continuous_same_side_zoom(self):
        self.run_harness("rapid")

    def test_session_api_and_no_static_zoom_override(self):
        getter = method(SESSION, "public CameraXLensFrame getLensFrame(")
        self.assertIn("closed ? null : controller.getLensFrame(surfaceTimestampNanos)", getter)
        callback = method(CONTROLLER, "private CameraCaptureSession.CaptureCallback createLensCaptureCallback(")
        self.assertNotIn("getZoomState", callback)
        self.assertNotRegex(CONTROLLER, r"setCaptureRequestOption\(\s*CaptureRequest\.CONTROL_ZOOM_RATIO")

    def test_startup_independent_capture_and_concurrent_diagnostics(self):
        self.assertIn("selectedInfo.isLogicalMultiCameraSupported()", CONTROLLER)
        self.assertIn("(observeLensMetadata || startFromUltraWide)", CONTROLLER)
        self.assertEqual(CONTROLLER.count("setSessionCaptureCallback("), 1)
        self.assertIn("installConcurrentCamera2Diagnostics(previewExtender,", CONTROLLER)
        diagnostics = method(CONTROLLER, "private static void installConcurrentCamera2Diagnostics(")
        self.assertIn("extender.setDeviceStateCallback(", diagnostics)
        self.assertIn("extender.setSessionStateCallback(", diagnostics)
        self.assertNotIn("setSessionCaptureCallback", diagnostics)

    def test_graph_lifecycle_and_prepared_attachment(self):
        for signature in ("public void closeCamera()", "private void clearPreparedUseCases()",
                          "private void invalidateBoundCameraControls()"):
            self.assertIn("retireLensCaptureGraph();", method(CONTROLLER, signature))
        bind = method(CONTROLLER, "private boolean bindPreparedUseCases()")
        self.assertIn("attachBoundCamera(boundCamera, true)", bind)
        attach = method(CONTROLLER, "private void attachBoundCamera(@Nullable Camera camera, boolean preparedGraph)")
        self.assertIn("if (preparedGraph && camera != null)", attach)
        self.assertIn("resetBoundCameraControls();", attach)
        self.assertIn("backController.attachBoundCamera(backController.boundCamera, true)", CONTROLLER)
        self.assertIn("frontController.attachBoundCamera(frontController.boundCamera, true)", CONTROLLER)
        self.assertIn("captureGraphToken = lensFrameTracker.beginGraph();", CONTROLLER)

    def test_retired_prepared_preview_is_not_reused(self):
        self.assertRegex(CONTROLLER, r"if \(!rebuild && boundPreview != null && boundSelector != null"
                         r"\s*&& lensFrameTracker\.hasActiveGraph\(\)\) return true;")
        release = method(CONTROLLER, "public void unbindForSurfaceRebind()")
        self.assertIn("invalidateBoundCameraControls();", release)
        self.assertIn("invalidateBoundCameraControls();",
                      method(CONTROLLER, "private void unbindOwnUseCases()"))
        self.assertIn("invalidateBoundCameraControls();",
                      method(CONTROLLER, "public void onCameraXOwnershipInvalidated("))


if __name__ == "__main__":
    unittest.main()
