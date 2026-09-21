package dev.primeremote.core.protocol

/**
 * Reassembles BLE notifications into complete protocol frames.
 *
 * A single notification from the hub is not guaranteed to be a whole message: large
 * messages (a [DeviceNotification] with many attached devices, or a long console line)
 * arrive split across several notifications, and more than one frame can share one
 * notification. Frames are terminated by [Cobs.DELIMITER], which the COBS+XOR encoding
 * guarantees never appears inside a frame.
 */
class FrameAssembler(private val maxFrameSize: Int = 8 * 1024) {

    private var buffer = ByteArray(256)
    private var size = 0
    private var dropping = false

    /** Number of frames discarded because they exceeded [maxFrameSize]. */
    var overflowCount: Int = 0
        private set

    /** Feed one notification; returns every complete frame it completed (delimiter included). */
    fun feed(data: ByteArray): List<ByteArray> {
        if (data.isEmpty()) return emptyList()
        val frames = ArrayList<ByteArray>(1)
        for (b in data) {
            if (b == Cobs.DELIMITER) {
                if (!dropping && size > 0) {
                    append(b)
                    frames.add(buffer.copyOf(size))
                }
                size = 0
                dropping = false
                continue
            }
            if (dropping) continue
            if (size >= maxFrameSize) {
                // Runaway frame: drop until the next delimiter rather than growing forever.
                dropping = true
                size = 0
                overflowCount++
                continue
            }
            append(b)
        }
        return frames
    }

    fun reset() {
        size = 0
        dropping = false
    }

    private fun append(b: Byte) {
        if (size == buffer.size) buffer = buffer.copyOf(buffer.size * 2)
        buffer[size++] = b
    }
}
