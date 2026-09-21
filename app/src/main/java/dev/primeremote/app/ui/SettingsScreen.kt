package dev.primeremote.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.primeremote.app.AppController
import dev.primeremote.core.HubProgram

@Composable
fun SettingsScreen(controller: AppController, onBack: () -> Unit) {
    val hubName by controller.hubName.collectAsState()
    var autoReconnect by remember { mutableStateOf(controller.preferences.autoReconnect) }
    var notice by remember { mutableStateOf<String?>(null) }

    ScreenScaffold(title = "Settings", onBack = onBack) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard("Connection") {
                    SwitchRow("Offer to reconnect to the last hub", autoReconnect) {
                        autoReconnect = it
                        controller.preferences.autoReconnect = it
                    }
                    Hint("Last hub: " + (controller.preferences.lastHubAddress ?: "none yet"))
                    OutlinedButton(
                        onClick = {
                            controller.preferences.lastHubAddress?.let {
                                controller.preferences.forgetProgramVersion(it)
                            }
                            controller.preferences.lastHubAddress = null
                            notice = "Forgotten. The next connection will upload the program again."
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Forget the last hub") }
                }
            }

            item {
                SectionCard("Hub program") {
                    Hint(
                        "Prime-Remote drives your robot by putting a small MicroPython program on " +
                            "the hub and streaming commands to it. It goes in the slot set per layout " +
                            "(19 by default) and replaces whatever is in that slot."
                    )
                    Hint("Program version: ${HubProgram.VERSION}")
                    OutlinedButton(
                        onClick = {
                            controller.preferences.lastHubAddress?.let {
                                controller.preferences.forgetProgramVersion(it)
                            }
                            notice = "The program will be uploaded again on the next connection."
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("Re-upload the program next time") }
                    hubName?.let { name -> Hint("Connected to $name") }
                }
            }

            item {
                SectionCard("About") {
                    Hint(
                        "Prime-Remote talks to SPIKE Prime hubs running SPIKE App 3 firmware over " +
                            "Bluetooth Low Energy, using LEGO's published protocol."
                    )
                    Hint("Layouts live in the app's private storage and can be shared as JSON.")
                }
            }

            notice?.let { message ->
                item { Text(message, modifier = Modifier.padding(top = 4.dp)) }
            }
        }
    }
}
