import atexit
import json
import os
import ast
import copy
import tempfile
import threading
import time
import traceback
from typing import Any, NamedTuple
from android_utils import log

_previous_reload_shutdown = globals().get('_shutdown_for_reload')
if callable(_previous_reload_shutdown):
    try:
        _previous_reload_shutdown()
    except Exception:
        pass

_settings_cache = {}
_settings_file_path = None
_lock = threading.RLock()
_lifecycle_lock = threading.RLock()
_transaction_local = threading.local()
_generation = globals().get('_generation', 0)
_writer_state = None
_WRITER_WAIT_SECONDS = 5.0
_ATEXIT_WAIT_SECONDS = 1.0
_QUARANTINE_ID = '__legacy_quarantine__'

class _Snapshot(NamedTuple):
    generation: int
    path: str
    values: dict

class SettingsWriteCompletion:
    """A durable-generation barrier returned without waiting for disk I/O."""

    def __init__(self, state, generation):
        self._state = state
        self.generation = generation

    def done(self):
        with self._state.condition:
            return self._state._completion_outcome_locked(
                self.generation) is not None

    def result(self, timeout=None):
        deadline = _deadline(timeout)
        with self._state.condition:
            while True:
                outcome = self._state._completion_outcome_locked(
                    self.generation)
                if outcome is True:
                    return True
                if isinstance(outcome, BaseException):
                    raise outcome
                remaining = _remaining(deadline)
                if remaining is not None and remaining <= 0:
                    raise TimeoutError(
                        'Timed out waiting for plugin settings generation '
                        f'{self.generation}')
                self._state.condition.wait(remaining)

    wait = result

    def __bool__(self):
        
        return True

class _WriterState:
    def __init__(self, durable_generation):
        self.condition = threading.Condition()
        self.durable_generation = durable_generation
        self.pending_snapshot = None
        self.active_generation = None
        self.failure_generation = -1
        self.failure = None
        self.accepting = True
        self.stop_requested = False
        self.thread = threading.Thread(
            target=_writer_main,
            args=(self,),
            name=f'plugin-settings-writer-{durable_generation}',
            daemon=True)

    def start(self):
        self.thread.start()

    def request(self, snapshot, force=False):
        generation = snapshot.generation
        with self.condition:
            if not self.accepting and not force:
                raise RuntimeError('Plugin settings writer is shutting down')
            if generation > self.durable_generation:
                if (self.pending_snapshot is None
                        or generation > self.pending_snapshot.generation):
                    self.pending_snapshot = snapshot
                self.condition.notify()
            return SettingsWriteCompletion(self, generation)

    def mark_durable(self, generation):
        with self.condition:
            if generation > self.durable_generation:
                self.durable_generation = generation
            if (self.pending_snapshot is not None
                    and self.pending_snapshot.generation <= generation):
                self.pending_snapshot = None
            if self.failure_generation <= generation:
                self.failure_generation = -1
                self.failure = None
            self.condition.notify_all()

    def _completion_outcome_locked(self, generation):
        if self.durable_generation >= generation:
            return True
        retry_pending = (
            (self.pending_snapshot is not None
             and self.pending_snapshot.generation >= generation)
            or (self.active_generation is not None
                and self.active_generation >= generation)
        )
        if self.failure_generation >= generation and not retry_pending:
            return self.failure or RuntimeError(
                f'Plugin settings generation {generation} was not persisted')
        if self.stop_requested and not self.thread.is_alive():
            return RuntimeError(
                f'Plugin settings writer stopped before generation '
                f'{generation} became durable')
        return None

def _deadline(timeout):
    if timeout is None:
        return None
    timeout = float(timeout)
    if timeout < 0:
        raise ValueError('timeout must be non-negative')
    return time.monotonic() + timeout

def _remaining(deadline):
    if deadline is None:
        return None
    return max(0.0, deadline - time.monotonic())

