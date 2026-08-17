import pathlib
import unittest

REPO = pathlib.Path(__file__).resolve().parents[4]
JAVA = REPO / 'TMessagesProj/src/main/java'
ACTIVITY = JAVA / (
    'app/nimarkogram/messenger/plugins/ui/PluginsActivity.java')
CELL = JAVA / (
    'app/nimarkogram/messenger/plugins/ui/components/PluginCell.java')

def method(source, signature, next_signature):
    start = source.index(signature)
    end = source.index(next_signature, start)
    return source[start:end]

class PluginToggleUiLifecycleTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.activity = ACTIVITY.read_text()
        cls.cell = CELL.read_text()

    def test_bind_resolves_spinner_from_controller_and_ui_epoch(self):
        bind = method(
            self.cell,
            'public void set(\n            Plugin plugin,',
            'private void bindErrorState()')

        self.assertIn(
            'uiOperationEpoch != NO_UI_OPERATION_EPOCH', bind)
        self.assertIn(
            'controller.isTogglingInProgress(plugin.getId())', bind)
        self.assertIn(
            'controller.isEnablingInProgress(plugin.getId())', bind)
        self.assertIn('setLoading(', bind)
        self.assertNotIn(
            'loadingSpinner.setVisibility(View.GONE)', bind)

    def test_factory_carries_current_ui_operation_epoch_into_every_rebind(self):
        self.assertIn(
            'getCurrentPluginUiOperationEpoch(plugin.getId())',
            self.activity)
        self.assertIn(
            '((PluginCell) view).set(item.plugin, delegate, item.longValue);',
            self.cell)
        self.assertIn('item.longValue = uiOperationEpoch;', self.cell)

    def test_plugins_updated_is_never_suppressed_by_an_inflight_id(self):
        notifications = method(
            self.activity,
            'public void didReceivedNotification(',
            '\n}')
        plugins_updated = notifications[
            notifications.index(
                'if (i == NotificationCenter.pluginsUpdated)'):
            notifications.index(
                '} else if (i == NotificationCenter.reloadInterface)')
        ]

        self.assertIn('this.listView.adapter.update(true);', plugins_updated)
        self.assertNotIn('togglingPluginIds', plugins_updated)
        self.assertNotIn('isTogglingSomePlugin', plugins_updated)

    def test_callback_requires_operation_lifecycle_and_original_binding(self):
        toggle = method(
            self.activity,
            'public void togglePlugin(View view)',
            'public void openPluginSettings()')
        callback = toggle[toggle.index(
            'controller.setPluginEnabled(pluginId, z, str ->'):]
        operation_guard = callback.index(
            'isPluginUiOperationEpochCurrent(')
        finish = callback.index('finishPluginUiOperation(')
        lifecycle_guard = callback.index('isUiLifecycleCurrent(')
        binding_guard = callback.index('pluginCell.isBoundTo(')
        first_callback_mutation = callback.index(
            'pluginCell.setLoading(', binding_guard)

        self.assertLess(operation_guard, finish)
        self.assertLess(finish, lifecycle_guard)
        self.assertLess(lifecycle_guard, binding_guard)
        self.assertLess(binding_guard, first_callback_mutation)
        self.assertIn(
            'final long cellBindingEpoch = pluginCell.getBindingEpoch();',
            toggle)
        self.assertIn(
            'pluginId, cellBindingEpoch', toggle)

    def test_stale_fragment_delegate_cannot_start_a_new_ui_operation(self):
        toggle = method(
            self.activity,
            'public void togglePlugin(View view)',
            'public void openPluginSettings()')
        delete = method(
            self.activity,
            'public void deletePlugin()',
            'public void togglePlugin(View view)')

        self.assertLess(
            toggle.index('isUiLifecycleCurrent(callbackLifecycleEpoch)'),
            toggle.index('beginPluginUiOperation(pluginId)'))
        self.assertLess(
            delete.index('isUiLifecycleCurrent(dialogLifecycleEpoch)'),
            delete.index('beginPluginUiOperation(pluginId)'))

    def test_rebind_changes_binding_epoch_even_for_the_same_plugin_id(self):
        bind = method(
            self.cell,
            'public void set(\n            Plugin plugin,',
            'private void bindErrorState()')
        binding_check = method(
            self.cell,
            'public boolean isBoundTo(',
            'public void setPinned(')

        self.assertIn('bindingEpoch++;', bind)
        self.assertIn(
            'bindingEpoch == expectedBindingEpoch', binding_check)
        self.assertIn(
            'TextUtils.equals(plugin.getId(), pluginId)', binding_check)

    def test_fragment_destroy_invalidates_and_clears_toggle_ui_state(self):
        destroy = method(
            self.activity,
            'public void onFragmentDestroy()',
            'public void didReceivedNotification(')

        inactive = destroy.index('uiLifecycleActive = false;')
        invalidate = destroy.index('lifecycleEpoch++;')
        clear = destroy.index('pluginUiOperationEpochs.clear();')
        remove_observer = destroy.index('removeObserver(')
        self.assertLess(inactive, invalidate)
        self.assertLess(invalidate, clear)
        self.assertLess(clear, remove_observer)
        self.assertIn('searchItem = null;', destroy)
        self.assertIn('engineSettingsItem = null;', destroy)
        self.assertIn('emptyView = null;', destroy)
        self.assertIn('addPluginButton = null;', destroy)

if __name__ == '__main__':
    unittest.main()
