from typing import Any, Optional
import traceback
from java import jclass, jarray
from android_utils import log

JavaClass = type(jclass('java.lang.Object'))
JavaObject = jclass('java.lang.Object')
_CLASS_CACHE = {}
_FIELD_CACHE = {}
_MAX_FIELD_CACHE_SIZE = 256

# Remap table for HOOK-TARGET classes.
#
# exteraGram plugins resolve a class by its `com.exteragram.messenger.*` name
# and then Xposed-hook its methods/constructors or use it as a reflection
# parameter type. The running app, however, instantiates the REAL
# `app.nimarkogram.messenger.*` classes; the `com.exteragram.*` twins are
# either subclass ALIASES (so a hook installed on the alias' Method/Constructor
# never fires on the real instance's dispatch) or do not exist at all. For
# these specific classes we resolve and return the REAL class so hooks fire and
# reflection signatures line up.
#
# DELIBERATELY NOT REMAPPED (must keep returning the bridge):
#   - PluginsController  -> bridge exposes a Chaquopy-safe LinkedHashMap
#                           snapshot `plugins`; the real ConcurrentHashMap
#                           reintroduces a reflection bug.
#   - Plugin, plugins.models.*, PluginsConstants, ExteraConfig, BuildConfig,
#     all enums, utils.* -> bridge has translation/forwarder behavior or no
#                           hookable app twin (they are CALLED, not hooked).
#   - AppEvent / speech.ui.LoadingModelView / ai.* -> no real app twin exists;
#                           callers already degrade gracefully on None.
#
# NOTE: InstallPluginBottomSheet$PluginInstallParams and
# PluginsController$PluginValidationResult are remapped because they are the
# *parameter types* of the REAL InstallPluginBottomSheet constructor that
# quantahut hooks; Java reflection requires the exact real nested type for the
# getDeclaredConstructor lookup to resolve. Remapping these nested types does
# not affect resolution of the PluginsController class itself.
_HOOK_TARGET_REMAP = {
    'com.exteragram.messenger.plugins.ui.PluginsActivity':
        'app.nimarkogram.messenger.plugins.ui.PluginsActivity',
    'com.exteragram.messenger.plugins.ui.PluginSettingsActivity':
        'app.nimarkogram.messenger.plugins.ui.PluginSettingsActivity',
    'com.exteragram.messenger.plugins.ui.components.PluginCell':
        'app.nimarkogram.messenger.plugins.ui.components.PluginCell',
    'com.exteragram.messenger.plugins.ui.components.PluginCellDelegate':
        'app.nimarkogram.messenger.plugins.ui.components.PluginCellDelegate',
    'com.exteragram.messenger.plugins.ui.components.InstallPluginBottomSheet':
        'app.nimarkogram.messenger.plugins.ui.components.InstallPluginBottomSheet',
    'com.exteragram.messenger.plugins.ui.components.InstallPluginBottomSheet$PluginInstallParams':
        'app.nimarkogram.messenger.plugins.ui.components.InstallPluginBottomSheet$PluginInstallParams',
    'com.exteragram.messenger.plugins.ui.components.SafeModeBottomSheet':
        'app.nimarkogram.messenger.plugins.ui.components.SafeModeBottomSheet',
    'com.exteragram.messenger.plugins.PythonPluginsEngine':
        'app.nimarkogram.messenger.plugins.PythonPluginsEngine',
    'com.exteragram.messenger.plugins.PluginsController$PluginValidationResult':
        'app.nimarkogram.messenger.plugins.PluginsController$PluginValidationResult',
    # Plugin: used as a class-typed param in PluginCell.getDeclaredMethod("set",
    # Plugin, PluginCellDelegate) — the real signature uses app.nimarkogram.Plugin,
    # so find_class("...Plugin") MUST return the real class for the lookup to match.
    'com.exteragram.messenger.plugins.Plugin':
        'app.nimarkogram.messenger.plugins.Plugin',
    # TranslateCallback: used as a dynamic_proxy() base, then the proxy is passed
    # to the real TranslatorUtils.translate(...) — must be the real interface.
    'com.exteragram.messenger.utils.text.TranslatorUtils$TranslateCallback':
        'app.nimarkogram.messenger.utils.text.TranslatorUtils$TranslateCallback',
}

