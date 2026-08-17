import importlib
import json
import os
import sys
import tempfile
import types
import unittest
from unittest import mock

PYTHON_SOURCES = os.path.abspath(
    os.path.join(os.path.dirname(__file__), '..', '..', 'main', 'python'))

class _Entry:
    def __init__(self, key, value):
        self._key = key
        self._value = value

    def getKey(self):
        return self._key

    def getValue(self):
        return self._value

class _Iterator:
    def __init__(self, values):
        self._values = iter(values)
        self._next = None

    def hasNext(self):
        try:
            self._next = next(self._values)
            return True
        except StopIteration:
            return False

    def next(self):
        value = self._next
        self._next = None
        return value

class _EntrySet:
    def __init__(self, values):
        self._values = values

    def iterator(self):
        return _Iterator(self._values)

class _Prefs:
    def __init__(self, values=None):
        self._values = [_Entry(key, value) for key, value in (values or {}).items()]

    def entrySet(self):
        return _EntrySet(self._values)

class PluginSettingsMigrationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        sys.path.insert(0, PYTHON_SOURCES)
        sys.modules['android_utils'] = types.SimpleNamespace(log=lambda _message: None)

    def setUp(self):
        import plugin_settings
        self.settings = importlib.reload(plugin_settings)
        self.temp = tempfile.TemporaryDirectory()

    def tearDown(self):
        self.temp.cleanup()

    def _plugin(self, plugin_id, key):
        with open(os.path.join(self.temp.name, plugin_id + '.py'), 'w', encoding='utf-8') as stream:
            stream.write(f"SETTINGS = [Switch(key={key!r}, text='x')]\n")

    def _write_settings(self, value):
        with open(os.path.join(self.temp.name, 'plugin_settings.json'), 'w', encoding='utf-8') as stream:
            json.dump(value, stream)

    def test_repairs_corrupted_short_plugin_bucket_using_schema_evidence(self):
        self._plugin('foo', 'unrelated')
        self._plugin('foo_bar', 'theme')
        self._write_settings({'foo': {'bar_theme': 'dark'}})

        self.settings.init(self.temp.name, _Prefs())

        self.assertEqual('dark', self.settings.get_setting('foo_bar', 'theme', None))
        self.assertIsNone(self.settings.get_setting('foo', 'bar_theme', None))

    def test_quarantines_ambiguous_corrupted_json_instead_of_guessing(self):
        self._plugin('foo', 'bar_theme')
        self._plugin('foo_bar', 'theme')
        self._write_settings({'foo': {'bar_theme': 'dark'}})

        self.settings.init(self.temp.name, _Prefs())

        quarantined = self.settings.get_all_settings('__legacy_quarantine__')
        self.assertEqual('dark', quarantined['json:foo/bar_theme']['value'])
        self.assertNotIn('bar_theme', self.settings.get_all_settings('foo'))
        self.assertNotIn('theme', self.settings.get_all_settings('foo_bar'))

    def test_quarantines_and_deletes_ambiguous_shared_preference(self):
        self._plugin('foo', 'bar_theme')
        self._plugin('foo_bar', 'theme')

        deleted = self.settings.init(
            self.temp.name, _Prefs({'plugin_setting_foo_bar_theme': 'dark'}))

        self.assertEqual(['plugin_setting_foo_bar_theme'], deleted)
        quarantined = self.settings.get_all_settings('__legacy_quarantine__')
        self.assertEqual('dark', quarantined['plugin_setting_foo_bar_theme']['value'])

    def test_json_repair_quarantines_differing_destination_without_overwrite(self):
        self._plugin('foo', 'unrelated')
        self._plugin('foo_bar', 'theme')
        self._write_settings({
            'foo': {'bar_theme': 'source'},
            'foo_bar': {'theme': 'destination'},
        })

        self.settings.init(self.temp.name, _Prefs())

        self.assertEqual(
            'destination', self.settings.get_setting('foo_bar', 'theme', None))
        self.assertNotIn('bar_theme', self.settings.get_all_settings('foo'))
        conflict = self.settings.get_all_settings('__legacy_quarantine__')[
            'json:foo/bar_theme']
        self.assertEqual('source', conflict['value'])
        self.assertEqual('destination', conflict['destination_value'])
        self.assertEqual('json_setting', conflict['source_kind'])

    def test_legacy_pref_conflict_is_durable_before_source_delete_is_returned(self):
        self._plugin('foo_bar', 'theme')
        self._write_settings({'foo_bar': {'theme': 'destination'}})

        deleted = self.settings.init(
            self.temp.name,
            _Prefs({'plugin_setting_foo_bar_theme': 'source'}))

        self.assertEqual(['plugin_setting_foo_bar_theme'], deleted)
        self.assertEqual(
            'destination', self.settings.get_setting('foo_bar', 'theme', None))
        conflict = self.settings.get_all_settings('__legacy_quarantine__')[
            'plugin_setting_foo_bar_theme']
        self.assertEqual('source', conflict['value'])
        self.assertEqual('destination', conflict['destination_value'])
        with open(os.path.join(self.temp.name, 'plugin_settings.json'),
                  'r', encoding='utf-8') as stream:
            persisted = json.load(stream)
        self.assertEqual(conflict, persisted['__legacy_quarantine__'][
            'plugin_setting_foo_bar_theme'])

    def test_json_source_is_not_popped_when_acceptance_cannot_be_saved(self):
        self._plugin('foo', 'unrelated')
        self._plugin('foo_bar', 'theme')
        self._write_settings({'foo': {'bar_theme': 'source'}})

        original_save = self.settings._save_settings_to_file
        calls = 0

        def fail_first_strict_save(strict=False):
            nonlocal calls
            calls += 1
            if strict and calls == 1:
                raise OSError('simulated durable write failure')
            return original_save(strict=strict)

        with mock.patch.object(
                self.settings, '_save_settings_to_file',
                side_effect=fail_first_strict_save):
            with self.assertRaises(OSError):
                self.settings.init(self.temp.name, _Prefs())

        self.assertEqual(
            'source', self.settings.get_setting('foo', 'bar_theme', None))
        self.assertIsNone(self.settings.get_setting('foo_bar', 'theme', None))

    def test_reload_missing_file_clears_cached_settings(self):
        self._write_settings({'foo': {'theme': 'dark'}})
        self.settings.init(self.temp.name, _Prefs())
        os.unlink(os.path.join(self.temp.name, 'plugin_settings.json'))

        self.assertTrue(self.settings.reload_settings())
        self.assertIsNone(self.settings.get_setting('foo', 'theme', None))

    def test_normal_start_does_not_parse_every_plugin_schema(self):
        self._plugin('foo', 'theme')
        self._write_settings({'foo': {'theme': 'dark'}})

        with mock.patch.object(
                self.settings, '_setting_schemas',
                side_effect=AssertionError('schema scan should stay lazy')):
            self.settings.init(self.temp.name, _Prefs())

        self.assertEqual(
            'dark', self.settings.get_setting('foo', 'theme', None))

    def test_reload_valid_file_replaces_cached_settings(self):
        self._write_settings({'foo': {'theme': 'dark'}})
        self.settings.init(self.temp.name, _Prefs())
        self._write_settings({'foo': {'theme': 'light'}})

        self.assertTrue(self.settings.reload_settings())
        self.assertEqual('light', self.settings.get_setting('foo', 'theme', None))

    def test_reload_parse_failure_propagates_and_preserves_cached_settings(self):
        self._write_settings({'foo': {'theme': 'dark'}})
        self.settings.init(self.temp.name, _Prefs())
        with open(os.path.join(self.temp.name, 'plugin_settings.json'), 'w', encoding='utf-8') as stream:
            stream.write('{invalid json')

        with self.assertRaises(json.JSONDecodeError):
            self.settings.reload_settings()
        self.assertEqual('dark', self.settings.get_setting('foo', 'theme', None))

    def test_reload_rejects_non_object_root_and_preserves_cached_settings(self):
        self._write_settings({'foo': {'theme': 'dark'}})
        self.settings.init(self.temp.name, _Prefs())
        self._write_settings(['not', 'an', 'object'])

        with self.assertRaises(ValueError):
            self.settings.reload_settings()
        self.assertEqual('dark', self.settings.get_setting('foo', 'theme', None))

    def test_reload_rejects_non_object_plugin_bucket_and_preserves_cache(self):
        self._write_settings({'foo': {'theme': 'dark'}})
        self.settings.init(self.temp.name, _Prefs())
        self._write_settings({'foo': ['not', 'a', 'bucket']})

        with self.assertRaises(ValueError):
            self.settings.reload_settings()
        self.assertEqual('dark', self.settings.get_setting('foo', 'theme', None))

    def test_reload_open_failure_propagates_and_preserves_cached_settings(self):
        self._write_settings({'foo': {'theme': 'dark'}})
        self.settings.init(self.temp.name, _Prefs())
        path = os.path.join(self.temp.name, 'plugin_settings.json')
        os.unlink(path)
        os.mkdir(path)

        with self.assertRaises(IsADirectoryError):
            self.settings.reload_settings()
        self.assertEqual('dark', self.settings.get_setting('foo', 'theme', None))

    def test_nimarkoprivacy_settings_survive_save_and_reload_with_types(self):
        self.settings.init(self.temp.name, _Prefs())
        expected = {
            'stealth_toggle': True,
            'block_read': True,
            'mute_send': 2,
        }
        for key, value in expected.items():
            self.settings.set_setting('nimarkoprivacy', key, value)

        self.settings._settings_cache = {}
        self.assertTrue(self.settings.reload_settings())

        self.assertEqual(
            expected, self.settings.get_all_settings('nimarkoprivacy'))
        self.assertIs(
            True,
            self.settings.get_setting('nimarkoprivacy', 'stealth_toggle', False))
        self.assertEqual(
            2, self.settings.get_setting('nimarkoprivacy', 'mute_send', 0))

    def test_failed_setting_write_rolls_process_cache_back(self):
        self.settings.init(self.temp.name, _Prefs())
        self.assertTrue(
            self.settings.set_setting('nimarkoprivacy', 'mode', 'old'))
        with mock.patch.object(
                self.settings, '_save_settings_to_file',
                side_effect=OSError('disk full')):
            self.assertFalse(
                self.settings.set_setting(
                    'nimarkoprivacy', 'mode', 'new'))
        self.assertEqual(
            'old',
            self.settings.get_setting(
                'nimarkoprivacy', 'mode', None))

    def test_revoked_runtime_cannot_write_replacement_settings_bucket(self):
        self.settings.init(self.temp.name, _Prefs())
        self.assertTrue(
            self.settings.set_setting(
                'nimarkoprivacy', 'mode', 'current'))

        class _Token:
            def getPluginId(self):
                return 'nimarkoprivacy'

        runtime = types.SimpleNamespace(
            capture_callback_owner=lambda: _Token(),
            is_callback_allowed=lambda _token: False,
        )
        with mock.patch.dict(sys.modules, {'plugin_runtime': runtime}):
            self.assertFalse(
                self.settings.set_setting(
                    'nimarkoprivacy', 'mode', 'stale'))
        self.assertEqual(
            'current',
            self.settings.get_setting(
                'nimarkoprivacy', 'mode', None))

if __name__ == '__main__':
    unittest.main()
