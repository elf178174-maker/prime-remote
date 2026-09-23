# Prime-Remote

An Android app that connects to a LEGO® SPIKE™ Prime hub over Bluetooth Low Energy and
turns your phone into a custom gamepad for it. You lay out buttons, joysticks, D-pads,
switches, sliders and tilt controls on screen, bind each one to whatever you want the
robot to do, and drive.

> Not affiliated with or endorsed by the LEGO Group. LEGO and SPIKE are trademarks of the
> LEGO Group.

---

## What it does

**Build your own controller.** Drag controls onto a landscape canvas, resize them, colour
them, name them, and spread them over several pages you can flip between (or jump to with
a button binding).

**Bind anything to anything.** Every control exposes *slots* — a button has "while
pressed" and "on release", a joystick has a horizontal and a vertical axis, a D-pad has
four directions — and each slot holds a list of actions:

| Action | What it does |
| --- | --- |
| Run motor | Runs a motor at a set percentage for as long as the control is held |
| Motor follows axis | Maps a stick or slider onto a motor, with its own scale and direction |
| Run motor for a time | Starts a motor and lets the **hub** time it, e.g. "spin C for 10 seconds" |
| Turn motor by degrees | An exact amount of rotation |
| Move motor to a position | An absolute angle, with a choice of direction |
| Stop motor / Stop everything | With coast, brake, hold, or either of the smart modes |
| Reset motor position | Calls the current angle zero |
| Speed multiplier | Turbo or creep, applied to everything while held |
| Hub light / Show text / Show image / Clear matrix | The hub's light and 5×5 display |
| Beep | A tone from the hub's speaker |
| Reset yaw | Zeroes the gyro heading |
| Go to page | Switches the controller to another page |
| Sequence | A list of actions with delays between them |

**Motor outputs add up.** Every active binding contributes to a motor, and the
contributions are summed and clamped. That one rule covers everything:

- *"Forward on the joystick drives motors A and B"* — bind the vertical axis to
  `Motor A follows axis 100%` and `Motor B follows axis -100%` (one side is mirrored on a
  real drive base, so it gets a negative amount).
- *Arcade steering* — also bind the horizontal axis to `A +50%` and `B +50%`. Push
  forward and turn at the same time and the sums work out on their own.
- *Tank steering* — two joysticks, one bound to A and one to B.
- *A turbo button* — a `Speed multiplier 200%` on "while pressed" scales whatever else is
  happening.

**It knows your robot.** The hub reports which motor is on which port, so a "75%" command
becomes 787°/s on a large motor and 832°/s on a medium one, rather than a number that
means something different on every port. Battery, motor positions, force, colour and
distance sensors and the gyro all stream back to the telemetry screen.

**Safety is built in.** The hub runs a watchdog: if it stops hearing from the phone (you
walked out of range, the phone rang, the app was backgrounded) it stops every motor by
itself. There is a STOP button on the controller bar at all times, the app stops the robot
when it goes into the background, and it stops the robot on disconnect.

**Other things worth knowing about.**

- Physical Bluetooth gamepads work too — map a control to `BUTTON_A` or `AXIS_X` and it
  responds to both the screen and the gamepad.
- Deadzone, response curve (expo), output limit, ramp rate and a master speed limit, so a
  fast robot can be made gentle without rewriting the layout.
- Optional synchronized drive pair, using the hub's own `motor_pair` support so both
  wheels stay in step.
- Layouts export and import as JSON through the normal Android share sheet.
- A console screen showing everything the hub printed, with a box for sending raw
  commands — the first place to look when something is not behaving.

---

## Getting it on your phone

The repository builds an APK in GitHub Actions on every push.

1. Open the **Actions** tab, pick the newest **Build** run.
2. Download the **prime-remote-apk** artifact and unzip it.
3. Copy `app-debug.apk` to your phone and open it. Android will ask you to allow
   installing from this source.

Downloading a workflow artifact needs a GitHub login, which is awkward on a phone. For a
plain download link instead, run the **Release** workflow once (Actions → Release → Run
workflow); it builds the same APK and attaches it to a GitHub release.

