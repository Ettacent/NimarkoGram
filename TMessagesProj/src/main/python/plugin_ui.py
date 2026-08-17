"""Main-thread and runtime ownership helpers for plugin UI SDK modules."""

from app.nimarkogram.messenger.plugins.ui import PluginUiRegistry
from app.nimarkogram.messenger.plugins.utils import PythonRunnable
from org.telegram.messenger import AndroidUtilities

from plugin_runtime import capture_callback_owner

def capture_ui_owner(callback=None):
    """Capture the exact immutable runtime which requested a UI operation."""
    return capture_callback_owner(callback)

def post_owned_ui(runtime_token, task):
    """Post plugin-owned creation/show work; revoked tasks are dropped."""
    if runtime_token is None or task is None:
        return False
    AndroidUtilities.runOnUIThread(PythonRunnable(task, runtime_token))
    return True

def is_runtime_current(runtime_token):
    return bool(
        runtime_token is not None
        and PluginUiRegistry.isRuntimeCurrent(runtime_token)
    )

def get_usable_fragment(runtime_token, fragment=None):
    """Resolve and validate a live, attached fragment on the main thread."""
    if not PluginUiRegistry.isMainThread() or not is_runtime_current(runtime_token):
        return None
    candidate = fragment
    if candidate is None:
        from client_utils import get_last_fragment
        candidate = get_last_fragment()
    if candidate is None:
        return None
    if not PluginUiRegistry.isFragmentUsable(runtime_token, candidate):
        return None
    return candidate

def show_bulletin(runtime_token, bulletin):
    """Recheck ownership in Java immediately before Bulletin.show()."""
    return bool(PluginUiRegistry.showBulletin(runtime_token, bulletin))
