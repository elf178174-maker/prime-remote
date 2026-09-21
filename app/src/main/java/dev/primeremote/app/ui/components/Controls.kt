package dev.primeremote.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import dev.primeremote.core.model.ControlShape
import dev.primeremote.core.model.ControlSpec
import dev.primeremote.core.model.Slot
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** What a control reports back while it is being used. */
interface ControlHost {
    fun press(controlId: String, slot: Slot)
    fun release(controlId: String, slot: Slot)
    fun toggle(controlId: String, on: Boolean)
    fun axis(controlId: String, slot: Slot, value: Float)
    fun haptic()
}

private fun Color.dim(factor: Float) = Color(
    red = red * factor,
    green = green * factor,
    blue = blue * factor,
    alpha = alpha,
)

private fun DrawScope.drawShape(shape: ControlShape, color: Color, pressed: Boolean) {
    val fill = if (pressed) color else color.dim(0.55f)
    when (shape) {
        ControlShape.CIRCLE -> drawCircle(fill, radius = min(size.width, size.height) / 2f)
        ControlShape.SQUARE -> drawRect(fill)
        ControlShape.ROUNDED -> drawRoundRect(
            fill,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(min(size.width, size.height) * 0.22f),
        )
    }
    val outline = if (pressed) Color.White.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.25f)
    when (shape) {
        ControlShape.CIRCLE -> drawCircle(outline, radius = min(size.width, size.height) / 2f, style = Stroke(3f))
        ControlShape.SQUARE -> drawRect(outline, style = Stroke(3f))
        ControlShape.ROUNDED -> drawRoundRect(
            outline,
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(min(size.width, size.height) * 0.22f),
            style = Stroke(3f),
        )
    }
}

private fun DrawScope.drawLabel(measurer: TextMeasurer, text: String, color: Color = Color.White) {
    if (text.isEmpty()) return
    val style = TextStyle(color = color, fontSize = 14.sp, textAlign = TextAlign.Center)
    val layout = measurer.measure(text, style)
    drawText(
        layout,
        topLeft = Offset(
            (size.width - layout.size.width) / 2f,
            (size.height - layout.size.height) / 2f,
        ),
    )
}

@Composable
fun ButtonControl(spec: ControlSpec, host: ControlHost, enabled: Boolean = true) {
    var pressed by remember(spec.id) { mutableStateOf(false) }
    val measurer = rememberTextMeasurer()
    val color = Color(spec.colorArgb)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (!enabled) Modifier else Modifier.trackFinger(
                    key = spec.id,
                    onDown = {
                        pressed = true
                        if (spec.options.haptics) host.haptic()
                        host.press(spec.id, Slot.PRESS)
                    },
                    onUp = {
                        pressed = false
                        host.release(spec.id, Slot.PRESS)
                    },
                )
            )
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawShape(spec.shape, color, pressed)
            drawLabel(measurer, spec.label)
        }
    }
}

@Composable
fun ToggleControl(spec: ControlSpec, host: ControlHost, isOn: Boolean, enabled: Boolean = true) {
    val measurer = rememberTextMeasurer()
    val color = Color(spec.colorArgb)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (!enabled) Modifier else Modifier.trackFinger(
                    key = spec.id,
                    onDown = {
                        if (spec.options.haptics) host.haptic()
                        host.toggle(spec.id, !isOn)
                    },
                    onUp = {},
                )
            )
    ) {
        Canvas(Modifier.fillMaxSize()) {
            drawShape(spec.shape, color, isOn)
            drawLabel(measurer, if (spec.label.isEmpty()) "" else spec.label)
            // a small lamp in the corner so the state is readable at a glance
            val r = min(size.width, size.height) * 0.07f
            drawCircle(
                if (isOn) Color(0xFF2ECC71) else Color(0x66FFFFFF),
                radius = r,
                center = Offset(size.width - r * 2.2f, r * 2.2f),
            )
        }
    }
}

