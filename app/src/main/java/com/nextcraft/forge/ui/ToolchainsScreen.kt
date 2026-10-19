package com.nextcraft.forge.ui

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextcraft.forge.R
import com.nextcraft.forge.runtime.ToolchainEntry

@Composable
fun ToolchainsScreen(
    state: ForgeUiState,
    viewModel: MainViewModel,
) {
    val tokens = LocalForgeThemeTokens.current
    val actionsBlocked = state.toolchainBusy || state.sessionRunning || !state.runtimeInstalled
    val stacks = state.toolchains.filter { it.stack != null }
    val agents = state.toolchains.filter { it.agent != null }
    val core = state.toolchains.filter { it.stack == null && it.agent == null }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ForgeLargeTitle(
                title = stringResource(R.string.toolchains_title),
                subtitle = stringResource(R.string.toolchains_subtitle),
                modifier = Modifier.weight(1f),
            )
            ForgeActionPill {
                ForgePillIcon(
                    icon = Icons.Default.Refresh,
                    description = stringResource(R.string.toolchains_refresh),
                    onClick = viewModel::refreshToolchains,
                    enabled = !state.toolchainBusy,
                )
            }
        }

        if (state.toolchainBusy) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                text = stringResource(R.string.toolchains_working),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }
        if (!state.runtimeInstalled) {
            Text(
                text = stringResource(R.string.toolchains_runtime_missing),
                fontSize = 14.sp,
                color = tokens.warningText,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
        }
        if (state.toolchains.isEmpty()) {
            Text(
                text = stringResource(R.string.toolchains_empty),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
        }

        if (stacks.isNotEmpty()) {
            ForgeSectionLabel(stringResource(R.string.toolchains_section_stacks))
            stacks.forEach { entry ->
                ToolchainRow(entry = entry, state = state, blocked = actionsBlocked, viewModel = viewModel)
            }
        }
        if (agents.isNotEmpty()) {
            ForgeSectionLabel(stringResource(R.string.toolchains_section_agents))
            agents.forEach { entry ->
                ToolchainRow(entry = entry, state = state, blocked = actionsBlocked, viewModel = viewModel)
            }
        }
        if (core.isNotEmpty()) {
            ForgeSectionLabel(stringResource(R.string.toolchains_section_tools))
            core.forEach { entry ->
                ToolchainRow(entry = entry, state = state, blocked = actionsBlocked, viewModel = viewModel)
            }
        }
        Spacer(modifier = Modifier.heightIn(min = 24.dp))
    }
}

@Composable
private fun ToolchainRow(
    entry: ToolchainEntry,
    state: ForgeUiState,
    blocked: Boolean,
    viewModel: MainViewModel,
) {
    val tokens = LocalForgeThemeTokens.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val update = entry.agent?.let { state.toolchainUpdates[it] }
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
                    text = entry.label,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = entry.installedVersion ?: stringResource(R.string.toolchains_not_installed),
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    color = if (entry.installed) tokens.successText else muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(modifier = Modifier.width(10.dp))
            Box {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    entry.stack?.let { stack ->
                        if (!entry.installed) {
                            Button(onClick = { viewModel.installStack(stack) }, enabled = !blocked) {
                                Text(stringResource(R.string.toolchains_install))
                            }
                        } else if (entry.removable) {
                            OutlinedButton(onClick = { viewModel.removeStack(stack) }, enabled = !blocked) {
                                Text(stringResource(R.string.toolchains_remove))
                            }
                        }
                    }
                    entry.agent?.let { agent ->
                        if (update != null) {
                            Button(onClick = { viewModel.updateAgent(agent) }, enabled = !blocked) {
                                Text(stringResource(R.string.toolchains_update_to, update.latestVersion))
                            }
                        } else if (entry.installed) {
                            Text(
                                text = stringResource(R.string.toolchains_up_to_date),
                                fontSize = 12.sp,
                                color = muted,
                            )
                        }
                    }
                }
            }
        }
        Divider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(start = 20.dp))
    }
}
