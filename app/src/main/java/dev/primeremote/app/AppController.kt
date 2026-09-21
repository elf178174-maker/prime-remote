package dev.primeremote.app

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.primeremote.app.ble.BleScanner
import dev.primeremote.app.ble.ConsoleLine
import dev.primeremote.app.ble.HubSession
import dev.primeremote.app.ble.Telemetry
import dev.primeremote.app.data.AppPreferences
import dev.primeremote.app.data.ProfileRepository
import dev.primeremote.core.HubProgram
import dev.primeremote.core.engine.ControlEngine
import dev.primeremote.core.model.Port
import dev.primeremote.core.model.Profile
import dev.primeremote.core.model.Slot
import dev.primeremote.core.protocol.DeviceUpdate
import dev.primeremote.core.protocol.InfoResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The one place that knows about the robot, the layouts and the live control loop.
 *
 * It lives for as long as the process does, which is what you want for a remote control:
 * rotating the phone or bouncing between screens must never drop the connection or leave
 * a motor running.
 */
class AppController(private val context: Context) {

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val scanner = BleScanner(context)
    val repository = ProfileRepository(context)
    val preferences = AppPreferences(context)

    private val _session = MutableStateFlow<HubSession?>(null)
    val session: StateFlow<HubSession?> = _session.asStateFlow()

    private val _activeProfile = MutableStateFlow(initialProfile())
    val activeProfile: StateFlow<Profile> = _activeProfile.asStateFlow()

    private val _pageIndex = MutableStateFlow(0)
    val pageIndex: StateFlow<Int> = _pageIndex.asStateFlow()

    private val _status = MutableStateFlow("Not connected")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _connecting = MutableStateFlow(false)
    val connecting: StateFlow<Boolean> = _connecting.asStateFlow()

    private val _commandRateHz = MutableStateFlow(0)
    val commandRateHz: StateFlow<Int> = _commandRateHz.asStateFlow()

    // Mirrors of the current session's state. Keeping them here means the UI always has
    // something to read, whether or not a hub is connected, and never has to make a
    // composable call conditional on there being a session.
    private val _phase = MutableStateFlow<HubSession.Phase>(HubSession.Phase.Idle)
    val phase: StateFlow<HubSession.Phase> = _phase.asStateFlow()

    private val _telemetry = MutableStateFlow(Telemetry())
    val telemetry: StateFlow<Telemetry> = _telemetry.asStateFlow()

    private val _console = MutableStateFlow<List<ConsoleLine>>(emptyList())
    val console: StateFlow<List<ConsoleLine>> = _console.asStateFlow()

    private val _hubInfo = MutableStateFlow<InfoResponse?>(null)
    val hubInfo: StateFlow<InfoResponse?> = _hubInfo.asStateFlow()

    private val _latencyMs = MutableStateFlow<Int?>(null)
    val latencyMs: StateFlow<Int?> = _latencyMs.asStateFlow()

    private val _hubInputMode = MutableStateFlow<String?>(null)
    val hubInputMode: StateFlow<String?> = _hubInputMode.asStateFlow()

    private val _watchdogTripped = MutableStateFlow(false)
    val watchdogTripped: StateFlow<Boolean> = _watchdogTripped.asStateFlow()

    private val _hubName = MutableStateFlow<String?>(null)
    val hubName: StateFlow<String?> = _hubName.asStateFlow()

    private val mirrorJobs = mutableListOf<Job>()

    /** True while the controller screen is the visible one; the send loop only runs then. */
    private var controllerActive = false

    var engine = ControlEngine(_activeProfile.value)
        private set

    private var senderJob: Job? = null
    private var connectJob: Job? = null
    private var commandsSentInWindow = 0
    private var windowStartedAtMs = 0L
    private var reconnectAttempts = 0
    private var userDisconnected = false

    private fun initialProfile(): Profile {
        val all = repository.profiles.value
        val lastId = AppPreferences(context).lastProfileId
        return all.firstOrNull { it.id == lastId } ?: all.first()
    }

    // ------------------------------------------------------------------- layouts

    fun selectProfile(profile: Profile) {
        _activeProfile.value = profile
        preferences.lastProfileId = profile.id
        engine.replaceProfile(profile)
        _pageIndex.value = engine.pageIndex
    }

