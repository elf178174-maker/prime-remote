package dev.primeremote.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.primeremote.app.AppController
import dev.primeremote.app.ui.theme.Accent
import dev.primeremote.app.ui.theme.Bad
import dev.primeremote.app.ui.theme.Surface1
import dev.primeremote.app.ui.theme.Surface2
import dev.primeremote.app.ui.theme.TextDim
import dev.primeremote.core.model.Action
import dev.primeremote.core.model.Binding
import dev.primeremote.core.model.ControlOptions
import dev.primeremote.core.model.ControlShape
import dev.primeremote.core.model.ControlSpec
import dev.primeremote.core.model.ControlType
import dev.primeremote.core.model.DrivePair
import dev.primeremote.core.model.GamepadBinding
import dev.primeremote.core.model.Page
import dev.primeremote.core.model.Port
import dev.primeremote.core.model.Presets
import dev.primeremote.core.model.Profile
import dev.primeremote.core.model.Slot

private val paletteColors = listOf(
    0xFF4C6EF5, 0xFF2ECC71, 0xFFE67E22, 0xFFE74C3C,
    0xFF9B59B6, 0xFF16A085, 0xFFF1C40F, 0xFF7F8C8D,
).map { it.toInt() }

@Composable
fun EditorScreen(
    controller: AppController,
    profile: Profile,
    onDone: (Profile) -> Unit,
    onCancel: () -> Unit,
) {
    var draft by remember(profile.id) { mutableStateOf(profile) }
    var pageIndex by remember(profile.id) { mutableStateOf(0) }
    var selectedId by remember(profile.id) { mutableStateOf<String?>(null) }
    var showSettings by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }

    val page = draft.pages.getOrElse(pageIndex) { draft.pages.first() }
    val selected = page.controls.firstOrNull { it.id == selectedId }

    fun updatePage(transform: (Page) -> Page) {
        draft = draft.copy(
            pages = draft.pages.mapIndexed { index, p -> if (index == pageIndex) transform(p) else p }
        )
    }

    fun updateControl(id: String, transform: (ControlSpec) -> ControlSpec) {
        updatePage { p -> p.copy(controls = p.controls.map { if (it.id == id) transform(it) else it }) }
    }

    ScreenScaffold(
        title = "Editing ${draft.name}",
        onBack = { if (draft == profile) onCancel() else confirmDiscard = true },
        actions = {
            TextButton(onClick = { showSettings = true }) { Text("Settings") }
            Button(onClick = { onDone(draft) }) { Text("Save") }
        },
    ) { padding ->
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            val wide = maxWidth > 700.dp

            val canvas: @Composable () -> Unit = {
                EditorCanvas(
                    page = page,
                    selectedId = selectedId,
                    onSelect = { selectedId = it },
                    onMove = { id, dx, dy ->
                        updateControl(id) { control ->
                            control.copy(
                                x = (control.x + dx).coerceIn(0f, 1f - control.width),
                                y = (control.y + dy).coerceIn(0f, 1f - control.height),
                            )
                        }
                    },
                    onResize = { id, dw, dh ->
                        updateControl(id) { control ->
                            control.copy(
                                width = (control.width + dw).coerceIn(0.06f, 1f - control.x),
                                height = (control.height + dh).coerceIn(0.08f, 1f - control.y),
                            )
                        }
                    },
                )
            }

            val panel: @Composable () -> Unit = {
                EditorPanel(
                    draft = draft,
                    pageIndex = pageIndex,
                    selected = selected,
                    onAddControl = { type ->
                        val created = newControl(type, page.controls.size)
                        updatePage { p -> p.copy(controls = p.controls + created) }
                        selectedId = created.id
                    },
                    onUpdateControl = { updated -> updateControl(updated.id) { updated } },
                    onDeleteControl = { id ->
                        updatePage { p -> p.copy(controls = p.controls.filterNot { it.id == id }) }
                        selectedId = null
                    },
                    onDuplicateControl = { control ->
                        val copy = control.copy(
                            id = Presets.newId("c"),
                            x = (control.x + 0.04f).coerceAtMost(1f - control.width),
                            y = (control.y + 0.04f).coerceAtMost(1f - control.height),
                        )
                        updatePage { p -> p.copy(controls = p.controls + copy) }
                        selectedId = copy.id
                    },
                    onSelectPage = { index ->
                        pageIndex = index
                        selectedId = null
                    },
                    onAddPage = {
                        draft = draft.copy(
                            pages = draft.pages + Page(Presets.newId("pg"), "Page ${draft.pages.size + 1}")
                        )
                        pageIndex = draft.pages.size - 1
                        selectedId = null
                    },
                    onRenamePage = { name ->
                        updatePage { p -> p.copy(name = name) }
                    },
                    onDeletePage = {
                        if (draft.pages.size > 1) {
                            draft = draft.copy(pages = draft.pages.filterIndexed { i, _ -> i != pageIndex })
                            pageIndex = 0
                            selectedId = null
                        }
                    },
                )
            }

            if (wide) {
                Row(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    ) { canvas() }
                    Box(
                        Modifier
                            .width(360.dp)
                            .fillMaxHeight()
                            .background(Surface1)
                    ) { panel() }
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                    ) { canvas() }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(Surface1)
                    ) { panel() }
                }
            }
        }
    }

    if (showSettings) {
        ProfileSettingsDialog(
            profile = draft,
            onDismiss = { showSettings = false },
            onSave = {
                draft = it
                showSettings = false
            },
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard changes?") },
            text = { Text("The edits to this layout have not been saved.") },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; onCancel() }) {
                    Text("Discard", color = Bad)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false; onDone(draft) }) { Text("Save") }
            },
        )
    }
}

