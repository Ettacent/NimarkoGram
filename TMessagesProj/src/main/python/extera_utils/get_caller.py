"""
extera_utils.get_caller — resolve which plugin is currently executing.

The engine loads every plugin as a top-level module named by its plugin id
(``getPython().getModule(<id>)``), so the *module name* of a plugin's own
frame is exactly its id. ``get_plugin_id()`` walks up the call stack and
returns the nearest frame whose module name is a currently-loaded plugin.
"""

import sys

_SDK_MODULES = {
    'extera_utils', 'extera_utils.get_caller', 'extera_utils.metadata_parser',
    'extera_utils.text_formatting', 'extera_utils.classes',
    'base_plugin', 'android_utils', 'client_utils', 'hook_utils',
    'markdown_utils', 'file_utils', 'plugin_settings', 'dev_server',
    'intents', 'importlib', 'runpy', '__main__',
}

def _loaded_plugin_ids():
    try:
        from app.nimarkogram.messenger.plugins import PluginsController
        controller = PluginsController.getInstance()
        return {str(k) for k in controller.plugins.keySet()}
    except Exception:
        return None

def get_plugin_id():
    """Return the id of the plugin calling this function, or ``None``."""
    ids = _loaded_plugin_ids()
    fallback = None
    frame = sys._getframe(1)  
    while frame is not None:
        name = frame.f_globals.get('__name__')
        if name and name not in _SDK_MODULES and not name.startswith('extera_utils.'):
            if ids is not None:
                if name in ids:
                    return name
            elif fallback is None and not name.startswith('ui.'):
                fallback = name
        frame = frame.f_back
    return fallback
