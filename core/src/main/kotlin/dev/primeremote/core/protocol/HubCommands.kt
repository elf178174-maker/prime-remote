package dev.primeremote.core.protocol

import dev.primeremote.core.model.Direction
import dev.primeremote.core.model.Port
import dev.primeremote.core.model.StopMode

/**
 * The little ASCII command language Prime-Remote speaks to the program running on the hub.
 *
 * Commands are sent inside a [TunnelMessage], whose payload the firmware hands to the
 * stdin of the running program. Several commands can share one line, separated by `;`.
 * The line is terminated with `\n`.
 *
 * The format is deliberately printable ASCII: the payload of a tunnel message reaches the
 * program through its console input, where a raw 0x03 byte would read as ctrl-C and kill
 * the program. Keeping to printable characters side-steps that entirely, and makes the
 * traffic readable in the app's debug console.
 *
 * The other half of this protocol lives in `app/src/main/assets/prime_remote_hub.py`.
 * Keep the two in sync; `tools/test_hub_program.py` checks that they agree.
 */
object HubCommands {

    const val TERMINATOR = "\n"
    const val SEPARATOR = ";"

    /** motor.run(port, velocity) — runs until told otherwise. */
    fun run(port: Port, velocity: Int, acceleration: Int? = null): String =
        if (acceleration == null) "mv ${port.letter} $velocity"
        else "mv ${port.letter} $velocity $acceleration"

    /** motor.set_duty_cycle(port, pwm), pwm in -10000..10000. */
    fun duty(port: Port, pwm: Int): String = "md ${port.letter} ${pwm.coerceIn(-10000, 10000)}"

    /** motor.stop(port, stop=...) */
    fun stop(port: Port, stopMode: StopMode): String = "ms ${port.letter} ${stopMode.code}"

    /** motor.run_for_time(port, duration, velocity, stop=...) */
    fun runForTime(port: Port, ms: Int, velocity: Int, stopMode: StopMode): String =
        "mt ${port.letter} $ms $velocity ${stopMode.code}"

    /** motor.run_for_degrees(port, degrees, velocity, stop=...) */
    fun runForDegrees(port: Port, degrees: Int, velocity: Int, stopMode: StopMode): String =
        "mg ${port.letter} $degrees $velocity ${stopMode.code}"

    /** motor.run_to_absolute_position(port, position, velocity, direction=..., stop=...) */
    fun runToPosition(port: Port, position: Int, velocity: Int, direction: Direction, stopMode: StopMode): String =
        "mp ${port.letter} $position $velocity ${direction.code} ${stopMode.code}"

    /** motor.reset_relative_position(port, position) */
    fun resetPosition(port: Port, position: Int = 0): String = "mr ${port.letter} $position"

    /** motor_pair.pair(slot, left, right) */
    fun pair(slot: Int, left: Port, right: Port): String = "pr $slot ${left.letter} ${right.letter}"

    /** motor_pair.move_tank(slot, leftVelocity, rightVelocity) */
    fun tank(slot: Int, leftVelocity: Int, rightVelocity: Int): String = "pt $slot $leftVelocity $rightVelocity"

    /** motor_pair.move(slot, steering, velocity=...) */
    fun move(slot: Int, steering: Int, velocity: Int): String =
        "pm $slot ${steering.coerceIn(-100, 100)} $velocity"

    /** motor_pair.stop(slot, stop=...) */
    fun pairStop(slot: Int, stopMode: StopMode): String = "ps $slot ${stopMode.code}"

    /** light.color(light.POWER, color) */
    fun light(colorId: Int): String = "lc ${colorId.coerceIn(0, 10)}"

    /** light_matrix.write(text) */
    fun matrixText(text: String): String = "lw ${sanitize(text)}"

    /** light_matrix.show_image(image) */
    fun matrixImage(imageId: Int): String = "li ${imageId.coerceIn(1, 67)}"

    /** light_matrix.set_pixel(x, y, intensity) */
    fun matrixPixel(x: Int, y: Int, intensity: Int): String =
        "lp ${x.coerceIn(0, 4)} ${y.coerceIn(0, 4)} ${intensity.coerceIn(0, 100)}"

    /** light_matrix.clear() */
    fun matrixClear(): String = "lx"

    /** sound.beep(freq, duration, volume) */
    fun beep(freqHz: Int, ms: Int, volume: Int): String =
        "bp ${freqHz.coerceIn(60, 8000)} ${ms.coerceIn(1, 60000)} ${volume.coerceIn(0, 100)}"

    /** motion_sensor.reset_yaw(angle) */
    fun resetYaw(angle: Int = 0): String = "yr $angle"

    /** Arm/disarm the hub-side watchdog. 0 disables it. */
    fun watchdog(ms: Int): String = "wd ${ms.coerceAtLeast(0)}"

    /** Round-trip probe; the hub answers on the console with `!pg <n>`. */
    fun ping(n: Int): String = "pg $n"

    /** Stop every motor and motor pair immediately. */
    fun stopAll(): String = "st"

    /** Ask the hub program to re-announce itself with `!rdy <version> <mode>`. */
    fun version(): String = "ver"

    /**
     * Strip characters that would confuse the line protocol. `;` separates commands,
     * `\n` terminates the line, and anything non-printable has no business on the wire.
     */
    fun sanitize(text: String): String =
        text.map { c -> if (c.code in 0x20..0x7E && c != ';') c else ' ' }
            .joinToString("")
            .trim()
            .ifEmpty { " " }

    /** Join commands into wire payloads, each no larger than [maxPayload] bytes. */
    fun frame(commands: List<String>, maxPayload: Int = 200): List<ByteArray> {
        if (commands.isEmpty()) return emptyList()
        val out = ArrayList<ByteArray>()
        val sb = StringBuilder()
        for (cmd in commands) {
            val piece = if (sb.isEmpty()) cmd else "$SEPARATOR$cmd"
            if (sb.isNotEmpty() && sb.length + piece.length + TERMINATOR.length > maxPayload) {
                sb.append(TERMINATOR)
                out.add(sb.toString().encodeToByteArray())
                sb.setLength(0)
                sb.append(cmd)
            } else {
                sb.append(piece)
            }
        }
        if (sb.isNotEmpty()) {
            sb.append(TERMINATOR)
            out.add(sb.toString().encodeToByteArray())
        }
        return out
    }
}

/** Lines the hub program prints back over the console channel. */
sealed interface HubReply {
    data class Ready(val version: String, val inputMode: String) : HubReply
    data class Pong(val sequence: Int) : HubReply
    data object WatchdogFired : HubReply
    data class Error(val text: String) : HubReply
    data class Log(val text: String) : HubReply

    companion object {
        fun parse(line: String): HubReply {
            val t = line.trim()
            return when {
                t.startsWith("!rdy") -> {
                    val parts = t.split(" ")
                    Ready(parts.getOrElse(1) { "?" }, parts.getOrElse(2) { "?" })
                }
                t.startsWith("!pg") -> Pong(t.split(" ").getOrNull(1)?.toIntOrNull() ?: -1)
                t.startsWith("!wd") -> WatchdogFired
                t.startsWith("!er") -> Error(t.removePrefix("!er").trim())
                else -> Log(t)
            }
        }
    }
}
