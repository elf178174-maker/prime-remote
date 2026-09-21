package dev.primeremote.app.ble

import android.bluetooth.BluetoothDevice
import android.content.Context
import android.os.SystemClock
import android.util.Log
import dev.primeremote.core.HubProgram
import dev.primeremote.core.model.Port
import dev.primeremote.core.model.ProfileSettings
import dev.primeremote.core.protocol.ClearSlotRequest
import dev.primeremote.core.protocol.ConsoleNotification
import dev.primeremote.core.protocol.Crc
import dev.primeremote.core.protocol.DeviceNotification
import dev.primeremote.core.protocol.DeviceNotificationRequest
import dev.primeremote.core.protocol.DeviceUpdate
import dev.primeremote.core.protocol.HubCommands
import dev.primeremote.core.protocol.HubMessage
import dev.primeremote.core.protocol.HubReply
import dev.primeremote.core.protocol.HubRequest
import dev.primeremote.core.protocol.InfoRequest
import dev.primeremote.core.protocol.InfoResponse
import dev.primeremote.core.protocol.MessageId
import dev.primeremote.core.protocol.ProgramFlowNotification
import dev.primeremote.core.protocol.ProgramFlowRequest
import dev.primeremote.core.protocol.StartFileUploadRequest
import dev.primeremote.core.protocol.StatusResponse
import dev.primeremote.core.protocol.TransferChunkRequest
import dev.primeremote.core.protocol.TunnelMessage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/** What the app knows about the robot right now, as reported by the hub itself. */
data class Telemetry(
    val batteryPercent: Int? = null,
    val motors: Map<Port, DeviceUpdate.Motor> = emptyMap(),
    val forces: Map<Port, DeviceUpdate.Force> = emptyMap(),
    val colors: Map<Port, DeviceUpdate.Color> = emptyMap(),
    val distances: Map<Port, DeviceUpdate.Distance> = emptyMap(),
    val imu: DeviceUpdate.Imu? = null,
    val updatedAtMs: Long = 0L,
)

data class ConsoleLine(val timeMs: Long, val text: String, val fromHub: Boolean)

/**
 * Everything above the raw GATT connection: the handshake, putting the receiver program
 * on the hub, streaming commands to it, and collecting what comes back.
 */
