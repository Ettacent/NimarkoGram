import pathlib
import unittest

REPO = pathlib.Path(__file__).resolve().parents[4]
JAVA = REPO / 'TMessagesProj/src/main/java'
ACTIVITY = JAVA / (
    'app/nimarkogram/messenger/plugins/ui/PluginsActivity.java')
SHEET = JAVA / (
    'app/nimarkogram/messenger/plugins/ui/components/'
    'InstallPluginBottomSheet.java')

def source(path):
    return path.read_text(encoding='utf-8')

def between(text, start, end):
    start_index = text.index(start)
    return text[start_index:text.index(end, start_index)]

class PluginPickerImportSecurityTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.activity = source(ACTIVITY)
        cls.sheet = source(SHEET)

    def test_copy_is_exclusive_private_and_bounded_while_streaming(self):
        copy = between(
            self.activity,
            'private String copyUriToCache(Uri uri)',
            'private static File getPickerImportDirectory(')

        for required in (
                'MAX_PICKER_PLUGIN_BYTES = 4L * 1024L * 1024L',
                'UUID.randomUUID().toString().replace("-", "")',
                'OsConstants.O_EXCL',
                'OsConstants.O_NOFOLLOW',
                'OsConstants.O_CLOEXEC',
                'OsConstants.S_IRUSR | OsConstants.S_IWUSR',
                'count > MAX_PICKER_PLUGIN_BYTES - total',
                'ownedOutput.getFD().sync()',
                'syncPickerDirectory(outDir)',
                'deleteManagedPickerImport(out)'):
            self.assertIn(required, self.activity if required.startswith(
                'MAX_PICKER_PLUGIN_BYTES') else copy)

        limit = copy.index(
            'count > MAX_PICKER_PLUGIN_BYTES - total')
        write = copy.index('output.write(buffer, 0, count)')
        self.assertLess(limit, write)
        self.assertNotIn('OpenableColumns.SIZE', copy)
        self.assertNotIn('new FileOutputStream(out)', copy)

    def test_only_strict_host_names_are_swept(self):
        self.assertIn(
            '^\\\\.plugin-picker-[0-9a-f]{32}\\\\.plugin$',
            self.activity)
        sweep = between(
            self.activity,
            'private static void sweepOrphanedPickerImports()',
            'private static void deleteManagedPickerImport(')
        delete = between(
            self.activity,
            'private static void deleteManagedPickerImport(',
            'private static void syncPickerDirectory(')

        self.assertIn('PICKER_IMPORT_FILE_PATTERN.matcher(name).matches()',
                      sweep)
        self.assertIn('ACTIVE_PICKER_IMPORTS.keySet()', sweep)
        self.assertIn('!active.contains(candidate.getAbsolutePath())', sweep)
        self.assertNotIn('deleteDirectory', sweep)
        self.assertIn('Os.lstat(', delete)
        self.assertIn('OsConstants.S_IFREG', delete)
        self.assertIn('OsConstants.S_IFLNK', delete)
        self.assertIn('syncPickerDirectory(candidate.getParentFile())',
                      delete)
        directory = between(
            self.activity,
            'private static File getPickerImportDirectory(',
            'private static String normalizeManagedPickerImport(')
        self.assertIn('Os.lstat(outDir.getAbsolutePath())', directory)
        self.assertIn('OsConstants.S_IFDIR', directory)
        self.assertIn('syncPickerDirectory(cacheDir)', directory)

        fragment_create = between(
            self.activity,
            'public boolean onFragmentCreate()',
            'public void onFragmentDestroy()')
        self.assertIn('sweepOrphanedPickerImports();', fragment_create)

    def test_source_ownership_is_exactly_claimed_or_released(self):
        result = between(
            self.activity,
            'public void onActivityResultFragment(',
            'private String copyUriToCache(Uri uri)')
        self.assertIn(
            'PluginsController.getInstance().showInstallDialog(',
            result)
        self.assertIn('finally {', result)
        self.assertIn('releaseUnclaimedPickerImportSource(path)', result)

        show = between(
            self.sheet,
            'public void show()',
            'private synchronized boolean claimPickerImportSource()')
        self.assertIn('claimPickerImportSource()', show)
        self.assertIn('super.show();', show)
        self.assertIn('!isShowing() || isDismissed()', show)
        self.assertIn('releasePickerImportSource(true)', show)

        dismiss = between(
            self.sheet,
            'public void dismiss()',
            'protected void onSwipeStarts()')
        self.assertIn('releasePickerImportSource(false)', dismiss)
        self.assertIn(
            'PluginsActivity.releasePickerImportSource(path)',
            self.sheet)

    def test_external_view_gets_a_bounded_read_grace(self):
        open_source = between(
            self.sheet,
            'private void openSourceFile(',
            'private void showSuccessBulletin(')
        self.assertLess(
            open_source.index('AndroidUtilities.openForView('),
            open_source.index('dismiss();'))
        self.assertIn('deferPickerImportRelease()', open_source)
        self.assertIn('EXTERNAL_VIEW_SOURCE_GRACE_MS', open_source)
        self.assertIn('releasePickerImportSource(true)', open_source)

if __name__ == '__main__':
    unittest.main()
