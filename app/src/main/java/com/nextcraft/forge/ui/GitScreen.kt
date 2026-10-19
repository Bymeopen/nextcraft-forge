@file:OptIn(ExperimentalMaterial3Api::class)

package com.nextcraft.forge.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextcraft.forge.R

@Composable
fun GitScreen(
    state: ForgeUiState,
    viewModel: MainViewModel,
) {
    val git = state.git
    val tokens = LocalForgeThemeTokens.current
    var tab by rememberSaveable { mutableStateOf(0) }
    var commitMessage by rememberSaveable { mutableStateOf("") }
    var newBranch by rememberSaveable { mutableStateOf("") }
    var pendingDiscard by rememberSaveable { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ForgeLargeTitle(
                title = stringResource(R.string.git_title),
                subtitle = if (git.isRepo && git.branch.isNotBlank()) {
                    stringResource(R.string.git_on_branch, git.branch)
                } else {
                    state.activeProject?.name
                },
                modifier = Modifier.weight(1f),
            )
            ForgeActionPill {
                ForgePillIcon(
                    icon = Icons.Default.Refresh,
                    description = stringResource(R.string.git_refresh),
                    enabled = !git.busy,
                    onClick = viewModel::refreshGit,
                )
            }
        }
        if (git.busy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        }
        git.statusMessage?.let { note ->
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = tokens.accentText,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
        }

        when {
            !git.loaded -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.git_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            !git.isRepo -> GitNotRepo(enabled = !git.busy, onInit = viewModel::gitInit)
            else -> {
                ForgePillTabs(
                    items = listOf(
                        stringResource(R.string.git_tab_changes, git.entries.size),
                        stringResource(R.string.git_tab_branches, git.branches.size),
                        stringResource(R.string.git_tab_history, git.log.size),
                    ),
                    selectedIndex = tab,
                    onSelect = { tab = it },
                )
                when (tab) {
                    0 -> GitChangesPane(
                        git = git,
                        commitMessage = commitMessage,
                        onCommitMessageChange = { commitMessage = it },
                        onCommit = {
                            viewModel.gitCommit(commitMessage)
                            commitMessage = ""
                        },
                        onStage = viewModel::gitStage,
                        onUnstage = viewModel::gitUnstage,
                        onStageAll = viewModel::gitStageAll,
                        onUnstageAll = viewModel::gitUnstageAll,
                        onRequestDiscard = { pendingDiscard = it },
                        onPush = viewModel::gitPush,
                        onPull = viewModel::gitPull,
                        modifier = Modifier.weight(1f),
                    )
                    1 -> GitBranchesPane(
                        git = git,
                        newBranch = newBranch,
                        onNewBranchChange = { newBranch = it },
                        onCreateBranch = {
                            viewModel.gitCreateBranch(newBranch)
                            newBranch = ""
                        },
                        onCheckout = viewModel::gitCheckout,
                        modifier = Modifier.weight(1f),
                    )
                    else -> GitHistoryPane(git = git, modifier = Modifier.weight(1f))
                }
            }
        }
    }

    pendingDiscard?.let { path ->
        val entry = git.entries.firstOrNull { it.path == path }
        AlertDialog(
            onDismissRequest = { pendingDiscard = null },
            title = { Text(stringResource(R.string.git_discard_confirm_title)) },
            text = { Text(stringResource(R.string.git_discard_confirm_message, path)) },
            confirmButton = {
                Button(
                    onClick = {
                        entry?.let(viewModel::gitDiscard)
                        pendingDiscard = null
                    },
                ) {
                    Text(stringResource(R.string.git_discard))
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDiscard = null }) {
                    Text(stringResource(R.string.agent_cancel))
                }
            },
        )
    }
}

@Composable
private fun GitNotRepo(enabled: Boolean, onInit: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = stringResource(R.string.git_not_repo),
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.heightIn(min = 12.dp))
        ForgePrimaryButton(text = stringResource(R.string.git_init), onClick = onInit, enabled = enabled)
    }
}

