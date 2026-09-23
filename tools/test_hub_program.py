"""
Runs app/src/main/assets/prime_remote_hub.py under CPython against stubbed LEGO modules.

The hub program cannot be exercised without a robot, so this harness supplies fakes for
`motor`, `motor_pair`, `hub`, `runloop`, `time` and `select`, feeds commands in through a
fake stdin, and checks that each command reaches the LEGO API call it is supposed to.

    python3 tools/test_hub_program.py

It also checks that the program and the Kotlin side have not drifted apart: every command
HubCommands.kt can emit must have a handler, and the version constants must match.
"""

import os
import re
import sys
import types

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
PROGRAM = os.path.join(ROOT, "app", "src", "main", "assets", "prime_remote_hub.py")
HUB_COMMANDS_KT = os.path.join(
    ROOT, "core", "src", "main", "kotlin", "dev", "primeremote", "core", "protocol", "HubCommands.kt"
)
HUB_PROGRAM_KT = os.path.join(
    ROOT, "core", "src", "main", "kotlin", "dev", "primeremote", "core", "HubProgram.kt"
)

failures = []
checks = 0


def check(condition, message):
    global checks
    checks += 1
    if not condition:
        failures.append(message)


def check_equal(actual, expected, message):
    check(actual == expected, "%s\n    expected: %r\n    actual:   %r" % (message, expected, actual))


class Done(Exception):
    """Raised by the fake runloop to end an otherwise infinite program."""


class Recorder:
    """Records every call made on it, as (name, args, kwargs)."""

    def __init__(self, name, log, failing=()):
        self._name = name
        self._log = log
        self._failing = set(failing)

    def __getattr__(self, attr):
        if attr.startswith("_"):
            raise AttributeError(attr)

        def call(*args, **kwargs):
            self._log.append(("%s.%s" % (self._name, attr), args, kwargs))
            if attr in self._failing:
                raise RuntimeError("no device on port")

        return call


class FakeStdin:
    def __init__(self, script):
        # script: list of strings to deliver, each fed one character at a time
        self._data = "".join(script)
        self._pos = 0

    def read(self, n=1):
        if self._pos >= len(self._data):
            return ""
        out = self._data[self._pos:self._pos + n]
        self._pos += n
        return out

    @property
    def exhausted(self):
        return self._pos >= len(self._data)


def build_environment(script, poll_available=True, motor_failures=(), poll_lies=False):
    """Install fake LEGO modules and return (namespace_modules, log, clock, stdout)."""
    log = []
    stdout = []
    clock = {"ms": 1000}

    motor = Recorder("motor", log, failing=motor_failures)
    motor_pair = Recorder("motor_pair", log)
    motor_pair.PAIR_1 = "PAIR_1"
    motor_pair.PAIR_2 = "PAIR_2"
    motor_pair.PAIR_3 = "PAIR_3"

    port_module = types.SimpleNamespace(A="pA", B="pB", C="pC", D="pD", E="pE", F="pF")
    light = Recorder("light", log)
    light.POWER = 0
    light.CONNECT = 1
    light_matrix = Recorder("light_matrix", log)
    motion_sensor = Recorder("motion_sensor", log)
    sound = Recorder("sound", log)

    hub = types.ModuleType("hub")
    hub.port = port_module
    hub.light = light
    hub.light_matrix = light_matrix
    hub.motion_sensor = motion_sensor
    hub.sound = sound

    stdin = FakeStdin(script)

    class Sleep:
        def __init__(self, ms):
            self.ms = ms

        def __await__(self):
            yield self.ms

    runloop = types.ModuleType("runloop")
    runloop.sleep_ms = lambda ms: Sleep(ms)

    def run(*coros):
        coro = coros[0]
        idle_rounds = 0
        try:
            while True:
                slept = coro.send(None)
                clock["ms"] += slept if isinstance(slept, int) else 0
                if stdin.exhausted:
                    idle_rounds += 1
                    # let the watchdog have time to notice before giving up
                    if idle_rounds > 200:
                        raise Done()
                elif poll_lies:
                    # Nothing is being consumed, so the loop would otherwise spin here
                    # forever waiting for the fallback that only time can trigger.
                    idle_rounds += 1
                    if idle_rounds > 2000:
                        raise Done()
        except (StopIteration, Done):
            pass

    runloop.run = run

    time_module = types.ModuleType("time")
    time_module.ticks_ms = lambda: clock["ms"]
    time_module.ticks_diff = lambda a, b: a - b
    time_module.sleep_ms = lambda ms: None

    select_module = None
    if poll_available:
        class Poll:
            def __init__(self):
                self._streams = []

            def register(self, stream, flags):
                self._streams.append(stream)

            def poll(self, timeout=0):
                # poll_lies reproduces the MicroPython builds that never report the
                # console as readable even when data is waiting for it.
                if poll_lies:
                    return []
                return [] if stdin.exhausted else [(stdin, 1)]

        select_module = types.ModuleType("select")
        select_module.poll = Poll
        select_module.POLLIN = 1

    return {
        "motor": motor,
        "motor_pair": motor_pair,
        "hub": hub,
        "runloop": runloop,
        "time": time_module,
        "select": select_module,
        "stdin": stdin,
        "log": log,
        "stdout": stdout,
        "clock": clock,
    }


