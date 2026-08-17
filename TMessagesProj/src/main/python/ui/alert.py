from typing import Callable, Dict, List, Optional

from android.content import Context
from android.graphics.drawable import Drawable
from android.view import View
from android.widget import TextView
from java import cast
from org.telegram.ui.ActionBar import AlertDialog, Theme

from android_utils import is_on_ui_thread
from app.nimarkogram.messenger.plugins.ui import (
    PluginDialogCallback,
    PluginUiRegistry,
)
from plugin_ui import capture_ui_owner, post_owned_ui

_UNSET = object()

class _AlertDialogFacade:
    """Compatibility surface which never exposes the host-owned Dialog."""

    __slots__ = ('__builder',)

    def __init__(self, builder: 'AlertDialogBuilder'):
        self.__builder = builder

    def show(self) -> '_AlertDialogFacade':
        self.__builder.show()
        return self

    def dismiss(self):
        self.__builder.dismiss()

    def cancel(self):
        self.__builder.cancel()

    def is_showing(self) -> bool:
        dialog = self.__builder._alert_dialog
        return bool(
            dialog is not None
            and PluginUiRegistry.isRuntimeCurrent(
                self.__builder._runtime_token)
            and dialog.isShowing()
        )

    def isShowing(self) -> bool:
        return self.is_showing()

    def get_button(self, button_type: int) -> Optional[View]:
        return self.__builder.get_button(button_type)

    def getButton(self, button_type: int) -> Optional[View]:
        return self.get_button(button_type)

    def get_context(self) -> Context:
        return self.__builder.get_context()

    def getContext(self) -> Context:
        return self.get_context()

    def set_cancelable(self, cancelable: bool) -> '_AlertDialogFacade':
        self.__builder.set_cancelable(cancelable)
        return self

    def setCancelable(self, cancelable: bool) -> '_AlertDialogFacade':
        return self.set_cancelable(cancelable)

    def set_canceled_on_touch_outside(
            self, cancel: bool) -> '_AlertDialogFacade':
        self.__builder.set_canceled_on_touch_outside(cancel)
        return self

    def setCanceledOnTouchOutside(
            self, cancel: bool) -> '_AlertDialogFacade':
        return self.set_canceled_on_touch_outside(cancel)

    def set_progress(self, progress: int) -> '_AlertDialogFacade':
        self.__builder.set_progress(progress)
        return self

    def setProgress(self, progress: int) -> '_AlertDialogFacade':
        return self.set_progress(progress)

    def __getattr__(self, name):
        raise AttributeError(
            "AlertDialogBuilder.get_dialog() exposes a lifecycle-safe facade; "
            f"raw AlertDialog operation {name!r} is unavailable"
        )

