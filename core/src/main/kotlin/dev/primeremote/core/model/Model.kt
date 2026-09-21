package dev.primeremote.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A motor/sensor port on the hub. */
@Serializable
enum class Port(val index: Int, val letter: String) {
    A(0, "A"), B(1, "B"), C(2, "C"), D(3, "D"), E(4, "E"), F(5, "F");

    companion object {
        fun fromIndex(i: Int): Port? = entries.getOrNull(i)
        fun fromLetter(s: String): Port? = entries.firstOrNull { it.letter.equals(s, ignoreCase = true) }
    }
}

/** How a motor behaves once a movement finishes. Values match LEGO's `motor` constants. */
@Serializable
enum class StopMode(val code: Int, val label: String) {
    COAST(0, "Coast"),
    BRAKE(1, "Brake"),
    HOLD(2, "Hold"),
    CONTINUE(3, "Continue"),
    SMART_COAST(4, "Smart coast"),
    SMART_BRAKE(5, "Smart brake"),
}

/** Which way a motor turns to reach an absolute position. Values match LEGO's `motor` constants. */
@Serializable
enum class Direction(val code: Int, val label: String) {
    CLOCKWISE(0, "Clockwise"),
    COUNTERCLOCKWISE(1, "Counter-clockwise"),
    SHORTEST_PATH(2, "Shortest path"),
    LONGEST_PATH(3, "Longest path"),
}

/**
 * Speed-regulated (degrees/second) or raw power (duty cycle).
 *
 * VELOCITY keeps a steady speed under load and is what you normally want.
 * POWER applies a fixed amount of power, which feels more direct and lets a motor
 * stall gently — useful for grabbers and arms.
 */
@Serializable
enum class DriveMode { VELOCITY, POWER }

@Serializable
enum class ControlType {
    BUTTON,
    TOGGLE,
    JOYSTICK,
    DPAD,
    SLIDER,
    TILT,
}

/**
 * Where on a control a set of actions is attached.
 *
 * Continuous actions (MotorRun / AxisMotor / SpeedScale) stay active for as long as the
 * slot is active; one-shot actions fire once when the slot becomes active.
 */
@Serializable
enum class Slot(val label: String) {
    PRESS("While pressed"),
    RELEASE("On release"),
    TOGGLE_ON("When switched on"),
    TOGGLE_OFF("When switched off"),
    UP("Up"),
    DOWN("Down"),
    LEFT("Left"),
    RIGHT("Right"),
    AXIS_X("Horizontal axis"),
    AXIS_Y("Vertical axis"),
    AXIS("Axis");

    val isAxis: Boolean get() = this == AXIS_X || this == AXIS_Y || this == AXIS
}

/** Everything a control can be made to do. */
@Serializable
sealed interface Action {

    /** A human-readable one-line summary, used all over the editor UI. */
    fun describe(): String

    /** True when the action stays in effect while its slot is active. */
    val isContinuous: Boolean get() = false

    // ---- continuous ----

    /** Run [port] at [speed] percent for as long as the slot is active. */
    @Serializable
    @SerialName("motor_run")
    data class MotorRun(
        val port: Port,
        val speed: Int = 75,
        val mode: DriveMode = DriveMode.VELOCITY,
        /** What the motor does once the slot stops being active. */
        val stopOnRelease: StopMode = StopMode.BRAKE,
    ) : Action {
        override val isContinuous get() = true
        override fun describe() = "Run motor ${port.letter} at $speed%"
    }

    /**
     * Map an analog axis onto [port]: the axis value (-100..100) is multiplied by
     * [scale] percent. A negative [scale] reverses the motor, which is how you make the
     * two sides of a drive base turn the same way.
     */
    @Serializable
    @SerialName("axis_motor")
    data class AxisMotor(
        val port: Port,
        val scale: Int = 100,
        val mode: DriveMode = DriveMode.VELOCITY,
        /** What the motor does once the axis returns to centre. */
        val stopOnRelease: StopMode = StopMode.BRAKE,
    ) : Action {
        override val isContinuous get() = true
        override fun describe() =
            "Motor ${port.letter} follows axis at $scale%" + if (scale < 0) " (reversed)" else ""
    }