def init(plugins_dir_path: str, all_shared_prefs: dict,
         timeout=_WRITER_WAIT_SECONDS):
    global _settings_cache
    global _settings_file_path
    global _generation

    target_path = os.path.join(plugins_dir_path, 'plugin_settings.json')
    deadline = _deadline(timeout)
    with _lifecycle_lock:
        _shutdown_writer(
            timeout=_remaining(deadline), raise_on_error=True)

        existed = os.path.exists(target_path)
        loaded_settings = (
            _read_settings_file(target_path, validate_buckets=False)
            if existed else {})
        keys_to_delete = []

        known_ids = _known_plugin_ids(plugins_dir_path)
        legacy_entries = []
        prefix = 'plugin_setting_'
        iterator = all_shared_prefs.entrySet().iterator()
        while iterator.hasNext():
            entry = iterator.next()
            key = str(entry.getKey())
            if key.startswith(prefix):
                legacy_entries.append((key, entry.getValue()))

        schemas = (
            _setting_schemas(plugins_dir_path, known_ids)
            if _needs_setting_schemas(
                legacy_entries, known_ids, loaded_settings)
            else {})

        previous_cache = _settings_cache
        previous_path = _settings_file_path
        before_migration = copy.deepcopy(loaded_settings)
        try:
            with _lock:
                _settings_cache = loaded_settings
                _settings_file_path = target_path
                changed = False
                for key, value in legacy_entries:
                    split = _split_legacy_setting_key(
                        key[len(prefix):], known_ids, schemas)
                    if split is None:
                        _quarantine_legacy_value(
                            key, value, 'ambiguous_shared_preference',
                            source_kind='shared_preference')
                    else:
                        plugin_id, setting_key = split
                        _accept_migrated_value(
                            plugin_id, setting_key, value, key,
                            source_kind='shared_preference',
                            conflict_reason='shared_preference_destination_conflict')
                    keys_to_delete.append(key)
                    changed = True

                if _repair_underscore_ids(known_ids, schemas):
                    changed = True

                _generation += 1
                initialized_generation = _generation
                needs_write = changed or not existed
                _start_writer_locked(
                    initialized_generation if not needs_write
                    else initialized_generation - 1)
                completion = (
                    _save_settings_to_file(strict=True)
                    if needs_write else None)

            if completion is not None:
                completion.result(_remaining(deadline))
        except Exception:
            _stop_writer_without_flush(_remaining(deadline))
            with _lock:
                if previous_path is None:
                    _settings_cache = before_migration
                    _settings_file_path = target_path
                else:
                    _settings_cache = previous_cache
                    _settings_file_path = previous_path
                _generation += 1
                if _settings_file_path is not None:
                    _start_writer_locked(_generation)
            raise

        if changed or not existed:
            log(f'Migrated/repaired settings for {len(_settings_cache)} plugins.')
        return keys_to_delete

def _known_plugin_ids(plugins_dir_path):
    try:
        return sorted(
            (name[:-3] for name in os.listdir(plugins_dir_path)
             if name.endswith('.py') and len(name) > 3),
            key=len, reverse=True)
    except Exception:
        return []

def _setting_schemas(plugins_dir_path, known_ids):
    """Extract literal setting keys without importing plugin code.

    The legacy delimiter is lossy, so source-declared Switch/Selector/Input/
    EditText keys are the explicit evidence used to resolve foo vs foo_bar.
    Dynamic keys are intentionally treated as ambiguous and quarantined.
    """
    schemas = {plugin_id: set() for plugin_id in known_ids}
    setting_types = {'Switch', 'Selector', 'Input', 'EditText'}
    for plugin_id in known_ids:
        try:
            path = os.path.join(plugins_dir_path, plugin_id + '.py')
            with open(path, 'r', encoding='utf-8') as source:
                tree = ast.parse(source.read(), filename=path)
            for node in ast.walk(tree):
                if not isinstance(node, ast.Call):
                    continue
                name = node.func.attr if isinstance(node.func, ast.Attribute) else (
                    node.func.id if isinstance(node.func, ast.Name) else None)
                if name not in setting_types:
                    continue
                literal = None
                for keyword in node.keywords:
                    if keyword.arg == 'key' and isinstance(keyword.value, ast.Constant):
                        literal = keyword.value.value
                        break
                if literal is None and node.args and isinstance(node.args[0], ast.Constant):
                    literal = node.args[0].value
                if isinstance(literal, str) and literal:
                    schemas[plugin_id].add(literal)
        except Exception:
            
            pass
    return schemas

