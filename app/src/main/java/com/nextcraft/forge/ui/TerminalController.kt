package com.nextcraft.forge.ui

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.nextcraft.forge.R
import com.nextcraft.forge.model.ProviderProfile
import com.nextcraft.forge.model.projectSlug
import com.nextcraft.forge.runtime.NativeSpawnProcess
import com.nextcraft.forge.runtime.RuntimeLaunchConfigBuilder
import com.nextcraft.forge.runtime.completeUtf8PrefixLength
import com.nextcraft.forge.runtime.isRootAccessAvailable
import com.nextcraft.forge.runtime.readBoundedText
import java.io.File
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class TerminalController(private val vm: MainViewModel) {
    private val _state: MutableStateFlow<ForgeUiState> get() = vm._state
    private val viewModelScope: CoroutineScope get() = vm.viewModelScope
    private fun <T : Application> getApplication(): T = vm.getApplication()
    private val preferences get() = vm.preferences
    private val vault get() = vm.vault
    private val installer get() = vm.installer
    private val checkpoints get() = vm.checkpoints
    private val sshHosts get() = vm.sshHosts
    private fun resolveProviderSecret(profile: ProviderProfile): String? = vm.resolveProviderSecret(profile)
    private fun isProjectSessionRunning(projectId: String): Boolean = vm.isProjectSessionRunning(projectId)
    private fun refreshWorkspaceFilesAndChanges(projectId: String) = vm.refreshWorkspaceFilesAndChanges(projectId)
    private inline fun <T> withGitHubCredentials(token: String, login: String, block: () -> T): T =
        vm.github.withGitHubCredentials(token, login, block)

    private var activeTerminalJob: Job? = null
    @Volatile private var activeTerminalProcess: Process? = null
    @Volatile private var activeSshForwardProcess: Process? = null
    private var sshForwardJob: Job? = null

    fun runTerminalCommand(command: String) {
        val cleanCmd = command.trim()
        val interactive = activeTerminalProcess
        if (_state.value.terminalInteractive && interactive != null && interactive.isAlive) {
            // An interactive PTY session (SSH) owns the terminal: forward the line to its stdin.
            runCatching {
                interactive.outputStream.write((command + "\n").toByteArray(Charsets.UTF_8))
                interactive.outputStream.flush()
            }
            return
        }
        if (cleanCmd.isEmpty() || _state.value.terminalRunning) return
        val project = _state.value.activeProject ?: return
        _state.update {
            it.copy(
                terminalRunning = true,
                terminalOutput = (it.terminalOutput + "\n$ $cleanCmd\n").takeLast(MAX_TERMINAL_CHARS),
            )
        }
        activeTerminalJob = viewModelScope.launch {
            var streamedAny = false
            var output = ""
            try {
                output = withContext(Dispatchers.IO) {
                    val ws = checkpoints.ensureWorkspace(project.id)
                    runCommandInChrootOrHost(project.slug, ws, cleanCmd) { chunkText ->
                        streamedAny = true
                        _state.update { current ->
                            current.copy(
                                terminalOutput = (current.terminalOutput + chunkText).takeLast(MAX_TERMINAL_CHARS),
                            )
                        }
                    }
                }
            } finally {
                activeTerminalProcess = null
                _state.update {
                    val tail = if (streamedAny) "\n" else (output + "\n")
                    it.copy(
                        terminalRunning = false,
                        terminalOutput = (it.terminalOutput + tail).takeLast(MAX_TERMINAL_CHARS),
                    )
                }
                refreshWorkspaceFilesAndChanges(project.id)
            }
        }
    }

    fun stopTerminal() {
        // Killing the process unblocks the reader thread; the job then ends normally and resets state.
        activeTerminalProcess?.destroyForcibly()
        activeTerminalJob?.cancel()
    }

    fun clearTerminal() {
        _state.update { it.copy(terminalOutput = "") }
    }

    private fun activeChrootEnvironment(): Map<String, String> {
        val profile = _state.value.providerProfile
        val secret = resolveProviderSecret(profile)
        return runCatching {
            RuntimeLaunchConfigBuilder.build(profile, secret).environment
        }.getOrDefault(emptyMap())
    }

    /** Starts [command] inside the Root Chroot (Gradle, git and the SDK only exist there). */
    internal fun startChrootProcess(projectSlug: String, workspaceDir: File, command: String): Process {
        check(installer.isInstalled() && isRootAccessAvailable()) {
            "Root access and the installed Linux runtime are required to run this."
        }
        val (envFile, suCommand) = installer.prepareChrootShellCommand(
            workspace = workspaceDir,
            guestWorkspacePath = "/workspace/$projectSlug",
            environment = activeChrootEnvironment(),
            guestCommand = listOf("/bin/bash", "-lc", command),
        )
        return try {
            ProcessBuilder("su", "-mm", "-c", suCommand).redirectErrorStream(true).start()
        } catch (error: Throwable) {
            envFile.delete()
            throw error
        }
    }

    internal fun runCommandInChrootOrHost(
        projectSlug: String,
        workspaceDir: File,
        command: String,
        onOutputChunk: ((String) -> Unit)? = null,
    ): String {
        return runCatching {
            val process = if (installer.isInstalled() && isRootAccessAvailable()) {
                val installed = installer.installedRuntime()
                if (installed.chrootExec.isFile) {
                    startChrootProcess(projectSlug, workspaceDir, command)
                } else {
                    ProcessBuilder("sh", "-c", command).directory(workspaceDir).redirectErrorStream(true).start()
                }
            } else {
                ProcessBuilder("sh", "-c", command).directory(workspaceDir).redirectErrorStream(true).start()
            }
            activeTerminalProcess = process
            val buffer = StringBuilder()
            // The read below blocks until the command closes its output, so the timeout needs its own watchdog.
            val timedOut = java.util.concurrent.atomic.AtomicBoolean(false)
            val watchdog = Thread({
                try {
                    if (!process.waitFor(60, TimeUnit.SECONDS)) {
                        timedOut.set(true)
                        process.destroyForcibly()
                    }
                } catch (_: InterruptedException) {
                }
            }, "forge-terminal-watchdog").apply { isDaemon = true; start() }
            process.inputStream.bufferedReader().use { reader ->
                val chunk = CharArray(4096)
                while (buffer.length < MAX_TERMINAL_CHARS) {
                    val n = reader.read(chunk)
                    if (n <= 0) break
                    val text = String(chunk, 0, n)
                    buffer.append(text)
                    onOutputChunk?.invoke(text)
                }
            }
            if (process.isAlive && !timedOut.get()) process.destroyForcibly()
            watchdog.interrupt()
            if (timedOut.get()) {
                val timeoutNotice = "\n[Command timed out after 60s]"
                buffer.append(timeoutNotice)
                onOutputChunk?.invoke(timeoutNotice)
            }
            buffer.toString()
        }.getOrElse { "Error executing command: ${it.message}" }
    }

    /** Runs [command] like the terminal, but throws when it exits non-zero (the plain runner only returns output). */
    internal fun runCheckedCommand(projectSlug: String, workspaceDir: File, command: String): String {
        val wrapped = "{ $command\n} 2>&1; printf '\\n[forge-exit:%s]\\n' \"\$?\""
        val raw = runCommandInChrootOrHost(projectSlug, workspaceDir, wrapped)
        val code = Regex("""\[forge-exit:(\d+)]\s*$""").find(raw)?.groupValues?.get(1)?.toIntOrNull()
        val output = raw.substringBeforeLast("[forge-exit:").trim()
        check(code == 0) {
            output.lineSequence().filter { it.isNotBlank() }.toList().takeLast(3).joinToString(" | ")
                .ifBlank { "command failed" }
        }
        return output
    }

    fun connectSsh(hostId: String) {
        val host = _state.value.ssh.hosts.firstOrNull { it.id == hostId } ?: return
        if (!_state.value.ssh.clientInstalled) {
            _state.update { it.copy(ssh = it.ssh.copy(statusMessage = "Install the SSH client first.")) }
            return
        }
        val command = runCatching { sshHosts.buildSshCommand(host) }.getOrElse { err ->
            _state.update { it.copy(ssh = it.ssh.copy(statusMessage = err.message)) }
            return
        }
        runInteractiveInChroot(command)
    }

    fun startSshForward(hostId: String) {
        val host = _state.value.ssh.hosts.firstOrNull { it.id == hostId } ?: return
        if (activeSshForwardProcess?.isAlive == true) stopSshForward()
        if (host.portForwards.isEmpty()) {
            _state.update { it.copy(ssh = it.ssh.copy(statusMessage = "Add at least one port forward to this host.")) }
            return
        }
        val project = _state.value.activeProject ?: return
        val command = runCatching { sshHosts.buildForwardOnlyCommand(host) }.getOrElse { err ->
            _state.update { it.copy(ssh = it.ssh.copy(statusMessage = err.message)) }
            return
        }
        _state.update { it.copy(ssh = it.ssh.copy(forwardingHostId = host.id, statusMessage = null)) }
        sshForwardJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    check(installer.isInstalled() && isRootAccessAvailable()) { "Root access and the Linux runtime are required." }
                    val installed = installer.installedRuntime()
                    val ws = checkpoints.ensureWorkspace(project.id)
                    val process = installer.process(
                        installed.chrootExec,
                        installed.rootfs,
                        ws,
                        emptyMap(),
                        command,
                        guestWorkspacePath = "/workspace/${project.slug}",
                        emulateHardLinks = false,
                    )
                    activeSshForwardProcess = process
                    val exit = process.waitFor()
                    val output = (process as? NativeSpawnProcess)?.outputFile?.let { file ->
                        runCatching { file.readBoundedText(4 * 1024) }.getOrNull().also { file.delete() }
                    }
                    exit to output.orEmpty()
                }
            }
            activeSshForwardProcess = null
            val note = result.fold(
                onSuccess = { (exit, output) ->
                    if (exit == 0) "Port forward closed." else "Port forward ended (exit $exit): ${output.lines().lastOrNull { it.isNotBlank() }.orEmpty()}".trim()
                },
                onFailure = { err -> err.message ?: "Port forward failed" },
            )
            _state.update { it.copy(ssh = it.ssh.copy(forwardingHostId = null, statusMessage = note)) }
        }
    }

    fun stopSshForward() {
        activeSshForwardProcess?.destroy()
        activeSshForwardProcess?.let { process ->
            Thread({
                runCatching {
                    Thread.sleep(500)
                    if (process.isAlive) process.destroyForcibly()
                }
            }, "forge-ssh-forward-stop").apply { isDaemon = true; start() }
        }
    }

    /**
     * Runs [command] on a PTY inside the chroot and streams it to the terminal tab; the terminal
     * input line is forwarded to its stdin until it exits.
     */
    fun runInteractiveInChroot(command: List<String>) {
        if (command.isEmpty() || _state.value.terminalRunning) return
        val project = _state.value.activeProject ?: return
        val display = command.joinToString(" ")
        _state.update {
            it.copy(
                activeTab = ForgeTab.TERMINAL,
                terminalRunning = true,
                terminalInteractive = true,
                terminalOutput = (it.terminalOutput + "\n$ $display\n").takeLast(MAX_TERMINAL_CHARS),
            )
        }
        fun appendOutput(text: String) {
            if (text.isEmpty()) return
            _state.update { current -> current.copy(terminalOutput = (current.terminalOutput + text).takeLast(MAX_TERMINAL_CHARS)) }
        }
        activeTerminalJob = viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    check(installer.isInstalled() && isRootAccessAvailable()) {
                        "Root access and the installed Linux runtime are required to run this."
                    }
                    val installed = installer.installedRuntime()
                    val ws = checkpoints.ensureWorkspace(project.id)
                    val process = installer.process(
                        installed.chrootExec,
                        installed.rootfs,
                        ws,
                        emptyMap(),
                        command,
                        guestWorkspacePath = "/workspace/${project.slug}",
                        emulateHardLinks = false,
                        pseudoTerminal = true,
                    )
                    activeTerminalProcess = process
                    val native = process as? NativeSpawnProcess
                    if (native == null) {
                        process.inputStream.bufferedReader().use { reader ->
                            val chunk = CharArray(4096)
                            while (true) {
                                val n = reader.read(chunk)
                                if (n <= 0) break
                                appendOutput(String(chunk, 0, n))
                            }
                        }
                        return@withContext
                    }
                    try {
                        // Partial lines (prompts, password requests) must show up immediately, so poll raw bytes.
                        RandomAccessFile(native.outputFile, "r").use { file ->
                            var offset = 0L
                            while (isActive && (process.isAlive || native.outputFile.length() > offset)) {
                                val available = native.outputFile.length() - offset
                                if (available <= 0L) {
                                    delay(80)
                                    continue
                                }
                                val bytes = ByteArray(minOf(available, 16L * 1024).toInt())
                                file.seek(offset)
                                val count = file.read(bytes)
                                if (count <= 0) continue
                                val usable = completeUtf8PrefixLength(bytes, count).let { if (it > 0 || !process.isAlive) (if (it > 0) it else count) else 0 }
                                if (usable == 0) {
                                    delay(40)
                                    continue
                                }
                                offset += usable
                                appendOutput(String(bytes, 0, usable, Charsets.UTF_8).replace("\r\n", "\n"))
                            }
                        }
                    } finally {
                        runCatching { native.outputFile.delete() }
                    }
                }
            } catch (error: Throwable) {
                if (error !is CancellationException) appendOutput("\nError: ${error.message}\n")
            } finally {
                activeTerminalProcess?.let { if (it.isAlive) runCatching { it.destroyForcibly() } }
                activeTerminalProcess = null
                _state.update { it.copy(terminalRunning = false, terminalInteractive = false, terminalOutput = (it.terminalOutput + "\n").takeLast(MAX_TERMINAL_CHARS)) }
            }
        }
    }

    // region Tools: Git panel

    private fun gitIdentity(): Pair<String, String> {
        val login = _state.value.githubLogin.ifBlank { preferences.githubLogin }
        return if (login.isBlank()) "NextCraft Forge" to "forge@localhost" else login to "$login@users.noreply.github.com"
    }

    fun refreshGit() {
        val project = _state.value.activeProject ?: return
        _state.update { it.copy(git = it.git.copy(busy = true)) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val ws = checkpoints.ensureWorkspace(project.id)
                    GitPanelController.parseOverview(runCommandInChrootOrHost(project.slug, ws, GitPanelController.overviewCommand()))
                }
            }
            _state.update { current ->
                current.copy(
                    git = result.fold(
                        onSuccess = { parsed -> parsed.copy(statusMessage = current.git.statusMessage) },
                        onFailure = { err -> current.git.copy(loaded = true, busy = false, statusMessage = err.message) },
                    ),
                )
            }
        }
    }

    private fun gitAction(
        successNote: String? = null,
        withCredentials: Boolean = false,
        command: () -> String,
    ) {
        val project = _state.value.activeProject ?: return
        if (_state.value.git.busy) return
        if (isProjectSessionRunning(project.id)) {
            _state.update { it.copy(bannerMessage = getApplication<Application>().getString(R.string.git_blocked_session_running)) }
            return
        }
        val cmd = runCatching(command).getOrElse { err ->
            _state.update { it.copy(git = it.git.copy(statusMessage = err.message)) }
            return
        }
        _state.update { it.copy(git = it.git.copy(busy = true, statusMessage = null)) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val ws = checkpoints.ensureWorkspace(project.id)
                    if (withCredentials) {
                        val token = vault.get("GITHUB_TOKEN").orEmpty()
                        check(token.isNotBlank()) { getApplication<Application>().getString(R.string.git_connect_github_first) }
                        val login = gitIdentity().first
                        withGitHubCredentials(token, login) { runCheckedCommand(project.slug, ws, cmd) }
                    } else {
                        runCheckedCommand(project.slug, ws, cmd)
                    }
                }
            }
            val note = result.fold(
                onSuccess = { output -> successNote ?: output.lineSequence().lastOrNull { it.isNotBlank() } },
                onFailure = { err -> err.message ?: err::class.simpleName },
            )
            _state.update { it.copy(git = it.git.copy(busy = false, statusMessage = note)) }
            refreshGit()
            refreshWorkspaceFilesAndChanges(project.id)
        }
    }

    fun gitInit() = gitAction { GitPanelController.initCommand() }

    fun gitStage(path: String) = gitAction { GitPanelController.stageCommand(path) }

    fun gitUnstage(path: String) = gitAction { GitPanelController.unstageCommand(path) }

    fun gitStageAll() = gitAction { GitPanelController.stageAllCommand() }

    fun gitUnstageAll() = gitAction { GitPanelController.unstageAllCommand() }

    fun gitDiscard(entry: GitFileEntry) = gitAction { GitPanelController.discardCommand(entry) }

    fun gitCommit(message: String) {
        val (name, email) = gitIdentity()
        gitAction(successNote = getApplication<Application>().getString(R.string.git_commit_done)) {
            GitPanelController.commitCommand(message.trim(), name, email)
        }
    }

    fun gitCheckout(branch: String) = gitAction { GitPanelController.checkoutCommand(branch.trim()) }

    fun gitCreateBranch(branch: String) = gitAction { GitPanelController.createBranchCommand(branch.trim()) }

    fun gitPush() = gitAction(
        successNote = getApplication<Application>().getString(R.string.git_push_done),
        withCredentials = true,
    ) { GitPanelController.pushCommand(_state.value.git.branch) }

    fun gitPull() = gitAction(
        successNote = getApplication<Application>().getString(R.string.git_pull_done),
        withCredentials = true,
    ) { GitPanelController.pullCommand(_state.value.git.branch) }

    // endregion

    internal fun onCleared() {
        activeSshForwardProcess?.destroyForcibly()
        activeTerminalProcess?.destroyForcibly()
    }

    private companion object {
        const val MAX_TERMINAL_CHARS = 128 * 1024
    }
}
