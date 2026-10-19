package com.nextcraft.forge.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextcraft.forge.R
import com.nextcraft.forge.runtime.CheckpointSnapshot
import java.text.DateFormat
import java.util.Date

@Composable
fun CheckpointsScreen(
    state: ForgeUiState,
    viewModel: MainViewModel,
) {
    val tokens = LocalForgeThemeTokens.current
    var tab by rememberSaveable { mutableStateOf(0) }
    var labelInput by rememberSaveable { mutableStateOf("") }
    var restoreTarget by remember { mutableStateOf<CheckpointSnapshot?>(null) }
    val blocked = state.sessionRunning || state.checkpointBusy

    restoreTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { restoreTarget = null },
            title = { Text(stringResource(R.string.ckpt_restore_confirm_title)) },
            text = { Text(stringResource(R.string.ckpt_restore_confirm_message, target.label)) },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.restoreSnapshot(target.id)
                        restoreTarget = null
                    },
                ) {
                    Text(stringResource(R.string.ckpt_restore))
                }
            },
            dismissButton = {
                TextButton(onClick = { restoreTarget = null }) { Text(stringResource(R.string.agent_cancel)) }
            },
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ForgeLargeTitle(
            title = stringResource(R.string.ckpt_title),
            subtitle = stringResource(R.string.ckpt_subtitle),
        )
        ForgePillTabs(
            items = listOf(
                stringResource(R.string.ckpt_tab_pending),
                stringResource(R.string.ckpt_tab_history),
            ),
            selectedIndex = tab,
            onSelect = { tab = it },
        )
        if (state.checkpointBusy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                text = stringResource(R.string.ckpt_busy),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
        } else if (state.sessionRunning) {
            Text(
                text = stringResource(R.string.ckpt_blocked),
                fontSize = 13.sp,
                color = tokens.warningText,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }

        if (tab == 0) {
            WorkspaceDiffReviewList(
                changes = state.changedItems,
                sessionRunning = blocked,
                onOpenFile = { path ->
                    viewModel.openWorkspaceFile(path)
                    viewModel.selectTab(ForgeTab.FILES)
                },
                onRevertFile = viewModel::revertSingleFile,
                onRevertAll = viewModel::revertAllChanges,
                onKeepFile = viewModel::keepSingleFile,
                onKeepAll = viewModel::keepAllChanges,
                modifier = Modifier.weight(1f),
            )
        } else {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = labelInput,
                    onValueChange = { labelInput = it },
                    label = { Text(stringResource(R.string.ckpt_label)) },
                    placeholder = { Text(stringResource(R.string.ckpt_label_hint)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                Button(
                    onClick = {
                        viewModel.createSnapshot(labelInput)
                        labelInput = ""
                    },
                    enabled = !blocked,
                ) {
                    Text(stringResource(R.string.ckpt_create))
                }
            }
            if (state.checkpointHistory.isEmpty()) {
                Text(
                    text = stringResource(R.string.ckpt_empty_history),
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(20.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(state.checkpointHistory, key = { it.id }) { snapshot ->
                        SnapshotRow(
                            snapshot = snapshot,
                            blocked = blocked,
                            onRestore = { restoreTarget = snapshot },
                            onDelete = { viewModel.deleteSnapshot(snapshot.id) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SnapshotRow(
    snapshot: CheckpointSnapshot,
    blocked: Boolean,
    onRestore: () -> Unit,
    onDelete: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val time = remember(snapshot.createdAtMillis) {
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(snapshot.createdAtMillis))
    }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 62.dp)
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = snapshot.label,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val detail = time + " · " + stringResource(R.string.ckpt_files_count, snapshot.fileCount)
                Text(
                    text = detail,
                    fontSize = 13.sp,
                    color = muted,
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(onClick = onRestore, enabled = !blocked) {
                Text(stringResource(R.string.ckpt_restore))
            }
            IconButton(onClick = onDelete, enabled = !blocked) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = stringResource(R.string.ckpt_delete),
                    tint = muted,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Divider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(start = 20.dp))
    }
}
