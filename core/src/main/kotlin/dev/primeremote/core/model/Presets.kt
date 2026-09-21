package dev.primeremote.core.model

import kotlin.random.Random

/** Ready-made layouts, so the app is useful the moment it is installed. */
object Presets {

    fun newId(prefix: String): String =
        prefix + "-" + Random.nextLong(0x1000000, 0xFFFFFFF).toString(16)

    val all: List<() -> Profile> = listOf(
        ::arcadeDrive,
        ::tankDrive,
        ::driveAndArm,
        ::empty,
    )

    val names: List<String> = listOf(
        "Arcade drive (one stick)",
        "Tank drive (two sticks)",
        "Drive base + arm",
        "Empty layout",
    )

    /**
     * One stick drives: pushing forward runs A and B, pushing sideways steers by adding
     * speed to one side and taking it off the other. Two buttons work a tool motor on C.
     */
    fun arcadeDrive(): Profile {
        val stick = ControlSpec(
            id = newId("c"),
            type = ControlType.JOYSTICK,
            label = "Drive",
            x = 0.04f, y = 0.30f, width = 0.26f, height = 0.62f,
            colorArgb = 0xFF4C6EF5.toInt(),
            shape = ControlShape.CIRCLE,
            options = ControlOptions(deadzonePercent = 12, expoPercent = 25),
            bindings = listOf(
                Binding(
                    Slot.AXIS_Y,
                    listOf(
                        Action.AxisMotor(Port.A, scale = 100),
                        Action.AxisMotor(Port.B, scale = -100),
                    ),
                ),
                Binding(
                    Slot.AXIS_X,
                    listOf(
                        Action.AxisMotor(Port.A, scale = 50),
                        Action.AxisMotor(Port.B, scale = 50),
                    ),
                ),
            ),
        )
        val toolUp = ControlSpec(
            id = newId("c"),
            type = ControlType.BUTTON,
            label = "Arm ▲",
            x = 0.70f, y = 0.32f, width = 0.13f, height = 0.24f,
            colorArgb = 0xFF2ECC71.toInt(),
            bindings = listOf(Binding(Slot.PRESS, listOf(Action.MotorRun(Port.C, 60)))),
        )
        val toolDown = ControlSpec(
            id = newId("c"),
            type = ControlType.BUTTON,
            label = "Arm ▼",
            x = 0.70f, y = 0.62f, width = 0.13f, height = 0.24f,
            colorArgb = 0xFFE67E22.toInt(),
            bindings = listOf(Binding(Slot.PRESS, listOf(Action.MotorRun(Port.C, -60)))),
        )
        val spin = ControlSpec(
            id = newId("c"),
            type = ControlType.BUTTON,
            label = "Spin 10s",
            x = 0.85f, y = 0.32f, width = 0.13f, height = 0.24f,
            colorArgb = 0xFF9B59B6.toInt(),
            bindings = listOf(
                Binding(Slot.PRESS, listOf(Action.MotorRunForTime(Port.C, speed = 75, ms = 10_000))),
            ),
        )
        val horn = ControlSpec(
            id = newId("c"),
            type = ControlType.BUTTON,
            label = "Beep",
            x = 0.85f, y = 0.62f, width = 0.13f, height = 0.24f,
            colorArgb = 0xFF16A085.toInt(),
            bindings = listOf(
                Binding(Slot.PRESS, listOf(Action.Beep(660, 200, 100), Action.SetLight(4))),
            ),
        )
        val stop = ControlSpec(
            id = newId("c"),
            type = ControlType.BUTTON,
            label = "STOP",
            x = 0.42f, y = 0.72f, width = 0.16f, height = 0.20f,
            colorArgb = 0xFFE74C3C.toInt(),
            shape = ControlShape.CIRCLE,
            bindings = listOf(Binding(Slot.PRESS, listOf(Action.StopAll))),
        )
        return Profile(
            id = newId("p"),
            name = "Arcade drive",
            pages = listOf(Page(newId("pg"), "Main", listOf(stick, toolUp, toolDown, spin, horn, stop))),
            // Motors are driven individually rather than as a hardware pair: pairing needs
            // motors actually plugged into both of those ports, and a layout that does
            // nothing at all on a robot wired differently is a bad first impression.
            // Turn it on in the layout settings once the ports are right.
            settings = ProfileSettings(),
        )
    }