@Composable
fun JoystickControl(spec: ControlSpec, host: ControlHost, enabled: Boolean = true) {
    var knob by remember(spec.id) { mutableStateOf(Offset.Zero) } // -1..1 in both axes
    var touching by remember(spec.id) { mutableStateOf(false) }
    val measurer = rememberTextMeasurer()
    val color = Color(spec.colorArgb)

    fun update(position: Offset, area: Size) {
        val centre = Offset(area.width / 2f, area.height / 2f)
        val radius = min(area.width, area.height) / 2f
        if (radius <= 0f) return
        var dx = (position.x - centre.x) / radius
        var dy = (position.y - centre.y) / radius
        val length = hypot(dx, dy)
        if (length > 1f) {
            dx /= length
            dy /= length
        }
        knob = Offset(dx, dy)
        host.axis(spec.id, Slot.AXIS_X, dx)
        host.axis(spec.id, Slot.AXIS_Y, -dy) // screen Y grows downwards; up should be positive
    }

    var area by remember(spec.id) { mutableStateOf(Size.Zero) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { area = Size(it.width.toFloat(), it.height.toFloat()) }
            .then(
                if (!enabled) Modifier else Modifier.trackFinger(
                    key = spec.id,
                    onDown = { position ->
                        touching = true
                        if (spec.options.haptics) host.haptic()
                        host.press(spec.id, Slot.PRESS)
                        update(position, area)
                    },
                    onMove = { position -> update(position, area) },
                    onUp = {
                        touching = false
                        host.release(spec.id, Slot.PRESS)
                        if (spec.options.selfCentering) {
                            knob = Offset.Zero
                            host.axis(spec.id, Slot.AXIS_X, 0f)
                            host.axis(spec.id, Slot.AXIS_Y, 0f)
                        }
                    },
                )
            )
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = min(size.width, size.height) / 2f
            val centre = Offset(size.width / 2f, size.height / 2f)

            drawCircle(color.dim(0.25f), radius = radius, center = centre)
            drawCircle(Color.White.copy(alpha = 0.18f), radius = radius, center = centre, style = Stroke(3f))
            // deadzone ring
            val dz = spec.options.deadzonePercent / 100f
            if (dz > 0f) {
                drawCircle(
                    Color.White.copy(alpha = 0.10f),
                    radius = radius * dz,
                    center = centre,
                    style = Stroke(2f),
                )
            }
            // cross hairs
            drawLine(
                Color.White.copy(alpha = 0.12f),
                Offset(centre.x - radius, centre.y),
                Offset(centre.x + radius, centre.y),
                strokeWidth = 2f,
            )
            drawLine(
                Color.White.copy(alpha = 0.12f),
                Offset(centre.x, centre.y - radius),
                Offset(centre.x, centre.y + radius),
                strokeWidth = 2f,
            )

            val knobCentre = Offset(centre.x + knob.x * radius * 0.78f, centre.y + knob.y * radius * 0.78f)
            drawCircle(
                if (touching) color else color.dim(0.7f),
                radius = radius * 0.28f,
                center = knobCentre,
            )
            drawCircle(
                Color.White.copy(alpha = 0.6f),
                radius = radius * 0.28f,
                center = knobCentre,
                style = Stroke(3f),
            )
            if (spec.label.isNotEmpty()) {
                val layout = measurer.measure(
                    spec.label,
                    TextStyle(color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp),
                )
                drawText(layout, topLeft = Offset((size.width - layout.size.width) / 2f, 4f))
            }
        }
    }
}

@Composable
fun DPadControl(spec: ControlSpec, host: ControlHost, enabled: Boolean = true) {
    var active by remember(spec.id) { mutableStateOf(emptySet<Slot>()) }
    var area by remember(spec.id) { mutableStateOf(Size.Zero) }
    val color = Color(spec.colorArgb)

    fun directionsAt(position: Offset, size: Size): Set<Slot> {
        if (size.width <= 0f || size.height <= 0f) return emptySet()
        val dx = (position.x - size.width / 2f) / (size.width / 2f)
        val dy = (position.y - size.height / 2f) / (size.height / 2f)
        if (hypot(dx, dy) < 0.22f) return emptySet() // dead centre
        val result = mutableSetOf<Slot>()
        if (spec.options.eightWay) {
            // Anything beyond this fraction of the dominant axis counts as a diagonal.
            val threshold = 0.4f
            val major = max(abs(dx), abs(dy))
            if (abs(dy) >= major * threshold) result.add(if (dy < 0) Slot.UP else Slot.DOWN)
            if (abs(dx) >= major * threshold) result.add(if (dx < 0) Slot.LEFT else Slot.RIGHT)
        } else {
            if (abs(dx) > abs(dy)) result.add(if (dx < 0) Slot.LEFT else Slot.RIGHT)
            else result.add(if (dy < 0) Slot.UP else Slot.DOWN)
        }
        return result
    }

    fun apply(next: Set<Slot>) {
        if (next == active) return
        for (slot in active - next) host.release(spec.id, slot)
        for (slot in next - active) {
            if (spec.options.haptics) host.haptic()
            host.press(spec.id, slot)
        }
        active = next
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { area = Size(it.width.toFloat(), it.height.toFloat()) }
            .then(
                if (!enabled) Modifier else Modifier.trackFinger(
                    key = spec.id,
                    onDown = { apply(directionsAt(it, area)) },
                    onMove = { apply(directionsAt(it, area)) },
                    onUp = { apply(emptySet()) },
                )
            )
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val armW = w / 3f
            val armH = h / 3f

            fun arm(slot: Slot, left: Float, top: Float, width: Float, height: Float) {
                val on = slot in active
                drawRoundRect(
                    if (on) color else color.dim(0.5f),
                    topLeft = Offset(left, top),
                    size = Size(width, height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(width * 0.18f),
                )
                drawRoundRect(
                    Color.White.copy(alpha = if (on) 0.8f else 0.2f),
                    topLeft = Offset(left, top),
                    size = Size(width, height),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(width * 0.18f),
                    style = Stroke(3f),
                )
            }

            arm(Slot.UP, armW, 0f, armW, armH)
            arm(Slot.DOWN, armW, h - armH, armW, armH)
            arm(Slot.LEFT, 0f, armH, armW, armH)
            arm(Slot.RIGHT, w - armW, armH, armW, armH)
            drawRoundRect(
                color.dim(0.35f),
                topLeft = Offset(armW, armH),
                size = Size(armW, armH),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f),
            )
        }
    }
}

