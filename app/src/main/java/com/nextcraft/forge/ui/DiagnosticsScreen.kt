package com.nextcraft.forge.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextcraft.forge.R
import com.nextcraft.forge.runtime.ChrootProcess

@Composable
fun DiagnosticsScreen(
    state: ForgeUiState,
    viewModel: MainViewModel,
) {
    val tokens = LocalForgeThemeTokens.current
    val snap = state.diagnostics
    var killTarget by remember { mutableStateOf<ChrootProcess?>(null) }

    killTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { killTarget = null },
            title = { Text(stringResource(R.string.diag_kill_confirm_title)) },
            text = { Text(stringResource(R.string.diag_kill_confirm_message, target.name, target.pid)) },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = {
                            viewModel.killChrootProcess(target.pid, force = true)
                            killTarget = null
                        },
                    ) {
                        Text(stringResource(R.string.diag_force_kill))
                    }
                    Button(
                        onClick = {
                            viewModel.killChrootProcess(target.pid, force = false)
                            killTarget = null
                        },
                    ) {
                        Text(stringResource(R.string.diag_kill))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { killTarget = null }) { Text(stringResource(R.string.agent_cancel)) }
            },
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ForgeLargeTitle(
            title = stringResource(R.string.diag_title),
            subtitle = stringResource(R.string.diag_subtitle),
        )
        if (snap == null) {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            Text(
                text = stringResource(R.string.diag_sampling),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
            return
        }

        Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard(
                    title = stringResource(R.string.diag_cpu),
                    value = stringResource(R.string.diag_percent, snap.cpuPercent.toInt().coerceIn(0, 100)),
                    detail = null,
                    fraction = (snap.cpuPercent / 100f).coerceIn(0f, 1f),
                    modifier = Modifier.weight(1f),
                )
                StatCard(
                    title = stringResource(R.string.diag_ram),
                    value = stringResource(R.string.diag_mb_of_mb, snap.memUsedMb, snap.memTotalMb),
                    detail = null,
                    fraction = if (snap.memTotalMb > 0) (snap.memUsedMb.toFloat() / snap.memTotalMb).coerceIn(0f, 1f) else 0f,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatCard(
                    title = stringResource(R.string.diag_battery),
                    value = if (snap.batteryPercent >= 0) {
                        stringResource(R.string.diag_battery_detail, snap.batteryPercent, snap.batteryTempC)
                    } else {
                        stringResource(R.string.diag_unknown)
                    },
                    detail = stringResource(if (snap.charging) R.string.diag_charging else R.string.diag_on_battery),
                    fraction = (snap.batteryPercent.coerceAtLeast(0) / 100f).coerceIn(0f, 1f),
                    modifier = Modifier.weight(1f),
                    barColor = if (snap.batteryPercent in 0..15) tokens.dangerText else tokens.successText,
                )
                StatCard(
                    title = stringResource(R.string.diag_storage),
                    value = stringResource(R.string.diag_mb_free, snap.storageFreeMb, snap.storageTotalMb),
                    detail = null,
                    fraction = if (snap.storageTotalMb > 0) {
                        ((snap.storageTotalMb - snap.storageFreeMb).toFloat() / snap.storageTotalMb).coerceIn(0f, 1f)
                    } else {
                        0f
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }

        ForgeSectionLabel(stringResource(R.string.diag_processes))
        if (snap.chrootProcesses.isEmpty()) {
            Text(
                text = stringResource(R.string.diag_no_processes),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(snap.chrootProcesses, key = { it.pid }) { process ->
                    Column(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .padding(horizontal = 20.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = process.name.ifBlank { process.cmdline },
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    fontFamily = FontFamily.Monospace,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = stringResource(R.string.diag_process_detail, process.pid, process.rssKb / 1024L),
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (process.cmdline.isNotBlank() && process.cmdline != process.name) {
                                    Text(
                                        text = process.cmdline,
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            OutlinedButton(onClick = { killTarget = process }) {
                                Text(stringResource(R.string.diag_kill))
                            }
                        }
                        Divider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(start = 20.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatCard(
    title: String,
    value: String,
    detail: String?,
    fraction: Float,
    modifier: Modifier = Modifier,
    barColor: Color = MaterialTheme.colorScheme.primary,
) {
    ForgeThemedCard(modifier = modifier) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = title,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = FontFamily.Monospace,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            detail?.let {
                Text(text = it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            LinearProgressIndicator(
                progress = fraction,
                color = barColor,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
