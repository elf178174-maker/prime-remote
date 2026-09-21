package dev.primeremote.core.engine

import dev.primeremote.core.model.Action
import dev.primeremote.core.model.ControlSpec
import dev.primeremote.core.model.ControlType
import dev.primeremote.core.model.DriveMode
import dev.primeremote.core.model.Port
import dev.primeremote.core.model.Profile
import dev.primeremote.core.model.Slot
import dev.primeremote.core.model.StopMode
import dev.primeremote.core.protocol.HubCommands
import dev.primeremote.core.protocol.MotorType
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Turns control input into hub commands.
 *
 * The engine is deliberately free of Android and of any I/O so it can be unit tested:
 * feed it input, call [tick] with a timestamp, get back the exact command strings that
 * should go out over BLE.
 *
 * The central idea is that every motor's output is the **sum of all active contributions**.
 * A joystick's vertical axis can add +80% to motors A and B while its horizontal axis adds
 * +40% to A and -40% to B, and the result is arcade steering without any special case for
 * it. Buttons, D-pads and sliders feed the same summation.
 */
class ControlEngine(profile: Profile) {

    var profile: Profile = profile
        private set

    /** Index of the page whose controls are currently live. */
    var pageIndex: Int = 0
        private set

    /** Set when input arrives that should be flushed without waiting for the next tick. */
    var hasPendingWork: Boolean = false
        private set

    private data class PortOutput(val percent: Float, val mode: DriveMode, val stopOnRelease: StopMode)

    /** slotKey -> active (button-like slots). */
    private val activeSlots = LinkedHashSet<String>()
    /** slotKey -> processed axis value in -1..1. */
    private val axisValues = HashMap<String, Float>()
    /** controlId -> toggle state. */
    private val toggles = HashMap<String, Boolean>()
    /** port -> last motor device type seen in telemetry. */
    private val motorTypes = HashMap<Port, Int>()

    /** Commands produced by one-shot actions, waiting to be flushed. */
    private val pending = ArrayList<String>()

    /** port -> last velocity/duty actually sent. */
    private val lastSent = HashMap<Port, Int>()
    /** port -> smoothed output, used when slew limiting is on. */
    private val smoothed = HashMap<Port, Float>()
    /** port -> when we last sent anything for it, for periodic refresh. */
    private val lastSentAt = HashMap<Port, Long>()
    /** port -> the command family last used for it, so a ramp-down stays consistent. */
    private val lastMode = HashMap<Port, DriveMode>()
    private var lastPairSent: Pair<Int, Int>? = null
    private var lastPairSentAt = 0L

    private var lastTickMs = 0L
    private var hasTicked = false
    private var pageSwitchRequest: Int? = null

    private val runningMacros = ArrayList<MacroRun>()
    private val repeats = HashMap<String, Long>()

    private class MacroRun(val steps: List<dev.primeremote.core.model.MacroStep>) {
        var index = 0
        var nextAtMs = 0L
    }

    // ------------------------------------------------------------------ input

    fun replaceProfile(newProfile: Profile) {
        profile = newProfile
        if (pageIndex >= newProfile.pages.size) pageIndex = 0
        clearInputs()
    }

    fun setPage(index: Int) {
        if (index == pageIndex) return
        clearInputs()
        pageIndex = index.coerceIn(0, profile.pages.lastIndex.coerceAtLeast(0))
    }

    /** Remember a motor's type so percentages can be scaled to that motor's real top speed. */
    fun setMotorType(port: Port, deviceType: Int) {
        motorTypes[port] = deviceType
    }

    fun maxVelocityFor(port: Port): Int =
        motorTypes[port]?.let { MotorType.maxVelocity(it) } ?: profile.settings.defaultMaxVelocity

    fun pressSlot(controlId: String, slot: Slot, nowMs: Long) {
        val control = findControl(controlId) ?: return
        val key = key(controlId, slot)
        if (!activeSlots.add(key)) return
        fireOneShots(control.actionsFor(slot), nowMs)
        if (control.options.repeatWhileHeld) repeats[key] = nowMs + control.options.repeatMs
        hasPendingWork = true
    }

