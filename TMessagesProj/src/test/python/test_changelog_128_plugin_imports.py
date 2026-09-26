"""Run with python -B -m unittest discover -s TMessagesProj/src/test/python
-p test_changelog_128_plugin_imports.py. No Android/Gradle required.
"""
import importlib
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import threading
import types
import unittest
from unittest.mock import patch


REPO = Path(__file__).resolve().parents[4]
SOURCE = REPO / 'TMessagesProj/src/main/python/plugin_imports.py'


class Token:
    def __init__(self, name, generation=1):
        self.name, self.generation = name, generation

    def getPluginId(self):
        return self.name

    def getGeneration(self):
        return self.generation

    def getInstanceId(self):
        return self.generation


class Controller:
    RUNTIME_TASK_DROP = 0

    def __init__(self):
        self.local = threading.local()
        self.current = {}

    def getInstance(self):
        return self

    def isPluginRuntimeCurrent(self, token):
        return self.current.get(token.name) is token

    def getPluginRuntimeTaskDecision(self, token):
        return 1 if self.isPluginRuntimeCurrent(token) else 0

    def captureCurrentPluginRuntime(self):
        return getattr(self.local, 'token', None)


class PluginImportsTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.path = sys.path[:]
        self.meta = sys.meta_path[:]
        self.modules = sys.modules.copy()
        self.controller = Controller()
        package = types.ModuleType('app.nimarkogram.messenger.plugins')
        package.PluginsController = self.controller
        self.stub = patch.dict(sys.modules, {package.__name__: package})
        self.stub.start()
        spec = importlib.util.spec_from_file_location('_test_plugin_imports', SOURCE)
        self.loader = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(self.loader)
        self.loader.install(self.root)

    def tearDown(self):
        sys.path[:] = self.path
        sys.meta_path[:] = self.meta
        self.stub.stop()
        for name in set(sys.modules) - self.modules.keys():
            sys.modules.pop(name, None)
        self.temp.cleanup()

    def entry(self, name='audit_entry', body='value = 42\n'):
        path = self.root / (name + '.py')
        path.write_text('__id__ = ' + repr(name) + '\n' + body)
        importlib.invalidate_caches()
        return path

    def token(self, name='audit_entry', generation=1):
        token = Token(name, generation)
        self.controller.current[name] = token
        self.controller.local.token = token
        return token

    def test_cached_stdlib_collision_never_stamps_or_removes_foreign_module(self):
        path = self.entry('json', "raise AssertionError('executed')\n")
        token = self.token('json')
        with self.assertRaisesRegex(ImportError, 'collides'):
            self.loader.load_plugin('json', path, token)
        self.loader.remove_plugin('json', token)
        self.assertIs(sys.modules['json'], json)
        self.assertFalse(hasattr(json, '__nimarko_runtime_token__'))

    def test_cold_collision_does_not_execute_foreign_module_or_package(self):
        foreign = self.root / 'libraries'
        foreign.mkdir()
        sys.path.insert(0, str(foreign))
        for name, package in [('audit_library', False), ('audit_package', True)]:
            target = foreign / (name + '.py')
            if package:
                (foreign / name).mkdir()
                target = foreign / name / '__init__.py'
            target.write_text("raise AssertionError('foreign executed')\n")
            with self.assertRaisesRegex(ImportError, 'collides'):
                self.loader.load_plugin(name, self.entry(name), self.token(name))
            self.assertNotIn(name, sys.modules)

    def test_disabled_entry_blocked_but_helpers_packages_and_stdlib_work(self):
        self.entry(body="raise AssertionError('disabled executed')\n")
        with self.assertRaisesRegex(ImportError, 'host'):
            importlib.import_module('audit_entry')
        self.assertNotIn('audit_entry', sys.modules)
        (self.root / 'audit_helper.py').write_text('import json\nvalue = json.loads("42")\n')
        (self.root / 'audit_helpers').mkdir()
        (self.root / 'audit_helpers' / '__init__.py').write_text('value = 7\n')
        self.assertEqual(importlib.import_module('audit_helper').value, 42)
        self.assertEqual(importlib.import_module('audit_helpers').value, 7)

    def test_exact_entry_self_import_and_token_visible_before_execution(self):
        path = self.entry(body='import audit_entry\nassert audit_entry is __import__(__name__)\n'
                          'seen = __nimarko_runtime_token__\n')
        token = self.token()
        module = self.loader.load_plugin('audit_entry', path, token)
        self.assertIs(module.seen, token)
        self.assertEqual(module.__name__, 'audit_entry')
        self.assertIs(importlib.import_module('audit_entry'), module)
        self.assertIs(self.loader.load_plugin('audit_entry', path, token), module)
        with self.assertRaisesRegex(ImportError, 'host'):
            importlib.reload(module)
        self.loader.remove_plugin('audit_entry', token)
        with self.assertRaisesRegex(ImportError, 'host'):
            importlib.import_module('audit_entry')

    def test_generation_and_identity_checked_on_cleanup(self):
        path = self.entry()
        old = self.token()
        self.loader.load_plugin('audit_entry', path, old)
        self.loader.remove_plugin('audit_entry', old)
        new = self.token(generation=2)
        module = self.loader.load_plugin('audit_entry', path, new)
        self.loader.remove_plugin('audit_entry', old)
        self.assertIs(sys.modules['audit_entry'], module)
        replacement = types.ModuleType('audit_entry')
        sys.modules['audit_entry'] = replacement
        self.loader.remove_plugin('audit_entry', new)
        self.assertIs(sys.modules['audit_entry'], replacement)
        self.assertNotIn('audit_entry', self.loader._owned)

    def test_failure_rolls_back_and_new_generation_can_retry(self):
        path = self.entry(body="raise RuntimeError('top-level failure')\n")
        with self.assertRaisesRegex(RuntimeError, 'top-level'):
            self.loader.load_plugin('audit_entry', path, self.token())
        self.assertNotIn('audit_entry', sys.modules)
        self.assertNotIn('audit_entry', self.loader._owned)
        self.entry(body='value = 123456\n')
        module = self.loader.load_plugin('audit_entry', path, self.token(generation=2))
        self.assertEqual(module.value, 123456)

    def test_revoked_or_wrong_scope_cannot_execute(self):
        path = self.entry(body="raise AssertionError('executed')\n")
        token = self.token()
        self.controller.local.token = None
        with self.assertRaisesRegex(ImportError, 'authorized'):
            self.loader.load_plugin('audit_entry', path, token)
        self.controller.local.token = token
        self.controller.current.clear()
        with self.assertRaisesRegex(ImportError, 'authorized'):
            self.loader.load_plugin('audit_entry', path, token)
        self.assertNotIn('audit_entry', sys.modules)

    def test_revoke_during_execution_rolls_back(self):
        path = self.entry(body='from app.nimarkogram.messenger.plugins import PluginsController\n'
                          'PluginsController.current.clear()\n')
        with self.assertRaisesRegex(ImportError, 'authorized'):
            self.loader.load_plugin('audit_entry', path, self.token())
        self.assertNotIn('audit_entry', sys.modules)
        self.assertFalse(self.loader._owned)

    def test_concurrent_normal_import_waits_for_initialization(self):
        bridge = types.ModuleType('audit_bridge')
        bridge.entered, bridge.release = threading.Event(), threading.Event()
        sys.modules['audit_bridge'] = bridge
        path = self.entry(body='import audit_bridge\naudit_bridge.entered.set()\n'
                          'assert audit_bridge.release.wait(5)\nvalue = 42\n')
        token = self.token()
        results, errors = [], []
        imported = threading.Event()

        def load():
            try:
                self.controller.local.token = token
                results.append(self.loader.load_plugin('audit_entry', path, token))
            except BaseException as error:
                errors.append(error)

        def normal_import():
            try:
                results.append(importlib.import_module('audit_entry'))
            except BaseException as error:
                errors.append(error)
            finally:
                imported.set()

        first = threading.Thread(target=load)
        second = threading.Thread(target=normal_import)
        first.start()
        try:
            self.assertTrue(bridge.entered.wait(3))
            second.start()
            self.assertFalse(imported.wait(0.05))
        finally:
            bridge.release.set()
            first.join(5)
            if second.ident is not None:
                second.join(5)
        self.assertFalse(first.is_alive() or second.is_alive())
        self.assertFalse(errors, errors)
        self.assertEqual(len(results), 2)
        self.assertIs(results[0], results[1])
        self.assertEqual(results[0].value, 42)


if __name__ == '__main__':
    unittest.main()