def run_program(script, poll_available=True, motor_failures=(), poll_lies=False):
    env = build_environment(script, poll_available, motor_failures, poll_lies)
    saved_modules = {}
    for name in ("motor", "motor_pair", "hub", "runloop", "time", "select"):
        saved_modules[name] = sys.modules.get(name)
        if env[name] is None:
            sys.modules[name] = None  # make `import select` fail, as on a hub without it
        else:
            sys.modules[name] = env[name]

    saved_stdin, saved_print = sys.stdin, None
    sys.stdin = env["stdin"]

    source = open(PROGRAM).read()
    namespace = {"__name__": "__main__", "print": lambda *a: env["stdout"].append(" ".join(str(x) for x in a))}
    try:
        exec(compile(source, PROGRAM, "exec"), namespace)
    finally:
        sys.stdin = saved_stdin
        for name, module in saved_modules.items():
            if module is None:
                sys.modules.pop(name, None)
            else:
                sys.modules[name] = module
    return env, namespace


def calls(env, name=None):
    if name is None:
        return env["log"]
    return [entry for entry in env["log"] if entry[0] == name]


# --------------------------------------------------------------------------- the tests

def test_announces_itself():
    env, _ = run_program(["ver\n"])
    check(env["stdout"], "the program should print something on startup")
    check_equal(env["stdout"][0], "!rdy 2 poll", "startup announcement")
    ready_lines = [line for line in env["stdout"] if line.startswith("!rdy")]
    check_equal(len(ready_lines), 2, "ver should re-announce: %r" % env["stdout"])
    check(("light.color", (0, 4), {}) in env["log"], "status light should turn azure when ready")


def test_motor_commands():
    env, _ = run_program([
        "mv A 500\n",
        "mv B -1050 200\n",
        "md C 4000\n",
        "ms D 0\n",
        "mt E 10000 833 1\n",
        "mg F 720 1110 2\n",
        "mp A 90 500 2 1\n",
        "mr B 15\n",
    ])
    check_equal(calls(env, "motor.run")[0], ("motor.run", ("pA", 500), {}), "mv")
    check_equal(
        calls(env, "motor.run")[1],
        ("motor.run", ("pB", -1050), {"acceleration": 200}),
        "mv with acceleration",
    )
    check_equal(calls(env, "motor.set_duty_cycle")[0], ("motor.set_duty_cycle", ("pC", 4000), {}), "md")
    check_equal(calls(env, "motor.stop")[0], ("motor.stop", ("pD",), {"stop": 0}), "ms")
    check_equal(
        calls(env, "motor.run_for_time")[0],
        ("motor.run_for_time", ("pE", 10000, 833), {"stop": 1}),
        "mt",
    )
    check_equal(
        calls(env, "motor.run_for_degrees")[0],
        ("motor.run_for_degrees", ("pF", 720, 1110), {"stop": 2}),
        "mg",
    )
    check_equal(
        calls(env, "motor.run_to_absolute_position")[0],
        ("motor.run_to_absolute_position", ("pA", 90, 500), {"direction": 2, "stop": 1}),
        "mp",
    )
    check_equal(
        calls(env, "motor.reset_relative_position")[0],
        ("motor.reset_relative_position", ("pB", 15), {}),
        "mr",
    )


