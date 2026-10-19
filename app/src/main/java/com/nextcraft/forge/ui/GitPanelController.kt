package com.nextcraft.forge.ui

data class GitFileEntry(
    val path: String,
    /** First porcelain column: state in the index. */
    val indexStatus: Char,
    /** Second porcelain column: state in the working tree. */
    val workStatus: Char,
) {
    val staged: Boolean get() = indexStatus != ' ' && indexStatus != '?'
    val untracked: Boolean get() = indexStatus == '?' && workStatus == '?'
    val unstagedChange: Boolean get() = untracked || (workStatus != ' ' && workStatus != '?')
}

data class GitLogEntry(
    val hash: String,
    val author: String,
    val relativeTime: String,
    val subject: String,
)

data class GitUiState(
    val loaded: Boolean = false,
    val isRepo: Boolean = false,
    val branch: String = "",
    val branches: List<String> = emptyList(),
    val entries: List<GitFileEntry> = emptyList(),
    val log: List<GitLogEntry> = emptyList(),
    val hasRemote: Boolean = false,
    val busy: Boolean = false,
    val statusMessage: String? = null,
) {
    val stagedCount: Int get() = entries.count { it.staged }
    val unstagedCount: Int get() = entries.count { it.unstagedChange }
}

/**
 * Builds the shell commands the Git tool runs inside the chroot and parses their output.
 * Every user-supplied value is single-quoted; nothing here touches the filesystem directly.
 */
object GitPanelController {
    private const val BRANCH_MARK = "[forge-git:branch]"
    private const val STATUS_MARK = "[forge-git:status]"
    private const val BRANCHES_MARK = "[forge-git:branches]"
    private const val LOG_MARK = "[forge-git:log]"
    private const val REMOTE_MARK = "[forge-git:remote]"
    private const val LOG_FIELD_SEPARATOR = '\u001f'

    fun quote(raw: String): String = "'" + raw.replace("'", "'\"'\"'") + "'"

    private fun quotePath(path: String): String {
        require(!path.contains('\u0000') && path.isNotBlank()) { "Invalid path" }
        // A leading dash would be read as an option; `--` already guards that, this is belt and braces.
        return quote(path)
    }

    fun overviewCommand(): String = buildString {
        append("if ! git rev-parse --is-inside-work-tree >/dev/null 2>&1; then echo '[forge-git:norepo]'; exit 0; fi; ")
        append("printf '%s\\n' ").append(quote(BRANCH_MARK)).append("; git rev-parse --abbrev-ref HEAD 2>/dev/null; ")
        append("printf '%s\\n' ").append(quote(STATUS_MARK)).append("; git status --porcelain=v1 --untracked-files=all 2>/dev/null; ")
        append("printf '%s\\n' ").append(quote(BRANCHES_MARK)).append("; git branch --format='%(refname:short)' 2>/dev/null; ")
        append("printf '%s\\n' ").append(quote(REMOTE_MARK)).append("; git remote get-url origin 2>/dev/null; ")
        append("printf '%s\\n' ").append(quote(LOG_MARK)).append("; ")
        append("git log -50 --pretty=format:'%h%x1f%an%x1f%ar%x1f%s' 2>/dev/null; echo")
    }

    fun parseOverview(raw: String): GitUiState {
        if (raw.contains("[forge-git:norepo]")) return GitUiState(loaded = true, isRepo = false)
        fun section(mark: String, nextMarks: List<String>): String {
            val start = raw.indexOf(mark)
            if (start < 0) return ""
            val body = raw.substring(start + mark.length)
            val end = nextMarks.mapNotNull { m -> body.indexOf(m).takeIf { it >= 0 } }.minOrNull() ?: body.length
            return body.substring(0, end)
        }
        val branch = section(BRANCH_MARK, listOf(STATUS_MARK)).trim().lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        val entries = section(STATUS_MARK, listOf(BRANCHES_MARK)).lineSequence()
            .filter { it.length >= 4 }
            .mapNotNull { line ->
                val index = line[0]
                val work = line[1]
                var path = line.substring(3)
                // Renames print "old -> new"; the new path is what the user acts on.
                if (index == 'R' || index == 'C') path = path.substringAfter(" -> ", path)
                path = path.trim().removeSurrounding("\"")
                if (path.isEmpty()) null else GitFileEntry(path, index, work)
            }
            .toList()
        val branches = section(BRANCHES_MARK, listOf(REMOTE_MARK)).lineSequence()
            .map(String::trim).filter { it.isNotEmpty() }.toList()
        val remote = section(REMOTE_MARK, listOf(LOG_MARK)).trim()
        val log = section(LOG_MARK, emptyList()).lineSequence()
            .mapNotNull { line ->
                val parts = line.split(LOG_FIELD_SEPARATOR)
                if (parts.size < 4) null else GitLogEntry(parts[0].trim(), parts[1], parts[2], parts.drop(3).joinToString(LOG_FIELD_SEPARATOR.toString()))
            }
            .toList()
        return GitUiState(
            loaded = true,
            isRepo = true,
            branch = branch,
            branches = branches,
            entries = entries,
            log = log,
            hasRemote = remote.isNotBlank(),
        )
    }

    fun initCommand(): String = "git init -b main"

    fun stageCommand(path: String): String = "git add -- ${quotePath(path)}"

    fun stageAllCommand(): String = "git add -A"

    fun unstageCommand(path: String): String =
        "git restore --staged -- ${quotePath(path)} 2>/dev/null || git rm --cached -q -- ${quotePath(path)}"

    fun unstageAllCommand(): String = "git restore --staged -- . 2>/dev/null || git rm --cached -q -r -- . >/dev/null 2>&1"

    fun discardCommand(entry: GitFileEntry): String =
        if (entry.untracked) "rm -rf -- ${quotePath(entry.path)}" else "git checkout -- ${quotePath(entry.path)}"

    fun commitCommand(message: String, userName: String, userEmail: String): String {
        require(message.isNotBlank()) { "Commit message is empty" }
        return "git -c user.name=${quote(userName)} -c user.email=${quote(userEmail)} commit -m ${quote(message)}"
    }

    fun checkoutCommand(branch: String): String {
        validateRef(branch)
        return "git checkout ${quote(branch)}"
    }

    fun createBranchCommand(branch: String): String {
        validateRef(branch)
        return "git checkout -b ${quote(branch)}"
    }

    fun pushCommand(branch: String): String =
        "git push -u origin ${quote(branch.ifBlank { "HEAD" })}"

    fun pullCommand(branch: String): String =
        "git pull --rebase --autostash origin ${quote(branch.ifBlank { "HEAD" })} || { git rebase --abort 2>/dev/null; false; }"

    private fun validateRef(ref: String) {
        require(ref.isNotBlank() && !ref.startsWith('-') && Regex("""[A-Za-z0-9._/\-]+""").matches(ref) && !ref.contains("..")) {
            "Invalid branch name"
        }
    }
}
