import ast
import pathlib
import unittest

REPO = pathlib.Path(__file__).resolve().parents[4]
JAVA = REPO / 'TMessagesProj/src/main/java'
PYTHON = REPO / 'TMessagesProj/src/main/python'

def read_java(relative_path):
    return (JAVA / relative_path).read_text()

def load_alert_request_state():
    """Execute only the request-ordering methods, without Android imports."""
    source = (PYTHON / 'ui/alert.py').read_text()
    tree = ast.parse(source)
    builder = next(
        node for node in tree.body
        if isinstance(node, ast.ClassDef)
        and node.name == 'AlertDialogBuilder'
    )
    method_names = {
        'dismiss',
        '_begin_ui_request',
        '_is_ui_request_current',
        '_on_dialog_dismissed',
    }
    methods = [
        node for node in builder.body
        if isinstance(node, ast.FunctionDef)
        and node.name in method_names
    ]
    request_state = ast.ClassDef(
        name='RequestState',
        bases=[],
        keywords=[],
        body=methods,
        decorator_list=[],
    )
    module = ast.fix_missing_locations(
        ast.Module(body=[request_state], type_ignores=[]))

    class PluginUiRegistry:
        dismissed = []

        @classmethod
        def dismissDialog(cls, dialog):
            cls.dismissed.append(dialog)

    namespace = {'PluginUiRegistry': PluginUiRegistry}
    exec(compile(module, 'alert_request_state', 'exec'), namespace)
    return namespace['RequestState'], PluginUiRegistry