@Composable
private fun EditorCanvas(
    page: Page,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    onMove: (String, Float, Float) -> Unit,
    onResize: (String, Float, Float) -> Unit,
) {
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0C0F14))
            .pointerInput(page.id) {
                detectTapGestures(onTap = { onSelect(null) })
            }
    ) {
        val areaWidth = maxWidth
        val areaHeight = maxHeight
        val density = LocalDensity.current
        val widthPx = with(density) { areaWidth.toPx() }
        val heightPx = with(density) { areaHeight.toPx() }

        if (page.controls.isEmpty()) {
            Text(
                "Add controls from the panel, then drag them into place",
                modifier = Modifier.align(Alignment.Center),
                color = TextDim,
            )
        }

        for (control in page.controls) {
            val isSelected = control.id == selectedId
            Box(
                Modifier
                    .offset(x = areaWidth * control.x, y = areaHeight * control.y)
                    .size(width = areaWidth * control.width, height = areaHeight * control.height)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(control.colorArgb).copy(alpha = if (isSelected) 0.75f else 0.45f))
                    .border(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) Accent else Color.White.copy(alpha = 0.25f),
                        shape = RoundedCornerShape(10.dp),
                    )
                    .pointerInput(control.id) {
                        detectTapGestures(onTap = { onSelect(control.id) })
                    }
                    .pointerInput(control.id) {
                        detectDragGestures(
                            onDragStart = { onSelect(control.id) },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                if (widthPx > 0f && heightPx > 0f) {
                                    onMove(control.id, dragAmount.x / widthPx, dragAmount.y / heightPx)
                                }
                            },
                        )
                    }
            ) {
                Column(Modifier.padding(6.dp)) {
                    Text(
                        control.label.ifEmpty { control.type.name.lowercase() },
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White,
                    )
                    Text(
                        "${control.boundActionCount} action(s)",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }

                if (isSelected) {
                    // resize grip in the bottom-right corner
                    Box(
                        Modifier
                            .align(Alignment.BottomEnd)
                            .size(26.dp)
                            .background(Accent, RoundedCornerShape(topStart = 8.dp))
                            .pointerInput(control.id) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    if (widthPx > 0f && heightPx > 0f) {
                                        onResize(control.id, dragAmount.x / widthPx, dragAmount.y / heightPx)
                                    }
                                }
                            }
                    )
                }
            }
        }
    }
}