class AlertDialogBuilder:
    ALERT_TYPE_MESSAGE = 0
    ALERT_TYPE_LOADING = 2
    ALERT_TYPE_SPINNER = 3
    BUTTON_POSITIVE = -1
    BUTTON_NEGATIVE = -2
    BUTTON_NEUTRAL = -3

    def __init__(
            self,
            context: Context,
            progress_style: int = ALERT_TYPE_MESSAGE,
            resources_provider: Optional[Theme.ResourcesProvider] = None):
        
        self._context = context
        self._progress_style = progress_style
        self._resources_provider = resources_provider
        self._runtime_token = capture_ui_owner()
        self._java_builder = None
        self._alert_dialog = None
        self._dialog_facade = _AlertDialogFacade(self)
        self._red_buttons = []
        self._ui_request_id = 0
        self._dismissed_through_id = 0
        self._shown_request_id = 0

        self._title = _UNSET
        self._message = _UNSET
        self._message_clickable = _UNSET
        self._positive_button = _UNSET
        self._negative_button = _UNSET
        self._neutral_button = _UNSET
        self._back_listener = _UNSET
        self._view = _UNSET
        self._items = _UNSET
        self._dismiss_listener = _UNSET
        self._cancel_listener = _UNSET
        self._top_image = _UNSET
        self._top_drawable = _UNSET
        self._top_animation = _UNSET
        self._top_animation_is_new = _UNSET
        self._dim_enabled = _UNSET
        self._button_color_key = _UNSET
        self._blurred_background = _UNSET
        self._cancelable = _UNSET
        self._cancel_on_touch_outside = _UNSET
        self._progress = _UNSET

    def get_context(self) -> Context:
        """Returns the Activity-backed context used by this builder."""
        return self._context

    def set_title(self, title: str) -> 'AlertDialogBuilder':
        self._title = title
        return self

    def set_message(self, message: str) -> 'AlertDialogBuilder':
        self._message = message
        return self

    def set_message_text_view_clickable(
            self, clickable: bool) -> 'AlertDialogBuilder':
        self._message_clickable = clickable
        return self

    def set_positive_button(
            self,
            text: str,
            listener: Optional[
                Callable[['AlertDialogBuilder', int], None]
            ] = None) -> 'AlertDialogBuilder':
        self._positive_button = (text, listener)
        return self

    def set_negative_button(
            self,
            text: str,
            listener: Optional[
                Callable[['AlertDialogBuilder', int], None]
            ] = None) -> 'AlertDialogBuilder':
        self._negative_button = (text, listener)
        return self

    def set_neutral_button(
            self,
            text: str,
            listener: Optional[
                Callable[['AlertDialogBuilder', int], None]
            ] = None) -> 'AlertDialogBuilder':
        self._neutral_button = (text, listener)
        return self

    def make_button_red(self, button_type: int) -> 'AlertDialogBuilder':
        """Marks a positive, negative or neutral button as destructive."""
        if button_type not in self._red_buttons:
            self._red_buttons.append(button_type)
        return self

    def set_on_back_button_listener(
            self,
            listener: Optional[
                Callable[['AlertDialogBuilder', int], None]
            ] = None) -> 'AlertDialogBuilder':
        self._back_listener = listener
        return self

    def set_view(
            self, view: View, height: int = -2) -> 'AlertDialogBuilder':
        self._view = (view, height)
        return self

    def set_items(
            self,
            items: List[str],
            listener: Optional[
                Callable[['AlertDialogBuilder', int], None]
            ] = None,
            icons: Optional[List[int]] = None) -> 'AlertDialogBuilder':
        self._items = (items, listener, icons)
        return self

    def set_on_dismiss_listener(
            self,
            listener: Optional[
                Callable[['AlertDialogBuilder'], None]
            ] = None) -> 'AlertDialogBuilder':
        self._dismiss_listener = listener
        return self

    def set_on_cancel_listener(
            self,
            listener: Optional[
                Callable[['AlertDialogBuilder'], None]
            ] = None) -> 'AlertDialogBuilder':
        self._cancel_listener = listener
        return self

    def set_top_image(
            self, res_id: int, background_color: int) -> 'AlertDialogBuilder':
        self._top_image = (res_id, background_color)
        return self

    def set_top_drawable(
            self,
            drawable: Drawable,
            background_color: int) -> 'AlertDialogBuilder':
        self._top_drawable = (drawable, background_color)
        return self

    def set_top_animation(
            self,
            res_id: int,
            size: int,
            auto_repeat: bool,
            background_color: int,
            layer_colors: Optional[Dict[str, int]] = None
    ) -> 'AlertDialogBuilder':
        self._top_animation = (
            res_id, size, auto_repeat, background_color, layer_colors
        )
        return self

    def set_top_animation_is_new(
            self, is_new: bool) -> 'AlertDialogBuilder':
        self._top_animation_is_new = is_new
        return self

    def set_dim_enabled(self, enabled: bool) -> 'AlertDialogBuilder':
        self._dim_enabled = enabled
        return self

    def set_dialog_button_color_key(
            self, theme_key: int) -> 'AlertDialogBuilder':
        self._button_color_key = theme_key
        return self

    def set_blurred_background(
            self,
            blur: bool,
            blur_behind_if_possible: bool = True) -> 'AlertDialogBuilder':
        self._blurred_background = (blur, blur_behind_if_possible)
        if self._alert_dialog is not None:
            post_owned_ui(self._runtime_token, self._apply_blur_on_ui)
        return self

    def set_cancelable(self, cancelable: bool) -> 'AlertDialogBuilder':
        self._cancelable = cancelable
        if self._alert_dialog is not None:
            post_owned_ui(self._runtime_token, self._apply_dialog_flags_on_ui)
        return self

    def set_canceled_on_touch_outside(
            self, cancel: bool) -> 'AlertDialogBuilder':
        self._cancel_on_touch_outside = cancel
        if self._alert_dialog is not None:
            post_owned_ui(self._runtime_token, self._apply_dialog_flags_on_ui)
        return self

    def set_progress(self, progress: int) -> 'AlertDialogBuilder':
        """Sets progress for ALERT_TYPE_LOADING once the dialog exists."""
        self._progress = progress
        if self._alert_dialog is not None:
            post_owned_ui(self._runtime_token, self._apply_progress_on_ui)
        return self

    def create(self) -> 'AlertDialogBuilder':
        """Creates the dialog on main without showing it."""
        request_id = self._begin_ui_request()
        if not is_on_ui_thread():
            post_owned_ui(
                self._runtime_token,
                lambda: self._create_on_ui(request_id),
            )
            return self
        return self._create_on_ui(request_id)

    def show(self) -> 'AlertDialogBuilder':
        """Creates and shows the dialog on main if its exact owner is current."""
        request_id = self._begin_ui_request()
        if not is_on_ui_thread():
            post_owned_ui(
                self._runtime_token,
                lambda: self._show_on_ui(request_id),
            )
            return self
        return self._show_on_ui(request_id)

    def dismiss(self):
        """Requests host-owned, frame-safe dismissal from any thread."""
        
        self._ui_request_id += 1
        self._dismissed_through_id = self._ui_request_id
        dialog = self._alert_dialog
        if dialog is self._alert_dialog:
            
            self._alert_dialog = None
            self._java_builder = None
            self._shown_request_id = 0
        if dialog is not None:
            PluginUiRegistry.dismissDialog(dialog)

    def cancel(self):
        """Requests host-owned, frame-safe cancellation from any thread."""
        self._ui_request_id += 1
        self._dismissed_through_id = self._ui_request_id
        dialog = self._alert_dialog
        if dialog is self._alert_dialog:
            self._alert_dialog = None
            self._java_builder = None
            self._shown_request_id = 0
        if dialog is not None:
            PluginUiRegistry.cancelDialog(dialog)

    def get_dialog(self) -> Optional[_AlertDialogFacade]:
        """Returns a safe compatibility facade once the dialog is created."""
        if self._alert_dialog is None:
            return None
        return self._dialog_facade

    def get_button(self, button_type: int) -> Optional[View]:
        """Returns a button only when called on main for a current runtime."""
        if (
                is_on_ui_thread()
                and self._alert_dialog is not None
                and PluginUiRegistry.isRuntimeCurrent(self._runtime_token)):
            return self._alert_dialog.getButton(button_type)
        return None

    def _begin_ui_request(self) -> int:
        self._ui_request_id += 1
        return self._ui_request_id

    def _is_ui_request_current(self, request_id: int) -> bool:
        return request_id > self._dismissed_through_id

    def _on_dialog_dismissed(self, dialog):
        
        if dialog != self._alert_dialog:
            return
        self._alert_dialog = None
        self._java_builder = None
        self._shown_request_id = 0
        
        self._dismissed_through_id = max(
            self._dismissed_through_id,
            self._ui_request_id,
        )

    def _on_dialog_shown(self, dialog):
        """Host callback after the frame-safe window attachment completes."""
        if (
                dialog != self._alert_dialog
                or not PluginUiRegistry.isRuntimeCurrent(
                    self._runtime_token)):
            return
        self._apply_red_buttons_on_ui()

    def _ensure_java_builder_on_ui(self, request_id: int):
        if self._java_builder is not None:
            return self._java_builder
        if not self._is_ui_request_current(request_id):
            return None
        if not PluginUiRegistry.canCreateDialog(
                self._runtime_token, self._context):
            return None

        builder = AlertDialog.Builder(
            self._context, self._progress_style, self._resources_provider
        )
        if self._title is not _UNSET:
            builder.setTitle(self._title)
        if self._message is not _UNSET:
            builder.setMessage(self._message)
        if self._message_clickable is not _UNSET:
            builder.setMessageTextViewClickable(self._message_clickable)
        if self._positive_button is not _UNSET:
            text, listener = self._positive_button
            proxy = (
                PluginDialogCallback(
                    PluginDialogCallback.TYPE_BUTTON,
                    listener,
                    self,
                    self._runtime_token,
                )
                if listener else None
            )
            builder.setPositiveButton(text, proxy)
        if self._negative_button is not _UNSET:
            text, listener = self._negative_button
            proxy = (
                PluginDialogCallback(
                    PluginDialogCallback.TYPE_BUTTON,
                    listener,
                    self,
                    self._runtime_token,
                )
                if listener else None
            )
            builder.setNegativeButton(text, proxy)
        if self._neutral_button is not _UNSET:
            text, listener = self._neutral_button
            proxy = (
                PluginDialogCallback(
                    PluginDialogCallback.TYPE_BUTTON,
                    listener,
                    self,
                    self._runtime_token,
                )
                if listener else None
            )
            builder.setNeutralButton(text, proxy)
        if self._back_listener is not _UNSET:
            proxy = (
                PluginDialogCallback(
                    PluginDialogCallback.TYPE_BUTTON,
                    self._back_listener,
                    self,
                    self._runtime_token,
                )
                if self._back_listener else None
            )
            builder.setOnBackButtonListener(proxy)
        if self._view is not _UNSET:
            view, height = self._view
            if not PluginUiRegistry.canAttachPluginView(
                    self._runtime_token, view):
                return None
            builder.setView(view, height)
        if self._items is not _UNSET:
            items, listener, icons = self._items
            proxy = (
                PluginDialogCallback(
                    PluginDialogCallback.TYPE_ITEMS,
                    listener,
                    self,
                    self._runtime_token,
                )
                if listener else None
            )
            if icons:
                builder.setItems(items, icons, proxy)
            else:
                builder.setItems(items, proxy)
        dismiss_callback = (
            None
            if self._dismiss_listener is _UNSET
            else self._dismiss_listener
        )
        
        builder.setOnDismissListener(
            PluginDialogCallback(
                PluginDialogCallback.TYPE_DISMISS,
                dismiss_callback,
                self,
                self._runtime_token,
            )
        )
        if self._cancel_listener is not _UNSET:
            proxy = (
                PluginDialogCallback(
                    PluginDialogCallback.TYPE_CANCEL,
                    self._cancel_listener,
                    self,
                    self._runtime_token,
                )
                if self._cancel_listener else None
            )
            builder.setOnCancelListener(proxy)
        if self._top_image is not _UNSET:
            builder.setTopImage(*self._top_image)
        if self._top_drawable is not _UNSET:
            builder.setTopImage(*self._top_drawable)
        if self._top_animation is not _UNSET:
            builder.setTopAnimation(*self._top_animation)
        if self._top_animation_is_new is not _UNSET:
            builder.setTopAnimationIsNew(self._top_animation_is_new)
        if self._dim_enabled is not _UNSET:
            builder.setDimEnabled(self._dim_enabled)
        if self._button_color_key is not _UNSET:
            builder.setDialogButtonColorKey(self._button_color_key)
        if self._blurred_background is not _UNSET:
            builder.setBlurredBackground(self._blurred_background[0])

        self._java_builder = builder
        return builder

    def _create_on_ui(self, request_id: int) -> 'AlertDialogBuilder':
        if (
                not PluginUiRegistry.isMainThread()
                or not self._is_ui_request_current(request_id)):
            return self
        if self._alert_dialog is not None:
            return self
        builder = self._ensure_java_builder_on_ui(request_id)
        if builder is None:
            return self
        
        dialog = builder.create()
        if not PluginUiRegistry.registerDialog(
                self._runtime_token, dialog, True):
            return self
        dialog.setOnShowListener(
            PluginDialogCallback(
                PluginDialogCallback.TYPE_SHOW,
                None,
                self,
                self._runtime_token,
            )
        )
        self._alert_dialog = dialog
        self._apply_dialog_flags_on_ui()
        self._apply_blur_on_ui()
        self._apply_progress_on_ui()
        return self

    def _show_on_ui(self, request_id: int) -> 'AlertDialogBuilder':
        if (
                not PluginUiRegistry.isMainThread()
                or not self._is_ui_request_current(request_id)):
            return self
        if self._alert_dialog is None:
            self._create_on_ui(request_id)
        if not self._is_ui_request_current(request_id):
            return self
        dialog = self._alert_dialog
        if dialog is None:
            return self
        
        if PluginUiRegistry.showDialog(self._runtime_token, dialog, True):
            self._shown_request_id = request_id
        return self

    def _apply_red_buttons_on_ui(self):
        dialog = self._alert_dialog
        if dialog is None or not dialog.isShowing():
            return
        for button_type in self._red_buttons:
            button_view = dialog.getButton(button_type)
            if button_view and isinstance(button_view, TextView):
                cast(TextView, button_view).setTextColor(
                    dialog.getThemedColor(Theme.key_text_RedBold)
                )

    def _apply_blur_on_ui(self):
        dialog = self._alert_dialog
        if (
                dialog is not None
                and self._blurred_background is not _UNSET
                and PluginUiRegistry.isRuntimeCurrent(self._runtime_token)):
            blur, blur_behind_if_possible = self._blurred_background
            if blur:
                dialog.setBlurParams(0.8, blur_behind_if_possible, blur)

    def _apply_dialog_flags_on_ui(self):
        dialog = self._alert_dialog
        if (
                dialog is None
                or not PluginUiRegistry.isRuntimeCurrent(self._runtime_token)):
            return
        if self._cancelable is not _UNSET:
            dialog.setCancelable(self._cancelable)
        if self._cancel_on_touch_outside is not _UNSET:
            dialog.setCanceledOnTouchOutside(self._cancel_on_touch_outside)

    def _apply_progress_on_ui(self):
        dialog = self._alert_dialog
        if (
                dialog is not None
                and self._progress is not _UNSET
                and self._progress_style == self.ALERT_TYPE_LOADING
                and PluginUiRegistry.isRuntimeCurrent(self._runtime_token)):
            dialog.setProgress(self._progress)
