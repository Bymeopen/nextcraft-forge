package com.nextcraft.forge.ui

import android.app.Application
import androidx.lifecycle.viewModelScope
import com.nextcraft.forge.model.WorkspaceEntry
import com.nextcraft.forge.runtime.readBoundedText
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class WorkspaceFilesController(private val vm: MainViewModel) {
    private val _state: MutableStateFlow<ForgeUiState> get() = vm._state
    private val viewModelScope: CoroutineScope get() = vm.viewModelScope
    private fun <T : Application> getApplication(): T = vm.getApplication()
    private val preferences get() = vm.preferences
    private val checkpoints get() = vm.checkpoints
    private fun syncProjectWithGitHub(
        projectId: String = _state.value.activeProjectId,
        commitMessage: String = "Sync project changes from NextCraft Forge",
    ) = vm.github.syncProjectWithGitHub(projectId, commitMessage)

    fun refreshWorkspaceFilesAndChanges(projectId: String) {
        viewModelScope.launch {
            val (entries, changes) = withContext(Dispatchers.IO) {
                val ws = checkpoints.ensureWorkspace(projectId)
                val rootCanonical = runCatching { ws.canonicalFile.toPath() }.getOrNull()
                val ignored = setOf(".git", ".gradle", ".nextcraft", "build", "node_modules", "__pycache__")
                val files = if (rootCanonical == null) {
                    emptyList()
                } else {
                    ws.walkTopDown()
                        .onEnter { dir ->
                            dir == ws || (
                                dir.name !in ignored &&
                                    !Files.isSymbolicLink(dir.toPath()) &&
                                    runCatching { dir.canonicalFile.toPath().startsWith(rootCanonical) }.getOrDefault(false)
                                )
                        }
                        .maxDepth(5)
                        .filter { it != ws && !Files.isSymbolicLink(it.toPath()) }
                        .take(250)
                        .map { f ->
                            val rel = f.relativeTo(ws).invariantSeparatorsPath
                            val depth = rel.count { it == '/' }
                            WorkspaceEntry(
                                path = rel,
                                name = f.name,
                                isDirectory = f.isDirectory,
                                depth = depth,
                                sizeBytes = if (f.isFile) f.length() else 0L,
                            )
                        }
                        .toList()
                        .sortedWith { a, b ->
                            val aParts = a.path.split('/')
                            val bParts = b.path.split('/')
                            val minLen = minOf(aParts.size, bParts.size)
                            var cmp = 0
                            for (i in 0 until minLen) {
                                if (aParts[i] != bParts[i]) {
                                    val aDir = (i < aParts.lastIndex) || a.isDirectory
                                    val bDir = (i < bParts.lastIndex) || b.isDirectory
                                    cmp = if (aDir != bDir) {
                                        if (aDir) -1 else 1
                                    } else {
                                        val ci = aParts[i].compareTo(bParts[i], ignoreCase = true)
                                        if (ci != 0) ci else aParts[i].compareTo(bParts[i])
                                    }
                                    break
                                }
                            }
                            if (cmp != 0) cmp else aParts.size.compareTo(bParts.size)
                        }
                }
                val changedPaths = checkpoints.readChangedPaths(projectId)
                val changeDetails = if (changedPaths.isNotEmpty()) {
                    checkpoints.buildChangeDetails(projectId, ws, changedPaths)
                } else {
                    emptyList()
                }
                files to changeDetails
            }
            _state.update {
                it.copy(
                    workspaceFiles = entries,
                    changedItems = changes,
                )
            }
        }
    }

    private var openFileRequest = 0

    fun openWorkspaceFile(relativePath: String) {
        val project = _state.value.activeProject ?: return
        val requestId = ++openFileRequest
        viewModelScope.launch {
            // Saving a truncated, binary or unreadable file back as text would destroy it, so those open read-only.
            val (content, editable) = withContext(Dispatchers.IO) {
                runCatching {
                    val ws = checkpoints.ensureWorkspace(project.id)
                    val target = checkpoints.safeWorkspaceFile(ws, relativePath)
                    if (!target.isFile) {
                        "" to true
                    } else {
                        val head = ByteArray(8192)
                        val headCount = target.inputStream().use { it.read(head) }
                        val binary = (0 until maxOf(headCount, 0)).any { head[it] == 0.toByte() }
                        if (binary || target.length() > 256 * 1024) {
                            "[Read-only: binary file or larger than 256 KB]" to false
                        } else {
                            target.readBoundedText(256 * 1024) to true
                        }
                    }
                }.getOrDefault("[Read-only: file could not be read]" to false)
            }
            // A slower earlier read must not overwrite the file the user opened afterwards.
            if (requestId != openFileRequest) return@launch
            _state.update {
                it.copy(
                    selectedFilePath = relativePath,
                    selectedFileContent = content,
                    selectedFileEditable = editable,
                    selectedFileDirty = false,
                    fileSavedBanner = null,
                )
            }
        }
    }

    fun updateSelectedFileContent(newContent: String) {
        _state.update {
            it.copy(
                selectedFileContent = newContent,
                selectedFileDirty = true,
                fileSavedBanner = null,
            )
        }
    }

    fun saveSelectedFile() {
        val project = _state.value.activeProject ?: return
        val path = _state.value.selectedFilePath ?: return
        if (!_state.value.selectedFileEditable) {
            _state.update { it.copy(fileSavedBanner = "$path is read-only here") }
            return
        }
        val content = _state.value.selectedFileContent
        viewModelScope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    val ws = checkpoints.ensureWorkspace(project.id)
                    val target = checkpoints.safeWorkspaceFile(ws, path)
                    target.parentFile?.mkdirs()
                    target.writeText(content)
                    true
                }.getOrDefault(false)
            }
            _state.update {
                it.copy(
                    selectedFileDirty = if (saved) false else it.selectedFileDirty,
                    fileSavedBanner = if (saved) "Saved $path" else "Failed to save $path",
                )
            }
            refreshWorkspaceFilesAndChanges(project.id)
            if (saved && preferences.autoSyncGitHub) {
                syncProjectWithGitHub(project.id, "Update $path")
            }
        }
    }

    fun createNewWorkspaceFile(relativePath: String, initialContent: String = "") {
        val project = _state.value.activeProject ?: return
        val cleanPath = relativePath.trim().trimStart('/')
        if (cleanPath.isEmpty()) return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val ws = checkpoints.ensureWorkspace(project.id)
                    val target = checkpoints.safeWorkspaceFile(ws, cleanPath)
                    target.parentFile?.mkdirs()
                    if (!target.exists()) target.writeText(initialContent)
                    true
                }.getOrDefault(false)
            }
            if (ok) {
                refreshWorkspaceFilesAndChanges(project.id)
                openWorkspaceFile(cleanPath)
            }
        }
    }

    fun deleteWorkspaceFile(relativePath: String) {
        if (_state.value.sessionRunning) return
        val project = _state.value.activeProject ?: return
        val cleanPath = relativePath.trim().trimStart('/')
        if (cleanPath.isEmpty()) return
        viewModelScope.launch {
            val deleted = withContext(Dispatchers.IO) {
                runCatching {
                    val ws = checkpoints.ensureWorkspace(project.id)
                    val target = checkpoints.safeWorkspaceFile(ws, cleanPath)
                    target.exists() && target.deleteRecursively()
                }.getOrDefault(false)
            }
            if (deleted) {
                if (_state.value.selectedFilePath == cleanPath || _state.value.selectedFilePath?.startsWith("$cleanPath/") == true) {
                    _state.update {
                        it.copy(
                            selectedFilePath = null,
                            selectedFileContent = "",
                            selectedFileDirty = false,
                            fileSavedBanner = "Deleted $cleanPath",
                        )
                    }
                } else {
                    _state.update { it.copy(fileSavedBanner = "Deleted $cleanPath") }
                }
                refreshWorkspaceFilesAndChanges(project.id)
            }
        }
    }

    fun revertSingleFile(path: String) {
        if (_state.value.sessionRunning) return
        val project = _state.value.activeProject ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val ws = checkpoints.ensureWorkspace(project.id)
                checkpoints.revertFile(project.id, ws, path)
            }
            refreshWorkspaceFilesAndChanges(project.id)
            if (_state.value.selectedFilePath == path) {
                openWorkspaceFile(path)
            }
        }
    }

    fun keepSingleFile(path: String) {
        if (_state.value.sessionRunning) return
        val project = _state.value.activeProject ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val ws = checkpoints.ensureWorkspace(project.id)
                checkpoints.acceptFile(project.id, ws, path)
            }
            refreshWorkspaceFilesAndChanges(project.id)
        }
    }

    fun keepAllChanges() {
        if (_state.value.sessionRunning) return
        val project = _state.value.activeProject ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { checkpoints.acceptAll(project.id) }
            refreshWorkspaceFilesAndChanges(project.id)
        }
    }

    fun revertAllChanges() {
        if (_state.value.sessionRunning) return
        val project = _state.value.activeProject ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val ws = checkpoints.ensureWorkspace(project.id)
                checkpoints.revertLatestTurn(project.id, ws)
            }
            refreshWorkspaceFilesAndChanges(project.id)
        }
    }

}
