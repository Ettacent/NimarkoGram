from typing import Callable, Optional, Tuple

from android_utils import R as Runnable
from org.telegram.messenger import LocaleController, R
from org.telegram.ui.ActionBar import BaseFragment, Theme
from org.telegram.ui.Components import Bulletin, BulletinFactory

from plugin_ui import (
    capture_ui_owner,
    get_usable_fragment,
    post_owned_ui,
    show_bulletin,
)

class BulletinHelper:
    """Main-thread, runtime-owned wrappers around BulletinFactory."""

    DURATION_SHORT = 1500
    DURATION_LONG = 2750
    DURATION_PROLONG = 5000

    @classmethod
    def _get_factory_and_provider(
            cls,
            runtime_token,
            fragment: Optional[BaseFragment] = None
    ) -> Tuple[
            Optional[BulletinFactory],
            Optional[Theme.ResourcesProvider]]:
        safe_fragment = get_usable_fragment(runtime_token, fragment)
        if safe_fragment is None:
            return None, None
        provider = safe_fragment.getResourceProvider()
        visible_dialog = safe_fragment.visibleDialog
        if (
                visible_dialog is not None
                and isinstance(visible_dialog, Bulletin.BottomSheet)
                and visible_dialog.container is not None
                and visible_dialog.container.isAttachedToWindow()):
            return (
                BulletinFactory.of(visible_dialog.container, provider),
                provider,
            )
        return BulletinFactory.of(safe_fragment), provider

    @classmethod
    def _show(
            cls,
            fragment: Optional[BaseFragment],
            create_bulletin: Callable[
                [BulletinFactory, Optional[Theme.ResourcesProvider]],
                Optional[Bulletin]
            ]):
        runtime_token = capture_ui_owner()

        def _task():
            
            factory, provider = cls._get_factory_and_provider(
                runtime_token, fragment
            )
            if factory is None:
                return
            bulletin = create_bulletin(factory, provider)
            if bulletin is None or isinstance(
                    bulletin, Bulletin.EmptyBulletin):
                return
            
            show_bulletin(runtime_token, bulletin)

        post_owned_ui(runtime_token, _task)

    @classmethod
    def show_info(
            cls,
            message: str,
            fragment: Optional[BaseFragment] = None):
        cls._show(
            fragment,
            lambda factory, _provider:
                factory.createSimpleBulletin(R.raw.info, message),
        )

    @classmethod
    def show_error(
            cls,
            message: str,
            fragment: Optional[BaseFragment] = None):
        """Shows a short bulletin or a selectable dialog for technical text."""
        msg = str(message) if message is not None else ""
        low = msg.lower()
        technical = (
            len(msg) > 120
            or "exception" in low
            or "landroid" in low
            or "ljava" in low
            or "non-static" in low
            or "nosuchmethod" in low
            or "<init>" in low
            or "java." in low
            or "stack trace" in low
        )
        if technical:
            cls._show_error_dialog(msg, fragment)
            return
        cls._show(
            fragment,
            lambda factory, _provider:
                factory.createErrorBulletin(msg),
        )

    @classmethod
    def _show_error_dialog(
            cls,
            message: str,
            fragment: Optional[BaseFragment] = None):
        """Shows long error text without ever using ApplicationContext as a window."""
        runtime_token = capture_ui_owner()

        def _task():
            try:
                from android.content import ClipData, Context
                from android.widget import ScrollView, TextView
                from org.telegram.messenger import AndroidUtilities
                from ui.alert import AlertDialogBuilder

                safe_fragment = get_usable_fragment(runtime_token, fragment)
                if safe_fragment is None:
                    return
                context = safe_fragment.getParentActivity()
                if context is None:
                    return

                text_view = TextView(context)
                text_view.setText(message)
                text_view.setTextSize(14.0)
                text_view.setTextIsSelectable(True)
                padding = AndroidUtilities.dp(20)
                text_view.setPadding(
                    padding,
                    AndroidUtilities.dp(8),
                    padding,
                    AndroidUtilities.dp(8),
                )

                scroll_view = ScrollView(context)
                scroll_view.addView(text_view)

                def _copy(_builder, _which):
                    clipboard = context.getSystemService(
                        Context.CLIPBOARD_SERVICE
                    )
                    if clipboard is not None:
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText("plugin_error", message)
                        )
                        cls.show_info("Скопировано", safe_fragment)

                (
                    AlertDialogBuilder(context)
                    .set_title("Ошибка плагина")
                    .set_view(scroll_view)
                    .set_positive_button("Копировать", _copy)
                    .set_negative_button("OK")
                    .show()
                )
            except Exception:
                
                factory, _provider = cls._get_factory_and_provider(
                    runtime_token, fragment
                )
                if factory is not None:
                    bulletin = factory.createErrorBulletin(message)
                    show_bulletin(runtime_token, bulletin)

        post_owned_ui(runtime_token, _task)

    @classmethod
    def show_success(
            cls,
            message: str,
            fragment: Optional[BaseFragment] = None):
        cls._show(
            fragment,
            lambda factory, _provider:
                factory.createSuccessBulletin(message),
        )

    @classmethod
    def show_simple(
            cls,
            text: str,
            icon_res_id: int,
            fragment: Optional[BaseFragment] = None):
        cls._show(
            fragment,
            lambda factory, _provider:
                factory.createSimpleBulletin(icon_res_id, text),
        )

    @classmethod
    def show_two_line(
            cls,
            title: str,
            subtitle: str,
            icon_res_id: int,
            fragment: Optional[BaseFragment] = None):
        cls._show(
            fragment,
            lambda factory, _provider:
                factory.createSimpleBulletin(icon_res_id, title, subtitle),
        )

    @classmethod
    def show_with_button(
            cls,
            text: str,
            icon_res_id: int,
            button_text: str,
            on_click: Optional[Callable[[], None]],
            fragment: Optional[BaseFragment] = None,
            duration: int = DURATION_PROLONG):
        def _create(factory, _provider):
            runnable = Runnable(on_click) if on_click else None
            return factory.createSimpleBulletin(
                icon_res_id,
                text,
                button_text,
                duration,
                runnable,
            )

        cls._show(fragment, _create)

    @classmethod
    def show_undo(
            cls,
            text: str,
            on_undo: Callable[[], None],
            on_action: Optional[Callable[[], None]] = None,
            subtitle: Optional[str] = None,
            fragment: Optional[BaseFragment] = None):
        def _create(factory, _provider):
            undo_runnable = Runnable(on_undo)
            action_runnable = Runnable(on_action) if on_action else None
            if subtitle:
                return factory.createUndoBulletin(
                    text,
                    subtitle,
                    undo_runnable,
                    action_runnable,
                )
            return factory.createUndoBulletin(
                text,
                undo_runnable,
                action_runnable,
            )

        cls._show(fragment, _create)

    @classmethod
    def show_copied_to_clipboard(
            cls,
            message: Optional[str] = None,
            fragment: Optional[BaseFragment] = None):
        display_message = message
        if display_message is None:
            display_message = LocaleController.getString(
                'TextCopied', R.string.TextCopied
            )
        cls._show(
            fragment,
            lambda factory, provider:
                factory.createCopyBulletin(display_message, provider),
        )

    @classmethod
    def show_link_copied(
            cls,
            is_private_link_info: bool = False,
            fragment: Optional[BaseFragment] = None):
        cls._show(
            fragment,
            lambda factory, _provider:
                factory.createCopyLinkBulletin(is_private_link_info),
        )

    @classmethod
    def show_file_saved_to_gallery(
            cls,
            is_video: bool = False,
            amount: int = 1,
            fragment: Optional[BaseFragment] = None):
        def _create(factory, provider):
            if is_video:
                file_type = (
                    BulletinFactory.FileType.VIDEOS
                    if amount > 1
                    else BulletinFactory.FileType.VIDEO
                )
            else:
                file_type = (
                    BulletinFactory.FileType.PHOTOS
                    if amount > 1
                    else BulletinFactory.FileType.PHOTO
                )
            return factory.createDownloadBulletin(
                file_type, amount, provider
            )

        cls._show(fragment, _create)

    @classmethod
    def show_file_saved_to_downloads(
            cls,
            file_type_enum_name: str = 'UNKNOWN',
            amount: int = 1,
            fragment: Optional[BaseFragment] = None):
        def _create(factory, provider):
            file_types = BulletinFactory.FileType
            try:
                file_type = getattr(
                    file_types, file_type_enum_name.upper()
                )
                if amount > 1:
                    plural_name = file_type.name() + 'S'
                    if (
                            hasattr(file_types, plural_name)
                            and file_type.getText(2) != file_type.getText(1)):
                        file_type = getattr(file_types, plural_name)
            except AttributeError:
                file_type = (
                    file_types.UNKNOWNS
                    if amount > 1
                    else file_types.UNKNOWN
                )
            return factory.createDownloadBulletin(
                file_type, amount, provider
            )

        cls._show(fragment, _create)
