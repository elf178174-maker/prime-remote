package dev.primeremote.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.primeremote.app.AppController
import dev.primeremote.app.ui.theme.Accent
import dev.primeremote.app.ui.theme.Surface1
import dev.primeremote.app.ui.theme.TextDim
import dev.primeremote.core.protocol.HubCommands
import dev.primeremote.core.model.Port
import dev.primeremote.core.model.StopMode

/**
 * A view of everything the hub has printed, plus a way to send raw commands.
 * This is the first place to look when a layout is not doing what you expect.
 */
@Composable
fun ConsoleScreen(controller: AppController, onBack: () -> Unit) {
    val session by controller.session.collectAsState()
    val lines = session?.console?.collectAsState()?.value.orEmpty()
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
    }

    ScreenScaffold(
        title = "Hub console",
        onBack = onBack,
        actions = {
            TextButton(onClick = { session?.clearConsole() }) { Text("Clear") }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp)
        ) {
            if (session == null) {
                Hint("Connect to a hub to see its output.")
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Surface1)
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(lines) { line ->
                    Text(
                        line.text,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (line.fromHub) MaterialTheme.colorScheme.onSurface else Accent,
                    )
                }
            }

            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    label = { Text("Command, e.g. mv A 500") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        session?.sendRaw(input)
                        input = ""
                    }
                ) {
                    Icon(Icons.Filled.Send, contentDescription = "Send")
                }
            }

            Text("Quick commands", style = MaterialTheme.typography.bodySmall, color = TextDim)
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                QuickCommand("Stop all", HubCommands.stopAll(), session != null) { session?.sendRaw(it) }
                QuickCommand("A 30%", HubCommands.run(Port.A, 300), session != null) { session?.sendRaw(it) }
                QuickCommand("A stop", HubCommands.stop(Port.A, StopMode.BRAKE), session != null) { session?.sendRaw(it) }
                QuickCommand("Beep", HubCommands.beep(660, 200, 100), session != null) { session?.sendRaw(it) }
                QuickCommand("Version", HubCommands.version(), session != null) { session?.sendRaw(it) }
            }
            Spacer(Modifier.width(1.dp))
            Hint("Commands are listed in the README under \"the wire protocol\".")
        }
    }
}

@Composable
private fun QuickCommand(label: String, command: String, enabled: Boolean, onSend: (String) -> Unit) {
    OutlinedButton(onClick = { onSend(command) }, enabled = enabled) {
        Text(label, style = MaterialTheme.typography.bodySmall)
    }
}
