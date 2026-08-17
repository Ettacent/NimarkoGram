import os
import traceback
from typing import Any, Optional, Callable
from java.io import File
from java.lang import Object
from java.util import ArrayList, HashMap, List
from android.graphics import Bitmap, BitmapFactory
from android.media import MediaMetadataRetriever
from android.webkit import MimeTypeMap
from org.telegram.messenger import AccountInstance, AndroidUtilities, DispatchQueue, ImageLoader, MediaController, MessageObject, NotificationCenter, SendMessagesHelper, UserConfig, Utilities
from org.telegram.tgnet import RequestDelegate, TLObject
from org.telegram.ui import LaunchActivity
from android_utils import R as Runnable
from android_utils import log, run_on_ui_thread
from plugin_runtime import capture_callback_owner, make_interface_proxy
from app.nimarkogram.messenger.plugins.utils import (
    PythonNotificationDelegate,
    PythonRequestDelegate,
)
from java import jarray, jbyte, jint

try:
    from tl_compat import TLRPC
except Exception as _e:
    from org.telegram.tgnet import TLRPC
    log(f'tl_compat unavailable, using native TLRPC: {_e}')

STAGE_QUEUE = 'stageQueue'
GLOBAL_QUEUE = 'globalQueue'
CACHE_CLEAR_QUEUE = 'cacheClearQueue'
SEARCH_QUEUE = 'searchQueue'
PHONE_BOOK_QUEUE = 'phoneBookQueue'
THEME_QUEUE = 'themeQueue'
EXTERNAL_NETWORK_QUEUE = 'externalNetworkQueue'
PLUGINS_QUEUE = 'pluginsQueue'

def get_queue_by_name(queue_name: str) -> Optional[DispatchQueue]:
    try:
        field = Utilities.getClass().getField(queue_name)
        field.setAccessible(True)
        queue_instance = field.get(None)
        return queue_instance if isinstance(queue_instance, DispatchQueue) else None
    except Exception as e:
        log(f'Error getting queue \'{queue_name}\': {e}\n{traceback.format_exc()}')
        return None

def run_on_queue(fn: callable, queue_name: str=PLUGINS_QUEUE, delay: int=0):
    queue = get_queue_by_name(queue_name)
    if queue:
        try:
            queue.postRunnable(Runnable(fn), delay)
        except Exception as e:
            log(f'Error posting runnable to queue \'{queue_name}\': {e}\n{traceback.format_exc()}')

def RequestCallback(fn: callable):
    """Create a stale-safe Java RequestDelegate for this plugin runtime."""
    token = capture_callback_owner(fn)
    if token is None:
        raise RuntimeError('RequestCallback requires a plugin runtime')
    if isinstance(fn, RequestDelegate):
        return PythonRequestDelegate.fromDelegate(fn, token)
    return PythonRequestDelegate(fn, token)

def send_request(request: Any, fn: callable, account: Optional[int]=None) -> int:
    
    return get_connections_manager(account).sendRequest(
        request, RequestCallback(fn))

def observe_notifications(notification_center, fn: callable, *notification_ids):
    """Register a stale-safe, runtime-owned NotificationCenter observer."""
    token = capture_callback_owner(fn)
    if token is None:
        raise RuntimeError(
            'observe_notifications requires a plugin runtime')
    delegate = PythonNotificationDelegate(fn, token)
    ids = jarray(jint)([int(value) for value in notification_ids])
    if not delegate.register(notification_center, ids):
        delegate.unregister()
        return None
    return delegate

def get_last_fragment():
    return LaunchActivity.getSafeLastFragment()

def _resolve_account(account: Optional[int]=None) -> int:
    return UserConfig.selectedAccount if account is None else int(account)

def get_account_instance(account: Optional[int]=None):
    return AccountInstance.getInstance(_resolve_account(account))

def get_messages_controller(account: Optional[int]=None):
    return get_account_instance(account).getMessagesController()

def get_contacts_controller(account: Optional[int]=None):
    return get_account_instance(account).getContactsController()

def get_media_data_controller(account: Optional[int]=None):
    return get_account_instance(account).getMediaDataController()

def get_connections_manager(account: Optional[int]=None):
    return get_account_instance(account).getConnectionsManager()