    fun releaseSlot(controlId: String, slot: Slot, nowMs: Long) {
        val control = findControl(controlId) ?: return
        val key = key(controlId, slot)
        if (!activeSlots.remove(key)) return
        repeats.remove(key)
        if (slot == Slot.PRESS) fireOneShots(control.actionsFor(Slot.RELEASE), nowMs)
        hasPendingWork = true
    }

    fun setToggle(controlId: String, on: Boolean, nowMs: Long) {
        val control = findControl(controlId) ?: return
        if (toggles[controlId] == on) return
        toggles[controlId] = on
        val onKey = key(controlId, Slot.TOGGLE_ON)
        val offKey = key(controlId, Slot.TOGGLE_OFF)
        if (on) {
            activeSlots.add(onKey)
            activeSlots.remove(offKey)
            fireOneShots(control.actionsFor(Slot.TOGGLE_ON), nowMs)
        } else {
            activeSlots.remove(onKey)
            activeSlots.add(offKey)
            fireOneShots(control.actionsFor(Slot.TOGGLE_OFF), nowMs)
        }
        hasPendingWork = true
    }

    fun isToggled(controlId: String): Boolean = toggles[controlId] == true

    /** @param raw axis position in -1..1, straight from the UI (no shaping applied yet). */
    fun setAxis(controlId: String, slot: Slot, raw: Float) {
        val control = findControl(controlId) ?: return
        val shaped = shapeAxis(raw, control)
        val key = key(controlId, slot)
        if (axisValues[key] == shaped) return
        if (shaped == 0f) axisValues.remove(key) else axisValues[key] = shaped
        hasPendingWork = true
    }

    fun axisValue(controlId: String, slot: Slot): Float = axisValues[key(controlId, slot)] ?: 0f

    /** Drop all input without emitting anything — used when switching pages or profiles. */
    private fun clearInputs() {
        activeSlots.clear()
        axisValues.clear()
        toggles.clear()
        repeats.clear()
        runningMacros.clear()
    }

    /** Release every input and stop every motor. */
    fun panicStop(): List<String> {
        clearInputs()
        pending.clear()
        lastSent.clear()
        smoothed.clear()
        lastSentAt.clear()
        lastMode.clear()
        lastPairSent = null
        hasPendingWork = false
        return listOf(HubCommands.stopAll())
    }

    // ------------------------------------------------------------------ output

    /** Commands to send once, right after the hub program reports itself ready. */
    fun sessionInit(): List<String> {
        val cmds = ArrayList<String>()
        cmds.add(HubCommands.watchdog(profile.settings.watchdogMs))
        profile.settings.drivePair?.let { cmds.add(HubCommands.pair(it.pairSlot, it.left, it.right)) }
        lastSent.clear()
        smoothed.clear()
        lastSentAt.clear()
        lastMode.clear()
        lastPairSent = null
        return cmds
    }

