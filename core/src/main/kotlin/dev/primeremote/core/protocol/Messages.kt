package dev.primeremote.core.protocol

/**
 * SPIKE Prime BLE message serialization / deserialization.
 *
 * Message layouts follow LEGO's published protocol reference
 * (https://lego.github.io/spike-prime-docs/messages.html). All fields are little-endian
 * and strings are null-terminated.
 */
object Uuids {
    const val SERVICE = "0000fd02-0000-1000-8000-00805f9b34fb"
    /** The characteristic the hub *receives* on, i.e. the one the app writes to. */
    const val RX_CHAR = "0000fd02-0001-1000-8000-00805f9b34fb"
    /** The characteristic the hub *transmits* on, i.e. the one the app subscribes to. */
    const val TX_CHAR = "0000fd02-0002-1000-8000-00805f9b34fb"
    const val CCC_DESCRIPTOR = "00002902-0000-1000-8000-00805f9b34fb"
}

object MessageId {
    const val INFO_REQUEST = 0x00
    const val INFO_RESPONSE = 0x01
    const val START_FILE_UPLOAD_REQUEST = 0x0C
    const val START_FILE_UPLOAD_RESPONSE = 0x0D
    const val TRANSFER_CHUNK_REQUEST = 0x10
    const val TRANSFER_CHUNK_RESPONSE = 0x11
    const val SET_HUB_NAME_REQUEST = 0x16
    const val SET_HUB_NAME_RESPONSE = 0x17
    const val GET_HUB_NAME_REQUEST = 0x18
    const val GET_HUB_NAME_RESPONSE = 0x19
    const val DEVICE_UUID_REQUEST = 0x1A
    const val DEVICE_UUID_RESPONSE = 0x1B
    const val PROGRAM_FLOW_REQUEST = 0x1E
    const val PROGRAM_FLOW_RESPONSE = 0x1F
    const val PROGRAM_FLOW_NOTIFICATION = 0x20
    const val CONSOLE_NOTIFICATION = 0x21
    const val DEVICE_NOTIFICATION_REQUEST = 0x28
    const val DEVICE_NOTIFICATION_RESPONSE = 0x29
    const val TUNNEL_MESSAGE = 0x32
    const val DEVICE_NOTIFICATION = 0x3C
    const val CLEAR_SLOT_REQUEST = 0x46
    const val CLEAR_SLOT_RESPONSE = 0x47
}

/** Little-endian byte writer. */
internal class LeWriter(capacity: Int = 64) {
    private var buf = ByteArray(capacity)
    private var size = 0

    private fun ensure(extra: Int) {
        if (size + extra > buf.size) {
            buf = buf.copyOf(maxOf(buf.size * 2, size + extra))
        }
    }

    fun u8(v: Int) = apply { ensure(1); buf[size++] = (v and 0xFF).toByte() }

    fun u16(v: Int) = apply {
        ensure(2)
        buf[size++] = (v and 0xFF).toByte()
        buf[size++] = ((v ushr 8) and 0xFF).toByte()
    }

    fun u32(v: Long) = apply {
        ensure(4)
        buf[size++] = (v and 0xFF).toByte()
        buf[size++] = ((v ushr 8) and 0xFF).toByte()
        buf[size++] = ((v ushr 16) and 0xFF).toByte()
        buf[size++] = ((v ushr 24) and 0xFF).toByte()
    }

    fun bytes(v: ByteArray) = apply { ensure(v.size); v.copyInto(buf, size); size += v.size }

    /** Null-terminated string, padded (or truncated) to exactly [length] bytes including the terminator. */
    fun fixedString(s: String, length: Int) = apply {
        val encoded = s.encodeToByteArray()
        require(encoded.size < length) { "String too long: ${encoded.size} + 1 > $length" }
        ensure(length)
        encoded.copyInto(buf, size)
        for (i in encoded.size until length) buf[size + i] = 0
        size += length
    }

    /** Null-terminated string of exactly the needed length (no padding). */
    fun cString(s: String) = apply {
        val encoded = s.encodeToByteArray()
        bytes(encoded)
        u8(0)
    }

    fun build(): ByteArray = buf.copyOf(size)
}

