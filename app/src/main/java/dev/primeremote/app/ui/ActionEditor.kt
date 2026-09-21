package dev.primeremote.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import dev.primeremote.app.ui.theme.Bad
import dev.primeremote.app.ui.theme.TextDim
import dev.primeremote.core.model.Action
import dev.primeremote.core.model.Direction
import dev.primeremote.core.model.DriveMode
import dev.primeremote.core.model.HubColor
import dev.primeremote.core.model.MacroStep
import dev.primeremote.core.model.Port
import dev.primeremote.core.model.StopMode

/** Every action the editor can create, with a friendly name and a starting value. */
data class ActionKind(val label: String, val hint: String, val create: () -> Action)

val actionKinds: List<ActionKind> = listOf(
    ActionKind("Run motor", "Runs while the control is held") { Action.MotorRun(Port.A, 75) },
    ActionKind("Motor follows axis", "For joysticks and sliders") { Action.AxisMotor(Port.A, 100) },
    ActionKind("Run motor for a time", "Starts it and lets the hub time it") { Action.MotorRunForTime(Port.C, 75, 10_000) },
    ActionKind("Turn motor by degrees", "Exact amount of rotation") { Action.MotorRunForDegrees(Port.C, 60, 360) },
    ActionKind("Move motor to a position", "Absolute angle") { Action.MotorToPosition(Port.C, 0, 50) },
    ActionKind("Stop motor", "One motor, chosen stop mode") { Action.MotorStop(Port.A) },
    ActionKind("Reset motor position", "Calls the current angle zero") { Action.MotorResetPosition(Port.A) },
    ActionKind("Stop everything", "Panic stop") { Action.StopAll },
    ActionKind("Speed multiplier", "Turbo or creep while held") { Action.SpeedScale(200) },
    ActionKind("Hub light", "Colour of the power button light") { Action.SetLight(9) },
    ActionKind("Show text", "Scrolls across the light matrix") { Action.MatrixText("Hi") },
    ActionKind("Show image", "One of the built-in images") { Action.MatrixImage(1) },
    ActionKind("Clear the matrix", "Switches the pixels off") { Action.MatrixClear },
    ActionKind("Beep", "A tone from the hub speaker") { Action.Beep(440, 250, 100) },
    ActionKind("Reset yaw", "Zeroes the gyro heading") { Action.ResetYaw(0) },
    ActionKind("Go to page", "Switch the controller page") { Action.SwitchPage(0) },
    ActionKind("Sequence", "Several actions with delays") { Action.Macro(emptyList()) },
)

private fun kindIndexOf(action: Action): Int = when (action) {
    is Action.MotorRun -> 0
    is Action.AxisMotor -> 1
    is Action.MotorRunForTime -> 2
    is Action.MotorRunForDegrees -> 3
    is Action.MotorToPosition -> 4
    is Action.MotorStop -> 5
    is Action.MotorResetPosition -> 6
    is Action.StopAll -> 7
    is Action.SpeedScale -> 8
    is Action.SetLight -> 9
    is Action.MatrixText -> 10
    is Action.MatrixImage -> 11
    is Action.MatrixClear -> 12
    is Action.Beep -> 13
    is Action.ResetYaw -> 14
    is Action.SwitchPage -> 15
    is Action.Macro -> 16
}

