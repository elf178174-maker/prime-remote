package dev.primeremote.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.primeremote.app.AppController
import dev.primeremote.app.ble.HubSession
import dev.primeremote.app.input.TiltProvider
import dev.primeremote.app.ui.components.ButtonControl
import dev.primeremote.app.ui.components.ControlHost
import dev.primeremote.app.ui.components.DPadControl
import dev.primeremote.app.ui.components.JoystickControl
import dev.primeremote.app.ui.components.SliderControl
import dev.primeremote.app.ui.components.TiltControl
import dev.primeremote.app.ui.components.ToggleControl
import dev.primeremote.app.ui.theme.Bad
import dev.primeremote.app.ui.theme.Good
import dev.primeremote.app.ui.theme.Surface1
import dev.primeremote.app.ui.theme.Surface2
import dev.primeremote.app.ui.theme.TextDim
import dev.primeremote.app.ui.theme.Warn
import dev.primeremote.core.model.ControlType
import dev.primeremote.core.model.Slot

@Composable
fun ControllerScreen(
    controller: AppController,
    tilt: TiltProvider,
    haptics: Haptics,
    onBack: () -> Unit,
    onEdit: () -> Unit,
    onOpenTelemetry: () -> Unit,
) {
    val profile by controller.activeProfile.collectAsState()
    val pageIndex by controller.pageIndex.collectAsState()
    val phase by controller.phase.collectAsState()
    val telemetry by controller.telemetry.collectAsState()
    val latency by controller.latencyMs.collectAsState()
    val watchdog by controller.watchdogTripped.collectAsState()
    val connected = phase is HubSession.Phase.Ready

    val page = profile.page(pageIndex)
    val toggleStates = remember(profile.id) { mutableStateMapOf<String, Boolean>() }
    val host = remember(controller, haptics) { ControllerHost(controller, haptics, toggleStates) }

    var tiltArmed by remember { mutableStateOf(false) }
    val tiltX by tilt.x.collectAsState()
    val tiltY by tilt.y.collectAsState()
    val tiltControls = page.controls.filter { it.type == ControlType.TILT }

    LaunchedEffect(tiltControls.isNotEmpty()) {
        if (tiltControls.isNotEmpty()) tilt.start() else tilt.stop()
    }

    LaunchedEffect(tiltArmed, tiltX, tiltY) {
        for (control in tiltControls) {
            if (tiltArmed) {
                controller.axis(control.id, Slot.AXIS_X, tiltX)
                controller.axis(control.id, Slot.AXIS_Y, tiltY)
            } else {
                controller.axis(control.id, Slot.AXIS_X, 0f)
                controller.axis(control.id, Slot.AXIS_Y, 0f)
            }
        }
    }

    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {

        // ---- heads-up bar -------------------------------------------------
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(Surface1)
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            IconButton(onClick = onBack, modifier = Modifier.size(38.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }

            Box(
                Modifier
                    .size(9.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(if (connected) Good else Bad)
            )

            Text(
                profile.name,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )

            telemetry.batteryPercent?.let { battery ->
                Text(
                    "$battery%",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (battery < 20) Warn else TextDim,
                )
            }
            latency?.let {
                Text("${it}ms", style = MaterialTheme.typography.labelSmall, color = TextDim)
            }
            if (watchdog) {
                Text("WATCHDOG", style = MaterialTheme.typography.labelSmall, color = Bad)
            }

            Spacer(Modifier.weight(1f))

            IconButton(onClick = onOpenTelemetry, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Filled.Insights, contentDescription = "Telemetry")
            }
            IconButton(onClick = onEdit, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Filled.Edit, contentDescription = "Edit this layout")
            }
            Button(
                onClick = { controller.panicStop() },
                colors = ButtonDefaults.buttonColors(containerColor = Bad),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                modifier = Modifier.height(36.dp),
            ) {
                Text("STOP", fontWeight = FontWeight.Bold)
            }
        }

        if (profile.pages.size > 1) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Surface1)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                profile.pages.forEachIndexed { index, p ->
                    Text(
                        p.name,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (index == pageIndex) MaterialTheme.colorScheme.primary else Surface2)
                            .clickable { controller.setPage(index) }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }

        if (!connected) {
            Text(
                "Not connected — controls will not reach the robot. " +
                    (phase as? HubSession.Phase.Failed)?.reason.orEmpty(),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Bad.copy(alpha = 0.18f))
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = Bad,
            )
        }

        // ---- the layout itself --------------------------------------------
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            val areaWidth = maxWidth
            val areaHeight = maxHeight

            if (page.controls.isEmpty()) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("This page has no controls yet", color = TextDim)
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = onEdit) { Text("Add some") }
                }
            }

            for (control in page.controls) {
                key(control.id) {
                    Box(
                        Modifier
                            .offset(x = areaWidth * control.x, y = areaHeight * control.y)
                            .size(
                                width = areaWidth * control.width,
                                height = areaHeight * control.height,
                            )
                    ) {
                        when (control.type) {
                            ControlType.BUTTON -> ButtonControl(control, host)
                            ControlType.TOGGLE -> ToggleControl(
                                control,
                                host,
                                isOn = toggleStates[control.id] == true,
                            )

                            ControlType.JOYSTICK -> JoystickControl(control, host)
                            ControlType.DPAD -> DPadControl(control, host)
                            ControlType.SLIDER -> SliderControl(control, host)
                            ControlType.TILT -> TiltControl(
                                control,
                                tiltX = tiltX,
                                tiltY = tiltY,
                                armed = tiltArmed,
                                onArmChange = { tiltArmed = it },
                            )
                        }
                    }
                }
            }
        }
    }
}

private class ControllerHost(
    private val controller: AppController,
    private val haptics: Haptics,
    private val toggleStates: MutableMap<String, Boolean>,
) : ControlHost {
    override fun press(controlId: String, slot: Slot) = controller.press(controlId, slot)
    override fun release(controlId: String, slot: Slot) = controller.release(controlId, slot)

    override fun toggle(controlId: String, on: Boolean) {
        toggleStates[controlId] = on
        controller.toggle(controlId, on)
    }
    override fun axis(controlId: String, slot: Slot, value: Float) = controller.axis(controlId, slot, value)
    override fun haptic() = haptics.tap()
}
