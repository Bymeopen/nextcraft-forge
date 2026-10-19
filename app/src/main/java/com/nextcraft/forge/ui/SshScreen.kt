@file:OptIn(ExperimentalMaterial3Api::class)

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.nextcraft.forge.R
import com.nextcraft.forge.runtime.PortForward
import com.nextcraft.forge.runtime.SshHost
import com.nextcraft.forge.runtime.SshKeyInfo

@Composable
fun SshScreen(
    state: ForgeUiState,
    viewModel: MainViewModel,
) {
    val tokens = LocalForgeThemeTokens.current
    val ssh = state.ssh
    var editing by remember { mutableStateOf<SshHost?>(null) }
    var showHostDialog by remember { mutableStateOf(false) }
    var showKeyDialog by remember { mutableStateOf(false) }
    val busy = state.terminalRunning || state.toolchainBusy

    if (showHostDialog) {
        HostDialog(
            initial = editing,
            keys = ssh.keys,
            onDismiss = { showHostDialog = false },
            onSave = { host ->
                viewModel.saveSshHost(host)
                showHostDialog = false
            },
        )
    }
    if (showKeyDialog) {
        ImportKeyDialog(
            onDismiss = { showKeyDialog = false },
            onImport = { label, pem ->
                viewModel.importSshKey(label, pem)
                showKeyDialog = false
            },
        )
    }

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
                title = stringResource(R.string.ssh_title),
                subtitle = stringResource(R.string.ssh_subtitle),
                modifier = Modifier.weight(1f),
            )
            ForgeActionPill {
                ForgePillIcon(
                    icon = Icons.Default.Lock,
                    description = stringResource(R.string.ssh_import_key),
                    onClick = { showKeyDialog = true },
                )
                ForgePillIcon(
                    icon = Icons.Default.Add,
                    description = stringResource(R.string.ssh_add_host),
                    onClick = {
                        editing = null
                        showHostDialog = true
                    },
                )
            }
        }
        if (state.toolchainBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        ssh.statusMessage?.let { message ->
            Text(
                text = message,
                fontSize = 13.sp,
                color = tokens.warningText,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
            )
        }
        if (!ssh.clientInstalled) {
            ForgeThemedCard(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.ssh_client_missing), fontSize = 14.sp)
                    ForgePrimaryButton(
                        text = stringResource(R.string.ssh_install_client),
                        onClick = viewModel::installSshClient,
                        enabled = !busy && state.runtimeInstalled,
                    )
                }
            }
        }

        if (ssh.hosts.isEmpty()) {
            Text(
                text = stringResource(R.string.ssh_no_hosts),
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(20.dp),
            )
        }
        ssh.hosts.forEach { host ->
            HostRow(
                host = host,
                forwarding = ssh.forwardingHostId == host.id,
                canConnect = ssh.clientInstalled && !busy,
                onConnect = { viewModel.connectSsh(host.id) },
                onForward = { viewModel.startSshForward(host.id) },
                onStopForward = viewModel::stopSshForward,
                onEdit = {
                    editing = host
                    showHostDialog = true
                },
                onDelete = { viewModel.deleteSshHost(host.id) },
            )
        }

        ForgeSectionLabel(stringResource(R.string.ssh_keys_section))
        ssh.keys.forEach { key ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 50.dp)
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(key.label, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        text = key.id,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { viewModel.deleteSshKey(key.id) }) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = stringResource(R.string.ssh_delete_key),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Divider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(start = 20.dp))
        }
        Spacer(modifier = Modifier.heightIn(min = 24.dp))
    }
}

@Composable
private fun HostRow(
    host: SshHost,
    forwarding: Boolean,
    canConnect: Boolean,
    onConnect: () -> Unit,
    onForward: () -> Unit,
    onStopForward: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val tokens = LocalForgeThemeTokens.current
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(host.label, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        text = stringResource(R.string.ssh_host_detail, host.user, host.host, host.port),
                        fontSize = 13.sp,
                        fontFamily = FontFamily.Monospace,
                        color = muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = if (forwarding) stringResource(R.string.ssh_forwarding) else stringResource(R.string.ssh_forwards_count, host.portForwards.size),
                        fontSize = 12.sp,
                        color = if (forwarding) tokens.successText else muted,
                    )
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = stringResource(R.string.ssh_edit_host), tint = muted, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.ssh_delete_host), tint = muted, modifier = Modifier.size(20.dp))
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = onConnect, enabled = canConnect) { Text(stringResource(R.string.ssh_connect)) }
                if (forwarding) {
                    OutlinedButton(onClick = onStopForward) { Text(stringResource(R.string.ssh_stop_forward)) }
                } else {
                    OutlinedButton(onClick = onForward, enabled = canConnect && host.portForwards.isNotEmpty()) {
                        Text(stringResource(R.string.ssh_forward_only))
                    }
                }
            }
        }
        Divider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(start = 20.dp))
    }
}