class PluginUiLifecycleIsolationTest(unittest.TestCase):
    def test_root_child_removal_always_crosses_frame_and_main_post(self):
        android_utilities = read_java(
            'org/telegram/messenger/AndroidUtilities.java')
        method = android_utilities[
            android_utilities.index(
                'public static void removeFromParent(View child)'):
            android_utilities.index(
                'public static boolean isFilNotFoundException')
        ]

        self.assertIn(
            'parent == child.getRootView() && parent.isAttachedToWindow()',
            method)
        self.assertIn('if (isDirectWindowChild)', method)
        self.assertNotIn('&& parent.isInLayout()', method)
        self.assertIn('child.setVisibility(View.GONE);', method)
        self.assertIn(
            'Choreographer.getInstance().postFrameCallback(', method)
        self.assertIn(
            'frameTimeNanos -> parent.post(removeIfStillOwned)', method)
        self.assertIn('if (child.getParent() == parent)', method)
        self.assertIn('} else {\n            parent.removeView(child);', method)

    def test_get_dialog_exposes_only_frame_safe_facade(self):
        alert = (PYTHON / 'ui/alert.py').read_text()
        tree = ast.parse(alert)
        facade = next(
            node for node in tree.body
            if isinstance(node, ast.ClassDef)
            and node.name == '_AlertDialogFacade'
        )
        builder = next(
            node for node in tree.body
            if isinstance(node, ast.ClassDef)
            and node.name == 'AlertDialogBuilder'
        )
        get_dialog = next(
            node for node in builder.body
            if isinstance(node, ast.FunctionDef)
            and node.name == 'get_dialog'
        )
        get_dialog_source = ast.get_source_segment(alert, get_dialog)
        facade_source = ast.get_source_segment(alert, facade)

        self.assertIn('return self._dialog_facade', get_dialog_source)
        self.assertNotIn('return self._alert_dialog', get_dialog_source)
        self.assertIn('self.__builder.dismiss()', facade_source)
        self.assertIn('self.__builder.cancel()', facade_source)
        self.assertIn('self.__builder.show()', facade_source)
        self.assertNotIn('self.__builder._alert_dialog.dismiss(', facade_source)
        self.assertNotIn('self.__builder._alert_dialog.show(', facade_source)
        self.assertIn(
            'PluginUiRegistry.cancelDialog(dialog)',
            alert)

    def test_native_plugin_screens_gate_async_ui_by_lifecycle_and_operation(self):
        activity = read_java(
            'app/nimarkogram/messenger/plugins/ui/PluginsActivity.java')
        install_sheet = read_java(
            'app/nimarkogram/messenger/plugins/ui/components/'
            'InstallPluginBottomSheet.java')
        registry = read_java(
            'app/nimarkogram/messenger/plugins/ui/PluginUiRegistry.java')

        self.assertIn('lifecycleEpoch', activity)
        self.assertIn('pluginUiOperationEpochs', activity)
        self.assertIn('isPluginUiOperationEpochCurrent(', activity)
        self.assertIn('isUiLifecycleCurrent(', activity)
        self.assertIn('pluginUiOperationEpochs.clear();', activity)
        self.assertNotIn('togglingPluginIds.isEmpty()', activity)

        self.assertIn('lifecycleEpoch', install_sheet)
        self.assertIn('installOperationEpoch', install_sheet)
        self.assertIn('isInstallUiCurrent(', install_sheet)
        self.assertIn(
            'hostFragment.getFragmentView() == hostFragmentView',
            install_sheet)
        self.assertIn(
            'PluginUiRegistry.isFragmentUiActive(fragment)', install_sheet)
        self.assertIn('finishInstallOperation(', install_sheet)

        self.assertIn(
            'public static boolean isFragmentUiActive(', registry)
        self.assertIn('fragment.isPaused()', registry)
        self.assertIn('fragmentView.isAttachedToWindow()', registry)

    def test_dismiss_invalidates_already_queued_create_or_show(self):
        request_state_class, registry = load_alert_request_state()
        state = request_state_class()
        state._ui_request_id = 0
        state._dismissed_through_id = 0
        state._alert_dialog = None
        state._java_builder = None
        state._shown_request_id = 0

        queued_show = state._begin_ui_request()
        state.dismiss()
        self.assertFalse(state._is_ui_request_current(queued_show))
        self.assertEqual(registry.dismissed, [])

        later_show = state._begin_ui_request()
        self.assertTrue(state._is_ui_request_current(later_show))

    def test_create_and_show_keep_fifo_semantics_without_dismiss(self):
        request_state_class, _ = load_alert_request_state()
        state = request_state_class()
        state._ui_request_id = 0
        state._dismissed_through_id = 0
        state._alert_dialog = None
        state._java_builder = None
        state._shown_request_id = 0

        first = state._begin_ui_request()
        second = state._begin_ui_request()
        self.assertTrue(state._is_ui_request_current(first))
        self.assertTrue(state._is_ui_request_current(second))

    def test_deferred_old_dismiss_cannot_close_or_invalidate_new_show(self):
        request_state_class, registry = load_alert_request_state()
        state = request_state_class()
        old_dialog = object()
        new_dialog = object()
        state._ui_request_id = 1
        state._dismissed_through_id = 0
        state._shown_request_id = 1
        state._java_builder = object()
        state._alert_dialog = old_dialog

        state.dismiss()
        self.assertIsNone(state._alert_dialog)
        self.assertIsNone(state._java_builder)
        self.assertEqual(registry.dismissed, [old_dialog])

        new_show = state._begin_ui_request()
        state._alert_dialog = new_dialog
        state._java_builder = object()
        state._shown_request_id = new_show
        state._on_dialog_dismissed(old_dialog)

        self.assertIs(state._alert_dialog, new_dialog)
        self.assertTrue(state._is_ui_request_current(new_show))

    def test_registry_uses_weak_ui_owners_and_deferred_teardown(self):
        registry = read_java(
            'app/nimarkogram/messenger/plugins/ui/PluginUiRegistry.java')
        dialog_entry = registry[
            registry.index('private static final class DialogEntry'):
            registry.index('private static final class BulletinEntry')
        ]
        self.assertIn('WeakReference<Dialog>', dialog_entry)
        self.assertNotIn('final Dialog dialog;', dialog_entry)
        self.assertIn('WeakReference<RuntimeOwnedUi>', registry)
        self.assertIn('scheduleAfterTraversal', registry)
        self.assertIn('clearPluginUiReferences(runtimeToken)', registry)
        self.assertIn('isRuntimeCurrent(runtimeToken)', registry)

    def test_universal_owners_survive_detach_and_clear_on_revoke_or_destroy(self):
        base = (
            'app/nimarkogram/messenger/plugins/ui/components/templates/')
        view = read_java(base + 'UniversalView.java')
        frame = read_java(base + 'UniversalFrameLayout.java')
        fragment = read_java(base + 'UniversalFragment.java')

        for source in (view, frame, fragment):
            self.assertIn('implements PluginUiRegistry.RuntimeOwnedUi', source)
            self.assertIn('registerRuntimeOwnedUi(runtimeToken, this)', source)
            self.assertIn(
                'unregisterRuntimeOwnedUi(ownedToken, this)', source)
            self.assertIn('onPluginDelegateCleared()', source)

        view_detach = view[view.index('protected void onDetachedFromWindow()'):]
        frame_detach = frame[
            frame.index('protected void onDetachedFromWindow()'):]
        fragment_destroy = fragment[
            fragment.index('public void onFragmentDestroy()'):]
        self.assertNotIn('clearDelegate(null);', view_detach)
        self.assertNotIn(
            'clearUniversalFrameLayoutListener(null);', frame_detach)
        self.assertIn(
            'clearDelegate(runtimeToken);',
            view[view.index('public void clearPluginUiReferences'):])
        self.assertIn(
            'clearUniversalFrameLayoutListener(runtimeToken);',
            frame[frame.index('public void clearPluginUiReferences'):])
        self.assertIn('clearDelegate(null, true);', fragment_destroy)

    def test_super_capability_is_one_shot_and_callback_scoped(self):
        base = (
            'app/nimarkogram/messenger/plugins/ui/components/templates/')
        runtime = read_java(base + 'PluginRuntimeDelegate.java')
        view = read_java(base + 'UniversalView.java')
        frame = read_java(base + 'UniversalFrameLayout.java')

        self.assertIn('private boolean active = true;', runtime)
        self.assertIn('private boolean claimed;', runtime)
        self.assertIn('claimed = true;', runtime)
        self.assertIn('runtimeToken.equals(scopedToken)', runtime)
        self.assertIn('superCallScope.close();', runtime)
        self.assertIn('catch (Throwable error)', runtime)
        self.assertIn('superCallScope.wasClaimed()', runtime)
        self.assertIn('superCallScope.getClaimedResult', runtime)
        self.assertIn('PluginRuntimeDelegate.runScoped(', view)
        self.assertIn('PluginRuntimeDelegate.callScoped(', view)
        self.assertIn('PluginRuntimeDelegate.runScoped(', frame)
        self.assertIn('PluginRuntimeDelegate.callScoped(', frame)
        self.assertNotIn(
            'delegatedCanvas -> PluginRuntimeDelegate.run', view + frame)

    def test_sdk_listeners_are_java_holders_not_stale_dynamic_proxies(self):
        alert = (PYTHON / 'ui/alert.py').read_text()
        android_utils = (PYTHON / 'android_utils.py').read_text()
        client_utils = (PYTHON / 'client_utils.py').read_text()
        dev_server = (PYTHON / 'dev_server.py').read_text()
        dialog_callback = read_java(
            'app/nimarkogram/messenger/plugins/ui/'
            'PluginDialogCallback.java')
        view_listener = read_java(
            'app/nimarkogram/messenger/plugins/ui/'
            'PluginViewListener.java')
        request_delegate = read_java(
            'app/nimarkogram/messenger/plugins/utils/'
            'PythonRequestDelegate.java')
        notification_delegate = read_java(
            'app/nimarkogram/messenger/plugins/utils/'
            'PythonNotificationDelegate.java')

        self.assertNotIn('dynamic_proxy(', alert)
        self.assertNotIn('dynamic_proxy(', android_utils)
        self.assertNotIn(
            'class RequestCallback(dynamic_proxy', client_utils)
        self.assertNotIn('dynamic_proxy(', dev_server)
        self.assertIn('PluginDialogCallback(', alert)
        self.assertIn('PluginViewListener(', android_utils)
        self.assertIn('PythonRequestDelegate(fn, token)', client_utils)
        self.assertIn(
            'PythonNotificationDelegate(fn, token)', client_utils)
        self.assertIn('observe_notifications(', client_utils)
        for source in (dialog_callback, view_listener):
            self.assertIn('PluginUiRegistry.RuntimeOwnedUi', source)
            self.assertIn('clearPluginUiReferences(', source)
        self.assertRegex(
            request_delegate, r'implements\s+RequestDelegate')
        self.assertIn('enterPluginRuntime(runtimeToken)', request_delegate)
        self.assertIn(
            'NotificationCenter.NotificationCenterDelegate',
            notification_delegate)
        self.assertIn(
            'PluginUiRegistry.RuntimeOwnedUi',
            notification_delegate)
        self.assertIn(
            'center.removeObserver(this, id)',
            notification_delegate)
        self.assertIn(
            'enterPluginRuntime(runtimeToken)',
            notification_delegate)
        self.assertNotIn('PythonUtilitiesCallback', dev_server)
        self.assertIn('authority.postToMain(action)', dev_server)

    def test_compat_bridges_expose_only_gated_clearable_adapters(self):
        base = (
            'com/exteragram/messenger/plugins/ui/components/templates/')
        sources = [
            read_java(base + name)
            for name in (
                'UniversalView.java',
                'UniversalFrameLayout.java',
                'UniversalFragment.java',
            )
        ]
        for source in sources:
            self.assertIn('GuardedDelegate', source.replace(
                'GuardedListener', 'GuardedDelegate'))
            self.assertIn('PluginRuntimeDelegate.', source)
            self.assertIn('onPluginDelegateCleared()', source)
            self.assertIn('void clear()', source)

        self.assertNotIn(
            'this.bridgeDelegate = delegate;', sources[0])
        self.assertNotIn(
            'this.bridgeListener = listener;', sources[1])

if __name__ == '__main__':
    unittest.main()
