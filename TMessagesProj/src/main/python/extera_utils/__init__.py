"""
exteraGram-compat package.

In exteraGram the plugin SDK ships an ``extera_utils`` package (compiled .so)
with helper modules used by some plugins. NimarkoGram provides the pure-Python
equivalents here, delegating to the modules that already exist in our SDK
(``markdown_utils``, ``utils.metadata_parser``, ``hook_utils``). This is local
code bundled in the APK and executed by Chaquopy — nothing talks to a server.

Submodules:
    extera_utils.text_formatting  -> markdown/HTML helpers
    extera_utils.metadata_parser  -> read a plugin's __metadata__
    extera_utils.get_caller       -> resolve the calling plugin's id
"""
