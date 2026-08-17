import pathlib
import unittest

REPO = pathlib.Path(__file__).resolve().parents[4]
JAVA = REPO / "TMessagesProj/src/main/java"

class NativeCrashDiagnosticsStartupTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.handler = (
            JAVA
            / "app/nimarkogram/messenger/plugins/utils/NativeCrashHandler.java"
        ).read_text()
        cls.application = (
            JAVA / "org/telegram/messenger/ApplicationLoader.java"
        ).read_text()

    def test_application_start_only_schedules_postmortem_work(self):
        on_create = self.application[
            self.application.index("public void onCreate()"):
        ]
        self.assertIn("schedulePreviousExitDiagnostics()", on_create)
        self.assertNotIn(
            "NativeCrashHandler.checkAndHandleNativeCrash()", on_create
        )

    def test_binder_and_disk_work_are_one_shot_and_off_main(self):
        self.assertIn(
            "private static final AtomicBoolean DIAGNOSTICS_SCHEDULED",
            self.handler,
        )
        self.assertIn(
            "private static final AtomicBoolean DIAGNOSTICS_HANDLED",
            self.handler,
        )
        schedule = self.handler[
            self.handler.index(
                "public static void schedulePreviousExitDiagnostics()"
            )
            : self.handler.index(
                "public static void checkAndHandleNativeCrash()"
            )
        ]
        self.assertIn('new Thread(() ->', schedule)
        self.assertIn('"ng-exit-diagnostics"', schedule)
        self.assertIn("checkAndHandleNativeCrash();", schedule)
        self.assertNotIn(
            "public static synchronized void checkAndHandleNativeCrash()",
            self.handler,
        )

    def test_process_exit_lookup_is_cached_under_a_narrow_lock(self):
        self.assertIn(
            "private static final Object EXIT_INFO_LOCK", self.handler
        )
        self.assertIn("private static volatile boolean exitInfoLoaded", self.handler)
        lookup = self.handler[
            self.handler.index(
                "private static ApplicationExitInfo lastExitInfo()"
            )
            : self.handler.index(
                "private static boolean isSupportedMainProcess()"
            )
        ]
        self.assertIn("synchronized (EXIT_INFO_LOCK)", lookup)
        self.assertIn("cachedExitInfo = result", lookup)
        self.assertIn("exitInfoLoaded = true", lookup)

if __name__ == "__main__":
    unittest.main()
