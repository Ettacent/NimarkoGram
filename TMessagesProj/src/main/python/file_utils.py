import os
import traceback
from typing import List, Optional
from java.io import File
from org.telegram.messenger import FileLoader
from app.nimarkogram.messenger.plugins import PluginsController
from android_utils import log
from plugin_runtime import capture_callback_owner, run_owned_callback

def get_plugins_dir() -> str:
    return PluginsController.getInstance().pluginsDir.getAbsolutePath()

def get_cache_dir() -> str:
    return FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE).getAbsolutePath()

def get_files_dir() -> str:
    return FileLoader.getDirectory(FileLoader.MEDIA_DIR_FILES).getAbsolutePath()

def get_images_dir() -> str:
    return FileLoader.getDirectory(FileLoader.MEDIA_DIR_IMAGE).getAbsolutePath()

def get_videos_dir() -> str:
    return FileLoader.getDirectory(FileLoader.MEDIA_DIR_VIDEO).getAbsolutePath()

def get_audios_dir() -> str:
    return FileLoader.getDirectory(FileLoader.MEDIA_DIR_AUDIO).getAbsolutePath()

def get_documents_dir() -> str:
    return FileLoader.getDirectory(FileLoader.MEDIA_DIR_DOCUMENT).getAbsolutePath()

def read_file(file_path: str) -> Optional[str]:
    try:
        with open(file_path, 'r', encoding='utf-8') as f:
            return f.read()
    except Exception as e:
        log(f'Error reading file {file_path}: {e}\n{traceback.format_exc()}')
        return None

def write_file(file_path: str, content: str):
    try:
        with open(file_path, 'w', encoding='utf-8') as f:
            f.write(content)
    except Exception as e:
        log(f'Error writing to file {file_path}: {e}\n{traceback.format_exc()}')

def read_file_bytes(file_path: str) -> Optional[bytes]:
    try:
        with open(file_path, 'rb') as f:
            return f.read()
    except Exception as e:
        log(f'Error reading file {file_path}: {e}\n{traceback.format_exc()}')
        return None

def write_file_bytes(file_path: str, content: bytes):
    try:
        with open(file_path, 'wb') as f:
            f.write(content)
    except Exception as e:
        log(f'Error writing to file {file_path}: {e}\n{traceback.format_exc()}')

def delete_file(file_path: str) -> bool:
    if os.path.exists(file_path):
        try:
            os.remove(file_path)
        except OSError as e:
            log(f'Error deleting file {file_path}: {e}\n{traceback.format_exc()}')
            return False
        else:
            return True
    else:
        return False

def ensure_dir_exists(dir_path: str):
    try:
        os.makedirs(dir_path, exist_ok=True)
    except OSError as e:
        log(f'Error creating directory {dir_path}: {e}\n{traceback.format_exc()}')
        
def list_dir(path: str, recursive: bool=False, include_files: bool=True, include_dirs: bool=False, extensions: Optional[List[str]]=None) -> List[str]:
    results = []
    if not os.path.isdir(path):
        log(f'Path is not a directory or does not exist: {path}')
        return results
    else:
        if recursive:
            for root, dirs, files in os.walk(path):
                if include_dirs:
                    for d in dirs:
                        results.append(os.path.join(root, d))
                if include_files:
                    for f in files:
                        if not extensions or any((f.endswith(ext) for ext in extensions)):
                            results.append(os.path.join(root, f))
        else:
            for item in os.listdir(path):
                full_path = os.path.join(path, item)
                is_dir = os.path.isdir(full_path)
                if is_dir and include_dirs or (not is_dir and include_files):
                    if not is_dir and extensions and (not any((item.endswith(ext) for ext in extensions))):
                                continue
                    results.append(full_path)
        return results

from dataclasses import dataclass, field
import uuid as _uuid

