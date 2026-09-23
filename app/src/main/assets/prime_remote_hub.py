# Prime-Remote hub receiver.
#
# The Android app uploads this program to a slot on the SPIKE Prime hub and starts it.
# It then streams commands to it as BLE tunnel messages, which the firmware delivers to
# this program's standard input. Everything this program prints comes back to the app as
# console notifications.
#
# The command language is documented in
# core/src/main/kotlin/dev/primeremote/core/protocol/HubCommands.kt and exercised by
# tools/test_hub_program.py. Keep VERSION in step with HubProgram.VERSION in the app:
# the app re-uploads this file whenever the two differ.

import sys
import motor
import motor_pair
import runloop
import time

from hub import port, light, light_matrix, motion_sensor, sound

VERSION = "2"

# How long to wait between polls of the input when nothing is arriving.
IDLE_SLEEP_MS = 10
# If select.poll() claims there is never anything to read for this long, stop believing
# it and switch to blocking reads. Some MicroPython builds do not report the console as
# readable through poll() even when data is waiting, and a remote control that cannot
# hear the phone is useless -- losing the watchdog is the lesser problem.
POLL_SILENCE_MS = 3000
# The status light turns this colour once the program is running (4 = azure).
READY_COLOUR = 4
# ...and this one if the watchdog has to stop the robot (9 = red).
ALARM_COLOUR = 9

PORTS = (port.A, port.B, port.C, port.D, port.E, port.F)
PAIRS = (motor_pair.PAIR_1, motor_pair.PAIR_2, motor_pair.PAIR_3)

# Stop modes and directions are passed over the wire as the integers LEGO documents
# (COAST 0, BRAKE 1, HOLD 2, CONTINUE 3, SMART_COAST 4, SMART_BRAKE 5), so this program
# never has to look up a constant by name.
DEFAULT_STOP = 1

_watchdog_ms = 0
_last_command_at = 0
_watchdog_tripped = False
_last_error = ""
_paired = {}
_rx_lines = 0
_errors = 0


def now_ms():
    return time.ticks_ms()


def since(then):
    return time.ticks_diff(now_ms(), then)


def out(text):
    print(text)


def report_error(text):
    # Repeating the same failure 20 times a second would drown the console, so only
    # changes get reported.
    global _last_error, _errors
    _errors += 1
    if text != _last_error:
        _last_error = text
        out("!er " + text)


def get_port(letter):
    index = "ABCDEF".find(letter)
    if index < 0:
        raise ValueError("port " + str(letter))
    return PORTS[index]


def get_pair(index):
    i = int(index)
    if i < 0 or i >= len(PAIRS):
        raise ValueError("pair " + str(index))
    return PAIRS[i]


def stop_everything():
    for p in PORTS:
        try:
            motor.stop(p, stop=DEFAULT_STOP)
        except Exception:
            pass  # nothing plugged into that port
    for index in list(_paired.keys()):
        try:
            motor_pair.stop(PAIRS[index], stop=DEFAULT_STOP)
        except Exception:
            pass


# --------------------------------------------------------------------------- commands


def cmd_mv(a):
    # mv <port> <velocity> [<acceleration>]
    if len(a) > 2:
        motor.run(get_port(a[0]), int(a[1]), acceleration=int(a[2]))
    else:
        motor.run(get_port(a[0]), int(a[1]))


def cmd_md(a):
    # md <port> <duty -10000..10000>
    motor.set_duty_cycle(get_port(a[0]), int(a[1]))


def cmd_ms(a):
    # ms <port> <stop mode>
    motor.stop(get_port(a[0]), stop=int(a[1]) if len(a) > 1 else DEFAULT_STOP)


def cmd_mt(a):
    # mt <port> <milliseconds> <velocity> <stop mode>
    motor.run_for_time(get_port(a[0]), int(a[1]), int(a[2]),
                       stop=int(a[3]) if len(a) > 3 else DEFAULT_STOP)


def cmd_mg(a):
    # mg <port> <degrees> <velocity> <stop mode>
    motor.run_for_degrees(get_port(a[0]), int(a[1]), int(a[2]),
                          stop=int(a[3]) if len(a) > 3 else DEFAULT_STOP)


def cmd_mp(a):
    # mp <port> <position> <velocity> <direction> <stop mode>
    motor.run_to_absolute_position(get_port(a[0]), int(a[1]), int(a[2]),
                                   direction=int(a[3]) if len(a) > 3 else 2,
                                   stop=int(a[4]) if len(a) > 4 else DEFAULT_STOP)


def cmd_mr(a):
    # mr <port> [<position>]
    motor.reset_relative_position(get_port(a[0]), int(a[1]) if len(a) > 1 else 0)


def cmd_pr(a):
    # pr <pair> <left port> <right port>
    index = int(a[0])
    motor_pair.pair(get_pair(index), get_port(a[1]), get_port(a[2]))
    _paired[index] = True


def cmd_pt(a):
    # pt <pair> <left velocity> <right velocity>
    motor_pair.move_tank(get_pair(a[0]), int(a[1]), int(a[2]))


def cmd_pm(a):
    # pm <pair> <steering> <velocity>
    motor_pair.move(get_pair(a[0]), int(a[1]), velocity=int(a[2]))


def cmd_ps(a):
    # ps <pair> <stop mode>
    motor_pair.stop(get_pair(a[0]), stop=int(a[1]) if len(a) > 1 else DEFAULT_STOP)


def cmd_lc(a):
    # lc <colour id>
    light.color(light.POWER, int(a[0]))


