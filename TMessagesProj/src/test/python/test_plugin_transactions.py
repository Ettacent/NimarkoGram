import pathlib
import unittest

REPO = pathlib.Path(__file__).resolve().parents[4]
JAVA = REPO / 'TMessagesProj/src/main/java/app/nimarkogram/messenger/plugins'

class PluginTransactionTest(unittest.TestCase):
    def test_source_update_has_durable_prepare_and_commit_barrier(self):
        engine = (JAVA / 'PythonPluginsEngine.java').read_text()
        transaction = engine[
            engine.index('final boolean hadExistingFile'):
            engine.index('private File allocatePluginBackupFile')
        ]
        dependency_snapshot = transaction.index(
            'writeDependencySnapshot(')
        artifact_prepare = transaction.index(
            'beginDeferredArtifactTransaction(')
        prepared = transaction.index('UPDATE_STATE_PREPARED')
        candidate_publish = transaction.index(
            'publishAuthorizedCandidate(')
        runtime_publish = transaction.index(
            'getPluginToggleGeneration(id)', candidate_publish)
        committed = transaction.index('UPDATE_STATE_COMMITTED')
        artifact_commit = transaction.index(
            'commitDeferredArtifactTransaction(')
        dependency_cleanup = transaction.index(
            'PipController.getInstance().cleanupAndReport();')
        self.assertLess(artifact_prepare, dependency_snapshot)
        self.assertLess(dependency_snapshot, prepared)
        self.assertLess(prepared, candidate_publish)
        self.assertLess(candidate_publish, runtime_publish)
        self.assertLess(runtime_publish, committed)
        self.assertLess(committed, artifact_commit)
        self.assertLess(artifact_commit, dependency_cleanup)
        self.assertLess(committed, dependency_cleanup)
        self.assertIn(
            'recoverInterruptedPluginUpdates(getPluginsController())',
            engine,
        )
        self.assertGreaterEqual(
            engine.count(
                'recoverInterruptedPluginUpdates(getPluginsController())'),
            2,
        )

    def test_prepared_recovery_restores_durable_dependency_sidecar(self):
        engine = (JAVA / 'PythonPluginsEngine.java').read_text()
        recovery = engine[
            engine.index('static void recoverInterruptedPluginUpdates('):
            engine.index(
                'private static PluginUpdateMarkerData '
                'readPluginUpdateMarker(')
        ]
        self.assertIn('readDependencySnapshot(', recovery)
        self.assertIn('.restoreState(', recovery)
        self.assertIn(
            '.rollbackDeferredArtifactTransaction(', recovery)
        self.assertIn('if (!Python.isStarted())', recovery)
        self.assertLess(
            recovery.index('.rollbackDeferredArtifactTransaction('),
            recovery.index('android.system.Os.rename('),
        )
        self.assertLess(
            recovery.index('readDependencySnapshot('),
            recovery.index(
                'dependencySnapshot.delete()',
                recovery.index('readDependencySnapshot('),
            ),
        )

    def test_committed_recovery_retries_post_commit_cleanup(self):
        engine = (JAVA / 'PythonPluginsEngine.java').read_text()
        recovery = engine[
            engine.index('static void recoverInterruptedPluginUpdates('):
            engine.index(
                'private static PluginUpdateMarkerData '
                'readPluginUpdateMarker(')
        ]
        committed = recovery.index(
            'UPDATE_STATE_COMMITTED.equals(markerData.state)')
        cleanup = recovery.index('cleanupAndReport()', committed)
        marker_delete = recovery.index('marker.delete()', cleanup)
        self.assertLess(committed, cleanup)
        self.assertLess(cleanup, marker_delete)

    def test_explicit_delete_publishes_durable_intent_first(self):
        engine = (JAVA / 'PythonPluginsEngine.java').read_text()
        delete = engine[
            engine.index('public void deletePlugin('):
            engine.index('public String getPluginPath(')
        ]
        self.assertLess(
            delete.index('writePluginDeleteMarker('),
            delete.index('completePluginDeletion('),
        )
        self.assertNotIn('restorePluginSettingsSnapshot(', delete)
        self.assertNotIn('loadPlugin(str, file.getAbsolutePath()', delete)

    def test_controller_delete_linearizes_after_durable_prepare(self):
        controller = (JAVA / 'PluginsController.java').read_text()
        start = controller.index(
            'public void deletePlugin(final String str,')
        delete = controller[
            start:
            controller.index(
                'public void cleanupPlugin(String str)', start)
        ]
        queue_handoff = delete.index(
            'Thread.currentThread() != queue')
        prepared = delete.index(
            'PythonPluginsEngine.prepareDurablePluginDeletion(')
        self.assertLess(queue_handoff, prepared)
        self.assertIn(
            'queue.postRunnable(() -> deletePlugin(str, callback))',
            delete[:prepared],
        )
        generation = delete.index('toggleGenerations.put(', prepared)
        pending = delete.index('pendingToggleState.remove(', prepared)
        detached = delete.index('detachPluginRuntimeLocked(str)', prepared)
        teardown = delete.index(
            'finishPluginDeactivation(immediateCleanup)', prepared)
        notified = delete.index('notifyPluginsChanged()', prepared)
        self.assertLess(prepared, generation)
        self.assertLess(prepared, pending)
        self.assertLess(prepared, detached)
        self.assertLess(prepared, teardown)
        self.assertLess(prepared, notified)
        failure = delete[
            delete.index('if (!deletionPrepared)'):
            delete.index('if (preferences != null)')
        ]
        self.assertIn('callback.run(', failure)
        self.assertIn('return;', failure)
        self.assertNotIn('toggleGenerations', failure)
        self.assertNotIn('detachPluginRuntime', failure)
        self.assertNotIn('notifyPluginsChanged', failure)

    def test_delete_recovery_wins_before_update_recovery(self):
        engine = (JAVA / 'PythonPluginsEngine.java').read_text()
        recovery = engine[
            engine.index('static void recoverInterruptedPluginUpdates('):
            engine.index(
                'private static PluginUpdateMarkerData '
                'readPluginUpdateMarker(')
        ]
        self.assertLess(
            recovery.index('recoverInterruptedPluginDeletes(controller)'),
            recovery.index('cleanupOrphanDependencySnapshots(directory)'),
        )
        self.assertIn(
            'pluginDeleteMarker(directory, pluginId).exists()',
            recovery,
        )
        complete = engine[
            engine.index('private static boolean completePluginDeletion('):
            engine.index(
                'private static boolean '
                'discardPluginSourceRecoveryFiles(')
        ]
        self.assertLess(
            complete.index('discardDeferredArtifactTransaction(pluginId)'),
            complete.index('uninstallDependencies(pluginId)'),
        )
        self.assertLess(
            complete.index('DELETE_STATE_COMMITTED'),
            complete.index('discardPluginSourceRecoveryFiles('),
        )
        self.assertLess(
            complete.index('discardPluginSourceRecoveryFiles('),
            complete.index('marker.delete()'),
        )

    def test_watchdog_uses_same_durable_delete_coordinator(self):
        watchdog = (
            JAVA / 'utils/PluginsWatchdog.java'
        ).read_text()
        method = watchdog[
            watchdog.index('public void forceDeletePlugin('):
            watchdog.index('private void restartApp(')
        ]
        self.assertIn('controller.forceDeletePluginDurably(pluginId)', method)
        self.assertNotIn('prepareDurablePluginDeletion(', method)
        self.assertNotIn('controller.cleanupPlugin(', method)
        self.assertNotIn('android.system.Os.rename(', method)
        self.assertNotIn('uninstallDependencies(', method)

    def test_watchdog_disable_uses_atomic_controller_path(self):
        watchdog = (
            JAVA / 'utils/PluginsWatchdog.java'
        ).read_text()
        method = watchdog[
            watchdog.index('public void forceDisablePlugin('):
            watchdog.index('public void forceDeletePlugin(')
        ]
        self.assertIn(
            'controller.forceDisablePluginDurably(pluginId)', method)
        self.assertNotIn('preferences.edit()', method)
        self.assertNotIn('controller.cleanupPlugin(', method)

    def test_watchdog_delete_marker_precedes_runtime_detach(self):
        controller = (JAVA / 'PluginsController.java').read_text()
        method = controller[
            controller.index(
                'public boolean forceDeletePluginDurably('):
            controller.index(
                'public void deletePlugin(final String str,')
        ]
        marker = method.index('prepareDurablePluginDeletion(')
        generation = method.index('toggleGenerations.put(', marker)
        detach = method.index(
            'detachPluginRuntimeLocked(pluginId)', marker)
        self.assertLess(marker, generation)
        self.assertLess(marker, detach)
        self.assertIn('synchronized (generationLock(pluginId))', method)

    def test_pending_recovery_cannot_execute_plugin(self):
        engine = (JAVA / 'PythonPluginsEngine.java').read_text()
        load_start = engine.index(
            'private void loadPlugin(\n'
            '            String str, String str2, Plugin plugin,')
        load = engine[
            load_start:
            engine.index(
                'boolean z = getPluginsController().preferences',
                load_start,
            )
        ]
        self.assertIn('RECOVERY_BLOCKED_PLUGIN_IDS.contains(str)', load)
        startup = engine[
            engine.index('public void loadPlugins('):
            engine.index('private void loadPlugin(String str',)
        ]
        self.assertIn(
            'RECOVERY_BLOCKED_PLUGIN_IDS.contains(\n'
            '                                    pluginId)',
            startup,
        )
        recovery_gate = startup.index(
            'RECOVERY_BLOCKED_PLUGIN_IDS.contains(')
        candidate_publication = startup.index(
            'startupFiles.put(pluginId, file)')
        activation_loop = startup.index(
            'for (Map.Entry<String, File> entry')
        self.assertLess(recovery_gate, candidate_publication)
        self.assertLess(candidate_publication, activation_loop)

    def test_pip_recovers_interrupted_artifact_renames(self):
        pip = (JAVA / 'pip/PipController.java').read_text()
        recovery = pip[
            pip.index(
                'private void recoverInterruptedArtifactTransactions()'):
            pip.index('// Cleanup / uninstall')
        ]
        self.assertIn('ARTIFACT_BACKUP_PATTERN', recovery)
        self.assertIn('ARTIFACT_STAGE_PATTERN', recovery)
        self.assertIn('android.system.Os.rename(', recovery)
        self.assertIn(
            'recoverInterruptedArtifactTransactions();',
            pip[pip.index('public synchronized void loadRegistry()'):],
        )

    def test_pip_registry_is_fail_closed_for_destructive_paths(self):
        pip = (JAVA / 'pip/PipController.java').read_text()
        strict = pip[
            pip.index('private void loadRegistryStrict()'):
            pip.index('public synchronized void saveRegistry()')
        ]
        self.assertIn('validated = new LinkedHashMap<>();', strict)
        self.assertLess(
            strict.index('parsed = gson.fromJson('),
            strict.index('registry.clear()'),
        )
        uninstall = pip[
            pip.index(
                'public synchronized boolean uninstallDependencies('):
            pip.index(
                'public synchronized DependencySnapshot snapshotState(')
        ]
        self.assertIn('loadRegistryStrict();', uninstall)

    def test_every_pip_publish_has_a_crash_recovery_journal(self):
        pip = (JAVA / 'pip/PipController.java').read_text()
        install = pip[
            pip.index(
                'public synchronized List<String> '
                'installDependencies('):
            pip.index(
                'private static final class '
                'InstallCancelledException')
        ]
        self.assertIn(
            'beginLocalArtifactTransaction(', install)
        self.assertIn(
            'markLocalArtifactRegistryCommitted(', install)
        self.assertIn(
            'commitDeferredArtifactTransaction(', install)
        self.assertLess(
            install.index('appendDeferredArtifactEntries('),
            install.index(
                'for (StagedReplacement replacement '
                ': replacements) replacement.commit()'),
        )
        self.assertLess(
            install.index('saveRegistryStrict();'),
            install.index(
                'markLocalArtifactRegistryCommitted('),
        )

    def test_dependency_journal_is_checksummed_and_identity_bound(self):
        pip = (JAVA / 'pip/PipController.java').read_text()
        journal = pip[
            pip.index(
                'private DeferredArtifactJournal '
                'readDeferredArtifactJournal('):
            pip.index(
                'private File deferredArtifactJournal(')
        ]
        self.assertIn(
            'Dependency artifact journal checksum mismatch',
            journal,
        )
        self.assertIn(
            'journal.transactionId',
            journal,
        )
        self.assertIn('+ ".backup"', journal)
        self.assertIn(
            'Duplicate dependency artifact target',
            pip,
        )

    def test_source_rollback_is_durable_and_checksum_verified(self):
        engine = (JAVA / 'PythonPluginsEngine.java').read_text()
        recovery = engine[
            engine.index(
                'static void recoverInterruptedPluginUpdates('):
            engine.index(
                'private static PluginUpdateMarkerData '
                'readPluginUpdateMarker(')
        ]
        self.assertIn('UPDATE_STATE_ROLLING_BACK', recovery)
        self.assertIn('UPDATE_STATE_ROLLED_BACK', recovery)
        self.assertIn('hasOuterArtifactTransaction(', recovery)
        self.assertIn('matchesSourceChecksum(', recovery)
        rollback = engine[
            engine.index('private void rollbackPluginFileInstall('):
            engine.index(
                'public PluginsController.PluginValidationResult '
                'validatePluginFromFile(')
        ]
        rolling = rollback.index('UPDATE_STATE_ROLLING_BACK')
        artifacts = rollback.index(
            'rollbackDeferredArtifactTransaction(')
        rolled_back = rollback.index(
            'UPDATE_STATE_ROLLED_BACK')
        sidecar_delete = rollback.index(
            'dependencySnapshotFile.delete()')
        self.assertLess(rolling, artifacts)
        self.assertLess(artifacts, rolled_back)
        self.assertLess(rolled_back, sidecar_delete)
        self.assertNotIn(
            'installDependencies(\n'
            '                            previousRequirements',
            rollback,
        )

    def test_settings_delete_is_host_native_and_bounded(self):
        engine = (JAVA / 'PythonPluginsEngine.java').read_text()
        host = engine[
            engine.index(
                'private static boolean removePluginSettingsFromDisk('):
            engine.index(
                'private static boolean clearPluginHostPreferences(')
        ]
        self.assertIn('MAX_PLUGIN_SETTINGS_BYTES', host)
        self.assertIn('JsonParser.parseString(', host)
        self.assertIn('android.system.Os.rename(', host)
        self.assertIn('syncDirectoryStrict(directory)', host)
        safe = engine[
            engine.index(
                'private boolean removePluginSettingsHostNative('):
            engine.index(
                'private static File pluginUpdateDependencySnapshot(')
        ]
        self.assertIn('Python current = this.python;', safe)
        self.assertNotIn('getPython()', safe)

    def test_recovery_runs_before_crash_attribution(self):
        controller = (JAVA / 'PluginsController.java').read_text()
        recover = controller.index(
            'PythonPluginsEngine.recoverInterruptedPluginUpdates(this)')
        attribution = controller.index(
            'String crashedPluginId = this.preferences.getString')
        self.assertLess(recover, attribution)

    def test_dependency_rollback_restores_ordered_managed_sys_path(self):
        pip = (JAVA / 'pip/PipController.java').read_text()
        self.assertIn(
            'private final List<String> managedImportPaths;', pip)
        restore = pip[
            pip.index('public synchronized boolean restoreState('):
            pip.index(
                'private static Map<String, Set<String>> '
                'deepCopyOwnership(')
        ]
        self.assertIn(
            'desiredPaths.equals(snapshotPaths)', restore)
        self.assertIn(
            'currentRuntime.orderedCanonicalPaths,\n'
            '                        desiredPaths, prepared', restore)
        self.assertLess(
            restore.index('saveRegistryStrict();'),
            restore.index('executeManagedRuntimeTransition('),
        )

    def test_self_update_requires_exact_live_runtime(self):
        engine = (JAVA / 'PythonPluginsEngine.java').read_text()
        method_start = engine.index('public void loadPluginFromRuntime(')
        method = engine[
            method_start:
            engine.index('public void loadPluginFromFile(', method_start)
        ]
        self.assertIn('PluginRuntimeToken requester', method)
        self.assertIn('captureCurrentPluginRuntime()', method)
        self.assertIn('!requester.equals(actual)', method)
        private_start = engine.index(
            'private void authorizeRuntimeSelfUpdate(')
        private_load = engine[
            private_start:
            engine.index(
                'boolean isCurrentDevInstallBridge(',
                private_start,
            )
        ]
        self.assertIn(
            'getPluginRuntimeTaskDecision(requester)', private_load)
        self.assertIn('RUNTIME_TASK_RUN', private_load)
        self.assertIn(
            'hostOwnedPayload, requester.getPluginId(),',
            private_load)

if __name__ == '__main__':
    unittest.main()
