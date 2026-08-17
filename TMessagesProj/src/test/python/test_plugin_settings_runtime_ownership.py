import pathlib
import unittest

REPO = pathlib.Path(__file__).resolve().parents[4]
JAVA = REPO / "TMessagesProj/src/main/java/app/nimarkogram/messenger/plugins"

def read(relative_path):
    return (JAVA / relative_path).read_text()

class PluginSettingsRuntimeOwnershipTest(unittest.TestCase):
    def test_every_callback_setting_can_release_borrowed_python_references(self):
        models = JAVA / "models"
        setting_item = (models / "SettingItem.java").read_text()
        self.assertIn("public void clearPythonReferences()", setting_item)
        self.assertIn("onLongClickCallback = null;", setting_item)
        self.assertNotIn(".close()", setting_item)

        callback_models = (
            "EditTextSetting.java",
            "InputSetting.java",
            "SelectorSetting.java",
            "SwitchSetting.java",
            "TextSetting.java",
            "CustomSetting.java",
        )
        for filename in callback_models:
            source = (models / filename).read_text()
            self.assertIn(
                "public void clearPythonReferences()", source, filename)
            self.assertIn(
                "super.clearPythonReferences();", source, filename)
            self.assertNotIn(".close()", source, filename)

    def test_settings_screen_is_exact_runtime_owned_and_drops_models(self):
        activity = read("ui/PluginSettingsActivity.java")
        self.assertIn(
            "PluginUiRegistry.RuntimeOwnedUi", activity)
        self.assertIn(
            "registerRuntimeOwnedUi(\n"
            "                        runtimeToken, this)",
            activity,
        )
        self.assertIn(
            "unregisterRuntimeOwnedUi(runtimeToken, this)", activity)
        self.assertIn(
            "public void clearPluginUiReferences(", activity)
        self.assertIn("clearRetainedPythonReferences();", activity)
        self.assertIn("item.clearPythonReferences();", activity)
        self.assertIn("createSubFragmentCallback = null;", activity)
        self.assertIn("customViewCache.clear();", activity)

    def test_attached_plugin_views_are_rejected_without_external_reparent(self):
        activity = read("ui/PluginSettingsActivity.java")
        custom_start = activity.index(
            'case "custom":')
        custom_end = activity.index(
            "\n            if (uItem != null)", custom_start)
        custom = activity[custom_start:custom_end]

        self.assertGreaterEqual(
            custom.count("if (v.getParent() != null)"), 2)
        self.assertIn(
            '"nimarko: attached plugin custom "', custom)
        self.assertIn(
            '"nimarko: bind_view attached a "', custom)
        self.assertNotIn(
            "((android.view.ViewGroup) pluginView.getParent())"
            ".removeView(pluginView)",
            custom,
        )
        self.assertIn(
            "pluginView.getParent() == host", custom)
        self.assertIn("host.removeView(pluginView);", custom)

    def test_edit_cell_cancels_debounce_on_exact_runtime_revocation(self):
        cell = read("ui/components/PluginEditTextCell.java")
        self.assertIn(
            "implements PluginUiRegistry.RuntimeOwnedUi", cell)
        self.assertIn(
            "registerRuntimeOwnedUi(\n"
            "                            newToken, this)",
            cell,
        )
        self.assertIn("private void dropPendingSave()", cell)
        clear_start = cell.index(
            "public void clearPluginUiReferences(")
        bind_start = cell.index("public void bind(", clear_start)
        clear = cell[clear_start:bind_start]
        self.assertIn("dropPendingSave();", clear)
        self.assertIn("currentSetting = null;", clear)
        self.assertIn("pluginId = null;", clear)
        self.assertIn("ownedRuntimeToken = null;", clear)

    def test_plugin_dialog_rejects_attached_custom_view(self):
        registry = read("ui/PluginUiRegistry.java")
        alert = (
            REPO / "TMessagesProj/src/main/python/ui/alert.py"
        ).read_text()
        self.assertIn(
            "public static boolean canAttachPluginView(", registry)
        self.assertIn("view.getParent() == null", registry)
        self.assertIn(
            "PluginUiRegistry.canAttachPluginView(\n"
            "                    self._runtime_token, view)",
            alert,
        )

    def test_plugin_dialog_show_crosses_frame_and_rechecks_ownership(self):
        registry = read("ui/PluginUiRegistry.java")
        callback = read("ui/PluginDialogCallback.java")
        alert = (
            REPO / "TMessagesProj/src/main/python/ui/alert.py"
        ).read_text()
        show_start = registry.index(
            "public static boolean showDialog(")
        show_end = registry.index(
            "public static boolean showBulletin(", show_start)
        show = registry[show_start:show_end]
        self.assertIn("scheduleAfterTraversal(() ->", show)
        self.assertIn("!isRuntimeCurrent(runtimeToken)", show)
        self.assertIn("!isRegistered(runtimeToken, dialog)", show)
        self.assertIn("dialog.show();", show)
        self.assertLess(
            show.index("scheduleAfterTraversal(() ->"),
            show.index("dialog.show();"),
        )
        self.assertIn(
            "DialogInterface.OnShowListener", callback)
        self.assertIn("TYPE_SHOW = 5", callback)
        self.assertIn('localOwner.callAttr("_on_dialog_shown"', callback)
        self.assertIn("def _on_dialog_shown(self, dialog):", alert)
        self.assertIn("dialog.setOnShowListener(", alert)

if __name__ == "__main__":
    unittest.main()