@Composable
fun <T> EnumPicker(
    label: String,
    options: List<T>,
    selected: T,
    labelOf: (T) -> String,
    modifier: Modifier = Modifier,
    onSelect: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = TextDim)
        Box {
            OutlinedButton(onClick = { open = true }) {
                Text(labelOf(selected))
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                options.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(labelOf(option)) },
                        onClick = {
                            onSelect(option)
                            open = false
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun NumberField(
    label: String,
    value: Int,
    modifier: Modifier = Modifier,
    onChange: (Int) -> Unit,
) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    OutlinedTextField(
        value = text,
        onValueChange = { entered ->
            text = entered.filter { it.isDigit() || it == '-' }
            text.toIntOrNull()?.let(onChange)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

@Composable
fun PercentSlider(label: String, value: Int, range: IntRange, onChange: (Int) -> Unit) {
    Column {
        Text("$label: $value", style = MaterialTheme.typography.bodySmall)
        Slider(
            value = value.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
        )
    }
}

@Composable
private fun PortPicker(label: String, port: Port, onChange: (Port) -> Unit) =
    EnumPicker(label, Port.entries.toList(), port, { "Port ${it.letter}" }, onSelect = onChange)

@Composable
private fun StopPicker(stop: StopMode, onChange: (StopMode) -> Unit) =
    EnumPicker("When it finishes", StopMode.entries.toList(), stop, { it.label }, onSelect = onChange)

@Composable
private fun ModePicker(mode: DriveMode, onChange: (DriveMode) -> Unit) =
    EnumPicker(
        "Control",
        DriveMode.entries.toList(),
        mode,
        { if (it == DriveMode.VELOCITY) "Speed (regulated)" else "Power (duty cycle)" },
        onSelect = onChange,
    )

/** The per-action parameter form. */
@Composable
fun ActionFields(
    action: Action,
    pageCount: Int,
    onTest: ((Port, Int) -> Unit)? = null,
    onChange: (Action) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TestMotorButton(action, onTest)
        when (action) {
            is Action.MotorRun -> {
                PortPicker("Motor", action.port) { onChange(action.copy(port = it)) }
                PercentSlider("Speed %", action.speed, -100..100) { onChange(action.copy(speed = it)) }
                ModePicker(action.mode) { onChange(action.copy(mode = it)) }
                StopPicker(action.stopOnRelease) { onChange(action.copy(stopOnRelease = it)) }
                Hint("Negative speed runs the motor the other way.")
            }

            is Action.AxisMotor -> {
                PortPicker("Motor", action.port) { onChange(action.copy(port = it)) }
                PercentSlider("Amount %", action.scale, -100..100) { onChange(action.copy(scale = it)) }
                ModePicker(action.mode) { onChange(action.copy(mode = it)) }
                StopPicker(action.stopOnRelease) { onChange(action.copy(stopOnRelease = it)) }
                Hint("A negative amount reverses this motor — that is how you make both sides of a drive base go the same way.")
            }

            is Action.MotorRunForTime -> {
                PortPicker("Motor", action.port) { onChange(action.copy(port = it)) }
                PercentSlider("Speed %", action.speed, -100..100) { onChange(action.copy(speed = it)) }
                NumberField("Milliseconds", action.ms) { onChange(action.copy(ms = it.coerceIn(1, 600_000))) }
                StopPicker(action.stop) { onChange(action.copy(stop = it)) }
                Hint("The hub times this itself, so it keeps going even if the phone is busy.")
            }

            is Action.MotorRunForDegrees -> {
                PortPicker("Motor", action.port) { onChange(action.copy(port = it)) }
                PercentSlider("Speed %", action.speed, 1..100) { onChange(action.copy(speed = it)) }
                NumberField("Degrees", action.degrees) { onChange(action.copy(degrees = it)) }
                StopPicker(action.stop) { onChange(action.copy(stop = it)) }
                Hint("Negative degrees turn the other way.")
            }

            is Action.MotorToPosition -> {
                PortPicker("Motor", action.port) { onChange(action.copy(port = it)) }
                NumberField("Position in degrees", action.position) { onChange(action.copy(position = it)) }
                PercentSlider("Speed %", action.speed, 1..100) { onChange(action.copy(speed = it)) }
                EnumPicker("Direction", Direction.entries.toList(), action.direction, { it.label }) {
                    onChange(action.copy(direction = it))
                }
                StopPicker(action.stop) { onChange(action.copy(stop = it)) }
            }

            is Action.MotorStop -> {
                PortPicker("Motor", action.port) { onChange(action.copy(port = it)) }
                StopPicker(action.stop) { onChange(action.copy(stop = it)) }
            }

            is Action.MotorResetPosition -> {
                PortPicker("Motor", action.port) { onChange(action.copy(port = it)) }
                NumberField("New position", action.position) { onChange(action.copy(position = it)) }
            }

            is Action.SpeedScale -> {
                PercentSlider("Multiplier %", action.percent, 10..400) { onChange(action.copy(percent = it)) }
                Hint("200% is double speed, 50% is half. Applies to everything while this control is held.")
            }

            is Action.SetLight -> {
                EnumPicker("Colour", HubColor.ids, action.colorId, { HubColor.name(it) }) {
                    onChange(action.copy(colorId = it))
                }
            }

            is Action.MatrixText -> {
                OutlinedTextField(
                    value = action.text,
                    onValueChange = { onChange(action.copy(text = it.take(40))) },
                    label = { Text("Text") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            is Action.MatrixImage -> {
                NumberField("Image number (1-67)", action.imageId) {
                    onChange(action.copy(imageId = it.coerceIn(1, 67)))
                }
            }

            is Action.Beep -> {
                NumberField("Frequency (Hz)", action.freqHz) { onChange(action.copy(freqHz = it)) }
                NumberField("Milliseconds", action.ms) { onChange(action.copy(ms = it)) }
                PercentSlider("Volume", action.volume, 0..100) { onChange(action.copy(volume = it)) }
            }

            is Action.ResetYaw -> NumberField("Angle", action.angle) { onChange(action.copy(angle = it)) }

            is Action.SwitchPage -> {
                NumberField("Page number", action.pageIndex + 1) {
                    onChange(action.copy(pageIndex = (it - 1).coerceIn(0, (pageCount - 1).coerceAtLeast(0))))
                }
            }

            is Action.Macro -> MacroEditor(action, pageCount, onTest, onChange)

            Action.StopAll, Action.MatrixClear -> Hint("Nothing to configure.")
        }
    }
}

/** Nudges the motor an action refers to, so you can see which way it turns. */
@Composable
private fun TestMotorButton(action: Action, onTest: ((Port, Int) -> Unit)?) {
    if (onTest == null) return
    val target: Pair<Port, Int>? = when (action) {
        is Action.MotorRun -> action.port to action.speed
        is Action.AxisMotor -> action.port to action.scale
        is Action.MotorRunForTime -> action.port to action.speed
        is Action.MotorRunForDegrees -> action.port to action.speed
        is Action.MotorToPosition -> action.port to action.speed
        else -> null
    } ?: return
    OutlinedButton(onClick = { onTest(target.first, target.second) }) {
        Text("Test: run ${target.first.letter} briefly")
    }
}

@Composable
private fun MacroEditor(
    macro: Action.Macro,
    pageCount: Int,
    onTest: ((Port, Int) -> Unit)?,
    onChange: (Action) -> Unit,
) {
    var adding by remember { mutableStateOf(false) }
    var editingIndex by remember { mutableStateOf(-1) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Hint("Steps run one after another. The delay is how long to wait before the next step.")
        macro.steps.forEachIndexed { index, step ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${index + 1}. ${step.action.describe()}", modifier = Modifier.weight(1f))
                NumberField(
                    "Delay ms",
                    step.delayMs,
                    modifier = Modifier.width(110.dp),
                ) { value ->
                    val updated = macro.steps.toMutableList()
                    updated[index] = step.copy(delayMs = value.coerceIn(0, 60_000))
                    onChange(macro.copy(steps = updated))
                }
                IconButton(onClick = { editingIndex = index }) {
                    Icon(Icons.Filled.ArrowDropDown, contentDescription = "Edit step")
                }
                IconButton(
                    onClick = {
                        onChange(macro.copy(steps = macro.steps.filterIndexed { i, _ -> i != index }))
                    }
                ) {
                    Icon(Icons.Filled.Delete, contentDescription = "Remove step", tint = Bad)
                }
            }
        }
        OutlinedButton(onClick = { adding = true }) { Text("Add a step") }
    }

    if (adding) {
        ActionDialog(
            initial = null,
            pageCount = pageCount,
            allowMacro = false,
            onTest = onTest,
            onDismiss = { adding = false },
            onConfirm = { action ->
                onChange(macro.copy(steps = macro.steps + MacroStep(action, 500)))
                adding = false
            },
        )
    }

    if (editingIndex >= 0 && editingIndex < macro.steps.size) {
        val step = macro.steps[editingIndex]
        ActionDialog(
            initial = step.action,
            pageCount = pageCount,
            allowMacro = false,
            onTest = onTest,
            onDismiss = { editingIndex = -1 },
            onConfirm = { action ->
                val updated = macro.steps.toMutableList()
                updated[editingIndex] = step.copy(action = action)
                onChange(macro.copy(steps = updated))
                editingIndex = -1
            },
        )
    }
}

/** Create or edit a single action. */
@Composable
fun ActionDialog(
    initial: Action?,
    pageCount: Int,
    allowMacro: Boolean = true,
    onTest: ((Port, Int) -> Unit)? = null,
    onDismiss: () -> Unit,
    onConfirm: (Action) -> Unit,
) {
    val kinds = remember(allowMacro) {
        if (allowMacro) actionKinds else actionKinds.filterNot { it.create() is Action.Macro }
    }
    var action by remember { mutableStateOf(initial ?: kinds.first().create()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "Add an action" else "Edit action") },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                EnumPicker(
                    label = "Action",
                    options = kinds,
                    selected = kinds.getOrElse(kindIndexOf(action)) { kinds.first() },
                    labelOf = { it.label },
                    onSelect = { kind -> action = kind.create() },
                )
                Hint(kinds.getOrNull(kindIndexOf(action))?.hint.orEmpty())
                Spacer(Modifier.width(1.dp))
                ActionFields(action, pageCount, onTest) { action = it }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(action) }) { Text(if (initial == null) "Add" else "Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