    /** Scale every other motor contribution while the slot is active — a turbo or creep button. */
    @Serializable
    @SerialName("speed_scale")
    data class SpeedScale(val percent: Int = 200) : Action {
        override val isContinuous get() = true
        override fun describe() = "Speed x${percent / 100f}"
    }

    // ---- one-shot ----

    /** Run [port] at [speed] percent for [ms] milliseconds, timed by the hub itself. */
    @Serializable
    @SerialName("motor_time")
    data class MotorRunForTime(
        val port: Port,
        val speed: Int = 75,
        val ms: Int = 1000,
        val stop: StopMode = StopMode.BRAKE,
    ) : Action {
        override fun describe() = "Run motor ${port.letter} at $speed% for ${ms / 1000f}s"
    }

    @Serializable
    @SerialName("motor_degrees")
    data class MotorRunForDegrees(
        val port: Port,
        val speed: Int = 75,
        val degrees: Int = 360,
        val stop: StopMode = StopMode.BRAKE,
    ) : Action {
        override fun describe() = "Turn motor ${port.letter} $degrees° at $speed%"
    }

    @Serializable
    @SerialName("motor_position")
    data class MotorToPosition(
        val port: Port,
        val position: Int = 0,
        val speed: Int = 75,
        val direction: Direction = Direction.SHORTEST_PATH,
        val stop: StopMode = StopMode.HOLD,
    ) : Action {
        override fun describe() = "Move motor ${port.letter} to $position°"
    }

    @Serializable
    @SerialName("motor_stop")
    data class MotorStop(val port: Port, val stop: StopMode = StopMode.BRAKE) : Action {
        override fun describe() = "Stop motor ${port.letter} (${stop.label.lowercase()})"
    }

    @Serializable
    @SerialName("motor_reset")
    data class MotorResetPosition(val port: Port, val position: Int = 0) : Action {
        override fun describe() = "Set motor ${port.letter} position to $position°"
    }

    @Serializable
    @SerialName("stop_all")
    data object StopAll : Action {
        override fun describe() = "Stop all motors"
    }

    @Serializable
    @SerialName("light")
    data class SetLight(val colorId: Int = 9) : Action {
        override fun describe() = "Hub light: ${HubColor.name(colorId)}"
    }

    @Serializable
    @SerialName("matrix_text")
    data class MatrixText(val text: String = "Hi") : Action {
        override fun describe() = "Show text \"$text\""
    }

    @Serializable
    @SerialName("matrix_image")
    data class MatrixImage(val imageId: Int = 1) : Action {
        override fun describe() = "Show image #$imageId"
    }

    @Serializable
    @SerialName("matrix_clear")
    data object MatrixClear : Action {
        override fun describe() = "Clear the light matrix"
    }

    @Serializable
    @SerialName("beep")
    data class Beep(val freqHz: Int = 440, val ms: Int = 250, val volume: Int = 100) : Action {
        override fun describe() = "Beep ${freqHz}Hz for ${ms}ms"
    }

    @Serializable
    @SerialName("reset_yaw")
    data class ResetYaw(val angle: Int = 0) : Action {
        override fun describe() = "Reset yaw to $angle°"
    }

    /** Switch the controller to another page of the same profile (app side, no hub traffic). */
    @Serializable
    @SerialName("switch_page")
    data class SwitchPage(val pageIndex: Int = 0) : Action {
        override fun describe() = "Go to page ${pageIndex + 1}"
    }

    /** Run a list of actions with delays between them. */
    @Serializable
    @SerialName("macro")
    data class Macro(val steps: List<MacroStep> = emptyList()) : Action {
        override fun describe() = "Sequence of ${steps.size} step" + if (steps.size == 1) "" else "s"
    }
}

