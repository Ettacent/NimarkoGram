import importlib.util
import pathlib
import sys
import types
import unittest

REPO = pathlib.Path(__file__).resolve().parents[4]

class _RuntimeToken:
    def __init__(self, plugin_id, generation):
        self.plugin_id = plugin_id
        self.generation = generation

    def getPluginId(self):
        return self.plugin_id

class _FakeWatchdog:
    def __init__(self, controller):
        self.controller = controller
        self.executing = []

    def onPluginExecutionStarted(self, plugin_id):
        self.executing.append(
            (plugin_id, self.controller.captureCurrentPluginRuntime()))

    def onPluginExecutionFailed(self, _plugin_id, _failure):
        return

    def onPluginExecutionFinished(self, plugin_id):
        current_id, _token = self.executing.pop()
        if current_id != plugin_id:
            raise AssertionError('watchdog callbacks finished out of order')

class _FakeController:
    def __init__(self):
        self.capture_token = None
        self.active_token = None
        self.entered = []
        self.exited = []
        self.runtime_scopes = []
        self.watchdog = _FakeWatchdog(self)

    def captureCurrentPluginRuntime(self):
        if self.runtime_scopes:
            return self.runtime_scopes[-1]
        return self.capture_token

    def isPluginRuntimeCallbackAllowed(self, token):
        return token is self.active_token

    def enterPluginRuntime(self, token):
        if token is not self.active_token:
            return False
        self.entered.append(token)
        self.runtime_scopes.append(token)
        return True

    def exitPluginRuntime(self, token):
        if not self.runtime_scopes or self.runtime_scopes[-1] is not token:
            raise AssertionError('runtime scopes exited out of order')
        self.runtime_scopes.pop()
        self.exited.append(token)

    def getWatchdog(self):
        return self.watchdog

class _FakeReturnType:
    def __init__(self, name):
        self._name = name

    def getName(self):
        return self._name

class _FakeMethod:
    def __init__(self, name, return_type):
        self._name = name
        self._return_type = _FakeReturnType(return_type)

    def getName(self):
        return self._name

    def getReturnType(self):
        return self._return_type

class _FakeJavaClass:
    def __init__(self, methods):
        self._methods = methods

    def getMethods(self):
        return self._methods

    def isInterface(self):
        return True

class _FakeInterface:
    def __init__(self, *methods):
        self.class_ = _FakeJavaClass([
            _FakeMethod(name, return_type)
            for name, return_type in methods
        ])

    def getClass(self):
        return self.class_

class _FakeJavaOwnedProxy:
    """CPython oracle for the Java InvocationHandler contract."""

    def __init__(self, target, interfaces, owner, controller):
        self._target = target
        self._nimarko_runtime_token = owner
        self._controller = controller
        self._methods = {}
        for interface in interfaces:
            for method in interface.class_.getMethods():
                self._methods[method.getName()] = method

    @staticmethod
    def _default(method):
        name = method.getReturnType().getName()
        if name == 'boolean':
            return False
        if name in (
                'byte', 'short', 'int', 'long',
                'float', 'double', 'char'):
            return 0
        return None

    def getClass(self):
        return _FakeJavaClass(list(self._methods.values()))

    def __getattr__(self, name):
        method = self._methods.get(name)
        if method is None:
            raise AttributeError(name)

        def invoke(*args):
            token = self._nimarko_runtime_token
            default = self._default(method)
            if not self._controller.isPluginRuntimeCallbackAllowed(token):
                return default
            if not self._controller.enterPluginRuntime(token):
                return default
            plugin_id = token.getPluginId()
            watchdog = self._controller.getWatchdog()
            watchdog.onPluginExecutionStarted(plugin_id)
            try:
                return getattr(self._target, name)(*args)
            except BaseException as failure:
                watchdog.onPluginExecutionFailed(plugin_id, failure)
                return default
            finally:
                watchdog.onPluginExecutionFinished(plugin_id)
                self._controller.exitPluginRuntime(token)

        invoke._nimarko_runtime_token = self._nimarko_runtime_token
        return invoke

