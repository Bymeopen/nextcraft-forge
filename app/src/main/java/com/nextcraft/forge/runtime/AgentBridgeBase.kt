package com.nextcraft.forge.runtime

import android.content.Context
import com.nextcraft.forge.model.ChangeItem
import com.nextcraft.forge.model.RuntimeEvent
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.withContext

/**
 * Session bookkeeping shared by every agent bridge: event bus, foreground-service
 * notifications, stop handling and checkpoint delegation. All mutable state is per instance;
 * the ViewModel may hold one bridge per project.
 */
abstract class AgentBridgeBase(
    protected val context: Context,
    private val logTag: String,
    private val agentTitle: String,
) : RuntimeBridge {
    protected val checkpoints: WorkspaceCheckpoints by lazy { WorkspaceCheckpoints(context.filesDir, context) }
    protected val history: CheckpointHistory by lazy { CheckpointHistory(checkpoints) }
    protected val eventBus = MutableSharedFlow<RuntimeEvent>(
        extraBufferCapacity = 1024,
        onBufferOverflow = BufferOverflow.SUSPEND,
    )
    override val events: Flow<RuntimeEvent> = eventBus
    protected val finishedSessions: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile protected var activeProcess: Process? = null
    @Volatile protected var activeSessionId: String? = null
    @Volatile protected var userStopRequested: Boolean = false
    @Volatile protected var activeProjectSlug: String? = null
    @Volatile protected var taskStartedAtElapsedRealtime: Long = 0L
    @Volatile private var lastForegroundProgressAt: Long = 0L
    @Volatile private var foregroundResultPosted: Boolean = false

    /** Resets per-session state; call first thing in startSession. */
    protected fun beginSession(sessionId: String, projectSlug: String) {
        finishedSessions.remove(sessionId)
        activeSessionId = sessionId
        userStopRequested = false
        activeProjectSlug = projectSlug
        taskStartedAtElapsedRealtime = android.os.SystemClock.elapsedRealtime()
        lastForegroundProgressAt = 0L
        foregroundResultPosted = false
    }

    /** Clears per-session state; call last in startSession. */
    protected fun endSession(sessionId: String) {
        activeProcess = null
        activeSessionId = null
        RuntimeTaskController.release(sessionId)
    }

    /** Registers a stop hook for [sessionId] that kills the active process from a daemon thread. */
    protected fun installStopHandler(sessionId: String): () -> Unit {
        val handler: () -> Unit = {
            userStopRequested = true
            val running = activeProcess
            if (running != null) {
                Thread({
                    runCatching {
                        running.destroy()
                        Thread.sleep(500)
                        if (running.isAlive) running.destroyForcibly()
                    }
                }, "forge-$logTag-stop").apply { isDaemon = true }.start()
            }
        }
        RuntimeTaskController.register(sessionId, handler)
        return handler
    }

    /** Kills the active process after a failure so it never keeps running unattended in the chroot. */
    protected fun killActiveProcessNow() {
        activeProcess?.let { running -> runCatching { if (running.isAlive) running.destroyForcibly() } }
    }

    override suspend fun stopSession(sessionId: String) = withContext(Dispatchers.IO) {
        if (activeSessionId == sessionId) {
            userStopRequested = true
            activeProcess?.destroy()
            delay(500)
            if (activeProcess?.isAlive == true) activeProcess?.destroyForcibly()
            emitFailureOnce(sessionId, "Stopped by user")
        }
    }

    override suspend fun stopActiveSession() {
        activeSessionId?.let { stopSession(it) }
    }

    fun configureProjectRoot(projectId: String, rootPath: String) {
        checkpoints.configureProjectRoot(projectId, rootPath)
    }

    override suspend fun undoLastChanges(projectId: String): Boolean = withContext(Dispatchers.IO) {
        val workspace = checkpoints.ensureWorkspace(projectId)
        checkpoints.revertLatestTurn(projectId, workspace).isNotEmpty()
    }

    override suspend fun acceptLastChanges(projectId: String) {
        withContext(Dispatchers.IO) { checkpoints.acceptAll(projectId) }
    }

    override suspend fun loadPendingChanges(projectId: String): List<ChangeItem> = withContext(Dispatchers.IO) {
        val workspace = checkpoints.ensureWorkspace(projectId)
        val paths = checkpoints.readChangedPaths(projectId).filterNot(checkpoints::isInternalRuntimePath)
        if (paths.isEmpty()) emptyList() else checkpoints.buildChangeDetails(projectId, workspace, paths)
    }

    override suspend fun undoFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        val workspace = checkpoints.ensureWorkspace(projectId)
        checkpoints.revertFile(projectId, workspace, path)
    }

    override suspend fun acceptFileChange(projectId: String, path: String): Boolean = withContext(Dispatchers.IO) {
        val workspace = checkpoints.ensureWorkspace(projectId)
        checkpoints.acceptFile(projectId, workspace, path)
    }

    /** Publishes changed paths after a turn, or clears a stale baseline when nothing changed. */
    protected suspend fun publishWorkspaceChanges(
        sessionId: String,
        projectId: String,
        workspace: java.io.File,
        before: Map<String, String>,
    ) {
        val changed = checkpoints.changedFiles(workspace, before)
        if (changed.isNotEmpty()) {
            android.util.Log.d(logTag, "Changed files (${changed.size})")
            checkpoints.saveChangedPaths(projectId, changed)
            eventBus.emit(RuntimeEvent.FilesChanged(sessionId, loadPendingChanges(projectId)))
        } else if (checkpoints.readChangedPaths(projectId).isEmpty()) {
            acceptLastChanges(projectId)
        }
    }

    protected suspend fun emitCompletedOnce(sessionId: String) {
        if (finishedSessions.add(sessionId)) {
            eventBus.emit(RuntimeEvent.SessionCompleted(sessionId))
            finishForegroundRuntime(
                completed = true,
                projectName = activeProjectSlug ?: "Project",
                detail = "$agentTitle finished",
            )
        }
    }

    protected suspend fun emitFailureOnce(sessionId: String, reason: String) {
        if (finishedSessions.add(sessionId)) {
            eventBus.emit(RuntimeEvent.SessionFailed(sessionId, reason))
            if (userStopRequested) {
                cancelForegroundRuntime()
            } else {
                finishForegroundRuntime(
                    completed = false,
                    projectName = activeProjectSlug ?: "Project",
                    detail = reason,
                )
            }
        }
    }

    protected fun pushForegroundProgress(detailRaw: String) {
        if (activeSessionId == null) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastForegroundProgressAt < foregroundProgressMinIntervalMs) return
        lastForegroundProgressAt = now
        val detail = detailRaw.replace(Regex("\\s+"), " ").trim().take(110)
        val elapsedMs = taskStartedAtElapsedRealtime.takeIf { it > 0 }?.let { now - it } ?: 0L
        val text = if (elapsedMs > 0L) "$detail · ${formatElapsedShort(elapsedMs)}" else detail
        RuntimeExecutionServiceContract.pushProgress(context, activeProjectSlug, text)
    }

    protected fun formatElapsedShort(milliseconds: Long): String {
        val totalSeconds = milliseconds / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
    }

    protected fun startForegroundRuntime(projectName: String) {
        RuntimeExecutionServiceContract.startForeground(context, projectName)
    }

    protected fun finishForegroundRuntime(completed: Boolean, projectName: String, detail: String) {
        if (foregroundResultPosted) return
        foregroundResultPosted = true
        RuntimeExecutionServiceContract.finishForeground(context, completed, projectName, detail, logTag)
    }

    protected fun cancelForegroundRuntime() {
        if (foregroundResultPosted) return
        foregroundResultPosted = true
        RuntimeExecutionServiceContract.cancelForeground(context)
    }

    companion object {
        const val DEFAULT_FOREGROUND_PROGRESS_INTERVAL_MS = 750L

        /** Process-wide throttle for foreground-notification updates; battery saver doubles it. */
        @Volatile var foregroundProgressMinIntervalMs: Long = DEFAULT_FOREGROUND_PROGRESS_INTERVAL_MS
    }
}
