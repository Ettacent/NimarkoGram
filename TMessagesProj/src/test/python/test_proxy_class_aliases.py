"""Python proxy aliases must resolve canonical host types, never Java wrappers."""
import importlib.util
from pathlib import Path
import sys
import types
import unittest
from unittest.mock import patch


PYTHON = Path(__file__).resolve().parents[2] / "main/python"
MOVED = ("ProxySettings", "WebProxyConnectionTester", "WebProxyTransport")
NESTED = ("ProxySettings$Builder", "ProxySettings$Type",
          "WebProxyConnectionTester$TestImplementation",
          "WebProxyTransport$ReadyCallback")
OLD = "org.telegram.proxy."
CURRENT = "org.telegram.utils.proxy."


def load(name):
    spec = importlib.util.spec_from_file_location(name, PYTHON / (name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class ProxyClassAliasTests(unittest.TestCase):
    def setUp(self):
        self.classes = {"java.lang.Object": type("JavaObject", (), {})}
        self.classes.update({CURRENT + name: type(name, (), {})
                             for name in MOVED + NESTED})
        self.calls = []

        def jclass(name):
            self.calls.append(name)
            if name not in self.classes:
                raise LookupError(name)
            return self.classes[name]

        self.original = jclass
        self.java = types.ModuleType("java")
        self.java.jclass = jclass
        self.java.jarray = lambda clazz: list
        android = types.ModuleType("android_utils")
        android.log = lambda message: None
        self.compat = load("plugin_compat")
        modules = patch.dict(sys.modules, {"java": self.java,
                                          "android_utils": android,
                                          "plugin_compat": self.compat})
        modules.start()
        self.addCleanup(modules.stop)

    def test_direct_jclass_returns_exact_host_types(self):
        self.compat._install_jclass_compat()
        for name in MOVED + NESTED:
            with self.subTest(name=name):
                self.assertIs(self.java.jclass(OLD + name),
                              self.java.jclass(CURRENT + name))
                self.assertNotIn(OLD + name, self.calls)

    def test_find_class_before_installation_and_cache_identity(self):
        hooks = load("hook_utils")
        self.assertIs(hooks.jclass, self.original)
        self.compat._install_jclass_compat()
        for name in MOVED + NESTED:
            with self.subTest(name=name):
                canonical = self.classes[CURRENT + name]
                self.assertIs(hooks.find_class(OLD + name), canonical)
                self.assertIs(hooks.find_class(CURRENT + name), canonical)
                count = len(self.calls)
                self.assertIs(hooks.find_class(OLD + name), canonical)
                self.assertEqual(len(self.calls), count)
                self.assertIs(hooks._CLASS_CACHE[CURRENT + name], canonical)

    def test_find_class_after_installation(self):
        self.compat._install_jclass_compat()
        hooks = load("hook_utils")
        for name in MOVED + NESTED:
            self.assertIs(hooks.find_class(OLD + name), self.classes[CURRENT + name])
        self.assertIsNone(hooks.find_class(None))

    def test_only_exact_moved_outer_classes_are_remapped(self):
        self.compat._install_jclass_compat()
        for name in ("Other", "ProxySettingsExtra", "ProxySettings.Builder"):
            with self.subTest(name=name):
                self.classes[CURRENT + name] = type(name, (), {})
                self.assertIsNone(self.compat._remap_proxy_class(OLD + name))
                with self.assertRaises(LookupError):
                    self.java.jclass(OLD + name)
                self.assertNotIn(CURRENT + name, self.calls)
        with self.assertRaises(LookupError):
            self.java.jclass(OLD + "ProxySettings$Missing")
        self.assertIsNone(load("hook_utils").find_class(OLD + "ProxySettings$Missing"))

    def test_canonical_proxy_type_wins_over_old_class(self):
        self.classes[OLD + "ProxySettings"] = type("StaleWrapper", (), {})
        self.compat._install_jclass_compat()
        canonical = self.classes[CURRENT + "ProxySettings"]
        self.assertIs(self.java.jclass(OLD + "ProxySettings"), canonical)
        self.assertIs(load("hook_utils").find_class(OLD + "ProxySettings"), canonical)

    def test_extera_bridge_preference_and_fallback_are_unchanged(self):
        old = "com.exteragram.messenger.ExistingBridge"
        new = "app.nimarkogram.messenger.ExistingBridge"
        self.classes[old] = type("Bridge", (), {})
        self.classes[new] = type("Host", (), {})
        moved = "com.exteragram.messenger.Moved"
        self.classes["app.nimarkogram.messenger.Moved"] = type("Moved", (), {})
        self.compat._install_jclass_compat()
        installed = self.java.jclass
        self.compat._install_jclass_compat()
        self.assertIs(self.java.jclass, installed)
        self.assertIs(self.java.jclass(old), self.classes[old])
        self.assertIs(self.java.jclass(moved),
                      self.classes["app.nimarkogram.messenger.Moved"])


if __name__ == "__main__":
    unittest.main()