def get_current_datacenter_id(account: Optional[int]=None) -> int:
    return get_connections_manager(account).getCurrentDatacenterId()

def get_location_controller(account: Optional[int]=None):
    return get_account_instance(account).getLocationController()

def get_notifications_controller(account: Optional[int]=None):
    return get_account_instance(account).getNotificationsController()

def get_messages_storage(account: Optional[int]=None):
    return get_account_instance(account).getMessagesStorage()

def get_send_messages_helper(account: Optional[int]=None):
    return get_account_instance(account).getSendMessagesHelper()

def get_file_loader(account: Optional[int]=None):
    return get_account_instance(account).getFileLoader()

def get_secret_chat_helper(account: Optional[int]=None):
    return get_account_instance(account).getSecretChatHelper()

def get_download_controller(account: Optional[int]=None):
    return get_account_instance(account).getDownloadController()

def get_notifications_settings(account: Optional[int]=None):
    return get_account_instance(account).getNotificationsSettings()

def get_notification_center(account: Optional[int]=None):
    return get_account_instance(account).getNotificationCenter()

def get_media_controller():
    return MediaController.getInstance()

def get_user_config(account: Optional[int]=None):
    return get_account_instance(account).getUserConfig()

def send_message(params: dict, account: Optional[int]=None):
    params = dict(params or {})
    if account is None:
        account = params.pop('account', None)
    else:
        params.pop('account', None)
    send_messages_helper = get_send_messages_helper(account)
    message_params = send_messages_helper.SendMessageParams()
    defaults = {'peer': 0, 'message': None, 'photo': None, 'document': None, 'videoEditedInfo': None, 'path': None, 'replyToMsg': None, 'replyToTopMsg': None, 'entities': None, 'replyMarkup': None, 'params': None, 'searchLinks': True, 'notify': True, 'updateStickersOrder': False, 'hasMediaSpoilers': False, 'invert_media': False, 'sendingHighQuality': False, 'scheduleDate': 0, 'scheduleRepeatPeriod': 0, 'ttl': 0, 'effect_id': 0, 'stars': 0, 'payStars': 0, 'monoForumPeer': None, 'quick_reply_shortcut_id': None, 'quick_reply_shortcut': None, 'parentObject': None, 'replyToStoryItem': None, 'sendingStory': None, 'replyQuote': None, 'suggestionParams': None}
    final_params = defaults.copy()
    final_params.update(params)

    def cast_to_array_list(py_list):
        if not py_list:
            return None
        else:
            java_list = ArrayList(len(py_list))
            for item in py_list:
                java_list.add(item)
            return java_list
    for key, value in final_params.items():
        try:
            if key == 'entities' and value is not None:
                setattr(message_params, key, cast_to_array_list(value))
            else:
                setattr(message_params, key, value)
        except Exception as e:
            log(f'Could not set parameter \'{key}\': {e}')
    run_on_ui_thread(lambda: send_messages_helper.sendMessage(message_params))

def _generate_photo_sizes(file_path: str, high_quality: bool=False,
                          account: Optional[int]=None) -> Optional[TLRPC.TL_photo]:
    try: 
        photo_size_limit = AndroidUtilities.getPhotoSize(high_quality)
        bitmap = ImageLoader.loadBitmap(file_path, None, photo_size_limit, photo_size_limit, True)
        
        if not bitmap:
            log(f'Failed to load bitmap from {file_path}')
            return None
            
        sizes = ArrayList()
        thumb_size = ImageLoader.scaleAndSaveImage(bitmap, 90, 90, 55, True)
        if thumb_size:
            sizes.add(thumb_size)
            
        if high_quality:
            main_size = ImageLoader.scaleAndSaveImage(None, bitmap, Bitmap.CompressFormat.JPEG, True, photo_size_limit, photo_size_limit, 99, False, 101, 101, False)
        else:
            main_size = ImageLoader.scaleAndSaveImage(bitmap, photo_size_limit, photo_size_limit, True, 80, False, 101, 101)
            
        if main_size:
            sizes.add(main_size)
            
        bitmap.recycle()
        
        if sizes.isEmpty():
            return None
            
        photo = TLRPC.TL_photo()
        photo.date = get_connections_manager(account).getCurrentTime()
        photo.sizes = sizes
        photo.file_reference = jarray(jbyte)(0) 
        return photo
        
    except Exception as e:
        log(f'Error generating photo sizes for \'{file_path}\': {e}\n{traceback.format_exc()}')
        return None

