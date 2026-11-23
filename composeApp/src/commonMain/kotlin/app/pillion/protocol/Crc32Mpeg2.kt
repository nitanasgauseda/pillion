package app.pillion.protocol

/**
 * CRC-32/MPEG-2: poly 0x04C11DB7, init 0xFFFFFFFF, no input/output reflection, xorout 0.
 * Single responsibility: compute the NaviLite frame checksum. Nothing else depends on framing here.
 */
internal object Crc32Mpeg2 {
    private val table: IntArray = IntArray(256).also { t ->
        for (i in 0 until 256) {
            var c = i shl 24
            repeat(8) { c = if (c and (1 shl 31) != 0) (c shl 1) xor 0x04C11DB7 else c shl 1 }
            t[i] = c
        }
    }

    fun compute(data: ByteArray): Int = update(INIT, data, 0, data.size)

    /** Continue a CRC over data[offset, offset+length) — lets a frame be checksummed in parts without concatenating. */
    fun update(crc: Int, data: ByteArray, offset: Int, length: Int): Int {
        var c = crc
        for (i in offset until offset + length) {
            c = (c shl 8) xor table[((c ushr 24) xor (data[i].toInt() and 0xff)) and 0xff]
        }
        return c
    }

    const val INIT: Int = -1 // 0xFFFFFFFF
}
