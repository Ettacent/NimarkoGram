from typing import Any
from android.os import Looper
from org.telegram.messenger import AndroidUtilities
from app.nimarkogram.messenger.utils import AppUtils
from app.nimarkogram.messenger.plugins.ui import PluginViewListener
from plugin_runtime import (
    capture_callback_owner,
    make_runnable,
)

R = make_runnable

def OnClickListener(fn: callable):
    """Return a Java listener which is cleared with its exact plugin runtime."""
    token = capture_callback_owner(fn)
    if token is None:
        raise RuntimeError('OnClickListener requires a plugin runtime')
    return PluginViewListener(
        PluginViewListener.TYPE_CLICK, fn, token)

def OnLongClickListener(fn: callable):
    """Return a stale-safe Java long-click listener."""
    token = capture_callback_owner(fn)
    if token is None:
        raise RuntimeError('OnLongClickListener requires a plugin runtime')
    return PluginViewListener(
        PluginViewListener.TYPE_LONG_CLICK, fn, token)

def run_on_ui_thread(func: callable, delay=0):
    AndroidUtilities.runOnUIThread(make_runnable(func), delay)

def is_on_ui_thread() -> bool:
    return Looper.myLooper() == Looper.getMainLooper()

def log(data: Any):
    msg = str(data) if isinstance(data, (str, int, float, bool)) or data is None else None
    try:
        if msg is not None:
            AppUtils.log(msg)
        else:
            AppUtils.printObjectDetails(data)
    except Exception:
        pass
    try:
        print(msg if msg is not None else repr(data))
    except Exception:
        pass

def copy_to_clipboard(text: str):
    if AndroidUtilities.addToClipboard(text):
        from ui.bulletin import BulletinHelper
        BulletinHelper.show_copied_to_clipboard()