    /**
     * Produce the commands for this moment in time.
     *
     * @param nowMs monotonic milliseconds; the engine only ever compares these to each other.
     */
    fun tick(nowMs: Long): List<String> {
        val out = ArrayList<String>()

        advanceMacros(nowMs, out)
        advanceRepeats(nowMs)

        out.addAll(pending)
        pending.clear()

        val targets = computeTargets()
        val dtMs = if (!hasTicked) 0L else (nowMs - lastTickMs).coerceIn(0, 1000)
        lastTickMs = nowMs
        hasTicked = true

        val pairCommands = emitDrivePair(targets, nowMs, dtMs)
        out.addAll(pairCommands.commands)

        for (port in Port.entries) {
            if (port in pairCommands.handledPorts) continue
            val target = targets[port]
            val output = applySlew(port, target?.percent ?: 0f, dtMs)
            val mode = target?.mode ?: lastMode[port] ?: DriveMode.VELOCITY
            if (target != null) lastMode[port] = mode

            if (target == null && !lastSent.containsKey(port)) continue

            if (target == null && abs(output) < 0.5f) {
                // The port just became idle: stop it the way the binding asked for.
                val stopMode = releaseModes[port] ?: StopMode.BRAKE
                out.add(HubCommands.stop(port, stopMode))
                lastSent.remove(port)
                smoothed.remove(port)
                lastSentAt.remove(port)
                lastMode.remove(port)
                continue
            }

            val value = when (mode) {
                DriveMode.VELOCITY -> percentToVelocity(port, output)
                DriveMode.POWER -> (output * 100f).roundToInt().coerceIn(-10000, 10000)
            }
            val previous = lastSent[port]
            val elapsed = nowMs - (lastSentAt[port] ?: 0L)
            val changedEnough = previous == null ||
                abs(value - previous) >= changeThreshold(mode) ||
                (value == 0) != (previous == 0)
            if (changedEnough || elapsed >= REFRESH_MS) {
                out.add(
                    when (mode) {
                        DriveMode.VELOCITY -> HubCommands.run(port, value)
                        DriveMode.POWER -> HubCommands.duty(port, value)
                    }
                )
                lastSent[port] = value
                lastSentAt[port] = nowMs
            }
        }

        hasPendingWork = false
        return out
    }

    /** A page change requested by a [Action.SwitchPage]; consumed by the UI layer. */
    fun consumePageSwitch(): Int? {
        val request = pageSwitchRequest
        pageSwitchRequest = null
        return request
    }

    // ------------------------------------------------------------------ internals

    private val releaseModes = HashMap<Port, StopMode>()

    private fun changeThreshold(mode: DriveMode) = if (mode == DriveMode.VELOCITY) 8 else 150

    private fun percentToVelocity(port: Port, percent: Float): Int {
        val max = maxVelocityFor(port)
        val scaled = percent / 100f * max
        return scaled.roundToInt().coerceIn(-max, max)
    }

    private class Target(var percent: Float, var mode: DriveMode)

    private fun computeTargets(): Map<Port, PortOutput> {
        val page = profile.pages.getOrNull(pageIndex) ?: return emptyMap()
        val raw = HashMap<Port, Target>()
        var scale = profile.settings.masterSpeedPercent / 100f

        // Speed scaling has to be resolved before motor contributions are totalled.
        forEachActiveAction(page) { action, _ ->
            if (action is Action.SpeedScale) scale *= action.percent / 100f
        }

        forEachActiveAction(page) { action, magnitude ->
            when (action) {
                is Action.MotorRun -> {
                    val t = raw.getOrPut(action.port) { Target(0f, action.mode) }
                    t.percent += action.speed.toFloat() * magnitude
                    t.mode = action.mode
                    releaseModes[action.port] = action.stopOnRelease
                }

                is Action.AxisMotor -> {
                    val t = raw.getOrPut(action.port) { Target(0f, action.mode) }
                    t.percent += action.scale.toFloat() * magnitude
                    t.mode = action.mode
                    releaseModes[action.port] = action.stopOnRelease
                }

                else -> Unit
            }
        }

        val out = HashMap<Port, PortOutput>()
        for ((port, target) in raw) {
            val value = (target.percent * scale).coerceIn(-100f, 100f)
            if (abs(value) < 0.5f) continue
            out[port] = PortOutput(value, target.mode, releaseModes[port] ?: StopMode.BRAKE)
        }
        return out
    }

    /**
     * Walks every action that is currently in effect.
     *
     * @param block receives the action and its magnitude: 1.0 for a held button, or the
     * shaped axis position (-1..1) for an analog slot.
     */
    private inline fun forEachActiveAction(
        page: dev.primeremote.core.model.Page,
        block: (Action, Float) -> Unit,
    ) {
        for (control in page.controls) {
            for (binding in control.bindings) {
                val k = key(control.id, binding.slot)
                if (binding.slot.isAxis) {
                    val v = axisValues[k] ?: continue
                    if (v == 0f) continue
                    for (action in binding.actions) if (action.isContinuous) block(action, v)
                } else {
                    if (k !in activeSlots) continue
                    for (action in binding.actions) if (action.isContinuous) block(action, 1f)
                }
            }
        }
    }