def _candidate_splits(payload, known_ids):
    candidates = []
    for plugin_id in known_ids:
        marker = plugin_id + '_'
        if payload.startswith(marker) and len(payload) > len(marker):
            candidates.append((plugin_id, payload[len(marker):]))
    return candidates

def _choose_candidate(candidates, schemas):
    if len(candidates) == 1:
        return candidates[0]
    evidenced = [candidate for candidate in candidates
                 if candidate[1] in schemas.get(candidate[0], set())]
    if len(evidenced) == 1:
        return evidenced[0]
    return None

def _needs_setting_schemas(legacy_entries, known_ids, settings_cache):
    for key, _value in legacy_entries:
        payload = key[len('plugin_setting_'):]
        if len(_candidate_splits(payload, known_ids)) > 1:
            return True
    known = set(known_ids)
    for old_id, old_values in settings_cache.items():
        if old_id == _QUARANTINE_ID or not isinstance(old_values, dict):
            continue
        for old_key in old_values:
            candidates = _candidate_splits(
                old_id + '_' + old_key, known_ids)
            if old_id in known and (old_id, old_key) not in candidates:
                candidates.append((old_id, old_key))
            if len(candidates) > 1:
                return True
    return False

def _split_legacy_setting_key(payload, known_ids, schemas=None):
    candidates = _candidate_splits(payload, known_ids)
    return _choose_candidate(candidates, schemas or {})

def _quarantine_legacy_value(source, value, reason, **metadata):
    bucket = _settings_cache.setdefault(_QUARANTINE_ID, {})
    key = str(source)
    record = {'reason': reason, 'value': value, 'source': str(source)}
    record.update(metadata)
    suffix = 1
    while key in bucket and bucket[key] != record:
        suffix += 1
        key = f'{source}#{suffix}'
    bucket[key] = record
    return key

def _accept_migrated_value(plugin_id, setting_key, value, source,
                           source_kind, conflict_reason):
    """Accept a migrated value or preserve a differing destination conflict."""
    destination = f'{plugin_id}/{setting_key}'
    bucket = _settings_cache.get(plugin_id)
    if bucket is None:
        bucket = {}
        _settings_cache[plugin_id] = bucket
    if not isinstance(bucket, dict):
        _quarantine_legacy_value(
            source, value, conflict_reason, source_kind=source_kind,
            destination=destination, destination_value=bucket,
            destination_reason='invalid_plugin_bucket')
        return 'quarantined'
    if setting_key not in bucket:
        bucket[setting_key] = value
        return 'accepted'
    if bucket[setting_key] == value:
        return 'duplicate'
    _quarantine_legacy_value(
        source, value, conflict_reason, source_kind=source_kind,
        destination=destination, destination_value=bucket[setting_key])
    return 'quarantined'

def _repair_underscore_ids(known_ids, schemas=None):
    global _settings_cache
    changed = False
    known = set(known_ids)
    schemas = schemas or {}
    for old_id in list(_settings_cache.keys()):
        if old_id == _QUARANTINE_ID:
            continue
        old_values = _settings_cache.get(old_id, {})
        if not isinstance(old_values, dict):
            source = f'json:{old_id}'
            _quarantine_legacy_value(
                source, old_values, 'invalid_plugin_bucket',
                source_kind='json_plugin_bucket')
            _settings_cache.pop(old_id, None)
            changed = True
            continue
        for old_key in list(old_values.keys()):
            combined = old_id + '_' + old_key
            candidates = _candidate_splits(combined, known_ids)
            
            if old_id in known and (old_id, old_key) not in candidates:
                candidates.append((old_id, old_key))
            longer = [candidate for candidate in candidates if candidate[0] != old_id]
            if not longer:
                continue
            selected = _choose_candidate(candidates, schemas)
            if selected == (old_id, old_key):
                continue
            value = old_values[old_key]
            source = f'json:{old_id}/{old_key}'
            if selected is None:
                _quarantine_legacy_value(
                    source, value, 'ambiguous_underscore_owner',
                    source_kind='json_setting',
                    source_plugin_id=old_id, source_setting_key=old_key)
            else:
                plugin_id, new_key = selected
                _accept_migrated_value(
                    plugin_id, new_key, value, source,
                    source_kind='json_setting',
                    conflict_reason='underscore_destination_conflict')
            old_values = _settings_cache.get(old_id, {})
            old_values.pop(old_key, None)
            if not old_values:
                _settings_cache.pop(old_id, None)
            changed = True
    return changed

