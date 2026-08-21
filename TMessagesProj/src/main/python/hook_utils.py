from typing import Any, Optional
import traceback
from java import jclass, jarray
from android_utils import log

JavaClass = type(jclass('java.lang.Object'))
JavaObject = jclass('java.lang.Object')
_CLASS_CACHE = {}

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
    'com.exteragram.messenger.plugins.Plugin':
        'app.nimarkogram.messenger.plugins.Plugin',
    'com.exteragram.messenger.utils.text.TranslatorUtils$TranslateCallback':
        'app.nimarkogram.messenger.utils.text.TranslatorUtils$TranslateCallback',
}

def find_class(class_name: str) -> Optional[JavaClass]:
    if not isinstance(class_name, str):
        return None
    cached = _CLASS_CACHE.get(class_name)
    if cached is not None:
        return cached

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

def get_private_field(obj: JavaObject, field_name: str) -> Optional[Any]:
    if not isinstance(obj, JavaObject) or not isinstance(field_name, str):
        return None
        
    clazz = obj.getClass()
    field = None
    current_class = clazz
    
    while current_class is not None:
        try:
            field = current_class.getDeclaredField(field_name)
        except Exception:
            current_class = current_class.getSuperclass()
        else:
            break
            
    if field is None:
        return None

    try:
        field.setAccessible(True)
        value = field.get(obj)
        return value
    except Exception as e:
        log(f'Error accessing field \'{field_name}\' on {obj}: {e}\n{traceback.format_exc()}')
        return None

def set_private_field(obj: JavaObject, field_name: str, new_value: Any) -> bool:
    if not isinstance(obj, JavaObject) or not isinstance(field_name, str):
        return False
        
    clazz = obj.getClass()
    field = None
    current_class = clazz
    
    while current_class is not None:
        try:
            field = current_class.getDeclaredField(field_name)
        except Exception:
            current_class = current_class.getSuperclass()
        else:
            break
            
    if field is None:
        return False

    try:
        field.setAccessible(True)
        field.set(obj, new_value)
        return True
    except Exception as e:
        log(f'Error setting field \'{field_name}\' on {obj}: {e}\n{traceback.format_exc()}')
        return False

def get_static_private_field(clazz: JavaClass, field_name: str) -> Optional[Any]:
    if type(clazz) is not JavaClass or not isinstance(field_name, str):
        return None
        
    field = None
    current_class = clazz
    
    while current_class is not None:
        try:
            field = current_class.getDeclaredField(field_name)
        except Exception:
            current_class = current_class.getSuperclass()
        else:
            break
            
    if field is None:
        return None

    try:
        field.setAccessible(True)
        value = field.get(None)
        return value
    except Exception as e:
        log(f'Error accessing static field \'{field_name}\' from {clazz.getName()}: {e}\n{traceback.format_exc()}')
        return None

def set_static_private_field(clazz: JavaClass, field_name: str, new_value: Any) -> bool:
    if type(clazz) is not JavaClass or not isinstance(field_name, str):
        return False
        
    field = None
    current_class = clazz
    
    while current_class is not None:
        try:
            field = current_class.getDeclaredField(field_name)
        except Exception:
            current_class = current_class.getSuperclass()
        else:
            break
            
    if field is None:
        log(f'Field \'{field_name}\' not found in class hierarchy of {clazz.getName()}')
        return False

    try:
        field.setAccessible(True)
        field.set(None, new_value)
        return True
    except Exception as e:
        log(f'Error setting static field \'{field_name}\' on {clazz.getName()}: {e}\n{traceback.format_exc()}')
        return False
