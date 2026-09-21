package dev.primeremote.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import dev.primeremote.app.AppController
import dev.primeremote.app.ble.HubSession
import dev.primeremote.app.ui.theme.Accent
import dev.primeremote.app.ui.theme.Bad
import dev.primeremote.app.ui.theme.Good
import dev.primeremote.app.ui.theme.Surface1
import dev.primeremote.app.ui.theme.Surface2
import dev.primeremote.app.ui.theme.TextDim
import dev.primeremote.app.ui.theme.Warn
import dev.primeremote.core.model.Presets
import dev.primeremote.core.model.Profile

@Composable
fun HomeScreen(
    controller: AppController,
    permissionsGranted: Boolean,
    onRequestPermissions: () -> Unit,
    onOpenController: () -> Unit,
    onEditProfile: (Profile) -> Unit,
    onOpenTelemetry: () -> Unit,
    onOpenConsole: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val hubs by controller.scanner.hubs.collectAsState()
    val scanning by controller.scanner.scanning.collectAsState()
    val scanError by controller.scanner.error.collectAsState()
    val session by controller.session.collectAsState()
    val status by controller.status.collectAsState()
    val connecting by controller.connecting.collectAsState()
    val profiles by controller.repository.profiles.collectAsState()
    val activeProfile by controller.activeProfile.collectAsState()
    val phase = session?.phase?.collectAsState()?.value

    var showPresets by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<Profile?>(null) }
    var importError by remember { mutableStateOf<String?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                val text = context.contentResolver.openInputStream(uri)?.use {
                    it.readBytes().decodeToString()
                } ?: ""
                val imported = controller.repository.import(text)
                importError = if (imported.isEmpty()) "That file held no layouts" else null
            } catch (e: Exception) {
                importError = "Could not read that file: ${e.message}"
            }
        }
    }

    DisposableEffect(Unit) {
        onDispose { controller.scanner.stop() }
    }

    ScreenScaffold(
        title = "Prime-Remote",
        actions = {
            IconButton(onClick = onOpenTelemetry) {
                Icon(Icons.Filled.Insights, contentDescription = "Telemetry")
            }
            IconButton(onClick = onOpenConsole) {
                Icon(Icons.Filled.Terminal, contentDescription = "Hub console")
            }
            IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = "Settings")
            }
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Spacer(Modifier.height(2.dp)) }

            item {
                SectionCard("Robot") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val dotColor = when {
                            phase is HubSession.Phase.Ready -> Good
                            connecting || phase is HubSession.Phase.Uploading -> Warn
                            phase is HubSession.Phase.Failed -> Bad
                            else -> TextDim
                        }
                        Box(
                            Modifier
                                .size(10.dp)
                                .clip(RoundedCornerShape(5.dp))
                                .background(dotColor)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(status, style = MaterialTheme.typography.bodyMedium)
                    }

                    when (val p = phase) {
                        is HubSession.Phase.Uploading -> {
                            val fraction = if (p.totalBytes == 0) 0f else p.sentBytes.toFloat() / p.totalBytes
                            Text("Sending the receiver program to the hub…", color = TextDim)
                            LinearProgressIndicator(
                                progress = { fraction },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }

                        is HubSession.Phase.Failed -> Text(p.reason, color = Bad)
                        else -> Unit
                    }

                    if (!permissionsGranted) {
                        Hint("Prime-Remote needs Bluetooth permission to find your hub.")
                        Button(onClick = onRequestPermissions) { Text("Grant Bluetooth permission") }
                    } else {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (session == null) {
                                Button(
                                    onClick = {
                                        if (scanning) controller.scanner.stop() else controller.scanner.start()
                                    }
                                ) {
                                    Icon(Icons.Filled.Bluetooth, contentDescription = null)
                                    Spacer(Modifier.width(6.dp))
                                    Text(if (scanning) "Stop scanning" else "Scan for hubs")
                                }
                                if (controller.preferences.lastHubAddress != null) {
                                    OutlinedButton(onClick = { controller.reconnectLast() }) {
                                        Text("Reconnect last")
                                    }
                                }
                            } else {
                                OutlinedButton(onClick = { controller.disconnect() }) { Text("Disconnect") }
                                if (phase is HubSession.Phase.Ready) {
                                    Button(onClick = onOpenController) {
                                        Icon(Icons.Filled.PlayArrow, contentDescription = null)
                                        Spacer(Modifier.width(6.dp))
                                        Text("Drive")
                                    }
                                }
                            }
                            if (connecting) {
                                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
                            }
                        }
                    }

                    scanError?.let { Text(it, color = Bad, style = MaterialTheme.typography.bodySmall) }

                    if (session == null && permissionsGranted) {
                        if (hubs.isEmpty()) {
                            Hint(
                                if (scanning) {
                                    "Looking for hubs. Switch the hub on and press its Bluetooth button until it blinks."
                                } else {
                                    "No hubs found yet."
                                }
                            )
                        }
                        for (hub in hubs) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { controller.connect(hub.device) },
                                colors = CardDefaults.cardColors(containerColor = Surface2),
                            ) {
                                Row(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(Icons.Filled.Bluetooth, contentDescription = null, tint = Accent)
                                    Spacer(Modifier.width(10.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(hub.name, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            hub.address,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = TextDim,
                                        )
                                    }
                                    Text(signalLabel(hub.rssi), color = TextDim)
                                }
                            }
                        }
                    }
                }
            }

            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Layouts", style = MaterialTheme.typography.titleMedium)
                    Row {
                        IconButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/*", "*/*")) }) {
                            Icon(Icons.Filled.FileDownload, contentDescription = "Import a layout")
                        }
                        IconButton(onClick = { showPresets = true }) {
                            Icon(Icons.Filled.Add, contentDescription = "New layout")
                        }
                    }
                }
            }

            importError?.let { message ->
                item { Text(message, color = Bad, style = MaterialTheme.typography.bodySmall) }
            }

            items(profiles, key = { it.id }) { profile ->
                val selected = profile.id == activeProfile.id
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            controller.selectProfile(profile)
                            if (phase is HubSession.Phase.Ready) onOpenController()
                        },
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) Surface2 else Surface1,
                    ),
                ) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 14.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                profile.name,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            )
                            val controls = profile.pages.sumOf { it.controls.size }
                            val actions = profile.pages.sumOf { page ->
                                page.controls.sumOf { it.boundActionCount }
                            }
                            Text(
                                "$controls controls · $actions actions · ${profile.pages.size} page(s)",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextDim,
                            )
                        }
                        IconButton(onClick = { onEditProfile(profile) }) {
                            Icon(Icons.Filled.Edit, contentDescription = "Edit")
                        }
                        IconButton(onClick = { controller.repository.duplicate(profile.id) }) {
                            Icon(Icons.Filled.ContentCopy, contentDescription = "Duplicate")
                        }
                        IconButton(
                            onClick = {
                                shareText(
                                    context,
                                    "${profile.name}.json",
                                    controller.repository.export(profile.id),
                                )
                            }
                        ) {
                            Icon(Icons.Filled.FileUpload, contentDescription = "Share")
                        }
                        IconButton(onClick = { deleteTarget = profile }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = Bad)
                        }
                    }
                }
            }

            item {
                OutlinedButton(
                    onClick = onOpenController,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Open the controller" + if (phase is HubSession.Phase.Ready) "" else " (not connected)")
                }
            }

            item { Spacer(Modifier.height(20.dp)) }
        }
    }

    if (showPresets) {
        AlertDialog(
            onDismissRequest = { showPresets = false },
            title = { Text("Start from") },
            text = {
                Column {
                    Presets.names.forEachIndexed { index, name ->
                        Text(
                            name,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val created = controller.repository.add(Presets.all[index]())
                                    controller.selectProfile(created)
                                    showPresets = false
                                    onEditProfile(created)
                                }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPresets = false }) { Text("Cancel") }
            },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete \"${target.name}\"?") },
            text = { Text("This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        controller.repository.delete(target.id)
                        if (controller.activeProfile.value.id == target.id) {
                            controller.repository.profiles.value.firstOrNull()
                                ?.let { controller.selectProfile(it) }
                        }
                        deleteTarget = null
                    }
                ) { Text("Delete", color = Bad) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text("Keep") }
            },
        )
    }
}

private fun signalLabel(rssi: Int): String = when {
    rssi == Int.MIN_VALUE -> "paired"
    rssi > -60 -> "strong"
    rssi > -75 -> "ok"
    else -> "weak"
}
