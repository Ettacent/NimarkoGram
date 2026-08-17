"""Runtime ownership helpers for NimarkoGram plugins.

Every imported plugin module receives an immutable Java PluginRuntimeToken.
The helpers in this module propagate that token into queued work and reject
callbacks from an unloaded/replaced Python instance.  This is intentionally
kept in one SDK module so old plugins gain the protection without source
changes.
"""

import functools
import sys

_MODULE_TOKEN_KEY = "__nimarko_runtime_token__"

_UNSET = globals().get("_UNSET", object())
_THREAD_PATCHED = globals().get("_THREAD_PATCHED", False)
_TIMER_PATCHED = globals().get("_TIMER_PATCHED", False)
_EXECUTOR_PATCHED = globals().get("_EXECUTOR_PATCHED", False)
_LOW_LEVEL_THREAD_PATCHED = globals().get(
    "_LOW_LEVEL_THREAD_PATCHED", False)

def _token_from_globals(namespace):
    if isinstance(namespace, dict):
        return namespace.get(_MODULE_TOKEN_KEY)
    return None

def _token_from_object(value):
    if value is None:
        return None
    for name in ("_runtime_token", "_nimarko_runtime_token"):
        try:
            token = getattr(value, name, None)
        except Exception:
            token = None
        if token is not None:
            return token
    return None

def capture_callback_owner(callback=None):
    """Return the immutable runtime token which owns *callback* or this call."""
    if callback is not None:
        candidate = callback
        seen = set()
        for _ in range(12):
            if candidate is None or id(candidate) in seen:
                break
            seen.add(id(candidate))
            token = _token_from_object(candidate)
            if token is not None:
                return token
            try:
                token = _token_from_object(
                    getattr(candidate, "__self__", None))
            except Exception:
                token = None
            if token is not None:
                return token
            try:
                token = _token_from_globals(
                    getattr(candidate, "__globals__", None))
            except Exception:
                token = None
            if token is not None:
                return token
            try:
                call = getattr(candidate, "__call__", None)
                token = _token_from_globals(
                    getattr(call, "__globals__", None))
            except Exception:
                token = None
            if token is not None:
                return token
            
            candidate = (
                getattr(candidate, "func", None)
                or getattr(candidate, "__wrapped__", None)
            )

    frame = None
    try:
        frame = sys._getframe(1)
        for _ in range(32):
            if frame is None:
                break
            token = _token_from_globals(frame.f_globals)
            if token is not None:
                return token
            frame = frame.f_back
    except Exception:
        pass
    finally:
        
        del frame

    try:
        import threading
        token = getattr(
            threading.current_thread(), "_nimarko_runtime_token", None)
        if token is not None:
            return token
    except Exception:
        pass

    try:
        from app.nimarkogram.messenger.plugins import PluginsController
        return PluginsController.getInstance().captureCurrentPluginRuntime()
    except Exception:
        return None

def is_callback_allowed(token):
    if token is None:
        return False
    try:
        from app.nimarkogram.messenger.plugins import PluginsController
        return bool(PluginsController.getInstance().isPluginRuntimeCallbackAllowed(token))
    except Exception:
        return False

def _await_worker_runtime(token):
    """Wait until a newly loaded plugin runtime is published or revoked.

    Plugins commonly start database/executor workers from ``on_plugin_load``.
    Those workers can reach their bootstrap before the lifecycle method
    returns and Java atomically changes PREPARING to ACTIVE. Treating that
    short PREPARING window as a rejection makes startup scheduler-dependent:
    the plugin appears enabled, but its worker and deferred hooks never run.

    Direct UI/listener callbacks remain fail-closed in
    ``is_callback_allowed``. Only newly created worker threads may wait here,
    and cancellation/replacement changes their decision to DROP immediately.
    """
    if token is None:
        return False
    try:
        import time
        from app.nimarkogram.messenger.plugins import PluginsController
        controller = PluginsController.getInstance()
        decision_for = getattr(
            controller, "getPluginRuntimeTaskDecision", None)
        if not callable(decision_for):
            
            return bool(
                controller.isPluginRuntimeCallbackAllowed(token))
        run = int(getattr(
            PluginsController, "RUNTIME_TASK_RUN", 2))
        wait = int(getattr(
            PluginsController, "RUNTIME_TASK_WAIT", 1))
        
        deadline = time.monotonic() + 31.0
        while True:
            decision = int(decision_for(token))
            if decision == run:
                return True
            if decision != wait or time.monotonic() >= deadline:
                return False
            
            time.sleep(0.002)
    except Exception:
        return False

def run_owned_callback(token, callback, *args, default=None, **kwargs):
    """Invoke a direct listener/network callback inside its exact runtime."""
    return _run_owned_callback(
        token, callback, args, kwargs, default)

