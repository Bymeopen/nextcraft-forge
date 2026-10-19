package com.nextcraft.forge.ui

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.nextcraft.forge.model.AgentKind
import com.nextcraft.forge.model.ProviderKind
import com.nextcraft.forge.model.ProviderProfile
import com.nextcraft.forge.model.providerProtocolForAgent
import com.nextcraft.forge.network.ConnectionValidation
import com.nextcraft.forge.network.ModelDiscoveryResult
import com.nextcraft.forge.runtime.AndroidAppInstaller
import com.nextcraft.forge.runtime.AntigravityRuntimeBridge
import com.nextcraft.forge.runtime.ClaudeRuntimeBridge
import com.nextcraft.forge.update.AppUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class ProviderSettingsController(private val vm: MainViewModel) {
    private val _state: MutableStateFlow<ForgeUiState> get() = vm._state
    private val viewModelScope: CoroutineScope get() = vm.viewModelScope
    private fun <T : Application> getApplication(): T = vm.getApplication()
    private val preferences get() = vm.preferences
    private val vault get() = vm.vault
    private val providerApi get() = vm.providerApi
    private val bridges get() = vm.bridges
    private fun resolveProviderSecret(profile: ProviderProfile): String? = vm.resolveProviderSecret(profile)

    fun updateProviderKind(kind: ProviderKind) {
        val candidate = ProviderProfile(
            kind = kind,
            baseUrl = kind.defaultBaseUrl,
            model = kind.defaultModel,
        )
        val updated = candidate.copy(hasSecret = !resolveProviderSecret(candidate).isNullOrBlank())
        preferences.saveProvider(updated, _state.value.agentKind)
        _state.update {
            it.copy(
                providerProfile = updated,
                savedApiKeys = vault.list(kind.name),
                discoveredModels = emptyList(),
                apiStatusMessage = null,
            )
        }
    }

    fun updateProviderConfig(
        baseUrl: String,
        model: String,
        openRouterOrder: String = _state.value.providerProfile.openRouterProviderOrder,
        openRouterAllowFallbacks: Boolean = _state.value.providerProfile.openRouterAllowFallbacks,
        dshApi: String = _state.value.providerProfile.dshApi,
    ) {
        val current = _state.value.providerProfile
        val updated = current.copy(
            baseUrl = baseUrl.trim(),
            model = model.trim(),
            openRouterProviderOrder = openRouterOrder.trim(),
            openRouterAllowFallbacks = openRouterAllowFallbacks,
            dshApi = dshApi.trim().ifBlank { current.dshApi },
            hasSecret = !resolveProviderSecret(current).isNullOrBlank(),
        )
        preferences.saveProvider(updated, _state.value.agentKind)
        _state.update { it.copy(providerProfile = updated) }
    }

    fun validateAntigravityConnection() {
        _state.update { it.copy(apiBusy = true, apiStatusMessage = "Probing Antigravity CLI inside chroot…") }
        viewModelScope.launch {
            val msg = runCatching {
                val probeProjectId = _state.value.activeProjectId
                val bridge = bridges.bridge(probeProjectId, AgentKind.ANTIGRAVITY) as AntigravityRuntimeBridge
                val reply = bridge.hello(45_000L)
                "✓ Antigravity CLI verified: ${reply.take(100)}"
            }.getOrElse { err ->
                "✕ Antigravity probe failed: ${err.message ?: "CLI error"}"
            }
            _state.update { it.copy(apiBusy = false, apiStatusMessage = msg) }
        }
    }

    fun addApiKey(label: String, secret: String) {
        val cleanSecret = secret.trim()
        if (cleanSecret.isEmpty()) return
        val providerId = _state.value.providerProfile.kind.name
        runCatching { vault.add(providerId, label.trim().ifBlank { "Primary Key" }, cleanSecret) }
            .onFailure { err ->
                _state.update { it.copy(apiStatusMessage = err.message ?: "Could not save the API key.") }
                return
            }
        val keys = vault.list(providerId)
        val updatedProfile = _state.value.providerProfile.copy(hasSecret = keys.isNotEmpty())
        preferences.saveProvider(updatedProfile, _state.value.agentKind)
        _state.update {
            it.copy(
                providerProfile = updatedProfile,
                savedApiKeys = keys,
                apiStatusMessage = "API key saved securely in Android Keystore.",
            )
        }
    }

    fun selectActiveApiKey(keyId: String) {
        val providerId = _state.value.providerProfile.kind.name
        vault.activate(providerId, keyId)
        _state.update { it.copy(savedApiKeys = vault.list(providerId)) }
    }

    fun removeApiKey(keyId: String) {
        val providerId = _state.value.providerProfile.kind.name
        vault.remove(providerId, keyId)
        val keys = vault.list(providerId)
        _state.update {
            it.copy(
                providerProfile = it.providerProfile.copy(hasSecret = !resolveProviderSecret(it.providerProfile).isNullOrBlank()),
                savedApiKeys = keys,
            )
        }
    }

    fun discoverProviderModels() {
        val profile = _state.value.providerProfile
        val apiKey = resolveProviderSecret(profile).orEmpty()
        val protocol = providerProtocolForAgent(profile, _state.value.agentKind)
        _state.update { it.copy(apiBusy = true, apiStatusMessage = "Fetching model catalog…") }
        viewModelScope.launch {
            when (val res = providerApi.discoverModels(profile.resolvedBaseUrl, apiKey, protocol)) {
                is ModelDiscoveryResult.Success -> {
                    _state.update {
                        it.copy(
                            apiBusy = false,
                            discoveredModels = res.models,
                            apiStatusMessage = "Discovered ${res.models.size} models from ${res.endpoint}",
                        )
                    }
                }
                is ModelDiscoveryResult.Failure -> {
                    _state.update {
                        it.copy(
                            apiBusy = false,
                            apiStatusMessage = listOfNotNull(res.message, res.providerMessage).joinToString(" — "),
                        )
                    }
                }
            }
        }
    }

    fun validateProviderConnection() {
        val profile = _state.value.providerProfile
        val apiKey = resolveProviderSecret(profile).orEmpty()
        val protocol = providerProtocolForAgent(profile, _state.value.agentKind)
        _state.update { it.copy(apiBusy = true, apiStatusMessage = "Validating connection…") }
        viewModelScope.launch {
            var res = providerApi.validate(
                baseUrl = profile.resolvedBaseUrl,
                model = profile.model,
                apiKey = apiKey,
                protocol = protocol,
                discoveredModels = _state.value.discoveredModels,
                openRouterProviderOrder = profile.openRouterProviderOrder,
                openRouterAllowFallbacks = profile.openRouterAllowFallbacks,
            )
            if (res is ConnectionValidation.Failure &&
                _state.value.agentKind == AgentKind.CLAUDE_CODE &&
                _state.value.runtimeInstalled &&
                (profile.kind == ProviderKind.CLAUDE || apiKey.startsWith("sk-ant-oat"))
            ) {
                val cliProbe = runCatching {
                    (bridges.bridge(_state.value.activeProjectId, AgentKind.CLAUDE_CODE) as ClaudeRuntimeBridge).hello(profile, 45_000L)
                }.getOrNull()
                if (!cliProbe.isNullOrBlank()) {
                    res = ConnectionValidation.Success("Claude Code CLI verified (${cliProbe.take(80)})")
                }
            }
            val msg = when (res) {
                is ConnectionValidation.Success -> "✓ ${res.message}"
                is ConnectionValidation.Failure -> "✕ ${res.label}: ${res.message} ${res.providerMessage.orEmpty()}".trim()
            }
            _state.update { it.copy(apiBusy = false, apiStatusMessage = msg) }
        }
    }

    fun setLanguageCode(code: String) {
        val clean = code.trim().ifEmpty { "system" }
        AppStrings.saveLanguageCode(getApplication(), clean)
        _state.update { it.copy(languageCode = clean) }
    }

    fun setInteractiveHighRiskApprovals(enabled: Boolean) {
        preferences.interactiveHighRiskApprovals = enabled
        if (enabled && preferences.autoApproveAllTools) {
            preferences.autoApproveAllTools = false
        }
        _state.update {
            it.copy(
                interactiveHighRiskApprovals = enabled,
                autoApproveAllTools = preferences.autoApproveAllTools,
            )
        }
    }

    fun setAutoApproveAllTools(enabled: Boolean) {
        preferences.autoApproveAllTools = enabled
        if (enabled && preferences.interactiveHighRiskApprovals) {
            preferences.interactiveHighRiskApprovals = false
        }
        _state.update {
            it.copy(
                autoApproveAllTools = enabled,
                interactiveHighRiskApprovals = preferences.interactiveHighRiskApprovals,
            )
        }
    }

    fun setAutoSyncGitHub(enabled: Boolean) {
        preferences.autoSyncGitHub = enabled
        _state.update { it.copy(autoSyncGitHub = enabled) }
    }

    fun setAntigravityConfig(model: String, effort: String) {
        val cleanModel = model.trim()
        val cleanEffort = effort.trim().ifEmpty { "high" }
        preferences.antigravityModel = cleanModel
        preferences.antigravityEffort = cleanEffort
        _state.update {
            it.copy(
                antigravityModel = cleanModel,
                antigravityEffort = cleanEffort,
            )
        }
    }

    fun checkForAppUpdates(manifestUrlOverride: String = "") {
        viewModelScope.launch {
            _state.update { it.copy(updateStatusMessage = "Checking for updates…") }
            val result = withContext(Dispatchers.IO) {
                runCatching { AppUpdater(getApplication(), manifestUrlOverride.trim()).check() }
            }
            val info = result.getOrNull()
            _state.update {
                it.copy(
                    updateInfo = info,
                    updateStatusMessage = if (result.isFailure) {
                        "Update check failed: ${result.exceptionOrNull()?.message ?: "network error"}"
                    } else if (info != null) {
                        "Update available: v${info.versionName} (${info.versionCode})"
                    } else {
                        "NextCraft Forge is up to date."
                    },
                )
            }
        }
    }

    fun downloadAndInstallAppUpdate() {
        val info = _state.value.updateInfo ?: return
        viewModelScope.launch {
            _state.update { it.copy(updateStatusMessage = "Downloading v${info.versionName}…") }
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val apk = AppUpdater(getApplication()).download(info) { downloaded, total ->
                        val pct = if (total > 0) ((downloaded * 100L) / total).coerceIn(0L, 100L) else -1L
                        val msg = if (pct >= 0) {
                            "Downloading v${info.versionName} ($pct%)…"
                        } else {
                            "Downloading v${info.versionName} (${downloaded / 1024} KB)…"
                        }
                        _state.update { it.copy(updateStatusMessage = msg) }
                    }
                    AndroidAppInstaller.install(
                        context = getApplication(),
                        apk = apk,
                        launchAfterInstall = false,
                    )
                }
            }
            result.onSuccess {
                _state.update { it.copy(updateStatusMessage = "Update v${info.versionName} verified & installing…") }
            }.onFailure { err ->
                _state.update { it.copy(updateStatusMessage = "Update install failed: ${err.message}") }
            }
        }
    }

    fun setThemeMode(mode: String) {
        val resolved = ForgeThemeMode.fromStored(mode).id
        preferences.themeMode = resolved
        _state.update { it.copy(themeMode = resolved) }
    }

    fun toggleThemeMode() {
        val current = ForgeThemeMode.fromStored(_state.value.themeMode)
        val entries = ForgeThemeMode.entries
        val next = entries[(current.ordinal + 1) % entries.size]
        setThemeMode(next.id)
    }

}
