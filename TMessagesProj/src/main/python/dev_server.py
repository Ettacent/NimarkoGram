import enum
import json
import os
import shutil
import socket
import sys
import tempfile
import threading
from typing import Dict, Any, Optional
import traceback
from app.nimarkogram.messenger.plugins import PluginsController, Plugin
from org.telegram.messenger import FileLoader
from android_utils import log
from base_plugin import BasePlugin, AppEvent

class DebuggerPlatform(enum.Enum):
    PyCharm = 'pycharm'
    VSCode = 'vscode'

class _ServerContext:
    """Identity of one exact Python + Java dev-server generation."""

    def __init__(self, bridge, generation: int, stop_event: threading.Event):
        self.bridge = bridge
        self.generation = generation
        self.stop_event = stop_event
        self.ready_event = threading.Event()
        self.start_error: Optional[str] = None

    def is_live(self) -> bool:
        return DevServer._context_is_live(self)

class DebuggerEventListener(BasePlugin):
    def __init__(self, context: _ServerContext, host: str, port: int,
                 platform: DebuggerPlatform):
        super().__init__()
        self.id = 'debugger_event_listener'
        self.name = 'Debugger Event Listener'
        self.enabled = True
        self.initialized = True
        self.host = host
        self.port = port
        self.platform = platform
        self.context = context

    def on_app_event(self, event_type: str):
        if not self.context.is_live():
            return None
        if self.platform != DebuggerPlatform.PyCharm:
            return None
        if event_type == AppEvent.PAUSE and self.context.is_live():
            DevServer.stop_remote_debugging(self.platform)
        elif event_type == AppEvent.RESUME and self.context.is_live():
            DevServer.setup_remote_debugging(
                self.host, self.port, self.platform)