/** Little-endian byte reader. */
internal class LeReader(private val data: ByteArray, private var pos: Int = 0) {
    val remaining: Int get() = data.size - pos
    fun u8(): Int = data[pos++].toInt() and 0xFF
    fun i8(): Int = data[pos++].toInt()
    fun u16(): Int = u8() or (u8() shl 8)
    fun i16(): Int = u16().toShort().toInt()
    fun i32(): Int = u16() or (u16() shl 16)
    fun u32(): Long = (i32().toLong()) and 0xFFFFFFFFL
    fun bytes(n: Int): ByteArray {
        val out = data.copyOfRange(pos, pos + n)
        pos += n
        return out
    }
    fun rest(): ByteArray = bytes(remaining)
    fun skip(n: Int) { pos += n }
}

/** A message sent from the app to the hub. */
sealed interface HubRequest {
    val id: Int
    fun serialize(): ByteArray
}

data object InfoRequest : HubRequest {
    override val id = MessageId.INFO_REQUEST
    override fun serialize() = byteArrayOf(0)
}

data class ClearSlotRequest(val slot: Int) : HubRequest {
    override val id = MessageId.CLEAR_SLOT_REQUEST
    override fun serialize() = LeWriter(2).u8(id).u8(slot).build()
}

data class StartFileUploadRequest(val fileName: String, val slot: Int, val crc: Long) : HubRequest {
    override val id = MessageId.START_FILE_UPLOAD_REQUEST
    override fun serialize(): ByteArray {
        require(fileName.encodeToByteArray().size <= 31) { "File name too long" }
        return LeWriter().u8(id).cString(fileName).u8(slot).u32(crc).build()
    }
}

data class TransferChunkRequest(val runningCrc: Long, val chunk: ByteArray) : HubRequest {
    override val id = MessageId.TRANSFER_CHUNK_REQUEST
    override fun serialize() =
        LeWriter(chunk.size + 8).u8(id).u32(runningCrc).u16(chunk.size).bytes(chunk).build()

    override fun equals(other: Any?): Boolean =
        this === other || (other is TransferChunkRequest &&
            runningCrc == other.runningCrc && chunk.contentEquals(other.chunk))

    override fun hashCode(): Int = 31 * runningCrc.hashCode() + chunk.contentHashCode()
}

/** @param stop false starts the program in [slot], true stops it. */
data class ProgramFlowRequest(val stop: Boolean, val slot: Int) : HubRequest {
    override val id = MessageId.PROGRAM_FLOW_REQUEST
    override fun serialize() = LeWriter(3).u8(id).u8(if (stop) 1 else 0).u8(slot).build()
}

/** @param intervalMs 0 disables device notifications. */
data class DeviceNotificationRequest(val intervalMs: Int) : HubRequest {
    override val id = MessageId.DEVICE_NOTIFICATION_REQUEST
    override fun serialize() = LeWriter(3).u8(id).u16(intervalMs).build()
}

/** Arbitrary payload delivered to the stdin of the program running on the hub. */
data class TunnelMessage(val payload: ByteArray) : HubRequest {
    override val id = MessageId.TUNNEL_MESSAGE
    override fun serialize() = LeWriter(payload.size + 3).u8(id).u16(payload.size).bytes(payload).build()

    override fun equals(other: Any?): Boolean =
        this === other || (other is TunnelMessage && payload.contentEquals(other.payload))

    override fun hashCode(): Int = payload.contentHashCode()
}

data class SetHubNameRequest(val name: String) : HubRequest {
    override val id = MessageId.SET_HUB_NAME_REQUEST
    override fun serialize() = LeWriter(32).u8(id).fixedString(name, 31).build()
}

data object GetHubNameRequest : HubRequest {
    override val id = MessageId.GET_HUB_NAME_REQUEST
    override fun serialize() = byteArrayOf(id.toByte())
}

data object DeviceUuidRequest : HubRequest {
    override val id = MessageId.DEVICE_UUID_REQUEST
    override fun serialize() = byteArrayOf(id.toByte())
}

/** A message received from the hub. */
sealed interface HubMessage {
    val id: Int
}

/** Any of the simple acknowledgement responses. */
data class StatusResponse(override val id: Int, val success: Boolean) : HubMessage