def _propagated_throwable(failure):
    """Return the exact Java Throwable which will leave this callback.

    Chaquopy creates a PyException only when a native Python exception crosses
    the Java boundary. The watchdog needs the identity of that same Throwable
    before the boundary is crossed, so materialize it here and then re-raise
    that object. Java-originated exceptions are already Throwables and must
    retain their identity.
    """
    try:
        from java.lang import Throwable
        if isinstance(failure, Throwable):
            return failure
        from com.chaquo.python import PyException
        try:
            message = f"{type(failure).__name__}: {failure}"
        except BaseException:
            message = type(failure).__name__
        propagated = PyException(message)
        try:
            return propagated.with_traceback(failure.__traceback__)
        except BaseException:
            return propagated
    except BaseException:
        
        return failure

def _run_owned_callback(token, callback, args, kwargs, default):
    """Internal form which doesn't reserve callback keyword argument names."""
    if callback is None or token is None:
        return default
    try:
        from app.nimarkogram.messenger.plugins import PluginsController
        controller = PluginsController.getInstance()
    except Exception:
        return default
    try:
        if (not controller.isPluginRuntimeCallbackAllowed(token)
                or not controller.enterPluginRuntime(token)):
            return default
    except Exception:
        return default
    try:
        try:
            plugin_id = token.getPluginId()
            watchdog = controller.getWatchdog()
            if not plugin_id or watchdog is None:
                return default
            
            watchdog.onPluginExecutionStarted(plugin_id)
        except Exception:
            return default
        try:
            return callback(*args, **kwargs)
        except BaseException as failure:
            propagated = _propagated_throwable(failure)
            try:
                watchdog.onPluginExecutionFailed(
                    plugin_id, propagated)
            except BaseException:
                
                pass
            if propagated is failure:
                raise
            raise propagated
        finally:
            try:
                watchdog.onPluginExecutionFinished(plugin_id)
            except BaseException:
                
                pass
    finally:
        try:
            controller.exitPluginRuntime(token)
        except Exception:
            
            pass

def guard_proxy_callback(callback, owner=_UNSET, default=None):
    """Bind a proxy method to the exact runtime which created its class.

    ``owner`` is deliberately captured in the wrapper rather than read from
    the proxy instance at invocation time. An old Java proxy may outlive an
    OFF→ON cycle, and must never borrow the replacement instance's capability.
    Ownerless SDK proxies are wrapped as disabled callbacks. Host code which
    isn't implementing plugin callbacks continues to use its native Java path.
    """
    if owner is _UNSET:
        owner = capture_callback_owner(callback)
    if getattr(callback, "_nimarko_proxy_runtime_guarded", False):
        return callback

    @functools.wraps(callback)
    def guarded(*args, **kwargs):
        return _run_owned_callback(
            owner, callback, args, kwargs, default)

    guarded._nimarko_proxy_runtime_guarded = True
    guarded._nimarko_runtime_token = owner
    return guarded

def run_owned_worker(token, callback, *args, default=None, **kwargs):
    """Run long-lived plugin work without holding Java callback quiescence.

    Holding ``enterPluginRuntime`` for an entire executor task deadlocks OFF:
    ``on_plugin_unload`` cannot run until active callbacks leave, while many
    workers intentionally wait for ``on_plugin_unload`` to set their stop
    event. The immutable token is propagated on the worker thread so every SDK
    publication remains guarded, but only short Java-facing callbacks enter
    the runtime counter.
    """
    if callback is None or token is None:
        return default
    if not _await_worker_runtime(token):
        return default
    import threading
    current = threading.current_thread()
    previous = getattr(current, "_nimarko_runtime_token", _UNSET)
    current._nimarko_runtime_token = token
    try:
        return callback(*args, **kwargs)
    finally:
        if previous is _UNSET:
            try:
                del current._nimarko_runtime_token
            except AttributeError:
                pass
        else:
            current._nimarko_runtime_token = previous

def make_runnable(callback, owner=_UNSET):
    """Create the Java one-shot Runnable while preserving plugin ownership."""
    if owner is _UNSET:
        owner = capture_callback_owner(callback)
    if owner is None:
        raise RuntimeError(
            "Plugin Runnable requires an exact runtime owner")
    from app.nimarkogram.messenger.plugins.utils import PythonRunnable
    return PythonRunnable(callback, owner)

def make_interface_proxy(target, interfaces, owner=_UNSET):
    """Create a Java-owned proxy for one or more callback interfaces.

    Android must never retain a Chaquopy ``dynamic_proxy`` after its Python
    module can be unloaded. The Java invocation handler created here remains a
    valid Java object for its full lifetime and drops its Python target as soon
    as the exact plugin generation is revoked.
    """
    if owner is _UNSET:
        owner = capture_callback_owner(target)
    if owner is None:
        raise RuntimeError(
            "Plugin interface proxy requires an exact runtime owner")
    if not interfaces:
        raise ValueError("At least one Java callback interface is required")

    from java import jarray, jclass
    from app.nimarkogram.messenger.plugins.bridge import PythonInterfaceProxy

    resolved = []
    for interface in interfaces:
        java_class = getattr(interface, "class_", None)
        if java_class is None:
            
            try:
                if bool(interface.isInterface()):
                    java_class = interface
            except Exception:
                java_class = None
        if java_class is None:
            raise TypeError(
                f"{interface!r} does not expose a Java interface class")
        resolved.append(java_class)

    classes = jarray(jclass("java.lang.Class"))(resolved)
    return PythonInterfaceProxy.create(target, owner, classes)

