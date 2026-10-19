package com.nextcraft.forge.ui

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.nextcraft.forge.model.Project
import com.nextcraft.forge.model.ProjectType
import com.nextcraft.forge.model.projectSlug
import com.nextcraft.forge.network.GitHubClient
import com.nextcraft.forge.network.GitHubRepository
import com.nextcraft.forge.network.GitHubTokenPoll
import com.nextcraft.forge.network.parseGitHubRemote
import com.nextcraft.forge.runtime.findAndroidProjectRoot
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class GitHubController(private val vm: MainViewModel) {
    private val _state: MutableStateFlow<ForgeUiState> get() = vm._state
    private val viewModelScope: CoroutineScope get() = vm.viewModelScope
    private fun <T : Application> getApplication(): T = vm.getApplication()
    private val preferences get() = vm.preferences
    private val vault get() = vm.vault
    internal val installer get() = vm.installer
    private val checkpoints get() = vm.checkpoints
    private val githubClient get() = vm.githubClient
    private val actionsClient get() = vm.actionsClient
    private fun configureAllProjectRoots(projects: List<Project>) = vm.configureAllProjectRoots(projects)
    private fun refreshWorkspaceFilesAndChanges(projectId: String) = vm.refreshWorkspaceFilesAndChanges(projectId)
    private fun selectProject(projectId: String) = vm.selectProject(projectId)
    private fun runCommandInChrootOrHost(
        projectSlug: String,
        workspaceDir: File,
        command: String,
        onOutputChunk: ((String) -> Unit)? = null,
    ): String = vm.terminal.runCommandInChrootOrHost(projectSlug, workspaceDir, command, onOutputChunk)
    private fun runCheckedCommand(projectSlug: String, workspaceDir: File, command: String): String =
        vm.terminal.runCheckedCommand(projectSlug, workspaceDir, command)

    private var githubPollJob: Job? = null

    /**
     * Writes the GitHub PAT into the rootfs only while [block] runs, then removes it so agent
     * processes (UID 0 inside the chroot) can never read a long-lived credential file.
     */
    internal inline fun <T> withGitHubCredentials(token: String, login: String, block: () -> T): T {
        installer.configureGitHubAccess(token, login)
        return try {
            block()
        } finally {
            installer.clearGitHubAccess()
        }
    }

    fun startGitHubDeviceSignIn(clientId: String = "") {
        val cleanId = clientId.trim().ifEmpty { GitHubClient.DEFAULT_CLIENT_ID }
        githubPollJob?.cancel()
        githubPollJob = viewModelScope.launch {
            _state.update { it.copy(githubStatusMessage = "Requesting GitHub device code…") }
            val codeResult = withContext(Dispatchers.IO) {
                runCatching { githubClient.requestDeviceCode(cleanId) }
            }
            val deviceCode = codeResult.getOrElse { err ->
                _state.update { it.copy(githubStatusMessage = "GitHub error: ${err.message}") }
                return@launch
            }
            _state.update {
                it.copy(
                    githubDeviceCode = deviceCode,
                    githubStatusMessage = "Open ${deviceCode.verificationUri} and enter code: ${deviceCode.userCode}",
                )
            }
            var intervalSec = deviceCode.intervalSeconds
            val deadline = System.currentTimeMillis() + deviceCode.expiresInSeconds * 1000L
            while (isActive && System.currentTimeMillis() < deadline) {
                delay(intervalSec * 1000L)
                val poll = withContext(Dispatchers.IO) {
                    runCatching { githubClient.pollDeviceToken(cleanId, deviceCode.deviceCode) }
                        .getOrElse { GitHubTokenPoll.Failure(it.message ?: "Polling error") }
                }
                when (poll) {
                    is GitHubTokenPoll.Success -> {
                        runCatching { vault.put("GITHUB_TOKEN", poll.accessToken) }.onFailure { err ->
                            _state.update {
                                it.copy(githubDeviceCode = null, githubStatusMessage = err.message ?: "Could not store the GitHub token.")
                            }
                            return@launch
                        }
                        val account = withContext(Dispatchers.IO) {
                            runCatching { githubClient.account(poll.accessToken) }.getOrNull()
                        }
                        val login = account?.login.orEmpty()
                        withContext(Dispatchers.IO) {
                            // Credentials are provisioned into the rootfs only during sync/push.
                            runCatching { installer.clearGitHubAccess() }
                        }
                        val repos = withContext(Dispatchers.IO) {
                            runCatching { githubClient.repositories(poll.accessToken) }.getOrDefault(emptyList())
                        }
                        preferences.githubLogin = login
                        _state.update {
                            it.copy(
                                githubLogin = login,
                                githubDeviceCode = null,
                                githubRepos = repos,
                                githubStatusMessage = "Connected as @${login.ifBlank { "GitHub user" }} (${repos.size} repos)",
                            )
                        }
                        return@launch
                    }
                    is GitHubTokenPoll.Pending -> {
                        if (poll.slowDown) intervalSec += 5L
                    }
                    is GitHubTokenPoll.Failure -> {
                        _state.update { it.copy(githubDeviceCode = null, githubStatusMessage = poll.message) }
                        return@launch
                    }
                }
            }
            // Reached only when the code expired (success/failure return above); don't leave a dead code on screen.
            if (isActive) {
                _state.update {
                    it.copy(
                        githubDeviceCode = null,
                        githubStatusMessage = "GitHub sign-in code expired. Start sign-in again.",
                    )
                }
            }
        }
    }

    fun connectGitHubWithToken(personalAccessToken: String) {
        val cleanToken = personalAccessToken.trim()
        if (cleanToken.isEmpty()) {
            _state.update { it.copy(githubStatusMessage = "Enter a valid GitHub Personal Access Token.") }
            return
        }
        githubPollJob?.cancel()
        viewModelScope.launch {
            _state.update { it.copy(githubStatusMessage = "Verifying GitHub token…") }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val account = githubClient.account(cleanToken)
                    val repos = githubClient.repositories(cleanToken)
                    // Credentials are provisioned into the rootfs only during sync/push.
                    runCatching { installer.clearGitHubAccess() }
                    account to repos
                }
            }
            result.onSuccess { (account, repos) ->
                runCatching { vault.put("GITHUB_TOKEN", cleanToken) }.onFailure { err ->
                    _state.update { it.copy(githubStatusMessage = err.message ?: "Could not store the GitHub token.") }
                    return@onSuccess
                }
                preferences.githubLogin = account.login
                _state.update {
                    it.copy(
                        githubLogin = account.login,
                        githubDeviceCode = null,
                        githubRepos = repos,
                        githubStatusMessage = "Connected as @${account.login} (${repos.size} repos)",
                    )
                }
            }.onFailure { err ->
                _state.update { it.copy(githubStatusMessage = "GitHub token error: ${err.message}") }
            }
        }
    }

    fun disconnectGitHub() {
        githubPollJob?.cancel()
        vault.remove("GITHUB_TOKEN")
        preferences.githubLogin = ""
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { installer.clearGitHubAccess() }
        }
        _state.update {
            it.copy(
                githubLogin = "",
                githubDeviceCode = null,
                githubRepos = emptyList(),
                githubStatusMessage = "Disconnected from GitHub.",
            )
        }
    }

    fun createGitHubRepository(
        name: String,
        description: String = "",
        isPrivate: Boolean = true,
        pushActiveProject: Boolean = true,
    ) {
        val token = vault.get("GITHUB_TOKEN").orEmpty()
        if (token.isBlank()) {
            _state.update { it.copy(githubStatusMessage = "Sign in to GitHub first before creating a repository.") }
            return
        }
        val cleanName = name.trim()
        if (cleanName.isEmpty()) {
            _state.update { it.copy(githubStatusMessage = "Repository name cannot be empty.") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(githubStatusMessage = "Creating GitHub repository '$cleanName'…") }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val repo = githubClient.createRepository(
                        token = token,
                        name = cleanName,
                        description = description,
                        private = isPrivate,
                        autoInit = false,
                    )
                    val project = _state.value.activeProject
                    var pushError: Throwable? = null
                    if (pushActiveProject && project != null) {
                        val ws = checkpoints.ensureWorkspace(project.id)
                        val login = _state.value.githubLogin.ifBlank { "x-access-token" }
                        val pushCmd = buildString {
                            append("git init -b main && ")
                            append("git config user.name ${shellSingleQuote(login)} && ")
                            append("git config user.email ${shellSingleQuote("$login@users.noreply.github.com")} && ")
                            append("(git remote remove origin 2>/dev/null || true) && ")
                            append("git remote add origin ${shellSingleQuote(repo.cloneUrl)} && ")
                            append("git add -A && ")
                            append("(git diff --cached --quiet || git commit -m 'Initial commit from NextCraft Forge') && ")
                            append("git push -u origin main")
                        }
                        pushError = runCatching {
                            withGitHubCredentials(token, login) {
                                runCheckedCommand(project.slug, ws, pushCmd)
                            }
                        }.exceptionOrNull()
                    }
                    val updatedRepos = runCatching { githubClient.repositories(token) }.getOrDefault(listOf(repo))
                    Triple(repo, updatedRepos, pushError)
                }
            }
            result.onSuccess { (repo, updatedRepos, pushError) ->
                _state.update {
                    it.copy(
                        githubRepos = updatedRepos,
                        githubStatusMessage = if (pushError == null) {
                            "Created & pushed to ${repo.fullName}"
                        } else {
                            "Created ${repo.fullName}, but the initial push failed: ${pushError.message}"
                        },
                    )
                }
            }.onFailure { err ->
                _state.update { it.copy(githubStatusMessage = "Failed to create repository: ${err.message}") }
            }
        }
    }

    fun syncProjectWithGitHub(
        projectId: String = _state.value.activeProjectId,
        commitMessage: String = "Sync project changes from NextCraft Forge",
    ) {
        val token = vault.get("GITHUB_TOKEN").orEmpty()
        if (token.isBlank()) return
        val project = _state.value.projects.firstOrNull { it.id == projectId } ?: _state.value.activeProject ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val ws = checkpoints.ensureWorkspace(project.id)
                val gitDir = File(ws, ".git")
                if (!gitDir.isDirectory) return@runCatching
                val login = _state.value.githubLogin.ifBlank { preferences.githubLogin }.ifBlank { "x-access-token" }
                val syncCmd = buildString {
                    append("if git remote get-url origin >/dev/null 2>&1; then ")
                    append("git config user.name ${shellSingleQuote(login)} && ")
                    append("git config user.email ${shellSingleQuote("$login@users.noreply.github.com")} && ")
                    append("git add -A && ")
                    append("(git diff --cached --quiet || git commit -m ${shellSingleQuote(commitMessage)}) && ")
                    append("git pull --rebase --autostash origin main >/dev/null 2>&1 || git rebase --abort >/dev/null 2>&1; ")
                    append("git push -u origin HEAD:main; ")
                    append("fi")
                }
                withGitHubCredentials(token, login) {
                    runCommandInChrootOrHost(project.slug, ws, syncCmd)
                }
            }
        }
    }

    fun pullProjectFromGitHub(projectId: String = _state.value.activeProjectId) {
        val token = vault.get("GITHUB_TOKEN").orEmpty()
        if (token.isBlank()) {
            _state.update { it.copy(githubStatusMessage = "Connect GitHub first before pulling.") }
            return
        }
        val project = _state.value.projects.firstOrNull { it.id == projectId } ?: _state.value.activeProject ?: return
        viewModelScope.launch {
            _state.update { it.copy(githubStatusMessage = "Pulling latest changes from GitHub for ${project.name}…") }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val ws = checkpoints.ensureWorkspace(project.id)
                    val login = _state.value.githubLogin.ifBlank { preferences.githubLogin }.ifBlank { "x-access-token" }
                    val pullCmd = buildString {
                        append("if git remote get-url origin >/dev/null 2>&1; then ")
                        append("git fetch origin && (git pull --rebase --autostash origin main || ")
                        append("{ git rebase --abort 2>/dev/null; false; }); ")
                        append("else echo 'No origin remote configured in workspace'; fi")
                    }
                    withGitHubCredentials(token, login) {
                        runCheckedCommand(project.slug, ws, pullCmd)
                    }
                }
            }
            refreshWorkspaceFilesAndChanges(project.id)
            _state.update {
                it.copy(
                    githubStatusMessage = result.fold(
                        onSuccess = { out -> "Pulled from GitHub: ${out.lines().lastOrNull { l -> l.isNotBlank() } ?: "up to date"}" },
                        onFailure = { err -> "GitHub pull failed: ${err.message}" },
                    ),
                )
            }
        }
    }

    fun cloneGitHubRepository(repo: GitHubRepository) {
        val token = vault.get("GITHUB_TOKEN").orEmpty()
        viewModelScope.launch {
            _state.update { it.copy(githubStatusMessage = "Cloning ${repo.fullName}…") }
            val clonedProject = withContext(Dispatchers.IO) {
                runCatching {
                    val cleanName = repo.fullName.substringAfterLast('/').ifBlank { repo.fullName }
                    val usedSlugs = _state.value.projects.map { it.slug }.toSet()
                    val baseSlug = projectSlug(cleanName)
                    var slug = baseSlug
                    var suffix = 2
                    while (slug in usedSlugs) slug = "$baseSlug-${suffix++}"
                    val project = Project(
                        name = cleanName,
                        description = repo.description.ifBlank { "Cloned from ${repo.fullName}" },
                        language = "Git Repository",
                        slug = slug,
                    )
                    val ws = checkpoints.ensureWorkspace(project.id)
                    ws.listFiles()?.forEach { it.deleteRecursively() }
                    val login = _state.value.githubLogin.ifBlank { preferences.githubLogin }.ifBlank { "x-access-token" }
                    val cloneCmd = "git clone ${shellSingleQuote(repo.cloneUrl)} ."
                    try {
                        if (token.isNotBlank()) {
                            withGitHubCredentials(token, login) {
                                runCheckedCommand(project.slug, ws, cloneCmd)
                            }
                        } else {
                            runCheckedCommand(project.slug, ws, cloneCmd)
                        }
                    } catch (error: Throwable) {
                        // The project is never saved on failure; don't leave its workspace behind on disk.
                        ws.deleteRecursively()
                        checkpoints.deleteProjectCheckpoints(project.id)
                        throw error
                    }
                    val isAndroidRepo = findAndroidProjectRoot(ws) != null
                    project.copy(
                        type = if (isAndroidRepo) ProjectType.ANDROID else ProjectType.GENERAL,
                        language = if (isAndroidRepo) "Kotlin / Android" else "Git Repository",
                    )
                }
            }
            clonedProject.onSuccess { newProject ->
                val updated = listOf(newProject) + _state.value.projects
                preferences.saveProjects(updated)
                configureAllProjectRoots(updated)
                _state.update {
                    it.copy(
                        projects = updated,
                        githubStatusMessage = "Cloned ${repo.fullName} into workspace '${newProject.name}'",
                    )
                }
                selectProject(newProject.id)
            }.onFailure { err ->
                _state.update { it.copy(githubStatusMessage = "Clone failed: ${err.message}") }
            }
        }
    }

    // region Tools: CI/CD Pipeline

    private fun cicdToken(): String? = vault.get("GITHUB_TOKEN")?.trim()?.takeIf { it.isNotEmpty() }

    private fun cicdError(err: Throwable): String = err.message ?: err::class.simpleName ?: "GitHub request failed"

    fun refreshCicd() {
        val project = _state.value.activeProject ?: return
        val token = cicdToken()
        if (token == null) {
            _state.update { it.copy(cicd = CicdUiState(resolved = true, statusMessage = "Connect GitHub in Settings to use CI/CD.")) }
            return
        }
        _state.update { it.copy(cicd = it.cicd.copy(busy = true, statusMessage = null)) }
        viewModelScope.launch {
            val resolved = withContext(Dispatchers.IO) {
                runCatching {
                    val ws = checkpoints.ensureWorkspace(project.id)
                    val raw = runCommandInChrootOrHost(
                        project.slug,
                        ws,
                        "git remote get-url origin 2>/dev/null; echo '[forge-branch]'; git rev-parse --abbrev-ref HEAD 2>/dev/null",
                    )
                    val remote = raw.substringBefore("[forge-branch]").trim().lineSequence().lastOrNull { it.isNotBlank() }.orEmpty()
                    val branch = raw.substringAfter("[forge-branch]", "").trim().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
                    Triple(parseGitHubRemote(remote), branch, remote)
                }
            }
            val (ownerRepo, branch, remote) = resolved.getOrElse { err ->
                _state.update { it.copy(cicd = CicdUiState(resolved = true, statusMessage = "Could not read git remote: ${cicdError(err)}")) }
                return@launch
            }
            if (ownerRepo == null) {
                val note = if (remote.isBlank()) "This workspace has no git remote named origin." else "origin is not a GitHub repository: $remote"
                _state.update { it.copy(cicd = CicdUiState(resolved = true, statusMessage = note)) }
                return@launch
            }
            val (owner, repo) = ownerRepo
            val runs = withContext(Dispatchers.IO) { runCatching { actionsClient.listRuns(token, owner, repo) } }
            _state.update {
                it.copy(
                    cicd = CicdUiState(
                        resolved = true,
                        owner = owner,
                        repo = repo,
                        branch = branch,
                        runs = runs.getOrDefault(emptyList()),
                        statusMessage = runs.exceptionOrNull()?.let(::cicdError),
                    ),
                )
            }
        }
    }

    private fun cicdAction(block: suspend (token: String, owner: String, repo: String) -> String?) {
        val cicd = _state.value.cicd
        val token = cicdToken() ?: return
        val owner = cicd.owner ?: return
        val repo = cicd.repo ?: return
        _state.update { it.copy(cicd = it.cicd.copy(busy = true)) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { block(token, owner, repo) } }
            _state.update {
                it.copy(cicd = it.cicd.copy(busy = false, statusMessage = result.fold({ note -> note }, ::cicdError)))
            }
        }
    }

    fun loadWorkflows() = cicdAction { token, owner, repo ->
        val workflows = actionsClient.listWorkflows(token, owner, repo)
        _state.update { it.copy(cicd = it.cicd.copy(workflows = workflows)) }
        null
    }

    fun dispatchWorkflow(workflowId: Long) = cicdAction { token, owner, repo ->
        val ref = _state.value.cicd.branch.ifBlank { "main" }
        actionsClient.dispatch(token, owner, repo, workflowId, ref)
        val runs = actionsClient.listRuns(token, owner, repo)
        _state.update { it.copy(cicd = it.cicd.copy(runs = runs)) }
        "Workflow dispatched on $ref."
    }

    fun rerunWorkflowRun(runId: Long) = cicdAction { token, owner, repo ->
        actionsClient.rerun(token, owner, repo, runId)
        val runs = actionsClient.listRuns(token, owner, repo)
        _state.update { it.copy(cicd = it.cicd.copy(runs = runs)) }
        "Re-run requested."
    }

    fun cancelWorkflowRun(runId: Long) = cicdAction { token, owner, repo ->
        actionsClient.cancel(token, owner, repo, runId)
        val runs = actionsClient.listRuns(token, owner, repo)
        _state.update { it.copy(cicd = it.cicd.copy(runs = runs)) }
        "Cancel requested."
    }

    fun loadWorkflowJobs(runId: Long) = cicdAction { token, owner, repo ->
        val jobs = actionsClient.listJobs(token, owner, repo, runId)
        _state.update { it.copy(cicd = it.cicd.copy(selectedRunId = runId, jobs = jobs, jobLog = null, selectedJobId = null)) }
        null
    }

    fun loadJobLog(jobId: Long) = cicdAction { token, owner, repo ->
        val log = actionsClient.jobLog(token, owner, repo, jobId)
        _state.update { it.copy(cicd = it.cicd.copy(selectedJobId = jobId, jobLog = log.takeLast(MAX_JOB_LOG_CHARS))) }
        null
    }

    fun closeWorkflowJobs() {
        _state.update { it.copy(cicd = it.cicd.copy(selectedRunId = null, jobs = emptyList(), jobLog = null, selectedJobId = null)) }
    }

    // endregion

    private companion object {
        const val MAX_JOB_LOG_CHARS = 64 * 1024
    }
}
