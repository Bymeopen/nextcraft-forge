package com.nextcraft.forge.runtime

import java.io.File
import org.json.JSONObject

data class CheckpointSnapshot(
    val id: String,
    val label: String,
    val createdAtMillis: Long,
    val fileCount: Int,
)

/**
 * Named workspace snapshots layered on the [WorkspaceCheckpoints] object store.
 * Each snapshot is one manifest under `checkpoints/<project>/history/<epochMillis>.json`;
 * file bytes are shared with the baseline, so a snapshot costs only the content that is new.
 */
class CheckpointHistory(private val checkpoints: WorkspaceCheckpoints) {

    fun snapshot(projectId: String, workspace: File, label: String): CheckpointSnapshot {
        val safeId = checkpoints.safeProjectId(projectId)
        return checkpoints.withProjectLock(safeId) {
            val previous = checkpoints.readBaselineManifest(safeId)
            val manifest = checkpoints.captureManifestUnlocked(safeId, workspace, previous)
            val createdAt = System.currentTimeMillis()
            var file = File(checkpoints.historyDir(safeId), "$createdAt.json")
            while (file.exists()) file = File(checkpoints.historyDir(safeId), "${createdAt + 1}.json")
            checkpoints.writeManifestFile(
                file,
                manifest,
                extra = mapOf("label" to label.take(120), "createdAt" to createdAt),
            )
            pruneUnlocked(safeId)
            CheckpointSnapshot(file.nameWithoutExtension, label.take(120), createdAt, manifest.size)
        }
    }

    fun list(projectId: String): List<CheckpointSnapshot> {
        val safeId = checkpoints.safeProjectId(projectId)
        return checkpoints.withProjectLock(safeId) { listUnlocked(safeId) }
    }

    /** Rewrites [workspace] to the snapshot: restores tracked files, deletes untracked ones (internal paths excepted). */
    fun restore(projectId: String, workspace: File, id: String): Boolean {
        val safeId = checkpoints.safeProjectId(projectId)
        return checkpoints.withProjectLock(safeId) {
            val file = snapshotFile(safeId, id) ?: return@withProjectLock false
            val manifest = checkpoints.readManifestFile(file)
            if (manifest.isEmpty() && !file.isFile) return@withProjectLock false
            checkpoints.walkWorkspaceFiles(workspace).toList().forEach { current ->
                val relative = current.relativeTo(workspace).invariantSeparatorsPath
                if (relative !in manifest) checkpoints.deleteTargetAndPruneEmptyParents(workspace, current)
            }
            manifest.forEach { (relative, entry) ->
                val target = checkpoints.safeWorkspaceFile(workspace, relative)
                if (target.isFile && target.length() == entry.size && target.lastModified() == entry.mtime) return@forEach
                checkpoints.restoreObject(safeId, entry, target)
            }
            // The restored tree is the new reviewed state; pending-change tracking restarts at the next turn.
            checkpoints.checkpointDir(safeId).deleteRecursively()
            checkpoints.collectGarbageUnlocked(safeId)
            true
        }
    }

    fun delete(projectId: String, id: String): Boolean {
        val safeId = checkpoints.safeProjectId(projectId)
        return checkpoints.withProjectLock(safeId) {
            val removed = snapshotFile(safeId, id)?.delete() == true
            if (removed) checkpoints.collectGarbageUnlocked(safeId)
            removed
        }
    }

    private fun snapshotFile(safeId: String, id: String): File? {
        if (id.isBlank() || !id.all { it.isDigit() }) return null
        return File(checkpoints.historyDir(safeId), "$id.json").takeIf(File::isFile)
    }

    private fun listUnlocked(safeId: String): List<CheckpointSnapshot> =
        checkpoints.historyDir(safeId).listFiles { f -> f.isFile && f.name.endsWith(".json") && !f.name.startsWith(".") }
            .orEmpty()
            .mapNotNull { file ->
                val id = file.nameWithoutExtension
                if (!id.all { it.isDigit() }) return@mapNotNull null
                val root = runCatching { JSONObject(file.readText()) }.getOrNull() ?: return@mapNotNull null
                CheckpointSnapshot(
                    id = id,
                    label = root.optString("label").ifBlank { "Snapshot" },
                    createdAtMillis = root.optLong("createdAt", id.toLongOrNull() ?: 0L),
                    fileCount = root.optJSONObject("files")?.length() ?: 0,
                )
            }
            .sortedByDescending { it.createdAtMillis }

    private fun pruneUnlocked(safeId: String) {
        val stale = listUnlocked(safeId).drop(MAX_SNAPSHOTS_PER_PROJECT)
        if (stale.isEmpty()) return
        stale.forEach { snapshotFile(safeId, it.id)?.delete() }
        checkpoints.collectGarbageUnlocked(safeId)
    }

    private companion object {
        const val MAX_SNAPSHOTS_PER_PROJECT = 10
    }
}