@dataclass
class FileInfo:
    path: str
    name: Optional[str] = None
    size: int = 0
    extension: Optional[str] = None
    extra: dict = field(default_factory=dict)

    def __post_init__(self):
        try:
            if self.path and self.name is None:
                self.name = os.path.basename(self.path)
            if self.extension is None:
                base = self.name or self.path or ''
                self.extension = _normalize_ext(os.path.splitext(base)[1])
            if (not self.size) and self.path:
                try:
                    if os.path.exists(self.path):
                        self.size = os.path.getsize(self.path)
                except Exception:
                    pass
        except Exception:
            pass

class _FileHandlerHandle:
    def __init__(self, handler_id):
        self.handler_id = handler_id

    def unregister(self):
        FilesController.get_instance().unregister(self)

class ExtensionAlreadyRegistered(Exception):
    pass

class ExtensionNotRegistered(Exception):
    pass

class SecretInvalid(Exception):
    pass

class staticproperty:
    """Descriptor for a read-only static property (exteraGram-compat).
    Allows ``FilesController.Place`` style class-level computed attributes
    without binding ``self``/``cls``."""

    def __init__(self, fget):
        self.fget = fget

    def __get__(self, instance, owner=None):
        return self.fget()

import enum as _enum

class Place(_enum.Enum):
    """Where a registered file hook's open action is surfaced.
    Mirrors exteraGram's ``file_utils.FilesController.Place``."""
    DEFAULT = 'default'
    DOCUMENT = 'document'
    SHARED_FILES = 'shared_files'

SUPPORT_ICONS = []

def _normalize_ext(ext: str) -> str:
    ext = (ext or '').strip().lower()
    if ext and not ext.startswith('.'):
        ext = '.' + ext
    return ext

class _FilesController:
    def __init__(self):
        self._by_id = {}       
        self._by_ext = {}      
        self._lock = __import__('threading').RLock()

    def register(self, plugin_id, extensions, on_click, name=None, icon=None, place=None, secret=None):
        if on_click is None:
            return None
        plugin_id = str(plugin_id)
        if isinstance(extensions, str):
            extensions = [extensions]
        exts = [_normalize_ext(e) for e in (extensions or []) if e]
        exts = [e for e in exts if e]
        if not exts:
            return None
        if secret is not None and not isinstance(secret, str):
            raise SecretInvalid(f"Provided secret '{secret}' is invalid.")
        if place is not None and isinstance(place, str):
            try:
                place = Place(place)
            except ValueError:
                pass
        handler_id = _uuid.uuid4().hex
        committed = {'value': False}
        def commit():
            with self._lock:
                for e in exts:
                    existing = self._by_ext.get(e)
                    if existing is not None and self._by_id.get(existing, {}).get('plugin_id') != plugin_id:
                        raise ExtensionAlreadyRegistered(
                            f"Handler for extension '{e}' is already registered.")
                self._by_id[handler_id] = {
                    'plugin_id': plugin_id, 'extensions': exts, 'on_click': on_click,
                    'name': name, 'icon': icon, 'place': place, 'secret': secret,
                    'runtime_token': capture_callback_owner(on_click),
                }
                for e in exts:
                    self._by_ext[e] = handler_id
                committed['value'] = True
        try:
            if not PluginsController.getInstance().runPluginRuntimePythonMutation(plugin_id, commit):
                if committed['value']:
                    with self._lock:
                        rec = self._by_id.pop(handler_id, None)
                        if rec:
                            for e in rec['extensions']:
                                if self._by_ext.get(e) == handler_id:
                                    self._by_ext.pop(e, None)
                return None
        except ExtensionAlreadyRegistered:
            raise
        except Exception:
            return None
        log(f"nimarko files: registered file hook {handler_id} for {plugin_id} exts={exts}")
        return _FileHandlerHandle(handler_id)

    def unregister(self, handle_or_id):
        hid = handle_or_id.handler_id if isinstance(handle_or_id, _FileHandlerHandle) else handle_or_id
        with self._lock:
            rec = self._by_id.pop(hid, None)
            if rec is None:
                raise ExtensionNotRegistered(f"Handler '{hid}' is not registered.")
            for e in rec['extensions']:
                if self._by_ext.get(e) == hid:
                    self._by_ext.pop(e, None)

    def remove_by_plugin(self, plugin_id, runtime_token=None):
        """Remove one generation, or only legacy tokenless records for None."""
        with self._lock:
            for hid in [
                    k for k, v in self._by_id.items()
                    if v.get('plugin_id') == plugin_id
                    and v.get('runtime_token') == runtime_token]:
                rec = self._by_id.pop(hid, None)
                if rec:
                    for e in rec['extensions']:
                        if self._by_ext.get(e) == hid:
                            self._by_ext.pop(e, None)

    def get_handler_for_extension(self, ext):
        with self._lock:
            hid = self._by_ext.get(_normalize_ext(ext))
            return self._by_id.get(hid) if hid else None

    def dispatch_open(self, file_info) -> bool:
        """Invoke the on_click for the handler matching file_info's extension.
        Returns True if a handler consumed the open. Callable from the file-open
        path (Java or Python). ``file_info`` may be a FileInfo or a path string."""
        if isinstance(file_info, str):
            fi = FileInfo(path=file_info, name=os.path.basename(file_info),
                          extension=_normalize_ext(os.path.splitext(file_info)[1]))
        else:
            fi = file_info
        ext = fi.extension or _normalize_ext(os.path.splitext(fi.path or '')[1])
        rec = self.get_handler_for_extension(ext)
        if not rec:
            return False
        try:
            if not PluginsController.getInstance().isPluginActive(rec.get('plugin_id')):
                return False
        except Exception:
            return False
        try:
            return run_owned_callback(
                rec.get('runtime_token'), rec['on_click'], fi, default=False
            ) is True
        except Exception:
            log(f"nimarko files: on_click failed: {traceback.format_exc()}")
            return False

