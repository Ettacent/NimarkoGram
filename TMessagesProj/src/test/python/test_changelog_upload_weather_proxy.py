"""Focused source contracts and upload-cap model; no Android/Gradle build."""
from pathlib import Path
import re
import unittest

ROOT = Path(__file__).resolve().parents[2] / "main/java/org/telegram"
UPLOAD = (ROOT / "messenger/FileUploadOperation.java").read_text()
WEATHER = (ROOT / "ui/Stories/recorder/Weather.java").read_text()
PROXY = (ROOT / "ui/ProxyListActivity.java").read_text()


def body(source, signature):
    opening = source.index("{", source.index(signature))
    depth = 0
    for token in re.finditer(r'//[^\n]*|/\*[\s\S]*?\*/|"(?:\\.|[^"\\])*"|[{}]', source[opening:]):
        if token[0] == "{":
            depth += 1
        elif token[0] == "}":
            depth -= 1
            if depth == 0:
                return source[opening + 1:opening + token.start()]
    raise AssertionError(signature)


class ChangelogUploadWeatherProxyTests(unittest.TestCase):
    def test_upload_common_gate_after_initialization_before_read_and_iv(self):
        request = body(UPLOAD, "private void startUploadRequest()")
        gate = "if (currentUploadRequetsCount >= maxRequestsCount)"
        self.assertIn(gate + " {\n                return;\n            }", request)
        self.assertLess(request.index("maxRequestsCount ="), request.index(gate))
        self.assertLess(request.index("freeRequestIvs.add("), request.index(gate))
        # Earlier reads reconstruct encryption state when resuming a saved upload;
        # the admission gate must precede the actual next-part read.
        self.assertLess(request.index(gate), request.rindex("stream.read("))
        self.assertLess(request.index(gate), request.index("freeRequestIvs.get(0)"))
        for signature in ("public void start()", "protected void onNetworkChanged("):
            self.assertIn("startUploadRequest();", body(UPLOAD, signature))
        restart = body(UPLOAD, "protected void onNetworkChanged(")
        self.assertLess(restart.index("cleanup();"), restart.index("startUploadRequest();"))
        self.assertIn("stream = null;", body(UPLOAD, "private void cleanup()"))

    def test_burst_and_refill_cap_model_including_encrypted_network_restart(self):
        # Model of the common admission rule, not execution of Android upload code.
        for chunk, budget, burst in ((128, 2048, 8), (512, 2048, 8), (32, 32, 1), (1024, 2048, 8)):
            cap = max(1, budget // chunk)
            for encrypted in (False, True):
                for _restart in range(2):
                    active, ivs = 0, cap
                    for _ in range(burst):
                        if active >= cap:
                            continue
                        if encrypted:
                            self.assertGreater(ivs, 0)
                            ivs -= 1
                        active += 1
                    self.assertEqual(active, min(burst, cap))
                    for _ in range(20):
                        active -= 1
                        if encrypted:
                            ivs += 1
                        if active < cap:
                            if encrypted:
                                self.assertGreater(ivs, 0)
                                ivs -= 1
                            active += 1
                        self.assertLessEqual(active, cap)

    def test_story_fetch_preserves_permission_settings_and_progress_semantics(self):
        fetch = body(WEATHER, "public static void fetch(boolean withProgress")
        self.assertIn("getUserLocation(withProgress", fetch)
        self.assertNotIn("fetchCancellable(", fetch)
        self.assertLess(fetch.index("getUserLocation("), fetch.index("progressDialog.showDelayed(200)"))
        self.assertIn("setOnCancelListener(di -> cancel.run())", fetch)
        self.assertIn("progressDialog.dismissUnless(350)", fetch)
        legacy = body(WEATHER, "public static void getUserLocation(")
        self.assertIn("PermissionRequest.ensureEitherPermission(", legacy)
        self.assertIn("if (l == null && withProgress)", legacy)
        self.assertIn("GpsDisabledAlertText", legacy)
        self.assertIn("ACTION_LOCATION_SOURCE_SETTINGS", legacy)
        self.assertIn("requestLegacyGps(lm, whenGot)", legacy)
        cancellable = body(WEATHER, "public static Runnable fetchCancellable(")
        self.assertIn("locationRequest.run();", cancellable)
        self.assertIn("cancelWeather[0].run();", cancellable)

    def test_location_timeout_retires_listener_before_callback(self):
        location = body(WEATHER, "private static class LocationRequest")
        self.assertIn("TIMEOUT_MS = 15_000", location)
        self.assertIn("timeout = () -> finish(null)", location)
        self.assertIn("runOnUIThread(timeout, TIMEOUT_MS)", location)
        finish = body(location, "private void finish(")
        self.assertLess(finish.index("run();"), finish.index("whenGot.run(location)"))
        cancel = body(location, "public void run()")
        for guard in ("if (finished) return;", "callback = null;", "cancelRunOnUIThread(timeout)", "removeUpdates(listener)"):
            self.assertIn(guard, cancel)
        legacy = body(WEATHER, "public static void getUserLocation(")
        self.assertIn("requestLegacyGps(lm, whenGot);", legacy)
        self.assertNotIn("requestLocationUpdates(", legacy)

    def test_legacy_gps_success_timeout_and_registration_failure_share_cleanup(self):
        gps = body(WEATHER, "private static void requestLegacyGps(")
        finish = body(gps, "private void finish(")
        self.assertIn("if (finished) return;", finish)
        self.assertLess(finish.index("finished = true"), finish.index("callback.run(location)"))
        for cleanup in ("cancelRunOnUIThread(this)", "manager.removeUpdates(this)"):
            self.assertLess(finish.index(cleanup), finish.index("callback.run(location)"))
        self.assertIn("finish(null);", body(gps, "public void run()"))
        self.assertIn("finish(location);", body(gps, "public void onLocationChanged("))
        self.assertIn("runOnUIThread(request, 15_000)", gps)
        self.assertIn("requestLocationUpdates(LocationManager.GPS_PROVIDER, 1, 0, request, Looper.getMainLooper())", gps)
        self.assertNotIn("NETWORK_PROVIDER", gps)
        self.assertIn("request.run();", gps)

    def test_both_proxy_delete_actions_reconcile_before_notification_and_bind(self):
        self.assertNotIn("boolean keptBypass", PROXY)
        for start, end in (("} else if (position == deleteAllRow)", "listView.setOnItemLongClickListener"),
                           ("case MENU_DELETE:", "AlertDialog dialog = builder.create();")):
            section = PROXY[PROXY.index(start):]
            section = section[:section.index(end)]
            self.assertLess(section.index("SharedConfig.deleteProxy(info)"), section.index("reconcileProxyState();"))
            self.assertLess(section.index("reconcileProxyState();"), section.index("postNotificationName("))
            self.assertLess(section.index("reconcileProxyState();"), section.index("updateRows(true)"))
            self.assertIn("if (isOwnWsBypass(info))", section)


if __name__ == "__main__":
    unittest.main()