data class InfoResponse(
    val rpcMajor: Int,
    val rpcMinor: Int,
    val rpcBuild: Int,
    val firmwareMajor: Int,
    val firmwareMinor: Int,
    val firmwareBuild: Int,
    val maxPacketSize: Int,
    val maxMessageSize: Int,
    val maxChunkSize: Int,
    val productGroupDevice: Int,
) : HubMessage {
    override val id = MessageId.INFO_RESPONSE
    val firmwareVersion: String get() = "$firmwareMajor.$firmwareMinor.$firmwareBuild"
    val rpcVersion: String get() = "$rpcMajor.$rpcMinor.$rpcBuild"
}

data class ProgramFlowNotification(val stop: Boolean) : HubMessage {
    override val id = MessageId.PROGRAM_FLOW_NOTIFICATION
}

data class ConsoleNotification(val text: String) : HubMessage {
    override val id = MessageId.CONSOLE_NOTIFICATION
}

data class HubNameResponse(val name: String) : HubMessage {
    override val id = MessageId.GET_HUB_NAME_RESPONSE
}

data class DeviceUuidResponse(val uuid: String) : HubMessage {
    override val id = MessageId.DEVICE_UUID_RESPONSE
}

data class TunnelNotification(val payload: ByteArray) : HubMessage {
    override val id = MessageId.TUNNEL_MESSAGE

    override fun equals(other: Any?): Boolean =
        this === other || (other is TunnelNotification && payload.contentEquals(other.payload))

    override fun hashCode(): Int = payload.contentHashCode()
}

/** A message the app does not know how to interpret. */
data class UnknownMessage(override val id: Int, val raw: ByteArray) : HubMessage {
    override fun equals(other: Any?): Boolean =
        this === other || (other is UnknownMessage && id == other.id && raw.contentEquals(other.raw))

    override fun hashCode(): Int = 31 * id + raw.contentHashCode()
}

/** One entry inside a [DeviceNotification]. */
sealed interface DeviceUpdate {
    data class Battery(val percent: Int) : DeviceUpdate
    data class Imu(
        val upFace: Int,
        val yawFace: Int,
        val yaw: Int,
        val pitch: Int,
        val roll: Int,
        val accelX: Int,
        val accelY: Int,
        val accelZ: Int,
        val gyroX: Int,
        val gyroY: Int,
        val gyroZ: Int,
    ) : DeviceUpdate

    data class Matrix5x5(val pixels: IntArray) : DeviceUpdate {
        override fun equals(other: Any?) =
            this === other || (other is Matrix5x5 && pixels.contentEquals(other.pixels))
        override fun hashCode() = pixels.contentHashCode()
    }

    data class Motor(
        val port: Int,
        val deviceType: Int,
        val absolutePosition: Int,
        val power: Int,
        val speed: Int,
        val position: Int,
    ) : DeviceUpdate

    data class Force(val port: Int, val value: Int, val pressed: Boolean) : DeviceUpdate
    data class Color(val port: Int, val color: Int, val red: Int, val green: Int, val blue: Int) : DeviceUpdate
    data class Distance(val port: Int, val distance: Int) : DeviceUpdate

    data class Matrix3x3(val port: Int, val pixels: IntArray) : DeviceUpdate {
        override fun equals(other: Any?) =
            this === other || (other is Matrix3x3 && port == other.port && pixels.contentEquals(other.pixels))
        override fun hashCode() = 31 * port + pixels.contentHashCode()
    }
}

data class DeviceNotification(val updates: List<DeviceUpdate>) : HubMessage {
    override val id = MessageId.DEVICE_NOTIFICATION
}

object Messages {

