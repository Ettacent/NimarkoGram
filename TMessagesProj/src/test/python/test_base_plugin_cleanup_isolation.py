import ast
import copy
import pathlib
import sys
import threading
import types
import unittest
from typing import final

REPO = pathlib.Path(__file__).resolve().parents[4]
BASE_PLUGIN = REPO / 'TMessagesProj/src/main/python/base_plugin.py'
PLUGIN_RUNTIME = REPO / 'TMessagesProj/src/main/python/plugin_runtime.py'

class _RuntimeToken:
    def __init__(self, plugin_id, generation):
        self.plugin_id = plugin_id
        self.generation = generation

    def getPluginId(self):
        return self.plugin_id

class _FakeController:
    def __init__(self):
        self.active_token = None
        self.runtime_scopes = []
        self.cleanup_calls = []
        self.broad_calls = []
        self.hooks = {}
        self.menu_items = {}

    def captureCurrentPluginRuntime(self):
        if self.runtime_scopes:
            return self.runtime_scopes[-1]
        return None

    def isPluginRuntimeCallbackAllowed(self, token):
        return token is self.active_token

    def enterPluginRuntime(self, token):
        if token is not self.active_token:
            return False
        self.runtime_scopes.append(token)
        return True

    def exitPluginRuntime(self, token):
        if not self.runtime_scopes or self.runtime_scopes[-1] is not token:
            raise AssertionError('runtime scopes exited out of order')
        self.runtime_scopes.pop()

    def cleanupPlugin(self, plugin_id, runtime_token):
        self.cleanup_calls.append((plugin_id, runtime_token))
        self.hooks[plugin_id] = [
            record for record in self.hooks.get(plugin_id, [])
            if record[1] is not runtime_token
        ]
        self.menu_items[plugin_id] = [
            record for record in self.menu_items.get(plugin_id, [])
            if record[1] is not runtime_token
        ]
        if self.active_token is runtime_token:
            self.active_token = None

    def removeHooksByPluginId(self, plugin_id):
        self.broad_calls.append(('hooks', plugin_id))
        self.hooks.pop(plugin_id, None)

    def removeMenuItemsByPluginId(self, plugin_id):
        self.broad_calls.append(('menu', plugin_id))
        self.menu_items.pop(plugin_id, None)

class BasePluginCleanupIsolationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.controller = _FakeController()

        class PluginsController:
            @staticmethod
            def getInstance():
                return cls.controller

        module_names = (
            'app',
            'app.nimarkogram',
            'app.nimarkogram.messenger',
            'app.nimarkogram.messenger.plugins',
        )
        cls.previous_modules = {
            name: sys.modules.get(name) for name in module_names
        }
        for name in module_names[:-1]:
            module = types.ModuleType(name)
            module.__path__ = []
            sys.modules[name] = module
        plugins_module = types.ModuleType(module_names[-1])
        plugins_module.PluginsController = PluginsController
        sys.modules[module_names[-1]] = plugins_module

        runtime_tree = ast.parse(
            PLUGIN_RUNTIME.read_text(encoding='utf-8'),
            filename=str(PLUGIN_RUNTIME),
        )
        runtime_nodes = []
        for node in runtime_tree.body:
            if (isinstance(node, ast.Assign)
                    and any(isinstance(target, ast.Name)
                            and target.id == '_UNSET'
                            for target in node.targets)):
                runtime_nodes.append(copy.deepcopy(node))
            elif (isinstance(node, ast.FunctionDef)
                    and node.name in {
                        'is_callback_allowed',
                        '_await_worker_runtime',
                        'run_owned_worker',
                    }):
                runtime_nodes.append(copy.deepcopy(node))
        runtime_subset = ast.Module(
            body=runtime_nodes,
            type_ignores=[],
        )
        ast.fix_missing_locations(runtime_subset)
        runtime_namespace = {}
        exec(
            compile(runtime_subset, str(PLUGIN_RUNTIME), 'exec'),
            runtime_namespace,
        )
        cls.runtime = types.SimpleNamespace(
            is_callback_allowed=runtime_namespace[
                'is_callback_allowed'],
            run_owned_worker=runtime_namespace['run_owned_worker'],
            _UNSET=runtime_namespace['_UNSET'],
        )

        source = BASE_PLUGIN.read_text(encoding='utf-8')
        tree = ast.parse(source, filename=str(BASE_PLUGIN))
        runtime_owned = next(
            node for node in tree.body
            if isinstance(node, ast.FunctionDef)
            and node.name == '_runtime_owned'
        )
        base_plugin = next(
            node for node in tree.body
            if isinstance(node, ast.ClassDef)
            and node.name == 'BasePlugin'
        )
        cleanup = next(
            node for node in base_plugin.body
            if isinstance(node, ast.FunctionDef)
            and node.name == '_cleanup'
        )
        extracted_class = ast.ClassDef(
            name='ExtractedBasePlugin',
            bases=[],
            keywords=[],
            body=[copy.deepcopy(cleanup)],
            decorator_list=[],
        )
        extracted = ast.Module(
            body=[copy.deepcopy(runtime_owned), extracted_class],
            type_ignores=[],
        )
        ast.fix_missing_locations(extracted)
        namespace = {
            'PluginsController': PluginsController,
            'final': final,
            'plugin_runtime': cls.runtime,
        }
        exec(compile(extracted, str(BASE_PLUGIN), 'exec'), namespace)
        cls.plugin_class = namespace['ExtractedBasePlugin']

    @classmethod
    def tearDownClass(cls):
        for name, previous in cls.previous_modules.items():
            if previous is None:
                sys.modules.pop(name, None)
            else:
                sys.modules[name] = previous

    def setUp(self):
        self.controller.active_token = None
        self.controller.runtime_scopes.clear()
        self.controller.cleanup_calls.clear()
        self.controller.broad_calls.clear()
        self.controller.hooks.clear()
        self.controller.menu_items.clear()

    def _plugin(self, token, unload_calls):
        plugin = self.plugin_class()
        plugin.id = token.getPluginId()
        plugin._runtime_token = token
        plugin.enabled = True
        plugin.isenabled = True
        plugin.initialized = True
        plugin._initializing = True
        plugin.log = lambda _message: None
        plugin.on_plugin_unload = lambda: unload_calls.append(token)
        return plugin

    def test_revoked_old_worker_cannot_clean_replacement_runtime(self):
        old_token = _RuntimeToken('same-plugin', 1)
        new_token = _RuntimeToken('same-plugin', 2)
        unload_calls = []
        old_plugin = self._plugin(old_token, unload_calls)
        self.controller.active_token = old_token
        worker_started = threading.Event()
        release_worker = threading.Event()

        def old_worker():
            worker_started.set()
            release_worker.wait(1.0)
            old_plugin._cleanup()

        worker = threading.Thread(
            target=lambda: self.runtime.run_owned_worker(
                old_token, old_worker, default=None))
        worker.start()
        self.assertTrue(worker_started.wait(1.0))

        self.controller.active_token = new_token
        self.controller.hooks['same-plugin'] = [
            ('old-hook', old_token),
            ('new-hook', new_token),
        ]
        self.controller.menu_items['same-plugin'] = [
            ('old-menu', old_token),
            ('new-menu', new_token),
        ]
        release_worker.set()
        worker.join(1.0)

        self.assertFalse(worker.is_alive())
        self.assertEqual(self.controller.cleanup_calls, [])
        self.assertEqual(self.controller.broad_calls, [])
        self.assertEqual(
            self.controller.hooks['same-plugin'],
            [('old-hook', old_token), ('new-hook', new_token)],
        )
        self.assertEqual(
            self.controller.menu_items['same-plugin'],
            [('old-menu', old_token), ('new-menu', new_token)],
        )
        self.assertEqual(unload_calls, [])

    def test_current_cleanup_uses_exact_token_without_unload_callback(self):
        old_token = _RuntimeToken('same-plugin', 1)
        new_token = _RuntimeToken('same-plugin', 2)
        unload_calls = []
        replacement = self._plugin(new_token, unload_calls)
        self.controller.active_token = new_token
        self.controller.hooks['same-plugin'] = [
            ('old-hook', old_token),
            ('new-hook', new_token),
        ]
        self.controller.menu_items['same-plugin'] = [
            ('old-menu', old_token),
            ('new-menu', new_token),
        ]

        replacement._cleanup()

        self.assertEqual(
            self.controller.cleanup_calls,
            [('same-plugin', new_token)],
        )
        self.assertEqual(self.controller.broad_calls, [])
        self.assertEqual(
            self.controller.hooks['same-plugin'],
            [('old-hook', old_token)],
        )
        self.assertEqual(
            self.controller.menu_items['same-plugin'],
            [('old-menu', old_token)],
        )
        self.assertEqual(unload_calls, [])
        
        self.assertTrue(replacement.enabled)
        self.assertTrue(replacement.isenabled)
        self.assertTrue(replacement.initialized)
        self.assertTrue(replacement._initializing)
        self.assertEqual(self.controller.runtime_scopes, [])

    def test_cleanup_structure_is_exact_runtime_only(self):
        tree = ast.parse(
            BASE_PLUGIN.read_text(encoding='utf-8'),
            filename=str(BASE_PLUGIN),
        )
        base_plugin = next(
            node for node in tree.body
            if isinstance(node, ast.ClassDef)
            and node.name == 'BasePlugin'
        )
        cleanup = next(
            node for node in base_plugin.body
            if isinstance(node, ast.FunctionDef)
            and node.name == '_cleanup'
        )
        decorators = {
            decorator.func.id
            for decorator in cleanup.decorator_list
            if isinstance(decorator, ast.Call)
            and isinstance(decorator.func, ast.Name)
        }
        self.assertIn('_runtime_owned', decorators)

        calls = [
            node for node in ast.walk(cleanup)
            if isinstance(node, ast.Call)
        ]
        method_calls = {
            call.func.attr
            for call in calls
            if isinstance(call.func, ast.Attribute)
        }
        self.assertIn('cleanupPlugin', method_calls)
        self.assertNotIn('removeHooksByPluginId', method_calls)
        self.assertNotIn('removeMenuItemsByPluginId', method_calls)
        self.assertNotIn('on_plugin_unload', method_calls)
        assigned_attributes = {
            target.attr
            for node in ast.walk(cleanup)
            if isinstance(node, ast.Assign)
            for target in node.targets
            if isinstance(target, ast.Attribute)
        }
        self.assertTrue({
            'enabled', 'isenabled', 'initialized', '_initializing'
        }.isdisjoint(assigned_attributes))

        exact_call = next(
            call for call in calls
            if isinstance(call.func, ast.Attribute)
            and call.func.attr == 'cleanupPlugin'
        )
        self.assertEqual(len(exact_call.args), 2)
        self.assertIsInstance(exact_call.args[0], ast.Attribute)
        self.assertEqual(exact_call.args[0].attr, 'id')
        self.assertIsInstance(exact_call.args[1], ast.Name)
        self.assertEqual(exact_call.args[1].id, 'token')

if __name__ == '__main__':
    unittest.main()
