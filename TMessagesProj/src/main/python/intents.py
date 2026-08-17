"""
intents — Android-intent routing for plugins (exteraGram-compat).

Plugins register handlers via ``base_plugin.add_intent_hook(filters, callback)``.
When an Android intent reaches ``LaunchActivity.handleIntent``, the Java
``IntentsController`` matches the registered filters and calls
``_dispatch_from_java`` here, which builds an :class:`IntentContext` and invokes
the callbacks: global-before handlers, then matched handlers (priority order,
highest first), then global-after handlers. A handler consumes the intent by
calling ``ctx.consume()`` or returning ``True`` — the app's default handling is
then skipped.

Recognised filter keys (all optional): ``scheme``, ``host``, ``path`` (supports
``{name}`` placeholders extracted into ``ctx.path_args``), ``path_regex``,
``action``, ``type``/``mime``, ``categories`` (list), ``whitelist_flags`` (int),
``blacklist_flags`` (int), ``required_path_args_names`` (list),
``required_query_args`` (list), ``hook_type``.
"""

import traceback
import threading
import uuid
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional

from android_utils import log
from app.nimarkogram.messenger.plugins.intents import IntentsController
from plugin_runtime import (
    capture_callback_owner,
    is_callback_allowed,
    run_owned_callback,
)

class HandlerNotRegistered(Exception):
    def __init__(self, handler_id):
        self.handler_id = handler_id
        super().__init__(f"Handler '{handler_id}' is not registered.")

@dataclass
class HandlerInfo:
    handler_id: str
    callback: Callable
    plugin_id: Optional[str] = None
    priority: int = 0
    name: Optional[str] = None
    scheme: Optional[str] = None
    host: Optional[str] = None
    path: Optional[str] = None
    path_regex: Optional[str] = None
    action: Optional[str] = None
    categories: List[str] = field(default_factory=list)
    whitelist_flags: int = 0
    blacklist_flags: int = 0
    required_path_args_names: List[str] = field(default_factory=list)
    required_query_args: List[str] = field(default_factory=list)
    type: Optional[str] = None
    hook_type: str = 'standard'
    runtime_token: Any = None

class IntentContext:
    """Parsed view of an incoming ``android.content.Intent`` passed to handlers."""

    def __init__(self, intent, path_args=None):
        self.intent = intent
        self.path_args = dict(path_args or {})
        data = intent.getData() if intent is not None else None
        self.data = data
        self.action = intent.getAction() if intent is not None else None
        self.scheme = data.getScheme() if data is not None else None
        self.host = data.getHost() if data is not None else None
        self.path = data.getPath() if data is not None else None
        self.type = intent.getType() if intent is not None else None
        self.flags = intent.getFlags() if intent is not None else 0
        self.query_args = {}
        self.categories = set()
        if data is not None:
            try:
                if data.isHierarchical():
                    for name in data.getQueryParameterNames():
                        self.query_args[str(name)] = data.getQueryParameter(name)
            except Exception:
                pass
        if intent is not None:
            try:
                cats = intent.getCategories()
                if cats is not None:
                    for c in cats:
                        self.categories.add(str(c))
            except Exception:
                pass
        self._consumed = False

    def consume(self):
        """Mark the intent handled — the app's default handling will be skipped."""
        self._consumed = True

    @property
    def consumed(self) -> bool:
        return self._consumed

class _HandlerHandle:
    def __init__(self, handler_id, is_global=False):
        self.handler_id = handler_id
        self._is_global = is_global

    def unhandle(self):
        _INSTANCE._remove(self.handler_id, self._is_global)

