# Modifications Copyright (C) 2026 Ettacent
import inspect
def invoke_selection(target, *args):
    callback = target.didSelectDialogs
    if len(args) != 8:
        raise TypeError("Unexpected dialog selection argument count")
    try:
        signature = inspect.signature(callback)
    except (TypeError, ValueError):
        return callback(*args)
    legacy_args = args[:6] + args[7:]
    try:
        signature.bind(*args)
    except TypeError as current_error:
        try:
            signature.bind(*legacy_args)
        except TypeError:
            raise current_error
        if args[6] != 0:
            raise ValueError("Legacy dialog delegate does not support repeat scheduling")
        args = legacy_args
    return callback(*args)