def _prepare_document(file_path: str, mime_type_str: Optional[str]=None,
                      account: Optional[int]=None) -> Optional[TLRPC.TL_document]:
    try:
        file = File(file_path)
        if not file.exists():
            log(f'File not found: {file_path}')
            return
        else:
            file_name = file.getName()
            if not mime_type_str:
                extension = ''
                dot_index = file_name.rfind('.')
                if dot_index!= (-1):
                    extension = file_name[dot_index + 1:]
                mime_type_str = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension.lower())
                if not mime_type_str:
                    mime_type_str = 'application/octet-stream'
            doc = TLRPC.TL_document()
            doc.id = 0
            doc.access_hash = 0
            doc.file_reference = jarray(jbyte)(0)
            doc.date = get_connections_manager(account).getCurrentTime()
            doc.mime_type = mime_type_str
            doc.size = file.length()
            doc.dc_id = 0
            attr_file_name = TLRPC.TL_documentAttributeFilename()
            attr_file_name.file_name = file_name
            doc.attributes = ArrayList()
            doc.attributes.add(attr_file_name)
            doc.thumbs = ArrayList()
            return doc
    except Exception as e:
        log(f'Error preparing document for \'{file_path}\': {e}')
        return None

def _add_video_attributes(document: TLRPC.TL_document, file_path: str):
    try:
        retriever = MediaMetadataRetriever()
        retriever.setDataSource(file_path)
        attr_video = TLRPC.TL_documentAttributeVideo()
        attr_video.w = int(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))
        attr_video.h = int(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT))
        attr_video.duration = int(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)) / 1000.0
        attr_video.supports_streaming = True
        document.attributes.add(attr_video)
        thumb_bitmap = retriever.getFrameAtTime()
        if thumb_bitmap:
            thumb_size = ImageLoader.scaleAndSaveImage(thumb_bitmap, 320, 320, 80, False)
            if thumb_size:
                document.thumbs.add(thumb_size)
            thumb_bitmap.recycle()
        retriever.release()
    except Exception as e:
        log(f'Could not get video metadata for \'{file_path}\': {e}')

def _add_audio_attributes(document: TLRPC.TL_document, file_path: str):
    try:
        retriever = MediaMetadataRetriever()
        retriever.setDataSource(file_path)
        attr_audio = TLRPC.TL_documentAttributeAudio()
        attr_audio.duration = int(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)) / 1000.0
        attr_audio.title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) or ''
        attr_audio.performer = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) or ''
        document.attributes.add(attr_audio)
        retriever.release()
    except Exception as e:
        log(f'Could not get audio metadata for \'{file_path}\': {e}')

def send_text(peer: int, text: str, account: Optional[int]=None, **kwargs):
    params = {'peer': peer, 'message': text}
    params.update(kwargs)
    send_message(params, account)

def send_photo(peer: int, file_path: str, caption: str='', high_quality: bool=False,
               account: Optional[int]=None, **kwargs):
    photo = _generate_photo_sizes(file_path, high_quality, account)
    if not photo:
        log(f'Failed to create photo object for {file_path}')
        return None
    else:
        params = {'peer': peer, 'photo': photo, 'path': file_path, 'caption': caption, 'sendingHighQuality': high_quality}
        params.update(kwargs)
        send_message(params, account)

def send_document(peer: int, file_path: str, caption: str='',
                  account: Optional[int]=None, **kwargs):
    try:
        _sd_exists = os.path.exists(file_path) if file_path else False
        _sd_size = os.path.getsize(file_path) if file_path and _sd_exists else 0
        log(f"nimarko-py: send_document called peer={peer} file_path={file_path} exists={_sd_exists} size={_sd_size}")
    except Exception as _e:
        log(f"nimarko-py: send_document called peer={peer} file_path={file_path} (stat err={_e})")
    document = _prepare_document(file_path, account=account)
    if not document:
        log(f'Failed to prepare document for {file_path}')
        return None
    else:
        params = {'peer': peer, 'document': document, 'path': file_path, 'caption': caption}
        params.update(kwargs)
        send_message(params, account)

