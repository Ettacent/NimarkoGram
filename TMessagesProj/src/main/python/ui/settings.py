from dataclasses import dataclass, field
from typing import List, Callable, Any, Optional
from android.view import View
from app.nimarkogram.messenger.plugins import PluginsConstants

@dataclass
class Switch:
    key: str
    text: str
    default: bool
    subtext: str = None
    icon: str = None
    on_change: Callable[[bool], None] = field(default=None, compare=False, repr=False)
    type: str = field(default=PluginsConstants.Settings.TYPE_SWITCH, init=False)
    on_long_click: Callable[[View], None] = field(default=None, compare=False, repr=False)
    link_alias: str = None

@dataclass
class Selector:
    key: str
    text: str
    default: int
    items: List[str]
    icon: str = None
    on_change: Callable[[int], None] = field(default=None, compare=False, repr=False)
    type: str = field(default=PluginsConstants.Settings.TYPE_SELECTOR, init=False)
    on_long_click: Callable[[View], None] = field(default=None, compare=False, repr=False)
    link_alias: str = None

@dataclass
class Input:
    key: str
    text: str
    default: str = ''
    subtext: str = None
    icon: str = None
    on_change: Callable[[str], None] = field(default=None, compare=False, repr=False)
    type: str = field(default=PluginsConstants.Settings.TYPE_INPUT, init=False)
    on_long_click: Callable[[View], None] = field(default=None, compare=False, repr=False)
    link_alias: str = None

@dataclass
class Text:
    text: str
    subtext: str = None
    icon: str = None
    accent: bool = False
    red: bool = False
    on_click: Callable[[View], None] = field(default=None, compare=False, repr=False)
    create_sub_fragment: Callable[[], List[Any]] = field(default=None, compare=False, repr=False)
    type: str = field(default=PluginsConstants.Settings.TYPE_TEXT, init=False)
    on_long_click: Callable[[View], None] = field(default=None, compare=False, repr=False)
    link_alias: str = None

@dataclass
class Header:
    text: str
    type: str = field(default=PluginsConstants.Settings.TYPE_HEADER, init=False)

@dataclass
class Divider:
    text: str = None
    type: str = field(default=PluginsConstants.Settings.TYPE_DIVIDER, init=False)
    
@dataclass
class EditText:
    key: str
    hint: str
    default: str = ''
    multiline: bool = False
    max_length: int = 256
    mask: str = None
    on_change: Callable[[str], None] = field(default=None, compare=False, repr=False)
    type: str = field(default=PluginsConstants.Settings.TYPE_EDIT_TEXT, init=False)

@dataclass
class Custom:
    """
    A settings row that renders a plugin-supplied Android View.

    ``create_view(context)`` must return an ``android.view.View`` and is called
    when the row is bound. ``bind_view(view)`` (optional) runs afterwards to
    (re)populate it. ``on_click`` / ``on_long_click`` fire on row interaction.
    """
    create_view: Callable[[Any], View] = field(default=None, compare=False, repr=False)
    bind_view: Callable[[View], None] = field(default=None, compare=False, repr=False)
    
    view: View = field(default=None, compare=False, repr=False)
    item: Any = None
    text: str = None
    subtext: str = None
    divider: bool = False
    on_click: Callable[[View], None] = field(default=None, compare=False, repr=False)
    on_long_click: Callable[[View], None] = field(default=None, compare=False, repr=False)
    link_alias: str = None
    type: str = field(default=PluginsConstants.Settings.TYPE_CUSTOM, init=False)

    def __post_init__(self):
        
        if self.view is not None and self.create_view is None:
            _prebuilt = self.view
            self.create_view = lambda context, _v=_prebuilt: _v

class SimpleSettingFactory:
    """
    exteraGram-compatible reusable factory for plugin-supplied custom settings
    rows.

    In exteraGram this wraps ``CustomSetting.Factory`` / ``UItem.UItemFactory``
    and is *called* to produce a settings item, e.g.::

        my_row = SimpleSettingFactory(
            custom_name="my_row",
            create_view_fn=lambda ctx: MyView(ctx),
            bind_view_fn=lambda view: view.refresh(),
            on_click_handler=lambda view: do_something(),
        )

        def create_settings(self):
            return [my_row("arg_a", "arg_b")]

    Each call returns a fresh, renderable settings row (a :class:`Custom`),
    delegating view creation/binding and click handling to the supplied
    callables. ``custom_name`` is a stable identifier (kept for parity with
    exteraGram; useful for diffing / debugging). ``is_clickable`` / ``is_shadow``
    are advisory flags mirroring ``UItem.UItemFactory``; ``equals_handler`` /
    ``content_equals_handler`` / ``attached_view_handler`` are accepted for API
    parity and exposed on the produced row so the host can use them if/when it
    supports factory-backed UItems. Nothing here raises on its own.
    """

    def __init__(self, custom_name: str=None, create_view_fn: Callable[[Any], View]=None,
                 bind_view_fn: Callable[[View], None]=None,
                 attached_view_handler: Callable[[View], None]=None,
                 equals_handler: Callable[[Any, Any], bool]=None,
                 content_equals_handler: Callable[[Any, Any], bool]=None,
                 is_clickable: bool=True, is_shadow: bool=False,
                 on_click_handler: Callable[[View], None]=None,
                 on_long_click_handler: Callable[[View], None]=None,
                 link_alias: str=None):
        self.custom_name = custom_name
        self.create_view_fn = create_view_fn
        self.bind_view_fn = bind_view_fn
        self.attached_view_handler = attached_view_handler
        self.equals_handler = equals_handler
        self.content_equals_handler = content_equals_handler
        self.is_clickable = is_clickable
        self.is_shadow = is_shadow
        self.on_click_handler = on_click_handler
        self.on_long_click_handler = on_long_click_handler
        self.link_alias = link_alias

    def create(self, *args, **kwargs) -> 'Custom':
        """Build a fresh settings row for this factory. Alias of ``__call__``."""
        return self.__call__(*args, **kwargs)

    def __call__(self, *args, **kwargs) -> 'Custom':
        factory = self

        def _create_view(context):
            if factory.create_view_fn is not None:
                try:
                    return factory.create_view_fn(context, *args, **kwargs)
                except TypeError:
                    return factory.create_view_fn(context)
            return None

        def _bind_view(view):
            if factory.bind_view_fn is not None:
                try:
                    factory.bind_view_fn(view, *args, **kwargs)
                except TypeError:
                    factory.bind_view_fn(view)

        row = Custom(
            create_view=_create_view,
            bind_view=_bind_view if factory.bind_view_fn is not None else None,
            on_click=factory.on_click_handler if factory.is_clickable else None,
            on_long_click=factory.on_long_click_handler,
            divider=not factory.is_shadow,
            link_alias=factory.link_alias,
        )
        
        row.custom_name = factory.custom_name
        row.attached_view_handler = factory.attached_view_handler
        row.equals_handler = factory.equals_handler
        row.content_equals_handler = factory.content_equals_handler
        row.is_clickable = factory.is_clickable
        row.is_shadow = factory.is_shadow
        return row