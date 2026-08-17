"""Lazy compatibility namespace for TL classes moved out of TLRPC.

Telegram now groups many generated classes in ``org.telegram.tgnet.tl``
containers. Older plugins still expect ``TLRPC.TL_xxx``. Eager reflection over
every container is both expensive and unreliable with Chaquopy class proxies,
so moved classes are resolved only on the first actual lookup.
"""

from java import jclass

try:
    _TLRPC = jclass("org.telegram.tgnet.TLRPC")
except Exception:
    _TLRPC = None

_TL_CONTAINERS = (
    "org.telegram.tgnet.tl.TL_update",
    "org.telegram.tgnet.tl.TL_stories",
    "org.telegram.tgnet.tl.TL_account",
    "org.telegram.tgnet.tl.TL_chatlists",
    "org.telegram.tgnet.tl.TL_stars",
    "org.telegram.tgnet.tl.TL_bots",
    "org.telegram.tgnet.tl.TL_phone",
    "org.telegram.tgnet.tl.TL_payments",
    "org.telegram.tgnet.tl.TL_stats",
    "org.telegram.tgnet.tl.TL_forum",
    "org.telegram.tgnet.tl.TL_fragment",
    "org.telegram.tgnet.tl.TL_iv",
    "org.telegram.tgnet.tl.TL_aicompose",
)

_CACHE = {}
_MISSES = set()

def _resolve_moved(name):
    cached = _CACHE.get(name)
    if cached is not None:
        return cached
    if name in _MISSES or not name.startswith("TL_"):
        raise AttributeError(name)

    for container in _TL_CONTAINERS:
        try:
            proxy = jclass(f"{container}${name}")
        except Exception:
            continue
        _CACHE[name] = proxy
        return proxy

    _MISSES.add(name)
    raise AttributeError(name)

class _TLRPCShim:
    def __getattr__(self, name):
        if _TLRPC is not None:
            try:
                return getattr(_TLRPC, name)
            except Exception:
                pass
        return _resolve_moved(name)

    def __dir__(self):
        names = set(_CACHE)
        if _TLRPC is not None:
            try:
                names.update(dir(_TLRPC))
            except Exception:
                pass
        return sorted(names)

TLRPC = _TLRPCShim()

def get_moved_class(name):
    try:
        return _resolve_moved(name)
    except AttributeError:
        return None
