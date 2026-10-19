package com.nextcraft.forge.runtime

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

object ProjectArchiver {
    suspend fun exportZip(
        workspace: File,
        out: OutputStream,
        onProgress: suspend (filesWritten: Int, bytesWritten: Long) -> Unit = { _, _ -> },
    ): Int = withContext(Dispatchers.IO) {
        require(workspace.isDirectory) { "Workspace does not exist" }
        val rootPath = workspace.canonicalFile.toPath()
        var files = 0
        var bytes = 0L
        ZipOutputStream(out.buffered()).use { zip ->
            val entries = workspace.walkTopDown()
                .onEnter { directory ->
                    directory == workspace || (
                        directory.name !in IGNORED_DIRECTORY_NAMES &&
                            !Files.isSymbolicLink(directory.toPath()) &&
                            runCatching { directory.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
                        )
                }
                .filter { it != workspace && !Files.isSymbolicLink(it.toPath()) }
            for (file in entries) {
                coroutineContext.ensureActive()
                val relative = file.relativeTo(workspace).invariantSeparatorsPath
                if (file.isDirectory) {
                    zip.putNextEntry(ZipEntry("$relative/"))
                    zip.closeEntry()
                    continue
                }
                if (!file.isFile) continue
                zip.putNextEntry(ZipEntry(relative).apply { time = file.lastModified() })
                file.inputStream().use { input -> bytes += input.copyTo(zip) }
                zip.closeEntry()
                files++
                onProgress(files, bytes)
            }
        }
        files
    }

    /** Extracts into [destination]; refuses zip-slip paths, absolute names, symlinks and oversized archives. */
    suspend fun importZip(input: InputStream, destination: File): Int = withContext(Dispatchers.IO) {
        destination.mkdirs()
        val rootPath = destination.canonicalFile.toPath()
        var entries = 0
        var total = 0L
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                coroutineContext.ensureActive()
                val entry = zip.nextEntry ?: break
                entries++
                check(entries <= MAX_ENTRIES) { "Archive has too many entries" }
                val name = entry.name.replace('\\', '/')
                require(name.isNotBlank() && !name.startsWith('/') && !name.contains('\u0000')) { "Unsafe archive entry" }
                require(name.split('/').none { it == ".." }) { "Archive entry escapes the project" }
                val target = File(destination, name)
                require(runCatching { target.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)) {
                    "Archive entry escapes the project"
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                    zip.closeEntry()
                    continue
                }
                // Unix symlinks are stored with mode 0120000 in the external attributes; java.util.zip
                // does not expose them, so an entry that would replace a symlink is simply skipped.
                if (Files.isSymbolicLink(target.toPath())) {
                    zip.closeEntry()
                    continue
                }
                target.parentFile?.mkdirs()
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = zip.read(buffer)
                        if (count < 0) break
                        total += count
                        check(total <= MAX_TOTAL_BYTES) { "Archive is larger than 2 GB" }
                        output.write(buffer, 0, count)
                    }
                }
                if (entry.time > 0L) runCatching { target.setLastModified(entry.time) }
                zip.closeEntry()
            }
        }
        entries
    }

    private const val MAX_ENTRIES = 50_000
    private const val MAX_TOTAL_BYTES = 2L * 1024L * 1024L * 1024L
    private val IGNORED_DIRECTORY_NAMES = setOf(
        ".git", ".gradle", ".idea", ".next", ".dart_tool", ".venv", "node_modules", "venv", "build", "dist", "target",
    )
}