    /** Deserialize a complete, unpacked message payload received from the hub. */
    fun deserialize(data: ByteArray): HubMessage {
        if (data.isEmpty()) return UnknownMessage(-1, data)
        val r = LeReader(data)
        return when (val id = r.u8()) {
            MessageId.INFO_RESPONSE -> InfoResponse(
                rpcMajor = r.u8(),
                rpcMinor = r.u8(),
                rpcBuild = r.u16(),
                firmwareMajor = r.u8(),
                firmwareMinor = r.u8(),
                firmwareBuild = r.u16(),
                maxPacketSize = r.u16(),
                maxMessageSize = r.u16(),
                maxChunkSize = r.u16(),
                productGroupDevice = r.u16(),
            )

            MessageId.START_FILE_UPLOAD_RESPONSE,
            MessageId.TRANSFER_CHUNK_RESPONSE,
            MessageId.PROGRAM_FLOW_RESPONSE,
            MessageId.DEVICE_NOTIFICATION_RESPONSE,
            MessageId.CLEAR_SLOT_RESPONSE,
            MessageId.SET_HUB_NAME_RESPONSE,
            -> StatusResponse(id, r.u8() == 0x00)

            MessageId.PROGRAM_FLOW_NOTIFICATION -> ProgramFlowNotification(r.u8() != 0)

            MessageId.CONSOLE_NOTIFICATION -> ConsoleNotification(decodeCString(r.rest()))

            MessageId.GET_HUB_NAME_RESPONSE -> HubNameResponse(decodeCString(r.rest()))

            MessageId.DEVICE_UUID_RESPONSE -> DeviceUuidResponse(
                r.bytes(minOf(16, r.remaining)).joinToString("") { b ->
                    val v = b.toInt() and 0xFF
                    "0123456789abcdef"[v shr 4].toString() + "0123456789abcdef"[v and 0xF]
                }
            )

            MessageId.TUNNEL_MESSAGE -> {
                val size = r.u16()
                TunnelNotification(r.bytes(minOf(size, r.remaining)))
            }

            MessageId.DEVICE_NOTIFICATION -> {
                val size = r.u16()
                DeviceNotification(parseDeviceUpdates(r.bytes(minOf(size, r.remaining))))
            }

            else -> UnknownMessage(id, data)
        }
    }

    private fun decodeCString(raw: ByteArray): String {
        var end = raw.size
        while (end > 0 && raw[end - 1] == 0.toByte()) end--
        return raw.copyOf(end).decodeToString()
    }

    internal fun parseDeviceUpdates(payload: ByteArray): List<DeviceUpdate> {
        val out = ArrayList<DeviceUpdate>()
        val r = LeReader(payload)
        while (r.remaining > 0) {
            val type = r.u8()
            val needed = when (type) {
                0x00 -> 1
                0x01 -> 20
                0x02 -> 25
                0x0A -> 11
                0x0B -> 3
                0x0C -> 8
                0x0D -> 3
                0x0E -> 10
                else -> -1
            }
            if (needed < 0 || r.remaining < needed) break // unknown or truncated: stop parsing
            when (type) {
                0x00 -> out.add(DeviceUpdate.Battery(r.u8()))
                0x01 -> out.add(
                    DeviceUpdate.Imu(
                        upFace = r.u8(), yawFace = r.u8(),
                        yaw = r.i16(), pitch = r.i16(), roll = r.i16(),
                        accelX = r.i16(), accelY = r.i16(), accelZ = r.i16(),
                        gyroX = r.i16(), gyroY = r.i16(), gyroZ = r.i16(),
                    )
                )
                0x02 -> out.add(DeviceUpdate.Matrix5x5(IntArray(25) { r.u8() }))
                0x0A -> out.add(
                    DeviceUpdate.Motor(
                        port = r.u8(), deviceType = r.u8(),
                        absolutePosition = r.i16(), power = r.i16(),
                        speed = r.i8(), position = r.i32(),
                    )
                )
                0x0B -> out.add(DeviceUpdate.Force(port = r.u8(), value = r.u8(), pressed = r.u8() != 0))
                0x0C -> out.add(
                    DeviceUpdate.Color(
                        port = r.u8(), color = r.i8(),
                        red = r.u16(), green = r.u16(), blue = r.u16(),
                    )
                )
                0x0D -> out.add(DeviceUpdate.Distance(port = r.u8(), distance = r.i16()))
                0x0E -> out.add(DeviceUpdate.Matrix3x3(port = r.u8(), pixels = IntArray(9) { r.u8() }))
            }
        }
        return out
    }
}

/** LEGO motor device type ids, used to scale a percentage into degrees/second. */
object MotorType {
    const val MEDIUM = 0x30
    const val LARGE = 0x31
    const val SMALL = 0x41

    /** Maximum velocity in degrees/second, per LEGO's SPIKE 3 Python API documentation. */
    fun maxVelocity(deviceType: Int): Int = when (deviceType) {
        SMALL -> 660
        MEDIUM -> 1110
        LARGE -> 1050
        else -> 1000
    }

    fun name(deviceType: Int): String = when (deviceType) {
        SMALL -> "Small"
        MEDIUM -> "Medium"
        LARGE -> "Large"
        else -> "Motor"
    }
}