class _IntentsManager:
    HandlerHandle = _HandlerHandle
    HandlerInfo = HandlerInfo
    HandlerNotRegistered = HandlerNotRegistered

    def __init__(self):
        self._handlers: Dict[str, HandlerInfo] = {}
        self._global_before: List[Any] = []  
        self._global_after: List[Any] = []
        self._lock = threading.RLock()

    def add_handler(self, plugin_id, filters, callback, priority=0, name=None):
        if callback is None:
            return None
        plugin_id = str(plugin_id)
        filters = filters or {}
        handler_id = uuid.uuid4().hex
        info = HandlerInfo(
            handler_id=handler_id, callback=callback, plugin_id=plugin_id,
            priority=int(priority or 0), name=name,
            scheme=filters.get('scheme'), host=filters.get('host'),
            path=filters.get('path'), path_regex=filters.get('path_regex'),
            action=filters.get('action'),
            categories=list(filters.get('categories') or []),
            whitelist_flags=int(filters.get('whitelist_flags') or 0),
            blacklist_flags=int(filters.get('blacklist_flags') or 0),
            required_path_args_names=list(filters.get('required_path_args_names') or []),
            required_query_args=list(filters.get('required_query_args') or []),
            type=filters.get('type', filters.get('mime')),
            hook_type=filters.get('hook_type') or 'standard',
            runtime_token=capture_callback_owner(callback),
        )
        result = {'registered': None}
        def commit():
            with self._lock:
                self._handlers[handler_id] = info
                result['registered'] = IntentsController.getInstance().addIntentHook(
                    self._to_java_filter(plugin_id, info))
                if not result['registered']:
                    self._handlers.pop(handler_id, None)
        try:
            from app.nimarkogram.messenger.plugins import PluginsController
            accepted = PluginsController.getInstance().runPluginRuntimePythonMutation(plugin_id, commit)
        except Exception:
            accepted = False
        if not accepted or not result['registered']:
            if result['registered']:
                with self._lock:
                    self._handlers.pop(handler_id, None)
                try:
                    IntentsController.getInstance().removeIntentHook(handler_id)
                except Exception:
                    pass
            return None
        return _HandlerHandle(handler_id)

    def remove_handler(self, handle_or_id):
        hid = handle_or_id.handler_id if isinstance(handle_or_id, _HandlerHandle) else handle_or_id
        self._remove(hid, is_global=False)

    def _remove(self, handler_id, is_global=False):
        with self._lock:
            if is_global:
                for lst in (self._global_before, self._global_after):
                    for i, record in enumerate(list(lst)):
                        if record[0] == handler_id:
                            lst.pop(i)
                            IntentsController.getInstance().decrementGlobals()
                            return
                raise HandlerNotRegistered(handler_id)
            if handler_id not in self._handlers:
                raise HandlerNotRegistered(handler_id)
            del self._handlers[handler_id]
            IntentsController.getInstance().removeIntentHook(handler_id)

    def new_global_before_handler(self, callback, priority=0):
        return self._add_global(self._global_before, callback, priority)

    def new_global_after_handler(self, callback, priority=0):
        return self._add_global(self._global_after, callback, priority)

    def _add_global(self, lst, callback, priority):
        if callback is None:
            return None
        gid = uuid.uuid4().hex
        plugin_id = self._callback_plugin_id(callback)
        if not plugin_id:
            try:
                from extera_utils.get_caller import get_plugin_id
                plugin_id = get_plugin_id()
            except Exception:
                plugin_id = None
        if not plugin_id:
            log('Rejected ownerless global intent handler')
            return None
        committed = {'value': False}
        def commit():
            with self._lock:
                lst.append((
                    gid, callback, int(priority or 0), plugin_id,
                    capture_callback_owner(callback)))
                lst.sort(key=lambda t: t[2], reverse=True)
                IntentsController.getInstance().incrementGlobals()
                committed['value'] = True
        try:
            controller = __import__(
                'app.nimarkogram.messenger.plugins', fromlist=['PluginsController']
            ).PluginsController.getInstance()
            if not controller.runPluginRuntimePythonMutation(plugin_id, commit):
                if committed['value']:
                    with self._lock:
                        lst[:] = [record for record in lst if record[0] != gid]
                    IntentsController.getInstance().decrementGlobals()
                return None
        except Exception:
            if committed['value']:
                with self._lock:
                    lst[:] = [record for record in lst if record[0] != gid]
                try:
                    IntentsController.getInstance().decrementGlobals()
                except Exception:
                    pass
            return None
        return _HandlerHandle(gid, is_global=True)

    @staticmethod
    def _callback_plugin_id(callback):
        owner = getattr(callback, '__self__', None)
        plugin_id = getattr(owner, 'id', None) if owner is not None else None
        if plugin_id:
            return str(plugin_id)
        module_name = getattr(callback, '__module__', None)
        if not module_name:
            return None
        try:
            from app.nimarkogram.messenger.plugins import PluginsController
            for pid in PluginsController.getInstance().plugins.keySet().toArray():
                if str(pid) == str(module_name):
                    return str(pid)
        except Exception:
            pass
        return None

    def remove_by_plugin(self, plugin_id, runtime_token=None):
        """Remove one generation, or only legacy tokenless records for None."""
        plugin_id = str(plugin_id)
        with self._lock:
            self._remove_by_plugin_locked(plugin_id, runtime_token)

    def _remove_by_plugin_locked(self, plugin_id, runtime_token=None):
        for handler_id, info in list(self._handlers.items()):
            
            if (info.plugin_id == plugin_id
                    and info.runtime_token == runtime_token):
                self._handlers.pop(handler_id, None)
                try:
                    IntentsController.getInstance().removeIntentHook(handler_id)
                except Exception:
                    pass
        for lst in (self._global_before, self._global_after):
            kept = []
            for record in lst:
                owner = record[3] if len(record) > 3 else self._callback_plugin_id(record[1])
                record_token = record[4] if len(record) > 4 else None
                if (owner == plugin_id
                        and record_token == runtime_token):
                    IntentsController.getInstance().decrementGlobals()
                else:
                    kept.append(record)
            lst[:] = kept

    def unhandle(self, handler_id):
        self._remove(handler_id, is_global=False)

    def parse(self, uri_or_intent):
        """Build an IntentContext from an Intent object, or wrap a uri string."""
        try:
            if hasattr(uri_or_intent, 'getAction'):
                return IntentContext(uri_or_intent)
        except Exception:
            pass
        try:
            from android.content import Intent as AndroidIntent
            from android.net import Uri
            
            intent = AndroidIntent()
            intent.setAction(AndroidIntent.ACTION_VIEW)
            intent.setData(Uri.parse(str(uri_or_intent)))
            return IntentContext(intent)
        except Exception as e:
            raise ValueError(f"Invalid uri: {uri_or_intent} ({e})")

    def _to_java_filter(self, plugin_id, info: HandlerInfo) -> dict:
        return {
            'plugin_id': plugin_id,
            'handler_id': info.handler_id,
            'priority': info.priority,
            'name': info.name,
            'scheme': info.scheme,
            'host': info.host,
            'path': info.path,
            'path_regex': info.path_regex,
            'action': info.action,
            'type': info.type,
            'categories': list(info.categories),
            'whitelist_flags': info.whitelist_flags,
            'blacklist_flags': info.blacklist_flags,
            'required_path_args_names': list(info.required_path_args_names),
            'required_query_args': list(info.required_query_args),
            'hook_type': info.hook_type,
        }

    def dispatch(self, intent, matched) -> bool:
        ctx = IntentContext(intent)
        for record in list(self._global_before):
            if self._global_is_active(record):
                self._invoke(record[1], ctx, record[4] if len(record) > 4 else None)
        try:
            for m in matched:
                hid = m.get('handler_id')
                if hid is None:
                    continue
                info = self._handlers.get(str(hid))
                if info is None:
                    continue
                ctx.path_args = _jmap_to_dict(m.get('path_args'))
                self._invoke(info.callback, ctx, info.runtime_token)
        except Exception:
            log(f"Failed to dispatch intent handlers: {traceback.format_exc()}")
        ctx.path_args = {}
        for record in list(self._global_after):
            if self._global_is_active(record):
                self._invoke(record[1], ctx, record[4] if len(record) > 4 else None)
        return ctx.consumed

    def _global_is_active(self, record):
        owner = record[3] if len(record) > 3 else self._callback_plugin_id(record[1])
        if not owner:
            return False
        token = record[4] if len(record) > 4 else None
        if token is not None:
            return is_callback_allowed(token)
        try:
            from app.nimarkogram.messenger.plugins import PluginsController
            return PluginsController.getInstance().isPluginActive(owner)
        except Exception:
            return False

    @staticmethod
    def _invoke(callback, ctx, runtime_token=None):
        try:
            if run_owned_callback(
                    runtime_token, callback, ctx, default=False) is True:
                ctx.consume()
        except Exception:
            log(f"Failed to dispatch intent handlers: {traceback.format_exc()}")

