package com.nextcraft.forge.runtime

import java.io.File
import java.nio.file.Files
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class SearchHit(
    val path: String,
    val line: Int,
    val column: Int,
    val lineText: String,
)

object WorkspaceSearch {
    suspend fun search(
        root: File,
        query: String,
        regex: Boolean = false,
        caseSensitive: Boolean = false,
        maxResults: Int = 500,
        maxFileBytes: Long = 1_000_000L,
    ): List<SearchHit> = withContext(Dispatchers.IO) {
        if (query.isEmpty() || !root.isDirectory) return@withContext emptyList()
        val pattern = runCatching {
            val options = if (caseSensitive) emptySet() else setOf(RegexOption.IGNORE_CASE)
            if (regex) Regex(query, options) else Regex(Regex.escape(query), options)
        }.getOrElse { return@withContext emptyList() }
        val rootPath = root.canonicalFile.toPath()
        val hits = ArrayList<SearchHit>()
        val probe = ByteArray(8 * 1024)
        val files = root.walkTopDown()
            .onEnter { directory ->
                directory == root || (
                    !isIgnoredDirectory(directory.relativeTo(root).invariantSeparatorsPath) &&
                        !Files.isSymbolicLink(directory.toPath()) &&
                        runCatching { directory.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)
                    )
            }
            .filter { it.isFile && !Files.isSymbolicLink(it.toPath()) && it.length() <= maxFileBytes }
        for (file in files) {
            coroutineContext.ensureActive()
            if (!runCatching { file.canonicalFile.toPath().startsWith(rootPath) }.getOrDefault(false)) continue
            if (isBinary(file, probe)) continue
            val relative = file.relativeTo(root).invariantSeparatorsPath
            runCatching {
                file.bufferedReader(Charsets.UTF_8).useLines { lines ->
                    var number = 0
                    for (line in lines) {
                        number++
                        val match = pattern.find(line) ?: continue
                        hits += SearchHit(relative, number, match.range.first + 1, line.take(MAX_LINE_CHARS))
                        if (hits.size >= maxResults) return@useLines
                    }
                }
            }
            if (hits.size >= maxResults) break
        }
        hits
    }

    private fun isBinary(file: File, probe: ByteArray): Boolean = runCatching {
        file.inputStream().use { input ->
            val count = input.read(probe)
            (0 until maxOf(count, 0)).any { probe[it] == 0.toByte() }
        }
    }.getOrDefault(true)

    private fun isIgnoredDirectory(relative: String): Boolean {
        var insideSourceTree = false
        for (segment in relative.split('/')) {
            if (segment in IGNORED_DIRECTORY_NAMES) return true
            if (segment in BUILD_OUTPUT_DIRECTORY_NAMES && !insideSourceTree) return true
            if (segment == "src") insideSourceTree = true
        }
        return false
    }

    private const val MAX_LINE_CHARS = 400
    private val IGNORED_DIRECTORY_NAMES = setOf(
        ".claude", ".git", ".gradle", ".idea", ".next", ".dart_tool", ".venv", "node_modules", "venv", "__pycache__",
    )
    private val BUILD_OUTPUT_DIRECTORY_NAMES = setOf("build", "dist", "target")
}