def test_pair_commands():
    env, _ = run_program(["pr 0 A B\n", "pt 0 500 -500\n", "pm 1 50 300\n", "ps 0 1\n"])
    check_equal(calls(env, "motor_pair.pair")[0], ("motor_pair.pair", ("PAIR_1", "pA", "pB"), {}), "pr")
    check_equal(
        calls(env, "motor_pair.move_tank")[0],
        ("motor_pair.move_tank", ("PAIR_1", 500, -500), {}),
        "pt",
    )
    check_equal(calls(env, "motor_pair.move")[0], ("motor_pair.move", ("PAIR_2", 50), {"velocity": 300}), "pm")
    check_equal(calls(env, "motor_pair.stop")[0], ("motor_pair.stop", ("PAIR_1",), {"stop": 1}), "ps")


def test_light_and_sound_commands():
    env, _ = run_program([
        "lc 9\n", "lw hello world\n", "li 3\n", "lp 1 2 100\n", "lx\n",
        "bp 440 250 80\n", "yr 45\n",
    ])
    check(("light.color", (0, 9), {}) in env["log"], "lc")
    check_equal(calls(env, "light_matrix.write")[0], ("light_matrix.write", ("hello world",), {}), "lw keeps spaces")
    check_equal(calls(env, "light_matrix.show_image")[0], ("light_matrix.show_image", (3,), {}), "li")
    check_equal(calls(env, "light_matrix.set_pixel")[0], ("light_matrix.set_pixel", (1, 2, 100), {}), "lp")
    check_equal(calls(env, "light_matrix.clear")[0], ("light_matrix.clear", (), {}), "lx")
    check_equal(calls(env, "sound.beep")[0], ("sound.beep", (440, 250, 80), {}), "bp")
    check_equal(calls(env, "motion_sensor.reset_yaw")[0], ("motion_sensor.reset_yaw", (45,), {}), "yr")


def test_several_commands_on_one_line():
    env, _ = run_program(["mv A 500;mv B -500;lc 6\n"])
    check_equal(len(calls(env, "motor.run")), 2, "both motor commands on the line should run")
    check(("light.color", (0, 6), {}) in env["log"], "third command on the line should run")


def test_ping_round_trip():
    env, _ = run_program(["pg 42\n"])
    check("!pg 42" in env["stdout"], "ping should be answered with its sequence number")


def test_stop_all():
    env, _ = run_program(["pr 0 A B\n", "st\n"])
    stops = calls(env, "motor.stop")
    check_equal(len(stops), 6, "st should stop all six ports")
    check_equal(len(calls(env, "motor_pair.stop")), 1, "st should stop the paired motors too")


def test_unknown_and_malformed_commands_do_not_kill_the_program():
    env, _ = run_program(["frobnicate 1 2\n", "mv Z 500\n", "mv A notanumber\n", "mv A 250\n"])
    errors = [line for line in env["stdout"] if line.startswith("!er")]
    check(len(errors) >= 3, "each bad command should be reported: %r" % errors)
    check(any("unknown frobnicate" in line for line in errors), "unknown command reported")
    check_equal(
        calls(env, "motor.run")[-1],
        ("motor.run", ("pA", 250), {}),
        "a good command after bad ones should still run",
    )


def test_repeated_errors_are_reported_once():
    env, _ = run_program(["bogus\n"] * 5)
    errors = [line for line in env["stdout"] if line.startswith("!er")]
    check_equal(len(errors), 1, "the same error repeated should only be reported once")


def test_missing_motor_does_not_break_stop_all():
    env, _ = run_program(["st\n"], motor_failures=("stop",))
    check(
        not any(line.startswith("!er") for line in env["stdout"]),
        "stopping an empty port should be silently ignored: %r" % env["stdout"],
    )


def test_watchdog_stops_the_robot():
    env, _ = run_program(["wd 200\n", "mv A 1000\n"])
    check("!wd" in env["stdout"], "the watchdog should fire once the commands stop: %r" % env["stdout"])
    stops = calls(env, "motor.stop")
    check(len(stops) >= 6, "the watchdog should stop every port")
    check(("light.color", (0, 9), {}) in env["log"], "the watchdog should turn the light red")


def test_watchdog_can_be_disabled():
    env, _ = run_program(["wd 0\n", "mv A 1000\n"])
    check("!wd" not in env["stdout"], "a disabled watchdog must never fire")
    check_equal(len(calls(env, "motor.stop")), 0, "a disabled watchdog must not stop anything")


def test_watchdog_does_not_fire_while_commands_keep_coming():
    # 60 commands, each separated by roughly one poll interval, well inside a 500 ms window
    env, _ = run_program(["mv A 500\n"] * 60)
    check("!wd" not in env["stdout"], "a steady command stream must not trip the watchdog")