To build it yourself you need the Android SDK (platform 35) and JDK 17:

```bash
./gradlew :app:assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
```

Requirements: Android 8.0 or newer, Bluetooth LE, and a SPIKE Prime hub running **SPIKE
App 3 firmware** (the protocol this uses does not exist on the older SPIKE 2 firmware).

---

## Using it

1. Switch the hub on and press its Bluetooth button until the light flashes.
2. In Prime-Remote, grant the Bluetooth permission, tap **Scan for hubs**, tap your hub.
3. The first connection uploads a small receiver program to the hub (slot 19 by default)
   and starts it. The hub's light turns azure when the program is running.
4. Pick a layout and tap **Drive**.

Reconnecting later skips the upload: the app starts the program that is already in the
slot and only re-uploads if the version differs.

### Two things to know

**It uses a program slot.** Slot 19 by default, configurable per layout. Whatever was in
that slot is replaced. Pick a different slot in the layout settings if you need 19.

**The hub's own app will fight over the connection.** Close the LEGO SPIKE app (and any
browser tab connected to the hub) before connecting.

---

## How it works

There is no protocol message for "run motor A" — LEGO's BLE protocol deals in files,
programs and notifications. So Prime-Remote does what the SPIKE app itself does: it puts a
program on the hub and talks to it.

```
   phone                                    hub
   ┌─────────────┐                    ┌──────────────────┐
   │ layout      │                    │ prime_remote_hub │
   │   ↓         │  TunnelMessage     │   .py  (slot 19) │
   │ control     │ ───────────────▶   │        ↓         │
   │ engine      │  "mv A 500;mv B…"  │  motor.run(...)  │
   │   ↑         │                    │        │         │
   │ telemetry   │ ◀───────────────   │  console + device│
   └─────────────┘  DeviceNotification└──────────────────┘
```

1. **Connect** over GATT service `FD02`, write to `FD02-0001`, subscribe to `FD02-0002`.
2. **Handshake** with an `InfoRequest`, which reports the firmware version and the largest
   packet and chunk the hub will accept.
3. **Upload** `prime_remote_hub.py` — clear the slot, start the upload with a CRC of the
   whole file, then send chunks each carrying a running CRC.
4. **Start** the program and wait for it to print `!rdy`.
5. **Drive**: the control engine works out each motor's target 20 times a second and sends
   only what changed, as an ASCII command line inside a tunnel message. The firmware hands
   each tunnel message to the program through `hub.config["module_tunnel"]`, as a
   callback.
6. **Listen**: sensor and motor state arrive as device notifications, and anything the
   program prints arrives as a console notification.

Messages are framed with COBS (delimiter `0x02`, XOR `0x03`) exactly as LEGO documents.

### The wire protocol

Commands are printable ASCII, several per line separated by `;`, terminated with a
newline. Keeping to printable characters means the traffic is readable in the console
screen, and the program never has to guess at binary framing.

| Command | Meaning |
| --- | --- |
| `mv <port> <deg/s> [<accel>]` | `motor.run` |
| `md <port> <duty>` | `motor.set_duty_cycle`, ±10000 |
| `ms <port> <stop>` | `motor.stop` |
| `mt <port> <ms> <deg/s> <stop>` | `motor.run_for_time` |
| `mg <port> <degrees> <deg/s> <stop>` | `motor.run_for_degrees` |
| `mp <port> <pos> <deg/s> <dir> <stop>` | `motor.run_to_absolute_position` |
| `mr <port> [<pos>]` | `motor.reset_relative_position` |
| `pr <pair> <left> <right>` | `motor_pair.pair` |
| `pt <pair> <left> <right>` | `motor_pair.move_tank` |
| `pm <pair> <steer> <deg/s>` | `motor_pair.move` |
| `ps <pair> <stop>` | `motor_pair.stop` |
| `lc <colour>` | hub status light |
| `lw <text>` / `li <image>` / `lp <x> <y> <i>` / `lx` | light matrix |
| `bp <hz> <ms> <volume>` | beep |
| `yr <angle>` | reset yaw |
| `wd <ms>` | arm the watchdog (0 disables) |
| `pg <n>` | ping, answered with `!pg <n>` |
| `st` | stop everything |
| `ver` | re-announce `!rdy <version> <mode>` |

