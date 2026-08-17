import pathlib
import unittest

ROOT = pathlib.Path(__file__).resolve().parents[4]
JAVA = (
    ROOT
    / "TMessagesProj/src/main/java/app/nimarkogram/messenger/plugins"
)

class PluginStartupMetadataPublicationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.engine = (JAVA / "PythonPluginsEngine.java").read_text()
        cls.controller = (JAVA / "PluginsController.java").read_text()
        cls.cell = (
            JAVA / "ui/components/PluginCell.java"
        ).read_text()

    def test_metadata_is_published_before_python_startup(self):
        init = self.engine[
            self.engine.index("public void init(Runnable runnable)"):
            self.engine.index("@Override\n    public void checkDevServer()")
        ]
        self.assertLess(
            init.index("registerPluginsMetadataOnly("),
            init.index("if (getPython() == null)"),
        )
        self.assertLess(
            init.index("registerPluginsMetadataOnly("),
            init.index("loadPlugins(runnable)"),
        )

    def test_all_candidates_are_published_before_dependencies_and_imports(self):
        load = self.engine[
            self.engine.index("public void loadPlugins("):
            self.engine.index("private void loadPlugin(String str")
        ]
        publish = load.index(
            "getPluginsController().notifyPluginsChanged();",
            load.index("Map<String, File> startupFiles"),
        )
        dependency_phase = load.index(
            "Map<String, List<String>> reqsByPlugin")
        activation_phase = load.index(
            "for (Map.Entry<String, File> entry")
        self.assertLess(publish, dependency_phase)
        self.assertLess(dependency_phase, activation_phase)

    def test_startup_switch_uses_persisted_intent_without_runtime_liveness(self):
        self.assertIn(
            "startupActivations.contains(pluginId)",
            self.controller,
        )
        self.assertIn(
            "controller.getRequestedPluginEnabled(plugin.getId())",
            self.cell,
        )

    def test_temporary_toggle_debug_is_file_io_free(self):
        debug_log = (JAVA / "PluginDebugLog.java").read_text()
        self.assertIn("public static final boolean ENABLED = false", debug_log)
        self.assertNotIn("FileWriter", debug_log)
        self.assertNotIn("Executor", debug_log)
        self.assertNotIn("plugin-toggle-debug", debug_log)

if __name__ == "__main__":
    unittest.main()
