"""
extera_utils.classes — subclass Java from Python (exteraGram-compat shim).

exteraGram ships a Cython framework (``Base`` + ``JHelper``) that lets a plugin
define a Python class implementing/overriding Java methods. On NimarkoGram we
reimplement the *common* subset with a Java-owned invocation handler, which can
implement Java **interfaces** (listeners/callbacks — the 80% case) without
leaving Android a retained Chaquopy proxy after plugin unload.

Supported
    * ``@Base.extends(SomeInterface[, OtherInterface...])`` — build a proxy that
      implements the given Java interface(s).
    * ``@JHelper.Override(...)`` / ``@JHelper.Method(...)`` — mark a method as a
      Java-visible implementation. Chaquopy dispatches by method name + argument
      types, so the type-string arguments are accepted but informational.
    * ``@JHelper.Constructor`` / ``@JHelper.PreConstructor`` — passthrough (your
      ``__init__`` should call ``super().__init__()`` so the proxy initialises).
    * ``JHelper.Field`` / ``GetMethod`` / ``SetMethod`` — descriptors over a Java
      field / getter / setter.
    * ``Base.new_instance`` / ``new_java_instance`` — construct and return the
      Java proxy.
    * ``from_java`` — return an existing Java object.

NOT supported (raise ``NotImplementedError`` with a clear message)
    * Subclassing a *concrete* Java class at runtime — Chaquopy can only do this
      at build time via ``static_proxy``; there is no host ``createProxyClass``.
    * ``JHelper.MVELMethod`` / ``MVELOverride`` — no MVEL expression engine.
    * ``JavaSuper`` into an interface (interfaces have no super implementation).
    * Calling arbitrary Python-only helper methods on the returned Java proxy.
      Keep that state behind interface methods instead.
"""

from java import jclass  # noqa: F401  (jclass re-exported for convenience)
from plugin_runtime import capture_callback_owner, make_interface_proxy

__all__ = ['Base', 'JHelper', 'JavaSuper', 'jclass', 'java_subclass', 'joverride', 'joverload']

def _build_proxy_base(java_classes):
    """Validate and return the requested Java callback interfaces."""
    if not java_classes:
        raise ValueError("Base.extends requires at least one Java interface")
    for interface in java_classes:
        java_class = getattr(interface, 'class_', None)
        if java_class is None:
            try:
                java_class = interface if interface.isInterface() else None
            except Exception:
                java_class = None
        try:
            valid = java_class is not None and bool(java_class.isInterface())
        except Exception:
            valid = java_class is not None
        if not valid:
            raise NotImplementedError(
                "extera_utils.classes: only Java interfaces can be "
                "implemented at runtime")
    return tuple(java_classes)

class _ManagedField:
    """Descriptor backing JHelper.Field / GetMethod / SetMethod."""

    def __init__(self, field_type=None, java_name=None, modifiers=None,
                 getter=None, setter=None):
        self.field_type = field_type
        self.java_name = java_name
        self.modifiers = modifiers or []
        self.getter = getter
        self.setter = setter
        self._py_name = None

    def __set_name__(self, owner, name):
        self._py_name = name
        if self.java_name is None:
            self.java_name = name

    def __get__(self, instance, owner=None):
        if instance is None:
            return self
        if self.getter:
            return getattr(instance, self.getter)()
        return getattr(instance, self.java_name)

    def __set__(self, instance, value):
        if 'FINAL' in self.modifiers:
            raise AttributeError(f"field '{self.java_name}' is final")
        if self.setter:
            getattr(instance, self.setter)(value)
        else:
            setattr(instance, self.java_name, value)

class _JavaOwnedProxyMeta(type):
    """Construct the Python target, then publish only a Java-owned proxy."""

    def __call__(cls, *args, **kwargs):
        target = super().__call__(*args, **kwargs)
        owner = getattr(cls, '__nimarko_runtime_owner__', None)
        if owner is None:
            raise RuntimeError(
                "Java callback class was created outside a plugin runtime")
        target._nimarko_runtime_token = owner
        proxy = make_interface_proxy(
            target, cls.__extera_interfaces__, owner=owner)
        target._nimarko_java_proxy = proxy
        return proxy

def _proxy_metaclass_for(user_cls):
    """Compose with a plugin's custom metaclass without losing its contract."""
    user_meta = type(user_cls)
    if issubclass(user_meta, _JavaOwnedProxyMeta):
        return user_meta
    if issubclass(_JavaOwnedProxyMeta, user_meta):
        return _JavaOwnedProxyMeta
    return type(
        f'_NimarkoProxyMeta_{user_meta.__name__}',
        (_JavaOwnedProxyMeta, user_meta),
        {})