@Composable
private fun GitChangesPane(
    git: GitUiState,
    commitMessage: String,
    onCommitMessageChange: (String) -> Unit,
    onCommit: () -> Unit,
    onStage: (String) -> Unit,
    onUnstage: (String) -> Unit,
    onStageAll: () -> Unit,
    onUnstageAll: () -> Unit,
    onRequestDiscard: (String) -> Unit,
    onPush: () -> Unit,
    onPull: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val staged = git.entries.filter { it.staged }
    val unstaged = git.entries.filter { it.unstagedChange }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 16.dp),
    ) {
        item(key = "commit") {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                OutlinedTextField(
                    value = commitMessage,
                    onValueChange = onCommitMessageChange,
                    label = { Text(stringResource(R.string.git_commit_message)) },
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = onCommit,
                        enabled = !git.busy && commitMessage.isNotBlank() && staged.isNotEmpty(),
                    ) {
                        Text(stringResource(R.string.git_commit_staged, staged.size))
                    }
                    OutlinedButton(onClick = onPull, enabled = !git.busy && git.hasRemote) {
                        Text(stringResource(R.string.git_pull))
                    }
                    OutlinedButton(onClick = onPush, enabled = !git.busy && git.hasRemote) {
                        Text(stringResource(R.string.git_push))
                    }
                }
                if (!git.hasRemote) {
                    Text(
                        text = stringResource(R.string.git_no_remote),
                        style = MaterialTheme.typography.bodySmall,
                        color = muted,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
        if (git.entries.isEmpty()) {
            item(key = "clean") {
                Text(
                    text = stringResource(R.string.git_no_changes),
                    color = muted,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
        }
        if (staged.isNotEmpty()) {
            item(key = "staged-header") {
                GitSectionHeader(
                    label = stringResource(R.string.git_staged, staged.size),
                    actionLabel = stringResource(R.string.git_unstage_all),
                    enabled = !git.busy,
                    onAction = onUnstageAll,
                )
            }
            items(staged, key = { "s:" + it.path }) { entry ->
                GitFileRow(
                    entry = entry,
                    enabled = !git.busy,
                    primaryIcon = Icons.Default.Close,
                    primaryLabel = stringResource(R.string.git_unstage),
                    onPrimary = { onUnstage(entry.path) },
                    onDiscard = null,
                )
            }
        }
        if (unstaged.isNotEmpty()) {
            item(key = "unstaged-header") {
                GitSectionHeader(
                    label = stringResource(R.string.git_unstaged, unstaged.size),
                    actionLabel = stringResource(R.string.git_stage_all),
                    enabled = !git.busy,
                    onAction = onStageAll,
                )
            }
            items(unstaged, key = { "u:" + it.path }) { entry ->
                GitFileRow(
                    entry = entry,
                    enabled = !git.busy,
                    primaryIcon = Icons.Default.Add,
                    primaryLabel = stringResource(R.string.git_stage),
                    onPrimary = { onStage(entry.path) },
                    onDiscard = { onRequestDiscard(entry.path) },
                )
            }
        }
    }
}

@Composable
private fun GitSectionHeader(label: String, actionLabel: String, enabled: Boolean, onAction: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 8.dp, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onAction, enabled = enabled) { Text(actionLabel, fontSize = 12.sp) }
    }
}

@Composable
private fun GitFileRow(
    entry: GitFileEntry,
    enabled: Boolean,
    primaryIcon: androidx.compose.ui.graphics.vector.ImageVector,
    primaryLabel: String,
    onPrimary: () -> Unit,
    onDiscard: (() -> Unit)?,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val code = if (entry.untracked) "??" else "${entry.indexStatus}${entry.workStatus}".trim().ifBlank { "M" }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(start = 20.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = code,
                fontFamily = FontFamily.Monospace,
                fontSize = 12.sp,
                color = if (entry.untracked) LocalForgeThemeTokens.current.accentText else LocalForgeThemeTokens.current.warningText,
                modifier = Modifier.width(28.dp),
            )
            Text(
                text = entry.path,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (onDiscard != null) {
                IconButton(onClick = onDiscard, enabled = enabled) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.git_discard), tint = muted, modifier = Modifier.size(18.dp))
                }
            }
            IconButton(onClick = onPrimary, enabled = enabled) {
                Icon(primaryIcon, contentDescription = primaryLabel, modifier = Modifier.size(18.dp))
            }
        }
        Divider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp, modifier = Modifier.padding(start = 20.dp))
    }
}

@Composable
private fun GitBranchesPane(
    git: GitUiState,
    newBranch: String,
    onNewBranchChange: (String) -> Unit,
    onCreateBranch: () -> Unit,
    onCheckout: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        item(key = "new") {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = newBranch,
                    onValueChange = onNewBranchChange,
                    label = { Text(stringResource(R.string.git_branch_name)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(onClick = onCreateBranch, enabled = !git.busy && newBranch.isNotBlank()) {
                    Text(stringResource(R.string.git_create_branch))
                }
            }
        }
        items(git.branches, key = { it }) { branch ->
            val current = branch == git.branch
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clickable(enabled = !git.busy && !current, role = Role.Button) { onCheckout(branch) }
                    .padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = branch,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    fontWeight = if (current) FontWeight.Bold else FontWeight.Normal,
                    modifier = Modifier.weight(1f),
                )
                if (current) {
                    Icon(Icons.Default.Check, contentDescription = stringResource(R.string.git_current_branch), modifier = Modifier.size(18.dp))
                } else {
                    FilterChip(
                        selected = false,
                        enabled = !git.busy,
                        onClick = { onCheckout(branch) },
                        label = { Text(stringResource(R.string.git_checkout)) },
                    )
                }
            }
            Divider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp, modifier = Modifier.padding(start = 20.dp))
        }
    }
}

@Composable
private fun GitHistoryPane(git: GitUiState, modifier: Modifier = Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    if (git.log.isEmpty()) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.git_no_commits), color = muted)
        }
        return
    }
    LazyColumn(modifier = modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 16.dp)) {
        items(git.log, key = { it.hash }) { entry ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
            ) {
                Text(
                    text = entry.subject,
                    fontSize = 14.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = listOf(entry.hash, entry.author, entry.relativeTime).joinToString(" · ")
                Text(
                    text = meta,
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Divider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 0.5.dp, modifier = Modifier.padding(start = 20.dp))
        }
    }
}