class ProxyRuntimeIsolationTest(unittest.TestCase):
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
            'java',
            'plugin_runtime',
            'extera_utils',
            'extera_utils.classes',
        )
        cls.previous_modules = {
            name: sys.modules.get(name) for name in module_names
        }

        for name in module_names[:3]:
            module = types.ModuleType(name)
            module.__path__ = []
            sys.modules[name] = module
        plugins_module = types.ModuleType(module_names[3])
        plugins_module.PluginsController = PluginsController
        sys.modules[module_names[3]] = plugins_module

        java_module = types.ModuleType('java')
        java_module.jclass = lambda name: name
        sys.modules['java'] = java_module

        runtime_source = (
            REPO / 'TMessagesProj/src/main/python/plugin_runtime.py')
        runtime_spec = importlib.util.spec_from_file_location(
            'plugin_runtime', runtime_source)
        cls.runtime = importlib.util.module_from_spec(runtime_spec)
        sys.modules['plugin_runtime'] = cls.runtime
        runtime_spec.loader.exec_module(cls.runtime)
        cls.runtime.make_interface_proxy = (
            lambda target, interfaces, owner:
            _FakeJavaOwnedProxy(
                target, interfaces, owner, cls.controller)
        )

        extera_package = types.ModuleType('extera_utils')
        extera_package.__path__ = []
        sys.modules['extera_utils'] = extera_package
        classes_source = (
            REPO / 'TMessagesProj/src/main/python/extera_utils/classes.py')
        classes_spec = importlib.util.spec_from_file_location(
            'extera_utils.classes', classes_source)
        cls.classes = importlib.util.module_from_spec(classes_spec)
        sys.modules['extera_utils.classes'] = cls.classes
        classes_spec.loader.exec_module(cls.classes)

        cls.interface = _FakeInterface(
            ('onEvent', 'java.lang.String'),
            ('shouldHandle', 'boolean'),
        )

    @classmethod
    def tearDownClass(cls):
        for name, previous in cls.previous_modules.items():
            if previous is None:
                sys.modules.pop(name, None)
            else:
                sys.modules[name] = previous

    def setUp(self):
        self.controller.capture_token = None
        self.controller.active_token = None
        self.controller.entered.clear()
        self.controller.exited.clear()
        self.controller.runtime_scopes.clear()
        self.controller.watchdog.executing.clear()

    def _make_listener(
            self, token, label, constructor_calls,
            callback_calls, service_calls):
        self.controller.capture_token = token

        @self.classes.Base.extends(self.interface)
        class Listener:
            def __init__(self, value):
                constructor_calls.append((label, value))
                self.value = value

            def onEvent(self, value):
                callback_calls.append((label, 'event', value))
                return label + ':' + value

            def shouldHandle(self):
                callback_calls.append((label, 'boolean'))
                return True

            def helper(self):
                service_calls.append((label, 'helper'))
                return label + '-helper'

            def on_pre_init(self):
                service_calls.append((label, 'pre-init'))

        return Listener

    def test_stale_generation_is_dropped_and_new_generation_runs(self):
        old_token = _RuntimeToken('same-plugin', 1)
        new_token = _RuntimeToken('same-plugin', 2)
        constructor_calls = []
        callback_calls = []
        service_calls = []

        OldListener = self._make_listener(
            old_token, 'old', constructor_calls,
            callback_calls, service_calls)
        self.controller.active_token = old_token
        old_listener = OldListener('first')

        NewListener = self._make_listener(
            new_token, 'new', constructor_calls,
            callback_calls, service_calls)
        self.controller.active_token = new_token
        new_listener = NewListener('second')

        self.assertIsNone(old_listener.onEvent('stale'))
        self.assertFalse(old_listener.shouldHandle())
        self.assertEqual(callback_calls, [])
        self.assertEqual(self.controller.entered, [])

        self.assertEqual(new_listener.onEvent('current'), 'new:current')
        self.assertTrue(new_listener.shouldHandle())
        self.assertEqual(
            callback_calls,
            [('new', 'event', 'current'), ('new', 'boolean')],
        )
        self.assertEqual(
            self.controller.entered, [new_token, new_token])
        self.assertEqual(
            self.controller.exited, [new_token, new_token])

        self.assertIs(
            self.runtime.capture_callback_owner(old_listener.onEvent),
            old_token,
        )
        self.assertIs(
            self.runtime.capture_callback_owner(new_listener.onEvent),
            new_token,
        )

    def test_constructor_runs_but_java_proxy_exposes_interfaces_only(self):
        old_token = _RuntimeToken('same-plugin', 1)
        new_token = _RuntimeToken('same-plugin', 2)
        constructor_calls = []
        callback_calls = []
        service_calls = []
        OldListener = self._make_listener(
            old_token, 'old', constructor_calls,
            callback_calls, service_calls)

        self.controller.active_token = new_token
        listener = OldListener.new_instance('after-reload')
        self.assertEqual(
            constructor_calls, [('old', 'after-reload')])
        with self.assertRaises(AttributeError):
            listener.helper()
        with self.assertRaises(AttributeError):
            listener.on_pre_init()
        with self.assertRaises(AttributeError):
            listener.bind()
        with self.assertRaises(AttributeError):
            getattr(listener, 'this')
        self.assertEqual(service_calls, [])
        self.assertEqual(self.controller.entered, [])

        implementation = next(
            base for base in OldListener.__mro__
            if '__init__' in base.__dict__)
        self.assertFalse(getattr(
            implementation.__dict__['__init__'],
            '_nimarko_proxy_runtime_guarded', False))
        self.assertFalse(getattr(
            implementation.__dict__['helper'],
            '_nimarko_proxy_runtime_guarded', False))
        self.assertFalse(getattr(
            implementation.__dict__['onEvent'],
            '_nimarko_proxy_runtime_guarded', False))

    def test_ownerless_proxy_fails_closed_at_construction(self):
        calls = []
        self.controller.capture_token = None
        self.controller.active_token = None

        @self.classes.Base.extends(self.interface)
        class HostListener:
            def onEvent(self, value):
                calls.append(value)
                return value

            def shouldHandle(self):
                calls.append('boolean')
                return True

        with self.assertRaisesRegex(
                RuntimeError, 'outside a plugin runtime'):
            HostListener()
        self.assertEqual(calls, [])

    def test_custom_metaclass_contract_is_composed(self):
        token = _RuntimeToken('meta-plugin', 1)
        self.controller.capture_token = token
        self.controller.active_token = token
        events = []

        class CustomMeta(type):
            def __new__(meta, name, bases, namespace):
                namespace['created_by_custom_meta'] = True
                events.append(name)
                return super().__new__(meta, name, bases, namespace)

        @self.classes.Base.extends(self.interface)
        class Listener(metaclass=CustomMeta):
            def onEvent(self, value):
                return value

            def shouldHandle(self):
                return True

        self.assertTrue(Listener.created_by_custom_meta)
        self.assertGreaterEqual(events.count('Listener'), 2)
        proxy = Listener()
        self.assertEqual(proxy.onEvent('ok'), 'ok')

    def test_java_bridge_is_revocable_and_exact_runtime_scoped(self):
        java = (
            REPO / 'TMessagesProj/src/main/java/'
            'app/nimarkogram/messenger/plugins/bridge/'
            'PythonInterfaceProxy.java'
        ).read_text(encoding='utf-8')
        classes = (
            REPO / 'TMessagesProj/src/main/python/'
            'extera_utils/classes.py'
        ).read_text(encoding='utf-8')
        runtime = (
            REPO / 'TMessagesProj/src/main/python/'
            'plugin_runtime.py'
        ).read_text(encoding='utf-8')

        self.assertIn('implements\n        InvocationHandler,', java)
        self.assertIn(
            'PluginUiRegistry.RuntimeOwnedUi', java)
        self.assertIn(
            'AtomicReference<PyObject> target', java)
        self.assertIn(
            'getPluginRuntimeTaskDecision(runtimeToken)', java)
        self.assertIn(
            'controller.enterPluginRuntime(runtimeToken)', java)
        self.assertIn(
            'controller.exitPluginRuntime(runtimeToken)', java)
        self.assertIn('target.set(null)', java)
        self.assertNotIn('dynamic_proxy', classes)
        self.assertIn('make_interface_proxy(', classes)
        self.assertIn(
            'PythonInterfaceProxy.create(target, owner, classes)',
            runtime)

if __name__ == '__main__':
    unittest.main()
