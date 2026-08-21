"""Process-wide compatibility fixes for third-party plugin runtimes.

The module is installed by :class:`PythonPluginsEngine` before importing any
plugin. It intentionally patches only narrowly identified legacy contracts.
"""

import base64
from collections import OrderedDict
import hashlib
import io
import re
import struct
import threading
import time
import zlib
from urllib.parse import quote, unquote, urlsplit


_INSTALL_LOCK = threading.Lock()
_INSTALLED = False
_MAX_BINARY_SIZE = 64 * 1024 * 1024
_BINARY_SUFFIXES = (".dex", ".jar", ".apk", ".zip", ".so", ".bin")
_EXTERA_PREFIX = "com.exteragram.messenger."
_NIMARKO_PREFIX = "app.nimarkogram.messenger."
_HEAD_CACHE_LIMIT = 4 * 1024 * 1024
_HEAD_CACHE_TTL = 60.0
_HEAD_CACHE_LOCK = threading.Lock()
_HEAD_CACHE = OrderedDict()
_HEAD_CACHE_BYTES = 0
_RAW_PATH = re.compile(
    r"^/api/repos/([^/]+)/([^/]+)/raw/branch/([^/]+)/(.+)$")


def _gitverse_contents_url(url):
    try:
        string_url = str(url)
        if not string_url.startswith("https://gitverse.ru/api/repos/"):
            return None
        parsed = urlsplit(string_url)
        if parsed.scheme != "https" or parsed.netloc.lower() != "gitverse.ru":
            return None
        match = _RAW_PATH.match(unquote(parsed.path))
        if not match:
            return None
        owner, repo, ref, path = match.groups()
        if not path.lower().endswith(_BINARY_SUFFIXES):
            return None
        if ".." in path.split("/"):
            return None
        encoded = "/".join(quote(part, safe="") for part in path.split("/"))
        api_url = (
            f"https://gitverse.ru/api/repos/{quote(owner, safe='')}/"
            f"{quote(repo, safe='')}/contents/{encoded}?ref={quote(ref, safe='')}")
        return api_url, path
    except Exception:
        return None


def _validate_git_blob(data, expected_sha):
    if not expected_sha:
        return
    digest = hashlib.sha1(
        b"blob " + str(len(data)).encode("ascii") + b"\0" + data).hexdigest()
    if digest.lower() != str(expected_sha).lower():
        raise ValueError("Git blob SHA-1 mismatch")


def _validate_dex(data):
    if len(data) < 112 or data[:4] != b"dex\n" or data[7] != 0:
        raise ValueError("invalid DEX magic")
    if not data[4:7].isdigit():
        raise ValueError("invalid DEX version")
    if struct.unpack_from("<I", data, 32)[0] != len(data):
        raise ValueError("DEX file size mismatch")
    if struct.unpack_from("<I", data, 36)[0] != 112:
        raise ValueError("invalid DEX header size")
    if struct.unpack_from("<I", data, 40)[0] != 0x12345678:
        raise ValueError("unsupported DEX byte order")
    if hashlib.sha1(data[32:]).digest() != data[12:32]:
        raise ValueError("DEX signature mismatch")
    if (zlib.adler32(data[12:]) & 0xFFFFFFFF) != struct.unpack_from("<I", data, 8)[0]:
        raise ValueError("DEX checksum mismatch")


def _validate_binary(path, data):
    lowered = path.lower()
    if lowered.endswith(".dex"):
        _validate_dex(data)
    elif lowered.endswith((".jar", ".apk", ".zip")):
        if data[:4] not in (b"PK\x03\x04", b"PK\x05\x06", b"PK\x07\x08"):
            raise ValueError("invalid ZIP-based binary")
    elif lowered.endswith(".so") and not data.startswith(b"\x7fELF"):
        raise ValueError("invalid ELF binary")


def _cache_head_binary(key, data, metadata):
    global _HEAD_CACHE_BYTES
    if len(data) > _HEAD_CACHE_LIMIT:
        return
    with _HEAD_CACHE_LOCK:
        previous = _HEAD_CACHE.pop(key, None)
        if previous is not None:
            _HEAD_CACHE_BYTES -= len(previous[1])
        _HEAD_CACHE[key] = (time.monotonic() + _HEAD_CACHE_TTL, data, metadata)
        _HEAD_CACHE_BYTES += len(data)
        while _HEAD_CACHE_BYTES > _HEAD_CACHE_LIMIT and _HEAD_CACHE:
            _, evicted = _HEAD_CACHE.popitem(last=False)
            _HEAD_CACHE_BYTES -= len(evicted[1])