@Serializable
data class MacroStep(
    val action: Action,
    /** Wait this long after running [action] before moving to the next step. */
    val delayMs: Int = 500,
)

/** LEGO `color` module constants, for the hub status light. */
object HubColor {
    val ids = listOf(0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
    fun name(id: Int): String = when (id) {
        0 -> "Black"; 1 -> "Magenta"; 2 -> "Purple"; 3 -> "Blue"; 4 -> "Azure"
        5 -> "Turquoise"; 6 -> "Green"; 7 -> "Yellow"; 8 -> "Orange"; 9 -> "Red"
        10 -> "White"; else -> "Unknown"
    }
    /** ARGB preview colors for the editor UI. */
    fun argb(id: Int): Int = when (id) {
        0 -> 0xFF202020.toInt(); 1 -> 0xFFE91E8C.toInt(); 2 -> 0xFF8E44AD.toInt()
        3 -> 0xFF2D6CDF.toInt(); 4 -> 0xFF29A8E0.toInt(); 5 -> 0xFF1ABC9C.toInt()
        6 -> 0xFF2ECC40.toInt(); 7 -> 0xFFF1C40F.toInt(); 8 -> 0xFFF39C12.toInt()
        9 -> 0xFFE74C3C.toInt(); 10 -> 0xFFECF0F1.toInt(); else -> 0xFF808080.toInt()
    }
}

/** A set of actions attached to one slot of a control. */
@Serializable
data class Binding(
    val slot: Slot,
    val actions: List<Action> = emptyList(),
)

@Serializable
enum class ControlShape { ROUNDED, CIRCLE, SQUARE }

/**
 * Optional mapping from a physical game controller to this control.
 *
 * Names are Android's own: `BUTTON_A`, `BUTTON_R1`, `DPAD_UP` for keys and `AXIS_X`,
 * `AXIS_RZ`, `AXIS_HAT_X` for sticks and triggers. A control with a mapping works from
 * the screen and from the gamepad at the same time.
 */
@Serializable
data class GamepadBinding(
    val key: String? = null,
    val axisX: String? = null,
    val axisY: String? = null,
) {
    val isEmpty: Boolean get() = key == null && axisX == null && axisY == null

    fun describe(): String = listOfNotNull(
        key,
        axisX?.let { "X:$it" },
        axisY?.let { "Y:$it" },
    ).joinToString(" ").ifEmpty { "Not mapped" }
}

/** Per-control tuning. Not every field applies to every control type. */
@Serializable
data class ControlOptions(
    /** Analog inputs below this percentage are treated as zero. */
    val deadzonePercent: Int = 10,
    /**
     * Response curve for analog inputs. 0 = linear, 100 = very soft around centre.
     * Softer curves make fine steering much easier.
     */
    val expoPercent: Int = 0,
    /** Joystick returns to centre when released. Turn off for a "set and leave" stick. */
    val selfCentering: Boolean = true,
    /** Limit the magnitude an analog control can reach. */
    val maxOutputPercent: Int = 100,
    /** Slider travels 0..100 instead of -100..100. */
    val unipolar: Boolean = false,
    /** Vibrate the phone when a button is pressed. */
    val haptics: Boolean = true,
    /** Repeat one-shot actions while the control is held down. */
    val repeatWhileHeld: Boolean = false,
    /** Repeat interval when [repeatWhileHeld] is on. */
    val repeatMs: Int = 500,
    /** D-pad also reports the four diagonals (both neighbouring directions at once). */
    val eightWay: Boolean = true,
    /** Slider snaps back to its resting value when released. */
    val springBack: Boolean = false,
)

/** One control on the controller canvas. Geometry is normalized 0..1 of the canvas. */
@Serializable
data class ControlSpec(
    val id: String,
    val type: ControlType,
    val label: String = "",
    val x: Float = 0.1f,
    val y: Float = 0.1f,
    val width: Float = 0.15f,
    val height: Float = 0.25f,
    val colorArgb: Int = 0xFF4C6EF5.toInt(),
    val shape: ControlShape = ControlShape.ROUNDED,
    val bindings: List<Binding> = emptyList(),
    val options: ControlOptions = ControlOptions(),
    /** Optional physical game controller mapping. */
    val gamepad: GamepadBinding? = null,
) {
    fun actionsFor(slot: Slot): List<Action> =
        bindings.firstOrNull { it.slot == slot }?.actions ?: emptyList()

    fun withActions(slot: Slot, actions: List<Action>): ControlSpec {
        val existing = bindings.firstOrNull { it.slot == slot }
        val updated = when {
            existing == null && actions.isEmpty() -> bindings
            existing == null -> bindings + Binding(slot, actions)
            actions.isEmpty() -> bindings.filterNot { it.slot == slot }
            else -> bindings.map { if (it.slot == slot) it.copy(actions = actions) else it }
        }
        return copy(bindings = updated)
    }

    /** Slots this control type can use, in the order the editor should show them. */
    val availableSlots: List<Slot>
        get() = when (type) {
            ControlType.BUTTON -> listOf(Slot.PRESS, Slot.RELEASE)
            ControlType.TOGGLE -> listOf(Slot.TOGGLE_ON, Slot.TOGGLE_OFF)
            ControlType.JOYSTICK -> listOf(Slot.AXIS_X, Slot.AXIS_Y, Slot.PRESS, Slot.RELEASE)
            ControlType.DPAD -> listOf(Slot.UP, Slot.DOWN, Slot.LEFT, Slot.RIGHT)
            ControlType.SLIDER -> listOf(Slot.AXIS)
            ControlType.TILT -> listOf(Slot.AXIS_X, Slot.AXIS_Y)
        }

    val boundActionCount: Int get() = bindings.sumOf { it.actions.size }
}

@Serializable
data class Page(
    val id: String,
    val name: String = "Page",
    val controls: List<ControlSpec> = emptyList(),
)

/** Optional hardware-synchronized drive base. */
@Serializable
data class DrivePair(
    val left: Port = Port.A,
    val right: Port = Port.B,
    /** Which of the hub's three pair slots to use. */
    val pairSlot: Int = 0,
) {
    fun contains(port: Port) = port == left || port == right
}

@Serializable
data class ProfileSettings(
    /** Program slot on the hub that Prime-Remote uploads its receiver program to. */
    val hubSlot: Int = 19,
    /** How often the app pushes control state to the hub. */
    val sendRateHz: Int = 20,
    /** How often the hub reports sensor/motor state back. 0 disables telemetry. */
    val telemetryIntervalMs: Int = 200,
    /**
     * The hub stops all motors if it hears nothing for this long. 0 disables it.
     * This is what stops a robot driving into a wall if the phone goes out of range.
     */
    val watchdogMs: Int = 700,
    /** Default top speed in degrees/second for motors whose type isn't known yet. */
    val defaultMaxVelocity: Int = 1000,
    /** Global speed limit applied to every motor command, as a percentage. */
    val masterSpeedPercent: Int = 100,
    /** When set, the two ports are driven as a synchronized pair. */
    val drivePair: DrivePair? = null,
    /** Stop the robot when the app goes into the background. */
    val stopOnPause: Boolean = true,
    /** Keep the screen awake while the controller is open. */
    val keepScreenOn: Boolean = true,
    /** Ramp motor changes instead of applying them instantly; 0 disables. Percent per second. */
    val slewRatePercentPerSecond: Int = 0,
)

@Serializable
data class Profile(
    val id: String,
    val name: String = "New layout",
    val pages: List<Page> = listOf(Page(id = "page-1", name = "Main")),
    val settings: ProfileSettings = ProfileSettings(),
    /** Schema version, so that older exported files can be migrated. */
    val version: Int = 1,
) {
    fun page(index: Int): Page = pages.getOrElse(index) { pages.first() }
}
