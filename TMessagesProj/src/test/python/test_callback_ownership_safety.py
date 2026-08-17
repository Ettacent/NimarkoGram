import pathlib
import unittest

REPO = pathlib.Path(__file__).resolve().parents[4]
JAVA = (
    REPO / 'TMessagesProj/src/main/java/app/nimarkogram/messenger/plugins'
)
PYTHON = REPO / 'TMessagesProj/src/main/python'

class CallbackOwnershipSafetyTest(unittest.TestCase):
    def test_watchdog_force_disable_is_atomic_and_python_free(self):
        controller = (JAVA / 'PluginsController.java').read_text()
        start = controller.index(
            'public boolean forceDisablePluginDurably(')
        method = controller[
            start:
            controller.index(
                'public void deletePlugin(final String str,', start)
        ]
        locked = method[
            method.index('synchronized (generationLock(pluginId))'):
            method.index('finishPluginDeactivation(cleanup)')
        ]
        generation = locked.index('toggleGenerations.put(')
        pending = locked.index('pendingToggleState.remove(')
        enabling = locked.index('enablingInProgress.remove(')
        callbacks = locked.index(
            'drainPendingToggleCallbacksLocked(')
        disabled = locked.index('plugin.setEnabled(false)')
        committed = locked.index('.commit()')
        detached = locked.index(
            'detachPluginRuntimeLocked(pluginId)')
        for operation in (
                pending, enabling, callbacks, disabled,
                committed, detached):
            self.assertLess(generation, operation)
        self.assertLess(committed, detached)

        self.assertIn(
            'finishPluginDeactivation(cleanup)', method)
        self.assertIn(
            'deliverToggleCallbacks(', method)
        self.assertIn(
            'invalidateInterestedPluginsCache()', method)
        self.assertIn('notifyPluginsChanged()', method)
        self.assertNotIn('getPluginEngine(', method)
        self.assertNotIn('setPluginEnabled(', method)
        self.assertNotIn('com.chaquo.python', method)
        self.assertNotIn('cleanupPythonRegistries(', method)

    def test_preparing_is_lifecycle_only_and_external_callbacks_require_run(self):
        controller = (JAVA / 'PluginsController.java').read_text()
        enter_start = controller.index(
            'public boolean enterPluginRuntime(')
        enter = controller[
            enter_start:
            controller.index(
                'public void exitPluginRuntime(', enter_start)
        ]
        self.assertIn(
            'slot.state == RuntimeState.PREPARING', enter)
        self.assertIn(
            'PluginInitializationToken permit = '
            'initializationPermit.get()', enter)
        self.assertIn(
            'permit.generation != token.generation', enter)
        self.assertIn(
            'permit.pluginId, token.pluginId', enter)
        self.assertIn(
            'token.equals(pluginUnloadPermit.get())', enter)

        callback_start = controller.index(
            'public boolean isPluginRuntimeCallbackAllowed(')
        callback_gate = controller[
            callback_start:
            controller.index(
                'public void beginPluginUnload(', callback_start)
        ]
        self.assertIn(
            '== RUNTIME_TASK_RUN', callback_gate)
        self.assertNotIn(
            'RUNTIME_TASK_WAIT', callback_gate)
        self.assertNotIn(
            'pluginUnloadPermit', callback_gate)

    def test_request_sdk_always_uses_java_holder(self):
        client = (PYTHON / 'client_utils.py').read_text()
        send_start = client.index('def send_request(')
        send = client[
            send_start:
            client.index('def observe_notifications(', send_start)
        ]
        self.assertIn('RequestCallback(fn)', send)
        self.assertNotIn(
            'fn if isinstance(fn, RequestDelegate)', send)

        holder = (
            JAVA / 'utils/PythonRequestDelegate.java'
        ).read_text()
        self.assertIn(
            'static PythonRequestDelegate fromDelegate(', holder)
        self.assertIn(
            'OneShotCallbackState<CallbackTarget>', holder)
        self.assertIn(
            'callback.delegateCallback.run(', holder)
        self.assertIn(
            'getPluginRuntimeTaskDecision(runtimeToken)', holder)
        self.assertIn(
            'enterPluginRuntime(runtimeToken)', holder)
        self.assertIn(
            'callbackState.beginInitialInvocation()', holder)
        self.assertIn(
            'callbackState.beginOnlyRetry()', holder)
        self.assertIn(
            '.registerRuntimeCallbackHolder(runtimeToken, this)',
            holder,
        )
        self.assertIn(
            'controller.deferRuntimeCallback(', holder)
        self.assertIn(
            'void onPluginRuntimeActive()', holder)
        self.assertIn(
            'void revokePluginRuntime()', holder)
        self.assertIn(
            'callback instanceof PyProxy', holder)
        self.assertNotIn('postDelayed(', holder)

    def test_plugin_runnable_is_fail_closed_and_atomically_one_shot(self):
        runnable = (
            JAVA / 'utils/PythonRunnable.java'
        ).read_text()
        self.assertIn(
            'public PythonRunnable(PyObject callback)', runnable)
        self.assertIn(
            'captureCurrentPluginRuntime()', runnable)
        self.assertIn(
            'if (runtimeToken == null)', runnable)
        self.assertIn(
            'Python Runnable requires an exact plugin runtime',
            runnable,
        )
        self.assertNotIn(
            'runtimeToken == null ||', runnable)
        self.assertIn(
            'OneShotCallbackState<PyObject>', runnable)
        self.assertIn(
            'callbackState.beginInitialInvocation()', runnable)
        self.assertIn(
            'callbackState.beginOnlyRetry()', runnable)
        self.assertIn(
            '.registerRuntimeCallbackHolder(runtimeToken, this)',
            runnable,
        )
        self.assertIn(
            'controller.deferRuntimeCallback(', runnable)
        self.assertIn(
            'void onPluginRuntimeActive()', runnable)
        self.assertIn(
            'void revokePluginRuntime()', runnable)
        self.assertNotIn('postDelayed(', runnable)

    def test_one_shot_state_machine_has_atomic_terminal_ownership(self):
        state = (
            JAVA / 'utils/OneShotCallbackState.java'
        ).read_text()
        for lifecycle_state in (
                'PENDING', 'WAITING', 'RUNNING', 'DONE', 'DROPPED'):
            self.assertIn(lifecycle_state, state)
        self.assertIn(
            'AtomicReference<T> callback', state)
        self.assertIn(
            'AtomicReference<State> state', state)
        self.assertIn(
            'compareAndSet(State.PENDING, State.RUNNING)', state)
        self.assertIn(
            'compareAndSet(State.RUNNING, State.WAITING)', state)
        self.assertIn(
            'compareAndSet(State.WAITING, State.RUNNING)', state)
        self.assertIn('callback.getAndSet(null)', state)

    def test_host_callback_is_separate_and_atomically_one_shot(self):
        callback = (
            JAVA / 'utils/PythonUtilitiesCallback.java'
        ).read_text()
        self.assertIn(
            'captureCurrentPluginRuntime() != null', callback)
        self.assertIn(
            'Engine callback bridge is unavailable to plugins',
            callback,
        )
        self.assertIn(
            'OneShotCallbackState<PyObject>', callback)
        self.assertIn(
            'callbackState.beginInitialInvocation()', callback)
        self.assertIn(
            'callbackState.takeForExecution()', callback)

    def test_retained_notification_holder_has_exact_runtime_and_close_gate(self):
        holder = (
            JAVA / 'utils/PythonNotificationDelegate.java'
        ).read_text()
        self.assertIn(
            'callback == null || runtimeToken == null', holder)
        self.assertIn(
            'AtomicReference<PyObject> callback', holder)
        self.assertIn(
            'AtomicReference<State> state', holder)
        self.assertIn(
            'state.set(State.CLOSED)', holder)
        self.assertIn(
            'callback.getAndSet(null)', holder)
        self.assertIn(
            'getPluginRuntimeTaskDecision(runtimeToken)', holder)
        self.assertIn(
            'enterPluginRuntime(runtimeToken)', holder)

    def test_legacy_notification_delegate_has_immutable_guard(self):
        client = (PYTHON / 'client_utils.py').read_text()
        start = client.index(
            'class _NotificationCenterDelegateMeta(')
        legacy = client[
            start:
            client.index(
                'class NotificationCenterDelegate(', start)
        ]
        self.assertIn(
            'token = capture_callback_owner(callback)', legacy)
        self.assertIn(
            'make_interface_proxy(', legacy)
        self.assertNotIn(
            "getattr(self, '_runtime_token'", legacy)

    def test_py_method_hook_reports_exact_python_failure_before_rethrow(self):
        hook = (
            JAVA / 'xposed/PyMethodHook.java'
        ).read_text()
        self.assertIn(
            'requireExactRuntime(str, runtimeToken)',
            hook,
        )
        require_start = hook.index(
            'private static '
            'PluginsController.PluginRuntimeToken requireExactRuntime(')
        require_end = hook.index(
            'private boolean enterRuntime(', require_start)
        owner_check = hook[require_start:require_end]
        self.assertIn('if (runtimeToken == null)', owner_check)
        self.assertIn(
            '!pluginId.equals(runtimeToken.getPluginId())',
            owner_check,
        )
        enter = hook[
            hook.index('private boolean enterRuntime('):
            hook.index('private void exitRuntime(')
        ]
        self.assertNotIn('this.runtimeToken == null', enter)
        self.assertNotIn('isPluginActive(', enter)
        self.assertEqual(
            hook.count(
                'controller.getWatchdog().onPluginExecutionFailed('),
            2,
        )
        for method, end in (
                ('beforeHookedMethod(', 'afterHookedMethod('),
                ('afterHookedMethod(', 'private void handleHookError(')):
            section = hook[
                hook.index(method):
                hook.index(end, hook.index(method) + len(method))
            ]
            py_check = section.index(
                'catch (PyException pythonFailure)')
            failed = section.index(
                'onPluginExecutionFailed(', py_check)
            rethrow = section.index(
                'throw pythonFailure;', failed)
            self.assertLess(py_check, failed)
            self.assertLess(failed, rethrow)
            self.assertIn(
                'this.pluginId, pythonFailure',
                section[failed:rethrow])

    def test_replacement_hook_requires_exact_generation_and_never_revives_by_id(self):
        replacement = (
            JAVA / 'xposed/PyMethodReplacement.java'
        ).read_text()
        self.assertIn(
            'requireExactRuntime(str, runtimeToken)',
            replacement,
        )
        require_start = replacement.index(
            'private static '
            'PluginsController.PluginRuntimeToken requireExactRuntime(')
        require_end = replacement.index(
            'private static PyObject resolveBound(', require_start)
        owner_check = replacement[require_start:require_end]
        self.assertIn('if (runtimeToken == null)', owner_check)
        self.assertIn(
            '!pluginId.equals(runtimeToken.getPluginId())',
            owner_check,
        )

        replace_start = replacement.index(
            'protected Object replaceHookedMethod(')
        replace_end = replacement.index(
            'private void handleHookError(', replace_start)
        dispatch = replacement[replace_start:replace_end]
        self.assertIn(
            'getPluginRuntimeTaskDecision(this.runtimeToken)',
            dispatch,
        )
        self.assertIn(
            'controller.enterPluginRuntime(this.runtimeToken)',
            dispatch,
        )
        self.assertIn(
            'controller.exitPluginRuntime(this.runtimeToken)',
            dispatch,
        )
        self.assertNotIn('isPluginActive(', dispatch)
        self.assertNotIn('this.runtimeToken == null', dispatch)

    def test_pine_mismatched_nested_call_keeps_stack_balanced(self):
        adapter = (
            JAVA / 'xposed/PineAdapter.java'
        ).read_text()
        self.assertIn('boolean skipAfterCallback;', adapter)
        mismatch_start = adapter.index(
            'if (receiverMismatched(cf)) {',
            adapter.index('public void beforeCall('),
        )
        mismatch_end = adapter.index(
            'AdapterParam param = claim(cf);', mismatch_start)
        mismatch = adapter[mismatch_start:mismatch_end]
        self.assertIn('AdapterParam skipped = claim(cf);', mismatch)
        self.assertIn('skipped.skipAfterCallback = true;', mismatch)
        self.assertIn('pushActive(skipped);', mismatch)

        after_start = adapter.index('public void afterCall(')
        after = adapter[after_start:]
        pop = after.index('AdapterParam param = popActive();')
        skip = after.index('param.skipAfterCallback', pop)
        replacement = after.index(
            'xcHook instanceof XC_MethodReplacement', pop)
        self.assertLess(pop, skip)
        self.assertLess(skip, replacement)

    def test_shared_chaquopy_wrappers_are_not_closed_manually(self):
        utils = (
            JAVA / 'utils/PyObjectUtils.java'
        ).read_text()
        intents = (
            JAVA / 'intents/IntentsController.java'
        ).read_text()
        engine = (
            JAVA / 'PythonPluginsEngine.java'
        ).read_text()

        self.assertNotIn('.close()', utils)
        self.assertNotIn('result.close()', intents)
        self.assertNotIn('pyObject.close()', engine)
        self.assertNotIn('module.close()', engine)
        self.assertNotIn('pyObjectCall.close()', engine)

    def test_plugin_ui_failures_are_attributed_and_dialog_teardown_is_frame_safe(self):
        delegate = (
            JAVA / 'ui/components/templates/PluginRuntimeDelegate.java'
        ).read_text()
        watchdog = (
            JAVA / 'utils/PluginsWatchdog.java'
        ).read_text()

        self.assertGreaterEqual(
            delegate.count('onPluginExecutionFailed('),
            4,
        )
        dismiss_start = watchdog.index(
            'private void dismissStaleAlert(')
        dismiss_end = watchdog.index(
            'public void forceDisablePlugin(', dismiss_start)
        dismiss = watchdog[dismiss_start:dismiss_end]
        self.assertIn('PluginUiRegistry.dismissDialog(dialog)', dismiss)
        self.assertNotIn('dialog.dismiss()', dismiss)

    def test_plugin_ui_consumes_python_results_inside_exact_runtime(self):
        settings = (
            JAVA / 'ui/PluginSettingsActivity.java'
        ).read_text()
        listener = (
            JAVA / 'ui/PluginViewListener.java'
        ).read_text()
        editor = (
            JAVA / 'ui/components/PluginEditTextCell.java'
        ).read_text()

        callback_start = settings.index(
            'private <T> T callPluginCallback(')
        callback_end = settings.index(
            'private void runPluginCallback(', callback_start)
        guarded_callback = settings[callback_start:callback_end]
        result_call = guarded_callback.index(
            'PyObject result = callback.call(args);')
        result_map = guarded_callback.index(
            'resultMapper.map(result)', result_call)
        runtime_exit = guarded_callback.index(
            'controller.exitPluginRuntime(runtimeToken)', result_map)
        self.assertLess(result_call, result_map)
        self.assertLess(result_map, runtime_exit)
        self.assertNotIn(
            'PyObject pyObjectCall = callPluginCallback', settings)
        self.assertNotIn(
            'PyObject res = callPluginCallback', settings)
        self.assertIn(
            'parsePySettingDefinitions(',
            settings[
                settings.index('private List<SettingItem> callSettingsCallback('):
                settings.index('@Override', callback_end)
            ],
        )

        listener_call = listener[
            listener.index('public boolean onLongClick('):
            listener.index('@Override', listener.index(
                'public boolean onLongClick(') + 1)
        ]
        self.assertIn(
            'pythonResult.toJava(Object.class)', listener_call)
        self.assertIn(
            'pythonResult.toBoolean()', listener_call)
        self.assertNotIn(
            'PyObject result = PluginRuntimeDelegate.call(', listener_call)
        self.assertIn(
            'onPluginExecutionFailed(', editor)

    def test_settings_notifications_are_generation_bound_and_frame_safe(self):
        controller = (
            JAVA / 'PluginsController.java'
        ).read_text()
        settings = (
            JAVA / 'ui/PluginSettingsActivity.java'
        ).read_text()
        registry = (
            JAVA / 'ui/PluginUiRegistry.java'
        ).read_text()

        self.assertIn(
            'NotificationCenter.pluginSettingsRegistered,\n'
            '                                        str, runtimeToken',
            controller,
        )
        self.assertIn(
            'NotificationCenter.pluginSettingsUnregistered,\n'
            '                            cleanup.pluginId, cleanup.runtimeToken',
            controller,
        )
        self.assertIn(
            'eventRuntime.equals(runtimeToken)', settings)
        self.assertIn(
            'scheduleFinishForRuntimeLoss()', settings)
        finish_start = settings.index(
            'private void scheduleFinishForRuntimeLoss()')
        finish = settings[finish_start:]
        self.assertIn(
            'PluginUiRegistry.runAfterTraversal(', finish)
        self.assertIn(
            '!isExactRuntimeCurrent()', finish)
        self.assertIn(
            '!controller.hasPluginSettings(plugin.getId())', finish)
        self.assertIn(
            'public static void runAfterTraversal(', registry)

if __name__ == '__main__':
    unittest.main()