def send_video(peer: int, file_path: str, caption: str='',
               account: Optional[int]=None, **kwargs):
    document = _prepare_document(file_path, 'video/mp4', account)
    if not document:
        log(f'Failed to prepare video document for {file_path}')
        return None
    else:
        _add_video_attributes(document, file_path)
        params = {'peer': peer, 'document': document, 'path': file_path, 'caption': caption}
        params.update(kwargs)
        send_message(params, account)

def send_audio(peer: int, file_path: str, caption: str='',
               account: Optional[int]=None, **kwargs):
    document = _prepare_document(file_path, 'audio/mpeg', account)
    if not document:
        log(f'Failed to prepare audio document for {file_path}')
        return None
    else:
        _add_audio_attributes(document, file_path)
        params = {'peer': peer, 'document': document, 'path': file_path, 'caption': caption}
        params.update(kwargs)
        send_message(params, account)

def edit_message(message_obj: Any, text: Optional[str]=None, file_path: Optional[str]=None,
                 with_spoiler: bool=False, account: Optional[int]=None, **kwargs):
    if not isinstance(message_obj, MessageObject):
        log('\'message_obj\' must be a valid MessageObject instance.')
        return None
    else:
        if text is None and file_path is None:
            log('edit_message called with no changes (text or file_path). Cancelled.')
            return None
        else:
            if file_path and (not os.path.exists(file_path)):
                log(f'Media file not found at path: {file_path}')
                return None
            else:
                if account is None:
                    try:
                        account = int(message_obj.currentAccount)
                    except Exception:
                        pass
                send_helper = get_send_messages_helper(account)
                if text is not None:
                    message_obj.editingMessage = text
                    message_obj.editingMessageEntities = None
                photo = None
                document = None
                video_info = None
                cover = None
                if file_path:
                    ext = os.path.splitext(file_path)[1].lower()
                    if ext in ['.jpg', '.jpeg', '.png', '.webp']:
                        photo = _generate_photo_sizes(file_path, account=account)
                        if not photo:
                            log(f'Failed to generate photo sizes for: {file_path}')
                            return
                    else:
                        if ext in ['.mp4', '.mov', '.mkv']:
                            document = _prepare_document(file_path, 'video/mp4', account)
                            if not document:
                                log(f'Failed to prepare document for video: {file_path}')
                                return None
                            else:
                                _add_video_attributes(document, file_path)
                        else:
                            if ext in ['.mp3', '.m4a', '.ogg', '.opus', '.flac']:
                                document = _prepare_document(file_path, account=account)
                                if not document:
                                    log(f'Failed to prepare document for audio: {file_path}')
                                    return None
                                else:
                                    _add_audio_attributes(document, file_path)
                            else:
                                document = _prepare_document(file_path, account=account)
                                if not document:
                                    log(f'Failed to prepare document for file: {file_path}')
                                    return None
                java_params = HashMap()
                for key, value in kwargs.items():
                    java_params.put(str(key), str(value))
                try:
                    run_on_ui_thread(lambda: send_helper.editMessage(message_obj, photo, video_info, document, file_path, cover, java_params, False, with_spoiler, None))
                    log(f'Edit request sent for message {message_obj.getId()} in dialog {message_obj.getDialogId()}.')
                except Exception as e:
                    log(f'An error occurred while calling editMessage: {e}\n{traceback.format_exc()}')

class _NotificationCenterDelegateMeta(type):
    """Compatibility constructor which never publishes a Chaquopy proxy."""

    def __call__(cls, *args, **kwargs):
        target = super().__call__(*args, **kwargs)
        callback = getattr(target, 'didReceivedNotification', None)
        token = capture_callback_owner(callback)
        if token is None:
            raise RuntimeError(
                'NotificationCenterDelegate requires a plugin runtime')
        target._nimarko_runtime_token = token
        return make_interface_proxy(
            target,
            (NotificationCenter.NotificationCenterDelegate,),
            owner=token)

class NotificationCenterDelegate(
        metaclass=_NotificationCenterDelegateMeta):
    """Legacy subclass API backed by a revocable Java invocation handler."""

    def didReceivedNotification(self, id, account, args):
        return None
