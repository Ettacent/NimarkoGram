import hashlib
import pathlib
import re
import unittest

REPO = pathlib.Path(__file__).resolve().parents[4]
JAVA = (
    REPO / 'TMessagesProj/src/main/java/'
    'app/nimarkogram/messenger/plugins'
)
PYTHON = REPO / 'TMessagesProj/src/main/python'

def _source(path):
    return path.read_text(encoding='utf-8')

def _between(source, start, end):
    start_index = source.index(start)
    return source[start_index:source.index(end, start_index)]

class _StagedAuthorityContract:
    """Behavioral oracle for exact-byte and one-shot authorization."""

    def __init__(self, plugin_id, payload, expires_at):
        self.plugin_id = plugin_id
        self.sha256 = hashlib.sha256(payload).hexdigest()
        self.expires_at = expires_at
        self.transferred = False

    def transfer(self, plugin_id, payload, now):
        if self.transferred:
            return False
        self.transferred = True
        return (
            now < self.expires_at
            and plugin_id == self.plugin_id
            and hashlib.sha256(payload).hexdigest() == self.sha256
        )

class PluginInstallAuthorizationTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.engine = _source(JAVA / 'PythonPluginsEngine.java')
        cls.bridge = _source(JAVA / 'PluginDevInstallBridge.java')
        cls.sheet = _source(
            JAVA / 'ui/components/InstallPluginBottomSheet.java')
        cls.server = _source(PYTHON / 'dev_server.py')

    def test_staged_authority_contract_is_exact_and_one_shot(self):
        authority = _StagedAuthorityContract('alpha', b'v1', 100)
        self.assertFalse(authority.transfer('beta', b'v1', 1))
        self.assertFalse(authority.transfer('alpha', b'v1', 1))

        self.assertFalse(_StagedAuthorityContract(
            'alpha', b'v1', 100
        ).transfer('alpha', b'v2', 1))
        self.assertFalse(_StagedAuthorityContract(
            'alpha', b'v1', 100
        ).transfer('alpha', b'v1', 100))
        self.assertTrue(_StagedAuthorityContract(
            'alpha', b'v1', 100
        ).transfer('alpha', b'v1', 99))

    def test_internal_loaders_are_private_and_queue_confined(self):
        declarations = re.findall(
            r'(?m)^\s*(public|protected|private)\s+void\s+'
            r'loadPlugin\s*\(',
            self.engine)
        self.assertGreaterEqual(len(declarations), 5)
        self.assertEqual({'private'}, set(declarations))
        deepest = _between(
            self.engine,
            'private void loadPlugin(\n',
            'PluginDebugLog.log("loadPlugin START')
        self.assertIn(
            'Thread.currentThread() != Utilities.pluginsQueue',
            deepest)
        self.assertIn(
            'Internal plugin loader is pluginsQueue-only',
            deepest)

        legacy = _between(
            self.engine,
            'public void loadPluginFromFile(',
            'public void loadPluginFromRuntime(')
        self.assertIn(
            'Legacy plugin installation API has no host authority',
            legacy)
        self.assertNotIn('loadAuthorizedPluginFromFile(', legacy)

    def test_host_ticket_is_private_post_constructor_capability(self):
        ticket = _between(
            self.engine,
            'private final class HostInstallTicket',
            'private final class AuthorizedCandidate')
        self.assertNotIn('public static final class HostInstallTicket',
                         self.engine)
        self.assertNotIn('claimHostInstallTicket', self.engine)
        self.assertNotIn('installPluginFromHostTicket', self.engine)
        self.assertIn(
            'implements InstallPluginBottomSheet.HostInstallAuthority',
            ticket)
        self.assertIn(
            'captureCurrentPluginRuntime() != null', ticket)
        self.assertIn(
            'Looper.myLooper() != Looper.getMainLooper()', ticket)
        self.assertIn(
            'HOST_TICKET_BOUND,\n'
            '                    HOST_TICKET_QUEUED', ticket)
        self.assertLess(
            ticket.index('hostInstallTickets.remove(nonce, this)'),
            ticket.index('Utilities.pluginsQueue.postRunnable'))

        dialog = _between(
            self.engine,
            'public void showInstallDialog(',
            'public void openPluginSettings(')
        constructor = dialog.index(
            'new com.exteragram.messenger.plugins.ui.components.'
            'InstallPluginBottomSheet(')
        issued = dialog.index('issueHostInstallTicket(')
        bound = dialog.index('sheet.bindHostInstallAuthority(ticket)')
        shown = dialog.index('baseFragment.showDialog(')
        self.assertLess(constructor, issued)
        self.assertLess(issued, bound)
        self.assertLess(bound, shown)
        self.assertIn(
            'dialog -> sheet.onHostFragmentTeardown()', dialog)

    def test_stage_is_exclusive_bounded_and_publishes_parsed_bytes(self):
        stage_call = _between(
            self.engine,
            'private AuthorizedCandidate stageAuthorizedCandidate(',
            'private Map<String, String> parsePluginMetadataBytes(')
        self.assertIn('payload.bytes', stage_call)
        self.assertIn('payload.sha256', stage_call)

        stage = _between(
            self.engine,
            'private StagedCandidatePayload copyToExclusiveHostStage(',
            'private static void copyFileAndSync(')
        for required in (
                'O_EXCL', 'O_NOFOLLOW', 'O_CLOEXEC',
                'S_IRUSR', 'S_IWUSR',
                'MAX_PLUGIN_CANDIDATE_BYTES',
                'UUID.randomUUID()', 'Os.fchmod(', 'Os.fsync('):
            self.assertIn(required, stage)
        self.assertIn('calculateFileSha256NoFollow(staged)', stage)

        publish = _between(
            self.engine,
            'private void publishAuthorizedCandidate(',
            'private static String calculateFileSha256NoFollow(')
        self.assertIn(
            'synchronized (installPublicationLock)', publish)
        identity = publish.index(
            'calculateFileSha256NoFollow(staged)')
        guard = publish.index(
            'continuationGuard.isAuthorized(')
        rename = publish.index('android.system.Os.rename(')
        self.assertLess(identity, guard)
        self.assertLess(guard, rename)
        self.assertIn('candidate.markPublished()', publish)
        self.assertIn(
            'activeAuthorizedCandidates.remove(this)',
            self.engine)
        self.assertGreaterEqual(
            self.engine.count('revokeAllInstallCandidates();'), 2)
        self.assertIn(
            'cleanupOrphanedHostInstallStages();', self.engine)
        orphan_cleanup = _between(
            self.engine,
            'private void cleanupOrphanedHostInstallStages()',
            'private void consumeHostInstallTicket(')
        self.assertIn('HOST_INSTALL_STAGE_PATTERN', orphan_cleanup)
        self.assertIn('activeAuthorizedCandidates', orphan_cleanup)
        self.assertIn('stage.isFile() && stage.delete()', orphan_cleanup)

    def test_self_update_uses_exact_runtime_through_commit(self):
        public = _between(
            self.engine,
            'public void loadPluginFromRuntime(',
            'public void loadPluginFromFile(')
        self.assertIn('captureCurrentPluginRuntime()', public)
        self.assertIn('!requester.equals(actual)', public)
        self.assertLess(
            public.index('copyAuthorizedCandidateToHostStage(path)'),
            public.index('Utilities.pluginsQueue.postRunnable'))
        self.assertIn(
            'cleanupStagedCandidatePayload(hostOwnedPayload)', public)

        guard = _between(
            self.engine,
            'private final class RuntimeSelfUpdateGuard',
            'private static final class PluginUpdateMarkerData')
        for required in (
                'requester.equals(current)',
                'exactRetirementStarted && current == null',
                'requester.equals(mapped)',
                'getPluginToggleGeneration(pluginId)',
                'requester.getGeneration()',
                'isPluginEnableRequested('):
            self.assertIn(required, guard)
        self.assertIn(
            'continuationGuard.didRetireExistingRuntime(id)',
            self.engine)

        publish = _between(
            self.engine,
            'private void publishAuthorizedCandidate(',
            'private static String calculateFileSha256NoFollow(')
        self.assertLess(
            publish.index('continuationGuard.isAuthorized('),
            publish.index('android.system.Os.rename('))

    def test_dev_bridge_only_mints_authenticated_one_shots(self):
        outer = self.bridge[:self.bridge.index(
            'public static final class CommandAuthority')]
        self.assertNotIn('installCandidate(', outer)
        self.assertNotIn(
            'public PluginDevInstallBridge(', self.bridge)
        self.assertIn(
            'boolean startServer(PyObject serverClass)', self.bridge)
        self.assertIn(
            'serverClass.callAttrThrows("start_server", this)',
            self.bridge)
        self.assertIn(
            'serverThread.compareAndSet(null, thread)', self.bridge)
        self.assertIn(
            'serverThread.get() == Thread.currentThread()', self.bridge)
        self.assertIn(
            'MessageDigest.isEqual(', self.bridge)
        self.assertNotIn('public void serverTerminated(', self.bridge)
        self.assertIn(
            'engine.onDevServerTerminated(this, generation)',
            self.bridge)
        self.assertIn(
            'String getAuthenticationTokenForHostUi()', self.bridge)
        self.assertNotIn(
            'public String getAuthenticationTokenForHostUi()',
            self.bridge)

        authority = self.bridge[self.bridge.index(
            'public static final class CommandAuthority'):]
        self.assertIn(
            'private final AtomicBoolean consumed', authority)
        self.assertIn(
            'consumed.compareAndSet(false, true)', authority)
        self.assertIn('bridgeGeneration', authority)
        self.assertIn('commandGeneration', authority)
        self.assertIn(
            'owner.engine.installFromDevAuthority(', authority)
        self.assertIn(
            'public boolean installCandidate(', authority)
        self.assertIn(
            'private final class DevCompletion', self.bridge)
        self.assertIn(
            'callback.getAndSet(null)', self.bridge)

        self.assertIn(
            'bridge.startServer(pyObject)', self.engine)
        self.assertNotIn(
            'getAuthenticationTokenForHostUi()', self.engine)
        dev_install = _between(
            self.engine,
            'boolean installFromDevAuthority(',
            'private boolean isCurrentDevInstallAuthority(')
        self.assertIn(
            'isCurrentDevInstallAuthority(', dev_install)
        self.assertIn('commandGeneration', dev_install)
        self.assertLess(
            dev_install.index(
                'copyAuthorizedCandidateToHostStage(path)'),
            dev_install.index(
                'Utilities.pluginsQueue.postRunnable(authorize)'))
        self.assertIn(
            'cleanupStagedCandidatePayload(hostOwnedPayload)',
            dev_install)
        revoke = _between(
            self.engine,
            'private void revokeDevInstallBridge(',
            'void onDevServerTerminated(')
        self.assertIn(
            'synchronized (installPublicationLock)', revoke)
        self.assertIn('bridge.revokeFromHost()', revoke)

    def test_dev_server_revokes_and_generation_guards_deferred_work(self):
        self.assertNotIn('_install_bridge', self.server)
        self.assertNotIn('bridge.installCandidate(', self.server)
        self.assertIn('authority.installCandidate(', self.server)
        self.assertIn(
            'context.bridge.authorize(supplied)', self.server)
        for forbidden in (
                'auth_token', 'get_auth_token',
                'bindServerThread', 'serverTerminated',
                'PythonUtilitiesCallback'):
            self.assertNotIn(forbidden, self.server)
        start = _between(
            self.server,
            'def start_server(',
            'def stop_server(')
        self.assertIn(
            'current_thread = threading.current_thread()', start)
        self.assertNotIn('threading.Thread(', start)

        final = _between(
            self.server,
            '        finally:\n'
            '            # Make every queued Python action stale',
            "            log('Server thread terminated')")
        self.assertLess(
            final.index('context.stop_event.set()'),
            final.index('cls._active_context = None'))

        deferred = _between(
            self.server,
            'def _post_host_action',
            'def _handle_client')
        self.assertIn('authority.postToMain(action)', deferred)
        for handler in (
                '_handle_enable_plugin', '_handle_disable_plugin',
                '_handle_remove_plugin', '_handle_stop_debugger'):
            section = self.server[self.server.index(
                f'def {handler}'):self.server.index(
                    '\n    @classmethod',
                    self.server.index(f'def {handler}') + 1)]
            self.assertIn(
                '_post_host_action', section)
        stop = _between(
            self.server, 'def stop_server(', 'def _server_thread_function(')
        self.assertIn('client_socket.shutdown(socket.SHUT_RDWR)', stop)
        self.assertNotIn('thread.join(', stop)
        self.assertIn('return True', stop)
        self.assertIn(
            'bridge.awaitServerTermination(5_000L)', self.engine)
        self.assertIn(
            'thread.join(boundedTimeout)', self.bridge)

    def test_dev_temp_candidates_transfer_before_immediate_cleanup(self):
        write = _between(
            self.server,
            'def _handle_write_plugin',
            'def _handle_remove_plugin')
        reload_plugin = _between(
            self.server,
            'def _reload_plugin',
            'def setup_remote_debugging')
        for section in (write, reload_plugin):
            self.assertIn('cleanup = cls._candidate_cleanup(', section)
            self.assertIn('authority.installCandidate(', section)
            self.assertIn('plugin_id, _callback)', section)
            self.assertLess(
                section.index('authority.installCandidate('),
                section.index('cleanup()', section.index(
                    'authority.installCandidate(')))
            self.assertNotIn('PythonUtilitiesCallback', section)

    def test_sheet_has_explicit_transfer_and_fragment_lifecycle(self):
        for state in (
                'UNBOUND', 'BOUND', 'TRANSFERRING',
                'QUEUED', 'REVOKED'):
            self.assertIn(state, self.sheet)
        self.assertIn('onHostFragmentTeardown()', self.sheet)
        self.assertIn(
            'new AtomicReference<>(InstallTransferState.UNBOUND)',
            self.sheet)
        self.assertIn('revokeUnqueuedAuthority()', self.sheet)

        install = _between(
            self.sheet,
            'private void installPlugin(',
            'private void onInstallCompleted(')
        transfer = install.index('authority.transfer(')
        queue_cas = install.index(
            'InstallTransferState.TRANSFERRING,\n'
            '                            InstallTransferState.QUEUED')
        dismiss = install.index('dismiss();')
        self.assertLess(
            install.index('isTransferAttemptCurrent('), transfer)
        self.assertLess(transfer, queue_cas)
        self.assertLess(queue_cas, dismiss)
        self.assertIn('lifecycleEpoch == callbackLifecycleEpoch', install)
        self.assertIn('installOperationEpoch == operationEpoch', install)
        self.assertIn('catch (Throwable transferFailure)', install)
        self.assertIn('failInstallTransfer(', install)

        teardown = _between(
            self.sheet,
            'public final void onHostFragmentTeardown()',
            'private void showIncompatibleHint(')
        self.assertIn(
            'Looper.myLooper() != Looper.getMainLooper()', teardown)
        self.assertIn('revokeUnqueuedAuthority();', teardown)

        dismiss = _between(
            self.sheet,
            'public void dismiss()',
            'protected void onSwipeStarts()')
        self.assertIn('try {', dismiss)
        self.assertIn('finally {', dismiss)
        self.assertIn('revokeUnqueuedAuthority();', dismiss)

    def test_nimarkoprivacy_keeps_exact_runtime_self_update(self):
        for path in (
                pathlib.Path('/root/plugins/NimarkoPrivacy.plugin'),
                pathlib.Path(
                    '/root/claude_plugins/nimarkoplugins/'
                    'NimarkoPrivacy.plugin')):
            source = _source(path)
            self.assertIn(
                'plugin_runtime.run_owned_callback(', source)
            self.assertIn(
                'engine.loadPluginFromRuntime,', source)
            self.assertIn('target = None', source)
            self.assertIn(
                'for candidate in (staged, target):', source)

if __name__ == '__main__':
    unittest.main()