    /** Left stick runs motor A, right stick runs motor B — classic tank steering. */
    fun tankDrive(): Profile {
        val left = ControlSpec(
            id = newId("c"),
            type = ControlType.JOYSTICK,
            label = "Left",
            x = 0.04f, y = 0.30f, width = 0.24f, height = 0.60f,
            shape = ControlShape.CIRCLE,
            colorArgb = 0xFF4C6EF5.toInt(),
            options = ControlOptions(deadzonePercent = 12),
            bindings = listOf(Binding(Slot.AXIS_Y, listOf(Action.AxisMotor(Port.A, 100)))),
        )
        val right = ControlSpec(
            id = newId("c"),
            type = ControlType.JOYSTICK,
            label = "Right",
            x = 0.72f, y = 0.30f, width = 0.24f, height = 0.60f,
            shape = ControlShape.CIRCLE,
            colorArgb = 0xFF4C6EF5.toInt(),
            options = ControlOptions(deadzonePercent = 12),
            bindings = listOf(Binding(Slot.AXIS_Y, listOf(Action.AxisMotor(Port.B, -100)))),
        )
        val turbo = ControlSpec(
            id = newId("c"),
            type = ControlType.BUTTON,
            label = "Turbo",
            x = 0.40f, y = 0.34f, width = 0.20f, height = 0.18f,
            colorArgb = 0xFFF1C40F.toInt(),
            bindings = listOf(Binding(Slot.PRESS, listOf(Action.SpeedScale(200)))),
        )
        val stop = ControlSpec(
            id = newId("c"),
            type = ControlType.BUTTON,
            label = "STOP",
            x = 0.40f, y = 0.60f, width = 0.20f, height = 0.22f,
            colorArgb = 0xFFE74C3C.toInt(),
            shape = ControlShape.CIRCLE,
            bindings = listOf(Binding(Slot.PRESS, listOf(Action.StopAll))),
        )
        return Profile(
            id = newId("p"),
            name = "Tank drive",
            pages = listOf(Page(newId("pg"), "Main", listOf(left, right, turbo, stop))),
            settings = ProfileSettings(masterSpeedPercent = 50),
        )
    }

    /** D-pad driving with a slider for an arm and a toggle for a gripper. */
    fun driveAndArm(): Profile {
        val dpad = ControlSpec(
            id = newId("c"),
            type = ControlType.DPAD,
            label = "Drive",
            x = 0.04f, y = 0.32f, width = 0.28f, height = 0.60f,
            colorArgb = 0xFF4C6EF5.toInt(),
            bindings = listOf(
                Binding(Slot.UP, listOf(Action.MotorRun(Port.A, 70), Action.MotorRun(Port.B, -70))),
                Binding(Slot.DOWN, listOf(Action.MotorRun(Port.A, -70), Action.MotorRun(Port.B, 70))),
                Binding(Slot.LEFT, listOf(Action.MotorRun(Port.A, -50), Action.MotorRun(Port.B, -50))),
                Binding(Slot.RIGHT, listOf(Action.MotorRun(Port.A, 50), Action.MotorRun(Port.B, 50))),
            ),
        )
        val arm = ControlSpec(
            id = newId("c"),
            type = ControlType.SLIDER,
            label = "Arm",
            x = 0.62f, y = 0.30f, width = 0.12f, height = 0.62f,
            colorArgb = 0xFF2ECC71.toInt(),
            options = ControlOptions(deadzonePercent = 8, springBack = true),
            bindings = listOf(Binding(Slot.AXIS, listOf(Action.AxisMotor(Port.C, 80)))),
        )
        val grip = ControlSpec(
            id = newId("c"),
            type = ControlType.TOGGLE,
            label = "Grip",
            x = 0.80f, y = 0.32f, width = 0.16f, height = 0.22f,
            colorArgb = 0xFF9B59B6.toInt(),
            bindings = listOf(
                Binding(Slot.TOGGLE_ON, listOf(Action.MotorRunForDegrees(Port.D, 60, 180, StopMode.HOLD))),
                Binding(Slot.TOGGLE_OFF, listOf(Action.MotorRunForDegrees(Port.D, 60, -180, StopMode.COAST))),
            ),
        )
        val stop = ControlSpec(
            id = newId("c"),
            type = ControlType.BUTTON,
            label = "STOP",
            x = 0.80f, y = 0.62f, width = 0.16f, height = 0.24f,
            colorArgb = 0xFFE74C3C.toInt(),
            shape = ControlShape.CIRCLE,
            bindings = listOf(Binding(Slot.PRESS, listOf(Action.StopAll))),
        )
        return Profile(
            id = newId("p"),
            name = "Drive base + arm",
            pages = listOf(Page(newId("pg"), "Main", listOf(dpad, arm, grip, stop))),
        )
    }

    fun empty(): Profile = Profile(
        id = newId("p"),
        name = "Empty layout",
        pages = listOf(Page(newId("pg"), "Main", emptyList())),
    )
}
