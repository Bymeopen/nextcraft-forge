package com.nextcraft.forge.runtime

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Process-wide registry of pending high-risk tool approvals so a notification action
 * (handled by RuntimeExecutionService, outside the bridge) can answer a request owned by a bridge.
 */
object ApprovalRelay {
    private class Entry(val sessionId: String, val respond: suspend (Boolean) -> Unit)

    private val pending = ConcurrentHashMap<String, Entry>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun register(sessionId: String, approvalId: String, respond: suspend (Boolean) -> Unit) {
        pending[approvalId] = Entry(sessionId, respond)
    }

    /** Answers [approvalId] if still pending. Returns false when nothing was waiting for it. */
    fun respond(approvalId: String, approved: Boolean): Boolean {
        val entry = pending.remove(approvalId) ?: return false
        scope.launch { runCatching { entry.respond(approved) } }
        return true
    }

    fun unregister(approvalId: String) {
        pending.remove(approvalId)
    }

    fun clear(sessionId: String) {
        pending.entries.removeIf { it.value.sessionId == sessionId }
    }

    fun pendingIds(sessionId: String): List<String> =
        pending.entries.filter { it.value.sessionId == sessionId }.map { it.key }
}
