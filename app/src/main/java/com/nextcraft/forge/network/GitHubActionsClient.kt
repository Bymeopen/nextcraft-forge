package com.nextcraft.forge.network

import com.nextcraft.forge.model.stringOrEmpty
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

data class Workflow(
    val id: Long,
    val name: String,
    val path: String,
    val state: String,
)

data class WorkflowRun(
    val id: Long,
    val name: String,
    val status: String,
    val conclusion: String,
    val headBranch: String,
    val event: String,
    val createdAt: String,
    val updatedAt: String,
    val htmlUrl: String,
    val runNumber: Long,
)

data class WorkflowJob(
    val id: Long,
    val name: String,
    val status: String,
    val conclusion: String,
    val steps: List<String>,
)

/** Parses `https://github.com/o/r(.git)` and `git@github.com:o/r(.git)` into owner to repo. */
fun parseGitHubRemote(url: String): Pair<String, String>? {
    val trimmed = url.trim()
    val path = when {
        trimmed.startsWith("git@github.com:") -> trimmed.removePrefix("git@github.com:")
        trimmed.startsWith("ssh://git@github.com/") -> trimmed.removePrefix("ssh://git@github.com/")
        trimmed.startsWith("https://github.com/") -> trimmed.removePrefix("https://github.com/")
        trimmed.startsWith("http://github.com/") -> trimmed.removePrefix("http://github.com/")
        else -> return null
    }
    val parts = path.removeSuffix("/").removeSuffix(".git").split('/')
    if (parts.size < 2) return null
    val owner = parts[0]
    val repo = parts[1]
    val valid = Regex("^[A-Za-z0-9_.-]+$")
    if (!valid.matches(owner) || !valid.matches(repo)) return null
    return owner to repo
}

class GitHubActionsClient {