def find_class(class_name: str) -> Optional[JavaClass]:
    if not isinstance(class_name, str):
        return None
    cached = _CLASS_CACHE.get(class_name)
    if cached is not None:
        return cached

    # HOOK-TARGET remap: resolve the REAL app class so hooks fire / reflection
    # signatures match. On any failure, fall through to the normal resolution
    # of the original name so behavior never regresses. Never raises.
    remapped = _HOOK_TARGET_REMAP.get(class_name)
    if remapped is not None:
        try:
            clazz = jclass(remapped)
            _CLASS_CACHE[class_name] = clazz
            _CLASS_CACHE[remapped] = clazz
            return clazz
        except Exception as e:
            log(f'Failed to resolve remapped class \'{remapped}\' for \'{class_name}\', '
                f'falling back to original: {e}')

    try:
        clazz = jclass(class_name)
        _CLASS_CACHE[class_name] = clazz
        return clazz
    except Exception as original_error:
        # Generic namespace fallback for old plugins which reference a class
        # that moved unchanged from exteraGram to NimarkoGram. Explicit
        # entries above still win, and an existing com.exteragram bridge is
        # always preferred, so adapter classes keep their translation logic.
        prefix = 'com.exteragram.messenger.'
        if class_name.startswith(prefix):
            current_name = 'app.nimarkogram.messenger.' + class_name[len(prefix):]
            try:
                clazz = jclass(current_name)
                _CLASS_CACHE[class_name] = clazz
                _CLASS_CACHE[current_name] = clazz
                return clazz
            except Exception:
                pass
        log(f'Error finding class \'{class_name}\': {original_error}\n{traceback.format_exc()}')
        return None

def _find_private_field(clazz: JavaClass, field_name: str):
    try:
        cache_key = (str(clazz.getName()), int(clazz.hashCode()), field_name)
    except Exception:
        cache_key = (str(clazz), field_name)
    cached = _FIELD_CACHE.get(cache_key)
    if cached is not None:
        return cached

    current_class = clazz
    while current_class is not None:
        try:
            field = current_class.getDeclaredField(field_name)
            field.setAccessible(True)
            if len(_FIELD_CACHE) >= _MAX_FIELD_CACHE_SIZE:
                _FIELD_CACHE.clear()
            _FIELD_CACHE[cache_key] = field
            return field
        except Exception:
            current_class = current_class.getSuperclass()
    return None

def get_private_field(obj: JavaObject, field_name: str) -> Optional[Any]:
    if not isinstance(obj, JavaObject) or not isinstance(field_name, str):
        return None

    field = _find_private_field(obj.getClass(), field_name)
    if field is None:
        return None

    try:
        value = field.get(obj)
        return value
    except Exception as e:
        log(f'Error accessing field \'{field_name}\' on {obj}: {e}\n{traceback.format_exc()}')
        return None

def set_private_field(obj: JavaObject, field_name: str, new_value: Any) -> bool:
    if not isinstance(obj, JavaObject) or not isinstance(field_name, str):
        return False
        
    field = _find_private_field(obj.getClass(), field_name)
    if field is None:
        return False

    try:
        field.set(obj, new_value)
        return True
    except Exception as e:
        log(f'Error setting field \'{field_name}\' on {obj}: {e}\n{traceback.format_exc()}')
        return False

def get_static_private_field(clazz: JavaClass, field_name: str) -> Optional[Any]:
    if type(clazz) is not JavaClass or not isinstance(field_name, str):
        return None
        
    field = _find_private_field(clazz, field_name)
    if field is None:
        return None

    try:
        value = field.get(None)
        return value
    except Exception as e:
        log(f'Error accessing static field \'{field_name}\' from {clazz.getName()}: {e}\n{traceback.format_exc()}')
        return None

def set_static_private_field(clazz: JavaClass, field_name: str, new_value: Any) -> bool:
    if type(clazz) is not JavaClass or not isinstance(field_name, str):
        return False
        
    field = _find_private_field(clazz, field_name)
    if field is None:
        log(f'Field \'{field_name}\' not found in class hierarchy of {clazz.getName()}')
        return False

    try:
        field.set(None, new_value)
        return True
    except Exception as e:
        log(f'Error setting static field \'{field_name}\' on {clazz.getName()}: {e}\n{traceback.format_exc()}')
        return False