@Composable
private fun EditorPanel(
    draft: Profile,
    pageIndex: Int,
    selected: ControlSpec?,
    onAddControl: (ControlType) -> Unit,
    onUpdateControl: (ControlSpec) -> Unit,
    onDeleteControl: (String) -> Unit,
    onDuplicateControl: (ControlSpec) -> Unit,
    onSelectPage: (Int) -> Unit,
    onAddPage: () -> Unit,
    onRenamePage: (String) -> Unit,
    onDeletePage: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("Pages", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            draft.pages.forEachIndexed { index, page ->
                Text(
                    page.name,
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (index == pageIndex) Accent else Surface2)
                        .clickable { onSelectPage(index) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            IconButton(onClick = onAddPage, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Add, contentDescription = "Add a page")
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = draft.pages.getOrElse(pageIndex) { draft.pages.first() }.name,
                onValueChange = onRenamePage,
                label = { Text("Page name") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            if (draft.pages.size > 1) {
                IconButton(onClick = onDeletePage) {
                    Icon(Icons.Filled.Delete, contentDescription = "Delete page", tint = Bad)
                }
            }
        }

        HorizontalDivider()

        Text("Add a control", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ControlType.entries.take(3).forEach { type ->
                OutlinedButton(onClick = { onAddControl(type) }) { Text(typeLabel(type)) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            ControlType.entries.drop(3).forEach { type ->
                OutlinedButton(onClick = { onAddControl(type) }) { Text(typeLabel(type)) }
            }
        }

        HorizontalDivider()

        if (selected == null) {
            Hint("Tap a control on the left to change what it does.")
        } else {
            ControlInspector(
                control = selected,
                pageCount = draft.pages.size,
                onChange = onUpdateControl,
                onDelete = { onDeleteControl(selected.id) },
                onDuplicate = { onDuplicateControl(selected) },
            )
        }
        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun ControlInspector(
    control: ControlSpec,
    pageCount: Int,
    onChange: (ControlSpec) -> Unit,
    onDelete: () -> Unit,
    onDuplicate: () -> Unit,
) {
    var editingSlot by remember { mutableStateOf<Slot?>(null) }
    var editingActionIndex by remember { mutableStateOf(-1) }
    var showGamepad by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(typeLabel(control.type), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = onDuplicate) { Icon(Icons.Filled.Add, contentDescription = "Duplicate") }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = Bad) }
        }

        OutlinedTextField(
            value = control.label,
            onValueChange = { onChange(control.copy(label = it.take(18))) },
            label = { Text("Label") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Text("Colour", style = MaterialTheme.typography.bodySmall, color = TextDim)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            paletteColors.forEach { argb ->
                Box(
                    Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color(argb))
                        .border(
                            width = if (control.colorArgb == argb) 3.dp else 0.dp,
                            color = Color.White,
                            shape = RoundedCornerShape(6.dp),
                        )
                        .clickable { onChange(control.copy(colorArgb = argb)) }
                )
            }
        }

        EnumPicker(
            label = "Shape",
            options = ControlShape.entries.toList(),
            selected = control.shape,
            labelOf = { it.name.lowercase().replaceFirstChar { c -> c.uppercase() } },
            onSelect = { onChange(control.copy(shape = it)) },
        )

        ControlOptionsEditor(control) { onChange(control.copy(options = it)) }

        OutlinedButton(onClick = { showGamepad = true }, modifier = Modifier.fillMaxWidth()) {
            Text("Gamepad: " + (control.gamepad?.describe() ?: "Not mapped"))
        }

        HorizontalDivider()
        Text("What it does", style = MaterialTheme.typography.titleMedium)

        for (slot in control.availableSlots) {
            val actions = control.actionsFor(slot)
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(Surface2)
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(slot.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    IconButton(
                        onClick = {
                            editingSlot = slot
                            editingActionIndex = -1
                        },
                        modifier = Modifier.size(30.dp),
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Add an action")
                    }
                }
                if (actions.isEmpty()) {
                    Hint("Nothing yet")
                }
                actions.forEachIndexed { index, action ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            action.describe(),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = {
                                editingSlot = slot
                                editingActionIndex = index
                            },
                            modifier = Modifier.size(30.dp),
                        ) {
                            Icon(Icons.Filled.Edit, contentDescription = "Edit")
                        }
                        IconButton(
                            onClick = {
                                onChange(
                                    control.withActions(
                                        slot,
                                        actions.filterIndexed { i, _ -> i != index },
                                    )
                                )
                            },
                            modifier = Modifier.size(30.dp),
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = "Remove", tint = Bad)
                        }
                    }
                }
            }
        }
    }

    editingSlot?.let { slot ->
        val actions = control.actionsFor(slot)
        ActionDialog(
            initial = actions.getOrNull(editingActionIndex),
            pageCount = pageCount,
            onDismiss = {
                editingSlot = null
                editingActionIndex = -1
            },
            onConfirm = { action ->
                val updated = if (editingActionIndex >= 0) {
                    actions.mapIndexed { i, existing -> if (i == editingActionIndex) action else existing }
                } else {
                    actions + action
                }
                onChange(control.withActions(slot, updated))
                editingSlot = null
                editingActionIndex = -1
            },
        )
    }

    if (showGamepad) {
        GamepadDialog(
            binding = control.gamepad ?: GamepadBinding(),
            type = control.type,
            onDismiss = { showGamepad = false },
            onSave = {
                onChange(control.copy(gamepad = if (it.isEmpty) null else it))
                showGamepad = false
            },
        )
    }
}

