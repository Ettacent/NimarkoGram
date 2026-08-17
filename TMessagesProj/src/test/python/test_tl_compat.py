import importlib.util
import pathlib
import sys
import types
import unittest

REPO = pathlib.Path(__file__).resolve().parents[4]
MODULE_PATH = REPO / "TMessagesProj/src/main/python/tl_compat.py"

class TlCompatTest(unittest.TestCase):
    def setUp(self):
        self.old_java = sys.modules.get("java")
        self.calls = []
        self.existing = object()
        self.moved = object()

        class RealTlrpc:
            pass

        RealTlrpc.TL_existing = self.existing

        def jclass(name):
            self.calls.append(name)
            if name == "org.telegram.tgnet.TLRPC":
                return RealTlrpc
            if name == "org.telegram.tgnet.tl.TL_update$TL_moved":
                return self.moved
            raise RuntimeError(name)

        java = types.ModuleType("java")
        java.jclass = jclass
        sys.modules["java"] = java
        spec = importlib.util.spec_from_file_location("_tl_compat_test", MODULE_PATH)
        self.module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.module)

    def tearDown(self):
        if self.old_java is None:
            sys.modules.pop("java", None)
        else:
            sys.modules["java"] = self.old_java

    def test_import_is_lazy_and_native_tlrpc_wins(self):
        self.assertEqual(["org.telegram.tgnet.TLRPC"], self.calls)
        self.assertIs(self.existing, self.module.TLRPC.TL_existing)
        self.assertEqual(["org.telegram.tgnet.TLRPC"], self.calls)

    def test_moved_class_uses_binary_name_and_is_cached(self):
        self.assertIs(self.moved, self.module.TLRPC.TL_moved)
        self.assertIn(
            "org.telegram.tgnet.tl.TL_update$TL_moved", self.calls)
        count = len(self.calls)
        self.assertIs(self.moved, self.module.TLRPC.TL_moved)
        self.assertEqual(count, len(self.calls))

    def test_unknown_class_is_negative_cached(self):
        self.assertIsNone(self.module.get_moved_class("TL_missing"))
        count = len(self.calls)
        self.assertIsNone(self.module.get_moved_class("TL_missing"))
        self.assertEqual(count, len(self.calls))

if __name__ == "__main__":
    unittest.main()