class DevServer:
    DEFAULT_HOST = '127.0.0.1'
    DEFAULT_PORT = 42690
    SOCKET_TIMEOUT = 600
    BUFFER_SIZE = 4096
    MAX_REQUEST_BYTES = 1024 * 1024
    MAX_COMMANDS_PER_CONNECTION = 128
    PYCHARM_DEBUGGER_DIR = os.path.join(FileLoader.getDirectory(FileLoader.MEDIA_DIR_FILES).getAbsolutePath(), 'debugger', 'pydevd-pycharm.egg')
    _server_thread: Optional[threading.Thread] = None
    _server_socket: Optional[socket.socket] = None
    _is_running = False
    _active_debugging = False
    _active_platform = None
    _state_lock = threading.RLock()
    _stop_event: Optional[threading.Event] = None
    _generation = 0
    _active_context: Optional[_ServerContext] = None
    _client_sockets = {}

    @classmethod
    def start_server(cls, server_bridge, host: str=None,
                     port: int=None) -> bool:
        """Run the server on the exact Java-owned calling thread.

        Java creates and pins this physical thread before entering Python.
        Python must not nominate another privileged thread or retain the
        authentication secret.
        """
        if server_bridge is None or not server_bridge.isActive():
            raise RuntimeError(
                'Development server requires an active Java host bridge')
        
        host = cls.DEFAULT_HOST
        if port is None:
            port = cls.DEFAULT_PORT
        current_thread = threading.current_thread()
        with cls._state_lock:
            old_thread = cls._server_thread
            if (old_thread is not None
                    and old_thread is not current_thread
                    and old_thread.is_alive()):
                raise RuntimeError(
                    'Previous development-server thread is still alive')
            cls._generation += 1
            generation = cls._generation
            stop_event = threading.Event()
            context = _ServerContext(server_bridge, generation, stop_event)
            cls._stop_event = stop_event
            cls._active_context = context
            cls._is_running = True
            cls._server_thread = current_thread
        cls._server_thread_function(host, int(port), context)
        if context.start_error:
            raise RuntimeError(context.start_error)
        return True

    @classmethod
    def stop_server(cls) -> bool:
        with cls._state_lock:
            thread = cls._server_thread
            if (not cls._is_running
                    and not (thread and thread.is_alive())):
                log('Server not running')
                return True
            cls._is_running = False
            stop_event = cls._stop_event
            server_socket = cls._server_socket
            context = cls._active_context
            client_sockets = tuple(
                client_socket
                for client_socket, owner in cls._client_sockets.items()
                if owner is context)
            cls._server_socket = None
            if stop_event:
                stop_event.set()
        log('Stopping development server...')
        if cls._active_debugging and cls._active_platform:
            cls.stop_remote_debugging(cls._active_platform)
        if server_socket:
            try:
                server_socket.close()
            except Exception as e:
                log(f'Error closing server socket: {e}')
        for client_socket in client_sockets:
            try:
                client_socket.shutdown(socket.SHUT_RDWR)
            except Exception:
                pass
            try:
                client_socket.close()
            except Exception:
                pass
        engine = PluginsController.engines.get('python')
        if engine and engine.debuggerListener:
            engine.setDebuggerListener(None)
        
        log('Development server stop requested')
        return True

    @classmethod
    def _server_thread_function(cls, host: str, port: int,
                                context: _ServerContext):
        server_socket = None
        try:
            server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            server_socket.setsockopt(
                socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            server_socket.bind((host, port))
            server_socket.listen(4)
            server_socket.settimeout(0.5)
            with cls._state_lock:
                if not cls._context_is_live_locked(context):
                    return
                cls._server_socket = server_socket
                context.ready_event.set()
            log(f'Server listening on {host}:{port}')
            while context.is_live():
                try:
                    client_socket, client_address = server_socket.accept()
                    if client_address[0] not in ('127.0.0.1', '::1'):
                        client_socket.close()
                        continue
                    with cls._state_lock:
                        if not cls._context_is_live_locked(context):
                            client_socket.close()
                            break
                        cls._client_sockets[client_socket] = context
                    log(f'Accepted connection from {client_address}')
                    cls._handle_client(client_socket, context)
                except socket.timeout:
                    continue
                except Exception as e:
                    if context.is_live():
                        log(f'Accept error: {e}\n{traceback.format_exc()}')
        except Exception as e:
            context.start_error = str(e)
            log(f'Server error: {e}\n{traceback.format_exc()}')
        finally:
            
            context.stop_event.set()
            context.ready_event.set()
            if server_socket is not None:
                try:
                    server_socket.close()
                except Exception:
                    pass
            with cls._state_lock:
                clients = tuple(
                    client_socket
                    for client_socket, owner
                    in cls._client_sockets.items()
                    if owner is context)
            for client_socket in clients:
                try:
                    client_socket.close()
                except Exception:
                    pass
            with cls._state_lock:
                for client_socket in clients:
                    cls._client_sockets.pop(client_socket, None)
                if cls._active_context is context:
                    if cls._server_socket is server_socket:
                        cls._server_socket = None
                    cls._server_thread = None
                    cls._stop_event = None
                    cls._is_running = False
                    cls._active_context = None
            log('Server thread terminated')

    @classmethod
    def _context_is_live_locked(cls, context: _ServerContext) -> bool:
        return (
            context is not None
            and cls._active_context is context
            and cls._generation == context.generation
            and cls._is_running
            and not context.stop_event.is_set()
        )

    @classmethod
    def _context_is_live(cls, context: _ServerContext) -> bool:
        with cls._state_lock:
            live = cls._context_is_live_locked(context)
        if not live:
            return False
        try:
            return bool(context.bridge.isActive())
        except Exception:
            return False

    @classmethod
    def _post_host_action(cls, context: _ServerContext,
                          authority, action) -> bool:
        if not context.is_live():
            return False
        try:
            return bool(authority.postToMain(action))
        except Exception:
            return False

    @classmethod
    def _handle_client(cls, client_socket: socket.socket,
                       context: _ServerContext):
        buffer = b''
        command_count = 0
        try:
            client_socket.settimeout(min(cls.SOCKET_TIMEOUT, 30))
            while context.is_live():
                data = client_socket.recv(cls.BUFFER_SIZE)
                if not data:
                    break
                if len(buffer) + len(data) > cls.MAX_REQUEST_BYTES:
                    cls._send_response(client_socket, None, {'error': 'request too large'})
                    break
                buffer += data
                remaining = cls.MAX_COMMANDS_PER_CONNECTION - command_count
                buffer, processed = cls._process_buffer(
                    buffer, client_socket, remaining, context)
                command_count += processed
                if command_count >= cls.MAX_COMMANDS_PER_CONNECTION:
                    cls._send_response(client_socket, None, {'error': 'command limit exceeded'})
                    break
        except socket.timeout:
            log('Connection timed out')
        except Exception as e:
            log(f'Error handling client: {e}\n{traceback.format_exc()}')
        finally:
            with cls._state_lock:
                cls._client_sockets.pop(client_socket, None)
            try:
                client_socket.close()
            except Exception:
                pass
            log('Client connection closed')

    @classmethod
    def _process_buffer(cls, buffer: bytes, client_socket: socket.socket,
                        max_commands: int, context: _ServerContext):
        buffer_str = buffer.decode('utf-8', errors='replace')
        pos = 0
        processed = 0
        
        while pos < len(buffer_str) and processed < max_commands:
            while pos < len(buffer_str) and buffer_str[pos].isspace():
                pos += 1
            
            if pos >= len(buffer_str):
                break

            try:
                decoder = json.JSONDecoder()
                json_obj, end_pos = decoder.raw_decode(buffer_str[pos:])
                
                cls._process_command(json_obj, client_socket, context)
                processed += 1
                
                pos += end_pos
                
            except json.JSONDecodeError:
                return buffer_str[pos:].encode('utf-8'), processed

        return b'', processed

    @classmethod
    def _process_command(cls, command: Dict[str, Any],
                         client_socket: socket.socket,
                         context: _ServerContext):
        if not isinstance(command, dict):
            cls._send_response(client_socket, None, {'error': 'invalid command'})
            return
        action = command.get('@')
        request_id = command.get('#')
        supplied = command.get('auth')
        if not context.is_live() or not isinstance(supplied, str):
            cls._send_response(client_socket, request_id, {'error': 'unauthorized'})
            return
        try:
            authority = context.bridge.authorize(supplied)
        except Exception:
            authority = None
        if authority is None or not context.is_live():
            cls._send_response(
                client_socket, request_id, {'error': 'unauthorized'})
            return
        handlers = {'get_plugins': cls._handle_get_plugins, 'enable_plugin': cls._handle_enable_plugin, 'disable_plugin': cls._handle_disable_plugin, 'reload_plugin': cls._handle_reload_plugin, 'write_plugin': cls._handle_write_plugin, 'remove_plugin': cls._handle_remove_plugin, 'start_debugger': cls._handle_start_debugger, 'stop_debugger': cls._handle_stop_debugger, 'ping': cls._handle_ping}
        handler = handlers.get(action)
        if handler:
            result = handler(command, context, authority) or {}
        else:
            authority.consume()
            log(f'Unknown action: {action}')
            result = {'error': 'unknown command'}
        cls._send_response(client_socket, request_id, result)

    @classmethod
    def _consume_authority(cls, context: _ServerContext, authority) -> bool:
        if not context.is_live():
            return False
        try:
            consumed = bool(authority.consume())
        except Exception:
            consumed = False
        
        return consumed and context.is_live()

    @classmethod
    def _send_response(cls, client_socket, request_id, result):
        data = {'#': request_id, **(result or {})}
        try:
            response_data = json.dumps(data).encode('utf-8')
            client_socket.sendall(response_data)
            log(f'Sent response for request {request_id}')
        except Exception as e:
            log(f'Error sending response: {e}\n{traceback.format_exc()}')

    @classmethod
    def _get_plugins_map(cls):
        result = {}
        for plugin in PluginsController.getInstance().plugins.values().toArray():
            error = plugin.getError()
            result[plugin.getId()] = {'name': plugin.getName(), 'description': plugin.getDescription(), 'author': plugin.getAuthor(), 'version': plugin.getVersion(), 'error': error.getLocalizedMessage() if error else None, 'enabled': plugin.isEnabled()}
        return result

    @classmethod
    def _handle_get_plugins(cls, command: Dict[str, Any],
                            context: _ServerContext, authority):
        if not cls._consume_authority(context, authority):
            return {'error': 'stale server generation'}
        return cls._get_plugins_map()

    @classmethod
    def _handle_enable_plugin(cls, command: Dict[str, Any],
                              context: _ServerContext, authority):
        plugin_id = command.get('plugin_id')
        if not plugin_id:
            log('Missing plugin_id in enable_plugin command')
            return {'error': 'Missing plugin_id'}
        if not PluginsController.getInstance().plugins.containsKey(plugin_id):
            log(f'Plugin {plugin_id} not found')
            return {'error': 'Plugin not found'}
        log(f'Enabling plugin {plugin_id}')
        if not cls._post_host_action(
                context, authority,
                lambda: PluginsController.getInstance().setPluginEnabled(
                    plugin_id, True, None)):
            return {'error': 'stale server generation'}
        return {'status': 'scheduled'}

    @classmethod
    def _handle_disable_plugin(cls, command: Dict[str, Any],
                               context: _ServerContext, authority):
        plugin_id = command.get('plugin_id')
        if not plugin_id:
            log('Missing plugin_id in disable_plugin command')
            return {'error': 'Missing plugin_id'}
        if not PluginsController.getInstance().plugins.containsKey(plugin_id):
            log(f'Plugin {plugin_id} not found')
            return {'error': 'Plugin not found'}
        log(f'Disabling plugin {plugin_id}')
        if not cls._post_host_action(
                context, authority,
                lambda: PluginsController.getInstance().setPluginEnabled(
                    plugin_id, False, None)):
            return {'error': 'stale server generation'}
        return {'status': 'scheduled'}

    @classmethod
    def _handle_reload_plugin(cls, command: Dict[str, Any],
                              context: _ServerContext, authority):
        plugin_id = command.get('plugin_id')
        if not plugin_id:
            log('Missing plugin_id in reload_plugin command')
            return {'error': 'Missing plugin_id'}
        if not context.is_live():
            return {'error': 'stale server generation'}
        if not PluginsController.getInstance().plugins.containsKey(plugin_id):
            log(f'Plugin {plugin_id} not found')
            return {'error': 'Plugin not found'}
        log(f'Reloading plugin {plugin_id}')
        return cls._reload_plugin(
            plugin_id, context, authority)

    @classmethod
    def _handle_write_plugin(cls, command: Dict[str, Any],
                             context: _ServerContext, authority):
        plugin_id = command.get('plugin_id')
        content = command.get('content')
        if (not plugin_id or not isinstance(content, str)
                or not content):
            log('Missing plugin_id or content in write_plugin command')
            return {'error': 'Missing plugin_id or content'}
        if len(content.encode('utf-8')) > cls.MAX_REQUEST_BYTES:
            return {'error': 'Plugin content is too large'}
        if not context.is_live():
            return {'error': 'stale server generation'}

        log(f'Writing plugin {plugin_id}')
        controller = PluginsController.getInstance()
        temp_plugin_path = None
        try:
            with tempfile.NamedTemporaryFile(mode='w', suffix='.py', delete=False, encoding='utf-8') as tmp_file:
                tmp_file.write(content)
                tmp_file.flush()
                os.fsync(tmp_file.fileno())
                temp_plugin_path = tmp_file.name
            
            log(f'Plugin content written to temporary file: {temp_plugin_path}')

            cleanup = cls._candidate_cleanup(temp_plugin_path)

            def _callback(error: str):
                if not context.is_live():
                    return
                if error:
                    log(f'Error activating plugin {plugin_id}: {error}')
                    return

                def enable_if_needed():
                    plugin = controller.plugins.get(plugin_id)
                    if plugin and not plugin.isEnabled():
                        controller.setPluginEnabled(plugin_id, True, None)

                enable_if_needed()

            try:
                if not context.is_live():
                    cleanup()
                    return {'error': 'stale server generation'}
                accepted = bool(authority.installCandidate(
                    temp_plugin_path, plugin_id, _callback))
                
                cleanup()
                if not accepted:
                    return {
                        'error': 'Development install authority was rejected'}
            except Exception as e:
                log(f'Error activating plugin {plugin_id}: {e}\n{traceback.format_exc()}')
                cleanup()
                return {'error': str(e)}
            log(f'Plugin {plugin_id} written successfully')
            return {'status': 'scheduled'}
            
        except Exception as e:
            if temp_plugin_path:
                cls._remove_candidate(temp_plugin_path)
            log(f'Error writing plugin {plugin_id}: {e}\n{traceback.format_exc()}')
            return {'error': str(e)}

    @classmethod
    def _handle_remove_plugin(cls, command: Dict[str, Any],
                              context: _ServerContext, authority):
        plugin_id = command.get('plugin_id')
        if not plugin_id:
            log('Missing plugin_id in remove_plugin command')
            return {'error': 'Missing plugin_id'}
        if not PluginsController.getInstance().plugins.containsKey(plugin_id):
            log(f'Plugin {plugin_id} not found')
            return {'error': 'Plugin not found'}
        log(f'Removing plugin {plugin_id}')
        if not cls._post_host_action(
                context, authority,
                lambda: PluginsController.getInstance().deletePlugin(
                    plugin_id, None)):
            return {'error': 'stale server generation'}
        return {'status': 'scheduled'}
  
    @classmethod
    def _handle_start_debugger(cls, command: Dict[str, Any],
                               context: _ServerContext, authority):
        if not cls._consume_authority(context, authority):
            return {'error': 'stale server generation'}
        if cls._active_debugging:
            log('Debugger is already active.')
            return {'status': 'already_running'}
        host = command.get('host')
        port = command.get('port')
        platform_str = command.get('platform')
        if not host or not port or not platform_str:
            log('Missing host, port, or platform in start_debugger command')
            return {'error': 'Missing required parameters'}
        try:
            platform = DebuggerPlatform(platform_str)
        except ValueError:
            log(f'Invalid platform: {platform_str}')
            return {'error': f'Invalid platform: {platform_str}'}
        if not context.is_live():
            return {'error': 'stale server generation'}
        log(
            f'Starting remote debugger on {host}:{port}, '
            f'platform: {platform}')
        success = cls.setup_remote_debugging(host, int(port), platform)
        if not success:
            return {'error': 'Failed to start debugger'}
        if not context.is_live():
            cls.stop_remote_debugging(platform)
            return {'error': 'stale server generation'}
        listener = DebuggerEventListener(
            context, host, int(port), platform)
        if not context.is_live():
            cls.stop_remote_debugging(platform)
            return {'error': 'stale server generation'}
        PluginsController.engines.get('python').setDebuggerListener(listener)
        cls._active_debugging = True
        cls._active_platform = platform
        return {'status': 'started'}
   
    @classmethod
    def _handle_stop_debugger(cls, command: Dict[str, Any],
                              context: _ServerContext, authority):
        log('Stopping remote debugger')
        if not cls._active_debugging:
            if not cls._consume_authority(context, authority):
                return {'error': 'stale server generation'}
            log('Debugger is not active.')
            return {'status': 'not_running'}
        platform_str = command.get('platform')
        if not platform_str:
            log('Missing platform in stop_debugger command')
            return {'error': 'Missing platform'}
        try:
            platform = DebuggerPlatform(platform_str)
        except ValueError:
            log(f'Invalid platform: {platform_str}')
            return {'error': f'Invalid platform: {platform_str}'}

        def stop_debugger():
            cls.stop_remote_debugging(platform)
            PluginsController.engines.get(
                'python').setDebuggerListener(None)
            cls._active_debugging = False
            cls._active_platform = None

        if not cls._post_host_action(
                context, authority, stop_debugger):
            return {'error': 'stale server generation'}
        return {'status': 'scheduled'}
   
    @classmethod
    def _handle_ping(cls, command: Dict[str, Any],
                     context: _ServerContext, authority):
        if not cls._consume_authority(context, authority):
            return {'error': 'stale server generation'}
        log('Received ping command')
        return {'pong': True}

    @staticmethod
    def _remove_candidate(path: str):
        try:
            if path and os.path.exists(path):
                os.remove(path)
        except Exception as e:
            log(f'Could not remove temporary plugin candidate {path}: {e}')

    @classmethod
    def _candidate_cleanup(cls, path: str):
        lock = threading.Lock()
        cleaned = False

        def cleanup():
            nonlocal cleaned
            with lock:
                if cleaned:
                    return
                cleaned = True
            cls._remove_candidate(path)

        return cleanup
  
    @classmethod
    def _reload_plugin(cls, plugin_id: str,
                       context: _ServerContext, authority):
        temp_copy_path = None
        try:
            controller = PluginsController.getInstance()
            plugin_path = controller.getPluginPath(plugin_id)
            if not plugin_path or not os.path.exists(plugin_path):
                log(f'Cannot reload plugin \'{plugin_id}\': file not found.')
                return {'error': 'Plugin file not found'}
            with tempfile.NamedTemporaryFile(
                    mode='wb', suffix='.py', delete=False) as tmp_file:
                temp_copy_path = tmp_file.name
            shutil.copyfile(plugin_path, temp_copy_path)
            cleanup = cls._candidate_cleanup(temp_copy_path)

            def _callback(error: str):
                if not context.is_live():
                    return
                if error:
                    log(f'Error reloading plugin {plugin_id}: {error}')
                else:
                    log(f'Plugin \'{plugin_id}\' reloaded successfully.')

            if not context.is_live():
                cleanup()
                return {'error': 'stale server generation'}
            accepted = bool(authority.installCandidate(
                temp_copy_path, plugin_id, _callback))
            
            cleanup()
            if not accepted:
                return {
                    'error': 'Development install authority was rejected'}
            log(f'Scheduled reload for plugin \'{plugin_id}.py\'.')
            return {'status': 'scheduled'}
        except Exception as e:
            if temp_copy_path:
                cls._remove_candidate(temp_copy_path)
            log(f'Error during _reload_plugin for \'{plugin_id}.py\': {e}\n{traceback.format_exc()}')
            return {'error': str(e)}
  
    @classmethod
    def setup_remote_debugging(cls, host: str, port: int, platform: DebuggerPlatform) -> bool:
        if platform == DebuggerPlatform.PyCharm:
            success = cls._setup_pycharm_remote_debugger(host, port)
        else:
            if platform == DebuggerPlatform.VSCode:
                success = cls._setup_vscode_remote_debugger(host, port)
            else:
                log(f'Unsupported debugger platform: {platform}')
                success = False
        if success:
            cls._active_debugging = True
            cls._active_platform = platform
        return success
  
    @classmethod
    def stop_remote_debugging(cls, platform: DebuggerPlatform) -> bool:
        if platform == DebuggerPlatform.PyCharm:
            success = cls._stop_pycharm_remote_debugging()
        else:
            if platform == DebuggerPlatform.VSCode:
                success = cls._stop_vscode_remote_debugger()
            else:
                log(f'Unsupported debugger platform: {platform}')
                success = False
        if success:
            cls._active_debugging = False
            cls._active_platform = None
        return success
 
    @classmethod
    def _setup_pycharm_remote_debugger(cls, host: str, port: int) -> bool:
        try:
            sys.path.append(cls.PYCHARM_DEBUGGER_DIR)
            import pydevd_pycharm
            log(f'Setting up PyCharm remote debugger on {host}:{port}')
            pydevd_pycharm.settrace(host, port=port, stdoutToServer=True, stderrToServer=True, suspend=False)
            log('PyCharm remote debugger setup complete')
        except ImportError:
            log('PyCharm debugger module not found')
            return False
        except Exception as e:
            log(f'Error setting up PyCharm remote debugger: {e}')
            return False
        else:
            return True
  
    @classmethod
    def _setup_vscode_remote_debugger(cls, host: str, port: int) -> bool:
        try:
            import debugpy
            log(f'Connecting VS Code remote debugger to {host}:{port}')
            debugpy.listen((host, port), in_process_debug_adapter=True)
            log('VS Code remote debugger connected successfully')
        except ImportError:
            log('VS Code debugger module not found')
            return False
        except Exception as e:
            log(f'Error setting up VS Code remote debugger: {e}')
            return False
        else:
            return True
  
    @classmethod
    def _stop_pycharm_remote_debugging(cls) -> bool:
        try:
            import pydevd_pycharm
            log('Stopping PyCharm remote debugger')
            pydevd_pycharm.stoptrace()
            log('PyCharm remote debugger stopped successfully')
        except ImportError:
            log('PyCharm debugger module not found')
            return False
        except Exception as e:
            log(f'Error stopping PyCharm remote debugger: {e}')
            return False
        else:
            return True
 
    @classmethod
    def _stop_vscode_remote_debugger(cls) -> bool:
        try:
            import debugpy._vendored.pydevd.pydevd
            log('Stopping VS Code remote debugger')
            debugpy._vendored.pydevd.pydevd.stoptrace()
            log('VS Code remote debugger stopped successfully')
        except ImportError:
            log('VS Code debugger module not found')
            return False
        except Exception as e:
            log(f'Error stopping VS Code remote debugger: {e}')
            return False
        else:
            return True