    /** Save an edited layout and keep the live engine in step with it. */
    fun saveProfile(profile: Profile) {
        repository.upsert(profile)
        if (profile.id == _activeProfile.value.id) {
            _activeProfile.value = profile
            engine.replaceProfile(profile)
            _pageIndex.value = engine.pageIndex
            // Settings such as the watchdog or the drive pair have to be re-applied.
            _session.value?.let { session ->
                if (session.phase.value == HubSession.Phase.Ready) {
                    session.sendCommands(engine.sessionInit())
                }
            }
        }
    }

    fun setPage(index: Int) {
        engine.setPage(index)
        _pageIndex.value = engine.pageIndex
        // Anything the old page was driving drops out of the engine's targets, so the
        // next tick emits the stop commands for it.
        flushIfIdle()
    }

    // --------------------------------------------------------------- connection

    fun connect(device: BluetoothDevice) {
        if (connectJob?.isActive == true) return
        val retrying = reconnectAttempts > 0
        disconnect()
        userDisconnected = false
        if (!retrying) reconnectAttempts = 0
        scanner.stop()
        val session = HubSession(context, device, scope) { readHubProgram() }
        _session.value = session
        startMirrors(session)
        _connecting.value = true
        _status.value = "Connecting…"

        connectJob = scope.launch {
            val known = preferences.programVersionFor(device.address)
            val ok = try {
                session.open(_activeProfile.value.settings, known)
            } catch (e: Exception) {
                Log.e(TAG, "connect failed", e)
                false
            }
            _connecting.value = false
            if (ok) {
                reconnectAttempts = 0
                preferences.lastHubAddress = device.address
                preferences.setProgramVersion(device.address, HubProgram.VERSION)
                _status.value = "Connected to ${session.deviceName}"
                session.sendCommands(engine.sessionInit())
                if (controllerActive) startSenderLoop()
            } else {
                preferences.forgetProgramVersion(device.address)
                _status.value = (session.phase.value as? HubSession.Phase.Failed)?.reason ?: "Could not connect"
            }
        }
    }

    fun reconnectLast(): Boolean {
        val address = preferences.lastHubAddress ?: return false
        val device = scanner.deviceFor(address) ?: return false
        connect(device)
        return true
    }

    fun disconnect() {
        userDisconnected = true
        connectJob?.cancel()
        connectJob = null
        stopSenderLoop()
        stopMirrors()
        _session.value?.let { session ->
            session.sendCommands(engine.panicStop())
            session.closeAndStopProgram(_activeProfile.value.settings.hubSlot)
        }
        _session.value = null
        _connecting.value = false
        _status.value = "Not connected"
        _phase.value = HubSession.Phase.Idle
        _telemetry.value = Telemetry()
        _hubInfo.value = null
        _latencyMs.value = null
        _hubInputMode.value = null
        _watchdogTripped.value = false
        _hubName.value = null
    }

    val isReady: Boolean
        get() = _phase.value == HubSession.Phase.Ready

    private fun readHubProgram(): ByteArray =
        context.assets.open(HubProgram.ASSET_NAME).use { it.readBytes() }

    private fun startMirrors(session: HubSession) {
        stopMirrors()
        _hubName.value = session.deviceName
        mirrorJobs += scope.launch {
            session.telemetry.collect { telemetry ->
                _telemetry.value = telemetry
                // Knowing which motor is on which port lets percentages map onto the real
                // top speed of that motor instead of a guess.
                for ((port, motor) in telemetry.motors) {
                    engine.setMotorType(port, motor.deviceType)
                }
            }
        }
        mirrorJobs += scope.launch {
            session.phase.collect { phase ->
                _phase.value = phase
                if (phase is HubSession.Phase.Failed) onConnectionLost(phase.reason)
            }
        }
        mirrorJobs += scope.launch { session.console.collect { _console.value = it } }
        mirrorJobs += scope.launch { session.hubInfo.collect { _hubInfo.value = it } }
        mirrorJobs += scope.launch { session.latencyMs.collect { _latencyMs.value = it } }
        mirrorJobs += scope.launch { session.hubInputMode.collect { _hubInputMode.value = it } }
        mirrorJobs += scope.launch { session.watchdogTripped.collect { _watchdogTripped.value = it } }
    }

