package com.nextcraft.forge.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Divider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextcraft.forge.R
import com.nextcraft.forge.network.WorkflowRun
import java.time.Instant

@Composable
fun CicdScreen(
    state: ForgeUiState,
    viewModel: MainViewModel,
) {
    val tokens = LocalForgeThemeTokens.current
    val cicd = state.cicd
    var showDispatch by remember { mutableStateOf(false) }

    if (showDispatch) {
        AlertDialog(
            onDismissRequest = { showDispatch = false },
            title = { Text(stringResource(R.string.cicd_choose_workflow, cicd.branch.ifBlank { "main" })) },
            text = {
                if (cicd.busy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else if (cicd.workflows.isEmpty()) {
                    Text(stringResource(R.string.cicd_no_workflows))
                } else {
                    Column(
                        modifier = Modifier.verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        cicd.workflows.forEach { workflow ->
                            TextButton(
                                onClick = {
                                    viewModel.dispatchWorkflow(workflow.id)
                                    showDispatch = false
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    Text(workflow.name, fontWeight = FontWeight.Medium)
                                    Text(
                                        text = workflow.path,
                                        fontSize = 12.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDispatch = false }) { Text(stringResource(R.string.agent_cancel)) }
            },
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ForgeLargeTitle(
                title = stringResource(R.string.cicd_title),
                subtitle = cicd.repositoryName?.let { repo ->
                    if (cicd.branch.isBlank()) repo else "$repo @ ${cicd.branch}"
                } ?: stringResource(R.string.cicd_subtitle),
                modifier = Modifier.weight(1f),
            )
            ForgeActionPill {
                ForgePillIcon(
                    icon = Icons.Default.PlayArrow,
                    description = stringResource(R.string.cicd_dispatch),
                    onClick = {
                        showDispatch = true
                        viewModel.loadWorkflows()
                    },
                    enabled = !cicd.busy && cicd.repositoryName != null,
                )
                ForgePillIcon(
                    icon = Icons.Default.Refresh,
                    description = stringResource(R.string.cicd_refresh),
                    onClick = viewModel::refreshCicd,
                    enabled = !cicd.busy,
                )
            }
        }
        if (cicd.busy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        cicd.statusMessage?.let { message ->
            Text(
                text = message,
                fontSize = 13.sp,
                color = tokens.warningText,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }

        when {
            !cicd.resolved -> Text(
                text = stringResource(R.string.cicd_not_resolved),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
            cicd.selectedRunId != null -> JobsPanel(state = state, viewModel = viewModel, modifier = Modifier.weight(1f))
            cicd.runs.isEmpty() -> Text(
                text = stringResource(R.string.cicd_no_runs),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
            else -> LazyColumn(modifier = Modifier.weight(1f)) {
                items(cicd.runs, key = { it.id }) { run ->
                    RunRow(
                        run = run,
                        busy = cicd.busy,
                        onRerun = { viewModel.rerunWorkflowRun(run.id) },
                        onCancel = { viewModel.cancelWorkflowRun(run.id) },
                        onJobs = { viewModel.loadWorkflowJobs(run.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun RunRow(
    run: WorkflowRun,
    busy: Boolean,
    onRerun: () -> Unit,
    onCancel: () -> Unit,
    onJobs: () -> Unit,
) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val inProgress = run.status == "in_progress" || run.status == "queued" || run.status == "waiting"
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 62.dp)
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusChip(status = run.status, conclusion = run.conclusion)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = run.name,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            }
            val meta = stringResource(R.string.cicd_run_number, run.runNumber) +
                " · " + run.headBranch + " · " + relativeTime(run.createdAt)
            Text(text = meta, fontSize = 12.sp, color = muted, fontFamily = FontFamily.Monospace)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OutlinedButton(onClick = onJobs, enabled = !busy) { Text(stringResource(R.string.cicd_jobs)) }
                if (inProgress) {
                    OutlinedButton(onClick = onCancel, enabled = !busy) { Text(stringResource(R.string.cicd_cancel_run)) }
                } else {
                    OutlinedButton(onClick = onRerun, enabled = !busy) { Text(stringResource(R.string.cicd_rerun)) }
                }
            }
        }
        Divider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(start = 20.dp))
    }
}

@Composable
private fun JobsPanel(
    state: ForgeUiState,
    viewModel: MainViewModel,
    modifier: Modifier = Modifier,
) {
    val tokens = LocalForgeThemeTokens.current
    val cicd = state.cicd
    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.cicd_jobs),
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = viewModel::closeWorkflowJobs) { Text(stringResource(R.string.cicd_close_jobs)) }
        }
        cicd.jobs.forEach { job ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusChip(status = job.status, conclusion = job.conclusion)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = job.name,
                    fontSize = 14.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { viewModel.loadJobLog(job.id) }, enabled = !cicd.busy) {
                    Text(stringResource(R.string.cicd_view_log))
                }
            }
        }
        Divider(color = MaterialTheme.colorScheme.outlineVariant)
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(12.dp)
                .clip(RoundedCornerShape(tokens.cardCornerRadius))
                .background(tokens.terminalBackground)
                .verticalScroll(rememberScrollState())
                .padding(10.dp),
        ) {
            Text(
                text = cicd.jobLog?.ifBlank { null } ?: stringResource(R.string.cicd_log_empty),
                style = ForgeCodeTextStyle,
                color = tokens.terminalText,
            )
        }
    }
}

@Composable
private fun StatusChip(status: String, conclusion: String) {
    val tokens = LocalForgeThemeTokens.current
    val effective = if (status == "completed") conclusion else status
    val (label, color) = when (effective) {
        "queued", "waiting", "pending" -> stringResource(R.string.cicd_status_queued) to tokens.warningText
        "in_progress" -> stringResource(R.string.cicd_status_in_progress) to tokens.accentText
        "success" -> stringResource(R.string.cicd_status_success) to tokens.successText
        "failure", "timed_out", "startup_failure" -> stringResource(R.string.cicd_status_failure) to tokens.dangerText
        "cancelled" -> stringResource(R.string.cicd_status_cancelled) to MaterialTheme.colorScheme.onSurfaceVariant
        "skipped", "neutral" -> stringResource(R.string.cicd_status_skipped) to MaterialTheme.colorScheme.onSurfaceVariant
        else -> stringResource(R.string.cicd_status_other, effective.ifBlank { status }) to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(100))
            .background(color.copy(alpha = 0.16f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text = label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = color, maxLines = 1)
    }
}

@Composable
private fun relativeTime(iso: String): String {
    val millis = remember(iso) { runCatching { Instant.parse(iso).toEpochMilli() }.getOrNull() }
        ?: return iso
    val diffMinutes = ((System.currentTimeMillis() - millis) / 60_000L).coerceAtLeast(0L)
    return when {
        diffMinutes < 1L -> stringResource(R.string.cicd_time_just_now)
        diffMinutes < 60L -> stringResource(R.string.cicd_time_minutes, diffMinutes)
        diffMinutes < 24L * 60L -> stringResource(R.string.cicd_time_hours, diffMinutes / 60L)
        else -> stringResource(R.string.cicd_time_days, diffMinutes / (24L * 60L))
    }
}
