package dev.primeremote.app.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.util.Log
import dev.primeremote.core.protocol.Cobs
import dev.primeremote.core.protocol.FrameAssembler
import dev.primeremote.core.protocol.HubMessage
import dev.primeremote.core.protocol.HubRequest
import dev.primeremote.core.protocol.Messages
import dev.primeremote.core.protocol.Uuids
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * A GATT connection to one SPIKE Prime hub.
 *
 * Responsibilities kept here on purpose:
 *  - the connect / discover / MTU / subscribe dance, which is where most Android BLE bugs live
 *  - splitting outgoing frames into packets the hub will accept, and sending them one at a
 *    time (the stack silently drops writes issued before the previous one completes)
 *  - reassembling notifications back into whole protocol messages
 *
 * Everything above this — handshakes, uploads, commands — lives in [HubSession].
 */
@SuppressLint("MissingPermission")
class SpikeHubConnection(
    private val context: Context,
    val device: BluetoothDevice,
    private val scope: CoroutineScope,
) {

    enum class State { DISCONNECTED, CONNECTING, DISCOVERING, SUBSCRIBING, READY, FAILED }

    sealed interface Event {
        data class StateChanged(val state: State) : Event
        data class Incoming(val message: HubMessage) : Event
        data class Failure(val reason: String) : Event
    }

    private val _state = MutableStateFlow(State.DISCONNECTED)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _events = MutableSharedFlow<Event>(replay = 0, extraBufferCapacity = 256)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    private var gatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null
    private var txCharacteristic: BluetoothGattCharacteristic? = null

    private val assembler = FrameAssembler()

    /**
     * Outgoing packets. Control traffic is a stream of "latest wins" updates, so if the
     * radio falls behind it is better to drop the oldest packet than to build up a queue
     * of stale motor commands.
     */
    private val outgoing = Channel<ByteArray>(capacity = 96, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private val writeAcks = Channel<Unit>(Channel.CONFLATED)
    private var pumpJob: Job? = null

    /** Largest single write the hub accepts; refined once the hub reports its own limit. */
    @Volatile
    private var packetSize: Int = DEFAULT_PACKET_SIZE

    /** Number of packets dropped because the radio could not keep up. */
    @Volatile
    var droppedPackets: Int = 0
        private set

    fun setHubPacketSize(size: Int) {
        if (size > 0) packetSize = minOf(packetSize, size)
    }

    fun connect() {
        if (gatt != null) return
        emitState(State.CONNECTING)
        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        if (gatt == null) fail("Could not open a connection to the hub")
    }

    fun disconnect() {
        pumpJob?.cancel()
        pumpJob = null
        val g = gatt
        gatt = null
        rxCharacteristic = null
        txCharacteristic = null
        assembler.reset()
        try {
            g?.disconnect()
            g?.close()
        } catch (e: Exception) {
            Log.w(TAG, "closing the connection failed", e)
        }
        emitState(State.DISCONNECTED)
    }

    /** Queue a message. Returns false if the connection is not ready to take traffic. */
    fun send(request: HubRequest): Boolean {
        if (_state.value != State.READY) return false
        val frame = Cobs.pack(request.serialize())
        var offset = 0
        while (offset < frame.size) {
            val end = minOf(offset + packetSize, frame.size)
            val packet = frame.copyOfRange(offset, end)
            val result = outgoing.trySend(packet)
            if (!result.isSuccess) droppedPackets++
            offset = end
        }
        return true
    }

    // ------------------------------------------------------------------- GATT callback

    private val callback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    emitState(State.DISCOVERING)
                    // A short settle time before discovery avoids a well-known race on
                    // several Android BLE stacks.
                    scope.launch {
                        delay(300)
                        if (gatt != null && !g.discoverServices()) {
                            fail("Service discovery could not be started")
                        }
                    }
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (_state.value != State.DISCONNECTED) {
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            fail(describeStatus(status))
                        } else {
                            emitState(State.DISCONNECTED)
                        }
                    }
                    pumpJob?.cancel()
                    pumpJob = null
                    try {
                        g.close()
                    } catch (e: Exception) {
                        Log.w(TAG, "close failed", e)
                    }
                    gatt = null
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("Service discovery failed (status $status)")
                return
            }
            val service = g.getService(UUID.fromString(Uuids.SERVICE))
            if (service == null) {
                fail("This device does not expose the SPIKE Prime service. Is the hub running SPIKE App 3 firmware?")
                return
            }
            rxCharacteristic = service.getCharacteristic(UUID.fromString(Uuids.RX_CHAR))
            txCharacteristic = service.getCharacteristic(UUID.fromString(Uuids.TX_CHAR))
            if (rxCharacteristic == null || txCharacteristic == null) {
                fail("The hub is missing the expected characteristics")
                return
            }
            // A larger MTU means fewer packets per command; failure is not fatal.
            if (!g.requestMtu(REQUESTED_MTU)) subscribe(g)
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS && mtu > 3) {
                packetSize = (mtu - 3).coerceAtLeast(DEFAULT_PACKET_SIZE)
            }
            subscribe(g)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("Could not subscribe to hub notifications (status $status)")
                return
            }
            emitState(State.READY)
            startPump(g)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            writeAcks.trySend(Unit)
        }

        // API 33 and newer deliver the value directly.
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            handleNotification(value)
        }

        @Deprecated("Superseded by the three-argument overload on API 33+")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            val value = characteristic.value ?: return
            handleNotification(value)
        }
    }

    // ----------------------------------------------------------------------- internals

    private fun subscribe(g: BluetoothGatt) {
        val tx = txCharacteristic ?: return fail("No notification characteristic")
        emitState(State.SUBSCRIBING)
        if (!g.setCharacteristicNotification(tx, true)) {
            fail("The system refused to enable notifications")
            return
        }
        val descriptor = tx.getDescriptor(UUID.fromString(Uuids.CCC_DESCRIPTOR))
        if (descriptor == null) {
            // Some stacks work without the descriptor write; carry on rather than give up.
            emitState(State.READY)
            startPump(g)
            return
        }
        val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(descriptor, enable) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                descriptor.value = enable
                g.writeDescriptor(descriptor)
            }
        }
        if (!ok) fail("Could not write the notification descriptor")
    }

    private fun startPump(g: BluetoothGatt) {
        if (pumpJob != null) return
        pumpJob = scope.launch {
            for (packet in outgoing) {
                val rx = rxCharacteristic ?: break
                while (writeAcks.tryReceive().isSuccess) {
                    // drain any ack left over from a previous write
                }
                var sent = writeBytes(g, rx, packet)
                if (!sent) {
                    // The stack was busy. One short retry is worth it; beyond that the
                    // packet is stale anyway and the next update supersedes it.
                    delay(12)
                    sent = writeBytes(g, rx, packet)
                }
                if (!sent) {
                    droppedPackets++
                    continue
                }
                withTimeoutOrNull(WRITE_TIMEOUT_MS) { writeAcks.receive() }
            }
        }
    }

    private fun writeBytes(
        g: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray,
    ): Boolean = try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(
                characteristic,
                value,
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE,
            ) == BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                characteristic.value = value
                g.writeCharacteristic(characteristic)
            }
        }
    } catch (e: Exception) {
        Log.w(TAG, "write failed", e)
        false
    }

    private fun handleNotification(data: ByteArray) {
        for (frame in assembler.feed(data)) {
            val message = try {
                Messages.deserialize(Cobs.unpack(frame))
            } catch (e: Exception) {
                Log.w(TAG, "could not decode a frame of ${frame.size} bytes", e)
                continue
            }
            _events.tryEmit(Event.Incoming(message))
        }
    }

    private fun emitState(state: State) {
        _state.value = state
        _events.tryEmit(Event.StateChanged(state))
    }

    private fun fail(reason: String) {
        _state.value = State.FAILED
        _events.tryEmit(Event.Failure(reason))
        _events.tryEmit(Event.StateChanged(State.FAILED))
    }

    private fun describeStatus(status: Int): String = when (status) {
        8 -> "The hub went out of range or was switched off"
        19 -> "The hub closed the connection"
        22 -> "The connection was dropped by the phone"
        133 -> "The phone could not keep the connection (error 133) — try again, and turn Bluetooth off and on if it persists"
        else -> "The connection was lost (status $status)"
    }

    private companion object {
        const val TAG = "SpikeHubConnection"
        const val REQUESTED_MTU = 247
        const val DEFAULT_PACKET_SIZE = 20
        const val WRITE_TIMEOUT_MS = 1500L
    }
}