    private class PairResult(val commands: List<String>, val handledPorts: Set<Port>)

    private fun emitDrivePair(targets: Map<Port, PortOutput>, nowMs: Long, dtMs: Long): PairResult {
        val pair = profile.settings.drivePair ?: return PairResult(emptyList(), emptySet())
        val left = targets[pair.left]
        val right = targets[pair.right]
        // Fall back to individual motors if either side is being driven as raw power.
        if (left?.mode == DriveMode.POWER || right?.mode == DriveMode.POWER) {
            return PairResult(emptyList(), emptySet())
        }
        val handled = setOf(pair.left, pair.right)

        val leftOut = applySlew(pair.left, left?.percent ?: 0f, dtMs)
        val rightOut = applySlew(pair.right, right?.percent ?: 0f, dtMs)
        val leftVel = percentToVelocity(pair.left, leftOut)
        val rightVel = percentToVelocity(pair.right, rightOut)

        val idle = left == null && right == null && abs(leftOut) < 0.5f && abs(rightOut) < 0.5f
        if (idle) {
            if (lastPairSent != null) {
                lastPairSent = null
                lastSent.remove(pair.left)
                lastSent.remove(pair.right)
                val stopMode = releaseModes[pair.left] ?: releaseModes[pair.right] ?: StopMode.BRAKE
                return PairResult(listOf(HubCommands.pairStop(pair.pairSlot, stopMode)), handled)
            }
            return PairResult(emptyList(), handled)
        }

        val previous = lastPairSent
        val changed = previous == null ||
            abs(leftVel - previous.first) >= changeThreshold(DriveMode.VELOCITY) ||
            abs(rightVel - previous.second) >= changeThreshold(DriveMode.VELOCITY) ||
            (leftVel == 0) != (previous.first == 0) ||
            (rightVel == 0) != (previous.second == 0)
        if (changed || nowMs - lastPairSentAt >= REFRESH_MS) {
            lastPairSent = leftVel to rightVel
            lastPairSentAt = nowMs
            lastSent[pair.left] = leftVel
            lastSent[pair.right] = rightVel
            return PairResult(listOf(HubCommands.tank(pair.pairSlot, leftVel, rightVel)), handled)
        }
        return PairResult(emptyList(), handled)
    }

    private fun applySlew(port: Port, target: Float, dtMs: Long): Float {
        val rate = profile.settings.slewRatePercentPerSecond
        if (rate <= 0 || dtMs <= 0) {
            smoothed[port] = target
            return target
        }
        val current = smoothed[port] ?: 0f
        val maxStep = rate * dtMs / 1000f
        val delta = target - current
        val next = if (abs(delta) <= maxStep) target else current + sign(delta) * maxStep
        smoothed[port] = next
        return next
    }

