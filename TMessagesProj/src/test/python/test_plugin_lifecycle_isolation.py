import concurrent.futures
import functools
import importlib.util
import _thread
import pathlib
import sys
import threading
import types
import unittest
from unittest import mock

REPO = pathlib.Path(__file__).resolve().parents[4]

class _RuntimeToken:
    def __init__(self, plugin_id, generation=1):
        self.plugin_id = plugin_id
        self.generation = generation

    def getPluginId(self):
        return self.plugin_id

class _FakeWatchdog:
    def __init__(self, controller):
        self.controller = controller
        self.executing = []
        self.failures = []

    def reset(self):
        self.executing.clear()
        self.failures.clear()

    def onPluginExecutionStarted(self, plugin_id):
        token = self.controller.captureCurrentPluginRuntime()
        self.executing.append((plugin_id, token))
        self.controller.events.append(
            ('started', plugin_id, token))

    def onPluginExecutionFailed(self, plugin_id, failure):
        token = self.controller.captureCurrentPluginRuntime()
        self.failures.append((plugin_id, token, failure))
        self.controller.events.append(
            ('failed', plugin_id, token, failure))

    def onPluginExecutionFinished(self, plugin_id):
        current_id, token = self.executing.pop()
        if current_id != plugin_id:
            raise AssertionError(
                f'finished {plugin_id}, current callback is {current_id}')
        self.controller.events.append(
            ('finished', plugin_id, token))

class _FakeController:
    def __init__(self):
        self.allowed = True
        self.task_decision = None
        self.entered = []
        self.exited = []
        self.events = []
        self.runtime_scopes = []
        self.watchdog = _FakeWatchdog(self)

    def captureCurrentPluginRuntime(self):
        if self.runtime_scopes:
            return self.runtime_scopes[-1]
        return None

    def isPluginRuntimeCallbackAllowed(self, token):
        return self.allowed

    def getPluginRuntimeTaskDecision(self, token):
        if self.task_decision is not None:
            return self.task_decision
        return 2 if self.allowed else 0

    def enterPluginRuntime(self, token):
        if not self.allowed:
            return False
        self.entered.append(token)
        self.runtime_scopes.append(token)
        self.events.append(('enter', token))
        return True

    def exitPluginRuntime(self, token):
        if not self.runtime_scopes or self.runtime_scopes[-1] is not token:
            raise AssertionError('runtime scopes exited out of order')
        self.runtime_scopes.pop()
        self.exited.append(token)
        self.events.append(('exit', token))

    def getWatchdog(self):
        return self.watchdog

class PluginLifecycleIsolationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.controller = _FakeController()

        class PluginsController:
            RUNTIME_TASK_DROP = 0
            RUNTIME_TASK_WAIT = 1
            RUNTIME_TASK_RUN = 2

            @staticmethod
            def getInstance():
                return cls.controller

        module_names = (
            'app',
            'app.nimarkogram',
            'app.nimarkogram.messenger',
            'app.nimarkogram.messenger.plugins',
        )
        cls.previous_modules = {
            name: sys.modules.get(name) for name in module_names
        }
        for name in module_names[:-1]:
            module = types.ModuleType(name)
            module.__path__ = []
            sys.modules[name] = module
        plugins_module = types.ModuleType(module_names[-1])
        plugins_module.PluginsController = PluginsController
        sys.modules[module_names[-1]] = plugins_module

        source = REPO / 'TMessagesProj/src/main/python/plugin_runtime.py'
        spec = importlib.util.spec_from_file_location(
            'nimarko_plugin_runtime_under_test', source)
        cls.runtime = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(cls.runtime)

    @classmethod
    def tearDownClass(cls):
        for name, previous in cls.previous_modules.items():
            if previous is None:
                sys.modules.pop(name, None)
            else:
                sys.modules[name] = previous

    def setUp(self):
        self.controller.allowed = True
        self.controller.task_decision = None
        self.controller.entered.clear()
        self.controller.exited.clear()
        self.controller.events.clear()
        self.controller.runtime_scopes.clear()
        self.controller.watchdog.reset()

    @staticmethod
    def _owned_callback(token, body):
        namespace = {
            '__nimarko_runtime_token__': token,
            '_body': body,
        }
        exec('def callback(*args):\n    return _body(*args)', namespace)
        return namespace['callback']

    def test_runtime_reload_does_not_stack_process_wide_wrappers(self):
        wrapped_entry_points = (
            threading.Thread.__init__,
            threading.Thread._bootstrap_inner,
            threading.Timer.__init__,
            concurrent.futures.ThreadPoolExecutor.submit,
            concurrent.futures.Future.add_done_callback,
        )
        low_level_start = _thread.start_new_thread

        self.runtime.__spec__.loader.exec_module(self.runtime)

        self.assertEqual(
            wrapped_entry_points,
            (
                threading.Thread.__init__,
                threading.Thread._bootstrap_inner,
                threading.Timer.__init__,
                concurrent.futures.ThreadPoolExecutor.submit,
                concurrent.futures.Future.add_done_callback,
            ),
        )
        self.assertIs(low_level_start, _thread.start_new_thread)

    def test_owner_survives_partial_wrapper(self):
        token = _RuntimeToken('partial')
        callback = self._owned_callback(token, lambda value: value)
        wrapped = functools.partial(callback, 7)
        self.assertIs(
            self.runtime.capture_callback_owner(wrapped), token)

    def test_revoked_future_callback_is_dropped(self):
        token = _RuntimeToken('future')
        called = []
        callback = self._owned_callback(
            token, lambda future: called.append(future.result()))
        future = concurrent.futures.Future()
        future.add_done_callback(callback)
        self.controller.allowed = False
        future.set_result('stale')
        self.assertEqual(called, [])
        self.assertEqual(self.controller.entered, [])
        self.assertEqual(self.controller.events, [])

    def test_revoked_timer_never_runs(self):
        token = _RuntimeToken('timer')
        called = threading.Event()
        callback = self._owned_callback(token, called.set)
        self.controller.allowed = False
        timer = threading.Timer(0.001, callback)
        timer.start()
        timer.join(1.0)
        self.assertFalse(called.is_set())

    def test_worker_created_during_preparing_waits_for_publication(self):
        token = _RuntimeToken('preparing')
        called = threading.Event()
        callback = self._owned_callback(token, called.set)
        self.controller.task_decision = 1

        worker = threading.Thread(target=callback)
        worker.start()
        self.assertFalse(called.wait(0.03))

        self.controller.task_decision = 2
        worker.join(1.0)
        self.assertFalse(worker.is_alive())
        self.assertTrue(called.is_set())

    def test_thread_subclass_created_during_preparing_waits_for_publication(self):
        token = _RuntimeToken('preparing-subclass')
        called = threading.Event()
        namespace = {
            '__nimarko_runtime_token__': token,
            'threading': threading,
            '_called': called,
        }
        exec(
            'class Worker(threading.Thread):\n'
            '    def __init__(self):\n'
            '        super().__init__(daemon=True)\n'
            '    def run(self):\n'
            '        _called.set()\n',
            namespace,
        )
        self.controller.task_decision = 1

        worker = namespace['Worker']()
        worker.start()
        self.assertFalse(called.wait(0.03))

        self.controller.task_decision = 2
        worker.join(1.0)
        self.assertFalse(worker.is_alive())
        self.assertTrue(called.is_set())

    def test_allowed_callback_balances_runtime_scope(self):
        token = _RuntimeToken('callback')
        callback = self._owned_callback(
            token, lambda value: value + 1)
        result = self.runtime.run_owned_callback(
            token, callback, 4, default=-1)
        self.assertEqual(result, 5)
        self.assertEqual(self.controller.entered, [token])
        self.assertEqual(self.controller.exited, [token])
        self.assertEqual(
            self.controller.events,
            [
                ('enter', token),
                ('started', 'callback', token),
                ('finished', 'callback', token),
                ('exit', token),
            ],
        )

    def test_callback_reports_exact_throwable_which_is_rethrown(self):
        token = _RuntimeToken('failure')

        class Throwable(Exception):
            pass

        class PyException(Throwable):
            pass

        java = types.ModuleType('java')
        java.__path__ = []
        java_lang = types.ModuleType('java.lang')
        java_lang.Throwable = Throwable
        com = types.ModuleType('com')
        com.__path__ = []
        chaquo = types.ModuleType('com.chaquo')
        chaquo.__path__ = []
        chaquo_python = types.ModuleType('com.chaquo.python')
        chaquo_python.PyException = PyException
        original = RuntimeError('plugin failed')

        def callback():
            raise original

        with mock.patch.dict(
                sys.modules,
                {
                    'java': java,
                    'java.lang': java_lang,
                    'com': com,
                    'com.chaquo': chaquo,
                    'com.chaquo.python': chaquo_python,
                }):
            with self.assertRaises(PyException) as raised:
                self.runtime.run_owned_callback(
                    token, callback, default=None)

        reported = self.controller.watchdog.failures
        self.assertEqual(len(reported), 1)
        self.assertEqual(reported[0][:2], ('failure', token))
        self.assertIs(reported[0][2], raised.exception)
        self.assertEqual(
            self.controller.events,
            [
                ('enter', token),
                ('started', 'failure', token),
                ('failed', 'failure', token, raised.exception),
                ('finished', 'failure', token),
                ('exit', token),
            ],
        )

    def test_guard_proxy_callback_is_watchdog_tracked(self):
        token = _RuntimeToken('proxy')
        callback = self._owned_callback(
            token, lambda value: value.upper())
        guarded = self.runtime.guard_proxy_callback(
            callback, owner=token, default='closed')

        self.assertEqual(guarded('ok'), 'OK')
        self.assertEqual(
            self.controller.events,
            [
                ('enter', token),
                ('started', 'proxy', token),
                ('finished', 'proxy', token),
                ('exit', token),
            ],
        )

    def test_allowed_future_callback_is_watchdog_tracked(self):
        token = _RuntimeToken('future')
        called = []
        callback = self._owned_callback(
            token, lambda future: called.append(future.result()))
        future = concurrent.futures.Future()
        future.add_done_callback(callback)

        future.set_result('current')

        self.assertEqual(called, ['current'])
        self.assertEqual(
            self.controller.events,
            [
                ('enter', token),
                ('started', 'future', token),
                ('finished', 'future', token),
                ('exit', token),
            ],
        )

    def test_nested_callback_tracking_restores_outer_scope(self):
        token = _RuntimeToken('nested')
        observed = []

        def inner():
            observed.append(
                ('inner', self.controller.watchdog.executing[-1]))

        def outer():
            observed.append(
                ('outer-before', self.controller.watchdog.executing[-1]))
            self.runtime.run_owned_callback(
                token, inner, default=None)
            observed.append(
                ('outer-after', self.controller.watchdog.executing[-1]))

        self.runtime.run_owned_callback(
            token, outer, default=None)

        owner = ('nested', token)
        self.assertEqual(
            observed,
            [
                ('outer-before', owner),
                ('inner', owner),
                ('outer-after', owner),
            ],
        )
        self.assertEqual(self.controller.watchdog.executing, [])
        self.assertEqual(
            self.controller.events,
            [
                ('enter', token),
                ('started', 'nested', token),
                ('enter', token),
                ('started', 'nested', token),
                ('finished', 'nested', token),
                ('exit', token),
                ('finished', 'nested', token),
                ('exit', token),
            ],
        )

    def test_ownerless_sdk_callbacks_fail_closed(self):
        called = []

        def callback():
            called.append(True)
            return 'unsafe'

        self.assertFalse(self.runtime.is_callback_allowed(None))
        self.assertEqual(
            self.runtime.run_owned_callback(
                None, callback, default='closed'),
            'closed',
        )
        self.assertEqual(
            self.runtime.run_owned_worker(
                None, callback, default='closed'),
            'closed',
        )
        with self.assertRaisesRegex(
                RuntimeError, 'exact runtime owner'):
            self.runtime.make_runnable(callback)
        self.assertEqual(called, [])
        self.assertEqual(self.controller.events, [])

    def test_nested_work_inherits_owner_from_plugin_thread(self):
        token = _RuntimeToken('nested-worker')
        current = threading.current_thread()
        previous = getattr(
            current, '_nimarko_runtime_token', self.runtime._UNSET)
        current._nimarko_runtime_token = token
        try:
            
            namespace = {}
            exec('def callback():\n    return None', namespace)
            self.assertIs(
                self.runtime.capture_callback_owner(namespace['callback']),
                token,
            )
        finally:
            if previous is self.runtime._UNSET:
                del current._nimarko_runtime_token
            else:
                current._nimarko_runtime_token = previous

    def test_executor_worker_propagates_owner_without_holding_callback_scope(self):
        token = _RuntimeToken('executor')
        observed = []
        callback = self._owned_callback(
            token,
            lambda: observed.append(
                self.runtime.capture_callback_owner()))
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as executor:
            executor.submit(callback).result(timeout=1.0)
        self.assertEqual(observed, [token])
        self.assertEqual(self.controller.entered, [])
        self.assertEqual(self.controller.exited, [])

    def test_java_retirement_waits_for_real_return_and_quiescence(self):
        engine = (
            REPO / 'TMessagesProj/src/main/java/app/nimarkogram/messenger/'
                   'plugins/PythonPluginsEngine.java'
        ).read_text()
        controller = (
            REPO / 'TMessagesProj/src/main/java/app/nimarkogram/messenger/'
                   'plugins/PluginsController.java'
        ).read_text()
        self.assertIn('actuallyReturned', engine)
        self.assertIn('runWhenPluginRuntimeQuiescent', engine)
        eviction = engine[
            engine.index('private void evictPluginInstance('):
            engine.index('private void callOnPluginLoadWithTimeout(')
        ]
        self.assertNotIn('.close()', eviction)
        self.assertIn('cleanupPythonRegistries', controller)
        self.assertIn(
            'runWhenPluginRuntimeQuiescent(cleanup.quiescenceToken',
            controller)
        self.assertIn('schedulePluginUnloadAfterQuiescence', engine)
        self.assertIn('rollbackPluginImport(', engine)
        self.assertIn('removePluginModuleIfOwned(', engine)
        self.assertIn('rollbackPluginFileInstall(', engine)
        self.assertIn('catch (LifecyclePendingException pending)', engine)
        self.assertIn('allocatePluginBackupFile(', engine)
        self.assertIn('candidateWriteStarted', engine)
        self.assertIn('snapshotPluginRequirements(', engine)
        self.assertIn('snapshotRequirements(pluginId)', engine)
        self.assertIn(
            'cancelPluginInitialization(pluginId, enableGeneration, false)',
            engine,
        )
        self.assertIn('expectedRuntime', engine)
        self.assertIn('pendingToggleCallbacks', controller)
        self.assertIn('CopyOnWriteArrayList<PendingToggleCallback>',
                      controller)
        self.assertIn('rejectTimedOutLifecycle', engine)
        self.assertIn('releaseDeferredActionsAfterTimeout', engine)
        self.assertIn('interestedPluginsRevision', controller)
        self.assertIn('pluginRegistryCleanupQueue', controller)
        self.assertIn(
            'runtimeToken != null ? runtimeToken : quiescenceToken',
            controller,
        )
        self.assertIn(
            'cleanup.runtimeToken == null',
            controller,
        )
        self.assertIn(
            'getCurrentPluginRuntime(cleanup.pluginId) != null',
            controller,
        )

        abandon = engine[
            engine.index(
                'private void abandonPythonRuntimeForShutdown()'):
            engine.index(
                'private LifecycleOperation beginLifecycleOperation(')
        ]
        self.assertIn('PYTHON_RUNTIME_ABANDONED.set(true)', abandon)
        self.assertIn('basePluginClass = null', abandon)
        self.assertIn('revokeAllInstallCandidates()', abandon)
        revoke_all = engine[
            engine.index('private void revokeAllInstallCandidates()'):
            engine.index(
                'private void consumeHostInstallTicket(')
        ]
        self.assertIn('hostInstallTickets.clear()', revoke_all)
        self.assertIn('executor.shutdownNow()', abandon)

        get_python = engine[
            engine.index('private Python getPython()'):
            engine.index(
                'private boolean ensurePineReady()')
        ]
        self.assertLess(
            get_python.index('PYTHON_RUNTIME_ABANDONED.get()'),
            get_python.index('initPython()'),
        )
        self.assertIn(
            'PYTHON_RUNTIME_ABANDONED.get()',
            engine[
                engine.index('private void initPython()'):
                engine.index('@Override\n    public boolean isPlugin(')
            ],
        )
        restart = controller[
            controller.index(
                'public void restart(final boolean startWithSafeMode)'):
            controller.index(
                'public List<SettingItem> getPluginSettingsList')
        ]
        self.assertLess(
            restart.index('requiresProcessRestart()'),
            restart.index('shutdown(() ->'),
        )
        self.assertIn(
            'AppRestartHelper\n'
            '                    .triggerRebirth(',
            restart,
        )
        self.assertIn(
            'isProcessPythonRuntimeAbandoned()',
            engine,
        )
        self.assertIn(
            '!PythonPluginsEngine\n'
            '                            .isProcessPythonRuntimeAbandoned()',
            controller,
        )
        pip = (
            REPO / 'TMessagesProj/src/main/java/app/nimarkogram/messenger/'
                   'plugins/pip/PipController.java'
        ).read_text()
        self.assertIn('isPythonRuntimeUsable()', pip)
        self.assertIn(
            '.isProcessPythonRuntimeAbandoned()',
            pip,
        )
        intents = (
            REPO / 'TMessagesProj/src/main/java/app/nimarkogram/messenger/'
                   'plugins/intents/IntentsController.java'
        ).read_text()
        self.assertLess(
            intents.index('isProcessPythonRuntimeAbandoned()'),
            intents.index('Python.getInstance()'),
        )

        settings_activity = (
            REPO / 'TMessagesProj/src/main/java/app/nimarkogram/messenger/'
                   'plugins/ui/PluginSettingsActivity.java'
        ).read_text()
        callback_gate = settings_activity[
            settings_activity.index(
                'private <T> T callPluginCallback('):
            settings_activity.index('@Override\n    public boolean onFragmentCreate()')
        ]
        self.assertIn('runtimeToken != null', callback_gate)
        self.assertNotIn('isPluginActive(', callback_gate)
        self.assertIn('resultMapper.map(result)', callback_gate)

        edit_cell = (
            REPO / 'TMessagesProj/src/main/java/app/nimarkogram/messenger/'
                   'plugins/ui/components/PluginEditTextCell.java'
        ).read_text()
        save_gate = edit_cell[
            edit_cell.index('private void performSave('):
            edit_cell.index('@Override\n    protected void onFocusChanged')
        ]
        self.assertIn('token != null', save_gate)
        self.assertNotIn('isPluginActive(', save_gate)

if __name__ == '__main__':
    unittest.main()
