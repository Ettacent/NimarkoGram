import pathlib
import unittest

REPO = pathlib.Path(__file__).resolve().parents[4]
JAVA = (
    REPO
    / "TMessagesProj/src/main/java/app/nimarkogram/messenger/plugins"
)

class PluginControllerLifecycleTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.controller = (JAVA / "PluginsController.java").read_text()
        cls.engine = (
            JAVA / "PythonPluginsEngine.java"
        ).read_text()
        cls.watchdog = (
            JAVA / "utils/PluginsWatchdog.java"
        ).read_text()

    def test_revoked_running_runtime_remains_watchdog_visible(self):
        tick = self.watchdog[
            self.watchdog.index("private void tick()"):
            self.watchdog.index(
                "public static final class ExecutionInfo")
        ]
        self.assertIn(
            "controller.isPluginRuntimeExecuting(\n"
            "                                info.runtimeToken)",
            tick,
        )
        self.assertNotIn(
            "getPluginRuntimeTaskDecision(info.runtimeToken)",
            tick,
        )
        owner_check = self.watchdog[
            self.watchdog.index(
                "private boolean isRuntimeStillFrozenOwner("):
        ]
        self.assertIn("executingPlugins.values()", owner_check)
        self.assertIn(
            "controller.isPluginRuntimeExecuting(runtimeToken)",
            owner_check,
        )
        self.assertNotIn(
            "controller.getCurrentPluginRuntime(pluginId)",
            owner_check,
        )

    def test_shutdown_is_bounded_exactly_once_and_stops_watchdog_last(self):
        shutdown = self.controller[
            self.controller.index(
                "public void shutdown(final Runnable runnable)"):
            self.controller.index(
                "private void finishControllerShutdown()")
        ]
        self.assertIn(
            "new ArrayList<>(engines.values())", shutdown)
        self.assertIn("engineSnapshot.isEmpty()", shutdown)
        self.assertIn(
            "new AtomicBoolean(false)", shutdown)
        self.assertIn(
            "ENGINE_SHUTDOWN_TIMEOUT_MS", shutdown)
        self.assertIn(
            "shutdownRequiresProcessRestart = true", shutdown)
        self.assertNotIn("watchdog.stop()", shutdown)

        finish = self.controller[
            self.controller.index(
                "private void finishControllerShutdown()"):
            self.controller.index(
                "private ArrayList<PendingToggleCallback>")
        ]
        self.assertIn("watchdog.stop()", finish)
        self.assertIn(
            "cancelPendingTogglesForLifecycleBoundary()", finish)

    def test_restart_rechecks_runtime_after_shutdown(self):
        restart = self.controller[
            self.controller.index(
                "public void restart(final boolean startWithSafeMode)"):
            self.controller.index(
                "public List<SettingItem> getPluginSettingsList")
        ]
        self.assertGreaterEqual(
            restart.count("requiresProcessRestart()"), 2)
        self.assertIn("failedShutdown", restart)
        self.assertIn("triggerRebirth(", restart)

    def test_dependency_transition_failure_requires_real_process_restart(self):
        requires_restart = self.engine[
            self.engine.index(
                "public boolean requiresProcessRestart()"):
            self.engine.index(
                "/** Process-wide guard",
                self.engine.index(
                    "public boolean requiresProcessRestart()"),
            )
        ]
        self.assertIn(
            "PipController.getInstance()\n"
            "                        .requiresProcessRestart()",
            requires_restart,
        )

    def test_dependencies_bootstrap_before_first_plugin_import(self):
        load_plugins = self.engine[
            self.engine.index(
                "public void loadPlugins(final Runnable runnable)"):
            self.engine.index(
                "private void loadPlugin(String str, String str2)")
        ]
        bootstrap = load_plugins.index(
            "bootstrapRuntimeForPluginStartup()")
        python_module = load_plugins.index(
            'getPython().getModule("sys")')
        plugin_loop = load_plugins.index(
            "for (File file : fileArrListFiles)")
        self.assertLess(bootstrap, python_module)
        self.assertLess(bootstrap, plugin_loop)
        self.assertIn(
            "catch (RuntimeException bootstrapFailure)",
            load_plugins,
        )
        self.assertIn(
            "registerPluginsForSafeMode();",
            load_plugins,
        )

    def test_toggle_callbacks_are_generation_and_epoch_owned(self):
        callback = self.controller[
            self.controller.index(
                "private static final class PendingToggleCallback"):
            self.controller.index(
                "private static final class PluginCleanup")
        ]
        self.assertIn("final int generation;", callback)
        self.assertIn("final long lifecycleEpoch;", callback)

        loop = self.controller[
            self.controller.index(
                "private void runToggleLoop("):
            self.controller.index(
                "private List<PendingToggleCallback> "
                "drainPendingToggleCallbacksLocked")
        ]
        self.assertIn(
            "isControllerLifecycleCurrent(scheduledEpoch)", loop)
        self.assertIn("completionOnce.compareAndSet(false, true)", loop)

        delivery = self.controller[
            self.controller.index(
                "private void deliverToggleCallbacks("):
            self.controller.index(
                "/** Completion for forcibly superseded operations")
        ]
        self.assertIn(
            "pending.generation\n"
            "                                            "
            "== appliedGeneration",
            delivery,
        )
        self.assertIn(
            "pending.lifecycleEpoch\n"
            "                                            "
            "== appliedEpoch",
            delivery,
        )

    def test_init_is_snapshot_based_bounded_and_exactly_once(self):
        init = self.controller[
            self.controller.index(
                "public void init(final boolean startWithSafeMode"):
            self.controller.index(
                "private boolean finishControllerInitialization")
        ]
        self.assertIn(
            "new ArrayList<>(engines.values())", init)
        self.assertIn("engineSnapshot.isEmpty()", init)
        self.assertIn("aggregateCompleted", init)
        self.assertIn("engineCompleted", init)
        self.assertIn("ENGINE_INIT_TIMEOUT_MS", init)
        self.assertNotIn("this.initialized = true", init)

if __name__ == "__main__":
    unittest.main()