class HubSession(
    context: Context,
    val device: BluetoothDevice,
    private val scope: CoroutineScope,
    private val readProgram: () -> ByteArray,
) {

    sealed interface Phase {
        data object Idle : Phase
        data object Connecting : Phase
        data object Handshaking : Phase
        data class Uploading(val sentBytes: Int, val totalBytes: Int) : Phase
        data object StartingProgram : Phase
        data object Ready : Phase
        data class Failed(val reason: String) : Phase
    }

    private val connection = SpikeHubConnection(context, device, scope)

    private val _phase = MutableStateFlow<Phase>(Phase.Idle)
    val phase: StateFlow<Phase> = _phase.asStateFlow()

    private val _telemetry = MutableStateFlow(Telemetry())
    val telemetry: StateFlow<Telemetry> = _telemetry.asStateFlow()

    private val _console = MutableStateFlow<List<ConsoleLine>>(emptyList())
    val console: StateFlow<List<ConsoleLine>> = _console.asStateFlow()

    private val _hubInfo = MutableStateFlow<InfoResponse?>(null)
    val hubInfo: StateFlow<InfoResponse?> = _hubInfo.asStateFlow()

    /** Round-trip time of the last ping, in milliseconds; null until one completes. */
    private val _latencyMs = MutableStateFlow<Int?>(null)
    val latencyMs: StateFlow<Int?> = _latencyMs.asStateFlow()

    /** Input mode the hub program reported: "poll" (watchdog works) or "block". */
    private val _hubInputMode = MutableStateFlow<String?>(null)
    val hubInputMode: StateFlow<String?> = _hubInputMode.asStateFlow()

    val deviceName: String = try {
        device.name ?: device.address
    } catch (e: SecurityException) {
        device.address
    }

    private val pending = HashMap<Int, CompletableDeferred<HubMessage>>()
    private val requestLock = Mutex()
    private var readyDeferred: CompletableDeferred<String>? = null

    private var eventJob: Job? = null
    private var pingJob: Job? = null
    private var pingSequence = 0
    private var pingSentAtMs = 0L
    private var lastSentAtMs = 0L

    /**
     * How long the link may stay silent before a ping is sent.
     *
     * This has to be comfortably shorter than the hub's watchdog: the control engine only
     * sends what has changed, so holding a joystick perfectly still produces no traffic at
     * all, and without a keepalive the hub would decide the phone had gone away and stop
     * the motors mid-drive.
     */
    private var keepAliveMs = 1000L

    /** Set when the hub's own safety watchdog has stopped the motors. */
    private val _watchdogTripped = MutableStateFlow(false)
    val watchdogTripped: StateFlow<Boolean> = _watchdogTripped.asStateFlow()

    val packetsDropped: Int get() = connection.droppedPackets

    // --------------------------------------------------------------------- lifecycle

    /**
     * Connect, make sure the receiver program is running, and get ready for commands.
     *
     * @param knownProgramVersion the version this hub was last given, if any. When it
     * matches, the program already in the slot is simply started instead of uploaded.
     */
    suspend fun open(settings: ProfileSettings, knownProgramVersion: String?): Boolean {
        _phase.value = Phase.Connecting
        log("Connecting to $deviceName")
        startEventLoop()
        connection.connect()

        val connected = withTimeoutOrNull(CONNECT_TIMEOUT_MS) {
            connection.state.first { it == SpikeHubConnection.State.READY || it == SpikeHubConnection.State.FAILED }
        }
        if (connected != SpikeHubConnection.State.READY) {
            if (_phase.value !is Phase.Failed) failWith("Could not connect to the hub")
            return false
        }

        _phase.value = Phase.Handshaking
        val info = request(InfoRequest, MessageId.INFO_RESPONSE, HANDSHAKE_TIMEOUT_MS) as? InfoResponse
        if (info == null) {
            failWith("The hub did not answer the handshake")
            return false
        }
        _hubInfo.value = info
        connection.setHubPacketSize(info.maxPacketSize)
        log("Hub firmware ${info.firmwareVersion}, packet ${info.maxPacketSize} B, chunk ${info.maxChunkSize} B")

        if (settings.telemetryIntervalMs > 0) {
            request(
                DeviceNotificationRequest(settings.telemetryIntervalMs.coerceIn(50, 60000)),
                MessageId.DEVICE_NOTIFICATION_RESPONSE,
                HANDSHAKE_TIMEOUT_MS,
            )
        }

        keepAliveMs = if (settings.watchdogMs > 0) {
            (settings.watchdogMs / 2L).coerceIn(150L, 1000L)
        } else {
            1000L
        }

        val slot = settings.hubSlot.coerceIn(0, 19)
        var running = false
        if (knownProgramVersion == HubProgram.VERSION) {
            log("Starting the program already in slot $slot")
            running = startProgramAndWaitForReady(slot)
        }
        if (!running) {
            if (!uploadProgram(slot, info.maxChunkSize)) return false
            running = startProgramAndWaitForReady(slot)
        }
        if (!running) {
            failWith("The receiver program did not start. Check that slot $slot is free and try again.")
            return false
        }

        _phase.value = Phase.Ready
        startPingLoop()
        return true
    }

    fun close() {
        pingJob?.cancel()
        pingJob = null
        eventJob?.cancel()
        eventJob = null
        try {
            // Best effort: stop the motors on the way out.
            connection.send(TunnelMessage((HubCommands.stopAll() + HubCommands.TERMINATOR).encodeToByteArray()))
        } catch (e: Exception) {
            Log.w(TAG, "could not send the parting stop", e)
        }
        connection.disconnect()
        _phase.value = Phase.Idle
    }

    /** Stop the program on the hub as well as disconnecting. */
    fun closeAndStopProgram(slot: Int) {
        try {
            connection.send(ProgramFlowRequest(stop = true, slot = slot.coerceIn(0, 19)))
        } catch (e: Exception) {
            Log.w(TAG, "could not stop the program", e)
        }
        close()
    }

    // ---------------------------------------------------------------------- commands

    /** Send control commands to the running program. */
    fun sendCommands(commands: List<String>) {
        if (commands.isEmpty() || _phase.value != Phase.Ready) return
        for (payload in HubCommands.frame(commands, maxCommandPayload())) {
            connection.send(TunnelMessage(payload))
        }
        lastSentAtMs = SystemClock.elapsedRealtime()
        if (_watchdogTripped.value) _watchdogTripped.value = false
    }

    /** Send one raw command line, as typed in the debug console. */
    fun sendRaw(line: String) {
        val text = line.trim()
        if (text.isEmpty()) return
        log("> $text", fromHub = false)
        sendCommands(listOf(text))
    }

    private fun maxCommandPayload(): Int {
        // A frame is split across as many packets as it needs, so the limit that matters
        // is the largest message the hub will accept, not the largest single write.
        val maxMessage = _hubInfo.value?.maxMessageSize ?: 64
        // Leave room for the message header, COBS overhead and the frame delimiter.
        return (maxMessage - 16).coerceIn(16, 200)
    }

    // ----------------------------------------------------------------------- upload

    private suspend fun uploadProgram(slot: Int, maxChunkSize: Int): Boolean {
        val program = try {
            readProgram()
        } catch (e: Exception) {
            failWith("Could not read the hub program from the app: ${e.message}")
            return false
        }

        log("Uploading the receiver program (${program.size} bytes) to slot $slot")
        _phase.value = Phase.Uploading(0, program.size)

        // Stop whatever may be running in the slot before overwriting it.
        request(ProgramFlowRequest(stop = true, slot = slot), MessageId.PROGRAM_FLOW_RESPONSE, SHORT_TIMEOUT_MS)
        request(ClearSlotRequest(slot), MessageId.CLEAR_SLOT_RESPONSE, SHORT_TIMEOUT_MS)

        val started = request(
            StartFileUploadRequest(HubProgram.FILE_NAME, slot, Crc.crc32(program)),
            MessageId.START_FILE_UPLOAD_RESPONSE,
            HANDSHAKE_TIMEOUT_MS,
        ) as? StatusResponse
        if (started == null || !started.success) {
            failWith("The hub refused the program upload")
            return false
        }

        val chunkSize = maxChunkSize.coerceIn(16, 1024)
        var runningCrc = 0L
        var offset = 0
        while (offset < program.size) {
            val end = minOf(offset + chunkSize, program.size)
            val chunk = program.copyOfRange(offset, end)
            runningCrc = Crc.crc32(chunk, runningCrc)
            val response = request(
                TransferChunkRequest(runningCrc, chunk),
                MessageId.TRANSFER_CHUNK_RESPONSE,
                HANDSHAKE_TIMEOUT_MS,
            ) as? StatusResponse
            if (response == null || !response.success) {
                failWith("The upload failed at byte $offset of ${program.size}")
                return false
            }
            offset = end
            _phase.value = Phase.Uploading(offset, program.size)
        }
        log("Upload complete")
        return true
    }

    private suspend fun startProgramAndWaitForReady(slot: Int): Boolean {
        _phase.value = Phase.StartingProgram
        val ready = CompletableDeferred<String>()
        readyDeferred = ready
        val response = request(
            ProgramFlowRequest(stop = false, slot = slot),
            MessageId.PROGRAM_FLOW_RESPONSE,
            HANDSHAKE_TIMEOUT_MS,
        ) as? StatusResponse
        if (response == null || !response.success) {
            readyDeferred = null
            return false
        }
        val version = withTimeoutOrNull(HubProgram.READY_TIMEOUT_MS) { ready.await() }
        readyDeferred = null
        if (version == null) return false
        if (version != HubProgram.VERSION) {
            log("Slot $slot holds Prime-Remote $version, this app expects ${HubProgram.VERSION}")
            return false
        }
        return true
    }

    // ------------------------------------------------------------------ event loop

    private fun startEventLoop() {
        if (eventJob != null) return
        eventJob = scope.launch {
            connection.events.collect { event ->
                when (event) {
                    is SpikeHubConnection.Event.Incoming -> onMessage(event.message)
                    is SpikeHubConnection.Event.Failure -> failWith(event.reason)
                    is SpikeHubConnection.Event.StateChanged ->
                        if (event.state == SpikeHubConnection.State.DISCONNECTED &&
                            _phase.value !is Phase.Failed && _phase.value != Phase.Idle
                        ) {
                            failWith("Disconnected from the hub")
                        }
                }
            }
        }
    }

    private fun onMessage(message: HubMessage) {
        pending.remove(message.id)?.complete(message)
        when (message) {
            is DeviceNotification -> applyTelemetry(message)
            is ConsoleNotification -> onConsole(message.text)
            is ProgramFlowNotification ->
                if (message.stop) {
                    log("The program on the hub stopped")
                    if (_phase.value == Phase.Ready) {
                        failWith("The program on the hub stopped running")
                    }
                }
            else -> Unit
        }
    }

    private fun onConsole(text: String) {
        // Every piece is parsed as it arrives, including a fragment with no trailing
        // newline: whether the firmware includes the newline from print() is not something
        // to bet the handshake on, and the cost of being wrong the other way is only that a
        // very long line could appear in the log as two.
        for (raw in text.split('\n')) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            when (val reply = HubReply.parse(line)) {
                is HubReply.Ready -> {
                    _hubInputMode.value = reply.inputMode
                    log("Hub program ready (version ${reply.version}, ${reply.inputMode} input)")
                    readyDeferred?.complete(reply.version)
                }

                is HubReply.Pong -> {
                    if (reply.sequence == pingSequence) {
                        _latencyMs.value = (SystemClock.elapsedRealtime() - pingSentAtMs).toInt()
                    }
                }

                HubReply.WatchdogFired -> {
                    _watchdogTripped.value = true
                    log("Hub watchdog stopped the motors")
                }

                is HubReply.Error -> log("Hub error: ${reply.text}")
                is HubReply.Log -> log(reply.text)
            }
        }
    }

    private fun applyTelemetry(notification: DeviceNotification) {
        var current = _telemetry.value
        val motors = HashMap(current.motors)
        val forces = HashMap(current.forces)
        val colors = HashMap(current.colors)
        val distances = HashMap(current.distances)
        var battery = current.batteryPercent
        var imu = current.imu

        for (update in notification.updates) {
            when (update) {
                is DeviceUpdate.Battery -> battery = update.percent
                is DeviceUpdate.Imu -> imu = update
                is DeviceUpdate.Motor -> Port.fromIndex(update.port)?.let { motors[it] = update }
                is DeviceUpdate.Force -> Port.fromIndex(update.port)?.let { forces[it] = update }
                is DeviceUpdate.Color -> Port.fromIndex(update.port)?.let { colors[it] = update }
                is DeviceUpdate.Distance -> Port.fromIndex(update.port)?.let { distances[it] = update }
                else -> Unit
            }
        }

        current = Telemetry(
            batteryPercent = battery,
            motors = motors,
            forces = forces,
            colors = colors,
            distances = distances,
            imu = imu,
            updatedAtMs = SystemClock.elapsedRealtime(),
        )
        _telemetry.value = current
    }

    private fun startPingLoop() {
        if (pingJob != null) return
        lastSentAtMs = SystemClock.elapsedRealtime()
        pingJob = scope.launch {
            while (true) {
                delay(PING_TICK_MS)
                if (_phase.value != Phase.Ready) continue
                if (SystemClock.elapsedRealtime() - lastSentAtMs < keepAliveMs) continue
                pingSequence = (pingSequence + 1) and 0xFFFF
                pingSentAtMs = SystemClock.elapsedRealtime()
                sendCommands(listOf(HubCommands.ping(pingSequence)))
            }
        }
    }

    // -------------------------------------------------------------------- plumbing

    private suspend fun request(request: HubRequest, responseId: Int, timeoutMs: Long): HubMessage? =
        requestLock.withLock {
            val deferred = CompletableDeferred<HubMessage>()
            pending[responseId] = deferred
            if (!connection.send(request)) {
                pending.remove(responseId)
                return@withLock null
            }
            val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
            pending.remove(responseId)
            result
        }

    private fun failWith(reason: String) {
        if (_phase.value is Phase.Failed) return
        log(reason)
        _phase.value = Phase.Failed(reason)
        pending.values.forEach { it.cancel() }
        pending.clear()
    }

    fun log(text: String, fromHub: Boolean = true) {
        val line = ConsoleLine(SystemClock.elapsedRealtime(), text, fromHub)
        _console.value = (_console.value + line).takeLast(MAX_CONSOLE_LINES)
    }

    fun clearConsole() {
        _console.value = emptyList()
    }

    private companion object {
        const val TAG = "HubSession"
        const val CONNECT_TIMEOUT_MS = 20_000L
        const val HANDSHAKE_TIMEOUT_MS = 5_000L
        const val SHORT_TIMEOUT_MS = 1_500L
        const val PING_TICK_MS = 100L
        const val MAX_CONSOLE_LINES = 400
    }
}