Stop modes are LEGO's own numbers: 0 coast, 1 brake, 2 hold, 3 continue, 4 smart coast,
5 smart brake. Directions: 0 clockwise, 1 counter-clockwise, 2 shortest, 3 longest.

The hub answers with `!rdy`, `!pg`, `!wd` and `!er` lines, all visible on the console
screen.

---

## Layout of the repository

```
core/     pure Kotlin, no Android: the protocol, the layout model, the control engine
app/      the Android app: BLE, Compose UI, the editor
app/src/main/assets/prime_remote_hub.py    the program that runs on the hub
tools/    test harness for the hub program, and the golden-vector generator
```

`core` has no Android dependencies on purpose, so the interesting logic can be built and
tested anywhere:

```bash
gradle -c settings-core.gradle.kts :core:test   # no Android SDK needed
python3 tools/test_hub_program.py               # runs the hub program under CPython
```

### How this was tested

- **The protocol** is checked byte-for-byte against LEGO's own Python reference
  implementation. `tools/gen_golden_vectors.py` runs their `cobs.py`, `crc.py` and
  `messages.py` to produce vectors, and the Kotlin port is asserted against them. CI
  re-generates the vectors from the upstream repository and fails if they have drifted.
- **The control engine** has unit tests covering the summation rule, deadzone and expo,
  turbo scaling, drive pairing, ramping, macros, repeat-while-held and page switching.
- **The hub program** runs under CPython against stubbed `motor`, `motor_pair`, `hub`,
  `runloop` and `select` modules, so every command is checked to reach the LEGO call it
  should — including the watchdog, malformed input, and the fallback input mode.
- **The app** is compiled in CI.

The hub-program harness delivers commands exactly the way the firmware does — through a
callback on `module_tunnel`, one call per message, with the payload as a `memoryview` —
so it fails the way a real hub fails if the program listens anywhere else. That was the
bug in the first releases: they read commands from standard input, which SPIKE App 3
firmware never feeds, so the hub connected fine and then ignored everything. The fix
follows two independent working SPIKE 3 remote-control projects.

---

## If something does not work

**The hub does not appear when scanning.** It advertises only when it is not already
connected — close the LEGO SPIKE app, and press the hub's Bluetooth button until it
flashes. On Android 11 and older, scanning needs Location permission.

**"error 133" when connecting.** A generic Android BLE failure. Try again; if it keeps
happening, toggle Bluetooth off and on.

**Connected, but nothing moves.** Open the console screen — it tells you exactly how far
the chain got:

| What you see | What it means |
| --- | --- |
| Nothing at all | The program is not running. The hub's light should be azure once it is. |
| `!rdy 3 tunnel`, then `Commands are reaching the hub` | Everything works; the problem is in the bindings (wrong port, speed 0, master speed turned down). |
| `!rdy 3 tunnel`, then `No reply to any ping` | The program is running but not receiving. See below. |
| `!er …` lines | A command is being rejected, and the line names it. |

The fastest check of all: open the console and tap the **Version** quick command. If a
`!rdy …` line and a `!st rx=… ` line appear, the hub is hearing you. If nothing appears,
it is not.

`!rdy` without `!pg` means the program started but nothing the app sends is reaching
it. The program registers a callback on `hub.config["module_tunnel"]`, which is how SPIKE
App 3 firmware delivers tunnel messages to a running program; if the hub cannot provide
that, the console shows `!er tunnel unavailable` and the program announces itself as
`!rdy 3 none`.

**I turned on the synchronized drive pair and now nothing moves.** Pairing needs motors
plugged into both of the ports it names. If they are not there the console shows
`!er pr …` and every drive command after it fails. Either fix the ports in the layout
settings or switch pairing off — driving the motors individually works whatever is
plugged in where.

**The robot keeps moving after I let go.** Check the watchdog is not set to 0 in the
layout settings, and that the binding's stop mode is brake rather than continue.