_INSTANCE = _IntentsManager()

class IntentsManager:
    """Public facade mirroring exteraGram's ``IntentsManager``."""

    HandlerHandle = _HandlerHandle
    HandlerInfo = HandlerInfo
    HandlerNotRegistered = HandlerNotRegistered

    @staticmethod
    def get_instance() -> _IntentsManager:
        return _INSTANCE

    @classmethod
    def parse(cls, uri_or_intent):
        return _INSTANCE.parse(uri_or_intent)

    @classmethod
    def new_global_before_handler(cls, callback, priority=0):
        return _INSTANCE.new_global_before_handler(callback, priority)

    @classmethod
    def new_global_after_handler(cls, callback, priority=0):
        return _INSTANCE.new_global_after_handler(callback, priority)

    @classmethod
    def unhandle(cls, handler_id):
        return _INSTANCE.unhandle(handler_id)

def _jmap_to_dict(m):
    d = {}
    if m is None:
        return d
    try:
        for k in m.keySet():
            d[str(k)] = m.get(k)
    except Exception:
        pass
    return d

def _dispatch_from_java(intent, matched):
    """Entry point invoked by Java ``IntentsController.dispatchIntent()``."""
    try:
        return _INSTANCE.dispatch(intent, matched)
    except Exception:
        log(f"Failed to dispatch intent handlers: {traceback.format_exc()}")
        return False

def _remove_intent_hooks_for_plugin(plugin_id, runtime_token=None):
    """Drop normal and global Python callbacks on disable/uninstall/safe mode."""
    try:
        _INSTANCE.remove_by_plugin(plugin_id, runtime_token)
    except Exception:
        log(f"Failed to remove intent handlers for {plugin_id}: {traceback.format_exc()}")