    /**
     * Something went wrong after a successful connection. Stop driving, say so, and offer
     * to get back on: a dropped connection mid-drive is the normal case outdoors, not an
     * error the user should have to go back to the home screen to recover from.
     */
    private fun onConnectionLost(reason: String) {
        stopSenderLoop()
        _status.value = reason
        val address = _session.value?.device?.address ?: return
        if (!preferences.autoReconnect || userDisconnected) return
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            _status.value = "$reason — reconnect from the home screen"
            return
        }
        reconnectAttempts++
        scope.launch {
            delay(RECONNECT_DELAY_MS)
            if (userDisconnected || _phase.value == HubSession.Phase.Ready) return@launch
            val device = scanner.deviceFor(address) ?: return@launch
            _status.value = "Reconnecting (attempt $reconnectAttempts)…"
            connect(device)
        }
    }

    private fun stopMirrors() {
        mirrorJobs.forEach { it.cancel() }
        mirrorJobs.clear()
    }

    /** Clear the log shown on the console screen. */
    fun clearConsole() {
        _session.value?.clearConsole()
        _console.value = emptyList()
    }

    /** Send one command line exactly as typed, for the debug console. */
    fun sendRaw(line: String) {
        _session.value?.sendRaw(line)
    }

    val packetsDropped: Int get() = _session.value?.packetsDropped ?: 0

    // -------------------------------------------------------------- control loop

    fun onControllerVisible() {
        controllerActive = true
        if (isReady) startSenderLoop()
    }

    fun onControllerHidden() {
        controllerActive = false
        stopSenderLoop()
        _session.value?.sendCommands(engine.panicStop())
    }

    fun onAppPaused() {
        if (_activeProfile.value.settings.stopOnPause) {
            _session.value?.sendCommands(engine.panicStop())
        }
        stopSenderLoop()
    }

    fun onAppResumed() {
        if (controllerActive && isReady) startSenderLoop()
    }

    private fun startSenderLoop() {
        if (senderJob?.isActive == true) return
        val period = (1000L / _activeProfile.value.settings.sendRateHz.coerceIn(5, 50)).coerceAtLeast(20L)
        senderJob = scope.launch {
            windowStartedAtMs = SystemClock.elapsedRealtime()
            commandsSentInWindow = 0
            while (isActive) {
                val now = SystemClock.elapsedRealtime()
                val commands = engine.tick(now)
                if (commands.isNotEmpty()) {
                    _session.value?.sendCommands(commands)
                    commandsSentInWindow += commands.size
                }
                engine.consumePageSwitch()?.let { requested ->
                    engine.setPage(requested)
                    _pageIndex.value = engine.pageIndex
                }
                if (now - windowStartedAtMs >= 1000) {
                    _commandRateHz.value = commandsSentInWindow
                    commandsSentInWindow = 0
                    windowStartedAtMs = now
                }
                delay(period)
            }
        }
    }

    private fun stopSenderLoop() {
        senderJob?.cancel()
        senderJob = null
        _commandRateHz.value = 0
    }

    // -------------------------------------------------------------------- input

    fun press(controlId: String, slot: Slot) {
        engine.pressSlot(controlId, slot, SystemClock.elapsedRealtime())
        flushIfIdle()
    }

    fun release(controlId: String, slot: Slot) {
        engine.releaseSlot(controlId, slot, SystemClock.elapsedRealtime())
        flushIfIdle()
    }

    fun toggle(controlId: String, on: Boolean) {
        engine.setToggle(controlId, on, SystemClock.elapsedRealtime())
        flushIfIdle()
    }

    fun axis(controlId: String, slot: Slot, value: Float) {
        engine.setAxis(controlId, slot, value)
    }

    fun panicStop() {
        val commands = engine.panicStop()
        _session.value?.sendCommands(commands)
    }

    /**
     * When the send loop is not running (for instance while previewing a layout in the
     * editor) input would otherwise never reach the hub; send it straight away.
     */
    private fun flushIfIdle() {
        if (senderJob?.isActive == true) return
        val commands = engine.tick(SystemClock.elapsedRealtime())
        if (commands.isNotEmpty()) _session.value?.sendCommands(commands)
    }

    fun motorFor(port: Port): DeviceUpdate.Motor? = _telemetry.value.motors[port]

    private companion object {
        const val TAG = "AppController"
        const val RECONNECT_DELAY_MS = 2_000L
        const val MAX_RECONNECT_ATTEMPTS = 3
    }
}