def _invalidate_java_cache(plugin_id, key=None):
    try:
        from app.nimarkogram.messenger.plugins import PluginsController
        engine = PluginsController.engines.get('python')
        if engine is not None:
            engine.invalidatePluginSettingCache(plugin_id, key)
    except Exception:
        pass

def _read_settings_file(path, validate_buckets):
    try:
        with open(path, 'r', encoding='utf-8') as stream:
            loaded_settings = json.load(stream)
    except FileNotFoundError:
        return {}
    except Exception as error:
        log(
            f'Error loading plugin settings from JSON: {error}\n'
            f'{traceback.format_exc()}')
        raise
    if not isinstance(loaded_settings, dict):
        raise ValueError('Plugin settings root must be an object')
    if validate_buckets:
        for plugin_id, bucket in loaded_settings.items():
            if not isinstance(plugin_id, str) or not isinstance(bucket, dict):
                raise ValueError(
                    f'Plugin settings bucket {plugin_id!r} must be an object')
    return loaded_settings

def _snapshot_locked():
    """Return the current copy-on-write cache root as an immutable snapshot."""
    if not _settings_file_path:
        raise RuntimeError('Plugin settings path is not initialized')
    return _Snapshot(_generation, _settings_file_path, _settings_cache)

def _write_snapshot(snapshot):
    """Crash-safe replace. This function is called only by the writer thread."""
    settings_dir = os.path.dirname(snapshot.path) or '.'
    temp_path = None
    try:
        with tempfile.NamedTemporaryFile(
                mode='w', encoding='utf-8', dir=settings_dir,
                prefix='.plugin_settings.', suffix='.tmp',
                delete=False) as stream:
            temp_path = stream.name
            json.dump(snapshot.values, stream, indent=2)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temp_path, snapshot.path)
        temp_path = None
        directory_flags = os.O_RDONLY | getattr(os, 'O_DIRECTORY', 0)
        directory_fd = os.open(settings_dir, directory_flags)
        try:
            os.fsync(directory_fd)
        finally:
            os.close(directory_fd)
    finally:
        if temp_path:
            try:
                os.unlink(temp_path)
            except OSError:
                pass

def _writer_main(state):
    while True:
        with state.condition:
            while (state.pending_snapshot is None
                   and not state.stop_requested):
                state.condition.wait()
            if state.pending_snapshot is None and state.stop_requested:
                state.condition.notify_all()
                return
            snapshot = state.pending_snapshot
            state.pending_snapshot = None
            state.active_generation = snapshot.generation

        attempted_generation = snapshot.generation
        try:
            
            _write_snapshot(snapshot)
        except BaseException as error:
            with state.condition:
                state.failure_generation = max(
                    state.failure_generation, attempted_generation)
                state.failure = error
                state.active_generation = None
                state.condition.notify_all()
            try:
                log(
                    'Error saving plugin settings generation '
                    f'{attempted_generation}: {error}\n'
                    f'{traceback.format_exc()}')
            except Exception:
                pass
        else:
            with state.condition:
                if attempted_generation > state.durable_generation:
                    state.durable_generation = attempted_generation
                if (state.pending_snapshot is not None
                        and state.pending_snapshot.generation
                        <= attempted_generation):
                    state.pending_snapshot = None
                if state.failure_generation <= attempted_generation:
                    state.failure_generation = -1
                    state.failure = None
                state.active_generation = None
                state.condition.notify_all()

def _start_writer_locked(durable_generation):
    global _writer_state
    state = _WriterState(durable_generation)
    _writer_state = state
    state.start()
    return state

def _schedule_write_locked():
    if _writer_state is None:
        raise RuntimeError('Plugin settings writer is not initialized')
    return _writer_state.request(_snapshot_locked())

def _save_settings_to_file(strict=False):
    """Compatibility shim: schedule a write and return its completion.

    The old helper performed disk I/O inline. Keeping the private name avoids
    breaking host tests and monkeypatches, while all work is now asynchronous.
    """
    with _lock:
        if not _settings_file_path or _writer_state is None:
            if strict:
                raise RuntimeError('Plugin settings path is not initialized')
            return False
        return _schedule_write_locked()

