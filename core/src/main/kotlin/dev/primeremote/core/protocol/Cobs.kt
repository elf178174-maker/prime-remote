package dev.primeremote.core.protocol

/**
 * Consistent Overhead Byte Stuffing, in the variant used by the SPIKE Prime BLE protocol.
 *
 * This is a direct port of LEGO's reference implementation
 * (https://github.com/LEGO/spike-prime-docs, examples/python/cobs.py) and is verified
 * byte-for-byte against it by [dev.primeremote.core.CobsGoldenTest].
 */
object Cobs {

    /** Delimiter used to mark end of frame. */
    const val DELIMITER: Byte = 0x02

    /** Code word indicating no delimiter in block. */
    private const val NO_DELIMITER = 0xFF

    /** Offset added to the code word. */
    private const val CODE_OFFSET = 0x02

    /** Maximum block size (incl. code word). */
    private const val MAX_BLOCK_SIZE = 84

    /** XOR mask applied to the whole frame so that it can never contain 0x03 (ctrl-C). */
    private const val XOR = 3

    fun encode(data: ByteArray): ByteArray {
        val buffer = ArrayList<Int>(data.size + data.size / MAX_BLOCK_SIZE + 2)
        var codeIndex = 0
        var block = 0

        fun beginBlock() {
            codeIndex = buffer.size
            buffer.add(NO_DELIMITER) // placeholder, patched when the block is closed
            block = 1
        }

        beginBlock()
        for (raw in data) {
            val byte = raw.toInt() and 0xFF
            if (byte > CODE_OFFSET) {
                buffer.add(byte)
                block++
            }
            if (byte <= CODE_OFFSET || block > MAX_BLOCK_SIZE) {
                if (byte <= CODE_OFFSET) {
                    val delimiterBase = byte * MAX_BLOCK_SIZE
                    val blockOffset = block + CODE_OFFSET
                    buffer[codeIndex] = delimiterBase + blockOffset
                }
                beginBlock()
            }
        }
        buffer[codeIndex] = block + CODE_OFFSET

        val out = ByteArray(buffer.size)
        for (i in buffer.indices) out[i] = buffer[i].toByte()
        return out
    }

    fun decode(data: ByteArray): ByteArray {
        if (data.isEmpty()) return ByteArray(0)
        val buffer = ArrayList<Byte>(data.size)

        // value == -1 means "no delimiter value for this block"
        var value: Int
        var block: Int

        fun unescape(code: Int): Pair<Int, Int> {
            if (code == 0xFF) return -1 to (MAX_BLOCK_SIZE + 1)
            var v = (code - CODE_OFFSET) / MAX_BLOCK_SIZE
            var b = (code - CODE_OFFSET) % MAX_BLOCK_SIZE
            if (b == 0) {
                b = MAX_BLOCK_SIZE
                v -= 1
            }
            return v to b
        }

        var (v0, b0) = unescape(data[0].toInt() and 0xFF)
        value = v0
        block = b0

        for (i in 1 until data.size) {
            val byte = data[i]
            block--
            if (block > 0) {
                buffer.add(byte)
                continue
            }
            if (value != -1) buffer.add(value.toByte())
            val (v, b) = unescape(byte.toInt() and 0xFF)
            value = v
            block = b
        }

        val out = ByteArray(buffer.size)
        for (i in buffer.indices) out[i] = buffer[i]
        return out
    }

    /** Encode, XOR-mask and append the frame delimiter. Result is ready to write to the hub. */
    fun pack(data: ByteArray): ByteArray {
        val encoded = encode(data)
        val out = ByteArray(encoded.size + 1)
        for (i in encoded.indices) out[i] = (encoded[i].toInt() xor XOR).toByte()
        out[encoded.size] = DELIMITER
        return out
    }

    /** Strip framing/XOR and decode a complete frame received from the hub. */
    fun unpack(frame: ByteArray): ByteArray {
        if (frame.isEmpty()) return ByteArray(0)
        var start = 0
        if (frame[0] == 0x01.toByte()) start++ // unused priority byte
        val end = frame.size - 1 // drop trailing delimiter
        if (end <= start) return ByteArray(0)
        val unframed = ByteArray(end - start)
        for (i in unframed.indices) unframed[i] = (frame[start + i].toInt() xor XOR).toByte()
        return decode(unframed)
    }
}