@Composable
fun SliderControl(spec: ControlSpec, host: ControlHost, enabled: Boolean = true) {
    // Stored in -1..1; the engine turns that into a percentage.
    var value by remember(spec.id) { mutableStateOf(0f) }
    var area by remember(spec.id) { mutableStateOf(Size.Zero) }
    val measurer = rememberTextMeasurer()
    val color = Color(spec.colorArgb)
    val vertical = spec.height >= spec.width

    fun update(position: Offset, size: Size) {
        if (size.width <= 0f || size.height <= 0f) return
        val raw = if (vertical) {
            1f - 2f * (position.y / size.height)
        } else {
            2f * (position.x / size.width) - 1f
        }
        value = raw.coerceIn(-1f, 1f)
        host.axis(spec.id, Slot.AXIS, value)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onSizeChanged { area = Size(it.width.toFloat(), it.height.toFloat()) }
            .then(
                if (!enabled) Modifier else Modifier.trackFinger(
                    key = spec.id,
                    onDown = { position ->
                        if (spec.options.haptics) host.haptic()
                        update(position, area)
                    },
                    onMove = { position -> update(position, area) },
                    onUp = {
                        if (spec.options.springBack) {
                            value = 0f
                            host.axis(spec.id, Slot.AXIS, 0f)
                        }
                    },
                )
            )
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = androidx.compose.ui.geometry.CornerRadius(min(size.width, size.height) * 0.3f)
            drawRoundRect(color.dim(0.22f), cornerRadius = radius)
            drawRoundRect(Color.White.copy(alpha = 0.18f), cornerRadius = radius, style = Stroke(3f))

            // the zero line
            if (!spec.options.unipolar) {
                if (vertical) {
                    drawLine(
                        Color.White.copy(alpha = 0.2f),
                        Offset(0f, size.height / 2f),
                        Offset(size.width, size.height / 2f),
                        strokeWidth = 2f,
                    )
                } else {
                    drawLine(
                        Color.White.copy(alpha = 0.2f),
                        Offset(size.width / 2f, 0f),
                        Offset(size.width / 2f, size.height),
                        strokeWidth = 2f,
                    )
                }
            }

            val knobThickness = if (vertical) size.height * 0.12f else size.width * 0.12f
            if (vertical) {
                val y = ((1f - value) / 2f) * (size.height - knobThickness)
                drawRoundRect(
                    color,
                    topLeft = Offset(size.width * 0.08f, y),
                    size = Size(size.width * 0.84f, knobThickness),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(knobThickness * 0.4f),
                )
            } else {
                val x = ((value + 1f) / 2f) * (size.width - knobThickness)
                drawRoundRect(
                    color,
                    topLeft = Offset(x, size.height * 0.08f),
                    size = Size(knobThickness, size.height * 0.84f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(knobThickness * 0.4f),
                )
            }

            if (spec.label.isNotEmpty()) {
                val layout = measurer.measure(
                    spec.label,
                    TextStyle(color = Color.White.copy(alpha = 0.75f), fontSize = 12.sp),
                )
                drawText(layout, topLeft = Offset((size.width - layout.size.width) / 2f, 2f))
            }
        }
    }
}

/**
 * Steer by tilting the phone. The values come from the app's tilt provider rather than
 * from touch, so this control draws a read-out instead of reacting to a finger.
 */
@Composable
fun TiltControl(spec: ControlSpec, tiltX: Float, tiltY: Float, armed: Boolean, onArmChange: (Boolean) -> Unit) {
    val measurer = rememberTextMeasurer()
    val color = Color(spec.colorArgb)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .trackFinger(
                key = spec.id,
                onDown = { onArmChange(true) },
                onUp = { onArmChange(false) },
            )
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val radius = min(size.width, size.height) / 2f
            val centre = Offset(size.width / 2f, size.height / 2f)
            drawCircle(color.dim(if (armed) 0.45f else 0.2f), radius = radius, center = centre)
            drawCircle(Color.White.copy(alpha = 0.2f), radius = radius, center = centre, style = Stroke(3f))
            drawCircle(
                if (armed) Color.White else Color.White.copy(alpha = 0.5f),
                radius = radius * 0.16f,
                center = Offset(centre.x + tiltX * radius * 0.7f, centre.y - tiltY * radius * 0.7f),
            )
            val text = if (armed) (spec.label.ifEmpty { "Tilt" }) else "Hold to tilt"
            val layout = measurer.measure(text, TextStyle(color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp))
            drawText(layout, topLeft = Offset((size.width - layout.size.width) / 2f, size.height - layout.size.height - 4f))
        }
    }
}
