package com.nextcraft.forge.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import androidx.lifecycle.viewModelScope
import com.nextcraft.forge.R
import com.nextcraft.forge.data.VaultBackup
import com.nextcraft.forge.model.AgentKind
import com.nextcraft.forge.model.DevStack
import com.nextcraft.forge.model.Project
import com.nextcraft.forge.model.ProjectType
import com.nextcraft.forge.model.ProviderProfile
import com.nextcraft.forge.model.projectSlug
import com.nextcraft.forge.runtime.AgentBridgeBase
import com.nextcraft.forge.runtime.AndroidBuildPhase
import com.nextcraft.forge.runtime.AttachmentStore
import com.nextcraft.forge.runtime.DiagnosticReport
import com.nextcraft.forge.runtime.ProjectArchiver
import com.nextcraft.forge.runtime.SshHost
import com.nextcraft.forge.runtime.WorkspaceSearch
import com.nextcraft.forge.runtime.findAndroidProjectRoot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class ToolsController(private val vm: MainViewModel) {
    private val _state: MutableStateFlow<ForgeUiState> get() = vm._state
    private val viewModelScope: CoroutineScope get() = vm.viewModelScope
    private fun <T : Application> getApplication(): T = vm.getApplication()
    private val preferences get() = vm.preferences
    private val vault get() = vm.vault
    private val installer get() = vm.installer
    private val checkpoints get() = vm.checkpoints
    private val checkpointHistory get() = vm.checkpointHistory
    private val toolchainInventory get() = vm.toolchainInventory
    private val systemDiagnostics get() = vm.systemDiagnostics
    private val sshHosts get() = vm.sshHosts
    private fun resolveProviderSecret(profile: ProviderProfile): String? = vm.resolveProviderSecret(profile)
    private fun configureAllProjectRoots(projects: List<Project>) = vm.configureAllProjectRoots(projects)
    private fun refreshWorkspaceFilesAndChanges(projectId: String) = vm.refreshWorkspaceFilesAndChanges(projectId)
    private fun selectProject(projectId: String) = vm.selectProject(projectId)
    private fun refreshRuntimeStatus() = vm.refreshRuntimeStatus()
    private fun stopSshForward() = vm.terminal.stopSshForward()

    private var diagnosticsJob: Job? = null
    private var toolchainJob: Job? = null

    // region Tools: Toolchains Manager

    fun refreshToolchains() {
        viewModelScope.launch {
            val entries = withContext(Dispatchers.IO) {
                runCatching { toolchainInventory.list() }.getOrDefault(emptyList())
            }
            _state.update { it.copy(toolchains = entries) }
            val updates = withContext(Dispatchers.IO) {
                runCatching { installer.checkAgentUpdates() }.getOrDefault(emptyMap())
            }
            _state.update { it.copy(toolchainUpdates = updates) }
        }
    }

    private fun canRunToolchainJob(): Boolean {
        val s = _state.value
        if (s.sessionRunning || s.toolchainBusy || s.androidBuild.phase == AndroidBuildPhase.BUILDING || s.terminalRunning) {
            _state.update { it.copy(bannerMessage = "Wait for the running agent, build, or toolchain job to finish first.") }
            return false
        }
        if (!s.runtimeInstalled) {
            _state.update { it.copy(bannerMessage = "Install the Linux runtime before managing toolchains.") }
            return false
        }
        return true
    }

    private fun runToolchainJob(failureLabel: String, block: suspend (suspend (com.nextcraft.forge.runtime.RuntimeInstallProgress) -> Unit) -> Unit) {
        if (!canRunToolchainJob()) return
        _state.update { it.copy(toolchainBusy = true) }
        toolchainJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    block { progress ->
                        _state.update { it.copy(bannerMessage = progress.message) }
                    }
                }
            }
            result.onFailure { err ->
                _state.update { it.copy(bannerMessage = "$failureLabel: ${err.message ?: err::class.simpleName}") }
            }
            _state.update { it.copy(toolchainBusy = false) }
            refreshRuntimeStatus()
            refreshToolchains()
            refreshSsh()
        }
    }

    fun installStack(stack: DevStack) = runToolchainJob("Install failed") { progress ->
        installer.ensureStackInstalled(stack, progress)
    }

    fun removeStack(stack: DevStack) = runToolchainJob("Remove failed") { progress ->
        installer.removeStack(stack, progress)
    }

    fun updateAgent(agent: AgentKind) {
        val target = _state.value.toolchainUpdates[agent] ?: return
        runToolchainJob("Update failed") { progress ->
            installer.updateAgent(agent, target.latestVersion, progress)
        }
    }

    // endregion

    // region Tools: System Diagnostics

    internal fun startDiagnosticsPolling() {
        if (diagnosticsJob?.isActive == true) return
        diagnosticsJob = viewModelScope.launch {
            while (isActive) {
                val snapshot = runCatching { systemDiagnostics.sample() }.getOrNull()
                if (snapshot != null) _state.update { it.copy(diagnostics = snapshot) }
                delay(diagnosticsIntervalMs())
            }
        }
    }

    internal fun stopDiagnosticsPolling() {
        diagnosticsJob?.cancel()
        diagnosticsJob = null
    }

    fun killChrootProcess(pid: Int, force: Boolean = false) {
        viewModelScope.launch {
            val ok = runCatching { systemDiagnostics.kill(pid, force) }.getOrDefault(false)
            if (!ok) _state.update { it.copy(bannerMessage = "Could not signal process $pid.") }
            runCatching { systemDiagnostics.sample() }.getOrNull()?.let { snap -> _state.update { it.copy(diagnostics = snap) } }
        }
    }

    // endregion

    // region Tools: Checkpoint Manager

    fun refreshCheckpointHistory() {
        val project = _state.value.activeProject ?: return
        viewModelScope.launch {
            val history = withContext(Dispatchers.IO) {
                runCatching { checkpointHistory.list(project.id) }.getOrDefault(emptyList())
            }
            _state.update { it.copy(checkpointHistory = history) }
        }
    }

    private fun runCheckpointJob(failureLabel: String, block: suspend () -> Unit) {
        if (_state.value.sessionRunning || _state.value.checkpointBusy) {
            _state.update { it.copy(bannerMessage = "Wait for the running agent session before changing snapshots.") }
            return
        }
        val project = _state.value.activeProject ?: return
        _state.update { it.copy(checkpointBusy = true) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { block() } }
            result.onFailure { err ->
                _state.update { it.copy(bannerMessage = "$failureLabel: ${err.message ?: err::class.simpleName}") }
            }
            _state.update { it.copy(checkpointBusy = false) }
            refreshCheckpointHistory()
            refreshWorkspaceFilesAndChanges(project.id)
        }
    }

    fun createSnapshot(label: String) {
        val project = _state.value.activeProject ?: return
        val cleanLabel = label.trim().ifBlank { "Manual snapshot" }
        runCheckpointJob("Snapshot failed") {
            val ws = checkpoints.ensureWorkspace(project.id)
            checkpointHistory.snapshot(project.id, ws, cleanLabel)
        }
    }

    fun restoreSnapshot(id: String) {
        val project = _state.value.activeProject ?: return
        runCheckpointJob("Restore failed") {
            val ws = checkpoints.ensureWorkspace(project.id)
            check(checkpointHistory.restore(project.id, ws, id)) { "Snapshot not found" }
            _state.update { it.copy(selectedFilePath = null, selectedFileContent = "", selectedFileDirty = false) }
        }
    }

    fun deleteSnapshot(id: String) {
        val project = _state.value.activeProject ?: return
        runCheckpointJob("Delete failed") {
            checkpointHistory.delete(project.id, id)
        }
    }

    // endregion

    // region Tools: SSH Manager

    fun refreshSsh() {
        viewModelScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                runCatching {
                    Triple(sshHosts.hosts(), sshHosts.keys(), sshHosts.isSshClientInstalled())
                }
            }
            snapshot.onSuccess { (hosts, keys, installed) ->
                _state.update { it.copy(ssh = it.ssh.copy(hosts = hosts, keys = keys, clientInstalled = installed)) }
            }.onFailure { err ->
                _state.update { it.copy(ssh = it.ssh.copy(statusMessage = err.message)) }
            }
        }
    }

    private fun sshMutation(block: () -> Unit) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching(block) }
            result.onFailure { err -> _state.update { it.copy(ssh = it.ssh.copy(statusMessage = err.message ?: "SSH change failed")) } }
            refreshSsh()
        }
    }

    fun saveSshHost(host: SshHost) = sshMutation { sshHosts.save(host) }

    fun deleteSshHost(hostId: String) {
        if (_state.value.ssh.forwardingHostId == hostId) stopSshForward()
        sshMutation { sshHosts.delete(hostId) }
    }

    fun importSshKey(label: String, pem: String) = sshMutation { sshHosts.importPrivateKey(label, pem) }

    fun deleteSshKey(keyId: String) = sshMutation { sshHosts.deleteKey(keyId) }

    fun installSshClient() = runToolchainJob("SSH client install failed") { progress ->
        installer.installAptPackages(listOf("openssh-client"), progress)
    }

    // endregion

    // region Workspace search

    private var searchJob: Job? = null

    fun searchWorkspace(query: String, regex: Boolean, caseSensitive: Boolean) {
        val project = _state.value.activeProject ?: return
        searchJob?.cancel()
        if (query.isBlank()) {
            _state.update { it.copy(searchQuery = "", searchResults = emptyList(), searchBusy = false) }
            return
        }
        _state.update { it.copy(searchQuery = query, searchBusy = true) }
        searchJob = viewModelScope.launch {
            val hits = runCatching {
                val ws = withContext(Dispatchers.IO) { checkpoints.ensureWorkspace(project.id) }
                WorkspaceSearch.search(ws, query, regex = regex, caseSensitive = caseSensitive)
            }.getOrDefault(emptyList())
            if (isActive) _state.update { it.copy(searchResults = hits, searchBusy = false) }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _state.update { it.copy(searchQuery = "", searchResults = emptyList(), searchBusy = false) }
    }

    // endregion

    // region Project export / import

    fun exportProjectZip(projectId: String, target: Uri) {
        val project = _state.value.projects.firstOrNull { it.id == projectId } ?: return
        val app = getApplication<Application>()
        viewModelScope.launch {
            _state.update { it.copy(bannerMessage = app.getString(R.string.project_export_running, project.name)) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val ws = checkpoints.ensureWorkspace(project.id)
                    val out = app.contentResolver.openOutputStream(target, "wt") ?: error("Cannot open destination")
                    out.use { ProjectArchiver.exportZip(ws, it) }
                }
            }
            _state.update {
                it.copy(
                    bannerMessage = result.fold(
                        onSuccess = { count -> app.getString(R.string.project_export_done, project.name, count) },
                        onFailure = { err -> app.getString(R.string.project_export_failed, err.message ?: "") },
                    ),
                )
            }
        }
    }

    fun importProjectZip(source: Uri) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val baseName = AttachmentStore.displayName(app, source).removeSuffix(".zip").ifBlank { "Imported project" }
            _state.update { it.copy(bannerMessage = app.getString(R.string.project_import_running, baseName)) }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val usedSlugs = _state.value.projects.map { it.slug }.toSet()
                    val baseSlug = projectSlug(baseName)
                    var slug = baseSlug
                    var suffix = 2
                    while (slug in usedSlugs) slug = "$baseSlug-${suffix++}"
                    val project = Project(
                        name = baseName.take(60),
                        description = "Imported from $baseName.zip",
                        language = "Multi-language",
                        slug = slug,
                    )
                    val ws = checkpoints.ensureWorkspace(project.id)
                    try {
                        val input = app.contentResolver.openInputStream(source) ?: error("Cannot open archive")
                        val count = input.use { ProjectArchiver.importZip(it, ws) }
                        val isAndroid = findAndroidProjectRoot(ws) != null
                        project.copy(
                            type = if (isAndroid) ProjectType.ANDROID else ProjectType.GENERAL,
                            language = if (isAndroid) "Kotlin / Android" else "Multi-language",
                        ) to count
                    } catch (error: Throwable) {
                        ws.deleteRecursively()
                        checkpoints.deleteProjectCheckpoints(project.id)
                        throw error
                    }
                }
            }
            result.onSuccess { (project, count) ->
                val updated = listOf(project) + _state.value.projects
                preferences.saveProjects(updated)
                configureAllProjectRoots(updated)
                _state.update {
                    it.copy(projects = updated, bannerMessage = app.getString(R.string.project_import_done, project.name, count))
                }
                selectProject(project.id)
            }.onFailure { err ->
                _state.update { it.copy(bannerMessage = app.getString(R.string.project_import_failed, err.message ?: "")) }
            }
        }
    }

    // endregion

    // region API key vault backup

    fun exportVaultBackup(target: Uri, passphrase: CharArray) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val blob = VaultBackup.export(vault, passphrase)
                    val out = app.contentResolver.openOutputStream(target, "wt") ?: error("Cannot open destination")
                    out.use { it.write(blob.toByteArray(Charsets.UTF_8)) }
                }
            }
            passphrase.fill('\u0000')
            _state.update {
                it.copy(
                    apiStatusMessage = result.fold(
                        onSuccess = { app.getString(R.string.backup_exported) },
                        onFailure = { err -> app.getString(R.string.backup_failed, err.message ?: "") },
                    ),
                )
            }
        }
    }

    fun importVaultBackup(source: Uri, passphrase: CharArray) {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val input = app.contentResolver.openInputStream(source) ?: error("Cannot open backup")
                    val bytes = input.use { it.readBytes() }
                    check(bytes.size <= 1024 * 1024) { "Backup file is too large" }
                    VaultBackup.import(vault, String(bytes, Charsets.UTF_8), passphrase)
                }
            }
            passphrase.fill('\u0000')
            val providerId = _state.value.providerProfile.kind.name
            _state.update {
                it.copy(
                    savedApiKeys = vault.list(providerId),
                    providerProfile = it.providerProfile.copy(hasSecret = !resolveProviderSecret(it.providerProfile).isNullOrBlank()),
                    apiStatusMessage = result.fold(
                        onSuccess = { imported -> app.getString(R.string.backup_restored, imported.providers, imported.keys) },
                        onFailure = { err -> app.getString(R.string.backup_failed, err.message ?: "") },
                    ),
                )
            }
        }
    }

    // endregion

    // region Battery saver

    fun setBatterySaver(enabled: Boolean) {
        preferences.batterySaver = enabled
        applyBatterySaver(enabled)
        _state.update { it.copy(batterySaver = enabled) }
        if (diagnosticsJob?.isActive == true) {
            stopDiagnosticsPolling()
            startDiagnosticsPolling()
        }
    }

    internal fun applyBatterySaver(enabled: Boolean) {
        AgentBridgeBase.foregroundProgressMinIntervalMs =
            if (enabled) AgentBridgeBase.DEFAULT_FOREGROUND_PROGRESS_INTERVAL_MS * 2 else AgentBridgeBase.DEFAULT_FOREGROUND_PROGRESS_INTERVAL_MS
    }

    private fun diagnosticsIntervalMs(): Long =
        if (preferences.batterySaver) DIAGNOSTICS_INTERVAL_SAVER_MS else DIAGNOSTICS_INTERVAL_MS

    // endregion

    // region Diagnostic log

    fun shareDiagnosticLog() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val report = DiagnosticReport.build(app, installer, _state.value.rootAvailable)
                    DiagnosticReport.writeToCache(app, report)
                }
            }
            result.onSuccess { file ->
                runCatching {
                    val uri = FileProvider.getUriForFile(app, "${app.packageName}.fileprovider", file)
                    val send = Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .putExtra(Intent.EXTRA_SUBJECT, file.name)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    app.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.onFailure { err ->
                    _state.update { it.copy(bannerMessage = app.getString(R.string.diagnostic_share_failed, err.message ?: "")) }
                }
            }.onFailure { err ->
                _state.update { it.copy(bannerMessage = app.getString(R.string.diagnostic_share_failed, err.message ?: "")) }
            }
        }
    }

    // endregion

    internal fun onCleared() {
        searchJob?.cancel()
    }

    private companion object {
        const val DIAGNOSTICS_INTERVAL_MS = 2_000L
        const val DIAGNOSTICS_INTERVAL_SAVER_MS = 5_000L
    }
}
