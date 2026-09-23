"""Source-extracted JVM regression tests; no Android/Gradle/APK or real RPCs.

Only transport, storage, queues and views are fakes. Loading/fanout/completion,
cache probes, timeout/cancellation and popup fetch bodies come from production.
"""
import pathlib
import subprocess
import tempfile
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[4]
JAVA = ROOT / "TMessagesProj/src/main/java/org/telegram"
MEDIA = JAVA / "messenger/MediaDataController.java"
ALERT = JAVA / "ui/Components/StickersAlert.java"
FIXTURE = ROOT / "TMessagesProj/src/test/fixtures/StickerLoadingHarness.java"


def method(source, signature):
    start = source.index(signature)
    opening = source.index("{", start)
    depth = 1
    end = opening + 1
    while depth:
        depth += (source[end] == "{") - (source[end] == "}")
        end += 1
    return source[start:end]


class StickerLoadingLifecycleTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.media = MEDIA.read_text()
        cls.alert = ALERT.read_text()
        methods = [
            "public static String inputSetKey(",
            "public void checkStickers(",
            "public TLRPC.TL_messages_stickerSet getStickerSet(TLRPC.InputStickerSet inputStickerSet, Integer hash, boolean cacheOnly, boolean runWhenRemote,",
            "private void fetchStickerSetInternal(",
            "private void processLoadStickersResponse(",
            "public void loadStickers(int type, boolean cache, boolean useHash)",
            "public void loadStickers(int type, boolean cache, boolean force, boolean scheduleIfLoading,",
            "private void putStickersToCache(",
            "private static long calcStickersHash(",
            "private boolean isCurrentStickerLoad(",
            "private void finishLoadingStickers(",
            "private void failLoadingStickers(",
            "private void processLoadedStickers(",
        ]
        extracted = "\n".join(method(cls.media, s) for s in methods)
        request_start = cls.media.index("private static final int STICKER_REQUEST_TIMEOUT_MS")
        request_end = cls.media.index("private void fetchStickerSetInternal(", request_start)
        extracted += cls.media[request_start:request_end]
        # Execute the exact loading cleanup prefix; unrelated drafts/bots are not stubbed.
        cleanup = cls.media[cls.media.index("public void cleanup() {"):cls.media.index("        loadingPinnedMessages.clear();")]
        extracted += cleanup + "}\n"
        recent = cls.media[cls.media.index("public void loadRecents(int type,"):]
        extracted += recent[:recent.index("        if (gif) {")] + "}\n"
        # Use production array declarations, including all seven sticker types.
        for name in ["stickerSets", "stickersByIds"]:
            line = next(line for line in cls.media.splitlines() if f"[] {name} =" in line)
            extracted += line + "\n"
        popup = method(cls.alert, "public void loadStickerSet(boolean force)")
        popup += method(cls.alert, "public void updateStickerSet(")
        dismiss = cls.alert[cls.alert.index("public void dismiss() {"):]
        popup += dismiss[:dismiss.index("        stickersShaker.stopShake")] + "}\n"
        source = FIXTURE.read_text().replace("// @MEDIA_METHODS@", extracted).replace("// @POPUP_METHODS@", popup)
        cls.temp = tempfile.TemporaryDirectory(prefix="sticker-loading-")
        cls.addClassCleanup(cls.temp.cleanup)
        path = pathlib.Path(cls.temp.name) / "StickerLoadingHarness.java"
        path.write_text(source)
        build = subprocess.run(["javac", "-d", cls.temp.name, str(path)], capture_output=True, text=True, timeout=30)
        if build.returncode:
            raise AssertionError(build.stderr)

    def test_deterministic_scenarios(self):
        for scenario in [
            "cache_probe_then_network", "cache_probe_keeps_active_key", "malformed_cache",
            "fetch_error_reentry", "fetch_throwing_consumer", "fetch_unexpected_response",
            "fanout_partial_failure_retry", "fanout_cancel", "fanout_duplicate_ids",
            "rpc_error_hash_zero", "rpc_error_existing_hash", "not_modified",
            "publish_before_finish", "scheduled_reentry", "cleanup_stale_rpc",
            "cleanup_stale_storage", "cleanup_stale_stage", "multiaccount",
            "connected_timeout_retry", "offline_wait_reconnect", "disconnect_pauses_timeout",
            "popup_error_reopen", "popup_cancel_late", "popup_force_generation",
            "popup_cache_supersedes_rpc", "popup_timeout_reopen",
            "processing_failure_retry", "processing_null_name", "cache_parse_failure_retry",
            "cleanup_cache_write",
        ]:
            with self.subTest(scenario=scenario):
                result = subprocess.run(["java", "-ea", "-cp", self.temp.name, "StickerLoadingHarness", scenario],
                                        capture_output=True, text=True, timeout=10)
                self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_integration_boundaries(self):
        constructor = method(self.alert, "public StickersAlert(Context context, BaseFragment baseFragment, TLRPC.InputStickerSet set, TLRPC.TL_messages_stickerSet loadedSet, StickersAlertDelegate stickersAlertDelegate, Theme.ResourcesProvider resourcesProvider, boolean forceRequest)")
        self.assertLess(constructor.index("currentAccount = baseFragment.getCurrentAccount()"), constructor.index("loadStickerSet(forceRequest)"))
        self.assertIn("if (generation != stickerSetGeneration) return;", method(self.media, "private void putStickersToCache("))
        emoji = (JAVA / "ui/Components/EmojiView.java").read_text()
        visible = method(emoji, "\n    public void setVisibility(int visibility)")
        self.assertIn("loadRecents(MediaDataController.TYPE_IMAGE, false, true, false)", visible)
        self.assertIn("if (generation != stickerSetGeneration) return;", method(self.media, "private void saveStickerSetIntoCache("))


if __name__ == "__main__":
    unittest.main()
