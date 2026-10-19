package com.nextcraft.forge.runtime

import android.content.Context
import com.nextcraft.forge.model.ChangeItem
import com.nextcraft.forge.model.DiffLine
import com.nextcraft.forge.model.DiffLineType
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** One tracked workspace file inside a baseline or history manifest. */
internal data class ManifestEntry(val sha256: String, val size: Long, val mtime: Long)

/**
 * Workspace checkpoint / snapshot / diff store shared by agent bridges.
 *
 * Layout under `checkpoints/<project>/`:
 *  - `objects/<sha256>`: content-addressed copies, written once per unique content (0600).
 *  - `latest/manifest.json`: baseline `{path -> {sha, size, mtime}}` taken before the current turn.
 *  - `latest/changes.json`: paths changed since that baseline and not yet reviewed.
 *  - `history/<epochMillis>.json`: named snapshots referencing the same objects (see [CheckpointHistory]).
 *
 * Objects are distinct inodes, so in-place `O_TRUNC` writes by an agent never mutate a baseline.
 */
class WorkspaceCheckpoints(
    private val filesDir: File,
    @Suppress("UNUSED_PARAMETER") private val context: Context? = null,
) {
    private data class FileKey(val path: String, val mtime: Long, val size: Long)

    private val projectRoots = ConcurrentHashMap<String, String>()
    private val projectLocks = ConcurrentHashMap<String, Any>()
    private val sha256Cache = ConcurrentHashMap<FileKey, String>()

    private fun lockFor(projectId: String): Any = projectLocks.computeIfAbsent(projectId) { Any() }

    internal fun <T> withProjectLock(projectId: String, block: () -> T): T =
        synchronized(lockFor(safeProjectId(projectId))) { block() }

    internal fun safeProjectId(projectId: String): String {
        val clean = projectId.trim()
        require(
            clean.isNotEmpty() &&
                clean != "." &&
                !clean.contains('\u0000') &&
                !clean.contains('/') &&
                !clean.contains('\\') &&
                !clean.contains(".."),
        ) {
            "Unsafe project identifier"
        }
        return clean
    }

    fun ensureWorkspace(projectId: String): File {
        val safeId = safeProjectId(projectId)
        val base = File(filesDir, "workspaces/$safeId").apply { mkdirs() }.canonicalFile
        val rootPath = projectRoots[safeId].orEmpty()
        if (rootPath.isBlank()) return base
        val selected = File(base, rootPath).canonicalFile
        require(selected.toPath().startsWith(base.toPath())) { "Unsafe project root" }
        return selected.apply { mkdirs() }
    }

    fun configureProjectRoot(projectId: String, rootPath: String) {
        val safeId = safeProjectId(projectId)
        val normalized = rootPath.replace('\\', '/').trim().trim('/')
        require(
            normalized.isBlank() || (
                !normalized.contains('\u0000') &&
                    !normalized.startsWith('/') &&
                    normalized.split('/').none { it.isEmpty() || it == "." || it == ".." }
                ),
        ) {
            "Unsafe project root"
        }
        synchronized(lockFor(safeId)) {
            val previous = projectRoots.put(safeId, normalized).orEmpty()
            if (previous != normalized) {
                checkpointDir(safeId).deleteRecursively()
                collectGarbageUnlocked(safeId)
            }
        }
    }

    internal fun projectDir(projectId: String) = File(filesDir, "checkpoints/${safeProjectId(projectId)}")
    fun checkpointDir(projectId: String) = File(projectDir(projectId), "latest")
    internal fun objectsDir(projectId: String) = File(projectDir(projectId), "objects")
    internal fun historyDir(projectId: String) = File(projectDir(projectId), "history")
    internal fun objectFile(projectId: String, sha256: String): File {
        require(sha256.length == 64 && sha256.all { it in '0'..'9' || it in 'a'..'f' }) { "Invalid object id" }
        return File(objectsDir(projectId), sha256)
    }

    /** Removes every checkpoint artifact (baseline, history and objects) of a project. */
    fun deleteProjectCheckpoints(projectId: String) {
        val safeId = safeProjectId(projectId)
        synchronized(lockFor(safeId)) { projectDir(safeId).deleteRecursively() }
    }

    fun createCheckpoint(projectId: String, workspace: File) {
        val safeId = safeProjectId(projectId)
        synchronized(lockFor(safeId)) {
            migrateLegacyBaselineUnlocked(safeId)
            val manifestFile = baselineManifestFile(safeId)
            // Keep the original baseline only while unreviewed pending files exist.
            if (manifestFile.isFile && (readChangedPathsUnlocked(safeId).isNotEmpty() || isManifestUnreadable(safeId))) return
            File(checkpointDir(safeId), "changes.json").delete()
            File(checkpointDir(safeId), ".changes.json.tmp").delete()
            val previous = readManifestFile(manifestFile)
            val manifest = captureManifestUnlocked(safeId, workspace, previous)
            writeManifestFile(manifestFile, manifest)
            collectGarbageUnlocked(safeId)
        }
    }

    /**
     * Walks [workspace], stores any content not yet in the object store, and returns the manifest.
     * Files whose size and mtime match [previous] are not re-hashed.
     */
    internal fun captureManifestUnlocked(
        safeId: String,
        workspace: File,
        previous: Map<String, ManifestEntry> = emptyMap(),
    ): Map<String, ManifestEntry> {
        val manifest = LinkedHashMap<String, ManifestEntry>()
        walkWorkspaceFiles(workspace).forEach { source ->
            val relative = source.relativeTo(workspace).invariantSeparatorsPath
            val size = source.length()
            val mtime = source.lastModified()
            val known = previous[relative]
            val entry = if (known != null && known.size == size && known.mtime == mtime && objectFile(safeId, known.sha256).isFile) {
                known
            } else {
                ManifestEntry(storeObject(safeId, source), size, mtime)
            }
            manifest[relative] = entry
        }
        return manifest
    }

    internal fun walkWorkspaceFiles(workspace: File): Sequence<File> {
        val workspacePath = workspace.canonicalFile.toPath()
        return workspace.walkTopDown()
            .onEnter { directory ->
                val relative = if (directory == workspace) "" else directory.relativeTo(workspace).invariantSeparatorsPath
                directory == workspace || (
                    !isInternalRuntimePath(relative) &&
                        !Files.isSymbolicLink(directory.toPath()) &&
                        runCatching { directory.canonicalFile.toPath().startsWith(workspacePath) }.getOrDefault(false)
                    )
            }
            .filter {
                it.isFile &&
                    !isInternalRuntimePath(it.relativeTo(workspace).invariantSeparatorsPath) &&
                    !Files.isSymbolicLink(it.toPath()) &&
                    runCatching { it.canonicalFile.toPath().startsWith(workspacePath) }.getOrDefault(false)
            }
    }

    /** Copies [source] into the object store if its content is new; returns the sha256. */
    internal fun storeObject(safeId: String, source: File): String {
        val sha = digest(source)
        val target = objectFile(safeId, sha)
        if (target.isFile && target.length() == source.length()) return sha
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, ".$sha.${System.nanoTime()}.tmp")
        try {
            source.copyTo(tmp, overwrite = true)
            runCatching { android.system.Os.chmod(tmp.absolutePath, 0b110000000) }
            if (!tmp.renameTo(target)) {
                if (!target.isFile) tmp.copyTo(target, overwrite = true)
            }
        } finally {
            tmp.delete()
        }
        return sha
    }

    internal fun restoreObject(safeId: String, entry: ManifestEntry, target: File) {
        val source = objectFile(safeId, entry.sha256)
        check(source.isFile) { "Checkpoint object is missing" }
        target.parentFile?.mkdirs()
        source.copyTo(target, overwrite = true)
        runCatching { target.setLastModified(entry.mtime) }
    }

    private fun baselineManifestFile(safeId: String) = File(checkpointDir(safeId), "manifest.json")

    internal fun readManifestFile(file: File): Map<String, ManifestEntry> {
        if (!file.isFile) return emptyMap()
        return runCatching {
            val root = JSONObject(file.readText())
            val files = root.optJSONObject("files") ?: root
            val out = LinkedHashMap<String, ManifestEntry>()
            files.keys().forEach { path ->
                val obj = files.optJSONObject(path) ?: return@forEach
                val sha = obj.optString("sha256")
                if (sha.length == 64) out[path] = ManifestEntry(sha, obj.optLong("size"), obj.optLong("mtime"))
            }
            out
        }.getOrDefault(emptyMap())
    }

    internal fun writeManifestFile(file: File, manifest: Map<String, ManifestEntry>, extra: Map<String, Any> = emptyMap()) {
        val files = JSONObject()
        manifest.forEach { (path, entry) ->
            files.put(
                path,
                JSONObject().put("sha256", entry.sha256).put("size", entry.size).put("mtime", entry.mtime),
            )
        }
        val root = JSONObject().put("version", 2).put("files", files)
        extra.forEach { (key, value) -> root.put(key, value) }
        writeManifestAtomically(file, root.toString())
    }

    internal fun readBaselineManifest(projectId: String): Map<String, ManifestEntry> =
        readManifestFile(baselineManifestFile(safeProjectId(projectId)))

    /** Deletes objects no baseline or history snapshot references. Caller holds the project lock. */
    internal fun collectGarbageUnlocked(safeId: String) {
        val objects = objectsDir(safeId)
        if (!objects.isDirectory) return
        val referenced = HashSet<String>()
        readManifestFile(baselineManifestFile(safeId)).values.forEach { referenced += it.sha256 }
        historyDir(safeId).listFiles { f -> f.isFile && f.name.endsWith(".json") }.orEmpty().forEach { file ->
            readManifestFile(file).values.forEach { referenced += it.sha256 }
        }
        objects.listFiles().orEmpty().forEach { file ->
            if (file.name.startsWith(".") || file.name !in referenced) file.delete()
        }
    }

    /** Moves a pre-v2 `latest/project/` tree into the object store once. */
    private fun migrateLegacyBaselineUnlocked(safeId: String) {
        val legacy = File(checkpointDir(safeId), "project")
        if (!legacy.isDirectory) return
        val manifestFile = baselineManifestFile(safeId)
        if (!manifestFile.isFile) {
            val manifest = LinkedHashMap<String, ManifestEntry>()
            walkWorkspaceFiles(legacy).forEach { file ->
                val relative = file.relativeTo(legacy).invariantSeparatorsPath
                manifest[relative] = ManifestEntry(storeObject(safeId, file), file.length(), file.lastModified())
            }
            writeManifestFile(manifestFile, manifest)
        }
        legacy.deleteRecursively()
    }

    fun saveChangedPaths(projectId: String, paths: List<String>) {
        val safeId = safeProjectId(projectId)
        synchronized(lockFor(safeId)) {
            val dir = checkpointDir(safeId).apply { mkdirs() }
            val manifest = File(dir, "changes.json")
            val merged = (readChangedPathsUnlocked(safeId) + paths)
                .filterNot(::isInternalRuntimePath)
                .distinct()
                .sorted()
            writeManifestAtomically(manifest, JSONArray(merged).toString())
        }
    }

    fun readChangedPaths(projectId: String): List<String> {
        val safeId = safeProjectId(projectId)
        return synchronized(lockFor(safeId)) {
            readChangedPathsUnlocked(safeId)
        }
    }

    /** A manifest that exists but cannot be parsed still guards an undo baseline; do not overwrite that baseline. */
    private fun isManifestUnreadable(safeId: String): Boolean {
        val manifest = File(checkpointDir(safeId), "changes.json")
        return manifest.isFile && runCatching { JSONArray(manifest.readText()) }.isFailure
    }

    private fun readChangedPathsUnlocked(safeId: String): List<String> {
        val manifest = File(checkpointDir(safeId), "changes.json")
        if (!manifest.isFile) return emptyList()
        return runCatching {
            val array = JSONArray(manifest.readText())
            (0 until array.length()).map(array::getString)
        }.getOrDefault(emptyList())
    }

    private fun writeRemainingUnlocked(safeId: String, remaining: List<String>) {
        if (remaining.isEmpty()) {
            checkpointDir(safeId).deleteRecursively()
            collectGarbageUnlocked(safeId)
        } else {
            writeManifestAtomically(File(checkpointDir(safeId), "changes.json"), JSONArray(remaining).toString())
        }
    }

    fun removeChangedPath(projectId: String, path: String) {
        val safeId = safeProjectId(projectId)
        synchronized(lockFor(safeId)) {
            writeRemainingUnlocked(safeId, readChangedPathsUnlocked(safeId).filterNot { it == path })
        }
    }

    fun revertFile(projectId: String, workspace: File, path: String): Boolean {
        val safeId = safeProjectId(projectId)
        if (isInternalRuntimePath(path)) return false
        return synchronized(lockFor(safeId)) {
            migrateLegacyBaselineUnlocked(safeId)
            val pending = readChangedPathsUnlocked(safeId)
            if (path !in pending) return@synchronized false
            val manifestFile = baselineManifestFile(safeId)
            if (!manifestFile.isFile) return@synchronized false
            val baseline = readManifestFile(manifestFile)
            val target = safeWorkspaceFile(workspace, path)
            val original = baseline[path]
            if (original != null) {
                restoreObject(safeId, original, target)
            } else if (target.isFile) {
                deleteTargetAndPruneEmptyParents(workspace, target)
            }
            writeRemainingUnlocked(safeId, pending.filterNot { it == path })
            true
        }
    }

    fun revertLatestTurn(projectId: String, workspace: File): List<String> {
        val safeId = safeProjectId(projectId)
        return synchronized(lockFor(safeId)) {
            migrateLegacyBaselineUnlocked(safeId)
            val manifestFile = baselineManifestFile(safeId)
            val paths = readChangedPathsUnlocked(safeId).filterNot(::isInternalRuntimePath)
            if (!manifestFile.isFile || paths.isEmpty()) return@synchronized emptyList()
            val baseline = readManifestFile(manifestFile)
            paths.forEach { path ->
                val target = safeWorkspaceFile(workspace, path)
                val original = baseline[path]
                if (original != null) {
                    restoreObject(safeId, original, target)
                } else if (target.isFile) {
                    deleteTargetAndPruneEmptyParents(workspace, target)
                }
            }
            checkpointDir(safeId).deleteRecursively()
            collectGarbageUnlocked(safeId)
            paths
        }
    }

    fun acceptFile(projectId: String, workspace: File, path: String): Boolean {
        val safeId = safeProjectId(projectId)
        if (isInternalRuntimePath(path)) return false
        return synchronized(lockFor(safeId)) {
            migrateLegacyBaselineUnlocked(safeId)
            val pending = readChangedPathsUnlocked(safeId)
            if (path !in pending) return@synchronized false
            val manifestFile = baselineManifestFile(safeId)
            val baseline = LinkedHashMap(readManifestFile(manifestFile))
            val current = safeWorkspaceFile(workspace, path)
            if (current.isFile) {
                baseline[path] = ManifestEntry(storeObject(safeId, current), current.length(), current.lastModified())
            } else {
                baseline.remove(path)
            }
            val remaining = pending.filterNot { it == path }
            if (remaining.isNotEmpty()) writeManifestFile(manifestFile, baseline)
            writeRemainingUnlocked(safeId, remaining)
            true
        }
    }

    fun acceptAll(projectId: String) {
        val safeId = safeProjectId(projectId)
        synchronized(lockFor(safeId)) {
            checkpointDir(safeId).deleteRecursively()
            collectGarbageUnlocked(safeId)
        }
    }

    internal fun deleteTargetAndPruneEmptyParents(root: File, target: File) {
        val rootCanonical = runCatching { root.canonicalFile }.getOrDefault(root)
        var parent = target.parentFile
        target.delete()
        while (parent != null) {
            val currentParent = parent
            val parentCanonical = runCatching { currentParent.canonicalFile }.getOrDefault(currentParent)
            if (currentParent == root || parentCanonical == rootCanonical || !parentCanonical.toPath().startsWith(rootCanonical.toPath())) {
                break
            }
            val children = currentParent.list()
            if (children != null && children.isEmpty() && currentParent.delete()) {
                parent = currentParent.parentFile
            } else {
                break
            }
        }
    }

    internal fun writeManifestAtomically(manifest: File, content: String) {
        manifest.parentFile?.mkdirs()
        val tmp = File(manifest.parentFile, ".${manifest.name}.tmp")
        tmp.writeText(content)
        if (!tmp.renameTo(manifest)) {
            android.util.Log.w("Checkpoints", "Atomic rename failed for ${manifest.name}; falling back to copy")
            tmp.copyTo(manifest, overwrite = true)
            tmp.delete()
        }
    }

    fun buildChangeDetails(projectId: String, workspace: File, paths: List<String>): List<ChangeItem> {
        val safeId = safeProjectId(projectId)
        val baseline = synchronized(lockFor(safeId)) {
            migrateLegacyBaselineUnlocked(safeId)
            readManifestFile(baselineManifestFile(safeId))
        }
        return paths.map { path ->
            val beforeEntry = baseline[path]?.takeIf { objectFile(safeId, it.sha256).isFile }
            val beforeFile = beforeEntry?.let { objectFile(safeId, it.sha256) }
            val afterFile = safeWorkspaceFile(workspace, path).takeIf(File::isFile)
            if (listOfNotNull(beforeFile, afterFile).any { it.length() > MAX_DIFF_FILE_BYTES }) {
                return@map ChangeItem(
                    path = path,
                    additions = 0,
                    deletions = 0,
                    diffLines = listOf(
                        DiffLine(
                            DiffLineType.INFO,
                            "File is too large to preview safely. Undo and Keep still work.",
                        ),
                    ),
                )
            }
            val before = beforeFile?.readBytes() ?: ByteArray(0)
            val after = afterFile?.readBytes() ?: ByteArray(0)
            val binary = !before.isProbablyText() || !after.isProbablyText()
            val (additions, deletions) = lineChanges(before, after)
            ChangeItem(
                path = path,
                additions = additions,
                deletions = deletions,
                diffLines = buildDiffLines(before, after),
                binary = binary,
            )
        }
    }

    fun buildDiffLines(beforeBytes: ByteArray, afterBytes: ByteArray): List<DiffLine> {
        if (!beforeBytes.isProbablyText() || !afterBytes.isProbablyText()) {
            return listOf(DiffLine(DiffLineType.INFO, "Binary file changed"))
        }
        val before = textLines(beforeBytes)
        val after = textLines(afterBytes)
        if (before.size > MAX_RENDERED_DIFF_LINES || after.size > MAX_RENDERED_DIFF_LINES) {
            return listOf(
                DiffLine(
                    DiffLineType.INFO,
                    "Diff is too large to display (${before.size} → ${after.size} lines). Undo and Keep still work.",
                ),
            )
        }

        val lcs = Array(before.size + 1) { IntArray(after.size + 1) }
        for (oldIndex in before.lastIndex downTo 0) {
            for (newIndex in after.lastIndex downTo 0) {
                lcs[oldIndex][newIndex] = if (before[oldIndex] == after[newIndex]) {
                    lcs[oldIndex + 1][newIndex + 1] + 1
                } else {
                    maxOf(lcs[oldIndex + 1][newIndex], lcs[oldIndex][newIndex + 1])
                }
            }
        }

        val result = mutableListOf<DiffLine>()
        var oldIndex = 0
        var newIndex = 0
        while (oldIndex < before.size || newIndex < after.size) {
            when {
                oldIndex < before.size && newIndex < after.size && before[oldIndex] == after[newIndex] -> {
                    result += DiffLine(DiffLineType.CONTEXT, before[oldIndex], oldIndex + 1, newIndex + 1)
                    oldIndex++
                    newIndex++
                }
                newIndex < after.size && (oldIndex == before.size || lcs[oldIndex][newIndex + 1] >= lcs[oldIndex + 1][newIndex]) -> {
                    result += DiffLine(DiffLineType.ADDITION, after[newIndex], null, newIndex + 1)
                    newIndex++
                }
                oldIndex < before.size -> {
                    result += DiffLine(DiffLineType.DELETION, before[oldIndex], oldIndex + 1, null)
                    oldIndex++
                }
            }
        }
        return collapseUnchangedLines(result)
    }

    private fun collapseUnchangedLines(lines: List<DiffLine>): List<DiffLine> {
        val changedIndexes = lines.indices.filter { lines[it].type != DiffLineType.CONTEXT }
        if (changedIndexes.isEmpty()) return lines
        val visible = BooleanArray(lines.size)
        changedIndexes.forEach { changed ->
            for (index in maxOf(0, changed - DIFF_CONTEXT_LINES)..minOf(lines.lastIndex, changed + DIFF_CONTEXT_LINES)) {
                visible[index] = true
            }
        }
        val result = mutableListOf<DiffLine>()
        var index = 0
        while (index < lines.size) {
            if (visible[index]) {
                result += lines[index++]
            } else {
                val start = index
                while (index < lines.size && !visible[index]) index++
                result += DiffLine(DiffLineType.INFO, "… ${index - start} unchanged lines …")
            }
        }
        return result
    }

    private fun lineChanges(beforeBytes: ByteArray, afterBytes: ByteArray): Pair<Int, Int> {
        if (!beforeBytes.isProbablyText() || !afterBytes.isProbablyText()) {
            return (if (afterBytes.isNotEmpty()) 1 else 0) to (if (beforeBytes.isNotEmpty()) 1 else 0)
        }
        val before = textLines(beforeBytes)
        val after = textLines(afterBytes)
        if (before.size > MAX_DIFF_LINES || after.size > MAX_DIFF_LINES) {
            return maxOf(0, after.size - before.size) to maxOf(0, before.size - after.size)
        }
        var previous = IntArray(after.size + 1)
        before.forEach { oldLine ->
            val current = IntArray(after.size + 1)
            after.forEachIndexed { index, newLine ->
                current[index + 1] = if (oldLine == newLine) {
                    previous[index] + 1
                } else {
                    maxOf(previous[index + 1], current[index])
                }
            }
            previous = current
        }
        val common = previous[after.size]
        return (after.size - common) to (before.size - common)
    }

    private fun ByteArray.isProbablyText(): Boolean =
        size <= MAX_DIFF_FILE_BYTES && none { it == 0.toByte() }

    private fun textLines(bytes: ByteArray): List<String> {
        if (bytes.isEmpty() || !bytes.isProbablyText()) return emptyList()
        val lines = bytes.toString(Charsets.UTF_8).split('\n')
        return if (lines.lastOrNull().isNullOrEmpty()) lines.dropLast(1) else lines
    }

    fun safeWorkspaceFile(root: File, relative: String): File {
        val normalized = relative.replace('\\', '/')
        val segments = normalized.split('/')
        require(
            normalized.isNotBlank() &&
                !normalized.contains('\u0000') &&
                !normalized.startsWith('/') &&
                segments.isNotEmpty() &&
                segments.none { it.isEmpty() || it == "." || it == ".." },
        ) {
            "Unsafe workspace path"
        }
        val file = File(root, normalized)
        val rootCanonical = root.canonicalFile
        val rootPath = rootCanonical.toPath()
        var cursor: File? = file
        while (cursor != null && cursor != root && cursor.canonicalFile != rootCanonical) {
            require(!Files.isSymbolicLink(cursor.toPath())) {
                "Symbolic links cannot be restored or diffed directly"
            }
            cursor = cursor.parentFile
        }
        val parentPath = (file.parentFile ?: root).canonicalFile.toPath()
        require(parentPath.startsWith(rootPath)) { "Workspace path escapes project" }
        val canonicalFile = file.canonicalFile.toPath()
        require(canonicalFile.startsWith(rootPath) && canonicalFile != rootPath) {
            "Workspace path escapes project"
        }
        return file
    }

    fun snapshot(root: File): Map<String, String> =
        walkWorkspaceFiles(root).associate { it.relativeTo(root).invariantSeparatorsPath to digest(it) }

    fun changedFiles(root: File, before: Map<String, String>): List<String> {
        val after = snapshot(root)
        return (before.keys + after.keys).distinct().filter { before[it] != after[it] }.sorted()
    }

    fun isInternalRuntimePath(path: String): Boolean {
        val normalized = path.replace('\\', '/')
        if (normalized == ".claude.json") return true
        var insideSourceTree = false
        for (segment in normalized.split('/')) {
            if (segment in IGNORED_DIRECTORY_NAMES) return true
            // build/dist/target are artifact folders, but a package or directory with that name under src/
            // is real source and must stay tracked (and undoable).
            if (segment in BUILD_OUTPUT_DIRECTORY_NAMES && !insideSourceTree) return true
            if (segment == "src") insideSourceTree = true
        }
        return false
    }

    private fun digest(file: File): String {
        val mtime = file.lastModified()
        val size = file.length()
        val key = if (mtime > 0L) FileKey(file.absolutePath, mtime, size) else null
        if (key != null) {
            sha256Cache[key]?.let { return it }
        }
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        if (key != null && file.lastModified() == mtime && file.length() == size) {
            if (sha256Cache.size > 4_096) sha256Cache.clear()
            sha256Cache[key] = hex
        }
        return hex
    }

    companion object {
        private const val MAX_DIFF_LINES = 2_000
        private const val MAX_RENDERED_DIFF_LINES = 600
        private const val MAX_DIFF_FILE_BYTES = 1_000_000L
        private const val DIFF_CONTEXT_LINES = 3
        private val IGNORED_DIRECTORY_NAMES = setOf(
            ".claude",
            ".nextcraft",
            ".git",
            ".gradle",
            ".idea",
            ".next",
            ".dart_tool",
            ".venv",
            "node_modules",
            "venv",
        )
        private val BUILD_OUTPUT_DIRECTORY_NAMES = setOf("build", "dist", "target")
    }
}