def flush_settings_async():
    """Return a non-blocking durable barrier for the current generation."""
    with _lock:
        if not _settings_file_path or _writer_state is None:
            raise RuntimeError('Plugin settings path is not initialized')
        return _writer_state.request(_snapshot_locked(), force=True)

def flush_settings(timeout=_WRITER_WAIT_SECONDS):
    """Wait a bounded amount of time for the current generation to be durable."""
    return flush_settings_async().result(timeout)

def _load_settings_from_file(validate_buckets=False):
    """Compatibility entry point with host-transaction safety."""
    return _reload_settings(validate_buckets)

def _reload_settings(validate_buckets=True):
    global _settings_cache
    global _generation

    owns_transaction = getattr(_transaction_local, 'depth', 0) == 0
    if owns_transaction:
        begin_host_transaction()
    try:
        if not _settings_file_path:
            raise RuntimeError('Plugin settings path is not initialized')
        loaded_settings = _read_settings_file(
            _settings_file_path, validate_buckets)
        
        with _lock:
            _settings_cache = loaded_settings
            _generation += 1
            if _writer_state is not None:
                _writer_state.mark_durable(_generation)
        return True
    finally:
        if owns_transaction:
            end_host_transaction()

def reload_settings():
    """Atomically adopt a host-side backup replacement."""
    return _reload_settings(validate_buckets=True)

def begin_host_transaction(timeout=_WRITER_WAIT_SECONDS):
    """Acquire an atomic host file transaction after a bounded durable flush."""
    depth = getattr(_transaction_local, 'depth', 0)
    if depth:
        _lock.acquire()
        _transaction_local.depth = depth + 1
        return True

    deadline = _deadline(timeout)
    while True:
        with _lock:
            if _settings_file_path is None:
                remaining = _remaining(deadline)
                acquired = (
                    _lock.acquire()
                    if remaining is None
                    else _lock.acquire(timeout=remaining))
                if not acquired:
                    raise TimeoutError(
                        'Timed out acquiring plugin settings transaction')
                _transaction_local.depth = 1
                return True
            state = _writer_state
            if state is None:
                raise RuntimeError('Plugin settings writer is not initialized')
            completion = state.request(_snapshot_locked(), force=True)

        completion.result(_remaining(deadline))
        remaining = _remaining(deadline)
        acquired = (
            _lock.acquire()
            if remaining is None
            else _lock.acquire(timeout=remaining))
        if not acquired:
            raise TimeoutError(
                'Timed out acquiring plugin settings transaction')

        with state.condition:
            durable = state.durable_generation
        if state is _writer_state and durable >= _generation:
            _transaction_local.depth = 1
            return True
        _lock.release()
        if _remaining(deadline) == 0:
            raise TimeoutError(
                'Plugin settings changed continuously during transaction start')

def end_host_transaction():
    depth = getattr(_transaction_local, 'depth', 0)
    if depth <= 0:
        raise RuntimeError('No plugin settings host transaction is active')
    _transaction_local.depth = depth - 1
    _lock.release()
    return True

def _shutdown_writer(timeout=_WRITER_WAIT_SECONDS, raise_on_error=True):
    """Stop the current writer without waiting longer than timeout."""
    global _writer_state

    deadline = _deadline(timeout)
    with _lock:
        state = _writer_state
        if state is None:
            return True
        with state.condition:
            state.accepting = False
        completion = state.request(_snapshot_locked(), force=True)

    failure = None
    try:
        completion.result(_remaining(deadline))
    except BaseException as error:
        failure = error

    with state.condition:
        state.stop_requested = True
        state.condition.notify_all()
    if state.thread is not threading.current_thread():
        state.thread.join(_remaining(deadline))
    if state.thread.is_alive():
        timeout_error = TimeoutError(
            'Timed out shutting down plugin settings writer')
        if raise_on_error:
            raise timeout_error
        try:
            log(str(timeout_error))
        except Exception:
            pass
        return False

    with _lock:
        if _writer_state is state:
            _writer_state = None
    if failure is not None:
        if raise_on_error:
            raise failure
        try:
            log(f'Plugin settings shutdown flush failed: {failure}')
        except Exception:
            pass
        return False
    return True

