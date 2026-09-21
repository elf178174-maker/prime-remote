package dev.primeremote.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.primeremote.app.AppController
import dev.primeremote.app.ble.HubSession
import dev.primeremote.app.ui.theme.Good
import dev.primeremote.app.ui.theme.TextDim
import dev.primeremote.app.ui.theme.Warn
import dev.primeremote.core.model.Port
import dev.primeremote.core.protocol.MotorType

@Composable
fun TelemetryScreen(controller: AppController, onBack: () -> Unit) {
    val session by controller.session.collectAsState()
    val telemetry = session?.telemetry?.collectAsState()?.value
    val info = session?.hubInfo?.collectAsState()?.value
    val latency = session?.latencyMs?.collectAsState()?.value
    val inputMode = session?.hubInputMode?.collectAsState()?.value
    val phase = session?.phase?.collectAsState()?.value
    val commandRate by controller.commandRateHz.collectAsState()

    ScreenScaffold(title = "Telemetry", onBack = onBack) { padding ->
        LazyColumn(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                SectionCard("Connection") {
                    KeyValue("State", phase?.let { describePhase(it) } ?: "Not connected")
                    KeyValue("Hub", session?.deviceName ?: "—")
                    KeyValue("Firmware", info?.firmwareVersion ?: "—")
                    KeyValue("Protocol", info?.rpcVersion ?: "—")
                    KeyValue("Max packet / chunk", info?.let { "${it.maxPacketSize} B / ${it.maxChunkSize} B" } ?: "—")
                    KeyValue("Round trip", latency?.let { "$it ms" } ?: "—")
                    KeyValue("Commands sent", "$commandRate per second")
                    KeyValue("Dropped packets", (session?.packetsDropped ?: 0).toString())
                    KeyValue(
                        "Hub input mode",
                        when (inputMode) {
                            "poll" -> "poll (safety watchdog active)"
                            "block" -> "blocking (watchdog cannot fire)"
                            else -> "—"
                        },
                    )
                }
            }

            item {
                SectionCard("Battery") {
                    val battery = telemetry?.batteryPercent
                    if (battery == null) {
                        Hint("No reading yet.")
                    } else {
                        Text("$battery%", style = MaterialTheme.typography.titleLarge)
                        LinearProgressIndicator(
                            progress = { battery / 100f },
                            modifier = Modifier.fillMaxWidth(),
                            color = if (battery < 20) Warn else Good,
                        )
                    }
                }
            }

            item {
                SectionCard("Motors") {
                    val motors = telemetry?.motors.orEmpty()
                    if (motors.isEmpty()) {
                        Hint("No motors reported. Plug one in and the hub will announce it.")
                    }
                    for (port in Port.entries) {
                        val motor = motors[port] ?: continue
                        KeyValue(
                            "Port ${port.letter} · ${MotorType.name(motor.deviceType)}",
                            "speed ${motor.speed}%  ·  ${motor.position}°  ·  abs ${motor.absolutePosition}°",
                        )
                    }
                }
            }

            item {
                SectionCard("Sensors") {
                    val forces = telemetry?.forces.orEmpty()
                    val distances = telemetry?.distances.orEmpty()
                    val colors = telemetry?.colors.orEmpty()
                    if (forces.isEmpty() && distances.isEmpty() && colors.isEmpty()) {
                        Hint("No sensors reported.")
                    }
                    for ((port, force) in forces) {
                        KeyValue("Force ${port.letter}", "${force.value} dN${if (force.pressed) " · pressed" else ""}")
                    }
                    for ((port, distance) in distances) {
                        KeyValue(
                            "Distance ${port.letter}",
                            if (distance.distance < 0) "nothing in range" else "${distance.distance} mm",
                        )
                    }
                    for ((port, color) in colors) {
                        KeyValue(
                            "Colour ${port.letter}",
                            "id ${color.color} · r${color.red} g${color.green} b${color.blue}",
                        )
                    }
                }
            }

            item {
                SectionCard("Motion") {
                    val imu = telemetry?.imu
                    if (imu == null) {
                        Hint("No motion data yet.")
                    } else {
                        KeyValue("Yaw / pitch / roll", "${imu.yaw / 10}° / ${imu.pitch / 10}° / ${imu.roll / 10}°")
                        KeyValue("Acceleration", "${imu.accelX}, ${imu.accelY}, ${imu.accelZ}")
                        KeyValue("Angular rate", "${imu.gyroX}, ${imu.gyroY}, ${imu.gyroZ}")
                        KeyValue("Face up", imu.upFace.toString())
                    }
                }
            }
        }
    }
}

@Composable
private fun KeyValue(key: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(key, style = MaterialTheme.typography.bodySmall, color = TextDim)
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}

private fun describePhase(phase: HubSession.Phase): String = when (phase) {
    HubSession.Phase.Idle -> "Idle"
    HubSession.Phase.Connecting -> "Connecting"
    HubSession.Phase.Handshaking -> "Handshaking"
    is HubSession.Phase.Uploading -> "Uploading ${phase.sentBytes}/${phase.totalBytes} B"
    HubSession.Phase.StartingProgram -> "Starting the program"
    HubSession.Phase.Ready -> "Ready"
    is HubSession.Phase.Failed -> "Failed: ${phase.reason}"
}