    fun listWorkflows(token: String, owner: String, repo: String): List<Workflow> {
        val json = getJson("${repoBase(owner, repo)}/actions/workflows?per_page=100", token) as? JSONObject
            ?: error("Unexpected GitHub workflows response")
        val array = json.optJSONArray("workflows") ?: JSONArray()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            Workflow(
                id = item.optLong("id"),
                name = item.stringOrEmpty("name"),
                path = item.stringOrEmpty("path"),
                state = item.stringOrEmpty("state"),
            )
        }
    }

    fun listRuns(token: String, owner: String, repo: String, perPage: Int = 20): List<WorkflowRun> {
        val json = getJson(
            "${repoBase(owner, repo)}/actions/runs?per_page=${perPage.coerceIn(1, 100)}",
            token,
        ) as? JSONObject ?: error("Unexpected GitHub runs response")
        val array = json.optJSONArray("workflow_runs") ?: JSONArray()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            WorkflowRun(
                id = item.optLong("id"),
                name = item.stringOrEmpty("name").ifBlank { item.stringOrEmpty("display_title") },
                status = item.stringOrEmpty("status"),
                conclusion = item.stringOrEmpty("conclusion"),
                headBranch = item.stringOrEmpty("head_branch"),
                event = item.stringOrEmpty("event"),
                createdAt = item.stringOrEmpty("created_at"),
                updatedAt = item.stringOrEmpty("updated_at"),
                htmlUrl = item.stringOrEmpty("html_url"),
                runNumber = item.optLong("run_number"),
            )
        }
    }

    fun dispatch(
        token: String,
        owner: String,
        repo: String,
        workflowId: Long,
        ref: String,
        inputs: Map<String, String> = emptyMap(),
    ) {
        require(ref.isNotBlank()) { "A branch or tag is required to dispatch a workflow" }
        val payload = JSONObject().put("ref", ref)
        if (inputs.isNotEmpty()) {
            payload.put("inputs", JSONObject().apply { inputs.forEach { (key, value) -> put(key, value) } })
        }
        postJson("${repoBase(owner, repo)}/actions/workflows/$workflowId/dispatches", token, payload)
    }

    fun rerun(token: String, owner: String, repo: String, runId: Long) {
        postJson("${repoBase(owner, repo)}/actions/runs/$runId/rerun", token, JSONObject())
    }

    fun cancel(token: String, owner: String, repo: String, runId: Long) {
        postJson("${repoBase(owner, repo)}/actions/runs/$runId/cancel", token, JSONObject())
    }

    fun listJobs(token: String, owner: String, repo: String, runId: Long): List<WorkflowJob> {
        val json = getJson("${repoBase(owner, repo)}/actions/runs/$runId/jobs?per_page=100", token) as? JSONObject
            ?: error("Unexpected GitHub jobs response")
        val array = json.optJSONArray("jobs") ?: JSONArray()
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            val steps = item.optJSONArray("steps") ?: JSONArray()
            WorkflowJob(
                id = item.optLong("id"),
                name = item.stringOrEmpty("name"),
                status = item.stringOrEmpty("status"),
                conclusion = item.stringOrEmpty("conclusion"),
                steps = (0 until steps.length()).mapNotNull { stepIndex ->
                    steps.optJSONObject(stepIndex)?.stringOrEmpty("name")?.takeIf(String::isNotBlank)
                },
            )
        }
    }

    /** Returns the tail of the job log (at most [MAX_LOG_CHARS] characters). */
    fun jobLog(token: String, owner: String, repo: String, jobId: Long): String {
        // GitHub answers 302 to a signed blob URL that must be fetched without the Authorization header.
        val first = open("${repoBase(owner, repo)}/actions/jobs/$jobId/logs", token, "GET").apply {
            instanceFollowRedirects = false
        }
        val target = try {
            val code = first.responseCode
            when {
                code in 300..399 -> first.getHeaderField("Location") ?: error("GitHub did not provide a log location")
                code in 200..299 -> return readTail(first)
                else -> failWith(first, code)
            }
        } finally {
            first.disconnect()
        }
        val second = (URL(target).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 60_000
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            val code = second.responseCode
            if (code !in 200..299) error("Log download failed with HTTP $code")
            readTail(second)
        } finally {
            second.disconnect()
        }
    }

    private fun readTail(connection: HttpURLConnection): String {
        val tail = CharArray(MAX_LOG_CHARS)
        var filled = 0
        var wrapped = false
        connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
            val buffer = CharArray(8 * 1024)
            while (true) {
                val count = reader.read(buffer)
                if (count <= 0) break
                for (i in 0 until count) {
                    tail[filled] = buffer[i]
                    filled++
                    if (filled == MAX_LOG_CHARS) {
                        filled = 0
                        wrapped = true
                    }
                }
            }
        }
        return if (!wrapped) {
            String(tail, 0, filled)
        } else {
            "…\n" + String(tail, filled, MAX_LOG_CHARS - filled) + String(tail, 0, filled)
        }
    }

    private fun repoBase(owner: String, repo: String): String {
        val valid = Regex("^[A-Za-z0-9_.-]+$")
        require(valid.matches(owner) && valid.matches(repo)) { "Invalid GitHub repository name" }
        return "https://api.github.com/repos/${URLEncoder.encode(owner, "UTF-8")}/${URLEncoder.encode(repo, "UTF-8")}"
    }

    private fun open(endpoint: String, token: String, method: String): HttpURLConnection =
        (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", USER_AGENT)
        }

    private fun getJson(endpoint: String, token: String): Any {
        val connection = open(endpoint, token, "GET")
        return try {
            readResponse(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun postJson(endpoint: String, token: String, payload: JSONObject): Any {
        val connection = open(endpoint, token, "POST").apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        return try {
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            readResponse(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun readResponse(connection: HttpURLConnection): Any {
        val status = connection.responseCode
        val stream = if (status in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
            buildString {
                val buffer = CharArray(8 * 1024)
                while (length < MAX_RESPONSE_CHARS) {
                    val count = reader.read(buffer, 0, minOf(buffer.size, MAX_RESPONSE_CHARS - length))
                    if (count <= 0) break
                    append(buffer, 0, count)
                }
            }
        }.orEmpty()
        if (status !in 200..299) failWith(status, text)
        if (text.isBlank()) return JSONObject()
        return if (text.trimStart().startsWith("[")) JSONArray(text) else JSONObject(text)
    }

    private fun failWith(connection: HttpURLConnection, status: Int): Nothing {
        val text = connection.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText().take(4096) }.orEmpty()
        failWith(status, text)
    }

    private fun failWith(status: Int, text: String): Nothing {
        val message = runCatching { JSONObject(text).stringOrEmpty("message") }.getOrNull().orEmpty()
        if (status == 403 && (message.contains("workflow", ignoreCase = true) || message.contains("scope", ignoreCase = true))) {
            throw IllegalStateException("GitHub token needs the 'workflow' scope")
        }
        error(message.takeIf(String::isNotBlank) ?: "GitHub returned HTTP $status")
    }

    private companion object {
        const val USER_AGENT = "NextCraft-Forge-Android"
        const val MAX_RESPONSE_CHARS = 4 * 1024 * 1024
        const val MAX_LOG_CHARS = 256 * 1024
    }
}
