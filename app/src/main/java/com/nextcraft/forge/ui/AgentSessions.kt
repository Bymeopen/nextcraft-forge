package com.nextcraft.forge.ui

import com.nextcraft.forge.model.AgentKind
import com.nextcraft.forge.model.ChatMessage
import com.nextcraft.forge.model.RuntimeEvent
import com.nextcraft.forge.model.ToolRequest
import com.nextcraft.forge.runtime.AgentBridgeBase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Live state of one project's agent session. The ViewModel keeps one per project so several
 * projects can run at once; the active project's copy is mirrored into [ForgeUiState].
 */
data class ProjectSessionState(
    val projectId: String,
    val chatId: String,
    val agentKind: AgentKind,
    val messages: List<ChatMessage>,
    val sessionRunning: Boolean = false,
    val activeStatusLine: String? = null,
    val pendingToolRequest: ToolRequest? = null,
    /** Bridge-side session id, known once SessionStarted arrives; events for other ids are stale. */
    val runtimeSessionId: String? = null,
)

/**
 * One bridge instance per (project, agent). Each bridge's event flow is collected once and
 * forwarded with its project id, so the ViewModel never has to map session ids back to projects.
 */
class AgentBridgeRegistry(
    private val scope: CoroutineScope,
    private val factory: (projectId: String, agent: AgentKind) -> AgentBridgeBase,
    private val onEvent: (projectId: String, event: RuntimeEvent) -> Unit,
) {
    private class Entry(val bridge: AgentBridgeBase, val collector: Job)

    private val entries = HashMap<Pair<String, AgentKind>, Entry>()

    @Synchronized
    fun bridge(projectId: String, agent: AgentKind): AgentBridgeBase {
        val key = projectId to agent
        entries[key]?.let { return it.bridge }
        val bridge = factory(projectId, agent)
        val collector = scope.launch { bridge.events.collect { event -> onEvent(projectId, event) } }
        entries[key] = Entry(bridge, collector)
        return bridge
    }

    @Synchronized
    fun existing(projectId: String, agent: AgentKind): AgentBridgeBase? = entries[projectId to agent]?.bridge

    @Synchronized
    fun forProject(projectId: String): List<AgentBridgeBase> =
        entries.filterKeys { it.first == projectId }.values.map { it.bridge }

    @Synchronized
    fun all(): List<AgentBridgeBase> = entries.values.map { it.bridge }

    @Synchronized
    fun remove(projectId: String) {
        val keys = entries.keys.filter { it.first == projectId }
        keys.forEach { key -> entries.remove(key)?.collector?.cancel() }
    }
}
