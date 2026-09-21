package dev.primeremote.core

import dev.primeremote.core.engine.ControlEngine
import dev.primeremote.core.model.*
import dev.primeremote.core.protocol.MotorType
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ControlEngineTest {

    private fun profileOf(vararg controls: ControlSpec, settings: ProfileSettings = ProfileSettings()) =
        Profile(
            id = "p",
            name = "test",
            pages = listOf(Page("pg", "Main", controls.toList())),
            settings = settings,
        )

    private fun button(id: String, vararg actions: Action, slot: Slot = Slot.PRESS) = ControlSpec(
        id = id,
        type = ControlType.BUTTON,
        bindings = listOf(Binding(slot, actions.toList())),
    )

    private fun stick(id: String, xActions: List<Action> = emptyList(), yActions: List<Action> = emptyList()) =
        ControlSpec(
            id = id,
            type = ControlType.JOYSTICK,
            options = ControlOptions(deadzonePercent = 0, expoPercent = 0),
            bindings = buildList {
                if (xActions.isNotEmpty()) add(Binding(Slot.AXIS_X, xActions))
                if (yActions.isNotEmpty()) add(Binding(Slot.AXIS_Y, yActions))
            },
        )

    @Test
    fun `held button runs a motor and releasing stops it`() {
        val engine = ControlEngine(profileOf(button("b1", Action.MotorRun(Port.C, 50))))
        engine.setMotorType(Port.C, MotorType.LARGE) // 1050 deg/s

        assertEquals(emptyList(), engine.tick(0).filter { it.startsWith("mv") })

        engine.pressSlot("b1", Slot.PRESS, 100)
        val running = engine.tick(100)
        assertContains(running, "mv C 525") // 50% of 1050

        // no change -> nothing re-sent until the periodic refresh
        assertFalse(engine.tick(150).any { it.startsWith("mv C") })

        engine.releaseSlot("b1", Slot.PRESS, 200)
        assertContains(engine.tick(200), "ms C 1") // brake by default
    }

    @Test
    fun `joystick forward drives two motors, one of them reversed`() {
        val engine = ControlEngine(
            profileOf(
                stick(
                    "j",
                    yActions = listOf(Action.AxisMotor(Port.A, 100), Action.AxisMotor(Port.B, -100)),
                )
            )
        )
        engine.setMotorType(Port.A, MotorType.LARGE)
        engine.setMotorType(Port.B, MotorType.LARGE)

        engine.setAxis("j", Slot.AXIS_Y, 1f)
        val cmds = engine.tick(10)
        assertContains(cmds, "mv A 1050")
        assertContains(cmds, "mv B -1050")

        engine.setAxis("j", Slot.AXIS_Y, 0.5f)
        val half = engine.tick(20)
        assertContains(half, "mv A 525")
        assertContains(half, "mv B -525")

        engine.setAxis("j", Slot.AXIS_Y, 0f)
        val stopped = engine.tick(30)
        assertContains(stopped, "ms A 1")
        assertContains(stopped, "ms B 1")
    }

    @Test
    fun `steering and throttle on the same stick add up per motor`() {
        val engine = ControlEngine(
            profileOf(
                stick(
                    "j",
                    xActions = listOf(Action.AxisMotor(Port.A, 50), Action.AxisMotor(Port.B, 50)),
                    yActions = listOf(Action.AxisMotor(Port.A, 100), Action.AxisMotor(Port.B, -100)),
                )
            )
        )
        engine.setMotorType(Port.A, MotorType.LARGE)
        engine.setMotorType(Port.B, MotorType.LARGE)

        engine.setAxis("j", Slot.AXIS_Y, 0.5f)  // A +50, B -50
        engine.setAxis("j", Slot.AXIS_X, 0.5f)  // A +25, B +25
        val cmds = engine.tick(10)
        // A: 75% of 1050 = 787, B: -25% of 1050 = -262
        assertContains(cmds, "mv A 788")
        assertContains(cmds, "mv B -262")
    }

    @Test
    fun `contributions are clamped to full speed`() {
        val engine = ControlEngine(
            profileOf(
                button("b1", Action.MotorRun(Port.A, 80)),
                button("b2", Action.MotorRun(Port.A, 80)),
            )
        )
        engine.setMotorType(Port.A, MotorType.LARGE)
        engine.pressSlot("b1", Slot.PRESS, 0)
        engine.pressSlot("b2", Slot.PRESS, 0)
        assertContains(engine.tick(0), "mv A 1050")
    }

    @Test
    fun `turbo button scales every motor`() {
        val engine = ControlEngine(
            profileOf(
                button("drive", Action.MotorRun(Port.A, 40)),
                button("turbo", Action.SpeedScale(200)),
            )
        )
        engine.setMotorType(Port.A, MotorType.LARGE)
        engine.pressSlot("drive", Slot.PRESS, 0)
        assertContains(engine.tick(0), "mv A 420")

        engine.pressSlot("turbo", Slot.PRESS, 10)
        assertContains(engine.tick(10), "mv A 840")

        engine.releaseSlot("turbo", Slot.PRESS, 20)
        assertContains(engine.tick(20), "mv A 420")
    }

    @Test
    fun `master speed limit applies to everything`() {
        val engine = ControlEngine(
            profileOf(
                button("b", Action.MotorRun(Port.A, 100)),
                settings = ProfileSettings(masterSpeedPercent = 50),
            )
        )
        engine.setMotorType(Port.A, MotorType.LARGE)
        engine.pressSlot("b", Slot.PRESS, 0)
        assertContains(engine.tick(0), "mv A 525")
    }

    @Test
    fun `timed action is sent once and handled by the hub`() {
        val engine = ControlEngine(
            profileOf(button("b", Action.MotorRunForTime(Port.C, speed = 75, ms = 10_000)))
        )
        engine.setMotorType(Port.C, MotorType.MEDIUM) // 1110
        engine.pressSlot("b", Slot.PRESS, 0)
        val cmds = engine.tick(0)
        assertContains(cmds, "mt C 10000 833 1")
        // releasing must not cancel it
        engine.releaseSlot("b", Slot.PRESS, 100)
        assertTrue(engine.tick(100).none { it.startsWith("ms C") || it.startsWith("mv C") })
    }

    @Test
    fun `one-shot actions do not fight continuous ones on other ports`() {
        val engine = ControlEngine(
            profileOf(
                button("drive", Action.MotorRun(Port.A, 50)),
                button("shoot", Action.MotorRunForDegrees(Port.C, 100, 720)),
            )
        )
        engine.setMotorType(Port.A, MotorType.LARGE)
        engine.setMotorType(Port.C, MotorType.MEDIUM)
        engine.pressSlot("drive", Slot.PRESS, 0)
        engine.tick(0)
        engine.pressSlot("shoot", Slot.PRESS, 10)
        val cmds = engine.tick(10)
        assertContains(cmds, "mg C 720 1110 1")
        assertFalse(cmds.any { it.startsWith("mv C") || it.startsWith("ms C") })
    }

    @Test
    fun `toggle switches a motor on and off`() {
        val control = ControlSpec(
            id = "t",
            type = ControlType.TOGGLE,
            bindings = listOf(Binding(Slot.TOGGLE_ON, listOf(Action.MotorRun(Port.D, 100)))),
        )
        val engine = ControlEngine(profileOf(control))
        engine.setMotorType(Port.D, MotorType.LARGE)

        engine.setToggle("t", true, 0)
        assertContains(engine.tick(0), "mv D 1050")
        assertTrue(engine.isToggled("t"))

        engine.setToggle("t", false, 10)
        assertContains(engine.tick(10), "ms D 1")
        assertFalse(engine.isToggled("t"))
    }

    @Test
    fun `dpad directions drive both motors`() {
        val dpad = ControlSpec(
            id = "d",
            type = ControlType.DPAD,
            bindings = listOf(
                Binding(Slot.UP, listOf(Action.MotorRun(Port.A, 70), Action.MotorRun(Port.B, -70))),
                Binding(Slot.RIGHT, listOf(Action.MotorRun(Port.A, 50), Action.MotorRun(Port.B, 50))),
            ),
        )
        val engine = ControlEngine(profileOf(dpad))
        engine.setMotorType(Port.A, MotorType.LARGE)
        engine.setMotorType(Port.B, MotorType.LARGE)

        // up + right at once, as an eight-way pad reports a diagonal
        engine.pressSlot("d", Slot.UP, 0)
        engine.pressSlot("d", Slot.RIGHT, 0)
        val cmds = engine.tick(0)
        assertContains(cmds, "mv A 1050") // 70 + 50 = 120 -> clamped to 100%
        assertContains(cmds, "mv B -210") // -70 + 50 = -20%
    }

    @Test
    fun `drive pair is sent as a synchronized tank command`() {
        val engine = ControlEngine(
            profileOf(
                stick("j", yActions = listOf(Action.AxisMotor(Port.A, 100), Action.AxisMotor(Port.B, -100))),
                settings = ProfileSettings(drivePair = DrivePair(Port.A, Port.B, pairSlot = 0)),
            )
        )
        engine.setMotorType(Port.A, MotorType.LARGE)
        engine.setMotorType(Port.B, MotorType.LARGE)

        assertContains(engine.sessionInit(), "pr 0 A B")

        engine.setAxis("j", Slot.AXIS_Y, 1f)
        val cmds = engine.tick(10)
        assertContains(cmds, "pt 0 1050 -1050")
        assertFalse(cmds.any { it.startsWith("mv A") || it.startsWith("mv B") })

        engine.setAxis("j", Slot.AXIS_Y, 0f)
        assertContains(engine.tick(20), "ps 0 1")
    }

    @Test
    fun `deadzone and expo shape the stick`() {
        val control = ControlSpec(
            id = "j",
            type = ControlType.JOYSTICK,
            options = ControlOptions(deadzonePercent = 20, expoPercent = 0),
            bindings = listOf(Binding(Slot.AXIS_Y, listOf(Action.AxisMotor(Port.A, 100)))),
        )
        val engine = ControlEngine(profileOf(control))
        engine.setMotorType(Port.A, MotorType.LARGE)

        engine.setAxis("j", Slot.AXIS_Y, 0.15f)
        assertEquals(0f, engine.axisValue("j", Slot.AXIS_Y))

        engine.setAxis("j", Slot.AXIS_Y, 0.6f) // (0.6-0.2)/0.8 = 0.5
        assertEquals(0.5f, engine.axisValue("j", Slot.AXIS_Y), absoluteTolerance = 1e-5f)
        assertContains(engine.tick(0), "mv A 525")
    }

    @Test
    fun `panic stop clears everything`() {
        val engine = ControlEngine(profileOf(button("b", Action.MotorRun(Port.A, 100))))
        engine.pressSlot("b", Slot.PRESS, 0)
        engine.tick(0)
        assertEquals(listOf("st"), engine.panicStop())
        assertTrue(engine.tick(10).isEmpty())
    }

    @Test
    fun `stop all action stops the robot`() {
        val engine = ControlEngine(profileOf(button("b", Action.StopAll)))
        engine.pressSlot("b", Slot.PRESS, 0)
        assertContains(engine.tick(0), "st")
    }

    @Test
    fun `values are refreshed periodically so a lost packet self-heals`() {
        val engine = ControlEngine(profileOf(button("b", Action.MotorRun(Port.A, 50))))
        engine.setMotorType(Port.A, MotorType.LARGE)
        engine.pressSlot("b", Slot.PRESS, 0)
        assertContains(engine.tick(0), "mv A 525")
        assertFalse(engine.tick(500).any { it.startsWith("mv A") })
        assertContains(engine.tick(1200), "mv A 525")
    }

    @Test
    fun `macro runs its steps in order with delays`() {
        val macro = Action.Macro(
            listOf(
                MacroStep(Action.SetLight(9), delayMs = 100),
                MacroStep(Action.Beep(440, 100, 100), delayMs = 100),
                MacroStep(Action.MatrixText("go"), delayMs = 0),
            )
        )
        val engine = ControlEngine(profileOf(button("b", macro)))
        engine.pressSlot("b", Slot.PRESS, 0)

        assertContains(engine.tick(0), "lc 9")
        assertTrue(engine.tick(50).isEmpty())
        assertContains(engine.tick(100), "bp 440 100 100")
        val last = engine.tick(200)
        assertContains(last, "lw go")
    }

    @Test
    fun `repeat while held re-fires a one-shot`() {
        val control = ControlSpec(
            id = "b",
            type = ControlType.BUTTON,
            options = ControlOptions(repeatWhileHeld = true, repeatMs = 100),
            bindings = listOf(Binding(Slot.PRESS, listOf(Action.MotorRunForDegrees(Port.C, 50, 90)))),
        )
        val engine = ControlEngine(profileOf(control))
        engine.pressSlot("b", Slot.PRESS, 0)
        assertEquals(1, engine.tick(0).count { it.startsWith("mg C") })
        assertEquals(0, engine.tick(50).count { it.startsWith("mg C") })
        assertEquals(1, engine.tick(100).count { it.startsWith("mg C") })
        engine.releaseSlot("b", Slot.PRESS, 150)
        assertEquals(0, engine.tick(250).count { it.startsWith("mg C") })
    }

    @Test
    fun `slew rate ramps the motor instead of stepping`() {
        val engine = ControlEngine(
            profileOf(
                button("b", Action.MotorRun(Port.A, 100)),
                settings = ProfileSettings(slewRatePercentPerSecond = 100),
            )
        )
        engine.setMotorType(Port.A, MotorType.LARGE)
        engine.tick(0)
        engine.pressSlot("b", Slot.PRESS, 0)
        // 100%/s means 25% after 250 ms
        engine.tick(250)
        assertContains(engine.tick(500), "mv A 525")
        assertContains(engine.tick(1200), "mv A 1050")
    }

    @Test
    fun `switching pages releases the old page's inputs`() {
        val p1 = ControlSpec(
            id = "b1",
            type = ControlType.BUTTON,
            bindings = listOf(Binding(Slot.PRESS, listOf(Action.MotorRun(Port.A, 100)))),
        )
        val p2 = ControlSpec(id = "b2", type = ControlType.BUTTON)
        val profile = Profile(
            id = "p",
            pages = listOf(Page("pg1", "1", listOf(p1)), Page("pg2", "2", listOf(p2))),
        )
        val engine = ControlEngine(profile)
        engine.setMotorType(Port.A, MotorType.LARGE)
        engine.pressSlot("b1", Slot.PRESS, 0)
        assertContains(engine.tick(0), "mv A 1050")

        engine.setPage(1)
        assertContains(engine.tick(10), "ms A 1")
        assertEquals(1, engine.pageIndex)
    }

    @Test
    fun `page switch action is reported to the UI`() {
        val engine = ControlEngine(profileOf(button("b", Action.SwitchPage(1))))
        engine.pressSlot("b", Slot.PRESS, 0)
        engine.tick(0)
        assertEquals(1, engine.consumePageSwitch())
        assertEquals(null, engine.consumePageSwitch())
    }

    @Test
    fun `power mode sends a duty cycle`() {
        val engine = ControlEngine(
            profileOf(button("b", Action.MotorRun(Port.E, 40, DriveMode.POWER)))
        )
        engine.pressSlot("b", Slot.PRESS, 0)
        assertContains(engine.tick(0), "md E 4000")
    }

    @Test
    fun `unknown motor type falls back to the profile default`() {
        val engine = ControlEngine(
            profileOf(
                button("b", Action.MotorRun(Port.F, 50)),
                settings = ProfileSettings(defaultMaxVelocity = 800),
            )
        )
        engine.pressSlot("b", Slot.PRESS, 0)
        assertContains(engine.tick(0), "mv F 400")
    }

    @Test
    fun `session init arms the watchdog`() {
        val engine = ControlEngine(profileOf(settings = ProfileSettings(watchdogMs = 700)))
        assertContains(engine.sessionInit(), "wd 700")
    }
}