    private fun fireOneShots(actions: List<Action>, nowMs: Long) {
        for (action in actions) {
            if (action.isContinuous) continue
            when (action) {
                is Action.MotorRunForTime -> pending.add(
                    HubCommands.runForTime(
                        action.port,
                        action.ms,
                        percentToVelocity(action.port, action.speed.toFloat() * master()),
                        action.stop,
                    )
                )

                is Action.MotorRunForDegrees -> pending.add(
                    HubCommands.runForDegrees(
                        action.port,
                        action.degrees,
                        percentToVelocity(action.port, abs(action.speed.toFloat()) * master()),
                        action.stop,
                    )
                )

                is Action.MotorToPosition -> pending.add(
                    HubCommands.runToPosition(
                        action.port,
                        action.position,
                        percentToVelocity(action.port, abs(action.speed.toFloat()) * master()),
                        action.direction,
                        action.stop,
                    )
                )

                is Action.MotorStop -> {
                    pending.add(HubCommands.stop(action.port, action.stop))
                    lastSent.remove(action.port)
                    smoothed.remove(action.port)
                }

                is Action.MotorResetPosition -> pending.add(
                    HubCommands.resetPosition(action.port, action.position)
                )

                is Action.StopAll -> {
                    pending.add(HubCommands.stopAll())
                    lastSent.clear()
                    smoothed.clear()
                    lastPairSent = null
                }

                is Action.SetLight -> pending.add(HubCommands.light(action.colorId))
                is Action.MatrixText -> pending.add(HubCommands.matrixText(action.text))
                is Action.MatrixImage -> pending.add(HubCommands.matrixImage(action.imageId))
                is Action.MatrixClear -> pending.add(HubCommands.matrixClear())
                is Action.Beep -> pending.add(HubCommands.beep(action.freqHz, action.ms, action.volume))
                is Action.ResetYaw -> pending.add(HubCommands.resetYaw(action.angle))
                is Action.SwitchPage -> pageSwitchRequest = action.pageIndex
                is Action.Macro -> if (action.steps.isNotEmpty()) {
                    runningMacros.add(MacroRun(action.steps).also { it.nextAtMs = nowMs })
                }

                else -> Unit
            }
        }
        if (pending.isNotEmpty()) hasPendingWork = true
    }

    private fun master() = profile.settings.masterSpeedPercent / 100f

    private fun advanceMacros(nowMs: Long, out: MutableList<String>) {
        if (runningMacros.isEmpty()) return
        val finished = ArrayList<MacroRun>()
        for (macro in runningMacros) {
            while (macro.index < macro.steps.size && nowMs >= macro.nextAtMs) {
                val step = macro.steps[macro.index]
                fireOneShots(listOf(step.action), nowMs)
                macro.index++
                macro.nextAtMs = nowMs + step.delayMs.coerceAtLeast(0)
                if (step.delayMs > 0) break
            }
            if (macro.index >= macro.steps.size && nowMs >= macro.nextAtMs) finished.add(macro)
        }
        runningMacros.removeAll(finished)
        out.addAll(pending)
        pending.clear()
    }

    private fun advanceRepeats(nowMs: Long) {
        if (repeats.isEmpty()) return
        val page = profile.pages.getOrNull(pageIndex) ?: return
        for ((k, dueAt) in repeats.toList()) {
            if (nowMs < dueAt) continue
            val (controlId, slotName) = k.split('#', limit = 2).let { it[0] to it[1] }
            val control = page.controls.firstOrNull { it.id == controlId } ?: continue
            val slot = Slot.entries.firstOrNull { it.name == slotName } ?: continue
            fireOneShots(control.actionsFor(slot), nowMs)
            repeats[k] = nowMs + control.options.repeatMs.coerceAtLeast(50)
        }
    }

    private fun findControl(controlId: String): ControlSpec? =
        profile.pages.getOrNull(pageIndex)?.controls?.firstOrNull { it.id == controlId }

    private fun shapeAxis(raw: Float, control: ControlSpec): Float {
        val options = control.options
        var v = raw.coerceIn(-1f, 1f)
        val dz = options.deadzonePercent / 100f
        if (dz > 0f) {
            if (abs(v) <= dz) return 0f
            v = sign(v) * ((abs(v) - dz) / (1f - dz))
        }
        val e = (options.expoPercent / 100f).coerceIn(0f, 1f)
        if (e > 0f) v = (1f - e) * v + e * v * v * v
        val limit = options.maxOutputPercent / 100f
        v *= limit
        if (control.type == ControlType.SLIDER && options.unipolar) v = (v + 1f) / 2f * limit
        return v.coerceIn(-1f, 1f)
    }

    private fun key(controlId: String, slot: Slot) = "$controlId#${slot.name}"

    private companion object {
        /** Re-send a port's value at least this often, so a dropped packet self-heals. */
        const val REFRESH_MS = 1000L
    }
}
