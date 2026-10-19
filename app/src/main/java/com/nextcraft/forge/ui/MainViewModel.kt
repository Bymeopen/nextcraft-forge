package com.nextcraft.forge.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.nextcraft.forge.R
import com.nextcraft.forge.data.ApiKeyVault
import com.nextcraft.forge.data.AppPreferences
import com.nextcraft.forge.model.ActivityItem
import com.nextcraft.forge.model.AgentKind
import com.nextcraft.forge.model.AndroidTemplate
import com.nextcraft.forge.model.ChatAttachment
import com.nextcraft.forge.model.ChatMessage
import com.nextcraft.forge.model.DevStack
import com.nextcraft.forge.model.Project
import com.nextcraft.forge.model.ProjectChat
import com.nextcraft.forge.model.ProjectKind
import com.nextcraft.forge.model.ProjectType
import com.nextcraft.forge.model.ProviderKind
import com.nextcraft.forge.model.ProviderProfile
import com.nextcraft.forge.model.RuntimeEvent
import com.nextcraft.forge.model.projectSlug
import com.nextcraft.forge.model.stringOrEmpty
import com.nextcraft.forge.network.GitHubActionsClient
import com.nextcraft.forge.network.GitHubClient
import com.nextcraft.forge.network.GitHubRepository
import com.nextcraft.forge.network.ProviderApiClient
import com.nextcraft.forge.runtime.AgentBridgeBase
import com.nextcraft.forge.runtime.AndroidAppInstaller
import com.nextcraft.forge.runtime.AndroidBuildPhase
import com.nextcraft.forge.runtime.AndroidBuildRecord
import com.nextcraft.forge.runtime.AndroidBuildStage
import com.nextcraft.forge.runtime.AndroidLogLevel
import com.nextcraft.forge.runtime.AndroidLogcatState
import com.nextcraft.forge.runtime.AndroidProjectTemplateGenerator
import com.nextcraft.forge.runtime.AntigravityAuthController
import com.nextcraft.forge.runtime.AntigravityRuntimeBridge
import com.nextcraft.forge.runtime.AttachmentStore
import com.nextcraft.forge.runtime.CheckpointHistory
import com.nextcraft.forge.runtime.ClaudeRuntimeBridge
import com.nextcraft.forge.runtime.DshRuntimeBridge
import com.nextcraft.forge.runtime.RootfsRepair
import com.nextcraft.forge.runtime.RuntimeBridge
import com.nextcraft.forge.runtime.RuntimeExecutionServiceContract
import com.nextcraft.forge.runtime.RuntimeInstaller
import com.nextcraft.forge.runtime.RuntimeSetupController
import com.nextcraft.forge.runtime.RuntimeSetupService
import com.nextcraft.forge.runtime.RuntimeSetupStatus
import com.nextcraft.forge.runtime.RuntimeTaskController
import com.nextcraft.forge.runtime.SshHost
import com.nextcraft.forge.runtime.SshHostStore
import com.nextcraft.forge.runtime.SystemDiagnostics
import com.nextcraft.forge.runtime.ToolchainInventory
import com.nextcraft.forge.runtime.WorkspaceCheckpoints
import com.nextcraft.forge.runtime.findAndroidProjectRoot
import com.nextcraft.forge.runtime.findDebugApk
import com.nextcraft.forge.runtime.isRootAccessAvailable
import com.nextcraft.forge.runtime.supportsArm64Runtime
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class MainViewModel(application: Application) : AndroidViewModel(application) {
    internal val preferences = AppPreferences(application)
    internal val vault = ApiKeyVault(application)
    internal val installer = RuntimeInstaller(application)
    internal val checkpoints = WorkspaceCheckpoints(application.filesDir, application)
    internal val providerApi = ProviderApiClient(application)
    internal val githubClient = GitHubClient(application)
    internal val checkpointHistory = CheckpointHistory(checkpoints)
    internal val toolchainInventory = ToolchainInventory(application, installer)
    internal val systemDiagnostics = SystemDiagnostics(application)
    internal val actionsClient = GitHubActionsClient()
    internal val sshHosts = SshHostStore(application)
    val antigravityAuth = AntigravityAuthController(
        context = application,
        initiallySignedIn = preferences.antigravitySignedIn,
        initialAccountEmail = preferences.antigravityAccountEmail,
        onSignedInChanged = { signedIn, email ->
            preferences.antigravitySignedIn = signedIn
            preferences.antigravityAccountEmail = email.orEmpty()
        },
    )

    internal fun resolveProviderSecret(profile: ProviderProfile): String? {
        vault.get(profile.kind.name)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        if (profile.kind == ProviderKind.CLAUDE) {
            vault.get(ProviderKind.ANTHROPIC.name)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        } else if (profile.kind == ProviderKind.ANTHROPIC) {
            vault.get(ProviderKind.CLAUDE.name)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        if (profile.kind == ProviderKind.CLAUDE || profile.kind == ProviderKind.ANTHROPIC) {
            val credsFile = File(RuntimeInstaller.rootfsDir(getApplication()), "root/.claude/.credentials.json")
            val guestToken = runCatching {
                JSONObject(credsFile.readText())
                    .optJSONObject("claudeAiOauth")
                    ?.stringOrEmpty("accessToken")
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }
            }.getOrNull()
            if (guestToken != null) return guestToken
        }
        return null
    }

    private fun rotateProviderSecret(profile: ProviderProfile): String? {
        vault.rotateNext(profile.kind.name)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        if (profile.kind == ProviderKind.CLAUDE) {
            vault.rotateNext(ProviderKind.ANTHROPIC.name)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        } else if (profile.kind == ProviderKind.ANTHROPIC) {
            vault.rotateNext(ProviderKind.CLAUDE.name)?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        }
        return resolveProviderSecret(profile)
    }

    // Session registries must exist before init{} runs bootstrap(), which touches the bridge registry.

    private val sessions = ConcurrentHashMap<String, ProjectSessionState>()
    private val sessionJobs = HashMap<String, Job>()
    private val sessionBridges = HashMap<String, RuntimeBridge>()
    /** Prompt being retried without `--resume` after Claude rejected the saved session id, per project. */
    private val resumeRetries = HashMap<String, PendingAgentLaunch>()

    internal val bridges = AgentBridgeRegistry(
        scope = viewModelScope,
        factory = ::createBridge,
        onEvent = ::onRuntimeEvent,
    )

    internal val _state = MutableStateFlow(
        ForgeUiState(
            themeMode = ForgeThemeMode.fromStored(preferences.themeMode).id,
            languageCode = AppStrings.languageCode(application),
        ),
    )
    val state: StateFlow<ForgeUiState> = _state.asStateFlow()

    internal val workspace = WorkspaceFilesController(this)
    internal val terminal = TerminalController(this)
    internal val androidBuild = AndroidBuildController(this)
    internal val providerSettings = ProviderSettingsController(this)
    internal val github = GitHubController(this)
    internal val tools = ToolsController(this)

    init {
        RuntimeSetupController.restore(application)
        viewModelScope.launch {
            RuntimeSetupController.snapshot.collect { snap ->
                val completed = snap.status == RuntimeSetupStatus.COMPLETE
                _state.update { it.copy(setupSnapshot = snap) }
                if (completed) {
                    refreshRuntimeStatus()
                }
            }
        }
        viewModelScope.launch {
            AndroidAppInstaller.events.collect { event ->
                val statusMsg = event.message ?: if (event.success) {
                    val pkg = event.packageName ?: "APK"
                    if (event.launched) "Installed & launched $pkg" else "Installed $pkg"
                } else {
                    "APK installation failed"
                }
                _state.update {
                    it.copy(
                        androidBuild = it.androidBuild.copy(
                            message = statusMsg,
                            stage = if (event.success) AndroidBuildStage.COMPLETE else AndroidBuildStage.FAILED,
                        ),
                        bannerMessage = statusMsg,
                    )
                }
            }
        }
        bootstrap()
    }

    private fun bootstrap() {
        viewModelScope.launch {
            val arm64 = supportsArm64Runtime(Build.SUPPORTED_ABIS, System.getProperty("os.arch"))
            val rootOk = withContext(Dispatchers.IO) { isRootAccessAvailable() }
            val savedAgent = AgentKind.fromStored(preferences.agentKind)
            val savedStacks = preferences.selectedDevStacks
                .mapNotNull { runCatching { DevStack.valueOf(it) }.getOrNull() }
                .toSet()
                .ifEmpty { setOf(DevStack.WEB, DevStack.PYTHON, DevStack.ANDROID) }
            val installed = withContext(Dispatchers.IO) {
                installer.migrateLegacyToolMarkers()
                installer.isInstalled().also { ready ->
                    if (ready) installer.cleanupLegacyWorkspaceScaffolding()
                }
            }
            val installedStacks = if (installed) {
                withContext(Dispatchers.IO) { installer.installedStacks() }
            } else {
                emptySet()
            }
            val projects = loadOrCreateDefaultProject()
            val firstProject = projects.first()
            configureAllProjectRoots(projects)
            val chats = preferences.loadProjectChats(firstProject.id)
            val activeChatId = chats.firstOrNull()?.id ?: "main"
            val messages = preferences.loadMessages(firstProject.id, activeChatId)
            val loadedProfile = preferences.loadProvider(vault, savedAgent)
            val profile = loadedProfile.copy(hasSecret = !resolveProviderSecret(loadedProfile).isNullOrBlank())
            val keys = vault.list(profile.kind.name)
            val initialBuildRecord = withContext(Dispatchers.IO) {
                val ws = checkpoints.ensureWorkspace(firstProject.id)
                val androidRoot = findAndroidProjectRoot(ws)
                val existingApk = androidRoot?.let { findDebugApk(it) }
                if (existingApk != null) {
                    AndroidBuildRecord(
                        phase = AndroidBuildPhase.SUCCEEDED,
                        stage = AndroidBuildStage.COMPLETE,
                        apkPath = existingApk.absolutePath,
                        apkSizeBytes = existingApk.length(),
                        message = "Existing APK: ${existingApk.name} (${existingApk.length() / 1024} KB)",
                    )
                } else {
                    AndroidBuildRecord()
                }
            }

            _state.update {
                it.copy(
                    arm64Supported = arm64,
                    rootAvailable = rootOk,
                    runtimeInstalled = installed,
                    selectedDevStacks = savedStacks,
                    installedDevStacks = installedStacks,
                    agentKind = savedAgent,
                    themeMode = ForgeThemeMode.fromStored(preferences.themeMode).id,
                    projects = projects,
                    activeProjectId = firstProject.id,
                    chats = chats,
                    activeChatId = activeChatId,
                    messages = messages,
                    androidBuild = if (it.androidBuild.phase == AndroidBuildPhase.BUILDING) it.androidBuild else initialBuildRecord,
                    providerProfile = profile,
                    savedApiKeys = keys,
                    githubLogin = preferences.githubLogin,
                    languageCode = AppStrings.languageCode(getApplication()),
                    interactiveHighRiskApprovals = preferences.interactiveHighRiskApprovals,
                    autoApproveAllTools = preferences.autoApproveAllTools,
                    autoSyncGitHub = preferences.autoSyncGitHub,
                    antigravityModel = preferences.antigravityModel,
                    antigravityEffort = preferences.antigravityEffort,
                    batterySaver = preferences.batterySaver,
                )
            }
            tools.applyBatterySaver(preferences.batterySaver)
            val savedGitHubToken = vault.get("GITHUB_TOKEN").orEmpty()
            if (savedGitHubToken.isNotBlank()) {
                withContext(Dispatchers.IO) {
                    // Credentials are written into the rootfs only for the duration of a sync.
                    runCatching { installer.clearGitHubAccess() }
                    val repos = runCatching { githubClient.repositories(savedGitHubToken) }.getOrDefault(emptyList())
                    if (repos.isNotEmpty()) {
                        _state.update { it.copy(githubRepos = repos) }
                    }
                }
            }
            refreshWorkspaceFilesAndChanges(firstProject.id)
        }
    }

    private suspend fun loadOrCreateDefaultProject(): List<Project> = withContext(Dispatchers.IO) {
        val existing = preferences.loadProjects()
        if (existing.isNotEmpty()) {
            existing.forEach { checkpoints.ensureWorkspace(it.id) }
            return@withContext existing
        }
        val defaultProject = Project(
            name = "Forge Sandbox",
            description = "Default workspace inside Ubuntu ARM64 Root Chroot",
            language = "Kotlin / TypeScript / Python",
            slug = "forge-sandbox",
        )
        val ws = checkpoints.ensureWorkspace(defaultProject.id)
        val readme = File(ws, "README.md")
        if (!readme.exists()) {
            readme.writeText(
                "# Forge Sandbox\n\n" +
                    "Welcome to **NextCraft Forge**.\n" +
                    "- Pure Root Chroot (`su -mm`) execution\n" +
                    "- Multi-agent coding (`Claude Code`, `Antigravity`, `DeepSeek Harness`)\n" +
                    "- 1-Tap Android Debug APK Build & Silent Root Install\n",
            )
        }
        val list = listOf(defaultProject)
        preferences.saveProjects(list)
        list
    }

    internal fun configureAllProjectRoots(projects: List<Project>) {
        projects.forEach { project ->
            checkpoints.configureProjectRoot(project.id, project.rootPath)
            bridges.forProject(project.id).forEach { it.configureProjectRoot(project.id, project.rootPath) }
        }
    }

    private var lastResumeRefreshAt = 0L

    /** onResume fires on every return to the app; each refresh spawns `su`, so don't repeat it within a few seconds. */
    fun refreshRuntimeStatusOnResume() {
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastResumeRefreshAt != 0L && now - lastResumeRefreshAt < RESUME_REFRESH_MIN_INTERVAL_MS) return
        lastResumeRefreshAt = now
        refreshRuntimeStatus()
    }

    fun refreshRuntimeStatus() {
        viewModelScope.launch {
            val rootOk = withContext(Dispatchers.IO) { isRootAccessAvailable() }
            val installed = withContext(Dispatchers.IO) { installer.isInstalled() }
            val installedStacks = if (installed) {
                withContext(Dispatchers.IO) { installer.installedStacks() }
            } else {
                emptySet()
            }
            _state.update {
                it.copy(
                    rootAvailable = rootOk,
                    runtimeInstalled = installed,
                    installedDevStacks = installedStacks,
                )
            }
        }
    }

    fun toggleDevStack(stack: DevStack) {
        _state.update { current ->
            val updated = if (stack in current.selectedDevStacks) {
                current.selectedDevStacks - stack
            } else {
                current.selectedDevStacks + stack
            }
            preferences.selectedDevStacks = updated.map { it.name }.toSet()
            current.copy(selectedDevStacks = updated)
        }
    }

    fun startRuntimeSetup() {
        val current = _state.value
        val stacksCsv = current.selectedDevStacks.joinToString(",") { it.name }
        preferences.selectedDevStacks = current.selectedDevStacks.map { it.name }.toSet()
        preferences.agentKind = current.agentKind.stableId
        ContextCompat.startForegroundService(
            getApplication(),
            Intent(getApplication(), RuntimeSetupService::class.java)
                .setAction(RuntimeSetupService.ACTION_START)
                .putExtra(RuntimeSetupService.EXTRA_STACKS, stacksCsv)
                .putExtra(RuntimeSetupService.EXTRA_AGENT, current.agentKind.name),
        )
    }

    fun pauseRuntimeSetup() {
        getApplication<Application>().startService(
            Intent(getApplication(), RuntimeSetupService::class.java)
                .setAction(RuntimeSetupService.ACTION_STOP),
        )
    }

    fun repairRootfs() {
        viewModelScope.launch {
            _state.update { it.copy(bannerMessage = "Repairing chroot mounts & runtime scripts…") }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val rootfs = File(getApplication<Application>().filesDir, "runtime/ubuntu")
                    installer.ensureChrootScripts()
                    if (RootfsRepair.ensureUnmounted(rootfs)) {
                        "Chroot mount points and supervisor scripts verified."
                    } else {
                        "Chroot repair incomplete: some mounts are still active. Reboot the device and retry."
                    }
                }.getOrElse { "Chroot repair note: ${it.message}" }
            }
            refreshRuntimeStatus()
            _state.update { it.copy(bannerMessage = result) }
        }
    }

    fun selectTab(tab: ForgeTab) {
        _state.update { it.copy(activeTab = tab) }
        if (tab == ForgeTab.FILES || tab == ForgeTab.ANDROID || tab == ForgeTab.CHECKPOINTS) {
            _state.value.activeProject?.let { refreshWorkspaceFilesAndChanges(it.id) }
        }
        if (tab == ForgeTab.PREVIEW) {
            scanLocalPreviewPorts()
        }
        if (tab == ForgeTab.DIAGNOSTICS) tools.startDiagnosticsPolling() else tools.stopDiagnosticsPolling()
        when (tab) {
            ForgeTab.TOOLCHAINS -> refreshToolchains()
            ForgeTab.CHECKPOINTS -> refreshCheckpointHistory()
            ForgeTab.CICD -> refreshCicd()
            ForgeTab.SSH -> refreshSsh()
            ForgeTab.GIT -> refreshGit()
            else -> Unit
        }
    }

    fun selectAgent(agent: AgentKind) {
        // Switching bridges mid-run would desync providerProfile/hasSecret from the session that is
        // still streaming, and would misroute any pending tool approval to the newly selected bridge.
        if (_state.value.sessionRunning) return
        preferences.agentKind = agent.stableId
        val loadedProfile = preferences.loadProvider(vault, agent)
        val profile = loadedProfile.copy(hasSecret = !resolveProviderSecret(loadedProfile).isNullOrBlank())
        val keys = vault.list(profile.kind.name)
        _state.update {
            it.copy(
                agentKind = agent,
                providerProfile = profile,
                savedApiKeys = keys,
                apiStatusMessage = null,
            )
        }
    }

    fun selectProject(projectId: String) {
        val project = _state.value.projects.firstOrNull { it.id == projectId } ?: return
        val switchingProject = projectId != _state.value.activeProjectId
        // Agent sessions are per project, so another project may keep streaming; builds and the
        // terminal are still single instances, so those block the switch.
        if (switchingProject) {
            if (_state.value.androidBuild.phase == AndroidBuildPhase.BUILDING) {
                _state.update { it.copy(bannerMessage = "Wait for the Android APK build to finish or stop it before switching projects.") }
                return
            }
            if (_state.value.terminalRunning) {
                _state.update { it.copy(bannerMessage = "Wait for the active terminal command to finish before switching projects.") }
                return
            }
            androidBuild.activeLogcatProcess?.destroyForcibly()
            androidBuild.activeLogcatProcess = null
            androidBuild.activeLogcatJob?.cancel()
            discardPendingAttachments(_state.value.activeProjectId)
            clearSearch()
        }
        val chats = preferences.loadProjectChats(project.id)
        // A project whose session is still streaming shows that live chat, not the copy on disk.
        val live = sessions[project.id]
        val chatId = live?.chatId ?: chats.firstOrNull()?.id ?: "main"
        val messages = live?.messages ?: preferences.loadMessages(project.id, chatId)
        _state.update {
            it.copy(
                activeProjectId = project.id,
                chats = chats,
                activeChatId = chatId,
                messages = messages,
                sessionRunning = live?.sessionRunning == true,
                activeStatusLine = live?.activeStatusLine,
                pendingToolRequest = live?.pendingToolRequest,
                selectedFilePath = null,
                selectedFileContent = "",
                selectedFileDirty = false,
                fileSavedBanner = null,
                git = if (switchingProject) GitUiState() else it.git,
                androidLogcat = if (switchingProject) AndroidLogcatState() else it.androidLogcat,
            )
        }
        if (_state.value.androidBuild.phase != AndroidBuildPhase.BUILDING) {
            viewModelScope.launch {
                val buildRecord = withContext(Dispatchers.IO) {
                    val ws = checkpoints.ensureWorkspace(project.id)
                    val androidRoot = findAndroidProjectRoot(ws)
                    val existingApk = androidRoot?.let { findDebugApk(it) }
                    if (existingApk != null) {
                        AndroidBuildRecord(
                            phase = AndroidBuildPhase.SUCCEEDED,
                            stage = AndroidBuildStage.COMPLETE,
                            apkPath = existingApk.absolutePath,
                            apkSizeBytes = existingApk.length(),
                            message = "Existing APK: ${existingApk.name} (${existingApk.length() / 1024} KB)",
                        )
                    } else {
                        AndroidBuildRecord()
                    }
                }
                if (_state.value.activeProjectId == project.id && _state.value.androidBuild.phase != AndroidBuildPhase.BUILDING) {
                    _state.update { it.copy(androidBuild = buildRecord) }
                }
            }
        }
        refreshWorkspaceFilesAndChanges(project.id)
    }

    fun createProject(
        name: String,
        description: String,
        isAndroid: Boolean,
        androidTemplate: AndroidTemplate = AndroidTemplate.COMPOSE,
    ) {
        if (_state.value.androidBuild.phase == AndroidBuildPhase.BUILDING || _state.value.terminalRunning) {
            _state.update { it.copy(bannerMessage = "Finish or stop the active task before creating and switching to a new project.") }
            return
        }
        val cleanName = name.trim().ifBlank { "New Project" }
        viewModelScope.launch {
            val newProject = withContext(Dispatchers.IO) {
                val usedSlugs = _state.value.projects.map { it.slug }.toSet()
                val baseSlug = projectSlug(cleanName)
                var slug = baseSlug
                var suffix = 2
                while (slug in usedSlugs) slug = "$baseSlug-${suffix++}"
                val project = Project(
                    name = cleanName,
                    description = description.trim().ifBlank {
                        if (isAndroid) "Android (${androidTemplate.label}) project" else "General coding workspace"
                    },
                    language = if (isAndroid) "Kotlin" else "Multi-language",
                    slug = slug,
                    type = if (isAndroid) ProjectType.ANDROID else ProjectType.GENERAL,
                    androidTemplate = if (isAndroid) androidTemplate else null,
                )
                val wsDir = File(getApplication<Application>().filesDir, "workspaces/${project.id}")
                if (isAndroid) {
                    wsDir.deleteRecursively()
                    AndroidProjectTemplateGenerator.generate(
                        destination = wsDir,
                        appName = cleanName,
                        template = androidTemplate,
                    )
                } else {
                    checkpoints.ensureWorkspace(project.id)
                    File(wsDir, "README.md").writeText("# $cleanName\n\n${project.description}\n")
                }
                project
            }
            val updated = listOf(newProject) + _state.value.projects
            preferences.saveProjects(updated)
            configureAllProjectRoots(updated)
            _state.update {
                it.copy(
                    projects = updated,
                    activeTab = if (it.activeTab == ForgeTab.PROJECTS) ForgeTab.AGENT else it.activeTab,
                )
            }
            selectProject(newProject.id)
        }
    }

    fun createQuickProject() {
        if (_state.value.androidBuild.phase == AndroidBuildPhase.BUILDING || _state.value.terminalRunning) {
            _state.update { it.copy(bannerMessage = "Finish or stop the active task before starting a new Quick Chat.") }
            return
        }
        viewModelScope.launch {
            val quickProject = withContext(Dispatchers.IO) {
                val quickCount = _state.value.projects.count { it.kind == ProjectKind.QUICK_PROJECT } + 1
                val cleanName = if (quickCount == 1) "Quick Chat" else "Quick Chat $quickCount"
                val usedSlugs = _state.value.projects.map { it.slug }.toSet()
                val baseSlug = projectSlug(cleanName)
                var slug = baseSlug
                var suffix = 2
                while (slug in usedSlugs) slug = "$baseSlug-${suffix++}"
                val project = Project(
                    name = cleanName,
                    description = "Lightweight scratchpad & instant agent session",
                    language = "Multi-language",
                    slug = slug,
                    type = ProjectType.GENERAL,
                    kind = ProjectKind.QUICK_PROJECT,
                )
                val wsDir = checkpoints.ensureWorkspace(project.id)
                val readme = File(wsDir, "README.md")
                if (!readme.exists()) {
                    readme.writeText("# $cleanName\n\nQuick scratchpad workspace.\n")
                }
                project
            }
            val updated = listOf(quickProject) + _state.value.projects
            preferences.saveProjects(updated)
            configureAllProjectRoots(updated)
            _state.update {
                it.copy(
                    projects = updated,
                    activeTab = ForgeTab.AGENT,
                )
            }
            selectProject(quickProject.id)
        }
    }

    fun deleteProject(projectId: String) {
        // The workspace would be wiped under a running agent, build, or terminal process.
        if (isProjectSessionRunning(projectId) || _state.value.androidBuild.phase == AndroidBuildPhase.BUILDING || _state.value.terminalRunning) {
            _state.update { it.copy(bannerMessage = "Cannot delete a project while an agent session, build, or terminal command is running.") }
            return
        }
        val currentProjects = _state.value.projects
        if (currentProjects.size <= 1) return
        val updated = currentProjects.filterNot { it.id == projectId }
        if (updated.size == currentProjects.size) return
        val oldChats = runCatching { preferences.loadProjectChats(projectId) }.getOrDefault(emptyList())
        oldChats.forEach { chat ->
            AgentKind.entries.forEach { agent ->
                preferences.saveAgentConversation(agent, projectId, chat.id, null)
            }
        }
        preferences.saveProjects(updated)
        preferences.deleteProjectChats(projectId)
        sessions.remove(projectId)
        sessionBridges.remove(projectId)
        sessionJobs.remove(projectId)
        bridges.remove(projectId)
        if (_state.value.activeProjectId == projectId) discardPendingAttachments(projectId)
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                File(getApplication<Application>().filesDir, "workspaces/$projectId").deleteRecursively()
                checkpoints.deleteProjectCheckpoints(projectId)
            }
        }
        _state.update { it.copy(projects = updated) }
        if (_state.value.activeProjectId == projectId) {
            selectProject(updated.first().id)
        }
    }

    fun selectChat(chatId: String) {
        if (_state.value.sessionRunning) return
        val project = _state.value.activeProject ?: return
        if (_state.value.chats.none { it.id == chatId }) return
        val messages = preferences.loadMessages(project.id, chatId)
        _state.update {
            it.copy(
                activeChatId = chatId,
                messages = messages,
            )
        }
    }

    fun createChat(title: String = "") {
        if (_state.value.sessionRunning) return
        val project = _state.value.activeProject ?: return
        val nextIndex = _state.value.chats.size + 1
        val chat = ProjectChat(
            title = title.trim().ifBlank { "Chat $nextIndex" },
        )
        val updatedChats = _state.value.chats + chat
        preferences.saveProjectChats(project.id, updatedChats)
        preferences.saveMessages(project.id, chat.id, emptyList())
        _state.update {
            it.copy(
                chats = updatedChats,
                activeChatId = chat.id,
                messages = emptyList(),
            )
        }
    }

    fun deleteChat(chatId: String) {
        if (_state.value.sessionRunning) return
        val project = _state.value.activeProject ?: return
        val currentChats = _state.value.chats
        if (currentChats.size <= 1) return
        val updatedChats = currentChats.filterNot { it.id == chatId }
        if (updatedChats.size == currentChats.size) return
        AgentKind.entries.forEach { agent ->
            preferences.saveAgentConversation(agent, project.id, chatId, null)
        }
        preferences.saveProjectChats(project.id, updatedChats)
        runCatching { preferences.deleteChatMessages(project.id, chatId) }
        val nextChatId = if (_state.value.activeChatId == chatId) {
            updatedChats.first().id
        } else {
            _state.value.activeChatId
        }
        val messages = preferences.loadMessages(project.id, nextChatId)
        _state.update {
            it.copy(
                chats = updatedChats,
                activeChatId = nextChatId,
                messages = messages,
            )
        }
    }

    fun clearActiveChat() {
        if (_state.value.sessionRunning) return
        val project = _state.value.activeProject ?: return
        val chatId = _state.value.activeChatId
        AgentKind.entries.forEach { agent ->
            preferences.saveAgentConversation(agent, project.id, chatId, null)
        }
        preferences.saveMessages(project.id, chatId, emptyList())
        _state.update {
            it.copy(
                messages = emptyList(),
                activeStatusLine = null,
            )
        }
    }

    // region Agent sessions: one live session per project, bridges created per (project, agent)
    private fun createBridge(projectId: String, agent: AgentKind): AgentBridgeBase = when (agent) {
        AgentKind.CLAUDE_CODE -> ClaudeRuntimeBridge(
            context = getApplication(),
            secretFor = ::resolveProviderSecret,
            rotateSecretFor = ::rotateProviderSecret,
        )
        AgentKind.DEEPSEEK_HARNESS -> DshRuntimeBridge(
            context = getApplication(),
            secretFor = ::resolveProviderSecret,
            rotateSecretFor = ::rotateProviderSecret,
        )
        AgentKind.ANTIGRAVITY -> AntigravityRuntimeBridge(
            context = getApplication(),
            model = { preferences.antigravityModel },
            effort = { preferences.antigravityEffort },
            conversationId = { pid -> preferences.loadAgentConversation(AgentKind.ANTIGRAVITY, pid, sessionChatId(pid)) },
            saveConversationId = { pid, convId ->
                preferences.saveAgentConversation(AgentKind.ANTIGRAVITY, pid, sessionChatId(pid), convId)
            },
        )
    }

    private fun sessionChatId(projectId: String): String =
        sessions[projectId]?.chatId
            ?: if (projectId == _state.value.activeProjectId) _state.value.activeChatId else "main"

    private fun runningSessionCount(): Int = sessions.values.count { it.sessionRunning }

    internal fun isProjectSessionRunning(projectId: String): Boolean = sessions[projectId]?.sessionRunning == true

    /** Copies the session's fields into the UI state when it belongs to the active project. */
    private fun mirrorSession(session: ProjectSessionState) {
        _state.update { current ->
            val running = if (session.sessionRunning) {
                current.runningProjectIds + session.projectId
            } else {
                current.runningProjectIds - session.projectId
            }
            if (current.activeProjectId == session.projectId) {
                current.copy(
                    messages = session.messages,
                    sessionRunning = session.sessionRunning,
                    activeStatusLine = session.activeStatusLine,
                    pendingToolRequest = session.pendingToolRequest,
                    runningProjectIds = running,
                )
            } else {
                current.copy(runningProjectIds = running)
            }
        }
    }

    private fun updateSession(projectId: String, transform: (ProjectSessionState) -> ProjectSessionState) {
        val current = sessions[projectId] ?: return
        val next = transform(current)
        sessions[projectId] = next
        mirrorSession(next)
    }

    private data class PendingAgentLaunch(
        val project: Project,
        val chatId: String,
        val agent: AgentKind,
        val provider: ProviderProfile,
        val prompt: String,
        val history: List<ChatMessage>,
    )

    fun sendPrompt(promptText: String) {
        val cleanPrompt = promptText.trim()
        val project = _state.value.activeProject ?: return
        val attachments = _state.value.pendingAttachments
        if ((cleanPrompt.isEmpty() && attachments.isEmpty()) || isProjectSessionRunning(project.id)) return
        if (runningSessionCount() >= MAX_CONCURRENT_SESSIONS) {
            _state.update {
                it.copy(bannerMessage = getApplication<Application>().getString(R.string.agent_too_many_sessions, MAX_CONCURRENT_SESSIONS))
            }
            return
        }
        val chatId = _state.value.activeChatId
        val agent = _state.value.agentKind
        val provider = _state.value.providerProfile
        val now = System.currentTimeMillis()
        val userMsg = ChatMessage(fromUser = true, text = cleanPrompt, attachments = attachments)
        val assistantPlaceholder = ChatMessage(
            id = "streaming-assistant",
            fromUser = false,
            text = "",
        )
        val historyBefore = _state.value.messages
        val updatedMessages = historyBefore + userMsg + assistantPlaceholder
        preferences.appendMessage(project.id, chatId, userMsg)

        val titleSource = cleanPrompt.ifBlank { attachments.firstOrNull()?.displayName.orEmpty() }
        val autoTitle = titleSource.lineSequence().firstOrNull()?.trim().orEmpty().let { firstLine ->
            if (firstLine.length > 34) firstLine.take(34).trimEnd() + "…" else firstLine
        }
        val updatedChats = _state.value.chats.map { chat ->
            if (chat.id == chatId) {
                val shouldRetitle = historyBefore.isEmpty() &&
                    (chat.title == "Main chat" || chat.title.matches(Regex("""^Chat \d+$"""))) &&
                    autoTitle.isNotEmpty()
                chat.copy(
                    title = if (shouldRetitle) autoTitle else chat.title,
                    updatedAtMillis = now,
                )
            } else {
                chat
            }
        }
        val updatedProjects = _state.value.projects.map { proj ->
            if (proj.id == project.id) proj.copy(updatedAtMillis = now) else proj
        }
        preferences.saveProjectChats(project.id, updatedChats)
        preferences.saveProjects(updatedProjects)

        _state.update {
            it.copy(
                projects = updatedProjects,
                chats = updatedChats,
                pendingAttachments = emptyList(),
            )
        }
        val session = ProjectSessionState(
            projectId = project.id,
            chatId = chatId,
            agentKind = agent,
            messages = updatedMessages,
            sessionRunning = true,
            activeStatusLine = "Starting ${agent.title}…",
        )
        sessions[project.id] = session
        mirrorSession(session)

        // The bridge lists attachments only for prior turns; the current turn's files go in the prompt itself.
        val agentPrompt = buildString {
            append(cleanPrompt)
            if (attachments.isNotEmpty()) {
                appendLine()
                appendLine()
                appendLine("Attached files (read them from the workspace as needed):")
                attachments.forEach { attachment ->
                    appendLine("- ${attachment.displayName}: /workspace/${project.slug}/${attachment.relativePath} (${attachment.mimeType}, ${attachment.sizeBytes} bytes)")
                }
            }
        }
        val launch = PendingAgentLaunch(project, chatId, agent, provider, agentPrompt, historyBefore + userMsg)
        resumeRetries[project.id] = launch
        launchAgentSession(launch, allowResume = true)
    }

    private fun launchAgentSession(launch: PendingAgentLaunch, allowResume: Boolean) {
        val projectId = launch.project.id
        val bridge = bridges.bridge(projectId, launch.agent)
        sessionBridges[projectId] = bridge
        val resumeId = if (allowResume && launch.agent == AgentKind.CLAUDE_CODE) {
            preferences.loadAgentSessionId(launch.agent, projectId, launch.chatId)?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        sessionJobs[projectId] = viewModelScope.launch {
            try {
                bridge.startSession(
                    projectId = projectId,
                    projectSlug = launch.project.slug,
                    projectKind = launch.project.kind,
                    prompt = launch.prompt,
                    conversationHistory = launch.history,
                    provider = launch.provider,
                    resumeSessionId = resumeId,
                )
            } catch (error: Throwable) {
                if (error !is CancellationException) {
                    appendAssistantText(projectId, "\n\nError: ${error.message ?: "Session failed"}")
                }
                finishAgentTurn(projectId)
            }
        }
    }

    /** Stops the active project's agent session and any running Android build. */
    fun stopActiveTask() {
        _state.value.activeProject?.let { stopProjectSession(it.id) }
        androidBuild.activeBuildProcess?.destroyForcibly()
        androidBuild.activeBuildJob?.cancel()
    }

    fun stopProjectSession(projectId: String) {
        val session = sessions[projectId] ?: return
        if (!session.sessionRunning) return
        val bridge = sessionBridges[projectId]
        val job = sessionJobs[projectId]
        resumeRetries.remove(projectId)
        session.runtimeSessionId?.let { RuntimeTaskController.requestStop(it) }
        updateSession(projectId) { it.copy(activeStatusLine = "Stopping…", pendingToolRequest = null) }
        // Bridge sessions run NonCancellable: keep the session marked running until it really ends,
        // otherwise a new prompt could race with it over the bridge's process state.
        viewModelScope.launch {
            session.pendingToolRequest?.let { pending ->
                runCatching { bridge?.respondToApproval(pending, false) }
            }
            runCatching { bridge?.stopActiveSession() }
            job?.join()
            if (isProjectSessionRunning(projectId)) {
                appendAssistantText(projectId, "\n\n[Stopped by user]")
                finishAgentTurn(projectId)
            }
        }
    }

    fun respondToToolRequest(approved: Boolean) {
        val project = _state.value.activeProject ?: return
        val session = sessions[project.id] ?: return
        val req = session.pendingToolRequest ?: return
        val bridge = sessionBridges[project.id] ?: return
        updateSession(project.id) { it.copy(pendingToolRequest = null) }
        viewModelScope.launch {
            // The bridge also unregisters the ApprovalRelay entry and cancels the notification.
            bridge.respondToApproval(req, approved)
        }
    }

    private fun onRuntimeEvent(projectId: String, event: RuntimeEvent) {
        val session = sessions[projectId] ?: return
        if (event is RuntimeEvent.SessionStarted) {
            if (!session.sessionRunning) return
            updateSession(projectId) { it.copy(runtimeSessionId = event.sessionId, activeStatusLine = "Session started…") }
            return
        }
        // Late events from an older session of the same project must not touch the current chat.
        if (event.sessionId != session.runtimeSessionId) return
        when (event) {
            is RuntimeEvent.SessionStarted -> Unit
            is RuntimeEvent.AssistantDelta -> appendAssistantText(projectId, event.text)
            is RuntimeEvent.ReasoningProgress -> {
                updateSession(projectId) { it.copy(activeStatusLine = "Reasoning (${event.estimatedTokens} tokens)…") }
            }
            is RuntimeEvent.ReasoningSummary -> {
                updateSession(projectId) { it.copy(activeStatusLine = event.summary.take(120)) }
            }
            is RuntimeEvent.ToolStarted -> {
                recordActivityItem(projectId, event.toolName, event.detail, isComplete = false)
                updateSession(projectId) { it.copy(activeStatusLine = "${event.toolName}: ${event.detail}".take(120)) }
            }
            is RuntimeEvent.ToolProgress -> {
                updateSession(projectId) { it.copy(activeStatusLine = "${event.toolName}: ${event.detail}".take(120)) }
            }
            is RuntimeEvent.ToolCompleted -> {
                recordActivityItem(projectId, event.toolName, event.summary, isComplete = true)
            }
            is RuntimeEvent.RuntimeLog -> {
                updateSession(projectId) { it.copy(activeStatusLine = "${event.title}: ${event.detail}".take(120)) }
            }
            is RuntimeEvent.ToolRequested -> {
                updateSession(projectId) { it.copy(pendingToolRequest = event.request) }
            }
            is RuntimeEvent.ToolApproved, is RuntimeEvent.ToolRejected -> {
                // Answered in-app or from the notification; either way the card and the notification go away.
                val approvalId = (event as? RuntimeEvent.ToolApproved)?.approvalId ?: (event as RuntimeEvent.ToolRejected).approvalId
                RuntimeExecutionServiceContract.cancelApproval(getApplication(), approvalId)
                updateSession(projectId) { it.copy(pendingToolRequest = null) }
            }
            is RuntimeEvent.FilesChanged -> {
                if (projectId == _state.value.activeProjectId) {
                    _state.update { it.copy(changedItems = event.changes) }
                    refreshWorkspaceFilesAndChanges(projectId)
                }
            }
            is RuntimeEvent.PreviewStarted -> {
                if (projectId == _state.value.activeProjectId) _state.update { it.copy(previewUrl = event.url) }
            }
            is RuntimeEvent.SessionIdentified -> {
                preferences.saveAgentSessionId(session.agentKind, projectId, session.chatId, event.agentSessionId)
            }
            is RuntimeEvent.SessionCompleted -> {
                resumeRetries.remove(projectId)
                finishAgentTurn(projectId)
            }
            is RuntimeEvent.SessionFailed -> {
                val retry = resumeRetries.remove(projectId)
                if (retry != null && event.reason.startsWith("resume_unavailable:")) {
                    // The saved Claude session no longer exists: drop it and run the same prompt once from scratch.
                    preferences.saveAgentSessionId(retry.agent, projectId, retry.chatId, null)
                    val previousJob = sessionJobs[projectId]
                    viewModelScope.launch {
                        previousJob?.join()
                        if (isProjectSessionRunning(projectId)) {
                            launchAgentSession(retry, allowResume = false)
                        }
                    }
                } else {
                    appendAssistantText(projectId, "\n\n[${event.reason}]")
                    finishAgentTurn(projectId)
                }
            }
        }
    }

    private fun appendAssistantText(projectId: String, delta: String) {
        if (delta.isEmpty()) return
        updateSession(projectId) { session ->
            val list = session.messages.toMutableList()
            val lastIdx = list.indexOfLast { !it.fromUser }
            if (lastIdx >= 0) {
                val prev = list[lastIdx]
                list[lastIdx] = prev.copy(text = prev.text + delta)
            } else {
                list += ChatMessage(fromUser = false, text = delta)
            }
            session.copy(messages = list)
        }
    }

    private fun recordActivityItem(projectId: String, title: String, detail: String, isComplete: Boolean) {
        updateSession(projectId) { session ->
            val list = session.messages.toMutableList()
            val lastIdx = list.indexOfLast { !it.fromUser }
            if (lastIdx >= 0) {
                val prev = list[lastIdx]
                val items = prev.workItems.toMutableList()
                val existingIdx = items.indexOfLast { it.title == title && !it.isComplete }
                if (existingIdx >= 0 && isComplete) {
                    items[existingIdx] = items[existingIdx].copy(detail = detail, isComplete = true)
                } else {
                    items += ActivityItem(
                        title = title,
                        detail = detail,
                        isComplete = isComplete,
                        isCommand = title.equals("Bash", ignoreCase = true),
                    )
                }
                list[lastIdx] = prev.copy(workItems = items)
            }
            session.copy(messages = list)
        }
    }

    private fun finishAgentTurn(projectId: String) {
        // The terminal event and the job's own exit path both call this; the second call must not re-save or re-sync.
        val session = sessions[projectId] ?: return
        if (!session.sessionRunning) return
        sessionBridges.remove(projectId)
        sessionJobs.remove(projectId)
        resumeRetries.remove(projectId)
        val chatId = session.chatId
        val now = System.currentTimeMillis()
        val finalizedMessages = session.messages.map { msg ->
            if (msg.id == "streaming-assistant") {
                val elapsed = (now - msg.createdAt.toEpochMilli()).coerceAtLeast(0L)
                msg.copy(
                    id = UUID.randomUUID().toString(),
                    text = msg.text.ifBlank { "Task finished." },
                    workedMillis = elapsed,
                )
            } else {
                msg
            }
        }
        val streamingIndex = session.messages.indexOfLast { it.id == "streaming-assistant" }
        if (streamingIndex >= 0 && streamingIndex == finalizedMessages.lastIndex) {
            // Only the assistant reply is new on disk; the user message was appended when the prompt was sent.
            preferences.appendMessage(projectId, chatId, finalizedMessages[streamingIndex])
        } else {
            preferences.saveMessages(projectId, chatId, finalizedMessages)
        }
        updateSession(projectId) {
            it.copy(
                messages = finalizedMessages,
                sessionRunning = false,
                activeStatusLine = null,
                pendingToolRequest = null,
                runtimeSessionId = null,
            )
        }
        if (projectId == _state.value.activeProjectId) refreshWorkspaceFilesAndChanges(projectId)
        if (preferences.autoSyncGitHub) {
            syncProjectWithGitHub(projectId, "Sync agent changes from NextCraft Forge")
        }
    }

    // endregion

    // region Prompt attachments

    fun attachFiles(uris: List<Uri>) {
        val project = _state.value.activeProject ?: return
        if (uris.isEmpty()) return
        viewModelScope.launch {
            val room = (AttachmentStore.MAX_PER_PROMPT - _state.value.pendingAttachments.size).coerceAtLeast(0)
            val picked = uris.take(room)
            val added = withContext(Dispatchers.IO) {
                val ws = checkpoints.ensureWorkspace(project.id)
                picked.mapNotNull { uri -> AttachmentStore.copyInto(getApplication(), ws, uri) }
            }
            _state.update { it.copy(pendingAttachments = it.pendingAttachments + added) }
            val skipped = uris.size - added.size
            if (skipped > 0) {
                _state.update {
                    it.copy(bannerMessage = getApplication<Application>().getString(R.string.agent_attachments_skipped, skipped))
                }
            }
        }
    }

    fun removePendingAttachment(attachmentId: String) {
        val project = _state.value.activeProject ?: return
        val attachment = _state.value.pendingAttachments.firstOrNull { it.id == attachmentId } ?: return
        _state.update { it.copy(pendingAttachments = it.pendingAttachments.filterNot { a -> a.id == attachmentId }) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { AttachmentStore.delete(checkpoints.ensureWorkspace(project.id), attachment) }
        }
    }

    private fun discardPendingAttachments(projectId: String) {
        val pending = _state.value.pendingAttachments
        if (pending.isEmpty()) return
        _state.update { it.copy(pendingAttachments = emptyList()) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val ws = checkpoints.ensureWorkspace(projectId)
                pending.forEach { AttachmentStore.delete(ws, it) }
            }
        }
    }

    /** Absolute file of an attachment in the active project's workspace (for thumbnails). */
    fun attachmentFile(attachment: ChatAttachment): File? {
        val project = _state.value.activeProject ?: return null
        return runCatching { AttachmentStore.file(checkpoints.ensureWorkspace(project.id), attachment) }.getOrNull()
    }

    // endregion

    // region Delegates to controllers
    fun refreshWorkspaceFilesAndChanges(projectId: String) = workspace.refreshWorkspaceFilesAndChanges(projectId)
    fun openWorkspaceFile(relativePath: String) = workspace.openWorkspaceFile(relativePath)
    fun updateSelectedFileContent(newContent: String) = workspace.updateSelectedFileContent(newContent)
    fun saveSelectedFile() = workspace.saveSelectedFile()
    fun createNewWorkspaceFile(relativePath: String, initialContent: String = "") = workspace.createNewWorkspaceFile(relativePath, initialContent)
    fun deleteWorkspaceFile(relativePath: String) = workspace.deleteWorkspaceFile(relativePath)
    fun revertSingleFile(path: String) = workspace.revertSingleFile(path)
    fun keepSingleFile(path: String) = workspace.keepSingleFile(path)
    fun keepAllChanges() = workspace.keepAllChanges()
    fun revertAllChanges() = workspace.revertAllChanges()
    fun runTerminalCommand(command: String) = terminal.runTerminalCommand(command)
    fun stopTerminal() = terminal.stopTerminal()
    fun clearTerminal() = terminal.clearTerminal()
    fun connectSsh(hostId: String) = terminal.connectSsh(hostId)
    fun startSshForward(hostId: String) = terminal.startSshForward(hostId)
    fun stopSshForward() = terminal.stopSshForward()
    fun runInteractiveInChroot(command: List<String>) = terminal.runInteractiveInChroot(command)
    fun refreshGit() = terminal.refreshGit()
    fun gitInit() = terminal.gitInit()
    fun gitStage(path: String) = terminal.gitStage(path)
    fun gitUnstage(path: String) = terminal.gitUnstage(path)
    fun gitStageAll() = terminal.gitStageAll()
    fun gitUnstageAll() = terminal.gitUnstageAll()
    fun gitDiscard(entry: GitFileEntry) = terminal.gitDiscard(entry)
    fun gitCommit(message: String) = terminal.gitCommit(message)
    fun gitCheckout(branch: String) = terminal.gitCheckout(branch)
    fun gitCreateBranch(branch: String) = terminal.gitCreateBranch(branch)
    fun gitPush() = terminal.gitPush()
    fun gitPull() = terminal.gitPull()
    fun runAndroidBuild(task: String = "assembleDebug", installAfterBuild: Boolean = false) = androidBuild.runAndroidBuild(task, installAfterBuild)
    fun installBuiltApk(apkPath: String? = _state.value.androidBuild.apkPath) = androidBuild.installBuiltApk(apkPath)
    fun startProjectLogcat() = androidBuild.startProjectLogcat()
    fun stopProjectLogcat() = androidBuild.stopProjectLogcat()
    fun clearProjectLogcat() = androidBuild.clearProjectLogcat()
    fun setLogcatMinimumLevel(level: AndroidLogLevel) = androidBuild.setLogcatMinimumLevel(level)
    fun setLogcatQuery(query: String) = androidBuild.setLogcatQuery(query)
    fun updatePreviewUrl(url: String) = androidBuild.updatePreviewUrl(url)
    fun scanLocalPreviewPorts() = androidBuild.scanLocalPreviewPorts()
    fun updateProviderKind(kind: ProviderKind) = providerSettings.updateProviderKind(kind)
    fun updateProviderConfig(
        baseUrl: String,
        model: String,
        openRouterOrder: String = _state.value.providerProfile.openRouterProviderOrder,
        openRouterAllowFallbacks: Boolean = _state.value.providerProfile.openRouterAllowFallbacks,
        dshApi: String = _state.value.providerProfile.dshApi,
    ) = providerSettings.updateProviderConfig(baseUrl, model, openRouterOrder, openRouterAllowFallbacks, dshApi)
    fun validateAntigravityConnection() = providerSettings.validateAntigravityConnection()
    fun addApiKey(label: String, secret: String) = providerSettings.addApiKey(label, secret)
    fun selectActiveApiKey(keyId: String) = providerSettings.selectActiveApiKey(keyId)
    fun removeApiKey(keyId: String) = providerSettings.removeApiKey(keyId)
    fun discoverProviderModels() = providerSettings.discoverProviderModels()
    fun validateProviderConnection() = providerSettings.validateProviderConnection()
    fun setLanguageCode(code: String) = providerSettings.setLanguageCode(code)
    fun setInteractiveHighRiskApprovals(enabled: Boolean) = providerSettings.setInteractiveHighRiskApprovals(enabled)
    fun setAutoApproveAllTools(enabled: Boolean) = providerSettings.setAutoApproveAllTools(enabled)
    fun setAutoSyncGitHub(enabled: Boolean) = providerSettings.setAutoSyncGitHub(enabled)
    fun setAntigravityConfig(model: String, effort: String) = providerSettings.setAntigravityConfig(model, effort)
    fun checkForAppUpdates(manifestUrlOverride: String = "") = providerSettings.checkForAppUpdates(manifestUrlOverride)
    fun downloadAndInstallAppUpdate() = providerSettings.downloadAndInstallAppUpdate()
    fun setThemeMode(mode: String) = providerSettings.setThemeMode(mode)
    fun toggleThemeMode() = providerSettings.toggleThemeMode()
    fun startGitHubDeviceSignIn(clientId: String = "") = github.startGitHubDeviceSignIn(clientId)
    fun connectGitHubWithToken(personalAccessToken: String) = github.connectGitHubWithToken(personalAccessToken)
    fun disconnectGitHub() = github.disconnectGitHub()
    fun createGitHubRepository(
        name: String,
        description: String = "",
        isPrivate: Boolean = true,
        pushActiveProject: Boolean = true,
    ) = github.createGitHubRepository(name, description, isPrivate, pushActiveProject)
    fun syncProjectWithGitHub(
        projectId: String = _state.value.activeProjectId,
        commitMessage: String = "Sync project changes from NextCraft Forge",
    ) = github.syncProjectWithGitHub(projectId, commitMessage)
    fun pullProjectFromGitHub(projectId: String = _state.value.activeProjectId) = github.pullProjectFromGitHub(projectId)
    fun cloneGitHubRepository(repo: GitHubRepository) = github.cloneGitHubRepository(repo)
    fun refreshCicd() = github.refreshCicd()
    fun loadWorkflows() = github.loadWorkflows()
    fun dispatchWorkflow(workflowId: Long) = github.dispatchWorkflow(workflowId)
    fun rerunWorkflowRun(runId: Long) = github.rerunWorkflowRun(runId)
    fun cancelWorkflowRun(runId: Long) = github.cancelWorkflowRun(runId)
    fun loadWorkflowJobs(runId: Long) = github.loadWorkflowJobs(runId)
    fun loadJobLog(jobId: Long) = github.loadJobLog(jobId)
    fun closeWorkflowJobs() = github.closeWorkflowJobs()
    fun refreshToolchains() = tools.refreshToolchains()
    fun installStack(stack: DevStack) = tools.installStack(stack)
    fun removeStack(stack: DevStack) = tools.removeStack(stack)
    fun updateAgent(agent: AgentKind) = tools.updateAgent(agent)
    fun killChrootProcess(pid: Int, force: Boolean = false) = tools.killChrootProcess(pid, force)
    fun refreshCheckpointHistory() = tools.refreshCheckpointHistory()
    fun createSnapshot(label: String) = tools.createSnapshot(label)
    fun restoreSnapshot(id: String) = tools.restoreSnapshot(id)
    fun deleteSnapshot(id: String) = tools.deleteSnapshot(id)
    fun refreshSsh() = tools.refreshSsh()
    fun saveSshHost(host: SshHost) = tools.saveSshHost(host)
    fun deleteSshHost(hostId: String) = tools.deleteSshHost(hostId)
    fun importSshKey(label: String, pem: String) = tools.importSshKey(label, pem)
    fun deleteSshKey(keyId: String) = tools.deleteSshKey(keyId)
    fun installSshClient() = tools.installSshClient()
    fun searchWorkspace(query: String, regex: Boolean, caseSensitive: Boolean) = tools.searchWorkspace(query, regex, caseSensitive)
    fun clearSearch() = tools.clearSearch()
    fun exportProjectZip(projectId: String, target: Uri) = tools.exportProjectZip(projectId, target)
    fun importProjectZip(source: Uri) = tools.importProjectZip(source)
    fun exportVaultBackup(target: Uri, passphrase: CharArray) = tools.exportVaultBackup(target, passphrase)
    fun importVaultBackup(source: Uri, passphrase: CharArray) = tools.importVaultBackup(source, passphrase)
    fun setBatterySaver(enabled: Boolean) = tools.setBatterySaver(enabled)
    fun shareDiagnosticLog() = tools.shareDiagnosticLog()
    // endregion

    fun releaseMemoryCaches() {
        _state.update {
            it.copy(
                terminalOutput = it.terminalOutput.takeLast(16 * 1024),
                androidLogcat = it.androidLogcat.copy(lines = it.androidLogcat.lines.takeLast(100)),
            )
        }
    }

    fun dismissBanner() {
        _state.update { it.copy(bannerMessage = null) }
    }

    override fun onCleared() {
        RuntimeTaskController.requestStopAll()
        tools.onCleared()
        terminal.onCleared()
        androidBuild.onCleared()
        super.onCleared()
    }

    private companion object {
        const val RESUME_REFRESH_MIN_INTERVAL_MS = 5_000L
        const val MAX_CONCURRENT_SESSIONS = 3
    }
}