private fun parseForwardLine(line: String): PortForward? {
    val parts = line.trim().split(':')
    if (parts.size != 3) return null
    val local = parts[0].toIntOrNull() ?: return null
    val remote = parts[2].toIntOrNull() ?: return null
    if (parts[1].isBlank() || local !in 1..65535 || remote !in 1..65535) return null
    return PortForward(local, parts[1].trim(), remote)
}

@Composable
private fun HostDialog(
    initial: SshHost?,
    keys: List<SshKeyInfo>,
    onDismiss: () -> Unit,
    onSave: (SshHost) -> Unit,
) {
    var label by remember { mutableStateOf(initial?.label.orEmpty()) }
    var hostName by remember { mutableStateOf(initial?.host.orEmpty()) }
    var port by remember { mutableStateOf(initial?.port?.toString() ?: "22") }
    var user by remember { mutableStateOf(initial?.user.orEmpty()) }
    var keyId by remember { mutableStateOf(initial?.keyId) }
    var forwards by remember {
        mutableStateOf(initial?.portForwards.orEmpty().joinToString("\n") { "${it.localPort}:${it.remoteHost}:${it.remotePort}" })
    }
    var keyMenu by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val invalidPort = stringResource(R.string.ssh_invalid_port)
    val invalidForwardTemplate = stringResource(R.string.ssh_invalid_forward, "%s")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.ssh_add_host else R.string.ssh_edit_host)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text(stringResource(R.string.ssh_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = hostName, onValueChange = { hostName = it }, label = { Text(stringResource(R.string.ssh_host)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = user, onValueChange = { user = it }, label = { Text(stringResource(R.string.ssh_user)) }, singleLine = true, modifier = Modifier.weight(2f))
                    OutlinedTextField(value = port, onValueChange = { port = it.filter(Char::isDigit).take(5) }, label = { Text(stringResource(R.string.ssh_port)) }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Box {
                    OutlinedButton(onClick = { keyMenu = true }, modifier = Modifier.fillMaxWidth()) {
                        val selected = keys.firstOrNull { it.id == keyId }
                        Text(selected?.label ?: stringResource(R.string.ssh_no_key))
                    }
                    DropdownMenu(expanded = keyMenu, onDismissRequest = { keyMenu = false }) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.ssh_no_key)) },
                            onClick = {
                                keyId = null
                                keyMenu = false
                            },
                        )
                        keys.forEach { key ->
                            DropdownMenuItem(
                                text = { Text(key.label) },
                                onClick = {
                                    keyId = key.id
                                    keyMenu = false
                                },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = forwards,
                    onValueChange = { forwards = it },
                    label = { Text(stringResource(R.string.ssh_forwards)) },
                    placeholder = { Text(stringResource(R.string.ssh_forwards_hint)) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 96.dp),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp) }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val portValue = port.toIntOrNull()
                    if (portValue == null || portValue !in 1..65535) {
                        error = invalidPort
                        return@Button
                    }
                    val parsed = mutableListOf<PortForward>()
                    for (line in forwards.lines().map(String::trim).filter(String::isNotEmpty)) {
                        val forward = parseForwardLine(line)
                        if (forward == null) {
                            error = invalidForwardTemplate.format(line)
                            return@Button
                        }
                        parsed += forward
                    }
                    val host = SshHost(
                        id = initial?.id ?: java.util.UUID.randomUUID().toString(),
                        label = label.trim().ifBlank { "${user.trim()}@${hostName.trim()}" },
                        host = hostName.trim(),
                        port = portValue,
                        user = user.trim(),
                        keyId = keyId,
                        portForwards = parsed,
                    )
                    onSave(host)
                },
                enabled = hostName.isNotBlank() && user.isNotBlank(),
            ) {
                Text(stringResource(R.string.ssh_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.agent_cancel)) }
        },
    )
}

@Composable
private fun ImportKeyDialog(
    onDismiss: () -> Unit,
    onImport: (label: String, pem: String) -> Unit,
) {
    var label by remember { mutableStateOf("") }
    // Plain remember: the key material must never land in the saved-instance Bundle.
    var pem by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ssh_import_key)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text(stringResource(R.string.ssh_label)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    value = pem,
                    onValueChange = { pem = it },
                    label = { Text(stringResource(R.string.ssh_key_pem)) },
                    textStyle = ForgeCodeTextStyle,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 160.dp),
                )
            }
        },
        confirmButton = {
            Button(onClick = { onImport(label, pem) }, enabled = pem.contains("PRIVATE KEY")) {
                Text(stringResource(R.string.ssh_import))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.agent_cancel)) }
        },
    )
}