class Base:
    """Base class for Python-defined Java-interface proxies."""

    @classmethod
    def extends(cls, *java_classes):
        """Class decorator: make the decorated class implement ``java_classes``."""
        def decorator(user_cls):
            interfaces = _build_proxy_base(java_classes)
            owner = capture_callback_owner()
            if owner is None:
                for value in user_cls.__dict__.values():
                    if callable(value):
                        owner = capture_callback_owner(value)
                        if owner is not None:
                            break
            bases = ((user_cls,)
                     if issubclass(user_cls, Base)
                     else (user_cls, Base))
            namespace = {
                '__module__': user_cls.__module__,
                '__doc__': user_cls.__doc__,
                '__qualname__': user_cls.__qualname__,
                '__extera_interfaces__': interfaces,
                '__nimarko_runtime_owner__': owner,
            }
            proxy_meta = _proxy_metaclass_for(user_cls)
            new_cls = proxy_meta(
                user_cls.__name__, bases, namespace)
            return new_cls
        return decorator

    @property
    def this(self):
        """The proxy instance (usable wherever the Java object is expected)."""
        proxy = getattr(self, '_nimarko_java_proxy', None)
        if proxy is None:
            raise RuntimeError(
                "Java proxy is not available during Python construction")
        return proxy

    @property
    def java(self):
        return self.this

    @property
    def java_class(self):
        return self.this.getClass()

    def on_pre_init(self, *args, **kwargs):
        """Override hook: runs (if defined) before your constructor body."""

    def on_post_init(self, *args, **kwargs):
        """Override hook: runs (if defined) after your constructor body."""

    @classmethod
    def new_instance(cls, *args, **kwargs):
        return cls(*args, **kwargs)

    @classmethod
    def new_java_instance(cls, *args, **kwargs):
        return cls(*args, **kwargs)

    @classmethod
    def from_java(cls, java_obj):
        """Best-effort: wrap/return an existing Java object."""
        return java_obj

    def bind(self, *args, **kwargs):
        return self.this

def _inject_helpers(ns):
    for name in ('this', 'java', 'java_class'):
        ns.setdefault(name, getattr(Base, name))
    for name in ('on_pre_init', 'on_post_init', 'new_instance',
                 'new_java_instance', 'from_java', 'bind'):
        ns.setdefault(name, Base.__dict__[name])

class JavaSuper:
    """``JavaSuper(self).method(...)`` — calls the Java super implementation.

    Interface proxies have no super implementation, so this raises a clear error.
    Kept importable so plugin code resolves.
    """

    def __init__(self, instance):
        self._instance = instance

    def __getattr__(self, name):
        raise NotImplementedError(
            "extera_utils.classes: JavaSuper calls are not available for interface "
            "proxies on NimarkoGram (interfaces have no super implementation).")

def _passthrough_decorator(*_args, **_kwargs):
    """A decorator factory that returns the function unchanged.

    The Java-owned bridge dispatches interface methods by name, so
    Override/Method/Overload/Constructor markers are accepted for source
    compatibility but don't need to alter the function.
    """
    def deco(fn):
        return fn
    return deco

class JHelper:
    
    Override = staticmethod(_passthrough_decorator)
    Method = staticmethod(_passthrough_decorator)
    Overload = staticmethod(_passthrough_decorator)
    Constructor = staticmethod(_passthrough_decorator)
    PreConstructor = staticmethod(_passthrough_decorator)
    ClassBuilder = staticmethod(_passthrough_decorator)

    @staticmethod
    def Field(field_type=None, java_name=None, modifiers=None):
        return _ManagedField(field_type=field_type, java_name=java_name, modifiers=modifiers)

    @staticmethod
    def GetMethod(name=None):
        return _ManagedField(getter=name, java_name=name)

    @staticmethod
    def SetMethod(name=None):
        return _ManagedField(setter=name, java_name=name)

    @staticmethod
    def MVELMethod(*_args, **_kwargs):
        raise NotImplementedError(
            "extera_utils.classes: MVELMethod is not supported on NimarkoGram "
            "(no embedded MVEL engine for Java-from-Python method bodies).")

    MVELOverride = MVELMethod

Constructor = JHelper.Constructor
Method = JHelper.Method
Override = JHelper.Override

def java_subclass(*java_classes):
    """exteraGram-compat: class decorator to subclass Java interface(s).

    Equivalent to ``Base.extends`` — builds a Java-owned proxy over the given
    Java interface(s). Runtime subclassing of a concrete Java class remains
    unsupported and raises NotImplementedError.
    """
    return Base.extends(*java_classes)

def joverride(*args, **kwargs):
    """exteraGram-compat: marks a method as a Java override.

    Passthrough decorator — the Java bridge dispatches interface methods by
    name/signature, so no transformation is needed. Usable both as bare
    ``@joverride`` and as ``@joverride("argtype", ...)``.
    """
    if len(args) == 1 and callable(args[0]) and not kwargs:
        return args[0]  

    def deco(fn):
        return fn
    return deco

joverload = joverride
