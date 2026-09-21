package dev.primeremote.core

import dev.primeremote.core.model.*
import dev.primeremote.core.protocol.HubCommands
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ProfileJsonTest {

    @Test
    fun `every preset survives a round trip`() {
        for (factory in Presets.all) {
            val profile = factory()
            val text = ProfileJson.encode(profile)
            assertEquals(profile, ProfileJson.decode(text), "round trip of ${profile.name}")
        }
    }

    @Test
    fun `a layout with every action type round trips`() {
        val actions = listOf(
            Action.MotorRun(Port.A, 50),
            Action.AxisMotor(Port.B, -100, DriveMode.POWER),
            Action.SpeedScale(150),
            Action.MotorRunForTime(Port.C, 75, 10_000, StopMode.COAST),
            Action.MotorRunForDegrees(Port.D, 60, 360, StopMode.HOLD),
            Action.MotorToPosition(Port.E, 90, 50, Direction.CLOCKWISE, StopMode.HOLD),
            Action.MotorStop(Port.F, StopMode.SMART_BRAKE),
            Action.MotorResetPosition(Port.A, 10),
            Action.StopAll,
            Action.SetLight(3),
            Action.MatrixText("hello"),
            Action.MatrixImage(7),
            Action.MatrixClear,
            Action.Beep(880, 300, 80),
            Action.ResetYaw(45),
            Action.SwitchPage(1),
            Action.Macro(listOf(MacroStep(Action.Beep(440, 100, 100), 250), MacroStep(Action.StopAll, 0))),
        )
        val control = ControlSpec(
            id = "c1",
            type = ControlType.BUTTON,
            label = "everything",
            bindings = listOf(Binding(Slot.PRESS, actions)),
        )
        val profile = Profile(id = "p", pages = listOf(Page("pg", "Main", listOf(control))))
        val decoded = ProfileJson.decode(ProfileJson.encode(profile))
        assertEquals(profile, decoded)
        assertEquals(actions.size, decoded.pages[0].controls[0].boundActionCount)
    }

    @Test
    fun `decodeAny accepts single layouts and lists`() {
        val one = Presets.tankDrive()
        val many = listOf(Presets.arcadeDrive(), Presets.driveAndArm())
        assertEquals(1, ProfileJson.decodeAny(ProfileJson.encode(one)).size)
        assertEquals(2, ProfileJson.decodeAny(ProfileJson.encodeAll(many)).size)
    }

    @Test
    fun `unknown fields are ignored so older exports still load`() {
        val text = """
            {"id":"p","name":"old","version":1,"pages":[{"id":"pg","name":"Main","controls":[]}],
             "settings":{"hubSlot":5},"somethingFromTheFuture":42}
        """.trimIndent()
        val profile = ProfileJson.decode(text)
        assertEquals("old", profile.name)
        assertEquals(5, profile.settings.hubSlot)
    }

    @Test
    fun `re-identifying an import keeps content but changes ids`() {
        val original = Presets.arcadeDrive()
        val copy = ProfileJson.reId(original)
        assertNotEquals(original.id, copy.id)
        assertNotEquals(original.pages[0].controls[0].id, copy.pages[0].controls[0].id)
        assertEquals(original.pages[0].controls.size, copy.pages[0].controls.size)
        assertEquals(original.pages[0].controls[0].label, copy.pages[0].controls[0].label)
    }

    @Test
    fun `withActions adds, replaces and removes a binding`() {
        var control = ControlSpec(id = "c", type = ControlType.BUTTON)
        control = control.withActions(Slot.PRESS, listOf(Action.StopAll))
        assertEquals(1, control.actionsFor(Slot.PRESS).size)
        control = control.withActions(Slot.PRESS, listOf(Action.StopAll, Action.MatrixClear))
        assertEquals(2, control.actionsFor(Slot.PRESS).size)
        control = control.withActions(Slot.PRESS, emptyList())
        assertTrue(control.bindings.isEmpty())
    }

    @Test
    fun `command text is kept safe for the wire`() {
        assertEquals("hi there", HubCommands.sanitize("hi;there\n"))
        assertEquals("ok", HubCommands.sanitize("  ok\u0003  "))
        assertEquals(" ", HubCommands.sanitize(""))
    }

    @Test
    fun `frames respect the payload limit`() {
        val commands = List(40) { "mv A $it" }
        val frames = HubCommands.frame(commands, maxPayload = 40)
        assertTrue(frames.size > 1)
        for (frame in frames) {
            assertTrue(frame.size <= 40, "frame of ${frame.size} bytes exceeds the limit")
            assertEquals('\n'.code.toByte(), frame.last())
        }
        val rebuilt = frames.joinToString("") { it.decodeToString() }
            .replace("\n", ";").trim(';').split(";")
        assertEquals(commands, rebuilt)
    }
}
