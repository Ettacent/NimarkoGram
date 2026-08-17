"""
extera_utils.metadata_parser — read a plugin's declared __metadata__.

Delegates to NimarkoGram's ``utils.metadata_parser.get_metadata(path)`` which
parses the ``__version__`` / ``__name__`` / ``__author__`` / ... module-level
constants out of a .py plugin file. The plugin path is resolved through the
real PluginsController.
"""

from utils import metadata_parser as _mp
from extera_utils.get_caller import get_plugin_id

def _resolve_path(plugin):
    
    if plugin and ('/' in plugin or plugin.endswith('.py')):
        return plugin
    pid = plugin or get_plugin_id()
    if not pid:
        return None
    try:
        from app.nimarkogram.messenger.plugins import PluginsController
        return PluginsController.getInstance().getPluginPath(pid)
    except Exception:
        return None

def get_metadata(plugin=None):
    """
    Return a dict of metadata for the given plugin id / path, or for the
    calling plugin if ``plugin`` is omitted. Returns ``{}`` on failure.
    """
    path = _resolve_path(plugin)
    if not path:
        return {}
    try:
        return _mp.get_metadata(path)
    except Exception:
        return {}