def _install_thread_propagation():
    """Propagate runtime identity through Thread/Timer and executor tasks."""
    global _THREAD_PATCHED, _TIMER_PATCHED, _EXECUTOR_PATCHED
    global _LOW_LEVEL_THREAD_PATCHED

    try:
        import threading
        if not _THREAD_PATCHED:
            original_init = threading.Thread.__init__
            original_bootstrap_inner = threading.Thread._bootstrap_inner

            @functools.wraps(original_init)
            def owned_init(self, *args, **kwargs):
                target = kwargs.get("target")
                if target is None and len(args) >= 2:
                    target = args[1]
                token = capture_callback_owner(target)
                original_init(self, *args, **kwargs)
                if token is not None:
                    self._nimarko_runtime_token = token

            @functools.wraps(original_bootstrap_inner)
            def owned_bootstrap_inner(self, *args, **kwargs):
                token = getattr(self, "_nimarko_runtime_token", None)
                original_run = None
                if token is not None:
                    
                    original_run = self.run

                    @functools.wraps(original_run)
                    def guarded_run():
                        if _await_worker_runtime(token):
                            return original_run()
                        return None

                    self.run = guarded_run
                try:
                    return original_bootstrap_inner(self, *args, **kwargs)
                finally:
                    if original_run is not None:
                        self.run = original_run

            threading.Thread.__init__ = owned_init
            threading.Thread._bootstrap_inner = owned_bootstrap_inner
            _THREAD_PATCHED = True

        if not _TIMER_PATCHED:
            original_timer_init = threading.Timer.__init__

            @functools.wraps(original_timer_init)
            def owned_timer_init(
                    self, interval, function, args=None, kwargs=None):
                token = capture_callback_owner(function)
                if token is None:
                    original_timer_init(
                        self, interval, function,
                        args=args, kwargs=kwargs)
                    return

                @functools.wraps(function)
                def guarded_function(*call_args, **call_kwargs):
                    return run_owned_worker(
                        token, function, *call_args,
                        default=None, **call_kwargs)

                original_timer_init(
                    self, interval, guarded_function,
                    args=args, kwargs=kwargs)
                self._nimarko_runtime_token = token

            threading.Timer.__init__ = owned_timer_init
            _TIMER_PATCHED = True
    except Exception:
        pass

    try:
        import _thread
        if not _LOW_LEVEL_THREAD_PATCHED:
            original_start_new_thread = _thread.start_new_thread

            @functools.wraps(original_start_new_thread)
            def owned_start_new_thread(function, args, kwargs=None):
                token = capture_callback_owner(function)
                if token is None:
                    if kwargs is None:
                        return original_start_new_thread(function, args)
                    return original_start_new_thread(
                        function, args, kwargs)

                @functools.wraps(function)
                def invoke(*call_args, **call_kwargs):
                    return run_owned_worker(
                        token, function, *call_args,
                        default=None, **call_kwargs)

                if kwargs is None:
                    return original_start_new_thread(invoke, args)
                return original_start_new_thread(invoke, args, kwargs)

            _thread.start_new_thread = owned_start_new_thread
            _LOW_LEVEL_THREAD_PATCHED = True
    except Exception:
        pass

    try:
        import concurrent.futures
        if not _EXECUTOR_PATCHED:
            original_submit = concurrent.futures.ThreadPoolExecutor.submit
            original_add_done_callback = concurrent.futures.Future.add_done_callback

            @functools.wraps(original_submit)
            def owned_submit(self, fn, /, *args, **kwargs):
                token = capture_callback_owner(fn)
                if token is None:
                    return original_submit(self, fn, *args, **kwargs)

                @functools.wraps(fn)
                def invoke():
                    return run_owned_worker(
                        token, fn, *args, default=None, **kwargs)

                return original_submit(self, invoke)

            concurrent.futures.ThreadPoolExecutor.submit = owned_submit

            @functools.wraps(original_add_done_callback)
            def owned_add_done_callback(self, fn):
                token = capture_callback_owner(fn)
                if token is None:
                    return original_add_done_callback(self, fn)

                @functools.wraps(fn)
                def invoke(future):
                    return run_owned_callback(
                        token, fn, future, default=None)

                return original_add_done_callback(self, invoke)

            concurrent.futures.Future.add_done_callback = owned_add_done_callback
            _EXECUTOR_PATCHED = True
    except Exception:
        pass

_install_thread_propagation()