def _pop_head_binary(key):
    global _HEAD_CACHE_BYTES
    with _HEAD_CACHE_LOCK:
        cached = _HEAD_CACHE.pop(key, None)
        if cached is None:
            return None
        _HEAD_CACHE_BYTES -= len(cached[1])
    expires_at, data, metadata = cached
    return (data, metadata) if expires_at >= time.monotonic() else None


def _install_jclass_compat():
    """Fallback direct Python jclass imports to the current package name."""
    try:
        import java

        original = java.jclass
        if getattr(original, "__nimarko_extera_class_compat__", False):
            return
        aliases = {}

        def jclass_with_compat(class_name):
            name = str(class_name)
            mapped = aliases.get(name)
            if mapped is not None:
                return original(mapped)
            try:
                return original(name)
            except Exception:
                if not name.startswith(_EXTERA_PREFIX):
                    raise
                current_name = _NIMARKO_PREFIX + name[len(_EXTERA_PREFIX):]
                result = original(current_name)
                aliases[name] = current_name
                return result

        jclass_with_compat.__nimarko_extera_class_compat__ = True
        jclass_with_compat.__nimarko_original_jclass__ = original
        java.jclass = jclass_with_compat
    except Exception:
        return


def install():
    """Install the compatibility layer once for the shared interpreter."""
    global _INSTALLED
    with _INSTALL_LOCK:
        if _INSTALLED:
            return True

        _install_jclass_compat()

        import requests

        session_type = requests.sessions.Session
        original = session_type.request
        if getattr(original, "__nimarko_gitverse_binary_compat__", False):
            _INSTALLED = True
            return True

        def request_with_binary_compat(session, method, url, *args, **kwargs):
            target = _gitverse_contents_url(url)
            normalized_method = str(method).upper()
            if target is None or normalized_method not in ("GET", "HEAD"):
                return original(session, method, url, *args, **kwargs)

            api_url, repo_path = target
            try:
                if normalized_method == "GET":
                    cached = _pop_head_binary(api_url)
                    if cached is not None:
                        data, payload = cached
                        cached_response = requests.Response()
                        cached_response.status_code = 200
                        cached_response.reason = "OK"
                        cached_response.url = str(url)
                        cached_response._content = data
                        cached_response._content_consumed = True
                        cached_response.raw = io.BytesIO(data)
                        cached_response.headers["Content-Length"] = str(len(data))
                        cached_response.headers["Content-Type"] = "application/octet-stream"
                        if payload.get("sha"):
                            cached_response.headers["ETag"] = '"{}"'.format(payload["sha"])
                        return cached_response

                api_kwargs = dict(kwargs)
                api_kwargs.pop("stream", None)
                api_response = original(
                    session, "GET", api_url, *args, **api_kwargs)
                if not api_response.ok:
                    return original(session, method, url, *args, **kwargs)

                payload = api_response.json()
                if payload.get("encoding") != "base64":
                    raise ValueError("GitVerse contents response is not base64")
                declared_size = int(payload.get("size", -1))
                if declared_size < 0 or declared_size > _MAX_BINARY_SIZE:
                    raise ValueError("GitVerse binary size is outside safe bounds")
                data = base64.b64decode(payload.get("content", ""), validate=False)
                if len(data) != declared_size:
                    raise ValueError("GitVerse binary size mismatch")
                _validate_git_blob(data, payload.get("sha"))
                _validate_binary(repo_path, data)

                if normalized_method == "HEAD":
                    _cache_head_binary(api_url, data, payload)

                api_response._content = (
                    b"" if normalized_method == "HEAD" else data)
                api_response._content_consumed = True
                api_response.raw = io.BytesIO(api_response._content)
                api_response.encoding = None
                api_response.url = str(url)
                api_response.headers["Content-Length"] = str(len(data))
                api_response.headers["Content-Type"] = "application/octet-stream"
                if payload.get("sha"):
                    api_response.headers["ETag"] = '"{}"'.format(payload["sha"])
                return api_response
            except Exception:
                return original(session, method, url, *args, **kwargs)

        request_with_binary_compat.__nimarko_gitverse_binary_compat__ = True
        request_with_binary_compat.__nimarko_original_request__ = original
        session_type.request = request_with_binary_compat
        _INSTALLED = True
        return True
