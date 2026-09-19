"""Source-contract checks; no Android build or device execution."""
import pathlib
import unittest

JAVA = pathlib.Path(__file__).resolve().parents[4] / "TMessagesProj/src/main/java"
PREFS = "app/nimarkogram/messenger/preferences/"


def read(path):
    return (JAVA / path).read_text()


class PreferencesSoundRegressions(unittest.TestCase):
    def test_section_fill_uses_animation_alpha(self):
        source = read("org/telegram/ui/Components/RecyclerListView.java")
        self.assertIn("sections.add(new SectionsDrawer.Section(from, to, child.getAlpha()))", source)
        self.assertIn("sectionBackgroundPaint.setColor(multAlpha(Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider), alpha))", source)

    def test_media_has_no_special_animation_delay(self):
        source = read(PREFS + "NimarkoMediaPreferencesActivity.java")
        self.assertNotIn("setDelayAnimations(true)", source)
        self.assertNotIn("setRemoveDuration", source)
        self.assertIn("ID_SHADOW_PLATFORMS", source)

    def test_search_tracks_layout_for_entire_pulse_and_cleans_up(self):
        for base in ("BasePreferencesActivity", "NimarkoUniversalPreferencesActivity"):
            source = read(PREFS + base + ".java")
            self.assertIn("new SettingsSearchHighlight(listView,", source)
            self.assertIn("if (searchHighlight != null) searchHighlight.run();", source)
        source = read(PREFS + "SettingsSearchHighlight.java")
        self.assertIn("list.hasPendingAdapterUpdates()", source)
        self.assertIn("list.findPositionByItemId(itemId)", source)
        self.assertIn("holder.itemView != highlightedView", source)
        self.assertIn("list.updateSelector();", source)
        self.assertIn("list.postDelayed(this, 700)", source)
        self.assertIn("onViewDetachedFromWindow", source)
        self.assertIn("removeOnPreDrawListener(this)", source)

    def test_loading_sound_does_not_save_default(self):
        source = read("org/telegram/ui/NotificationsSoundActivity.java")
        load = source.split("private void loadTones()", 1)[1].split("public static String findRingtonePathByName", 1)[0]
        self.assertNotIn("selectedToneChanged = true", load)
        self.assertIn('preferences.getString(prefPath, "Default")', source)
        refresh = source.split("public void didReceivedNotification", 1)[1].split("private void trimTitle", 1)[0]
        self.assertIn("currentDocuments.get(cachedTone.document.id)", refresh)
        self.assertNotIn("selectedToneChanged = true", refresh)
        self.assertNotIn("selectedTone = systemTones.get(0)", refresh)

    def test_sound_ownership_is_topic_scoped_and_not_only_live_updates(self):
        source = read("org/telegram/messenger/NotificationsSettingsFacade.java")
        self.assertIn('editor.putBoolean("custom_" + NotificationsController.getSharedPrefKey(dialogId, topicId, true), true)', source)
        self.assertNotIn('editor.putBoolean("custom_" + dialogId, true)', source)
        self.assertNotIn("if (serverUpdate && dialogId != 0)", source)

    def test_explicit_default_is_not_serialized_as_local_ringtone(self):
        source = read("org/telegram/messenger/NotificationsController.java")
        self.assertIn('else if (soundPath.equalsIgnoreCase("Default")) {\n                req.settings.sound = new TLRPC.TL_notificationSoundDefault();', source)

    def test_picked_sound_keeps_ownership_and_invalidates_channel_after_save(self):
        source = read("org/telegram/ui/NotificationsSoundActivity.java")
        save = source.split("public void onFragmentDestroy()", 1)[1].split("public void startDocumentSelectActivity", 1)[0]
        self.assertIn("selectedTone != null && selectedToneChanged", save)
        self.assertIn('editor.putBoolean("custom_" + NotificationsController.getSharedPrefKey(dialogId, topicId), true)', save)
        self.assertLess(save.index("editor.apply();"), save.index("deleteNotificationChannel(dialogId, topicId)"))


if __name__ == "__main__":
    unittest.main()
