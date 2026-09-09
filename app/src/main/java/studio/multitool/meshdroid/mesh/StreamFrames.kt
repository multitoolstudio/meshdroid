package studio.multitool.meshdroid.mesh

import java.io.InputStream
import java.io.OutputStream

/**
 * The framing meshtasticd uses on its TCP and serial streams.
 *
 * Each frame is 0x94, 0xc3, then a 16 bit big-endian payload length, then that many bytes
 * of a serialised ToRadio or FromRadio. The two magic bytes exist so a reader that joins
 * mid-stream, or one that has been fed console output, can resync on a frame boundary.
 * Anything before a valid header is discarded rather than treated as an error, which is
 * what the firmware's own reader does.
 */
object StreamFrames {
    const val START1 = 0x94
    const val START2 = 0xc3
    const val MAX_PAYLOAD = 512   // MAX_TO_FROM_RADIO_SIZE in the firmware

    fun write(out: OutputStream, payload: ByteArray) {
        require(payload.size <= MAX_PAYLOAD) { "frame of ${payload.size} bytes exceeds $MAX_PAYLOAD" }
        out.write(
            byteArrayOf(
                START1.toByte(), START2.toByte(),
                ((payload.size shr 8) and 0xff).toByte(), (payload.size and 0xff).toByte()
            )
        )
        out.write(payload)
        out.flush()
    }

    /**
     * Reads one frame, discarding bytes until a header appears. Returns null at end of
     * stream.
     *
     * Two cases the naive version gets wrong. A byte that fails the second position can
     * itself be the start of the real header, so 0x94 0x94 0xc3 has to stay in the hunt
     * rather than restart from scratch. And a length above MAX_PAYLOAD means those two
     * bytes were data that happened to look like a header, so the search resumes instead
     * of trusting the length and swallowing the rest of the stream.
     */
    fun read(input: InputStream): ByteArray? {
        var sawStart1 = false
        while (true) {
            val b = input.read()
            if (b < 0) return null

            if (!sawStart1) {
                sawStart1 = b == START1
                continue
            }
            if (b != START2) {
                sawStart1 = b == START1
                continue
            }
            sawStart1 = false

            val hi = input.read(); val lo = input.read()
            if (hi < 0 || lo < 0) return null
            val len = (hi shl 8) or lo
            if (len > MAX_PAYLOAD) continue

            val buf = ByteArray(len)
            var read = 0
            while (read < len) {
                val n = input.read(buf, read, len - read)
                if (n < 0) return null
                read += n
            }
            return buf
        }
    }
}
