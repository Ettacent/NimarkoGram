import pathlib
import unittest

REPO = pathlib.Path(__file__).resolve().parents[4]
JAVA = (
    REPO
    / "TMessagesProj/src/main/java/app/nimarkogram/messenger/plugins"
)

class PluginDecorOverlayIsolationTests(unittest.TestCase):

    @classmethod
    def setUpClass(cls):
        cls.host = (
            JAVA / "ui/PluginOverlayHost.java"
        ).read_text(encoding="utf-8")
        cls.registry = (
            JAVA / "ui/PluginUiRegistry.java"
        ).read_text(encoding="utf-8")
        cls.runnable = (
            JAVA / "utils/PythonRunnable.java"
        ).read_text(encoding="utf-8")
        cls.engine = (
            JAVA / "PythonPluginsEngine.java"
        ).read_text(encoding="utf-8")

    def test_legacy_decor_children_are_adopted_around_owned_ui_callback(self):
        self.assertIn(
            "PluginUiRegistry.captureDecorChildren()", self.runnable
        )
        self.assertIn(
            "PluginUiRegistry.adoptNewDecorChildren(", self.runnable
        )
        self.assertLess(
            self.runnable.index("callable.call();"),
            self.runnable.index("PluginUiRegistry.adoptNewDecorChildren("),
        )

    def test_overlay_host_is_stable_and_scoped_per_plugin(self):
        self.assertIn(
            "HashMap<String, WeakReference<PluginOverlayHost>>",
            self.registry,
        )
        self.assertIn(
            "getOrCreateOverlayHost(decor, runtimeToken.getPluginId())",
            self.registry,
        )
        self.assertNotIn(
            "decor.removeView(host)", self.registry
        )

    def test_raw_legacy_java_callbacks_are_probed_across_plugin_load(self):
        self.assertIn(
            "PluginUiRegistry.captureDecorChildrenBlocking(750L)",
            self.engine,
        )
        self.assertIn(
            "legacyOverlayProbes.put(runtimeToken, overlayProbe)",
            self.engine,
        )
        self.assertIn(
            "finishLegacyOverlayProbe(runtimeToken);", self.engine
        )
        self.assertIn(
            "PluginUiRegistry.adoptNewDecorChildrenDeferred(",
            self.engine,
        )

    def test_background_view_hierarchy_mutations_are_marshaled_to_main(self):
        self.assertIn(
            "Looper.myLooper() == Looper.getMainLooper()", self.host
        )
        self.assertIn(
            "postMutation(() -> removeViewIfOwned(view))", self.host
        )
        self.assertIn(
            "postMutation(() -> addViewIfDetached(child, index, params))",
            self.host,
        )
        self.assertIn(
            "postMutation(this::removeAllViews)", self.host
        )

    def test_runtime_cleanup_removes_only_the_owned_overlay_view(self):
        self.assertIn(
            "new OverlayViewEntry(child, host)", self.registry
        )
        self.assertIn(
            "host.removeViewIfOwned(view)", self.registry
        )
        self.assertNotIn(
            "host.removeAllViews()", self.registry
        )

if __name__ == "__main__":
    unittest.main()
