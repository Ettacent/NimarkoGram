"""Guard Monet switch separator ownership and approved local-banner layout."""
from pathlib import Path
import unittest
from test_recording_composer_lifecycle import method

JAVA = Path(__file__).resolve().parents[2] / 'main/java'


class MonetSwitchDividerTests(unittest.TestCase):
    def test_vertical_line_has_independent_provider_aware_paint(self):
        source = (JAVA / 'org/telegram/ui/Cells/NotificationsCheckCell.java').read_text()
        draw = method(source, 'protected void onDraw(')
        self.assertIn('if (MonetHelper.isActiveMonetTheme())', draw)
        self.assertIn('Theme.key_windowBackgroundWhite, resourcesProvider', draw)
        self.assertIn('Theme.key_windowBackgroundWhiteBlackText, resourcesProvider', draw)
        self.assertIn('paint = switchDividerPaint;', draw)
        self.assertIn('x + dp(1)', draw)
        self.assertNotIn('Theme.dividerPaint.set', draw)
        self.assertIn('Theme.dividerPaint', draw[:draw.index('if (drawLine)')])

    def test_disabled_local_picker_is_inside_card(self):
        source = (JAVA / 'app/nimarkogram/messenger/preferences/BannerPreferencesActivity.java').read_text()
        branch = source[source.index('items.add(UItem.asHeader(LocaleController.getString(R.string.NM_BAN_LocalHeader)))'):]
        branch = branch[:branch.index('} else {')]
        self.assertIn('asSettingsLink(ID_PICK_LOCAL', branch)
        self.assertIn('NM_BAN_LocalDisabledHint)).setEnabled(false)', branch)
        self.assertIn('UItem.asShadow(null)', branch)
        self.assertNotIn('UItem.asShadow(LocaleController', branch)


if __name__ == '__main__':
    unittest.main()
