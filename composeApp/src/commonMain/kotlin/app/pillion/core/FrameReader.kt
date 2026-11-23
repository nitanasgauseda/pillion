package app.pillion.core

import app.pillion.protocol.NaviLiteCodec

/** A frame parsed off the wire. */
data class NaviFrameView(val serviceType: Int, val payload: ByteArray)

/**
 * Reads complete NaviLite frames from a [ByteChannel]. Single responsibility: framing/resync.
 * It owns no transport and no protocol semantics beyond locating frame boundaries.
 *
 * Bytes live in one growable buffer addressed by [start]/[end]: consuming a frame or skipping a
 * garbage byte only moves [start], and data is compacted to the front only when more room is
 * needed — no per-read or per-frame copy of the whole backlog.
 */
class FrameReader(private val channel: ByteChannel) {
    private var buf = ByteArray(INITIAL_CAPACITY)
    private var start = 0
    private var end = 0
    private val tmp = ByteArray(8192)

    private val available: Int get() = end - start

    fun next(): NaviFrameView {
        val minFrame = NaviLiteCodec.HEADER_SIZE + NaviLiteCodec.CRC_SIZE
        fill(minFrame)
        while (!NaviLiteCodec.hasMagicAt(buf, start)) {
            start++ // resync: drop one byte, then make sure a full header is buffered again
            fill(minFrame)
        }
        val len = NaviLiteCodec.frameLengthAt(buf, start)
        check(len >= minFrame && len <= MAX_FRAME) { "bad frame length $len" }
        fill(len)
        val view = NaviFrameView(NaviLiteCodec.serviceTypeAt(buf, start), NaviLiteCodec.payloadAt(buf, start))
        start += len
        if (start == end) { start = 0; end = 0 }
        return view
    }

    /** Block until at least [n] unconsumed bytes are buffered. */
    private fun fill(n: Int) {
        while (available < n) {
            val r = channel.read(tmp)
            if (r < 0) error("channel closed")
            if (r > 0) append(r)
        }
    }

    private fun append(count: Int) {
        if (end + count > buf.size) {
            val needed = available + count
            if (needed > buf.size) {
                var cap = buf.size
                while (cap < needed) cap *= 2
                val grown = ByteArray(cap)
                buf.copyInto(grown, 0, start, end)
                buf = grown
            } else {
                buf.copyInto(buf, 0, start, end) // compact in place
            }
            end -= start
            start = 0
        }
        tmp.copyInto(buf, end, 0, count)
        end += count
    }

    private companion object {
        const val INITIAL_CAPACITY = 16 * 1024
        /** Inbound frames are small control messages; reject absurd lengths instead of buffering forever. */
        const val MAX_FRAME = 8 * 1024 * 1024
    }
}