def cmd_lw(a):
    # lw <text...>
    light_matrix.write(" ".join(a))


def cmd_li(a):
    # li <image id>
    light_matrix.show_image(int(a[0]))


def cmd_lp(a):
    # lp <x> <y> <intensity>
    light_matrix.set_pixel(int(a[0]), int(a[1]), int(a[2]))


def cmd_lx(a):
    light_matrix.clear()


def cmd_bp(a):
    # bp <frequency> <milliseconds> <volume>
    sound.beep(int(a[0]), int(a[1]), int(a[2]))


def cmd_yr(a):
    # yr <angle>
    motion_sensor.reset_yaw(int(a[0]) if a else 0)


def cmd_wd(a):
    # wd <milliseconds>, 0 disables the watchdog
    global _watchdog_ms, _watchdog_tripped
    _watchdog_ms = int(a[0]) if a else 0
    _watchdog_tripped = False


def cmd_pg(a):
    # pg <sequence> -- round trip probe
    out("!pg " + (a[0] if a else "0"))


def cmd_st(a):
    stop_everything()


def cmd_ver(a):
    announce()
    out("!st rx=" + str(_rx_lines) + " er=" + str(_errors) + " wd=" + str(_watchdog_ms))


HANDLERS = {
    "mv": cmd_mv, "md": cmd_md, "ms": cmd_ms, "mt": cmd_mt, "mg": cmd_mg,
    "mp": cmd_mp, "mr": cmd_mr,
    "pr": cmd_pr, "pt": cmd_pt, "pm": cmd_pm, "ps": cmd_ps,
    "lc": cmd_lc, "lw": cmd_lw, "li": cmd_li, "lp": cmd_lp, "lx": cmd_lx,
    "bp": cmd_bp, "yr": cmd_yr,
    "wd": cmd_wd, "pg": cmd_pg, "st": cmd_st, "ver": cmd_ver,
}


def handle_command(text):
    parts = text.split()
    if not parts:
        return
    handler = HANDLERS.get(parts[0])
    if handler is None:
        report_error("unknown " + parts[0])
        return
    try:
        handler(parts[1:])
    except Exception as exc:
        report_error(parts[0] + " " + repr(exc))


def handle_line(line):
    global _last_command_at, _watchdog_tripped, _rx_lines
    _last_command_at = now_ms()
    _rx_lines += 1
    if _rx_lines == 1:
        # Say so the first time anything arrives: it is the one fact you cannot work out
        # from the phone's side of the link.
        out("!rx")
    if _watchdog_tripped:
        _watchdog_tripped = False
        try:
            light.color(light.POWER, READY_COLOUR)
        except Exception:
            pass
    for command in line.split(";"):
        command = command.strip()
        if command:
            handle_command(command)


def check_watchdog():
    # If the phone goes out of range mid-drive, nothing else will stop the robot.
    global _watchdog_tripped
    if _watchdog_ms <= 0 or _watchdog_tripped:
        return
    if since(_last_command_at) > _watchdog_ms:
        _watchdog_tripped = True
        stop_everything()
        try:
            light.color(light.POWER, ALARM_COLOUR)
        except Exception:
            pass
        out("!wd")


# ------------------------------------------------------------------------------ input

# Reading standard input without blocking needs select.poll(). Where it is unavailable
# the program falls back to blocking line reads, which still work because the app sends
# continuously -- but the watchdog cannot fire while a read is blocked, so the app is
# told which mode is in use.
try:
    import select as _select

    _poll = _select.poll()
    _poll.register(sys.stdin, _select.POLLIN)
    INPUT_MODE = "poll"
except Exception:
    _poll = None
    INPUT_MODE = "block"


def announce():
    out("!rdy " + VERSION + " " + INPUT_MODE)


async def pump_polled():
    global INPUT_MODE
    buffer = ""
    last_input_at = now_ms()
    while True:
        received = False
        while _poll.poll(0):
            char = sys.stdin.read(1)
            if not char:
                break
            received = True
            if char == "\n" or char == "\r":
                if buffer:
                    handle_line(buffer)
                    buffer = ""
            else:
                buffer += char
                if len(buffer) > 512:  # runaway line, drop it
                    buffer = ""
        if received:
            last_input_at = now_ms()
        elif since(last_input_at) > POLL_SILENCE_MS:
            # The app sends a keepalive several times a second, so this much silence
            # means poll() is not telling us the truth about the console. Blocking reads
            # go through the same stream and do work on these builds.
            INPUT_MODE = "block"
            announce()
            await pump_blocking()
            return
        check_watchdog()
        await runloop.sleep_ms(1 if received else IDLE_SLEEP_MS)


async def pump_blocking():
    buffer = ""
    while True:
        char = sys.stdin.read(1)
        if not char:
            # Nothing there (and the read did not block): wait rather than spin.
            check_watchdog()
            await runloop.sleep_ms(IDLE_SLEEP_MS)
            continue
        if char == "\n" or char == "\r":
            if buffer:
                handle_line(buffer)
                buffer = ""
            check_watchdog()
            # Give the awaitables started by mt/mg/mp a chance to make progress.
            await runloop.sleep_ms(1)
        else:
            buffer += char
            if len(buffer) > 512:  # runaway line, drop it
                buffer = ""


async def main():
    global _last_command_at
    _last_command_at = now_ms()
    try:
        light.color(light.POWER, READY_COLOUR)
    except Exception:
        pass
    announce()
    if _poll is not None:
        await pump_polled()
    else:
        await pump_blocking()


runloop.run(main())