def test_blocking_input_mode():
    env, _ = run_program(["mv A 500\n", "lc 6\n"], poll_available=False)
    check_equal(env["stdout"][0], "!rdy 2 block", "the fallback mode should be announced")
    check_equal(calls(env, "motor.run")[0], ("motor.run", ("pA", 500), {}), "commands work without select")
    check(("light.color", (0, 6), {}) in env["log"], "later commands work without select")


def test_carriage_returns_are_tolerated():
    env, _ = run_program(["mv A 500\r\n"])
    check_equal(len(calls(env, "motor.run")), 1, "a CRLF line ending should still be one command")


def test_first_command_is_announced():
    env, _ = run_program(["mv A 500\n", "mv A 600\n"])
    beacons = [line for line in env["stdout"] if line == "!rx"]
    check_equal(len(beacons), 1, "the hub should say once that it has started receiving")


def test_version_reports_what_it_has_received():
    env, _ = run_program(["mv A 500\n", "bogus\n", "ver\n"])
    stats = [line for line in env["stdout"] if line.startswith("!st")]
    check(stats, "ver should report counters: %r" % env["stdout"])
    check("rx=3" in stats[0], "three lines were received: %r" % stats)
    check("er=1" in stats[0], "one of them was bad: %r" % stats)


def test_falls_back_when_poll_never_reports_data():
    # The program starts in poll mode, hears nothing despite data waiting, and switches.
    env, _ = run_program(["mv A 500\n", "lc 6\n"], poll_lies=True)
    check_equal(env["stdout"][0], "!rdy 2 poll", "it should start out trusting poll")
    check(
        "!rdy 2 block" in env["stdout"],
        "it should re-announce after falling back: %r" % env["stdout"][:4],
    )
    check_equal(
        calls(env, "motor.run")[0],
        ("motor.run", ("pA", 500), {}),
        "the waiting command should run once it falls back",
    )
    check(("light.color", (0, 6), {}) in env["log"], "and so should the one after it")


def test_no_fallback_while_poll_is_working():
    env, _ = run_program(["mv A 500\n"] * 40)
    blocks = [line for line in env["stdout"] if line.endswith("block")]
    check(not blocks, "a working poll must not trigger the fallback: %r" % env["stdout"])


def test_kotlin_and_python_have_not_drifted():
    kotlin = open(HUB_COMMANDS_KT).read()
    program = open(PROGRAM).read()

    # every command string the Kotlin side can emit
    emitted = set(re.findall(r'"([a-z]{2,3})(?: |")', kotlin))
    emitted &= {
        "mv", "md", "ms", "mt", "mg", "mp", "mr",
        "pr", "pt", "pm", "ps",
        "lc", "lw", "li", "lp", "lx",
        "bp", "yr", "wd", "pg", "st", "ver",
    }
    handlers = set(re.findall(r'"([a-z]{2,3})": cmd_', program))
    missing = emitted - handlers
    check(not missing, "commands the app can send but the hub program cannot handle: %s" % sorted(missing))
    check_equal(len(handlers), 22, "handler count")

    py_version = re.search(r'^VERSION = "([^"]+)"', program, re.M).group(1)
    kt_version = re.search(r'const val VERSION = "([^"]+)"', open(HUB_PROGRAM_KT).read()).group(1)
    check_equal(py_version, kt_version, "hub program version in Python and Kotlin must match")


def test_program_is_valid_for_the_hub():
    source = open(PROGRAM).read()
    check(len(source) < 30000, "the program has to be uploaded over BLE, so keep it small")
    check("\t" not in source, "tabs and MicroPython indentation do not mix well")
    for line in source.splitlines():
        check(all(ord(c) < 128 for c in line), "the program must stay plain ASCII: %r" % line)


def main():
    tests = [value for name, value in sorted(globals().items()) if name.startswith("test_")]
    for test in tests:
        before = len(failures)
        try:
            test()
        except Exception as exc:  # a crash is a failure too
            import traceback
            failures.append("%s raised %r\n%s" % (test.__name__, exc, traceback.format_exc()))
        status = "ok  " if len(failures) == before else "FAIL"
        print("%s %s" % (status, test.__name__))

    print("\n%d checks, %d failures" % (checks, len(failures)))
    for failure in failures:
        print("\n- " + failure)
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
