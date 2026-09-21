package dev.primeremote.core.protocol

/**
 * CRC-32 (IEEE, same polynomial as zlib/binascii.crc32) with the 4-byte zero padding
 * the SPIKE Prime file-upload protocol requires.
 *
 * Port of examples/python/crc.py from LEGO's protocol documentation.
 */
object Crc {

    private val table = IntArray(256) {
        var c = it
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
        c
    }

    /**
     * @param seed running CRC from the previous chunk (0 to start)
     * @param align pad the data with zero bytes up to a multiple of this many bytes
     * @return the CRC as an unsigned value held in a Long
     */
    fun crc32(data: ByteArray, seed: Long = 0L, align: Int = 4): Long {
        var c = (seed.toInt()).inv()
        for (b in data) {
            c = table[(c xor b.toInt()) and 0xFF] xor (c ushr 8)
        }
        val remainder = data.size % align
        if (remainder != 0) {
            repeat(align - remainder) {
                c = table[c and 0xFF] xor (c ushr 8) // XOR with 0x00
            }
        }
        return (c.inv()).toLong() and 0xFFFFFFFFL
    }
}