@Composable
private fun ControlOptionsEditor(control: ControlSpec, onChange: (ControlOptions) -> Unit) {
    val options = control.options
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        val analog = control.type == ControlType.JOYSTICK ||
            control.type == ControlType.SLIDER ||
            control.type == ControlType.TILT
        if (analog) {
            PercentSlider("Deadzone %", options.deadzonePercent, 0..50) {
                onChange(options.copy(deadzonePercent = it))
            }
            PercentSlider("Smoothing (expo) %", options.expoPercent, 0..100) {
                onChange(options.copy(expoPercent = it))
            }
            PercentSlider("Maximum output %", options.maxOutputPercent, 10..100) {
                onChange(options.copy(maxOutputPercent = it))
            }
        }
        if (control.type == ControlType.JOYSTICK) {
            SwitchRow("Springs back to centre", options.selfCentering) {
                onChange(options.copy(selfCentering = it))
            }
        }
        if (control.type == ControlType.SLIDER) {
            SwitchRow("Springs back to zero", options.springBack) {
                onChange(options.copy(springBack = it))
            }
            SwitchRow("Runs 0 to 100 instead of -100 to 100", options.unipolar) {
                onChange(options.copy(unipolar = it))
            }
        }
        if (control.type == ControlType.DPAD) {
            SwitchRow("Allow diagonals", options.eightWay) { onChange(options.copy(eightWay = it)) }
        }
        if (control.type == ControlType.BUTTON) {
            SwitchRow("Repeat while held", options.repeatWhileHeld) {
                onChange(options.copy(repeatWhileHeld = it))
            }
            if (options.repeatWhileHeld) {
                NumberField("Repeat every (ms)", options.repeatMs) {
                    onChange(options.copy(repeatMs = it.coerceIn(50, 10_000)))
                }
            }
        }
        SwitchRow("Vibrate on press", options.haptics) { onChange(options.copy(haptics = it)) }
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun GamepadDialog(
    binding: GamepadBinding,
    type: ControlType,
    onDismiss: () -> Unit,
    onSave: (GamepadBinding) -> Unit,
) {
    var draft by remember { mutableStateOf(binding) }
    val keys = listOf(
        "", "BUTTON_A", "BUTTON_B", "BUTTON_X", "BUTTON_Y",
        "BUTTON_L1", "BUTTON_R1", "BUTTON_L2", "BUTTON_R2",
        "BUTTON_THUMBL", "BUTTON_THUMBR", "BUTTON_START", "BUTTON_SELECT",
        "DPAD_UP", "DPAD_DOWN", "DPAD_LEFT", "DPAD_RIGHT",
    )
    val axes = listOf("", "AXIS_X", "AXIS_Y", "AXIS_Z", "AXIS_RX", "AXIS_RY", "AXIS_RZ", "AXIS_HAT_X", "AXIS_HAT_Y", "AXIS_LTRIGGER", "AXIS_RTRIGGER")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Physical gamepad") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Hint("Optional. Map this control to a button or stick on a Bluetooth gamepad; the on-screen control keeps working too.")
                if (type == ControlType.BUTTON || type == ControlType.TOGGLE || type == ControlType.DPAD) {
                    EnumPicker("Button", keys, draft.key ?: "", { it.ifEmpty { "None" } }) {
                        draft = draft.copy(key = it.ifEmpty { null })
                    }
                }
                if (type == ControlType.JOYSTICK || type == ControlType.DPAD || type == ControlType.SLIDER) {
                    EnumPicker("Horizontal axis", axes, draft.axisX ?: "", { it.ifEmpty { "None" } }) {
                        draft = draft.copy(axisX = it.ifEmpty { null })
                    }
                    EnumPicker("Vertical axis", axes, draft.axisY ?: "", { it.ifEmpty { "None" } }) {
                        draft = draft.copy(axisY = it.ifEmpty { null })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(draft) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ProfileSettingsDialog(
    profile: Profile,
    onDismiss: () -> Unit,
    onSave: (Profile) -> Unit,
) {
    var draft by remember { mutableStateOf(profile) }
    val settings = draft.settings

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Layout settings") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { draft = draft.copy(name = it.take(40)) },
                    label = { Text("Layout name") },
                    singleLine = true,
                )
                NumberField("Hub program slot (0-19)", settings.hubSlot) {
                    draft = draft.copy(settings = settings.copy(hubSlot = it.coerceIn(0, 19)))
                }
                PercentSlider("Master speed limit %", settings.masterSpeedPercent, 10..100) {
                    draft = draft.copy(settings = settings.copy(masterSpeedPercent = it))
                }
                NumberField("Updates per second", settings.sendRateHz) {
                    draft = draft.copy(settings = settings.copy(sendRateHz = it.coerceIn(5, 50)))
                }
                NumberField("Telemetry interval (ms, 0 = off)", settings.telemetryIntervalMs) {
                    draft = draft.copy(settings = settings.copy(telemetryIntervalMs = it.coerceIn(0, 10_000)))
                }
                NumberField("Safety watchdog (ms, 0 = off)", settings.watchdogMs) {
                    draft = draft.copy(settings = settings.copy(watchdogMs = it.coerceIn(0, 10_000)))
                }
                Hint("The hub stops the motors if it hears nothing from the phone for this long.")
                NumberField("Ramp (% per second, 0 = instant)", settings.slewRatePercentPerSecond) {
                    draft = draft.copy(settings = settings.copy(slewRatePercentPerSecond = it.coerceIn(0, 1000)))
                }
                NumberField("Default top speed (deg/s)", settings.defaultMaxVelocity) {
                    draft = draft.copy(settings = settings.copy(defaultMaxVelocity = it.coerceIn(100, 1200)))
                }
                SwitchRow("Stop when the app goes to the background", settings.stopOnPause) {
                    draft = draft.copy(settings = settings.copy(stopOnPause = it))
                }

                HorizontalDivider()
                SwitchRow("Drive two motors as a synchronized pair", settings.drivePair != null) { on ->
                    draft = draft.copy(
                        settings = settings.copy(drivePair = if (on) DrivePair(Port.A, Port.B) else null)
                    )
                }
                settings.drivePair?.let { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EnumPicker("Left", Port.entries.toList(), pair.left, { it.letter }) {
                            draft = draft.copy(settings = settings.copy(drivePair = pair.copy(left = it)))
                        }
                        EnumPicker("Right", Port.entries.toList(), pair.right, { it.letter }) {
                            draft = draft.copy(settings = settings.copy(drivePair = pair.copy(right = it)))
                        }
                    }
                    Hint("Pairing keeps both wheels in step. Turn it off if those ports are not a drive base.")
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(draft) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun typeLabel(type: ControlType): String = when (type) {
    ControlType.BUTTON -> "Button"
    ControlType.TOGGLE -> "Switch"
    ControlType.JOYSTICK -> "Joystick"
    ControlType.DPAD -> "D-pad"
    ControlType.SLIDER -> "Slider"
    ControlType.TILT -> "Tilt"
}

private fun newControl(type: ControlType, existing: Int): ControlSpec {
    val step = (existing % 5) * 0.06f
    return when (type) {
        ControlType.JOYSTICK -> ControlSpec(
            id = Presets.newId("c"),
            type = type,
            label = "Stick",
            x = 0.08f + step, y = 0.3f, width = 0.24f, height = 0.5f,
            shape = ControlShape.CIRCLE,
            bindings = listOf(Binding(Slot.AXIS_Y, listOf(Action.AxisMotor(Port.A, 100)))),
        )

        ControlType.DPAD -> ControlSpec(
            id = Presets.newId("c"),
            type = type,
            label = "Pad",
            x = 0.08f + step, y = 0.3f, width = 0.26f, height = 0.5f,
        )

        ControlType.SLIDER -> ControlSpec(
            id = Presets.newId("c"),
            type = type,
            label = "Slider",
            x = 0.45f + step, y = 0.25f, width = 0.1f, height = 0.55f,
        )

        ControlType.TILT -> ControlSpec(
            id = Presets.newId("c"),
            type = type,
            label = "Tilt",
            x = 0.4f + step, y = 0.35f, width = 0.2f, height = 0.4f,
            shape = ControlShape.CIRCLE,
        )

        ControlType.TOGGLE -> ControlSpec(
            id = Presets.newId("c"),
            type = type,
            label = "Switch",
            x = 0.6f + step, y = 0.35f, width = 0.14f, height = 0.2f,
            colorArgb = paletteColors[4],
        )

        ControlType.BUTTON -> ControlSpec(
            id = Presets.newId("c"),
            type = type,
            label = "Button",
            x = 0.6f + step, y = 0.35f, width = 0.13f, height = 0.22f,
            colorArgb = paletteColors[1],
        )
    }
}
