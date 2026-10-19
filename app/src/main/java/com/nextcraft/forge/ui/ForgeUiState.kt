package com.nextcraft.forge.ui

import com.nextcraft.forge.model.AgentKind
import com.nextcraft.forge.model.ChangeItem
import com.nextcraft.forge.model.ChatAttachment
import com.nextcraft.forge.model.ChatMessage
import com.nextcraft.forge.model.DevStack
import com.nextcraft.forge.model.Project
import com.nextcraft.forge.model.ProjectChat
import com.nextcraft.forge.model.ProviderKind
import com.nextcraft.forge.model.ProviderProfile
import com.nextcraft.forge.model.ToolRequest
import com.nextcraft.forge.model.WorkspaceEntry
import com.nextcraft.forge.network.DiscoveredModel
import com.nextcraft.forge.network.GitHubDeviceCode
import com.nextcraft.forge.network.GitHubRepository
import com.nextcraft.forge.runtime.AgentUpdateInfo
import com.nextcraft.forge.runtime.AndroidBuildRecord
import com.nextcraft.forge.runtime.AndroidLogcatState
import com.nextcraft.forge.runtime.CheckpointSnapshot
import com.nextcraft.forge.runtime.DiagnosticsSnapshot
import com.nextcraft.forge.runtime.RuntimeSetupSnapshot
import com.nextcraft.forge.runtime.SearchHit
import com.nextcraft.forge.runtime.ToolchainEntry
import com.nextcraft.forge.update.AppUpdateInfo
import java.nio.file.Files

enum class ForgeTab(val title: String) {
    PROJECTS("Projects"),
    AGENT("Agent"),
    TOOLS("Tools"),
    FILES("Files"),
    TERMINAL("Terminal"),
    ANDROID("Android"),
    PREVIEW("Preview"),
    TOOLCHAINS("Toolchains"),
    DIAGNOSTICS("Diagnostics"),
    CHECKPOINTS("Checkpoints"),
    CICD("CI/CD"),
    SSH("SSH"),
    GIT("Git"),
    SETTINGS("Settings"),
}

data class ForgeUiState(
    val arm64Supported: Boolean = true,
    val rootAvailable: Boolean = false,
    val runtimeInstalled: Boolean = false,
    val setupSnapshot: RuntimeSetupSnapshot = RuntimeSetupSnapshot(),
    val selectedDevStacks: Set<DevStack> = setOf(DevStack.WEB, DevStack.PYTHON, DevStack.ANDROID),
    val installedDevStacks: Set<DevStack> = emptySet(),
    val agentKind: AgentKind = AgentKind.CLAUDE_CODE,
    val activeTab: ForgeTab = ForgeTab.AGENT,
    val themeMode: String = "dark",
    val projects: List<Project> = emptyList(),
    val activeProjectId: String = "",
    val chats: List<ProjectChat> = emptyList(),
    val activeChatId: String = "main",
    val messages: List<ChatMessage> = emptyList(),
    val sessionRunning: Boolean = false,
    val activeStatusLine: String? = null,
    val pendingToolRequest: ToolRequest? = null,
    val changedItems: List<ChangeItem> = emptyList(),
    val workspaceFiles: List<WorkspaceEntry> = emptyList(),
    val selectedFilePath: String? = null,
    val selectedFileContent: String = "",
    val selectedFileEditable: Boolean = true,
    val selectedFileDirty: Boolean = false,
    val fileSavedBanner: String? = null,
    val terminalOutput: String = "NextCraft Forge Root Chroot Terminal Ready.\n",
    val terminalRunning: Boolean = false,
    val androidBuild: AndroidBuildRecord = AndroidBuildRecord(),
    val androidLogcat: AndroidLogcatState = AndroidLogcatState(),
    val previewUrl: String = "http://127.0.0.1:3000",
    val activeLocalPorts: List<Int> = emptyList(),
    val providerProfile: ProviderProfile = ProviderProfile(ProviderKind.ANTHROPIC),
    val savedApiKeys: List<com.nextcraft.forge.data.ApiKeyInfo> = emptyList(),
    val discoveredModels: List<DiscoveredModel> = emptyList(),
    val apiStatusMessage: String? = null,
    val apiBusy: Boolean = false,
    val githubLogin: String = "",
    val githubDeviceCode: GitHubDeviceCode? = null,
    val githubRepos: List<GitHubRepository> = emptyList(),
    val githubStatusMessage: String? = null,
    val languageCode: String = "system",
    val interactiveHighRiskApprovals: Boolean = true,
    val autoApproveAllTools: Boolean = false,
    val autoSyncGitHub: Boolean = false,
    val antigravityModel: String = "gemini-2.5-pro",
    val antigravityEffort: String = "high",
    val updateInfo: AppUpdateInfo? = null,
    val updateStatusMessage: String? = null,
    val bannerMessage: String? = null,
    val terminalInteractive: Boolean = false,
    val toolchains: List<ToolchainEntry> = emptyList(),
    val toolchainUpdates: Map<AgentKind, AgentUpdateInfo> = emptyMap(),
    val toolchainBusy: Boolean = false,
    val diagnostics: DiagnosticsSnapshot? = null,
    val checkpointHistory: List<CheckpointSnapshot> = emptyList(),
    val checkpointBusy: Boolean = false,
    val cicd: CicdUiState = CicdUiState(),
    val ssh: SshUiState = SshUiState(),
    /** Projects with a live agent session (the active one is also reflected in [sessionRunning]). */
    val runningProjectIds: Set<String> = emptySet(),
    val pendingAttachments: List<ChatAttachment> = emptyList(),
    val git: GitUiState = GitUiState(),
    val searchQuery: String = "",
    val searchResults: List<SearchHit> = emptyList(),
    val searchBusy: Boolean = false,
    val batterySaver: Boolean = false,
) {
    val activeProject: Project?
        get() = projects.firstOrNull { it.id == activeProjectId } ?: projects.firstOrNull()
}
