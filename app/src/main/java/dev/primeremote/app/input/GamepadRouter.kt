package dev.primeremote.app.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import dev.primeremote.app.AppController
import dev.primeremote.core.model.ControlType
import dev.primeremote.core.model.Slot
import kotlin.math.abs

/**
 * Lets a physical game controller drive the same layout as the touchscreen.
 *
 * A control carries an optional mapping (`BUTTON_A`, `AXIS_X`, …) and this router turns
 * real gamepad events into exactly the same calls the on-screen controls make, so both
 * work at once and neither needs to know about the other.
 */
class GamepadRouter(private val controller: AppController) {

    private val pressedKeys = HashSet<String>()

    /** @return true when the event belonged to a mapped control. */
    fun onKey(event: KeyEvent): Boolean {
        if (!isGamepad(event.device)) return false
        val name = KeyEvent.keyCodeToString(event.keyCode).removePrefix("KEYCODE_")
        val page = currentControls()
        var handled = false

        for (control in page) {
            val key = control.gamepad?.key ?: continue
            if (!key.equals(name, ignoreCase = true)) continue
            handled = true
            when (event.action) {
                KeyEvent.ACTION_DOWN -> {
                    if (!pressedKeys.add(control.id + name)) continue // ignore auto-repeat
                    when (control.type) {
                        ControlType.TOGGLE -> controller.toggle(control.id, !controller.engine.isToggled(control.id))
                        else -> controller.press(control.id, defaultSlot(control.type))
                    }
                }

                KeyEvent.ACTION_UP -> {
                    pressedKeys.remove(control.id + name)
                    if (control.type != ControlType.TOGGLE) {
                        controller.release(control.id, defaultSlot(control.type))
                    }
                }
            }
        }
        return handled
    }

    fun onMotion(event: MotionEvent): Boolean {
        if (!isGamepad(event.device)) return false
        if (event.action != MotionEvent.ACTION_MOVE) return false
        var handled = false

        for (control in currentControls()) {
            val mapping = control.gamepad ?: continue
            mapping.axisX?.let { axisName ->
                val axis = MotionEvent.axisFromString(normalizeAxis(axisName))
                if (axis >= 0) {
                    handled = true
                    val slot = if (control.type == ControlType.SLIDER) Slot.AXIS else Slot.AXIS_X
                    controller.axis(control.id, slot, deadzoned(event.getAxisValue(axis)))
                }
            }
            mapping.axisY?.let { axisName ->
                val axis = MotionEvent.axisFromString(normalizeAxis(axisName))
                if (axis >= 0) {
                    handled = true
                    val slot = if (control.type == ControlType.SLIDER) Slot.AXIS else Slot.AXIS_Y
                    // Gamepad sticks report "up" as negative, the engine wants it positive.
                    controller.axis(control.id, slot, -deadzoned(event.getAxisValue(axis)))
                }
            }
        }
        return handled
    }

    /** Release everything, for when the controller screen goes away. */
    fun reset() {
        pressedKeys.clear()
    }

    private fun currentControls() =
        controller.activeProfile.value.page(controller.pageIndex.value).controls

    private fun defaultSlot(type: ControlType): Slot =
        if (type == ControlType.DPAD) Slot.UP else Slot.PRESS

    private fun deadzoned(value: Float): Float = if (abs(value) < HARDWARE_DEADZONE) 0f else value

    private fun normalizeAxis(name: String): String =
        if (name.startsWith("AXIS_")) name else "AXIS_$name"

    private fun isGamepad(device: InputDevice?): Boolean {
        val sources = device?.sources ?: return false
        return sources and InputDevice.SOURCE_GAMEPAD == InputDevice.SOURCE_GAMEPAD ||
            sources and InputDevice.SOURCE_JOYSTICK == InputDevice.SOURCE_JOYSTICK
    }

    private companion object {
        /** Cheap sticks rest a little off centre; ignore the slop before the layout's own deadzone. */
        const val HARDWARE_DEADZONE = 0.08f
    }
}
