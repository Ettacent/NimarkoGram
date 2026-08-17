import pathlib
import unittest

REPO = pathlib.Path(__file__).resolve().parents[4]
JAVA = REPO / 'TMessagesProj/src/main/java'
ACTIVITY = JAVA / (
    'app/nimarkogram/messenger/plugins/ui/PluginsActivity.java')
SHEET = JAVA / (
    'app/nimarkogram/messenger/plugins/ui/components/'
    'InstallPluginBottomSheet.java')
EXECUTOR = JAVA / (
    'app/nimarkogram/messenger/plugins/ui/PluginUiDiskExecutor.java')

def source(path):
    return path.read_text(encoding='utf-8')

def between(text, start, end):
    start_index = text.index(start)
    return text[start_index:text.index(end, start_index)]

class PluginUiDiskIoOffMainRegressionTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.activity = source(ACTIVITY)
        cls.sheet = source(SHEET)
        cls.executor = source(EXECUTOR)

    def test_executor_is_one_thread_bounded_and_never_caller_runs(self):
        self.assertIn('new ThreadPoolExecutor(', self.executor)
        self.assertRegex(
            self.executor,
            r'new ThreadPoolExecutor\(\s*1,\s*1,')
        self.assertIn(
            'new ArrayBlockingQueue<>(MAX_QUEUED_OPERATIONS)',
            self.executor)
        self.assertIn(
            'new ThreadPoolExecutor.AbortPolicy()', self.executor)
        self.assertNotIn('CallerRunsPolicy', self.executor)
        self.assertIn(
            'public static boolean execute(', self.executor)

    def test_picker_copy_has_pre_post_epochs_and_main_publication(self):
        result = between(
            self.activity,
            'public void onActivityResultFragment(',
            'private boolean isPickerIoOperationCurrent(')
        copy_call = result.index('copyUriToCache(uri)')
        ui_post = result.index('AndroidUtilities.runOnUIThread', copy_call)
        precheck = result.index(
            'isPickerIoOperationCurrent(', result.index(
                'PluginUiDiskExecutor.execute('))
        postcheck = result.index(
            'isPickerIoOperationCurrent(', precheck + 1)

        self.assertLess(precheck, copy_call)
        self.assertLess(copy_call, postcheck)
        self.assertLess(postcheck, ui_post)
        self.assertIn('publishPickerImportResult(', result[ui_post:])
        self.assertIn('isPickerUiOperationCurrent(', result)

    def test_staging_security_io_is_reached_only_from_serial_tasks(self):
        fragment_create = between(
            self.activity,
            'public boolean onFragmentCreate()',
            'public void onFragmentDestroy()')
        release = between(
            self.activity,
            'public static void releasePickerImportSource(',
            'private static void releaseUnclaimedPickerImportSource(')
        enqueue_delete = between(
            self.activity,
            'private static void enqueuePickerImportDelete(',
            'private static void sweepOrphanedPickerImports()')

        self.assertIn('PluginUiDiskExecutor.execute(', fragment_create)
        self.assertIn('sweepOrphanedPickerImports();', fragment_create)
        self.assertIn('enqueuePickerImportDelete(', release)
        self.assertNotIn('deleteManagedPickerImport(', release)
        self.assertIn('PluginUiDiskExecutor.execute(', enqueue_delete)
        self.assertIn('deleteManagedPickerImport(file)', enqueue_delete)

        for operation in (
                'openInputStream(uri)',
                'ownedOutput.getFD().sync()',
                'Os.fsync(descriptor)',
                'Os.lstat('):
            self.assertIn(operation, self.activity)

    def test_source_probe_is_off_main_and_sheet_epoch_guarded(self):
        open_source = between(
            self.sheet,
            'private void openSourceFile(',
            'private void showSuccessBulletin(')
        exists = open_source.index('file.exists()')
        ui_post = open_source.index('AndroidUtilities.runOnUIThread', exists)
        precheck = open_source.index(
            'isSourceViewIoCurrent(', open_source.index(
                'PluginUiDiskExecutor.execute('))
        postcheck = open_source.index(
            'isSourceViewIoCurrent(', precheck + 1)

        self.assertLess(precheck, exists)
        self.assertLess(exists, postcheck)
        self.assertLess(postcheck, ui_post)
        self.assertIn('publishOpenSourceResult(', open_source[ui_post:])
        self.assertIn('isSourceViewUiCurrent(', open_source)

        dismiss = between(
            self.sheet,
            'public void dismiss()',
            'protected void onSwipeStarts()')
        self.assertIn('sourceViewOperationEpoch++;', dismiss)

if __name__ == '__main__':
    unittest.main()
