"""Host-owned plugin entry imports, not a sandbox for arbitrary Python code.

Keep ordinary helper/package resolution intact. Entry files (top-level __id__
metadata) may execute only through load_plugin, under the host's exact runtime.
Top-level module names remain compatible, but collisions fail before mutation.
"""

import ast
import importlib.abc
import importlib.machinery
import importlib.util
from importlib._bootstrap import _ModuleLockManager
import os
import sys


_roots = globals().get('_roots', set())
_owned = globals().get('_owned', {})


def _canonical(path):
    return os.path.realpath(os.fspath(path))


def _is_entry(origin):
    if not origin or not origin.endswith('.py'):
        return False
    path = _canonical(origin)
    if os.path.dirname(path) not in _roots:
        return False
    # Inspect metadata without executing the candidate or its dependencies.
    # Files without plugin metadata remain ordinary importable helpers.
    with open(path, 'rb') as source:
        tree = ast.parse(source.read(), filename=path)
    for node in tree.body:
        targets = node.targets if isinstance(node, ast.Assign) else (
            [node.target] if isinstance(node, ast.AnnAssign) else [])
        if any(isinstance(target, ast.Name) and target.id == '__id__'
               for target in targets):
            return True
    return False


class _EntryGuard(importlib.abc.MetaPathFinder, importlib.abc.Loader):
    def find_spec(self, fullname, path=None, target=None):
        # Delegate only filesystem resolution here. Earlier SDK/Java finders and
        # modules outside the plugin roots retain their normal precedence.
        spec = importlib.machinery.PathFinder.find_spec(fullname, path)
        if spec is not None and _is_entry(spec.origin):
            spec.loader = self
            return spec
        return None

    def create_module(self, spec):
        return None

    def exec_module(self, module):
        raise ImportError('Plugin entry must be loaded by the host: ' + module.__name__)


_guard = globals().get('_guard', _EntryGuard())


def install(root):
    root = _canonical(root)
    _roots.add(root)
    if _guard not in sys.meta_path:
        # Immediately before PathFinder, not before Chaquopy's Java/SDK finders.
        index = next((i for i, finder in enumerate(sys.meta_path)
                      if finder is importlib.machinery.PathFinder), len(sys.meta_path))
        sys.meta_path.insert(index, _guard)
    if root not in sys.path:
        sys.path.append(root)
    importlib.invalidate_caches()


def _require_runtime(name, token):
    from app.nimarkogram.messenger.plugins import PluginsController
    controller = PluginsController.getInstance()
    if (token is None or str(token.getPluginId()) != name
            or not controller.isPluginRuntimeCurrent(token)
            or controller.getPluginRuntimeTaskDecision(token) == controller.RUNTIME_TASK_DROP
            or controller.captureCurrentPluginRuntime() != token):
        raise ImportError('Plugin import runtime is no longer authorized: ' + name)


def load_plugin(name, path, token):
    """Load exactly path; never relabel, reuse or evict an unrelated module."""
    _require_runtime(name, token)
    path = _canonical(path)
    if os.path.basename(path) != name + '.py':
        raise ImportError('Plugin entry filename does not match its id: ' + name)
    install(os.path.dirname(path))
    # Use Python's per-name import lock: circular self-imports keep working and
    # concurrent normal imports cannot observe a half-published entry module.
    with _ModuleLockManager(name):
        existing = sys.modules.get(name)
        owner = _owned.get(name)
        if existing is not None:
            if (owner is not None and owner[0] is existing
                    and owner[1] == token and owner[2] == path):
                return existing
            raise ImportError('Plugin id collides with an existing Python module: ' + name)
        resolved = importlib.util.find_spec(name)
        if resolved is not None and (
                not resolved.origin or _canonical(resolved.origin) != path):
            raise ImportError('Plugin id collides with a Python module/package: ' + name)
        spec = importlib.util.spec_from_file_location(name, path)
        if spec is None or spec.loader is None:
            raise ImportError('Cannot create plugin entry spec: ' + name)
        module = importlib.util.module_from_spec(spec)
        module.__nimarko_runtime_token__ = token
        module.__nimarko_plugin_id__ = name
        module.__nimarko_plugin_generation__ = token.getGeneration()
        module.__nimarko_plugin_instance_id__ = token.getInstanceId()
        ownership = (module, token, path)
        _owned[name] = ownership
        spec._initializing = True
        sys.modules[name] = module
        try:
            _require_runtime(name, token)
            spec.loader.exec_module(module)
            _require_runtime(name, token)
            if sys.modules.get(name) is not module:
                raise ImportError('Plugin replaced its own module during import: ' + name)
            return module
        except BaseException:
            remove_plugin(name, token)
            raise
        finally:
            spec._initializing = False


def remove_plugin(name, token):
    if token is None:
        return
    with _ModuleLockManager(name):
        owner = _owned.get(name)
        if owner is None or owner[1] != token:
            return
        if sys.modules.get(name) is owner[0]:
            del sys.modules[name]
        del _owned[name]