def _stop_writer_without_flush(timeout):
    """Abort queued work after an init failure; active filesystem calls finish."""
    global _writer_state

    with _lock:
        state = _writer_state
        if state is None:
            return True
        with state.condition:
            state.accepting = False
            state.pending_snapshot = None
            state.stop_requested = True
            state.condition.notify_all()
    if state.thread is not threading.current_thread():
        state.thread.join(timeout)
    if state.thread.is_alive():
        raise TimeoutError(
            'Timed out aborting failed plugin settings initialization')
    with _lock:
        if _writer_state is state:
            _writer_state = None
    return True

def shutdown(timeout=_WRITER_WAIT_SECONDS):
    """Flush and stop the writer; init() may safely start a new generation."""
    with _lifecycle_lock:
        return _shutdown_writer(timeout=timeout, raise_on_error=True)

def _shutdown_for_reload():
    try:
        with _lifecycle_lock:
            _shutdown_writer(
                timeout=_ATEXIT_WAIT_SECONDS, raise_on_error=False)
    except Exception:
        pass

def get_setting(plugin_id: str, key: str, default: Any) -> Any:
    with _lock:
        return copy.deepcopy(
            _settings_cache.get(plugin_id, {}).get(key, default))

def _runtime_mutation_allowed(plugin_id: str) -> bool:
    """Reject writes made by a revoked/replaced plugin generation.

    Host-side maintenance has no runtime owner and remains allowed. Plugin
    callbacks and inherited workers carry an immutable token, which must still
    be live and must belong to the bucket they are trying to mutate.
    """
    try:
        from plugin_runtime import capture_callback_owner, is_callback_allowed
        token = capture_callback_owner()
        if token is None:
            return True
        token_plugin_id = str(token.getPluginId())
        return token_plugin_id == str(plugin_id) and is_callback_allowed(token)
    except Exception:
        return False

def set_setting(plugin_id: str, key: str, value: Any):
    if not _runtime_mutation_allowed(plugin_id):
        return False
    with _lock:
        global _settings_cache
        global _generation
        if _writer_state is None or not _writer_state.accepting:
            return False
        old_cache = _settings_cache
        new_cache = dict(old_cache)
        bucket = dict(old_cache.get(plugin_id, {}))
        bucket[key] = copy.deepcopy(value)
        new_cache[plugin_id] = bucket
        _settings_cache = new_cache
        _generation += 1
        try:
            completion = _save_settings_to_file(strict=True)
        except Exception:
            _settings_cache = old_cache
            _generation += 1
            return False
    _invalidate_java_cache(plugin_id, key)
    return completion

def clear_settings(plugin_id: str):
    if not _runtime_mutation_allowed(plugin_id):
        return False
    with _lock:
        global _settings_cache
        global _generation
        if _writer_state is None or not _writer_state.accepting:
            return False
        if plugin_id not in _settings_cache:
            return SettingsWriteCompletion(_writer_state, _generation)
        old_cache = _settings_cache
        new_cache = dict(old_cache)
        del new_cache[plugin_id]
        _settings_cache = new_cache
        _generation += 1
        try:
            completion = _save_settings_to_file(strict=True)
        except Exception:
            _settings_cache = old_cache
            _generation += 1
            return False
    _invalidate_java_cache(plugin_id)
    return completion

def get_all_settings(plugin_id: str) -> Any:
    with _lock:
        return copy.deepcopy(_settings_cache.get(plugin_id, {}))

def set_all_settings(plugin_id: str, settings: dict):
    if not _runtime_mutation_allowed(plugin_id):
        return False
    with _lock:
        global _settings_cache
        global _generation
        if _writer_state is None or not _writer_state.accepting:
            return False
        old_cache = _settings_cache
        new_cache = dict(old_cache)
        new_cache[plugin_id] = copy.deepcopy(settings)
        _settings_cache = new_cache
        _generation += 1
        try:
            completion = _save_settings_to_file(strict=True)
        except Exception:
            _settings_cache = old_cache
            _generation += 1
            return False
    _invalidate_java_cache(plugin_id)
    return completion

def _atexit_shutdown():
    _shutdown_for_reload()

if not globals().get('_atexit_registered', False):
    atexit.register(_atexit_shutdown)
    _atexit_registered = True
