#!/usr/bin/env python3
import ast
import importlib
import json
import os
import sys
import tempfile
import threading
import time
import types
import unittest
from unittest import mock

PYTHON_SOURCES = os.path.abspath(
    os.path.join(os.path.dirname(__file__), '..', '..', 'main', 'python'))
BASE_PLUGIN_SOURCE = os.path.join(PYTHON_SOURCES, 'base_plugin.py')

class _EmptyIterator:
    def hasNext(self):
        return False

class _EmptyEntrySet:
    def iterator(self):
        return _EmptyIterator()

class _EmptyPrefs:
    def entrySet(self):
        return _EmptyEntrySet()

class PluginSettingsCoalescingDurabilityTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        sys.path.insert(0, PYTHON_SOURCES)

    def setUp(self):
        self.logs = []
        sys.modules['android_utils'] = types.SimpleNamespace(
            log=self.logs.append)
        import plugin_settings
        self.settings = importlib.reload(plugin_settings)
        self.temp = tempfile.TemporaryDirectory()
        self.settings.init(self.temp.name, _EmptyPrefs())

    def tearDown(self):
        try:
            self.settings.shutdown(timeout=2.0)
        finally:
            self.temp.cleanup()

    def _read_disk(self, directory=None):
        path = os.path.join(
            directory or self.temp.name, 'plugin_settings.json')
        with open(path, 'r', encoding='utf-8') as stream:
            return json.load(stream)

    def test_base_plugin_mutators_keep_legacy_none_return_contract(self):
        with open(BASE_PLUGIN_SOURCE, 'r', encoding='utf-8') as stream:
            tree = ast.parse(stream.read(), filename=BASE_PLUGIN_SOURCE)
        base_plugin = next(
            node for node in tree.body
            if isinstance(node, ast.ClassDef)
            and node.name == 'BasePlugin')
        for method_name in ('set_setting', 'import_settings'):
            method = next(
                node for node in base_plugin.body
                if isinstance(node, ast.FunctionDef)
                and node.name == method_name)
            self.assertFalse(any(
                isinstance(node, ast.Return)
                and isinstance(node.value, ast.Name)
                and node.value.id == 'completion'
                for node in ast.walk(method)))

    def test_serialization_and_all_durability_io_run_only_on_writer(self):
        calls = []
        original_dump = self.settings.json.dump
        original_fsync = self.settings.os.fsync
        original_replace = self.settings.os.replace

        def checked(name, function):
            def invoke(*args, **kwargs):
                calls.append((
                    name,
                    threading.current_thread().name,
                    self.settings._lock._is_owned()))
                return function(*args, **kwargs)
            return invoke

        with mock.patch.object(
                self.settings.json, 'dump',
                side_effect=checked('dump', original_dump)),\
                mock.patch.object(
                    self.settings.os, 'fsync',
                    side_effect=checked('fsync', original_fsync)),\
                mock.patch.object(
                    self.settings.os, 'replace',
                    side_effect=checked('replace', original_replace)):
            completion = self.settings.set_setting(
                'plugin', 'theme', 'dark')
            self.assertIsInstance(
                completion, self.settings.SettingsWriteCompletion)
            completion.result(2.0)

        self.assertGreaterEqual(
            [name for name, _thread, _owned in calls].count('fsync'), 2)
        self.assertTrue(calls)
        for _name, thread_name, lock_owned in calls:
            self.assertTrue(
                thread_name.startswith('plugin-settings-writer-'))
            self.assertFalse(lock_owned)

    def test_copy_on_write_snapshots_are_immutable_and_coalesced(self):
        entered = threading.Event()
        release = threading.Event()
        writes = []

        def controlled_write(snapshot):
            writes.append(snapshot)
            if len(writes) == 1:
                entered.set()
                self.assertTrue(release.wait(2.0))

        value = {'nested': ['original']}
        with mock.patch.object(
                self.settings, '_write_snapshot',
                side_effect=controlled_write):
            first = self.settings.set_setting(
                'plugin', 'payload', value)
            self.assertTrue(entered.wait(1.0))
            value['nested'].append('caller mutation')

            latest = first
            for index in range(50):
                latest = self.settings.set_setting(
                    'plugin', 'counter', index)
            release.set()
            latest.result(2.0)

        self.assertEqual(2, len(writes))
        self.assertLess(writes[0].generation, writes[1].generation)
        self.assertEqual(
            ['original'],
            writes[0].values['plugin']['payload']['nested'])
        self.assertEqual(49, writes[-1].values['plugin']['counter'])

    def test_set_all_and_clear_update_cache_before_writer_is_released(self):
        entered = threading.Event()
        release = threading.Event()
        original_write = self.settings._write_snapshot

        def blocked_first_write(snapshot):
            if not entered.is_set():
                entered.set()
                self.assertTrue(release.wait(2.0))
            return original_write(snapshot)

        with mock.patch.object(
                self.settings, '_write_snapshot',
                side_effect=blocked_first_write):
            self.settings.set_all_settings(
                'plugin', {'one': 1, 'two': 2})
            self.assertTrue(entered.wait(1.0))
            self.assertEqual(
                {'one': 1, 'two': 2},
                self.settings.get_all_settings('plugin'))

            cleared = self.settings.clear_settings('plugin')
            self.assertEqual(
                {}, self.settings.get_all_settings('plugin'))
            release.set()
            cleared.result(2.0)

        self.assertNotIn('plugin', self._read_disk())

    def test_writer_failure_is_logged_observable_and_explicitly_retryable(self):
        original_write = self.settings._write_snapshot
        attempts = 0

        def fail_once(snapshot):
            nonlocal attempts
            attempts += 1
            if attempts == 1:
                raise OSError('synthetic disk failure')
            return original_write(snapshot)

        with mock.patch.object(
                self.settings, '_write_snapshot',
                side_effect=fail_once):
            failed = self.settings.set_setting(
                'plugin', 'mode', 'durable')
            with self.assertRaisesRegex(
                    OSError, 'synthetic disk failure'):
                failed.result(2.0)

            retried = self.settings.flush_settings_async()
            self.assertTrue(retried.result(2.0))

        self.assertEqual('durable', self._read_disk()['plugin']['mode'])
        self.assertTrue(any(
            'synthetic disk failure' in message for message in self.logs))

    def test_host_transaction_timeout_is_bounded_and_does_not_leak_lock(self):
        entered = threading.Event()
        release = threading.Event()
        original_write = self.settings._write_snapshot

        def blocked_write(snapshot):
            entered.set()
            self.assertTrue(release.wait(2.0))
            return original_write(snapshot)

        with mock.patch.object(
                self.settings, '_write_snapshot',
                side_effect=blocked_write):
            completion = self.settings.set_setting(
                'plugin', 'before', True)
            self.assertTrue(entered.wait(1.0))
            started = time.monotonic()
            with self.assertRaises(TimeoutError):
                self.settings.begin_host_transaction(timeout=0.05)
            self.assertLess(time.monotonic() - started, 0.5)
            release.set()
            completion.result(2.0)

        after = self.settings.set_setting('plugin', 'after', True)
        after.result(2.0)

    def test_host_replace_reload_and_concurrent_set_remain_atomic(self):
        initial = self.settings.set_setting(
            'plugin', 'before_import', True)
        initial.result(2.0)
        self.assertTrue(self.settings.begin_host_transaction(timeout=2.0))

        setter_done = threading.Event()
        setter_result = []

        def concurrent_setter():
            setter_result.append(self.settings.set_setting(
                'plugin', 'after_import', True))
            setter_done.set()

        thread = threading.Thread(target=concurrent_setter)
        thread.start()
        try:
            self.assertFalse(setter_done.wait(0.05))
            path = os.path.join(
                self.temp.name, 'plugin_settings.json')
            replacement = path + '.host'
            with open(replacement, 'w', encoding='utf-8') as stream:
                json.dump({'plugin': {'imported': True}}, stream)
            os.replace(replacement, path)
            self.assertTrue(self.settings.reload_settings())
            self.assertEqual(
                {'imported': True},
                self.settings.get_all_settings('plugin'))
        finally:
            self.settings.end_host_transaction()

        thread.join(1.0)
        self.assertFalse(thread.is_alive())
        self.assertTrue(setter_done.is_set())
        setter_result[0].result(2.0)
        self.assertEqual(
            {'imported': True, 'after_import': True},
            self._read_disk()['plugin'])

    def test_shutdown_rejects_mutation_and_reinit_keeps_generation_monotonic(self):
        first = self.settings.set_setting('plugin', 'value', 'first')
        first.result(2.0)
        first_generation = first.generation
        old_thread = self.settings._writer_state.thread

        self.assertTrue(self.settings.shutdown(timeout=2.0))
        self.assertFalse(old_thread.is_alive())
        self.assertFalse(
            self.settings.set_setting('plugin', 'value', 'rejected'))

        second_dir = tempfile.TemporaryDirectory()
        try:
            self.settings.init(second_dir.name, _EmptyPrefs())
            second = self.settings.set_setting(
                'plugin', 'value', 'second')
            second.result(2.0)
            self.assertGreater(second.generation, first_generation)
            self.assertEqual(
                'second', self._read_disk(second_dir.name)['plugin']['value'])
        finally:
            self.settings.shutdown(timeout=2.0)
            second_dir.cleanup()

if __name__ == '__main__':
    unittest.main()
