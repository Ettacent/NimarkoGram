import pathlib
import unittest

REPO = pathlib.Path(__file__).resolve().parents[4]
JAVA = (
    REPO
    / "TMessagesProj/src/main/java/app/nimarkogram/messenger/plugins"
)

def read(relative):
    return (JAVA / relative).read_text()

class ControllerExactCallbackRetirementTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.controller = read("PluginsController.java")
        cls.runnable = read("utils/PythonRunnable.java")
        cls.request = read("utils/PythonRequestDelegate.java")
        cls.menu = read("hooks/MenuItemRecord.java")
        cls.intents = read("intents/IntentsController.java")

    def test_shutdown_deadline_and_finalization_do_not_wait_on_plugins_queue(self):
        shutdown = self.controller[
            self.controller.index(
                "public void shutdown(final Runnable runnable)")
            : self.controller.index(
                "/**\n     * Final shutdown publication")
        ]
        deadline = shutdown.index(
            "AndroidUtilities.runOnUIThread(\n"
            "                timeout, ENGINE_SHUTDOWN_TIMEOUT_MS)")
        queue_post = shutdown.index(
            "Utilities.pluginsQueue.postRunnable(")
        self.assertLess(deadline, queue_post)
        timeout = shutdown[
            shutdown.index("final Runnable timeout = () ->")
            : shutdown.index("Runnable finishShutdown = () ->")
        ]
        self.assertIn("finishControllerShutdown();", timeout)
        self.assertNotIn("Utilities.pluginsQueue", timeout)
        self.assertIn("shutdownRequiresProcessRestart = true", timeout)

        init = self.controller[
            self.controller.index(
                "public void init(final boolean startWithSafeMode")
            : self.controller.index(
                "NativeCrashHandler.schedulePreviousExitDiagnostics()")
        ]
        self.assertIn("if (shutdownRequiresProcessRestart)", init)
        self.assertIn("triggerRebirth(", init)

    def test_previous_exit_diagnostics_are_scheduled_from_init(self):
        init = self.controller[
            self.controller.index(
                "public void init(final boolean startWithSafeMode")
            : self.controller.index(
                "private void timeoutControllerInitialization")
        ]
        self.assertIn(
            "NativeCrashHandler.schedulePreviousExitDiagnostics();",
            init,
        )
        self.assertNotIn(
            "NativeCrashHandler.checkAndHandleNativeCrash();",
            init,
        )

    def test_initialization_leaves_main_before_binder_disk_and_python_work(self):
        public_init = self.controller[
            self.controller.index(
                "public void init(final boolean startWithSafeMode")
            : self.controller.index(
                "private void startControllerInitialization")
        ]
        self.assertIn(
            "Thread.currentThread() == initializationQueue", public_init)
        self.assertIn(
            "initializationQueue.postRunnable(initializationWork)",
            public_init,
        )
        self.assertIn(
            "timeoutControllerInitialization(attempt)", public_init)
        deadline = public_init.index(
            "ENGINE_INIT_TIMEOUT_MS")
        queue_post = public_init.index(
            "initializationQueue.postRunnable(initializationWork)")
        self.assertLess(deadline, queue_post)

        background = self.controller[
            self.controller.index(
                "private void startControllerInitialization")
            : self.controller.index(
                "private void timeoutControllerInitialization")
        ]
        self.assertIn(
            "PythonPluginsEngine.recoverInterruptedPluginUpdates(this)",
            background,
        )
        self.assertIn(
            "NativeCrashHandler.lastExitWasBenignKill()", background)
        self.assertIn("engine.init(engineDone)", background)

    def test_preparing_callbacks_are_owned_and_drained_by_exact_slot(self):
        slot = self.controller[
            self.controller.index("private static final class RuntimeSlot")
            : self.controller.index(
                "private final AtomicLong nextRuntimeInstanceId")
        ]
        self.assertIn(
            "ArrayList<RuntimeCallbackHolder> callbackHolders", slot)
        self.assertIn(
            "ArrayList<RuntimeCallbackHolder> preparingCallbacks", slot)

        commit = self.controller[
            self.controller.index("public boolean commitPluginRuntime(")
            : self.controller.index(
                "public void revokePluginRuntime(")
        ]
        self.assertIn("slot.state = RuntimeState.ACTIVE", commit)
        self.assertIn("slot.preparingCallbacks.clear()", commit)
        self.assertIn("holder.onPluginRuntimeActive()", commit)

        revoke = self.controller[
            self.controller.index(
                "private void revokeRuntimeSlotLocked(")
            : self.controller.index(
                "/** Latest requested state")
        ]
        self.assertIn("holder.revokePluginRuntime()", revoke)
        self.assertIn("slot.callbackHolders.clear()", revoke)
        self.assertIn("slot.preparingCallbacks.clear()", revoke)

    def test_callback_holders_use_slot_signal_and_reject_raw_pyproxy(self):
        for source in (self.runnable, self.request):
            self.assertIn(
                "PluginsController.RuntimeCallbackHolder", source)
            self.assertIn(
                ".registerRuntimeCallbackHolder(runtimeToken, this)",
                source,
            )
            self.assertIn(
                "controller.deferRuntimeCallback(", source)
            self.assertIn(
                "void onPluginRuntimeActive()", source)
            self.assertIn(
                "void revokePluginRuntime()", source)
            self.assertNotIn("LIFECYCLE_RETRY_DELAY_MS", source)
            self.assertNotIn("postDelayed(", source)
        self.assertIn("callback instanceof PyProxy", self.request)
        self.assertIn(
            "Raw PyProxy RequestDelegate is not safe to retain",
            self.request,
        )

    def test_menu_replacement_removes_old_type_and_releases_exact_callback(self):
        self.assertIn(
            "public void releaseCallback(", self.menu)
        self.assertIn(
            "expectedRuntime.equals(this.runtimeToken)", self.menu)
        self.assertIn("this.onClickCallback = null", self.menu)
        self.assertNotIn(".close()", self.menu)

        add = self.controller[
            self.controller.index("public String addMenuItem(")
            : self.controller.index("public boolean removeMenuItem(")
        ]
        self.assertIn(
            "this.menuItemsByMenuType.entrySet()", add)
        self.assertIn("oldItems.remove(stale)", add)
        self.assertIn(
            "stale.releaseCallback(stale.runtimeToken)", add)

    def test_intent_counts_keep_no_arg_compatibility_and_exact_ownership(self):
        self.assertIn("public void incrementGlobals()", self.intents)
        self.assertIn("public void decrementGlobals()", self.intents)
        self.assertIn(
            "globalCountsByRuntime", self.intents)
        self.assertIn("legacyGlobalCount", self.intents)
        self.assertIn(
            "globalCountsByRuntime.remove(runtimeToken)", self.intents)
        decrement = self.intents[
            self.intents.index("public void decrementGlobals()")
            : self.intents.index("private boolean hasWork()")
        ]
        self.assertIn(
            "captureCurrentPluginRuntime()", decrement)
        self.assertIn(
            "legacyGlobalCount.updateAndGet(", decrement)

    def test_exact_settings_detach_clears_borrowed_callbacks_without_close(self):
        detach = self.controller[
            self.controller.index(
                "private PluginCleanup detachPluginRuntimeLocked(\n"
                "            String str, PluginRuntimeToken runtimeToken)")
            : self.controller.index(
                "/**\n     * Complete phase-one notifications")
        ]
        self.assertIn(
            "runtimeToken != null ? runtimeToken : quiescenceToken",
            detach,
        )
        self.assertIn(
            "clearSettingPythonReferences(detachedSettings)", detach)
        self.assertIn(
            ".removeIntentHooksByPluginId(str, detachToken)", detach)
        self.assertNotIn(".close()", detach)

        clear = self.controller[
            self.controller.index(
                "private static void clearSettingPythonReferences(")
            : self.controller.index(
                "/**\n     * Complete phase-one notifications")
        ]
        self.assertIn("item.clearPythonReferences()", clear)
        self.assertNotIn(".close()", clear)

    def test_tokenless_cleanup_cannot_revoke_current_exact_runtime(self):
        cleanup = self.controller[
            self.controller.index("public void cleanupPlugin(String str)")
            : self.controller.index(
                "/**\n     * Phase one of disable")
        ]
        self.assertIn("captureCurrentPluginRuntime()", cleanup)
        self.assertIn(
            "runtimeToken == null\n"
            "                    && currentRuntimeByPlugin.get(str) != null",
            cleanup,
        )
        self.assertIn(
            "Skipped tokenless cleanup for exact runtime", cleanup)

if __name__ == "__main__":
    unittest.main()
