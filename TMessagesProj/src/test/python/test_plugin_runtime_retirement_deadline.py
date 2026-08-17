import pathlib
import unittest

REPO = pathlib.Path(__file__).resolve().parents[4]
JAVA = (
    REPO
    / "TMessagesProj/src/main/java/app/nimarkogram/messenger/plugins"
)

class PluginRuntimeRetirementDeadlineTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.engine = (JAVA / "PythonPluginsEngine.java").read_text()
        cls.controller = (JAVA / "PluginsController.java").read_text()

    def test_deadline_starts_before_quiescence_and_off_is_idempotent(self):
        unload = self.engine[
            self.engine.index("private boolean unloadPluginNow("):
            self.engine.index(
                "@Override\n    public void setPluginEnabled",
                self.engine.index("private boolean unloadPluginNow("),
            )
        ]
        self.assertLess(
            unload.index("scheduleRuntimeRetirementDeadline(operation)"),
            unload.index("schedulePluginUnloadAfterQuiescence(operation)"),
        )

        toggle = self.engine[
            self.engine.index("private void setPluginEnabled("):
            self.engine.index(
                "private void callOnPluginUnloadWithTimeout(",
                self.engine.index("private void setPluginEnabled("),
            )
        ]
        self.assertLess(
            toggle.index("if (!z && retiring != null"),
            toggle.index("if (deferUntilLifecycleSettled("),
        )

    def test_deadline_is_not_hosted_on_the_blockable_plugins_queue(self):
        deadline = self.engine[
            self.engine.index(
                "private void scheduleRuntimeRetirementDeadline("):
            self.engine.index(
                "private void schedulePluginUnloadAfterQuiescence(")
        ]
        self.assertIn("LIFECYCLE_DEADLINE_EXECUTOR.schedule(", deadline)
        self.assertIn("PYTHON_RUNTIME_ABANDONED.set(true)", deadline)
        self.assertIn("ABANDONED_RUNTIME_PLUGIN_IDS.add(", deadline)
        self.assertIn("scheduleRuntimeRetirement(operation)", deadline)
        self.assertNotIn("pluginsQueue.postRunnable", deadline)

    def test_expired_runtime_cannot_start_late_unload_or_replacement(self):
        quiescence = self.engine[
            self.engine.index(
                "private void schedulePluginUnloadAfterQuiescence("):
            self.engine.index("private void finalizeRuntimeRetirement(")
        ]
        self.assertIn("operation.retirementExpired.get()", quiescence)

        loader = self.engine[
            self.engine.index("private void loadPlugin("):
            self.engine.index(
                "PluginDebugLog.log(\"loadPlugin START",
                self.engine.index("private void loadPlugin("),
            )
        ]
        self.assertIn("PYTHON_RUNTIME_ABANDONED.get()", self.engine)

    def test_pending_toggle_callbacks_are_released_exactly_once(self):
        expire = self.engine[
            self.engine.index("private void expireRuntimeRetirement("):
            self.engine.index(
                "private void schedulePluginUnloadAfterQuiescence(")
        ]
        self.assertIn(
            "completePluginToggleForAbandonedRuntime(", expire)
        self.assertIn("releaseDeferredActionsAfterTimeout(operation)", expire)

        completion = self.controller[
            self.controller.index(
                "public void completePluginToggleForAbandonedRuntime("):
            self.controller.index(
                "/**\n     * Watchdog/emergency OFF publication",
                self.controller.index(
                    "public void completePluginToggleForAbandonedRuntime("),
            )
        ]
        self.assertIn("pendingToggleState.remove(pluginId)", completion)
        self.assertIn("drainPendingToggleCallbacksLocked(pluginId)", completion)
        self.assertIn("enablingInProgress.remove(pluginId)", completion)
        self.assertIn("requestedState ? error : null", completion)

if __name__ == "__main__":
    unittest.main()
