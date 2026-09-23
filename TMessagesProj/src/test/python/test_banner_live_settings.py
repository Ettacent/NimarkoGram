"""Execute banner setting/status mutations on JVM IO/UI-queue stubs, not Android."""
import subprocess
import tempfile
import unittest
from pathlib import Path

from test_banner_background_stability import block

ROOT = Path(__file__).resolve().parents[2]
BANNERS = ROOT / 'main/java/app/nimarkogram/messenger/banners'


def harness():
    controller = (BANNERS / 'NimarkoBannerController.java').read_text()
    config = (BANNERS / 'NimarkoBannerConfig.java').read_text()
    renderer = (BANNERS / 'NimarkoBannerRenderer.java').read_text()
    template = (ROOT / 'test/fixtures/BannerLiveSettingsHarness.java.txt').read_text()
    for marker, source, signatures in (
        ('CONTROLLER', controller, (
            'private static final class Scope', 'private static final class CacheKey',
            'private static final class Meta', 'public boolean shouldHideAvatar(',
            'private long beginStatusMutation(', 'private boolean statusRevisionMatches(',
            'private long finishStatusMutationLocked(', 'private int fetchStatus(',
            'public void setHideAvatarRemote(', 'private void invalidate()',
        )),
        ('CONFIG', config, (
            'public static void setEnabled(', 'public static void setUseAvatar(',
            'public static void setLiteMode(', 'private static void notifyDisplayChanged()',
        )),
        ('RENDERER', renderer, ('public void invalidateBannerState()', 'public void onSettingsChanged()')),
    ):
        template = template.replace('/* ' + marker + ' */', '\n'.join(block(source, s) for s in signatures))
    return template


def execute(source):
    with tempfile.TemporaryDirectory(prefix='banner-live-settings-') as directory:
        java = Path(directory) / 'Harness.java'
        java.write_text(source)
        result = subprocess.run(['javac', str(java)], capture_output=True, text=True, timeout=30)
        if result.returncode:
            raise AssertionError(result.stderr)
        return subprocess.run(['java', '-ea', '-cp', directory, 'Harness'],
                              capture_output=True, text=True, timeout=30)


class BannerLiveSettingsTests(unittest.TestCase):
    def test_production_mutations(self):
        result = execute(harness())
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_negative_controls(self):
        source = harness()
        for before, after, message in (
            ('if (eid == my) return myHideAvatar;', '', 'own POST overrides stale public metadata'),
            ('if (displayChanged) invalidate();', 'if (!old.equals(myStatus)) invalidate();',
             'same-status hide/sound change invalidates'),
            ('                invalidate();\n                uiOk', '                uiOk',
             'POST invalidates settled frame'),
            ('frameLastExtra = -999f;', '', 'metadata bypasses video geometry quick path'),
            ('lastFxExtra = -1;', '', 'settings bypass FX geometry quick path'),
            ('if (renderer != null) renderer.onSettingsChanged();', '', 'config reaches retained renderer'),
            ('if (eid == my && !"approved".equals(myStatus)) return false;', '', 'pending never hides'),
        ):
            with self.subTest(message=message):
                self.assertIn(before, source)
                result = execute(source.replace(before, after, 1))
                self.assertNotEqual(result.returncode, 0)
                self.assertIn(message, result.stderr)

    def test_local_file_and_remote_metadata_wiring(self):
        source = (BANNERS / 'NimarkoBannerController.java').read_text()
        save = block(source, 'public void setLocalBanner(')
        self.assertIn('java.util.UUID.randomUUID()', save)
        self.assertLess(save.index('atomic.finishWrite(out)'), save.index('NimarkoBannerConfig.setLocalBannerPath('))
        self.assertLess(save.index('NimarkoBannerConfig.setLocalBannerPath('), save.index('safeRemove(new File(previousPath))'))
        self.assertIn('operationGeneration != localBannerGeneration', save)
        self.assertIn('!isCurrentScope(operationScope)', save)
        self.assertIn('invalidate();', block(source, 'public void removeLocalBanner()'))
        self.assertIn('hasCached && (!checking || upd)', block(source, 'private void syncBanner('))


if __name__ == '__main__':
    unittest.main()
