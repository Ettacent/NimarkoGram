import pathlib
import threading
import types
import unittest

REPO = pathlib.Path(__file__).resolve().parents[4]
ENGINE = (
    REPO
    / "TMessagesProj/src/main/java/app/nimarkogram/messenger/plugins"
    / "PythonPluginsEngine.java"
)
PYTHON = REPO / "TMessagesProj/src/main/python"

def _between(source, start, end):
    start_index = source.index(start)
    return source[start_index:source.index(end, start_index)]

class PythonEngineMonitorTokenlessCleanupRegressionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.engine = ENGINE.read_text(encoding="utf-8")
        cls.intents = (PYTHON / "intents.py").read_text(encoding="utf-8")
        cls.file_utils = (PYTHON / "file_utils.py").read_text(encoding="utf-8")

    def test_lifecycle_admission_timeout_and_settlement_share_monitor(self):
        operation = _between(
            self.engine,
            "private static final class LifecycleOperation",
            "private static final class LifecyclePendingException",
        )
        self.assertIn("final Object monitor = new Object()", operation)
        self.assertIn(
            "final ArrayList<Runnable> deferredActions", operation
        )
        self.assertIn("boolean deferredAdmissionClosed", operation)
        self.assertNotIn("CopyOnWriteArrayList", operation)

        defer = _between(
            self.engine,
            "private boolean deferUntilLifecycleSettled(",
            "private LifecycleOperation getTimedOutLifecycle(",
        )
        monitor = defer.index("synchronized (operation.monitor)")
        self.assertGreater(defer.index("operation.deferredActions.add(action)"), monitor)
        self.assertGreater(defer.index("operation.timedOut.get()"), monitor)
        self.assertGreater(
            defer.index("operation.retirementExpired.get()"), monitor
        )
        self.assertGreater(
            defer.index("operation.deferredAdmissionClosed"), monitor
        )

        settle = _between(
            self.engine,
            "private void settleLifecycleOperation(",
            "private void scheduleActualReturn(",
        )
        settle_monitor = settle.index("synchronized (operation.monitor)")
        self.assertGreater(
            settle.index("operation.settled.compareAndSet(false, true)"),
            settle_monitor,
        )
        self.assertGreater(
            settle.index("operation.deferredActions.clear()"), settle_monitor
        )

        timeout = _between(
            self.engine,
            "private void markLifecycleTimedOut(",
            "private void scheduleRuntimeRetirement(",
        )
        timeout_monitor = timeout.index("synchronized (operation.monitor)")
        closed = timeout.index("operation.deferredAdmissionClosed = true")
        timed_out = timeout.index("operation.timedOut.set(true)")
        released = timeout.index(
            "releaseDeferredActionsAfterTimeout(operation)"
        )
        self.assertGreater(closed, timeout_monitor)
        self.assertGreater(timed_out, closed)
        self.assertGreater(released, timed_out)

        expiry = _between(
            self.engine,
            "private void expireRuntimeRetirement(",
            "private void schedulePluginUnloadAfterQuiescence(",
        )
        self.assertIn("synchronized (operation.monitor)", expiry)
        self.assertIn("operation.deferredAdmissionClosed = true", expiry)
        self.assertIn("releaseDeferredActionsAfterTimeout(operation)", expiry)
        self.assertNotIn("deferredAdmissionClosed = false", self.engine)

    def test_python_start_pine_and_dev_server_have_no_abba_order(self):
        self.assertIn(
            "private static final Object PINE_INIT_LOCK = new Object()",
            self.engine,
        )
        self.assertIn(
            "private final Object devServerLock = new Object()", self.engine
        )

        pine = _between(
            self.engine,
            "private boolean ensurePineReady()",
            "private void initPython()",
        )
        self.assertIn("synchronized (PINE_INIT_LOCK)", pine)
        self.assertNotIn("synchronized (this)", pine)
        self.assertNotIn("private synchronized boolean", pine)

        dev_server = _between(
            self.engine,
            "private void runDevServer()",
            "private void stopDevServer()",
        )
        get_python = dev_server.index("final Python current = getPython()")
        dev_lock = dev_server.index("synchronized (devServerLock)")
        self.assertLess(get_python, dev_lock)
        self.assertNotIn("private synchronized void", dev_server)
        self.assertNotIn("getPython()", dev_server[dev_lock:])

        stop_server = _between(
            self.engine,
            "private void stopDevServer()",
            "private void revokeDevInstallBridge(",
        )
        self.assertIn("synchronized (devServerLock)", stop_server)
        self.assertNotIn("getPython()", stop_server)
        self.assertNotIn("synchronized (this)", self.engine)

    def test_none_cleanup_matches_only_legacy_tokenless_records(self):
        intents_cleanup = _between(
            self.intents,
            "    def remove_by_plugin(self, plugin_id, runtime_token=None):",
            "    def unhandle(self, handler_id):",
        )
        self.assertIn(
            "info.runtime_token == runtime_token", intents_cleanup
        )
        self.assertIn("record_token == runtime_token", intents_cleanup)
        self.assertNotIn("runtime_token is None", intents_cleanup)

        files_cleanup = _between(
            self.file_utils,
            "    def remove_by_plugin(self, plugin_id, runtime_token=None):",
            "    def get_handler_for_extension(self, ext):",
        )
        self.assertIn(
            "v.get('runtime_token') == runtime_token", files_cleanup
        )
        self.assertNotIn("runtime_token is None", files_cleanup)

        class FakeIntentsController:
            removed = []
            global_decrements = 0

            @classmethod
            def getInstance(cls):
                return cls

            @classmethod
            def removeIntentHook(cls, handler_id):
                cls.removed.append(handler_id)

            @classmethod
            def decrementGlobals(cls):
                cls.global_decrements += 1

        namespace = {"IntentsController": FakeIntentsController}
        exec("class Subject:\n" + intents_cleanup, namespace)
        intents = namespace["Subject"]()
        intents._lock = threading.RLock()
        old_token = object()
        new_token = object()
        intents._handlers = {
            "legacy": types.SimpleNamespace(
                plugin_id="plugin", runtime_token=None
            ),
            "old": types.SimpleNamespace(
                plugin_id="plugin", runtime_token=old_token
            ),
            "new": types.SimpleNamespace(
                plugin_id="plugin", runtime_token=new_token
            ),
            "other": types.SimpleNamespace(
                plugin_id="other", runtime_token=None
            ),
        }
        callback = lambda: None
        intents._global_before = [
            ("legacy-global", callback, 0, "plugin", None),
            ("old-global", callback, 0, "plugin", old_token),
            ("new-global", callback, 0, "plugin", new_token),
        ]
        intents._global_after = []

        intents.remove_by_plugin("plugin", None)
        self.assertEqual(
            {"old", "new", "other"}, set(intents._handlers)
        )
        self.assertEqual(
            {"old-global", "new-global"},
            {record[0] for record in intents._global_before},
        )
        intents.remove_by_plugin("plugin", old_token)
        self.assertEqual({"new", "other"}, set(intents._handlers))
        self.assertEqual(
            ["new-global"],
            [record[0] for record in intents._global_before],
        )

        exec("class Subject:\n" + files_cleanup, namespace)
        files = namespace["Subject"]()
        files._lock = threading.RLock()
        files._by_id = {
            "legacy": {
                "plugin_id": "plugin",
                "runtime_token": None,
                "extensions": [".legacy"],
            },
            "old": {
                "plugin_id": "plugin",
                "runtime_token": old_token,
                "extensions": [".old"],
            },
            "new": {
                "plugin_id": "plugin",
                "runtime_token": new_token,
                "extensions": [".new"],
            },
        }
        files._by_ext = {
            ".legacy": "legacy", ".old": "old", ".new": "new"
        }
        files.remove_by_plugin("plugin", None)
        self.assertEqual({"old", "new"}, set(files._by_id))
        self.assertEqual({".old", ".new"}, set(files._by_ext))
        files.remove_by_plugin("plugin", old_token)
        self.assertEqual({"new"}, set(files._by_id))
        self.assertEqual({".new"}, set(files._by_ext))

if __name__ == "__main__":
    unittest.main()