class FilesController:
    """Public facade mirroring exteraGram's ``file_utils.FilesController``."""

    FileHandlerHandle = _FileHandlerHandle
    ExtensionAlreadyRegistered = ExtensionAlreadyRegistered
    ExtensionNotRegistered = ExtensionNotRegistered
    SecretInvalid = SecretInvalid
    FileInfo = FileInfo
    Place = Place
    SUPPORT_ICONS = SUPPORT_ICONS

    @staticmethod
    def get_instance() -> _FilesController:
        return _FILES_INSTANCE

    @staticmethod
    def register(plugin_id, extensions, on_click, name=None, icon=None, place=None, secret=None):
        return _FILES_INSTANCE.register(plugin_id, extensions, on_click,
                                        name=name, icon=icon, place=place, secret=secret)

    @staticmethod
    def unregister(handle_or_id):
        return _FILES_INSTANCE.unregister(handle_or_id)

_FILES_INSTANCE = _FilesController()

def _remove_file_hooks_for_plugin(plugin_id, runtime_token=None):
    """Called from Java PluginsController.cleanupPlugin on disable/unload."""
    try:
        _FILES_INSTANCE.remove_by_plugin(plugin_id, runtime_token)
    except Exception:
        pass

def _dispatch_open_from_java(path, name=None, mime=None):
    """Called from Java AndroidUtilities.openForView when a chat file is opened.
    Returns True if a registered file hook consumed the open. Fast no-op when no
    file hooks are registered."""
    try:
        if not _FILES_INSTANCE._by_ext:
            return False
        path = str(path) if path is not None else ''
        fname = str(name) if name else os.path.basename(path)
        size = 0
        try:
            if path and os.path.exists(path):
                size = os.path.getsize(path)
        except Exception:
            pass
        fi = FileInfo(
            path=path, name=fname, size=size,
            extension=_normalize_ext(os.path.splitext(fname or path)[1]),
            extra={'mime': str(mime) if mime else None},
        )
        return _FILES_INSTANCE.dispatch_open(fi)
    except Exception:
        log(f"nimarko files: dispatch_open failed: {traceback.format_exc()}")
        return False
