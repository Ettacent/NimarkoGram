"""
NimarkoGram stub for the third-party `nimarko-blocker` package.

The real package on PyPI (author: shareui) ships a one-shot detector that
matches the NG application id and shows an undismissable bottomsheet asking
the user to install exteraGram. We don't install or run it. This stub is
bundled into the APK and registered as a pre-satisfied requirement in
PipController.PREINSTALLED_PACKAGES, so any `__requirements__ = ["nimarko-
blocker"]` declared by a plugin is short-circuited and the runtime import
resolves here.

`block_nimarko()` (and any other attribute looked up on the module) is a
no-op — calling it does nothing, lets the plugin keep loading, and returns
None. Module-level `__getattr__` covers future renames without our needing
to ship a new build.
"""

def _noop(*_args, **_kwargs):
    return None

def block_nimarko(*_args, **_kwargs):
    return None

def __getattr__(_name):
    return _noop
